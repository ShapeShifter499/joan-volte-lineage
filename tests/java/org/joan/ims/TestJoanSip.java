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
        testGlobalCriterion();
        testInviteAckTransactions();
        testImei();
        testGrantedExpires();
        testRefreshLead();
        testAdvertisedCapabilities();
        testCodecHonesty();
        testDerivedIdentity();
        testSecAgreeOnInvite();
        testAmrPayload();
        testOfferSummary();
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
        check(msg.contains("Via: SIP/2.0/UDP "),
                "default REGISTER Via is UDP (T-Mobile path)");
        check(!msg.contains("Via: SIP/2.0/TCP "),
                "default REGISTER does not advertise TCP");
        String nr = JoanSipBuilder.buildRegister(id, txn, 1, null, null,
                null, null, "3GPP-NR-FDD");
        check(nr.contains("P-Access-Network-Info: 3GPP-NR-FDD"),
                "reg1 PANI follows radio not carrier");
        testProtectedTcpChoice();
        testInvite();
    }

    /**
     * Transport follows stock GetTCPCriterionLength, then RFC 3261
     * §18.1.1 on IPv6. A measured ~1.6 kB REG1 stays UDP on IPv4
     * GLOBAL (Viettel 401 over UDP). The same size on IPv6 with
     * unknown MTU takes TCP (NOS REG1 silence). TMUS never leaves UDP.
     */
    private static void testProtectedTcpChoice() {
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc000.mcc460.3gppnetwork.org", 1024),
                "CMCC REGISTER under criterion stays UDP");
        check(JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc000.mcc460.3gppnetwork.org", 1400),
                "CMCC oversized message goes TCP");
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc002.mcc460.3gppnetwork.org", 1024),
                "other MCC 460 MNC also criterion-bound");
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc001.mcc460.3gppnetwork.org", 1824),
                "CU must not inherit CMCC instead of GLOBAL");
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc260.mcc310.3gppnetwork.org", 1024),
                "T-Mobile home REGISTER stays UDP");
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc260.mcc310.3gppnetwork.org", 9216),
                "T-Mobile registration never leaves UDP (criterion off)");
        check(JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc999.mcc310.3gppnetwork.org", 5000),
                "unmapped MCC-310 PLMN must receive GLOBAL not TMUS disabled threshold");
        check(!JoanSipBuilder.preferProtectedTcp("msg.pc.t-mobile.com", 9216),
                "non-3GPP realm never flips transport");
        check(!JoanSipBuilder.preferProtectedTcp(null, 9216)
                        && !JoanSipBuilder.preferProtectedTcp("", 9216),
                "empty realm does not flip transport");
        check(JoanSipBuilder.plmnOf("ims.mnc000.mcc460.3gppnetwork.org") == 460,
                "CMCC realm parses MCC 460");
        check(JoanSipBuilder.plmnOf("ims.mnc260.mcc310.3gppnetwork.org") == 310,
                "T-Mobile home MCC 310");
        check(JoanSipBuilder.plmnOf("msg.pc.t-mobile.com") == -1,
                "non-3GPP realm has no MCC");
        java.security.SecureRandom rng = new java.security.SecureRandom();
        JoanSipBuilder.Params mine = new JoanSipBuilder.Params(
                1111, 2222, 15000, 16000);
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(mine, rng);
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.mnc000.mcc460.3gppnetwork.org",
                "sip:user@ims.mnc000.mcc460.3gppnetwork.org",
                "ims.mnc000.mcc460.3gppnetwork.org", "2001:db8::2",
                15000, 16000, "123456789012345");
        JoanSipBuilder.Challenge ch = new JoanSipBuilder.Challenge(
                "dGVzdG5vbmNlMTIzNA==", "AKAv1-MD5",
                "ipsec-3gpp;alg=hmac-md5-96;ealg=null;spi-c=1;spi-s=2;"
                        + "port-c=9950;port-s=9900");
        byte[] res = JoanSipCrypto.hexBytes("00112233445566778899aabbccddeeff");
        String tcp = JoanSipBuilder.buildRegister(id, txn, 2, ch, res,
                null, null, "3GPP-E-UTRAN-TDD", true);
        check(tcp.contains("Via: SIP/2.0/TCP [2001:db8::2]:15000"),
                "protected CMCC REGISTER Via is TCP from port-c");
        check(tcp.contains("Security-Verify:"),
                "protected TCP REGISTER still carries Security-Verify");
        check(!tcp.contains("Via: SIP/2.0/UDP "),
                "TCP REGISTER does not also claim UDP");
        String udp = JoanSipBuilder.buildRegister(id, txn, 2, ch, res,
                null, null, "3GPP-E-UTRAN-TDD", false);
        check(udp.contains("Via: SIP/2.0/UDP [2001:db8::2]:15000"),
                "explicit UDP REGISTER keeps UDP Via");
        /* buildRegister() re-rolls txn.branch on every call, so the TCP
         * and UDP variants are DIFFERENT transactions. Callers must match
         * a reply against the variant they actually put on the wire;
         * matching a TCP reply against the UDP build rejects every final
         * as "mismatch" (CMCC REG1 regression, alpha20). */
        check(!JoanSipBuilder.branchOf(tcp).isEmpty()
                        && !JoanSipBuilder.branchOf(tcp)
                        .equals(JoanSipBuilder.branchOf(udp)),
                "each buildRegister call is its own transaction branch");
        // Stock parity (alpha12): libims never emits `integrity-protected`
        // (verified absent from libims.lge.so). Joan must match stock.
        String prot = udp;
        check(prot.contains("Authorization: Digest username="),
                "protected REGISTER carries a Digest Authorization");
        check(!prot.contains("integrity-protected"),
                "stock parity: REGISTER never carries integrity-protected");
        check(!prot.contains("auts="),
                "an ordinary REGISTER carries no auts");

        /* Codec negotiation: the OFFERER's order decides, and we answer
         * with their payload number. AOSP negotiates the same way, walking
         * the peer's payload list (AudioProfileNegotiator.cpp). */
        String head = "v=0\r\no=- 1 1 IN IP6 2001:db8::9\r\ns=-\r\n"
                + "c=IN IP6 2001:db8::9\r\nt=0 0\r\n";
        String wbFirst = head
                + "m=audio 40000 RTP/AVP 104 0\r\n"
                + "a=rtpmap:104 AMR-WB/16000/1\r\n"
                + "a=fmtp:104 octet-align=1; mode-set=0,1,2\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        JoanSipBuilder.Codec pick =
                JoanSipBuilder.selectAnswerCodec(JoanSipBuilder.parseSdp(wbFirst));
        check(pick != null && pick.pt == 104 && pick.is("AMR-WB", 16000),
                "AMR-WB offered first is selected");
        String ans = JoanSipBuilder.sdpAnswer("2001:db8::2", 40000, wbFirst);
        check(ans.contains("m=audio 40000 RTP/AVP 104\r\n")
                        && ans.contains("a=rtpmap:104 AMR-WB/16000/1"),
                "the answer echoes the offerer's payload number");
        check(ans.contains("a=fmtp:104 octet-align=1"),
                "an AMR answer states octet-align explicitly");
        check(!ans.contains("RTP/AVP 96"), "our own offer's pt is not reused");

        // Offerer order wins over any preference of ours.
        String nbFirst = head
                + "m=audio 40000 RTP/AVP 97 104 0\r\n"
                + "a=rtpmap:97 AMR/8000/1\r\na=fmtp:97 octet-align=1\r\n"
                + "a=rtpmap:104 AMR-WB/16000/1\r\na=fmtp:104 octet-align=1\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        JoanSipBuilder.Codec nb =
                JoanSipBuilder.selectAnswerCodec(JoanSipBuilder.parseSdp(nbFirst));
        check(nb != null && nb.pt == 97 && nb.is("AMR", 8000),
                "the offerer's order decides, not ours");

        // Bandwidth-efficient AMR is not implemented: skip it, do not
        // accept it by omission, and fall back to the next usable entry.
        String beAmr = head
                + "m=audio 40000 RTP/AVP 104 0\r\n"
                + "a=rtpmap:104 AMR-WB/16000/1\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        JoanSipBuilder.Codec be =
                JoanSipBuilder.selectAnswerCodec(JoanSipBuilder.parseSdp(beAmr));
        check(be != null && be.pt == 104,
                "bandwidth-efficient AMR is now carried, not skipped");
        check(!JoanSipBuilder.amrOctetAligned(be),
                "an fmtp with no octet-align means bandwidth-efficient");
        String beAns = JoanSipBuilder.sdpAnswer("2001:db8::2", 40000, beAmr);
        check(beAns.contains("m=audio 40000 RTP/AVP 104\r\n"),
                "the answer takes their bandwidth-efficient payload type");
        check(beAns.contains("a=fmtp:104 octet-align=0"),
                "the answer states the framing it will actually send");
        check(JoanSipBuilder.amrOctetAligned(pick),
                "an offer naming octet-align=1 is carried octet-aligned");

        /* The trace has to distinguish "they preferred PCMU" from "they
         * offered AMR we had to skip", because only the second is a bug
         * on our side. */
        check("AMR-WB/104(oct=1),PCMU/0".equals(JoanSipBuilder.codecSummary(
                        JoanSipBuilder.parseSdp(wbFirst))),
                "offer summary marks octet-aligned AMR");
        check(JoanSipBuilder.codecSummary(JoanSipBuilder.parseSdp(beAmr))
                        .contains("AMR-WB/104(oct=0)"),
                "offer summary marks bandwidth-efficient AMR");
        check("none".equals(JoanSipBuilder.codecSummary(null)),
                "no offer summarises as none");

        /* mode-set: encoding above what the peer allows produces frames
         * they discard, which sounds like a dead uplink on a call whose
         * microphone is plainly working. */
        String pinned = head
                + "m=audio 40000 RTP/AVP 110 0\r\n"
                + "a=rtpmap:110 AMR-WB/16000/1\r\n"
                + "a=fmtp:110 octet-align=1; mode-set=0,1,2\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        JoanSipBuilder.Codec pinnedPick =
                JoanSipBuilder.selectAnswerCodec(JoanSipBuilder.parseSdp(pinned));
        check(pinnedPick != null && pinnedPick.maxAmrMode() == 2,
                "highest offered AMR mode is read from mode-set");
        check(JoanSipBuilder.amrBitrate(pinnedPick) == 12650,
                "AMR-WB mode 2 clamps the encoder to 12650 bps");
        String pinnedAns = JoanSipBuilder.sdpAnswer("2001:db8::2", 40000, pinned);
        check(pinnedAns.contains("a=fmtp:110 octet-align=1;mode-set=0,1,2"),
                "the answer echoes the peer's mode-set");
        // No mode-set means unrestricted, and the codec default stands.
        String unpinned = head + "m=audio 40000 RTP/AVP 110\r\n"
                + "a=rtpmap:110 AMR-WB/16000/1\r\n"
                + "a=fmtp:110 octet-align=1\r\n";
        JoanSipBuilder.Codec free = JoanSipBuilder.selectAnswerCodec(
                JoanSipBuilder.parseSdp(unpinned));
        check(free != null && free.maxAmrMode() == -1,
                "an fmtp with no mode-set reads as unrestricted");
        check(JoanSipBuilder.amrBitrate(free) == 0,
                "no mode-set leaves the codec default alone");
        check(!JoanSipBuilder.sdpAnswer("2001:db8::2", 40000, unpinned)
                        .contains("mode-set"),
                "an answer invents no mode-set the peer did not name");
        check(JoanSipBuilder.amrBitrate(
                        JoanSipBuilder.parseSdp(beAmr).codec(0)) == 0,
                "PCMU has no AMR bitrate");
        // A mode beyond the table must clamp, not throw.
        String wild = head + "m=audio 40000 RTP/AVP 110\r\n"
                + "a=rtpmap:110 AMR-WB/16000/1\r\n"
                + "a=fmtp:110 octet-align=1;mode-set=0,99\r\n";
        check(JoanSipBuilder.amrBitrate(JoanSipBuilder.selectAnswerCodec(
                        JoanSipBuilder.parseSdp(wild))) == 23850,
                "an out-of-range mode clamps to the top of the table");

        // Nothing usable is the only honest reason to decline.
        String none = head + "m=audio 40000 RTP/AVP 9\r\n"
                + "a=rtpmap:9 G722/8000\r\n";
        check(JoanSipBuilder.selectAnswerCodec(JoanSipBuilder.parseSdp(none)) == null,
                "an offer with no codec we carry selects nothing");
        check(JoanSipBuilder.selectAnswerCodec(JoanSipBuilder.parseSdp(
                        head + "m=audio 40000 RTP/AVP 0\r\n")) != null,
                "PCMU with no rtpmap is still selectable");

        /* A request built for the UDP socket but written to the accepted
         * TCP connection must say TCP in its top Via; a response must not
         * be touched, because it echoes the request's Via (RFC 3261
         * 8.2.6.2). */
        String byeUdp = "BYE sip:peer@x SIP/2.0\r\nVia: SIP/2.0/UDP "
                + "[2001:db8::2]:15000;branch=z9hG4bKb1\r\n"
                + "Content-Length: 0\r\n\r\n";
        check(JoanSipBuilder.retargetRequestViaToTcp(byeUdp)
                        .contains("Via: SIP/2.0/TCP [2001:db8::2]:15000"),
                "a request written over TCP claims TCP in its Via");
        String rsp = "SIP/2.0 200 OK\r\nVia: SIP/2.0/UDP "
                + "[2001:db8::3]:5060;branch=z9hG4bKpeer\r\n"
                + "Content-Length: 0\r\n\r\n";
        check(rsp.equals(JoanSipBuilder.retargetRequestViaToTcp(rsp)),
                "a response keeps the Via it is echoing");
        String twoVia = "ACK sip:peer@x SIP/2.0\r\nVia: SIP/2.0/UDP a;"
                + "branch=z9hG4bK1\r\nVia: SIP/2.0/UDP b;branch=z9hG4bK2\r\n"
                + "Content-Length: 0\r\n\r\n";
        String retargeted = JoanSipBuilder.retargetRequestViaToTcp(twoVia);
        check(retargeted.indexOf("SIP/2.0/TCP") >= 0
                        && retargeted.indexOf("SIP/2.0/UDP")
                        > retargeted.indexOf("SIP/2.0/TCP"),
                "only the top Via is re-aimed");
        check(JoanSipBuilder.retargetRequestViaToTcp(byeUdp.replace(
                        "Via: SIP/2.0/UDP [2001:db8::2]:15000;branch=z9hG4bKb1",
                        "Max-Forwards: 70")).indexOf("SIP/2.0/TCP") < 0,
                "a request with no Via is left alone");

        /* AUTS resynchronisation REGISTER (RFC 3310 3.2). It answers a card
         * SYNCHRONISATION FAILURE, so it is an UNPROTECTED REGISTER: it
         * offers Security-Client and must NOT claim Security-Verify, and
         * its response is computed over an empty password, not a RES. */
        JoanSipBuilder.Challenge resyncCh = new JoanSipBuilder.Challenge(
                "dGVzdG5vbmNlMTIzNA==", "AKAv1-MD5", null,
                "ims.mnc000.mcc460.3gppnetwork.org", "auth")
                .resync("YXV0cy1ieXRlcw==");
        String resync = JoanSipBuilder.buildRegister(id, txn, 2, resyncCh,
                new byte[0], null, null, "3GPP-E-UTRAN-TDD", false);
        check(resync.contains("auts=\"YXV0cy1ieXRlcw==\""),
                "resync REGISTER carries quoted base64 auts");
        check(resync.contains("Security-Client:"),
                "resync REGISTER still offers Security-Client");
        check(!resync.contains("Security-Verify:"),
                "resync REGISTER claims no Security-Verify");
        check(JoanSipBuilder.cseqForMethod(resync, "REGISTER") == 2,
                "resync REGISTER is its own transaction");
        String expected = JoanSipCrypto.akaDigestResponseHex(
                id.impi, "ims.mnc000.mcc460.3gppnetwork.org", "REGISTER",
                "sip:" + id.realm, "dGVzdG5vbmNlMTIzNA==", new byte[0],
                "auth", "00000001", txn.cnonce, "AKAv1-MD5", null, null);
        check(resync.contains("response=\"" + expected + "\""),
                "resync response uses an empty password, not a RES");
        String withRes = JoanSipBuilder.buildRegister(id, txn, 2,
                new JoanSipBuilder.Challenge("dGVzdG5vbmNlMTIzNA==",
                        "AKAv1-MD5", null,
                        "ims.mnc000.mcc460.3gppnetwork.org", "auth"),
                res, null, null, "3GPP-E-UTRAN-TDD", false);
        check(!withRes.contains("response=\"" + expected + "\""),
                "an empty-password response differs from the RES response");
    }

    /** Stock global profile: unknown carriers keep a LIVE 4096 criterion. */
    private static void testGlobalCriterion() {
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc04.mcc452.3gppnetwork.org", 1024),
                "Viettel-sized REGISTER stays UDP on global criterion");
        check(!JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc03.mcc268.3gppnetwork.org", 1024),
                "NOS-sized REGISTER stays UDP on global criterion");
        check(JoanSipBuilder.preferProtectedTcp(
                        "ims.mnc04.mcc452.3gppnetwork.org", 5000),
                "unknown-PLMN message over 4096 goes TCP (stock 4096 live)");
        String nos = "ims.mnc003.mcc268.3gppnetwork.org";
        String viettel = "ims.mnc004.mcc452.3gppnetwork.org";
        String tmus = "ims.mnc260.mcc310.3gppnetwork.org";
        check(JoanSipBuilder.udpOverhead(true) == 48
                        && JoanSipBuilder.udpOverhead(false) == 28,
                "UDP/IP overhead is 48 for IPv6 and 28 for IPv4");
        check(JoanSipBuilder.preferTcp(nos, 1630, 0, true),
                "NOS IPv6 REG1 over 1300 with unknown MTU uses TCP");
        check(!JoanSipBuilder.preferTcp(nos, 1000, 0, true),
                "small IPv6 REGISTER with unknown MTU stays UDP");
        check(JoanSipBuilder.preferTcp(nos, 1630, 1500, true),
                "NOS IPv6 REG1 within 200 bytes of 1500 MTU uses TCP");
        check(!JoanSipBuilder.preferTcp(nos, 1630, 2500, true),
                "IPv6 REGISTER under a large path MTU stays UDP");
        check(!JoanSipBuilder.preferTcp(viettel, 1568, 0, false),
                "Viettel IPv4 REG1 stays UDP when MTU is unknown");
        check(!JoanSipBuilder.preferTcp(viettel, 1830, 1500, false),
                "Viettel IPv4 REG2 does not inherit the IPv6 RFC switch");
        check(!JoanSipBuilder.preferTcp(tmus, 1830, 0, true)
                        && !JoanSipBuilder.preferTcp(tmus, 9216, 1280, true),
                "TMUS never leaves UDP even on IPv6/small MTU");
        check(!JoanSipBuilder.preferTcp(null, 1630, 0, true),
                "non-3GPP realm never flips REG1 to TCP");
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
        String ack = JoanSipBuilder.buildAck2xx(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+155****0100@ims.example.net>;tag="
                        + dlg.fromTag, dlg.cseq);
        check(ack.startsWith("ACK sip:peer@host SIP/2.0"), "ack r-uri is Contact");
        check(ack.contains("To: <sip:x@y>;tag=abc"), "ack echoes To");
        check(ack.contains("CSeq: 1 ACK"), "ack reuses INVITE CSeq");
        String bye = JoanSipBuilder.buildBye(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+15555550100@ims.example.net>;tag="
                        + dlg.fromTag);
        check(bye.contains("CSeq: 2 BYE"), "bye increments CSeq");
        String holdSdp = JoanSipBuilder.sdpHold("2001:db8::2", 40000, true);
        check(holdSdp.contains("a=sendonly") && !holdSdp.contains("a=sendrecv"),
                "hold SDP is sendonly");
        String resumeSdp = JoanSipBuilder.sdpHold("2001:db8::2", 40000, false);
        check(resumeSdp.contains("a=sendrecv") && !resumeSdp.contains("a=sendonly"),
                "resume SDP is sendrecv");
        String reinv = JoanSipBuilder.buildReInvite(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc",
                "<sip:+155****0100@ims.example.net>;tag=" + dlg.fromTag,
                holdSdp);
        check(reinv.startsWith("INVITE sip:peer@host SIP/2.0"),
                "re-INVITE request-line");
        check(reinv.contains("CSeq: 2 INVITE"), "re-INVITE increments CSeq");
        check(reinv.contains("a=sendonly"), "re-INVITE carries hold SDP");
        check(reinv.contains("Content-Type: application/sdp"),
                "re-INVITE has SDP");
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
                "an offer naming only PCMU is answered with PCMU, mux mirrored");
        StringBuilder acc = new StringBuilder(
                "OPTIONS sip:x SIP/2.0\r\nContent-Length: 0\r\n\r\nINVITE sip:y SIP/2.0\r\nContent-Length: 0\r\n\r\n");
        check("OPTIONS sip:x SIP/2.0\r\nContent-Length: 0\r\n\r\n"
                        .equals(JoanSipBuilder.extractOne(acc)),
                "tcp extract one");
        check(JoanSipBuilder.extractOne(acc).startsWith("INVITE"),
                "tcp extract remainder");
        testCli();
    }

    /**
     * A 2xx ACK is a fresh in-dialog request, while a non-2xx ACK belongs
     * to the INVITE transaction. A later re-INVITE must not change either
     * archived ACK for the earlier transaction.
     */
    private static void testInviteAckTransactions() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example.net", "sip:+155****0100@ims.example.net",
                "ims.example.net", "2001:db8::2", 25000, 26000,
                "123456789012345");
        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        String first = JoanSipBuilder.buildInvite(id, dlg, "tel:+155****0999",
                "<sip:[2001:db8::1]:5060;lr>", "ipsec-3gpp", 40000,
                "3GPP-E-UTRAN-FDD");
        int firstCseq = dlg.cseq;
        String firstBranch = viaBranch(first);
        String ack2xx = JoanSipBuilder.buildAck2xx(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+155****0100@ims.example.net>;tag="
                        + dlg.fromTag, firstCseq);
        check(!firstBranch.equals(viaBranch(ack2xx)),
                "2xx ACK gets a fresh Via branch");
        check(ack2xx.contains("CSeq: 1 ACK"),
                "2xx ACK retains its INVITE CSeq");
        check(firstBranch.equals(viaBranch(JoanSipBuilder.buildAckNon2xx(id, dlg,
                "sip:peer@host", "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+155****0100@ims.example.net>;tag="
                        + dlg.fromTag, firstCseq, firstBranch))),
                "non-2xx ACK reuses its INVITE Via branch");

        String reinvite = JoanSipBuilder.buildReInvite(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+155****0100@ims.example.net>;tag="
                        + dlg.fromTag,
                JoanSipBuilder.sdpHold("2001:db8::2", 40000, true));
        int reinviteCseq = dlg.cseq;
        String reinviteBranch = viaBranch(reinvite);
        String reAck2xx = JoanSipBuilder.buildAck2xx(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+155****0100@ims.example.net>;tag="
                        + dlg.fromTag, reinviteCseq);

        JoanSipBuilder.InviteAckArchive a = new JoanSipBuilder.InviteAckArchive();
        JoanSipBuilder.Dialog d1 = new JoanSipBuilder.Dialog();
        d1.callId = dlg.callId;
        d1.fromTag = dlg.fromTag;
        d1.branch = firstBranch;
        d1.cseq = firstCseq;
        a.begin(dlg.callId, firstCseq, d1, null, null, "sip:peer@host", null);
        a.remember2xx(dlg.callId, firstCseq, ack2xx);
        JoanSipBuilder.Dialog d2 = new JoanSipBuilder.Dialog();
        d2.callId = dlg.callId;
        d2.fromTag = dlg.fromTag;
        d2.branch = reinviteBranch;
        d2.cseq = reinviteCseq;
        a.begin(dlg.callId, reinviteCseq, d2, null, null, "sip:peer@host", null);
        a.remember2xx(dlg.callId, reinviteCseq, reAck2xx);
        String newer = JoanSipBuilder.buildReInvite(id, dlg, "sip:peer@host",
                "<sip:[2001:db8::1];lr>", "ipsec-3gpp",
                "<sip:x@y>;tag=abc", "<sip:+155****0100@ims.example.net>;tag="
                        + dlg.fromTag,
                JoanSipBuilder.sdpHold("2001:db8::2", 40000, false));
        check(dlg.cseq == reinviteCseq + 1,
                "later re-INVITE advances the mutable dialog CSeq");
        check(ack2xx.equals(a.ack2xx(dlg.callId, firstCseq))
                        && reAck2xx.equals(a.ack2xx(dlg.callId, reinviteCseq)),
                "ACK archive keeps exact stale-transaction ACKs");
        check(reinviteBranch.equals(viaBranch(reinvite))
                        && !reinviteBranch.equals(viaBranch(reAck2xx))
                        && newer.contains("CSeq: " + dlg.cseq + " INVITE"),
                "stale 2xx cannot inherit a newer INVITE branch or CSeq");
        check(JoanSipBuilder.cseqForMethod(
                "SIP/2.0 200 OK\r\nCSeq: 2 INVITE\r\n\r\n", "INVITE") == 2
                        && JoanSipBuilder.cseqForMethod(
                "SIP/2.0 200 OK\r\nCSeq: 2 ACK\r\n\r\n", "INVITE") < 0,
                "CSeq parser keys replies by INVITE method as well as number");

        /* Late finals: a 2xx that arrives after the waiter gave up must
         * still be ACKed, from the send-time snapshot (pjsip's last_ack +
         * late-200 pattern), byte-identical on every retransmission. */
        JoanSipBuilder.Dialog lateDlg = new JoanSipBuilder.Dialog();
        lateDlg.callId = "late-1";
        lateDlg.fromTag = "lft";
        lateDlg.branch = "z9hG4bK-invite-br";
        lateDlg.cseq = 2;
        JoanSipBuilder.InviteAckArchive la = new JoanSipBuilder.InviteAckArchive();
        la.begin("late-1", 2, lateDlg, "<sip:x@y>;tag=abc",
                "<sip:a@b>;tag=lft", "sip:peer@host", "<sip:r>;lr");
        String late200 = "SIP/2.0 200 OK\r\n"
                + "To: <sip:x@y>;tag=abc\r\n"
                + "From: <sip:a@b>;tag=lft\r\n"
                + "Call-ID: late-1\r\n"
                + "CSeq: 2 INVITE\r\n"
                + "Contact: <sip:peer@host>\r\n\r\n";
        String la1 = la.ackLate2xx(id, "late-1", 2, "ipsec-3gpp", late200);
        check(la1 != null && la1.startsWith("ACK "),
                "late 2xx gets ACKed");
        check(la1.contains("CSeq: 2 ACK"),
                "late 2xx ACK carries the INVITE CSeq");
        check(!lateDlg.branch.equals(viaBranch(la1)),
                "late 2xx ACK gets a fresh Via branch");
        String la2 = la.ackLate2xx(id, "late-1", 2, "ipsec-3gpp", late200);
        check(la1.equals(la2),
                "late 2xx ACK resends byte-identical on retransmission");
        check(la.ackLate2xx(id, "late-1", 99, "ipsec-3gpp", late200) == null,
                "an INVITE we never sent is never ACKed");

        JoanSipBuilder.Dialog lateDlg2 = new JoanSipBuilder.Dialog();
        lateDlg2.callId = "late-2";
        lateDlg2.fromTag = "lft2";
        lateDlg2.branch = "z9hG4bK-invite-br2";
        lateDlg2.cseq = 3;
        JoanSipBuilder.InviteAckArchive la2x = new JoanSipBuilder.InviteAckArchive();
        la2x.begin("late-2", 3, lateDlg2, "<sip:x@y>;tag=abc2",
                "<sip:a@b>;tag=lft2", "sip:peer@host", "<sip:r>;lr");
        String late486 = "SIP/2.0 486 Busy Here\r\n"
                + "To: <sip:x@y>;tag=abc2\r\n"
                + "From: <sip:a@b>;tag=lft2\r\n"
                + "Call-ID: late-2\r\n"
                + "CSeq: 3 INVITE\r\n"
                + "Contact: <sip:peer@host>\r\n\r\n";
        String ln = la2x.ackLateNon2xx(id, "late-2", 3, "ipsec-3gpp", late486);
        check(ln != null && ln.contains("CSeq: 3 ACK"),
                "late non-2xx final gets ACKed");
        check(viaBranch(ln).equals(lateDlg2.branch),
                "late non-2xx ACK reuses its INVITE Via branch");

        /* Initial INVITE snapshot has no To/From/Contact yet: the late
         * response itself fills those in. */
        JoanSipBuilder.Dialog lateDlg3 = new JoanSipBuilder.Dialog();
        lateDlg3.callId = "late-3";
        lateDlg3.fromTag = "lft3";
        lateDlg3.branch = "z9hG4bK-invite-br3";
        lateDlg3.cseq = 1;
        JoanSipBuilder.InviteAckArchive la3 = new JoanSipBuilder.InviteAckArchive();
        la3.begin("late-3", 1, lateDlg3, "", "", "sip:dest@host", null);
        String late200b = "SIP/2.0 200 OK\r\n"
                + "To: <sip:b@x>;tag=bt\r\n"
                + "From: <sip:a@x>;tag=lft3\r\n"
                + "Call-ID: late-3\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:b@1.2.3.4:5060>\r\n\r\n";
        String lg = la3.ackLate2xx(id, "late-3", 1, "ipsec-3gpp", late200b);
        check(lg != null && lg.contains("sip:b@1.2.3.4:5060")
                        && lg.contains("tag=bt"),
                "late initial-invite ACK takes To/Contact from the response");
    }

    private static String viaBranch(String msg) {
        String via = JoanSipBuilder.header(msg, "Via");
        if (via == null) {
            return "";
        }
        int p = via.indexOf("branch=");
        if (p < 0) {
            return "";
        }
        int start = p + "branch=".length();
        int end = via.indexOf(';', start);
        return end < 0 ? via.substring(start) : via.substring(start, end);
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
        String inv = JoanSipBuilder.buildInvite(id, dlg, "tel:+155****0111",
                null, null, 40000, "3GPP-E-UTRAN-FDD");
        check(!inv.contains("Supported: replaces"),
                "INVITE does not claim Replaces");
        /* MESSAGE still is not implemented -- SMS would be dropped -- so
         * it must not appear. Allow lists only what we answer. */
        for (String m2 : new String[] { "MESSAGE" }) {
            check(!reg.contains(m2) && !inv.contains(m2),
                    "neither request allows " + m2);
        }
        /* UPDATE joined the list because we answer it: a network
         * refreshing the session (RFC 4028) picks a method the peer
         * allows, and an unanswered refresh tears the call down. */
        for (String m2 : new String[] { "REFER", "SUBSCRIBE", "NOTIFY",
                "PRACK", "INFO", "UPDATE" }) {
            check(reg.contains(m2) && inv.contains(m2),
                    "both allow " + m2 + " (implemented)");
        }
        check(reg.contains("Allow: " + JoanSipBuilder.ALLOW)
                && inv.contains("Allow: " + JoanSipBuilder.ALLOW),
                "both advertise exactly the handled method set");

        /* The conference SUBSCRIBE must carry the stock wire shape. */
        JoanSipBuilder.Dialog cd = new JoanSipBuilder.Dialog();
        cd.callId = "conf-1";
        cd.fromTag = "fromtag";
        String sub = JoanSipBuilder.buildConfSubscribe(id, cd,
                "sip:8881112663@msg.pc.t-mobile.com", null, null, 21600);
        check(sub.startsWith("SUBSCRIBE sip:8881112663@msg.pc.t-mobile.com "),
                "conf SUBSCRIBE targets the focus");
        check(sub.contains("Event: conference\r\n"),
                "conf SUBSCRIBE Event is conference");
        check(sub.contains("Accept: application/conference-info+xml\r\n"),
                "conf SUBSCRIBE accepts conference-info");
        check(sub.contains("Supported: replaces\r\n"),
                "conf SUBSCRIBE supports replaces");
        check(sub.contains("Expires: 21600\r\n"),
                "conf SUBSCRIBE expires 21600 like stock");

        /* REFER into the focus carries Replaces naming the leg. */
        JoanSipBuilder.Dialog rd = new JoanSipBuilder.Dialog();
        rd.callId = "leg-7";
        rd.fromTag = "a";
        String ref = JoanSipBuilder.buildReferConf(id, rd,
                "sip:peer@ims.example", null, null, null, null,
                "sip:focus?Replaces=leg-7%3Bto-tag%3Db%3Bfrom-tag%3Ba",
                "sip:+155****0100@ims.example", true);
        check(ref.startsWith("REFER sip:peer@ims.example "),
                "REFER targets the peer");
        check(ref.contains("Refer-To: <sip:focus?Replaces="),
                "REFER moves the leg into the focus");
        check(ref.contains("Referred-By: <sip:+155****0100@ims.example>"),
                "REFER identifies the referrer");
        check(!ref.contains("Refer-Sub: false"),
                "REFER keeps the subscription when referSub is on");
        String refNs = JoanSipBuilder.buildReferConf(id, rd,
                "sip:peer@ims.example", null, null, null, null,
                "sip:focus?Replaces=x", null, false);
        check(refNs.contains("Refer-Sub: false\r\n"),
                "REFER can suppress the implicit subscription (RFC 4488)");

        /* conference-info parsing: connected users only. */
        String notify = "NOTIFY sip:me SIP/2.0\r\nEvent: conference\r\n"
                + "Content-Type: application/conference-info+xml\r\n"
                + "\r\n"
                + "<conference-info>\r\n"
                + "<user entity=\"sip:a@x\"><connection status=\"connected\"/></user>\r\n"
                + "<user entity=\"sip:b@x\"><connection status=\"disconnected\"/></user>\r\n"
                + "<user entity=\"sip:c@x\"/>"
                + "</conference-info>\r\n";
        java.util.List<String> users = JoanSipBuilder.parseConferenceUsers(notify);
        check(users != null && users.size() == 1
                        && users.get(0).equals("sip:a@x"),
                "conference-info lists only connected users");
    }

    /** Offer only PCMU, and read back which codec the answer selected. */
    private static void testCodecHonesty() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example", "sip:+15555550100@ims.example",
                "ims.example", "2600::5", 5000, 5001, "123456789012345");
        String inv = JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+15555550111", null, null, 40000, "3GPP-E-UTRAN-FDD");
        /* The offer is rendered from CAPABILITIES, so it promises exactly
         * what is implemented: AMR-WB and AMR-NB (JoanAmrCodec does both
         * through MediaCodec, JoanAmr does the RFC 4867 framing) and PCMU
         * last as the interoperability floor. AMR-NB is the codec IR.92
         * makes mandatory, so omitting it was the defect. */
        check(inv.contains("m=audio 40000 RTP/AVP 96 97 0\r\n"),
                "offer lists AMR-WB, AMR-NB, then PCMU");
        check(inv.contains("a=rtpmap:96 AMR-WB/16000/1"),
                "offer names AMR-WB at the dynamic payload type");
        check(inv.contains("a=rtpmap:97 AMR/8000/1"),
                "offer names AMR-NB at the dynamic payload type");
        check(inv.contains("a=rtpmap:0 PCMU/8000"),
                "offer keeps PCMU as fallback");

        /* No split: one profile drives both directions. Everything the
         * offer promises must also be accepted when a peer offers it back,
         * which is what stopped being true when the answer was hardcoded. */
        for (JoanSipBuilder.Capability cap : JoanSipBuilder.CAPABILITIES) {
            check(inv.contains("a=rtpmap:" + cap.offerPt + " " + cap.name + "/"
                            + cap.rate),
                    "offer renders capability " + cap.name);
            String back = "v=0\r\nc=IN IP6 2600::9\r\nt=0 0\r\n"
                    + "m=audio 21000 RTP/AVP " + cap.offerPt + "\r\n"
                    + "a=rtpmap:" + cap.offerPt + " " + cap.name + "/"
                    + cap.rate + "\r\n"
                    + (cap.needsOctetAlign
                            ? "a=fmtp:" + cap.offerPt + " octet-align=1\r\n" : "");
            check(JoanSipBuilder.selectAnswerCodec(
                            JoanSipBuilder.parseSdp(back)) != null,
                    "an offer of " + cap.name + " is accepted, not just offered");
        }
        /* Bandwidth-efficient packing is not implemented, so it must not
         * be negotiated by leaving octet-align out -- its absence means
         * bandwidth-efficient, not "either". */
        check(inv.contains("octet-align=1"),
                "offer requires octet-aligned AMR");
        /* telephone-event needs an RFC 4733 event sender, not just an SDP
         * line, so it stays out of the profile until that exists. */
        check(!inv.contains("telephone-event"),
                "offer does not promise telephone-event");

        String amrAnswer = "SIP/2.0 200 OK\r\n\r\nv=0\r\n"
                + "c=IN IP6 2600::9\r\n"
                + "m=audio 21000 RTP/AVP 96\r\n"
                + "a=rtpmap:96 AMR-WB/16000/1\r\n";
        JoanSipBuilder.Media m = JoanSipBuilder.parseSdp(amrAnswer);
        check(m != null && m.payloadType == 96 && !m.offersPcmu,
                "an AMR-WB answer is recognised as not PCMU");
        check(m != null && "AMR-WB".equals(m.codecName),
                "rtpmap names the selected codec");
        String pcmuAns = "SIP/2.0 200 OK\r\n\r\nv=0\r\n"
                + "c=IN IP6 2600::9\r\n"
                + "m=audio 21000 RTP/AVP 0\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        JoanSipBuilder.Media pm = JoanSipBuilder.parseSdp(pcmuAns);
        check(pm != null && "PCMU".equals(pm.codecName),
                "a PCMU answer names PCMU");
        String other = "SIP/2.0 200 OK\r\n\r\nv=0\r\n"
                + "c=IN IP6 2600::9\r\n"
                + "m=audio 21000 RTP/AVP 97\r\n"
                + "a=rtpmap:97 G729/8000\r\n";
        JoanSipBuilder.Media om = JoanSipBuilder.parseSdp(other);
        check(om != null && "G729".equals(om.codecName),
                "an unimplemented codec is named, not mistaken for AMR");

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

    /** TS 23.003 13.3 derivation, used when the card has no ISIM. */
    private static void testDerivedIdentity() {
        check("ims.mnc260.mcc310.3gppnetwork.org".equals(
                JoanSipBuilder.derivedDomain("310260")),
                "3-digit MNC domain");
        /* China Mobile is 460/00 -- a 2-digit MNC that must pad to 3.
         * Getting this wrong yields ims.mnc00.mcc460 and a realm the core
         * has never heard of. */
        check("ims.mnc000.mcc460.3gppnetwork.org".equals(
                JoanSipBuilder.derivedDomain("46000")),
                "2-digit MNC pads to 3");
        check("ims.mnc007.mcc460.3gppnetwork.org".equals(
                JoanSipBuilder.derivedDomain("46007")),
                "2-digit MNC 07 pads to 007");
        check(JoanSipBuilder.derivedDomain("4600") == null
                && JoanSipBuilder.derivedDomain("4600001") == null
                && JoanSipBuilder.derivedDomain("46x00") == null
                && JoanSipBuilder.derivedDomain(null) == null,
                "malformed operator numeric returns null, not a guess");
        check(("460001234567890@ims.mnc000.mcc460.3gppnetwork.org").equals(
                JoanSipBuilder.derivedImpi("460001234567890", "46000")),
                "derived IMPI is IMSI@domain");
        check(JoanSipBuilder.derivedImpi("46000", "46000") == null
                && JoanSipBuilder.derivedImpi("abc460001234", "46000") == null
                && JoanSipBuilder.derivedImpi(null, "46000") == null,
                "malformed IMSI returns null");
    }

    /** sec-agree is hop-by-hop; a 420 must be retriable without it. */
    private static void testSecAgreeOnInvite() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example", "sip:+15555550100@ims.example",
                "ims.example", "2600::5", 5000, 5001, "123456789012345");
        String with = JoanSipBuilder.buildInvite(id,
                new JoanSipBuilder.Dialog(), "tel:+15555550111", null,
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD",
                true);
        String without = JoanSipBuilder.buildInvite(id,
                new JoanSipBuilder.Dialog(), "tel:+15555550111", null,
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD",
                false);
        check(with.contains("Require: sec-agree")
                && with.contains("Proxy-Require: sec-agree"),
                "INVITE carries sec-agree by default");
        check(!without.contains("Require: sec-agree")
                && !without.contains("Proxy-Require: sec-agree"),
                "retry INVITE drops both sec-agree option tags");
        /* Security-Verify is not an option tag and must survive: it is how
         * the P-CSCF matches the request to the security association. */
        check(without.contains("Security-Verify: ipsec-3gpp"),
                "retry INVITE keeps Security-Verify");
        check(JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+1", null, null, 40000, "x").contains("Require: sec-agree"),
                "the 7-arg form still defaults to sending it");
    }

    /** RFC 4867 octet-aligned framing. */
    private static void testAmrPayload() {
        /* Spec frame sizes. Getting one wrong truncates or overruns every
         * packet at that mode, with no error anywhere. */
        int[] nb = { 12, 13, 15, 17, 19, 20, 26, 31 };
        for (int ft = 0; ft < nb.length; ft++) {
            check(JoanAmr.frameBytes(ft, false) == nb[ft],
                    "AMR-NB mode " + ft + " is " + nb[ft] + " bytes");
        }
        int[] wb = { 17, 23, 32, 36, 40, 46, 50, 58, 60 };
        for (int ft = 0; ft < wb.length; ft++) {
            check(JoanAmr.frameBytes(ft, true) == wb[ft],
                    "AMR-WB mode " + ft + " is " + wb[ft] + " bytes");
        }
        check(JoanAmr.frameBytes(8, false) == 5
                && JoanAmr.frameBytes(9, true) == 5, "SID is 5 bytes");
        check(JoanAmr.frameBytes(15, true) == 0
                && JoanAmr.frameBytes(14, true) == 0,
                "NO_DATA and speech-lost carry nothing");
        check(JoanAmr.frameBytes(11, true) < 0
                && JoanAmr.frameBytes(10, false) < 0,
                "reserved frame types are refused, not invented");

        /* Storage -> RTP payload, AMR-WB mode 2 (12.65 kbit/s). */
        byte hdr = (byte) ((2 << 3) | (1 << 2));   /* FT=2, Q=1 */
        byte[] storage = new byte[1 + 32];
        storage[0] = hdr;
        for (int i = 0; i < 32; i++) {
            storage[1 + i] = (byte) (i + 1);
        }
        byte[] pay = new byte[64];
        int n = JoanAmr.pack(storage, 0, storage.length, JoanAmr.CMR_NONE,
                true, pay);
        check(n == 34, "WB mode 2 payload is CMR + ToC + 32 = 34 bytes");
        check((pay[0] & 0xff) == 0xf0, "CMR 15 (no request) in the top nibble");
        check(pay[1] == hdr && (pay[1] & 0x80) == 0,
                "ToC carries FT/Q with F clear for a single frame");
        check(pay[2] == 1 && pay[33] == 32, "speech data follows the ToC");

        /* Round trip. */
        byte[] back = new byte[64];
        int m = JoanAmr.unpack(pay, 0, n, true, back);
        check(m == storage.length, "unpack returns the storage frame length");
        boolean same = true;
        for (int i = 0; i < m; i++) {
            same &= back[i] == storage[i];
        }
        check(same, "round trip is byte-identical");

        /* Bandwidth-efficient (RFC 4867 4.3): nothing is byte-aligned.
         * CMR(4) + ToC(6) + 253 speech bits = 263 bits -> 33 bytes, one
         * less than the octet-aligned 34, and the speech no longer starts
         * on a byte boundary. */
        byte[] be = new byte[64];
        int bn = JoanAmr.packBe(storage, 0, storage.length, JoanAmr.CMR_NONE,
                true, be);
        check(JoanAmr.frameBits(2, true) == 253,
                "AMR-WB mode 2 carries 253 bits");
        check(bn == 33, "bandwidth-efficient WB mode 2 payload is 33 bytes");
        check(bn < n, "bandwidth-efficient is smaller than octet-aligned");
        check((be[0] & 0xf0) == 0xf0, "CMR 15 still occupies the top nibble");
        /* CMR(1111) F(0) FT(0010) Q(1) runs straight across the boundary:
         * byte 0 is 1111 0 001 = 0xF1, and the last FT bit plus Q begin
         * byte 1 as 01. Nothing lands where the octet-aligned form puts
         * it, which is the point. */
        check((be[0] & 0xff) == 0xf1,
                "CMR, F and the first FT bits fill byte 0");
        check((be[1] & 0x80) == 0 && (be[1] & 0x40) != 0,
                "the ToC straddles the byte boundary, Q leading byte 1");

        byte[] beBack = new byte[64];
        int bm = JoanAmr.unpackBe(be, 0, bn, true, beBack);
        check(bm == storage.length, "bandwidth-efficient unpack returns a full frame");
        boolean beSame = true;
        for (int i = 0; i < bm; i++) {
            beSame &= beBack[i] == storage[i];
        }
        check(beSame, "bandwidth-efficient round trip is byte-identical");

        /* Narrowband too, where the bit counts differ again. */
        byte nbHdr = (byte) ((7 << 3) | (1 << 2));   /* FT=7, 12.2 kbit/s */
        byte[] nbStore = new byte[1 + 31];
        nbStore[0] = nbHdr;
        for (int i = 0; i < 31; i++) {
            nbStore[1 + i] = (byte) (0x5a ^ i);
        }
        /* 244 bits is 30 whole bytes plus 4, so the last four bits of the
         * final byte are padding and carry nothing. Leaving junk there
         * would be asserting that padding survives a round trip, which it
         * must not -- bandwidth-efficient never transmits it. */
        nbStore[1 + 30] &= (byte) 0xf0;
        byte[] nbBe = new byte[64];
        int nn = JoanAmr.packBe(nbStore, 0, nbStore.length, JoanAmr.CMR_NONE,
                false, nbBe);
        check(JoanAmr.frameBits(7, false) == 244, "AMR-NB mode 7 carries 244 bits");
        check(nn == (4 + 6 + 244 + 7) / 8, "narrowband bandwidth-efficient length");
        byte[] nbBack = new byte[64];
        check(JoanAmr.unpackBe(nbBe, 0, nn, false, nbBack) == nbStore.length,
                "narrowband bandwidth-efficient unpack returns a full frame");
        boolean nbSame = true;
        for (int i = 0; i < nbStore.length; i++) {
            nbSame &= nbBack[i] == nbStore[i];
        }
        check(nbSame, "narrowband bandwidth-efficient round trip is byte-identical");

        /* Truncation must be refused, not read past the end. */
        check(JoanAmr.unpackBe(be, 0, bn - 1, true, beBack) < 0,
                "a truncated bandwidth-efficient payload is refused");
        check(JoanAmr.frameBits(11, true) < 0, "a reserved frame type carries no bits");

        /* Adaptation: a CMR or an ANBR recommendation is a mode, and a
         * bitrate has to map down to the highest mode that fits inside it
         * -- never up, or we transmit above what the network said it can
         * carry. */
        check(JoanAmr.modeBitrate(2, true) == 12650, "AMR-WB mode 2 is 12650 bps");
        check(JoanAmr.modeBitrate(7, false) == 12200, "AMR-NB mode 7 is 12200 bps");
        check(JoanAmr.modeBitrate(99, true) == 0, "an unknown mode has no bitrate");
        check(JoanAmr.bitrateMode(12650, true) == 2,
                "an exact bitrate maps to its own mode");
        check(JoanAmr.bitrateMode(13000, true) == 2,
                "a bitrate between modes rounds DOWN, never up");
        check(JoanAmr.bitrateMode(99000, true) == JoanAmr.modeCount(true) - 1,
                "a bitrate above every mode takes the top one");
        check(JoanAmr.bitrateMode(1000, true) < 0,
                "a bitrate below every mode maps to none");
        check(JoanAmr.modeCount(true) == 9 && JoanAmr.modeCount(false) == 8,
                "nine wideband modes, eight narrowband");

        /* RTCP receiver reports: what the far end says it is getting.
         * An RR is header(8) + one 24-byte report block; fraction lost is
         * the byte after the reported SSRC, cumulative loss the next
         * three, jitter at offset 16. */
        byte[] rr = new byte[32];
        rr[0] = (byte) 0x81;              // V=2, RC=1
        rr[1] = (byte) 201;               // RR
        rr[2] = 0; rr[3] = 7;             // length in words - 1
        rr[8] = 0; rr[9] = 0; rr[10] = 0; rr[11] = 9;   // reported SSRC
        rr[12] = (byte) 64;               // fraction lost = 64/256 = 25%
        rr[13] = 0; rr[14] = 1; rr[15] = 44;            // cumulative = 300
        rr[24] = 0; rr[25] = 0; rr[26] = 2; rr[27] = 88; // jitter = 600
        JoanRtcp.Report rep = JoanRtcp.parse(rr, rr.length);
        check(rep != null && rep.lossPercent() == 25,
                "fraction lost is read as a percentage");
        check(rep != null && rep.cumulativeLost == 300,
                "cumulative loss spans three bytes");
        check(rep != null && rep.jitter == 600, "interarrival jitter is read");
        /* A truncated or unknown packet must be ignored, never read past. */
        check(JoanRtcp.parse(new byte[] { (byte) 0x81, (byte) 201, 0, 7 }, 4) == null,
                "a truncated RTCP packet yields no report");
        byte[] sdes = new byte[12];
        sdes[0] = (byte) 0x81; sdes[1] = (byte) 202; sdes[2] = 0; sdes[3] = 2;
        check(JoanRtcp.parse(sdes, sdes.length) == null,
                "a non-report RTCP type is skipped, not misread");
        check(JoanRtcp.isRtcp(new byte[] { (byte) 0x80, (byte) 201, 0, 7, 0, 0, 0, 0 }, 8),
                "an RR on a muxed port is recognised as RTCP");
        check(!JoanRtcp.isRtcp(new byte[] { (byte) 0x80, (byte) 96, 0, 7, 0, 0, 0, 0 }, 8),
                "a dynamic audio payload type is not mistaken for RTCP");

        /* A multi-frame payload: two ToCs, then both frames. We take the
         * first, since this UA offers ptime 20 and never asks for more. */
        byte[] multi = new byte[1 + 2 + 64];
        multi[0] = (byte) 0xf0;
        multi[1] = (byte) (hdr | 0x80);   /* F set: another ToC follows */
        multi[2] = hdr;
        multi[3] = 0x55;
        multi[35] = 0x66;
        check(JoanAmr.unpack(multi, 0, multi.length, true, back) == 33
                && back[1] == 0x55,
                "multi-frame payload yields the first frame");

        /* Malformed input must be refused rather than half-decoded. */
        check(JoanAmr.unpack(pay, 0, 1, true, back) < 0, "refuses a 1-byte payload");
        check(JoanAmr.unpack(pay, 0, 10, true, back) < 0, "refuses a truncated frame");
        byte[] reserved = { (byte) 0xf0, (byte) (11 << 3), 0, 0 };
        check(JoanAmr.unpack(reserved, 0, 4, true, back) < 0,
                "refuses a reserved frame type");
        check(JoanAmr.pack(storage, 0, 5, JoanAmr.CMR_NONE, true, pay) < 0,
                "refuses to pack a short storage frame");
        check(JoanAmr.requestedMode(new byte[] { (byte) 0x20 }, 0, 1) == 2,
                "CMR is read from the top nibble");
    }

    private static void testOfferSummary() {
        String hdr = "ipsec-3gpp; alg=hmac-sha-1-96; ealg=null; prot=esp;"
                + " mod=trans; spi-c=1; spi-s=2; port-c=100; port-s=200; q=0.8,"
                + " ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp;"
                + " mod=trans; spi-c=3; spi-s=4; port-c=101; port-s=201; q=0.2";
        JoanSecAgree pick = JoanSecAgree.select(hdr);
        String sum = JoanSecAgree.offerSummary(hdr, pick);
        check(sum.contains("hmac-sha-1-96/null")
                && sum.contains("hmac-sha-1-96/aes-cbc"),
                "offer summary lists every mechanism");
        check(sum.indexOf('*') > 0, "offer summary marks the chosen one");
        check(sum.indexOf("spi") < 0 && sum.indexOf("port") < 0,
                "offer summary carries no SPIs or ports");
        check("none".equals(JoanSecAgree.offerSummary(null, null)),
                "no offer reads as none");
    }

    private static void testImei() {
        check("12345678-901234-5".equals(
                JoanSipBuilder.imeiInstance("123456789012345")),
                "imei instance 15 digits");
    }
}
