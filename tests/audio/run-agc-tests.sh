#!/usr/bin/env bash
# Host checks for the AGC audio_effects.xml merge (no Android).
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
MERGE=$ROOT/upstream/merge-agc-effect.sh
FIX=$ROOT/tests/audio/fixtures/orig-audio-effects.xml
OUT=$ROOT/native/build/agc-host
mkdir -p "$OUT"

fail() { echo "FAIL: $*"; exit 1; }
pass() { echo "  ok: $*"; }

[ -f "$MERGE" ] || fail "missing $MERGE"
[ -f "$FIX" ] || fail "missing $FIX"

# The fixture is joan's real /vendor/etc/audio_effects.xml: aec and ns
# from Qualcomm, no AGC, no pre_processing library.
grep -q 'libqcomvoiceprocessing.so' "$FIX" || fail "fixture lost its qcom library"
grep -q 'libaudiopreprocessing.so' "$FIX" && fail "fixture already has AGC"
pass "fixture is a real joan config with no AGC"

sh "$MERGE" "$FIX" > "$OUT/merged.xml" || fail "merge"

python3 - "$OUT/merged.xml" "$FIX" <<'PY' || fail "merged xml"
import sys, xml.etree.ElementTree as ET
merged = ET.parse(sys.argv[1]).getroot()
orig = ET.parse(sys.argv[2]).getroot()
ns = {'a': 'http://schemas.android.com/audio/audio_effects_conf/v2_0'}

def libs(root):
    return {e.get('name'): e.get('path') for e in root.iter() if e.tag.endswith('library')}
def fx(root):
    return {e.get('name'): (e.get('library'), e.get('uuid'))
            for e in root.iter() if e.tag.endswith('effect')}

ol, ml = libs(orig), libs(merged)
of, mf = fx(orig), fx(merged)

# Nothing the ROM had may be lost: this is the whole device's audio config.
for k, v in ol.items():
    assert ml.get(k) == v, ('library dropped or changed', k, v, ml.get(k))
for k, v in of.items():
    assert mf.get(k) == v, ('effect dropped or changed', k, v, mf.get(k))

assert ml.get('pre_processing') == 'libaudiopreprocessing.so', ml
assert mf.get('agc') == ('pre_processing',
                         'aa8130e0-66fc-11e0-bad0-0002a5d5c51b'), mf.get('agc')

# aec and ns must STAY on Qualcomm's library. Two implementations of one
# effect type in a single config is a coin toss at runtime.
assert mf['aec'][0] == 'audio_pre_processing', mf['aec']
assert mf['ns'][0] == 'audio_pre_processing', mf['ns']
assert len([n for n in mf if n == 'aec']) == 1

print('merged libraries', len(ml), 'effects', len(mf))
PY
pass "agc added, every ROM library and effect preserved, aec/ns left on qcom"

sh "$MERGE" "$OUT/merged.xml" > "$OUT/merged2.xml" || fail "second merge"
cmp -s "$OUT/merged.xml" "$OUT/merged2.xml" || fail "re-merge is not a no-op"
pass "re-running the merge changes nothing"

# Refuse rather than corrupt the device's audio config.
printf '<audio_effects_conf/>\n' > "$OUT/bad.xml"
if sh "$MERGE" "$OUT/bad.xml" > /dev/null 2>&1; then
    fail "merge accepted a file with no <libraries>/<effects>"
fi
pass "a config without the expected sections is refused, not mangled"

# The zip must not touch /vendor at all. The AGC step used to live in
# the installer; it could never work, because /vendor on this device has
# 335 free blocks and refuses writes, and carrying it meant a mount, a
# write probe and a failure path that existed only to be skipped. Worse,
# an early version guarded that mount with "|| true", which cannot catch
# the exit 1 inside the installer's own error(), and aborted an entire
# flash before a single file was copied.
#
# The patch now belongs to a ROM build, and upstream/README.md documents
# how to apply it. These checks keep it out of the zip.
UB=$ROOT/scripts/update-binary
[ -f "$UB" ] || fail "missing $UB"

grep -qiE "vendmnt|vendor_rw|merge-agc" "$UB" \
    && fail "the installer references vendor or the AGC merge again"
pass "the installer does not touch /vendor"

grep -q "merge-agc-effect" "$ROOT/scripts/pack-zip.sh" \
    && fail "the AGC merge script is being packed into the zip again"
pass "the AGC merge script is not shipped in the zip"

[ -f "$ROOT/upstream/merge-agc-effect.sh" ] \
    || fail "the AGC merge script should live in upstream/ for ROM builders"
grep -q "audio_effects.xml" "$ROOT/upstream/README.md" \
    || fail "upstream/README.md must document the AGC patch"
pass "the patch and its documentation live in upstream/"

echo "AGC effect merge tests passed"
