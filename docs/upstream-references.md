# Upstream references

What this port consulted, where, and what it changed. The rule is the one
the RE work already follows: **reference only, nothing copied**. If code is
ever actually derived from another project, it gets its own entry here
naming the licence and the files, and the commit body says so.

Each entry records the source at a *pinned* revision, because "I read it on
the web" is not reproducible and a moving branch is not a citation.

## AOSP IMS stack (`packages/modules/ImsStack`)

- Source: <https://android.googlesource.com/platform/packages/modules/ImsStack>
- Revision consulted: tag `android-17.0.0_r1`
- Licence: Apache-2.0 (same as this repo)
- Used as: corroborating reference for two diagnoses. **No AOSP code is
  copied into this tree**, and no AOSP file is shipped.

### 1. Via sent-protocol must match the transport actually used

`native/libimsstack/engine/sipcore/SipServerTransport.cpp`,
`SipServerTransport::ValidateViaHeader()`:

> If the topmost Via header has scheme SIP/2.0/TCP, but actually came on
> UDP, (or vice versa) flag off error. Application SHOULD respond to this
> with 400 "Bad Request".

This is the receiving half of the Viettel MO-call failure: `JoanSipUa`
transmits every outbound request on its UDP client socket, while a global
flag set by an *inbound* TCP accept rewrote the outgoing Via to claim TCP.
The P-CSCF answered 400 Bad Request on every MO INVITE once any MT call had
arrived. AOSP turned this from a strong reading of RFC 3261 18.1.1 into a
confirmed diagnosis, and it names the exact status code we were seeing.

Fixed in `JoanSipBuilder.requestViaTransport()`; regression pinned by
`invite-via-claims-tcp-but-sends-udp` in the UA suite.

### 2. AKA synchronisation failure (0xDC) is not a parse error

`native/libimsstack/platform/os/android/device/OsUsim.cpp`,
`OsUsimDigestAka::OnResponse()` splits the card's answer on the leading
tag: `0xDB` is RES/CK/IK, `0xDC` is AUTS, anything else is a MAC failure.
Ours collapsed every non-`0xDB` answer into `FAIL: aka parse`, so a card
asking for an SQN resync read as a broken parser.

`native/libimsstack/engine/sipcore/SipAuHelper.cpp` carries the two rules
the resync REGISTER needs, for when it is written:

- the `auts` parameter is quoted base64 (`STR_AUTS`);
- "when the AUTS is present, the included `response` parameter is
  calculated using an empty password (password of ""), instead of a RES"
  — matching RFC 3310 3.2.

Both landed: `JoanAka.isSyncFailure`/`autsBytes` split the tags, and
`JoanAppRegister` sends one resynchronisation REGISTER on the transport
that already produced the challenge, requires a *new* nonce back before
touching the card again, and allows the resync exactly once. The empty
password falls out of `digestPassword()` returning RES unchanged for
AKAv1-MD5, so an empty RES **is** the empty password.

Not verifiable offline: the HSS round trip. The message shape is pinned by
host tests (`resync REGISTER carries quoted base64 auts`, `resync response
uses an empty password, not a RES`, and the Security-Client/Verify pair);
whether a real HSS accepts it is a tester result, not a test result.

### 3. VoWiFi: the ePDG tunnel is not the IMS stack's job

Searched at the same tag, and the *absence* is the finding. The module has
119 `IWLAN` references, 74 `WFC`, 23 `ePDG` -- and **zero** `IkeSession`.
It never builds a tunnel. What it actually does:

- `platform/interface/INetworkConnection`: `IsePDGEnabled()` is a *query*.
  The stack asks whether an ePDG connection exists; something else made it.
- `core/agents/WifiAgent.java`: a plain `NetworkRequest` for
  `TRANSPORT_WIFI`. No tunnel setup, no ePDG discovery.
- `imsservice/mmtel/internal/WfcSettingTracker.java`: WFC mode state only
  (`WIFI_ONLY` / `WIFI_PREFERRED` / `CELLULAR_PREFERRED` via
  `ImsMmTelManager`), gated on `ServiceCaps.isWfcEnabledByPlatform`.
- `IsePDGEnabled()` feeds `P-Access-Network-Info` (see
  `PAccessNetworkInfoHeaderTest.cpp`), so the ePDG state changes PANI --
  `IEEE-802.11` instead of `3GPP-E-UTRAN-FDD`.

The tunnel lives in a separate AOSP component,
<https://android.googlesource.com/platform/packages/services/Iwlan> (repo
confirmed to exist; pure-Java DataService, Apache-2.0), which builds the
IKEv2/IPsec ePDG tunnel with the **public** `android.net.ipsec.ike` API.

This revises the alpha12 line in `lg-ims-fullstack-re-2026-09-05.md`, which
plans an "own IKEv2 client + ePDG discovery". AOSP's answer is: do not
write one. The remaining joan-side work is the gate named in
`vowifi-feasibility-2026-08-29.md` -- our `ImsService` advertising
`REGISTRATION_TECH_IWLAN`, the WFC CarrierConfig keys, binding the UA to
the tunnel network, and taking the P-CSCF from the IKE config payload
instead of PCO.

**Not yet verified:** that AOSP `Iwlan` runs on joan without the Qualcomm
`vendor.qti.hardware.data.iwlan` HAL. The feasibility doc measured that
vendor path dead (`pidof vendor.qti.iwlan` -> not running, no `*epdg*` or
`*ike*` under `/vendor`). AOSP `Iwlan` is designed to be independent of it,
but that is a reading of the architecture, not a measurement on this
device. Prove it before planning around it.

### 4. One Call-ID and a rising CSeq for the whole REGISTER series

`native/libimsstack/engine/registration/Registration.cpp` keeps both across
failures, and says why in the code:

> Do not check the status code to support re-use of Call-ID & CSeq number
> when the registration is failed

The `if (nStatusCode == SipStatusCode::SC_423)` guard above that block is
commented out precisely so the re-use is unconditional.

Ours minted a fresh Call-ID and restarted CSeq at 1 for **every attempt**,
and for each of the two P-CSCF candidates inside one attempt -- so a
registrar saw a burst of unrelated registrations rather than retries of one.
RFC 3261 10.2 asks for a single Call-ID per registrar. Now held in
`JoanAppRegister.RegSeries`, keyed on the private identity, in memory only
(a CSeq that restarts under a Call-ID already used would be rejected, and a
fresh Call-ID after a restart is always safe).

### 5. Codec negotiation: the offerer's order decides, both directions

`enabler/media/BaseNego.cpp` dispatches on offer/answer **state**, not call
direction -- `STATE_IDLE`/`STATE_NEGOTIATED` go to `NegotiateOffer()` (an
offer arrived, we answer), `STATE_OFFER_SENT` to `NegotiateAnswer()` (the
answer to ours arrived). Both reach the same
`m_pProfileNegotiator->Negotiate(local, peer, ...)`, so one negotiator
serves MO and MT alike.

`enabler/media/audio/AudioProfileNegotiator.cpp` iterates the **peer's**
payload list in the peer's order and matches each entry against local
capability. That is the rule we adopted: the offerer proposes an order,
local capability disposes, and the answer echoes the offerer's payload
number rather than our own.

Two places we deliberately differ, both because our AMR path has never
executed on a real call:

- **octet-align.** AOSP in strict mode imposes the *local* value and always
  emits it when it is 1 (`NegotiateAmrFmtp`), with
  `bAmrPayloadFormatRelaxedMatching` as a carrier knob to keep the peer's
  instead. We skip an AMR entry whose offer lacks `octet-align=1` and
  continue down the list, because asserting our requirement relies on the
  peer changing framing to suit us.
- **mode-set** and the `mode-change-*` parameters: AOSP negotiates them, we
  ignore them.

AOSP has **no device-capability gate anywhere in this path**. There is not
one `MediaCodecList`, `getCodecInfos` or `MediaCodecInfo` in the module;
every codec value comes from `CarrierConfig::Ims*::KEY_*`. `ImsMediaManager`
is not the gate it sounds like either -- every use is `setCodecType(...)`,
writing a decision into an `AudioConfig` and handing it to the session, with
`getCodecType()` only reading back what they set. It is a sink, not an
oracle, and never advertises what the ROM can run.

That works for an OEM integration, where carrier config and the hardware
media stack agree by construction. It does not work for us: we are an app
on arbitrary LineageOS builds using general-purpose MediaCodec, with no
integration contract. So `JoanSipBuilder.restrictProfile()` narrows the
profile at startup from `JoanAmrCodec.availableAmr()`, requiring both an
encoder and a decoder. **This is a deliberate divergence, not a copy.**
Offering a codec the device cannot open is worse than not offering it: the
carrier selects it, the encoder fails, the media layer falls back to PCMU,
and the peer keeps sending AMR.

One value worth knowing: `CodecAmrConfig::DEFAULT_PAYLOAD_FORMAT` is
`BANDWIDTH_EFFICIENT`. AOSP's default AMR packing is the framing JoanAmr
does not implement, so on a network following that default our octet-align
gate falls through to PCMU every time. That, rather than the negotiation,
is what keeps AMR from running -- recorded under "Not in this zip".

Structural gap still open on our side: AOSP builds its *offer* from carrier
configuration (`AudioProfileGenerator` with `AudioConfiguration`,
`CodecAmrConfig`, `CodecEvsConfig`), so it offers AMR-NB, EVS, mode-set and
ptime per carrier. Ours is a fixed string offering only AMR-WB and PCMU --
no AMR-NB, which IR.92 mandates. That is a plausible reason T-Mobile skips
our AMR-WB and answers G.711, which in turn is why AMR has never run here.

### What they do NOT do, corrected

An earlier pass of this file listed four "divergences where AOSP is more
correct". Two of them do not survive reading their code, and the record
matters more than the tidy list:

- **Retry-After on a failed REGISTER**: AOSP does not honour it. The only
  uses are *emitting* it as a server when rejecting a request
  (`SipServerTransactionState.cpp`); there is a `SipRetryAfterHeader`
  parser, but the registration path never consults it. The earlier claim
  was inferred from the existence of a header test, which proves nothing
  about the policy.
- **423 / 403 special-casing**: absent, and deliberately so per the comment
  quoted above.

A third is narrower than stated: AOSP's refresh is not unconditionally sent
inside the existing SA either -- `Registration::RefreshCompleted` handles a
401/407 on a refresh and calls `RespondToChallenge`, so a challenged
refresh is a normal path there too, as it is here.

The fourth, a reg-event `SUBSCRIBE` (`RegSubscription.cpp`), is real and
still missing here, but it is a feature to build rather than a divergence
to correct.

## LG IMS (reverse engineered)

- `docs/lg-ims-fullstack-re-2026-09-05.md`,
  `docs/lg-ims-carrier-extract-2026-09-04.md`
- Used as: carrier-behaviour facts (TCP criterion lengths, per-carrier
  config values) transcribed into our own profile format.
- Boundary, unchanged: extract-only, no LG code or blobs shipped.
