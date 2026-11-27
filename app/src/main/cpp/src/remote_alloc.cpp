#include "mem_engine/remote_alloc.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/proc_io.hpp"

#include <cctype>
#include <cerrno>
#include <cinttypes>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <dlfcn.h>
#include <elf.h>
#include <fstream>
#include <sstream>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>
#include <unordered_set>
#include <vector>

namespace mem_engine {

namespace {

std::string protToRwx(int prot) {
    std::string s;
    s.push_back((prot & PROT_READ)  ? 'r' : '-');
    s.push_back((prot & PROT_WRITE) ? 'w' : '-');
    s.push_back((prot & PROT_EXEC)  ? 'x' : '-');
    return s;
}

bool findSyscallGadgetInTarget(ProcIO& io, int pid, uint64_t& outPc) {
    auto regions = MapsParser::parseProcessMaps(pid, 0, false);

    // Prefer executable mappings in libc.so or [vdso], then any r-x region
    std::vector<VmaRegion> execRegions;
    for (const auto& r : regions) {
        if (r.executable && r.readable && r.length >= 4096) {
            execRegions.push_back(r);
        }
    }
    std::stable_sort(execRegions.begin(), execRegions.end(), [](const VmaRegion& a, const VmaRegion& b) {
        auto score = [](const std::string& p) -> int {
            if (p.find("libc.so") != std::string::npos) return 0;
            if (p == "[vdso]") return 1;
            if (p.find("/system/") != std::string::npos || p.find("/apex/") != std::string::npos) return 2;
            return 3;
        };
        return score(a.pathname) < score(b.pathname);
    });

    std::vector<uint8_t> buf(64 * 1024);
    for (const auto& r : execRegions) {
        size_t maxScan = std::min<size_t>(r.length, 512 * 1024);
        size_t scanned = 0;
        while (scanned < maxScan) {
            size_t chunk = std::min(buf.size(), maxScan - scanned);
            size_t got = 0;
            if (io.readMemory(r.startAddress + scanned, buf.data(), chunk, &got) != EngineErrc::OK || got < 4) {
                break;
            }
#if defined(__aarch64__)
            // ARM64: svc #0 -> 01 00 00 D4 (little-endian 0xD4000001)
            for (size_t off = 0; off + 4 <= got; off += 4) {
                uint32_t insn = 0;
                std::memcpy(&insn, buf.data() + off, 4);
                if (insn == 0xD4000001u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#elif defined(__arm__)
            // ARM32: svc #0 -> 00 00 00 EF (0xEF000000)
            for (size_t off = 0; off + 4 <= got; off += 4) {
                uint32_t insn = 0;
                std::memcpy(&insn, buf.data() + off, 4);
                if (insn == 0xEF000000u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#elif defined(__x86_64__)
            // x86_64: syscall -> 0F 05
            for (size_t off = 0; off + 2 <= got; ++off) {
                if (buf[off] == 0x0Fu && buf[off + 1] == 0x05u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#elif defined(__i386__)
            // x86: int 0x80 -> CD 80
            for (size_t off = 0; off + 2 <= got; ++off) {
                if (buf[off] == 0xCDu && buf[off + 1] == 0x80u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#endif
            scanned += got;
        }
    }
    return false;
}

bool waitForStop(int pid, int& outStatus) {
    while (true) {
        pid_t r = ::waitpid(pid, &outStatus, __WALL);
        if (r < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        return r == pid && WIFSTOPPED(outStatus);
    }
}

bool verifyAllocatedVma(
    int pid,
    uint64_t addr,
    size_t expectedSize,
    int requestedProt,
    ProcIO& io,
    RemoteAllocResult& res
) {
    auto regions = MapsParser::parseProcessMaps(pid, 0, false);
    const VmaRegion* found = nullptr;
    for (const auto& r : regions) {
        if (addr >= r.startAddress && addr < r.endAddress) {
            found = &r;
            break;
        }
    }
    if (!found) {
        res.ok = false;
        res.error = "已调用 mmap 但目标进程 /proc/" + std::to_string(pid) + "/maps 中未找到该内存页";
        return false;
    }

    int actualProt = 0;
    if (found->readable)   actualProt |= PROT_READ;
    if (found->writable)   actualProt |= PROT_WRITE;
    if (found->executable) actualProt |= PROT_EXEC;
    res.actualProt = actualProt;
    res.size = std::max<size_t>(expectedSize, found->length > 0 ? expectedSize : 4096);

    if ((requestedProt & PROT_WRITE) && !(actualProt & PROT_WRITE)) {
        res.ok = false;
        res.error = "内核返回的内存页缺少可写权限 (请求 " + protToRwx(requestedProt) + "，实际 " + protToRwx(actualProt) + ")";
        return false;
    }
    if ((requestedProt & PROT_EXEC) && !(actualProt & PROT_EXEC)) {
        res.ok = false;
        res.error = "内核/SELinux 拒绝了可执行权限 (请求 " + protToRwx(requestedProt) + "，实际 " + protToRwx(actualProt) + ")";
        return false;
    }

    // Verify actual cross-process write & readback when PROT_WRITE is requested
    if (requestedProt & PROT_WRITE) {
        uint8_t orig[8] = {0};
        size_t got = 0;
        io.readMemory(static_cast<uintptr_t>(addr), orig, sizeof(orig), &got);

        const uint8_t probe[4] = {0x5A, 0xA5, 0x3C, 0xC3};
        auto wrc = io.writeMemory(static_cast<uintptr_t>(addr), probe, sizeof(probe));
        if (wrc != EngineErrc::OK) {
            res.ok = false;
            res.error = "内存页已在目标进程分配，但跨进程写入通道校验失败 (rc=" + std::to_string(static_cast<int>(wrc)) + ")";
            return false;
        }
        uint8_t check[4] = {0};
        size_t checkGot = 0;
        auto rrc = io.readMemory(static_cast<uintptr_t>(addr), check, sizeof(check), &checkGot);
        // Restore zeroed/original bytes cleanly
        io.writeMemory(static_cast<uintptr_t>(addr), orig, sizeof(probe));

        if (rrc != EngineErrc::OK || checkGot != sizeof(probe) || std::memcmp(probe, check, sizeof(probe)) != 0) {
            res.ok = false;
            res.error = "内存页写入回读校验不一致，目标页可能受写保护";
            return false;
        }
        res.writeVerified = true;
    }

    res.ok = true;
    res.address = addr;
    return true;
}

} // namespace

RemoteAllocResult allocateTargetProcessPage(
    int targetPid,
    uint64_t hintAddr,
    size_t size,
    int prot
) {
    RemoteAllocResult res;
    if (targetPid <= 0) {
        res.error = "未附加有效的目标进程";
        return res;
    }

    long pageSize = ::sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    size_t alignedSize = size == 0
        ? static_cast<size_t>(pageSize)
        : ((size + static_cast<size_t>(pageSize) - 1) / static_cast<size_t>(pageSize)) * static_cast<size_t>(pageSize);

    // Self-process fast path
    if (targetPid == ::getpid()) {
        void* p = ::mmap(
            reinterpret_cast<void*>(static_cast<uintptr_t>(hintAddr)),
            alignedSize,
            prot,
            MAP_PRIVATE | MAP_ANONYMOUS,
            -1,
            0
        );
        if (p == MAP_FAILED) {
            res.error = "mmap 分配失败 (errno=" + std::to_string(errno) + ": " + std::strerror(errno) + ")";
            return res;
        }
        ProcIO selfIo;
        selfIo.attach(targetPid);
        verifyAllocatedVma(targetPid, static_cast<uint64_t>(reinterpret_cast<uintptr_t>(p)), alignedSize, prot, selfIo, res);
        return res;
    }

    ProcIO io;
    if (io.attach(targetPid) != EngineErrc::OK) {
        res.error = "无法附加目标进程内存通道 (pid=" + std::to_string(targetPid) + ")";
        return res;
    }

    uint64_t syscallPc = 0;
    if (!findSyscallGadgetInTarget(io, targetPid, syscallPc)) {
        res.error = "未在目标进程可执行段中找到系统调用指令 (libc.so / [vdso])";
        return res;
    }

    if (::ptrace(PTRACE_ATTACH, targetPid, nullptr, nullptr) != 0) {
        int e = errno;
        res.error = "ptrace 附加目标进程失败 (errno=" + std::to_string(e) + ": " + std::strerror(e) + "，请确认已获 Root 或同 UID 权限)";
        return res;
    }

    int status = 0;
    if (!waitForStop(targetPid, status)) {
        ::ptrace(PTRACE_DETACH, targetPid, nullptr, nullptr);
        res.error = "等待目标进程暂停失败";
        return res;
    }

    int64_t mmapRet = -EINVAL;
    bool execOk = false;

#if defined(__aarch64__)
    struct user_pt_regs savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct user_pt_regs callRegs = savedRegs;
        callRegs.regs[8] = __NR_mmap;
        callRegs.regs[0] = hintAddr;
        callRegs.regs[1] = static_cast<uint64_t>(alignedSize);
        callRegs.regs[2] = static_cast<uint64_t>(prot);
        callRegs.regs[3] = static_cast<uint64_t>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.regs[4] = static_cast<uint64_t>(-1LL);
        callRegs.regs[5] = 0;
        callRegs.pc = syscallPc;

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            if (::ptrace(PTRACE_SINGLESTEP, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct user_pt_regs afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(afterRegs.regs[0]);
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#elif defined(__arm__)
    struct pt_regs savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct pt_regs callRegs = savedRegs;
        callRegs.uregs[7] = __NR_mmap2;
        callRegs.uregs[0] = static_cast<unsigned long>(hintAddr);
        callRegs.uregs[1] = static_cast<unsigned long>(alignedSize);
        callRegs.uregs[2] = static_cast<unsigned long>(prot);
        callRegs.uregs[3] = static_cast<unsigned long>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.uregs[4] = static_cast<unsigned long>(-1);
        callRegs.uregs[5] = 0;
        callRegs.uregs[15] = static_cast<unsigned long>(syscallPc);
        // Clear Thumb bit (T=0) because svc #0 gadget is ARM-mode 0xEF000000
        callRegs.uregs[16] &= ~(1UL << 5);

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            // Stop at syscall-enter then syscall-exit
            if (::ptrace(PTRACE_SYSCALL, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status) &&
                ::ptrace(PTRACE_SYSCALL, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct pt_regs afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(static_cast<int32_t>(afterRegs.uregs[0]));
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#elif defined(__x86_64__)
    struct user_regs_struct savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct user_regs_struct callRegs = savedRegs;
        callRegs.rax = __NR_mmap;
        callRegs.rdi = hintAddr;
        callRegs.rsi = static_cast<unsigned long long>(alignedSize);
        callRegs.rdx = static_cast<unsigned long long>(prot);
        callRegs.r10 = static_cast<unsigned long long>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.r8  = static_cast<unsigned long long>(-1LL);
        callRegs.r9  = 0;
        callRegs.rip = syscallPc;

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            if (::ptrace(PTRACE_SINGLESTEP, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct user_regs_struct afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(afterRegs.rax);
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#elif defined(__i386__)
    struct user_regs_struct savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct user_regs_struct callRegs = savedRegs;
        callRegs.eax = __NR_mmap2;
        callRegs.ebx = static_cast<long>(hintAddr);
        callRegs.ecx = static_cast<long>(alignedSize);
        callRegs.edx = static_cast<long>(prot);
        callRegs.esi = static_cast<long>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.edi = -1;
        callRegs.ebp = 0;
        callRegs.eip = static_cast<long>(syscallPc);

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            if (::ptrace(PTRACE_SINGLESTEP, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct user_regs_struct afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(static_cast<int32_t>(afterRegs.eax));
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#else
    res.error = "当前架构不支持跨进程 mmap 分配";
#endif

    ::ptrace(PTRACE_DETACH, targetPid, nullptr, nullptr);

    if (!execOk) {
        if (res.error.empty()) {
            res.error = "目标进程内执行 mmap 系统调用失败 (可能是架构位宽不匹配或 ptrace 寄存器读写受限)";
        }
        return res;
    }

    // Linux syscalls return -errno in [-4095, -1] on error
    if (mmapRet < 0 && mmapRet >= -4095) {
        int errCode = static_cast<int>(-mmapRet);
        std::string hint;
        if (errCode == EACCES && (prot & PROT_EXEC)) {
            hint = " (SELinux 禁止 execmem，可尝试去掉“执行”权限仅申请读写页)";
        }
        res.error = "目标进程 mmap 返回错误 errno=" + std::to_string(errCode) +
                    " (" + std::strerror(errCode) + ")" + hint;
        return res;
    }

    uint64_t mappedAddr = static_cast<uint64_t>(mmapRet);
    if (mappedAddr == 0 || mappedAddr == static_cast<uint64_t>(-1LL)) {
        res.error = "目标进程 mmap 返回无效地址 0x0";
        return res;
    }

    verifyAllocatedVma(targetPid, mappedAddr, alignedSize, prot, io, res);
    return res;
}

namespace {

std::string toLowerCopy(const std::string& s) {
    std::string out = s;
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

std::string baseNameOf(const std::string& path) {
    auto pos = path.find_last_of('/');
    return (pos == std::string::npos) ? path : path.substr(pos + 1);
}

const char* elfSymTypeName(uint8_t stInfo) {
    switch (ELF64_ST_TYPE(stInfo)) {
        case STT_FUNC:   return "FUNC";
        case STT_OBJECT: return "OBJECT";
        case STT_TLS:    return "TLS";
        case STT_GNU_IFUNC: return "IFUNC";
        default:         return "NOTYPE";
    }
}

std::string escapeJsonElf(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 4);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c >= 0x20) out.push_back(static_cast<char>(c));
                break;
        }
    }
    return out;
}

template <typename EhdrT, typename PhdrT, typename ShdrT, typename SymT>
bool parseElfBytes(
    const std::vector<uint8_t>& data,
    uint64_t mappedBase,
    const std::string& moduleName,
    const std::string& symFilterLower,
    size_t maxCount,
    std::vector<ElfSymbolInfo>& outSymbols
) {
    if (data.size() < sizeof(EhdrT)) return false;
    EhdrT ehdr{};
    std::memcpy(&ehdr, data.data(), sizeof(EhdrT));

    uint64_t firstLoadVaddr = UINT64_MAX;
    if (ehdr.e_phoff > 0 && ehdr.e_phnum > 0 && ehdr.e_phentsize == sizeof(PhdrT)) {
        for (uint16_t i = 0; i < ehdr.e_phnum; ++i) {
            size_t off = static_cast<size_t>(ehdr.e_phoff) + static_cast<size_t>(i) * sizeof(PhdrT);
            if (off + sizeof(PhdrT) > data.size()) break;
            PhdrT ph{};
            std::memcpy(&ph, data.data() + off, sizeof(PhdrT));
            if (ph.p_type == PT_LOAD) {
                uint64_t v = static_cast<uint64_t>(ph.p_vaddr);
                if (v < firstLoadVaddr) firstLoadVaddr = v;
            }
        }
    }
    if (firstLoadVaddr == UINT64_MAX) firstLoadVaddr = 0;
    const uint64_t loadBias = mappedBase - firstLoadVaddr;

    if (ehdr.e_shoff == 0 || ehdr.e_shnum == 0 || ehdr.e_shentsize != sizeof(ShdrT)) {
        return false;
    }

    std::vector<ShdrT> shdrs;
    shdrs.reserve(ehdr.e_shnum);
    for (uint16_t i = 0; i < ehdr.e_shnum; ++i) {
        size_t off = static_cast<size_t>(ehdr.e_shoff) + static_cast<size_t>(i) * sizeof(ShdrT);
        if (off + sizeof(ShdrT) > data.size()) break;
        ShdrT sh{};
        std::memcpy(&sh, data.data() + off, sizeof(ShdrT));
        shdrs.push_back(sh);
    }

    std::unordered_set<std::string> seen;
    auto scanSymSection = [&](uint32_t targetShType) {
        for (const auto& sh : shdrs) {
            if (sh.sh_type != targetShType || sh.sh_entsize != sizeof(SymT)) continue;
            if (sh.sh_link >= shdrs.size()) continue;
            const auto& strSh = shdrs[sh.sh_link];
            size_t symOff = static_cast<size_t>(sh.sh_offset);
            size_t symSz  = static_cast<size_t>(sh.sh_size);
            size_t strOff = static_cast<size_t>(strSh.sh_offset);
            size_t strSz  = static_cast<size_t>(strSh.sh_size);
            if (symOff + symSz > data.size() || strOff + strSz > data.size()) continue;

            size_t count = symSz / sizeof(SymT);
            for (size_t idx = 0; idx < count; ++idx) {
                SymT sym{};
                std::memcpy(&sym, data.data() + symOff + idx * sizeof(SymT), sizeof(SymT));
                if (sym.st_name == 0 || sym.st_shndx == SHN_UNDEF || sym.st_value == 0) continue;
                if (sym.st_name >= strSz) continue;

                const char* namePtr = reinterpret_cast<const char*>(data.data() + strOff + sym.st_name);
                size_t maxLen = strSz - sym.st_name;
                size_t nameLen = 0;
                while (nameLen < maxLen && namePtr[nameLen] != '\0') ++nameLen;
                if (nameLen == 0) continue;

                std::string name(namePtr, nameLen);
                if (!symFilterLower.empty()) {
                    if (toLowerCopy(name).find(symFilterLower) == std::string::npos) continue;
                }
                if (!seen.insert(name).second) continue;

                ElfSymbolInfo info;
                info.name = std::move(name);
                info.offset = static_cast<uint64_t>(sym.st_value);
                info.address = loadBias + static_cast<uint64_t>(sym.st_value);
                info.size = static_cast<uint64_t>(sym.st_size);
                info.type = elfSymTypeName(sym.st_info);
                info.moduleName = moduleName;
                info.moduleBase = mappedBase;
                outSymbols.push_back(std::move(info));
                if (maxCount > 0 && outSymbols.size() >= maxCount) return;
            }
        }
    };

    // Prioritize exported dynamic symbols (.dynsym), then static symbols (.symtab)
    scanSymSection(SHT_DYNSYM);
    if (maxCount == 0 || outSymbols.size() < maxCount) {
        scanSymSection(SHT_SYMTAB);
    }
    return true;
}

bool readSockExact(int fd, void* buf, size_t len) {
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

bool writeSockExact(int fd, const void* buf, size_t len) {
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

} // namespace

std::vector<ElfSymbolInfo> listProcessModuleExports(
    int targetPid,
    const std::string& libFilter,
    const std::string& symFilter,
    size_t maxCount,
    uint64_t* outModuleBase,
    std::string* outResolvedPath,
    std::string* outError
) {
    std::vector<ElfSymbolInfo> results;
    if (targetPid <= 0 || libFilter.empty()) {
        if (outError) *outError = "Invalid PID or module name";
        return results;
    }

    auto regions = MapsParser::parseProcessMaps(targetPid, 0, false);
    std::string needleLower = toLowerCopy(libFilter);

    uint64_t modBase = 0;
    std::string modPath;
    for (const auto& r : regions) {
        if (r.pathname.empty() || r.pathname[0] != '/') continue;
        std::string pathLower = toLowerCopy(r.pathname);
        std::string baseLower = toLowerCopy(baseNameOf(r.pathname));
        bool exactMatch = (baseLower == needleLower || pathLower == needleLower);
        bool substrMatch = (pathLower.find(needleLower) != std::string::npos);
        if (exactMatch || substrMatch) {
            if (modBase == 0 || r.startAddress < modBase) {
                modBase = static_cast<uint64_t>(r.startAddress);
                modPath = r.pathname;
            }
            if (exactMatch && r.fileOffset == 0) {
                modBase = static_cast<uint64_t>(r.startAddress);
                modPath = r.pathname;
                break;
            }
        }
    }

    if (modBase == 0 || modPath.empty()) {
        if (outError) *outError = "Module not mapped in target process: " + libFilter;
        return results;
    }

    if (outModuleBase) *outModuleBase = modBase;
    if (outResolvedPath) *outResolvedPath = modPath;

    // Strip "(deleted)" suffix if present
    std::string filePath = modPath;
    auto delPos = filePath.find(" (deleted)");
    if (delPos != std::string::npos) {
        filePath.erase(delPos);
    }

    std::ifstream ifs(filePath, std::ios::in | std::ios::binary | std::ios::ate);
    if (!ifs.is_open()) {
        if (outError) *outError = "Cannot open ELF file on disk: " + filePath;
        return results;
    }
    auto fileSize = ifs.tellg();
    if (fileSize <= 64 || fileSize > 256LL * 1024LL * 1024LL) {
        if (outError) *outError = "ELF file size invalid or exceeds 256MB limit";
        return results;
    }
    std::vector<uint8_t> data(static_cast<size_t>(fileSize));
    ifs.seekg(0, std::ios::beg);
    ifs.read(reinterpret_cast<char*>(data.data()), fileSize);
    ifs.close();

    if (data.size() < EI_NIDENT ||
        data[EI_MAG0] != ELFMAG0 || data[EI_MAG1] != ELFMAG1 ||
        data[EI_MAG2] != ELFMAG2 || data[EI_MAG3] != ELFMAG3) {
        if (outError) *outError = "Target file is not a valid ELF binary: " + filePath;
        return results;
    }

    std::string symFilterLower = toLowerCopy(symFilter);
    std::string modShort = baseNameOf(filePath);
    bool parsed = false;
    if (data[EI_CLASS] == ELFCLASS64) {
        parsed = parseElfBytes<Elf64_Ehdr, Elf64_Phdr, Elf64_Shdr, Elf64_Sym>(
            data, modBase, modShort, symFilterLower, maxCount, results
        );
    } else if (data[EI_CLASS] == ELFCLASS32) {
        parsed = parseElfBytes<Elf32_Ehdr, Elf32_Phdr, Elf32_Shdr, Elf32_Sym>(
            data, modBase, modShort, symFilterLower, maxCount, results
        );
    }

    if (!parsed && outError) {
        *outError = "Unsupported ELF class or missing section headers in " + modShort;
    }
    return results;
}

uint64_t findProcessSymbolAddress(
    int targetPid,
    const std::string& libFilter,
    const std::string& symName,
    uint64_t* outModuleBase,
    std::string* outError
) {
    if (symName.empty()) return 0;
    auto syms = listProcessModuleExports(targetPid, libFilter, symName, 0, outModuleBase, nullptr, outError);
    for (const auto& s : syms) {
        if (s.name == symName) return s.address;
    }
    for (const auto& s : syms) {
        if (s.name.find(symName) != std::string::npos) return s.address;
    }
    return 0;
}

std::string elfSymbolsToJson(const std::vector<ElfSymbolInfo>& symbols) {
    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < symbols.size(); ++i) {
        const auto& s = symbols[i];
        if (i > 0) oss << ",";
        char addrBuf[32], offBuf[32], baseBuf[32];
        std::snprintf(addrBuf, sizeof(addrBuf), "0x%" PRIx64, s.address);
        std::snprintf(offBuf, sizeof(offBuf), "0x%" PRIx64, s.offset);
        std::snprintf(baseBuf, sizeof(baseBuf), "0x%" PRIx64, s.moduleBase);
        oss << "{\"name\":\"" << escapeJsonElf(s.name) << "\""
            << ",\"address\":" << static_cast<int64_t>(s.address)
            << ",\"addressHex\":\"" << addrBuf << "\""
            << ",\"offset\":" << static_cast<int64_t>(s.offset)
            << ",\"offsetHex\":\"" << offBuf << "\""
            << ",\"size\":" << s.size
            << ",\"type\":\"" << escapeJsonElf(s.type) << "\""
            << ",\"module\":\"" << escapeJsonElf(s.moduleName) << "\""
            << ",\"moduleBase\":" << static_cast<int64_t>(s.moduleBase)
            << ",\"moduleBaseHex\":\"" << baseBuf << "\"}";
    }
    oss << "]";
    return oss.str();
}

bool sendHookAgentCommand(
    int targetPid,
    const std::string& reqJson,
    std::string* outRespJson,
    std::string* outError
) {
    if (targetPid <= 0) {
        if (outError) *outError = "No valid target process attached";
        return false;
    }

    // Fast path: in-process self-target invokes libggnext_agent.so directly
    if (targetPid == ::getpid()) {
        using HookCmdFn = int (*)(const char*, char*, size_t);
        static void* sAgentHandle = nullptr;
        static HookCmdFn sHookCmdFn = nullptr;
        if (!sHookCmdFn) {
            sAgentHandle = ::dlopen("libggnext_agent.so", RTLD_LAZY);
            if (sAgentHandle) {
                sHookCmdFn = reinterpret_cast<HookCmdFn>(::dlsym(sAgentHandle, "ggnext_agent_hook_command"));
            }
        }
        if (!sHookCmdFn) {
            if (outError) *outError = "libggnext_agent.so not loaded or missing ggnext_agent_hook_command";
            return false;
        }
        std::vector<char> outBuf(256 * 1024, '\0');
        int n = sHookCmdFn(reqJson.c_str(), outBuf.data(), outBuf.size());
        if (n < 0) {
            if (outError) *outError = "In-process agent command failed";
            return false;
        }
        if (outRespJson) outRespJson->assign(outBuf.data(), static_cast<size_t>(n));
        return true;
    }

    // Cross-process IPC via abstract Unix domain socket \0ggnext_hook_<pid>
    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        if (outError) *outError = std::string("socket() failed: ") + std::strerror(errno);
        return false;
    }

    struct timeval tv{4, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    char name[64];
    int nameLen = std::snprintf(name, sizeof(name), "ggnext_hook_%d", targetPid);
    addr.sun_path[0] = '\0';
    std::memcpy(addr.sun_path + 1, name, static_cast<size_t>(nameLen));
    socklen_t sockLen = static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) + 1 + nameLen);

    if (::connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sockLen) != 0) {
        int e = errno;
        ::close(fd);
        if (outError) {
            *outError = "ShadowHook agent not listening in target PID " + std::to_string(targetPid) +
                        " (@ggnext_hook_" + std::to_string(targetPid) + ", errno=" + std::to_string(e) +
                        "). Ensure libggnext_agent.so is loaded into the target process (e.g. wrap.<pkg>/LD_PRELOAD) or attach self-process.";
        }
        return false;
    }

    uint32_t reqLen = static_cast<uint32_t>(reqJson.size());
    if (!writeSockExact(fd, &reqLen, sizeof(reqLen)) || !writeSockExact(fd, reqJson.data(), reqJson.size())) {
        ::close(fd);
        if (outError) *outError = "Failed to send command to ShadowHook agent";
        return false;
    }

    uint32_t respLen = 0;
    if (!readSockExact(fd, &respLen, sizeof(respLen)) || respLen == 0 || respLen > 4 * 1024 * 1024) {
        ::close(fd);
        if (outError) *outError = "Failed to read response length from ShadowHook agent";
        return false;
    }

    std::string resp(respLen, '\0');
    if (!readSockExact(fd, resp.data(), respLen)) {
        ::close(fd);
        if (outError) *outError = "Failed to read response body from ShadowHook agent";
        return false;
    }

    ::close(fd);
    if (outRespJson) *outRespJson = std::move(resp);
    return true;
}

} // namespace mem_engine
