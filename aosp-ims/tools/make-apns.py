#!/usr/bin/env python3
"""The IMS APNs a LineageOS APN list lacks, from the Pixel APNs LineageOS converts.

carrier/lineage-pixel-apns.xml (import-carrier-apns.py) is the Pixel list.
For the three APN types IMS needs -- ims, xcap (Ut) and emergency -- this
finds the networks whose SIMs get none from a given LineageOS list, and
adds the Pixel's, following how Android 15's TelephonyProvider picks the
APNs of a SIM (getSubscriptionMatchingAPNList): the rows whose MVNO data
matches the SIM if there are any, else the rows of its MCC/MNC, and rows
that carry only a carrier id on top.

A row added at a more specific level than the one the ROM uses would hide
the ROM's own rows (an MVNO row for a SIM that had only MCC/MNC rows takes
its internet APN away), so a row is only added:
- for an MVNO the list already has rows for, as a row of that MVNO;
- for an MCC/MNC, as a row of that MCC/MNC, when every MVNO sharing those
  rows uses the same APN for that type, or none;
and only for the type it lacks. IMS and emergency APNs also allow IWLAN,
and a network whose IMS or emergency APNs all leave IWLAN out gets an
IWLAN-only copy: Wi-Fi calling is offered for every carrier, and Android's
own default IMS APN allows every bearer. Android 15 builds an IMS APN named "ims"
and an emergency APN named "sos" (IPv4v6) by itself when a SIM has none;
a Pixel APN that is exactly that is not added.

Modes:
  --vendor-apn DIR   the source build: compute against DIR/*.xml (a
                     LineageOS vendor/apn) and write DIR/aosp-ims.xml
  --against FILE     report what would be added to an assembled
                     apns-conf.xml (the ROM's /product/etc/apns-conf.xml)
  --assets DIR       the zip: the Pixel list split per MCC/MNC, for
                     ImsApnGate to apply on the phone against the APNs the
                     phone actually has

Usage: make-apns.py <lineage-pixel-apns.xml> (--vendor-apn DIR | --against FILE | --assets DIR)
"""
import collections
import glob
import os
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

TYPES = ('ims', 'xcap', 'emergency')
# Android 15 adds these when a SIM has no APN of the type (DataProfileManager).
FRAMEWORK_DEFAULT = {'ims': ('ims', 'IPV4V6'), 'emergency': ('sos', 'IPV4V6')}
OUR_FILE = 'aosp-ims.xml'


def rows_of(path):
    return [dict(a.attrib) for a in ET.parse(path).getroot().iter('apn')]


def types(row):
    t = {x.strip().lower() for x in (row.get('type') or '').split(',') if x.strip()}
    return set(TYPES) | t if '*' in t else t


def plmn(row):
    return (row.get('mcc') or '') + (row.get('mnc') or '')


def mvno(row):
    return (row.get('mvno_type') or '').lower(), (row.get('mvno_match_data') or '')


def mvno_covers(rom, sim):
    """Whether ROM MVNO rows keyed rom=(type, data) match the SIMs a Pixel keys
    as sim=(type, data), by ApnSettingUtils.mvnoMatches. Different kinds of
    match data cannot be compared and count as no match."""
    (rt, rd), (st, sd) = rom, sim
    if rt != st or not rd or not sd:
        return False
    if rt == 'gid':
        return sd.lower().startswith(rd.lower())
    if rt == 'spn':
        return sd.lower() == rd.lower()
    if rt == 'iccid':
        return sd.startswith(rd)
    if rt == 'imsi':
        return len(sd) >= len(rd) and all(r in 'xX' or r == s for r, s in zip(rd, sd))
    return False


def index(rows):
    """{plmn: {'mno': [rows], 'mvno': {(type, data): [rows]}}}; carrier-id-only
    rows (no MCC/MNC) are kept apart."""
    idx = collections.defaultdict(lambda: {'mno': [], 'mvno': collections.defaultdict(list)})
    cid_only = []
    for r in rows:
        if not plmn(r):
            cid_only.append(r)
        elif mvno(r)[0]:
            idx[plmn(r)]['mvno'][mvno(r)].append(r)
        else:
            idx[plmn(r)]['mno'].append(r)
    return idx, cid_only


def has(rows, t):
    return any(t in types(r) for r in rows)


def names(rows, t):
    return frozenset((r.get('apn') or '').lower() for r in rows if t in types(r))


IWLAN = '18'  # TelephonyManager.NETWORK_TYPE_IWLAN, as a bearer and network type
WIFI_TYPES = ('ims', 'emergency')


def allows_iwlan(row):
    masks = [row.get(k) for k in ('bearer_bitmask', 'network_type_bitmask') if row.get(k)]
    return all(IWLAN in m.split('|') for m in masks)


def with_iwlan(mask):
    return '|'.join(sorted(set(mask.split('|')) | {IWLAN}, key=int))


def restricted(row, want):
    """The row as an APN of the missing types only, hidden from the APN picker.
    IMS and emergency APNs also allow IWLAN: Wi-Fi calling is offered for
    every carrier, and Android's own default IMS APN allows every bearer."""
    out = {k: v for k, v in row.items() if k not in ('carrier_id',)}
    out['type'] = ','.join(t for t in TYPES if t in want)
    if any(t in want for t in WIFI_TYPES):
        for k in ('bearer_bitmask', 'network_type_bitmask'):
            if out.get(k):
                out[k] = with_iwlan(out[k])
    out['user_visible'] = 'false'
    return out


def wifi_copy(row, t):
    """An IWLAN-only copy of the ROM's own APN of type t, for a network whose
    APNs of that type all leave out IWLAN."""
    out = {k: v for k, v in row.items()
           if k not in ('carrier_id', 'bearer_bitmask', 'network_type_bitmask', 'type')}
    out['carrier'] = (row.get('carrier') or t) + ' Wi-Fi'
    out['type'] = t
    out['bearer_bitmask'] = IWLAN
    out['user_visible'] = 'false'
    out['_wifi_copy'] = '1'
    return out


def effective(idx, p, k):
    """The ROM rows a SIM of MCC/MNC p (and MVNO key k, or None) gets."""
    R = idx.get(p)
    if R is None:
        return []
    if k:
        covering = [r for rk, rows in R['mvno'].items() if mvno_covers(rk, k) for r in rows]
        if covering:
            return covering
    return R['mno']


def default_like(row, t):
    """A Pixel APN Android 15 would build by itself: its default IMS or
    emergency APN, IPv4v6 both ways, nothing else set."""
    if t not in FRAMEWORK_DEFAULT:
        return False
    name, proto = FRAMEWORK_DEFAULT[t]
    extra = set(row) - {'carrier', 'mcc', 'mnc', 'apn', 'type', 'protocol', 'roaming_protocol',
                        'carrier_id', 'mvno_type', 'mvno_match_data'}
    return ((row.get('apn') or '').lower() == name and row.get('protocol') == proto
            and row.get('roaming_protocol') == proto and not extra)


def needed(prows, t):
    """The Pixel rows of type t worth adding: none if Android's own default
    would be the same APN."""
    rows = [r for r in prows if t in types(r)]
    return [] if all(default_like(r, t) for r in rows) else rows


def additions(pixel, rom):
    """Rows to add to the rom list: only the missing types, at the level the
    SIM's rows already come from."""
    pix, _ = index(pixel)
    have, _ = index(rom)
    added = []
    for p in sorted(pix):
        P = pix[p]
        rom_mvno = have[p]['mvno'] if p in have else {}
        # Pixel MVNOs whose SIMs get the ROM's MCC/MNC rows.
        fallback = [k for k in P['mvno'] if not any(mvno_covers(rk, k) for rk in rom_mvno)]
        want = collections.defaultdict(set)
        for t in TYPES:
            rows = needed(P['mno'], t)
            if not rows or has(effective(have, p, None), t):
                continue
            mno_names = names(P['mno'], t)
            if any(has(P['mvno'][k], t) and names(P['mvno'][k], t) != mno_names
                   for k in fallback):
                continue  # an MVNO on the same rows uses another APN
            for r in rows:
                want[id(r)].add(t)
        added += [restricted(r, want[id(r)]) for r in P['mno'] if id(r) in want]
        for k, prows in sorted(P['mvno'].items()):
            if k in fallback:
                continue  # rows of its own would hide the MCC/MNC ones
            want = collections.defaultdict(set)
            for t in TYPES:
                if has(effective(have, p, k), t):
                    continue
                for r in needed(prows, t):
                    want[id(r)].add(t)
            added += [restricted(r, want[id(r)]) for r in prows if id(r) in want]
    # Networks that have an IMS or emergency APN, none of which allows IWLAN.
    after, _ = index(rom + added)
    for p in sorted(after):
        keys = [None] + [k for k in sorted(after[p]['mvno'])]
        for k in keys:
            rows = after[p]['mvno'][k] if k else after[p]['mno']
            for t in WIFI_TYPES:
                typed = [r for r in rows if t in types(r)]
                if typed and not any(allows_iwlan(r) for r in typed):
                    added.append(wifi_copy(typed[0], t))
    return added


def outcomes(pixel, rom, added):
    """For each network the Pixel list has an APN of a type for, what a SIM
    of it gets on the ROM once the rows are added."""
    pix, _ = index(pixel)
    before, _ = index(rom)
    after, _ = index(rom + added)
    out = collections.Counter()
    for p, P in pix.items():
        for k, prows in [(None, P['mno'])] + list(P['mvno'].items()):
            for t in TYPES:
                if not has(prows, t):
                    continue
                if has(effective(before, p, k), t):
                    out[(t, 'ROM has it')] += 1
                elif has(effective(after, p, k), t):
                    out[(t, 'added')] += 1
                elif not needed(prows, t):
                    out[(t, "Android's default is the same")] += 1
                elif k and not any(mvno_covers(rk, k) for rk in before[p]['mvno']):
                    out[(t, 'missing: MVNO on MCC/MNC rows with another APN')] += 1
                else:
                    out[(t, 'missing: MCC/MNC shared with MVNOs using another APN')] += 1
    return out


def render(rows, source):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!--',
           '    SPDX-FileCopyrightText: Google Inc',
           '    SPDX-FileCopyrightText: The LineageOS Project',
           '    SPDX-License-Identifier: Apache-2.0',
           '',
           '    IMS, XCAP and emergency APNs this list lacks, from the Pixel carrier',
           '    settings LineageOS converts. Generated by joan-volte-lineage',
           f'    aosp-ims/tools/make-apns.py against {source}.',
           '-->',
           '<apns version="8">']
    for r in rows:
        out.append('    <apn')
        out.extend(f'        {k}={quoteattr(v)}' for k, v in r.items() if not k.startswith('_'))
        out.append('    />')
    out.append('</apns>')
    return '\n'.join(out) + '\n'


def summary(added, outs):
    lines = []
    for t in TYPES:
        n = sum(1 for r in added if t in types(r))
        wifi = sum(1 for r in added if t in types(r) and r.get('_wifi_copy'))
        lines.append(f'{t}: {n} rows added' + (f' ({wifi} of them Wi-Fi-only copies)' if wifi else ''))
        for (tt, what), c in sorted(outs.items()):
            if tt == t:
                lines.append(f'    {c:5} networks: {what}')
    return '\n'.join(lines)


def main(argv):
    if len(argv) != 3 or argv[1] not in ('--vendor-apn', '--against', '--assets'):
        raise SystemExit(__doc__)
    pixel = rows_of(argv[0])
    mode, target = argv[1], argv[2]
    if mode == '--assets':
        by = collections.defaultdict(list)
        for r in pixel:
            if plmn(r):
                by[plmn(r)].append(r)
        os.makedirs(target, exist_ok=True)
        for p, rows in sorted(by.items()):
            with open(os.path.join(target, p + '.xml'), 'w', encoding='utf-8') as f:
                f.write('<apns>\n')
                for r in rows:
                    f.write('<apn ' + ' '.join(f'{k}={quoteattr(v)}' for k, v in r.items())
                            + '/>\n')
                f.write('</apns>\n')
        print(f'{target}: {len(by)} PLMN files, {sum(map(len, by.values()))} APNs')
        return
    if mode == '--vendor-apn':
        files = sorted(f for f in glob.glob(os.path.join(target, '*.xml'))
                       if os.path.basename(f) != OUR_FILE)
        if not files:
            raise SystemExit(f'{target}: no APN files')
        rom = [r for f in files for r in rows_of(f)]
        source = 'vendor/apn'
    else:
        rom = rows_of(target)
        source = os.path.basename(target)
    added = additions(pixel, rom)
    if mode == '--vendor-apn':
        with open(os.path.join(target, OUR_FILE), 'w', encoding='utf-8') as f:
            f.write(render(added, source))
        print(f'{os.path.join(target, OUR_FILE)}: {len(added)} APNs')
    print(summary(added, outcomes(pixel, rom, added)))


if __name__ == '__main__':
    main(sys.argv[1:])
