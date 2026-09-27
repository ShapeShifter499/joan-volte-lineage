#define _GNU_SOURCE
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static const char *level_tag(int level)
{
    switch (level) {
    case LOG_INFO: return "I";
    case LOG_WARN: return "W";
    case LOG_ERR: return "E";
    default: return "?";
    }
}

/* Log sink.
 *
 * init gives this daemon /dev/null on fd 0/1/2, so stderr is not a channel.
 * The sink is therefore a file, and picking its path is a MAC+DAC problem:
 * we run in domain netmgrd as uid root gid radio, so the only directory we
 * can prove is writable is netmgrd's own /data/vendor/netmgr
 * (netmgrd_data_file, radio:radio 0770). The remaining candidates are
 * bring-up conveniences for a permissive or rooted device.
 *
 * A previous revision opened a path whose parent directory did not exist,
 * discarded both open() failures and logged nothing for hours while looking
 * exactly like a healthy daemon. So this version must never fail silently:
 * it creates the parent, caches the fd, and records the path it settled on
 * (or the errno it died with) where the ctl STATUS verb can report it.
 */
static const char *const k_log_candidates[] = {
    "/data/vendor/netmgr/joan-ims-ua.log",
    "/data/vendor/joan-ims/ua.log",
    "/data/local/tmp/joan-ua.log",
};

static int g_log_fd = -1;
static char g_log_path[128];
static int g_log_errno;

/* mkdir the parent of path, ignoring an existing directory. */
static void mkdir_parent(const char *path)
{
    char dir[128];
    snprintf(dir, sizeof(dir), "%s", path);
    char *slash = strrchr(dir, '/');
    if (!slash || slash == dir)
        return;
    *slash = '\0';
    if (mkdir(dir, 0770) == 0 || errno == EEXIST)
        return;
    /* Parent missing too: one level up, then retry. Enough for our paths. */
    char *up = strrchr(dir, '/');
    if (up && up != dir) {
        *up = '\0';
        if (mkdir(dir, 0770) != 0 && errno != EEXIST)
            return;
        *up = '/';
        mkdir(dir, 0770);
    }
}

static void klog_sink_open(void)
{
    if (g_log_fd >= 0)
        return;
    for (size_t i = 0; i < sizeof(k_log_candidates) / sizeof(*k_log_candidates);
         i++) {
        const char *path = k_log_candidates[i];
        mkdir_parent(path);
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0640);
        if (fd >= 0) {
            g_log_fd = fd;
            snprintf(g_log_path, sizeof(g_log_path), "%s", path);
            g_log_errno = 0;
            return;
        }
        g_log_errno = errno;
    }
    /* Every candidate failed. Remember why; STATUS will surface it. */
    g_log_path[0] = '\0';
}

const char *klog_sink_status(char *buf, size_t buflen)
{
    if (g_log_fd >= 0 && g_log_path[0])
        snprintf(buf, buflen, "%s", g_log_path);
    else
        snprintf(buf, buflen, "none errno=%d", g_log_errno);
    return buf;
}

void klog_banner(const char *what)
{
    char sink[160];
    char probe[256];
    klog_sink_open();
    /* Positive control: if this line is not in the file, the channel is
     * dead and nothing else in the log can be trusted as evidence. */
    snprintf(probe, sizeof(probe), "[I] --- %s: log sink open (%s) ---",
             what, klog_sink_status(sink, sizeof(sink)));
    klog_raw(probe);
}

void klog_raw(const char *line)
{
    dprintf(2, "joan-ims: %s\n", line);
    klog_sink_open();
    if (g_log_fd >= 0) {
        long ms = now_ms();
        dprintf(g_log_fd, "[%ld.%03ld] joan-ims: %s\n",
                ms / 1000, ms % 1000, line);
    }
}

void klog(int level, const char *fmt, ...)
{
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    char line[600];
    snprintf(line, sizeof(line), "[%s] %s", level_tag(level), msg);
    klog_raw(line);
}

int read_file_str(const char *path, char *buf, size_t buflen)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return -1;
    ssize_t n = read(fd, buf, buflen - 1);
    close(fd);
    if (n <= 0)
        return -1;
    buf[n] = '\0';
    return 0;
}

int write_file_str(const char *path, const char *buf)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0)
        return -1;
    ssize_t n = strlen(buf);
    ssize_t w = write(fd, buf, n);
    close(fd);
    return (w == n) ? 0 : -1;
}

long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

int hex_encode(const uint8_t *in, size_t inlen, char *out, size_t outlen)
{
    if (inlen * 2 + 1 > outlen)
        return -1;
    static const char hx[] = "0123456789abcdef";
    for (size_t i = 0; i < inlen; i++) {
        out[i * 2] = hx[in[i] >> 4];
        out[i * 2 + 1] = hx[in[i] & 0xf];
    }
    out[inlen * 2] = '\0';
    return 0;
}

static int nib(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

size_t hex_decode(const char *hex, uint8_t *out, size_t outlen)
{
    size_t n = strlen(hex);
    size_t max = n / 2;
    if (max > outlen)
        max = outlen;
    for (size_t i = 0; i < max; i++) {
        int hi = nib(hex[i * 2]);
        int lo = nib(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0)
            return i;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return max;
}

int b64_decode(const char *in, uint8_t *out, size_t outmax)
{
    static const int8_t T[256] = {
        ['A'] = 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25,
        ['a'] = 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51,
        ['0'] = 52, 53, 54, 55, 56, 57, 58, 59, 60, 61,
        ['+'] = 62, ['/'] = 63, ['='] = -2,
    };
    size_t o = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (const char *p = in; *p; p++) {
        int8_t v = T[(uint8_t)*p];
        if (v == -2) break; /* '=' padding: stop */
        if (v < 0) continue; /* whitespace/CR/LF */
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (o >= outmax)
                return (int)o;
            out[o++] = (uint8_t)((acc >> bits) & 0xff);
        }
    }
    return (int)o;
}

uint64_t rand_u64(void)
{
    uint64_t v = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t r = read(fd, &v, sizeof(v));
        (void)r;
        close(fd);
        if (v != 0)
            return v;
    }
    return ((uint64_t)getpid() << 32) ^ (uint64_t)now_ms();
}

unsigned rand_below(unsigned bound)
{
    if (bound == 0)
        return 0;
    for (;;) {
        uint64_t v = rand_u64();
        uint64_t lim = UINT64_MAX - UINT64_MAX % bound;
        if (v < lim)
            return (unsigned)(v % bound);
    }
}
