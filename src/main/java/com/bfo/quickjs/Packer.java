package com.bfo.quickjs;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.lang.reflect.*;
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
        if (o == null) {
            return null;
        } else if (o instanceof Supplier) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { return ((Supplier)o).get(); } };
        } else if (o instanceof Consumer) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { ((Consumer)o).accept(args.get(0)); return null; } };
        } else if (o instanceof BiConsumer) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { ((BiConsumer)o).accept(args.get(0), args.get(1)); return null; } };
        } else if (o instanceof BiFunction) {
            return new Function<List<Object>,Object>() { @Override public Object apply(List<Object> args) { return ((BiFunction)o).apply(args.get(0), args.get(1)); } };
        } else if (o.getClass().isAnnotationPresent(JSExport.class)) {
            return ctx.getExportProxies().computeIfAbsent(o, new Function<Object,JSObject>() {
                public JSObject apply(Object o) {
                    return fromJSExport(o);
                }
            });
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
                throw new IllegalArgumentException("Unable to pack object of type \"" + o.getClass().getName() + "\"");
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

    /**
     * Given an object that is annotated with @JSExport, return a new JSObject
     * which will mirror any values set on it to the fields and methods annotated
     * with @JSExport
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    JSObject fromJSExport(final Object object) {
        Class c = object.getClass();
        if (c.isAnnotationPresent(JSExport.class)) {
            final JSObject output = ctx.newObject();
            final Map<String,FieldProp> map = new LinkedHashMap<String,FieldProp>();
            for (Field f : c.getDeclaredFields()) {
                if (f.canAccess(object) && f.isAnnotationPresent(JSExport.class) && (f.getModifiers() & Modifier.STATIC) == 0) {
                    JSExport export = f.getAnnotation(JSExport.class);
                    String name = export.field().equals("") ? f.getName() : export.field();
                    if (output.containsKey(name) || map.containsKey(name)) {
                        throw new IllegalStateException("Duplicate name \"" + name + "\"");
                    }
                    FieldProp prop = new FieldProp();
                    map.put(name, prop);
                    prop.getter = f;
                    if ((f.getModifiers() & Modifier.FINAL) == 0) {
                        prop.setter = f;
                    }
                    if (export.hidden()) {
                        prop.hidden = true;
                    }
                    if (export.deleteable()) {
                        prop.deleteable = true;
                    }
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if ((m.getModifiers() & (Modifier.STATIC|Modifier.ABSTRACT)) == 0 && m.isAnnotationPresent(JSExport.class) && m.canAccess(object)) {
                    JSExport export = m.getAnnotation(JSExport.class);
                    final Method method = m;
                    if (export.field().equals("")) {        // export as a method
                        final String name = method.getName();
                        if (output.containsKey(name) || map.containsKey(name)) {
                            throw new IllegalStateException("Duplicate name \"" + name + "\"");
                        }
                        output.put(name, new Function<List<Object>,Object>() {
                            public String toString() {
                                return "[Function that calls \"" + name + "\"]";
                            }
                            public Object apply(List<Object> args) {
                                Class[] types = method.getParameterTypes();
                                Object[] values = new Object[types.length];
                                for (int i=0;i<args.size();i++) {
                                    if (i == types.length - 1 && method.isVarArgs()) {
                                        // final parameter is X[] - take all remaining parameters and put into Array of X
                                        Class c = types[i].getComponentType();
                                        int l = args.size() - i;
                                        Object a = values[i] = Array.newInstance(c, l);
                                        for (int j=0;j<l;j++) {
                                            Array.set(a, j, marshall(c, args.get(i++)));
                                        }
                                    } else {
                                        values[i] = marshall(types[i], args.get(i));
                                    }
                                }
                                try {
                                    return method.invoke(object, values);
                                } catch (Exception e) {
                                    throw JSRuntime.toRuntimeException(e);
                                }
                            }
                        });
                    } else {
                        final String name = export.field();
                        if (output.containsKey(name)) {
                            throw new IllegalStateException("Duplicate name \"" + name + "\"");
                        }
                        FieldProp prop = map.computeIfAbsent(name, k -> new FieldProp());
                        if (method.getParameterTypes().length == 0 && method.getReturnType() != Void.TYPE) {
                            if (prop.getter == null) {
                                prop.getter = method;
                            } else {
                                throw new IllegalStateException("Duplicate getter \"" + name + "\"");
                            }
                        } else if (method.getParameterTypes().length == 1 && method.getReturnType() == Void.TYPE) {
                            if (prop.setter == null) {
                                prop.setter = method;
                            } else {
                                throw new IllegalStateException("Duplicate setter \"" + name + "\"");
                            }
                        } else {
                            throw new IllegalStateException("Method \"" + method.getName() + "\" annotated with field=\"" + name + "\" but not a getter or setter");
                        }
                        if (export.hidden()) {
                            prop.hidden = true;
                        }
                        if (export.deleteable()) {
                            prop.deleteable = true;
                        }
                    }
                }
            }
            for (Map.Entry<String,FieldProp> e : map.entrySet()) {
                final String name = e.getKey();
                final FieldProp p = e.getValue();
                if (p.getter == null) {
                    throw new IllegalStateException("Property \"" + name + "\" has setter but no getter");
                } else {
                    JSComputedValue cv = new JSComputedValue() {
                        public String toString() {
                            return "[JSComputedValue getter for \"" + name + "\"]";
                        }
                        @Override public Object get(JSObject owner, String key) {
                            try {
                                if (p.getter instanceof Field) {
                                    return ((Field)p.getter).get(object);
                                } else {
                                    return ((Method)p.getter).invoke(object);
                                }
                            } catch (InvocationTargetException e) {
                                Throwable e2 = e.getCause();
                                if (!(e2 instanceof RuntimeException)) {
                                    e2 = new RuntimeException(e2);
                                }
                                throw (RuntimeException)e2;
                            } catch (Exception e) {
                                throw JSRuntime.toRuntimeException(e);
                            }
                        }
                        @Override public boolean isHidden() {
                            return p.hidden;
                        }
                        @Override public boolean isDeleteable() {
                            return p.deleteable;
                        }
                    };
                    if (p.setter != null) {
                        final JSComputedValue fcv = cv;
                        cv = new JSComputedValue() {
                            public String toString() {
                                return "[JSComputedValue getter/setter for \"" + name + "\"]";
                            }
                            @Override public Object get(JSObject owner, String key) {
                                return fcv.get(owner, key);
                            }
                            @Override public void set(JSObject owner, String key, Object value) {
                                try {
                                    if (p.setter instanceof Field) {
                                        ((Field)p.setter).set(object, marshall(((Field)p.setter).getType(), value));
                                    } else {
                                        ((Method)p.setter).invoke(object, marshall(((Method)p.setter).getParameterTypes()[0], value));
                                    }
                                } catch (Exception e) {
                                    throw JSRuntime.toRuntimeException(e);
                                }
                            }
                            @Override public boolean isHidden() {
                                return fcv.isHidden();
                            }
                            @Override public boolean isDeleteable() {
                                return fcv.isDeleteable();
                            }
                        };
                    }
                    output.put(e.getKey(), cv);
                }
            }
            return output;
        } else {
            throw new IllegalArgumentException("Object not a known type and not annotated with @JSExport");
        }
    }
    private static class FieldProp {
        Member getter, setter;
        boolean hidden, deleteable;
    }

    /**

     * Given an Object of any type, try and marshall it into an object of the specified class.
     * Can convert Boolean, Number and String, and arrays of those values.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object marshall(Class c, Object v) {
        if (v == null) {
            return v;
        } else if (c.isArray()) {
            List l = v instanceof List ? (List)v : List.of(v);
            c = c.getComponentType();
            Object a = Array.newInstance(c, l.size());
            for (int i=0;i<l.size();i++) {
                Array.set(a, i, marshall(c, l.get(i)));
            }
            return a;
        } else if (c == Boolean.class) {
            return v instanceof Number ? ((Number)v).floatValue() != 0 : "true".equals(v.toString());
        } else if (c == String.class) {
            return v.toString();
        } else if (c == Integer.class || c == Integer.TYPE) {
            return v instanceof Number ? Integer.valueOf(((Number)v).intValue()) : Integer.parseInt(v instanceof Boolean ? (((Boolean)v).booleanValue()?"1":"0"):v.toString());
        } else if (c == Long.class || c == Long.TYPE) {
            return v instanceof Number ? Long.valueOf(((Number)v).longValue()) : Long.parseLong(v instanceof Boolean ? (((Boolean)v).booleanValue()?"1":"0"):v.toString());
        } else if (c == Float.class || c == Float.TYPE) {
            return v instanceof Number ? Float.valueOf(((Number)v).floatValue()) : Float.parseFloat(v instanceof Boolean ? (((Boolean)v).booleanValue()?"1":"0"):v.toString());
        } else if (c == Double.class || c == Double.TYPE) {
            return v instanceof Number ? Double.valueOf(((Number)v).doubleValue()) : Double.parseDouble(v instanceof Boolean ? (((Boolean)v).booleanValue()?"1":"0"):v.toString());
        } else {
            return v;
        }
    }

}
