package org.joan.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registration driver: watches the IMS network, checks that the radio,
 * SIM and IMS PDN are all ready, and then runs the two-stage REGISTER in
 * JoanAppRegister, refreshing it before the registrar's grant lapses.
 *
 * Important battery/user-intent rule: if cellular/LTE is intentionally off
 * (airplane mode, no active SIM/subscription, preferred network mode excludes
 * LTE/NR), this driver enters quiet idle. It must never spin AKA, SIP, xfrm,
 * or framework retries just because the user turned the radio state off.
 */
final class JoanDriver {
    private static final String TAG = "JoanIms";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Thread sThread;

    /** Normal wait while radio/framework are still bringing IMS up. */
    private static final long WAIT_MS = 60_000L;
    /** Quiet user-off state: don't keep poking radio/SIM every minute. */
    private static final long USER_OFF_IDLE_MS = 10 * 60_000L;
    /** When a loss was deferred because a call was up; 0 when not. */
    private static volatile long sLostDuringCallAtMs;

    /** Failed REGISTER backoff range. */
    private static final long REG_RETRY_MIN_MS = 60_000L;
    private static final long REG_RETRY_MAX_MS = 15 * 60_000L;
    /* Ceiling on a network-supplied Retry-After. The header is honoured
     * as given below this; the clamp only stops a malformed or hostile
     * value from parking registration for a day. */
    private static final long REG_RETRY_RETRY_AFTER_MAX_MS = 30 * 60_000L;

    private static String sLastState = "";
    /* The full REGISTER summary, surfaced by JoanStateProvider. The trace
     * file needs root and logcat gets lost; the content provider is the
     * artifact a user can actually produce, so the line that says why
     * registration failed belongs in it. */
    private static volatile String sLastRegister = "";
    /** Current failed-REGISTER backoff; static so wake-ups can reset it. */
    private static volatile long sRegisterBackoffMs = REG_RETRY_MIN_MS;
    /** A PDN loss was observed; the next availability poke must not trust
     * the current registration state (see JoanRegLifecycle.reacquireAfterLoss). */
    private static volatile boolean sStaleAfterLoss;
    private static final Object NET_LOCK = new Object();
    private static ConnectivityManager.NetworkCallback sImsCallback;
    private static boolean sImsRequested;

    /**
     * One entry point for every wake-up: network callbacks (routine) and
     * boot/receiver pokes (manual). Rules, per {@link JoanRegLifecycle}:
     * <ul>
     *   <li>never interrupt an in-flight REGISTER cycle;</li>
     *   <li>routine "available": a registered driver is left alone (the
     *       refresh sleep self-corrects); an unregistered one gets its
     *       backoff reset and wakes for a prompt attempt;</li>
     *   <li>routine "lost": clear the stale UA registration and supersede
     *       in-flight work, reset backoff, wake;</li>
     *   <li>manual pokes (boot, package-replaced, airplane): wake only
     *       when not registered.</li>
     * </ul>
     * A healthy registration is never torn down by a routine poke.
     */
    static void poke(String reason) {
        boolean lost = JoanAppRegister.JoanRegLifecycle
                .POKE_IMS_LOST.equals(reason);
        if (lost) {
            /* A loss invalidates any binding the driver might still
             * believe in -- including one adopted by an attempt that is
             * just finishing -- so remember it and supersede in-flight
             * work; the next availability poke forces a fresh REGISTER. */
            sStaleAfterLoss = true;
            JoanAppRegister.stop();
            sRegisterBackoffMs = REG_RETRY_MIN_MS;
        }
        if (JoanAppRegister.inProgress()) {
            return;
        }
        boolean ua = JoanSipUa.isRegistered();
        if (JoanAppRegister.JoanRegLifecycle
                .reregisterOnIpChange(reason, ua)) {
            /* Everything is bound to an address the link no longer has:
             * the SIP sockets, both IPsec SAs, and the RTP socket. A
             * refresh on the old address cannot leave, so re-register
             * from scratch. adopt() keeps any live dialog and then moves
             * its media to the new address; nothing here has to know
             * whether a call is up. */
            sStaleAfterLoss = false;
            sRegisterBackoffMs = REG_RETRY_MIN_MS;
            wake();
            JoanSipUa.release();
            JoanTrace.note("IMS local address changed; re-registering");
            return;
        }
        if (JoanAppRegister.JoanRegLifecycle.routinePoke(reason)) {
            if (JoanAppRegister.JoanRegLifecycle
                    .reacquireAfterLoss(reason, sStaleAfterLoss)) {
                sStaleAfterLoss = false;
                /* Wake first so a hiccup in the release/broadcast path
                 * can never leave the driver sleeping on stale state. */
                sRegisterBackoffMs = REG_RETRY_MIN_MS;
                wake();
                if (ua) {
                    /* The binding predates the loss: re-register fresh
                     * instead of sleeping to refresh on stale state. */
                    JoanSipUa.release();
                    JoanTrace.note("IMS network back after loss; "
                            + "re-registering");
                }
                sLostDuringCallAtMs = 0;
                return;
            }
            if (JoanAppRegister.JoanRegLifecycle.deferClearForCall(
                    reason, ua, JoanSipUa.callActive())) {
                sLostDuringCallAtMs = System.currentTimeMillis();
                JoanTrace.note("IMS network lost during a call; held until "
                        + "the call ends");
                wake();
            } else if (JoanAppRegister.JoanRegLifecycle.clearOnLost(
                    reason, ua, JoanSipUa.callActive())) {
                /* Wake first so a hiccup in the release/broadcast path can
                 * never leave the driver sleeping on stale state. */
                wake();
                JoanSipUa.release();
                JoanTrace.note("IMS network lost; cleared stale registration");
            } else if (!ua) {
                sRegisterBackoffMs = REG_RETRY_MIN_MS;
                wake();
            }
            return;
        }
        if (JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE.equals(reason)) {
            /* The network came back inside the grace period: the call rode
             * out the gap and nothing needs tearing down. */
            if (sLostDuringCallAtMs != 0) {
                JoanTrace.note("IMS network back within the call grace period");
                sLostDuringCallAtMs = 0;
            }
        }
        /* Everything above this point returned. What is left is a user
         * or radio state change -- boot, a package replacement, an
         * airplane-mode toggle -- which is explicit intent to try again,
         * and the one thing allowed to discard a wait the network asked
         * for. Routine network chatter is not. */
        sRetryNotBeforeMs = 0L;
        sRetryPlmn = null;
        if (!ua) {
            sRegisterBackoffMs = REG_RETRY_MIN_MS;
            wake();
        }
    }

    private static void wake() {
        Thread t = sThread;
        if (t != null) {
            t.interrupt();
        }
    }

    static void start(Context ctx) {
        JoanTrace.init(ctx.getApplicationContext());
        if (!STARTED.compareAndSet(false, true)) {
            /* Boot/replaced/airplane receivers re-call start() on every
             * lifecycle event. Do not treat that as permission to interrupt
             * a registered sleep or an in-flight cycle: route through the
             * wake rules instead of waking unconditionally. */
            poke("manual poke");
            return;
        }
        /* Watch carrier config reloads so a com.android.phone restart
         * cannot silently drop the VoLTE admit until the next REGISTER
         * refresh, which can be half an hour out. */
        JoanVolteCarrierGate.watch(ctx.getApplicationContext());
        JoanTrace.note("starting registration driver");
        Thread t = new Thread(() -> loop(ctx.getApplicationContext()),
                "joan-ims-cycle");
        t.setPriority(Thread.MIN_PRIORITY);
        sThread = t;
        t.start();
    }

    static boolean isRunning() {
        return STARTED.get();
    }

    static String lastState() {
        return sLastState;
    }

    static String lastRegister() {
        return sLastRegister;
    }

    static boolean imsRequested() {
        synchronized (NET_LOCK) {
            return sImsRequested;
        }
    }

    private static void loop(Context app) {
        while (true) {
            try {
                /* A loss deferred for a live call has to expire, or a call
                 * the network has actually dropped stays "up" forever. */
                long lostAt = sLostDuringCallAtMs;
                if (lostAt != 0
                        && JoanAppRegister.JoanRegLifecycle.heldLossMayClear(
                                lostAt, System.currentTimeMillis(),
                                JoanSipUa.callActive())) {
                    sLostDuringCallAtMs = 0;
                    JoanTrace.note("IMS loss held for a call is now honoured; "
                            + "releasing");
                    JoanSipUa.release();
                }
                Discovery d = discover(app);
                if (d.cycle == null) {
                    JoanRegistration.setRegistered(false, null);
                    if (d.quietIdle) {
                        releaseImsRequest(app);
                        JoanImsDiagnostics.stop();
                    }
                    logState((d.quietIdle ? "quiet-idle: " : "waiting: ")
                            + d.reason);
                    sRegisterBackoffMs = REG_RETRY_MIN_MS;
                    Thread.sleep(d.sleepMs);
                    continue;
                }

                Cycle c = d.cycle;
                boolean refreshing = JoanSipUa.isRegistered();
                if (refreshing) {
                    long wait = JoanSipUa.msUntilRefresh();
                    if (wait > 0) {
                        /* Re-assert what the UA already knows. The flag
                         * above is the one Android reads, and it is
                         * cleared whenever discovery comes back empty --
                         * including for reasons that are not an IMS loss,
                         * such as a momentarily empty P-CSCF list, which
                         * do not release the UA. Without this line the
                         * loop then sleeps here on every pass, the UA
                         * stays happily bound, and the published flag
                         * stays false forever: the log reads "registered
                         * via app UA" while telephony never learns voice
                         * is usable and the dialer falls back to CS.
                         *
                         * That is issue #1's signature exactly. The
                         * airplane-mode route into it was closed in
                         * 086bd14 by releasing the UA on an honoured
                         * loss, and a bench airplane cycle on alpha38 no
                         * longer reproduces it -- but that fixed the
                         * trigger, not the gap. setRegistered is a no-op
                         * when the value is unchanged, so this costs
                         * nothing on the common path. */
                        JoanRegistration.setRegistered(true, c.pcscf);
                        logState("registered via app UA; refresh in "
                                + (wait / 60_000L) + "m");
                        Thread.sleep(wait);
                        continue;
                    }
                }
                logState(refreshing ? "refreshing REGISTER via app UA"
                        : "attempt REGISTER via app UA");
                String r = JoanSipUa.register(app);
                sLastRegister = (r == null) ? "null" : r;
                boolean ok = r != null && r.contains("reg2=200");
                JoanTrace.note("app register: "
                        + (r == null ? "null" : r));
                if (ok && JoanSipUa.isRegistered()) {
                    JoanRegistration.setRegistered(true, c.pcscf);
                    sRegisterBackoffMs = REG_RETRY_MIN_MS;
                    sRetryNotBeforeMs = 0L;
                    sRetryPlmn = null;
                    /* The next pass reads the granted lifetime and
                     * sleeps until the refresh is due. */
                    continue;
                }
                if (refreshing) {
                    /* A failed refresh leaves the old binding in place
                     * but unrenewed, and isRegistered() would send us
                     * straight back to sleep instead of retrying. */
                    JoanSipUa.release();
                }
                JoanRegistration.setRegistered(false, null);
                /* Stock parity (alpha12, repaired alpha13): a whole-cycle
                 * failure earns one address-family flip retry when the PDN
                 * actually has a usable pair of the other family — the
                 * decision runs at REGISTER time against the real locals
                 * and peers. v4-only or P-CSCF-less PDNs never flip. */
                if (JoanAppRegister.scheduleFamilyRetry(sLastRegister)) {
                    logState("registration failed; one IP-version flip "
                            + "retry due (stock parity)");
                }
                /* A Retry-After on the rejection outranks our own
                 * backoff. Both reference stacks treat it as the
                 * governing delay -- AOSP's flow recovery branches on
                 * it citing IR.92, LG's China Mobile override reads it
                 * first and computes a wait only when it is absent --
                 * and RFC 3261 10.3 asks the same. joan honoured it on
                 * a 503 to an INVITE and nowhere else, so a network
                 * that said "wait an hour" was retried in a minute,
                 * which is how a client earns a refusal.
                 *
                 * The doubling is left alone on such a cycle: the
                 * network named the interval, so there is nothing for
                 * an exponential to discover. */
                long asked = JoanAppRegister.retryAfterMs(sLastRegister);
                long waitMs = sRegisterBackoffMs;
                if (asked > 0L) {
                    waitMs = Math.min(asked, REG_RETRY_RETRY_AFTER_MAX_MS);
                    sRetryNotBeforeMs = System.currentTimeMillis() + waitMs;
                    sRetryPlmn = sPlmn;
                    logState("app REGISTER failed; network asked for "
                            + (waitMs / 1000) + "s");
                } else {
                    logState("app REGISTER failed; backoff "
                            + (sRegisterBackoffMs / 1000) + "s");
                    sRegisterBackoffMs = Math.min(REG_RETRY_MAX_MS,
                            sRegisterBackoffMs * 2);
                }
                Thread.sleep(waitMs);
                continue;
            } catch (InterruptedException ie) {
                // A receiver/provider/service poke woke us after a user/radio
                // state change. Re-run discovery immediately instead of
                // staying in a stale quiet-idle sleep.
                continue;
            } catch (Throwable t) {
                JoanRegistration.setRegistered(false, null);
                Log.e(TAG, "loop error " + t.getClass().getSimpleName());
                try {
                    Thread.sleep(WAIT_MS);
                } catch (InterruptedException ie) {
                    continue;
                }
            }
        }
    }

    private static void logState(String state) {
        if (!state.equals(sLastState)) {
            JoanTrace.note(state);
            sLastState = state;
        }
    }

    /**
     * What discovery proved is ready. JoanAppRegister reads the IMS network
     * and the ISIM itself; the only field anyone still consumes is the
     * P-CSCF list, which JoanRegistration reports as registration state.
     */
    private static final class Cycle {
        String pcscf;
    }

    private static final class Discovery {
        final Cycle cycle;
        final String reason;
        final boolean quietIdle;
        final long sleepMs;

        private Discovery(Cycle c, String r, boolean q, long ms) {
            cycle = c;
            reason = r;
            quietIdle = q;
            sleepMs = ms;
        }

        static Discovery ready(Cycle c) {
            return new Discovery(c, "ready", false, 0);
        }

        static Discovery waitFor(String reason) {
            return new Discovery(null, reason, false, WAIT_MS);
        }

        static Discovery quietIdle(String reason) {
            return new Discovery(null, reason, true, USER_OFF_IDLE_MS);
        }

        /** Not ready by our own choice, for exactly as long as asked. */
        static Discovery holdFor(String reason, long ms) {
            return new Discovery(null, reason, false, ms);
        }
    }

    @SuppressWarnings("unchecked")
    private static Discovery discover(Context app) throws Exception {
        TelephonyManager tm0 = app.getSystemService(TelephonyManager.class);
        if (tm0 == null) {
            return Discovery.waitFor("telephony service unavailable");
        }

        if (airplaneModeOn(app)) {
            return Discovery.quietIdle("airplane mode is on");
        }

        int sub = defaultOrActiveSubscriptionId(app);
        if (sub < 0) {
            return Discovery.quietIdle("no active default subscription");
        }

        TelephonyManager tm = tm0.createForSubscriptionId(sub);
        int simState = safeSimState(tm);
        if (simState != TelephonyManager.SIM_STATE_READY) {
            return Discovery.quietIdle("SIM not ready (state=" + simState
                    + ")");
        }
        JoanVolteCarrierGate.applyIfNeeded(app, sub, tm);
        applySessionTimerConfig(app, sub);
        JoanImsDiagnostics.start(app, sub);

        Integer preferredMode = preferredNetworkMode(app, sub);
        if (preferredMode != null && !networkModeAllowsLte(preferredMode)) {
            return Discovery.quietIdle("preferred network mode disables LTE ("
                    + preferredMode + ")");
        }

        ConnectivityManager cm = app.getSystemService(
                ConnectivityManager.class);
        if (cm == null) {
            return Discovery.waitFor("connectivity service unavailable");
        }

        // If the modem is not camped on LTE/NR and no IMS network is exposed,
        // do not fetch ISIM identity or run AKA yet. This covers the user's
        // "LTE off after reboot" case gracefully.
        /* The RAT check is applied further down, only when no usable IMS
         * network was found. It exists to stop this spinning AKA and xfrm
         * when the user has turned the radio down, not to veto a PDN that
         * is demonstrably up: getDataNetworkType() reports the default
         * data bearer, which on a marginal cell flaps to HSPA while the
         * IMS PDN is still there. Checking it first meant alternating
         * between "not LTE/NR (15)" and a working network, forever. */
        int dataNetwork = safeDataNetworkType(tm);

        Network ims = null;
        LinkProperties imsLp = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null
                    || !cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                continue;
            }
            boolean isIms = cap.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_IMS);
            if (!isIms) {
                try {
                    Object spec = cap.getNetworkSpecifier();
                    String s = spec == null ? "" : spec.toString();
                    isIms = s.toLowerCase(Locale.US).contains("ims");
                } catch (Exception ignore) {
                    // no specifier access; capability check above stands
                }
            }
            if (!isIms) {
                continue;
            }
            /* Pin the network to the subscription identity/AKA will run
             * against: a second SIM's IMS PDN must never win. */
            if (!JoanAppRegister.networkSubsMatch(cap, sub)) {
                continue;
            }
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) {
                continue;
            }
            ims = n;
            imsLp = lp;
            break;
        }

        if (ims == null || imsLp == null) {
            JoanImsDiagnostics.networkGone();
            if (dataNetwork != TelephonyManager.NETWORK_TYPE_UNKNOWN
                    && !isLteLike(dataNetwork)) {
                return Discovery.waitFor(
                        "no IMS network and data is not LTE/NR ("
                                + dataNetwork + ")");
            }
            ensureImsRequest(cm);
            return Discovery.waitFor("LTE on, IMS APN/network requested; waiting");
        }
        /* Keep the callback even when the PDN is already present: a later
         * loss must be observed so the stale binding gets cleared. */
        ensureImsRequest(cm);

        /* Discovery evidence: usable locals, every advertised/SIM P-CSCF,
         * and an event-driven data-call line (configured vs negotiated
         * families) — one trace cycle answers the class-1b questions. */
        List<InetAddress> locals = JoanImsDiscovery.locals(imsLp);
        JoanImsDiscovery.Pcscfs pcscfInfo = JoanImsDiscovery.read(imsLp, tm);
        /* Readiness only. The flip flag is consumed at REGISTER time by
         * selectAttemptPlan so a discovery pass cannot steal it. */
        JoanImsDiscovery.Plan attempt = JoanImsDiscovery.plan(
                locals, pcscfInfo.addresses, false);
        JoanImsDiscovery.Plan alternate = JoanImsDiscovery.plan(
                locals, pcscfInfo.addresses, true);
        JoanAppRegister.noteLastAttemptDualFamily(alternate.local != null);
        JoanImsDiagnostics.network(sub, safeSimOperator(tm), imsLp,
                pcscfInfo);
        if (attempt.local == null || attempt.peers.isEmpty()) {
            return Discovery.waitFor("no usable P-CSCF/local pair ("
                    + pcscfInfo.summary() + ")");
        }
        StringBuilder pcscf = new StringBuilder();
        for (InetAddress a : attempt.peers) {
            if (pcscf.length() > 0) {
                pcscf.append(',');
            }
            String host = a.getHostAddress();
            int scope = host.indexOf('%');
            pcscf.append(scope >= 0 ? host.substring(0, scope) : host);
        }
        // Only after radio+IMS prerequisites are met do we ask for identity.
        String domain = hiddenString(tm, "getIsimDomain");
        String impi = hiddenString(tm, "getIsimImpi");
        String idSource = "isim";
        {
            String mccMnc = safeSimOperator(tm);
            String mcc = null;
            String mnc = null;
            if (mccMnc != null && mccMnc.length() >= 5) {
                mcc = mccMnc.substring(0, 3);
                mnc = mccMnc.substring(3);
            }
            if (mcc == null) {
                /* The SIM is READY but has not published its operator
                 * yet. Going on from here is not "registering without a
                 * profile": every carrier decision below is a bare
                 * static, so the REGISTER leaves on whatever the last
                 * network put there, or on our compiled-in defaults --
                 * which is how a carrier whose profile asks for no
                 * User-Agent can be sent one on the first attempt after
                 * a boot. Hold the attempt instead; the PLMN lands
                 * within a pass or two and the loop comes straight back.
                 *
                 * The hold has a backstop because a card that never
                 * publishes an operator must still be able to register
                 * off its ISIM identity -- that path exists and works.
                 * Past the backstop we go on, with the 3GPP defaults
                 * applied explicitly rather than inherited. */
                long now = System.currentTimeMillis();
                if (sPlmnUnknownSinceMs == 0) {
                    sPlmnUnknownSinceMs = now;
                }
                if (JoanAppRegister.JoanRegLifecycle.holdForPlmn(
                        sPlmnUnknownSinceMs, now)) {
                    return Discovery.waitFor("SIM operator not published "
                            + "yet; holding REGISTER for the carrier "
                            + "profile");
                }
            } else {
                sPlmnUnknownSinceMs = 0;
                JoanRegistration.setOperator(mcc, mnc);
            }
            sPlmn = (mcc == null) ? null : mcc + mnc;
            /* Withheld from nobody.
             *
             * alpha28 briefly withheld the MMTEL tags from China
             * Mobile, reasoning that AOSP takes them from carrier
             * configuration so "none" is a shape it already has. LG's
             * shipping CMCC configuration says otherwise: its Contact
             * template is literally
             *   ;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel
             * and its header_info_feature_tags is 0x03000208 against
             * T-Mobile's 0x01000208 -- China Mobile gets MORE feature
             * tags than a network we already work on, not fewer. The
             * switch stays because it is the right shape and the
             * diagnostic uses it; the CMCC scoping was wrong. */
            boolean tags = true;
            /* Push the carrier's own transport criterion into the SIP
             * builder. 164 carrier profiles were distilled from stock
             * configuration and then read by nothing but the conference
             * path; the registration transport decision was a separate
             * hardcoded table that disagreed with them. */
            JoanCarrierProfile cp =
                    JoanCarrierProfile.forNetwork(app, mcc, mnc);
            /* Platform first, vendor snapshot second. A CarrierConfig
             * update ships without us and must win; the distilled
             * profile only answers where the platform is silent. */
            JoanImsVoiceConfig pv = JoanImsVoiceConfig.forSub(app, sub);
            JoanSipBuilder.setPlatformSipMtu(pv.sipMtuV4, pv.sipMtuV6);
            /* ims.sip_preferred_transport_int: tier 1, and the gate AOSP
             * puts in front of the whole length-criterion calculation. */
            JoanSipBuilder.setPlatformPreferredTransport(
                    pv.preferredTransport);
            String cs = applyCarrierProfile(cp, mcc, mnc, pv.regExpirySec);
            if (cs != null && !cs.equals(sCarrierSummary)) {
                sCarrierSummary = cs;
                JoanTrace.note("carrier profile " + cs);
            }
            if (tags != JoanSipBuilder.registerContactTags()) {
                JoanSipBuilder.setRegisterContactTags(tags);
                JoanTrace.note("register contact tags="
                        + (tags ? "mmtel" : "none (CMCC)"));
            }
            /* A wait the network asked for outranks anything that wakes
             * us. The Retry-After was already honoured against our own
             * backoff, but only there: any poke interrupts that sleep,
             * and the loop's next pass registered immediately. On a PDN
             * that flaps -- which is what a network refusing to register
             * us tends to produce -- "wait 619s" became a REGISTER 69s
             * later, over and over, which is how a client earns a
             * refusal rather than recovers from one.
             *
             * Checked here, above identity, so a held pass does not read
             * the card either: every attempt runs AKA, and on a core
             * complaining about AKA synchronisation, burning
             * authentication vectors while under a hold is the last
             * thing to do. */
            long holdLeft = JoanAppRegister.JoanRegLifecycle
                    .retryHoldRemainingMs(sRetryNotBeforeMs,
                            System.currentTimeMillis());
            if (holdLeft > 0 && JoanAppRegister.JoanRegLifecycle
                    .retryHoldGoverns(sRetryPlmn, sPlmn)) {
                return Discovery.holdFor("network asked to wait; "
                        + (holdLeft / 1000) + "s left", holdLeft);
            }
        }
        if (impi == null || !impi.contains("@")) {
            /* No ISIM on the card. TS 23.003 13.3 derives the private
             * identity and home domain from the IMSI, which is what a
             * handset does with a USIM-only card. Requiring an ISIM was
             * why such cards stopped here with "no ISIM IMPI". */
            String mccMnc = safeSimOperator(tm);
            impi = JoanSipBuilder.derivedImpi(
                    hiddenString(tm, "getSubscriberId"), mccMnc);
            if (domain == null || domain.isEmpty()) {
                domain = JoanSipBuilder.derivedDomain(mccMnc);
            }
            idSource = "derived";
        }
        if (impi == null || !impi.contains("@")) {
            return Discovery.waitFor(
                    "no ISIM IMPI, and none derivable from the IMSI");
        }
        logState("identity=" + idSource);

        String realm = (domain != null && !domain.isEmpty())
                ? domain : realmFromImpi(impi);
        if (realm == null || realm.isEmpty()) {
            return Discovery.waitFor("no IMS realm derivable from SIM");
        }
        Cycle c = new Cycle();
        c.pcscf = pcscf.toString();
        return Discovery.ready(c);
    }

    private static boolean airplaneModeOn(Context app) {
        try {
            return Settings.Global.getInt(app.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** MCC+MNC as reported by the SIM, or null. Not an identity. */
    private static String safeSimOperator(TelephonyManager tm) {
        try {
            String s = tm.getSimOperator();
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeSimState(TelephonyManager tm) {
        try {
            return tm.getSimState();
        } catch (Throwable t) {
            return TelephonyManager.SIM_STATE_UNKNOWN;
        }
    }

    /**
     * A Retry-After the network named: the earliest the next REGISTER may
     * go, and the PLMN it was said on. Scoped to the PLMN so it cannot
     * follow a SIM swap onto a network that never asked for anything.
     */
    private static volatile long sRetryNotBeforeMs;
    private static volatile String sRetryPlmn;
    /** The PLMN this pass is registering on, or null when unknown. */
    private static volatile String sPlmn;

    /** Last session-timer summary, so the trace says it once per change. */
    private static volatile String sSeSummary;
    private static volatile String sCarrierSummary;

    /**
     * When the SIM went READY without publishing an operator, so the hold
     * on the first REGISTER can expire rather than last forever.
     */
    private static volatile long sPlmnUnknownSinceMs;

    /**
     * Push a carrier profile's registration decisions into the SIP
     * builder.
     *
     * <p>Only the transport criterion is scoped to the PLMN it came from
     * -- {@link JoanSipBuilder#setCarrierTransport} records the MCC/MNC
     * and the length calculation refuses a criterion belonging to another
     * network. Everything else here is a bare static that simply stays
     * where it was last put: the User-Agent policy, the sec-agree offer
     * mask, the {@code algorithm} parameter and the P-CSCF port.
     *
     * <p>They used to be applied only inside the criterion's own gate, so
     * a profile that carried no criterion applied none of its other
     * knobs, and a network without a profile at all -- {@link
     * JoanCarrierProfile#defaults}, criterion -1 -- silently kept the
     * PREVIOUS carrier's. The scoped setting was guarding the unscoped
     * ones, which is exactly backwards. A profile now answers for all of
     * its own knobs; the criterion alone still needs a numeric PLMN and a
     * value to apply.
     *
     * <p>No Context, and the trace is the caller's: the platform's own
     * values are read by the caller and passed in, and the summary is
     * returned rather than logged, which keeps this callable from the
     * host suite.
     */
    static String applyCarrierProfile(JoanCarrierProfile cp, String mcc,
                                      String mnc, int platformExpirySec) {
        if (cp == null) {
            return null;
        }
        boolean criterion = false;
        if (cp.tcpCriterionLen >= 0 && mcc != null && mnc != null) {
            try {
                JoanSipBuilder.setCarrierTransport(
                        Integer.parseInt(mcc),
                        Integer.parseInt(mnc),
                        cp.tcpCriterionLen,
                        cp.tcpCriterionV4,
                        cp.tcpCriterionV6);
                criterion = true;
            } catch (NumberFormatException e) {
                /* A PLMN that is not numeric is not a PLMN. */
            }
        }
        JoanSipBuilder.setCarrierPcscfPort(cp.pcscfPort);
        JoanSipBuilder.setSendUserAgent(cp.sendUserAgent);
        /* The carrier's own sec-agree algorithm set: it shapes the
         * Security-Client offer and what a Security-Server row must match
         * to be selected -- the reference stack's
         * ChoosePreferredSecurityServer, fed from config it always had.
         * -1 keeps the full offer for any carrier without a profile, so
         * nothing already registering changes what it offers. */
        JoanSipCrypto.setOfferMask(cp.ipsecAlgs);
        JoanSipBuilder.setSendAuthAlgorithm(cp.sendAuthAlgorithm);
        JoanSipBuilder.setCarrierRegisterExpires(
                platformExpirySec > 0 ? platformExpirySec
                        : cp.regExpiration);
        String cs = (mcc == null ? "no-plmn" : mcc + "/" + mnc)
                + " criterion="
                + (criterion ? String.valueOf(cp.tcpCriterionLen) : "none")
                + "/v4=" + cp.tcpCriterionV4
                + "/v6=" + cp.tcpCriterionV6
                + " expires=" + JoanSipBuilder.registerExpires()
                + (platformExpirySec > 0 ? "(platform)" : "(profile)")
                + " pcscf_port=" + JoanSipBuilder.pcscfSipPort()
                + " ua=" + (JoanSipBuilder.sendUserAgent() ? "yes" : "no")
                + " auth_algo="
                + (JoanSipBuilder.sendAuthAlgorithm() ? "yes" : "no")
                + " src=" + cp.srcKey;
        return cs;
    }

    /**
     * Push the carrier's session-timer settings into the SIP builder.
     *
     * <p>Done on every driver pass rather than once: carrier config
     * arrives after the SIM settles and is rebuilt whenever
     * com.android.phone restarts, so a value read at boot is not
     * necessarily the one in force when a call is placed.
     */
    private static void applySessionTimerConfig(Context app, int sub) {
        JoanImsVoiceConfig c = JoanImsVoiceConfig.forSub(app, sub);
        if (c.timerSupported) {
            JoanSipBuilder.setSessionTimer(c.sessionExpiresSec, c.minSeSec,
                    c.refresherType);
        } else {
            /* The carrier says no. Offering a Session-Expires anyway and
             * then refreshing on a schedule nothing agreed to is how a
             * working call gets torn down. */
            JoanSipBuilder.setSessionTimer(0, c.minSeSec, c.refresherType);
        }
        JoanSipBuilder.setSessionRefreshMethod(c.refreshMethod);
        JoanSipBuilder.setSessionBandwidth(c.asKbps, c.rsBps, c.rrBps);
        String sum = c.summary();
        if (!sum.equals(sSeSummary)) {
            sSeSummary = sum;
            JoanTrace.note("session timer config " + sum);
        }
        /* Same pass, same reason: the carrier's codec offer arrives with
         * the rest of carrier config, after the SIM settles. */
        String codecs = JoanCodecConfig.apply(app, sub);
        if (!codecs.isEmpty()) {
            JoanTrace.note("codec offer " + codecs);
        }
    }

    private static void ensureImsRequest(ConnectivityManager cm) {
        synchronized (NET_LOCK) {
            if (sImsRequested) {
                return;
            }
            try {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                        .build();
                sImsCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        JoanTrace.note("IMS network callback available");
                        poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE);
                    }

                    @Override
                    public void onLost(Network network) {
                        JoanTrace.note("IMS network callback lost");
                        poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST);
                    }

                    @Override
                    public void onLinkPropertiesChanged(Network network,
                            LinkProperties lp) {
                        /* AOSP watches the same callback for this
                         * (Apn.ImsNetworkCallback.onLinkPropertiesChanged)
                         * and raises EVENT_IP_CHANGED; what it does next
                         * is in its native stack, so the recovery below
                         * is ours. */
                        java.net.InetAddress inUse = JoanSipUa.localAddr();
                        if (inUse == null || lp == null) {
                            return;
                        }
                        java.util.List<LinkAddress> las;
                        try {
                            las = lp.getLinkAddresses();
                        } catch (Throwable t) {
                            return;
                        }
                        if (las == null || las.isEmpty()) {
                            return;
                        }
                        String[] have = new String[las.size()];
                        for (int i = 0; i < have.length; i++) {
                            java.net.InetAddress a = las.get(i).getAddress();
                            have[i] = a == null ? "" : a.getHostAddress();
                        }
                        if (!JoanAppRegister.JoanRegLifecycle
                                .localAddressGone(have,
                                        inUse.getHostAddress())) {
                            return;
                        }
                        JoanTrace.note("IMS link no longer carries our local"
                                + " address (" + have.length + " present)");
                        poke(JoanAppRegister.JoanRegLifecycle
                                .POKE_IMS_IP_CHANGED);
                    }
                };
                cm.requestNetwork(req, sImsCallback);
                sImsRequested = true;
                JoanTrace.note("requested IMS cellular network");
            } catch (Throwable t) {
                JoanTrace.note("IMS network request failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    private static void releaseImsRequest(Context app) {
        synchronized (NET_LOCK) {
            if (!sImsRequested || sImsCallback == null) {
                return;
            }
            try {
                ConnectivityManager cm = app.getSystemService(
                        ConnectivityManager.class);
                if (cm != null) {
                    cm.unregisterNetworkCallback(sImsCallback);
                }
                JoanTrace.note("released IMS network request");
            } catch (Throwable t) {
                JoanTrace.note("IMS network release failed: "
                        + t.getClass().getSimpleName());
            } finally {
                sImsCallback = null;
                sImsRequested = false;
            }
        }
    }

    private static int safeDataNetworkType(TelephonyManager tm) {
        try {
            return tm.getDataNetworkType();
        } catch (Throwable t) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }

    private static int defaultOrActiveSubscriptionId(Context app) {
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub >= 0) {
            return sub;
        }
        sub = SubscriptionManager.getDefaultSubscriptionId();
        if (sub >= 0) {
            return sub;
        }
        try {
            SubscriptionManager sm = app.getSystemService(
                    SubscriptionManager.class);
            if (sm == null) {
                return -1;
            }
            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list != null && !list.isEmpty()) {
                return list.get(0).getSubscriptionId();
            }
            SubscriptionInfo slot0 = sm.getActiveSubscriptionInfoForSimSlotIndex(0);
            if (slot0 != null) {
                return slot0.getSubscriptionId();
            }
            int[] ids = sm.getSubscriptionIds(0);
            if (ids != null && ids.length > 0) {
                return ids[0];
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static String subscriptionDebug(Context app) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("defaultData=")
                    .append(SubscriptionManager.getDefaultDataSubscriptionId());
            sb.append(" default=")
                    .append(SubscriptionManager.getDefaultSubscriptionId());
            SubscriptionManager sm = app.getSystemService(
                    SubscriptionManager.class);
            if (sm == null) {
                sb.append(" sm=null");
            } else {
                try {
                    List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                    sb.append(" activeList=").append(list == null ? -1 : list.size());
                } catch (Throwable t) {
                    sb.append(" activeList=").append(t.getClass().getSimpleName());
                }
                try {
                    SubscriptionInfo slot0 = sm.getActiveSubscriptionInfoForSimSlotIndex(0);
                    sb.append(" slot0Info=")
                            .append(slot0 == null ? -1 : slot0.getSubscriptionId());
                } catch (Throwable t) {
                    sb.append(" slot0Info=").append(t.getClass().getSimpleName());
                }
                try {
                    int[] ids = sm.getSubscriptionIds(0);
                    sb.append(" slot0Ids=");
                    if (ids == null) {
                        sb.append("null");
                    } else {
                        sb.append('[');
                        for (int i = 0; i < ids.length; i++) {
                            if (i != 0) sb.append(',');
                            sb.append(ids[i]);
                        }
                        sb.append(']');
                    }
                } catch (Throwable t) {
                    sb.append(" slot0Ids=").append(t.getClass().getSimpleName());
                }
            }
            int sub = defaultOrActiveSubscriptionId(app);
            sb.append(" chosen=").append(sub);
            TelephonyManager tm0 = app.getSystemService(TelephonyManager.class);
            if (tm0 != null) {
                TelephonyManager tm = sub >= 0 ? tm0.createForSubscriptionId(sub) : tm0;
                sb.append(" sim=").append(safeSimState(tm));
                sb.append(" dataNet=").append(safeDataNetworkType(tm));
                Integer mode = preferredNetworkMode(app, sub);
                sb.append(" pref=").append(mode == null ? "null" : mode);
            }
        } catch (Throwable t) {
            sb.append(" error=").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** Return the first available preferred-network setting for this sub. */
    private static Integer preferredNetworkMode(Context app, int sub) {
        String[] keys = new String[] {
                "preferred_network_mode" + sub,
                "preferred_network_mode0",
                "preferred_network_mode1",
                "preferred_network_mode"
        };
        for (String key : keys) {
            try {
                String v = Settings.Global.getString(app.getContentResolver(),
                        key);
                if (v == null || v.isEmpty() || "null".equals(v)) {
                    continue;
                }
                return Integer.valueOf(v.trim());
            } catch (Throwable ignore) {
                // try next key
            }
        }
        return null;
    }

    /** Android RIL preferred-network modes that include LTE. */
    private static boolean networkModeAllowsLte(int mode) {
        switch (mode) {
            case 8:   // LTE_CDMA_EVDO
            case 9:   // LTE_GSM_WCDMA
            case 10:  // LTE_CDMA_EVDO_GSM_WCDMA
            case 11:  // LTE_ONLY
            case 12:  // LTE_WCDMA
            case 15:  // LTE_TD_SCDMA
            case 17:  // LTE_TD_SCDMA_GSM
            case 19:  // LTE_TD_SCDMA_WCDMA
            case 20:  // LTE_TD_SCDMA_GSM_WCDMA
            case 22:  // LTE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA
            case 24:  // NR_LTE
            case 25:
            case 26:
            case 27:
            case 28:
            case 29:
            case 30:
            case 31:
            case 32:
            case 33:
                return true;
            default:
                return false;
        }
    }

    private static boolean isLteLike(int networkType) {
        // LTE_CA is radio type 19 but is not exposed by every public SDK jar.
        return networkType == TelephonyManager.NETWORK_TYPE_LTE
                || networkType == 19
                || networkType == TelephonyManager.NETWORK_TYPE_NR;
    }

    /** Realm comes from the IMPI suffix; null when IMPI is malformed. */
    private static String realmFromImpi(String impi) {
        int at = impi.indexOf('@');
        return at > 0 ? impi.substring(at + 1) : null;
    }

    private static String hiddenString(TelephonyManager tm, String name) {
        try {
            Method m = TelephonyManager.class.getMethod(name);
            Object v = m.invoke(tm);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A usable local address on the IMS PDN: IPv6 preferred, IPv4 accepted.
     *
     * This used to demand IPv6 and stop otherwise, which was stricter than
     * the code behind it -- JoanAppRegister.findIms() has always fallen
     * back to IPv4. A handset whose IMS PDN is v4-only was refused at the
     * readiness check for a capability the registration path had, and the
     * only sign was "IMS network has no usable IPv6 local address"
     * repeating forever.
     */
    /**
     * A usable local address on the IMS PDN: IPv6 preferred, IPv4 accepted.
     * Kept as a small compatibility shim for any external reader; discovery
     * itself now runs through JoanImsDiscovery.locals/plan.
     */
    private static InetAddress pickLocal(LinkProperties lp) {
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet6Address && !a.isLinkLocalAddress()
                    && !a.isLoopbackAddress()) {
                return a;
            }
        }
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                return a;
            }
        }
        return null;
    }

    /**
     * The other family's address for the stock-parity flip retry: if
     * pickLocal returned v6, this returns the first v4 (and vice versa).
     * Null when the PDN is single-family.
     */
    private static InetAddress pickLocalFlipped(LinkProperties lp) {
        boolean preferV6 = pickLocal(lp) instanceof Inet6Address;
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            boolean is6 = a instanceof Inet6Address;
            if (a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                continue;
            }
            if (is6 != preferV6) {
                return a;
            }
        }
        return null;
    }

    /**
     * Every P-CSCF the IMS PDN advertises, comma-separated, IPv6 first.
     *
     * This used to return only the first address. When the carrier drained
     * that node mid-session the daemon retried it forever and registration
     * stayed down until the radio was bounced, even though the PDN was
     * advertising two other addresses that answered immediately. The daemon
     * fails over across whatever it is given, so give it all of them.
     */
    /**
     * Why discovery found no P-CSCF. Replaced in alpha13 by
     * JoanImsDiscovery.Pcscfs (link vs SIM source, counts, API status).
     */
    private static volatile String sPcscfReason = "";

    static String pcscfReason() {
        return sPcscfReason;
    }

    private static String stripScope(String host) {
        if (host != null && host.contains("%")) {
            return host.substring(0, host.indexOf('%'));
        }
        return host;
    }
}
