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
int ua_register_stage2(const uint8_t *res, size_t res_len,
                       const uint8_t *ck, const uint8_t *ik);

#endif /* JOAN_IMS_UA_H */
