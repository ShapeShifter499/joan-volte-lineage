/* ua.c — two-stage REGISTER sequencer over UDP on the IMS PDN. */
#define _GNU_SOURCE

#include "ua.h"

#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "sip.h"
#include "util.h"
#include "xfrm.h"

static ua_config_t *g_cfg;
static int g_port_s_fd = -1;
static int g_port_s_bound = -1;
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

static int sip_socket_bind(int local_port)
{
    int fam = strchr(g_cfg->id.local_ip, ':') ? AF_INET6 : AF_INET;
    int s = socket(fam, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0)
        return -1;
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

static int sip_sendto(int s, int dport, const char *pkt, size_t len);

/* Send on `s`, then wait for a reply on EITHER `s` or `alt`.
 *
 * The P-CSCF answers a protected REGISTER on the UE's protected SERVER
 * port, not the client port the request went out from. Measured on joan:
 * the inbound SA for pcscf-port-c -> ue-port-s counted a decrypted 1217
 * byte packet while the daemon sat blocked on the port-c socket and timed
 * out. Waiting on only the sending socket loses every response.
 */
static int sip_send_recv_dual(int s, int alt, int dport,
                              const char *pkt, size_t len,
                              char *rx, size_t rxlen, int timeout_ms)
{
    if (sip_sendto(s, dport, pkt, len) < 0)
        return -1;

    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;

    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(s, &rfds);
    int maxfd = s;
    if (alt >= 0) {
        FD_SET(alt, &rfds);
        if (alt > maxfd)
            maxfd = alt;
    }
    int rc = select(maxfd + 1, &rfds, NULL, NULL, &tv);
    if (rc <= 0)
        return -1;

    int from = FD_ISSET(s, &rfds) ? s : alt;
    ssize_t r = recv(from, rx, rxlen - 1, 0);
    if (r <= 0)
        return -1;
    rx[r] = '\0';
    klog(LOG_INFO, "reply arrived on %s port socket",
         from == s ? "client" : "server");
    return (int)r;
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

    /* RFC 3329 / TS 33.203: the UE holds BOTH protected ports open --
     * port-c to send from, port-s to receive requests on. We only ever
     * bound port-c, so the UE was never reachable on the server port the
     * Security-Client advertised. Hold it for the life of the process. */
    /* The security parameters are regenerated per registration attempt, so
     * port-s changes each cycle; a socket bound once holds a stale port and
     * the P-CSCF's reply lands on nothing. Rebind whenever it moves. */
    if (g_port_s_fd >= 0 && g_port_s_bound != (int)ue_sec.port_s) {
        close(g_port_s_fd);
        g_port_s_fd = -1;
    }
    if (g_port_s_fd < 0) {
        g_port_s_fd = sip_socket_bind((int)ue_sec.port_s);
        if (g_port_s_fd < 0) {
            klog(LOG_WARN, "could not hold protected server port %u",
                 ue_sec.port_s);
        } else {
            g_port_s_bound = (int)ue_sec.port_s;
            klog(LOG_INFO, "holding protected server port %u", ue_sec.port_s);
        }
    }

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
    id.pcscf_port = (int)pcscf_sec.port_s;
    char msg[SIP_MAX_MSG];
    int n = build_register(msg, sizeof(msg), &id, &g_txn, 2, &g_ch,
                           res, res_len, ck, ik);
    if (n <= 0) {
        fail("build reg2", 25);
        return -25;
    }
    klog(LOG_INFO, "reg2 built (%d bytes)", n);

    int s = sip_socket_bind((int)ue_sec.port_c);
    if (s < 0) {
        fail("bind2", 26);
        return -26;
    }
    klog(LOG_INFO, "reg2 sending %u -> %u (protected)",
         ue_sec.port_c, pcscf_sec.port_s);
    char rx[4096];
    int r = sip_send_recv_dual(s, g_port_s_fd, (int)pcscf_sec.port_s,
                               msg, (size_t)n, rx, sizeof(rx), 8000);
    close(s);
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
        klog(LOG_INFO, "REGISTERED expires=%d",
             resp.expires > 0 ? resp.expires : 600000);
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
