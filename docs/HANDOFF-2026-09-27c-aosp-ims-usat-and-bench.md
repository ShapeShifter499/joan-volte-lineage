# Handoff — 2026-09-27 (c) — AOSP IMS: carriers opened up, USAT call control fixed, bench build 3

Branch `claude/aosp-ims-a15-backport`. Background: `docs/HANDOFF-2026-09-27-aosp-ims-backport.md`
(what exists and why). This replaces `HANDOFF-2026-09-27b`, whose items 1
and 2 are done.

## State

- **Every SIM** gets VoLTE and Wi-Fi calling offered, with the VoLTE
  toggle kept usable; each carrier's IMS settings come from LineageOS's
  own Pixel carrier data (`aosp-ims/carrier/lineage-pixel-ims.xml`, 1361
  blocks, 576 carriers). The zip and ROM apply it at run time
  (`CarrierImsGate`, one asset per PLMN), so a LineageOS-based ROM keeps
  its own carrier config; a source build gets it in joan-common's
  vendor.xml from `upstream/aosp-ims/apply-patches.sh`. Not the RRO the
  (b) handoff proposed: an RRO replaces the ROM's whole vendor.xml.
- Video calling, RTT and RCS are switched on (overlays, CAMERA grant).
- ImsStack **0005/0006** (USAT call control, below).
- ImsStack **0007**: EVS is never offered. ImsMedia's EVS encoder and
  decoder are TODOs, and the Pixel data listed EVS for 430 of its 772
  codec bundles (ImsStack's internal defaults turn EVS support on). The
  importer drops EVS too and `check-carrier-config.py` fails on any.
- **Calling permissions** (`zip/java/.../CallPermissionsActivity.java`):
  an app-drawer entry, enabled by ImsStack at startup only while
  RECORD_AUDIO is missing, that asks for the runtime permissions with
  Android's dialogs. A zip flashed onto a booted ROM never gets the
  default grants (they apply on a fingerprint change only). Untested on a
  phone. Wi-Fi calling still needs adb once (IWLAN's app-op).
- Installer free-space check uses statfs (toybox df overstated it).
- Local checks at handoff: `tests/UsatCheck.java` 20 cases,
  `check-carrier-config.py` 2866 SIM identities (three layers, 1473
  imported blocks), e2e installer 164/164, `check-rom.sh` all passed,
  plus `ApnPlanCheck` (9081 checks), `GbaCheck` (24, against a local
  BSF), `check-apns.py` (2714 SIM identities), `check-soong.sh
  --verify-stubs` (3 Soong fixture tests, 51 stand-ins found in Android
  15), `regen-aosp-carrierconfig.sh --check`, and both privapp allowlist
  checks. The 0001-0008 series applies to a clean `1e3981c` and gives the
  work tree's exact tree; `apply-patches.sh` on a synthetic tree is
  idempotent and splices 1553 region blocks.
- **LG's settings for networks the Pixel data lacks**
  (`tools/import-lg-ims.py`, `carrier/lg-ims.xml`, 38 PLMNs): IPsec off,
  USSD over IMS, conference factory URI, each rule re-checked against
  the Pixel data on every build. In the zip's assets, the source build's
  vendor.xml region (after the Pixel blocks) and `check-carrier-config.py`
  (which also checks the two sources never overlap). The device patches
  were regenerated for the region's new header line only.
- **AOSP 17's CarrierConfig changes** (`tools/import-aosp-carrierconfig.py`,
  `carrier/aosp17-carrierconfig-ims.xml`, 74 blocks, 11 carriers): the
  IMS keys Android 17's CarrierConfig assets set differently from Android
  15's, as the bottom layer (`--base`) under the Pixel data (`--imported`)
  and LG's (`--fill`). CI regenerates it from pinned commits
  (`tools/regen-aosp-carrierconfig.sh --check`).
- **No release is published.** The user asked for bench zips first.
  Publishing = edit a line of `aosp-ims/RELEASE` and push (CI builds from
  scratch and publishes); only do it when the user says so.

## The bench (US998, T-Mobile)

- Bench 2: registers with IPsec (after 0004). Every MO call failed before
  an INVITE: `USAT: call-control - ... response=0000` then
  `invokeStartFailed`. The SIM has call control by USIM (service 30);
  joan's RIL completes the ENVELOPE (CALL CONTROL) with status words
  00 00 and no data, which ImsStack read as "not allowed". (Not the
  decoder bit-order theory a local agent proposed: there was no data
  byte at all.)
- 0005 sets the call up as dialled when the UICC's answer is missing;
  real answers (93 00, errors, result 01) still block. 0006 fixes the
  PLMN coding of a three-digit MNC in the same envelope.
- **Bench 3** (0005/0006): the call now goes out, rings (183, 180 with
  PRACKs) and is answered (200 OK, ACK), then **our phone sends BYE
  ~216 ms after the ACK**. Leading suspect: RECORD_AUDIO was never
  granted (zip flashed onto a booted ROM), so ImsMedia's AAudio input
  fails when the call goes sendrecv and ImsStack ends the call as a
  media failure. Ruled out: the QoS wait (T-Mobile: 40 s, voice on the
  default bearer allowed), and the mic being silenced in a background
  process (capabilities are per uid; ImsStack's persistent process
  gives the uid the microphone). Other candidates: an ImsMedia crash
  (MEDIA_DETACH) or an AAudio/HAL failure.
- Asked of the user: `dumpsys package com.android.imsstack | grep -E
  "RECORD_AUDIO|CAMERA"`, grant plus reboot, retest; if it still drops,
  `adb logcat -b all -d | grep -E "OnMediaFailed|- Terminate :|NotifyFailures|invokeTerminated|libimsmedia|AudioSession|AAudio|AudioRecord|Fatal signal|FATAL EXCEPTION|avc: +denied"`.
- **Bench 4** zips carry 0007 and Calling permissions.
- **Bench 5** (sent 09-28) adds the APN gate, the GBA service (0008) and
  IWLAN's release flag values. On T-Mobile the gate should log
  `nothing to add or remove` (the ROM has T-Mobile's IMS, XCAP and
  emergency APNs); GBA shows only when call forwarding/waiting settings
  are opened (`ImsStackGba` in the log).
- Same log, ~80 s after the calls: the IMS and internet PDNs dropped
  (`LOST_CONNECTION`) and data went out of service, then IMS
  deregistered. Looks like a network or modem event; watch for it
  recurring.
- Bench logs carry the IMSI and phone number. They live in the user's
  `aosp-ims/work/bench-logs/` (git-ignored); never commit or quote them.

## Bench fixes from the local agent (branch `claude/aosp-ims-a15-backport-v2`)

The user's local agent (on the bench phone) found the BYE's cause and a
second blocker, and pushed fixes to the v2 branch. Brought onto this
branch, verified against the sources:
- **ImsMedia 0002**: the pinned ImsMedia registers `setTestMode` in its
  native JNI table but never declared it in `JNIImsMediaService`, so the
  media service died at library load when the first call's media
  started: the BYE right after answer. Outbound calls work end to end
  with it (bench). **ImsStack 0009** synchronizes the teardown that death
  raced; **0010** logs a refused incoming call (v2's patch called a
  `logw` the class lacked; the helper is added so it compiles).
- **Incoming calls**: LineageOS 22.2's `ImsPhoneCallTracker` returns no
  listener from `onIncomingCall` while
  `ro.telephony.block_binder_thread_on_incoming_calls` is false, which
  joan-common's `system.prop` sets; ImsStack answered 480. Device patch
  **0003** sets it true; the zips flip that one line and leave a marker
  (not v2's whole-`build.prop` backup, which a ROM update could make
  stale), the uninstaller flips it back; `repack-rom.sh` sets it and
  `check-rom.sh` asserts it (v2 read `/build.prop`; on this image it is
  `/system/build.prop`). Inbound not yet confirmed on the bench.
- Left out of v2: `zip/rro-carrierconfig/res/xml/vendor.xml` (a spliced
  copy of joan-common's vendor.xml with no manifest and no build step;
  the runtime gate replaced that approach), and the claim that
  `USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER` breaks GBA on the zip path
  (Android 15 falls back to `READ_PRIVILEGED_PHONE_STATE`, which the zip
  holds).

## Still to do

1. Confirm an incoming call on the bench; then the alpha1 release when
   the user agrees.
2. ~~Every V30 model~~: done. The LineageOS wiki lists H930, H930DS,
   US998 (unlocked), H932 (T-Mobile), H931, H933, LS998, V300K/L/S and
   VS996, all on the one joan build. The zips check no model; the ROM
   keeps the nightly's own device assert. Documented in both READMEs.
3. ~~IMS APNs~~: done (item 5): the zip's `ImsApnGate` and the source
   build's `vendor/apn/aosp-ims.xml`.
4. Build the source-tree integration in a real LineageOS tree. Short of
   that: `tests/check-soong.sh` (in CI) runs Android 15's own Soong over
   the kit's ImsStack and ImsMedia `Android.bp` files and passes (user and
   userdebug, product variables applied), every module they use from the
   tree is defined in Android 15 (`--verify-stubs`), the ten resources
   the device patch overlays exist in the nightly's framework-res and
   TeleService, ImsStack's upstream privapp allowlist covers what its
   manifests request at Android 15's protection levels (`build-apk.sh`),
   and the zip's native compile flags match Soong's per module except
   Soong's hardening sanitizers. `tests/check-tree-compile.sh` (in CI)
   compiles all 873 native sources with Android 15's clang and Soong's
   warnings as errors: clean.
5. ~~From the port audit~~: done. GBA service (ImsStack 0008, device
   patch 0002), the IMS/XCAP/emergency APNs LineageOS lacks (ImsApnGate,
   vendor/apn/aosp-ims.xml), IWLAN flags at the Android 17 release values.
   The SIM event list and IARI shims keep Android 15's contract on
   purpose (see `docs/aosp-ims-port-audit-2026-09-27.md`).
