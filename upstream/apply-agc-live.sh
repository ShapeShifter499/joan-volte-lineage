#!/usr/bin/env bash
# Re-apply the AGC declaration to a running joan over adb.
#
# FOR A BENCH, NOT FOR TESTERS. This writes through the `adb remount`
# scratch overlay, which is a debug facility: a LineageOS nightly or OTA
# wipes it, and a factory reset wipes it. The durable answer is the
# device-tree patch described in upstream/README.md; this exists so a
# development handset can carry the AGC between ROM updates without
# rebuilding.
#
# Usage: upstream/apply-agc-live.sh [adb-serial]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
MERGE=$ROOT/upstream/merge-agc-effect.sh
SER=${1:-}
ADB=(adb)
[ -n "$SER" ] && ADB=(adb -s "$SER")

say() { echo "  $*"; }

"${ADB[@]}" root >/dev/null 2>&1 || true
sleep 3
"${ADB[@]}" wait-for-device

if "${ADB[@]}" shell "grep -q 'name=\"agc\"' /vendor/etc/audio_effects.xml" 2>/dev/null; then
    say "already declared; nothing to do"
    "${ADB[@]}" shell "ls -l /vendor/etc/audio_effects.xml"
    exit 0
fi

say "remounting"
"${ADB[@]}" remount >/dev/null 2>&1 || {
    echo "adb remount failed; this device cannot take the live patch" >&2
    exit 1
}

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT
"${ADB[@]}" pull /vendor/etc/audio_effects.xml "$TMPD/before.xml" >/dev/null
say "current: $(wc -c < "$TMPD/before.xml") bytes"

# Keep a copy on the device: the overlay is the only thing standing
# between the running system and a 0-byte file on the raw partition.
"${ADB[@]}" shell "cp /vendor/etc/audio_effects.xml /data/local/tmp/audio_effects.xml.before" || true

sh "$MERGE" "$TMPD/before.xml" > "$TMPD/after.xml"
say "merged:  $(wc -c < "$TMPD/after.xml") bytes"
diff "$TMPD/before.xml" "$TMPD/after.xml" || true

"${ADB[@]}" push "$TMPD/after.xml" /vendor/etc/audio_effects.xml >/dev/null
"${ADB[@]}" shell "chmod 644 /vendor/etc/audio_effects.xml; \
    chcon u:object_r:vendor_configs_file:s0 /vendor/etc/audio_effects.xml"

"${ADB[@]}" shell "grep -q 'name=\"agc\"' /vendor/etc/audio_effects.xml" \
    || { echo "readback failed: agc not present after push" >&2; exit 1; }
say "declared; reboot for audioserver to load it"
say "confirm afterwards with platform_agc=true in the joan trace"
