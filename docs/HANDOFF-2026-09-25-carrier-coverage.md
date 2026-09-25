# Handoff — 2026-09-25 — upgrade bootloop fixed, Verizon/USC unblocked, carrier ids

Assisted-by: Claude Code
Branch: `claude/serene-bardeen-qj0tc6` (pushed). Latest build: **0.4.0-alpha77 (vc87)**.

## Goal this work is serving

From the owner: reverse-engineer LG's IMS and other IMS stacks to support as
many carriers as possible, delivered two ways — an upstreamable drop-in for a
LineageOS build, and a flashable zip for LG phones already on LineageOS 22.2
(or a LineageOS-based ROM) — and build a LineageOS image with it if feasible.
The owner shared the LG V30 firmware archive (Google Drive folder
`1UutsnwTRZvzmmdKNKJdOw_ryiNA5THrr`; table below).

## Commits this session

| commit | what |
|---|---|
| `09be93e` | **alpha76: the bootloop every build after alpha67 caused on upgrade.** Root cause, allowlist made append-only, installer v8, e2e installer test. |
| `639f583` | **Register without IPsec** where the carrier does not negotiate sec-agree (Verizon, U.S. Cellular, and 9 more). |
| `7de5bfe` | **Carrier profile by Android carrier id**; 149 profiles ship (was 136); PLMN map 400 rows (was 294); MCC guessing removed; `Android.bp` shipped no assets. |
| (this) | alpha77 build, this handoff, RE helpers in `tools/lgfw/`. |

## 1. The upgrade bootloop (fixed in alpha76, verify on hardware)

Owner reported "alpha67 is the last version that flashes cleanly". Cause,
verified against Android 15 source (`android15-qpr2-release`):

- `PackageCacher.isCacheFileUpToDate`: a cached parse is reused while
  `pkg.st_mtime < cache.st_mtime`. For a priv-app the scanned path is the
  **directory** `/system/priv-app/JoanIms`; overwriting the apk never changes
  it, and recovery's clock reads 2017. So after every flash PackageManager
  kept parsing the **previous build's manifest** (this is also why `dumpsys`
  showed stale versions for months).
- alpha70+ removed `BIND_IMS_SERVICE` from the manifest **and** the privapp
  allowlist. A phone upgrading from ≤alpha67 still requested it via the cached
  manifest → `PermissionManagerServiceImpl.onSystemReady()` throws
  `IllegalStateException` (privapp violation, enforce mode) → system_server
  dies → bootloop. Fresh installs had no cache entry, so they worked.
- Also: alpha75's installer had dropped the deletion of the alpha70-73
  `joan-grant.rc`, so 73→75 kept that service too.

Fix: allowlist is append-only (now lists every privileged permission any
manifest in git history requested, incl. `CAPTURE_AUDIO_OUTPUT` from v0.1–0.2;
host gate walks git history and derives protection levels from the SDK's
platform manifest); installer `stamp_future` re-dates scanned paths to
2037-12-31; atomic write-then-rename copies; free-space pre-check; optional
steps (default grants, APN merge) warn instead of abort; boot-grant removal
restored; rollback removed. `tests/installer/run-e2e-install.sh` runs the real
`update-binary`/uninstaller under **mksh and busybox ash** on loop-mounted ext4
images (fresh, alpha67 upgrade, full system, tight product, re-flash, ROM
replaced APN list, /system-only APN list, uninstall). Against the alpha75
installer it fails 31 checks. Needs root + `losetup`; skips otherwise.

**Permissions (owner asked to stop shoehorning them):** nothing runs at boot.
`etc/default-permissions` now includes `RECORD_AUDIO`; Android applies it on
the first boot after the build fingerprint changes, i.e. when the zip is
flashed in the same recovery session as a ROM install/update. Otherwise the
"joan IMS" launcher entry, or `scripts/grant-permissions.sh` (plain
`adb shell pm grant`, **no root needed**).

Recovering a phone bootlooping from alpha70-75: sideload alpha76+, or the
uninstall zip, or dirty-flash the ROM. `docs/install-troubleshooting.md` §0.

## 2. Non-IPsec registration (alpha77, untested on a live core)

LG's config has `aos_reg_0_ipsec=false` for **VZW (all variants), USC, SPR,
BEE.RU, MGF.RU, CSL/H3G/PCCW HK, EST.CA, FRD.CA, TELE.BG** (EU GPRI adds
284-05). joan rejected any 401 without `Security-Server`, so none could
register — Verizon included. Now: profile `ipsec` → `JoanSipBuilder.setSecAgree`;
REGISTER without sec-agree headers; a 401 with no Security-Server proceeds
unprotected (`sec=none(profile|network-declined)` in the trace); a 420 to
sec-agree is remembered per PLMN; REG2 from the attempt's fresh port to the
P-CSCF SIP port; UA adopts one socket for both roles
(`JoanAppRegister.reg2Unprotected`). INVITE no longer sends
`Require: sec-agree` without a `Security-Verify`.

**Ask a Verizon or U.S. Cellular tester for** `last_register` and the capture.

## 3. Carrier resolution (alpha77)

Order: Android carrier id (`getSimSpecificCarrierId`, then `getSimCarrierId`,
only if the SIM's PLMN matches) → `carrier-id-map.json` → `carrier-plmn-map.json`
→ CMCC MNC rule → 3GPP defaults. Trace shows `via=carrier-id:N|plmn:X|rule|default`.
Regenerate everything with:

```
tools/regen-carrier-assets.sh <Ims6 assets dir> <carrier_list.textpb>
```

Inputs used: VS996 30d `Ims6.apk` assets (same 169-file world set as H932
30d) and AOSP TelephonyProvider `assets/latest_carrier_id/carrier_list.textpb`
(android15-qpr2-release). Curated ids are in `tools/make-carrier-id-map.py`.
Rakuten, China Unicom/Telecom deliberately get defaults (LG ships nothing).

## 4. What was learned (not yet acted on)

- **Android 15 synthesizes an IMS APN** ("DEFAULT IMS", APN `ims`, IPv4v6)
  when the SIM has none (`DataProfileManager`). Only ~15 LG IMS APNs are not
  literally `ims`: Tele2 RU `ims.tele2.ru`, 3 HK `ims.lte.three.com.hk`, and
  IMS-over-internet-APN carriers (Fastweb `apn.fastweb.it`, spusu
  `webapn.at`, T-2 SI). A generalized APN merge would help only these; the
  Viettel merge is the only one shipped.
- **Verizon specifics** (LG `VZW.US.NAO`): no IPsec; `sdm_sms_format=3gpp2`;
  T1=3 s, Timer F 30 s; no SDP preconditions; session refresh by UPDATE,
  Session-Expires 300; target `tel:` in local format; PPI in REGISTER;
  `vzims.com`; UA `LG---#MODEL_TRIM1#---#SW_VERSION_SHORT`.
- **Dial target:** joan sends `tel:<digits>` without `phone-context` for
  local numbers (RFC 3966 requires it; TS 24.229 5.1.2A.1.5 says how).
  AOSP's rule is in `native/libimsstack/enabler/mtc/dialingplan/` and
  `config/common/ImsIdentity.cpp` (`GetPhoneContext`). Was being read when
  this session stopped. Likely matters for Verizon (`number_format=local`).
- **LG OP partition** (`OP.img`, open-market SKUs H930/H930DS only) holds
  `OPEN_EU/config/ims_conf.xml`: 91 per-PLMN "GPRI" sets with ~90 standard
  keys (`volte_sip_ipsec_enable`, `..._authentication_method` aka-v1/v2,
  `..._provisioning_method` isim/usim, `volte_sms_over_ip_sms_over_ims`,
  timers, XCAP/BSF, CS-retry codes). Not yet transcribed into profiles.
  `vo_config.xml` = LG's per-PLMN VoLTE/VoWiFi enable flags. SMS-over-IMS is
  on for 62 of 91 EU entries.

## 5. Next steps, in priority order

1. **SMS over IMS** (task: Verizon and Jio need it; LTE-only networks have no
   SGs). Reference: AOSP ImsStack `java/.../imsservice/mmtel/sms/` +
   `native/libimsstack/enabler/mts/` (Apache-2.0, same licence). Facts
   already extracted:
   - framework `sendSms(token, ref, format, smsc, isRetry, pdu)`: `smsc` is a
     **hex** SCA (`[len][TON/NPI][BCD]`) or null (then use
     `SmsManager.getSmscAddress`); `pdu` = TPDU.
   - MO: SIP MESSAGE, `Content-Type: application/vnd.3gpp.sms` (3gpp2 for
     VZW), `Request-Disposition: no-fork`, Request-URI `tel:+<SC>` (or
     `sip:+SC@domain;user=phone`), body RP-DATA (MTI 0). Wait TR1M=195 s for
     the network's RP-ACK (MTI 3) / RP-ERROR (MTI 5) in an incoming MESSAGE.
     On failure report `SEND_STATUS_ERROR_FALLBACK` so the framework retries
     over CS.
   - MT: incoming MESSAGE with RP-DATA (MTI 1) → 200 OK → `onSmsReceived(
     token, "3gpp", RP-OA(len-prefixed) + TPDU)`; the framework's
     `acknowledgeSms` → MESSAGE carrying RP-ACK (MTI 2) / RP-ERROR (MTI 4)
     to the **first P-Asserted-Identity** of the MT MESSAGE, with
     `In-Reply-To: <its Call-ID>`. TR2=20 s.
   - Advertise `+g.3gpp.smsip` and MmTel `CAPABILITY_TYPE_SMS` only where
     enabled (per carrier), `MESSAGE` already in Allow.
   - **Blocker to fix first:** the UA decodes/encodes SIP as US-ASCII
     (`JoanSipUa.recvTcpClient`, `JoanRegTransport.tryRecv`, every
     `getBytes(US_ASCII)`), which corrupts binary bodies. Switch to
     ISO-8859-1 (byte-transparent) consistently.
   - Needs an `ImsSmsImplBase` stub in `ims-service/stubs/` (SystemApi).
2. **phone-context on `tel:` targets** (section 4).
3. **Transcribe the EU GPRI** (`ims_conf.xml`) where it adds facts LG's Ims6
   profiles lack (AKAv2 carriers 24002/24004, 28405 no IPsec).
4. **More firmware**: H930DS `OP.img` (Asia GPRI, 310 MB compressed); US998
   and Korean V300K/S/L `system.img`; H933 and AS998 exist only as KDZ (write
   a KDZ/DZ chunk reader — chunks are zlib with target offsets, so
   range-fetching only needed chunks is possible).
5. **Upstream** (`upstream/`): produce actual patches for
   `LineageOS/android_device_lge_joan` / `msm8998-common` lineage-22.2
   (`config_device_volte_available` overlay, AGC declaration, inherit line).
   `Android.bp` was fixed this session but has never been through Soong.
6. **LineageOS image**: a source build does not fit (30 GB disk here). Plan:
   take the official joan 22.2 OTA, unpack system/product images, inject the
   same files the zip installs (plus default-permissions and the fw overlay),
   repack, re-sign with test keys. Not started.
7. Hardware verification of alpha76/77: upgrade from alpha67 boots;
   `dumpsys package org.joan.ims` shows the new version; Verizon/USC register.

## Environment notes for the next session

- SDK: `sdkmanager "build-tools;36.1.0" "platforms;android-36" "platforms;android-35"`
  into `~/Android/Sdk` (commandlinetools from dl.google.com works).
- e2e installer test needs `apt-get install mksh busybox-static toybox`, root,
  and loop devices; it skips cleanly without them.
- GitHub web/API is blocked by the proxy; `git` over https works (LineageOS
  repos clone fine). android.googlesource.com works (`?format=TEXT`).
- **Firmware without downloading it all:** the Drive files support HTTP
  `Range`. `tools/lgfw/httpfile.py` is a seekable range-backed file;
  `python3 tools/lgfw/fetch_entry.py <drive_id> <zip entry> <out>` streams
  one entry of a stock-ROM zip to a sparse file. The stock zips' `system.img`
  is trimmed: `truncate -s $((blocks*4096))` to the ext4 block count before
  `mount -o ro,loop`. Ims6 lives at `product/priv-app/Ims6/Ims6.apk` inside
  the system image (Pie).
- Drive ids (stock ROM zips): H930 31a `16R3gVTTsNwYOdzBLhA3eLIJwWIo3_Efv`
  (has OP.img), H930DS 30c `1Ib-0nQwUVJvuO0QG813e6ONoegEJXcrw` (has OP.img),
  H932 30d `1qR194dhBHkJMMQnY_0uXhRJbqizrm5R3`, US998 30b
  `1QlmvBMaBJuWJyx_3r3DlmLuozDvET-Q7`, VS996 30d
  `1EJsb6y_pX_KUSwdk6XekWoc4d9NchFFY`, V300K 30p
  `1wa8t2WNPoGsvCu1RIsxiGAlknT_8mJ4t`, V300S 30p
  `1sLeUBGumTRrAe8cLYl9lK6yFoC7etV1t`, V300L 30p
  `1-q7LaszWD-1hMXWlfAU380lSoPb6CuTT`. KDZ only: H933 30k
  `1Z7oAOb1w37ljyMdZf4mpI3zfGzucBvv5`, AS998 20b
  `1ZIgCosGTKaN7n_7v_nuBoUaw06UkiZeL`. (`gdown --folder` lists the rest.)
- Reference checkouts used: AOSP `platform/packages/modules/ImsStack` at
  `android-17.0.0_r1`, `platform/packages/apps/CarrierConfig` and
  `platform/packages/providers/TelephonyProvider` at
  `android15-qpr2-release`, `LineageOS/android_vendor_apn` (main).
