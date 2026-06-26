package com.bfo.quickjs;

import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.InvocationTargetException;
import java.io.*;
import java.nio.charset.*;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.*;
import com.dylibso.chicory.wasm.types.*;
import com.dylibso.chicory.wasm.WasmModule;

/**
 * The JS Runtime may contain one or more JS Contexts. It is the entry point into the API
 */
public class JSRuntime implements AutoCloseable {

    private volatile Instance instance;                 // WASM instance
    private TaskManager tasker;
    private JSLogger logger;
    private long threadId;                              // The ID of the Thread that will run all our tasks. Set by TaskManager
    private final Map<Long,JSContext> contexts = new HashMap<>();
    private InputStream stdin;                          // Streams
    private OutputStream stdout, stderr;
    private volatile long pointer;                      // Pointer to the runtime in the wasm library.
    private long scriptRuntimeLimit;
    private long scriptStart;
    private int memoryLimit;

    /**
     * Create a new JSRuntime
     */
    public JSRuntime() {
    }

    /**
     * Set the {@link TaskManager} to use for this JSRuntime, or null to use the default
     * @param taskManager the taskManager
     * @throws IllegalStateException if the runtime has already started
     * @return this
     */
    public JSRuntime setTaskManager(TaskManager taskManager) {
        if (instance != null) {
            throw new IllegalStateException("Already created");
        }
        this.tasker = taskManager;
        return this;
    }

    final boolean isClosed() {
        return pointer == 0;
    }

    static RuntimeException toRuntimeException(Throwable e) {
        if (e instanceof RuntimeException) {
            return (RuntimeException)e;
        } else if (e instanceof ExecutionException) {
            return toRuntimeException(e.getCause());
        } else if (e instanceof InvocationTargetException) {
            return toRuntimeException(e.getCause());
        } else {
            throw new RuntimeException(e);
        }
    }

    void setThreadId(long id) {
        this.threadId = id;
    }

    long getThreadId() {
        return threadId;
    }

    private TaskManager getTaskManager() {
        if (tasker == null) {
            tasker = TaskManager.useSharedThread();
        }
        return tasker;
    }

    /**
     * Set the Logger
     * @param logger the logger, or null to use the default
     * @throws IllegalStateException if the runtime has already started
     * @return this
     */
    public JSRuntime setLogger(JSLogger logger) {
        if (instance != null) {
            throw new IllegalStateException("Already created");
        }
        this.logger = logger;
        return this;
    }

    /**
     * Set the InputStream to read as stdin, or null for none (the default)
     * Must be called before the first context is created
     * @param in the InputStream, or null
     * @throws IllegalStateException if the runtime has already started
     * @return this
     */
    public JSRuntime setStdin(InputStream in) {
        if (instance != null) {
            throw new IllegalStateException("Already created");
        }
        this.stdin = in;
        return this;
    }

    /**
     * Set the OutputStream to write to as stdout, or null for none (the default)
     * Must be called before the first context is created
     * @param out the OutputStream, or null
     * @throws IllegalStateException if the runtime has already started
     * @return this
     */
    public JSRuntime setStdout(OutputStream out) {
        if (instance != null) {
            throw new IllegalStateException("Already created");
        }
        this.stdout = out;
        return this;
    }

    /**
     * Set the OutputStream to write to as stderr, or null for none (the default)
     * Must be called before the first context is created
     * @param err the OutputStream, or null
     * @throws IllegalStateException if the runtime has already started
     * @return this
     */
    public JSRuntime setStderr(OutputStream err) {
        if (instance != null) {
            throw new IllegalStateException("Already created");
        }
        this.stderr = err;
        return this;
    }

    /**
     * Set the number of bytes that can be allocated.
     * Attempts to allocate more within this Runtime will throw an Exception
     * @param bytes the number of bytes that can be allocated in the runtime
     * @throws IllegalStateException if the runtime has already started
     * @return this
     */
    public JSRuntime setMemoryLimit(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("Invalid value");
        }
        memoryLimit = bytes;
        if (instance != null) {
            fnRuntimeSetMemoryLimit(memoryLimit);
        }
        return this;
    }

    /**
     * Set the number of milliseconds before a task is cancelled
     * A script that takes longer than this to excecute will be interrupted and an exception thrown.
     * @param ms the number of milliseconds that an individual script can run for in this runtime
     * @return this
     */
    public JSRuntime setRuntimeLimit(long ms) {
        scriptRuntimeLimit = Math.max(0, ms);
        return this;
    }


    /**
     * Create a new JSContext
     */
    public JSContext newContext() {
        getInstance();
        try {
            return doNow(new Task<JSContext>("create context") {
                public void run() {
                    JSContext ctx = new JSContext(JSRuntime.this);
                    contexts.put(ctx.getPointer(), ctx);
                    complete(ctx);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    void closeContext(final JSContext ctx, Runnable callback) {
        try {
            doNow(new Task<Void>("close context") {
                public void run() {
                    callback.run();
                    contexts.remove(ctx.getPointer());
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    private JSContext getContext(long id) {
        JSContext ctx = contexts.get(id);
        if (ctx == null) {
            throw new RuntimeException("Invalid Context: " + id);
        }
        return ctx;
    }

    long getPointer() {
        return pointer;
    }

    /**
     * Return the logger specified in the constructor
     */
    public JSLogger getLogger() {
        if (logger == null) {
            logger = JSLogger.toSystem();
        }
        return logger;
    }

    /**
     * Closes the runtime and all associated contexts
     */
    @Override public void close() throws Exception {
        if (!isClosed()) {
            doNow(new Task<Void>("close runtime " + getPointer()) {
                public void run() {
                    if (!isClosed()) {
                        try {
                            for (JSContext ctx : contexts.values()) {
                                ctx.close();
                            }
                        } catch (Exception e) {}
                        contexts.clear();
                        fnRuntimeClose();
                        getTaskManager().remove(JSRuntime.this);
                        pointer = 0;
                        complete(null);
                    }
                }
            }).get();
        }
    }

    //--------------------------------------------------------------------------
    // Some utility functions
    //--------------------------------------------------------------------------

    static String format(String msg, Object... args) {
        for (int i=0;i<args.length;i++) {
            int ix = msg.indexOf("{}");
            if (ix >= 0) {
                msg = msg.substring(0, ix) + toString(args[i]) + msg.substring(ix + 2);
            } else {
                break;
            }
        }
        return msg;
    }

    static String toString(Object o) {
        if (o == null) {
            return "null";
        } else if (o instanceof int[]) {
            return Arrays.toString((int[])o);
        } else if (o instanceof long[]) {
            return Arrays.toString((long[])o);
        } else if (o instanceof byte[]) {
            return "0x" + HexFormat.of().formatHex((byte[])o);
        } else if (o instanceof Object[]) {
            return Arrays.toString((Object[])o);
        } else {
            return o.toString();
        }
    }

    private HostFunction createHostFunction(final String set, final String name, final List<ValType> in, final List<ValType> out, final WasmFunctionHandle func) {
        return new HostFunction(set, name, FunctionType.of(in, out), (Instance instance, long... args) -> {
            try {
                long[] result = func.apply(instance, args);
                if (result == null) {
                    getLogger().log(JSLogger.TRACE, "hear {}.{}{} = {}", set, name, args, result);
                } else if (result.length == 1) {
                    getLogger().log(JSLogger.TRACE, "hear {}.{}{} = {} ({} {})", set, name, args, result, ptrlen2ptr(result[0]), ptrlen2len(result[0]));
                }
                return result;
            } catch (RuntimeException e) {
                getLogger().log(JSLogger.TRACE, "hear {}.{}{} = ERROR", set, name, args, e);
                return null;
            }
        });
    }

    Instance getInstance() {
        if (instance == null) {
            doLater(new Task<Void>("create instance") {
                public void run() {
                    WasiOptions.Builder optionsBuilder = WasiOptions.builder();
                    if (stdout != null) {
                        optionsBuilder = optionsBuilder.withStdout(stdout);
                    }
                    if (stderr != null) {
                        optionsBuilder = optionsBuilder.withStderr(stderr);
                    }
                    if (stdin != null) {
                        optionsBuilder = optionsBuilder.withStdin(stdin);
                    }
                    optionsBuilder = optionsBuilder.withEnvironment("RUST_BACKTRACE", "full");
                    WasiOptions options = optionsBuilder.build();
                    WasiPreview1 wasi = WasiPreview1.builder().withOptions(options).build();
                    Store store = new Store().addFunction(wasi.toHostFunctions()).addFunction(new HostFunction[] {

                        createHostFunction("env", "call_java_function", List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I64),
                            (Instance instance, long... args) -> { return new long[] { fnCallJavaFunction((int)args[0], (int)args[1], (int)args[2], (int)args[3]) }; }),

                        createHostFunction("env", "log_java", List.of(ValType.I32, ValType.I32, ValType.I32), List.of(),
                            (Instance instance, long... args) -> { fnLog((int)args[0], (int)args[1], (int)args[2]); return new long[0]; }),

                        createHostFunction("env", "js_interrupt_handler", List.of(), List.of(ValType.I32),
                            (Instance instance, long... args) -> { return new long[] { fnInterruptHandler() }; }),

                        createHostFunction("env", "create_completable_future", List.of(ValType.I64, ValType.I64), List.of(ValType.I64),
                            (Instance instance, long... args) -> { return new long[] { fnCreateCompletableFuture(args[0], args[1]) }; }),

                        createHostFunction("env", "complete_completable_future", List.of(ValType.I64, ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I64),
                            (Instance instance, long... args) -> { fnCompleteCompletableFuture(args[0], (int)args[1], (int)args[2], (int)args[3], (int)args[4]); return new long[] { 0 }; }),

                        createHostFunction("env", "handle_rejected_promise", List.of(ValType.I64, ValType.I64, ValType.I32, ValType.I32, ValType.I32), List.of(),
                            (Instance instance, long... args) -> { fnHandleRejectedPromise(args[0], args[1], (int)args[2], (int)args[3], args[4] != 0); return new long[] { 0 }; })

                    });
                    WasmModule module = WasmLib.load();
                    instance = Instance.builder(module).withImportValues(store.toImportValues()).withMachineFactory(WasmLib::create).build();
                    // instance = Instance.builder(module).withImportValues(store.toImportValues()).build();
                    pointer = fnRuntimeCreate();

                    int level;
                    for (level=JSLogger.ERROR;level<=JSLogger.TRACE && getLogger().isLoggable(level);level++);
                    fnRuntimeInitLogger(Math.min(level, JSLogger.TRACE));
                    if (memoryLimit > 0) {
                        fnRuntimeSetMemoryLimit(memoryLimit);
                    }
                    complete(null);
                }
            }).join();
        }
        return instance;
    }

    <T> Task<T>  doNow(Task<T>  task) {
        task.setRuntime(this);
        return getTaskManager().doNow(task);
    }

    <T> Task<T>  doLater(Task<T>  task) {
        task.setRuntime(this);
        return getTaskManager().doLater(task);
    }

    //--------------------------------------------------------------------------------------
    // Memory operations: store, fetch, alloc, dealloc.
    // A "long" value represents two 32-bit values (ptr and len) bundled into a 64-bit value (ptrlen)
    //--------------------------------------------------------------------------------------

    private static long ptrlen(int ptr, int len) {
        return (long) len | ((long) ptr << 32);
    }
    private static int ptrlen2ptr(long ptrlen) {
        return (int) ((ptrlen >> 32) & 0xffffffff);
    }
    private static int ptrlen2len(long ptrlen) {
        return (int) (ptrlen & 0xffffffff);
    }

    private long store(byte[] data) {
        int ptr = alloc(data.length);
        getInstance().memory().write(ptr, data);
        return ptrlen(ptr, data.length);
    }

    private byte[] fetch(int ptr, int len) {
        return getInstance().memory().readBytes(ptr, len);
    }

    private byte[] fetch(long ptrlen) {
        return fetch(ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
    }

    private int alloc(int size) {
        long[] ptr = call("alloc", size);
        return (int)ptr[0];
    }

    private void dealloc(long ptrlen) {
        dealloc(ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
    }

    private void dealloc(int ptr, int len) {
        call("dealloc", ptr, len);
    }

    private long[] call(String name, long... args) {
        ExportFunction func = getInstance().export(name);
        try {
            long[] result = func.apply(args);
            if (result == null) {
                getLogger().log(JSLogger.TRACE, "call {}{} = {}", name, args, result);
            } else if (result.length == 1) {
                getLogger().log(JSLogger.TRACE, "call {}{} = {} ({} {})", name, args, result, ptrlen2ptr(result[0]), ptrlen2len(result[0]));
            }
            return result;
        } catch (RuntimeException e) {
            getLogger().log(JSLogger.TRACE, "call {}{} = ERROR", name, args, e);
            throw e;
        }
    }

    //--------------------------------------------------------------------------------------
    // Incoming functions - registered as HostFunctions and called from Chicory
    //--------------------------------------------------------------------------------------

    /**
     * Calls the host function. This is a host function that is called from the QuickJS
     * runtime. Delegate the call to the corresponding context.
     * @param contextPtr context pointer                /// XXX why is this an int?
     * @param functionPtr function pointer
     * @param argsPtr points to message pack object
     * @param argsLen length of the message pack object
     * @return ptrlen to message-packed result
     */
    private long fnCallJavaFunction(int contextPtr, int functionIndex, int argsPtr, int argsLen) {
        // Incoming call!
        JSContext ctx = getContext(contextPtr);
        @SuppressWarnings("unchecked") Function<List<Object>,Object> function = ctx.getProxy(functionIndex, Function.class);
        byte[] response;
        try {
            byte[] data = fetch(argsPtr, argsLen);
            dealloc(argsPtr, argsLen);
            Object unpacked = ctx.unpack(data);
            @SuppressWarnings("unchecked")List<Object> argslist = unpacked instanceof List ? (List<Object>)unpacked : List.of(unpacked);
            Object result = function.apply(argslist);
            return store(ctx.pack(result));
        } catch (RuntimeException e) {
            return store(ctx.pack(e));
        }
    }

    /**
     * This callback is called regularly from the QuickJS runtime to check if there
     * is an interrupt. If it returns 1 (true), the execution is interrupted. This
     * is currently used to set a max execution time for a script
     * @return 1 if the execution should be interrupted, 0 otherwise
     */
    private int fnInterruptHandler() {
        boolean interrupt = false;
        if (scriptStart > 0 && scriptRuntimeLimit > 0) {
            long runtime = System.currentTimeMillis() - scriptStart;
            if (runtime > scriptRuntimeLimit) {
                getLogger().log(JSLogger.WARN, "Runtime {}ms exceeds limit of {}ms: interrupting", runtime, scriptRuntimeLimit);
                interrupt = true;
            }
        }
        return interrupt ? 1 : 0;
    }

    /**
     * Creates a new completable future and returns its index. Used to wrap native promises.
     * @param contextPtr context pointer
     * @param functionPtr function pointer
     * @return the pointer to the future
     */
    private long fnCreateCompletableFuture(long contextPtr, long promisePtr) {
        JSPromise future = getContext(contextPtr).newPromise(promisePtr);
        return future.getIndex();
    }

    /**
     * Complete a new completable future and returns its index. Used to wrap native promises.
     * @param contextPtr context pointer
     * @param reject if 1, reject the promise
     * @param futurePtr the pointer to the future
     * @param argPtr the pointer to the arguments
     * @param argLen the length of the arguments
     */
    private void fnCompleteCompletableFuture(long contextPtr, int reject, int futureIndex, int argPtr, int argLen) {
        JSContext ctx = getContext(contextPtr);
        @SuppressWarnings("unchecked") CompletableFuture<Object> future = ctx.getProxy(futureIndex, CompletableFuture.class);
        byte[] data = fetch(argPtr, argLen);
        dealloc(argPtr, argLen);
        Object result = ctx.unpack(data);
        getLogger().log(JSLogger.DEBUG, "{} future with value {}", (reject == 1 ? "Rejecting" : "Resolving"), result);
        if (future instanceof JSPromise) {
            ((JSPromise)future).notifyCompletedByJS();
        }
        if (reject != 0) {
            if (!(result instanceof Exception)) { // What could it be?
                result = new JSException("Promise rejected", result.toString());
            }
            future.completeExceptionally((Exception)result);
        } else {
            future.complete(result);
        }
    }

    /**
     * Logs a message from the QuickJS wasm runtime to the native logger.
     * @param level the level
     * @param messagPtr the message string pointer
     * @param messagLen the length of the message string
     */
    private void fnLog(int level, int ptr, int len) {
        byte[] data = fetch(ptr, len);
        dealloc(ptr, len);
        getLogger().log(level, new String(data, StandardCharsets.UTF_8));
    }

    private void fnHandleRejectedPromise(long contextPtr, long promisePtr, int ptr, int len, boolean isHandled) {
        JSContext ctx = getContext(contextPtr);
        JSPromise promise = ctx.newPromise(promisePtr);
        byte[] data = fetch(ptr, len);
        dealloc(ptr, len);
        ctx.notifyUnhandledRejectedPromise(data, isHandled);
    }


    //-------------------------------------------------------------------------
    // Runtime functions
    //-------------------------------------------------------------------------

    private long fnRuntimeCreate() {
        long[] r = call("create_runtime_wasm");
        return r[0];
    }

    private void fnRuntimeClose() {
        if (!isClosed()) {
            call("close_runtime_wasm", getPointer());
        }
    }

    private void fnRuntimeSetMemoryLimit(int bytes) {
        call("set_memory_limit_runtime_wasm", getPointer(), bytes);
    }

    private void fnRuntimeInitLogger(int level) {
        call("init_logger_wasm", level);
    }

    //-------------------------------------------------------------------------
    // Context functions
    //-------------------------------------------------------------------------

    /**
     * Create a context
     */
    long fnContextCreate() {
        try {
            return doNow(new Task<Long>("fnContextCreate") {
                public void run() {
                    long[] r = call("create_context_wasm", getPointer());
                    if (r[0] == 0) {
                        throw new IllegalStateException("Context creation failed");
                    }
                    complete(r[0]);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Return the Globals object for this context
     */
    byte[] fnGlobals(JSContext ctx) {
        try {
            return doNow(new Task<byte[]>("fnGlobals") {
                public void run() {
                    long[] r = call("globals_wasm", ctx.getPointer());
                    long ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Close a context
     */
    void fnContextClose(JSContext ctx) {
        try {
            doNow(new Task<Void>("fnContextClose") {
                public void run() {
                    if (!ctx.isClosed()) {
                        call("close_context_wasm", getPointer(), ctx.getPointer());
                    }
                    complete(null);
                }
            });
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    byte[] fnEvalScript(final JSContext ctx, final String script) {
        try {
            return doNow(new Task<byte[]>("fnEval") {
                public void run() {
                    long ptrlen = store(script.getBytes(StandardCharsets.UTF_8));
                    scriptStart = System.currentTimeMillis();
                    long[] r = call("eval_script_wasm", ctx.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    scriptStart = 0;
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    byte[] fnEvalScriptAsync(final JSContext ctx, final String script) {
        try {
            return doNow(new Task<byte[]>("fnEvalAsync") {
                public void run() {
                    long ptrlen = store(script.getBytes(StandardCharsets.UTF_8));
                    long[] r = call("eval_script_async_wasm", ctx.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Return true if more tasks await
     */
    boolean fnPoll(final JSContext ctx) {
        try {
            return doNow(new Task<Boolean>("fnPoll") {
                public void run() {
                    complete(call("poll_wasm", ctx.getPointer())[0] == 1);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    //-------------------------------------------------------------------------
    // Object functions
    // Note keys are serialized with msgpack (as {"string":nnn} not nnn). Not ideal.
    //-------------------------------------------------------------------------

    /**
     * Create an object
     */
    long fnObjectCreate(final JSContext ctx) {
        try {
            return doNow(new Task<Long>("fnObjectCreate") {
                public void run() {
                    long[] r = call("object_create_wasm", ctx.getPointer());
                    if (r[0] == 0) {
                        throw new IllegalStateException("Object creation failed");
                    }
                    complete(r[0]);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Close an object
     */
    void fnObjectClose(final JSObject object) {
        try {
            doNow(new Task<Void>("fnObjectSize") {
                public void run() {
                    if (!object.isClosed()) {
                        final JSContext ctx = object.getContext();
                        call("object_close_wasm", ctx.getPointer(), object.getPointer());
                    }
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Return the size of a JSObject
     */
    int fnObjectSize(final JSObject object) {
        try {
            return doNow(new Task<Integer>("fnObjectSize") {
                public void run() {
                    final JSContext ctx = object.getContext();
                    complete((int)call("object_size_wasm", ctx.getPointer(), object.getPointer())[0]);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Put a value on an object
     */
    void fnObjectPut(final JSObject object, final byte[] key, final byte[] value) {
        try {
            doNow(new Task<Void>("fnObjectPut") {
                public void run() {
                    final JSContext ctx = object.getContext();
                    long kptrlen = store(key);
                    long vptrlen = store(value);
                    call("object_set_value_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(kptrlen), ptrlen2len(kptrlen), ptrlen2ptr(vptrlen), ptrlen2len(vptrlen));
                    dealloc(kptrlen);
                    dealloc(vptrlen);
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Get a value from an object
     */
    byte[] fnObjectGet(final JSObject object, final byte[] key) {
        try {
            return doNow(new Task<byte[]>("fnObjectGet") {
                public void run() {
                    final JSContext ctx = object.getContext();
                    long ptrlen = store(key);
                    long[] r = call("object_get_value_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Remove a value from an object
     */
    void fnObjectRemove(final JSObject object, final byte[] key) {
        try {
            doNow(new Task<Void>("fnObjectKeySet") {
                public void run() {
                    final JSContext ctx = object.getContext();
                    long ptrlen = store(key);
                    call("object_remove_value_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    dealloc(ptrlen);
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Get a list of keys from an object
     */
    byte[] fnObjectKeySet(JSObject object) {
        try {
            return doNow(new Task<byte[]>("fnObjectKeySet") {
                public void run() {
                    final JSContext ctx = object.getContext();
                    long[] r = call("object_key_set_wasm", ctx.getPointer(), object.getPointer());
                    long ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Define getter/setter on a property
     * @param key the property name
     * @param getter the index to the getter funtion (required, non zero)
     * @param getter the index to the setter funtion (may be zero)
     * @param flags a bitmask: 0x01 = property is enumerable, 0x02 = property is deletable
     */
    void fnObjectDefineProperty(final JSObject object, final byte[] key, final int getter, final int setter, final int flags) {
        try {
            doNow(new Task<Void>("fnObjectDefineProperty") {
                public void run() {
                    final JSContext ctx = object.getContext();
                    long ptrlen = store(key);
                    long[] r = call("object_define_property_get_set_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen), getter, setter, flags);
                    dealloc(ptrlen);
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    //-------------------------------------------------------------------------
    // Array functions
    //-------------------------------------------------------------------------

    /**
     * Create an array
     */
    long fnArrayCreate(final JSContext ctx) {
        try {
            return doNow(new Task<Long>("fnArrayCreate") {
                public void run() {
                    long[] r = call("array_create_wasm", ctx.getPointer());
                    if (r[0] == 0) {
                        throw new IllegalStateException("Array creation failed");
                    }
                    complete(r[0]);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Return the size of the array
     */
    int fnArraySize(final JSArray array) {
        try {
            return doNow(new Task<Integer>("fnArraySize") {
                public void run() {
                    final JSContext ctx = array.getContext();
                    complete((int)call("array_size_wasm", ctx.getPointer(), array.getPointer())[0]);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Insert an item in the array
     */
    void fnArrayAdd(JSArray array, int ix, byte[] value) {
        try {
            doNow(new Task<byte[]>("fnArrayAdd") {
                public void run() {
                    final JSContext ctx = array.getContext();
                    long ptrlen = store(value);
                    call("array_add_wasm", ctx.getPointer(), array.getPointer(), ix, ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    dealloc(ptrlen);
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Set an item in the array
     */
    void fnArraySet(final JSArray array, final int ix, final byte[] value) {
        try {
            doNow(new Task<byte[]>("fnArraySet") {
                public void run() {
                    final JSContext ctx = array.getContext();
                    long ptrlen = store(value);
                    call("array_set_wasm", ctx.getPointer(), array.getPointer(), ix, ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    dealloc(ptrlen);
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Get an item from the array
     */
    byte[] fnArrayGet(final JSArray array, final int ix) {
        try {
            return doNow(new Task<byte[]>("fnArrayGet") {
                public void run() {
                    if (array.isClosed()) {
                        throw new IllegalStateException("Array closed");
                    }
                    final JSContext ctx = array.getContext();
                    long[] r = call("array_get_wasm", ctx.getPointer(), array.getPointer(), ix);
                    long ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Remove an item from the array
     */
    void fnArrayRemove(final JSArray array, final int ix) {
        try {
            doNow(new Task<Void>("fnArrayRemove") {
                public void run() {
                    if (array.isClosed()) {
                        throw new IllegalStateException("Array closed");
                    }
                    final JSContext ctx = array.getContext();
                    call("array_remove_wasm", ctx.getPointer(), array.getPointer(), ix);
                    complete(null);
                }
            });
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Free an array
     */
    void fnArrayClose(final JSArray array) {
        try {
            doNow(new Task<Void>("fnArrayClose") {
                public void run() {
                    if (!array.isClosed()) {
                        final JSContext ctx = array.getContext();
                        call("array_close_wasm", ctx.getPointer(), array.getPointer());
                    }
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    //-------------------------------------------------------------------------
    // Function functions
    //-------------------------------------------------------------------------

    /**
     * Invoke a function
     */
    byte[] fnFunctionCall(final JSFunction function, final byte[] data) {
        try {
            return doNow(new Task<byte[]>("fnFunctionCall") {
                public void run() {
                    if (function.isClosed()) {
                        throw new IllegalStateException("Function closed");
                    }
                    final JSContext ctx = function.getContext();
                    long ptrlen = store(data);
                    scriptStart = System.currentTimeMillis();
                    long[] r = call("call_function_wasm", ctx.getPointer(), function.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    scriptStart = 0;
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Invoke a function as a constructor
     */
    byte[] fnFunctionConstruct(final JSFunction function, final byte[] data) {
        try {
            return doNow(new Task<byte[]>("fnFunctionConstruct") {
                public void run() {
                    if (function.isClosed()) {
                        throw new IllegalStateException("Function closed");
                    }
                    final JSContext ctx = function.getContext();
                    long ptrlen = store(data);
                    scriptStart = System.currentTimeMillis();
                    long[] r = call("construct_function_wasm", ctx.getPointer(), function.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    scriptStart = 0;
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    byte[] data = fetch(ptrlen);
                    dealloc(ptrlen);
                    complete(data);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Return true if this function is a constructor function
     */
    boolean fnFunctionIsConstructor(final JSFunction function) {
        try {
            return doNow(new Task<Boolean>("fnFunctionClose") {
                public void run() {
                    if (function.isClosed()) {
                        throw new IllegalStateException("Function closed");
                    }
                    final JSContext ctx = function.getContext();
                    complete(call("function_is_constructor_wasm", ctx.getPointer(), function.getPointer())[0] == 1);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Free a function
     */
    void fnFunctionClose(final JSFunction function) {
        try {
            doNow(new Task<Void>("fnFunctionClose") {
                public void run() {
                    if (!function.isClosed()) {
                        final JSContext ctx = function.getContext();
                        call("close_function_wasm", ctx.getPointer(), function.getPointer());
                    }
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    //-------------------------------------------------------------------------
    // Promise functions
    //-------------------------------------------------------------------------

    /**
     * Create a promise
     */
    long fnPromiseCreate(final JSContext ctx, final int index) {
        try {
            return doNow(new Task<Long>("fnPromiseCreate") {
                public void run() {
                    if (ctx.isClosed()) {
                        throw new IllegalStateException("Context closed");
                    }
                    long[] r = call("promise_create_wasm", ctx.getPointer(), index);
                    if (r[0] == 0) {
                        completeExceptionally(new IllegalStateException("Promise creation failed"));
                    } else {
                        complete(r[0]);
                    }
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Resolve a promise
     */
    void fnPromiseResolve(final JSPromise promise, final byte[] data) {
        try {
            doNow(new Task<Void>("fnPromiseResolve") {
                public void run() {
                    if (promise.isClosed()) {
                        throw new IllegalStateException("Promise closed");
                    }
                    final JSContext ctx = promise.getContext();
                    long ptrlen = store(data);
                    scriptStart = System.currentTimeMillis();
                    long[] r = call("promise_resolve_wasm", ctx.getPointer(), promise.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    scriptStart = 0;
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Reject a promise
     */
    void fnPromiseReject(final JSPromise promise, final byte[] data) {
        try {
            doNow(new Task<Void>("fnPromiseReject") {
                public void run() {
                    if (promise.isClosed()) {
                        throw new IllegalStateException("Promise closed");
                    }
                    final JSContext ctx = promise.getContext();
                    long ptrlen = store(data);
                    scriptStart = System.currentTimeMillis();
                    long[] r = call("promise_reject_wasm", ctx.getPointer(), promise.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
                    scriptStart = 0;
                    dealloc(ptrlen);
                    ptrlen = r[0];
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    /**
     * Free a Promise
     */
    void fnPromiseClose(final JSPromise promise) {
        try {
            doNow(new Task<Void>("fnPromiseClose") {
                public void run() {
                    if (!promise.isClosed()) {
                        final JSContext ctx = promise.getContext();
                        call("promise_close_wasm", ctx.getPointer(), promise.getPointer());
                    }
                    complete(null);
                }
            }).get();
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

}
