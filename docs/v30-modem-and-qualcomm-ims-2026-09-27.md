# Qualcomm's IMS on the V30, and what the modem has instead — 2026-09-27

Question asked: the V60 has working VoLTE on LineageOS using Qualcomm's IMS
binaries. Could the V30 use them too?

Answer: no. The V30's modem was built without the half of Qualcomm's IMS
those binaries drive, and a different modem image cannot be loaded. The
check did turn up LG's own modem-side voice media engine, which is a lead
for call audio in a LineageOS build (not the zip). Evidence and the steps
to reproduce follow.

## How Qualcomm's IMS is split

- **Modem:** the IMS core — registration, SIP, call control, SMS over IP —
  exposed to the AP as QMI services (IMSA, IMSS, IMSP, IMS DCM, IMS RTP).
- **AP:** `ims.apk` (the ImsService), `imsqmidaemon`, `imsdatadaemon` (brings
  up the IMS PDN for the modem), `ims_rtp_daemon` (video RTP), `imsrcsd`,
  and the `IImsRadio` HAL inside `qcrild` (`qcril_qmi_imsa_*`,
  `qcril_qmi_imss_*`). None of these speak SIP themselves.

The V60's LineageOS tree ships the AP half (`timelm` lineage-22.2
`proprietary-files.txt`, the IMS block around lines 943–994, plus
`ims-ext-common` in `device.mk`). It works because the V60's modem has the
core.

## The V30's modem has no Qualcomm IMS core

Source: US998 30b `modem.img` (FAT; `mcopy -s` with mtools), build
`MPSS.AT.2.5.c1.2-00056-8998_GEN_PACK-1`.

The modem ships a QShrink 4 message database, `image/qdsp6m.qdb`: a 64-byte
header, then one zlib stream that inflates to about 21 MB of
`<hash>:<ss_mask>:<ssid>:<line>:<file>:<string>` rows, one per debug message
compiled into the modem. The file column is therefore a list of the modem's
source files: **5,092 of them.**

- **Missing: every Qualcomm IMS core file.** There is no qipcall, `sip*`,
  `qvp*` or `ims_*` source, and no IMSA, IMSS, IMSP or DCM QMI server. The
  only `qp*` files are `qpa26xx` RF power-amplifier drivers.
- **Present: LG's own QMI services, prefixed `qmi_vss_`.** There are 15 files,
  logging as `[LGE_VSS_QCSI]`. They include `qmi_vss_ims_server.c` and
  `qmi_vss_ims_service.c`, which handle `qmi_vss_lge_ims_media_req_v02` and
  send `QMI_VSS_IMS_MEDIA_IND_MSG_V02`.
- **Present: the hooks an IMS client running outside the modem uses to talk
  to it.**
  - `qmi_nas_ims_extn.c` takes IMS call state
    (`MM_CM_EXT_VOLTE_CALL_STATE_IND`), VoLTE mode, E911 state and T3346.
  - `qmi_voice_ims_extn.c` handles SRVCC hand-in.
  - `lge_ltecall.c` is also present.

**The carrier modem configs agree.** Each
`image/modem_pr/mcfg/configs/mcfg_sw/generic_/joan_nao/na/{att,cca,lra,sprint,tmo,uscc,verizon_}`
MCFG carries only `/nv/item_files/ims/IMS_enable` plus a few items:
- `mmode` domain-selection items (`ims_reg_status_wait_timer`,
  `allow_csfb_upon_ims_reg`);
- for Verizon, the hVoLTE items and the `vzwims` APN.

A Qualcomm-IMS phone's MCFGs carry dozens of IMS items (SIP timers, codecs,
registration and SMS settings).

**The stock Android side agrees.** The VS996 Pie vendor has no
`imsqmidaemon`, `imsdatadaemon`, `ims_rtp_daemon` or `ims.apk`. The
Qualcomm pieces it does have come from building on Qualcomm's tree and have
no server to reach:
- `qcrild` with `qcril_qmi_imsa_*`;
- `IImsRadio/imsradio0` in the vendor manifest;
- `lib-rtpcore.so`, `lib-rtpsl.so` and `lib-rtpdaemoninterface.so`, with no
  daemon linking them.

LineageOS `android_device_lge_joan-common` lineage-22.2 likewise ships
`vendor.qti.hardware.radio.ims@1.0`–`1.7` for its `qcrild` and no IMS
daemons or ImsService.

**Swapping the modem image is not possible.** MPSS images are authenticated
at load against the OEM root key fused into the SoC. A Pixel 2 or OnePlus 5
modem (msm8998, with the core) will not load on a V30.

**Verdict.** Qualcomm's AP IMS binaries would start, find no IMSA service,
and never register. Do not spend time porting them to the V30.

## What the modem has instead: LG's media engine

66 of the modem's source files are LG's MMPF media framework and its RTP
service:

- **Graph and session:** `MMPFSession`, `MMPFGraph`, `MMPFGraphTx`,
  `MMPFGraphRx`, `MMPFGraphRtcp`.
- **RTP:** `MMPFRTPSession` (calls `LGIMS_RtpSvc_SessionEnableRTP` and
  `...EnableRTCP`), `RtpService`, `rtpmain`, and RTP payload encoders and
  decoders for audio and for text (real-time text, with redundancy).
- **DTMF:** encoder and sender.
- **Jitter:** `MMPFJitterBuffer`, `MMPFJitterNetworkAnalyser`,
  `MMPFNodeJitterControl`.
- **Voice:** `VoiceSource`, `VoiceRenderer`, `MMPF_AMRFmt`.
- **`MMPF_Voice_QCT_CVD.cpp`:** the voice adaptor into Qualcomm's Core Voice
  Driver. It requests the vocoder from the modem's Voice Agent ("Wait
  GrantedVocoder Call Back by Voice Agent") and sets AMR mode and SCR, and
  EVS mode, bandwidth, bitrate and channel-aware mode.
- **`MMPFSocket.cpp`:** opens sockets on the modem's own data stack
  (`dss_open_netlib`, `dss_pppopen` on the IMS APN's PDP profile).
- **`MMPFSocketBridgeProxy` / `MMPFSocketBridgeStub`:** a bridge, likely for
  bearers the modem does not own (VoWiFi rides the AP's ePDG tunnel).
- **Reporting:** RTCP-XR plus analytics reporters (CIQ, MOCA, DRA, LDB,
  HASATI, CCT).

On this firmware LG's IMS could keep a call's voice media entirely on the
modem:
- RTP runs on the modem's IMS bearer.
- The codec and audio path go through the same DSP chain a circuit-switched
  call uses.
- The AP does SIP and sends per-call media parameters through `vss_ims`.

The US998 20h AP library `libimsmmpf.lge.so` has the same class names. See
`docs/audio-quality-vs-reference-stacks.md`, which compares joan against
that AP copy. Which engine stock used for plain VoLTE voice is **not proven
yet**. A stock-firmware logcat or modem trace of one call would settle it.

## The lead, and why it is not a zip feature

On stock (VS996 Pie vendor), the path from the AP to the modem is:

```
vendor.lge.hardware.vss_ims@1.0-service        /vendor/bin/hw, started by
                                                /vendor/etc/init/vendor.lge.hardware.vss_ims@1.0-service.rc
  -> vendor.lge.hardware.vss_ims@1.0-impl.so -> libvssims-impl.so
  -> libvss_ims_qcci.so   (QCCI client)
  -> modem qmi_vss_ims    (qmi_vss_lge_ims_media_req_v02)
Java stubs: /system/framework/vendor.lge.hardware.vss_ims-V1.0-java.jar
```

For joan to hand call media to the modem, all of this would be needed:

1. **The service on the device, with SELinux policy.** The HAL service and
   its libraries need to be on /vendor. Vendor SELinux policy needs a domain
   for the service and must let joan find it in hwservicemanager. LineageOS
   joan-common ships none of this. A flashable zip cannot add SELinux
   policy, so this belongs in the device tree (the upstream route).
2. **The message format.** The QMI IDL type tables in `libvss_ims_qcci.so`
   give the TLV layout of `qmi_vss_lge_ims_media_req_v02`. What goes inside
   is LG's own protocol and has to be recovered from `libimsmmpf.lge.so`
   and `libims.lge.so`.
3. **Audio routing.** The audio HAL has to route an IMS call onto the modem
   voice path (the VoLTE voice session). Confirm how LG's audio HAL is told
   about it.

Payoff: DSP echo cancellation and noise suppression, EVS, and the audio
path of a circuit-switched call, in place of joan's
`AudioRecord`/`AudioTrack` path on the AP. This is a research project. It
does not block anything joan does today.

## Reproduce

```sh
# modem.img from the US998 30b KDZ/zip (tools/lgfw/fetch_entry.py can
# range-read it out of the Drive archive)
MTOOLS_SKIP_CHECK=1 mcopy -s -n -i modem.img ::/ modemfs/
python3 - <<'EOF'
import zlib
d = open('modemfs/image/qdsp6m.qdb', 'rb').read()
open('qdsp6m.txt', 'wb').write(zlib.decompressobj(15).decompress(d[64:]))
EOF
awk -F: 'NR>20 {print $5}' qdsp6m.txt | sort | uniq -c | sort -rn > files.txt
grep -ciE ' (qipcall|sip|qvp|ims_)' files.txt        # 0
grep -iE 'vss|mmpf|rtp' files.txt                   # LG's engine
```
