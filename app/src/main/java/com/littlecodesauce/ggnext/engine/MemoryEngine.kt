package com.littlecodesauce.ggnext.engine

import android.content.Context
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.MemoryRegion
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * 统一跨进程内存检索引擎：
 * 通过 `MemoryBackend` 统一调度以下 3 类后端，彻底消除重复的双端分支代码：
 * 1. `RootDaemonBackend` (`su` + `libmem_engine_root.so`)
 * 2. `JniBackend` (同 UID / 虚拟空间 `libmem_engine_jni.so`)
 * 3. `JvmProcBackend` (纯 JVM `/proc/<pid>/mem` 回退)
 */
class MemoryEngine {

    data class SearchResult(
        val items: List<MemoryItem>,
        val totalFound: Long = items.size.toLong(),
        val cancelled: Boolean = false
    )

    companion object {
        /** Kotlin 层缓存的最大候选条目数（上限 100,000，C++ 层 MmapCandidateStore 保留全量候选） */
        const val MAX_CACHED_CANDIDATES = 100000
    }

    data class Criteria(
        val type: Int = ValueType.DWORD,
        val value: Long = 0L,
        val from: Long = value,
        val to: Long = value,
        val isRange: Boolean = false,
        val notRange: Boolean = false,
        val sign: Int = ValueType.SIGN_EQUAL,
        val xorKey: Long = 0L,
        val xorOffset: Int = 0,
        val orderedGroup: List<Long> = emptyList(),
        val groupTo: List<Long> = emptyList(),
        val groupOps: List<Int> = emptyList(),
        val ordered: Boolean = false,
        val groupTypes: List<Int> = emptyList(),
        val area: Int = 512,
        val memoryFrom: Long = 0L,
        val memoryTo: Long = Long.MAX_VALUE,
        val limit: Int = MAX_CACHED_CANDIDATES,
        val rawExpression: String = "",
        val encryptedXor: Boolean = false,
        val selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        val mask: Long = 0L,
        val maskPattern: Long = 0L,
        val isText: Boolean = false,
        val textBytes: ByteArray = ByteArray(0),
        val textUtf16: Boolean = false
    )

    var pid: Int = 0
        private set

    var x64: Boolean = true
        private set

    var attachedOk: Boolean = false
        private set

    var rootDaemonActive: Boolean = false
        private set

    var nativeBackendActive: Boolean = false
        private set

    var lastAttachError: String = ""
        private set

    @Volatile
    private var activeBackend: MemoryBackend? = null
    private var isRootModeActive: Boolean = false
    private var appContextRef: Context? = null
    @Volatile
    private var cachedRegions: List<MemoryRegion> = emptyList()
    private val removedAddresses: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val scanCancelled = AtomicBoolean(false)

    val supportsNativeScan: Boolean
        get() = activeBackend?.supportsNativeScan == true

    private val isNativeLockerActive: Boolean
        get() = activeBackend?.supportsNativeLocker == true

    fun setOnTargetProcessDead(listener: (() -> Unit)?) {
        RootMemDaemon.onTargetProcessDead = listener
    }

    fun isTargetProcessAlive(): Boolean {
        if (!attachedOk || pid <= 0) return false
        return activeBackend?.isProcessAlive() ?: false
    }

    /**
     * 立即中断当前正在执行的扫描（同步下发到 C++ 守护进程/JNI CancellationToken 与 JVM 回退标志）。
     */
    fun cancelScan() {
        scanCancelled.set(true)
        activeBackend?.cancelScan()
    }

    fun markRemoved(addresses: Collection<Long>) {
        if (addresses.isEmpty()) return
        val backend = activeBackend
        if (backend != null && backend.supportsNativeScan) {
            backend.removeCandidates(addresses)
        } else {
            removedAddresses.addAll(addresses)
        }
    }

    /** 前缀批量移除快路径（直接 memmove，无需传输海量地址列表） */
    fun removePrefixCandidates(count: Int, fallbackAddresses: Collection<Long>): Boolean {
        if (count <= 0) return true
        val backend = activeBackend
        if (backend != null && backend.supportsNativeScan && backend.removePrefixCandidates(count)) {
            return true
        }
        markRemoved(fallbackAddresses)
        return false
    }

    fun clearRemoved() {
        removedAddresses.clear()
    }

    fun clearSession() {
        removedAddresses.clear()
        activeBackend?.clearSession()
    }

    /**
     * 附加到目标进程：
     * - Root 模式下优先通过 `RootDaemonBackend` (`su` + `libmem_engine_root.so`) 以 `uid=0` 附加；
     * - 免 Root 模式下通过 `JniBackend` (`libmem_engine_jni.so`) 或 `JvmProcBackend` 直接访问 `/proc/<pid>/mem`；
     * - 若无任何后端具备真实跨进程内存读取权限，诚实返回 `false` 并清空区域与结果，绝不假装附加成功。
     */
    fun attach(
        targetPid: Int,
        targetX64: Boolean = true,
        isRootMode: Boolean = true,
        context: Context? = null
    ): Boolean {
        clearNativeFreezes()
        removedAddresses.clear()
        scanCancelled.set(false)
        pid = targetPid
        x64 = targetX64
        isRootModeActive = isRootMode
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }
        activeBackend = null
        rootDaemonActive = false
        nativeBackendActive = false
        attachedOk = false
        lastAttachError = ""
        cachedRegions = emptyList()

        if (targetPid <= 0) {
            lastAttachError = "无效的目标 PID ($targetPid)"
            return false
        }

        val hasRootNow = isRootMode && Shell.hasRoot()

        // 1. Root 模式：优先通过持久化 su C++20 守护进程
        if (hasRootNow) {
            val rootBackend = RootDaemonBackend()
            if (rootBackend.attach(targetPid, appContextRef)) {
                activeBackend = rootBackend
                rootDaemonActive = true
                attachedOk = true
                cachedRegions = rootBackend.getRegions().ifEmpty {
                    parseMapsFromProc(targetPid, allowRoot = true)
                }
                AppLog.i("Engine", "Attached pid=$targetPid via RootDaemonBackend (regions=${cachedRegions.size})")
                return true
            }
            if (rootBackend.lastError.isNotBlank()) {
                lastAttachError = rootBackend.lastError
            }
        }

        // 2. 进程内 C++20 JNI 通道 (process_vm_readv + /proc/<pid>/mem)
        if (NativeMemBridge.isLoaded) {
            val jniBackend = JniBackend { p -> parseMapsFromProc(p, allowRoot = hasRootNow) }
            if (jniBackend.attach(targetPid, appContextRef)) {
                activeBackend = jniBackend
                nativeBackendActive = true
                attachedOk = true
                cachedRegions = jniBackend.getRegions()
                lastAttachError = ""
                AppLog.i("Engine", "Attached pid=$targetPid via JniBackend (regions=${cachedRegions.size})")
                return true
            }
        }

        // 3. JVM 探测：仅当当前进程自身确实可读取 `/proc/<pid>/mem` 时才允许回退成功
        val jvmBackend = JvmProcBackend { p -> parseMapsFromProc(p, allowRoot = hasRootNow) }
        if (jvmBackend.attach(targetPid, appContextRef)) {
            activeBackend = jvmBackend
            attachedOk = true
            cachedRegions = jvmBackend.getRegions()
            lastAttachError = ""
            AppLog.i("Engine", "Attached pid=$targetPid via JvmProcBackend (regions=${cachedRegions.size})")
            return true
        }

        if (lastAttachError.isBlank()) {
            lastAttachError = if (isRootMode && !hasRootNow) {
                "未获取到 Root (su) 授权"
            } else {
                jvmBackend.lastError.ifBlank { "无跨进程内存读写权限（进程可能已退出或受内核/SELinux 保护）" }
            }
        }
        AppLog.w("Engine", "Attach failed for pid=$targetPid (isRootMode=$isRootMode, hasRoot=$hasRootNow): $lastAttachError")
        return false
    }

    fun detach() {
        cancelScan()
        clearNativeFreezes()
        activeBackend?.detach()
        activeBackend = null
        removedAddresses.clear()
        pid = 0
        attachedOk = false
        rootDaemonActive = false
        nativeBackendActive = false
        lastAttachError = ""
        cachedRegions = emptyList()
    }

    /**
     * 读取当前附加进程的全部真实 VMA 内存段列表。
     */
    fun regions(): List<MemoryRegion> {
        if (!attachedOk || pid <= 0) return emptyList()
        val backendRegs = activeBackend?.getRegions().orEmpty()
        if (backendRegs.isNotEmpty()) {
            cachedRegions = backendRegs
            return backendRegs
        }
        val fresh = parseMapsFromProc(pid, allowRoot = isRootModeActive && Shell.hasRoot())
        if (fresh.isNotEmpty()) {
            cachedRegions = fresh
        }
        return cachedRegions
    }

    /**
     * 获取指定区域类型 (如 "Xa", "Cd", "Cb", "Jh", "Ch", "Ca") 的首个真实基址
     */
    fun firstRegionBase(code: String): Long? {
        val regs = if (cachedRegions.isNotEmpty()) cachedRegions else regions()
        return regs.firstOrNull { it.code.equals(code, ignoreCase = true) && it.readable }?.start
            ?: regs.firstOrNull { it.code.equals(code, ignoreCase = true) }?.start
    }

    private fun parseMapsFromProc(targetPid: Int, allowRoot: Boolean): List<MemoryRegion> {
        if (targetPid <= 0) return emptyList()
        val lines = runCatching {
            val f = File("/proc/$targetPid/maps")
            if (f.canRead()) f.readLines() else emptyList()
        }.getOrDefault(emptyList()).let { direct ->
            if (direct.isNotEmpty()) direct
            else if (allowRoot) Shell.suLines("cat /proc/$targetPid/maps 2>/dev/null")
            else emptyList()
        }
        if (lines.isEmpty()) return emptyList()

        val out = ArrayList<MemoryRegion>(lines.size)
        var prevFileBackedPath = ""
        for (line in lines) {
            val seg = parseMapLine(line, prevFileBackedPath) ?: continue
            prevFileBackedPath = seg.prevPathNext
            out.add(
                MemoryRegion(
                    start = seg.start,
                    end = seg.end,
                    perms = seg.perms,
                    path = seg.path,
                    code = seg.code,
                    system = (seg.code == "Xs" || seg.code == "O")
                )
            )
        }
        return out
    }

    private fun untagAddr(address: Long): Long =
        if (x64) (address and 0x00FFFFFFFFFFFFFFL) else (address and 0xFFFFFFFFL)

    /** 查询指定虚拟地址所属的细分内存区域代码 (Jh, Ch, Ca, Cd, Cb, Xa 等) */
    fun regionCodeAt(address: Long): String {
        val cleanAddr = untagAddr(address)
        val regs = cachedRegions
        var lo = 0
        var hi = regs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = regs[mid]
            if (java.lang.Long.compareUnsigned(cleanAddr, r.start) < 0) {
                hi = mid - 1
            } else if (java.lang.Long.compareUnsigned(cleanAddr, r.end) >= 0) {
                lo = mid + 1
            } else {
                return r.code
            }
        }
        activeBackend?.getRegionCodeAt(cleanAddr)?.let {
            if (it.isNotEmpty()) return it
        }
        return "O"
    }

    /**
     * 根据当前进程真实 VMA 映射表对候选值进行指针属性分类 (忽略 ARM64 TBI 高 8 位标签)：
     * 0 = 非指针, 1 = 可写且可执行 (WX), 2 = 只读可执行 (X), 8 = 只读 (RO), 16 = 可写 (W)
     */
    fun classifyPointer(ptrValue: Long): Int {
        if (x64) {
            val topByte = (ptrValue ushr 56) and 0xFFL
            // 仅放行无标签 (0x00) 或 Android ARM64 Scudo/TBI 标签 (0xB0..0xBF)，避免浮点数/普通整数高字节误判为指针
            if (topByte != 0L && topByte !in 0xB0L..0xBFL) return 0
        }
        val cleanPtr = untagAddr(ptrValue)
        if (cleanPtr <= 0x10000L) return 0
        if (x64 && (cleanPtr ushr 48) != 0L) return 0

        var regs = cachedRegions
        if (regs.isEmpty() && attachedOk && pid > 0) {
            regs = regions()
        }
        var lo = 0
        var hi = regs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = regs[mid]
            if (java.lang.Long.compareUnsigned(cleanPtr, r.start) < 0) {
                hi = mid - 1
            } else if (java.lang.Long.compareUnsigned(cleanPtr, r.end) >= 0) {
                lo = mid + 1
            } else {
                if (r.code == "B") return 0
                val read = r.perms.contains('r')
                val write = r.perms.contains('w')
                val exec = r.perms.contains('x')
                if (!read) return 0
                return when {
                    write && exec -> 1
                    exec -> 2
                    write -> 16
                    else -> 8
                }
            }
        }
        if (regs.isNotEmpty()) return 0
        return activeBackend?.classifyPointer(cleanPtr) ?: 0
    }

    fun classifyPointers(candidates: LongArray): IntArray =
        IntArray(candidates.size) { idx -> classifyPointer(candidates[idx]) }

    /** 读取单个地址的值（小端序） */
    fun read(address: Long, size: Int): Long {
        if (!attachedOk || pid <= 0) return 0L
        val safeSize = size.coerceIn(1, 8)
        val bytes = readBlock(address, safeSize)
        var v = 0L
        for (i in 0 until min(safeSize, bytes.size)) {
            v = v or ((bytes[i].toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    /** 批量实时读取一组离散地址的最新内存值 */
    fun batchReadRawValues(items: List<Pair<Long, Int>>): Map<Long, Long> {
        if (!attachedOk || pid <= 0 || items.isEmpty()) return emptyMap()
        return activeBackend?.batchReadValues(items) ?: emptyMap()
    }

    /** 批量读取连续字节块（供内存浏览器及偏移计算器使用） */
    fun readBlock(baseAddress: Long, length: Int): ByteArray {
        val safeLen = length.coerceIn(1, 16 * 1024 * 1024)
        if (!attachedOk || pid <= 0) return ByteArray(safeLen)
        return activeBackend?.readBlock(baseAddress, safeLen) ?: ByteArray(safeLen)
    }

    /** 写入一个地址的值 */
    fun write(address: Long, size: Int, value: Long): Boolean {
        if (!attachedOk || pid <= 0) return false
        val safeSize = size.coerceIn(1, 8)
        val buf = ByteArray(safeSize)
        for (i in 0 until safeSize) {
            buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
        return activeBackend?.writeBlock(address, buf) == true
    }

    /** 写入任意长度原始字节块（供 Keystone 汇编补丁与十六进制流写入使用） */
    /** 批量写入（Root 模式下合并为少量 BATCH_WRITE / BATCH_WRITE_UNIFORM + process_vm_writev iovec） */
    fun writeBatch(entries: List<Triple<Long, Int, Long>>): Int {
        if (!attachedOk || entries.isEmpty()) return 0
        val backend = activeBackend ?: return 0
        val rawPairs = ArrayList<Pair<Long, ByteArray>>(entries.size)
        for ((addr, size, value) in entries) {
            val sz = size.coerceIn(1, 8)
            val buf = ByteArray(sz)
            for (i in 0 until sz) {
                buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
            }
            rawPairs.add(addr to buf)
        }
        return backend.writeBatch(rawPairs)
    }

    fun writeBlockBatch(entries: List<Pair<Long, ByteArray>>): Int {
        if (!attachedOk || entries.isEmpty()) return 0
        val backend = activeBackend ?: return 0
        return backend.writeBatch(entries)
    }

    fun lockBatch(items: List<MemoryItem>): Int {
        if (!attachedOk || items.isEmpty()) return 0
        val backend = activeBackend ?: return 0
        if (!backend.supportsNativeLocker) return 0
        val batch = ArrayList<MemoryBackend.BatchLockEntry>(items.size)
        for (item in items) {
            val sz = ValueType.sizeOf(item.flags).coerceIn(1, 8)
            val buf = ByteArray(sz)
            for (i in 0 until sz) {
                buf[i] = ((item.value ushr (i * 8)) and 0xFF).toByte()
            }
            batch.add(
                MemoryBackend.BatchLockEntry(
                    address = item.address,
                    data = buf,
                    nativeDt = NativeMemBridge.toNativeDataType(item.flags),
                    freezeMode = item.freezeType,
                    minVal = item.freezeFrom,
                    maxVal = item.freezeTo
                )
            )
        }
        return backend.lockBatch(batch)
    }

    fun unlockBatch(addresses: Collection<Long>) {
        if (!attachedOk || addresses.isEmpty()) return
        val backend = activeBackend ?: return
        if (!backend.supportsNativeLocker) return
        backend.unlockBatch(addresses)
    }

        fun writeBlock(address: Long, data: ByteArray): Boolean {
        if (!attachedOk || pid <= 0 || data.isEmpty()) return false
        return activeBackend?.writeBlock(address, data) == true
    }

    /** 设置或取消指定地址的冻结锁值（同步到 C++20 ValueLocker 独立高频锁值线程，原生支持全部 4 种冻结模式） */
    fun setFreeze(
        address: Long,
        flags: Int,
        value: Long,
        enabled: Boolean,
        freezeType: Int = 0,
        freezeFrom: Long = 0L,
        freezeTo: Long = 0L
    ) {
        if (!attachedOk) return
        val backend = activeBackend ?: return
        if (!backend.supportsNativeLocker) return
        if (enabled) {
            val sz = ValueType.sizeOf(flags).coerceIn(1, 8)
            val buf = ByteArray(sz)
            for (i in 0 until sz) {
                buf[i] = ((value ushr (i * 8)) and 0xFF).toByte()
            }
            val nativeDt = NativeMemBridge.toNativeDataType(flags)
            backend.lockAddressConfigured(address, buf, nativeDt, freezeType, freezeFrom, freezeTo)
        } else {
            backend.unlockAddress(address)
        }
    }

    fun setFreezePaused(paused: Boolean) {
        activeBackend?.setFreezePaused(paused)
    }

    fun setFreezeIntervalMs(intervalMs: Int) {
        activeBackend?.setFreezeIntervalMs(intervalMs.coerceIn(5, 5000))
    }

    /** 仅在未启用 C++ 原生锁值线程时作为纯 JVM 回退执行锁值写入 */
    fun tickFreeze(savedList: List<MemoryItem>): List<MemoryItem>? {
        if (!attachedOk || pid <= 0 || savedList.isEmpty()) return null
        if (isNativeLockerActive) return null

        var changedAny = false
        val updated = ArrayList<MemoryItem>(savedList.size)
        for (item in savedList) {
            if (!item.freeze) {
                updated.add(item)
                continue
            }
            val nextVal = when (item.freezeType) {
                1 -> addTypedValue(item.value, 1L, item.flags)
                2 -> addTypedValue(item.value, -1L, item.flags)
                3 -> {
                    if (item.freezeFrom != item.freezeTo) {
                        val lo = minOf(item.freezeFrom, item.freezeTo)
                        val hi = maxOf(item.freezeFrom, item.freezeTo)
                        item.value.coerceIn(lo, hi)
                    } else item.value
                }
                else -> item.value
            }
            write(item.address, item.size, nextVal)
            if (nextVal != item.value) {
                changedAny = true
                updated.add(item.copy(value = nextVal))
            } else {
                updated.add(item)
            }
        }
        return if (changedAny) updated else null
    }

    fun clearNativeFreezes() {
        activeBackend?.unlockAll()
    }

    fun copyMemory(fromAddress: Long, toAddress: Long, bytesCount: Long): Boolean {
        if (!attachedOk || pid <= 0) return false
        val safeLen = bytesCount.coerceIn(0L, 16L * 1024L * 1024L)
        if (safeLen <= 0L) return false
        return (activeBackend?.copyMemory(fromAddress, toAddress, safeLen) ?: 0L) > 0L
    }

    fun dumpMemory(startAddr: Long, endAddr: Long, fullPath: String): Boolean {
        if (!attachedOk || pid <= 0) return false
        val total = (endAddr - startAddr).coerceIn(0L, 64L * 1024L * 1024L)
        if (total <= 0L) return false
        val outFile = runCatching {
            val f = File(fullPath)
            f.parentFile?.mkdirs()
            f
        }.getOrElse {
            File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", fullPath.substringAfterLast('/'))
        }
        return (activeBackend?.dumpMemory(startAddr, endAddr, outFile.absolutePath) ?: 0L) > 0L
    }

    /** 将 Criteria 的地址区间与掩码下推到 C++ 引擎 */
    private fun pushAddressFilter(backend: MemoryBackend, minAddr: Long, maxAddr: Long, mask: Long, pattern: Long) {
        val nativeMax = if (maxAddr == Long.MAX_VALUE) 0L else maxAddr
        backend.setAddressFilter(minAddr, nativeMax, mask, pattern)
    }

    /**
     * 按需从底层 C++ MmapCandidateStore 补拉更多已存储候选（用于用户在筛选器中提高显示上限或启用条件筛选时）。
     */
    fun fetchStoredCandidates(
        limit: Int,
        defaultType: Int,
        useCandidateDt: Boolean = true,
        offset: Int = 0
    ): List<MemoryItem> {
        val backend = activeBackend ?: return emptyList()
        if (!attachedOk || pid <= 0 || !backend.supportsNativeScan) return emptyList()
        val cap = limit.coerceIn(1, MAX_CACHED_CANDIDATES)
        return fetchNativeCandidates(
            backend = backend,
            maxResults = cap,
            defaultType = defaultType,
            useCandidateDt = useCandidateDt,
            startOffset = offset.coerceAtLeast(0)
        ).first
    }

    /**
     * 从底层 C++ 后端统一拉取候选列表并组装为 `MemoryItem`（支持大批量移除后的分块跳过，不截断于 10000）
     */
    private fun fetchNativeCandidates(
        backend: MemoryBackend,
        maxResults: Int,
        defaultType: Int,
        useCandidateDt: Boolean,
        criteriaFilter: Criteria? = null,
        prevValueMap: Map<Long, Long>? = null,
        startOffset: Int = 0
    ): Pair<List<MemoryItem>, Long> {
        val hasRemoved = removedAddresses.isNotEmpty()
        var skippedRemoved = 0L

        val xorKey = criteriaFilter?.xorKey ?: 0L
        val xorOffset = criteriaFilter?.xorOffset ?: 0
        val encryptedAuto = (criteriaFilter?.encryptedXor == true) || ((defaultType and 0x7F) == ValueType.XOR)
        val hasCriteriaFilter = criteriaFilter != null && (
            criteriaFilter.memoryFrom != 0L ||
                criteriaFilter.memoryTo != Long.MAX_VALUE ||
                criteriaFilter.mask != 0L
            )

        val out = ArrayList<MemoryItem>(min(maxResults, 4096))
        var cursor = startOffset.coerceAtLeast(0)
        var guardIterations = 0
        val maxIterations = 64
        while (out.size < maxResults && guardIterations < maxIterations) {
            guardIterations++
            val remaining = maxResults - out.size
            val chunkSize = if (hasRemoved || hasCriteriaFilter) {
                (remaining + 4096).coerceIn(1024, MAX_CACHED_CANDIDATES)
            } else {
                remaining.coerceIn(1, MAX_CACHED_CANDIDATES)
            }
            val cands = backend.getCandidates(cursor, chunkSize)
            if (cands.isEmpty()) break
            cursor += cands.size
            for (cand in cands) {
                if (hasRemoved && cand.address in removedAddresses) {
                    skippedRemoved++
                    continue
                }
                if (hasCriteriaFilter && !addressMatchesRangeAndMask(cand.address, criteriaFilter!!)) {
                    continue
                }
                val dt = NativeMemBridge.fromNativeDataType(cand.meta and 0xFF)
                val regShift = (cand.meta ushr 8) and 0xFF
                val regCode = NativeMemBridge.fromNativeRegionFlag(1 shl regShift)
                val nativePtrFlags = (cand.meta ushr 16) and 0xFF
                val ptrKind = if (nativePtrFlags != 0) nativePtrFlags else classifyPointer(cand.rawValue)
                val prevVal = prevValueMap?.get(cand.address) ?: cand.rawValue
                out.add(
                    MemoryItem(
                        address = cand.address,
                        value = cand.rawValue,
                        previousValue = prevVal,
                        flags = if (useCandidateDt) dt else defaultType,
                        region = regCode,
                        pointer = ptrKind,
                        xorKey = xorKey,
                        xorOffset = xorOffset,
                        encryptedAuto = encryptedAuto
                    )
                )
                if (out.size >= maxResults) break
            }
            if (cands.size < chunkSize) break
        }
        return out to skippedRemoved
    }

    /**
     * 搜索：previous == null 时全量扫描；previous != null 时在已有候选集基础上改善 (Refine)
     */
    fun search(
        criteria: Criteria,
        previous: List<MemoryItem>? = null,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        scanCancelled.set(false)
        (activeBackend as? JvmProcBackend)?.resetCancel()

        val newSearch = previous == null
        if (newSearch) {
            removedAddresses.clear()
        }
        val current = previous.orEmpty()
        val fetchCap = criteria.limit.coerceIn(1, MAX_CACHED_CANDIDATES)
        val backend = activeBackend
        return if (backend != null && backend.supportsNativeScan) {
            searchViaNativeBackend(backend, criteria, criteria.selectedRegions, current, newSearch, fetchCap, onProgress)
        } else {
            val items = searchRealJvmFallback(criteria, criteria.selectedRegions, current, newSearch, fetchCap, onProgress)
            SearchResult(items, items.size.toLong(), cancelled = scanCancelled.get())
        }
    }

    /**
     * 模糊搜索：
     * - initial == true 时进行初始未知值快照采样
     * - initial == false 时对比每个候选地址的新旧数值并按 sign / delta / onlyNth 过滤（全路径原生 C++ 支持）
     */
    fun fuzzySearch(
        type: Int,
        sign: Int,
        delta: Long = 0L,
        onlyNth: Int = 0,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = MAX_CACHED_CANDIDATES,
        memoryFrom: Long = 0L,
        memoryTo: Long = Long.MAX_VALUE,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        scanCancelled.set(false)
        (activeBackend as? JvmProcBackend)?.resetCancel()

        val initial = previous == null
        val current = previous.orEmpty()
        val hasDelta = delta != 0L
        val fetchCap = limit.coerceIn(1, MAX_CACHED_CANDIDATES)
        val dummyCriteria = Criteria(
            type = type,
            sign = sign,
            memoryFrom = memoryFrom,
            memoryTo = memoryTo,
            selectedRegions = selectedRegions,
            limit = fetchCap
        )

        val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
        val size = ValueType.sizeOf(targetType)
        val nativeDt = NativeMemBridge.toNativeDataType(targetType)
        val backend = activeBackend

        if (initial) {
            removedAddresses.clear()
            if (backend != null && backend.supportsNativeScan) {
                val mask = NativeMemBridge.buildRegionMask(selectedRegions)
                backend.setRegionMask(mask)
                pushAddressFilter(backend, memoryFrom, memoryTo, 0L, 0L)
                val totalFound = backend.startUnknownSnapshot(nativeDt, onProgress)
                if (totalFound == MemoryBackend.CANCELLED_RESULT || scanCancelled.get()) {
                    return SearchResult(emptyList(), 0L, cancelled = true)
                }
                val (items, _) = fetchNativeCandidates(
                    backend = backend,
                    maxResults = fetchCap,
                    defaultType = targetType,
                    useCandidateDt = false,
                    criteriaFilter = dummyCriteria
                )
                return SearchResult(items, totalFound.coerceAtLeast(items.size.toLong()))
            }

            val c = dummyCriteria.copy(type = targetType, sign = ValueType.SIGN_EQUAL)
            val fallback = searchRealJvmFallback(c, selectedRegions, emptyList(), newSearch = true, maxResults = fetchCap, onProgress = onProgress)
            return SearchResult(fallback, fallback.size.toLong(), cancelled = scanCancelled.get())
        }

        if (backend != null && backend.supportsNativeScan) {
            pushAddressFilter(backend, memoryFrom, memoryTo, 0L, 0L)
            val deltaOp = when {
                hasDelta -> NativeMemBridge.OP_DELTA_EXACT
                (sign and ValueType.SIGN_MASK) == ValueType.SIGN_NOT_EQUAL -> NativeMemBridge.OP_CHANGED
                (sign and ValueType.SIGN_MASK) == ValueType.SIGN_GREATER_EQ -> NativeMemBridge.OP_INCREASED
                (sign and ValueType.SIGN_MASK) == ValueType.SIGN_LESS_EQ -> NativeMemBridge.OP_DECREASED
                else -> NativeMemBridge.OP_UNCHANGED
            }
            val totalFound = backend.refineFuzzy(deltaOp, delta, delta.toDouble(), 1e-4, onlyNth.coerceAtLeast(0), onProgress)
            if (totalFound == MemoryBackend.CANCELLED_RESULT || scanCancelled.get()) {
                return SearchResult(emptyList(), 0L, cancelled = true)
            }
            val prevMap = current.associate { it.address to it.value }
            val (items, skipped) = fetchNativeCandidates(
                backend = backend,
                maxResults = fetchCap,
                defaultType = targetType,
                useCandidateDt = false,
                criteriaFilter = dummyCriteria,
                prevValueMap = prevMap
            )
            return SearchResult(items, (totalFound - skipped).coerceAtLeast(items.size.toLong()))
        }

        var matchCounter = 0
        val refined = ArrayList<MemoryItem>()
        for (item in current) {
            if (scanCancelled.get()) return SearchResult(emptyList(), 0L, cancelled = true)
            if (!addressMatchesRangeAndMask(item.address, dummyCriteria)) continue
            val now = read(item.address, size)
            val pass = matchesFuzzyChange(now, item.value, item.flags and 0x7F, sign, delta, hasDelta)
            if (!pass) continue
            matchCounter++
            if (onlyNth > 0 && (matchCounter % onlyNth) != 0) continue
            refined.add(
                item.copy(
                    value = now,
                    previousValue = item.value,
                    pointer = classifyPointer(now)
                )
            )
        }
        return SearchResult(refined.take(fetchCap), refined.size.toLong())
    }

    /** 地址蒙版搜索：在选定内存区域或已有候选集中匹配 `(address & maskBits) == (addressPattern & maskBits)` */
    fun maskSearch(
        addressPattern: Long,
        maskBits: Long,
        type: Int,
        sign: Int,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = MAX_CACHED_CANDIDATES,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        scanCancelled.set(false)
        val targetType = if (type == ValueType.AUTO) ValueType.DWORD else type
        val size = ValueType.sizeOf(targetType).coerceAtLeast(1)
        val newSearch = previous == null
        val fetchCap = limit.coerceIn(1, MAX_CACHED_CANDIDATES)
        val backend = activeBackend

        // 若存在原生后端且为新搜索，直接利用 C++ 地址蒙版下推执行未知值快照扫描，零漏值且支持中断
        if (newSearch && backend != null && backend.supportsNativeScan) {
            removedAddresses.clear()
            val regMask = NativeMemBridge.buildRegionMask(selectedRegions)
            backend.setRegionMask(regMask)
            backend.setAddressFilter(0L, 0L, maskBits, addressPattern)
            val totalFound = backend.startUnknownSnapshot(NativeMemBridge.toNativeDataType(targetType), onProgress)
            backend.setAddressFilter(0L, 0L, 0L, 0L)
            if (totalFound == MemoryBackend.CANCELLED_RESULT || scanCancelled.get()) {
                return SearchResult(emptyList(), 0L, cancelled = true)
            }
            val (items, _) = fetchNativeCandidates(
                backend = backend,
                maxResults = fetchCap,
                defaultType = targetType,
                useCandidateDt = false
            )
            return SearchResult(items, totalFound.coerceAtLeast(items.size.toLong()))
        }

        if (!newSearch) {
            val prevList = previous.orEmpty()
            val matched = ArrayList<MemoryItem>()
            for (item in prevList) {
                if (scanCancelled.get()) return SearchResult(emptyList(), 0L, cancelled = true)
                if (item.region in selectedRegions && ((item.address and maskBits) == (addressPattern and maskBits))) {
                    val v = read(item.address, ValueType.sizeOf(item.flags and 0x7F))
                    matched.add(item.copy(value = v, previousValue = item.value, pointer = classifyPointer(v)))
                }
            }
            return SearchResult(matched.take(fetchCap), matched.size.toLong())
        }

        val targetMasked = addressPattern and maskBits
        val out = ArrayList<MemoryItem>(min(fetchCap, 2048))
        var totalFound = 0L
        val regs = regions().filter { it.code in selectedRegions && it.perms.startsWith("r") && it.size > 0 }
        for (reg in regs) {
            if (scanCancelled.get()) return SearchResult(emptyList(), 0L, cancelled = true)
            var addr = reg.start
            while (addr + size <= reg.end) {
                if (scanCancelled.get()) return SearchResult(emptyList(), 0L, cancelled = true)
                if ((addr and maskBits) == targetMasked) {
                    totalFound++
                    if (out.size < fetchCap) {
                        val v = read(addr, size)
                        out.add(
                            MemoryItem(
                                address = addr,
                                value = v,
                                previousValue = v,
                                flags = targetType,
                                region = reg.code,
                                pointer = classifyPointer(v)
                            )
                        )
                    }
                }
                addr += size
            }
        }
        return SearchResult(out, totalFound)
    }

    /** 指针链搜索（委托给 `MemoryBackend` 原生 C++20 `searchPointers` / `refinePointers`） */
    fun pointerSearch(
        targetAddress: Long,
        maxOffset: Long,
        type: Int,
        previous: List<MemoryItem>? = null,
        selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,
        limit: Int = MAX_CACHED_CANDIDATES,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        if (!attachedOk || pid <= 0) return SearchResult(emptyList(), 0L)
        scanCancelled.set(false)
        if (cachedRegions.isEmpty()) {
            regions()
        }
        val ptrType = if (type == ValueType.AUTO) {
            if (x64) ValueType.QWORD else ValueType.DWORD
        } else (type and 0x7F)
        val absOffset = abs(maxOffset).coerceAtLeast(0L)
        val fetchCap = limit.coerceIn(1, MAX_CACHED_CANDIDATES)
        val nativeDt = NativeMemBridge.toNativeDataType(ptrType)
        val backend = activeBackend

        if (backend != null && backend.supportsNativeScan) {
            val mask = NativeMemBridge.buildRegionMask(selectedRegions)
            backend.setRegionMask(mask)
            backend.setAddressFilter(0L, 0L, 0L, 0L)
            val totalFound: Long = if (previous == null) {
                removedAddresses.clear()
                backend.searchPointers(nativeDt, targetAddress, absOffset, NativeMemBridge.PTR_PROT_ALL, true, onProgress)
            } else {
                backend.refinePointers(nativeDt, targetAddress, absOffset, NativeMemBridge.PTR_PROT_ALL, onProgress)
            }
            if (totalFound == MemoryBackend.CANCELLED_RESULT || scanCancelled.get()) {
                return SearchResult(emptyList(), 0L, cancelled = true)
            }
            val prevMap = previous?.associate { it.address to it.value }
            val (items, skipped) = fetchNativeCandidates(
                backend = backend,
                maxResults = fetchCap,
                defaultType = ptrType,
                useCandidateDt = false,
                prevValueMap = prevMap
            )
            return SearchResult(items, (totalFound - skipped).coerceAtLeast(items.size.toLong()))
        }

        // 纯 JVM 回退路径
        val cleanTarget = if (ptrType == ValueType.DWORD) {
            targetAddress and 0xFFFFFFFFL
        } else {
            targetAddress and 0x00FFFFFFFFFFFFFFL
        }
        val lo = (cleanTarget - absOffset).coerceAtLeast(0x10000L)
        val hi = cleanTarget.coerceAtLeast(lo)

        fun acceptPointerCandidate(addr: Long, rawVal: Long): Int {
            if ((addr and 3L) != 0L) return 0
            val cleanVal = if (ptrType == ValueType.DWORD) (rawVal and 0xFFFFFFFFL) else (rawVal and 0x00FFFFFFFFFFFFFFL)
            if (cleanVal !in lo..hi) return 0
            val cleanAddr = if (ptrType == ValueType.DWORD) (addr and 0xFFFFFFFFL) else (addr and 0x00FFFFFFFFFFFFFFL)
            if (cleanVal == cleanAddr) return 0
            return classifyPointer(rawVal)
        }

        if (previous == null) {
            removedAddresses.clear()
            val c = Criteria(
                type = ptrType,
                value = lo,
                from = lo,
                to = hi,
                isRange = true,
                notRange = false,
                sign = ValueType.SIGN_EQUAL,
                selectedRegions = selectedRegions,
                limit = fetchCap * 2
            )
            val fallback = searchRealJvmFallback(c, selectedRegions, emptyList(), newSearch = true, maxResults = fetchCap * 2, onProgress = onProgress)
                .filter { acceptPointerCandidate(it.address, it.value) != 0 }
                .take(fetchCap)
            return SearchResult(fallback, fallback.size.toLong(), cancelled = scanCancelled.get())
        }

        val itemSz = ValueType.sizeOf(ptrType)
        val filteredPrev = previous.filter { it.region in selectedRegions && it.address !in removedAddresses }
        val rawMap = batchReadRawValues(filteredPrev.map { it.address to itemSz })
        val matched = ArrayList<MemoryItem>()
        for ((idx, item) in filteredPrev.withIndex()) {
            if (scanCancelled.get()) return SearchResult(emptyList(), 0L, cancelled = true)
            val v = rawMap[item.address] ?: read(item.address, itemSz)
            val ptrKind = acceptPointerCandidate(item.address, v)
            if (ptrKind != 0) {
                matched.add(item.copy(value = v, previousValue = item.value, flags = ptrType, pointer = ptrKind))
            }
            if ((idx and 0xFF) == 0 || idx + 1 == filteredPrev.size) {
                onProgress?.invoke((idx + 1).toLong(), filteredPrev.size.coerceAtLeast(1).toLong(), matched.size.toLong())
            }
        }
        return SearchResult(matched.take(fetchCap), matched.size.toLong())
    }

    /**
     * 统一驱动 `MemoryBackend` 原生 C++20 引擎执行扫描与改善 (Refine)
     */
    private fun searchViaNativeBackend(
        backend: MemoryBackend,
        c: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): SearchResult {
        val regMask = NativeMemBridge.buildRegionMask(regions)
        backend.setRegionMask(regMask)
        pushAddressFilter(backend, c.memoryFrom, c.memoryTo, c.mask, c.maskPattern)

        val nativeDt = NativeMemBridge.toNativeDataType(c.type)
        val fVal1 = when (c.type and 0x7F) {
            ValueType.FLOAT -> Float.fromBits(c.from.toInt()).toDouble()
            ValueType.DOUBLE -> Double.fromBits(c.from)
            else -> c.from.toDouble()
        }
        val fVal2 = when (c.type and 0x7F) {
            ValueType.FLOAT -> Float.fromBits(c.to.toInt()).toDouble()
            ValueType.DOUBLE -> Double.fromBits(c.to)
            else -> c.to.toDouble()
        }

        val isXorType = (c.type and 0x7F) == ValueType.XOR
        val useEncryptedAuto = c.encryptedXor || isXorType

        fun maxIntBoundFor(typeFlag: Int, lowerVal: Long): Long = when (typeFlag and 0x7F) {
            ValueType.BYTE -> if (lowerVal > Byte.MAX_VALUE) 255L else Byte.MAX_VALUE.toLong()
            ValueType.WORD -> if (lowerVal > Short.MAX_VALUE) 65535L else Short.MAX_VALUE.toLong()
            ValueType.DWORD, ValueType.XOR -> if (lowerVal > Int.MAX_VALUE) 4294967295L else Int.MAX_VALUE.toLong()
            else -> Long.MAX_VALUE
        }

        fun minIntBoundFor(typeFlag: Int, upperVal: Long): Long = when (typeFlag and 0x7F) {
            ValueType.BYTE -> if (upperVal > Byte.MAX_VALUE) 0L else Byte.MIN_VALUE.toLong()
            ValueType.WORD -> if (upperVal > Short.MAX_VALUE) 0L else Short.MIN_VALUE.toLong()
            ValueType.DWORD, ValueType.XOR -> if (upperVal > Int.MAX_VALUE) 0L else Int.MIN_VALUE.toLong()
            else -> Long.MIN_VALUE
        }

        val sign = c.sign and ValueType.SIGN_MASK
        val effectiveExcludeRange = c.notRange xor (sign == ValueType.SIGN_NOT_EQUAL)

        val totalFound: Long = if (newSearch) {
            when {
                c.isText && c.textBytes.isNotEmpty() -> {
                    val maskStr = "x".repeat(c.textBytes.size)
                    backend.searchPattern(c.textBytes, maskStr, onProgress)
                }
                c.orderedGroup.size > 1 -> {
                    val count = c.orderedGroup.size
                    val typesArr = IntArray(count) { i ->
                        NativeMemBridge.toNativeDataType(c.groupTypes.getOrElse(i) { c.type })
                    }
                    val intArr = LongArray(count) { i -> c.orderedGroup[i] }
                    val fltArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.orderedGroup[i]
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    val intMaxArr = LongArray(count) { i -> c.groupTo.getOrElse(i) { c.orderedGroup[i] } }
                    val fltMaxArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.groupTo.getOrElse(i) { c.orderedGroup[i] }
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    val opsArr = IntArray(count) { i -> c.groupOps.getOrElse(i) { 0 } }
                    val span = c.area.coerceIn(2, 65536)
                    backend.searchGroup(typesArr, intArr, fltArr, intMaxArr, fltMaxArr, opsArr, span, c.ordered, true, onProgress)
                }
                c.xorOffset != 0 -> {
                    backend.searchEncoded(nativeDt, NativeMemBridge.ENC_XOR_OFFSET, c.value, fVal1, c.xorOffset.toLong(), true, onProgress)
                }
                c.xorKey != 0L -> {
                    backend.searchEncoded(nativeDt, NativeMemBridge.ENC_XOR_STATIC, c.value, fVal1, c.xorKey, true, onProgress)
                }
                useEncryptedAuto -> {
                    backend.searchEncoded(nativeDt, NativeMemBridge.ENC_GG_AUTO, c.value, fVal1, 0L, true, onProgress)
                }
                c.isRange -> {
                    val iLo = minOf(c.from, c.to)
                    val iHi = maxOf(c.from, c.to)
                    val fLo = minOf(fVal1, fVal2)
                    val fHi = maxOf(fVal1, fVal2)
                    backend.searchRange(nativeDt, iLo, iHi, fLo, fHi, true, true, effectiveExcludeRange, onProgress)
                }
                sign == ValueType.SIGN_NOT_EQUAL -> {
                    backend.searchNotEqual(nativeDt, c.value, fVal1, 1e-4, true, onProgress)
                }
                sign == ValueType.SIGN_GREATER_EQ -> {
                    val maxInt = maxIntBoundFor(c.type, c.value)
                    backend.searchRange(nativeDt, c.value, maxInt, fVal1, Double.MAX_VALUE, true, true, false, onProgress)
                }
                sign == ValueType.SIGN_LESS_EQ -> {
                    val minInt = minIntBoundFor(c.type, c.value)
                    backend.searchRange(nativeDt, minInt, c.value, -Double.MAX_VALUE, fVal1, true, true, false, onProgress)
                }
                sign == ValueType.SIGN_EQUAL -> {
                    backend.searchExact(nativeDt, c.value, fVal1, 1e-4, true, onProgress)
                }
                else -> {
                    val fallback = searchRealJvmFallback(c, regions, current, true, maxResults, onProgress)
                    return SearchResult(fallback, fallback.size.toLong(), cancelled = scanCancelled.get())
                }
            }
        } else {
            when {
                c.orderedGroup.size > 1 -> {
                    val count = c.orderedGroup.size
                    val typesArr = IntArray(count) { i ->
                        NativeMemBridge.toNativeDataType(c.groupTypes.getOrElse(i) { c.type })
                    }
                    val intArr = LongArray(count) { i -> c.orderedGroup[i] }
                    val fltArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.orderedGroup[i]
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    val intMaxArr = LongArray(count) { i -> c.groupTo.getOrElse(i) { c.orderedGroup[i] } }
                    val fltMaxArr = DoubleArray(count) { i ->
                        val gt = c.groupTypes.getOrElse(i) { c.type } and 0x7F
                        val raw = c.groupTo.getOrElse(i) { c.orderedGroup[i] }
                        when (gt) {
                            ValueType.FLOAT -> Float.fromBits(raw.toInt()).toDouble()
                            ValueType.DOUBLE -> Double.fromBits(raw)
                            else -> raw.toDouble()
                        }
                    }
                    val opsArr = IntArray(count) { i -> c.groupOps.getOrElse(i) { 0 } }
                    val span = c.area.coerceIn(2, 65536)
                    backend.refineGroup(typesArr, intArr, fltArr, intMaxArr, fltMaxArr, opsArr, span, c.ordered, true, onProgress)
                }
                c.xorOffset != 0 -> {
                    backend.refineEncoded(nativeDt, NativeMemBridge.ENC_XOR_OFFSET, c.value, fVal1, c.xorOffset.toLong(), onProgress)
                }
                c.xorKey != 0L -> {
                    backend.refineEncoded(nativeDt, NativeMemBridge.ENC_XOR_STATIC, c.value, fVal1, c.xorKey, onProgress)
                }
                useEncryptedAuto -> {
                    backend.refineEncoded(nativeDt, NativeMemBridge.ENC_GG_AUTO, c.value, fVal1, 0L, onProgress)
                }
                c.isRange -> {
                    val iLo = minOf(c.from, c.to)
                    val iHi = maxOf(c.from, c.to)
                    val fLo = minOf(fVal1, fVal2)
                    val fHi = maxOf(fVal1, fVal2)
                    backend.refineRange(nativeDt, iLo, iHi, fLo, fHi, true, effectiveExcludeRange, onProgress)
                }
                !c.isText && sign == ValueType.SIGN_NOT_EQUAL -> {
                    backend.refineNotEqual(nativeDt, c.value, fVal1, 1e-4, onProgress)
                }
                !c.isText && sign == ValueType.SIGN_GREATER_EQ -> {
                    val maxInt = maxIntBoundFor(c.type, c.value)
                    backend.refineRange(nativeDt, c.value, maxInt, fVal1, Double.MAX_VALUE, true, false, onProgress)
                }
                !c.isText && sign == ValueType.SIGN_LESS_EQ -> {
                    val minInt = minIntBoundFor(c.type, c.value)
                    backend.refineRange(nativeDt, minInt, c.value, -Double.MAX_VALUE, fVal1, true, false, onProgress)
                }
                !c.isText && sign == ValueType.SIGN_EQUAL -> {
                    backend.refineExact(nativeDt, c.value, fVal1, 1e-4, onProgress)
                }
                else -> {
                    val refined = refineLoadedCandidates(c, current, maxResults, onProgress)
                    return SearchResult(refined, refined.size.toLong(), cancelled = scanCancelled.get())
                }
            }
        }

        if (totalFound == MemoryBackend.CANCELLED_RESULT || scanCancelled.get()) {
            return SearchResult(emptyList(), 0L, cancelled = true)
        }

        val prevMap = if (newSearch) null else current.associate { it.address to it.value }
        val (out, skippedRemoved) = fetchNativeCandidates(
            backend = backend,
            maxResults = maxResults,
            defaultType = c.type,
            useCandidateDt = (c.type == ValueType.AUTO || c.orderedGroup.size > 1),
            criteriaFilter = c,
            prevValueMap = prevMap
        )
        val effectiveTotal = (totalFound - skippedRemoved).coerceAtLeast(out.size.toLong())
        return SearchResult(out, effectiveTotal)
    }

    private fun refineLoadedCandidates(
        criteria: Criteria,
        current: List<MemoryItem>,
        maxResults: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): List<MemoryItem> {
        val eligible = current.filter { it.address !in removedAddresses && addressMatchesRangeAndMask(it.address, criteria) }
        if (eligible.isEmpty()) {
            onProgress?.invoke(1L, 1L, 0L)
            return emptyList()
        }
        val out = ArrayList<MemoryItem>(min(maxResults, eligible.size))
        val prevMap = current.associate { it.address to it.value }
        val total = eligible.size.toLong()
        var processed = 0L

        for (chunk in eligible.chunked(512)) {
            if (scanCancelled.get()) return emptyList()
            val batchReq = chunk.map { item ->
                item.address to ValueType.sizeOf(item.flags and 0x7F).coerceIn(1, 8)
            }
            val batchVals = batchReadRawValues(batchReq)
            for (item in chunk) {
                val itemType = item.flags and 0x7F
                val itemSize = ValueType.sizeOf(itemType).coerceIn(1, 8)
                val v = batchVals[item.address] ?: read(item.address, itemSize)
                if (matches(v, criteria.copy(type = itemType), prevMap, item.address)) {
                    out.add(
                        item.copy(
                            value = v,
                            previousValue = item.value,
                            pointer = classifyPointer(v),
                            xorKey = criteria.xorKey,
                            xorOffset = criteria.xorOffset,
                            encryptedAuto = criteria.encryptedXor || itemType == ValueType.XOR
                        )
                    )
                    if (out.size >= maxResults) break
                }
            }
            processed += chunk.size
            onProgress?.invoke(processed, total, out.size.toLong())
            if (out.size >= maxResults) break
        }
        return out
    }

    // ---------------- 真实进程 JVM 回退与 VMA 解析实现 ----------------

    private data class Seg(
        val start: Long,
        val end: Long,
        val perms: String,
        val code: String,
        val path: String,
        val prevPathNext: String = ""
    )

    private fun parseMapLine(line: String, prevFileBackedPath: String = ""): Seg? {
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 5) return null
        val range = parts[0].split('-')
        if (range.size != 2) return null
        val start = range[0].toULongOrNull(16)?.toLong() ?: return null
        val end = range[1].toULongOrNull(16)?.toLong() ?: return null
        if (end <= start) return null
        val perms = parts[1]
        val inode = parts[4].toLongOrNull() ?: 0L
        val rawPath = if (parts.size >= 6) parts[5] else ""
        val path = rawPath.removeSuffix(" (deleted)")
        val code = classifyMapRegion(perms, path, inode, prevFileBackedPath)
        val nextPrev = if (path.startsWith("/")) path else if (code == "Cb") prevFileBackedPath else ""
        return Seg(start, end, perms, code, path, nextPrev)
    }

    /**
     * 15 类细分内存区域判定（严格对齐原版 GG 与 C++20 MapsParser::classifyVma）：
     * Ch, Jh, Ca, Cd, Cb, Ps, A, J, S, As, V, O, B, Xa, Xs
     */
    private fun classifyMapRegion(
        perms: String,
        path: String,
        inode: Long,
        prevFileBackedPath: String
    ): String {
        val readable = perms.startsWith('r')
        val writable = perms.length > 1 && perms[1] == 'w'
        val executable = perms.length > 2 && perms[2] == 'x'
        val isPrivate = perms.length > 3 && perms[3] == 'p'
        val p = path

        if (p.contains("PPSSPP", true) || p.contains("psp_ram", true) || p.contains("psp_vram", true)) {
            return "Ps"
        }

        if (p.endsWith(".ttf", true) || p.endsWith(".otf", true) ||
            p.endsWith(".ttc", true) || p.endsWith(".woff", true) ||
            "/fonts/" in p || "/system/usr/icu/" in p ||
            "/apex/com.android.i18n/etc/icu/" in p || "icudt" in p ||
            "hyphen-data" in p || p.endsWith(".hyb", true) ||
            p == "[vvar]" || p == "[vectors]" || p == "[sigpage]" ||
            p == "[uprobes]" || p == "[kuserhelpers]" ||
            p.startsWith("/dev/binder") || p.startsWith("/dev/hwbinder") ||
            p.startsWith("/dev/vndbinder") || p.startsWith("/dev/__properties__") ||
            p.startsWith("/dev/event-log-tags") || p.startsWith("/dev/pmsg") ||
            p.startsWith("/dev/kmsg") || p.startsWith("/dev/null") ||
            p.startsWith("/dev/urandom") || p.startsWith("/dev/random") ||
            p.startsWith("/dev/cpuctl") || p.startsWith("/dev/cpuset") ||
            p.startsWith("/dev/stune") || p.startsWith("/dev/blkio") ||
            p.startsWith("[anon:GWP-ASan Guard")
        ) {
            return "B"
        }
        if (!readable) {
            return "O"
        }

        if ("kgsl" in p || "/dev/mali" in p || "/dev/pvr" in p ||
            "/dev/nvhost" in p || "/dev/ion" in p || "/dev/dma_heap" in p ||
            "/dev/dri/" in p || "gralloc" in p || "/dev/ashmem/GFX" in p ||
            "dmabuf" in p || "[anon:gralloc" in p
        ) {
            return "V"
        }

        if (executable) {
            if (p == "[vdso]" || p == "[vsyscall]" ||
                p.startsWith("/memfd:jit-cache") || p.startsWith("/memfd:jit-zygote-cache") ||
                "jit-code-cache" in p || "jit-zygote-cache" in p
            ) {
                return "Xs"
            }
            if (p.startsWith("/data/dalvik-cache/")) {
                val isUserAppDalvik = "data@app@" in p || "mnt@expand@" in p || "data@data@" in p
                return if (isUserAppDalvik) "Xa" else "Xs"
            }
            val isAppCode = p.startsWith("/data/app/") || p.startsWith("/data/data/") ||
                p.startsWith("/data/user/") || p.startsWith("/data/user_de/") ||
                p.startsWith("/mnt/asec/") || p.startsWith("/mnt/expand/") ||
                p.startsWith("/sdcard/") || p.startsWith("/storage/")
            if (isAppCode) return "Xa"
            if (p.isEmpty() || p.startsWith("[anon:")) {
                return if (writable) "Xa" else "Xs"
            }
            return "Xs"
        }

        if (p == "[stack]" || p.startsWith("[stack:") ||
            p.startsWith("[anon:stack_and_tls") || p.startsWith("[anon:thread signal stack") ||
            p.startsWith("[anon:bionic_tls")
        ) {
            return "S"
        }

        if (p == "[heap]" || p.startsWith("[heap:")) {
            return "Ch"
        }

        if ("dalvik-" in p || p.startsWith("[anon:dalvik-") ||
            p.startsWith("/dev/ashmem/dalvik-") || p.startsWith("/memfd:dalvik-") ||
            p.startsWith("[anon:art_") || p.startsWith("/memfd:jit-")
        ) {
            val isJavaMetadataOrJit = "bitmap" in p || "mark-stack" in p ||
                "allocation-stack" in p || "live-stack" in p ||
                "card-table" in p || "card table" in p ||
                "mod-union" in p || "LinearAlloc" in p ||
                "indirect-ref" in p || "reference table" in p ||
                "aux-structure" in p || "CompilerMetadata" in p ||
                "jit" in p ||
                "image space" in p || "intern table" in p || "class table" in p
            if (isJavaMetadataOrJit) return "J"

            val isObjectAllocHeap = "main space" in p || "alloc space" in p ||
                "region space" in p || "large object" in p ||
                "non moving space" in p || "zygote space" in p ||
                "bump-pointer" in p || "rosalloc" in p ||
                "dlmalloc" in p || "free list" in p
            return if (writable && isObjectAllocHeap) "Jh" else "J"
        }

        if (p.endsWith(".art") || p.endsWith(".vdex") || p.endsWith(".dex") || p.endsWith(".jar")) {
            return "J"
        }

        if ("libc_malloc" in p || "scudo:" in p || "jemalloc" in p ||
            "GWP-ASan" in p || "bionic_alloc" in p || "malloc" in p
        ) {
            return "Ca"
        }

        if (p == "[anon:.bss]" || p.startsWith("[anon:.bss") || p.startsWith("[anon:bss")) {
            return "Cb"
        }
        val prevIsNativeElf = prevFileBackedPath.isNotEmpty() &&
            (prevFileBackedPath.endsWith(".so") || ".so!" in prevFileBackedPath ||
                prevFileBackedPath.endsWith(".apk") ||
                prevFileBackedPath.startsWith("/system/bin/") || prevFileBackedPath.startsWith("/apex/")) &&
            !prevFileBackedPath.endsWith(".art") && !prevFileBackedPath.endsWith(".oat") &&
            !prevFileBackedPath.endsWith(".dex") && !prevFileBackedPath.endsWith(".vdex")
        if (p.isEmpty() && writable && isPrivate && prevIsNativeElf) {
            return "Cb"
        }

        if ((p.startsWith("/dev/ashmem") && "dalvik" !in p && "jit" !in p) ||
            p.startsWith("[anon:ashmem") || p.startsWith("/memfd:ashmem")
        ) {
            return "As"
        }

        if (p.isEmpty() || p.startsWith("[anon:")) {
            return if (writable && isPrivate) "A" else "O"
        }

        val isNativeElfData = (p.endsWith(".so") || ".so!" in p || p.endsWith(".apk") ||
            p.startsWith("/system/bin/") || p.startsWith("/vendor/bin/") || p.startsWith("/apex/")) &&
            !p.endsWith(".art") && !p.endsWith(".oat") && !p.endsWith(".dex") &&
            !p.endsWith(".vdex") && !p.endsWith(".jar")
        if (writable && isPrivate && isNativeElfData) {
            return "Cd"
        }

        return "O"
    }

    private fun searchRealJvmFallback(
        criteria: Criteria,
        regions: Set<String>,
        current: List<MemoryItem>,
        newSearch: Boolean,
        maxResults: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): List<MemoryItem> {
        if (!newSearch && current.isNotEmpty()) {
            return refineLoadedCandidates(criteria, current, maxResults, onProgress)
        }

        val targetType = if (criteria.type == ValueType.AUTO) ValueType.DWORD else criteria.type
        val size = ValueType.sizeOf(targetType)
        val out = ArrayList<MemoryItem>(min(maxResults, 2048))

        // 移除 64MB 单段硬上限，改按 64KB 块流式扫描任意大小的合法可读 VMA 段
        val segs = regions()
            .filter { it.code in regions && it.perms.startsWith("r") && it.size > 0L }

        val totalBytes = segs.sumOf { it.size }.coerceAtLeast(1L)
        var scannedBytes = 0L
        val buffer = ByteArray(64 * 1024 + 16)
        runCatching {
            RandomAccessFile("/proc/$pid/mem", "r").use { f ->
                for (seg in segs) {
                    if (scanCancelled.get()) return emptyList()
                    var segStart = seg.start
                    var segEnd = seg.end
                    if (criteria.memoryFrom != 0L && java.lang.Long.compareUnsigned(segEnd, criteria.memoryFrom) <= 0) {
                        scannedBytes += seg.size
                        continue
                    }
                    if (criteria.memoryTo != Long.MAX_VALUE && java.lang.Long.compareUnsigned(segStart, criteria.memoryTo) > 0) {
                        scannedBytes += seg.size
                        continue
                    }
                    if (criteria.memoryFrom != 0L && java.lang.Long.compareUnsigned(segStart, criteria.memoryFrom) < 0) {
                        segStart = criteria.memoryFrom
                    }
                    if (criteria.memoryTo != Long.MAX_VALUE && java.lang.Long.compareUnsigned(segEnd, criteria.memoryTo) > 0) {
                        segEnd = criteria.memoryTo
                    }
                    var offset = segStart
                    while (offset < segEnd && out.size < maxResults) {
                        if (scanCancelled.get()) return emptyList()
                        val logicalChunk = min(65536L, segEnd - offset).toInt()
                        val readLen = min(buffer.size.toLong(), segEnd - offset).toInt()
                        val read = try {
                            f.seek(offset)
                            f.read(buffer, 0, readLen)
                        } catch (_: Throwable) {
                            -1
                        }
                        if (read <= 0) {
                            offset += 4096L
                            scannedBytes += 4096L
                            continue
                        }

                        if (criteria.isText && criteria.textBytes.isNotEmpty()) {
                            val pat = criteria.textBytes
                            val limit = min(logicalChunk, read - pat.size + 1)
                            var i = 0
                            while (i < limit && out.size < maxResults) {
                                var matched = true
                                for (k in pat.indices) {
                                    if (buffer[i + k] != pat[k]) {
                                        matched = false
                                        break
                                    }
                                }
                                if (matched && addressMatchesRangeAndMask(offset + i, criteria)) {
                                    var v = 0L
                                    for (b in 0 until min(4, pat.size)) {
                                        v = v or ((pat[b].toLong() and 0xFFL) shl (b * 8))
                                    }
                                    out.add(
                                        MemoryItem(
                                            address = offset + i,
                                            value = v,
                                            previousValue = v,
                                            flags = if (criteria.textUtf16) ValueType.WORD else ValueType.BYTE,
                                            region = seg.code
                                        )
                                    )
                                }
                                i++
                            }
                        } else {
                            var i = 0
                            val limit = min(logicalChunk, read - size + 1)
                            while (i < limit && out.size < maxResults) {
                                val candidateAddr = offset + i
                                if (addressMatchesRangeAndMask(candidateAddr, criteria)) {
                                    var v = 0L
                                    for (b in 0 until size) {
                                        v = v or ((buffer[i + b].toLong() and 0xFFL) shl (b * 8))
                                    }
                                    if (matches(v, criteria.copy(type = targetType), emptyMap(), candidateAddr)) {
                                        out.add(
                                            MemoryItem(
                                                address = candidateAddr,
                                                value = v,
                                                previousValue = v,
                                                flags = targetType,
                                                region = seg.code,
                                                pointer = classifyPointer(v),
                                                xorKey = criteria.xorKey,
                                                xorOffset = criteria.xorOffset,
                                                encryptedAuto = criteria.encryptedXor || targetType == ValueType.XOR
                                            )
                                        )
                                    }
                                }
                                i += size
                            }
                        }
                        offset += logicalChunk
                        scannedBytes += logicalChunk
                        onProgress?.invoke(scannedBytes.coerceAtMost(totalBytes), totalBytes, out.size.toLong())
                    }
                }
            }
        }
        return out
    }

    private fun addressMatchesRangeAndMask(addr: Long, c: Criteria): Boolean {
        if (c.memoryFrom != 0L && java.lang.Long.compareUnsigned(addr, c.memoryFrom) < 0) return false
        if (c.memoryTo != Long.MAX_VALUE && java.lang.Long.compareUnsigned(addr, c.memoryTo) > 0) return false
        if (c.mask != 0L && (addr and c.mask) != (c.maskPattern and c.mask)) return false
        return true
    }

    /** 核心数值匹配函数（支持有符号整数、无符号正整数范围及 IEEE-754 浮点数比较） */
    private fun matches(v: Long, c: Criteria, prevMap: Map<Long, Long>, addr: Long): Boolean {
        val itemSize = ValueType.sizeOf(if (c.type == ValueType.AUTO) ValueType.DWORD else (c.type and 0x7F))
        val logicalVal = when {
            c.xorOffset != 0 -> {
                val keyAtOff = read(addr + c.xorOffset, itemSize)
                v xor keyAtOff
            }
            c.xorKey != 0L -> v xor c.xorKey
            c.encryptedXor -> {
                val keyAfter = read(addr + itemSize, itemSize)
                v xor keyAfter
            }
            else -> v
        }
        val evalType = if (c.type == ValueType.AUTO) ValueType.DWORD else (c.type and 0x7F)
        val sign = c.sign and ValueType.SIGN_MASK

        if (c.isRange) {
            val cmp1 = compareTypedValues(logicalVal, c.from, evalType)
            val cmp2 = compareTypedValues(logicalVal, c.to, evalType)
            val loCmp = minOf(cmp1, cmp2)
            val hiCmp = maxOf(cmp1, cmp2)
            val inRange = (cmp1 >= 0 && cmp2 <= 0) || (cmp1 <= 0 && cmp2 >= 0) || (loCmp <= 0 && hiCmp >= 0)
            val baseMatch = if (c.notRange) !inRange else inRange
            return when (sign) {
                ValueType.SIGN_NOT_EQUAL -> !baseMatch
                else -> baseMatch
            }
        }

        val cmp = compareTypedValues(logicalVal, c.value, evalType)
        return when (sign) {
            ValueType.SIGN_EQUAL -> cmp == 0
            ValueType.SIGN_NOT_EQUAL -> cmp != 0
            ValueType.SIGN_GREATER_EQ -> cmp >= 0
            ValueType.SIGN_LESS_EQ -> cmp <= 0
            else -> {
                val prev = prevMap[addr] ?: return true
                compareTypedValues(logicalVal, prev, evalType) != 0
            }
        }
    }

    /** 按数据类型正确比较两个原始位模式 (raw bits)，兼容原版 GG 的无符号正数与有符号负数语义 */
    fun compareTypedValues(rawA: Long, rawB: Long, type: Int): Int {
        return when (type and 0x7F) {
            ValueType.FLOAT -> {
                val fa = Float.fromBits(rawA.toInt())
                val fb = Float.fromBits(rawB.toInt())
                if (fa.isNaN() || fb.isNaN()) return -1
                if (abs(fa - fb) <= 1e-4f) 0 else fa.compareTo(fb)
            }
            ValueType.DOUBLE -> {
                val da = Double.fromBits(rawA)
                val db = Double.fromBits(rawB)
                if (da.isNaN() || db.isNaN()) return -1
                if (abs(da - db) <= 1e-6) 0 else da.compareTo(db)
            }
            ValueType.BYTE -> {
                if (rawB > Byte.MAX_VALUE) {
                    val ua = rawA and 0xFFL
                    val ub = rawB and 0xFFL
                    ua.compareTo(ub)
                } else {
                    val sa = rawA.toByte().toLong()
                    val sb = rawB.toByte().toLong()
                    sa.compareTo(sb)
                }
            }
            ValueType.WORD -> {
                if (rawB > Short.MAX_VALUE) {
                    val ua = rawA and 0xFFFFL
                    val ub = rawB and 0xFFFFL
                    ua.compareTo(ub)
                } else {
                    val sa = rawA.toShort().toLong()
                    val sb = rawB.toShort().toLong()
                    sa.compareTo(sb)
                }
            }
            ValueType.DWORD, ValueType.XOR -> {
                if (rawB > Int.MAX_VALUE) {
                    val ua = rawA and 0xFFFFFFFFL
                    val ub = rawB and 0xFFFFFFFFL
                    ua.compareTo(ub)
                } else {
                    val sa = rawA.toInt().toLong()
                    val sb = rawB.toInt().toLong()
                    sa.compareTo(sb)
                }
            }
            else -> rawA.compareTo(rawB)
        }
    }

    private fun matchesFuzzyChange(
        newRaw: Long,
        oldRaw: Long,
        type: Int,
        sign: Int,
        delta: Long,
        hasDelta: Boolean
    ): Boolean {
        val targetOld = if (hasDelta && delta != 0L) addTypedValue(oldRaw, delta, type) else oldRaw
        val cmp = compareTypedValues(newRaw, targetOld, type)
        return when (sign and ValueType.SIGN_MASK) {
            ValueType.SIGN_EQUAL -> cmp == 0
            ValueType.SIGN_NOT_EQUAL -> cmp != 0
            ValueType.SIGN_GREATER_EQ -> if (hasDelta) cmp > 0 else compareTypedValues(newRaw, oldRaw, type) > 0
            ValueType.SIGN_LESS_EQ -> if (hasDelta) cmp < 0 else compareTypedValues(newRaw, oldRaw, type) < 0
            else -> true
        }
    }

    private fun addTypedValue(raw: Long, delta: Long, type: Int): Long {
        return when (type and 0x7F) {
            ValueType.FLOAT -> {
                val f = Float.fromBits(raw.toInt()) + delta.toFloat()
                f.toRawBits().toLong() and 0xFFFFFFFFL
            }
            ValueType.DOUBLE -> {
                val d = Double.fromBits(raw) + delta.toDouble()
                d.toRawBits()
            }
            ValueType.BYTE -> ((raw.toByte() + delta.toInt()) and 0xFF).toLong()
            ValueType.WORD -> ((raw.toShort() + delta.toInt()) and 0xFFFF).toLong()
            ValueType.DWORD, ValueType.XOR -> ((raw.toInt() + delta.toInt()).toLong() and 0xFFFFFFFFL)
            else -> raw + delta
        }
    }

    // ============ 硬件断点调试器 (Perf / Ptrace，兼容 Root 与免 Root 同 UID) ============

    fun bpAdd(
        address: Long,
        bpType: com.littlecodesauce.ggnext.model.BpType,
        sizeBytes: Int,
        bpMode: com.littlecodesauce.ggnext.model.BpMode,
        captureCallstack: Boolean
    ): Pair<Int, String> {
        if (!attachedOk || pid <= 0) return -1 to "请先附加目标进程"
        if (rootDaemonActive) {
            return RootMemDaemon.bpAdd(address, bpType.code, sizeBytes, bpMode.code, captureCallstack)
        }
        val h = NativeMemBridge.ensureHandle()
        if (h == 0L) return -1 to "原生调试引擎未就绪"
        val id = NativeMemBridge.nativeBpAdd(h, pid, address, bpType.code, sizeBytes, bpMode.code, captureCallstack)
        return if (id > 0) id to "" else -1 to "添加断点失败（免 Root 模式下请确认目标同 UID 且选择 Ptrace 模式）"
    }

    fun bpRemove(bpId: Int): Boolean {
        if (rootDaemonActive) return RootMemDaemon.bpRemove(bpId)
        val h = NativeMemBridge.currentHandle()
        return h != 0L && NativeMemBridge.nativeBpRemove(h, bpId)
    }

    fun bpSetPaused(bpId: Int, paused: Boolean): Boolean {
        if (rootDaemonActive) return RootMemDaemon.bpSetPaused(bpId, paused)
        val h = NativeMemBridge.currentHandle()
        return h != 0L && NativeMemBridge.nativeBpSetPaused(h, bpId, paused)
    }

    fun bpSetAllPaused(paused: Boolean): Boolean {
        if (rootDaemonActive) return RootMemDaemon.bpSetAllPaused(paused)
        val h = NativeMemBridge.currentHandle()
        return h != 0L && NativeMemBridge.nativeBpSetPaused(h, 0, paused)
    }

    fun bpClearHits(bpId: Int = 0) {
        if (rootDaemonActive) {
            RootMemDaemon.bpClearHits(bpId)
        } else {
            val h = NativeMemBridge.currentHandle()
            if (h != 0L) NativeMemBridge.nativeBpClearHits(h, bpId)
        }
    }

    fun bpStopAll() {
        if (rootDaemonActive) {
            RootMemDaemon.bpStopAll()
        } else {
            val h = NativeMemBridge.currentHandle()
            if (h != 0L) NativeMemBridge.nativeBpStopAll(h)
        }
    }

    fun bpSnapshot(): List<com.littlecodesauce.ggnext.model.BpSession> {
        val json = if (rootDaemonActive) {
            RootMemDaemon.bpSnapshotJson()
        } else {
            val h = NativeMemBridge.currentHandle()
            if (h != 0L) NativeMemBridge.nativeBpSnapshotJson(h) else "[]"
        }
        return com.littlecodesauce.ggnext.model.BpSnapshotParser.parse(json) { arch, pc, insnHex ->
            AsmEngineManager.disassembleHex(arch, pc, insnHex)
        }
    }

    fun bpTraceEvents(bpId: Int = 0, sinceSeq: Long = 0L, limit: Int = 120): List<com.littlecodesauce.ggnext.model.BpTraceEvent> {
        val json = if (rootDaemonActive) {
            RootMemDaemon.bpTraceJson(bpId, sinceSeq, limit)
        } else {
            val h = NativeMemBridge.currentHandle()
            if (h != 0L) NativeMemBridge.nativeBpTraceJson(h, bpId, sinceSeq, limit) else "[]"
        }
        return com.littlecodesauce.ggnext.model.BpSnapshotParser.parseTraceEvents(json) { arch, pc, insnHex ->
            AsmEngineManager.disassembleHex(arch, pc, insnHex)
        }
    }

    fun bpClearTrace(bpId: Int = 0) {
        if (rootDaemonActive) {
            RootMemDaemon.bpClearTrace(bpId)
        } else {
            val h = NativeMemBridge.currentHandle()
            if (h != 0L) NativeMemBridge.nativeBpClearTrace(h, bpId)
        }
    }

    fun bpSetRegister(
        bpId: Int,
        tid: Int,
        hitPc: Long,
        regName: String,
        hexValue: String,
        isFp: Boolean
    ): Pair<Boolean, String> {
        if (!attachedOk || pid <= 0) return false to "请先附加目标进程"
        if (rootDaemonActive) {
            return RootMemDaemon.bpSetRegister(bpId, tid, hitPc, regName, hexValue, isFp)
        }
        if (!NativeMemBridge.ensureLoaded(appContextRef)) {
            return false to "原生调试库未加载：${NativeMemBridge.loadError}"
        }
        val h = NativeMemBridge.ensureHandle()
        if (h == 0L) return false to "原生调试引擎未就绪"
        val resp = NativeMemBridge.nativeBpSetReg(h, bpId, tid, hitPc, regName, hexValue, isFp)
        return if (resp == "OK") {
            true to ""
        } else {
            false to resp.removePrefix("ERR:").ifBlank { "寄存器写回失败" }
        }
    }

    // ============ 变速控制 (ShadowHook + xDL Agent) ============

    fun applySpeedMultiplier(
        rate: Float,
        targetPkg: String?,
        enableWrapProperty: Boolean = false
    ): Pair<Boolean, String> {
        val clamped = rate.coerceIn(0.01f, 100f)
        val myPid = android.os.Process.myPid()
        if (pid == myPid || pid <= 0) {
            val ok = NativeMemBridge.isLoaded && NativeMemBridge.nativeAgentSetSpeed(clamped)
            return if (ok) {
                true to "ShadowHook 进程内变速已生效 (${clamped}x)"
            } else {
                false to "ShadowHook 仅支持 ARM/ARM64 架构或原生库未加载"
            }
        }
        if (rootDaemonActive) {
            val cfgOk = RootMemDaemon.setSpeedConfig(pid, clamped)
            val agentSo = appContextRef?.applicationInfo?.nativeLibraryDir?.let { File(it, "libggnext_agent.so") }
            if (enableWrapProperty && !targetPkg.isNullOrBlank() && agentSo != null && agentSo.exists()) {
                val staged = "/data/local/tmp/libggnext_agent.so"
                val cmd = buildString {
                    append("cp -f '").append(agentSo.absolutePath).append("' ").append(staged)
                    append(" && chmod 755 ").append(staged)
                    append(" && (chcon u:object_r:system_file:s0 ").append(staged).append(" 2>/dev/null || true)")
                    append(" && setprop wrap.").append(targetPkg)
                        .append(" \"GGNEXT_AUTO_SPEED=1 LD_PRELOAD=").append(staged).append("\"")
                }
                Shell.runAs(cmd, asRoot = true)
            }
            return if (cfgOk) {
                true to "已写入变速配置 ${clamped}x（目标 PID $pid，ShadowHook Agent 实时生效）"
            } else {
                false to "写入变速配置文件失败"
            }
        }
        // 免 Root 同进程/虚拟空间直接调用 ShadowHook Agent
        val localOk = NativeMemBridge.isLoaded && NativeMemBridge.nativeAgentSetSpeed(clamped)
        return if (localOk) {
            true to "已应用 ShadowHook 变速 ${clamped}x"
        } else {
            false to "变速设置失败：当前环境无 Root 且未在目标进程内加载 Agent"
        }
    }

    // ============ 目标进程内存页分配 (跨进程 ptrace SYS_mmap + 权限与写入校验) ============

    fun allocatePage(
        hintAddr: Long,
        read: Boolean,
        write: Boolean,
        exec: Boolean,
        pageSize: Int = 4096
    ): Pair<Long, String> {
        if (!attachedOk || pid <= 0) {
            return 0L to "请先附加目标进程"
        }
        var prot = 0
        if (read) prot = prot or 1  // PROT_READ
        if (write) prot = prot or 2 // PROT_WRITE
        if (exec) prot = prot or 4  // PROT_EXEC
        if (prot == 0) prot = 3     // default RW

        val (addr, err) = if (rootDaemonActive) {
            RootMemDaemon.allocTargetPage(hintAddr, pageSize, prot)
        } else if (NativeMemBridge.ensureLoaded(appContextRef)) {
            val h = NativeMemBridge.ensureHandle()
            val resp = NativeMemBridge.nativeAllocTargetPage(h, pid, hintAddr, pageSize, prot)
            if (resp.startsWith("OK:")) {
                val parts = resp.removePrefix("OK:").split(':')
                val parsed = parts.firstOrNull()?.toULongOrNull(16)?.toLong() ?: 0L
                if (parsed != 0L) parsed to "" else 0L to "目标进程返回了无效的分配地址"
            } else {
                0L to resp.removePrefix("ERR:").ifBlank { "目标进程内存页分配失败" }
            }
        } else {
            0L to "原生内存引擎未加载：${NativeMemBridge.loadError}"
        }

        if (addr != 0L) {
            // Refresh target process VMA cache so memory browser & region lookup immediately see the new page
            cachedRegions = emptyList()
            regions()
            val rwx = buildString {
                append(if (read) 'r' else '-')
                append(if (write) 'w' else '-')
                append(if (exec) 'x' else '-')
            }
            return addr to "已在目标进程分配内存页 0x${java.lang.Long.toUnsignedString(addr, 16).uppercase()} ($rwx)"
        }
        return 0L to err.ifBlank { "内存页分配失败（请检查权限标志或目标进程状态）" }
    }

    // ============ ShadowHook Inline Hook / Register Intercept & Trampoline Active Call ============

    fun hookCommand(reqJson: org.json.JSONObject): org.json.JSONObject {
        val targetPid = if (attachedOk && pid > 0) pid else android.os.Process.myPid()
        val myPid = android.os.Process.myPid()
        val rawResp = if (targetPid == myPid || !rootDaemonActive) {
            if (!NativeMemBridge.ensureLoaded(appContextRef)) {
                return org.json.JSONObject()
                    .put("ok", false)
                    .put("error", "Native engine not loaded: ${NativeMemBridge.loadError}")
            }
            val h = NativeMemBridge.ensureHandle()
            NativeMemBridge.nativeHookCommand(h, targetPid, reqJson.toString())
        } else {
            RootMemDaemon.hookCommand(reqJson.toString())
        }
        return runCatching { org.json.JSONObject(rawResp) }.getOrElse {
            org.json.JSONObject().put("ok", false).put("error", rawResp.ifBlank { "Invalid hook agent response" })
        }
    }

    // ============ ELF / .so Module Base & Symbol Resolution ============

    fun getModuleBase(libName: String): Long? {
        val needle = libName.trim()
        if (needle.isEmpty()) return null
        val regs = regions()
        var best: Long? = null
        for (r in regs) {
            if (r.path.isBlank()) continue
            val baseName = r.path.substringAfterLast('/')
            if (baseName.equals(needle, ignoreCase = true) || r.path.contains(needle, ignoreCase = true)) {
                if (best == null || java.lang.Long.compareUnsigned(r.start, best) < 0) {
                    best = r.start
                }
            }
        }
        return best
    }

    fun elfExportsJson(libName: String, filter: String = "", limit: Int = 500): org.json.JSONArray {
        val cleanLib = libName.trim()
        if (cleanLib.isEmpty()) return org.json.JSONArray()
        val targetPid = if (attachedOk && pid > 0) pid else android.os.Process.myPid()
        val raw = if (rootDaemonActive && targetPid != android.os.Process.myPid()) {
            RootMemDaemon.elfExportsJson(cleanLib, filter.trim(), limit).second
        } else if (NativeMemBridge.ensureLoaded(appContextRef)) {
            val h = NativeMemBridge.ensureHandle()
            NativeMemBridge.nativeElfExportsJson(h, targetPid, cleanLib, filter.trim(), limit)
        } else {
            "[]"
        }
        return runCatching { org.json.JSONArray(raw) }.getOrElse { org.json.JSONArray() }
    }

    fun findSymbol(libName: String, symName: String): Long {
        val cleanLib = libName.trim()
        val cleanSym = symName.trim()
        if (cleanLib.isEmpty() || cleanSym.isEmpty()) return 0L
        val targetPid = if (attachedOk && pid > 0) pid else android.os.Process.myPid()
        return if (rootDaemonActive && targetPid != android.os.Process.myPid()) {
            RootMemDaemon.findSymbol(cleanLib, cleanSym)
        } else if (NativeMemBridge.ensureLoaded(appContextRef)) {
            val h = NativeMemBridge.ensureHandle()
            NativeMemBridge.nativeFindSymbol(h, targetPid, cleanLib, cleanSym)
        } else {
            0L
        }
    }
}
