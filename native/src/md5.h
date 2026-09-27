/* md5.h — RFC 1321 MD5 (public-domain-style portable implementation). */
#ifndef JOAN_IMS_MD5_H
#define JOAN_IMS_MD5_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint32_t a, b, c, d;
    uint64_t nbytes;
    uint8_t buf[64];
    size_t buflen;
} md5_ctx;

void md5_init(md5_ctx *ctx);
void md5_update(md5_ctx *ctx, const void *data, size_t len);
void md5_final(md5_ctx *ctx, uint8_t digest[16]);

void md5(const void *data, size_t len, uint8_t digest[16]);

#endif /* JOAN_IMS_MD5_H */
