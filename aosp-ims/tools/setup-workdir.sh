#!/bin/bash
# Fetch everything the AOSP IMS backport builds from, into $WORK
# (default aosp-ims/work, git-ignored). Every step is skipped when its
# output already exists, so re-running only fills in what is missing.
#
# Needs: git, curl, python3, brotli, debugfs (e2fsprogs), java, and the
# Android SDK/NDK under $ANDROID_HOME (default ~/Android/Sdk). No root:
# ROM images are read with debugfs, not mounted.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
mkdir -p "$WORK"
# The work dir holds upstream Android.bp files. If this repository sits
# inside an Android tree (vendor/lge/joan-ims), Soong would parse them and
# fail on duplicate modules; a .find-ignore makes it skip the directory.
touch "$WORK/.find-ignore"
cd "$WORK"
MAVEN=https://repo1.maven.org/maven2
GOOGLE_MAVEN=https://dl.google.com/dl/android/maven2
AOSP=https://android.googlesource.com

retry() { local i; for i in 1 2 3 4 5; do "$@" && return 0; sleep $((i * 4)); done; return 1; }

# --- 1. AOSP 17 ImsStack / ImsMedia, pinned and patched -------------------
upstream() { # upstream <name> <url> <ref> <commit>
    local name=$1 url=$2 ref=$3 commit=$4
    [ -d "src/$name/.git" ] && return 0
    retry git clone -q --depth 1 -b "$ref" "$url" "src/$name"
    [ "$(git -C "src/$name" rev-parse HEAD)" = "$commit" ] \
        || { echo "src/$name: $ref is not $commit"; exit 1; }
    if ls "$HERE/patches/$name/"*.patch >/dev/null 2>&1; then
        git -C "src/$name" -c user.name=build -c user.email=build@localhost \
            am -q "$HERE/patches/$name/"*.patch
    fi
    echo "src/$name: $ref + $(ls "$HERE/patches/$name/" 2>/dev/null | wc -l) patches"
}
upstream ImsStack "$IMSSTACK_URL" "$IMSSTACK_REF" "$IMSSTACK_COMMIT"
upstream ImsMedia "$IMSMEDIA_URL" "$IMSMEDIA_REF" "$IMSMEDIA_COMMIT"
upstream Iwlan "$IWLAN_URL" "$IWLAN_REF" "$IWLAN_COMMIT"
upstream Qns "$QNS_URL" "$QNS_REF" "$QNS_COMMIT"

# --- 2. Android 15 QPR2 headers (blobless sparse checkouts) ----------------
sparse() { # sparse <project> <dir> <branch> <paths...>
    local proj=$1 dir=$2 branch=$3; shift 3
    if [ ! -d "$dir/.git" ]; then
        retry git clone -q --filter=blob:none --no-checkout --depth 1 \
            -b "$branch" "$AOSP/$proj" "$dir"
    fi
    git -C "$dir" sparse-checkout init --no-cone >/dev/null 2>&1
    printf '%s\n' "$@" > "$dir/.git/info/sparse-checkout"
    retry git -C "$dir" checkout -q
}
P=aosp15
sparse platform/frameworks/native $P/native "$PLATFORM_BRANCH" \
    /libs/binder/include/ /include/
sparse platform/system/core $P/core "$PLATFORM_BRANCH" \
    /libutils/ /libcutils/include/ /libsystem/include/ /include/
sparse platform/system/libbase $P/libbase "$PLATFORM_BRANCH" /include/
sparse platform/system/logging $P/logging "$PLATFORM_BRANCH" /liblog/include/
sparse platform/libnativehelper $P/libnativehelper "$PLATFORM_BRANCH" \
    /include/ /header_only_include/ /include_jni/ /include_platform/ \
    /include_platform_header_only/
sparse platform/external/libxml2 $P/libxml2 "$PLATFORM_BRANCH" /include/
sparse platform/external/boringssl $P/boringssl "$PLATFORM_BRANCH" /src/include/
sparse platform/frameworks/av $P/av "$PLATFORM_BRANCH" \
    /media/utils/include/ /camera/ndk/include/ /include/
sparse platform/system/media $P/sysmedia "$PLATFORM_BRANCH" /audio/include/
sparse platform/hardware/libhardware $P/libhardware "$PLATFORM_BRANCH" /include/
sparse platform/frameworks/base $P/base "$PLATFORM_BRANCH" \
    /core/jni/include/ /core/jni/android_os_Parcel.h
sparse platform/prebuilts/clang/host/linux-x86 $P/clang "$PLATFORM_BRANCH" \
    "/$CLANG_VERSION/include/c++/v1/" \
    "/$CLANG_VERSION/android_libc++/platform/aarch64/include/"
sparse platform/prebuilts/sdk sdk "$PLATFORM_BRANCH" \
    /35/module-lib/ /35/public/android.jar
# Android's carrier id database, for the carrier config generator.
sparse platform/packages/providers/TelephonyProvider $P/telephonyprovider "$PLATFORM_BRANCH" \
    /assets/latest_carrier_id/carrier_list.textpb
# ImsMediaFramework links android.hardware.radio.ims.media-V2-java.
sparse platform/hardware/interfaces hwif "$IMSMEDIA_REF" \
    /radio/aidl/aidl_api/android.hardware.radio.ims.media/2/ \
    /radio/aidl/aidl_api/android.hardware.radio/4/android/hardware/radio/AccessNetwork.aidl
# QNS's stats library and IWLAN's HandlerExecutor, from the same release.
sparse platform/packages/modules/Telephony src/modules-telephony "$LIBS_REF" \
    /libs/TelephonyStatsLib/src/
sparse platform/frameworks/libs/modules-utils src/modules-utils "$LIBS_REF" \
    /java/com/android/modules/utils/HandlerExecutor.java
echo "aosp15 headers, clang $CLANG_VERSION libc++, sdk 35 stubs, radio AIDL, VoWiFi libs: ok"

# --- 3. Java libraries --------------------------------------------------------
mkdir -p deps tools/d2j
[ -s deps/androidx-annotation.jar ] || retry curl -sSfL -o deps/androidx-annotation.jar \
    "$GOOGLE_MAVEN/androidx/annotation/annotation-jvm/1.8.0/annotation-jvm-1.8.0.jar"
[ -s deps/libphonenumber.jar ] || retry curl -sSfL -o deps/libphonenumber.jar \
    "$MAVEN/com/googlecode/libphonenumber/libphonenumber/8.13.40/libphonenumber-8.13.40.jar"
[ -s deps/support-annotations.jar ] || retry curl -sSfL -o deps/support-annotations.jar \
    "$GOOGLE_MAVEN/com/android/support/support-annotations/28.0.0/support-annotations-28.0.0.jar"
for a in auto-value auto-value-annotations; do
    [ -s "deps/$a.jar" ] || retry curl -sSfL -o "deps/$a.jar" \
        "$MAVEN/com/google/auto/value/$a/$AUTOVALUE_VERSION/$a-$AUTOVALUE_VERSION.jar"
done
for a in dex-tools dex-translator dex-reader-api d2j-external dex-reader dex-ir d2j-base-cmd; do
    f=tools/d2j/$a-$DEX2JAR_VERSION.jar
    [ -s "$f" ] || retry curl -sSfL -o "$f" \
        "$MAVEN/de/femtopedia/dex2jar/$a/$DEX2JAR_VERSION/$a-$DEX2JAR_VERSION.jar"
done
for a in asm asm-tree asm-util asm-analysis asm-commons; do
    f=tools/d2j/$a-$ASM_VERSION.jar
    [ -s "$f" ] || retry curl -sSfL -o "$f" "$MAVEN/org/ow2/asm/$a/$ASM_VERSION/$a-$ASM_VERSION.jar"
done

# --- 4. The ROM: framework classes and platform libraries -------------------
mkdir -p rom fwcls platlibs
OTA=rom/$(basename "$LINEAGE_OTA_URL")
if [ ! -s rom/system.img ]; then
    [ -s "$OTA" ] || retry curl -sSfL -o "$OTA" "$LINEAGE_OTA_URL"
    echo "$LINEAGE_OTA_SHA256  $OTA" | sha256sum -c --quiet
    for part in system; do
        unzip -o -q "$OTA" "$part.new.dat.br" "$part.transfer.list" -d rom
        brotli -d -f "rom/$part.new.dat.br" -o "rom/$part.new.dat"
        python3 "$HERE/tools/sdat2img.py" "rom/$part.transfer.list" \
            "rom/$part.new.dat" "rom/$part.img" >/dev/null
        rm -f "rom/$part.new.dat.br" "rom/$part.new.dat"
        python3 - "rom/$part.img" <<'EOF'
import os, struct, sys
p = sys.argv[1]
sb = open(p, 'rb').read(2048)[1024:]
n = struct.unpack('<I', sb[4:8])[0] | struct.unpack('<I', sb[0x150:0x154])[0] << 32
need = n * (1024 << struct.unpack('<I', sb[24:28])[0])
if os.path.getsize(p) < need:
    os.truncate(p, need)
EOF
    done
fi
dump() { debugfs -R "dump $1 $2" rom/system.img >/dev/null 2>&1; [ -s "$2" ] || { echo "cannot read $1"; exit 1; }; }
for j in framework telephony-common ims-common framework-location framework-connectivity-b; do
    [ -s "fwcls/$j.jar" ] && continue
    dump "/system/framework/$j.jar" "rom/$j.jar"
    java -Xmx4g -cp "$(ls tools/d2j/*.jar | tr '\n' ':')" \
        com.googlecode.dex2jar.tools.Dex2jarCmd -f -n -o "fwcls/$j.jar" "rom/$j.jar" >/dev/null
done
[ -s rom/framework-res.apk ] || dump /system/framework/framework-res.apk rom/framework-res.apk
for l in libbinder libutils libcutils libc++ liblog libbase libxml2 libcrypto libssl \
         libz libmediautils framework-permission-aidl-cpp libaaudio libandroid \
         libandroid_runtime libcamera2ndk libjnigraphics libmediandk libnativewindow; do
    [ -s "platlibs/$l.so" ] || dump "/system/lib64/$l.so" "platlibs/$l.so"
done
echo "ROM framework classes in fwcls/, platform libraries in platlibs/: ok"
