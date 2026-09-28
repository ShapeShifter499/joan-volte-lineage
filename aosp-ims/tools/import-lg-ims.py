#!/usr/bin/env python3
"""Carrier config from LG's own IMS profiles, for the networks the Pixel
data has nothing for.

The per-carrier IMS config comes from LineageOS's Pixel carrier settings
(import-carrier-settings.py, carrier/lineage-pixel-ims.xml). joan's LG
profiles (ims-service/assets: the IMS settings LG shipped on the V30 for
each carrier, and LG's PLMN map) also cover networks the Pixel data has
no block for at all: MTS, MegaFon, Beeline and Tele2 in Russia, Claro and
Movistar across Latin America, Vodacom, MTN, Cell C and Telkom in South
Africa, CSL and PCCW in Hong Kong, and others. For those PLMNs this
writes what LG's settings say where they differ from ImsStack's
defaults, in ImsStack's carrier config keys, for the fields whose
translation is exact and which the Pixel data confirms:

- ims.sip_over_ipsec_enabled_bool false where LG registers without IPsec
  (sec-agree). ImsStack would otherwise try IPsec first and fall back on
  the network's 420.
- carrier_ussd_method_int USSD_OVER_IMS_PREFERRED where LG sends USSD
  over IMS; otherwise Android sends it over CS.
- imsvoice.conference_factory_uri_string where LG names a conference
  factory other than the one ImsStack derives from the SIM's PLMN
  (TS 23.003's mmtel@conf-factory name), so that merging calls reaches
  the carrier's conference server.

Each rule is checked against the PLMNs both data sets cover, every time
this runs: where LG registers without IPsec the Pixel data turns it off,
where LG sends USSD over IMS the Pixel data prefers IMS, and where both
name a conference factory they agree. A rule whose agreement falls below
MIN_AGREEMENT stops the run.

LG's data has copy slips, so a conference URI is left out when it is an
unfilled template or names another country's network (a 3GPP name with
another MCC). Nothing else is taken: LG's IPsec algorithm masks only
narrow what the default offers, the Pixel data disagrees with LG's USSD
"no" and Ut choices as often as not, and SIP timers and codec payload
numbers are the network's or the stack's to settle.

Usage: import-lg-ims.py <carrier-profiles-full.json> <carrier-plmn-map.json>
           <lineage-pixel-ims.xml> <out.xml>
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

KEY_IPSEC = 'ims.sip_over_ipsec_enabled_bool'
KEY_USSD = 'carrier_ussd_method_int'
KEY_CONF = 'imsvoice.conference_factory_uri_string'
USSD_OVER_IMS_PREFERRED = 1
MIN_AGREEMENT = 0.85


def pixel_blocks(path):
    """{plmn: [(filtered, {key: value})]} of the Pixel data's blocks."""
    root = ET.fromstring('<l>' + open(path, encoding='utf-8').read() + '</l>')
    out = {}
    for b in root.findall('carrier_config'):
        vals = {}
        for el in b:
            if el.tag == 'boolean':
                vals[el.get('name')] = el.get('value') == 'true'
            elif el.tag == 'int':
                vals[el.get('name')] = int(el.get('value'))
            elif el.tag == 'string':
                vals[el.get('name')] = el.text or ''
        filtered = any(b.get(k) for k in ('gid1', 'gid2', 'spn', 'imsi'))
        out.setdefault(b.get('mcc') + b.get('mnc'), []).append((filtered, vals))
    return out


def norm_uri(u):
    return re.sub(r'^sips?:', '', (u or '').strip().lower())


def derived_conf_uri(plmn):
    """The conference factory ImsStack uses when the carrier names none."""
    return f'mmtel@conf-factory.ims.mnc{plmn[3:].zfill(3)}.mcc{plmn[:3]}.3gppnetwork.org'


def conf_uri(profile, plmn):
    """LG's conference factory for this PLMN, if it is one to keep."""
    u = (profile.get('conf_uri') or '').strip()
    if not u or '[' in u or ']' in u:
        return None
    m = re.search(r'\.mcc(\d{3})\.3gppnetwork\.org', u, re.I)
    if m and m.group(1) != plmn[:3]:
        return None
    if norm_uri(u) == derived_conf_uri(plmn):
        return None
    return u


def lg_values(profile, plmn):
    vals = []
    if profile.get('ipsec') is False:
        vals.append((KEY_IPSEC, False))
    if profile.get('ussd_over_ims') is True:
        vals.append((KEY_USSD, USSD_OVER_IMS_PREFERRED))
    uri = conf_uri(profile, plmn)
    if uri:
        vals.append((KEY_CONF, uri))
    return vals


def agreement(profiles, plmn_map, pixel):
    """{rule: (agree, total)} over the PLMNs whose Pixel block is unfiltered."""
    stats = {KEY_IPSEC: [0, 0], KEY_USSD: [0, 0], KEY_CONF: [0, 0]}
    for plmn, name in plmn_map.items():
        mno = [v for f, v in pixel.get(plmn, []) if not f]
        if not mno:
            continue
        px, p = mno[0], profiles[name]
        if p.get('ipsec') is False:
            stats[KEY_IPSEC][1] += 1
            stats[KEY_IPSEC][0] += px.get(KEY_IPSEC, True) is False
        if p.get('ussd_over_ims') is True:
            stats[KEY_USSD][1] += 1
            stats[KEY_USSD][0] += px.get(KEY_USSD) == USSD_OVER_IMS_PREFERRED
        if norm_uri(p.get('conf_uri')) and norm_uri(px.get(KEY_CONF)):
            stats[KEY_CONF][1] += 1
            stats[KEY_CONF][0] += norm_uri(p.get('conf_uri')) == norm_uri(px.get(KEY_CONF))
    return {k: tuple(v) for k, v in stats.items()}


def render(key, value):
    if isinstance(value, bool):
        return f'<boolean name="{key}" value="{str(value).lower()}" />'
    if isinstance(value, int):
        return f'<int name="{key}" value="{value}" />'
    return f'<string name="{key}">{escape(value)}</string>'


def main(profiles_json, plmn_json, pixel_xml, out):
    profiles = json.load(open(profiles_json, encoding='utf-8'))
    plmn_map = json.load(open(plmn_json, encoding='utf-8'))
    pixel = pixel_blocks(pixel_xml)

    stats = agreement(profiles, plmn_map, pixel)
    low = [k for k, (a, n) in stats.items() if not n or a / n < MIN_AGREEMENT]
    summary = ', '.join(f'{k} {a}/{n}' for k, (a, n) in stats.items())
    if low:
        raise SystemExit(f'LG and Pixel data disagree too often ({summary}): {low}')

    blocks = []
    for plmn in sorted(plmn_map):
        if plmn in pixel or not plmn.isdigit() or len(plmn) not in (5, 6):
            continue
        name = plmn_map[plmn]
        vals = lg_values(profiles[name], plmn)
        if vals:
            blocks.append((plmn, name, vals))

    lines = ['<!-- aosp-ims: carrier config from LG\'s IMS profiles for the V30, for PLMNs',
             '     the Pixel data (lineage-pixel-ims.xml) has no block for. Keys: IPsec off,',
             '     USSD over IMS, conference factory URI. Agreement with the Pixel data where',
             f'     both cover a PLMN: {escape(summary)}.',
             f'     {len(blocks)} blocks. Generated by aosp-ims/tools/import-lg-ims.py. -->']
    for plmn, name, vals in blocks:
        lines.append(f'    <!-- {escape(name)} (LG) -->')
        lines.append(f'    <carrier_config mcc="{plmn[:3]}" mnc="{plmn[3:]}">')
        for key, value in vals:
            lines.append('        ' + render(key, value))
        lines.append('    </carrier_config>')
    with open(out, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    counts = {k: sum(1 for _, _, v in blocks if any(key == k for key, _ in v)) for k in stats}
    print(f'{out}: {len(blocks)} blocks ({", ".join(f"{k} {n}" for k, n in counts.items())}); '
          f'agreement with Pixel: {summary}')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
