# Handoff — 2026-09-27 — AOSP 17 IMS stack backported to LineageOS 22.2 (builds; not yet packaged)

Branch: `claude/aosp-ims-a15-backport`, cut from `claude/serene-bardeen-qj0tc6`.
The joan stack and its zips are untouched on both branches. They stay the
fallback until the AOSP stack registers on a real phone.

## Direction

The user asked to "swap everything for Qualcomm and upstream IMS, like
the V60 did, but backport what makes 17's work". What that turned into:

- **Qualcomm IMS: impossible on the V30.** The modem was built without
  Qualcomm's IMS core, and modem images are LG-signed
  (`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`).
- **Upstream IMS: AOSP `packages/modules/ImsStack` and `ImsMedia` at
  `android-17.0.0_r1`, backported to Android 15 QPR2.** This is what this
  branch does. It runs on the AP and needs from the modem only the LTE
  bearer and SIM AKA, both working today with joan.
- **Context from lifehackerhansol (LineageOS joan maintainer).** The V60's
  Android 17 (LineageOS 24) bringup exists but is not pushed to the
  official repos, which is why only 22.2/23.x V60 trees (Qualcomm blobs)
  were visible. He suggested forward-porting joan to 24 instead, as a
  low-priority "maybe". The device-side pieces worked out here carry over
  to that.
  - **Open question for him:** does the V60 24 build run AOSP's
    `com.android.imsstack` or Qualcomm's `ims.apk`, and what device-side
    config did it need?

## Done (all in `aosp-ims/`, see its README)

### 1. Java compiles against LineageOS 22.2's real framework

The classpath is the 2026-09-20 joan nightly's framework jars, dex2jar'd.
The error count shows how much of Android 17's IMS is already present in
Android 15:

| Compiled against | Errors |
|---|---|
| Android 15 system stubs | 903 |
| Android 15 module-lib stubs | 494 |
| First Android 15 emulator image | 22 |
| LineageOS 22.2 framework | 14 |

Most of those were hidden, flagged or QPR additions that exist on the
phone. The 14 were in five places. Four are Android 16/17 APIs, fixed in
`patches/ImsStack/0001`. The fifth was the debug menu's androidx.appcompat
dependency, dropped in `0002`.

ImsMediaFramework compiled unchanged, and so did the ImsMedia service
(against ImsStack's classes).

### 2. Native libraries build and link against the ROM's own libraries

- `libimsstack.so`: 746 C++ files, 7.8 MB. NEEDED: binder, cutils, log,
  nativehelper, utils, xml2, z, crypto, ssl, mediautils, c++.
- `libimsmedia.so`: 670 KB. NEEDED adds aaudio, android,
  android_runtime, camera2ndk, jnigraphics, mediandk, nativewindow.

How:
- `tools/bp2ninja.py` builds the upstream Android.bp modules.
- Soong's quirks had to be replicated to get this to compile:
  - Soong's `commonGlobalIncludes`: `system/core/include` and
    `frameworks/native/include` are how these modules reach `binder/`
    and `utils/` without declaring them.
  - libc++ headers from `clang-r536225`, the release Android 15 QPR2 used.
    `external/libcxx` in that branch is stale.
  - `libnativehelper` linked as the NDK library, for `AFileDescriptor_*`.
  - `libandroid_runtime` re-exporting nativehelper headers.

### 3. Runtime preconditions, checked in Android 15 source only

- **Hidden API.** A system app plus `android:usesNonSdkApi="true"` →
  `isAllowedToUseHiddenApis()`. The attribute is public (`0x0101058e`), so
  aapt2 accepts it.
- **Linking.** A bundled system app gets the shared linker namespace
  → it can link `/system/lib64` libraries.

## Next: packaging (task "Package AOSP IMS as a flashable zip")

1. **One APK for the zip.** Upstream ImsMedia runs as
   `sharedUserId="android.uid.phone"`, which needs the ROM's platform key.
   - Fold the ImsMedia service into the `com.android.imsstack` APK: same
     UID, `android:process="com.android.telephony.imsmedia"` on the
     service.
   - `ImsMediaManager.MEDIA_SERVICE_PACKAGE` is hard-coded
     (`framework/src/android/telephony/imsmedia/ImsMediaManager.java:45`).
     Patch it to bind within the caller's own package when that package
     declares the service.
   - Resolve the Application class clash:
     `com.android.telephony.imsmedia.ImsMediaApplication` against
     ImsStack's own application class. Check what each `onCreate` does
     before choosing; the media process must not start the IMS stack.
2. **Manifest.**
   - Add `android:usesNonSdkApi="true"`.
   - Merge the permissions of both apps.
   - Keep `USE_IMSMEDIA` (it becomes self-granted).
   - Drop the test activities if the debug menu is not wanted.
3. **Privileged and signature permissions.**
   - Upstream `privapp-permissions_com.android.imsstack.xml` covers the
     privileged set.
   - Signature-only permissions will not be granted to a zip-signed app:
     `ACCESS_SURFACE_FLINGER`, `INTERACT_ACROSS_USERS_FULL`. Grep their
     uses and guard or drop them.
   - `RECORD_AUDIO` and location need default-permissions, as joan does
     (`permissions/default-permissions-org.joan.ims.xml`).
4. **Selecting the ImsService.**
   - Framework overlay: `config_ims_mmtel_package = com.android.imsstack`,
     `config_device_volte_available`. Same mechanism as joan's overlay, so
     reuse the installer's overlay step.
   - Carrier config `KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING` is an
     alternative.
5. **Call audio routing: needs checking.**
   - joan found that Telecom must put an AP-media IMS call in
     `MODE_IN_COMMUNICATION`, not `MODE_IN_CALL`, on this audio HAL. See
     `JoanCallSession.java:21,159` and `JoanMmTelFeature.java:366`.
   - ImsMedia plays and records through AAudio `VOICE_COMMUNICATION`
     (`core/audio/android/ImsMediaAudioSource.cpp:351`).
   - Find how ImsStack reports its calls to Telecom and whether the same
     treatment is needed. It likely needs a patch, since upstream assumes
     the device's IN_CALL path works for AP media.
6. **Native libraries in the APK.** Store them uncompressed and
   page-aligned, and also install them to `<app dir>/lib/arm64/`, the
   system-app layout.
7. **Carrier settings.**
   - ImsStack reads CarrierConfigManager plus
     `assets/carrier_config/carrier_config.xml`.
   - Map joan's 164 LG carrier profiles (`ims-service/assets/`) onto
     ImsStack's keys to keep joan's carrier coverage.

### First on-device checks, once a zip exists

- `dumpsys package com.android.imsstack` (permissions, hidden API policy).
- `logcat -b all | grep -iE 'imsstack|ImsResolver|imsmedia'`.
- `dumpsys telephony.registry` and `dumpsys ims` for registration state.

## VoWiFi (asked 2026-09-27): realistic, after VoLTE

- **Present:**
  - The kernel is 4.4.302 with `CONFIG_NET_IPVTI`, `CONFIG_IPV6_VTI`,
    `CONFIG_INET(6)_ESP` and tunnel modes (read from the OTA's boot.img
    ikconfig).
  - `/vendor/etc/permissions/android.software.ipsec_tunnels.xml`.
  - The `com.android.ipsec` (IKE) module.
  - ImsStack implements Wi-Fi calling and handover.
- **Missing:**
  - AOSP's IWLAN ePDG service (`packages/services/Iwlan`). The ROM points
    at Qualcomm's `vendor.qti.iwlan`, which never starts. It is Java-only,
    so it can be added the same way.
  - Per-carrier Wi-Fi calling config.
  - US carriers additionally need an E911 address on the account.

## Upstream route (V60-style)

In a LineageOS 22.2 tree:
- Add `packages/modules/ImsStack` and `ImsMedia` at `android-17.0.0_r1`
  via a local manifest, and apply `aosp-ims/patches`.
- `PRODUCT_PACKAGES += ImsStack ImsMediaService`.
- Overlay `config_ims_mmtel_package`.

Signed with the platform key there, so the zip-only workarounds above
(merged APK, usesNonSdkApi) are unnecessary. Not yet written.

## Environment notes

- **Blobless sparse clones.** Use
  `git clone --filter=blob:none --depth 1 -b <branch>` with a sparse
  checkout for single AOSP directories. gitiles `+archive` of
  frameworks/base subdirectories returns 503 every time.
- **Device images.** `sdkmanager "system-images;android-35;default;arm64-v8a"`
  is Android 15's first release (AE3A) and is missing QPR APIs.
  LineageOS's download API
  (`https://download.lineageos.org/api/v2/devices/joan/builds`) gives the
  real nightly.
- **Unpacking the OTA.** It is block-based: `*.new.dat.br` +
  `transfer.list` → brotli + `tools/sdat2img.py`. The ext4 image must
  then be extended to its superblock size.
- **dex2jar.** It is on Maven Central (`de.femtopedia.dex2jar`, 2.4.38).
