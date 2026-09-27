/* config.h — runtime identity/config for the UA.
 * Values arrive from the Java side over the ctl socket (identity family);
 * nothing secret is persisted by this daemon. */
#ifndef JOAN_IMS_CONFIG_H
#define JOAN_IMS_CONFIG_H

#include "sip.h"

/* The IMS PDN advertises several P-CSCF addresses. Keeping only the first
 * meant that when the carrier drained that node mid-session, registration
 * stayed down across every retry until the radio was bounced. */
#define JOAN_PCSCF_MAX 8

typedef struct {
    char addr[80];
    long blocked_until_ms;   /* monotonic ms; 0 = usable */
} pcscf_cand_t;

typedef struct {
    sip_identity_t id;       /* id.pcscf is the candidate in use now */
    sec_params_t mine;
    pcscf_cand_t pcscf_list[JOAN_PCSCF_MAX];
    int pcscf_n;
} ua_config_t;

void cfg_init(ua_config_t *c);

/* Apply key=value line from ctl 'CONFIG' command.
 * 0 ok, -1 malformed, -2 unknown key. Masked errors only. */
int cfg_apply_line(ua_config_t *c, const char *line);

#endif /* JOAN_IMS_CONFIG_H */
