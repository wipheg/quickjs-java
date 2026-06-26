package com.bfo.quickjs;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.charset.*;

/**
 * A JS Context is the context within which all javascript operations are
 * run. Contexts within the same runtime share code but not data. The context
 * also represents the "globalThis" object and so can be iterated just like
 * a regular JSObject.
 */
public class JSContext extends AbstractMap<String,Object> implements AutoCloseable {

    private final JSRuntime runtime;
    private final List<AutoCloseable> closeables = new ArrayList<>();   // Dependents that are closed when we're closed
    private final List<Object> proxies = new ArrayList<>();
    private final Map<Object,JSObject> exportProxies = new HashMap<>(); // Map of @JSExport-implementing-Object => JSObject
    private final Packer packer;
    private final Deque<Runnable> pollqueue = new ConcurrentLinkedDeque<Runnable>();
    private final JSObject globals;
    private volatile long pointer;
    private volatile int generation;
    private AtomicBoolean pollQueued = new AtomicBoolean();     // No point polling more than once in each event loop


    JSContext(JSRuntime runtime) {
        // Always called on correct thread
        this.runtime = runtime;
        this.packer = new Packer(this);
        this.pointer = runtime.fnContextCreate();
        this.globals = (JSObject)unpack(runtime.fnGlobals(JSContext.this));
        proxies.add(null);      // index 0 is never used
    }

    /**
     * Return the runtime for the context
     */
    public JSRuntime getRuntime() {
        return runtime;
    }

    /**
     * Return the pointer to the Context, or 0 if the context is closed and should no longer be used
     */
    public long getPointer() {
        return pointer;
    }

    final boolean isClosed() {
        return pointer == 0;
    }

    /**
     * Adds a resource depending on this context that will be closed before this context closes
     * @param resource Resource to close
     */
    void addCloseable(AutoCloseable resource) {
        closeables.add(resource);
    }


    @SuppressWarnings("unchecked") <T> T getProxy(int index, Class<T> type) {
        if (isClosed()) {
            throw new IllegalStateException("Closed");
        }
        synchronized(proxies) {
            if (index < 1 || index >= proxies.size()) {
                throw new IllegalArgumentException("Invalid proxy index " + index + " (1.." + (proxies.size() - 1) + ")");
            }
            Object o = proxies.get(index);
            if (!type.isAssignableFrom(o.getClass())) {
                throw new IllegalArgumentException("Invalid proxy index " + index + " (wrong type)");
            }
            return (T)o;
        }
    }

    int registerProxy(Object o) {
        if (isClosed()) {
            throw new IllegalStateException("Closed");
        }
        synchronized(proxies) {
            if (o == null) {
                throw new NullPointerException();
            }
            int ix = proxies.indexOf(o);
            if (ix > 0) {
                return ix;
            }
            ix = proxies.size();
            if (o instanceof JSFunction) {
                ((JSFunction)o).setIndex(ix);       // only for debug
            }
            proxies.add(o);
            runtime.getLogger().log(JSLogger.DEBUG, "Register {} as proxy {} {}", o, ix);
            return ix;
        }
    }

    /**
     * A map mapping objects with @JSExport to their matching JSObject proxies
     */
    Map<Object,JSObject> getExportProxies() {
        return exportProxies;
    }

    /**
     * Close the Context
     */
    @Override public void close() throws Exception {
        if (!isClosed()) {
            runtime.closeContext(this, new Runnable() {
                public void run() {
                    if (!isClosed()) {
                        // Always called on correct thread
                        runtime.getLogger().log(JSLogger.DEBUG, "Closing Context {}", getPointer());
                        try {
                            globals.close();
                            for (AutoCloseable resource : closeables) {
                                if (resource != null) {
                                    resource.close();
                                }
                            }
                        } catch (Exception e) {}
                        closeables.clear();
                        runtime.fnContextClose(JSContext.this);
                        pointer = 0;
                    }
                }
            });
        }
    }

    byte[] pack(Object object) {
        return packer.pack(object);
    }

    Object unpack(byte[] data){
        return packer.unpack(data);
    }

    /**
     * Create a new Array within this context
     */
    public JSArray newArray() {
        return new JSArray(this, getRuntime().fnArrayCreate(this));
    }

    /**
     * Create a new Object within this context
     */
    public JSObject newObject() {
        return new JSObject(this, getRuntime().fnObjectCreate(this));
    }

    /**
     * Called to create a new JSPromise because one has been created on WASM and we need a local proxy
     */
    JSPromise newPromise(long pointer) {
        // Called by WASM so always called on correct thread
        synchronized(proxies) {
            if (pointer == 0) {
                throw new IllegalArgumentException("Invalid pointer");
            }
            int index = proxies.size();
            final JSPromise p = new JSPromise(this, pointer, index);
            notifyPromiseCreated(p);
            registerProxy(p);
            return p;
        }
    }

    /**
     * Called to create a new JSPromise because one needs to be been created on WASM as a proxy for a Java future
     */
    JSPromise newPromise(final CompletionStage<Object> proxy) {
        // May be called on any thread.
        JSPromise p;
        synchronized(proxies) {
            int index = proxies.size();
            p = new JSPromise(this, getRuntime().fnPromiseCreate(this, index), index);
            notifyPromiseCreated(p);
            registerProxy(p);
        }
        proxy.handle(new BiFunction<Object,Throwable,Object>() {
            public Object apply(final Object value, final Throwable ex) {
                runtime.getLogger().log(JSLogger.DEBUG, "Completed {}, notifying proxy {}", proxy, p);
                if (ex != null) {
                    return p.completeExceptionally(ex);
                } else {
                    return p.complete(value);
                }
            }
        });
        return p;
    }

    // Next three all we need to implement AbstractMap

    @Override public Object put(String key, Object value) {
        return globals.put(key, value);
    }

    @Override public int size() {
        return globals.size();
    }

    @Override public Set<Map.Entry<String,Object>> entrySet() {
        return globals.entrySet();
    }

    /**
     * Eval the supplied script and return the result immediately.
     * @param script the script
     */
    public Object evalNow(String script) {
        try {
            return runtime.doNow(new Task<Object>("eval " + getPointer()) {
                public void run() {
                    final Task<Object> task = this;
                    bump();
                    byte[] data = getRuntime().fnEvalScript(JSContext.this, script);
                    Object o = unpack(data);
                    if (o instanceof RuntimeException) {
                        completeExceptionally((RuntimeException)o);
                    } else {
                        complete(o);
                    }
                }
            }).get();
        } catch (Exception e) {
            throw JSRuntime.toRuntimeException(e);
        }
    }

    /**
     * Eval the supplied script and return a Future to the result.
     * @param script the script
     */
    public CompletableFuture<Object> eval(String script) {
        return runtime.doLater(new Task<Object>("eval " + getPointer()) {
            public void run() {
                final Task<Object> task = this;
                final int proxySize0 = proxies.size();
                lastAsyncTask = this;
                byte[] data = runtime.fnEvalScriptAsync(JSContext.this, script);
                Object o = unpack(data);
                if (o instanceof RuntimeException) {
                    completeExceptionally((RuntimeException)o);
                } else {
                    bump();
                    final JSPromise promise = (JSPromise)o;
                    promise.handle((Object result, Throwable error) -> {
                        if (error != null) {
                            task.completeExceptionally(error);
                        } else {
                            task.completeOrChain(result);
                        }
                        return null;
                    });
                }
            }
        });
    }

    /**
     * Poll the context to run any queued tasks. This is normally done automatically, but
     * needs to be called manually with {@link TaskManager#useCurrentThread}
     */
    public void poll() {
        if (pollQueued.compareAndSet(false, true)) {
            runtime.doLater(new Task<Void>("poll " + getPointer()) {     // Always doLater
                public void run() {
                    if (!isClosed()) {
                        pollQueued.set(false);
                        bump();
                        if (runtime.fnPoll(JSContext.this)) {
                            poll();
                        }
                    }
                    complete(null);
                }
            });
        }
    }

    void bump() {
        generation++;
    }

    int getGeneration() {
        return generation;
    }

    //--------------------------------------------------------------------------------
    // Promises, promises
    //--------------------------------------------------------------------------------
    // Broadly a JSPromise is a Java proxy for a JavaScript Promise. Completing one
    // completes the other.
    //
    // * When Java creates a Future and that is sent to JS, a JSPromise is created as
    //   a proxy and "notifyPromiseCreated" is called. Completing the future completes
    //   the proxy.
    //
    // * When JS creates a Promise, it notifys Java and "notifyPromiseCreated" is called.
    //
    // * When JS completes a promise it notifies Java and the "notifyPromiseCompleted"
    //   method is called, with "completedByJS" true
    //
    // * When Java completes a future, the proxy JSPromise is completed immedfiately
    //   and "notifyPromiseCompleted" is called with "completedByJS" false. JS is notified
    //   the promise is completed shortly on the next eventloop call.
    //
    // * At any point JS can notify Java that something has failed in an async block.
    //   We need to notify the original Task that triggered it that it has failed. To do
    //   this, whenever an async Task is created we record it, and any promises created
    //   as a direct result of that call are linked to that Task. If one of those promises
    //   completes, we notify JS it has completed - and any promises created as a direct
    //   result of the poll() that immediately follows that notification as ALSO linked
    //   to the original Task.
    //   If a Promise N steps down the line fails during a poll(), Java is notified and the
    //   notifyUnhandledRejectedPromise() method is called - we know which Task triggered
    //   the chain of events that led to that poll(), so we can fail the Task.
    //   
    //--------------------------------------------------------------------------------

    private Task<?> lastAsyncTask; // The Task associated with the last poll() or eval()

    /**
     * Called by the JS if an unhandled exception occurs during an async method call
     */
    void notifyUnhandledRejectedPromise(byte[] data, boolean handled) {
        JSException pendingRejection = (JSException)unpack(data);
        boolean pendingRejectionHandled = handled;
        runtime.getLogger().log(JSLogger.DEBUG, "Unhandled exception originally from {}", lastAsyncTask);
        lastAsyncTask.completeExceptionally(pendingRejection);
    }

    /**
     * Called when a promise is created, either from JS or when we create one
     */
    void notifyPromiseCreated(JSPromise promise) {
        promise.setTask(lastAsyncTask);
        runtime.getLogger().log(JSLogger.DEBUG, "Created {}", promise);
        poll();
    }

    /**
     * Called when a promise is completed, which may happen on the worker thread (if the promise
     * was completed by JS) or any thread (if the promise is a proxy for a local Future
     * @param promise the promise
     * @param value the value, which may be anything including null
     * @param ex the exception, which will be non-null if the promise was rejected
     * @param completedByJS if true JS has told us the promise was completed, and doesn't need to be told again.
     */
    void notifyPromiseCompleted(final JSPromise promise, final Object value, final Throwable ex, boolean completedByJS) {
        runtime.getLogger().log(JSLogger.DEBUG, "Completed {}", promise);
        if (!completedByJS) {
            runtime.doLater(new Task<Void>("complete " + promise) {
                public void run() {
                    lastAsyncTask = promise.getTask();
                    if (ex != null) {
                        byte[] data = pack(ex);
                        runtime.fnPromiseResolve(promise, data);
                    } else {
                        byte[] data = pack(value);
                        runtime.fnPromiseResolve(promise, data);
                    }
                    pollQueued.set(false);
                    bump();
                    if (runtime.fnPoll(JSContext.this)) {
                        poll();
                    }
                    complete(null);
                }
            });
        } else {
            poll();
        }
    }

}
