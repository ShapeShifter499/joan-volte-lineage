#!/usr/bin/env bash
# Regenerate every carrier asset the ImsService reads, from its sources.
#
#   tools/regen-carrier-assets.sh <Ims6-assets-dir> <carrier_list.textpb>
#
# <Ims6-assets-dir> is assets/ out of a stock LG Ims6.apk (the VS996 30d
# and H932 30d builds carry the same 169-file world set); only values are
# transcribed, nothing of LG's ships. <carrier_list.textpb> is
# assets/latest_carrier_id/carrier_list.textpb from AOSP
# platform/packages/providers/TelephonyProvider.
#
# Outputs, all under ims-service/assets/:
#   carrier-profiles-full.json  every profile the stock tree yields
#   carrier-profiles.json       the ones something can reach
#   carrier-plmn-map.json       PLMN -> profile (stock table + extras)
#   carrier-id-map.json         Android carrier id -> profile
set -euo pipefail
cd "$(dirname "$0")/.."
ASSETS=${1:?Ims6 assets dir}
TEXTPB=${2:?carrier_list.textpb}
OUT=ims-service/assets
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

python3 tools/make-carrier-profiles.py "$ASSETS/Configuration" "$OUT/carrier-profiles-full.json"
python3 tools/make-plmn-map.py "$ASSETS/SmartConfiguration/SmartConfiguration.xml" \
    "$OUT/carrier-profiles-full.json" "$WORK/plmn-stock.json"
python3 tools/make-carrier-id-map.py "$TEXTPB" "$OUT/carrier-profiles-full.json" \
    "$WORK/plmn-stock.json" "$OUT/carrier-id-map.json" "$WORK/plmn-extra.json"
python3 tools/make-plmn-map.py "$ASSETS/SmartConfiguration/SmartConfiguration.xml" \
    "$OUT/carrier-profiles-full.json" "$OUT/carrier-plmn-map.json" "$WORK/plmn-extra.json"

# Ship what something can reach: the PLMN map, the carrier id map, or the
# hand-kept CMCC rule in JoanCarrierProfile.carrierKey.
python3 - "$OUT" <<'PY'
import json, os, sys
out = sys.argv[1]
full = json.load(open(os.path.join(out, "carrier-profiles-full.json")))
reach = set(json.load(open(os.path.join(out, "carrier-plmn-map.json"))).values())
reach |= set(json.load(open(os.path.join(out, "carrier-id-map.json"))).values())
reach |= {"CMCC.CN"}
ship = {k: v for k, v in full.items() if k in reach}
with open(os.path.join(out, "carrier-profiles.json"), "w") as f:
    json.dump(ship, f, indent=1, sort_keys=True)
dropped = sorted(set(full) - set(ship))
print(f"shipping {len(ship)} of {len(full)} profiles; unreachable: {', '.join(dropped)}")
PY
