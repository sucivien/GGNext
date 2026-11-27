#pragma once
#ifndef MEM_ENGINE_SIMD_KERNEL_HPP
#define MEM_ENGINE_SIMD_KERNEL_HPP

#include "types.hpp"
#include <cstdint>
#include <cstddef>
#include <vector>

namespace mem_engine {

// ============================================================================
// SimdKernel: Dual-Path Vectorized Memory Scanning Kernel
// - Path A (ARM64 __aarch64__ / ARMv7 __ARM_NEON): 128-bit <arm_neon.h> SIMD
//   using strictly safe unaligned loads (vld1q_u8 + vreinterpretq_*_u8) and
//   horizontal max reduction (vmaxvq_u8 / vmaxvq_u32) to skip non-matching
//   16-byte / 64-byte cache lines in a single cycle.
// - Path B (x86_64 / Scalar Fallback): Strict-aliasing-safe memcpy-based loops
//   optimized for Clang/GCC auto-vectorization and trailing bytes (< 16B).
// ============================================================================
class SimdKernel {
public:
    // 1. Exact Match Kernels
    static void scanExactI8(const uint8_t* buf, size_t len, int8_t target, std::vector<size_t>& outOffsets);
    static void scanExactI16(const uint8_t* buf, size_t len, int16_t target, size_t step, std::vector<size_t>& outOffsets);
    static void scanExactI32(const uint8_t* buf, size_t len, int32_t target, size_t step, std::vector<size_t>& outOffsets);
    static void scanExactI64(const uint8_t* buf, size_t len, int64_t target, size_t step, std::vector<size_t>& outOffsets);
    static void scanExactF32(const uint8_t* buf, size_t len, float target, float epsilon, size_t step, std::vector<size_t>& outOffsets);
    static void scanExactF64(const uint8_t* buf, size_t len, double target, double epsilon, size_t step, std::vector<size_t>& outOffsets);

    // 2. Range Match Kernels (Inclusive [minVal, maxVal] or Exclusive (minVal, maxVal))
    template <typename T>
    static void scanRange(const uint8_t* buf, size_t len, T minVal, T maxVal, bool inclusive, size_t step, std::vector<size_t>& outOffsets);

    // Generalized Signed + Unsigned Integer Range Kernel (handles e.g. BYTE 0..255, WORD 0..65535, DWORD 0..4294967295)
    static void scanRangeInteger(
        const uint8_t* buf,
        size_t len,
        DataType dt,
        int64_t iMin,
        int64_t iMax,
        bool inclusive,
        size_t step,
        std::vector<size_t>& outOffsets
    );

    // 3. Vectorized Float32 Range Kernel (NEON vcgeq_f32 / vcleq_f32 + Scalar)
    static void scanRangeF32(const uint8_t* buf, size_t len, float minVal, float maxVal, bool inclusive, size_t step, std::vector<size_t>& outOffsets);

    // 4. Byte Array / Masked Pattern Search (High-entropy anchor selection + memchr)
    static void scanBytePattern(const uint8_t* buf, size_t len, const uint8_t* pattern, const uint8_t* mask, size_t patLen, std::vector<size_t>& outOffsets);

    // 5. Encoded & XOR Kernels
    static void scanXorPair32(const uint8_t* buf, size_t len, uint32_t targetBits, size_t step, std::vector<size_t>& outOffsets);
    static void scanXorPair64(const uint8_t* buf, size_t len, uint64_t targetBits, size_t step, std::vector<size_t>& outOffsets);
    static void scanAddressXor32(uintptr_t baseAddr, const uint8_t* buf, size_t len, uint32_t logicalTarget, uint32_t addrMask, size_t step, std::vector<size_t>& outOffsets);

    // 6. Snapshot Delta / Fuzzy Comparison Kernel (compares current buffer vs previous snapshot buffer)
    template <typename T>
    static void compareBuffersDelta(
        uintptr_t baseAddr,
        const uint8_t* curBuf,
        const uint8_t* oldBuf,
        size_t len,
        SearchOp op,
        T exactDelta,
        uint8_t regionTag,
        std::vector<CandidateEntry>& outCandidates,
        size_t step = 0
    );
};

} // namespace mem_engine

#endif // MEM_ENGINE_SIMD_KERNEL_HPP
