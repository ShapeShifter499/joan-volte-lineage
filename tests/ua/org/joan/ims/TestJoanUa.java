package org.joan.ims;

import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Regression tests against production UA/register/builder sources.
 * Synthetic fixtures and mock transports are explicitly offline; never radio evidence.
 * Exit 1 means regression failures. Transports never contact a carrier.
 */
public class TestJoanUa {
    static int defects;
    static final Class<?> UA = JoanSipUa.class;
    static final String TO = "<sip:peer@example.invalid>;tag=remote";
    static final String FROM = "<sip:joan@example.invalid>;tag=local";
    static final String SDP = "v=0\r\nc=IN IP4 127.0.0.1\r\nm=audio 40002 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n";
    static Field field(Class<?> c, String n) throws Exception { Field f=c.getDeclaredField(n);f.setAccessible(true);return f; }
    static Object get(String n) throws Exception { return field(UA,n).get(null); }
    static void set(String n,Object v) throws Exception { field(UA,n).set(null,v); }
    static Object invoke(String n,Class<?>[] types,Object... args) throws Exception {
        Method m=UA.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(null,args);
    }
    static void defect(String n, boolean reproduced, String details) {
        if (reproduced) { defects++; System.out.println("FAIL "+n+": "+details); }
        else System.out.println("PASS "+n);
    }
    static JoanSipBuilder.Dialog dialog(String cid) {
        JoanSipBuilder.Dialog d=new JoanSipBuilder.Dialog();d.callId=cid;d.fromTag="local";d.branch="z9hG4bKfixture";d.cseq=1;return d;
    }
    static JoanSipBuilder.Id identity() {
        return new JoanSipBuilder.Id("private@example.invalid","sip:joan@example.invalid","example.invalid","127.0.0.1",40010,40011,"");
    }
    static class Wire extends DatagramSocket {
        final BlockingQueue<String> sent=new LinkedBlockingQueue<>();
        final BlockingQueue<String> inbound=new LinkedBlockingQueue<>();
        volatile boolean blocked;
        final CountDownLatch release=new CountDownLatch(1);
        Wire() throws Exception { super((SocketAddress)null); }
        @Override public void send(DatagramPacket p) throws IOException {
            sent.add(new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.US_ASCII));
            if (blocked) {
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IOException("offline gate timeout"); }
                catch (InterruptedException e) { throw new IOException(e); }
                throw new IOException("offline injected send failure");
            }
        }
        @Override public void receive(DatagramPacket p) throws IOException {
            String s=inbound.poll();if(s==null) throw new SocketTimeoutException("offline empty");
            byte[] b=s.getBytes(StandardCharsets.US_ASCII);System.arraycopy(b,0,p.getData(),0,b.length);p.setLength(b.length);
        }
    }
    static Wire reset() throws Exception {
        if(get("sSockC")!=null) ((DatagramSocket)get("sSockC")).close();
        set("sReg",true);set("sCall",true);set("sLiveHeld",false);
        set("sId",identity());set("sPublicId","sip:joan@example.invalid");set("sDlg",dialog("A"));
        set("sDest","sip:peer@example.invalid");set("sTarget","sip:peer@example.invalid");
        set("sRoute","");set("sServiceRoute","");set("sToHdr",TO);set("sFromHdr",FROM);set("sOurToTag","local");
        set("sHeldInvite",null);set("sParked",null);((Map<?,?>)get("sInviteFlights")).clear();
        set("sTcpPeer",null);set("sTcpClient",null);set("sReplyTcp",false);set("sApp",null);
        set("sPcscf",InetAddress.getLoopbackAddress());set("sPcscfPortS",5060);
        ((Map<?,?>)get("sInviteWaits")).clear();((JoanSipBuilder.InviteAckArchive)get("sInviteAcks")).clear();
        ((StringBuilder)get("sTcpClientAcc")).setLength(0);
        Wire w=new Wire();set("sSockC",w);set("sSockS",null);return w;
    }
    static String request(String method,String cid,int seq,String body) {
        return method+" sip:joan@example.invalid SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKtest\r\nFrom: "+TO+"\r\nTo: "+FROM+"\r\nCall-ID: "+cid+"\r\nCSeq: "+seq+" "+method+"\r\nContact: <sip:peer@example.invalid>\r\nContent-Length: "+body.length()+"\r\n\r\n"+body;
    }
    static String reply(String cid,int seq) {
        return "SIP/2.0 200 OK\r\nFrom: "+FROM+"\r\nTo: "+TO+"\r\nCall-ID: "+cid+"\r\nCSeq: "+seq+" INVITE\r\nContact: <sip:peer@example.invalid>\r\nContent-Length: 0\r\n\r\n";
    }
    static void inbound(String rx) throws Exception { invoke("handleInbound",new Class<?>[]{String.class},rx); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 1: The listener must route a first final to the active waiter, not merely ACK it.
        Wire w=reset();JoanSipBuilder.Dialog a=(JoanSipBuilder.Dialog)get("sDlg");
        ((JoanSipBuilder.InviteAckArchive)get("sInviteAcks")).begin("A",1,a,TO,FROM,"sip:peer@example.invalid","");
        Class<?> wc=Class.forName("org.joan.ims.JoanSipUa$InviteWait");
        Constructor<?> ctor=wc.getDeclaredConstructor(String.class,int.class);ctor.setAccessible(true);
        Object waiter=ctor.newInstance("A",1);
        ((Map<String,Object>)get("sInviteWaits")).put("A#1",waiter);
        inbound(reply("A",1));
        int queued=((BlockingQueue<?>)field(wc,"replies").get(waiter)).size();
        defect("active-final-stolen",queued==0 && w.sent.size()==1,"active waiter queued="+queued+", transmitted="+(w.sent.peek()==null?"none":w.sent.peek().split(" ")[0]));

        // 2: Two held calls satisfy merge's entry contract but fail its focus INVITE.
        w=reset();set("sLiveHeld",true);JoanSipUa.sInviteTimeoutMs=400;
        Object parked=invoke("snapLocked",new Class<?>[]{});field(parked.getClass(),"dlg").set(parked,dialog("B"));set("sParked",parked);
        String result=JoanSipUa.merge(JoanCarrierProfile.defaults("310","260"));
        defect("conference-focus-blocked",result.contains("two calls") && w.sent.isEmpty(),"merge returned "+result+", sent="+w.sent.size());

        JoanSipUa.sInviteTimeoutMs=30_000;
        // 3: Wrong/stale dialog BYE cannot tear down a different active call.
        w=reset();inbound(request("BYE","unrelated",8,""));
        defect("unrelated-bye-kills-live",!JoanSipUa.callActive(),"callActive="+JoanSipUa.callActive()+", response="+w.sent.peek().split("\r\n")[0]);

        // 4: Retransmitted initial INVITE or in-dialog re-INVITE with only one live call.
        w=reset();inbound(request("INVITE","A",1,SDP));
        defect("existing-dialog-rings-again",get("sHeldInvite")!=null && w.sent.stream().anyMatch(s->s.startsWith("SIP/2.0 180")),"same Call-ID became ringing INVITE; responses="+w.sent.size());

        // 5: Release must clear ringing/old dialog state as well as transports.
        set("sHeldInvite",request("INVITE","ringing",1,SDP));
        invoke("releaseLocked",new Class<?>[]{});
        defect("release-retains-ringing",get("sHeldInvite")!=null,"held INVITE still present after release");

        // 6: The real A/B/A send path: B overwrites the supposedly per-dialog flight claim.
        w=reset();parked=invoke("snapLocked",new Class<?>[]{});
        field(parked.getClass(),"dlg").set(parked,dialog("B"));set("sParked",parked);
        w.blocked=true;final Wire blockedWire=w;
        ExecutorService pool=Executors.newFixedThreadPool(3);
        List<Future<?>> futures=new ArrayList<>();List<String> ids=new ArrayList<>();
        try {
            for(String cid:new String[]{"A","B","A"}) {
                futures.add(pool.submit(()->{try{return invoke("reInviteLive",new Class<?>[]{boolean.class,String.class},true,cid);}catch(Exception e){throw new RuntimeException(e);}}));
                String sent=w.sent.poll(2,TimeUnit.SECONDS);if(sent!=null) ids.add(JoanSipBuilder.header(sent,"Call-ID"));
            }
            defect("per-dialog-flight-overwritten",ids.equals(Arrays.asList("A","B","A")),"simultaneously outstanding INVITEs="+ids);
        } finally { blockedWire.release.countDown();for(Future<?> f:futures)f.get(3,TimeUnit.SECONDS);pool.shutdownNow(); }

        // 7: Real TCP reader leaves a complete pipelined frame stranded at EOF/idle.
        w=reset();String one=reply("one",1),two=reply("two",1);
        Socket fake=new Socket(){ final InputStream in=new ByteArrayInputStream((one+two).getBytes(StandardCharsets.US_ASCII));@Override public InputStream getInputStream(){return in;} };
        set("sTcpClient",fake);
        String first=(String)invoke("recvTcpClient",new Class<?>[]{int.class},1);
        String second=(String)invoke("recvTcpClient",new Class<?>[]{int.class},1);
        int remains=((StringBuilder)get("sTcpClientAcc")).length();
        defect("tcp-buffer-not-drained",one.equals(first)&&second==null&&remains==two.length(),"first delivered; second="+second+", complete buffered chars="+remains);
        fake.close();set("sTcpClient",null);

        // 8: Reliable provisional acknowledgement must advance local request CSeq.
        JoanSipBuilder.Id id=identity();a=dialog("A");
        String prack=JoanSipBuilder.buildPrack(id,a,"sip:peer@example.invalid","",null,TO,FROM,1);
        String ri=JoanSipBuilder.buildReInvite(id,a,"sip:peer@example.invalid","",null,TO,FROM,SDP);
        int pc=JoanSipBuilder.cseqForMethod(prack,"PRACK"),rc=JoanSipBuilder.cseqForMethod(ri,"INVITE");
        defect("prack-cseq-reused",pc==rc,"PRACK CSeq="+pc+", following re-INVITE CSeq="+rc);

        // 9: An outbound request's Via transport must match the socket it is
        // sent on. Every request leaves on the UDP client socket, including
        // after an inbound TCP accept, so a Via claiming TCP is the defect:
        // the P-CSCF answers 400 Bad Request (Viettel MO, alpha20).
        String inv=JoanSipBuilder.buildInvite(id,dialog("fresh"),"sip:peer@example.invalid","",null,40000,null);
        defect("invite-via-claims-tcp-but-sends-udp",
                JoanSipBuilder.header(inv,"Via").contains("/TCP"),
                "Via="+JoanSipBuilder.header(inv,"Via"));

        // 10: SUBSCRIBE builder emits no mandatory To header.
        String sub=JoanSipBuilder.buildConfSubscribe(id,dialog("focus"),"sip:focus@example.invalid","",null,21600);
        defect("subscribe-missing-to",JoanSipBuilder.header(sub,"To")==null,"To="+JoanSipBuilder.header(sub,"To"));

        // 11: Missing header must not be sourced from the SIP body.
        String bodyHeader=JoanSipBuilder.header("SIP/2.0 200 OK\r\nContent-Length: 21\r\n\r\nCall-ID: body-spoof\r\n","Call-ID");
        defect("header-parser-reads-body","body-spoof".equals(bodyHeader),"absent header resolved to "+bodyHeader);

        // 12: REGISTER transaction accepts 100 Trying as if it were its final response.
        w=reset();w.inbound.add("SIP/2.0 100 Trying\r\nCSeq: 1 REGISTER\r\nContent-Length: 0\r\n\r\n");
        Method sr=JoanAppRegister.class.getDeclaredMethod("sendRecv",DatagramSocket.class,DatagramSocket.class,InetAddress.class,int.class,byte[].class,int.class);sr.setAccessible(true);
        String got=(String)sr.invoke(null,w,null,InetAddress.getLoopbackAddress(),5060,new byte[]{1},20);
        boolean provisional=got!=null&&got.startsWith("SIP/2.0 100");
        defect("register-stops-at-provisional",provisional,"sendRecv returned "+(got==null?"null (correct: no final)":got.split("\r\n")[0]));

        // 13: successful refresh must keep the live call and retire old UDP/SAs.
        w=reset();
        final int[] closed=new int[1];
        AutoCloseable sa=()->closed[0]++;
        DatagramSocket oldS=new DatagramSocket((SocketAddress)null);
        set("sSockS",oldS);set("sHeld",new AutoCloseable[]{sa});
        invoke("releaseLocked",new Class<?>[]{boolean.class},false);
        defect("refresh-clears-call",!JoanSipUa.callActive(),"sCall cleared on preserve release");
        defect("refresh-leaks-udp",!w.isClosed()||!oldS.isClosed()||closed[0]!=1,
                "closed c="+w.isClosed()+" s="+oldS.isClosed()+" sa="+closed[0]);
        oldS.close();

        // 14: wrong-dialog REFER NOTIFY cannot confirm a transfer.
        w=reset();
        Class<?> nwc=Class.forName("org.joan.ims.JoanSipUa$NonInviteWait");
        Constructor<?> nctor=nwc.getDeclaredConstructors()[0];nctor.setAccessible(true);
        Object nw=nctor.newInstance(nctor.getParameterCount()==7
                ? new Object[]{"focus",7,"REFER","z9hG4bK-good","sip:x@example.invalid","local","remote"}
                : new Object[]{"focus",7,"REFER","z9hG4bK-good","sip:x@example.invalid"});
        ((Map<String,Object>)get("sNonInviteWaits")).put("focus#REFER#7",nw);
        inbound("NOTIFY sip:x@example.invalid SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1;branch=z9hG4bK-bad\r\nFrom: <sip:x@example.invalid>;tag=WRONG-REMOTE\r\nTo: <sip:y@example.invalid>;tag=WRONG-LOCAL\r\nCall-ID: focus\r\nCSeq: 12 NOTIFY\r\nEvent: refer;id=7\r\nSubscription-State: terminated\r\nContent-Type: message/sipfrag\r\nContent-Length: 16\r\n\r\nSIP/2.0 200 OK\r\n");
        defect("wrong-dialog-refer-notify",field(nwc,"notifyFinal").getBoolean(nw),"notifyFinal set from mismatched dialog tags");
        ((Map<?,?>)get("sNonInviteWaits")).clear();

        // 15: tagless/mismatched BYE cannot kill the live dialog.
        w=reset();
        inbound("BYE sip:joan@example.invalid SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1;branch=z9hG4bK-bad\r\nFrom: <sip:peer@example.invalid>\r\nTo: <sip:joan@example.invalid>;tag=WRONG\r\nCall-ID: A\r\nCSeq: 18 BYE\r\nContent-Length: 0\r\n\r\n");
        defect("tagless-bye-kills-live",!JoanSipUa.callActive(),"callActive="+JoanSipUa.callActive());
        w.close();System.out.println("UA_REGRESSION_FAILURES="+defects);System.exit(defects==0?0:1);
    }
}
