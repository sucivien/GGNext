#include "mem_engine/asm_engine.hpp"

#include <cstdio>
#include <cstring>
#include <dlfcn.h>

namespace mem_engine {

namespace {

// Keystone C ABI constants (matching Keystone 0.9.x)
enum KsArch : int {
    KS_ARCH_ARM   = 1,
    KS_ARCH_ARM64 = 2,
    KS_ARCH_X86   = 4
};

enum KsMode : int {
    KS_MODE_LITTLE_ENDIAN = 0,
    KS_MODE_ARM           = 1 << 0,
    KS_MODE_THUMB         = 1 << 4,
    KS_MODE_32            = 1 << 2,
    KS_MODE_64            = 1 << 3
};

using KsEnginePtr = void*;
using FnKsOpen     = int (*)(int arch, int mode, KsEnginePtr* ks);
using FnKsOption   = int (*)(KsEnginePtr ks, int type, size_t value);
using FnKsAsm      = int (*)(KsEnginePtr ks, const char* string, uint64_t address, unsigned char** encoding, size_t* encoding_size, size_t* stat_count);
using FnKsFree     = void (*)(unsigned char* p);
using FnKsClose    = int (*)(KsEnginePtr ks);
using FnKsErrno    = int (*)(KsEnginePtr ks);
using FnKsStrerror = const char* (*)(int code);

// Capstone C ABI constants (matching Capstone 5.0.x)
enum CsArch : int {
    CS_ARCH_ARM   = 0,
    CS_ARCH_ARM64 = 1,
    CS_ARCH_X86   = 3
};

enum CsMode : int {
    CS_MODE_LITTLE_ENDIAN = 0,
    CS_MODE_ARM           = 0,
    CS_MODE_32            = 1 << 2,
    CS_MODE_64            = 1 << 3,
    CS_MODE_THUMB         = 1 << 4
};

// Capstone cs_insn ABI layout (Capstone 5.x, CS_MNEMONIC_SIZE = 32)
struct CsInsnRaw {
    unsigned int id;
    uint64_t address;
    uint16_t size;
    uint8_t bytes[24];
    char mnemonic[32];
    char op_str[160];
    void* detail;
};

using CsHandle = size_t;
using FnCsOpen   = int (*)(int arch, int mode, CsHandle* handle);
using FnCsOption = int (*)(CsHandle handle, int type, size_t value);
using FnCsDisasm = size_t (*)(CsHandle handle, const uint8_t* code, size_t code_size, uint64_t address, size_t count, CsInsnRaw** insn);
using FnCsFree   = void (*)(CsInsnRaw* insn, size_t count);
using FnCsClose  = int (*)(CsHandle* handle);

bool mapKsArch(AsmArch arch, int& outArch, int& outMode) {
    switch (arch) {
        case AsmArch::ARM64:
            outArch = KS_ARCH_ARM64;
            outMode = KS_MODE_LITTLE_ENDIAN;
            return true;
        case AsmArch::ARM:
            outArch = KS_ARCH_ARM;
            outMode = KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN;
            return true;
        case AsmArch::THUMB:
            outArch = KS_ARCH_ARM;
            outMode = KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN;
            return true;
        case AsmArch::X86:
            outArch = KS_ARCH_X86;
            outMode = KS_MODE_32;
            return true;
        case AsmArch::X86_64:
            outArch = KS_ARCH_X86;
            outMode = KS_MODE_64;
            return true;
        default:
            return false;
    }
}

bool mapCsArch(AsmArch arch, int& outArch, int& outMode) {
    switch (arch) {
        case AsmArch::ARM64:
            outArch = CS_ARCH_ARM64;
            outMode = CS_MODE_LITTLE_ENDIAN;
            return true;
        case AsmArch::ARM:
            outArch = CS_ARCH_ARM;
            outMode = CS_MODE_ARM | CS_MODE_LITTLE_ENDIAN;
            return true;
        case AsmArch::THUMB:
            outArch = CS_ARCH_ARM;
            outMode = CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN;
            return true;
        case AsmArch::X86:
            outArch = CS_ARCH_X86;
            outMode = CS_MODE_32;
            return true;
        case AsmArch::X86_64:
            outArch = CS_ARCH_X86;
            outMode = CS_MODE_64;
            return true;
        default:
            return false;
    }
}

} // namespace

AsmEngine& AsmEngine::instance() {
    static AsmEngine inst;
    return inst;
}

AsmEngine::~AsmEngine() {
    unload();
}

bool AsmEngine::loadFromDir(const std::string& libDir, std::string* outError) {
    std::lock_guard<std::mutex> lk(mtx_);
    if (ksHandle_) { ::dlclose(ksHandle_); ksHandle_ = nullptr; }
    if (csHandle_) { ::dlclose(csHandle_); csHandle_ = nullptr; }
    fnKsOpen_ = fnKsOption_ = fnKsAsm_ = fnKsFree_ = fnKsClose_ = fnKsErrno_ = fnKsStrerror_ = nullptr;
    fnCsOpen_ = fnCsOption_ = fnCsDisasm_ = fnCsFree_ = fnCsClose_ = nullptr;

    if (libDir.empty()) {
        if (outError) *outError = "库目录路径为空";
        return false;
    }

    std::string base = libDir;
    while (!base.empty() && base.back() == '/') base.pop_back();
    std::string ksPath = base + "/libkeystone.so";
    std::string csPath = base + "/libcapstone.so";

    std::string errs;

    void* csH = ::dlopen(csPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!csH) {
        const char* e = ::dlerror();
        errs += std::string("Capstone 加载失败: ") + (e ? e : "unknown") + "; ";
    } else {
        fnCsOpen_   = ::dlsym(csH, "cs_open");
        fnCsOption_ = ::dlsym(csH, "cs_option");
        fnCsDisasm_ = ::dlsym(csH, "cs_disasm");
        fnCsFree_   = ::dlsym(csH, "cs_free");
        fnCsClose_  = ::dlsym(csH, "cs_close");
        if (fnCsOpen_ && fnCsDisasm_ && fnCsFree_ && fnCsClose_) {
            csHandle_ = csH;
        } else {
            ::dlclose(csH);
            errs += "Capstone 符号不完整; ";
        }
    }

    void* ksH = ::dlopen(ksPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!ksH) {
        const char* e = ::dlerror();
        errs += std::string("Keystone 加载失败: ") + (e ? e : "unknown") + "; ";
    } else {
        fnKsOpen_     = ::dlsym(ksH, "ks_open");
        fnKsOption_   = ::dlsym(ksH, "ks_option");
        fnKsAsm_      = ::dlsym(ksH, "ks_asm");
        fnKsFree_     = ::dlsym(ksH, "ks_free");
        fnKsClose_    = ::dlsym(ksH, "ks_close");
        fnKsErrno_    = ::dlsym(ksH, "ks_errno");
        fnKsStrerror_ = ::dlsym(ksH, "ks_strerror");
        if (fnKsOpen_ && fnKsAsm_ && fnKsFree_ && fnKsClose_) {
            ksHandle_ = ksH;
        } else {
            ::dlclose(ksH);
            errs += "Keystone 符号不完整; ";
        }
    }

    if (outError) *outError = errs;
    return ksHandle_ != nullptr && csHandle_ != nullptr;
}

void AsmEngine::unload() {
    std::lock_guard<std::mutex> lk(mtx_);
    if (ksHandle_) { ::dlclose(ksHandle_); ksHandle_ = nullptr; }
    if (csHandle_) { ::dlclose(csHandle_); csHandle_ = nullptr; }
    fnKsOpen_ = fnKsOption_ = fnKsAsm_ = fnKsFree_ = fnKsClose_ = fnKsErrno_ = fnKsStrerror_ = nullptr;
    fnCsOpen_ = fnCsOption_ = fnCsDisasm_ = fnCsFree_ = fnCsClose_ = nullptr;
}

bool AsmEngine::isKeystoneLoaded() const {
    std::lock_guard<std::mutex> lk(mtx_);
    return ksHandle_ != nullptr;
}

bool AsmEngine::isCapstoneLoaded() const {
    std::lock_guard<std::mutex> lk(mtx_);
    return csHandle_ != nullptr;
}

AsmEncodeResult AsmEngine::assemble(AsmArch arch, uint64_t address, const std::string& code) {
    AsmEncodeResult res;
    std::lock_guard<std::mutex> lk(mtx_);
    if (!ksHandle_ || !fnKsOpen_ || !fnKsAsm_ || !fnKsFree_ || !fnKsClose_) {
        res.error = "请先在主页导入 Keystone/Capstone 汇编引擎包";
        return res;
    }

    int kArch = 0, kMode = 0;
    if (!mapKsArch(arch, kArch, kMode)) {
        res.error = "不支持的目标架构";
        return res;
    }

    auto ksOpen = reinterpret_cast<FnKsOpen>(fnKsOpen_);
    auto ksAsm = reinterpret_cast<FnKsAsm>(fnKsAsm_);
    auto ksFree = reinterpret_cast<FnKsFree>(fnKsFree_);
    auto ksClose = reinterpret_cast<FnKsClose>(fnKsClose_);
    auto ksErrno = reinterpret_cast<FnKsErrno>(fnKsErrno_);
    auto ksStrerror = reinterpret_cast<FnKsStrerror>(fnKsStrerror_);

    KsEnginePtr ks = nullptr;
    int err = ksOpen(kArch, kMode, &ks);
    if (err != 0 || !ks) {
        res.error = (ksStrerror ? ksStrerror(err) : "ks_open failed");
        return res;
    }

    unsigned char* encode = nullptr;
    size_t size = 0;
    size_t count = 0;
    int rc = ksAsm(ks, code.c_str(), address, &encode, &size, &count);
    if (rc != 0) {
        int e = ksErrno ? ksErrno(ks) : rc;
        res.error = (ksStrerror ? ksStrerror(e) : "汇编语法错误");
    } else if (encode && size > 0) {
        res.ok = true;
        res.bytes.assign(encode, encode + size);
        res.statementCount = count;
    } else {
        res.error = "未生成有效机器码";
    }

    if (encode) ksFree(encode);
    ksClose(ks);
    return res;
}

std::string AsmEngine::fallbackDisassembleArm64(uint64_t address, uint32_t insn) {
    char buf[96];
    if (insn == 0xD503201Fu) return "NOP";
    if (insn == 0xD65F03C0u) return "RET";
    if ((insn & 0xFFFF0000u) == 0x00000000u) {
        std::snprintf(buf, sizeof(buf), "UDF #0x%X", insn & 0xFFFFu);
        return buf;
    }
    if ((insn & 0xFFE0001Fu) == 0xD4200000u) {
        std::snprintf(buf, sizeof(buf), "BRK #0x%X", (insn >> 5) & 0xFFFFu);
        return buf;
    }
    if ((insn & 0xFFE0001Fu) == 0xD4000001u) {
        std::snprintf(buf, sizeof(buf), "SVC #0x%X", (insn >> 5) & 0xFFFFu);
        return buf;
    }
    // B / BL imm26
    if ((insn & 0x7C000000u) == 0x14000000u) {
        bool isBl = (insn & 0x80000000u) != 0;
        int32_t imm26 = static_cast<int32_t>(insn & 0x03FFFFFFu);
        if (imm26 & 0x02000000) imm26 |= ~0x03FFFFFF;
        uint64_t target = address + static_cast<int64_t>(imm26) * 4;
        std::snprintf(buf, sizeof(buf), "%s 0x%llX", isBl ? "BL" : "B", static_cast<unsigned long long>(target));
        return buf;
    }
    // MOVZ / MOVN (32/64)
    if ((insn & 0x7F800000u) == 0x52800000u) {
        bool is64 = (insn & 0x80000000u) != 0;
        uint32_t rd = insn & 0x1Fu;
        uint32_t imm16 = (insn >> 5) & 0xFFFFu;
        uint32_t hw = ((insn >> 21) & 0x3u) * 16;
        if (hw == 0) {
            std::snprintf(buf, sizeof(buf), "MOV %c%u, #0x%X", is64 ? 'X' : 'W', rd, imm16);
        } else {
            std::snprintf(buf, sizeof(buf), "MOVZ %c%u, #0x%X, LSL #%u", is64 ? 'X' : 'W', rd, imm16, hw);
        }
        return buf;
    }
    // BR / BLR
    if ((insn & 0xFFFFFC1Fu) == 0xD61F0000u) {
        std::snprintf(buf, sizeof(buf), "BR X%u", (insn >> 5) & 0x1Fu);
        return buf;
    }
    if ((insn & 0xFFFFFC1Fu) == 0xD63F0000u) {
        std::snprintf(buf, sizeof(buf), "BLR X%u", (insn >> 5) & 0x1Fu);
        return buf;
    }
    std::snprintf(buf, sizeof(buf), ".word 0x%08X", insn);
    return buf;
}

std::string AsmEngine::disassembleOne(AsmArch arch, uint64_t address, const uint8_t* bytes, size_t size, uint32_t* outInsnSize) {
    if (!bytes || size == 0) {
        if (outInsnSize) *outInsnSize = 0;
        return "";
    }
    std::lock_guard<std::mutex> lk(mtx_);
    if (csHandle_ && fnCsOpen_ && fnCsDisasm_ && fnCsFree_ && fnCsClose_) {
        int cArch = 0, cMode = 0;
        if (mapCsArch(arch, cArch, cMode)) {
            auto csOpen = reinterpret_cast<FnCsOpen>(fnCsOpen_);
            auto csDisasm = reinterpret_cast<FnCsDisasm>(fnCsDisasm_);
            auto csFree = reinterpret_cast<FnCsFree>(fnCsFree_);
            auto csClose = reinterpret_cast<FnCsClose>(fnCsClose_);

            CsHandle h = 0;
            if (csOpen(cArch, cMode, &h) == 0) {
                CsInsnRaw* insn = nullptr;
                size_t count = csDisasm(h, bytes, size, address, 1, &insn);
                std::string text;
                if (count > 0 && insn) {
                    text = insn[0].mnemonic;
                    if (insn[0].op_str[0] != '\0') {
                        text.push_back(' ');
                        text += insn[0].op_str;
                    }
                    if (outInsnSize) *outInsnSize = insn[0].size;
                    csFree(insn, count);
                } else {
                    if (outInsnSize) *outInsnSize = (arch == AsmArch::THUMB) ? 2 : 4;
                }
                csClose(&h);
                if (!text.empty()) return text;
            }
        }
    }

    // Fallback when Capstone pack is not yet imported
    if (arch == AsmArch::ARM64 && size >= 4) {
        uint32_t w = 0;
        std::memcpy(&w, bytes, 4);
        if (outInsnSize) *outInsnSize = 4;
        return fallbackDisassembleArm64(address, w);
    }
    if (outInsnSize) *outInsnSize = (size >= 4) ? 4 : static_cast<uint32_t>(size);
    return "";
}

std::vector<DisasmInsn> AsmEngine::disassembleBlock(AsmArch arch, uint64_t baseAddress, const uint8_t* bytes, size_t size, size_t maxInsns) {
    std::vector<DisasmInsn> out;
    if (!bytes || size == 0 || maxInsns == 0) return out;

    std::lock_guard<std::mutex> lk(mtx_);
    if (csHandle_ && fnCsOpen_ && fnCsDisasm_ && fnCsFree_ && fnCsClose_) {
        int cArch = 0, cMode = 0;
        if (mapCsArch(arch, cArch, cMode)) {
            auto csOpen = reinterpret_cast<FnCsOpen>(fnCsOpen_);
            auto csDisasm = reinterpret_cast<FnCsDisasm>(fnCsDisasm_);
            auto csFree = reinterpret_cast<FnCsFree>(fnCsFree_);
            auto csClose = reinterpret_cast<FnCsClose>(fnCsClose_);

            CsHandle h = 0;
            if (csOpen(cArch, cMode, &h) == 0) {
                // Fixed-width architectures (ARM64/ARM): decode word-by-word so a single invalid word doesn't halt the whole memory page
                if (arch == AsmArch::ARM64 || arch == AsmArch::ARM) {
                    size_t step = 4;
                    size_t n = std::min(maxInsns, size / step);
                    out.reserve(n);
                    for (size_t i = 0; i < n; ++i) {
                        uint64_t addr = baseAddress + i * step;
                        CsInsnRaw* insn = nullptr;
                        size_t cnt = csDisasm(h, bytes + i * step, step, addr, 1, &insn);
                        DisasmInsn item;
                        item.address = addr;
                        item.size = static_cast<uint32_t>(step);
                        if (cnt > 0 && insn) {
                            item.text = insn[0].mnemonic;
                            if (insn[0].op_str[0] != '\0') {
                                item.text.push_back(' ');
                                item.text += insn[0].op_str;
                            }
                            csFree(insn, cnt);
                        } else if (arch == AsmArch::ARM64) {
                            uint32_t w = 0;
                            std::memcpy(&w, bytes + i * step, 4);
                            item.text = fallbackDisassembleArm64(addr, w);
                        } else {
                            item.text = ".word";
                        }
                        out.push_back(std::move(item));
                    }
                } else {
                    // Variable-width architectures (Thumb, x86, x86_64): decode sequentially from baseAddress
                    size_t off = 0;
                    while (off < size && out.size() < maxInsns) {
                        CsInsnRaw* insn = nullptr;
                        size_t cnt = csDisasm(h, bytes + off, size - off, baseAddress + off, maxInsns - out.size(), &insn);
                        if (cnt > 0 && insn) {
                            for (size_t i = 0; i < cnt; ++i) {
                                DisasmInsn item;
                                item.address = insn[i].address;
                                item.size = insn[i].size;
                                item.text = insn[i].mnemonic;
                                if (insn[i].op_str[0] != '\0') {
                                    item.text.push_back(' ');
                                    item.text += insn[i].op_str;
                                }
                                out.push_back(std::move(item));
                                off = static_cast<size_t>((insn[i].address - baseAddress) + insn[i].size);
                            }
                            csFree(insn, cnt);
                        } else {
                            // Skip 2 bytes (Thumb) or 1 byte (x86) on invalid opcode
                            size_t skip = (arch == AsmArch::THUMB) ? 2 : 1;
                            DisasmInsn item;
                            item.address = baseAddress + off;
                            item.size = static_cast<uint32_t>(skip);
                            item.text = "???";
                            out.push_back(std::move(item));
                            off += skip;
                        }
                    }
                }
                csClose(&h);
                return out;
            }
        }
    }

    // Fallback ARM64 block decode
    if (arch == AsmArch::ARM64) {
        size_t n = std::min(maxInsns, size / 4);
        out.reserve(n);
        for (size_t i = 0; i < n; ++i) {
            uint32_t w = 0;
            std::memcpy(&w, bytes + i * 4, 4);
            DisasmInsn item;
            item.address = baseAddress + i * 4;
            item.size = 4;
            item.text = fallbackDisassembleArm64(item.address, w);
            out.push_back(std::move(item));
        }
    }
    return out;
}

} // namespace mem_engine
