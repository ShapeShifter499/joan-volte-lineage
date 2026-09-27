#!/usr/bin/env bash
# Offline registration/transport/lifecycle regression harness.
# Isolated build output (native/build/ua-registration-host): does not share
# or touch the UA harness output, does not edit run-ua-tests.sh, and never
# exercises radio, SIM, AKA, or real network endpoints.
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}/platforms/android-36/android.jar
SRC=ims-service/src/org/joan/ims
OUT=native/build/ua-registration-host
rm -rf "$OUT"
mkdir -p "$OUT"
javac -classpath "$SDK" -d "$OUT" \
  $(find ims-service/stubs ims-service/src -name '*.java') \
  tests/registration/org/joan/ims/*.java
java -cp "$OUT:$SDK" org.joan.ims.TestJoanRegistration
java -cp "$OUT:$SDK" org.joan.ims.TestJoanDiscovery
