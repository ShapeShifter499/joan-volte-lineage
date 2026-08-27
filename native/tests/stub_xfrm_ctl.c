/* stub_xfrm_ctl.c — host-test stand-in for the device-only xfrm module.
 * Records the install request so tests can assert on it later. */
#include <string.h>

#include "xfrm.h"

static xfrm_status_t g_last_stub_status;
static int g_stub_install_calls;

int xfrm_install(
        const char *ue,
        const char *pcscf,
        const sec_agree_t *ue_sec,
        const sec_agree_t *pcscf_sec,
        const uint8_t *ck,
        const uint8_t *ik,
        xfrm_status_t *st)
{
    (void)ue;
    (void)pcscf;
    (void)ue_sec;
    (void)pcscf_sec;
    (void)ck;
    (void)ik;
    memset(&g_last_stub_status, 0, sizeof(g_last_stub_status));
    for (int i = 0; i < 4; i++)
        g_last_stub_status.sa_ok[i] = 1;
    g_last_stub_status.pol_ok = 1;
    if (st)
        *st = g_last_stub_status;
    g_stub_install_calls++;
    return 0;
}

int stub_xfrm_calls(void)
{
    return g_stub_install_calls;
}
