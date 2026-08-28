#define _GNU_SOURCE
#include "util.h"

#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

void klog_raw(const char *line)
{
    /* stderr is /dev/null under init; mirror to a vendor-data log file so
     * bring-up debugging can read daemon klog lines without logd access. */
    dprintf(2, "joan-ims: %s\n", line);
    int fd = open("/data/misc/joan-ims/ua.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0)
        fd = open("/data/local/tmp/joan-ua.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        dprintf(fd, "joan-ims: %s\n", line);
        close(fd);
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
