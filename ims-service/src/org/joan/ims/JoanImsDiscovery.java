package org.joan.ims;

import android.net.LinkProperties;
import android.net.Network;
import android.telephony.TelephonyManager;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Shared driver/REGISTER discovery. No APN writes or subscriber logging.
 * LinkProperties is framework evidence, NOT a raw modem PCO capture.
 * Stock LG AoSPCSCF::GetFromISIM and AOSP getIsimPcscf establish the SIM
 * source.
 *
 * <p>A P-CSCF may arrive as a NAME rather than an address -- the ISIM's
 * EF_PCSCF commonly carries one. Names are resolved, but only on the IMS
 * {@link Network} and only behind a deadline; see {@link #resolveOn}.
 */
final class JoanImsDiscovery {
    private static final int MAX_PEERS = 10;
    private JoanImsDiscovery() {}

    static final class Pcscfs {
        final List<InetAddress> addresses;
        final String source, linkStatus, isimStatus;
        final int isimEntries;
        /** Entries that named a host instead of addressing one, in order. */
        final List<String> names;
        Pcscfs(List<InetAddress> a, String s, String link, String isim, int count) {
            this(a, s, link, isim, count, Collections.<String>emptyList());
        }
        Pcscfs(List<InetAddress> a, String s, String link, String isim, int count,
               List<String> hostNames) {
            addresses = Collections.unmodifiableList(new ArrayList<>(a));
            source = s; linkStatus = link; isimStatus = isim; isimEntries = count;
            names = Collections.unmodifiableList(new ArrayList<>(hostNames));
        }
        String summary() {
            return "source=" + source + " link_api=" + linkStatus
                    + " isim_api=" + isimStatus + " isim_entries=" + isimEntries
                    + " pcscf4=" + count(addresses, false)
                    + " pcscf6=" + count(addresses, true)
                    + (names.isEmpty() ? "" : " pcscf_names=" + names.size());
        }
    }

    /** How long a P-CSCF name may take before registration moves on. */
    static final int DNS_TIMEOUT_MS = 2000;

    static Pcscfs read(LinkProperties lp, TelephonyManager tm) {
        return read(lp, tm, null);
    }

    static Pcscfs read(LinkProperties lp, TelephonyManager tm, Network network) {
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
        if (result.addresses.isEmpty() && !result.names.isEmpty()
                && network != null) {
            List<InetAddress> resolved = new ArrayList<>();
            for (String name : result.names) {
                for (InetAddress a : resolveOn(network, name, DNS_TIMEOUT_MS)) {
                    add(resolved, a);
                }
                if (!resolved.isEmpty()) {
                    break;   /* the first name that answers is enough */
                }
            }
            if (!resolved.isEmpty()) {
                return new Pcscfs(resolved, "isim-dns", status, isimStatus,
                        result.isimEntries, result.names);
            }
        }
        return new Pcscfs(result.addresses, result.source, status,
                isimStatus, result.isimEntries, result.names);
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
        List<String> names = new ArrayList<>();
        if (isim != null) {
            for (int i = 0; i < Math.min(isim.length, MAX_PEERS); i++) {
                InetAddress a = literal(isim[i]);
                if (a != null) {
                    add(addresses, a);
                } else if (isHostname(isim[i]) && !names.contains(isim[i])
                        && names.size() < MAX_PEERS) {
                    names.add(isim[i]);
                }
            }
        }
        String src = !addresses.isEmpty() ? "isim"
                : (names.isEmpty() ? "none" : "isim-name");
        return new Pcscfs(addresses, src,
                status, isim == null ? "unread" : "ok",
                isim == null ? 0 : isim.length, names);
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

    /**
     * True for something that names a host rather than addressing one.
     *
     * <p>Deliberately strict, because the consequence of a false positive
     * is a DNS lookup for garbage. At least one letter is required, so a
     * dotted quad is never mistaken for a name, and anything carrying a
     * colon or a slash is rejected outright rather than half-parsed as a
     * host:port or a URI.
     */
    static boolean isHostname(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim();
        if (s.length() < 4 || s.length() > 255) {
            return false;
        }
        if (s.indexOf(':') >= 0 || s.indexOf('/') >= 0 || s.indexOf(' ') >= 0) {
            return false;
        }
        if (s.startsWith(".") || s.endsWith(".") || s.indexOf('.') < 0) {
            return false;
        }
        boolean letter = false;
        for (String label : s.split("\\.", -1)) {
            int n = label.length();
            if (n == 0 || n > 63) {
                return false;
            }
            if (label.charAt(0) == '-' || label.charAt(n - 1) == '-') {
                return false;
            }
            for (int i = 0; i < n; i++) {
                char c = label.charAt(i);
                boolean alpha = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
                if (alpha) {
                    letter = true;
                } else if (!((c >= '0' && c <= '9') || c == '-')) {
                    return false;
                }
            }
        }
        return letter;
    }

    /**
     * Resolve a P-CSCF name on the IMS network, never the default one.
     *
     * <p>{@code InetAddress.getByName} would use whatever resolver the
     * default network has, which on this device is the one serving
     * ordinary data -- a different network from the IMS PDN, with
     * different servers and no reason to know the carrier's internal
     * names. {@link Network#getAllByName} is the API that asks the right
     * resolver, and it is why this takes a Network rather than a hostname
     * alone.
     *
     * <p>It runs on a daemon thread behind a deadline because the
     * objection that kept names unsupported was a real one: a lookup on
     * this path would otherwise block registration behind the network's
     * DNS. AOSP answers the same problem the same way, resolving
     * asynchronously behind a retry timer
     * ({@code AosPcscf::IsAsyncDnsDiscovery}). We return what arrived in
     * time and let the caller continue; a slow resolver costs one
     * deadline, never the registration.
     */
    static List<InetAddress> resolveOn(final Network network,
                                       final String host, int timeoutMs) {
        final List<InetAddress> out = new ArrayList<>();
        if (network == null || !isHostname(host) || timeoutMs <= 0) {
            return out;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InetAddress[] r = network.getAllByName(host);
                    if (r == null) {
                        return;
                    }
                    synchronized (out) {
                        for (InetAddress a : r) {
                            if (usable(a) && !out.contains(a)
                                    && out.size() < MAX_PEERS) {
                                out.add(a);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    /* An unresolvable name is an ordinary outcome here. */
                }
            }
        }, "joan-ims-dns");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (out) {
            return new ArrayList<>(out);
        }
    }

    /**
     * Turn EF_PCSCF entries into addresses, resolving names if needed.
     *
     * <p>A card may store either, and TS 31.103 4.2.8 allows an FQDN
     * outright, so both are accepted through the same validation the
     * other sources use: a literal must parse, a name must satisfy
     * {@link #isHostname} and then resolve on the IMS network. Anything
     * that is neither is dropped rather than guessed at.
     */
    static List<InetAddress> fromCardPcscf(List<String> entries,
                                           Network network) {
        List<InetAddress> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (String e : entries) {
            InetAddress lit = literal(e);
            if (lit != null) {
                add(out, lit);
            } else if (isHostname(e)) {
                for (InetAddress a : resolveOn(network, e, DNS_TIMEOUT_MS)) {
                    add(out, a);
                }
            }
            if (out.size() >= MAX_PEERS) {
                break;
            }
        }
        return out;
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
