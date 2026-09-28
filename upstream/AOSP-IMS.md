# AOSP IMS in a LineageOS 22.2 build (joan)

How to build VoLTE and Wi-Fi calling into LineageOS 22.2 for the LG V30,
using AOSP's own IMS stack from Android 17. This replaces the joan
`ImsService` described in `README.md`. Use one or the other, never both:
a device has one MMTEL `ImsService`.

**Status.** Alpha.
- The flashable zip and the repacked ROM (`aosp-ims/README.md`) are built
  from the same patched sources as this and pass the installer tests. On
  a US998 with a T-Mobile SIM the zip registers with IPsec; calls failed
  on the SIM's call control until ImsStack 0005 and have not been
  re-tested yet.
- The source-tree integration below has not been built in a LineageOS
  tree: a full tree does not fit in the environment this was written in.
  It was checked piece by piece against the Android 15 sources (see
  "What was checked"), including Android 15's own Soong reading the
  build files it adds. Please report any build error on the first build.

## What goes into the tree

| Piece | Source | How it gets there |
|---|---|---|
| ImsStack (SIP, IPsec, calls, SMS) | AOSP `packages/modules/ImsStack`, `android-17.0.0_r1` | `upstream/aosp-ims/local_manifests/aosp-ims.xml`, then `upstream/aosp-ims/apply-patches.sh` |
| ImsMedia (RTP, codecs, call audio) | AOSP `packages/modules/ImsMedia`, `android-17.0.0_r1`, replacing the tree's Android 15 copy | the same |
| IWLAN (the ePDG tunnel for Wi-Fi calling) | the tree's own, `packages/services/Iwlan` | `PRODUCT_PACKAGES` |
| QNS (moves IMS between LTE and Wi-Fi) | the tree's own, `packages/modules/Telephony/services/QualifiedNetworksService` | `PRODUCT_PACKAGES` |
| Device configuration | `device/lge/joan-common` | `upstream/aosp-ims/device/0001-joan-common-Add-the-AOSP-IMS-stack.patch` |

Why Android 17: ImsStack first appeared there. Android 15 already has
ImsMedia, but Android 17's ImsStack needs Android 17's ImsMedia (it links
its `ImsMediaFramework` and native `libimsmedia_config`). IWLAN and QNS
are only services the framework binds by package name, so the tree's
Android 15 versions do. (The zip carries Android 17 IWLAN and QNS only
because the official ROM ships neither.)

The patches in `aosp-ims/patches/ImsStack` and `aosp-ims/patches/ImsMedia`
are the backport, the same ones the zip is built from:

| Patch | What it does |
|---|---|
| ImsStack 0001 | Replaces the Android 16/17 APIs ImsStack uses with Android 15 equivalents |
| ImsStack 0002 | The debug menus without androidx.appcompat |
| ImsStack 0003 | Hands call audio to Android (`AUDIO_HANDLER_ANDROID`), so Telecom uses `MODE_IN_COMMUNICATION`, the mode ImsMedia's audio path needs on this HAL |
| ImsStack 0004 | Survives a refused outgoing-emergency-call listener (the zip lacks the signature permission) by falling back to the call state and `TelecomManager#isInEmergencyCall`. A platform-signed tree build holds the permission, registers the original listener and never uses the fallback |
| ImsStack 0005 | USAT call control and MO SMS control with no answer from the SIM: joan's RIL completes the envelope with status words 00 00 and no data, which ImsStack took as a refusal, failing every call on a SIM with call control by USIM. With no answer the call is set up as dialled; a real answer from the SIM still counts. Needed in a tree build too: it is the RIL, not the signing, that drops the answer |
| ImsStack 0006 | Codes a three-digit MNC in those envelopes' location information as 3GPP TS 24.008 says |
| ImsStack 0007 | Drops EVS from the codec offer: ImsMedia has no EVS codec yet (its encoder and decoder are TODOs), so an EVS call would be silent. Remove this patch once ImsMedia gains one |
| ImsStack 0008 | Adds `ImsStackGbaService`, a GBA_ME service for platforms with none (LineageOS): Ut/XCAP authenticates with GBA, Android's default GBA mode is GBA_ME for every carrier, and without a service in `config_gba_package` every XCAP server that asks for GBA refuses. The device patch 0002 selects it |
| ImsStack 0009 | Synchronizes `MediaManagerHelper.close()`, which raced when the media service died mid-call |
| ImsStack 0010 | Logs why the framework refused an incoming call before the stack answers 480 |
| ImsMedia 0001 | Lets ImsMedia run inside the caller's own package (the zip's single APK). A separate `ImsMediaService`, as here, is bound as before |
| ImsMedia 0002 | Declares `JNIImsMediaService.setTestMode`, which the pinned ImsMedia's native JNI table registers and its Java class lacked: the media service died at library load on the first call. Needed in a tree build too |

## Steps

From the top of a LineageOS 22.2 tree that already builds joan:

1. **Sync the Android 17 projects.**

   ```
   cp <this repo>/upstream/aosp-ims/local_manifests/aosp-ims.xml .repo/local_manifests/
   repo sync packages/modules/ImsMedia packages/modules/ImsStack
   ```

2. **Apply the patches and the device change.** One script does all of
   it; each patch becomes a commit in its project, so `repo status` shows
   them. Run it again after any `repo sync` that resets them: patches
   already applied are skipped.

   ```
   <this repo>/upstream/aosp-ims/apply-patches.sh .
   ```

   It applies, in order:
   - the backport patches (above) to `packages/modules/ImsStack` and
     `packages/modules/ImsMedia`;
   - `upstream/aosp-ims/device/0001-joan-common-Add-the-AOSP-IMS-stack.patch`,
     `0002-joan-common-Use-ImsStack-s-GBA-service.patch` and
     `0003-joan-common-Turn-on-framework-incoming-call-handling.patch` to
     `device/lge/joan-common` (made against `lineage-22.2` at 47c4939,
     2025-02-11);
   - the per-carrier IMS config (below), spliced into joan-common's
     CarrierConfig `vendor.xml` as a commit of its own. It is 7 MB of
     generated XML, so it is not in the device patch;
   - the IMS, XCAP and emergency APNs LineageOS's list lacks (below), as
     `vendor/apn/aosp-ims.xml`, a commit in `vendor/apn`.
   `--no-carrier-data` leaves out the last two.

   The device patch:
   - adds `ImsStack`, `ImsMediaService`, `Iwlan` and
     `QualifiedNetworksService` to `PRODUCT_PACKAGES`, and the
     `android.hardware.telephony.ims` feature;
   - sets the framework overlay: `config_device_volte_available`,
     `config_device_wfc_ims_available` and `config_device_vt_available`
     true, and the WLAN data, WLAN network and qualified networks services
     to AOSP's IWLAN and QNS in place of `vendor.qti.iwlan` (Qualcomm's
     needs modem IMS, which this modem does not have);
   - adds a Telephony overlay: `config_ims_mmtel_package` and
     `config_ims_rcs_package` = `com.android.imsstack`, and
     `config_support_rtt` true (0002 adds `config_gba_package` =
     `com.android.imsstack`);
   - adds the carrier blocks to CarrierConfig's `vendor.xml` (below);
   - adds `system_ext/etc/default-permissions/default-permissions-ims.xml`
     (below);
   - (0003) sets `ro.telephony.block_binder_thread_on_incoming_calls=true`
     in `system.prop`. The property is LineageOS's own: its
     `ImsPhoneCallTracker` answers an incoming call's `onIncomingCall`
     with no listener when it is false, for the Qualcomm modem IMS joan's
     tree was written for, and an ImsService like ImsStack reads no
     listener as a refusal: it answers 480 and the phone never rings.

3. **Build and flash** as usual (`breakfast joan`, `brunch joan`). On a
   phone that had the flashable zip, flash the zip's `-uninstall` first
   (or wipe), so the zip's copies in `/system` do not shadow the build's.

4. **Check** after boot:

   ```
   adb shell dumpsys telephony.registry | grep -i ims
   adb shell cmd phone ims get-ims-service
   adb logcat -b all | grep -iE 'imsstack|ImsResolver|imsmedia|iwlan'
   ```

## Permissions

A tree build signs everything with the platform key and grants the
runtime permissions at first boot, so it needs none of the zip's
permission steps.

| Kind | Handled by |
|---|---|
| Privileged permissions | Each module's own allowlist, installed next to it on `system_ext` by its `Android.bp`: `privapp_permissions_com.android.imsstack`, `privapp-permlist_com.google.android.iwlan.xml`, `privapp-permissions_com.android.telephony.qns`. ImsMedia requests none. |
| Signature permissions | The platform key: `ACCESS_SURFACE_FLINGER`, `INTERACT_ACROSS_USERS_FULL` (ImsStack; requested, though nothing in the stack uses either), `MANAGE_IPSEC_TUNNELS` (IWLAN), `USE_IMSMEDIA`. These are what the zip has to do without. |
| Runtime permissions | `default-permissions-ims.xml` from the device patch, granted on first boot and after each system update: microphone and camera for ImsMedia, phone state and location for ImsStack, IWLAN and QNS. |
| Hidden APIs | Platform-signed apps are exempt from the hidden API policy. |
| User types | `sysconfig_com.android.imsstack` and `preinstalled-packages-imsmedia.xml` install both for the system user. |
| SELinux | Stock domains only: ImsStack runs as `platform_app` (own uid, platform key); ImsMedia as `radio` (`android.uid.phone`); IWLAN as `system_app` (`android.uid.system`); QNS as `platform_app`. No policy was written. Report any denials from the first boot. |

## Carrier configuration

LineageOS ships IMS carrier config for Pixels, not for joan. The build
gets it in `overlay/packages/apps/CarrierConfig/res/xml/vendor.xml`,
which CarrierConfig applies after each carrier's own config, in one
region between `aosp-ims-begin`/`aosp-ims-end` markers:

1. ePDG addresses for T-Mobile (and its MVNOs such as MetroPCS), AT&T
   and Verizon, whose ePDGs are not at the 3GPP default name (from the
   device patch).
2. The IMS keys AOSP 17's own CarrierConfig assets set differently from
   this tree's (Android 15's): 74 blocks for 11 carriers, keyed by carrier
   id and PLMN as Android 15's carrier database assigns them
   (`aosp-ims/carrier/aosp17-carrierconfig-ims.xml`, from
   `aosp-ims/tools/import-aosp-carrierconfig.py`; CI regenerates it from
   the commits pinned in `upstream.lock` and compares). First, as the
   asset they stand for lies under everything else. Then
   the per-carrier IMS config LineageOS ships for Pixels: the Pixel
   CarrierSettings converted with LineageOS's own
   `carriersettings-extractor`, IMS keys only, 1361 blocks for 576
   carriers (`aosp-ims/carrier/lineage-pixel-ims.xml`, added by
   `apply-patches.sh`). It comes after the ePDG blocks, so a carrier's
   own address wins. Keys that would pick another ImsService, require
   provisioning, lock the VoLTE toggle or turn Wi-Fi calling on by
   default are left out (`aosp-ims/tools/import-carrier-settings.py`).
   Then, for the PLMNs joan's LG profiles cover and the Pixel data has
   no block for, what LG's own V30 settings say about IPsec, USSD over
   IMS and the conference factory where they differ from ImsStack's
   defaults: 38 PLMNs, in Russia, Hong Kong, South Africa, North America
   and elsewhere (`aosp-ims/carrier/lg-ims.xml`, from
   `aosp-ims/tools/import-lg-ims.py`, which checks each of the three
   against the networks both data sets cover every time it runs).
3. Last, for every SIM: VoLTE and Wi-Fi calling offered, and the VoLTE
   toggle visible, editable and able to turn IMS off (from the device
   patch).

These are the same rules the flashable zip applies at run time
(`CarrierImsGate`), generated from the same data. To regenerate the
region by hand:

```
python3 aosp-ims/tools/make-carrier-config.py \
    ims-service/assets/carrier-id-map.json \
    ims-service/assets/carrier-plmn-map.json \
    aosp-ims/zip/java/com/android/imsstack/joan/CarrierImsGate.java \
    <tree>/packages/providers/TelephonyProvider/assets/latest_carrier_id/carrier_list.textpb \
    --base aosp-ims/carrier/aosp17-carrierconfig-ims.xml \
    --imported aosp-ims/carrier/lineage-pixel-ims.xml \
    --fill aosp-ims/carrier/lg-ims.xml \
    --splice <tree>/device/lge/joan-common/overlay/packages/apps/CarrierConfig/res/xml/vendor.xml
```

`--splice` replaces the region, so it can be re-run when the data
changes; without `--imported` it writes only the device patch's blocks.
`aosp-ims/tests/check-carrier-config.py` (the same inputs) checks the
merged result for every carrier id and PLMN Android knows: 2866 SIM
identities, 477 of them with an ePDG address from the imported data, no
differences. Given `--patch` and the device patch, it also checks that
the patch carries exactly the generated blocks; CI runs both.

## IMS APNs

LineageOS's APN list (`vendor/apn`) has IMS APNs for about 200 networks;
the Pixel carrier settings LineageOS converts have them for about 1400,
and XCAP (Ut) and emergency APNs for hundreds more. Android 15 makes up
an IMS APN named `ims` and an emergency APN named `sos` when a SIM has
none, so what the tree lacks is the carriers whose APNs are named
otherwise (Verizon's MVNOs, among others) and every XCAP APN.

`apply-patches.sh` writes those as `vendor/apn/aosp-ims.xml`, which
`vendor/apn`'s `make-apns.sh` includes with the country files, and
commits it there. `aosp-ims/tools/make-apns.py` computes it against the
tree's own country files, following how TelephonyProvider picks a SIM's
APNs (its MVNO's rows if any match, else its MCC/MNC's): a row is added
only for a type the SIM has none of, only at the level its APNs already
come from, and at the MCC/MNC level only when the MVNOs sharing those
rows use the same APN. IMS and emergency APNs allow IWLAN, and a network
whose IMS APNs all leave IWLAN out gets an IWLAN-only copy, for Wi-Fi
calling. At the `vendor/apn` commit pinned in `aosp-ims/upstream.lock`
that is 939 rows; `aosp-ims/tests/check-apns.py` checks them for 2714
SIM identities (no SIM's APNs change level or lose a row) and validates
the assembled list against `vendor/apn`'s schema. CI runs it.

`vendor/apn` serves every device in the tree, and the APNs are right for
any of them: this is also the shape of a change for LineageOS itself.

## What was checked, and against what

Checked against the `android15-qpr2-release` sources and the LineageOS
22.2 manifest, without a full tree build:

- The backport compiles and links against LineageOS 22.2's real framework
  and libraries (the zip build does exactly this).
- `apply-patches.sh` applies the patches to the pinned commits and
  produces trees identical to the ones the zip is built from; a second
  run skips all of them.
- The device patch applies to `lineage-22.2` of `joan-common`.
- Android 15's Soong reads ImsStack's and ImsMedia's Android 17
  `Android.bp` files, as patched, without an error: every module type,
  property, `select()` and product variable, in user and userdebug
  builds, tests included (Soong resolves test modules in every build).
  `aosp-ims/tests/check-soong.sh` runs `build/soong` from
  `android15-qpr2-release` over them, and fails on a property it does
  not know (its own control case); CI runs it.
- Every libimsstack and libimsmedia source (873) compiles under what a
  tree build uses: Android 15's clang (`clang-r536225`) with Soong's
  global warning flags and each module's own, warnings as errors
  (`aosp-ims/tests/check-tree-compile.sh`, in CI; a warning planted in a
  copy of one source must fail it). The zip's build uses the NDK's clang
  with warnings off, so this is the only place that is checked.
- Every one of the 51 modules those files use from the rest of the tree
  is defined in Android 15 (`check-soong.sh --verify-stubs`), among them
  `android.hardware.radio.ims.media-V2-java` (version 2 is frozen there),
  `libaconfig_storage_read_api_cc`, `keepanno-annotations` and
  `libphonenumber`. The modules the device patch adds exist: `ImsStack`,
  `ImsMediaService` and `preinstalled-packages-imsmedia.xml` from the
  synced projects, `Iwlan` in LineageOS's fork and
  `QualifiedNetworksService` in `packages/modules/Telephony`, each with
  its own privileged-permission allowlist.
- Android 17's ImsMedia keeps every module Android 15's defines, so
  nothing else in the tree loses a dependency when it is replaced.
- ImsStack's own allowlist covers the 10 permissions its manifests
  request (the debuggable one included) that are privileged at the
  nightly's protection levels, so an enforcing build boots
  (`aosp-ims/tools/check-privapp.py`, run by `build-apk.sh`); ImsMedia
  requests none. `ACCESS_LOCAL_NETWORK`, which only the debuggable
  manifest requests, does not exist in Android 15 and is ignored. Every
  permission the device patch grants by default is a runtime permission
  there, and the ten resources it overlays exist in the nightly's
  framework-res and TeleService.
- The LineageOS manifest carries ImsMedia from AOSP (so the local
  manifest can replace it), IWLAN as LineageOS's fork, and no ImsStack or
  standalone QNS.
- Android 15 has no IWLAN "legacy mode" any more: binding QNS is all it
  takes for IMS to move to Wi-Fi.
- The kernel has the IPsec pieces IWLAN needs (`CONFIG_NET_IPVTI`,
  `CONFIG_IPV6_VTI`, `CONFIG_INET(6)_ESP`), and joan-common already
  declares `android.software.ipsec_tunnels`.

## Other LineageOS versions

- **23.x (Android 16):** the same steps should mostly apply; the
  Android 16 framework has some of the APIs patch 0001 replaces, so
  parts of it may be unnecessary. Not tried.
- **24 (Android 17):** no backport. ImsStack and ImsMedia are in the
  tree; only the device change is needed.

## Do not

- Do not also inherit `joan-ims.mk` (the joan `ImsService`): two MMTEL
  services cannot both be the device's.
- Do not flash the AOSP IMS flashable zip on a build made this way.
- Do not build this repository's root `Android.bp` into the same tree
  unless you want the joan stack instead.
