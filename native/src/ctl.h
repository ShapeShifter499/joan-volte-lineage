/* ctl.h — loopback control socket protocol between Java and the UA. */
#ifndef JOAN_IMS_CTL_H
#define JOAN_IMS_CTL_H

#include <sys/types.h>

#include "config.h"

#define CTL_SOCK_ABSTRACT_NAME "joan_ims_ctl"
#define CTL_TCP_PORT 15090
#define CTL_BACKLOG 2
#define CTL_MAX_LINE 2048

void ctl_init(ua_config_t *cfg);
int ctl_serve(void);

#endif /* JOAN_IMS_CTL_H */
