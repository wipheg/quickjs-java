package com.bfo.quickjs;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.msgpack.core.*;
import org.msgpack.value.*;

/**
 * Utility class to pack / unpack supported java object into the format common
 * with the native library
 */
class Packer {

    private final JSContext ctx;

    Packer(JSContext ctx) {
        this.ctx = ctx;
    }

    byte[] pack(Object o) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(out);
            pack(o, packer);
            packer.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Object unpack(byte[] o) {
        try {
            // ctx.getRuntime().log(JSRuntime.LOG_DEBUG, "RX " + com.bfo.json.Json.read(new com.bfo.json.MsgpackReader().setInput(java.nio.ByteBuffer.wrap(o))));
            MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(o);
            return unpack(unpacker);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object prepack(final Object o) {
        if (o instanceof Supplier) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { return ((Supplier)o).get(); } };
        } else if (o instanceof Consumer) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { ((Consumer)o).accept(args.get(0)); return null; } };
        } else if (o instanceof BiConsumer) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { ((BiConsumer)o).accept(args.get(0), args.get(1)); return null; } };
        } else if (o instanceof BiFunction) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { return ((BiFunction)o).apply(args.get(0), args.get(1)); } };
        }
        return o;
    }

    private void pack(Object o, final MessagePacker p) throws IOException {
        o = prepack(o);
        if (o == null) {
            p.packString("null");
        } else {
            p.packMapHeader(1);
            if (o instanceof String) {
                p.packString("string");
                p.packString((String)o);
            } else if (o instanceof Float || o instanceof Double) {
                p.packString("float");
                p.packDouble(((Number)o).doubleValue());
            } else if (o instanceof Boolean) {
                p.packString("boolean");
                p.packBoolean(((Boolean)o).booleanValue());
            } else if (o instanceof Integer) {
                p.packString("int");
                p.packInt(((Integer)o).intValue());
            } else if (o instanceof JSArray) {
                p.packString("nativeArray");
                p.packLong(((JSArray)o).getPointer());
            } else if (o instanceof JSObject) {
                p.packString("nativeObject");
                p.packLong(((JSObject)o).getPointer());
            } else if (o instanceof List) {
                p.packString("array");
                @SuppressWarnings("unchecked")List<Object> l = (List<Object>)o;
                p.packArrayHeader(l.size());
                for (Object item : l) {
                    pack(item, p);
                }
            } else if (o instanceof Map) {
                p.packString("object");
                @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>)o;
                p.packMapHeader(m.size());
                for (Map.Entry<String,Object> e : m.entrySet()) {
                    p.packString(e.getKey());
                    pack(e.getValue(), p);
                }
            } else if (o instanceof JSFunction) {
                p.packString("function");
                JSFunction f = (JSFunction)o;
                p.packArrayHeader(2);
                p.packString(f.getName());
                p.packLong(f.getPointer());
            } else if (o instanceof Function) {
                p.packString("javaFunction");
                @SuppressWarnings("unchecked") Function<List<Object>,Object> f = (Function<List<Object>,Object>) o;
                int functionPointer = ctx.registerProxy(f);
                p.packArrayHeader(2);
                p.packInt((int)ctx.getPointer());
                p.packInt(functionPointer);
            } else if (o instanceof Exception) {
                p.packString("exception");
                Exception e = (Exception)o;
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                p.packArrayHeader(2);
                p.packString(e.getMessage());
                p.packString(sw.toString());
            } else if (o instanceof CompletionStage) {
                p.packString("completableFuture");
                JSPromise promise;
                if (o instanceof JSPromise) {
                    promise = (JSPromise)o;
                } else {
                    @SuppressWarnings("unchecked") CompletionStage<Object> cs = (CompletionStage<Object>)o;
                    promise = ctx.newPromise(cs);
                }
                int index = promise.getIndex();                 // First check if this is an already registred completable future
                long promisePtr = promise.getPointer();         // Then check for a promise pointer -> available if it is a JSPromise
                p.packArrayHeader(2);
                p.packInt(index);
                p.packLong(promisePtr);
            } else {
                throw new IllegalArgumentException("Unable to pack object of type " + o.getClass().getName());
            }
        }
    }

    Object unpack(final MessageUnpacker u) throws IOException {
        ValueType type = u.getNextFormat().getValueType();
        if (type == ValueType.STRING) {
            String val = u.unpackString();
            return val.equals("null") || val.equals("undefined") ? null : val;
        } else if (type == ValueType.MAP) {
            u.unpackMapHeader(); // Should be 1
            String tag = u.unpackString();
            int size;
            long pointer;
            switch (tag) {
                case "string":
                    return u.unpackString();
                case "float":
                    return u.unpackDouble();
                case "boolean":
                    return u.unpackBoolean();
                case "int":
                    return u.unpackInt();
                case "nativeArray":
                    return new JSArray(ctx, u.unpackLong());
                case "nativeObject":
                    return new JSObject(ctx, u.unpackLong());
                case "array":
                    size = u.unpackArrayHeader();
                    List<Object> list = new ArrayList<>();
                    for (int i=0;i<size;i++) {
                        list.add(unpack(u));
                    }
                    return list;
                case "object":
                    size = u.unpackMapHeader();
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i=0;i<size;i++) {
                        String key = u.unpackString();
                        map.put(key, unpack(u));
                    }
                    return map;
                case "function":
                    size = u.unpackArrayHeader();
                    if (size != 2) {
                        throw new IOException("Expected array with 2 element (function name, function ptr)");
                    }
                    return new JSFunction(ctx, u.unpackString(), u.unpackLong());
                case "javaFunction": // Impossible to enter since, one would always get back a 'function', wrapping the Java function
                    size = u.unpackArrayHeader();
                    if (size != 2) {
                        throw new IOException("Expected array with 2 element (ctx ptr, function index)");
                    }
                    final int ctxPtr = u.unpackInt();
                    final int functionIndex = u.unpackInt();
                    if (ctxPtr != ctx.getPointer()) {
                        throw new RuntimeException("Context pointer does not match");
                    }
                    return ctx.getProxy(functionIndex, Function.class);
                case "exception":
                    size = u.unpackArrayHeader();
                    if (size != 2) {
                        throw new IOException("Expected array with 2 element (exception message, exception stack)");
                    }
                    return new JSException(u.unpackString(), u.unpackString());
                case "completableFuture":
                    size = u.unpackArrayHeader();
                    if (size != 2) {
                        throw new RuntimeException("Expected completableFuture with 2 element (completable futre pointer, promise pointer)");
                    }
                    final int futureIndex = u.unpackInt();
                    final long promisePtr = u.unpackLong();
                    @SuppressWarnings("unchecked") CompletableFuture<Object> future = ctx.getProxy(futureIndex, CompletableFuture.class); // TODO wrap into a completablefuture with promise ptr
                    if (future instanceof JSPromise && ((JSPromise)future).getPointer() != promisePtr) {
                        throw new IOException("Pointer does not match for completable future " + futureIndex);
                    }
                    return future;
                default:
                    throw new IOException("Unknown type \"" + tag + "\"");

            }
        } else {
            throw new IllegalArgumentException("Unexpected type " + type);
        }
    }
}
