#!/bin/sh
# Grant joan's runtime permissions from a computer, over adb.
#
#   ./scripts/grant-permissions.sh            # the only device attached
#   ./scripts/grant-permissions.sh SERIAL     # one of several
#
# No root and no "rooted debugging" needed: `pm grant` requires
# GRANT_RUNTIME_PERMISSIONS, and the adb shell user holds it. USB
# debugging must be on (Settings > System > Developer options).
#
# Why this exists: RECORD_AUDIO and the location permissions are
# dangerous runtime permissions, and a recovery zip has no safe way to
# grant them to a ROM that has already booted. The zip ships
# etc/default-permissions, which PackageManager applies on the first boot
# after a ROM install or update, so flashing joan together with the ROM
# needs none of this. Flashing joan on its own does, or one tap in the
# "joan IMS" launcher entry.
#
# Nothing here runs on the phone at boot. The alpha70-73 attempt to grant
# these from an init service broke a tester's ROM; this is the same four
# commands, run by a person, once.
set -u
ADB=${ADB:-adb}
if [ -n "${1:-}" ]; then
  ADB="$ADB -s $1"
fi
PKG=org.joan.ims

if ! $ADB get-state >/dev/null 2>&1; then
  echo "No device over adb. Enable USB debugging and accept the prompt on the phone." >&2
  exit 1
fi
if ! $ADB shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
  echo "$PKG is not installed on this device. Flash joan-volte-recovery.zip first." >&2
  exit 1
fi

# Foreground location before background: the platform refuses
# ACCESS_BACKGROUND_LOCATION unless a foreground one is already held.
rc=0
for p in RECORD_AUDIO ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION \
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
  | grep -E 'android\.permission\.(RECORD_AUDIO|ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION): granted=' \
  | sed 's/^ */  /' | sort -u
echo
echo "RECORD_AUDIO is the one that matters for calls: without it the other"
echo "side hears silence. Location only adds the serving cell to"
echo "P-Access-Network-Info."
exit "$rc"
