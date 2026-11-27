#include "mem_engine/maps_parser.hpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <algorithm>
#include <fstream>
#include <sstream>

namespace mem_engine {

namespace {

inline bool startsWith(const std::string& s, const char* prefix) noexcept {
    size_t plen = std::strlen(prefix);
    return s.size() >= plen && std::memcmp(s.data(), prefix, plen) == 0;
}

inline bool containsStr(const std::string& s, const char* sub) noexcept {
    return s.find(sub) != std::string::npos;
}

inline bool endsWith(const std::string& s, const char* suffix) noexcept {
    size_t slen = std::strlen(suffix);
    return s.size() >= slen && std::memcmp(s.data() + s.size() - slen, suffix, slen) == 0;
}

inline std::string stripDeleted(const std::string& raw) {
    const char* delSuffix = " (deleted)";
    size_t dlen = std::strlen(delSuffix);
    if (raw.size() >= dlen && raw.compare(raw.size() - dlen, dlen, delSuffix) == 0) {
        return raw.substr(0, raw.size() - dlen);
    }
    return raw;
}

} // anonymous namespace

// ============================================================================
// VmaIndex Implementation (Inspired by KittyPtrValidator & frida-gum)
// ============================================================================
VmaIndex::VmaIndex(std::vector<VmaRegion> regions) {
    rebuild(std::move(regions));
}

VmaIndex::VmaIndex(const VmaIndex& other)
    : m_regions(other.m_regions),
      m_lastHitIdx(other.m_lastHitIdx.load(std::memory_order_relaxed)) {}

VmaIndex& VmaIndex::operator=(const VmaIndex& other) {
    if (this != &other) {
        m_regions = other.m_regions;
        m_lastHitIdx.store(other.m_lastHitIdx.load(std::memory_order_relaxed), std::memory_order_relaxed);
    }
    return *this;
}

VmaIndex::VmaIndex(VmaIndex&& other) noexcept
    : m_regions(std::move(other.m_regions)),
      m_lastHitIdx(other.m_lastHitIdx.load(std::memory_order_relaxed)) {
    other.m_lastHitIdx.store(0, std::memory_order_relaxed);
}

VmaIndex& VmaIndex::operator=(VmaIndex&& other) noexcept {
    if (this != &other) {
        m_regions = std::move(other.m_regions);
        m_lastHitIdx.store(other.m_lastHitIdx.load(std::memory_order_relaxed), std::memory_order_relaxed);
        other.m_lastHitIdx.store(0, std::memory_order_relaxed);
    }
    return *this;
}

void VmaIndex::rebuild(std::vector<VmaRegion> regions) {
    m_regions = std::move(regions);
    if (m_regions.size() > 1) {
        auto cmp = [](const VmaRegion& a, const VmaRegion& b) noexcept {
            return a.startAddress < b.startAddress;
        };
        if (!std::is_sorted(m_regions.begin(), m_regions.end(), cmp)) {
            std::sort(m_regions.begin(), m_regions.end(), cmp);
        }
    }
    m_lastHitIdx.store(0, std::memory_order_relaxed);
}

void VmaIndex::clear() noexcept {
    m_regions.clear();
    m_lastHitIdx.store(0, std::memory_order_relaxed);
}

const VmaRegion* VmaIndex::findRegion(uintptr_t rawAddr, bool is64Bit) const noexcept {
    if (m_regions.empty()) return nullptr;
    const uintptr_t addr = static_cast<uintptr_t>(ArchPtrPolicy::untag(rawAddr, is64Bit));

    // 1. Check MRU cached region index first (O(1) sequential locality hit)
    size_t hint = m_lastHitIdx.load(std::memory_order_relaxed);
    if (hint < m_regions.size() && m_regions[hint].contains(addr)) {
        return &m_regions[hint];
    }
    if (hint + 1 < m_regions.size() && m_regions[hint + 1].contains(addr)) {
        m_lastHitIdx.store(hint + 1, std::memory_order_relaxed);
        return &m_regions[hint + 1];
    }

    // 2. Binary search via std::lower_bound (O(log N))
    auto it = std::lower_bound(
        m_regions.begin(),
        m_regions.end(),
        addr,
        [](const VmaRegion& r, uintptr_t a) noexcept {
            return r.endAddress <= a;
        }
    );

    if (it != m_regions.end() && it->contains(addr)) {
        size_t idx = static_cast<size_t>(it - m_regions.begin());
        m_lastHitIdx.store(idx, std::memory_order_relaxed);
        return &(*it);
    }
    return nullptr;
}

uint32_t VmaIndex::classifyPointer(uintptr_t rawPtrVal, bool is64Bit) const noexcept {
    if (!ArchPtrPolicy::isCanonicalUserPtr(rawPtrVal, is64Bit)) {
        return PointerProtMask::NONE;
    }
    const uintptr_t addr = static_cast<uintptr_t>(ArchPtrPolicy::untag(rawPtrVal, is64Bit));
    const VmaRegion* r = findRegion(addr, false);
    if (!r) return PointerProtMask::NONE;
    return r->pointerProtClass();
}

const char* VmaIndex::regionTagAt(uintptr_t rawAddr, bool is64Bit) const noexcept {
    const VmaRegion* r = findRegion(rawAddr, is64Bit);
    return r ? regionFlagToTag(r->regionFlag) : "O";
}

uintptr_t VmaIndex::firstRegionBase(uint32_t regionFlag) const noexcept {
    for (const auto& r : m_regions) {
        if (!r.readable || r.regionFlag == RegionFlag::B) continue;
        if (regionFlag == 0 || (r.regionFlag & regionFlag) != 0) {
            return r.startAddress;
        }
    }
    return 0;
}

// ============================================================================
// MapsParser Implementation
// ============================================================================
uint32_t MapsParser::classifyVma(const VmaRegion& vma, const std::string& prevFileBackedPath) {
    if (vma.length == 0) {
        return RegionFlag::B;
    }

    const std::string path = stripDeleted(vma.pathname);

    // 1. PPSSPP Emulator Regions (Ps)
    if (vma.readable && (containsStr(path, "ppsspp") || containsStr(path, "PPSSPP") ||
        containsStr(path, "psp_ram") || containsStr(path, "psp_vram") ||
        startsWith(path, "/dev/shm/ppsspp"))) {
        return RegionFlag::PS;
    }

    // 2. Bad (B): Fonts, ICU/Hyphenation Data (~137 MB), Kernel Trap Pages, Binder & Special Device Nodes
    if (endsWith(path, ".ttf") || endsWith(path, ".otf") || endsWith(path, ".ttc") || endsWith(path, ".woff") ||
        containsStr(path, "/system/fonts/") || containsStr(path, "/product/fonts/") || containsStr(path, "/vendor/fonts/") ||
        startsWith(path, "/system/usr/icu/") || startsWith(path, "/apex/com.android.i18n/etc/icu/") ||
        (containsStr(path, "/icu/") && endsWith(path, ".dat")) ||
        startsWith(path, "/system/usr/hyphen-data/") || endsWith(path, ".hyb") ||
        path == "[vvar]" || path == "[vectors]" || path == "[sigpage]" || path == "[uprobes]" || path == "[kuserhelpers]" ||
        startsWith(path, "/dev/binder") || startsWith(path, "/dev/hwbinder") ||
        startsWith(path, "/dev/vndbinder") || startsWith(path, "/dev/pmsg") ||
        startsWith(path, "/dev/kmsg") || startsWith(path, "/dev/null") ||
        startsWith(path, "/dev/urandom") || startsWith(path, "/dev/random") ||
        startsWith(path, "/dev/cpuctl") || startsWith(path, "/dev/cpuset") ||
        startsWith(path, "/dev/stune") || startsWith(path, "/dev/blkio") ||
        startsWith(path, "[anon:GWP-ASan Guard") || startsWith(path, "[anon:guard")) {
        return RegionFlag::B;
    }

    // 3. Unreadable / PROT_NONE virtual address reservations (---p) -> O (Other)
    if (!vma.readable) {
        return RegionFlag::O;
    }

    // 4. Video / GPU / DMA-BUF / ION / KGSL / Gralloc (V)
    if (startsWith(path, "/dev/kgsl") || containsStr(path, "kgsl-3d0") ||
        startsWith(path, "/dev/mali") || startsWith(path, "/dev/ion") ||
        startsWith(path, "/dev/dma_heap") || startsWith(path, "/dev/dri/") ||
        startsWith(path, "/dev/nvidia") || startsWith(path, "/dev/pvrsrvkm") ||
        startsWith(path, "/dev/galcore") || containsStr(path, "gralloc") ||
        startsWith(path, "/dmabuf:") || startsWith(path, "[anon:dmabuf")) {
        return RegionFlag::V;
    }

    // 5. Executable Code Regions: App Code (Xa) vs System Code (Xs)
    if (vma.executable) {
        if (path == "[vdso]" || path == "[vsyscall]" ||
            startsWith(path, "/memfd:jit-") ||
            containsStr(path, "jit-code-cache") ||
            containsStr(path, "jit-zygote-cache")) {
            return RegionFlag::XS;
        }
        if (startsWith(path, "/data/dalvik-cache/")) {
            if (containsStr(path, "data@app@") || containsStr(path, "mnt@expand@") || containsStr(path, "data@data@")) {
                return RegionFlag::XA;
            }
            return RegionFlag::XS;
        }
        bool isAppExec = startsWith(path, "/data/app/") ||
                         startsWith(path, "/data/data/") ||
                         startsWith(path, "/data/user/") ||
                         startsWith(path, "/data/user_de/") ||
                         startsWith(path, "/mnt/asec/") ||
                         startsWith(path, "/mnt/expand/") ||
                         startsWith(path, "/sdcard/") ||
                         startsWith(path, "/storage/") ||
                         (path.empty() && vma.writable && vma.isPrivate);
        return isAppExec ? RegionFlag::XA : RegionFlag::XS;
    }

    // 6. Stack (S)
    if (path == "[stack]" || startsWith(path, "[stack:") ||
        startsWith(path, "[anon:stack_and_tls") || startsWith(path, "[anon:thread signal stack")) {
        return RegionFlag::S;
    }

    // 7. Native C/C++ Heap (Ch)
    if (path == "[heap]" || startsWith(path, "[heap:") || startsWith(path, "[anon:heap")) {
        return RegionFlag::CH;
    }

    // 8. Java Heap (Jh) vs Java Runtime Structures (J)
    if (startsWith(path, "/memfd:jit-")) {
        return RegionFlag::J;
    }
    if (containsStr(path, "dalvik-") || startsWith(path, "[anon:dalvik-") ||
        startsWith(path, "/dev/ashmem/dalvik-") || startsWith(path, "/memfd:dalvik-") ||
        startsWith(path, "[anon:art_") ||
        containsStr(path, "main space") || containsStr(path, "alloc space") ||
        containsStr(path, "region space") || containsStr(path, "large object space") ||
        containsStr(path, "non moving space") || containsStr(path, "zygote space") ||
        containsStr(path, "bump-pointer space") || containsStr(path, "rosalloc space") ||
        containsStr(path, "dlmalloc space") || containsStr(path, "free list large") ||
        containsStr(path, "image space")) {
        bool isDalvikInternalMeta =
            containsStr(path, "bitmap") ||
            containsStr(path, "mark-stack") ||
            containsStr(path, "allocation-stack") ||
            containsStr(path, "live-stack") ||
            containsStr(path, "card-table") ||
            containsStr(path, "card table") ||
            containsStr(path, "mod-union") ||
            containsStr(path, "LinearAlloc") ||
            containsStr(path, "indirect-ref") ||
            containsStr(path, "reference table") ||
            containsStr(path, "aux-structure") ||
            containsStr(path, "CompilerMetadata") ||
            containsStr(path, "jit") ||
            containsStr(path, "image space") ||
            containsStr(path, "intern table") ||
            containsStr(path, "class table");

        bool isJavaObjectAllocHeap =
            containsStr(path, "main space") ||
            containsStr(path, "alloc space") ||
            containsStr(path, "region space") ||
            containsStr(path, "large object") ||
            containsStr(path, "non moving space") ||
            containsStr(path, "zygote space") ||
            containsStr(path, "bump-pointer") ||
            containsStr(path, "rosalloc") ||
            containsStr(path, "dlmalloc") ||
            containsStr(path, "free list");

        if (vma.writable && isJavaObjectAllocHeap && !isDalvikInternalMeta) {
            return RegionFlag::JH;
        }
        return RegionFlag::J;
    }
    if (vma.writable && (endsWith(path, ".art") || endsWith(path, ".vdex") || endsWith(path, ".dex") || containsStr(path, "classes.dex"))) {
        return RegionFlag::J;
    }

    // 9. C++ Allocator (Ca): Bionic libc_malloc, Scudo, Jemalloc, GWP-ASan
    if (startsWith(path, "[anon:libc_malloc") ||
        startsWith(path, "[anon:scudo:") ||
        startsWith(path, "[anon:jemalloc") ||
        startsWith(path, "[anon:GWP-ASan") ||
        startsWith(path, "[anon:malloc") ||
        startsWith(path, "[anon:bionic_alloc") ||
        startsWith(path, "[anon:mtk_malloc")) {
        return RegionFlag::CA;
    }

    // 10. C++ .bss Segment (Cb)
    if (path == "[anon:.bss]" || startsWith(path, "[anon:.bss") || startsWith(path, "[anon:bss")) {
        return RegionFlag::CB;
    }
    if (path.empty() && vma.writable && !vma.executable && vma.isPrivate && !prevFileBackedPath.empty()) {
        if (endsWith(prevFileBackedPath, ".so") || containsStr(prevFileBackedPath, ".so!") || endsWith(prevFileBackedPath, ".apk")) {
            return RegionFlag::CB;
        }
    }

    // 11. C++ .data Segment (Cd): Writable private segment of an ELF shared library (.so / .oat / .odex / uncompressed .apk)
    if (vma.writable && vma.isPrivate &&
        (endsWith(path, ".so") || containsStr(path, ".so!") || endsWith(path, ".oat") || endsWith(path, ".odex") || endsWith(path, ".apk"))) {
        return RegionFlag::CD;
    }

    // 12. Ashmem Shared Memory (As)
    if ((startsWith(path, "/dev/ashmem") && !containsStr(path, "dalvik") &&
         !containsStr(path, "jit") && !containsStr(path, "gralloc") && !containsStr(path, "GFX")) ||
        startsWith(path, "[anon:ashmem") || startsWith(path, "/memfd:ashmem")) {
        return RegionFlag::AS;
    }

    // 13. Pure Anonymous Memory (A)
    if (path.empty() || startsWith(path, "[anon:")) {
        if (vma.writable && vma.isPrivate) {
            return RegionFlag::A;
        }
        return RegionFlag::O;
    }

    return RegionFlag::O;
}

std::vector<VmaRegion> MapsParser::parseMapsContent(const std::string& content, uint32_t filterMask, bool skipUnreadable) {
    std::vector<VmaRegion> regions;
    regions.reserve(512);

    std::string prevFileBackedPath;
    size_t pos = 0;
    const size_t totalLen = content.size();

    while (pos < totalLen) {
        size_t lineEnd = content.find('\n', pos);
        if (lineEnd == std::string::npos) lineEnd = totalLen;

        const char* linePtr = content.data() + pos;
        size_t lineLen = lineEnd - pos;
        pos = lineEnd + 1;

        if (lineLen == 0) continue;

        char* endPtr = nullptr;
        unsigned long long start = std::strtoull(linePtr, &endPtr, 16);
        if (!endPtr || *endPtr != '-') continue;

        unsigned long long end = std::strtoull(endPtr + 1, &endPtr, 16);
        if (!endPtr || end <= start) continue;

        while (*endPtr == ' ' || *endPtr == '\t') ++endPtr;
        if (static_cast<size_t>(endPtr - linePtr) + 4 > lineLen) continue;

        char perms[5] = { endPtr[0], endPtr[1], endPtr[2], endPtr[3], '\0' };
        endPtr += 4;

        unsigned long long offset = std::strtoull(endPtr, &endPtr, 16);
        while (*endPtr == ' ' || *endPtr == '\t') ++endPtr;

        const char* devStart = endPtr;
        while (static_cast<size_t>(endPtr - linePtr) < lineLen && *endPtr != ' ' && *endPtr != '\t') ++endPtr;
        std::string devStr(devStart, static_cast<size_t>(endPtr - devStart));

        unsigned long long inode = std::strtoull(endPtr, &endPtr, 10);
        while (static_cast<size_t>(endPtr - linePtr) < lineLen && (*endPtr == ' ' || *endPtr == '\t')) ++endPtr;

        std::string pathname;
        if (static_cast<size_t>(endPtr - linePtr) < lineLen) {
            size_t pathLen = lineLen - static_cast<size_t>(endPtr - linePtr);
            while (pathLen > 0 && (endPtr[pathLen - 1] == '\r' || endPtr[pathLen - 1] == '\n' || endPtr[pathLen - 1] == ' ')) {
                --pathLen;
            }
            pathname.assign(endPtr, pathLen);
        }

        VmaRegion vma;
        vma.startAddress = static_cast<uintptr_t>(start);
        vma.endAddress   = static_cast<uintptr_t>(end);
        vma.length       = static_cast<size_t>(end - start);
        vma.fileOffset   = offset;
        vma.inode        = inode;
        vma.readable     = (perms[0] == 'r');
        vma.writable     = (perms[1] == 'w');
        vma.executable   = (perms[2] == 'x');
        vma.isPrivate    = (perms[3] == 'p');
        vma.isShared     = (perms[3] == 's');
        vma.dev          = std::move(devStr);
        vma.pathname     = std::move(pathname);

        vma.regionFlag   = classifyVma(vma, prevFileBackedPath);

        if (!vma.pathname.empty() && vma.pathname[0] == '/') {
            prevFileBackedPath = stripDeleted(vma.pathname);
        } else if (vma.regionFlag != RegionFlag::CB) {
            prevFileBackedPath.clear();
        }

        if (skipUnreadable && (!vma.readable || (vma.regionFlag == RegionFlag::B && (filterMask & RegionFlag::B) == 0))) {
            continue;
        }
        if (filterMask != 0 && (vma.regionFlag & filterMask) == 0) {
            continue;
        }

        regions.push_back(std::move(vma));
    }

    return regions;
}

std::vector<VmaRegion> MapsParser::parseProcessMaps(int pid, uint32_t filterMask, bool skipUnreadable) {
    if (pid <= 0) return {};
    std::string path = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream ifs(path, std::ios::in | std::ios::binary);
    if (!ifs.is_open()) return {};

    std::ostringstream oss;
    oss << ifs.rdbuf();
    return parseMapsContent(oss.str(), filterMask, skipUnreadable);
}

} // namespace mem_engine
