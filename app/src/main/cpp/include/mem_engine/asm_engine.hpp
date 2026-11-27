#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace mem_engine {

enum class AsmArch : int32_t {
    ARM64  = 0,  // A8
    ARM    = 1,  // A (32-bit ARM)
    THUMB  = 2,  // T (Thumb / Thumb-2)
    X86    = 3,  // x86 32-bit
    X86_64 = 4   // x86_64 64-bit
};

struct AsmEncodeResult {
    bool ok = false;
    std::vector<uint8_t> bytes;
    std::string error;
    size_t statementCount = 0;
};

struct DisasmInsn {
    uint64_t address = 0;
    uint32_t size = 0;
    std::string text;
};

/**
 * Dynamic runtime bridge for user-imported libkeystone.so & libcapstone.so.
 * Neither library is statically linked into the APK, keeping APK size minimal.
 */
class AsmEngine {
public:
    static AsmEngine& instance();

    bool loadFromDir(const std::string& libDir, std::string* outError = nullptr);
    void unload();

    bool isKeystoneLoaded() const;
    bool isCapstoneLoaded() const;

    AsmEncodeResult assemble(AsmArch arch, uint64_t address, const std::string& code);

    std::string disassembleOne(AsmArch arch, uint64_t address, const uint8_t* bytes, size_t size, uint32_t* outInsnSize = nullptr);

    std::vector<DisasmInsn> disassembleBlock(AsmArch arch, uint64_t baseAddress, const uint8_t* bytes, size_t size, size_t maxInsns = 256);

private:
    AsmEngine() = default;
    ~AsmEngine();

    static std::string fallbackDisassembleArm64(uint64_t address, uint32_t insn);

    mutable std::mutex mtx_;
    void* ksHandle_ = nullptr;
    void* csHandle_ = nullptr;

    // Keystone function pointers
    void* fnKsOpen_ = nullptr;
    void* fnKsOption_ = nullptr;
    void* fnKsAsm_ = nullptr;
    void* fnKsFree_ = nullptr;
    void* fnKsClose_ = nullptr;
    void* fnKsErrno_ = nullptr;
    void* fnKsStrerror_ = nullptr;

    // Capstone function pointers
    void* fnCsOpen_ = nullptr;
    void* fnCsOption_ = nullptr;
    void* fnCsDisasm_ = nullptr;
    void* fnCsFree_ = nullptr;
    void* fnCsClose_ = nullptr;
};

} // namespace mem_engine
