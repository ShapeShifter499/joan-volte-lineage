#!/usr/bin/env python3
"""The IMS part of what AOSP 17's CarrierConfig app changed since Android 15.

packages/apps/CarrierConfig ships each carrier's base config as assets
(carrier_config_carrierid_<id>_<name>.xml), and a LineageOS 22.2 phone
has Android 15's. Android 17's differ: carriers added (Brisanet, OXIO,
netplus.ch, Madar, Pivotel, ...) and IMS settings changed (TIM's Ut and
IPsec, ALIV's USSD and conference factory, Verizon's and Xfinity's hold
in IMS calls, ...). This writes, as CarrierConfig vendor.xml blocks, each
key that Android 17's asset sets differently from Android 15's, for the
keys the AOSP IMS stack reads (import-carrier-settings.py's keep()),
less those the every-SIM block sets for everyone anyway (VoLTE and Wi-Fi
calling offered, the VoLTE toggle):

- a key Android 17 sets: its Android 17 value;
- a key Android 17 no longer sets: Android 15's framework default, which
  is what Android 17 then uses.

Blocks carry both the carrier id and each MCC/MNC Android 15's carrier
database gives that id (a SIM gets an id only on those), so they match
exactly the SIMs whose asset CarrierConfig loads, and the zip can file
them by PLMN. A carrier Android 15's database does not know gets blocks
by its Android 17 MCC/MNC rules instead (only plain and IMSI-prefix rules
are supported). They go before the Pixel data in the vendor.xml region,
as the asset they stand for goes under it on a LineageOS Pixel build.

Usage: import-aosp-carrierconfig.py <Android 15 CarrierConfig assets>
           <Android 17 CarrierConfig assets> <Android 15 carrier_list.textpb>
           <Android 17 carrier_list.textpb> <Android 15 CarrierConfigManager.java>
           <out.xml>
"""
import importlib.util
import os
import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
PREFIX = 'carrier_config_carrierid_'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


imp = module('imp', 'import-carrier-settings.py')
mcc = module('mcc', 'make-carrier-config.py')
# The every-SIM block at the end of the region sets these for every SIM.
EVERY_SIM_KEYS = {k for k, _ in mcc.EVERY_SIM}


def wanted(key):
    return imp.keep(key) and key not in EVERY_SIM_KEYS


def carrier_list(path):
    """{id: (name, [attribute dicts])} from a carrier_list.textpb."""
    out = {}
    for blk in re.split(r'\ncarrier_id \{', open(path, encoding='utf-8').read()):
        m = re.search(r'canonical_id: (\d+)', blk)
        if not m:
            continue
        name = re.search(r'carrier_name: "([^"]*)"', blk)
        attrs = []
        for a in re.findall(r'carrier_attribute \{(.*?)\n  \}', blk, flags=re.S):
            attrs.append({field: re.findall(field + r': "([^"]*)"', a)
                          for field in ('mccmnc_tuple', 'gid1', 'gid2', 'spn', 'imsi_prefix_xpattern',
                                        'plmn', 'iccid_prefix', 'privilege_access_rule')})
        out[int(m.group(1))] = (name.group(1) if name else '', attrs)
    return out


def asset(path):
    """{key: element} of a carrier-id asset (one unfiltered bundle)."""
    root = ET.parse(path).getroot()
    blocks = root.findall('carrier_config') if root.tag == 'carrier_config_list' else [root]
    if len(blocks) != 1 or blocks[0].attrib:
        raise SystemExit(f'{path}: not a single unfiltered bundle')
    return {el.get('name'): el for el in blocks[0]}


def same(a, b):
    def norm(el):
        return re.sub(r'\s+', ' ', ET.tostring(el, encoding='unicode')).strip()
    return norm(a) == norm(b)


def defaults(ccm_java):
    """{key name: (type, literal)} from CarrierConfigManager's defaults.

    Nested classes (Ims, ImsVoice, ...) prefix their keys with their own
    KEY_PREFIX and fill their defaults in their own getDefaults(), so
    constants are resolved in the class they are used in.
    """
    src = open(ccm_java, encoding='utf-8').read()
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    token = re.compile(r'(?P<cls>\bclass\s+(?P<cname>\w+)[^{;]*\{)|(?P<open>\{)|(?P<close>\})'
                       r'|(?P<const>public static final String (?P<kname>\w+)\s*=\s*(?P<kexpr>[^;]+);)'
                       r'|(?P<put>(?:sDefaults|defaults)\s*\.\s*put(?P<kind>\w+)\s*\(\s*'
                       r'(?P<ref>[\w.]+)\s*,\s*(?P<value>.*?)\)\s*;)', re.S)
    stack, consts, puts = [], {}, []
    for m in token.finditer(src):
        if m.group('cls'):
            stack.append(m.group('cname'))
        elif m.group('open'):
            stack.append(None)
        elif m.group('close'):
            if stack:
                stack.pop()
        elif m.group('const'):
            cls = next((c for c in reversed(stack) if c), '')
            consts[(cls, m.group('kname'))] = m.group('kexpr')
        elif m.group('put'):
            cls = next((c for c in reversed(stack) if c), '')
            puts.append((cls, m.group('kind'), m.group('ref'), m.group('value').strip()))

    def resolve(cls, name):
        expr = consts.get((cls, name))
        if expr is None:
            return None
        text = ''.join(re.findall(r'"([^"]*)"', expr))
        if 'KEY_PREFIX' in expr:
            prefix = consts.get((cls, 'KEY_PREFIX'), '')
            text = ''.join(re.findall(r'"([^"]*)"', prefix)) + text
        return text

    out = {}
    for cls, kind, ref, value in puts:
        parts = ref.split('.')
        owner = parts[-2] if len(parts) >= 2 else cls
        name = resolve(owner, parts[-1]) or resolve('CarrierConfigManager', parts[-1])
        if name:
            out.setdefault(name, (kind, value))
    return out


def default_element(key, dflt):
    """The framework default of a key as a vendor.xml element, or None."""
    if key not in dflt:
        return None
    kind, value = dflt[key]
    if kind == 'Boolean' and value in ('true', 'false'):
        return f'<boolean name="{key}" value="{value}" />'
    if kind == 'Int' and re.fullmatch(r'-?\d+', value):
        return f'<int name="{key}" value="{value}" />'
    if kind == 'String' and re.fullmatch(r'"[^"]*"', value):
        return f'<string name="{key}">{escape(value[1:-1])}</string>'
    return None


def filters_for(cid, c15, c17):
    """[(attrs, note)]: the vendor.xml filters that match the SIMs with this id."""
    if cid in c15:
        return [({'cid': str(cid), 'mcc': t[:3], 'mnc': t[3:]}, None)
                for t in sorted({t for a in c15[cid][1] for t in a['mccmnc_tuple']})]
    if cid not in c17:
        raise SystemExit(f'carrier id {cid} is in neither carrier list')
    out = []
    for a in c17[cid][1]:
        extra = {}
        if a['imsi_prefix_xpattern']:
            if len(a['imsi_prefix_xpattern']) != 1:
                raise SystemExit(f'carrier id {cid}: several IMSI prefixes in one rule')
            extra['imsi'] = a['imsi_prefix_xpattern'][0].replace('x', '.') + '.*'
        if any(a[f] for f in ('gid1', 'gid2', 'spn', 'plmn', 'iccid_prefix',
                              'privilege_access_rule')):
            raise SystemExit(f'carrier id {cid}: a rule this cannot express in vendor.xml: {a}')
        for t in a['mccmnc_tuple']:
            out.append(({'mcc': t[:3], 'mnc': t[3:], **extra},
                        'not in Android 15\'s carrier database'))
    return out


def main(a15_dir, a17_dir, list15, list17, ccm_java, out):
    c15, c17 = carrier_list(list15), carrier_list(list17)
    dflt = defaults(ccm_java)
    blocks, carriers, keys = [], 0, set()
    for f in sorted(os.listdir(a17_dir)):
        m = re.match(PREFIX + r'(\d+)_(.*)\.xml$', f)
        if not m:
            continue
        cid, name = int(m.group(1)), m.group(2)
        new = asset(os.path.join(a17_dir, f))
        path15 = os.path.join(a15_dir, f)
        if not os.path.exists(path15):
            # Android 15 may name the same carrier's file differently.
            alt = [g for g in os.listdir(a15_dir) if g.startswith(f'{PREFIX}{cid}_')]
            path15 = os.path.join(a15_dir, alt[0]) if alt else None
        old = asset(path15) if path15 else {}
        values = []
        for key, el in new.items():
            if wanted(key) and (key not in old or not same(el, old[key])):
                el = ET.fromstring(ET.tostring(el, encoding='unicode'))
                imp.drop_nested(el)
                values.append(re.sub(r'\s+', ' ', ET.tostring(el, encoding='unicode')).strip())
                keys.add(key)
        for key in sorted(set(old) - set(new)):
            if wanted(key):
                el = default_element(key, dflt)
                if el is None:
                    raise SystemExit(f'{f}: {key} was dropped and its default is not simple')
                values.append(el)
                keys.add(key)
        if not values:
            continue
        carriers += 1
        for attrs, note in filters_for(cid, c15, c17):
            blocks.append((attrs, f'{name} (carrier id {cid})' + (f', {note}' if note else ''),
                           values))

    lines = ['<!-- aosp-ims: the IMS keys AOSP 17\'s CarrierConfig assets set differently',
             '     from Android 15\'s, per carrier id (and MCC/MNC, as Android 15\'s carrier',
             '     database assigns it). Goes before the Pixel data, as the asset it stands',
             f'     for goes under it. {len(blocks)} blocks, {carriers} carriers, {len(keys)} keys.',
             '     Generated by aosp-ims/tools/import-aosp-carrierconfig.py. -->']
    for attrs, comment, values in blocks:
        head = ''.join(f' {k}={quoteattr(v)}' for k, v in attrs.items())
        lines.append(f'    <!-- {escape(comment)} -->')
        lines.append(f'    <carrier_config{head}>')
        lines += ['        ' + v for v in values]
        lines.append('    </carrier_config>')
    with open(out, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(lines) + '\n')
    print(f'{out}: {len(blocks)} blocks, {carriers} carriers, keys: {", ".join(sorted(keys))}')


if __name__ == '__main__':
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
