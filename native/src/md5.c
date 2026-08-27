#include "md5.h"

#include <string.h>

static const uint32_t K[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
    0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
    0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
    0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
    0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
    0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
    0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
    0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
    0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
};

static const int R[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

static inline uint32_t rotl(uint32_t x, int n)
{
    return (x << n) | (x >> (32 - n));
}

static void md5_block(md5_ctx *ctx, const uint8_t *p)
{
    uint32_t m[16];
    for (int i = 0; i < 16; i++)
        m[i] = (uint32_t)p[i * 4]
             | ((uint32_t)p[i * 4 + 1] << 8)
             | ((uint32_t)p[i * 4 + 2] << 16)
             | ((uint32_t)p[i * 4 + 3] << 24);

    uint32_t a = ctx->a, b = ctx->b, c = ctx->c, d = ctx->d;
    for (int i = 0; i < 64; i++) {
        uint32_t f;
        int g;
        if (i < 16) {
            f = (b & c) | (~b & d);
            g = i;
        } else if (i < 32) {
            f = (d & b) | (~d & c);
            g = (5 * i + 1) % 16;
        } else if (i < 48) {
            f = b ^ c ^ d;
            g = (3 * i + 5) % 16;
        } else {
            f = c ^ (b | ~d);
            g = (7 * i) % 16;
        }
        f = f + a + K[i] + m[g];
        a = d;
        d = c;
        c = b;
        b = b + rotl(f, R[i]);
    }
    ctx->a += a;
    ctx->b += b;
    ctx->c += c;
    ctx->d += d;
}

void md5_init(md5_ctx *ctx)
{
    ctx->a = 0x67452301;
    ctx->b = 0xefcdab89;
    ctx->c = 0x98badcfe;
    ctx->d = 0x10325476;
    ctx->nbytes = 0;
    ctx->buflen = 0;
}

void md5_update(md5_ctx *ctx, const void *data, size_t len)
{
    const uint8_t *p = data;
    ctx->nbytes += len;
    while (len) {
        size_t take = 64 - ctx->buflen;
        if (take > len)
            take = len;
        memcpy(ctx->buf + ctx->buflen, p, take);
        ctx->buflen += take;
        p += take;
        len -= take;
        if (ctx->buflen == 64) {
            md5_block(ctx, ctx->buf);
            ctx->buflen = 0;
        }
    }
}

void md5_final(md5_ctx *ctx, uint8_t digest[16])
{
    uint64_t bits = ctx->nbytes * 8;
    uint8_t pad = 0x80;
    md5_update(ctx, &pad, 1);
    uint8_t z = 0;
    while (ctx->buflen != 56)
        md5_update(ctx, &z, 1);
    uint8_t tail[8];
    for (int i = 0; i < 8; i++)
        tail[i] = (uint8_t)(bits >> (8 * i));
    md5_update(ctx, tail, 8);
    uint32_t st[4] = { ctx->a, ctx->b, ctx->c, ctx->d };
    for (int i = 0; i < 4; i++) {
        digest[i * 4] = (uint8_t)st[i];
        digest[i * 4 + 1] = (uint8_t)(st[i] >> 8);
        digest[i * 4 + 2] = (uint8_t)(st[i] >> 16);
        digest[i * 4 + 3] = (uint8_t)(st[i] >> 24);
    }
}

void md5(const void *data, size_t len, uint8_t digest[16])
{
    md5_ctx ctx;
    md5_init(&ctx);
    md5_update(&ctx, data, len);
    md5_final(&ctx, digest);
}
