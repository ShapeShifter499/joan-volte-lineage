#!/bin/sh
# Grant the AOSP IMS stack's runtime permissions over adb.
#
# Flashed in the same recovery session as a ROM install or update, the
# zip's default-permissions file does this at first boot. Flashed onto a
# ROM that has already booted, run this once with USB debugging on:
#   sh aosp-ims/zip/grant-permissions.sh [device-serial]
set -u
ADB=${ADB:-adb}
if [ -n "${1:-}" ]; then
  ADB="$ADB -s $1"
fi
PKG=com.android.imsstack
if ! $ADB get-state >/dev/null 2>&1; then
  echo "No device over adb. Enable USB debugging and accept the prompt on the phone." >&2
  exit 1
fi
if ! $ADB shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
  echo "$PKG is not installed on this device. Flash the AOSP IMS zip first." >&2
  exit 1
fi
rc=0
# Foreground location before background: Android refuses the reverse.
for p in RECORD_AUDIO READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION \
         ACCESS_BACKGROUND_LOCATION; do
  if out=$($ADB shell pm grant "$PKG" "android.permission.$p" 2>&1) && [ -z "$out" ]; then
    echo "granted  $p"
  else
    echo "FAILED   $p: $out"
    rc=1
  fi
done
echo
echo "As the package manager now reports them:"
$ADB shell dumpsys package "$PKG" 2>/dev/null \
  | grep -E 'android\.permission\.(RECORD_AUDIO|READ_PHONE_STATE|ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION): granted=' \
  | sed 's/^ */  /' | sort -u
echo
echo "RECORD_AUDIO matters for calls: ImsMedia records in this package, and"
echo "without it the other side hears silence. Location adds the serving"
echo "cell to P-Access-Network-Info and is needed for emergency calls."
exit "$rc"
