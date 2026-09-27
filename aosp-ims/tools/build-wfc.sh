#!/bin/bash
# Build the two AOSP 17 apps VoWiFi needs besides ImsStack, backported to
# LineageOS 22.2 and packaged for installs without the ROM's platform key:
#
#   Iwlan.apk                    com.google.android.iwlan   the ePDG tunnel (IKEv2/IPsec)
#   QualifiedNetworksService.apk com.android.telephony.qns  LTE <-> Wi-Fi transport choice
#
# Upstream Iwlan runs as android.uid.system; here it is a privileged app
# like the others, and the one permission that leaves it short,
# MANAGE_IPSEC_TUNNELS (signature|appop), is granted as an app-op over adb
# (zip/grant-permissions.sh). Both declare usesNonSdkApi.
#
# Run after setup-workdir.sh and build-apk.sh (for the signing key).
# Output: $WORK/out/apk/{Iwlan,QualifiedNetworksService}.apk
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HERE/work}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
S=$WORK/src
ML=$WORK/sdk/35/module-lib
PUB=$WORK/sdk/35/public/android.jar
FWAIDL=$SDK/platforms/android-35/framework.aidl
OUT=$WORK/out/wfc
APK=$WORK/out/apk
KS=$WORK/keys/imsstack.jks
[ -f "$KS" ] || { echo "run build-apk.sh first (signing key)"; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT" "$APK"

CP=$WORK/fwcls/framework.jar:$WORK/fwcls/telephony-common.jar:$WORK/fwcls/ims-common.jar
CP=$CP:$WORK/fwcls/framework-location.jar:$WORK/fwcls/framework-connectivity-b.jar:$ML/android.jar
for j in "$ML"/framework-*.jar "$ML"/android.net.ipsec.ike.jar; do CP=$CP:$j; done
CP=$CP:$WORK/deps/androidx-annotation.jar:$WORK/deps/auto-value-annotations.jar:$WORK/deps/support-annotations.jar
LIBS=()
for j in "$WORK"/fwcls/*.jar "$ML"/android.jar "$ML"/framework-*.jar "$ML"/android.net.ipsec.ike.jar; do
    LIBS+=(--lib "$j")
done

# build <name> <src dir> <extra java dirs...>
build() {
    local name=$1 src=$2; shift 2
    local o=$OUT/$name
    mkdir -p "$o"/{gen,rgen,classes,dex,res}
    for f in $(find "$src/src" -name 'I*.aidl'); do
        "$BT/aidl" --lang=java -p"$FWAIDL" -I"$src/src" -o "$o/gen" "$f"
    done
    python3 "$HERE/tools/patch-manifest.py" "$src/AndroidManifest.xml" "$o/AndroidManifest.xml"
    local res=() assets=()
    if [ -d "$src/res" ]; then
        "$BT/aapt2" compile --dir "$src/res" -o "$o/res/res.zip"
        res=("$o/res/res.zip")
    fi
    [ -d "$src/assets" ] && assets=(-A "$src/assets")
    "$BT/aapt2" link --manifest "$o/AndroidManifest.xml" -I "$PUB" "${assets[@]}" \
        --java "$o/rgen" -o "$o/base.apk" "${res[@]}"
    find "$src/src" "$o/gen" "$o/rgen" "$@" -name '*.java' > "$o/srcs"
    find "$HERE/stubs/java/android/annotation" "$HERE/stubs/java/com/android/internal" \
        -name '*.java' >> "$o/srcs"
    javac -J-Xmx3g -encoding UTF-8 -nowarn -source 21 -target 21 \
        -processorpath "$WORK/deps/auto-value.jar" -d "$o/classes" -classpath "$CP" @"$o/srcs"
    rm -rf "$o/classes/android/annotation" "$o/classes/com/android/internal"
    "$BT/d8" --release --min-api 31 "${LIBS[@]}" --output "$o/dex" \
        $(find "$o/classes" -name '*.class')
    cp "$o/base.apk" "$o/unaligned.apk"
    (cd "$o/dex" && zip -q -X "$o/unaligned.apk" classes*.dex)
    "$BT/zipalign" -f -p 4 "$o/unaligned.apk" "$o/aligned.apk"
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:imsstack --ks-key-alias imsstack \
        --out "$APK/$name.apk" "$o/aligned.apk"
    ls -la "$APK/$name.apk"
}

python3 "$HERE/tools/aconfig-stub.py" "$S/Iwlan/flags/main.aconfig" "$OUT/iwlan-flags"
build Iwlan "$S/Iwlan" "$OUT/iwlan-flags" "$HERE/stubs/wfc/com/google" \
    "$S/modules-utils/java"
build QualifiedNetworksService "$S/Qns" "$HERE/stubs/wfc/com/android" \
    "$S/modules-telephony/libs/TelephonyStatsLib/src"

for app in Iwlan:privapp-permissions-com.google.android.iwlan.xml \
           QualifiedNetworksService:privapp-permissions-com.android.telephony.qns.xml; do
    python3 "$HERE/tools/check-privapp.py" "$BT/aapt2" "$APK/${app%%:*}.apk" \
        "$WORK/rom/framework-res.apk" "$HERE/permissions/${app##*:}"
done
