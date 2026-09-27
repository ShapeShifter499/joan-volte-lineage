# AOSP IMS for joan on LineageOS 22.2 (Android 17's IMS stack, backported)

VoLTE, SMS over IMS and Wi-Fi calling for the LG V30 (`joan`) on
LineageOS 22.2, using AOSP's own IMS stack from `android-17.0.0_r1`
backported to Android 15 QPR2. It replaces joan's own `ImsService`.

| Part | Package | Does |
|---|---|---|
| ImsStack | `com.android.imsstack` | IMS registration, SIP, IPsec, calls, SMS over IMS |
| ImsMedia | `com.android.telephony.imsmedia` | RTP, jitter buffer, codecs, call audio |
| IWLAN | `com.google.android.iwlan` | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS | `com.android.telephony.qns` | Moves IMS between LTE and Wi-Fi |

Qualcomm's IMS is not an option on the V30: its modem was built without
Qualcomm's IMS core (`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`).
AOSP's stack runs on the application processor and needs the modem only
for the LTE bearer and SIM authentication, which the V30 has.

## What it carries, per feature

| Feature | State here |
|---|---|
| VoLTE | Every carrier. Toggle visible and editable everywhere; on by default, the toggle is the opt-out. |
| SMS over IMS | In: part of the MMTEL feature ImsStack registers, riding the same registration and the same Wi-Fi tunnel as voice. |
| Wi-Fi calling | Every carrier gets the toggle. Per-carrier IMS data comes from Google's Pixel carrier settings (below). US carriers need an E911 address on the account. |
| Supplementary services | Call waiting, conferencing, Ut/XCAP where the carrier data carries it (`ImsUtImpl`). |
| Emergency | IMS emergency registration is in the stack. One backport seam: Android 16's domain-selection emergency-mode callback does not exist on Android 15, so that state is not reported. Verify on the bench before relying on it. |
| Video (ViLTE) | Advertised capability comes with the stack, but the media path was never wired or tested on joan, the carrier-data import drops the VT keys, and the zip path lacks the platform-signature surface/camera permissions. Not enabled; a follow-up, not an architectural blocker. |
| RCS | Out by design. RCS is a client (Google Messages/Jibe; AOSP ships none) plus a separate `RcsFeature`; this stack declares none, so nothing binds and nothing half-works. GApps + Google Messages is the only real route and is independent of this stack. |
| RTT | Not enabled; same category as video. |

> **Alpha. Nothing here has run on a phone yet.** Everything is built from
> source and the installers pass their end-to-end tests, but no call has
> been made with it. Keep a way back: the `-uninstall` zip, or the
> official nightly.

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

It runs `pm grant` for ImsStack's microphone, phone and location
permissions, IWLAN's phone and location, and QNS's phone state; and
`appops set com.google.android.iwlan MANAGE_IPSEC_TUNNELS allow`, which
Wi-Fi calling needs to build its tunnel. A source build needs none of
this.

## Carrier data

LineageOS ships no IMS carrier config for most carriers. The stack
ships it in three layers, merged by CarrierConfig in document order
(later wins):

1. **The ROM's own CAF `vendor.xml`** (`aosp-ims/carrier/joan-common-vendor-base.xml`),
   untouched — an overlay replacing the resource wholesale must carry
   what it replaces.
2. **The converted Pixel carrier data** (`aosp-ims/carrier/lineage-pixel-ims.xml`):
   LineageOS converts Google's Pixel CarrierSettings protobufs with
   `carriersettings-extractor` for Pixel devices; the same conversion,
   pre-run and filtered to the IMS namespaces (`ims.`, `imsvoice.`,
   `imssms.`, `imsss.`, `imswfc.`, `imsemergency.`, `iwlan.`, `qns.`,
   `bsf.`), gives 1353 carrier entries over 573 named carriers — VoLTE
   for 1201, Wi-Fi calling for 936, a static ePDG for 587 — with SIP
   timers, codec profiles, Ut/XCAP, and LTE/Wi-Fi handover policy per
   carrier. Its ePDG addresses are authoritative.
3. **Our rules, last**: VoLTE and Wi-Fi calling available for every SIM,
   both toggles usable, IMS user-turnoff-able
   (`carrier_allow_turnoff_ims_bool=true`).

The zip and the ROM apply this through **`ImsStackCarrierConfigOverlay`**
(target `com.android.carrierconfig`, priority above the nightly's
auto-generated CarrierConfig overlay, which the device tree's
`PRODUCT_ENFORCE_RRO_TARGETS` produces in `vendor/overlay`). A source
build splices the same blocks into the device-tree `vendor.xml`
instead. The run-time gate
(`zip/java/com/android/imsstack/joan/CarrierImsGate.java`) only fills
what the config is missing: VoLTE and Wi-Fi calling where absent, and
the ePDG address for AT&T and Verizon where the overlay did not match
the SIM (T-Mobile's ePDG is the 3GPP default name and needs nothing).

`tests/check-carrier-config.py` checks gate and config agree for every
carrier id, specific carrier id and PLMN in Android's carrier database
(2866 SIM identities). `tools/make-carrier-config.py` generates the
merged region; `tools/import-carrier-settings.py` re-runs the Pixel
conversion to regenerate the data file.

## Devices

The ROM keeps LineageOS's own install assert
(`v30, joan, h930, h932`), so it installs wherever the official nightly
does (LineageOS recovery reports `joan`). The official wiki supports:
H930, H930DS, US998; H932; H931, H933, LS998, V300L, V300K, V300S,
VS996 — one unified `lineage_joan` build, variant picked at runtime
from `ro.boot.vendor.lge.model.name`. The zips check no model. Japanese
variants (L-01K, LGV35) are handled by the device tree but are not on
the official wiki.

## Status (2026-09-27)

| Step | State |
|---|---|
| ImsStack + ImsMedia Java, against LineageOS 22.2's own framework | done: 3 ImsStack patches, 1 ImsMedia |
| `libimsstack.so`, `libimsmedia.so`, linked against the ROM's libraries | done |
| IWLAN + QNS (Android 17) for the zip | done: 1 patch each |
| Single-APK packaging, overlays (phone, framework, CarrierConfig), permission files | done |
| Carrier data (converted Pixel data + rules, all carriers) | done; 2866/2866 SIM identities checked |
| Flashable zips (fresh, migrate, uninstall) | done; 164 end-to-end installer checks pass |
| Repacked LineageOS 22.2 ROM | done; `tests/check-rom.sh` passes |
| Source-tree integration (`upstream/AOSP-IMS.md`) | written and checked piece by piece; not yet built in a tree |
| **Tested on a phone** | **never** |

## What the backport changes

Every patch is in `patches/<project>/`, applied on top of the commit
pinned in `upstream.lock`.

| Patch | What |
|---|---|
| ImsStack 0001 | The four Android 16/17 APIs ImsStack uses, on Android 15: `requestUiccIari` (no IARIs, RCS only), `BarringInfo#getCellIdentity` (read back from the parcel), `EXTRA_SETUP_EVENT_LIST` (local constant), `DomainSelectionEmergencyModeListener` (not registered) |
| ImsStack 0002 | Debug menus without androidx.appcompat |
| ImsStack 0003 | Hands call audio to Android (`AUDIO_HANDLER_ANDROID`), so Telecom puts the call in `MODE_IN_COMMUNICATION`, the mode ImsMedia's audio path needs on this HAL |
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
- **Selecting the service.** Three overlays: `zip/rro-phone` (ImsStack as
  `config_ims_mmtel_package`), `zip/rro-fw` (VoLTE and Wi-Fi calling
  available on the device; IWLAN and QNS as the WLAN data, network and
  qualified-networks services), and `zip/rro-carrierconfig` (the carrier
  data above, generated at build time).
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
