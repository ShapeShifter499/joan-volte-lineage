/* joan-ims: AP SIP helper, SELinux domain `ims`.
 * No secrets, no keys in this binary. CK/IK come from ISIM AKA at runtime.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <linux/xfrm.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static void klog(const char *msg)
{
    /* stderr only — kmsg_device is denied in vendor domains */
    dprintf(2, "joan-ims: %s\n", msg);
}

static int prove_xfrm(void)
{
    struct sockaddr_nl nl = {
        .nl_family = AF_NETLINK,
        .nl_pid = 0,
        .nl_groups = 0,
    };
    int s = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_XFRM);
    if (s < 0) {
        klog("xfrm socket failed");
        return -1;
    }
    if (bind(s, (struct sockaddr *)&nl, sizeof(nl)) < 0) {
        klog("xfrm bind failed");
        close(s);
        return -1;
    }
    close(s);
    klog("xfrm netlink ok");
    return 0;
}

static int prove_sip_udp(void)
{
    int s = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0) {
        klog("udp6 socket failed");
        return -1;
    }
    int v6only = 0;
    setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
    struct sockaddr_in6 a;
    memset(&a, 0, sizeof(a));
    a.sin6_family = AF_INET6;
    a.sin6_port = htons(15060); /* not 5060: don't fight radio yet */
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
        klog("udp6 bind 15060 failed");
        close(s);
        return -1;
    }
    close(s);
    klog("udp6 bind 15060 ok");
    return 0;
}

int main(int argc, char **argv)
{
    (void)argc;
    klog(argv[0] ? argv[0] : "joan-ims");
    klog("start (open AP SIP helper, domain ims)");
    prove_xfrm();
    prove_sip_udp();
    /* Stay up so init does not respawn-loop. Real REGISTER comes next. */
    for (;;)
        pause();
    return 0;
}
