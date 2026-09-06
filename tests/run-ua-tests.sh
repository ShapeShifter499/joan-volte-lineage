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
 "$SRC/JoanCarrierProfile.java" "$SRC/JoanXfrmStats.java" tests/ua/org/joan/ims/*.java
java -cp "$OUT:$SDK" org.joan.ims.TestJoanUa
java -cp "$OUT:$SDK" org.joan.ims.TestJoanXfrmStats
java -cp "$OUT:$SDK" org.joan.ims.TestJoanMerge
