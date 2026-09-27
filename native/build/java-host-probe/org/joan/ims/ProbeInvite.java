
package org.joan.ims;
public class ProbeInvite {
  public static void main(String[] a) {
    java.security.SecureRandom rng = new java.security.SecureRandom();
    JoanSipBuilder.Params mine = new JoanSipBuilder.Params(1111,2222,15000,16000);
    JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(mine, rng);
    JoanSipBuilder.Id id = new JoanSipBuilder.Id(
      "user@ims.example.net","sip:+15555550100@ims.example.net",
      "ims.example.net","2001:db8::2",25000,26000,"123456789012345");
    JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
    String inv = JoanSipBuilder.buildInvite(id, dlg,
      "tel:+15555550999", "<sip:[2001:db8::1]:5060;lr>",
      "ipsec-3gpp;alg=hmac-sha-1-96", 40000, "3GPP-E-UTRAN-FDD");
    if (inv == null) throw new RuntimeException("null invite");
    if (!inv.startsWith("INVITE tel:+15555550999 SIP/2.0")) throw new RuntimeException("request-line");
    if (!inv.contains("a=rtpmap:0 PCMU/8000")) throw new RuntimeException("pcmu first");
    if (inv.contains("Supported: timer")) throw new RuntimeException("must not advertise timer");
    String sdp = "SIP/2.0 200 OK\r\nContent-Type: application/sdp\r\n\r\n"
      +"v=0\r\nc=IN IP6 2001:db8::9\r\nm=audio 20000 RTP/AVP 0\r\na=sendrecv\r\n";
    JoanSipBuilder.Media m = JoanSipBuilder.parseSdp(sdp);
    if (m == null || m.port != 20000 || !"2001:db8::9".equals(m.ip))
      throw new RuntimeException("sdp parse");
    String to = "SIP/2.0 200 OK\r\nTo: <sip:x@y>;tag=" + "t".repeat(81) + "\r\n\r\n";
    String tag = JoanSipBuilder.extractToTag(to);
    if (tag.length() != 81) throw new RuntimeException("to-tag len "+tag.length());
    System.out.println("ok   invite/sdp/to-tag probe");
  }
}
