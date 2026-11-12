#ifndef ANYTLS_ANYTLS_H
#define ANYTLS_ANYTLS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum AnyTlsLogLevel {
    ANYTLS_LOG_LEVEL_OFF = 0,
    ANYTLS_LOG_LEVEL_ERROR = 1,
    ANYTLS_LOG_LEVEL_WARN = 2,
    ANYTLS_LOG_LEVEL_INFO = 3,
    ANYTLS_LOG_LEVEL_DEBUG = 4,
    ANYTLS_LOG_LEVEL_TRACE = 5,
} AnyTlsLogLevel;

typedef struct AnyTlsTrafficStatus {
    uint64_t tx;
    uint64_t rx;
} AnyTlsTrafficStatus;

void anytls_set_log_callback(
        bool set_logger,
        void (*callback)(AnyTlsLogLevel level, const char *message, void *ctx),
        void *ctx);

void anytls_set_traffic_status_callback(
        uint32_t send_interval_secs,
        void (*callback)(const AnyTlsTrafficStatus *status, void *ctx),
        void *ctx);

int32_t anytls_client_run(
        const char *command_line,
        void (*callback)(int32_t port, void *ctx),
        void *ctx);

size_t anytls_generate_url(const char *command_line, char *buf, size_t size);

int32_t anytls_client_stop(void);

#ifdef __cplusplus
}
#endif

#endif
