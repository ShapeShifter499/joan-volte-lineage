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
- Installer free-space check uses statfs (toybox df overstated it).
- Local checks at handoff: `tests/UsatCheck.java` 20 cases,
  `check-carrier-config.py` 2866 SIM identities, e2e installer 164/164,
  `check-rom.sh` all passed.
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
- **Bench 3** zips (with 0005/0006) were sent to the user; results
  pending. On the phone, look for
  `call-control - no answer from the UICC, set up as dialled`, then the
  INVITE (`SIPMSG[0]={ OUT, INVITE`) and its responses. If the call
  still fails, the next suspect is the SIP/SDP exchange or media.
- Same log, ~80 s after the calls: the IMS and internet PDNs dropped
  (`LOST_CONNECTION`) and data went out of service, then IMS
  deregistered. Looks like a network or modem event; watch for it
  recurring.
- Bench logs carry the IMSI and phone number. They live in the user's
  `aosp-ims/work/bench-logs/` (git-ignored); never commit or quote them.

## Still to do

1. Bench 3 results; then the alpha1 release when the user agrees.
2. **Every V30 model**: the ROM keeps LineageOS's OTA assert
   (`v30, joan, h930, h932`); the zips check no model. Confirm against
   `device/lge/joan` and document the models (H930, H930DS, H932,
   US998, LS998, VS996).
3. IMS APNs: ~100 carriers have IMS APNs with custom names that
   LineageOS lacks (Android 15's default `ims` profile covers the rest).
   The zip's APN merge is Viettel-only.
4. Build the source-tree integration in a real LineageOS tree.
