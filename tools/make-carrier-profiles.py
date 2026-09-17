#!/usr/bin/env python3
"""Distill stock LG carrier XMLs into joan carrier profiles.

Extract-only transcription of discovered configuration VALUES (facts) into
joan's own profile format. No LG files or code are shipped; run this tool
against a stock Ims6 APK's assets/Configuration tree and it emits
profiles.json keyed CARRIER.CC[.VARIANT].

Usage:
    tools/make-carrier-profiles.py <Configuration-dir> <out.json>
"""
import json
import re
import sys
from pathlib import Path

# table -> params we care about (name in xml -> profile key)
TABLES = {
    "lgims_sip": {
        "common_tcp_criterion_len": "tcp_criterion_len",
        "reg_expiration": "reg_expiration",
        "reg_methods": "reg_methods",
        "common_sip_features": "sip_features",
        "common_compact_form": "compact_form",
    },
    "lgims_com_sip": {
        # Whether this carrier's stock profile sends a User-Agent at all.
        # 87 of 169 leave the format empty and 82 set one, so omitting the
        # header is ordinary rather than unusual. China Mobile is among
        # those that get none. joan does not copy the vendor's string --
        # only whether a User-Agent is sent.
        "header_info_useragent_fmt": "user_agent_fmt",
    },
    "lgims_aosreg": {
        # per-profile params use <name> without the aos_reg_N_ prefix;
        # we keep profile 0 (normal) only for brevity.
        "aos_reg_0_tcp_criterion_length_ipv4": "reg_tcp_criterion_v4",
        "aos_reg_0_tcp_criterion_length_ipv6": "reg_tcp_criterion_v6",
        "aos_reg_0_retry_interval": "reg_retry_interval",
        "aos_reg_0_retry_base_time": "reg_retry_base_time",
        "aos_reg_0_retry_max_time": "reg_retry_max_time",
        "aos_reg_0_authentication_max_count ": "auth_max_count",
        "aos_reg_0_ipsec": "ipsec",
        "aos_reg_0_ipsec_algs": "ipsec_algs",
        "aos_reg_0_ipsec_spi_3gpp": "ipsec_spi_3gpp",
        "aos_reg_0_refresh_3gpp_standard": "refresh_3gpp_standard",
        "aos_reg_0_retry_pcscf_count": "retry_pcscf_count",
        "aos_reg_0_dscp_value": "dscp_value",
        "aos_reg_gba": "gba",
    },
    "lgims_aoscondition": {
        "aos_condition_0_pcscf_port": "pcscf_port",
        "aos_condition_0_multiple_discovery_scheme": "multiple_discovery_scheme",
        "aos_condition_0_pcscf_changed_control": "pcscf_changed_control",
        "aos_condition_0_isim_index_for_pcscf": "isim_index_for_pcscf",
        "aos_condition_0_wifi_rovein_threshold": "wifi_rovein_threshold",
        "aos_condition_0_wifi_roveout_threshold": "wifi_roveout_threshold",
        "aos_condition_0_lte_rovein_threshold": "lte_rovein_threshold",
        "aos_condition_0_lte_roveout_threshold": "lte_roveout_threshold",
    },
    "lgims_uc_session_voip": {
        "tConfURI": "conf_uri",
        "bReferSub": "refer_sub",
        "bConfSub": "conf_sub",
        "bConfSubInDialog": "conf_sub_in_dialog",
        "nMaxSess": "max_sessions",
        "nCW": "cw_type",
        "bUse180RPR": "use_180_rpr",
        "nOfferResCode": "offer_res_code",
        "eDTMFType": "dtmf_type",
        "bPR_Answer": "pr_answer",
        "bESCheckSOS": "es_check_sos",
        "nSRVCCSupportedType": "srvcc_supported_type",
        "nUETIMER_MO_NOANSWER": "t_mo_noanswer",
        "nUETIMER_MT_ALERTING": "t_mt_alerting",
        "nUETIMER_MO_UPDATE": "t_mo_update",
        "nUETIMER_MT_UPDATE": "t_mt_update",
    },
    "lgims_com_service_mmtel": {
        "mmtel_server_ip": "xcap_server",
        "mmtel_server_port": "xcap_port",
        "mmtel_tls": "xcap_tls",
        "mmtel_ussd_over_ims": "ussd_over_ims",
        "mmtel_provisioning_vowifi": "provisioning_vowifi",
        "mmtel_control_preference": "ut_control_preference",
        "mmtel_pdn": "xcap_pdn",
    },
}


def parse_table(body: str) -> dict:
    out = {}
    for m in re.finditer(r'<param name="([^"]+)" type="([^"]+)" value="([^"]*)"', body):
        name, typ, val = m.group(1), m.group(2), m.group(3)
        if typ == "INTEGER":
            if val == "":
                out[name] = None
            elif val.startswith("0x"):
                out[name] = int(val, 16)
            else:
                out[name] = int(val)
        else:
            out[name] = val
    return out


def coerce(v):
    if isinstance(v, bool):
        return v
    if v == "true":
        return True
    if v == "false":
        return False
    return v



def parse_codecs(media_path):
    """The ordered VoLTE audio offer from a media XML, or []."""
    if not media_path.exists():
        return []
    txt = media_path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'<table id="lgims_com_media_audio_codec_volte">(.*?)</table>',
                  txt, re.S)
    if not m:
        return []
    raw = {}
    for p in re.finditer(r'<param name="([^"]+)"[^>]*value="([^"]*)"', m.group(1)):
        raw.setdefault(p.group(1).strip(), p.group(2))
    out = []
    for i in range(16):
        ctype = raw.get(f"audiocodec_{i}_codec_type", "").strip()
        if not ctype or ctype == "None":
            continue
        try:
            pt = int(raw.get(f"audiocodec_{i}_payload_type", ""))
            rate = int(raw.get(f"audiocodec_{i}_sampling_rate", ""))
        except ValueError:
            continue
        # A static payload number for a dynamic codec is a mistake in the
        # source, and offering it would put a codec where something else
        # already means something on the wire.
        if not (96 <= pt <= 127):
            continue
        entry = {"type": ctype, "pt": pt, "rate": rate}
        oa = raw.get(f"AMR_{i}_octet_align", "").strip()
        if ctype == "AMR":
            entry["octet_align"] = oa == "1"
            ms = raw.get(f"AMR_{i}_default_rtp_modeset", "").strip()
            if ms:
                try:
                    entry["mode_set"] = [int(x) for x in ms.split(",") if x != ""]
                except ValueError:
                    pass
        out.append(entry)
    return out

def main() -> int:
    cfg_dir = Path(sys.argv[1])
    out_path = Path(sys.argv[2])
    profiles = {}
    seen = 0
    for xml in sorted(cfg_dir.rglob("configuration*.xml")):
        if xml.stem.endswith(".MOVED"):
            # LG's moved-marker stubs (KT/LGU/SKR) must not clobber the
            # real per-carrier file that shares the same profile key.
            continue
        rel = xml.relative_to(cfg_dir)
        parts = list(rel.parts)
        if len(parts) < 3:
            continue
        carrier, cc = parts[0], parts[1]
        variant = parts[2] if len(parts) == 4 else None
        txt = xml.read_text(encoding="utf-8", errors="replace")
        prof = {}
        for table, mapping in TABLES.items():
            m = re.search(r'<table id="' + table + r'">(.*?)</table>', txt, re.S)
            if not m:
                continue
            raw = parse_table(m.group(1))
            for src, dst in mapping.items():
                if src in raw:
                    prof[dst] = coerce(raw[src])
        if not prof:
            continue
        # The sibling media XML carries the VoLTE audio offer: which
        # codecs, on which payload types, in which AMR framing, with which
        # mode-set. Android's own carrier config supplies payload types and
        # framing on some networks but leaves the attribute bundles empty on
        # others, and it never supplied a mode-set on any network tested, so
        # this is the only source for that.
        media = xml.with_name(xml.name.replace("configuration", "media", 1))
        codecs = parse_codecs(media)
        if codecs:
            prof["codecs"] = codecs

        key = f"{carrier}.{cc}" + (f".{variant}" if variant else "")
        prof["carrier"] = carrier
        prof["cc"] = cc
        prof["variant"] = variant
        # source file provenance (fact-transcription trail)
        prof["_src"] = f"{xml.parent.name}/{xml.name}"
        profiles[key] = prof
        seen += 1
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(profiles, indent=1, sort_keys=True))
    print(f"wrote {out_path}: {seen} profiles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
