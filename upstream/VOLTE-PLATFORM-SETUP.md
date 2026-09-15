# Platform VoLTE setup for an AP-side ImsService

This is **part 2** of the split described in `README.md`. Part 1 is the IMS
implementation itself (`Android.bp`, `joan-ims.mk`, the app, the RRO, the
permission files). This file is the other half: the platform variables a
ROM must set before the framework will *admit* VoLTE at all.

Shipping a working `ImsService` is not enough. A correct, registering,
call-capable IMS implementation still gets no outbound calls and no
Settings toggle until these are set. They are device-tree and carrier
config concerns, not app concerns, which is exactly why they are easy to
miss — the app looks finished and nothing works.

Nothing here is joan-specific. Any LineageOS device with an AP-side
`ImsService` needs the same list.

## The gate you are trying to satisfy

Everything below exists to make one framework method return true:

```java
// frameworks/opt/net/ims  —  ImsManager.isVolteEnabledByPlatform()
if (SystemProperties.getInt(PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE, ...) == 1) {
    return true;                                    // debug hammer, do not ship
}
return mContext.getResources().getBoolean(
            com.android.internal.R.bool.config_device_volte_available)
        && getBooleanCarrierConfig(
            CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
        && isGbaValid();
```

It is an **AND of three independent terms**, owned by three different
subsystems:

| # | Term | Lives in | Scope |
|---|------|----------|-------|
| 1 | `config_device_volte_available` | framework-res (`android`) | whole device |
| 2 | `carrier_volte_available_bool` | CarrierConfig | per carrier |
| 3 | `isGbaValid()` | CarrierConfig + SIM | per carrier + SIM |

Miss any one and both of these break:

- **Settings hides the VoLTE toggle.** The user cannot opt in *or* out,
  because the control is not rendered. The toggle lives at Settings →
  Network & internet → SIMs → *VoLTE*; that is the **only** opt-out
  surface, because LineageOS ships no VoLTE quick-settings tile. Do not
  send testers looking in quick settings for it.
- **`GsmCdmaPhone.isImsUseEnabled()` is false**, so every outbound dial
  is refused the IMS path. Where the network still runs CS voice it falls
  back there; on a **VoLTE-only carrier there is no CS to fall back to
  and outbound calling simply fails** (observed on T-Mobile US
  2026-09-14). Registration is unaffected — the stack still sends
  REGISTER and gets 200 OK, and MT calls can still arrive. That
  combination (REGISTER fine, MT fine, MO failing or dropping to GSM) is
  the signature of this misconfiguration, and it is easy to misread as a
  SIP bug.

Debug the terms in order. Term 1 is device-wide and the most commonly
missed.

## Term 1 — framework resource (device-wide)

Package `android` (framework-res), type `bool`:

| Resource | Set to | Notes |
|---|---|---|
| `config_device_volte_available` | `true` | **Required.** AOSP default is `false`. |
| `config_device_vt_available` | leave `false` | Only if you implement video. Enabling the UI without the stack promises what you cannot deliver. |
| `config_device_wfc_ims_available` | leave `false` | VoWiFi. Same reasoning. |

Two ways to set it:

1. **Device tree overlay** (the upstream-correct route) — add to
   `device/<vendor>/<device>/overlay/frameworks/base/core/res/res/values/config.xml`.
   This is what lands when LineageOS carries the device properly.
2. **RRO** targeting package `android` — needed for anything installable
   after the fact, including a flashable zip. See
   `../rro-fw/` for a worked example.

`config_device_volte_available` is a `com.android.internal` resource, not
a public one. Overlaying it from an RRO works because framework-res on
LineageOS 22.2 declares **no `<overlayable>` restrictions at all**
(`aapt2 dump overlayable framework-res.apk` returns nothing), so a
preinstalled overlay on a read-only partition may reach any resource. Do
not rely on that staying true across releases — the device-tree overlay
is the durable answer.

## Term 2 — carrier config (per carrier)

| Key | Set to | Why |
|---|---|---|
| `carrier_volte_available_bool` | `true` | Term 2 of the AND. |
| `hide_enhanced_4g_lte_bool` | `false` | `true` vanishes the toggle and steals the user's opt-out. |
| `editable_enhanced_4g_lte_bool` | `true` | `false` locks the toggle. |
| `enhanced_4g_lte_on_by_default_bool` | leave default (`true`) | Makes untouched users opt-in-by-default with **zero** user-setting writes. |

The upstream-correct route is a per-carrier asset in
`com.android.carrierconfig`, keyed by carrier id or MCCMNC. Major
carriers already have one — that is why some SIMs work with no effort and
smaller carriers do not. Check before assuming yours is missing:

```sh
adb shell dumpsys carrier_config | sed -n '/mConfigFromDefaultApp/,/mConfigFromCarrierApp/p'
```

A device overlay of `vendor.xml` in the carrier config package is the
other build-time option.

## Term 3 — GBA validity

`isGbaValid()` returns true when `carrier_ims_gba_required_bool` is
`false`. If a carrier asset sets it `true`, the framework additionally
requires a GBA-capable ISIM, and VoLTE stays off when the SIM cannot
satisfy it.

Verified on T-Mobile (carrier id 1): with
`carrier_ims_gba_required_bool = true` and terms 1 and 2 satisfied,
the toggle rendered, so `isGbaValid()` **passed** on that SIM. GBA being
required is therefore not by itself a blocker.

It is still the term to suspect if the toggle stays missing with terms 1
and 2 confirmed true, since it depends on the SIM's ISIM rather than on
anything you configure.

## Service discovery — being found at all

Separate from the admit gate: the framework has to bind your service.

| What | Where | Value |
|---|---|---|
| `config_ims_mmtel_package` | `com.android.phone` resource | your ImsService package |
| `android.hardware.telephony.ims` | `etc/permissions/*.xml` `<feature>` | declared |

`config_ims_mmtel_package` makes `ImsResolver` classify the package as
the **device** service and read its manifest `MMTEL_FEATURE` metadata at
package scan time — no runtime override, no dynamic feature query, bound
at every boot. Without it the package is treated as a carrier-style
service with no declared features and is never bound.

This repo ships it as the `JoanImsPhoneDefault` RRO rather than a
build-time `PRODUCT_PACKAGE_OVERLAYS` directory, so one artifact serves
both the ROM and the zip. See `README.md`.

`android.hardware.telephony.ims` is the documented AOSP feature
(`PackageManager.FEATURE_TELEPHONY_IMS`). Note that
`../permissions/android.hardware.telephony.ims.xml` also declares
`android.hardware.telephony.ims.volte`, which is **not** an AOSP feature
constant; it appears harmless but nothing in this repo demonstrates that
it is required. Treat it as unproven rather than as part of the recipe.

## Privileged permissions — and a boot trap

The app is privileged and platform-signed, so every signature|privileged
permission it requests must appear in its privapp allowlist
(`../permissions/org.joan.ims.xml`, installed to
`etc/permissions/`). For this stack:

```
MODIFY_PHONE_STATE              READ_PRIVILEGED_PHONE_STATE
READ_PRECISE_PHONE_STATE        CONNECTIVITY_USE_RESTRICTED_NETWORKS
BIND_IMS_SERVICE                RECORD_AUDIO
MODIFY_AUDIO_SETTINGS
```

**The trap:** `ro.control_privapp_permissions=enforce` (the LineageOS
default) makes a privileged permission that is requested but not
allowlisted a **fatal boot error**, not a silent denial. If you update
the app and it adds a permission, you must update the allowlist in the
same change or the device bootloops.

This bites hardest on the out-of-tree path, where someone pushes only the
APK to `priv-app` and leaves the old allowlist in place. If you are
deploying by hand, push both:

```sh
adb push joan-ims.apk       /system/priv-app/JoanIms/JoanIms.apk
adb push org.joan.ims.xml   /system/etc/permissions/org.joan.ims.xml
```

## APNs

IMS needs its own PDN. The carrier's `apns-conf.xml` entry needs an
`apn="ims"` row (and `apn="xcap"` for UT/supplementary services). Merge
into the existing world list — never replace the file, which would drop
every other carrier. `../scripts/merge-viettel-apns.sh` in this repo is a
worked example of the merge.

## Do not

- **Do not ship `persist.dbg.volte_avail_ovr=1`.** It is the first clause
  of `isVolteEnabledByPlatform()` and short-circuits the entire AND, for
  every SIM, bypassing the per-carrier terms. It is a bring-up hammer.
  Its usefulness during debugging is a strong hint that term 1 is your
  actual problem — fix term 1 instead.
- **Do not persist carrier config overrides.** A persistent override is
  written into `com.android.phone`'s storage, where an uninstaller cannot
  reach it. Non-persistent overrides are dropped at reboot, which is the
  behaviour you want for anything a user can uninstall.
- **Do not write user settings** from the app — no `settings put`, no
  `siminfo` writes. `enhanced_4g_lte_on_by_default_bool` already gives
  you default-on without touching the user's choice, and writing it
  destroys the opt-out you are trying to preserve.

## Verifying on a device

Read the three terms directly rather than inferring them:

```sh
# Term 1 — framework resource (effective value, overlays applied)
adb shell cmd overlay lookup android android:bool/config_device_volte_available

# Terms 2 and 3 — merged carrier config (needs adb root)
adb shell cmd phone cc get-value carrier_volte_available_bool
adb shell cmd phone cc get-value carrier_ims_gba_required_bool
adb shell cmd phone cc get-value hide_enhanced_4g_lte_bool
adb shell cmd phone cc get-value editable_enhanced_4g_lte_bool

# Where a carrier config value came from (default / asset / override)
adb shell dumpsys carrier_config

# Service discovery
adb shell cmd overlay lookup com.android.phone \
    com.android.phone:string/config_ims_mmtel_package
```

`cmd phone cc get-value` reports the **merged** value — defaults, the
carrier asset, and any override, in the order the loader applies them —
which is what the framework actually reads. `dumpsys carrier_config`
prints the layers separately, so use it to find out *which* layer set a
value. Note that the `Default Values from CarrierConfigManager` section
is AOSP's `sDefaults`, not the effective config; reading the effective
value out of that section is a common and misleading mistake.

## Status of this list

Verified on an LG V30 (US998), LineageOS 22.2, Android 15, 2026-09-14:

- Term 1 read `false` on a stock build; an RRO setting it `true` was
  built, installed to `/product/overlay`, and confirmed effective
  (`cmd overlay lookup` → `true`, idmap binding the real framework
  resource).
- Term 2 read `true` from the stock T-Mobile asset, and a runtime
  `overrideConfig` was confirmed to set it on a carrier that lacks one.
- `config_ims_mmtel_package` confirmed effective via its RRO idmap.
- The privapp `enforce` behaviour was confirmed by hitting it.
- With all three terms true, the Settings VoLTE toggle **rendered and
  read ON**, while `settings get global volte_vt_enabled` stayed `null` —
  confirming that `enhanced_4g_lte_on_by_default_bool` delivers default-on
  with no user-setting write, and that the opt-out is genuinely present.
- Term 3 passed on a carrier whose asset sets
  `carrier_ims_gba_required_bool = true`.

Before the term 1 fix, the same handset had **no VoLTE toggle anywhere in
Settings** while registering successfully — the exact symptom this
document exists to explain.

**Not verified:** that MO dial routes to IMS as a result. That needs a
handset and carrier where VoLTE was genuinely unavailable before the
change; the verification above was done on a carrier that already had a
CarrierConfig asset. Do not read this document as a claim that these
settings alone deliver working VoLTE — they remove the platform's veto,
and the IMS implementation still has to work.
