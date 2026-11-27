#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct lua_State;

/**
 * GGNext Native Plugin Host ABI v1
 *
 * Versioned C function-pointer table exposed to external C/C++ plugin shared libraries (.so).
 * External plugins export either:
 *   1. Standard Lua C module entrypoint:
 *        int luaopen_<module>(struct lua_State* L);
 *      (and retrieve GGNextHostApiV1* from Lua registry key GGNEXT_HOST_API_REGISTRY_KEY)
 *   2. Extended GGNext plugin entrypoint:
 *        int ggnext_plugin_init_<module>(struct lua_State* L, const GGNextHostApiV1* host_api);
 */
#define GGNEXT_HOST_API_VERSION 1u
#define GGNEXT_HOST_API_REGISTRY_KEY "ggnext.host_api_v1"

enum GGNextLogLevel {
    GGNEXT_LOG_DEBUG = 0,
    GGNEXT_LOG_INFO  = 1,
    GGNEXT_LOG_WARN  = 2,
    GGNEXT_LOG_ERROR = 3
};

typedef struct GGNextMemoryRegionC {
    uint64_t start;
    uint64_t end;
    uint32_t perms;       /* bit0=R, bit1=W, bit2=X */
    uint32_t region_flag; /* GG REGION_* bitmask */
    char     code[8];     /* Short tag: Ch, Jh, Ca, Cd, Cb, A, Xa, Xs, ... */
    char     path[256];   /* Backing file path or [anon] */
} GGNextMemoryRegionC;

typedef struct GGNextHostApiV1 {
    uint32_t abi_version;
    uint32_t struct_size;
    void*    user_data;

    /* Target process metadata */
    int32_t  (*get_target_pid)(void* ctx);
    int32_t  (*is_target_64bit)(void* ctx);

    /* Direct cross-process memory I/O */
    int64_t  (*read_memory)(void* ctx, uint64_t address, void* out_buf, size_t size);
    int32_t  (*write_memory)(void* ctx, uint64_t address, const void* in_buf, size_t size);

    /* Virtual memory region query */
    int32_t  (*list_regions)(void* ctx, GGNextMemoryRegionC* out_regions, size_t max_count);

    /* Remote target page allocation (prot: PROT_READ|PROT_WRITE|PROT_EXEC) */
    uint64_t (*allocate_page)(void* ctx, uint64_t hint_addr, size_t size, int32_t prot);

    /* Assembly / Disassembly (arch: 0=ARM, 1=THUMB, 2=ARM64, 3=X86, 4=X86_64) */
    int32_t  (*assemble)(void* ctx, int32_t arch, uint64_t address, const char* code,
                         uint8_t* out_bytes, size_t max_bytes);
    int32_t  (*disassemble_one)(void* ctx, int32_t arch, uint64_t address,
                                const uint8_t* bytes, size_t len,
                                char* out_buf, size_t out_cap);

    /* Hardware breakpoint controls */
    int32_t  (*bp_add)(void* ctx, uint64_t address, int32_t bp_type, int32_t bp_size, int32_t bp_mode);
    int32_t  (*bp_remove)(void* ctx, int32_t bp_id);

    /* Logging & Lua/UI event dispatch */
    void     (*log_message)(void* ctx, int32_t level, const char* tag, const char* msg);
    int32_t  (*emit_lua_event)(void* ctx, const char* channel, const char* payload_json);

    /* Generic host bridge RPC (JSON args -> JSON result) */
    int32_t  (*invoke_host_json)(void* ctx, const char* method, const char* args_json,
                                 char* out_buf, size_t out_cap);
} GGNextHostApiV1;

typedef int (*GGNextPluginLuaOpenFn)(struct lua_State* L);
typedef int (*GGNextPluginInitFn)(struct lua_State* L, const GGNextHostApiV1* host_api);

#ifdef __cplusplus
}
#endif
