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
    private long pointer;
    private final int index;
    private volatile boolean promiseCompleted; // has the JS promise that is equivalent to this been completed?

    JSPromise(JSContext ctx, long pointer, int index) {
        this.ctx = ctx;
        this.pointer = pointer;
        this.index = index;

        handle(new BiFunction<Object,Throwable,Object>() {
            public Object apply(final Object value, final Throwable ex) {
                if (!promiseCompleted) {
                    ctx.pollQueue(new Runnable() {
                        public void run() {
                            if (ex != null) {
                                ctx.getRuntime().getLogger().log(JSRuntime.Logger.DEBUG, "JS promise {} rejected", pointer, ex);
                                byte[] data = ctx.pack(ex);
                                ctx.getRuntime().fnPromiseResolve(JSPromise.this, data);
                            } else {
                                ctx.getRuntime().getLogger().log(JSRuntime.Logger.DEBUG, "JS promise {} resolved: {}", pointer, value);
                                byte[] data = ctx.pack(value);
                                ctx.getRuntime().fnPromiseResolve(JSPromise.this, data);
                            }
                        }
                    });
                }
                return null;
            }
        });
    }

    @Override public long getPointer() {
        return pointer;
    }

    int getIndex() {
        return index;
    }

    @Override public JSContext getContext() {
        return ctx;
    }

    @Override public void close() throws Exception {
        if (pointer != 0) {
            ctx.getRuntime().fnPromiseClose(this);
            pointer = 0;  
        }
    }

    void notifyCompletedByJS() {
        promiseCompleted = true;
    }

}
