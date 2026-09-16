# joan-volte-lineage

VoLTE for the LG V30 (`joan`: US998 / H930 / H932) on **LineageOS 22**.
Flashable recovery zip. No Magisk, no stock `Ims6` blobs, no CAF
`OpenIMSd`.

SIP, AKA, and IPsec run in a privileged `ImsService` (`org.joan.ims`)
using public `IpSecManager` APIs. There is no native daemon and no
loopback control socket.

> ### Read this before you flash: use LineageOS recovery, not TWRP
>
> `joan` uses **dynamic partitions** — `/system` and `/product` live inside
> `super` and have to be mapped before anything can write to them.
> **LineageOS 22 recovery does that. TWRP on this device generally does
> not.**
>
> On a recovery that cannot map them, `/dev/block/mapper/` is empty or
> absent and the installer fails with:
>
> ```
> ERROR: cannot mount system (looked in /dev/block/mapper and by-name)
> ```
>
> That is the recovery, not the zip, and no zip can work around it — the
> tools to map a logical partition are not present in recovery. Flash from
> LineageOS recovery instead.

> **Working on LineageOS 22.2:** IMS REGISTER 200, Dialer outbound and
> inbound PCMU calls, two-way audio, hangup from either side. Speaker
> and earpiece follow Dialer. Caller ID is the asserted number; Dialer
> can still overlay a matching contact.

## Current tester build: v0.4.0-alpha20

`v0.4.0-alpha20` (versionCode 28) is the current tester zip. It is a
prerelease: offline suites passed; it is **not** a live-carrier qualifier
and has not replaced the last T-Mobile bench-validated build
(`v0.4.0-alpha12` on the development US998). Sideload
`joan-volte-recovery.zip` from the GitHub release, reboot, then confirm
the `build` row below reads `0.4.0-alpha20 (28)`.

What landed after the last public GitHub alpha (`v0.4.0-alpha16`), plus
the 2026-09-13 NOS/Viettel pass:

- **Self-contained traces.** Startup writes `trace init build=…`. Truncation
  reprints the last IMS network/data/attempt snapshot so a later excerpt
  still has MTU and family counts. Each REGISTER cycle also writes an
  `IMS attempt` line even when those summaries have not changed.
- **REG1 IPv6 TCP switch (RFC 3261 §18.1.1).** NOS's ~1630-byte IPv6
  REG1 now prefers TCP when the path MTU is unknown or the request is
  within 200 bytes of it. IPv4 (Viettel 401 over UDP) stays on the stock
  size criterion. T-Mobile 310-260 still never leaves UDP. A refused TCP
  connect may retry UDP; a connected TCP timeout does not.
- **REG2 receive counters.** A protected UDP timeout now prints
  `reg2_send_ok` / `reg2_rx` / `reg2_rejected` / `reg2_rx_err`, not only
  `reg2retx`.
- **Framework VoLTE admit overlay (alpha20).** `ImsManager
  .isVolteEnabledByPlatform()` ANDs three things: the framework-res bool
  `config_device_volte_available`, the carrier config
  `carrier_volte_available_bool`, and `isGbaValid()`. joan ships the
  framework bool **false**, so before this the VoLTE toggle did not
  render anywhere in Settings and every outbound call went CS — while
  REGISTER still succeeded and inbound calls still arrived, which looks
  exactly like a SIP bug and is not one. The zip now installs a runtime
  resource overlay setting that bool true. Verified on the development
  US998: the Settings toggle appears and reads ON, with no user-setting
  written. Uninstall removes the overlay. A ROM build should set this in
  the device tree instead — see `upstream/VOLTE-PLATFORM-SETUP.md`.
  The toggle lives at Settings -> Network & internet -> SIMs -> VoLTE;
  LineageOS ships no VoLTE quick-settings tile, so do not look there.
- **Default-on VoLTE admit gate (alpha19).** On boot the service asks
  Telephony to treat VoLTE as carrier-available for **any** carrier whose
  ROM leaves `carrier_volte_available_bool` unconfigured (the AOSP
  default is false, and most LOS trees ship no asset for smaller
  carriers). This is non-persistent: uninstall + reboot restores
  production values. The **opt-out is the stock Settings toggle**
  ("VoLTE" / "Enhanced 4G LTE Mode") — the framework honors it before
  every dial, so a carrier that cannot use IMS just has the user turn
  the toggle off. **Turning it off is not a guaranteed escape hatch:** it
  gives a working call only where the network still runs CS voice, and on
  a VoLTE-only carrier outbound calling stops entirely (observed on
  T-Mobile US). The gate also forces that toggle
  visible/editable when a restored carrier config would hide it, and it
  never runs before carrier config has loaded. Look for `volte_gate`
  rows (`applied` / `applied-visibility` / `skip:already-true` /
  `wait:config-not-applied`) when reporting. The admit is re-checked on
  carrier-config reload rather than remembered: the override lives in
  `com.android.phone`, so if that process restarts it is lost while this
  service keeps running. The service listens for
  `ACTION_CARRIER_CONFIG_CHANGED` and re-applies, because once registered
  the driver sleeps until the REGISTER refresh and would otherwise not
  look again for up to half an hour. A `reapplied=N` suffix on the row
  means the override had to be put back N times.
- **Viettel 45204 APN overlay (alpha18).** Recovery **merges** AOSP-shaped
  IMS (`apn=ims`) and XCAP/UT (`apn=xcap`) rows, plus IPV4V6 on internet,
  into the existing product `apns-conf.xml`. It does **not** replace the
  world list. Uninstall restores `apns-conf.xml.joan-orig`. This is a
  catalog patch, not a proven REG2/IPsec fix. AOSP has no Viettel
  CarrierConfig asset (carrier_id 1899); Joan does not invent one.

What landed after the last public GitHub alpha (`v0.4.0-alpha10`), plus
the 2026-09-13 fresh pass:

- **Hold / call waiting / ACK repair** from alpha10 is still there.
- **Network-hosted conference merge** is implemented (focus INVITE +
  REFER / Replaces + conference-info) and offline-tested. It is **not**
  live-carrier qualified. Two-call merge on a real core is still an open
  test, not a claimed pass. The focus dialog now keeps the negotiated
  media and local tag instead of a zeroed Leg.
- **Registration diagnostics** (`ims_diag_*` rows, REG1 send/rx/error
  counters, configured vs negotiated IMS protocol). Framework view only;
  `raw_modem_pco=unobserved` is honest, not a missing field.
- **P-CSCF discovery** uses the IMS link list, then ISIM literals if the
  link list is empty. No guessed `pcscf.ims.mnc…` DNS, no “SIM is not
  provisioned” verdict from an empty list.
- **IP-family flip retry** after dual-stack REG1 silence / REG2 timeout,
  consumed at REGISTER planning (not eaten in discovery).
- **LOS 22.2 ImsService ABI** (`ImsFeatureConfiguration` in
  `android.telephony.ims.stub`, `onDeregistered(ImsReasonInfo,int,int)`).
- **Refresh** keeps an active call and closes the previous UDP sockets /
  IPsec SAs before adopting the new binding.
- **AKA** follows AOSP EAP-AKA / TS 31.102: DB + length-delimited
  RES/CK/IK, 4–16 byte RES, exact 16-byte CK/IK. Untagged 48-byte blobs,
  DC sync-failure, extra CK hex, and non-9000 APDU status words are
  refused instead of being treated as keys.
- **NOTIFY / BYE** require both dialog tags; a tagless or wrong-dialog
  request does not tear down the live call. MO BYE matches the UAC local
  tag on From.
- **Remote CSeq is separate from local CSeq.** A peer re-INVITE is not
  compared against our outbound sequence; a mismatched-tag re-INVITE is
  481; a retransmitted answered INVITE replays the exact cached 200.
- **Ringing overwrite / CANCEL identity.** A second initial INVITE while
  one is ringing is 486; CANCEL must match Call-ID **and** the INVITE
  CSeq, otherwise 481 and the ringing INVITE stays.
- **Responses copy every Via and Record-Route** in original order
  (RFC 3261 §8.2.6 / §16.7). Extra headers are terminated before
  Content-Length.
- **TCP criterion is full PLMN** (MCC+MNC), matching stock’s size
  threshold: China Mobile 460-00 uses 1300; T-Mobile 310-260 stays on the
  proven UDP path; other PLMNs (including China Unicom 460-01 and
  unmapped MCC 310) use the stock GLOBAL 4096 threshold. `integrity-protected`
  is not sent.
- **Dialer routing (`last_dial`).** The state provider records whether
  Telephony asked Joan to place a call (`shouldProcessCall` → IMS or
  CSFB, `createCallSession`, `call session start`, or `start failed`).
  An empty `last_dial` after a failed Dialer attempt means the framework
  never handed the call to this ImsService. Counts and flags only; the
  callee is never stored.

USIM-only identity (TS 23.003) and USIM AKA are still in these alphas.
NULL-encryption ESP (`ealg=null`) is still unverified on the development
handset (that core offers `aes-cbc`).

## Obtaining logs

Do this after a failed or interesting registration, **before** rebooting
or clearing logcat. The state provider is the safe paste; the rotating
trace is the detail. Neither should contain IMPI, IMSI, AUTN, RES, CK,
IK, nonce, or P-CSCF addresses. If a dump does, redact those before
opening an issue.

**1. Confirm the build, then paste the state rows** (safe for a public
issue; ordinary ADB shell is enough — no root):

```
adb shell content query --uri content://org.joan.ims.state
```

Useful rows:

- `build` — must be `0.4.0-alpha20 (28)` for this zip
- `registered`, `last_state`, `aka_stage`, `last_register`, `last_dial`
- `ims_diag_listener`, `ims_diag_data`, `ims_diag_network`, `ims_diag_ages`

`last_register` is counts, status codes and algorithm names: P-CSCFs
advertised/tried, reg1 result, AKA algorithm, cipher/integrity, RES/CK/IK
*lengths*, whether IPsec SAs applied, `tpt=udp|tcp`, `tcp_fail=…`,
retransmit counts, reg2 status. `ims_diag_data` is telephony’s view of
the IMS data call (configured vs negotiated protocol, cause, address
families). Empty P-CSCF / `PDN advertised none` is a discovery clue, **not**
proof the SIM lacks VoLTE provisioning.

After this zip, a Viettel tester can also paste (safe; no subscriber
identity):

```
adb shell content query --uri content://telephony/carriers --where "numeric='45204'"
```

Look for `type` containing `ims` and `xcap`. File coverage is not proof
the selected runtime APN is IMS.

**2. Pull the rotating trace** (survives logcat rotation; needs root
because `adbd` drops root across reboot):

```
adb root
adb shell cat /data/user_de/0/org.joan.ims/files/joan-trace.log > joan-trace.log
```

If that path is `Permission denied`, run `adb root` again and retry —
that is lost adbd root, not an empty log. The file is 256 KB rotating.
Look for `IMS data_call`, `IMS network`, `AKA/REG stage`, `reg1`,
`reg2`, `tcp_fail`, and `pcscf` *counts* (not addresses).

**3. Optional, only if IMS never starts** (redact numbers / IMSI / cell
before sharing):

```
adb logcat -d -s JoanIms:V Telephony:V Telecom:V > joan-logcat.txt
```

Do not paste full REGISTER / 401 bodies, AKA payloads, or `dumpsys`
output that includes the subscriber identity.

Results that are especially useful:

- **`ealg=null` and `reg2=200 OK`** — NULL-encryption ESP works
- **`tpt=tcp` then `reg2=200 OK`** — protected TCP REGISTER is what that
  core wanted
- **`tpt=tcp tcp_fail=timeout` with `FAIL: reg2 timeout`** — TCP
  connected; the core stayed silent
- **`tpt=tcp tcp_fail=connect tpt=udp reg2retx=4`** — TCP never
  established; UDP retried and also timed out
- **IMS callback + empty app P-CSCF list** — class 1b discovery; include
  `ims_diag_data` / `ims_diag_network` (configured vs negotiated protocol)
- **`reg1_send_ok` / `reg1_rx` / `reg1_result`** — distinguishes bind/send
  failure from a matching-final timeout

## Emergency calling — read this

**Emergency calls do not go through this app, by design.** 911/112 on this
handset are placed by the modem over the circuit-switched domain, exactly
as they are on a stock LineageOS install with none of this flashed. This
app declines them: it does not advertise emergency MMTEL, and
`shouldProcessCall()` pushes any number the platform reports as an
emergency number to CS fallback. A number it cannot classify also goes to
CS.

Earlier releases were worse than that. Up to and including v0.2.1 the app
declared `EMERGENCY_MMTEL_FEATURE` and returned `PROCESS_CALL_IMS` for
*every* number, which invited telephony to hand an emergency dial to a UA
with no emergency registration, no `urn:service:sos` request-URI, no PSAP
callback handling and no location conveyance. That is fixed.

**This has not been tested against a PSAP, and it should not be.** Do not
dial emergency services to try it. If you want assurance, watch the domain
telephony selects with `logcat -s Telecom` on a carrier test number, or
test with a lab SIM.

**Residual risk worth understanding:** in LTE-only coverage with no CS
available, emergency calling depends on the modem's own emergency attach.
That path is the modem's and is unchanged by this app — the same with it
installed or not — but "911 is fine because CS is there" only holds where
CS is there. If emergency calling on this handset matters to you, satisfy
yourself about it on your own network before relying on this phone.

**An accidental emergency dial on 2026-09-16 did not connect**, and what
it looked like is worth recording. Telecom placed it over CS
(`callTechnologies: [GSM]`), it never left `DIALING`, `startTime` stayed
0, and it hit `STATE_TIMEOUT` after 60 seconds. At the same moment the CS
domain was `NOT_REG_SEARCHING` while the LTE PS domain advertised
`emergencyEnabled=true availableServices=[EMERGENCY]` — the network was
offering emergency over IMS, which is the one path this app declines.

Two honest caveats. That attempt was 23 minutes after a reboot with the
radio still moving between `IN_SERVICE` and `OUT_OF_SERVICE`, so a single
observation cannot separate "no IMS emergency path" from "radio was still
recovering". And this is not new: it has been true since the app was
first installed. But on a carrier that has retired 2G/3G, CS fallback has
nowhere to land, and that is the case to assume until you have tested
otherwise on your own network. **Do not carry this handset as your only
phone.**

## What changed in 0.3.0

**The zip installs now.** Every earlier release — v0.1.0, v0.2.0, v0.2.1 —
wrote into the recovery ramdisk and reported success, so nothing landed.
Recovery does not mount `/system` or `/product` for a sideload here; the
installer now mounts the real logical partitions, refuses to write if the
target resolves to the ramdisk, write-tests each one and byte-compares
every file it copies. It also ships a static RRO setting
`config_ims_mmtel_package`, without which `ImsResolver` never binds the
service even when the files are present.

Verified end to end on a US998: sideload, reboot, both APKs on the real
partitions, overlay enabled, MmTel bound, IMS registered — no manual
commands.

## What changed in 0.2.0

A review pass over the 0.1.0 release, with every claim below verified on
a handset rather than by reading the source.

**Calls no longer wedge.** An inbound CANCEL -- a caller ringing off
before you answer -- was dropped unhandled, leaving the held INVITE set
forever so *every* later incoming call was answered 486 Busy. The phone
silently stopped receiving calls until the process restarted.

**Registration is refreshed.** The driver never re-registered: it saw
itself registered, slept thirty minutes and came back to the same branch,
forever. This core grants 3600s against the 600000s the REGISTER asks
for, so the binding expired an hour after every registration while the
app still reported itself registered. The granted lifetime is now read
from the 200 OK and refreshed at 80% of it.

**Uplink level.** There is no AGC anywhere on this path -- the platform
declares none and LG's own conditioning lives in the ADSP voice topology
that the AP audio path never reaches -- so the transmitted level tracked
how loudly you spoke. Measured speech ranged over 21 dB between calls
while peaks clipped. A software AGC and limiter now target -20 dBFS, and
defer to `AutomaticGainControl` on a ROM that provides one.

**Capabilities match the implementation.** The app advertised emergency
MMTEL, `+g.3gpp.smsip` and `Allow: MESSAGE, UPDATE, REFER, NOTIFY, INFO`,
and offered AMR-WB, AMR and telephone-event in SDP. None of it was
implemented; all of it was dropped on arrival. Each of those is a way to
talk the network out of a path that works -- emergency dials belong on
CS, and SMS rides CS/SGs. It now advertises what it implements, answers
OPTIONS, and declines a codec it cannot speak instead of streaming noise.

**Other:** P-CSCF failover across every address the PDN advertises (this
one advertises three, and only the first was ever tried); RTCP sent to
the negotiated port; RTP media threads at audio priority; the trace log
rotates, no longer holds AKA key material, and no longer blocks the
playback thread; the dead native-daemon control plane is gone, along with
a reconnect loop that ran every three seconds forever.

Emergency calling and SMS/MMS over IMS remain unimplemented. The
difference is that the app no longer claims otherwise -- see the
Emergency calling section above.

Proven first on postmarketOS on this handset, then ported to a
stock-shaped Android ROM.

## Flash

Use LineageOS 22 recovery — see the warning at the top of this file. A
recovery that cannot map dynamic partitions will fail with `cannot mount
system`, and that is not something the zip can work around.

1. Install LineageOS 22 (and GApps if you want them).
2. Sideload `joan-volte-recovery.zip` (skip signature verification if
   recovery asks).
3. Reboot to system.

The zip installs:

- `/system/priv-app/JoanIms/JoanIms.apk`
- `/system/etc/permissions/org.joan.ims.xml`
- `/system/etc/permissions/android.hardware.telephony.ims.xml`

To undo, sideload `joan-volte-uninstall.zip` and reboot.

**Re-flash after a ROM update.** A LineageOS update replaces the
partitions this installs into, so the app and the IMS overlay go with it.
Sideload the zip again after each update.

**If a future ROM ships this stack itself, uninstall first.** Run
`joan-volte-uninstall.zip` *before* upgrading to a build that includes it
in-tree. Otherwise the sideloaded copy and the in-tree one both claim
`config_ims_mmtel_package` and the priv-app path, and which one wins is
not something you want decided by scan order.

Do not flash `abl` / `xbl` / `tz` / `hyp` / `keymaster` / `laf` from
stock.

## Build the zips

Needs a JDK, Android SDK (`build-tools` + `platforms/android-36`), and
`python3` (zip assembly only; nothing Python runs on the phone).

```sh
./tests/run-host-tests.sh
./scripts/pack-zip.sh              # -> out/joan-volte-recovery.zip
./scripts/pack-cleanup-zip.sh      # -> out/joan-volte-uninstall.zip
```

App sources: `ims-service/` (see `app/README.md`).
LineageOS 22 inherit: `upstream/` (`joan-ims.mk` + `Android.bp`).

## How it works

**What the zip contains and what each piece does:**
[`docs/zip-contents-and-how-it-works.md`](docs/zip-contents-and-how-it-works.md)
— every file in the flashable zip, the four conditions that must all hold
before VoLTE is admitted, what the installer does and why, and what uninstall
reverses.

`org.joan.ims` is an Android `ImsService` / `MmTelFeature`:

- Identity and AKA from the ISIM, or from the USIM (TS 23.003) when
  the SIM has no ISIM application
- 3GPP sec-agree (`Security-Client` offers hmac-sha-1-96 / hmac-md5-96
  × aes-cbc / null; the P-CSCF picks)
- Transport-mode ESP via `IpSecTransform` on the IMS PDN sockets
- REGISTER, INVITE/ACK/BYE, PCMU RTP, RTCP SR+SDES
- `setCallAudioHandler(ANDROID)` so Telecom uses the voice-communication
  stream; Dialer owns routing

Native C in `native/` is host unit tests and historical bring-up code.
It is **not** installed.

## Carrier support

No compiled-in realm or cipher. Realm comes from the SIM (ISIM domain /
IMPI suffix); P-CSCF from IMS PCO; ESP from `Security-Server`. It has
so far been exercised on one live IMS core.

## Not in this zip

- **Emergency calling.** Not carried by this app and not advertised;
  emergency dials go to CS fallback. Not tested against a PSAP. See the
  "Emergency calling" section above before relying on this handset.
- **SMS / MMS over IMS.** Not implemented and no longer advertised, so
  the core keeps delivering SMS over CS/SGs, which works and owes nothing
  to this app. MMS rides the data APN and is likewise unaffected.
- **Conference merge.** Implemented as a network-hosted focus INVITE +
  REFER / Replaces flow and offline-tested. Not live-carrier qualified.
  Do not treat Dialer merge as proven on your network until you try it.
- **EVS.** Carriers offer it first (observed: `EVS/115, EVS/109, AMR-WB/104,
  ...`) and this stack skips it, because LineageOS 22 ships no EVS
  encoder. The codec profile is probed from `MediaCodecList` at startup,
  so if a future ROM build adds one it is offered without a code change.
- **Video (VT), RTT, and supplementary services over Ut/XCAP.** Not
  implemented. Call waiting and the conference flow are SIP-side only.
- VoWiFi (see `docs/vowifi-feasibility-2026-08-29.md`)

Implemented since this list was written, and no longer missing:

- **DTMF** now sends RFC 4733 `telephone-event`, negotiated at the chosen
  codec's clock rate, with the audio suppressed for the tone. Not yet
  exercised against a live IVR.
- **Bandwidth-efficient AMR** is implemented and is what a real carrier
  call actually negotiated here (`framing=bandwidth-efficient` on an
  inbound call, `octet-aligned` on an outbound one, both AMR-WB at
  12650 bps in the same session).

## License

Apache-2.0. See `LICENSE`.
