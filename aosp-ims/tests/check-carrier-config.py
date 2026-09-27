#!/usr/bin/env python3
"""Check the carrier config the AOSP IMS stack ships.

The region make-carrier-config.py generates is rendered, parsed back as
CarrierConfig would read it, and checked:

- every imported block carries only keys import-carrier-settings.py keeps
  (no ImsService package overrides, provisioning, GBA-required, opt-out
  lock or Wi-Fi-calling-on-by-default), and no EVS anywhere, not even
  inside a codec bundle: ImsMedia has no EVS codec;
- the last block is the filterless every-SIM block;
- for every SIM Android can tell apart -- each carrier id and specific
  carrier id in carrier_list.textpb on each of its PLMNs, plus every PLMN
  in joan's map with no carrier id -- CarrierConfig's merge
  (DefaultCarrierConfigService: every matching block, in order; a cid
  filter matches the carrier id or the specific carrier id) offers VoLTE
  and Wi-Fi calling with a usable VoLTE toggle, and sets the ePDG address
  the rules say: the imported one where the carrier data has one, else
  CarrierImsGate's for the SIM's LG operator, else none. CarrierImsGate,
  which runs in the zip, then has nothing left to add.

The simulated SIMs carry no GID1, SPN or IMSI, so blocks filtered on
those (MVNOs in the imported data) are checked for keys only.

--patch <device patch>: the joan-common patch carries exactly the region
without imported data (apply-patches.sh adds that).

Usage: check-carrier-config.py <carrier-id-map.json> <carrier-plmn-map.json>
           <CarrierImsGate.java> <carrier_list.textpb> --imported <file>
           [--patch <device patch>]
"""
import importlib.util
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, '..', 'tools', path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


mcc = module('mcc', 'make-carrier-config.py')
imp = module('imp', 'import-carrier-settings.py')


def parse_blocks(text):
    """[(attrs, {key: value})] from carrier_config blocks, as CarrierConfig reads them."""
    root = ET.fromstring('<carrier_config_list>' + text + '</carrier_config_list>')
    out = []
    for cc in root.findall('carrier_config'):
        vals = {}
        for el in cc:
            name = el.get('name')
            if el.tag == 'boolean':
                vals[name] = el.get('value') == 'true'
            elif el.tag == 'string':
                vals[name] = el.text or ''
            elif el.tag in ('int-array', 'string-array', 'long-array'):
                items = [i.get('value') for i in el.findall('item')]
                assert len(items) == int(el.get('num')), name
                vals[name] = [int(i) for i in items] if el.tag == 'int-array' else items
            else:
                vals[name] = el.get('value', el.text)
        out.append((dict(cc.attrib), vals))
    return out


def region_of(text):
    s = text.index(mcc.BEGIN)
    e = text.index(mcc.END, s) + len(mcc.END)
    return text[s:e]


def matches(attrs, cid, specific, plmn):
    for k, v in attrs.items():
        if k == 'mcc' and v != plmn[:3]:
            return False
        if k == 'mnc' and v != plmn[3:]:
            return False
        if k == 'cid' and int(v) not in (cid, specific):
            return False
        if k in ('gid1', 'gid2', 'spn', 'imsi'):
            return False  # the simulated SIMs have none
        if k not in ('mcc', 'mnc', 'cid', 'name', 'gid1', 'gid2', 'spn', 'imsi'):
            raise AssertionError(f'unknown filter {k}')
    return True


def patch_region(patch):
    """The region as the device patch adds it to vendor.xml."""
    lines, inside = [], False
    for line in open(patch, encoding='utf-8').read().split('\n'):
        if not line.startswith('+') or line.startswith('+++'):
            continue
        body = line[1:]
        if body == mcc.BEGIN:
            inside = True
        if inside:
            lines.append(body)
        if body == mcc.END:
            inside = False
    return '\n'.join(lines)


def main(argv):
    opts = {}
    for flag in ('--imported', '--patch'):
        if flag in argv:
            i = argv.index(flag)
            opts[flag] = argv[i + 1]
            argv = argv[:i] + argv[i + 2:]
    if len(argv) != 4 or '--imported' not in opts:
        raise SystemExit(__doc__)
    ids, plmns, epdg, carriers = mcc.load(*argv)
    fails = []

    imported_text = open(opts['--imported'], encoding='utf-8').read()
    imported = parse_blocks(imported_text)
    bad_keys = sorted({k for _, vals in imported for k in vals if not imp.keep(k)})
    if bad_keys:
        fails.append(f'imported data carries keys the importer excludes: {bad_keys[:8]}')
    nested = sorted({e.get('name') for e in ET.fromstring('<l>' + imported_text + '</l>').iter()
                     if e.get('name') in imp.DROP_NESTED})
    if nested:
        fails.append(f'imported data carries nested keys the importer drops: {nested}')

    text = mcc.render(ids, plmns, epdg, carriers, opts['--imported'])
    blocks = parse_blocks(text)
    last_attrs, last_vals = blocks[-1]
    if last_attrs or last_vals != dict(mcc.EVERY_SIM):
        fails.append('the last block is not the filterless every-SIM block')

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

    n_imported_epdg = 0
    for cid, specific, plmn in sorted(sims):
        merged = {}
        for attrs, vals in blocks:
            if matches(attrs, cid, specific, plmn):
                merged.update(vals)
        want_epdg = None
        for attrs, vals in imported:
            if matches(attrs, cid, specific, plmn) and vals.get(mcc.KEY_EPDG_STATIC):
                want_epdg = vals[mcc.KEY_EPDG_STATIC]
        from_imported = bool(want_epdg)
        if from_imported:
            n_imported_epdg += 1
        else:
            # CarrierImsGate: carrier id, then PLMN, to an LG profile.
            profile = ids.get(str(cid)) if cid >= 0 else None
            if profile is None:
                profile = plmns.get(plmn)
            want_epdg = epdg.get(mcc.operator_of(profile)) if profile else None
        got = merged.get(mcc.KEY_EPDG_STATIC) or None
        # Our own addresses come with STATIC-first priority; the imported
        # data keeps whatever order the carrier's config gives (often the
        # default, PLMN first).
        ok = (all(merged.get(k) == v for k, v in mcc.EVERY_SIM)
              and got == want_epdg
              and (from_imported or (got is None) == (mcc.KEY_EPDG_PRIORITY not in merged)))
        if not ok:
            fails.append(f'cid={cid} specific={specific} plmn={plmn}: ePDG {got!r}, '
                         f'want {want_epdg!r}; every-SIM keys '
                         f'{[(k, merged.get(k)) for k, _ in mcc.EVERY_SIM]}')

    if '--patch' in opts:
        want = region_of(mcc.render(ids, plmns, epdg, carriers, None))
        if patch_region(opts['--patch']) != want:
            fails.append(f'{os.path.basename(opts["--patch"])}: its vendor.xml region is not '
                         'what make-carrier-config.py generates without imported data')
        else:
            print(f'{os.path.basename(opts["--patch"])}: region matches the generator')

    for f in fails[:40]:
        print('FAIL', f)
    print(f'{len(sims)} SIM identities, {n_imported_epdg} with an imported ePDG; '
          f'{len(imported)} imported blocks; {len(blocks)} region blocks: '
          f'{"OK" if not fails else f"{len(fails)} FAILURES"}')
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
