#include "config.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "util.h"

void cfg_init(ua_config_t *c)
{
    memset(c, 0, sizeof(*c));
    snprintf(c->id.realm, sizeof(c->id.realm), "%s", "msg.pc.t-mobile.com");
    c->id.local_port = 5060;
    c->id.pcscf_port = 5060;
    joan_sec_params_default(&c->mine);
}

static void urldecode(char *s)
{
    char *o = s;
    while (*s) {
        if (s[0] == '%' && s[1] && s[2]) {
            int hi = s[1], lo = s[2];
            int hv = (hi >= '0' && hi <= '9') ? hi - '0'
                   : (hi >= 'a' && hi <= 'f') ? hi - 'a' + 10
                   : (hi >= 'A' && hi <= 'F') ? hi - 'A' + 10 : -1;
            int lv = (lo >= '0' && lo <= '9') ? lo - '0'
                   : (lo >= 'a' && lo <= 'f') ? lo - 'a' + 10
                   : (lo >= 'A' && lo <= 'F') ? lo - 'A' + 10 : -1;
            if (hv >= 0 && lv >= 0) {
                *o++ = (char)((hv << 4) | lv);
                s += 3;
                continue;
            }
        }
        *o++ = *s++;
    }
    *o = '\0';
}

static void sanitize_lws(char *s)
{
    for (char *p = s; *p; p++)
        if ((unsigned char)*p < 0x20)
            *p = ' ';
}

int cfg_apply_line(ua_config_t *c, const char *line)
{
    char key[64], val[512];
    const char *eq = strchr(line, '=');
    if (!eq)
        return -1;
    size_t kl = (size_t)(eq - line);
    if (kl == 0 || kl >= sizeof(key))
        return -1;
    memcpy(key, line, kl);
    key[kl] = '\0';
    snprintf(val, sizeof(val), "%s", eq + 1);

    if (!strcmp(key, "IMPI")) {
        urldecode(val);
        sanitize_lws(val);
        snprintf(c->id.impi, sizeof(c->id.impi), "%s", val);
        c->id.have_id = c->id.impi[0] && strchr(c->id.impi, '@') != NULL;
    } else if (!strcmp(key, "IMPU")) {
        urldecode(val);
        sanitize_lws(val);
        snprintf(c->id.impu, sizeof(c->id.impu), "%s", val);
    } else if (!strcmp(key, "DOMAIN")) {
        urldecode(val);
        sanitize_lws(val);
        if (val[0])
            snprintf(c->id.realm, sizeof(c->id.realm), "%s", val);
    } else if (!strcmp(key, "LOCAL_IP")) {
        snprintf(c->id.local_ip, sizeof(c->id.local_ip), "%s", val);
    } else if (!strcmp(key, "LOCAL_PORT")) {
        c->id.local_port = atoi(val);
    } else if (!strcmp(key, "PCSCF")) {
        snprintf(c->id.pcscf, sizeof(c->id.pcscf), "%s", val);
    } else if (!strcmp(key, "PCSCF_PORT")) {
        c->id.pcscf_port = atoi(val);
    } else if (!strcmp(key, "IMEI")) {
        snprintf(c->id.imei, sizeof(c->id.imei), "%.15s", val);
    } else {
        return -2;
    }
    return 0;
}
