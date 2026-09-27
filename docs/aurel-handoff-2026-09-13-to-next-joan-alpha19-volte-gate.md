# Handoff: joan alpha19 (code 27) default-on VoLTE admit gate

Written-by: Aurel Nymvale (agent-aurel)
Agent-harness: Hermes-Agent:kimi-coding/k3-256k
Date: 2026-09-13
Recipient: next session / any sibling agent resuming the joan VoLTE tester lane
Direction: FYI + resume-work. Alpha19 code is DONE, packed, local-only; publish + bench are the open gates.

## Context / standing instruction

External Viettel tester (Deck #110, PLMN 45204, carrier_id 1899): class-4
failure — REGISTER 200 OK, inbound (MT) calls work, outbound (MO) dials fall
back to GSM/CS. Root cause established earlier: AOSP CarrierConfig has no
1899 asset and joan-common vendor.xml ships zero VoLTE bools, so
`carrier_volte_available_bool` defaults false → `GsmCdmaPhone.isImsUseEnabled()`
false → MO dial never routes to IMS. Not a SIP bug.

Lance's design decision (2026-09-13, verbatim): **"just force it by default.
And those who don't need or can't use it toggle volte off in the settings?"**
— i.e. default-on admit for EVERY carrier, opt-out = the stock Settings
"VoLTE / Enhanced 4G LTE" toggle. No app-managed allowlist. Earlier variants
(1899-only allowlist, TMUS-denylist) are superseded.

## Repo + branch state (recomputed at write time)

- Repo: `/home/kumo02/vibe-coding-projects/coding/joan-volte-lineage`, branch `main`.
- HEAD: `602428e` = **v0.4.0-alpha18 (versionCode 26)** — committed, tagged,
  pushed, and published as a GitHub prerelease TODAY (2026-09-13 21:37 UTC,
  "tester zip (versionCode 26)"). `git rev-list --count origin/main..main` = 0.
- Alpha18 contents (already public): Viettel 45204 APN overlay merged into
  product `apns-conf.xml` (IMS + XCAP/UT + IPV4V6) with `.joan-orig` backup;
  uninstall restores. alpha18 did NOT fix the MO-routing gate — that is alpha19.
- **Alpha19 changes are UNCOMMITTED on top of HEAD** (working tree only):
  - new: `ims-service/src/org/joan/ims/JoanVolteCarrierGate.java`
  - new: `tests/java/org/joan/ims/TestJoanVolteCarrierGate.java`
  - modified: `ims-service/src/org/joan/ims/JoanDriver.java` (one call-site line),
    `ims-service/src/org/joan/ims/JoanStateProvider.java` (`volte_gate` row),
    `ims-service/AndroidManifest.xml` (0.4.0-alpha19 / 27), `README.md`
    (build row + gate bullet), `tests/run-host-tests.sh` (gate block).
- Packed LOCAL candidates (not committed, not tagged, not on GitHub):
  - `out/joan-volte-recovery.zip`  md5 `7408a7f64146b3b17e0e34ca2fd96809`
  - `out/joan-volte-uninstall.zip` md5 `574a925e668c14211cde28773eca1483`
  - packed APK `classes.dex` md5 `157b1bafd9637e259ed0ca4bc3b355bc`
  - aapt2 badging verified: `org.joan.ims` versionName `0.4.0-alpha19` (27);
    dex contains `volte_gate` / `hide_enhanced_4g_lte_bool` /
    `editable_enhanced_4g_lte_bool` strings.

## What the gate does (verified against code + LOS 22.2 / android-15.0.0_r32 sources)

`JoanVolteCarrierGate.applyIfNeeded(ctx, subId, tm)` — called from
`JoanDriver.discover()` immediately after `SIM_STATE_READY`
(JoanDriver.java:314), i.e. once per driver pass, memoized per subId via
`sAppliedSub`.

Pure `decide(volteAvailable, configApplied, toggleUsable)` matrix
(JoanVolteCarrierGate.java:77-86):

| configApplied | volteAvailable | toggleUsable | decision |
|---|---|---|---|
| false | * | * | `WAIT_CONFIG` (retry next driver pass) |
| true | true | true | `SKIP_ALREADY` — write nothing |
| true | true | false | `APPLY_VISIBILITY` — force toggle visible/editable only |
| true | false | * | `APPLY_FULL` — admit VoLTE + force toggle visible/editable |

- Apply path: ONE `PersistableBundle`, `overrideConfig(subId, bundle, false)`
  via reflection with a 2-arg signature fallback (lines 152-175).
  **Non-persistent** — uninstall zip + reboot restores production values.
  Never persist (the persistent override XML would live in com.android.phone
  and uninstall cannot delete it).
- `hide_enhanced_4g_lte_bool` / `editable_enhanced_4g_lte_bool` are NOT in the
  public SDK jar — addressed by string literal (compiles against android-36).
- Why the visibility force matters: a restored Qualcomm-style carrier cache
  (the Viettel 1899 dump was one) can ship `hide=true`/`editable=false`,
  which VANISHES the Settings toggle and steals the user's opt-out.
- Why the opt-out is real: `GsmCdmaPhone.isImsUseEnabled()` ANDs
  `isVolteEnabledByPlatform()` with `isEnhanced4gLteModeSettingEnabledByUser()`
  before every MO dial; toggle-off stores DISABLED in siminfo → CS dial.
  AOSP default `enhanced_4g_lte_on_by_default_bool=true` ⇒ untouched users
  are ON with zero user-setting writes. The app must never `settings put`
  or write siminfo.
- State row for triage: `volte_gate` = `applied` | `applied-visibility` |
  `skip:already-true` | `wait:config-not-applied` | `fail:<Ex>` | `skip:no-sub`
  (each suffixed with `cid=` where known).

## Verification status (all green at pack time, 2026-09-13 ~21:00 PDT)

- `tests/run-host-tests.sh` gate block **7/7** (wait×3, skip, visibility-only,
  full×2) — run bare, exit code checked separately (skill pitfall).
- Full `project-profile check` green: host / UA / registration / pack lanes.
- Uninstall zip re-packed in the same pass via `pack-cleanup-zip.sh`
  (`pack-zip.sh` does NOT rebuild the uninstall zip — skill pitfall).
- NOT bench-validated. NOT published. Distribution to testers = Lance's lane.

## Questions Lance asked this session + answers given

1. **"'skip:already-true'? Do we need that?"**
   Answer: not strictly needed for correctness (applying would be a
   per-key no-op when the system config already matches), but KEEP it:
   (a) least privilege — a non-persistent override becomes the top config
   layer until reboot, so don't write where the ROM already provides the
   answer; (b) triage signal — `skip:already-true` vs `applied` in the
   state row distinguishes "ROM admitted this" from "Joan forced it";
   (c) cost is four lines, already host-tested. **Correction issued in the
   same answer:** on joan the branch is nearly dead code — joan-common ships
   zero VoLTE bools, so TMUS on the bench will take `APPLY_FULL`, not skip.
   Skip only fires on ROMs/carrier apps that already declare VoLTE.

2. **"Is it going to slow the system down or stall calls at all?"**
   Answer: no. Runs on the `joan-ims-cycle` thread (JoanDriver.java:144-147,
   `Thread.MIN_PRIORITY`), once per boot per SIM: one `getSimCarrierId()`,
   one cached `getConfigForSubId()`, three boolean reads, one
   `overrideConfig()` binder call — low single-digit ms. Afterwards the
   `sAppliedSub == subId` short-circuit is an int compare per 60 s+ driver
   pass. The dial path is untouched: `isImsUseEnabled()` read the merged
   carrier config before this change and reads it after; the override just
   becomes the top merge layer. Media/RTP: zero involvement. Honest caveat:
   the apply fires the standard `ACTION_CARRIER_CONFIG_CHANGED` rebroadcast;
   whether Telephony re-evaluates VoLTE immediately on that broadcast or
   needs one airplane-mode cycle on the FIRST boot after install is an open
   bench question (worst case: one-time airplane toggle on install day).

## Proposed next steps (in order)

1. **Bench on US998 (needs Lance's per-instance go to boot):** standard deploy
   cycle — `adb root` + `adb remount` (remount is lost on every reboot), push
   APK to priv-app, reboot, verify md5 both sides + `dumpsys package`
   versionCode 27. Then:
   - state row shows `volte_gate=applied cid=…` (TMUS → APPLY_FULL expected);
   - `dumpsys carrier_config` shows `carrier_volte_available_bool=true` with
     the override layer;
   - Dialer MO call routes IMS (real routing, not just registration);
   - note whether first apply needs an airplane-mode cycle.
2. **Publish (needs Lance's explicit go):** proven loop — commit on `main`
   with both trailers → annotated tag `v0.4.0-alpha19` → push commit + tag →
   `gh release create --prerelease` with BOTH zips → show exact notes first.
   Recipe: skill ref `references/joan-tester-alpha-github-release-2026-09.md`.
3. **Tester distribution:** Lance sends the Viettel tester the alpha19 tag
   URL (never `/releases/latest` — that is still v0.3.0). Ask for the
   `volte_gate` state row + trace `build=` line in the reply.

## Notes / warnings for the recipient

- Do NOT redistribute alpha13; alpha14/15 superseded as tester zips.
- Do not persist the override; do not write user settings from the app.
- Do not ship `persist.dbg.volte_avail_ovr` in the zip (all-SIM hammer).
- The alpha18 APN merge is already public; alpha19 adds the gate only — keep
  the release notes scoped accordingly.
- Gate behavior depends on `carrier_config_applied_bool` (string literal,
  default true when absent) as the early-boot wait signal; on real LOS 22.2
  verify the reflection `overrideConfig` 3-arg signature exists (2-arg
  fallback is coded but unexercised on device).
- Bench phone was unreachable at handoff time (`adb: device
  'LGUS9986e606d55' not found` via nest) — likely the known US998 long-adb
  USB drop; check cabling before concluding anything else.

## Evidence / sources

- Gate design + framework verification + open items:
  skill `ims-volte-stack-development` →
  `references/joan-alpha19-default-on-gate-2026-09.md` (this session's
  authoritative design doc, Hermes-Agent:zai/glm-5.3 — earlier same-day route).
- Loader merge chain: `references/joan-carrierconfig-overrideconfig-merge-2026-09.md`.
- AOSP gate analysis: `references/joan-aosp-carrierconfig-volte-gate-2026-09.md`.
- Other-ROM admit patterns: `references/joan-lineageos-volte-admit-patterns-2026-09.md`.
- Journal: `~/.hermes/journal/joan-alpha19-default-on-volte-gate-2026-09-13.md`.
- Session evidence: `~/.hermes/workspace/reviews/joan-alpha19-default-on-volte-gate/`.
- Deck: Shared Tasks #110 "Joan IMS tester: Viettel — AES-CBC protected REG2
  timeout" (alpha19 section appended 2026-09-13, Waiting).
