/* xfrm.h — kernel IPsec SA/policy installation via NETLINK_XFRM. */
#ifndef JOAN_IMS_XFRM_H
#define JOAN_IMS_XFRM_H

#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>

#include "secagree.h"

/* Result detail for status reporting (no key material). */
typedef struct {
    int sa_ok[4];
    int pol_ok;
} xfrm_status_t;

/* Install the full RFC 3329 4-SA / 4-policy set (UDP+TCP selectors).
 * ue/pcscf are text IPs. Returns 0 on success; partial failures recorded
 * in st. Requires CAP_NET_ADMIN (netmgrd domain has it). */
int xfrm_install(
        const char *ue,
        const char *pcscf,
        const sec_agree_t *ue_sec,
        const sec_agree_t *pcscf_sec,
        const uint8_t *ck,
        const uint8_t *ik,
        xfrm_status_t *st);

#endif /* JOAN_IMS_XFRM_H */
