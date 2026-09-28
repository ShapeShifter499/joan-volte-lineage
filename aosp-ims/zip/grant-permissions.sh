#!/system/bin/sh
# Grant the AOSP IMS stack's runtime permissions over adb, plus the IWLAN
# app-op Wi-Fi calling needs. Usage: sh grant-permissions.sh [serial]
A=${ADB:-adb}
[ -n "${1:-}" ] && A="$A -s $1"
P=com.android.imsstack
n=0
for p in RECORD_AUDIO READ_PHONE_STATE ACCESS_COARSE_LOCATION \
         ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA; do
  $A shell pm grant $P android.permission.$p >/dev/null 2>&1 && n=$((n+1))
done
I=com.google.android.iwlan
$A shell appops set $I MANAGE_IPSEC_TUNNELS allow >/dev/null 2>&1 && n=$((n+1))
for p in READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do
  $A shell pm grant $I android.permission.$p >/dev/null 2>&1 && n=$((n+1))
done
for p in READ_PHONE_STATE; do
  $A shell pm grant com.android.telephony.qns android.permission.$p >/dev/null 2>&1 && n=$((n+1))
done
echo "$n permissions granted. Reboot so the stack starts with them."
