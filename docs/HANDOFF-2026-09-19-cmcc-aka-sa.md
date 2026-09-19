# Handoff — CMCC after alpha38, and what the new trace changed

Written 2026-09-19 from a tester trace captured that morning on
`0.4.0-alpha38 (48)`, with an `alpha34 (44)` run in the same file for
comparison. Read this before touching the CMCC lane; several things the
repo previously asserted are now wrong.

## Read this first: the failure changed

alpha34 and alpha38 fail differently on the same handset, same SIM,
minutes apart. That has never happened before -- every prior CMCC
capture was invariant.

| | alpha34 (11:36) | alpha38 (11:40) |
| --- | --- | --- |
| status | `reg2=404` | `reg2=500`, then `reg2=404` |
| warning text | `"Server Internal Error"` | `"AKA sync proc timeout"`, then `"AKA add SA failure"` |
| warn-agent | `5144.2233.S.260.5.263.255.255.5938.0.0.zj.chinamobile.com` | `03024.03384.A.005.517.227.25.5.07309.00000000` |
| AKA route | `aka via USIM getIccAuthentication` | `aka via USIM apdu aid=ef_dir` |

**The warn-agent format changed completely.** The old one is the 11-field
Huawei grammar plus an operator domain, documented in
`cmcc-wiring-and-trace-playbook.md`. The new one has a different field
count and no domain suffix. A different network element is answering, or
the same one is failing at a later stage. Either way the request is
getting further than it used to, and the error is now specific rather
than generic.

Do not read this as "alpha38 broke it". It is the first movement in this
lane in weeks. The generic 404 became two named AKA/SA faults.

## Hypotheses this trace CLOSES

- **The card has no ISIM.** EF_DIR now reads (the alpha38 fix works) and
  returns exactly one record:
  `ef_dir: records=1 aids=[A0000000871002FF86FFFF89FFFFFFFF]`, followed by
  `isim_files: EF_DIR lists no ISIM`. One USIM, no ISIM, stated by the
  card. The AID/ISIM hypothesis is dead -- not weakened, dead. Stop
  spending on it.
- **AKA is not desynchronised on the card side.** `apdu: len=110 sw=9000
  tag=db field_len=8` -- tag `0xDB` is success, not `0xDC` sync failure.
  The AUTS resync path exists and is correct; it is simply not being
  triggered. "AKA sync proc timeout" is the network's own procedure
  timing out, not our card refusing.
- **Not roaming.** `network_roaming=false`, `plmn=46002`, home network.
  Lance asked the tester directly: one SIM only.

## The strongest open lead: we offer null encryption

```
ealg=null alg=hmac-sha-1-96 offered=hmac-sha-1-96/null*
```

On the T-Mobile bench the same field reads
`ealg=aes-cbc alg=hmac-sha-1-96 offered=hmac-sha-1-96/aes-cbc*`.

So joan offers CMCC integrity-only ESP with **no encryption**, and CMCC
then reports `"AKA add SA failure"` -- the network failing to add its
security association. Those two facts sit next to each other and nobody
has connected them yet.

The carrier assets disagree with the wire:

| profile | `ipsec_algs` | |
| --- | --- | --- |
| CMCC.CN | `458755` = `0x70003` | low word 3, high word **7** |
| TMO.US | `65539` = `0x10003` | low word 3, high word **1** |

CMCC advertises *more* encryption algorithms than T-Mobile, and gets
null. That inversion is the thing to chase.

**`ipsec_algs` is never read.** Verified: zero references to `ipsec_algs`
anywhere in `ims-service/src`. It is distilled into
`carrier-profiles-full.json` and ignored, exactly as `xcap_server` was
before Deck #144. So whatever selects `null` is doing so without ever
consulting the carrier's declared algorithm set.

Next step, in order:
1. Find what actually sets `pcscfSec.ealg`. It comes from `JoanSecAgree`,
   parsed from the network's `Security-Server` header. Determine whether
   CMCC genuinely offers `null`, or whether our parse of a multi-row or
   multi-value `Security-Server` drops `aes-cbc`. Note alpha37 changed
   this area (`561de72`, read every `Security-Server` row) -- check
   whether joining rows with commas confuses the selector.
2. Decode `ipsec_algs` and honour it, at minimum as a sanity check: if
   the carrier declares encryption algorithms and we selected `null`,
   that is worth refusing or at least tracing loudly.
3. Only then consider whether CMCC requires `aes-cbc` and rejects
   integrity-only SAs.

`xfrm=unavailable` in the same line is a **diagnostics** gap, not a
functional one -- `JoanXfrmStats` could not read the counters. Our own
SAs applied (`apply_out=ok in=ok`). Do not chase it as the fault.

## Corrections to standing repo claims

- **`README.md:143-146` says "The same handset and build registers on
  China Telecom (46011)".** The tester told Lance on 2026-09-19 they use
  only one SIM, and this capture is `network_roaming=false` on 46002.
  That claim is load-bearing -- it is the basis for "identity derivation
  works on a Chinese network" -- and it now needs re-verification or
  removal. It may conflate two testers. Do not build on it until settled.
- **`cmcc-wiring-and-trace-playbook.md`** describes the failure as
  invariant with only field 6 of the warn-agent varying. No longer true;
  see the table above.
- **alpha38's release notes** call the EF_DIR work "mostly diagnostic"
  for CMCC. That was right, and the diagnosis it produced is the
  no-ISIM finding above.

## What shipped, and what is not pushed

- **alpha37** (`v0.4.0-alpha37`) released, then **superseded** -- its
  EF_DIR read never worked. Banner added to its release notes.
- **alpha38** (`v0.4.0-alpha38`) released, bench-verified: full
  uninstall/install pass, EF_DIR reads, `aid=ef_dir` in use, registers on
  T-Mobile. **This is the build testers should have.**
- **alpha39 is committed locally and NOT pushed** (`27efd70` fix +
  `8c7784e` bump). It re-publishes the registration flag on the refresh
  path -- a real gap, but not the bug either GitHub #1 or the XDA tester
  hit. Bench-verified for no regression via an airplane cycle; its
  corrective case was not reproducible on the bench.
- **`/releases/latest` still serves alpha34**, because 37/38 were
  published as prereleases. Two testers (Deck #122, #135) were already
  burned by an equivalent trap serving v0.3.0. Promoting alpha38 to
  Latest is a one-line `gh release edit` and is Lance's call.

## Tester board, corrected

Six lanes on Deck board 4, stack "Waiting". A previous session answered a
"which tester is closest" question from GitHub issues and the README
alone and got it wrong; **check the board first.**

- **#111 China Unicom** (@Slowhy, alpha10) -- PCO carries zero P-CSCF, never
  reaches SIP. **Closest lane to closing.** Its proposed fix, "resolve the
  3GPP FQDN via `Network.getAllByName` when PCO is empty", is half built:
  `resolveOn(Network, host, 2s)` landed in `c4b49d0`, but nothing
  synthesises `pcscf.ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org` -- zero
  references to `3gppnetwork` in the source. Build the FQDN from the
  SIM's PLMN, never the realm (branded-realm trap), and pass it to
  `resolveOn`. Their card is a USIM with no ISIM, so EF_PCSCF will not
  help them.
- **#137 CMCC** -- this document.
- **#135 GitHub #1** and **#122 XDA** -- both on v0.3.0 via "Latest".
  Build age, not code. Tell them to take alpha38.
- **#131 NOS** -- REG1 sent, zero datagrams received. Untouched by alpha38.
- **#133 Viettel** -- 401 seen, REG2 timeouts. Untouched by alpha38.

## Bench facts worth keeping

- Handset is on **nym-nest**, not skyforge. `ssh nym-nest-family`.
- Recovery auto-enters sideload; poll for state `sideload` OR `recovery`,
  and expect a transient `unauthorized` while it re-enumerates.
- **An unsigned zip needs a physical tap** on the handset.
- `error: 21` and `Command 0 finished with 1` appear in `recovery.log` on
  a **successful** install. Verify against the filesystem: mount
  `/dev/block/mapper/system` and `product` read-only from recovery.
- Verify the build by the state provider's build row and the APK md5,
  never `dumpsys package`.
- `pgrep -f org.joan.ims` self-matches the shell running it. Use `ps -A`.

Signed-off-by: Lance <Gero3977@gmail.com>
Assisted-by: Claude-Code:claude-opus-5
