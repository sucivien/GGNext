#pragma once
#ifndef MEM_ENGINE_TYPES_HPP
#define MEM_ENGINE_TYPES_HPP

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>
#include <atomic>
#include <functional>
#include <limits>
#include <type_traits>

namespace mem_engine {

// ============================================================================
// 1. Granular Virtual Memory Area (VMA) Region Classification Bitmasks
// ============================================================================
namespace RegionFlag {
    constexpr uint32_t NONE        = 0;
    constexpr uint32_t JH          = 1u << 0;  // Java Heap (ART/Dalvik main/alloc/region/large/zygote spaces)
    constexpr uint32_t CH          = 1u << 1;  // C/C++ Native Heap ([heap])
    constexpr uint32_t CA          = 1u << 2;  // C++ Allocator ([anon:libc_malloc], scudo, jemalloc, GWP-ASan)
    constexpr uint32_t CD          = 1u << 3;  // C++ .data segment (rw-p private file-backed .so / binary data)
    constexpr uint32_t CB          = 1u << 4;  // C++ .bss segment ([anon:.bss] or anonymous rw-p immediately following .so)
    constexpr uint32_t A           = 1u << 5;  // Anonymous (unnamed rw-p or generic [anon:*])
    constexpr uint32_t S           = 1u << 6;  // Stack ([stack], [stack:tid])
    constexpr uint32_t AS          = 1u << 7;  // Ashmem (/dev/ashmem*, [anon:ashmem], /memfd:*)
    constexpr uint32_t XA          = 1u << 8;  // Code App (r-xp / rwxp in /data/app, JIT cache, anonymous executable)
    constexpr uint32_t XS          = 1u << 9;  // Code System (r-xp in /system, /apex, /vendor, /product, [vdso])
    constexpr uint32_t V           = 1u << 10; // Video / GPU (/dev/mali*, /dev/kgsl-3d0, /dev/ion, /dev/dma_heap, /dev/dri)
    constexpr uint32_t PS          = 1u << 11; // PPSSPP Emulator RAM (ppsspp, /dev/shm/ppsspp*, PSP scratchpad/VRAM)
    constexpr uint32_t B           = 1u << 12; // Bad / Unreadable / Guard (PROT_NONE ---p, fonts .ttf/.otf, /dev/* hardware nodes)
    constexpr uint32_t O           = 1u << 13; // Other / Unclassified readable file mappings
    constexpr uint32_t J           = 1u << 14; // Java Runtime (dalvik-LinearAlloc, dalvik-classes, .art/.vdex/.odex non-heap Java VM structures)

    // Default safe scan mask: All writable data/heap/anonymous segments (excludes B, V, XS)
    constexpr uint32_t DEFAULT_RW  = JH | CH | CA | CD | CB | A | S | AS | PS | J;
    constexpr uint32_t ALL_SAFE    = DEFAULT_RW | XA | XS | O;
}

// ============================================================================
// Pointer Target Protection Classification Bitmasks (matches UI filter flags)
// ============================================================================
namespace PointerProtMask {
    constexpr uint32_t NONE = 0;
    constexpr uint32_t WX   = 1u << 0; // 1: Writable + Executable
    constexpr uint32_t X    = 1u << 1; // 2: Executable only (r-x)
    constexpr uint32_t RO   = 1u << 3; // 8: Read-Only (r--)
    constexpr uint32_t W    = 1u << 4; // 16: Read-Write (rw-)
    constexpr uint32_t ALL  = WX | X | RO | W; // 27
}

// ============================================================================
// Architecture-Aware Pointer Untagging Policy (ARM64 TBI/MTE & 32-bit compat)
// Inspired by KittyMemoryEx (KittyUtils::untagPointer) & frida-gum
// ============================================================================
struct ArchPtrPolicy {
    static constexpr uint64_t ARM64_TBI_MASK = (1ULL << 56) - 1ULL; // 0x00FFFFFFFFFFFFFFULL
    static constexpr uint64_t ARM32_MASK     = 0x00000000FFFFFFFFULL;
    static constexpr uintptr_t MIN_VALID_USER_ADDR = 0x10000ULL;

    static constexpr uint64_t untag(uint64_t rawPtr, bool is64BitTarget = true) noexcept {
        return is64BitTarget ? (rawPtr & ARM64_TBI_MASK) : (rawPtr & ARM32_MASK);
    }

    static constexpr bool isCanonicalUserPtr(uint64_t rawPtr, bool is64BitTarget = true) noexcept {
        if (!is64BitTarget) {
            uint64_t u32 = rawPtr & ARM32_MASK;
            return u32 >= MIN_VALID_USER_ADDR && u32 < 0xFFFF0000ULL;
        }
        uint8_t topByte = static_cast<uint8_t>(rawPtr >> 56);
        // On Android ARM64, valid user pointers are untagged (0x00) or Scudo/TBI tagged (0xB0..0xBF, e.g. 0xB4).
        // Reject arbitrary non-pointer high bytes (floats, packed ints, kernel 0xFF) to eliminate false positives.
        const bool validTag = (topByte == 0x00) || (topByte >= 0xB0 && topByte <= 0xBF);
        if (!validTag) return false;
        uint64_t untagged = rawPtr & ARM64_TBI_MASK;
        return untagged >= MIN_VALID_USER_ADDR && untagged <= 0x0000FFFFFFFFFFFFULL;
    }
};

inline const char* regionFlagToTag(uint32_t flag) noexcept {
    switch (flag) {
        case RegionFlag::JH: return "Jh";
        case RegionFlag::CH: return "Ch";
        case RegionFlag::CA: return "Ca";
        case RegionFlag::CD: return "Cd";
        case RegionFlag::CB: return "Cb";
        case RegionFlag::A:  return "A";
        case RegionFlag::S:  return "S";
        case RegionFlag::AS: return "As";
        case RegionFlag::XA: return "Xa";
        case RegionFlag::XS: return "Xs";
        case RegionFlag::V:  return "V";
        case RegionFlag::PS: return "Ps";
        case RegionFlag::B:  return "B";
        case RegionFlag::O:  return "O";
        case RegionFlag::J:  return "J";
        default:             return "O";
    }
}

// ============================================================================
// 2. Supported Data Types
// ============================================================================
enum class DataType : uint8_t {
    INT8       = 0,  // Byte (1B)
    INT16      = 1,  // Word (2B)
    INT32      = 2,  // Dword (4B)
    INT64      = 3,  // Qword (8B)
    FLOAT      = 4,  // Float32 (4B)
    DOUBLE     = 5,  // Float64 (8B)
    BYTE_ARRAY = 6,  // Arbitrary Byte[] pattern
    UTF8       = 7,  // UTF-8 String
    UTF16      = 8,  // UTF-16LE String
    AUTO       = 9   // Adaptive multi-type (Int32 + Int64 + Float + Double + Int16 + Int8)
};

inline size_t dataTypeSize(DataType dt) noexcept {
    switch (dt) {
        case DataType::INT8:   return 1;
        case DataType::INT16:  return 2;
        case DataType::INT32:  return 4;
        case DataType::INT64:  return 8;
        case DataType::FLOAT:  return 4;
        case DataType::DOUBLE: return 8;
        default:               return 1;
    }
}

// ============================================================================
// 3. Search & Refinement Operators
// ============================================================================
enum class SearchOp : uint8_t {
    EXACT        = 0,  // == target (with epsilon for float/double)
    NOT_EQUAL    = 1,  // != target
    GREATER      = 2,  // > target
    GREATER_EQ   = 3,  // >= target
    LESS         = 4,  // < target
    LESS_EQ      = 5,  // <= target
    RANGE_INC    = 6,  // min <= x <= max (inclusive)
    RANGE_EXC    = 7,  // min <  x <  max (exclusive)
    UNKNOWN_INIT = 8,  // Full snapshot of initial state
    INCREASED    = 9,  // current > old
    DECREASED    = 10, // current < old
    CHANGED      = 11, // current != old
    UNCHANGED    = 12, // current == old
    DELTA_EXACT  = 13  // N = O + D or N = O - D (exact delta)
};

// ============================================================================
// 4. Encoded & XOR Value Modes
// ============================================================================
enum class EncodedMode : uint8_t {
    OFFSET_ADDED = 0, // stored = logical_val + offset_key
    XOR_STATIC   = 1, // stored = logical_val ^ static_key
    XOR_PAIR_32  = 2, // ACTk ObscuredInt / ObscuredFloat: [uint32_t key][uint32_t hidden] where key ^ hidden == logical_bits
    XOR_PAIR_64  = 3, // ACTk ObscuredLong / ObscuredDouble: [uint64_t key][uint64_t hidden]
    XOR_ADDRESS  = 4, // stored = logical_val ^ (uint32_t(address) & mask)
    GG_AUTO      = 5, // Stateful GG Encrypted Search (Constant XOR, Dynamic Neighbor XOR ±4/±8, Additive Offset)
    XOR_OFFSET   = 6  // GG ValueXOffset syntax (e.g. 25X4, 25X8, 25X-4): (val_at(addr) ^ val_at(addr + offset)) == logical_bits
};

// ============================================================================
// 5. Value Freeze / Lock Modes (Unified C++ & Kotlin Contract)
// ============================================================================
enum class FreezeMode : uint8_t {
    EXACT       = 0, // Continuously write exact locked value
    INCREMENT   = 1, // Increment stored value on each freeze cycle
    DECREMENT   = 2, // Decrement stored value on each freeze cycle
    RANGE_CLAMP = 3  // Clamp live memory value into [minVal, maxVal]
};

// ============================================================================
// 6. System Status & Error Codes
// ============================================================================
enum class EngineErrc : int32_t {
    OK                = 0,
    ERR_PERM          = -1, // EPERM / EACCES: Target process access denied by Linux DAC/SELinux
    ERR_SRCH          = -2, // ESRCH: Target process does not exist or terminated mid-operation
    ERR_FAULT         = -3, // EFAULT: Unmapped or PROT_NONE guard page encountered
    ERR_INVAL         = -4, // EINVAL: Invalid arguments or overflowed range
    ERR_NOMEM         = -5, // ENOMEM / Disk spillover failure
    ERR_CANCELLED     = -6  // Operation cancelled via atomic token
};

// ============================================================================
// 7. Thread-Safe Cancellation Token & Progress Callback
// ============================================================================
class CancellationToken {
public:
    CancellationToken() noexcept : m_cancelled(false) {}
    void cancel() noexcept { m_cancelled.store(true, std::memory_order_release); }
    void reset() noexcept { m_cancelled.store(false, std::memory_order_release); }
    bool isCancelled() const noexcept { return m_cancelled.load(std::memory_order_acquire); }
private:
    std::atomic<bool> m_cancelled;
};

using ProgressCallback = std::function<void(size_t bytesProcessed, size_t totalBytes)>;

// ============================================================================
// 8. Compact Candidate Record (24-byte packed for mmap storage)
// ============================================================================
#pragma pack(push, 1)
struct CandidateEntry {
    uint64_t address;
    uint64_t rawValue; // Little-endian bit representation of value at snapshot/match time
    uint8_t  type;     // DataType cast to uint8_t
    uint8_t  regionTag;// RegionFlag bit index
    uint16_t reserved;
    uint32_t flags;    // Mode-specific metadata (e.g. EncodedMode invariants or PointerProtMask)
};
#pragma pack(pop)

static_assert(sizeof(CandidateEntry) == 24, "CandidateEntry must be 24 bytes");

} // namespace mem_engine

#endif // MEM_ENGINE_TYPES_HPP
