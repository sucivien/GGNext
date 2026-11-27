#include "mem_engine/process_enum.hpp"
#include <dirent.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <unordered_set>
#include <algorithm>

namespace mem_engine {

namespace {

bool isAllDigits(const char* s) noexcept {
    if (!s || *s == '\0') return false;
    while (*s) {
        if (!std::isdigit(static_cast<unsigned char>(*s))) return false;
        ++s;
    }
    return true;
}

std::string readFirstArgCmdline(int pid) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    FILE* fp = std::fopen(path, "rb");
    if (!fp) return {};

    char buf[512] = {0};
    size_t n = std::fread(buf, 1, sizeof(buf) - 1, fp);
    std::fclose(fp);
    if (n == 0) return {};

    // cmdline arguments are NUL-separated; first argument is the process/package name
    buf[n] = '\0';
    std::string firstArg(buf);
    // Trim trailing whitespace/newlines
    while (!firstArg.empty() && (firstArg.back() == '\n' || firstArg.back() == '\r' || firstArg.back() == ' ')) {
        firstArg.pop_back();
    }
    return firstArg;
}

bool readStatusMetadata(int pid, int& outPpid, uint32_t& outUid, size_t& outVmRssKB, std::string& outComm) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/status", pid);
    FILE* fp = std::fopen(path, "r");
    if (!fp) return false;

    outPpid = 0;
    outUid = 0;
    outVmRssKB = 0;
    outComm.clear();
    int tgid = 0;

    char line[512];
    while (std::fgets(line, sizeof(line), fp)) {
        if (std::strncmp(line, "Name:", 5) == 0) {
            const char* p = line + 5;
            while (*p == ' ' || *p == '\t') ++p;
            outComm = p;
            while (!outComm.empty() && (outComm.back() == '\n' || outComm.back() == '\r')) {
                outComm.pop_back();
            }
        } else if (std::strncmp(line, "Tgid:", 5) == 0) {
            tgid = std::atoi(line + 5);
        } else if (std::strncmp(line, "PPid:", 5) == 0) {
            outPpid = std::atoi(line + 5);
        } else if (std::strncmp(line, "Uid:", 4) == 0) {
            unsigned int realUid = 0;
            if (std::sscanf(line + 4, "%u", &realUid) == 1) {
                outUid = realUid;
            }
        } else if (std::strncmp(line, "VmRSS:", 6) == 0) {
            size_t rss = 0;
            if (std::sscanf(line + 6, "%zu", &rss) == 1) {
                outVmRssKB = rss;
            }
        }
    }

    std::fclose(fp);
    // Filter out threads (TIDs) that resolve when probing /proc/<tid>/status directly in sharedUserId mode
    if (tgid > 0 && tgid != pid) {
        return false;
    }
    return true;
}

} // anonymous namespace

bool ProcessEnumerator::looksLikeAndroidPackage(const std::string& cmdline) {
    if (cmdline.empty() || cmdline[0] == '/' || cmdline[0] == '[') return false;
    if (cmdline == "system_server") return true;

    // Extract base package name before any ':process_suffix'
    size_t colonPos = cmdline.find(':');
    std::string pkg = (colonPos != std::string::npos) ? cmdline.substr(0, colonPos) : cmdline;

    // Android package names have at least one dot (e.g., com.android.phone) and valid identifier characters
    size_t dotCount = 0;
    for (size_t i = 0; i < pkg.size(); ++i) {
        char c = pkg[i];
        if (c == '.') {
            if (i == 0 || i + 1 == pkg.size() || pkg[i - 1] == '.') return false;
            ++dotCount;
        } else if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_') {
            return false;
        }
    }
    return dotCount >= 1;
}

ProcessEntry ProcessEnumerator::classifyProcessMetadata(
    int pid,
    int ppid,
    uint32_t uid,
    size_t vmRssKB,
    const std::string& cmdline,
    const std::string& comm,
    bool parentIsZygote
) {
    ProcessEntry entry;
    entry.pid         = pid;
    entry.ppid        = ppid;
    entry.uid         = uid;
    entry.userId      = uid / AID_USER_OFFSET;
    entry.appId       = uid % AID_USER_OFFSET;
    entry.vmRssKB     = vmRssKB;
    entry.zygoteChild = parentIsZygote;
    entry.processName = !cmdline.empty() ? cmdline : comm;

    size_t colonPos = entry.processName.find(':');
    if (colonPos != std::string::npos && entry.processName[0] != '/') {
        entry.isSubProcess = true;
        entry.basePackage  = entry.processName.substr(0, colonPos);
    } else {
        entry.isSubProcess = false;
        entry.basePackage  = entry.processName;
    }

    // 1. Kernel threads have empty cmdline and VmRSS == 0 (or ppid == 2 kthreadd)
    if (cmdline.empty() && vmRssKB == 0) {
        entry.category = ProcessCategory::KERNEL_DAEMON;
        return entry;
    }

    // 2. Isolated / WebView / Sandboxed Renderer Processes (appId in 90000..99999)
    if (entry.appId >= AID_ISOLATED_START && entry.appId <= AID_ISOLATED_END) {
        entry.category = ProcessCategory::ISOLATED_APP;
        return entry;
    }

    // 3. Standard User Apps across any Multi-User profile (appId in 10000..19999)
    if (entry.appId >= AID_APP_START && entry.appId <= AID_APP_END) {
        // Exclude pure shell/su helper invocations while keeping all Zygote/app processes (including sharedUserId)
        bool isShellHelper = (cmdline.find("/system/bin/") == 0 || cmdline.find("/vendor/bin/") == 0 ||
                              comm == "sh" || comm == "su" || comm == "daemonsu" || comm == "magisk");
        if (!isShellHelper && (parentIsZygote || looksLikeAndroidPackage(entry.processName) || !entry.processName.empty())) {
            entry.category = ProcessCategory::USER_APP;
            return entry;
        }
    }

    // 4. System Apps (e.g. system_server, com.android.systemui, com.android.phone, vendor apps)
    // Either spawned by Zygote/Zygote64 OR running under system/radio/bluetooth/nfc UIDs with Android package syntax
    if (parentIsZygote || looksLikeAndroidPackage(entry.processName)) {
        entry.category = ProcessCategory::SYSTEM_APP;
        return entry;
    }

    // 5. Pure Linux native daemons (/system/bin/*, /vendor/bin/*) vs QA / userland native processes
    if (cmdline.find("/system/bin/") == 0 || cmdline.find("/vendor/bin/") == 0 ||
        cmdline.find("/apex/") == 0 || cmdline.find("/sbin/") == 0 ||
        comm == "init" || comm == "zygote" || comm == "zygote64" || comm == "logd" ||
        comm == "servicemanager" || comm == "hwservicemanager" || comm == "vndservicemanager" ||
        comm == "surfaceflinger" || comm == "lmkd" || comm == "netd" || comm == "vold") {
        entry.category = ProcessCategory::KERNEL_DAEMON;
        return entry;
    }

    entry.category = ProcessCategory::NATIVE_PROCESS;
    return entry;
}

std::vector<ProcessEntry> ProcessEnumerator::enumerateProcesses(bool androidAppsOnly) {
    std::vector<ProcessEntry> results;

    struct RawProc {
        int pid;
        int ppid;
        uint32_t uid;
        size_t vmRssKB;
        std::string cmdline;
        std::string comm;
    };

    std::vector<RawProc> rawList;
    rawList.reserve(256);
    std::unordered_set<int> zygotePids;
    std::unordered_set<int> visitedPids;

    auto inspectPid = [&](int pid) {
        if (pid <= 0 || visitedPids.count(pid) > 0) return;
        int ppid = 0;
        uint32_t uid = 0;
        size_t vmRssKB = 0;
        std::string comm;
        if (!readStatusMetadata(pid, ppid, uid, vmRssKB, comm)) return;
        visitedPids.insert(pid);

        std::string cmdline = readFirstArgCmdline(pid);

        // Identify Zygote / Zygote64 / USAP pool parents
        if (comm == "zygote" || comm == "zygote64" || comm == "usap32" || comm == "usap64" ||
            cmdline.find("zygote") != std::string::npos ||
            cmdline.find("app_process") != std::string::npos) {
            zygotePids.insert(pid);
        }

        rawList.push_back(RawProc{pid, ppid, uid, vmRssKB, std::move(cmdline), std::move(comm)});
    };

    DIR* dir = ::opendir("/proc");
    if (dir) {
        struct dirent* ent = nullptr;
        while ((ent = ::readdir(dir)) != nullptr) {
            // Do not rely solely on d_type == DT_DIR because some procfs mounts return DT_UNKNOWN
            if (!isAllDigits(ent->d_name)) continue;
            int pid = std::atoi(ent->d_name);
            inspectPid(pid);
        }
        ::closedir(dir);
    }

    // Fallback / supplement: On Android 7.0+ without root, SELinux blocks opendir("/proc") (proc:dir read),
    // or hidepid hides entries, but allows direct path lookup (/proc/<pid>/status via proc:dir search)
    // for all processes sharing the same UID / android:sharedUserId & SELinux domain.
    if (rawList.size() <= 2) {
        int maxPid = 65536;
        if (FILE* fp = std::fopen("/proc/sys/kernel/pid_max", "r")) {
            int val = 0;
            if (std::fscanf(fp, "%d", &val) == 1 && val > 0) {
                maxPid = std::min(val, 131072);
            }
            std::fclose(fp);
        }
        for (int pid = 1; pid <= maxPid; ++pid) {
            inspectPid(pid);
        }
    }

    results.reserve(rawList.size());
    for (const auto& rp : rawList) {
        if (zygotePids.count(rp.pid) > 0) continue; // Exclude the Zygote master daemon itself
        bool parentIsZygote = (zygotePids.count(rp.ppid) > 0);
        ProcessEntry pe = classifyProcessMetadata(
            rp.pid, rp.ppid, rp.uid, rp.vmRssKB, rp.cmdline, rp.comm, parentIsZygote
        );

        if (androidAppsOnly) {
            if (pe.category == ProcessCategory::USER_APP ||
                pe.category == ProcessCategory::ISOLATED_APP ||
                pe.category == ProcessCategory::SYSTEM_APP) {
                results.push_back(std::move(pe));
            }
        } else {
            if (pe.category != ProcessCategory::KERNEL_DAEMON) {
                results.push_back(std::move(pe));
            }
        }
    }

    std::sort(results.begin(), results.end(), [](const ProcessEntry& a, const ProcessEntry& b) {
        return a.pid < b.pid;
    });
    return results;
}

} // namespace mem_engine
