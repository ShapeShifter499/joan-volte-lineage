# vendor/lge/joan-ims (LineageOS 22)

AP-side IMS (VoLTE) for the LG V30 (joan). SIP, AKA, and IPsec run in
the Java `ImsService` over public `IpSecManager` APIs. There is no
native daemon and no loopback control socket.

## The split

Getting VoLTE working on a LineageOS device takes two separate things,
and confusing them wastes a lot of time:

**Part 1 — the IMS implementation.** This directory. The `ImsService`
app, the service-discovery RRO, the permission files, and the build
wiring. It is device-specific work and it is the part that is finished
and confirmed working.

**Part 2 — the platform VoLTE variables.** `VOLTE-PLATFORM-SETUP.md`.
The device-tree resources and carrier config keys the framework checks
before it will admit VoLTE *at all*. None of it is joan-specific; any
device with an AP-side `ImsService` needs the same list.

Part 1 without part 2 produces a stack that registers successfully,
receives inbound calls, and still sends every outbound call over GSM,
with no VoLTE toggle anywhere in Settings. That is a configuration
result, not a SIP bug, and part 2 exists so nobody spends a week reading
packet captures to discover it.

## Where the flashable zips fit

The repo root builds `joan-volte-recovery.zip`, a sideload package that
installs the same app plus the overlays and permission files into an
existing LineageOS build. **It is a stopgap.** It exists so people can
run this before LineageOS carries it, and so testers on carriers we
cannot reach can try a build without compiling a ROM.

Once a device tree inherits `joan-ims.mk` and carries the part 2
settings, the zip is redundant and should not be used — see "Do not"
below. Treat the zip path as the temporary one and this directory as the
destination.

## Inherit

Copy this directory to `vendor/lge/joan-ims` (or keep it as a git
submodule). In `device/lge/joan/device.mk`:

```
$(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
```

`joan-ims.mk` expects the ImsService sources at `ims-service/` next to
this makefile (this repo's layout). If you vendor only this folder,
point `LOCAL_PATH` at a checkout that also contains `ims-service/` and
`permissions/`.

Inheriting this module covers part 1 only. You still have to apply
`VOLTE-PLATFORM-SETUP.md` to your device tree and carrier config.

## Layout

    Android.bp          JoanIms privileged app
    joan-ims.mk         PRODUCT_PACKAGES + overlays + IMS feature xml
    rro/                JoanImsPhoneDefault RRO: config_ims_mmtel_package
    rro-fw/             framework-res RRO: config_device_volte_available
                        (part 2 term 1, for the out-of-tree path only —
                        in a ROM build use a device-tree overlay instead)
    permissions/        telephony.ims feature + privapp allowlist

Only `Android.bp` and `joan-ims.mk` live in this directory. Everything
they build — `ims-service/`, `permissions/`, `rro/`, `rro-fw/` — sits at
the repo root, which is why both files refer to those paths with `../`.

## Confirmed working — part 1

- `ImsService` / `MmTelFeature` registration, including AKA from the ISIM
  or from the USIM (TS 23.003) when the SIM has no ISIM application
- 3GPP sec-agree and transport-mode ESP over `IpSecTransform`
- REGISTER, INVITE/ACK/BYE, RTCP SR+SDES and receiver reports
- AMR-WB and AMR-NB in both RFC 4867 framings, negotiated from a
  MediaCodec probe of what the ROM actually carries, with PCMU as the
  floor. Verified on a live carrier in one session: bandwidth-efficient
  inbound, octet-aligned outbound, both AMR-WB at 12650 bps
- DTMF as RFC 4733 telephone-events, negotiated at the chosen codec's
  clock rate
- Session timers (RFC 4028) and RFC 3556 session bandwidth, both read
  from carrier config rather than hardcoded
- Hold initiated by the far end, answered with a mirrored SDP direction
- Registration event package (RFC 3680), so a network-initiated
  deregistration is seen at once rather than at the next refresh
- SRVCC notification, so a call leaving LTE can be handed to CS
- Local IP change during a call: media is rebound and the dialog
  re-INVITEd rather than left silent
- MO and MT calls with two-way audio, demonstrated on the development
  handset
- Device-service binding via `config_ims_mmtel_package`

One caveat on the MO result: until 2026-09-14 the development handset
read `config_device_volte_available = false` with no
`persist.dbg.volte_avail_ovr` set, which by the part 2 gate should have
sent outbound dials to CS — and indeed it had no VoLTE toggle in Settings
at all. Either the earlier MO demonstrations ran with that property set,
or MO reached IMS by a path the gate does not cover. Setting term 1 made
the toggle appear (ON, with no user-setting write), so the gate analysis
holds; how MO previously succeeded without it does not, and is worth
resolving before anyone treats the older MO result as a baseline. It is
the reason part 2 is written as a checklist to verify rather than a story
to trust.

See the repo root `README.md` for what is explicitly **not** carried —
emergency calling, SMS/MMS over IMS, video, RTT, Ut/XCAP supplementary
services, VoWiFi — before relying on this on a daily-driver handset.
Emergency calling is the one to read twice: it is deliberately declined
rather than half-implemented, and on a carrier that has retired 2G/3G
that means emergency calls may have nowhere to go.

## Do not

- Do not start a `joan-ims` init service.
- Do not listen on `127.0.0.1:15090`.
- Do not flash the recovery zip on a ROM that already inherits this
  module — you would install the app twice.
- Do not ship the `rro-fw/` overlay in a ROM build. Set
  `config_device_volte_available` in the device tree instead; an RRO is
  the mechanism for installing into a build you do not control.

## Uplink gain: patch audio_effects.xml in the device tree

> ### This is the main thing a ROM build gets that the zip cannot
>
> **The flashable zip does not do this and never will.** It was tried
> and removed on 2026-09-16: `/vendor` on this device has 335 free
> blocks and refuses a 7.5 KB write, so the installer carried a mount,
> a write probe and a failure path that existed only to be skipped.
> `scripts/merge-agc-effect.sh` moved here, to `upstream/`, because a
> ROM build is the only place it can run.
>
> **If you are building a ROM for joan, apply this.** It is two lines and
> it is measurable. Verified on the bench, same handset and same PSTN far
> end, two calls each way before and after, with the far end confirming
> the difference by ear:
>
> | | without | with |
> |---|---|---|
> | uplink speech | -39.2 dBFS | **-32.7 / -31.9 dBFS** |
> | uplink peak | -20.8 dBFS | **-10.4 / -11.0 dBFS** |
> | downlink speech | -23.2 dBFS | -23.4 / -21.7 dBFS (unchanged) |
>
> About +7 dB of speech and +10 dB of peak. The uplink still sits some
> 9 dB below the downlink, so this improves the problem without closing
> it -- do not expect it to be the last word on level.
>
> `upstream/merge-agc-effect.sh` applies the patch to an existing
> `audio_effects.xml` idempotently, preserving every library and effect
> the ROM already declares. Run it against the file in your device tree
> and commit the result, or apply the two lines by hand.
>
> Confirm it landed with `platform_agc=true` in the joan trace. A trace
> reading `platform_agc=false` means the declaration is absent and the
> uplink is running unconditioned.
>
> ### Keeping it on a development handset
>
> `upstream/apply-agc-live.sh [serial]` applies the same patch to a
> running device over `adb remount`, and is idempotent -- run it again
> and it says "already declared". **This is for a bench, not for
> testers.** It writes through the scratch overlay, which is a debug
> facility: a LineageOS nightly, an OTA or a factory reset all wipe it,
> and the only symptom is quieter calls with `platform_agc=false` as the
> sole clue. Re-run it after any ROM update. Flashing the zip does not
> disturb it, because the zip does not touch `/vendor`.
>
> One thing it guards against, learned the hard way: the copy on the raw
> `/vendor` partition is **0 bytes** on this ROM, and the working file
> has always come from the overlay. Do not "repair" the partition copy --
> it cannot be written (335 free blocks, ENOSPC even for an in-place
> rewrite) and it is not what the system reads.



**This app does no gain control at all.** That is deliberate -- no IMS
implementation does it in the application; on a normal handset the ADSP
voice topology conditions the uplink from ACDB calibration, and for the
AP VoIP path the platform's AGC effect does it. JoanMedia reads PCM and
encodes it, nothing more.

joan has no AGC on that path, so without the patch below the transmitted
level is whatever the microphone gave. Measured before any of this:
uplink speech ranging -24 to -45 dBFS across calls against a far end
arriving at -16 to -23, with peaks clipping. With the platform AGC
enabled it sat at -19.6 to -24.0 against a -19.3 downlink on the same
PSTN call.

### The patch

`/vendor/etc/audio_effects.xml` is built by LineageOS -- `ro.vendor.build.date`
matches `ro.system.build.date` -- so this belongs in `device/lge/joan-common`
(or wherever that file is sourced) and every nightly then carries it:

    <library name="pre_processing" path="libaudiopreprocessing.so"/>
    <effect name="agc" library="pre_processing" uuid="aa8130e0-66fc-11e0-bad0-0002a5d5c51b"/>
    ...
    <stream type="voice_communication">
      <apply effect="aec"/><apply effect="ns"/><apply effect="agc"/>
    </stream>

`libaudiopreprocessing.so` -- AOSP's WebRTC audio processing module, which
implements AGC -- already ships on the device and nothing references it.
The uuid was verified by finding it as a packed `effect_uuid_t` inside the
device's own copy of that library, not taken from documentation. The
struct is `{u32 timeLow; u16 timeMid; u16 timeHiVer; u16 clockSeq; u8
node[6]}`, first four fields little-endian -- pack clockSeq the wrong way
round and every uuid appears absent.

Once it lands, `AutomaticGainControl.isAvailable()` returns true and the
trace line `media record ok src=7 platform_agc=true` confirms it.

**Declaring the effect is sufficient; the `<stream>` block is not
required.** JoanMedia attaches the AGC itself, in `attachEffects()`, via
`AutomaticGainControl.create(sessionId)` on the AudioRecord session --
so the two lines above are the whole patch. The
`<stream type="voice_communication">` form is the alternative for a
platform that should apply it to every VoIP capture rather than only to
this app; either works, and the declaration-only form touches less.

Measured on the US998 bench, 2026-09-16, same handset and same PSTN far
end, two calls before and two after:

| | declaration absent | declaration present |
|---|---|---|
| uplink speech | -39.2 dBFS | **-32.7 / -31.9 dBFS** |
| uplink peak | -20.8 dBFS | **-10.4 / -11.0 dBFS** |
| downlink speech | -23.2 dBFS | -23.4 / -21.7 dBFS |

About +7 dB of speech level and +10 dB of peak, with the downlink
unchanged as it should be. The uplink is still some 9 dB below the
downlink, so this improves the problem rather than closing it.

Re-confirmed 2026-09-16 on the US998: `/vendor` reports 335 free blocks
and refuses a write of 7.5 KB, ENOSPC, including an in-place rewrite of
a file it already holds. The installer now probes this with a
write-readback and skips the AGC step with a message rather than
failing the flash -- an earlier attempt guarded the mount with
`|| true`, which cannot catch the `exit 1` inside the installer's own
`error()`, and aborted the entire install before a single file was
copied.

### Why the flashable zip cannot do this

Recorded so nobody re-derives it. The config search path, read out of
`libaudiopolicyenginedefault.so` on the device, is:

    /odm/etc  ->  /vendor/etc  ->  /system/etc

**First file wins.** It is not a merge and not per-element overriding: a
file at a higher-priority location displaces the lower one entirely.

- `/odm/etc` -- highest priority, and a 1.3 MB image with 8 KB free.
  Returns ENOSPC. Dynamic partitions are sized to their contents; the
  slack lives in `super`, not in the partitions.
- `/vendor/etc` -- ENOSPC as well, including for an in-place rewrite of a
  file that already exists. It is also replaced by every OTA.
- `/system/etc` -- writable, but last in priority and always shadowed by
  vendor's copy.

### Do not delete the vendor file so /system/etc wins

It looks attractive, because `/system/etc/audio_effects.xml` already
declares `agc` with the correct uuid. It is a trap.

That file's `<preprocess>` block is **inside an XML comment** -- it is
AOSP's documentation example, not live configuration. Fall back to it and
`aec`, `ns` and `agc` are declared and never applied: no echo
cancellation, no noise suppression and no gain control on the VoIP path.
You also lose everything the vendor config carries and the system one
does not -- the `music` / `ring` / `alarm` / `notification` / `voice_call`
postprocess chains, Qualcomm's `volume_listener` speaker protection,
`offload_bundle`, `audiosphere`, the hardware visualizer, and the SW/HW
`effectProxy` routing for bassboost/equalizer/virtualizer/reverb.

Worse, `AutomaticGainControl.isAvailable()` would return **true**, since
the effect is declared. Anything deferring to the platform on that basis
stands down while nothing is applied.

And vendor cannot be written back: it returns ENOSPC even for blocks it
has just freed. Such a deletion is only repaired by the next nightly.

## Licence

Apache-2.0. See `LICENSE`.
