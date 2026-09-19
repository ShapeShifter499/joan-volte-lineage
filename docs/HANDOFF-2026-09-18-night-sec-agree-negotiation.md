# Handoff — sec-agree negotiation rebuilt; six lanes; push pending

Written-by: Fulgor Nymvale (agent-fulgor-zcode), ZCode:zai-coding-plan/GLM-5.3
Date: 2026-09-18 ~21:45 PDT. Session follow to Ember's
`HANDOFF-2026-09-19-cmcc-aka-sa.md` (fd374b4) — read that one first for
the CMCC lane's alpha38 shift; this one covers everything since and the
state of the whole board.

## Repo state — one decision blocks it

`main` locally is **4 commits ahead of origin, unpushed**:

```
82e42b7 joan-ims: negotiate sec-agree the way the reference stacks do   (Fulgor)
fd374b4 docs: handoff for the CMCC lane after alpha38's first field trace (Ember)
8c7784e joan-ims: alpha39, so the bench can prove the registration fix ran
27efd70 joan-ims: re-publish the registration flag on the refresh path
```

Pushing publishes all four. Lance has not called it yet. Likewise
unreleased: **alpha40 (versionCode 50)** — the sec-agree work, built
(`ims-service/build/joan-ims.apk`, md5 `5a596c3a4ef99068599c23e04f9aee3d`)
and **bench-verified** but no GitHub release, and `/releases/latest` still
serves **alpha34**, so testers clicking Latest miss the SIM/EF_DIR work
(alpha38) and the negotiation work (alpha40). Promoting is one
`gh release edit` / `create --latest`.

## What 82e42b7 did and why (the short version)

AOSP's ImsStack (LG's engine, same codebase) selects sec-agree by
`RegParameter::ChoosePreferredSecurityServer`: exact-tuple match of each
Security-Server row against the UE's own offer (mechanism, alg, ealg,
prot, mod, RFC defaults for omissions), highest q, first-listed on ties.
joan had the q rule but no prot/mod check — an AH or tunnel row could be
selected and silently built as transport ESP. The carrier's
`aos_reg_0_ipsec_algs` mask (distilled into assets since the start, read
by nothing — the `xcap_server` shape of gap) is now read: bits are
low{md5, sha1} high{aes, null, 3des}; TMO `0x10003` = aes-only, CMCC
`0x70003` = all, 127/136 profiles `0x70003`. It shapes the
Security-Client offer and gates selection; no profile (-1) = offer
everything, identical to prior behavior. `offered=` in the trace now
annotates every rejected mechanism: `(prot)(mod)(alg)(ealg)(not-offered)
(not-ipsec)(unparsed)`.

**Bench proof**: alpha40 on US998, T-Mobile, `reg2=200 OK`,
`ealg=aes-cbc offered=hmac-sha-1-96/aes-cbc*` — established lane
unchanged. 816 host checks; UA/registration/merge suites green.

**Why it matters for CMCC**: their alpha38 failure is `500 "AKA sync proc
timeout"` then `404 "AKA add SA failure"` with joan on `ealg=null`. With
CMCC's mask live, any aes row their P-CSCF offers is now selectable, and
the annotated `offered=` will say — in one trace — whether they truly
offer null-only or offer aes we never parsed. That trace is the lane's
next step. Full lane state: Deck card **#146**.

## Open items, ranked

1. **Push + release alpha40 + promote Latest** — Lance's call, blocks
   tester progress on every lane (Latest still serves alpha34).
2. **CMCC on alpha40**: get the tester the build, read the annotated
   `offered=`. Operator-side ask already framed (provisioning + ZJ-node
   log pull with timestamp); multilingual research says CMCC core-side
   device gating is real, no public client workaround exists.
3. **UA pre-profile race — found, NOT fixed.** A registration attempt can
   fire before `applyCarrierProfile` runs (subscription/CarrierConfig
   async), sending a User-Agent where the carrier's profile says none —
   observed in the CMCC tester's 09-18 state row (`reg2_hdrs` includes
   User-Agent) while their 09-17 attempts correctly suppressed it. Not
   the CMCC cause (both variants drew the same 404), but a real ordering
   bug: gate first REGISTER on profile application, or apply synchronously.
4. **README's "same handset registers on China Telecom 46011" claim is
   doubtful** (Ember, fd374b4): tester says one SIM. Re-verify or remove;
   it underpins "identity derivation works on a Chinese network".
5. **Remaining RE, low priority**: `aos_reg_0_features` bits 9/11
   (0xA04) unattributed (61 bit-test sites, aos4-only, not in AOSP);
   `ipsec_spi_3gpp=false` semantics (no AOSP consumer; LG binary/Ghidra
   only — project at `~/ghidra-projects/joan-registration-20260906`).
6. **Session robustness**: two background research subagents were lost to
   session crashes with unsalvageable output (EPIPE'd transcripts). Run
   research inline, or have subagents checkpoint findings to disk.

## Tester board (Deck 4, Waiting stack)

| lane | card | state in one line |
| --- | --- | --- |
| CMCC 46002 | #146 | failure moved to named AKA/SA faults; wants alpha40 trace |
| NOS 26803 | #131 | registration FIXED (TCP criterion); wants call-window trace (audio-quiet bug) |
| Viettel 45204 | #133 | registration FIXED; INVITE 400 — reason phrase now logged, wants one outbound call |
| GH #1 H932/TMO | #135 | replied (stale v0.3.0 + caps={} diagnosed); wants alpha-retest; we own an H932 on the nest |
| XDA US998 | #122 | stale v0.3.0 MO-dial-hang; wants current-zip retest |
| GitHub ROM user | (none) | InfinityX 2.9 = Android 15, install fails on 3 MiB-full /system — space, not version; ROM analysis at `~/joan-analysis/infinityx/` |

## Bench and evidence

- Bench: US998 on nym-nest (`ssh nym-nest-family`, `adb`, needs `adb
  root` re-run after reboots; adb auth needs a screen tap when revoked).
  Running alpha40 (50), registered T-Mobile. H932 (`LGH9322d3f3c24`) also
  on the nest, untouched this session.
- Evidence: `~/joan-analysis/garbled-mt-2026-09-18/` (bench call
  forensics + RR-jitter bug), `~/joan-analysis/aosp-ims/tree/` (full
  AOSP ImsStack android-17.0.0_r1 source — grep here before decompiling),
  `~/joan-analysis/gh-issue1/`, `~/joan-analysis/infinityx/`.
- Deck PUT on cards is unsupported on this Nextcloud build — update by
  delete + recreate (title + full description), IDs change each time.
- Fulgor journal: `~/.zcode/journal/joan-audio-handoff-audit-2026-09-07.md`.

## Standing rules that mattered this session

- versionCode MUST bump per bench install — same-code replacements
  silently keep the cached dex (`joan_flash_verification` memory).
- Verify by the state provider's `build` row, never `dumpsys package`.
- Every commit: `Signed-off-by: Lance <Gero3977@gmail.com>` +
  `Assisted-by: <Harness>:<model>` — the hook enforces it.
- LG binary questions: diff against the AOSP source first — LG's aos4 is
  an older fork of the same engine ("diff, don't decompile").
