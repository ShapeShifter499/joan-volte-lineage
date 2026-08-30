# joan-volte-lineage — H932 bring-up + installer/IMS-bind fixes (Aurel → Ember)

Author: Aurel Nymvale · Hermes-Agent:zai/glm-5.3-flash · 2026-08-29 (rev 2, supersedes the earlier same-day draft)
Repo: `~/vibe-coding-projects/coding/joan-volte-lineage` · branch `main` at
`018875a` — **3 commits ahead of origin, NOT pushed (Lance review pending)** ·
base `b47f5d9` = v0.2.0 = origin tag v0.2.1 (your repackage, same tree).

## Story in one paragraph

Handset on the nest is now the **H932** (LGH9322d3f3c24, T-Mobile 310260),
same LOS 22.2 nightly as the US998. Your v0.2.0 zip sideload on it *silently
no-oped*: recovery logged signature error 21 (expected, dev-signed) then
"Install completed with status 0", but JoanIms never landed. v5's
`mount -o rw,remount / || true` fails on SAR+AVB in recovery and the writes
went into the recovery ramdisk. Second issue behind it: even after a live
`adb remount` deploy, MmTel never bound — `config_ims_mmtel_package` is empty
in TeleService, so ImsResolver classified JoanIms carrier-style with no
features. The US998 only ever bound because `set-ims-service -d` was run by
hand during bring-up.

## The three commits (all trailers verified via `%(trailers:only)`)

1. **`226c7c9`** — SAR/verity-safe **update-binary v6** (st_dev check, write
   test, `cmp -s` read-back on every copy, loud errors) + **static RRO**
   `org.joan.ims.rro.phonedefault` → `com.android.phone`, sets
   `config_ims_mmtel_package=org.joan.ims` (priority 2, isStatic). Resolver
   then classifies JoanIms as the device service at scan time and reads the
   manifest MMTEL_FEATURE metadata — no runtime commands, no dynamic query,
   every boot, every joan variant. pack-zip.sh builds + packs
   `app/joan-ims-rro.apk`.
2. **`427fd1c`** — adopts your **v0.2.1 no-vendor packaging** as repo policy:
   vendor-legacy block removed from v6 installer *and* uninstaller
   (`scripts/update-binary`, `scripts/update-binary-cleanup`). Uninstaller
   also gained `/product/overlay/JoanImsPhoneDefault.apk` removal — without
   it an uninstall left `config_ims_mmtel_package` pointing at a ghost.
3. **`018875a`** — APK bumped to **0.3.0 (versionCode 4)**. Skipped 0.2.1:
   that name is burned on your repackage release whose zip had the v5
   installer. versionCode bump keeps upgrade-over-install semantics.

## Verified on H932 hardware (2026-08-29)

- JoanIms v0.2.0 installed priv-app via adb-remount loop, cert `16f22b2e…`
  (matches your handoff); app UA registered: AKA+IPsec, reg2=200 OK,
  `pcscf_n=3 pcscf_tried=1`, refresh firing on schedule
- RRO live on device: `[x] org.joan.ims.rro.phonedefault`, `cmd overlay
  lookup` → `org.joan.ims`
- Resolver: `Device: MMTEL -> org.joan.ims`, `featureFromMetadata=true,
  features: [{0,MMTEL}]`, controller `isBound=true`
- `MmTelFeatureListeners: state=READY, reason=READY, hasConfig=true`
- project-profile check green at every commit; ad-hoc verifier for
  `427fd1c`: 23 pass / 0 fail / 1 warn (uninstaller not host-executed —
  absolute-path `rm`; static coverage + zip byte-identity is the proof;
  functional test happens at next hardware flash)

## Artifact hashes (current build at `018875a`)

```
5e638d65bb324860312763ac67ef9e515695aed4312a7996dbf34f26ca24bf77  out/joan-volte-recovery.zip
78958505955e7f1f49d81d8979e8c8c5e510bd6be17298cccbdd7866562ed7fe  out/joan-volte-uninstall.zip
69b95a3576c328b395ed6c98e5743abb1a73e1dcba1d4e0bb76693a68f0f8ff2  ims-service/build/joan-ims.apk   (0.3.0, vc4)
f87f5e3245dc88c5743b9b417562b0370da596481c4b9560c4b66bcc38da0b66  rro/build/joan-ims-rro.apk
```

## Traps for the next agent (do not re-derive)

- `cmd phone ims set-ims-service -d <pkg>` **returns true and does nothing**
  without `-f` (empty feature map iterates nothing in
  `overrideDeviceService`). Probe form: `-d -f 1 <pkg>` (1=FEATURE_MMTEL).
  Even working, it's RAM-only.
- Dynamic feature query path is a dead end on this build: resolver binds
  the service (app `onCreate` fires in the trace) then
  `dynamicQueryComplete` returns empty in ~20ms;
  `querySupportedImsFeatures` never executes. Don't debug the app —
  reclassify via the RRO/device-default path.
- `adb install -r` on org.joan.ims → `INSTALL_FAILED_INVALID_APK`
  (persistent app). System push + reboot is the only update route.
- Recovery ramdisk writes are the SAR shape of "exit 0, nothing happened".
- Your §2 traps stand: RTCP dual-send is load-bearing (e7783f8); DEC7
  Volume 85 is +1 dB, not +85.

## Owed / open

- **Live call each way on the H932** (Lance places; no auto-dial) — also
  validates the v0.2.0 AGC on-air (your §3 first-job).
- MT path on T-Mobile H932 unproven.
- Next sideload test of the v6 installer: US998 first (same LOS recovery).
- Release cut from `main` will be **v0.3.0**: v6 installer + RRO + no-vendor
  policy + matching APK label. Release-notes text Lance wanted (how to
  flash, dynamic-system recovery, installer+uninstaller) still to write.
- Your §5 ranking stands (A3 socket race first, then AMR-WB, A5/A6/A8,
  C2/C3, NI19, 423). 298e51e message correction still open if wanted.
