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

The advantage is that rather than one-thread-per task, a single thread can be used to interleave operations on multiple runtimes,
much like `select(2)`. **QuickJS Java** has been tested with 1000 runtimes, all waiting on promises, all from a single Java thread.

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
JSObject m = (JSObject)ctx.evalNow("var x = {\"foo\":1 };  // implements Map
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
Object o = ctx.evalNow("person.first = 'John'; person.last = 'Smith'; person.name");   // = "John Smith"
o = ctx.evalNow("person.name = 'Jim Jones'; person.first");                            //= "Jim"
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
ctx.evalNow("person.first = 'John'; person.last = 'Smith'; person.name");  // = "John Smith"
ctx.evalNow("person.name = 'Jim Jones'; person.first");                    // = "Jim"
ctx.evalNow("person.greet(\"Hello\", 12)");                                // prints "Hello, Jim Jones you are 12"
ctx.evalNow("person.min(5,4,9,1,3)");                                      // = 1
```

### Async

The examples so far have called `Object result = ctx.evalNow(script)`, which executes the code and waits for a response.
In general it is a better idea to call `CompletableFuture<Object> result = ctx.eval(script)`. This immediately returns a Future
that will evaluate to the object when it is resolved. Details on the process is described in the next section,
but using this is very simple.

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

ctx.eval("delay().then(x => 'all ' + x).then(x => log(x))").join();
// Loop completes after 500ms with "all done" printed to System.out

Object o = ctx.eval("await new Promise((resolve) => { delay().then(x => 'all ' + x).then(resolve) })").get();
System.out.println(o);   // Same result as above.

// Errors propagate up
ctx.eval("delay().then(x => 'all ' + x).then(() => { throw new Error('fail') })").join();  // throws "Promise Rejected"
o = ctx.eval("await new Promise((resolve) => { delay().then(x => 'all ' + x).then(() => { throw new Error('fail'); }) })").get();  // throws "fail"
```

### Threading details
JavaScript is famously single-threaded, so for the above examples to work it's necessary to communicate with the JavaScript
engine in a background thread. A JSRuntime marshalls all its communication with the JavaScript engine onto the same thread,
which is managed internally by the library - **it is safe to share a context across multiple threads**. This isn't full
concurrency - for example, if you iterate over a map in one thread and delete a value from it in another, a
`ConcurrentModificationException` will be thrown. So don't do that. But calling `ctx.eval()` from multiple threads is fine.

The exact details of the threading model are determined by a `TaskManager`, which can be set on the `JSRuntime`. Currently there
are three options.

```java
JSRuntime runtime = new JSRuntime();
runtime.setTaskManager(TaskManager.useSharedThread());  // one thread for all runtimes. The default
runtime.setTaskManager(TaskManager.useOwnThread());     // one thread per runtime
runtime.setTaskManager(TaskManager.useCurrentThread()); // no threading
```
* The _shared thread_ model uses a single background thread, interleaving tasks for multiple runtimes in sequence.
  This is the default and for short-lived tasks (the JavaScript way) it's very effective, particularly
  if those tasks are going to be largely waiting for promises to complete, eg for network or file access
  (the "delay" example above has been tested with 1000 instances all running at once).
  The thread is started on demand and closed shortly after the last runtime is closed.

* The _own thread_ model is one thread per JSRuntime. The thread is started on demand and closed when the JSRuntime is closed

* The _current thread_ model doesn't use threads. Everything is run on the current thread - it's up to you to
  ensure this is done in a thread safe manner. For futures to complete it's necessary to regularly call poll:
  ```java
  JSRuntime runtime = new JSRuntime().withTaskManager(TaskManager.useCurrentThread());
  JSContext ctx = runtime.newContext();
  CompleteableFuture<Object> future = ctx.eval("delay().then(x => 'all ' + x).then(() => { throw new Error('fail') })");
  while (!future.isDone()) {
     ctx.poll();
     Thread.sleep(20);
  }
  Object o = future.get();
  ```

### Runtime vs Context
A _Runtime_ is fully isolated, and multiple _Contexts_ within the same Runtime are independent only because they don't
have a pointer from one to the other. In theory it would be possible for one context to reference another (not implemented)
or to create one Runtime and run many Contexts indepdently,
but in practice there are problems with this (https://github.com/faceless2/quickjs-java/issues/5)
For multiple tasks, for now the recommendation is create multiple Runtimes.
```java

// This works
JSContext[] ctx = new JSContext[100];
for (int i=0;i<ctx.length;i++) {
    JSRuntime runtime = new JSRuntime();
    ctx[i] = runtime.newContext();
    ctx[i].put(...);
    CompleteableFuture<Object> f = ctx[i].eval(...);
}

// For now, this doesn't
JSRuntime runtime = new JSRuntime();
JSContext[] ctx = new JSContext[100];
for (int i=0;i<ctx.length;i++) {
    ctx[i] = runtime.newContext();
    ctx[i].put(...);
    CompleteableFuture<Object> f = ctx[i].eval(...);
}
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
ctx.evalNow("let x = {'foo: 1}");
JSObject o1 = (JSObject)ctx.get("x");
assert o1 != null;  // No! X is set in the lexical context, but not on this.
ctx.evalNow("globalThis.x = {'foo: 1}");   // But either of these will work
ctx.evalNow("var x = {'foo: 1}");

JSObject o2 = (JSObject)ctx.evalNow("var x = {'foo: 1}; x");
JSObject o3 = (JSObject)ctx.evalNow("x");
assert o1 == o2;  // No! Different object each time. See https://github.com/faceless2/quickjs-java/issues/2

ctx.put("myobject", o3);
asssert ctx.get("myobject") == o3;  // Still no! Same problem.

// JavaScript is complicated! This seems to be by design. Be careful.
ctx.evalNow("var x = 1; globalThis.y = 2; let z = 3");
assert ctx.containsKey("x");
assert ctx.containsKey("y");
assert !ctx.containsKey("z");
ctx.remove("x");
ctx.remove("y");
assert !ctx.containsKey("x");
assert !ctx.containsKey("y");
assert "number".equals(ctx.evalNow("typeof x"));
assert "undefined".equals(ctx.evalNow("typeof y"));
assert "number".equals(ctx.evalNow("typeof z"));
```
Fixes here require changes at the Rust layer which are beyond my abililty - assistance welcome.

Best practice: if an object is created in Java, **manage it in Java**: keep a reference to it and use that
reference to interact with it. Exporting it to JavaScript will export a proxy for the same object, and
reimporting back into Java will give you a proxy for the proxy.


### Credits
Very heavily based on `quickjs-wasm-java`, as mentioned. Main difference
* no log4j dependency
* no use of FunctionalInterfaces - I found they made working with variable-parameter functions much harder.
* no longer single-threaded
* API changes - although superficially `JSContext` is a `QuickJSContext`, be aware that `JSContext.eval` and `JSContext.evalNow`
correspond to `QuickJSContext.evalAsync` and `QuickJSContext.eval`.

However the Rust layer is largely unchanged, so a big thank you to Stefan for releasing it.



