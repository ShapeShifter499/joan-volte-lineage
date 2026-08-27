#include "sip.h"
#include "md5.h"
#include "util.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void mk_branch(char *dst, size_t n)
{
    snprintf(dst, n, "z9hG4bK%08x%08x",
             (uint32_t)(rand_u64() >> 32), (uint32_t)rand_u64());
}

static void mk_tag(char *dst, size_t n)
{
    snprintf(dst, n, "%012llx",
             (unsigned long long)(rand_u64() & 0xffffffffffffULL));
}

static void mk_call_id(char *dst, size_t n)
{
    snprintf(dst, n, "%08x-%04x-%04x-%04x-%06x%04x",
             (uint32_t)(rand_u64() >> 32),
             (unsigned)(rand_u64() & 0xffff),
             (unsigned)(rand_u64() & 0xffff),
             (unsigned)(rand_u64() & 0xffff),
             (unsigned)(rand_u64() & 0xffffffu),
             (unsigned)(rand_u64() & 0xffff));
}

static void bracket(const char *ip, char *dst, size_t n)
{
    if (strchr(ip, ':'))
        snprintf(dst, n, "[%s]", ip);
    else
        snprintf(dst, n, "%s", ip);
}

static void imei_instance(const char *imei, char *dst, size_t n)
{
    char digits[24] = { 0 };
    int k = 0;
    for (const char *p = imei ? imei : ""; *p && k < 23; p++)
        if (*p >= '0' && *p <= '9')
            digits[k++] = *p;
    if (k >= 14) {
        char last = (k > 14) ? digits[14] : '0';
        snprintf(dst, n, "%.8s-%.6s-%c", digits, digits + 8, last);
    } else {
        snprintf(dst, n, "00000000-000000-0");
    }
}

typedef struct {
    char *p;
    size_t left;
} appender_t;

static void app(appender_t *a, const char *fmt, ...)
        __attribute__((format(printf, 2, 3)));

static void app(appender_t *a, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int w = vsnprintf(a->p, a->left, fmt, ap);
    va_end(ap);
    if (w <= 0)
        return;
    if ((size_t)w >= a->left)
        w = (int)(a->left - 1);
    a->p += w;
    a->left -= (size_t)w;
}

void txn_new(sip_txn_t *t, const sip_identity_t *id, sec_params_t mine)
{
    memset(t, 0, sizeof(*t));
    mk_call_id(t->call_id, sizeof(t->call_id));
    mk_tag(t->from_tag, sizeof(t->from_tag));
    t->mine = mine;
}

int aka_digest_response_hex(
        const char *username,
        const char *realm,
        const char *method,
        const char *uri,
        const char *nonce_b64,
        const uint8_t *res,
        const char *qop,
        const char *nc,
        const char *cnonce,
        char *out_hex /* 33 */)
{
    /* RFC 3310 AKAv1-MD5: password = raw RES bytes. */
    md5_ctx c1;
    md5_init(&c1);
    md5_update(&c1, username, strlen(username));
    md5_update(&c1, ":", 1);
    md5_update(&c1, realm, strlen(realm));
    md5_update(&c1, ":", 1);
    md5_update(&c1, res, 16);
    uint8_t ha1[16];
    md5_final(&c1, ha1);

    md5_ctx c2;
    md5_init(&c2);
    md5_update(&c2, method, strlen(method));
    md5_update(&c2, ":", 1);
    md5_update(&c2, uri, strlen(uri));
    uint8_t ha2[16];
    md5_final(&c2, ha2);

    char h1[33], h2[33];
    hex_encode(ha1, 16, h1, sizeof(h1));
    hex_encode(ha2, 16, h2, sizeof(h2));

    md5_ctx c3;
    md5_init(&c3);
    md5_update(&c3, h1, 32);
    md5_update(&c3, ":", 1);
    md5_update(&c3, nonce_b64, strlen(nonce_b64));
    if (qop) {
        md5_update(&c3, ":", 1);
        md5_update(&c3, nc, strlen(nc));
        md5_update(&c3, ":", 1);
        md5_update(&c3, cnonce, strlen(cnonce));
        md5_update(&c3, ":", 1);
        md5_update(&c3, qop, strlen(qop));
    }
    md5_update(&c3, ":", 1);
    md5_update(&c3, h2, 32);
    uint8_t resp[16];
    md5_final(&c3, resp);
    hex_encode(resp, 16, out_hex, 33);
    return 0;
}

static void security_client_line(const sec_params_t *m, char *dst, size_t n)
{
    joan_security_client_value(m, dst, n);
}

/* Proven offer: hmac-sha-1-96 first, hmac-md5-96 alternate. */
void joan_security_client_value(const sec_params_t *m, char *dst, size_t n)
{
    snprintf(dst, n,
             "ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp; mod=trans; "
             "spi-c=%u; spi-s=%u; port-c=%u; port-s=%u, "
             "ipsec-3gpp; alg=hmac-md5-96; ealg=aes-cbc; prot=esp; mod=trans; "
             "spi-c=%u; spi-s=%u; port-c=%u; port-s=%u",
             m->spi_c, m->spi_s, m->port_c, m->port_s,
             m->spi_c, m->spi_s, m->port_c, m->port_s);
}

/* Public entry also used by host tests. */
void joan_sec_params_default(sec_params_t *out)
{
    out->spi_c = 256u + (uint32_t)(rand_u64() % (0x7fffffffu - 255u));
    out->spi_s = 256u + (uint32_t)(rand_u64() % (0x7fffffffu - 255u));
    unsigned base = 10000u + rand_below(20000u);
    out->port_c = base;
    out->port_s = base + 1000u;
}

int build_register(
        char *out, size_t outlen,
        const sip_identity_t *id,
        sip_txn_t *txn,
        int cseq,
        const sip_challenge_t *ch,
        const uint8_t *res,
        const uint8_t *ck,
        const uint8_t *ik)
{
    (void)ck;
    (void)ik;
    const char *public_id = id->impu[0] ? id->impu : id->impi;
    char aor[300];
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char request_uri[160];
    snprintf(request_uri, sizeof(request_uri), "sip:%s", id->realm);

    char via_host[80], contact_host[80];
    bracket(id->local_ip, via_host, sizeof(via_host));
    bracket(id->local_ip, contact_host, sizeof(contact_host));

    mk_branch(txn->branch, sizeof(txn->branch));

    char auth_line[1400];
    if (res != NULL && ch != NULL && ch->have_nonce) {
        char resp_hex[33];
        aka_digest_response_hex(
            id->impi, id->realm, "REGISTER", request_uri,
            ch->nonce_b64, res, "auth", "00000001", "cnonce01", resp_hex);
        snprintf(auth_line, sizeof(auth_line),
                 "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", "
                 "uri=\"%s\", response=\"%s\", algorithm=%s, "
                 "qop=auth, nc=00000001, cnonce=\"cnonce01\", "
                 "integrity-protected=yes",
                 id->impi, id->realm, ch->nonce_b64, request_uri,
                 resp_hex, ch->algorithm);
    } else {
        snprintf(auth_line, sizeof(auth_line),
                 "Digest username=\"%s\", realm=\"%s\", nonce=\"\", "
                 "uri=\"%s\", response=\"\", algorithm=AKAv1-MD5",
                 id->impi, id->realm, request_uri);
    }

    char inst[40];
    imei_instance(id->imei, inst, sizeof(inst));

    char sec_cli[1024];
    security_client_line(&txn->mine, sec_cli, sizeof(sec_cli));

    /* Contact user part: strip scheme, stop at '@'. */
    const char *cu = aor;
    if (!strncmp(cu, "sip:", 4)) cu += 4;
    if (!strncmp(cu, "tel:", 4)) cu += 4;
    char contact_user[128];
    {
        const char *at = strchr(cu, '@');
        size_t ul = at ? (size_t)(at - cu) : strlen(cu);
        if (ul >= sizeof(contact_user))
            ul = sizeof(contact_user) - 1;
        memcpy(contact_user, cu, ul);
        contact_user[ul] = '\0';
    }

    appender_t a = { out, outlen };
    app(&a, "REGISTER %s SIP/2.0\r\n", request_uri);
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        via_host, id->local_port, txn->branch);
    app(&a, "Max-Forwards: 70\r\n");
    app(&a, "From: <%s>;tag=%s\r\n", aor, txn->from_tag);
    app(&a, "To: <%s>\r\n", aor);
    app(&a, "Call-ID: %s\r\n", txn->call_id);
    app(&a, "CSeq: %d REGISTER\r\n", cseq);
    app(&a, "Contact: <sip:%s@%s:%d>;+sip.instance=\"<urn:gsma:imei:%s>\""
        ";+g.3gpp.icsi-ref=\"urn%%3Aurn-7%%3A3gpp-service.ims.icsi.mmtel\""
        ";+g.3gpp.smsip;audio\r\n",
        contact_user, contact_host, id->local_port, inst);
    app(&a, "Expires: 600000\r\n");
    app(&a, "Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, OPTIONS, PRACK\r\n");
    app(&a, "Supported: path, sec-agree\r\n");
    app(&a, "Require: sec-agree\r\n");
    app(&a, "Proxy-Require: sec-agree\r\n");
    app(&a, "Security-Client: %s\r\n", sec_cli);
    app(&a, "P-Access-Network-Info: 3GPP-E-UTRAN-FDD\r\n");
    app(&a, "P-Preferred-Identity: <%s>\r\n", aor);
    if (ch && ch->have_sec_server && ch->sec_server[0])
        app(&a, "Security-Verify: %s\r\n", ch->sec_server);
    app(&a, "Authorization: %s\r\n", auth_line);
    app(&a, "Content-Length: 0\r\n");
    app(&a, "\r\n");
    return (int)(outlen - a.left);
}

/* ---- response parsing ---- */

static int header_value(const char *msg, const char *name,
                        char *dst, size_t dstlen)
{
    size_t nl = strlen(name);
    const char *p = msg;
    while (*p) {
        if (!strncasecmp(p, name, nl) && p[nl] == ':') {
            p += nl + 1;
            while (*p == ' ' || *p == '\t')
                p++;
            size_t i = 0;
            while (*p && *p != '\r' && *p != '\n' && i + 1 < dstlen)
                dst[i++] = *p++;
            dst[i] = '\0';
            return (int)i;
        }
        const char *eol = strchr(p, '\n');
        if (!eol)
            break;
        p = eol + 1;
    }
    return -1;
}

int parse_response(const char *msg, size_t len, sip_response_t *r)
{
    memset(r, 0, sizeof(*r));
    r->expires = -1;
    if (len < 12 || strncmp(msg, "SIP/2.0 ", 8))
        return -1;
    r->status = atoi(msg + 8);
    const char *sp = msg + 8;
    while (*sp && *sp != ' ')
        sp++;
    while (*sp == ' ')
        sp++;
    size_t ri = 0;
    while (*sp && *sp != '\r' && *sp != '\n' && ri + 1 < sizeof(r->reason))
        r->reason[ri++] = *sp++;
    r->reason[ri] = '\0';

    r->have_www_auth = header_value(msg, "WWW-Authenticate",
                                    r->www_authenticate,
                                    sizeof(r->www_authenticate)) >= 0;
    r->have_sec_server = header_value(msg, "Security-Server",
                                      r->security_server,
                                      sizeof(r->security_server)) >= 0;
    r->have_service_route = header_value(msg, "Service-Route",
                                         r->service_route,
                                         sizeof(r->service_route)) >= 0;
    char exp[32];
    if (header_value(msg, "Expires", exp, sizeof(exp)) >= 0)
        r->expires = atoi(exp);
    header_value(msg, "Date", r->date_hdr, sizeof(r->date_hdr));
    return 0;
}
