# quickjs for Java

**QuickJS Java** is a repackaging of the [quickjs-wasm-java](https://github.com/StefanRichterHuber/quickjs-wasm-java) package
by Stefan Richter-Huber, so I can take it in a slightly different direction.

## Why?
The use case is running small scripts from untrusted sources that are completely sandboxed. Graal isn't an option for two main
reasons:
1. it's designed to integrated tightly with Java code - resource limits and isolation have been added on later, with Isolated Contexts.
2. no control over event loop: the JavaScript will pause until the script completes, effectively hanging the thread.
   With 1000 tasks that's 1000 hung threads, and Graal's isolated contexts don't (currently; 2026) work with virtual threads.

This project takes a different approach. The [QuickJS engine](https://bellard.org/quickjs/) (in C) is linked with [rquickjs](https://github.com/DelSkayn/rquickjs) (in Rust)
and compiled to Web-Assembly, which is run in the JVM using [Chicory](https://chicory.dev/), a pure-Java WebAssembly runtime without native dependencies.
Chicory converts the Web-Assembly directly into Java class files which can be called from Java.

There are a few projects taking this approach
* [quickjs-wasm-java](https://github.com/StefanRichterHuber/quickjs-wasm-java), which is the basis for this API. Almost all of the Rust source in this repository was taken directly from that project.
* [quickjs4j](https://github.com/roastedroot/quickjs4j), which uses [Javy](https://github.com/bytecodealliance/javy)

## Building
* Requirements: Java 21+ and Rust
* There is `pom.xml` for Maven, which is lifted entirely from Stefan Richter-Huber's `quickjs-wasm-java`
* Alternatively if you find Maven slow, verbose, opaque and you want to manage your dependency supply-chain instead of outsourcing the process to the internet? There are some simple shell scripts to build the components:
  * `download-libs.sh` to download the Jars
  * `build-wasm.sh` to compile the rust to WebAssembly, and compile the WebAssembly to Java classes with Chicory
  * `build-jars.sh` to compile the Java code and bundle it into `target/quickjs-${VERSION}.jar`
  * `test.sh` to compille the Java tests, bundle them into `target/quickjs-test-${VERSION}.jar` and run them.

## Examples
### Getting Started

Simple things are simple. Use a _runtime_ to create one or more _contexts_.
Simple objects (numbers, strings, booleans) map to Java types. Maps become
`com.github.quickjs.JSObject` (which extends Map)`, Arrays become
`com.github.quickjs.JSArray` (which extends List)`.
```java
import com.bfo.quickjs.*;

// Simple examples
JSRuntime js = new JSRuntime();
JSContext ctx = js.newContext();
Object o = ctx.eval("var x = 1 + 2; x");    // o = 3;
JSObject m = (JSObject)ctx.eval("var x = {\"foo\":1 };  // implements Map
m.put("bar", 3);
o = ctx.eval("x.bar");    // o = 3;
o = ctx.eval("throw new Error('fail')");  // o is a JSException
```

### Dynamic Properties
Dynamic properties can be created with the `JSComputedValue` interface.
* Implement `Object get(JSObject owner, String key)` to implement the required getter
* Override `void set(JSObject owner, String key, Object value)` to implement the optional setter.
* Optionally, override `boolean isHidden()` and `boolean isDeletable()` to determine if the property is listed in the object keyset, or if it can be deleted

`key` is the property name and `owner` is the `JSObject` the property is set on (***note*** don't rely on this value yet, see https://github.com/faceless2/quickjs-java/issues/2)
```java
JSRuntime runtime = new JSRuntime();
JSContext ctx = runtime.newContext();
final JSObject map = ctx.newObject();
map.put("name", new JSComputedValue() {
  public Object get(JSObject owner, String key) {
    return map.get("first") + " " + map.get("last");
  }
  public void set(JSObject owner, String key, Object value) {
    String name = (String)value;
    int ix = name.indexOf(" ");
    if (ix > 0) {
        map.put("first", name.substring(0, ix));
        map.put("last", name.substring(ix + 1));
    }
  }
});
ctx.put("person", map);
Object o = ctx.eval("person.first = 'John'; person.last = 'Smith'; person.name");   // = "John Smith"
o = ctx.eval("person.name = 'Jim Jones'; person.first");                            //= "Jim"
```
### Annotations for simpler exporting to JavaScript
The `@JSExport` annotation can be set on a class, which marks it as containing
fields and methods (also annotated) to be exported via a proxy `JSObject`.
* Fields annotated with `@JSExport` must be public and may be final.
* Methods annotated with `@JSExport(field="name")` become getters or setters for a property of that name, depending on the method signature
* Methods annotated with a simple `@JSExport` become JS functions. Conversion for simple types is automatic, and _varargs_ can be used for variable length methods.

```java
@JSExport
public static class Person
{
  @JSExport public String first;
  @JSExport public String last;
  @JSExport(hidden=true) public final int secret; // won't be enumerated, can't be changed.


  @JSExport(field="name")
  public String getName() {   // a getter returns non-void and takes no arguments
    return first + " " + last;
  }

  @JSExport(field="name")
  public void setName(String name) {  // a setter returns void and takes one argument
    int ix = name.indexOf(" ");
    if (ix > 0) {
      first = name.substring(0, ix);
      last = name.substring(ix + 1);
    }
  }

  @JSExport
  public void greet(String intro, int age) {
    System.out.println(intro + ", " + getName() + " you are " + age);
  }

  @JSExport
  public Object min(int... values) {
    Arrays.sort(values);
    return values[0];
  }
}

JSRuntime runtime = new JSRuntime();
JSContext ctx = runtime.newContext();
ctx.put("person", new Person());
ctx.eval("person.first = 'John'; person.last = 'Smith'; person.name");  // = "John Smith"
ctx.eval("person.name = 'Jim Jones'; person.first");                    // = "Jim"
ctx.eval("person.greet(\"Hello\", 12)");                                // prints "Hello, Jim Jones you are 12"
ctx.eval("person.min(5,4,9,1,3)");                                      // = 1
```

### Async

Asynchronous code is supported. Each JS promise is mirrored by a Java [CompleteableFuture](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html), and completing one complete the other.
Promises can be returned directly, or expressions can be wrapped in a promise by calling `evalAsync()`

JavaScript is single threaded so the `poll()` method must be repeatedly called on the primary thread to check for queued events. Java promises may be completed on any thread, and their state will be reflected in JavaScript on the next call to `poll()`

```java
JSRuntime js = new JSRuntime();
JSContext ctx = js.newContext();

// Create a function "log" which prints output. There's no console object!
ctx.put("log", new Consumer<Object>() {
  @Override public void accept(Object o) {
    System.out.println(o); 
  }
});

// Create a function "delay" which eventually resolves on a background thread.
context.put("delay", new Supplier<Object>() {
  public Object get() {
    final CompletableFuture<Object> future = new CompletableFuture<>();
    new Thread() {
      public void run() {
        try { Thread.sleep(500); } catch (Exception e) {}
        future.complete("done");
      }
    }.start();
    return future;
  }
});

CompletableFuture<?> future;
future = (CompletableFuture<?>)ctx.eval("delay().then(x => 'all ' + x).then(x => log(x))");
while (!future.isDone()) {
  Thread.sleep(200);
  ctx.poll();
}
// Loop completes after 500ms with "all done" printed to System.out

future = ctx.evalAsync("await new Promise((resolve) => { delay().then(x => 'all ' + x).then(resolve) })");
while (!future.isDone()) {
  Thread.sleep(200);
  ctx.poll();
}
System.out.println(future.get());   // Same result as above.

// Errors propagate up
future = (CompletableFuture<?>)ctx.eval("delay().then(x => 'all ' + x).then(() => { throw new Error('fail') })");
while (!future.isDone()) {
  Thread.sleep(200);
  ctx.poll();
}
System.out.println(future.get());   // Exception is thrown "Promise Rejected"

future = ctx.evalAsync("await new Promise((resolve) => { delay().then(x => 'all ' + x).then(() => { throw new Error('fail'); }) })");
while (!future.isDone()) {
  ctx.poll();
  Thread.sleep(200);
}
System.out.println(future.get());  // Exception is thrown "fail"
```

### Plumbing

By default the logging is done using `System.Logger("com.bfo.quickjs")`
but you can change this easily by passing a `JSRuntime.Logger` to `setLogger()`.
This is a trivial two-method interface for your logging process of choice:
* `boolean isLoggable(int level)`
* `void log(int level, String message, Object... args)`

where
* `level` is ERROR=1, WARN=2, INFO=3, DEBUG=4, TRACE=5
* "{}" in `message` will be substituted with the value from `args`. A Throwable may be added as the final arg.

There is no console object for JavaScript to read/write to, but the Web-Assembly
layer may still log to stderr. Streams for these can be set with `setStdin(InputStream)`,
`setStdout(OutputStream)` and `setStdErr(OutputStream)`

A runtime limit can be set with `setRuntimeLimit(long ms)` and a memory limit with 
`setMemoryLimit(int bytes)`.

```java
JSRuntime js = new JSRuntime();
js.setStdout(System.out).setStderr(System.err);
js.setRuntimeLimit(1000).setMemoryLimit(65535);
js.setLogger(JSRuntime.Logger.toStream(JSRuntime.Logger.INFO, System.out));
JSContext ctx = js.newContext();
/// etc
```

### Gotchas

```java
JSRuntime js = new JSRuntime();
JSContext ctx = js.newContext();
ctx.eval("let x = {'foo: 1}");
JSObject o1 = (JSObject)ctx.get("x");
assert o1 != null;  // No! X is set in the lexical context, but not on this.
ctx.eval("globalThis.x = {'foo: 1}");   // But either of these will work
ctx.eval("var x = {'foo: 1}");

JSObject o2 = (JSObject)ctx.eval("var x = {'foo: 1}; x");
JSObject o3 = (JSObject)ctx.eval("x");
assert o1 == o2;  // No! Different object each time. See https://github.com/faceless2/quickjs-java/issues/2

ctx.put("myobject", o3);
asssert ctx.get("myobject") == o3;  // Still no! Same problem.
```
Fixes here require changes at the Rust layer which are beyond my abililty - assistance welcome.

My advise is that if an object is created in Java, **manage it in Java**: keep a reference to it and use that
reference to interact with it. Exporting it to JavaScript will export a proxy for the same object, and
reimporting back into Java will give you a proxy for the proxy.


### Credits
Very heavily based on `quickjs-wasm-java`, as mentioned. That project makes use of FunctionalInterfaces, which I found made simple things easier and harder things (eg methods with variable parameter lists) impossible. This difference and the original's dependency on `log4j` was the original reason for the rewrite and repackaging, however the Rust layer is largely unchanged.



