package com.bfo.quickjs;

/**
 * A generic type interface for JS objects
 */
public interface JSType {

    /**
     * Return an opaque pointer to the JS object
     */
    long getPointer();

    /**
     * Return the JS context this type belongs to
     */
    JSContext getContext();
}
