# AOSP IMS backport: is the port to Android 15 complete? (2026-09-27, updated 09-28)

A check of everything at the boundary between Android 17's IMS modules
(ImsStack, ImsMedia, IWLAN, QNS) and LineageOS 22.2 (Android 15 QPR2),
done by diffing the sources, not by reading release notes. Sources:
`frameworks/base`, `frameworks/opt/telephony`, `packages/services/Telephony`
and `packages/providers/TelephonyProvider` at `android15-qpr2-release` and
`android-17.0.0_r1`, `system/sepolicy` and `build/release` at the same,
the modules at the commits in `aosp-ims/upstream.lock`, LineageOS's
`vendor/apn` and `android` manifest (lineage-22.2), and the 2026-09-20
joan nightly (TeleService resources, product APN list, vendor VINTF
manifest).

## Verdict

At the Android version boundary the port is complete: every Android 16/17
API ImsStack uses has an Android 15 translation or is shown to change
nothing on LineageOS 22.2, and the flags match the Android 17 release.
The two platform pieces the stack needs and LineageOS lacks, a GBA
service and the carriers' IMS APNs, are now supplied (below). What
remains is on-device verification and joan's own hardware limits.

## What was checked

| Boundary | Android 15 vs 17 | Effect on the backport |
|---|---|---|
| Binder interfaces between the phone process and ImsStack (`android/telephony/ims/aidl`, 29 files) | Identical except one new callback, `IImsRegistrationCallback.onDeregisteredWithTime` | ImsStack never uses it (it is how Android 17 passes a deregistration throttle time to apps) |
| Java IMS API (all 81 classes in `android.telephony.ims`) | Additions only: video ringback / CRS / low-battery call extras, a P-CSCF field in `ImsRegistrationAttributes`, an `onDeregistered` overload | ImsStack uses none of them. It is compiled against the ROM's own framework jars, so any Android 16/17-only call fails the build; the four it has are below |
| Carrier config defaults | ImsStack reads 220 `CarrierConfigManager` keys: 194 have a framework default, the same in both; 26 have none in either | No behavior drift from defaults |
| aconfig flags | ImsStack and ImsMedia read none (Java or native; the native flag libraries are declared but unused). IWLAN reads three | Built with the Android 17 release's values (`cp1a` inherits `bp3a`): `iwlan_silent_restart` on, the two trunk-only flags off |
| Phone process (`imsphone`, `ims`) | Mostly launched-flag cleanups; new features (CRS, video ringback, deregistration throttle time) and call-merge fixes inside the phone process | Nothing ImsStack depends on |
| Native libraries | Full upstream `libimsstack` and `libimsmedia` graphs, linked against the ROM's `/system/lib64` with `--no-undefined`; only these two are loaded. Each module's compile flags match what Android 15's Soong gives it (C++20, no RTTI or exceptions, the same defines), less Soong's hardening (integer-overflow and bounds sanitizers, CFI, shadow call stack, LTO) and the userdebug-only `__IMS_TRACE_MEM__` debug-log define | Every symbol resolves on this ROM; a tree build adds the hardening, so an overflow the zip wraps would abort there, as on upstream's own builds |
| Build files (`Android.bp`, the source-build kit) | Android 15's Soong (`android15-qpr2-release`) reads ImsStack's and ImsMedia's Android 17 files, patched, with no error in user or userdebug; every one of the 51 modules they take from the rest of the tree is defined in Android 15; Android 17's ImsMedia keeps every module Android 15's defines | A LineageOS 22.2 tree gets past Soong's analysis (`tests/check-soong.sh`, in CI) |
| Manifests (ImsStack merged, IWLAN, QNS) | All 51 permissions requested and every component's permission are defined on the ROM | Every binding and request can succeed |
| Reflection | None in ImsStack or ImsMedia | No hidden run-time lookups |
| Permission and sysconfig files | Ours list upstream's names; the zip adds `WRITE_APN_SETTINGS` for its APN gate | Same grants as upstream |
| SELinux | Android 17 has no ImsStack-specific policy (runs as `platform_app`) | Nothing to port |

## The four Android 16/17 APIs (ImsStack 0001)

| API (release) | Android 15 translation | Effect on joan |
|---|---|---|
| `BarringInfo#getCellIdentity` (17) | Read back from the parcel, where `BarringInfo` writes it first | None |
| `TelephonyManager#EXTRA_SETUP_EVENT_LIST` (17) | Local constant; Android 15's contract kept | None. Android 17's `CatService` accepts a SIM's SET UP EVENT LIST with the IMS registration event and forwards it to the IMS app; Android 15's declines that event ("beyond terminal capability"), so the SIM never expects it. ImsStack sends the event only under `ims.usat_reg_event_download_policy_int` 1-3; its default is 0 (never) and no carrier data sets it. Backporting 17's acceptance would promise the SIM an event nothing sends |
| `TelephonyManager#requestUiccIari` (17) | No IARIs | None: the IARIs only feed that same policy (3) |
| `DomainSelectionEmergencyModeListener` (16) | Not registered | None: the ROM leaves domain selection off (`config_domain_selection_service_component_name` is empty) and joan's HIDL radio has no emergency mode, so it would never fire |

## Platform pieces LineageOS lacks, now supplied

- **GBA** (ImsStack 0008, device patch 0002, the zip's phone overlay).
  Ut/XCAP authenticates with GBA through
  `TelephonyManager#bootstrapAuthenticationRequest`; Android 15's
  `gba_mode_int` defaults to GBA_ME for every carrier and no carrier data
  overrides it; the request goes to the `GbaService` in TeleService's
  `config_gba_package`, which is empty on LineageOS, and AOSP ships none.
  `ImsStackGbaService` does GBA_ME with the carrier's BSF (TS 24.109,
  TS 33.220), checked on the host against a BSF of the test's own.
- **IMS, XCAP and emergency APNs** (`ImsApnGate` in the zip,
  `vendor/apn/aosp-ims.xml` in a source build). LineageOS's list has IMS
  APNs for about 200 networks, the Pixel data it converts for about 1400.
  Android 15 falls back to "ims" and "sos" by itself; the rest (Verizon's
  MVNOs, every XCAP APN) are added only for a type the SIM lacks and at
  the level its APNs already come from.
- **Carrier config where the Pixel data has none** (`carrier/lg-ims.xml`).
  82 PLMNs joan's LG profiles cover have no Pixel block at all; the 38
  where LG's settings differ from ImsStack's defaults get LG's IPsec,
  USSD-over-IMS and conference-factory settings, the
  three LG fields whose translation into ImsStack's keys the Pixel data
  confirms on the networks both cover (30/34, 88/95, 71/75). ImsStack's
  defaults cover the rest: it falls back from IPsec on a 420 and derives
  the 3GPP conference factory by itself, so these only save a round trip
  or reach a carrier-specific conference server.

## Carried as upstream has them

- **EVS**: ImsMedia's encoder and decoder are TODOs (ImsStack 0007 stops
  offering it).
- **SIP delegates (RCS single registration)**: commented out in
  `ImsService#getImsServiceCapabilities` upstream, so no
  `android.hardware.telephony.ims.singlereg` feature is declared.
- **Upstream placeholders**: `imsstack-prebuilt` is empty in AOSP,
  `imsstack-carrier-config-ext` is optional and not public, and the
  per-carrier public config assets are empty; the carrier data comes from
  LineageOS instead (`aosp-ims/carrier/`).
- **Limited admin SMS (Verizon PCO 0xFF00)**: the PCO receiver is not in
  any carrier config's signal list, and the feature is off
  (`imssms.support_limited_admin_sms_mode_bool` false everywhere), so
  registration never waits for it.

## joan's hardware

The vendor manifest has HIDL `android.hardware.radio@1.4::IRadio` and no
AIDL `android.hardware.radio.ims` (Android 14's IMS radio interface). So
the framework's IMS radio requests (IMS traffic sessions, registration
info to the modem, SRVCC call info, EPS fallback, ANBR) all fail. ImsStack
copes: Android 15 answers a failed traffic session with
`REASON_UNSPECIFIED`, which ImsStack maps to an internal error and treats
as ready. What cannot work on this hardware with any AP IMS stack:
**SRVCC** (a call drops rather than moving to 3G/2G when LTE is lost) and
modem-side IMS traffic priority.

## Zip-only compromises (a platform-signed source build has none)

- `READ_ACTIVE_EMERGENCY_SESSION`: ImsStack 0004 falls back to the call
  state.
- `MANAGE_IPSEC_TUNNELS`: IWLAN gets the app-op over adb instead.
- `ACCESS_SURFACE_FLINGER`, `INTERACT_ACROSS_USERS_FULL`: requested but
  unused. ImsMedia draws video into the surfaces the dialer hands it
  (`ANativeWindow`), and neither app makes a cross-user call.
- ImsMedia runs inside ImsStack's uid instead of `android.uid.phone`, as
  `priv_app` instead of `platform_app`; the uid's persistent main process
  gives it the microphone capability while in the background.
- Runtime grants on a ROM that has already booted: "Calling permissions".

## Not yet verified

- On the bench (US998, T-Mobile): IPsec registration and outgoing call
  signalling work; call media is pending (bench 4). Untested: incoming
  calls, SMS over IMS, Wi-Fi calling and handover, emergency calls, Ut
  (now with GBA), conference, video, RTT, dual SIM, other carriers and
  models, the APN gate on a phone.
- The source-build kit has not been compiled in a full LineageOS tree:
  its build files pass Android 15's Soong and its sources compile and
  link against the ROM (the zip build), but no tree has run the two
  together.
- ImsStack's own unit tests (instrumentation tests) have not run.
