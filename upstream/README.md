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

## Optional: platform AGC for the uplink

Not required, and not done by the zip. Read this only if you are building
in-tree and want the platform to do gain control instead of the app.

There is no AGC on the VoIP capture path on joan. `/vendor/etc/audio_effects.xml`
declares only Qualcomm's `aec` and `ns` from `libqcomvoiceprocessing.so`:

    <library name="audio_pre_processing" path="libqcomvoiceprocessing.so"/>
    <effect name="aec" library="audio_pre_processing" uuid="0f8d0d2a-..."/>
    <effect name="ns"  library="audio_pre_processing" uuid="1d97bb0b-..."/>
    <preprocess>
      <stream type="voice_communication">
        <apply effect="aec"/><apply effect="ns"/>
      </stream>
    </preprocess>

AOSP's `libaudiopreprocessing.so` — the WebRTC audio processing module,
which does implement AGC and AGC2 — ships on the device and nothing
references it. LG's own uplink conditioning lives in the ADSP voice
topology loaded from ACDB (`/vendor/etc/acdbdata/`), which only the
modem's voice path reaches, never the AP VoIP path this stack uses.

JoanMedia therefore runs its own software AGC and limiter. It calls
`AutomaticGainControl.isAvailable()` first and defers entirely to the
platform when a ROM provides one, so enabling the effect below makes the
app step aside automatically. No app change is needed either way.

To enable it, add to the device's `audio_effects.xml` (in
`device/lge/joan-common`, not from a flashable zip — `/vendor` writes are
reverted by OTA and a stale copy of a vendor config breaks audio
system-wide):

    <library name="pre_processing" path="libaudiopreprocessing.so"/>
    <effect name="agc" library="pre_processing" uuid="..."/>
    ...
    <stream type="voice_communication">
      <apply effect="aec"/><apply effect="ns"/><apply effect="agc"/>
    </stream>

Take the UUID from AOSP's reference `frameworks/av/media/libeffects/data/audio_effects.xml`
in your own tree rather than from this document; it is version-specific
and worth checking against the library you are actually shipping.

This is arguably the *correct* shape rather than a risky addition. Most
Android devices expose an AGC on `voice_communication` --
`AutomaticGainControl.isAvailable()` returns true on the majority of
handsets -- and WebRTC-based apps are built and shipped against that
majority. An app that pumped whenever a platform AGC was present would be
broken on most phones. joan lacking one is the anomaly, and it exists
because LG's voice path went through the ADSP: the `voice_communication`
block was only ever for third-party apps, so it was never tuned.

Two things to check, neither a blocker:

- `<preprocess>` applies to every app using `voice_communication`, not
  just this one. Make a Signal or Meet call before and after and listen.
- Qualcomm AEC/NS feeding an AOSP AGC is not a combination anyone has
  validated on this device specifically.

If it works, it is the better home for gain control than an app doing it
in software, and the app gets out of the way on its own.

Measured before deciding: with the app's software AGC, uplink speech sits
at -21.3 dBFS against a far end arriving at -19.5 dBFS. Level is not
currently the limiting factor on this handset; narrowband PCMU is.
