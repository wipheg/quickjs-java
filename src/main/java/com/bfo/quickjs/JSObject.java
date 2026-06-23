package com.bfo.quickjs;

import java.util.*;
import java.util.function.*;
import java.lang.reflect.*;

/**
 * Represents a JavaScript "Object" type
 */
public class JSObject extends AbstractMap<String,Object> implements JSType, AutoCloseable {

    private static final Object UNSET = new Object();
    private final JSContext ctx;
    private final long pointer;
    private int gen;
    private Set<Map.Entry<String,Object>> entryset;

    JSObject(JSContext ctx, long pointer) {
        this.ctx = ctx;
        this.pointer = pointer;
    }

    @Override public long getPointer() {
        return pointer;
    }

    @Override public JSContext getContext() {
        return ctx;
    }

    @Override public int size() {
        return entrySet().size();
    }

    @Override public Set<Map.Entry<String,Object>> entrySet() {
        final int nowgen = ctx.getGeneration();
        if (entryset == null || gen != nowgen) {
            gen = nowgen;
            entryset = new AbstractSet<Map.Entry<String,Object>>() {
                int size = -1;
                List<JSEntry> data;
                @Override public int size() {
                    if (size < 0) {
                        size = ctx.getRuntime().fnObjectSize(JSObject.this);
                    }
                    return size;
                }
                @Override public Iterator<Map.Entry<String,Object>> iterator() {
                    if (data == null) {
                        @SuppressWarnings("unchecked") Collection<Object> c = (Collection<Object>)ctx.unpack(ctx.getRuntime().fnObjectKeySet(JSObject.this));
                        if (size < 0) {
                            size = c.size();
                        }
                        data = new ArrayList<JSEntry>(size);
                        for (Object o : c) {
                            data.add(new JSEntry((String)o));
                        }
                    }
                    return new Iterator<Map.Entry<String,Object>>() {
                        int i = 0;
                        boolean removed;
                        @Override public boolean hasNext() {
                            if (nowgen != ctx.getGeneration()) {
                                throw new ConcurrentModificationException();
                            }
                            return i < data.size();
                        }
                        @Override public Map.Entry<String,Object> next() {
                            if (i == data.size()) {
                                throw new NoSuchElementException();
                            }
                            if (nowgen != ctx.getGeneration()) {
                                throw new ConcurrentModificationException();
                            }
                            removed = false;
                            return data.get(i++);
                        }
                        @Override public void remove() {
                            if (i == 0 || removed) {
                                throw new IllegalStateException();
                            }
                            if (nowgen != ctx.getGeneration()) {
                                throw new ConcurrentModificationException();
                            }
                            data.get(i - 1).remove();
                            data.remove(--i);
                            size--;
                            removed = true;
                        }
                    };
                }
            };
        }
        return entryset;
    }

    @Override public Object put(String key, Object value) {
        if (key == null) {
             throw new NullPointerException("Null key");
        }
        final Object oldValue = get(key);
        set(key, value);
        entryset = null; 
        return oldValue;
    }

    private void set(String key, Object value) {
        if (value instanceof JSComputedValue) {
            Function<List<Object>,Object> getter = new Function<>() {
                public Object apply(List<Object> args) {
                    System.out.println("GETTER CALLED: " + args+": myptr="+pointer+" otherptr="+((JSObject)args.get(0)).pointer);
                    return 42;
                }
            };
            ctx.getRuntime().fnObjectDefineProperty(this, ctx.pack(key), ctx.registerProxy(getter), 0);
        } else {
            ctx.getRuntime().fnObjectPut(this, ctx.pack(key), ctx.pack(value));
        }
    }

    private class JSEntry implements Map.Entry<String,Object> {
        private final String key;
        private Object value;
        private int gen;

        JSEntry(String key) {
            this.key = key;
            this.value = UNSET;
        }

        @Override public String getKey() {
            return key;
        }

        @Override public Object getValue() {
            if (value == UNSET) {
                byte[] b = ctx.pack(key);
                if (b.length == 0) throw new Error("KEY="+key);
                value = ctx.unpack(ctx.getRuntime().fnObjectGet(JSObject.this, ctx.pack(key)));
            }
            if (value instanceof RuntimeException) {
                throw (RuntimeException)value;
            }
            return value;
        }

        @Override public Object setValue(Object value) {
            Object oldvalue = getValue();    // updates generation
            set(key, value);
            this.value = value;
            return oldvalue;
        }

        void remove() {
            ctx.getRuntime().fnObjectRemove(JSObject.this, ctx.pack(key));
        }
    }

    @Override public void close() throws Exception {
        ctx.getRuntime().fnObjectClose(this);
    }

    /**
     * Return a {@link Proxy} for this object which implemnts the specified interface
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T as(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                if (m.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, m, args);
                } else {
                    JSObject object = JSObject.this;
                    Object o = object.get(m.getName());
                    if (o instanceof JSFunction) {
                        return ((JSFunction)o).call(args);
                    } else {
                        throw new NoSuchMethodException("Method " + m.getName() + " not found on " + object);
                    }
                }
            }
        });
    }

}
