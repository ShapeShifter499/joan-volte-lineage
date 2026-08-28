/* ctlprobe.c -- minimal client for the daemon's ctl channel.
 *
 * Speaks the abstract unix socket @joan_ims_ctl directly, so it exercises
 * the authenticated transport rather than the bring-up TCP listener. Run as
 * root: the daemon accepts uid 0 plus whatever
 * /data/vendor/netmgr/joan-ims.allowuid lists.
 *
 *   ctlprobe STATUS
 *   ctlprobe "CALL sip:+15551234567@ims.example.net"
 *   ctlprobe HANGUP
 *
 * The number above is a placeholder. Pass a real destination on the command
 * line; never commit one.
 *
 * Build:  aarch64-linux-gnu-gcc -static -O1 -o ctlprobe ctlprobe.c
 * Deploy: adb push ctlprobe /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/ctlprobe
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <stddef.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
int main(int argc, char **argv) {
    const char *req = argc > 1 ? argv[1] : "STATUS";
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un sa; memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    strcpy(sa.sun_path + 1, "joan_ims_ctl");
    socklen_t l = offsetof(struct sockaddr_un, sun_path) + 1 + strlen("joan_ims_ctl");
    if (connect(s, (struct sockaddr*)&sa, l) < 0) { perror("connect"); return 1; }
    dprintf(s, "%s\n", req);
    char buf[2048]; ssize_t n = read(s, buf, sizeof(buf)-1);
    if (n > 0) { buf[n]='\0'; printf("%s\n", buf); } else printf("(no reply, n=%zd)\n", n);
    return 0;
}
