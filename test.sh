#!/bin/sh

# Run the tests. Depends on build-jar.sh

# Run as "test.sh" to run all tests
# Run as "test.sh ModuleTest" or "test.sh ModuleTest.testModuleError ModuleTest.testModuleError2"
#   for a names subset

. ./env.sh

mkdir -p target/test-classes

javac --class-path ${CLASSPATH_TEST}:${JAR_FILE} \
      -d target/test-classes \
      --source-path src/test/java \
      src/test/java/com/bfo/quickjs/*.java

if [ $? -eq 0 ] ; then
    if [ $# -eq 0 ] ; then 
        # Next line lists every java file in src/test/java/com/bfo/quickjs without path or ".java" suffix
        # eg "TESTS=TestHarness JSContextTest"
        TESTS=$(cd src/test/java/com/bfo/quickjs && ls -1 *.java | sed 's/.java$//')
    else
        TESTS=$*
    fi
    jar --create --file ${TEST_JAR_FILE} -C target/test-classes com

    java --class-path ${CLASSPATH_TEST}:${JAR_FILE}:${TEST_JAR_FILE} \
         -Dmsgpack.universal-buffer=true \
         -Djava.util.logging.config.file=logging.properties \
         com.bfo.quickjs.TestHarness ${TESTS}

fi
