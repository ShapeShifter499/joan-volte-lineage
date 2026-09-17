#!/usr/bin/env python3
"""Emit a PLMN -> carrier-profile-key map from stock SmartConfiguration.xml.

Extract-only transcription of discovered VALUES. No vendor file is shipped;
run this against a stock Ims6 APK's assets and it emits a JSON map that
joan's JoanCarrierProfile uses to pick a profile by PLMN.

Why this exists: joan distilled 164 carrier profiles long before it could
address them. carrierKey() mapped about six PLMN ranges by hand, so the
rest were unreachable, and MCC 460 was once mapped wholesale to China
Mobile -- which handed Unicom and Telecom subscribers another operator's
settings. A real PLMN table removes the guessing.

Usage:
    tools/make-plmn-map.py <SmartConfiguration.xml> <profiles.json> <out.json>
"""
import json
import re
import sys

# Variants joan deliberately prefers over the bare OPERATOR.COUNTRY key.
PREFER = {
    "ATT.US": "ATT.US.NAO",
    "TMO.US": "TMO.US.NAO",
    "VZW.US": "VZW.US.VOWIFI",
}


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    smart, profiles_path, out = sys.argv[1:4]
    text = open(smart, encoding="utf-8", errors="replace").read()
    block = re.search(r'<table id="mccmnc_list">(.*?)</table>', text, re.S)
    if not block:
        print("no mccmnc_list table", file=sys.stderr)
        return 1
    profiles = json.load(open(profiles_path))

    out_map, skipped = {}, {}
    for row in re.findall(r"<param ([^/]*)/>", block.group(1)):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', row))
        plmn = attrs.get("mccmnc", "")
        op, cc = attrs.get("operator", ""), attrs.get("country", "")
        if not (plmn.isdigit() and 5 <= len(plmn) <= 6 and op and cc):
            continue
        # A gid-qualified row needs a SIM group id joan does not read;
        # mapping it by PLMN alone would claim subscribers it does not own.
        if attrs.get("gid"):
            skipped.setdefault("gid-qualified", []).append(plmn)
            continue
        base = f"{op}.{cc}"
        key = PREFER.get(base, base)
        if key not in profiles:
            cands = sorted(k for k in profiles if k.startswith(base + "."))
            if base in profiles:
                key = base
            elif len(cands) == 1:
                key = cands[0]
            else:
                skipped.setdefault("no-profile", []).append(f"{plmn}:{base}")
                continue
        # Two operators claiming one PLMN means the table disagrees with
        # itself; keeping the first silently would be a coin toss.
        if plmn in out_map and out_map[plmn] != key:
            skipped.setdefault("conflict", []).append(
                f"{plmn}:{out_map[plmn]}/{key}")
            continue
        out_map[plmn] = key

    with open(out, "w") as f:
        json.dump(dict(sorted(out_map.items())), f, indent=1, sort_keys=True)
        f.write("\n")
    print(f"wrote {out}: {len(out_map)} PLMNs -> "
          f"{len(set(out_map.values()))} profiles")
    for why, items in sorted(skipped.items()):
        print(f"  skipped {len(items):4d} ({why}): {', '.join(items[:6])}"
              f"{' ...' if len(items) > 6 else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
