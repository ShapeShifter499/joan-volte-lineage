#!/bin/sh
# Add AOSP's AGC pre-processing effect to a device audio_effects.xml.
# Usage: merge-agc-effect.sh ORIG.xml > merged.xml
#
# joan ships libaudiopreprocessing.so in /vendor/lib*/soundfx but never
# declares it, so AutomaticGainControl.isAvailable() is false and the
# VoLTE uplink is whatever the microphone gave us -- measured ~16 dB
# below the downlink on real calls.
#
# Only agc is added. aec and ns are already provided by Qualcomm's
# libqcomvoiceprocessing.so on this device; declaring AOSP's versions of
# those as well would put two implementations of the same effect type in
# one config, and the effect framework picks by type.
set -eu

ORIG=${1:-}
[ -n "$ORIG" ] || { echo "usage: merge-agc-effect.sh ORIG.xml" >&2; exit 2; }
[ -f "$ORIG" ] || { echo "missing orig $ORIG" >&2; exit 1; }

AGC_UUID=aa8130e0-66fc-11e0-bad0-0002a5d5c51b
LIB_LINE='        <library name="pre_processing" path="libaudiopreprocessing.so"/>'
FX_LINE="        <effect name=\"agc\" library=\"pre_processing\" uuid=\"$AGC_UUID\"/>"

# Refuse rather than corrupt: this is the device's whole audio config.
grep -q '</libraries>' "$ORIG" || { echo "no </libraries> in $ORIG" >&2; exit 1; }
grep -q '</effects>' "$ORIG"   || { echo "no </effects> in $ORIG" >&2; exit 1; }

# Idempotent: a second run must not add a second copy.
have_lib=0
have_fx=0
grep -q 'libaudiopreprocessing.so' "$ORIG" && have_lib=1
grep -qE '<effect name="agc"' "$ORIG" && have_fx=1

if [ "$have_lib" = 1 ] && [ "$have_fx" = 1 ]; then
    cat "$ORIG"
    exit 0
fi

while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        *'</libraries>'*)
            [ "$have_lib" = 0 ] && printf '%s\n' "$LIB_LINE"
            printf '%s\n' "$line"
            ;;
        *'</effects>'*)
            [ "$have_fx" = 0 ] && printf '%s\n' "$FX_LINE"
            printf '%s\n' "$line"
            ;;
        *)
            printf '%s\n' "$line"
            ;;
    esac
done < "$ORIG"
