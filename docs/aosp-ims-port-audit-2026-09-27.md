# AOSP IMS backport: is the port to Android 15 complete? (2026-09-27)

A check of everything at the boundary between Android 17's IMS modules
(ImsStack, ImsMedia, IWLAN, QNS) and LineageOS 22.2 (Android 15 QPR2),
done by diffing the sources, not by reading release notes. Sources:
`frameworks/base` and `frameworks/opt/telephony` at `android15-qpr2-release`
and `android-17.0.0_r1`, `system/sepolicy` and `build/release` at the same,
the modules at the commits in `aosp-ims/upstream.lock`, and the 2026-09-20
joan nightly (TeleService resources, vendor VINTF manifest).

## Verdict

At the Android version boundary the port is complete: nothing ImsStack
needs from Android 16 or 17 is missing, apart from the two small losses
below. What remains is on-device verification, one platform service
LineageOS lacks (GBA), and joan's own hardware limits.

## What was checked

| Boundary | Android 15 vs 17 | Effect on the backport |
|---|---|---|
| Binder interfaces between the phone process and ImsStack (`android/telephony/ims/aidl`, 29 files) | Identical except one new callback, `IImsRegistrationCallback.onDeregisteredWithTime` | ImsStack never uses it (it is how Android 17 passes a deregistration throttle time to apps) |
| Java IMS API (all 81 classes in `android.telephony.ims`) | Additions only: video ringback / CRS / low-battery call extras, a P-CSCF field in `ImsRegistrationAttributes`, an `onDeregistered` overload | ImsStack uses none of them. It is compiled against the ROM's own framework jars, so any Android 16/17-only call fails the build; the four it has are shimmed by ImsStack 0001 |
| Carrier config defaults | ImsStack reads 220 `CarrierConfigManager` keys: 194 have a framework default, the same in both; 26 have none in either | No behavior drift from defaults |
| aconfig flags | ImsStack and ImsMedia read none (Java or native; the native flag libraries are declared but unused). IWLAN reads three | Android 17's release (`cp1a` inherits `bp3a`) enables `iwlan_silent_restart`; ours is off. It only enables a restart hook that nothing on Android 15 calls. The other two are off in both |
| Phone process (`imsphone`, `ims`) | Mostly launched-flag cleanups; new features (CRS, video ringback, deregistration throttle time) and call-merge fixes inside the phone process | Nothing ImsStack depends on |
| Native libraries | Full upstream `libimsstack` and `libimsmedia` graphs, linked against the ROM's `/system/lib64` with `--no-undefined` | Every symbol resolves on this ROM |
| Reflection | None in ImsStack or ImsMedia | No hidden run-time lookups |
| Permission and sysconfig files | Ours list exactly upstream's names | Same grants as upstream |
| SELinux | Android 17 has no ImsStack-specific policy (runs as `platform_app`) | Nothing to port |

## The four shims in ImsStack 0001

| API (release) | Shim | Loss on joan |
|---|---|---|
| `TelephonyManager#requestUiccIari` (17) | No IARIs | RCS only |
| `BarringInfo#getCellIdentity` (17) | Read back from the parcel | None |
| `TelephonyManager#EXTRA_SETUP_EVENT_LIST` (17) | Local constant | **Real, small:** Android 15's `CatService` never broadcasts the SIM's SET UP EVENT LIST, so ImsStack cannot send the IMS registration event download (TS 31.111) to SIM applets. Closable in a source build by backporting Android 17's `CatService#broadcastSetupEventList` (about 20 lines behind `supportImsRegistrationEventDownload`); not in the zip |
| `DomainSelectionEmergencyModeListener` (16) | Not registered | None: the ROM leaves domain selection off (`config_domain_selection_service_component_name` is empty), so it would never fire |

## Missing on the platform, not version-specific

- **GBA.** ImsStack's Ut/XCAP (call forwarding, waiting and barring
  settings over IMS) authenticates through
  `TelephonyManager#bootstrapAuthenticationRequest`, which needs a
  `GbaService`. AOSP ships only the API; LineageOS has none
  (`config_gba_package` is empty). Carriers whose XCAP server asks for
  GBA will fail those settings. Fix: a small `GbaService` app (GBA_ME,
  3GPP TS 33.220 / 24.109, AKA through `getIccAuthentication`), usable by
  both the zip and a source build.
- **Upstream placeholders.** `imsstack-prebuilt` is an empty library in
  AOSP, `imsstack-carrier-config-ext` is optional and not public, and the
  per-carrier public config assets are empty. The carrier data comes from
  LineageOS instead (`aosp-ims/carrier/`).
- **EVS.** ImsMedia's encoder and decoder are TODOs upstream (ImsStack 0007).

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

The signature permissions `ACCESS_SURFACE_FLINGER` (video surfaces),
`INTERACT_ACROSS_USERS_FULL` (work profiles), `READ_ACTIVE_EMERGENCY_SESSION`
(ImsStack 0004 falls back) and `MANAGE_IPSEC_TUNNELS` (adb app-op for
IWLAN); ImsMedia inside ImsStack's uid instead of `android.uid.phone`;
`priv_app` instead of `platform_app`; runtime grants on a booted ROM
("Calling permissions").

## Not yet verified

- On the bench (US998, T-Mobile): IPsec registration and outgoing call
  signalling work; call media is pending (bench 4). Untested: incoming
  calls, SMS over IMS, Wi-Fi calling and handover, emergency calls, Ut,
  conference, video, RTT, dual SIM, other carriers and models.
- The source-build kit has not been compiled in a LineageOS tree.
- ImsStack's own unit tests (instrumentation tests) have not run.
