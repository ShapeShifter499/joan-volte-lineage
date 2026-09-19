package org.joan.ims;

/** Host tests for carrier-neutral Digest AKA + sec-agree selection. */
public final class TestJoanSip {
    private static int gFail;

    public static void main(String[] args) {
        testAkaV1();
        testAkaV2();
        testEspKeys();
        testRawMechanismCount();
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
        testSessionTimer();
        testSdpDirection();
        testSessionBandwidth();
        testRegInfo();
        testJitterBuffer();
        testPcmu();
        testCarrierCodecs();
        testRegisterRedirect();
        testRegisterShape();
        testCarrierTcpCriterion();
        testSessionId();
        testOutgoingOir();
        testRoutingDivergences();
        testRetryAfter();
        testDialIdentity();
        testUeSpiPortConvention();
        testEfDir();
        testEfDirGeometry();
        testIsimFiles();
        testXcap();
        testAuthAlgorithmGate();
        testSecurityServerRows();
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

    /**
     * A P-CSCF may spread its mechanisms over several Security-Server
     * rows. Reading only the first chose from a truncated list AND
     * echoed a partial Security-Verify, which RFC 3329 2.3.1 wants
     * returned whole.
     */
    private static void testSecurityServerRows() {
        String base = "SIP/2.0 401 Unauthorized\r\n"
                + "Via: SIP/2.0/TCP [2001:db8::2]:38866\r\n"
                + "WWW-Authenticate: Digest realm=\"ims.example\","
                + " nonce=\"abcd\", algorithm=AKAv1-MD5\r\n";
        String one = "Security-Server: ipsec-3gpp;alg=hmac-md5-96;"
                + "ealg=null;prot=esp;mod=trans;spi-c=111;spi-s=222;"
                + "port-c=5000;port-s=5001;q=0.1\r\n";
        String two = "Security-Server: ipsec-3gpp;alg=hmac-sha-1-96;"
                + "ealg=aes-cbc;prot=esp;mod=trans;spi-c=333;spi-s=444;"
                + "port-c=6000;port-s=6001;q=0.9\r\n";

        String split = base + one + two + "\r\n";
        String got = JoanSipBuilder.allSecurityServers(split);
        check(got != null && got.contains("hmac-md5-96")
                        && got.contains("hmac-sha-1-96"),
                "both Security-Server rows are collected, not just the first");
        /* The stronger mechanism sits in the SECOND row with the higher
         * q. Reading row one alone would have selected md5/null. */
        JoanSecAgree best = JoanSecAgree.select(got);
        check(best != null && best.alg.equals("hmac-sha-1-96")
                        && best.ealg.equals("aes-cbc"),
                "and the highest-q mechanism is chosen across rows");

        /* One row carrying both, comma separated, must behave the same:
         * RFC 3261 7.3.1 makes the two spellings one message. */
        String folded = base + one.replace("q=0.1\r\n", "q=0.1, ")
                + two.replaceFirst("Security-Server: ", "") + "\r\n";
        JoanSecAgree f = JoanSecAgree.select(
                JoanSipBuilder.allSecurityServers(folded));
        check(f != null && f.alg.equals("hmac-sha-1-96"),
                "a folded single row selects identically");

        check(JoanSipBuilder.allSecurityServers(base + "\r\n") == null,
                "a 401 with no Security-Server yields null, not empty");
    }

    /** The Authorization header line of a REGISTER, or "". */
    private static String authLineOf(String register) {
        for (String line : register.split("\r\n")) {
            if (line.startsWith("Authorization:")) {
                return line;
            }
        }
        return "";
    }

    /** The response="..." value of an Authorization line, or "". */
    private static String respOf(String authLine) {
        int i = authLine.indexOf("response=\"");
        if (i < 0) {
            return "";
        }
        int start = i + "response=\"".length();
        int end = authLine.indexOf('"', start);
        return end < 0 ? "" : authLine.substring(start, end);
    }

    /**
     * Authorization's `algorithm` parameter follows the carrier profile.
     *
     * <p>AOSP gates the same append on common_sip_features bit 24 and
     * sets that bit only from an ALLOW key absent from its baseline, so
     * omitting is the reference default; LG's binary gates it identically
     * and 130 of its 136 profiles leave it off. We sent it to everyone
     * because it was hardcoded. A carrier we hold no profile for still
     * gets it, which is RFC 3310 and keeps today's working lanes intact.
     */
    private static void testAuthAlgorithmGate() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        JoanSipBuilder.Params mine = new JoanSipBuilder.Params(
                1111, 1112, 38500, 39500);
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.mnc002.mcc460.3gppnetwork.org",
                "sip:user@ims.mnc002.mcc460.3gppnetwork.org",
                "ims.mnc002.mcc460.3gppnetwork.org", "2001:db8::2",
                38500, 39500, "123456789012345");
        JoanSipBuilder.Challenge ch = new JoanSipBuilder.Challenge(
                "dGVzdG5vbmNlMTIzNA==", "AKAv1-MD5",
                "ipsec-3gpp;alg=hmac-sha-1-96;ealg=null;spi-c=1;spi-s=2;"
                        + "port-c=9950;port-s=9900");
        byte[] res = JoanSipCrypto.hexBytes(
                "00112233445566778899aabbccddeeff");

        check(JoanSipBuilder.sendAuthAlgorithm(),
                "a carrier with no profile still sends algorithm= (RFC 3310)");
        JoanSipBuilder.Txn on = new JoanSipBuilder.Txn(mine, rng);
        String with = authLineOf(JoanSipBuilder.buildRegister(
                id, on, 2, ch, res, null, null, "3GPP-E-UTRAN-FDD", true));
        check(with.contains(", algorithm=AKAv1-MD5"),
                "and that REGISTER carries algorithm=AKAv1-MD5");
        try {
            JoanSipBuilder.setSendAuthAlgorithm(false);
            /* Same Txn: identical cnonce and nonce-count, so the digest
             * input is unchanged and any difference is the header alone. */
            String without = authLineOf(JoanSipBuilder.buildRegister(
                    id, on, 2, ch, res, null, null,
                    "3GPP-E-UTRAN-FDD", true));
            check(!without.contains("algorithm="),
                    "a profile that clears bit 24 omits the parameter");
            check(with.replace(", algorithm=AKAv1-MD5", "").equals(without),
                    "and that is the ONLY difference in the header");
            check(respOf(with).equals(respOf(without))
                            && !respOf(with).isEmpty(),
                    "the digest response does not depend on algorithm=");
        } finally {
            JoanSipBuilder.setSendAuthAlgorithm(true);
        }
        check(JoanSipBuilder.sendAuthAlgorithm(),
                "the gate is restored for later tests");
    }

    private static byte[] hexb(String h) {
        byte[] o = new byte[h.length() / 2];
        for (int i = 0; i < o.length; i++) {
            o[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return o;
    }

    /**
     * ADF_ISIM's files, parsed the way AOSP parses them.
     *
     * <p>IsimUiccRecords.isimTlvToString walks a record's TLVs, takes tag
     * 0x80 and decodes UTF-8, and it treats EF_IMPI, EF_IMPU, EF_DOMAIN
     * and EF_PCSCF identically. These follow it.
     */
    private static void testIsimFiles() {
        /* EF_IMPI: 80 <len> <NAI>. Built rather than hand-written: the
         * first draft of this test hard-coded the length bytes and got
         * two of them wrong, which is the same mistake the parser exists
         * to survive. */
        check("310123456789012@ims.example".equals(
                        JoanIsim.text(tlv80("310123456789012@ims.example"))),
                "EF_IMPI decodes the tag-0x80 value as text");
        check(JoanIsim.text(hexb("FFFFFFFF")) == null,
                "a padded record decodes to nothing");
        check(JoanIsim.text(hexb("8105414243")) == null,
                "a record with no tag 0x80 decodes to nothing");
        check(JoanIsim.text(hexb("80FF4142")) == null,
                "a length longer than the record is refused");

        /* EF_PCSCF: TS 31.103 4.2.8 puts an address-type byte first --
         * 00 FQDN, 01 IPv4, 02 IPv6 -- which AOSP leaves in the string. */
        check("pcscf.example.com".equals(
                        JoanIsim.pcscf(tlv80((char) 0x00 + "pcscf.example.com"))),
                "EF_PCSCF strips the FQDN address-type byte");
        check("pcscf.example.com".equals(
                        JoanIsim.pcscf(tlv80("sip:pcscf.example.com"))),
                "and a bare sip: URI with no type byte also reads");
        check(JoanIsim.pcscf(hexb("FFFF")) == null,
                "a padded P-CSCF record yields nothing");

        /* EF_IST: services number from 1, eight per byte, LSB first, so
         * service 5 is bit 4 of byte 0. TS 31.103 4.2.7. */
        check(JoanIsim.istService(hexb("10"), 5),
                "EF_IST reports service 5 present when bit 4 is set");
        check(!JoanIsim.istService(hexb("0F"), 5),
                "and absent when it is not");
        check(JoanIsim.istService(hexb("0001"), 9),
                "service 9 is bit 0 of the second byte");
        check(!JoanIsim.istService(hexb("10"), 99),
                "a service past the end of a short table reads as absent");
        check(!JoanIsim.istService(null, 5),
                "an unreadable table claims nothing");
    }

    /**
     * Ut/XCAP addressing and document reading.
     *
     * <p>Nothing here is advertised: Ut is not a SIP capability tag, so
     * this claims nothing to the network. The line not crossed is
     * declaring ImsUtImplBase, at which point Settings would route here
     * and bypass the CS/MMI path that works today.
     */
    private static void testXcap() {
        /* RFC 4825 4: <root>/<auid>/users/<XUI>/<document>. The XUI is a
         * SIP URI inside a path segment, so its colon and @ must be
         * escaped or they read as URI syntax instead of as data. */
        String u = JoanXcap.documentUri("xcap.example.com", 80, false,
                "sip:user@ims.mnc002.mcc460.3gppnetwork.org");
        check(u != null && u.startsWith("http://xcap.example.com/"),
                "a plain XCAP root builds an http URI");
        check(u != null && u.contains("/simservs.ngn.etsi.org/users/"),
                "with the simservs AUID and users collection");
        check(u != null && u.contains("sip%3Auser%40ims."),
                "and the SIP URI percent-encoded inside the path segment");
        check(u != null && u.endsWith("/simservs.xml"),
                "addressing the simservs document");

        /* The default port for the scheme is omitted; anything else is
         * written, or the request goes to the wrong place. */
        check(!JoanXcap.documentUri("x.example.com", 80, false, "sip:a@b")
                        .contains(":80/"),
                "the default http port is left out");
        check(JoanXcap.documentUri("x.example.com", 443, true, "sip:a@b")
                        .startsWith("https://x.example.com/"),
                "and 443 is the default for https");
        check(JoanXcap.documentUri("x.example.com", 8080, false, "sip:a@b")
                        .contains(":8080/"),
                "a non-default port is written out");

        /* A half-built URI is worse than none: 80 of 136 profiles carry
         * no XCAP server at all. */
        check(JoanXcap.documentUri("", 80, false, "sip:a@b") == null,
                "no server yields no URI");
        check(JoanXcap.documentUri("x.example.com", 80, false, "") == null,
                "no identity yields no URI");
        check(JoanXcap.documentUri("x.example.com/evil", 80, false,
                        "sip:a@b") == null,
                "a server carrying a path is refused, not concatenated");

        /* TS 24.623: each service element carries an active attribute,
         * and prefixes vary between carriers so matching is on the local
         * name. Absent and inactive are DIFFERENT answers -- absent means
         * not provisioned -- so absent is null, never false. */
        String doc = "<?xml version=\"1.0\"?>"
                + "<simservs xmlns=\"http://uri.etsi.org/ngn/params/xml/simservs/xcap\">"
                + "<originating-identity-presentation-restriction active=\"false\">"
                + "<default-behaviour>presentation-not-restricted</default-behaviour>"
                + "</originating-identity-presentation-restriction>"
                + "<ss:communication-diversion active=\"true\"/>"
                + "<incoming-communication-barring/>"
                + "</simservs>";
        check(Boolean.FALSE.equals(JoanXcap.serviceActive(doc,
                        "originating-identity-presentation-restriction")),
                "a service marked active=false reads as inactive");
        check(Boolean.TRUE.equals(JoanXcap.serviceActive(doc,
                        "communication-diversion")),
                "a prefixed element is matched on its local name");
        check(Boolean.TRUE.equals(JoanXcap.serviceActive(doc,
                        "incoming-communication-barring")),
                "no active attribute defaults to active, per TS 24.623");
        check(JoanXcap.serviceActive(doc, "outgoing-communication-barring")
                        == null,
                "an absent service is null, NOT false: not provisioned and "
                        + "provisioned-but-off are different answers");
        check(JoanXcap.serviceActive(null, "x") == null,
                "no document yields no answer");
    }

    /** A tag-0x80 TLV holding this text, with the length computed. */
    private static byte[] tlv80(String text) {
        byte[] v = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] out = new byte[v.length + 2];
        out[0] = (byte) 0x80;
        out[1] = (byte) v.length;
        System.arraycopy(v, 0, out, 2, v.length);
        return out;
    }

    /** ASCII to hex, for building card records in tests. */
    private static String hexOf(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            b.append(String.format("%02X", c));
        }
        return b.toString();
    }

    /**
     * EF_DIR is the card's own statement of what it holds.
     *
     * <p>We used to guess a 16-byte AID and read the failure, which
     * cannot tell "this card has no ISIM" apart from "not under the
     * identifier you asked for" -- and the bench card proves those differ,
     * since its USIM AID is not the one we shipped.
     */
    private static void testEfDirGeometry() {
        /* The legacy TS 51.011 9.2.1 structure, which is what
         * iccExchangeSimIO actually returns: the RIL normalises the
         * card's FCP into it, and AOSP's IccFileHandler parses only this.
         * Reading it as a BER-TLV FCP is what made every live EF_DIR read
         * answer "no record geometry" while the host tests passed.
         *
         * file size 0x0098 = 152 at bytes 2-3, type EF (4) at byte 6,
         * linear fixed (1) at byte 13, record length 0x26 = 38 at byte
         * 14 -> 152/38 = 4 records. */
        byte[] legacy = {
            0x00, 0x00, 0x00, (byte) 0x98, 0x2f, 0x00, 0x04, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x02, 0x01, 0x26,
        };
        int[] g = JoanEfDir.parseRecordInfo(legacy);
        check(g != null && g[0] == 38 && g[1] == 4,
                "legacy GET RESPONSE gives 38-byte records, 4 of them");
        check(JoanEfDir.parseFcpRecordInfo(legacy) == null,
                "the FCP parser alone cannot read it -- the shipped bug");

        /* A BER-TLV FCP, for a RIL that passes one through untouched. */
        byte[] fcp = {
            0x62, 0x1e, (byte) 0x82, 0x05, 0x42, 0x21, 0x00, 0x26, 0x04,
            (byte) 0x83, 0x02, 0x2f, 0x00,
        };
        int[] f = JoanEfDir.parseRecordInfo(fcp);
        check(f != null && f[0] == 38 && f[1] == 4,
                "a real FCP still parses, chosen by its 0x62 tag");

        /* A transparent file answers the same call and must not yield a
         * confident wrong geometry: structure byte is 0, not 1. */
        byte[] transparent = {
            0x00, 0x00, 0x00, (byte) 0x98, 0x2f, 0x02, 0x04, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00,
        };
        check(JoanEfDir.parseRecordInfo(transparent) == null,
                "a transparent file is refused, not guessed at");

        byte[] wrongType = {
            0x00, 0x00, 0x00, (byte) 0x98, 0x2f, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x02, 0x01, 0x26,
        };
        check(JoanEfDir.parseRecordInfo(wrongType) == null,
                "a non-EF file type is refused");

        byte[] shortSize = {
            0x00, 0x00, 0x00, 0x10, 0x2f, 0x00, 0x04, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x02, 0x01, 0x26,
        };
        check(JoanEfDir.parseRecordInfo(shortSize) == null,
                "a file smaller than one record is refused");

        check(JoanEfDir.parseRecordInfo(null) == null
                && JoanEfDir.parseRecordInfo(new byte[0]) == null
                && JoanEfDir.parseRecordInfo(new byte[] {0x00, 0x01}) == null,
                "short and absent responses are refused");

        check("none".equals(JoanEfDir.head(null))
                && "62 1e 82 05".replace(" ", "").equals(JoanEfDir.head(fcp)),
                "head() names what came back for the trace");
    }

    private static void testEfDir() {
        /* TS 102 221 11.1.1.3: 61 <len> { 4F <len> AID, 50 <len> label }.
         * This is the bench card's real USIM entry, labelled "USIM". */
        byte[] rec = hexb("61184F10A0000000871002FFFFFFFF8906190000"
                + "50045553494D");
        java.util.List<String> aids = JoanEfDir.parseRecord(rec);
        check(aids.size() == 1
                        && aids.get(0).equals("A0000000871002FFFFFFFF8906190000"),
                "an EF_DIR record yields the card's real AID");
        check(JoanEfDir.firstWithPrefix(aids, JoanEfDir.USIM_PREFIX) != null,
                "and it is matched by the 3GPP USIM prefix");
        check(JoanEfDir.firstWithPrefix(aids, JoanEfDir.ISIM_PREFIX) == null,
                "while the ISIM prefix does not match a USIM entry");

        /* An unused record is 0xFF padding, which is not an error. */
        check(JoanEfDir.parseRecord(hexb("FFFFFFFFFFFF")).isEmpty(),
                "a padded record yields nothing and does not throw");
        check(JoanEfDir.parseRecord(null).isEmpty(),
                "a null record yields nothing");
        /* A truncated length must not read past the buffer. */
        check(JoanEfDir.parseRecord(hexb("61FF4F10A000")).isEmpty(),
                "a record claiming more than it holds is refused");

        /* File Descriptor: 82 05 <fd> <coding> <reclen hi> <reclen lo> <n> */
        int[] g = JoanEfDir.parseFcpRecordInfo(
                hexb("621A8205422100260483022F00"));
        check(g != null && g[0] == 0x26 && g[1] == 4,
                "the FCP names the record length and count");
        check(JoanEfDir.parseFcpRecordInfo(hexb("62048202412100")) == null,
                "a two-byte descriptor is not record-based and is refused");
        check(JoanEfDir.parseFcpRecordInfo(null) == null,
                "a null SELECT response is refused");
    }

    /**
     * The UE sec-agree parameters must follow stock's convention:
     * spi-s is spi-c + 1, both at or above SPI_MIN, and the protected
     * ports come from stock's two windows (38001-39000 client, one
     * PORTS_INTERVAL higher for the server).
     *
     * A zero SPI is not legal and our own peer parser rejects one, so
     * the counter must never produce one however long it runs.
     */
    private static void testUeSpiPortConvention() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        long prev = -1;
        boolean adjacent = true;
        boolean floored = true;
        boolean inWindow = true;
        boolean serverOffset = true;
        boolean nonZero = true;
        boolean distinct = true;
        for (int i = 0; i < 20000; i++) {
            JoanSipBuilder.Params p = JoanSipBuilder.Params.random(rng);
            adjacent &= (p.spiS == p.spiC + 1L);
            floored &= (p.spiC >= 1000000000L);
            nonZero &= (p.spiC != 0L && p.spiS != 0L);
            /* Both must survive the cast to the signed int that
             * IpSecManager.allocateSecurityParameterIndex() takes, a
             * path joan has only ever exercised with positive values. */
            floored &= (p.spiS <= 0x7fffffffL);
            floored &= ((int) p.spiC > 0 && (int) p.spiS > 0);
            floored &= ((long) (int) p.spiC == p.spiC);
            inWindow &= (p.portC >= 38001 && p.portC <= 39000);
            serverOffset &= (p.portS == p.portC + 1000);
            distinct &= (p.spiC != prev);
            prev = p.spiC;
        }
        check(adjacent, "spi-s is spi-c + 1, as stock emits it");
        check(floored, "spi-c is at or above SPI_MIN and fits 32 bits");
        check(nonZero, "neither SPI is ever zero");
        check(distinct, "each attempt gets its own spi-c");
        check(inWindow, "port-c comes from stock's 38001-39000 window");
        check(serverOffset, "port-s is one PORTS_INTERVAL above port-c");
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

        /* The four mechanism corners, because the field only ever proved
         * two of them and they were the two already tested here.
         * T-Mobile registers on sha1+aes and China Telecom on md5+null --
         * which left sha1+null, China Mobile's mechanism, as the one
         * combination nothing had run. The branches are orthogonal (auth
         * from alg, encryption from ealg), so this is coverage rather
         * than a suspected defect; it stops the corner being untested. */
        JoanSipCrypto.EspKeys shaNull = JoanSipCrypto.espKeys(
                "hmac-sha-1-96", "null", ck, ik);
        check(shaNull.authKey.length == 20 && !shaNull.hasEncryption(),
                "sha1-96 + null encryption is integrity-only (CMCC's mechanism)");
        check(shaNull.authKey[15] == 15 && shaNull.authKey[16] == 0
                        && shaNull.authKey[19] == 0,
                "and still pads IK to 160 bits with no encryption to carry");
        check(shaNull.authTruncBits == 96 && md5Null.authTruncBits == 96,
                "both integrity-only mechanisms truncate to 96 bits");
        check(shaNull.androidAuth.equals("hmac(sha1)")
                        && md5Null.androidAuth.equals("hmac(md5)"),
                "and each names its own kernel algorithm");
        JoanSipCrypto.EspKeys md5Aes = JoanSipCrypto.espKeys(
                "hmac-md5-96", "aes-cbc", ck, ik);
        check(md5Aes.authKey.length == 16 && md5Aes.hasEncryption(),
                "md5-96 + aes-cbc is the fourth corner and builds too");

        /* The auth key is IK. CK is the encryption key, and a swap would
         * still produce a well-formed SA that no assertion on lengths
         * could see -- the network would simply reject every packet. */
        check(shaAes.authKey[0] == ik[0] && shaAes.authKey[15] == ik[15],
                "the authentication key is IK, not CK");
        check(shaAes.encKey[0] == ck[0] && shaAes.encKey[15] == ck[15],
                "and the encryption key is CK");

        /* RFC 3329 makes null the default when ealg is absent, and a
         * Security-Server row that omits it reaches here as an empty
         * string rather than the word. */
        JoanSipCrypto.EspKeys omitted = JoanSipCrypto.espKeys(
                "hmac-sha-1-96", "", ck, ik);
        check(!omitted.hasEncryption() && omitted.authKey.length == 20,
                "an omitted ealg builds the same SA as an explicit null");
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

    private static java.util.List<String> headerRows(String msg, String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : msg.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                out.add(line.substring(name.length() + 1).trim());
            }
        }
        return out;
    }

    private static int countHeaderRows(String msg, String name) {
        return headerRows(msg, name).size();
    }

    private static void testRawMechanismCount() {
        /* The count that says whether offered= told the whole story. */
        String two = "ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp; "
                + "mod=trans; spi-c=1; spi-s=2; port-c=5061; port-s=5062, "
                + "ipsec-3gpp; alg=hmac-md5-96; ealg=null; prot=esp; "
                + "mod=trans; spi-c=3; spi-s=4; port-c=5063; port-s=5064";
        check(JoanSecAgree.rawMechanismCount(two) == 2
                        && JoanSecAgree.parseAll(two).size() == 2,
                "two well-formed mechanisms count and parse alike");
        check(JoanSecAgree.rawMechanismCount("Security-Server: " + two) == 2,
                "the header name is stripped before counting");

        /* The shape reported in the wild: several values in one unquoted
         * parameter. RFC 3329 separates MECHANISMS by comma, so this one
         * mechanism splits in two and neither half survives -- the first
         * loses its SPIs, the second its mechanism name. Before the count
         * existed, offered= showed nothing and the header looked empty. */
        String merged = "ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc,null; "
                + "prot=esp; mod=trans; spi-c=1; spi-s=2; port-c=5061; "
                + "port-s=5062";
        check(JoanSecAgree.rawMechanismCount(merged) == 2,
                "a comma inside a parameter splits the mechanism in two");
        check(JoanSecAgree.parseAll(merged).isEmpty(),
                "and neither half parses, so the count exposes the loss");
        check(JoanSecAgree.rawMechanismCount(null) == 0
                        && JoanSecAgree.rawMechanismCount("") == 0,
                "an absent header counts zero rather than throwing");
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

        /* Reference-stack fidelity, from RegParameter::
         * ChoosePreferredSecurityServer + SipSecurityHeader matching. */
        JoanSecAgree tie = JoanSecAgree.select(
                "ipsec-3gpp; q=0.3; alg=hmac-md5-96; ealg=null; "
                        + "spi-c=1; spi-s=2; port-c=1000; port-s=1001, "
                        + "ipsec-3gpp; q=0.3; alg=hmac-sha-1-96; ealg=aes-cbc; "
                        + "spi-c=3; spi-s=4; port-c=2000; port-s=2001");
        check(tie != null && tie.spiC == 1,
                "an equal-q tie keeps the first listed mechanism");

        JoanSecAgree unprot = JoanSecAgree.select(
                "ipsec-3gpp; q=0.9; prot=ah; alg=hmac-sha-1-96; ealg=aes-cbc; "
                        + "spi-c=1; spi-s=2; port-c=1000; port-s=1001, "
                        + "ipsec-3gpp; q=0.1; alg=hmac-sha-1-96; ealg=aes-cbc; "
                        + "spi-c=3; spi-s=4; port-c=2000; port-s=2001");
        check(unprot != null && unprot.spiC == 3,
                "an AH row loses to an ESP row at any preference");

        JoanSecAgree tun = JoanSecAgree.select(
                "ipsec-3gpp; q=0.9; mod=tun; alg=hmac-sha-1-96; ealg=aes-cbc; "
                        + "spi-c=1; spi-s=2; port-c=1000; port-s=1001, "
                        + "ipsec-3gpp; q=0.1; alg=hmac-sha-1-96; ealg=null; "
                        + "spi-c=3; spi-s=4; port-c=2000; port-s=2001");
        check(tun != null && tun.spiC == 3,
                "a tunnel-mode row loses to transport at any preference");

        JoanSecAgree noEalg = JoanSecAgree.select(
                "ipsec-3gpp; alg=hmac-sha-1-96; "
                        + "spi-c=7; spi-s=8; port-c=3000; port-s=3001");
        check(noEalg != null && noEalg.ealg.equals("null")
                        && noEalg.prot.equals("esp") && noEalg.mod.equals("trans"),
                "an omitted ealg reads as null, prot and mode as their defaults");

        /* The carrier mask: it shapes both what we offer and what a
         * server row must match. TMO 0x10003: low word 3 = both
         * integrity algorithms, high word 1 = aes-cbc only -- so md5 and
         * sha1 over aes, and nothing over null. */
        try {
            JoanSipCrypto.setOfferMask(0x10003);
            JoanSecAgree masked = JoanSecAgree.select(
                    "ipsec-3gpp; q=0.9; alg=hmac-sha-1-96; ealg=null; "
                            + "spi-c=1; spi-s=2; port-c=1000; port-s=1001, "
                            + "ipsec-3gpp; q=0.1; alg=hmac-md5-96; ealg=aes-cbc; "
                            + "spi-c=3; spi-s=4; port-c=2000; port-s=2001");
            check(masked != null && masked.spiC == 3
                            && masked.alg.equals("hmac-md5-96"),
                    "an aes-only mask rejects a higher-q null row");

            /* A SOLE mechanism is taken as offered, mask or no mask.
             * RegParameter::ChoosePreferredSecurityServer copies element
             * zero and returns before it reaches the Security-Client
             * comparison, so a P-CSCF naming one mechanism is never
             * argued with. joan applied the mask to a single row from the
             * moment it first read one -- a veto stock does not have, and
             * one that would refuse a lone md5-or-null row on T-Mobile,
             * whose mask is aes-only, on a lane that registers today. */
            String soleMasked = "ipsec-3gpp; alg=hmac-md5-96; ealg=null; "
                    + "spi-c=7; spi-s=8; port-c=3000; port-s=3001";
            JoanSecAgree sole = JoanSecAgree.select(soleMasked);
            check(sole != null && sole.spiC == 7,
                    "a sole mechanism is taken even when the mask excludes it");
            check(!JoanSecAgree.offerSummary(soleMasked, sole)
                            .contains("not-offered"),
                    "and is not annotated as a rejection that never happened");
            check(JoanSecAgree.offerSummary(soleMasked, sole).contains("*"),
                    "the sole row is marked as the one chosen");

            /* What a sole row does NOT buy: a mechanism we could not
             * build. Stock would take it and fail later assembling the
             * SA; failing here names the reason. */
            check(JoanSecAgree.select("ipsec-3gpp; alg=hmac-sha-1-96; "
                            + "ealg=aes-cbc; prot=ah; mod=trans; spi-c=1; "
                            + "spi-s=2; port-c=1000; port-s=1001") == null,
                    "a sole AH row is still refused: we cannot build it");
            check(JoanSecAgree.select("ipsec-3gpp; alg=hmac-sha-1-96; "
                            + "ealg=aes-cbc; prot=esp; mod=tun; spi-c=1; "
                            + "spi-s=2; port-c=1000; port-s=1001") == null,
                    "and a sole tunnel-mode row likewise");
            check(JoanSecAgree.select("ipsec-3gpp; alg=hmac-sha-256-128; "
                            + "ealg=aes-cbc; spi-c=1; spi-s=2; "
                            + "port-c=1000; port-s=1001") == null,
                    "and a sole row naming an algorithm we do not implement");

            /* The mask still governs where the reference uses it: two or
             * more rows, matched against the client offer. */
            check(JoanSecAgree.select(
                    "ipsec-3gpp; q=0.9; alg=hmac-md5-96; ealg=null; "
                            + "spi-c=5; spi-s=6; port-c=1000; port-s=1001, "
                            + "ipsec-3gpp; q=0.1; alg=hmac-sha-1-96; "
                            + "ealg=aes-cbc; spi-c=9; spi-s=10; "
                            + "port-c=2000; port-s=2001").spiC == 9,
                    "with two rows the mask still rejects the higher-q one");

            JoanSipBuilder.Params p2 = new JoanSipBuilder.Params(
                    1111, 2222, 15000, 16000);
            String offer = JoanSecAgree.cartesianClientValue(p2);
            check(offer.contains("hmac-sha-1-96") && offer.contains("aes-cbc")
                            && offer.contains("hmac-md5-96")
                            && !offer.contains("ealg=null"),
                    "the TMO-shaped offer carries both algs over aes only");
            check(countMechanisms(offer) == 2,
                    "two mechanisms in the TMO-shaped offer");

            /* Stock parameter order. SipSecurityHeader::ToString writes
             * q, alg, prot, mod, ealg, spi-c, spi-s, port-c, port-s and
             * MakeSecurityClientH never sets a preference, so a stock
             * offer names ealg after mod and carries no q at all. */
            check(offer.indexOf("alg=") < offer.indexOf("prot=")
                            && offer.indexOf("prot=") < offer.indexOf("mod=")
                            && offer.indexOf("mod=") < offer.indexOf("ealg=")
                            && offer.indexOf("ealg=") < offer.indexOf("spi-c="),
                    "the offer follows the reference stack's parameter order");
            check(!offer.contains("q="),
                    "and carries no q, which stock never sets on a client offer");
            check(offer.indexOf("spi-c=") < offer.indexOf("spi-s=")
                            && offer.indexOf("spi-s=") < offer.indexOf("port-c=")
                            && offer.indexOf("port-c=") < offer.indexOf("port-s="),
                    "SPIs precede ports, as the reference serialiser writes them");
            check(offer.contains("spi-c=1111;") || offer.contains("spi-c=1111,")
                            || offer.endsWith("spi-c=1111")
                            || offer.contains("spi-c=1111 "),
                    "the SPI is written plainly, never zero-padded");

            JoanSipCrypto.setOfferMask(0x70003);
            check(countMechanisms(
                    JoanSecAgree.cartesianClientValue(p2)) == 4,
                    "the CMCC-shaped mask offers all four mechanisms");

            JoanSipCrypto.setOfferMask(0x40002);
            check(countMechanisms(
                    JoanSecAgree.cartesianClientValue(p2)) == 4,
                    "a 3DES-only mask falls back to the full offer");

            /* Annotations: unparsed and non-ipsec rows are named, not
             * dropped. */
            JoanSipCrypto.setOfferMask(-1);
            String chosenList =
                    "ipsec-3gpp; q=0.5; alg=hmac-sha-1-96; ealg=aes-cbc; "
                            + "spi-c=1; spi-s=2; port-c=1000; port-s=1001";
            String summary = JoanSecAgree.offerSummary(
                    "tls; q=0.9, " + chosenList + ", "
                            + "ipsec-3gpp; q=0.8; prot=ah; alg=hmac-md5-96; "
                            + "ealg=aes-cbc; spi-c=5; spi-s=6; port-c=7; port-s=8",
                    JoanSecAgree.select(chosenList));
            check(summary.contains("tls(not-ipsec)"),
                    "a non-ipsec mechanism is named in the summary");
            check(summary.contains("(prot)"),
                    "an AH row's rejection reason is named");
            check(summary.contains("*"),
                    "the chosen mechanism is still marked");
        } finally {
            JoanSipCrypto.setOfferMask(-1);
        }
    }

    private static int countMechanisms(String offer) {
        int n = 0;
        for (String part : offer.split(",")) {
            if (part.contains("ipsec-3gpp")) {
                n++;
            }
        }
        return n;
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
        /* One header row per mechanism, the way the reference emits them.
         * RegParameter::AddSecurityHeaders walks the Security-Client list
         * and calls AddHeader once per element, so a stock REGISTER
         * carries four rows where the mask allows four combinations.
         * joan wrote one row with the mechanisms comma-joined -- the same
         * message by RFC 3261 7.3.1, but not the shape a P-CSCF whose
         * parser reads a mechanism per row is expecting. */
        check(countHeaderRows(msg, "Security-Client") == 4,
                "reg1 writes one Security-Client row per mechanism");
        for (String row : headerRows(msg, "Security-Client")) {
            check(!row.contains(","),
                    "no Security-Client row carries a comma-joined list");
            check(row.contains("spi-c=") && row.contains("port-s="),
                    "and each row is a whole mechanism, not a fragment");
        }
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
        /* PLMN scoping -- that China Unicom (46001) must not inherit
         * China Mobile's value -- is now a property of the snapshot
         * lookup rather than of routing, because routing no longer
         * consults the snapshot. Every 3GPP realm gets the same computed
         * criterion, so the assertion moves to where the distinction
         * still exists. */
        check(JoanSipBuilder.tcpCriterionFor(
                        "ims.mnc001.mcc460.3gppnetwork.org", false) == 4096
                        && JoanSipBuilder.tcpCriterionFor(
                        "ims.mnc000.mcc460.3gppnetwork.org", false) == 1300,
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

        /* Security-Verify gets a row per mechanism too. Service.cpp and
         * RegParameter.cpp both loop their list and AddHeader once per
         * element, on the REGISTER path and inside a dialog alike. The
         * server's whole list still goes back -- RFC 3329 2.3.1 wants it
         * returned so the P-CSCF can spot tampering -- but as rows. */
        JoanSipBuilder.Challenge twoRow = new JoanSipBuilder.Challenge(
                "dGVzdG5vbmNlMTIzNA==", "AKAv1-MD5",
                "ipsec-3gpp;alg=hmac-md5-96;ealg=null;spi-c=1;spi-s=2;"
                        + "port-c=9950;port-s=9900, "
                        + "ipsec-3gpp;alg=hmac-sha-1-96;ealg=aes-cbc;spi-c=3;"
                        + "spi-s=4;port-c=9951;port-s=9901");
        String twoRowMsg = JoanSipBuilder.buildRegister(id, txn, 3, twoRow,
                res, null, null, "3GPP-E-UTRAN-TDD", true);
        check(countHeaderRows(twoRowMsg, "Security-Verify") == 2,
                "a two-mechanism Security-Server echoes as two Security-Verify rows");
        for (String row : headerRows(twoRowMsg, "Security-Verify")) {
            check(!row.contains(",") && row.contains("spi-c="),
                    "and each echoed row is one whole mechanism");
        }
        check(twoRowMsg.contains("spi-c=1") && twoRowMsg.contains("spi-c=3"),
                "the server's whole list goes back, not just the chosen row");
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
                "a message over the computed criterion goes TCP");
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
        /* Changed deliberately with the MTU-derived criterion. A 2500
         * MTU is clamped to max_allowed_network_mtu (1500), so it gives
         * the same 1300 as a 1500 bearer -- joan used to let an
         * over-large MTU keep a big message on UDP. */
        check(JoanSipBuilder.preferTcp(nos, 1630, 2500, true),
                "an MTU above 1500 is clamped, so 1630 still goes TCP");
        /* IPv4 and IPv6 now share one calculation. joan used to apply
         * RFC 3261 18.1.1 to IPv6 only and leave IPv4 on the carrier
         * criterion, which left 1568- and 1830-byte IPv4 REGISTERs on
         * UDP well past the point of fragmenting. */
        check(JoanSipBuilder.preferTcp(viettel, 1568, 0, false),
                "IPv4 uses the same criterion as IPv6 (unknown MTU -> 1300)");
        check(JoanSipBuilder.preferTcp(viettel, 1830, 1500, false),
                "and an 1830-byte IPv4 REG2 no longer stays on UDP");
        check(!JoanSipBuilder.preferTcp(tmus, 1830, 0, true)
                        && !JoanSipBuilder.preferTcp(tmus, 9216, 1280, true),
                "TMUS never leaves UDP even on IPv6/small MTU");
        check(!JoanSipBuilder.preferTcp(null, 1630, 0, true),
                "non-3GPP realm never flips REG1 to TCP");
    }

    /**
     * The carrier's own codec offer: payload numbers, one entry per
     * framing, and mode-set.
     */
    private static void testCarrierCodecs() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example.net", "sip:+15555550100@ims.example.net",
                "ims.example.net", "2001:db8::2", 25000, 26000,
                "123456789012345");

        /* Exactly what T-Mobile 310-260 publishes: each codec twice, the
         * bandwidth-efficient entry first, telephone-event on 101/102. */
        java.util.List<JoanSipBuilder.Capability> tmo =
                new java.util.ArrayList<>();
        tmo.add(JoanSipBuilder.Capability.amr("AMR-WB", 16000, 97, false, null));
        tmo.add(JoanSipBuilder.Capability.amr("AMR-WB", 16000, 98, true, null));
        tmo.add(JoanSipBuilder.Capability.amr("AMR", 8000, 99, false, null));
        tmo.add(JoanSipBuilder.Capability.amr("AMR", 8000, 100, true, null));
        JoanSipBuilder.restrictProfile(
                java.util.Arrays.asList("AMR-WB", "AMR"));
        JoanSipBuilder.applyCarrierCodecs(tmo, 101, 102);

        String inv = JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+15555550999", "<sip:[2001:db8::1]:5060;lr>",
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD");

        check(inv.contains("a=rtpmap:97 AMR-WB/16000/1"),
                "carrier AMR-WB payload type is offered");
        check(inv.contains("a=fmtp:97 octet-align=0;mode-change-capability=2"),
                "bandwidth-efficient entry says octet-align=0");
        check(inv.contains("a=fmtp:98 octet-align=1;mode-change-capability=2"),
                "octet-aligned entry says octet-align=1");
        check(inv.contains("a=rtpmap:99 AMR/8000/1"),
                "carrier AMR-NB payload type is offered");
        check(inv.contains("a=rtpmap:101 telephone-event/16000"),
                "carrier wideband telephone-event payload type");
        check(inv.contains("a=rtpmap:102 telephone-event/8000"),
                "carrier narrowband telephone-event payload type");
        /* The whole point: the network gets to choose the framing. */
        check(inv.contains("octet-align=0") && inv.contains("octet-align=1"),
                "both framings offered, so a BE network need not fall to PCMU");
        /* PCMU is never in carrier config and must still be the floor. */
        check(inv.contains("a=rtpmap:0 PCMU/8000"),
                "PCMU floor survives a carrier codec list");

        /* A mode-set, which T-Mobile does not set but others do. */
        java.util.List<JoanSipBuilder.Capability> ms =
                new java.util.ArrayList<>();
        ms.add(JoanSipBuilder.Capability.amr("AMR-WB", 16000, 96, true,
                new int[] {0, 1, 2}));
        JoanSipBuilder.applyCarrierCodecs(ms, 0, 0);
        String inv2 = JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+15555550999", "<sip:[2001:db8::1]:5060;lr>",
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD");
        check(inv2.contains("mode-set=0,1,2"), "carrier mode-set is offered");
        check(!inv2.contains("a=rtpmap:99 "),
                "a replaced carrier list does not leak the previous one");
        /* teWbPt=0 means "carrier said nothing"; keep the defaults. */
        check(inv2.contains("a=rtpmap:100 telephone-event/16000"),
                "a zero telephone-event payload type keeps the default");

        /* The probe still wins: a ROM without AMR-WB must not offer it
         * however loudly carrier config asks. */
        JoanSipBuilder.restrictProfile(java.util.Arrays.asList("AMR"));
        JoanSipBuilder.applyCarrierCodecs(tmo, 101, 102);
        String inv3 = JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+15555550999", "<sip:[2001:db8::1]:5060;lr>",
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD");
        check(!inv3.contains("AMR-WB"),
                "probe still vetoes a codec the ROM cannot run");
        check(inv3.contains("a=rtpmap:99 AMR/8000/1"),
                "and keeps the one it can");

        /* Back to the built-in table for every later test. */
        JoanSipBuilder.applyCarrierCodecs(null, 0, 0);
        JoanSipBuilder.restrictProfile(
                java.util.Arrays.asList("AMR-WB", "AMR"));
        String inv4 = JoanSipBuilder.buildInvite(id, new JoanSipBuilder.Dialog(),
                "tel:+15555550999", "<sip:[2001:db8::1]:5060;lr>",
                "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD");
        check(inv4.contains("a=rtpmap:96 AMR-WB/16000/1"),
                "null carrier list restores the built-in profile");
    }

    /**
     * The REGISTER's instance-id, its optional Contact tags, and the
     * identity-free header shape used to diagnose them.
     */
    private static void testRegisterShape() {
        /* TS 23.003 13.8 / RFC 7254, as AOSP SipUrnHelper.cpp builds it:
         * tac(8) "-" snr(6) "-" spare, and the spare is a literal 0. We
         * used to emit the IMEI's check digit as the last character. */
        check("12345678-901234-0".equals(
                        JoanSipBuilder.imeiInstance("123456789012345")),
                "instance-id spare digit is 0, not the check digit");
        check("12345678-901234-0".equals(
                        JoanSipBuilder.imeiInstance("12345678901234")),
                "a 14-digit IMEI gets the same spare digit");
        check("12345678-901234-0".equals(
                        JoanSipBuilder.imeiInstance("12-345678 901234/5")),
                "punctuation in the IMEI is ignored");
        check("00000000-000000-0".equals(JoanSipBuilder.imeiInstance("123")),
                "too few digits falls back, visibly");
        check("00000000-000000-0".equals(JoanSipBuilder.imeiInstance(null)),
                "a null IMEI does not throw");

        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "user@ims.example.net", "sip:+15555550100@ims.example.net",
                "ims.example.net", "2001:db8::2", 25000, 26000,
                "123456789012345");
        JoanSipBuilder.Params mine = new JoanSipBuilder.Params(
                1111, 2222, 25000, 26000);
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(mine,
                new java.security.SecureRandom());

        JoanSipBuilder.setRegisterContactTags(true);
        String withTags = JoanSipBuilder.buildRegister(id, txn, 1, null, null);
        check(withTags.contains("+sip.instance=\"<urn:gsma:imei:"
                        + "12345678-901234-0>\""),
                "REGISTER carries the AOSP-shaped instance-id");
        check(withTags.contains("+g.3gpp.icsi-ref="),
                "MMTEL tags present by default");

        JoanSipBuilder.setRegisterContactTags(false);
        String noTags = JoanSipBuilder.buildRegister(id, txn, 1, null, null);
        check(!noTags.contains("+g.3gpp.icsi-ref="),
                "MMTEL tags can be withheld, for CMCC");
        check(!noTags.contains(";audio"),
                "and the audio tag goes with them");
        check(noTags.contains("+sip.instance=\"<urn:gsma:imei:"),
                "but the instance-id always stays: it identifies the binding");

        /* RFC 3325 9.1: a UAC may include P-Preferred-Identity "in any
         * request other than REGISTER". AOSP's REGISTER builders never
         * reference the header; it belongs on INVITE and the reg-event
         * SUBSCRIBE. joan sent it in both REGISTERs until alpha29. */
        check(!withTags.contains("P-Preferred-Identity"),
                "REGISTER carries no P-Preferred-Identity (RFC 3325 9.1)");
        check(!noTags.contains("P-Preferred-Identity"),
                "and not in the no-tags form either");

        /* RFC 3261 20.41. Optional, but both reference stacks send one,
         * and ours must be truthful rather than an imitation of a vendor
         * string a carrier might profile on. */
        JoanSipBuilder.setUserAgentVersion(null);
        check(withTags.contains("User-Agent: joan-ims")
                        || JoanSipBuilder.buildRegister(id, txn, 1, null, null)
                                .contains("User-Agent: joan-ims"),
                "REGISTER carries a User-Agent");
        JoanSipBuilder.setUserAgentVersion("0.4.0-test");
        String uaReg = JoanSipBuilder.buildRegister(id, txn, 1, null, null);
        check(uaReg.contains("User-Agent: joan-ims/0.4.0-test"),
                "and it carries our version when we know it");
        /* Scoped to the header line: "algorithm=AKAv1-MD5" contains
         * "lg", which a whole-message search trips over. */
        String uaLine = "";
        for (String ln : uaReg.split("\r\n")) {
            if (ln.startsWith("User-Agent:")) {
                uaLine = ln;
                break;
            }
        }
        final String ua = uaLine.toLowerCase(java.util.Locale.ROOT);
        check(!ua.contains("lge") && !ua.contains("lg/")
                        && !ua.contains("t-mobile") && !ua.contains("volte-epdg"),
                "and never imitates a vendor or carrier");
        /* Carrier policy gates the header. 74 of 136 shipped profiles
         * send no User-Agent, China Mobile among them, so omitting it is
         * the ordinary case and not a special one. */
        JoanSipBuilder.setSendUserAgent(false);
        check(!JoanSipBuilder.buildRegister(id, txn, 1, null, null)
                        .contains("User-Agent"),
                "a carrier whose profile sends no User-Agent gets none");
        JoanSipBuilder.setSendUserAgent(true);
        check(JoanSipBuilder.buildRegister(id, txn, 1, null, null)
                        .contains("User-Agent: joan-ims"),
                "and one whose profile sends it gets ours");
        JoanSipBuilder.setUserAgentVersion(null);
        /* No dangling separator where the tags used to be: the Contact
         * must end at the instance-id's closing quote. */
        check(noTags.contains(">\"\r\n"),
                "Contact ends cleanly when the tags are withheld");
        check(!noTags.contains(";;") && !noTags.contains(";\r\n"),
                "no empty parameter left behind");
        JoanSipBuilder.setRegisterContactTags(true);

        /* headerShape: names, counts, no values. */
        String msg = "REGISTER sip:ims.example.net SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP [2001:db8::2]:5060;branch=z9hG4bKsecret\r\n"
                + "Via: SIP/2.0/TCP [2001:db8::3]:5060\r\n"
                + "From: <sip:+15555550100@ims.example.net>;tag=abc\r\n"
                + "Authorization: Digest response=\"deadbeef\"\r\n"
                + "Contact: <sip:a@b>\r\n"
                + " ;expires=600\r\n"
                + "\r\n";
        String shape = JoanSipBuilder.headerShape(msg);
        check(shape.contains("Viax2"), "repeated headers are counted");
        check(shape.contains("Authorization"), "header names are reported");
        check(!shape.contains("deadbeef") && !shape.contains("5555550100")
                        && !shape.contains("z9hG4bK"),
                "header VALUES never appear -- no identities, no credentials");
        check(!shape.contains("expires"),
                "a folded continuation line is not read as a header");
        check("none".equals(JoanSipBuilder.headerShape(null))
                        && "none".equals(JoanSipBuilder.headerShape("")),
                "headerShape survives an empty message");
    }

    /** REGISTER redirects: RFC 3261 s21.3.4, and never leak the user part. */
    private static void testRegisterRedirect() {
        String r305 = "SIP/2.0 305 Use Proxy\r\n"
                + "Via: SIP/2.0/UDP [2001:db8::2]:5060\r\n"
                + "To: <sip:+8613800000000@ims.mnc002.mcc460.3gppnetwork.org>\r\n"
                + "Contact: <sip:[2001:db8:abcd::9]:5060;lr>\r\n"
                + "\r\n";
        check("2001:db8:abcd::9".equals(JoanSipBuilder.redirectHost(r305)),
                "305 Use Proxy: IPv6 proxy host is read, brackets stripped");

        check("10.1.2.3".equals(JoanSipBuilder.redirectHost(
                        "SIP/2.0 302 Moved\r\nContact: <sip:10.1.2.3:5060>\r\n\r\n")),
                "302: IPv4 host with a port");
        check("pcscf.example.net".equals(JoanSipBuilder.redirectHost(
                        "SIP/2.0 301 Moved\r\nContact: <sip:pcscf.example.net>\r\n\r\n")),
                "301: an FQDN is read too");
        check("proxy.example.net".equals(JoanSipBuilder.redirectHost(
                        "SIP/2.0 305 Use Proxy\r\nm: <sips:proxy.example.net:5061>\r\n\r\n")),
                "compact form 'm:' and sips: are both accepted");

        /* The whole point of returning host-only. A Contact in a redirect
         * can carry a subscriber identity; it must never reach a trace. */
        String withUser = "SIP/2.0 305 Use Proxy\r\n"
                + "Contact: <sip:+8613800000000@2001:db8::9>\r\n\r\n";
        String got = JoanSipBuilder.redirectHost(withUser);
        check(got != null && got.indexOf("8613800000000") < 0
                        && got.indexOf('@') < 0,
                "the user part of a redirect Contact is never returned");

        check(JoanSipBuilder.redirectHost(
                        "SIP/2.0 404 Not Found\r\nWarning: 399 x \"y\"\r\n\r\n") == null,
                "a reply with no Contact yields no host");
        check(JoanSipBuilder.redirectHost(null) == null
                        && JoanSipBuilder.redirectHost("") == null,
                "redirectHost survives a null or empty reply");
        check(JoanSipBuilder.redirectHost(
                        "SIP/2.0 305 Use Proxy\r\nContact: *\r\n\r\n") == null,
                "a Contact with no URI yields no host");
    }

    /** The carrier's transport criterion, and the PLMN scoping of it. */
    private static void testCarrierTcpCriterion() {
        String cmcc2 = "ims.mnc002.mcc460.3gppnetwork.org";
        String cmcc0 = "ims.mnc000.mcc460.3gppnetwork.org";
        String tmo   = "ims.mnc260.mcc310.3gppnetwork.org";
        String other = "ims.mnc001.mcc234.3gppnetwork.org";

        /* The snapshot's criterion is now DIAGNOSTIC ONLY. Routing moved
         * to registerTcpCriterion(), which AOSP computes from the MTU and
         * which consults nothing provisioned. These checks keep pinning
         * what LG actually shipped and that its PLMN scoping is right,
         * because that is what a trace from a failing network has to be
         * read against -- they just no longer describe what joan does. */
        JoanSipBuilder.setCarrierTcpCriterion(-1, -1, -1);
        check(JoanSipBuilder.tcpCriterionFor(cmcc2, true) == 1300,
                "snapshot: CMCC 46002 reads China Mobile's 1300, not 4096");
        check(JoanSipBuilder.tcpCriterionFor(cmcc0, true) == 1300,
                "snapshot: CMCC 46000 still does");
        check(JoanSipBuilder.tcpCriterionFor(other, false) == 4096,
                "snapshot: an unknown carrier reads the 4096 GLOBAL default");
        JoanSipBuilder.setCarrierTransport(460, 2, 1300, 900, 1080);
        check(JoanSipBuilder.tcpCriterionFor(cmcc2, true) == 1080
                        && JoanSipBuilder.tcpCriterionFor(cmcc2, false) == 900,
                "snapshot: a per-family value wins over the common one");
        check(JoanSipBuilder.tcpCriterionFor(other, false) == 4096,
                "snapshot: a pushed profile never leaks onto another PLMN");
        JoanSipBuilder.setCarrierTransport(-1, -1, -1, 0, 0);

        /* And the point of the change: a pushed profile no longer moves
         * the transport decision at all. 900 would have flipped a
         * 1000-byte message; the computed criterion is 1300, so it does
         * not. */
        JoanSipBuilder.setCarrierTransport(460, 2, 900, 900, 900);
        check(!JoanSipBuilder.preferTcp(cmcc2, 1000, 0, true),
                "a snapshot criterion no longer routes");
        JoanSipBuilder.setCarrierTransport(-1, -1, -1, 0, 0);

        /* The T-Mobile exception is the one piece of joan policy that
         * still outranks the computation, because it is bench-proven. */
        check(!JoanSipBuilder.preferTcp(tmo, 4000, 1500, false)
                        && !JoanSipBuilder.preferTcp(tmo, 9216, 1280, true),
                "T-Mobile never flips to TCP, whatever the MTU says");

        /* The platform's own transport policy is tier 1 and decides
         * before any length is considered. */
        JoanSipBuilder.setPlatformPreferredTransport(
                JoanSipBuilder.TRANSPORT_UDP);
        check(!JoanSipBuilder.preferTcp(cmcc2, 9000, 1500, false),
                "the platform asking for UDP keeps a huge message on UDP");
        JoanSipBuilder.setPlatformPreferredTransport(
                JoanSipBuilder.TRANSPORT_TCP);
        check(JoanSipBuilder.preferTcp(cmcc2, 10, 1500, false),
                "the platform asking for TCP flips even a tiny message");
        JoanSipBuilder.setPlatformPreferredTransport(
                JoanSipBuilder.TRANSPORT_TLS);
        check(!JoanSipBuilder.preferTcp(cmcc2, 10, 1500, false),
                "TLS falls through to the criterion; joan has no TLS to claim");
        JoanSipBuilder.setPlatformPreferredTransport(
                JoanSipBuilder.TRANSPORT_DYNAMIC_UDP_TCP);
        check(JoanSipBuilder.preferTcp(cmcc2, 1400, 1500, false)
                        && !JoanSipBuilder.preferTcp(cmcc2, 1000, 1500, false),
                "DYNAMIC_UDP_TCP is the value that consults the criterion");
        JoanSipBuilder.setPlatformPreferredTransport(-1);

        /* AOSP's own arithmetic: AosRegistration::SetTcpCriterionLength. */
        check(JoanSipBuilder.registerTcpCriterion(1500, false) == 1300,
                "a 1500 MTU gives 1300 (1500 - 200 threshold)");
        check(JoanSipBuilder.registerTcpCriterion(9000, false) == 1300,
                "an MTU above max_allowed_network_mtu is clamped to 1500");
        check(JoanSipBuilder.registerTcpCriterion(1280, true) == 1080,
                "a 1280 MTU gives 1080, the IPv6 value 108 profiles carry");
        check(JoanSipBuilder.registerTcpCriterion(0, false) == 1300,
                "no MTU and no platform SIP MTU falls back to 1500 - 200");
        check(JoanSipBuilder.registerTcpCriterion(100, false) == 1300,
                "an MTU below the threshold is unusable, not a criterion of -100");
        JoanSipBuilder.setPlatformSipMtu(1400, 1200);
        check(JoanSipBuilder.registerTcpCriterion(0, false) == 1400
                        && JoanSipBuilder.registerTcpCriterion(0, true) == 1200,
                "an unusable link MTU falls back to the platform SIP MTU key, "
                + "taken as the criterion directly (AOSP's asymmetry)");
        JoanSipBuilder.setPlatformSipMtu(0, 0);

        /* A branded IMS domain still belongs to a carrier. T-Mobile's
         * ISIM hands out "msg.pc.t-mobile.com", which carries no MCC or
         * MNC, so the realm alone said "not a carrier" and every
         * PLMN-scoped transport decision was skipped on the one network
         * this project can test. Measured on the handset as plmn_ok=0.
         * The SIM's own PLMN, which the driver already pushes, is the
         * authority. */
        JoanSipBuilder.setCarrierTransport(310, 260, 1300, 0, 0);
        check(!JoanSipBuilder.preferTcp("msg.pc.t-mobile.com", 9000, 1500,
                        false),
                "a branded realm resolves through the SIM PLMN, so the "
                + "T-Mobile exception finally applies to T-Mobile");
        JoanSipBuilder.setCarrierTransport(234, 1, 1300, 0, 0);
        check(JoanSipBuilder.preferTcp("ims.example-carrier.net", 1400, 1500,
                        false),
                "and another carrier's branded realm gets the criterion "
                + "instead of being skipped entirely");
        JoanSipBuilder.setCarrierTransport(-1, -1, -1, 0, 0);
        check(!JoanSipBuilder.preferTcp("ims.example.net", 9000, 1500, false),
                "with no SIM PLMN either, a non-carrier realm still never "
                + "flips transport");

        /* REGISTER Expires. Both carriers joan can test want 600000, so
         * this must not change them; 43 of 136 profiles want otherwise. */
        JoanSipBuilder.Id id2 = new JoanSipBuilder.Id(
                "user@ims.example.net", "sip:+15555550100@ims.example.net",
                "ims.example.net", "2001:db8::2", 25000, 26000,
                "123456789012345");
        JoanSipBuilder.Params mine2 = new JoanSipBuilder.Params(
                1111, 2222, 25000, 26000);
        JoanSipBuilder.Txn txn2 = new JoanSipBuilder.Txn(mine2,
                new java.security.SecureRandom());
        JoanSipBuilder.setCarrierRegisterExpires(0);
        check(JoanSipBuilder.buildRegister(id2, txn2, 1, null, null)
                        .contains("Expires: 600000"),
                "no carrier value keeps joan's 600000");
        JoanSipBuilder.setCarrierRegisterExpires(3600);
        check(JoanSipBuilder.buildRegister(id2, txn2, 1, null, null)
                        .contains("Expires: 3600"),
                "a carrier asking for 3600 gets 3600");
        JoanSipBuilder.setCarrierRegisterExpires(0);

        /* Platform SIP MTU outranks the link MTU: it is an updatable
         * carrier-config key, the link MTU is whatever the bearer gave. */
        JoanSipBuilder.setPlatformSipMtu(0, 0);
        check(JoanSipBuilder.effectiveMtu(1500, true) == 1500,
                "with no platform MTU the link MTU is used");
        JoanSipBuilder.setPlatformSipMtu(1400, 1200);
        check(JoanSipBuilder.effectiveMtu(1500, true) == 1200,
                "the platform IPv6 SIP MTU wins over the link MTU");
        check(JoanSipBuilder.effectiveMtu(1500, false) == 1400,
                "and the IPv4 one is used for IPv4");
        JoanSipBuilder.setPlatformSipMtu(0, 0);
        check(JoanSipBuilder.effectiveMtu(0, true) == 0,
                "neither set means no MTU to reason about");
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

        /* RFC 3325 7 / TS 24.229 5.1.2A: the caller withheld their
         * number, and this network did not strip the assertion on the way
         * in. Showing it puts a withheld number on the callee's screen,
         * and nothing on the handset would ever reveal that we had. */
        String withheldButAsserted = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"Anonymous\" <sip:anonymous@anonymous.invalid>;tag=a\r\n"
                + "P-Asserted-Identity: \"Real Name\" <tel:+15555550123>\r\n"
                + "Privacy: id\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(withheldButAsserted);
        check(c.withheld && c.uri.isEmpty() && c.name.isEmpty(),
                "Privacy: id withholds an asserted identity the network left in");

        String privHeader = "INVITE sip:me SIP/2.0\r\n"
                + "From: <sip:x@y>;tag=a\r\n"
                + "P-Asserted-Identity: <tel:+15555550123>\r\n"
                + "Privacy: header;critical\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(privHeader);
        check(c.withheld,
                "Privacy: header withholds too, and critical is not a level");

        /* "user" is display-name privacy (RFC 3323 4.2), not identity
         * privacy. Withholding the number for it would break callbacks on
         * every network that sets it as a matter of course. */
        String privUser = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"Bob\" <tel:+15555550123>;tag=a\r\n"
                + "P-Asserted-Identity: \"Bob\" <tel:+15555550123>\r\n"
                + "Privacy: user\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(privUser);
        check(!c.withheld && "tel:+15555550123".equals(c.uri)
                        && c.name.isEmpty(),
                "Privacy: user drops the name and keeps the number");

        String privNone = "INVITE sip:me SIP/2.0\r\n"
                + "From: <tel:+15555550123>;tag=a\r\n"
                + "Privacy: none\r\n\r\n";
        check(!JoanSipBuilder.callingIdentity(privNone).withheld,
                "Privacy: none is not privacy");

        /* RFC 3325 9 allows a sip: and a tel: as two header FIELDS, not
         * only as one comma-joined field. Reading the first field alone
         * handed the dialer "alice" and no dialable number. */
        String twoPaiFields = "INVITE sip:me SIP/2.0\r\n"
                + "From: <sip:alice@example.com>;tag=a\r\n"
                + "P-Asserted-Identity: <sip:alice@example.com>\r\n"
                + "P-Asserted-Identity: <tel:+15555550123>\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(twoPaiFields);
        check("tel:+15555550123".equals(c.uri),
                "a tel: in the second P-Asserted-Identity field is found");

        /* The name belongs to the whole asserted field, not to the slice
         * taken from "<tel:". */
        String namedTel = "INVITE sip:me SIP/2.0\r\n"
                + "From: <sip:x@y>;tag=a\r\n"
                + "P-Asserted-Identity: \"Real Name\" <tel:+15555550123>\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(namedTel);
        check("Real Name".equals(c.name) && "tel:+15555550123".equals(c.uri),
                "an asserted display name survives picking the tel: form");

        /* "anonymous" was matched as a substring of the URI and of the
         * display name, so a real caller could be blanked by their name. */
        String anonish = "INVITE sip:me SIP/2.0\r\n"
                + "From: \"Anonymous Vodka Ltd\" <tel:+15555559999>;tag=a\r\n"
                + "P-Asserted-Identity: <tel:+15555559999>\r\n\r\n";
        c = JoanSipBuilder.callingIdentity(anonish);
        check(!c.withheld && "Anonymous Vodka Ltd".equals(c.name),
                "a caller named Anonymous-something is not withheld");
        check(JoanSipBuilder.isAnonymousUri("sip:anonymous@anonymous.invalid")
                        && JoanSipBuilder.isAnonymousUri("tel:anonymous")
                        && JoanSipBuilder.isAnonymousUri(""),
                "the anonymous placeholder is recognised by structure");
        check(!JoanSipBuilder.isAnonymousUri("sip:anonymous.jones@example.com")
                        && !JoanSipBuilder.isAnonymousUri("tel:+15555550100"),
                "a name that merely contains 'anonymous' is not the placeholder");

        check(JoanSipBuilder.privacyLevel("INVITE sip:me SIP/2.0\r\n\r\n")
                        == JoanSipBuilder.PRIV_NONE,
                "no Privacy header is not a privacy request");
    }

    /**
     * Outgoing caller-ID restriction, TS 24.229 5.1.3.1: Privacy: id and
     * an untouched P-Preferred-Identity, because anonymising the asserted
     * identity is the S-CSCF's job inside the trust domain.
     */
    private static void testOutgoingOir() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "310260123456789@ims.mnc260.mcc310.3gppnetwork.org",
                "sip:+15550000@ims.mnc260.mcc310.3gppnetwork.org",
                "ims.mnc260.mcc310.3gppnetwork.org",
                "2001:db8::1", 5060, 5060, null);

        JoanSipBuilder.Dialog plain = new JoanSipBuilder.Dialog();
        String ordinary = JoanSipBuilder.buildInvite(id, plain,
                "sip:peer@host", "", null, 40000, null);
        check(ordinary.indexOf("Privacy:") < 0,
                "an ordinary call sends no Privacy header at all");

        JoanSipBuilder.Dialog hidden = new JoanSipBuilder.Dialog();
        hidden.privacyId = true;
        String restricted = JoanSipBuilder.buildInvite(id, hidden,
                "sip:peer@host", "", null, 40000, null);
        check(restricted.indexOf("\r\nPrivacy: id\r\n") > 0,
                "a restricted call sends Privacy: id");
        check(restricted.indexOf("P-Preferred-Identity: <sip:+15550000@") > 0,
                "and keeps P-Preferred-Identity for the network to assert on");
        check(restricted.indexOf("anonymous") < 0,
                "the UE does not anonymise its own From, which is a dialog id");
        /* The whole point: never emit "none". A subscriber with permanent
         * OIR provisioned would be unmasked by a UE that volunteered it. */
        check(ordinary.indexOf("Privacy: none") < 0
                        && restricted.indexOf("Privacy: none") < 0,
                "Privacy: none is never volunteered");
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
         * it must not appear. Allow lists only what we answer.
         *
         * INFO left the list for the same reason it should never have
         * been on it: there is no handler, so an INFO from the core was
         * answered 501 by a UA that had just advertised support for it.
         * DTMF goes as RFC 4733 telephone-events, not as
         * application/dtmf-relay, so nothing here needs INFO. */
        for (String m2 : new String[] { "MESSAGE", "INFO" }) {
            check(!reg.contains(m2) && !inv.contains(m2),
                    "neither request allows " + m2 + " (no handler)");
        }
        /* UPDATE joined the list because we answer it: a network
         * refreshing the session (RFC 4028) picks a method the peer
         * allows, and an unanswered refresh tears the call down. PRACK
         * stays because an inbound one is now answered 200. */
        for (String m2 : new String[] { "REFER", "SUBSCRIBE", "NOTIFY",
                "PRACK", "UPDATE" }) {
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
        check(inv.contains("m=audio 40000 RTP/AVP 96 97 0 100 101\r\n"),
                "offer lists AMR-WB, AMR-NB, PCMU, then the event types");
        check(inv.contains("a=rtpmap:96 AMR-WB/16000/1"),
                "offer names AMR-WB at the dynamic payload type");
        check(inv.contains("a=rtpmap:97 AMR/8000/1"),
                "offer names AMR-NB at the dynamic payload type");
        check(inv.contains("a=rtpmap:0 PCMU/8000"),
                "offer keeps PCMU as fallback");

        /* No split: one profile drives both directions. Everything the
         * offer promises must also be accepted when a peer offers it back,
         * which is what stopped being true when the answer was hardcoded. */
        /* The answer takes THEIR event payload type at the clock rate of
         * the codec chosen, never our own number and never a rate that
         * does not match the stream. */
        String teHead = "v=0\r\no=- 1 1 IN IP6 2001:db8::9\r\ns=-\r\n"
                + "c=IN IP6 2001:db8::9\r\nt=0 0\r\n";
        String teOffer = teHead
                + "m=audio 40000 RTP/AVP 104 0 96 97\r\n"
                + "a=rtpmap:104 AMR-WB/16000/1\r\n"
                + "a=fmtp:104 octet-align=1\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=rtpmap:96 telephone-event/8000\r\n"
                + "a=rtpmap:97 telephone-event/16000\r\n";
        JoanSipBuilder.Media teM = JoanSipBuilder.parseSdp(teOffer);
        JoanSipBuilder.Codec teChosen = JoanSipBuilder.selectAnswerCodec(teM);
        check(teChosen != null && teChosen.pt == 104,
                "telephone-event is never chosen as the audio codec");
        JoanSipBuilder.Codec te = JoanSipBuilder.telephoneEventFor(teM, teChosen);
        check(te != null && te.pt == 97,
                "the event type matching the codec's clock rate is chosen");
        String teAns = JoanSipBuilder.sdpAnswer("2001:db8::2", 40000, teOffer);
        check(teAns.contains("m=audio 40000 RTP/AVP 104 97\r\n")
                        && teAns.contains("a=rtpmap:97 telephone-event/16000"),
                "the answer carries their event type beside the codec");
        String noTe = teHead + "m=audio 40000 RTP/AVP 0\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n";
        check(JoanSipBuilder.telephoneEventFor(
                        JoanSipBuilder.parseSdp(noTe),
                        JoanSipBuilder.selectAnswerCodec(
                                JoanSipBuilder.parseSdp(noTe))) == null,
                "an offer with no telephone-event yields none");

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
        /* telephone-event now has a sender behind it, so the offer may
         * promise it -- one per clock rate, because the event duration
         * counts ticks of the stream carrying it. */
        check(inv.contains("a=rtpmap:100 telephone-event/16000")
                        && inv.contains("a=rtpmap:101 telephone-event/8000"),
                "offer carries telephone-event at both clock rates");
        check(inv.contains("a=fmtp:100 0-15") && inv.contains("a=fmtp:101 0-15"),
                "offer accepts the whole DTMF event range");

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

        /* DTMF as RTP events (RFC 4733). A tone cannot ride inside a
         * speech codec: AMR reproduces voice, and a tone through it
         * arrives as something no IVR will recognise. */
        check(JoanDtmf.event('7') == 7 && JoanDtmf.event('0') == 0,
                "digits map to their own event numbers");
        check(JoanDtmf.event('*') == 10 && JoanDtmf.event('#') == 11,
                "star is 10 and hash is 11");
        check(JoanDtmf.event('A') == 12 && JoanDtmf.event('d') == 15,
                "A-D are 12-15, either case");
        check(JoanDtmf.event('G') < 0 && JoanDtmf.event('+') < 0,
                "a non-digit is not invented into an event");
        byte[] ev = new byte[4];
        check(JoanDtmf.pack(11, false, 10, 320, ev) == 4,
                "an event payload is four bytes");
        check(ev[0] == 11 && (ev[1] & 0x80) == 0 && (ev[1] & 0x3f) == 10,
                "event and volume sit in the first two bytes, E clear");
        check((ev[2] & 0xff) == 1 && (ev[3] & 0xff) == 64,
                "duration is 16 bits, network order");
        check(JoanDtmf.pack(11, true, 10, 320, ev) == 4 && (ev[1] & 0x80) != 0,
                "the end packet sets the E bit");
        check(JoanDtmf.eventOf(ev, 0, 4) == 11 && JoanDtmf.isEnd(ev, 0, 4),
                "an event payload reads back");
        check(JoanDtmf.pack(16, false, 10, 0, ev) < 0
                        && JoanDtmf.pack(3, false, 64, 0, ev) < 0
                        && JoanDtmf.pack(3, false, 10, 0x10000, ev) < 0,
                "out-of-range event, volume or duration is refused");
        check(JoanDtmf.pack(3, false, 10, 0, new byte[3]) < 0,
                "a short buffer is refused, not overrun");

        /* RTCP receiver reports: what the far end says it is getting.
         * An RR is header(8) + one 24-byte report block; per RFC 3550
         * 6.4.1: SSRC(4) fraction(1) cumulative(3) extended-max-seq(4)
         * JITTER(4) lsr(4) dlsr(4). The jitter word is block+12 -- this
         * test once placed it at block+16 (LSR) and the parser agreed,
         * both misreading the field: the bench logged a real LSR of
         * 452198400 as "jitter" on a lossless call. LSR gets a distinct
         * value so reading the wrong word can never pass again. */
        byte[] rr = new byte[32];
        rr[0] = (byte) 0x81;              // V=2, RC=1
        rr[1] = (byte) 201;               // RR
        rr[2] = 0; rr[3] = 7;             // length in words - 1
        rr[8] = 0; rr[9] = 0; rr[10] = 0; rr[11] = 9;   // reported SSRC
        rr[12] = (byte) 64;               // fraction lost = 64/256 = 25%
        rr[13] = 0; rr[14] = 1; rr[15] = 44;            // cumulative = 300
        rr[20] = 0; rr[21] = 0; rr[22] = 2; rr[23] = 88; // jitter = 600
        rr[24] = 0x1a; rr[25] = (byte) 0xfa; rr[26] = 0; rr[27] = 0; // LSR
        JoanRtcp.Report rep = JoanRtcp.parse(rr, rr.length);
        check(rep != null && rep.lossPercent() == 25,
                "fraction lost is read as a percentage");
        check(rep != null && rep.cumulativeLost == 300,
                "cumulative loss spans three bytes");
        check(rep != null && rep.jitter == 600, "interarrival jitter is read");
        check(rep != null && rep.jitter != 0x1afa0000,
                "the LSR word is never mistaken for jitter");
        /* RFC 3550 6.4.1: cumulative loss is SIGNED 24-bit, and duplicates
         * legitimately drive it negative. Read unsigned, a peer's -1 came
         * back as 16777215 -- sixteen million lost on a healthy call. Our
         * own sender has always written this field signed. */
        byte[] dup = rr.clone();
        dup[13] = (byte) 0xff; dup[14] = (byte) 0xff; dup[15] = (byte) 0xff;
        JoanRtcp.Report neg = JoanRtcp.parse(dup, dup.length);
        check(neg != null && neg.cumulativeLost == -1,
                "a negative cumulative loss reads as negative, not 16777215");
        dup[13] = (byte) 0x80; dup[14] = 0; dup[15] = 0;
        JoanRtcp.Report most = JoanRtcp.parse(dup, dup.length);
        check(most != null && most.cumulativeLost == -8388608,
                "the most negative cumulative loss sign-extends");
        dup[13] = 0x7f; dup[14] = (byte) 0xff; dup[15] = (byte) 0xff;
        JoanRtcp.Report pos = JoanRtcp.parse(dup, dup.length);
        check(pos != null && pos.cumulativeLost == 8388607,
                "the largest positive cumulative loss is untouched");
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

        /* SID detection decides when the jitter buffer may shorten.
         * Reading the wrong framing misclassifies speech as silence and
         * would shrink the buffer while somebody is talking. */
        byte[] octSid = { (byte) 0xf0, (byte) (JoanAmr.FT_SID << 3), 0, 0, 0, 0, 0 };
        check(JoanAmr.isSid(octSid, 0, octSid.length, true, true),
                "octet-aligned SID is recognised");
        byte[] octSpeech = { (byte) 0xf0, (byte) (2 << 3), 0, 0, 0, 0, 0 };
        check(!JoanAmr.isSid(octSpeech, 0, octSpeech.length, true, true),
                "octet-aligned speech is not mistaken for SID");
        /* Bandwidth-efficient: FT straddles the octet boundary. */
        byte[] beSid = { (byte) 0xf4, (byte) 0x00, 0, 0, 0, 0 };
        check(JoanAmr.isSid(beSid, 0, beSid.length, false, true),
                "bandwidth-efficient SID is recognised");
        check(!JoanAmr.isSid(beSid, 0, beSid.length, true, true),
                "and reading it with the wrong framing does not say SID");
        check(!JoanAmr.isSid(null, 0, 4, true, true)
                        && !JoanAmr.isSid(new byte[]{ 1 }, 0, 1, true, true)
                        && !JoanAmr.isSid(new byte[]{ 1, 2 }, 0, 9, true, true),
                "an unparseable payload never reads as SID");
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

    private static void testSdpDirection() {
        String head = "v=0\r\no=- 1 1 IN IP6 2001:db8::9\r\ns=-\r\n"
                + "c=IN IP6 2001:db8::9\r\nt=0 0\r\n"
                + "m=audio 40000 RTP/AVP 96\r\n"
                + "a=rtpmap:96 AMR-WB/16000/1\r\n"
                + "a=fmtp:96 octet-align=1\r\n";

        /* RFC 3264 s6.1: an offer with no direction attribute is sendrecv,
         * not unknown. */
        check(JoanSipBuilder.DIR_SENDRECV.equals(
                        JoanSipBuilder.parseSdp(head).direction),
                "an offer with no direction attribute is sendrecv");
        check(JoanSipBuilder.DIR_SENDONLY.equals(
                        JoanSipBuilder.parseSdp(head + "a=sendonly\r\n").direction),
                "a=sendonly is read");
        check(JoanSipBuilder.DIR_INACTIVE.equals(
                        JoanSipBuilder.parseSdp(head + "a=inactive\r\n").direction),
                "a=inactive is read");
        check(JoanSipBuilder.DIR_RECVONLY.equals(
                        JoanSipBuilder.parseSdp(head + "a=recvonly\r\n").direction),
                "a=recvonly is read");

        /* Mirror, not echo. */
        check(JoanSipBuilder.DIR_RECVONLY.equals(
                        JoanSipBuilder.mirrorDirection(JoanSipBuilder.DIR_SENDONLY)),
                "sendonly is answered recvonly, not sendonly");
        check(JoanSipBuilder.DIR_SENDONLY.equals(
                        JoanSipBuilder.mirrorDirection(JoanSipBuilder.DIR_RECVONLY)),
                "recvonly is answered sendonly");
        check(JoanSipBuilder.DIR_INACTIVE.equals(
                        JoanSipBuilder.mirrorDirection(JoanSipBuilder.DIR_INACTIVE)),
                "inactive is answered inactive");
        check(JoanSipBuilder.DIR_SENDRECV.equals(
                        JoanSipBuilder.mirrorDirection(JoanSipBuilder.DIR_SENDRECV))
                        && JoanSipBuilder.DIR_SENDRECV.equals(
                                JoanSipBuilder.mirrorDirection(null)),
                "sendrecv and an unknown direction answer sendrecv");

        check(JoanSipBuilder.isHeldByPeer(JoanSipBuilder.DIR_SENDONLY)
                        && JoanSipBuilder.isHeldByPeer(JoanSipBuilder.DIR_INACTIVE),
                "sendonly and inactive both mean the peer holds us");
        check(!JoanSipBuilder.isHeldByPeer(JoanSipBuilder.DIR_SENDRECV)
                        && !JoanSipBuilder.isHeldByPeer(JoanSipBuilder.DIR_RECVONLY),
                "sendrecv and recvonly are not a hold");
        check(JoanSipBuilder.sendsRtp(JoanSipBuilder.DIR_SENDRECV)
                        && JoanSipBuilder.sendsRtp(JoanSipBuilder.DIR_SENDONLY)
                        && !JoanSipBuilder.sendsRtp(JoanSipBuilder.DIR_RECVONLY)
                        && !JoanSipBuilder.sendsRtp(JoanSipBuilder.DIR_INACTIVE),
                "we send RTP only when our own direction says we do");

        /* And the answer actually carries it. */
        JoanSipBuilder.Media hold = JoanSipBuilder.parseSdp(
                head + "a=sendonly\r\n");
        String ans = JoanSipBuilder.sdpAnswer("2001:db8::1", 40000, hold,
                JoanSipBuilder.selectAnswerCodec(hold));
        check(ans.indexOf("a=recvonly\r\n") > 0
                        && ans.indexOf("a=sendrecv") < 0,
                "the answer to a hold offer is recvonly, not sendrecv");
        JoanSipBuilder.Media norm = JoanSipBuilder.parseSdp(head);
        String ans2 = JoanSipBuilder.sdpAnswer("2001:db8::1", 40000, norm,
                JoanSipBuilder.selectAnswerCodec(norm));
        check(ans2.indexOf("a=sendrecv\r\n") > 0,
                "an ordinary offer is still answered sendrecv");
    }

    private static void testSessionBandwidth() {
        JoanSipBuilder.setSessionBandwidth(0, 0, 0);
        check("".equals(JoanSipBuilder.bandwidthLines()),
                "nothing configured emits no b= lines at all");

        JoanSipBuilder.setSessionBandwidth(41, 600, 2000);
        String b = JoanSipBuilder.bandwidthLines();
        check("b=AS:41\r\nb=RS:600\r\nb=RR:2000\r\n".equals(b),
                "the three RFC 3556 lines are emitted in order");

        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "310260123456789@ims.mnc260.mcc310.3gppnetwork.org",
                "sip:+15550000@ims.mnc260.mcc310.3gppnetwork.org",
                "ims.mnc260.mcc310.3gppnetwork.org",
                "2001:db8::1", 5060, 5060, null);
        String inv = JoanSipBuilder.buildInvite(id,
                new JoanSipBuilder.Dialog(), "sip:peer@host", "", null,
                40000, null);
        int cPos = inv.indexOf("c=IN IP6");
        int bPos = inv.indexOf("b=AS:41");
        int mPos = inv.indexOf("m=audio");
        int aPos = inv.indexOf("a=rtpmap");
        check(bPos > 0, "the offer carries b=AS");
        /* RFC 4566 fixes the order: b= after c=/m=, before any a=.
         * Cores do reject an SDP that puts them elsewhere. */
        check(cPos < bPos && mPos < bPos && bPos < aPos,
                "b= lines sit after m= and before the a= lines");

        String head = "v=0\r\no=- 1 1 IN IP6 2001:db8::9\r\ns=-\r\n"
                + "c=IN IP6 2001:db8::9\r\nt=0 0\r\n"
                + "m=audio 40000 RTP/AVP 96\r\n"
                + "a=rtpmap:96 AMR-WB/16000/1\r\n"
                + "a=fmtp:96 octet-align=1\r\n";
        JoanSipBuilder.Media o = JoanSipBuilder.parseSdp(head);
        String ans = JoanSipBuilder.sdpAnswer("2001:db8::1", 40000, o,
                JoanSipBuilder.selectAnswerCodec(o));
        check(ans.indexOf("b=AS:41\r\n") > 0
                        && ans.indexOf("m=audio") < ans.indexOf("b=AS:41")
                        && ans.indexOf("b=AS:41") < ans.indexOf("a=rtpmap"),
                "the answer carries them too, in the same position");

        /* A carrier that configures only some of them gets only those. */
        JoanSipBuilder.setSessionBandwidth(41, 0, 0);
        check("b=AS:41\r\n".equals(JoanSipBuilder.bandwidthLines()),
                "an unset RS/RR is left off rather than sent as zero");

        JoanSipBuilder.setSessionBandwidth(0, 0, 0);
    }

    private static void testJitterBuffer() {
        byte[] p = { 1, 2, 3, 4 };

        /* In order, no loss: everything comes back in order once the
         * buffer has filled to its depth. */
        JoanJitter j = new JoanJitter();
        j.setClockRate(16000);
        int delivered = 0;
        for (int i = 0; i < 40; i++) {
            j.offer(1000 + i, i * 320L, i * 320L, p, i * 20L);
            if (j.poll(i * 20L) != null) {
                delivered++;
            }
        }
        check(delivered > 30 && delivered <= 40,
                "an in-order stream is delivered, less the initial fill");
        check(j.reordered() == 0 && j.dropped() == 0,
                "an in-order stream reorders and drops nothing");
        check(j.cumulativeLost() == 0, "and loses nothing");

        /* Out of order: the whole point. Straight-through playback would
         * emit these in arrival order; the buffer must not. */
        JoanJitter r = new JoanJitter();
        r.setClockRate(16000);
        byte[] a = { 10 };
        byte[] b = { 11 };
        byte[] c = { 12 };
        for (int i = 0; i < 6; i++) {
            r.offer(500 + i, i * 320L, i * 320L, new byte[]{ (byte) i },
                    i * 20L);
        }
        /* 8 and 7 arrive swapped. */
        r.offer(508, 8 * 320L, 8 * 320L, c, 160L);
        r.offer(507, 7 * 320L, 7 * 320L, b, 170L);
        r.offer(506, 6 * 320L, 6 * 320L, a, 175L);
        check(r.reordered() > 0, "an out-of-order arrival is noticed");
        java.util.List<Byte> got = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            byte[] out = r.poll(200L + i * 20L);
            if (out != null) {
                got.add(out[0]);
            }
        }
        boolean ordered = true;
        for (int i = 1; i < got.size(); i++) {
            if (got.get(i) < got.get(i - 1)) {
                ordered = false;
            }
        }
        check(ordered, "packets come out in sequence order, not arrival order");

        /* A packet so late that its slot has already played must be
         * dropped, not emitted out of order. */
        JoanJitter late = new JoanJitter();
        late.setClockRate(16000);
        for (int i = 0; i < 20; i++) {
            late.offer(200 + i, i * 320L, i * 320L, p, i * 20L);
            late.poll(i * 20L);
        }
        int droppedBefore = late.dropped();
        check(!late.offer(200, 0, 0, p, 400L),
                "a packet whose slot already played is refused");
        check(late.dropped() == droppedBefore + 1,
                "and counted as late rather than silently ignored");

        /* Loss shows up in the RFC 3550 statistics. */
        JoanJitter loss = new JoanJitter();
        loss.setClockRate(16000);
        for (int i = 0; i < 10; i++) {
            if (i == 4 || i == 5) {
                continue; /* two packets never arrive */
            }
            loss.offer(700 + i, i * 320L, i * 320L, p, i * 20L);
        }
        check(loss.cumulativeLost() == 2,
                "two missing packets are counted as two lost");
        check(loss.received() == 8, "and the received count excludes them");
        int frac = loss.fractionLostAndReset();
        check(frac > 0, "the report-block fraction is non-zero after loss");
        check(loss.fractionLostAndReset() == 0,
                "and resets, so each report covers its own interval");

        /* Jitter: a steady stream has none, a varying one does. */
        JoanJitter steady = new JoanJitter();
        steady.setClockRate(16000);
        for (int i = 0; i < 30; i++) {
            steady.offer(1 + i, i * 320L, i * 320L, p, i * 20L);
        }
        check(steady.jitter() == 0, "a perfectly paced stream has no jitter");
        JoanJitter jumpy = new JoanJitter();
        jumpy.setClockRate(16000);
        for (int i = 0; i < 30; i++) {
            long arrive = i * 320L + ((i % 2 == 0) ? 0 : 480L);
            jumpy.offer(1 + i, i * 320L, arrive, p, i * 20L);
        }
        check(jumpy.jitter() > 0, "an uneven stream shows jitter");

        /* Depth adapts, and is bounded at both ends. */
        JoanJitter adapt = new JoanJitter();
        adapt.setClockRate(16000);
        int start = adapt.depth();
        for (int i = 0; i < 60; i++) {
            long arrive = i * 320L + (i % 3 == 0 ? 1600L : 0L);
            adapt.offer(1 + i, i * 320L, arrive, p, i * 20L);
        }
        check(adapt.depth() >= start,
                "a stream that keeps arriving late grows the buffer");
        check(adapt.depth() <= JoanJitter.MAX_DEPTH,
                "and never past the ceiling");

        /* The sizing follows AOSP's JitterNetworkAnalyser, and these are
         * the three things the first port got wrong. 16 kHz: 320 ticks
         * is one 20 ms frame, 16 ticks one millisecond. */

        /* 1. A big spike is exactly what needs the depth. The old rule
         * only grew for a delta UNDER 200 ms, so a 300 ms arrival -- the
         * one that will certainly be discarded next time -- grew the
         * buffer by nothing at all. */
        JoanJitter spike = new JoanJitter();
        spike.setClockRate(16000);
        spike.offer(1, 0, 0, p, 0L);
        spike.poll(0L);
        int beforeSpike = spike.depth();
        spike.offer(2, 320L, 320L + 300 * 16L, p, 20L);
        check(spike.depth() > beforeSpike,
                "a 300 ms arrival grows the buffer instead of being ignored");

        /* 2. Growth goes straight to the computed size. A 150 ms offset
         * wants (150 + 10) / 20 = 8 frames, and stepping one frame at a
         * time would discard audio on the way there. */
        JoanJitter jump = new JoanJitter();
        jump.setClockRate(16000);
        jump.offer(1, 0, 0, p, 0L);
        jump.poll(0L);
        jump.offer(2, 320L, 320L + 150 * 16L, p, 20L);
        check(jump.depth() >= 8,
                "growth reaches the computed target in one step");
        check(jump.depth() <= JoanJitter.MAX_DEPTH, "and still respects the cap");

        /* 3. The decision is the worst offset in the WINDOW, not the
         * newest arrival. Calm packets must not talk the buffer back down
         * while the window still remembers the spike -- sizing from the
         * latest sample alone is what let one quiet packet undo it. */
        int jumpHeld = jump.depth();
        for (int i = 3; i < 60; i++) {
            jump.offer(i, i * 320L, i * 320L + 150 * 16L, p, i * 20L);
            jump.poll(i * 20L);
        }
        check(jump.depth() == jumpHeld,
                "calm packets do not shrink the buffer while the window holds the spike");

        /* 4. Once the spike ages out of the window and the link has been
         * quiet for the dwell, it does come back down -- stepped, and
         * never below the floor. */
        JoanJitter relax = new JoanJitter();
        relax.setClockRate(16000);
        relax.offer(1, 0, 0, p, 0L);
        relax.poll(0L);
        relax.offer(2, 320L, 320L + 150 * 16L, p, 20L);
        int relaxGrown = relax.depth();
        for (int i = 3; i < 400; i++) {
            relax.offer(i, i * 320L, i * 320L, p, i * 20L);
            relax.poll(i * 20L);
        }
        check(relax.depth() < relaxGrown,
                "a quiet link eventually gets the depth back");
        check(relax.depth() >= JoanJitter.MIN_DEPTH,
                "but never below the floor");
        /* The ceiling is latency the call never gets back. AOSP's IMS
         * media stack caps its audio jitter buffer at 9 frames; ours was
         * 50, a full second at 20 ms a frame. */
        check(JoanJitter.MAX_DEPTH <= 9,
                "the buffer cannot grow past AOSP's 9-frame ceiling");
        check(JoanJitter.MAX_DEPTH * JoanJitter.PACKET_INTERVAL_MS <= 180,
                "the deepest buffer is at most 180 ms of held audio");
        check(JoanJitter.MAX_QUEUE >= JoanJitter.MAX_DEPTH + JoanJitter.SLACK,
                "the arrival guard never cuts below the drain bound");
        check(JoanJitter.MIN_DEPTH >= 2,
                "the floor keeps at least one packet of slack");

        /* The buffer holds until it has filled, rather than starting on
         * the first packet and running dry. */
        JoanJitter fill = new JoanJitter();
        fill.setClockRate(16000);
        fill.offer(9000, 0, 0, p, 0L);
        check(fill.poll(0L) == null,
                "one packet is not enough to start playing");

        /* The fill must be judged by elapsed time, not by how many
         * packets are queued. A count-based rule never completes when
         * packets go missing during the fill, and playback simply never
         * starts -- audio that intermittently does not begin at all. */
        JoanJitter lossy = new JoanJitter();
        lossy.setClockRate(16000);
        lossy.offer(300, 0, 0, p, 0L);
        lossy.offer(302, 640L, 640L, p, 40L);   /* 301 never arrives */
        check(lossy.poll(0L) == null, "nothing plays at once");
        byte[] started = null;
        for (int t = 20; t <= 400 && started == null; t += 20) {
            started = lossy.poll(t);
        }
        check(started != null,
                "playback still starts when the fill loses a packet");

        /* An SSRC change is a different stream: carrying the old
         * sequence baseline across it reads as enormous loss and the
         * reordering guard then throws away everything that arrives. */
        JoanJitter ss = new JoanJitter();
        ss.setClockRate(16000);
        check(!ss.onSsrc(0x1111), "the first SSRC is not a change");
        for (int i = 0; i < 10; i++) {
            ss.offer(60000 + i, i * 320L, i * 320L, p, i * 20L);
        }
        check(ss.received() == 10, "packets counted before the change");
        check(ss.onSsrc(0x2222), "a new SSRC reports as a change");
        check(ss.received() == 0 && ss.queued() == 0
                        && ss.cumulativeLost() == 0,
                "and resets the buffer rather than reading it as loss");
        check(!ss.onSsrc(0x2222), "the same SSRC again is not a change");

        /* Comfort noise is the one moment latency can be given back
         * without anybody hearing the stream shorten. */
        JoanJitter sid = new JoanJitter();
        sid.setClockRate(16000);
        for (int i = 0; i < 30; i++) {
            long arrive = i * 320L + (i % 3 == 0 ? 1600L : 0L);
            sid.offer(70000 + i, i * 320L, arrive, p, false, i * 20L);
        }
        int grown = sid.depth();
        for (int i = 30; i < 60; i++) {
            sid.offer(70000 + i, i * 320L, i * 320L, p, true, i * 20L);
        }
        for (int t = 0; t < 80; t++) {
            sid.poll(2000L + t * 20L);
        }
        check(sid.depth() <= grown,
                "playing comfort noise does not grow the buffer");
        check(sid.depth() >= JoanJitter.MIN_DEPTH,
                "and never shrinks past the floor");

        /* The queue must not be able to grow without bound. Offering one
         * packet and playing one per arrival can never work off frames
         * accumulated during the fill, so without a ceiling the backlog
         * is permanent: measured on the bench as nine frames held
         * against a target of two, about 180 ms the call never gets
         * back. */
        JoanJitter back = new JoanJitter();
        back.setClockRate(16000);
        for (int i = 0; i < 200; i++) {
            back.offer(21000 + i, i * 320L, i * 320L, p, i * 20L);
            if (i % 3 != 0) {
                back.poll(i * 20L);   /* play less often than we receive */
            }
        }
        check(back.queued() <= back.depth() + JoanJitter.SLACK,
                "a backlog is bounded by the depth plus slack");
        check(back.queued() < 20,
                "and does not grow without limit when polled too seldom");

        /* But an ordinary stream must not be trimmed. One or two frames
         * over the target is normal -- an arrival can land just before
         * its slot -- and cutting that would tear a healthy stream. */
        JoanJitter calm = new JoanJitter();
        calm.setClockRate(16000);
        for (int i = 0; i < 60; i++) {
            calm.offer(22000 + i, i * 320L, i * 320L, p, i * 20L);
            calm.poll(i * 20L);
        }
        check(calm.dropped() == 0,
                "a well-behaved stream loses nothing to the ceiling");

        /* late and trimmed mean opposite things and must not be summed
         * into one number: late says the buffer is too shallow for this
         * link, trimmed says it is holding the latency down as designed.
         * The first bounded call reported late_dropped=5 and could not
         * say which it was. */
        check(back.trimmed() > 0 && back.late() == 0,
                "a backlog is trimmed, and none of it counts as late");
        JoanJitter lateOnly = new JoanJitter();
        lateOnly.setClockRate(16000);
        for (int i = 0; i < 20; i++) {
            lateOnly.offer(600 + i, i * 320L, i * 320L, p, i * 20L);
            lateOnly.poll(i * 20L);
        }
        int trimmedBefore = lateOnly.trimmed();
        lateOnly.offer(600, 0, 0, p, 500L);      /* its slot long gone */
        check(lateOnly.late() == 1,
                "a packet past its slot counts as late");
        check(lateOnly.trimmed() == trimmedBefore,
                "and not as a trim");
        check(lateOnly.dropped() == lateOnly.late() + lateOnly.trimmed(),
                "dropped() is still the total of both");

        /* A gap and a fill both return null and need opposite answers:
         * a gap wants concealment so the stream keeps its timing, a fill
         * wants silence because the call has not started. Concealing
         * during the fill would invent audio before the first real
         * frame. */
        JoanJitter gp = new JoanJitter();
        gp.setClockRate(16000);
        gp.offer(800, 0, 0, p, 0L);
        check(gp.poll(0L) == null && !gp.lastWasGap(),
                "a null while filling is not a gap");
        for (int i = 1; i < 6; i++) {
            gp.offer(800 + i, i * 320L, i * 320L, p, i * 20L);
        }
        byte[] first = null;
        for (int t = 20; t <= 300 && first == null; t += 20) {
            first = gp.poll(t);
        }
        check(first != null, "playback starts once the fill completes");
        /* 806 never arrives; 807 does. */
        gp.offer(807, 7 * 320L, 7 * 320L, p, 200L);
        byte[] out = null;
        boolean sawGap = false;
        for (int t = 220; t <= 600; t += 20) {
            out = gp.poll(t);
            if (out == null && gp.lastWasGap()) {
                sawGap = true;
                break;
            }
        }
        check(sawGap, "a missing frame reports as a gap");

        /* The lost-frame signal RFC 4867 s4.3.2 defines, which is what
         * asks the decoder to run its own concealment. */
        byte[] lf = new byte[4];
        check(JoanAmr.lostFrame(lf) == 1, "a lost frame is one byte");
        check(((lf[0] >> 3) & 0x0f) == JoanAmr.FT_SPEECH_LOST,
                "and carries frame type 14, SPEECH_LOST");
        check((lf[0] & 0x80) == 0, "with the F bit clear");
        check(JoanAmr.lostFrame(new byte[0]) < 0,
                "and refuses a buffer it cannot fill");

        /* observe() must account exactly as offer() does, so the report
         * blocks are right whether or not playback is buffering yet. */
        JoanJitter o1 = new JoanJitter();
        JoanJitter o2 = new JoanJitter();
        o1.setClockRate(16000);
        o2.setClockRate(16000);
        for (int i = 0; i < 12; i++) {
            if (i == 3) {
                continue;
            }
            o1.offer(40 + i, i * 320L, i * 320L, p, i * 20L);
            o2.observe(40 + i, i * 320L, i * 320L, i * 20L);
        }
        check(o1.cumulativeLost() == o2.cumulativeLost()
                        && o1.received() == o2.received()
                        && o1.extendedMaxSeq() == o2.extendedMaxSeq(),
                "observe() and offer() agree on the reception statistics");
        check(o2.queued() == 0,
                "but observe() queues nothing");

        /* reset() must clear everything: a tracker outlives one call. */
        o1.reset();
        check(o1.received() == 0 && o1.cumulativeLost() == 0
                        && o1.queued() == 0 && o1.jitter() == 0
                        && o1.extendedMaxSeq() == 0,
                "reset clears the statistics for the next call");

        /* RFC 3550 6.4.1: cumulative lost is SIGNED 24-bit. Duplicates
         * can drive it negative, and a report that truncates instead of
         * sign-extending claims enormous loss on a healthy call. */
        JoanJitter dup = new JoanJitter();
        dup.setClockRate(16000);
        for (int i = 0; i < 5; i++) {
            dup.offer(80 + i, i * 320L, i * 320L, p, i * 20L);
        }
        for (int i = 0; i < 3; i++) {
            dup.offer(80 + i, i * 320L, i * 320L, p, 100L + i);
        }
        check(dup.cumulativeLost() < 0,
                "duplicates make cumulative lost negative, as the RFC allows");
    }

    /**
     * PCMU gap concealment and pacing. Until this existed a lost PCMU
     * frame wrote nothing at all and the track underran -- on a call
     * measured at 42% loss the far side came through as torn bursts.
     */
    private static void testPcmu() {
        /* The fade: a short hole is bridged with something voice-shaped,
         * a long one must not repeat a syllable forever. */
        short[] last = { 400, -800, 1200, 0 };
        short[] dst = new short[4];
        JoanPcmu.conceal(dst, last, 0);
        check(dst[0] == 400 && dst[1] == -800 && dst[2] == 1200,
                "the first concealed frame is the last real frame");
        JoanPcmu.conceal(dst, last, 1);
        check(dst[0] == 300 && dst[1] == -600 && dst[2] == 900,
                "the second fades to three quarters");
        JoanPcmu.conceal(dst, last, 2);
        check(dst[0] == 200 && dst[1] == -400 && dst[2] == 600,
                "the third to a half");
        JoanPcmu.conceal(dst, last, 3);
        check(dst[0] == 100 && dst[1] == -200 && dst[2] == 300,
                "the fourth to a quarter");
        JoanPcmu.conceal(dst, last, 4);
        check(dst[0] == 0 && dst[1] == 0 && dst[2] == 0,
                "past the fade the frame is silence, not an echo");
        JoanPcmu.conceal(dst, null, 0);
        check(dst[0] == 0 && dst[2] == 0,
                "no last frame yet means silence, not an exception");
        JoanPcmu.conceal(dst, new short[8], 0);
        check(dst[0] == 0,
                "a mismatched last-frame length is silence, not a copy");

        /* The pace: the track is owed frames by wall clock, not by
         * arrivals, and a stall too long to replay re-anchors instead. */
        check(JoanPcmu.framesOwed(1000, 20, 0, 1000) == 0,
                "at anchor nothing is owed");
        check(JoanPcmu.framesOwed(1000, 20, 0, 1019) == 0,
                "19 ms in, not yet one frame");
        check(JoanPcmu.framesOwed(1000, 20, 0, 1020) == 1,
                "20 ms owes exactly one");
        check(JoanPcmu.framesOwed(1000, 20, 5, 1120) == 1,
                "written frames are subtracted, not replayed");
        check(JoanPcmu.framesOwed(1000, 20, 3, 1060) == 0,
                "being ahead owes nothing back");
        check(JoanPcmu.framesOwed(1000, 20, 0, 200) == 0,
                "a clock behind the anchor owes nothing");
        check(JoanPcmu.framesOwed(1000, 20, 0, 1200) == 10,
                "behind by exactly the cap is still catch-up");
        check(JoanPcmu.framesOwed(1000, 20, 0, 1500) == -1,
                "behind by more than the cap re-anchors, not replays");
        check(JoanPcmu.framesOwed(1000, 0, 5, 5000) == -1,
                "a zero frame period is guarded, not divided by");
    }

    private static void testRegInfo() {
        String ours = "sip:joan@[2001:db8::1]:5060";
        String active = "<?xml version=\"1.0\"?>"
                + "<reginfo xmlns=\"urn:ietf:params:xml:ns:reginfo\""
                + " version=\"0\" state=\"full\">"
                + "<registration aor=\"sip:u@ims\" id=\"a7\" state=\"active\">"
                + "<contact id=\"76\" state=\"active\" event=\"registered\">"
                + "<uri>sip:joan@2001:db8::1:5060</uri></contact>"
                + "</registration></reginfo>";
        check(JoanRegInfo.parse(active, ours) == JoanRegInfo.STATE_ACTIVE,
                "an active binding reads as active");

        String deact = active.replace("state=\"active\" event=\"registered\"",
                "state=\"terminated\" event=\"deactivated\"");
        check(JoanRegInfo.parse(deact, ours)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "deactivated means register again");

        String rej = active.replace("state=\"active\" event=\"registered\"",
                "state=\"terminated\" event=\"rejected\"");
        check(JoanRegInfo.parse(rej, ours)
                        == JoanRegInfo.STATE_TERMINATED_FINAL,
                "rejected means do not come back");
        String unreg = active.replace("state=\"active\" event=\"registered\"",
                "state=\"terminated\" event=\"unregistered\"");
        check(JoanRegInfo.parse(unreg, ours)
                        == JoanRegInfo.STATE_TERMINATED_FINAL,
                "unregistered means do not come back either");
        String expired = active.replace("state=\"active\" event=\"registered\"",
                "state=\"terminated\" event=\"expired\"");
        check(JoanRegInfo.parse(expired, ours)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "an expired binding is worth re-registering");

        /* Another device on the same public identity is not news. */
        String other = "<reginfo xmlns=\"urn:ietf:params:xml:ns:reginfo\">"
                + "<registration aor=\"sip:u@ims\" state=\"active\">"
                + "<contact id=\"9\" state=\"terminated\" event=\"deactivated\">"
                + "<uri>sip:other@198.51.100.7:5060</uri></contact>"
                + "</registration></reginfo>";
        check(JoanRegInfo.parse(other, ours) == JoanRegInfo.STATE_UNKNOWN,
                "another handset's deregistration is not ours");
        check(JoanRegInfo.parse(other, null)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "with no uri to match on, any contact counts");

        /* Junk from the network must not be read as a deregistration. */
        check(JoanRegInfo.parse(null, ours) == JoanRegInfo.STATE_UNKNOWN
                        && JoanRegInfo.parse("", ours) == JoanRegInfo.STATE_UNKNOWN
                        && JoanRegInfo.parse("not xml at all", ours)
                                == JoanRegInfo.STATE_UNKNOWN
                        && JoanRegInfo.parse("<reginfo><contact", ours)
                                == JoanRegInfo.STATE_UNKNOWN,
                "a malformed body is never read as a deregistration");

        check("terminated".equals(JoanRegInfo.attr(
                        "<contact state='terminated' event='deactivated'>",
                        "state")),
                "single-quoted attributes are read");
        check("deactivated".equals(JoanRegInfo.attr(
                        "<contact state=terminated event=deactivated>",
                        "event")),
                "unquoted attributes are read");

        check("2001:db8::1".equals(JoanRegInfo.hostOf(
                        "<sip:joan@[2001:db8::1]:5060>;expires=600")),
                "the host survives brackets, port, params and angle quotes");
        check("example.invalid".equals(
                        JoanRegInfo.hostOf("sip:u@example.invalid")),
                "a plain host is read");
        check(JoanRegInfo.hostOf(null) == null,
                "no uri gives no host");

        /* A namespace prefix is legal and changes nothing about meaning.
         * Missing it does not throw -- it finds no contacts at all, which
         * reads exactly like a body that said nothing about us. */
        String pfx = "<reg:reginfo xmlns:reg=\"urn:ietf:params:xml:ns:reginfo\">"
                + "<reg:registration aor=\"sip:u@ims\" state=\"active\">"
                + "<reg:contact id=\"76\" state=\"terminated\" event=\"deactivated\">"
                + "<reg:uri>sip:joan@2001:db8::1:5060</reg:uri>"
                + "</reg:contact></reg:registration></reg:reginfo>";
        check(JoanRegInfo.parse(pfx, ours)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "a namespace-prefixed contact is still read");
        check(JoanRegInfo.nextContact(
                        pfx.toLowerCase(java.util.Locale.US), 0) > 0,
                "nextContact finds a prefixed element");
        check(JoanRegInfo.nextContact("<contacts>no</contacts>", 0) < 0,
                "a longer element merely starting with contact is not one");

        /* The description must separate "no contacts" from "not ours",
         * and must never carry a URI. */
        String d1 = JoanRegInfo.describe(active, ours);
        check(d1.contains("contacts=1") && d1.contains("matched=1"),
                "describe counts our own contact as matched");
        String d2 = JoanRegInfo.describe(other, ours);
        check(d2.contains("contacts=1") && d2.contains("matched=0"),
                "describe separates somebody else's contact from ours");
        check(d2.indexOf("198.51.100.7") < 0 && d2.indexOf("sip:") < 0,
                "describe carries no uri");
        check(JoanRegInfo.describe(null, ours).contains("empty")
                        && JoanRegInfo.describe("hello", ours)
                                .contains("not-reginfo"),
                "describe names an empty or non-reginfo body as such");
        String d3 = JoanRegInfo.describe(
                "<reginfo state=\"full\"></reginfo>", ours);
        check(d3.contains("contacts=0"),
                "a reginfo with no contacts at all says so");

        /* Names, never values: a body's shape has to be reportable
         * without anybody pasting subscriber identities into a bug
         * report. */
        check("id|state|event".equals(JoanRegInfo.attrNames(
                        "<contact id=\"76\" state=\"active\" event=\"registered\">")),
                "attrNames lists names in order");
        check(JoanRegInfo.attrNames(
                        "<contact id=\"a=b\" state=\"active\">").indexOf("b") < 0,
                "a value containing = is not read as another attribute");
        check("uri|unknown-param".equals(JoanRegInfo.childNames(
                        "<uri>sip:x@y</uri><unknown-param name=\"+sip.instance\">z"
                        + "</unknown-param>")),
                "childNames lists each child once, without values");

        String inst = "<reginfo><registration state=\"active\">"
                + "<contact id=\"1\" state=\"active\" event=\"registered\">"
                + "<uri>sip:x@host</uri>"
                + "<unknown-param name=\"+sip.instance\">"
                + "&lt;urn:gsma:imei:11111111-222222-3&gt;</unknown-param>"
                + "</contact></registration></reginfo>";
        String d4 = JoanRegInfo.describe(inst, ours, "11111111-222222-3");
        check(d4.contains("inst_seen=true") && d4.contains("inst_match=true"),
                "an echoed +sip.instance is detected and matched");
        check(d4.indexOf("11111111") < 0,
                "describe never carries the instance value");
        String d5 = JoanRegInfo.describe(inst, ours, "99999999-888888-7");
        check(d5.contains("inst_seen=true") && d5.contains("inst_match=false"),
                "another device's instance does not read as ours");
        check(JoanRegInfo.describe(active, ours, "x").contains("inst_seen=false"),
                "a body with no instance parameter says so");

        /* Instance first, host second. The address changes across a
         * re-registration and the network keeps listing the old binding;
         * matching on host alone then calls every contact somebody
         * else's. Observed on T-Mobile as contacts=4 matched=0 straight
         * after a reboot, matching again once the address settled. */
        String OURINST = "11111111-222222-3";
        String THEIRS = "99999999-888888-7";
        String staleHost = "<reginfo><registration state=\"active\">"
                + "<contact id=\"1\" state=\"terminated\" event=\"deactivated\">"
                + "<uri>sip:x@2001:db8::OLD:5060</uri>"
                + "<unknown-param name=\"+sip.instance\">"
                + "&lt;urn:gsma:imei:" + OURINST + "&gt;</unknown-param>"
                + "</contact></registration></reginfo>";
        check(JoanRegInfo.parse(staleHost, ours, OURINST)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "our instance identifies us even when the address moved on");
        check(JoanRegInfo.parse(staleHost, ours) == JoanRegInfo.STATE_UNKNOWN,
                "and host matching alone would have missed it");

        String otherInst = staleHost.replace(OURINST, THEIRS)
                .replace("2001:db8::OLD", "2001:db8::1");
        check(JoanRegInfo.parse(otherInst, ours, OURINST)
                        == JoanRegInfo.STATE_UNKNOWN,
                "a contact naming another instance is refused even on our host");
        check(JoanRegInfo.parse(otherInst, ours)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "which host matching alone would have wrongly claimed");

        /* A contact with no instance at all still falls back to the host,
         * so a network that does not echo it is no worse off. */
        String noInst = "<reginfo><registration state=\"active\">"
                + "<contact id=\"1\" state=\"terminated\" event=\"deactivated\">"
                + "<uri>sip:x@2001:db8::1:5060</uri>"
                + "</contact></registration></reginfo>";
        check(JoanRegInfo.parse(noInst, ours, OURINST)
                        == JoanRegInfo.STATE_TERMINATED_REREGISTER,
                "no instance in the body falls back to matching the host");
    }

    private static void testSessionTimer() {
        /* Parsing, against what a network actually sends. */
        check(JoanSessionTimer.parseExpires("1800;refresher=uac") == 1800,
                "session-expires reads the seconds before the parameters");
        check(JoanSessionTimer.parseExpires(" 600 ") == 600,
                "session-expires tolerates surrounding space");
        check(JoanSessionTimer.parseExpires("abc") < 0
                        && JoanSessionTimer.parseExpires(null) < 0
                        && JoanSessionTimer.parseExpires("18x0") < 0,
                "a non-numeric session-expires is refused, not guessed");
        check(JoanSessionTimer.parseExpires("99999999") == 86400,
                "an absurd interval is capped rather than overflowed");
        check(JoanSessionTimer.parseRefresher("1800;refresher=uac")
                        == JoanSessionTimer.REFRESHER_UAC,
                "refresher=uac is read");
        check(JoanSessionTimer.parseRefresher("1800;REFRESHER=UAS")
                        == JoanSessionTimer.REFRESHER_UAS,
                "refresher is case-insensitive");
        check(JoanSessionTimer.parseRefresher("1800")
                        == JoanSessionTimer.REFRESHER_UNKNOWN,
                "no refresher parameter reads as unknown");
        check(JoanSessionTimer.parseRefresher("1800;refresher=bogus")
                        == JoanSessionTimer.REFRESHER_UNKNOWN,
                "an unrecognised refresher is not taken for either side");

        /* Option tags have to match as tokens, not as substrings. */
        check(JoanSessionTimer.peerSupportsTimer("timer", null),
                "Supported: timer is enough");
        check(JoanSessionTimer.peerSupportsTimer(null, "timer"),
                "Require: timer is enough");
        check(JoanSessionTimer.peerSupportsTimer("100rel, timer, precondition",
                        null),
                "timer is found in a list");
        check(!JoanSessionTimer.peerSupportsTimer("timers", null)
                        && !JoanSessionTimer.peerSupportsTimer("no-timer", null),
                "a longer token that merely contains timer does not count");
        check(!JoanSessionTimer.peerSupportsTimer(null, null),
                "a peer that says nothing gets no session timer");

        check(JoanSessionTimer.allowsUpdate("INVITE, ACK, UPDATE, BYE"),
                "UPDATE is found in Allow");
        check(!JoanSessionTimer.allowsUpdate("INVITE, ACK, BYE"),
                "a peer without UPDATE is not sent one");
        check(JoanSessionTimer.refreshWithUpdate(
                        JoanSessionTimer.METHOD_UPDATE_PREFERRED,
                        "INVITE, ACK, UPDATE, BYE"),
                "UPDATE preferred and allowed means UPDATE");
        check(!JoanSessionTimer.refreshWithUpdate(
                        JoanSessionTimer.METHOD_UPDATE_PREFERRED,
                        "INVITE, ACK, BYE"),
                "UPDATE preferred but not allowed falls back to re-INVITE");
        check(!JoanSessionTimer.refreshWithUpdate(
                        JoanSessionTimer.METHOD_INVITE,
                        "INVITE, ACK, UPDATE, BYE"),
                "a carrier asking for INVITE gets INVITE");

        /* Negotiation. */
        check(JoanSessionTimer.minSe(30) == JoanSessionTimer.MIN_SE_FLOOR,
                "Min-SE never goes below the RFC 4028 floor");
        check(JoanSessionTimer.minSe(120) == 120,
                "a carrier Min-SE above the floor is kept");
        check(JoanSessionTimer.offerExpires(60, 90) == 90,
                "an offer below our own Min-SE is raised to it");
        check(JoanSessionTimer.offerExpires(0, 90)
                        == JoanSessionTimer.DEFAULT_EXPIRES_SEC,
                "an unset interval uses the AOSP default");
        check(JoanSessionTimer.rejectBelowMinSe(60, 90) == 90,
                "an INVITE below our Min-SE is answered 422 with our value");
        check(JoanSessionTimer.rejectBelowMinSe(1800, 90) == 0,
                "an acceptable interval is not rejected");
        check(JoanSessionTimer.rejectBelowMinSe(0, 90) == 0,
                "an INVITE with no timer is not rejected for one");
        check(JoanSessionTimer.retryExpiresAfter422(120, 3600) == 120,
                "a 422 is retried at the peer's Min-SE");
        check(JoanSessionTimer.retryExpiresAfter422(30, 3600) < 0,
                "a 422 demanding less than the floor is not retried");
        check(JoanSessionTimer.retryExpiresAfter422(7200, 3600) < 0,
                "a 422 demanding more than we will hold is not retried");

        check(JoanSessionTimer.uasRefresher(JoanSessionTimer.REFRESHER_UAC,
                        JoanSessionTimer.REFRESHER_UAS)
                        == JoanSessionTimer.REFRESHER_UAC,
                "the peer's stated preference wins over ours");
        check(JoanSessionTimer.uasRefresher(JoanSessionTimer.REFRESHER_UNKNOWN,
                        JoanSessionTimer.REFRESHER_UAC)
                        == JoanSessionTimer.REFRESHER_UAC,
                "with no preference the carrier config decides");
        check(JoanSessionTimer.uasRefresher(JoanSessionTimer.REFRESHER_UNKNOWN,
                        JoanSessionTimer.REFRESHER_UNKNOWN)
                        == JoanSessionTimer.REFRESHER_UAS,
                "with nothing stated we refresh rather than nobody");
        check(JoanSessionTimer.requireTimerInAnswer(
                        JoanSessionTimer.REFRESHER_UAC),
                "naming the UAC as refresher requires timer of it");
        check(!JoanSessionTimer.requireTimerInAnswer(
                        JoanSessionTimer.REFRESHER_UAS),
                "taking the work ourselves demands nothing of the peer");

        check(JoanSessionTimer.weRefresh(JoanSessionTimer.REFRESHER_UAC, true)
                        && !JoanSessionTimer.weRefresh(
                                JoanSessionTimer.REFRESHER_UAC, false),
                "refresher=uac means the caller refreshes");
        check(!JoanSessionTimer.weRefresh(JoanSessionTimer.REFRESHER_UAS, true)
                        && JoanSessionTimer.weRefresh(
                                JoanSessionTimer.REFRESHER_UAS, false),
                "refresher=uas means the callee refreshes");
        check(JoanSessionTimer.weRefresh(
                        JoanSessionTimer.REFRESHER_UNKNOWN, true),
                "an unnamed refresher is taken by us, not assumed of them");

        check("1800;refresher=uac".equals(JoanSessionTimer.expiresHeader(
                        1800, JoanSessionTimer.REFRESHER_UAC)),
                "the header names the refresher");
        check("1800".equals(JoanSessionTimer.expiresHeader(
                        1800, JoanSessionTimer.REFRESHER_UNKNOWN)),
                "an unknown refresher is left off the header");

        /* Timing, RFC 4028 s10. */
        check(JoanSessionTimer.refreshDueMs(1800, true) == 900000L,
                "the refresher acts at half the interval");
        check(JoanSessionTimer.refreshDueMs(1800, false) == 1768000L,
                "a long interval lets the other side act 32s before expiry");
        check(JoanSessionTimer.refreshDueMs(90, false) == 67000L,
                "a short interval uses three quarters instead");
        check(JoanSessionTimer.refreshDueMs(0, true) < 0,
                "an untimed session has no refresh due");
        check(JoanSessionTimer.refreshDueMs(1, true) == 1000L,
                "the refresh never lands at zero");
        check(JoanSessionTimer.expiryDueMs(1800) == 1800000L
                        && JoanSessionTimer.expiryDueMs(0) < 0,
                "expiry is the whole interval, or nothing");
        check(JoanSessionTimer.refreshDueMs(1800, true)
                        < JoanSessionTimer.expiryDueMs(1800),
                "there is room for a second attempt before expiry");
        check(JoanSessionTimer.due(1000L, 1000L)
                        && !JoanSessionTimer.due(1000L, 999L)
                        && !JoanSessionTimer.due(-1L, 99999L),
                "a deadline of -1 is never due");

        /* What actually goes on the wire. */
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "310260123456789@ims.mnc260.mcc310.3gppnetwork.org",
                "sip:+15550000@ims.mnc260.mcc310.3gppnetwork.org",
                "ims.mnc260.mcc310.3gppnetwork.org",
                "2001:db8::1", 5060, 5060, null);
        JoanSipBuilder.setSessionTimer(0, 90, JoanSessionTimer.REFRESHER_UAC);
        String off = JoanSipBuilder.buildInvite(id,
                new JoanSipBuilder.Dialog(), "sip:peer@host", "", null,
                40000, null);
        check(off.indexOf("Session-Expires") < 0
                        && off.indexOf("Min-SE") < 0
                        && off.indexOf("Supported: timer") < 0,
                "a carrier with timers off gets no Session-Expires at all");

        JoanSipBuilder.setSessionTimer(1800, 90,
                JoanSessionTimer.REFRESHER_UAC);
        String on = JoanSipBuilder.buildInvite(id,
                new JoanSipBuilder.Dialog(), "sip:peer@host", "", null,
                40000, null);
        check(on.indexOf("Supported: timer\r\n") > 0,
                "the INVITE advertises the timer option tag");
        check(on.indexOf("Session-Expires: 1800;refresher=uac\r\n") > 0,
                "the INVITE offers the interval and names the refresher");
        check(on.indexOf("Min-SE: 90\r\n") > 0,
                "the INVITE carries Min-SE");
        check(on.indexOf("Session-Expires") < on.indexOf("Content-Length"),
                "the timer headers land before the body");

        JoanSipBuilder.setSessionTimer(1800, 30,
                JoanSessionTimer.REFRESHER_UAC);
        String floored = JoanSipBuilder.buildInvite(id,
                new JoanSipBuilder.Dialog(), "sip:peer@host", "", null,
                40000, null);
        check(floored.indexOf("Min-SE: 90\r\n") > 0,
                "a carrier Min-SE below the floor is raised on the wire");

        check("Session-Expires: 1800;refresher=uac\r\nRequire: timer\r\n"
                        .equals(JoanSipBuilder.sessionTimerAnswerHeaders(
                                1800, JoanSessionTimer.REFRESHER_UAC)),
                "a 2xx naming the caller as refresher requires timer");
        check("Session-Expires: 1800;refresher=uas\r\n"
                        .equals(JoanSipBuilder.sessionTimerAnswerHeaders(
                                1800, JoanSessionTimer.REFRESHER_UAS)),
                "a 2xx taking the work itself requires nothing");
        check("".equals(JoanSipBuilder.sessionTimerAnswerHeaders(
                        0, JoanSessionTimer.REFRESHER_UAS)),
                "an untimed answer carries no Session-Expires");
        check(JoanSipBuilder.sessionTimerRefreshHeaders(600,
                        JoanSessionTimer.REFRESHER_UAC)
                        .indexOf("Session-Expires: 600;refresher=uac") >= 0,
                "a refresh carries the agreed interval, not the offered one");

        /* Leave the builder as the rest of the suite expects it. */
        JoanSipBuilder.setSessionTimer(0, 90, JoanSessionTimer.REFRESHER_UAC);
    }

    /**
     * RFC 7989 Session-ID, against AOSP's SipUtils::GenerateSessionId
     * construction: HMAC-SHA-1 over the Call-ID, leading 128 bits,
     * lowercase hex.
     */
    private static void testSessionId() {
        String a = JoanSipBuilder.sessionIdFor("call-one@host");
        String b = JoanSipBuilder.sessionIdFor("call-two@host");

        check(a.length() == 32, "session-id is 32 characters");
        check(a.matches("[0-9a-f]{32}"), "session-id is lowercase hex");
        check(!a.equals(b), "a different Call-ID gives a different UUID");
        check(a.equals(JoanSipBuilder.sessionIdFor("call-one@host")),
                "the same Call-ID gives the same UUID (RFC 7989 7: the "
                + "local UUID does not change during a session)");
        check("".equals(JoanSipBuilder.sessionIdFor(null))
                        && "".equals(JoanSipBuilder.sessionIdFor("")),
                "no Call-ID means no session-id, not a constant");

        /* RFC 7989 6: the UUID must not be derivable from a user or
         * device identifier. The Call-ID is the only input, and the key
         * is random per process -- but assert the obvious failure mode
         * anyway, because it is the one that would leak a subscriber. */
        String imsi = "310260123456789";
        String imei = "123456789012345";
        String fromIds = JoanSipBuilder.sessionIdFor(imsi + "@host");
        check(fromIds.indexOf(imsi) < 0 && fromIds.indexOf(imei) < 0,
                "session-id carries neither IMSI nor IMEI");
        check(JoanSipBuilder.sessionIdFor("x").indexOf("x") < 0,
                "session-id is not the Call-ID in disguise");

        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "310260123456789@ims.mnc260.mcc310.3gppnetwork.org",
                "sip:+15550000@ims.mnc260.mcc310.3gppnetwork.org",
                "ims.mnc260.mcc310.3gppnetwork.org",
                "2001:db8::1", 5060, 5060, null);

        /* The INVITE opens with the null UUID: we have not been told the
         * peer's yet (RFC 7989 7). */
        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        String inv = JoanSipBuilder.buildInvite(id, dlg, "sip:peer@host",
                "", null, 40000, null);
        String local = JoanSipBuilder.sessionIdFor(dlg.callId);
        check(inv.indexOf("Session-ID: " + local + ";remote="
                        + JoanSipBuilder.SESSION_ID_NULL + "\r\n") > 0,
                "an initial INVITE carries our UUID and the null remote");
        check(local.equals(dlg.sessionId),
                "the dialog's UUID is the one derived from its Call-ID");

        /* The peer names itself; every later in-dialog request pairs the
         * two. */
        String peer = "0123456789abcdef0123456789abcdef";
        String ok200 = "SIP/2.0 200 OK\r\nCall-ID: " + dlg.callId
                + "\r\nSession-ID: " + peer + ";remote=" + local
                + "\r\nContent-Length: 0\r\n\r\n";
        JoanSipBuilder.learnSessionId(dlg, ok200);
        check(peer.equals(dlg.sessionIdRemote),
                "the peer's sess-id becomes our remote");
        String bye = JoanSipBuilder.buildBye(id, dlg, "sip:peer@host", "",
                null, "<sip:peer@host>;tag=b", "<sip:us@host>;tag=a");
        check(bye.indexOf("Session-ID: " + local + ";remote=" + peer
                        + "\r\n") > 0,
                "an in-dialog request carries both halves");

        /* RFC 7989 10: a legacy RFC 7329 peer reflects what it was given.
         * Taking that back would set remote == local and erase the
         * distinction the header exists to draw. */
        JoanSipBuilder.Dialog legacy = new JoanSipBuilder.Dialog();
        JoanSipBuilder.buildInvite(id, legacy, "sip:peer@host", "", null,
                40000, null);
        JoanSipBuilder.learnSessionId(legacy, "SIP/2.0 200 OK\r\n"
                + "Session-ID: " + legacy.sessionId + "\r\n\r\n");
        check(legacy.sessionIdRemote == null,
                "a reflected UUID is not adopted as the remote");
        JoanSipBuilder.learnSessionId(legacy, "SIP/2.0 200 OK\r\n"
                + "Session-ID: " + JoanSipBuilder.SESSION_ID_NULL + "\r\n\r\n");
        check(legacy.sessionIdRemote == null,
                "the null UUID teaches us nothing");

        /* Parsing: the sess-id is the token before any parameter. */
        check(peer.equals(JoanSipBuilder.peerSessionId(
                        "INVITE sip:x SIP/2.0\r\nSession-ID: " + peer
                        + ";remote=" + JoanSipBuilder.SESSION_ID_NULL
                        + "\r\n\r\n")),
                "sess-id is read without its parameters");
        check(peer.equals(JoanSipBuilder.peerSessionId(
                        "INVITE sip:x SIP/2.0\r\nsession-id: "
                        + peer.toUpperCase(java.util.Locale.US) + "\r\n\r\n")),
                "an uppercase value on a lowercase header name still parses");
        check("".equals(JoanSipBuilder.peerSessionId(
                        "INVITE sip:x SIP/2.0\r\nSession-ID: abc\r\n\r\n")),
                "a short value is not a UUID");
        check("".equals(JoanSipBuilder.peerSessionId(
                        "INVITE sip:x SIP/2.0\r\nSession-ID: "
                        + "0123456789abcdef0123456789abcdeg\r\n\r\n")),
                "a non-hex character is not a UUID");
        check("".equals(JoanSipBuilder.peerSessionId(
                        "INVITE sip:x SIP/2.0\r\nCSeq: 1 INVITE\r\n\r\n")),
                "no header means no value");

        /* Mirroring into a response, and only when asked. */
        String req = "INVITE sip:us SIP/2.0\r\nCall-ID: mt-1@host\r\n"
                + "Session-ID: " + peer + ";remote="
                + JoanSipBuilder.SESSION_ID_NULL + "\r\n\r\n";
        check(("Session-ID: " + JoanSipBuilder.sessionIdFor("mt-1@host")
                        + ";remote=" + peer + "\r\n")
                        .equals(JoanSipBuilder.sessionIdMirror(req)),
                "a response answers with our UUID and theirs as remote");
        check("".equals(JoanSipBuilder.sessionIdMirror(
                        "OPTIONS sip:us SIP/2.0\r\nCall-ID: x@host\r\n\r\n")),
                "a request without Session-ID gets a response without one");

        /* Dialogs that are not communication sessions carry none: the
         * reg-event SUBSCRIBE never has a UUID minted for it. */
        JoanSipBuilder.Dialog sub = new JoanSipBuilder.Dialog();
        sub.callId = "sub-1@host";
        String subscribe = JoanSipBuilder.buildRegEventSubscribe(id, sub,
                "sip:+15550000@ims.mnc260.mcc310.3gppnetwork.org", "", null,
                600000);
        check(subscribe.indexOf("Session-ID") < 0,
                "a reg-event SUBSCRIBE carries no Session-ID");

        /* The switch, and the state the rest of the suite expects. */
        JoanSipBuilder.setSendSessionId(false);
        JoanSipBuilder.Dialog offDlg = new JoanSipBuilder.Dialog();
        String offInv = JoanSipBuilder.buildInvite(id, offDlg,
                "sip:peer@host", "", null, 40000, null);
        check(offInv.indexOf("Session-ID") < 0 && offDlg.sessionId == null,
                "a carrier with Session-ID off sends none");
        check("".equals(JoanSipBuilder.sessionIdMirror(req)),
                "and mirrors none into a response");
        JoanSipBuilder.setSendSessionId(true);
        check(JoanSipBuilder.sendSessionId(), "default is on, per AOSP");
    }

    /**
     * The two AOSP routing defaults joan deliberately does not share,
     * pinned so the reasoning is testable rather than only written down.
     */
    private static void testRoutingDivergences() {
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                "310260123456789@ims.mnc260.mcc310.3gppnetwork.org",
                "sip:+15550000@ims.mnc260.mcc310.3gppnetwork.org",
                "ims.mnc260.mcc310.3gppnetwork.org",
                "2001:db8::1", 5060, 5060, null);

        /* AOSP defaults ims.allow_sip_p_access_network_info_header_in_
         * initial_register_bool to false, and joan sends PANI anyway.
         * The divergence is nominal: AOSP's PANI carries
         * utran-cell-id-3gpp (AccessNetworkInfoFormatter.cpp), which is
         * the serving cell -- the subscriber's location, in the clear,
         * before IPsec exists. joan's carries an access-type token and
         * nothing else, so there is nothing in it to protect.
         *
         * This is the tripwire: the day PANI gains cell information, the
         * unprotected REGISTER is where it must not appear. */
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(
                new JoanSipBuilder.Params(1111, 2222, 15000, 16000),
                new java.security.SecureRandom());
        String reg1 = JoanSipBuilder.buildRegister(id, txn, 1,
                null, null, null, null, "3GPP-E-UTRAN-FDD", false);
        check(reg1.indexOf("P-Access-Network-Info: 3GPP-E-UTRAN-FDD") > 0,
                "the unprotected REGISTER carries the access type");
        check(reg1.indexOf("utran-cell-id") < 0
                        && reg1.indexOf("cgi-3gpp") < 0
                        && reg1.indexOf("i-wlan-node-id") < 0,
                "and carries no cell identity: an unprotected REGISTER "
                + "must never locate the subscriber");

        /* The UDP-fallback gate. joan's trigger already matches AOSP's;
         * only the default differs, and it is a switch rather than a
         * constant so a carrier profile or platform key can move it. */
        check(JoanSipBuilder.udpFallbackOnTcpConnectFail(),
                "UDP fallback after a refused TCP connect is on by default");
        JoanSipBuilder.setUdpFallbackOnTcpConnectFail(false);
        check(!JoanSipBuilder.udpFallbackOnTcpConnectFail(),
                "and can be turned off without a code change");
        JoanSipBuilder.setUdpFallbackOnTcpConnectFail(true);

        /* RST-on-close for the protected TCP socket. Default on, but a
         * switch rather than a constant: it tells the P-CSCF the flow
         * died, LG scoped it to one carrier, and it stopped being narrow
         * the moment the MTU-derived criterion put most carriers onto
         * protected TCP in the first place. */
        check(JoanSipBuilder.protectedTcpLingerReset(),
                "the protected TCP socket closes with RST by default");
        JoanSipBuilder.setProtectedTcpLingerReset(false);
        check(!JoanSipBuilder.protectedTcpLingerReset(),
                "and that can be turned off without a rebuild");
        JoanSipBuilder.setProtectedTcpLingerReset(true);
    }

    /**
     * RFC 3261 20.33 Retry-After on a REGISTER rejection. joan honoured
     * this on a 503 to an INVITE and nowhere else; both reference stacks
     * treat it as the governing retry delay for registration.
     */
    private static void testRetryAfter() {
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 503 Service Unavailable\r\n"
                + "Retry-After: 120\r\n\r\n") == 120,
                "a plain delta-seconds is read");
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 404 Not Found\r\n"
                + "Retry-After: 300 (out of service);duration=600\r\n\r\n")
                        == 300,
                "a comment and a duration parameter are ignored");
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 486 Busy\r\nretry-after:  45\r\n\r\n") == 45,
                "the header name is case-insensitive and space is skipped");
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 404 Not Found\r\n\r\n") == -1,
                "absent reads as -1, not as zero seconds");
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 404 Not Found\r\nRetry-After: soon\r\n\r\n") == -1,
                "a non-numeric value is not a delay");
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 404 Not Found\r\nRetry-After: 0\r\n\r\n") == 0,
                "zero is a legal value meaning retry immediately");
        /* A day is the cap; past it the header is treated as unusable
         * rather than parking registration indefinitely. */
        check(JoanSipBuilder.retryAfterSeconds(
                "SIP/2.0 404 Not Found\r\nRetry-After: 999999\r\n\r\n") == -1,
                "an absurd value is rejected rather than honoured");
    }

    /**
     * The identity a subscriber may be shown as. Pins the 2026-08-28
     * incident: a USIM-only card's registration identity is the IMPI,
     * which contains the IMSI, and it once reached a called party's
     * screen as caller ID.
     */
    private static void testDialIdentity() {
        String impi = "310260123456789@ims.mnc260.mcc310.3gppnetwork.org";
        String isimImpu = "sip:+15555550100@ims.mnc260.mcc310.3gppnetwork.org";
        String assoc = "<sip:+15555550100@ims.mnc260.mcc310.3gppnetwork.org>";

        check(JoanSipBuilder.dialIdentity(assoc, isimImpu, impi)
                        .indexOf("+15555550100") >= 0,
                "P-Associated-URI wins: the network says who we are");
        check(isimImpu.equals(JoanSipBuilder.dialIdentity(null, isimImpu, impi)),
                "an ISIM IMPU is used when the network supplied nothing");

        /* The one that matters. On a USIM-only card impu == impi, and
         * the IMPI contains the IMSI. */
        check("".equals(JoanSipBuilder.dialIdentity(null, impi, impi)),
                "a USIM-only identity is refused, not substituted");
        check("".equals(JoanSipBuilder.dialIdentity("", impi, impi)),
                "and refused with an empty header too");
        check(JoanSipBuilder.dialIdentity(null, impi, impi)
                        .indexOf("310260123456789") < 0,
                "the IMSI never survives as a dial identity");
        check("".equals(JoanSipBuilder.dialIdentity(null, null, impi))
                        && "".equals(JoanSipBuilder.dialIdentity(null, "", impi)),
                "no IMPU at all is refused rather than defaulted");
    }

    private static void testImei() {
        /* Was "12345678-901234-5", asserting the IMEI's check digit as the
         * last character. That was wrong: TS 23.003 13.8 / RFC 7254 put a
         * SPARE digit there, and AOSP's SipUrnHelper.cpp appends a literal
         * '0'. The old expectation is kept in this comment because it is
         * what shipped to every tester up to alpha26. Fuller coverage is
         * in testRegisterShape(). */
        check("12345678-901234-0".equals(
                JoanSipBuilder.imeiInstance("123456789012345")),
                "imei instance 15 digits ends in the spare digit 0");
    }
}
