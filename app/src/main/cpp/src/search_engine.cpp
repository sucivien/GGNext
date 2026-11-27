#include "mem_engine/search_engine.hpp"
#include <chrono>
#include <thread>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <cstring>
#include <cmath>

namespace mem_engine {

// ============================================================================
// GroupFieldItem Selectivity Scoring
// ============================================================================
uint32_t GroupFieldItem::computeSelectivityScore() const noexcept {
    if (op == 3) return 0u;  // Wildcard (0~~0) must not be chosen as anchor unless all fields are wildcards
    if (op == 2) return 2u;  // Inverse range (~~) has very low selectivity
    if (op == 1) return 20u; // Range (~) has moderate-low selectivity

    uint32_t score = static_cast<uint32_t>(dataTypeSize(type)) * 100u;

    uint64_t rawBits = 0;
    if (type == DataType::FLOAT) {
        float fv = static_cast<float>(fVal);
        uint32_t bits = 0;
        std::memcpy(&bits, &fv, sizeof(float));
        rawBits = bits;
    } else if (type == DataType::DOUBLE) {
        std::memcpy(&rawBits, &fVal, sizeof(double));
    } else {
        rawBits = static_cast<uint64_t>(iVal);
    }

    if (rawBits == 0) {
        return 5u;
    }
    if (rawBits == 1 || rawBits == 0xFFFFFFFFULL || rawBits == 0xFFFFFFFFFFFFFFFFULL) {
        score = score / 3u;
    }

    uint32_t pop = static_cast<uint32_t>(__builtin_popcountll(rawBits));
    score += pop * 8u;

    size_t width = dataTypeSize(type);
    uint32_t nonZeroBytes = 0;
    for (size_t b = 0; b < width; ++b) {
        uint8_t byteVal = static_cast<uint8_t>((rawBits >> (b * 8)) & 0xFFu);
        if (byteVal != 0x00 && byteVal != 0xFF) {
            nonZeroBytes++;
        }
    }
    score += nonZeroBytes * 45u;
    return score;
}

// ============================================================================
// SearchEngine Implementation
// ============================================================================
SearchEngine::SearchEngine(ProcIO& io, size_t workerThreads, const std::string& spillDir)
    : m_io(io),
      m_workerThreads(workerThreads == 0 ? 1 : workerThreads),
      m_spillDir(spillDir),
      m_candidates(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, spillDir),
      m_snapshotStore(spillDir) {}

SearchEngine::~SearchEngine() {
    shutdownPool();
}

void SearchEngine::ensurePoolLocked(size_t desiredWorkers) {
    size_t target = std::max<size_t>(1, desiredWorkers);
    while (m_poolWorkers.size() < target) {
        const size_t workerId = m_poolWorkers.size();
        m_poolWorkers.emplace_back([this, workerId]() {
            uint64_t seenGen = 0;
            while (true) {
                const std::function<void(size_t)>* task = nullptr;
                bool active = false;
                {
                    std::unique_lock<std::mutex> lk(m_poolMutex);
                    m_poolWorkCv.wait(lk, [&]() {
                        return m_poolStop || m_poolGeneration != seenGen;
                    });
                    if (m_poolStop) return;
                    seenGen = m_poolGeneration;
                    if (workerId < m_poolActiveWorkers && m_poolTask != nullptr) {
                        task = m_poolTask;
                        active = true;
                    }
                }
                if (active && task) {
                    (*task)(workerId);
                    std::lock_guard<std::mutex> lk(m_poolMutex);
                    if (m_poolRemaining > 0) {
                        --m_poolRemaining;
                        if (m_poolRemaining == 0) {
                            m_poolDoneCv.notify_one();
                        }
                    }
                }
            }
        });
    }
}

void SearchEngine::shutdownPool() noexcept {
    {
        std::lock_guard<std::mutex> lk(m_poolMutex);
        m_poolStop = true;
    }
    m_poolWorkCv.notify_all();
    for (auto& w : m_poolWorkers) {
        if (w.joinable()) w.join();
    }
    m_poolWorkers.clear();
}

void SearchEngine::runParallel(size_t desiredWorkers, const std::function<void(size_t)>& fn) {
    if (desiredWorkers <= 1) {
        fn(0);
        return;
    }
    std::unique_lock<std::mutex> lk(m_poolMutex);
    ensurePoolLocked(desiredWorkers);
    m_poolTask = &fn;
    m_poolActiveWorkers = desiredWorkers;
    m_poolRemaining = desiredWorkers;
    ++m_poolGeneration;
    m_poolWorkCv.notify_all();
    m_poolDoneCv.wait(lk, [&]() { return m_poolRemaining == 0; });
    m_poolTask = nullptr;
}

std::vector<VmaRegion> SearchEngine::refreshRegions() {
    m_regions = MapsParser::parseProcessMaps(m_io.pid(), m_regionMask, true);
    return m_regions;
}

void SearchEngine::clearSession() noexcept {
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_encPrevLogicalInt = 0;
    m_encPrevLogicalFlt = 0.0;
    m_encPrevTargetBits = 0;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};
}

size_t SearchEngine::removePrefixCandidates(size_t count) noexcept {
    if (count == 0 || m_candidates.empty()) return 0;
    size_t removed = m_candidates.removePrefix(count);
    if (removed > 0) {
        size_t curTotal = m_totalMatchedCount.load(std::memory_order_relaxed);
        if (curTotal >= removed) {
            m_totalMatchedCount.store(std::max(m_candidates.size(), curTotal - removed), std::memory_order_relaxed);
        } else {
            m_totalMatchedCount.store(m_candidates.size(), std::memory_order_relaxed);
        }
    }
    return removed;
}

size_t SearchEngine::removeCandidates(const uint64_t* addresses, size_t count) {
    if (!addresses || count == 0 || m_candidates.empty()) return 0;
    size_t removed = m_candidates.removeAddresses(addresses, count);
    if (removed > 0) {
        size_t curTotal = m_totalMatchedCount.load(std::memory_order_relaxed);
        if (curTotal >= removed) {
            m_totalMatchedCount.store(std::max(m_candidates.size(), curTotal - removed), std::memory_order_relaxed);
        } else {
            m_totalMatchedCount.store(m_candidates.size(), std::memory_order_relaxed);
        }
    }
    return removed;
}

void SearchEngine::fetchCandidates(size_t offset, size_t limit, std::vector<CandidateEntry>& out) const {
    out.clear();
    if (limit == 0) return;

    if (!m_candidates.empty()) {
        size_t total = m_candidates.size();
        if (offset >= total) return;
        size_t count = std::min(limit, total - offset);
        out.resize(count);
        std::memcpy(out.data(), m_candidates.data() + offset, count * sizeof(CandidateEntry));
        return;
    }

    if (m_hasFullSnapshot) {
        size_t elemSz = std::max<size_t>(1, dataTypeSize(m_snapshotType));
        size_t stepSz = std::min<size_t>(elemSz, 4);
        size_t curIndex = 0;
        out.reserve(std::min<size_t>(limit, 4096));
        for (const auto& blk : m_snapshotStore.blocks()) {
            const uint8_t* ptr = m_snapshotStore.getBlockData(blk);
            if (!ptr || blk.length < elemSz) continue;
            size_t blkElems = ((blk.length - elemSz) / stepSz) + 1;
            if (curIndex + blkElems <= offset) {
                curIndex += blkElems;
                continue;
            }
            size_t startElem = (offset > curIndex) ? (offset - curIndex) : 0;
            uint8_t rTag = regionFlagToIndex(blk.regionFlag);
            for (size_t e = startElem; e < blkElems && out.size() < limit; ++e) {
                size_t byteOff = e * stepSz;
                CandidateEntry entry{};
                entry.address = blk.startAddress + byteOff;
                std::memcpy(&entry.rawValue, ptr + byteOff, std::min<size_t>(elemSz, 8));
                entry.type = static_cast<uint8_t>(m_snapshotType);
                entry.regionTag = rTag;
                out.push_back(entry);
            }
            curIndex += blkElems;
            if (out.size() >= limit) break;
        }
    }
}

uint8_t SearchEngine::regionFlagToIndex(uint32_t flag) noexcept {
    if (flag == 0) return 0;
    return static_cast<uint8_t>(__builtin_ctz(flag));
}

std::vector<SearchEngine::ChunkTask> SearchEngine::buildChunkTasks(size_t overlapBytes) const {
    std::vector<ChunkTask> tasks;
    const bool hasMin = (m_addrMin != 0);
    const bool hasMax = (m_addrMax != 0);
    for (const auto& vma : m_regions) {
        if (!vma.isScanSafe(m_regionMask)) continue;
        uint64_t effStart = static_cast<uint64_t>(vma.startAddress);
        uint64_t effEnd   = static_cast<uint64_t>(vma.endAddress);
        if (hasMin) {
            if (effEnd <= m_addrMin) continue;
            if (effStart < m_addrMin) effStart = m_addrMin;
        }
        if (hasMax) {
            if (effStart > m_addrMax) continue;
            // m_addrMax is inclusive user bound; include up to m_addrMax + 1 without overflow
            const uint64_t exclMax = (m_addrMax < UINT64_MAX) ? (m_addrMax + 1ULL) : UINT64_MAX;
            if (effEnd > exclMax) effEnd = exclMax;
        }
        if (effStart >= effEnd) continue;

        const bool isFileBacked = vma.isFileBacked();
        uintptr_t cur = static_cast<uintptr_t>(effStart);
        const uintptr_t clipEnd = static_cast<uintptr_t>(effEnd);
        while (cur < clipEnd) {
            size_t rem = clipEnd - cur;
            size_t logical = (rem < m_chunkSize) ? rem : m_chunkSize;
            size_t vmaRem = vma.endAddress - cur;
            size_t readLen = (vmaRem < (logical + overlapBytes)) ? vmaRem : (logical + overlapBytes);
            tasks.push_back(ChunkTask{cur, logical, readLen, vma.regionFlag, isFileBacked});
            cur += logical;
        }
    }
    return tasks;
}

bool SearchEngine::isRangeInValidPages(
    size_t offsetInChunk,
    size_t itemLen,
    uintptr_t chunkStartAddr,
    size_t pageSize,
    const std::vector<uint8_t>& validPageMask
) noexcept {
    if (validPageMask.empty() || itemLen == 0) return false;
    uintptr_t pageAlignedBase = chunkStartAddr & ~(pageSize - 1);
    uintptr_t itemStart = chunkStartAddr + offsetInChunk;
    uintptr_t itemEnd   = itemStart + itemLen - 1;

    size_t firstPage = (itemStart - pageAlignedBase) / pageSize;
    size_t lastPage  = (itemEnd - pageAlignedBase) / pageSize;

    if (lastPage >= validPageMask.size()) return false;
    for (size_t p = firstPage; p <= lastPage; ++p) {
        if (validPageMask[p] == 0) return false;
    }
    return true;
}

namespace {

uint64_t extractRawValue(const uint8_t* ptr, DataType dt) noexcept {
    uint64_t raw = 0;
    size_t sz = dataTypeSize(dt);
    if (sz <= 8) {
        std::memcpy(&raw, ptr, sz);
    }
    return raw;
}

bool evalSingleExact(const uint8_t* ptr, DataType dt, int64_t intTarget, double floatTarget, double epsilon) noexcept {
    switch (dt) {
        case DataType::INT8: {
            int8_t v = 0;
            std::memcpy(&v, ptr, 1);
            return v == static_cast<int8_t>(intTarget);
        }
        case DataType::INT16: {
            int16_t v = 0;
            std::memcpy(&v, ptr, 2);
            return v == static_cast<int16_t>(intTarget);
        }
        case DataType::INT32: {
            int32_t v = 0;
            std::memcpy(&v, ptr, 4);
            return v == static_cast<int32_t>(intTarget);
        }
        case DataType::INT64: {
            int64_t v = 0;
            std::memcpy(&v, ptr, 8);
            return v == intTarget;
        }
        case DataType::FLOAT: {
            float v = 0.0f;
            std::memcpy(&v, ptr, 4);
            if (std::isnan(v)) return false;
            return std::fabs(static_cast<double>(v) - floatTarget) <= epsilon;
        }
        case DataType::DOUBLE: {
            double v = 0.0;
            std::memcpy(&v, ptr, 8);
            if (std::isnan(v)) return false;
            return std::fabs(v - floatTarget) <= epsilon;
        }
        default:
            return false;
    }
}

bool evalSingleRange(const uint8_t* ptr, DataType dt, int64_t iMin, int64_t iMax, double fMin, double fMax, bool inclusive) noexcept {
    if (iMin > iMax) std::swap(iMin, iMax);
    if (fMin > fMax) std::swap(fMin, fMax);

    switch (dt) {
        case DataType::INT8: {
            if (iMin >= 0 && iMax > 127 && iMax <= 255) {
                uint8_t u = 0;
                std::memcpy(&u, ptr, 1);
                return inclusive ? (u >= static_cast<uint8_t>(iMin) && u <= static_cast<uint8_t>(iMax))
                                 : (u >  static_cast<uint8_t>(iMin) && u <  static_cast<uint8_t>(iMax));
            }
            int8_t v = 0;
            std::memcpy(&v, ptr, 1);
            int8_t lo = static_cast<int8_t>(std::clamp<int64_t>(iMin, -128, 127));
            int8_t hi = static_cast<int8_t>(std::clamp<int64_t>(iMax, -128, 127));
            return inclusive ? (v >= lo && v <= hi) : (v > lo && v < hi);
        }
        case DataType::INT16: {
            if (iMin >= 0 && iMax > 32767 && iMax <= 65535) {
                uint16_t u = 0;
                std::memcpy(&u, ptr, 2);
                return inclusive ? (u >= static_cast<uint16_t>(iMin) && u <= static_cast<uint16_t>(iMax))
                                 : (u >  static_cast<uint16_t>(iMin) && u <  static_cast<uint16_t>(iMax));
            }
            int16_t v = 0;
            std::memcpy(&v, ptr, 2);
            int16_t lo = static_cast<int16_t>(std::clamp<int64_t>(iMin, -32768, 32767));
            int16_t hi = static_cast<int16_t>(std::clamp<int64_t>(iMax, -32768, 32767));
            return inclusive ? (v >= lo && v <= hi) : (v > lo && v < hi);
        }
        case DataType::INT32: {
            if (iMin >= 0 && iMax > 2147483647LL && iMax <= 4294967295LL) {
                uint32_t u = 0;
                std::memcpy(&u, ptr, 4);
                return inclusive ? (u >= static_cast<uint32_t>(iMin) && u <= static_cast<uint32_t>(iMax))
                                 : (u >  static_cast<uint32_t>(iMin) && u <  static_cast<uint32_t>(iMax));
            }
            int32_t v = 0;
            std::memcpy(&v, ptr, 4);
            int32_t lo = static_cast<int32_t>(std::clamp<int64_t>(iMin, -2147483648LL, 2147483647LL));
            int32_t hi = static_cast<int32_t>(std::clamp<int64_t>(iMax, -2147483648LL, 2147483647LL));
            return inclusive ? (v >= lo && v <= hi) : (v > lo && v < hi);
        }
        case DataType::INT64: {
            int64_t v = 0;
            std::memcpy(&v, ptr, 8);
            bool ok = inclusive ? (v >= iMin && v <= iMax)
                                : (v >  iMin && v <  iMax);
            if (!ok && iMin >= 0x10000LL && iMax <= static_cast<int64_t>(ArchPtrPolicy::ARM64_TBI_MASK) && iMin <= iMax) {
                uint64_t uv = static_cast<uint64_t>(v);
                uint8_t topByte = static_cast<uint8_t>(uv >> 56);
                if (topByte != 0 && topByte != 0xFF) {
                    int64_t untagged = static_cast<int64_t>(uv & ArchPtrPolicy::ARM64_TBI_MASK);
                    ok = inclusive ? (untagged >= iMin && untagged <= iMax)
                                   : (untagged >  iMin && untagged <  iMax);
                }
            }
            return ok;
        }
        case DataType::FLOAT: {
            float v = 0.0f;
            std::memcpy(&v, ptr, 4);
            if (std::isnan(v)) return false;
            return inclusive ? (v >= static_cast<float>(fMin) && v <= static_cast<float>(fMax))
                             : (v >  static_cast<float>(fMin) && v <  static_cast<float>(fMax));
        }
        case DataType::DOUBLE: {
            double v = 0.0;
            std::memcpy(&v, ptr, 8);
            if (std::isnan(v)) return false;
            return inclusive ? (v >= fMin && v <= fMax)
                             : (v >  fMin && v <  fMax);
        }
        default:
            return false;
    }
}

// Builds a per-page scan eligibility mask:
// 1. For non-zero queries (!queryCanMatchZero), an all-zero 4KB page mathematically cannot contain a match,
//    so skipping all-zero pages is a 100% lossless optimization.
// 2. For queries that CAN match zero (e.g. searching "0" or a range containing 0):
//    - In static/global data & bss segments (CD | CB | PS), we NEVER skip a resident readable page even if all
//      4096 bytes on that page are currently 0, guaranteeing zero-initialized globals/statics are never missed!
//    - In heap/anonymous/allocator segments, if the kernel exposed the shared zero-page PFN (pagemapZeroPfnKnown),
//      ProcIO::readChunkFaultTolerant has already excluded unpopulated and shared empty_zero_page PTEs, while
//      for non-PFN environments we filter untouched 100%-zero pages so unallocated virtual reservations do not
//      produce millions of phantom hits.
void buildScanPageMask(
    const uint8_t* buf,
    size_t readLen,
    size_t pageSize,
    uint32_t regionFlag,
    bool queryCanMatchZero,
    bool pagemapZeroPfnKnown,
    const std::vector<uint8_t>& validMask,
    std::vector<uint8_t>& outScanMask
) noexcept {
    const size_t numPages = validMask.size();
    const bool isStaticDataSegment = (regionFlag & (RegionFlag::CD | RegionFlag::CB | RegionFlag::PS)) != 0;

    if (queryCanMatchZero && (isStaticDataSegment || pagemapZeroPfnKnown)) {
        outScanMask = validMask;
        return;
    }

    outScanMask.assign(numPages, 0);
    for (size_t p = 0; p < numPages; ++p) {
        if (validMask[p] == 0) continue;
        size_t pOff = p * pageSize;
        if (pOff >= readLen) continue;
        size_t pLen = std::min(pageSize, readLen - pOff);
        const uint8_t* pagePtr = buf + pOff;
        const uint64_t* w64 = reinterpret_cast<const uint64_t*>(pagePtr);
        size_t words = pLen / sizeof(uint64_t);
        bool nonZero = false;
        for (size_t w = 0; w < words; ++w) {
            if (w64[w] != 0) {
                nonZero = true;
                break;
            }
        }
        if (!nonZero) {
            for (size_t b = words * sizeof(uint64_t); b < pLen; ++b) {
                if (pagePtr[b] != 0) {
                    nonZero = true;
                    break;
                }
            }
        }
        if (nonZero) {
            outScanMask[p] = 1;
        }
    }
}

} // anonymous namespace

// ============================================================================
// 1. Exact Search (Single-Type & Adaptive Multi-Type AUTO)
// ============================================================================
EngineErrc SearchEngine::searchExact(
    DataType type,
    int64_t intTarget,
    double floatTarget,
    double epsilon,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();

    size_t maxItemSize = (type == DataType::AUTO) ? 8 : dataTypeSize(type);
    size_t overlap = (maxItemSize > 1) ? (maxItemSize - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> guardPages{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());

    const size_t pageSize = m_io.pageSize();
    const bool pagemapZeroKnown = m_io.hasPagemapResidency() && (m_io.zeroPagePfn() != 0);
    const uint8_t zeroBytes[8] = {0};
    const bool queryCanMatchZero = (type == DataType::AUTO)
        ? (intTarget == 0 || std::fabs(floatTarget) <= epsilon)
        : evalSingleExact(zeroBytes, type, intTarget, floatTarget, epsilon);

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<uint8_t> scanMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;
            localHits.reserve(8192);

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);

                size_t chunkGuards = 0;
                for (uint8_t v : validMask) {
                    if (v == 0) chunkGuards++;
                }
                guardPages.fetch_add(chunkGuards, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    buildScanPageMask(
                        buf.data(), task.readLen, pageSize, task.regionFlag,
                        queryCanMatchZero, pagemapZeroKnown, validMask, scanMask
                    );
                    const size_t numPages = scanMask.size();
                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    size_t chunkMatchCount = 0;

                    auto runTypeScan = [&](DataType dt) {
                        size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));
                        size_t step = aligned ? std::min<size_t>(itemSz, 4) : 1;
                        bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);

                        size_t p = 0;
                        while (p < numPages) {
                            if (scanMask[p] == 0) {
                                p++;
                                continue;
                            }
                            size_t runStartPage = p;
                            while (p < numPages && scanMask[p] != 0) p++;
                            size_t runByteStart = runStartPage * pageSize;
                            if (runByteStart >= task.logicalLen) break;

                            size_t runByteEnd = std::min(p * pageSize, task.readLen);
                            size_t scanByteEnd = runByteEnd;
                            if (itemSz > 1 && p < numPages && validMask[p] != 0) {
                                scanByteEnd = std::min(task.readLen, runByteEnd + itemSz - 1);
                            }
                            if (scanByteEnd <= runByteStart) continue;
                            size_t scanLen = scanByteEnd - runByteStart;
                            const uint8_t* scanPtr = buf.data() + runByteStart;

                            offsets.clear();
                            switch (dt) {
                                case DataType::INT8:
                                    SimdKernel::scanExactI8(scanPtr, scanLen, static_cast<int8_t>(intTarget), offsets);
                                    break;
                                case DataType::INT16:
                                    SimdKernel::scanExactI16(scanPtr, scanLen, static_cast<int16_t>(intTarget), step, offsets);
                                    break;
                                case DataType::INT32:
                                    SimdKernel::scanExactI32(scanPtr, scanLen, static_cast<int32_t>(intTarget), step, offsets);
                                    break;
                                case DataType::INT64:
                                    SimdKernel::scanExactI64(scanPtr, scanLen, intTarget, step, offsets);
                                    break;
                                case DataType::FLOAT:
                                    SimdKernel::scanExactF32(scanPtr, scanLen, static_cast<float>(floatTarget), static_cast<float>(epsilon), step, offsets);
                                    break;
                                case DataType::DOUBLE:
                                    SimdKernel::scanExactF64(scanPtr, scanLen, floatTarget, epsilon, step, offsets);
                                    break;
                                default:
                                    break;
                            }

                            for (size_t relOff : offsets) {
                                size_t off = runByteStart + relOff;
                                if (off >= runByteEnd || off >= task.logicalLen) continue;
                                if (!isRangeInValidPages(off, itemSz, task.startAddr, pageSize, validMask)) continue;
                                uintptr_t candAddr = task.startAddr + off;
                                if (!matchesAddressFilter(candAddr)) continue;

                                chunkMatchCount++;

                                if (!storeAlreadyFull) {
                                    CandidateEntry entry{};
                                    entry.address   = candAddr;
                                    entry.rawValue  = extractRawValue(buf.data() + off, dt);
                                    entry.type      = static_cast<uint8_t>(dt);
                                    entry.regionTag = rTag;
                                    localHits.push_back(entry);

                                    if (localHits.size() >= 16384) {
                                        std::lock_guard<std::mutex> lk(storeMutex);
                                        m_candidates.appendBatch(localHits.data(), localHits.size());
                                        storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                        localHits.clear();
                                        if (m_candidates.isFull()) {
                                            storeAlreadyFull = true;
                                        }
                                    }
                                }
                            }
                        }
                    };

                    if (type == DataType::AUTO) {
                        bool isFractional = std::fabs(floatTarget - static_cast<double>(intTarget)) > 1e-9;
                        if (isFractional) {
                            runTypeScan(DataType::FLOAT);
                            runTypeScan(DataType::DOUBLE);
                        } else {
                            runTypeScan(DataType::INT32);
                            runTypeScan(DataType::FLOAT);
                            runTypeScan(DataType::DOUBLE);
                            runTypeScan(DataType::INT64);
                            if (intTarget != 0 && intTarget >= -32768 && intTarget <= 65535) {
                                runTypeScan(DataType::INT16);
                            }
                            if (intTarget != 0 && intTarget >= -128 && intTarget <= 255) {
                                runTypeScan(DataType::INT8);
                            }
                        }
                    } else {
                        runTypeScan(type);
                    }

                    if (chunkMatchCount > 0) {
                        size_t newTotal = totalMatches.fetch_add(chunkMatchCount, std::memory_order_relaxed) + chunkMatchCount;
                        m_totalMatchedCount = newTotal;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) {
                    progress(done, totalPlannedBytes);
                }
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
    });

    if (token && token->isCancelled()) {
        return EngineErrc::ERR_CANCELLED;
    }

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.guardPagesSkipped = guardPages.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();

    return EngineErrc::OK;
}

// ============================================================================
// Page-Batched iovec Iterative Refinement for Exact Search
// ============================================================================
EngineErrc SearchEngine::refineExact(
    DataType type,
    int64_t intTarget,
    double floatTarget,
    double epsilon,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();

    if (m_hasFullSnapshot && m_candidates.empty()) {
        const auto& blocks = m_snapshotStore.blocks();
        if (blocks.empty()) {
            m_snapshotStore.clear();
            m_hasFullSnapshot = false;
            m_encryptedSnapshot = false;
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }

        const DataType snapDt = (type == DataType::AUTO) ? m_snapshotType : type;
        const size_t elemSz = std::max<size_t>(1, dataTypeSize(snapDt));
        const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());

        std::atomic<size_t> nextBlock{0};
        std::atomic<size_t> syscalls{0};
        std::atomic<size_t> bytesScanned{0};
        std::atomic<size_t> totalMatches{0};
        std::atomic<size_t> storedCount{0};
        std::mutex storeMutex;

        size_t numThreads = std::min(m_workerThreads, blocks.size());

        runParallel(numThreads, [&](size_t) {
                std::vector<uint8_t> curBuf;
                std::vector<uint8_t> validMask;
                std::vector<CandidateEntry> blockHits;
                blockHits.reserve(4096);

                while (true) {
                    if (token && token->isCancelled()) break;
                    size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                    if (bIdx >= blocks.size()) break;

                    const auto& blk = blocks[bIdx];
                    if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                    size_t chunkReadable = 0;
                    EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                    syscalls.fetch_add(1, std::memory_order_relaxed);
                    size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;

                    if (rc != EngineErrc::OK || chunkReadable == 0) {
                        if (progress) progress(doneBytes, totalSnapBytes);
                        continue;
                    }

                    blockHits.clear();
                    uint8_t rTag = regionFlagToIndex(blk.regionFlag);
                    size_t stepSz = std::min<size_t>(elemSz, 4);
                    for (size_t off = 0; off + elemSz <= blk.length; off += stepSz) {
                        uintptr_t candAddr = blk.startAddress + off;
                        if (!matchesAddressFilter(candAddr)) continue;
                        const uint8_t* valPtr = curBuf.data() + off;
                        if (evalSingleExact(valPtr, snapDt, intTarget, floatTarget, epsilon)) {
                            CandidateEntry entry{};
                            entry.address   = candAddr;
                            entry.rawValue  = extractRawValue(valPtr, snapDt);
                            entry.type      = static_cast<uint8_t>(snapDt);
                            entry.regionTag = rTag;
                            blockHits.push_back(entry);
                        }
                    }

                    if (!blockHits.empty()) {
                        size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                        m_totalMatchedCount = newTotal;
                        if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            std::lock_guard<std::mutex> lk(storeMutex);
                            m_candidates.appendBatch(blockHits.data(), blockHits.size());
                            storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                        }
                    }

                    if (progress) progress(doneBytes, totalSnapBytes);
                }
        });
        m_snapshotStore.clear();
        m_hasFullSnapshot = false;
        m_encryptedSnapshot = false;

        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

        m_candidates.sortByAddress();
        m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());

        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.totalBytesScanned = bytesScanned.load();
        m_lastTelemetry.readableBytes     = bytesScanned.load();
        m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued    = syscalls.load();
        m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
        return EngineErrc::OK;
    }

    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    groups.reserve(std::min<size_t>(count, 8192));

    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
        size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));

        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx;
        idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            DataType nextDt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
            size_t nextSz = std::max<size_t>(1, dataTypeSize(nextDt));
            uintptr_t nextEnd = nextAddr + nextSz;

            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    size_t totalBatchBytes = 0;
    for (const auto& g : groups) totalBatchBytes += g.byteLen;

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;
    const bool isFractional = std::fabs(floatTarget - static_cast<double>(intTarget)) > 1e-9;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            ranges[i - gBase].success    = false;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;

            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(cand.type) : type;
                if (type == DataType::AUTO && isFractional && dt != DataType::FLOAT && dt != DataType::DOUBLE) {
                    continue;
                }
                if (!matchesAddressFilter(cand.address)) continue;
                size_t relOff = cand.address - grp.startPageAddr;
                const uint8_t* valPtr = br.localBuf + relOff;

                if (evalSingleExact(valPtr, dt, intTarget, floatTarget, epsilon)) {
                    cand.rawValue = extractRawValue(valPtr, dt);
                    cand.type     = static_cast<uint8_t>(dt);
                    nextStore.append(cand);
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) {
            progress(gEnd, groups.size());
        }
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalBytesScanned = totalBatchBytes;
    m_lastTelemetry.readableBytes     = totalBatchBytes;
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscallsIssued;
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();

    return EngineErrc::OK;
}

// ============================================================================
// Byte[] Pattern & UTF-8 / UTF-16 String Exact Search
// ============================================================================
EngineErrc SearchEngine::searchBytePattern(
    const std::vector<uint8_t>& pattern,
    const std::vector<uint8_t>& mask,
    CancellationToken* token,
    ProgressCallback progress
) {
    if (pattern.empty()) return EngineErrc::ERR_INVAL;
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();

    const size_t patLen = pattern.size();
    const size_t overlap = (patLen > 1) ? (patLen - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> guardPages{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    const size_t pageSize = m_io.pageSize();
    const uint8_t* maskPtr = (mask.size() == patLen) ? mask.data() : nullptr;

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    offsets.clear();
                    SimdKernel::scanBytePattern(buf.data(), task.readLen, pattern.data(), maskPtr, patLen, offsets);
                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                    size_t chunkMatches = 0;

                    for (size_t off : offsets) {
                        if (off >= task.logicalLen) continue;
                        if (!isRangeInValidPages(off, patLen, task.startAddr, pageSize, validMask)) continue;
                        uintptr_t candAddr = task.startAddr + off;
                        if (!matchesAddressFilter(candAddr)) continue;

                        chunkMatches++;
                        if (!storeAlreadyFull) {
                            CandidateEntry entry{};
                            entry.address   = candAddr;
                            std::memcpy(&entry.rawValue, buf.data() + off, std::min<size_t>(patLen, 8));
                            entry.type      = static_cast<uint8_t>(DataType::BYTE_ARRAY);
                            entry.regionTag = rTag;
                            localHits.push_back(entry);

                            if (localHits.size() >= 16384) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(localHits.data(), localHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                localHits.clear();
                                if (m_candidates.isFull()) storeAlreadyFull = true;
                            }
                        }
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
    });
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.guardPagesSkipped = guardPages.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 1b. Not-Equal Search (!= / ≠) + Refinement
// ============================================================================
EngineErrc SearchEngine::searchNotEqual(
    DataType type,
    int64_t intTarget,
    double floatTarget,
    double epsilon,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();

    size_t maxItemSz = (type == DataType::AUTO) ? 8 : std::max<size_t>(1, dataTypeSize(type));
    size_t overlap = (maxItemSz > 1) ? (maxItemSz - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    const size_t pageSize = m_io.pageSize();
    const bool pagemapZeroKnown = m_io.hasPagemapResidency() && (m_io.zeroPagePfn() != 0);
    const uint8_t zeroBytes[8] = {0};
    const bool queryCanMatchZero = (type == DataType::AUTO)
        ? (intTarget != 0 || std::fabs(floatTarget) > epsilon)
        : !evalSingleExact(zeroBytes, type, intTarget, floatTarget, epsilon);

    runParallel(numThreads, [&](size_t) {
        std::vector<uint8_t> buf;
        std::vector<uint8_t> validMask;
        std::vector<uint8_t> scanMask;
        std::vector<CandidateEntry> localHits;
        localHits.reserve(8192);

        while (true) {
            if (token && token->isCancelled()) break;
            size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
            if (idx >= tasks.size()) break;

            const ChunkTask& task = tasks[idx];
            if (buf.size() < task.readLen) buf.resize(task.readLen);

            size_t chunkReadable = 0;
            EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
            syscalls.fetch_add(1, std::memory_order_relaxed);
            readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

            if (rc == EngineErrc::OK && chunkReadable > 0) {
                buildScanPageMask(
                    buf.data(), task.readLen, pageSize, task.regionFlag,
                    queryCanMatchZero, pagemapZeroKnown, validMask, scanMask
                );
                const size_t numPages = scanMask.size();
                uint8_t rTag = regionFlagToIndex(task.regionFlag);
                size_t chunkMatches = 0;

                auto runNeqType = [&](DataType dt) {
                    size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));
                    size_t step = aligned ? std::min<size_t>(itemSz, 4) : 1;
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);

                    for (size_t p = 0; p < numPages; ++p) {
                        if (scanMask[p] == 0) continue;
                        size_t pStart = p * pageSize;
                        if (pStart >= task.logicalLen) break;
                        size_t pEnd = std::min((p + 1) * pageSize, task.logicalLen);

                        for (size_t off = pStart; off < pEnd && off + itemSz <= task.readLen; off += step) {
                            if (!isRangeInValidPages(off, itemSz, task.startAddr, pageSize, validMask)) continue;
                            uintptr_t candAddr = task.startAddr + off;
                            if (!matchesAddressFilter(candAddr)) continue;
                            const uint8_t* ptr = buf.data() + off;
                            if (dt == DataType::FLOAT) {
                                float fv = 0.0f;
                                std::memcpy(&fv, ptr, 4);
                                if (std::isnan(fv)) continue;
                            } else if (dt == DataType::DOUBLE) {
                                double dv = 0.0;
                                std::memcpy(&dv, ptr, 8);
                                if (std::isnan(dv)) continue;
                            }
                            if (!evalSingleExact(ptr, dt, intTarget, floatTarget, epsilon)) {
                                chunkMatches++;
                                if (!storeAlreadyFull) {
                                    CandidateEntry entry{};
                                    entry.address   = candAddr;
                                    entry.rawValue  = extractRawValue(ptr, dt);
                                    entry.type      = static_cast<uint8_t>(dt);
                                    entry.regionTag = rTag;
                                    localHits.push_back(entry);
                                    if (localHits.size() >= 16384) {
                                        std::lock_guard<std::mutex> lk(storeMutex);
                                        m_candidates.appendBatch(localHits.data(), localHits.size());
                                        storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                        localHits.clear();
                                        if (m_candidates.isFull()) storeAlreadyFull = true;
                                    }
                                }
                            }
                        }
                    }
                };

                if (type == DataType::AUTO) {
                    runNeqType(DataType::INT32);
                } else {
                    runNeqType(type);
                }

                if (chunkMatches > 0) {
                    m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                }
            }

            size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
            if (progress) progress(done, totalPlannedBytes);
        }

        if (!localHits.empty()) {
            std::lock_guard<std::mutex> lk(storeMutex);
            m_candidates.appendBatch(localHits.data(), localHits.size());
            storedCount.store(m_candidates.size(), std::memory_order_relaxed);
        }
    });

    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineNotEqual(
    DataType type,
    int64_t intTarget,
    double floatTarget,
    double epsilon,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();

    if (m_hasFullSnapshot && m_candidates.empty()) {
        const auto& blocks = m_snapshotStore.blocks();
        if (blocks.empty()) {
            m_snapshotStore.clear();
            m_hasFullSnapshot = false;
            m_encryptedSnapshot = false;
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }
        const DataType snapDt = (type == DataType::AUTO) ? m_snapshotType : type;
        const size_t elemSz = std::max<size_t>(1, dataTypeSize(snapDt));
        const size_t stepSz = std::min<size_t>(elemSz, 4);
        const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());

        std::atomic<size_t> nextBlock{0};
        std::atomic<size_t> syscalls{0};
        std::atomic<size_t> bytesScanned{0};
        std::atomic<size_t> totalMatches{0};
        std::atomic<size_t> storedCount{0};
        std::mutex storeMutex;
        size_t numThreads = std::min(m_workerThreads, blocks.size());

        runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> curBuf;
            std::vector<uint8_t> validMask;
            std::vector<CandidateEntry> blockHits;
            blockHits.reserve(4096);

            while (true) {
                if (token && token->isCancelled()) break;
                size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                if (bIdx >= blocks.size()) break;

                const auto& blk = blocks[bIdx];
                if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;
                if (rc != EngineErrc::OK || chunkReadable == 0) {
                    if (progress) progress(doneBytes, totalSnapBytes);
                    continue;
                }

                blockHits.clear();
                uint8_t rTag = regionFlagToIndex(blk.regionFlag);
                for (size_t off = 0; off + elemSz <= blk.length; off += stepSz) {
                    uintptr_t candAddr = blk.startAddress + off;
                    if (!matchesAddressFilter(candAddr)) continue;
                    const uint8_t* valPtr = curBuf.data() + off;
                    if (!evalSingleExact(valPtr, snapDt, intTarget, floatTarget, epsilon)) {
                        CandidateEntry entry{};
                        entry.address   = candAddr;
                        entry.rawValue  = extractRawValue(valPtr, snapDt);
                        entry.type      = static_cast<uint8_t>(snapDt);
                        entry.regionTag = rTag;
                        blockHits.push_back(entry);
                    }
                }
                if (!blockHits.empty()) {
                    size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                    m_totalMatchedCount = newTotal;
                    if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                        std::lock_guard<std::mutex> lk(storeMutex);
                        m_candidates.appendBatch(blockHits.data(), blockHits.size());
                        storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                    }
                }
                if (progress) progress(doneBytes, totalSnapBytes);
            }
        });

        m_snapshotStore.clear();
        m_hasFullSnapshot = false;
        m_encryptedSnapshot = false;
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        m_candidates.sortByAddress();
        m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.totalBytesScanned = bytesScanned.load();
        m_lastTelemetry.readableBytes     = bytesScanned.load();
        m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued    = syscalls.load();
        m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
        return EngineErrc::OK;
    }

    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
        size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));
        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx++;
        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            DataType nextDt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
            size_t nextSz = std::max<size_t>(1, dataTypeSize(nextDt));
            uintptr_t nextEnd  = nextAddr + nextSz;
            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;
    constexpr size_t BATCH_GROUP_LIMIT = 4096;

    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;
        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }
        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                if (!matchesAddressFilter(cand.address)) continue;
                DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(cand.type) : type;
                const uint8_t* valPtr = br.localBuf + (cand.address - grp.startPageAddr);
                if (!evalSingleExact(valPtr, dt, intTarget, floatTarget, epsilon)) {
                    cand.rawValue = extractRawValue(valPtr, dt);
                    nextStore.append(cand);
                }
            }
        }
        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 2. Range Search ([Min, Max] Inclusive, (Min, Max) Exclusive, or Inverted ~~) + Refinement
// ============================================================================
EngineErrc SearchEngine::searchRange(
    DataType type,
    int64_t intMin,
    int64_t intMax,
    double floatMin,
    double floatMax,
    bool inclusive,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress,
    bool excludeRange
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();

    size_t maxItemSz = (type == DataType::AUTO) ? 8 : std::max<size_t>(1, dataTypeSize(type));
    size_t overlap = (maxItemSz > 1) ? (maxItemSz - 1) : 0;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    const size_t pageSize = m_io.pageSize();
    const bool pagemapZeroKnown = m_io.hasPagemapResidency() && (m_io.zeroPagePfn() != 0);
    const uint8_t zeroBytes[8] = {0};
    const int64_t loI = std::min(intMin, intMax);
    const int64_t hiI = std::max(intMin, intMax);
    const double  loF = std::min(floatMin, floatMax);
    const double  hiF = std::max(floatMin, floatMax);
    const bool zeroInRange = (type == DataType::AUTO)
        ? ((inclusive ? (loI <= 0 && hiI >= 0) : (loI < 0 && hiI > 0)) ||
           (inclusive ? (loF <= 0.0 && hiF >= 0.0) : (loF < 0.0 && hiF > 0.0)))
        : evalSingleRange(zeroBytes, type, intMin, intMax, floatMin, floatMax, inclusive);
    const bool queryCanMatchZero = excludeRange ? !zeroInRange : zeroInRange;

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<uint8_t> scanMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;
            localHits.reserve(8192);

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    buildScanPageMask(
                        buf.data(), task.readLen, pageSize, task.regionFlag,
                        queryCanMatchZero, pagemapZeroKnown, validMask, scanMask
                    );
                    const size_t numPages = scanMask.size();
                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    size_t chunkMatches = 0;

                    auto runRangeType = [&](DataType dt) {
                        size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));
                        size_t step = aligned ? std::min<size_t>(itemSz, 4) : 1;
                        bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);

                        size_t p = 0;
                        while (p < numPages) {
                            if (scanMask[p] == 0) {
                                p++;
                                continue;
                            }
                            size_t runStartPage = p;
                            while (p < numPages && scanMask[p] != 0) p++;
                            size_t runByteStart = runStartPage * pageSize;
                            if (runByteStart >= task.logicalLen) break;

                            size_t runByteEnd = std::min(p * pageSize, task.readLen);
                            size_t scanByteEnd = runByteEnd;
                            if (itemSz > 1 && p < numPages && validMask[p] != 0) {
                                scanByteEnd = std::min(task.readLen, runByteEnd + itemSz - 1);
                            }
                            if (scanByteEnd <= runByteStart) continue;
                            size_t scanLen = scanByteEnd - runByteStart;
                            const uint8_t* scanPtr = buf.data() + runByteStart;

                            offsets.clear();
                            if (!excludeRange) {
                                switch (dt) {
                                    case DataType::INT8:
                                    case DataType::INT16:
                                    case DataType::INT32:
                                    case DataType::INT64:
                                        SimdKernel::scanRangeInteger(scanPtr, scanLen, dt, intMin, intMax, inclusive, step, offsets);
                                        break;
                                    case DataType::FLOAT:
                                        SimdKernel::scanRangeF32(scanPtr, scanLen, static_cast<float>(loF), static_cast<float>(hiF), inclusive, step, offsets);
                                        break;
                                    case DataType::DOUBLE:
                                        SimdKernel::scanRange<double>(scanPtr, scanLen, loF, hiF, inclusive, step, offsets);
                                        break;
                                    default:
                                        break;
                                }
                            } else {
                                for (size_t rel = 0; rel + itemSz <= scanLen; rel += step) {
                                    const uint8_t* ptr = scanPtr + rel;
                                    if (dt == DataType::FLOAT) {
                                        float fv = 0.0f;
                                        std::memcpy(&fv, ptr, 4);
                                        if (std::isnan(fv)) continue;
                                    } else if (dt == DataType::DOUBLE) {
                                        double dv = 0.0;
                                        std::memcpy(&dv, ptr, 8);
                                        if (std::isnan(dv)) continue;
                                    }
                                    if (!evalSingleRange(ptr, dt, intMin, intMax, floatMin, floatMax, inclusive)) {
                                        offsets.push_back(rel);
                                    }
                                }
                            }

                            for (size_t relOff : offsets) {
                                size_t off = runByteStart + relOff;
                                if (off >= runByteEnd || off >= task.logicalLen) continue;
                                if (!isRangeInValidPages(off, itemSz, task.startAddr, pageSize, validMask)) continue;
                                uintptr_t candAddr = task.startAddr + off;
                                if (!matchesAddressFilter(candAddr)) continue;

                                chunkMatches++;
                                if (!storeAlreadyFull) {
                                    CandidateEntry entry{};
                                    entry.address   = candAddr;
                                    entry.rawValue  = extractRawValue(buf.data() + off, dt);
                                    entry.type      = static_cast<uint8_t>(dt);
                                    entry.regionTag = rTag;
                                    localHits.push_back(entry);

                                    if (localHits.size() >= 16384) {
                                        std::lock_guard<std::mutex> lk(storeMutex);
                                        m_candidates.appendBatch(localHits.data(), localHits.size());
                                        storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                        localHits.clear();
                                        if (m_candidates.isFull()) storeAlreadyFull = true;
                                    }
                                }
                            }
                        }
                    };

                    if (type == DataType::AUTO) {
                        bool isFractional = (std::fabs(floatMin - static_cast<double>(intMin)) > 1e-9) ||
                                            (std::fabs(floatMax - static_cast<double>(intMax)) > 1e-9);
                        if (isFractional) {
                            runRangeType(DataType::FLOAT);
                            runRangeType(DataType::DOUBLE);
                        } else {
                            runRangeType(DataType::INT32);
                            runRangeType(DataType::FLOAT);
                            runRangeType(DataType::DOUBLE);
                            runRangeType(DataType::INT64);
                        }
                    } else {
                        runRangeType(type);
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
    });

    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineRange(
    DataType type,
    int64_t intMin,
    int64_t intMax,
    double floatMin,
    double floatMax,
    bool inclusive,
    CancellationToken* token,
    ProgressCallback progress,
    bool excludeRange
) {
    auto t0 = std::chrono::steady_clock::now();

    if (m_hasFullSnapshot && m_candidates.empty()) {
        const auto& blocks = m_snapshotStore.blocks();
        if (blocks.empty()) {
            m_snapshotStore.clear();
            m_hasFullSnapshot = false;
            m_encryptedSnapshot = false;
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }

        const DataType snapDt = (type == DataType::AUTO) ? m_snapshotType : type;
        const size_t elemSz = std::max<size_t>(1, dataTypeSize(snapDt));
        const size_t stepSz = std::min<size_t>(elemSz, 4);
        const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());

        std::atomic<size_t> nextBlock{0};
        std::atomic<size_t> syscalls{0};
        std::atomic<size_t> bytesScanned{0};
        std::atomic<size_t> totalMatches{0};
        std::atomic<size_t> storedCount{0};
        std::mutex storeMutex;

        size_t numThreads = std::min(m_workerThreads, blocks.size());

        runParallel(numThreads, [&](size_t) {
                std::vector<uint8_t> curBuf;
                std::vector<uint8_t> validMask;
                std::vector<CandidateEntry> blockHits;
                blockHits.reserve(4096);

                while (true) {
                    if (token && token->isCancelled()) break;
                    size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                    if (bIdx >= blocks.size()) break;

                    const auto& blk = blocks[bIdx];
                    if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                    size_t chunkReadable = 0;
                    EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                    syscalls.fetch_add(1, std::memory_order_relaxed);
                    size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;

                    if (rc != EngineErrc::OK || chunkReadable == 0) {
                        if (progress) progress(doneBytes, totalSnapBytes);
                        continue;
                    }

                    blockHits.clear();
                    uint8_t rTag = regionFlagToIndex(blk.regionFlag);
                    for (size_t off = 0; off + elemSz <= blk.length; off += stepSz) {
                        uintptr_t candAddr = blk.startAddress + off;
                        if (!matchesAddressFilter(candAddr)) continue;
                        const uint8_t* valPtr = curBuf.data() + off;
                        bool inRange = evalSingleRange(valPtr, snapDt, intMin, intMax, floatMin, floatMax, inclusive);
                        bool matched = excludeRange ? !inRange : inRange;
                        if (matched) {
                            CandidateEntry entry{};
                            entry.address   = candAddr;
                            entry.rawValue  = extractRawValue(valPtr, snapDt);
                            entry.type      = static_cast<uint8_t>(snapDt);
                            entry.regionTag = rTag;
                            blockHits.push_back(entry);
                        }
                    }

                    if (!blockHits.empty()) {
                        size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                        m_totalMatchedCount = newTotal;
                        if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            std::lock_guard<std::mutex> lk(storeMutex);
                            m_candidates.appendBatch(blockHits.data(), blockHits.size());
                            storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                        }
                    }

                    if (progress) progress(doneBytes, totalSnapBytes);
                }
        });
        m_snapshotStore.clear();
        m_hasFullSnapshot = false;
        m_encryptedSnapshot = false;

        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

        m_candidates.sortByAddress();
        m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());

        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.totalBytesScanned = bytesScanned.load();
        m_lastTelemetry.readableBytes     = bytesScanned.load();
        m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued    = syscalls.load();
        m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
        return EngineErrc::OK;
    }

    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
        size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));
        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            DataType nextDt = (type == DataType::AUTO) ? static_cast<DataType>(m_candidates[idx].type) : type;
            size_t nextSz = std::max<size_t>(1, dataTypeSize(nextDt));
            uintptr_t nextEnd  = nextAddr + nextSz;
            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                if (!matchesAddressFilter(cand.address)) continue;
                DataType dt = (type == DataType::AUTO) ? static_cast<DataType>(cand.type) : type;
                const uint8_t* valPtr = br.localBuf + (cand.address - grp.startPageAddr);
                bool inRange = evalSingleRange(valPtr, dt, intMin, intMax, floatMin, floatMax, inclusive);
                bool matched = excludeRange ? !inRange : inRange;
                if (matched) {
                    cand.rawValue = extractRawValue(valPtr, dt);
                    nextStore.append(cand);
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 3. Fuzzy / Delta Search: Full Snapshot + Refinement
// ============================================================================
EngineErrc SearchEngine::startUnknownSnapshot(
    DataType type,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot   = false;
    m_encryptedSnapshot = false;
    m_snapshotType      = (type == DataType::AUTO) ? DataType::INT32 : type;
    m_totalMatchedCount = 0;
    m_lastTelemetry     = ScanTelemetry{};

    refreshRegions();

    auto tasks = buildChunkTasks(0);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    if (!m_snapshotStore.openStore(totalPlannedBytes)) {
        return EngineErrc::ERR_NOMEM;
    }

    const size_t elemSz = std::max<size_t>(1, dataTypeSize(m_snapshotType));
    const size_t stepSz = std::min<size_t>(elemSz, 4);
    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> guardPages{0};
    std::atomic<size_t> syscalls{0};
    std::mutex          snapMutex;
    const size_t        pageSize = m_io.pageSize();
    const bool          pagemapZeroKnown = m_io.hasPagemapResidency() && (m_io.zeroPagePfn() != 0);

    size_t numThreads = std::min(m_workerThreads, tasks.size());

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf(m_chunkSize);
            std::vector<uint8_t> validMask;
            std::vector<uint8_t> scanMask;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.logicalLen) buf.resize(task.logicalLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.logicalLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    buildScanPageMask(
                        buf.data(), task.logicalLen, pageSize, task.regionFlag,
                        true, pagemapZeroKnown, validMask, scanMask
                    );
                    size_t numPages = scanMask.size();
                    size_t keptReadable = 0;

                    size_t p = 0;
                    while (p < numPages) {
                        if (scanMask[p] == 0) {
                            p++;
                            continue;
                        }
                        size_t runStart = p;
                        while (p < numPages && scanMask[p] != 0) p++;
                        size_t runByteOffset = runStart * pageSize;
                        size_t runByteLen    = std::min((p - runStart) * pageSize, task.logicalLen - runByteOffset);
                        if (runByteLen > 0) {
                            keptReadable += runByteLen;
                            std::lock_guard<std::mutex> lk(snapMutex);
                            m_snapshotStore.appendRegionChunk(
                                task.startAddr + runByteOffset,
                                buf.data() + runByteOffset,
                                runByteLen,
                                task.regionFlag
                            );
                        }
                    }
                    chunkReadable = keptReadable;
                }

                size_t totalReadSoFar = readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed) + chunkReadable;
                m_totalMatchedCount = totalReadSoFar / stepSz;

                size_t chunkGuards = 0;
                for (uint8_t v : validMask) {
                    if (v == 0) chunkGuards++;
                }
                guardPages.fetch_add(chunkGuards, std::memory_order_relaxed);

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }
    });
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_hasFullSnapshot   = !m_snapshotStore.blocks().empty();
    m_totalMatchedCount = m_snapshotStore.totalSnapshotBytes() / stepSz;

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.guardPagesSkipped = guardPages.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = true;

    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineFuzzy(
    SearchOp deltaOp,
    int64_t intDelta,
    double floatDelta,
    double epsilon,
    CancellationToken* token,
    ProgressCallback progress,
    uint32_t onlyNth
) {
    auto t0 = std::chrono::steady_clock::now();

    if (m_hasFullSnapshot && m_candidates.empty()) {
        const auto& blocks = m_snapshotStore.blocks();
        if (blocks.empty()) {
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }

        const size_t elemSz = std::max<size_t>(1, dataTypeSize(m_snapshotType));
        const size_t stepSz = std::min<size_t>(elemSz, 4);
        const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());
        std::atomic<size_t> nextBlock{0};
        std::atomic<size_t> syscalls{0};
        std::atomic<size_t> bytesScanned{0};
        std::atomic<size_t> totalMatches{0};
        std::atomic<size_t> storedCount{0};
        std::mutex storeMutex;

        size_t numThreads = std::min(m_workerThreads, blocks.size());

        runParallel(numThreads, [&](size_t) {
                std::vector<uint8_t> curBuf;
                std::vector<uint8_t> validMask;
                std::vector<CandidateEntry> blockHits;
                blockHits.reserve(8192);

                while (true) {
                    if (token && token->isCancelled()) break;
                    size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                    if (bIdx >= blocks.size()) break;

                    const auto& blk = blocks[bIdx];
                    uint8_t* oldPtr = m_snapshotStore.getBlockDataMut(blk);
                    if (!oldPtr) continue;

                    if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                    size_t chunkReadable = 0;
                    EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                    syscalls.fetch_add(1, std::memory_order_relaxed);
                    size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;

                    if (rc != EngineErrc::OK || chunkReadable == 0) {
                        if (progress) progress(doneBytes, totalSnapBytes);
                        continue;
                    }

                    blockHits.clear();
                    uint8_t rTag = regionFlagToIndex(blk.regionFlag);
                    switch (m_snapshotType) {
                        case DataType::INT8:
                            SimdKernel::compareBuffersDelta<int8_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<int8_t>(intDelta), rTag, blockHits, 1);
                            break;
                        case DataType::INT16:
                            SimdKernel::compareBuffersDelta<int16_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<int16_t>(intDelta), rTag, blockHits, 2);
                            break;
                        case DataType::INT32:
                            SimdKernel::compareBuffersDelta<int32_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<int32_t>(intDelta), rTag, blockHits, 4);
                            break;
                        case DataType::INT64:
                            SimdKernel::compareBuffersDelta<int64_t>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, intDelta, rTag, blockHits, stepSz);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::compareBuffersDelta<float>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, static_cast<float>(floatDelta), rTag, blockHits, 4);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::compareBuffersDelta<double>(blk.startAddress, curBuf.data(), oldPtr, blk.length, deltaOp, floatDelta, rTag, blockHits, stepSz);
                            break;
                        default:
                            break;
                    }

                    std::memcpy(oldPtr, curBuf.data(), blk.length);

                    if (!blockHits.empty()) {
                        size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                        m_totalMatchedCount = newTotal;

                        if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                            std::lock_guard<std::mutex> lk(storeMutex);
                            m_candidates.appendBatch(blockHits.data(), blockHits.size());
                            storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                        }
                    }

                    if (progress) progress(doneBytes, totalSnapBytes);
                }
        });
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

        size_t matched = totalMatches.load();
        if (deltaOp == SearchOp::UNCHANGED && matched > MmapCandidateStore::MAX_STORED_CANDIDATES) {
            m_candidates.clear();
            m_hasFullSnapshot = true;
            m_totalMatchedCount = matched;
        } else {
            m_snapshotStore.clear();
            m_hasFullSnapshot = false;
            m_candidates.sortByAddress();
            if (onlyNth > 1 && !m_candidates.empty()) {
                MmapCandidateStore filtered(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
                for (size_t i = 0; i < m_candidates.size(); ++i) {
                    if (((i + 1) % onlyNth) == 0) {
                        filtered.append(m_candidates[i]);
                    }
                }
                m_candidates = std::move(filtered);
                matched = m_candidates.size();
            }
            m_totalMatchedCount = std::max(matched, m_candidates.size());
        }

        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.totalBytesScanned = bytesScanned.load();
        m_lastTelemetry.readableBytes     = bytesScanned.load();
        m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued    = syscalls.load();
        m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk() || m_hasFullSnapshot;
        return EngineErrc::OK;
    }

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        DataType dt = static_cast<DataType>(m_candidates[idx].type);
        size_t itemSz = std::max<size_t>(1, dataTypeSize(dt));
        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            DataType nextDt = static_cast<DataType>(m_candidates[idx].type);
            size_t nextSz = std::max<size_t>(1, dataTypeSize(nextDt));
            uintptr_t nextEnd  = nextAddr + nextSz;
            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                DataType dt = static_cast<DataType>(cand.type);
                const uint8_t* valPtr = br.localBuf + (cand.address - grp.startPageAddr);

                bool keep = false;
                auto checkTyped = [&](auto oldVal, auto curVal, auto targetDelta) {
                    switch (deltaOp) {
                        case SearchOp::INCREASED:   return curVal > oldVal;
                        case SearchOp::DECREASED:   return curVal < oldVal;
                        case SearchOp::CHANGED:     return curVal != oldVal;
                        case SearchOp::UNCHANGED:   return curVal == oldVal;
                        case SearchOp::DELTA_EXACT: return (curVal == static_cast<decltype(curVal)>(oldVal + targetDelta)) ||
                                                           (curVal == static_cast<decltype(curVal)>(oldVal - targetDelta));
                        default:                    return false;
                    }
                };

                switch (dt) {
                    case DataType::INT8: {
                        int8_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 1);
                        std::memcpy(&c, valPtr, 1);
                        keep = checkTyped(o, c, static_cast<int8_t>(intDelta));
                        break;
                    }
                    case DataType::INT16: {
                        int16_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 2);
                        std::memcpy(&c, valPtr, 2);
                        keep = checkTyped(o, c, static_cast<int16_t>(intDelta));
                        break;
                    }
                    case DataType::INT32: {
                        int32_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 4);
                        std::memcpy(&c, valPtr, 4);
                        keep = checkTyped(o, c, static_cast<int32_t>(intDelta));
                        break;
                    }
                    case DataType::INT64: {
                        int64_t o = 0, c = 0;
                        std::memcpy(&o, &cand.rawValue, 8);
                        std::memcpy(&c, valPtr, 8);
                        keep = checkTyped(o, c, intDelta);
                        break;
                    }
                    case DataType::FLOAT: {
                        float o = 0.0f, c = 0.0f;
                        std::memcpy(&o, &cand.rawValue, 4);
                        std::memcpy(&c, valPtr, 4);
                        if (!std::isnan(o) && !std::isnan(c)) {
                            if (deltaOp == SearchOp::DELTA_EXACT) {
                                keep = std::fabs(static_cast<double>(c - o) - floatDelta) <= epsilon ||
                                       std::fabs(static_cast<double>(o - c) - floatDelta) <= epsilon;
                            } else if (deltaOp == SearchOp::UNCHANGED) {
                                keep = std::fabs(static_cast<double>(c - o)) <= epsilon;
                            } else if (deltaOp == SearchOp::CHANGED) {
                                keep = std::fabs(static_cast<double>(c - o)) > epsilon;
                            } else {
                                keep = checkTyped(o, c, static_cast<float>(floatDelta));
                            }
                        }
                        break;
                    }
                    case DataType::DOUBLE: {
                        double o = 0.0, c = 0.0;
                        std::memcpy(&o, &cand.rawValue, 8);
                        std::memcpy(&c, valPtr, 8);
                        if (!std::isnan(o) && !std::isnan(c)) {
                            if (deltaOp == SearchOp::DELTA_EXACT) {
                                keep = std::fabs((c - o) - floatDelta) <= epsilon ||
                                       std::fabs((o - c) - floatDelta) <= epsilon;
                            } else if (deltaOp == SearchOp::UNCHANGED) {
                                keep = std::fabs(c - o) <= epsilon;
                            } else if (deltaOp == SearchOp::CHANGED) {
                                keep = std::fabs(c - o) > epsilon;
                            } else {
                                keep = checkTyped(o, c, floatDelta);
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }

                if (keep) {
                    cand.rawValue = extractRawValue(valPtr, dt);
                    nextStore.append(cand);
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    m_candidates = std::move(nextStore);
    if (onlyNth > 1 && !m_candidates.empty()) {
        MmapCandidateStore filtered(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
        for (size_t i = 0; i < m_candidates.size(); ++i) {
            if (((i + 1) % onlyNth) == 0) {
                filtered.append(m_candidates[i]);
            }
        }
        m_candidates = std::move(filtered);
    }
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 4. Encoded & XOR Search (Offset, Static XOR, XOR Pair 32/64, Address XOR, GG_AUTO, XOR_OFFSET)
// ============================================================================
namespace {

uint64_t encodeTargetBits(DataType type, int64_t logicalInt, double logicalFloat) noexcept {
    switch (type) {
        case DataType::INT8:
            return static_cast<uint8_t>(logicalInt);
        case DataType::INT16:
            return static_cast<uint16_t>(logicalInt);
        case DataType::INT32:
            return static_cast<uint32_t>(logicalInt);
        case DataType::INT64:
            return static_cast<uint64_t>(logicalInt);
        case DataType::FLOAT: {
            float fv = static_cast<float>(logicalFloat);
            uint32_t bits = 0;
            std::memcpy(&bits, &fv, 4);
            return bits;
        }
        case DataType::DOUBLE: {
            uint64_t bits = 0;
            std::memcpy(&bits, &logicalFloat, 8);
            return bits;
        }
        default:
            return static_cast<uint32_t>(logicalInt);
    }
}

int64_t signExtendRaw(uint64_t raw, DataType dt) noexcept {
    switch (dt) {
        case DataType::INT8:   return static_cast<int8_t>(raw & 0xFFu);
        case DataType::INT16:  return static_cast<int16_t>(raw & 0xFFFFu);
        case DataType::INT32:  return static_cast<int32_t>(raw & 0xFFFFFFFFu);
        default:               return static_cast<int64_t>(raw);
    }
}

bool evalGroupField(const uint8_t* ptr, const GroupFieldItem& fld) noexcept {
    if (fld.op == 3) return true;
    if (fld.op == 1) {
        return evalSingleRange(ptr, fld.type, fld.iVal, fld.iMax, fld.fVal, fld.fMax, true);
    }
    if (fld.op == 2) {
        return !evalSingleRange(ptr, fld.type, fld.iVal, fld.iMax, fld.fVal, fld.fMax, true);
    }
    return evalSingleExact(ptr, fld.type, fld.iVal, fld.fVal, fld.epsilon);
}

} // anonymous namespace

EngineErrc SearchEngine::searchEncoded(
    DataType type,
    EncodedMode mode,
    int64_t logicalInt,
    double logicalFloat,
    uint64_t keyOrMask,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    DataType effectiveType = (type == DataType::AUTO) ? DataType::INT32 : type;

    if (mode == EncodedMode::GG_AUTO) {
        EngineErrc rc = startUnknownSnapshot(effectiveType, token, progress);
        if (rc == EngineErrc::OK) {
            m_encryptedSnapshot = true;
            m_encPrevLogicalInt = logicalInt;
            m_encPrevLogicalFlt = logicalFloat;
            m_encPrevTargetBits = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
        }
        return rc;
    }

    if (mode == EncodedMode::OFFSET_ADDED) {
        int64_t offsetKey = static_cast<int64_t>(keyOrMask);
        return searchExact(effectiveType, logicalInt + offsetKey, logicalFloat + static_cast<double>(offsetKey), 1e-4, aligned, token, progress);
    }
    if (mode == EncodedMode::XOR_STATIC) {
        uint64_t rawLogical = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
        int64_t encodedTarget = static_cast<int64_t>(rawLogical ^ keyOrMask);
        DataType intEquiv = (effectiveType == DataType::FLOAT) ? DataType::INT32
                          : (effectiveType == DataType::DOUBLE) ? DataType::INT64 : effectiveType;
        return searchExact(intEquiv, encodedTarget, 0.0, 0.0, aligned, token, progress);
    }

    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();

    size_t elemSz = std::max<size_t>(1, dataTypeSize(effectiveType));
    int64_t xorByteOffset = static_cast<int64_t>(keyOrMask);
    size_t absOffset = (mode == EncodedMode::XOR_OFFSET)
        ? static_cast<size_t>(std::min<int64_t>(std::llabs(xorByteOffset), 4096))
        : 0;
    size_t spanBytes = (mode == EncodedMode::XOR_PAIR_64) ? 16
                     : (mode == EncodedMode::XOR_PAIR_32) ? 8
                     : (mode == EncodedMode::XOR_OFFSET)  ? (elemSz + absOffset) : 4;
    size_t step = aligned ? std::min<size_t>(elemSz, 4) : 1;
    size_t overlap = spanBytes > 1 ? (spanBytes - 1) : 0;

    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    uint64_t targetBits64 = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
    uint32_t targetBits32 = static_cast<uint32_t>(targetBits64);

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    const size_t pageSize = m_io.pageSize();

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<size_t> offsets;
            std::vector<CandidateEntry> localHits;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    offsets.clear();
                    if (mode == EncodedMode::XOR_PAIR_32) {
                        SimdKernel::scanXorPair32(buf.data(), task.readLen, targetBits32, aligned ? 4 : 1, offsets);
                    } else if (mode == EncodedMode::XOR_PAIR_64) {
                        SimdKernel::scanXorPair64(buf.data(), task.readLen, targetBits64, aligned ? 8 : 1, offsets);
                    } else if (mode == EncodedMode::XOR_ADDRESS) {
                        uint32_t mask32 = (keyOrMask == 0) ? 0xFFFFFFFFu : static_cast<uint32_t>(keyOrMask);
                        SimdKernel::scanAddressXor32(task.startAddr, buf.data(), task.readLen, targetBits32, mask32, aligned ? 4 : 1, offsets);
                    } else if (mode == EncodedMode::XOR_OFFSET) {
                        size_t startOff = (xorByteOffset < 0) ? absOffset : 0;
                        if (step > 1 && (startOff % step) != 0) {
                            startOff += step - (startOff % step);
                        }
                        for (size_t off = startOff; off + elemSz <= task.logicalLen; off += step) {
                            int64_t keyPos = static_cast<int64_t>(off) + xorByteOffset;
                            if (keyPos < 0 || static_cast<size_t>(keyPos) + elemSz > task.readLen) continue;
                            uint64_t v = extractRawValue(buf.data() + off, effectiveType);
                            uint64_t k = extractRawValue(buf.data() + static_cast<size_t>(keyPos), effectiveType);
                            if (v != 0 && k != 0 && (v ^ k) == targetBits64) {
                                offsets.push_back(off);
                            }
                        }
                    }

                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                    size_t chunkMatches = 0;

                    for (size_t off : offsets) {
                        if (off >= task.logicalLen) continue;
                        size_t checkStart = (mode == EncodedMode::XOR_OFFSET && xorByteOffset < 0)
                            ? (off - absOffset) : off;
                        if (!isRangeInValidPages(checkStart, spanBytes, task.startAddr, pageSize, validMask)) continue;
                        uintptr_t candAddr = task.startAddr + off;
                        if (!matchesAddressFilter(candAddr)) continue;

                        chunkMatches++;
                        if (!storeAlreadyFull) {
                            CandidateEntry entry{};
                            entry.address   = candAddr;
                            entry.rawValue  = extractRawValue(buf.data() + off, effectiveType);
                            entry.type      = static_cast<uint8_t>(effectiveType);
                            entry.regionTag = rTag;
                            entry.flags     = static_cast<uint32_t>(mode);
                            localHits.push_back(entry);

                            if (localHits.size() >= 16384) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(localHits.data(), localHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                localHits.clear();
                                if (m_candidates.isFull()) storeAlreadyFull = true;
                            }
                        }
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
    });
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineEncoded(
    DataType type,
    EncodedMode mode,
    int64_t logicalInt,
    double logicalFloat,
    uint64_t keyOrMask,
    CancellationToken* token,
    ProgressCallback progress
) {
    DataType effectiveType = (type == DataType::AUTO) ? m_snapshotType : type;

    if (mode == EncodedMode::OFFSET_ADDED) {
        int64_t offsetKey = static_cast<int64_t>(keyOrMask);
        return refineExact(effectiveType, logicalInt + offsetKey, logicalFloat + static_cast<double>(offsetKey), 1e-4, token, progress);
    }
    if (mode == EncodedMode::XOR_STATIC) {
        uint64_t rawLogical = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
        DataType intEquiv = (effectiveType == DataType::FLOAT) ? DataType::INT32
                          : (effectiveType == DataType::DOUBLE) ? DataType::INT64 : effectiveType;
        return refineExact(intEquiv, static_cast<int64_t>(rawLogical ^ keyOrMask), 0.0, 0.0, token, progress);
    }

    auto t0 = std::chrono::steady_clock::now();
    uint64_t targetBits64 = encodeTargetBits(effectiveType, logicalInt, logicalFloat);
    uint32_t targetBits32 = static_cast<uint32_t>(targetBits64);

    // ========================================================================
    // Stateful GG Encrypted Search Refinement (EncodedMode::GG_AUTO)
    // ========================================================================
    if (mode == EncodedMode::GG_AUTO) {
        const uint64_t uPrev = m_encPrevTargetBits;
        const uint64_t uCur  = targetBits64;
        const int64_t  iPrev = m_encPrevLogicalInt;
        const int64_t  iCur  = logicalInt;
        const DataType dt    = m_hasFullSnapshot ? m_snapshotType : effectiveType;
        const size_t elemSz  = std::max<size_t>(1, dataTypeSize(dt));
        const size_t stepSz  = std::min<size_t>(elemSz, 4);
        const bool isFloatType = (dt == DataType::FLOAT || dt == DataType::DOUBLE);

        // Pass 1: Refine directly against full resident snapshot (V1 -> V2)
        if (m_hasFullSnapshot && m_candidates.empty()) {
            const auto& blocks = m_snapshotStore.blocks();
            if (blocks.empty()) {
                m_totalMatchedCount = 0;
                return EngineErrc::OK;
            }

            const size_t totalSnapBytes = std::max<size_t>(1, m_snapshotStore.totalSnapshotBytes());
            std::atomic<size_t> nextBlock{0};
            std::atomic<size_t> syscalls{0};
            std::atomic<size_t> bytesScanned{0};
            std::atomic<size_t> totalMatches{0};
            std::atomic<size_t> storedCount{0};
            std::mutex storeMutex;

            size_t numThreads = std::min(m_workerThreads, blocks.size());

            runParallel(numThreads, [&](size_t) {
                    std::vector<uint8_t> curBuf;
                    std::vector<uint8_t> validMask;
                    std::vector<CandidateEntry> blockHits;
                    blockHits.reserve(4096);

                    while (true) {
                        if (token && token->isCancelled()) break;
                        size_t bIdx = nextBlock.fetch_add(1, std::memory_order_relaxed);
                        if (bIdx >= blocks.size()) break;

                        const auto& blk = blocks[bIdx];
                        uint8_t* oldPtr = m_snapshotStore.getBlockDataMut(blk);
                        if (!oldPtr) continue;

                        if (curBuf.size() < blk.length) curBuf.resize(blk.length);
                        size_t chunkReadable = 0;
                        EngineErrc rc = m_io.readChunkFaultTolerant(blk.startAddress, curBuf.data(), blk.length, validMask, &chunkReadable);
                        syscalls.fetch_add(1, std::memory_order_relaxed);
                        size_t doneBytes = bytesScanned.fetch_add(blk.length, std::memory_order_relaxed) + blk.length;

                        if (rc != EngineErrc::OK || chunkReadable == 0) {
                            if (progress) progress(doneBytes, totalSnapBytes);
                            continue;
                        }

                        blockHits.clear();
                        uint8_t rTag = regionFlagToIndex(blk.regionFlag);

                        for (size_t off = 0; off + elemSz <= blk.length; off += stepSz) {
                            uint64_t e1 = extractRawValue(oldPtr + off, dt);
                            uint64_t e2 = extractRawValue(curBuf.data() + off, dt);
                            if (e1 == 0 && e2 == 0) continue;

                            uint32_t matchFlags = 0;

                            // 1. Constant XOR Key Invariant: (E1 ^ U1) == (E2 ^ U2)
                            if (uCur != uPrev) {
                                if (e1 != e2 && ((e1 ^ uPrev) == (e2 ^ uCur))) {
                                    matchFlags |= 1u;
                                }
                            } else {
                                if (e1 == e2 && e1 != 0) {
                                    matchFlags |= 1u;
                                }
                            }

                            // 2. Dynamic Neighbor XOR Key Invariant (ObscuredInt / struct [key, val] at ±4, ±8)
                            if (elemSz == 4 || elemSz == 8) {
                                const int deltas[4] = {-8, -4, 4, 8};
                                for (int d : deltas) {
                                    if (elemSz == 8 && (d == -4 || d == 4)) continue;
                                    int64_t nPos = static_cast<int64_t>(off) + d;
                                    if (nPos < 0 || static_cast<size_t>(nPos) + elemSz > blk.length) continue;
                                    uint64_t n1 = extractRawValue(oldPtr + static_cast<size_t>(nPos), dt);
                                    uint64_t n2 = extractRawValue(curBuf.data() + static_cast<size_t>(nPos), dt);
                                    if (n1 != 0 && n2 != 0 && ((e1 ^ n1) == uPrev) && ((e2 ^ n2) == uCur)) {
                                        if (uCur == uPrev || e1 != e2 || n1 != n2) {
                                            matchFlags |= 2u;
                                            break;
                                        }
                                    }
                                }
                            }

                            // 3. Constant Additive Offset Invariant: (E2 - E1) == (V2 - V1) or (E1 - E2) == (V2 - V1)
                            if (!isFloatType && iCur != iPrev && e1 != 0 && e2 != 0) {
                                int64_t s1 = signExtendRaw(e1, dt);
                                int64_t s2 = signExtendRaw(e2, dt);
                                int64_t vDiff = iCur - iPrev;
                                if ((s2 - s1) == vDiff) {
                                    matchFlags |= 4u;
                                } else if ((s1 - s2) == vDiff) {
                                    matchFlags |= 8u;
                                }
                            }

                            if (matchFlags != 0) {
                                CandidateEntry entry{};
                                entry.address   = blk.startAddress + off;
                                entry.rawValue  = e2;
                                entry.type      = static_cast<uint8_t>(dt);
                                entry.regionTag = rTag;
                                entry.flags     = matchFlags;
                                blockHits.push_back(entry);
                            }
                        }

                        std::memcpy(oldPtr, curBuf.data(), blk.length);

                        if (!blockHits.empty()) {
                            size_t newTotal = totalMatches.fetch_add(blockHits.size(), std::memory_order_relaxed) + blockHits.size();
                            m_totalMatchedCount = newTotal;

                            if (storedCount.load(std::memory_order_relaxed) < MmapCandidateStore::MAX_STORED_CANDIDATES) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(blockHits.data(), blockHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                            }
                        }

                        if (progress) progress(doneBytes, totalSnapBytes);
                    }
            });
            if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

            m_encPrevLogicalInt = logicalInt;
            m_encPrevLogicalFlt = logicalFloat;
            m_encPrevTargetBits = targetBits64;

            size_t matched = totalMatches.load();
            if (uCur == uPrev && matched > MmapCandidateStore::MAX_STORED_CANDIDATES) {
                m_candidates.clear();
                m_hasFullSnapshot = true;
                m_totalMatchedCount = matched;
            } else {
                m_snapshotStore.clear();
                m_hasFullSnapshot = false;
                m_candidates.sortByAddress();
                m_totalMatchedCount = std::max(matched, m_candidates.size());
            }

            auto t1 = std::chrono::steady_clock::now();
            m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
            m_lastTelemetry.totalBytesScanned = bytesScanned.load();
            m_lastTelemetry.readableBytes     = bytesScanned.load();
            m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
            m_lastTelemetry.syscallsIssued    = syscalls.load();
            return EngineErrc::OK;
        }

        // Pass 2+: Coalesced batched refinement on existing m_candidates (V2 -> V3 -> ...)
        if (m_candidates.empty()) {
            m_totalMatchedCount = 0;
            return EngineErrc::OK;
        }

        m_candidates.sortByAddress();
        const size_t count = m_candidates.size();
        MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
        size_t syscalls = 0;

        constexpr size_t BATCH_LIMIT = 2048;
        for (size_t base = 0; base < count; base += BATCH_LIMIT) {
            if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
            size_t end = std::min(count, base + BATCH_LIMIT);
            size_t batchCount = end - base;

            std::vector<uint8_t> slab(batchCount * 24, 0);
            std::vector<BatchReadRange> ranges(batchCount);
            for (size_t k = 0; k < batchCount; ++k) {
                uintptr_t cAddr = m_candidates[base + k].address;
                ranges[k].remoteAddr = (cAddr >= 8) ? (cAddr - 8) : cAddr;
                ranges[k].length     = (cAddr >= 8) ? 24 : 8;
                ranges[k].localBuf   = slab.data() + (k * 24);
            }
            m_io.readBatchScattered(ranges);
            syscalls += (batchCount + m_io.iovMax() - 1) / m_io.iovMax();

            for (size_t k = 0; k < batchCount; ++k) {
                CandidateEntry cand = m_candidates[base + k];
                DataType cDt = static_cast<DataType>(cand.type);
                size_t cSz = std::max<size_t>(1, dataTypeSize(cDt));

                const uint8_t* winBuf = slab.data() + (k * 24);
                bool hasWin = (cand.address >= 8) && ranges[k].success;
                uint64_t e1 = cand.rawValue;
                uint64_t e2 = 0;

                if (hasWin) {
                    e2 = extractRawValue(winBuf + 8, cDt);
                } else if (ranges[k].success && cand.address < 8) {
                    e2 = extractRawValue(winBuf, cDt);
                } else {
                    uint8_t fallback[8] = {0};
                    if (m_io.readMemory(cand.address, fallback, cSz) != EngineErrc::OK) continue;
                    e2 = extractRawValue(fallback, cDt);
                }

                uint32_t prevFlags = (cand.flags == 0) ? 0x0Fu : cand.flags;
                uint32_t nextFlags = 0;

                if (prevFlags & 1u) {
                    if (uCur != uPrev) {
                        if (e1 != e2 && ((e1 ^ uPrev) == (e2 ^ uCur))) nextFlags |= 1u;
                    } else {
                        if (e1 == e2) nextFlags |= 1u;
                    }
                }
                if ((prevFlags & 2u) && hasWin && (cSz == 4 || cSz == 8)) {
                    const int offsetsInWin[4] = {0, 4, 12, 16};
                    for (int wOff : offsetsInWin) {
                        if (cSz == 8 && (wOff == 4 || wOff == 12)) continue;
                        uint64_t n2 = extractRawValue(winBuf + wOff, cDt);
                        if (n2 != 0 && ((e2 ^ n2) == uCur)) {
                            nextFlags |= 2u;
                            break;
                        }
                    }
                }
                if ((prevFlags & 4u) && !isFloatType) {
                    int64_t s1 = signExtendRaw(e1, cDt);
                    int64_t s2 = signExtendRaw(e2, cDt);
                    if ((s2 - s1) == (iCur - iPrev)) nextFlags |= 4u;
                }
                if ((prevFlags & 8u) && !isFloatType) {
                    int64_t s1 = signExtendRaw(e1, cDt);
                    int64_t s2 = signExtendRaw(e2, cDt);
                    if ((s1 - s2) == (iCur - iPrev)) nextFlags |= 8u;
                }

                if (nextFlags != 0) {
                    cand.rawValue = e2;
                    cand.flags    = nextFlags;
                    nextStore.append(cand);
                }
            }
            if (progress) progress(end, count);
        }

        m_encPrevLogicalInt = logicalInt;
        m_encPrevLogicalFlt = logicalFloat;
        m_encPrevTargetBits = targetBits64;
        m_candidates = std::move(nextStore);
        m_totalMatchedCount = m_candidates.size();

        auto t1 = std::chrono::steady_clock::now();
        m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
        m_lastTelemetry.candidatesFound = m_totalMatchedCount;
        m_lastTelemetry.syscallsIssued  = syscalls;
        return EngineErrc::OK;
    }

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    size_t elemSz = std::max<size_t>(1, dataTypeSize(effectiveType));
    int64_t xorByteOffset = static_cast<int64_t>(keyOrMask);
    size_t spanBytes = (mode == EncodedMode::XOR_PAIR_64) ? 16
                     : (mode == EncodedMode::XOR_PAIR_32) ? 8 : 4;

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscalls = 0;

    constexpr size_t BATCH_LIMIT = 2048;
    for (size_t base = 0; base < count; base += BATCH_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t end = std::min(count, base + BATCH_LIMIT);
        size_t batchCount = end - base;

        if (mode == EncodedMode::XOR_OFFSET) {
            std::vector<uint8_t> slab(batchCount * 16, 0);
            std::vector<BatchReadRange> ranges(batchCount * 2);
            for (size_t k = 0; k < batchCount; ++k) {
                uintptr_t vAddr = m_candidates[base + k].address;
                uintptr_t kAddr = static_cast<uintptr_t>(static_cast<int64_t>(vAddr) + xorByteOffset);
                ranges[k * 2 + 0] = BatchReadRange{vAddr, elemSz, slab.data() + (k * 16), false};
                ranges[k * 2 + 1] = BatchReadRange{kAddr, elemSz, slab.data() + (k * 16 + 8), false};
            }
            m_io.readBatchScattered(ranges);
            syscalls += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

            for (size_t k = 0; k < batchCount; ++k) {
                if (!ranges[k * 2 + 0].success || !ranges[k * 2 + 1].success) continue;
                CandidateEntry cand = m_candidates[base + k];
                uint64_t v = extractRawValue(slab.data() + (k * 16), effectiveType);
                uint64_t keyVal = extractRawValue(slab.data() + (k * 16 + 8), effectiveType);
                if (v != 0 && keyVal != 0 && (v ^ keyVal) == targetBits64) {
                    cand.rawValue = v;
                    nextStore.append(cand);
                }
            }
        } else {
            std::vector<uint8_t> slab(batchCount * 16, 0);
            std::vector<BatchReadRange> ranges(batchCount);
            for (size_t k = 0; k < batchCount; ++k) {
                ranges[k] = BatchReadRange{static_cast<uintptr_t>(m_candidates[base + k].address), spanBytes, slab.data() + (k * 16), false};
            }
            m_io.readBatchScattered(ranges);
            syscalls += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

            for (size_t k = 0; k < batchCount; ++k) {
                if (!ranges[k].success) continue;
                CandidateEntry cand = m_candidates[base + k];
                const uint8_t* rawBuf = slab.data() + (k * 16);
                bool matched = false;
                uint64_t curRaw = cand.rawValue;

                if (mode == EncodedMode::XOR_PAIR_32) {
                    uint32_t w0 = 0, w1 = 0;
                    std::memcpy(&w0, rawBuf, 4);
                    std::memcpy(&w1, rawBuf + 4, 4);
                    matched = (w0 != 0 && w1 != 0 && (w0 ^ w1) == targetBits32);
                    curRaw = w0;
                } else if (mode == EncodedMode::XOR_PAIR_64) {
                    uint64_t q0 = 0, q1 = 0;
                    std::memcpy(&q0, rawBuf, 8);
                    std::memcpy(&q1, rawBuf + 8, 8);
                    matched = (q0 != 0 && q1 != 0 && (q0 ^ q1) == targetBits64);
                    curRaw = q0;
                } else if (mode == EncodedMode::XOR_ADDRESS) {
                    uint32_t stored = 0;
                    std::memcpy(&stored, rawBuf, 4);
                    uint32_t mask32 = (keyOrMask == 0) ? 0xFFFFFFFFu : static_cast<uint32_t>(keyOrMask);
                    uint32_t addrKey = static_cast<uint32_t>(cand.address) & mask32;
                    matched = ((stored ^ addrKey) == targetBits32);
                    curRaw = stored;
                }

                if (matched) {
                    cand.rawValue = curRaw;
                    nextStore.append(cand);
                }
            }
        }
        if (progress) progress(end, count);
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscalls;
    return EngineErrc::OK;
}

// ============================================================================
// 5. Group / Struct Search (Ordered & Unordered with Anchor-First Scheduling)
// ============================================================================
EngineErrc SearchEngine::searchGroup(
    const GroupSearchQuery& query,
    CancellationToken* token,
    ProgressCallback progress
) {
    if (query.fields.empty()) return EngineErrc::ERR_INVAL;
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();

    size_t anchorIdx = 0;
    uint32_t bestScore = 0;
    for (size_t i = 0; i < query.fields.size(); ++i) {
        uint32_t sc = query.fields[i].computeSelectivityScore();
        if (sc > bestScore) {
            bestScore = sc;
            anchorIdx = i;
        }
    }
    const GroupFieldItem& anchor = query.fields[anchorIdx];

    size_t overlap = query.maxSpanBytes * 2 + 16;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    const size_t pageSize = m_io.pageSize();
    const bool pagemapZeroKnown = m_io.hasPagemapResidency() && (m_io.zeroPagePfn() != 0);
    const uint8_t zeroBytes[8] = {0};
    const bool anchorCanMatchZero = evalGroupField(zeroBytes, anchor);

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<uint8_t> scanMask;
            std::vector<size_t> anchorOffsets;
            std::vector<CandidateEntry> localHits;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc != EngineErrc::OK || chunkReadable == 0) {
                    size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                    if (progress) progress(done, totalPlannedBytes);
                    continue;
                }

                buildScanPageMask(
                    buf.data(), task.readLen, pageSize, task.regionFlag,
                    anchorCanMatchZero, pagemapZeroKnown, validMask, scanMask
                );
                anchorOffsets.clear();
                size_t anchorSz = std::max<size_t>(1, dataTypeSize(anchor.type));
                size_t anchorStep = query.aligned ? std::min<size_t>(anchorSz, 4) : 1;

                if (anchor.op == 1) {
                    switch (anchor.type) {
                        case DataType::INT8:
                        case DataType::INT16:
                        case DataType::INT32:
                        case DataType::INT64:
                            SimdKernel::scanRangeInteger(buf.data(), task.readLen, anchor.type, anchor.iVal, anchor.iMax, true, anchorStep, anchorOffsets);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::scanRangeF32(buf.data(), task.readLen, static_cast<float>(anchor.fVal), static_cast<float>(anchor.fMax), true, anchorStep, anchorOffsets);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::scanRange<double>(buf.data(), task.readLen, anchor.fVal, anchor.fMax, true, anchorStep, anchorOffsets);
                            break;
                        default:
                            break;
                    }
                } else if (anchor.op == 0) {
                    switch (anchor.type) {
                        case DataType::INT8:
                            SimdKernel::scanExactI8(buf.data(), task.readLen, static_cast<int8_t>(anchor.iVal), anchorOffsets);
                            break;
                        case DataType::INT16:
                            SimdKernel::scanExactI16(buf.data(), task.readLen, static_cast<int16_t>(anchor.iVal), anchorStep, anchorOffsets);
                            break;
                        case DataType::INT32:
                            SimdKernel::scanExactI32(buf.data(), task.readLen, static_cast<int32_t>(anchor.iVal), anchorStep, anchorOffsets);
                            break;
                        case DataType::INT64:
                            SimdKernel::scanExactI64(buf.data(), task.readLen, anchor.iVal, anchorStep, anchorOffsets);
                            break;
                        case DataType::FLOAT:
                            SimdKernel::scanExactF32(buf.data(), task.readLen, static_cast<float>(anchor.fVal), static_cast<float>(anchor.epsilon), anchorStep, anchorOffsets);
                            break;
                        case DataType::DOUBLE:
                            SimdKernel::scanExactF64(buf.data(), task.readLen, anchor.fVal, anchor.epsilon, anchorStep, anchorOffsets);
                            break;
                        default:
                            break;
                    }
                } else {
                    for (size_t off = 0; off + anchorSz <= task.readLen; off += anchorStep) {
                        if (evalGroupField(buf.data() + off, anchor)) {
                            anchorOffsets.push_back(off);
                        }
                    }
                }

                uint8_t rTag = regionFlagToIndex(task.regionFlag);
                bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                size_t chunkMatches = 0;
                const size_t numFields = query.fields.size();
                std::vector<size_t> matchedOffsets(numFields, 0);

                for (size_t aOff : anchorOffsets) {
                    size_t anchorPage = aOff / pageSize;
                    if (anchorPage >= scanMask.size() || scanMask[anchorPage] == 0) continue;
                    if (!isRangeInValidPages(aOff, anchorSz, task.startAddr, pageSize, validMask)) continue;

                    size_t winStart = (aOff > query.maxSpanBytes) ? (aOff - query.maxSpanBytes) : 0;
                    size_t winEnd   = std::min(task.readLen, aOff + query.maxSpanBytes + 16);

                    bool allMatched = true;
                    size_t minMatchedOffset = aOff;
                    size_t maxMatchedEnd    = aOff + anchorSz;
                    matchedOffsets[anchorIdx] = aOff;

                    if (query.ordered) {
                        size_t curUpperBound = aOff;
                        size_t firstFieldOffset = aOff;

                        for (size_t rev = 0; rev < anchorIdx; ++rev) {
                            size_t fIdx = anchorIdx - 1 - rev;
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = std::max<size_t>(1, dataTypeSize(fld.type));
                            size_t fStep = query.aligned ? std::min<size_t>(fSz, 4) : 1;

                            if (curUpperBound < winStart + fSz) {
                                allMatched = false;
                                break;
                            }

                            size_t maxPos = curUpperBound - fSz;
                            if (fStep > 1) {
                                maxPos -= (maxPos % fStep);
                            }

                            bool foundField = false;
                            for (size_t pos = maxPos; pos >= winStart; ) {
                                if (evalGroupField(buf.data() + pos, fld)) {
                                    matchedOffsets[fIdx] = pos;
                                    curUpperBound = pos;
                                    if (fIdx == 0) firstFieldOffset = pos;
                                    foundField = true;
                                    break;
                                }
                                if (pos < winStart + fStep) break;
                                pos -= fStep;
                            }
                            if (!foundField) {
                                allMatched = false;
                                break;
                            }
                        }

                        if (!allMatched) continue;
                        minMatchedOffset = (anchorIdx == 0) ? aOff : firstFieldOffset;

                        size_t prevEnd = aOff + anchorSz;
                        size_t maxAllowedStart = minMatchedOffset + query.maxSpanBytes;

                        for (size_t fIdx = anchorIdx + 1; fIdx < numFields; ++fIdx) {
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = std::max<size_t>(1, dataTypeSize(fld.type));
                            size_t fStep = query.aligned ? std::min<size_t>(fSz, 4) : 1;
                            size_t alignedStart = prevEnd;
                            if (fStep > 1 && (alignedStart % fStep) != 0) {
                                alignedStart += fStep - (alignedStart % fStep);
                            }

                            bool foundField = false;
                            for (size_t pos = alignedStart; pos <= maxAllowedStart && pos + fSz <= task.readLen; pos += fStep) {
                                if (evalGroupField(buf.data() + pos, fld)) {
                                    matchedOffsets[fIdx] = pos;
                                    prevEnd = pos + fSz;
                                    maxMatchedEnd = prevEnd;
                                    foundField = true;
                                    break;
                                }
                            }
                            if (!foundField) {
                                allMatched = false;
                                break;
                            }
                        }

                        if (allMatched && (matchedOffsets[numFields - 1] - matchedOffsets[0]) > query.maxSpanBytes) {
                            allMatched = false;
                        }
                    } else {
                        size_t minStartOff = aOff;
                        size_t maxStartOff = aOff;
                        size_t maxEndOff   = aOff + anchorSz;

                        for (size_t fIdx = 0; fIdx < numFields; ++fIdx) {
                            if (fIdx == anchorIdx) continue;
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = std::max<size_t>(1, dataTypeSize(fld.type));
                            size_t fStep = query.aligned ? std::min<size_t>(fSz, 4) : 1;

                            auto overlapsMatched = [&](size_t candPos, size_t candSz) noexcept -> bool {
                                size_t candEnd = candPos + candSz;
                                if (candPos < aOff + anchorSz && aOff < candEnd) return true;
                                for (size_t prevIdx = 0; prevIdx < fIdx; ++prevIdx) {
                                    if (prevIdx == anchorIdx) continue;
                                    size_t pPos = matchedOffsets[prevIdx];
                                    size_t pSz  = std::max<size_t>(1, dataTypeSize(query.fields[prevIdx].type));
                                    if (candPos < pPos + pSz && pPos < candEnd) return true;
                                }
                                return false;
                            };

                            size_t center = aOff;
                            if (fStep > 1 && (center % fStep) != 0) {
                                center -= (center % fStep);
                            }

                            bool foundField = false;
                            size_t bestPos = 0;
                            size_t maxRadius = query.maxSpanBytes;

                            for (size_t dist = 0; dist <= maxRadius; dist += fStep) {
                                for (int dir = 0; dir < 2; ++dir) {
                                    if (dist == 0 && dir == 1) continue;
                                    size_t pos = 0;
                                    if (dir == 0) {
                                        if (center < dist || center - dist < winStart) continue;
                                        pos = center - dist;
                                    } else {
                                        pos = center + dist;
                                    }
                                    if (pos + fSz > winEnd || pos + fSz > task.readLen) continue;
                                    if (overlapsMatched(pos, fSz)) continue;

                                    size_t candMinStart = std::min(minStartOff, pos);
                                    size_t candMaxStart = std::max(maxStartOff, pos);
                                    if ((candMaxStart - candMinStart) > query.maxSpanBytes) continue;

                                    if (evalGroupField(buf.data() + pos, fld)) {
                                        bestPos = pos;
                                        foundField = true;
                                        break;
                                    }
                                }
                                if (foundField) break;
                            }

                            if (!foundField) {
                                allMatched = false;
                                break;
                            }

                            matchedOffsets[fIdx] = bestPos;
                            minStartOff = std::min(minStartOff, bestPos);
                            maxStartOff = std::max(maxStartOff, bestPos);
                            maxEndOff   = std::max(maxEndOff, bestPos + fSz);
                        }

                        minMatchedOffset = minStartOff;
                        maxMatchedEnd    = maxEndOff;
                    }

                    if (allMatched && minMatchedOffset < task.logicalLen) {
                        size_t groupSpan = maxMatchedEnd - minMatchedOffset;
                        if (isRangeInValidPages(minMatchedOffset, groupSpan, task.startAddr, pageSize, validMask)) {
                            for (size_t k = 0; k < numFields; ++k) {
                                size_t fOff = matchedOffsets[k];
                                DataType fDt = query.fields[k].type;
                                chunkMatches++;
                                if (!storeAlreadyFull) {
                                    CandidateEntry entry{};
                                    entry.address   = task.startAddr + fOff;
                                    entry.rawValue  = extractRawValue(buf.data() + fOff, fDt);
                                    entry.type      = static_cast<uint8_t>(fDt);
                                    entry.regionTag = rTag;
                                    localHits.push_back(entry);
                                }
                            }

                            if (!storeAlreadyFull && localHits.size() >= 16384) {
                                std::lock_guard<std::mutex> lk(storeMutex);
                                m_candidates.appendBatch(localHits.data(), localHits.size());
                                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                localHits.clear();
                                if (m_candidates.isFull()) storeAlreadyFull = true;
                            }
                        }
                    }
                }

                if (chunkMatches > 0) {
                    m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
    });
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = m_candidates.isFull()
        ? std::max(totalMatches.load(), m_candidates.size())
        : m_candidates.size();
    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.anchorFieldIndex  = anchorIdx;
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refineGroup(
    const GroupSearchQuery& query,
    CancellationToken* token,
    ProgressCallback progress
) {
    if (query.fields.empty()) return EngineErrc::ERR_INVAL;
    auto t0 = std::chrono::steady_clock::now();

    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        if (progress) progress(1, 1);
        return EngineErrc::OK;
    }

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t numFields = query.fields.size();
    const size_t maxSpan = std::max<size_t>(8, query.maxSpanBytes);

    struct CoalescedGroup {
        uintptr_t readStartAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t firstAddr = m_candidates[idx].address;
        DataType dt0 = static_cast<DataType>(m_candidates[idx].type);
        size_t sz0 = std::max<size_t>(1, dataTypeSize(dt0));
        uintptr_t clusterMin = firstAddr;
        uintptr_t clusterMaxEnd = firstAddr + sz0;
        size_t firstIdx = idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            DataType nextDt = static_cast<DataType>(m_candidates[idx].type);
            size_t nextSz = std::max<size_t>(1, dataTypeSize(nextDt));
            uintptr_t nextEnd = nextAddr + nextSz;
            if (nextAddr <= clusterMaxEnd + maxSpan + 256 && (nextEnd - clusterMin) <= 32768) {
                if (nextEnd > clusterMaxEnd) clusterMaxEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }

        uintptr_t readStart = clusterMin;
        uintptr_t readEnd   = clusterMaxEnd;
        if ((idx - firstIdx) < numFields) {
            uintptr_t pageFloor = clusterMin & ~static_cast<uintptr_t>(4095);
            uintptr_t pageCeil  = (clusterMaxEnd + 4095) & ~static_cast<uintptr_t>(4095);
            if (pageCeil == clusterMaxEnd) pageCeil += 4096;
            readStart = (clusterMin > maxSpan) ? std::max(pageFloor, clusterMin - maxSpan) : pageFloor;
            readEnd   = std::min(pageCeil, clusterMaxEnd + maxSpan + 16);
        }

        groups.push_back(CoalescedGroup{readStart, readEnd - readStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    if (progress) progress(0, std::max<size_t>(1, groups.size()));

    constexpr size_t BATCH_GROUP_LIMIT = 1024;
    std::vector<size_t> matchedCandIndices(numFields, 0);

    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].readStartAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            const size_t candCountInGrp = grp.lastCandIdx - grp.firstCandIdx;

            if (candCountInGrp >= numFields) {
                for (size_t startC = grp.firstCandIdx; startC < grp.lastCandIdx; ++startC) {
                    const CandidateEntry& c0 = m_candidates[startC];
                    const uint8_t* ptr0 = br.localBuf + (c0.address - grp.readStartAddr);

                    if (query.ordered) {
                        if (!evalGroupField(ptr0, query.fields[0])) continue;
                        matchedCandIndices[0] = startC;
                        uintptr_t prevEndAddr = c0.address + std::max<size_t>(1, dataTypeSize(query.fields[0].type));
                        bool allOk = true;
                        size_t searchFrom = startC + 1;

                        for (size_t fIdx = 1; fIdx < numFields; ++fIdx) {
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = std::max<size_t>(1, dataTypeSize(fld.type));
                            bool foundF = false;
                            for (size_t candIdx = searchFrom; candIdx < grp.lastCandIdx; ++candIdx) {
                                const CandidateEntry& cm = m_candidates[candIdx];
                                if (cm.address < prevEndAddr) continue;
                                if ((cm.address - c0.address) > query.maxSpanBytes) break;
                                const uint8_t* ptrM = br.localBuf + (cm.address - grp.readStartAddr);
                                if (evalGroupField(ptrM, fld)) {
                                    matchedCandIndices[fIdx] = candIdx;
                                    prevEndAddr = cm.address + fSz;
                                    searchFrom = candIdx + 1;
                                    foundF = true;
                                    break;
                                }
                            }
                            if (!foundF) {
                                allOk = false;
                                break;
                            }
                        }

                        if (allOk) {
                            for (size_t fIdx = 0; fIdx < numFields; ++fIdx) {
                                CandidateEntry kept = m_candidates[matchedCandIndices[fIdx]];
                                DataType fDt = query.fields[fIdx].type;
                                const uint8_t* ptrK = br.localBuf + (kept.address - grp.readStartAddr);
                                kept.rawValue = extractRawValue(ptrK, fDt);
                                kept.type     = static_cast<uint8_t>(fDt);
                                nextStore.append(kept);
                            }
                        }
                    } else {
                        if (!evalGroupField(ptr0, query.fields[0])) continue;
                        matchedCandIndices[0] = startC;
                        uintptr_t minAddr = c0.address;
                        uintptr_t maxAddr = c0.address;
                        bool allOk = true;

                        for (size_t fIdx = 1; fIdx < numFields; ++fIdx) {
                            const auto& fld = query.fields[fIdx];
                            size_t fSz = std::max<size_t>(1, dataTypeSize(fld.type));
                            bool foundF = false;
                            size_t bestCandIdx = 0;
                            uint64_t bestDist = UINT64_MAX;

                            for (size_t candIdx = grp.firstCandIdx; candIdx < grp.lastCandIdx; ++candIdx) {
                                const CandidateEntry& cm = m_candidates[candIdx];
                                uintptr_t candMin = std::min(minAddr, static_cast<uintptr_t>(cm.address));
                                uintptr_t candMax = std::max(maxAddr, static_cast<uintptr_t>(cm.address));
                                if ((candMax - candMin) > query.maxSpanBytes) {
                                    if (cm.address > maxAddr + query.maxSpanBytes) break;
                                    continue;
                                }

                                bool overlaps = false;
                                for (size_t p = 0; p < fIdx; ++p) {
                                    uintptr_t pAddr = m_candidates[matchedCandIndices[p]].address;
                                    size_t pSz = std::max<size_t>(1, dataTypeSize(query.fields[p].type));
                                    if (cm.address < pAddr + pSz && pAddr < cm.address + fSz) {
                                        overlaps = true;
                                        break;
                                    }
                                }
                                if (overlaps) continue;

                                const uint8_t* ptrM = br.localBuf + (cm.address - grp.readStartAddr);
                                if (evalGroupField(ptrM, fld)) {
                                    uint64_t dist = (cm.address >= c0.address)
                                        ? (cm.address - c0.address)
                                        : (c0.address - cm.address);
                                    if (dist < bestDist) {
                                        bestDist = dist;
                                        bestCandIdx = candIdx;
                                        foundF = true;
                                        if (dist <= fSz) break;
                                    }
                                }
                            }

                            if (!foundF) {
                                allOk = false;
                                break;
                            }
                            matchedCandIndices[fIdx] = bestCandIdx;
                            uintptr_t bAddr = m_candidates[bestCandIdx].address;
                            minAddr = std::min(minAddr, bAddr);
                            maxAddr = std::max(maxAddr, bAddr);
                        }

                        if (allOk) {
                            for (size_t fIdx = 0; fIdx < numFields; ++fIdx) {
                                CandidateEntry kept = m_candidates[matchedCandIndices[fIdx]];
                                DataType fDt = query.fields[fIdx].type;
                                const uint8_t* ptrK = br.localBuf + (kept.address - grp.readStartAddr);
                                kept.rawValue = extractRawValue(ptrK, fDt);
                                kept.type     = static_cast<uint8_t>(fDt);
                                nextStore.append(kept);
                            }
                        }
                    }
                }
            } else {
                for (size_t startC = grp.firstCandIdx; startC < grp.lastCandIdx; ++startC) {
                    const CandidateEntry& c0 = m_candidates[startC];
                    size_t aOff = c0.address - grp.readStartAddr;
                    if (!evalGroupField(br.localBuf + aOff, query.fields[0])) continue;

                    std::vector<size_t> offMatches(numFields, 0);
                    offMatches[0] = aOff;
                    size_t minStartOff = aOff;
                    size_t maxStartOff = aOff;
                    size_t prevEnd = aOff + std::max<size_t>(1, dataTypeSize(query.fields[0].type));
                    bool allOk = true;

                    for (size_t fIdx = 1; fIdx < numFields; ++fIdx) {
                        const auto& fld = query.fields[fIdx];
                        size_t fSz = std::max<size_t>(1, dataTypeSize(fld.type));
                        size_t fStep = query.aligned ? std::min<size_t>(fSz, 4) : 1;
                        size_t searchStart = query.ordered ? prevEnd : 0;
                        if (fStep > 1 && (searchStart % fStep) != 0) {
                            searchStart += fStep - (searchStart % fStep);
                        }

                        bool foundF = false;
                        for (size_t pos = searchStart; pos + fSz <= grp.byteLen; pos += fStep) {
                            size_t candMin = std::min(minStartOff, pos);
                            size_t candMax = std::max(maxStartOff, pos);
                            if ((candMax - candMin) > query.maxSpanBytes) continue;

                            bool overlaps = false;
                            for (size_t p = 0; p < fIdx; ++p) {
                                size_t pOff = offMatches[p];
                                size_t pSz  = std::max<size_t>(1, dataTypeSize(query.fields[p].type));
                                if (pos < pOff + pSz && pOff < pos + fSz) {
                                    overlaps = true;
                                    break;
                                }
                            }
                            if (overlaps) continue;

                            if (evalGroupField(br.localBuf + pos, fld)) {
                                offMatches[fIdx] = pos;
                                minStartOff = candMin;
                                maxStartOff = candMax;
                                prevEnd = pos + fSz;
                                foundF = true;
                                break;
                            }
                        }
                        if (!foundF) {
                            allOk = false;
                            break;
                        }
                    }

                    if (allOk) {
                        for (size_t fIdx = 0; fIdx < numFields; ++fIdx) {
                            CandidateEntry kept{};
                            DataType fDt = query.fields[fIdx].type;
                            kept.address   = grp.readStartAddr + offMatches[fIdx];
                            kept.rawValue  = extractRawValue(br.localBuf + offMatches[fIdx], fDt);
                            kept.type      = static_cast<uint8_t>(fDt);
                            kept.regionTag = c0.regionTag;
                            nextStore.append(kept);
                        }
                    }
                }
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    nextStore.sortByAddress();
    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

// ============================================================================
// 6. Native Validated Pointer Search & Refinement (VmaIndex + ArchPtrPolicy)
// Inspired by KittyPtrValidator & frida-gum gum_memory_find_pointers
// ============================================================================
EngineErrc SearchEngine::searchPointers(
    DataType ptrType,
    uintptr_t targetAddress,
    size_t maxOffset,
    uint32_t protMask,
    bool aligned,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_candidates.clear();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;
    m_totalMatchedCount = 0;
    m_lastTelemetry = ScanTelemetry{};

    refreshRegions();
    VmaIndex vmaIndex(MapsParser::parseProcessMaps(m_io.pid(), 0, false));
    if (vmaIndex.empty()) return EngineErrc::OK;

    const bool is64Target = m_io.targetIs64Bit();
    DataType effectiveDt = (ptrType == DataType::INT32 || ptrType == DataType::INT64)
        ? ptrType
        : (is64Target ? DataType::INT64 : DataType::INT32);
    const size_t ptrWidth = (effectiveDt == DataType::INT32) ? 4 : 8;
    const size_t step = aligned ? 4 : 1;
    const uint32_t effectiveProtMask = (protMask == 0) ? PointerProtMask::ALL : protMask;

    const uintptr_t cleanTarget = static_cast<uintptr_t>(ArchPtrPolicy::untag(targetAddress, is64Target));
    const uintptr_t minTarget = (cleanTarget > 0)
        ? ((cleanTarget > maxOffset) ? std::max<uintptr_t>(ArchPtrPolicy::MIN_VALID_USER_ADDR, cleanTarget - maxOffset) : ArchPtrPolicy::MIN_VALID_USER_ADDR)
        : ArchPtrPolicy::MIN_VALID_USER_ADDR;
    const uintptr_t maxTarget = (cleanTarget > 0)
        ? cleanTarget
        : (is64Target ? static_cast<uintptr_t>(ArchPtrPolicy::ARM64_TBI_MASK) : static_cast<uintptr_t>(0xFFFF0000u));

    size_t overlap = ptrWidth - 1;
    auto tasks = buildChunkTasks(overlap);
    if (tasks.empty()) return EngineErrc::OK;

    size_t totalPlannedBytes = 0;
    for (const auto& t : tasks) totalPlannedBytes += t.logicalLen;

    std::atomic<size_t> nextIdx{0};
    std::atomic<size_t> processedBytes{0};
    std::atomic<size_t> readableBytes{0};
    std::atomic<size_t> syscalls{0};
    std::atomic<size_t> totalMatches{0};
    std::atomic<size_t> storedCount{0};
    std::mutex storeMutex;

    size_t numThreads = std::min(m_workerThreads, tasks.size());
    const size_t pageSize = m_io.pageSize();

    runParallel(numThreads, [&](size_t) {
            std::vector<uint8_t> buf;
            std::vector<uint8_t> validMask;
            std::vector<uint8_t> nonEmptyMask;
            std::vector<CandidateEntry> localHits;
            localHits.reserve(8192);
            // Thread-local copy of VmaIndex so MRU atomic hint has zero cross-thread contention
            VmaIndex localVma = vmaIndex;

            while (true) {
                if (token && token->isCancelled()) break;
                size_t idx = nextIdx.fetch_add(1, std::memory_order_relaxed);
                if (idx >= tasks.size()) break;

                const ChunkTask& task = tasks[idx];
                if (buf.size() < task.readLen) buf.resize(task.readLen);

                size_t chunkReadable = 0;
                EngineErrc rc = m_io.readChunkFaultTolerant(task.startAddr, buf.data(), task.readLen, validMask, &chunkReadable, !task.fileBacked);
                syscalls.fetch_add(1, std::memory_order_relaxed);
                readableBytes.fetch_add(chunkReadable, std::memory_order_relaxed);

                if (rc == EngineErrc::OK && chunkReadable > 0) {
                    // Valid pointers are never 0, so skipping all-zero pages is 100% exact
                    buildScanPageMask(buf.data(), task.readLen, pageSize, task.regionFlag, false, true, validMask, nonEmptyMask);
                    const size_t numPages = nonEmptyMask.size();
                    uint8_t rTag = regionFlagToIndex(task.regionFlag);
                    bool storeAlreadyFull = (storedCount.load(std::memory_order_relaxed) >= MmapCandidateStore::MAX_STORED_CANDIDATES);
                    size_t chunkMatches = 0;

                    for (size_t p = 0; p < numPages; ++p) {
                        if (nonEmptyMask[p] == 0) continue;
                        size_t pStart = p * pageSize;
                        if (pStart >= task.logicalLen) break;
                        size_t pEnd = std::min((p + 1) * pageSize, task.logicalLen);

                        for (size_t off = pStart; off < pEnd && off + ptrWidth <= task.readLen; off += step) {
                            uint64_t rawVal = 0;
                            std::memcpy(&rawVal, buf.data() + off, ptrWidth);
                            if (rawVal == 0) continue;

                            if (!ArchPtrPolicy::isCanonicalUserPtr(rawVal, ptrWidth == 8)) continue;
                            uintptr_t untagged = static_cast<uintptr_t>(ArchPtrPolicy::untag(rawVal, ptrWidth == 8));
                            if (untagged < minTarget || untagged > maxTarget) continue;

                            uintptr_t candAddr = task.startAddr + off;
                            if (!matchesAddressFilter(candAddr)) continue;
                            if (cleanTarget == 0) {
                                // General pointer discovery: filter unaligned & self-pointing noise
                                if ((untagged & (ptrWidth - 1)) != 0) continue;
                                if (untagged == candAddr) continue;
                            }

                            uint32_t pClass = localVma.classifyPointer(untagged, false);
                            if ((pClass & effectiveProtMask) == 0) continue;
                            if (!isRangeInValidPages(off, ptrWidth, task.startAddr, pageSize, validMask)) continue;

                            chunkMatches++;
                            if (!storeAlreadyFull) {
                                CandidateEntry entry{};
                                entry.address   = candAddr;
                                entry.rawValue  = rawVal;
                                entry.type      = static_cast<uint8_t>(effectiveDt);
                                entry.regionTag = rTag;
                                entry.flags     = pClass;
                                localHits.push_back(entry);

                                if (localHits.size() >= 16384) {
                                    std::lock_guard<std::mutex> lk(storeMutex);
                                    m_candidates.appendBatch(localHits.data(), localHits.size());
                                    storedCount.store(m_candidates.size(), std::memory_order_relaxed);
                                    localHits.clear();
                                    if (m_candidates.isFull()) storeAlreadyFull = true;
                                }
                            }
                        }
                    }

                    if (chunkMatches > 0) {
                        m_totalMatchedCount = totalMatches.fetch_add(chunkMatches, std::memory_order_relaxed) + chunkMatches;
                    }
                }

                size_t done = processedBytes.fetch_add(task.logicalLen, std::memory_order_relaxed) + task.logicalLen;
                if (progress) progress(done, totalPlannedBytes);
            }

            if (!localHits.empty()) {
                std::lock_guard<std::mutex> lk(storeMutex);
                m_candidates.appendBatch(localHits.data(), localHits.size());
                storedCount.store(m_candidates.size(), std::memory_order_relaxed);
            }
    });
    if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;

    m_candidates.sortByAddress();
    m_totalMatchedCount = std::max(totalMatches.load(), m_candidates.size());

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs         = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.totalVmasScanned  = m_regions.size();
    m_lastTelemetry.totalBytesScanned = processedBytes.load();
    m_lastTelemetry.readableBytes     = readableBytes.load();
    m_lastTelemetry.candidatesFound   = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued    = syscalls.load();
    m_lastTelemetry.spilledToMmap     = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

EngineErrc SearchEngine::refinePointers(
    DataType ptrType,
    uintptr_t targetAddress,
    size_t maxOffset,
    uint32_t protMask,
    CancellationToken* token,
    ProgressCallback progress
) {
    auto t0 = std::chrono::steady_clock::now();
    m_snapshotStore.clear();
    m_hasFullSnapshot = false;
    m_encryptedSnapshot = false;

    if (m_candidates.empty()) {
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    VmaIndex vmaIndex(MapsParser::parseProcessMaps(m_io.pid(), 0, false));
    if (vmaIndex.empty()) {
        m_candidates.clear();
        m_totalMatchedCount = 0;
        return EngineErrc::OK;
    }

    const bool is64Target = m_io.targetIs64Bit();
    DataType defaultDt = (ptrType == DataType::INT32 || ptrType == DataType::INT64)
        ? ptrType
        : (is64Target ? DataType::INT64 : DataType::INT32);
    const uint32_t effectiveProtMask = (protMask == 0) ? PointerProtMask::ALL : protMask;

    const uintptr_t cleanTarget = static_cast<uintptr_t>(ArchPtrPolicy::untag(targetAddress, is64Target));
    const uintptr_t minTarget = (cleanTarget > 0)
        ? ((cleanTarget > maxOffset) ? std::max<uintptr_t>(ArchPtrPolicy::MIN_VALID_USER_ADDR, cleanTarget - maxOffset) : ArchPtrPolicy::MIN_VALID_USER_ADDR)
        : ArchPtrPolicy::MIN_VALID_USER_ADDR;
    const uintptr_t maxTarget = (cleanTarget > 0)
        ? cleanTarget
        : (is64Target ? static_cast<uintptr_t>(ArchPtrPolicy::ARM64_TBI_MASK) : static_cast<uintptr_t>(0xFFFF0000u));

    m_candidates.sortByAddress();
    const size_t count = m_candidates.size();
    const size_t pageSize = m_io.pageSize();

    struct CoalescedGroup {
        uintptr_t startPageAddr;
        size_t    byteLen;
        size_t    firstCandIdx;
        size_t    lastCandIdx;
    };

    std::vector<CoalescedGroup> groups;
    size_t idx = 0;
    while (idx < count) {
        uintptr_t addr = m_candidates[idx].address;
        size_t itemSz = 8;
        uintptr_t spanStart = addr;
        uintptr_t spanEnd   = addr + itemSz;
        size_t firstIdx = idx++;

        while (idx < count) {
            uintptr_t nextAddr = m_candidates[idx].address;
            uintptr_t nextEnd  = nextAddr + 8;
            if (nextAddr <= spanEnd + 256 && (nextEnd - spanStart) <= pageSize) {
                if (nextEnd > spanEnd) spanEnd = nextEnd;
                idx++;
            } else {
                break;
            }
        }
        groups.push_back(CoalescedGroup{spanStart, spanEnd - spanStart, firstIdx, idx});
    }

    MmapCandidateStore nextStore(MmapCandidateStore::DEFAULT_MEMORY_THRESHOLD, m_spillDir);
    size_t syscallsIssued = 0;

    constexpr size_t BATCH_GROUP_LIMIT = 4096;
    for (size_t gBase = 0; gBase < groups.size(); gBase += BATCH_GROUP_LIMIT) {
        if (token && token->isCancelled()) return EngineErrc::ERR_CANCELLED;
        size_t gEnd = std::min(groups.size(), gBase + BATCH_GROUP_LIMIT);
        size_t batchBytes = 0;
        for (size_t i = gBase; i < gEnd; ++i) batchBytes += groups[i].byteLen;

        std::vector<uint8_t> slab(batchBytes, 0);
        std::vector<BatchReadRange> ranges(gEnd - gBase);
        size_t offset = 0;
        for (size_t i = gBase; i < gEnd; ++i) {
            ranges[i - gBase].remoteAddr = groups[i].startPageAddr;
            ranges[i - gBase].length     = groups[i].byteLen;
            ranges[i - gBase].localBuf   = slab.data() + offset;
            offset += groups[i].byteLen;
        }

        m_io.readBatchScattered(ranges);
        syscallsIssued += (ranges.size() + m_io.iovMax() - 1) / m_io.iovMax();

        for (size_t i = gBase; i < gEnd; ++i) {
            const auto& br = ranges[i - gBase];
            if (!br.success) continue;
            const auto& grp = groups[i];
            for (size_t cIdx = grp.firstCandIdx; cIdx < grp.lastCandIdx; ++cIdx) {
                CandidateEntry cand = m_candidates[cIdx];
                DataType dt = (cand.type == static_cast<uint8_t>(DataType::INT32) || cand.type == static_cast<uint8_t>(DataType::INT64))
                    ? static_cast<DataType>(cand.type)
                    : defaultDt;
                size_t ptrWidth = (dt == DataType::INT32) ? 4 : 8;
                const uint8_t* valPtr = br.localBuf + (cand.address - grp.startPageAddr);

                uint64_t rawVal = 0;
                std::memcpy(&rawVal, valPtr, ptrWidth);
                if (rawVal == 0) continue;

                if (!ArchPtrPolicy::isCanonicalUserPtr(rawVal, ptrWidth == 8)) continue;
                uintptr_t untagged = static_cast<uintptr_t>(ArchPtrPolicy::untag(rawVal, ptrWidth == 8));
                if (untagged < minTarget || untagged > maxTarget) continue;

                if (cleanTarget == 0) {
                    if ((untagged & (ptrWidth - 1)) != 0) continue;
                    if (untagged == cand.address) continue;
                }

                uint32_t pClass = vmaIndex.classifyPointer(untagged, false);
                if ((pClass & effectiveProtMask) == 0) continue;

                cand.rawValue = rawVal;
                cand.type     = static_cast<uint8_t>(dt);
                cand.flags    = pClass;
                nextStore.append(cand);
            }
        }

        m_totalMatchedCount = nextStore.size();
        if (progress) progress(gEnd, groups.size());
    }

    m_candidates = std::move(nextStore);
    m_totalMatchedCount = m_candidates.size();

    auto t1 = std::chrono::steady_clock::now();
    m_lastTelemetry.elapsedMs       = std::chrono::duration<double, std::milli>(t1 - t0).count();
    m_lastTelemetry.candidatesFound = m_totalMatchedCount;
    m_lastTelemetry.syscallsIssued  = syscallsIssued;
    m_lastTelemetry.spilledToMmap   = m_candidates.isSpilledToDisk();
    return EngineErrc::OK;
}

} // namespace mem_engine
