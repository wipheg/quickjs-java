#!/bin/sh

# Rebuild the Jar file. Depends on the content compiled from Rust by build-wasm.sh

. ./env.sh

javac --class-path ${CLASSPATH}:${WASM_TARGET_RESOURCES} \
      -d target/classes \
      --source-path src/main/java \
      --source-path ${WASM_TARGET_SOURCES} \
      -Xlint:unchecked \
      -Xlint:rawtypes \
      src/main/java/com/bfo/quickjs/*.java

jar --create --file ${JAR_FILE} -C target/classes com -C ${WASM_TARGET_RESOURCES} com
