package com.bfo.quickjs;

import java.util.*;
import java.util.function.*;
import java.lang.reflect.*;

/**
 * Represents a JavaScript "Object" type.
 * The internal state of this object will be cached as much as possible, and the cached
 * value invalidated whenever the context is updated by a call to <code>eval</code> or
 * a promise resolving.
 */
public class JSObject extends AbstractMap<String,Object> implements JSType, AutoCloseable {

    private static final Object UNSET = new Object();
    private final JSContext ctx;
    private volatile long pointer;
    private volatile int generation;
    private volatile JSEntrySet entryset;

    JSObject(JSContext ctx, long pointer) {
        this.ctx = ctx;
        this.pointer = pointer;
        ctx.addCloseable(this);
    }

    @Override public long getPointer() {
        return pointer;
    }

    @Override public JSContext getContext() {
        return ctx;
    }

    final boolean isClosed() {
        return pointer == 0;
    }

    @Override public int size() {
        return entrySet().size();
    }

    @Override public Set<Map.Entry<String,Object>> entrySet() {
        final int contextGeneration = ctx.getGeneration();
        if (entryset == null || generation < contextGeneration) {
            synchronized(this) {
                if (entryset == null || generation < contextGeneration) {
                    entryset = new JSEntrySet(contextGeneration);
                    generation = contextGeneration;
                }
            }
        }
        return entryset;
    }

    @Override public Object get(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        // Otherwise simple globalThis.get() may fail, as we call keyset, then poll, then iterate the keyset values
        byte[] keybytes = ctx.pack(key);
        Object value = ctx.unpack(ctx.getRuntime().fnObjectGet(JSObject.this, keybytes));
        JSEntrySet entryset = this.entryset;
        if (entryset != null) {
            List<JSEntry> l = entryset.data;
            if (l != null) {
                for (JSEntry e : l) {
                    if (e.getKey().equals(key)) {
                        e.updateValue(value);
                        break;
                    }
                }
            }
        }
        return value;
    }

    @Override public Object remove(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        // Override this method for the same reason as get() - otherwise we risk frequent ConcurrentModificationExceptions.
        // However it's a bit more involved as we're changing content and have to return a value too.
        final byte[] keybytes = ctx.pack(key);
        JSEntrySet entryset = this.entryset;
        if (entryset != null) {
            List<JSEntry> l = entryset.data;
            if (l != null) {
                for (int i=0;i<l.size();i++) {
                    // We have already queried the keyset, and it hasn't been invalidated
                    // so we have to assume it represents the current state. Check if the
                    // key exists, remove it if so.
                    JSEntry e = l.get(i);
                    if (e.getKey().equals(key)) {
                        Object oldvalue = e.getValue();
                        ctx.getRuntime().fnObjectRemove(JSObject.this, keybytes);
                        l.remove(i--);  // Removal is pointless because we're going to invalidate
                        ctx.bump();     // this set, but since we're here...
                        return oldvalue;
                    }
                }
                // We could remove anyway here, just in case logic in previous was wrong? TBD.
                // ctx.getRuntime().fnObjectRemove(JSObject.this, keybytes);
                return null;
            }
        }
        // If we get here set wasn't populated anyway.
        // We need to return previous value, so have to get it first.
        Object oldvalue = ctx.unpack(ctx.getRuntime().fnObjectGet(JSObject.this, keybytes));
        ctx.getRuntime().fnObjectRemove(JSObject.this, keybytes);
        return oldvalue;
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
        if (isClosed()) {
            throw new IllegalStateException("Object closed");
        }
        if (value instanceof JSComputedValue) {
            JSComputedValue cv = (JSComputedValue) value;
            Function<List<Object>,Object> getter = new Function<>() {
                public String toString() {
                    return "wrapper(getter(" + cv + "))";
                }
                public Object apply(List<Object> args) {
                    JSObject owner = (JSObject)args.get(0);
                    String key = args.get(1) == null ? null : args.get(1).toString();   // not expecting null
                    return cv.get(owner, key);
                }
            };
            Function<List<Object>,Object> setter = null;
            boolean hassetter = false;
            try {
                hassetter = !cv.getClass().getMethod("set", JSObject.class, String.class, Object.class).isDefault();
            } catch (Exception e) {}
            if (hassetter) {
                // Setter is overridden
                setter = new Function<>() {
                    public String toString() {
                        return "wrapper(setter(" + cv + "))";
                    }
                    public Object apply(List<Object> args) {
                        JSObject owner = (JSObject)args.get(0);
                        String key = args.get(1) == null ? null : args.get(1).toString(); // not expecting null
                        Object value = args.get(2);
                        cv.set(owner, key, value);
                        return null;
                    }
                };
            }
            int getptr = ctx.registerProxy(getter);
            int setptr = setter == null ? 0 : ctx.registerProxy(setter);
            int flags = (cv.isHidden() ? 0 : 1) + (cv.isDeleteable() ? 2 : 0);
            ctx.getRuntime().fnObjectDefineProperty(this, ctx.pack(key), getptr, setptr, flags);
        } else {
            ctx.getRuntime().fnObjectPut(this, ctx.pack(key), ctx.pack(value));
        }
    }

    private class JSEntrySet extends AbstractSet<Map.Entry<String,Object>> {
        private final int contextGeneration;
        int size = -1;
        List<JSEntry> data;
        JSEntrySet(int contextGeneration) {
            this.contextGeneration = contextGeneration;
        }
        @Override public int size() {
            if (isClosed()) {
                throw new IllegalStateException("Object closed");
            }
            if (size < 0) {
                size = ctx.getRuntime().fnObjectSize(JSObject.this);
            }
            return size;
        }
        @Override public Iterator<Map.Entry<String,Object>> iterator() {
            if (data == null) {
                if (isClosed()) {
                    throw new IllegalStateException("Object closed");
                }
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
                    if (contextGeneration != ctx.getGeneration()) {
                        throw new ConcurrentModificationException();
                    }
                    return i < data.size();
                }
                @Override public Map.Entry<String,Object> next() {
                    if (i == data.size()) {
                        throw new NoSuchElementException();
                    }
                    if (contextGeneration != ctx.getGeneration()) {
                        throw new ConcurrentModificationException();
                    }
                    removed = false;
                    return data.get(i++);
                }
                @Override public void remove() {
                    if (i == 0 || removed) {
                        throw new IllegalStateException();
                    }
                    if (contextGeneration != ctx.getGeneration()) {
                        throw new ConcurrentModificationException();
                    }
                    data.get(i - 1).remove();
                    data.remove(--i);
                    size--;
                    removed = true;
                }
            };
        }
    }

    private class JSEntry implements Map.Entry<String,Object> {
        private final String key;
        private Object value;

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
                value = ctx.unpack(ctx.getRuntime().fnObjectGet(JSObject.this, ctx.pack(key)));
            }
            if (value instanceof RuntimeException) {
                throw (RuntimeException)value;
            }
            return value;
        }

        void updateValue(Object value) {
            this.value = value;
        }

        @Override public Object setValue(Object value) {
            Object oldvalue = getValue();    // updates generation
            set(key, value);
            updateValue(value);
            return oldvalue;
        }

        void remove() {
            ctx.getRuntime().fnObjectRemove(JSObject.this, ctx.pack(key));
        }
    }

    @Override public void close() throws Exception {
        if (!isClosed()) {
            ctx.getRuntime().fnObjectClose(this);
            pointer = 0;
        }
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
