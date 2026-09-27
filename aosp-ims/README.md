# AOSP IMS for joan on LineageOS 22.2 (backport of Android 17's IMS stack)

This directory replaces joan's own IMS implementation with the upstream
one: AOSP's **ImsStack** (SIP/IMS signalling, `com.android.imsstack`) and
**ImsMedia** (RTP, jitter buffer, codecs, `com.android.telephony.imsmedia`)
from `android-17.0.0_r1`, backported to Android 15 QPR2, the base of
LineageOS 22.2.

Qualcomm's IMS is not an option on the V30; see
`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`. AOSP's stack runs on the
application processor and needs the modem only for the LTE bearer and SIM
authentication, both of which the V30 has.

## Status (2026-09-27)

| Step | State |
|---|---|
| Java: ImsStack + ImsMediaFramework compile against LineageOS 22.2's framework | done, 2 patches |
| Java: ImsMedia service compiles against LineageOS 22.2 | done, no changes |
| Native: `libimsstack.so` (746 files) links against the ROM's libraries | done |
| Native: `libimsmedia.so` links against the ROM's libraries | done |
| APK packaging for a flashable zip | **next** |
| Flashable zip, framework overlay, permissions | not started |
| LineageOS device-tree integration (upstream route) | not started |
| Tested on a phone | **never**: nothing here has run on hardware yet |

## What the backport changes

`patches/ImsStack/`, applied on top of the pinned upstream commit:

1. **Android 15 APIs** (`0001`). Only four telephony APIs ImsStack uses
   are newer than Android 15 QPR2. Everything else is already in the ROM's
   framework, hidden or flagged.
   - `TelephonyManager#requestUiccIari` → no IARIs (RCS only).
   - `BarringInfo#getCellIdentity` → read back from the parcel.
   - `TelephonyManager#EXTRA_SETUP_EVENT_LIST` → local constant.
   - `TelephonyCallback.DomainSelectionEmergencyModeListener` → not
     registered.
2. **Debug menus without androidx.appcompat** (`0002`): platform
   ActionBar and SearchView.

ImsMedia needs no source change to compile.

## How it builds

No Android tree is needed (a LineageOS checkout does not fit in 30 GB).
`tools/bp2ninja.py` reads the upstream `Android.bp` files and generates a
ninja build that does what Soong would:

- **Headers:** Android 15 QPR2 platform headers (sparse checkouts).
- **C++ library:** the platform libc++ headers of `clang-r536225`, the
  clang release Soong used for that branch (`std::__1`, not the NDK's
  `__ndk1`).
- **Compiler:** the NDK's clang.
- **Linking:** against the ROM's own `/system/lib64` libraries
  (`libbinder`, `libutils`, `libc++` ...). The result has exactly the ABI
  of the ROM it will run on.

The Java side compiles against the ROM's real `framework.jar`,
`telephony-common.jar` and `ims-common.jar`. They are converted with
dex2jar from the pinned LineageOS 22.2 nightly, so javac proves that every
framework member referenced exists on the phone.

```sh
aosp-ims/tools/setup-workdir.sh   # sources + patches, headers, ROM, deps
aosp-ims/tools/build-java.sh      # -> aosp-ims/work/out/java
aosp-ims/tools/build-native.sh    # -> aosp-ims/work/out/native
```

All inputs are pinned in `upstream.lock`. `work/` is git-ignored and
about 6 GB (the OTA and its system image are most of it).

## Why a zip-installed build can work without the platform key

Both points below are read from Android 15 source. Neither has been
checked on a device yet.

- **Hidden APIs:** `ApplicationInfo.isAllowedToUseHiddenApis()` exempts a
  system app whose manifest sets `android:usesNonSdkApi="true"`.
- **Platform libraries:** bundled system apps get a shared linker
  namespace (`LoadedApk`, `isBundledApp`), so the JNI libraries may link
  `libbinder.so` and friends from `/system/lib64`.

Upstream ImsMedia runs as `android.uid.phone`, which only the ROM's
platform key can claim. For the zip, the plan is to fold the ImsMedia
service into the ImsStack APK: same app and UID, and its own process as
upstream. See `docs/HANDOFF-2026-09-27-aosp-ims-backport.md`.
