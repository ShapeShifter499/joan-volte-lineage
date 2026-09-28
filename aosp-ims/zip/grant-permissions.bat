@echo off
rem Grant the AOSP IMS stack's runtime permissions over adb, and the IPsec
rem tunnel app-op VoWiFi needs.
rem
rem Windows equivalent of grant-permissions.sh. Run once with USB debugging
rem on and the phone connected:
rem   grant-permissions.bat [device-serial]
rem If adb is not on your PATH, add its folder first, e.g.:
rem   set PATH=%PATH%;C:\adb\platform-tools
setlocal enabledelayedexpansion
set ADB=adb
if not "%~1"=="" set ADB=adb -s %~1
set PKG=com.android.imsstack
set IWLAN=com.google.android.iwlan
set QNS=com.android.telephony.qns
set RC=0

%ADB% get-state >nul 2>&1
if errorlevel 1 (
  echo No device over adb. Enable USB debugging and accept the prompt on the phone.
  exit /b 1
)

%ADB% shell pm path %PKG% 2>nul | findstr /b "package:" >nul
if errorlevel 1 (
  echo %PKG% is not installed on this device. Flash the AOSP IMS zip first.
  exit /b 1
)

rem Foreground location before background: Android refuses the reverse.
for %%p in (RECORD_AUDIO READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION) do (
  %ADB% shell pm grant %PKG% android.permission.%%p 2>nul | findstr /r ".*" >nul
  if errorlevel 1 (
    echo granted  %%p
  ) else (
    echo FAILED   %%p
    set RC=1
  )
)

rem VoWiFi. IWLAN builds the IPsec tunnel to the carrier's ePDG. Installed
rem without the ROM's platform key it needs the MANAGE_IPSEC_TUNNELS
rem app-op instead, which only adb can grant.
%ADB% shell pm path %IWLAN% 2>nul | findstr /b "package:" >nul
if not errorlevel 1 (
  %ADB% shell appops set %IWLAN% MANAGE_IPSEC_TUNNELS allow 2>nul
  if not errorlevel 1 (
    echo granted  %IWLAN% app-op MANAGE_IPSEC_TUNNELS
  ) else (
    echo FAILED   %IWLAN% app-op MANAGE_IPSEC_TUNNELS
    set RC=1
  )
  for %%p in (READ_PHONE_STATE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION) do (
    %ADB% shell pm grant %IWLAN% android.permission.%%p 2>nul
    if errorlevel 1 (echo FAILED   %IWLAN% %%p) else (echo granted  %IWLAN% %%p)
  )
  %ADB% shell pm grant %QNS% android.permission.READ_PHONE_STATE 2>nul
  if not errorlevel 1 (echo granted  %QNS% READ_PHONE_STATE) else (echo FAILED   %QNS% READ_PHONE_STATE)
)

echo.
echo As the package manager now reports them:
%ADB% shell dumpsys package %PKG% 2>nul | findstr /r "android.permission.RECORD_AUDIO android.permission.CAMERA android.permission.READ_PHONE_STATE android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.ACCESS_BACKGROUND_LOCATION"
echo.
echo IWLAN app-op (VoWiFi):
%ADB% shell appops get %IWLAN% MANAGE_IPSEC_TUNNELS 2>nul
echo.
echo RECORD_AUDIO matters for calls: ImsMedia records in this package, and
echo without it a call can't open the microphone. CAMERA is for video calls.
echo Location adds the serving cell to P-Access-Network-Info and is needed
echo for emergency calls. Reboot now, so the stack starts with all of them.
exit /b %RC%
