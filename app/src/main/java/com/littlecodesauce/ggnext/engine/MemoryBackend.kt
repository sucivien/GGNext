package com.littlecodesauce.ggnext.engine

import android.content.Context
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.MemoryRegion
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 统一跨进程内存引擎后端抽象接口：
 * 将 Root C++20 守护进程 (`RootDaemonBackend`)、进程内 JNI C++20 (`JniBackend`)
 * 以及 JVM `/proc/<pid>/mem` 回退 (`JvmProcBackend`) 统一在相同契约下，
 * 消除上层 `MemoryEngine` 中重复的双端分支代码。
 */
interface MemoryBackend {

    companion object {
        const val CANCELLED_RESULT: Long = -1L
        const val NATIVE_ERR_CANCELLED: Int = -1
        const val NATIVE_ERR_PROCESS_DEAD: Int = -2
    }

    data class RawCandidate(
        val address: Long,
        val rawValue: Long,
        val meta: Int
    )

    val name: String
    val supportsNativeScan: Boolean
    val supportsNativeLocker: Boolean
    val lastError: String

    fun attach(pid: Int, context: Context?): Boolean
    fun detach()
    fun isProcessAlive(): Boolean

    fun getRegions(): List<MemoryRegion>
    fun getRegionCodeAt(address: Long): String? = null
    fun classifyPointer(ptrValue: Long): Int = 0

    fun setRegionMask(mask: Int)
    fun setAddressFilter(minAddr: Long, maxAddr: Long, addrMask: Long, addrPattern: Long)
    fun cancelScan()

    fun readBlock(address: Long, length: Int): ByteArray?
    fun batchReadValues(items: List<Pair<Long, Int>>): Map<Long, Long>
    fun writeBlock(address: Long, data: ByteArray): Boolean

    data class BatchLockEntry(
        val address: Long,
        val data: ByteArray,
        val nativeDt: Int,
        val freezeMode: Int = 0,
        val minVal: Long = 0L,
        val maxVal: Long = 0L
    )

    fun writeBatch(entries: List<Pair<Long, ByteArray>>): Int {
        var ok = 0
        for ((addr, data) in entries) {
            if (writeBlock(addr, data)) ok++
        }
        return ok
    }

    fun lockBatch(entries: List<BatchLockEntry>): Int {
        var ok = 0
        for (e in entries) {
            lockAddressConfigured(e.address, e.data, e.nativeDt, e.freezeMode, e.minVal, e.maxVal)
            ok++
        }
        return ok
    }

    fun unlockBatch(addresses: Collection<Long>) {
        for (addr in addresses) unlockAddress(addr)
    }

    fun removePrefixCandidates(count: Int): Boolean = false
    fun copyMemory(fromAddr: Long, toAddr: Long, length: Long): Long
    fun dumpMemory(startAddr: Long, endAddr: Long, outPath: String): Long

    fun lockAddressConfigured(
        address: Long,
        data: ByteArray,
        dataType: Int,
        freezeMode: Int,
        minVal: Long,
        maxVal: Long
    )
    fun setFreezePaused(paused: Boolean)
    fun setFreezeIntervalMs(intervalMs: Int)
    fun unlockAddress(address: Long)
    fun unlockAll()

    fun clearSession()
    fun removeCandidates(addresses: Collection<Long>)

    fun searchExact(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refineExact(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun searchNotEqual(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refineNotEqual(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun searchRange(
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        aligned: Boolean,
        excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refineRange(
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun searchPointers(
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refinePointers(
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun searchGroup(
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refineGroup(
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun searchPattern(
        pattern: ByteArray,
        mask: String,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun searchEncoded(
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refineEncoded(
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun startUnknownSnapshot(
        dataType: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun refineFuzzy(
        deltaOp: Int,
        intDelta: Long,
        floatDelta: Double,
        epsilon: Double,
        onlyNth: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long

    fun getCandidates(offset: Int, limit: Int): List<RawCandidate>
}

/**
 * 1. Root C++20 守护通道 (`su` + `libmem_engine_root.so`)
 */
class RootDaemonBackend : MemoryBackend {

    override val name: String = "RootMemDaemon"
    override val supportsNativeScan: Boolean = true
    override val supportsNativeLocker: Boolean = true
    override var lastError: String = ""
        private set

    private var attachedPid: Int = 0

    override fun attach(pid: Int, context: Context?): Boolean {
        lastError = ""
        val ok = RootMemDaemon.attach(context, pid)
        if (ok) {
            attachedPid = pid
            return true
        }
        lastError = RootMemDaemon.lastStartError
        return false
    }

    override fun detach() {
        RootMemDaemon.unlockAll()
        RootMemDaemon.clearSession()
        attachedPid = 0
    }

    override fun isProcessAlive(): Boolean =
        attachedPid > 0 && RootMemDaemon.isTargetProcessAlive()

    override fun getRegions(): List<MemoryRegion> = RootMemDaemon.getRegions()

    override fun setRegionMask(mask: Int) {
        RootMemDaemon.setRegionMask(mask)
    }

    override fun setAddressFilter(minAddr: Long, maxAddr: Long, addrMask: Long, addrPattern: Long) {
        RootMemDaemon.setAddressFilter(minAddr, maxAddr, addrMask, addrPattern)
    }

    override fun cancelScan() {
        RootMemDaemon.cancelScan()
    }

    override fun readBlock(address: Long, length: Int): ByteArray? =
        RootMemDaemon.readBlock(address, length)

    override fun batchReadValues(items: List<Pair<Long, Int>>): Map<Long, Long> =
        RootMemDaemon.batchReadValues(items)

    override fun writeBlock(address: Long, data: ByteArray): Boolean =
        RootMemDaemon.writeBlock(address, data)

    override fun copyMemory(fromAddr: Long, toAddr: Long, length: Long): Long =
        RootMemDaemon.copyMemory(fromAddr, toAddr, length)

    override fun dumpMemory(startAddr: Long, endAddr: Long, outPath: String): Long =
        RootMemDaemon.dumpMemory(startAddr, endAddr, outPath)

    override fun lockAddressConfigured(
        address: Long,
        data: ByteArray,
        dataType: Int,
        freezeMode: Int,
        minVal: Long,
        maxVal: Long
    ) {
        RootMemDaemon.lockAddressConfigured(address, data, dataType, freezeMode, minVal, maxVal)
    }

    override fun setFreezePaused(paused: Boolean) = RootMemDaemon.setFreezePaused(paused)
    override fun setFreezeIntervalMs(intervalMs: Int) = RootMemDaemon.setFreezeIntervalMs(intervalMs)
    override fun unlockAddress(address: Long) = RootMemDaemon.unlockAddress(address)
    override fun unlockAll() = RootMemDaemon.unlockAll()

    override fun writeBatch(entries: List<Pair<Long, ByteArray>>): Int =
        RootMemDaemon.writeBatch(entries)

    override fun lockBatch(entries: List<MemoryBackend.BatchLockEntry>): Int =
        RootMemDaemon.lockBatch(entries)

    override fun unlockBatch(addresses: Collection<Long>) =
        RootMemDaemon.unlockBatch(addresses)

    override fun removePrefixCandidates(count: Int): Boolean {
        RootMemDaemon.removePrefixCandidates(count)
        return true
    }
    override fun clearSession() = RootMemDaemon.clearSession()

    override fun removeCandidates(addresses: Collection<Long>) {
        RootMemDaemon.removeCandidates(addresses)
    }

    override fun searchExact(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchExact(dataType, intTarget, floatTarget, epsilon, aligned, onProgress)

    override fun refineExact(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refineExact(dataType, intTarget, floatTarget, epsilon, onProgress)

    override fun searchNotEqual(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchNotEqual(dataType, intTarget, floatTarget, epsilon, aligned, onProgress)

    override fun refineNotEqual(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refineNotEqual(dataType, intTarget, floatTarget, epsilon, onProgress)

    override fun searchRange(
        dataType: Int, intMin: Long, intMax: Long, floatMin: Double, floatMax: Double,
        inclusive: Boolean, aligned: Boolean, excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchRange(
        dataType, intMin, intMax, floatMin, floatMax, inclusive, aligned, excludeRange, onProgress
    )

    override fun refineRange(
        dataType: Int, intMin: Long, intMax: Long, floatMin: Double, floatMax: Double,
        inclusive: Boolean, excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refineRange(
        dataType, intMin, intMax, floatMin, floatMax, inclusive, excludeRange, onProgress
    )

    override fun searchPointers(
        dataType: Int, targetAddress: Long, maxOffset: Long, protMask: Int, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchPointers(dataType, targetAddress, maxOffset, protMask, aligned, onProgress)

    override fun refinePointers(
        dataType: Int, targetAddress: Long, maxOffset: Long, protMask: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refinePointers(dataType, targetAddress, maxOffset, protMask, onProgress)

    override fun searchGroup(
        types: IntArray, intVals: LongArray, floatVals: DoubleArray,
        intMaxVals: LongArray, floatMaxVals: DoubleArray, fieldOps: IntArray,
        windowSize: Int, ordered: Boolean, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchGroup(
        types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned, onProgress
    )

    override fun refineGroup(
        types: IntArray, intVals: LongArray, floatVals: DoubleArray,
        intMaxVals: LongArray, floatMaxVals: DoubleArray, fieldOps: IntArray,
        windowSize: Int, ordered: Boolean, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refineGroup(
        types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned, onProgress
    )

    override fun searchPattern(
        pattern: ByteArray, mask: String, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchPattern(pattern, mask, onProgress)

    override fun searchEncoded(
        dataType: Int, encodedMode: Int, logicalInt: Long, logicalFloat: Double,
        keyOrMask: Long, aligned: Boolean, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.searchEncoded(
        dataType, encodedMode, logicalInt, logicalFloat, keyOrMask, aligned, onProgress
    )

    override fun refineEncoded(
        dataType: Int, encodedMode: Int, logicalInt: Long, logicalFloat: Double,
        keyOrMask: Long, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refineEncoded(
        dataType, encodedMode, logicalInt, logicalFloat, keyOrMask, onProgress
    )

    override fun startUnknownSnapshot(
        dataType: Int, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.startUnknownSnapshot(dataType, onProgress)

    override fun refineFuzzy(
        deltaOp: Int, intDelta: Long, floatDelta: Double, epsilon: Double, onlyNth: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = RootMemDaemon.refineFuzzy(deltaOp, intDelta, floatDelta, epsilon, onlyNth, onProgress)

    override fun getCandidates(offset: Int, limit: Int): List<MemoryBackend.RawCandidate> =
        RootMemDaemon.getCandidates(offset, limit)
}

/**
 * 2. 免 Root / 同 UID 进程内 C++20 JNI 通道 (`libmem_engine_jni.so`)
 */
class JniBackend(
    private val parseMaps: (Int) -> List<MemoryRegion>
) : MemoryBackend {

    override val name: String = "JniNative"
    override val supportsNativeScan: Boolean = true
    override val supportsNativeLocker: Boolean = true
    override var lastError: String = ""
        private set

    @Volatile
    private var handle: Long = 0L
    private var attachedPid: Int = 0

    override fun attach(pid: Int, context: Context?): Boolean {
        lastError = ""
        if (!NativeMemBridge.isLoaded) {
            lastError = "Native JNI 库未加载"
            return false
        }
        val spillDir = context?.cacheDir?.absolutePath ?: "/data/local/tmp"
        val h = NativeMemBridge.ensureHandle(spillDir)
        if (h == 0L) {
            lastError = "无法初始化 NativeMemBridge 会话句柄"
            return false
        }
        val rc = NativeMemBridge.nativeAttach(h, pid)
        if (rc == 0) {
            handle = h
            attachedPid = pid
            return true
        }
        lastError = "JNI 附加失败 (rc=$rc)"
        return false
    }

    override fun detach() {
        val h = handle
        if (h != 0L) {
            NativeMemBridge.nativeUnlockAll(h)
            NativeMemBridge.nativeClearSession(h)
        }
        attachedPid = 0
    }

    override fun isProcessAlive(): Boolean {
        val h = handle
        return h != 0L && attachedPid > 0 && NativeMemBridge.nativeIsProcessAlive(h)
    }

    override fun getRegions(): List<MemoryRegion> =
        if (attachedPid > 0) parseMaps(attachedPid) else emptyList()

    override fun getRegionCodeAt(address: Long): String? {
        val h = handle
        return if (h != 0L) NativeMemBridge.nativeGetRegionCodeAt(h, address) else null
    }

    override fun classifyPointer(ptrValue: Long): Int {
        val h = handle
        return if (h != 0L) NativeMemBridge.nativeClassifyPointer(h, ptrValue) else 0
    }

    override fun setRegionMask(mask: Int) {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeSetRegionMask(h, mask)
    }

    override fun setAddressFilter(minAddr: Long, maxAddr: Long, addrMask: Long, addrPattern: Long) {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeSetAddressFilter(h, minAddr, maxAddr, addrMask, addrPattern)
    }

    override fun cancelScan() {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeCancelScan(h)
    }

    override fun readBlock(address: Long, length: Int): ByteArray? {
        val h = handle
        return if (h != 0L) NativeMemBridge.nativeReadBlock(h, address, length) else null
    }

    override fun batchReadValues(items: List<Pair<Long, Int>>): Map<Long, Long> {
        val h = handle
        if (h == 0L || items.isEmpty()) return emptyMap()
        val out = HashMap<Long, Long>(items.size * 4 / 3 + 8)
        for ((addr, sz) in items) {
            val safeSize = sz.coerceIn(1, 8)
            val bytes = NativeMemBridge.nativeReadBlock(h, addr, safeSize) ?: continue
            var v = 0L
            for (i in 0 until min(safeSize, bytes.size)) {
                v = v or ((bytes[i].toLong() and 0xFFL) shl (i * 8))
            }
            out[addr] = v
        }
        return out
    }

    override fun writeBlock(address: Long, data: ByteArray): Boolean {
        val h = handle
        return h != 0L && NativeMemBridge.nativeWriteBlock(h, address, data)
    }

    override fun copyMemory(fromAddr: Long, toAddr: Long, length: Long): Long {
        val h = handle
        return if (h != 0L) NativeMemBridge.nativeCopyMemory(h, fromAddr, toAddr, length) else 0L
    }

    override fun dumpMemory(startAddr: Long, endAddr: Long, outPath: String): Long {
        val h = handle
        return if (h != 0L) NativeMemBridge.nativeDumpMemory(h, startAddr, endAddr, outPath) else 0L
    }

    override fun lockAddressConfigured(
        address: Long,
        data: ByteArray,
        dataType: Int,
        freezeMode: Int,
        minVal: Long,
        maxVal: Long
    ) {
        val h = handle
        if (h != 0L) {
            NativeMemBridge.nativeLockAddressConfigured(h, address, data, dataType, freezeMode, minVal, maxVal)
        }
    }

    override fun setFreezePaused(paused: Boolean) {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeSetFreezePaused(h, paused)
    }

    override fun setFreezeIntervalMs(intervalMs: Int) {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeSetFreezeIntervalMs(h, intervalMs)
    }

    override fun unlockAddress(address: Long) {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeUnlockAddress(h, address)
    }

    override fun unlockAll() {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeUnlockAll(h)
    }

    override fun clearSession() {
        val h = handle
        if (h != 0L) NativeMemBridge.nativeClearSession(h)
    }

    override fun removeCandidates(addresses: Collection<Long>) {
        val h = handle
        if (h != 0L && addresses.isNotEmpty()) {
            NativeMemBridge.nativeRemoveCandidates(h, addresses.toLongArray())
        }
    }

    private fun finishNativeScan(rc: Int, onProgress: ((Long, Long, Long) -> Unit)?): Long {
        if (rc == MemoryBackend.NATIVE_ERR_CANCELLED) {
            return MemoryBackend.CANCELLED_RESULT
        }
        if (rc == MemoryBackend.NATIVE_ERR_PROCESS_DEAD) {
            RootMemDaemon.onTargetProcessDead?.invoke()
            return 0L
        }
        val h = handle
        val cnt = if (h != 0L) NativeMemBridge.nativeGetCandidateCount(h) else 0L
        onProgress?.invoke(1L, 1L, cnt)
        return cnt
    }

    override fun searchExact(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeSearchExact(h, dataType, intTarget, floatTarget, epsilon, aligned),
            onProgress
        )
    }

    override fun refineExact(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefineExact(h, dataType, intTarget, floatTarget, epsilon),
            onProgress
        )
    }

    override fun searchNotEqual(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeSearchNotEqual(h, dataType, intTarget, floatTarget, epsilon, aligned),
            onProgress
        )
    }

    override fun refineNotEqual(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefineNotEqual(h, dataType, intTarget, floatTarget, epsilon),
            onProgress
        )
    }

    override fun searchRange(
        dataType: Int, intMin: Long, intMax: Long, floatMin: Double, floatMax: Double,
        inclusive: Boolean, aligned: Boolean, excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeSearchRange(
                h, dataType, intMin, intMax, floatMin, floatMax, inclusive, aligned, excludeRange
            ),
            onProgress
        )
    }

    override fun refineRange(
        dataType: Int, intMin: Long, intMax: Long, floatMin: Double, floatMax: Double,
        inclusive: Boolean, excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefineRange(
                h, dataType, intMin, intMax, floatMin, floatMax, inclusive, excludeRange
            ),
            onProgress
        )
    }

    override fun searchPointers(
        dataType: Int, targetAddress: Long, maxOffset: Long, protMask: Int, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeSearchPointers(h, dataType, targetAddress, maxOffset, protMask, aligned),
            onProgress
        )
    }

    override fun refinePointers(
        dataType: Int, targetAddress: Long, maxOffset: Long, protMask: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefinePointers(h, dataType, targetAddress, maxOffset, protMask),
            onProgress
        )
    }

    override fun searchGroup(
        types: IntArray, intVals: LongArray, floatVals: DoubleArray,
        intMaxVals: LongArray, floatMaxVals: DoubleArray, fieldOps: IntArray,
        windowSize: Int, ordered: Boolean, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeSearchGroup(
                h, types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned
            ),
            onProgress
        )
    }

    override fun refineGroup(
        types: IntArray, intVals: LongArray, floatVals: DoubleArray,
        intMaxVals: LongArray, floatMaxVals: DoubleArray, fieldOps: IntArray,
        windowSize: Int, ordered: Boolean, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefineGroup(
                h, types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned
            ),
            onProgress
        )
    }

    override fun searchPattern(
        pattern: ByteArray, mask: String, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(NativeMemBridge.nativeSearchPattern(h, pattern, mask), onProgress)
    }

    override fun searchEncoded(
        dataType: Int, encodedMode: Int, logicalInt: Long, logicalFloat: Double,
        keyOrMask: Long, aligned: Boolean, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeSearchEncoded(h, dataType, encodedMode, logicalInt, logicalFloat, keyOrMask, aligned),
            onProgress
        )
    }

    override fun refineEncoded(
        dataType: Int, encodedMode: Int, logicalInt: Long, logicalFloat: Double,
        keyOrMask: Long, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefineEncoded(h, dataType, encodedMode, logicalInt, logicalFloat, keyOrMask),
            onProgress
        )
    }

    override fun startUnknownSnapshot(
        dataType: Int, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(NativeMemBridge.nativeStartUnknownSnapshot(h, dataType), onProgress)
    }

    override fun refineFuzzy(
        deltaOp: Int, intDelta: Long, floatDelta: Double, epsilon: Double, onlyNth: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long {
        val h = handle
        if (h == 0L) return 0L
        return finishNativeScan(
            NativeMemBridge.nativeRefineFuzzy(h, deltaOp, intDelta, floatDelta, epsilon, onlyNth),
            onProgress
        )
    }

    override fun getCandidates(offset: Int, limit: Int): List<MemoryBackend.RawCandidate> {
        val h = handle
        if (h == 0L) return emptyList()
        val packed = NativeMemBridge.nativeGetCandidatesPacked(h, offset, limit) ?: return emptyList()
        val count = packed.size / 3
        val out = ArrayList<MemoryBackend.RawCandidate>(count)
        var idx = 0
        while (idx + 2 < packed.size) {
            out.add(MemoryBackend.RawCandidate(packed[idx], packed[idx + 1], packed[idx + 2].toInt()))
            idx += 3
        }
        return out
    }
}

/**
 * 3. 纯 JVM `/proc/<pid>/mem` 回退通道（用于无 Native 库但当前进程具备直接 procfs 读写权限的场景）
 */
class JvmProcBackend(
    private val parseMaps: (Int) -> List<MemoryRegion>
) : MemoryBackend {

    override val name: String = "JvmProcFallback"
    override val supportsNativeScan: Boolean = false
    override val supportsNativeLocker: Boolean = false
    override var lastError: String = ""
        private set

    private var attachedPid: Int = 0
    val cancelled = AtomicBoolean(false)

    override fun attach(pid: Int, context: Context?): Boolean {
        lastError = ""
        val regions = parseMaps(pid)
        val canReadMem = runCatching {
            RandomAccessFile("/proc/$pid/mem", "r").use { f ->
                val firstReadable = regions.firstOrNull { it.perms.startsWith("r") && it.size > 0 }
                if (firstReadable != null) {
                    f.seek(firstReadable.start)
                    val buf = ByteArray(4)
                    f.read(buf, 0, 4) >= 0
                } else {
                    false
                }
            }
        }.getOrDefault(false)
        if (regions.isNotEmpty() && canReadMem) {
            attachedPid = pid
            cancelled.set(false)
            return true
        }
        lastError = "无跨进程内存读写权限（进程可能已退出或受内核/SELinux 保护）"
        return false
    }

    override fun detach() {
        attachedPid = 0
        cancelled.set(false)
    }

    override fun isProcessAlive(): Boolean =
        attachedPid > 0 && File("/proc/$attachedPid/maps").exists()

    override fun getRegions(): List<MemoryRegion> =
        if (attachedPid > 0) parseMaps(attachedPid) else emptyList()

    override fun setRegionMask(mask: Int) = Unit
    override fun setAddressFilter(minAddr: Long, maxAddr: Long, addrMask: Long, addrPattern: Long) = Unit

    override fun cancelScan() {
        cancelled.set(true)
    }

    fun resetCancel() {
        cancelled.set(false)
    }

    override fun readBlock(address: Long, length: Int): ByteArray? {
        val pid = attachedPid
        if (pid <= 0 || length <= 0) return null
        val out = ByteArray(length)
        return try {
            RandomAccessFile("/proc/$pid/mem", "r").use { f ->
                f.seek(address)
                f.read(out, 0, length)
            }
            out
        } catch (_: Throwable) {
            out
        }
    }

    override fun batchReadValues(items: List<Pair<Long, Int>>): Map<Long, Long> {
        val pid = attachedPid
        if (pid <= 0 || items.isEmpty()) return emptyMap()
        val out = HashMap<Long, Long>(items.size * 4 / 3 + 8)
        runCatching {
            RandomAccessFile("/proc/$pid/mem", "r").use { f ->
                val buf = ByteArray(8)
                for ((addr, sz) in items) {
                    val safeSize = sz.coerceIn(1, 8)
                    val got = try {
                        f.seek(addr)
                        f.read(buf, 0, safeSize)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (got > 0) {
                        var v = 0L
                        for (i in 0 until min(safeSize, got)) {
                            v = v or ((buf[i].toLong() and 0xFFL) shl (i * 8))
                        }
                        out[addr] = v
                    }
                }
            }
        }
        return out
    }

    override fun writeBlock(address: Long, data: ByteArray): Boolean {
        val pid = attachedPid
        if (pid <= 0 || data.isEmpty()) return false
        return try {
            RandomAccessFile("/proc/$pid/mem", "rw").use { f ->
                f.seek(address)
                f.write(data)
            }
            true
        } catch (t: Throwable) {
            AppLog.e("JvmProcBackend", "write failed @0x${address.toString(16)}", t)
            false
        }
    }

    override fun copyMemory(fromAddr: Long, toAddr: Long, length: Long): Long {
        val pid = attachedPid
        if (pid <= 0 || length <= 0L) return 0L
        val safeLen = length.coerceAtMost(16L * 1024L * 1024L).toInt()
        val chunkSize = 64 * 1024
        var offset = 0
        while (offset < safeLen) {
            val step = min(chunkSize, safeLen - offset)
            val data = readBlock(fromAddr + offset, step) ?: break
            if (!writeBlock(toAddr + offset, data)) break
            offset += step
        }
        return offset.toLong()
    }

    override fun dumpMemory(startAddr: Long, endAddr: Long, outPath: String): Long {
        val pid = attachedPid
        if (pid <= 0 || endAddr <= startAddr) return 0L
        val outFile = File(outPath)
        outFile.parentFile?.mkdirs()
        var written = 0L
        runCatching {
            outFile.outputStream().buffered().use { out ->
                val chunk = 64 * 1024
                var cur = startAddr
                while (cur < endAddr) {
                    val step = min(chunk.toLong(), endAddr - cur).toInt()
                    val bytes = readBlock(cur, step) ?: ByteArray(step)
                    out.write(bytes)
                    written += bytes.size
                    cur += step
                }
            }
        }
        return written
    }

    override fun lockAddressConfigured(
        address: Long, data: ByteArray, dataType: Int, freezeMode: Int, minVal: Long, maxVal: Long
    ) = Unit

    override fun setFreezePaused(paused: Boolean) = Unit
    override fun setFreezeIntervalMs(intervalMs: Int) = Unit
    override fun unlockAddress(address: Long) = Unit
    override fun unlockAll() = Unit
    override fun clearSession() = Unit
    override fun removeCandidates(addresses: Collection<Long>) = Unit

    override fun searchExact(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refineExact(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun searchNotEqual(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refineNotEqual(
        dataType: Int, intTarget: Long, floatTarget: Double, epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun searchRange(
        dataType: Int, intMin: Long, intMax: Long, floatMin: Double, floatMax: Double,
        inclusive: Boolean, aligned: Boolean, excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refineRange(
        dataType: Int, intMin: Long, intMax: Long, floatMin: Double, floatMax: Double,
        inclusive: Boolean, excludeRange: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun searchPointers(
        dataType: Int, targetAddress: Long, maxOffset: Long, protMask: Int, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refinePointers(
        dataType: Int, targetAddress: Long, maxOffset: Long, protMask: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun searchGroup(
        types: IntArray, intVals: LongArray, floatVals: DoubleArray,
        intMaxVals: LongArray, floatMaxVals: DoubleArray, fieldOps: IntArray,
        windowSize: Int, ordered: Boolean, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refineGroup(
        types: IntArray, intVals: LongArray, floatVals: DoubleArray,
        intMaxVals: LongArray, floatMaxVals: DoubleArray, fieldOps: IntArray,
        windowSize: Int, ordered: Boolean, aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun searchPattern(
        pattern: ByteArray, mask: String, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun searchEncoded(
        dataType: Int, encodedMode: Int, logicalInt: Long, logicalFloat: Double,
        keyOrMask: Long, aligned: Boolean, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refineEncoded(
        dataType: Int, encodedMode: Int, logicalInt: Long, logicalFloat: Double,
        keyOrMask: Long, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun startUnknownSnapshot(
        dataType: Int, onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun refineFuzzy(
        deltaOp: Int, intDelta: Long, floatDelta: Double, epsilon: Double, onlyNth: Int,
        onProgress: ((Long, Long, Long) -> Unit)?
    ): Long = 0L

    override fun getCandidates(offset: Int, limit: Int): List<MemoryBackend.RawCandidate> = emptyList()
}
