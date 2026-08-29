/* sip.h — SIP REGISTER builder + response parsing for joan IMS.
 * Ported from the proven pmOS builders (joan_ims_register.py / ua / ipsec).
 */
#ifndef JOAN_IMS_SIP_H
#define JOAN_IMS_SIP_H

#include <stddef.h>
#include <stdint.h>

#define SIP_MAX_MSG 4096

typedef struct {
    char impi[256];        /* user@realm — never logged */
    char impu[256];        /* public identity (sip:…) or empty to use impi */
    char realm[128];       /* msg.pc.t-mobile.com */
    char local_ip[64];     /* unbracketed text form */
    int local_port;        /* Via: where we send from, and get responses */
    int contact_port;      /* Contact: where the network sends us REQUESTS.
                            * RFC 3329 -- this is the protected server port,
                            * not the client port. 0 means use local_port. */
    char pcscf[80];
    int pcscf_port;
    char imei[24];
    char iface[32];        /* IMS PDN netdev (e.g. rmnet_data1); optional */
    int have_id;           /* impi non-empty with '@' */
} sip_identity_t;

typedef struct {
    unsigned spi_c, spi_s;
    unsigned port_c, port_s;
} sec_params_t;

/* Default random Security-Client params (spi 256..2^31-1, port pair). */
void joan_sec_params_default(sec_params_t *out);

/* The Security-Client header VALUE we offer (both REGISTERs identical). */
void joan_security_client_value(const sec_params_t *m, char *dst, size_t n);

/* State carried between first and second REGISTER. */
typedef struct {
    char call_id[64];
    char from_tag[16];
    char branch[40];
    sec_params_t mine;     /* our Security-Client params */
} sip_txn_t;

typedef struct {
    char nonce_b64[512];
    char algorithm[32];
    int have_nonce;
    char sec_server[512];
    int have_sec_server;
} sip_challenge_t;

typedef struct {
    int status;                       /* e.g. 200, 401, 403 */
    char reason[64];
    char www_authenticate[1024];
    int have_www_auth;
    char security_server[512];
    int have_sec_server;
    char service_route[512];
    int have_service_route;
    char contact[256];                /* remote target for in-dialog reqs */
    int have_contact;
    char record_route[512];
    int have_record_route;
    char p_associated_uri[512];       /* public identities the core registered */
    int have_p_associated_uri;
    int expires;                      /* -1 if absent */
    char date_hdr[64];
    int rseq;                         /* RFC 3262; 0 if absent */
    char require[128];
    int session_expires;              /* RFC 4028; 0 if absent */
    char se_refresher[8];             /* "uac", "uas", or empty */
} sip_response_t;

void txn_new(sip_txn_t *t, const sip_identity_t *id, sec_params_t mine);

/* Build REGISTER into out. Returns length or -1. cseq 1 = unprotected. */
int build_register(
        char *out, size_t outlen,
        const sip_identity_t *id,
        sip_txn_t *txn,
        int cseq,
        const sip_challenge_t *ch,   /* NULL => unprotected */
        const uint8_t *res,          /* AKA RES (8 or 16 bytes) or NULL */
        size_t res_len,              /* exact RES byte length */
        const uint8_t *ck,
        const uint8_t *ik);

int parse_response(const char *msg, size_t len, sip_response_t *r);

/* Method of an inbound request ("INVITE", "BYE", ...), or "" if `msg` is a
 * response rather than a request. */
int sip_request_method(const char *msg, char *out, size_t outlen);

void mk_tag_public(char *dst, size_t n);

/* Response to an inbound request. Echoes the Via, Record-Route, From,
 * Call-ID and CSeq the request carried -- a response that does not is
 * unroutable -- and adds our tag to To. `sdp` may be NULL. */
int build_response(char *out, size_t outlen,
                   const char *req,
                   int code, const char *reason,
                   const sip_identity_t *id,
                   const char *to_tag,
                   const char *sdp);

/* SDP answer accepting one codec from an offer. Prefers PCMU: that is
 * the only codec we can send today (see rtp.c). */
int sdp_answer(char *out, size_t outlen, const char *ip, int rtp_port,
               const char *offer);

typedef struct {
    char ip[64];
    int port;
    int pt;          /* first payload type on m=audio */
    int have_pcmu;   /* 1 if PT 0 or an rtpmap names PCMU */
    int have_rtcp_mux;
} sdp_media_t;

/* Parse c=/m=audio from a SIP message (headers + body) or a bare SDP
 * body. Returns 0 if an audio m-line with a destination was found. */
int sdp_parse_media(const char *msg, sdp_media_t *out);

/* Pull one Content-Length-framed SIP message out of a TCP buffer.
 * Returns 1 and copies into out if complete, 0 if more data is needed,
 * -1 on overflow or a malformed length. Consumes the message from buf. */
int sip_extract_one(char *buf, size_t *buflen, char *out, size_t outmax);

/* Dialog identifiers minted by build_invite(), needed for the ACK. */
typedef struct {
    char call_id[64];
    char from_tag[16];
    char branch[40];
    int  cseq;
} sip_dialog_t;

/* MO INVITE with an SDP offer. `route` is the Service-Route learned at
 * registration (may be empty), `sec_verify` the Security-Server value we
 * must echo. Returns length or -1. */
int build_invite(char *out, size_t outlen,
                 const sip_identity_t *id,
                 const char *dest,
                 const char *route,
                 const char *sec_verify,
                 int rtp_port,
                 sip_dialog_t *dlg);

/* CANCEL withdraws an INVITE that has not been answered. RFC 3261: it
 * reuses the INVITE's Request-URI, Call-ID, From-tag, CSeq number and --
 * critically -- the INVITE's Via branch, which is how the server matches
 * it to the transaction. */
int build_cancel(char *out, size_t outlen,
                 const sip_identity_t *id,
                 const char *dest,
                 const char *route,
                 const char *sec_verify,
                 const sip_dialog_t *dlg);

/* BYE ends an established dialog. Same routing and security agreement as
 * the INVITE; CSeq is incremented past the INVITE's. */
int build_bye(char *out, size_t outlen,
              const sip_identity_t *id,
              const char *target,     /* Request-URI: remote target */
              const char *to_uri,     /* To header: the URI we dialled */
              const char *route,
              const char *sec_verify,
              const sip_dialog_t *dlg,
              const char *to_tag);

/* ACK for a 2xx, routed to the same target. */
int build_ack(char *out, size_t outlen,
              const sip_identity_t *id,
              const char *target,
              const char *to_uri,
              const char *route,
              const char *sec_verify,
              const sip_dialog_t *dlg,
              const char *to_tag);

/* RFC 4028 session refresh. CSeq is dlg->cseq+1. No SDP. */
int build_update(char *out, size_t outlen,
                 const sip_identity_t *id,
                 const char *target,
                 const char *to_uri,
                 const char *route,
                 const char *sec_verify,
                 const sip_dialog_t *dlg,
                 const char *to_tag,
                 int session_expires);

/* RFC 3262 PRACK for a reliable 1xx (RSeq). CSeq is dlg->cseq+1. */
int build_prack(char *out, size_t outlen,
                const sip_identity_t *id,
                const char *target,
                const char *to_uri,
                const char *route,
                const char *sec_verify,
                const sip_dialog_t *dlg,
                const char *to_tag,
                int rseq);

/* Digest response per RFC 3310 (AKAv1-MD5 password=RES, res_len exact). */
int aka_digest_response_hex(
        const char *username,
        const char *realm,
        const char *method,
        const char *uri,
        const char *nonce_b64,
        const uint8_t *res,
        size_t res_len,
        const char *qop,             /* "auth" or NULL */
        const char *nc,
        const char *cnonce,
        char *out_hex /* 33 */);

#endif /* JOAN_IMS_SIP_H */
