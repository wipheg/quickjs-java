package com.bfo.quickjs;

/**
 * A generic type interface for JS objects
 */
public interface JSType {

    /**
     * Return an opaque pointer to the JS object, or 0 if the object is closed
     * and should no longer be used
     */
    long getPointer();

    /**
     * Return the JS context this type belongs to
     */
    JSContext getContext();

}
