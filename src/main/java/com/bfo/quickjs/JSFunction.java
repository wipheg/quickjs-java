package com.bfo.quickjs;

import java.util.*;

/**
 * Represents a JavaScript Function type
 */
public class JSFunction implements JSType {

    final JSContext ctx;
    final long pointer;
    final String name;
    private Boolean constructor;

    JSFunction(JSContext ctx, String name, long pointer) {
        this.ctx = ctx;
        this.name = name;
        this.pointer = pointer;
    }

    public String getName() {
        return name;
    }

    @Override public long getPointer() {
        return pointer;
    }

    @Override public JSContext getContext() {
        return ctx;
    }

    /** 
     * Return true if the function is a constructor function,
     * false if it is a regular function
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

}
