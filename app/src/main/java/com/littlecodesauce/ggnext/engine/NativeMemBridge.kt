package com.littlecodesauce.ggnext.engine

import android.content.Context
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.ValueType
import java.io.File

/**
 * C++20 MemEngine JNI 桥接层 (libmem_engine_jni.so)
 *
 * 将 GGNext 的上层 UI 状态机与底层 C++20 跨进程内存检索引擎深度集成：
 * - process_vm_readv / process_vm_writev 零拷贝跨进程读写 + /proc/[pid]/mem 自动回退
 * - 16KB / 4KB 动态页对齐 (sysconf(_SC_PAGESIZE)) 与 Adaptive Normal<->Page 自适应容错读取
 * - 15 类细分内存区域 (Ch, Jh, Ca, Cd, Cb, Ps, A, J, S, As, V, O, B, Xa, Xs) 原生解析与 VmaIndex 索引
 * - ARM64 NEON SIMD 向量化扫描、原生指针链扫描与 MmapCandidateStore 零 OOM 磁盘溢出存储
 * - ValueLocker 独立高精度定时锁值线程（原生支持普通冻结/递增/递减/范围钳制）
 */
object NativeMemBridge {

    // C++ DataType 枚举映射
    const val DT_INT8 = 0
    const val DT_INT16 = 1
    const val DT_INT32 = 2
    const val DT_INT64 = 3
    const val DT_FLOAT = 4
    const val DT_DOUBLE = 5
    const val DT_BYTE_ARRAY = 6
    const val DT_UTF8 = 7
    const val DT_UTF16 = 8
    const val DT_AUTO = 9

    // C++ SearchOp 枚举映射
    const val OP_EXACT = 0
    const val OP_NOT_EQUAL = 1
    const val OP_GREATER = 2
    const val OP_GREATER_EQ = 3
    const val OP_LESS = 4
    const val OP_LESS_EQ = 5
    const val OP_RANGE_INC = 6
    const val OP_RANGE_EXC = 7
    const val OP_UNKNOWN_INIT = 8
    const val OP_INCREASED = 9
    const val OP_DECREASED = 10
    const val OP_CHANGED = 11
    const val OP_UNCHANGED = 12
    const val OP_DELTA_EXACT = 13

    // C++ EncodedMode 枚举映射
    const val ENC_OFFSET_ADDED = 0
    const val ENC_XOR_STATIC = 1
    const val ENC_XOR_PAIR_32 = 2
    const val ENC_XOR_PAIR_64 = 3
    const val ENC_XOR_ADDRESS = 4
    const val ENC_GG_AUTO = 5
    const val ENC_XOR_OFFSET = 6

    // C++ PointerProtMask 位掩码
    const val PTR_PROT_WX = 1
    const val PTR_PROT_X = 2
    const val PTR_PROT_RO = 8
    const val PTR_PROT_W = 16
    const val PTR_PROT_ALL = PTR_PROT_WX or PTR_PROT_X or PTR_PROT_RO or PTR_PROT_W

    // C++ RegionFlag 位掩码
    private val REGION_FLAG_MAP = mapOf(
        "Jh" to (1 shl 0),
        "Ch" to (1 shl 1),
        "Ca" to (1 shl 2),
        "Cd" to (1 shl 3),
        "Cb" to (1 shl 4),
        "A"  to (1 shl 5),
        "S"  to (1 shl 6),
        "As" to (1 shl 7),
        "Xa" to (1 shl 8),
        "Xs" to (1 shl 9),
        "V"  to (1 shl 10),
        "Ps" to (1 shl 11),
        "B"  to (1 shl 12),
        "O"  to (1 shl 13),
        "J"  to (1 shl 14)
    )

    @Volatile
    private var _isLoaded: Boolean = false

    @Volatile
    var loadError: String = ""
        private set

    val isLoaded: Boolean
        get() = _isLoaded || ensureLoaded(null)

    init {
        ensureLoaded(null)
    }

    @Synchronized
    fun ensureLoaded(context: Context? = null): Boolean {
        if (_isLoaded) return true
        var lastErr: Throwable? = null
        try {
            System.loadLibrary("mem_engine_jni")
            _isLoaded = true
            loadError = ""
            AppLog.i("NativeMemBridge", "C++20 libmem_engine_jni.so loaded successfully")
            return true
        } catch (t: Throwable) {
            lastErr = t
            AppLog.w("NativeMemBridge", "System.loadLibrary(mem_engine_jni) failed: ${t.message}")
        }
        val nativeDir = context?.applicationInfo?.nativeLibraryDir
        if (!nativeDir.isNullOrBlank()) {
            val soFile = File(nativeDir, "libmem_engine_jni.so")
            if (soFile.exists()) {
                try {
                    System.load(soFile.absolutePath)
                    _isLoaded = true
                    loadError = ""
                    AppLog.i("NativeMemBridge", "Loaded libmem_engine_jni.so via ${soFile.absolutePath}")
                    return true
                } catch (t2: Throwable) {
                    lastErr = t2
                    AppLog.e("NativeMemBridge", "System.load(${soFile.absolutePath}) failed", t2)
                }
            }
        }
        loadError = lastErr?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        return false
    }

    @Volatile
    private var sessionHandle: Long = 0L

    @Synchronized
    fun ensureHandle(spillDir: String = "/data/local/tmp"): Long {
        if (!ensureLoaded(null)) return 0L
        if (sessionHandle == 0L) {
            val dir = File(spillDir).takeIf { it.exists() && it.canWrite() }?.absolutePath
                ?: System.getProperty("java.io.tmpdir")
                ?: "/data/local/tmp"
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            sessionHandle = nativeCreate(threads, dir)
        }
        return sessionHandle
    }

    @Synchronized
    fun releaseHandle() {
        val h = sessionHandle
        if (h != 0L && isLoaded) {
            sessionHandle = 0L
            runCatching { nativeDestroy(h) }
        }
    }

    fun currentHandle(): Long = sessionHandle

    fun toNativeDataType(ggType: Int): Int = when (ggType and 0x7F) {
        ValueType.BYTE -> DT_INT8
        ValueType.WORD -> DT_INT16
        ValueType.DWORD -> DT_INT32
        ValueType.QWORD -> DT_INT64
        ValueType.FLOAT -> DT_FLOAT
        ValueType.DOUBLE -> DT_DOUBLE
        ValueType.XOR -> DT_INT32
        ValueType.AUTO -> DT_AUTO
        else -> DT_INT32
    }

    fun fromNativeDataType(nativeDt: Int): Int = when (nativeDt) {
        DT_INT8 -> ValueType.BYTE
        DT_INT16 -> ValueType.WORD
        DT_INT32 -> ValueType.DWORD
        DT_INT64 -> ValueType.QWORD
        DT_FLOAT -> ValueType.FLOAT
        DT_DOUBLE -> ValueType.DOUBLE
        else -> ValueType.DWORD
    }

    fun fromNativeRegionFlag(flag: Int): String = when (flag) {
        1 shl 0 -> "Jh"
        1 shl 1 -> "Ch"
        1 shl 2 -> "Ca"
        1 shl 3 -> "Cd"
        1 shl 4 -> "Cb"
        1 shl 5 -> "A"
        1 shl 6 -> "S"
        1 shl 7 -> "As"
        1 shl 8 -> "Xa"
        1 shl 9 -> "Xs"
        1 shl 10 -> "V"
        1 shl 11 -> "Ps"
        1 shl 12 -> "B"
        1 shl 13 -> "O"
        1 shl 14 -> "J"
        else -> "O"
    }

    fun buildRegionMask(codes: Set<String>): Int {
        var mask = 0
        for (c in codes) {
            mask = mask or (REGION_FLAG_MAP[c] ?: 0)
        }
        return mask
    }

    @JvmStatic external fun nativeCreate(workerThreads: Int, spillDir: String): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeAttach(handle: Long, pid: Int): Int
    @JvmStatic external fun nativeIsProcessAlive(handle: Long): Boolean
    @JvmStatic external fun nativeSetRegionMask(handle: Long, regionMask: Int)
    @JvmStatic external fun nativeSetAddressFilter(handle: Long, minAddr: Long, maxAddr: Long, addrMask: Long, addrPattern: Long)
    @JvmStatic external fun nativeCancelScan(handle: Long)
    @JvmStatic external fun nativeGetRegionTotals(handle: Long, pid: Int): LongArray?
    @JvmStatic external fun nativeGetRegionCodeAt(handle: Long, address: Long): String?
    @JvmStatic external fun nativeClassifyPointer(handle: Long, ptrValue: Long): Int
    @JvmStatic external fun nativeReadBlock(handle: Long, address: Long, size: Int): ByteArray?
    @JvmStatic external fun nativeWriteBlock(handle: Long, address: Long, data: ByteArray): Boolean
    @JvmStatic external fun nativeCopyMemory(handle: Long, fromAddr: Long, toAddr: Long, length: Long): Long
    @JvmStatic external fun nativeDumpMemory(handle: Long, startAddr: Long, endAddr: Long, outFilePath: String): Long
    @JvmStatic external fun nativeSearchExact(
        handle: Long,
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean
    ): Int
    @JvmStatic external fun nativeRefineExact(
        handle: Long,
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double
    ): Int
    @JvmStatic external fun nativeSearchNotEqual(
        handle: Long,
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean
    ): Int
    @JvmStatic external fun nativeRefineNotEqual(
        handle: Long,
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double
    ): Int
    @JvmStatic external fun nativeSearchRange(
        handle: Long,
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        aligned: Boolean,
        excludeRange: Boolean = false
    ): Int
    @JvmStatic external fun nativeRefineRange(
        handle: Long,
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        excludeRange: Boolean = false
    ): Int
    @JvmStatic external fun nativeSearchPointers(
        handle: Long,
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int,
        aligned: Boolean
    ): Int
    @JvmStatic external fun nativeRefinePointers(
        handle: Long,
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int
    ): Int
    @JvmStatic external fun nativeSearchGroup(
        handle: Long,
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean
    ): Int
    @JvmStatic external fun nativeRefineGroup(
        handle: Long,
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean
    ): Int
    @JvmStatic external fun nativeSearchPattern(
        handle: Long,
        pattern: ByteArray,
        mask: String
    ): Int
    @JvmStatic external fun nativeSearchEncoded(
        handle: Long,
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        aligned: Boolean
    ): Int
    @JvmStatic external fun nativeRefineEncoded(
        handle: Long,
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long
    ): Int
    @JvmStatic external fun nativeStartUnknownSnapshot(handle: Long, dataType: Int): Int
    @JvmStatic external fun nativeRefineFuzzy(
        handle: Long,
        deltaOp: Int,
        intDelta: Long,
        floatDelta: Double,
        epsilon: Double,
        onlyNth: Int = 0
    ): Int
    @JvmStatic external fun nativeGetCandidateCount(handle: Long): Long
    @JvmStatic external fun nativeGetCandidatesPacked(handle: Long, offset: Int, limit: Int): LongArray?
    @JvmStatic external fun nativeRemoveCandidates(handle: Long, addresses: LongArray)
    @JvmStatic external fun nativeClearSession(handle: Long)
    @JvmStatic external fun nativeLockAddress(handle: Long, address: Long, data: ByteArray)
    @JvmStatic external fun nativeLockAddressConfigured(
        handle: Long,
        address: Long,
        data: ByteArray,
        dataType: Int,
        freezeMode: Int,
        minVal: Long,
        maxVal: Long
    )
    @JvmStatic external fun nativeSetFreezePaused(handle: Long, paused: Boolean)
    @JvmStatic external fun nativeSetFreezeIntervalMs(handle: Long, intervalMs: Int)
    @JvmStatic external fun nativeUnlockAddress(handle: Long, address: Long)
    @JvmStatic external fun nativeUnlockAll(handle: Long)

    // Hardware Breakpoints (Perf & Ptrace)
    @JvmStatic external fun nativeBpAdd(
        handle: Long,
        pid: Int,
        address: Long,
        bpType: Int,
        bpSize: Int,
        bpMode: Int,
        captureCallstack: Boolean
    ): Int
    @JvmStatic external fun nativeBpRemove(handle: Long, bpId: Int): Boolean
    @JvmStatic external fun nativeBpSetPaused(handle: Long, bpId: Int, paused: Boolean): Boolean
    @JvmStatic external fun nativeBpClearHits(handle: Long, bpId: Int)
    @JvmStatic external fun nativeBpStopAll(handle: Long)
    @JvmStatic external fun nativeBpSnapshotJson(handle: Long): String
    @JvmStatic external fun nativeBpTraceJson(handle: Long, bpId: Int, sinceSeq: Long, limit: Int): String
    @JvmStatic external fun nativeBpClearTrace(handle: Long, bpId: Int)
    @JvmStatic external fun nativeBpSetReg(
        handle: Long,
        bpId: Int,
        tid: Int,
        hitPc: Long,
        regName: String,
        hexValue: String,
        isFp: Boolean
    ): String

    // Target-process mmap page allocation + /proc/maps & write verification
    @JvmStatic external fun nativeAllocTargetPage(
        handle: Long,
        pid: Int,
        hintAddr: Long,
        size: Int,
        prot: Int
    ): String

    // Dynamic Keystone & Capstone Assembly Engine
    @JvmStatic external fun nativeLoadAsmEngine(libDir: String): String
    @JvmStatic external fun nativeGetAsmEngineFlags(): Int
    @JvmStatic external fun nativeAssemble(arch: Int, address: Long, code: String): ByteArray?
    @JvmStatic external fun nativeAssembleWithError(arch: Int, address: Long, code: String): String
    @JvmStatic external fun nativeDisassembleOne(arch: Int, address: Long, bytes: ByteArray): String
    @JvmStatic external fun nativeDisassembleBlock(arch: Int, baseAddress: Long, bytes: ByteArray, maxInsns: Int): String

    // ShadowHook + xDL Agent (Speedhack, Local Page Allocation, Hook/Active-Call IPC & ELF Symbol Resolution)
    @JvmStatic external fun nativeAgentSetSpeed(rate: Float): Boolean
    @JvmStatic external fun nativeAgentGetSpeed(): Float
    @JvmStatic external fun nativeAgentAllocPage(hintAddr: Long, size: Int, prot: Int): Long
    @JvmStatic external fun nativeVerifyXdlAndHook(): Boolean
    @JvmStatic external fun nativeHookCommand(handle: Long, pid: Int, reqJson: String): String
    @JvmStatic external fun nativeElfExportsJson(handle: Long, pid: Int, libName: String, filter: String, maxCount: Int): String
    @JvmStatic external fun nativeFindSymbol(handle: Long, pid: Int, libName: String, symName: String): Long

    // Embedded Lua 5.5.1 Script Engine & Native Plugin Bridge
    @JvmStatic external fun nativeLuaCreate(engineHandle: Long, callback: Any, propsJson: String): Long
    @JvmStatic external fun nativeLuaDestroy(luaHandle: Long)
    @JvmStatic external fun nativeLuaRunFile(luaHandle: Long, scriptPath: String): String
    @JvmStatic external fun nativeLuaRunString(luaHandle: Long, source: String, chunkName: String): String
    @JvmStatic external fun nativeLuaCancel(luaHandle: Long)
    @JvmStatic external fun nativeLuaLoadNativePlugin(luaHandle: Long, moduleName: String, soPath: String): String
    @JvmStatic external fun nativeLuaRegisterPreload(luaHandle: Long, moduleName: String, source: String): Boolean
}
