#!/system/bin/sh
# Grant the AOSP IMS permissions on the phone itself. Run from a terminal
# app or: adb shell sh /data/local/tmp/grant-on-device.sh
# Full uid (adb shell or root) sets everything; other uids get failures -
# use Settings > Apps > ImsStack > Permissions for those instead.
n=0
for p in RECORD_AUDIO READ_PHONE_STATE ACCESS_COARSE_LOCATION \
         ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA; do
  pm grant com.android.imsstack android.permission.$p >/dev/null 2>&1 && n=$((n+1))
done
I=com.google.android.iwlan
appops set $I MANAGE_IPSEC_TUNNELS allow >/dev/null 2>&1 && n=$((n+1))
for p in READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do
  pm grant $I android.permission.$p >/dev/null 2>&1 && n=$((n+1))
done
pm grant com.android.telephony.qns android.permission.READ_PHONE_STATE >/dev/null 2>&1 && n=$((n+1))
echo "$n permissions granted. Reboot so the stack starts with them."
