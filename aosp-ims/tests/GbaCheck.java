/*
 * Copyright (C) 2026 The joan-volte-lineage authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.imsstack.gba;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Host check of GbaBootstrap (ImsStack 0008), the GBA_ME client of the
 * GBA service: digest arithmetic, the UICC's answers, the NAF key, and
 * whole bootstraps against a BSF run here, which checks the AKAv1-MD5
 * digest with its own arithmetic. Run by tools/build-apk.sh.
 */
public final class GbaCheck {
    private static int sCases;
    private static final String IMPI = "001010123456789@ims.mnc001.mcc001.3gppnetwork.org";
    private static final byte[] RES = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final byte[] CK = range(0, 16);
    private static final byte[] IK = range(16, 32);
    private static final byte[] RAND = range(32, 48);
    private static final byte[] AUTN = range(48, 64);
    private static final byte[] AUTS = range(64, 78);

    private static byte[] range(int from, int to) {
        byte[] b = new byte[to - from];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (from + i);
        }
        return b;
    }

    private static void check(boolean ok, String what) {
        sCases++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static String md5hex(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("MD5").digest(b)) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** The BSF's own digest check: RFC 2617 with qop auth-int over an empty body. */
    private static String expected(Map<String, String> a, byte[] password) throws Exception {
        byte[] p = (a.get("username") + ":" + a.get("realm") + ":").getBytes(StandardCharsets.UTF_8);
        byte[] a1 = new byte[p.length + password.length];
        System.arraycopy(p, 0, a1, 0, p.length);
        System.arraycopy(password, 0, a1, p.length, password.length);
        String ha2 = md5hex(("GET:" + a.get("uri") + ":" + md5hex(new byte[0]))
                .getBytes(StandardCharsets.UTF_8));
        return md5hex((md5hex(a1) + ":" + a.get("nonce") + ":" + a.get("nc") + ":"
                + a.get("cnonce") + ":" + a.get("qop") + ":" + ha2).getBytes(StandardCharsets.UTF_8));
    }

    /** A BSF: challenges, checks the answer, and hands out a B-TID. */
    private static final class Bsf {
        final HttpServer server;
        final List<String> seen = new ArrayList<>();
        final boolean wantResync;
        int challenges;

        Bsf(boolean wantResync) throws IOException {
            this.wantResync = wantResync;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        URL url() throws Exception {
            return new URL("http", "127.0.0.1", server.getAddress().getPort(), "/");
        }

        private void challenge(HttpExchange x) throws IOException {
            challenges++;
            byte[] nonce = new byte[40];
            System.arraycopy(RAND, 0, nonce, 0, 16);
            System.arraycopy(AUTN, 0, nonce, 16, 16);
            nonce[39] = (byte) challenges;  // server-specific data
            x.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"127.0.0.1\", nonce=\""
                    + Base64.getEncoder().encodeToString(nonce) + "\", algorithm=AKAv1-MD5, "
                    + "qop=\"auth-int\", opaque=\"op,aque\"");
            x.sendResponseHeaders(401, -1);
            x.close();
        }

        private void handle(HttpExchange x) throws IOException {
            try {
                String auth = x.getRequestHeaders().getFirst("Authorization");
                seen.add(x.getRequestHeaders().getFirst("User-Agent") + " | " + auth);
                Map<String, String> a = GbaBootstrap.parseChallenge(auth);
                if (a.getOrDefault("response", "").isEmpty()) {
                    check(IMPI.equals(a.get("username")) && "/".equals(a.get("uri")),
                            "first request: " + auth);
                    challenge(x);
                    return;
                }
                check("op,aque".equals(a.get("opaque")) && "auth-int".equals(a.get("qop"))
                        && "AKAv1-MD5".equals(a.get("algorithm")), "answer params: " + auth);
                if (a.containsKey("auts")) {
                    check(wantResync && java.util.Arrays.equals(AUTS,
                            Base64.getDecoder().decode(a.get("auts"))), "auts: " + auth);
                    check(expected(a, new byte[0]).equals(a.get("response")),
                            "resync response over an empty password");
                    challenge(x);
                    return;
                }
                if (!expected(a, RES).equals(a.get("response"))) {
                    x.sendResponseHeaders(403, -1);
                    x.close();
                    return;
                }
                byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<BootstrappingInfo "
                        + "xmlns=\"uri:3gpp-gba\"><btid>B-TID@127.0.0.1</btid>"
                        + "<lifetime>2099-01-02T03:04:05Z</lifetime></BootstrappingInfo>")
                        .getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, body.length);
                try (OutputStream o = x.getResponseBody()) {
                    o.write(body);
                }
            } catch (Throwable t) {
                seen.add("SERVER ERROR " + t);
                x.sendResponseHeaders(500, -1);
                x.close();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // RFC 2617 3.5's example request-digest.
        check("6629fae49393a05397450978507c4ef1".equals(GbaBootstrap.digestResponse("Mufasa",
                "testrealm@host.com", "Circle Of Life".getBytes(StandardCharsets.UTF_8), "GET",
                "/dir/index.html", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b",
                "auth")), "RFC 2617 example");

        Map<String, String> ch = GbaBootstrap.parseChallenge(
                "Digest realm=\"bsf.example.com\", nonce=\"a\\\"b\", qop=\"auth,auth-int\", "
                        + "algorithm=AKAv1-MD5");
        check("bsf.example.com".equals(ch.get("realm")) && "a\"b".equals(ch.get("nonce"))
                && "auth,auth-int".equals(ch.get("qop")) && "AKAv1-MD5".equals(ch.get("algorithm")),
                "challenge: " + ch);

        // The UICC's answers (TS 31.102 7.1.2.1).
        byte[] db = new byte[1 + 1 + RES.length + 1 + 16 + 1 + 16];
        int i = 0;
        db[i++] = (byte) 0xdb;
        db[i++] = (byte) RES.length;
        System.arraycopy(RES, 0, db, i, RES.length);
        i += RES.length;
        db[i++] = 16;
        System.arraycopy(CK, 0, db, i, 16);
        i += 16;
        db[i++] = 16;
        System.arraycopy(IK, 0, db, i, 16);
        GbaBootstrap.AkaResult r = GbaBootstrap.AkaResult.parse(db);
        check(java.util.Arrays.equals(RES, r.res) && java.util.Arrays.equals(CK, r.ck)
                && java.util.Arrays.equals(IK, r.ik) && r.auts == null, "DB parse");
        byte[] dc = new byte[2 + AUTS.length];
        dc[0] = (byte) 0xdc;
        dc[1] = (byte) AUTS.length;
        System.arraycopy(AUTS, 0, dc, 2, AUTS.length);
        check(java.util.Arrays.equals(AUTS, GbaBootstrap.AkaResult.parse(dc).auts), "DC parse");
        for (byte[] bad : new byte[][] {{(byte) 0x98, 0x62}, {(byte) 0xdb, 40, 1}}) {
            try {
                GbaBootstrap.AkaResult.parse(bad);
                check(false, "bad AKA answer accepted");
            } catch (GbaBootstrap.GbaException e) {
                check(e.reason == GbaBootstrap.GbaException.UNKNOWN, "bad AKA reason");
            }
        }

        // Ks_NAF (TS 33.220 Annex B), against a vector computed with Python's hmac.
        byte[] ks = new byte[32];
        System.arraycopy(CK, 0, ks, 0, 16);
        System.arraycopy(IK, 0, ks, 16, 16);
        check("273152e7675c40055af1395a22e6a563cbe29075e3a3d3462c55660e4ec54094".equals(
                GbaBootstrap.hex(GbaBootstrap.nafKey(ks, RAND, IMPI,
                        "xcap.ims.mnc001.mcc001.pub.3gppnetwork.org", new byte[] {1, 0, 0, 0, 2}))),
                "Ks_NAF vector");

        GbaBootstrap.Opener direct = url -> (HttpURLConnection) url.openConnection();

        // A whole bootstrap.
        Bsf bsf = new Bsf(false);
        try {
            GbaBootstrap.Bootstrapped b = GbaBootstrap.bootstrap(bsf.url(), IMPI,
                    (rand, autn) -> {
                        check(java.util.Arrays.equals(RAND, rand)
                                && java.util.Arrays.equals(AUTN, autn), "RAND/AUTN from nonce");
                        return GbaBootstrap.AkaResult.success(RES, CK, IK);
                    }, direct);
            check("B-TID@127.0.0.1".equals(b.btid), "btid " + b.btid);
            check(b.expiresMillis == java.time.Instant.parse("2099-01-02T03:04:05Z").toEpochMilli(),
                    "lifetime");
            check(java.util.Arrays.equals(ks, b.ks) && java.util.Arrays.equals(RAND, b.rand), "Ks");
            check(bsf.seen.size() == 2 && bsf.seen.get(0).startsWith(GbaBootstrap.USER_AGENT)
                    && bsf.seen.get(0).contains("3gpp-gba"), "requests: " + bsf.seen);
        } finally {
            bsf.server.stop(0);
        }

        // Out of sequence: AUTS, a fresh challenge, then the answer.
        Bsf resync = new Bsf(true);
        try {
            int[] calls = {0};
            GbaBootstrap.Bootstrapped b = GbaBootstrap.bootstrap(resync.url(), IMPI,
                    (rand, autn) -> calls[0]++ == 0 ? GbaBootstrap.AkaResult.syncFailure(AUTS)
                            : GbaBootstrap.AkaResult.success(RES, CK, IK), direct);
            check(calls[0] == 2 && resync.challenges == 2 && "B-TID@127.0.0.1".equals(b.btid),
                    "resync: " + resync.seen);
        } finally {
            resync.server.stop(0);
        }

        // A wrong RES: the BSF refuses, and that is not a network failure.
        Bsf refuse = new Bsf(false);
        try {
            GbaBootstrap.bootstrap(refuse.url(), IMPI,
                    (rand, autn) -> GbaBootstrap.AkaResult.success(new byte[] {9}, CK, IK), direct);
            check(false, "wrong RES accepted");
        } catch (GbaBootstrap.GbaException e) {
            check(e.reason == GbaBootstrap.GbaException.UNKNOWN, "refused: " + e.getMessage());
        } finally {
            refuse.server.stop(0);
        }

        // Nobody listening: a network failure, so the caller tries another network.
        Bsf gone = new Bsf(false);
        URL dead = gone.url();
        gone.server.stop(0);
        try {
            GbaBootstrap.bootstrap(dead, IMPI,
                    (rand, autn) -> GbaBootstrap.AkaResult.success(RES, CK, IK), direct);
            check(false, "no BSF, yet bootstrapped");
        } catch (GbaBootstrap.GbaException e) {
            check(e.reason == GbaBootstrap.GbaException.NETWORK, "unreachable: " + e.getMessage());
        }

        System.out.println("GBA_ME client: " + sCases + " checks against a local BSF, OK");
    }
}
