#!/usr/bin/env bash
# Host checks for the Viettel 45204 APN overlay + merge (no Android).
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
MERGE=$ROOT/scripts/merge-viettel-apns.sh
OVER=$ROOT/apn/viettel-45204.xml
FIX=$ROOT/tests/apn/fixtures
OUT=$ROOT/native/build/apn-host
mkdir -p "$OUT"

fail() { echo "FAIL: $*"; exit 1; }
pass() { echo "  ok: $*"; }

[ -x "$MERGE" ] || [ -f "$MERGE" ] || fail "missing $MERGE"
chmod +x "$MERGE" 2>/dev/null || true
[ -f "$OVER" ] || fail "missing $OVER"

python3 - "$OVER" <<'PY' || fail "overlay xml"
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
rows = [el.attrib for el in root.iter("apn")]
assert root.get("version") == "8", root.get("version")
by = {(d.get("apn"), d.get("type")): d for d in rows}
ims = by[("ims", "ims")]
xcap = by[("xcap", "xcap")]
inet = by[("v-internet", "default,ia,supl")]
mms = by[("v-mms", "mms")]
for d in (ims, xcap, inet):
    assert d.get("mcc") == "452" and d.get("mnc") == "04"
    assert d.get("protocol") == "IPV4V6"
    assert d.get("roaming_protocol") == "IPV4V6"
    assert d.get("carrier_id") == "1899"
assert ims.get("carrier") == "Viettel IMS"
assert xcap.get("carrier") == "Viettel Ut"
assert "ia" in inet.get("type")
assert mms.get("mmsc", "").startswith("http://mms.viettelmobile.com.vn/")
assert all(d.get("mcc") == "452" and d.get("mnc") == "04" for d in rows)
assert len(rows) == 4, rows
print("overlay rows", len(rows))
PY
pass "overlay has IMS, XCAP/UT, IPV4V6 internet, Lineage MMS"

export MERGE_TMP="$OUT/over-blocks"
merged=$OUT/merged.xml
sh "$MERGE" "$FIX/orig-apns-conf.xml" "$OVER" > "$merged" || fail "merge"

python3 - "$merged" "$FIX/orig-apns-conf.xml" <<'PY' || fail "merged xml"
import sys, xml.etree.ElementTree as ET
merged = ET.parse(sys.argv[1]).getroot()
orig = ET.parse(sys.argv[2]).getroot()
mrows = [el.attrib for el in merged.iter("apn")]
orows = [el.attrib for el in orig.iter("apn")]
def plmn(d):
    return d.get("mcc"), d.get("mnc")
keep = [d for d in orows if plmn(d) != ("452", "04")]
got_keep = [d for d in mrows if plmn(d) != ("452", "04")]
assert [(d.get("carrier"), d.get("apn")) for d in keep] == [
    (d.get("carrier"), d.get("apn")) for d in got_keep
], (keep, got_keep)
vt = [d for d in mrows if plmn(d) == ("452", "04")]
assert len(vt) == 4, vt
by_apn = {d.get("apn"): d for d in vt}
assert by_apn["ims"]["type"] == "ims"
assert by_apn["ims"]["protocol"] == "IPV4V6"
assert by_apn["xcap"]["type"] == "xcap"
assert by_apn["xcap"]["protocol"] == "IPV4V6"
assert "ia" in by_apn["v-internet"]["type"]
assert by_apn["v-internet"]["protocol"] == "IPV4V6"
assert by_apn["v-mms"]["type"] == "mms"
vnet = [d for d in mrows if d.get("apn") == "v-internet"]
assert len(vnet) == 1 and vnet[0].get("protocol") == "IPV4V6"
assert any(d.get("mcc") == "310" and d.get("apn") == "fast.t-mobile.com" for d in mrows)
assert any(d.get("mcc") == "452" and d.get("mnc") == "01" for d in mrows)
print("merged total", len(mrows), "kept non-45204", len(got_keep))
PY
pass "merge keeps other PLMNs and installs IMS/XCAP/IPV4V6"

sh "$MERGE" "$merged" "$OVER" > "$OUT/merged2.xml" || fail "second merge"
python3 - "$OUT/merged2.xml" <<'PY' || fail "idempotent"
import sys, xml.etree.ElementTree as ET
rows = [el.attrib for el in ET.parse(sys.argv[1]).getroot().iter("apn")]
vt = [d for d in rows if d.get("mcc") == "452" and d.get("mnc") == "04"]
assert len(vt) == 4, vt
assert any(d.get("mcc") == "310" and d.get("apn") == "fast.t-mobile.com" for d in rows)
print("second-merge rows", len(rows))
PY
pass "re-merge does not duplicate 45204 or drop TMUS"

# Flashing the overlay as the whole product file would wipe TMUS/etc.
python3 - "$OVER" <<'PY' || fail "overlay is not a world list"
import sys, xml.etree.ElementTree as ET
rows = [el.attrib for el in ET.parse(sys.argv[1]).getroot().iter("apn")]
assert all(d.get("mcc") == "452" and d.get("mnc") == "04" for d in rows)
assert len(rows) == 4
print("overlay must be merged, never copied over product/etc/apns-conf.xml")
PY
pass "overlay is Viettel-only (installer must merge)"

echo "APN overlay tests passed"
