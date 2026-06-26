package com.bfo.quickjs;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Each JSRuntime uses a TaskManager to queue tasks on the runtime,
 * and the Thread that occurs on depends on the TaskManager.
 * The default TaskManager is {@link #useSharedThread}
 * @see JSRuntime#setTaskManager
 */
public abstract class TaskManager {
    
    private static final Task<Object>  DONE = new Task<>("close") {
        @Override public void run() { }
    };

    private static TaskManager shared, own, current;

    /**
     * Enqueue a task to run immediately on the current thread if possible, or
     * queued to run later if not.
     */
    public <T> Task<T> doNow(Task<T> task) {
        if (Thread.currentThread().threadId() == task.getRuntime().getThreadId()) {
            task.run();
            return task;
        } else {
            return doLater(task);
        }
    }

    /**
     * Enqueue a task to run later on the appropriate thread.
     */
    public abstract <T> Task<T> doLater(Task<T> task);

    /**
     * Notify the worker that the runtime has been closed
     */
    public abstract void remove(JSRuntime runtime);

    /**
     * Return a worker that will interleave all tasks for multiple JSRuntimes onto a single thread.
     */
    public synchronized static TaskManager useSharedThread() {
        if (shared == null) {
            shared = new TaskManager() {
                private int dependents;
                private Thread thread;
                private LinkedBlockingDeque<Task<? extends Object>> queue = new LinkedBlockingDeque<>();

                private synchronized Thread getThread() {
                    if (thread == null) {
                        thread = new Thread() {
                            public void run() {
                                Task<?> r;
                                try {
                                    while ((r=queue.takeFirst()) != DONE) {
                                        r.getRuntime().getLogger().log(JSLogger.DEBUG, "Task [{}]", r.tostring);
                                        try {
                                            r.run();
                                        } catch (RuntimeException e) {
                                            if (!r.isDone()) {
                                                r.completeExceptionally(e);
                                            }
                                        }
                                    }
                                } catch (InterruptedException e) {}
                                thread = null;
                            }
                        };
                        thread.setName("JSRuntime Shared Worker");
                        thread.setDaemon(true);
                        thread.start();
                        dependents++;
                    }
                    return thread;
                }

                private synchronized void clearThread() {
                    if (dependents == 1) {
                        try {
                            queue.clear();
                            queue.putFirst(DONE);
                        } catch (InterruptedException e) {}
                        thread = null;
                        dependents = 0;
                    }
                }

                @Override public <T> Task<T> doLater(Task<T> task) {
                    Thread thread = getThread();
                    if (task.getRuntime().getThreadId() != thread.threadId()) {
                        task.getRuntime().setThreadId(thread.threadId());
                        synchronized(this) {
                            dependents++;
                        }
                    }
                    try {
                        queue.putLast(task);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    return task;
                }

                @Override public synchronized void remove(JSRuntime runtime) {
                    dependents--;
                    if (dependents == 1) {
                        ForkJoinPool.commonPool().schedule(new Runnable() {
                            public void run() {
                                clearThread();
                            }
                        }, 1000, TimeUnit.MILLISECONDS);
                    }
                }

                @Override public String toString() {
                    return "TaskManager-sharedThread";
                }
            };
        }
        return shared;
    }

    /**
     * Return a worker that will create a new thread for each JSRuntime
     * on demand, and close the thread immediately the runtime is closed.
     */
    public synchronized static TaskManager useOwnThread() {
        if (own == null) {
            own = new TaskManager() {
                private final ConcurrentHashMap<JSRuntime,ThreadDeque> map = new ConcurrentHashMap<>();

                @Override public <T> Task<T> doLater(Task<T> task) {
                    final JSRuntime runtime = task.getRuntime();
                    final ThreadDeque tq = map.computeIfAbsent(runtime, new Function<JSRuntime,ThreadDeque>() {
                        public ThreadDeque apply(JSRuntime runtime) {
                            final LinkedBlockingDeque<Task<?>> queue = new LinkedBlockingDeque<>();
                            final Thread thread = new Thread() {
                                public void run() {
                                    Task<?> r;
                                    try {
                                        while ((r=queue.takeFirst()) != DONE) {
                                            r.getRuntime().getLogger().log(JSLogger.DEBUG, "Task [{}]", r.tostring);
                                            try {
                                                r.run();
                                            } catch (RuntimeException e) {
                                                if (!r.isDone()) {
                                                    r.completeExceptionally(e);
                                                }
                                            }
                                        }
                                    } catch (InterruptedException e) {}
                                    map.remove(runtime);
                                }
                            };
                            thread.setName("JSRuntime Worker for " + runtime.getPointer());
                            thread.setDaemon(true);
                            thread.start();
                            runtime.setThreadId(thread.threadId());
                            return new ThreadDeque(thread, queue);
                        }
                    });
                    try {
                        tq.queue.putLast(task);
                    } catch (InterruptedException ex) {
                        throw JSRuntime.toRuntimeException(ex);
                    }
                    return task;
                }

                @Override public void remove(JSRuntime runtime) {
                    final ThreadDeque tq = map.remove(runtime);
                    if (tq != null) {
                        try {
                            tq.queue.clear();
                            tq.queue.putFirst(DONE);
                            tq.thread.interrupt();      // Belt and braces
                        } catch (InterruptedException ex) { }
                    }
                }
                @Override public String toString() {
                    return "TaskManager-ownThread";
                }
            };
        }
        return own;
    }

    /**
     * A TaskManager that runs everything on the current thread.
     * It will be necessary to call {@link JSContext#poll} at regular
     * intervals to evaluate any pending asynchronous operations
     */
    public synchronized static TaskManager useCurrentThread() {
        if (current == null) {
            current = new TaskManager() {
                @Override public <T> Task<T> doLater(Task<T> task) {
                    task.run();
                    return task;
                }
                @Override public void remove(JSRuntime runtime) {
                }
                @Override public String toString() {
                    return "TaskManager-currentThread";
                }
            };
        }
        return current;
    }

    private static class ThreadDeque {
        final Thread thread;
        final LinkedBlockingDeque<Task<?>> queue;
        ThreadDeque(Thread thread, LinkedBlockingDeque<Task<?>> queue) {
            this.thread = thread;
            this.queue = queue;
        }
    }

}
