# upstream/ — building the AOSP IMS stack into LineageOS

This directory is the source-build kit for the AOSP IMS backport:

- **[`AOSP-IMS.md`](AOSP-IMS.md)** — how to build the stack into a
  LineageOS 22.2 tree (and what taking it upstream to LineageOS
  involves): a local manifest, the backport patches, and one
  `device/lge/joan-common` patch.
- **`aosp-ims/apply-patches.sh`**, **`aosp-ims/local_manifests/`**,
  **`aosp-ims/device/`** — the kit itself, pinned in
  `aosp-ims/upstream.lock`.
- **`VOLTE-PLATFORM-SETUP.md`** — the platform-side VoLTE variables
  (framework resources and carrier config keys) that gate whether any
  AP-side IMS stack is admitted at all. Not joan-specific.

The from-scratch joan IMS implementation this once described
(`vendor/lge/joan-ims`, `joan-ims.mk`, the `org.joan.ims` app) is
retired on this branch; its documentation and history live on the joan
branches of this repository.

## Device-tree note worth keeping: the platform AGC effect

Stack-agnostic hardware knowledge from the joan work, relevant to any
IMS stack on this device — the AOSP path included, since ImsMedia's
audio runs in `MODE_IN_COMMUNICATION` and depends on the platform's
voice-communication effects:

- `/vendor/etc/audio_effects.xml` declares the platform's AGC
  (`libaudiopreprocessing.so`, AOSP's WebRTC module — already on the
  device and unreferenced). The effect uuid below was verified inside
  the device's own copy of that library, not taken from documentation:
  `aa8130e0-66fc-11e0-bad0-0002a5d5c51b`.
- The config search path is `/odm/etc → /vendor/etc → /system/etc`,
  **first file wins** — a higher-priority file displaces the lower one
  entirely; it is not a merge.
- Do **not** delete the vendor file so `/system/etc` wins: the system
  file's `<preprocess>` block is inside an XML comment (AOSP's
  documentation example), so aec/ns/agc would be declared and never
  applied — while `AutomaticGainControl.isAvailable()` still returns
  true. And `/vendor` cannot be written back: on this ROM it returns
  ENOSPC even for in-place rewrites of files it already holds.
- The fix belongs in the device tree (joan-common or wherever
  `audio_effects.xml` is sourced), not in a flashable zip:
  `merge-agc-effect.sh` here patches an existing `audio_effects.xml`
  idempotently; `apply-agc-live.sh [serial]` applies it to a running
  bench device over `adb remount` (wiped by every OTA).
- Confirm with `platform_agc=true` in the joan trace. Whether AGC
  improves the uplink *level* was never established — an early +7 dB
  claim did not survive interleaved A/B measurement; apply it because
  effect declaration is the platform's job, not because of a number.

## Licence

Apache-2.0. See `LICENSE`.
