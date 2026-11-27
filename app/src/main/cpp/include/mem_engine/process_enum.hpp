#pragma once
#ifndef MEM_ENGINE_PROCESS_ENUM_HPP
#define MEM_ENGINE_PROCESS_ENUM_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace mem_engine {

enum class ProcessCategory : uint8_t {
    USER_APP        = 0, // Third-party user application (appId in 10000..19999)
    ISOLATED_APP    = 1, // Sandboxed WebView / isolated service process (appId in 90000..99999)
    SYSTEM_APP      = 2, // Privileged / system app forked from Zygote (system_server, com.android.systemui, etc.)
    NATIVE_PROCESS  = 3, // Native Linux / QA test binary with userland VM
    KERNEL_DAEMON   = 4  // Kernel thread (kthreadd child / empty VmRSS) or pure native init daemon
};

struct ProcessEntry {
    int             pid          = 0;
    int             ppid         = 0;
    uint32_t        uid          = 0;
    uint32_t        userId       = 0; // Multi-user profile ID: uid / 100000
    uint32_t        appId        = 0; // Base App ID: uid % 100000
    size_t          vmRssKB      = 0;
    bool            isSubProcess = false; // e.g., com.pkg:remote, com.pkg:push
    bool            zygoteChild  = false;
    ProcessCategory category     = ProcessCategory::KERNEL_DAEMON;
    std::string     processName;          // Full cmdline name (e.g. com.example.app:remote)
    std::string     basePackage;          // Package root before ':' (e.g. com.example.app)
};

class ProcessEnumerator {
public:
    // Android AOSP UID constants (system/core/libcutils/include/private/android_filesystem_config.h)
    static constexpr uint32_t AID_USER_OFFSET    = 100000;
    static constexpr uint32_t AID_SYSTEM         = 1000;
    static constexpr uint32_t AID_APP_START      = 10000;
    static constexpr uint32_t AID_APP_END        = 19999;
    static constexpr uint32_t AID_ISOLATED_START = 90000;
    static constexpr uint32_t AID_ISOLATED_END   = 99999;

    // Real-time cache-free enumeration of /proc
    // If androidAppsOnly == true, keeps 100% of User Apps, System Apps, and multi-process children while
    // filtering out kernel threads and pure native daemons.
    static std::vector<ProcessEntry> enumerateProcesses(bool androidAppsOnly = false);

    // Pure classification helper (also exposed for deterministic unit verification)
    static ProcessEntry classifyProcessMetadata(
        int pid,
        int ppid,
        uint32_t uid,
        size_t vmRssKB,
        const std::string& cmdline,
        const std::string& comm,
        bool parentIsZygote
    );

    // Check if a given process name Looks like an Android package or Zygote-spawned app
    static bool looksLikeAndroidPackage(const std::string& cmdline);
};

} // namespace mem_engine

#endif // MEM_ENGINE_PROCESS_ENUM_HPP
