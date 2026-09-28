/*
 * Copyright (C) 2026 The joan-volte-lineage authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.imsstack.joan;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * Host check of ApnPlan, the decisions of the zip's ImsApnGate: run by
 * tools/build-apk.sh against the compiled class.
 *
 * Usage: java -cp <check>:<classes> com.android.imsstack.joan.ApnPlanCheck [lineage-pixel-apns.xml]
 */
public final class ApnPlanCheck {
    private static int sCases;

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void check(boolean ok, String what) {
        sCases++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static String types(List<Map<String, String>> rows) {
        List<String> s = new ArrayList<>();
        for (Map<String, String> r : rows) {
            s.add(r.get("type"));
        }
        return String.join(" ", s);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> romInternet = row("_id", "1", "carrier", "Net", "apn", "internet",
                "type", "default,mms,supl", "numeric", "31048", "mcc", "310", "mnc", "48");
        Map<String, String> romIms = row("_id", "2", "carrier", "IMS", "apn", "ims",
                "type", "ims", "protocol", "IPV6", "roaming_protocol", "IPV6");
        Map<String, String> pixIms = row("carrier", "Carrier IMS", "mcc", "310", "mnc", "48",
                "apn", "vzwims", "type", "ims,ia", "protocol", "IPV4V6",
                "roaming_protocol", "IPV4V6", "bearer_bitmask", "14|20", "carrier_id", "10036");
        Map<String, String> pixXcap = row("carrier", "Carrier XCAP", "mcc", "310", "mnc", "48",
                "apn", "internet", "type", "xcap", "bearer_bitmask", "14|18|20");
        Map<String, String> pixSos = row("carrier", "SOS", "apn", "sos", "type", "emergency",
                "protocol", "IPV4V6", "roaming_protocol", "IPV4V6");

        // The ROM has IMS: only the missing XCAP is added, at no level.
        ApnPlan p = ApnPlan.plan(List.of(pixIms, pixXcap), List.of(romInternet, romIms));
        check(types(p.insert).equals("xcap") && p.delete.isEmpty(), "xcap only: " + p.insert);
        Map<String, String> x = p.insert.get(0);
        check(!x.containsKey("mcc") && !x.containsKey("carrier_id") && !x.containsKey("_id"),
                "no level keys: " + x);
        check(x.get("carrier").equals("Carrier XCAP" + ApnPlan.MARK)
                && "false".equals(x.get("user_visible")), "marked and hidden: " + x);
        check("14|18|20".equals(x.get("bearer_bitmask")), "xcap mask kept: " + x);

        // No IMS APN on the ROM: the Pixel's, IMS only, IWLAN allowed.
        p = ApnPlan.plan(List.of(pixIms, pixXcap), List.of(romInternet));
        check(types(p.insert).equals("ims xcap"), "ims and xcap: " + types(p.insert));
        Map<String, String> ims = p.insert.get(0);
        check("vzwims".equals(ims.get("apn")) && "14|18|20".equals(ims.get("bearer_bitmask")),
                "ims with iwlan: " + ims);

        // A Pixel APN that is exactly Android's own default is not added.
        p = ApnPlan.plan(List.of(pixSos), List.of(romInternet));
        check(p.insert.isEmpty(), "default-like sos skipped: " + p.insert);
        Map<String, String> pixSos6 = new LinkedHashMap<>(pixSos);
        pixSos6.put("protocol", "IPV6");
        p = ApnPlan.plan(List.of(pixSos6), List.of(romInternet));
        check(types(p.insert).equals("emergency"), "sos over IPv6 added: " + p.insert);

        // One Pixel row carrying two missing types: one row, both types.
        Map<String, String> both = row("carrier", "Both", "apn", "ims.x", "type", "ims,xcap");
        p = ApnPlan.plan(List.of(both), List.of(romInternet));
        check(p.insert.size() == 1 && "ims,xcap".equals(p.insert.get(0).get("type")),
                "merged: " + p.insert);

        // Read back as TelephonyProvider stores it, the row is left alone.
        Map<String, String> stored = row("_id", "9", "carrier", "Carrier IMS" + ApnPlan.MARK,
                "apn", "vzwims", "type", "ims", "protocol", "IPV4V6",
                "roaming_protocol", "IPV4V6", "bearer_bitmask", "14|18|20",
                "network_type_bitmask", "13|18|20", "carrier_id", "1839", "numeric", "");
        p = ApnPlan.plan(List.of(pixIms), List.of(romInternet, stored));
        check(p.insert.isEmpty() && p.delete.isEmpty(), "idempotent: " + p.insert + p.delete);

        // Once the ROM has its own IMS APN, ours is taken back.
        p = ApnPlan.plan(List.of(pixIms), List.of(romInternet, romIms, stored));
        check(p.insert.isEmpty() && p.delete.size() == 1 && "9".equals(p.delete.get(0).get("_id")),
                "taken back: " + p.delete);

        // No Pixel data any more: ours goes too.
        p = ApnPlan.plan(List.of(), List.of(romInternet, stored));
        check(p.delete.size() == 1, "no data, removed: " + p.delete);

        // The ROM's IMS APNs all leave IWLAN out: an IWLAN-only copy.
        Map<String, String> romImsLte = new LinkedHashMap<>(romIms);
        romImsLte.put("bearer_bitmask", "14|20");
        p = ApnPlan.plan(List.of(pixIms), List.of(romInternet, romImsLte));
        check(p.insert.size() == 1 && "18".equals(p.insert.get(0).get("bearer_bitmask"))
                && "ims".equals(p.insert.get(0).get("type"))
                && "ims".equals(p.insert.get(0).get("apn"))
                && p.insert.get(0).get("carrier").endsWith(" Wi-Fi" + ApnPlan.MARK),
                "wifi copy: " + p.insert);
        check(!p.insert.get(0).containsKey("_id"), "copy has no id: " + p.insert);

        // A "*" row is every type.
        Map<String, String> star = row("_id", "3", "carrier", "All", "apn", "all", "type", "*");
        p = ApnPlan.plan(List.of(pixIms, pixXcap), List.of(star));
        check(p.insert.isEmpty(), "star covers all: " + p.insert);

        // The real data: every network's rows, against a SIM with no APNs.
        if (args.length > 0) {
            NodeList apns = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new File(args[0])).getElementsByTagName("apn");
            Map<String, List<Map<String, String>>> byNet = new LinkedHashMap<>();
            for (int i = 0; i < apns.getLength(); i++) {
                NamedNodeMap at = ((Element) apns.item(i)).getAttributes();
                Map<String, String> r = new LinkedHashMap<>();
                for (int j = 0; j < at.getLength(); j++) {
                    r.put(at.item(j).getNodeName(), at.item(j).getNodeValue());
                }
                String net = r.get("mcc") + r.get("mnc") + "/" + r.getOrDefault("mvno_type", "")
                        + "/" + r.getOrDefault("mvno_match_data", "");
                byNet.computeIfAbsent(net, k -> new ArrayList<>()).add(r);
            }
            int nets = 0;
            int rows = 0;
            for (List<Map<String, String>> pix : byNet.values()) {
                p = ApnPlan.plan(pix, List.of());
                for (Map<String, String> r : p.insert) {
                    Set<String> t = ApnPlan.types(r);
                    check(!t.isEmpty() && Set.of(ApnPlan.TYPES).containsAll(t), "types " + r);
                    check(r.get("carrier").endsWith(ApnPlan.MARK), "marked " + r);
                    if (t.contains("ims") || t.contains("emergency")) {
                        check(ApnPlan.allowsIwlan(r), "iwlan " + r);
                    }
                }
                // Planning again against what was planned changes nothing.
                List<Map<String, String>> stored2 = new ArrayList<>();
                for (Map<String, String> r : p.insert) {
                    Map<String, String> s = new LinkedHashMap<>(r);
                    s.put("_id", "1");
                    stored2.add(s);
                }
                ApnPlan again = ApnPlan.plan(pix, stored2);
                check(again.insert.isEmpty() && again.delete.isEmpty(), "stable " + pix);
                nets++;
                rows += p.insert.size();
            }
            System.out.println("ImsApnGate plans: " + nets + " networks from the Pixel data, "
                    + rows + " rows for a SIM with no APNs");
        }
        System.out.println("ImsApnGate decisions: " + sCases + " checks, OK");
    }
}
