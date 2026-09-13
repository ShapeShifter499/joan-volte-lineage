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
        System.out.println("DISCOVERY_CHECKS=" + checks + " FAILURES=0");
    }
}
