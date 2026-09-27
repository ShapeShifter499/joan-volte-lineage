**Unofficial alpha. Nothing in it has run on a phone yet.** It is built
entirely from source by this repository's `aosp-ims` workflow and passes
its installer, carrier-config and ROM checks. Keep a way back: the
`-uninstall` zip, or the official LineageOS nightly.

## What this is

VoLTE, SMS over IMS and Wi-Fi calling for the LG V30 (joan) on
LineageOS 22.2, using AOSP's own IMS stack from Android 17, backported
to Android 15. It replaces joan's IMS stack.

| Part | What it does |
|---|---|
| ImsStack + ImsMedia (`com.android.imsstack`) | IMS registration, calls, SMS over IMS; call audio on Android |
| IWLAN (`com.google.android.iwlan`) | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS (`com.android.telephony.qns`) | Moves IMS between LTE and Wi-Fi |

- **VoLTE** is offered for every carrier; the Settings toggle is visible
  and editable everywhere.
- **Wi-Fi calling** is offered for every carrier. The per-carrier IMS
  data (SIP timers, codecs, ePDG addresses, LTE/Wi-Fi handover policy)
  comes from Google's Pixel carrier settings, the same data LineageOS
  converts for Pixel devices, plus our own rules. Among the carriers
  with dedicated data: AT&T, T-Mobile, Verizon, FirstNet, Jio, Truphone,
  Bell, Rogers, Deutsche Telekom, Orange, Three, O2.
- **SMS over IMS** rides the same registration as voice, including over
  Wi-Fi calling.

**Not in this alpha:** video calling (the capability exists in the
stack; the media path was never wired or tested on joan — a follow-up),
RCS (a client thing: Google Messages/Jibe; AOSP ships no RCS client and
this stack deliberately declares no RCS feature), RTT. Emergency
calling: IMS emergency registration is in the stack, with one backport
seam — the domain-selection emergency-mode callback is an Android 16
API and is not reported on Android 15.

## Which file

Flash with **LineageOS recovery**. It maps joan's dynamic partitions;
TWRP on this device generally does not.

- **`lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip`**: the whole
  ROM. It is the official 2026-09-20 nightly with the IMS stack built in.
  It installs on every model the official nightly installs on (H930,
  H930DS, US998, H932, H931, H933, LS998, V300L, V300K, V300S, VS996).
  - Recovery warns that the signature can't be verified (only LineageOS
    can sign with its key); choose to install anyway.
  - It reports itself as `UNOFFICIAL`, so the updater won't replace it
    with an official nightly (which would drop the IMS stack).
- **`aosp-ims-17.0.0_r1-a15-alpha1-fresh.zip`**: for a phone on LineageOS
  22.2, or a ROM based on it, that never had joan's IMS zip. It refuses a
  phone that has joan, and changes nothing.
- **`aosp-ims-17.0.0_r1-a15-alpha1-migrate-from-joan.zip`**: for a phone
  running joan's IMS (alpha67 or earlier). It removes joan's stack, then
  installs this one.
- **`aosp-ims-17.0.0_r1-a15-alpha1-uninstall.zip`**: puts the ROM back as
  it was. It also works on the ROM above.
- **`SHA256SUMS`**: check a download with `sha256sum -c SHA256SUMS --ignore-missing`.

## After flashing: one adb step

Until a LineageOS build signs these apps with its platform key, some
permissions can only be granted over adb. Once, after the first boot,
with USB debugging on:

```
sh grant-permissions.sh
```

The script is attached here, inside the zips, and at
`aosp-ims/zip/grant-permissions.sh` in the repository. Without `sh`
(Windows), run its commands directly:

```
adb shell pm grant com.android.imsstack android.permission.RECORD_AUDIO
adb shell pm grant com.android.imsstack android.permission.READ_PHONE_STATE
adb shell pm grant com.android.imsstack android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.android.imsstack android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.android.imsstack android.permission.ACCESS_BACKGROUND_LOCATION
adb shell appops set com.google.android.iwlan MANAGE_IPSEC_TUNNELS allow
adb shell pm grant com.google.android.iwlan android.permission.READ_PHONE_STATE
adb shell pm grant com.google.android.iwlan android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.google.android.iwlan android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.android.telephony.qns android.permission.READ_PHONE_STATE
```

- Without `RECORD_AUDIO`, the other side of a call hears silence.
- Without the IWLAN app-op, Wi-Fi calling can't build its tunnel.
- Location is used for emergency calls and the network location header.

Then turn on **VoLTE** (Settings > Network & internet > SIMs), and
**Wi-Fi calling** where you want it — every carrier gets the toggle.

## Known limits

- **Signature permissions.** Two signature-only permissions ImsStack asks
  for can't be granted to an app not signed with the ROM's key:
  `ACCESS_SURFACE_FLINGER` and `INTERACT_ACROSS_USERS_FULL`. Their
  features (video surfaces, work profiles) may misbehave.
- **Emergency mode.** Android 16's domain-selection emergency-mode
  callback doesn't exist on Android 15, so ImsStack doesn't see that
  state.
- **Carrier activation portals.** Wi-Fi calling activation portals open in
  the browser, not in an in-app tab.
- **US E911 address.** US carriers need an E911 address on the account
  for Wi-Fi calling.
- **Video calling** is not enabled (see above).

## Building it into LineageOS

A source build needs none of the above workarounds: see
[`upstream/AOSP-IMS.md`](https://github.com/ShapeShifter499/joan-volte-lineage/blob/claude/aosp-ims-a15-backport/upstream/AOSP-IMS.md)
for the local manifest, the patches and the `device/lge/joan-common`
change.

## What to send back

If something fails, send:

- `adb logcat -b all -d > log.txt`, taken right after the failure;
- the output of `adb shell dumpsys telephony.registry`;
- the output of `adb shell dumpsys package com.android.imsstack`.

Logs can contain your phone number and SIM identifiers (IMSI); send them
privately rather than posting them publicly.

Source, patches and build scripts: `aosp-ims/` on branch
[`claude/aosp-ims-a15-backport`](https://github.com/ShapeShifter499/joan-volte-lineage/tree/claude/aosp-ims-a15-backport/aosp-ims).
