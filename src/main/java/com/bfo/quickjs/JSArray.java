package com.bfo.quickjs;

import java.util.*;

/**
 * A JSArray is a representation of a JavaScript list
 * The internal state of this object will be cached as much as possible, and the
 * cached value invalidated whenever the context is updated by a call to
 * <code>eval</code> or a promise resolving.
 */
public class JSArray extends AbstractList<Object> implements JSType, AutoCloseable {

    private static final Object UNSET = new Object();
    private final JSContext ctx;
    private volatile long pointer;
    private volatile int generation;
    private volatile List<Object> data;

    JSArray(JSContext ctx, long pointer) {
        this.ctx = ctx;
        this.pointer = pointer;
        ctx.addCloseable(this);
    }

    @Override public JSContext getContext() {
        return ctx;
    }

    @Override public long getPointer() {
        return pointer;
    }

    final boolean isClosed() {
        return pointer == 0;
    }

    @Override public int size() {
        return getData().size();
    }

    private Object _get(int ix, List<Object> l) {
        Object value = l.get(ix);
        if (value == UNSET) {
            value = ctx.unpack(ctx.getRuntime().fnArrayGet(this, ix));
            l.set(ix, value);
        }
        return value;
    }

    @Override public Object get(int ix) {
        Object value = _get(ix, getData());
        if (value instanceof RuntimeException) {
            throw (RuntimeException)value;
        }
        return value;
    }

    @Override public Object set(int ix, Object value) {
        List<Object> l = getData();
        Object oldvalue = _get(ix, l);
        ctx.getRuntime().fnArraySet(this, ix, ctx.pack(value));
        l.set(ix, value);
        return oldvalue;
    }

    @Override public void add(int ix, Object value) {
        List<Object> l = getData();
        ctx.getRuntime().fnArrayAdd(this, ix, ctx.pack(value));
        l.add(ix, value);
    }

    @Override public Object remove(int ix) {
        List<Object> l = getData();
        Object oldvalue = _get(ix, l);
        ctx.getRuntime().fnArrayRemove(this, ix);
        l.remove(ix);
        return oldvalue;
    }

    private List<Object> getData() {
        final int contextGeneration = ctx.getGeneration();
        if (data == null || generation < contextGeneration) {
            synchronized(this) {
                if (data == null || generation < contextGeneration) {
                    int size = ctx.getRuntime().fnArraySize(this);
                    data = new ArrayList<Object>(size);
                    for (int i=0;i<size;i++) {
                        data.add(UNSET);
                    }
                    generation = contextGeneration;
                }
            }
        }
        return data;
    }

    @Override public void close() throws Exception {
        if (!isClosed()) {
            ctx.getRuntime().fnArrayClose(this);
            pointer = 0;
        }
    }

}
