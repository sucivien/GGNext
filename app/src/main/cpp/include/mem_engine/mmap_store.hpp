#pragma once
#ifndef MEM_ENGINE_MMAP_STORE_HPP
#define MEM_ENGINE_MMAP_STORE_HPP

#include "types.hpp"
#include <vector>
#include <string>
#include <cstdint>
#include <cstddef>

namespace mem_engine {

// ============================================================================
// MmapCandidateStore: Hybrid In-Memory + Automatic Disk-Spillover (`mmap`)
// Prevents OOM crashes when candidate counts exceed inMemoryThreshold
// (default: 1,000,000 hits = 24 MB RAM cap).
// Uses an unlinked file in `spillDir` (with anonymous mmap fallback) so disk
// blocks are automatically reclaimed by the kernel on close/exit.
// ============================================================================
class MmapCandidateStore {
public:
    static constexpr size_t DEFAULT_MEMORY_THRESHOLD = 1000000;  // 1M candidates in RAM before mmap spillover
    static constexpr size_t MAX_STORED_CANDIDATES    = 16000000; // 16M candidates cap (~384 MB)

    explicit MmapCandidateStore(size_t inMemoryThreshold = DEFAULT_MEMORY_THRESHOLD,
                                const std::string& spillDir = "/data/local/tmp");
    ~MmapCandidateStore();

    MmapCandidateStore(const MmapCandidateStore&) = delete;
    MmapCandidateStore& operator=(const MmapCandidateStore&) = delete;
    MmapCandidateStore(MmapCandidateStore&& other) noexcept;
    MmapCandidateStore& operator=(MmapCandidateStore&& other) noexcept;

    bool append(const CandidateEntry& entry);
    bool appendBatch(const CandidateEntry* entries, size_t count);

    // Prunes any candidate whose address is in `addrs[0..count)`
    size_t removeAddresses(const uint64_t* addrs, size_t count);
    size_t removePrefix(size_t count) noexcept;

    size_t size() const noexcept { return m_count; }
    bool empty() const noexcept { return m_count == 0; }
    bool isFull() const noexcept { return m_count >= MAX_STORED_CANDIDATES; }
    bool isSpilledToDisk() const noexcept { return m_isMmap; }

    const CandidateEntry& operator[](size_t idx) const noexcept;
    CandidateEntry& operator[](size_t idx) noexcept;

    const CandidateEntry* data() const noexcept;
    CandidateEntry* data() noexcept;

    void sortByAddress();
    void clear() noexcept;

private:
    bool transitionToMmap(size_t requiredCount);
    bool growMmap(size_t requiredCount);

    size_t                      m_threshold = DEFAULT_MEMORY_THRESHOLD;
    std::string                 m_spillDir  = "/data/local/tmp";
    std::vector<CandidateEntry> m_ramVec;
    size_t                      m_count     = 0;

    // File-backed or anonymous demand-paged mmap state
    bool                        m_isMmap            = false;
    int                         m_fd                = -1;
    CandidateEntry*             m_mmapPtr           = nullptr;
    size_t                      m_mmapCapacityBytes = 0;
};

// ============================================================================
// MmapRegionSnapshotStore: Unlinked-File / Demand-Paged Full Memory Snapshot
// for Unknown Initial Value Fuzzy Searches without OOM!
// ============================================================================
class MmapRegionSnapshotStore {
public:
    static constexpr size_t MAX_SNAPSHOT_BYTES = 256ULL * 1024ULL * 1024ULL; // 256 MB cap

    struct SnapshotBlock {
        uintptr_t startAddress = 0;
        size_t    length       = 0;
        size_t    fileOffset   = 0;
        uint32_t  regionFlag   = 0;
    };

    explicit MmapRegionSnapshotStore(const std::string& spillDir = "/data/local/tmp");
    ~MmapRegionSnapshotStore();

    MmapRegionSnapshotStore(const MmapRegionSnapshotStore&) = delete;
    MmapRegionSnapshotStore& operator=(const MmapRegionSnapshotStore&) = delete;
    MmapRegionSnapshotStore(MmapRegionSnapshotStore&& other) noexcept;
    MmapRegionSnapshotStore& operator=(MmapRegionSnapshotStore&& other) noexcept;

    bool openStore(size_t reserveBytes);
    bool appendRegionChunk(uintptr_t startAddr, const uint8_t* data, size_t len, uint32_t regionFlag);
    const uint8_t* getBlockData(const SnapshotBlock& block) const noexcept;
    uint8_t* getBlockDataMut(const SnapshotBlock& block) noexcept;

    const std::vector<SnapshotBlock>& blocks() const noexcept { return m_blocks; }
    size_t totalSnapshotBytes() const noexcept { return m_writeOffset; }
    void clear() noexcept;

private:
    bool ensureCapacity(size_t totalBytesNeeded);

    std::string                m_spillDir = "/data/local/tmp";
    int                        m_fd       = -1;
    uint8_t*                   m_mmapPtr  = nullptr;
    size_t                     m_capacity = 0;
    size_t                     m_writeOffset = 0;
    std::vector<SnapshotBlock> m_blocks;
};

} // namespace mem_engine

#endif // MEM_ENGINE_MMAP_STORE_HPP
