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

- SMS / MMS over IMS
- Emergency calling
- VoWiFi (see `docs/vowifi-feasibility-2026-08-29.md`)

## License

Apache-2.0. See `LICENSE`.
