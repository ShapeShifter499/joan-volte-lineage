# joan-volte-lineage

**AOSP's IMS stack, backported to LineageOS 22.2 for the LG V30.**
Alpha: built and checked end to end, not yet proven on a phone.

The V30's modem was built without Qualcomm's IMS core and the modem
images are LG-signed, so IMS cannot run in the modem the way most
phones do it. This project instead backports **AOSP's own IMS stack
from Android 17** — ImsStack (registration, SIP, IPsec, calls, SMS over
IP) and ImsMedia (RTP, codecs, call audio) — onto LineageOS 22.2
(Android 15 QPR2), with AOSP's IWLAN (the ePDG tunnel) and QNS (the
LTE ⇄ Wi-Fi choice) for Wi-Fi calling. All of it runs on the
application processor; the modem only needs the LTE bearer and SIM
authentication, which the V30 has.

An earlier approach in this repository — a from-scratch IMS
implementation (`org.joan.ims`, the `joan-ims` era, versions
`0.4.0-alpha*`) — is retired on this branch. Its history and its branch
(`conference-alpha11` and friends) keep everything; this branch now
contains only the backport and what the backport reuses: the installer
scripts, the carrier maps, and the APN merge.

## What the stack carries

| Feature | State |
|---|---|
| **VoLTE** | Every carrier; the Settings toggle is visible and editable everywhere. |
| **SMS over IMS** | Part of the MMTEL feature ImsStack registers; rides the same registration as voice, including over Wi-Fi calling. |
| **Wi-Fi calling** | Every carrier gets the toggle; per-carrier IMS data comes from Google's Pixel carrier settings (below). US carriers need an E911 address on the account. |
| **Supplementary services** | Call waiting, conferencing and the Ut/XCAP keys the carrier data carries, through ImsStack's `ImsUtImpl`. |
| **Emergency calling** | IMS emergency registration is in the stack, with one backport seam: Android 16's domain-selection emergency-mode callback does not exist on Android 15, so that state is not reported. Test it on the bench before relying on it. |
| **Video calling (ViLTE)** | Not enabled. Nothing fundamental blocks it — the media engine carries video and nothing needs the modem's missing IMS core — but the carrier data import deliberately drops the VT keys, and the flashable-zip path cannot hold the platform-signature surface and camera permissions the in-call video path wants (a source build can). Follow-up work, not alpha scope. |
| **RCS** | Out by design. RCS is a client (Google Messages/Jibe; AOSP ships none) plus a separate `RcsFeature` an ImsService may implement. This stack declares no RCS feature, which already is the fail-closed state: nothing binds, nothing appears, MMTEL is unaffected. Flashed GApps + Google Messages is the only real route and does not need this stack. |
| **RTT** | Not enabled, same category as video. |

## Carrier data

LineageOS ships no IMS carrier config for most carriers, and AOSP's
default asset covers a subset. This stack ships three layers, merged in
this order:

1. the ROM's own CAF-derived `vendor.xml` (MMS and voicemail config,
   untouched);
2. the IMS keys converted from **Google's Pixel CarrierSettings** — the
   same conversion LineageOS runs for Pixels (`carriersettings-extractor`),
   pre-run and filtered to the IMS namespaces (`aosp-ims/carrier/`):
   1353 carrier entries, 573 named carriers, VoLTE for 1201, Wi-Fi
   calling for 936, static ePDG for 587;
3. our own rules last: VoLTE and Wi-Fi calling for every SIM, both
   toggles usable, IMS always user-turnoff-able, plus the ePDG
   addresses for AT&T and Verizon for the case the overlay did not
   match the SIM (T-Mobile's ePDG is the 3GPP default name and needs
   nothing).

The flashable zip and the repacked ROM apply this through a runtime
resource overlay (`ImsStackCarrierConfigOverlay`) that outranks the
nightly's own CarrierConfig overlay; a source build splices the same
blocks into the device tree instead. The run-time gate
(`CarrierImsGate`) only fills what the config is missing.
`tests/check-carrier-config.py` checks gate and config agree for all
2866 SIM identities Android's carrier database knows.

## The three ways to get it

| | For | What |
|---|---|---|
| **ROM** | Anyone flashing a ROM anyway | `lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip`: the official 2026-09-20 nightly with the stack built in |
| **Flashable zips** | A phone already on LineageOS 22.2 or a ROM based on it | `aosp-ims-17.0.0_r1-a15-alpha1-{fresh,migrate-from-joan,uninstall}.zip` |
| **Source build** | ROM builders, and LineageOS itself | [`upstream/AOSP-IMS.md`](upstream/AOSP-IMS.md): a local manifest, the backport patches, and the `device/lge/joan-common` change |

Downloads are on the
[`aosp-ims-17.0.0_r1-a15-alpha1`](https://github.com/ShapeShifter499/joan-volte-lineage/releases/tag/aosp-ims-17.0.0_r1-a15-alpha1)
prerelease (built by CI from this tree). Details — which file to flash,
the one adb step the zips and the repacked ROM need, known limits, and
what logs to send back — are in
[`aosp-ims/README.md`](aosp-ims/README.md) and
[`aosp-ims/RELEASE-NOTES.md`](aosp-ims/RELEASE-NOTES.md).

The zips and the ROM are deliberately versioned apart from the old
joan stack (`aosp-ims-17.0.0_r1-a15-alpha1`, ROM suffix
`UNOFFICIAL-AOSPIMS-alpha1`) so the two can never be confused; the alpha
count starts at 1. The app version is `17.0.0_r1-a15-alpha1`.

## Devices

The ROM keeps LineageOS's own install assert
(`TARGET_OTA_ASSERT_DEVICE := v30,joan,h930,h932` in the joan device
tree), so it installs wherever the official nightly installs. The
official wiki supports: **H930, H930DS, US998** (unlocked),
**H932** (T-Mobile), and **H931, H933, LS998, V300L, V300K, V300S,
VS996** ("Other") — one unified `lineage_joan` build; the device tree
picks the variant at runtime from `ro.boot.vendor.lge.model.name`. The
flashable zips check no model. Japanese variants (L-01K, LGV35) are
handled by the device tree but not on the official wiki.

## Building

No Android tree is needed to build the zip and the ROM; the source
build kit is separate (see [`upstream/AOSP-IMS.md`](upstream/AOSP-IMS.md)).

```sh
aosp-ims/tools/build-all.sh               # sources, Java, native, APKs, zips
sudo aosp-ims/tests/run-e2e-install.sh    # installer end-to-end tests
sudo aosp-ims/tools/repack-rom.sh         # the ROM (needs the pinned OTA)
aosp-ims/tests/check-rom.sh <rom.zip> aosp-ims/work/out/apk
```

Inputs are pinned in `aosp-ims/upstream.lock`; `aosp-ims/work/` is
scratch (git-ignored, ~4 GB). CI (`.github/workflows/aosp-ims.yml`)
runs the same steps from scratch on every push and publishes when
`aosp-ims/RELEASE` changes.

## Layout

    aosp-ims/            the backport: patches, packaging, zips, ROM, checks
      carrier/           the converted Pixel IMS carrier data + the CAF base vendor.xml
      patches/           the Android 17 → Android 15 backport patches
      tests/             carrier-config, installer e2e, ROM checks
      tools/             build, pack, import, check scripts
      upstream.lock      pinned sources, branches and the base ROM
    upstream/            the source-build kit for a real LineageOS tree
    scripts/             the installer (v8) the zips are generated from
    apn/, permissions/, META-INF/   installer inputs (IMS feature xml, APN rows)
    docs/                the handoffs and the modem/Qualcomm analysis

## Licence

Apache-2.0. See `LICENSE`.
