#define _GNU_SOURCE
#include "rtp.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include "util.h"

#define RTP_PTIME_MS 20
#define PCMU_PT 0
#define PCMU_SAMPLES 160 /* 8 kHz * 20 ms */
#define RTP_HDR 12
#define RTCP_INTERVAL_MS 5000
#define RTCP_FIRST_MS 400

static int g_fd = -1;
static int g_rtcp_fd = -1;
static int g_active;
static int g_pt;
static int g_rtcp_mux;
static uint16_t g_seq;
static uint32_t g_ts;
static uint32_t g_ssrc;
static uint32_t g_remote_ssrc;
static int g_have_remote_ssrc;
static uint16_t g_remote_seq;
static uint32_t g_phase;
static long g_next_ms;
static long g_rtcp_next_ms;
static unsigned g_sent;
static unsigned g_recv;
static unsigned g_rtcp_sent;
static unsigned g_octets;
static struct sockaddr_storage g_dst;
static socklen_t g_dlen;

/* Loopback PCMU bridge to the ImsService (AudioRecord/AudioTrack). */
#define MEDIA_PORT 15091
static int g_media_fd = -1;
static struct sockaddr_in g_media_peer;
static int g_have_media_peer;
static unsigned char g_uplink[PCMU_SAMPLES];
static int g_have_uplink;

static int media_bind(void)
{
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (s < 0)
        return -1;
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons(MEDIA_PORT);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
        klog(LOG_WARN, "rtp media bind 127.0.0.1:%d errno=%d", MEDIA_PORT, errno);
        close(s);
        return -1;
    }
    klog(LOG_INFO, "rtp media 127.0.0.1:%d", MEDIA_PORT);
    return s;
}

static void media_drain_uplink(void)
{
    if (g_media_fd < 0)
        return;
    unsigned char buf[512];
    struct sockaddr_in peer;
    socklen_t plen = sizeof(peer);
    for (;;) {
        ssize_t r = recvfrom(g_media_fd, buf, sizeof(buf), 0,
                             (struct sockaddr *)&peer, &plen);
        if (r <= 0)
            break;
        g_media_peer = peer;
        g_have_media_peer = 1;
        if (r >= PCMU_SAMPLES) {
            memcpy(g_uplink, buf, PCMU_SAMPLES);
            g_have_uplink = 1;
        }
    }
}

static void media_push_downlink(const unsigned char *ulaw, int n)
{
    if (g_media_fd < 0 || n <= 0)
        return;
    if (!g_have_media_peer) {
        if ((g_recv % 50) == 1)
            klog(LOG_WARN, "rtp dl no java peer yet recv=%u", g_recv);
        return;
    }
    ssize_t w = sendto(g_media_fd, ulaw, (size_t)n, 0,
                       (const struct sockaddr *)&g_media_peer,
                       sizeof(g_media_peer));
    if (w < 0 && (g_recv < 5 || (g_recv % 50) == 0))
        klog(LOG_WARN, "rtp dl sendto errno=%d recv=%u", errno, g_recv);
}

/* ITU-T G.711 µ-law. */
static uint8_t linear_to_ulaw(int16_t pcm)
{
    const int BIAS = 0x84;
    const int CLIP = 32635;
    int sign = (pcm >> 8) & 0x80;
    if (sign)
        pcm = (int16_t)(-pcm);
    if (pcm > CLIP)
        pcm = CLIP;
    pcm = (int16_t)(pcm + BIAS);
    int exp = 7;
    for (int mask = 0x4000; (pcm & mask) == 0 && exp > 0; mask >>= 1)
        exp--;
    int mantissa = (pcm >> (exp + 3)) & 0x0F;
    return (uint8_t)(~(sign | (exp << 4) | mantissa));
}

/* CAF/AOSP ImsCallSession does not inject a test tone. In-call audio
 * is the HAL after STARTED. A 440 Hz square wave made the far end hang
 * up while Dialer was still DIALING. Send µ-law idle instead. */

static int udp_bind(const char *local_ip, const char *iface, int port)
{
    int fam = strchr(local_ip, ':') ? AF_INET6 : AF_INET;
    int s = socket(fam, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (s < 0)
        return -1;
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (fam == AF_INET6) {
        int v6only = 1;
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
    }
    if (iface && iface[0]) {
        if (setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE,
                       iface, strlen(iface) + 1) < 0) {
            klog(LOG_WARN, "rtp SO_BINDTODEVICE errno=%d", errno);
        }
    }
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    if (fam == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&ss;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)port);
        if (inet_pton(AF_INET6, local_ip, &a->sin6_addr) != 1) {
            close(s);
            return -1;
        }
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&ss;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)port);
        if (inet_pton(AF_INET, local_ip, &a->sin_addr) != 1) {
            close(s);
            return -1;
        }
    }
    if (bind(s, (struct sockaddr *)&ss, sizeof(ss)) < 0) {
        klog(LOG_ERR, "rtp bind port %d errno=%d", port, errno);
        close(s);
        return -1;
    }
    return s;
}

static void put32(unsigned char *p, uint32_t v)
{
    p[0] = (unsigned char)(v >> 24);
    p[1] = (unsigned char)(v >> 16);
    p[2] = (unsigned char)(v >> 8);
    p[3] = (unsigned char)v;
}

static int is_rtcp(const unsigned char *buf, ssize_t r)
{
    /* RTP PT is 0..127 in the low 7 bits of byte 1. RTCP PT is the
     * whole of byte 1 (SR=200, RR=201, SDES=202, BYE=203, APP=204). */
    return r >= 8 && (buf[0] & 0xc0) == 0x80 && buf[1] >= 200 && buf[1] <= 204;
}

static void rtcp_dest(struct sockaddr_storage *ss, socklen_t *len)
{
    *ss = g_dst;
    *len = g_dlen;
    if (g_rtcp_mux)
        return;
    if (ss->ss_family == AF_INET6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)ss;
        a->sin6_port = htons((uint16_t)(ntohs(a->sin6_port) + 1));
    } else if (ss->ss_family == AF_INET) {
        struct sockaddr_in *a = (struct sockaddr_in *)ss;
        a->sin_port = htons((uint16_t)(ntohs(a->sin_port) + 1));
    }
}

/* Compound RTCP as used by PJSIP pjmedia (pjmedia_rtcp_build_rtcp +
 * send_rtcp with_sdes): SR with one RR block, then SDES CNAME.
 * PJMEDIA_RTCP_INTERVAL is 5000 ms. RFC 3550 §6.1: a compound packet
 * MUST include a report (SR/RR) and an SDES CNAME. RFC 5761: mux only
 * when the SDP answer carried a=rtcp-mux — never because a stray
 * packet arrived on the RTP socket. RFC 4028 §1: RTCP is the audio
 * liveness signal. */
static void rtp_send_rtcp(void)
{
    if (g_fd < 0)
        return;

    unsigned char pkt[96];
    memset(pkt, 0, sizeof(pkt));

    struct timeval tv;
    gettimeofday(&tv, NULL);
    uint32_t ntp_sec = (uint32_t)tv.tv_sec + 2208988800u;
    uint32_t ntp_frac = (uint32_t)(((uint64_t)tv.tv_usec << 32) / 1000000u);

    int rc = g_have_remote_ssrc ? 1 : 0;
    /* SR header + SSRC + sender info = 28 bytes; each RR block = 24. */
    int sr_bytes = 28 + rc * 24;
    pkt[0] = (unsigned char)(0x80 | rc);
    pkt[1] = 200;
    pkt[2] = 0;
    pkt[3] = (unsigned char)(sr_bytes / 4 - 1);
    put32(pkt + 4, g_ssrc);
    put32(pkt + 8, ntp_sec);
    put32(pkt + 12, ntp_frac);
    put32(pkt + 16, g_ts);
    put32(pkt + 20, g_sent);
    put32(pkt + 24, g_octets);
    if (rc) {
        put32(pkt + 28, g_remote_ssrc);
        pkt[32] = 0; /* fraction lost */
        pkt[33] = pkt[34] = pkt[35] = 0; /* cumul lost */
        put32(pkt + 36, g_remote_seq); /* extended highest seq */
        put32(pkt + 40, 0); /* jitter */
        put32(pkt + 44, 0); /* LSR */
        put32(pkt + 48, 0); /* DLSR */
    }

    unsigned char *sdes = pkt + sr_bytes;
    sdes[0] = 0x81;
    sdes[1] = 202;
    sdes[2] = 0;
    sdes[3] = 4;
    put32(sdes + 4, g_ssrc);
    sdes[8] = 1;  /* CNAME */
    sdes[9] = 8;
    memcpy(sdes + 10, "joan.ims", 8);
    sdes[18] = 0;
    sdes[19] = 0;
    int n = sr_bytes + 20;

    struct sockaddr_storage dest;
    socklen_t dlen;
    rtcp_dest(&dest, &dlen);
    int fd = g_rtcp_mux ? g_fd : g_rtcp_fd;
    if (fd < 0) {
        klog(LOG_WARN, "rtcp no socket mux=%d", g_rtcp_mux);
        return;
    }
    ssize_t w = sendto(fd, pkt, n, 0, (struct sockaddr *)&dest, dlen);
    if (w == n) {
        g_rtcp_sent++;
        if (g_rtcp_sent == 1 || (g_rtcp_sent % 4) == 0)
            klog(LOG_INFO, "rtcp sr sent=%u mux=%d rc=%d rtp sent=%u recv=%u",
                 g_rtcp_sent, g_rtcp_mux, rc, g_sent, g_recv);
    } else if (w < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        klog(LOG_WARN, "rtcp send errno=%d mux=%d", errno, g_rtcp_mux);
    }
}

int rtp_start(const char *local_ip, const char *iface,
              int local_port,
              const char *remote_ip, int remote_port,
              int pt, int rtcp_mux)
{
    rtp_stop();
    if (!local_ip || !remote_ip || remote_port <= 0)
        return -1;
    if (pt != PCMU_PT) {
        klog(LOG_WARN, "rtp skip: payload type %d is not PCMU", pt);
        return -2;
    }
    int s = udp_bind(local_ip, iface, local_port);
    if (s < 0)
        return -1;

    memset(&g_dst, 0, sizeof(g_dst));
    if (strchr(remote_ip, ':')) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&g_dst;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)remote_port);
        if (inet_pton(AF_INET6, remote_ip, &a->sin6_addr) != 1) {
            close(s);
            return -1;
        }
        g_dlen = sizeof(*a);
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)&g_dst;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)remote_port);
        if (inet_pton(AF_INET, remote_ip, &a->sin_addr) != 1) {
            close(s);
            return -1;
        }
        g_dlen = sizeof(*a);
    }

    g_fd = s;
    g_rtcp_mux = rtcp_mux ? 1 : 0;
    g_rtcp_fd = udp_bind(local_ip, iface, local_port + 1);
    if (g_rtcp_fd < 0)
        klog(LOG_WARN, "rtcp bind %d failed; mux=%d", local_port + 1, g_rtcp_mux);
    g_active = 1;
    g_pt = pt;
    g_seq = (uint16_t)rand_u64();
    g_ts = (uint32_t)rand_u64();
    g_ssrc = 0xA11E0001u;
    g_remote_ssrc = 0;
    g_have_remote_ssrc = 0;
    g_remote_seq = 0;
    g_phase = 0;
    g_sent = 0;
    g_recv = 0;
    g_rtcp_sent = 0;
    g_octets = 0;
    g_have_uplink = 0;
    g_have_media_peer = 0;
    g_next_ms = now_ms();
    g_rtcp_next_ms = g_next_ms + RTCP_FIRST_MS;
    if (g_media_fd >= 0)
        close(g_media_fd);
    g_media_fd = media_bind();
    klog(LOG_INFO, "rtp start PCMU %d -> %d mux=%d", local_port, remote_port,
         g_rtcp_mux);
    return 0;
}

void rtp_stop(void)
{
    if (g_fd >= 0)
        close(g_fd);
    g_fd = -1;
    if (g_rtcp_fd >= 0)
        close(g_rtcp_fd);
    g_rtcp_fd = -1;
    if (g_media_fd >= 0)
        close(g_media_fd);
    g_media_fd = -1;
    g_have_media_peer = 0;
    g_have_uplink = 0;
    if (g_active)
        klog(LOG_INFO, "rtp stop sent=%u recv=%u rtcp=%u",
             g_sent, g_recv, g_rtcp_sent);
    g_active = 0;
}

int rtp_active(void) { return g_active; }
int rtp_fd(void) { return g_fd; }
int rtp_rtcp_fd(void) { return g_rtcp_fd; }

int rtp_poll_ms(void)
{
    if (!g_active)
        return -1;
    long n = now_ms();
    long d = g_next_ms - n;
    if (d < 0)
        d = 0;
    long r = g_rtcp_next_ms - n;
    if (r < 0)
        r = 0;
    if (r < d)
        d = r;
    if (d > RTP_PTIME_MS)
        d = RTP_PTIME_MS;
    return (int)d;
}

static void rtp_send_one(void)
{
    unsigned char pkt[RTP_HDR + PCMU_SAMPLES];
    pkt[0] = 0x80;
    pkt[1] = (unsigned char)(g_pt & 0x7f);
    if (g_sent == 0)
        pkt[1] |= 0x80; /* marker on first packet */
    pkt[2] = (unsigned char)(g_seq >> 8);
    pkt[3] = (unsigned char)g_seq;
    pkt[4] = (unsigned char)(g_ts >> 24);
    pkt[5] = (unsigned char)(g_ts >> 16);
    pkt[6] = (unsigned char)(g_ts >> 8);
    pkt[7] = (unsigned char)g_ts;
    pkt[8] = (unsigned char)(g_ssrc >> 24);
    pkt[9] = (unsigned char)(g_ssrc >> 16);
    pkt[10] = (unsigned char)(g_ssrc >> 8);
    pkt[11] = (unsigned char)g_ssrc;
    media_drain_uplink();
    if (g_have_uplink || g_uplink[80])
        memcpy(pkt + RTP_HDR, g_uplink, PCMU_SAMPLES);
    else
        for (int i = 0; i < PCMU_SAMPLES; i++)
            pkt[RTP_HDR + i] = linear_to_ulaw(0);
    g_have_uplink = 0;
    ssize_t w = sendto(g_fd, pkt, sizeof(pkt), 0,
                       (struct sockaddr *)&g_dst, g_dlen);
    if (w == (ssize_t)sizeof(pkt)) {
        g_seq++;
        g_ts += PCMU_SAMPLES;
        g_sent++;
        g_octets += PCMU_SAMPLES;
        if (g_sent == 1 || (g_sent % 50) == 0)
            klog(LOG_INFO, "rtp sent=%u recv=%u", g_sent, g_recv);
    } else if (w < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        klog(LOG_WARN, "rtp send errno=%d", errno);
    }
}

static void rtp_take(const unsigned char *buf, ssize_t r)
{
    if (r <= 0)
        return;
    /* RFC 5761 §4: RTCP PT is 200-204. Do not decode as PCMU.
     * Mux is SDP-only (RFC 5761 §5.1.3) — PJSIP/Asterisk set
     * remote_rtcp_mux from a=rtcp-mux, never from a stray packet. */
    if (is_rtcp(buf, r))
        return;
    if (r < RTP_HDR || (buf[0] & 0xc0) != 0x80)
        return;
    g_recv++;
    g_remote_seq = (uint16_t)((buf[2] << 8) | buf[3]);
    if (!g_have_remote_ssrc) {
        g_remote_ssrc = ((uint32_t)buf[8] << 24) | ((uint32_t)buf[9] << 16) |
                        ((uint32_t)buf[10] << 8) | buf[11];
        g_have_remote_ssrc = 1;
    }
    int hdr = 12 + ((buf[0] & 0x0f) * 4);
    if ((buf[0] & 0x10) && r >= hdr + 4) {
        int ext = (buf[hdr + 2] << 8) | buf[hdr + 3];
        hdr += 4 + ext * 4;
    }
    if (hdr < r)
        media_push_downlink(buf + hdr, (int)r - hdr);
}

static void rtp_drain(void)
{
    unsigned char buf[2048];
    if (g_fd >= 0) {
        for (;;) {
            ssize_t r = recv(g_fd, buf, sizeof(buf), 0);
            if (r <= 0)
                break;
            rtp_take(buf, r);
        }
    }
    if (g_rtcp_fd >= 0) {
        for (;;) {
            ssize_t r = recv(g_rtcp_fd, buf, sizeof(buf), 0);
            if (r <= 0)
                break;
            /* Inbound RR/SR: we don't need the numbers, just drain. */
        }
    }
}

void rtp_tick(void)
{
    if (!g_active || g_fd < 0)
        return;
    rtp_drain();
    long n = now_ms();
    int steps = 0;
    while (n >= g_next_ms && steps < 5) {
        rtp_send_one();
        g_next_ms += RTP_PTIME_MS;
        steps++;
        n = now_ms();
    }
    if (g_next_ms < n)
        g_next_ms = n + RTP_PTIME_MS;
    if (n >= g_rtcp_next_ms) {
        rtp_send_rtcp();
        /* PJSIP: (PJMEDIA_RTCP_INTERVAL-500 + rand%1000) msec. */
        g_rtcp_next_ms = n + (RTCP_INTERVAL_MS - 500) +
                         (long)(rand_u64() % 1000);
    }
}
