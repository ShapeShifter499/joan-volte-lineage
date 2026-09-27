package org.joan.ims;

/**
 * Host tests for the default-on VoLTE admit gate. Runs against the
 * android.jar stub; only the pure decision is exercised here (the
 * overrideConfig binder call needs a live phone process).
 *
 * Semantics under test (framework-verified 2026-09-13):
 *   dial gate = carrier_volte_available_bool AND user toggle;
 *   user toggle defaults ON (enhanced_4g_lte_on_by_default=true);
 *   opt-out = stock Settings toggle, honored by isImsUseEnabled().
 */
public final class TestJoanVolteCarrierGate {
    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (ok) {
            System.out.println("ok   " + what);
        } else {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        // config not applied yet -> wait, never force during early boot
        check(JoanVolteCarrierGate.decide(false, false, true)
                == JoanVolteCarrierGate.WAIT_CONFIG,
                "config-not-applied waits (voLTE false)");
        check(JoanVolteCarrierGate.decide(true, false, true)
                == JoanVolteCarrierGate.WAIT_CONFIG,
                "config-not-applied waits (voLTE true)");
        check(JoanVolteCarrierGate.decide(true, false, false)
                == JoanVolteCarrierGate.WAIT_CONFIG,
                "config-not-applied waits (toggle hidden)");

        // already available + toggle usable -> skip entirely
        check(JoanVolteCarrierGate.decide(true, true, true)
                == JoanVolteCarrierGate.SKIP_ALREADY,
                "already-true with usable toggle skips");

        // already available but toggle hidden/locked -> visibility-only
        check(JoanVolteCarrierGate.decide(true, true, false)
                == JoanVolteCarrierGate.APPLY_VISIBILITY,
                "already-true with hidden toggle forces visibility");

        // not available -> full admit (the default-on path)
        check(JoanVolteCarrierGate.decide(false, true, true)
                == JoanVolteCarrierGate.APPLY_FULL,
                "unconfigured carrier gets full admit");
        check(JoanVolteCarrierGate.decide(false, true, false)
                == JoanVolteCarrierGate.APPLY_FULL,
                "unconfigured carrier with hidden toggle gets full admit");

        // --- state row: SKIP_ALREADY is ambiguous without the forced flag.
        // The override lives in com.android.phone, so it can vanish while
        // this process keeps running; the row has to tell the two apart.
        check("skip:already-true".equals(
                JoanVolteCarrierGate.kindFor(
                        JoanVolteCarrierGate.SKIP_ALREADY, false)),
                "already-true we did NOT force reads as skip");
        check("applied".equals(
                JoanVolteCarrierGate.kindFor(
                        JoanVolteCarrierGate.SKIP_ALREADY, true)),
                "already-true we DID force still reads as applied");
        check("applied".equals(
                JoanVolteCarrierGate.kindFor(
                        JoanVolteCarrierGate.APPLY_FULL, true)),
                "full admit reads as applied");
        check("applied-visibility".equals(
                JoanVolteCarrierGate.kindFor(
                        JoanVolteCarrierGate.APPLY_VISIBILITY, true)),
                "visibility-only admit is labelled separately");

        check("applied cid=1".equals(
                JoanVolteCarrierGate.stateLabel("applied", 1, 0)),
                "first apply carries no reapply count");
        check("applied cid=1 reapplied=2".equals(
                JoanVolteCarrierGate.stateLabel("applied", 1, 2)),
                "a re-applied override is visible in the row");
        check("skip:already-true cid=-1".equals(
                JoanVolteCarrierGate.stateLabel("skip:already-true", -1, 0)),
                "unknown carrier id still renders");

        System.out.println((failures == 0 ? "ok   " : "FAIL ")
                + "volte gate decision tests (" + checks + " checks)");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
