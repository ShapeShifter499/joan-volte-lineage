/* config.h — runtime identity/config for the UA.
 * Values arrive from the Java side over the ctl socket (identity family);
 * nothing secret is persisted by this daemon. */
#ifndef JOAN_IMS_CONFIG_H
#define JOAN_IMS_CONFIG_H

#include "sip.h"

typedef struct {
    sip_identity_t id;
    sec_params_t mine;
} ua_config_t;

void cfg_init(ua_config_t *c);

/* Apply key=value line from ctl 'CONFIG' command.
 * 0 ok, -1 malformed, -2 unknown key. Masked errors only. */
int cfg_apply_line(ua_config_t *c, const char *line);

#endif /* JOAN_IMS_CONFIG_H */
