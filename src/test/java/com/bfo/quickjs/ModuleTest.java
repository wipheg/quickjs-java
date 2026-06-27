package com.bfo.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public class ModuleTest {

    void debug(JSRuntime runtime, String msg, Object... args) {
        runtime.getLogger().log(JSLogger.DEBUG, msg, args);
    }

    private static JSRuntime newRuntime() {
        JSRuntime runtime = new JSRuntime();
        runtime.setStderr(System.err);
        runtime.setStdout(System.out);
        runtime.setLogger(JSLogger.toStream(6, System.out));
        return runtime;
    }

    //----------------------------------------------------------------------------

    @Test
    public void testModuleImport() throws Exception {
        String moduleScript = "export function add(a, b) { return a+b; }";
        String mainScript = "import { add } from 'module.js'; globalThis.result = add(1, 2)";

        JSModuleResolver resolver = new JSModuleResolver() {
            @Override public String normalize(String path, String base) {
                return path;
            }
            @Override public String load(String module) {
                assertEquals("module.js", module);
                return moduleScript;
            }
        };

        try (JSRuntime runtime = newRuntime().setModuleResolver(resolver); JSContext context = runtime.newContext()) {
            context.evalModule("test.js", mainScript).join();
            Object result = context.evalNow("result");
            assertEquals(3, result);
        }
    }

    @Test
    public void testModuleGlobal() throws Exception {
        final String moduleScript = "let a = 0; export function set(x) { a=x; }; export function get() { return a; }";
        final String mainScript = "import { set } from 'module.js'; set(12)";
        final String secondaryScript = "import { get } from 'module.js'; globalThis.result = get()";

        JSModuleResolver resolver = new JSModuleResolver() {
            @Override public String normalize(String path, String base) {
                return path;
            }

            @Override public String load(String module) {
                assertEquals("module.js", module);
                return moduleScript;
            }
        };

        try (JSRuntime runtime = newRuntime().setModuleResolver(resolver); JSContext context = runtime.newContext()) {
            context.evalModule("first.js", mainScript).join();
            context.evalModule("second.js", secondaryScript).join();
            Object result = context.eval("result").get();
            assertEquals(12, result);
        }
    }

    @Test
    public void testModuleError1() throws Exception {
        String mainScript = "import { add } from 'module.js'; globalThis.result = add(1, 2)";
        JSModuleResolver resolver = new JSModuleResolver() {
            @Override public String normalize(String path, String base) {
                return null;
            }

            @Override public String load(String module) {
                return "";
            }
        };

        try (JSRuntime runtime = newRuntime().setModuleResolver(resolver); JSContext context = runtime.newContext()) {
            try {
                context.evalModule("first.js", mainScript).join();
                fail("should have failed");
            } catch (Exception e) {
                assertEquals("Failed resolving module \"module.js\" from \"first.js\"", e.getCause().getMessage());
            }
        }
    }

    @Test
    public void testModuleError3() throws Exception {
        String mainScript = "import { add } from 'module.js'; globalThis.result = add(1, 2)";
        JSModuleResolver resolver = new JSModuleResolver() {
            @Override public String normalize(String path, String base) {
                return path;
            }

            @Override public String load(String module) {
                return null;
            }
        };

        try (JSRuntime runtime = newRuntime().setModuleResolver(resolver); JSContext context = runtime.newContext()) {
            try {
                context.evalModule("first.js", mainScript).join();
                fail("should have failed");
            } catch (Exception e) {
                assertEquals("Failed loading module \"module.js\"", e.getCause().getMessage());
            }
        }
    }

}
