/* ctl.c — loopback control server. One request line -> one response.
 *
 * All traffic originates Java-side; there is no reverse channel.
 * Commands:
 *   ID <impi> <impu> <domain> <imei>
 *   NET <local_ip> <local_port> <pcscf> <pcscf_port>
 *   REG1                          -> CHALLENGE <nonce_b64> | ERR <why>
 *   REG2 <res_hex> <ck_hex> <ik_hex> -> STATE registered | ERR <why>
 *   STATUS                        -> INFO STATE=<n> PCSCF=<ip> ERROR=<e>
 *   KEEPALIVE                     -> OK keepalive
 */
#define _GNU_SOURCE
#include "ctl.h"

#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "config.h"
#include "ua.h"
#include "util.h"

static ua_config_t *g_cfg;
static char g_last_error[192];
static int g_registering;

void ctl_init(ua_config_t *cfg)
{
    g_cfg = cfg;
    g_last_error[0] = '\0';
}

static void respond(int fd, const char *code, const char *msg)
{
    char out[CTL_MAX_LINE + 64];
    snprintf(out, sizeof(out), "%s %s\n", code, msg ? msg : "");
    size_t n = strlen(out);
    ssize_t w = send(fd, out, n, MSG_NOSIGNAL);
    (void)w;
}

static int split_fields(char *line, char **f, int maxf)
{
    int n = 0;
    char *p = line;
    while (n < maxf) {
        while (*p == ' ')
            p++;
        if (!*p)
            break;
        f[n++] = p;
        char *sp = strchr(p, ' ');
        if (!sp)
            break;
        *sp = '\0';
        p = sp + 1;
    }
    return n;
}

static void handle_line(int fd, char *line)
{
    char *fields[8];
    int nf = split_fields(line, fields, 8);
    if (nf == 0) {
        respond(fd, "ERR", "empty");
        return;
    }
    const char *verb = fields[0];

    if (!strcmp(verb, "KEEPALIVE")) {
        respond(fd, "OK", "keepalive");
        return;
    }

    if (!strcmp(verb, "STATUS")) {
        char st[CTL_MAX_LINE];
        char sink[160];
        snprintf(st, sizeof(st),
                 "STATE=%d PCSCF=%.60s ERROR=%.100s LOG=%.120s",
                 (int)ua_state(),
                 g_cfg->id.pcscf[0] ? g_cfg->id.pcscf : "-",
                 ua_errstr(),
                 klog_sink_status(sink, sizeof(sink)));
        respond(fd, "INFO", st);
        return;
    }

    if (!strcmp(verb, "ID") && nf >= 2 && nf <= 5) {
        char kv[600];
        snprintf(kv, sizeof(kv), "IMPI=%s", fields[1]);
        if (cfg_apply_line(g_cfg, kv) != 0) {
            respond(fd, "ERR", "bad impi");
            return;
        }
        snprintf(kv, sizeof(kv), "IMPU=%s", nf > 2 ? fields[2] : "");
        cfg_apply_line(g_cfg, kv);
        snprintf(kv, sizeof(kv), "DOMAIN=%s", nf > 3 ? fields[3] : "");
        cfg_apply_line(g_cfg, kv);
        snprintf(kv, sizeof(kv), "IMEI=%s", nf > 4 ? fields[4] : "");
        cfg_apply_line(g_cfg, kv);
        klog(LOG_INFO, "id configured have_id=%d",
             g_cfg->id.have_id ? 1 : 0);
        respond(fd, "OK", "id");
        return;
    }

    if (!strcmp(verb, "NET") && nf >= 5) {
        char kv[256];
        int bad = 0;
        snprintf(kv, sizeof(kv), "LOCAL_IP=%s", fields[1]);
        bad |= cfg_apply_line(g_cfg, kv) < 0;
        snprintf(kv, sizeof(kv), "LOCAL_PORT=%s", fields[2]);
        bad |= cfg_apply_line(g_cfg, kv) < 0;
        snprintf(kv, sizeof(kv), "PCSCF=%s", fields[3]);
        bad |= cfg_apply_line(g_cfg, kv) < 0;
        snprintf(kv, sizeof(kv), "PCSCF_PORT=%s", fields[4]);
        bad |= cfg_apply_line(g_cfg, kv) < 0;
        /* Optional 6th field: IMS PDN interface name for SO_BINDTODEVICE. */
        if (nf >= 6) {
            snprintf(kv, sizeof(kv), "IFACE=%s", fields[5]);
            bad |= cfg_apply_line(g_cfg, kv) < 0;
        }
        if (bad) {
            respond(fd, "ERR", "net fields");
            return;
        }
        klog(LOG_INFO, "net configured");
        respond(fd, "OK", "net");
        return;
    }

    if (!strcmp(verb, "REG1")) {
        if (g_registering) {
            respond(fd, "ERR", "busy");
            return;
        }
        g_registering = 1;
        char nonce[512];
        int rc = ua_register_stage1(nonce, sizeof(nonce));
        g_registering = 0;
        if (rc == 0)
            respond(fd, "CHALLENGE", nonce);
        else
            respond(fd, "ERR", ua_errstr());
        return;
    }

    if (!strcmp(verb, "REG2") && nf == 4) {
        uint8_t res[16], ck[16], ik[16];
        memset(res, 0, sizeof(res));
        /* RES may be 8 bytes (64-bit AKA RES, as this ISIM returns) or 16.
         * hex_decode fills the front; the rest stays zero. CK/IK must be
         * exactly 16. */
        size_t reslen = hex_decode(fields[1], res, 16);
        if ((reslen != 8 && reslen != 16) ||
            hex_decode(fields[2], ck, 16) != 16 ||
            hex_decode(fields[3], ik, 16) != 16) {
            respond(fd, "ERR", "key hex malformed");
            return;
        }
        if (g_registering) {
            respond(fd, "ERR", "busy");
            return;
        }
        g_registering = 1;
        int rc = ua_register_stage2(res, reslen, ck, ik);
        g_registering = 0;
        if (rc == 0) {
            g_last_error[0] = '\0';
            respond(fd, "STATE", "registered");
        } else
            respond(fd, "ERR", ua_errstr());
        return;
    }

    respond(fd, "ERR", "unknown verb or arity");
}

static int listen_unix_abstract(void)
{
    int ls = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (ls < 0) {
        klog(LOG_ERR, "ctl unix socket failed errno=%d", errno);
        return -1;
    }
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    sa.sun_path[0] = '\0';
    strncpy(sa.sun_path + 1, CTL_SOCK_ABSTRACT_NAME,
            sizeof(sa.sun_path) - 2);
    socklen_t salen = offsetof(struct sockaddr_un, sun_path) +
                      1 + strlen(CTL_SOCK_ABSTRACT_NAME);
    if (bind(ls, (struct sockaddr *)&sa, salen) < 0) {
        klog(LOG_ERR, "ctl unix bind failed errno=%d", errno);
        close(ls);
        return -1;
    }
    if (listen(ls, CTL_BACKLOG) < 0) {
        klog(LOG_ERR, "ctl unix listen failed errno=%d", errno);
        close(ls);
        return -1;
    }
    klog(LOG_INFO, "ctl listening abstract @%s", CTL_SOCK_ABSTRACT_NAME);
    return ls;
}

/* Peer authentication for the ctl channel.
 *
 * The ctl verbs include REG2, which injects AKA keys, and NET/ID, which
 * redirect where SIP is sent, so the peer must be identified before its
 * line is executed.
 *
 * An earlier revision added a 127.0.0.1 listener, which any app holding
 * INTERNET could reach. Authenticating that peer required resolving its
 * uid from /proc/net/tcp, and that file carries the proc_net_tcp_udp
 * label: opening it from domain netmgrd returns EACCES (measured on
 * joan). An unauthenticatable control channel that injects key material
 * is not worth keeping, so the TCP listener is gone.
 *
 * The unix socket does not have that problem: SO_PEERCRED reports the
 * peer's uid from the kernel with no filesystem access at all.
 */
#define CTL_ALLOWUID_PATH "/data/vendor/netmgr/joan-ims.allowuid"

/* Allowlist is uid 0 plus whatever CTL_ALLOWUID_PATH lists, one uid per
 * line. The IMS app runs as an ordinary per-app uid assigned at install
 * time, so it cannot be compiled in; the installer writes it there. */
static int uid_allowed(uid_t uid)
{
    if (uid == 0)
        return 1;
    FILE *f = fopen(CTL_ALLOWUID_PATH, "re");
    if (!f) {
        klog(LOG_ERR, "ctl allowlist %s unreadable errno=%d",
             CTL_ALLOWUID_PATH, errno);
        return 0;
    }
    char line[64];
    int ok = 0;
    while (fgets(line, sizeof(line), f)) {
        unsigned v;
        if (sscanf(line, "%u", &v) == 1 && (uid_t)v == uid) {
            ok = 1;
            break;
        }
    }
    fclose(f);
    return ok;
}

/* 0 if the peer may issue ctl verbs, -1 otherwise. Fails closed. */
static int ctl_authenticate_unix(int c)
{
    struct ucred cr;
    socklen_t crlen = sizeof(cr);
    if (getsockopt(c, SOL_SOCKET, SO_PEERCRED, &cr, &crlen) < 0) {
        klog(LOG_ERR, "ctl SO_PEERCRED failed errno=%d", errno);
        return -1;
    }
    if (!uid_allowed(cr.uid)) {
        klog(LOG_WARN, "ctl refused uid %u pid %d (not in %s)",
             (unsigned)cr.uid, (int)cr.pid, CTL_ALLOWUID_PATH);
        return -1;
    }
    return 0;
}

static void handle_client(int c)
{
    char buf[CTL_MAX_LINE + 1];
    ssize_t r = recv(c, buf, CTL_MAX_LINE, 0);
    if (r > 0) {
        buf[r] = '\0';
        for (ssize_t i = 0; i < r; i++)
            if (buf[i] == '\n' || buf[i] == '\r')
                buf[i] = '\0';
        handle_line(c, buf);
    }
    close(c);
}

int ctl_serve(void)
{
    int ls = listen_unix_abstract();
    if (ls < 0)
        return -1;

    for (;;) {
        int c = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
        if (c < 0) {
            if (errno == EINTR)
                continue;
            sleep(1);
            continue;
        }
        if (ctl_authenticate_unix(c) == 0)
            handle_client(c);
        else
            close(c);
    }
    return 0;
}
