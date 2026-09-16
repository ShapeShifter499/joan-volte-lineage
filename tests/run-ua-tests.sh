#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}/platforms/android-36/android.jar
SRC=ims-service/src/org/joan/ims
OUT=native/build/ua-host
mkdir -p "$OUT"
javac -cp "$SDK" -d "$OUT" \
 "$SRC/JoanSipUa.java" "$SRC/JoanSipBuilder.java" \
 "$SRC/JoanSipCrypto.java" "$SRC/JoanSecAgree.java" \
 "$SRC/JoanAppRegister.java" "$SRC/JoanAka.java" \
 "$SRC/JoanImsDiscovery.java" \
 "$SRC/JoanCarrierProfile.java" "$SRC/JoanXfrmStats.java" tests/ua/org/joan/ims/*.java
java -cp "$OUT:$SDK" org.joan.ims.TestJoanUa
java -cp "$OUT:$SDK" org.joan.ims.TestJoanXfrmStats
java -cp "$OUT:$SDK" org.joan.ims.TestJoanMerge
java -cp "$OUT:$SDK" org.joan.ims.TestJoanFreshPass
# The registration suite has its own runner because it compiles a different
# file set. Call it from here so it cannot silently stop running: it was
# reachable only by hand, and checks added to it passed unnoticed because
# nothing executed them.
"$(dirname "$0")/ua/run-registration-tests.sh"
