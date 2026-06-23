package com.bfo.quickjs;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.charset.*;

/**
 * A JS Context is the context within which all javascript operations are
 * run. Contexts within the same runtime share code but not data.
 */
public class JSContext implements AutoCloseable {

    private final JSRuntime runtime;
    private final List<AutoCloseable> closeables = new ArrayList<>();   // Dependents that are closed when we're closed
    private final List<Object> proxies = new ArrayList<>();
    private final Packer packer;
    private final Deque<Runnable> pollqueue = new ConcurrentLinkedDeque<Runnable>();
    private long pointer;
    private int generation;


    JSContext(JSRuntime runtime) {
        this.runtime = runtime;
        this.packer = new Packer(this);
        this.pointer = runtime.fnContextCreate();
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

    int registerProxy(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        int ix = proxies.indexOf(o);
        if (ix > 0) {
            return ix;
        }
        ix = proxies.size();
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

    /**
     * Put a property on the "globalThis" object for this context
     */
    public void put(String key, Object value) {
        byte[] data = pack(value);
        data = getRuntime().fnContextPut(this, key, data);
        value = unpack(data);
        if (value instanceof RuntimeException) {
            throw (RuntimeException)value;
        } else if (value != null) {
            throw new RuntimeException("Unexpected response");
        }
    }

    /**
     * Get a property frmo the "globalThis" object for this context
     */
    public Object get(String key) {
        Object value = unpack(getRuntime().fnContextGet(this, key));
        if (value instanceof RuntimeException) {
            throw (RuntimeException)value;
        }
        return value;
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
     * Eval the supplied script asynchronousely and return the result as a {@link JSPromise}
     * @param script the script
     */
    public JSPromise evalAsync(String script) {
        prepoll();
        byte[] data = getRuntime().fnEvalScriptAsync(this, script);
        Object o = unpack(data);
        if (o instanceof RuntimeException) {
            throw (RuntimeException)o;
        }
        return (JSPromise)o;
    }

    int getGeneration() {
        return generation;
    }

}
