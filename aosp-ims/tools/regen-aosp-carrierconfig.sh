#!/bin/bash
# Regenerate carrier/aosp17-carrierconfig-ims.xml from the inputs pinned in
# upstream.lock (AOSP_*), fetched blobless into $WORK/aosp-carrierconfig.
# --check writes to a temporary file and fails if the committed one differs.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
D=$WORK/aosp-carrierconfig
retry() { local i; for i in 1 2 3 4 5; do "$@" && return 0; sleep $((i * 4)); done; return 1; }

fetch() { # fetch <url> <commit> <dir>: a blobless repository with that commit
    [ -d "$3/.git" ] || { git init -q "$3" && git -C "$3" remote add origin "$1"; }
    git -C "$3" cat-file -e "$2^{commit}" 2>/dev/null \
        || retry git -C "$3" fetch -q --depth 1 --filter=blob:none origin "$2"
}
export_tree() { # export_tree <repo> <commit> <path> <out dir>
    rm -rf "$4" && mkdir -p "$4"
    git -C "$1" archive "$2" "$3" | tar -x -C "$4"
}
fetch "$AOSP_CARRIERCONFIG_URL" "$AOSP_CARRIERCONFIG_A15_COMMIT" "$D/cc"
fetch "$AOSP_CARRIERCONFIG_URL" "$AOSP_CARRIERCONFIG_A17_COMMIT" "$D/cc"
fetch "$AOSP_TELEPHONYPROVIDER_URL" "$AOSP_TELEPHONYPROVIDER_A15_COMMIT" "$D/tp"
fetch "$AOSP_TELEPHONYPROVIDER_URL" "$AOSP_TELEPHONYPROVIDER_A17_COMMIT" "$D/tp"
fetch "$AOSP_FRAMEWORKS_BASE_URL" "$AOSP_FRAMEWORKS_BASE_A15_COMMIT" "$D/fb"
export_tree "$D/cc" "$AOSP_CARRIERCONFIG_A15_COMMIT" assets "$D/a15"
export_tree "$D/cc" "$AOSP_CARRIERCONFIG_A17_COMMIT" assets "$D/a17"
L=assets/latest_carrier_id/carrier_list.textpb
git -C "$D/tp" show "$AOSP_TELEPHONYPROVIDER_A15_COMMIT:$L" > "$D/carrier_list-15.textpb"
git -C "$D/tp" show "$AOSP_TELEPHONYPROVIDER_A17_COMMIT:$L" > "$D/carrier_list-17.textpb"
git -C "$D/fb" show "$AOSP_FRAMEWORKS_BASE_A15_COMMIT:telephony/java/android/telephony/CarrierConfigManager.java" \
    > "$D/CarrierConfigManager-15.java"

OUT=$HERE/carrier/aosp17-carrierconfig-ims.xml
[ "${1:-}" = --check ] && OUT=$D/aosp17-carrierconfig-ims.xml
python3 "$HERE/tools/import-aosp-carrierconfig.py" "$D/a15/assets" "$D/a17/assets" \
    "$D/carrier_list-15.textpb" "$D/carrier_list-17.textpb" "$D/CarrierConfigManager-15.java" "$OUT"
if [ "${1:-}" = --check ]; then
    cmp "$OUT" "$HERE/carrier/aosp17-carrierconfig-ims.xml" \
        || { echo "carrier/aosp17-carrierconfig-ims.xml is stale: run tools/regen-aosp-carrierconfig.sh"; exit 1; }
    echo "carrier/aosp17-carrierconfig-ims.xml is current"
fi
