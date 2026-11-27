#include "mem_engine/simd_kernel.hpp"
#include <cstring>
#include <cmath>
#include <algorithm>
#include <limits>
#include <type_traits>

#if defined(__aarch64__) || defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define MEMENGINE_HAS_NEON 1
#else
#define MEMENGINE_HAS_NEON 0
#endif

namespace mem_engine {

namespace {

#if MEMENGINE_HAS_NEON && !defined(__aarch64__)
inline uint8_t neonMaxvU8(uint8x16_t v) noexcept {
    uint8x8_t m1 = vmax_u8(vget_low_u8(v), vget_high_u8(v));
    uint8x8_t m2 = vpmax_u8(m1, m1);
    uint8x8_t m3 = vpmax_u8(m2, m2);
    uint8x8_t m4 = vpmax_u8(m3, m3);
    return vget_lane_u8(m4, 0);
}
inline uint32_t neonMaxvU32(uint32x4_t v) noexcept {
    uint32x2_t m1 = vmax_u32(vget_low_u32(v), vget_high_u32(v));
    uint32x2_t m2 = vpmax_u32(m1, m1);
    return vget_lane_u32(m2, 0);
}
#elif MEMENGINE_HAS_NEON && defined(__aarch64__)
inline uint8_t neonMaxvU8(uint8x16_t v) noexcept { return vmaxvq_u8(v); }
inline uint32_t neonMaxvU32(uint32x4_t v) noexcept { return vmaxvq_u32(v); }
#endif

} // anonymous namespace

// ============================================================================
// 1. Exact Match Kernels
// ============================================================================
void SimdKernel::scanExactI8(const uint8_t* buf, size_t len, int8_t target, std::vector<size_t>& outOffsets) {
    size_t i = 0;
    const uint8_t uTarget = static_cast<uint8_t>(target);

#if MEMENGINE_HAS_NEON
    uint8x16_t vTarget = vdupq_n_u8(uTarget);
    for (; i + 16 <= len; i += 16) {
        uint8x16_t vChunk = vld1q_u8(buf + i);
        uint8x16_t vCmp   = vceqq_u8(vChunk, vTarget);
        if (neonMaxvU8(vCmp) != 0) {
            uint8_t lanes[16];
            vst1q_u8(lanes, vCmp);
            for (size_t k = 0; k < 16; ++k) {
                if (lanes[k] != 0) outOffsets.push_back(i + k);
            }
        }
    }
#endif

    while (i < len) {
        const void* found = std::memchr(buf + i, uTarget, len - i);
        if (!found) break;
        size_t hit = static_cast<const uint8_t*>(found) - buf;
        outOffsets.push_back(hit);
        i = hit + 1;
    }
}

void SimdKernel::scanExactI16(const uint8_t* buf, size_t len, int16_t target, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 2) return;
    if (step == 0) step = 2;
    size_t i = 0;

#if MEMENGINE_HAS_NEON
    if (step == 2) {
        uint16x8_t vTarget = vdupq_n_u16(static_cast<uint16_t>(target));
        for (; i + 16 <= len; i += 16) {
            uint8x16_t raw = vld1q_u8(buf + i);
            uint16x8_t vChunk = vreinterpretq_u16_u8(raw);
            uint16x8_t vCmp = vceqq_u16(vChunk, vTarget);
            if (neonMaxvU32(vreinterpretq_u32_u16(vCmp)) != 0) {
                uint16_t lanes[8];
                vst1q_u16(lanes, vCmp);
                for (size_t k = 0; k < 8; ++k) {
                    if (lanes[k] != 0) outOffsets.push_back(i + k * 2);
                }
            }
        }
    }
#endif

    for (; i + 2 <= len; i += step) {
        int16_t val;
        std::memcpy(&val, buf + i, sizeof(int16_t));
        if (val == target) outOffsets.push_back(i);
    }
}

void SimdKernel::scanExactI32(const uint8_t* buf, size_t len, int32_t target, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 4) return;
    if (step == 0) step = 4;
    size_t i = 0;

#if MEMENGINE_HAS_NEON
    if (step == 4) {
        uint32x4_t vTarget = vdupq_n_u32(static_cast<uint32_t>(target));
        for (; i + 32 <= len; i += 32) {
            uint32x4_t c0 = vreinterpretq_u32_u8(vld1q_u8(buf + i));
            uint32x4_t c1 = vreinterpretq_u32_u8(vld1q_u8(buf + i + 16));
            uint32x4_t m0 = vceqq_u32(c0, vTarget);
            uint32x4_t m1 = vceqq_u32(c1, vTarget);
            uint32x4_t mAny = vorrq_u32(m0, m1);
            if (neonMaxvU32(mAny) != 0) {
                uint32_t lanes0[4], lanes1[4];
                vst1q_u32(lanes0, m0);
                vst1q_u32(lanes1, m1);
                for (size_t k = 0; k < 4; ++k) {
                    if (lanes0[k] != 0) outOffsets.push_back(i + k * 4);
                }
                for (size_t k = 0; k < 4; ++k) {
                    if (lanes1[k] != 0) outOffsets.push_back(i + 16 + k * 4);
                }
            }
        }
    }
#endif

    for (; i + 4 <= len; i += step) {
        int32_t val;
        std::memcpy(&val, buf + i, sizeof(int32_t));
        if (val == target) outOffsets.push_back(i);
    }
}

void SimdKernel::scanExactI64(const uint8_t* buf, size_t len, int64_t target, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 8) return;
    if (step == 0) step = 8;
    size_t i = 0;

#if MEMENGINE_HAS_NEON && defined(__aarch64__)
    if (step == 8) {
        uint64x2_t vTarget = vdupq_n_u64(static_cast<uint64_t>(target));
        for (; i + 16 <= len; i += 16) {
            uint64x2_t vChunk = vreinterpretq_u64_u8(vld1q_u8(buf + i));
            uint64x2_t vCmp = vceqq_u64(vChunk, vTarget);
            if (vmaxvq_u32(vreinterpretq_u32_u64(vCmp)) != 0) {
                uint64_t lanes[2];
                vst1q_u64(lanes, vCmp);
                if (lanes[0] != 0) outOffsets.push_back(i);
                if (lanes[1] != 0) outOffsets.push_back(i + 8);
            }
        }
    }
#endif

    for (; i + 8 <= len; i += step) {
        int64_t val;
        std::memcpy(&val, buf + i, sizeof(int64_t));
        if (val == target) outOffsets.push_back(i);
    }
}

void SimdKernel::scanExactF32(const uint8_t* buf, size_t len, float target, float epsilon, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 4) return;
    if (step == 0) step = 4;
    size_t i = 0;

#if MEMENGINE_HAS_NEON
    if (step == 4 && epsilon > 0.0f) {
        float32x4_t vTarget = vdupq_n_f32(target);
        float32x4_t vEps    = vdupq_n_f32(epsilon);
        for (; i + 16 <= len; i += 16) {
            float32x4_t vChunk = vreinterpretq_f32_u8(vld1q_u8(buf + i));
            float32x4_t vDiff  = vabdq_f32(vChunk, vTarget);
            uint32x4_t  vCmp   = vcleq_f32(vDiff, vEps);
            if (neonMaxvU32(vCmp) != 0) {
                uint32_t lanes[4];
                vst1q_u32(lanes, vCmp);
                for (size_t k = 0; k < 4; ++k) {
                    if (lanes[k] != 0) outOffsets.push_back(i + k * 4);
                }
            }
        }
    }
#endif

    uint32_t targetBits;
    std::memcpy(&targetBits, &target, sizeof(uint32_t));
    for (; i + 4 <= len; i += step) {
        float val;
        uint32_t valBits;
        std::memcpy(&val, buf + i, sizeof(float));
        std::memcpy(&valBits, buf + i, sizeof(uint32_t));
        if (valBits == targetBits || (!std::isnan(val) && std::fabs(val - target) <= epsilon)) {
            outOffsets.push_back(i);
        }
    }
}

void SimdKernel::scanExactF64(const uint8_t* buf, size_t len, double target, double epsilon, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 8) return;
    if (step == 0) step = 8;
    size_t i = 0;

#if MEMENGINE_HAS_NEON && defined(__aarch64__)
    if (step == 8 && epsilon > 0.0) {
        float64x2_t vTarget = vdupq_n_f64(target);
        float64x2_t vEps    = vdupq_n_f64(epsilon);
        for (; i + 16 <= len; i += 16) {
            float64x2_t vChunk = vreinterpretq_f64_u8(vld1q_u8(buf + i));
            float64x2_t vDiff  = vabdq_f64(vChunk, vTarget);
            uint64x2_t  vCmp   = vcleq_f64(vDiff, vEps);
            if (vmaxvq_u32(vreinterpretq_u32_u64(vCmp)) != 0) {
                uint64_t lanes[2];
                vst1q_u64(lanes, vCmp);
                if (lanes[0] != 0) outOffsets.push_back(i);
                if (lanes[1] != 0) outOffsets.push_back(i + 8);
            }
        }
    }
#endif

    uint64_t targetBits;
    std::memcpy(&targetBits, &target, sizeof(uint64_t));
    for (; i + 8 <= len; i += step) {
        double val;
        uint64_t valBits;
        std::memcpy(&val, buf + i, sizeof(double));
        std::memcpy(&valBits, buf + i, sizeof(uint64_t));
        if (valBits == targetBits || (!std::isnan(val) && std::fabs(val - target) <= epsilon)) {
            outOffsets.push_back(i);
        }
    }
}

// ============================================================================
// 2. Range Match Kernels
// ============================================================================
void SimdKernel::scanRangeF32(const uint8_t* buf, size_t len, float minVal, float maxVal, bool inclusive, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 4) return;
    if (step == 0) step = 4;
    size_t i = 0;

#if MEMENGINE_HAS_NEON
    if (step == 4) {
        float32x4_t vMin = vdupq_n_f32(minVal);
        float32x4_t vMax = vdupq_n_f32(maxVal);
        for (; i + 16 <= len; i += 16) {
            float32x4_t vChunk = vreinterpretq_f32_u8(vld1q_u8(buf + i));
            uint32x4_t cMin = inclusive ? vcgeq_f32(vChunk, vMin) : vcgtq_f32(vChunk, vMin);
            uint32x4_t cMax = inclusive ? vcleq_f32(vChunk, vMax) : vcltq_f32(vChunk, vMax);
            uint32x4_t vMask = vandq_u32(cMin, cMax);
            if (neonMaxvU32(vMask) != 0) {
                uint32_t lanes[4];
                vst1q_u32(lanes, vMask);
                for (size_t k = 0; k < 4; ++k) {
                    if (lanes[k] != 0) outOffsets.push_back(i + k * 4);
                }
            }
        }
    }
#endif

    for (; i + 4 <= len; i += step) {
        float val;
        std::memcpy(&val, buf + i, sizeof(float));
        if (std::isnan(val)) continue;
        bool ok = inclusive ? (val >= minVal && val <= maxVal) : (val > minVal && val < maxVal);
        if (ok) outOffsets.push_back(i);
    }
}

template <typename T>
void SimdKernel::scanRange(const uint8_t* buf, size_t len, T minVal, T maxVal, bool inclusive, size_t step, std::vector<size_t>& outOffsets) {
    if constexpr (std::is_same_v<T, float>) {
        scanRangeF32(buf, len, minVal, maxVal, inclusive, step, outOffsets);
        return;
    }

    const size_t tSize = sizeof(T);
    if (len < tSize) return;
    if (step == 0) step = tSize;
    size_t i = 0;

#if MEMENGINE_HAS_NEON
    if constexpr (std::is_same_v<T, int32_t>) {
        if (step == 4) {
            int32x4_t vMin = vdupq_n_s32(minVal);
            int32x4_t vMax = vdupq_n_s32(maxVal);
            for (; i + 16 <= len; i += 16) {
                int32x4_t vChunk = vreinterpretq_s32_u8(vld1q_u8(buf + i));
                uint32x4_t cMin = inclusive ? vcgeq_s32(vChunk, vMin) : vcgtq_s32(vChunk, vMin);
                uint32x4_t cMax = inclusive ? vcleq_s32(vChunk, vMax) : vcltq_s32(vChunk, vMax);
                uint32x4_t vMask = vandq_u32(cMin, cMax);
                if (neonMaxvU32(vMask) != 0) {
                    uint32_t lanes[4];
                    vst1q_u32(lanes, vMask);
                    for (size_t k = 0; k < 4; ++k) {
                        if (lanes[k] != 0) outOffsets.push_back(i + k * 4);
                    }
                }
            }
        }
    } else if constexpr (std::is_same_v<T, uint32_t>) {
        if (step == 4) {
            uint32x4_t vMin = vdupq_n_u32(minVal);
            uint32x4_t vMax = vdupq_n_u32(maxVal);
            for (; i + 16 <= len; i += 16) {
                uint32x4_t vChunk = vreinterpretq_u32_u8(vld1q_u8(buf + i));
                uint32x4_t cMin = inclusive ? vcgeq_u32(vChunk, vMin) : vcgtq_u32(vChunk, vMin);
                uint32x4_t cMax = inclusive ? vcleq_u32(vChunk, vMax) : vcltq_u32(vChunk, vMax);
                uint32x4_t vMask = vandq_u32(cMin, cMax);
                if (neonMaxvU32(vMask) != 0) {
                    uint32_t lanes[4];
                    vst1q_u32(lanes, vMask);
                    for (size_t k = 0; k < 4; ++k) {
                        if (lanes[k] != 0) outOffsets.push_back(i + k * 4);
                    }
                }
            }
        }
    }
#endif

    const bool checkArm64TbiPointer = std::is_same_v<T, int64_t> &&
        minVal >= static_cast<T>(0x10000LL) &&
        maxVal <= static_cast<T>(0x00FFFFFFFFFFFFFFLL) &&
        minVal <= maxVal;

    for (; i + tSize <= len; i += step) {
        T val;
        std::memcpy(&val, buf + i, tSize);
        if constexpr (std::is_floating_point_v<T>) {
            if (std::isnan(val)) continue;
        }
        bool ok = inclusive ? (val >= minVal && val <= maxVal) : (val > minVal && val < maxVal);
        if (!ok && checkArm64TbiPointer) {
            if constexpr (std::is_same_v<T, int64_t>) {
                uint64_t uval = static_cast<uint64_t>(val);
                uint8_t topByte = static_cast<uint8_t>(uval >> 56);
                if (topByte != 0 && topByte != 0xFF) {
                    int64_t untagged = static_cast<int64_t>(uval & ArchPtrPolicy::ARM64_TBI_MASK);
                    ok = inclusive ? (untagged >= minVal && untagged <= maxVal)
                                   : (untagged >  minVal && untagged <  maxVal);
                }
            }
        }
        if (ok) outOffsets.push_back(i);
    }
}

void SimdKernel::scanRangeInteger(
    const uint8_t* buf,
    size_t len,
    DataType dt,
    int64_t iMin,
    int64_t iMax,
    bool inclusive,
    size_t step,
    std::vector<size_t>& outOffsets
) {
    if (iMin > iMax) std::swap(iMin, iMax);

    switch (dt) {
        case DataType::INT8: {
            if (iMin >= 0 && iMax > 127 && iMax <= 255) {
                scanRange<uint8_t>(buf, len, static_cast<uint8_t>(iMin), static_cast<uint8_t>(iMax), inclusive, 1, outOffsets);
            } else {
                int8_t lo = static_cast<int8_t>(std::clamp<int64_t>(iMin, -128, 127));
                int8_t hi = static_cast<int8_t>(std::clamp<int64_t>(iMax, -128, 127));
                scanRange<int8_t>(buf, len, lo, hi, inclusive, 1, outOffsets);
            }
            break;
        }
        case DataType::INT16: {
            if (iMin >= 0 && iMax > 32767 && iMax <= 65535) {
                scanRange<uint16_t>(buf, len, static_cast<uint16_t>(iMin), static_cast<uint16_t>(iMax), inclusive, step, outOffsets);
            } else {
                int16_t lo = static_cast<int16_t>(std::clamp<int64_t>(iMin, -32768, 32767));
                int16_t hi = static_cast<int16_t>(std::clamp<int64_t>(iMax, -32768, 32767));
                scanRange<int16_t>(buf, len, lo, hi, inclusive, step, outOffsets);
            }
            break;
        }
        case DataType::INT32: {
            if (iMin >= 0 && iMax > 2147483647LL && iMax <= 4294967295LL) {
                scanRange<uint32_t>(buf, len, static_cast<uint32_t>(iMin), static_cast<uint32_t>(iMax), inclusive, step, outOffsets);
            } else {
                int32_t lo = static_cast<int32_t>(std::clamp<int64_t>(iMin, -2147483648LL, 2147483647LL));
                int32_t hi = static_cast<int32_t>(std::clamp<int64_t>(iMax, -2147483648LL, 2147483647LL));
                scanRange<int32_t>(buf, len, lo, hi, inclusive, step, outOffsets);
            }
            break;
        }
        case DataType::INT64:
        default: {
            scanRange<int64_t>(buf, len, iMin, iMax, inclusive, step, outOffsets);
            break;
        }
    }
}

template void SimdKernel::scanRange<int8_t>(const uint8_t*, size_t, int8_t, int8_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<uint8_t>(const uint8_t*, size_t, uint8_t, uint8_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<int16_t>(const uint8_t*, size_t, int16_t, int16_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<uint16_t>(const uint8_t*, size_t, uint16_t, uint16_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<int32_t>(const uint8_t*, size_t, int32_t, int32_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<uint32_t>(const uint8_t*, size_t, uint32_t, uint32_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<int64_t>(const uint8_t*, size_t, int64_t, int64_t, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<float>(const uint8_t*, size_t, float, float, bool, size_t, std::vector<size_t>&);
template void SimdKernel::scanRange<double>(const uint8_t*, size_t, double, double, bool, size_t, std::vector<size_t>&);

// ============================================================================
// 3. Byte Array / Masked Pattern Search (High-entropy anchor selection)
// ============================================================================
void SimdKernel::scanBytePattern(
    const uint8_t* buf,
    size_t len,
    const uint8_t* pattern,
    const uint8_t* mask,
    size_t patLen,
    std::vector<size_t>& outOffsets
) {
    if (!buf || !pattern || patLen == 0 || len < patLen) return;

    // Select highest-entropy exact byte as anchor (preferring non-0x00 and non-0xFF bytes, inspired by frida-gum)
    size_t anchorIdx = 0;
    bool hasAnchor = false;
    int bestScore = -1;
    for (size_t k = 0; k < patLen; ++k) {
        if (!mask || mask[k] == 0xFF) {
            uint8_t b = pattern[k];
            int score = (b != 0x00 && b != 0xFF) ? 10 : (b != 0x00 ? 5 : 1);
            if (score > bestScore) {
                bestScore = score;
                anchorIdx = k;
                hasAnchor = true;
                if (score == 10) break;
            }
        }
    }

    const size_t limit = len - patLen + 1;
    if (!hasAnchor) {
        for (size_t i = 0; i < limit; ++i) {
            bool match = true;
            for (size_t j = 0; j < patLen; ++j) {
                if (mask && (buf[i + j] & mask[j]) != (pattern[j] & mask[j])) {
                    match = false;
                    break;
                }
            }
            if (match) outOffsets.push_back(i);
        }
        return;
    }

    const uint8_t anchorByte = pattern[anchorIdx];
    size_t i = 0;
    while (i < limit) {
        const void* p = std::memchr(buf + i + anchorIdx, anchorByte, limit - i);
        if (!p) break;
        size_t candidatePos = static_cast<const uint8_t*>(p) - buf - anchorIdx;

        bool match = true;
        if (!mask) {
            match = (std::memcmp(buf + candidatePos, pattern, patLen) == 0);
        } else {
            for (size_t j = 0; j < patLen; ++j) {
                if ((buf[candidatePos + j] & mask[j]) != (pattern[j] & mask[j])) {
                    match = false;
                    break;
                }
            }
        }
        if (match) {
            outOffsets.push_back(candidatePos);
        }
        i = candidatePos + 1;
    }
}

// ============================================================================
// 4. Encoded & XOR Kernels
// ============================================================================
void SimdKernel::scanXorPair32(const uint8_t* buf, size_t len, uint32_t targetBits, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 8) return;
    if (step == 0) step = 4;
    size_t i = 0;

#if MEMENGINE_HAS_NEON
    if (step == 4) {
        uint32x4_t vTarget = vdupq_n_u32(targetBits);
        for (; i + 20 <= len; i += 16) {
            uint32x4_t v0 = vreinterpretq_u32_u8(vld1q_u8(buf + i));
            uint32x4_t v1 = vreinterpretq_u32_u8(vld1q_u8(buf + i + 4));
            uint32x4_t vXor = veorq_u32(v0, v1);
            uint32x4_t vCmp = vceqq_u32(vXor, vTarget);
            if (neonMaxvU32(vCmp) != 0) {
                uint32_t lanes[4];
                vst1q_u32(lanes, vCmp);
                for (size_t k = 0; k < 4; ++k) {
                    if (lanes[k] != 0) outOffsets.push_back(i + k * 4);
                }
            }
        }
    }
#endif

    for (; i + 8 <= len; i += step) {
        uint32_t k, v;
        std::memcpy(&k, buf + i, 4);
        std::memcpy(&v, buf + i + 4, 4);
        if ((k ^ v) == targetBits) {
            outOffsets.push_back(i);
        }
    }
}

void SimdKernel::scanXorPair64(const uint8_t* buf, size_t len, uint64_t targetBits, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 16) return;
    if (step == 0) step = 8;
    for (size_t i = 0; i + 16 <= len; i += step) {
        uint64_t k, v;
        std::memcpy(&k, buf + i, 8);
        std::memcpy(&v, buf + i + 8, 8);
        if ((k ^ v) == targetBits) {
            outOffsets.push_back(i);
        }
    }
}

void SimdKernel::scanAddressXor32(uintptr_t baseAddr, const uint8_t* buf, size_t len, uint32_t logicalTarget, uint32_t addrMask, size_t step, std::vector<size_t>& outOffsets) {
    if (len < 4) return;
    if (step == 0) step = 4;
    for (size_t i = 0; i + 4 <= len; i += step) {
        uint32_t stored;
        std::memcpy(&stored, buf + i, 4);
        uint32_t addrKey = static_cast<uint32_t>(baseAddr + i) & addrMask;
        if ((stored ^ addrKey) == logicalTarget) {
            outOffsets.push_back(i);
        }
    }
}

// ============================================================================
// 5. Snapshot Delta / Fuzzy Comparison Kernel
// ============================================================================
template <typename T>
void SimdKernel::compareBuffersDelta(
    uintptr_t baseAddr,
    const uint8_t* curBuf,
    const uint8_t* oldBuf,
    size_t len,
    SearchOp op,
    T exactDelta,
    uint8_t regionTag,
    std::vector<CandidateEntry>& outCandidates,
    size_t step
) {
    const size_t tSize = sizeof(T);
    if (len < tSize) return;
    const size_t actualStep = (step == 0) ? std::min<size_t>(tSize, 4) : step;

    DataType dt = DataType::INT32;
    if constexpr (std::is_same_v<T, int8_t>)   dt = DataType::INT8;
    if constexpr (std::is_same_v<T, int16_t>)  dt = DataType::INT16;
    if constexpr (std::is_same_v<T, int32_t>)  dt = DataType::INT32;
    if constexpr (std::is_same_v<T, int64_t>)  dt = DataType::INT64;
    if constexpr (std::is_same_v<T, float>)    dt = DataType::FLOAT;
    if constexpr (std::is_same_v<T, double>)   dt = DataType::DOUBLE;

    for (size_t off = 0; off + tSize <= len; off += actualStep) {
        T curVal, oldVal;
        std::memcpy(&curVal, curBuf + off, tSize);
        std::memcpy(&oldVal, oldBuf + off, tSize);

        if constexpr (std::is_floating_point_v<T>) {
            if (std::isnan(curVal) || std::isnan(oldVal)) continue;
        }

        bool match = false;
        switch (op) {
            case SearchOp::INCREASED:   match = (curVal > oldVal); break;
            case SearchOp::DECREASED:   match = (curVal < oldVal); break;
            case SearchOp::CHANGED:     match = (curVal != oldVal); break;
            case SearchOp::UNCHANGED:   match = (curVal == oldVal); break;
            case SearchOp::DELTA_EXACT: {
                if constexpr (std::is_floating_point_v<T>) {
                    T diff = static_cast<T>(std::fabs(curVal - oldVal));
                    match = (std::fabs(diff - std::fabs(exactDelta)) <= static_cast<T>(1e-4));
                } else {
                    match = ((curVal - oldVal) == exactDelta) || ((oldVal - curVal) == exactDelta);
                }
                break;
            }
            default: break;
        }

        if (match) {
            CandidateEntry ce{};
            ce.address   = static_cast<uint64_t>(baseAddr + off);
            ce.rawValue  = 0;
            std::memcpy(&ce.rawValue, &curVal, tSize);
            ce.type      = static_cast<uint8_t>(dt);
            ce.regionTag = regionTag;
            outCandidates.push_back(ce);
        }
    }
}

template void SimdKernel::compareBuffersDelta<int8_t>(uintptr_t, const uint8_t*, const uint8_t*, size_t, SearchOp, int8_t, uint8_t, std::vector<CandidateEntry>&, size_t);
template void SimdKernel::compareBuffersDelta<int16_t>(uintptr_t, const uint8_t*, const uint8_t*, size_t, SearchOp, int16_t, uint8_t, std::vector<CandidateEntry>&, size_t);
template void SimdKernel::compareBuffersDelta<int32_t>(uintptr_t, const uint8_t*, const uint8_t*, size_t, SearchOp, int32_t, uint8_t, std::vector<CandidateEntry>&, size_t);
template void SimdKernel::compareBuffersDelta<int64_t>(uintptr_t, const uint8_t*, const uint8_t*, size_t, SearchOp, int64_t, uint8_t, std::vector<CandidateEntry>&, size_t);
template void SimdKernel::compareBuffersDelta<float>(uintptr_t, const uint8_t*, const uint8_t*, size_t, SearchOp, float, uint8_t, std::vector<CandidateEntry>&, size_t);
template void SimdKernel::compareBuffersDelta<double>(uintptr_t, const uint8_t*, const uint8_t*, size_t, SearchOp, double, uint8_t, std::vector<CandidateEntry>&, size_t);

} // namespace mem_engine
