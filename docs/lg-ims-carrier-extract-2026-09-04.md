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
