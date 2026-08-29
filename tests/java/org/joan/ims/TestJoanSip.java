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
    }

    private static void testImei() {
        check("12345678-901234-5".equals(
                JoanSipBuilder.imeiInstance("123456789012345")),
                "imei instance 15 digits");
    }
}
