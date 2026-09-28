#!/system/bin/sh
# Grant the AOSP IMS stack's runtime permissions and the IWLAN IPsec
# tunnel app-op, running ON the phone itself. The Windows/macOS/Linux
# scripts (grant-permissions.bat / grant-permissions.sh) do this from a
# computer; this one does it without one.
#
# Run it from a terminal on the phone, or over adb from a computer:
#   sh /data/local/tmp/grant-on-device.sh
#
# Where it runs decides what works:
#   - as shell (adb shell) or root: everything, including the IWLAN
#     MANAGE_IPSEC_TUNNELS app-op VoWiFi needs.
#   - from a phone terminal app (another uid): the pm grant calls fail
#     with SecurityException. Use Settings > Apps > ImsStack >
#     Permissions for the runtime permissions instead, and run this
#     once over adb for the IWLAN app-op - no terminal app can set it.

PKG=com.android.imsstack
IWLAN=com.google.android.iwlan
QNS=com.android.telephony.qns
rc=0

# Foreground location before background: Android refuses the reverse.
for p in RECORD_AUDIO READ_PHONE_STATE ACCESS_COARSE_LOCATION \
         ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA; do
  if pm grant "$PKG" "android.permission.$p" 2>/dev/null; then
    echo "granted  $p"
  else
    echo "FAILED   $p (needs shell or root uid - see header)"
    rc=1
  fi
done

if pm path "$IWLAN" >/dev/null 2>&1; then
  if appops set "$IWLAN" MANAGE_IPSEC_TUNNELS allow 2>/dev/null; then
    echo "granted  $IWLAN app-op MANAGE_IPSEC_TUNNELS"
  else
    echo "FAILED   $IWLAN app-op MANAGE_IPSEC_TUNNELS (needs shell or root uid)"
    rc=1
  fi
  for p in READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do
    if pm grant "$IWLAN" "android.permission.$p" 2>/dev/null; then
      echo "granted  $IWLAN $p"
    else
      echo "FAILED   $IWLAN $p"
      rc=1
    fi
  done
fi

if pm path "$QNS" >/dev/null 2>&1; then
  if pm grant "$QNS" android.permission.READ_PHONE_STATE 2>/dev/null; then
    echo "granted  $QNS READ_PHONE_STATE"
  else
    echo "FAILED   $QNS READ_PHONE_STATE"
    rc=1
  fi
fi

echo
echo "Verify in Settings > Apps > ImsStack > Permissions (everything"
echo "granted above shows as allowed), then reboot so the stack starts"
echo "with all of them. Record_audio matters for calls: without it the"
echo "other side hears silence. The IWLAN app-op is what lets Wi-Fi"
echo "calling build its tunnel."
exit $rc
