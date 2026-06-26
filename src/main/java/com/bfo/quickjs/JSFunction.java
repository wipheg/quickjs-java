package com.bfo.quickjs;

import java.util.*;

/**
 * Represents a JavaScript Function type
 */
public class JSFunction implements JSType, AutoCloseable {

    private final JSContext ctx;
    private final String name;
    private volatile long pointer;
    private Boolean constructor;
    private int index;

    JSFunction(JSContext ctx, String name, long pointer) {
        this.ctx = ctx;
        this.name = name;
        this.pointer = pointer;
        ctx.addCloseable(this);
    }

    /**
     * Return the name of the function
     */
    public String getName() {
        return name;
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


    void setIndex(int index) {
        this.index = index;
    }

    /** 
     * Return true if the function may be called as a Constructor.
     * A function may be both Callable and Constructable!
     */
    public boolean isConstructor() {
        if (constructor == null) {
            constructor = ctx.getRuntime().fnFunctionIsConstructor(this);
        }
        return constructor;
    }

    /**
     * Call the function
     * @param args the list of arguments for the function
     * @return the function result
     */
    public Object call(Object... args) {
        byte[] data = ctx.pack(List.of(args));
        data = ctx.getRuntime().fnFunctionCall(this, data);
        return ctx.unpack(data);
    }

    /**
     * Call the function as a constructor
     * @param args the list of arguments for the function
     * @return the function result
     */
    public Object construct(Object... args) {
        byte[] data = ctx.pack(List.of(args));
        data = ctx.getRuntime().fnFunctionConstruct(this, data);
        return ctx.unpack(data);
    }

    public String toString() {
        return "{JSFunction " + ctx.getPointer() + "/" + index + "}";
    }

    @Override public void close() throws Exception {
        if (!isClosed()) {
            ctx.getRuntime().fnFunctionClose(this);
            pointer = 0;
        }
    }
}
