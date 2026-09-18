# Stock Ims6 / libims.lge.so — carrier routing extract (2026-09-04)

Read-only RE of owner-extracted US998 30b blobs. **Do not load, ship, or
dlopen** `Ims6.apk` / `libims.lge.so`. Behaviour below was replicated in
Joan IMS in Java; none of the LG binaries are in the zip.

Source: owner-extracted US998 30b dump (`Ims6-unpacked/`,
`native/libims.lge.so` 18 MiB, stripped aarch64).

## What the APK actually contains

`assets/Configuration/` is **T-Mobile US only**: NAO + TRF XML. No CMCC
XML, no `mcc460`. Dex has `IMSSetting_CMCC` / `ApnImsCMCC` /
`VoLteServiceCMCC` class *names* (hidden settings / APN helpers), not a
SIP profile.

Carrier SIP lives in **`libims.lge.so`**, compiled in even on a T-Mobile
handset.

## AoS trees in the .so (Enabler/aos4)

| Tree | Role |
|---|---|
| `cmcc/` | China Mobile — `CMCCAoSBuilder`, `CMCCAoSConfiguration`, `CMCCAoSRegistration`, `CMCCAoSIPSecHelper`, `CMCCAoSSubscription` |
| `tmus/` | T-Mobile US — `TMUSAoS*`, `AdjustTcpCriterionPerMtu` |
| `att/` | AT&T |
| `vzw/` | Verizon |
| `spr/` | Sprint — `SPRAoSConfiguration.SetForNormal` |
| `usc/` | US Cellular |
| `kddi/` `dcm/` `sbm/` `kr/` | Japan / Korea IPsec helpers |
| `canada/` + `ca_bell` `ca_rgs` `ca_tls` `ca_vtr` | Canada |
| `global/` + `eu_o2` `eu_dtag` `gb_h3g` `at_h3g` `au_opt` `au_tel` `hk` `in_cmn`/`INRJIL` `it_tim` `org` `pl_nju` `sg_shb` `sg_stl` `tw_twm` | global operators |
| `rcs/` `dpac/` | RCS / DPAC |

Also: `SIPoTCP` / `SIPoUDP`, `CreateSPforTCP` / `CreateSPforUDP`,
`TransmissionProxy :: UDP fallback`, `TCP client is re-used`.

## Named CMCC `SetForNormal` stores

`CMCCAoSConfiguration::SetForNormal` (`0xa6f56c`) writes:

| offset | getter | value |
|---|---|---|
| `+0x40` | `SetDBWritable` | 1 |
| `+0xa0` | `SetUSIMRefresh` | 1 |
| `+0x154` | `SetIPv6DelayInterval` | 4000 ms |
| `+0x4c` | `SetIPv6Delay` (and neighbour, `movi v0.2s #1`) | 1 |
| `+0x17c` | `SetStateStartInterval` | 4000 ms |
| `Init` extra `+0x164` | `SetAuthenticationMaxCount` | 5 |

Sprint `SetForNormal` only sets `DBWritable=1`. TMUS does **not** force
TCP; it calls `AdjustTcpCriterionPerMtu` then the base
`SetTCPCriterionLength`. T-Mobile XML `common_tcp_criterion_len=1200`
with per-reg IPv4/IPv6 criterion **0** (disabled). Do not copy that
onto MCC 460.

CMCC `InitIPSec` is a `SIPRTConfig` socket-option poke (`w1=3`), not a
different ESP matrix. Base `AoSIPSec::CreateSAs` still installs **four**
SAs (directions 1, 0, 3, 2) — same layout Joan already uses.

`CMCCAoSRegistration` has `RecoverPCSCF`,
`ProcessFlowRecoveryWithNewPCSCF`, `ProcessStartFailed_TxnTimeout`.
CMCC live dump: `pcscf_n=2`, only first tried.

Identity templates in the .so (3GPP, not CMCC-only):

```
ims.mnc%s.mcc%s.3gppnetwork.org
%s@ims.mnc%s.mcc%s.3gppnetwork.org
```

Joan already derives these (TS 23.003 §13.3).

## What we replicated (no blobs)

1. Stock `GetTCPCriterionLength` semantics (`v0.4.0-alpha9`): transport
   per message by size — TCP only when it exceeds the carrier's XML
   criterion (CMCC 1300; TMUS per-reg 0 = disabled), else UDP with TCP
   fallback. Replaces alpha7's blanket MCC-460 forced-TCP, which the
   CMCC field trace disproved (`tcp_fail=connect` then silent UDP).
2. Keep that TCP client after REG2 200; INVITE/ACK/BYE reuse it (LG
   “TCP client is re-used”). T-Mobile stays UDP.
3. REG1+REG2 as one attempt per advertised P-CSCF; silent REG2 advances
   (`RecoverPCSCF`). AKA/IpSecManager failures still abort.

## What we did **not** copy

- Hidden-menu / APN Java (`IMSSetting_*`, `ApnImsCMCC`).
- Conference / VT / P-Early-Media / Accept-Contact CMCC UC session code.
- Sprint ECM, KDDI linger, Global `UpdateRegStatusToPref`.
- Native `IPSecApi_*` / xfrm netlink (plat neverallow; Joan uses
  `IpSecManager`).
- Any LG `.so` / `.apk` in the flashable zip.

## REGISTER Contact feature tags are configuration, in LG too (2026-09-16)

Extracted from the H932 Oreo KDZ already on the bench
(`H93230d_00_0902.kdz`), whose AP-side IMS is **`/product/lib64/
libims.lge.so`** -- `/product`, which is why earlier searches of
`/vendor/lib64` and `/lib64` found only the QMI shims and concluded the
AP-side stack was absent. Located by byte-offset: `grep -a -b -o` for
`CMCCAoS`, then `debugfs icheck`/`ncheck` to map block to inode to name.

Two symbols settle a question we had been answering by inference:

```
_ZNK17CoreServiceConfig14GetFeatureTagsEv   CoreServiceConfig::GetFeatureTags()
_ZNK10SipConfigV20GetFeatureTagOptionsEv    SipConfigV2::GetFeatureTagOptions()
```

LG builds the REGISTER Contact's feature tags from **service
configuration**, exactly as AOSP does (`RegContact.cpp` iterating
`piServiceConfig->GetFeatureTags()`). Two independent shipping stacks
agree, and joan was the only one hardcoding
`+g.3gpp.icsi-ref=...mmtel;audio` onto every carrier's REGISTER. alpha28
makes ours switchable and withholds it for CMCC.

### The IMEI URN ends in a literal '0' here too (disassembled)

`SIPURNHelper::GetURN(int, int, bool)` at `0x7ec338`. The single
reference to `"urn:gsma:imei:"` is at `0x7ec434`; the construction that
follows is unambiguous:

```asm
add  x1, x1, #0xfaa          ; "urn:gsma:imei:"
bl   AString::Append(char*)
...                          ; zero-pad to 14 digits (cmp w8,#0xd loop)
bl   AString::GetSubStr(0, 8)   ; TAC
bl   AString::Append
mov  w1, #0x2d                  ; '-'
bl   AString::Append(char)
bl   AString::GetSubStr(8, 6)   ; SNR
bl   AString::Append
mov  w1, #0x2d                  ; '-'
bl   AString::Append(char)
orr  w1, wzr, #0x30             ; <-- literal '0'
bl   AString::Append(char)
```

`orr w1, wzr, #0x30` loads ASCII `'0'` and appends it as the final
character. **LG appends the spare digit, not the IMEI's check digit** --
the same as AOSP's `SipUrnHelper.cpp`. The branch below it
(`GetSubStr(0, 14)` then `IMSSHA1_Initialize`) is the named-UUID
fallback, and `"urn:uuid:"` is the very next string in `.rodata` at
`+0xfb9`, matching AOSP's structure as well.

So two independent shipping implementations and TS 23.003 13.8 / RFC 7254
all agree, and joan was alone in sending the check digit there through
alpha26. alpha28 corrects it.

Method, since the host toolchain fights this: `/usr/bin/objdump` is
x86-only here and fails with "can't disassemble for architecture
UNKNOWN"; use `aarch64-linux-gnu-objdump`. Map a `.rodata` file offset to
a vaddr with the section header (`vaddr 0xfa3ea0` <- `file 0xe1eea0`),
then grep the disassembly for the `adrp`/`add` pair -- objdump prints the
page without an `0x` prefix, so match `adrp.*, fcc000` and then
`add.*#0xfaa`.

**Not on this bench:** any US998 ROM image. `firmware-lge-joan-blobs/
us998/` holds only `ath10k` Wi-Fi firmware, and the V300L Pie KDZ that
the sections above were written from is no longer on disk. The H932
image serves the same purpose: same stack, same `CMCCAoS*` classes.

### What LG's CMCC registration class actually specialises (2026-09-16)

90 `CMCCAoS*` symbols in `libims.lge.so`. The source path leaks from a
trace string: `vendor/lge/apps/Ims/libims/imscore/Enabler/aos4/cmcc/
registration/CMCCAoSRegistration.cpp`.

`CMCCAoSRegistration` overrides, among others:

```
ProcessStartFailed_305          ProcessStartFailed_TxnTimeout
RecoverPCSCF                    ProcessFlowRecoveryWithNewPCSCF
ProcessDefaultFlowRecovery_Start/_Update
UpdateUserIdentities            ProcessRegEvent_REJECTED
IsRetryAfterValueFromPrevResponse
```

**The cluster is about proxy and flow recovery, not identity encoding.**
In LG's view the CMCC quirk is where to send the REGISTER and what to do
when a transaction dies, which is consistent with the other CMCC finding
already recorded here (`pcscf_n=2`, only the first tried) and with the
4000 ms `SetIPv6Delay`.

**There is no CMCC 404 handler.** The only per-status overrides in
`CMCCAoSRegistration` are 305 and TxnTimeout; `ProcessStartFailed_403_404`
exists in the binary but belongs to **`INRJILAoSRegistration`** -- Reliance
Jio, not China Mobile. Read carefully: the symbol is easy to mistake for a
general one. So a 404 on CMCC REGISTER falls through to LG's generic
handler, which suggests a correctly-formed REGISTER does not normally
draw one there.

**A real gap it exposes on our side:** LG needs a CMCC-specific
`ProcessStartFailed_305` -- SIP 305 Use Proxy, on REGISTER. joan handles
3xx only for INVITE (`JoanSipUa`); `JoanAppRegister` has no redirect
handling at all. That is not the current 404 -- the tester's trace shows
no 305 -- but it is a hole on exactly the network LG needed it for.

`UpdateUserIdentities` is CMCC-overridden and is the one worth reading
next. Its assembly is dominated by TraceService calls with the real work
behind vtable dispatch, so it needs Ghidra decompilation rather than
objdump.

### CMCCAoSRegistration::UpdateUserIdentities, decompiled (2026-09-16)

Ghidra 11.4.3 headless, JDK 21 (11.4 will not run on the JDK 17 also
installed here). The function branches on whether the card has an ISIM,
and LG's own log line names the branch:

```
"[%s:%d] [%s] UpdateUserIdentities :: ISIM (%s)"     // "true" / "false"
"[%s:%d] Do not update the IMPU (%s); ISIM supports"
```

In the **no-ISIM** branch it walks an `AStringArray` and writes up to
eight entries into parameter slots `i + 0x3b`:

```c
if (((hasISIM & 1) == 0) && (count > 0)) {
    do {
        pAVar10 = AStringArray::GetElementAt(list, i);
        plVar5->vtable[0x10](plVar5, i + 0x3b, str);   // CP_I_IMPU_0 + i
        if (7 < i) break;
    } while (i < count);
}
```

`CP_I_IMPU_0` is in the string table, so the slots are IMPU_0..IMPU_7.

**The negative result is the useful part.** The list comes from
`AoSSubscriber::GetTemporaryIMPU(AStringArray&, bool)`, and that function
calls `ImsIdentity::CreateTemporaryPublicUserId` and
`CreateTemporaryPrivateUserId` -- the **standard TS 23.003 derivation,
the same one joan already performs**. LG is not deriving a different or
CMCC-specific identity. The override exists to populate the stack's
internal IMPU slots for a USIM-only card, work an ISIM would otherwise
do; it does not change what goes in To/From on the wire.

So the "CMCC expects a different IMPU form" idea, which this function
looked like it was going to support, does **not** hold up. Identity
derivation is now confirmed identical between joan, AOSP ImsStack and
LG's shipping binary. Attention for the 404 belongs elsewhere in REG2.

Ghidra note: `-import` runs full analysis once (slow on this 18 MB
binary); afterwards `-process <name> -noanalysis` reuses the project and
returns in seconds, which is how the second function was read.

### LG ships China Mobile's own IMS configuration, and it was on the bench

`Ims6.apk` is at **`/product/priv-app/Ims6/Ims6.apk`** in the H932 KDZ --
`/product`, like `libims.lge.so`, which is why earlier searches of `/app`
and `/priv-app` concluded the AP-side IMS app was absent.

**This image ships LG's WORLD carrier set**, 347 asset files, including
`assets/Configuration/CMCC/CN/configuration.CMCC.CN.xml`. The note above
saying the T-Mobile SKU carries "T-Mobile US only" configuration is wrong
for this image. No ROM download was needed.

988 parameters per carrier; **212 differ** between CMCC and TMO/US:

| parameter | TMO/US | CMCC/CN |
| --- | --- | --- |
| `aos_reg_0_ipsec_spi_3gpp` | true | **false** |
| `aos_reg_0_ipsec_algs` | 0x00010003 | **0x00070003** |
| `aos_reg_0_features` | 0x00000004 | **0x00000A04** |
| `aos_reg_0_authentication_max_count` | 3 | **5** |
| `header_info_feature_tags` | 0x01000208 | **0x03000208** |
| `header_info_target_scheme` | sip | **tel** |
| `header_info_target_number_format` | global | **local** |
| `common_sip_features` | 0x151A001B | **0x16000000** |
| `common_tcp_criterion_len` | 1200 | **1300** |
| `session_st_headers` | 0x01 | **0x11** |
| `mmtel_auth_username` | MDN | **IMPI** |
| `LGE_FEATURE_MULTIPLE_REGISTRATION` | 1 | **0** |
| `reg_methods` | ...MESSAGE | ...MESSAGE,**OPTIONS** |

**Two conclusions, both correcting something written earlier.**

*The MMTEL feature tags belong in CMCC's Contact.* CMCC's `tContactH` is
literally

```
;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel
```

and its `header_info_feature_tags` is `0x03000208` against T-Mobile's
`0x01000208` -- an extra bit, so China Mobile gets **more** tags than a
network joan already works on. alpha28 briefly withheld them from CMCC on
the argument that AOSP treats them as configuration; that was the right
observation and the wrong direction, and it is reverted.

*No home domain is configured for CMCC.* `home_network_domain_name` is
empty and `subscriber_0_home_domain_name` is the placeholder
`ims.mnc001.mcc001.3gppnetwork.org`, filled at runtime. So LG derives the
domain on China Mobile too. Together with AOSP and joan that is three
implementations deriving it, which weighs against the domain being the
404's cause -- the Qualcomm `CONFIG_ITEM_DOMAIN_NAME` knob recorded in
`upstream-references.md` shows the *mechanism* exists, not that CMCC uses
it.

Placeholder subscriber values are templates (`1234567890`), but their
*shapes* are informative: CMCC's `subscriber_0_impu_1` is a **`tel:`**
URI where T-Mobile's is `sip:`, matching `target_scheme=tel` and
`number_format=local`.

### CMCC deltas vs joan: complete status (2026-09-16)

Everything LG configures differently for China Mobile, with what joan
already does. The point of the table is that most of it is **already
covered**, so the remaining rows are the short list worth testing.

| LG CMCC setting | joan status |
| --- | --- |
| `common_tcp_criterion_len` 1300 | **done** -- implemented since alpha9 |
| `reg_methods` / `allow_methods` include OPTIONS | **done** -- `ALLOW` carries OPTIONS and `JoanSipUa` answers it |
| `session_st_refresher` local (UE refreshes) | **done** -- `REFRESHER_UAC` |
| `tContactH` carries `+g.3gpp.icsi-ref` mmtel | **done** -- and alpha28's withholding of it was reverted |
| `home_network_domain_name` empty (derive) | **done** -- we derive per TS 23.003 |
| `authentication_max_count` 5 (vs TMO 3) | joan allows one AKA resync; not obviously a factor |
| `bUse180RPR` 0, `session_sdp_non_rpr` false | joan answers PRACK but never requires 180rel |
| `LGE_FEATURE_GRUU` 0, `MULTIPLE_REGISTRATION` 0 | joan advertises neither `gruu` nor `outbound`; **but sends `+sip.instance` unconditionally** |
| `LGE_FEATURE_AUTH_SIP_DIGEST` 1 | **not implemented** -- joan does AKA only |
| `ipsec_spi_3gpp` false, `ipsec_algs` 0x00070003 | their 404 came back **over** the SA, so IPsec demonstrably works. [2026-09-18: `ipsec_spi_3gpp` is no longer unknown -- it is decimal formatting of `spi-c`/`spi-s`, and joan already matches. See "REG2 message shape" below.] |
| `target_scheme` tel, `number_format` local | affects INVITE targets, not REGISTER |
| `aos_reg_0_features` 0x00000A04 | bits 9+11 beyond the decoder's vocabulary -- see below. [2026-09-18: confirmed unresolvable from the AOSP source too; needs the binary.] |

**Feature bitmask decoding.** `AoSRegistration::FeatureToString()` is a
trace helper that names only four bits:

```
bit 0  0x00000001  FEATURE_SUBSCRIPTION
bit 1  0x00000002  FEATURE_IPSEC
bit 2  0x00000004  FEATURE_TRM
bit 3  0x00000008  FEATURE_TRM_BLOCK
```

So TMO `0x004` and CMCC `0xA04` both decode to `FEATURE_TRM`, and CMCC's
extra bits 9 and 11 have no trace name. 61 sites in the binary bit-test
those positions, too many to attribute without knowing which act on this
field, so the semantics are **unresolved** and recorded as such rather
than guessed.

**`+sip.instance` is conditionally omitted, but not on GRUU.**
`SetContactHeader` (0x7ebe00) contains a loop that skips a Contact
parameter equal to `"+sip.instance"`. The guard resolves to
`RegStateTracker::IsWithinTrustDomain`, not `IsGRUUConfigured` -- inside
that block `*param_2 == false` already holds, so both branches of the
GRUU test yield the same value. **LG's GRUU=0 for CMCC does not make it
drop the instance-id.** Worth stating plainly because the config flag
makes the opposite look obvious.

**The one real capability gap is `LGE_FEATURE_AUTH_SIP_DIGEST`.** China
Mobile is configured for SIP Digest where T-Mobile is not, and joan
implements AKA only. The failing tester is challenged with `AKAv1-MD5`
and answers it, so Digest is not what that network asked of them -- but
it is the only row in this table naming something joan cannot do at all.

### LG applies no hardcoded SIP quirk to China Mobile (2026-09-16)

`SIPFeatures` in `libims.lge.so` holds eleven per-operator predicates --
`IsTransportParameterIgnoredForRegBinding`, `IsHeaderSessionIdRequired`,
`IsPANIHeaderForAckRequired`, `IsReferSubHeaderSupported`,
`IsSocketOptionRequiredForTcpMaxSeg` and others. They look like bitmask
tests on `common_sip_features`; they are not. Each calls

```c
bool IsOperatorTargetFS(int wanted, int slot) {
    return op->slots[slot].operatorId == wanted;   // offset 0x14
}
```

so they are **hardcoded per-operator quirks**, keyed by an operator enum.

The enum's name table is in `.rodata`, 158 entries beginning
`NONE AIS APT ARTL ATT CRK AVE BEE BELL BYT CCM CELC CHT CLR CMCC ...`,
which puts **CMCC at index 14 (0x0E)**. The five constants those
predicates test resolve to:

| id | operator | quirk |
| --- | --- | --- |
| 0x13 | DCM (NTT Docomo) | TCP MaxSeg socket option, host-part validation |
| 0x20 | KDDI | PANI required in ACK |
| 0x3c | SBM (SoftBank) | Refer-Sub not supported |
| 0x52 | USC (US Cellular) | Session-ID header required |
| 0x5a | SPR (Sprint) | transport parameter ignored for reg binding |

**None of the five `SIPFeatures` predicates targets China Mobile.**

**Correction, from sweeping the whole binary rather than one class.** The
statement above was originally written as "LG applies no hardcoded SIP
quirk to China Mobile", which is wrong. `IsOperatorTargetFS` has **384
call sites covering 47 operators**, and China Mobile has **10** of them:

```
SIPFeatures::IsTransportParameterIgnoredForIncomingRequestRouting   <- SIP
AoSMngr::AoSBuilderFactory                 (selects CMCCAoSBuilder)
DialingFactory::CreateDialingPlan          (matches target_scheme=tel)
MediaServiceProfile::GetModemFeature
Session::InitInstance, OperatorEnablerFactory::IsOperatorEnablerRequired,
UCHelper::createComMsgHelper / ComBlock / ComFailure, UCFactory::createUCApp
```

Only the first is SIP behaviour, and it governs how an **incoming**
request is routed -- whether a URI's `transport=` parameter is honoured
-- not what an outgoing REGISTER contains. So the conclusion survives for
the 404 specifically, but the general claim did not, and the method that
produced it (reading one class) was the reason.

Quirk counts are concentrated in LG's home and main markets: KT 43,
SKT 40, TMO 34, LGU 34, MPCS 30, ATT 24, VZW 20, KDDI 14, DCM 12, TRF 12,
SPR 11, ORG 11, **CMCC 10**.

### LG's AoS and AOSP's Aos are the same codebase (2026-09-17)

The single most useful structural fact found so far, and it was sitting
in plain sight in both names.

AOSP's ImsStack registration engine is
`native/libimsstack/enabler/aos/registration/AosRegistration.cpp`. LG's
binary carries `AoSRegistration`, and its CMCC subclass traces to
`vendor/lge/apps/Ims/libims/imscore/Enabler/aos4/cmcc/registration/CMCCAoSRegistration.cpp`
-- the path is in the binary's own trace strings.

The method names are identical, not merely similar:
`ProcessStartFailed_423`, `ProcessUpdateFailed_423`,
`ProcessUpdateFailed_403`, `ProcessStartFailed_305`,
`ProcessStartFailed_TxnTimeout`, `ProcessDefaultFlowRecovery_Start`,
`ProcessReInitiate_Update`. All of these appear in AOSP's source and in
LG's symbol table.

**So AOSP's Apache-2.0 source is the readable form of LG's binary.** LG's
is an older fork (`aos4`) that has methods AOSP no longer does --
`UpdateUserIdentities` has no AOSP counterpart -- but for anything
present in both, the source says what the disassembly means. That turns
future work on this binary from decompilation into diffing, and it is
worth using before reaching for Ghidra again.

### The complete CMCC override surface

1177 CMCC symbols across 30 classes. The registration specialisation,
`CMCCAoSRegistration`, overrides exactly these:

```
ProcessStartFailed_423      ProcessUpdateFailed_423   ProcessUpdateFailed_403
ProcessStartFailed_305      ProcessUpdateFailed_305   ProcessStartFailed_TxnTimeout
ProcessDefaultFlowRecovery_Start / _Update
ProcessFlowRecoveryWithNewPCSCF   RecoverPCSCF        ProcessReInitiate_Update
ProcessRegEvent_REJECTED    IsRetryAfterValueFromPrevResponse
CreateIPSecHelper           UpdateUserIdentities      GetSubscription
Registration_Started / _Updated / _Removed            Timer_TimerExpired
```

Two conclusions follow, and both are negative results that close
hypotheses rather than opening them.

**There is no CMCC-specific identity derivation.** Across all 1177
symbols, `UpdateUserIdentities` is the *only* one touching identity at
all -- no IMPU builder, no IMPI builder, no domain or realm
specialisation, nothing barred-identity related. CMCC uses the base
class's derivation, which is the same derivation AOSP documents and joan
implements. This was previously ruled out by comparing three
implementations' outputs; it is now settled from the override surface
itself.

And what `UpdateUserIdentities` actually does, decompiled: when the card
is **not** an ISIM, it copies the registered identity list into the
card-parameter store, up to 8 entries from index 0x3b, gated on a trace
that logs `UpdateUserIdentities :: ISIM (true|false)`. That is
propagation *after* a successful registration -- the list it copies comes
from the 200 OK -- so it cannot shape the REGISTER that earns the 200.

**There is no CMCC 404 handler by name.** CMCC specialises 403, 423,
305 and transaction timeout, and there is no `ProcessStartFailed_404`.
That is *not* the same as having no 404 behaviour: a 404 falls through
to `ProcessDefaultFlowRecovery_Start`, which CMCC does override. See
"CMCC's failure policy is Retry-After back-off" below, which corrects
the conclusion originally drawn here.

That is the closing argument on a long hypothesis class. joan's 404
arrives on the protected REGISTER, after REG1 drew an `AKAv1-MD5`
challenge -- so the network found the private identity, ran MAR against
the HSS, and then rejected the registration. Every CMCC-specific
behaviour in the vendor stack is now enumerated, and none of them changes
what an outgoing REGISTER claims. There is no unimplemented CMCC quirk
left to find in this binary: the remaining evidence has to come from the
wire.

### CMCC's one IPsec override is SO_LINGER(0), and it explains the TCP failure

`CMCCAoSRegistration::CreateIPSecHelper` builds a `CMCCAoSIPSecHelper`
rather than the base helper, and that subclass overrides exactly one
method. Decompiled, `CMCCAoSIPSecHelper::InitIPSec` does one thing:

```c
iVar2 = ...GetSlotId();
plVar4 = SIPFactory::GetRTConfigHelper(iVar2);
SIPRTConfig::SocketOption::SocketOption(aSStack_c8);
local_c0 = 0;                          /* linger value zeroed */
plVar4->SetConfig(3, aSStack_c8);
```

Config id 3 resolves against AOSP's `SipRtConfig.h` enum -- `CONFIG_I_BASE`
0, `CONFIG_I_LOG_MASK` 1, `CONFIG_I_REUSEADDR` 2, **`CONFIG_I_LINGER` 3**.
So China Mobile's entire IPsec specialisation is **`SO_LINGER` with a
linger time of 0**: close the SIP socket with a TCP RST rather than a
FIN, leaving no `TIME_WAIT` behind.

**Why that matters, and why it is not a cosmetic tweak.** Under RFC 3329
sec-agree both ends of the protected connection are fixed: the UE's
`port_uc` and the P-CSCF's `port_ps` are negotiated once, in the
Security-Client/Security-Server exchange, and cannot be varied. Every
protected TCP connection therefore reuses the **exact same 4-tuple**. A
previous connection still in `TIME_WAIT` makes the next `connect()`
fail -- and `SO_REUSEADDR`, which joan already set, does not help: it
permits *binding* over a `TIME_WAIT` socket, not completing a connection
whose full 4-tuple is still held.

That is `TcpFail.CONNECT`. Which is what the China Mobile field trace
shows, immediately before the UDP fallback that draws the 404.

**Adopted**, and deliberately wider than LG's scoping. LG puts it in a
China Mobile subclass because that is the carrier whose network drove
it; joan sets it on every protected TCP socket, because the fixed-4-tuple
problem belongs to sec-agree rather than to any carrier, and a carrier
joan cannot test is the worst possible place to put a scope. No
configuration key anywhere supplies this -- it is a runtime socket option
in the engine, not a carrier-config value -- so there is nothing to make
switchable.

**What this does and does not claim.** It explains the TCP connect
failure mechanically. It does **not** explain the 404, which arrives on
the UDP fallback afterwards. If the connect now succeeds, the protected
REGISTER goes over TCP as China Mobile's own configuration asks, and the
404 either follows it there or does not -- and either answer is worth
more than the current trace, because it separates the transport failure
from the registration failure for the first time.

### CMCC's failure policy is Retry-After back-off, not P-CSCF rotation

All thirteen remaining `CMCCAoSRegistration` overrides decompiled. Two
of the addresses turned out to be shared thunks landing in neighbours
(`VZWAoSRegistration::ProcessUpdateFailed_Others` and the *base*
`AoSRegistration::ProcessUpdateFailed_423`), so they are not CMCC
overrides at all. What the rest do:

| override | behaviour |
| --- | --- |
| `ProcessStartFailed_423` | reads `AoSUtil::GetMinExpiresValue`, re-registers |
| `ProcessStartFailed_305` / `ProcessUpdateFailed_305` | both call `ProcessFlowRecoveryWithNewPCSCF` |
| `ProcessFlowRecoveryWithNewPCSCF` | `ClearPending` then `RecoverPCSCF` |
| `ProcessStartFailed_TxnTimeout` | traces "no pcscf and don't retry registration" |
| `ProcessDefaultFlowRecovery_Start` | fail count, Retry-After, timer, state |
| `IsRetryAfterValueFromPrevResponse` | true when the **previous** response carried Retry-After > 0 |

**This corrects an earlier claim in this document.** It said CMCC has no
404 handling, on the grounds that there is no `ProcessStartFailed_404`.
There is not -- but a 404 does not need one. It falls through
`ProcessStartFailed_StatusCode` to `ProcessDefaultFlowRecovery_Start`,
and **CMCC overrides that**. So China Mobile does have specific 404
behaviour; it simply reaches it by the default path.

What that override does, decompiled:

```c
IncreaseConsecutiveFailCount();
retryAfter = AoSUtil::GetRetryAfterValue(response);
if (retryAfter == 0) retryAfter = GetActualWaitTime();
if (no registration)  StartTimer(100, retryAfter * 1000), state = 0;
else                  StartTimer(101),                    state = 2;
SetState(state);
ReportStateChanged(3, 4);
```

**It never calls `TryNextPcscf()`.** AOSP's base flow recovery does.
CMCC replaces P-CSCF rotation with a timed retry against the *same*
P-CSCF, and reserves `RecoverPCSCF` / `ProcessFlowRecoveryWithNewPCSCF`
exclusively for **305 Use Proxy** -- both 305 handlers call it and
nothing else does. Read with `IsRetryAfterValueFromPrevResponse`, which
carries a Retry-After forward from the previous response when the
current one has none, China Mobile's entire registration-failure policy
is Retry-After-driven back-off.

**The gap that found.** joan parsed `Retry-After` on a 503 to an INVITE
and **nowhere else** -- the registration path ignored the header
completely, so a network that said "wait an hour" was retried in sixty
seconds and then every doubling after that. Both reference stacks treat
it as the governing delay (AOSP's
`ProcessDefaultFlowRecovery_Start_WithRfcRule` branches on
`nRetryAfter > 0`, citing IR.92), and RFC 3261 10.3 asks the same of a
registrar's client. A UE that retries sooner than a network told it to
is the kind of client a network starts refusing.

Now honoured: the rejection's Retry-After outranks joan's own backoff,
clamped at 30 minutes so a malformed or hostile value cannot park
registration, and the exponential is left untouched on such a cycle
because the network named the interval.

Not adopted: CMCC's refusal to rotate P-CSCF. joan tries each advertised
candidate within an attempt, which is AOSP's behaviour rather than
China Mobile's, and there is no evidence that stopping would help --
only that LG chose it. Recorded here rather than acted on.

### AOSP replaced the whole mechanism with configuration

LG hardcodes these against an operator enum. AOSP turns the same
behaviours into carrier-config keys, and defaults several of them **on
for everyone** rather than for the one carrier that forced the issue:

| LG, per operator | AOSP key and default |
| --- | --- |
| `IsTransportParameter*Ignored*` (3 ops) | `ims.ignore_udp_transport_parameter_for_outgoing_request_bool` = **true** |
| `IsHeaderSessionIdRequired` (USC) | `ims.support_sip_session_id_header_bool` = **true** |
| `IsReferSubHeaderSupported` (SBM) | `imsvoice.support_conference_refer_subscribe_bool` = **true** |
| `IsMultipleDialogUsagesRequired...` (DCM) | `ims.request_uri_validation_required_in_mid_dialog_bool` = **true** |

That is the better design and the one joan follows: a carrier-scoped
value, not a hardcoded operator id.

**Where joan stands against those defaults.** It never emits or parses a
`transport=` URI parameter at all, so it already behaves the way
`ignore_udp_transport_parameter` prescribes -- by omission rather than by
decision, which is worth knowing if that ever needs to change.

Session-ID (RFC 7989) **was** the one real gap in this table and is now
closed: joan carries it on call dialogs, defaulted on for every carrier
the way AOSP does rather than for US Cellular alone the way LG does. It
is dialog-scope and could never have borne on a REGISTER, so it does not
touch the 404. See "Session-ID" in
`docs/carrier-configuration-architecture.md` for why AOSP's default won.

This closes a hypothesis class rather than opening one: there is no
hidden, code-level China Mobile SIP behaviour in LG's stack. Everything
CMCC-specific is in the configuration XML -- which is now fully mined --
and in the `CMCCAoS*` classes, whose specialisation is proxy and flow
recovery. So the 404 is not explained by a vendor quirk joan is missing,
and the remaining unknowns are what our REGISTER actually contains
(`reg2_hdrs` will say) and their core's own behaviour.

## Hardware still required

CMCC SIM: `last_register` with `tpt=tcp` then `reg2=200`, or
`tcp_fail=timeout` (ESP-NULL still live). T-Mobile must still REGISTER
on UDP without `tpt=tcp`.

## V300L Pie KDZ (LG U+, 2026-09-04, extract-only)

Official `V300L30p_00_1220.kdz` (3 683 122 321 bytes, MD5 `97eb8b596cd80499fe39f1e0ec2f5762`).
Extracted locally for RE. **Not flashed.** US998 stays the LOS bench.

- Modem `NON-HLOS.bin` → `modem.image` (102 MiB FAT16). **Zero** `qmi_imss` /
  `SET_REG_MGR` / `OpenIMSd` strings. Same AP-SIP architecture as joan US998/H932,
  not QMI-in-modem. Korea SKU does not prove a different IMSS wiring.
- `Ims6.apk` + `libims.lge.so` live under **`/product/`** (priv-app `Ims6`,
  `product/lib64/libims.lge.so`), not `/system/priv-app`.
- V300L `Ims6.apk` **ships world XML** under `assets/Configuration/` (CMCC, KT,
  SKT, LGU, KDDI, DCM, SBM, RJIL, TMO NAO/TRF, …). US998 30b APK only shipped
  T-Mobile NAO/TRF.
- `libims.lge.so` is the same class of world binary, **not** byte-identical
  (V300L 18 694 736 sha256 `09adbe70…` vs US998 18 694 952 `ad1af4d2…`).
  `KRAoS` / `CMCCAoS` / `TMUSAoS` strings still present.

XML `aos_reg_0` knobs (per-reg `tcp_criterion_length_ipv4/6` is **0** = use common):

| Profile | `aos_reg_0_ipsec` | `aos_reg_0_ipsec_algs` | `common_tcp_criterion_len` |
|---|---|---|---|
| CMCC/CN | true | `0x00070003` | **1300** |
| TMO US NAO | true | `0x00010003` | 1200 |
| LGU / KT / SKT KR | true | `0x00040002` | **4096** |

Korea keeps IPsec but a high TCP threshold (UDP unless SIP is huge). CMCC 1300
is the size criterion Joan now enforces per message on MCC 460 (alpha7's
blanket forced-TCP REG2 misread it and died `tcp_fail=connect`). Do not copy
the Korea 4096 onto MCC 460, and do not flip T-Mobile onto TCP (its per-reg
criterion is 0 = disabled; `AdjustTcpCriterionPerMtu` stays unreplicated).

L-01K still has no public KDZ listing. H930DS HK Pie was identified but not
downloaded.

## REG2 message shape, read against AOSP ImsStack (2026-09-18)

LG's `libims.lge.so` is a derivative of AOSP's
`platform/packages/modules/ImsStack`: the `AoS*` class names, the
`CreateUeSpi` / `SetUePortnSpi` call shape and the `SIP_FEATURE_CAPS_*`
vocabulary are all the same codebase. Where one of LG's XML knobs maps
onto an AOSP call site, **AOSP's source names the behaviour the knob
selects** — without needing the binary. Tree read at tag
`android-17.0.0_r1`, extracted read-only to `~/joan-analysis/aosp-ims/`.

This closes three rows of the 09-16 delta table, opens one new one, and
kills a theory.

### `common_sip_features` decoded

The single largest unexplained value in the table. It is
`ISipConfig::SIP_FEATURE_CAPS_*`
(`config/interface/common/ISipConfig.h:104`), consumed through
`SipConfig::HasFeature` / `SipConfigProxy::Is*`.

| bit | name | TMO `0x151A001B` | CMCC `0x16000000` |
| --- | --- | --- | --- |
| 0 | `IPSEC` | set | clear |
| 1 | `GRUU` | set | clear |
| 3 | `KEEP` | set | clear |
| 4 | `TRUST_DOMAIN` | set | clear |
| 17 | `PPI_HEADER_IN_REG_SUB` | set | **clear** |
| 19 | `SIP_INSTANCE_FOR_CALLER_PREFERENCE` | set | clear |
| 20 | `ROUTE_HEADER_IN_REG` | set | clear |
| 24 | `AUTHENTICATION_ALGORITHM_PARAMETER` | **set** | **clear** |
| 25 | `UA_SET_BY_CONTEXT` | clear | set |
| 26 | `USER_AGENT` | set | set |
| 28 | `CONTACT_IN_ALL_1XX` | set | set |

Bit 17 clear for CMCC is the independent confirmation of alpha33's
P-Preferred-Identity fix — LG had already configured China Mobile not to
carry PPI in REGISTER/SUBSCRIBE.

Two cautions against over-reading this table. Bit 0 `IPSEC` has **no
consumer anywhere in the AOSP tree**, and CMCC plainly does use IPsec
(`aos_reg_0_ipsec` is true and their 404 came back over the SA), so bit 0
is not the IPsec master switch. Bit 19 governs how `+sip.instance` is
*evaluated for service routing*, not whether it is emitted — consistent
with the `SetContactHeader` finding above that LG does not drop the
instance-id for CMCC.

### New confirmed delta: `algorithm=` in the Authorization header

`engine/registration/RegParameter.cpp:403` appends the `algorithm=`
parameter to the REGISTER's Authorization header **only** when
`SipConfigProxy::IsAuthenticationAlgorithmRequired()` is true, which is
`HasFeature(SIP_FEATURE_CAPS_AUTHENTICATION_ALGORITHM_PARAMETER)` —
bit 24 (`config/interface/private/SipConfig.h:95`).

**T-Mobile sets bit 24. China Mobile clears it.** So LG sends the
Authorization header *without* `algorithm=` to China Mobile.

joan emits it unconditionally, on both legs —
`JoanSipBuilder.java:1336` (the REG2 digest) and `:1353` (the initial
credential-less REGISTER).

RFC 3310 §4 *requires* `algorithm=AKAv1-MD5`, so omitting it is
non-standard, which is presumably exactly why LG made it a per-carrier
flag rather than a default: a carrier-scoped opt-out is what you write
after hitting a core that mishandles the standard form. This is the same
class of finding as the `integrity-protected` parity note already in
`JoanSipBuilder` — a byte-shape difference in the one header that
precedes CMCC's 404.

Not proven causal. It is a diff, in the failing header, that LG
deliberately configured off for this carrier.

### Checked and NOT a delta: User-Agent to China Mobile

Worth recording because bit 26 makes the opposite look obvious, and
because a previous session reached the wrong answer from the header
list of a *different* tester's trace.

Bit 26 `USER_AGENT` is **set** for CMCC, which looks like "LG does send a
UA to China Mobile". Following the whole chain says otherwise:

1. `RegParameter.cpp:380` adds the header only if bit 26 is set. CMCC: yes.
2. `UserAgentHeader::SetHeader` (`engine/core/util/UserAgentHeader.cpp:47`)
   resolves the string: `GetRegUaString` → the profile's reg-UA if
   non-null, else `SipConfig::GetUaVersion()` →
   `SipConfigV::m_strServiceVersion` ← `KEY_IMS_USER_AGENT_STRING`.
3. **If that string is empty it returns without setting the header**
   ("UA version is empty", `UserAgentHeader.cpp:58`).

LG's CMCC profile has exactly one UA key, `user_agent_fmt = ''`, against
T-Mobile's `'T-Mobile VoLTE-ePDG-#RCS#-IR94-ussd LG/#MODEL# #SW_VERSION#'`.
There is no separate reg-UA key. So the machinery is enabled and the
string is empty: **no User-Agent reaches China Mobile**, and joan's
suppression matches stock.

Bit 25 `UA_SET_BY_CONTEXT` does not change this. Per
`SipProfile.h:591`, it selects *which* header carries the string —
`User-Agent` on requests, `Server` on responses — it does not synthesise
one.

Do not "fix" this. The CMCC tester's alpha33 REGISTERs correctly carry no
User-Agent; the `User-Agent` seen in a 2026-09-17 trace belongs to the
**NOS (268-03)** tester, where it is expected.

### Closed: `ipsec_spi_3gpp` semantics

The 09-16 table recorded this as "unknown semantics". It was in fact
resolved from the binary on 2026-09-05 and the note never made it back
here. `SIPSecurityHeader::ToString()` (0x79770c) tests the SPI-option
bool stored by `SetSPIOption` and selects `;spi-c=%010u` / `;spi-s=%010u`
when true, `;spi-c=%u` / `;spi-s=%u` when false. It is **decimal
formatting**, not a port-derived SA scheme.

CMCC is `false` → unpadded. joan writes plain unpadded decimal, so
**joan already matches.** Full evidence and the rejected "SPI = port"
hypothesis:
`~/.hermes/skills/software-development/ims-volte-stack-development/references/joan-cmcc-spi-flag-correction-2026-09.md`.

### Open: the UE SPI and protected-port convention

joan and stock pick these differently. Constants from
`enabler/include/aos/registration/AosIpsec.h`, behaviour from
`AosIpsec::CreateUeSpi()` and `AosIpsecHelper::SetUePortnSpi()` — and
independently corroborated in LG's own binary, where
`AoSIPSecHelper::SetUePortnSPI` (0xa44484) "stores SPI then SPI+1".

| | stock (AOSP + LG binary) | joan |
| --- | --- | --- |
| `spi-c` | monotonic counter, step `SPI_VALUE_TO_BE_INCREASED` = 2, floored at `SPI_MIN` = 1 000 000 000 | `256 + rng.nextInt(0x7fffffff - 256)` |
| `spi-s` | **`spi-c + 1`** | a second, unrelated random |
| `port-c` | `UE_PORT_LOWER` 38001 – `UE_PORT_UPPER` 39000 | `10000 + rng.nextInt(20000)` |
| `port-s` | 39001 – 40000 (`+ PORTS_INTERVAL`) | `port-c + 1000` |

So stock always offers an adjacent SPI pair, both ≥ 1e9, from two fixed
1000-port windows; joan offers two unrelated SPIs that are usually far
below 1e9, from a 20000-port window.

Nothing in TS 33.203 or RFC 3329 requires the pair to be adjacent, and
joan's current values register fine on T-Mobile, NOS and China Telecom —
so this is **not** a demonstrated cause of anything. It is simply the
last part of the sec-agree shape that still differs from stock, and
matching it is a small, bounded change to `JoanSipBuilder.Params.random`.

### Closed as unresolvable from AOSP: `aos_reg_0_features` bits 9 and 11

AOSP's registration feature enum
(`enabler/include/aos/registration/AosRegistration.h:496`) has **two**
values, `FEATURE_SUBSCRIPTION = 0x01` and `FEATURE_IPSEC = 0x02`; TRM and
TRM_BLOCK are LG additions, and bits 9/11 are further LG additions on top
of that. The upstream source cannot name them, so this stays where the
09-16 entry left it: resolvable only from `libims.lge.so`, by the
disassembly method that settled `ipsec_spi_3gpp`.
