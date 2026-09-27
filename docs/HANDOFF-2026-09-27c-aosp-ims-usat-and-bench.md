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
  `check-carrier-config.py` 2866 SIM identities, e2e installer 164/164,
  `check-rom.sh` all passed. The 0001-0007 series applies to a clean
  `1e3981c` and gives the work tree's exact tree.
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
- Same log, ~80 s after the calls: the IMS and internet PDNs dropped
  (`LOST_CONNECTION`) and data went out of service, then IMS
  deregistered. Looks like a network or modem event; watch for it
  recurring.
- Bench logs carry the IMSI and phone number. They live in the user's
  `aosp-ims/work/bench-logs/` (git-ignored); never commit or quote them.

## Still to do

1. The BYE after answer (above); then the alpha1 release when the user
   agrees.
2. ~~Every V30 model~~: done. The LineageOS wiki lists H930, H930DS,
   US998 (unlocked), H932 (T-Mobile), H931, H933, LS998, V300K/L/S and
   VS996, all on the one joan build. The zips check no model; the ROM
   keeps the nightly's own device assert. Documented in both READMEs.
3. IMS APNs: ~100 carriers have IMS APNs with custom names that
   LineageOS lacks (Android 15's default `ims` profile covers the rest).
   The zip's APN merge is Viettel-only.
4. Build the source-tree integration in a real LineageOS tree.
