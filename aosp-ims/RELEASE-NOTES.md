**Unofficial alpha.** Bench results so far (US998 on T-Mobile): the
migrate zip installs, ImsStack registers with IPsec, and calls ring and
are answered (after a fix for the SIM's call control check, see Known
limits); a completed call has not been confirmed yet. It is built entirely from
source by this repository's `aosp-ims` workflow and passes its installer,
carrier-config and ROM checks. Keep a way back: the `-uninstall` zip, or
the official LineageOS nightly.

## What this is

VoLTE and Wi-Fi calling for the LG V30 (joan) on LineageOS 22.2, using
AOSP's own IMS stack from Android 17, backported to Android 15. It
replaces joan's IMS stack.

It is for every V30 model LineageOS supports, all on the one joan build:
H930, H930DS, US998, H932, H931, H933, LS998, V300K, V300L, V300S and
VS996 (tested so far: US998 on T-Mobile).

| Part | What it does |
|---|---|
| ImsStack + ImsMedia (`com.android.imsstack`) | IMS registration, calls and SMS; call audio on Android |
| IWLAN (`com.google.android.iwlan`) | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS (`com.android.telephony.qns`) | Moves IMS between LTE and Wi-Fi |

It does VoLTE, Wi-Fi calling, SMS over IMS, video calling (ViLTE), RTT,
emergency calls over IMS, call forwarding/waiting/barring over Ut/XCAP,
and conference calls, where the carrier offers them.

VoLTE and Wi-Fi calling are offered for every carrier: Wi-Fi calling
works where the carrier's ePDG accepts the SIM, and IMS stays on LTE
elsewhere. Each carrier's IMS settings (SIP, SMS over IMS, Ut, emergency,
video, RTT, ePDG) come from the carrier data LineageOS ships for Pixels,
1361 entries for 576 carriers, applied on top of whatever carrier config
your ROM already has. The same data supplies the IMS, XCAP (Ut) and
emergency APNs your ROM's APN list lacks for your SIM (Verizon's MVNOs,
among others), added on the phone and taken back if the ROM later
brings its own.

## Which file

Flash with **LineageOS recovery**. It maps joan's dynamic partitions;
TWRP on this device generally does not.

- **`lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip`**: the whole
  ROM. It is the official 2026-09-20 nightly with the IMS stack built in.
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

## After flashing: permissions

These apps are not signed with the ROM's platform key (only LineageOS
has it), so Android does not hand them every permission by itself.

**Calls: the microphone.** Flashed together with a ROM install or update,
or as the ROM above, the microphone, camera, location and phone
permissions are granted at first boot. Flashed onto a ROM that has
already booted, **Calling permissions** appears in the app drawer: open
it and allow them. It goes away once the microphone is allowed; no
reboot needed. (Or Settings > Apps > ImsStack > Permissions, with system
apps shown.)

**Wi-Fi calling: one adb step**, whichever file you flashed. IWLAN needs
an app-op for its IPsec tunnel that has no setting on the phone. Once,
after the first boot, with USB debugging on:

```
sh grant-permissions.sh
```

The script is attached here, inside the zips, and at
`aosp-ims/zip/grant-permissions.sh` in the repository. It also grants
everything above. Without `sh` (Windows), run its commands directly:

```
adb shell pm grant com.android.imsstack android.permission.RECORD_AUDIO
adb shell pm grant com.android.imsstack android.permission.CAMERA
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

- Without `RECORD_AUDIO`, calls can't open the microphone.
- Without the IWLAN app-op, Wi-Fi calling can't build its tunnel.
- Location is used for emergency calls and the network location header.

Reboot afterwards, so IWLAN starts with the app-op in place.

Then turn on **VoLTE** (Settings > Network & internet > SIMs), and
**Wi-Fi calling** where it is offered.

## Known limits

- **Signature permissions.** Two signature-only permissions ImsStack asks
  for can't be granted to an app not signed with the ROM's key:
  `ACCESS_SURFACE_FLINGER` and `INTERACT_ACROSS_USERS_FULL`. Their
  features (video surfaces, work profiles) may misbehave.
- **Emergency calls.** The listener ImsStack uses to see outgoing
  emergency calls needs a permission only the ROM's own key can grant, so
  this build detects them from the call state instead (patch 0004). A
  LineageOS build made from source keeps the original. Android 16's
  domain-selection emergency-mode callback doesn't exist on Android 15,
  so ImsStack doesn't see that state either way.
- **Carrier activation portals.** Wi-Fi calling activation portals open in
  the browser, not in an in-app tab.
- **US E911 address.** US carriers need an E911 address on the account
  for Wi-Fi calling.
- **SIM call control.** On a SIM with call control by USIM (T-Mobile's
  have it), the phone asks the SIM about every call before placing it.
  joan's RIL does not pass the SIM's answer back (it reports status
  words 00 00), which failed every call until patch 0005: the call is now
  placed as dialled. The SIM therefore can't bar or rewrite a call on
  this phone; a SIM that does answer is still obeyed.
- **Video calling** is offered where the carrier's config allows it, and
  has not been tested yet.
- **No EVS.** AOSP's media stack has no EVS codec yet, so calls use HD
  voice (AMR-WB) or AMR, never EVS, even where the carrier offers it
  (patch 0007).

## Building it into LineageOS

A source build needs none of the above workarounds: see
[`upstream/AOSP-IMS.md`](https://github.com/ShapeShifter499/joan-volte-lineage/blob/claude/aosp-ims-a15-backport/upstream/AOSP-IMS.md)
for the local manifest, the patches and the `device/lge/joan-common`
change.

## What to send back

If something fails, send:

- `adb logcat -b all -d > log.txt`, taken right after the failure (for a
  failed call, `adb logcat -b all -d | grep -iE "call-control|invokeStartFailed|SIPMSG|OnMediaFailed|- Terminate :|libimsmedia|AAudio|ImsStackPermissions"`
  shows the stack's view of it);
- the output of `adb shell dumpsys telephony.registry`;
- the output of `adb shell dumpsys package com.android.imsstack`.

Logs can contain your phone number and SIM identifiers (IMSI); send them
privately rather than posting them publicly.

Source, patches and build scripts: `aosp-ims/` on branch
[`claude/aosp-ims-a15-backport`](https://github.com/ShapeShifter499/joan-volte-lineage/tree/claude/aosp-ims-a15-backport/aosp-ims).
