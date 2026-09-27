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
was.

**Nothing in this package has ever granted it, and until 2026-09-19 that
went unnoticed because only one handset ever reached a completed call.**

- The bench had it. A 2026-08-29 trace records
  `media ul level rms=-31.2dBFS peak=-11.6dBFS speech=-24.0dBFS
  active=19%` -- real capture. The bench is installed by `adb remount` +
  push, which needs `adb root`, so a `pm grant` during bring-up is the
  likely source. Runtime grants persist in
  `/data/system/users/0/runtime-permissions.xml` keyed by package name,
  surviving reinstalls and reboots, so one forgotten command stays in
  force indefinitely and invisibly. **Not confirmed**; one
  `dumpsys package org.joan.ims | grep -A2 RECORD_AUDIO` settles it.
- **An external T-Mobile tester DID have it**, on a flashed build. Their
  trace, alpha20 and alpha26 on 2026-09-13/16, carries real capture
  across four calls in both directions:
  `media ul level rms=-20.2dBFS peak=0.0dBFS speech=-18.3dBFS active=64%`,
  and again at 56%, 28% and 25% activity. So the permission can be
  present on a sideloaded install, and an earlier version of this section
  claimed the opposite. **The mechanism is still unidentified** -- it is
  not in this package, because nothing here grants it.
- The Digi.Mobil RO handset does NOT have it:
  `rms=-99.0dBFS peak=-99.0dBFS speech=silent` -- every sample zero, on
  both audio sources -- alongside `pani_cell=no-permission`.
- The two handsets differ in a way that is still not explained, and the
  explanations tried so far have not survived contact with the evidence.

  The T-Mobile handset throws `SecurityException` on
  `registerTelephonyCallback` for the whole session
  (`ims_diag_listener=unavailable_SecurityException`). That call needs
  `READ_PRECISE_PHONE_STATE`, which is signature|privileged and which the
  privapp allowlist grants -- and the alpha26 zip it was running **did**
  ship and install that allowlist. So on that device the allowlist was
  not in force, which means it was not a clean priv-app install. Whether
  the app was somewhere other than priv-app, whether the permissions file
  never landed, or whether that ROM sets
  `ro.control_privapp_permissions` to something other than `enforce`, is
  not established.

  A `pm install` would produce exactly that shape -- not privileged, but
  with runtime permissions the installer can grant -- and an earlier
  version of this section asserted it. **It cannot have come from the
  zip**: recovery has no package manager, so it would have to have been a
  separate `adb install` on a booted phone, which nobody has reported
  doing. The simplest explanation for the microphone needs no odd install
  at all: a priv-app still appears under Settings > Apps > Permissions,
  and the tester could have granted it there by hand.

  The `install` state row added in alpha69 answers the first half in one
  line, and `dumpsys package org.joan.ims` plus
  `getprop ro.control_privapp_permissions` answers the rest.

Without the permission the appops layer returns **digital silence rather
than an error**: `AudioRecord` constructs, reports `STATE_INITIALIZED`,
and `read()` fills the buffer with zeros. So `media record ok` appears in
the trace on a handset that cannot record, which is how this was read as
a working microphone and a network fault. Since alpha68 the trace states
`media capture record_audio=granted|DENIED` before opening anything.

### 2b. The runtime permissions: microphone and location

`etc/default-permissions/org.joan.ims.xml` lists `RECORD_AUDIO` and the
three location permissions. `DefaultPermissionGrantPolicy` applies it on
the first boot after the build fingerprint changes (a clean ROM install,
or a ROM update), so flashing joan in the same recovery session as the ROM
grants them with no taps. It is not re-run for a package added to a ROM
that has already booted; that case was measured (alpha63). Then the
"joan IMS" launcher entry asks once, or over adb with no root:

```
./scripts/grant-permissions.sh
```

No recovery zip can grant a runtime permission to an already-booted ROM
safely. alpha70-73 tried from an init service at boot, and it is gone.

**The allowlist (section 2) is load-bearing in a dangerous way.**
`ro.control_privapp_permissions=enforce` (the LineageOS default) makes a
requested-but-not-allowlisted privileged permission a **fatal boot error**,
not a silent denial, and PackageManager may be requesting on behalf of an
older cached manifest. That is why the allowlist is append-only. If you
deploy manually, push both the apk and the allowlist.

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

`update-binary` is at v8. Most of its bulk is paranoia earned from real
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
4. **Check free space before writing anything.** A partition without
   room is refused with its number and nothing changes.
5. Remove leftovers: the legacy `joan-ims` daemon, and the alpha70-73
   boot-time grant (`joan-grant.rc` / `joan-grant.sh`) whenever found.
6. Install the **allowlist first**, then the APK, then the default
   grants, the IMS feature xml, and both overlays. Every copy is
   **write-then-rename**: the new file is written beside the old one,
   size-checked and byte-compared, then renamed over it, so a full
   partition leaves each file old or new and never truncated.
7. **Record ownership of the IMS feature xml.** Absent, or byte-identical to
   ours, marks it `.joan-added`; present and different backs the ROM's copy up
   to `.joan-orig`. Decided once, before the first overwrite, so a re-flash
   cannot mistake our own file for the ROM's.
8. Back up `apns-conf.xml` once, merge in the ramdisk, check the result,
   then rename it into place. This step is optional: any failure leaves
   the ROM's list untouched and prints a warning instead of failing the
   flash. If the ROM replaced the list since the last merge, the backup
   is re-based onto the new one first.
9. **Re-date what PackageManager scans** (`stamp_future`), so the next
   boot parses this build's manifest instead of a cached earlier one.
10. `sync`, and unmount **only** what it mounted itself.

### Why step 9 exists: the upgrade bootloop

PackageManager reuses a cached parse of a package while the scanned path
is older than the cache entry. For a priv-app that path is the
directory, which an in-place overwrite never made newer, and recovery's
clock reads 2017. So after a flash the phone kept parsing the previous
build's manifest. When alpha70+ removed `BIND_IMS_SERVICE` from both the
manifest and the allowlist, a phone upgrading from alpha67 still
requested it through the cache, with no allowlist entry: a fatal
privapp violation, and a bootloop. The allowlist is now append-only as
well, so the stamp is belt and braces rather than the only defence.
`docs/install-troubleshooting.md` has the full account.

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
