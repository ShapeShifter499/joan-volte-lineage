#!/usr/bin/env python3
"""Check the generated vendor.xml blocks against the zip's gate.

For every SIM Android can tell apart -- each carrier id and specific
carrier id in carrier_list.textpb, on each of its PLMNs, plus every PLMN
in joan's map with no carrier id at all -- compute what CarrierImsGate
decides (carrier id, then PLMN, as in profileFor()) and what CarrierConfig
merges from the vendor.xml blocks (DefaultCarrierConfigService: every
block whose filters match, in document order, a cid filter matching the
carrier id or the specific carrier id). Wi-Fi calling and the ePDG
address must agree for all of them; VoLTE must be offered for all.

The blocks are parsed back out of the rendered XML, so this checks the
file a ROM would ship, not the generator's intermediate list.

With --patch, also checks that the device-tree patch
(upstream/aosp-ims/device/*.patch) carries exactly these blocks, so the
patch cannot drift from the data.

Usage: check-carrier-config.py <same five inputs as make-carrier-config.py>
           [--patch <device patch>]
"""
import importlib.util
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    'mcc', os.path.join(HERE, '..', 'tools', 'make-carrier-config.py'))
mcc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcc)


def parse(text):
    """[(attrs, {key: value})] from the rendered blocks."""
    root = ET.fromstring('<carrier_config_list>' + text + '</carrier_config_list>')
    out = []
    for cc in root.findall('carrier_config'):
        vals = {}
        for el in cc:
            if el.tag == 'boolean':
                vals[el.get('name')] = el.get('value') == 'true'
            elif el.tag == 'string':
                vals[el.get('name')] = el.text or ''
            elif el.tag == 'int-array':
                items = [int(i.get('value')) for i in el.findall('item')]
                assert len(items) == int(el.get('num')), el.get('name')
                vals[el.get('name')] = items
            else:
                raise AssertionError(f'unexpected <{el.tag}>')
        out.append((dict(cc.attrib), vals))
    return out


def matches(attrs, cid, specific, plmn):
    for k, v in attrs.items():
        if k == 'mcc' and v != plmn[:3]:
            return False
        if k == 'mnc' and v != plmn[3:]:
            return False
        if k == 'cid' and int(v) not in (cid, specific):
            return False
        if k not in ('mcc', 'mnc', 'cid', 'name'):
            return False
    return True


def patch_blocks(patch):
    """The generated region as the patch adds it to vendor.xml."""
    lines, inside = [], False
    for line in open(patch, encoding='utf-8').read().split('\n'):
        if not line.startswith('+') or line.startswith('+++'):
            inside = inside and not line.startswith('diff --git')
            continue
        body = line[1:]
        if body == mcc.BEGIN:
            inside = True
        if inside:
            lines.append(body)
        if body == mcc.END:
            inside = False
    return '\n'.join(lines) + '\n' if lines else ''


def main(argv):
    patch = None
    if '--patch' in argv:
        i = argv.index('--patch')
        patch = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if len(argv) != 5:
        raise SystemExit(__doc__)
    ids, plmns, wfc, epdg, carriers = mcc.load(*argv)
    text = mcc.render(mcc.blocks(ids, plmns, wfc, epdg, carriers))
    parsed = parse(text)
    if patch:
        if patch_blocks(patch) != text:
            print(f'MISMATCH {patch}: its vendor.xml blocks are not what '
                  'make-carrier-config.py generates now; regenerate the patch')
            return 1
        print(f'{os.path.basename(patch)}: vendor.xml blocks match the generator')

    textpb = open(argv[4], encoding='utf-8').read()
    tuples = {}
    for block in re.split(r'\ncarrier_id \{', textpb):
        m = re.search(r'canonical_id: (\d+)', block)
        if m:
            tuples[int(m.group(1))] = set(re.findall(r'mccmnc_tuple: "(\d+)"', block))

    sims = set()
    for c, (_, parent) in carriers.items():
        for plmn in tuples.get(c, ()):
            sims.add((parent if parent is not None else c, c, plmn))
    for plmn in plmns:
        sims.add((-1, -1, plmn))

    bad = 0
    wfc_on = 0
    for cid, specific, plmn in sorted(sims):
        # The gate: TelephonyManager.getSimCarrierId() only, then PLMN.
        profile = ids.get(str(cid)) if cid >= 0 else None
        if profile is None:
            profile = plmns.get(plmn)
        want_wfc = mcc.is_wfc(profile, wfc)
        want_epdg = epdg.get(mcc.operator_of(profile)) if want_wfc else None

        merged = {}
        for attrs, vals in parsed:
            if matches(attrs, cid, specific, plmn):
                merged.update(vals)
        got_wfc = merged.get(mcc.KEY_WFC, False)
        got_epdg = merged.get(mcc.KEY_EPDG_STATIC)
        ok = (merged.get(mcc.KEY_VOLTE) is True
              and merged.get(mcc.KEY_HIDE_4G) is False
              and merged.get(mcc.KEY_EDITABLE_4G) is True
              and got_wfc == want_wfc and got_epdg == want_epdg
              and (got_epdg is None) == (mcc.KEY_EPDG_PRIORITY not in merged))
        wfc_on += want_wfc
        if not ok:
            bad += 1
            print(f'MISMATCH cid={cid} specific={specific} plmn={plmn} profile={profile}: '
                  f'gate wfc={want_wfc} epdg={want_epdg}; vendor.xml wfc={got_wfc} epdg={got_epdg}')
    print(f'{len(sims)} SIM identities checked, {wfc_on} with Wi-Fi calling, '
          f'{len(parsed)} blocks: {"OK" if not bad else f"{bad} MISMATCHES"}')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
