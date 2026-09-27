# AOSP IMS for joan on LineageOS 22.2 (Android 17's IMS stack, backported)

VoLTE and Wi-Fi calling for the LG V30 (`joan`) on LineageOS 22.2, using
AOSP's own IMS stack from `android-17.0.0_r1` backported to Android 15
QPR2. It replaces joan's own `ImsService`.

| Part | Package | Does |
|---|---|---|
| ImsStack | `com.android.imsstack` | IMS registration, SIP, IPsec, calls, SMS |
| ImsMedia | `com.android.telephony.imsmedia` | RTP, jitter buffer, codecs, call audio |
| IWLAN | `com.google.android.iwlan` | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS | `com.android.telephony.qns` | Moves IMS between LTE and Wi-Fi |

Qualcomm's IMS is not an option on the V30: its modem was built without
Qualcomm's IMS core (`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`).
AOSP's stack runs on the application processor and needs the modem only
for the LTE bearer and SIM authentication, which the V30 has.

> **Alpha.** Bench results so far (US998, T-Mobile): the migrate zip
> installs and ImsStack registers with IPsec, once patch 0004 stopped a
> startup crash. The first calls then failed before any INVITE left the
> phone: the SIM's call control answer never reached ImsStack (joan's RIL
> returns status words 00 00), and 0005 now sets such a call up as
> dialled. No call has completed yet. Keep a way back: the `-uninstall`
> zip, or the official nightly.

What the stack does, all of it upstream AOSP code: VoLTE (voice over
LTE, HD voice codecs), Wi-Fi calling (VoWiFi over IWLAN, with QNS moving
calls between LTE and Wi-Fi), SMS over IMS, video calling (ViLTE, with
the camera), RTT (real-time text), emergency calls over IMS, supplementary
services over Ut/XCAP (call forwarding, waiting, barring), conference
calls, USAT call control and MO SMS control by the SIM, and RCS presence
(UCE). Each works where the carrier offers it; see below for how the
carrier's settings are chosen.

## Three ways to get it

| | For | What |
|---|---|---|
| **ROM** | Anyone flashing a ROM anyway | `lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip`: the official 2026-09-20 nightly with the stack built in |
| **Flashable zips** | A phone already on LineageOS 22.2 or a ROM based on it | `aosp-ims-17.0.0_r1-a15-alpha1-{fresh,migrate-from-joan,uninstall}.zip` |
| **Source build** | ROM builders, and LineageOS itself | [`upstream/AOSP-IMS.md`](../upstream/AOSP-IMS.md): a local manifest, the patches, and a `device/lge/joan-common` patch |

The ROM and zips are on the
[`aosp-ims-17.0.0_r1-a15-alpha1`](https://github.com/ShapeShifter499/joan-volte-lineage/releases/tag/aosp-ims-17.0.0_r1-a15-alpha1)
prerelease, built from this directory by `.github/workflows/aosp-ims.yml`.
`aosp-ims/RELEASE-NOTES.md` is its description: which file to flash,
the adb step, known limits, and what logs to send.

### Which zip

Flash with **LineageOS recovery** (it maps the dynamic partitions; TWRP
on this device generally does not).

- **`-fresh`**: a phone that never had joan's IMS zip. It refuses a phone
  that has joan, and changes nothing.
- **`-migrate-from-joan`**: a phone running joan's IMS (alpha67 or any
  earlier one). It removes joan's app, overlays, permission files and
  stamps, then installs this stack.
- **`-uninstall`**: removes everything either zip, or the ROM above,
  added. The ROM's own files are left as the nightly shipped them.

These use joan's installer v8, the one that flashes across
LineageOS-based ROMs (alpha67): it checks free space before writing,
copies atomically, writes the permission allowlists before the APKs, and
invalidates the package manager's cache so the next boot rescans.

### The adb step (zips and repacked ROM)

The apps are not signed with the ROM's platform key (only LineageOS has
it), so some permissions can only be granted over adb until a build
carries the stack. With USB debugging on, once, after the first boot:

```
sh grant-permissions.sh        # in the zip, and at aosp-ims/zip/grant-permissions.sh
```

It runs `pm grant` for ImsStack's microphone, camera (video calls),
phone and location permissions, IWLAN's phone and location, and QNS's
phone state; and `appops set com.google.android.iwlan MANAGE_IPSEC_TUNNELS
allow`, which Wi-Fi calling needs to build its tunnel. Reboot afterwards.
A source build needs none of this.

## What it does per carrier

LineageOS ships IMS carrier config for Pixels, not for joan. This stack
brings it along, for every SIM:

- **VoLTE**: offered for every carrier, with the toggle visible, editable
  and able to turn IMS off (Settings > Network & internet > SIMs > VoLTE).
- **Wi-Fi calling**: offered for every carrier too. It works where the
  carrier's ePDG accepts the SIM; elsewhere the tunnel is not built and
  IMS stays on LTE.
- **Per-carrier IMS settings** from LineageOS itself: the IMS part of the
  Pixel carrier settings LineageOS converts for its Pixel builds
  (`lineage/scripts/carriersettings-extractor`), 1361 blocks for 576
  carriers: SIP and registration timers, SMS over IMS, Ut/XCAP, emergency,
  video and RTT, ePDG and QNS settings. Imported by
  `tools/import-carrier-settings.py` into `carrier/lineage-pixel-ims.xml`
  (sources pinned in `upstream.lock`), filtered to the keys the AOSP stack
  reads; never the keys that would pick another ImsService, require
  provisioning, lock the VoLTE toggle or turn Wi-Fi calling on by default.
- **ePDG**: the carrier's own address from that data where it has one;
  for T-Mobile, MetroPCS, AT&T and Verizon otherwise, whose ePDGs are not
  at the 3GPP default name, joan's table. Everyone else uses the default
  name derived from the SIM.

Where it lives: the zip and the ROM apply it at run time, on top of
whatever carrier config the ROM ships
(`zip/java/com/android/imsstack/joan/CarrierImsGate.java`, ported from
joan's gate, with the imported data as one asset per PLMN), so a
LineageOS-based ROM keeps its own. A source build carries the same as
CarrierConfig `vendor.xml` blocks (`tools/make-carrier-config.py`,
spliced in by `upstream/aosp-ims/apply-patches.sh`).
`tests/check-carrier-config.py` checks the result for all 2866 SIM
identities Android's carrier database knows.

## Status (2026-09-27)

| Step | State |
|---|---|
| ImsStack + ImsMedia Java, against LineageOS 22.2's own framework | done: 6 ImsStack patches, 1 ImsMedia |
| `libimsstack.so`, `libimsmedia.so`, linked against the ROM's libraries | done |
| IWLAN + QNS (Android 17) for the zip | done: 1 patch each |
| Single-APK packaging, overlays, permission files | done |
| Flashable zips (fresh, migrate, uninstall) | done; 164 end-to-end installer checks pass |
| Repacked LineageOS 22.2 ROM | done; `tests/check-rom.sh` passes |
| Source-tree integration (`upstream/AOSP-IMS.md`) | written and checked piece by piece; not yet built in a tree |
| **Tested on a phone** | US998 on T-Mobile: the migrate zip installs and ImsStack registers (IPsec sec-agree, reg-event) once 0004 stops the startup crash. Calls failed on USAT call control until 0005; not yet re-tested |

## What the backport changes

Every patch is in `patches/<project>/`, applied on top of the commit
pinned in `upstream.lock`.

| Patch | What |
|---|---|
| ImsStack 0001 | The four Android 16/17 APIs ImsStack uses, on Android 15: `requestUiccIari` (no IARIs, RCS only), `BarringInfo#getCellIdentity` (read back from the parcel), `EXTRA_SETUP_EVENT_LIST` (local constant), `DomainSelectionEmergencyModeListener` (not registered) |
| ImsStack 0002 | Debug menus without androidx.appcompat |
| ImsStack 0003 | Hands call audio to Android (`AUDIO_HANDLER_ANDROID`), so Telecom puts the call in `MODE_IN_COMMUNICATION`, the mode ImsMedia's audio path needs on this HAL |
| ImsStack 0004 | Emergency call tracking without `READ_ACTIVE_EMERGENCY_SESSION` (signature-only). Without the platform key the listener is refused, which crash-looped the stack on a US998; ImsStack now falls back to the call state plus `TelecomManager#isInEmergencyCall` (a privileged permission the zip holds). Platform-signed builds keep the original listener |
| ImsStack 0005 | USAT call control with no answer from the SIM. joan's RIL completes the CALL CONTROL envelope with status words 00 00 and no data, which no UICC sends; ImsStack took it as a refusal and failed every MO call on a SIM with call control by USIM (T-Mobile's). With no answer there is no verdict: the call is set up as dialled, and an SMS under MO SMS control sent as is. A real answer from the SIM (busy, an error, or result 01 "not allowed") still blocks |
| ImsStack 0006 | The location information in those envelopes coded a three-digit MNC in dialling order (310-260 as `13 20 06`); it is now coded as 3GPP TS 24.008 says (`13 00 62`) |
| ImsMedia 0001 | `ImsMediaManager` binds the ImsMedia service in its own package when that package has one (the zip's single APK) |
| Iwlan 0001 | No physical-network reporting in `DataCallResponse` (Android 16 API) |
| QNS 0001 | Wi-Fi calling activation without androidx: the activity is a plain `Activity`, and carrier portals open in the browser |

## How the zip installs without the platform key

- **One APK.** Upstream ImsMedia runs as `android.uid.phone`, which only
  the platform key can claim. The zip folds the ImsMedia service into the
  ImsStack APK instead (`tools/merge-manifest.py`): the same app and uid,
  with the service in its own process as upstream.
  `zip/java/.../ImsStackZipApp.java` keeps the media process from
  starting the IMS stack.
- **Hidden APIs.** `android:usesNonSdkApi="true"` on a system app
  (`ApplicationInfo.isAllowedToUseHiddenApis()`).
- **Platform libraries.** A bundled system app gets the shared linker
  namespace, so the JNI libraries link `libbinder` and friends from
  `/system/lib64`. They are stored page-aligned and uncompressed, and
  load straight from the APK.
- **Privileged permissions.** Allowlists in `permissions/`;
  `tools/check-privapp.py` fails the build if the APK requests a
  privileged permission the allowlist does not cover (the ROM would not
  boot).
- **Selecting the service.** Two overlays (`zip/rro-phone`,
  `zip/rro-fw`): ImsStack as `config_ims_mmtel_package`; VoLTE and Wi-Fi
  calling available on the device; IWLAN and QNS as the WLAN data,
  network and qualified-networks services.
- **What it cannot have.** `ACCESS_SURFACE_FLINGER` and
  `INTERACT_ACROSS_USERS_FULL` are signature-only: video surfaces and
  work profiles may misbehave. `MANAGE_IPSEC_TUNNELS` comes from the adb
  app-op instead.

## How it builds

No Android tree is needed (a LineageOS checkout does not fit here).

- **Native:** `tools/bp2ninja.py` reads the upstream `Android.bp` files
  and writes a ninja build that does what Soong would. Android 15 QPR2
  headers (sparse checkouts), the platform libc++ headers of
  `clang-r536225` (the clang Soong used for that branch), the NDK's
  compiler, and linking against the ROM's own `/system/lib64` libraries:
  the result has the ABI of the ROM it runs on.
- **Java:** compiled against the ROM's real `framework.jar`,
  `telephony-common.jar`, `ims-common.jar` and friends, converted with
  dex2jar from the pinned nightly. javac proves every framework member
  referenced exists on the phone.

```sh
tools/build-all.sh               # all of the below, in order
tools/setup-workdir.sh           # sources + patches, headers, ROM, deps
tools/build-java.sh              # AIDL + a compile check
tools/build-native.sh            # libimsstack.so, libimsmedia.so
tools/build-apk.sh               # ImsStack.apk + overlays (wipes out/apk)
tools/build-wfc.sh               # Iwlan.apk, QualifiedNetworksService.apk
tools/pack-zip.sh                # the three zips
sudo tests/run-e2e-install.sh    # installer end-to-end tests
sudo tools/repack-rom.sh         # the ROM
tests/check-rom.sh <rom.zip> work/out/apk
```

Inputs are pinned in `upstream.lock`. `work/` is git-ignored and takes
about 4 GB, most of it the OTA and its system image; repacking the ROM
needs about 5 GB more while it runs. `VERSION_NAME` sets the version
(`17.0.0_r1-a15-alpha1` by default); `ROM_TAG` the ROM's version suffix.
APKs are signed with a key generated in the work dir, never committed: a
published key would let anyone ship an "update" to a privileged app.

**Versions**, so this is never mistaken for joan or an official build:
the app is `17.0.0_r1-a15-alpha1`; the zips are
`aosp-ims-17.0.0_r1-a15-alpha1-*`; the ROM reports
`22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan` and release type
`UNOFFICIAL`, so the updater does not offer an official nightly over it.

### The ROM

`tools/repack-rom.sh` is not a source build. It unpacks the official
block OTA pinned in `upstream.lock`, adds the zip's files to the system
and product images (root-owned, `system_file` labels), marks the version
UNOFFICIAL, and writes a block OTA recovery flashes the same way. The
images are written whole, at exactly the partition sizes the OTA's
dynamic-partition ops declare. Only LineageOS can sign with its key, so
recovery warns that verification failed and asks before installing.

### Releasing

Edit `RELEASE` (tag, version, ROM suffix, title) and push. The
`aosp-ims` workflow builds everything from source on a clean runner, runs
the carrier-config, installer and ROM checks, and publishes a prerelease
under the tag if it does not exist yet. Any other push to `aosp-ims/`
runs the same build as a check.
