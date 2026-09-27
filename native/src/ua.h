/* ua.h — REGISTER sequencer interface.
 *
 * Two-stage flow (all socket traffic Java->native; no reverse channel):
 *   stage1: send unprotected REGISTER, parse 401 (nonce + Security-Server),
 *           stash challenge -> returns nonce_b64 for Java AKA
 *   stage2: given RES/CK/IK, install kernel xfrm set, send protected
 *           REGISTER with Security-Verify + integrity-protected digest,
 *           parse final response -> REGISTERED on 2xx.
 */
#ifndef JOAN_IMS_UA_H
#define JOAN_IMS_UA_H

#include <stdint.h>
#include <sys/select.h>

#include "config.h"

typedef enum {
    UA_STATE_IDLE = 0,
    UA_STATE_TRYING,
    UA_STATE_CHALLENGED,
    UA_STATE_REGISTERED,
    UA_STATE_ERROR,
} ua_state_t;

void ua_init(ua_config_t *cfg);

ua_state_t ua_state(void);
const char *ua_errstr(void);   /* masked, stable string for STATUS */

/* Stage 1. On success stores challenge and returns 0; nonce written to
 * nonce_out (base64, NUL-terminated). Any other rc = failure. */
int ua_register_stage1(char *nonce_out, size_t nonce_len);

/* Stage 2. res is raw AKA RES (8 or 16 bytes; res_len is exact), ck/ik
 * are raw 16-byte values from ISIM AKA.
 * Returns 0 iff final REGISTER got 2xx. */
/* Place an MO call to `dest` (a sip: or tel: URI) using the registration
 * context learned from the 200 OK. Returns 0 once the call is answered
 * and ACKed. */
int ua_call_invite(const char *dest);

/* Accept or decline an inbound call the daemon is holding while the dialer
 * rings. Returns 0 on success, -1 if nothing is ringing. */
int ua_call_answer(void);
int ua_call_reject(int code);

/* End the call established by ua_call_invite(). Returns 0 if a BYE was
 * sent, -1 if no call is up. */
int ua_call_hangup(void);
int ua_call_is_active(void);

/* The socket inbound requests arrive on (the protected server port), or -1
 * if we are not registered. The ctl loop selects on it. */
int ua_inbound_fd(void);

/* Add UA SIP/RTP fds to rfds. Returns the new maxfd. */
int ua_select_prep(fd_set *rfds, int maxfd);
void ua_select_handle(fd_set *rfds);

/* RTP pacing for the ctl loop. -1 if no media session. */
int ua_media_poll_ms(void);
void ua_media_tick(void);

/* Read and act on one inbound SIP request. */
void ua_handle_inbound(void);

int ua_register_stage2(const uint8_t *res, size_t res_len,
                       const uint8_t *ck, const uint8_t *ik);

#endif /* JOAN_IMS_UA_H */
