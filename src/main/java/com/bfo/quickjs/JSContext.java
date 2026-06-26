package com.bfo.quickjs;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.*;
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
    private long pointer;
    private int generation;
    private JSException pendingRejection;
    private boolean pendingRejectionHandled;


    JSContext(JSRuntime runtime) {
        this.runtime = runtime;
        this.packer = new Packer(this);
        this.pointer = runtime.fnContextCreate();
        this.globals = (JSObject)unpack(runtime.fnGlobals(this));
        proxies.add(null);      // index 0 is never used
    }

    /**
     * Return the runtime for the context
     */
    public JSRuntime getRuntime() {
        return runtime;
    }

    /**
     * Return the pointer to the context
     */
    public long getPointer() {
        return pointer;
    }

    /**
     * Adds a resource depending on this context and needs to be closed before this context closes
     * @param resource Resource to close
     */
    void addCloseable(AutoCloseable resource) {
        closeables.add(resource);
    }

    /**
     * Add a task to run before the next time this context is polled or evaluated.
     * @param runnable the task
     */
    void pollQueue(Runnable runnable) {
        pollqueue.addLast(runnable);
    }

    /**
     * Before running poll or eval, run any queued poll operations and increase the generation
     */
    private void prepoll() {
        for (Iterator<Runnable> i = pollqueue.iterator();i.hasNext();) {
            i.next().run();
            i.remove();
        }
        generation++;
    }


    @SuppressWarnings("unchecked") <T> T getProxy(int index, Class<T> type) {
        if (index < 1 || index >= proxies.size()) {
            throw new IllegalArgumentException("Invalid proxy index " + index);
        }
        Object o = proxies.get(index);
        if (!type.isAssignableFrom(o.getClass())) {
            throw new IllegalArgumentException("Invalid proxy index " + index + " (wrong type)");
        }
        return (T)o;
    }

    Map<Object,JSObject> getExportProxies() {
        return exportProxies;
    }

    int registerProxy(Object o) {
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
        return ix;
    }

    @Override public void close() throws Exception {
        if (pointer != 0) {
            for (AutoCloseable resource : closeables) {
                if (resource != null) {
                    resource.close();
                }
            }
            closeables.clear();
            proxies.clear();
            getRuntime().fnContextClose(this);
            pointer = 0;
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
        JSArray a = new JSArray(this, getRuntime().fnArrayCreate(this));
        closeables.add(a);
        return a;
    }

    /**
     * Create a new Object within this context
     */
    public JSObject newObject() {
        JSObject o = new JSObject(this, getRuntime().fnObjectCreate(this));
        closeables.add(o);
        return o;
    }

    /**
     * Create a new {@link ByteBuffer} backed by the WebAssembly memory for this runtime.
     * The buffer can be passed to JavaScript as an ArrayBuffer without copying.
     */
    public ByteBuffer newBuffer(int size) {
        JSRuntime.BufferAllocation allocation = getRuntime().newBuffer(size);
        closeables.add(allocation);
        return allocation.buffer();
    }

    /**
     * Called to create a new JSPromise because one has been created on WASM and we need a local proxy
     */
    JSPromise newPromise(long pointer) {
        if (pointer == 0) {
            throw new IllegalArgumentException("Invalid pointer");
        }
        int index = proxies.size();
        JSPromise p = new JSPromise(this, pointer, index);
        registerProxy(p);
        return p;
    }

    /**
     * Called to create a new JSPromise because one needs to be been created on WASM as a proxy for a Java future
     */
    JSPromise newPromise(CompletionStage<Object> proxy) {
        int index = proxies.size();
        final JSPromise p = new JSPromise(this, getRuntime().fnPromiseCreate(this, index), index);
        registerProxy(p);
        proxy.handle(new BiFunction<Object,Throwable,Object>() {
            public Object apply(final Object value, final Throwable ex) {
                if (ex != null) {
                    return p.completeExceptionally(ex);
                } else {
                    return p.complete(value);
                }
            }
        });
        return p;
    }

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
     * Poll the context, returning true if there are any unresolved
     * async tasks.
     */
    public boolean poll() {
        prepoll();
        return getRuntime().fnPoll(this);
    }

    /**
     * Called on any thread to notify the JS engine that a promise has completed.
     * Queues a task to run on next call to poll()
     * @param result the result
     * @param ex if not null, the promise was rejected
     * @param finalStage if an uncaught exception is thrown as a result of a poll following the
     * promise notification, fail that promise.
     */
    void notifyPromiseCompleted(final JSPromise promise, final Object value, final Throwable ex, final CompletableFuture<?> finalStage) {
        final JSContext ctx = this;
        pollQueue(new Runnable() {
            public void run() {
                if (ex != null) {
                    byte[] data = pack(ex);
                    getRuntime().fnPromiseResolve(promise, data);
                } else {
                    byte[] data = pack(value);
                    getRuntime().fnPromiseResolve(promise, data);
                }
                CompletableFuture<?> localFinalStage = finalStage;
                while (localFinalStage != null && getRuntime().fnPoll(ctx)) {
                    generation++;
                    if (pendingRejection != null) {
                        localFinalStage.completeExceptionally(pendingRejection);
                        localFinalStage = null;
                        pendingRejection = null;
                    }
                }
            }
        });
    }

    /**
     * Eval the supplied script and return the result.
     * @param script the script
     */
    public Object eval(String script) {
        prepoll();
        byte[] data = getRuntime().fnEvalScript(this, script);
        Object o = unpack(data);
        if (o instanceof RuntimeException) {
            throw (RuntimeException)o;
        }
        return o;
    }

    /**
     * Eval the supplied script asynchronously and return the result as a {@link JSPromise}
     * @param script the script
     */
    public JSPromise evalAsync(String script) {
        pendingRejection = null;
        prepoll();
        int size = proxies.size();
        byte[] data = getRuntime().fnEvalScriptAsync(this, script);
        Object o = unpack(data);
        if (o instanceof RuntimeException) {
            throw (RuntimeException)o;
        }
        JSPromise promise = (JSPromise)o;
        if (pendingRejection != null) {
            // We've *already* had an exception from JS - we would see this
            // if the script was (eg) "throw Error()". Fail the promise now.
            if (pendingRejectionHandled) {
                promise.notifyCompletedByJS();
            }
            promise.completeExceptionally(pendingRejection);
            pendingRejection = null;
        } else {
            // Any promises created after the call to evalAsync need to be told that THIS
            // promise is the "final stage". Any unhandled exceptions seen when resolving
            // one of them must fail the final stage.
            for (int i=size;i<proxies.size();i++) {
                if (proxies.get(i) instanceof JSPromise && proxies.get(i) != promise) {
                    ((JSPromise)proxies.get(i)).setFinalStage(promise);
                }
            }
        }
        return promise;
    }

    int getGeneration() {
        return generation;
    }

    /**
     * Called by the JS if an unhandled exception occurs during an async method call
     */
    void handleRejectedPromise(byte[] data, boolean handled) {
        pendingRejection = (JSException)unpack(data);
        pendingRejectionHandled = handled;
    }

}
