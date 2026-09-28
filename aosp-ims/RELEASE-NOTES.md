**Unofficial alpha.** VoLTE and Wi-Fi calling for the LG V30 on
LineageOS 22.2, using AOSP's own IMS stack from Android 17. It has been
tested on one phone and one carrier (a US998 on T-Mobile US) and nowhere
else yet. Keep a way back: the `-uninstall` zip, or the official
LineageOS nightly. **Something wrong? See
[How to log a problem](https://github.com/ShapeShifter499/joan-volte-lineage/blob/claude/aosp-ims-a15-backport/aosp-ims/HOW-TO-LOG.md)**
(also attached below as `HOW-TO-LOG.md`).

## What works so far

Tested on a US998 on T-Mobile US:

| | |
|---|---|
| VoLTE registration (with IPsec) | works |
| Outgoing VoLTE calls, audio both ways | works |
| Incoming VoLTE calls | ring and answer; not re-checked since the last media fixes |
| Wi-Fi calling: registration over the carrier's tunnel | works |
| Outgoing Wi-Fi calls, audio both ways | works (1 min 45 s call, clean hang-up) |
| SMS over IMS, video calling, RTT, call forwarding/waiting settings, conference calls | built in, not tested yet |
| Emergency calls over IMS | built in, **not tested, and must not be tested by dialling**: see Known limits |

New in this build, not yet tested on a phone: carriers whose IMS runs
over IPv4 (Digi Mobil Romania among them) could never register with the
earlier bench builds. Every registration going over TCP failed before
anything was sent. Fixed (ImsStack 0014).

Also new, not yet re-tested on the phone: hanging up an outgoing call
before it rang (or the network refusing it, busy for instance) could leave
the call screen stuck on "Disconnecting". Fixed (ImsStack 0015).

## What this is

It replaces joan's own IMS app with the IMS stack Google wrote for
Android 17, backported to Android 15 (LineageOS 22.2):

| Part | What it does |
|---|---|
| ImsStack + ImsMedia (`com.android.imsstack`) | IMS registration, calls and SMS; call audio |
| IWLAN (`com.google.android.iwlan`) | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS (`com.android.telephony.qns`) | Moves IMS between LTE and Wi-Fi |

For every V30 model LineageOS supports, all on the one joan build: H930,
H930DS, US998, H932, H931, H933, LS998, V300K, V300L, V300S and VS996.

VoLTE and Wi-Fi calling are offered for every carrier. Each carrier's IMS
settings come from the carrier data LineageOS ships for Pixels (1361
entries for 576 carriers), applied on top of your ROM's own carrier
config, plus LG's settings for networks that data lacks. Some carriers
only allow VoLTE on phone models they have certified; that is the
network's decision, not the phone's.

## Which file

Flash with **LineageOS recovery** (*Apply update* > *Apply from ADB*).
It maps joan's dynamic partitions; TWRP on this device generally does
not. Recovery warns that the signature can't be verified (only LineageOS
can sign with its key): choose *Yes* to install anyway.

- **`lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip`**: the whole
  ROM, the official 2026-09-20 nightly with the IMS stack built in. It
  reports itself as `UNOFFICIAL`, so the updater won't offer official
  nightlies over it. It has its own build number, so flashing it over the
  official 2026-09-20 nightly without a wipe counts as a system update
  and the calling permissions are granted at first boot.
- **`aosp-ims-17.0.0_r1-a15-alpha1-fresh.zip`**: for a phone on LineageOS
  22.2, or a ROM based on it, that never had joan's IMS zip. It refuses a
  phone that has joan, and then changes nothing.
- **`aosp-ims-17.0.0_r1-a15-alpha1-migrate-from-joan.zip`**: for a phone
  running joan's IMS (any version). It removes joan's stack, then installs
  this one.
- **`aosp-ims-17.0.0_r1-a15-alpha1-uninstall.zip`**: puts the ROM back as
  it was. It also works on the ROM above.
- **`SHA256SUMS`**: check a download with `sha256sum -c SHA256SUMS --ignore-missing`.

Bench testers: this is the same code as bench 11.

**LineageOS updates** keep the stack: its `addon.d` script puts it back
after each nightly, so there is no need to re-flash.

## After flashing: permissions

These apps are not signed with the ROM's platform key (only LineageOS
has it), so Android does not hand them every permission by itself.

**Calls need the microphone.** Flashed with a ROM install or update, or
as the ROM above, it is granted at first boot. Flashed onto a ROM that has
already booted, **Calling permissions** appears in the app drawer: open
it and allow what it asks. It goes away once the microphone is allowed;
no reboot needed.

**Wi-Fi calling needs one adb step**, whichever file you flashed: IWLAN's
tunnel needs an app-op that has no setting on the phone. Once, with USB
debugging on:

```
sh grant-permissions.sh        # macOS/Linux
grant-permissions.bat          # Windows (adb on your PATH)
```

Both are attached below and are inside the zips at `scripts/`.
`grant-on-device.sh` does the same from `adb shell` on the phone. They run
the commands below, which you can also paste yourself:

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

Reboot afterwards. Then turn on **VoLTE** (Settings > Network & internet
> SIMs), and **Wi-Fi calling** where it is offered.

## Known limits

- **Emergency calls.** The stack can place emergency calls over IMS where
  the network asks for that, but this has not been tested, and must not
  be tested by dialling emergency services. The listener ImsStack uses to
  see an outgoing emergency call needs a permission only the ROM's key
  grants, so this build tracks emergency calls from the call state
  instead (ImsStack 0004). **Don't rely on this phone as your only way to
  reach emergency services.**
- **SIM call control.** On a SIM with call control by USIM (T-Mobile's
  have it), the phone asks the SIM about every call. joan's RIL doesn't
  pass the SIM's answer back, which failed every call until ImsStack
  0005: the call is now placed as dialled, so such a SIM can't bar or
  rewrite a call on this phone. A SIM that does answer is still obeyed.
- **No EVS.** AOSP's media engine has no EVS codec yet, so calls use HD
  voice (AMR-WB) or AMR (ImsStack 0007).
- **Video calling** is offered only where the carrier's config allows it
  (T-Mobile US does; most carriers don't), and hasn't been tested.
- **Wi-Fi calling** in the US needs an E911 address on your account, and
  carrier activation pages open in the browser.
- **Switching Wi-Fi calling off** while IMS is on Wi-Fi leaves you
  without VoLTE for 20 seconds or more: joan's modem refuses to take the
  IMS connection over from Wi-Fi and briefly drops off LTE, so IMS starts
  again on LTE from scratch. A call made in that gap goes over 3G/2G and
  makes the gap longer (85 s on the bench). A Wi-Fi call can't move to
  LTE when you leave Wi-Fi.
- **If the IMS app keeps crashing.** It restarts by itself after a crash
  (a call in progress ends), and low memory doesn't kill it. But after 5
  crashes within a minute, Android's rescue mode steps in; it can end in
  a reboot and a "factory reset?" prompt. Choose *Try again*, don't
  reset, then flash the `-uninstall` zip and send logs.
- Two signature-only permissions ImsStack asks for,
  `ACCESS_SURFACE_FLINGER` and `INTERACT_ACROSS_USERS_FULL`, can't be
  granted here. Nothing in the stack uses them.

## If something goes wrong

Follow **[How to log a problem](https://github.com/ShapeShifter499/joan-volte-lineage/blob/claude/aosp-ims-a15-backport/aosp-ims/HOW-TO-LOG.md)**.
In short, right after the failure:

```
adb logcat -b all -d > logcat.txt
adb shell dumpsys activity service com.android.imsstack/.imsservice.ImsService > ims.txt
```

The logs contain your phone number, IMSI and IMEI: **send them privately**,
not in a public issue.

## Building it into LineageOS

A source build needs none of the permission workarounds: see
[`upstream/AOSP-IMS.md`](https://github.com/ShapeShifter499/joan-volte-lineage/blob/claude/aosp-ims-a15-backport/upstream/AOSP-IMS.md).

Source, patches and build scripts: `aosp-ims/` on branch
[`claude/aosp-ims-a15-backport`](https://github.com/ShapeShifter499/joan-volte-lineage/tree/claude/aosp-ims-a15-backport/aosp-ims).
Everything here is built from source by the repository's `aosp-ims`
workflow, which also runs the installer, carrier-config and ROM checks.
