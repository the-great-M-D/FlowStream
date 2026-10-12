#!/bin/bash
# libdex — compile and run the pure-JVM test suite (no Gradle, mirrors the repo's manual pipeline)
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME=/opt/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH
KOTLINC=/opt/kotlinc/bin/kotlinc

OUT=build/libdex
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== compiling libdex main =="
"$KOTLINC" \
  -jvm-target 17 \
  -d "$OUT/main.jar" \
  libdex/src/main/kotlin/com/downloadx/libdex/*.kt

echo "== compiling libdex tests =="
"$KOTLINC" \
  -jvm-target 17 \
  -cp "$OUT/main.jar:/opt/junit-console.jar" \
  -d "$OUT/test.jar" \
  libdex/src/test/kotlin/com/downloadx/libdex/*.kt

echo "== running tests =="
java -jar /opt/junit-console.jar \
  --class-path "$OUT/main.jar:$OUT/test.jar:/opt/kotlinc/lib/kotlin-stdlib.jar:/opt/junit-console.jar" \
  --select-class com.downloadx.libdex.LibDexTest \
  --fail-if-no-tests \
  --details=tree
