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
