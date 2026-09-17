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

echo "== apn overlay merge"
"$ROOT/tests/apn/run-apn-tests.sh"
"$ROOT/tests/audio/run-agc-tests.sh"

echo "== carrier assets"
"$ROOT/tests/carrier/run-carrier-tests.sh"

echo "== java sip/crypto (host javac)"
JAVA_SRC="$ROOT/ims-service/src/org/joan/ims"
JAVA_TEST="$ROOT/tests/java"
JAVA_OUT="$ROOT/native/build/java-host"
mkdir -p "$JAVA_OUT"
javac -d "$JAVA_OUT" \
    "$JAVA_SRC/JoanSipCrypto.java" \
    "$JAVA_SRC/JoanSecAgree.java" \
    "$JAVA_SRC/JoanSipBuilder.java" \
    "$JAVA_SRC/JoanAmr.java" \
    "$JAVA_SRC/JoanRtcp.java" \
    "$JAVA_SRC/JoanDtmf.java" \
    "$JAVA_SRC/JoanSessionTimer.java" \
    "$JAVA_SRC/JoanRegInfo.java" \
    "$JAVA_SRC/JoanJitter.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanSip.java"
java -cp "$JAVA_OUT" org.joan.ims.TestJoanSip

echo "== viettel volte carrier gate"
SDK_JAR=${ANDROID_SDK:-$HOME/Android/Sdk}/platforms/android-36/android.jar
GATE_OUT="$ROOT/native/build/java-gate-host"
mkdir -p "$GATE_OUT"
javac -cp "$SDK_JAR" -d "$GATE_OUT" \
    "$JAVA_SRC/JoanVolteCarrierGate.java" \
    "$JAVA_SRC/JoanImsVoiceConfig.java" \
    "$JAVA_SRC/JoanSessionTimer.java" \
    "$JAVA_SRC/JoanCodecConfig.java" \
    "$JAVA_SRC/JoanSipBuilder.java" \
    "$JAVA_SRC/JoanSipCrypto.java" \
    "$JAVA_SRC/JoanSecAgree.java" \
    "$JAVA_SRC/JoanAmr.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanVolteCarrierGate.java"
exec java -cp "$GATE_OUT:$SDK_JAR" org.joan.ims.TestJoanVolteCarrierGate
