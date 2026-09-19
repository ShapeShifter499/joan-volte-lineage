package org.joan.ims;

import java.net.InetAddress;
import java.util.*;

/** Pure production discovery decisions, no Android constructors or live DNS. */
public class TestJoanDiscovery {
    static int checks;
    static void check(boolean v, String name) {
        checks++;
        if (!v) throw new AssertionError(name);
        System.out.println("PASS discovery: " + name);
    }
    static InetAddress ip(String s) throws Exception { return InetAddress.getByName(s); }
    public static void main(String[] args) throws Exception {
        InetAddress v4 = ip("192.0.2.4"), p4 = ip("192.0.2.1");
        InetAddress v6 = ip("2001:db8::4"), p6 = ip("2001:db8::1");
        check(JoanImsDiscovery.matchesSubscription(new HashSet<>(Arrays.asList(2)), 2), "Set matching sub accepted");
        check(!JoanImsDiscovery.matchesSubscription(new HashSet<>(Arrays.asList(1)), 2), "Set foreign sub rejected");
        check(!JoanImsDiscovery.matchesSubscription(new int[]{1}, 2), "legacy array foreign sub rejected");
        check(JoanImsDiscovery.matchesSubscription(Collections.emptySet(), 2), "redacted metadata distinguished from mismatch");
        check(JoanImsDiscovery.matchesSubscription(null, 2), "absent metadata preserves scoped-request path");
        JoanImsDiscovery.Pcscfs found = JoanImsDiscovery.selectPcscfs(Arrays.asList(p6,p4,p4), "ok", new String[]{"192.0.2.99"});
        check(found.addresses.size() == 2 && found.source.equals("link"), "advertised list wins and deduplicates");
        found = JoanImsDiscovery.selectPcscfs(Collections.emptyList(), "empty", new String[]{"192.0.2.1", "[2001:db8::1]", "192.0.2.1"});
        check(found.addresses.size() == 2 && found.source.equals("isim"), "SIM literal fallback supplies endpoints");
        found = JoanImsDiscovery.selectPcscfs(Collections.emptyList(), "empty", new String[]{"pcscf.example.invalid", "127.0.0.1", "0.0.0.0", "::", "ff02::1", "fe80::1%3", "192.0.2.1\r\nInjected", "1.2.3", "999.1.2.3"});
        check(found.addresses.isEmpty(), "no guessed DNS or malformed/local/multicast endpoints");
        check(!found.summary().contains("example.invalid") && !found.summary().contains("Injected"), "SIM diagnostics never echo source values");
        check(!found.summary().contains("provisioned"), "empty list makes no provisioning verdict");
        found = JoanImsDiscovery.selectPcscfs(null, "denied", null);
        check(found.summary().contains("link_api=denied"), "API failure remains distinct from empty");
        check(JoanImsDiscovery.localFor(Arrays.asList(v6,v4), p4).equals(v4), "peer v4 gets v4 source despite v6 preference");
        check(JoanImsDiscovery.localFor(Arrays.asList(v4), p6) == null, "no cross-family source substitution");
        check(JoanImsDiscovery.localFor(Arrays.asList(ip("::"),ip("fe80::1"),v6),p6).equals(v6), "unusable sources skipped");
        JoanImsDiscovery.Plan primary = JoanImsDiscovery.plan(Arrays.asList(v6,v4),Arrays.asList(p4,p6), false);
        check(primary.local.equals(v6) && primary.peers.equals(Arrays.asList(p6)), "primary v6 only has v6 peers");
        JoanImsDiscovery.Plan fallback = JoanImsDiscovery.plan(Arrays.asList(v6,v4),Arrays.asList(p4,p6), true);
        check(fallback.local.equals(v4) && fallback.peers.equals(Arrays.asList(p4)), "alternate plan actually changes source and peers");
        check(JoanImsDiscovery.plan(Arrays.asList(v6,v4),Arrays.asList(p4),false).local.equals(v4), "v4-only P-CSCF works on dual-stack PDN");
        check(JoanImsDiscovery.plan(Arrays.asList(v4),Arrays.asList(p6),false).local == null, "family mismatch is not ready");
        check(JoanImsDiscovery.plan(Arrays.asList(v4),Arrays.asList(p4),true).local == null, "single family has no flip plan");
        check(JoanImsDiagnostics.protocol(0).equals("IP") && JoanImsDiagnostics.protocol(2).equals("IPV4V6"), "protocol labels match Android constants");
        check(JoanImsDiagnostics.protocol(-999).equals("UNKNOWN"), "invalid protocol is explicit");
        check(JoanImsDiagnostics.apnClass("3gnet").equals("other") && JoanImsDiagnostics.apnClass("ims").equals("ims"), "APN classification not subscriber text");
        check(JoanImsDiagnostics.attemptContextLine().contains("listener=not_started")
                        && JoanImsDiagnostics.attemptContextLine().contains("network={unobserved}")
                        && JoanImsDiagnostics.attemptContextLine().contains("data={unobserved}"),
                "attempt context reprints cached diagnostics even when unobserved");
        check(!JoanImsDiagnostics.attemptContextLine().contains("192.")
                        && !JoanImsDiagnostics.attemptContextLine().contains("pcscf.ims"),
                "attempt context never invents addresses");
        JoanAppRegister.stop();
        JoanImsDiscovery.Plan chosen = JoanAppRegister.selectAttemptPlan(Arrays.asList(v6,v4),Arrays.asList(p6,p4));
        check(chosen.local.equals(v6), "real register planner starts v6");
        // Driver order: discovery notes the PDN's family pair BEFORE the
        // failure scheduler can earn a flip.
        JoanAppRegister.noteLastAttemptDualFamily(true);
        check(JoanAppRegister.scheduleFamilyRetry("reg1_result=timeout FAIL: reg1 no matching final"), "driver failure schedules actual alternate");
        check(JoanAppRegister.flipPending(), "pending retained before REGISTER planning");
        chosen = JoanAppRegister.selectAttemptPlan(Arrays.asList(v6,v4),Arrays.asList(p6,p4));
        check(chosen.local.equals(v4) && chosen.peers.equals(Arrays.asList(p4)), "REGISTER consumer uses alternate family");
        check(!JoanAppRegister.scheduleFamilyRetry("FAIL: reg2 timeout"), "failed alternate does not loop flips forever");
        JoanAppRegister.stop();
        JoanAppRegister.noteLastAttemptDualFamily(true);
        check(JoanAppRegister.scheduleFamilyRetry("FAIL: reg2 timeout"), "driver-order flip scheduled");
        JoanImsDiscovery.Plan readiness = JoanImsDiscovery.plan(
                Arrays.asList(v6, v4), Arrays.asList(p6, p4), false);
        check(readiness.local.equals(v6) && JoanAppRegister.flipPending(),
                "discovery readiness must not consume the REGISTER flip");
        chosen = JoanAppRegister.selectAttemptPlan(Arrays.asList(v6, v4), Arrays.asList(p6, p4));
        check(chosen.local.equals(v4) && chosen.peers.equals(Arrays.asList(p4)),
                "REGISTER must use the alternate after real discovery consumer");
        JoanAppRegister.stop();
        // Fresh state on a single-family PDN: discovery notes no pair, so a
        // timeout can never queue an impossible flip.
        JoanAppRegister.selectAttemptPlan(Arrays.asList(v4),Arrays.asList(p4));
        JoanAppRegister.noteLastAttemptDualFamily(false);
        check(!JoanAppRegister.scheduleFamilyRetry("FAIL: reg2 timeout"), "single-family timeout never queues impossible flip");

        /* A P-CSCF may be NAMED rather than addressed -- the ISIM's
         * EF_PCSCF commonly carries one. Names are kept separately so the
         * registration path can resolve them on the IMS network, while
         * the literal fast path stays exactly as it was. */
        JoanImsDiscovery.Pcscfs named = JoanImsDiscovery.selectPcscfs(
                null, "empty",
                new String[]{"pcscf.ims.mnc002.mcc460.3gppnetwork.org"});
        check(named.addresses.isEmpty() && named.names.size() == 1,
                "a named P-CSCF is captured as a name, not dropped");
        check(named.source.equals("isim-name"), "and the source says so");
        JoanImsDiscovery.Pcscfs lit = JoanImsDiscovery.selectPcscfs(
                null, "empty", new String[]{"192.0.2.99"});
        check(lit.addresses.size() == 1 && lit.names.isEmpty(),
                "a literal is still taken directly, with no name recorded");

        /* The validator decides whether anything is looked up at all, so a
         * false positive means a DNS query for garbage. */
        check(JoanImsDiscovery.isHostname("pcscf.example.com"),
                "a dotted name is a hostname");
        check(!JoanImsDiscovery.isHostname("192.0.2.99"),
                "a dotted quad is NOT a hostname");
        check(!JoanImsDiscovery.isHostname("2001:db8::1"),
                "an IPv6 literal is not a hostname");
        check(!JoanImsDiscovery.isHostname("host.example.com:5060"),
                "a host:port is refused rather than half-parsed");
        check(!JoanImsDiscovery.isHostname("sip://x.example.com"),
                "anything URI-shaped is refused");
        check(!JoanImsDiscovery.isHostname("-bad.example.com")
                        && !JoanImsDiscovery.isHostname("bad-.example.com"),
                "a label may not start or end with a hyphen");
        check(!JoanImsDiscovery.isHostname("nodot"),
                "a single label is not enough");
        check(JoanImsDiscovery.resolveOn(null, "x.example.com", 100).isEmpty(),
                "no network means no lookup and no exception");


        /* EF_PCSCF off the card is the ISIM leg of discovery -- AOSP's
         * third method after PCO and CONFIG. Literals go straight
         * through; names need a network, and without one they are simply
         * not used rather than resolved on the wrong resolver. */
        check(JoanImsDiscovery.fromCardPcscf(
                        Arrays.asList("192.0.2.77"), null).size() == 1,
                "a literal from EF_PCSCF is used with no network needed");
        check(JoanImsDiscovery.fromCardPcscf(
                        Arrays.asList("pcscf.example.com"), null).isEmpty(),
                "a named EF_PCSCF entry is not resolved without a network");
        check(JoanImsDiscovery.fromCardPcscf(
                        Arrays.asList("not a host", "::", "0.0.0.0"),
                        null).isEmpty(),
                "junk from the card is dropped, never guessed at");
        check(JoanImsDiscovery.fromCardPcscf(null, null).isEmpty(),
                "no EF_PCSCF entries yields nothing and does not throw");

        /* A carrier profile answers for all of its own knobs.
         *
         * Every setting below is a bare static except the transport
         * criterion, which is PLMN-scoped -- and the criterion's gate
         * used to stand in front of all of them, so a profile with no
         * criterion (the 3GPP defaults) applied nothing and the previous
         * network's decisions stayed in force. Put a carrier's settings
         * in place, then apply a profile that carries no criterion: the
         * defaults must land, not the leftovers. */
        JoanSipBuilder.setSendUserAgent(false);
        JoanSipCrypto.setOfferMask(0x70003);
        JoanSipBuilder.setSendAuthAlgorithm(false);
        JoanSipBuilder.setCarrierPcscfPort(5070);
        JoanSipBuilder.setCarrierRegisterExpires(3600);
        JoanCarrierProfile none = JoanCarrierProfile.defaults("460", "02");
        check(none.tcpCriterionLen < 0, "the 3GPP defaults carry no criterion");
        JoanDriver.applyCarrierProfile(none, "460", "02", 0);
        check(JoanSipBuilder.sendUserAgent(),
                "a profile with no criterion still sets the User-Agent policy");
        check(JoanSipCrypto.offerMask() == -1,
                "and the sec-agree offer mask, not the last carrier's");
        check(JoanSipBuilder.sendAuthAlgorithm(),
                "and the algorithm parameter");
        check(JoanSipBuilder.pcscfSipPort() == 5060,
                "and the P-CSCF port returns to the default");
        check(JoanSipBuilder.registerExpires() == 600000,
                "and the registration expiry");

        /* The platform's own value still outranks the vendor snapshot. */
        JoanDriver.applyCarrierProfile(none, "460", "02", 7200);
        check(JoanSipBuilder.registerExpires() == 7200,
                "CarrierConfig expiry wins over the profile's");

        /* No PLMN at all: the settings still apply, from the defaults,
         * rather than being inherited from whoever registered last. */
        JoanSipBuilder.setSendUserAgent(false);
        JoanDriver.applyCarrierProfile(JoanCarrierProfile.defaults(null, null),
                null, null, 0);
        check(JoanSipBuilder.sendUserAgent(),
                "an unknown PLMN applies defaults instead of inheriting");
        JoanDriver.applyCarrierProfile(null, "460", "02", 0);
        check(JoanSipBuilder.sendUserAgent(),
                "a missing profile changes nothing and does not throw");

        /* And the REGISTER waits for the PLMN before any of that runs --
         * bounded, because a card that never publishes one still has an
         * ISIM identity to register with. */
        long t0 = 1_000_000L;
        check(JoanAppRegister.JoanRegLifecycle.holdForPlmn(t0, t0),
                "the first pass with no PLMN holds the REGISTER");
        check(JoanAppRegister.JoanRegLifecycle.holdForPlmn(t0, t0 + 29_000L),
                "still held just inside the backstop");
        check(!JoanAppRegister.JoanRegLifecycle.holdForPlmn(
                        t0, t0 + JoanAppRegister.JoanRegLifecycle
                                .PLMN_WAIT_BACKSTOP_MS),
                "the hold expires rather than blocking registration forever");
        check(!JoanAppRegister.JoanRegLifecycle.holdForPlmn(0, t0),
                "no wait recorded is not a hold");

        /* A Retry-After is a deadline, not the length of one sleep. The
         * CMCC tester's 2026-09-19 log is the case: the network asked for
         * 513s, its own IMS PDN dropped and came back 89s later, and the
         * availability poke put a fresh REGISTER on the wire -- over and
         * over, for the whole log. */
        long now = 5_000_000L;
        check(JoanAppRegister.JoanRegLifecycle.retryHoldRemainingMs(
                        now + 513_000L, now) == 513_000L,
                "a named Retry-After still has its whole wait to run");
        check(JoanAppRegister.JoanRegLifecycle.retryHoldRemainingMs(
                        now + 513_000L, now + 89_000L) == 424_000L,
                "and being woken partway through leaves the rest, not zero");
        check(JoanAppRegister.JoanRegLifecycle.retryHoldRemainingMs(
                        now + 513_000L, now + 513_000L) == 0L,
                "the hold ends exactly when the network said it could");
        check(JoanAppRegister.JoanRegLifecycle.retryHoldRemainingMs(0L, now)
                        == 0L,
                "no hold recorded never delays a REGISTER");
        check(JoanAppRegister.JoanRegLifecycle.retryHoldGoverns("46002",
                        "46002"),
                "a hold governs the network that named it");
        check(!JoanAppRegister.JoanRegLifecycle.retryHoldGoverns("46002",
                        "46011"),
                "and not the other SIM in the same slot");
        check(!JoanAppRegister.JoanRegLifecycle.retryHoldGoverns(null,
                        "46002"),
                "an unrecorded PLMN holds nothing");
        check(!JoanAppRegister.JoanRegLifecycle.retryHoldGoverns("46002",
                        null),
                "and neither does an unknown current PLMN");

        System.out.println("DISCOVERY_CHECKS=" + checks + " FAILURES=0");
    }
}
