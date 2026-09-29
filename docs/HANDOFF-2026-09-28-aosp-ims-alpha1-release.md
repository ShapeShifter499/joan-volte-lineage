# Handoff — 2026-09-28 — AOSP IMS alpha1: local release, bench 10/11, open issues

Branch `claude/aosp-ims-a15-backport` (pushed). Replaces
`HANDOFF-2026-09-27c`. Written-by: Ember (Claude-Code:claude-opus-5-5).

## Release process changed: GitHub no longer builds

The owner asked for no GitHub builds. The `aosp-ims` workflow is
**disabled** (`gh workflow disable aosp-ims.yml`, state
`disabled_manually`); every Actions run was deleted. Releases are built
here and uploaded with `gh release create`. The workflow file stays in
the repo as a record of the checks; re-enable only if the owner asks.

Local release recipe (what CI did), from `aosp-ims/`:

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=$JAVA_HOME/bin:$PATH   # default javac is 17: fails
export VERSION_NAME=$(sed -n 2p RELEASE)
tools/build-java.sh && tools/build-native.sh && tools/build-apk.sh && tools/build-wfc.sh && tools/pack-zip.sh
#   build-apk.sh wipes out/apk: always run build-wfc.sh after it
sudo env PATH=$PATH E2E_OTA=work/rom/<ota zip> tests/run-e2e-install.sh
sudo env PATH=$PATH WORK=$PWD/work ROM_TAG=$(sed -n 3p RELEASE) tools/repack-rom.sh
TMPDIR=work tests/check-rom.sh work/out/rom/*.zip work/out/apk
```

Run `tests/run-e2e-install.sh` from the repo root. On this host it needed
two harness fixes (`29cec31`: no toybox; Docker's long mount lines stop
busybox's mount listing). The device patch had gone stale against the
generator (`9ba50c0`), which only CI's carrier-config step would have caught.

`tests/check-soong.sh` / `check-tree-compile.sh` need `work/soong15`,
which this machine does not have (they ran only in CI).

## State

- **alpha1 published** 2026-09-28: prerelease
  `aosp-ims-17.0.0_r1-a15-alpha1`, tag on `29cec31`, built locally (same
  code as bench 11). Assets: the ROM (sha256 `c716012f…`), `-fresh`
  (`bbda824d…`), `-migrate-from-joan` (`5f559ced…`), `-uninstall`, the
  three grant scripts, `HOW-TO-LOG.md`, `SHA256SUMS`. Checked before
  upload: 261/261 installer e2e checks (mksh and busybox sh),
  `check-rom.sh` all passed, carrier-config check OK (2866 SIM identities),
  every patch series == its work tree. Not run: Soong/tree-compile checks
  (no `work/soong15` here), lineage-forks (network).
- **ImsStack 0014**: IPv4 TCP connects all failed (errno clobbered by
  0010's post-`connect()` bind). Found in the Digi Mobil RO tester log
  (Nextcloud `Research/LG_v30_VoLTE/tester_logs/Digi_Mobil_Romania/09-28-2026`).
  Our bug, not upstream's. Untested on Digi.
- **ImsStack 0015**: hang-up of an outgoing call between 100 Trying and
  the first 18x stalled in DISCONNECTING (framework drops start-failed
  after onCallInitiating without domain selection). Diagnosed live on the
  bench; fix untested on the phone.
- Tester zips: Nextcloud `Research/LG_v30_VoLTE/test_zips/<version>/`
  (bench3, 8, 9, 10, 11 each in their own folder). The owner put an extra
  `…-bench10.zip` (= `-fresh`) in bench10's folder.
- Bench logs (IMSI inside, git-ignored): `aosp-ims/work/bench-logs/`
  `digi-ro-2026-09-28`, `us998-2026-09-28-wfc-off`,
  `us998-2026-09-28-hangup-stall`. Copies of the last capture are also in
  `/tmp/hs-*.txt` on nest (not deleted: ask before removing).

## Open issues, in priority order

1. **Bench 11 / alpha1 on the phone**: dial and hang up before it rings,
   LTE and Wi-Fi; confirm the call screen closes.
2. **Wi-Fi calling off / leaving Wi-Fi**: the modem refuses
   `SETUP_DATA_CALL reason=HANDOVER` (IWLAN->EUTRAN, `ERROR_UNSPECIFIED`)
   and detached from LTE 50 ms later (one capture); fresh LTE IMS PDN
   then failed once with `IPV6_PREFIX_UNAVAILABLE` after 13 s; calls in
   the gap go CSFB (`INVALID_EMM_STATE`): 85 s. A Wi-Fi call can't move to
   LTE. Next: bench test Wi-Fi call + Wi-Fi off; candidate fix an IWLAN
   handover policy `disallowed` for IMS (carrier config via
   `CarrierImsGate`) so the framework sets up on LTE without the handover.
3. **Crash-loop brake**: ImsStack is persistent (adj -800); 5 crashes in
   1 min trigger RescueParty (Android 15 PackageWatchdog: count 5,
   window 1 min) up to warm reboot / factory-reset prompt; disabled only
   with USB on userdebug. Add a brake: after N quick restarts, don't start
   IMS.
4. **Tester lanes** (Viettel, NOS, CMCC, CU, Digi): all were on joan;
   bench 11/alpha1 `-migrate-from-joan` is the thing to send them.
5. ViLTE never tested (T-Mobile enables it; Digi's config doesn't).
6. Tree build: the tree's own IWLAN lacks IWLAN 0003's VTI fix.
