#!/usr/bin/env python3
"""Import the VoLTE and Wi-Fi calling carrier config LineageOS ships for
Pixels, for the carriers joan's data lacks.

LineageOS builds Pixels without Google's CarrierSettings app. Instead
extract-utils converts the Pixel's CarrierSettings protobufs
(product/etc/CarrierSettings/*.pb, in TheMuppets' vendor repositories)
into a CarrierConfig vendor.xml with lineage/scripts/
carriersettings-extractor. That file is how a LineageOS Pixel gets IMS
settings for some 1300 carriers.

This runs the same converter code on the same inputs and keeps only what
IMS needs, one <carrier_config> block per carrier entry, with the
converter's own filters (mcc/mnc, and gid1, spn or imsi for MVNOs):

- the IMS namespaces AOSP's ImsStack, IWLAN and QNS read: ims.,
  imsvoice., imssms., imsss., imswfc., imsemergency., iwlan., qns., bsf.
  (SIP timers, codecs, Ut/XCAP, ePDG addresses and IKE proposals,
  LTE/Wi-Fi handover policy);
- the top-level VoLTE and Wi-Fi calling keys in KEEP_TOP.

Left out, deliberately:
- the generic pn_xx entries (1100 PLMNs sharing Google's defaults for
  unknown carriers): as vendor.xml blocks they would override the
  carrier-specific config AOSP's CarrierConfig ships for those PLMNs;
- anything naming a package (ImsService overrides would move IMS away
  from ImsStack), provisioning requirements (they would block VoLTE until
  a carrier app provisions it, and there is none), GBA-required (it gates
  VoLTE on the SIM), RCS, the VoLTE opt-out lock, and Wi-Fi calling on by
  default (it stays the user's choice);
- video and RTT, which are not enabled on joan.

The converter's own exclusions (package names, APN editing locks, the
Enhanced 4G toggle keys) apply first.

Usage: import-carrier-settings.py <carriersettings-extractor dir>
           <CarrierSettings dir> <source note> <out.xml>
"""
import importlib.util
import os
import sys
from glob import glob
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

KEEP_PREFIXES = ('ims.', 'imsvoice.', 'imssms.', 'imsss.', 'imswfc.', 'imsemergency.',
                 'iwlan.', 'qns.', 'bsf.')
KEEP_TOP = {
    # VoLTE and Wi-Fi calling availability and modes
    'carrier_volte_available_bool', 'carrier_wfc_ims_available_bool',
    'carrier_default_wfc_ims_mode_int', 'carrier_default_wfc_ims_roaming_mode_int',
    'editable_wfc_mode_bool', 'editable_wfc_roaming_mode_bool',
    'use_wfc_home_network_mode_in_roaming_network_bool',
    'enhanced_4g_lte_on_by_default_bool', 'enhanced_4g_lte_title_variant_int',
    # Wi-Fi calling presentation
    'wfc_spn_format_idx_int', 'wfc_data_spn_format_idx_int',
    'wfc_flight_mode_spn_format_idx_int', 'wfc_spn_use_root_locale',
    'wfc_operator_error_codes_string_array', 'wfc_carrier_name_override_by_pnn_bool',
    'show_wifi_calling_icon_in_status_bar_bool', 'notify_international_call_on_wfc_bool',
    'wifi_calls_can_be_hd_audio', 'allow_merge_wifi_calls_when_vowifi_off_bool',
    # The IMS PDN between LTE and Wi-Fi
    'iwlan_handover_policy_string_array', 'min_udp_port_4500_nat_timeout_sec_int',
    # IMS calls
    'ims_reasoninfo_mapping_string_array', 'ims_dtmf_tone_delay_int',
    'is_ims_conference_size_enforced_bool', 'ims_conference_size_limit_int',
    'support_ims_conference_call_bool', 'support_ims_conference_event_package_bool',
    'support_ims_conference_event_package_on_peer_bool',
    'support_manage_ims_conference_call_bool', 'support_add_conference_participants_bool',
    'support_adhoc_conference_calls_bool', 'local_disconnect_empty_ims_conference_bool',
    'allow_hold_in_ims_call', 'carrier_allow_transfer_ims_call_bool',
    'delay_ims_tear_down_until_call_end_bool', 'carrier_ussd_method_int',
    'support_emergency_sms_over_ims_bool',
    # Supplementary services over Ut/XCAP, and GBA for its authentication
    'carrier_supports_ss_over_ut_bool', 'gba_mode_int', 'gba_ua_security_organization_int',
    'gba_ua_security_protocol_int', 'gba_ua_tls_cipher_suite_int',
}
DROP_TOP = {
    'carrier_ims_gba_required_bool', 'carrier_allow_turnoff_ims_bool',
    'carrier_default_wfc_ims_enabled_bool', 'carrier_default_wfc_ims_roaming_enabled_bool',
}
DROP_SUBSTRINGS = ('package_override', 'provisioning', 'rcs')
GENERIC = {'pn_xx'}


def keep(key):
    if key in DROP_TOP or any(s in key for s in DROP_SUBSTRINGS):
        return False
    return key.startswith(KEEP_PREFIXES) or key in KEEP_TOP


def load_extractor(path):
    """The converter as a module: its protobuf classes and extract_elements()."""
    sys.path.insert(0, path)
    cwd = os.getcwd()
    os.chdir(path)  # it compiles its .proto files into its own directory
    try:
        spec = importlib.util.spec_from_file_location(
            'carriersettings_extractor', os.path.join(path, 'carriersettings_extractor.py'))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
    finally:
        os.chdir(cwd)
    return mod


def main(extractor_dir, pb_dir, source, out):
    cse = load_extractor(os.path.abspath(extractor_dir))
    carrier_list = cse.CarrierList()
    carrier_list.ParseFromString(open(os.path.join(pb_dir, 'carrier_list.pb'), 'rb').read())
    # Loaded the way the converter does: generic settings, then each
    # carrier's own file on top.
    settings = {}
    multi = cse.MultiCarrierSettings()
    multi.ParseFromString(open(os.path.join(pb_dir, 'others.pb'), 'rb').read())
    for s in multi.setting:
        settings[s.canonical_name] = s
    for f in sorted(glob(os.path.join(pb_dir, '*.pb'))):
        if os.path.basename(f) in ('carrier_list.pb', 'others.pb'):
            continue
        s = cse.CarrierSettings()
        s.ParseFromString(open(f, 'rb').read())
        settings[s.canonical_name] = s

    blocks, kept_keys, carriers = [], set(), set()
    for entry in carrier_list.entry:
        s = settings.get(entry.canonical_name)
        cid = entry.carrier_id[0]
        if s is None or entry.canonical_name in GENERIC or cid.mcc_mnc[:6] == '000000':
            continue
        el = ET.Element('carrier_config')
        el.set('mcc', cid.mcc_mnc[:3])
        el.set('mnc', cid.mcc_mnc[3:])
        for field in ('spn', 'imsi', 'gid1'):
            if cid.HasField(field):
                el.set(field, getattr(cid, field))
        full = ET.Element('carrier_config')
        for config in s.configs.config:
            cse.extract_elements(full, config)
        for child in full:
            if keep(child.get('name')):
                el.append(child)
                kept_keys.add(child.get('name'))
        if len(el):
            el.set('_name', entry.canonical_name)
            blocks.append(el)
            carriers.add(entry.canonical_name)

    lines = ['<!-- aosp-ims: VoLTE and Wi-Fi calling carrier config from the Pixel',
             '     CarrierSettings LineageOS converts for Pixels, IMS keys only.',
             f'     {escape(source)}',
             f'     {len(blocks)} blocks, {len(carriers)} carriers, {len(kept_keys)} keys.',
             '     Generated by aosp-ims/tools/import-carrier-settings.py. -->']
    for el in blocks:
        name = el.attrib.pop('_name')
        cse.indent(el, 1)
        el.tail = None
        text = ET.tostring(el, encoding='unicode')
        lines.append(f'    <!-- {escape(name)} -->')
        lines.append('    ' + text.rstrip())
    with open(out, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print(f'{out}: {len(blocks)} blocks, {len(carriers)} carriers, {len(kept_keys)} keys, '
          f'{os.path.getsize(out)} bytes')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
