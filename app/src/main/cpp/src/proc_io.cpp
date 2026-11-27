#include "mem_engine/proc_io.hpp"
#include <fcntl.h>
#include <unistd.h>
#include <sys/uio.h>
#include <sys/mman.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <cmath>
#include <algorithm>
#include <chrono>

#ifndef KT_EINTR_RETRY
#define KT_EINTR_RETRY(exp)                  \
    ({                                       \
        decltype(exp) _rc;                   \
        do {                                 \
            _rc = (exp);                     \
        } while (_rc == -1 && errno == EINTR); \
        _rc;                                 \
    })
#endif

namespace mem_engine {

namespace {

inline size_t pageRemain(uintptr_t addr, size_t pageSize) noexcept {
    uintptr_t nextPage = ((addr + pageSize) & ~(pageSize - 1));
    return static_cast<size_t>(nextPage - addr);
}

} // anonymous namespace

size_t ProcIO::querySystemPageSize() noexcept {
    long ps = ::sysconf(_SC_PAGESIZE);
    if (ps > 0 && (static_cast<size_t>(ps) & (static_cast<size_t>(ps) - 1)) == 0) {
        return static_cast<size_t>(ps);
    }
    return 4096;
}

size_t ProcIO::querySystemIovMax() noexcept {
    long im = ::sysconf(_SC_IOV_MAX);
    if (im > 0 && im <= 4096) {
        return static_cast<size_t>(im);
    }
    return 1024;
}

uint64_t ProcIO::detectKernelZeroPagePfn(size_t pageSize) noexcept {
    int pmFd = ::open("/proc/self/pagemap", O_RDONLY | O_CLOEXEC);
    if (pmFd < 0) return 0;

    void* page = ::mmap(nullptr, pageSize, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) {
        ::close(pmFd);
        return 0;
    }

    // Touch with a read to fault in the kernel's shared empty_zero_page without COW
    volatile uint8_t dummy = *static_cast<const volatile uint8_t*>(page);
    (void)dummy;

    uintptr_t vaddr = reinterpret_cast<uintptr_t>(page) & ArchPtrPolicy::ARM64_TBI_MASK;
    off64_t offset = static_cast<off64_t>((vaddr / pageSize) * sizeof(uint64_t));
    uint64_t pte = 0;
    ssize_t n = KT_EINTR_RETRY(::pread64(pmFd, &pte, sizeof(uint64_t), offset));

    ::munmap(page, pageSize);
    ::close(pmFd);

    constexpr uint64_t kPresentMask = (1ULL << 63);
    constexpr uint64_t kPfnMask     = (1ULL << 55) - 1ULL;
    if (n == sizeof(uint64_t) && (pte & kPresentMask) != 0) {
        return (pte & kPfnMask); // Non-zero only if CAP_SYS_ADMIN / root exposes PFN
    }
    return 0;
}

bool ProcIO::detectProcessIs64Bit(int pid) noexcept {
    if (pid <= 0) return sizeof(void*) == 8;

    // 1. Try reading ELF EI_CLASS (byte 4: 1 = 32-bit, 2 = 64-bit) from /proc/[pid]/exe
    char exePath[64];
    std::snprintf(exePath, sizeof(exePath), "/proc/%d/exe", pid);
    int fd = ::open(exePath, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        uint8_t ident[6] = {0};
        ssize_t n = KT_EINTR_RETRY(::read(fd, ident, sizeof(ident)));
        ::close(fd);
        if (n >= 5 && ident[0] == 0x7F && ident[1] == 'E' && ident[2] == 'L' && ident[3] == 'F') {
            if (ident[4] == 1) return false;
            if (ident[4] == 2) return true;
        }
    }

    // 2. Fallback: inspect /proc/[pid]/maps for any 64-bit virtual address (> 0xFFFFFFFF)
    char mapsPath[64];
    std::snprintf(mapsPath, sizeof(mapsPath), "/proc/%d/maps", pid);
    if (FILE* fp = std::fopen(mapsPath, "r")) {
        char line[256];
        bool sawAny = false;
        bool saw64  = false;
        while (std::fgets(line, sizeof(line), fp)) {
            char* endPtr = nullptr;
            unsigned long long start = std::strtoull(line, &endPtr, 16);
            if (endPtr && *endPtr == '-') {
                unsigned long long end = std::strtoull(endPtr + 1, nullptr, 16);
                sawAny = true;
                if ((start & ArchPtrPolicy::ARM64_TBI_MASK) > 0xFFFFFFFFULL ||
                    (end & ArchPtrPolicy::ARM64_TBI_MASK) > 0x100000000ULL) {
                    saw64 = true;
                    break;
                }
            }
        }
        std::fclose(fp);
        if (sawAny) return saw64;
    }

    return sizeof(void*) == 8;
}

EngineErrc ProcIO::mapErrnoToErrc(int err) noexcept {
    switch (err) {
        case 0:      return EngineErrc::OK;
        case EPERM:
        case EACCES: return EngineErrc::ERR_PERM;
        case ESRCH:  return EngineErrc::ERR_SRCH;
        case EFAULT:
        case EIO:    return EngineErrc::ERR_FAULT;
        case ENOMEM: return EngineErrc::ERR_NOMEM;
        default:     return EngineErrc::ERR_INVAL;
    }
}

ProcIO::ProcIO()
    : m_pid(-1),
      m_memFd(-1),
      m_pagemapFd(-1),
      m_pageSize(querySystemPageSize()),
      m_iovMax(querySystemIovMax()),
      m_zeroPagePfn(detectKernelZeroPagePfn(m_pageSize)),
      m_targetIs64Bit(sizeof(void*) == 8) {}

ProcIO::ProcIO(int pid) : ProcIO() {
    attach(pid);
}

ProcIO::~ProcIO() {
    detach();
}

ProcIO::ProcIO(ProcIO&& other) noexcept
    : m_pid(other.m_pid),
      m_memFd(other.m_memFd),
      m_pagemapFd(other.m_pagemapFd),
      m_pageSize(other.m_pageSize),
      m_iovMax(other.m_iovMax),
      m_zeroPagePfn(other.m_zeroPagePfn),
      m_targetIs64Bit(other.m_targetIs64Bit),
      m_pvmSupported(other.m_pvmSupported.load(std::memory_order_relaxed)),
      m_pagemapUsable(other.m_pagemapUsable.load(std::memory_order_relaxed)) {
    other.m_pid = -1;
    other.m_memFd = -1;
    other.m_pagemapFd = -1;
}

ProcIO& ProcIO::operator=(ProcIO&& other) noexcept {
    if (this != &other) {
        detach();
        m_pid = other.m_pid;
        m_memFd = other.m_memFd;
        m_pagemapFd = other.m_pagemapFd;
        m_pageSize = other.m_pageSize;
        m_iovMax = other.m_iovMax;
        m_zeroPagePfn = other.m_zeroPagePfn;
        m_targetIs64Bit = other.m_targetIs64Bit;
        m_pvmSupported.store(other.m_pvmSupported.load(std::memory_order_relaxed), std::memory_order_relaxed);
        m_pagemapUsable.store(other.m_pagemapUsable.load(std::memory_order_relaxed), std::memory_order_relaxed);
        other.m_pid = -1;
        other.m_memFd = -1;
        other.m_pagemapFd = -1;
    }
    return *this;
}

EngineErrc ProcIO::attach(int pid) {
    detach();
    if (pid <= 0) return EngineErrc::ERR_INVAL;

    char procDir[64];
    std::snprintf(procDir, sizeof(procDir), "/proc/%d", pid);
    if (::access(procDir, F_OK) != 0) {
        return mapErrnoToErrc(errno);
    }

    m_pid = pid;
    m_targetIs64Bit = detectProcessIs64Bit(pid);
    m_pvmSupported.store(true, std::memory_order_relaxed);
    m_pagemapUsable.store(false, std::memory_order_relaxed);

    // Open /proc/[pid]/mem (O_RDWR allows FOLL_FORCE writes to read-only pages, matching KittyMemIO)
    char memPath[64];
    std::snprintf(memPath, sizeof(memPath), "/proc/%d/mem", pid);
    m_memFd = ::open(memPath, O_RDWR | O_LARGEFILE | O_CLOEXEC);
    if (m_memFd < 0) {
        m_memFd = ::open(memPath, O_RDONLY | O_LARGEFILE | O_CLOEXEC);
    }

    // Collect diverse readable VMA probe addresses (preferring active writable heap/anon first, then ELF/code)
    std::vector<uintptr_t> anonRwProbes;
    std::vector<uintptr_t> fileProbes;
    anonRwProbes.reserve(16);
    fileProbes.reserve(16);
    bool mapsReadable = false;

    char mapsPath[64];
    std::snprintf(mapsPath, sizeof(mapsPath), "/proc/%d/maps", pid);
    if (FILE* mfp = std::fopen(mapsPath, "r")) {
        char line[512];
        while (std::fgets(line, sizeof(line), mfp)) {
            unsigned long long start = 0, end = 0;
            char perms[8] = {0};
            if (std::sscanf(line, "%llx-%llx %7s", &start, &end, perms) == 3 &&
                perms[0] == 'r' && end > start && start > 0) {
                mapsReadable = true;
                // Skip device/trap/font mappings which may have VM_IO / VM_PFNMAP
                if (std::strstr(line, "/dev/") != nullptr ||
                    std::strstr(line, "[vvar]") != nullptr ||
                    std::strstr(line, "[vectors]") != nullptr ||
                    std::strstr(line, ".ttf") != nullptr ||
                    std::strstr(line, ".otf") != nullptr) {
                    continue;
                }
                const bool isStack = (std::strstr(line, "[stack") != nullptr);
                uintptr_t probeAddr = isStack && (end - start >= m_pageSize)
                    ? static_cast<uintptr_t>(end - m_pageSize)
                    : static_cast<uintptr_t>(start);
                const bool isAnonLike = (std::strchr(line, '/') == nullptr);
                if (perms[1] == 'w' && isAnonLike) {
                    if (anonRwProbes.size() < 16) anonRwProbes.push_back(probeAddr);
                } else {
                    if (fileProbes.size() < 16) fileProbes.push_back(probeAddr);
                }
                if (anonRwProbes.size() >= 16 && fileProbes.size() >= 16) break;
            }
        }
        std::fclose(mfp);
    }

    std::vector<uintptr_t> probeAddrs;
    probeAddrs.reserve(anonRwProbes.size() + fileProbes.size());
    probeAddrs.insert(probeAddrs.end(), anonRwProbes.begin(), anonRwProbes.end());
    probeAddrs.insert(probeAddrs.end(), fileProbes.begin(), fileProbes.end());

    // Probe real cross-process read capability via process_vm_readv & /proc/[pid]/mem pread64
    std::vector<uintptr_t> verifiedAddrs;
    verifiedAddrs.reserve(8);
    int lastReadErr = (m_memFd < 0) ? EPERM : 0;

    if (!probeAddrs.empty()) {
        bool pvmBlocked = false;
        for (uintptr_t probeAddr : probeAddrs) {
            uint8_t dummy = 0;
            bool readOk = false;

            if (!pvmBlocked) {
                struct iovec l_iov{&dummy, 1u};
                struct iovec r_iov{reinterpret_cast<void*>(probeAddr), 1u};
                ssize_t n = KT_EINTR_RETRY(::process_vm_readv(m_pid, &l_iov, 1, &r_iov, 1, 0));
                if (n == 1) {
                    readOk = true;
                } else {
                    lastReadErr = errno;
                    if (lastReadErr == ESRCH) {
                        detach();
                        return EngineErrc::ERR_SRCH;
                    }
                    if (lastReadErr == ENOSYS || lastReadErr == EPERM || lastReadErr == EACCES) {
                        pvmBlocked = true;
                        m_pvmSupported.store(false, std::memory_order_relaxed);
                    }
                }
            }

            if (!readOk && m_memFd >= 0) {
                ssize_t n = KT_EINTR_RETRY(::pread64(m_memFd, &dummy, 1u, static_cast<off64_t>(probeAddr)));
                if (n == 1) {
                    readOk = true;
                } else {
                    lastReadErr = errno;
                    if (lastReadErr == ESRCH) {
                        detach();
                        return EngineErrc::ERR_SRCH;
                    }
                }
            }

            if (readOk) {
                verifiedAddrs.push_back(probeAddr);
                if (verifiedAddrs.size() >= 6) break;
            } else if (m_memFd < 0 && pvmBlocked) {
                break;
            }
        }
    }

    // Never falsely report OK if no memory can actually be read from the target process
    if (verifiedAddrs.empty()) {
        if (m_memFd < 0 || !probeAddrs.empty() || !mapsReadable) {
            EngineErrc errc = (lastReadErr != 0) ? mapErrnoToErrc(lastReadErr) : EngineErrc::ERR_PERM;
            detach();
            return (errc == EngineErrc::OK) ? EngineErrc::ERR_PERM : errc;
        }
    }

    // Open /proc/[pid]/pagemap and validate cross-process residency reporting against the EXACT
    // proven-readable pages of the target process (never /proc/self/pagemap).
    char pagemapPath[64];
    std::snprintf(pagemapPath, sizeof(pagemapPath), "/proc/%d/pagemap", pid);
    m_pagemapFd = ::open(pagemapPath, O_RDONLY | O_CLOEXEC);
    if (m_pagemapFd >= 0 && !verifiedAddrs.empty()) {
        constexpr uint64_t kPresentOrSwapped = (1ULL << 63) | (1ULL << 62);
        bool targetPagemapOk = false;
        for (uintptr_t vAddr : verifiedAddrs) {
            uintptr_t canonPage = ArchPtrPolicy::untag(vAddr, m_targetIs64Bit) / m_pageSize;
            uint64_t pte = 0;
            ssize_t n = KT_EINTR_RETRY(
                ::pread64(m_pagemapFd, &pte, sizeof(uint64_t), static_cast<off64_t>(canonPage * sizeof(uint64_t)))
            );
            if (n == sizeof(uint64_t) && (pte & kPresentOrSwapped) != 0) {
                targetPagemapOk = true;
                break;
            }
        }
        m_pagemapUsable.store(targetPagemapOk, std::memory_order_relaxed);
    }

    return EngineErrc::OK;
}

void ProcIO::detach() noexcept {
    if (m_pagemapFd >= 0) {
        ::close(m_pagemapFd);
        m_pagemapFd = -1;
    }
    if (m_memFd >= 0) {
        ::close(m_memFd);
        m_memFd = -1;
    }
    m_pagemapUsable.store(false, std::memory_order_relaxed);
    m_pid = -1;
}

EngineErrc ProcIO::readMemory(uintptr_t addr, void* buf, size_t size, size_t* outBytesRead) const noexcept {
    if (outBytesRead) *outBytesRead = 0;
    if (m_pid <= 0 || !buf) return EngineErrc::ERR_INVAL;
    if (size == 0) return EngineErrc::OK;
    const uintptr_t effAddr = static_cast<uintptr_t>(ArchPtrPolicy::untag(addr, m_targetIs64Bit));
    if (effAddr + size < effAddr) return EngineErrc::ERR_INVAL;

    if (m_pvmSupported.load(std::memory_order_relaxed)) {
        struct iovec localIov{buf, size};
        struct iovec remoteIov{reinterpret_cast<void*>(effAddr), size};
        ssize_t nread = KT_EINTR_RETRY(::process_vm_readv(m_pid, &localIov, 1, &remoteIov, 1, 0));
        if (nread == static_cast<ssize_t>(size)) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::OK;
        }
        if (nread > 0) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::ERR_FAULT;
        }
        int err = errno;
        if (err == ENOSYS) {
            m_pvmSupported.store(false, std::memory_order_relaxed);
        } else if (err == ESRCH) {
            return EngineErrc::ERR_SRCH;
        } else if (err == EPERM || err == EACCES) {
            if (m_memFd < 0) return EngineErrc::ERR_PERM;
            m_pvmSupported.store(false, std::memory_order_relaxed);
        }
    }

    if (m_memFd >= 0) {
        ssize_t nread = KT_EINTR_RETRY(::pread64(m_memFd, buf, size, static_cast<off64_t>(effAddr)));
        if (nread == static_cast<ssize_t>(size)) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::OK;
        }
        if (nread > 0) {
            if (outBytesRead) *outBytesRead = static_cast<size_t>(nread);
            return EngineErrc::ERR_FAULT;
        }
        return mapErrnoToErrc(errno);
    }

    return EngineErrc::ERR_FAULT;
}

EngineErrc ProcIO::writeMemory(uintptr_t addr, const void* buf, size_t size) const noexcept {
    if (m_pid <= 0 || !buf) return EngineErrc::ERR_INVAL;
    if (size == 0) return EngineErrc::OK;
    const uintptr_t effAddr = static_cast<uintptr_t>(ArchPtrPolicy::untag(addr, m_targetIs64Bit));
    if (effAddr + size < effAddr) return EngineErrc::ERR_INVAL;

    int lastErr = EFAULT;

    // 1. Try fast process_vm_writev for writable pages
    if (m_pvmSupported.load(std::memory_order_relaxed)) {
        struct iovec localIov{const_cast<void*>(buf), size};
        struct iovec remoteIov{reinterpret_cast<void*>(effAddr), size};
        ssize_t nwritten = KT_EINTR_RETRY(::process_vm_writev(m_pid, &localIov, 1, &remoteIov, 1, 0));
        if (nwritten == static_cast<ssize_t>(size)) {
            return EngineErrc::OK;
        }
        lastErr = errno;
        if (lastErr == ENOSYS) {
            m_pvmSupported.store(false, std::memory_order_relaxed);
        } else if (lastErr == ESRCH) {
            return EngineErrc::ERR_SRCH;
        }
        // Note: Do NOT abort on EFAULT/EACCES before trying /proc/[pid]/mem pwrite64!
        // process_vm_writev fails on read-only code/const pages (Xa/Xs/Cd), whereas
        // /proc/[pid]/mem pwrite64 uses FOLL_FORCE in the kernel to write via COW.
    }

    // 2. Fallback to /proc/[pid]/mem pwrite64 (supports both RW and read-only COW pages)
    if (m_memFd >= 0) {
        size_t totalWritten = 0;
        const uint8_t* src = static_cast<const uint8_t*>(buf);
        while (totalWritten < size) {
            ssize_t nwritten = KT_EINTR_RETRY(
                ::pwrite64(m_memFd, src + totalWritten, size - totalWritten, static_cast<off64_t>(effAddr + totalWritten))
            );
            if (nwritten <= 0) {
                return mapErrnoToErrc(errno);
            }
            totalWritten += static_cast<size_t>(nwritten);
        }
        return EngineErrc::OK;
    }

    return mapErrnoToErrc(lastErr);
}

EngineErrc ProcIO::readChunkFaultTolerant(
    uintptr_t startAddr,
    uint8_t* buf,
    size_t size,
    std::vector<uint8_t>& outValidPageMask,
    size_t* outReadableBytes,
    bool usePagemap
) const {
    if (outReadableBytes) *outReadableBytes = 0;
    if (m_pid <= 0 || !buf || size == 0) return EngineErrc::ERR_INVAL;
    if (startAddr + size < startAddr) return EngineErrc::ERR_INVAL;

    const size_t ps = m_pageSize;
    uintptr_t firstPage = (startAddr / ps) * ps;
    uintptr_t endAddr   = startAddr + size;
    uintptr_t lastPage  = ((endAddr + ps - 1) / ps) * ps;
    size_t numPages     = (lastPage - firstPage) / ps;

    outValidPageMask.assign(numPages, 1);

    // 1. Pagemap resident-page & shared-zero-page pre-filtering (only when verified for target pid)
    if (usePagemap && m_pagemapFd >= 0 && m_pagemapUsable.load(std::memory_order_relaxed)) {
        uintptr_t canonicalStart = ArchPtrPolicy::untag(firstPage, m_targetIs64Bit);
        off64_t pmOffset = static_cast<off64_t>((canonicalStart / ps) * sizeof(uint64_t));
        std::vector<uint64_t> ptes(numPages, 0);
        ssize_t pmRead = KT_EINTR_RETRY(::pread64(m_pagemapFd, ptes.data(), numPages * sizeof(uint64_t), pmOffset));
        if (pmRead == static_cast<ssize_t>(numPages * sizeof(uint64_t))) {
            constexpr uint64_t kPresentMask      = (1ULL << 63);
            constexpr uint64_t kPresentOrSwapped = (1ULL << 63) | (1ULL << 62);
            constexpr uint64_t kPfnMask          = (1ULL << 55) - 1ULL;
            size_t residentPages = 0;

            for (size_t p = 0; p < numPages; ++p) {
                const uint64_t pte = ptes[p];
                bool isPopulated = (pte & kPresentOrSwapped) != 0;
                // Exclude shared kernel empty_zero_page when PFN is exposed
                if (isPopulated && m_zeroPagePfn != 0 && (pte & kPresentMask) != 0) {
                    uint64_t pfn = pte & kPfnMask;
                    if (pfn == m_zeroPagePfn) {
                        isPopulated = false;
                    }
                }
                if (isPopulated) {
                    outValidPageMask[p] = 1;
                    residentPages++;
                } else {
                    outValidPageMask[p] = 0;
                }
            }

            if (residentPages == 0) {
                if (outReadableBytes) *outReadableBytes = 0;
                return EngineErrc::OK;
            }

            if (residentPages < numPages) {
                size_t totalValidBytes = 0;
                size_t p = 0;
                while (p < numPages) {
                    if (outValidPageMask[p] == 0) {
                        uintptr_t pageStart = std::max(startAddr, firstPage + p * ps);
                        uintptr_t pageEnd   = std::min(endAddr, firstPage + (p + 1) * ps);
                        if (pageEnd > pageStart) {
                            std::memset(buf + (pageStart - startAddr), 0, pageEnd - pageStart);
                        }
                        p++;
                        continue;
                    }
                    size_t runStart = p;
                    while (p < numPages && outValidPageMask[p] != 0) p++;
                    uintptr_t runAddr = std::max(startAddr, firstPage + runStart * ps);
                    uintptr_t runEnd  = std::min(endAddr, firstPage + p * ps);
                    if (runEnd <= runAddr) continue;
                    size_t runSize = runEnd - runAddr;
                    size_t runBufOff = runAddr - startAddr;
                    size_t runRead = 0;
                    EngineErrc rrc = readMemory(runAddr, buf + runBufOff, runSize, &runRead);
                    if (rrc == EngineErrc::OK) {
                        totalValidBytes += runSize;
                    } else if (rrc == EngineErrc::ERR_PERM || rrc == EngineErrc::ERR_SRCH) {
                        return rrc;
                    } else {
                        for (size_t sp = runStart; sp < p; ++sp) {
                            uintptr_t spAddr = std::max(startAddr, firstPage + sp * ps);
                            uintptr_t spEnd  = std::min(endAddr, firstPage + (sp + 1) * ps);
                            size_t spLen = spEnd - spAddr;
                            size_t spOff = spAddr - startAddr;
                            if (readMemory(spAddr, buf + spOff, spLen) == EngineErrc::OK) {
                                totalValidBytes += spLen;
                            } else {
                                outValidPageMask[sp] = 0;
                                std::memset(buf + spOff, 0, spLen);
                            }
                        }
                    }
                }
                if (outReadableBytes) *outReadableBytes = totalValidBytes;
                return totalValidBytes > 0 ? EngineErrc::OK : EngineErrc::ERR_FAULT;
            }
        }
    }

    // 2. Adaptive Two-State Transfer (Normal <-> Page, inspired by KittyMemoryEx KittyMemoryTransfer)
    enum class TransferState { Normal, Page };
    TransferState state = TransferState::Normal;
    size_t totalValidBytes = 0;
    size_t contiguousPageSuccess = 0;
    size_t curOffset = 0;

    while (curOffset < size) {
        uintptr_t curAddr = startAddr + curOffset;
        size_t rem = size - curOffset;

        if (state == TransferState::Normal) {
            size_t nread = 0;
            EngineErrc rc = readMemory(curAddr, buf + curOffset, rem, &nread);
            if (rc == EngineErrc::OK) {
                totalValidBytes += rem;
                break;
            }
            if (rc == EngineErrc::ERR_PERM || rc == EngineErrc::ERR_SRCH) {
                outValidPageMask.assign(numPages, 0);
                return rc;
            }
            if (nread > 0) {
                totalValidBytes += nread;
                curOffset += nread;
                if (curOffset >= size) break;
            }
            // Switch to Page state to step across unmapped / guard pages
            state = TransferState::Page;
            contiguousPageSuccess = 0;
            continue;
        }

        // TransferState::Page: read up to next page boundary
        size_t chunkToRead = std::min(pageRemain(curAddr, ps), rem);
        size_t nread = 0;
        EngineErrc rc = readMemory(curAddr, buf + curOffset, chunkToRead, &nread);
        if (rc == EngineErrc::OK && nread == chunkToRead) {
            totalValidBytes += chunkToRead;
            curOffset += chunkToRead;
            contiguousPageSuccess += chunkToRead;
            if (contiguousPageSuccess >= RETRY_NORMAL_TRANSFER_BYTES) {
                state = TransferState::Normal;
                contiguousPageSuccess = 0;
            }
        } else if (rc == EngineErrc::ERR_PERM || rc == EngineErrc::ERR_SRCH) {
            return rc;
        } else {
            size_t pageIdx = (curAddr - firstPage) / ps;
            if (pageIdx < numPages) {
                outValidPageMask[pageIdx] = 0;
            }
            std::memset(buf + curOffset, 0, chunkToRead);
            curOffset += chunkToRead;
            contiguousPageSuccess = 0;
        }
    }

    if (outReadableBytes) *outReadableBytes = totalValidBytes;
    return totalValidBytes > 0 ? EngineErrc::OK : EngineErrc::ERR_FAULT;
}

size_t ProcIO::writeBatchScattered(std::vector<BatchWriteRange>& ranges) const noexcept {
    if (m_pid <= 0 || ranges.empty()) return 0;

    const size_t maxBatch = m_iovMax;
    std::vector<struct iovec> localIovs;
    std::vector<struct iovec> remoteIovs;
    localIovs.reserve(std::min(ranges.size(), maxBatch));
    remoteIovs.reserve(std::min(ranges.size(), maxBatch));

    size_t totalOk = 0;
    size_t idx = 0;
    while (idx < ranges.size()) {
        localIovs.clear();
        remoteIovs.clear();
        size_t batchStart = idx;
        size_t expectedBytes = 0;

        while (idx < ranges.size() && localIovs.size() < maxBatch) {
            auto& r = ranges[idx];
            if (r.length > 0 && r.localBuf != nullptr) {
                const uintptr_t effAddr = static_cast<uintptr_t>(ArchPtrPolicy::untag(r.remoteAddr, m_targetIs64Bit));
                localIovs.push_back({const_cast<uint8_t*>(r.localBuf), r.length});
                remoteIovs.push_back({reinterpret_cast<void*>(effAddr), r.length});
                expectedBytes += r.length;
            }
            ++idx;
        }
        if (localIovs.empty()) continue;

        ssize_t nwritten = -1;
        if (m_pvmSupported.load(std::memory_order_relaxed)) {
            nwritten = KT_EINTR_RETRY(::process_vm_writev(
                m_pid,
                localIovs.data(),
                static_cast<unsigned long>(localIovs.size()),
                remoteIovs.data(),
                static_cast<unsigned long>(remoteIovs.size()),
                0
            ));
        }

        if (nwritten == static_cast<ssize_t>(expectedBytes)) {
            for (size_t k = batchStart; k < idx; ++k) {
                if (ranges[k].length > 0 && ranges[k].localBuf != nullptr) {
                    ranges[k].success = true;
                    ++totalOk;
                }
            }
        } else {
            // Fallback per-item (handles read-only COW code pages via /proc/[pid]/mem pwrite64)
            for (size_t k = batchStart; k < idx; ++k) {
                auto& r = ranges[k];
                if (r.length > 0 && r.localBuf != nullptr &&
                    writeMemory(r.remoteAddr, r.localBuf, r.length) == EngineErrc::OK) {
                    r.success = true;
                    ++totalOk;
                }
            }
        }
    }
    return totalOk;
}

EngineErrc ProcIO::readBatchScattered(std::vector<BatchReadRange>& ranges) const noexcept {
    if (m_pid <= 0) return EngineErrc::ERR_INVAL;
    if (ranges.empty()) return EngineErrc::OK;

    const size_t maxBatch = m_iovMax;
    std::vector<struct iovec> localIovs;
    std::vector<struct iovec> remoteIovs;
    localIovs.reserve(std::min(ranges.size(), maxBatch));
    remoteIovs.reserve(std::min(ranges.size(), maxBatch));

    size_t idx = 0;
    while (idx < ranges.size()) {
        localIovs.clear();
        remoteIovs.clear();
        size_t batchStart = idx;
        size_t expectedBytes = 0;

        while (idx < ranges.size() && localIovs.size() < maxBatch) {
            auto& r = ranges[idx];
            if (r.length > 0 && r.localBuf != nullptr) {
                localIovs.push_back({r.localBuf, r.length});
                remoteIovs.push_back({reinterpret_cast<void*>(r.remoteAddr), r.length});
                expectedBytes += r.length;
            }
            ++idx;
        }

        if (localIovs.empty()) continue;

        ssize_t nread = -1;
        if (m_pvmSupported.load(std::memory_order_relaxed)) {
            nread = KT_EINTR_RETRY(::process_vm_readv(
                m_pid,
                localIovs.data(),
                static_cast<unsigned long>(localIovs.size()),
                remoteIovs.data(),
                static_cast<unsigned long>(remoteIovs.size()),
                0
            ));
        }

        if (nread == static_cast<ssize_t>(expectedBytes)) {
            for (size_t k = batchStart; k < idx; ++k) {
                ranges[k].success = true;
            }
        } else {
            for (size_t k = batchStart; k < idx; ++k) {
                auto& r = ranges[k];
                r.success = (readMemory(r.remoteAddr, r.localBuf, r.length) == EngineErrc::OK);
            }
        }
    }

    return EngineErrc::OK;
}

// ============================================================================
// ValueLocker Implementation (Unified Multi-Mode C++ Worker)
// ============================================================================
ValueLocker::ValueLocker(ProcIO& io, uint32_t intervalMs)
    : m_io(io), m_intervalMs(std::max(1u, intervalMs)), m_paused(false), m_running(false), m_started(false) {}

ValueLocker::~ValueLocker() {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_running = false;
    }
    m_cv.notify_all();
    if (m_worker.joinable()) {
        m_worker.join();
    }
}

void ValueLocker::lockAddress(uintptr_t addr, const void* data, size_t size) {
    DataType inferredType = DataType::INT32;
    if (size == 1) inferredType = DataType::INT8;
    else if (size == 2) inferredType = DataType::INT16;
    else if (size == 8) inferredType = DataType::INT64;
    lockAddressConfigured(addr, data, size, inferredType, FreezeMode::EXACT, 0, 0);
}

void ValueLocker::lockAddressConfigured(
    uintptr_t addr,
    const void* data,
    size_t size,
    DataType type,
    FreezeMode mode,
    int64_t minVal,
    int64_t maxVal
) {
    if (!data || size == 0) return;
    if (mode == FreezeMode::EXACT) {
        m_io.writeMemory(addr, data, size);
    }
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        LockedItem item;
        item.addr   = addr;
        item.bytes.assign(static_cast<const uint8_t*>(data), static_cast<const uint8_t*>(data) + size);
        item.type   = type;
        item.mode   = mode;
        item.minVal = minVal;
        item.maxVal = maxVal;
        m_items[addr] = std::move(item);
        if (!m_started) {
            m_running = true;
            m_started = true;
            m_worker = std::thread(&ValueLocker::workerLoop, this);
        }
    }
    m_cv.notify_one();
}

void ValueLocker::unlockAddress(uintptr_t addr) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_items.erase(addr);
}

void ValueLocker::unlockAll() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_items.clear();
}

void ValueLocker::setIntervalMs(uint32_t intervalMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_intervalMs = std::max(1u, intervalMs);
    m_cv.notify_one();
}

uint32_t ValueLocker::intervalMs() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_intervalMs;
}

void ValueLocker::setPaused(bool paused) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_paused = paused;
    m_cv.notify_one();
}

void ValueLocker::applyFreezeStep(LockedItem& item) {
    if (item.bytes.empty()) return;

    if (item.mode == FreezeMode::EXACT) {
        m_io.writeMemory(item.addr, item.bytes.data(), item.bytes.size());
        return;
    }

    const int64_t stepDir = (item.mode == FreezeMode::INCREMENT) ? 1 : -1;

    switch (item.type) {
        case DataType::INT8: {
            int8_t v = 0;
            std::memcpy(&v, item.bytes.data(), 1);
            if (item.mode == FreezeMode::RANGE_CLAMP) {
                if (m_io.readValue(item.addr, v) == EngineErrc::OK) {
                    int8_t lo = static_cast<int8_t>(item.minVal);
                    int8_t hi = static_cast<int8_t>(item.maxVal);
                    if (v < lo) { v = lo; m_io.writeValue(item.addr, v); }
                    else if (v > hi) { v = hi; m_io.writeValue(item.addr, v); }
                }
            } else {
                v = static_cast<int8_t>(v + stepDir);
                std::memcpy(item.bytes.data(), &v, 1);
                m_io.writeValue(item.addr, v);
            }
            break;
        }
        case DataType::INT16: {
            if (item.bytes.size() < 2) break;
            int16_t v = 0;
            std::memcpy(&v, item.bytes.data(), 2);
            if (item.mode == FreezeMode::RANGE_CLAMP) {
                if (m_io.readValue(item.addr, v) == EngineErrc::OK) {
                    int16_t lo = static_cast<int16_t>(item.minVal);
                    int16_t hi = static_cast<int16_t>(item.maxVal);
                    if (v < lo) { v = lo; m_io.writeValue(item.addr, v); }
                    else if (v > hi) { v = hi; m_io.writeValue(item.addr, v); }
                }
            } else {
                v = static_cast<int16_t>(v + stepDir);
                std::memcpy(item.bytes.data(), &v, 2);
                m_io.writeValue(item.addr, v);
            }
            break;
        }
        case DataType::INT32: {
            if (item.bytes.size() < 4) break;
            int32_t v = 0;
            std::memcpy(&v, item.bytes.data(), 4);
            if (item.mode == FreezeMode::RANGE_CLAMP) {
                if (m_io.readValue(item.addr, v) == EngineErrc::OK) {
                    int32_t lo = static_cast<int32_t>(item.minVal);
                    int32_t hi = static_cast<int32_t>(item.maxVal);
                    if (v < lo) { v = lo; m_io.writeValue(item.addr, v); }
                    else if (v > hi) { v = hi; m_io.writeValue(item.addr, v); }
                }
            } else {
                v = static_cast<int32_t>(v + stepDir);
                std::memcpy(item.bytes.data(), &v, 4);
                m_io.writeValue(item.addr, v);
            }
            break;
        }
        case DataType::INT64: {
            if (item.bytes.size() < 8) break;
            int64_t v = 0;
            std::memcpy(&v, item.bytes.data(), 8);
            if (item.mode == FreezeMode::RANGE_CLAMP) {
                if (m_io.readValue(item.addr, v) == EngineErrc::OK) {
                    if (v < item.minVal) { v = item.minVal; m_io.writeValue(item.addr, v); }
                    else if (v > item.maxVal) { v = item.maxVal; m_io.writeValue(item.addr, v); }
                }
            } else {
                v += stepDir;
                std::memcpy(item.bytes.data(), &v, 8);
                m_io.writeValue(item.addr, v);
            }
            break;
        }
        case DataType::FLOAT: {
            if (item.bytes.size() < 4) break;
            float v = 0.0f;
            std::memcpy(&v, item.bytes.data(), 4);
            if (item.mode == FreezeMode::RANGE_CLAMP) {
                if (m_io.readValue(item.addr, v) == EngineErrc::OK && !std::isnan(v)) {
                    float lo = static_cast<float>(item.minVal);
                    float hi = static_cast<float>(item.maxVal);
                    if (v < lo) { v = lo; m_io.writeValue(item.addr, v); }
                    else if (v > hi) { v = hi; m_io.writeValue(item.addr, v); }
                }
            } else {
                v += static_cast<float>(stepDir);
                std::memcpy(item.bytes.data(), &v, 4);
                m_io.writeValue(item.addr, v);
            }
            break;
        }
        case DataType::DOUBLE: {
            if (item.bytes.size() < 8) break;
            double v = 0.0;
            std::memcpy(&v, item.bytes.data(), 8);
            if (item.mode == FreezeMode::RANGE_CLAMP) {
                if (m_io.readValue(item.addr, v) == EngineErrc::OK && !std::isnan(v)) {
                    double lo = static_cast<double>(item.minVal);
                    double hi = static_cast<double>(item.maxVal);
                    if (v < lo) { v = lo; m_io.writeValue(item.addr, v); }
                    else if (v > hi) { v = hi; m_io.writeValue(item.addr, v); }
                }
            } else {
                v += static_cast<double>(stepDir);
                std::memcpy(item.bytes.data(), &v, 8);
                m_io.writeValue(item.addr, v);
            }
            break;
        }
        default: {
            m_io.writeMemory(item.addr, item.bytes.data(), item.bytes.size());
            break;
        }
    }
}

void ValueLocker::workerLoop() {
    std::unique_lock<std::mutex> lock(m_mutex);
    while (m_running) {
        if (m_items.empty() || m_paused) {
            m_cv.wait(lock, [this]() { return !m_running || (!m_items.empty() && !m_paused); });
            if (!m_running) break;
        }

        std::vector<LockedItem> snapshot;
        snapshot.reserve(m_items.size());
        for (const auto& kv : m_items) {
            snapshot.push_back(kv.second);
        }
        uint32_t sleepMs = m_intervalMs;

        lock.unlock();
        for (auto& item : snapshot) {
            applyFreezeStep(item);
        }
        lock.lock();

        // Persist updated INCREMENT/DECREMENT step values back into m_items if still locked
        for (const auto& updated : snapshot) {
            if (updated.mode == FreezeMode::INCREMENT || updated.mode == FreezeMode::DECREMENT) {
                auto it = m_items.find(updated.addr);
                if (it != m_items.end() && it->second.mode == updated.mode) {
                    it->second.bytes = updated.bytes;
                }
            }
        }

        if (!m_running) break;
        m_cv.wait_for(lock, std::chrono::milliseconds(sleepMs));
    }
}

} // namespace mem_engine
