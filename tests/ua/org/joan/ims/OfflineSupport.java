package org.joan.ims;

// Offline-only seams: no Android services, no audio, no carrier network.
class JoanTrace { static void note(String s) {} }
class JoanImsDiagnostics {
    static void noteAttemptContext() {}
    static String attemptContextLine() { return "IMS attempt listener=not_started network={unobserved} data={unobserved}"; }
}
class JoanMedia {
    static int stops;
    static int restarts;
    /** Set false to make the offline seam refuse, as a dead codec would. */
    static boolean restartOk = true;
    static void stop() { stops++; }
    static boolean startRtp(android.content.Context ctx,
                            android.net.Network net,
                            java.net.InetAddress local,
                            java.net.InetAddress dest, int destPort,
                            int rtcpPort, boolean mux, int payloadType,
                            Boolean amrWideband, int amrBitrate,
                            boolean amrOctetAligned, int amrMaxMode,
                            int telephoneEventPt) {
        restarts++;
        return restartOk;
    }
}
class JoanRegistration { static void setRegistered(boolean b, String s) {} }
class JoanDriver {
    static java.util.List<String> pokes = new java.util.ArrayList<>();
    static void poke(String reason) { pokes.add(reason); }
}
class JoanMmTelFeature {
    static int incoming, ended;
    static void onIncomingCall(android.content.Context c, String u, String n, String id) { incoming++; }
    static void onDialogEnded(String id) { ended++; }
    static void onCallEndedRemotely() { ended++; }
    static int peerHolds, peerResumes;
    static void onPeerHoldChanged(String sipCallId, boolean held) {
        if (held) { peerHolds++; } else { peerResumes++; }
    }
    static void onConferenceUsers(java.util.List<String> u) {}
}
