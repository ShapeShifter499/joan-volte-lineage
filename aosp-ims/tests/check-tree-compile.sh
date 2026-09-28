#!/bin/bash
# The kit's native code under a LineageOS 22.2 tree build's compiler:
# Android 15's clang ($CLANG_VERSION) with Soong's warnings as errors
# (tests/tree-compile.py). Run after tools/build-native.sh (its ninja
# files list every compile) and tests/check-soong.sh (its Soong computes
# each module's flags). Fetches clang's binary and builtin headers from the
# prebuilts checkout tools/setup-workdir.sh made.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
S=$WORK/soong15
C=$WORK/aosp15/clang
for f in "$WORK/out/native/libimsstack/build.ninja" "$WORK/out/native/libimsmedia/build.ninja" \
         "$S/build/soong/imscheck/imscheck_test.go" "$C/.git"; do
    [ -e "$f" ] || { echo "$f missing: run setup-workdir.sh, build-native.sh and check-soong.sh"; exit 1; }
done

# Android 15's clang: the compiler and its builtin headers only.
T=$WORK/aosp15-clang-bin
V=$(git -C "$C" ls-tree --name-only HEAD "$CLANG_VERSION/lib/clang/" | sed -n 1p)
[ -n "$V" ] || { echo "no $CLANG_VERSION/lib/clang/<version> in the prebuilts"; exit 1; }
if [ ! -x "$T/$CLANG_VERSION/bin/clang-${V##*/}" ]; then
    rm -rf "$T" && mkdir -p "$T"
    git -C "$C" archive HEAD "$CLANG_VERSION/bin/clang-${V##*/}" "$V/include" | tar -x -C "$T"
fi

# Each native module's flags, as Soong computes them for the tree build.
MODULES=$(python3 - "$WORK/src" <<'PY'
import os, re, sys
names = []
for root in ('ImsStack/native', 'ImsMedia'):
    for dp, dn, fn in os.walk(os.path.join(sys.argv[1], root)):
        if '/test' in dp or 'Android.bp' not in fn:
            continue
        text = re.sub(r'//[^\n]*', '', open(os.path.join(dp, 'Android.bp')).read())
        for m in re.finditer(r'^(?:cc_library_static|cc_library_shared)\s*\{(.*?)^\}', text, re.M | re.S):
            n = re.search(r'name:\s*"([^"]+)"', m.group(1))
            if n:
                names.append(n.group(1))
print(','.join(sorted(names)))
PY
)
(
    cd "$S/build/soong"
    FLAGS_OUT=$WORK/native-flags.txt FLAGS_MODULES=$MODULES \
        IMSSTACK=$WORK/src/ImsStack IMSMEDIA=$WORK/src/ImsMedia STUBS=$HERE/tests/soong/stubs.bp \
        GOTOOLCHAIN=local GOFLAGS= go test ./imscheck/ -count=1 -run TestDumpNativeFlags >/dev/null
)
python3 "$HERE/tests/tree-compile.py" "$T/$CLANG_VERSION/bin/clang-${V##*/}" "$T/$V" \
    "$S/build/soong/cc/config" "$WORK/native-flags.txt" \
    "$WORK/out/native/libimsstack/build.ninja" "$WORK/out/native/libimsmedia/build.ninja"
