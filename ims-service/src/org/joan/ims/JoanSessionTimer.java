package org.joan.ims;

/**
 * Session timers, RFC 4028.
 *
 * <p>A SIP dialog has no keepalive of its own. If the far end vanishes --
 * radio gone, core restarted, a proxy that dropped the dialog -- nothing
 * in the signalling says so, and both sides sit holding a call that no
 * longer exists. The user sees a call that will not end and a phone that
 * will not place another one. A periodic refresh inside the dialog is the
 * mechanism that makes that state detectable, and the party that sends it
 * is negotiated at INVITE time.
 *
 * <p>The values are carrier configuration, not constants: AOSP keys them
 * as {@code CarrierConfigManager.ImsVoice.KEY_SESSION_TIMER_SUPPORTED_BOOL},
 * {@code KEY_SESSION_EXPIRES_TIMER_SEC_INT},
 * {@code KEY_MINIMUM_SESSION_EXPIRES_TIMER_SEC_INT},
 * {@code KEY_SESSION_REFRESHER_TYPE_INT} and
 * {@code KEY_SESSION_REFRESH_METHOD_INT}, and ImsStack reads exactly those
 * five. {@link JoanImsVoiceConfig} reads them here; this class is the
 * policy they feed, kept free of android types so every branch is
 * provable on the host.
 */
final class JoanSessionTimer {

    /** RFC 4028 s5: Min-SE is never below 90 seconds. */
    static final int MIN_SE_FLOOR = 90;

    /* AOSP's own defaults for the five keys, used when the platform has
     * no carrier config to give us. Matching them means an unconfigured
     * carrier behaves the way the rest of Android would. */
    static final int DEFAULT_EXPIRES_SEC = 1800;
    static final int DEFAULT_MIN_SE_SEC = 90;

    /* Values of KEY_SESSION_REFRESHER_TYPE_INT. */
    static final int REFRESHER_UNKNOWN = 0;
    static final int REFRESHER_UAC = 1;
    static final int REFRESHER_UAS = 2;

    /* Values of KEY_SESSION_REFRESH_METHOD_INT. */
    static final int METHOD_INVITE = 0;
    static final int METHOD_UPDATE_PREFERRED = 1;

    /**
     * RFC 4028 s10: a non-refresher that wants to act early does so 32
     * seconds before expiry, but only when the interval leaves room.
     */
    private static final int NON_REFRESHER_LEAD_SEC = 32;
    private static final int NON_REFRESHER_LEAD_MIN_INTERVAL = 90;

    private JoanSessionTimer() {}

    /* ------------------------------------------------------------------
     * Parsing. Header values arrive from the network, so every one of
     * these has to survive being handed nonsense.
     * ------------------------------------------------------------------ */

    /**
     * Seconds from a Session-Expires value such as {@code 1800;refresher=uac},
     * or -1 when there is no usable number.
     */
    static int parseExpires(String value) {
        if (value == null) {
            return -1;
        }
        String s = value.trim();
        int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi).trim();
        }
        return parseNonNegative(s);
    }

    /** The refresher= parameter of a Session-Expires value. */
    static int parseRefresher(String value) {
        if (value == null) {
            return REFRESHER_UNKNOWN;
        }
        String low = value.toLowerCase(java.util.Locale.US);
        int at = low.indexOf("refresher");
        if (at < 0) {
            return REFRESHER_UNKNOWN;
        }
        int eq = low.indexOf('=', at);
        if (eq < 0) {
            return REFRESHER_UNKNOWN;
        }
        String rest = low.substring(eq + 1).trim();
        int end = 0;
        while (end < rest.length()
                && Character.isLetter(rest.charAt(end))) {
            end++;
        }
        String who = rest.substring(0, end);
        if ("uac".equals(who)) {
            return REFRESHER_UAC;
        }
        if ("uas".equals(who)) {
            return REFRESHER_UAS;
        }
        return REFRESHER_UNKNOWN;
    }

    /** Seconds from a Min-SE value, or -1. */
    static int parseMinSe(String value) {
        return parseExpires(value);
    }

    private static int parseNonNegative(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
            if (n > 86400) {
                /* A day is already absurd for a voice session, and beyond
                 * this the value is more likely corrupt than intended. */
                return 86400;
            }
        }
        return n;
    }

    /**
     * Whether the peer understands session timers.
     *
     * <p>Either header carries the option tag: Supported when the peer
     * merely accepts them, Require when it insists. A peer that says
     * neither gets no Session-Expires from us, because refreshing at one
     * that was never agreed is how a working call gets torn down.
     */
    static boolean peerSupportsTimer(String supported, String require) {
        return hasTimerTag(supported) || hasTimerTag(require);
    }

    private static boolean hasTimerTag(String header) {
        if (header == null) {
            return false;
        }
        String low = header.toLowerCase(java.util.Locale.US);
        int from = 0;
        while (true) {
            int at = low.indexOf("timer", from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0
                    || !isTokenChar(low.charAt(at - 1));
            int after = at + "timer".length();
            boolean rightOk = after >= low.length()
                    || !isTokenChar(low.charAt(after));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '.'
                || c == '_' || c == '+';
    }

    /** Whether an Allow header permits refreshing with UPDATE. */
    static boolean allowsUpdate(String allow) {
        if (allow == null) {
            return false;
        }
        String low = allow.toLowerCase(java.util.Locale.US);
        int from = 0;
        while (true) {
            int at = low.indexOf("update", from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !isTokenChar(low.charAt(at - 1));
            int after = at + "update".length();
            boolean rightOk = after >= low.length()
                    || !isTokenChar(low.charAt(after));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * The method to refresh with.
     *
     * <p>UPDATE is preferred where the carrier asks for it AND the peer
     * allowed it, because it refreshes without re-offering media -- a
     * re-INVITE re-runs codec negotiation every half hour for no reason,
     * and a peer that answers it badly breaks a call that was working.
     * A peer whose Allow omits UPDATE gets a re-INVITE whatever the
     * carrier preference says: sending it a method it rejected would turn
     * every refresh into a 405 and then a dropped call.
     */
    static boolean refreshWithUpdate(int configuredMethod, String peerAllow) {
        return configuredMethod == METHOD_UPDATE_PREFERRED
                && allowsUpdate(peerAllow);
    }

    /* ------------------------------------------------------------------
     * Negotiation.
     * ------------------------------------------------------------------ */

    /** Min-SE we advertise: the carrier's value, never below the floor. */
    static int minSe(int configured) {
        return configured < MIN_SE_FLOOR ? MIN_SE_FLOOR : configured;
    }

    /** Session-Expires we offer: at least our own Min-SE. */
    static int offerExpires(int configured, int configuredMinSe) {
        int want = configured > 0 ? configured : DEFAULT_EXPIRES_SEC;
        int floor = minSe(configuredMinSe);
        return want < floor ? floor : want;
    }

    /**
     * What to re-offer after a 422 Session Interval Too Small.
     *
     * <p>The peer's Min-SE is authoritative for the retry: RFC 4028 s6
     * has the UAC raise its Session-Expires to at least that value and
     * carry the same number as its own Min-SE. Returning -1 means the
     * peer asked for something we will not agree to, and the caller must
     * give up rather than loop: a 422 answered with the same value we
     * already sent is an infinite retry, and a peer demanding an absurd
     * interval is not one we can hold a session with.
     */
    static int retryExpiresAfter422(int peerMinSe, int cap) {
        if (peerMinSe < MIN_SE_FLOOR) {
            /* Below the floor the peer is not speaking RFC 4028; there is
             * nothing to raise to. */
            return -1;
        }
        if (cap > 0 && peerMinSe > cap) {
            return -1;
        }
        return peerMinSe;
    }

    /**
     * The 422 we send when the peer's Session-Expires is below our Min-SE,
     * or 0 when the request is acceptable.
     */
    static int rejectBelowMinSe(int peerExpires, int ourMinSe) {
        if (peerExpires <= 0) {
            return 0; // no timer asked for; nothing to reject
        }
        return peerExpires < minSe(ourMinSe) ? minSe(ourMinSe) : 0;
    }

    /**
     * Who refreshes, as the UAS decides it for its 2xx.
     *
     * <p>The peer's stated preference wins when it made one: it asked to
     * carry the work, or asked us to. With no preference the carrier
     * configuration decides, and with neither we take it ourselves --
     * an unrefreshed session is the failure this mechanism exists to
     * prevent, so the ambiguous case must not leave nobody refreshing.
     */
    static int uasRefresher(int peerPreference, int configured) {
        if (peerPreference == REFRESHER_UAC
                || peerPreference == REFRESHER_UAS) {
            return peerPreference;
        }
        if (configured == REFRESHER_UAC || configured == REFRESHER_UAS) {
            return configured;
        }
        return REFRESHER_UAS;
    }

    /**
     * RFC 4028 s8.2: a 2xx naming the UAC as refresher carries
     * Require: timer, because the UAC has to know it owes the refreshes.
     * Naming ourselves needs no such demand of the peer.
     */
    static boolean requireTimerInAnswer(int refresher) {
        return refresher == REFRESHER_UAC;
    }

    /** Whether we are the one that must send refreshes. */
    static boolean weRefresh(int refresher, boolean weAreUac) {
        if (refresher == REFRESHER_UAC) {
            return weAreUac;
        }
        if (refresher == REFRESHER_UAS) {
            return !weAreUac;
        }
        /* RFC 4028 s7.2: no refresher parameter means the session is not
         * timed. Refreshing anyway is harmless; assuming the peer will is
         * not, so take it. */
        return true;
    }

    /** Header value for a Session-Expires we are sending. */
    static String expiresHeader(int seconds, int refresher) {
        StringBuilder b = new StringBuilder(24);
        b.append(seconds);
        if (refresher == REFRESHER_UAC) {
            b.append(";refresher=uac");
        } else if (refresher == REFRESHER_UAS) {
            b.append(";refresher=uas");
        }
        return b.toString();
    }

    /* ------------------------------------------------------------------
     * Timing.
     * ------------------------------------------------------------------ */

    /**
     * Milliseconds from now until this side should send its refresh, or
     * -1 when the session is not timed.
     *
     * <p>RFC 4028 s10: the refresher acts at half the interval, which
     * leaves a whole second attempt before expiry. A non-refresher may
     * act 32 seconds early when the interval is long enough to make that
     * meaningful, and at three quarters when it is not -- that is a
     * backstop for a peer that agreed to refresh and then did not, not a
     * second refresher.
     */
    static long refreshDueMs(int expiresSec, boolean weRefresh) {
        if (expiresSec <= 0) {
            return -1;
        }
        int sec;
        if (weRefresh) {
            sec = expiresSec / 2;
        } else if (expiresSec > NON_REFRESHER_LEAD_MIN_INTERVAL) {
            sec = expiresSec - NON_REFRESHER_LEAD_SEC;
        } else {
            sec = expiresSec * 3 / 4;
        }
        if (sec < 1) {
            sec = 1;
        }
        return sec * 1000L;
    }

    /**
     * Milliseconds until the session is dead if nothing refreshed it.
     *
     * <p>Reaching this is not a timeout to retry through: RFC 4028 s7
     * says the session is over, and the honest response is a BYE and a
     * call that ends, rather than a call that stays on screen with no
     * dialog behind it.
     */
    static long expiryDueMs(int expiresSec) {
        return expiresSec <= 0 ? -1 : expiresSec * 1000L;
    }

    /** Whether {@code nowMs} has reached a deadline set at {@code atMs}. */
    static boolean due(long deadlineMs, long nowMs) {
        return deadlineMs > 0 && nowMs >= deadlineMs;
    }
}
