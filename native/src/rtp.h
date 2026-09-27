/* rtp.h — PCMU (G.711 µ-law) RTP sender/receiver on the IMS PDN.
 *
 * Signalling IPsec does not cover this socket: 3GPP media is ordinary
 * UDP on the IMS bearer. The pmOS bring-up proved one-way PCMU to a
 * live voicemail this way. AMR is advertised in SDP but not implemented
 * here; rtp_start() refuses a session whose payload type is not PCMU.
 *
 * RTCP SR+RR+SDES follows RFC 3550 §6.1 (compound) / §6.4.1 / §6.5.
 * Same compound AOSP ImsMedia builds (RtpSession::formSrList,
 * krazey/ImsMedia a8f065b). That C++ encoder is not imported; PJSIP's
 * pjmedia_rtcp_build_rtcp is GPL-2.0 and is not copied. Mux is SDP-only
 * (RFC 5761). a=rtcp: (RFC 3605) sets the non-mux destination port.
 */
#ifndef JOAN_IMS_RTP_H
#define JOAN_IMS_RTP_H

#include <stddef.h>

int rtp_start(const char *local_ip, const char *iface,
              int local_port,
              const char *remote_ip, int remote_port,
              int pt, int rtcp_mux, int remote_rtcp_port);

void rtp_stop(void);
int rtp_active(void);

/* Milliseconds until the next 20 ms frame or RTCP, or -1 if idle. */
int rtp_poll_ms(void);

/* Send due frames. Drain inbound RTP to the Java bridge. Send RTCP. */
void rtp_tick(void);

/* fds to include in select(), or -1. */
int rtp_fd(void);
int rtp_rtcp_fd(void);

#endif /* JOAN_IMS_RTP_H */
