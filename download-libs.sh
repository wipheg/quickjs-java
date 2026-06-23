#!/bin/sh

# Download the Jars we depend on

. ./env.sh

mkdir -p libs
rm -f libs/*.jar
CURL=$(which curl)
CURL_ARGS=-s
LIBS=libs
HOST=https://repo1.maven.org/maven2

download()
{
    ${CURL} ${CURL_ARGS} ${HOST}/${PATH}/${NAME}/${VERSION}/${NAME}-${VERSION}.jar > libs/${NAME}-${VERSION}.jar
}

echo Downloading Jars
PATH=com/dylibso/chicory NAME=runtime VERSION=$CHICORY_VERSION download
PATH=com/dylibso/chicory NAME=build-time-compiler VERSION=$CHICORY_VERSION download
PATH=com/dylibso/chicory NAME=compiler VERSION=$CHICORY_VERSION download
PATH=com/dylibso/chicory NAME=log VERSION=$CHICORY_VERSION download
PATH=com/dylibso/chicory NAME=wasi VERSION=$CHICORY_VERSION download
PATH=com/dylibso/chicory NAME=wasm VERSION=$CHICORY_VERSION download
PATH=org/msgpack NAME=msgpack-core VERSION=$MSGPACK_VERSION download
PATH=com/github/javaparser NAME=javaparser-core VERSION=$JAVAPARSER_VERSION download
PATH=org/ow2/asm NAME=asm VERSION=$OBJECTWEB_VERSION download
PATH=org/ow2/asm NAME=asm-commons VERSION=$OBJECTWEB_VERSION download
PATH=org/junit/jupiter NAME=junit-jupiter-api VERSION=$JUNIT_VERSION download
PATH=org/junit/platform NAME=junit-platform-commons VERSION=$JUNIT_VERSION download
PATH=org/opentest4j NAME=opentest4j VERSION=$OPENTEST4J_VERSION download
PATH=org/apiguardian NAME=apiguardian-api VERSION=$APIGUARDIAN_VERSION download

# Rebuild the "chicory-libary-generator.jar" from source - a trivial shim.
echo Compiling shim for Chicory Library Generator
javac --class-path ${LIBS}/build-time-compiler-${CHICORY_VERSION}.jar:${LIBS}/compiler-${CHICORY_VERSION}.jar \
      --source-path src/builder \
      -d ${LIBS} \
      src/builder/ChicoryLibraryGenerator.java
jar --create --file ${LIBS}/chicory-library-generator.jar --main-class ChicoryLibraryGenerator -C ${LIBS} ChicoryLibraryGenerator.class
rm -f ${LIBS}/ChicoryLibraryGenerator.class
