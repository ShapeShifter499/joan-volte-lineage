# LineageOS 24 IMS scope for LG V30 (joan)

Verified 2026-10-13. This is deliberately separate from the LineageOS 22.2
implementation in `upstream/AOSP-IMS.md`.

## Executive verdict

LineageOS 24 has accepted a **generic AOSP IMS product integration**. The
accepted device-tree shape is intentionally tiny:

```make
$(call inherit-product, hardware/lineage/generic-ims/ims.mk)
```

Gerrit change 502871 (`s5e9925: Inherit Generic IMS Makefile`) is **MERGED**
on `lineage-24.0`; its complete diff is that three-line IMS block. Changes
494628, 497129 and 500618 are **ABANDONED**, and 503116 is **NEW**, not merged.
Do not cite those abandoned device-specific stacks as accepted precedent.

For joan, this is the right destination, but it is not yet a one-line port:
there is no `lineage-24.0` (or 23.x) joan-common branch, and several generic
runtime defects that the 22.2 bench proved still exist in LineageOS 24's
pinned AOSP sources. Land those as generic upstream fixes, not as a large
joan device-tree fork; then joan can use the accepted one-line inheritance
plus genuinely device-specific configuration.

## Exact LineageOS 24 inputs

Snapshot:

- LineageOS manifest `lineage-24.0` at
  `2a50ca061bffe4e73918ae2e0b99b7a05f647154`.
- The manifest's AOSP remote is `android-17.0.0_r1`; ImsStack is the unmodified
  AOSP project at that tag.
- `hardware/lineage/generic-ims` at
  `e9c35906eca2bc8436c38616608f38b1802f5089`.
- LineageOS ImsMedia at
  `aa3e6c5f070238cae1ec05d9997e6ac021a1e22e`; relative to AOSP
  `0101cb4`, Lineage already carries the missing `setTestMode` declaration
  (`6d2999c`) and a makefile-list termination fix.
- Current official joan-common has only `lineage-22.2` at
  `187b7e443bc8dac1c5a89f157f7a04314e887b45`; no official 23.x/24 branch was
  advertised by `git ls-remote` at this snapshot.

## What `generic-ims/ims.mk` already supplies

The shared makefile installs:

- AOSP `ImsStack`;
- LineageOS `ImsMediaService` through `packages/modules/ImsMedia/imsmedia.mk`;
- AOSP `Iwlan` and `QualifiedNetworksService`;
- `FrameworkResOverlayIms` and `TelephonyOverlayIms`;
- the IMS feature XML;
- generic ImsMedia SELinux policy; and
- LineageOS's **full platform CarrierConfig service**
  `com.android.carriersettings` with hundreds of protobuf carrier profiles.

That CarrierSettings app is not krazey's data-only
`org.lineageos.carriersettings`. On LineageOS 24, do **not** add krazey's
package or splice the 22.2 Pixel carrier data into `vendor.xml`; use the shared
LineageOS carrier service, then contribute missing profiles/corrections there.
APN rows remain a separate `vendor/apn` concern.

The shared framework overlay already sets:

- `config_device_volte_available=true`;
- `config_device_wfc_ims_available=true`;
- `config_use_voip_mode_for_ims=true`; and
- `config_ims_mmtel_package=com.android.imsstack`.

`config_use_voip_mode_for_ims=true` makes `ImsPhoneConnection` select Android
VoIP audio mode at connection construction, so the joan 22.2 stack patch that
calls `setCallAudioHandler(AUDIO_HANDLER_ANDROID)` should not be carried onto
24 without evidence.

The shared SELinux policy already permits the radio-domain ImsMedia process to
find `scheduling_policy_service`, so the old zip-domain thread-priority
workaround is also not a LineageOS 24 patch.

## Joan's eventual device-tree delta

Once joan has a buildable LineageOS 24 device branch, start with:

1. inherit `hardware/lineage/generic-ims/ims.mk`;
2. keep `ro.telephony.block_binder_thread_on_incoming_calls=true` unless the
   Lineage telephony fork no longer contains that compatibility gate;
3. carry only APNs missing from `vendor/apn`, generated/validated against the
   actual 24 branch;
4. verify the generic carrier protobufs cover T-Mobile US and every target
   carrier before adding narrowly scoped profile corrections;
5. verify joan's kernel exposes every IPsec algorithm the Android 17 IWLAN
   path selects; and
6. add no second MMTEL service, alternate CarrierSettings package, Magisk
   module or proprietary LG/Qualcomm IMS stack.

The krazey-only ImsStack resource
`config_imsstack_dedicated_bearer_qos_supported` does not exist in AOSP
`android-17.0.0_r1`, so a joan RRO cannot set it on the current LineageOS 24
stack. If a dedicated-bearer gate is still needed, first upstream the generic
resource seam (or make the behavior carrier-config driven), then overlay it;
do not fork `ConfigAgent` in the device tree.

## Generic runtime fixes still required or needing proof

These are not reasons for a large joan device patch. They are candidate AOSP,
LineageOS generic-IMS, or shared ImsMedia/IWLAN changes, each with its own test.

### Baseline-critical, proven on the 22.2 bench

- **ImsMedia deferred native open:** AOSP/LineageOS 24 `AudioSession` still
  opens a native session when the incoming-call open has a null `RtpConfig`
  and still dereferences `mLocalSession` for thresholds and DTMF. Port the
  corrected deferred-open state machine and its close/failure tests to
  LineageOS ImsMedia. This is generic, not joan-specific.
- **IMS network binding:** unbound SIP can leave over the default Wi-Fi
  network during Wi-Fi calling; the bind-before-connect/live-capability fixes
  remain generic ImsStack candidates.
- **AOSP IWLAN on a VTI kernel:** Android 17's pinned IWLAN still needs real
  tunnel endpoints on joan's 4.4 VTI path. Upstream the real-endpoint behavior
  for VTI kernels or provide equivalent kernel support; otherwise downlink
  packets are dropped.
- **USAT no-answer semantics:** joan's RIL returns `00 00` with no envelope
  body. Treating that as refusal blocks every T-Mobile outgoing call. The
  clean destination is the RIL boundary; until then, the ImsStack fix is
  required for joan qualification.
- **Three-digit MNC coding:** the USAT location-information encoding fix is a
  3GPP correctness fix and should be upstreamed, not hidden in joan.

### Important edge/capability fixes

- early outgoing-call local termination (krazey's superset of old patch 0015);
- inverted codec bitrate range hardening;
- media-service teardown race;
- accurate IWLAN registration transport reporting;
- EVS must remain disabled until ImsMedia has a working EVS codec;
- GBA service is needed before claiming Ut/XCAP completeness; and
- emergency MMTEL, RTT/video and RCS must remain separately qualified rather
  than inferred from package presence.

## Proposed acceptance sequence

1. **Do not start with a joan Gerrit change.** First establish a booting
   LineageOS 24 joan device branch; none exists upstream today.
2. Port and unit-test the generic ImsMedia deferred-open fix against
   LineageOS 24 ImsMedia.
3. Submit generic/spec fixes (ImsMedia, network binding, IWLAN VTI behavior,
   three-digit MNC) to their owning projects. Keep joan RIL behavior isolated
   at the narrowest boundary.
4. Bench an exact 24 candidate on US998: LTE registration, incoming and
   outgoing calls, early hang-up, audio, Wi-Fi registration/call, LTE↔Wi-Fi
   transitions, restart/close races, and uninstall/rollback.
5. Only then submit joan's device change in the accepted shape: shared
   `generic-ims/ims.mk` inheritance plus minimal device properties/APNs.

## Non-claims

- Gerrit acceptance of the generic makefile proves project direction, not that
  IMS works on joan.
- Package presence or registration alone does not prove audio, incoming calls,
  Wi-Fi traffic, handover, emergency calling, SMS, Ut, video, RTT or RCS.
- This scope is not a LineageOS 24 build; joan has no such source branch yet.
