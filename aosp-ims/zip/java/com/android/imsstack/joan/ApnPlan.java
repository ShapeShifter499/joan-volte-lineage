/*
 * Copyright (C) 2026 The joan-volte-lineage authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.imsstack.joan;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/*
 * What ImsApnGate adds to, or takes back from, a SIM's APNs: the IMS, XCAP
 * and emergency APNs the ROM gives it none of, from the Pixel carrier data
 * (the rules aosp-ims/tools/make-apns.py applies to a source build's list).
 * No Android types, so the build checks it on the host
 * (aosp-ims/tests/ApnPlanCheck.java).
 *
 * Rows are attribute maps named as in apns-conf.xml (carrier, apn, type,
 * protocol, bearer_bitmask and network_type_bitmask as "14|18" lists, ...).
 */
final class ApnPlan {
    static final String[] TYPES = {"ims", "xcap", "emergency"};
    /** Ends the name of every row the gate inserts. */
    static final String MARK = " [AOSP IMS]";
    /** TelephonyManager.NETWORK_TYPE_IWLAN, also the IWLAN radio technology. */
    static final String IWLAN = "18";

    // Wi-Fi calling is offered for every carrier, and Android's own default
    // IMS APN allows every bearer: the IMS and emergency APNs the gate adds
    // allow IWLAN too.
    private static final Set<String> WIFI_TYPES = Set.of("ims", "emergency");
    // Android 15 builds these when a SIM has no APN of the type
    // (DataProfileManager): "ims" and "sos", IPv4v6 both ways.
    private static final Map<String, String> DEFAULT_NAME = Map.of("ims", "ims", "emergency", "sos");
    private static final Set<String> DEFAULT_LIKE_KEYS = Set.of("carrier", "mcc", "mnc", "apn",
            "type", "protocol", "roaming_protocol", "carrier_id", "mvno_type", "mvno_match_data");
    // Where a row applies: the gate sets that itself.
    private static final Set<String> LEVEL_KEYS = Set.of("_id", "numeric", "mcc", "mnc",
            "mvno_type", "mvno_match_data", "carrier_id");

    /** Rows to insert: no level keys, the gate adds those. */
    final List<Map<String, String>> insert = new ArrayList<>();
    /** The gate's own rows to delete, each with its "_id". */
    final List<Map<String, String>> delete = new ArrayList<>();

    private ApnPlan() {}

    static Set<String> types(Map<String, String> row) {
        Set<String> out = new LinkedHashSet<>();
        String t = row.get("type");
        if (t != null) {
            for (String s : t.split(",")) {
                s = s.trim().toLowerCase(Locale.ROOT);
                if (s.equals("*")) {
                    out.addAll(List.of(TYPES));
                } else if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static boolean ours(Map<String, String> row) {
        String name = row.get("carrier");
        return name != null && name.endsWith(MARK);
    }

    static boolean allowsIwlan(Map<String, String> row) {
        for (String k : new String[] {"bearer_bitmask", "network_type_bitmask"}) {
            String mask = row.get(k);
            if (mask != null && !mask.isEmpty() && !List.of(mask.split("\\|")).contains(IWLAN)) {
                return false;
            }
        }
        return true;
    }

    static String withIwlan(String mask) {
        TreeSet<Integer> v = new TreeSet<>();
        for (String s : mask.split("\\|")) {
            v.add(Integer.parseInt(s.trim()));
        }
        v.add(Integer.parseInt(IWLAN));
        StringBuilder sb = new StringBuilder();
        for (int i : v) {
            sb.append(sb.length() == 0 ? "" : "|").append(i);
        }
        return sb.toString();
    }

    /** A Pixel APN that is exactly the default Android 15 builds by itself. */
    static boolean defaultLike(Map<String, String> row, String t) {
        String name = DEFAULT_NAME.get(t);
        if (name == null || !name.equalsIgnoreCase(row.get("apn"))
                || !"IPV4V6".equals(row.get("protocol"))
                || !"IPV4V6".equals(row.get("roaming_protocol"))) {
            return false;
        }
        return DEFAULT_LIKE_KEYS.containsAll(row.keySet());
    }

    /** The Pixel row as an APN of the given types only, hidden from the APN picker. */
    static Map<String, String> restricted(Map<String, String> row, Set<String> want) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (!LEVEL_KEYS.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder type = new StringBuilder();
        boolean wifi = false;
        for (String t : TYPES) {
            if (want.contains(t)) {
                type.append(type.length() == 0 ? "" : ",").append(t);
                wifi |= WIFI_TYPES.contains(t);
            }
        }
        out.put("type", type.toString());
        if (wifi) {
            for (String k : new String[] {"bearer_bitmask", "network_type_bitmask"}) {
                String mask = out.get(k);
                if (mask != null && !mask.isEmpty()) {
                    out.put(k, withIwlan(mask));
                }
            }
        }
        out.put("carrier", (row.getOrDefault("carrier", type.toString())) + MARK);
        out.put("user_visible", "false");
        return out;
    }

    /** An IWLAN-only copy of the ROM's own APN of type t. */
    static Map<String, String> wifiCopy(Map<String, String> row, String t) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : row.entrySet()) {
            String k = e.getKey();
            if (!LEVEL_KEYS.contains(k) && !k.equals("bearer_bitmask")
                    && !k.equals("network_type_bitmask") && !k.equals("type")) {
                out.put(k, e.getValue());
            }
        }
        out.put("carrier", row.getOrDefault("carrier", t) + " Wi-Fi" + MARK);
        out.put("type", t);
        out.put("bearer_bitmask", IWLAN);
        out.put("user_visible", "false");
        return out;
    }

    /**
     * What identifies a row the gate inserted, the same planned or read back:
     * TelephonyProvider fills in what a row leaves out ("IP" protocols, a
     * network type mask from the bearer mask), so only what it keeps as
     * given is compared.
     */
    static String signature(Map<String, String> row) {
        return String.join("/", String.valueOf(row.get("apn")).toLowerCase(Locale.ROOT),
                String.join(",", new TreeSet<>(types(row))),
                row.getOrDefault("protocol", "IP"), row.getOrDefault("roaming_protocol", "IP"),
                allowsIwlan(row) ? "iwlan" : "no-iwlan");
    }

    /**
     * @param pixel the Pixel's APNs for this SIM: its MVNO's rows if the
     *     Pixel list has rows for it, else its MCC/MNC's
     * @param device the APNs Android gives the SIM now (TelephonyProvider's
     *     filtered list), the gate's own included
     */
    static ApnPlan plan(List<Map<String, String>> pixel, List<Map<String, String>> device) {
        List<Map<String, String>> rom = new ArrayList<>();
        List<Map<String, String>> mine = new ArrayList<>();
        for (Map<String, String> r : device) {
            (ours(r) ? mine : rom).add(r);
        }
        List<Map<String, String>> want = new ArrayList<>();
        Map<Map<String, String>, Set<String>> fromPixel = new IdentityHashMap<>();
        List<Map<String, String>> order = new ArrayList<>();
        for (String t : TYPES) {
            List<Map<String, String>> romT = new ArrayList<>();
            for (Map<String, String> r : rom) {
                if (types(r).contains(t)) {
                    romT.add(r);
                }
            }
            if (!romT.isEmpty()) {
                if (WIFI_TYPES.contains(t) && romT.stream().noneMatch(ApnPlan::allowsIwlan)) {
                    want.add(wifiCopy(romT.get(0), t));
                }
                continue;
            }
            List<Map<String, String>> pixT = new ArrayList<>();
            boolean allDefault = true;
            for (Map<String, String> r : pixel) {
                if (types(r).contains(t)) {
                    pixT.add(r);
                    allDefault &= defaultLike(r, t);
                }
            }
            if (pixT.isEmpty() || allDefault) {
                continue;
            }
            for (Map<String, String> r : pixT) {
                if (!fromPixel.containsKey(r)) {
                    fromPixel.put(r, new LinkedHashSet<>());
                    order.add(r);
                }
                fromPixel.get(r).add(t);
            }
        }
        for (Map<String, String> r : order) {
            want.add(restricted(r, fromPixel.get(r)));
        }

        ApnPlan plan = new ApnPlan();
        Set<String> have = new LinkedHashSet<>();
        for (Map<String, String> r : mine) {
            have.add(signature(r));
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (Map<String, String> r : want) {
            if (wanted.add(signature(r)) && !have.contains(signature(r))) {
                plan.insert.add(r);
            }
        }
        for (Map<String, String> r : mine) {
            if (!wanted.contains(signature(r))) {
                plan.delete.add(r);
            }
        }
        return plan;
    }
}
