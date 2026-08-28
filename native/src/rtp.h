/* rtp.h — PCMU (G.711 µ-law) RTP sender/receiver on the IMS PDN.
 *
 * Signalling IPsec does not cover this socket: 3GPP media is ordinary
 * UDP on the IMS bearer. The pmOS bring-up proved one-way PCMU to a
 * live voicemail this way. AMR is advertised in SDP but not implemented
 * here; rtp_start() refuses a session whose payload type is not PCMU.
 */
#ifndef JOAN_IMS_RTP_H
#define JOAN_IMS_RTP_H

#include <stddef.h>

int rtp_start(const char *local_ip, const char *iface,
              int local_port,
              const char *remote_ip, int remote_port,
              int pt);

void rtp_stop(void);
int rtp_active(void);

/* Milliseconds until the next 20 ms frame, or -1 if idle. */
int rtp_poll_ms(void);

/* Send due frames (µ-law silence). Drain inbound RTP (counted, not
 * rendered — in-call audio is Dialer/AudioFlinger after STARTED). */
void rtp_tick(void);

/* fd to include in select(), or -1. */
int rtp_fd(void);

#endif /* JOAN_IMS_RTP_H */
