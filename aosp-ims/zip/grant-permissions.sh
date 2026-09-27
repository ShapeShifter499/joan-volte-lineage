#!/bin/sh
# Grant the AOSP IMS stack's runtime permissions over adb, and the IPsec
# tunnel app-op VoWiFi needs.
#
# Flashed in the same recovery session as a ROM install or update, the
# zip's default-permissions file grants the runtime permissions at first
# boot; flashed onto a ROM that has already booted, "Calling permissions"
# in the app drawer asks for them. The IWLAN app-op Wi-Fi calling needs
# has no setting on the phone: run this once with USB debugging on:
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
for p in RECORD_AUDIO CAMERA READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION \
         ACCESS_BACKGROUND_LOCATION; do
  if out=$($ADB shell pm grant "$PKG" "android.permission.$p" 2>&1) && [ -z "$out" ]; then
    echo "granted  $p"
  else
    echo "FAILED   $p: $out"
    rc=1
  fi
done
# VoWiFi. IWLAN builds the IPsec tunnel to the carrier's ePDG. Upstream it
# runs as the system user; installed without the ROM's platform key it
# needs the MANAGE_IPSEC_TUNNELS app-op instead, which only adb can grant.
IWLAN=com.google.android.iwlan
QNS=com.android.telephony.qns
if $ADB shell pm path "$IWLAN" 2>/dev/null | grep -q '^package:'; then
  if out=$($ADB shell appops set "$IWLAN" MANAGE_IPSEC_TUNNELS allow 2>&1) && [ -z "$out" ]; then
    echo "granted  $IWLAN app-op MANAGE_IPSEC_TUNNELS"
  else
    echo "FAILED   $IWLAN app-op MANAGE_IPSEC_TUNNELS: $out"
    rc=1
  fi
  for p in READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do
    if out=$($ADB shell pm grant "$IWLAN" "android.permission.$p" 2>&1) && [ -z "$out" ]; then
      echo "granted  $IWLAN $p"
    else
      echo "FAILED   $IWLAN $p: $out"
      rc=1
    fi
  done
fi
if $ADB shell pm path "$QNS" 2>/dev/null | grep -q '^package:'; then
  if out=$($ADB shell pm grant "$QNS" android.permission.READ_PHONE_STATE 2>&1) && [ -z "$out" ]; then
    echo "granted  $QNS READ_PHONE_STATE"
  else
    echo "FAILED   $QNS READ_PHONE_STATE: $out"
    rc=1
  fi
fi
echo
echo "As the package manager now reports them:"
$ADB shell dumpsys package "$PKG" 2>/dev/null \
  | grep -E 'android\.permission\.(RECORD_AUDIO|CAMERA|READ_PHONE_STATE|ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION): granted=' \
  | sed 's/^ */  /' | sort -u
echo
echo "IWLAN app-op (VoWiFi): $($ADB shell appops get "$IWLAN" MANAGE_IPSEC_TUNNELS 2>/dev/null | tr -d '\r')"
echo
echo "RECORD_AUDIO matters for calls: ImsMedia records in this package, and"
echo "without it a call can't open the microphone. CAMERA is for video calls."
echo "Location adds the serving cell to P-Access-Network-Info and is needed"
echo "for emergency calls. Reboot now, so the stack starts with all of them."
exit "$rc"
