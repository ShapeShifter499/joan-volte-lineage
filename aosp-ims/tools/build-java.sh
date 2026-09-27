#!/bin/bash
# Compile the patched AOSP 17 ImsStack, ImsMediaFramework and the ImsMedia
# service against LineageOS 22.2's real framework (dex2jar'd by
# setup-workdir.sh), so every framework API they reference is known to
# exist on the ROM. Output: $WORK/out/java/{imsstack,imsmedia}/ classes.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HERE/work}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
S=$WORK/src
OUT=$WORK/out/java
ML=$WORK/sdk/35/module-lib
rm -rf "$OUT" && mkdir -p "$OUT"/{gen,rgen-stack,rgen-media,imsstack,imsmedia,res}

# Framework first (hidden and flagged Android 15 APIs included), then the
# module-lib stubs for mainline modules and java.*, then libraries.
CP=$WORK/fwcls/framework.jar:$WORK/fwcls/telephony-common.jar:$WORK/fwcls/ims-common.jar
CP=$CP:$WORK/fwcls/framework-location.jar:$ML/android.jar
for j in "$ML"/framework-*.jar "$ML"/android.net.ipsec.ike.jar; do CP=$CP:$j; done
CP=$CP:$WORK/deps/androidx-annotation.jar:$WORK/deps/libphonenumber.jar

# AIDL: the radio ims.media HAL (frozen V2), ImsMediaFramework, ImsStack.
HAL=$WORK/hwif/radio/aidl/aidl_api
for f in $(find "$HAL/android.hardware.radio.ims.media/2" -name '*.aidl') \
         "$HAL/android.hardware.radio/4/android/hardware/radio/AccessNetwork.aidl"; do
    "$BT/aidl" --lang=java --structured --stability=vintf \
        -I "$HAL/android.hardware.radio.ims.media/2" -I "$HAL/android.hardware.radio/4" \
        -o "$OUT/gen" "$f"
done
FWAIDL=$SDK/platforms/android-35/framework.aidl
IMF=$S/ImsMedia/framework/src
for f in $(find "$IMF" -name '*.aidl'); do
    "$BT/aidl" --lang=java -p"$FWAIDL" -I"$IMF" -I"$HERE/stubs/aidl" -o "$OUT/gen" "$f"
done
ISS=$S/ImsStack/java/src
for f in $(find "$ISS" -name '*.aidl'); do
    "$BT/aidl" --lang=java -p"$FWAIDL" -I"$ISS" -I"$HERE/stubs/aidl" -o "$OUT/gen" "$f"
done

# R classes.
PUB=$WORK/sdk/35/public/android.jar
"$BT/aapt2" compile --dir "$S/ImsStack/java/res" -o "$OUT/res/stack.zip"
"$BT/aapt2" link --manifest "$S/ImsStack/java/AndroidManifest-lib.xml" -I "$PUB" \
    --java "$OUT/rgen-stack" -o "$OUT/res/stack.apk" "$OUT/res/stack.zip" \
    --auto-add-overlay --non-final-ids
"$BT/aapt2" compile --dir "$S/ImsMedia/service/res" -o "$OUT/res/media.zip"
"$BT/aapt2" link --manifest "$S/ImsMedia/service/AndroidManifest.xml" -I "$PUB" \
    --java "$OUT/rgen-media" -o "$OUT/res/media.apk" "$OUT/res/media.zip" \
    --auto-add-overlay --non-final-ids

jc() { # jc <outdir> <classpath> <sources...>
    local d=$1 cp=$2; shift 2
    javac -J-Xmx4g -encoding UTF-8 -nowarn -proc:none -Xmaxerrs 200 \
        -source 17 -target 17 -d "$d" -classpath "$cp" "$@"
}
find "$ISS" "$IMF" "$OUT/gen" "$OUT/rgen-stack" "$HERE/stubs/java" -name '*.java' > "$OUT/stack.srcs"
jc "$OUT/imsstack" "$CP" @"$OUT/stack.srcs"
find "$S/ImsMedia/service/src" "$OUT/rgen-media" "$HERE/stubs/java" -name '*.java' > "$OUT/media.srcs"
jc "$OUT/imsmedia" "$OUT/imsstack:$CP" @"$OUT/media.srcs"
echo "classes: $(find "$OUT/imsstack" -name '*.class' | wc -l) ImsStack+framework," \
     "$(find "$OUT/imsmedia" -name '*.class' | wc -l) ImsMedia service"
