/* ctl.h — loopback control socket protocol between Java and the UA. */
#ifndef JOAN_IMS_CTL_H
#define JOAN_IMS_CTL_H

#include <sys/types.h>

#include "config.h"

#define CTL_SOCK_ABSTRACT_NAME "joan_ims_ctl"
#define CTL_TCP_PORT 15090
#define CTL_BACKLOG 2
#define CTL_MAX_LINE 2048

/* An app holding an EVENTS connection can be pushed to. The daemon
 * otherwise has no way to reach the app -- the control socket is
 * request/response with the app always connecting -- so an inbound call
 * could never ring the dialer. Best effort; a subscriber that has gone
 * away is dropped. Never pass identity or key material through this. */
#define CTL_EVT_MAX 4

void ctl_init(ua_config_t *cfg);
int ctl_serve(void);
void ctl_emit_event(const char *fmt, ...)
    __attribute__((format(printf, 1, 2)));

#endif /* JOAN_IMS_CTL_H */
