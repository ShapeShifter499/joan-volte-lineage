# LG IMS full-stack reverse engineering — 2026-09-05

Scope: extract-only RE of the complete stock LG IMS stack (Joan / LG V30
family) to replicate behavior in the joan-volte-lineage app stack.
Authorized ROMs: US998 20h/30b (own device), V300L 30p, H930DS 30c.
Lance: "Do everything you can, bin walk, decompile, etc… Try for VoWIFI
and the whole stack… Use all of the different stock roms you have access
to."

## Assets inventory

| Asset | Location | Notes |
|---|---|---|
| libims.lge.so (Oreo, US998) | /data/models/joan-stock/extract/native/ | 18,694,952 B, sha256 ad1af4d2… |
| libimsmmpf.lge.so (media plane) | same dir | 926,384 B |
| libimswms.lge.so (wms bridge) | same dir | 68,720 B |
| libims.lge.so (KR Pie) | /data/models/joan-asia-kdz/v300l-ims/ | 18,694,736 B, sha256 09adbe70… |
| libims.lge.so (SG Pie) | /data/models/joan-asia-kdz/h930ds-ims/ | 18,695,040 B, sha256 0e48b415… |
| Ims6.apk (Java layer, 3×) | each variant's Ims6-unpacked/ | classes.dex + assets |
| Ims6.odex/.vdex (US998) | extract/Ims6/Ims6/oat/arm64/ | compiled |
| system images (ext4) | v300l-parts/, h930ds-parts/, joan-stock/system.img | debugfs-searchable |
| KDZs | H930DS30c, V300L30p, US99820h, US99830b.zip | last two unextracted |

## Key structural findings

### 1. The three libims variants are symbol-identical in surface
41,172 / 41,177 / 41,172 defined dynamic symbols — one engine, all
carriers. Subsystem symbol counts (identical across variants): RCS/Chat
3191, IPsec/IKE 454, presence/XCAP 370, WFC/ePDG 344, emergency 202,
register/AKA 145, SRVCC 128, video 78, SMS 61, USSD 40, DTMF 20.

### 2. Config vocabulary: 29 config classes, 389 KEY_* literals
The KEY_* static members are the literal strings packed in .rodata
(read directly at the symbol address; verified across variants — 0
diffs). Classes: AoSRegConfig (32: retry ladders, tcp_criterion
ipv4/ipv6, ipsec_algs, auth_max_count, retry_pcscf_count,
block_conditions, dscp_value, reginfo_contact_match_rule…),
AoSConditionConfig (17: isim_index_for_impu/pcscf, pcscf_port,
multiple_discovery_scheme, pcscf_changed_control,
network_suspend_condition, **RAT rovein/roveout thresholds for
LTE/3G/2G/WIFI**), AoSConnectionConfig (10: profile_name, ip_version,
access_policy, service_in/out_time, rat_guard_interval, **epdg_scheme**),
SipConfig (15: compact_form, device_id, sip_features, tcp_criterion_len,
expiration, **methods**, subscription…), plus EAB (70), Video (46),
AV (42), MediaSession (26), Subscriber (16), codec configs (EVS/H264/
H265/H263/AMR/T140/telephone-event), Presence/XDM/Network/HTTP/Stats/
Text/Engine.

### 3. The carrier values ship in the Ims6 APK as per-carrier XML
`Ims6.apk/assets/Configuration/<CARRIER>/<CC>[/<variant>]/
configuration.<CARRIER>.<CC>[.<variant>].xml` — 342 files, ~155 carrier
families, every region. Each file is the LG `lgims` DB schema with
tables lgims_db_info, lgims_aos, lgims_aosapplication, lgims_aosreg,
lgims_aosconnection, lgims_aoscondition, lgims_sip, lgims_aosprovider,
+ media xml per carrier. Provisioning/ has att_ipme + tmus_rcs_spirent
templates; SmartConfiguration/ has SmartConfiguration.xml.
Runtime chain: XML asset → /data/user_de/0/com.lge.ims/databases/
ims_simoperator.db + lgims.db (Java layer) → libims
UCSessionConfig::readDB / updateConfig (native).

### 4. T-Mobile US NAO (the bench carrier) — decoded values
- global `common_tcp_criterion_len = 1200`; **per-reg criterion ipv4/ipv6
  = 0 (disabled)** — REGISTER never leaves UDP, confirming alpha9/10
- REG retry ladder `120,240,480,960,1920,3840,7200` ms; base 30 s, max
  1800 s; `authentication_max_count = 3`; `refresh_3gpp_standard=true`;
  `reg_expiration = 600000`; `ipsec_algs = 0x00010003`;
  `reginfo_contact_match_rule = 2`
- condition: `pcscf_port = 5060`, `multiple_discovery_scheme = 2`,
  `isim_index_for_impu/pcscf = 1`
- connection: `common_mpdn=true`, profiles mobile_ims /
  mobile_emergency / mobile_internet / **wifi** (VoWiFi; access_policy
  0x0100FFFF, epdg_scheme=0)
- sip: `common_sip_features = 0x151A001B`, method set
  `INVITE,BYE,CANCEL,ACK,NOTIFY,UPDATE,REFER,PRACK,INFO,MESSAGE`,
  `reg_subscription=true`, `sub_expiration=600000`
- aos: service ids lgims.com.service.sms / .uc / .voip / .emergency

### 5. WFC/ePDG surface (native)
Strings + symbols: EPDG_PREFERRED / EPDG_ONLY / EPDG_CALL_CELLULAR_HO_
BLOCKED / EPDG_PREFERRED_OOS / EPDG_PREFERRED_SESSION modes;
`HandleEpdgHoStateChanged`; `NetConnectionWatcher::NotifyWifiEarlyRoute
SetupChanged`; DSCP 46 marking for ePDG traffic; `ProcessWFCAPSetting
Changed`; IsePDGEnabled gating of deREGISTER/REGISTER. libims embeds
its own IKE/IPsec (454 symbols) — stock does not use a system IKE
daemon for ePDG.

### 6. Conference path (carrier-generic)
Already extracted: factory URI (3GPP TS 24.147), per-carrier
GetConfURI overrides, Conference/ConferenceRefer/ConferenceSubMngr/
ConferenceSubscription/ConferenceInfo class family, RFC 4579
conference-info subscription (initial SUBSCRIBE, 21600 s expiry), RFC
3515 REFER with Replaces, RFC 5366 multiple-refer + resource-lists
(norefersub honored).

## Work plan (alpha11 → full stack)

- alpha11: conference merge (REFER + SUBSCRIBE/NOTIFY + conference-info
  codecs + AOSP conference session) + carrier profile layer distilled
  from the XML tree (own format, values as facts — no LG files shipped)
- alpha12: VoWiFi/ePDG (own IKEv2 client + ePDG discovery + WFC mode
  state machine, gated on carrier profile)
- alpha13: SMS over IMS (SIP MESSAGE, 3gpp.sms body) + USSD over IMS
- alpha14: DTMF (INFO + telephone-event), reg event package, misc
- per-carrier rollout: TMUS (bench-proven), CMCC (tester loop), then
  on demand from the 155-carrier tree
- RCS: research only for now (biggest surface; not requested as a
  priority)

Boundaries maintained: extract-only RE, no LG code/blobs shipped, no
KDZ flashing (US998/H932 untouched), no auto-dial, config values
transcribed as discovered facts into our own profile format.
