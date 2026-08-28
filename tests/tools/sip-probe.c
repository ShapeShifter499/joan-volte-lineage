/* sip-probe.c -- drive the real parser and builder with a synthetic 401
 * and print exactly what would go on the wire.
 *
 * Host-only. Every value here is invented: the IMPI uses the reserved test
 * PLMN (MCC 001 / MNC 01) and the realm is ims.example.net. Never paste a
 * real IMPI, IMSI, MSISDN, nonce, RES, CK or IK into this file -- the point
 * of the probe is that it needs none of them.
 *
 * It answers questions like "what does an AKAv2 challenge actually make us
 * emit", which is how the AKAv2-advertised-but-never-computed defect was
 * found: an AKAv1 and an AKAv2 challenge produce an identical response
 * hash while the header advertises whichever was challenged.
 *
 * Build:  cc -I../../native/src -o sip-probe \
 *             ../../native/src/{util,md5,secagree,sip,config}.c sip-probe.c
 */
#include <stdio.h>
#include <string.h>
#include <strings.h>
#include "sip.h"

static void run(const char *label, const char *raw401)
{
    sip_response_t r;
    memset(&r, 0, sizeof(r));
    printf("\n=== %s ===\n", label);
    if (parse_response(raw401, strlen(raw401), &r) != 0) {
        printf("parse_response FAILED\n");
        return;
    }
    printf("status=%d www_auth=[%s]\n", r.status, r.www_authenticate);

    /* --- verbatim copy of ua.c:196-223 extraction --- */
    sip_challenge_t ch; memset(&ch, 0, sizeof(ch));
    const char *p = strcasestr(r.www_authenticate, "nonce=\"");
    if (!p) { printf("no nonce\n"); return; }
    p += 7;
    const char *e = strchr(p, '"');
    size_t nl = e ? (size_t)(e - p) : strlen(p);
    if (nl >= sizeof(ch.nonce_b64)) nl = sizeof(ch.nonce_b64) - 1;
    memcpy(ch.nonce_b64, p, nl); ch.nonce_b64[nl] = '\0';
    ch.have_nonce = 1;
    snprintf(ch.algorithm, sizeof(ch.algorithm), "AKAv1-MD5");
    {
        const char *al = strcasestr(r.www_authenticate, "algorithm=");
        if (al) {
            al += 10;
            char tmp[32]; size_t ti = 0;
            while (*al && *al != ',' && *al != '"' && ti + 1 < sizeof(tmp))
                tmp[ti++] = *al++;
            tmp[ti] = '\0';
            if (ti) snprintf(ch.algorithm, sizeof(ch.algorithm), "%s", tmp);
        }
    }
    printf("parsed nonce=[%s]\n", ch.nonce_b64);
    printf("parsed algorithm=[%s]  (len %zu, hex:", ch.algorithm, strlen(ch.algorithm));
    for (size_t i = 0; i < strlen(ch.algorithm); i++)
        printf(" %02x", (unsigned char)ch.algorithm[i]);
    printf(")\n");
    if (r.have_sec_server) {
        snprintf(ch.sec_server, sizeof(ch.sec_server), "%s", r.security_server);
        ch.have_sec_server = 1;
    }

    sip_identity_t id; memset(&id, 0, sizeof(id));
    snprintf(id.impi, sizeof(id.impi), "001010000000000@ims.mnc001.mcc001.3gppnetwork.org");
    snprintf(id.realm, sizeof(id.realm), "ims.example.net");
    snprintf(id.local_ip, sizeof(id.local_ip), "2607:fb90::1");
    id.local_port = 5060;
    snprintf(id.pcscf, sizeof(id.pcscf), "2607:fb90::99");
    id.pcscf_port = 5060;
    snprintf(id.imei, sizeof(id.imei), "355558081234567");
    id.have_id = 1;

    sec_params_t mine; joan_sec_params_default(&mine);
    sip_txn_t t; txn_new(&t, &id, mine);

    static const unsigned char res8[8] = {0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef};
    char msg[SIP_MAX_MSG];
    int n = build_register(msg, sizeof(msg), &id, &t, 2, &ch,
                           res8, sizeof(res8), NULL, NULL);
    if (n <= 0) { printf("build_register failed %d\n", n); return; }
    const char *a = strcasestr(msg, "\r\nAuthorization:");
    if (a) {
        a += 2;
        const char *ae = strstr(a, "\r\n");
        printf("WIRE -> %.*s\n", (int)(ae - a), a);
    }
    /* show the raw bytes of the algorithm= region on the wire */
    const char *alg = strcasestr(msg, "algorithm=");
    if (alg) {
        printf("wire algorithm bytes:");
        for (int i = 0; i < 24 && alg[i]; i++)
            printf(" %02x", (unsigned char)alg[i]);
        printf("\n");
    }
}

int main(void)
{
    /* Case 1: algorithm as a trailing token (very common from real P-CSCF) */
    run("401 with algorithm LAST (trailing token)",
        "SIP/2.0 401 Unauthorized\r\n"
        "Via: SIP/2.0/UDP [2607:fb90::1]:5060;branch=z9hG4bKabc\r\n"
        "WWW-Authenticate: Digest realm=\"ims.example.net\", "
        "nonce=\"K1hHYW5vbmNlMDAwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OQ==\", "
        "qop=\"auth\", algorithm=AKAv1-MD5\r\n"
        "Security-Server: ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp; mod=trans; spi-c=1000; spi-s=1001; port-c=5100; port-s=5101\r\n"
        "Content-Length: 0\r\n\r\n");

    /* Case 2: AKAv2-MD5 challenge */
    run("401 with algorithm=AKAv2-MD5",
        "SIP/2.0 401 Unauthorized\r\n"
        "WWW-Authenticate: Digest realm=\"ims.example.net\", "
        "nonce=\"K1hHYW5vbmNlMDAwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OQ==\", "
        "algorithm=AKAv2-MD5, qop=\"auth\"\r\n"
        "Security-Server: ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp; mod=trans; spi-c=1000; spi-s=1001; port-c=5100; port-s=5101\r\n"
        "Content-Length: 0\r\n\r\n");

    /* Case 3: algorithm quoted (some cores quote it) */
    run("401 with algorithm=\"AKAv1-MD5\" (quoted)",
        "SIP/2.0 401 Unauthorized\r\n"
        "WWW-Authenticate: Digest realm=\"ims.example.net\", "
        "nonce=\"K1hHYW5vbmNlMDAwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OQ==\", "
        "algorithm=\"AKAv1-MD5\", qop=\"auth\"\r\n"
        "Security-Server: ipsec-3gpp; alg=hmac-sha-1-96; ealg=aes-cbc; prot=esp; mod=trans; spi-c=1000; spi-s=1001; port-c=5100; port-s=5101\r\n"
        "Content-Length: 0\r\n\r\n");
    return 0;
}
