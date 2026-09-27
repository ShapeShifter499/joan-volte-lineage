#!/bin/bash
# Build libimsstack.so and libimsmedia.so for arm64 from the patched AOSP
# sources in $WORK/src, the way Soong would inside a LineageOS 22.2 tree:
# Android.bp modules resolved by bp2ninja.py, Android 15 QPR2 headers,
# the platform libc++ (clang-r536225, std::__1), and the ROM's own
# /system/lib64 libraries to link against. Output: $WORK/out/native/.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
TC=$SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64
A=$WORK/aosp15
PL=$WORK/platlibs
CL=$A/clang/$CLANG_VERSION
OUT=$WORK/out/native
CFG=$OUT/config
mkdir -p "$CFG" "$OUT/empty"

# Modules the AOSP trees use but do not define: headers and the library
# to link. NDK APIs (AAudio, MediaCodec, camera, native_window) come from
# the NDK sysroot; their libraries still come from the ROM.
cat > "$CFG/external.json" <<EOF
{
 "libsystem_headers": {"include": ["$A/core/libsystem/include"]},
 "liblog": {"include": ["$A/logging/liblog/include"], "lib": "$PL/liblog.so"},
 "libbase": {"include": ["$A/libbase/include"], "lib": "$PL/libbase.so"},
 "libcutils": {"include": ["$A/core/libcutils/include"], "reexport": ["liblog", "libsystem_headers"], "lib": "$PL/libcutils.so"},
 "libutils": {"include": ["$A/core/libutils/binder/include", "$A/core/libutils/include"], "reexport": ["libcutils", "liblog", "libsystem_headers", "libbase"], "lib": "$PL/libutils.so"},
 "libbinder": {"include": ["$A/native/libs/binder/include"], "reexport": ["libutils", "libbase", "libcutils", "liblog"], "lib": "$PL/libbinder.so"},
 "libnativehelper": {"include": ["$A/libnativehelper/include", "$A/libnativehelper/header_only_include", "$A/libnativehelper/include_platform", "$A/libnativehelper/include_platform_header_only"], "lib": "-lnativehelper"},
 "libxml2": {"include": ["$A/libxml2/include"], "lib": "$PL/libxml2.so"},
 "libz": {"include": [], "lib": "$PL/libz.so"},
 "libcrypto": {"include": ["$A/boringssl/src/include"], "lib": "$PL/libcrypto.so"},
 "libssl": {"include": ["$A/boringssl/src/include"], "reexport": ["libcrypto"], "lib": "$PL/libssl.so"},
 "libaconfig_storage_read_api_cc": {"include": []},
 "server_configurable_flags": {"include": []},
 "imsstack_flags_cc_lib": {"include": []},
 "libmediautils": {"include": ["$A/av/media/utils/include"], "reexport": ["libbinder", "libutils"], "lib": "$PL/libmediautils.so"},
 "framework-permission-aidl-cpp": {"include": [], "lib": "$PL/framework-permission-aidl-cpp.so"},
 "libaaudio": {"include": [], "lib": "$PL/libaaudio.so"},
 "libandroid": {"include": [], "lib": "$PL/libandroid.so"},
 "libandroid_runtime": {"include": ["$A/base/core/jni/include", "$A/base/core/jni"], "reexport": ["libnativehelper", "libbinder", "libutils", "libcutils", "liblog"], "lib": "$PL/libandroid_runtime.so"},
 "libcamera2ndk": {"include": [], "lib": "$PL/libcamera2ndk.so"},
 "libjnigraphics": {"include": [], "lib": "$PL/libjnigraphics.so"},
 "libmediandk": {"include": [], "lib": "$PL/libmediandk.so"},
 "libnativewindow": {"include": [], "lib": "$PL/libnativewindow.so"}
}
EOF
# include_dirs entries (paths from the root of an Android tree).
cat > "$CFG/rootmap.json" <<EOF
{"external/libxml2": "$A/libxml2", "external/zlib": "$OUT/empty", "frameworks/av": "$A/av",
 "frameworks/native": "$A/native", "system/libbase": "$A/libbase"}
EOF
# Soong's device defaults for Android 15 (cc/config/global.go), trimmed to
# what changes code generation, plus its commonGlobalIncludes.
cat > "$CFG/toolchain.json" <<EOF
{
 "cc": "$TC/bin/clang", "cxx": "$TC/bin/clang++", "ar": "$TC/bin/llvm-ar",
 "cflags": ["--target=aarch64-linux-android35", "--sysroot=$TC/sysroot", "-fPIC", "-O2", "-g0", "-w",
            "-DANDROID", "-DNDEBUG", "-UDEBUG", "-D__compiler_offsetof=__builtin_offsetof",
            "-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__", "-fno-exceptions", "-fno-strict-aliasing",
            "-ffp-contract=off", "-fdata-sections", "-ffunction-sections", "-fstack-protector-strong",
            "-D_FORTIFY_SOURCE=2", "-nostdinc++",
            "-isystem", "$CL/android_libc++/platform/aarch64/include/c++/v1", "-isystem", "$CL/include/c++/v1"],
 "cppflags": ["-std=gnu++20", "-fno-rtti"],
 "conlyflags": ["-std=gnu17"],
 "global_includes": ["$A/core/include", "$A/logging/liblog/include", "$A/sysmedia/audio/include",
                     "$A/libhardware/include", "$A/native/include", "$A/av/include"],
 "ldflags": ["--target=aarch64-linux-android35", "--sysroot=$TC/sysroot", "-shared", "-nostdlib++",
             "-fuse-ld=lld", "-Wl,--no-undefined", "-Wl,-z,relro", "-Wl,-z,now", "-Wl,--build-id=sha1",
             "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL"],
 "ldlibs": ["$PL/libc++.so", "-ldl", "-lm", "-lc"]
}
EOF
for target in libimsstack libimsmedia; do
    python3 "$HERE/tools/bp2ninja.py" \
        --tree "$WORK/src/ImsStack/native" \
        --tree "$WORK/src/ImsMedia/service/src" \
        --external "$CFG/external.json" --root-map "$CFG/rootmap.json" \
        --config "$CFG/toolchain.json" --target "$target" --out "$OUT/$target"
    ninja -C "$OUT/$target" -j"$(nproc)"
done
ls -la "$OUT"/libimsstack/libimsstack.so "$OUT"/libimsmedia/libimsmedia.so
