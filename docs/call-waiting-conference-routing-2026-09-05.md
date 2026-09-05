# Joan IMS — call-waiting swap and conference merge: how LG and the standards route it

Findings behind the alpha10 ACK repair and the conference plan.
Extract-only reverse engineering of the authorized stock binary
(`libims.lge.so`, US998 20h, sha256 `ad1af4d2…`) plus 3GPP/RFC/AOSP
sources. No stock code is shipped; the shipped implementation is
original Java built against public APIs.

## 1. Why the swap stalls: ACK identity, not routing

RFC 3261 has two different ACKs:

| Response | ACK belongs to | Via branch | CSeq |
|---|---|---|---|
| 2xx to INVITE | a NEW in-dialog transaction (§13.2.2.4) | fresh branch | same number as the INVITE |
| non-2xx to INVITE | the INVITE transaction itself (§17.1.1.3) | the INVITE's branch | same number as the INVITE |

A retransmitted final response must be answered with the *exact ACK that
was already built for that transaction* — including the original CSeq
number. Our alpha8 "reuse the dialog branch" logic plus re-ACK-from-
current-dialog broke both halves: it used a stale branch for 2xx ACKs
and could stamp a newer hold/resume CSeq onto a re-ACK for an older
response.

The P-CSCF only stops retransmitting (Timer G/H, up to 64×T1 ≈ 32 s)
when the ACK it receives matches the response it sent. Alpha8's
mismatched ACKs left those transactions "open" from the P-CSCF's view,
so the next re-INVITE (resume) waited behind retransmissions — the
00:31:34 → 00:31:46 stall seen on the bench.

Fix (alpha10, original code):

- `JoanSipBuilder.buildAck2xx()` — fresh Via branch, INVITE CSeq number.
- `JoanSipBuilder.buildAckNon2xx()` — exact INVITE Via branch.
- `JoanSipBuilder.InviteAckArchive` — remembers the exact ACK wire
  bytes per (Call-ID, INVITE CSeq), capped at 16 records with 64 s TTL
  (twice the max Timer G).
- Reply dispatch keys every final response on Call-ID **and** INVITE
  CSeq instead of Call-ID alone, so a retransmitted old 2xx is never
  mistaken for the reply to the newer hold/resume.
- Unknown final responses are logged and left alone — never re-ACKed
  from a guess, because a wrong ACK prolongs the storm instead of
  ending it.

## 2. What LG's stock IMS does (extracted facts)

The stock `libims.lge.so` carries a per-carrier `UCSession` subclass
with a conference state machine; symbols were read with `nm -D` +
`c++filt` and the conference paths disassembled (objdump).

### 2.1 Carrier-specialised conference URIs

One `GetConfURI()` per carrier family, and a generic one that reads a
database value and falls back to a standard URI. The standard
conference factory URI string is literally compiled in:

```
sip:mmtel@conf-factory.ims.mnc%s.mcc%s.3gppnetwork.org
```

so the base implementation derives it from MCC/MNC, exactly as
3GPP TS 24.147 4.3.5.3 describes:

```
sip:mmtel@conf-factory.ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org
```

Observed carrier overrides of `GetConfURI()`: TMUS, VZW, ATT, CMCC,
Global, SBM, Canada, KDDI, USC, LGUP, SKT, SPR. The stock T-Mobile
path additionally has `TMUSUCSession::GetCCAConfURI()` — a
T-Mobile-specific fallback when the factory URI is empty.

### 2.2 The conference state machine is network-centric

`UCSessionConfig` exposes the route model via getters (`nm -D`
symbols):

```
IsConferenceSupportLocal(unsigned int)      // is a local mixer ever used?
IsConfSub / IsReferSub / IsConfSubInDialog  // subscription styles
IsSupportCreatorUser / IsPreconditionRemoteConf / IsTIP
GetConfType_START / _EXPAND / _MERGE
GetREFERType_START / _EXPAND / _MERGE / _JOIN / _DROP
GetMultipleREFERSendType
GetCWType(unsigned int)                     // call-waiting strategy
GetMaxSess(unsigned int)                    // max simultaneous sessions
CheckConfDocVers(unsigned int)              // conference-info version
```

The flow: a conference is created through a *conference factory URI*
(dial the factory, or a session established with the factory), the
network hosts the mixer, other participants are added with REFER, and
the phone tracks state with the conference-info event package
(NOTIFY subscriptions). `ConferenceMngr`, `ConferenceRefer`,
`ConferenceSubMngr` and `ConferenceInfo` symbol groups are the four
moving parts; `StateIDLE_StartConf / StateIDLE_ExpandToConf /
StateIDLE_MergeConf` are the entry states.

`TMUSUCSession::StateIDLE_MergeConf()` (disassembled) does exactly one
network-relevant thing before delegating to the base `UCSession`
handler: it records a T-Mobile analytics metric
(`TMUSUCStats::CreateDraIdOutgoing`) and notifies call state. The
actual merge work is in the base class — a wrapper of stats, not
carrier-specific SIP.

### 2.3 What we did NOT do

No decompilation, no runtime loading, no shipping of LG code, no
reCAPTCHA/site circumvention, no device flashing. Static, read-only
analysis of the already-extracted file only.

## 3. What AOSP expects

`MmTelFeature.createCallSession()` for `ImsCallProfile.SERVICE_TYPE_CONFERENCE`
returns a conference session. AOSP telephony then drives it with
`hold(ImsStreamMediaProfile)` on existing calls, then `merge()` on the
conference session (Android 14+; `ImsPhoneCallTracker.conference()`
and its `processMergeComplete` logic wait for
`callSessionConferenceStateUpdated` on the conference session). The
session is the network-hosted conference, not a mixer: AOSP calls
`callSessionMergeStarted/MergeComplete/MergeFailed`, and
`ConferenceState.CALL_STATE_*` notifications keep the UI in sync.

## 4. Two-lane implementation plan

### Lane A — alpha10 (now, fixes call waiting swap)

1. Transaction-keyed ACK repair as in §1 (implemented, 241 host tests
   green including the new ACK-transaction tests).
2. Bench verification on the US998: repeated hold → second call →
   accept (park) → resume swaps, plus held-leg retransmission storms.

### Lane B — conference merge (separate, gated)

Do it the stock way, not a local mixer:

1. Derive the factory URI: `sip:mmtel@conf-factory.ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org`
   (TS 24.147) — the same rule LG's base implementation uses.
2. `JoanMmTelFeature.createCallSession()` returns a conference session
   for `SERVICE_TYPE_CONFERENCE`.
3. Create a dialog to the factory URI (new outgoing call to the
   factory); the network answers with a hosted conference.
4. Implement REFER-with-Replaces for each participant leg
   (`Refer-To: <conf-id>?Replaces=<dialog>;method=INVITE`), matching
   stock's REFER model.
5. Implement the conference-info event package (SUBSCRIBE/NOTIFY) to
   track participants — the `ConferenceSubMngr`/`ConferenceInfo`
   equivalent.
6. Drive AOSP callbacks: `callSessionMergeStarted/Complete`,
   `callSessionConferenceStateUpdated` so the Dialer's "merge" button
   works end to end.
7. Gate by carrier capability, like stock: TMUS merge uses the factory
   path (`GetCCAConfURI` fallback), CMCC uses `GetConfURI` with the
   standard factory URI. Never mix legs locally; the network owns the
   bridge.

Scope notes: this needs a new SIP transaction type (SUBSCRIBE/NOTIFY
handling), an outgoing dialog to a URI rather than a TEL number, and
the conference session callback contract. It is deliberately NOT part
of the alpha10 ACK repair.

## 5. Sources

- RFC 3261 §13.2.2.4 (2xx ACK as new request) and §17.1.1.3 (non-2xx ACK in-transaction) — rfc-editor.org
- 3GPP TS 24.147 §4.3.5.3, §5.3.1.3.2 (conference factory URI, creation flow) — ETSI deliverable
- AOSP ImsCallSessionImplBase / ImsCallSessionListener / ImsPhoneCallTracker — android.googlesource.com
- `libims.lge.so` (US998 20h, sha256 `ad1af4d2…`) — exported symbol table and
  objdump of the conference paths (extract-only)
