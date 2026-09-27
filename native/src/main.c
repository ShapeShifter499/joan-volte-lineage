/* main.c — joan-ims entrypoint.
 *
 * Runs as init service in the netmgrd SELinux domain (has netlink_xfrm +
 * net_admin). Waits for Java identity push over ctl socket, then runs
 * REGISTER stages on request. No identity material ever logged.
 */
#define _GNU_SOURCE

#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "config.h"
#include "ctl.h"
#include "ua.h"
#include "util.h"

static void on_signal(int sig)
{
    /* async-safe minimal note then exit; stdio not safe here but dprintf is */
    (void)sig;
    _exit(0);
}

int main(int argc, char **argv)
{
    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    /* Open + positive-control the log sink before anything else, so a
     * silent channel is caught here rather than mistaken for a quiet run. */
    klog_banner("joan-ims-ua start");
    klog(LOG_INFO, "start (%s)",
         (argc > 1 && !strcmp(argv[1], "register")) ? "register" : "standby");

    static ua_config_t cfg;
    cfg_init(&cfg);
    ua_init(&cfg);
    ctl_init(&cfg);

    /* Stage 1/2 driven via ctl socket from Java. */
    return ctl_serve();
}
