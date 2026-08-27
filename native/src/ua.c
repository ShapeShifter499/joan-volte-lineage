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

static int sip_socket_bind(void)
{
    int fam = strchr(g_cfg->id.local_ip, ':') ? AF_INET6 : AF_INET;
    int s = socket(fam, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0)
        return -1;
    if (fam == AF_INET6) {
        int v6only = 1;
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
    }
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    if (fam == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&ss;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)g_cfg->id.local_port);
        if (inet_pton(AF_INET6, g_cfg->id.local_ip, &a->sin6_addr) != 1) {
            close(s);
            return -1;
        }
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&ss;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)g_cfg->id.local_port);
        if (inet_pton(AF_INET, g_cfg->id.local_ip, &a->sin_addr) != 1) {
            close(s);
            return -1;
        }
    }
    if (bind(s, (struct sockaddr *)&ss, sizeof(ss)) < 0) {
        klog(LOG_ERR, "sip bind port %d failed errno=%d",
             g_cfg->id.local_port, errno);
        close(s);
        return -1;
    }
    return s;
}

static int sip_send_recv(int s, const char *pkt, size_t len,
                         char *rx, size_t rxlen, int timeout_ms)
{
    struct sockaddr_storage dst;
    memset(&dst, 0, sizeof(dst));
    socklen_t dlen;
    int fam = strchr(g_cfg->id.pcscf, ':') ? AF_INET6 : AF_INET;
    if (fam == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&dst;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)g_cfg->id.pcscf_port);
        dlen = sizeof(*a);
        if (inet_pton(AF_INET6, g_cfg->id.pcscf, &a->sin6_addr) != 1)
            return -1;
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&dst;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)g_cfg->id.pcscf_port);
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
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    ssize_t r = recv(s, rx, rxlen - 1, 0);
    if (r <= 0)
        return -1;
    rx[r] = '\0';
    return (int)r;
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
                           NULL, NULL, NULL);
    if (n <= 0) {
        fail("build reg1", 2);
        return -2;
    }
    klog(LOG_INFO, "reg1 built (%d bytes) UDP", n);

    g_state = UA_STATE_TRYING;
    int s = sip_socket_bind();
    if (s < 0) {
        fail("bind", 3);
        return -3;
    }
    char rx[4096];
    int r = sip_send_recv(s, msg, (size_t)n, rx, sizeof(rx), 8000);
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

int ua_register_stage2(const uint8_t *res, const uint8_t *ck,
                       const uint8_t *ik)
{
    if (g_state != UA_STATE_CHALLENGED || !g_ch.have_nonce) {
        fail("stage2 without challenge", 20);
        return -20;
    }
    if (!res || !ck || !ik) {
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
                       NULL, NULL, NULL);
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

    /* Kernel IPsec: SA+policy set from CK/IK (UDP+TCP selectors). */
    xfrm_status_t xs;
    int xr = xfrm_install(g_cfg->id.local_ip, g_cfg->id.pcscf,
                          &ue_sec, &pcscf_sec, ck, ik, &xs);
    if (xr != 0)
        klog(LOG_WARN, "xfrm install partial rc=%d (continuing)", xr);

    /* Protected REGISTER. */
    sip_identity_t id = g_cfg->id;
    char msg[SIP_MAX_MSG];
    int n = build_register(msg, sizeof(msg), &id, &g_txn, 2, &g_ch,
                           res, ck, ik);
    if (n <= 0) {
        fail("build reg2", 25);
        return -25;
    }
    klog(LOG_INFO, "reg2 built (%d bytes)", n);

    int s = sip_socket_bind();
    if (s < 0) {
        fail("bind2", 26);
        return -26;
    }
    char rx[4096];
    int r = sip_send_recv(s, msg, (size_t)n, rx, sizeof(rx), 8000);
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
    snprintf(g_err, sizeof(g_err), "reg2 status %d", resp.status);
    g_state = UA_STATE_ERROR;
    return -29;
}
