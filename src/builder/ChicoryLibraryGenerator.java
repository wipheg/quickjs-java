import com.dylibso.chicory.build.time.compiler.Config;
import com.dylibso.chicory.build.time.compiler.Generator;
import com.dylibso.chicory.compiler.InterpreterFallback;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Generates an invokable library from the compiled Wasm.
 * A de-mavenized version of
 * https://github.com/dylibso/chicory/blob/main/compiler-maven-plugin/src/main/java/com/dylibso/chicory/build/time/maven/ChicoryCompilerGenMojo.java
 */
public class ChicoryLibraryGenerator {

    /**
     * the wasm module to be used
     */
    private File wasmFile;

    /**
     * the base name to be used for the generated classes
     */
    private String name;

    /**
     * the target folder to generate classes
     */
    private File targetClassFolder;

    /**
     * the target source folder to generate the Machine implementation
     */
    private File targetSourceFolder;

    /**
     * the target wasm folder to generate the stripped meta wasm module
     */
    private File targetWasmFolder;

    /**
     * the action to take if the compiler needs to use the interpreter because a function is too big
     */
    InterpreterFallback interpreterFallback;

    /**
     * The indexes of functions that should be interpreted, separated by commas
     */
    Set<Integer> interpretedFunctions;

    /**
     * Fully qualified name of the user's class that will use the compiled module.
     * When set, the plugin generates _ModuleExports and _ModuleImports wrapper classes,
     * eliminating the need for @WasmModuleInterface annotation and the annotation processor.
     */
    String moduleInterface;

    public static void main(String[] args) throws Exception {
        new ChicoryLibraryGenerator().args(args).execute();
    }

    private ChicoryLibraryGenerator args(String[] args) throws Exception {
        for (int i=0;i<args.length;i++) {
            String s = args[i];
            if (s.equals("--wasm-file") && i + 1 < args.length && wasmFile == null) {
                wasmFile = new File(args[++i]);
            } else if (s.equals("--name") && i + 1 < args.length && name == null) {
                name = args[++i];
            } else if (s.equals("--target-class-folder") && i + 1 < args.length && targetClassFolder == null) {
                targetClassFolder = new File(args[++i]);
            } else if (s.equals("--target-source-folder") && i + 1 < args.length && targetSourceFolder == null) {
                targetSourceFolder = new File(args[++i]);
            } else if (s.equals("--target-wasm-folder") && i + 1 < args.length && targetWasmFolder == null) {
                targetWasmFolder = new File(args[++i]);
            } else if (s.equals("--interpreter-fallback") && i + 1 < args.length && interpreterFallback == null) {
                try {
                    interpreterFallback = InterpreterFallback.valueOf(args[++i].toUpperCase());
                } catch (Exception e) {
                    help("Invalid value for --interpreter-fallback: not one of " + Arrays.toString(InterpreterFallback.values()).toLowerCase());
                }

            } else if (s.equals("--interpreted-functions") && i + 1 < args.length && interpretedFunctions == null) {
                interpretedFunctions = new HashSet<Integer>();
                for (String t : args[++i].split(",")) {
                    try {
                        interpretedFunctions.add(Integer.parseInt(t));
                    } catch (Exception e) {
                        help("Invalid value for --interpreted-functions: not a comma-separated list of integers");
                    }
                }
            } else if (s.equals("--module-interface") && i + 1 < args.length && moduleInterface == null) {
                moduleInterface = args[++i];
            } else {
                help("Invalid argument \"" + s + "\"");
            }
        }
        if (interpreterFallback == null) {
            interpreterFallback = InterpreterFallback.FAIL;
        }
        if (name == null || wasmFile == null || targetClassFolder == null || targetSourceFolder == null || targetWasmFolder == null) {
            help("Missing required parameter");
        }
        return this;
    }

    public void help(String error) {
        if (error != null) {
            System.err.println("ERROR: " + error);
        }
        System.err.println("""
Generates an invokable library from the compiled WASM

Usage: java com.github.quickjs.ChicoryLibraryGenerator [...args]

  --name <name>                 the base name to be used for the generated classes (required)
  --wasm-file <path>            the WASM module to be used (required)
  --target-class-folder <path>  the target folder to generate classes (required)
  --target-source-folder <path> the target source folder to generate the Machine
                                implementation (required)
  --target-wasm-folder <path>   the target wasm folder to generate the stripped meta WASM
                                module (required)
  --interpreter-fallback <val>  the action to take if the compiler needs to use the
                                interpreter because a function is too big (one of silent,
                                warn or fail: default is fail)
  --interpreted-functions <set> the indices of functions that should be interpreted: a list
                                of integers separated by commas
""");
/*
  --module-interface <name>     fully qualified name of the user's class that will use the
                                compiled module. When set, the plugin generates _ModuleExports
                                and _ModuleImports wrapper classes, eliminating the need for
                                @WasmModuleInterface annotation and the annotation processor.
                                */
        System.exit(0);
    }

    public void execute() throws Exception {

        Config.Builder builder = 
                Config.builder()
                        .withWasmFile(wasmFile.toPath())
                        .withName(name)
                        .withTargetClassFolder(targetClassFolder.toPath())
                        .withTargetSourceFolder(targetSourceFolder.toPath())
                        .withTargetWasmFolder(targetWasmFolder.toPath())
                        .withInterpreterFallback(interpreterFallback)
                        .withInterpretedFunctions(interpretedFunctions);
        if (moduleInterface != null) {
            builder = builder.withModuleInterface(moduleInterface);
        }
        var config = builder.build();

        var generator = new Generator(config);

        var finalInterpretedFunctions = generator.generateResources();
        generator.generateMetaWasm(finalInterpretedFunctions);
        generator.generateSources();

        if (moduleInterface != null && !moduleInterface.isEmpty()) {
            generator.generateModuleInterface(moduleInterface);
        }

        if (interpreterFallback == InterpreterFallback.WARN
                && !finalInterpretedFunctions.isEmpty()) {
            var sorted = new TreeSet<>(finalInterpretedFunctions);
            StringBuilder sb = new StringBuilder();
            sb.append("<interpretedFunctions>\n");
            for (Integer funcId : sorted) {
                sb.append("  <function>").append(funcId).append("</function>\n");
            }
            sb.append("</interpretedFunctions>");
            System.out.println("# WARNING: Copy-paste the following to pre-declare interpreted functions"
                                    + " in your pom.xml:\n"
                                    + sb);
        }
    }
}
