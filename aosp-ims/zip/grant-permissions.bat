@echo off
rem Grant the AOSP IMS stack's runtime permissions over adb, plus the IWLAN
rem app-op Wi-Fi calling needs. Usage: grant-permissions.bat [serial]
set ADB=adb
if not "%~1"=="" set ADB=adb -s %~1
set P=com.android.imsstack
set I=com.google.android.iwlan
for %%p in (RECORD_AUDIO READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA) do adb shell pm grant %P% android.permission.%%p >nul 2>&1
adb shell appops set %I% MANAGE_IPSEC_TUNNELS allow >nul 2>&1
for %%p in (READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION) do adb shell pm grant %I% android.permission.%%p >nul 2>&1
adb shell pm grant com.android.telephony.qns android.permission.READ_PHONE_STATE >nul 2>&1
echo Done. Permissions granted. permissions granted. Reboot so the stack starts with them.
