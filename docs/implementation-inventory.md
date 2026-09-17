# Implementation inventory

What this port implements, where each piece lives, and which
specification it answers to. Provenance -- what was consulted, what was
derived, and what was reverse engineered -- is in
[`upstream-references.md`](upstream-references.md); this file is the
capability view, and the README carries the per-release changelog.

Everything here is AP-side. There are **no vendor blobs**, no IMS daemon,
and no modem-side IMS: the stack registers and carries calls from an
ordinary (privileged) Android app, doing sec-agree IPsec through the
public `IpSecManager` API.

## The shape of the stack

**Registration and security**

| file | role |
| --- | --- |
| `JoanDriver` | Registration driver: watches the IMS network and the radio, decides when to register |
| `JoanAppRegister` | REGISTER over `IpSecTransform`, and the REGISTER series (one Call-ID, rising CSeq) |
| `JoanAka` | ISIM AKA on the device: AUTHENTICATE against the card, split `0xDB` / `0xDC` |
| `JoanSipCrypto` | Carrier-neutral Digest AKA (RFC 3310 / RFC 4169) |
| `JoanSecAgree` | `ipsec-3gpp` parse and selection (RFC 3329) |
| `JoanRegInfo` | `reginfo+xml` body of a reg-event NOTIFY (RFC 3680) |
| `JoanRegistration` | Registration state surfaced to telephony / Dialer |

**SIP and call control**

| file | role |
| --- | --- |
| `JoanSipUa` | In-app SIP UA; keeps the IPsec sockets after REGISTER 200 so INVITE rides them |
| `JoanSipBuilder` | Request/response construction, SDP offer and answer |
| `JoanSessionTimer` | Session timers (RFC 4028), as pure policy |
| `JoanCallSession` | One IMS call session; callback sequence matches AOSP |

**Media**

| file | role |
| --- | --- |
| `JoanMedia` | Capture and playback threads; the RTP send/receive path |
| `JoanJitter` | Adaptive jitter buffer and RFC 3550 reception statistics |
| `JoanRtcp` | RTCP sender/receiver reports (RFC 3550 6.4) |
| `JoanAmr` | AMR / AMR-WB payload format (RFC 4867), both framings |
| `JoanAmrCodec` | AMR encode/decode over `MediaCodec`, framed for 20 ms |
| `JoanDtmf` | DTMF as RTP events (RFC 4733) |

**Framework surface**

| file | role |
| --- | --- |
| `JoanImsService` | The binder entry telephony binds to |
| `JoanMmTelFeature` | Capability surface Dialer and telephony query |
| `JoanVolteCarrierGate` | VoLTE admit gate; makes platform VoLTE available per carrier |
| `JoanImsVoiceConfig` | The carrier's IMS voice knobs from `CarrierConfigManager` |
| `JoanCarrierProfile` | Carrier behaviour profile distilled from stock LG Ims6 configuration |

**Diagnostics** -- `JoanTrace`, `JoanStateProvider`, `JoanImsDiscovery`,
`JoanImsDiagnostics`, `JoanXfrmStats`. These are deliberately
identity-free: `JoanXfrmStats` is key-free by construction, and
`JoanImsDiscovery` / `JoanImsDiagnostics` each carry a note that they
report the *framework's* view and not a raw modem PCO capture, because
conflating the two produced a wrong diagnosis once already.

## Specifications implemented

| spec | what | where |
| --- | --- | --- |
| RFC 3261 | SIP core; Call-ID/CSeq reuse across a REGISTER series; 3xx; 503 `Retry-After` | `JoanAppRegister`, `JoanSipUa`, `JoanSipBuilder` |
| RFC 3262 | PRACK (answered) | `JoanSipUa` |
| RFC 3264 | Offer/answer; direction mirrored rather than always `sendrecv` | `JoanSipUa`, `JoanSipBuilder` |
| RFC 3310 | Digest AKAv1-MD5, including AUTS resync with an empty password | `JoanAka`, `JoanSipCrypto`, `JoanAppRegister` |
| RFC 3311 | UPDATE | `JoanSipUa`, `JoanSipBuilder` |
| RFC 3329 | Security-Client / Security-Server / Security-Verify, `ipsec-3gpp` | `JoanSecAgree`, `JoanSipBuilder` |
| RFC 3515 | REFER | `JoanSipBuilder`, `JoanSipUa` |
| RFC 3550 | RTP/RTCP; SR **and** a real reception report block; interarrival jitter | `JoanJitter`, `JoanRtcp`, `JoanMedia` |
| RFC 3551 | Static payload types | `JoanSipBuilder` |
| RFC 3556 | `b=AS` / `b=RS` / `b=RR` session bandwidth | `JoanImsVoiceConfig`, `JoanSipBuilder` |
| RFC 3680 | Registration event package; own binding matched by `+sip.instance` | `JoanRegInfo`, `JoanSipBuilder` |
| RFC 3891 | `Replaces` | `JoanSipUa` |
| RFC 3892 | `Referred-By` | `JoanSipBuilder` |
| RFC 4028 | Session timers, from carrier config, with 422 retry | `JoanSessionTimer`, `JoanSipUa`, `JoanSipBuilder` |
| RFC 4169 | Digest AKAv2 | `JoanSipCrypto` |
| RFC 4488 | `Refer-Sub` | `JoanSipBuilder` |
| RFC 4566 | SDP, including `b=` ordering between `m=` and `a=` | `JoanSipBuilder` |
| RFC 4579 | Conference control (focus INVITE) | `JoanSipBuilder`, `JoanSipUa` |
| RFC 4733 | `telephone-event` DTMF, sent and recognised on receive | `JoanDtmf`, `JoanCallSession`, `JoanMedia`, `JoanSipUa`, `JoanSipBuilder` |
| RFC 4867 | AMR / AMR-WB, octet-aligned **and** bandwidth-efficient; FT=8 SID, FT=14 SPEECH_LOST | `JoanAmr`, `JoanAmrCodec`, `JoanSipBuilder` |
| RFC 5761 | `rtcp-mux` | `JoanRtcp`, `JoanMedia` |

**3GPP:** TS 23.003 (identity derivation, 13.3/13.4), TS 24.229 (IMS
call control; 5.1.1.3 for the reg-event subscribe), TS 24.147
(conferencing), TS 31.102 / TS 31.103 (USIM / ISIM files), TS 33.203
(access security).

## Deliberately not implemented

Each of these is a decision, not an oversight, and the README's "Not in
this zip" section gives the reasoning:

- **SMS/MMS over IMS.** Not implemented **and not advertised** -- the
  Contact header carries `+g.3gpp.icsi-ref=...mmtel` and never
  `+g.3gpp.smsip`, so the network keeps delivering SMS over CS/SGs.
  Advertising a capability we do not have would silently break texting.
- **EVS.** Carriers offer it first; LineageOS 22 ships no EVS encoder.
  The codec profile is probed from `MediaCodecList` at startup, so a
  future ROM that adds one gets it offered with no code change.
- **`a=rtcp-xr`.** Not advertised, because nothing here emits XR blocks.
- **Emergency calling, video (VT), RTT, Ut/XCAP supplementary services,
  VoWiFi.** See the README and `docs/vowifi-feasibility-2026-08-29.md`.

The common rule: **do not advertise a capability the stack does not
have.** That principle was learned from claiming `mode-change-capability`,
AMR-NB and `octet-align` support that did not exist, and it is why the
SMS-over-IMS feature tag is absent -- which is also what proves this
stack is not the cause of the visual-voicemail activation failure
documented in the README.
