#!/bin/sh

# Do everything!

#rm -rf target libs      # this is "mvn clean"
./download-libs.sh
./build-wasm.sh
./build.sh
./test.sh
