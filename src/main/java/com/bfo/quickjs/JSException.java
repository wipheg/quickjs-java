package com.bfo.quickjs;

/**
 * Exception thrown when a QuickJS script throws an exception. It contains the
 * message and the stack trace. If a java callback throws an exception, it is
 * wrapped into a JSException. The original message is kept, but the Java
 * stacktrace will be replaced by the JS stacktrace.
 */
public class JSException extends RuntimeException {

    private final String stack;         // JS stack

    JSException(String message, String stack) {
        this(message, stack, null);
    }

    JSException(String message, String stack, Throwable cause) {
        super(message, cause);
        this.stack = stack;
    }

    /**
     * Returns the string containing the JS exception stack, or null if unset
     */
    public String getStack() {
        return stack;
    }

}
