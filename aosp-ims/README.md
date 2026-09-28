# AOSP IMS for joan on LineageOS 22.2 (Android 17's IMS stack, backported)

VoLTE and Wi-Fi calling for the LG V30 (`joan`) on LineageOS 22.2, using
AOSP's own IMS stack from `android-17.0.0_r1` backported to Android 15
QPR2. It replaces joan's own `ImsService`.

| Part | Package | Does |
|---|---|---|
| ImsStack | `com.android.imsstack` | IMS registration, SIP, IPsec, calls, SMS |
| ImsMedia | `com.android.telephony.imsmedia` | RTP, jitter buffer, codecs, call audio |
| IWLAN | `com.google.android.iwlan` | The IPsec tunnel to the carrier's ePDG, for Wi-Fi calling |
| QNS | `com.android.telephony.qns` | Moves IMS between LTE and Wi-Fi |

Qualcomm's IMS is not an option on the V30: its modem was built without
Qualcomm's IMS core (`docs/v30-modem-and-qualcomm-ims-2026-09-27.md`).
AOSP's stack runs on the application processor and needs the modem only
for the LTE bearer and SIM authentication, which the V30 has.

**Phones.** Every V30 that LineageOS 22.2 supports, all on the one `joan`
build (per the LineageOS wiki): H930, H930DS (dual SIM), US998, H932
(T-Mobile), H931, H933, LS998, V300K, V300L, V300S and VS996. The zips
check no model, and the ROM keeps the official nightly's device check,
so both install wherever the official nightly does. Tested so far: a
US998 on T-Mobile. Some carriers allow VoLTE only on phone models they
have certified; that is decided by the network, not the phone.

> **Alpha.** Tested on one phone and carrier, a US998 on T-Mobile US:
> VoLTE registers with IPsec, outgoing calls work with audio both ways,
> incoming calls ring and answer, and Wi-Fi calling registers over the
> carrier's tunnel and carries calls with audio both ways. Carriers whose
> IMS runs over IPv4 (Digi Mobil Romania among them) get past the first
> REGISTER only from ImsStack 0014 on; not yet tested there. Hanging up
> an outgoing call before it rang could leave it stuck "disconnecting"
> until ImsStack 0015 (not yet re-tested on the bench). SMS over IMS,
> video calling, RTT, Ut/XCAP and emergency calls over IMS are built in
> but untested. Keep a way back: the `-uninstall` zip, or the official
> nightly. Something wrong: [`HOW-TO-LOG.md`](HOW-TO-LOG.md).

What the stack does, all of it upstream AOSP code: VoLTE (voice over
LTE, HD voice codecs), Wi-Fi calling (VoWiFi over IWLAN, with QNS moving
calls between LTE and Wi-Fi), SMS over IMS, video calling (ViLTE, with
the camera), RTT (real-time text), emergency calls over IMS, supplementary
services over Ut/XCAP (call forwarding, waiting, barring), conference
calls, USAT call control and MO SMS control by the SIM, and RCS presence
(UCE). Each works where the carrier offers it; see below for how the
carrier's settings are chosen.

## Three ways to get it

| | For | What |
|---|---|---|
| **ROM** | Anyone flashing a ROM anyway | `lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip`: the official 2026-09-20 nightly with the stack built in |
| **Flashable zips** | A phone already on LineageOS 22.2 or a ROM based on it | `aosp-ims-17.0.0_r1-a15-alpha1-{fresh,migrate-from-joan,uninstall}.zip` |
| **Source build** | ROM builders, and LineageOS itself | [`upstream/AOSP-IMS.md`](../upstream/AOSP-IMS.md): a local manifest, the patches, and a `device/lge/joan-common` patch |

The ROM and zips are on the
[`aosp-ims-17.0.0_r1-a15-alpha1`](https://github.com/ShapeShifter499/joan-volte-lineage/releases/tag/aosp-ims-17.0.0_r1-a15-alpha1)
prerelease, built from this directory by `.github/workflows/aosp-ims.yml`.
`aosp-ims/RELEASE-NOTES.md` is its description: which file to flash,
permissions, known limits, and what logs to send.

### Which zip

Flash with **LineageOS recovery** (it maps the dynamic partitions; TWRP
on this device generally does not).

- **`-fresh`**: a phone that never had joan's IMS zip. It refuses a phone
  that has joan, and changes nothing.
- **`-migrate-from-joan`**: a phone running joan's IMS (alpha67 or any
  earlier one). It removes joan's app, overlays, permission files and
  stamps, then installs this stack.
- **`-uninstall`**: removes everything either zip, or the ROM above,
  added. The ROM's own files are left as the nightly shipped them.

Both install zips also set one line of `/system/build.prop`:
`ro.telephony.block_binder_thread_on_incoming_calls=true`. joan's tree
ships it false for the Qualcomm modem IMS, and with it false LineageOS's
telephony hands an IMS stack no incoming call. Only that line changes;
the zip leaves a marker (`/system/etc/aosp-ims-incoming-calls.flipped`)
and the `-uninstall` zip sets the line back to false. A ROM without the
line is left alone. The repacked ROM ships it true.

These use joan's installer v8, the one that flashes across
LineageOS-based ROMs (alpha67): it checks free space before writing,
copies atomically, writes the permission allowlists before the APKs, and
invalidates the package manager's cache so the next boot rescans.

### LineageOS updates

The zips and the repacked ROM install `/system/addon.d/60-aosp-ims.sh`.
A LineageOS update runs it through its backuptool before and after
rewriting system and product. The script puts the stack back, then redoes
the build.prop incoming-call flip and the Viettel APN merge on the
update's own files. Afterwards the phone is as if the zip had been
flashed onto the update, so there is no need to re-flash after each
nightly. The uninstall zip removes the script. `tests/run-e2e-install.sh` runs the
joan OTA's own backuptool around a simulated update, and CI checks that
copy against the pinned OTA.

### Permissions (zips and repacked ROM)

The apps are not signed with the ROM's platform key (only LineageOS has
it), so:

- **Calls** need ImsStack's runtime permissions, the microphone above
  all: ImsMedia, in ImsStack's package, records the call. The
  default-permissions file grants them on the first boot after a ROM
  install or update, so the repacked ROM (an update even over its own
  nightly: see [The ROM](#the-rom)), and a zip flashed in the same
  recovery session as a ROM update, need nothing more. A zip flashed
  onto a ROM that has already booted gets them from **Calling
  permissions** in the app drawer. ImsStack puts it there while the
  microphone is missing (`zip/java/.../CallPermissionsActivity.java`); it
  asks with Android's own dialogs for the microphone, camera (video
  calls), location (emergency calls) and phone, and leaves the drawer
  once the microphone is allowed. No reboot needed. The same permissions
  are under Settings > Apps > ImsStack > Permissions (show system apps).
- **Wi-Fi calling** needs one step over adb, however it was installed:
  IWLAN's tunnel needs the `MANAGE_IPSEC_TUNNELS` app-op, which has no
  setting on the phone. With USB debugging on, once:

  ```
  sh scripts/grant-permissions.sh   # in the zip, and at aosp-ims/zip/grant-permissions.sh
  scripts\grant-permissions.bat     # Windows equivalent (needs adb on PATH)
  # on the phone itself: aosp-ims/zip/grant-on-device.sh (see header)
  ```

  It runs `appops set com.google.android.iwlan MANAGE_IPSEC_TUNNELS
  allow`, and `pm grant` for everything above plus IWLAN's phone and
  location and QNS's phone state. Reboot afterwards.

A source build needs none of this.

## When something fails

[`HOW-TO-LOG.md`](HOW-TO-LOG.md) is the tester's guide: bigger log
buffers first, what to reproduce, the seven adb commands to run
afterwards (two more for Wi-Fi calling), what to say, and how to send
the logs privately (they carry the IMSI, phone number and IMEI). It
ships with each release. The first two files are the ones that matter
most: `adb logcat -b all -d` and `adb shell dumpsys activity service
com.android.imsstack/.imsservice.ImsService`, the stack's own recent
registration history.

## What it does per carrier

LineageOS ships IMS carrier config for Pixels, not for joan. This stack
brings it along, for every SIM:

- **VoLTE**: offered for every carrier, with the toggle visible, editable
  and able to turn IMS off (Settings > Network & internet > SIMs > VoLTE).
- **Wi-Fi calling**: offered for every carrier too. It works where the
  carrier's ePDG accepts the SIM; elsewhere the tunnel is not built and
  IMS stays on LTE.
- **Per-carrier IMS settings** from LineageOS itself: the IMS part of the
  Pixel carrier settings LineageOS converts for its Pixel builds
  (`lineage/scripts/carriersettings-extractor`), 1361 blocks for 576
  carriers: SIP and registration timers, SMS over IMS, Ut/XCAP, emergency,
  video and RTT, ePDG and QNS settings. Imported by
  `tools/import-carrier-settings.py` into `carrier/lineage-pixel-ims.xml`
  (sources pinned in `upstream.lock`), filtered to the keys the AOSP stack
  reads; never the keys that would pick another ImsService, require
  provisioning, lock the VoLTE toggle or turn Wi-Fi calling on by default,
  and never EVS (ImsMedia has no EVS codec; ImsStack 0007 also drops it
  from any other config).
- **What AOSP 17's own CarrierConfig changed**: Android 17's
  CarrierConfig app ships newer carrier assets than LineageOS 22.2's
  (Android 15's). The IMS keys they set differently are carried over as
  a layer under the Pixel data (`tools/import-aosp-carrierconfig.py`,
  `carrier/aosp17-carrierconfig-ims.xml`, regenerated and compared in CI
  by `tools/regen-aosp-carrierconfig.sh`): 11 carriers, among them TIM
  (Ut, no IPsec), ALIV (USSD over IMS, conference factory), netplus.ch
  (Ut and BSF servers), Brisanet, OXIO, Madar and Pivotel, and Verizon's
  and Xfinity's hold in IMS calls where the Pixel data does not decide
  it. Keyed by carrier id and PLMN as Android 15's carrier database
  assigns them (by PLMN for carriers it does not know yet).
- **LG's own settings where the Pixel data has nothing**: of the 82
  PLMNs joan's LG profiles cover and the Pixel data has no block for,
  the 38 where LG's V30 settings differ from ImsStack's defaults get
  what they say about IPsec (off), USSD (over IMS) and the conference
  factory: MTS, MegaFon, Beeline and Tele2 in Russia, CSL and PCCW in
  Hong Kong, Vodacom and Cell C in South Africa, Verizon's, AT&T's and
  Canadian carriers' secondary PLMNs, among others
  (`tools/import-lg-ims.py`, `carrier/lg-ims.xml`). Only those three:
  each is checked, every time the file is made, against the networks
  both data sets cover (LG's IPsec off matches the Pixel data 30 of 34
  times, its USSD over IMS 88 of 95, conference factories 71 of 75), and
  conference URIs copied from another country's network in LG's data
  are left out.
- **ePDG**: the carrier's own address from that data where it has one;
  for T-Mobile, MetroPCS, AT&T and Verizon otherwise, whose ePDGs are not
  at the 3GPP default name, joan's table. Everyone else uses the default
  name derived from the SIM.
- **IMS, XCAP and emergency APNs** from the same Pixel data
  (`carrier/lineage-pixel-apns.xml`, the converter's APN list, imported
  by `tools/import-carrier-apns.py`). LineageOS's own list has IMS APNs
  for about 200 networks, the Pixel data for about 1400. Android 15 makes
  up an IMS APN named `ims` and an emergency APN named `sos` by itself,
  so what was missing is the carriers whose APNs are named otherwise
  (Verizon's MVNOs, among others) and every XCAP APN, which Ut needs. An
  APN is added only for a type the SIM has none of, and only at the level
  its APNs already come from (Android picks a SIM's MVNO rows over its
  MCC/MNC rows, so a row at the wrong level would hide its internet APN).
  IMS and emergency APNs allow IWLAN, for Wi-Fi calling.

Where it lives: the zip and the ROM apply it at run time, on top of
whatever carrier config the ROM ships
(`zip/java/com/android/imsstack/joan/CarrierImsGate.java`, ported from
joan's gate, with the imported data as one asset per PLMN), so a
LineageOS-based ROM keeps its own. A source build carries the same as
CarrierConfig `vendor.xml` blocks (`tools/make-carrier-config.py`,
spliced in by `upstream/aosp-ims/apply-patches.sh`).
`tests/check-carrier-config.py` checks the result for all 2866 SIM
identities Android's carrier database knows, and that LG's blocks only
fill PLMNs the Pixel data lacks.

The APNs likewise: the zip adds them on the phone
(`zip/java/.../ImsApnGate.java`, deciding with `ApnPlan.java` against the
APNs Android actually gives the SIM), as rows keyed by the SIM's carrier
id, which Android appends to the SIM's other rows; it takes them back
once the ROM has its own. A source build gets them as
`vendor/apn/aosp-ims.xml` (`tools/make-apns.py`, from `apply-patches.sh`),
939 rows against LineageOS's list at the commit pinned in
`upstream.lock`. `tests/check-apns.py` checks that list for 2714 SIM
identities: no SIM's APNs change level or lose a row, and the list still
validates against LineageOS's schema.

## Status (2026-09-28)

| Step | State |
|---|---|
| ImsStack + ImsMedia Java, against LineageOS 22.2's own framework | done: 15 ImsStack patches, 6 ImsMedia |
| LineageOS 22.2's own changes on the IMS path | reviewed, 62 files and 3 properties; `tests/check-lineage-forks.py` (in CI) |
| `libimsstack.so`, `libimsmedia.so`, linked against the ROM's libraries | done |
| IWLAN + QNS (Android 17) for the zip | done: 3 IWLAN patches, 1 QNS |
| Single-APK packaging, overlays, permission files | done |
| Flashable zips (fresh, migrate, uninstall) | done; end-to-end installer tests pass, LineageOS updates (addon.d) included |
| Repacked LineageOS 22.2 ROM | done; `tests/check-rom.sh` passes |
| Source-tree integration (`upstream/AOSP-IMS.md`) | written and checked piece by piece, its build files by Android 15's own Soong (`tests/check-soong.sh`, in CI); not yet built in a tree |
| **Tested on a phone** | US998 on T-Mobile US: VoLTE registration (IPsec), outgoing calls with two-way audio, incoming calls ring and answer, Wi-Fi calling registration and a Wi-Fi call with two-way audio. Nothing else yet |

### Known problems

- **Leaving Wi-Fi calling.** Switching Wi-Fi calling off while IMS is on
  Wi-Fi makes the framework hand the IMS connection over to LTE. joan's
  modem refuses the handover (`SETUP_DATA_CALL` with reason HANDOVER
  fails with `ERROR_UNSPECIFIED`) and, in the one bench capture, detached
  from LTE 50 ms later. IMS comes back on LTE by a fresh connection, which
  took 13 s to fail once (`IPV6_PREFIX_UNAVAILABLE`) before the retry. A
  call dialled in that gap goes over 3G/2G and keeps the modem off LTE
  until it ends: 85 s in all on the bench. A Wi-Fi call can't move to LTE
  either. Candidate fix: an IWLAN-to-cellular handover policy of
  `disallowed` for IMS, so the framework sets up on LTE directly without
  asking the modem for a handover. Not tested yet.
- **Crash loops.** ImsStack is a persistent system app: the low-memory
  killer never picks it (oom_score_adj -800, like the phone process), and
  Android restarts it at once if it crashes. A call in progress ends, and
  IMS registers again. IWLAN, QNS and ImsMedia run at the visible-app
  priority (100) and are rebound by their clients after a kill. But if
  ImsStack crashes 5 times within a minute, Android's RescueParty steps in
  and escalates, up to a reboot and a "factory reset?" prompt (it is
  disabled only while USB is connected on a userdebug build). The stack
  has no crash-loop brake of its own yet.

## What the backport changes

Every patch is in `patches/<project>/`, applied on top of the commit
pinned in `upstream.lock`.

| Patch | What |
|---|---|
| ImsStack 0001 | The four Android 16/17 APIs ImsStack uses, on Android 15: `requestUiccIari` (no IARIs, RCS only), `BarringInfo#getCellIdentity` (read back from the parcel), `EXTRA_SETUP_EVENT_LIST` (local constant), `DomainSelectionEmergencyModeListener` (not registered) |
| ImsStack 0002 | Debug menus without androidx.appcompat |
| ImsStack 0003 | Hands call audio to Android (`AUDIO_HANDLER_ANDROID`), so Telecom puts the call in `MODE_IN_COMMUNICATION`, the mode ImsMedia's audio path needs on this HAL |
| ImsStack 0004 | Emergency call tracking without `READ_ACTIVE_EMERGENCY_SESSION` (signature-only). Without the platform key the listener is refused, which crash-looped the stack on a US998; ImsStack now falls back to the call state plus `TelecomManager#isInEmergencyCall`. Platform-signed builds keep the original listener |
| ImsStack 0005 | USAT call control with no answer from the SIM: joan's RIL completes the CALL CONTROL envelope with status words 00 00 and no data, which ImsStack took as a refusal, failing every MO call on a SIM with call control by USIM (T-Mobile's). With no answer the call is set up as dialled; a real answer from the SIM still counts |
| ImsStack 0006 | The location information in those envelopes codes a three-digit MNC as 3GPP TS 24.008 says (310-260 as `13 00 62`, not `13 20 06`) |
| ImsStack 0007 (EVS) | Never offers EVS: ImsMedia's EVS encoder and decoder are TODOs, so an EVS call would carry no audio |
| ImsStack 0007 (teardown) | `MediaManagerHelper.close()` is synchronized: when the media service died mid-call, two callers raced its teardown into a NullPointerException |
| ImsStack 0008 (GBA) | ImsStack is also the device's GBA service (`ImsStackGbaService`). Ut/XCAP authenticates with GBA and neither AOSP nor LineageOS ships a GBA service, so XCAP servers that ask for it refused call forwarding, waiting and barring settings |
| ImsStack 0008 (logging) | Logs why the framework refused an incoming call before the stack answers 480 |
| ImsStack 0009 | Swaps an inverted codec bitrate range instead of crashing call setup (seen while media restarted after a call) |
| ImsStack 0010 | Binds every socket to the IMS network, by capability when the data-connection registry has no entry: unbound SIP over Wi-Fi calling went to the home router |
| ImsStack 0011 | Reports the WLAN registration's RAT (IWLAN) when IMS runs over Wi-Fi |
| ImsStack 0012 | Resolves the network to bind by live capability, not a cached one |
| ImsStack 0013 | Reports IWLAN only while the IMS APN itself runs over WLAN. 0011 did so whenever Wi-Fi was up, and every LTE registration then failed within seconds |
| ImsStack 0014 | Binds a connecting socket before `connect()` and keeps `errno`. 0010's bind after `connect()` left `errno` at ENOTCONN, so every IPv4 TCP connection failed before anything was sent: carriers with IPv4 IMS and a REGISTER over the TCP threshold (Digi Mobil Romania) never registered |
| ImsStack 0015 | Ends an outgoing call as terminated, not start-failed, once Telephony has seen it initiate. Without domain selection, LineageOS acts on a start failure only while its pending MO is set, which the first 100 Trying clears: a call hung up or refused before the first 18x stayed in Telecom's DISCONNECTING for good |
| ImsMedia 0001 | `ImsMediaManager` binds the ImsMedia service in its own package when that package has one (the zip's single APK) |
| ImsMedia 0002 | Declares `JNIImsMediaService.setTestMode`, which the pinned ImsMedia's native JNI table registers and its Java class never declared: the media service died at the first call's media |
| ImsMedia 0003 | Defers the native open when a session opens with no RTP config yet (incoming calls open at ring time), and runs it with the first modify |
| ImsMedia 0004 | Logs the socket monitor and receive path |
| ImsMedia 0005 | Sets thread priority through the kernel: the `scheduling_policy` service lookup is SELinux-denied for a privileged app and blocked the socket monitor forever, so no downlink RTP was read |
| ImsMedia 0006 | Drops media-quality thresholds that arrive with no native session (crashed the media service at call teardown) |
| Iwlan 0001 | No physical-network reporting in `DataCallResponse` (Android 16 API) |
| Iwlan 0002 | Doesn't propose AES_XCBC_96 (Android 15 can't build it) or crash on an unsupported algorithm |
| Iwlan 0003 | Creates the tunnel interface with its real endpoints: on joan's 4.4 kernel it is a VTI, which dropped every packet addressed with IWLAN's placeholder endpoints |
| QNS 0001 | Wi-Fi calling activation without androidx: the activity is a plain `Activity`, and carrier portals open in the browser |

## How the zip installs without the platform key

- **One APK.** Upstream ImsMedia runs as `android.uid.phone`, which only
  the platform key can claim. The zip folds the ImsMedia service into the
  ImsStack APK instead (`tools/merge-manifest.py`): the same app and uid,
  with the service in its own process as upstream.
  `zip/java/.../ImsStackZipApp.java` keeps the media process from
  starting the IMS stack.
- **Hidden APIs.** `android:usesNonSdkApi="true"` on a system app
  (`ApplicationInfo.isAllowedToUseHiddenApis()`).
- **Platform libraries.** A bundled system app gets the shared linker
  namespace, so the JNI libraries link `libbinder` and friends from
  `/system/lib64`. They are stored page-aligned and uncompressed, and
  load straight from the APK.
- **Privileged permissions.** Allowlists in `permissions/`;
  `tools/check-privapp.py` fails the build if the APK requests a
  privileged permission the allowlist does not cover (the ROM would not
  boot).
- **Selecting the service.** Two overlays (`zip/rro-phone`,
  `zip/rro-fw`): ImsStack as `config_ims_mmtel_package`; VoLTE and Wi-Fi
  calling available on the device; IWLAN and QNS as the WLAN data,
  network and qualified-networks services.
- **What it cannot have.** `ACCESS_SURFACE_FLINGER` and
  `INTERACT_ACROSS_USERS_FULL` are signature-only, but nothing uses
  them: ImsMedia draws video into the surfaces the dialer hands it
  (`ANativeWindow`, no SurfaceFlinger calls), and neither app makes a
  cross-user call. `MANAGE_IPSEC_TUNNELS` comes from the adb app-op
  instead.

## How it builds

No Android tree is needed (a LineageOS checkout does not fit here).

- **Native:** `tools/bp2ninja.py` reads the upstream `Android.bp` files
  and writes a ninja build that does what Soong would. Android 15 QPR2
  headers (sparse checkouts), the platform libc++ headers of
  `clang-r536225` (the clang Soong used for that branch), the NDK's
  compiler, and linking against the ROM's own `/system/lib64` libraries:
  the result has the ABI of the ROM it runs on.
- **Java:** compiled against the ROM's real `framework.jar`,
  `telephony-common.jar`, `ims-common.jar` and friends, converted with
  dex2jar from the pinned nightly. javac proves every framework member
  referenced exists on the phone.

```sh
tools/build-all.sh               # all of the below, in order
tools/setup-workdir.sh           # sources + patches, headers, ROM, deps
tools/build-java.sh              # AIDL + a compile check
tools/build-native.sh            # libimsstack.so, libimsmedia.so
tools/build-apk.sh               # ImsStack.apk + overlays (wipes out/apk)
tools/build-wfc.sh               # Iwlan.apk, QualifiedNetworksService.apk
tools/pack-zip.sh                # the three zips
sudo tests/run-e2e-install.sh    # installer end-to-end tests
sudo tools/repack-rom.sh         # the ROM
tests/check-rom.sh <rom.zip> work/out/apk
```

Inputs are pinned in `upstream.lock`. `work/` is git-ignored and takes
about 4 GB, most of it the OTA and its system image; repacking the ROM
needs about 5 GB more while it runs. `VERSION_NAME` sets the version
(`17.0.0_r1-a15-alpha1` by default); `ROM_TAG` the ROM's version suffix.
APKs are signed with a key generated in the work dir, never committed: a
published key would let anyone ship an "update" to a privileged app.

**Versions**, so this is never mistaken for joan or an official build:
the app is `17.0.0_r1-a15-alpha1`; the zips are
`aosp-ims-17.0.0_r1-a15-alpha1-*`; the ROM reports
`22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan` and release type
`UNOFFICIAL`, so the updater does not offer an official nightly over it.

### The ROM

`tools/repack-rom.sh` is not a source build. It unpacks the official
block OTA pinned in `upstream.lock`, adds the zip's files to the system
and product images (root-owned, `system_file` labels), marks the version
UNOFFICIAL, and writes a block OTA recovery flashes the same way. The
images are written whole, at exactly the partition sizes the OTA's
dynamic-partition ops declare. Only LineageOS can sign with its key, so
recovery warns that verification failed and asks before installing.

It also gives the ROM its own build number: `ro.build.version.incremental`
becomes the nightly's plus `.aospims.` and a hash of what the repack adds
(Settings > About shows it). On joan, LineageOS tells a system update by
that number alone, because every partition's fingerprint is LG's stock
one in every build. PackageManager's upgrade scan and package cache, the
default permission grants and TeleService's carrier config cache all key
on it. So flashing the ROM over the nightly it came from, without a
wipe, is still an update: ImsStack gets its permissions at first boot,
and the package cache can't keep an older IMS zip's manifest.

### LineageOS's own changes

LineageOS builds most of Android 15 from AOSP's `android-15.0.0_r32` and
forks a few projects. `tests/check-lineage-forks.py` (in CI) diffs the
forks on the IMS path against that tag at the commits `upstream.lock`
pins: frameworks/base's telephony, location, permission,
package-manager, audio and network-policy code, frameworks/opt/telephony,
TeleService, TelephonyProvider and IWLAN. Every file that differs must be
in `tests/lineage-forks.txt` with the reviewed diff's hash and what it
means for this stack, and so must every system property LineageOS's added
code reads. It also checks that CarrierConfig, `frameworks/opt/net/ims`,
ImsMedia and the Telephony module are still AOSP's in LineageOS's manifest.
The review found three properties that matter:

- `ro.telephony.block_binder_thread_on_incoming_calls`, set true above;
- `ro.build.version.incremental`, handled as above;
- `ro.telephony.handle_audio_direction_changes_between_call_state_changes`,
  which joan leaves at AOSP's behaviour.

`--heads` shows what LineageOS has changed since the review.

### Releasing

Edit `RELEASE` (tag, version, ROM suffix, title) and push. The
`aosp-ims` workflow builds everything from source on a clean runner, runs
the carrier-config, installer and ROM checks, and publishes a prerelease
under the tag if it does not exist yet. Any other push to `aosp-ims/`
runs the same build as a check.
