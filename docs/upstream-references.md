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

## AOSP ImsMedia (`packages/modules/ImsMedia`)

- Source: <https://android.googlesource.com/platform/packages/modules/ImsMedia>
- Revision consulted: tag `android-17.0.0_r1`
- Licence: Apache-2.0. Reference only; nothing copied.

**The stack is split, and the split matters.** `ImsStack` owns SIP, SDP and
negotiation; `ImsMedia` owns RTP, RTCP and codec transport. Reading only
the first led to "AOSP has no X" conclusions about media that were really
"X is in the other repo" -- notably RTCP, where ImsStack has no transport
at all. Look in ImsMedia for anything below SDP.

Two of our RTCP decisions have a reference behind them:

- **A separate RTCP socket.** `AudioSession` holds `mRtpFd` and `mRtcpFd`
  as distinct descriptors and builds a whole separate
  `AudioStreamGraphRtcp(this, mRtcpFd)`. Our non-muxed RTP+1 bind is the
  same shape.
- **Receiver reports are diagnostic, not control.** For audio,
  `RtcpDecoderNode` answers an RR with `SendEvent(kCollectPacketInfo,
  kStreamRtcp)` and nothing else. The only loss-driven bitrate change in
  the file is behind `#ifdef DEBUG_BITRATE_CHANGE_SIMULATION` and video
  only. Adaptation is ANBR's job, which is why we log loss and do not act
  on it.

Where they go further: `RtcpXrEncoder.cpp` actually produces RTCP-XR
blocks, which is why ImsStack negotiates `a=rtcp-xr`; and `RtcpConfig`
carries a configurable `intervalSec` where we hardcode five seconds. Both
are reasons NOT to advertise `a=rtcp-xr` until something here emits the
blocks -- the same mistake as claiming mode-change-capability, AMR-NB or
octet-align support we did not have.

DTMF as RTP events follows the same two files, and four details came
from reading them rather than from RFC 4733 alone:

- **The event timestamp is frozen at the first packet.**
  `RtpEncoderNode::ProcessAudioData()` latches `mDtmfTimestamp` on the
  marked packet and passes that same value to `SendRtpPacket` for every
  packet of the tone; only the duration field grows. We latch `sDtmfTs`
  the same way and keep the audio clock advancing underneath.
- **Audio is suppressed for the duration.** The same function will not
  send `MEDIASUBTYPE_RTPPAYLOAD` while `mDtmfMode` is set. Our capture
  loop drops the frame's audio when an event is in flight, for the same
  reason: a speech codec's rendering of the tone underneath the event is
  noise to whatever is decoding the digit.
- **A 40 ms floor on tone length**, forced in
  `DtmfEncoderNode::calculateDtmfDuration()` before any packet is built.
- **The end packet is retransmitted**, as a 40 ms window in
  `SendDTMFEvent()` -- three packets at a 20 ms frame, which is what
  RFC 4733 s2.5.2 asks for and what we send.

Their default duration (200 ms) and volume (10) are the values we use.

## AOSP framework IMS (outside `packages/modules`)

`packages/modules` carries exactly two IMS repos, ImsStack and ImsMedia,
and reading only those left a gap: the code that *drives* an ImsService
lives elsewhere in the tree. Three more repos, all at
`android-security-17.0.0_r1`, all Apache-2.0, reference only:

- `frameworks/base`, sparse at `telephony/java/android/telephony/ims/` --
  the SystemApi surface our `ims-service/stubs/` imitate.
- `frameworks/opt/net/ims` -- `com.android.ims.ImsCall`, the wrapper that
  forwards framework calls into our session.
- `frameworks/opt/telephony` -- `ImsPhone`, `ImsPhoneCallTracker`,
  `ImsPhoneConnection`: the state machine that decides *when* our
  overrides are called and what it expects back.

What reading them settled:

- **Our stub signatures are right, not merely plausible.** `sendDtmf(char,
  Message)`, `startDtmf(char)`, `stopDtmf()` and `callSessionNotifyAnbr(int,
  int, int)` match the real `ImsCallSessionImplBase`, and
  `MEDIA_STREAM_DIRECTION_UPLINK` is 1 -- which is the constant our ANBR
  handler compares against, previously assumed.
- **The DTMF Message is a pacing signal, not an ack.**
  `ImsPhoneConnection.processPostDialChar()` sends one digit, waits for
  that Message, waits `mDtmfToneDelay`, then sends the next. Answering it
  at queue time drops digits out of post-dial strings; see the commit
  that moved the callback to tone completion.
- **Session timers are carrier configuration, not a constant.**
  `ImsStack/core/config/CarrierConfig.java` reads
  `CarrierConfigManager.ImsVoice.KEY_SESSION_TIMER_SUPPORTED_BOOL`,
  `KEY_SESSION_EXPIRES_TIMER_SEC_INT`,
  `KEY_MINIMUM_SESSION_EXPIRES_TIMER_SEC_INT` and
  `KEY_SESSION_REFRESHER_TYPE_INT`. Those keys exist on LineageOS 22, so
  our RFC 4028 support should read them rather than pick numbers.
- **Local IP change is detected in Java and acted on below it.**
  `Apn.ImsNetworkCallback.onLinkPropertiesChanged()` compares the cached
  link addresses, separates an IP change from a P-CSCF change, and raises
  `EVENT_IP_CHANGED` -> `EDataState.DATA_STATE_IP_CHANGED`. The response
  is in the native stack, so AOSP gives us the detection pattern and not
  the recovery.

### Session timers, as implemented here

The five keys ImsStack reads are the ones we read, with AOSP's own
defaults as fallbacks (CarrierConfigManager's ImsVoice block): timers
supported, 1800 s, Min-SE 90 s, refresher=uac, UPDATE preferred. Picking
our own numbers would have meant a carrier asking for a 600-second
session getting 1800, and a refresh arriving after its core had already
dropped the dialog.

Two behaviours also come from reading that code rather than from
RFC 4028 alone: the refresh method is a carrier preference that still
has to yield to the peer's Allow (a peer sent an UPDATE it rejected
turns every refresh into a 405), and the values are re-read on
ACTION_CARRIER_CONFIG_CHANGED because com.android.phone can restart
under a running call.

### Local IP change, and where we deliberately differ

`Apn.ImsNetworkCallback.onLinkPropertiesChanged()` is the right callback
and we watch the same one, but AOSP's `isIpChanged()` compares the whole
cached address set and calls any difference an IP change. We ask a
narrower question: is the ONE address our sockets are bound to still on
the link? A link that gains an address, or loses one we never used, has
invalidated nothing, and re-registering for it would drop a working
call. A link that no longer carries ours has invalidated the SIP
sockets, both IPsec SAs and the RTP socket at once.

AOSP's own recovery is in its native stack (EVENT_IP_CHANGED ->
EDataState.DATA_STATE_IP_CHANGED, consumed across SystemCallInterface),
so what we do afterwards has no upstream to follow.

### Registration event package

Two things from ImsStack shaped ours.

- **The URI check is known-unreliable.**
  `CarrierConfig.KEY_USE_REGINFO_CONTACT_WITHOUT_URI_CHECK_BOOL` is a
  per-carrier switch to accept a reginfo contact *without* matching its
  URI. Its existence is the evidence: some networks send a contact that
  cannot be matched against the Contact we registered. We keep strict
  matching as the default -- accepting any contact lets a second handset
  on the same public identity deregister this one -- but the trace now
  distinguishes "no contacts at all" from "contacts, none ours", because
  those are the two sides of that switch and a bare "unknown" cannot
  tell them apart.
- **Nothing else is in Java.** ImsStack has no reginfo parser; the body
  is handled in the native stack and only this config key crosses the
  boundary. So the parsing itself has no upstream to copy, which is why
  ours is a small tolerant scanner with its own tests rather than a port.

Our first version was also wrong in a way worth recording: namespace
prefixes. `<reg:reginfo>` is not `<reginfo>`, and the same blind spot sat
in three places -- the root-element guard, the contact finder and the
closing-tag search. None of them threw; they found no contacts, which
reads exactly like a network that said nothing about us.

### Adopting ImsMedia rather than reading it

Evaluated 2026-09-16; see `docs/imsmedia-evaluation-2026-09-16.md`. The
short version: the AP-side path is the default, the API links statically
into an app, and `openSession()` takes the RTP and RTCP sockets from the
caller -- so our IPsec-bound sockets are not an obstacle. What blocks it
is `sharedUserId="android.uid.phone"` plus `certificate: "platform"`,
which the flashable zip cannot satisfy on a release-keys ROM -- so the
zip cannot SHIP the service. It can still USE one a ROM provides:
`USE_IMSMEDIA` is `signature|privileged`, and we are already a
privileged app with an allowlist. The jitter buffer we lack entirely is
the part worth porting first, because it is the only thing that helps a
zip install on a stock ROM.

## LG IMS (reverse engineered)

- `docs/lg-ims-fullstack-re-2026-09-05.md`,
  `docs/lg-ims-carrier-extract-2026-09-04.md`
- Used as: carrier-behaviour facts (TCP criterion lengths, per-carrier
  config values) transcribed into our own profile format.
- Boundary, unchanged: extract-only, no LG code or blobs shipped.
