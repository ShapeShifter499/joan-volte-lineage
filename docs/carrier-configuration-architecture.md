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

joan currently diverges from the first three. See "Open divergences".

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

## Precedence: the platform always outranks the snapshot

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

## Open divergences from AOSP's defaults

Recorded, not yet acted on. Each needs a decision rather than a guess --
this project has already shipped one carrier change in the wrong
direction by reasoning from a mechanism without checking the value.

- **PANI in the initial REGISTER.** joan always sends
  `P-Access-Network-Info`; AOSP defaults to not sending it in the
  unprotected REGISTER. LG templates the field and leaves it empty for
  both CMCC and TMO, so LG takes no position we can copy.
- **UDP fallback after a TCP connect failure.** joan falls back; AOSP
  defaults to not falling back. The CMCC trace shows exactly this
  sequence (`tcp connect FAIL` then UDP), so the behaviour is live on a
  failing network.
- **Blocking a P-CSCF that failed registration.** AOSP defaults to
  blocking it; joan retries the same candidate list in the same order.
  LG's CMCC config specialises `RecoverPCSCF` and
  `ProcessFlowRecoveryWithNewPCSCF`, so both other stacks do something
  here that joan does not.

## What is deliberately NOT adopted

- ImsStack's 488 private keys: no provider on this platform.
- AOSP's carrier-id keying: joan resolves by PLMN because the LG table
  is PLMN-keyed. Carrier id is the better key and is worth migrating to
  if a second source ever arrives.
- Any LG file, verbatim. Values only.
