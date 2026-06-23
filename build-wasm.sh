#!/bin/sh

# Rebuild the Rust API, then compile it with Chicory into class files

. ./env.sh

mkdir -p target/builder-classes
mkdir -p $(dirname ${WASM_FILE})
mkdir -p ${WASM_TARGET_RESOURCES}
mkdir -p ${WASM_TARGET_SOURCES}

echo Compiling Rust sources
cargo build --manifest-path src/main/rust/quickjslib/wasm_lib/Cargo.toml \
            --profile dev \
            --target wasm32-wasip1 \
            --target-dir target/rust

if [ $? -eq 0 ] ; then 
    echo Compiling WASM code into Java classes with Chicory
    java --class-path $CLASSPATH_BUILD ChicoryLibraryGenerator \
            --name com.bfo.quickjs.WasmLib \
            --wasm-file ${WASM_FILE} \
            --target-class-folder ${WASM_TARGET_RESOURCES} \
            --target-wasm-folder ${WASM_TARGET_RESOURCES} \
            --target-source-folder ${WASM_TARGET_SOURCES} \
            --interpreter-fallback silent
fi 
# wasm-opt -O4 ${WASM_FILE} -o ${WASM_OPTIMIZED_FILE}
