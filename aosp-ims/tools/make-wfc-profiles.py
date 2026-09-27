#!/usr/bin/env python3
"""List the LG carrier profiles that shipped VoWiFi, for CarrierImsGate.

LG's Ims6 app carries one configuration.<OP>.<CC>[.<variant>].xml per
carrier profile. A profile had Wi-Fi calling when setting_vowifi_enable is
1 (the VoWiFi setting is offered) or support_over_wifi is true (LG's
T-Mobile US profiles, whose Wi-Fi calling used a carrier-specific path and
leave the setting key at 0). An empty setting_vowifi_enable means unset,
i.e. off.

The profile keys are the ones joan's carrier maps resolve a SIM to
(ims-service/assets/carrier-id-map.json, carrier-plmn-map.json).

Usage: make-wfc-profiles.py <Ims6 assets/Configuration dir> <out.json>
"""
import glob
import json
import os
import re
import sys


def main(cfg_dir, out):
    on, total = [], 0
    for f in sorted(glob.glob(os.path.join(cfg_dir, '**', 'configuration.*.xml'),
                              recursive=True)):
        text = open(f, encoding='utf-8', errors='replace').read()

        def val(name):
            m = re.search(r'name="%s"[^>]*?value="([^"]*)"' % name, text)
            return m.group(1) if m else ''
        total += 1
        key = os.path.basename(f)[len('configuration.'):-len('.xml')]
        if val('setting_vowifi_enable') == '1' or val('support_over_wifi') == 'true':
            on.append(key)
    doc = {
        'source': 'LG Ims6 assets/Configuration (VS996 30d), setting_vowifi_enable=1 '
                  'or support_over_wifi=true',
        'profiles': sorted(set(on)),
    }
    with open(out, 'w') as f:
        json.dump(doc, f, indent=1)
        f.write('\n')
    print(f'{len(doc["profiles"])} of {total} LG profiles shipped VoWiFi -> {out}')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
