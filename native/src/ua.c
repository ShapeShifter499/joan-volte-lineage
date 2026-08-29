/* ua.c — two-stage REGISTER sequencer over UDP/TCP on the IMS PDN. */
#define _GNU_SOURCE

#include "ua.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#include "rtp.h"
#include "sip.h"
#include "util.h"
#include "xfrm.h"

static ua_config_t *g_cfg;
static int g_port_s_fd = -1;
static int g_port_s_bound = -1;
static int g_port_c_fd = -1;
static int g_port_c_bound = -1;
static int g_tcp_s_fd = -1;
static int g_tcp_c_fd = -1;
static int g_tcp_s_bound = -1;
static int g_tcp_c_bound = -1;

#define TCP_MAX 4
#define TCP_BUFSZ 8192
static struct {
    int fd;
    char buf[TCP_BUFSZ];
    size_t len;
} g_tcp[TCP_MAX];

static int g_reply_fd = -1;
static int g_reply_tcp;
static struct sockaddr_storage g_reply_peer;
static socklen_t g_reply_plen;

/* Registration context, captured from the 200 OK. A call has to be routed
 * through the same P-CSCF path and carry the same security agreement, so
 * everything the INVITE needs is kept here rather than rebuilt. */
static struct {
    char service_route[512];
    char sec_verify[512];
    int  port_c;          /* our protected client port */
    int  port_s;          /* our protected server port (inbound requests) */
    int  pcscf_port_s;    /* where protected requests go */
    char public_id[300];   /* IMPU from P-Associated-URI; NEVER the IMPI */
    int  valid;
} g_reg;

/* The dialog of the call currently up. ua_call_invite() left its dialog on
 * the stack, so once it returned there was no way to send a BYE and the
 * call sat established until the far end or a session timer killed it. */
static struct {
    sip_dialog_t dlg;
    char dest[300];        /* original request URI */
    char target[300];      /* remote target from the 2xx Contact */
    char route[512];       /* route set: Record-Route if the 2xx carried one */
    char to_tag[64];
    int  active;
    int  se_sec;           /* RFC 4028 Session-Expires; 0 = none */
    int  se_uac;           /* 1 if we are the refresher */
    long refresh_at;
} g_call;
static ua_state_t g_state;
static char g_err[160];

/* Challenge carried between stages. */
static sip_challenge_t g_ch;
static sip_txn_t g_txn;

void ua_init(ua_config_t *cfg)
{
    g_cfg = cfg;
    g_state = UA_STATE_IDLE;
    g_err[0] = '\0';
    for (int i = 0; i < TCP_MAX; i++)
        g_tcp[i].fd = -1;
}

ua_state_t ua_state(void) { return g_state; }

const char *ua_errstr(void)
{
    return g_err[0] ? g_err : "-";
}

static void fail(const char *what, int rc)
{
    /* Masked: error strings carry codes/status lines, never identity. */
    snprintf(g_err, sizeof(g_err), "%.60s rc=%d", what, rc);
    klog(LOG_ERR, "%s", g_err);
    g_state = UA_STATE_ERROR;
}

/* Call-setup failures must not drop REGISTER. A 30s INVITE timeout
 * used fail() and the next Dialer tap got "call before register". */
static void fail_call(const char *what, int rc)
{
    snprintf(g_err, sizeof(g_err), "%.60s rc=%d", what, rc);
    klog(LOG_ERR, "%s", g_err);
}

static int sip_socket_bind(int local_port)
{
    int fam = strchr(g_cfg->id.local_ip, ':') ? AF_INET6 : AF_INET;
    int s = socket(fam, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0)
        return -1;
    {
        int one = 1;
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    }
    if (fam == AF_INET6) {
        int v6only = 1;
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
    }
    /* Android routes per-network via fwmark rules; pin egress to the IMS
     * PDN interface or our packets leak to the default (internet) table
     * and the P-CSCF never answers. */
    if (g_cfg->id.iface[0]) {
        if (setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE,
                       g_cfg->id.iface, strlen(g_cfg->id.iface) + 1) < 0) {
            klog(LOG_ERR, "SO_BINDTODEVICE %.16s failed errno=%d",
                 g_cfg->id.iface, errno);
            close(s);
            return -1;
        }
    }
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    if (fam == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&ss;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)local_port);
        if (inet_pton(AF_INET6, g_cfg->id.local_ip, &a->sin6_addr) != 1) {
            close(s);
            return -1;
        }
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&ss;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)local_port);
        if (inet_pton(AF_INET, g_cfg->id.local_ip, &a->sin_addr) != 1) {
            close(s);
            return -1;
        }
    }
    if (bind(s, (struct sockaddr *)&ss, sizeof(ss)) < 0) {
        klog(LOG_ERR, "sip bind port %d failed errno=%d",
             local_port, errno);
        close(s);
        return -1;
    }
    return s;
}

static int sip_tcp_listen(int local_port)
{
    int fam = strchr(g_cfg->id.local_ip, ':') ? AF_INET6 : AF_INET;
    int s = socket(fam, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (s < 0)
        return -1;
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (fam == AF_INET6) {
        int v6only = 1;
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
    }
    if (g_cfg->id.iface[0]) {
        if (setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE,
                       g_cfg->id.iface, strlen(g_cfg->id.iface) + 1) < 0) {
            klog(LOG_WARN, "tcp SO_BINDTODEVICE errno=%d", errno);
        }
    }
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    if (fam == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&ss;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)local_port);
        if (inet_pton(AF_INET6, g_cfg->id.local_ip, &a->sin6_addr) != 1) {
            close(s);
            return -1;
        }
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&ss;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)local_port);
        if (inet_pton(AF_INET, g_cfg->id.local_ip, &a->sin_addr) != 1) {
            close(s);
            return -1;
        }
    }
    if (bind(s, (struct sockaddr *)&ss, sizeof(ss)) < 0) {
        klog(LOG_ERR, "sip tcp bind port %d errno=%d", local_port, errno);
        close(s);
        return -1;
    }
    if (listen(s, 4) < 0) {
        close(s);
        return -1;
    }
    return s;
}

static void fd_close(int *fd)
{
    if (fd && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static void hold_protected_ports(int port_c, int port_s)
{
    if (g_port_s_fd >= 0 && g_port_s_bound != port_s)
        fd_close(&g_port_s_fd);
    if (g_port_s_fd < 0) {
        g_port_s_fd = sip_socket_bind(port_s);
        if (g_port_s_fd < 0)
            klog(LOG_WARN, "could not hold protected server port %d", port_s);
        else {
            g_port_s_bound = port_s;
            klog(LOG_INFO, "holding protected server port %d", port_s);
        }
    }
    if (g_port_c_fd >= 0 && g_port_c_bound != port_c)
        fd_close(&g_port_c_fd);
    if (g_port_c_fd < 0) {
        g_port_c_fd = sip_socket_bind(port_c);
        if (g_port_c_fd < 0)
            klog(LOG_WARN, "could not hold protected client port %d", port_c);
        else {
            g_port_c_bound = port_c;
            klog(LOG_INFO, "holding protected client port %d", port_c);
        }
    }
    if (g_tcp_s_fd >= 0 && g_tcp_s_bound != port_s)
        fd_close(&g_tcp_s_fd);
    if (g_tcp_s_fd < 0) {
        g_tcp_s_fd = sip_tcp_listen(port_s);
        if (g_tcp_s_fd < 0)
            klog(LOG_WARN, "tcp listen port-s %d failed", port_s);
        else {
            g_tcp_s_bound = port_s;
            klog(LOG_INFO, "tcp listen protected server port %d", port_s);
        }
    }
    if (g_tcp_c_fd >= 0 && g_tcp_c_bound != port_c)
        fd_close(&g_tcp_c_fd);
    if (g_tcp_c_fd < 0) {
        g_tcp_c_fd = sip_tcp_listen(port_c);
        if (g_tcp_c_fd < 0)
            klog(LOG_WARN, "tcp listen port-c %d failed", port_c);
        else {
            g_tcp_c_bound = port_c;
            klog(LOG_INFO, "tcp listen protected client port %d", port_c);
        }
    }
}

static void tcp_accept(int ls)
{
    struct sockaddr_storage peer;
    socklen_t plen = sizeof(peer);
    int c = accept4(ls, (struct sockaddr *)&peer, &plen, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (c < 0)
        return;
    int slot = -1;
    for (int i = 0; i < TCP_MAX; i++) {
        if (g_tcp[i].fd < 0) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        klog(LOG_WARN, "tcp accept dropped (no slot)");
        close(c);
        return;
    }
    g_tcp[slot].fd = c;
    g_tcp[slot].len = 0;
    klog(LOG_INFO, "inbound tcp accept");
}

static int add_fd(fd_set *rfds, int fd, int maxfd)
{
    if (fd >= 0) {
        FD_SET(fd, rfds);
        if (fd > maxfd)
            maxfd = fd;
    }
    return maxfd;
}

int ua_select_prep(fd_set *rfds, int maxfd)
{
    maxfd = add_fd(rfds, g_port_s_fd, maxfd);
    maxfd = add_fd(rfds, g_port_c_fd, maxfd);
    maxfd = add_fd(rfds, g_tcp_s_fd, maxfd);
    maxfd = add_fd(rfds, g_tcp_c_fd, maxfd);
    for (int i = 0; i < TCP_MAX; i++)
        maxfd = add_fd(rfds, g_tcp[i].fd, maxfd);
    maxfd = add_fd(rfds, rtp_fd(), maxfd);
    maxfd = add_fd(rfds, rtp_rtcp_fd(), maxfd);
    return maxfd;
}

static int media_from_sip(const char *msg)
{
    sdp_media_t m;
    if (sdp_parse_media(msg, &m) != 0) {
        klog(LOG_WARN, "no SDP media in message");
        return -1;
    }
    int pt = m.have_pcmu ? 0 : m.pt;
    klog(LOG_INFO, "sdp media port=%d mux=%d rtcp=%d",
         m.port, m.have_rtcp_mux, m.rtcp_port);
    return rtp_start(g_cfg->id.local_ip, g_cfg->id.iface,
                     40000, m.ip, m.port, pt, m.have_rtcp_mux, m.rtcp_port);
}

static int sip_sendto(int s, int dport, const char *pkt, size_t len);

/* Wait for a datagram on `s` or `alt`, whichever speaks first. */
static int sip_wait_recv(int s, int alt, char *rx, size_t rxlen,
                         int timeout_ms)
{
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;

    fd_set rfds;
    FD_ZERO(&rfds);
    int maxfd = -1;
    maxfd = add_fd(&rfds, s, maxfd);
    maxfd = add_fd(&rfds, alt, maxfd);
    maxfd = ua_select_prep(&rfds, maxfd);
    if (maxfd < 0)
        return -1;
    if (select(maxfd + 1, &rfds, NULL, NULL, &tv) <= 0)
        return -1;

    if (g_tcp_s_fd >= 0 && FD_ISSET(g_tcp_s_fd, &rfds))
        tcp_accept(g_tcp_s_fd);
    if (g_tcp_c_fd >= 0 && FD_ISSET(g_tcp_c_fd, &rfds))
        tcp_accept(g_tcp_c_fd);

    for (int i = 0; i < TCP_MAX; i++) {
        if (g_tcp[i].fd < 0 || !FD_ISSET(g_tcp[i].fd, &rfds))
            continue;
        ssize_t n = recv(g_tcp[i].fd, g_tcp[i].buf + g_tcp[i].len,
                         TCP_BUFSZ - 1 - g_tcp[i].len, 0);
        if (n <= 0) {
            close(g_tcp[i].fd);
            g_tcp[i].fd = -1;
            g_tcp[i].len = 0;
            continue;
        }
        g_tcp[i].len += (size_t)n;
        g_tcp[i].buf[g_tcp[i].len] = '\0';
        int got = sip_extract_one(g_tcp[i].buf, &g_tcp[i].len, rx, rxlen);
        if (got == 1) {
            g_reply_fd = g_tcp[i].fd;
            g_reply_tcp = 1;
            klog(LOG_INFO, "reply arrived on tcp");
            return (int)strlen(rx);
        }
    }

    int from = -1;
    if (s >= 0 && FD_ISSET(s, &rfds))
        from = s;
    else if (alt >= 0 && FD_ISSET(alt, &rfds))
        from = alt;
    else if (g_port_s_fd >= 0 && FD_ISSET(g_port_s_fd, &rfds))
        from = g_port_s_fd;
    else if (g_port_c_fd >= 0 && FD_ISSET(g_port_c_fd, &rfds))
        from = g_port_c_fd;
    if (from < 0)
        return -1;
    g_reply_plen = sizeof(g_reply_peer);
    ssize_t r = recvfrom(from, rx, rxlen - 1, 0,
                         (struct sockaddr *)&g_reply_peer, &g_reply_plen);
    if (r <= 0)
        return -1;
    rx[r] = '\0';
    g_reply_fd = from;
    g_reply_tcp = 0;
    klog(LOG_INFO, "reply arrived on %s port socket",
         from == g_port_c_fd ? "client" : "server");
    return (int)r;
}

static int sip_send_recv_dual(int s, int alt, int dport,
                              const char *pkt, size_t len,
                              char *rx, size_t rxlen, int timeout_ms)
{
    if (sip_sendto(s, dport, pkt, len) < 0)
        return -1;

    return sip_wait_recv(s, alt, rx, rxlen, timeout_ms);
}

static int sip_sendto(int s, int dport, const char *pkt, size_t len)
{
    struct sockaddr_storage dst;
    memset(&dst, 0, sizeof(dst));
    socklen_t dlen;
    int fam = strchr(g_cfg->id.pcscf, ':') ? AF_INET6 : AF_INET;
    if (fam == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&dst;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)dport);
        dlen = sizeof(*a);
        if (inet_pton(AF_INET6, g_cfg->id.pcscf, &a->sin6_addr) != 1)
            return -1;
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&dst;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)dport);
        dlen = sizeof(*a);
        if (inet_pton(AF_INET, g_cfg->id.pcscf, &a->sin_addr) != 1)
            return -1;
    }
    ssize_t w = sendto(s, pkt, len, 0, (struct sockaddr *)&dst, dlen);
    if (w < 0 || (size_t)w != len) {
        klog(LOG_ERR, "sendto failed errno=%d w=%zd len=%zu",
             errno, w, len);
        return -1;
    }
    return 0;
}

int ua_register_stage1(char *nonce_out, size_t nonce_len)
{
    g_err[0] = '\0';
    if (!g_cfg->id.have_id || !g_cfg->id.local_ip[0] ||
        !g_cfg->id.pcscf[0]) {
        fail("no id/net yet", 1);
        return -1;
    }

    sip_identity_t id = g_cfg->id;
    joan_sec_params_default(&g_cfg->mine);
    txn_new(&g_txn, &id, g_cfg->mine);
    g_ch.have_nonce = 0;
    g_ch.have_sec_server = 0;

    char msg[SIP_MAX_MSG];
    int n = build_register(msg, sizeof(msg), &id, &g_txn, 1, NULL,
                           NULL, 0, NULL, NULL);
    if (n <= 0) {
        fail("build reg1", 2);
        return -2;
    }
    klog(LOG_INFO, "reg1 built (%d bytes) UDP", n);

    g_state = UA_STATE_TRYING;
    int s = sip_socket_bind(g_cfg->id.local_port);
    if (s < 0) {
        fail("bind", 3);
        return -3;
    }
    char rx[4096];
    int r = sip_send_recv_dual(s, -1, g_cfg->id.pcscf_port, msg,
                               (size_t)n, rx, sizeof(rx), 8000);
    close(s);
    if (r <= 0) {
        fail("reg1 no reply", 4);
        return -4;
    }
    sip_response_t resp;
    if (parse_response(rx, (size_t)r, &resp) != 0) {
        fail("reg1 parse", 5);
        return -5;
    }
    klog(LOG_INFO, "reg1 reply: %d %s", resp.status, resp.reason);

    if (resp.status != 401) {
        snprintf(g_err, sizeof(g_err), "reg1 unexpected %d", resp.status);
        klog(LOG_ERR, "%s", g_err);
        g_state = resp.status >= 200 && resp.status < 300
                      ? UA_STATE_REGISTERED
                      : UA_STATE_ERROR;
        return resp.status >= 200 && resp.status < 300 ? 0 : -6;
    }

    /* 401: stash WWW-Authenticate + Security-Server. */
    if (!resp.have_www_auth) {
        fail("401 no www-auth", 7);
        return -7;
    }
    /* Extract nonce="..." from WWW-Authenticate value. */
    const char *p = strcasestr(resp.www_authenticate, "nonce=\"");
    if (!p) {
        fail("401 no nonce", 8);
        return -8;
    }
    p += 7;
    const char *e = strchr(p, '"');
    size_t nl = e ? (size_t)(e - p) : strlen(p);
    if (nl >= sizeof(g_ch.nonce_b64))
        nl = sizeof(g_ch.nonce_b64) - 1;
    memcpy(g_ch.nonce_b64, p, nl);
    g_ch.nonce_b64[nl] = '\0';
    g_ch.have_nonce = 1;

    snprintf(g_ch.algorithm, sizeof(g_ch.algorithm), "AKAv1-MD5");
    {
        const char *al = strcasestr(resp.www_authenticate, "algorithm=");
        if (al) {
            al += 10;
            char tmp[32];
            size_t ti = 0;
            while (*al && *al != ',' && *al != '"' && ti + 1 < sizeof(tmp))
                tmp[ti++] = *al++;
            tmp[ti] = '\0';
            if (ti)
                snprintf(g_ch.algorithm, sizeof(g_ch.algorithm), "%s", tmp);
        }
    }
    if (resp.have_sec_server) {
        snprintf(g_ch.sec_server, sizeof(g_ch.sec_server), "%s",
                 resp.security_server);
        g_ch.have_sec_server = 1;
    } else {
        fail("401 no security-server", 9);
        return -9;
    }

    g_state = UA_STATE_CHALLENGED;
    if (nonce_out && nonce_len) {
        snprintf(nonce_out, nonce_len, "%s", g_ch.nonce_b64);
    }
    klog(LOG_INFO, "challenge stashed (algorithm=%s)", g_ch.algorithm);
    return 0;
}

int ua_register_stage2(const uint8_t *res, size_t res_len,
                       const uint8_t *ck, const uint8_t *ik)
{
    if (g_state != UA_STATE_CHALLENGED || !g_ch.have_nonce) {
        fail("stage2 without challenge", 20);
        return -20;
    }
    if (!res || (res_len != 8 && res_len != 16) || !ck || !ik) {
        fail("stage2 missing keys arg", 21);
        return -21;
    }

    /* Security agreement both sides. */
    sec_agree_t ue_sec, pcscf_sec;
    {
        char sec_cli_value[1024];
        sip_identity_t id = g_cfg->id;
        sip_txn_t t = g_txn;
        char probe[SIP_MAX_MSG];
        sip_challenge_t ch_probe;
        memset(&ch_probe, 0, sizeof(ch_probe));
        build_register(probe, sizeof(probe), &id, &t, 99, NULL,
                       NULL, 0, NULL, NULL);
        const char *m = strcasestr(probe, "Security-Client: ");
        if (!m) {
            fail("self sec-client missing", 22);
            return -22;
        }
        m += 17;
        const char *meol = strstr(m, "\r\n");
        size_t vl = meol ? (size_t)(meol - m) : strlen(m);
        if (vl >= sizeof(sec_cli_value))
            vl = sizeof(sec_cli_value) - 1;
        memcpy(sec_cli_value, m, vl);
        sec_cli_value[vl] = '\0';
        if (sec_agree_parse(sec_cli_value, &ue_sec) != 0) {
            fail("own sec-agree parse", 23);
            return -23;
        }
        if (sec_agree_parse(g_ch.sec_server, &pcscf_sec) != 0) {
            fail("server sec-agree parse", 24);
            return -24;
        }
    }

    /* Carrier-level security-agreement parameters only: algorithm names,
     * SPIs and ports. No identity, no key material. Needed because a
     * mis-parsed Security-Server means we encrypt with the wrong SPI and
     * the P-CSCF drops the packet without answering. */
    klog(LOG_INFO, "sec-agree ue: alg=%s ealg=%s spi-c=%u spi-s=%u "
                   "port-c=%u port-s=%u",
         ue_sec.alg, ue_sec.ealg, ue_sec.spi_c, ue_sec.spi_s,
         ue_sec.port_c, ue_sec.port_s);
    klog(LOG_INFO, "sec-agree pcscf: alg=%s ealg=%s spi-c=%u spi-s=%u "
                   "port-c=%u port-s=%u",
         pcscf_sec.alg, pcscf_sec.ealg, pcscf_sec.spi_c, pcscf_sec.spi_s,
         pcscf_sec.port_c, pcscf_sec.port_s);
    klog(LOG_INFO, "sec-server raw: %.200s", g_ch.sec_server);

    /* Kernel IPsec: SA+policy set from CK/IK (UDP+TCP selectors). */
    xfrm_status_t xs;
    int xr = xfrm_install(g_cfg->id.local_ip, g_cfg->id.pcscf,
                          &ue_sec, &pcscf_sec, ck, ik, &xs);
    if (xr != 0)
        klog(LOG_WARN, "xfrm install partial rc=%d (continuing)", xr);

    /* RFC 3329 / TS 33.203: hold BOTH protected ports for the life of the
     * registration, UDP and TCP. Closing port-c after REG2 left inbound
     * requests that the core sent to the client port (or over TCP, which
     * pmOS measured as ESP next-header=6) with nowhere to land. */
    hold_protected_ports((int)ue_sec.port_c, (int)ue_sec.port_s);

    /* Give the P-CSCF a moment to install its own SAs before the first
     * protected packet arrives. */
    usleep(300 * 1000);

    /* Protected REGISTER.
     *
     * This one does NOT go out on 5060 like REG1 did. Once the security
     * association exists, TS 33.203 / RFC 3329 require the protected
     * REGISTER to travel inside it: from the UE's protected client port
     * to the P-CSCF's protected server port, the same selectors
     * xfrm_install() just programmed. Sending it 5060 -> 5060 bypasses
     * every SA we installed while the message asserts
     * integrity-protected=yes and echoes Security-Verify, and the P-CSCF
     * answers 401 -- which is exactly what this daemon did until now.
     *
     * Via and Contact must advertise port-c too, so responses and
     * subsequent requests come back inside the association. This mirrors
     * the pmOS implementation that achieved REGISTER 200 on this handset,
     * which builds msg2 with local_port=port_c and pcscf_port=
     * pcscf_sec.port_s and sends it over the SA socket.
     */
    sip_identity_t id = g_cfg->id;
    id.local_port = (int)ue_sec.port_c;
    id.contact_port = (int)ue_sec.port_s;
    id.pcscf_port = (int)pcscf_sec.port_s;
    char msg[SIP_MAX_MSG];
    int n = build_register(msg, sizeof(msg), &id, &g_txn, 2, &g_ch,
                           res, res_len, ck, ik);
    if (n <= 0) {
        fail("build reg2", 25);
        return -25;
    }
    klog(LOG_INFO, "reg2 built (%d bytes)", n);

    int s = g_port_c_fd;
    if (s < 0) {
        fail("bind2", 26);
        return -26;
    }
    klog(LOG_INFO, "reg2 sending %u -> %u (protected)",
         ue_sec.port_c, pcscf_sec.port_s);
    char rx[4096];
    int r = sip_send_recv_dual(s, g_port_s_fd, (int)pcscf_sec.port_s,
                               msg, (size_t)n, rx, sizeof(rx), 8000);
    if (r <= 0) {
        fail("reg2 no reply", 27);
        return -27;
    }
    sip_response_t resp;
    if (parse_response(rx, (size_t)r, &resp) != 0) {
        fail("reg2 parse", 28);
        return -28;
    }
    klog(LOG_INFO, "reg2 reply: %d %s", resp.status, resp.reason);
    if (resp.status >= 200 && resp.status < 300) {
        g_state = UA_STATE_REGISTERED;
        memset(&g_reg, 0, sizeof(g_reg));
        if (resp.have_service_route)
            snprintf(g_reg.service_route, sizeof(g_reg.service_route),
                     "%s", resp.service_route);
        snprintf(g_reg.sec_verify, sizeof(g_reg.sec_verify), "%s",
                 g_ch.sec_server);
        g_reg.port_c = (int)ue_sec.port_c;
        g_reg.port_s = (int)ue_sec.port_s;
        g_reg.pcscf_port_s = (int)pcscf_sec.port_s;
        /* Take the public identity from P-Associated-URI.
         *
         * Without it build_invite() falls back to the IMPI, which is
         * IMSI@ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org -- so an outgoing call
         * presents the subscriber's permanent IMSI as its calling identity
         * and the far end displays it as the caller ID. That happened on a
         * live call. The IMPI authenticates us; it is not a public
         * identity and must never leave in From, Contact or
         * P-Preferred-Identity.
         *
         * P-Associated-URI is the core telling us which public identities
         * it registered, so it is both correct and carrier-neutral. Prefer
         * a tel: URI, else the first sip: URI. */
        if (resp.have_p_associated_uri) {
            const char *p = resp.p_associated_uri;
            const char *pick = strstr(p, "<tel:");
            if (!pick)
                pick = strstr(p, "<sip:");
            if (pick) {
                pick++;
                const char *gt = strchr(pick, '>');
                size_t l = gt ? (size_t)(gt - pick) : 0;
                if (l && l < sizeof(g_reg.public_id)) {
                    memcpy(g_reg.public_id, pick, l);
                    g_reg.public_id[l] = '\0';
                }
            }
        }
        g_reg.valid = 1;
        klog(LOG_INFO, "public identity from P-Associated-URI: %s",
             g_reg.public_id[0] ? "yes" : "NONE (calls blocked)");
        klog(LOG_INFO, "REGISTERED expires=%d service-route=%s",
             resp.expires > 0 ? resp.expires : 600000,
             resp.have_service_route ? "yes" : "none");
        return 0;
    }
    if (resp.status == 401) {
        /* Surface realm/stale (carrier-level metadata, never identity or
         * key material) so we can see whether the P-CSCF re-challenged
         * because of a realm mismatch or a digest mismatch. */
        const char *rp = strcasestr(rx, "realm=\"");
        char realm[96] = "";
        if (rp) {
            rp += 7;
            const char *re = strchr(rp, '"');
            size_t rl = re ? (size_t)(re - rp) : 0;
            if (rl >= sizeof(realm))
                rl = sizeof(realm) - 1;
            memcpy(realm, rp, rl);
            realm[rl] = '\0';
        }
        const char *sp = strcasestr(rx, "stale=");
        int stale = sp ? (!strncasecmp(sp + 6, "true", 4)) : -1;
        klog(LOG_INFO, "reg2 401 realm=%s stale=%d have_www_auth=%d",
             realm[0] ? realm : "(none)", stale, resp.have_www_auth);
    }
    snprintf(g_err, sizeof(g_err), "reg2 status %d", resp.status);
    g_state = UA_STATE_ERROR;
    return -29;
}


/* ---- MO call ----------------------------------------------------------- */

#define JOAN_RTP_PORT 40000

static void extract_to_tag(const char *msg, char *dst, size_t n)
{
    dst[0] = '\0';
    const char *t = strcasestr(msg, "\r\nTo:");
    if (!t)
        return;
    const char *eol = strstr(t + 2, "\r\n");
    const char *tag = strcasestr(t, "tag=");
    if (!tag || (eol && tag > eol))
        return;
    tag += 4;
    size_t i = 0;
    while (tag[i] && tag[i] != ';' && tag[i] != '\r' && tag[i] != '>' &&
           i + 1 < n) {
        dst[i] = tag[i];
        i++;
    }
    dst[i] = '\0';
}

int ua_call_invite(const char *dest)
{
    if (g_state != UA_STATE_REGISTERED || !g_reg.valid) {
        fail("call before register", 40);
        return -40;
    }
    if (!dest || !dest[0]) {
        fail_call("call no dest", 41);
        return -41;
    }

    /* Refuse to dial without a public identity rather than fall back to
     * the IMPI and leak the IMSI as caller ID. */
    if (!g_reg.public_id[0]) {
        fail_call("no public identity (IMPU); refusing to dial", 47);
        return -47;
    }

    sip_identity_t id = g_cfg->id;
    id.local_port = g_reg.port_c;
    id.contact_port = g_reg.port_s;
    id.pcscf_port = g_reg.pcscf_port_s;
    snprintf(id.impu, sizeof(id.impu), "%s", g_reg.public_id);

    memset(&g_call, 0, sizeof(g_call));
    sip_dialog_t dlg;
    memset(&dlg, 0, sizeof(dlg));
    char msg[SIP_MAX_MSG];
    int n = build_invite(msg, sizeof(msg), &id, dest,
                         g_reg.service_route, g_reg.sec_verify,
                         JOAN_RTP_PORT, &dlg);
    if (n <= 0) {
        fail_call("build invite", 42);
        return -42;
    }
    klog(LOG_INFO, "invite built (%d bytes)", n);

    int s = g_port_c_fd;
    if (s < 0) {
        fail_call("invite bind", 43);
        return -43;
    }
    if (sip_sendto(s, g_reg.pcscf_port_s, msg, (size_t)n) < 0) {
        fail_call("invite send", 44);
        return -44;
    }

    /* Provisional responses (100/180/183) precede the answer, so keep
     * reading until a final one arrives or the call setup timer runs out. */
    char rx[4096];
    char to_tag[64] = "";
    int rc = -45;
    long deadline = now_ms() + 30000;
    while (now_ms() < deadline) {
        int remain = (int)(deadline - now_ms());
        int r = sip_wait_recv(s, g_port_s_fd, rx, sizeof(rx),
                              remain > 0 ? remain : 1);
        if (r <= 0)
            break;
        sip_response_t resp;
        if (parse_response(rx, (size_t)r, &resp) != 0)
            continue;
        klog(LOG_INFO, "invite reply: %d %s", resp.status, resp.reason);
        if (resp.status >= 100 && resp.status < 200) {
            extract_to_tag(rx, to_tag, sizeof(to_tag));
            /* RFC 3262: a reliable 1xx (RSeq / 100rel) needs PRACK or
             * the UAS never sends 200. We saw 183 Session Progress
             * retransmits until CANCEL; GV never rang. */
            if (resp.rseq > 0) {
                const char *tgt = dest;
                char prack[SIP_MAX_MSG];
                int pn = build_prack(prack, sizeof(prack), &id, tgt, dest,
                                     g_reg.service_route, g_reg.sec_verify,
                                     &dlg, to_tag, resp.rseq);
                if (pn > 0 && sip_sendto(s, g_reg.pcscf_port_s, prack,
                                         (size_t)pn) == 0)
                    klog(LOG_INFO, "PRACK sent for 1xx rseq=%d", resp.rseq);
                else
                    klog(LOG_WARN, "PRACK failed rseq=%d", resp.rseq);
            }
            continue;
        }
        if (resp.status >= 200 && resp.status < 300) {
            extract_to_tag(rx, to_tag, sizeof(to_tag));
            snprintf(g_call.dest, sizeof(g_call.dest), "%s", dest);
            snprintf(g_call.to_tag, sizeof(g_call.to_tag), "%s", to_tag);
            /* RFC 3261: an in-dialog request goes to the remote target,
             * which is the Contact of the 2xx -- not the URI we originally
             * dialled. Sending BYE to the dialled AOR gets 481 Call/
             * Transaction Does Not Exist, which is what happened. */
            if (resp.have_contact) {
                const char *lt = strchr(resp.contact, '<');
                const char *gt = lt ? strchr(lt, '>') : NULL;
                if (lt && gt && (size_t)(gt - lt) < sizeof(g_call.target)) {
                    size_t tl = (size_t)(gt - lt - 1);
                    memcpy(g_call.target, lt + 1, tl);
                    g_call.target[tl] = '\0';
                } else {
                    snprintf(g_call.target, sizeof(g_call.target), "%s",
                             resp.contact);
                }
            } else {
                snprintf(g_call.target, sizeof(g_call.target), "%s", dest);
            }
            snprintf(g_call.route, sizeof(g_call.route), "%s",
                     resp.have_record_route ? resp.record_route
                                            : g_reg.service_route);
            klog(LOG_INFO, "dialog target captured (rr=%s)",
                 resp.have_record_route ? "yes" : "no");
            g_call.dlg = dlg;
            g_call.active = 1;
            g_call.se_sec = resp.session_expires;
            g_call.se_uac = 0;
            g_call.refresh_at = 0;
            if (resp.session_expires > 0) {
                /* RFC 4028 §7.2: a 2xx with Session-Expires starts the
                 * timer even if we did not advertise Supported: timer.
                 * refresher=uac (or absent while we are UAC) means we
                 * send UPDATE at half the interval. */
                if (!resp.se_refresher[0] ||
                    !strcasecmp(resp.se_refresher, "uac"))
                    g_call.se_uac = 1;
                klog(LOG_INFO, "session-expires=%d refresher=%s",
                     resp.session_expires,
                     resp.se_refresher[0] ? resp.se_refresher : "uac");
                if (g_call.se_uac) {
                    int half = resp.session_expires / 2;
                    if (half < 15)
                        half = resp.session_expires > 15 ? 15
                                                       : resp.session_expires - 1;
                    if (half < 1)
                        half = 1;
                    g_call.refresh_at = now_ms() + (long)half * 1000;
                }
            } else {
                klog(LOG_INFO, "session-expires=none");
            }
            char ack[SIP_MAX_MSG];
            int an = build_ack(ack, sizeof(ack), &id,
                               g_call.target[0] ? g_call.target : dest,
                               dest, g_call.route[0] ? g_call.route
                                                     : g_reg.service_route,
                               g_reg.sec_verify, &dlg, to_tag);
            if (an > 0 && sip_sendto(s, g_reg.pcscf_port_s, ack,
                                     (size_t)an) == 0)
                klog(LOG_INFO, "call answered, ACK sent");
            else
                klog(LOG_WARN, "call answered but ACK failed");
            media_from_sip(rx);
            rc = 0;
            break;
        }
        klog(LOG_ERR, "call rejected %d", resp.status);
        snprintf(g_err, sizeof(g_err), "invite status %d", resp.status);
        rc = -46;
        break;
    }
    if (rc == -45) {
        /* Withdraw the INVITE rather than leaving the far end ringing
         * until its own timer gives up. */
        char cancel[SIP_MAX_MSG];
        int cn = build_cancel(cancel, sizeof(cancel), &id, dest,
                              g_reg.service_route, g_reg.sec_verify, &dlg);
        if (cn > 0 && sip_sendto(s, g_reg.pcscf_port_s, cancel,
                                 (size_t)cn) == 0)
            klog(LOG_INFO, "setup timed out, CANCEL sent");
        else
            klog(LOG_WARN, "setup timed out, CANCEL failed");
        fail_call("invite no final reply", 45);
    }
    return rc;
}


int ua_call_is_active(void)
{
    return g_call.active ? 1 : 0;
}

int ua_call_hangup(void)
{
    if (!g_call.active) {
        klog(LOG_WARN, "hangup with no call up");
        return -1;
    }
    sip_identity_t id = g_cfg->id;
    id.local_port = g_reg.port_c;
    id.pcscf_port = g_reg.pcscf_port_s;

    char msg[SIP_MAX_MSG];
    int n = build_bye(msg, sizeof(msg), &id, g_call.target, g_call.dest,
                      g_call.route, g_reg.sec_verify,
                      &g_call.dlg, g_call.to_tag);
    if (n <= 0) {
        g_call.active = 0;
        return -1;
    }
    rtp_stop();
    int s = g_port_c_fd;
    if (s < 0) {
        g_call.active = 0;
        return -1;
    }
    int rc = -1;
    if (sip_sendto(s, g_reg.pcscf_port_s, msg, (size_t)n) == 0) {
        char rx[2048];
        int r = sip_wait_recv(s, g_port_s_fd, rx, sizeof(rx), 5000);
        if (r > 0) {
            sip_response_t resp;
            if (parse_response(rx, (size_t)r, &resp) == 0)
                klog(LOG_INFO, "bye reply: %d %s", resp.status, resp.reason);
        } else {
            klog(LOG_WARN, "bye sent, no reply");
        }
        rc = 0;
    }
    g_call.active = 0;
    return rc;
}


/* ---- Inbound (MT) calls ------------------------------------------------ */

int ua_inbound_fd(void)
{
    return (g_state == UA_STATE_REGISTERED) ? g_port_s_fd : -1;
}

int ua_media_poll_ms(void)
{
    return rtp_poll_ms();
}

void ua_media_tick(void)
{
    rtp_tick();
    if (!g_call.active || !g_call.se_uac || g_call.se_sec <= 0 ||
        g_call.refresh_at <= 0 || now_ms() < g_call.refresh_at)
        return;
    sip_identity_t id = g_cfg->id;
    id.local_port = g_reg.port_c;
    id.pcscf_port = g_reg.pcscf_port_s;
    char msg[SIP_MAX_MSG];
    int n = build_update(msg, sizeof(msg), &id,
                         g_call.target[0] ? g_call.target : g_call.dest,
                         g_call.dest, g_call.route, g_reg.sec_verify,
                         &g_call.dlg, g_call.to_tag, g_call.se_sec);
    if (n <= 0 || g_port_c_fd < 0) {
        klog(LOG_WARN, "session UPDATE build failed");
        g_call.refresh_at = now_ms() + 5000;
        return;
    }
    if (sip_sendto(g_port_c_fd, g_reg.pcscf_port_s, msg, (size_t)n) == 0) {
        g_call.dlg.cseq++;
        klog(LOG_INFO, "session UPDATE sent se=%d", g_call.se_sec);
        g_call.refresh_at = now_ms() + (long)(g_call.se_sec / 2) * 1000;
    } else {
        klog(LOG_WARN, "session UPDATE send failed");
        g_call.refresh_at = now_ms() + 2000;
    }
}

static void inbound_send(const char *pkt, size_t len)
{
    if (g_reply_fd < 0) {
        klog(LOG_WARN, "inbound_send no reply fd");
        return;
    }
    ssize_t w;
    if (g_reply_tcp)
        w = send(g_reply_fd, pkt, len, MSG_NOSIGNAL);
    else
        w = sendto(g_reply_fd, pkt, len, 0,
                   (const struct sockaddr *)&g_reply_peer, g_reply_plen);
    if (w < 0 || (size_t)w != len)
        klog(LOG_WARN, "inbound_send w=%zd len=%zu errno=%d tcp=%d",
             w, len, errno, g_reply_tcp);
}

static void handle_sip_request(char *rx, size_t r)
{
    /* Never log the request line: it carries the public identity. */
    char method[16];
    if (sip_request_method(rx, method, sizeof(method)) != 0) {
        int code = 0;
        if (!strncmp(rx, "SIP/2.0", 7))
            code = atoi(rx + 8);
        klog(LOG_INFO, "inbound datagram (%zu B): response %d", r, code);
        return;
    }
    klog(LOG_INFO, "inbound datagram (%zu B): %s", r, method);

    sip_identity_t id = g_cfg->id;
    id.local_port = g_reg.port_c;
    id.contact_port = g_reg.port_s;
    id.pcscf_port = g_reg.pcscf_port_s;
    snprintf(id.impu, sizeof(id.impu), "%s", g_reg.public_id);

    char resp[SIP_MAX_MSG];
    int n;

    if (!strcasecmp(method, "INVITE")) {
        klog(LOG_INFO, "inbound INVITE");
        char tag[16];
        mk_tag_public(tag, sizeof(tag));

        n = build_response(resp, sizeof(resp), rx, 100, "Trying", &id, NULL, NULL);
        if (n > 0) inbound_send(resp, (size_t)n);

        n = build_response(resp, sizeof(resp), rx, 180, "Ringing", &id, tag, NULL);
        if (n > 0) inbound_send(resp, (size_t)n);
        klog(LOG_INFO, "sent 100 + 180");

        const char *body = strstr(rx, "\r\n\r\n");
        char sdp[768];
        int sl = sdp_answer(sdp, sizeof(sdp), g_cfg->id.local_ip,
                            40000, body ? body + 4 : NULL);
        if (sl <= 0)
            return;
        n = build_response(resp, sizeof(resp), rx, 200, "OK", &id, tag, sdp);
        if (n > 0) {
            inbound_send(resp, (size_t)n);
            klog(LOG_INFO, "inbound call answered (200 OK sent)");
            media_from_sip(rx);
        }
        return;
    }

    if (!strcasecmp(method, "BYE")) {
        klog(LOG_INFO, "inbound BYE");
        n = build_response(resp, sizeof(resp), rx, 200, "OK", &id, NULL, NULL);
        if (n > 0) {
            inbound_send(resp, (size_t)n);
            klog(LOG_INFO, "BYE 200 sent %d B fd=%d tcp=%d",
                 n, g_reply_fd, g_reply_tcp);
        } else {
            klog(LOG_WARN, "BYE 200 build failed");
        }
        rtp_stop();
        g_call.active = 0;
        return;
    }

    if (!strcasecmp(method, "CANCEL")) {
        klog(LOG_INFO, "inbound CANCEL");
        n = build_response(resp, sizeof(resp), rx, 200, "OK", &id, NULL, NULL);
        if (n > 0) inbound_send(resp, (size_t)n);
        return;
    }

    if (!strcasecmp(method, "ACK"))
        return;

    if (!strcasecmp(method, "OPTIONS")) {
        n = build_response(resp, sizeof(resp), rx, 200, "OK", &id, NULL, NULL);
        if (n > 0) inbound_send(resp, (size_t)n);
        return;
    }

    if (!strcasecmp(method, "UPDATE")) {
        klog(LOG_INFO, "inbound UPDATE");
        n = build_response(resp, sizeof(resp), rx, 200, "OK", &id, NULL, NULL);
        if (n > 0) inbound_send(resp, (size_t)n);
        return;
    }

    klog(LOG_INFO, "inbound %.12s (not handled)", method);
    n = build_response(resp, sizeof(resp), rx, 501, "Not Implemented",
                       &id, NULL, NULL);
    if (n > 0) inbound_send(resp, (size_t)n);
}

void ua_select_handle(fd_set *rfds)
{
    if (g_tcp_s_fd >= 0 && FD_ISSET(g_tcp_s_fd, rfds))
        tcp_accept(g_tcp_s_fd);
    if (g_tcp_c_fd >= 0 && FD_ISSET(g_tcp_c_fd, rfds))
        tcp_accept(g_tcp_c_fd);

    for (int i = 0; i < TCP_MAX; i++) {
        if (g_tcp[i].fd < 0 || !FD_ISSET(g_tcp[i].fd, rfds))
            continue;
        ssize_t n = recv(g_tcp[i].fd, g_tcp[i].buf + g_tcp[i].len,
                         TCP_BUFSZ - 1 - g_tcp[i].len, 0);
        if (n <= 0) {
            close(g_tcp[i].fd);
            g_tcp[i].fd = -1;
            g_tcp[i].len = 0;
            continue;
        }
        g_tcp[i].len += (size_t)n;
        g_tcp[i].buf[g_tcp[i].len] = '\0';
        char rx[4096];
        int got;
        while ((got = sip_extract_one(g_tcp[i].buf, &g_tcp[i].len,
                                      rx, sizeof(rx))) == 1) {
            g_reply_fd = g_tcp[i].fd;
            g_reply_tcp = 1;
            handle_sip_request(rx, strlen(rx));
        }
    }

    int udp[2] = { g_port_s_fd, g_port_c_fd };
    for (int i = 0; i < 2; i++) {
        int fd = udp[i];
        if (fd < 0 || !FD_ISSET(fd, rfds))
            continue;
        char rx[4096];
        g_reply_plen = sizeof(g_reply_peer);
        ssize_t r = recvfrom(fd, rx, sizeof(rx) - 1, 0,
                             (struct sockaddr *)&g_reply_peer, &g_reply_plen);
        if (r <= 0)
            continue;
        rx[r] = '\0';
        g_reply_fd = fd;
        g_reply_tcp = 0;
        handle_sip_request(rx, (size_t)r);
    }
    if (rtp_fd() >= 0 && FD_ISSET(rtp_fd(), rfds))
        rtp_tick();
    if (rtp_rtcp_fd() >= 0 && FD_ISSET(rtp_rtcp_fd(), rfds))
        rtp_tick();
}

void ua_handle_inbound(void)
{
    /* Legacy entry: drain the server UDP socket. ctl_serve now uses
     * ua_select_handle on the full set. */
    if (g_port_s_fd < 0)
        return;
    char rx[4096];
    g_reply_plen = sizeof(g_reply_peer);
    ssize_t r = recvfrom(g_port_s_fd, rx, sizeof(rx) - 1, 0,
                         (struct sockaddr *)&g_reply_peer, &g_reply_plen);
    if (r <= 0)
        return;
    rx[r] = '\0';
    g_reply_fd = g_port_s_fd;
    g_reply_tcp = 0;
    handle_sip_request(rx, (size_t)r);
}
