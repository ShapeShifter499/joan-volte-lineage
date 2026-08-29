package org.joan.ims;

/** Host tests for carrier-neutral Digest AKA + sec-agree selection. */
public final class TestJoanSip {
    private static int gFail;

    public static void main(String[] args) {
        testAkaV1();
        testAkaV2();
        testEspKeys();
        testSecAgreeSelect();
        testRegisterOffer();
        testImei();
        testGrantedExpires();
        testRefreshLead();
        testAdvertisedCapabilities();
        testCodecHonesty();
        if (gFail != 0) {
            System.out.println("FAIL " + gFail);
            System.exit(1);
        }
        System.out.println("ok   java sip/crypto tests");
    }

    private static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("ok   " + name);
        } else {
            System.out.println("FAIL " + name);
            gFail++;
        }
    }

    private static void testAkaV1() {
        byte[] res = JoanSipCrypto.hexBytes("00112233445566778899aabbccddeeff");
        String out = JoanSipCrypto.akaDigestResponseHex(
                "user@msg.pc.t-mobile.com",
                "msg.pc.t-mobile.com",
                "REGISTER",
                "sip:msg.pc.t-mobile.com",
                "dGVzdG5vbmNlMTIzNA==",
                res, "auth", "00000001", "cnonce01");
        check("803db8645c1631ba35e82bc29c8a26c3".equals(out),
                "aka v1 qop matches C/pmOS vector");

        byte[] res8 = JoanSipCrypto.hexBytes("0011223344556677");
        String out8 = JoanSipCrypto.akaDigestResponseHex(
                "user@msg.pc.t-mobile.com",
                "msg.pc.t-mobile.com",
                "REGISTER",
                "sip:msg.pc.t-mobile.com",
                "dGVzdG5vbmNlMTIzNA==",
                res8, "auth", "00000001", "cnonce01");
        check(!out.equals(out8), "aka 8-byte RES is not padded to 16");
    }

    private static void testAkaV2() {
        byte[] res = JoanSipCrypto.hexBytes("00112233445566778899aabbccddeeff");
        byte[] ik = JoanSipCrypto.hexBytes("f0e1d2c3b4a5968778695a4b3c2d1e0f");
        byte[] ck = JoanSipCrypto.hexBytes("0f0e0d0c0b0a09080706050403020100");
        String v2 = JoanSipCrypto.akaDigestResponseHex(
                "user@ims.example.net",
                "ims.example.net",
                "REGISTER",
                "sip:ims.example.net",
                "dGVzdG5vbmNlMTIzNA==",
                res, "auth", "00000001", "cnonce01",
                "AKAv2-MD5", ck, ik);
        check("d065baafeb7ebe257f5a26891df2a9f3".equals(v2),
                "aka v2 RFC 4169 HMAC-MD5 password");
        String v1 = JoanSipCrypto.akaDigestResponseHex(
                "user@ims.example.net",
                "ims.example.net",
                "REGISTER",
                "sip:ims.example.net",
                "dGVzdG5vbmNlMTIzNA==",
                res, "auth", "00000001", "cnonce01",
                "AKAv1-MD5", ck, ik);
        check(!v1.equals(v2), "aka v2 differs from v1 with same RES");
        boolean threw = false;
        try {
            JoanSipCrypto.akaDigestResponseHex(
                    "u", "r", "REGISTER", "sip:r", "n", res,
                    "auth", "00000001", "c", "AKAv2-SHA-256", ck, ik);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "unknown AKA algorithm is refused");
    }

    private static void testEspKeys() {
        byte[] ik = new byte[16];
        byte[] ck = new byte[16];
        for (int i = 0; i < 16; i++) {
            ik[i] = (byte) i;
            ck[i] = (byte) (0x10 + i);
        }
        JoanSipCrypto.EspKeys shaAes = JoanSipCrypto.espKeys(
                "hmac-sha-1-96", "aes-cbc", ck, ik);
        check(shaAes.authKey.length == 20
                        && shaAes.authKey[16] == 0
                        && shaAes.hasEncryption(),
                "sha1-96 pads IK to 160 bits and keeps AES");
        JoanSipCrypto.EspKeys md5Null = JoanSipCrypto.espKeys(
                "hmac-md5-96", "null", ck, ik);
        check(md5Null.authKey.length == 16 && !md5Null.hasEncryption(),
                "md5-96 + null encryption is integrity-only");
        boolean threw = false;
        try {
            JoanSipCrypto.espKeys("hmac-sha-256-128", "aes-cbc", ck, ik);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "unknown ESP alg is refused (not silently SHA-1)");
        threw = false;
        try {
            JoanSipCrypto.espKeys("hmac-sha-1-96", "des-ede3-cbc", ck, ik);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "3DES refused: not in IpSecManager");
    }

    private static void testSecAgreeSelect() {
        JoanSecAgree first = JoanSecAgree.parse(
                "ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp; mod=trans; "
                        + "spi-c=300; spi-s=400; port-c=25000; port-s=26000");
        check(first != null && first.spiC == 300 && first.portS == 26000,
                "sec-agree single mechanism");

        JoanSecAgree picked = JoanSecAgree.select(
                "ipsec-3gpp; q=0.1; alg=hmac-md5-96; ealg=null; "
                        + "spi-c=1; spi-s=2; port-c=1000; port-s=1001, "
                        + "ipsec-3gpp; q=0.9; alg=hmac-sha-1-96; ealg=aes-cbc; "
                        + "spi-c=3; spi-s=4; port-c=2000; port-s=2001");
        check(picked != null
                        && picked.alg.equals("hmac-sha-1-96")
                        && picked.ealg.equals("aes-cbc")
                        && picked.spiC == 3,
                "sec-agree selects highest q among supported");

        JoanSecAgree skip = JoanSecAgree.select(
                "ipsec-3gpp; q=1.0; alg=hmac-sha-256-128; ealg=aes-gcm; "
                        + "spi-c=9; spi-s=8; port-c=1; port-s=2, "
                        + "ipsec-3gpp; q=0.2; alg=hmac-sha-1-96; ealg=aes-cbc; "
                        + "spi-c=5; spi-s=6; port-c=7; port-s=8");
        check(skip != null && skip.spiC == 5,
                "sec-agree skips unsupported GCM/SHA-256");
    }

    private static void testRegisterOffer() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        JoanSipBuilder.Params mine = new JoanSipBuilder.Params(
                1111, 2222, 15000, 16000);
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(mine, rng);
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example.net", "sip:user@ims.example.net",
                "ims.example.net", "2001:db8::2", 5060, 5060,
                "123456789012345");
        String msg = JoanSipBuilder.buildRegister(id, txn, 1, null, null);
        check(msg.contains("REGISTER sip:ims.example.net SIP/2.0"),
                "reg1 request-uri is home realm");
        check(msg.contains("alg=hmac-sha-1-96")
                        && msg.contains("alg=hmac-md5-96")
                        && msg.contains("ealg=aes-cbc")
                        && msg.contains("ealg=null"),
                "reg1 Security-Client offers 3GPP set including null ealg");
        check(msg.contains("P-Access-Network-Info: 3GPP-E-UTRAN-FDD"),
                "reg1 default PANI is radio token");
        String nr = JoanSipBuilder.buildRegister(id, txn, 1, null, null,
                null, null, "3GPP-NR-FDD");
        check(nr.contains("P-Access-Network-Info: 3GPP-NR-FDD"),
                "reg1 PANI follows radio not carrier");
        testInvite();
    }

    private static void testInvite() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example.net", "sip:+15555550100@ims.example.net",
                "ims.example.net", "2001:db8::2", 25000, 26000,
                "123456789012345");
        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        String inv = JoanSipBuilder.buildInvite(id, dlg,
                "tel:+15555550999", "<sip:[2001:db8::1]:5060;lr>",
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD");
        check(inv != null && inv.startsWith("INVITE tel:+15555550999 SIP/2.0"),
                "invite request-line");
        check(inv.contains("a=rtpmap:0 PCMU/8000"), "invite PCMU first");
        check(!inv.contains("Supported: timer"), "invite does not advertise timer");
        check(inv.contains("m=audio 40000 RTP/AVP"), "invite rtp port");
        String to = "SIP/2.0 200 OK\r\nTo: <sip:x@y>;tag="
                + "t".repeat(81) + "\r\n\r\n";
        check(JoanSipBuilder.extractToTag(to).length() == 81,
                "81-char To-tag survives");
        String ack = JoanSipBuilder.buildAck(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+15555550100@ims.example.net>;tag="
                        + dlg.fromTag);
        check(ack.startsWith("ACK sip:peer@host SIP/2.0"), "ack r-uri is Contact");
        check(ack.contains("To: <sip:x@y>;tag=abc"), "ack echoes To");
        check(ack.contains("CSeq: 1 ACK"), "ack reuses INVITE CSeq");
        String bye = JoanSipBuilder.buildBye(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+15555550100@ims.example.net>;tag="
                        + dlg.fromTag);
        check(bye.contains("CSeq: 2 BYE"), "bye increments CSeq");
        String sdp = "SIP/2.0 200 OK\r\n\r\nv=0\r\nc=IN IP6 2001:db8::9\r\n"
                + "m=audio 20000 RTP/AVP 0\r\na=sendrecv\r\n";
        JoanSipBuilder.Media m = JoanSipBuilder.parseSdp(sdp);
        check(m != null && m.port == 20000 && "2001:db8::9".equals(m.ip),
                "sdp parse c/m");
        check(JoanSipBuilder.pickPublicId(
                "<sip:a@ims>; <tel:+15555550100>").equals("tel:+15555550100"),
                "public id prefers tel:");
        String ans = JoanSipBuilder.sdpAnswer("2001:db8::2", 40000,
                "m=audio 20000 RTP/AVP 0 96\r\na=rtpmap:0 PCMU/8000\r\na=rtcp-mux\r\n");
        check(ans.contains("m=audio 40000 RTP/AVP 0") && ans.contains("PCMU")
                        && !ans.contains("AMR") && ans.contains("a=rtcp-mux"),
                "sdp answer is PCMU-only with mux");
        StringBuilder acc = new StringBuilder(
                "OPTIONS sip:x SIP/2.0\r\nContent-Length: 0\r\n\r\nINVITE sip:y SIP/2.0\r\nContent-Length: 0\r\n\r\n");
        check("OPTIONS sip:x SIP/2.0\r\nContent-Length: 0\r\n\r\n"
                        .equals(JoanSipBuilder.extractOne(acc)),
                "tcp extract one");
        check(JoanSipBuilder.extractOne(acc).startsWith("INVITE"),
                "tcp extract remainder");
        testCli();
    }

    private static void testCli() {
        String pai = "INVITE sip:me@example.net SIP/2.0\r\n"
                + "From: \"Alice Smith\" <sip:spoofed@evil.example>;tag=a1\r\n"
                + "P-Asserted-Identity: <sip:+15555550100@ims.example.net>, "
                + "<tel:+15555550100>\r\n"
                + "Content-Length: 0\r\n\r\n";
        JoanSipBuilder.Cli c = JoanSipBuilder.callingIdentity(pai);
        check(!c.withheld && "tel:+15555550100".equals(c.uri),
                "cli prefers the asserted tel: over the claimed From");
        check("Alice Smith".equals(c.name), "cli takes display name from From");
        String fromonly = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"Bob\" <tel:+15555558888>;tag=b2\r\n"
                + "Content-Length: 0\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(fromonly);
        check("tel:+15555558888".equals(c.uri) && "Bob".equals(c.name),
                "cli falls back to From when no P-Asserted-Identity");
        String anon = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"Anonymous\" <sip:anonymous@anonymous.invalid>;tag=c3\r\n"
                + "Content-Length: 0\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(anon);
        check(c.withheld && c.uri.isEmpty() && c.name.isEmpty(),
                "withheld number leaks neither number nor name");
        String nasty = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"bad\tname\" <tel:+15555550000>;tag=d4\r\n"
                + "Content-Length: 0\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(nasty);
        check(c.name.indexOf('\t') < 0 && c.name.indexOf('"') < 0,
                "display name is stripped of control characters and quotes");
        /* The old Java wrap "<" + pai + ">" produced a leftover '<'. */
        String realPai = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"Alice\" <sip:user@domain>;tag=z\r\n"
                + "P-Asserted-Identity: \"Alice\" <sip:+15555550100@ims.example.net>\r\n"
                + "Content-Length: 0\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(realPai);
        check(c.uri.startsWith("sip:+") && c.uri.indexOf('<') < 0,
                "cli uri has no leftover angle bracket");
        check("Alice".equals(c.name), "cli name from PAI/From display");
    }

    private static void testGrantedExpires() {
        /* Two contacts registered against the same IMPU: ours is the one
         * on our own contact port, and it is not the first. */
        String twoContacts = "SIP/2.0 200 OK\r\n"
                + "Contact: <sip:+15555550100@[2600:1:2::9]:5060>;expires=3600\r\n"
                + "Contact: <sip:+15555550100@[2600:1:2::5]:12345>;expires=600\r\n"
                + "Expires: 999999\r\n"
                + "Content-Length: 0\r\n\r\n";
        check(JoanSipBuilder.grantedExpiresSeconds(twoContacts, 12345) == 600,
                "granted expiry prefers our own contact port");
        check(JoanSipBuilder.grantedExpiresSeconds(twoContacts, 40000) == 3600,
                "granted expiry falls back to the first contact");

        /* A port that is a prefix of ours must not match. */
        String prefix = "SIP/2.0 200 OK\r\n"
                + "Contact: <sip:u@[2600::5]:1234567>;expires=77\r\n"
                + "Contact: <sip:u@[2600::5]:1234>;expires=88\r\n"
                + "Content-Length: 0\r\n\r\n";
        check(JoanSipBuilder.grantedExpiresSeconds(prefix, 1234) == 88,
                "granted expiry does not match a longer port");

        String headerOnly = "SIP/2.0 200 OK\r\n"
                + "Contact: <sip:u@[2600::5]:1234>\r\n"
                + "Expires: 1800\r\n"
                + "Content-Length: 0\r\n\r\n";
        check(JoanSipBuilder.grantedExpiresSeconds(headerOnly, 1234) == 1800,
                "granted expiry falls back to the Expires header");

        String silent = "SIP/2.0 200 OK\r\n"
                + "Contact: <sip:u@[2600::5]:1234>\r\n"
                + "Content-Length: 0\r\n\r\n";
        check(JoanSipBuilder.grantedExpiresSeconds(silent, 1234) == -1,
                "granted expiry is -1 when the registrar says nothing");

        /* headers() must stop at the blank line, not walk into the body. */
        String withBody = "SIP/2.0 200 OK\r\n"
                + "Record-Route: <sip:a;lr>\r\n"
                + "Record-Route: <sip:b;lr>\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length: 12\r\n\r\n"
                + "Contact: nope\r\n";
        check(JoanSipBuilder.headers(withBody, "Record-Route").size() == 2,
                "headers returns every occurrence in order");
        check(JoanSipBuilder.headers(withBody, "Contact").isEmpty(),
                "headers stops at the body");
    }

    private static void testRefreshLead() {
        final long CAP = 30 * 60_000L;
        final long FLOOR = 60_000L;
        /* Whatever the registrar grants, the refresh must land strictly
         * inside it. A 60s floor used to override the 80% rule below a 75s
         * grant and schedule the refresh at or after expiry. */
        int[] grants = { 10, 30, 60, 75, 120, 600, 1800, 3600, 86400, 600000 };
        boolean allInside = true;
        for (int g : grants) {
            long lead = JoanSipBuilder.refreshLeadMs(g, CAP, FLOOR);
            if (lead >= g * 1000L) {
                allInside = false;
                System.out.println("     granted=" + g + "s lead=" + lead + "ms");
            }
        }
        check(allInside, "refresh always lands inside the granted lifetime");
        check(JoanSipBuilder.refreshLeadMs(3600, CAP, FLOOR) == CAP,
                "long grant is capped so the binding is re-validated");
        check(JoanSipBuilder.refreshLeadMs(600, CAP, FLOOR) == 480_000L,
                "ordinary grant refreshes at 80%");
        check(JoanSipBuilder.refreshLeadMs(30, CAP, FLOOR) == 24_000L,
                "short grant beats the floor rather than expiring");
        check(JoanSipBuilder.refreshLeadMs(-1, CAP, FLOOR) == CAP,
                "silent registrar falls back to the cap");
    }

    /** We must not advertise a capability this UA does not implement. */
    private static void testAdvertisedCapabilities() {
        JoanSipBuilder.Params m = new JoanSipBuilder.Params(1, 2, 5000, 5001);
        java.security.SecureRandom rng = new java.security.SecureRandom();
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(m, rng);
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example", "sip:+15555550100@ims.example",
                "ims.example", "2600::5", 5000, 5001, "123456789012345");
        String reg = JoanSipBuilder.buildRegister(id, txn, 1, null, null);
        /* +g.3gpp.smsip tells the core to deliver SMS as a SIP MESSAGE.
         * handleInbound has no MESSAGE case, so those would be dropped. */
        check(!reg.contains("smsip"),
                "REGISTER does not claim SMS over IP");
        check(!reg.contains("MESSAGE"),
                "REGISTER does not allow MESSAGE");

        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        String inv = JoanSipBuilder.buildInvite(id, dlg, "tel:+15555550111",
                null, null, 40000, "3GPP-E-UTRAN-FDD");
        check(!inv.contains("Supported: replaces"),
                "INVITE does not claim Replaces");
        for (String m2 : new String[] { "UPDATE", "REFER", "NOTIFY",
                "MESSAGE", "INFO" }) {
            check(!reg.contains(m2) && !inv.contains(m2),
                    "neither request allows " + m2);
        }
        check(reg.contains("Allow: " + JoanSipBuilder.ALLOW)
                && inv.contains("Allow: " + JoanSipBuilder.ALLOW),
                "both advertise exactly the handled method set");
    }

    /** Offer only PCMU, and read back which codec the answer selected. */
    private static void testCodecHonesty() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example", "sip:+15555550100@ims.example",
                "ims.example", "2600::5", 5000, 5001, "123456789012345");
        String inv = JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+15555550111", null, null, 40000, "3GPP-E-UTRAN-FDD");
        check(inv.contains("m=audio 40000 RTP/AVP 0\r\n"),
                "offer lists PCMU and nothing else");
        for (String codec : new String[] { "AMR-WB", "AMR/8000",
                "telephone-event" }) {
            check(!inv.contains(codec), "offer does not promise " + codec);
        }

        String amrAnswer = "SIP/2.0 200 OK\r\n\r\nv=0\r\n"
                + "c=IN IP6 2600::9\r\n"
                + "m=audio 21000 RTP/AVP 96\r\n"
                + "a=rtpmap:96 AMR-WB/16000/1\r\n";
        JoanSipBuilder.Media m = JoanSipBuilder.parseSdp(amrAnswer);
        check(m != null && m.payloadType == 96 && !m.offersPcmu,
                "an AMR-WB answer is recognised as not PCMU");

        String pcmuAnswer = "SIP/2.0 200 OK\r\n\r\nv=0\r\n"
                + "c=IN IP6 2600::9\r\n"
                + "m=audio 21000 RTP/AVP 0\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        m = JoanSipBuilder.parseSdp(pcmuAnswer);
        check(m != null && m.payloadType == 0 && m.offersPcmu,
                "a PCMU answer is accepted");

        String multiOffer = "INVITE sip:me SIP/2.0\r\n\r\nv=0\r\n"
                + "c=IN IP6 2600::9\r\n"
                + "m=audio 21000 RTP/AVP 96 97 0 101\r\n";
        m = JoanSipBuilder.parseSdp(multiOffer);
        check(m != null && m.payloadType == 96 && m.offersPcmu,
                "an offer listing PCMU anywhere is answerable");
    }

    private static void testImei() {
        check("12345678-901234-5".equals(
                JoanSipBuilder.imeiInstance("123456789012345")),
                "imei instance 15 digits");
    }
}
