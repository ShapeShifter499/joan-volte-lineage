#!/usr/bin/env bash
# The shipped carrier assets must agree with each other.
#
# The PLMN map is generated from stock configuration and the profiles are
# distilled from it; nothing at runtime notices if the two drift, because a
# key that resolves to nothing silently falls back to 3GPP defaults. That
# failure is invisible in a trace, so it is caught here instead.
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 - <<'PY'
import json, sys, re
fail = 0
def check(cond, name):
    global fail
    print(("  ok: " if cond else "  FAIL: ") + name)
    if not cond:
        fail += 1

prof = json.load(open("ims-service/assets/carrier-profiles.json"))
pmap = json.load(open("ims-service/assets/carrier-plmn-map.json"))
cmap = json.load(open("ims-service/assets/carrier-id-map.json"))

missing = sorted({v for v in pmap.values() if v not in prof})
check(not missing, f"every mapped key has a profile (missing: {missing[:5]})")

bad = [k for k in pmap if not (k.isdigit() and 5 <= len(k) <= 6)]
check(not bad, f"every PLMN is 5-6 digits (bad: {bad[:5]})")

# A profile nothing can address is dead weight in the apk.
hand = {"CMCC.CN"}
orphan = sorted(set(prof) - set(pmap.values()) - set(cmap.values()) - hand)
check(not orphan, f"no unreachable profiles (orphans: {orphan[:5]})")
cmiss = sorted({v for v in cmap.values() if v not in prof})
check(not cmiss, f"every carrier id resolves to a shipped profile ({cmiss[:5]})")
badid = [k for k in cmap if not k.isdigit()]
check(not badid, f"carrier id keys are numeric ({badid[:5]})")

# North America. LG sold carrier-branded SKUs there, so its PLMN table has
# no row for these; joan used to hand all of MCC 310-316 T-Mobile's
# profile. They come from Android's carrier id database now.
na = {"310260": "TMO.US.NAO", "310410": "ATT.US.NAO", "311480": "VZW.US.NAO",
      "310590": "VZW.US.NAO", "311580": "USC.US.NAO", "310150": "ATT.US.CRK",
      "302610": "BELL.CA", "302720": "RGS.CA", "302220": "TLS.CA",
      "302500": "VTR.CA", "302490": "FRD.CA", "310120": "SPR.US"}
for p, k in na.items():
    check(pmap.get(p) == k, f"{p} resolves to {k} (got {pmap.get(p)})")
ids = {"1": "TMO.US.NAO", "1187": "ATT.US.NAO", "1839": "VZW.US.NAO",
       "1952": "USC.US.NAO", "1779": "ATT.US.CRK", "1949": "MPCS.US",
       "10000": "ATT.US.TRF", "10001": "TMO.US.TRF", "576": "BELL.CA",
       "2008": "VTR.CA", "1659": "ORG.PL", "2101": "BT.GB"}
for i, k in ids.items():
    check(cmap.get(i) == k, f"carrier id {i} resolves to {k} (got {cmap.get(i)})")
# Operators LG ships nothing for must fall to the 3GPP defaults, not to a
# neighbour: Rakuten used to get DoCoMo's profile, Unicom and Telecom once
# got China Mobile's.
for p in ("44011", "46001", "46003", "46011"):
    check(p not in pmap, f"{p} is not mapped to a borrowed profile")
for i in ("2109", "2429", "1436", "2237"):
    check(i not in cmap, f"carrier id {i} is not mapped to a borrowed profile")

# The networks registration depends on distinguishing: these do not
# negotiate IPsec in LG's configuration, and joan has to register there
# without sec-agree.
for k in ("VZW.US.NAO", "USC.US.NAO", "SPR.US", "CSL.HK", "PCCW.HK",
          "H3G.HK", "BEE.RU", "MGF.RU", "EST.CA", "FRD.CA", "TELE.BG"):
    check(prof.get(k, {}).get("ipsec") is False, f"{k} registers without IPsec")
for k in ("TMO.US.NAO", "ATT.US.NAO", "CMCC.CN", "KT.KR", "DCM.JP"):
    check(prof.get(k, {}).get("ipsec") is True, f"{k} registers with IPsec")

# China Mobile is more than one PLMN. Mapping MCC 460 wholesale once gave
# Unicom and Telecom subscribers China Mobile's settings.
cmcc = sorted(k for k, v in pmap.items() if v == "CMCC.CN")
check(cmcc == ["46000", "46002", "46007", "46008"],
      f"CMCC covers its own PLMNs and no others ({cmcc})")
for other in ("46001", "46006", "46009", "46003", "46005", "46011"):
    check(pmap.get(other) != "CMCC.CN",
          f"{other} is not given China Mobile's profile")

# The PLMN that reports the reg2=404 must resolve, with the right criterion.
check(pmap.get("46002") == "CMCC.CN", "46002 resolves to CMCC.CN")
check(prof["CMCC.CN"].get("tcp_criterion_len") == 1300,
      "CMCC.CN carries China Mobile's 1300-byte TCP criterion")
check(prof["TMO.US.NAO"].get("tcp_criterion_len") == 1200,
      "TMO.US.NAO carries T-Mobile's 1200 (joan overrides it to stay UDP)")

# Routing: the unprotected P-CSCF port is not 5060 everywhere.
kt = prof.get("KT.KR", {})
check(kt.get("pcscf_port") == 5080,
      f"KT.KR keeps its 5080 P-CSCF port ({kt.get('pcscf_port')})")
check(pmap.get("45002") == "KT.KR" and pmap.get("45008") == "KT.KR",
      "KT Korea's PLMNs resolve to KT.KR")
off = sorted({v.get("pcscf_port") for v in prof.values()
              if v.get("pcscf_port") not in (5060, None)})
check(off == [5080], f"5080 is the only non-default P-CSCF port ({off})")

# Encryption: joan offers aes-cbc and null. No carrier may require
# something outside that set, or registration there cannot protect.
OURS_E = {1, 4}
bad_e = []
for k, v in prof.items():
    m = v.get("ipsec_algs")
    if not isinstance(m, int):
        continue
    enc = {b for b in (1, 2, 4) if (m >> 16) & b}
    if enc and not (enc & OURS_E):
        bad_e.append(k)
check(not bad_e,
      f"every carrier allows an encryption alg joan offers (bad: {bad_e[:4]})")

# common_sip_features bit 24 (AUTHENTICATION_ALGORITHM_PARAMETER) is what
# decides, in stock, whether the REGISTER's Authorization header carries
# "algorithm=". Since alpha38 the runtime follows it per carrier
# (JoanCarrierProfile.sendAuthAlgorithm), with RFC 3310's behaviour where
# no profile answers. These checks pin the finding that
# docs/lg-ims-carrier-extract-2026-09-04.md rests on, so a regenerated
# profile set cannot quietly invalidate what that document claims.
AUTH_ALGO_BIT = 0x01000000
def sip_features(key):
    v = prof.get(key, {}).get("sip_features")
    return None if not isinstance(v, str) else int(v, 16)

unparsable = sorted(k for k, v in prof.items()
                    if isinstance(v.get("sip_features"), str)
                    and not re.fullmatch(r"0[xX][0-9a-fA-F]+",
                                         v["sip_features"]))
check(not unparsable,
      f"every sip_features mask is readable hex (bad: {unparsable[:4]})")

cm = sip_features("CMCC.CN")
check(cm is not None and not (cm & AUTH_ALGO_BIT),
      f"CMCC.CN clears the algorithm-parameter bit (0x{cm:08x})"
      if cm is not None else "CMCC.CN carries a sip_features mask")
tm = sip_features("TMO.US.NAO")
check(tm is not None and (tm & AUTH_ALGO_BIT),
      f"TMO.US.NAO sets the algorithm-parameter bit (0x{tm:08x})"
      if tm is not None else "TMO.US.NAO carries a sip_features mask")
# Omission is stock's NORM, not a China Mobile quirk: only the Korean
# three, two Russian carriers and the T-Mobile US family (T-Mobile and its
# MetroPCS and TracFone variants) send the parameter. If this set moves,
# re-read that decision.
senders = sorted(k for k in prof
                 if (sip_features(k) or 0) & AUTH_ALGO_BIT)
check(senders == ["BEE.RU", "KT.KR", "LGU.KR", "MPCS.US", "SKT.KR",
                  "TELE2.RU", "TMO.US.NAO", "TMO.US.TRF"],
      f"the same eight profiles send algorithm= ({len(senders)} of "
      f"{len(prof)}): {senders}")

print(f"carrier asset tests: {'FAIL %d' % fail if fail else 'all passed'}")
sys.exit(1 if fail else 0)
PY
