# joan-volte-lineage

VoLTE for the LG V30 (`joan`: US998 / H930 / H932) on **LineageOS 22**.
Flashable recovery zip. No Magisk, no stock `Ims6` blobs, no CAF
`OpenIMSd`.

SIP, AKA, and IPsec run in a privileged `ImsService` (`org.joan.ims`)
using public `IpSecManager` APIs. There is no native daemon and no
loopback control socket.

> **Working on LineageOS 22.2:** IMS REGISTER 200, Dialer outbound and
> inbound PCMU calls, two-way audio, hangup from either side. Speaker
> and earpiece follow Dialer. Caller ID is the asserted number; Dialer
> can still overlay a matching contact.

## Help wanted: USIM-only cards

**If your SIM has no ISIM application, this needs testers and logs.**

Support for deriving the IMS identity from the IMSI (3GPP TS 23.003
§13.3) and authenticating against the USIM instead of an ISIM landed in
the `v0.4.0-alpha*` prereleases. Both halves are confirmed working on one
handset: the identity is derived and AKA succeeds. On China Mobile the
protected REGISTER was then sent over UDP and timed out.
`v0.4.0-alpha7` sends that second REGISTER over TCP to P-CSCF `port-s`
on MCC 460 only, keeps the TCP client for later SIP, and advances to
the next advertised P-CSCF if that REG2 is silent (T-Mobile stays on
the proven UDP path).

The remaining open question is IPsec with **NULL encryption**. Android
exposes no NULL cipher constant, so an authentication-only
`IpSecTransform` is *assumed* to produce ESP-NULL, and that has never
been verified anywhere. It cannot be reproduced on the development
handset, whose network offers only `aes-cbc`. A report from a network
that selects `null` and *works* would settle it as fast as one that
fails.

If you are on a USIM-only card, please try the latest `v0.4.0-alpha`
prerelease and send:

```
adb shell content query --uri content://org.joan.ims.state
```

The `last_register` row carries the whole diagnosis — how many P-CSCFs
were advertised and tried, the reg1 status, the AKA algorithm, the
selected cipher and integrity algorithm, RES/CK/IK lengths, whether the
IPsec SAs applied, the retransmission count and the reg2 status. It
contains counts, status codes and algorithm names only: no IMPI, no IMSI,
no P-CSCF address, no nonce, no keys. It is safe to paste in an issue.

Two results that are especially useful:

- **`ealg=null` and `reg2=200 OK`** — NULL-encryption ESP works, and the
  remaining failure is something else
- **`tpt=tcp` then `reg2=200 OK`** — protected TCP REGISTER is what that
  core wanted
- **`tpt=tcp tcp_fail=timeout` with `FAIL: reg2 timeout`** — TCP connected
  and the core still stayed silent; ESP-NULL is still a live suspect
- **`tpt=tcp tcp_fail=connect tpt=udp reg2retx=4`** — TCP never
  established, UDP retried and also timed out

If registration never starts and the state says the PDN advertised no
P-CSCF, that usually means the SIM is not provisioned for VoLTE rather
than a fault here.

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

Use a recovery that can write **dynamic system** partitions the way
LineageOS 22 recovery does (Lineage recovery qualifies). TWRP on this
device generally cannot.

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

`org.joan.ims` is an Android `ImsService` / `MmTelFeature`:

- Identity and AKA from the ISIM
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
- **Codecs other than PCMU.** G.711 u-law only. AMR-WB is the largest
  available audio quality win and is not done; a core that requires AMR
  will now reject the INVITE rather than be sent u-law it did not ask for.
- **DTMF.** No RFC 4733; keypresses in an IVR do nothing.
- VoWiFi (see `docs/vowifi-feasibility-2026-08-29.md`)

## License

Apache-2.0. See `LICENSE`.
