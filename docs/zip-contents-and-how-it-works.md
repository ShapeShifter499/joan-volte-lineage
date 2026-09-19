# What is in the flashable zip, and how it works

Describes `joan-volte-recovery.zip` and `joan-volte-uninstall.zip` as of
**v0.4.0-alpha20 (versionCode 28)**. Written so nobody has to reverse
engineer the zip to understand what it does to their phone.

## Contents

```
   111140  app/joan-ims.apk                                     the IMS stack itself
     8530  app/joan-ims-rro.apk                                 overlay: "joan is the IMS service"
     8530  app/joan-fw-volte.apk                                overlay: "this device can do VoLTE"
      711  etc/permissions/org.joan.ims.xml                     privileged-permission allowlist
      182  etc/permissions/android.hardware.telephony.ims.xml   system feature declaration
     1517  apn/viettel-45204.xml                                Viettel IMS/XCAP APN rows
     4043  scripts/merge-viettel-apns.sh                        merges those rows into the world list
    11540  META-INF/com/google/android/update-binary            the installer (shell)
       54  META-INF/com/google/android/updater-script           dummy; Lineage runs update-binary
```

Nothing native is installed. The C under `native/` is host unit tests and
historical bring-up code.

## The four conditions

VoLTE on an AP-side stack is not one switch. Four independent conditions
must all hold, and each file in the zip exists to satisfy one of them.
This is the model that makes everything else legible.

### 1. The framework has to find joan and bind it

`joan-ims-rro.apk` is a runtime resource overlay targeting
`com.android.phone`, setting exactly one string:

    string/config_ims_mmtel_package = "org.joan.ims"

That makes `ImsResolver` classify joan as the **device** IMS service, so it
reads the MMTEL feature metadata directly from the manifest at package scan
time and binds it at every boot — no runtime override, no dynamic feature
query. Without it, joan is treated as a carrier-style service with no
declared features and is never bound at all.

`android.hardware.telephony.ims.xml` declares the matching system feature so
the framework builds its IMS machinery in the first place.

### 2. joan has to be allowed to do privileged things

`org.joan.ims.xml` allowlists the signature|privileged permissions the app
requests: `MODIFY_PHONE_STATE`, `READ_PRIVILEGED_PHONE_STATE`,
`READ_PRECISE_PHONE_STATE`, `CONNECTIVITY_USE_RESTRICTED_NETWORKS`,
`BIND_IMS_SERVICE`, `MODIFY_AUDIO_SETTINGS`.

**`RECORD_AUDIO` is also listed there and that entry does nothing.** It is
a *dangerous runtime* permission, not `signature|privileged`:
privapp-permissions neither grants it nor requires it to be listed, so the
line is never consulted. It is left in place as harmless, but it is not
what makes microphone capture work, and this file previously claimed it
was. **How `RECORD_AUDIO` is actually granted on the bench has not been
established** -- `dumpsys package org.joan.ims` on a working handset would
settle it in one line, and it is worth settling, because a tester whose
uplink is silent may simply not have it.

### 2b. The one runtime permission, pre-granted

`etc/default-permissions/org.joan.ims.xml` is the mechanism that *does*
apply to runtime permissions, and it carries exactly one:
`ACCESS_FINE_LOCATION`, which `getAllCellInfo` has required since API 29
and which P-Access-Network-Info needs so it can carry
`utran-cell-id-3gpp`. Nothing else in the package uses location, and the
grant is `fixed="false"` so it can be revoked.

This one is **best effort and unverified on a handset**:
`DefaultPermissionGrantPolicy` applies these when it runs, and whether it
re-runs for a package added to `/system` after the device was provisioned
has not been tested. If it does not apply, the header falls back to the
bare access type, the `pani_cell` state row reads `no-permission`, and one
command fixes it with no root:

```
adb shell pm grant org.joan.ims android.permission.ACCESS_FINE_LOCATION
```

Nothing about registration depends on it.

**This file is load-bearing in a dangerous way.**
`ro.control_privapp_permissions=enforce` (the LineageOS default) makes a
requested-but-not-allowlisted privileged permission a **fatal boot error**,
not a silent denial. Pushing the APK by hand without this file bootloops the
device. If you deploy manually, push both.

### 3. The platform has to admit VoLTE

This is the part alpha20 added, and the reason the release exists.

```java
isVolteEnabledByPlatform() =
      config_device_volte_available     // framework-res bool, whole device
   && carrier_volte_available_bool      // carrier config, per carrier
   && isGbaValid();                     // carrier config + SIM
```

An **AND of three terms owned by three different subsystems.** joan ships the
first one false, so the AND was false for every SIM. The effect: the Settings
VoLTE toggle is not rendered at all, and `GsmCdmaPhone.isImsUseEnabled()` is
false, so an outbound dial is refused the IMS path — while REGISTER still
succeeds and inbound calls still work. On a carrier that still runs 2G/3G the
call drops to circuit-switched; on a VoLTE-only carrier there is nothing to
fall back to and it simply fails. That combination looks exactly like a SIP
bug and is not one.

- `joan-fw-volte.apk` overlays package `android` with
  `bool/config_device_volte_available = true` — **term 1**.
- `JoanVolteCarrierGate`, compiled into the APK, handles **term 2** at
  runtime (below).
- Term 3 needs nothing from the zip unless a carrier asset requires GBA.

Measured, not argued: removing `joan-fw-volte.apk` from a working bench
handset and rebooting reproduces the outbound failure testers report —
`last_dial` empty, no call session created, registration healthy, and Telecom
ending the attempt with `DisconnectCause Code:(ERROR) ... TelephonyCause:
36/-1`. Restoring it restores outbound calling.

A ROM build should set this bool in the device tree instead; the overlay
exists for installing into a build we do not control. See
`upstream/VOLTE-PLATFORM-SETUP.md`.

### 4. There has to be an IMS PDN

`viettel-45204.xml` plus `merge-viettel-apns.sh` add `apn="ims"` and
`apn="xcap"` rows for Viettel 45204. The script **merges** into the existing
`apns-conf.xml`; copying a four-row file over it would wipe every other
carrier in the world list. The original is kept as
`/product/etc/apns-conf.xml.joan-orig`.

## The carrier gate, at runtime

`JoanVolteCarrierGate` reads the merged carrier config and decides:

| config applied | VoLTE available | toggle usable | decision |
|---|---|---|---|
| no | – | – | `WAIT_CONFIG` — early boot, retry |
| yes | yes | yes | `SKIP_ALREADY` — the ROM already allows it, write nothing |
| yes | yes | no | `APPLY_VISIBILITY` — force the toggle visible/editable |
| yes | no | – | `APPLY_FULL` — admit VoLTE and force the toggle usable |

Three properties worth knowing:

- **The write is non-persistent.** It evaporates on reboot, so uninstalling
  genuinely undoes it. A persistent override would be stored inside
  `com.android.phone`, where the uninstall zip cannot reach it.
- **It forces the toggle visible and editable** because a restored carrier
  cache can ship `hide_enhanced_4g_lte_bool=true`, which vanishes the control
  and steals the user's opt-out.
- **It never writes user settings.** `enhanced_4g_lte_on_by_default_bool`
  already supplies default-on, so the toggle remains genuinely the user's.

It re-checks on every `ACTION_CARRIER_CONFIG_CHANGED` rather than remembering
that it ran, because the override lives in another process: if
`com.android.phone` restarts, the override is gone while this service is
still running, and a remembered "applied" would leave VoLTE off with the
state row still claiming success.

The `volte_gate` state row reports `applied` / `applied-visibility` /
`skip:already-true` / `wait:config-not-applied` / `fail:<Ex>`, plus
`reapplied=N` when an override had to be restored.

## What the installer does

`update-binary` is at v6. Most of its bulk is paranoia earned from real
failures:

1. Unzip to `/tmp/joan-volte`; refuse if the APK is implausibly small.
2. **Mount system and product**, reusing a mount recovery already holds.
   Recovery mounts `/system` to read `otacerts.zip` while verifying the
   package signature, and ext4 refuses a conflicting second rw mount of the
   same device — that race made installs fail with `cannot mount system` on
   some attempts and not others.
3. **Prove the mount is not the recovery ramdisk** by comparing its `st_dev`
   against `/tmp`. An earlier version wrote into the ramdisk, reported
   "Install completed", and left nothing installed after reboot.
4. Every copy is size-checked **and byte-compared after writing** — a failed
   mount can accept a write and serve different bytes back.
5. Remove the legacy `joan-ims` daemon binaries and init scripts.
6. Install the APK, both overlays, and both permission files.
7. **Record ownership of the IMS feature xml.** Absent, or byte-identical to
   ours, marks it `.joan-added`; present and different backs the ROM's copy up
   to `.joan-orig`. Decided once, before the first overwrite, so a re-flash
   cannot mistake our own file for the ROM's.
8. Back up `apns-conf.xml`, merge, sanity-check the result is larger than the
   overlay and contains both `ims` and `xcap`, then write it.
9. `sync`, and unmount **only** what it mounted itself.

## What the uninstall zip does

Two files, no payload. It reverses everything: removes the app, both
overlays and the privapp allowlist; restores `apns-conf.xml` from
`.joan-orig`; and for the IMS feature xml restores the ROM's copy or removes
ours according to the marker, leaving it alone if ownership cannot be proven.

It fails loudly if it cannot mount. A previous version would walk every `rm`,
remove nothing, and still report success — an uninstall that silently no-ops
while telling you it worked.

## Not carried

Emergency calling (dials go CS; untested against a PSAP), SMS/MMS over IMS,
DTMF (RFC 4733), VoWiFi, and video. Conference merge is implemented but not
live-carrier qualified. Read the "Emergency calling" section of the top-level
`README.md` before relying on this on a handset you depend on.

---

Signed-off-by: Lance <Gero3977@gmail.com>
Assisted-by: Claude-Code:claude-opus-5
Date: 2026-09-15
