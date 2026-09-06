package org.joan.ims;

// Offline-only seams: no Android services, no audio, no carrier network.
class JoanTrace { static void note(String s) {} }
class JoanMedia { static int stops; static void stop() { stops++; } }
class JoanRegistration { static void setRegistered(boolean b, String s) {} }
class JoanMmTelFeature {
    static int incoming, ended;
    static void onIncomingCall(android.content.Context c, String u, String n, String id) { incoming++; }
    static void onDialogEnded(String id) { ended++; }
    static void onCallEndedRemotely() { ended++; }
    static void onConferenceUsers(java.util.List<String> u) {}
}
