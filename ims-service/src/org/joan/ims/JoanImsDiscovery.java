package org.joan.ims;

import android.net.LinkProperties;
import android.telephony.TelephonyManager;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Shared driver/REGISTER discovery. No DNS, APN writes, or subscriber logging.
 * LinkProperties is framework evidence, NOT a raw modem PCO capture.
 * Stock LG AoSPCSCF::GetFromISIM and AOSP getIsimPcscf establish the SIM
 * source; only literal addresses are supported in this conservative fallback.
 */
final class JoanImsDiscovery {
    private static final int MAX_PEERS = 10;
    private JoanImsDiscovery() {}

    static final class Pcscfs {
        final List<InetAddress> addresses;
        final String source, linkStatus, isimStatus;
        final int isimEntries;
        Pcscfs(List<InetAddress> a, String s, String link, String isim, int count) {
            addresses = Collections.unmodifiableList(new ArrayList<>(a));
            source = s; linkStatus = link; isimStatus = isim; isimEntries = count;
        }
        String summary() {
            return "source=" + source + " link_api=" + linkStatus
                    + " isim_api=" + isimStatus + " isim_entries=" + isimEntries
                    + " pcscf4=" + count(addresses, false)
                    + " pcscf6=" + count(addresses, true);
        }
    }

    static Pcscfs read(LinkProperties lp, TelephonyManager tm) {
        List<?> advertised = null;
        String status;
        try {
            Object v = lp.getClass().getMethod("getPcscfServers").invoke(lp);
            if (v instanceof List<?>) {
                advertised = (List<?>) v;
                status = advertised.isEmpty() ? "empty" : "ok";
            } else {
                status = v == null ? "null" : "unexpected";
            }
        } catch (NoSuchMethodException e) {
            status = "absent";
        } catch (Exception e) {
            status = "error";
        }
        Pcscfs link = selectPcscfs(advertised, status, null);
        if (!link.addresses.isEmpty() || tm == null) return link;
        String[] isim = null;
        String isimStatus;
        try {
            Object v = tm.getClass().getMethod("getIsimPcscf").invoke(tm);
            if (v instanceof String[]) {
                isim = (String[]) v;
                isimStatus = isim.length == 0 ? "empty" : "ok";
            } else {
                isimStatus = v == null ? "null" : "unexpected";
            }
        } catch (NoSuchMethodException e) {
            isimStatus = "absent";
        } catch (Exception e) {
            isimStatus = "error";
        }
        Pcscfs result = selectPcscfs(advertised, status, isim);
        return new Pcscfs(result.addresses, result.source, status,
                isimStatus, result.isimEntries);
    }

    static Pcscfs selectPcscfs(List<?> advertised, String status, String[] isim) {
        List<InetAddress> addresses = new ArrayList<>();
        if (advertised != null) {
            for (Object v : advertised) {
                if (v instanceof InetAddress) add(addresses, (InetAddress) v);
                if (addresses.size() == MAX_PEERS) break;
            }
        }
        if (!addresses.isEmpty()) return new Pcscfs(addresses, "link", status, "not_needed", 0);
        if (isim != null) {
            for (int i = 0; i < Math.min(isim.length, MAX_PEERS); i++) {
                add(addresses, literal(isim[i]));
            }
        }
        return new Pcscfs(addresses, addresses.isEmpty() ? "none" : "isim",
                status, isim == null ? "unread" : "ok", isim == null ? 0 : isim.length);
    }

    private static void add(List<InetAddress> list, InetAddress a) {
        if (usable(a) && !list.contains(a) && list.size() < MAX_PEERS) list.add(a);
    }

    /** Reject names before InetAddress: no accidental default-network lookup. */
    static InetAddress literal(String text) {
        if (text == null || text.isEmpty() || text.length() > 64) return null;
        String s = text;
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        try {
            InetAddress a;
            if (s.indexOf(':') >= 0 && s.matches("[0-9a-fA-F:.]+")) {
                a = InetAddress.getByName(s); // grammar permits IPv6 literals only
            } else if (s.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
                String[] parts = s.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    if (parts[i].length() > 1 && parts[i].startsWith("0")) return null;
                    int v = Integer.parseInt(parts[i]);
                    if (v > 255) return null;
                    bytes[i] = (byte) v;
                }
                a = InetAddress.getByAddress(bytes);
            } else return null;
            return usable(a) ? a : null;
        } catch (Exception e) { return null; }
    }

    static boolean usable(InetAddress a) {
        return (a instanceof Inet4Address || a instanceof Inet6Address)
                && !a.isAnyLocalAddress() && !a.isLoopbackAddress()
                && !a.isLinkLocalAddress() && !a.isMulticastAddress()
                && !"255.255.255.255".equals(a.getHostAddress());
    }

    static List<InetAddress> locals(LinkProperties lp) {
        List<InetAddress> out = new ArrayList<>();
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (usable(a) && !out.contains(a)) out.add(a);
        }
        return out;
    }

    static int count(List<? extends InetAddress> list, boolean v6) {
        int n = 0;
        for (InetAddress a : list) if ((a instanceof Inet6Address) == v6) n++;
        return n;
    }

    static InetAddress localFor(List<InetAddress> locals, InetAddress peer) {
        if (!usable(peer)) return null;
        for (InetAddress a : locals) {
            if (usable(a) && (a instanceof Inet6Address) == (peer instanceof Inet6Address)) return a;
        }
        return null;
    }

    static final class Plan {
        final InetAddress local;
        final List<InetAddress> peers;
        Plan(InetAddress a, List<InetAddress> p) {
            local = a; peers = Collections.unmodifiableList(p);
        }
    }

    /** Choose a usable source/peer family pair, IPv6 first, or its alternate.
     * The alternate exists only when BOTH families have local AND peer addresses.
     */
    static Plan plan(List<InetAddress> locals, List<InetAddress> peers, boolean alternate) {
        List<Plan> families = new ArrayList<>();
        for (boolean v6 : new boolean[]{true, false}) {
            InetAddress local = null;
            List<InetAddress> same = new ArrayList<>();
            for (InetAddress peer : peers) {
                if ((peer instanceof Inet6Address) != v6) continue;
                InetAddress a = localFor(locals, peer);
                if (a != null) { local = a; if (!same.contains(peer)) same.add(peer); }
            }
            if (local != null) families.add(new Plan(local, same));
        }
        int index = alternate ? 1 : 0;
        return families.size() > index ? families.get(index)
                : new Plan(null, new ArrayList<>());
    }

    /** AOSP returns Set<Integer>, not int[]. Empty can mean redaction. */
    static boolean matchesSubscription(Object ids, int sub) {
        if (sub < 0 || ids == null) return true;
        if (ids instanceof Set<?>) return ((Set<?>) ids).isEmpty() || ((Set<?>) ids).contains(sub);
        if (ids instanceof int[]) {
            int[] a = (int[]) ids;
            if (a.length == 0) return true;
            for (int v : a) if (v == sub) return true;
            return false;
        }
        return true; // opaque metadata; subscription-scoped request still applies
    }
}
