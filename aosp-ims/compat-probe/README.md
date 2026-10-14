# Unchanged IMS on Android 15: external compatibility proof

Author: Aurel Nymvale — Hermes-Agent:openai-codex/gpt-6-astra
Date: 2026-10-13 (America/Los_Angeles)
Generator: `probe.py`, `make_barring_patch.py`, `test_barring_bridge.py`

**Status: one real framework-side bridge proved; full stack build still fails.**
This is NOT a recovery zip, ROM, deployment candidate, or evidence of working
VoLTE/VoWiFi. It deliberately does not use the existing patched backport sources.
The previous adoption/zip work is preserved separately, not retroactively called
an unchanged-stack solution.

## Contract

Lance's target is AOSP 17 IMS on LineageOS 22.2 / Android 15, with compatibility
outside IMS rather than device-specific edits inside the IMS implementation.

- Export exact commits from Git, compare every exported file to its Git blob,
  and compare a complete before/after SHA-256 inventory.
- Keep pure AOSP and the krazey fork distinct. An untouched fork is not identical
  to AOSP; use the fork's existing pre-17 emergency-monitor source selection
  only in the fork lane.
- No IMS Java/native/build file is changed in these snapshots.
- Do not fabricate `android.telephony` methods in compile stubs and report
  success. Android's existing boot-framework classes cannot gain missing
  methods merely from an additional ordinary APK/library.
- A framework-side source backport requires rebuilding the target platform. It
  does not make a zip work on an unchanged stock nightly.

## Inputs

- Pure AOSP ImsStack: `1e3981c23117d75d5ad304b32bb030327ad33eaa`.
- Pure AOSP ImsMedia: `0101cb4caf48dbcc3b8fbc6ab3e41137b90b8e67`.
- krazey ImsStack: `5ff784d265a94b8227f8fcc93bf915f7635283f0`.
- krazey ImsMedia: `cb1dd74e9d5e7b7693dfc5870037607eb58d8ea3`.
- Framework source target: LineageOS frameworks/base
  `5c05c66e872c907a925f633b69f11661c59e7ad0`.
- Compiler classpath: the existing kit's recovered LineageOS 22.2 jars plus API
  35 module libraries. Every consumed jar is hashed in each report. These jars
  came from the published alpha1 repack; this experiment does not independently
  re-prove equivalence to the now-unavailable original signed OTA.
- JDK 21, Android SDK build-tools 36.0.0, API 35 AIDL; existing kit prerequisites.
- Official Google Maven AppCompat 1.7.0 and Core 1.13.1: validate publisher SHA-1,
  archive structure, and record SHA-256. These are compile dependencies, NOT
  proof of the ROM's exact AndroidX dependency closure.
- The kit's annotation stand-ins and default example aconfig flag are explicitly
  inventoried. No missing framework-method implementation is supplied by them.

## Actual findings

The source counts below are compilation units, including generated AIDL/R and
annotation/aconfig stand-ins, not counts of hand-written IMS files.

- Untouched krazey stack, existing pre-17 option: 471 units, **three errors**:
  `TelephonyManager.requestUiccIari`, `TelephonyManager.EXTRA_SETUP_EVENT_LIST`,
  `BarringInfo.getCellIdentity`.
- Untouched AOSP stack: 469 units, **12 errors**. The extra diagnostics concern
  `TelephonyCallback.DomainSelectionEmergencyModeListener`,
  `TelephonyManager.DomainSelectionEmergencyType`, and dependent overrides.
  Twelve diagnostics do NOT mean twelve independent missing APIs.
- The real framework getter backport reduces these to **two** and **11** errors
  respectively. Both stack compilations remain failed, as intended for this
  partial milestone. No claim that these are the complete runtime/native gaps.
- The unchanged ImsMedia framework + service Java sources compile successfully
  in both lanes, independently of the failed stack output. Native media, JNI
  resolution, call audio and RTP have NOT been qualified here.
- Actual Android 15 Soong rejects krazey's unchanged `select()` on Java `srcs`,
  for both library and test modules. The prior patched kit's green Soong result
  does not apply. To keep fork build files unchanged requires build-system
  compatibility work; hiding/changing those files is not this proof's solution.

## Implemented framework bridge

`make_barring_patch.py` verifies exact source/API hashes and emits a two-file
frameworks/base patch:

1. Expose the existing `mCellIdentity` using AOSP's actual getter body.
2. Add the corresponding nullable System API signature to `system-current.txt`.

The newer AOSP flag annotation is not copied because that flag is absent in the
A15 source target. This is an explicit A15 API backport, not a no-op shim.
Existing parcel storage and location-sanitized-copy code remain unchanged.

`test_barring_bridge.py` passes **7 tests**, including:

- exact target identity and refusal of altered inputs/ambiguous anchors;
- `git apply --check`, application, and byte-for-byte generated-file equivalence;
- RED: original framework cannot compile a minimal getter caller;
- actual patched framework-class compile;
- bytecode checks: reads `mCellIdentity`, returns it, no null substitution or call;
- GREEN: **9 host assertions** exercising the actual compiled BarringInfo with a
  clearly isolated host-only CellIdentity fixture. It tests identity, null,
  isolation and preservation of a sanitized value; NOT real LTE redaction,
  Parcel behavior, a full framework build, or an Android runtime.

The initial attempt to run dex2jar's actual CellIdentity on a host JVM hit a
VerifyError (missing stackmap frames); it is retained as a harness limitation.
Java verification was not disabled. The fixture is never on the compiler
classpath used for the unmodified IMS probes and is never a product artifact.

## Remaining producer chains (not stubbed)

- **IARI:** A17 TelephonyManager calls `IPhoneSubInfo.getUiccIari`; the server
  validates subscription/permissions, selects ISIM or USIM records, and obtains
  loaded EF_IARI data. Porting the facade alone cannot implement this. Inspect
  binder compatibility, IccRecords, IsimUiccRecords, SIMRecords, feature flags,
  asynchronous error delivery, and SIM-refresh behavior as one change.
- **STK event list:** adding the extra string fixes syntax, not behavior.
  A17 CatService supplies the actual targeted, STK-permission-protected broadcast.
  Port the producer and event semantics, not just the constant.
- **Pure-AOSP emergency callbacks:** TelephonyCallback + registry/binder delivery
  + Phone notification + EmergencyStateTracker state transitions form the
  contract. An empty listener would conceal absent events. Validate without
  dialing emergency numbers. krazey's documented pre-17 mode is a separate
  reduced-feature fork configuration, not an AOSP backport of this contract.
- **Beyond Java:** unchanged-source Soong/module graph, native ABI/JNI, signing,
  separate media service, overlays, audio policy, radio quirks, IWLAN/QNS, VTI
  behavior on joan's kernel and on-device qualification all remain separate gates.
  The old zip's merged service and old behavior patches cannot be presumed needed
  or sufficient for a platform-signed source build.

## Reproduction

From the repository root, with the kit's SDK/framework prerequisites present:

```sh
python3 aosp-ims/compat-probe/fetch_reference_inputs.py
python3 aosp-ims/compat-probe/test_barring_bridge.py
python3 aosp-ims/compat-probe/make_barring_patch.py \
  --source aosp-ims/work/shim-proof/platform-reference/a15-BarringInfo.java \
  --system-api aosp-ims/work/shim-proof/platform-chain-01/a15-system-current.txt \
  --out aosp-ims/work/shim-proof/framework-new-attempt

python3 aosp-ims/compat-probe/probe.py \
  --variant aosp \
  --stack-repo /path/to/ImsStack-git \
  --media-repo /path/to/ImsMedia-git \
  --attempt aosp-ims/work/shim-proof/aosp-new-attempt \
  --barring-framework-dir aosp-ims/work/shim-proof/framework-new-attempt
```

Use `--variant krazey` for the untouched fork lane. Omit
`--barring-framework-dir` for the unmodified A15-framework baseline.
Each output directory must be new; attempts are never overwritten or removed.
Probe exit 1 means the actual compiler rejected the stack; exit 2 means harness
failure. At this milestone exit 1 is expected, not a hidden success.

`aapt2` performs a **static-library merge only** to generate R classes; final
resource linking is not tested. Generated resource `.apk` files in work are
library intermediates, never installable applications.

## Local evidence

Under `aosp-ims/work/shim-proof/`:

- `krazey-red-02`, `aosp-red-01`: baseline failures.
- `krazey-getter-media-01`, `aosp-getter-media-01`: independent stack/media probes
  with the framework getter; report JSON binds source/classpath hashes and logs.
- `barring-framework-01/frameworks-base-barring-info.patch`: real source patch.
- `bridge-tests-4urmh858`: passing 7-test/9-assertion bridge evidence.
- `platform-reference`, `platform-chain-01`, `platform-chain-02`: reference
  sources, URLs and hashes; failed fetches are explicitly recorded.
- `lineage-review-01`: fresh Gerrit readbacks, not a maintainer promise.

## Upstream acceptance boundary

Live Gerrit readback confirms [502871](https://review.lineageos.org/c/LineageOS/android_device_samsung_s5e9925-common/+/502871)
is merged **for lineage-24.0**, inheriting Generic IMS. That is useful precedent,
not acceptance of this Android-15 backport or of joan support.
[503116](https://review.lineageos.org/c/LineageOS/android_device_samsung_a21s-common/+/503116)
and [505683](https://review.lineageos.org/c/LineageOS/android_hardware_lge/+/505683)
remain NEW. No upstream submission, phone contact, install, reboot, call or
publication was performed for this proof.
