#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <cinttypes>
#include <cmath>
#include <csignal>
#include <csetjmp>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <utility>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#define AGENT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GGNextAgent", __VA_ARGS__)
#define AGENT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GGNextAgent", __VA_ARGS__)
#else
#define AGENT_LOGI(...) ((void)0)
#define AGENT_LOGW(...) ((void)0)
#endif

#include "xdl.h"

#if defined(__arm__) || defined(__aarch64__)
#include "shadowhook.h"
#define GGNEXT_HAS_SHADOWHOOK 1
#else
#define GGNEXT_HAS_SHADOWHOOK 0
#endif

namespace {

// ============================================================================
// 1. Speedhack State & Time Hooks (Preserved)
// ============================================================================
std::mutex g_clockMtx;
std::atomic<double> g_speedRate{1.0};
std::atomic<bool> g_hooksInstalled{false};
#if GGNEXT_HAS_SHADOWHOOK
std::atomic<bool> g_shadowhookInited{false};

void ensureShadowHookInit() {
    if (!g_shadowhookInited.exchange(true)) {
        int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        if (rc != 0 && rc != SHADOWHOOK_ERRNO_OK) {
            AGENT_LOGW("shadowhook_init returned %d (errno=%d)", rc, shadowhook_get_init_errno());
        }
    }
}
#endif

#if GGNEXT_HAS_SHADOWHOOK
constexpr int kMaxClocks = 16;

struct ClockAnchor {
    bool initialized = false;
    int64_t realBaseNs = 0;
    int64_t virtBaseNs = 0;
    double lastRate = 1.0;
};

ClockAnchor g_anchors[kMaxClocks];
ClockAnchor g_gtodAnchor;
ClockAnchor g_timeAnchor;
std::atomic<int64_t> g_lastConfigCheckNs{0};

using FnClockGettime = int (*)(clockid_t, struct timespec*);
using FnGettimeofday = int (*)(struct timeval*, struct timezone*);
using FnTime         = time_t (*)(time_t*);
using FnUsleep       = int (*)(useconds_t);
using FnNanosleep    = int (*)(const struct timespec*, struct timespec*);

FnClockGettime g_origClockGettime = nullptr;
FnGettimeofday g_origGettimeofday = nullptr;
FnTime         g_origTime         = nullptr;
FnUsleep       g_origUsleep       = nullptr;
FnNanosleep    g_origNanosleep    = nullptr;

void* g_stubClockGettime = nullptr;
void* g_stubGettimeofday = nullptr;
void* g_stubTime         = nullptr;
void* g_stubUsleep       = nullptr;
void* g_stubNanosleep    = nullptr;

inline int64_t tsToNs(const struct timespec& ts) {
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}

inline void nsToTs(int64_t ns, struct timespec& ts) {
    if (ns < 0) ns = 0;
    ts.tv_sec = static_cast<time_t>(ns / 1000000000LL);
    ts.tv_nsec = static_cast<long>(ns % 1000000000LL);
}

inline int64_t tvToUs(const struct timeval& tv) {
    return static_cast<int64_t>(tv.tv_sec) * 1000000LL + static_cast<int64_t>(tv.tv_usec);
}

inline void usToTv(int64_t us, struct timeval& tv) {
    if (us < 0) us = 0;
    tv.tv_sec = static_cast<time_t>(us / 1000000LL);
    tv.tv_usec = static_cast<suseconds_t>(us % 1000000LL);
}

void pollExternalSpeedConfig(int64_t nowRealNs) {
    int64_t prev = g_lastConfigCheckNs.load(std::memory_order_relaxed);
    if (nowRealNs - prev < 50000000LL) return;
    if (!g_lastConfigCheckNs.compare_exchange_strong(prev, nowRealNs, std::memory_order_relaxed)) {
        return;
    }

    char path[96];
    std::snprintf(path, sizeof(path), "/data/local/tmp/.ggnext_spd_%d", static_cast<int>(::getpid()));
    int fd = ::open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;

    char buf[64] = {0};
    ssize_t n = ::read(fd, buf, sizeof(buf) - 1);
    ::close(fd);
    if (n > 0) {
        double parsed = std::strtod(buf, nullptr);
        if (std::isfinite(parsed) && parsed >= 0.01 && parsed <= 100.0) {
            g_speedRate.store(parsed, std::memory_order_release);
        }
    }
}

int64_t scaleMonotonic(ClockAnchor& anchor, int64_t realVal, double rate) {
    if (!anchor.initialized) {
        anchor.initialized = true;
        anchor.realBaseNs = realVal;
        anchor.virtBaseNs = realVal;
        anchor.lastRate = rate;
        return realVal;
    }
    int64_t delta = realVal - anchor.realBaseNs;
    int64_t scaled = anchor.virtBaseNs + static_cast<int64_t>(static_cast<double>(delta) * anchor.lastRate);
    if (std::fabs(rate - anchor.lastRate) > 1e-6) {
        anchor.realBaseNs = realVal;
        anchor.virtBaseNs = scaled;
        anchor.lastRate = rate;
    }
    return scaled;
}

int hooked_clock_gettime(clockid_t clk_id, struct timespec* tp) {
    int rc = g_origClockGettime ? g_origClockGettime(clk_id, tp) : -1;
    if (rc != 0 || !tp) return rc;

    int64_t realNs = tsToNs(*tp);
    pollExternalSpeedConfig(realNs);
    double rate = g_speedRate.load(std::memory_order_acquire);
    if (clk_id >= 0 && clk_id < kMaxClocks) {
        std::lock_guard<std::mutex> lk(g_clockMtx);
        int64_t virtNs = scaleMonotonic(g_anchors[clk_id], realNs, rate);
        nsToTs(virtNs, *tp);
    }
    return rc;
}

int hooked_gettimeofday(struct timeval* tv, struct timezone* tz) {
    int rc = g_origGettimeofday ? g_origGettimeofday(tv, tz) : -1;
    if (rc != 0 || !tv) return rc;

    int64_t realUs = tvToUs(*tv);
    pollExternalSpeedConfig(realUs * 1000LL);
    double rate = g_speedRate.load(std::memory_order_acquire);
    {
        std::lock_guard<std::mutex> lk(g_clockMtx);
        int64_t virtUs = scaleMonotonic(g_gtodAnchor, realUs, rate);
        usToTv(virtUs, *tv);
    }
    return rc;
}

time_t hooked_time(time_t* tloc) {
    time_t realSec = g_origTime ? g_origTime(nullptr) : static_cast<time_t>(-1);
    if (realSec == static_cast<time_t>(-1)) return realSec;

    double rate = g_speedRate.load(std::memory_order_acquire);
    int64_t virtSec = realSec;
    {
        std::lock_guard<std::mutex> lk(g_clockMtx);
        virtSec = scaleMonotonic(g_timeAnchor, static_cast<int64_t>(realSec), rate);
    }
    if (tloc) *tloc = static_cast<time_t>(virtSec);
    return static_cast<time_t>(virtSec);
}

int hooked_usleep(useconds_t usec) {
    double rate = g_speedRate.load(std::memory_order_acquire);
    if (rate > 0.01 && std::fabs(rate - 1.0) > 1e-4) {
        double adj = static_cast<double>(usec) / rate;
        if (adj < 1.0) adj = 1.0;
        if (adj > 60000000.0) adj = 60000000.0;
        usec = static_cast<useconds_t>(adj);
    }
    return g_origUsleep ? g_origUsleep(usec) : 0;
}

int hooked_nanosleep(const struct timespec* req, struct timespec* rem) {
    if (!req || !g_origNanosleep) {
        return g_origNanosleep ? g_origNanosleep(req, rem) : 0;
    }
    double rate = g_speedRate.load(std::memory_order_acquire);
    if (rate > 0.01 && std::fabs(rate - 1.0) > 1e-4) {
        int64_t ns = tsToNs(*req);
        int64_t adjNs = static_cast<int64_t>(static_cast<double>(ns) / rate);
        if (adjNs < 1000LL) adjNs = 1000LL;
        struct timespec adjReq{};
        nsToTs(adjNs, adjReq);
        return g_origNanosleep(&adjReq, rem);
    }
    return g_origNanosleep(req, rem);
}
#endif

// ============================================================================
// 2. Minimal Zero-Dependency JSON Parser & Writer for Agent IPC
// ============================================================================
struct JsonVal {
    enum class Type { Null, Bool, Int, Double, String, Array, Object };
    Type type = Type::Null;
    bool b = false;
    int64_t i = 0;
    double d = 0.0;
    std::string s;
    std::vector<JsonVal> arr;
    std::vector<std::pair<std::string, JsonVal>> obj;

    const JsonVal* get(const char* key) const {
        if (type != Type::Object) return nullptr;
        for (const auto& kv : obj) {
            if (kv.first == key) return &kv.second;
        }
        return nullptr;
    }

    std::string getStr(const char* key, const std::string& def = "") const {
        const auto* v = get(key);
        if (!v) return def;
        if (v->type == Type::String) return v->s;
        return def;
    }

    bool getBool(const char* key, bool def = false) const {
        const auto* v = get(key);
        if (!v) return def;
        if (v->type == Type::Bool) return v->b;
        if (v->type == Type::Int) return v->i != 0;
        return def;
    }

    int64_t getInt(const char* key, int64_t def = 0) const {
        const auto* v = get(key);
        if (!v) return def;
        if (v->type == Type::Int) return v->i;
        if (v->type == Type::Double) return static_cast<int64_t>(v->d);
        if (v->type == Type::String && !v->s.empty()) {
            const char* p = v->s.c_str();
            int base = (v->s.size() > 2 && p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) ? 16 : 10;
            if (base == 16) {
                return static_cast<int64_t>(std::strtoull(p + 2, nullptr, 16));
            }
            return std::strtoll(p, nullptr, 10);
        }
        return def;
    }

    uint64_t getU64(const char* key, uint64_t def = 0) const {
        const auto* v = get(key);
        if (!v) return def;
        if (v->type == Type::Int) return static_cast<uint64_t>(v->i);
        if (v->type == Type::Double) return static_cast<uint64_t>(v->d);
        if (v->type == Type::String && !v->s.empty()) {
            const char* p = v->s.c_str();
            if (v->s.size() > 2 && p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
                return std::strtoull(p + 2, nullptr, 16);
            }
            return std::strtoull(p, nullptr, 10);
        }
        return def;
    }

    double getDouble(const char* key, double def = 0.0) const {
        const auto* v = get(key);
        if (!v) return def;
        if (v->type == Type::Double) return v->d;
        if (v->type == Type::Int) return static_cast<double>(v->i);
        if (v->type == Type::String && !v->s.empty()) {
            return std::strtod(v->s.c_str(), nullptr);
        }
        return def;
    }
};

class MiniJsonParser {
public:
    MiniJsonParser(const char* data, size_t len) : cur_(data), end_(data + len) {}

    bool parse(JsonVal& out, int depth = 0) {
        if (depth > 24) return false;
        skipWs();
        if (cur_ >= end_) return false;
        char c = *cur_;
        if (c == 'n' && end_ - cur_ >= 4 && std::memcmp(cur_, "null", 4) == 0) {
            cur_ += 4;
            out.type = JsonVal::Type::Null;
            return true;
        }
        if (c == 't' && end_ - cur_ >= 4 && std::memcmp(cur_, "true", 4) == 0) {
            cur_ += 4;
            out.type = JsonVal::Type::Bool;
            out.b = true;
            return true;
        }
        if (c == 'f' && end_ - cur_ >= 5 && std::memcmp(cur_, "false", 5) == 0) {
            cur_ += 5;
            out.type = JsonVal::Type::Bool;
            out.b = false;
            return true;
        }
        if (c == '"') {
            out.type = JsonVal::Type::String;
            return parseStr(out.s);
        }
        if (c == '[') {
            ++cur_;
            out.type = JsonVal::Type::Array;
            skipWs();
            if (cur_ < end_ && *cur_ == ']') { ++cur_; return true; }
            while (cur_ < end_) {
                JsonVal elem;
                if (!parse(elem, depth + 1)) return false;
                out.arr.push_back(std::move(elem));
                skipWs();
                if (cur_ < end_ && *cur_ == ',') { ++cur_; }
                else if (cur_ < end_ && *cur_ == ']') { ++cur_; return true; }
                else return false;
            }
            return false;
        }
        if (c == '{') {
            ++cur_;
            out.type = JsonVal::Type::Object;
            skipWs();
            if (cur_ < end_ && *cur_ == '}') { ++cur_; return true; }
            while (cur_ < end_) {
                skipWs();
                std::string key;
                if (!parseStr(key)) return false;
                skipWs();
                if (cur_ >= end_ || *cur_ != ':') return false;
                ++cur_;
                JsonVal val;
                if (!parse(val, depth + 1)) return false;
                out.obj.emplace_back(std::move(key), std::move(val));
                skipWs();
                if (cur_ < end_ && *cur_ == ',') { ++cur_; }
                else if (cur_ < end_ && *cur_ == '}') { ++cur_; return true; }
                else return false;
            }
            return false;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            const char* start = cur_;
            bool isFlt = false;
            if (*cur_ == '-') ++cur_;
            while (cur_ < end_ && *cur_ >= '0' && *cur_ <= '9') ++cur_;
            if (cur_ < end_ && *cur_ == '.') {
                isFlt = true;
                ++cur_;
                while (cur_ < end_ && *cur_ >= '0' && *cur_ <= '9') ++cur_;
            }
            if (cur_ < end_ && (*cur_ == 'e' || *cur_ == 'E')) {
                isFlt = true;
                ++cur_;
                if (cur_ < end_ && (*cur_ == '+' || *cur_ == '-')) ++cur_;
                while (cur_ < end_ && *cur_ >= '0' && *cur_ <= '9') ++cur_;
            }
            std::string tok(start, cur_);
            if (!isFlt) {
                errno = 0;
                char* ep = nullptr;
                long long iv = std::strtoll(tok.c_str(), &ep, 10);
                if (errno == 0 && ep && *ep == '\0') {
                    out.type = JsonVal::Type::Int;
                    out.i = static_cast<int64_t>(iv);
                    return true;
                }
            }
            char* ep = nullptr;
            double dv = std::strtod(tok.c_str(), &ep);
            if (ep && *ep == '\0') {
                out.type = JsonVal::Type::Double;
                out.d = dv;
                return true;
            }
            return false;
        }
        return false;
    }

private:
    void skipWs() {
        while (cur_ < end_ && (*cur_ == ' ' || *cur_ == '\t' || *cur_ == '\r' || *cur_ == '\n')) ++cur_;
    }

    bool parseStr(std::string& out) {
        if (cur_ >= end_ || *cur_ != '"') return false;
        ++cur_;
        out.clear();
        while (cur_ < end_) {
            char c = *cur_++;
            if (c == '"') return true;
            if (c == '\\') {
                if (cur_ >= end_) return false;
                char esc = *cur_++;
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    default: out.push_back(esc); break;
                }
            } else {
                out.push_back(c);
            }
        }
        return false;
    }

    const char* cur_;
    const char* end_;
};

std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    return out;
}

std::string hexAddr(uint64_t addr) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "0x%" PRIx64, addr);
    return buf;
}

int64_t nowWallMs() {
    struct timeval tv{};
    ::gettimeofday(&tv, nullptr);
    return static_cast<int64_t>(tv.tv_sec) * 1000LL + static_cast<int64_t>(tv.tv_usec) / 1000LL;
}

int currentTid() {
#if defined(__linux__)
    return static_cast<int>(::syscall(SYS_gettid));
#else
    return static_cast<int>(::getpid());
#endif
}

// ============================================================================
// 3. Multi-Slot ShadowHook Manager + Hit Ring Buffer + Trampoline Active Call
// ============================================================================
constexpr int kMaxHookSlots = 64;
constexpr size_t kMaxHitLogs = 512;

struct ArgOverride {
    bool enabled = false;
    uint64_t value = 0;
};

struct RegOverride {
    std::string name;
    uint64_t intVal = 0;
    double fpVal = 0.0;
    bool isFp = false;
};

struct HookSlot {
    std::mutex ruleMtx;
    std::atomic<bool> active{false};
    int32_t id = 0;
    bool isIntercept = false;
    std::string libName;
    std::string symbolName;
    uint64_t targetAddress = 0;
    void* stub = nullptr;
    void* origAddr = nullptr;

    // Mutable interception rules
    std::array<ArgOverride, 8> argOverrides{};
    std::vector<RegOverride> regOverrides;
    bool bypassOriginal = false;
    bool hasReturnOverride = false;
    uint64_t returnOverride = 0;
    bool logEnabled = true;

    std::atomic<uint64_t> hitCount{0};
};

struct HookHitRecord {
    uint64_t seq = 0;
    int32_t hookId = 0;
    int32_t tid = 0;
    int64_t timestampMs = 0;
    bool isIntercept = false;
    uint64_t pc = 0;
    uint64_t lr = 0;
    std::array<uint64_t, 8> args{};
    uint64_t origRet = 0;
    uint64_t ret = 0;
    bool bypassed = false;
    bool modified = false;
};

std::mutex g_hookTableMtx;
std::array<HookSlot, kMaxHookSlots> g_slots;
#if GGNEXT_HAS_SHADOWHOOK
std::atomic<int32_t> g_nextHookId{1};
#endif

std::mutex g_hitLogMtx;
std::vector<HookHitRecord> g_hitRing;
uint64_t g_nextHitSeq = 1;

thread_local int g_hookRecursionDepth = 0;

void appendHitRecord(const HookHitRecord& recIn) {
    std::lock_guard<std::mutex> lk(g_hitLogMtx);
    HookHitRecord rec = recIn;
    rec.seq = g_nextHitSeq++;
    if (g_hitRing.size() >= kMaxHitLogs) {
        g_hitRing.erase(g_hitRing.begin());
    }
    g_hitRing.push_back(rec);
}

using GenericFn8 = uint64_t (*)(
    uint64_t, uint64_t, uint64_t, uint64_t,
    uint64_t, uint64_t, uint64_t, uint64_t
);

uint64_t dispatchHookSlot(
    int slotIdx,
    uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3,
    uint64_t a4, uint64_t a5, uint64_t a6, uint64_t a7
) {
    auto& slot = g_slots[static_cast<size_t>(slotIdx)];
    GenericFn8 origFn = reinterpret_cast<GenericFn8>(slot.origAddr);

    // Prevent infinite recursion if hooked function is invoked inside logging/mutex
    if (g_hookRecursionDepth > 0 || !slot.active.load(std::memory_order_acquire)) {
        return origFn ? origFn(a0, a1, a2, a3, a4, a5, a6, a7) : 0;
    }
    ++g_hookRecursionDepth;

    std::array<uint64_t, 8> callArgs = {a0, a1, a2, a3, a4, a5, a6, a7};
    bool bypass = false;
    bool hasRetOv = false;
    uint64_t retOv = 0;
    bool logEnabled = true;
    bool modified = false;
    int32_t hookId = 0;
    uint64_t targetAddr = 0;

    {
        std::lock_guard<std::mutex> lk(slot.ruleMtx);
        hookId = slot.id;
        targetAddr = slot.targetAddress;
        bypass = slot.bypassOriginal;
        hasRetOv = slot.hasReturnOverride;
        retOv = slot.returnOverride;
        logEnabled = slot.logEnabled;
        for (size_t i = 0; i < 8; ++i) {
            if (slot.argOverrides[i].enabled) {
                callArgs[i] = slot.argOverrides[i].value;
                modified = true;
            }
        }
    }

    uint64_t origRet = 0;
    uint64_t finalRet = 0;
    if (bypass) {
        finalRet = hasRetOv ? retOv : 0;
        modified = true;
    } else if (origFn) {
        origRet = origFn(
            callArgs[0], callArgs[1], callArgs[2], callArgs[3],
            callArgs[4], callArgs[5], callArgs[6], callArgs[7]
        );
        finalRet = hasRetOv ? retOv : origRet;
        if (hasRetOv) modified = true;
    }

    slot.hitCount.fetch_add(1, std::memory_order_relaxed);
    if (logEnabled) {
        HookHitRecord rec;
        rec.hookId = hookId;
        rec.tid = currentTid();
        rec.timestampMs = nowWallMs();
        rec.isIntercept = false;
        rec.pc = targetAddr;
        rec.lr = reinterpret_cast<uint64_t>(__builtin_return_address(0));
        rec.args = callArgs;
        rec.origRet = origRet;
        rec.ret = finalRet;
        rec.bypassed = bypass;
        rec.modified = modified;
        appendHitRecord(rec);
    }

    --g_hookRecursionDepth;
    return finalRet;
}

template <int N>
uint64_t hookProxyFn(
    uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3,
    uint64_t a4, uint64_t a5, uint64_t a6, uint64_t a7
) {
    return dispatchHookSlot(N, a0, a1, a2, a3, a4, a5, a6, a7);
}

template <size_t... Is>
std::array<void*, sizeof...(Is)> makeProxyTable(std::index_sequence<Is...>) {
    return { reinterpret_cast<void*>(&hookProxyFn<static_cast<int>(Is)>)... };
}

const std::array<void*, kMaxHookSlots> kHookProxies = makeProxyTable(std::make_index_sequence<kMaxHookSlots>{});

#if GGNEXT_HAS_SHADOWHOOK
void shadowInterceptCallback(shadowhook_cpu_context_t* ctx, void* data) {
    if (!ctx || !data) return;
    int slotIdx = static_cast<int>(reinterpret_cast<intptr_t>(data));
    if (slotIdx < 0 || slotIdx >= kMaxHookSlots) return;
    auto& slot = g_slots[static_cast<size_t>(slotIdx)];
    if (!slot.active.load(std::memory_order_acquire) || g_hookRecursionDepth > 0) return;

    ++g_hookRecursionDepth;
    bool logEnabled = true;
    bool modified = false;
    int32_t hookId = 0;

    {
        std::lock_guard<std::mutex> lk(slot.ruleMtx);
        hookId = slot.id;
        logEnabled = slot.logEnabled;

        for (size_t i = 0; i < 8; ++i) {
            if (slot.argOverrides[i].enabled) {
#if defined(__aarch64__)
                ctx->regs[i] = slot.argOverrides[i].value;
                modified = true;
#elif defined(__arm__)
                if (i < 4) {
                    ctx->regs[i] = static_cast<uint32_t>(slot.argOverrides[i].value);
                    modified = true;
                }
#endif
            }
        }

        for (const auto& ro : slot.regOverrides) {
            std::string rn = ro.name;
            std::transform(rn.begin(), rn.end(), rn.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
#if defined(__aarch64__)
            if (rn.size() >= 2 && rn[0] == 'x') {
                int idx = std::atoi(rn.c_str() + 1);
                if (idx >= 0 && idx <= 30) { ctx->regs[idx] = ro.intVal; modified = true; }
            } else if (rn == "sp") {
                ctx->sp = ro.intVal; modified = true;
            } else if (rn == "lr") {
                ctx->regs[30] = ro.intVal; modified = true;
            } else if (rn.size() >= 2 && rn[0] == 'd') {
                int idx = std::atoi(rn.c_str() + 1);
                if (idx >= 0 && idx < 32) {
                    uint64_t bits = 0;
                    double dv = ro.isFp ? ro.fpVal : static_cast<double>(ro.intVal);
                    std::memcpy(&bits, &dv, sizeof(bits));
                    ctx->vregs[idx].d[0] = bits;
                    modified = true;
                }
            }
#elif defined(__arm__)
            if (rn.size() >= 2 && rn[0] == 'r') {
                int idx = std::atoi(rn.c_str() + 1);
                if (idx >= 0 && idx <= 14) { ctx->regs[idx] = static_cast<uint32_t>(ro.intVal); modified = true; }
            } else if (rn == "sp") {
                ctx->regs[13] = static_cast<uint32_t>(ro.intVal); modified = true;
            } else if (rn == "lr") {
                ctx->regs[14] = static_cast<uint32_t>(ro.intVal); modified = true;
            }
#endif
        }
    }

    slot.hitCount.fetch_add(1, std::memory_order_relaxed);
    if (logEnabled) {
        HookHitRecord rec;
        rec.hookId = hookId;
        rec.tid = currentTid();
        rec.timestampMs = nowWallMs();
        rec.isIntercept = true;
        rec.modified = modified;
#if defined(__aarch64__)
        rec.pc = ctx->pc;
        rec.lr = ctx->regs[30];
        for (size_t i = 0; i < 8; ++i) rec.args[i] = ctx->regs[i];
#elif defined(__arm__)
        rec.pc = ctx->regs[15];
        rec.lr = ctx->regs[14];
        for (size_t i = 0; i < 4; ++i) rec.args[i] = ctx->regs[i];
#endif
        appendHitRecord(rec);
    }
    --g_hookRecursionDepth;
}
#endif

// ============================================================================
// 4. Signal-Guarded Trampoline Active Call Engine
// ============================================================================
std::mutex g_activeCallMtx;
thread_local sigjmp_buf g_callJmpBuf;
thread_local volatile sig_atomic_t g_inSafeCall = 0;
thread_local volatile sig_atomic_t g_caughtSig = 0;

void activeCallSigHandler(int sig) {
    if (g_inSafeCall) {
        g_caughtSig = sig;
        g_inSafeCall = 0;
        siglongjmp(g_callJmpBuf, sig);
    }
    std::signal(sig, SIG_DFL);
    std::raise(sig);
}

struct ScopedSignalGuard {
    struct sigaction oldSegv{}, oldBus{}, oldIll{}, oldFpe{};
    bool active = false;

    ScopedSignalGuard() {
        struct sigaction sa{};
        sa.sa_handler = activeCallSigHandler;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = SA_NODEFER;
        ::sigaction(SIGSEGV, &sa, &oldSegv);
        ::sigaction(SIGBUS,  &sa, &oldBus);
        ::sigaction(SIGILL,  &sa, &oldIll);
        ::sigaction(SIGFPE,  &sa, &oldFpe);
        active = true;
    }

    ~ScopedSignalGuard() {
        if (active) {
            ::sigaction(SIGSEGV, &oldSegv, nullptr);
            ::sigaction(SIGBUS,  &oldBus,  nullptr);
            ::sigaction(SIGILL,  &oldIll,  nullptr);
            ::sigaction(SIGFPE,  &oldFpe,  nullptr);
        }
    }
};

struct ActiveCallResult {
    bool ok = false;
    uint64_t rawInt = 0;
    double fpVal = 0.0;
    std::string strVal;
    std::string retType = "int64";
    uint64_t calledAddress = 0;
    std::string error;
};

ActiveCallResult invokeTargetFunctionSafe(
    void* fnPtr,
    const std::array<uint64_t, 8>& gpr,
    const std::array<double, 8>& fpr,
    int fpCount,
    const std::string& retType
) {
    ActiveCallResult res;
    res.retType = retType;
    res.calledAddress = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(fnPtr));
    if (!fnPtr) {
        res.error = "Target function address is null (0x0)";
        return res;
    }

    std::lock_guard<std::mutex> lk(g_activeCallMtx);
    ScopedSignalGuard guard;
    g_caughtSig = 0;

    int jumpedSig = sigsetjmp(g_callJmpBuf, 1);
    if (jumpedSig != 0) {
        g_inSafeCall = 0;
        const char* sigName = "SIGNAL";
        if (jumpedSig == SIGSEGV) sigName = "SIGSEGV";
        else if (jumpedSig == SIGBUS) sigName = "SIGBUS";
        else if (jumpedSig == SIGILL) sigName = "SIGILL";
        else if (jumpedSig == SIGFPE) sigName = "SIGFPE";
        res.ok = false;
        res.error = std::string("Protected call trapped ") + sigName + " (" + std::to_string(jumpedSig) + ")";
        return res;
    }

    g_inSafeCall = 1;

    if (retType == "double") {
        if (fpCount > 0) {
            using Fn = double (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
                                  double, double, double, double, double, double, double, double);
            auto fn = reinterpret_cast<Fn>(fnPtr);
            res.fpVal = fn(gpr[0], gpr[1], gpr[2], gpr[3], gpr[4], gpr[5], gpr[6], gpr[7],
                           fpr[0], fpr[1], fpr[2], fpr[3], fpr[4], fpr[5], fpr[6], fpr[7]);
        } else {
            using Fn = double (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);
            auto fn = reinterpret_cast<Fn>(fnPtr);
            res.fpVal = fn(gpr[0], gpr[1], gpr[2], gpr[3], gpr[4], gpr[5], gpr[6], gpr[7]);
        }
        std::memcpy(&res.rawInt, &res.fpVal, sizeof(res.rawInt));
    } else if (retType == "float") {
        float fv = 0.0f;
        if (fpCount > 0) {
            using Fn = float (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
                                 double, double, double, double, double, double, double, double);
            auto fn = reinterpret_cast<Fn>(fnPtr);
            fv = fn(gpr[0], gpr[1], gpr[2], gpr[3], gpr[4], gpr[5], gpr[6], gpr[7],
                    fpr[0], fpr[1], fpr[2], fpr[3], fpr[4], fpr[5], fpr[6], fpr[7]);
        } else {
            using Fn = float (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);
            auto fn = reinterpret_cast<Fn>(fnPtr);
            fv = fn(gpr[0], gpr[1], gpr[2], gpr[3], gpr[4], gpr[5], gpr[6], gpr[7]);
        }
        res.fpVal = static_cast<double>(fv);
        uint32_t fbits = 0;
        std::memcpy(&fbits, &fv, sizeof(fbits));
        res.rawInt = static_cast<uint64_t>(fbits);
    } else {
        uint64_t rv = 0;
        if (fpCount > 0) {
            using Fn = uint64_t (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
                                    double, double, double, double, double, double, double, double);
            auto fn = reinterpret_cast<Fn>(fnPtr);
            rv = fn(gpr[0], gpr[1], gpr[2], gpr[3], gpr[4], gpr[5], gpr[6], gpr[7],
                    fpr[0], fpr[1], fpr[2], fpr[3], fpr[4], fpr[5], fpr[6], fpr[7]);
        } else {
            auto fn = reinterpret_cast<GenericFn8>(fnPtr);
            rv = fn(gpr[0], gpr[1], gpr[2], gpr[3], gpr[4], gpr[5], gpr[6], gpr[7]);
        }
        res.rawInt = rv;
        if (retType == "string" && rv != 0) {
            const char* cstr = reinterpret_cast<const char*>(static_cast<uintptr_t>(rv));
            size_t len = 0;
            while (len < 4096 && cstr[len] != '\0') ++len;
            res.strVal.assign(cstr, len);
        }
    }

    g_inSafeCall = 0;
    res.ok = true;
    return res;
}

// ============================================================================
// 5. Command Dispatcher (install, update, uninstall, uninstall_all, list, logs, clear_logs, call, ping)
// ============================================================================
void applyRulesFromJson(HookSlot& slot, const JsonVal& req) {
    if (const auto* argOv = req.get("argOverrides")) {
        if (argOv->type == JsonVal::Type::Object) {
            for (const auto& kv : argOv->obj) {
                int idx = std::atoi(kv.first.c_str());
                if (idx >= 0 && idx < 8) {
                    if (kv.second.type == JsonVal::Type::Null) {
                        slot.argOverrides[static_cast<size_t>(idx)].enabled = false;
                    } else {
                        slot.argOverrides[static_cast<size_t>(idx)].enabled = true;
                        if (kv.second.type == JsonVal::Type::Int) {
                            slot.argOverrides[static_cast<size_t>(idx)].value = static_cast<uint64_t>(kv.second.i);
                        } else if (kv.second.type == JsonVal::Type::Double) {
                            slot.argOverrides[static_cast<size_t>(idx)].value = static_cast<uint64_t>(static_cast<int64_t>(kv.second.d));
                        } else if (kv.second.type == JsonVal::Type::String) {
                            const char* p = kv.second.s.c_str();
                            if (kv.second.s.size() > 2 && p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
                                slot.argOverrides[static_cast<size_t>(idx)].value = std::strtoull(p + 2, nullptr, 16);
                            } else {
                                slot.argOverrides[static_cast<size_t>(idx)].value = static_cast<uint64_t>(std::strtoll(p, nullptr, 10));
                            }
                        }
                    }
                }
            }
        }
    }
    if (const auto* regOv = req.get("regOverrides")) {
        if (regOv->type == JsonVal::Type::Object) {
            slot.regOverrides.clear();
            for (const auto& kv : regOv->obj) {
                RegOverride ro;
                ro.name = kv.first;
                if (kv.second.type == JsonVal::Type::Double) {
                    ro.isFp = true;
                    ro.fpVal = kv.second.d;
                    ro.intVal = static_cast<uint64_t>(static_cast<int64_t>(kv.second.d));
                } else if (kv.second.type == JsonVal::Type::Int) {
                    ro.intVal = static_cast<uint64_t>(kv.second.i);
                } else if (kv.second.type == JsonVal::Type::String) {
                    const char* p = kv.second.s.c_str();
                    if (kv.second.s.size() > 2 && p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
                        ro.intVal = std::strtoull(p + 2, nullptr, 16);
                    } else {
                        ro.intVal = static_cast<uint64_t>(std::strtoll(p, nullptr, 10));
                    }
                }
                slot.regOverrides.push_back(std::move(ro));
            }
        }
    }
    if (req.get("bypassOriginal")) {
        slot.bypassOriginal = req.getBool("bypassOriginal", slot.bypassOriginal);
    }
    if (const auto* rv = req.get("returnOverride")) {
        if (rv->type == JsonVal::Type::Null) {
            slot.hasReturnOverride = false;
        } else {
            slot.hasReturnOverride = true;
            slot.returnOverride = req.getU64("returnOverride", 0);
        }
    }
    if (req.get("logEnabled")) {
        slot.logEnabled = req.getBool("logEnabled", true);
    }
}

std::string handleHookCommandJson(const std::string& reqStr) {
    JsonVal req;
    MiniJsonParser parser(reqStr.data(), reqStr.size());
    if (!parser.parse(req) || req.type != JsonVal::Type::Object) {
        return "{\"ok\":false,\"error\":\"Invalid JSON request\"}";
    }

    std::string action = req.getStr("action");
    if (action == "ping") {
        std::ostringstream oss;
        oss << "{\"ok\":true,\"pid\":" << ::getpid()
            << ",\"shadowhook\":" << (GGNEXT_HAS_SHADOWHOOK ? "true" : "false")
#if GGNEXT_HAS_SHADOWHOOK
            << ",\"version\":\"" << shadowhook_get_version() << "\""
#endif
            << "}";
        return oss.str();
    }

    if (action == "install") {
#if !GGNEXT_HAS_SHADOWHOOK
        return "{\"ok\":false,\"error\":\"ShadowHook inline hook is supported on ARM64 and ARM32 targets\"}";
#else
        ensureShadowHookInit();
        std::string libName = req.getStr("libName");
        std::string symName = req.getStr("symbolName");
        uint64_t targetAddr = req.getU64("address", 0);
        std::string mode = req.getStr("mode", "hook");
        bool isIntercept = (mode == "intercept");

        if (targetAddr == 0 && (libName.empty() || symName.empty())) {
            return "{\"ok\":false,\"error\":\"Provide either address or (libName + symbolName)\"}";
        }

        std::lock_guard<std::mutex> tblLk(g_hookTableMtx);
        int freeIdx = -1;
        for (int i = 0; i < kMaxHookSlots; ++i) {
            if (!g_slots[static_cast<size_t>(i)].active.load(std::memory_order_acquire)) {
                freeIdx = i;
                break;
            }
        }
        if (freeIdx < 0) {
            return "{\"ok\":false,\"error\":\"Maximum concurrent hooks reached (64)\"}";
        }

        auto& slot = g_slots[static_cast<size_t>(freeIdx)];
        {
            std::lock_guard<std::mutex> lk(slot.ruleMtx);
            slot.argOverrides = {};
            slot.regOverrides.clear();
            slot.bypassOriginal = false;
            slot.hasReturnOverride = false;
            slot.returnOverride = 0;
            slot.logEnabled = true;
            applyRulesFromJson(slot, req);
            slot.id = g_nextHookId.fetch_add(1, std::memory_order_relaxed);
            slot.isIntercept = isIntercept;
            slot.libName = libName;
            slot.symbolName = symName;
            slot.targetAddress = targetAddr;
            slot.stub = nullptr;
            slot.origAddr = nullptr;
            slot.hitCount.store(0, std::memory_order_relaxed);
        }

        // Resolve symbol address via xDL if libName + symName given
        void* resolvedSym = reinterpret_cast<void*>(static_cast<uintptr_t>(targetAddr));
        if (!resolvedSym && !libName.empty() && !symName.empty()) {
            void* handle = xdl_open(libName.c_str(), XDL_DEFAULT);
            if (handle) {
                resolvedSym = xdl_sym(handle, symName.c_str(), nullptr);
                if (!resolvedSym) {
                    resolvedSym = xdl_dsym(handle, symName.c_str(), nullptr);
                }
                xdl_close(handle);
            }
        }
        if (resolvedSym) {
            slot.targetAddress = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(resolvedSym));
        }

        // Mark active before installing so first hit immediately sees valid slot
        slot.active.store(true, std::memory_order_release);
        void* stub = nullptr;
        void* orig = nullptr;

        if (isIntercept) {
            uint32_t flags = SHADOWHOOK_INTERCEPT_WITH_FPSIMD_READ_WRITE;
            if (!libName.empty() && !symName.empty() && targetAddr == 0) {
                stub = shadowhook_intercept_sym_name(
                    libName.c_str(),
                    symName.c_str(),
                    shadowInterceptCallback,
                    reinterpret_cast<void*>(static_cast<intptr_t>(freeIdx)),
                    flags
                );
            } else {
                stub = shadowhook_intercept_instr_addr(
                    reinterpret_cast<void*>(static_cast<uintptr_t>(slot.targetAddress)),
                    shadowInterceptCallback,
                    reinterpret_cast<void*>(static_cast<intptr_t>(freeIdx)),
                    flags
                );
            }
        } else {
            if (!libName.empty() && !symName.empty() && targetAddr == 0) {
                stub = shadowhook_hook_sym_name(
                    libName.c_str(),
                    symName.c_str(),
                    kHookProxies[static_cast<size_t>(freeIdx)],
                    &orig
                );
            } else {
                stub = shadowhook_hook_func_addr(
                    reinterpret_cast<void*>(static_cast<uintptr_t>(slot.targetAddress)),
                    kHookProxies[static_cast<size_t>(freeIdx)],
                    &orig
                );
            }
        }

        if (!stub) {
            slot.active.store(false, std::memory_order_release);
            int errNo = shadowhook_get_errno();
            const char* errMsg = shadowhook_to_errmsg(errNo);
            std::ostringstream oss;
            oss << "{\"ok\":false,\"errno\":" << errNo
                << ",\"error\":\"ShadowHook failed (" << errNo << "): "
                << jsonEscape(errMsg ? errMsg : "unknown") << "\"}";
            return oss.str();
        }

        slot.stub = stub;
        slot.origAddr = orig;

        // Backfill module/symbol metadata via xdl_addr when hooked by raw address
        if ((slot.libName.empty() || slot.symbolName.empty()) && slot.targetAddress != 0) {
            xdl_info_t info{};
            void* cache = nullptr;
            if (xdl_addr(reinterpret_cast<void*>(static_cast<uintptr_t>(slot.targetAddress)), &info, &cache) != 0) {
                if (slot.libName.empty() && info.dli_fname) slot.libName = info.dli_fname;
                if (slot.symbolName.empty() && info.dli_sname) slot.symbolName = info.dli_sname;
            }
            if (cache) xdl_addr_clean(&cache);
        }

        std::ostringstream oss;
        oss << "{\"ok\":true"
            << ",\"id\":" << slot.id
            << ",\"mode\":\"" << (slot.isIntercept ? "intercept" : "hook") << "\""
            << ",\"libName\":\"" << jsonEscape(slot.libName) << "\""
            << ",\"symbolName\":\"" << jsonEscape(slot.symbolName) << "\""
            << ",\"targetAddress\":\"" << hexAddr(slot.targetAddress) << "\""
            << ",\"targetAddressInt\":" << static_cast<int64_t>(slot.targetAddress)
            << ",\"origAddress\":\"" << hexAddr(static_cast<uint64_t>(reinterpret_cast<uintptr_t>(slot.origAddr))) << "\""
            << ",\"origAddressInt\":" << static_cast<int64_t>(reinterpret_cast<uintptr_t>(slot.origAddr))
            << "}";
        return oss.str();
#endif
    }

    if (action == "update") {
        int32_t hookId = static_cast<int32_t>(req.getInt("hookId", 0));
        std::lock_guard<std::mutex> tblLk(g_hookTableMtx);
        for (auto& slot : g_slots) {
            if (slot.active.load(std::memory_order_acquire) && slot.id == hookId) {
                std::lock_guard<std::mutex> lk(slot.ruleMtx);
                applyRulesFromJson(slot, req);
                return "{\"ok\":true}";
            }
        }
        return "{\"ok\":false,\"error\":\"Hook ID not found\"}";
    }

    if (action == "uninstall") {
        int32_t hookId = static_cast<int32_t>(req.getInt("hookId", 0));
        std::lock_guard<std::mutex> tblLk(g_hookTableMtx);
        for (auto& slot : g_slots) {
            if (slot.active.load(std::memory_order_acquire) && slot.id == hookId) {
#if GGNEXT_HAS_SHADOWHOOK
                if (slot.stub) {
                    int rc = slot.isIntercept ? shadowhook_unintercept(slot.stub) : shadowhook_unhook(slot.stub);
                    if (rc != 0) {
                        int errNo = shadowhook_get_errno();
                        std::ostringstream oss;
                        oss << "{\"ok\":false,\"error\":\"shadowhook_unhook failed (" << errNo << "): "
                            << jsonEscape(shadowhook_to_errmsg(errNo)) << "\"}";
                        return oss.str();
                    }
                }
#endif
                slot.active.store(false, std::memory_order_release);
                slot.stub = nullptr;
                slot.origAddr = nullptr;
                return "{\"ok\":true}";
            }
        }
        return "{\"ok\":false,\"error\":\"Hook ID not found\"}";
    }

    if (action == "uninstall_all") {
        int removed = 0;
        std::lock_guard<std::mutex> tblLk(g_hookTableMtx);
        for (auto& slot : g_slots) {
            if (slot.active.load(std::memory_order_acquire)) {
#if GGNEXT_HAS_SHADOWHOOK
                if (slot.stub) {
                    if (slot.isIntercept) shadowhook_unintercept(slot.stub);
                    else shadowhook_unhook(slot.stub);
                }
#endif
                slot.active.store(false, std::memory_order_release);
                slot.stub = nullptr;
                slot.origAddr = nullptr;
                ++removed;
            }
        }
        std::ostringstream oss;
        oss << "{\"ok\":true,\"removed\":" << removed << "}";
        return oss.str();
    }

    if (action == "list") {
        std::lock_guard<std::mutex> tblLk(g_hookTableMtx);
        std::ostringstream oss;
        oss << "{\"ok\":true,\"hooks\":[";
        bool first = true;
        for (auto& slot : g_slots) {
            if (!slot.active.load(std::memory_order_acquire)) continue;
            if (!first) oss << ",";
            first = false;
            std::lock_guard<std::mutex> lk(slot.ruleMtx);
            uint64_t origAddr = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(slot.origAddr));
            oss << "{\"id\":" << slot.id
                << ",\"mode\":\"" << (slot.isIntercept ? "intercept" : "hook") << "\""
                << ",\"libName\":\"" << jsonEscape(slot.libName) << "\""
                << ",\"symbolName\":\"" << jsonEscape(slot.symbolName) << "\""
                << ",\"targetAddress\":\"" << hexAddr(slot.targetAddress) << "\""
                << ",\"targetAddressInt\":" << static_cast<int64_t>(slot.targetAddress)
                << ",\"origAddress\":\"" << hexAddr(origAddr) << "\""
                << ",\"origAddressInt\":" << static_cast<int64_t>(origAddr)
                << ",\"hitCount\":" << slot.hitCount.load(std::memory_order_relaxed)
                << ",\"bypassOriginal\":" << (slot.bypassOriginal ? "true" : "false")
                << ",\"hasReturnOverride\":" << (slot.hasReturnOverride ? "true" : "false")
                << ",\"returnOverride\":\"" << hexAddr(slot.returnOverride) << "\""
                << ",\"logEnabled\":" << (slot.logEnabled ? "true" : "false")
                << ",\"active\":true}";
        }
        oss << "]}";
        return oss.str();
    }

    if (action == "logs") {
        int32_t filterHookId = static_cast<int32_t>(req.getInt("hookId", 0));
        uint64_t sinceSeq = req.getU64("sinceSeq", 0);
        size_t limit = static_cast<size_t>(std::max<int64_t>(1, std::min<int64_t>(req.getInt("limit", 100), 512)));
        bool clearAfter = req.getBool("clear", false);

        std::lock_guard<std::mutex> lk(g_hitLogMtx);
        std::ostringstream oss;
        oss << "{\"ok\":true,\"logs\":[";
        size_t emitted = 0;
        for (const auto& rec : g_hitRing) {
            if (filterHookId > 0 && rec.hookId != filterHookId) continue;
            if (rec.seq <= sinceSeq) continue;
            if (emitted > 0) oss << ",";
            oss << "{\"seq\":" << rec.seq
                << ",\"hookId\":" << rec.hookId
                << ",\"tid\":" << rec.tid
                << ",\"timestampMs\":" << rec.timestampMs
                << ",\"mode\":\"" << (rec.isIntercept ? "intercept" : "hook") << "\""
                << ",\"pc\":\"" << hexAddr(rec.pc) << "\""
                << ",\"lr\":\"" << hexAddr(rec.lr) << "\""
                << ",\"args\":[";
            for (size_t i = 0; i < 8; ++i) {
                if (i > 0) oss << ",";
                oss << static_cast<int64_t>(rec.args[i]);
            }
            oss << "],\"argsHex\":[";
            for (size_t i = 0; i < 8; ++i) {
                if (i > 0) oss << ",";
                oss << "\"" << hexAddr(rec.args[i]) << "\"";
            }
            oss << "],\"origRet\":" << static_cast<int64_t>(rec.origRet)
                << ",\"origRetHex\":\"" << hexAddr(rec.origRet) << "\""
                << ",\"ret\":" << static_cast<int64_t>(rec.ret)
                << ",\"retHex\":\"" << hexAddr(rec.ret) << "\""
                << ",\"bypassed\":" << (rec.bypassed ? "true" : "false")
                << ",\"modified\":" << (rec.modified ? "true" : "false") << "}";
            if (++emitted >= limit) break;
        }
        oss << "]}";

        if (clearAfter) {
            if (filterHookId <= 0) {
                g_hitRing.clear();
            } else {
                g_hitRing.erase(
                    std::remove_if(g_hitRing.begin(), g_hitRing.end(),
                                   [filterHookId](const HookHitRecord& r) { return r.hookId == filterHookId; }),
                    g_hitRing.end()
                );
            }
        }
        return oss.str();
    }

    if (action == "clear_logs") {
        int32_t filterHookId = static_cast<int32_t>(req.getInt("hookId", 0));
        std::lock_guard<std::mutex> lk(g_hitLogMtx);
        if (filterHookId <= 0) {
            g_hitRing.clear();
        } else {
            g_hitRing.erase(
                std::remove_if(g_hitRing.begin(), g_hitRing.end(),
                               [filterHookId](const HookHitRecord& r) { return r.hookId == filterHookId; }),
                g_hitRing.end()
            );
        }
        return "{\"ok\":true}";
    }

    if (action == "call") {
        int32_t hookId = static_cast<int32_t>(req.getInt("hookId", 0));
        uint64_t addr = req.getU64("address", 0);
        std::string libName = req.getStr("libName");
        std::string symName = req.getStr("symbolName");
        std::string retType = req.getStr("returnType", "int64");

        void* fnPtr = nullptr;
        if (hookId > 0) {
            std::lock_guard<std::mutex> tblLk(g_hookTableMtx);
            for (auto& slot : g_slots) {
                if (slot.active.load(std::memory_order_acquire) && slot.id == hookId) {
                    if (slot.origAddr) {
                        fnPtr = slot.origAddr;
                    } else {
                        return "{\"ok\":false,\"error\":\"Hook slot has no orig trampoline (intercept mode hooks do not create an orig stub)\"}";
                    }
                    break;
                }
            }
            if (!fnPtr) {
                return "{\"ok\":false,\"error\":\"Active hook ID not found for trampoline call\"}";
            }
        } else if (addr != 0) {
            fnPtr = reinterpret_cast<void*>(static_cast<uintptr_t>(addr));
        } else if (!libName.empty() && !symName.empty()) {
            void* handle = xdl_open(libName.c_str(), XDL_DEFAULT);
            if (handle) {
                fnPtr = xdl_sym(handle, symName.c_str(), nullptr);
                if (!fnPtr) fnPtr = xdl_dsym(handle, symName.c_str(), nullptr);
                xdl_close(handle);
            }
            if (!fnPtr) {
                return "{\"ok\":false,\"error\":\"Symbol not found via xDL\"}";
            }
        } else {
            return "{\"ok\":false,\"error\":\"Provide hookId, address, or (libName + symbolName)\"}";
        }

        std::array<uint64_t, 8> gpr{};
        std::array<double, 8> fpr{};
        int gprIdx = 0;
        int fprIdx = 0;
        std::vector<std::string> ownedStrings;
        ownedStrings.reserve(8);

        if (const auto* argsVal = req.get("args")) {
            if (argsVal->type == JsonVal::Type::Array) {
                for (const auto& item : argsVal->arr) {
                    if (item.type == JsonVal::Type::Object) {
                        std::string t = item.getStr("type", "int64");
                        if (t == "float" || t == "double") {
                            if (fprIdx < 8) {
                                fpr[static_cast<size_t>(fprIdx++)] = item.getDouble("value", 0.0);
                            }
                        } else if (t == "string" || t == "cstr") {
                            if (gprIdx < 8) {
                                ownedStrings.push_back(item.getStr("value", ""));
                                gpr[static_cast<size_t>(gprIdx++)] =
                                    static_cast<uint64_t>(reinterpret_cast<uintptr_t>(ownedStrings.back().c_str()));
                            }
                        } else {
                            if (gprIdx < 8) {
                                gpr[static_cast<size_t>(gprIdx++)] = item.getU64("value", 0);
                            }
                        }
                    } else if (item.type == JsonVal::Type::Double) {
                        if (fprIdx < 8) fpr[static_cast<size_t>(fprIdx++)] = item.d;
                    } else if (item.type == JsonVal::Type::Int) {
                        if (gprIdx < 8) gpr[static_cast<size_t>(gprIdx++)] = static_cast<uint64_t>(item.i);
                    } else if (item.type == JsonVal::Type::Bool) {
                        if (gprIdx < 8) gpr[static_cast<size_t>(gprIdx++)] = item.b ? 1ULL : 0ULL;
                    } else if (item.type == JsonVal::Type::String) {
                        if (gprIdx < 8) {
                            ownedStrings.push_back(item.s);
                            gpr[static_cast<size_t>(gprIdx++)] =
                                static_cast<uint64_t>(reinterpret_cast<uintptr_t>(ownedStrings.back().c_str()));
                        }
                    }
                }
            }
        }

        auto callRes = invokeTargetFunctionSafe(fnPtr, gpr, fpr, fprIdx, retType);
        if (!callRes.ok) {
            std::ostringstream oss;
            oss << "{\"ok\":false,\"error\":\"" << jsonEscape(callRes.error) << "\"}";
            return oss.str();
        }

        std::ostringstream oss;
        oss << "{\"ok\":true"
            << ",\"calledAddress\":\"" << hexAddr(callRes.calledAddress) << "\""
            << ",\"returnType\":\"" << jsonEscape(retType) << "\""
            << ",\"rawHex\":\"" << hexAddr(callRes.rawInt) << "\""
            << ",\"rawInt\":" << static_cast<int64_t>(callRes.rawInt);
        if (retType == "float" || retType == "double") {
            char fbuf[48];
            std::snprintf(fbuf, sizeof(fbuf), "%.17g", callRes.fpVal);
            oss << ",\"value\":" << fbuf;
        } else if (retType == "string") {
            oss << ",\"value\":\"" << jsonEscape(callRes.strVal) << "\"";
        } else if (retType == "ptr") {
            oss << ",\"value\":\"" << hexAddr(callRes.rawInt) << "\"";
        } else if (retType == "void") {
            oss << ",\"value\":null";
        } else if (retType == "int32") {
            oss << ",\"value\":" << static_cast<int32_t>(callRes.rawInt);
        } else {
            oss << ",\"value\":" << static_cast<int64_t>(callRes.rawInt);
        }
        oss << "}";
        return oss.str();
    }

    return "{\"ok\":false,\"error\":\"Unknown hook action\"}";
}

// ============================================================================
// 6. Abstract Unix Domain Socket IPC Server (\0ggnext_hook_<pid>)
// ============================================================================
std::once_flag g_ipcOnce;

bool readExact(int fd, void* buf, size_t len) {
    uint8_t* p = static_cast<uint8_t*>(buf);
    size_t got = 0;
    while (got < len) {
        ssize_t n = ::recv(fd, p + got, len - got, 0);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return false;
        }
        got += static_cast<size_t>(n);
    }
    return true;
}

bool writeExact(int fd, const void* buf, size_t len) {
    const uint8_t* p = static_cast<const uint8_t*>(buf);
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = ::send(fd, p + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return false;
        }
        sent += static_cast<size_t>(n);
    }
    return true;
}

void startHookIpcServerOnce() {
    std::call_once(g_ipcOnce, []() {
        std::thread([]() {
            int srv = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
            if (srv < 0) return;

            struct sockaddr_un addr{};
            addr.sun_family = AF_UNIX;
            char name[64];
            int nameLen = std::snprintf(name, sizeof(name), "ggnext_hook_%d", static_cast<int>(::getpid()));
            if (nameLen <= 0 || nameLen >= static_cast<int>(sizeof(addr.sun_path) - 1)) {
                ::close(srv);
                return;
            }
            addr.sun_path[0] = '\0';
            std::memcpy(addr.sun_path + 1, name, static_cast<size_t>(nameLen));
            socklen_t sockLen = static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) + 1 + nameLen);

            if (::bind(srv, reinterpret_cast<struct sockaddr*>(&addr), sockLen) != 0) {
                ::close(srv);
                return;
            }
            if (::listen(srv, 8) != 0) {
                ::close(srv);
                return;
            }

            AGENT_LOGI("ShadowHook IPC server listening on @%s", name);
            while (true) {
                int cli = ::accept4(srv, nullptr, nullptr, SOCK_CLOEXEC);
                if (cli < 0) {
                    if (errno == EINTR) continue;
                    break;
                }
                struct timeval tv{3, 0};
                ::setsockopt(cli, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
                ::setsockopt(cli, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

                uint32_t reqLen = 0;
                if (readExact(cli, &reqLen, sizeof(reqLen)) && reqLen > 0 && reqLen <= 1024 * 1024) {
                    std::string req(reqLen, '\0');
                    if (readExact(cli, req.data(), reqLen)) {
                        std::string resp = handleHookCommandJson(req);
                        uint32_t respLen = static_cast<uint32_t>(resp.size());
                        if (writeExact(cli, &respLen, sizeof(respLen))) {
                            writeExact(cli, resp.data(), resp.size());
                        }
                    }
                }
                ::close(cli);
            }
            ::close(srv);
        }).detach();
    });
}

} // namespace

extern "C" {

__attribute__((visibility("default")))
int ggnext_agent_install_hooks() {
    if (g_hooksInstalled.exchange(true)) {
        return 0;
    }
#if GGNEXT_HAS_SHADOWHOOK
    ensureShadowHookInit();
    g_stubClockGettime = shadowhook_hook_sym_name(
        "libc.so", "clock_gettime",
        reinterpret_cast<void*>(hooked_clock_gettime),
        reinterpret_cast<void**>(&g_origClockGettime)
    );
    g_stubGettimeofday = shadowhook_hook_sym_name(
        "libc.so", "gettimeofday",
        reinterpret_cast<void*>(hooked_gettimeofday),
        reinterpret_cast<void**>(&g_origGettimeofday)
    );
    g_stubTime = shadowhook_hook_sym_name(
        "libc.so", "time",
        reinterpret_cast<void*>(hooked_time),
        reinterpret_cast<void**>(&g_origTime)
    );
    g_stubUsleep = shadowhook_hook_sym_name(
        "libc.so", "usleep",
        reinterpret_cast<void*>(hooked_usleep),
        reinterpret_cast<void**>(&g_origUsleep)
    );
    g_stubNanosleep = shadowhook_hook_sym_name(
        "libc.so", "nanosleep",
        reinterpret_cast<void*>(hooked_nanosleep),
        reinterpret_cast<void**>(&g_origNanosleep)
    );
    AGENT_LOGI("ShadowHook+xDL time hooks installed (clock_gettime=%p, gettimeofday=%p)",
               g_stubClockGettime, g_stubGettimeofday);
    return (g_stubClockGettime != nullptr || g_stubGettimeofday != nullptr) ? 0 : -1;
#else
    AGENT_LOGW("ShadowHook inline hooks are ARM/ARM64 only; xDL verified on x86/x86_64");
    void* libc = xdl_open("libc.so", XDL_DEFAULT);
    if (libc) xdl_close(libc);
    return 0;
#endif
}

__attribute__((visibility("default")))
void ggnext_agent_set_speed(float rate) {
    if (!std::isfinite(rate)) return;
    double clamped = static_cast<double>(rate);
    if (clamped < 0.01) clamped = 0.01;
    if (clamped > 100.0) clamped = 100.0;
    if (!g_hooksInstalled.load()) {
        ggnext_agent_install_hooks();
    }
    g_speedRate.store(clamped, std::memory_order_release);
}

__attribute__((visibility("default")))
float ggnext_agent_get_speed() {
    return static_cast<float>(g_speedRate.load(std::memory_order_acquire));
}

__attribute__((visibility("default")))
uint64_t ggnext_agent_alloc_page(uint64_t hintAddr, size_t size, int prot) {
    long pageSize = ::sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    size_t alignedLen = (size == 0) ? static_cast<size_t>(pageSize)
                                    : ((size + static_cast<size_t>(pageSize) - 1) & ~(static_cast<size_t>(pageSize) - 1));
    void* p = ::mmap(
        reinterpret_cast<void*>(static_cast<uintptr_t>(hintAddr)),
        alignedLen,
        prot,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (p == MAP_FAILED) return 0;
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(p));
}

__attribute__((visibility("default")))
int ggnext_agent_is_hook_active() {
    return g_hooksInstalled.load() ? 1 : 0;
}

__attribute__((visibility("default")))
int ggnext_agent_hook_command(const char* reqJson, char* outBuf, size_t outCap) {
    if (!reqJson || !outBuf || outCap == 0) return -1;
    startHookIpcServerOnce();
    std::string resp = handleHookCommandJson(reqJson);
    if (resp.size() >= outCap) {
        std::memcpy(outBuf, resp.data(), outCap - 1);
        outBuf[outCap - 1] = '\0';
        return static_cast<int>(outCap - 1);
    }
    std::memcpy(outBuf, resp.data(), resp.size() + 1);
    return static_cast<int>(resp.size());
}

__attribute__((constructor))
static void ggnext_agent_on_load() {
    startHookIpcServerOnce();
    // Auto-install speedhack hooks when loaded via LD_PRELOAD or wrap.<pkg>
    const char* autoEnv = std::getenv("GGNEXT_AUTO_SPEED");
    if (autoEnv && autoEnv[0] == '1') {
        ggnext_agent_install_hooks();
    }
}

} // extern "C"
