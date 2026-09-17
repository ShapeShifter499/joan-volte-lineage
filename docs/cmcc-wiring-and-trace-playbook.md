# China Mobile: how it is wired, and how to read the next trace

Everything joan does for CMCC 460-00/01/02/07/08, why, and what each
outcome of the next field trace should make us do. Written 2026-09-17,
consolidating work that was otherwise spread across two documents and a
dozen commits.

The failure this exists to solve: the tester's handset draws an
`AKAv1-MD5` challenge on the unprotected REGISTER and then **404 Not
Found** on the protected one.

## What a 404 on REGISTER actually means

TS 24.229: the I-CSCF issues a **UAR** (User-Authorization-Request) to
the HSS on every REGISTER, *before* MAR. `DIAMETER_ERROR_USER_UNKNOWN`
comes back as **404**.

REG1 drew a 401 challenge, so on REG1 the UAR succeeded and MAR
succeeded. REG2 returns 404, so the UAR failed the second time -- same
subscriber, same HSS, seconds apart. That asymmetry is the whole
mystery, and it is why identity was the first thing checked and
re-checked.

**joan sends identical identities in REG1 and REG2.** Verified in code:
`sip2` is constructed from `id.impi, id.impu, id.realm` unchanged; only
the ports differ, because the protected ports are the negotiated ones.
So the 404 is not joan claiming something different the second time.

## What is wired for CMCC today

| area | value | source |
| --- | --- | --- |
| PLMN -> profile | 294 PLMNs -> `CMCC.CN` | `carrier-plmn-map.json` |
| P-CSCF port | 5060 | profile |
| REGISTER expiry | 600000 | profile, platform wins if set |
| Codecs | AMR-WB 97/98, AMR-NB 99/100, mode-sets | profile, platform wins |
| IPsec | on, `aes-cbc`/`hmac-sha-1-96` | profile |
| Feature tags | MMTEL **sent** | LG's own `tContactH` |
| REGISTER transport | computed from MTU | AOSP algorithm |
| Protected TCP close | RST, not FIN | `CMCCAoSIPSecHelper` |
| Retry-After | honoured | both reference stacks |

Three of those changed on 2026-09-17 and are the ones a new trace is
testing.

### 1. The REGISTER TCP criterion is computed, not provisioned

CMCC's LG profile provisions `reg_tcp_criterion_v4/v6 = 0`. In the
engine that means **always TCP** (`NOT_PROVISIONED` is -10, and
`nBuffLen > 0` is always true). joan used to read 0 as "unset" and
substitute 1300.

joan now ignores the provisioned value entirely and computes
`min(linkMtu, 1500) - 200`, which is AOSP's
`AosRegistration::SetTcpCriterionLength`. On a 1500-MTU bearer that is
1300 -- numerically what CMCC was already getting, but now from a
platform value a carrier can update.

### 2. The protected TCP socket closes with RST

`CMCCAoSIPSecHelper::InitIPSec` does exactly one thing:
`SetConfig(CONFIG_I_LINGER, {linger = 0})`. Under sec-agree both ends
are fixed, so every protected connection reuses one 4-tuple and a
lingering `TIME_WAIT` makes the next `connect()` fail -- which is the
`tcp connect FAIL` in the CMCC trace. `SO_REUSEADDR`, which joan already
set, permits *binding* over `TIME_WAIT` but not completing that
connection.

Verified on the bench: protected TCP carried a registration to 200 OK
and the binding survived. One network, not proof for CMCC.

### 3. Retry-After is honoured on a REGISTER rejection

CMCC's `ProcessDefaultFlowRecovery_Start` -- the path a 404 takes -- is
Retry-After back-off and **never** calls `TryNextPcscf()`. joan ignored
the header on the registration path entirely, retrying in 60s whatever
the network asked for.

## Reading the next trace

Three fields were added for this. Find them near the start of
`last_register`:

```
reg1_crit=<n> tpt_pol=<n> plmn=<realm:NNNNNN|sim:NNNNNN|none>
```

**`plmn=` first.** If it says `none`, nothing PLMN-scoped ran and every
carrier-specific decision was skipped -- fix that before reading
anything else. `sim:46002` is the expected CMCC value. This field exists
because T-Mobile silently sat at `none` for the entire project.

**Then `tpt_pol=`.** 0 = the platform demanded UDP, 1 = TCP, 2 =
dynamic (consult the criterion), -1 = the platform said nothing.

**Then `reg1_crit=` against `reg1len=`/`reg2len=`.** Over the criterion
means TCP was chosen.

### Outcome table

| what the trace shows | reading | next move |
| --- | --- | --- |
| `tcp_fail=CONNECT` gone, `tpt=tcp`, `reg2=200` | the reset fixed it | ship it |
| `tcp_fail=CONNECT` still, `tpt=udp`, `reg2=404` | `TIME_WAIT` was not the cause | `setProtectedTcpLingerReset(false)`, look at the P-CSCF port |
| `tpt=tcp` and `reg2=404` | transport is fine; the 404 is real | the 404 is an HSS/UAR answer -- provisioning, not joan |
| `reg2=404` with `retry_after=` present | we were retrying too fast before | confirm the new backoff is being honoured |
| `plmn=none` | carrier logic never ran | the realm is branded; check the SIM PLMN path |

The third row is the one to hope for, oddly. A 404 that arrives over the
transport the carrier's own configuration asks for is a much cleaner
signal than a 404 after a fallback -- it separates the transport failure
from the registration failure for the first time.

## Hypotheses closed, with evidence

Kept so nobody re-opens them:

- **ISIM required.** CMCC's own white paper requires USIM-derived
  identifiers. Not an ISIM problem.
- **Identity derivation differs for CMCC.** Of 1177 CMCC symbols in
  `libims.lge.so`, `UpdateUserIdentities` is the *only* one touching
  identity, and it runs *after* a successful registration. No IMPU
  builder, no IMPI builder, no realm specialisation.
- **A hidden CMCC SIP quirk.** All 15 `CMCCAoSRegistration` overrides
  are decompiled and tabulated in the extract doc. None shapes an
  outgoing REGISTER's identity.
- **Feature tags withheld.** Backwards -- LG sends MMTEL to CMCC, and
  joan briefly did the opposite. Reverted before it shipped.
- **An encryption-configuration outlier.** Across 136 profiles CMCC
  holds the *majority* value for `ipsec` (126), `ipsec_spi_3gpp` (120)
  and `ipsec_algs` (127).
- **AOSP has CMCC settings to copy.** It ships one carrier-config file
  and it is the defaults. Zero CMCC references in the whole tree.

## What would settle it

A packet capture, or the `reg2_hdrs` and the three new fields from a
build the tester runs. Everything reachable from this side has been
reached: the vendor binary is exhausted for this question.

If the trace shows row three -- a 404 over TCP -- the remaining
explanation is subscriber provisioning at the operator, not code, and
the useful next step is the tester asking China Mobile whether VoLTE is
provisioned for that IMPI on a non-VoLTE-branded device.
