#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/search_engine.hpp"
#include "mem_engine/asm_engine.hpp"
#include "mem_engine/debugger.hpp"
#include "mem_engine/remote_alloc.hpp"
#include "mem_engine/plugin_host_api.h"
#include "mem_engine/lua_engine.hpp"
#include "xdl.h"
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <cstring>
#include <fstream>
#include <algorithm>
#include <sstream>
#include <sys/mman.h>
#include <unistd.h>

namespace {
struct AgentApi {
    using SetSpeedFn   = void (*)(float);
    using GetSpeedFn   = float (*)();
    using AllocPageFn  = uint64_t (*)(uint64_t, size_t, int);
    using IsActiveFn   = int (*)();

    void* handle = nullptr;
    SetSpeedFn setSpeed = nullptr;
    GetSpeedFn getSpeed = nullptr;
    AllocPageFn allocPage = nullptr;
    IsActiveFn isActive = nullptr;
    bool initialized = false;
};

AgentApi& lazyAgentApi() {
    static std::mutex mtx;
    static AgentApi api;
    std::lock_guard<std::mutex> lk(mtx);
    if (!api.initialized) {
        api.initialized = true;
        api.handle = ::dlopen("libggnext_agent.so", RTLD_LAZY);
        if (api.handle) {
            api.setSpeed  = reinterpret_cast<AgentApi::SetSpeedFn>(::dlsym(api.handle, "ggnext_agent_set_speed"));
            api.getSpeed  = reinterpret_cast<AgentApi::GetSpeedFn>(::dlsym(api.handle, "ggnext_agent_get_speed"));
            api.allocPage = reinterpret_cast<AgentApi::AllocPageFn>(::dlsym(api.handle, "ggnext_agent_alloc_page"));
            api.isActive  = reinterpret_cast<AgentApi::IsActiveFn>(::dlsym(api.handle, "ggnext_agent_is_hook_active"));
        }
    }
    return api;
}
} // namespace

#if __has_include(<jni.h>)
#include <jni.h>
#else
using jint      = int32_t;
using jlong     = int64_t;
using jboolean  = uint8_t;
using jfloat    = float;
using jdouble   = double;
using jbyte     = int8_t;
using jsize     = int32_t;
struct _jobject;
using jobject       = _jobject*;
using jclass        = _jobject*;
using jstring       = _jobject*;
using jintArray     = _jobject*;
using jlongArray    = _jobject*;
using jdoubleArray  = _jobject*;
using jbyteArray    = _jobject*;
using jobjectArray  = _jobject*;
struct _jmethodID;
using jmethodID = _jmethodID*;
struct JNIEnv;
struct JavaVM {
    jint GetEnv(void** penv, jint) { if (penv) *penv = nullptr; return 0; }
    jint AttachCurrentThread(JNIEnv** penv, void*) { if (penv) *penv = nullptr; return 0; }
    jint DetachCurrentThread() { return 0; }
};
struct JNIEnv {
    const char* GetStringUTFChars(jstring, jboolean*) { return ""; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jstring NewStringUTF(const char*) { return nullptr; }
    jsize GetArrayLength(jobject) { return 0; }
    jbyte* GetByteArrayElements(jbyteArray, jboolean*) { return nullptr; }
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}
    jint* GetIntArrayElements(jintArray, jboolean*) { return nullptr; }
    void ReleaseIntArrayElements(jintArray, jint*, jint) {}
    jlong* GetLongArrayElements(jlongArray, jboolean*) { return nullptr; }
    void ReleaseLongArrayElements(jlongArray, jlong*, jint) {}
    jdouble* GetDoubleArrayElements(jdoubleArray, jboolean*) { return nullptr; }
    void ReleaseDoubleArrayElements(jdoubleArray, jdouble*, jint) {}
    jbyteArray NewByteArray(jsize) { return nullptr; }
    void SetByteArrayRegion(jbyteArray, jsize, jsize, const jbyte*) {}
    jlongArray NewLongArray(jsize) { return nullptr; }
    void SetLongArrayRegion(jlongArray, jsize, jsize, const jlong*) {}
    jint GetJavaVM(JavaVM** vm) { if (vm) *vm = nullptr; return 0; }
    jobject NewGlobalRef(jobject o) { return o; }
    void DeleteGlobalRef(jobject) {}
    void DeleteLocalRef(jobject) {}
    jclass GetObjectClass(jobject) { return nullptr; }
    jmethodID GetMethodID(jclass, const char*, const char*) { return nullptr; }
    jobject CallObjectMethod(jobject, jmethodID, ...) { return nullptr; }
    void CallVoidMethod(jobject, jmethodID, ...) {}
    jboolean ExceptionCheck() { return 0; }
    void ExceptionClear() {}
};
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_OK 0
#define JNI_EDETACHED (-2)
#define JNI_VERSION_1_6 0x00010006
#endif

namespace {

struct NativeEngineSession {
    std::mutex                                mtx;
    mem_engine::ProcIO                        io;
    std::unique_ptr<mem_engine::SearchEngine>      scanner;
    std::unique_ptr<mem_engine::ValueLocker>       locker;
    std::unique_ptr<mem_engine::BreakpointManager> debugger;
    mem_engine::CancellationToken                  cancelToken;
    std::vector<mem_engine::VmaRegion>             cachedAllRegions;
    mem_engine::VmaIndex                           vmaIndex;

    explicit NativeEngineSession(size_t threads, const std::string& spillDir)
        : scanner(std::make_unique<mem_engine::SearchEngine>(io, threads, spillDir)),
          locker(std::make_unique<mem_engine::ValueLocker>(io, 5)),
          debugger(std::make_unique<mem_engine::BreakpointManager>()) {}

    void refreshAllRegions() {
        if (io.pid() > 0) {
            cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(io.pid(), 0, false);
        } else {
            cachedAllRegions.clear();
        }
        vmaIndex.rebuild(cachedAllRegions);
    }
};

constexpr uint32_t kGGNextRegionOrder[15] = {
    mem_engine::RegionFlag::CH,
    mem_engine::RegionFlag::JH,
    mem_engine::RegionFlag::CA,
    mem_engine::RegionFlag::CD,
    mem_engine::RegionFlag::CB,
    mem_engine::RegionFlag::PS,
    mem_engine::RegionFlag::A,
    mem_engine::RegionFlag::J,
    mem_engine::RegionFlag::S,
    mem_engine::RegionFlag::AS,
    mem_engine::RegionFlag::V,
    mem_engine::RegionFlag::O,
    mem_engine::RegionFlag::B,
    mem_engine::RegionFlag::XA,
    mem_engine::RegionFlag::XS
};

} // anonymous namespace

extern "C" {

// ============================================================================
// GGNext Native JNI Bridge (com.littlecodesauce.ggnext.engine.NativeMemBridge)
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCreate(
    JNIEnv* env,
    jclass /*clazz*/,
    jint workerThreads,
    jstring spillDirJstr
) {
    std::string spillDir = "/data/local/tmp";
    if (spillDirJstr != nullptr) {
        const char* cstr = env->GetStringUTFChars(spillDirJstr, nullptr);
        if (cstr && cstr[0] != '\0') spillDir = cstr;
        if (cstr) env->ReleaseStringUTFChars(spillDirJstr, cstr);
    }
    auto* session = new NativeEngineSession(static_cast<size_t>(workerThreads > 0 ? workerThreads : 4), spillDir);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDestroy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (session) {
        session->cancelToken.cancel();
        delete session;
    }
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAttach(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint pid
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAll();
    auto rc = session->io.attach(static_cast<int>(pid));
    if (rc == mem_engine::EngineErrc::OK) {
        session->scanner->refreshRegions();
        session->refreshAllRegions();
    }
    return static_cast<jint>(rc);
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeIsProcessAlive(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return JNI_FALSE;
    int pid = session->io.pid();
    if (pid <= 0) return JNI_FALSE;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    return ::access(path, F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetRegionMask(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint regionMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->setRegionMask(static_cast<uint32_t>(regionMask));
    if (session->io.pid() > 0) {
        session->scanner->refreshRegions();
    }
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetAddressFilter(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong minAddr,
    jlong maxAddr,
    jlong addrMask,
    jlong addrPattern
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->setAddressFilter(
        static_cast<uint64_t>(minAddr),
        static_cast<uint64_t>(maxAddr),
        static_cast<uint64_t>(addrMask),
        static_cast<uint64_t>(addrPattern)
    );
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCancelScan(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (session) {
        session->cancelToken.cancel();
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetRegionTotals(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint pid
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    if (pid > 0 && session->io.pid() == pid) {
        session->refreshAllRegions();
    } else if (pid > 0) {
        session->cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(static_cast<int>(pid), 0, false);
        session->vmaIndex.rebuild(session->cachedAllRegions);
    }

    jlong out[45];
    std::memset(out, 0, sizeof(out));

    for (const auto& r : session->cachedAllRegions) {
        for (int idx = 0; idx < 15; ++idx) {
            if (r.regionFlag == kGGNextRegionOrder[idx]) {
                out[idx] += static_cast<jlong>(r.length);
                if (out[15 + idx] == 0) {
                    out[15 + idx] = static_cast<jlong>(r.startAddress);
                }
                out[30 + idx] += 1;
                break;
            }
        }
    }

    jlongArray arr = env->NewLongArray(45);
    if (arr) {
        env->SetLongArrayRegion(arr, 0, 45, out);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetRegionCodeAt(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return env->NewStringUTF("O");
    std::lock_guard<std::mutex> lk(session->mtx);

    const char* tag = session->vmaIndex.regionTagAt(
        static_cast<uintptr_t>(address),
        session->io.targetIs64Bit()
    );
    return env->NewStringUTF(tag);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeClassifyPointer(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong ptrValue
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    uint32_t mask = session->vmaIndex.classifyPointer(
        static_cast<uint64_t>(ptrValue),
        session->io.targetIs64Bit()
    );
    return static_cast<jint>(mask);
}

JNIEXPORT jbyteArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeReadBlock(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jint size
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || size <= 0 || size > (16 * 1024 * 1024)) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<jbyte> buf(static_cast<size_t>(size), 0);
    size_t bytesRead = 0;
    session->io.readMemory(static_cast<uintptr_t>(address), buf.data(), static_cast<size_t>(size), &bytesRead);

    jbyteArray arr = env->NewByteArray(size);
    if (arr) {
        env->SetByteArrayRegion(arr, 0, size, buf.data());
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeWriteBlock(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return JNI_FALSE;

    auto rc = session->io.writeMemory(static_cast<uintptr_t>(address), ptr, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
    return rc == mem_engine::EngineErrc::OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCopyMemory(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong fromAddr,
    jlong toAddr,
    jlong length
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || length <= 0) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    const size_t total = static_cast<size_t>(std::min<int64_t>(length, 16LL * 1024LL * 1024LL));
    const size_t chunk = 64 * 1024;
    std::vector<uint8_t> buf(chunk);
    size_t copied = 0;

    while (copied < total) {
        size_t step = std::min(chunk, total - copied);
        size_t bytesRead = 0;
        auto rrc = session->io.readMemory(
            static_cast<uintptr_t>(fromAddr) + copied,
            buf.data(),
            step,
            &bytesRead
        );
        if (rrc != mem_engine::EngineErrc::OK || bytesRead == 0) break;
        auto wrc = session->io.writeMemory(
            static_cast<uintptr_t>(toAddr) + copied,
            buf.data(),
            bytesRead
        );
        if (wrc != mem_engine::EngineErrc::OK) break;
        copied += bytesRead;
    }
    return static_cast<jlong>(copied);
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDumpMemory(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong startAddr,
    jlong endAddr,
    jstring outFilePathJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || endAddr <= startAddr || !outFilePathJstr) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    const char* cpath = env->GetStringUTFChars(outFilePathJstr, nullptr);
    std::string outPath = cpath ? cpath : "";
    if (cpath) env->ReleaseStringUTFChars(outFilePathJstr, cpath);
    if (outPath.empty()) return 0;

    std::ofstream ofs(outPath, std::ios::out | std::ios::binary | std::ios::trunc);
    if (!ofs.is_open()) return 0;

    const size_t total = static_cast<size_t>(std::min<int64_t>(endAddr - startAddr, 64LL * 1024LL * 1024LL));
    const size_t chunk = 64 * 1024;
    std::vector<char> buf(chunk, 0);
    size_t written = 0;

    while (written < total) {
        size_t step = std::min(chunk, total - written);
        size_t bytesRead = 0;
        std::memset(buf.data(), 0, step);
        session->io.readMemory(
            static_cast<uintptr_t>(startAddr) + written,
            buf.data(),
            step,
            &bytesRead
        );
        ofs.write(buf.data(), static_cast<std::streamsize>(step));
        written += step;
    }
    ofs.close();
    return static_cast<jlong>(written);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchExact(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchExact(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineExact(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineExact(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchNotEqual(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchNotEqual(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineNotEqual(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineNotEqual(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchRange(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intMin,
    jlong intMax,
    jdouble floatMin,
    jdouble floatMax,
    jboolean inclusive,
    jboolean aligned,
    jboolean excludeRange
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchRange(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intMin),
        static_cast<int64_t>(intMax),
        static_cast<double>(floatMin),
        static_cast<double>(floatMax),
        inclusive == JNI_TRUE,
        aligned == JNI_TRUE,
        &session->cancelToken,
        nullptr,
        excludeRange == JNI_TRUE
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineRange(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intMin,
    jlong intMax,
    jdouble floatMin,
    jdouble floatMax,
    jboolean inclusive,
    jboolean excludeRange
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineRange(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intMin),
        static_cast<int64_t>(intMax),
        static_cast<double>(floatMin),
        static_cast<double>(floatMax),
        inclusive == JNI_TRUE,
        &session->cancelToken,
        nullptr,
        excludeRange == JNI_TRUE
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchPointers(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong targetAddress,
    jlong maxOffset,
    jint protMask,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchPointers(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<uintptr_t>(targetAddress),
        static_cast<uint64_t>(maxOffset >= 0 ? maxOffset : -maxOffset),
        static_cast<uint32_t>(protMask),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefinePointers(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong targetAddress,
    jlong maxOffset,
    jint protMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refinePointers(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<uintptr_t>(targetAddress),
        static_cast<uint64_t>(maxOffset >= 0 ? maxOffset : -maxOffset),
        static_cast<uint32_t>(protMask),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchGroup(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jintArray typesJarr,
    jlongArray intValsJarr,
    jdoubleArray floatValsJarr,
    jlongArray intMaxValsJarr,
    jdoubleArray floatMaxValsJarr,
    jintArray fieldOpsJarr,
    jint windowSize,
    jboolean ordered,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !typesJarr || !intValsJarr || !floatValsJarr) {
        return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    }
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(typesJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);

    jint* typesPtr = env->GetIntArrayElements(typesJarr, nullptr);
    jlong* intPtr = env->GetLongArrayElements(intValsJarr, nullptr);
    jdouble* fltPtr = env->GetDoubleArrayElements(floatValsJarr, nullptr);
    jlong* intMaxPtr = intMaxValsJarr ? env->GetLongArrayElements(intMaxValsJarr, nullptr) : nullptr;
    jdouble* fltMaxPtr = floatMaxValsJarr ? env->GetDoubleArrayElements(floatMaxValsJarr, nullptr) : nullptr;
    jint* opsPtr = fieldOpsJarr ? env->GetIntArrayElements(fieldOpsJarr, nullptr) : nullptr;

    mem_engine::GroupSearchQuery query;
    query.fields.resize(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        query.fields[static_cast<size_t>(i)].type = static_cast<mem_engine::DataType>(typesPtr[i]);
        query.fields[static_cast<size_t>(i)].iVal = static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fVal = static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].iMax = intMaxPtr ? static_cast<int64_t>(intMaxPtr[i]) : static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fMax = fltMaxPtr ? static_cast<double>(fltMaxPtr[i]) : static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].op = opsPtr ? static_cast<uint8_t>(opsPtr[i]) : 0;
        query.fields[static_cast<size_t>(i)].epsilon = 1e-4;
        query.fields[static_cast<size_t>(i)].origIndex = static_cast<size_t>(i);
    }
    query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
    query.ordered = (ordered == JNI_TRUE);
    query.aligned = (aligned == JNI_TRUE);

    env->ReleaseIntArrayElements(typesJarr, typesPtr, 0);
    env->ReleaseLongArrayElements(intValsJarr, intPtr, 0);
    env->ReleaseDoubleArrayElements(floatValsJarr, fltPtr, 0);
    if (intMaxPtr) env->ReleaseLongArrayElements(intMaxValsJarr, intMaxPtr, 0);
    if (fltMaxPtr) env->ReleaseDoubleArrayElements(floatMaxValsJarr, fltMaxPtr, 0);
    if (opsPtr) env->ReleaseIntArrayElements(fieldOpsJarr, opsPtr, 0);

    session->cancelToken.reset();
    auto rc = session->scanner->searchGroup(query, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineGroup(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jintArray typesJarr,
    jlongArray intValsJarr,
    jdoubleArray floatValsJarr,
    jlongArray intMaxValsJarr,
    jdoubleArray floatMaxValsJarr,
    jintArray fieldOpsJarr,
    jint windowSize,
    jboolean ordered,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !typesJarr || !intValsJarr || !floatValsJarr) {
        return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    }
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(typesJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);

    jint* typesPtr = env->GetIntArrayElements(typesJarr, nullptr);
    jlong* intPtr = env->GetLongArrayElements(intValsJarr, nullptr);
    jdouble* fltPtr = env->GetDoubleArrayElements(floatValsJarr, nullptr);
    jlong* intMaxPtr = intMaxValsJarr ? env->GetLongArrayElements(intMaxValsJarr, nullptr) : nullptr;
    jdouble* fltMaxPtr = floatMaxValsJarr ? env->GetDoubleArrayElements(floatMaxValsJarr, nullptr) : nullptr;
    jint* opsPtr = fieldOpsJarr ? env->GetIntArrayElements(fieldOpsJarr, nullptr) : nullptr;

    mem_engine::GroupSearchQuery query;
    query.fields.resize(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        query.fields[static_cast<size_t>(i)].type = static_cast<mem_engine::DataType>(typesPtr[i]);
        query.fields[static_cast<size_t>(i)].iVal = static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fVal = static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].iMax = intMaxPtr ? static_cast<int64_t>(intMaxPtr[i]) : static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fMax = fltMaxPtr ? static_cast<double>(fltMaxPtr[i]) : static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].op = opsPtr ? static_cast<uint8_t>(opsPtr[i]) : 0;
        query.fields[static_cast<size_t>(i)].epsilon = 1e-4;
        query.fields[static_cast<size_t>(i)].origIndex = static_cast<size_t>(i);
    }
    query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
    query.ordered = (ordered == JNI_TRUE);
    query.aligned = (aligned == JNI_TRUE);

    env->ReleaseIntArrayElements(typesJarr, typesPtr, 0);
    env->ReleaseLongArrayElements(intValsJarr, intPtr, 0);
    env->ReleaseDoubleArrayElements(floatValsJarr, fltPtr, 0);
    if (intMaxPtr) env->ReleaseLongArrayElements(intMaxValsJarr, intMaxPtr, 0);
    if (fltMaxPtr) env->ReleaseDoubleArrayElements(floatMaxValsJarr, fltMaxPtr, 0);
    if (opsPtr) env->ReleaseIntArrayElements(fieldOpsJarr, opsPtr, 0);

    session->cancelToken.reset();
    auto rc = session->scanner->refineGroup(query, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchPattern(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jbyteArray patternJarr,
    jstring maskJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !patternJarr) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(patternJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    jbyte* ptr = env->GetByteArrayElements(patternJarr, nullptr);
    std::vector<uint8_t> pat(reinterpret_cast<uint8_t*>(ptr), reinterpret_cast<uint8_t*>(ptr) + len);
    env->ReleaseByteArrayElements(patternJarr, ptr, 0);

    std::vector<uint8_t> maskBytes(static_cast<size_t>(len), 0xFF);
    if (maskJstr != nullptr) {
        const char* cmask = env->GetStringUTFChars(maskJstr, nullptr);
        if (cmask && cmask[0] != '\0') {
            size_t mlen = std::min(static_cast<size_t>(len), std::strlen(cmask));
            for (size_t i = 0; i < mlen; ++i) {
                maskBytes[i] = (cmask[i] == '?' || cmask[i] == '.') ? 0x00 : 0xFF;
            }
        }
        if (cmask) env->ReleaseStringUTFChars(maskJstr, cmask);
    }

    session->cancelToken.reset();
    auto rc = session->scanner->searchBytePattern(pat, maskBytes, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchEncoded(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jint encodedMode,
    jlong logicalInt,
    jdouble logicalFloat,
    jlong keyOrMask,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchEncoded(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::EncodedMode>(encodedMode),
        static_cast<int64_t>(logicalInt),
        static_cast<double>(logicalFloat),
        static_cast<uint64_t>(keyOrMask),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineEncoded(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jint encodedMode,
    jlong logicalInt,
    jdouble logicalFloat,
    jlong keyOrMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineEncoded(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::EncodedMode>(encodedMode),
        static_cast<int64_t>(logicalInt),
        static_cast<double>(logicalFloat),
        static_cast<uint64_t>(keyOrMask),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeStartUnknownSnapshot(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->startUnknownSnapshot(
        static_cast<mem_engine::DataType>(dataType),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineFuzzy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint deltaOp,
    jlong intDelta,
    jdouble floatDelta,
    jdouble epsilon,
    jint onlyNth
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineFuzzy(
        static_cast<mem_engine::SearchOp>(deltaOp),
        static_cast<int64_t>(intDelta),
        static_cast<double>(floatDelta),
        static_cast<double>(epsilon),
        &session->cancelToken,
        nullptr,
        static_cast<uint32_t>(onlyNth > 0 ? onlyNth : 0)
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetCandidateCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);
    return static_cast<jlong>(session->scanner->totalMatchedCount());
}

JNIEXPORT jlongArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetCandidatesPacked(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint offset,
    jint limit
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || offset < 0 || limit <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<mem_engine::CandidateEntry> slice;
    session->scanner->fetchCandidates(static_cast<size_t>(offset), static_cast<size_t>(limit), slice);
    if (slice.empty()) return env->NewLongArray(0);

    size_t count = slice.size();
    std::vector<jlong> packed(count * 3);
    for (size_t i = 0; i < count; ++i) {
        const auto& c = slice[i];
        packed[i * 3 + 0] = static_cast<jlong>(c.address);
        packed[i * 3 + 1] = static_cast<jlong>(c.rawValue);
        packed[i * 3 + 2] = static_cast<jlong>(
            static_cast<uint64_t>(c.type) |
            (static_cast<uint64_t>(c.regionTag) << 8) |
            (static_cast<uint64_t>(c.flags) << 16)
        );
    }

    jlongArray arr = env->NewLongArray(static_cast<jsize>(count * 3));
    if (arr) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(count * 3), packed.data());
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRemoveCandidates(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlongArray addressesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !addressesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(addressesJarr);
    if (len <= 0) return;
    jlong* ptr = env->GetLongArrayElements(addressesJarr, nullptr);
    if (!ptr) return;

    session->scanner->removeCandidates(reinterpret_cast<const uint64_t*>(ptr), static_cast<size_t>(len));
    env->ReleaseLongArrayElements(addressesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeClearSession(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->clearSession();
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLockAddress(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return;

    session->locker->lockAddress(static_cast<uintptr_t>(address), ptr, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLockAddressConfigured(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr,
    jint dataType,
    jint freezeMode,
    jlong minVal,
    jlong maxVal
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return;

    session->locker->lockAddressConfigured(
        static_cast<uintptr_t>(address),
        ptr,
        static_cast<size_t>(len),
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::FreezeMode>(freezeMode),
        static_cast<int64_t>(minVal),
        static_cast<int64_t>(maxVal)
    );
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetFreezePaused(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jboolean paused
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->setPaused(paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetFreezeIntervalMs(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint intervalMs
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || intervalMs <= 0) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->setIntervalMs(static_cast<uint32_t>(intervalMs));
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeUnlockAddress(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong address
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAddress(static_cast<uintptr_t>(address));
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeUnlockAll(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAll();
}

// ============================================================================
// Hardware Breakpoint / Watchpoint JNI Bridge (Perf & Ptrace, supports No-Root)
// ============================================================================

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpAdd(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint pid,
    jlong address,
    jint bpType,
    jint bpSize,
    jint bpMode,
    jboolean captureCallstack
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return -1;
    int targetPid = (pid > 0) ? static_cast<int>(pid) : session->io.pid();
    return static_cast<jint>(session->debugger->addBreakpoint(
        targetPid,
        static_cast<uint64_t>(address),
        static_cast<mem_engine::BpType>(bpType),
        static_cast<int32_t>(bpSize),
        static_cast<mem_engine::BpMode>(bpMode),
        captureCallstack == JNI_TRUE,
        nullptr
    ));
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpRemove(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return JNI_FALSE;
    return session->debugger->removeBreakpoint(static_cast<int32_t>(bpId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpSetPaused(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId,
    jboolean paused
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return JNI_FALSE;
    if (bpId <= 0) {
        return session->debugger->setAllPaused(paused == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    }
    return session->debugger->setPaused(static_cast<int32_t>(bpId), paused == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpClearHits(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return;
    if (bpId <= 0) session->debugger->clearAllHits();
    else session->debugger->clearHits(static_cast<int32_t>(bpId));
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpStopAll(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return;
    session->debugger->stopAll();
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpSnapshotJson(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return env->NewStringUTF("[]");
    std::string json = session->debugger->snapshotsToJson();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpTraceJson(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint bpId,
    jlong sinceSeq,
    jint limit
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return env->NewStringUTF("[]");
    std::string json = session->debugger->traceEventsToJson(
        bpId,
        sinceSeq > 0 ? static_cast<uint64_t>(sinceSeq) : 0ULL,
        limit > 0 ? static_cast<size_t>(limit) : 120U
    );
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpClearTrace(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return;
    session->debugger->clearTrace(bpId);
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpSetReg(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint bpId,
    jint tid,
    jlong hitPc,
    jstring regNameJstr,
    jstring hexValJstr,
    jboolean isFp
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger || !regNameJstr || !hexValJstr) {
        return env->NewStringUTF("ERR:无效参数");
    }
    const char* rn = env->GetStringUTFChars(regNameJstr, nullptr);
    const char* hv = env->GetStringUTFChars(hexValJstr, nullptr);
    std::string regName = rn ? rn : "";
    std::string hexVal = hv ? hv : "";
    if (rn) env->ReleaseStringUTFChars(regNameJstr, rn);
    if (hv) env->ReleaseStringUTFChars(hexValJstr, hv);

    std::string err;
    bool ok = session->debugger->modifyRegister(
        static_cast<int32_t>(bpId),
        static_cast<int32_t>(tid),
        static_cast<uint64_t>(hitPc),
        regName,
        hexVal,
        isFp == JNI_TRUE,
        &err
    );
    if (ok) return env->NewStringUTF("OK");
    std::string out = "ERR:" + (err.empty() ? "寄存器写回失败" : err);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAllocTargetPage(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint pid,
    jlong hintAddr,
    jint size,
    jint prot
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    int targetPid = (pid > 0) ? static_cast<int>(pid) : (session ? session->io.pid() : 0);
    auto res = mem_engine::allocateTargetProcessPage(
        targetPid,
        static_cast<uint64_t>(hintAddr),
        static_cast<size_t>(size > 0 ? size : 4096),
        static_cast<int>(prot)
    );
    if (res.ok && session) {
        std::lock_guard<std::mutex> lk(session->mtx);
        session->scanner->refreshRegions();
        session->refreshAllRegions();
    }
    if (res.ok) {
        std::ostringstream oss;
        oss << "OK:" << std::hex << res.address << ":" << std::dec << res.size << ":" << res.actualProt;
        std::string s = oss.str();
        return env->NewStringUTF(s.c_str());
    }
    std::string out = "ERR:" + (res.error.empty() ? "ALLOC_FAILED" : res.error);
    return env->NewStringUTF(out.c_str());
}

// ============================================================================
// Dynamic Keystone & Capstone Assembly Engine JNI Bridge
// ============================================================================

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLoadAsmEngine(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring libDirJstr
) {
    std::string dir;
    if (libDirJstr) {
        const char* c = env->GetStringUTFChars(libDirJstr, nullptr);
        if (c) {
            dir = c;
            env->ReleaseStringUTFChars(libDirJstr, c);
        }
    }
    std::string err;
    bool ok = mem_engine::AsmEngine::instance().loadFromDir(dir, &err);
    if (ok) return env->NewStringUTF("OK");
    return env->NewStringUTF(err.empty() ? "LOAD_FAILED" : err.c_str());
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetAsmEngineFlags(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    int flags = 0;
    if (mem_engine::AsmEngine::instance().isKeystoneLoaded()) flags |= 1;
    if (mem_engine::AsmEngine::instance().isCapstoneLoaded()) flags |= 2;
    return static_cast<jint>(flags);
}

JNIEXPORT jbyteArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAssemble(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong address,
    jstring codeJstr
) {
    if (!codeJstr) return nullptr;
    const char* c = env->GetStringUTFChars(codeJstr, nullptr);
    std::string code = c ? c : "";
    if (c) env->ReleaseStringUTFChars(codeJstr, c);

    auto res = mem_engine::AsmEngine::instance().assemble(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(address),
        code
    );
    if (!res.ok || res.bytes.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(res.bytes.size()));
    if (arr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(res.bytes.size()), reinterpret_cast<const jbyte*>(res.bytes.data()));
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAssembleWithError(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong address,
    jstring codeJstr
) {
    if (!codeJstr) return env->NewStringUTF("ERR:汇编代码为空");
    const char* c = env->GetStringUTFChars(codeJstr, nullptr);
    std::string code = c ? c : "";
    if (c) env->ReleaseStringUTFChars(codeJstr, c);

    auto res = mem_engine::AsmEngine::instance().assemble(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(address),
        code
    );
    if (!res.ok) {
        std::string msg = "ERR:" + res.error;
        return env->NewStringUTF(msg.c_str());
    }
    static const char* kHex = "0123456789ABCDEF";
    std::string out = "OK:";
    out.reserve(3 + res.bytes.size() * 2);
    for (uint8_t b : res.bytes) {
        out.push_back(kHex[(b >> 4) & 0xF]);
        out.push_back(kHex[b & 0xF]);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDisassembleOne(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong address,
    jbyteArray bytesJarr
) {
    if (!bytesJarr) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(bytesJarr);
    if (len <= 0) return env->NewStringUTF("");
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return env->NewStringUTF("");
    std::string text = mem_engine::AsmEngine::instance().disassembleOne(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(address),
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len)
    );
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDisassembleBlock(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong baseAddress,
    jbyteArray bytesJarr,
    jint maxInsns
) {
    if (!bytesJarr) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(bytesJarr);
    if (len <= 0) return env->NewStringUTF("");
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return env->NewStringUTF("");
    auto insns = mem_engine::AsmEngine::instance().disassembleBlock(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(baseAddress),
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len),
        static_cast<size_t>(maxInsns > 0 ? maxInsns : 256)
    );
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);

    std::ostringstream oss;
    for (size_t i = 0; i < insns.size(); ++i) {
        if (i > 0) oss << '\n';
        oss << std::hex << insns[i].address << '|' << std::dec << insns[i].size << '|' << insns[i].text;
    }
    std::string out = oss.str();
    return env->NewStringUTF(out.c_str());
}

// ============================================================================
// ShadowHook + xDL Agent (Speedhack & Local Memory Page Allocation)
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAgentSetSpeed(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jfloat rate
) {
    auto& api = lazyAgentApi();
    if (!api.setSpeed) return JNI_FALSE;
    api.setSpeed(static_cast<float>(rate));
    return (api.isActive && api.isActive() != 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAgentGetSpeed(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    auto& api = lazyAgentApi();
    return api.getSpeed ? static_cast<jfloat>(api.getSpeed()) : 1.0f;
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAgentAllocPage(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong hintAddr,
    jint size,
    jint prot
) {
    auto& api = lazyAgentApi();
    if (!api.allocPage) return 0;
    return static_cast<jlong>(api.allocPage(
        static_cast<uint64_t>(hintAddr),
        static_cast<size_t>(size > 0 ? size : 4096),
        static_cast<int>(prot)
    ));
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeVerifyXdlAndHook(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    void* libc = xdl_open("libc.so", XDL_DEFAULT);
    if (!libc) return JNI_FALSE;
    void* sym = xdl_sym(libc, "clock_gettime", nullptr);
    xdl_close(libc);
    return sym != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeHookCommand(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint pid,
    jstring reqJsonJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    int targetPid = (pid > 0) ? static_cast<int>(pid) : (session ? session->io.pid() : ::getpid());
    if (!reqJsonJstr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"Empty hook command request\"}");
    }
    const char* cReq = env->GetStringUTFChars(reqJsonJstr, nullptr);
    std::string reqJson = cReq ? cReq : "";
    if (cReq) env->ReleaseStringUTFChars(reqJsonJstr, cReq);

    std::string respJson;
    std::string errMsg;
    if (mem_engine::sendHookAgentCommand(targetPid, reqJson, &respJson, &errMsg)) {
        return env->NewStringUTF(respJson.c_str());
    }
    std::string safeErr;
    safeErr.reserve(errMsg.size() + 32);
    safeErr = "{\"ok\":false,\"error\":\"";
    for (unsigned char c : errMsg) {
        if (c == '"') safeErr += "\\\"";
        else if (c == '\\') safeErr += "\\\\";
        else if (c == '\n' || c == '\r') safeErr.push_back(' ');
        else if (c >= 0x20) safeErr.push_back(static_cast<char>(c));
    }
    safeErr += "\"}";
    return env->NewStringUTF(safeErr.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeElfExportsJson(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint pid,
    jstring libNameJstr,
    jstring filterJstr,
    jint maxCount
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    int targetPid = (pid > 0) ? static_cast<int>(pid) : (session ? session->io.pid() : ::getpid());
    if (!libNameJstr) return env->NewStringUTF("[]");

    const char* cLib = env->GetStringUTFChars(libNameJstr, nullptr);
    std::string libName = cLib ? cLib : "";
    if (cLib) env->ReleaseStringUTFChars(libNameJstr, cLib);

    std::string filter;
    if (filterJstr) {
        const char* cF = env->GetStringUTFChars(filterJstr, nullptr);
        if (cF) filter = cF;
        if (cF) env->ReleaseStringUTFChars(filterJstr, cF);
    }

    size_t limit = maxCount > 0 ? static_cast<size_t>(maxCount) : 500U;
    auto syms = mem_engine::listProcessModuleExports(targetPid, libName, filter, limit);
    std::string json = mem_engine::elfSymbolsToJson(syms);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeFindSymbol(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint pid,
    jstring libNameJstr,
    jstring symNameJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    int targetPid = (pid > 0) ? static_cast<int>(pid) : (session ? session->io.pid() : ::getpid());
    if (!libNameJstr || !symNameJstr) return 0;

    const char* cLib = env->GetStringUTFChars(libNameJstr, nullptr);
    const char* cSym = env->GetStringUTFChars(symNameJstr, nullptr);
    std::string libName = cLib ? cLib : "";
    std::string symName = cSym ? cSym : "";
    if (cLib) env->ReleaseStringUTFChars(libNameJstr, cLib);
    if (cSym) env->ReleaseStringUTFChars(symNameJstr, cSym);

    uint64_t addr = mem_engine::findProcessSymbolAddress(targetPid, libName, symName);
    return static_cast<jlong>(addr);
}

} // extern "C"

// ============================================================================
// Embedded Lua 5.5.1 VM & Native C++ Plugin Host ABI v1 JNI Bridge
// ============================================================================

namespace {

std::string jsonEscapeSimple(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    return out;
}

std::string formatLuaRunResultJson(const mem_engine::LuaRunResult& r) {
    std::ostringstream oss;
    oss << "{\"ok\":" << (r.ok ? "true" : "false")
        << ",\"cancelled\":" << (r.cancelled ? "true" : "false")
        << ",\"exited\":" << (r.exited ? "true" : "false")
        << ",\"exitCode\":" << r.exitCode
        << ",\"error\":\"" << jsonEscapeSimple(r.error) << "\"}";
    return oss.str();
}

std::string extractJsonStringField(const std::string& json, const char* key, const std::string& defVal = "") {
    std::string needle = std::string("\"") + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return defVal;
    pos = json.find(':', pos + needle.size());
    if (pos == std::string::npos) return defVal;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t' || json[pos] == '\n' || json[pos] == '\r')) ++pos;
    if (pos >= json.size() || json[pos] != '"') return defVal;
    ++pos;
    std::string out;
    while (pos < json.size() && json[pos] != '"') {
        if (json[pos] == '\\' && pos + 1 < json.size()) {
            ++pos;
            switch (json[pos]) {
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                default:  out.push_back(json[pos]); break;
            }
        } else {
            out.push_back(json[pos]);
        }
        ++pos;
    }
    return out;
}

int32_t extractJsonIntField(const std::string& json, const char* key, int32_t defVal) {
    std::string needle = std::string("\"") + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return defVal;
    pos = json.find(':', pos + needle.size());
    if (pos == std::string::npos) return defVal;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) ++pos;
    if (pos >= json.size()) return defVal;
    return static_cast<int32_t>(std::atoi(json.c_str() + pos));
}

struct ScopedJniAttach {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;

    explicit ScopedJniAttach(JavaVM* javaVm) : vm(javaVm) {
        if (!vm) return;
        jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
        }
    }

    ~ScopedJniAttach() {
        if (attached && vm) {
            vm->DetachCurrentThread();
        }
    }
};

struct NativeLuaSession {
    NativeEngineSession* engineSession = nullptr;
    JavaVM*              jvm = nullptr;
    jobject              callbackObj = nullptr;
    jmethodID            onHostCallMid = nullptr;
    jmethodID            onPrintMid = nullptr;
    GGNextHostApiV1      hostApi{};
    std::unique_ptr<mem_engine::LuaEngine> lua;

    std::string callKotlinHost(const std::string& method, const std::string& argsJson) {
        if (!jvm || !callbackObj || !onHostCallMid) {
            return "{\"ok\":false,\"error\":\"JNI Lua callback unavailable\"}";
        }
        ScopedJniAttach guard(jvm);
        JNIEnv* env = guard.env;
        if (!env) {
            return "{\"ok\":false,\"error\":\"JNIEnv attach failed\"}";
        }
        jstring jMethod = env->NewStringUTF(method.c_str());
        jstring jArgs   = env->NewStringUTF(argsJson.c_str());
        auto jRes = static_cast<jstring>(env->CallObjectMethod(callbackObj, onHostCallMid, jMethod, jArgs));
        if (jMethod) env->DeleteLocalRef(jMethod);
        if (jArgs) env->DeleteLocalRef(jArgs);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (jRes) env->DeleteLocalRef(jRes);
            return "{\"ok\":false,\"error\":\"Kotlin host call threw an exception\"}";
        }
        if (!jRes) {
            return "{\"ok\":true,\"value\":null}";
        }
        const char* cRes = env->GetStringUTFChars(jRes, nullptr);
        std::string out = cRes ? cRes : "";
        if (cRes) env->ReleaseStringUTFChars(jRes, cRes);
        env->DeleteLocalRef(jRes);
        return out;
    }

    void callKotlinPrint(const std::string& line) {
        if (!jvm || !callbackObj || !onPrintMid) return;
        ScopedJniAttach guard(jvm);
        JNIEnv* env = guard.env;
        if (!env) return;
        jstring jLine = env->NewStringUTF(line.c_str());
        env->CallVoidMethod(callbackObj, onPrintMid, jLine);
        if (jLine) env->DeleteLocalRef(jLine);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    // ---- GGNextHostApiV1 static C thunks (real C++20 engine wiring) ----
    static int32_t apiGetTargetPid(void* ctx) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        return (self && self->engineSession) ? self->engineSession->io.pid() : -1;
    }

    static int32_t apiIsTarget64Bit(void* ctx) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        return (self && self->engineSession && self->engineSession->io.targetIs64Bit()) ? 1 : 0;
    }

    static int64_t apiReadMemory(void* ctx, uint64_t address, void* outBuf, size_t size) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !self->engineSession || !outBuf || size == 0) return -1;
        size_t bytesRead = 0;
        auto rc = self->engineSession->io.readMemory(static_cast<uintptr_t>(address), outBuf, size, &bytesRead);
        return (rc == mem_engine::EngineErrc::OK) ? static_cast<int64_t>(bytesRead) : -1;
    }

    static int32_t apiWriteMemory(void* ctx, uint64_t address, const void* inBuf, size_t size) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !self->engineSession || !inBuf || size == 0) return -1;
        auto rc = self->engineSession->io.writeMemory(static_cast<uintptr_t>(address), inBuf, size);
        return (rc == mem_engine::EngineErrc::OK) ? 0 : static_cast<int32_t>(rc);
    }

    static int32_t apiListRegions(void* ctx, GGNextMemoryRegionC* outRegions, size_t maxCount) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !self->engineSession) return -1;
        std::lock_guard<std::mutex> lk(self->engineSession->mtx);
        if (self->engineSession->cachedAllRegions.empty() && self->engineSession->io.pid() > 0) {
            self->engineSession->refreshAllRegions();
        }
        const auto& regs = self->engineSession->cachedAllRegions;
        if (!outRegions || maxCount == 0) {
            return static_cast<int32_t>(regs.size());
        }
        size_t n = std::min(maxCount, regs.size());
        for (size_t i = 0; i < n; ++i) {
            const auto& r = regs[i];
            outRegions[i].start = static_cast<uint64_t>(r.startAddress);
            outRegions[i].end   = static_cast<uint64_t>(r.endAddress);
            outRegions[i].perms = (r.readable ? 1u : 0u) | (r.writable ? 2u : 0u) | (r.executable ? 4u : 0u);
            outRegions[i].region_flag = r.regionFlag;
            std::strncpy(outRegions[i].code, mem_engine::regionFlagToTag(r.regionFlag), sizeof(outRegions[i].code) - 1);
            outRegions[i].code[sizeof(outRegions[i].code) - 1] = '\0';
            std::strncpy(outRegions[i].path, r.pathname.c_str(), sizeof(outRegions[i].path) - 1);
            outRegions[i].path[sizeof(outRegions[i].path) - 1] = '\0';
        }
        return static_cast<int32_t>(n);
    }

    static uint64_t apiAllocatePage(void* ctx, uint64_t hintAddr, size_t size, int32_t prot) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !self->engineSession) return 0;
        int pid = self->engineSession->io.pid();
        if (pid <= 0) return 0;
        auto res = mem_engine::allocateTargetProcessPage(pid, hintAddr, size > 0 ? size : 4096, prot);
        if (res.ok) {
            std::lock_guard<std::mutex> lk(self->engineSession->mtx);
            self->engineSession->scanner->refreshRegions();
            self->engineSession->refreshAllRegions();
            return res.address;
        }
        return 0;
    }

    static int32_t apiAssemble(void* /*ctx*/, int32_t arch, uint64_t address, const char* code,
                               uint8_t* outBytes, size_t maxBytes) {
        if (!code || !outBytes || maxBytes == 0) return -1;
        auto res = mem_engine::AsmEngine::instance().assemble(
            static_cast<mem_engine::AsmArch>(arch), address, code
        );
        if (!res.ok || res.bytes.size() > maxBytes) return -1;
        std::memcpy(outBytes, res.bytes.data(), res.bytes.size());
        return static_cast<int32_t>(res.bytes.size());
    }

    static int32_t apiDisassembleOne(void* /*ctx*/, int32_t arch, uint64_t address,
                                     const uint8_t* bytes, size_t len,
                                     char* outBuf, size_t outCap) {
        if (!bytes || len == 0 || !outBuf || outCap == 0) return -1;
        std::string text = mem_engine::AsmEngine::instance().disassembleOne(
            static_cast<mem_engine::AsmArch>(arch), address, bytes, len
        );
        std::strncpy(outBuf, text.c_str(), outCap - 1);
        outBuf[outCap - 1] = '\0';
        return static_cast<int32_t>(text.size());
    }

    static int32_t apiBpAdd(void* ctx, uint64_t address, int32_t bpType, int32_t bpSize, int32_t bpMode) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !self->engineSession || !self->engineSession->debugger) return -1;
        int pid = self->engineSession->io.pid();
        if (pid <= 0) return -1;
        return self->engineSession->debugger->addBreakpoint(
            pid,
            address,
            static_cast<mem_engine::BpType>(bpType),
            bpSize,
            static_cast<mem_engine::BpMode>(bpMode),
            false,
            nullptr
        );
    }

    static int32_t apiBpRemove(void* ctx, int32_t bpId) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !self->engineSession || !self->engineSession->debugger) return 0;
        return self->engineSession->debugger->removeBreakpoint(bpId) ? 1 : 0;
    }

    static void apiLogMessage(void* ctx, int32_t /*level*/, const char* tag, const char* msg) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self) return;
        std::string line = "[";
        line += (tag ? tag : "plugin");
        line += "] ";
        line += (msg ? msg : "");
        self->callKotlinPrint(line);
    }

    static int32_t apiEmitLuaEvent(void* ctx, const char* channel, const char* payloadJson) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self) return -1;
        std::string args = "[\"";
        args += jsonEscapeSimple(channel ? channel : "");
        args += "\",";
        args += (payloadJson && payloadJson[0] != '\0') ? payloadJson : "null";
        args += "]";
        self->callKotlinHost("plugin.emitEvent", args);
        return 0;
    }

    static int32_t apiInvokeHostJson(void* ctx, const char* method, const char* argsJson,
                                     char* outBuf, size_t outCap) {
        auto* self = static_cast<NativeLuaSession*>(ctx);
        if (!self || !method || !outBuf || outCap == 0) return -1;
        std::string resp = self->callKotlinHost(method, argsJson ? argsJson : "[]");
        std::strncpy(outBuf, resp.c_str(), outCap - 1);
        outBuf[outCap - 1] = '\0';
        return static_cast<int32_t>(resp.size());
    }

    void initHostApi() {
        hostApi.abi_version     = GGNEXT_HOST_API_VERSION;
        hostApi.struct_size     = static_cast<uint32_t>(sizeof(GGNextHostApiV1));
        hostApi.user_data       = this;
        hostApi.get_target_pid  = &NativeLuaSession::apiGetTargetPid;
        hostApi.is_target_64bit = &NativeLuaSession::apiIsTarget64Bit;
        hostApi.read_memory     = &NativeLuaSession::apiReadMemory;
        hostApi.write_memory    = &NativeLuaSession::apiWriteMemory;
        hostApi.list_regions    = &NativeLuaSession::apiListRegions;
        hostApi.allocate_page   = &NativeLuaSession::apiAllocatePage;
        hostApi.assemble        = &NativeLuaSession::apiAssemble;
        hostApi.disassemble_one = &NativeLuaSession::apiDisassembleOne;
        hostApi.bp_add          = &NativeLuaSession::apiBpAdd;
        hostApi.bp_remove       = &NativeLuaSession::apiBpRemove;
        hostApi.log_message     = &NativeLuaSession::apiLogMessage;
        hostApi.emit_lua_event  = &NativeLuaSession::apiEmitLuaEvent;
        hostApi.invoke_host_json = &NativeLuaSession::apiInvokeHostJson;
    }
};

} // anonymous namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaCreate(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong engineHandle,
    jobject callbackObj,
    jstring propsJsonJstr
) {
    if (!callbackObj) return 0;
    auto* ctx = new NativeLuaSession();
    ctx->engineSession = reinterpret_cast<NativeEngineSession*>(engineHandle);
    env->GetJavaVM(&ctx->jvm);
    ctx->callbackObj = env->NewGlobalRef(callbackObj);
    jclass cbClass = env->GetObjectClass(callbackObj);
    if (cbClass) {
        ctx->onHostCallMid = env->GetMethodID(cbClass, "onHostCall", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        ctx->onPrintMid    = env->GetMethodID(cbClass, "onPrint", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cbClass);
    }

    mem_engine::LuaRuntimeProps props;
    if (propsJsonJstr) {
        const char* pStr = env->GetStringUTFChars(propsJsonJstr, nullptr);
        if (pStr) {
            std::string pj(pStr);
            env->ReleaseStringUTFChars(propsJsonJstr, pStr);
            props.androidSdkInt = extractJsonStringField(pj, "androidSdkInt", props.androidSdkInt);
            props.packageName   = extractJsonStringField(pj, "packageName", props.packageName);
            props.version       = extractJsonStringField(pj, "version", props.version);
            props.versionInt    = extractJsonIntField(pj, "versionInt", props.versionInt);
            props.build         = extractJsonIntField(pj, "build", props.build);
            props.filesDir      = extractJsonStringField(pj, "filesDir", props.filesDir);
            props.cacheDir      = extractJsonStringField(pj, "cacheDir", props.cacheDir);
            props.extFilesDir   = extractJsonStringField(pj, "extFilesDir", props.extFilesDir);
            props.extCacheDir   = extractJsonStringField(pj, "extCacheDir", props.extCacheDir);
            props.extStorage    = extractJsonStringField(pj, "extStorage", props.extStorage);
            props.pluginDir     = extractJsonStringField(pj, "pluginDir", props.pluginDir);
        }
    }

    ctx->initHostApi();
    ctx->lua = std::make_unique<mem_engine::LuaEngine>();
    ctx->lua->setRuntimeProps(props);
    ctx->lua->setPluginHostApi(&ctx->hostApi);
    ctx->lua->setHostCallback([ctx](const std::string& m, const std::string& a) {
        return ctx->callKotlinHost(m, a);
    });
    ctx->lua->setPrintCallback([ctx](const std::string& line) {
        ctx->callKotlinPrint(line);
    });
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaDestroy(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong luaHandle
) {
    auto* ctx = reinterpret_cast<NativeLuaSession*>(luaHandle);
    if (!ctx) return;
    ctx->lua.reset();
    if (ctx->callbackObj && env) {
        env->DeleteGlobalRef(ctx->callbackObj);
        ctx->callbackObj = nullptr;
    }
    delete ctx;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaRunFile(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong luaHandle,
    jstring scriptPathJstr
) {
    auto* ctx = reinterpret_cast<NativeLuaSession*>(luaHandle);
    if (!ctx || !ctx->lua || !scriptPathJstr) {
        return env->NewStringUTF("{\"ok\":false,\"cancelled\":false,\"exited\":false,\"exitCode\":0,\"error\":\"Invalid Lua handle\"}");
    }
    const char* p = env->GetStringUTFChars(scriptPathJstr, nullptr);
    std::string path = p ? p : "";
    if (p) env->ReleaseStringUTFChars(scriptPathJstr, p);

    auto res = ctx->lua->runFile(path);
    std::string json = formatLuaRunResultJson(res);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaRunString(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong luaHandle,
    jstring sourceJstr,
    jstring chunkNameJstr
) {
    auto* ctx = reinterpret_cast<NativeLuaSession*>(luaHandle);
    if (!ctx || !ctx->lua || !sourceJstr) {
        return env->NewStringUTF("{\"ok\":false,\"cancelled\":false,\"exited\":false,\"exitCode\":0,\"error\":\"Invalid Lua handle\"}");
    }
    const char* s = env->GetStringUTFChars(sourceJstr, nullptr);
    std::string src = s ? s : "";
    if (s) env->ReleaseStringUTFChars(sourceJstr, s);

    std::string name = "script.lua";
    if (chunkNameJstr) {
        const char* n = env->GetStringUTFChars(chunkNameJstr, nullptr);
        if (n) {
            name = n;
            env->ReleaseStringUTFChars(chunkNameJstr, n);
        }
    }

    auto res = ctx->lua->runString(src, name);
    std::string json = formatLuaRunResultJson(res);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaCancel(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong luaHandle
) {
    auto* ctx = reinterpret_cast<NativeLuaSession*>(luaHandle);
    if (ctx && ctx->lua) {
        ctx->lua->requestCancel();
    }
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaLoadNativePlugin(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong luaHandle,
    jstring modNameJstr,
    jstring soPathJstr
) {
    auto* ctx = reinterpret_cast<NativeLuaSession*>(luaHandle);
    if (!ctx || !ctx->lua || !modNameJstr || !soPathJstr) {
        return env->NewStringUTF("ERR:Invalid arguments");
    }
    const char* m = env->GetStringUTFChars(modNameJstr, nullptr);
    const char* s = env->GetStringUTFChars(soPathJstr, nullptr);
    std::string modName = m ? m : "";
    std::string soPath  = s ? s : "";
    if (m) env->ReleaseStringUTFChars(modNameJstr, m);
    if (s) env->ReleaseStringUTFChars(soPathJstr, s);

    std::string err;
    if (ctx->lua->loadNativePlugin(modName, soPath, &err)) {
        return env->NewStringUTF("OK");
    }
    std::string out = "ERR:" + err;
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLuaRegisterPreload(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong luaHandle,
    jstring modNameJstr,
    jstring sourceJstr
) {
    auto* ctx = reinterpret_cast<NativeLuaSession*>(luaHandle);
    if (!ctx || !ctx->lua || !modNameJstr || !sourceJstr) return JNI_FALSE;
    const char* m = env->GetStringUTFChars(modNameJstr, nullptr);
    const char* s = env->GetStringUTFChars(sourceJstr, nullptr);
    std::string modName = m ? m : "";
    std::string src     = s ? s : "";
    if (m) env->ReleaseStringUTFChars(modNameJstr, m);
    if (s) env->ReleaseStringUTFChars(sourceJstr, s);

    std::string err;
    return ctx->lua->registerPreloadModule(modName, src, &err) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
