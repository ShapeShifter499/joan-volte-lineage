# joan-volte-lineage

VoLTE and Wi-Fi calling for the LG V30 (`joan`) on **LineageOS 22.2**.

This branch (`claude/aosp-ims-a15-backport`) does it with **AOSP's own
IMS stack**: ImsStack and ImsMedia from Android 17, with IWLAN and QNS
for Wi-Fi calling, backported to Android 15. It replaces joan's own IMS
app (see [joan's own stack](#joans-own-stack) below).

It comes three ways:

| | For | What |
|---|---|---|
| **ROM** | Anyone flashing a ROM anyway | The official LineageOS 22.2 nightly with the stack built in |
| **Flashable zips** | A phone already on LineageOS 22.2, or a ROM based on it | `-fresh`, `-migrate-from-joan` and `-uninstall` |
| **Source-build kit** | ROM builders, and LineageOS itself | [`upstream/AOSP-IMS.md`](upstream/AOSP-IMS.md) |

**Downloads:** the
[`aosp-ims-17.0.0_r1-a15-alpha1`](https://github.com/ShapeShifter499/joan-volte-lineage/releases/tag/aosp-ims-17.0.0_r1-a15-alpha1)
prerelease. Its notes say which file to flash and what to do after.

**Something wrong?** [How to log a problem](aosp-ims/HOW-TO-LOG.md).

## Status: alpha

Tested on one phone and one carrier so far, a US998 on T-Mobile US:

| | |
|---|---|
| VoLTE registration (with IPsec) | works |
| Outgoing VoLTE calls, audio both ways | works |
| Incoming VoLTE calls | ring and answer |
| Wi-Fi calling registration, and a Wi-Fi call with audio both ways | works |
| SMS over IMS, video calling, RTT, call forwarding/waiting settings, conference calls | built in, not tested |
| Emergency calls over IMS | built in, not tested: see [Emergency calls](#emergency-calls) |

Everything else is untested: other carriers, other V30 models. Carriers
whose IMS runs over IPv4 (Digi Mobil Romania among them) couldn't get
past the first REGISTER before ImsStack 0014, which alpha1 includes.
Known problems, among them a delay of 20 seconds or more after switching
Wi-Fi calling off, are listed in
[`aosp-ims/README.md`](aosp-ims/README.md#known-problems).

Keep a way back: the `-uninstall` zip, or the official nightly.

## Phones

Every V30 that LineageOS 22.2 supports, all on the one `joan` build:
H930, H930DS (dual SIM), US998, H932 (T-Mobile), H931, H933, LS998,
V300K, V300L, V300S and VS996. Some carriers only allow VoLTE on phone
models they have certified; that is decided by the network, not the phone.

## Flashing

> **Use LineageOS recovery, not TWRP.** `joan` uses dynamic partitions:
> `/system` and `/product` live inside `super` and have to be mapped
> before anything can write to them. LineageOS recovery does that; TWRP
> on this device generally does not, and the zip fails with
> `cannot mount system`.

In LineageOS recovery: *Apply update* > *Apply from ADB*, then
`adb sideload <file>.zip`. Recovery warns that the signature can't be
verified (only LineageOS can sign with its key): answer *Yes*.

- **ROM** (`lineage-22.2-…-UNOFFICIAL-AOSPIMS-…-joan.zip`): flash it like
  any LineageOS update. It reports itself as `UNOFFICIAL`, so the updater
  won't offer official nightlies over it.
- **`-fresh`**: a phone that never had joan's IMS zip.
- **`-migrate-from-joan`**: a phone running joan's IMS zip (any version).
  It removes joan's stack first.
- **`-uninstall`**: removes everything the zips or the ROM added.

The zips survive LineageOS updates: an `addon.d` script puts the stack
back after each nightly.

**After flashing:** calls need the microphone permission (granted at
first boot with the ROM; otherwise open *Calling permissions* in the app
drawer), and Wi-Fi calling needs one adb command for IWLAN's tunnel. The
release notes and [`aosp-ims/README.md`](aosp-ims/README.md#permissions-zips-and-repacked-rom)
have the steps and the scripts.

## When something fails

Follow [`aosp-ims/HOW-TO-LOG.md`](aosp-ims/HOW-TO-LOG.md): turn up the log
buffer, make the failure happen again, run a few adb commands (no root
needed), and send the files **privately**: they contain your phone
number, IMSI and IMEI.

## Emergency calls

ImsStack can place emergency calls over IMS where the network asks for
that, as a Pixel does. This has **not** been tested, and must not be
tested by dialling emergency services. The listener ImsStack uses to see
an outgoing emergency call needs a permission only the ROM's key grants,
so the zips and the ROM track emergency calls from the call state instead
(ImsStack 0004). On a carrier that has retired 2G/3G there is no fallback
if IMS emergency calling fails. **Don't carry this phone as your only way
to reach emergency services.**

## How it works

Four AOSP components, all upstream code with a small set of patches:

| Part | Package | Does |
|---|---|---|
| ImsStack | `com.android.imsstack` | IMS registration, SIP, IPsec, calls, SMS, Ut/XCAP |
| ImsMedia | `com.android.telephony.imsmedia` | RTP, jitter buffer, codecs, call audio and video |
| IWLAN | `com.google.android.iwlan` | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS | `com.android.telephony.qns` | Moves IMS between LTE and Wi-Fi |

Qualcomm's IMS can't be used on the V30: its modem was built without
Qualcomm's IMS core ([`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`](docs/v30-modem-and-qualcomm-ims-2026-09-27.md)).
AOSP's stack runs on the application processor and needs the modem only
for the LTE bearer and SIM authentication.

Each carrier's IMS settings come from the carrier data LineageOS ships
for Pixels (1361 entries for 576 carriers), Android 17's CarrierConfig
changes, and LG's own settings for networks those lack. They are applied
on top of the ROM's own carrier config, with the IMS and XCAP APNs a
SIM's list lacks.

[`aosp-ims/README.md`](aosp-ims/README.md) has the rest: every patch and
why, per-carrier config, how the zip installs without the platform key,
how it builds without an Android tree, how the ROM is repacked, and how
releases are made.

## Repository

| Path | What |
|---|---|
| `aosp-ims/` | The AOSP IMS backport: patches, carrier data, build scripts, tests, zip sources |
| `upstream/` | The source-build kit for a LineageOS tree ([`upstream/AOSP-IMS.md`](upstream/AOSP-IMS.md)) |
| `docs/` | Research notes, audits and handoffs |
| `.github/workflows/aosp-ims.yml` | The former CI build, disabled: releases are built locally (see `aosp-ims/README.md`, Releasing) |
| `ims-service/`, `native/`, `scripts/`, … | joan's own stack and the installer and carrier maps the backport reuses |

## joan's own stack

Before this branch, the project was a from-scratch IMS client,
`org.joan.ims`: SIP, AKA and IPsec in a privileged `ImsService`. Its
releases are the `v0.4.0-alpha*` tags (latest published:
[`v0.4.0-alpha67`](https://github.com/ShapeShifter499/joan-volte-lineage/releases/tag/v0.4.0-alpha67)),
and its documentation is the README on the
[`main`](https://github.com/ShapeShifter499/joan-volte-lineage/tree/main)
branch. A phone running it moves to this stack with the
`-migrate-from-joan` zip.

## Support this project

This stack gets built and tested the slow way: real LG V30 hardware,
real SIMs on live carrier networks, and a lot of reverse-engineering and
log analysis to get each network's quirks right. Every build costs AI
compute, testing time and hardware wear, and none of it is sponsored.

If this project put VoLTE on a phone that officially never had it for
you, you can help keep it moving:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/shapeshifter499)

Bug reports, logs and tester feedback are just as welcome as donations:
they are how each carrier gets working.

## License

Apache-2.0. See `LICENSE`. The AOSP components keep their own
Apache-2.0 notices.
