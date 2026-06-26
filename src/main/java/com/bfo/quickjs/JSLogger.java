package com.bfo.quickjs;

import java.util.*;
import java.io.*;
import java.nio.*;

/** 
 * A simple generic Logger interface, for extensible logging
 */
public interface JSLogger {

    public static final int TRACE = 5, DEBUG = 4, INFO = 3, WARN = 2, ERROR = 1;

    /**
     * Return true if the specified log level is loggable
     */
    public boolean isLoggable(int level);

     /**
      * Trivial logging interface which takes a Message string that may include "{}", one
      * or more objects to insert into that message, and an optional final argument which
      * is a Throwable. eg <code>log(INFO, "Ignoring exception from {}", source, exception);</code>
      * @param level the level
      * @param message the message template
      * @param args the arguments to insert into the template, followed by an optional exception
      */
    public void log(int level, String message, Object... args);

    /**
     * Create a new JSLogger that logs to the specified Appendable
     * @param maxlevel the logging level 
     * @param out the Appendable
     */
    public static JSLogger toStream(final int maxlevel, final Appendable out) {
        Writer w;
        if (!(out instanceof Writer)) {
            w = new Writer() {
                @Override public void close() throws IOException {
                    if (out instanceof Closeable) {
                        ((Closeable)out).close();
                    }
                }
                @Override public void flush() {
                }
                @Override public void write(char[] buf, int off, int len) throws IOException {
                    out.append(CharBuffer.wrap(buf, off, len));
                }
            };
        } else {
            w = (Writer)out;
        }
        final PrintWriter pw = new PrintWriter(w);
        return new JSLogger() {
            @Override public boolean isLoggable(int level) {
                return level <= maxlevel;
            }
            @Override public void log(int level, String msg, Object... args) {
                if (isLoggable(level)) {
                    Throwable e = null;
                    if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                        e = (Throwable)args[args.length - 1];
                        args = Arrays.copyOf(args, args.length - 1);
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("# ");
                    sb.append("T" + Thread.currentThread().threadId() + " ");
                    sb.append("[");
                    switch (level) {
                        case 1: sb.append("error"); break;
                        case 2: sb.append("warning"); break;
                        case 3: sb.append("info"); break;
                        case 4: sb.append("debug"); break;
                        case 5: sb.append("trace"); break;
                        default: sb.append("level" + level);
                    }
                    sb.append("]: ");
                    sb.append(JSRuntime.format(msg, args));
                    sb.append("\n");
                    pw.append(sb);
                    if (e != null) {
                        e.printStackTrace(pw);
                    }
                }
            }
        };
    }

    /**
     * Create a logger that logs to the "com.bfo.quickjs" System.Logger
     */
    public static JSLogger toSystem() {
        return toSystem(JSRuntime.class.getPackage().getName());
    }

    /**
     * Create a logger that logs to the specified System.Logger
     * @param name the logger name.
     */
    public static JSLogger toSystem(String name) {
        final System.Logger logger = System.getLogger(name);
        return new JSLogger() {
            private static System.Logger.Level[] LEVELS = new System.Logger.Level[] {
                System.Logger.Level.OFF,
                System.Logger.Level.ERROR,
                System.Logger.Level.WARNING,
                System.Logger.Level.INFO,
                System.Logger.Level.DEBUG,
                System.Logger.Level.TRACE
            };
            @Override public boolean isLoggable(int level) {
                return logger.isLoggable(LEVELS[level]);
            }
            @Override public void log(int level, String msg, Object... args) {
                if (isLoggable(level)) {
                    Throwable e = null;
                    if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                        e = (Throwable)args[args.length - 1];
                        args = Arrays.copyOf(args, args.length - 1);
                    }
                    logger.log(LEVELS[level], JSRuntime.format(msg, args), e);
                }
            }
        };
    }

}
