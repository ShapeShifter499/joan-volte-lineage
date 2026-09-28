#!/bin/bash
# Android 15's Soong over the kit's Android.bp files (tests/soong).
#
# A LineageOS 22.2 tree build syncs ImsStack and ImsMedia from Android 17
# and applies the kit's patches (upstream/aosp-ims). Soong reads every
# Android.bp in the tree before it builds anything, so a module type,
# property or dependency Android 15 does not have stops the whole build.
# This fetches Android 15's Soong (build/soong and the Go modules it
# needs, android15-qpr2-release) and runs tests/soong/imscheck_test.go
# over $WORK/src/ImsStack and $WORK/src/ImsMedia, the patched sources
# tools/setup-workdir.sh makes.
#
#   --verify-stubs  also check that every stand-in in tests/soong/stubs.bp
#                   is defined in Android 15 (blobless fetches of the
#                   projects that define them)
#
# Needs git and Go 1.23 or later.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
S=$WORK/soong15
AOSP=https://android.googlesource.com
retry() { local i; for i in 1 2 3 4 5; do "$@" && return 0; sleep $((i * 4)); done; return 1; }

for d in ImsStack ImsMedia; do
    [ -f "$WORK/src/$d/Android.bp" ] || [ -d "$WORK/src/$d/.git" ] \
        || { echo "$WORK/src/$d missing: run tools/setup-workdir.sh"; exit 1; }
done
command -v go >/dev/null || { echo "go not found (Go 1.23 or later)"; exit 1; }

# Soong's own layout: build/soong's go.work names the others by relative path.
for p in build/soong build/blueprint external/golang-protobuf external/starlark-go external/go-cmp; do
    [ -d "$S/$p/.git" ] || retry git clone -q --depth 1 -b "$PLATFORM_BRANCH" \
        "$AOSP/platform/$p" "$S/$p"
done
mkdir -p "$S/build/soong/imscheck"
cp "$HERE/tests/soong/imscheck_test.go" "$S/build/soong/imscheck/"
(
    cd "$S/build/soong"
    IMSSTACK=$WORK/src/ImsStack IMSMEDIA=$WORK/src/ImsMedia STUBS=$HERE/tests/soong/stubs.bp \
        GOTOOLCHAIN=local GOFLAGS= go test ./imscheck/ -count=1 -v
)
echo "Android 15 Soong: ImsStack and ImsMedia Android.bp files OK"

[ "${1:-}" = --verify-stubs ] || exit 0

# Where each stand-in is defined at $PLATFORM_BRANCH: project (build/make
# is "build"), file, name. A stand-in not listed here has to be one that
# Android 15's own ImsMedia uses, which a LineageOS 22.2 tree builds.
DEFS="
frameworks/opt/telephony Android.bp telephony-common
frameworks/opt/net/ims Android.bp ims-common
external/libphonenumber Android.bp libphonenumber
external/protobuf Android.bp libprotobuf-java-lite
build tools/aconfig/aconfig_storage_read_api/Android.bp libaconfig_storage_read_api_cc
system/server_configurable_flags libflags/Android.bp server_configurable_flags
system/libbase Android.bp libbase
libnativehelper Android.bp libnativehelper
external/libxml2 Android.bp libxml2
external/zlib Android.bp libz
external/boringssl Android.bp libcrypto
external/boringssl Android.bp libssl
external/dexmaker Android.bp mockito-target
external/dexmaker Android.bp mockito-target-minus-junit4
external/dexmaker Android.bp mockito-target-extended-minus-junit4
external/dexmaker Android.bp libdexmakerjvmtiagent
external/dexmaker Android.bp libstaticjvmtiagent
external/junit Android.bp junit
external/googletest googletest/Android.bp libgtest
frameworks/base tests/utils/testutils/Android.bp frameworks-base-testutils
frameworks/base test-base/Android.bp android.test.base
frameworks/base test-mock/Android.bp android.test.mock
frameworks/base test-runner/Android.bp android.test.runner
hardware/interfaces radio/aidl/Android.bp android.hardware.radio.ims.media
"
V=$S/verify
mkdir -p "$V"
fetch() {
    local d=$V/${1//\//_}
    [ -d "$d" ] || retry git clone -q --depth 1 --filter=blob:none --no-checkout \
        -b "$PLATFORM_BRANCH" "$AOSP/platform/$1" "$d"
    echo "$d"
}
bad=0
found=" "
while read -r proj file name; do
    [ -n "$proj" ] || continue
    d=$(fetch "$proj")
    if git -C "$d" show "HEAD:$file" | grep -q "name: *\"$name\""; then
        found="$found$name "
    else
        echo "not in $proj/$file: $name"
        bad=1
    fi
done <<< "$DEFS"
# The radio HAL's Java library for a version exists once that version is
# frozen; ImsMedia uses version 2.
d=$(fetch hardware/interfaces)
git -C "$d" show HEAD:radio/aidl/Android.bp | python3 -c '
import re, sys
t = sys.stdin.read()
m = re.search(r"name: \"android.hardware.radio.ims.media\",(.*?)\n}", t, re.S)
sys.exit(0 if m and re.search(r"version: \"2\"", m.group(1)) else 1)' \
    && found="${found}android.hardware.radio.ims.media-V2-java " \
    || { echo "android.hardware.radio.ims.media version 2 is not frozen"; bad=1; }
# java_sdk_library X gives X.stubs.system.
for n in android.test.base android.test.mock android.test.runner; do
    case "$found" in *" $n "*) found="${found}$n.stubs.system ";; esac
done
d=$(fetch packages/modules/ImsMedia)
a15media=$(git -C "$d" ls-tree -r --name-only HEAD | grep 'Android.bp$' \
    | while read -r f; do git -C "$d" show "HEAD:$f"; done)
for name in $(sed -n 's/^[a-z_]* *{ *name: *"\([^"]*\)".*/\1/p' "$HERE/tests/soong/stubs.bp"); do
    case "$found" in *" $name "*) continue;; esac
    if grep -q "\"$name\"" <<< "$a15media"; then
        continue
    fi
    echo "stand-in not found in Android 15: $name"
    bad=1
done
[ $bad = 0 ] || exit 1
echo "Android 15: all $(grep -c '^[a-z_]* *{ *name:' "$HERE/tests/soong/stubs.bp") stand-ins defined"
