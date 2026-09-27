/* secagree.h — Security-Client/Server mechanism parsing (RFC 3329). */
#ifndef JOAN_IMS_SECAGREE_H
#define JOAN_IMS_SECAGREE_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    char alg[32];      /* hmac-sha-1-96 */
    char ealg[32];     /* aes-cbc */
    uint32_t spi_c;
    uint32_t spi_s;
    uint32_t port_c;
    uint32_t port_s;
} sec_agree_t;

/* Parse one ipsec-3gpp mechanism from a Security-Client/-Server value
 * (first mechanism if comma list). Value without the header name.
 * Returns 0 ok, -1 malformed. */
int sec_agree_parse(const char *value, sec_agree_t *out);

#endif /* JOAN_IMS_SECAGREE_H */
