#include "mem_engine/debugger.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/proc_io.hpp"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sstream>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>

#ifndef PTRACE_SEIZE
#define PTRACE_SEIZE 0x4206
#endif
#ifndef PTRACE_INTERRUPT
#define PTRACE_INTERRUPT 0x4207
#endif
#ifndef PTRACE_EVENT_STOP
#define PTRACE_EVENT_STOP 128
#endif
#ifndef TRAP_HWBKPT
#define TRAP_HWBKPT 4
#endif
#ifndef TRAP_TRACE
#define TRAP_TRACE 2
#endif
#ifndef TRAP_BRKPT
#define TRAP_BRKPT 1
#endif
#ifndef NT_ARM_VFP
#define NT_ARM_VFP 0x400
#endif
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>
#include <unordered_map>

#include <linux/hw_breakpoint.h>
#include <linux/perf_event.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif
#ifndef NT_PRFPREG
#define NT_PRFPREG 2
#endif
#ifndef NT_ARM_HW_BREAK
#define NT_ARM_HW_BREAK 0x402
#endif
#ifndef NT_ARM_HW_WATCH
#define NT_ARM_HW_WATCH 0x403
#endif
#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif
#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif
#ifndef PTRACE_SETHBPREGS
#define PTRACE_SETHBPREGS 30
#endif

namespace mem_engine {

namespace {

std::string hostArchName() {
#if defined(__aarch64__)
    return "aarch64";
#elif defined(__arm__)
    return "arm";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

std::vector<int> listProcessThreads(int pid) {
    std::vector<int> tids;
    char dirPath[64];
    std::snprintf(dirPath, sizeof(dirPath), "/proc/%d/task", pid);
    DIR* d = ::opendir(dirPath);
    if (!d) return tids;
    while (struct dirent* ent = ::readdir(d)) {
        if (ent->d_name[0] < '0' || ent->d_name[0] > '9') continue;
        int tid = std::atoi(ent->d_name);
        if (tid > 0) tids.push_back(tid);
    }
    ::closedir(d);
    std::sort(tids.begin(), tids.end());
    return tids;
}

std::string baseModuleName(const std::string& path) {
    if (path.empty()) return "[anon]";
    size_t pos = path.find_last_of('/');
    if (pos != std::string::npos && pos + 1 < path.size()) {
        return path.substr(pos + 1);
    }
    return path;
}

void resolveAddressModule(
    const std::vector<VmaRegion>& maps,
    uint64_t addr,
    std::string& outModule,
    uint64_t& outOffset
) {
    outModule = "[anon]";
    outOffset = 0;
    for (const auto& r : maps) {
        if (addr >= r.startAddress && addr < r.endAddress) {
            outModule = baseModuleName(r.pathname);
            // Compute offset from earliest mapping of same module if file-backed
            uint64_t base = r.startAddress;
            if (!r.pathname.empty() && r.pathname[0] == '/') {
                for (const auto& first : maps) {
                    if (first.pathname == r.pathname) {
                        base = first.startAddress;
                        break;
                    }
                }
            }
            outOffset = addr - base;
            return;
        }
    }
}

std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) >= 0x20) out.push_back(c);
                break;
        }
    }
    return out;
}

std::string bytesToHexLower(const std::vector<uint8_t>& b) {
    static const char* kHex = "0123456789ABCDEF";
    std::string out;
    out.resize(b.size() * 2);
    for (size_t i = 0; i < b.size(); ++i) {
        out[i * 2] = kHex[(b[i] >> 4) & 0xF];
        out[i * 2 + 1] = kHex[b[i] & 0xF];
    }
    return out;
}

// Unwind frame-pointer chain from target memory using ProcIO
std::vector<CallFrameEntry> unwindFpChain(
    ProcIO& io,
    const std::vector<VmaRegion>& maps,
    uint64_t pc,
    uint64_t lr,
    uint64_t fp,
    int maxDepth = 16
) {
    std::vector<CallFrameEntry> frames;
    auto pushFrame = [&](uint64_t addr) {
        if (addr == 0) return;
        CallFrameEntry f;
        f.address = addr;
        resolveAddressModule(maps, addr, f.moduleName, f.moduleOffset);
        frames.push_back(std::move(f));
    };

    pushFrame(pc);
    if (lr != 0 && lr != pc) {
        pushFrame(lr);
    }

    uint64_t curFp = fp;
    for (int i = 0; i < maxDepth && curFp != 0 && (curFp & 0x7ULL) == 0; ++i) {
        uint64_t pair[2] = {0, 0};
        size_t got = 0;
        if (io.readMemory(static_cast<uintptr_t>(curFp), pair, sizeof(pair), &got) != EngineErrc::OK || got < sizeof(pair)) {
            break;
        }
        uint64_t nextFp = pair[0];
        uint64_t retAddr = pair[1];
        if (retAddr != 0) {
            pushFrame(retAddr);
        }
        if (nextFp <= curFp || nextFp - curFp > (1ULL << 22)) {
            break;
        }
        curFp = nextFp;
    }
    return frames;
}

// ============================================================================
// Architecture-specific Perf & Ptrace Helpers
// ============================================================================

uint32_t toPerfBpType(BpType t) {
    switch (t) {
        case BpType::READ:  return HW_BREAKPOINT_R;
        case BpType::WRITE: return HW_BREAKPOINT_W;
        case BpType::RW:    return HW_BREAKPOINT_RW;
        case BpType::EXEC:  return HW_BREAKPOINT_X;
        default:            return HW_BREAKPOINT_RW;
    }
}

uint64_t toPerfBpLen(int32_t sizeBytes, BpType t) {
    if (t == BpType::EXEC) {
#if defined(__aarch64__) || defined(__arm__)
        return HW_BREAKPOINT_LEN_4;
#else
        return HW_BREAKPOINT_LEN_1;
#endif
    }
    switch (sizeBytes) {
        case 1:  return HW_BREAKPOINT_LEN_1;
        case 2:  return HW_BREAKPOINT_LEN_2;
        case 8:  return HW_BREAKPOINT_LEN_8;
        default: return HW_BREAKPOINT_LEN_4;
    }
}


// Unified floating-point / SIMD register reader for an already ptrace-stopped thread
bool captureFpRegsFromStoppedTid(int tid, std::vector<FpRegEntry>& outFprs) {
    outFprs.clear();
#if defined(__aarch64__)
    struct user_fpsimd_state fpRegs{};
    struct iovec fpIov{&fpRegs, sizeof(fpRegs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRFPREG)), &fpIov) != 0) {
        return false;
    }
    outFprs.reserve(32);
    for (int i = 0; i < 32; ++i) {
        uint64_t lo = static_cast<uint64_t>(fpRegs.vregs[i]);
        uint64_t hi = static_cast<uint64_t>(fpRegs.vregs[i] >> 64);
        char hex[40];
        if (hi != 0) {
            std::snprintf(hex, sizeof(hex), "%016llX%016llX",
                          static_cast<unsigned long long>(hi),
                          static_cast<unsigned long long>(lo));
        } else {
            std::snprintf(hex, sizeof(hex), "%llX", static_cast<unsigned long long>(lo));
        }
        outFprs.push_back({"v" + std::to_string(i), hex});
    }
    return true;
#elif defined(__arm__)
    struct {
        uint64_t fpregs[32];
        uint32_t fpscr;
    } vfpRegs{};
    struct iovec fpIov{&vfpRegs, sizeof(vfpRegs)};
    bool ok = (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_ARM_VFP)), &fpIov) == 0);
#ifdef PTRACE_GETVFPREGS
    if (!ok) {
        ok = (::ptrace(PTRACE_GETVFPREGS, tid, nullptr, &vfpRegs) == 0);
    }
#endif
    if (!ok) return false;
    outFprs.reserve(32);
    for (int i = 0; i < 32; ++i) {
        char hex[24];
        std::snprintf(hex, sizeof(hex), "%llX", static_cast<unsigned long long>(vfpRegs.fpregs[i]));
        outFprs.push_back({"d" + std::to_string(i), hex});
    }
    return true;
#elif defined(__x86_64__)
    struct user_fpregs_struct fpRegs{};
    struct iovec fpIov{&fpRegs, sizeof(fpRegs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRFPREG)), &fpIov) != 0) {
        return false;
    }
    outFprs.reserve(16);
    for (int i = 0; i < 16; ++i) {
        uint64_t lo = 0, hi = 0;
        std::memcpy(&lo, &fpRegs.xmm_space[i * 4], 8);
        std::memcpy(&hi, &fpRegs.xmm_space[i * 4 + 2], 8);
        char hex[40];
        if (hi != 0) {
            std::snprintf(hex, sizeof(hex), "%016llX%016llX",
                          static_cast<unsigned long long>(hi),
                          static_cast<unsigned long long>(lo));
        } else {
            std::snprintf(hex, sizeof(hex), "%llX", static_cast<unsigned long long>(lo));
        }
        outFprs.push_back({"xmm" + std::to_string(i), hex});
    }
    return true;
#elif defined(__i386__)
    struct user_fpxregs_struct fpRegs{};
    struct iovec fpIov{&fpRegs, sizeof(fpRegs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRXFPREG)), &fpIov) != 0) {
        return false;
    }
    outFprs.reserve(8);
    for (int i = 0; i < 8; ++i) {
        uint64_t lo = 0, hi = 0;
        std::memcpy(&lo, &fpRegs.xmm_space[i * 4], 8);
        std::memcpy(&hi, &fpRegs.xmm_space[i * 4 + 2], 8);
        char hex[40];
        if (hi != 0) {
            std::snprintf(hex, sizeof(hex), "%016llX%016llX",
                          static_cast<unsigned long long>(hi),
                          static_cast<unsigned long long>(lo));
        } else {
            std::snprintf(hex, sizeof(hex), "%llX", static_cast<unsigned long long>(lo));
        }
        outFprs.push_back({"xmm" + std::to_string(i), hex});
    }
    return true;
#else
    (void)tid;
    return false;
#endif
}

inline int sendThreadSignal(int pid, int tid, int sig) {
    return static_cast<int>(::syscall(__NR_tgkill, pid, tid, sig));
}

// Wait with bounded timeout for a specific tid to stop, preserving any real app signal
bool waitForTidStopBounded(int tid, bool seized, int timeoutMs, bool& outExited, int& outPendingSig) {
    outExited = false;
    outPendingSig = 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        int st = 0;
        pid_t r = ::waitpid(tid, &st, __WALL | WNOHANG);
        if (r == tid) {
            if (WIFEXITED(st) || WIFSIGNALED(st)) {
                outExited = true;
                return false;
            }
            if (WIFSTOPPED(st)) {
                int sig = WSTOPSIG(st);
                unsigned ev = (static_cast<unsigned>(st) >> 16) & 0xFFFFu;
                if (ev == PTRACE_EVENT_STOP || (!seized && sig == SIGSTOP)) {
                    outPendingSig = 0;
                } else if (sig != SIGTRAP) {
                    outPendingSig = sig;
                }
                return true;
            }
        } else if (r < 0) {
            if (errno == EINTR) continue;
            if (errno == ECHILD) outExited = true;
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}

// Brief, non-blocking, signal-safe FP register snapshot for Perf mode (PTRACE_SEIZE + INTERRUPT -> GETREGSET -> DETACH)
bool sampleFpRegsBrief(int pid, int tid, std::vector<FpRegEntry>& outFprs) {
    if (tid <= 0 || tid == ::getpid() || tid == ::gettid()) return false;
    bool seized = false;
    if (::ptrace(PTRACE_SEIZE, tid, nullptr, nullptr) == 0) {
        seized = true;
        if (::ptrace(PTRACE_INTERRUPT, tid, nullptr, nullptr) != 0) {
            ::ptrace(PTRACE_DETACH, tid, nullptr, nullptr);
            return false;
        }
    } else if (::ptrace(PTRACE_ATTACH, tid, nullptr, nullptr) != 0) {
        return false;
    }

    bool exited = false;
    int pendingSig = 0;
    bool stopped = waitForTidStopBounded(tid, seized, 30, exited, pendingSig);
    bool ok = false;
    if (stopped) {
        ok = captureFpRegsFromStoppedTid(tid, outFprs);
    }
    if (!exited) {
        if (!stopped && seized) {
            ::ptrace(PTRACE_INTERRUPT, tid, nullptr, nullptr);
            waitForTidStopBounded(tid, seized, 15, exited, pendingSig);
        }
        if (!exited) {
            ::ptrace(PTRACE_DETACH, tid, nullptr, reinterpret_cast<void*>(static_cast<uintptr_t>(pendingSig)));
        }
    }
    (void)pid;
    return ok;
}

#if defined(__aarch64__)
struct Arm64HwDebugState {
    uint32_t dbg_info;
    uint32_t pad;
    struct {
        uint64_t addr;
        uint32_t ctrl;
        uint32_t pad;
    } dbg_regs[16];
};

bool installArm64HwBp(int tid, uint64_t addr, BpType type, int32_t sizeBytes, bool enable, int slot = 0) {
    const int regset = (type == BpType::EXEC) ? NT_ARM_HW_BREAK : NT_ARM_HW_WATCH;
    Arm64HwDebugState state{};
    struct iovec iov{};
    iov.iov_base = &state;
    iov.iov_len = sizeof(state);

    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(regset)), &iov) < 0) {
        return false;
    }

    int maxSlots = static_cast<int>(state.dbg_info & 0xFFu);
    if (maxSlots <= 0) maxSlots = 1;
    if (slot >= maxSlots) slot = 0;

    if (!enable) {
        state.dbg_regs[slot].addr = 0;
        state.dbg_regs[slot].ctrl = 0;
    } else if (type == BpType::EXEC) {
        // Execution hardware breakpoint (4-byte aligned instruction)
        state.dbg_regs[slot].addr = addr & ~3ULL;
        // E=1, PMC=EL0(2<<1), BAS=0xF(<<5)
        state.dbg_regs[slot].ctrl = 1u | (2u << 1) | (0xFu << 5);
    } else {
        // Data watchpoint: align base to 8 bytes and compute byte-address-select mask
        uint64_t alignedAddr = addr & ~7ULL;
        uint32_t byteOff = static_cast<uint32_t>(addr & 7ULL);
        int32_t clampedLen = (sizeBytes == 1 || sizeBytes == 2 || sizeBytes == 8) ? sizeBytes : 4;
        if (byteOff + static_cast<uint32_t>(clampedLen) > 8u) {
            clampedLen = static_cast<int32_t>(8u - byteOff);
            if (clampedLen <= 0) clampedLen = 1;
        }
        uint32_t bas = ((1u << clampedLen) - 1u) << byteOff;
        uint32_t lsc = (type == BpType::READ) ? 1u : (type == BpType::WRITE ? 2u : 3u);
        // E=1, PAC=EL0(2<<1), LSC=(lsc<<3), BAS=(bas<<5)
        state.dbg_regs[slot].addr = alignedAddr;
        state.dbg_regs[slot].ctrl = 1u | (2u << 1) | (lsc << 3) | ((bas & 0xFFu) << 5);
    }

    iov.iov_base = &state;
    // Kernel expects exact size for active slot count: header(8) + (slot+1)*16
    iov.iov_len = 8 + static_cast<size_t>(slot + 1) * 16;
    return ::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(regset)), &iov) == 0;
}

bool captureArm64Regs(
    int tid,
    uint64_t& outPc,
    uint64_t& outSp,
    uint64_t& outLr,
    uint64_t& outFp,
    std::vector<RegEntry>& outGprs,
    std::vector<FpRegEntry>& outFprs
) {
    struct user_pt_regs regs{};
    struct iovec iov{&regs, sizeof(regs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
        return false;
    }
    outPc = regs.pc;
    outSp = regs.sp;
    outLr = regs.regs[30];
    outFp = regs.regs[29];

    outGprs.clear();
    outGprs.reserve(34);
    for (int i = 0; i <= 28; ++i) {
        outGprs.push_back({"x" + std::to_string(i), regs.regs[i]});
    }
    outGprs.push_back({"fp", regs.regs[29]});
    outGprs.push_back({"lr", regs.regs[30]});
    outGprs.push_back({"sp", regs.sp});
    outGprs.push_back({"pc", regs.pc});
    outGprs.push_back({"pstate", regs.pstate});

    struct user_fpsimd_state fpRegs{};
    struct iovec fpIov{&fpRegs, sizeof(fpRegs)};
    outFprs.clear();
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRFPREG)), &fpIov) == 0) {
        outFprs.reserve(32);
        for (int i = 0; i < 32; ++i) {
            uint64_t lo = static_cast<uint64_t>(fpRegs.vregs[i]);
            uint64_t hi = static_cast<uint64_t>(fpRegs.vregs[i] >> 64);
            char hex[40];
            if (hi != 0) {
                std::snprintf(hex, sizeof(hex), "%016llX%016llX",
                              static_cast<unsigned long long>(hi),
                              static_cast<unsigned long long>(lo));
            } else {
                std::snprintf(hex, sizeof(hex), "%llX", static_cast<unsigned long long>(lo));
            }
            outFprs.push_back({"v" + std::to_string(i), hex});
        }
    }
    return true;
}
#endif

#if defined(__arm__)
bool installArm32HwBp(int tid, uint64_t addr, BpType type, int32_t sizeBytes, bool enable) {
    // Positive index = breakpoint, negative index = watchpoint (-1 = addr, -2 = ctrl)
    long idxAddr = (type == BpType::EXEC) ? 1 : -1;
    long idxCtrl = (type == BpType::EXEC) ? 2 : -2;
    uint32_t alignedAddr = static_cast<uint32_t>(addr & ~3ULL);
    uint32_t ctrl = 0;
    if (enable) {
        if (type == BpType::EXEC) {
            ctrl = 1u | (2u << 1) | (0xFu << 5);
        } else {
            uint32_t off = static_cast<uint32_t>(addr & 3ULL);
            int32_t len = (sizeBytes == 1 || sizeBytes == 2) ? sizeBytes : 4;
            if (off + static_cast<uint32_t>(len) > 4u) len = static_cast<int32_t>(4u - off);
            uint32_t bas = ((1u << len) - 1u) << off;
            uint32_t lsc = (type == BpType::READ) ? 1u : (type == BpType::WRITE ? 2u : 3u);
            ctrl = 1u | (2u << 1) | (lsc << 3) | ((bas & 0xFu) << 5);
        }
    }
    if (::ptrace(PTRACE_SETHBPREGS, tid, reinterpret_cast<void*>(idxAddr), &alignedAddr) < 0) {
        return false;
    }
    return ::ptrace(PTRACE_SETHBPREGS, tid, reinterpret_cast<void*>(idxCtrl), &ctrl) == 0;
}

bool captureArm32Regs(
    int tid,
    uint64_t& outPc,
    uint64_t& outSp,
    uint64_t& outLr,
    uint64_t& outFp,
    std::vector<RegEntry>& outGprs,
    std::vector<FpRegEntry>& outFprs
) {
    struct pt_regs regs{};
    struct iovec iov{&regs, sizeof(regs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
        return false;
    }
    outFp = static_cast<uint32_t>(regs.uregs[11]);
    outSp = static_cast<uint32_t>(regs.uregs[13]);
    outLr = static_cast<uint32_t>(regs.uregs[14]);
    outPc = static_cast<uint32_t>(regs.uregs[15]);
    outGprs.clear();
    for (int i = 0; i <= 12; ++i) {
        outGprs.push_back({"r" + std::to_string(i), static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[i]))});
    }
    outGprs.push_back({"sp", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[13]))});
    outGprs.push_back({"lr", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[14]))});
    outGprs.push_back({"pc", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[15]))});
    outGprs.push_back({"cpsr", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[16]))});
    captureFpRegsFromStoppedTid(tid, outFprs);
    return true;
}
#endif

#if defined(__x86_64__) || defined(__i386__)
bool installX86HwBp(int tid, uint64_t addr, BpType type, int32_t sizeBytes, bool enable) {
    const size_t dr0Off = offsetof(struct user, u_debugreg[0]);
    const size_t dr7Off = offsetof(struct user, u_debugreg[7]);
    if (!enable) {
        ::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr7Off), reinterpret_cast<void*>(0));
        ::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr0Off), reinterpret_cast<void*>(0));
        return true;
    }
    if (::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr0Off), reinterpret_cast<void*>(static_cast<uintptr_t>(addr))) < 0) {
        return false;
    }
    // DR7: L0=1, R/W0 (bits 16..17): 00=exec, 01=write, 11=rw/read; LEN0 (bits 18..19): 00=1, 01=2, 10=8, 11=4
    uintptr_t rwBits = (type == BpType::EXEC) ? 0u : (type == BpType::WRITE ? 1u : 3u);
    uintptr_t lenBits = 0u;
    if (type != BpType::EXEC) {
        switch (sizeBytes) {
            case 1: lenBits = 0u; break;
            case 2: lenBits = 1u; break;
            case 8: lenBits = 2u; break;
            default: lenBits = 3u; break;
        }
    }
    uintptr_t dr7 = 1u | (rwBits << 16) | (lenBits << 18);
    return ::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr7Off), reinterpret_cast<void*>(dr7)) == 0;
}

bool captureX86Regs(
    int tid,
    uint64_t& outPc,
    uint64_t& outSp,
    uint64_t& outLr,
    uint64_t& outFp,
    std::vector<RegEntry>& outGprs,
    std::vector<FpRegEntry>& outFprs
) {
    struct user_regs_struct regs{};
    struct iovec iov{&regs, sizeof(regs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
        return false;
    }
    outGprs.clear();
#if defined(__x86_64__)
    outPc = regs.rip;
    outSp = regs.rsp;
    outFp = regs.rbp;
    outLr = 0;
    outGprs = {
        {"rax", regs.rax}, {"rbx", regs.rbx}, {"rcx", regs.rcx}, {"rdx", regs.rdx},
        {"rsi", regs.rsi}, {"rdi", regs.rdi}, {"rbp", regs.rbp}, {"rsp", regs.rsp},
        {"r8",  regs.r8},  {"r9",  regs.r9},  {"r10", regs.r10}, {"r11", regs.r11},
        {"r12", regs.r12}, {"r13", regs.r13}, {"r14", regs.r14}, {"r15", regs.r15},
        {"rip", regs.rip}, {"eflags", regs.eflags}
    };
#else
    outPc = regs.eip;
    outSp = regs.esp;
    outFp = regs.ebp;
    outLr = 0;
    outGprs = {
        {"eax", static_cast<uint32_t>(regs.eax)}, {"ebx", static_cast<uint32_t>(regs.ebx)},
        {"ecx", static_cast<uint32_t>(regs.ecx)}, {"edx", static_cast<uint32_t>(regs.edx)},
        {"esi", static_cast<uint32_t>(regs.esi)}, {"edi", static_cast<uint32_t>(regs.edi)},
        {"ebp", static_cast<uint32_t>(regs.ebp)}, {"esp", static_cast<uint32_t>(regs.esp)},
        {"eip", static_cast<uint32_t>(regs.eip)}, {"eflags", static_cast<uint32_t>(regs.eflags)}
    };
#endif
    captureFpRegsFromStoppedTid(tid, outFprs);
    return true;
}
#endif

int32_t classifyInsnAccessType(const std::vector<uint8_t>& bytes, int32_t configuredType) {
    if (configuredType == static_cast<int32_t>(BpType::EXEC)) {
        return static_cast<int32_t>(BpType::EXEC);
    }
    if (configuredType == static_cast<int32_t>(BpType::READ) ||
        configuredType == static_cast<int32_t>(BpType::WRITE)) {
        return configuredType;
    }
    if (bytes.size() >= 4) {
#if defined(__aarch64__)
        uint32_t insn = 0;
        std::memcpy(&insn, bytes.data(), 4);
        // Load/Store Exclusive / Ordered (LDXR/STXR/LDAR/STLR/LDXP/STXP)
        if ((insn & 0x3F000000u) == 0x08000000u) {
            return ((insn >> 22) & 1u) ? static_cast<int32_t>(BpType::READ)
                                       : static_cast<int32_t>(BpType::WRITE);
        }
        // Load Register (literal)
        if ((insn & 0x3B000000u) == 0x18000000u) {
            return static_cast<int32_t>(BpType::READ);
        }
        // Load/Store Pair (LDP/STP/LDNP/STNP)
        if ((insn & 0x3A000000u) == 0x28000000u) {
            return ((insn >> 22) & 1u) ? static_cast<int32_t>(BpType::READ)
                                       : static_cast<int32_t>(BpType::WRITE);
        }
        // Advanced SIMD Load/Store multiple/single structures (LD1..LD4 / ST1..ST4)
        if ((insn & 0xBF000000u) == 0x0C000000u || (insn & 0xBF000000u) == 0x0D000000u) {
            return ((insn >> 22) & 1u) ? static_cast<int32_t>(BpType::READ)
                                       : static_cast<int32_t>(BpType::WRITE);
        }
        // LSE Atomic Memory Ops (LDADD/SWP/CAS/STADD) - modifies target address
        if ((insn & 0x3B200C00u) == 0x38200000u) {
            return static_cast<int32_t>(BpType::WRITE);
        }
        // Load/Store Register (unscaled, immediate, post/pre-index, register offset, unsigned offset)
        if ((insn & 0x3B000000u) == 0x38000000u || (insn & 0x3B000000u) == 0x39000000u) {
            uint32_t v = (insn >> 26) & 1u;
            uint32_t opc = (insn >> 22) & 3u;
            if (v == 0u) {
                return (opc == 0u) ? static_cast<int32_t>(BpType::WRITE)
                                   : static_cast<int32_t>(BpType::READ);
            } else {
                return ((opc & 1u) == 0u) ? static_cast<int32_t>(BpType::WRITE)
                                          : static_cast<int32_t>(BpType::READ);
            }
        }
#elif defined(__arm__)
        uint32_t insn = 0;
        std::memcpy(&insn, bytes.data(), 4);
        // Single Data Transfer (LDR/STR/LDRB/STRB)
        if ((insn & 0x0C000000u) == 0x04000000u) {
            return ((insn >> 20) & 1u) ? static_cast<int32_t>(BpType::READ)
                                       : static_cast<int32_t>(BpType::WRITE);
        }
        // Block Data Transfer (LDM/STM/PUSH/POP)
        if ((insn & 0x0E000000u) == 0x08000000u) {
            return ((insn >> 20) & 1u) ? static_cast<int32_t>(BpType::READ)
                                       : static_cast<int32_t>(BpType::WRITE);
        }
        // Halfword & Doubleword Data Transfer (LDRH/STRH/LDRD/STRD)
        if ((insn & 0x0E000090u) == 0x00000090u) {
            uint32_t l = (insn >> 20) & 1u;
            uint32_t sh = (insn >> 5) & 3u;
            if (l == 1u) return static_cast<int32_t>(BpType::READ);
            if (sh == 2u) return static_cast<int32_t>(BpType::READ); // LDRD
            return static_cast<int32_t>(BpType::WRITE);
        }
        // VFP VLDR / VSTR
        if ((insn & 0x0F300E00u) == 0x0D100A00u) return static_cast<int32_t>(BpType::READ);
        if ((insn & 0x0F300E00u) == 0x0D000A00u) return static_cast<int32_t>(BpType::WRITE);
#endif
    }
    return configuredType;
}

std::string stripHexPrefix(const std::string& raw) {
    std::string s;
    s.reserve(raw.size());
    for (char c : raw) {
        if (c == ' ' || c == '\t' || c == '_') continue;
        s.push_back(c);
    }
    if (s.size() >= 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        s.erase(0, 2);
    }
    return s;
}

bool parseHexU64(const std::string& raw, uint64_t& outVal) {
    std::string s = stripHexPrefix(raw);
    if (s.empty() || s.size() > 16) return false;
    char* end = nullptr;
    errno = 0;
    unsigned long long v = std::strtoull(s.c_str(), &end, 16);
    if (errno != 0 || !end || *end != '\0') return false;
    outVal = static_cast<uint64_t>(v);
    return true;
}

bool parseHexU128(const std::string& raw, uint64_t& outHi, uint64_t& outLo) {
    std::string s = stripHexPrefix(raw);
    if (s.empty() || s.size() > 32) return false;
    for (char c : s) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return false;
        }
    }
    if (s.size() <= 16) {
        outHi = 0;
        return parseHexU64(s, outLo);
    }
    std::string hiStr = s.substr(0, s.size() - 16);
    std::string loStr = s.substr(s.size() - 16);
    return parseHexU64(hiStr, outHi) && parseHexU64(loStr, outLo);
}

std::string toLowerCopy(const std::string& in) {
    std::string s = in;
    for (char& c : s) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return s;
}

bool writeRegToStoppedTid(
    int tid,
    const std::string& regNameRaw,
    const std::string& hexValue,
    bool isFp,
    uint64_t& outNumericVal,
    std::string& outNormHex,
    std::string& outErr
) {
    std::string name = toLowerCopy(regNameRaw);
    if (!isFp) {
        uint64_t val = 0;
        if (!parseHexU64(hexValue, val)) {
            outErr = "无效的十六进制寄存器值";
            return false;
        }
        outNumericVal = val;
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%llX", static_cast<unsigned long long>(val));
        outNormHex = buf;

#if defined(__aarch64__)
        struct user_pt_regs regs{};
        struct iovec iov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
            outErr = "读取线程通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        bool matched = false;
        if (name.size() >= 2 && name[0] == 'x') {
            int idx = std::atoi(name.c_str() + 1);
            if (idx >= 0 && idx <= 30) {
                regs.regs[idx] = val;
                matched = true;
            }
        } else if (name == "fp") {
            regs.regs[29] = val;
            matched = true;
        } else if (name == "lr") {
            regs.regs[30] = val;
            matched = true;
        } else if (name == "sp") {
            regs.sp = val;
            matched = true;
        } else if (name == "pc") {
            regs.pc = val;
            matched = true;
        } else if (name == "pstate" || name == "cpsr") {
            regs.pstate = val;
            matched = true;
        }
        if (!matched) {
            outErr = "不支持修改该寄存器: " + regNameRaw;
            return false;
        }
        struct iovec setIov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) < 0) {
            outErr = "写回通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        return true;
#elif defined(__arm__)
        struct pt_regs regs{};
        struct iovec iov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
            outErr = "读取线程通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        bool matched = false;
        if (name.size() >= 2 && name[0] == 'r') {
            int idx = std::atoi(name.c_str() + 1);
            if (idx >= 0 && idx <= 15) {
                regs.uregs[idx] = static_cast<unsigned long>(val);
                matched = true;
            }
        } else if (name == "fp") {
            regs.uregs[11] = static_cast<unsigned long>(val);
            matched = true;
        } else if (name == "ip") {
            regs.uregs[12] = static_cast<unsigned long>(val);
            matched = true;
        } else if (name == "sp") {
            regs.uregs[13] = static_cast<unsigned long>(val);
            matched = true;
        } else if (name == "lr") {
            regs.uregs[14] = static_cast<unsigned long>(val);
            matched = true;
        } else if (name == "pc") {
            regs.uregs[15] = static_cast<unsigned long>(val);
            matched = true;
        } else if (name == "cpsr") {
            regs.uregs[16] = static_cast<unsigned long>(val);
            matched = true;
        }
        if (!matched) {
            outErr = "不支持修改该寄存器: " + regNameRaw;
            return false;
        }
        struct iovec setIov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) < 0) {
            outErr = "写回通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        return true;
#elif defined(__x86_64__)
        struct user_regs_struct regs{};
        struct iovec iov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
            outErr = "读取线程通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        bool matched = true;
        if (name == "rax") regs.rax = val;
        else if (name == "rbx") regs.rbx = val;
        else if (name == "rcx") regs.rcx = val;
        else if (name == "rdx") regs.rdx = val;
        else if (name == "rsi") regs.rsi = val;
        else if (name == "rdi") regs.rdi = val;
        else if (name == "rbp") regs.rbp = val;
        else if (name == "rsp") regs.rsp = val;
        else if (name == "r8")  regs.r8  = val;
        else if (name == "r9")  regs.r9  = val;
        else if (name == "r10") regs.r10 = val;
        else if (name == "r11") regs.r11 = val;
        else if (name == "r12") regs.r12 = val;
        else if (name == "r13") regs.r13 = val;
        else if (name == "r14") regs.r14 = val;
        else if (name == "r15") regs.r15 = val;
        else if (name == "rip") regs.rip = val;
        else if (name == "eflags" || name == "rflags") regs.eflags = val;
        else matched = false;
        if (!matched) {
            outErr = "不支持修改该寄存器: " + regNameRaw;
            return false;
        }
        struct iovec setIov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) < 0) {
            outErr = "写回通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        return true;
#elif defined(__i386__)
        struct user_regs_struct regs{};
        struct iovec iov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
            outErr = "读取线程通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        bool matched = true;
        if (name == "eax") regs.eax = static_cast<long>(val);
        else if (name == "ebx") regs.ebx = static_cast<long>(val);
        else if (name == "ecx") regs.ecx = static_cast<long>(val);
        else if (name == "edx") regs.edx = static_cast<long>(val);
        else if (name == "esi") regs.esi = static_cast<long>(val);
        else if (name == "edi") regs.edi = static_cast<long>(val);
        else if (name == "ebp") regs.ebp = static_cast<long>(val);
        else if (name == "esp") regs.esp = static_cast<long>(val);
        else if (name == "eip") regs.eip = static_cast<long>(val);
        else if (name == "eflags") regs.eflags = static_cast<long>(val);
        else matched = false;
        if (!matched) {
            outErr = "不支持修改该寄存器: " + regNameRaw;
            return false;
        }
        struct iovec setIov{&regs, sizeof(regs)};
        if (::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) < 0) {
            outErr = "写回通用寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        return true;
#else
        (void)tid;
        outErr = "当前架构不支持寄存器写回";
        return false;
#endif
    } else {
        uint64_t hi = 0, lo = 0;
        if (!parseHexU128(hexValue, hi, lo)) {
            outErr = "无效的浮点寄存器十六进制值";
            return false;
        }
        outNumericVal = lo;
        char buf[40];
        if (hi != 0) {
            std::snprintf(buf, sizeof(buf), "%016llX%016llX",
                          static_cast<unsigned long long>(hi),
                          static_cast<unsigned long long>(lo));
        } else {
            std::snprintf(buf, sizeof(buf), "%llX", static_cast<unsigned long long>(lo));
        }
        outNormHex = buf;

#if defined(__aarch64__)
        if (name.size() < 2 || (name[0] != 'v' && name[0] != 'q' && name[0] != 'd' && name[0] != 's')) {
            outErr = "不支持修改该浮点寄存器: " + regNameRaw;
            return false;
        }
        int idx = std::atoi(name.c_str() + 1);
        if (idx < 0 || idx >= 32) {
            outErr = "浮点寄存器索引越界: " + regNameRaw;
            return false;
        }
        struct user_fpsimd_state fpRegs{};
        struct iovec fpIov{&fpRegs, sizeof(fpRegs)};
        if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRFPREG)), &fpIov) < 0) {
            outErr = "读取线程浮点寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        __uint128_t v128 = (static_cast<__uint128_t>(hi) << 64) | static_cast<__uint128_t>(lo);
        fpRegs.vregs[idx] = v128;
        struct iovec setFpIov{&fpRegs, sizeof(fpRegs)};
        if (::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRFPREG)), &setFpIov) < 0) {
            outErr = "写回浮点寄存器失败 (errno=" + std::to_string(errno) + ")";
            return false;
        }
        return true;
#else
        (void)tid;
        outErr = "当前架构不支持浮点寄存器写回";
        return false;
#endif
    }
}

} // namespace

struct BreakpointManager::Session {
    int32_t id = 0;
    int32_t pid = 0;
    uint64_t address = 0;
    BpType type = BpType::RW;
    int32_t size = 4;
    BpMode mode = BpMode::PERF;
    bool captureCallstack = true;

    std::atomic<bool> stopFlag{false};
    std::atomic<bool> pausedFlag{false};
    std::atomic<bool> runningFlag{false};

    struct PendingRegWrite {
        int32_t tid = 0;
        std::string regName;
        std::string hexValue;
        bool isFp = false;
        bool done = false;
        bool ok = false;
        uint64_t numericVal = 0;
        std::string normHex;
        std::string error;
    };

    mutable std::mutex dataMtx;
    std::mutex regEditMtx;
    std::vector<std::shared_ptr<PendingRegWrite>> pendingRegWrites;
    int32_t attachedThreads = 0;
    int32_t totalThreads = 0;
    uint64_t totalHits = 0;
    int32_t tracerPid = 0;
    std::vector<int32_t> sampleTids;
    std::string lastError;
    std::unordered_map<uint64_t, BpHitGroup> hitMap;
    static constexpr size_t kMaxTraceRing = 600;
    std::deque<BpTraceEvent> traceRing;
    uint64_t nextTraceSeq = 1;

    std::thread worker;

    void recordHit(
        uint64_t pc,
        int32_t tid,
        int32_t hitType,
        const std::vector<VmaRegion>& maps,
        ProcIO& io,
        uint64_t sp,
        uint64_t lr,
        std::vector<RegEntry>&& gprs,
        std::vector<FpRegEntry>&& fprs,
        std::vector<CallFrameEntry>&& callstack
    ) {
        std::vector<uint8_t> insnBuf(16, 0);
        size_t got = 0;
        if (pc != 0) {
            io.readMemory(static_cast<uintptr_t>(pc), insnBuf.data(), insnBuf.size(), &got);
            if (got < insnBuf.size()) insnBuf.resize(got);
        } else {
            insnBuf.clear();
        }

        int32_t resolvedHitType = classifyInsnAccessType(insnBuf, hitType);

        std::string mod;
        uint64_t off = 0;
        resolveAddressModule(maps, pc, mod, off);

        const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        const std::string archStr = hostArchName();

        std::lock_guard<std::mutex> lk(dataMtx);
        totalHits++;

        // 实时寄存器轨迹环形缓冲（类 Frida Interceptor 逐次命中流式记录）
        BpTraceEvent ev;
        ev.seq = nextTraceSeq++;
        ev.timestampMs = nowMs;
        ev.bpId = id;
        ev.tid = tid;
        ev.hitType = resolvedHitType;
        ev.pc = pc;
        ev.sp = sp;
        ev.lr = lr;
        ev.arch = archStr;
        ev.moduleName = mod;
        ev.moduleOffset = off;
        ev.insnBytes = insnBuf;
        ev.gprs = gprs;
        ev.fprs = fprs;
        if (traceRing.size() >= kMaxTraceRing) {
            traceRing.pop_front();
        }
        traceRing.push_back(std::move(ev));

        auto& g = hitMap[pc];
        g.pc = pc;
        g.hitType = resolvedHitType;
        g.tid = tid;
        g.arch = archStr;
        g.moduleName = mod;
        g.moduleOffset = off;
        if (!insnBuf.empty()) g.insnBytes = std::move(insnBuf);
        g.hitCount++;
        g.sp = sp;
        g.lr = lr;
        if (!gprs.empty()) g.gprs = std::move(gprs);
        if (!fprs.empty()) g.fprs = std::move(fprs);
        if (!callstack.empty()) g.callstack = std::move(callstack);
    }
};

BreakpointManager::~BreakpointManager() {
    stopAll();
}

int32_t BreakpointManager::addBreakpoint(
    int pid,
    uint64_t address,
    BpType type,
    int32_t sizeBytes,
    BpMode mode,
    bool captureCallstack,
    std::string* outError
) {
    if (pid <= 0 || address == 0) {
        if (outError) *outError = "无效的目标进程或断点地址";
        return -1;
    }
    int32_t validSize = (sizeBytes == 1 || sizeBytes == 2 || sizeBytes == 4 || sizeBytes == 8) ? sizeBytes : 4;

    auto sess = std::make_shared<Session>();
    {
        std::lock_guard<std::mutex> lk(mtx_);
        sess->id = nextId_++;
    }
    sess->pid = pid;
    sess->address = address;
    sess->type = type;
    sess->size = validSize;
    sess->mode = mode;
    sess->captureCallstack = captureCallstack;
    sess->tracerPid = ::getpid();
    sess->runningFlag.store(true);

    if (mode == BpMode::PERF) {
        sess->worker = std::thread(perfWorkerLoop, sess);
    } else {
        sess->worker = std::thread(ptraceWorkerLoop, sess);
    }

    // Give worker a brief window to attach threads so immediate errors can be surfaced
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    {
        std::lock_guard<std::mutex> dlk(sess->dataMtx);
        if (!sess->lastError.empty() && sess->attachedThreads == 0 && !sess->runningFlag.load()) {
            if (outError) *outError = sess->lastError;
            if (sess->worker.joinable()) sess->worker.join();
            return -1;
        }
    }

    {
        std::lock_guard<std::mutex> lk(mtx_);
        sessions_.push_back(sess);
    }
    return sess->id;
}

bool BreakpointManager::removeBreakpoint(int32_t id) {
    std::shared_ptr<Session> target;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        auto it = std::find_if(sessions_.begin(), sessions_.end(), [&](const auto& s) { return s->id == id; });
        if (it == sessions_.end()) return false;
        target = *it;
        sessions_.erase(it);
    }
    target->stopFlag.store(true);
    if (target->worker.joinable()) {
        target->worker.join();
    }
    return true;
}

bool BreakpointManager::setPaused(int32_t id, bool paused) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        if (s->id == id) {
            s->pausedFlag.store(paused);
            return true;
        }
    }
    return false;
}

bool BreakpointManager::setAllPaused(bool paused) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        s->pausedFlag.store(paused);
    }
    return true;
}

bool BreakpointManager::clearHits(int32_t id) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        if (s->id == id) {
            std::lock_guard<std::mutex> dlk(s->dataMtx);
            s->hitMap.clear();
            s->traceRing.clear();
            s->totalHits = 0;
            return true;
        }
    }
    return false;
}

void BreakpointManager::clearAllHits() {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        std::lock_guard<std::mutex> dlk(s->dataMtx);
        s->hitMap.clear();
        s->traceRing.clear();
        s->totalHits = 0;
    }
}

void BreakpointManager::stopAll() {
    std::vector<std::shared_ptr<Session>> copy;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        copy.swap(sessions_);
    }
    for (auto& s : copy) {
        s->stopFlag.store(true);
    }
    for (auto& s : copy) {
        if (s->worker.joinable()) s->worker.join();
    }
}

bool BreakpointManager::modifyRegister(
    int32_t bpId,
    int32_t tid,
    uint64_t hitPc,
    const std::string& regName,
    const std::string& hexValue,
    bool isFp,
    std::string* outError
) {
    std::shared_ptr<Session> target;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        for (auto& s : sessions_) {
            if (s->id == bpId) {
                target = s;
                break;
            }
        }
    }
    if (!target) {
        if (outError) *outError = "未找到对应的断点会话";
        return false;
    }

    int targetTid = tid > 0 ? tid : target->pid;
    uint64_t numericVal = 0;
    std::string normHex;
    std::string errMsg;
    bool ok = false;

    if (target->mode == BpMode::PTRACE && target->runningFlag.load()) {
        auto req = std::make_shared<Session::PendingRegWrite>();
        req->tid = targetTid;
        req->regName = regName;
        req->hexValue = hexValue;
        req->isFp = isFp;
        {
            std::lock_guard<std::mutex> rlk(target->regEditMtx);
            target->pendingRegWrites.push_back(req);
        }
        auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1200);
        while (std::chrono::steady_clock::now() < deadline) {
            {
                std::lock_guard<std::mutex> rlk(target->regEditMtx);
                if (req->done) {
                    ok = req->ok;
                    numericVal = req->numericVal;
                    normHex = req->normHex;
                    errMsg = req->error;
                    break;
                }
            }
            if (!target->runningFlag.load()) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        if (!ok && errMsg.empty()) {
            errMsg = "等待调试线程写回寄存器超时";
        }
    } else {
        // Perf mode or stopped session: perform brief signal-safe ptrace seize/attach -> write reg -> detach
        bool seized = false;
        bool attached = false;
        if (::ptrace(PTRACE_SEIZE, targetTid, nullptr, nullptr) == 0) {
            seized = true;
            attached = (::ptrace(PTRACE_INTERRUPT, targetTid, nullptr, nullptr) == 0);
            if (!attached) {
                ::ptrace(PTRACE_DETACH, targetTid, nullptr, nullptr);
            }
        }
        if (!attached && ::ptrace(PTRACE_ATTACH, targetTid, nullptr, nullptr) == 0) {
            attached = true;
        }
        if (!attached) {
            errMsg = "ptrace 附加目标线程失败 (errno=" + std::to_string(errno) + ")";
        } else {
            bool exited = false;
            int pendingSig = 0;
            if (waitForTidStopBounded(targetTid, seized, 150, exited, pendingSig)) {
                ok = writeRegToStoppedTid(targetTid, regName, hexValue, isFp, numericVal, normHex, errMsg);
            } else {
                errMsg = "等待目标线程暂停失败";
            }
            if (!exited) {
                ::ptrace(PTRACE_DETACH, targetTid, nullptr, reinterpret_cast<void*>(static_cast<uintptr_t>(pendingSig)));
            }
        }
    }

    if (!ok) {
        if (outError) *outError = errMsg.empty() ? "寄存器修改失败" : errMsg;
        return false;
    }

    // Update cached hit snapshot immediately so UI reflects the edited register value
    {
        std::lock_guard<std::mutex> dlk(target->dataMtx);
        std::string lowerTarget = toLowerCopy(regName);
        auto it = target->hitMap.find(hitPc);
        if (it != target->hitMap.end()) {
            auto& grp = it->second;
            if (!isFp) {
                for (auto& r : grp.gprs) {
                    if (toLowerCopy(r.name) == lowerTarget) {
                        r.value = numericVal;
                    }
                }
                if (lowerTarget == "sp") grp.sp = numericVal;
                if (lowerTarget == "lr") grp.lr = numericVal;
            } else {
                for (auto& fr : grp.fprs) {
                    if (toLowerCopy(fr.name) == lowerTarget) {
                        fr.hexValue = normHex;
                    }
                }
            }
        }
    }
    return true;
}

std::vector<BpSessionSnapshot> BreakpointManager::snapshots() const {
    std::vector<std::shared_ptr<Session>> list;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        list = sessions_;
    }
    std::vector<BpSessionSnapshot> out;
    out.reserve(list.size());
    for (const auto& s : list) {
        BpSessionSnapshot snap;
        snap.id = s->id;
        snap.pid = s->pid;
        snap.address = s->address;
        snap.type = static_cast<int32_t>(s->type);
        snap.size = s->size;
        snap.mode = static_cast<int32_t>(s->mode);
        snap.captureCallstack = s->captureCallstack;
        snap.running = s->runningFlag.load();
        snap.paused = s->pausedFlag.load();
        {
            std::lock_guard<std::mutex> dlk(s->dataMtx);
            snap.attachedThreads = s->attachedThreads;
            snap.totalThreads = s->totalThreads;
            snap.totalHits = s->totalHits;
            snap.tracerPid = s->tracerPid;
            snap.sampleTids = s->sampleTids;
            snap.lastError = s->lastError;
            snap.hits.reserve(s->hitMap.size());
            for (const auto& kv : s->hitMap) {
                snap.hits.push_back(kv.second);
            }
        }
        std::sort(snap.hits.begin(), snap.hits.end(), [](const BpHitGroup& a, const BpHitGroup& b) {
            return a.hitCount > b.hitCount;
        });
        out.push_back(std::move(snap));
    }
    return out;
}

std::string BreakpointManager::snapshotsToJson() const {
    auto list = snapshots();
    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < list.size(); ++i) {
        const auto& s = list[i];
        if (i > 0) oss << ",";
        oss << "{"
            << "\"id\":" << s.id
            << ",\"pid\":" << s.pid
            << ",\"address\":" << static_cast<int64_t>(s.address)
            << ",\"type\":" << s.type
            << ",\"size\":" << s.size
            << ",\"mode\":" << s.mode
            << ",\"captureCallstack\":" << (s.captureCallstack ? "true" : "false")
            << ",\"running\":" << (s.running ? "true" : "false")
            << ",\"paused\":" << (s.paused ? "true" : "false")
            << ",\"attachedThreads\":" << s.attachedThreads
            << ",\"totalThreads\":" << s.totalThreads
            << ",\"totalHits\":" << s.totalHits
            << ",\"tracerPid\":" << s.tracerPid
            << ",\"sampleTids\":[";
        for (size_t t = 0; t < s.sampleTids.size(); ++t) {
            if (t > 0) oss << ",";
            oss << s.sampleTids[t];
        }
        oss << "],\"lastError\":\"" << jsonEscape(s.lastError) << "\",\"hits\":[";
        for (size_t h = 0; h < s.hits.size(); ++h) {
            const auto& g = s.hits[h];
            if (h > 0) oss << ",";
            oss << "{"
                << "\"pc\":" << static_cast<int64_t>(g.pc)
                << ",\"hitType\":" << g.hitType
                << ",\"tid\":" << g.tid
                << ",\"arch\":\"" << jsonEscape(g.arch) << "\""
                << ",\"module\":\"" << jsonEscape(g.moduleName) << "\""
                << ",\"offset\":" << static_cast<int64_t>(g.moduleOffset)
                << ",\"insnHex\":\"" << bytesToHexLower(g.insnBytes) << "\""
                << ",\"count\":" << g.hitCount
                << ",\"sp\":" << static_cast<int64_t>(g.sp)
                << ",\"lr\":" << static_cast<int64_t>(g.lr)
                << ",\"gprs\":[";
            for (size_t r = 0; r < g.gprs.size(); ++r) {
                if (r > 0) oss << ",";
                oss << "{\"n\":\"" << jsonEscape(g.gprs[r].name) << "\",\"v\":" << static_cast<int64_t>(g.gprs[r].value) << "}";
            }
            oss << "],\"fprs\":[";
            for (size_t r = 0; r < g.fprs.size(); ++r) {
                if (r > 0) oss << ",";
                oss << "{\"n\":\"" << jsonEscape(g.fprs[r].name) << "\",\"v\":\"" << jsonEscape(g.fprs[r].hexValue) << "\"}";
            }
            oss << "],\"callstack\":[";
            for (size_t c = 0; c < g.callstack.size(); ++c) {
                if (c > 0) oss << ",";
                oss << "{\"addr\":" << static_cast<int64_t>(g.callstack[c].address)
                    << ",\"mod\":\"" << jsonEscape(g.callstack[c].moduleName) << "\""
                    << ",\"off\":" << static_cast<int64_t>(g.callstack[c].moduleOffset) << "}";
            }
            oss << "]}";
        }
        oss << "]}";
    }
    oss << "]";
    return oss.str();
}

void BreakpointManager::clearTrace(int32_t bpId) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        if (bpId <= 0 || s->id == bpId) {
            std::lock_guard<std::mutex> dlk(s->dataMtx);
            s->traceRing.clear();
        }
    }
}

std::string BreakpointManager::traceEventsToJson(int32_t bpId, uint64_t sinceSeq, size_t maxEvents) const {
    std::vector<std::shared_ptr<Session>> list;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        list = sessions_;
    }
    const size_t cap = (maxEvents == 0 || maxEvents > 600) ? 120 : maxEvents;
    std::vector<BpTraceEvent> events;
    events.reserve(cap);
    for (const auto& s : list) {
        if (bpId > 0 && s->id != bpId) continue;
        std::lock_guard<std::mutex> dlk(s->dataMtx);
        for (const auto& ev : s->traceRing) {
            if (ev.seq > sinceSeq) {
                events.push_back(ev);
            }
        }
    }
    if (bpId <= 0 && events.size() > 1) {
        std::sort(events.begin(), events.end(), [](const BpTraceEvent& a, const BpTraceEvent& b) {
            if (a.timestampMs != b.timestampMs) return a.timestampMs < b.timestampMs;
            return a.seq < b.seq;
        });
    }
    if (events.size() > cap) {
        events.erase(events.begin(), events.begin() + static_cast<std::ptrdiff_t>(events.size() - cap));
    }

    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < events.size(); ++i) {
        const auto& ev = events[i];
        if (i > 0) oss << ",";
        oss << "{"
            << "\"seq\":" << ev.seq
            << ",\"ts\":" << ev.timestampMs
            << ",\"bpId\":" << ev.bpId
            << ",\"tid\":" << ev.tid
            << ",\"hitType\":" << ev.hitType
            << ",\"pc\":" << static_cast<int64_t>(ev.pc)
            << ",\"sp\":" << static_cast<int64_t>(ev.sp)
            << ",\"lr\":" << static_cast<int64_t>(ev.lr)
            << ",\"arch\":\"" << jsonEscape(ev.arch) << "\""
            << ",\"module\":\"" << jsonEscape(ev.moduleName) << "\""
            << ",\"offset\":" << static_cast<int64_t>(ev.moduleOffset)
            << ",\"insnHex\":\"" << bytesToHexLower(ev.insnBytes) << "\""
            << ",\"gprs\":[";
        for (size_t r = 0; r < ev.gprs.size(); ++r) {
            if (r > 0) oss << ",";
            oss << "{\"n\":\"" << jsonEscape(ev.gprs[r].name) << "\",\"v\":" << static_cast<int64_t>(ev.gprs[r].value) << "}";
        }
        oss << "],\"fprs\":[";
        for (size_t r = 0; r < ev.fprs.size(); ++r) {
            if (r > 0) oss << ",";
            oss << "{\"n\":\"" << jsonEscape(ev.fprs[r].name) << "\",\"v\":\"" << jsonEscape(ev.fprs[r].hexValue) << "\"}";
        }
        oss << "]}";
    }
    oss << "]";
    return oss.str();
}

// ============================================================================
// Mode 1: Linux perf_event_open Hardware Breakpoint Worker
// ============================================================================

void BreakpointManager::perfWorkerLoop(std::shared_ptr<Session> sess) {
    ProcIO io;
    io.attach(sess->pid);
    auto maps = MapsParser::parseProcessMaps(sess->pid, 0, false);

    const long pageSize = ::sysconf(_SC_PAGESIZE);
    const size_t dataPages = 4;
    const size_t mmapSize = static_cast<size_t>(pageSize) * (1 + dataPages);
    const size_t dataSize = static_cast<size_t>(pageSize) * dataPages;

    struct PerfThreadSlot {
        int tid = 0;
        int fd = -1;
        void* mmapBase = nullptr;
        uint64_t sampleType = 0;
        uint64_t regsMask = 0;
    };

    std::vector<PerfThreadSlot> slots;
    std::unordered_map<int, bool> knownTids;
    bool wasPaused = false;

    auto openForTid = [&](int tid) -> bool {
        struct perf_event_attr attr{};
        attr.size = sizeof(attr);
        attr.type = PERF_TYPE_BREAKPOINT;
        attr.bp_type = toPerfBpType(sess->type);
        attr.bp_addr = sess->address;
        attr.bp_len = toPerfBpLen(sess->size, sess->type);
        attr.sample_period = 1;
        attr.wakeup_events = 1;
        attr.exclude_kernel = 1;
        attr.exclude_hv = 1;
        attr.disabled = sess->pausedFlag.load() ? 1 : 0;

        uint64_t regsMask = 0;
#if defined(__aarch64__)
        regsMask = (1ULL << 33) - 1ULL; // x0..x29, lr, sp, pc
#elif defined(__arm__)
        regsMask = (1ULL << 16) - 1ULL;
#elif defined(__x86_64__)
        regsMask = 0xFFFFFFULL;
#elif defined(__i386__)
        regsMask = 0xFFFFULL;
#endif

        // Progressive fallback for kernel sample capabilities
        const uint64_t configs[3] = {
            PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR | PERF_SAMPLE_REGS_USER |
                (sess->captureCallstack ? PERF_SAMPLE_CALLCHAIN : 0ULL),
            PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR | PERF_SAMPLE_REGS_USER,
            PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR
        };

        int fd = -1;
        uint64_t usedSampleType = 0;
        uint64_t usedRegsMask = 0;
        for (int attempt = 0; attempt < 3; ++attempt) {
            attr.sample_type = configs[attempt];
            attr.sample_regs_user = (configs[attempt] & PERF_SAMPLE_REGS_USER) ? regsMask : 0;
            fd = static_cast<int>(::syscall(__NR_perf_event_open, &attr, tid, -1, -1, PERF_FLAG_FD_CLOEXEC));
            if (fd >= 0) {
                usedSampleType = configs[attempt];
                usedRegsMask = attr.sample_regs_user;
                break;
            }
        }
        if (fd < 0) return false;

        void* base = ::mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (base == MAP_FAILED) {
            ::close(fd);
            return false;
        }
        ::ioctl(fd, PERF_EVENT_IOC_RESET, 0);
        if (!sess->pausedFlag.load()) {
            ::ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
        }
        slots.push_back({tid, fd, base, usedSampleType, usedRegsMask});
        return true;
    };

    auto scanAndAttachThreads = [&]() {
        auto tids = listProcessThreads(sess->pid);
        int attached = static_cast<int>(slots.size());
        for (int tid : tids) {
            if (knownTids.find(tid) == knownTids.end()) {
                knownTids[tid] = true;
                if (openForTid(tid)) {
                    attached++;
                }
            }
        }
        std::lock_guard<std::mutex> dlk(sess->dataMtx);
        sess->totalThreads = static_cast<int32_t>(tids.size());
        sess->attachedThreads = attached;
        sess->sampleTids.clear();
        for (size_t i = 0; i < std::min<size_t>(tids.size(), 4); ++i) {
            sess->sampleTids.push_back(tids[i]);
        }
        if (attached == 0 && !tids.empty()) {
            sess->lastError = "perf_event_open 附加失败 (errno=" + std::to_string(errno) + "，内核可能限制了 perf_event，请切换为 Ptrace 模式)";
        } else if (attached > 0) {
            sess->lastError.clear();
        }
    };

    scanAndAttachThreads();
    if (slots.empty()) {
        sess->runningFlag.store(false);
        return;
    }

    std::vector<uint8_t> recordBuf(4096);
    std::unordered_map<uint64_t, std::chrono::steady_clock::time_point> lastFpSampleByPc;
    auto lastRescan = std::chrono::steady_clock::now();

    while (!sess->stopFlag.load()) {
        bool nowPaused = sess->pausedFlag.load();
        if (nowPaused != wasPaused) {
            wasPaused = nowPaused;
            for (auto& sl : slots) {
                ::ioctl(sl.fd, nowPaused ? PERF_EVENT_IOC_DISABLE : PERF_EVENT_IOC_ENABLE, 0);
            }
        }

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastRescan).count() >= 1000) {
            lastRescan = now;
            scanAndAttachThreads();
            maps = MapsParser::parseProcessMaps(sess->pid, 0, false);
        }

        std::vector<struct pollfd> pfds(slots.size());
        for (size_t i = 0; i < slots.size(); ++i) {
            pfds[i].fd = slots[i].fd;
            pfds[i].events = POLLIN;
            pfds[i].revents = 0;
        }

        int ready = ::poll(pfds.data(), static_cast<nfds_t>(pfds.size()), 40);
        if (ready <= 0) continue;

        for (size_t i = 0; i < slots.size(); ++i) {
            if (!(pfds[i].revents & POLLIN)) continue;
            auto& sl = slots[i];
            auto* meta = reinterpret_cast<struct perf_event_mmap_page*>(sl.mmapBase);
            uint8_t* ring = reinterpret_cast<uint8_t*>(sl.mmapBase) + pageSize;

            uint64_t head = __atomic_load_n(&meta->data_head, __ATOMIC_ACQUIRE);
            uint64_t tail = meta->data_tail;

            while (tail < head) {
                struct perf_event_header hdr{};
                for (size_t b = 0; b < sizeof(hdr); ++b) {
                    reinterpret_cast<uint8_t*>(&hdr)[b] = ring[(tail + b) % dataSize];
                }
                if (hdr.size < sizeof(hdr) || hdr.size > dataSize) {
                    tail = head;
                    break;
                }
                if (hdr.type == PERF_RECORD_SAMPLE && !nowPaused) {
                    if (recordBuf.size() < hdr.size) recordBuf.resize(hdr.size);
                    for (size_t b = 0; b < hdr.size; ++b) {
                        recordBuf[b] = ring[(tail + b) % dataSize];
                    }

                    size_t off = sizeof(hdr);
                    auto readU64 = [&](uint64_t& v) -> bool {
                        if (off + 8 > hdr.size) return false;
                        std::memcpy(&v, recordBuf.data() + off, 8);
                        off += 8;
                        return true;
                    };

                    uint64_t ip = 0;
                    int32_t sampleTid = sl.tid;
                    uint64_t sp = 0, lr = 0, fp = 0;
                    std::vector<RegEntry> gprs;
                    std::vector<FpRegEntry> fprs;
                    std::vector<CallFrameEntry> callstack;

                    if (sl.sampleType & PERF_SAMPLE_IP) {
                        readU64(ip);
                    }
                    if (sl.sampleType & PERF_SAMPLE_TID) {
                        if (off + 8 <= hdr.size) {
                            uint32_t p = 0, t = 0;
                            std::memcpy(&p, recordBuf.data() + off, 4);
                            std::memcpy(&t, recordBuf.data() + off + 4, 4);
                            if (t > 0) sampleTid = static_cast<int32_t>(t);
                            off += 8;
                        }
                    }
                    if (sl.sampleType & PERF_SAMPLE_ADDR) {
                        uint64_t dummyAddr = 0;
                        readU64(dummyAddr);
                    }
                    if (sl.sampleType & PERF_SAMPLE_CALLCHAIN) {
                        uint64_t nr = 0;
                        if (readU64(nr)) {
                            for (uint64_t c = 0; c < nr && c < 32; ++c) {
                                uint64_t frameAddr = 0;
                                if (!readU64(frameAddr)) break;
                                // Skip kernel context markers (PERF_CONTEXT_*)
                                if (frameAddr >= 0xFFFFFFFFFFFFF000ULL) continue;
                                CallFrameEntry f;
                                f.address = frameAddr;
                                resolveAddressModule(maps, frameAddr, f.moduleName, f.moduleOffset);
                                callstack.push_back(std::move(f));
                            }
                        }
                    }
                    if (sl.sampleType & PERF_SAMPLE_REGS_USER) {
                        uint64_t abi = 0;
                        if (readU64(abi) && abi != 0) {
                            std::vector<uint64_t> rawRegs;
                            for (int bit = 0; bit < 64; ++bit) {
                                if (sl.regsMask & (1ULL << bit)) {
                                    uint64_t rv = 0;
                                    if (!readU64(rv)) break;
                                    rawRegs.push_back(rv);
                                }
                            }
#if defined(__aarch64__)
                            for (size_t r = 0; r < rawRegs.size(); ++r) {
                                if (r <= 28) gprs.push_back({"x" + std::to_string(r), rawRegs[r]});
                                else if (r == 29) { fp = rawRegs[r]; gprs.push_back({"fp", fp}); }
                                else if (r == 30) { lr = rawRegs[r]; gprs.push_back({"lr", lr}); }
                                else if (r == 31) { sp = rawRegs[r]; gprs.push_back({"sp", sp}); }
                                else if (r == 32) { if (ip == 0) ip = rawRegs[r]; gprs.push_back({"pc", rawRegs[r]}); }
                            }
#elif defined(__arm__)
                            for (size_t r = 0; r < rawRegs.size() && r < 16; ++r) {
                                if (r == 11) fp = rawRegs[r];
                                if (r == 13) sp = rawRegs[r];
                                if (r == 14) lr = rawRegs[r];
                                if (r == 15 && ip == 0) ip = rawRegs[r];
                                gprs.push_back({"r" + std::to_string(r), rawRegs[r]});
                            }
#else
                            for (size_t r = 0; r < rawRegs.size(); ++r) {
                                gprs.push_back({"reg" + std::to_string(r), rawRegs[r]});
                            }
#endif
                        }
                    }

                    if (sess->captureCallstack && callstack.empty()) {
                        callstack = unwindFpChain(io, maps, ip, lr, fp);
                    }

                    // Linux PERF_SAMPLE_REGS_USER omits ARM/x86 FP/SIMD registers; capture FP snapshot
                    // on first hit per PC and refresh at most every 250ms via signal-safe PTRACE_SEIZE.
                    {
                        auto sampleNow = std::chrono::steady_clock::now();
                        auto itFp = lastFpSampleByPc.find(ip);
                        bool shouldSampleFp = (itFp == lastFpSampleByPc.end()) ||
                            (std::chrono::duration_cast<std::chrono::milliseconds>(sampleNow - itFp->second).count() >= 250);
                        if (shouldSampleFp && sampleTid > 0) {
                            lastFpSampleByPc[ip] = sampleNow;
                            sampleFpRegsBrief(sess->pid, sampleTid, fprs);
                        }
                    }

                    sess->recordHit(
                        ip,
                        sampleTid,
                        static_cast<int32_t>(sess->type),
                        maps,
                        io,
                        sp,
                        lr,
                        std::move(gprs),
                        std::move(fprs),
                        std::move(callstack)
                    );
                }
                tail += hdr.size;
            }
            __atomic_store_n(&meta->data_tail, tail, __ATOMIC_RELEASE);
        }
    }

    for (auto& sl : slots) {
        if (sl.fd >= 0) {
            ::ioctl(sl.fd, PERF_EVENT_IOC_DISABLE, 0);
            if (sl.mmapBase) ::munmap(sl.mmapBase, mmapSize);
            ::close(sl.fd);
        }
    }
    sess->runningFlag.store(false);
}

// ============================================================================
// Mode 2: Linux ptrace Hardware Breakpoint / Watchpoint Worker
// (Inspired by YuPtrace.lua, upgraded to full multi-thread R/W/RW/X + GPR/FPR + unwind)
// ============================================================================

void BreakpointManager::ptraceWorkerLoop(std::shared_ptr<Session> sess) {
    ProcIO io;
    io.attach(sess->pid);
    auto maps = MapsParser::parseProcessMaps(sess->pid, 0, false);

    struct TracedThread {
        int tid = 0;
        bool seized = false;        // Attached with PTRACE_SEIZE (no stray SIGSTOP on detach)
        bool stopped = false;       // Currently in ptrace-stop awaiting CONT/SINGLESTEP/DETACH
        bool stepping = false;      // Executing PTRACE_SINGLESTEP past a trapped instruction
        bool hwArmed = false;       // Hardware bp/wp currently armed on this thread
        int pendingSig = 0;         // Real app signal preserved for transparent forwarding
        bool dead = false;
    };

    std::unordered_map<int, TracedThread> threads;
    bool wasPaused = sess->pausedFlag.load();

    auto installHw = [&](int tid, bool enable) -> bool {
#if defined(__aarch64__)
        return installArm64HwBp(tid, sess->address, sess->type, sess->size, enable, 0);
#elif defined(__arm__)
        return installArm32HwBp(tid, sess->address, sess->type, sess->size, enable);
#elif defined(__x86_64__) || defined(__i386__)
        return installX86HwBp(tid, sess->address, sess->type, sess->size, enable);
#else
        (void)tid; (void)enable;
        return false;
#endif
    };

    auto resumeThread = [&](TracedThread& t, bool singleStep) {
        if (t.dead || !t.stopped) return;
        int fwdSig = t.pendingSig;
        t.pendingSig = 0;
        void* sigArg = reinterpret_cast<void*>(static_cast<uintptr_t>(fwdSig));
        if (singleStep) {
            if (::ptrace(PTRACE_SINGLESTEP, t.tid, nullptr, sigArg) == 0) {
                t.stopped = false;
                t.stepping = true;
                return;
            }
            t.stepping = false;
        }
        if (::ptrace(PTRACE_CONT, t.tid, nullptr, sigArg) < 0 && errno == ESRCH) {
            t.dead = true;
        }
        t.stopped = false;
    };

    auto stopThreadForControl = [&](TracedThread& t, int timeoutMs) -> bool {
        if (t.dead) return false;
        if (t.stopped) return true;

        // First drain any already-pending stop for this exact tid before requesting a new stop
        {
            int st = 0;
            pid_t r = ::waitpid(t.tid, &st, __WALL | WNOHANG);
            if (r == t.tid) {
                if (WIFEXITED(st) || WIFSIGNALED(st)) {
                    t.dead = true;
                    return false;
                }
                if (WIFSTOPPED(st)) {
                    t.stopped = true;
                    int sig = WSTOPSIG(st);
                    unsigned ev = (static_cast<unsigned>(st) >> 16) & 0xFFFFu;
                    if (sig == SIGTRAP) {
                        t.stepping = false;
                    } else if (ev != PTRACE_EVENT_STOP && !(!t.seized && sig == SIGSTOP)) {
                        t.pendingSig = sig;
                    }
                    return true;
                }
            } else if (r < 0 && errno == ECHILD) {
                t.dead = true;
                return false;
            }
        }

        // Request a thread-directed stop: PTRACE_INTERRUPT (preferred) or tgkill(pid, tid, SIGSTOP)
        if (t.seized) {
            if (::ptrace(PTRACE_INTERRUPT, t.tid, nullptr, nullptr) < 0) {
                if (errno == ESRCH) { t.dead = true; return false; }
            }
        } else {
            if (sendThreadSignal(sess->pid, t.tid, SIGSTOP) < 0) {
                if (errno == ESRCH) { t.dead = true; return false; }
            }
        }

        bool exited = false;
        int sigOut = 0;
        if (waitForTidStopBounded(t.tid, t.seized, timeoutMs, exited, sigOut)) {
            t.stopped = true;
            if (sigOut != 0 && t.pendingSig == 0) {
                t.pendingSig = sigOut;
            }
            return true;
        }
        if (exited) t.dead = true;
        return false;
    };

    auto updateSessionThreadStats = [&](const std::vector<int>& allTids) {
        int aliveCount = 0;
        for (const auto& kv : threads) {
            if (!kv.second.dead) aliveCount++;
        }
        std::lock_guard<std::mutex> dlk(sess->dataMtx);
        sess->totalThreads = static_cast<int32_t>(allTids.size());
        sess->attachedThreads = aliveCount;
        sess->sampleTids.clear();
        for (size_t i = 0; i < std::min<size_t>(allTids.size(), 4); ++i) {
            sess->sampleTids.push_back(allTids[i]);
        }
        if (aliveCount == 0 && !allTids.empty()) {
            sess->lastError = "ptrace 附加失败 (errno=" + std::to_string(errno) + "，目标进程可能已被其他调试器占用或无同 UID/Root 权限)";
        } else if (aliveCount > 0) {
            sess->lastError.clear();
        }
    };

    auto attachNewThreads = [&]() {
        auto tids = listProcessThreads(sess->pid);
        bool currentlyPaused = sess->pausedFlag.load();
        for (int tid : tids) {
            if (threads.find(tid) != threads.end()) continue;
            if (tid == ::getpid() || tid == ::gettid()) continue;

            TracedThread t;
            t.tid = tid;
            bool attached = false;
            // Prefer PTRACE_SEIZE + PTRACE_INTERRUPT so we never inject user-visible SIGSTOP
            if (::ptrace(PTRACE_SEIZE, tid, nullptr, nullptr) == 0) {
                t.seized = true;
                attached = (::ptrace(PTRACE_INTERRUPT, tid, nullptr, nullptr) == 0);
                if (!attached) {
                    ::ptrace(PTRACE_DETACH, tid, nullptr, nullptr);
                }
            }
            if (!attached && ::ptrace(PTRACE_ATTACH, tid, nullptr, nullptr) == 0) {
                t.seized = false;
                attached = true;
            }
            if (!attached) continue;

            bool exited = false;
            int pendingSig = 0;
            if (waitForTidStopBounded(tid, t.seized, 120, exited, pendingSig)) {
                t.stopped = true;
                t.pendingSig = pendingSig;
                if (!currentlyPaused) {
                    t.hwArmed = installHw(tid, true);
                } else {
                    installHw(tid, false);
                    t.hwArmed = false;
                }
                resumeThread(t, false);
                if (!t.dead) {
                    threads.emplace(tid, t);
                }
            } else if (!exited) {
                ::ptrace(PTRACE_DETACH, tid, nullptr, nullptr);
            }
        }
        for (auto it = threads.begin(); it != threads.end(); ) {
            if (it->second.dead) it = threads.erase(it);
            else ++it;
        }
        updateSessionThreadStats(tids);
    };

    auto handleStoppedEvent = [&](int tid, int status, bool nowPaused) {
        auto it = threads.find(tid);
        if (it == threads.end()) {
            if (WIFSTOPPED(status)) {
                int s = WSTOPSIG(status);
                int fwd = (s == SIGSTOP || s == SIGTRAP) ? 0 : s;
                ::ptrace(PTRACE_CONT, tid, nullptr, reinterpret_cast<void*>(static_cast<uintptr_t>(fwd)));
            }
            return;
        }
        TracedThread& t = it->second;

        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            t.dead = true;
            threads.erase(it);
            return;
        }
        if (!WIFSTOPPED(status)) return;

        t.stopped = true;
        int sig = WSTOPSIG(status);
        unsigned ev = (static_cast<unsigned>(status) >> 16) & 0xFFFFu;

        // 1. Control stop from PTRACE_INTERRUPT or PTRACE_ATTACH
        if (ev == PTRACE_EVENT_STOP || (!t.seized && sig == SIGSTOP)) {
            if (nowPaused && t.hwArmed) {
                installHw(t.tid, false);
                t.hwArmed = false;
                t.stepping = false;
            }
            resumeThread(t, false);
            return;
        }

        // 2. SIGTRAP: completing single-step, hardware bp/wp hit, or app-raised SIGTRAP
        if (sig == SIGTRAP) {
            if (t.stepping) {
                t.stepping = false;
                if (!nowPaused) {
                    t.hwArmed = installHw(t.tid, true);
                } else if (t.hwArmed) {
                    installHw(t.tid, false);
                    t.hwArmed = false;
                }
                resumeThread(t, false);
                return;
            }

            // Distinguish debugger hardware trap from app-raised SIGTRAP (SI_USER / SI_TKILL)
            siginfo_t si{};
            bool isAppSigtrap = false;
            if (::ptrace(PTRACE_GETSIGINFO, t.tid, nullptr, &si) == 0) {
                if (si.si_code == SI_USER || si.si_code == SI_TKILL || si.si_code == SI_QUEUE) {
                    isAppSigtrap = true;
                }
            }
            if (isAppSigtrap) {
                t.pendingSig = SIGTRAP;
                resumeThread(t, false);
                return;
            }

            // Hardware breakpoint / watchpoint hit
            if (!nowPaused) {
                uint64_t pc = 0, sp = 0, lr = 0, fp = 0;
                std::vector<RegEntry> gprs;
                std::vector<FpRegEntry> fprs;
                bool okRegs = false;
#if defined(__aarch64__)
                okRegs = captureArm64Regs(t.tid, pc, sp, lr, fp, gprs, fprs);
#elif defined(__arm__)
                okRegs = captureArm32Regs(t.tid, pc, sp, lr, fp, gprs, fprs);
#elif defined(__x86_64__) || defined(__i386__)
                okRegs = captureX86Regs(t.tid, pc, sp, lr, fp, gprs, fprs);
#endif
                if (okRegs) {
                    std::vector<CallFrameEntry> callstack;
                    if (sess->captureCallstack) {
                        callstack = unwindFpChain(io, maps, pc, lr, fp);
                    }
                    sess->recordHit(
                        pc,
                        static_cast<int32_t>(t.tid),
                        static_cast<int32_t>(sess->type),
                        maps,
                        io,
                        sp,
                        lr,
                        std::move(gprs),
                        std::move(fprs),
                        std::move(callstack)
                    );
                }
            }

            // Disarm before stepping past the trapped instruction
            installHw(t.tid, false);
            t.hwArmed = false;
            if (nowPaused) {
                t.stepping = false;
                resumeThread(t, false);
            } else {
#if defined(__aarch64__) || defined(__x86_64__) || defined(__i386__)
                resumeThread(t, true); // PTRACE_SINGLESTEP -> re-arm on next SIGTRAP
#else
                resumeThread(t, false);
#endif
            }
            return;
        }

        // 3. Any other application signal (e.g. ART GC SIGSEGV/SIGUSR1/SIGRTMIN):
        //    ALWAYS forward transparently; if mid single-step, keep single-stepping!
        t.pendingSig = sig;
        resumeThread(t, t.stepping);
    };

    attachNewThreads();
    if (threads.empty()) {
        sess->runningFlag.store(false);
        return;
    }

    auto lastRescan = std::chrono::steady_clock::now();

    while (!sess->stopFlag.load() && !threads.empty()) {
        bool nowPaused = sess->pausedFlag.load();

        // Handle Pause / Resume cleanly without process-wide SIGSTOP or signal loss
        if (nowPaused != wasPaused) {
            wasPaused = nowPaused;

            // Drain any already-queued wait events first so stale traps don't collide
            while (true) {
                int st = 0;
                pid_t r = ::waitpid(-1, &st, __WALL | WNOHANG);
                if (r <= 0) break;
                handleStoppedEvent(static_cast<int>(r), st, nowPaused);
            }

            std::vector<int> tidsSnapshot;
            tidsSnapshot.reserve(threads.size());
            for (const auto& kv : threads) tidsSnapshot.push_back(kv.first);

            for (int tid : tidsSnapshot) {
                auto it = threads.find(tid);
                if (it == threads.end() || it->second.dead) continue;
                TracedThread& t = it->second;
                if (stopThreadForControl(t, 120)) {
                    t.stepping = false;
                    if (nowPaused) {
                        installHw(t.tid, false);
                        t.hwArmed = false;
                    } else {
                        t.hwArmed = installHw(t.tid, true);
                    }
                    resumeThread(t, false);
                }
            }

            for (auto it = threads.begin(); it != threads.end(); ) {
                if (it->second.dead) it = threads.erase(it);
                else ++it;
            }
        }

        // Process any pending register modification requests while holding ptrace ownership
        {
            std::vector<std::shared_ptr<Session::PendingRegWrite>> todo;
            {
                std::lock_guard<std::mutex> rlk(sess->regEditMtx);
                if (!sess->pendingRegWrites.empty()) {
                    todo.swap(sess->pendingRegWrites);
                }
            }
            for (auto& req : todo) {
                int targetTid = req->tid;
                auto it = threads.find(targetTid);
                if (it == threads.end() && !threads.empty()) {
                    it = threads.begin();
                    targetTid = it->first;
                }
                bool wrote = false;
                uint64_t numVal = 0;
                std::string normHex;
                std::string err;
                if (it != threads.end() && stopThreadForControl(it->second, 150)) {
                    wrote = writeRegToStoppedTid(targetTid, req->regName, req->hexValue, req->isFp, numVal, normHex, err);
                    resumeThread(it->second, it->second.stepping);
                } else {
                    err = "暂停目标线程以修改寄存器失败";
                }
                std::lock_guard<std::mutex> rlk(sess->regEditMtx);
                req->ok = wrote;
                req->numericVal = numVal;
                req->normHex = normHex;
                req->error = err;
                req->done = true;
            }
        }

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastRescan).count() >= 1500) {
            lastRescan = now;
            maps = MapsParser::parseProcessMaps(sess->pid, 0, false);
            attachNewThreads();
        }

        bool gotAny = false;
        while (!sess->stopFlag.load()) {
            int status = 0;
            pid_t tid = ::waitpid(-1, &status, __WALL | WNOHANG);
            if (tid <= 0) break;
            gotAny = true;
            handleStoppedEvent(static_cast<int>(tid), status, sess->pausedFlag.load());
        }
        if (!gotAny) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    // Cleanly disarm & detach all threads without leaving any stray SIGSTOP
    for (auto& kv : threads) {
        TracedThread& t = kv.second;
        if (t.dead) continue;
        if (stopThreadForControl(t, 120)) {
            installHw(t.tid, false);
            t.hwArmed = false;
            ::ptrace(PTRACE_DETACH, t.tid, nullptr, reinterpret_cast<void*>(static_cast<uintptr_t>(t.pendingSig)));
            t.stopped = false;
        } else if (!t.dead) {
            ::ptrace(PTRACE_DETACH, t.tid, nullptr, nullptr);
        }
    }
    sess->runningFlag.store(false);
}

} // namespace mem_engine
