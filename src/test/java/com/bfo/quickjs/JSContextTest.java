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


public class JSContextTest {

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

    /**
     * All supported java types can be returned from the eval function
     * 
     * @throws Exception
     */
    @Test
    public void testReturnValuesFromEval() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {
            // Test return of null
            {
                Object result = context.evalNow("let ra = null; ra");
                assertEquals(null, result);
            }
            // Test return of undefined
            {
                Object result = context.evalNow("let rb = undefined; rb");
                assertEquals(null, result);
            }
            // Test return of integer
            {
                Object result = context.evalNow("3 + 1");
                assertInstanceOf(Integer.class, result);
                assertEquals(4, result);
            }
            // Test return of string
            {
                Object result = context.evalNow(" 'Hello ' + 'World'");
                assertInstanceOf(String.class, result);
                assertEquals("Hello World", result);
            }
            // Test return of boolean
            {
                Object result = context.evalNow(" true");
                assertInstanceOf(Boolean.class, result);
                assertEquals(true, result);
            }
            // Test return of double
            {
                Object result = context.evalNow(" 3.14");
                assertInstanceOf(Double.class, result);
                assertEquals(3.14, result);
            }
            // Test return of (heterogeneous and nested) array
            {
                Object result = context.evalNow("[ 3.14, 42, 'hello', true, [1,2,3]]");
                assertInstanceOf(List.class, result);
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) result;
                assertEquals(5, list.size());
                assertEquals(3.14, list.get(0));
                assertEquals(42, list.get(1));
                assertEquals("hello", list.get(2));
                assertEquals(true, list.get(3));
                assertInstanceOf(List.class, list.get(4));
                @SuppressWarnings("unchecked")
                List<Object> nestedList = (List<Object>) list.get(4);
                assertEquals(3, nestedList.size());
                assertEquals(1, nestedList.get(0));
                assertEquals(2, nestedList.get(1));
                assertEquals(3, nestedList.get(2));
            }
            // Test return of (heterogeneous and nested) object
            {
                Object result = context
                        .evalNow("let r = { a: 3.14, b: 42, c: 'hello', d: true, e: [1,2,3], f: {g: 42}}; r");
                assertInstanceOf(Map.class, result);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) result;
                assertEquals(6, map.size());
                assertEquals(3.14, map.get("a"));
                assertEquals(42, map.get("b"));
                assertEquals("hello", map.get("c"));
                assertEquals(true, map.get("d"));
                assertInstanceOf(List.class, map.get("e"));
                @SuppressWarnings("unchecked")
                List<Object> nestedList = (List<Object>) map.get("e");
                assertEquals(3, nestedList.size());
                assertEquals(1, nestedList.get(0));
                assertEquals(2, nestedList.get(1));
                assertEquals(3, nestedList.get(2));
                assertInstanceOf(Map.class, map.get("f"));
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) map.get("f");
                assertEquals(1, nestedMap.size());
                assertEquals(42, nestedMap.get("g"));
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * Even functions can be returned form the eval function and will be represented
     * as JSFunction in java
     * 
     * @throws Exception
     */
    @Test
    public void testReturnFunctionFromEval() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {
            // Local function
            {
                Object result = context.evalNow("let r = function() { return 42; }; r");
                assertInstanceOf(JSFunction.class, result);
                JSFunction function = (JSFunction) result;
                assertEquals(42, function.call());
                assertEquals(42, function.call());
                assertEquals(42, function.call());
                assertEquals(42, function.call());

                // JS Function can be added back to the js context with a different name
                context.put("f1", function);
                Object r2 = context.evalNow("f1()");
                assertEquals(42, r2);
            }

            // Global function
            {
                Object result = context.evalNow("function a() { return 1; };a");
                assertInstanceOf(JSFunction.class, result);
                JSFunction function = (JSFunction) result;
                assertEquals(1, function.call());
                assertEquals(1, function.call());
                assertEquals(1, function.call());
                assertEquals(1, function.call());

            }
            // Function with arguments
            {
                Object result = context.evalNow("function a(b) { return b + 1; };a");
                assertInstanceOf(JSFunction.class, result);
                JSFunction function = (JSFunction) result;
                assertEquals("a", function.getName());
                assertEquals(42, function.call(41));
                assertEquals(1, function.call(0));
                assertEquals(2, function.call(1));
            }

        }
    }

    //----------------------------------------------------------------------------

    /**
     * All supported java types can be set as global values in the JS context
     * 
     * @throws Exception
     */
    @Test
    public void testSetGlobal() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {
            {
                context.put("a", 42);
                Object result = context.evalNow("a");
                assertEquals(42, result);
            }
            {
                context.put("a", "hello");
                Object result = context.evalNow("a");
                assertEquals("hello", result);
            }
            {
                context.put("a", true);
                Object result = context.evalNow("a");
                assertEquals(true, result);
            }
            {
                context.put("a", 3.14);
                Object result = context.evalNow("a");
                assertEquals(3.14, result);
            }
            {
                context.put("a", List.of(1, 2, 3));
                Object result = context.evalNow("a[0]");
                assertEquals(1, result);
            }
            {
                context.put("a", Map.of("b", 42));
                Object result = context.evalNow("a.b");
                assertEquals(42, result);
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * All supported java types can be retrieved from the global quickjs context
     * 
     * @throws Exception
     */
    @Test
    public void testGetGlobal() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {
            {
                context.put("a", 42);
                Object result = context.get("a");
                assertEquals(42, result);
            }
            {
                context.put("a", "hello");
                Object result = context.get("a");
                assertEquals("hello", result);
            }
            {
                context.put("a", true);
                Object result = context.get("a");
                assertEquals(true, result);
            }
            {
                context.put("a", 3.14);
                Object result = context.get("a");
                assertEquals(3.14, result);
            }
            {
                context.put("a", List.of(1, 2, 3));
                Object result = context.get("a");
                assertEquals(List.of(1, 2, 3), result);
            }
            {
                context.put("a", Map.of("b", 42));
                Object result = context.get("a");
                assertEquals(Map.of("b", 42), result);
            }
            {
                // Check Context is a regular Map with keyset, remove etc
                assertTrue(context.keySet().contains("a"));
                int size = context.size();
                context.remove("a");
                assertFalse(context.keySet().contains("a"));
                assertTrue(context.size() + 1 == size);
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * JS Object will be just wrapped as JSObject. All modifications on the
     * object will be visible on both the Java and the JS Side
     * 
     * @throws Exception
     */
    @Test
    public void testNativeObjects() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            context.evalNow("var a = {a: 1, b: 'Hello'};");
            Object result = context.get("a");
            assertInstanceOf(JSObject.class, result);

            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) result;
            assertFalse(obj.isEmpty());
            assertTrue(obj.containsValue(1));
            assertTrue(obj.containsValue("Hello"));

            assertTrue(obj.containsKey("a"));
            assertTrue(obj.containsKey("b"));
            assertEquals(2, obj.size());
            assertEquals(1, obj.get("a"));
            assertEquals("Hello", obj.get("b"));

            Set<String> keys = obj.keySet();
            assertEquals(2, keys.size());
            assertTrue(keys.contains("a"));
            assertTrue(keys.contains("b"));

            assertNull(obj.get("c"));
            Object r0 = context.evalNow("a.c");
            assertNull(r0);

            // One can add a value on Java side, and its visible on JS
            obj.put("c", 42);
            assertEquals(3, obj.size());
            assertEquals(1, obj.get("a"));
            assertEquals("Hello", obj.get("b"));
            assertEquals(42, obj.get("c"));
            Object r1 = context.evalNow("a.c");
            assertEquals(42, r1);

            // One can modify a value on java side and its visible on JS
            obj.put("a", 10);
            assertEquals(10, obj.get("a"));
            Object r2 = context.evalNow("a.a");
            assertEquals(10, r2);

            // One can remove a value on java side and its visible on JS
            obj.remove("b");
            assertEquals(2, obj.size()); // a and c
            assertEquals(10, obj.get("a"));
            Object r3 = context.evalNow("a.b");
            assertEquals(null, r3);
        }
    }

    //----------------------------------------------------------------------------

    /**
     * One can directly create native JS object on the java side using JSObject
     * 
     * @throws Exception
     */
    @Test
    public void testNativeObjectFromJavaSide() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            Map<String, Object> map = context.newObject();
            map.put("a", "Hello");
            map.put("b", "World");
            context.put("a", map);
            assertEquals(2, map.keySet().size());

            Object result = context.evalNow("a.a");
            assertEquals("Hello", result);

            result = context.evalNow("a.b");
            assertEquals("World", result);

            result = context.evalNow("a.c");
            assertEquals(null, result);

            // One can add a value on JS side, and its visible on Java
            context.evalNow("a.c = 'test_value'");
            assertEquals("test_value", map.get("c"));

            // One can modify a value on JS side, and its visible on Java
            context.evalNow("a.a = 'test_value'");
            assertEquals("test_value", map.get("a"));

            // One can remove a value on JS side, and its visible on Java
            context.evalNow("delete a.b");
            assertEquals(null, map.get("b"));

        }
    }

    //----------------------------------------------------------------------------

    /**
     * JS Arrays will be just wrapped as JSArrays. All modifications on the
     * array will be visible on both the Java and the JS side.
     */
    @Test
    public void testNativeArrays() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            context.evalNow("var a = [1, 2, 3];");
            Object result = context.get("a");
            assertInstanceOf(JSArray.class, result);
            @SuppressWarnings("unchecked")
            List<Object> array = (List<Object>) result;
            assertEquals(3, array.size());
            assertEquals(1, array.get(0));
            assertEquals(2, array.get(1));
            assertEquals(3, array.get(2));

            // One can add a value on Java side, and its visible on JS
            array.add(4);
            assertEquals(4, array.size());
            assertEquals(1, array.get(0));
            assertEquals(2, array.get(1));
            assertEquals(3, array.get(2));
            assertEquals(4, array.get(3));
            Object r1 = context.evalNow("a[3]");
            assertEquals(4, r1);

            // Even add inbetween
            array.add(1, 9);
            assertEquals(5, array.size());
            assertEquals(1, array.get(0));
            assertEquals(9, array.get(1));
            assertEquals(2, array.get(2));

            // One can modify a value on java side and its visible on JS
            array.set(0, 10);
            assertEquals(10, array.get(0));
            Object r2 = context.evalNow("a[0]");
            assertEquals(10, r2);

            // One can remove a value on java side and its visible on JS
            array.remove(0);
            assertEquals(4, array.size());
            Object r3 = context.evalNow("a[0]");
            assertEquals(9, r3);

        }
    }

    //----------------------------------------------------------------------------

    /**
     * One can create native JS arrays directly on the java side using JSArrays
     * 
     * @throws Exception
     */
    @Test
    public void testNativeArraysFromJavaSide() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            List<Object> list = context.newArray();
            list.add("a");
            list.add("b");
            assertEquals(2, list.size());

            context.put("a", list);
            assertEquals("a", context.evalNow("a[0]"));
            assertEquals("b", context.evalNow("a[1]"));

            // Modification on the js side are visible in the java object

            // Add element
            context.evalNow("a[2]='c';");
            assertEquals(3, list.size());
            assertEquals("c", list.get(2));

            // Reassign element
            context.evalNow("a[0]='d'");
            assertEquals(3, list.size());
            assertEquals("d", list.get(0));

            // Delete elements
            context.evalNow(" a.splice(2, 1);  a.splice(1, 1);");
            assertEquals(1, list.size());
            assertEquals("d", list.get(0));

        }
    }

    //----------------------------------------------------------------------------

    /**
     * Several java functions can be put into the quickjs context and called as if
     * they are native js functions
     * 
     * @throws Exception
     */
    @Test
    public void exportJavaFunctionsToJS() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            BiFunction<Integer, Integer, Integer> add = (a, b) -> {
                return a + b;
            };

            Function<List<Integer>, Integer> square = (a) -> {
                return a.get(0) * a.get(0);
            };

            Function<List<Integer>, Integer> clamp = (l) -> {
                int min = l.get(0);
                int val = l.get(1);
                int max = l.get(2);
                return Math.max(min, Math.min(val, max));
            };

            Supplier<Integer> random = () -> {
                return 42;
            };

            Function<Object, Object> generic = (a) -> {
                return a == null ? "null" : a.toString();
            };

            AtomicInteger counter = new AtomicInteger();
            Consumer<Integer> count = (a) -> {
                counter.set(a);
            };

            AtomicInteger adder = new AtomicInteger();
            BiConsumer<Integer, Integer> combine = (a, b) -> {
                adder.set(a + b);
            };

            context.put("add", add);
            context.put("square", square);
            context.put("random", random);
            context.put("count", count);
            context.put("combine", combine);
            context.put("clamp", clamp);
            context.put("generic", generic);

            Object result = context.evalNow("add(1, 2)");
            assertEquals(3, result);

            result = context.evalNow("square(2)");
            assertEquals(4, result);

            result = context.evalNow("random()");
            assertEquals(42, result);

            result = context.evalNow("clamp(2, 4, 3)");
            assertEquals(3, result);

            result = context.evalNow("count(1)");
            assertEquals(1, counter.get());

            result = context.evalNow("combine(3 , 4)");
            assertEquals(7, adder.get());

            // A function with an argument that is List, or a superclass of List
            // will always see the arguments supplied as a List.
            result = context.evalNow("generic()");
            assertEquals("[]", result);

            result = context.evalNow("generic(1)");
            assertEquals("[1]", result);

            result = context.evalNow("generic(1, 2)");
            assertEquals("[1, 2]", result);

            result = context.evalNow("generic(1, 2, 3)");
            assertEquals("[1, 2, 3]", result);

            // If one retrieves any javafunction from js it is returned as JSFUnction
            Object addBack = context.get("add");
            assertInstanceOf(JSFunction.class, addBack);
        }
    }

    //----------------------------------------------------------------------------

    /**
     * The runtime of the script can be limited in the Runtime object
     * 
     * @throws Exception
     */
    @Test
    public void testScriptRuntimeLimit() throws Exception {
        try (@SuppressWarnings("resource") JSRuntime runtime = newRuntime(); JSContext context = runtime.newContext()) {
            try {
                runtime.setRuntimeLimit(1000);
                context.evalNow("while(true){}");
                fail("Script runtime limit should have been reached");
            } catch (Exception e) {
                debug(runtime, e.getMessage());
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * The memory consumption of scripts can be limited by the runtime object to
     * prevent faulty scripts to overload the host
     */
    @Test
    public void testScriptMemoryLimit() throws Exception {
        try (@SuppressWarnings("resource") JSRuntime runtime = newRuntime(); JSContext context = runtime.newContext()) {
            try {
                runtime.setMemoryLimit(10000);
                context.evalNow(
                        "const memoryHog = [];\nconst chunk = \"M_E_M_O_R_Y_\".repeat(100000);\nwhile (true) {memoryHog.push(chunk); }");
            } catch (JSException e) {
                assertEquals("out of memory", e.getMessage());
            }
        }

    }

    //----------------------------------------------------------------------------

    /**
     * JS exceptions thrown in the script are wrapped as JSException
     * 
     * @throws Exception
     */

    @Test
    public void testExceptionHandling() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {
            try {
                context.evalNow("""
                        let a = 1;
                        let b = 0;
                        let c = a / b;
                        throw new Error('test');
                        """);
                fail("Exception should have been thrown");
            } catch (Exception e) {
                assertInstanceOf(JSException.class, e);
                JSException quickJSException = (JSException) e;
                assertEquals("test", quickJSException.getMessage());
                assertEquals("    at <eval> (eval_script:4:11)\n", quickJSException.getStack());
                debug(runtime, e.getMessage());
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * Java exceptions thrown in java callbacks, are wrapped as js exceptions and
     * then wrapped as JSException. The message remains, the call stack is,
     * however, replaced with the JS Callstack.
     * 
     * @throws Exception
     */
    @Test
    public void testJavaExceptionHandling() throws Exception {
        try (JSRuntime runtime = newRuntime(); JSContext context = runtime.newContext()) {
            try {
                BiFunction<Integer, Integer, Integer> add = (a, b) -> {
                    throw new RuntimeException("test");
                };

                context.put("add", add);
                // This calls the java function add which throws an exception
                context.evalNow("add(1, 2)");

                fail("Exception should have been thrown");
            } catch (Exception e) {
                assertInstanceOf(JSException.class, e);
                JSException quickJSException = (JSException) e;
                assertEquals("test", quickJSException.getMessage());
                debug(runtime, e.getMessage());
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * Invoking a JS function directly from java is possible. Even nested calls to
     * existing objects are supported
     * 
     * @throws Exception
     */
    @Test
    public void testInvokeJSFunction() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            {
                context.evalNow("function a(x, y) { return x + y; };");
                Object result = ((JSFunction)context.get("a")).call(1, 2);
                assertEquals(3, result);
            }
            {
                context.evalNow("var g = {f: function(x, y) { return x + y; }};");
                Object result = ((JSFunction)((JSObject)context.get("g")).get("f")).call(1, 2);
                assertEquals(3, result);
            }
        }
    }

     /**
     * Invoking a JS function directly from java is possible. Even nested calls to
     * existing objects are supported
     *
     * @throws Exception
     */
    @Test
    public void testConstructors() throws Exception {
        JSRuntime runtime = newRuntime();
        JSContext context = runtime.newContext();
        try {
            context.evalNow("class Foo { constructor(x) { this.a = x; } } function Bar(x) { return x; }");
            final JSFunction foo = (JSFunction)context.evalNow("Foo");
            final JSFunction bar = (JSFunction)context.evalNow("Bar");
            assertTrue(foo.isConstructor());
            assertTrue(bar.isConstructor());    // Meh, functions are also constructors
            Object o1 = foo.construct(1);
            Object o2 = bar.construct(1);
            Object o3 = bar.call(1);
            assertEquals(((JSObject)o1).get("a"), 1);
            assertTrue(((JSObject)o2).size() == 0);
            assertEquals(o3, 1);
        } finally {
            runtime.close();
        }
    }

    //----------------------------------------------------------------------------

    public interface TestInterface {
        int add(int a, int b);

        int substract(int a, int b);
    }

   /**
     * JS objects can be retrieved from the QuickJS context and mapped to java
     * interfaces. This way the JS object can be used as if it implements a java
     * interface.
     *
     * @throws Exception
     */
    @Test
    public void mapJSObjectToJavaInterface() throws Exception {
        try (JSRuntime runtime = newRuntime();JSContext context = runtime.newContext()) {
            JSObject obj = (JSObject) context.evalNow(
                    "let obj = {add: function(a, b) { return a + b; }, substract: function(a, b) { return a - b; }}; obj");
            assertNotNull(obj);
            TestInterface testInterface = obj.as(TestInterface.class);
            assertEquals(3, testInterface.add(1, 2));
            assertEquals(1, testInterface.substract(2, 1));
        }
    }

    //----------------------------------------------------------------------------

    /**
     * High-level async support is provided. Promises from
     * {@link JSContext#eval((String)} are wrapped by a
     * CompletableFuture, which will be completed / completed exceptionally as soon
     * as the underlying promise is completed / rejected
     * 
     * @throws Exception
     */
    @Test
    public void promiseSupport() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            CompletableFuture<Object> r1 = context.eval("let trigger;\n" + //
                    "const manualPromise = new Promise((resolve, reject) => {\n" + //
                    "  // Assign the internal resolve function to our outside variable\n" + //
                    "  trigger = resolve; \n" + //
                    "});\n" + //
                    "manualPromise\n");

            CompletableFuture<Object> r2 = context.eval("await trigger(\"Classic resolve\");");
            r1.get(1000, TimeUnit.MILLISECONDS);
            r2.get(1000, TimeUnit.MILLISECONDS);
            assertTrue(true);
        }
    }

    //----------------------------------------------------------------------------

    /**
     * High-level async support is provided. Promises from
     * {@link JSContext#eval((String)} are wrapped by a
     * CompletableFuture, which will be completed as soon
     * as the underlying promise is completed
     * 
     * @throws Exception
     */
    @Test
    public void simplePromiseSupport() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            CompletableFuture<Object> r1 = context.eval("await \"Classic resolve\" ");
            assertEquals("Classic resolve", r1.join());
        }
    }

    //----------------------------------------------------------------------------

    /**
     * High-level async support is provided. Promises from
     * {@link JSContext#eval((String)} are wrapped by a
     * CompletableFuture, which will be completed exceptionally as soon
     * as the underlying promise is rejected
     * 
     * @throws Exception
     */
    @Test
    public void simplePromiseErrSupport() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            CompletableFuture<Object> r1 = context.eval("throw Err('hello') ");
            try {
                r1.get(1000, TimeUnit.MILLISECONDS);
                fail("Should have failed");
            } catch (ExecutionException e) {
                assertTrue(true);
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * Completable futures are internally wrapped with promises and can be treated
     * like promises
     */
    @SuppressWarnings("rawtypes")
    @Test
    public void completableFutureSupport() throws Exception {
        try (JSRuntime runtime = newRuntime(); JSContext context = runtime.newContext()) {
            {
                CompletableFuture<Object> promise = new CompletableFuture<>();
                context.put("p0", promise);

                CompletableFuture<Object> r1 = context.eval("await p0");
                Thread.sleep(100);
                assertFalse(((CompletableFuture) r1).isDone());
                promise.complete(53);
                assertEquals(53, promise.join());
                assertEquals(53, r1.join());
            }
            {
                CompletableFuture<Object> promise = new CompletableFuture<>();
                //
                context.put("p", promise);
                CompletableFuture result = context.eval("await p.then((v) => { return v * 3; });");
                Thread.sleep(100);
                assertFalse(((CompletableFuture) result).isDone());
                promise.complete(54);
                assertEquals(162, ((CompletableFuture) result).join());
            }
        }
    }

    //----------------------------------------------------------------------------

    /**
     * On can return completable futures from java functions and these are treated
     * like promises on the js side
     */
    @SuppressWarnings("rawtypes")
    @Test
    public void functionsCanReturnCompletableFutures() throws Exception {
        try (JSRuntime runtime = newRuntime();
                JSContext context = runtime.newContext()) {

            CompletableFuture<Integer> cf = new CompletableFuture<>();

            Supplier<CompletableFuture<Integer>> answer = () -> {
                return cf;
            };

            context.put("answer", answer);

            CompletableFuture<?> result = context.eval("await answer()");
            assertFalse(((CompletableFuture) result).isDone());         // Result is not completed here
            Thread.sleep(100);
            assertFalse(((CompletableFuture) result).isDone());         // Even after emptying the event pipeline it is still not completed
            cf.complete(42);
            assertEquals(42, ((CompletableFuture) result).join());
        }
    }

    //----------------------------------------------------------------------------

    @Test
    public void testPromiseCanDependOnFutureDirect() throws Exception {
        try (JSRuntime runtime = newRuntime(); JSContext context = runtime.newContext()) {

            final Object[] answer = { null };
            context.put("store", new Consumer<Object>() {
                @Override public void accept(Object o) {
                    answer[0] = o;
                }
            });
            context.put("answer", new Supplier<Object>() {
                public Object get() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(500); } catch (Exception e) {}
                            runtime.getLogger().log(JSLogger.DEBUG, "Completing future in background thread");
                            future.complete("done");
                        }
                    }).start();
                    return future;
                }
            });

            CompletableFuture<?> result = (CompletableFuture<?>)context.evalNow("answer().then(x => \"all \" + x).then(store)");
            result.get(1000, TimeUnit.MILLISECONDS);
            assertEquals("all done", answer[0]);
        }
    }

    //----------------------------------------------------------------------------

    @Test
    public void testPromiseCanDependOnFutureAsync() throws Exception {
        try (JSRuntime runtime = newRuntime(); JSContext context = runtime.newContext()) {

            context.put("answer", new Supplier<Object>() {
                public Object get() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(500); } catch (Exception e) {}
                            runtime.getLogger().log(JSLogger.DEBUG, "Completing future in background thread");
                            future.complete("done");
                        }
                    }).start();
                    return future;
                }
            });

            CompletableFuture<?> result = context.eval("await new Promise((resolve) => { answer().then(resolve) })");
            assertEquals("done", result.get(1000, TimeUnit.MILLISECONDS));
        }
    }

    //----------------------------------------------------------------------------

    @Test
    public void testPromiseErrorInFutureDirect() throws Exception {
        JSRuntime runtime = newRuntime();
        JSContext context = runtime.newContext();
        try {
            context.put("answer", new Supplier<Object>() {
                public Object get() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(500); } catch (Exception e) {}
                            runtime.getLogger().log(JSLogger.DEBUG, "Completing future in background thread");
                            future.complete("done");
                        }
                    }).start();
                    return future;
                }
            });
            CompletableFuture<?> result = (CompletableFuture<?>)context.evalNow("answer().then(x => { throw new Error(\"failed\"); } )");
            result.get(1000, TimeUnit.MILLISECONDS);
            fail("Should have failed");
        } catch (ExecutionException e) {
            assertEquals("Promise rejected", e.getCause().getMessage());
        } finally {
            runtime.close();
        }
    }

    //----------------------------------------------------------------------------

    @Test
    public void testPromiseErrorInFutureAsync() throws Exception {
        JSRuntime runtime = newRuntime();
        JSContext context = runtime.newContext();
        try {
            context.put("answer", new Supplier<Object>() {
                public Object get() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(500); } catch (Exception e) {}
                            runtime.getLogger().log(JSLogger.DEBUG, "Completing future in background thread");
                            future.complete("done");
                        }
                    }).start();
                    return future;
                }
            });
            CompletableFuture<?> result = context.eval("await new Promise((resolve) => { answer().then(x => { throw new Error(\"failed\"); } ) })");
            result.get(1000, TimeUnit.MILLISECONDS);
            fail("Should have failed");
        } catch (ExecutionException e) {
            assertEquals("failed", e.getCause() == null ? null : e.getCause().getMessage());
        } finally {
            runtime.close();
        }
    }


    //----------------------------------------------------------------------------

    @Test
    public void testComputableValue() throws Exception {
        JSRuntime runtime = newRuntime();
        JSContext context = runtime.newContext();
        try {
            // Create a single computed-value property assigned to two 
            // keys. Set one value sets the value for the other.
            // Default value is empty string. getter returns NNN(value) where NNN is upper-case property name.
            JSComputedValue value = new JSComputedValue() {
                private Object v = null;
                @Override public Object get(JSObject owner, String key) {
                    return key.toString().toUpperCase() + "(" + (v == null ? "" : v) + ")";
                }
                @Override public void set(JSObject owner, String key, Object val) {
                    v = val;
                }
            };
            JSObject o = context.newObject();
            o.put("foo", value);
            o.put("bar", value);
            context.put("magic", o);
            Object oo = context.evalNow("magic.foo");
            assertEquals("FOO()", context.evalNow("magic.foo"));
            assertEquals("BAR()", context.evalNow("magic.bar"));
            assertEquals("BAR(1)", context.evalNow("magic.bar = 1; magic.bar"));
            assertEquals("FOO(1)", context.evalNow("magic.foo"));  // Same value shared over two properties
            assertEquals(2, context.evalNow("Object.keys(magic).length"));  // Properties are enumerable
        } finally {
            runtime.close();
        }
    }

    //----------------------------------------------------------------------------

    @JSExport
    private static class ExportTest {
        @JSExport public String field1 = "rw-1";                                 // field1 is a read/write field
        @JSExport public final String field2 = "ro-2";                           // field2 is a read-only field
        @JSExport(field="field3")  public String myfield3 = "rw-3";              // field3 is a read/write field with a different name
        @JSExport(hidden=true) public String field4 = "ro-h-4";                  // field4 is a hidden read/write field;
        private String myfield5 = "rw-5";
        @JSExport(field="field5") public String getField4() {                    // field5 is a read/write field using getter/setter
            return myfield5;
        }
        @JSExport(field="field5") public void setField4(String value) {
            myfield5 = value;
        }
        @JSExport(field="field6") public String getField6() {                   // field6 is a read-only field using getter
            return "ro-6";
        }
        @JSExport(field="field7",hidden=true) public String getField7() {       // field7 is a hidden read-only field using a getter
            return "ro-h-7";
        }
        String myfield8 = "rw-d-8";
        @JSExport(field="field8",deleteable=true) public String getField8() {   // field8 is a deleteable read-only field using a getter
            return myfield8;
        }
        @JSExport(field="field8",deleteable=true) public void setField8(String value) {   // field8 is a deleteable read-write field using a getter/setter
            myfield8 = value;
        }
        // Methods
        @JSExport int add(int a, int b) {                                       // call with add(1, 2)
            return a + b;
        }
        @JSExport float addAll(float... v) {                                    // call with addAll(1, 2.0, 3)
            float t = 0;
            for (int i=0;i<v.length;i++) t += v[i];
            return t;
        }
        @JSExport String cat(Object... v) {                                     // call with cat(1, true, "three", [4,"five"],{"six":7},null);
            return Arrays.toString(v);
        }
        @JSExport String catArray(Object[] v) {                                 // call with catArray([1, true, "three", [4,"five"],{"six":7},null]);
            return Arrays.toString(v);
        }
        @JSExport String catList(List<Object> v) {                              // as above
            return v.toString();
        }
    }

    @Test
    public void testExport() throws Exception {
        JSRuntime runtime = newRuntime();
        JSContext ctx = runtime.newContext();
        try {
            ExportTest test = new ExportTest();
            ctx.put("test", test);
            // Test fields
            assertEquals("rw-1", ctx.evalNow("test.field1"));
            assertEquals("ro-2", ctx.evalNow("test.field2"));
            assertEquals("rw-3", ctx.evalNow("test.field3"));
            assertEquals("ro-h-4", ctx.evalNow("test.field4"));
            assertEquals("rw-5", ctx.evalNow("test.field5"));
            assertEquals("ro-6", ctx.evalNow("test.field6"));
            assertEquals("ro-h-7", ctx.evalNow("test.field7"));
            assertEquals("rw-d-8", ctx.evalNow("test.field8"));
            assertEquals("add,addAll,cat,catArray,catList,field1,field2,field3,field5,field6,field8", ctx.evalNow("Object.keys(test).sort().toString()"));    // hidden fields are hidden
            assertEquals("mod1", ctx.evalNow("test.field1 = \"mod1\"; test.field1"));      // rw fields can be updated
            assertEquals("1", ctx.evalNow("test.field1 = 1; test.field1"));                // rw fields can be updated with type conversion
            assertEquals("[1, 2]", ctx.evalNow("test.field1 = [1,2]; test.field1"));       // rw fields can be updated with type conversion
            assertEquals(null, ctx.evalNow("test.field1 = null; test.field1"));            // rw fields can be updated with null value
            assertEquals("mod1", ctx.evalNow("test.field3 = \"mod1\"; test.field3"));      // rw fields can be updated
            assertEquals("1", ctx.evalNow("test.field3 = 1; test.field3"));                // as above but for renamed field
            assertEquals("[1, 2]", ctx.evalNow("test.field3 = [1,2]; test.field3"));       // 
            assertEquals(null, ctx.evalNow("test.field3 = null; test.field3"));            //
            assertEquals("mod1", ctx.evalNow("test.field5 = \"mod1\"; test.field5"));      //
            assertEquals("1", ctx.evalNow("test.field5 = 1; test.field5"));                // as above but using getter/setter
            assertEquals("[1, 2]", ctx.evalNow("test.field5 = [1,2]; test.field5"));       //
            assertEquals(null, ctx.evalNow("test.field5 = null; test.field5"));            //
            try {
                ctx.evalNow("test.field2 = 1");                                            // ro fields can not be updated
                fail("Value should be read-only");
            } catch (Exception e) {
                assertEquals("no setter for property", e.getMessage());
            }
            try {
                ctx.evalNow("test.field6 = \"foo\"");                                      // ro pseudo-fields with method can not be updated
                fail("Value should be read-only");
            } catch (Exception e) {
                assertEquals("no setter for property", e.getMessage());
            }
            ctx.evalNow("delete test.field8");                                             // a deletable property can be deleted,
            assertNull(ctx.evalNow("test.field8"));                                        // and when it is it has no value in JavaScript.
            ctx.evalNow("test.field8 = 1");                                                // recreated the value is allowed, but the JS
            assertEquals(1, ctx.evalNow("test.field8"));                                   // and Java values are no longer linked
            assertEquals("rw-d-8", test.getField8());

            // Test method calls
            assertEquals(3, ctx.evalNow("test.add(1,2)"));                                 // simple calls with various type coercions
            assertEquals(3, ctx.evalNow("test.add(true,2)"));
            assertEquals(3, ctx.evalNow("test.add(true,2.0)"));
            assertEquals(3, ctx.evalNow("test.add(true,\"2\")"));
            assertEquals(15, ctx.evalNow("test.addAll(1, 2, 3, 4, 5)"));                   // varargs
            assertEquals("[1, foo, false, [1, 2], {foo=1}, null]", ctx.evalNow("test.cat(1,\"foo\",false,[1,2],{\"foo\":1},null)"));       // varargs
            assertEquals("[1, foo, false, [1, 2], {foo=1}, null]", ctx.evalNow("test.catArray([1,\"foo\",false,[1,2],{\"foo\":1},null])"));
            assertEquals("[1, foo, false, [1, 2], {foo=1}, null]", ctx.evalNow("test.catList([1,\"foo\",false,[1,2],{\"foo\":1},null])"));
        } finally {
            runtime.close();
        }
    }

}
