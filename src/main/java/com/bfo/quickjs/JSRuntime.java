package com.bfo.quickjs;

import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.*;
import com.dylibso.chicory.wasm.types.*;
import com.dylibso.chicory.wasm.WasmModule;

/**
 * The JS Runtime may contain one or more JS Contexts. It is the entry point into the API
 */
public class JSRuntime implements AutoCloseable {
    

    private final Instance instance;          // WASM instance
    private final Map<Long,JSContext> contexts = new HashMap<>();
    private final Logger logger;
    private long pointer;                   // Pointer to the runtime in the wasm library.
    private long scriptRuntimeLimit;
    private long scriptStart;
    private int callCountLimit = 0, callCount;

    /** 
     * A simple generic Logger interface, for extensible logging
     */
    public interface Logger {
        public static final int TRACE = 5, DEBUG = 4, INFO = 3, WARN = 2, ERROR = 1;
        /**
         * Return true if the specified log level is loggable
         */
        public boolean isLoggable(int level);
         /**
          * Trivial logging interface which takes a Message string that may include "{}", one
          * or more objects to insert into that message, and an optional final argument which
          * is a Throwable. eg <code>log(INFO, "Ignoring exception from {}", source, exception);</code>
          * @param level the level
          * @param message the message template
          * @param args the arguments to insert into the template, followed by an optional exception
          */
        public void log(int level, String message, Object... args);

        /**
         * Create a new Logger that logs to the specified Appendable
         * @param maxlevel the logging level 
         * @param out the Appendable
         */
        public static Logger toStream(final int maxlevel, final Appendable out) {
            Writer w;
            if (!(out instanceof Writer)) {
                w = new Writer() {
                    @Override public void close() throws IOException {
                        if (out instanceof Closeable) {
                            ((Closeable)out).close();
                        }
                    }
                    @Override public void flush() {
                    }
                    @Override public void write(char[] buf, int off, int len) throws IOException {
                        out.append(CharBuffer.wrap(buf, off, len));
                    }
                };
            } else {
                w = (Writer)out;
            }
            final PrintWriter pw = new PrintWriter(w);
            return new Logger() {
                @Override public boolean isLoggable(int level) {
                    return level <= maxlevel;
                }
                @Override public void log(int level, String msg, Object... args) {
                    if (isLoggable(level)) {
                        Throwable e = null;
                        if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                            e = (Throwable)args[args.length - 1];
                            args = Arrays.copyOf(args, args.length - 1);
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("# [");
                        switch (level) {
                            case 1: sb.append("error"); break;
                            case 2: sb.append("warning"); break;
                            case 3: sb.append("info"); break;
                            case 4: sb.append("debug"); break;
                            case 5: sb.append("trace"); break;
                            default: sb.append("level" + level);
                        }
                        sb.append("]: ");
                        sb.append(JSRuntime.format(msg, args));
                        sb.append("\n");
                        pw.append(sb);
                        if (e != null) {
                            e.printStackTrace(pw);
                        }
                    }
                }
            };
        }

        /**
         * Create a logger that logs to the "com.bfo.quickjs" System.Logger
         */
        public static Logger toSystem() {
            return toSystem(JSRuntime.class.getPackage().getName());
        }
        /**
         * Create a logger that logs to the specified System.Logger
         * @param name the logger name.
         */
        public static Logger toSystem(String name) {
            final System.Logger logger = System.getLogger(name);
            return new Logger() {
                private static System.Logger.Level[] LEVELS = new System.Logger.Level[] {
                    System.Logger.Level.OFF,
                    System.Logger.Level.ERROR,
                    System.Logger.Level.WARNING,
                    System.Logger.Level.INFO,
                    System.Logger.Level.DEBUG,
                    System.Logger.Level.TRACE
                };
                @Override public boolean isLoggable(int level) {
                    return logger.isLoggable(LEVELS[level]);
                }
                @Override public void log(int level, String msg, Object... args) {
                    if (isLoggable(level)) {
                        Throwable e = null;
                        if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                            e = (Throwable)args[args.length - 1];
                            args = Arrays.copyOf(args, args.length - 1);
                        }
                        logger.log(LEVELS[level], JSRuntime.format(msg, args), e);
                    }
                }
            };
        }
    }

    /**
     * Create a new JSRuntime with the default Logger
     */
    public JSRuntime() {
        this(Logger.toSystem());
    }

    /**
     * Create a new JSRuntime with the specified Logger
     */
    public JSRuntime(Logger logger) {
        this.logger = logger;
        WasiOptions options = WasiOptions.builder().withStdout(System.out).build();
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
                (Instance instance, long... args) -> { fnCompleteCompletableFuture(args[0], (int)args[1], (int)args[2], (int)args[3], (int)args[4]); return new long[] { 0 }; })

        });
        WasmModule module = WasmLib.load();

        instance = Instance.builder(module).withImportValues(store.toImportValues()).withMachineFactory(WasmLib::create).build();
        this.pointer = fnCreateRuntime();

        int level;
        for (level=Logger.ERROR;level<=Logger.TRACE && logger.isLoggable(level);level++);
        fnInitLogger(Math.min(level, Logger.TRACE));
    }

    private HostFunction createHostFunction(final String set, final String name, final List<ValType> in, final List<ValType> out, final WasmFunctionHandle func) {
        return new HostFunction(set, name, FunctionType.of(in, out), (Instance instance, long... args) -> {
            try {
                long[] result = func.apply(instance, args);
                if (result == null) {
                    getLogger().log(Logger.TRACE, "hear {}.{}{} = {}", set, name, args, result);
                } else if (result.length == 1) {
                    getLogger().log(Logger.TRACE, "hear {}.{}{} = {} ({} {})", set, name, args, result, ptrlen2ptr(result[0]), ptrlen2len(result[0]));
                }
                return result;
            } catch (RuntimeException e) {
                getLogger().log(Logger.TRACE, "hear {}.{}{} = ERROR", set, name, args, e);
                return null;
            }
        });
    }

    /**
     * Set the number of bytes that can be allocated.
     * Attempts to allocate more within this Runtime will throw an Exception
     * @param bytes the number of bytes that can be allocated in the runtime
     */
    public void setMemoryLimit(int bytes) {
        fnSetMemoryLimit(bytes);
    }

    /**
     * Set the number of milliseconds before a task is cancelled
     * A script that takes longer than this to excecute will be interrupted and an exception thrown.
     * @param ms the number of milliseconds that an individual script can run for in this runtime
     */
    public void setRuntimeLimit(long ms) {
        scriptRuntimeLimit = Math.max(0, ms);
    }

    /**
     * Create a new JSContext
     */
    public JSContext createContext() {
        JSContext ctx = new JSContext(this);
        contexts.put(ctx.getPointer(), ctx);
        return ctx;
    }

    JSContext getContext(long id) {
        JSContext ctx = contexts.get(id);
        if (ctx == null) {
            throw new RuntimeException("Invalid Context: " + id);
        }
        return ctx;
    }

    /**
     * Return the logger specified in teh constructor
     */
    public Logger getLogger() {
        return logger;
    }

    Instance getInstance() {
        return instance;
    }

    long getPointer() {
        return pointer;
    }

    long[] call(String name, long... args) {
        if (callCountLimit > 0 && ++callCount > callCountLimit) {
            RuntimeException e = new RuntimeException("Too many calls");
            e.printStackTrace();
            throw e;
        }
        ExportFunction func = instance.export(name);
        try {
            long[] result = func.apply(args);
            if (result == null) {
                getLogger().log(Logger.TRACE, "call {}{} = {}", name, args, result);
            } else if (result.length == 1) {
                getLogger().log(Logger.TRACE, "call {}{} = {} ({} {})", name, args, result, ptrlen2ptr(result[0]), ptrlen2len(result[0]));
            }
            return result;
        } catch (RuntimeException e) {
            getLogger().log(Logger.TRACE, "call {}{} = ERROR", name, args, e);
            throw e;
        }
    }

    private static long ptrlen(int ptr, int len) {
        return (long) len | ((long) ptr << 32);
    }
    private static int ptrlen2ptr(long ptrlen) {
        return (int) ((ptrlen >> 32) & 0xffffffff);
    }
    private static int ptrlen2len(long ptrlen) {
        return (int) (ptrlen & 0xffffffff);
    }

    /**
     * Writes the given data to memory and returns the memory location of the data
     *
     * @param data the data to write
     * @return the memory location of the data
     */
    long store(byte[] data) {
        int ptr = alloc(data.length);
        instance.memory().write(ptr, data);
        return ptrlen(ptr, data.length);
    }

    byte[] fetch(int ptr, int len) {
        return instance.memory().readBytes(ptr, len);
    }

    byte[] fetch(long ptrlen) {
        return fetch(ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
    }

    int alloc(int size) {
        long[] ptr = call("alloc", size);
        return (int)ptr[0];
    }

    void dealloc(long ptrlen) {
        dealloc(ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
    }

    void dealloc(int ptr, int len) {
        call("dealloc", ptr, len);
    }

    /**
     * Closes the runtime and all associated contexts
     */
    @Override public void close() throws Exception {
        if (pointer != 0) {
            for (JSContext ctx : contexts.values()) {
                ctx.close();
            }
            contexts.clear();
            fnCloseRuntime();
            pointer = 0;
        }
    }

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

    //--------------------------------------------------------------------------------------
    // Incoming functions
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
                getLogger().log(Logger.WARN, "Runtime {}ms exceeds limit of {}ms: interrupting", runtime, scriptRuntimeLimit);
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
        getLogger().log(Logger.DEBUG, "{} future with value {}", (reject == 1 ? "Rejecting" : "Resolving"), result);
        if (future instanceof JSPromise) {
            ((JSPromise)future).notifyCompletedByJS();
        }
        if (reject != 0) {
            if (!(result instanceof Exception)) { // What could it be?
                result = new JSException("Promise rejected", null);
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

    //-------------------------------------------------------------------------
    // Runtime functions
    //-------------------------------------------------------------------------

    private long fnCreateRuntime() {
        long[] r = call("create_runtime_wasm");
        return r[0];
    }

    private void fnCloseRuntime() {
        call("close_runtime_wasm", getPointer());
    }

    private void fnSetMemoryLimit(int bytes) {
        call("set_memory_limit_runtime_wasm", getPointer(), bytes);
    }

    private void fnInitLogger(int level) {
        call("init_logger_wasm", level);
    }

    //-------------------------------------------------------------------------
    // Context functions
    //-------------------------------------------------------------------------

    /**
     * Create a context
     */
    long fnContextCreate() {
        long[] r = call("create_context_wasm", getPointer());
        if (r[0] == 0) {
            throw new IllegalStateException("Context creation failed");
        }
        return r[0];
    }

    /**
     * Close a context
     */
    void fnContextClose(JSContext ctx) {
        call("close_context_wasm", getPointer(), ctx.getPointer());
    }

    byte[] fnEvalScript(JSContext ctx, String script) {
        long ptrlen = store(script.getBytes(StandardCharsets.UTF_8));
        scriptStart = System.currentTimeMillis();
        long[] r = call("eval_script_wasm", ctx.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        scriptStart = 0;
        dealloc(ptrlen);
        ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    byte[] fnEvalScriptAsync(JSContext ctx, String script) {
        long ptrlen = store(script.getBytes(StandardCharsets.UTF_8));
        long[] r = call("eval_script_async_wasm", ctx.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        dealloc(ptrlen);
        ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Put a value on an object
     * @return an exception or null
     */
    byte[] fnContextPut(JSContext ctx, String key, byte[] value) {
        long kptrlen = store(key.getBytes(StandardCharsets.UTF_8));
        long vptrlen = store(value);
        long[] r = call("set_global_wasm", ctx.getPointer(), ptrlen2ptr(kptrlen), ptrlen2len(kptrlen), ptrlen2ptr(vptrlen), ptrlen2len(vptrlen));
        dealloc(kptrlen);
        dealloc(vptrlen);
        long ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Get a value from an object
     */
    byte[] fnContextGet(JSContext ctx, String key) {
        long ptrlen = store(key.getBytes(StandardCharsets.UTF_8));
        long[] r = call("get_global_wasm", ctx.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        dealloc(ptrlen);
        ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Invoke a function
     * @param key the key
     * @param value the serialized list of arguments
     * @return the response
     */
    byte[] fnInvoke(JSContext ctx, String key, byte[] value) {
        long kptrlen = store(key.getBytes(StandardCharsets.UTF_8));
        long vptrlen = store(value);
        scriptStart = System.currentTimeMillis();
        long[] r = call("invoke_wasm", ctx.getPointer(), ptrlen2ptr(kptrlen), ptrlen2len(kptrlen), ptrlen2ptr(vptrlen), ptrlen2len(vptrlen));
        scriptStart = 0;
        dealloc(kptrlen);
        dealloc(vptrlen);
        long ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Return true if more tasks await
     */
    boolean fnPoll(JSContext ctx) {
        return call("poll_wasm", ctx.getPointer())[0] == 1;
    }

    //-------------------------------------------------------------------------
    // Object functions
    //-------------------------------------------------------------------------

    /**
     * Create an object
     */
    long fnObjectCreate(JSContext ctx) {
        long[] r = call("object_create_wasm", ctx.getPointer());
        if (r[0] == 0) {
            throw new IllegalStateException("Object creation failed");
        }
        return r[0];
    }

    /**
     * Close an object
     */
    void fnObjectClose(JSObject object) {
        final JSContext ctx = object.getContext();
        call("object_close_wasm", ctx.getPointer(), object.getPointer());
    }

    /**
     * Return the size of a JSObject
     */
    int fnObjectSize(final JSObject object) {
        final JSContext ctx = object.getContext();
        return (int)call("object_size_wasm", ctx.getPointer(), object.getPointer())[0];
    }

    /**
     * Put a value on an object
     */
    void fnObjectPut(JSObject object, byte[] key, byte[] value) {
        final JSContext ctx = object.getContext();
        long kptrlen = store(key);
//        long kptrlen = store(key.getBytes(StandardCharsets.UTF_8));
        long vptrlen = store(value);
        call("object_set_value_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(kptrlen), ptrlen2len(kptrlen), ptrlen2ptr(vptrlen), ptrlen2len(vptrlen));
        dealloc(kptrlen);
        dealloc(vptrlen);
    }

    /**
     * Get a value from an object
     */
    byte[] fnObjectGet(JSObject object, byte[] key) {
        final JSContext ctx = object.getContext();
        long ptrlen = store(key);
//        long ptrlen = store(key.getBytes(StandardCharsets.UTF_8));
        long[] r = call("object_get_value_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        dealloc(ptrlen);
        ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Remove a value from an object
     */
    void fnObjectRemove(JSObject object, byte[] key) {
        final JSContext ctx = object.getContext();
        long ptrlen = store(key);
//        long ptrlen = store(key.getBytes(StandardCharsets.UTF_8));
        call("object_remove_value_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        dealloc(ptrlen);
    }

    /**
     * See if object contains a particular key
    boolean fnObjectContainsKey(JSObject object, String key) {
        final JSContext ctx = object.getContext();
        MemoryLocation k = store(key.getBytes(StandardCharsets.UTF_8));
        long[] r = call("object_contains_key_wasm", ctx.getPointer(), object.getPointer(), k.getPointer(), k.getLength());
        dealloc(k);
        return r[0] != 0;
    }
     */

    /**
     * Get a list of keys from an object
     */
    byte[] fnObjectKeySet(JSObject object) {
        final JSContext ctx = object.getContext();
        long[] r = call("object_key_set_wasm", ctx.getPointer(), object.getPointer());
        long ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Define getter/setter on a property
     * @param key the property name
     * @param getter the index to the getter funtion (required, non zero)
     * @param getter the index to the setter funtion (may be zero)
     * @param flags a bitmask: 0x01 = property is enumerable, 0x02 = property is deletable
     */
    void fnObjectDefineProperty(JSObject object, byte[] key, int getter, int setter, int flags) {
        final JSContext ctx = object.getContext();
        long ptrlen = store(key);
        long[] r = call("object_define_property_get_set_wasm", ctx.getPointer(), object.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen), getter, setter, flags);
        dealloc(ptrlen);
    }

    //-------------------------------------------------------------------------
    // Array functions
    //-------------------------------------------------------------------------

    /**
     * Create an array
     */
    long fnArrayCreate(JSContext ctx) {
        long[] r = call("array_create_wasm", ctx.getPointer());
        if (r[0] == 0) {
            throw new IllegalStateException("Array creation failed");
        }
        return r[0];
    }

    /**
     * Return the size of the array
     */
    int fnArraySize(JSArray array) {
        final JSContext ctx = array.getContext();
        return (int)call("array_size_wasm", ctx.getPointer(), array.getPointer())[0];
    }

    /**
     * Insert an item in the array
     */
    void fnArrayAdd(JSArray array, int ix, byte[] value) {
        final JSContext ctx = array.getContext();
        long ptrlen = store(value);
        call("array_add_wasm", ctx.getPointer(), array.getPointer(), ix, ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        dealloc(ptrlen);
    }

    /**
     * Set an item in the array
     */
    void fnArraySet(JSArray array, int ix, byte[] value) {
        final JSContext ctx = array.getContext();
        long ptrlen = store(value);
        call("array_set_wasm", ctx.getPointer(), array.getPointer(), ix, ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        dealloc(ptrlen);
    }

    /**
     * Get an item from the array
     */
    byte[] fnArrayGet(JSArray array, int ix) {
        final JSContext ctx = array.getContext();
        long[] r = call("array_get_wasm", ctx.getPointer(), array.getPointer(), ix);
        long ptrlen = r[0];
        byte[] data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Remove an item from the array
     */
    void fnArrayRemove(JSArray array, int ix) {
        final JSContext ctx = array.getContext();
        call("array_remove_wasm", ctx.getPointer(), array.getPointer(), ix);
    }

    /**
     * Free an array
     */
    void fnArrayClose(JSArray array) {
        final JSContext ctx = array.getContext();
        call("array_close_wasm", ctx.getPointer(), array.getPointer());
    }

    //-------------------------------------------------------------------------
    // Function functions
    //-------------------------------------------------------------------------

    /**
     * Invoke a function
     */
    byte[] fnFunctionCall(JSFunction function, byte[] data) {
        final JSContext ctx = function.getContext();
        long ptrlen = store(data);
        scriptStart = System.currentTimeMillis();
        long[] r = call("call_function_wasm", ctx.getPointer(), function.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        scriptStart = 0;
        dealloc(ptrlen);
        ptrlen = r[0];
        data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Invoke a function as a constructor
     */
    byte[] fnFunctionConstruct(JSFunction function, byte[] data) {
        final JSContext ctx = function.getContext();
        long ptrlen = store(data);
        scriptStart = System.currentTimeMillis();
        long[] r = call("construct_function_wasm", ctx.getPointer(), function.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        scriptStart = 0;
        dealloc(ptrlen);
        ptrlen = r[0];
        data = fetch(ptrlen);
        dealloc(ptrlen);
        return data;
    }

    /**
     * Return true if this function is a constructor function
     */
    boolean fnFunctionIsConstructor(JSFunction function) {
        final JSContext ctx = function.getContext();
        return call("function_is_constructor_wasm", ctx.getPointer(), function.getPointer())[0] == 1;
    }

    /**
     * Free a function
     */
    void fnFunctionClose(JSFunction function) {
        final JSContext ctx = function.getContext();
        call("close_function_wasm", ctx.getPointer(), function.getPointer());
    }

    //-------------------------------------------------------------------------
    // Promise functions
    //-------------------------------------------------------------------------

    /**
     * Create a promise
     */
    long fnPromiseCreate(JSContext ctx, int index) {
        long[] r = call("promise_create_wasm", ctx.getPointer(), index);
        if (r[0] == 0) {
            throw new IllegalStateException("Promise creation failed");
        }
        return r[0];
    }

    /**
     * Resolve a promise
     */
    void fnPromiseResolve(JSPromise promise, byte[] data) {
        final JSContext ctx = promise.getContext();
        long ptrlen = store(data);
        scriptStart = System.currentTimeMillis();
        long[] r = call("promise_resolve_wasm", ctx.getPointer(), promise.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        scriptStart = 0;
        dealloc(ptrlen);
        ptrlen = r[0];
    }

    /**
     * Reject a promise
     */
    void fnPromiseReject(JSPromise promise, byte[] data) {
        final JSContext ctx = promise.getContext();
        long ptrlen = store(data);
        scriptStart = System.currentTimeMillis();
        long[] r = call("promise_reject_wasm", ctx.getPointer(), promise.getPointer(), ptrlen2ptr(ptrlen), ptrlen2len(ptrlen));
        scriptStart = 0;
        dealloc(ptrlen);
        ptrlen = r[0];
    }

    /**
     * Free a Promise
     */
    void fnPromiseClose(JSPromise promise) {
        final JSContext ctx = promise.getContext();
        call("promise_close_wasm", ctx.getPointer(), promise.getPointer());
    }

}
