#!/bin/sh
# Merge Joan's Viettel 45204 overlay into a full apns-conf.xml.
# Usage: merge-viettel-apns.sh ORIG.xml OVERLAY.xml > merged.xml
#
# Drops every existing 452/04 <apn> from ORIG, then inserts OVERLAY's
# <apn> elements before </apns>. Other PLMNs are unchanged.
#
# TelephonyProvider loads ONE conf file (product wins over system/oem).
# Replacing product/etc/apns-conf.xml with the overlay alone would wipe
# the world list. Always merge.
set -eu

ORIG=${1:-}
OVERLAY=${2:-}
if [ -z "$ORIG" ] || [ -z "$OVERLAY" ]; then
    echo "usage: merge-viettel-apns.sh ORIG.xml OVERLAY.xml" >&2
    exit 2
fi
[ -f "$ORIG" ] || { echo "missing orig $ORIG" >&2; exit 1; }
[ -f "$OVERLAY" ] || { echo "missing overlay $OVERLAY" >&2; exit 1; }

OVER_TMP=${MERGE_TMP:-${TMP:-/tmp}/joan-viettel-apn-blocks.$$}
rm -f "$OVER_TMP"
trap 'rm -f "$OVER_TMP"' EXIT

# Collect overlay <apn> blocks. Busybox/toybox awk is not assumed.
in_apn=0
buf=""
n_over=0
saw_ims=0
saw_xcap=0
while IFS= read -r line || [ -n "$line" ]; do
    if [ "$in_apn" -eq 0 ]; then
        case "$line" in
            *"<apns"*)
                ;;
            *"<apn"*)
                in_apn=1
                buf=$line
                case "$line" in
                    *"/>"*|*"</apn>"*)
                        n_over=$((n_over + 1))
                        printf '%s\n' "$buf" >> "$OVER_TMP"
                        case "$buf" in
                            *'apn="ims"'*) saw_ims=1 ;;
                        esac
                        case "$buf" in
                            *'apn="xcap"'*) saw_xcap=1 ;;
                        esac
                        in_apn=0
                        buf=""
                        ;;
                esac
                ;;
        esac
        continue
    fi
    buf="$buf
$line"
    case "$line" in
        *"/>"*|*"</apn>"*)
            n_over=$((n_over + 1))
            printf '%s\n' "$buf" >> "$OVER_TMP"
            case "$buf" in
                *'apn="ims"'*) saw_ims=1 ;;
            esac
            case "$buf" in
                *'apn="xcap"'*) saw_xcap=1 ;;
            esac
            in_apn=0
            buf=""
            ;;
    esac
done < "$OVERLAY"

if [ "$n_over" -lt 1 ]; then
    echo "overlay contained no <apn> elements" >&2
    exit 1
fi
if [ "$saw_ims" -eq 0 ] || [ "$saw_xcap" -eq 0 ]; then
    echo "overlay missing Viettel IMS or XCAP/UT row" >&2
    exit 1
fi

# A row is ours if it is 452/04 however the ROM spelled the MNC.
# apns-conf.xml is not normalised: AOSP writes mnc="04", but an
# unpadded mnc="4" is equally valid XML and TelephonyProvider reads
# both as MNC 4. Matching only the padded form would leave the ROM's
# own Viettel rows in place beside ours, and TelephonyProvider would
# then have two candidates per type -- the duplicate-APN failure this
# whole merge exists to avoid.
is_viettel_45204() {
    case "$1" in
        *'mcc="452"'*) ;;
        *) return 1 ;;
    esac
    case "$1" in
        *'mnc="04"'*|*'mnc="4"'*) return 0 ;;
    esac
    return 1
}

in_apn=0
buf=""
inserted=0
while IFS= read -r line || [ -n "$line" ]; do
    if [ "$in_apn" -eq 0 ]; then
        case "$line" in
            *"<apns"*)
                printf '%s\n' "$line"
                continue
                ;;
            *"<apn"*)
                in_apn=1
                buf=$line
                case "$line" in
                    *"/>"*|*"</apn>"*)
                        if ! is_viettel_45204 "$buf"; then
                            printf '%s\n' "$buf"
                        fi
                        in_apn=0
                        buf=""
                        ;;
                esac
                continue
                ;;
            *"</apns>"*)
                printf '    <!-- joan-viettel-45204-begin -->\n'
                cat "$OVER_TMP"
                printf '    <!-- joan-viettel-45204-end -->\n'
                printf '%s\n' "$line"
                inserted=1
                continue
                ;;
        esac
        printf '%s\n' "$line"
        continue
    fi
    buf="$buf
$line"
    case "$line" in
        *"/>"*|*"</apn>"*)
            if ! is_viettel_45204 "$buf"; then
                printf '%s\n' "$buf"
            fi
            in_apn=0
            buf=""
            ;;
    esac
done < "$ORIG"

if [ "$in_apn" -ne 0 ]; then
    echo "unterminated <apn> in $ORIG" >&2
    exit 1
fi
if [ "$inserted" -eq 0 ]; then
    echo "no </apns> in $ORIG" >&2
    exit 1
fi
