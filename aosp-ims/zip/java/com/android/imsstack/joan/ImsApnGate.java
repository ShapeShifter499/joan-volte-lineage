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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.Log;
import android.util.Xml;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

/*
 * Gives a SIM the IMS, XCAP (Ut) and emergency APNs the ROM has none of.
 *
 * LineageOS's generic APN list has IMS APNs for about 200 networks; the
 * Pixel carrier data LineageOS converts has them for about 1400, and XCAP
 * and emergency APNs for hundreds more (assets/joan/apns/<mcc><mnc>.xml,
 * from aosp-ims/tools/make-apns.py). Android 15 makes up an IMS APN named
 * "ims" and an emergency APN named "sos" when a SIM has none, which is wrong
 * wherever the carrier's are named otherwise (Verizon's MVNOs, for one),
 * and has no fallback for XCAP at all.
 *
 * What to add is decided by ApnPlan against the APNs Android actually gives
 * the SIM (TelephonyProvider's filtered list). Rows are added with only the
 * SIM's carrier id, which TelephonyProvider appends to whatever MCC/MNC or
 * MVNO rows the SIM gets: nothing the ROM gives it is hidden. They are
 * marked by name (ApnPlan.MARK), kept in step with the data, and taken back
 * once the ROM has an APN of their type. A source build gets the same APNs
 * in its vendor/apn instead (make-apns.py --vendor-apn).
 */
final class ImsApnGate {
    private static final String TAG = "ImsStackApnGate";
    private static final Uri FILTERED =
            Uri.withAppendedPath(Telephony.Carriers.SIM_APN_URI, "filtered/subId/");

    private ImsApnGate() {}

    static void apply(Context ctx, int subId, TelephonyManager tm) {
        try {
            String plmn = tm.getSimOperator();
            if (plmn == null || plmn.length() < 5) {
                return;
            }
            List<Map<String, String>> device = query(ctx, subId);
            if (device == null) {
                return;
            }
            ApnPlan plan = ApnPlan.plan(forSim(load(ctx, plmn), tm, plmn), device);
            if (plan.insert.isEmpty() && plan.delete.isEmpty()) {
                return;
            }
            ContentResolver cr = ctx.getContentResolver();
            for (Map<String, String> r : plan.delete) {
                cr.delete(ContentUris.withAppendedId(Telephony.Carriers.CONTENT_URI,
                        Long.parseLong(r.get("_id"))), null, null);
            }
            int cid = tm.getSimSpecificCarrierId();
            for (Map<String, String> r : plan.insert) {
                cr.insert(Telephony.Carriers.CONTENT_URI, values(r, cid, device, plmn));
            }
            Log.i(TAG, "sub " + subId + " " + plmn + " cid " + cid + ": added "
                    + describe(plan.insert) + ", removed " + describe(plan.delete));
        } catch (Throwable t) {
            Log.w(TAG, "apn gate sub " + subId + ": " + t);
        }
    }

    /** The Pixel's APNs for this SIM: its MVNO's rows if any match, else its MCC/MNC's. */
    static List<Map<String, String>> forSim(List<Map<String, String>> rows,
            TelephonyManager tm, String plmn) {
        List<Map<String, String>> mvno = new ArrayList<>();
        List<Map<String, String>> mno = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String type = r.get("mvno_type");
            if (type == null || type.isEmpty()) {
                mno.add(r);
            } else if (tm.matchesCurrentSimOperator(plmn, mvnoType(type),
                    r.get("mvno_match_data"))) {
                mvno.add(r);
            }
        }
        return mvno.isEmpty() ? mno : mvno;
    }

    private static int mvnoType(String type) {
        switch (type) {
            case "spn":
                return ApnSetting.MVNO_TYPE_SPN;
            case "imsi":
                return ApnSetting.MVNO_TYPE_IMSI;
            case "gid":
                return ApnSetting.MVNO_TYPE_GID;
            case "iccid":
                return ApnSetting.MVNO_TYPE_ICCID;
            default:
                return -1;
        }
    }

    static List<Map<String, String>> load(Context ctx, String plmn) {
        List<Map<String, String>> out = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("joan/apns/" + plmn + ".xml")) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG && "apn".equals(p.getName())) {
                    Map<String, String> r = new LinkedHashMap<>();
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        r.put(p.getAttributeName(i), p.getAttributeValue(i));
                    }
                    out.add(r);
                }
            }
        } catch (FileNotFoundException e) {
            // No Pixel APNs for this PLMN: the plan only takes back our own rows.
        } catch (Throwable t) {
            Log.w(TAG, "apns " + plmn + ": " + t);
        }
        return out;
    }

    /** The APNs Android gives the SIM, as DataProfileManager reads them. */
    private static List<Map<String, String>> query(Context ctx, int subId) {
        try (Cursor c = ctx.getContentResolver().query(
                Uri.withAppendedPath(FILTERED, String.valueOf(subId)), null, null, null, null)) {
            if (c == null) {
                return null;
            }
            List<Map<String, String>> out = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, String> r = new LinkedHashMap<>();
                put(r, "_id", c, Telephony.Carriers._ID);
                put(r, "carrier", c, Telephony.Carriers.NAME);
                put(r, "apn", c, Telephony.Carriers.APN);
                put(r, "type", c, Telephony.Carriers.TYPE);
                put(r, "numeric", c, Telephony.Carriers.NUMERIC);
                put(r, "mcc", c, Telephony.Carriers.MCC);
                put(r, "mnc", c, Telephony.Carriers.MNC);
                put(r, "mvno_type", c, Telephony.Carriers.MVNO_TYPE);
                put(r, "mvno_match_data", c, Telephony.Carriers.MVNO_MATCH_DATA);
                put(r, "carrier_id", c, Telephony.Carriers.CARRIER_ID);
                put(r, "protocol", c, Telephony.Carriers.PROTOCOL);
                put(r, "roaming_protocol", c, Telephony.Carriers.ROAMING_PROTOCOL);
                putMask(r, "bearer_bitmask", c, Telephony.Carriers.BEARER_BITMASK);
                putMask(r, "network_type_bitmask", c, Telephony.Carriers.NETWORK_TYPE_BITMASK);
                out.add(r);
            }
            return out;
        } catch (SecurityException e) {
            Log.w(TAG, "no access to the APN list (WRITE_APN_SETTINGS): " + e);
            return null;
        }
    }

    private static void put(Map<String, String> r, String key, Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i >= 0 && !c.isNull(i)) {
            r.put(key, c.getString(i));
        }
    }

    /** A bitmask column as the "a|b" list apns-conf.xml uses: bit n-1 for n. */
    private static void putMask(Map<String, String> r, String key, Cursor c, String column) {
        int i = c.getColumnIndex(column);
        int mask = (i >= 0 && !c.isNull(i)) ? c.getInt(i) : 0;
        if (mask == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int n = 1; n <= 32; n++) {
            if ((mask & (1 << (n - 1))) != 0) {
                sb.append(sb.length() == 0 ? "" : "|").append(n);
            }
        }
        r.put(key, sb.toString());
    }

    private static int mask(String list) {
        int m = 0;
        for (String s : list.split("\\|")) {
            m |= 1 << (Integer.parseInt(s.trim()) - 1);
        }
        return m;
    }

    static ContentValues values(Map<String, String> r, int cid,
            List<Map<String, String>> device, String plmn) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Carriers.NAME, r.get("carrier"));
        v.put(Telephony.Carriers.APN, r.get("apn"));
        v.put(Telephony.Carriers.TYPE, r.get("type"));
        for (String k : new String[] {"protocol", "roaming_protocol", "user", "password",
                "server", "proxy", "port"}) {
            if (r.get(k) != null) {
                v.put(k, r.get(k));
            }
        }
        if (r.get("authtype") != null) {
            v.put(Telephony.Carriers.AUTH_TYPE, Integer.parseInt(r.get("authtype")));
        }
        if (r.get("mtu") != null) {
            v.put(Telephony.Carriers.MTU, Integer.parseInt(r.get("mtu")));
        }
        if (r.get("bearer_bitmask") != null) {
            v.put(Telephony.Carriers.BEARER_BITMASK, mask(r.get("bearer_bitmask")));
        }
        if (r.get("network_type_bitmask") != null) {
            v.put(Telephony.Carriers.NETWORK_TYPE_BITMASK, mask(r.get("network_type_bitmask")));
        }
        v.put(Telephony.Carriers.CARRIER_ENABLED, 1);
        v.put(Telephony.Carriers.USER_VISIBLE, 0);
        if (cid != TelephonyManager.UNKNOWN_CARRIER_ID) {
            // Carrier id only: appended to the SIM's other rows, never in their place.
            v.put(Telephony.Carriers.CARRIER_ID, cid);
            v.put(Telephony.Carriers.NUMERIC, "");
            v.put(Telephony.Carriers.MCC, "");
            v.put(Telephony.Carriers.MNC, "");
            return v;
        }
        // No carrier id: the same MCC/MNC (and MVNO) as the ROM's own rows.
        for (Map<String, String> d : device) {
            if (!ApnPlan.ours(d) && d.get("numeric") != null && !d.get("numeric").isEmpty()) {
                for (String k : new String[] {"numeric", "mcc", "mnc", "mvno_type",
                        "mvno_match_data"}) {
                    v.put(k, d.getOrDefault(k, ""));
                }
                return v;
            }
        }
        v.put(Telephony.Carriers.NUMERIC, plmn);
        v.put(Telephony.Carriers.MCC, plmn.substring(0, 3));
        v.put(Telephony.Carriers.MNC, plmn.substring(3));
        return v;
    }

    private static String describe(List<Map<String, String>> rows) {
        List<String> s = new ArrayList<>();
        for (Map<String, String> r : rows) {
            s.add(r.get("apn") + "(" + r.get("type") + ")");
        }
        return s.isEmpty() ? "none" : String.join(" ", s);
    }
}
