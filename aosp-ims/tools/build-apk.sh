#!/bin/bash
# Package the backport as ONE app, com.android.imsstack, for installs that do
# not have the ROM's platform key (the flashable zip and the repacked ROM):
# ImsStack plus the ImsMedia service in its own process
# (tools/merge-manifest.py), both native libraries stored uncompressed, and
# the two overlays that make it the device's ImsService.
#
# Run after build-java.sh (AIDL sources) and build-native.sh (libraries).
# Output: $WORK/out/apk/{ImsStack.apk,ImsStackPhoneOverlay.apk,ImsStackFrameworkOverlay.apk}
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
S=$WORK/src
OUT=$WORK/out/apk
ML=$WORK/sdk/35/module-lib
PUB=$WORK/sdk/35/public/android.jar
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-17.0.0_r1-a15-alpha1}
rm -rf "$OUT" && mkdir -p "$OUT"/{res,assets,rgen,classes,dex}

# 1. Manifest and resources, linked once so R matches for both packages.
python3 "$HERE/tools/merge-manifest.py" "$S/ImsStack/java/AndroidManifest.xml" \
    "$S/ImsMedia/service/AndroidManifest.xml" "$OUT/AndroidManifest.xml" \
    "$VERSION_CODE" "$VERSION_NAME"
cp -r "$S/ImsStack/java/assets/." "$S/ImsMedia/service/assets/." "$OUT/assets/"
# CarrierImsGate's data: joan's SIM -> LG profile maps (ePDG resolution).
mkdir -p "$OUT/assets/joan"
cp "$HERE/../aosp-ims/assets/carrier-id-map.json" \
   "$HERE/../aosp-ims/assets/carrier-plmn-map.json" "$OUT/assets/joan/"
"$BT/aapt2" compile --dir "$S/ImsStack/java/res" -o "$OUT/res/stack.zip"
"$BT/aapt2" compile --dir "$S/ImsMedia/service/res" -o "$OUT/res/media.zip"
"$BT/aapt2" link --manifest "$OUT/AndroidManifest.xml" -I "$PUB" -A "$OUT/assets" \
    --java "$OUT/rgen" --extra-packages com.android.telephony.imsmedia \
    -o "$OUT/base.apk" "$OUT/res/stack.zip" "$OUT/res/media.zip"

# 2. Everything compiled together against the ROM's framework.
CP=$WORK/fwcls/framework.jar:$WORK/fwcls/telephony-common.jar:$WORK/fwcls/ims-common.jar
CP=$CP:$WORK/fwcls/framework-location.jar:$ML/android.jar
for j in "$ML"/framework-*.jar "$ML"/android.net.ipsec.ike.jar; do CP=$CP:$j; done
CP=$CP:$WORK/deps/androidx-annotation.jar:$WORK/deps/libphonenumber.jar
[ -d "$WORK/out/java/gen" ] || { echo "run build-java.sh first (AIDL sources)"; exit 1; }
find "$S/ImsStack/java/src" "$S/ImsMedia/framework/src" "$S/ImsMedia/service/src" \
    "$WORK/out/java/gen" "$OUT/rgen" "$HERE/stubs/java" "$HERE/zip/java" \
    -name '*.java' > "$OUT/srcs"
javac -J-Xmx4g -encoding UTF-8 -nowarn -proc:none -source 21 -target 21 \
    -d "$OUT/classes" -classpath "$CP" @"$OUT/srcs"
# The annotation stubs exist only to compile; the platform owns those names.
rm -rf "$OUT/classes/android/annotation" "$OUT/classes/com/android/internal"

# 3. Dex. The framework is library, not program: it is on the device.
LIBS=()
for j in "$WORK"/fwcls/*.jar "$ML"/android.jar "$ML"/framework-*.jar "$ML"/android.net.ipsec.ike.jar; do
    LIBS+=(--lib "$j")
done
"$BT/d8" --release --min-api 31 "${LIBS[@]}" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class') "$WORK/deps/libphonenumber.jar"

# 4. Assemble: dex, libphonenumber's metadata, native libraries stored so
#    they load straight from the APK (extractNativeLibs=false).
python3 - "$OUT" "$WORK" <<'EOF'
import os, sys, zipfile
out, work = sys.argv[1], sys.argv[2]
src = zipfile.ZipFile(os.path.join(out, 'base.apk'))
with zipfile.ZipFile(os.path.join(out, 'unaligned.apk'), 'w', zipfile.ZIP_DEFLATED) as z:
    for info in src.infolist():
        z.writestr(info, src.read(info))
    for dex in sorted(os.listdir(os.path.join(out, 'dex'))):
        z.write(os.path.join(out, 'dex', dex), dex)
    jar = zipfile.ZipFile(os.path.join(work, 'deps', 'libphonenumber.jar'))
    for info in jar.infolist():
        n = info.filename
        if n.endswith('/') or n.endswith('.class') or n.startswith('META-INF/'):
            continue
        z.writestr(n, jar.read(info))
    for lib in ('libimsstack', 'libimsmedia'):
        path = os.path.join(work, 'out', 'native', lib, lib + '.so')
        z.write(path, f'lib/arm64-v8a/{lib}.so', compress_type=zipfile.ZIP_STORED)
EOF
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

# 5. Overlays. The CarrierConfig overlay's vendor.xml is generated, not
#    committed: the ROM's own content (carrier/joan-common-vendor-base.xml)
#    plus the converted Pixel IMS carrier data and our rules, spliced
#    together by make-carrier-config.py (the same region the device-tree
#    patch splices, so the two cannot drift; checked by
#    tests/check-carrier-config.py).
TP=$WORK/TelephonyProvider
TEXTPB=${TEXTPB:-$TP/assets/latest_carrier_id/carrier_list.textpb}
if [ ! -f "$TEXTPB" ]; then
    git clone -q --filter=blob:none --no-checkout --depth 1 -b "$PLATFORM_BRANCH" \
        https://android.googlesource.com/platform/packages/providers/TelephonyProvider "$TP"
    git -C "$TP" sparse-checkout set --no-cone /assets/latest_carrier_id/carrier_list.textpb
    git -C "$TP" checkout -q
fi
mkdir -p "$HERE/zip/rro-carrierconfig/res/xml"
cp "$HERE/carrier/joan-common-vendor-base.xml" \
   "$HERE/zip/rro-carrierconfig/res/xml/vendor.xml"
python3 "$HERE/tools/make-carrier-config.py" \
    "$HERE/../aosp-ims/assets/carrier-id-map.json" \
    "$HERE/../aosp-ims/assets/carrier-plmn-map.json" \
    "$HERE/zip/java/com/android/imsstack/joan/CarrierImsGate.java" \
    "$TEXTPB" --import "$HERE/carrier/lineage-pixel-ims.xml" \
    --splice "$HERE/zip/rro-carrierconfig/res/xml/vendor.xml"
for rro in phone:ImsStackPhoneOverlay fw:ImsStackFrameworkOverlay \
           carrierconfig:ImsStackCarrierConfigOverlay; do
    dir=$HERE/zip/rro-${rro%%:*}; name=${rro##*:}
    "$BT/aapt2" compile --dir "$dir/res" -o "$OUT/res/$name.zip"
    "$BT/aapt2" link -I "$PUB" --manifest "$dir/AndroidManifest.xml" \
        -o "$OUT/$name-unsigned.apk" "$OUT/res/$name.zip"
done

# 6. Sign. A system app's key may change between builds (PackageManager
#    keeps the data), so the key is per work dir and never committed: a
#    published private key would let anyone ship an "update" to a
#    privileged app.
KS=$WORK/keys/imsstack.jks
if [ ! -f "$KS" ]; then
    mkdir -p "$WORK/keys"
    keytool -genkeypair -keystore "$KS" -storepass imsstack -keypass imsstack \
        -alias imsstack -keyalg RSA -keysize 3072 -validity 10000 \
        -dname "CN=joan-volte-lineage AOSP IMS" >/dev/null 2>&1
fi
sign() { "$BT/apksigner" sign --ks "$KS" --ks-pass pass:imsstack --ks-key-alias imsstack \
    --out "$2" "$1"; }
sign "$OUT/aligned.apk" "$OUT/ImsStack.apk"
sign "$OUT/ImsStackPhoneOverlay-unsigned.apk" "$OUT/ImsStackPhoneOverlay.apk"
sign "$OUT/ImsStackFrameworkOverlay-unsigned.apk" "$OUT/ImsStackFrameworkOverlay.apk"
sign "$OUT/ImsStackCarrierConfigOverlay-unsigned.apk" "$OUT/ImsStackCarrierConfigOverlay.apk"

# 7. The allowlist must cover every privileged permission requested, or
#    PackageManager stops the boot. Checked against the ROM's own
#    framework-res protection levels.
python3 "$HERE/tools/check-privapp.py" "$BT/aapt2" "$OUT/ImsStack.apk" \
    "$WORK/rom/framework-res.apk" "$HERE/permissions/privapp-permissions-com.android.imsstack.xml"
ls -la "$OUT"/*.apk | grep -v -e unsigned -e aligned
