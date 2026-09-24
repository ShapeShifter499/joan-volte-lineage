# Handoff — 2026-09-19 — Digi.Mobil RO registers; the call is next

Signed-off-by: Lance <Gero3977@gmail.com>
Assisted-by: Claude-Code:claude-opus-5
Date: 2026-09-19

## Release state, and where to resume

**Latest is `v0.4.0-alpha75` (vc85).** Also published: alpha67, alpha65,
and the older line back to alpha33. `alpha73` and `alpha74` were
**deleted, tags and all** -- the audio work they carried is in alpha75,
which supersedes both, and the incident below is recorded here rather
than in public release notes. The alpha74 zip is kept off-repo (Lance's
phone and the session scratchpad) because it is the targeted cleanup for
a device that flashed alpha70-73 and does not want a full ROM reflash.

Resume when tester logs arrive. Four things only a handset can answer,
all one paste each: `qpeak` sizes `SLACK`, `media effects agc= aec=`
decides whether to keep attaching them, `media capture record_audio=`
settles the microphone, and `install:` separates a priv-app install from
a `pm install` one.

### CMCC, when that lane retests

Expect to **confirm provisioning, not fix it**, and say so when asking,
so a 404 coming back is not read as another failure. Nothing landed here
targets a 404: the Via fix only touches requests on the retained TCP
client (SUBSCRIBE/INVITE, not REG1/REG2), 423 handling is for a different
response, and Expires and PANI have no path to "user unknown". The
playbook's own outcome table already reads `tpt=tcp` + `reg2=404` as an
HSS/UAR answer.

Two things do make the retest worth doing. That lane last reported on
**alpha38**, and since then the sole-mechanism sec-agree veto was fixed
-- a live regression that could reject a P-CSCF offering exactly one
mechanism, which CMCC does. And the capture did not exist then: the
playbook's "what would settle it" is entirely about reading the real
exchange, which a tester can now send without root.

## Incident: alpha70-73 broke a tester's ROM

> **Correction, 2026-09-24 (alpha76).** The cause below is incomplete,
> and the more important cause was a third one. alpha70+ removed
> `BIND_IMS_SERVICE` from the manifest *and* from the privapp allowlist,
> while PackageManager kept parsing the previously installed build's
> manifest from `/data/system/package_cache` -- the cache is only
> invalidated when the scanned path (for a priv-app, the directory) is
> newer than the cache entry, which a recovery install never made it.
> Any phone upgrading from alpha67 or earlier therefore requested
> `BIND_IMS_SERVICE` through the stale cache with no allowlist entry:
> `IllegalStateException` in `onSystemReady()`, system_server down,
> bootloop. That is why "alpha67 is the last build that flashes cleanly"
> and fresh installs did not reproduce it. alpha76 makes the allowlist
> append-only, re-dates the scanned paths, and keeps removing the boot
> grant. See `docs/install-troubleshooting.md` section 0.


The boot-time permission grant added in alpha70 ran `pm grant` in the
shell domain at `sys.boot_completed`, racing the framework's own
first-boot permission setup. It broke the ROM's permissions device-wide
and the handset did not come back up. alpha73 was the first release
carrying it.

It shipped on the reasoning that a ROM whose SELinux policy refused the
service would never start it and nothing else would change. That was an
argument, not a measurement -- it had never run on any handset -- and the
payoff was saving a tester one tap on a screen that already worked.

- **A dirty flash of the ROM fixes it.** Everything joan wrote is on
  `/system` -- the two files above plus the apk and permission XMLs -- so
  rewriting `/system` removes all of it. `/data` survives a dirty flash,
  so if it boots with permissions still wrong,
  `adb shell pm reset-permissions` restores ROM defaults, and if it does
  not boot at all the damage is in `/data` and a factory reset is the
  answer.
- **alpha74 was the targeted cleanup**, deleting just those two files and
  keeping the joan install. Its release was withdrawn; the zip is kept
  off-repo for exactly this one handset.
- **It was probably not the only cause.** That handset is the InfinityX
  lane, 805 free blocks and 132 free inodes, and the installer wrote the
  apk BEFORE the privapp allowlist. Running out of space between the two
  leaves an apk requesting privileged permissions with no allowlist,
  which under `ro.control_privapp_permissions=enforce` is a fatal boot
  error. That fits "broke the ROM trying to set up permissions" at least
  as well as the init service does, and both are now closed: the
  allowlist is written first, and an aborted install rolls the apk back.
- `adb shell pm reset-permissions` restores ROM defaults if permissions
  are still wrong after boot.
- alpha73 is demoted with a DO NOT FLASH notice; alpha67 has no
  boot-time component at all.
- The source `.rc` is kept, marked DO NOT SHIP, as the record of what was
  tried.

Rule taken from it: nothing that executes at or before boot goes to a
release without running on hardware we hold.

## Where this stopped (2026-09-19 evening)

**Paused on VoLTE pending tester logs.** Three builds are released and the
open questions each need one run on a handset, not more work here.

Released, all with reporting instructions in their notes:
`v0.4.0-alpha65` (vc75), `v0.4.0-alpha67` (vc77), `v0.4.0-alpha73` (vc83,
marked latest). The 65/67/73 split exists because a tester could not
decide whether 65 or 67 sounded better -- 67 fixed one source of
discarded audio and left a second in place, and 73 is the one that
addresses that, which is written into the alpha67 notes so the comparison
is not repeated blind.

What the next logs answer, and nothing else can:

- `qpeak=` in `rx{}` -- sizes `SLACK`, which was raised 3 -> 6 as a
  deliberate middle rather than a second guess. AOSP's equivalent cap is
  150 frames; joan's was 12.
- `media effects agc= aec=` -- decides whether to keep attaching them.
  **Neither reference stack attaches any audio effect**, and on the
  reporting handsets the AGC half never attaches anyway
  (`platform_agc=false`).
- `media capture record_audio=` -- says outright whether the microphone is
  granted, which `media record ok` never did.
- `install:` -- priv-app vs data-app, which decides which permissions the
  package can hold at all and explains why two testers had opposite
  symptoms.

Read `docs/audio-quality-vs-reference-stacks.md` before touching the media
path again. It maps the whole mechanism against AOSP ImsMedia and LG, and
records what was checked and **refuted** as well as what changed -- this
subsystem was debugged four times from four symptoms before anyone read
it end to end.

## The headline

**Digi.Mobil Romania (PLMN 226-05) completes IMS registration.** 200 OK on
the protected REGISTER. It is the first carrier outside T-Mobile to do so
on joan.

The outgoing call then **got stuck**, and that is where the next session
starts. Nothing is known about why: until alpha64 the capture recorded
only the REGISTER exchange, so a call that stalls and a call that was
never placed looked identical.

## How the tester gets builds — the delivery route

Nothing is published. Lance hands zips to testers himself; the job here is
to put the file on his phone.

His phone is a Galaxy Z Fold 5 running Termux with an sshd on port 8022,
reachable as `nym-fold-family` through the OpenWrt gateway:

```
# ~/.ssh/config
Host openwrt-gw
    HostName 172.16.1.1
    User root
    IdentityFile /home/kumo02/.ssh/openwrt_admin_ed25519
    IdentitiesOnly yes

Host nym-fold-family
    HostName 192.168.1.192
    User u0_a500
    Port 8022
    IdentityFile /home/kumo02/.hermes/secrets/ssh/nym-fold-ember-kumo02_ed25519
    IdentitiesOnly yes
    ProxyJump openwrt-gw
```

The Fold is on a different VLAN from this host, hence the ProxyJump; no
firewall change was needed. The key is a **separate** one from the
nym-family key, at Lance's request.

Deliver by streaming over stdin, then verify the md5 **on the phone**
rather than assuming the copy arrived intact:

```
ssh nym-fold-family 'cat > ~/storage/downloads/joan-volte-recovery-0.4.0-alphaNN.zip' \
    < out/joan-volte-recovery.zip
ssh nym-fold-family 'md5sum ~/storage/downloads/joan-volte-recovery-0.4.0-alphaNN.zip'
```

`~/storage/downloads` is Termux's bind to `/sdcard/Download`, which is
where Lance's file manager and chat apps can see it. Two traps worth
knowing:

- **`find /sdcard ...` from Termux returns nothing** even for files that
  are demonstrably there. Search through `~/storage/...` instead. A sweep
  for the tester's fixed zip came back empty for exactly this reason and
  the file was sitting in Termux's own `~/downloads`, which is a
  *different directory* from `~/storage/downloads`.
- `ssh` prints post-quantum/key-exchange warnings that pollute output;
  filter them when parsing.

Getting files *back* from the tester uses the same route in reverse
(`ssh nym-fold-family 'cat ~/downloads/<file>' > local`), which is how the
tester's fixed installer was retrieved.

## Handling tester evidence

A capture is deliberately **not** anonymous: it keeps the IMPI, IMPU,
Call-ID and P-CSCF because that is what a 404 or a 500 is an argument
about, and the file says so in its own first lines. That makes it fine to
read and wrong to republish.

So when a finding is written anywhere durable — a Deck card, a journal, a
report, an issue — the subscriber goes and the **network stays**:

    <IMPI user, Digi RO 226-05 lane A>
    <MSISDN, Digi RO 226-05 lane A>
    <IMEI, Digi RO 226-05 lane A>

The lane is what a reader needs in order to know which carrier a finding
came from and whether it applies to theirs. The person holding the SIM is
not. `lane A` distinguishes two testers on one PLMN without naming either.

Raw captures stay where they land (`~/.hermes/cache/documents/`), unedited
and unpublished. Deck #148 was sanitized this way after the fact; the repo
itself has never carried an identifier, which was checked before the push
rather than assumed.

## Build, gate, ship

```
bash tests/run-host-tests.sh          # 958 checks, everything
bash scripts/pack-zip.sh              # -> out/joan-volte-recovery.zip
~/.hermes/bin/project-profile check   # required before reporting done
```

A pre-commit hook runs the fast check; never skip it. Bump
`versionCode` **and** `versionName` in `ims-service/AndroidManifest.xml`
for every build a tester will flash — PackageManager treats a same-code
replacement as the same package and keeps the cached compiled code, so
the new build silently does not run.

Verify what shipped rather than what was written:

```
unzip -o -q out/joan-volte-recovery.zip app/joan-ims.apk -d /tmp/v
$ANDROID_SDK/build-tools/*/aapt2 dump permissions /tmp/v/app/joan-ims.apk
$ANDROID_SDK/build-tools/*/aapt2 dump badging /tmp/v/app/joan-ims.apk | grep version
```

This caught a real divergence: a malformed XML comment made the build fail
after the source had already changed, so source and zip disagreed.

## What the tester runs

No root anywhere in this loop.

```
adb shell content query --uri content://org.joan.ims.state      # the rows
adb exec-out content read --uri content://org.joan.ims.state/capture > joan-capture.log
adb exec-out content read --uri content://org.joan.ims.state/trace   > joan-trace.log
```

`exec-out` rather than `shell` so a PTY does not rewrite line endings.
The capture normalises anyway and records what was really on the wire per
message (`crlf=`, `lf=`, `cr=`), so a mangled transfer can neither hide a
real line-ending fault nor invent one.

**Testers paste the state rows, not files.** Asked twice for the capture,
this one sent the rows both times. That is a fact about people, not a
failure: the `capture` row now names the file and the exact command to
fetch it, in the output they actually paste.

## The chain that got Digi RO registered

Every step came from the tester's capture, not from reasoning about the
failure.

| Build | Defect | Result |
|---|---|---|
| alpha57 | `Expires: 600000` — 6.9 days, from AOSP's own default in a key named `_SEC_INT` | still 500; candidate ruled out |
| alpha58 | **invented `qop`** on a challenge offering none | **500 → 423** |
| alpha62 | no `423` handling at all | adopt `Min-Expires`, retry |
| alpha63 | — | **200 OK** |

**The qop defect is the one worth remembering.** Digi's 401 carries no
`qop`. joan answered `qop=auth, nc=00000001, cnonce=...`. Per RFC 2617
3.2.2.1 that is not a default, it is a *different digest*:
`MD5(HA1:nonce:nc:cnonce:qop:HA2)` with qop versus RFC 2069's
`MD5(HA1:nonce:HA2)` without. Three separate places turned "none offered"
into `"auth"` — `extractQop`, a second default in `buildRegister`, and an
unconditional header append — so fixing any one alone left the wire
byte-identical. The test that caught the second one compares the **digest**,
not the header.

AOSP's `SipAuHelper.cpp` carries the absence through four places, and
`ImsDigest_CalculateResponse`'s signature documents it outright:
`IN const AString& strQop, // qop-value : "", "auth", "auth-int"`.

T-Mobile offers `qop="auth"`, which is why a working bench never showed
any of this.

## Open, in rough priority order

1. **The stuck call.** alpha64 captures the call leg. Ask for the capture
   *and* the state rows; `last_dial` is the row that matters. Shapes to
   expect: an INVITE with no response at all, a `100 Trying` then silence,
   or a `183`/`180` with no media — they need opposite fixes.
2. **`pani_cell` in the state row.** First real test of whether the
   `etc/default-permissions` pre-grant applies to a package added to
   `/system` after provisioning. Unverified; `no-permission` there means
   it does not, and one `adb shell pm grant` is the fallback.
3. **How `RECORD_AUDIO` is granted is still unestablished.** The entry in
   our `privapp-permissions` does nothing — that file covers
   signature|privileged only, and `RECORD_AUDIO` is dangerous. LG's own
   allowlist for `com.lge.ims` contains no location and no `RECORD_AUDIO`
   either, and *neither reference IMS app records audio at all* (LG has
   `MODIFY_AUDIO_SETTINGS` only; AOSP's media is in
   `packages/modules/ImsMedia` behind `USE_IMSMEDIA`). joan does its own
   RTP so genuinely needs it. One `dumpsys package org.joan.ims` on the
   bench settles it. **A tester with a silent uplink may simply not have
   it.**
4. **Nothing is pushed.** 25 commits ahead of `origin/main`; the GitHub
   release is still **alpha49**. Digi RO registering is a reason to
   revisit that.
5. **The bench is unplugged.** No LG on USB at nym-nest all session, so
   every on-device claim above is from testers, not from hardware here.

## Other lanes, untouched this session

CMCC #146 (REG2 404), NOS #131 (REG1), Viettel #133 (REG2 timeout), China
Unicom #111 (pre-SIP), InfinityX #147 (installer — SOLVED, ENOSPC from
ext4 `reserved_clusters`, fix in alpha54+). The qop fix plausibly matters
to any lane whose network omits `qop`; none has been re-tested.

## Gotchas this session paid for

- **`--` is illegal inside an XML comment.** It broke the build once, and
  the same text had gone into `etc/default-permissions`, which the
  platform parses at boot and **ignores silently** when malformed. The
  host gate now parses every shipped XML.
- **A requested `signature|privileged` permission with no
  `privapp-permissions` entry is a fatal boot error**, not a denial, under
  `ro.control_privapp_permissions=enforce`. The gate now enforces
  manifest ⊆ allowlist, and was positive-controlled by deleting the entry
  and confirming it fails.
- **PackageManager's record lags a system-app replacement.** The
  User-Agent read it directly and told a carrier the handset was alpha49
  while running alpha55. Use `JoanTrace.readVersionName`, which parses the
  apk on disk.
- The LG ROM is still extractable when a question needs it:
  `debugfs -R "dump /product/priv-app/Ims6/Ims6.apk out.apk" 0.system.img`
  against `~/joan-fp-compare/kdz/kdzout/0.system.img`.
