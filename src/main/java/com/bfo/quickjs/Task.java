package com.bfo.quickjs;

import java.util.concurrent.*;

public abstract class Task<T> extends CompletableFuture<T> implements RunnableFuture<T> {

    JSRuntime runtime;
    final String tostring;

    Task(String tostring) {
        this.tostring = tostring;
    }

    void setRuntime(JSRuntime runtime) {
        this.runtime = runtime;
    }

    public String toString() {
        return tostring;
    }

    public JSRuntime getRuntime() {
        return runtime;
    }

    void completeOrChain(Object result) {
        if (result instanceof JSPromise) {
            JSPromise promise = (JSPromise)result;
            if (isCancelled()) {
                promise.cancel(false);
            } else {
                runtime.getLogger().log(JSLogger.DEBUG, "Promise chained, now waiting on {}", promise);
                promise.handle((Object subresult, Throwable error) -> {
                    if (error != null) {
                        completeExceptionally(error);
                    } else {
                        completeOrChain(subresult);
                    }
                    return null;
                });
            }
        } else {
            @SuppressWarnings("unchecked") T cast = (T)result;
            complete(cast);
        }
    }

}
