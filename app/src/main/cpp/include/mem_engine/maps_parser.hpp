#pragma once
#ifndef MEM_ENGINE_MAPS_PARSER_HPP
#define MEM_ENGINE_MAPS_PARSER_HPP

#include "types.hpp"
#include <string>
#include <vector>
#include <atomic>

namespace mem_engine {

struct VmaRegion {
    uintptr_t   startAddress = 0;
    uintptr_t   endAddress   = 0;
    size_t      length       = 0;
    uint64_t    fileOffset   = 0;
    uint64_t    inode        = 0;
    uint32_t    regionFlag   = RegionFlag::O;
    bool        readable     = false;
    bool        writable     = false;
    bool        executable   = false;
    bool        isPrivate    = false;
    bool        isShared     = false;
    std::string dev;
    std::string pathname;

    bool contains(uintptr_t addr) const noexcept {
        return addr >= startAddress && addr < endAddress;
    }

    bool isFileBacked() const noexcept {
        return !pathname.empty() && pathname[0] == '/';
    }

    bool isScanSafe(uint32_t mask) const noexcept {
        if (!readable || length == 0) return false;
        if (regionFlag == RegionFlag::B) return false;
        return (mask == 0) || ((regionFlag & mask) != 0);
    }

    uint32_t pointerProtClass() const noexcept {
        if (!readable || regionFlag == RegionFlag::B) return PointerProtMask::NONE;
        if (writable && executable) return PointerProtMask::WX;
        if (executable) return PointerProtMask::X;
        if (writable) return PointerProtMask::W;
        return PointerProtMask::RO;
    }
};

// ============================================================================
// VmaIndex: Fast O(1) MRU + O(log N) Binary-Searched Virtual Memory Index
// Inspired by KittyMemoryEx (KittyPtrValidator) & frida-gum
// ============================================================================
class VmaIndex {
public:
    VmaIndex() = default;
    explicit VmaIndex(std::vector<VmaRegion> regions);

    VmaIndex(const VmaIndex& other);
    VmaIndex& operator=(const VmaIndex& other);
    VmaIndex(VmaIndex&& other) noexcept;
    VmaIndex& operator=(VmaIndex&& other) noexcept;

    void rebuild(std::vector<VmaRegion> regions);
    void clear() noexcept;

    bool empty() const noexcept { return m_regions.empty(); }
    size_t size() const noexcept { return m_regions.size(); }
    const std::vector<VmaRegion>& regions() const noexcept { return m_regions; }

    // O(1) MRU cache hit + O(log N) std::lower_bound lookup
    const VmaRegion* findRegion(uintptr_t rawAddr, bool is64Bit = true) const noexcept;

    // Returns PointerProtMask (WX=1, X=2, RO=8, W=16, or NONE=0 if invalid/unmapped/Bad)
    uint32_t classifyPointer(uintptr_t rawPtrVal, bool is64Bit = true) const noexcept;

    // Returns region tag string ("Ch", "Ca", "A", "Cd", etc.) at address
    const char* regionTagAt(uintptr_t rawAddr, bool is64Bit = true) const noexcept;

    // Returns first readable VMA start address matching regionFlag (or any readable VMA if 0)
    uintptr_t firstRegionBase(uint32_t regionFlag = 0) const noexcept;

private:
    std::vector<VmaRegion>      m_regions;
    mutable std::atomic<size_t> m_lastHitIdx{0};
};

class MapsParser {
public:
    // Parse /proc/[pid]/maps and classify every VMA into Jh, Ch, Ca, Cd, Cb, A, S, As, Xa, Xs, V, Ps, B, O, J
    static std::vector<VmaRegion> parseProcessMaps(int pid, uint32_t filterMask = 0, bool skipUnreadable = true);

    // Parse a raw maps buffer (used for both /proc/[pid]/maps and deterministic unit testing)
    static std::vector<VmaRegion> parseMapsContent(const std::string& content, uint32_t filterMask = 0, bool skipUnreadable = true);

    // Classify a single VMA entry given its attributes and the preceding VMA's pathname
    static uint32_t classifyVma(const VmaRegion& vma, const std::string& prevFileBackedPath);
};

} // namespace mem_engine

#endif // MEM_ENGINE_MAPS_PARSER_HPP
