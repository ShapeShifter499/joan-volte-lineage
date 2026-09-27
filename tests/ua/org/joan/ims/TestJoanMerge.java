package org.joan.ims;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic carrier/focus. Drives real UA dispatch; no radio or network. */
public final class TestJoanMerge extends TestJoanUa {
    static int checks;
    static void check(String name, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    static String h(String s, String n) { return JoanSipBuilder.header(s,n); }
    static String response(String req,int status,String body,String extra) {
        String to=h(req,"To");
        if (JoanSipBuilder.tagOf(to).isEmpty()) to += ";tag=focus";
        return "SIP/2.0 "+status+" Fixture\r\nVia: "+h(req,"Via")+"\r\nFrom: "+h(req,"From")
            +"\r\nTo: "+to+"\r\nCall-ID: "+h(req,"Call-ID")+"\r\nCSeq: "+h(req,"CSeq")
            +"\r\n"+extra+"Content-Length: "+body.length()+"\r\n\r\n"+body;
    }
    static class FocusWire extends Wire {
        int refers;
        int failRefer;
        boolean rejectFocus, omitTransferFinal, wrongNotify, refuseAll;
        boolean originalsPreserved = true;
        FocusWire() throws Exception { super(); }
        @Override public void send(DatagramPacket pkt) throws IOException {
            super.send(pkt);
            String req = new String(pkt.getData(),pkt.getOffset(),pkt.getLength(),StandardCharsets.US_ASCII);
            String method=JoanSipBuilder.requestMethod(req);
            try {
                if ("INVITE".equals(method)) {
                    boolean focus=!"A".equals(h(req,"Call-ID"))&&!"B".equals(h(req,"Call-ID"));
                    if (focus) originalsPreserved &= "A".equals(JoanSipUa.currentCallId());
                    inbound(response(req,focus&&rejectFocus?503:200,SDP,
                            "Contact: <sip:"+(focus?"focus":"peer")+"@example.invalid>"+(focus?";isfocus":"")+"\r\n"));
                } else if ("SUBSCRIBE".equals(method)) {
                    inbound(response(req,200,"","Expires: 21600\r\n"));
                } else if ("REFER".equals(method)) {
                    refers++;
                    inbound(response(req,100,"",""));
                    inbound(response(req,(refuseAll||refers==failRefer)?403:202,"",""));
                    if (!refuseAll&&refers!=failRefer&&!omitTransferFinal) {
                        int seq=JoanSipBuilder.cseqForMethod(req,"REFER");
                        String body="SIP/2.0 200 OK\r\n";
                        String notify="NOTIFY sip:joan@example.invalid SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKnotify"+seq
                            +"\r\nFrom: "+h(req,"To")+"\r\nTo: "+h(req,"From")+"\r\nCall-ID: "+h(req,"Call-ID")
                            +"\r\nCSeq: "+(700+seq)+" NOTIFY\r\nEvent: refer;id="+(wrongNotify?seq+99:seq)
                            +"\r\nSubscription-State: terminated;reason=noresource\r\nContent-Type: message/sipfrag\r\nContent-Length: "+body.length()+"\r\n\r\n"+body;
                        inbound(notify); // Can arrive before caller consumes the REFER 202.
                    }
                }
            } catch (Exception e) { throw new IOException(e); }
        }
    }
    static FocusWire setup() throws Exception {
        reset();JoanSipUa.sInviteTimeoutMs=120;
        set("sOurToTag","");
        try { set("sMerge",null);set("sConfFocusCallId",null);field(UA,"sTransactionTimeoutMs").set(null,150L); } catch(NoSuchFieldException ignored) {}
        Object p=invoke("snapLocked",new Class<?>[]{});
        field(p.getClass(),"dlg").set(p,dialog("B"));field(p.getClass(),"held").set(p,true);
        field(p.getClass(),"toHdr").set(p,TO);field(p.getClass(),"ourToTag").set(p,"");
        set("sParked",p);
        try { ((java.util.Map<?,?>)get("sNonInviteWaits")).clear(); } catch (Exception ignored) {}
        set("sMergeBusy",false);
        FocusWire w=new FocusWire();((DatagramSocket)get("sSockC")).close();set("sSockC",w);return w;
    }
    public static void main(String[] args) throws Exception {
        FocusWire w=setup();
        String r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("active-plus-held-merge",r.startsWith("OK"));
        check("focus-does-not-overwrite-originals-early",w.originalsPreserved);
        check("both-transfers-confirmed",w.refers==2);
        check("conference-selected",JoanSipUa.conferenceFocusCallId()!=null&&JoanSipUa.conferenceFocusCallId().equals(JoanSipUa.currentCallId()));
        for(String req:w.sent) if(req.startsWith("REFER ")) {
            check("REFER-addressed-to-focus",req.startsWith("REFER sip:focus@example.invalid "));
            String rt=h(req,"Refer-To");
            check("REFER-targets-original-participant",rt.startsWith("<sip:peer@example.invalid?Replaces="));
            check("Replaces-percent-encoded",rt.contains("%3Bto-tag%3Dremote%3Bfrom-tag%3Dlocal")&&!rt.contains("\""));
        }
        w=setup();w.rejectFocus=true;
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("focus-refusal-fails",r.startsWith("ERR"));
        check("focus-refusal-preserves-both",JoanSipUa.dialogAlive("A")&&JoanSipUa.dialogAlive("B"));
        check("focus-refusal-resumes-live",!JoanSipUa.liveHeld());
        w=setup();w.refuseAll=true;
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("all-transfer-refusals-fail",r.startsWith("ERR"));
        check("all-refusals-preserve-both",JoanSipUa.dialogAlive("A")&&JoanSipUa.dialogAlive("B"));
        check("all-refusals-resume-live",!JoanSipUa.liveHeld());
        w=setup();w.failRefer=1;
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("one-transfer-refusal-partial",r.startsWith("OK partial"));
        check("partial-keeps-conference",JoanSipUa.conferenceFocusCallId()!=null);
        check("partial-keeps-survivor",get("sParked")!=null);
        w=setup();w.omitTransferFinal=true;
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("202-alone-is-not-merge-success",!r.equals("OK"));
        check("unconfirmed-transfer-preserves-originals",JoanSipUa.dialogAlive("A")&&JoanSipUa.dialogAlive("B"));
        w=setup();w.failRefer=2;
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("partial-is-explicit",r.startsWith("OK partial"));
        check("partial-keeps-conference-and-survivor",JoanSipUa.conferenceFocusCallId()!=null&&get("sParked")!=null);
        w=setup();w.wrongNotify=true;
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("wrong-event-id-not-success",!r.equals("OK"));
        w=setup();set("sLiveHeld",true);
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("preheld-merge-succeeds",r.startsWith("OK"));
        check("preheld-no-duplicate-hold",!w.sent.stream().filter(s->s.startsWith("INVITE ")&&"A".equals(h(s,"Call-ID"))).anyMatch(s->s.contains("a=sendonly")));
        set("sMergeBusy",true);
        r=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        check("merge-single-flight",r.equals("ERR merge in progress"));
        set("sMergeBusy",false);
        System.out.println("MERGE_CHECKS="+checks+" FAILURES=0");
    }
}
