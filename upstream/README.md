# vendor/lge/joan-ims (LineageOS 22)

AP-side IMS (VoLTE) for the LG V30 (joan). SIP, AKA, and IPsec run in
the Java `ImsService` over public `IpSecManager` APIs. There is no
native daemon and no loopback control socket.

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

## Layout

    Android.bp          JoanIms privileged app
    joan-ims.mk         PRODUCT_PACKAGES + overlays + IMS feature xml
    rro/                JoanImsPhoneDefault RRO: config_ims_mmtel_package
    permissions/        telephony.ims feature + privapp allowlist

## Do not

- Do not start a `joan-ims` init service.
- Do not listen on `127.0.0.1:15090`.
- Do not flash the recovery zip on a ROM that already inherits this
  module — you would install the app twice.

## Licence

Apache-2.0. See `LICENSE`.

## Uplink gain: patch audio_effects.xml in the device tree

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
