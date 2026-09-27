# Handoff — 2026-09-27 (b) — AOSP IMS: open the carriers up, then release alpha1

> **Superseded** by `HANDOFF-2026-09-27c-aosp-ims-usat-and-bench.md`:
> items 1 and 2 below are done (run-time assets rather than the RRO
> proposed here).

Branch `claude/aosp-ims-a15-backport`. Read `docs/HANDOFF-2026-09-27-aosp-ims-backport.md`
first (what exists and why). This file is what the user asked for after
it, and exactly where the work stopped.

## State

- Pushed: ROM repack, zips, source-build kit (`upstream/AOSP-IMS.md`),
  carrier-config generator and checks, CI workflow.
- **No release is published yet.** The first CI run was cancelled on
  purpose (it would have published alpha1 before the changes below).
  `.github/workflows/aosp-ims.yml` now publishes **only** when a push
  changes `aosp-ims/RELEASE` (or a manual run with "publish" ticked).
- CI run 36316782850 (commit e9f7eef) was a from-scratch **build check**
  still running at handoff: check it on the Actions tab. If it failed,
  fix that first; logs are only downloadable after the job ends.
- Locally verified before the push: e2e installer tests 164/164,
  `tests/check-rom.sh` 30/30, `tests/check-carrier-config.py` 2866 SIM
  identities OK + device patch in sync.

## What the user asked for (not done yet)

Answer to "which all users": **"LineageOS, include their VoLTE and VoWifi
carrier support we lack", "Wi-Fi calling, every carrier", "Every V30 model"**.

### 1. Wi-Fi calling for every carrier (small)

- `zip/java/com/android/imsstack/joan/CarrierImsGate.java`:
  `wfcWanted = !wfcAvailable && isWfcProfile(...)` → `!wfcAvailable` for
  every SIM. Keep the ePDG table.
- `tools/make-carrier-config.py`: put `carrier_wfc_ims_available_bool=true`
  in the filterless block; ePDG blocks stay per operator.
- `tests/check-carrier-config.py`: expected WFC becomes true for all.
- Regenerate the joan-common patch (`--splice`, see `upstream/AOSP-IMS.md`),
  update docs (READMEs, RELEASE-NOTES, AOSP-IMS.md say "103 carriers").

### 2. LineageOS's VoLTE/VoWiFi carrier data (the big one)

LineageOS ships IMS carrier config for Pixels by converting Google's
CarrierSettings protobufs with `lineage/scripts/carriersettings-extractor`
(wired in `tools/extract-utils`, `add_generated_carriersettings`). Same
inputs, reproducible:

```
git clone --filter=blob:none --no-checkout --depth 1 -b main https://github.com/LineageOS/scripts
  (sparse: /carriersettings-extractor/; used at e81615b)
git clone --filter=blob:none --no-checkout --depth 1 -b lineage-24.0 https://github.com/TheMuppets/proprietary_vendor_google_husky
  (sparse: /proprietary/product/etc/CarrierSettings/; used at 7085ddc0, 620 files)
pip install protobuf grpcio-tools; a `protoc` wrapper: exec python3 -m grpc_tools.protoc "$@"
```

Findings (full converter output: 3452 blocks, 11 MB):
- VoLTE true in 1201 entries, Wi-Fi calling 936, static ePDG 565
  (Jio `vowifi.jio.com`, FirstNet, Truphone, Canada, Mexico ...).
- **T-Mobile's ePDG in Google's data is the plain
  `epdg.epc.mnc260.mcc310.pub.3gppnetwork.org`, not our `ss.epdg...`.**
  Prefer Google's; our table (TMO/MPCS) probably should change to it.
- 1100 entries are `pn_xx` (Google's generic defaults): must be skipped,
  or they override AOSP's own carrier configs.
- ImsStack reads exactly these namespaces (ims., imsvoice., imssms.,
  imsss., imswfc., imsemergency.) and implements Ut (`ImsUtImpl`).

`aosp-ims/tools/import-carrier-settings.py` (committed, **never run yet**)
does the filtered import: IMS namespaces + a top-level allowlist, drops
pn_xx, package overrides, provisioning, GBA-required, RCS, opt-out lock,
WFC-on-by-default. Next:
1. Run it → commit `aosp-ims/carrier/lineage-pixel-ims.xml` (check size).
2. Zip/ROM: a new RRO `ImsStackCarrierConfigOverlay` (target
   `com.android.carrierconfig`, `res/xml/vendor.xml`) = joan-common's
   vendor.xml (copy it, keep its CAF header) + imported blocks + our
   blocks **last** (VoLTE/WFC true, toggle visible/editable,
   `carrier_allow_turnoff_ims_bool=true`). Our ePDG blocks go **before**
   the imported ones so Google's addresses win. An RRO replaces the
   whole file, hence the joan-common base. Wire into `build-apk.sh`,
   `pack-zip.sh`, `make-installer.py` (install + uninstall lists),
   `run-e2e-install.sh`, `repack-rom.sh`, `check-rom.sh`. Confirm first
   where the official ROM's CarrierConfig RRO lives (vendor vs product
   overlay) so ours outranks it.
3. Source build: splice the same blocks into joan-common's vendor.xml
   (the patch grows by MBs; consider asking LineageOS reviewers).
4. Extend `check-carrier-config.py` to the new merge order.

IMS APNs: Android 15 adds a default `ims` IPv4v6 profile when a carrier
has none (`DataProfileManager`, "DEFAULT IMS"), so only ~100 of the 1253
IMS APNs Google has and LineageOS lacks matter (custom names: KT
`ims.ktfwing.com`, `ims.alkaa`, Yemen Mobile `ymims`, UK MVNOs with
auth). The zip's APN merge is Viettel-specific shell; generalising it is
real work. For the ROM, `repack-rom.sh` could merge them in Python.

### 3. Every V30 model

The ROM keeps LineageOS's own OTA assert (`v30, joan, h930, h932`), so
it installs wherever the official nightly does (LineageOS recovery
reports `joan`). The zips check no model. Still to do: confirm against
`device/lge/joan` (`TARGET_OTA_ASSERT_DEVICE`, libinit variant detection)
and document the supported models (H930, H930DS, H932, US998, LS998,
VS996, per the LineageOS wiki) in README/RELEASE-NOTES.

## To release alpha1 afterwards

Rebuild locally (`tools/build-apk.sh && tools/build-wfc.sh &&
tools/pack-zip.sh && tools/repack-rom.sh`), run the three checks, then
edit a line of `aosp-ims/RELEASE` (e.g. the title) and push: CI builds
from scratch and publishes `aosp-ims-17.0.0_r1-a15-alpha1`.
