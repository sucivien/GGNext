#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mem_engine {

struct RemoteAllocResult {
    bool ok = false;
    uint64_t address = 0;
    size_t size = 0;
    int actualProt = 0;
    bool writeVerified = false;
    std::string error;
};

struct ElfSymbolInfo {
    std::string name;
    uint64_t address = 0;
    uint64_t offset = 0;
    uint64_t size = 0;
    std::string type;
    std::string moduleName;
    uint64_t moduleBase = 0;
};

/**
 * Allocates an anonymous memory page inside the target process address space
 * via POSIX ptrace syscall invocation (SYS_mmap / SYS_mmap2), then verifies
 * actual VMA permissions in /proc/<pid>/maps and cross-process writeability.
 */
RemoteAllocResult allocateTargetProcessPage(
    int targetPid,
    uint64_t hintAddr,
    size_t size,
    int prot
);

/**
 * Parses ELF64/ELF32 dynamic & static symbol tables for a loaded module inside
 * the target process (using /proc/<pid>/maps + ELF file/memory headers).
 */
std::vector<ElfSymbolInfo> listProcessModuleExports(
    int targetPid,
    const std::string& libFilter,
    const std::string& symFilter,
    size_t maxCount,
    uint64_t* outModuleBase = nullptr,
    std::string* outResolvedPath = nullptr,
    std::string* outError = nullptr
);

/**
 * Resolves a single exported/static symbol's runtime virtual address in targetPid.
 */
uint64_t findProcessSymbolAddress(
    int targetPid,
    const std::string& libFilter,
    const std::string& symName,
    uint64_t* outModuleBase = nullptr,
    std::string* outError = nullptr
);

/**
 * Sends a JSON command to the ShadowHook agent inside targetPid (in-process
 * direct call for self-process, or abstract Unix socket \0ggnext_hook_<pid>
 * for target processes loaded with libggnext_agent.so via wrap.<pkg>/LD_PRELOAD).
 */
bool sendHookAgentCommand(
    int targetPid,
    const std::string& reqJson,
    std::string* outRespJson,
    std::string* outError = nullptr
);

/**
 * Serializes ElfSymbolInfo list into compact single-line JSON array.
 */
std::string elfSymbolsToJson(const std::vector<ElfSymbolInfo>& symbols);

} // namespace mem_engine
