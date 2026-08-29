#!/usr/bin/env bash
# Host unit tests for joan-volte-lineage.
# Builds the host test binary from native/ sources and runs it.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

CC=${CC:-cc}
CFLAGS="-O2 -Wall -Wextra -Wno-unused-parameter -Isrc"

cd native
SRC="src/util.c src/md5.c src/secagree.c src/sip.c src/config.c"
STUBS="tests/stub_xfrm_ctl.c"
TESTS="tests/test_sip_units.c"

mkdir -p build
$CC $CFLAGS -Isrc -o build/joan-ims-host-test \
    $SRC $STUBS $TESTS 2>/dev/null
./build/joan-ims-host-test

echo "== java sip/crypto (host javac)"
JAVA_SRC="$ROOT/ims-service/src/org/joan/ims"
JAVA_TEST="$ROOT/tests/java"
JAVA_OUT="$ROOT/native/build/java-host"
mkdir -p "$JAVA_OUT"
javac -d "$JAVA_OUT" \
    "$JAVA_SRC/JoanSipCrypto.java" \
    "$JAVA_SRC/JoanSecAgree.java" \
    "$JAVA_SRC/JoanSipBuilder.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanSip.java"
exec java -cp "$JAVA_OUT" org.joan.ims.TestJoanSip
