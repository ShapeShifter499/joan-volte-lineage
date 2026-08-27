#include "secagree.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

static void trim(char *s)
{
    size_t n = strlen(s);
    while (n && (unsigned char)s[n - 1] <= ' ')
        s[--n] = '\0';
}

static int find_kv(const char *mech, const char *key,
                   char *dst, size_t dstlen)
{
    size_t kl = strlen(key);
    const char *p = mech;
    while ((p = strstr(p, key))) {
        if (p[kl] == '=') {
            const char *v = p + kl + 1;
            /* value ends at ';' or end */
            const char *e = v;
            while (*e && *e != ';')
                e++;
            size_t vl = (size_t)(e - v);
            if (vl >= dstlen)
                vl = dstlen - 1;
            memcpy(dst, v, vl);
            dst[vl] = '\0';
            trim(dst);
            return 0;
        }
        p += kl;
    }
    return -1;
}

static uint32_t parse_u32(const char *s)
{
    /* decimal or 0x hex */
    uint32_t v = 0;
    int base = 10;
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        base = 16;
        s += 2;
    }
    while (*s) {
        int d;
        char c = (char)tolower((unsigned char)*s++);
        if (c >= '0' && c <= '9') d = c - '0';
        else if (base == 16 && c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else break;
        v = v * (uint32_t)base + (uint32_t)d;
    }
    return v;
}

int sec_agree_parse(const char *value_in, sec_agree_t *out)
{
    static const char skip_prefixes[][16] = { "Security-" };
    (void)skip_prefixes;

    char buf[512];
    snprintf(buf, sizeof(buf), "%s", value_in ? value_in : "");
    trim(buf);

    /* take first comma-separated mechanism */
    char *comma = strchr(buf, ',');
    if (comma)
        *comma = '\0';

    memset(out, 0, sizeof(*out));
    if (!strstr(buf, "ipsec-3gpp"))
        return -1;

    char tmp[64];
    if (find_kv(buf, "spi-c", tmp, sizeof(tmp)) < 0) return -1;
    out->spi_c = parse_u32(tmp);
    if (find_kv(buf, "spi-s", tmp, sizeof(tmp)) < 0) return -1;
    out->spi_s = parse_u32(tmp);
    if (find_kv(buf, "port-c", tmp, sizeof(tmp)) < 0) return -1;
    out->port_c = parse_u32(tmp);
    if (find_kv(buf, "port-s", tmp, sizeof(tmp)) < 0) return -1;
    out->port_s = parse_u32(tmp);

    if (find_kv(buf, "alg", tmp, sizeof(tmp)) == 0)
        snprintf(out->alg, sizeof(out->alg), "%s", tmp);
    else
        snprintf(out->alg, sizeof(out->alg), "hmac-sha-1-96");

    if (find_kv(buf, "ealg", tmp, sizeof(tmp)) == 0)
        snprintf(out->ealg, sizeof(out->ealg), "%s", tmp);
    else
        snprintf(out->ealg, sizeof(out->ealg), "aes-cbc");

    if (!out->spi_c || !out->spi_s || !out->port_c || !out->port_s)
        return -1;
    return 0;
}
