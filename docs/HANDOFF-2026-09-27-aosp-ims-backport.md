# Handoff — 2026-09-27 — AOSP 17 IMS stack on LineageOS 22.2: zips, ROM, source kit (alpha, untested)

Branch: `claude/aosp-ims-a15-backport`, cut from `claude/serene-bardeen-qj0tc6`.
The joan stack and its zips are untouched on both branches. They stay the
fallback until the AOSP stack registers on a real phone.

## Direction

The user asked to "swap everything for Qualcomm and upstream IMS, like
the V60 did, but backport what makes 17's work", then for a full backport
including VoWiFi, a LineageOS 22.2 build with it, flashable zips (fresh
and migrate-from-joan), and upstreaming instructions with the permissions.

- **Qualcomm IMS: impossible on the V30.** The modem was built without
  Qualcomm's IMS core, and modem images are LG-signed
  (`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`).
- **Upstream IMS: AOSP ImsStack and ImsMedia at `android-17.0.0_r1`,
  plus IWLAN and QNS for Wi-Fi calling, backported to Android 15 QPR2.**
  It runs on the AP and needs from the modem only the LTE bearer and SIM
  AKA, both working today with joan.
- **Context from lifehackerhansol (LineageOS joan maintainer).** The V60's
  Android 17 (LineageOS 24) bringup exists but is not pushed to the
  official repos. He suggested forward-porting joan to 24 instead, as a
  low-priority "maybe". The device-side pieces here (the joan-common
  patch) carry over to that; ImsStack needs no backport on 24.
  - **Open question for him:** does the V60 24 build run AOSP's
    `com.android.imsstack` or Qualcomm's `ims.apk`, and what device-side
    config did it need? (The public 22.2/23.2 V60 trees use Qualcomm IMS.)

## Done

Everything is in `aosp-ims/` (see its README) and `upstream/`.

| Deliverable | Where | Checked by |
|---|---|---|
| Backport patches: ImsStack ×3, ImsMedia, Iwlan, QNS ×1 | `aosp-ims/patches/` | compile + link against the ROM's own framework and libraries |
| Single-APK ImsStack (ImsMedia folded in), overlays, Iwlan, QNS | `tools/build-apk.sh`, `build-wfc.sh` | `check-privapp.py` against the ROM's framework-res |
| Carrier gate: VoLTE for all; Wi-Fi calling for LG's 103 VoWiFi profiles; ePDG for TMO/Metro, ATT, VZW | `zip/java/.../CarrierImsGate.java` | `tests/check-carrier-config.py` |
| Flashable zips: fresh, migrate-from-joan, uninstall (joan installer v8, alpha67's) | `tools/pack-zip.sh`, `make-installer.py` | `tests/run-e2e-install.sh`: 164 checks |
| Unofficial ROM: 2026-09-20 nightly + the stack, `UNOFFICIAL-AOSPIMS-alpha1` | `tools/repack-rom.sh` | `tests/check-rom.sh` |
| Source-build kit: local manifest, `apply-patches.sh`, joan-common patch, carrier `vendor.xml` generator | `upstream/AOSP-IMS.md`, `upstream/aosp-ims/` | patches apply; trees identical to the zip's sources; device patch applies to lineage-22.2 |
| CI build + release | `.github/workflows/aosp-ims.yml`, `aosp-ims/RELEASE` | — |

Decisions worth knowing:

- **Source builds use the tree's own IWLAN and QNS.** Android 15 has both
  (`packages/services/Iwlan` as a LineageOS fork,
  `packages/modules/Telephony/services/QualifiedNetworksService`); joan
  just never built them. Only ImsStack (new) and ImsMedia (ImsStack links
  Android 17's `ImsMediaFramework` and `libimsmedia_config`) come from
  Android 17. The zip ships Android 17 IWLAN and QNS because the ROM has
  neither.
- **Carrier config for source builds is static `vendor.xml`**, generated
  from the same maps as the zip's run-time gate, not a gate app.
  `check-carrier-config.py` simulates both over every carrier id,
  specific carrier id and PLMN in Android's carrier database (2866 SIM
  identities) and requires the same VoLTE, Wi-Fi calling and ePDG
  outcome. It found one real gap on the way: MetroPCS got Wi-Fi calling
  but not T-Mobile's non-default ePDG. Fixed in the gate.
- **The ROM is a repack, not a source build.** A LineageOS tree does not
  fit here. The official block OTA is unpacked, the zip's files are added
  with `system_file` labels, the version is marked UNOFFICIAL, and the
  images are written whole at exactly the partition sizes the OTA's
  dynamic-partition ops declare. It keeps the zip's bookkeeping files
  (`android.hardware.telephony.ims.xml.joan-added`,
  `apns-conf.xml.joan-orig` / `.joan-merged`), so the uninstall zip fully
  restores it. Unsigned: recovery warns and asks.
- **No signing key is committed.** Each work dir (and each CI run)
  generates its own.
- **Versions** are distinct from joan's and LineageOS's: app
  `17.0.0_r1-a15-alpha1`, zips `aosp-ims-17.0.0_r1-a15-alpha1-*`, ROM
  `22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan`.

## Next

1. **The release.** Pushing `aosp-ims/RELEASE` runs the `aosp-ims`
   workflow; it builds from scratch on a runner and publishes the
   prerelease `aosp-ims-17.0.0_r1-a15-alpha1` if all checks pass. If it
   did not run or failed, see the Actions tab; the scripts reproduce the
   build anywhere with the SDK, NDK r29 and root for the loop mounts.
2. **First on a phone** (ROM or `-fresh` zip, then the adb step):
   - `adb shell dumpsys package com.android.imsstack`: permissions
     granted, hidden API policy, both processes.
   - `adb logcat -b all | grep -iE 'imsstack|ImsResolver|imsmedia|iwlan|qns'`.
   - `adb shell dumpsys telephony.registry`, `adb shell dumpsys ims`:
     registration.
   - A call each way: audio both directions (patch 0003 decides the
     audio mode).
   - Wi-Fi calling on T-Mobile: tunnel up (`dumpsys` of IWLAN), QNS
     moving IMS to IWLAN.
3. **A source build** of `upstream/AOSP-IMS.md` in a real LineageOS 22.2
   tree: expect small `Android.bp` fixes; SELinux denials on first boot
   (no policy was written: ImsStack is `platform_app`, ImsMedia `radio`).
4. Then: submit the joan-common patch to LineageOS Gerrit, and ask whether
   they would carry ImsStack/ImsMedia from Android 17 in 22.2 or only in
   24.

## Environment notes

- **Blobless sparse clones** (`git clone --filter=blob:none --depth 1 -b
  <branch>` + sparse checkout) for single AOSP directories. gitiles
  `+archive` and `?format=TEXT` return 503 under load.
- **LineageOS's download API**
  (`https://download.lineageos.org/api/v2/devices/joan/builds`) gives the
  real nightly; the SDK's `android-35` system image is Android 15's first
  release and misses QPR APIs.
- **The OTA is block-based:** `*.new.dat.br` + `transfer.list` → brotli +
  `tools/sdat2img.py`, then extend the image to its superblock size.
  `debugfs` reads images without root.
- **dex2jar** is on Maven Central (`de.femtopedia.dex2jar`, 2.4.38).
- **`run-e2e-install.sh` mounts a tmpfs on `/tmp`** inside its namespace:
  keep `JOAN_E2E_WORK` off `/tmp` (the default, `aosp-ims/work/`, is fine).
