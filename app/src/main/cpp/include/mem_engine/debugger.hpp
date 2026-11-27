#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <deque>
#include <vector>

namespace mem_engine {

enum class BpType : int32_t {
    READ  = 1,
    WRITE = 2,
    RW    = 3,
    EXEC  = 4
};

enum class BpMode : int32_t {
    PERF   = 0,
    PTRACE = 1
};

struct RegEntry {
    std::string name;
    uint64_t value = 0;
};

struct FpRegEntry {
    std::string name;
    std::string hexValue;
};

struct CallFrameEntry {
    uint64_t address = 0;
    std::string moduleName;
    uint64_t moduleOffset = 0;
};

struct BpHitGroup {
    uint64_t pc = 0;
    int32_t hitType = 3; // 1=R, 2=W, 3=RW, 4=X
    int32_t tid = 0;
    std::string arch;
    std::string moduleName;
    uint64_t moduleOffset = 0;
    std::vector<uint8_t> insnBytes;
    uint64_t hitCount = 0;
    uint64_t sp = 0;
    uint64_t lr = 0;
    std::vector<RegEntry> gprs;
    std::vector<FpRegEntry> fprs;
    std::vector<CallFrameEntry> callstack;
};

struct BpTraceEvent {
    uint64_t seq = 0;
    int64_t timestampMs = 0;
    int32_t bpId = 0;
    int32_t tid = 0;
    int32_t hitType = 3;
    uint64_t pc = 0;
    uint64_t sp = 0;
    uint64_t lr = 0;
    std::string arch;
    std::string moduleName;
    uint64_t moduleOffset = 0;
    std::vector<uint8_t> insnBytes;
    std::vector<RegEntry> gprs;
    std::vector<FpRegEntry> fprs;
};

struct BpSessionSnapshot {
    int32_t id = 0;
    int32_t pid = 0;
    uint64_t address = 0;
    int32_t type = 3;
    int32_t size = 4;
    int32_t mode = 0;
    bool captureCallstack = true;
    bool running = false;
    bool paused = false;
    int32_t attachedThreads = 0;
    int32_t totalThreads = 0;
    uint64_t totalHits = 0;
    int32_t tracerPid = 0;
    std::vector<int32_t> sampleTids;
    std::string lastError;
    std::vector<BpHitGroup> hits;
};

/**
 * Hardware Breakpoint & Watchpoint Debugger supporting dual backends:
 * 1) Linux perf_event_open (PERF_TYPE_BREAKPOINT) — non-intrusive, high speed, Root.
 * 2) Linux ptrace hardware debug registers (NT_ARM_HW_WATCH/BREAK, DR0..DR7) — full GPR/FPR + unwind, supports Root & No-Root same-UID.
 */
class BreakpointManager {
public:
    BreakpointManager() = default;
    ~BreakpointManager();

    int32_t addBreakpoint(
        int pid,
        uint64_t address,
        BpType type,
        int32_t sizeBytes,
        BpMode mode,
        bool captureCallstack,
        std::string* outError = nullptr
    );

    bool removeBreakpoint(int32_t id);
    bool setPaused(int32_t id, bool paused);
    bool setAllPaused(bool paused);
    bool clearHits(int32_t id);
    void clearAllHits();
    void stopAll();

    bool modifyRegister(
        int32_t bpId,
        int32_t tid,
        uint64_t hitPc,
        const std::string& regName,
        const std::string& hexValue,
        bool isFp,
        std::string* outError = nullptr
    );

    std::vector<BpSessionSnapshot> snapshots() const;
    std::string snapshotsToJson() const;
    std::string traceEventsToJson(int32_t bpId, uint64_t sinceSeq, size_t maxEvents = 120) const;
    void clearTrace(int32_t bpId);

private:
    struct Session;

    static void perfWorkerLoop(std::shared_ptr<Session> sess);
    static void ptraceWorkerLoop(std::shared_ptr<Session> sess);

    mutable std::mutex mtx_;
    int32_t nextId_ = 1;
    std::vector<std::shared_ptr<Session>> sessions_;
};

} // namespace mem_engine
