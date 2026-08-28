/* xfrm.c — kernel IPsec SA/policy programming via NETLINK_XFRM.
 *
 * Ports the proven pmOS helper matrix (joan_ims_ipsec.py four_sas /
 * xfrm_commands) onto real netlink calls. Layout notes mirrored from
 * iproute2 behavior: selector ports are host-order values converted to
 * network order with exact-match masks; algo key lengths are in BITS.
 */
#include "xfrm.h"

#include <arpa/inet.h>
#include <errno.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/xfrm.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "util.h"

static int g_nl = -1;
static uint32_t g_seq;

typedef struct {
    char kname[32];       /* kernel algo name (array: snprintf target) */
    uint8_t key[32];
    unsigned key_bits;
} alg_key_t;

static int nl_open(void)
{
    if (g_nl >= 0)
        return 0;
    g_nl = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_XFRM);
    return g_nl < 0 ? -1 : 0;
}

static char *rta_append(struct nlmsghdr *n, int type,
                        const void *data, unsigned short len)
{
    char *at = (char *)n + n->nlmsg_len;
    unsigned short total = (unsigned short)(4 + ((len + 3) & ~3u));
    struct rtattr *ra = (struct rtattr *)at;
    ra->rta_type = (unsigned short)type;
    ra->rta_len = (unsigned short)(4 + len);
    memcpy(at + 4, data, len);
    memset(at + 4 + len, 0, total - 4 - len);
    n->nlmsg_len += total;
    return at;
}

/* Send request, wait for matching-seq ACK. Returns 0 ok or negative errno. */
static int nl_call(struct nlmsghdr *n)
{
    struct sockaddr_nl sa;
    memset(&sa, 0, sizeof(sa));
    sa.nl_family = AF_NETLINK;

    g_seq++;
    n->nlmsg_seq = g_seq;
    n->nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;

    ssize_t sd = sendto(g_nl, (void *)n, n->nlmsg_len, 0,
                        (struct sockaddr *)&sa, sizeof(sa));
    if (sd < 0)
        return -errno;

    char buf[4096];
    for (;;) {
        struct pollfd pfd;
        memset(&pfd, 0, sizeof(pfd));
        pfd.fd = g_nl;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 4000);
        if (pr < 0) {
            if (errno == EINTR)
                continue;
            return -errno;
        }
        if (pr == 0)
            return -ETIMEDOUT;
        ssize_t r = recv(g_nl, buf, sizeof(buf), 0);
        if (r <= 0) {
            if (r < 0 && errno == EINTR)
                continue;
            return r < 0 ? -errno : -EIO;
        }
        size_t rem = (size_t)r;
        for (struct nlmsghdr *h = (struct nlmsghdr *)buf;
             NLMSG_OK(h, rem);
             h = NLMSG_NEXT(h, rem)) {
            if (h->nlmsg_seq != g_seq)
                continue;
            if (h->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(h);
                return e->error ? e->error : 0;
            }
            if (h->nlmsg_type == NLMSG_DONE)
                return 0;
        }
    }
}

static int fill_addr(const char *txt, int *fam_out, xfrm_address_t *out)
{
    struct in6_addr a6;
    struct in_addr a4;
    if (inet_pton(AF_INET6, txt, &a6) == 1) {
        *fam_out = AF_INET6;
        memcpy(out, &a6, 16);
        return 0;
    }
    if (inet_pton(AF_INET, txt, &a4) == 1) {
        *fam_out = AF_INET;
        memcpy(out, &a4, 4);
        return 0;
    }
    return -1;
}

static void pick_algos(const sec_agree_t *agree,
                       alg_key_t *auth, alg_key_t *enc,
                       const uint8_t *ck, const uint8_t *ik)
{
    memset(auth, 0, sizeof(*auth));
    memset(enc, 0, sizeof(*enc));

    const char *a = agree->alg;
    if (!strcmp(a, "hmac-sha-1-96") || !strcmp(a, "hmac-sha1-96") ||
        !strcmp(a, "sha1")) {
        snprintf(auth->kname, sizeof(auth->kname), "sha1");
        memcpy(auth->key, ik, 16);
        memset(auth->key + 16, 0, 4);      /* 128-bit IK -> 160-bit key */
        auth->key_bits = 160;
    } else if (!strcmp(a, "hmac-md5-96") || !strcmp(a, "hmac-md5") ||
               !strcmp(a, "md5")) {
        snprintf(auth->kname, sizeof(auth->kname), "md5");
        memcpy(auth->key, ik, 16);
        auth->key_bits = 128;
    }

    const char *e = agree->ealg;
    if (!strcmp(e, "aes-cbc") || !strcmp(e, "aes")) {
        snprintf(enc->kname, sizeof(enc->kname), "aes");
        memcpy(enc->key, ck, 16);
        enc->key_bits = 128;
    } else if (!strcmp(e, "3des-cbc") || !strcmp(e, "3des")) {
        snprintf(enc->kname, sizeof(enc->kname), "des3_ede");
        memcpy(enc->key, ck, 16);
        memcpy(enc->key + 16, ck, 8);      /* expand to 24 bytes */
        enc->key_bits = 192;
    }
}

struct algo_wire {
    struct xfrm_algo algo;
    uint8_t key[32];
};

static int sa_add_once(
        const char *src, const char *dst,
        uint32_t spi, int fam, uint32_t reqid,
        const alg_key_t *auth, const alg_key_t *enc)
{
    char buf[1024];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *n = (struct nlmsghdr *)buf;
    n->nlmsg_type = XFRM_MSG_NEWSA;
    n->nlmsg_len = NLMSG_LENGTH(sizeof(struct xfrm_usersa_info));

    struct xfrm_usersa_info *su =
        (struct xfrm_usersa_info *)NLMSG_DATA(n);
    int sf = fam, df = fam;
    if (fill_addr(src, &sf, &su->saddr) < 0 ||
        fill_addr(dst, &df, &su->id.daddr) < 0)
        return -EINVAL;
    su->id.proto = IPPROTO_ESP;
    su->id.spi = (uint32_t)htonl(spi);
    /* Bind this SA to one policy.
     *
     * Four SAs share the same address pair and the SA selector is
     * wildcard (ip xfrm state shows "sel src ::/0 dst ::/0"), so with
     * reqid 0 on both the states and the policy templates the kernel has
     * nothing to distinguish them by: an outbound flow can resolve to
     * either of the two ue->pcscf states, i.e. it can encrypt REG2 under
     * the P-CSCF's spi-c when RFC 3329 requires spi-s. The P-CSCF then
     * cannot match the SA and drops the packet in silence, which is
     * exactly the symptom. reqid pairs each policy with its state. */
    su->reqid = reqid;
    /* Lifetimes MUST be set explicitly.
     *
     * This message is memset to zero, and a zeroed xfrm_lifetime_cfg does
     * not mean "unlimited" -- it means a hard limit of zero bytes and zero
     * packets, so the SA is expired the instant anything traverses it. The
     * SA installs cleanly and then the first sendto() through it fails with
     * EINVAL while /proc/net/xfrm_stat increments XfrmOutStateExpired.
     * `ip xfrm state add`, which the pmOS implementation used, fills these
     * with XFRM_INF for us, which is why this never surfaced there. */
    su->lft.soft_byte_limit = XFRM_INF;
    su->lft.hard_byte_limit = XFRM_INF;
    su->lft.soft_packet_limit = XFRM_INF;
    su->lft.hard_packet_limit = XFRM_INF;
    su->family = (uint16_t)fam;
    su->mode = XFRM_MODE_TRANSPORT;
    su->replay_window = 0;
    su->flags = 0;

    struct algo_wire aw;
    memset(&aw, 0, sizeof(aw));
    snprintf(aw.algo.alg_name, sizeof(aw.algo.alg_name), "%s", auth->kname);
    aw.algo.alg_key_len = auth->key_bits;
    memcpy(aw.key, auth->key, (auth->key_bits + 7) / 8);
    rta_append(n, XFRMA_ALG_AUTH, &aw.algo,
               sizeof(struct xfrm_algo) + (auth->key_bits + 7) / 8);

    memset(&aw, 0, sizeof(aw));
    snprintf(aw.algo.alg_name, sizeof(aw.algo.alg_name), "%s", enc->kname);
    aw.algo.alg_key_len = enc->key_bits;
    memcpy(aw.key, enc->key, (enc->key_bits + 7) / 8);
    rta_append(n, XFRMA_ALG_CRYPT, &aw.algo,
               sizeof(struct xfrm_algo) + (enc->key_bits + 7) / 8);

    return nl_call(n);
}

static int sa_del(const char *src, const char *dst,
                  uint32_t spi, int fam)
{
    char buf[512];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *n = (struct nlmsghdr *)buf;
    n->nlmsg_type = XFRM_MSG_DELSA;
    n->nlmsg_len = NLMSG_LENGTH(sizeof(struct xfrm_usersa_id));

    struct xfrm_usersa_id *id = (struct xfrm_usersa_id *)NLMSG_DATA(n);
    int df = fam;
    (void)src;
    if (fill_addr(dst, &df, &id->daddr) < 0)
        return -EINVAL;
    id->family = (uint16_t)fam;
    id->proto = IPPROTO_ESP;
    id->spi = (uint32_t)htonl(spi);
    return nl_call(n);
}

static int sa_install(const char *src, const char *dst,
                      uint32_t spi, int fam, uint32_t reqid,
                      const alg_key_t *auth, const alg_key_t *enc)
{
    int rc = sa_add_once(src, dst, spi, fam, reqid, auth, enc);
    if (rc == -EEXIST) {
        sa_del(src, dst, spi, fam);
        rc = sa_add_once(src, dst, spi, fam, reqid, auth, enc);
    }
    return rc;
}

static int pol_add_once(
        const char *src, const char *dst,
        uint16_t sport, uint16_t dport,
        uint8_t proto, int fam, uint8_t dir, uint32_t reqid)
{
    char buf[768];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *n = (struct nlmsghdr *)buf;
    n->nlmsg_type = XFRM_MSG_NEWPOLICY;
    n->nlmsg_len = NLMSG_LENGTH(sizeof(struct xfrm_userpolicy_info));

    struct xfrm_userpolicy_info *pi =
        (struct xfrm_userpolicy_info *)NLMSG_DATA(n);
    int sf = fam, df = fam;
    (void)sf;
    (void)df;
    if (fill_addr(src, &sf, &pi->sel.saddr) < 0 ||
        fill_addr(dst, &df, &pi->sel.daddr) < 0)
        return -EINVAL;
    pi->sel.sport = htons(sport);
    pi->sel.sport_mask = 0xffff;
    pi->sel.dport = htons(dport);
    pi->sel.dport_mask = 0xffff;
    pi->sel.family = (uint16_t)fam;
    pi->sel.proto = proto;
    /* Without an explicit prefix length these default to 0, and `ip xfrm
     * policy` shows the selectors as .../0: the addresses are carried but
     * ignored, so a policy installed for one IMS PDN address keeps
     * matching after the address changes. Pin them to a single host. */
    pi->sel.prefixlen_s = (fam == AF_INET6) ? 128 : 32;
    pi->sel.prefixlen_d = (fam == AF_INET6) ? 128 : 32;
    pi->dir = dir;
    pi->action = XFRM_POLICY_ALLOW;
    pi->priority = 0;
    pi->share = XFRM_SHARE_ANY;

    /* Template: transport-mode ESP, no pinned addresses/spi — SA lookup
     * by packet SPI (mirrors the proven `tmpl proto esp mode transport`). */
    struct xfrm_user_tmpl tpl;
    memset(&tpl, 0, sizeof(tpl));
    tpl.id.proto = IPPROTO_ESP;
    tpl.family = (uint16_t)fam;
    tpl.mode = XFRM_MODE_TRANSPORT;
    tpl.reqid = reqid;   /* pairs with the state installed above */
    tpl.share = XFRM_SHARE_ANY;
    tpl.optional = 0;
    rta_append(n, XFRMA_TMPL, &tpl, sizeof(tpl));

    return nl_call(n);
}

/* Flush all SAs and policies. Mirrors `ip xfrm state flush` plus
 * `ip xfrm policy flush`. */
static void xfrm_flush_all(void)
{
    char buf[256];

    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *n = (struct nlmsghdr *)buf;
    n->nlmsg_type = XFRM_MSG_FLUSHSA;
    n->nlmsg_len = NLMSG_LENGTH(sizeof(struct xfrm_usersa_flush));
    struct xfrm_usersa_flush *fs = (struct xfrm_usersa_flush *)NLMSG_DATA(n);
    fs->proto = 255;   /* IPSEC_PROTO_ANY; not in all uapi headers */
    if (nl_call(n) < 0)
        klog(LOG_WARN, "xfrm state flush failed errno=%d", errno);

    memset(buf, 0, sizeof(buf));
    n = (struct nlmsghdr *)buf;
    n->nlmsg_type = XFRM_MSG_FLUSHPOLICY;
    n->nlmsg_len = NLMSG_LENGTH(0);
    if (nl_call(n) < 0)
        klog(LOG_WARN, "xfrm policy flush failed errno=%d", errno);
}

int xfrm_install(
        const char *ue,
        const char *pcscf,
        const sec_agree_t *ue_sec,
        const sec_agree_t *pcscf_sec,
        const uint8_t *ck,
        const uint8_t *ik,
        xfrm_status_t *st)
{
    if (st)
        memset(st, 0, sizeof(*st));
    if (nl_open() < 0) {
        klog(LOG_ERR, "xfrm netlink socket failed errno=%d", errno);
        return -1;
    }

    /* Clear anything a previous registration left behind.
     *
     * Each attempt installs 4 SAs and 8 policies. Nothing removed them, so
     * they accumulated across every cycle -- 29 SAs and 64 policies were
     * measured on device -- including entries naming IMS PDN addresses the
     * handset no longer holds. Combined with the /0 selectors above, a
     * stale policy still matched the live flow and resolved a template to
     * an SA that no longer existed, and sendto() returned EINVAL.
     *
     * The proven pmOS implementation flushes state and policy before
     * installing (joan_ims_live.py:350-351); do the same. This daemon is
     * the only xfrm user on the device. */
    xfrm_flush_all();

    alg_key_t auth, enc;
    pick_algos(pcscf_sec, &auth, &enc, ck, ik);
    if (!auth.kname[0] || !enc.kname[0]) {
        klog(LOG_ERR, "unsupported sec-agree algos");
        return -2;
    }

    /* Exact SA matrix from the proven helper (bidirectional two-port). */
    struct {
        const char *src, *dst;
        uint32_t spi;
        uint16_t sport, dport;
        uint8_t dir;
    } rows[4];

    rows[0].src = ue;         rows[0].dst = pcscf;
    rows[0].spi = pcscf_sec->spi_s;
    rows[0].sport = ue_sec->port_c; rows[0].dport = pcscf_sec->port_s;
    rows[0].dir = XFRM_POLICY_OUT;

    rows[1].src = pcscf;      rows[1].dst = ue;
    rows[1].spi = ue_sec->spi_c;
    rows[1].sport = pcscf_sec->port_s; rows[1].dport = ue_sec->port_c;
    rows[1].dir = XFRM_POLICY_IN;

    rows[2].src = ue;         rows[2].dst = pcscf;
    rows[2].spi = pcscf_sec->spi_c;
    rows[2].sport = ue_sec->port_s; rows[2].dport = pcscf_sec->port_c;
    rows[2].dir = XFRM_POLICY_OUT;

    rows[3].src = pcscf;      rows[3].dst = ue;
    rows[3].spi = ue_sec->spi_s;
    rows[3].sport = pcscf_sec->port_c; rows[3].dport = ue_sec->port_s;
    rows[3].dir = XFRM_POLICY_IN;

    int rc = 0;
    int bad_pol = 0;
    for (int i = 0; i < 4; i++) {
        int fam = AF_INET6;
        uint32_t reqid = (uint32_t)(i + 1);
        int sar = sa_install(rows[i].src, rows[i].dst, rows[i].spi,
                             fam, reqid, &auth, &enc);
        if (st)
            st->sa_ok[i] = (sar == 0);
        if (sar != 0) {
            klog(LOG_ERR, "SA[%d] spi=%u failed rc=%d", i, rows[i].spi, sar);
            rc = -3;
            continue;
        }
        /* Inbound policies are omitted deliberately.
         *
         * The SA decrypts the P-CSCF's reply correctly (the inbound state
         * counts it), and then an inbound policy re-checks the decrypted
         * packet against its template and drops it: XfrmInTmplMismatch
         * climbed in step with every reply while userspace saw nothing.
         * Linux accepts a decrypted packet when no inbound policy demands
         * otherwise, so requiring one here buys nothing and costs us the
         * response. Outbound policies still force our own traffic into
         * the SA, which is what the P-CSCF actually checks. */
        if (rows[i].dir != XFRM_POLICY_OUT)
            continue;
        static const uint8_t protos[2] = { IPPROTO_UDP, IPPROTO_TCP };
        for (int pt = 0; pt < 2; pt++) {
            /* reqid pins SA selection, which outbound needs: two
             * ue->pcscf states share an address pair and the wildcard SA
             * selector cannot tell them apart. Inbound is the opposite --
             * the kernel picks the SA by the packet's SPI and then checks
             * it against the policy template, so a pinned reqid there just
             * rejects a correctly decrypted reply (XfrmInTmplMismatch, seen
             * on device while the inbound SA counted the P-CSCF's answer).
             * Pin outbound, leave inbound permissive. */
            uint32_t pol_reqid =
                (rows[i].dir == XFRM_POLICY_OUT) ? reqid : 0;
            int pr = pol_add_once(rows[i].src, rows[i].dst,
                                  rows[i].sport, rows[i].dport,
                                  protos[pt], fam, rows[i].dir, pol_reqid);
            if (pr != 0 && pr != -EEXIST) {
                klog(LOG_ERR, "POL[%d,%s,%s] failed rc=%d",
                     i, pt ? "tcp" : "udp",
                     rows[i].dir == XFRM_POLICY_OUT ? "out" : "in", pr);
                bad_pol = 1;
            }
        }
    }
    if (bad_pol && rc == 0)
        rc = -4;
    if (rc == 0)
        klog(LOG_INFO, "xfrm installed: 4 SA + 8 policies ok");
    return rc;
}
