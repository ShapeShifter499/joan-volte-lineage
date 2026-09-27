#include "sip.h"
#include "md5.h"
#include "util.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

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
        size_t res_len,
        const char *qop,
        const char *nc,
        const char *cnonce,
        char *out_hex /* 33 */)
{
    /* RFC 3310 AKAv1-MD5: password = raw RES bytes, exactly nres long
     * (8 for a 64-bit RES, 16 for 128-bit — zero padding would corrupt
     * the digest). */
    md5_ctx c1;
    md5_init(&c1);
    md5_update(&c1, username, strlen(username));
    md5_update(&c1, ":", 1);
    md5_update(&c1, realm, strlen(realm));
    md5_update(&c1, ":", 1);
    md5_update(&c1, res, res_len);
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
        size_t res_len,
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
            ch->nonce_b64, res, res_len,
            "auth", "00000001", "cnonce01", resp_hex);
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
    /* Contact carries the port the network should send REQUESTS to, which
     * RFC 3329 puts on the protected server port -- Via keeps the client
     * port for responses. Advertising port-c here means an inbound INVITE
     * is addressed to a port we do not accept requests on. */
    app(&a, "Contact: <sip:%s@%s:%d>;+sip.instance=\"<urn:gsma:imei:%s>\""
        ";+g.3gpp.icsi-ref=\"urn%%3Aurn-7%%3A3gpp-service.ims.icsi.mmtel\""
        ";+g.3gpp.smsip;audio\r\n",
        contact_user, contact_host,
        id->contact_port ? id->contact_port : id->local_port, inst);
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

/* RFC 3261 12.1.2: a UAC's route set is every Record-Route URI carried by
 * the response, taken in REVERSE order. Proxies prepend as the request goes
 * out, so the response lists them far-end first, and the ACK has to leave
 * here with our own P-CSCF on top.
 *
 * header_value() returns only the first matching header, so the route set
 * was both truncated and unreversed: the ACK was addressed past the P-CSCF
 * and the far end never saw it. It then retransmitted its 200 every 4s (T2)
 * and tore the call down on timer H at ~32s -- the "Google drops it" symptom.
 *
 * Commas inside <> or "" belong to a URI and are not separators. Returns the
 * length written and reports the number of URIs seen through *count. */
static int route_set_collect(const char *msg, char *dst, size_t dstlen,
                             int *count, int reverse)
{
    const char *ent[16];
    size_t elen[16];
    int n = 0;
    const char *name = "Record-Route";
    const size_t nl = 12;

    const char *p = msg;
    while (n < 16 && (p = strcasestr(p, name)) != NULL) {
        int at_line_start = (p == msg) ||
                            (p >= msg + 2 && p[-1] == '\n' && p[-2] == '\r');
        if (!at_line_start || p[nl] != ':') {
            p += nl;
            continue;
        }
        const char *v = p + nl + 1;
        while (*v == ' ' || *v == '\t')
            v++;
        const char *eol = strstr(v, "\r\n");
        const char *end = eol ? eol : v + strlen(v);

        const char *seg = v;
        int depth = 0, quoted = 0;
        for (const char *c = v; c <= end; c++) {
            if (c == end || (!depth && !quoted && *c == ',')) {
                const char *b = seg;
                const char *e = c;
                while (b < e && (*b == ' ' || *b == '\t'))
                    b++;
                while (e > b && (e[-1] == ' ' || e[-1] == '\t'))
                    e--;
                if (e > b && n < 16) {
                    ent[n] = b;
                    elen[n] = (size_t)(e - b);
                    n++;
                }
                seg = c + 1;
                continue;
            }
            if (*c == '"')
                quoted = !quoted;
            else if (!quoted && *c == '<')
                depth++;
            else if (!quoted && *c == '>' && depth)
                depth--;
        }
        p = eol ? eol + 2 : end;
    }

    if (count)
        *count = n;
    if (n == 0) {
        if (dstlen)
            dst[0] = '\0';
        return -1;
    }

    /* Build from the nearest hop outwards so that a set too long for the
     * buffer loses its tail, never the top hop. */
    size_t off = 0;
    dst[0] = '\0';
    for (int k = 0; k < n; k++) {
        int i = reverse ? (n - 1 - k) : k;
        size_t sep = off ? 2 : 0;
        if (off + sep + elen[i] + 1 > dstlen)
            break;
        if (sep) {
            dst[off] = ',';
            dst[off + 1] = ' ';
            off += 2;
        }
        memcpy(dst + off, ent[i], elen[i]);
        off += elen[i];
        dst[off] = '\0';
    }
    return (int)off;
}

/* RFC 3261 12.1.2: a UAC reverses the Record-Route list. */
static int route_set_reversed(const char *msg, char *dst, size_t dstlen,
                              int *count)
{
    return route_set_collect(msg, dst, dstlen, count, 1);
}

/* RFC 3261 12.1.1: a UAS does NOT. It takes the list in the order the
 * request carried it. Getting this backwards sends in-dialog requests --
 * the BYE that ends an inbound call, most visibly -- the wrong way through
 * the proxy chain. */
int sip_route_set_uas(const char *msg, char *dst, size_t dstlen, int *count)
{
    return route_set_collect(msg, dst, dstlen, count, 0);
}

/* One header field, verbatim, for callers outside this file. */
int sip_header_copy(const char *msg, const char *name, char *dst, size_t n)
{
    return header_value(msg, name, dst, n);
}

/* Strip surrounding quotes and anything a log or a UI should not see.
 * A display name is attacker-controlled text: it arrives from the far end
 * and ends up on a lock screen, so it does not get to carry control
 * characters or unbounded length. */
static void clean_display_name(char *s)
{
    size_t l = strlen(s);
    while (l && (s[l - 1] == ' ' || s[l - 1] == '\t'))
        s[--l] = '\0';
    if (l >= 2 && s[0] == '"' && s[l - 1] == '"') {
        memmove(s, s + 1, l - 2);
        s[l - 2] = '\0';
        l -= 2;
    }
    for (size_t i = 0; i < l; i++)
        if ((unsigned char)s[i] < 0x20 || s[i] == 0x7f)
            s[i] = ' ';
    /* Leading/trailing space again after the substitution. */
    size_t b = 0;
    while (s[b] == ' ')
        b++;
    if (b)
        memmove(s, s + b, strlen(s + b) + 1);
    l = strlen(s);
    while (l && s[l - 1] == ' ')
        s[--l] = '\0';
}

/* Pull "Display Name" <uri> out of one header value. */
static void split_name_addr(const char *val, char *uri, size_t urilen,
                            char *name, size_t namelen)
{
    uri[0] = '\0';
    name[0] = '\0';
    const char *lt = strchr(val, '<');
    const char *gt = lt ? strchr(lt, '>') : NULL;
    if (lt && gt) {
        size_t l = (size_t)(gt - lt - 1);
        if (l >= urilen)
            l = urilen - 1;
        memcpy(uri, lt + 1, l);
        uri[l] = '\0';
        size_t nl = (size_t)(lt - val);
        if (nl >= namelen)
            nl = namelen - 1;
        memcpy(name, val, nl);
        name[nl] = '\0';
        clean_display_name(name);
    } else {
        /* Bare URI with no angle brackets; parameters are not part of it. */
        size_t i = 0;
        while (val[i] && val[i] != ';' && val[i] != ',' && i + 1 < urilen) {
            uri[i] = val[i];
            i++;
        }
        uri[i] = '\0';
        clean_display_name(uri);
    }
}

/* The calling party, for caller ID.
 *
 * IMS asserts the real identity in P-Asserted-Identity; From is only what
 * the caller claims and may be anonymous. Prefer the asserted one for the
 * number. A P-Asserted-Identity often carries both a sip: and a tel: form,
 * comma separated -- the tel: form is the one worth displaying.
 *
 * The display name usually rides on From even when P-Asserted-Identity
 * supplies the number, so they are collected independently.
 *
 * Returns 1 if a number was found, 0 if the caller withheld it. `name` may
 * be empty either way.
 */
int sip_calling_identity(const char *msg, char *uri, size_t urilen,
                         char *name, size_t namelen)
{
    uri[0] = '\0';
    name[0] = '\0';

    char pai[512], from[512];
    int have_pai = header_value(msg, "P-Asserted-Identity", pai,
                                sizeof(pai)) >= 0;
    int have_from = header_value(msg, "From", from, sizeof(from)) >= 0;

    char u[300], n[200];
    if (have_pai) {
        /* Prefer a tel: entry when the header lists several. */
        const char *pick = pai;
        const char *tel = strcasestr(pai, "<tel:");
        if (tel)
            pick = tel;
        split_name_addr(pick, u, sizeof(u), n, sizeof(n));
        snprintf(uri, urilen, "%s", u);
        if (n[0])
            snprintf(name, namelen, "%s", n);
    }
    if (have_from) {
        split_name_addr(from, u, sizeof(u), n, sizeof(n));
        if (!uri[0])
            snprintf(uri, urilen, "%s", u);
        if (!name[0] && n[0])
            snprintf(name, namelen, "%s", n);
    }

    if (!uri[0] || strcasestr(uri, "anonymous") != NULL) {
        uri[0] = '\0';
        /* A withheld number should not leak a name either. */
        name[0] = '\0';
        return 0;
    }
    if (strcasestr(name, "anonymous") != NULL)
        name[0] = '\0';
    return 1;
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
    r->have_contact = header_value(msg, "Contact", r->contact,
                                   sizeof(r->contact)) >= 0;
    r->have_record_route = route_set_reversed(msg, r->record_route,
                                              sizeof(r->record_route),
                                              &r->record_route_n) >= 0;
    r->have_p_associated_uri = header_value(msg, "P-Associated-URI",
                                            r->p_associated_uri,
                                            sizeof(r->p_associated_uri)) >= 0;
    header_value(msg, "Call-ID", r->call_id, sizeof(r->call_id));
    header_value(msg, "To", r->to_hdr, sizeof(r->to_hdr));
    header_value(msg, "From", r->from_hdr, sizeof(r->from_hdr));
    char cseq_raw[64];
    if (header_value(msg, "CSeq", cseq_raw, sizeof(cseq_raw)) >= 0) {
        r->cseq = atoi(cseq_raw);
        const char *m = cseq_raw;
        while (*m && *m != ' ' && *m != '\t')
            m++;
        while (*m == ' ' || *m == '\t')
            m++;
        snprintf(r->cseq_method, sizeof(r->cseq_method), "%s", m);
    }
    char exp[32];
    if (header_value(msg, "Expires", exp, sizeof(exp)) >= 0)
        r->expires = atoi(exp);
    header_value(msg, "Date", r->date_hdr, sizeof(r->date_hdr));
    char rs[32];
    r->rseq = 0;
    if (header_value(msg, "RSeq", rs, sizeof(rs)) >= 0)
        r->rseq = atoi(rs);
    r->require[0] = '\0';
    header_value(msg, "Require", r->require, sizeof(r->require));
    r->session_expires = 0;
    r->se_refresher[0] = '\0';
    char se[64];
    if (header_value(msg, "Session-Expires", se, sizeof(se)) >= 0) {
        r->session_expires = atoi(se);
        const char *rf = strcasestr(se, "refresher=");
        if (rf) {
            rf += 10;
            size_t i = 0;
            while (rf[i] && rf[i] != ';' && rf[i] != ' ' && rf[i] != '\r' &&
                   i + 1 < sizeof(r->se_refresher)) {
                r->se_refresher[i] = rf[i];
                i++;
            }
            r->se_refresher[i] = '\0';
        }
    }
    return 0;
}


/* ---- Call setup -------------------------------------------------------- */

/* SDP offer.
 *
 * Codec order is preference order. PCMU is listed first because it is the
 * only codec we can actually send (rtp.c). AMR-WB first made the core pick
 * a codec with no encoder here, so every "working" call was silent. The
 * pmOS run that proved this path offered PCMU first and negotiated it.
 */
static int sdp_offer(char *out, size_t outlen, const char *ip, int rtp_port)
{
    appender_t a = { out, outlen };
    app(&a, "v=0\r\n");
    app(&a, "o=- %ld 1 IN IP6 %s\r\n", (long)time(NULL), ip);
    app(&a, "s=-\r\n");
    app(&a, "c=IN IP6 %s\r\n", ip);
    app(&a, "t=0 0\r\n");
    app(&a, "m=audio %d RTP/AVP 0 96 97 101\r\n", rtp_port);
    app(&a, "a=rtpmap:0 PCMU/8000\r\n");
    app(&a, "a=rtpmap:96 AMR-WB/16000/1\r\n");
    app(&a, "a=fmtp:96 octet-align=0;mode-change-capability=2\r\n");
    app(&a, "a=rtpmap:97 AMR/8000/1\r\n");
    app(&a, "a=fmtp:97 octet-align=0\r\n");
    app(&a, "a=rtpmap:101 telephone-event/8000\r\n");
    app(&a, "a=fmtp:101 0-15\r\n");
    app(&a, "a=ptime:20\r\n");
    app(&a, "a=maxptime:240\r\n");
    app(&a, "a=rtcp:%d\r\n", rtp_port + 1);
    app(&a, "a=rtcp-mux\r\n");
    app(&a, "a=sendrecv\r\n");
    return (int)(outlen - a.left);
}

static void invite_contact_user(const char *aor, char *dst, size_t n)
{
    const char *cu = aor;
    if (!strncmp(cu, "sip:", 4)) cu += 4;
    if (!strncmp(cu, "tel:", 4)) cu += 4;
    const char *at = strchr(cu, '@');
    size_t ul = at ? (size_t)(at - cu) : strlen(cu);
    if (ul >= n) ul = n - 1;
    memcpy(dst, cu, ul);
    dst[ul] = '\0';
}

int build_invite(char *out, size_t outlen,
                 const sip_identity_t *id,
                 const char *dest,
                 const char *route,
                 const char *sec_verify,
                 int rtp_port,
                 sip_dialog_t *dlg)
{
    /* No IMPI fallback here, deliberately. The IMPI is
     * IMSI@ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org: falling back to it puts
     * the subscriber's permanent IMSI in From/Contact/P-Preferred-Identity,
     * and the far end shows it as the caller ID. A call without a public
     * identity is a bug, not something to paper over. */
    if (!id->impu[0])
        return -1;
    const char *public_id = id->impu;
    char aor[300];
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char host[80];
    bracket(id->local_ip, host, sizeof(host));

    mk_branch(dlg->branch, sizeof(dlg->branch));
    mk_call_id(dlg->call_id, sizeof(dlg->call_id));
    mk_tag(dlg->from_tag, sizeof(dlg->from_tag));
    dlg->cseq = 1;

    char sdp[1024];
    int slen = sdp_offer(sdp, sizeof(sdp), id->local_ip, rtp_port);
    if (slen <= 0)
        return -1;

    char contact_user[128];
    invite_contact_user(aor, contact_user, sizeof(contact_user));

    appender_t a = { out, outlen };
    app(&a, "INVITE %s SIP/2.0\r\n", dest);
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        host, id->local_port, dlg->branch);
    app(&a, "Max-Forwards: 70\r\n");
    if (route && route[0])
        app(&a, "Route: %s\r\n", route);
    app(&a, "From: <%s>;tag=%s\r\n", aor, dlg->from_tag);
    app(&a, "To: <%s>\r\n", dest);
    app(&a, "Call-ID: %s\r\n", dlg->call_id);
    app(&a, "CSeq: %d INVITE\r\n", dlg->cseq);
    app(&a, "Contact: <sip:%s@%s:%d>"
            ";+g.3gpp.icsi-ref=\"urn%%3Aurn-7%%3A3gpp-service.ims.icsi.mmtel\""
            ";audio\r\n",
        contact_user, host,
        id->contact_port ? id->contact_port : id->local_port);
    app(&a, "P-Preferred-Identity: <%s>\r\n", aor);
    app(&a, "P-Access-Network-Info: 3GPP-E-UTRAN-FDD\r\n");
    app(&a, "Allow: INVITE, ACK, CANCEL, BYE, UPDATE, PRACK, INFO, OPTIONS\r\n");
    app(&a, "Supported: replaces\r\n");
    app(&a, "Require: sec-agree\r\n");
    app(&a, "Proxy-Require: sec-agree\r\n");
    if (sec_verify && sec_verify[0])
        app(&a, "Security-Verify: %s\r\n", sec_verify);
    app(&a, "Accept-Contact: *;+g.3gpp.icsi-ref="
            "\"urn%%3Aurn-7%%3A3gpp-service.ims.icsi.mmtel\"\r\n");
    app(&a, "Content-Type: application/sdp\r\n");
    app(&a, "Content-Length: %d\r\n", slen);
    app(&a, "\r\n");
    app(&a, "%s", sdp);
    return (int)(outlen - a.left);
}

/* RFC 3261 12.2.1.1: in a request within a dialog the To and From URIs are
 * the dialog's remote and local URI, established by the response that
 * created the dialog -- not the URI we dialled. Rebuilding them loses
 * whatever the far end actually put there, and the peer then cannot match
 * the request to its dialog: a rebuilt ACK left Google retransmitting its
 * 200 until timer H, and a rebuilt BYE was answered 481 Call/Transaction
 * Does Not Exist. Echo the dialog-creating response's own header fields
 * whenever we have them, and fall back only when we do not. */
static void app_dialog_to_from(appender_t *a, const char *aor,
                               const sip_dialog_t *dlg,
                               const char *to_uri, const char *to_tag,
                               const char *to_hdr, const char *from_hdr)
{
    if (from_hdr && from_hdr[0])
        app(a, "From: %s\r\n", from_hdr);
    else
        app(a, "From: <%s>;tag=%s\r\n", aor, dlg->from_tag);

    if (to_hdr && to_hdr[0])
        app(a, "To: %s\r\n", to_hdr);
    else if (to_tag && to_tag[0])
        app(a, "To: <%s>;tag=%s\r\n", to_uri, to_tag);
    else
        app(a, "To: <%s>\r\n", to_uri);
}

int build_ack(char *out, size_t outlen,
              const sip_identity_t *id,
              const char *target,     /* Request-URI: remote target */
              const char *to_uri,     /* To header: the URI we dialled */
              const char *route,
              const char *sec_verify,
              const sip_dialog_t *dlg,
              const char *to_tag,
              const char *to_hdr,
              const char *from_hdr)
{
    const char *public_id = id->impu[0] ? id->impu : id->impi;
    char aor[300];
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char host[80];
    bracket(id->local_ip, host, sizeof(host));
    char branch[40];
    mk_branch(branch, sizeof(branch));

    appender_t a = { out, outlen };
    app(&a, "ACK %s SIP/2.0\r\n", target);
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        host, id->local_port, branch);
    app(&a, "Max-Forwards: 70\r\n");
    if (route && route[0])
        app(&a, "Route: %s\r\n", route);
    /* RFC 3261 12.2.1.1: in a dialog request the To/From URIs are the
     * dialog's remote/local URI, which were established by the 2xx -- not
     * the URI we dialled. IMS rewrites the request URI on the way through
     * the core, so a rebuilt To fails to match the dialog at the far end,
     * which then retransmits its 200 until timer H and hangs up. Echo the
     * response's own header fields whenever we have them. */
    app_dialog_to_from(&a, aor, dlg, to_uri, to_tag, to_hdr, from_hdr);
    app(&a, "Call-ID: %s\r\n", dlg->call_id);
    app(&a, "CSeq: %d ACK\r\n", dlg->cseq);
    if (sec_verify && sec_verify[0])
        app(&a, "Security-Verify: %s\r\n", sec_verify);
    app(&a, "Content-Length: 0\r\n");
    app(&a, "\r\n");
    return (int)(outlen - a.left);
}

int build_prack(char *out, size_t outlen,
                const sip_identity_t *id,
                const char *target,
                const char *to_uri,
                const char *route,
                const char *sec_verify,
                const sip_dialog_t *dlg,
                const char *to_tag,
                int rseq,
                const char *to_hdr,
                const char *from_hdr)
{
    const char *public_id = id->impu[0] ? id->impu : id->impi;
    char aor[300];
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char host[80];
    bracket(id->local_ip, host, sizeof(host));
    char branch[40];
    mk_branch(branch, sizeof(branch));

    appender_t a = { out, outlen };
    app(&a, "PRACK %s SIP/2.0\r\n", target);
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        host, id->local_port, branch);
    app(&a, "Max-Forwards: 70\r\n");
    if (route && route[0])
        app(&a, "Route: %s\r\n", route);
    app_dialog_to_from(&a, aor, dlg, to_uri, to_tag, to_hdr, from_hdr);
    app(&a, "Call-ID: %s\r\n", dlg->call_id);
    app(&a, "CSeq: %d PRACK\r\n", dlg->cseq + 1);
    app(&a, "RAck: %d %d INVITE\r\n", rseq, dlg->cseq);
    if (sec_verify && sec_verify[0])
        app(&a, "Security-Verify: %s\r\n", sec_verify);
    app(&a, "Content-Length: 0\r\n");
    app(&a, "\r\n");
    return (int)(outlen - a.left);
}


int build_update(char *out, size_t outlen,
                 const sip_identity_t *id,
                 const char *target,
                 const char *to_uri,
                 const char *route,
                 const char *sec_verify,
                 const sip_dialog_t *dlg,
                 const char *to_tag,
                 int session_expires,
                const char *to_hdr,
                const char *from_hdr)
{
    const char *public_id = id->impu[0] ? id->impu : id->impi;
    char aor[300];
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char host[80];
    bracket(id->local_ip, host, sizeof(host));
    char branch[40];
    mk_branch(branch, sizeof(branch));

    appender_t a = { out, outlen };
    app(&a, "UPDATE %s SIP/2.0\r\n", target);
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        host, id->local_port, branch);
    app(&a, "Max-Forwards: 70\r\n");
    if (route && route[0])
        app(&a, "Route: %s\r\n", route);
    app_dialog_to_from(&a, aor, dlg, to_uri, to_tag, to_hdr, from_hdr);
    app(&a, "Call-ID: %s\r\n", dlg->call_id);
    app(&a, "CSeq: %d UPDATE\r\n", dlg->cseq + 1);
    if (session_expires > 0)
        app(&a, "Session-Expires: %d;refresher=uac\r\n", session_expires);
    app(&a, "Supported: timer\r\n");
    if (sec_verify && sec_verify[0])
        app(&a, "Security-Verify: %s\r\n", sec_verify);
    app(&a, "Content-Length: 0\r\n");
    app(&a, "\r\n");
    return (int)(outlen - a.left);
}

int build_bye(char *out, size_t outlen,
              const sip_identity_t *id,
              const char *target,     /* Request-URI: remote target */
              const char *to_uri,     /* To header: the URI we dialled */
              const char *route,
              const char *sec_verify,
              const sip_dialog_t *dlg,
              const char *to_tag,
                const char *to_hdr,
                const char *from_hdr)
{
    const char *public_id = id->impu[0] ? id->impu : id->impi;
    char aor[300];
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char host[80];
    bracket(id->local_ip, host, sizeof(host));
    char branch[40];
    mk_branch(branch, sizeof(branch));

    appender_t a = { out, outlen };
    app(&a, "BYE %s SIP/2.0\r\n", target);
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        host, id->local_port, branch);
    app(&a, "Max-Forwards: 70\r\n");
    if (route && route[0])
        app(&a, "Route: %s\r\n", route);
    app_dialog_to_from(&a, aor, dlg, to_uri, to_tag, to_hdr, from_hdr);
    app(&a, "Call-ID: %s\r\n", dlg->call_id);
    app(&a, "CSeq: %d BYE\r\n", dlg->cseq + 1);
    if (sec_verify && sec_verify[0])
        app(&a, "Security-Verify: %s\r\n", sec_verify);
    app(&a, "Content-Length: 0\r\n");
    app(&a, "\r\n");
    return (int)(outlen - a.left);
}


int build_cancel(char *out, size_t outlen,
                 const sip_identity_t *id,
                 const char *dest,
                 const char *route,
                 const char *sec_verify,
                 const sip_dialog_t *dlg)
{
    if (!id->impu[0])
        return -1;
    char aor[300];
    const char *public_id = id->impu;
    if (!strncmp(public_id, "tel:", 4) || !strncmp(public_id, "sip:", 4))
        snprintf(aor, sizeof(aor), "%s", public_id);
    else
        snprintf(aor, sizeof(aor), "sip:%s", public_id);

    char host[80];
    bracket(id->local_ip, host, sizeof(host));

    appender_t a = { out, outlen };
    app(&a, "CANCEL %s SIP/2.0\r\n", dest);
    /* Same branch as the INVITE: that is the matching key. */
    app(&a, "Via: SIP/2.0/UDP %s:%d;branch=%s;rport\r\n",
        host, id->local_port, dlg->branch);
    app(&a, "Max-Forwards: 70\r\n");
    if (route && route[0])
        app(&a, "Route: %s\r\n", route);
    app(&a, "From: <%s>;tag=%s\r\n", aor, dlg->from_tag);
    app(&a, "To: <%s>\r\n", dest);
    app(&a, "Call-ID: %s\r\n", dlg->call_id);
    app(&a, "CSeq: %d CANCEL\r\n", dlg->cseq);
    if (sec_verify && sec_verify[0])
        app(&a, "Security-Verify: %s\r\n", sec_verify);
    app(&a, "Content-Length: 0\r\n");
    app(&a, "\r\n");
    return (int)(outlen - a.left);
}


/* ---- Inbound requests -------------------------------------------------- */

int sip_request_method(const char *msg, char *out, size_t outlen)
{
    out[0] = '\0';
    if (!strncmp(msg, "SIP/2.0", 7))
        return -1;                      /* a response, not a request */
    size_t i = 0;
    while (msg[i] && msg[i] != ' ' && i + 1 < outlen) {
        out[i] = msg[i];
        i++;
    }
    out[i] = '\0';
    return i ? 0 : -1;
}

/* Copy every occurrence of `name` from the request into the response. Via
 * in particular can repeat, and dropping any of them makes the response
 * unroutable. */
static void echo_headers(appender_t *a, const char *req, const char *name)
{
    size_t nl = strlen(name);
    const char *p = req;
    while ((p = strcasestr(p, name)) != NULL) {
        int at_line_start = (p == req) ||
                            (p >= req + 2 && p[-1] == '\n' && p[-2] == '\r');
        if (!at_line_start || p[nl] != ':') {
            p += nl;
            continue;
        }
        const char *eol = strstr(p, "\r\n");
        size_t len = eol ? (size_t)(eol - p) : strlen(p);
        app(a, "%.*s\r\n", (int)len, p);
        p = eol ? eol + 2 : p + nl;
    }
}

int build_response(char *out, size_t outlen,
                   const char *req,
                   int code, const char *reason,
                   const sip_identity_t *id,
                   const char *to_tag,
                   const char *sdp)
{
    appender_t a = { out, outlen };
    app(&a, "SIP/2.0 %d %s\r\n", code, reason);
    echo_headers(&a, req, "Via");
    echo_headers(&a, req, "Record-Route");
    echo_headers(&a, req, "From");

    /* To, with our tag added if the request had none. */
    const char *t = req;
    int wrote_to = 0;
    while ((t = strcasestr(t, "To:")) != NULL) {
        int line_start = (t == req) ||
                         (t >= req + 2 && t[-1] == '\n' && t[-2] == '\r');
        if (!line_start) { t += 3; continue; }
        const char *eol = strstr(t, "\r\n");
        size_t len = eol ? (size_t)(eol - t) : strlen(t);
        char to_line[400];
        if (len >= sizeof(to_line)) len = sizeof(to_line) - 1;
        memcpy(to_line, t, len);
        to_line[len] = '\0';
        if (strcasestr(to_line, "tag=") || !to_tag || !to_tag[0])
            app(&a, "%s\r\n", to_line);
        else
            app(&a, "%s;tag=%s\r\n", to_line, to_tag);
        wrote_to = 1;
        break;
    }
    if (!wrote_to)
        return -1;

    echo_headers(&a, req, "Call-ID");
    echo_headers(&a, req, "CSeq");

    if (code >= 180 && code < 300) {
        char host[80];
        bracket(id->local_ip, host, sizeof(host));
        const char *pid = id->impu[0] ? id->impu : "";
        char cu[128];
        const char *p = pid;
        if (!strncmp(p, "sip:", 4)) p += 4;
        if (!strncmp(p, "tel:", 4)) p += 4;
        const char *at = strchr(p, '@');
        size_t ul = at ? (size_t)(at - p) : strlen(p);
        if (ul >= sizeof(cu)) ul = sizeof(cu) - 1;
        memcpy(cu, p, ul);
        cu[ul] = '\0';
        app(&a, "Contact: <sip:%s@%s:%d>"
                ";+g.3gpp.icsi-ref=\"urn%%3Aurn-7%%3A3gpp-service.ims.icsi.mmtel\""
                ";audio\r\n",
            cu, host, id->contact_port ? id->contact_port : id->local_port);
    }
    if (sdp && sdp[0]) {
        app(&a, "Content-Type: application/sdp\r\n");
        app(&a, "Content-Length: %d\r\n", (int)strlen(sdp));
        app(&a, "\r\n");
        app(&a, "%s", sdp);
    } else {
        app(&a, "Content-Length: 0\r\n");
        app(&a, "\r\n");
    }
    return (int)(outlen - a.left);
}

int sdp_answer(char *out, size_t outlen, const char *ip, int rtp_port,
               const char *offer)
{
    /* Answer PCMU whenever the offer allows it. We have no AMR encoder;
     * answering AMR-WB would complete signalling and still be silent. */
    int offer_has_pcmu = 1;
    if (offer && offer[0]) {
        sdp_media_t m;
        memset(&m, 0, sizeof(m));
        if (sdp_parse_media(offer, &m) == 0)
            offer_has_pcmu = m.have_pcmu;
        else
            offer_has_pcmu = (strcasestr(offer, "PCMU") != NULL);
    }
    if (!offer_has_pcmu)
        klog(LOG_WARN, "sdp answer: offer has no PCMU, answering PT 0 anyway");
    appender_t a = { out, outlen };
    app(&a, "v=0\r\n");
    app(&a, "o=- %ld 1 IN IP6 %s\r\n", (long)time(NULL), ip);
    app(&a, "s=-\r\n");
    app(&a, "c=IN IP6 %s\r\n", ip);
    app(&a, "t=0 0\r\n");
    app(&a, "m=audio %d RTP/AVP 0\r\n", rtp_port);
    app(&a, "a=rtpmap:0 PCMU/8000\r\n");
    app(&a, "a=ptime:20\r\n");
    app(&a, "a=rtcp:%d\r\n", rtp_port + 1);
    if (!offer || strstr(offer, "a=rtcp-mux"))
        app(&a, "a=rtcp-mux\r\n");
    app(&a, "a=sendrecv\r\n");
    return (int)(outlen - a.left);
}

int sdp_parse_media(const char *msg, sdp_media_t *out)
{
    if (!msg || !out)
        return -1;
    memset(out, 0, sizeof(*out));
    out->port = -1;
    out->pt = -1;
    const char *sdp = strstr(msg, "\r\n\r\n");
    if (sdp)
        sdp += 4;
    else
        sdp = msg;
    const char *p = sdp;
    while (*p) {
        const char *eol = strstr(p, "\r\n");
        size_t n = eol ? (size_t)(eol - p) : strlen(p);
        if (n >= 9 && !strncmp(p, "c=IN IP6 ", 9)) {
            size_t l = n - 9;
            if (l >= sizeof(out->ip))
                l = sizeof(out->ip) - 1;
            memcpy(out->ip, p + 9, l);
            out->ip[l] = '\0';
        } else if (n >= 9 && !strncmp(p, "c=IN IP4 ", 9)) {
            size_t l = n - 9;
            if (l >= sizeof(out->ip))
                l = sizeof(out->ip) - 1;
            memcpy(out->ip, p + 9, l);
            out->ip[l] = '\0';
        } else if (n >= 8 && !strncmp(p, "m=audio ", 8)) {
            out->port = atoi(p + 8);
            const char *q = p + 8;
            while (*q && *q != ' ' && *q != '\r')
                q++;
            while (*q == ' ')
                q++;
            while (*q && *q != ' ' && *q != '\r')
                q++;
            while (*q == ' ')
                q++;
            if (*q >= '0' && *q <= '9')
                out->pt = atoi(q);
            if (out->pt == 0)
                out->have_pcmu = 1;
            const char *r = q;
            while (r < p + n) {
                if (*r == ' ' && r[1] == '0' &&
                    (r[2] == ' ' || r[2] == '\r' || r[2] == '\0')) {
                    out->have_pcmu = 1;
                    if (out->pt < 0)
                        out->pt = 0;
                }
                r++;
            }
        } else if (n > 9 && !strncmp(p, "a=rtpmap:", 9) &&
                   strcasestr(p, "PCMU")) {
            out->have_pcmu = 1;
            int rpt = atoi(p + 9);
            if (out->pt < 0 && rpt >= 0)
                out->pt = rpt;
        } else if (n >= 10 && !strncmp(p, "a=rtcp-mux", 10)) {
            out->have_rtcp_mux = 1;
        } else if (n >= 7 && !strncmp(p, "a=rtcp:", 7)) {
            int rp = atoi(p + 7);
            if (rp > 0)
                out->rtcp_port = rp;
        }
        if (!eol)
            break;
        p = eol + 2;
    }
    if (out->have_pcmu && out->pt < 0)
        out->pt = 0;
    if (out->ip[0] && out->port > 0)
        return 0;
    return -1;
}

int sip_extract_one(char *buf, size_t *buflen, char *out, size_t outmax)
{
    if (!buf || !buflen || !out || outmax < 2)
        return -1;
    char *eoh = strstr(buf, "\r\n\r\n");
    if (!eoh)
        return 0;
    size_t hlen = (size_t)(eoh + 4 - buf);
    int cl = 0;
    const char *clh = strcasestr(buf, "\r\nContent-Length:");
    if (clh && clh < eoh)
        cl = atoi(clh + 17);
    if (cl < 0)
        cl = 0;
    size_t need = hlen + (size_t)cl;
    if (*buflen < need)
        return 0;
    if (need >= outmax)
        return -1;
    memcpy(out, buf, need);
    out[need] = '\0';
    memmove(buf, buf + need, *buflen - need);
    *buflen -= need;
    buf[*buflen] = '\0';
    return 1;
}

void mk_tag_public(char *dst, size_t n)
{
    mk_tag(dst, n);
}
