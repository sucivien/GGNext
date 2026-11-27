#include "mem_engine/mmap_store.hpp"
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <atomic>

namespace mem_engine {

namespace {

std::atomic<uint32_t> g_spillCounter{1};

// Attempts to create an unlinked file in spillDir sized to capBytes; returns fd >= 0 on success, -1 on fallback
int openUnlinkedSpillFile(const std::string& spillDir, const char* prefix, size_t capBytes) noexcept {
    if (capBytes == 0) return -1;
    char path[512];
    uint32_t seq = g_spillCounter.fetch_add(1, std::memory_order_relaxed);
    const char* primaryDir = spillDir.empty() ? "/data/local/tmp" : spillDir.c_str();
    std::snprintf(path, sizeof(path), "%s/.%s_%d_%u.mmap", primaryDir, prefix, ::getpid(), seq);

    int fd = ::open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0 && std::strcmp(primaryDir, "/data/local/tmp") != 0) {
        std::snprintf(path, sizeof(path), "/data/local/tmp/.%s_%d_%u.mmap", prefix, ::getpid(), seq);
        fd = ::open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    }
    if (fd < 0) return -1;
    // Immediately unlink so the kernel automatically frees the inode when fd closes or process exits
    ::unlink(path);

    if (::ftruncate(fd, static_cast<off_t>(capBytes)) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

} // anonymous namespace

// ============================================================================
// MmapCandidateStore Implementation
// ============================================================================
MmapCandidateStore::MmapCandidateStore(size_t inMemoryThreshold, const std::string& spillDir)
    : m_threshold(std::min<size_t>(inMemoryThreshold, MAX_STORED_CANDIDATES)),
      m_spillDir(spillDir) {
    m_ramVec.reserve(std::min<size_t>(m_threshold, 65536));
}

MmapCandidateStore::~MmapCandidateStore() {
    clear();
}

MmapCandidateStore::MmapCandidateStore(MmapCandidateStore&& other) noexcept
    : m_threshold(other.m_threshold),
      m_spillDir(std::move(other.m_spillDir)),
      m_ramVec(std::move(other.m_ramVec)),
      m_count(other.m_count),
      m_isMmap(other.m_isMmap),
      m_fd(other.m_fd),
      m_mmapPtr(other.m_mmapPtr),
      m_mmapCapacityBytes(other.m_mmapCapacityBytes) {
    other.m_count = 0;
    other.m_isMmap = false;
    other.m_fd = -1;
    other.m_mmapPtr = nullptr;
    other.m_mmapCapacityBytes = 0;
}

MmapCandidateStore& MmapCandidateStore::operator=(MmapCandidateStore&& other) noexcept {
    if (this != &other) {
        clear();
        m_threshold = other.m_threshold;
        m_spillDir = std::move(other.m_spillDir);
        m_ramVec = std::move(other.m_ramVec);
        m_count = other.m_count;
        m_isMmap = other.m_isMmap;
        m_fd = other.m_fd;
        m_mmapPtr = other.m_mmapPtr;
        m_mmapCapacityBytes = other.m_mmapCapacityBytes;

        other.m_count = 0;
        other.m_isMmap = false;
        other.m_fd = -1;
        other.m_mmapPtr = nullptr;
        other.m_mmapCapacityBytes = 0;
    }
    return *this;
}

bool MmapCandidateStore::transitionToMmap(size_t requiredCount) {
    size_t capCount = std::min<size_t>(
        MAX_STORED_CANDIDATES,
        std::max(requiredCount * 2, m_threshold * 2)
    );
    if (capCount < requiredCount) capCount = requiredCount;
    size_t capBytes = capCount * sizeof(CandidateEntry);

    int fd = openUnlinkedSpillFile(m_spillDir, "ggnext_cand", capBytes);
    void* ptr = MAP_FAILED;
    if (fd >= 0) {
        ptr = ::mmap(nullptr, capBytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) {
            ::close(fd);
            fd = -1;
        }
    }
    if (ptr == MAP_FAILED) {
        ptr = ::mmap(nullptr, capBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (ptr == MAP_FAILED) {
            return false;
        }
    }

    ::madvise(ptr, capBytes, MADV_SEQUENTIAL);
    m_mmapPtr = static_cast<CandidateEntry*>(ptr);
    m_mmapCapacityBytes = capBytes;
    m_isMmap = true;
    m_fd = fd;

    if (!m_ramVec.empty()) {
        std::memcpy(m_mmapPtr, m_ramVec.data(), m_ramVec.size() * sizeof(CandidateEntry));
        std::vector<CandidateEntry>().swap(m_ramVec);
    }

    return true;
}

bool MmapCandidateStore::growMmap(size_t requiredCount) {
    size_t neededBytes = requiredCount * sizeof(CandidateEntry);
    if (neededBytes <= m_mmapCapacityBytes) return true;

    size_t maxBytes = MAX_STORED_CANDIDATES * sizeof(CandidateEntry);
    size_t newCapBytes = std::min<size_t>(
        maxBytes,
        std::max(m_mmapCapacityBytes * 2, neededBytes + (8 * 1024 * 1024))
    );
    if (newCapBytes < neededBytes) newCapBytes = neededBytes;

    if (m_fd >= 0) {
        if (::ftruncate(m_fd, static_cast<off_t>(newCapBytes)) != 0) {
            return false;
        }
    }

#if defined(__linux__) && defined(MREMAP_MAYMOVE)
    void* newPtr = ::mremap(m_mmapPtr, m_mmapCapacityBytes, newCapBytes, MREMAP_MAYMOVE);
    if (newPtr == MAP_FAILED) return false;
#else
    int flags = (m_fd >= 0) ? MAP_SHARED : (MAP_PRIVATE | MAP_ANONYMOUS);
    void* newPtr = ::mmap(nullptr, newCapBytes, PROT_READ | PROT_WRITE, flags, m_fd, 0);
    if (newPtr == MAP_FAILED) return false;
    if (m_fd < 0 && m_count > 0) {
        std::memcpy(newPtr, m_mmapPtr, m_count * sizeof(CandidateEntry));
    }
    ::munmap(m_mmapPtr, m_mmapCapacityBytes);
#endif

    m_mmapPtr = static_cast<CandidateEntry*>(newPtr);
    m_mmapCapacityBytes = newCapBytes;
    return true;
}

bool MmapCandidateStore::append(const CandidateEntry& entry) {
    return appendBatch(&entry, 1);
}

bool MmapCandidateStore::appendBatch(const CandidateEntry* entries, size_t count) {
    if (count == 0 || !entries) return true;
    if (m_count >= MAX_STORED_CANDIDATES) return true;

    size_t allowed = std::min(count, MAX_STORED_CANDIDATES - m_count);
    size_t newCount = m_count + allowed;

    if (!m_isMmap) {
        if (newCount <= m_threshold) {
            m_ramVec.insert(m_ramVec.end(), entries, entries + allowed);
            m_count = newCount;
            return true;
        }
        if (!transitionToMmap(newCount)) {
            return false;
        }
    }

    if (!growMmap(newCount)) {
        return false;
    }

    std::memcpy(m_mmapPtr + m_count, entries, allowed * sizeof(CandidateEntry));
    m_count = newCount;
    return true;
}

size_t MmapCandidateStore::removePrefix(size_t count) noexcept {
    if (m_count == 0 || count == 0) return 0;
    size_t n = std::min(count, m_count);
    size_t remain = m_count - n;
    CandidateEntry* base = data();
    if (remain > 0 && base != nullptr) {
        std::memmove(base, base + n, remain * sizeof(CandidateEntry));
    }
    m_count = remain;
    if (!m_isMmap && m_ramVec.size() > remain) {
        m_ramVec.resize(remain);
    }
    return n;
}

size_t MmapCandidateStore::removeAddresses(const uint64_t* addrs, size_t count) {
    if (m_count == 0 || count == 0 || !addrs) return 0;

    std::vector<uint64_t> sortedRemove(addrs, addrs + count);
    std::sort(sortedRemove.begin(), sortedRemove.end());
    sortedRemove.erase(std::unique(sortedRemove.begin(), sortedRemove.end()), sortedRemove.end());

    CandidateEntry* base = data();
    // Fast check if candidates are non-decreasing by address -> O(N + K) two-pointer merge!
    bool isSorted = true;
    for (size_t i = 1; i < m_count; ++i) {
        if (base[i].address < base[i - 1].address) {
            isSorted = false;
            break;
        }
    }

    size_t writeIdx = 0;
    if (isSorted) {
        size_t remIdx = 0;
        const size_t remCount = sortedRemove.size();
        for (size_t readIdx = 0; readIdx < m_count; ++readIdx) {
            uint64_t addr = base[readIdx].address;
            while (remIdx < remCount && sortedRemove[remIdx] < addr) {
                ++remIdx;
            }
            if (remIdx < remCount && sortedRemove[remIdx] == addr) {
                continue;
            }
            if (writeIdx != readIdx) {
                base[writeIdx] = base[readIdx];
            }
            ++writeIdx;
        }
    } else {
        for (size_t readIdx = 0; readIdx < m_count; ++readIdx) {
            uint64_t addr = base[readIdx].address;
            if (!std::binary_search(sortedRemove.begin(), sortedRemove.end(), addr)) {
                if (writeIdx != readIdx) {
                    base[writeIdx] = base[readIdx];
                }
                ++writeIdx;
            }
        }
    }

    size_t removed = m_count - writeIdx;
    m_count = writeIdx;
    if (!m_isMmap && m_ramVec.size() > writeIdx) {
        m_ramVec.resize(writeIdx);
    }
    return removed;
}

const CandidateEntry& MmapCandidateStore::operator[](size_t idx) const noexcept {
    return m_isMmap ? m_mmapPtr[idx] : m_ramVec[idx];
}

CandidateEntry& MmapCandidateStore::operator[](size_t idx) noexcept {
    return m_isMmap ? m_mmapPtr[idx] : m_ramVec[idx];
}

const CandidateEntry* MmapCandidateStore::data() const noexcept {
    return m_isMmap ? m_mmapPtr : m_ramVec.data();
}

CandidateEntry* MmapCandidateStore::data() noexcept {
    return m_isMmap ? m_mmapPtr : m_ramVec.data();
}

void MmapCandidateStore::sortByAddress() {
    if (m_count <= 1) return;
    CandidateEntry* base = data();
    auto cmp = [](const CandidateEntry& a, const CandidateEntry& b) noexcept {
        if (a.address != b.address) return a.address < b.address;
        return a.type < b.type;
    };
    if (!std::is_sorted(base, base + m_count, cmp)) {
        std::sort(base, base + m_count, cmp);
    }
    auto eq = [](const CandidateEntry& a, const CandidateEntry& b) noexcept {
        return a.address == b.address && a.type == b.type;
    };
    CandidateEntry* newEnd = std::unique(base, base + m_count, eq);
    size_t uniqueCount = static_cast<size_t>(newEnd - base);
    if (uniqueCount < m_count) {
        m_count = uniqueCount;
        if (!m_isMmap && m_ramVec.size() > uniqueCount) {
            m_ramVec.resize(uniqueCount);
        }
    }
}

void MmapCandidateStore::clear() noexcept {
    if (m_mmapPtr) {
        ::munmap(m_mmapPtr, m_mmapCapacityBytes);
        m_mmapPtr = nullptr;
    }
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_mmapCapacityBytes = 0;
    m_isMmap = false;
    m_count = 0;
    std::vector<CandidateEntry>().swap(m_ramVec);
}

// ============================================================================
// MmapRegionSnapshotStore Implementation
// ============================================================================
MmapRegionSnapshotStore::MmapRegionSnapshotStore(const std::string& spillDir)
    : m_spillDir(spillDir) {}

MmapRegionSnapshotStore::~MmapRegionSnapshotStore() {
    clear();
}

MmapRegionSnapshotStore::MmapRegionSnapshotStore(MmapRegionSnapshotStore&& other) noexcept
    : m_spillDir(std::move(other.m_spillDir)),
      m_fd(other.m_fd),
      m_mmapPtr(other.m_mmapPtr),
      m_capacity(other.m_capacity),
      m_writeOffset(other.m_writeOffset),
      m_blocks(std::move(other.m_blocks)) {
    other.m_fd = -1;
    other.m_mmapPtr = nullptr;
    other.m_capacity = 0;
    other.m_writeOffset = 0;
}

MmapRegionSnapshotStore& MmapRegionSnapshotStore::operator=(MmapRegionSnapshotStore&& other) noexcept {
    if (this != &other) {
        clear();
        m_spillDir = std::move(other.m_spillDir);
        m_fd = other.m_fd;
        m_mmapPtr = other.m_mmapPtr;
        m_capacity = other.m_capacity;
        m_writeOffset = other.m_writeOffset;
        m_blocks = std::move(other.m_blocks);

        other.m_fd = -1;
        other.m_mmapPtr = nullptr;
        other.m_capacity = 0;
        other.m_writeOffset = 0;
    }
    return *this;
}

bool MmapRegionSnapshotStore::openStore(size_t reserveBytes) {
    clear();
    size_t cap = std::min<size_t>(
        MAX_SNAPSHOT_BYTES,
        std::max<size_t>(reserveBytes, 16 * 1024 * 1024)
    );

    int fd = openUnlinkedSpillFile(m_spillDir, "ggnext_snap", cap);
    void* ptr = MAP_FAILED;
    if (fd >= 0) {
        ptr = ::mmap(nullptr, cap, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) {
            ::close(fd);
            fd = -1;
        }
    }
    if (ptr == MAP_FAILED) {
        ptr = ::mmap(nullptr, cap, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (ptr == MAP_FAILED) {
            cap = 64 * 1024 * 1024;
            ptr = ::mmap(nullptr, cap, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (ptr == MAP_FAILED) {
                return false;
            }
        }
    }

    ::madvise(ptr, cap, MADV_SEQUENTIAL);
    m_mmapPtr = static_cast<uint8_t*>(ptr);
    m_capacity = cap;
    m_writeOffset = 0;
    m_fd = fd;
    return true;
}

bool MmapRegionSnapshotStore::ensureCapacity(size_t totalBytesNeeded) {
    if (!m_mmapPtr) {
        return openStore(totalBytesNeeded);
    }
    if (totalBytesNeeded <= m_capacity) return true;
    if (totalBytesNeeded > MAX_SNAPSHOT_BYTES) return false;

    size_t newCap = std::min<size_t>(
        MAX_SNAPSHOT_BYTES,
        std::max(m_capacity * 2, totalBytesNeeded + (16 * 1024 * 1024))
    );

    if (m_fd >= 0) {
        if (::ftruncate(m_fd, static_cast<off_t>(newCap)) != 0) {
            return false;
        }
    }

#if defined(__linux__) && defined(MREMAP_MAYMOVE)
    void* newPtr = ::mremap(m_mmapPtr, m_capacity, newCap, MREMAP_MAYMOVE);
    if (newPtr == MAP_FAILED) return false;
#else
    int flags = (m_fd >= 0) ? MAP_SHARED : (MAP_PRIVATE | MAP_ANONYMOUS);
    void* newPtr = ::mmap(nullptr, newCap, PROT_READ | PROT_WRITE, flags, m_fd, 0);
    if (newPtr == MAP_FAILED) return false;
    if (m_fd < 0 && m_writeOffset > 0) {
        std::memcpy(newPtr, m_mmapPtr, m_writeOffset);
    }
    ::munmap(m_mmapPtr, m_capacity);
#endif

    m_mmapPtr = static_cast<uint8_t*>(newPtr);
    m_capacity = newCap;
    return true;
}

bool MmapRegionSnapshotStore::appendRegionChunk(uintptr_t startAddr, const uint8_t* data, size_t len, uint32_t regionFlag) {
    if (len == 0 || !data) return true;
    if (m_writeOffset >= MAX_SNAPSHOT_BYTES) {
        return true;
    }

    size_t actualLen = std::min(len, MAX_SNAPSHOT_BYTES - m_writeOffset);
    if (!ensureCapacity(m_writeOffset + actualLen)) {
        return true;
    }

    std::memcpy(m_mmapPtr + m_writeOffset, data, actualLen);

    SnapshotBlock blk;
    blk.startAddress = startAddr;
    blk.length       = actualLen;
    blk.fileOffset   = m_writeOffset;
    blk.regionFlag   = regionFlag;
    m_blocks.push_back(blk);

    m_writeOffset += actualLen;
    return true;
}

const uint8_t* MmapRegionSnapshotStore::getBlockData(const SnapshotBlock& block) const noexcept {
    if (!m_mmapPtr || block.fileOffset + block.length > m_writeOffset) return nullptr;
    return m_mmapPtr + block.fileOffset;
}

uint8_t* MmapRegionSnapshotStore::getBlockDataMut(const SnapshotBlock& block) noexcept {
    if (!m_mmapPtr || block.fileOffset + block.length > m_writeOffset) return nullptr;
    return m_mmapPtr + block.fileOffset;
}

void MmapRegionSnapshotStore::clear() noexcept {
    if (m_mmapPtr) {
        ::munmap(m_mmapPtr, m_capacity);
        m_mmapPtr = nullptr;
    }
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_capacity = 0;
    m_writeOffset = 0;
    m_blocks.clear();
}

} // namespace mem_engine
