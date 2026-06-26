package com.bfo.quickjs;

import java.util.function.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A CompleteableFuture that mirrors a JS Promise.
 * When this future is completed in Java, the next call to context.poll() will complete the JS promise.
 * When the JS promise is completed, this Future is completed to match.
 */
public class JSPromise extends CompletableFuture<Object> implements JSType, AutoCloseable {

    private final JSContext ctx;
    private final int index;
    private volatile long pointer;
    private volatile boolean completedByJS; // has the JS promise that is equivalent to this been completed?
    private Task<?> task;

    JSPromise(JSContext ctx, long pointer, int index) {
        this.ctx = ctx;
        this.pointer = pointer;
        this.index = index;
        handle(new BiFunction<Object,Throwable,Object>() {
            public Object apply(final Object value, final Throwable ex) {
                JSPromise.this.ctx.notifyPromiseCompleted(JSPromise.this, value, ex, completedByJS);
                return null;
            }
        });
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

    int getIndex() {
        return index;
    }

    @Override public void close() throws Exception {
        if (!isClosed()) {
            ctx.getRuntime().fnPromiseClose(this);
            pointer = 0;
        }
    }

    void notifyCompletedByJS() {
        completedByJS = true;
    }

    @Override public String toString() {
        return "[JSPromise:" + ctx.getPointer()+"." + getIndex()+" @" + getPointer() + " state="+state()+"]";
    }

    /**
     * Set the Task that created this promise
     */
    void setTask(Task<?> task) {
        this.task = task;
    }
    /**
     * Retrieve the Task that created this promise
     */
    Task<?> getTask() {
        return task;
    }
}
