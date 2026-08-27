# How joan VoLTE was gotten working (2026-08-26)

Written-by: Aurel Nymvale (agent-aurel)
Agent-harness: Hermes-Agent:xai-oauth/grok-4.6
Date: 2026-08-26

This is the durable bank of the **pmOS AP-side VoLTE** result and the
**LineageOS 22.2** follow-on. It is evidence, not a recipe to paste
secrets from. Do **not** log IMPI, nonce, RES, CK, IK, or AKA
payloads. Identity lives in `0600` env files, never in git.

Two tracks:

| Track | What is proven | What is not |
| --- | --- | --- |
| **pmOS (mainline RAM boot)** | IMS PDN, SIP REGISTER 200, **MO VoLTE call** (INVITE 180/200, PCMU, BYE), SMS both ways | MT INVITE delivery, kernel xfrm on mainline 7.2, MMS, q6voice AMR on the IMS path |
| **LineageOS 22.2 official** | Recovery zip installs helper; `FEATURE_TELEPHONY_IMS` + ImsService bind; **IMS PDN up** with TMO P-CSCF list | SIP REGISTER on LOS, Dialer MO/MT, zip-complete product |

pmOS MO VoLTE is the result Lance asked not to forget.

---

## 0. Architecture (the thing that unblocked everything)

LG V30 (`joan` / MSM8998 / MPSS) does **not** run IMS inside the
modem. There is no QMI IMSS (service 18). `IMSA InvalidHandle` and
VSS `0x707` SUCCESS-without-SIP were **expected**.

Stock does this:

- `com.lge.ims` (`Ims6.apk`) + `libims.lge.so` = full **AP SIP UA**
  (`TMUSAoSRegistration.cpp`: REGISTER/INVITE, AKAv1/v2-MD5,
  Security-Client, 3GPP IPsec).
- qcril VSS messages `0x702` / `0x707` / `0x708` / `0x709` are
  **status SET/notify after an IMS app exists**. They do not start
  SIP.
- CAF `org.codeaurora.ims` / OpenIMSd talks to **modem IMSS**. That
  is the wrong architecture for joan. Do not ship it. Do not load
  `libvssims-impl.so`.

Portable shape (all joan models, including H932):

```
ISIM identity  →  IMS PDN (IPv6)  →  P-CSCF  →  REGISTER+AKA
               →  3GPP IPsec (kernel xfrm preferred)  →  INVITE/RTP
```

H932 vs US998 differs in **bootchain signing keys only**
(`abl`/`xbl`/`tz`/`hyp`/`keymaster`/`laf`). Same SIP client. Never
cross-flash those partitions.

Universal 3GPP core + carrier overlay (T-Mobile US first:
`ims-sip/profiles/tmo-us.yaml`). Not a T-Mobile-only fork.

---

## 1. pmOS — how the first real VoLTE call happened

Live known-good RAM image (do **not** replace with ESP images):

- nest `~/joan-images/boot-joan-mbhc-no.img`
- sha256 `2c3120fd0d34086927070b9ea5aa54dc8aba85c3b8cc8138077c9a5056f5dac3`
- kernel tree: `linux-mainline-v30-wcd934x-init` /
  `joan/afe-cdc-slimbus-cfg-test`

### 1.1 Radio / bearers

- T-Mobile `310260`. IMS APN IPv6-only. **No IPv4 route is required**
  for this VoLTE (Lance confirmed).
- Internet PDN on `qmapmux0.0` (IPv6 4/4).
- IMS PDN on ModemManager `qmapmux0.1` via **81voltd 1.2.0** (QRTR
  770). Do **not** steal MM's WDS CID; do not hand-roll mux-id=2
  (that iface TX'd with RX=0).
- Clock aligned to America/Los_Angeles (SBC/AKA are time-sensitive).

### 1.2 P-CSCF discovery

Tried and failed first: WDS PCO request with no P-CSCF TLV, 81voltd
Start/Stop-only IDL, DSD get-apn-info InformationUnavailable, no
DHCPv6 SIP options.

**What worked:** WDS Get Current Settings **TLV 0x2e** = count + IPv6
list. On TMO these are ULA P-CSCFs (`fd00:976a:…`). Stock
`GetFromPCO` is Android Binder transact 410; on pmOS we read QMI
directly.

XML fallback `66.94.3.103:5060` exists in LG NAO config; live path
used TLV 0x2e, not the XML lab IP.

### 1.3 First REGISTER (unprotected)

- `From`/`To` = IMPI → Ericsson SBC **403** `CC_IMS_IDENTITY_MISMATCH`.
- `From`/`To` = ISIM **IMPU**, Authorization username = **IMPI** →
  **401 Unauthorized**, `algorithm=AKAv1-MD5`,
  `Security-Server: ipsec-3gpp` / `hmac-sha-1-96` / `aes-cbc` / ESP
  transport. Warning host `wsc*.cnf*.icf.sip.t-mobile.com`.
- Realm already on ISIM: `msg.pc.t-mobile.com`.

Offline builders (self-test OK, no secrets in tree):

- `ims-sip/joan_ims_pco.py`
- `ims-sip/joan_ims_register.py` (RFC 3310 AKA digest)
- `ims-sip/joan_ims_ipsec.py` (4 ESP SAs as `ip xfrm` print-only)
- `ims-sip/joan_ims_ua.py` (401 → xfrm → second REGISTER)

### 1.4 AKA + IPsec + second REGISTER

- ISIM AUTHENTICATE APDU **P2=0x81** → RES 8 / CK 16 / IK 16 (RAM
  only).
- Mainline `.config` had `CONFIG_INET6_ESP=n` and `CONFIG_XFRM_USER=n`,
  so ESP was **userspace Python** (AES-CBC + HMAC-SHA1-96). This is a
  lab shim, not the portable product path.
- Second REGISTER without `Security-Verify` → **494**.
- With `Security-Verify` → **SIP/2.0 200 OK** `CC_NO_ERROR`.

### 1.5 First live MO call (the result)

INVITE to Lance's Google Voice (authorized test endpoint):

1. 100 Trying
2. 180 Ringing (heard on the GV device)
3. 200 OK (PCMU + telephone-event)
4. ACK
5. ~11 s / 548 PCMU frames — TTS: *“Hi Lance it's Aurel, Joan just
   rang you over open source VoLTE…”*
6. BYE 200 OK

Lance confirmed the **voicemail** with that exact message.

Media was **AP PCMU**, not q6voice/AMR. Signalling + one-way PCMU is
proven. Product audio (earpiece/mic on the IMS session) is a
separate lane.

### 1.6 SMS

ModemManager WMS, no extra daemon:

- outbound test SMS sent
- inbound reply received 2026-08-26 16:57 PDT

MMS needs `mmsd-tng` (not installed).

### 1.7 Why inbound (MT) went to voicemail

Repeated GV → joan MSISDN went straight to VM. Three stacked bugs:

1. **Contact used IPv6 privacy/temporary address.** Network never
   delivered INVITE. Fix: stable GUA + disable tempaddrs on the IMS
   iface.
2. After that, 84-byte ESP arrived on `spi-s` (P-CSCF keepalive).
   Userspace daemon spoke **UDP-inside-ESP only** and ignored them →
   UE looked unreachable.
3. Later 40-byte ESP with **next-header=6 (TCP)**. Userspace ESP was
   UDP-only. Transport mismatch.

MT is **not** proven. Kernel xfrm (UDP+TCP policies) is the intended
fix; see quarantine below.

### 1.8 Packaging (pmOS, not the current LOS focus)

`~/vibe-coding-projects/coding/lg-v30-joan-pmos-packages/`

- `joan-imsd/` — UA + OpenRC
- `lge-joan-volte` metapackage
- `FIRST-INSTALL-VOLTE.md`

Phosh Calls still uses MM **CS** Voice. T-Mobile US has no CS.
`joan-ims dial` is the VoLTE path until an MM IMS plugin exists.

---

## 2. Kernel IPsec on mainline 7.2 — PARKED

Product path is kernel `xfrm` / `INET6_ESP`, not Python AES.

| Image | Config | Result | Action |
| --- | --- | --- | --- |
| `boot-joan-mbhc-esp.img` | `INET_ESP=y` `INET6_ESP=y` `XFRM_USER=y` | paging oops `ffff929d48e94…` then reboot to LOS | **Do not re-boot** |
| `boot-joan-mbhc-esp-mod.img` | Librem 5 pattern: XFRM=y, ESP=m | hung (sshd never up) then crash to LOS | **Do not re-boot** |
| `boot-joan-mbhc-no.img` | no extra ESP | known-good; userspace ESP | use this |

Peer comparison (why builtins were a bad guess): QCOM pmOS kernels
often leave ESP **off** (modem IMSS). Librem 5 / PinePhone Pro use
`XFRM_USER=m` `INET6_ESP=m` `INET_ESP=m`. Keep **both** INET_ESP and
INET6_ESP for a universal joan stack (other carriers may use IPv4
P-CSCF). TMO IMS here is IPv6-only.

USB red herring: gadget `18d1:d001` while pmOS ran was **initramfs
default**, not a lost kernel fix. usb-moded already `1d6b:0104`.

`sysrq-b` without `sync` zeros `updates/*.ko`.

---

## 3. LineageOS 22.2 — how far the zip got (not calls yet)

User goal: inbound **and** outbound VoLTE on official
`lineage-22.2-20260823-nightly-joan` via a **Lineage recovery
flashable zip**, no Magisk, no stock Ims6.

Staged (SHA256 verified): `/data/models/joan-lineage-22.2/`

- LOS zip `0941ddb83cee593718bc7dc8c11f20506469a61f5fa2ebad1fda9263232b16a2`
- MindTheGapps 15 ARM64 `20250812_214357`
- recovery.img

Flash order: Lineage → GApps (skip signature, error 21) → VoLTE zip
(skip signature). TWRP 3.7 cannot apply this ROM's dynamic
partitions; it was only used to install Lineage recovery.
`fastboot reboot recovery` after flashing TWRP once booted LOS20 —
RAM-boot TWRP or TWRP “reboot recovery”.

Project: `~/vibe-coding-projects/coding/joan-volte-lineage/`

LOS 22.2 kernel already has `XFRM=y` `XFRM_USER=y` `INET_ESP=y`
`INET6_ESP=y` (unlike mainline 7.2). That is why LOS is the IPsec
host for the next REGISTER, not another ESP RAM boot.

### 3.1 Why Telephony never bound IMS at first

`PhoneGlobals` only constructs `ImsResolver` if
`PackageManager.FEATURE_TELEPHONY_IMS` is present. joan vendor
permissions had **no** `android.hardware.telephony.ims.xml`.
Every `cmd phone ims set-ims-service` returned **false** because
`mImsResolver == null`.

Fix (must be in the zip): `/vendor/etc/permissions/android.hardware.telephony.ims.xml`

After reboot:

- `pm list features` shows `telephony.ims` + `.volte`
- `set-ims-service -d org.joan.ims` → **true**
- Phone bound `JoanImsService`

### 3.2 IMS PDN on LOS

`JoanImsService` `requestNetwork(NET_CAPABILITY_IMS)` (phh/ims
shape). First crash: missing
`CONNECTIVITY_USE_RESTRICTED_NETWORKS` (privapp XML). After grant +
reboot:

- `rmnet_data0` **UP**
- `MOBILE[LTE] CONNECTED extra: ims`
- P-CSCF list present (`fd00:976a:…`, same family as pmOS TLV 0x2e)

### 3.3 REGISTER on LOS — not on air yet

Java `priv_app` `DatagramSocket` → `EPERM`. Relabel via
`plat_seapp_contexts` to `platform_app` got past `socket()`, then
`sendto` `EPERM`. Plat neverallow: **appdomain must not create
xfrm**. Native helper must stay vendor (`netmgrd` already has
`netlink_xfrm`; appended `ims` CIL did **not** load into the running
policy).

`joan-ims` C helper (static aarch64) is still an xfrm+UDP6 probe,
**not** the SIP UA. Zip installer issues already hit and fixed in
source:

- vendor unmounted → `cil backup empty (0)`
- vendor ro → `write /vendor/bin/joan-ims` fail → fallback `/system/bin`

Helper on disk after successful zip: `/system/bin/joan-ims`,
`u:r:netmgrd:s0`, running.

### 3.4 Honest LOS status

Dialer cannot place or receive IMS calls until:

1. REGISTER 200 from a domain that may `sendto` on the IMS network
   (native `joan-ims` in `netmgrd`, not Java)
2. AKA + kernel xfrm (LOS kernel already has ESP)
3. `MmTelFeature` reports registered + `shouldProcessCall` IMS
4. INVITE + media

CIL-on-disk ≠ loaded policy. Source ROM build is the safe neverallow
path; zip CIL append is what was asked for now.

---

## 4. Audio (same session, pmOS) — needed for a real handset call

Proven on `boot-joan-mbhc-no.img`:

- Headphone jack **stereo heard**
- Earpiece **heard and felt**
- Boot unplugged jack: `[off]` / SW=0 / Z=0 after dropping DTS
  `qcom,hphl-jack-type-normally-closed` (was stuck `[on]` on NC)

Still pinned:

- **Live** jack edges dead on both NC and NO (NO only fixes boot
  default)
- Auto-mute + mics still open
- IMS media was PCMU to GV, not the handset codec path

---

## 5. Do-not-repeat / quarantine

- Do not flash stock. Do not load Ims6 / `libvssims-impl.so`.
- Do not treat CAF `ims-ship` as joan VoLTE.
- Do not re-boot `boot-joan-mbhc-esp.img` or `boot-joan-mbhc-esp-mod.img`.
- Do not log IMPI/nonce/RES/CK/IK.
- Do not steal MM WDS CID; use `qmapmux0.1`.
- Do not leave the phone in fastboot unattended (LG auto-shutdown).
- adb on pmOS: libusb futex deadlock; use
  `/tmp/joan-bootloader-restart.py` (syscall 142 `"bootloader"`).
- Do not use USB gadget `18d1:d001` as “the kernel lost USB”.
- Java ImsService cannot program xfrm (plat neverallow).
- Slot_id=1 on VSS dropped PDNs — do not repeat.

---

## 6. Artifact index (no secrets)

| What | Where |
| --- | --- |
| pmOS UA + profiles | `~/.ember/workspace/joan-cellular-2026-08-23/ims-sip/` |
| Architecture notes | `~/.ember/workspace/joan-cellular-2026-08-23/LG-IMS-AP-SIP.md` |
| pmOS packages | `~/vibe-coding-projects/coding/lg-v30-joan-pmos-packages/` |
| LOS zip project | `~/vibe-coding-projects/coding/joan-volte-lineage/` |
| LOS/GApps zips | `/data/models/joan-lineage-22.2/` |
| Stock RE (do not flash) | `/data/models/joan-stock/from-nextcloud/` |
| Known-good boot | nest `~/joan-images/boot-joan-mbhc-no.img` |
| Journal (audio+cell) | `~/.hermes/journal/joan-audio-session-2026-08-25.md` |
| This bank | this file + `joan-volte-lineage/docs/aurel-handoff-2026-08-26-joan-volte-bank.md` |

Kernel branch convention (Lance, verbatim): **master = verified
fixes**; **joan/latest-clean-test = booting but ugly commit
history** (try/revert record). Do not “clean” that history.

---

## 7. Next boundary (not done this bank)

1. Native REGISTER from `joan-ims` (`netmgrd`) on LOS IMS PDN.
2. Kernel xfrm on LOS (already in kernel) for second REGISTER + MT TCP.
3. Bind Dialer through `JoanMmTelFeature` once registered.
4. Do not re-open parked mainline ESP images to get (2).
