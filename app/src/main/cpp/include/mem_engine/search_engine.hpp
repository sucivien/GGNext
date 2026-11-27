#pragma once
#ifndef MEM_ENGINE_SEARCH_ENGINE_HPP
#define MEM_ENGINE_SEARCH_ENGINE_HPP

#include "types.hpp"
#include "proc_io.hpp"
#include "maps_parser.hpp"
#include "mmap_store.hpp"
#include "simd_kernel.hpp"
#include <vector>
#include <string>
#include <cstdint>
#include <cstddef>
#include <functional>
#include <thread>
#include <mutex>
#include <condition_variable>

namespace mem_engine {

// ============================================================================
// Group Search Item Specification & Selectivity Scheduler
// ============================================================================
struct GroupFieldItem {
    DataType type      = DataType::INT32;
    int64_t  iVal      = 0;
    int64_t  iMax      = 0;
    double   fVal      = 0.0;
    double   fMax      = 0.0;
    double   epsilon   = 1e-4;
    uint8_t  op        = 0; // 0=EXACT, 1=RANGE_INC (~), 2=RANGE_INV (~~), 3=WILDCARD (0~~0)
    size_t   origIndex = 0; // Original index in user query (for ordered constraint)

    // Computes heuristic selectivity score: higher score = rarer pattern = better anchor
    uint32_t computeSelectivityScore() const noexcept;
};

struct GroupSearchQuery {
    std::vector<GroupFieldItem> fields;
    size_t                      maxSpanBytes = 512;  // e.g. A;B;C::512
    bool                        ordered      = true; // true: offset(f_0) < offset(f_1) < ...; false: any order within span
    bool                        aligned      = true; // align field checks to type width or 4B
};

// ============================================================================
// Telemetry & Diagnostics Structure for Automated QA & Benchmarking
// ============================================================================
struct ScanTelemetry {
    double   elapsedMs           = 0.0;
    size_t   totalVmasScanned    = 0;
    size_t   totalBytesScanned   = 0;
    size_t   readableBytes       = 0;
    size_t   guardPagesSkipped   = 0;
    size_t   candidatesFound     = 0;
    size_t   syscallsIssued      = 0;
    size_t   anchorFieldIndex    = 0; // For group search
    bool     spilledToMmap       = false;
};

// ============================================================================
// SearchEngine: Industrial-Grade Multi-Threaded Cross-Process Memory Scanner
// ============================================================================
class SearchEngine {
public:
    static constexpr size_t DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB per chunk task

    SearchEngine(ProcIO& io, size_t workerThreads = 4, const std::string& spillDir = "/data/local/tmp");
    ~SearchEngine();

    SearchEngine(const SearchEngine&) = delete;
    SearchEngine& operator=(const SearchEngine&) = delete;

    void setRegionMask(uint32_t mask) noexcept { m_regionMask = mask; }
    uint32_t regionMask() const noexcept { return m_regionMask; }

    void setAddressFilter(uint64_t minAddr, uint64_t maxAddr, uint64_t addrMask = 0, uint64_t addrPattern = 0) noexcept {
        m_addrMin     = minAddr;
        m_addrMax     = maxAddr;
        m_addrMask    = addrMask;
        m_addrPattern = addrPattern;
    }

    size_t workerThreads() const noexcept { return m_workerThreads; }
    size_t chunkSize() const noexcept { return m_chunkSize; }

    // Refresh target process VMAs matching current region mask
    std::vector<VmaRegion> refreshRegions();
    const std::vector<VmaRegion>& regions() const noexcept { return m_regions; }

    // Access current candidate store & telemetry
    const MmapCandidateStore& candidates() const noexcept { return m_candidates; }
    MmapCandidateStore& candidates() noexcept { return m_candidates; }
    size_t totalMatchedCount() const noexcept {
        const size_t matched = m_totalMatchedCount.load(std::memory_order_relaxed);
        return matched > m_candidates.size() ? matched : m_candidates.size();
    }
    void fetchCandidates(size_t offset, size_t limit, std::vector<CandidateEntry>& out) const;
    size_t removeCandidates(const uint64_t* addresses, size_t count);
    size_t removePrefixCandidates(size_t count) noexcept;

    void clearSession() noexcept;

    // ------------------------------------------------------------------------
    // 1. Exact Search (Single-Type & Adaptive Multi-Type AUTO) + Refinement
    // ------------------------------------------------------------------------
    EngineErrc searchExact(
        DataType type,
        int64_t intTarget,
        double floatTarget,
        double epsilon = 1e-4,
        bool aligned = true,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc refineExact(
        DataType type,
        int64_t intTarget,
        double floatTarget,
        double epsilon = 1e-4,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    // Byte[] (with optional mask) & UTF-8 / UTF-16 String Exact Search
    EngineErrc searchBytePattern(
        const std::vector<uint8_t>& pattern,
        const std::vector<uint8_t>& mask = {},
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc searchNotEqual(
        DataType type,
        int64_t intTarget,
        double floatTarget,
        double epsilon = 1e-4,
        bool aligned = true,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc refineNotEqual(
        DataType type,
        int64_t intTarget,
        double floatTarget,
        double epsilon = 1e-4,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    // ------------------------------------------------------------------------
    // 2. Range Search ([Min, Max] Inclusive, (Min, Max) Exclusive, or Inverted ~~) + Refine
    // ------------------------------------------------------------------------
    EngineErrc searchRange(
        DataType type,
        int64_t intMin,
        int64_t intMax,
        double floatMin,
        double floatMax,
        bool inclusive = true,
        bool aligned = true,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr,
        bool excludeRange = false
    );

    EngineErrc refineRange(
        DataType type,
        int64_t intMin,
        int64_t intMax,
        double floatMin,
        double floatMax,
        bool inclusive = true,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr,
        bool excludeRange = false
    );

    // ------------------------------------------------------------------------
    // 3. Fuzzy / Delta Search (Unknown Initial Snapshot + State-Change Refine)
    // ------------------------------------------------------------------------
    EngineErrc startUnknownSnapshot(
        DataType type,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc refineFuzzy(
        SearchOp deltaOp,
        int64_t intDelta = 0,
        double floatDelta = 0.0,
        double epsilon = 1e-4,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr,
        uint32_t onlyNth = 0
    );

    // ------------------------------------------------------------------------
    // 4. Encoded & XOR Search (Offset, Static XOR, XOR Pair 32/64, Addr XOR, GG_AUTO, XOR_OFFSET)
    // ------------------------------------------------------------------------
    EngineErrc searchEncoded(
        DataType type,
        EncodedMode mode,
        int64_t logicalInt,
        double logicalFloat,
        uint64_t keyOrMask = 0,
        bool aligned = true,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc refineEncoded(
        DataType type,
        EncodedMode mode,
        int64_t logicalInt,
        double logicalFloat,
        uint64_t keyOrMask = 0,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    // ------------------------------------------------------------------------
    // 5. Group / Struct Search (Ordered & Unordered with Anchor Scheduling)
    // ------------------------------------------------------------------------
    EngineErrc searchGroup(
        const GroupSearchQuery& query,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc refineGroup(
        const GroupSearchQuery& query,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    // ------------------------------------------------------------------------
    // 6. Native Validated Pointer Search & Refinement (VmaIndex + ArchPtrPolicy)
    // ------------------------------------------------------------------------
    EngineErrc searchPointers(
        DataType ptrType,
        uintptr_t targetAddress,
        size_t maxOffset,
        uint32_t protMask = PointerProtMask::ALL,
        bool aligned = true,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

    EngineErrc refinePointers(
        DataType ptrType,
        uintptr_t targetAddress,
        size_t maxOffset,
        uint32_t protMask = PointerProtMask::ALL,
        CancellationToken* token = nullptr,
        ProgressCallback progress = nullptr
    );

private:
    struct ChunkTask {
        uintptr_t startAddr   = 0;
        size_t    logicalLen  = 0; // Bytes belonging to this chunk [0, logicalLen)
        size_t    readLen     = 0; // logicalLen + overlapBytes (clamped to VMA end)
        uint32_t  regionFlag  = 0;
        bool      fileBacked  = false;
    };

    std::vector<ChunkTask> buildChunkTasks(size_t overlapBytes) const;

    static bool isRangeInValidPages(
        size_t offsetInChunk,
        size_t itemLen,
        uintptr_t chunkStartAddr,
        size_t pageSize,
        const std::vector<uint8_t>& validPageMask
    ) noexcept;

    static uint8_t regionFlagToIndex(uint32_t flag) noexcept;

    inline bool matchesAddressFilter(uintptr_t addr) const noexcept {
        const uint64_t a = static_cast<uint64_t>(addr);
        if (m_addrMin != 0 && a < m_addrMin) return false;
        if (m_addrMax != 0 && a > m_addrMax) return false;
        if (m_addrMask != 0 && ((a & m_addrMask) != (m_addrPattern & m_addrMask))) return false;
        return true;
    }

    // Reusable fixed-size worker pool to avoid per-scan thread creation overhead
    void runParallel(size_t desiredWorkers, const std::function<void(size_t)>& fn);
    void ensurePoolLocked(size_t desiredWorkers);
    void shutdownPool() noexcept;

    ProcIO&                  m_io;
    size_t                   m_workerThreads = 4;
    size_t                   m_chunkSize     = DEFAULT_CHUNK_SIZE;
    uint32_t                 m_regionMask    = RegionFlag::DEFAULT_RW;
    uint64_t                 m_addrMin       = 0;
    uint64_t                 m_addrMax       = 0;
    uint64_t                 m_addrMask      = 0;
    uint64_t                 m_addrPattern   = 0;
    std::string              m_spillDir      = "/data/local/tmp";

    std::vector<VmaRegion>   m_regions;
    MmapCandidateStore       m_candidates;
    MmapRegionSnapshotStore  m_snapshotStore;
    bool                     m_hasFullSnapshot   = false;
    bool                     m_encryptedSnapshot = false;
    int64_t                  m_encPrevLogicalInt = 0;
    double                   m_encPrevLogicalFlt = 0.0;
    uint64_t                 m_encPrevTargetBits = 0;
    DataType                 m_snapshotType      = DataType::INT32;
    std::atomic<size_t>      m_totalMatchedCount{0};
    ScanTelemetry            m_lastTelemetry{};

    // Persistent worker pool state
    std::mutex               m_poolMutex;
    std::condition_variable  m_poolWorkCv;
    std::condition_variable  m_poolDoneCv;
    std::vector<std::thread> m_poolWorkers;
    const std::function<void(size_t)>* m_poolTask = nullptr;
    uint64_t                 m_poolGeneration = 0;
    size_t                   m_poolActiveWorkers = 0;
    size_t                   m_poolRemaining = 0;
    bool                     m_poolStop = false;
};

} // namespace mem_engine

#endif // MEM_ENGINE_SEARCH_ENGINE_HPP
