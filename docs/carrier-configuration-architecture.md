# Carrier configuration: three sources, and which one answers what

How joan decides per-carrier IMS behaviour on LineageOS 22.2, why there
are three sources rather than one, and what each is actually good for.
Written 2026-09-16 after comparing AOSP's ImsStack at
`android-17.0.0_r1` against LG's shipping stack from the H932 KDZ.

## The short version

| source | what it gives | readable on LOS 22.2? |
| --- | --- | --- |
| AOSP ImsStack `carrier_config.xml` | 491 keys with **defaults**, Apache-2.0 | as a document, **not** at runtime |
| LG `configuration.<OP>.<CC>.xml` | 164 carriers x 988 **per-carrier values** | transcribed into our own assets |
| Platform `CarrierConfigManager` | ~160 public IMS keys, live | **yes**, and we read it today |

Defaults from the first, per-carrier deltas from the second, runtime
overrides from the third.

## AOSP ImsStack (android-17.0.0_r1)

`java/assets/carrier_config/carrier_config.xml` defines **491** keys
across seven namespaces:

```
ims 164   imsvoice 164   imsemergency 63   imsvt 40
imssms 28   imswfc 24   imsrtt 8
```

Per-carrier values are selected by **Android carrier id**, with
inheritance -- `ims.parent_carrier_ids_int_array` -- rather than by PLMN.
That is a better key than a PLMN: one operator can hold dozens of PLMNs,
and carrier id already collapses them.

**But AOSP ships no per-carrier values through it.** The asset directory
holds one file, and that file is the defaults: no carrier-id blocks, no
China Mobile entry, nothing to extrapolate from. AOSP defines the shape
and the default and leaves the values to carriers and OEMs. Checked
directly, because the mechanism's existence reads like the presence of
data and is not.

**But only 160 of those keys are in the public `CarrierConfigManager`
API, and the overlap with ImsStack's set is three keys.** The other 488
are internal to ImsStack, and **LineageOS 22.2 does not ship ImsStack at
all** -- it arrived after Android 15. So on this platform they have no
provider and cannot be read, whatever their names suggest.

What the file is still worth: it is an Apache-2.0 **specification of
which IMS behaviours a modern stack considers carrier-variable**, with a
sane default for each. When joan has to choose a default, this is the
citation. Examples that bear directly on our code:

```
false  ims.allow_sip_p_access_network_info_header_in_initial_register_bool
false  ims.allow_sip_udp_fallback_on_tcp_connection_setup_failed_bool
true   ims.block_pcscf_on_reg_failure_bool
false  ims.use_tcp_transport_for_register_bool
true   ims.require_sip_expires_header_in_register_bool
false  ims.sip_compact_form_enabled_bool
```

joan diverges from the first three. All three were examined on
2026-09-17 and each resolved differently -- see "Three divergences from
AOSP's defaults, resolved".

## LG Ims6 (H932 KDZ)

`/product/priv-app/Ims6/Ims6.apk` carries LG's **world** carrier set:
169 `configuration.<OP>.<CC>[.<VARIANT>].xml` files, 988 parameters
each, plus `assets/SmartConfiguration/SmartConfiguration.xml` whose
`mccmnc_list` maps **308 PLMNs** to operators.

This is the only source that gives **actual per-carrier values** for
networks we cannot test. It is proprietary, so the boundary is the one
this repo already follows: extract-only, values transcribed into joan's
own format, no vendor file shipped.

- `tools/make-carrier-profiles.py` -> `ims-service/assets/carrier-profiles.json`
- `tools/make-plmn-map.py` -> `ims-service/assets/carrier-plmn-map.json`

294 PLMNs resolve to 136 profiles. `tests/carrier/run-carrier-tests.sh`
keeps the two consistent.

It is also **older** than AOSP's set and pre-dates 5G/VoNR, so where the
two disagree on a default, AOSP wins; LG is authoritative only for
"what does *this carrier* want".

## Platform CarrierConfigManager (what actually runs)

The live, supported surface on LineageOS 22.2. joan reads:

- `JoanImsVoiceConfig` -- session timers (RFC 4028) and RFC 3556
  bandwidths
- `JoanCodecConfig` -- `imsvoice.*` payload types, AMR framing per
  payload type, and mode-set

This is the layer to prefer whenever a key exists, because the carrier
can change it without us shipping anything.

## How a PLMN becomes behaviour today

1. `JoanDriver` reads the SIM operator.
2. `JoanCarrierProfile.forNetwork()` resolves PLMN -> key via
   `carrier-plmn-map.json`, falling back to the hand-written
   `carrierKey()` for anything unmapped.
3. The profile's values are pushed into the pure, Android-free
   `JoanSipBuilder` (which must keep compiling without `android.jar`,
   because the host suite depends on it).
4. `CarrierConfigManager` values are read on the same driver pass and
   override where they exist.

## Precedence, in order

Lance's rule, and the one the code follows:

1. **Configuration LineageOS or a carrier can update** -- `CarrierConfigManager`.
   If a key exists here it wins, because it can change without us shipping.
2. **What the network, SIM or APN tells us** -- the ISIM, the P-CSCF list
   from PCO, the peer's own SDP answer. Negotiated facts beat stored ones.
3. **Sane defaults with the best chance of working** -- and "permissive"
   usually beats "specific". An absent AMR mode-set means *every* mode is
   allowed (RFC 4867 4.3.1), so omitting it is a better default than
   narrowing to a value from a 2017 snapshot.
4. **The vendor snapshot** -- only where nothing above answers, and never
   injected into something a higher tier already supplied.

Applied, this changed two things that had been built the other way round:

- **The AMR mode-set is no longer filled from the snapshot into a
  platform-supplied offer.** The platform owns that list; a snapshot value
  injected into it both narrows the offer and pins behaviour a
  CarrierConfig update should be able to move. The snapshot's mode-set is
  used only when the *whole* offer came from the snapshot, which is the
  carrier-silent case.
- **The SIP MTU now comes from `ims.ipv4/ipv6_sip_mtu_size_cellular_int`
  in preference to the link MTU.** Those are updatable keys and are
  populated on this handset (1500/1500); the link MTU is whatever the
  bearer produced. RFC 3261 18.1.1 wants the path MTU, and this is the
  closest the platform will state it.

## Where the platform outranks the snapshot

**A value the platform supplies must win over one joan distilled from a
vendor snapshot.** The snapshot is a 2017-era extract; a `CarrierConfig`
update ships with a ROM or from a carrier without us, and joan must not
pin behaviour that has moved on.

Verified on the bench handset (LineageOS 22.2, T-Mobile), the platform
populates these and they **agree with the LG snapshot**:

| platform key | device | LG profile |
| --- | --- | --- |
| `ims.registration_expiry_timer_sec_int` | 600000 | `reg_expiration` 600000 |
| `ims.registration_retry_base_timer_millis_int` | 30000 | `reg_retry_base_time` 30 |
| `ims.registration_retry_max_timer_millis_int` | 1800000 | `reg_retry_max_time` 1800 |
| `ims.ipv4/ipv6_sip_mtu_size_cellular_int` | 1500 | -- |
| `ims.sip_preferred_transport_int` | 2 = DYNAMIC_UDP_TCP | -- |

That last one is worth noting: the platform's transport policy is
"UDP, TCP when the message is large", which is exactly what joan does.

So the order is **platform -> profile -> built-in**, and the code says so
where it matters:

- REGISTER expiry reads `ims.registration_expiry_timer_sec_int` first and
  falls back to the profile's `reg_expiration`. The trace marks which
  answered, `(platform)` or `(profile)`.
- Codecs read `imsvoice.*` payload/framing first; the profile supplies
  the offer only when carrier config is silent, and a **mode-set** only
  where carrier config left a gap.

**What LineageOS does not ship:** any AMR `mode-set`. Checked on the
handset -- zero occurrences of `modeset` in the live carrier config, and
zero in `CarrierConfig.apk`, which carries 35 carrier files and no
`imsvoice` codec keys at all. So the mode-set can only come from the
vendor snapshot today, and if a future ROM adds one, the gap-fill above
stops applying by construction.

**Where the payload numbers actually come from.** The device reports
`amrwb=[97,98]`, `amrnb=[99,100]`, `dtmf=[101,102]`. These are **Android
platform defaults, not T-Mobile's configuration**: `CarrierConfig.apk`
defines no such keys and neither does AOSP's ImsStack asset, so the only
remaining source is the framework's compiled-in defaults. They apply to
every carrier on this ROM. LG's media config is therefore the only
carrier-*specific* codec source we hold -- and it disagrees with the
platform default on T-Mobile's wideband telephone-event (LG 99, platform
101), which is a reminder that agreeing on AMR numbers was convention,
not confirmation.

## Session-ID (RFC 7989): a default with no config behind it

A worked example of the precedence rule hitting an empty tier 1.

- **Tier 1, updatable config: nothing.** AOSP gates the header on
  `ims.support_sip_session_id_header_bool`, default **true**. That is one
  of ImsStack's 488 private keys, and `CarrierConfigManager` on API 36
  carries no session-id key under any name -- checked against the SDK jar,
  not inferred. Nothing can update this from outside the app.
- **Tier 2, negotiation: partly.** The header is not negotiated by SIM or
  APN, and RFC 7989 registers no option tag, so there is no
  Supported/Require handshake to lean on. What *is* negotiated is the
  pairing: the `remote` parameter starts as the null UUID and is filled
  in once the peer names its own. joan does that in both directions.
- **Tier 3, a sane default: AOSP's.** On for everyone.
- **Tier 4, the vendor snapshot: disagrees, and does not apply.** LG's
  `IsHeaderSessionIdRequired` is hardcoded true for operator 0x52 (US
  Cellular) alone. That is a per-operator predicate in `libims.lge.so`,
  **not** a value in the carrier XML -- so there is no per-carrier figure
  to transcribe, only LG's older default. It is exactly the mechanism
  AOSP replaced with a config key defaulted on for everyone, and the rule
  this document already states ("where the two disagree on a default,
  AOSP wins") decides it.

So: on for every carrier, behind `setSendSessionId()` so a future profile
field or platform key can turn it off without a code change.

**Construction.** AOSP's `SipUtils::GenerateSessionId` is HMAC-SHA-1 over
the Call-ID under a secret, leading 128 bits, lowercase hex. joan uses the
same construction with a per-process random key and no per-call salt, so a
Call-ID maps to one UUID for the life of the process. RFC 7989 7 wants
that -- the local UUID must not change during a session -- and deriving it
rather than storing it means a response path never has to be handed a
dialog to stay consistent with the request it answers. RFC 7989 6 forbids
deriving the UUID from a user or device identifier; the key is what
guarantees that, and the host suite asserts the IMSI and IMEI cannot
appear in the output.

**Scope.** Call dialogs only. A dialog gets a UUID when `buildInvite`
mints one (MO) or when an incoming INVITE arrives carrying one (MT); the
reg-event and conference SUBSCRIBE dialogs are dialogs but not
communication sessions and get none. Responses **mirror**: a request that
arrived without the header is answered without it. REGISTER never carries
it, which is also why it cannot be relevant to the CMCC 404.

## Protected TCP, verified end to end on hardware (2026-09-17)

The protected-TCP path had never executed on any device. T-Mobile is the
only carrier this project can test and it registers over UDP, so
`SO_LINGER(0)`, the IPsec transform on a stream socket, and the
MTU-derived criterion selecting TCP were all host-tested only.

Forced by installing a throwaway build with the T-Mobile PLMN exception
bypassed -- everything else identical to alpha32. Measured:

```
reg1_crit=1300 tpt_pol=2 plmn=sim:310260 reg1len=1529 reg1_tpt=tcp
reg1=401
reg2send=27954->65529 tpt=tcp
reg2=200 OK
```

Every element of the chain fired, and no `tcp_fail=` appears anywhere:

- the criterion selected TCP on its own arithmetic (1529 > 1300);
- the PLMN resolved through the SIM, not the branded realm;
- the unprotected REGISTER went over TCP and drew a 401 challenge;
- the **protected** REGISTER went over TCP inside the IPsec SAs and drew
  a 200 OK;
- `SO_LINGER(0)` -- RST rather than FIN on close -- did not prevent any
  of it.

Registration then stayed up across three checks at 45-second intervals,
which is the question that mattered for the reset: an RST tells the
P-CSCF the flow died, and the fear was that it would take the binding
with it. It did not.

**What this does and does not establish.** T-Mobile's P-CSCF accepts
protected TCP and tolerates the reset. That is one network. It does not
prove China Mobile's will, and the reset is still scoped wider than LG
scopes it -- see `setProtectedTcpLingerReset()`, which exists to be
turned off. But the mechanism is no longer theoretical: it has carried a
real registration to 200 OK on a real handset.

The bench was restored to alpha32 afterwards and re-verified registered.

## The REGISTER TCP criterion: now computed, not provisioned

Found 2026-09-17 while testing whether the CMCC 404 is an encryption
problem. It is not -- see below -- but the search turned up a live
defect, and the fix was to delete the mechanism it lived in.

### The defect

`SipProfile.h` defines `NOT_PROVISIONED = (-10)`.
`SipConfigProxy::GetTcpCriterionLength` returns the profile's value for
anything `!= NOT_PROVISIONED`, so a provisioned `0` is returned
unchanged, and `SipClientTransport.cpp` then does
`if (nBuffLen > criterion)` -- which every message satisfies. **A
criterion of 0 means "always TCP".**

joan's `tcpCriterionFor()` claimed *"0 is how the stock configuration
spells no per-family value"* and skipped it, and callers read
`criterion <= 0` as "never TCP". Both halves were the inverse of the
engine. 21 of 136 profiles provision 0 in both families -- CMCC, DCM,
KDDI, SBM, KT, SKT, LGU, ATT, TMO, O2, SFR, SPR and others. Docomo,
KDDI, SoftBank and the three Korean carriers are LG's home markets and
its most heavily documented profiles; an unset field does not cluster
like that.

### The fix: adopt AOSP's computation

Rather than settle what LG's 0 meant, joan now does what AOSP does and
consults nothing provisioned. Ported from
`AosRegistration::SetTcpCriterionLength()`:

```
if (mtu > 0 && mtu > threshold)  len = min(mtu, maxMtu) - threshold;
else                             len = sipMtuSize[family];
if (len <= 0)                    len = 1500 - 200;
```

The asymmetry in the middle is AOSP's: when the link MTU is unusable the
SIP MTU key is taken as the criterion *directly*, because that key
already states a SIP message size rather than a link MTU to subtract
headroom from.

Inputs, and where each sits in the precedence rule:

| input | source | tier |
| --- | --- | --- |
| `ims.sip_preferred_transport_int` | public `CarrierConfigManager` | 1 |
| `ims.ipv4/ipv6_sip_mtu_size_cellular_int` | public `CarrierConfigManager` | 1 |
| link MTU | the bearer | 2 |
| `max_allowed_network_mtu` = 1500 | AOSP constant; no public key | 3 |
| `sip_message_threshold_for_transport_change` = 200 | AOSP constant; no public key | 3 |

The first three are readable and updatable; only the two constants are
fixed, and both are the reference stack's own defaults.

### What changed in behaviour

- **The snapshot no longer routes.** `tcpCriterionFor()` survives as a
  diagnostic, because a trace from a failing network has to be read
  against what LG shipped, and the tests that pin its content are the
  record of that. Nothing decides on it.
- **IPv4 and IPv6 share one calculation.** joan applied RFC 3261 18.1.1
  to IPv6 only and left IPv4 on the carrier criterion, so a 1568- or
  1830-byte IPv4 REGISTER stayed on UDP well past the point of
  fragmenting. AOSP's 200-byte threshold *is* 18.1.1's headroom, so
  applying both was double-counting.
- **An over-large MTU is clamped.** A 2500-byte MTU used to keep a big
  message on UDP; it now clamps to 1500 and yields the same 1300.
- **The platform's transport policy decides first.** `UDP` never flips,
  `TCP` always does, `DYNAMIC_UDP_TCP` -- what this handset reports --
  is the only value that consults a length at all. `TLS` falls through
  to the criterion rather than being honoured: joan has no TLS
  transport, and claiming one it cannot speak would be worse than
  choosing between the two it can.

### Risk

Lower than it looks. `1500 - 200 = 1300`, which is also AOSP's final
fallback, so on any ordinary 1500-MTU cellular bearer the computed
criterion equals the 1300 the hardcoded CMCC path was already handing
out. The carriers that move are those whose snapshot value was far from
1300 -- chiefly the 4096 GLOBAL default, where oversized REGISTERs were
staying on UDP.

**The T-Mobile exception is kept, ahead of the computation.** It is the
one piece of policy here that is not the reference stack's: this handset
registers on T-Mobile over UDP and flipping it to TCP was tested and not
wanted. A bench-proven result outranks a computed default, and it is
PLMN-scoped so it cannot leak.

## The 404 is not an encryption-configuration problem

Tested directly, because it was a reasonable suspicion.

| field | CMCC | fleet |
| --- | --- | --- |
| `ipsec` | true | true x126, false x10 |
| `ipsec_spi_3gpp` | false | false x120, true x16 |
| `ipsec_algs` | 458755 | 458755 x127, others x9 |

CMCC holds the **majority** value in all three. There is no IPsec
parameter on which China Mobile is an outlier, so there is no
encryption-side carrier setting joan could be getting wrong for CMCC
specifically.

The mechanism worth knowing about, which does exist: AOSP carries
`ims.reg_retry_err_code_without_ipsec_int_array`, a per-carrier list of
REGISTER failure codes whose remedy is to retry the registration
**without IPsec** (`ProcessIpsecFallback`). So "this failure means drop
IPsec and try again" is a first-class, configured behaviour in the
reference stack. AOSP ships the list empty and defines no carrier
values, so whether China Mobile puts 404 in it is not knowable from
here -- but if a packet capture ever shows the 404 surviving an
unprotected retry, this is the mechanism that names it.

## Three divergences from AOSP's defaults, resolved

Recorded for a while as "needs a decision rather than a guess". Decided
2026-09-17 by reading AOSP's implementation rather than only its default,
which changed the answer in every case.

### PANI in the initial REGISTER: nominal, and now tripwired

AOSP defaults `ims.allow_sip_p_access_network_info_header_in_initial_register_bool`
to **false**; joan always sends the header.

What AOSP's PANI contains decides this. `platform/util/AccessNetworkInfoFormatter.cpp`
emits `utran-cell-id-3gpp=<MCC><MNC><LAC><CellID>` -- the serving cell,
which locates the subscriber, sent in the clear on a REGISTER that by
definition precedes IPsec. That is what the default protects.

joan's `paniFor()` emits an access-type token and nothing else:
`3GPP-E-UTRAN-FDD`, `3GPP-NR-FDD` or `IEEE-802.11`. There is no location
in it to protect, and TS 24.229 wants the access type. **No change**, and
a test now asserts the unprotected REGISTER carries no `utran-cell-id`,
`cgi-3gpp` or `i-wlan-node-id`, so the day PANI gains cell information
the tripwire fires instead of a silent leak.

### UDP fallback after a TCP connect failure: kept, now switchable

AOSP defaults `ims.allow_sip_udp_fallback_on_tcp_connection_setup_failed_bool`
to **false**; joan fell back unconditionally.

The trigger was never the difference. AOSP falls back from
`SipClientTransmissionProxy::NotifyTransportError` on
`ERROR_CONNECTION_TIMEDOUT` or `ERROR_CONNECT_FAILED` and nothing else;
joan falls back on `TcpFail.CONNECT` and nothing else, failing closed on
setup, send and read failures because those mean the transaction already
reached the far end. Same condition, independently arrived at.

Only the gate differed, and joan keeps it **on** against AOSP's default
for a reason the default cannot see: AOSP has a full P-CSCF manager to
fall through to, so refusing the fallback costs it nothing -- it moves to
the next node. Refusing it here ends the attempt. The one failing network
this project holds a trace from shows a refused TCP connect followed by a
UDP retry the network *answered*; failing closed there would have
produced silence and less evidence. Tier 3 of the precedence rule ("best
chance of working") outranks a default written for a stack with more
moves available to it.

Now behind `JoanSipBuilder.setUdpFallbackOnTcpConnectFail()` rather than
being unconditional, so a carrier profile or a future platform key can
move it without a code change.

### Blocking a failed P-CSCF: declined, semantics recorded

AOSP defaults `ims.block_pcscf_on_reg_failure_bool` to **true**, and the
divergence was recorded as "joan retries the same candidate list in the
same order". That description was wrong in one half and misleading in the
other.

joan **does** advance: `JoanAppRegister` loops `for (InetAddress cand :
n.pcscfs)` and continues past a candidate that fails, stopping early only
for AKA outcomes, which no second P-CSCF improves. What it lacks is
memory *across* attempts.

And AOSP's blocking is narrower than the key's name suggests:

- It fires from `ProcessStartFailed_TxnTimeout` -- a **Timer F timeout**,
  i.e. silence -- and from flow recovery. A 4xx answer goes to
  `ProcessStartFailed_StatusCode`, which invalidates the P-CSCF only if
  the code appears in `ims.reg_err_code_for_pcscf_discovery_int_array`,
  and AOSP ships that list **empty** (`num="0"`).
- The block is time-limited -- `SetCurrentPcscfInvalid(IMS_TRUE, nAwt + 300)`,
  or the `Retry-After` value when the response carried one -- not permanent.
- It is gated again by `IsRetryOnSamePcscfRequired()`.

So a cross-attempt blocklist buys something only on timeout, where joan's
per-attempt loop already tries every candidate each time. Declined as
complexity without a matching benefit; the semantics are written here so
a future decision starts from facts rather than from the key's name.

**This also corrects a claim made earlier in the same session.** Blocking
was flagged as the most actionable remaining lead on the CMCC 404, on the
reasoning that joan might never be reaching the second P-CSCF. It reaches
it, and AOSP would not invalidate a P-CSCF on a 404 either. The lead was
wrong.

## What is deliberately NOT adopted

- **AOSP's per-carrier values: there are none to adopt.** The inheritance
  mechanism is real -- `ims.parent_carrier_ids_int_array`, keyed by
  Android carrier id -- but `java/assets/carrier_config/` ships exactly
  one file, the defaults, with no carrier-id blocks in it. AOSP specifies
  the shape and leaves the values to carriers and OEMs. So there is no
  AOSP China Mobile configuration to extrapolate from, and the LG
  snapshot remains the only source of per-carrier values this project
  holds.

- ImsStack's 488 private keys: no provider on this platform.
- AOSP's carrier-id keying: joan resolves by PLMN because the LG table
  is PLMN-keyed. Carrier id is the better key, but nothing is keyed by
  it yet -- see the bullet above -- so migrating would buy nothing until
  a source of carrier-id-keyed values exists.
- Any LG file, verbatim. Values only.
