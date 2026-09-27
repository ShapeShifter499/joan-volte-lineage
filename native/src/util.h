/* util.h — shared helpers for joan-ims native UA. */
#ifndef JOAN_IMS_UTIL_H
#define JOAN_IMS_UTIL_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#define LOG_INFO 0
#define LOG_WARN 1
#define LOG_ERR 2

/* stderr output lands in kmsg via init stdio_to_kmsg. Identity substrings
 * (IMPI/IMPU users, nonce base64, keys) must NEVER reach klog — helper
 * below masks anything past '@'/'=' boundaries when asked. */
void klog(int level, const char *fmt, ...)
        __attribute__((format(printf, 2, 3)));

void klog_raw(const char *line);

/* Where klog actually landed, or "none errno=N" if every candidate path
 * failed. Reported by the ctl STATUS verb so a dead log channel is visible
 * without reading the log we could not write. */
const char *klog_sink_status(char *buf, size_t buflen);

/* Open the sink and write a positive-control banner. Call once at start:
 * if this line is absent from the file, the channel is dead. */
void klog_banner(const char *what);

int read_file_str(const char *path, char *buf, size_t buflen);

int write_file_str(const char *path, const char *buf);

long now_ms(void);

int hex_encode(const uint8_t *in, size_t inlen, char *out, size_t outlen);

size_t hex_decode(const char *hex, uint8_t *out, size_t outlen);

int b64_decode(const char *in, uint8_t *out, size_t outmax);

uint64_t rand_u64(void);

unsigned rand_below(unsigned bound);

#endif /* JOAN_IMS_UTIL_H */
