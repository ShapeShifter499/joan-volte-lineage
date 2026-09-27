#!/usr/bin/env python3
"""Map Android carrier ids, and the PLMNs LG's own table never listed, to
joan carrier profiles.

LG's SmartConfiguration maps PLMNs to profiles for its open-market SKUs
only. North America was sold as carrier-branded SKUs, so the table has no
row for AT&T, Verizon, U.S. Cellular, Bell, Rogers, TELUS or Videotron,
and joan filled the gap by giving every unmapped PLMN in MCC 310-316 T-Mobile's
profile. MVNO profiles LG carries (Cricket, MetroPCS, TracFone, nju) can
not be expressed by PLMN at all, because the MVNO shares its host's PLMN.

Android already solves both: the carrier id database in TelephonyProvider
(carrier_list.textpb) resolves a SIM to a canonical carrier -- and an MVNO
to its own id, by GID1, SPN or IMSI prefix -- and TelephonyManager
.getSimCarrierId() hands the answer to any app without a permission.

This tool emits:

  carrier-id-map.json    {carrier id: profile key}, from
                         (a) CURATED below, reviewed by hand, and
                         (b) every carrier id whose unqualified PLMNs all
                             resolve to one profile in the PLMN map.
  plmn extra rows        {plmn: profile key} for the curated operators'
                         own unqualified PLMNs, so a device that reports
                         no carrier id still resolves them.

Only VALUES are transcribed. Usage:
  tools/make-carrier-id-map.py carrier_list.textpb profiles.json \
      plmn-map.json out-carrier-id-map.json out-plmn-extra.json
"""
import json
import re
import sys

# carrier id -> profile key. Every id and name here was checked against
# carrier_list.textpb (android15-qpr2-release). Keep it to operators LG
# ships a profile for; everything else falls back to the PLMN map and then
# to 3GPP defaults.
CURATED = {
    # United States
    1: "TMO.US.NAO",        # T-Mobile - US
    1949: "MPCS.US",        # MetroPCS
    10001: "TMO.US.TRF",    # Tracfone-TMO
    10014: "TMO.US.NAO",    # Google Fi-TMO
    10023: "TMO.US.NAO",    # Consumer Cellular TMO
    1187: "ATT.US.NAO",     # AT&T
    10021: "ATT.US.NAO",    # AT&T 5G
    10028: "ATT.US.NAO",    # AT&T 5G SA
    2628: "ATT.US.NAO",     # AT&T Private
    2119: "ATT.US.NAO",     # FirstNet, on AT&T's core
    10013: "ATT.US.NAO",    # FirstNet Pacific
    1779: "ATT.US.CRK",     # Cricket Wireless
    10029: "ATT.US.CRK",    # Cricket 5G
    10038: "ATT.US.CRK",    # Cricket 5G SA
    10000: "ATT.US.TRF",    # Tracfone-ATT
    10022: "ATT.US.NAO",    # Consumer Cellular ATT
    1839: "VZW.US.NAO",     # Verizon Wireless
    2146: "VZW.US.NAO",     # Visible, on Verizon's core
    10008: "VZW.US.NAO",    # Tracfone-VZW
    1952: "USC.US.NAO",     # U.S. Cellular
    10016: "USC.US.NAO",    # Google Fi-USCellular
    1788: "SPR.US",         # Sprint
    # Canada
    576: "BELL.CA",         # Bell Mobility
    10005: "BELL.CA",       # pcmobile_prepaid_bell
    1403: "RGS.CA",         # Rogers
    10025: "RGS.CA",        # Rogers 5G
    1404: "TLS.CA",         # TELUS Mobility
    10006: "TLS.CA",        # pcmobile_postpaid_telus
    2008: "VTR.CA",         # Videotron
    1895: "FRD.CA",         # Freedom Mobile
    2252: "EST.CA",         # EastLink
    # Europe
    2101: "BT.GB",          # BT
    2102: "BT.GB",          # BT Business
    2103: "BT.GB",          # BT One Phone
    1659: "ORG.PL",         # Orange Polska: 26003 is claimed by both
                            # ORG.PL and NJU.PL in LG's PLMN table
    # Central America
    1520: "CLR.GT",         # Claro GT
    773: "CLR.HN",          # Claro HN
    1641: "CLR.NI",         # Claro NI
    1925: "CLR.PA",         # Claro PA
}

# Operators whose PLMNs must NOT get a borrowed profile through the PLMN
# fallback: LG ships nothing for them, and the wholesale MCC rules this
# replaces used to hand them a competitor's settings.
# Deliberately absent from CURATED: Spectrum Mobile (2126). It rides
# Verizon's radio network but claims its own PLMN 313450 as well, and
# whether that core registers on Verizon's IMS is not something this
# table can know; its Verizon-PLMN SIMs still resolve through the PLMN map.
NO_PROFILE = {
    2109: "Rakuten Mobile (MVNO on docomo)",
    2429: "Rakuten Mobile (MNO)",
    1436: "China Unicom",
    2237: "China Telecom",
}

QUALIFIERS = ("gid1", "gid2", "spn", "imsi_prefix_xpattern",
              "iccid_prefix", "plmn", "privilege_access_rule",
              "preferred_apn")


def parse(path):
    txt = open(path, encoding="utf-8").read()
    out = []
    for blk in re.finditer(r"carrier_id \{(.*?)\n\}", txt, re.S):
        b = blk.group(1)
        cid = int(re.search(r"canonical_id: (\d+)", b).group(1))
        name = re.search(r'carrier_name: "([^"]*)"', b)
        attrs = []
        for a in re.finditer(r"carrier_attribute \{(.*?)\n  \}", b, re.S):
            d = {}
            for k, v in re.findall(r'(\w+): "([^"]*)"', a.group(1)):
                d.setdefault(k, []).append(v)
            attrs.append(d)
        out.append({"cid": cid, "name": name.group(1) if name else "",
                    "attrs": attrs})
    return out


def unqualified_plmns(entry):
    """PLMNs an entry claims with no MVNO qualifier: the MNO's own."""
    out = set()
    for a in entry["attrs"]:
        if any(q in a for q in QUALIFIERS):
            continue
        out.update(a.get("mccmnc_tuple", []))
    return out


def main():
    if len(sys.argv) != 6:
        print(__doc__)
        return 2
    textpb, profiles_path, plmn_path, out_cid, out_extra = sys.argv[1:6]
    db = parse(textpb)
    by_id = {e["cid"]: e for e in db}
    profiles = json.load(open(profiles_path))
    plmn = json.load(open(plmn_path))

    for cid, key in CURATED.items():
        if cid not in by_id:
            print(f"curated id {cid} is not in the database", file=sys.stderr)
            return 1
        if key not in profiles:
            print(f"curated id {cid} -> {key}: no such profile",
                  file=sys.stderr)
            return 1

    cmap = {}
    auto = 0
    for e in db:
        if e["cid"] in CURATED or e["cid"] in NO_PROFILE:
            continue
        keys = {plmn[p] for p in unqualified_plmns(e) if p in plmn}
        if len(keys) == 1:
            cmap[e["cid"]] = keys.pop()
            auto += 1
    cmap.update(CURATED)

    extra, conflicts = {}, []
    for cid, key in CURATED.items():
        for p in sorted(unqualified_plmns(by_id[cid])):
            if not (p.isdigit() and 5 <= len(p) <= 6):
                continue
            have = plmn.get(p) or extra.get(p)
            if have and have != key:
                conflicts.append(f"{p}:{have}/{key}")
                continue
            if p not in plmn:
                extra[p] = key

    with open(out_cid, "w") as f:
        json.dump({str(k): v for k, v in sorted(cmap.items())}, f,
                  indent=1, sort_keys=False)
        f.write("\n")
    with open(out_extra, "w") as f:
        json.dump(dict(sorted(extra.items())), f, indent=1)
        f.write("\n")
    print(f"wrote {out_cid}: {len(cmap)} carrier ids "
          f"({len(CURATED)} curated, {auto} from the PLMN map)")
    print(f"wrote {out_extra}: {len(extra)} PLMNs the LG table lacked")
    if conflicts:
        print(f"  kept the LG table's answer for {len(conflicts)}: "
              + ", ".join(conflicts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
