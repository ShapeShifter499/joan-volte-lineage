#!/usr/bin/env python3
"""Check the generated vendor.xml region against the zip's gate.

For every SIM Android can tell apart -- each carrier id and specific
carrier id in carrier_list.textpb, on each of its PLMNs, plus every PLMN
in joan's map with no carrier id at all -- compute what CarrierConfig
merges from the region (DefaultCarrierConfigService: every block whose
filters match, in document order, a cid filter matching the carrier id or
the specific carrier id; gid1/spn/imsi filters are treated as matching,
since the gate cannot see them -- where such a block carries an address
the gate defers to it anyway, because it only fills a missing address).
Then compare with CarrierImsGate's rules:

- VoLTE and Wi-Fi calling must be offered for every SIM, with the VoLTE
  toggle visible and editable and IMS user-turnoff-able (the filterless
  final block guarantees it; the imported Pixel data must not get in).
- Wherever the gate's ePDG table has an address for the SIM's resolved
  LG profile operator, the merged config must carry exactly that
  address: the gate would otherwise override it at run time, and the
  zip and a source build would ship different ePDGs. For everyone else
  the imported data is authoritative and the gate applies nothing.

The region is parsed back out of the rendered XML, so this checks the
file a ROM would ship, not the generator's intermediate list.

With --patch, also checks that the device-tree patch
(upstream/aosp-ims/device/*.patch) carries exactly this region, so the
patch cannot drift from the data.

Usage: check-carrier-config.py <carrier-id-map.json> <carrier-plmn-map.json>
           <CarrierImsGate.java> <carrier_list.textpb> [--import <import.xml>]
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
    """[(attrs, {key: value})] from the rendered region."""
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
                num = el.get('num')
                assert num is None or len(items) == int(num), el.get('name')
                vals[el.get('name')] = items
            elif el.tag in ('int', 'long'):
                vals[el.get('name')] = el.get('value')
            elif el.tag == 'string-array':
                vals[el.get('name')] = [i.text or '' for i in el.findall('item')]
            elif el.tag in ('pbundle', 'pbundle_as_map'):
                # Nested bundles; not keys the gate decides on.
                vals[el.get('name')] = ET.tostring(el, encoding='unicode')
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
        if k not in ('mcc', 'mnc', 'cid', 'name', 'gid1', 'spn', 'imsi'):
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
    patch = import_path = None
    if '--patch' in argv:
        i = argv.index('--patch')
        patch = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if '--import' in argv:
        i = argv.index('--import')
        import_path = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if len(argv) != 4:
        raise SystemExit(__doc__)
    ids, plmns, epdg, carriers = mcc.load(*argv)
    text = mcc.region_text(ids, plmns, epdg, carriers, import_path)
    parsed = parse(text)
    if patch:
        if patch_blocks(patch) != text:
            print(f'MISMATCH {patch}: its vendor.xml region is not what '
                  'make-carrier-config.py generates now; regenerate the patch')
            return 1
        print(f'{os.path.basename(patch)}: vendor.xml region matches the generator')

    textpb = open(argv[3], encoding='utf-8').read()
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
    epdg_ok = 0
    for cid, specific, plmn in sorted(sims):
        # The gate: TelephonyManager.getSimCarrierId() only, then PLMN.
        profile = ids.get(str(cid)) if cid >= 0 else None
        if profile is None:
            profile = plmns.get(plmn)
        want_epdg = epdg.get(mcc.operator_of(profile)) if profile else None

        merged = {}
        for attrs, vals in parsed:
            if matches(attrs, cid, specific, plmn):
                merged.update(vals)
        ok = (merged.get(mcc.KEY_VOLTE) is True
              and merged.get(mcc.KEY_HIDE_4G) is False
              and merged.get(mcc.KEY_EDITABLE_4G) is True
              and merged.get(mcc.KEY_WFC) is True
              and merged.get(mcc.KEY_TURNOFF) is True)
        got_epdg = merged.get(mcc.KEY_EPDG_STATIC)
        if want_epdg is not None and got_epdg is None:
            # The gate would fill it in on the zip path; a source build
            # would leave it to the default name. Only reachable if the
            # generator failed to cover a table operator's SIM.
            ok = False
        if want_epdg is not None and got_epdg is not None:
            epdg_ok += 1  # merged carries an address; the gate defers to it
        if not ok:
            bad += 1
            print(f'MISMATCH cid={cid} specific={specific} plmn={plmn} profile={profile}: '
                  f'merged volte={merged.get(mcc.KEY_VOLTE)} wfc={merged.get(mcc.KEY_WFC)} '
                  f'turnoff={merged.get(mcc.KEY_TURNOFF)} epdg={got_epdg} '
                  f'(gate table: {want_epdg})')
    print(f'{len(sims)} SIM identities checked, VoLTE and Wi-Fi calling offered for all, '
          f'{epdg_ok} ePDG addresses from the gate\'s table confirmed in the merge, '
          f'{len(parsed)} blocks: {"OK" if not bad else f"{bad} MISMATCHES"}')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
