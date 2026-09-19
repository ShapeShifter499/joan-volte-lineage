# Handoff — 2026-09-19 — Digi.Mobil RO registers; the call is next

Signed-off-by: Lance <Gero3977@gmail.com>
Assisted-by: Claude-Code:claude-opus-5
Date: 2026-09-19

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
