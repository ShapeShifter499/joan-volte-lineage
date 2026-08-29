# Ember → Aurel: joan LineageOS 22.2 VoLTE (2026-08-29)

**Both directions now work.** Outgoing and incoming calls, two-way audio,
caller ID, clean hangup either side. The ~32s teardown you banked is solved
and the cause was not any of the three things it looked like.

**Immediate next actions, in order:**

1. **Nothing is pushed.** 18 commits on `main`, including your 4. No GitHub
   release cut. Lance wants the zip published once he is happy; the release
   notes must state plainly that the unauthenticated `127.0.0.1:15090`
   listener ships and any local app can drive the UA.
2. **The IpSecManager migration** is the next real work, and the recipe at
   the end of this file has everything needed to start. It deletes the
   daemon, the listener and the sepolicy together.
3. Do **not** auto-dial. Lance places calls.

---


- **Harness:model:** Claude-Code:claude-opus-5
- **Repo:** `~/vibe-coding-projects/coding/joan-volte-lineage`
- **Branch:** `main`, **16 commits ahead of origin, not pushed**
- **Device:** joan on nest (`ssh nym-nest-family`, `sudo adb -s LGUS9986e606d55`)

Read this before touching anything. Do **not** auto-dial; Lance places calls.

---

## Headline: calls work in both directions

Measured on device against a Google Voice number, on LineageOS 22.2.

**Outgoing.** 142-second call, `sent=7100 recv=7100`, two-way audio, no 200
retransmissions, clean hangup from either side.

**Incoming.** The dialer rings, the user answers or declines, two-way audio,
and hangup from our side is answered `200 OK`. Caller ID number reaches the
dialer (`number=yes`); Google Voice supplies no display name, so the name
path is written and tested but has not been seen populated on air.

---

## What was wrong, and what it actually was

### The ~32s teardown was a truncated To-tag

Every outgoing call died at ~32s with the far end retransmitting its 200 OK
every 4s, inbound RTP freezing at ~16s, and our own BYE drawing 481.

One cause. `extract_to_tag()` was called with a 64-byte buffer whose loop
guard stops at 63 characters; Google Voice via T-Mobile returns an
**81-character To-tag**. Every in-dialog request was then rebuilt around a
truncated tag and could not be matched to the dialog by the far end.

Established by measurement, not argument: the outbound IPsec SA counted 11
packets / 8774 bytes -- exactly reg2 + INVITE + ACK + 8 re-ACKs -- with every
`/proc/net/xfrm_stat` error counter at zero, proving the ACK was transmitted
and rejected on content. Instrumenting the comparison gave
`to_differs=1 to_len=102/84`: an 18-byte shortfall in To alone. After
widening the buffer the same log line reads `to_len=102/102`.

The ~16s media freeze was a *symptom* of the unconfirmed dialog. It was not
RTCP. Two earlier hypotheses -- an RTCP dual-send fix, and a
truncated/unreversed route set -- were both wrong about this call, though
the route-set bug was real and is fixed.

### Registration could strand for 45 minutes

The IMS PDN advertises several P-CSCF addresses; the app passed only the
first and the daemon retried it forever. When the carrier drained that node
mid-session everything went unanswered until the radio was bounced -- and
that only helped because it happened to return a different primary.

Fixed both halves: the app sends every advertised address, the daemon parks a
candidate that stops answering for 15 minutes and moves to the next.
Verified with a deliberately dead first candidate:
`pcscf candidate 1 of 3 parked` -> `pcscf switching to candidate 2 of 3` ->
`reg1 reply: 401`.

### Inbound calls were answered by the daemon

The daemon answered every INVITE itself, so the phone never rang, there was
no session for audio, and the user could not decline. It now answers 100 and
180, holds the request, and pushes `EVENT INCOMING` to an app parked on a new
`EVENTS` control connection. `ANSWER` sends the 200; `REJECT` declines 603.

Two traps that cost time and are easy to repeat:
- Answering must **adopt the inbound dialog**, or the BYE is built from an
  empty one and the far end keeps the call up until its own timer.
- A UAS keeps the `Record-Route` set in **received** order (RFC 3261
  12.1.1); a UAC reverses it (12.1.2). Do not reuse the reversing collector
  for inbound dialogs.

---

## The finding that changes the architecture

The native daemon exists on one premise: that a Java `ImsService` cannot
program the IPsec SAs sec-agree needs, because app domains are blocked from
xfrm netlink by a platform neverallow.

**That is true of raw xfrm netlink and false in general.** `IpSecManager` /
`IpSecTransform` are public framework APIs. The hard part of sec-agree is
that the P-CSCF names the outbound SPIs, so the UE must allocate a *named*
SPI toward a *remote* destination. A spike in the app, against the live IMS
PDN and the real P-CSCF, returns:

    ipsecmanager=ok addrs=ims spi_in=exact spi_out_named=exact
    algs=ok transform=ok apply_out=ok remove=ok

Run it with
`content query --uri content://org.joan.ims.state/ipsecspike`.

Consequence: **the daemon, the unauthenticated control listener and
`sepolicy/joan_ims.te` can all be deleted together** once the SIP UA moves
into the app. MT ringing then costs almost nothing, because the INVITE
arrives in Java already. `ProjectCiRCLE-ROM/packages_apps_PhhIms` is the
existence proof of that shape -- GPL-2.0, so read it for behaviour, never
copy it into this Apache-2.0 tree.

Removal order, which is not negotiable, because this project has already got
it wrong once by positive-controlling a replacement **as root** and then
finding the app could not reach the daemon:

1. Prove `IpSecTransform` carries sec-agree ESP to a 200 OK from the app.
2. Move the UA into the app; prove MO, MT and audio there.
3. Only then delete the daemon, `ctl.c`, the listener and the sepolicy.

Every step proved as the app, in its own uid and domain.

---

## The unauthenticated listener, and how to be rid of it

`127.0.0.1:15090` ships today and is **not authenticated**. It exists only
because `connectto` on the abstract unix socket `@joan_ims_ctl` was denied to
the app, and a sideloaded zip cannot add sepolicy (measured: an appended CIL
never loads into the running policy, which is why a zero-byte
`vendor_sepolicy.cil.bak-joan-imsd` was found on the device).

Options, cheapest first, none yet tried:

1. ~~Re-test the unix socket.~~ **Tried 2026-08-29; dead.** Even as
   `platform_app` the connect is refused:

       avc: denied { connectto } scontext=u:r:platform_app:s0
            tcontext=u:r:netmgrd:s0 tclass=unix_stream_socket permissive=0

   The domain change from `priv_app` did not help. No policy, no socket.
2. **Bind and token.** Have the daemon write a random per-boot token where
   only the app can read it. Weak on this device: the daemon's files are
   netmgrd-owned and the app cannot read them without the policy we do not
   have, so this mostly moves the problem.
3. **Reverse the direction** -- daemon connects to a socket the app listens
   on -- which needs `netmgrd` -> `platform_app` `connectto` and is no more
   likely to be permitted.
4. **Delete the daemon** (see above). This is the real answer, and after
   option 1 was measured dead it is close to the only one.

The reason none of 2 or 3 amount to security: TCP has no `SO_PEERCRED`, and
`/proc/net/tcp` is EACCES from `netmgrd`, so **the daemon cannot identify its
peer at all**. Anything built on top of that is obfuscation. Until the
daemon goes, any local app can drive the UA, and a release should say so
rather than soften it.

`JOAN_IMS_BRINGUP_TCP_CTL=0` already compiles it out for an in-tree build.

---

## Device state

Cleaned to carry exactly what the zip installs:

    /system/bin/joan-ims-ua                                    755
    /system/etc/init/joan-ims.rc                               644
    /system/priv-app/JoanIms/JoanIms.apk                       644
    /system/etc/permissions/org.joan.ims.xml                   644
    /system/etc/permissions/android.hardware.telephony.ims.xml 644

Removed (backups: `/tmp/joan-cleanup-backup` on skyforge,
`/data/local/tmp/joan-cleanup-backup` on the phone): a duplicate
`/system/priv-app/joan-ims/`, an old `/system/bin/joan-ims`, two zero-byte
vendor sepolicy backups, an empty `/vendor/overlay/JoanImsOverlay`, a
redundant `privapp-permissions-org.joan.ims.xml`, and
`plat_seapp_contexts.bak-joan`.

**The duplicate priv-app cost hours.** `pm path` flip-flopped between the two
copies, and several app changes were pushed to the inactive one and silently
never ran. Always push what `pm path` reports, and re-check it every time.

`plat_seapp_contexts` needs no patching: the APK is platform-signed, so it
gets `seinfo=platform` and lands in `platform_app` through the stock rule.
The hand-added line was removed and nothing changed.

---

## Open

- **MT audio quality** unverified beyond "two-way audio exists".
- **Caller display name** never seen on air (GV sends none).
- **SMS over IMS**: `inbound datagram: MESSAGE` arrives and is unhandled.
- **AKAv2 / quoted algorithm / hardcoded cnonce+nc** -- would matter on
  another carrier.
- **VoWiFi**: not implemented. See
  `docs/vowifi-feasibility-2026-08-29.md`. Next step there is small and
  zip-deployable -- advertise `REGISTRATION_TECH_IWLAN` from the
  ImsService, which is what makes the framework request a tunnel at all.
- **Release**: `out/joan-volte-recovery.zip` builds clean; nothing pushed to
  GitHub, no release cut.

## Upstream staging

`../joan_lineageos_volte` holds `device/lge/joan` and
`device/lge/joan-common` clones on branch `joan/volte-ims`, plus
`docs/architecture-decision.md`. `vendor/lge/joan-ims` deliberately lives
here, in `upstream/`, so the sources cannot drift into two copies.

---

## Recipe for the IpSecManager migration (gathered, not yet built)

The next milestone is a **REGISTER reaching 200 OK from the app alone**, over
`IpSecTransform`, with the daemon stopped. Everything below was read out of
the working C implementation so it does not have to be re-derived.

**AKA is already solved in Java.** `JoanAka.runIccAuth(ctx, nonceB64)`
returns `"RES=.. CK=.. IK=.."`. No porting needed.

**Key derivation** (3GPP TS 33.203 Annex I, as `native/src/xfrm.c` does it):

    HMAC-SHA-1-96 auth key = IK (16 bytes) followed by 4 zero bytes -> 160 bits
    AES-CBC       enc  key = CK (16 bytes)                          -> 128 bits

**Digest** is RFC 3310 AKAv1-MD5 (`aka_digest_response_hex`):

    HA1      = MD5(impi ":" realm ":" RES)      <- RES as RAW BYTES, at its
                                                   exact length (8 or 16).
                                                   Not hex. Not zero-padded;
                                                   padding corrupts it.
    HA2      = MD5("REGISTER" ":" request_uri)
    response = MD5(hex(HA1) ":" nonce_b64 ":" nc ":" cnonce ":" qop ":" hex(HA2))

`nc=00000001` and `cnonce="cnonce01"` are currently hardcoded -- a known
defect worth fixing during the port rather than carrying over. reg2's
Authorization also carries `integrity-protected=yes`.

**Ports.** reg1 goes unprotected 5060 -> P-CSCF 5060. reg2 goes from our
protected client port to the P-CSCF's **server** port. Replies arrive on our
protected **server** port, not the client port it was sent from -- that cost
a lot of time once already, so bind and select on both.

**One asymmetry to think about carefully.** The C daemon installs four SAs
but only **outbound** policies on purpose: an inbound policy caused decrypted
replies to be dropped with `XfrmInTmplMismatch`, and Linux accepts a
decrypted packet when no inbound policy demands otherwise. `IpSecManager` is
a different model -- transforms are applied per socket per direction via
`applyTransportModeTransform(socket, DIRECTION_IN|DIRECTION_OUT, t)` -- so
that workaround does not translate directly. Expect this to be the part that
needs real experimentation.

**Run it with the daemon stopped** (`stop joan-ims`). Two SIP UAs registering
the same subscriber at once is a good way to get one of them deregistered and
to spend an evening blaming the wrong thing.

The capability half is already proven: `spi_out_named=exact` from the app,
against the live P-CSCF, means the SPIs the P-CSCF dictates can be
programmed. What is unproven is the whole flow end to end.
