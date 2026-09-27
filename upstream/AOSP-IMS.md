# AOSP IMS in a LineageOS 22.2 build (joan)

How to build VoLTE and Wi-Fi calling into LineageOS 22.2 for the LG V30,
using AOSP's own IMS stack from Android 17. This replaces the joan
`ImsService` described in `README.md`. Use one or the other, never both:
a device has one MMTEL `ImsService`.

**Status.** Nothing here has run on a phone yet.
- The flashable zip and the repacked ROM (`aosp-ims/README.md`) are built
  from the same patched sources as this and pass the installer tests.
- The source-tree integration below has not been built in a LineageOS
  tree: a full tree does not fit in the environment this was written in.
  It was checked piece by piece against the Android 15 sources (see
  "What was checked"). Expect to fix small build errors on the first
  build, and please report them.

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
| ImsMedia 0001 | Lets ImsMedia run inside the caller's own package (the zip's single APK). A separate `ImsMediaService`, as here, is bound as before |

## Steps

From the top of a LineageOS 22.2 tree that already builds joan:

1. **Sync the Android 17 projects.**

   ```
   cp <this repo>/upstream/aosp-ims/local_manifests/aosp-ims.xml .repo/local_manifests/
   repo sync packages/modules/ImsMedia packages/modules/ImsStack
   ```

2. **Apply the backport patches.** Each one becomes a commit in its
   project. Run it again after any `repo sync` that resets them.

   ```
   <this repo>/upstream/aosp-ims/apply-patches.sh .
   ```

3. **Apply the device change** to `device/lge/joan-common`:

   ```
   git -C device/lge/joan-common am <this repo>/upstream/aosp-ims/device/0001-joan-common-Add-the-AOSP-IMS-stack.patch
   ```

   Made against `lineage-22.2` at 47c4939 (2025-02-11). It:
   - adds `ImsStack`, `ImsMediaService`, `Iwlan` and
     `QualifiedNetworksService` to `PRODUCT_PACKAGES`, and the
     `android.hardware.telephony.ims` feature;
   - sets the framework overlay: `config_device_volte_available` and
     `config_device_wfc_ims_available` true, and the WLAN data, WLAN
     network and qualified networks services to AOSP's IWLAN and QNS in
     place of `vendor.qti.iwlan` (Qualcomm's needs modem IMS, which this
     modem does not have);
   - adds a Telephony overlay: `config_ims_mmtel_package` =
     `com.android.imsstack`;
   - adds the carrier blocks to CarrierConfig's `vendor.xml` (below);
   - adds `system_ext/etc/default-permissions/default-permissions-ims.xml`
     (below).

4. **Build and flash** as usual (`breakfast joan`, `brunch joan`). On a
   phone that had the flashable zip, flash the zip's `-uninstall` first
   (or wipe), so the zip's copies in `/system` do not shadow the build's.

5. **Check** after boot:

   ```
   adb shell dumpsys telephony.registry | grep -i ims
   adb shell cmd phone ims get-ims-service
   adb logcat -b all | grep -iE 'imsstack|ImsResolver|imsmedia|iwlan'
   ```

## Permissions

A tree build signs everything with the platform key, so it needs none of
the zip's adb steps.

| Kind | Handled by |
|---|---|
| Privileged permissions | Each module's own allowlist, installed next to it on `system_ext` by its `Android.bp`: `privapp_permissions_com.android.imsstack`, `privapp-permlist_com.google.android.iwlan.xml`, `privapp-permissions_com.android.telephony.qns`. ImsMedia requests none. |
| Signature permissions | The platform key: `ACCESS_SURFACE_FLINGER`, `INTERACT_ACROSS_USERS_FULL` (ImsStack), `MANAGE_IPSEC_TUNNELS` (IWLAN), `USE_IMSMEDIA`. These are what the zip has to do without. |
| Runtime permissions | `default-permissions-ims.xml` from the device patch, granted on first boot and after each system update: microphone and camera for ImsMedia, phone state and location for ImsStack, IWLAN and QNS. |
| Hidden APIs | Platform-signed apps are exempt from the hidden API policy. |
| User types | `sysconfig_com.android.imsstack` and `preinstalled-packages-imsmedia.xml` install both for the system user. |
| SELinux | Stock domains only: ImsStack runs as `platform_app` (own uid, platform key); ImsMedia as `radio` (`android.uid.phone`); IWLAN as `system_app` (`android.uid.system`); QNS as `platform_app`. No policy was written. Report any denials from the first boot. |

## Carrier configuration

LineageOS ships no IMS carrier config for most carriers joan users are
on, so the device patch adds it to
`overlay/packages/apps/CarrierConfig/res/xml/vendor.xml`, which
CarrierConfig applies after each carrier's own config:

- VoLTE offered for every SIM, with the VoLTE toggle visible and
  editable. It is on by default (`enhanced_4g_lte_on_by_default_bool`);
  the toggle is the opt-out.
- Wi-Fi calling offered for the carriers LG's own firmware shipped it on
  (103 of LG's 164 profiles), matched by Android carrier id and by PLMN.
- The ePDG address for T-Mobile (and MetroPCS), AT&T and Verizon, whose
  ePDGs are not at the 3GPP default name.

These are the same rules the flashable zip applies at run time
(`CarrierImsGate`), generated from the same data:

```
python3 aosp-ims/tools/make-carrier-config.py \
    ims-service/assets/carrier-id-map.json \
    ims-service/assets/carrier-plmn-map.json \
    aosp-ims/zip/assets/wfc-profiles.json \
    aosp-ims/zip/java/com/android/imsstack/joan/CarrierImsGate.java \
    <tree>/packages/providers/TelephonyProvider/assets/latest_carrier_id/carrier_list.textpb \
    --splice <tree>/device/lge/joan-common/overlay/packages/apps/CarrierConfig/res/xml/vendor.xml
```

`--splice` replaces the block between the `aosp-ims-begin`/`aosp-ims-end`
markers, so it can be re-run when the data changes.
`aosp-ims/tests/check-carrier-config.py` (same five inputs) checks the
result against the gate's rules for every carrier id and PLMN Android
knows: 2866 SIM identities, 822 of them with Wi-Fi calling, no
differences. Given `--patch` and the device patch, it also checks that
the patch carries exactly the generated blocks; CI runs both.

## What was checked, and against what

Checked against the `android15-qpr2-release` sources and the LineageOS
22.2 manifest, without a full tree build:

- The backport compiles and links against LineageOS 22.2's real framework
  and libraries (the zip build does exactly this).
- `apply-patches.sh` applies the patches to the pinned commits and
  produces trees identical to the ones the zip is built from; a second
  run skips all of them.
- The device patch applies to `lineage-22.2` of `joan-common`.
- Every module the Android 17 `Android.bp` files depend on exists in
  Android 15: `android.hardware.radio.ims.media-V2-java`,
  `modules-utils-handlerexecutor`, `keepanno-annotations`,
  `TelephonyStatsLib` (QNS), `framework-annotations-lib`,
  `libphonenumber`. The Android 17 files use `select()` and aconfig
  `container`, both supported by Android 15's Soong.
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
