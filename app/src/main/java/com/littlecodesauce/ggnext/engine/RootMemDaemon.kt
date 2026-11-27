package com.littlecodesauce.ggnext.engine

import android.content.Context
import android.os.Build
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.MemoryRegion
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile

/**
 * 持久化 Root C++20 内存引擎守护客户端 (`libmem_engine_root.so`)
 *
 * - 支持控制通道超时代管（Watchdog 超时自动熔断并重启恢复）
 * - 支持扫描中无阻塞发送 `CANCEL` 立即中断 C++ 扫描线程
 * - 支持 `ESRCH` (`ERR -2`) 目标进程存活监测回调
 */
object RootMemDaemon {

    const val CANCELLED_RESULT = -1L
    private const val CONTROL_TIMEOUT_MS = 10_000L
    private const val BULK_READ_TIMEOUT_MS = 25_000L
    private const val SCAN_IDLE_TIMEOUT_MS = 35_000L

    private var process: Process? = null
    @Volatile
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var stderrThread: Thread? = null
    private var lastAppContext: Context? = null
    private var lastAttachedPid: Int = 0
    private var lastRegionMask: Int = 0x3FFF
    private var lastAddrMin: Long = 0L
    private var lastAddrMax: Long = 0L
    private var lastAddrMask: Long = 0L
    private var lastAddrPattern: Long = 0L
    private var isRecovering: Boolean = false

    private val writeLock = Any()
    private val ioWatchdog = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RootMemDaemon-read").apply { isDaemon = true }
    }

    @Volatile
    var onTargetProcessDead: (() -> Unit)? = null

    @Volatile
    var lastStartError: String = ""
        private set

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\"'\"'") + "'"

    private fun formatDouble(v: Double): String = when {
        v.isNaN() -> "0.0"
        v == Double.POSITIVE_INFINITY -> "1.7976931348623157E308"
        v == Double.NEGATIVE_INFINITY -> "-1.7976931348623157E308"
        else -> java.lang.Double.toString(v)
    }

    private fun writeLineOutOfBand(line: String): Boolean {
        synchronized(writeLock) {
            val w = writer ?: return false
            return try {
                w.write(line)
                w.write("\n")
                w.flush()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * 非阻塞向 C++ 守护进程发送 CANCEL 信号：
     * 不等待当前扫描持有的对象锁，直接通过写锁下发 `CANCEL\n`，
     * 守护进程主线程立即置位 CancellationToken 中断工作线程。
     */
    fun cancelScan() {
        writeLineOutOfBand("CANCEL")
    }

    private fun readLineWithTimeout(r: BufferedReader, timeoutMs: Long): String? {
        val future = ioWatchdog.submit<String?> { r.readLine() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            AppLog.w("RootMemDaemon", "Daemon read timed out after ${timeoutMs}ms; resetting daemon")
            forceKillProcess()
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 批量读取多行响应：整个批量读取只向 Watchdog 提交一次任务，
     * 彻底消除逐行 `submit + future.get` 带来的数万次线程上下文切换与 FutureTask 分配。
     */
    private fun <T> readBulkWithTimeout(
        r: BufferedReader,
        timeoutMs: Long,
        block: (BufferedReader) -> T
    ): T? {
        val future = ioWatchdog.submit<T> { block(r) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            AppLog.w("RootMemDaemon", "Daemon bulk read timed out after ${timeoutMs}ms; resetting daemon")
            forceKillProcess()
            null
        } catch (_: Throwable) {
            null
        }
    }

    internal fun parseHexLong(s: String, start: Int, end: Int): Long? {
        var i = start
        var e = end
        while (i < e && s[i] <= ' ') i++
        while (e > i && s[e - 1] <= ' ') e--
        if (i >= e) return null
        var acc = 0L
        while (i < e) {
            val ch = s[i]
            val d = when (ch) {
                in '0'..'9' -> ch.code - '0'.code
                in 'a'..'f' -> ch.code - 'a'.code + 10
                in 'A'..'F' -> ch.code - 'A'.code + 10
                else -> return null
            }
            acc = (acc shl 4) or d.toLong()
            i++
        }
        return acc
    }

    internal fun parseSignedLong(s: String, start: Int, end: Int): Long? {
        var i = start
        var e = end
        while (i < e && s[i] <= ' ') i++
        while (e > i && s[e - 1] <= ' ') e--
        if (i >= e) return null
        var negative = false
        val first = s[i]
        if (first == '-') {
            negative = true
            i++
        } else if (first == '+') {
            i++
        }
        if (i >= e) return null
        var acc = 0L
        while (i < e) {
            val ch = s[i]
            if (ch !in '0'..'9') return null
            acc = acc * 10L - (ch.code - '0'.code).toLong()
            i++
        }
        return if (negative) acc else -acc
    }

    internal fun parseUnsignedInt(s: String, start: Int, end: Int): Int? {
        var i = start
        var e = end
        while (i < e && s[i] <= ' ') i++
        while (e > i && s[e - 1] <= ' ') e--
        if (i >= e) return null
        var acc = 0L
        while (i < e) {
            val ch = s[i]
            if (ch !in '0'..'9') return null
            acc = acc * 10L + (ch.code - '0'.code).toLong()
            i++
        }
        return acc.toInt()
    }

    internal fun parseCandidateLine(line: String): MemoryBackend.RawCandidate? {
        val len = line.length
        if (len < 5) return null
        val sp1 = line.indexOf(' ')
        if (sp1 <= 0) return null
        val sp2 = line.indexOf(' ', sp1 + 1)
        if (sp2 <= sp1 + 1 || sp2 >= len - 1) return null
        val addr = parseHexLong(line, 0, sp1) ?: return null
        val rawVal = parseSignedLong(line, sp1 + 1, sp2) ?: 0L
        val meta = parseUnsignedInt(line, sp2 + 1, len) ?: 0
        return MemoryBackend.RawCandidate(addr, rawVal, meta)
    }

    internal fun parseBatchReadLine(line: String, out: MutableMap<Long, Long>) {
        val len = line.length
        if (len < 5) return
        val sp1 = line.indexOf(' ')
        if (sp1 <= 0) return
        val sp2 = line.indexOf(' ', sp1 + 1)
        if (sp2 <= sp1 + 1 || sp2 >= len - 1) return
        if (sp2 - (sp1 + 1) != 1 || line[sp1 + 1] != '1') return
        val addr = parseHexLong(line, 0, sp1) ?: return
        val rawVal = parseSignedLong(line, sp2 + 1, len) ?: 0L
        out[addr] = rawVal
    }

    private fun forceKillProcess() {
        try {
            process?.destroyForcibly()
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        synchronized(writeLock) {
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            writer = null
        }
        process = null
        reader = null
        stderrThread = null
    }

    @Synchronized
    fun ensureStarted(context: Context?): Boolean {
        if (context != null) {
            lastAppContext = context.applicationContext ?: context
        }
        if (isAlive()) return true
        stop()
        lastStartError = ""
        if (!Shell.hasRoot()) {
            lastStartError = "未获取到 Root (su) 权限"
            return false
        }

        val ctx = context ?: lastAppContext
        val binPath = resolveExecutablePath(ctx)
        if (binPath == null) {
            lastStartError = "未找到 libmem_engine_root.so 可执行引擎"
            return false
        }
        val spillDir = ctx?.cacheDir?.absolutePath ?: "/data/local/tmp"
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val nativeDir = ctx?.applicationInfo?.nativeLibraryDir

        val variants = listOf(
            listOf("su", "--mount-master", "-c"),
            listOf("su", "-mm", "-c"),
            listOf("su", "-c")
        )
        for (suPrefix in variants) {
            if (tryStartDaemonProcess(suPrefix, binPath, spillDir, threads, nativeDir)) {
                lastStartError = ""
                return true
            }
        }
        return false
    }

    private fun tryStartDaemonProcess(
        suPrefix: List<String>,
        binPath: String,
        spillDir: String,
        threads: Int,
        nativeDir: String?
    ): Boolean {
        return try {
            val binDir = File(binPath).parent ?: "/data/local/tmp"
            val appLibDirs = listOfNotNull(nativeDir, binDir).distinct().joinToString(":")

            val stageTmp = "/data/local/tmp/.ggnext_root_daemon"
            val stageDev = "/dev/.ggnext_root_daemon"
            val qSpill = shellQuote(spillDir)
            val qBin = shellQuote(binPath)
            val qStageTmp = shellQuote(stageTmp)
            val qStageDev = shellQuote(stageDev)

            val launchScript = buildString {
                append("magiskpolicy --live \"allow su proc_mem file *\" \"allow magisk proc_mem file *\" >/dev/null 2>&1 || ")
                append("ksud sepolicy patch \"allow su proc_mem file *\" >/dev/null 2>&1 || true; ")
                append("mkdir -p ").append(qSpill).append(" >/dev/null 2>&1 || true; ")
                append("rm -f ").append(qStageTmp).append(' ').append(qStageDev).append(" >/dev/null 2>&1 || true; ")
                if (appLibDirs.isNotEmpty()) {
                    append("export LD_LIBRARY_PATH=").append(shellQuote(appLibDirs)).append("; ")
                }
                append("if cp -f ").append(qBin).append(' ').append(qStageTmp).append(" >/dev/null 2>&1; then ")
                append("chmod 755 ").append(qStageTmp).append(" >/dev/null 2>&1 || true; ")
                append("chcon u:object_r:system_file:s0 ").append(qStageTmp)
                    .append(" >/dev/null 2>&1 || chcon u:object_r:shell_data_file:s0 ").append(qStageTmp)
                    .append(" >/dev/null 2>&1 || true; ")
                append(qStageTmp).append(' ').append(qSpill).append(' ').append(threads).append("; ")
                append("rc=$?; if [ \"\$rc\" -ne 126 ] && [ \"\$rc\" -ne 127 ]; then exit \"\$rc\"; fi; ")
                append("fi; ")
                append("if cp -f ").append(qBin).append(' ').append(qStageDev).append(" >/dev/null 2>&1; then ")
                append("chmod 755 ").append(qStageDev).append(" >/dev/null 2>&1 || true; ")
                append("chcon u:object_r:system_file:s0 ").append(qStageDev).append(" >/dev/null 2>&1 || true; ")
                append(qStageDev).append(' ').append(qSpill).append(' ').append(threads).append("; ")
                append("rc=$?; if [ \"\$rc\" -ne 126 ] && [ \"\$rc\" -ne 127 ]; then exit \"\$rc\"; fi; ")
                append("fi; ")
                append("chmod 755 ").append(qBin).append(" >/dev/null 2>&1 || true; ")
                append("exec ").append(qBin).append(' ').append(qSpill).append(' ').append(threads)
            }

            val pb = ProcessBuilder(suPrefix + launchScript)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), 65536)

            val stderrLines = java.util.Collections.synchronizedList(ArrayList<String>(8))
            val errThread = Thread({
                try {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { errReader ->
                        while (true) {
                            val errLine = errReader.readLine() ?: break
                            if (stderrLines.size < 16) {
                                stderrLines.add(errLine)
                            }
                            AppLog.w("RootMemDaemon[stderr]", errLine)
                        }
                    }
                } catch (_: Throwable) {
                }
            }, "RootMemDaemon-stderr").apply {
                isDaemon = true
                start()
            }

            process = p
            synchronized(writeLock) { writer = w }
            reader = r
            stderrThread = errThread

            val pong = sendRawLocked("PING", isScan = false)
            val ok = pong != null && pong.startsWith("OK")
            if (ok) {
                AppLog.i("RootMemDaemon", "Started uid=0 C++20 MemEngine daemon (${suPrefix.joinToString(" ")}): $binPath")
            } else {
                val errSummary = stderrLines.joinToString(" | ").take(200)
                lastStartError = buildString {
                    append("${suPrefix.joinToString(" ")} 握手失败")
                    if (pong != null) append(" (resp=${pong.take(80)})")
                    if (errSummary.isNotEmpty()) append(": $errSummary")
                }
                AppLog.w("RootMemDaemon", lastStartError)
                stop()
            }
            ok
        } catch (t: Throwable) {
            lastStartError = "${suPrefix.joinToString(" ")} 异常: ${t.message}"
            AppLog.e("RootMemDaemon", lastStartError, t)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        writeLineOutOfBand("EXIT")
        forceKillProcess()
    }

    private fun isAlive(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun resolveExecutablePath(context: Context?): String? {
        if (context == null) return null
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val directSo = File(nativeDir, "libmem_engine_root.so")
        if (directSo.exists() && directSo.length() > 0L) {
            return directSo.absolutePath
        }

        val extractDir = context.codeCacheDir ?: context.filesDir
        val extracted = File(extractDir, "libmem_engine_root.so")
        val extractedCpp = File(extractDir, "libc++_shared.so")
        val apkPath = context.applicationInfo.sourceDir ?: return extracted.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        val abis = Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")
        return try {
            ZipFile(apkPath).use { zip ->
                for (abi in abis) {
                    val entry = zip.getEntry("lib/$abi/libmem_engine_root.so") ?: continue
                    val expectedSize = entry.size
                    if (extracted.exists() && extracted.length() > 0L && (expectedSize <= 0L || extracted.length() == expectedSize)) {
                        extracted.setExecutable(true, false)
                        return extracted.absolutePath
                    }
                    extracted.delete()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(extracted).use { output ->
                            input.copyTo(output)
                        }
                    }
                    zip.getEntry("lib/$abi/libc++_shared.so")?.let { cppEntry ->
                        if (!extractedCpp.exists() || (cppEntry.size > 0L && extractedCpp.length() != cppEntry.size)) {
                            extractedCpp.delete()
                            zip.getInputStream(cppEntry).use { input ->
                                FileOutputStream(extractedCpp).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    extracted.setExecutable(true, false)
                    return extracted.absolutePath
                }
            }
            extracted.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        } catch (t: Throwable) {
            AppLog.e("RootMemDaemon", "Failed to extract libmem_engine_root.so from APK", t)
            extracted.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        }
    }

    private fun checkEsrchAndNotify(resp: String?) {
        if (resp != null && resp.trim() == "ERR -2") {
            onTargetProcessDead?.invoke()
        }
    }

    private fun sendRawLocked(
        cmd: String,
        onProgress: ((doneBytes: Long, totalBytes: Long, foundCount: Long) -> Unit)? = null,
        isScan: Boolean = onProgress != null
    ): String? {
        val r = reader ?: return null
        if (!writeLineOutOfBand(cmd)) {
            forceKillProcess()
            return null
        }
        val timeoutMs = if (isScan) SCAN_IDLE_TIMEOUT_MS else CONTROL_TIMEOUT_MS
        try {
            while (true) {
                val line = readLineWithTimeout(r, timeoutMs) ?: return null
                if (line.startsWith("PROGRESS ")) {
                    val parts = line.trim().split(' ')
                    if (parts.size >= 4) {
                        val done = parts[1].toLongOrNull() ?: 0L
                        val total = parts[2].toLongOrNull() ?: 1L
                        val found = parts[3].toLongOrNull() ?: 0L
                        onProgress?.invoke(done, total, found)
                    }
                    continue
                }
                checkEsrchAndNotify(line)
                return line
            }
        } catch (_: Throwable) {
            forceKillProcess()
            return null
        }
    }

    private fun ensureRecoveredAndAttached(): Boolean {
        if (isAlive() && writer != null && reader != null) return true
        if (isRecovering) return false
        isRecovering = true
        return try {
            if (!ensureStarted(lastAppContext)) {
                false
            } else {
                if (lastAttachedPid > 0) {
                    val attachResp = sendRawLocked("ATTACH $lastAttachedPid", isScan = false)
                    if (attachResp == null || !attachResp.startsWith("OK ")) {
                        lastStartError = "恢复附加 pid=$lastAttachedPid 失败 (${attachResp ?: "EOF"})"
                        return false
                    }
                    val uMask = lastRegionMask.toLong() and 0xFFFFFFFFL
                    sendRawLocked("SET_MASK $uMask", isScan = false)
                    if (lastAddrMin != 0L || lastAddrMax != 0L || lastAddrMask != 0L) {
                        val minH = java.lang.Long.toUnsignedString(lastAddrMin, 16)
                        val maxH = java.lang.Long.toUnsignedString(lastAddrMax, 16)
                        val maskH = java.lang.Long.toUnsignedString(lastAddrMask, 16)
                        val patH = java.lang.Long.toUnsignedString(lastAddrPattern, 16)
                        sendRawLocked("SET_ADDR_FILTER $minH $maxH $maskH $patH", isScan = false)
                    }
                }
                true
            }
        } finally {
            isRecovering = false
        }
    }

    private fun sendCommandLocked(
        cmd: String,
        onProgress: ((doneBytes: Long, totalBytes: Long, foundCount: Long) -> Unit)? = null,
        isScan: Boolean = onProgress != null
    ): String? {
        if (!ensureRecoveredAndAttached()) return null
        val firstTry = sendRawLocked(cmd, onProgress, isScan)
        if (firstTry != null) return firstTry
        if (!ensureRecoveredAndAttached()) return null
        return sendRawLocked(cmd, onProgress, isScan)
    }

    private fun parseScanCount(resp: String?): Long {
        if (resp == null) return 0L
        if (resp.startsWith("CANCELLED")) return CANCELLED_RESULT
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun attach(context: Context?, pid: Int): Boolean {
        if (context != null) {
            lastAppContext = context.applicationContext ?: context
        }
        lastAttachedPid = pid
        if (ensureStarted(lastAppContext)) {
            val resp = sendRawLocked("ATTACH $pid", isScan = false)
            if (resp != null && resp.startsWith("OK ")) {
                lastStartError = ""
                return true
            }
            if (resp != null) {
                lastStartError = "守护进程 ATTACH $pid 失败 ($resp)"
            }
        }
        stop()
        if (!ensureStarted(lastAppContext)) return false
        val retryResp = sendRawLocked("ATTACH $pid", isScan = false)
        if (retryResp != null && retryResp.startsWith("OK ")) {
            lastStartError = ""
            return true
        }
        lastStartError = "守护进程重试 ATTACH $pid 失败 (${retryResp ?: "EOF"})"
        return false
    }

    @Synchronized
    fun isTargetProcessAlive(): Boolean {
        if (lastAttachedPid <= 0 || !isAlive()) return false
        val resp = sendRawLocked("ALIVE", isScan = false) ?: return false
        return resp.trim() == "OK 1"
    }

    @Synchronized
    fun setRegionMask(mask: Int): Boolean {
        lastRegionMask = mask
        val uMask = mask.toLong() and 0xFFFFFFFFL
        val resp = sendCommandLocked("SET_MASK $uMask", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun setAddressFilter(minAddr: Long, maxAddr: Long, addrMask: Long = 0L, addrPattern: Long = 0L): Boolean {
        lastAddrMin = minAddr
        lastAddrMax = maxAddr
        lastAddrMask = addrMask
        lastAddrPattern = addrPattern
        val minH = java.lang.Long.toUnsignedString(minAddr, 16)
        val maxH = java.lang.Long.toUnsignedString(maxAddr, 16)
        val maskH = java.lang.Long.toUnsignedString(addrMask, 16)
        val patH = java.lang.Long.toUnsignedString(addrPattern, 16)
        val resp = sendCommandLocked("SET_ADDR_FILTER $minH $maxH $maskH $patH", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun getRegions(): List<MemoryRegion> {
        val header = sendCommandLocked("REGIONS", isScan = false) ?: return emptyList()
        if (!header.startsWith("OK ")) return emptyList()
        val count = header.removePrefix("OK ").trim().toIntOrNull() ?: 0
        if (count <= 0) return emptyList()
        val r = reader ?: return emptyList()
        return readBulkWithTimeout(r, CONTROL_TIMEOUT_MS) { br ->
            val out = ArrayList<MemoryRegion>(count)
            for (i in 0 until count) {
                val line = br.readLine() ?: break
                val parts = line.split(' ', limit = 5)
                if (parts.size < 4) continue
                val start = parseHexLong(parts[0], 0, parts[0].length) ?: continue
                val end = parseHexLong(parts[1], 0, parts[1].length) ?: continue
                val perms = parts[2]
                val code = parts[3]
                val rawPath = if (parts.size >= 5) parts[4] else "-"
                val path = if (rawPath == "-") "" else rawPath
                out.add(
                    MemoryRegion(
                        start = start,
                        end = end,
                        perms = perms,
                        path = path,
                        code = code,
                        system = (code == "Xs" || code == "O")
                    )
                )
            }
            out
        } ?: emptyList()
    }

    @Synchronized
    fun readBlock(address: Long, size: Int): ByteArray? {
        val safeLen = size.coerceIn(1, 16 * 1024 * 1024)
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val resp = sendCommandLocked("READ $addrHex $safeLen", isScan = false) ?: return null
        if (!resp.startsWith("OK ")) return null
        val hex = resp.removePrefix("OK ").trim()
        return hexToBytes(hex, safeLen)
    }

    @Synchronized
    fun batchReadValues(items: List<Pair<Long, Int>>): Map<Long, Long> {
        if (items.isEmpty() || lastAttachedPid <= 0) return emptyMap()
        val result = HashMap<Long, Long>(items.size)
        return try {
            for (chunk in items.chunked(512)) {
                val sb = StringBuilder("BATCH_READ ").append(chunk.size)
                for ((addr, sz) in chunk) {
                    val clampedSz = sz.coerceIn(1, 8)
                    sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16)).append(':').append(clampedSz)
                }
                val header = sendCommandLocked(sb.toString(), isScan = false) ?: break
                if (!header.startsWith("OK ")) break
                val r = reader ?: break
                val count = header.removePrefix("OK ").trim().toIntOrNull() ?: 0
                if (count <= 0) continue
                val okChunk = readBulkWithTimeout(r, CONTROL_TIMEOUT_MS) { br ->
                    for (i in 0 until count) {
                        val line = br.readLine() ?: break
                        parseBatchReadLine(line, result)
                    }
                    true
                }
                if (okChunk == null) break
            }
            result
        } catch (_: Throwable) {
            stop()
            result
        }
    }

    @Synchronized
    fun writeBlock(address: Long, data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val hex = bytesToHex(data)
        val resp = sendCommandLocked("WRITE $addrHex $hex", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun writeBatch(entries: List<Pair<Long, ByteArray>>): Int {
        if (entries.isEmpty()) return 0
        // Fast path: check if all payloads are identical -> BATCH_WRITE_UNIFORM (half IPC, single hex parse, iovec writev)
        val firstBytes = entries[0].second
        val isUniform = firstBytes.isNotEmpty() && entries.all { it.second.contentEquals(firstBytes) }
        var totalWritten = 0
        if (isUniform) {
            val hex = bytesToHex(firstBytes)
            for (chunk in entries.chunked(8192)) {
                val sb = StringBuilder(chunk.size * 18 + hex.length + 36)
                sb.append("BATCH_WRITE_UNIFORM ").append(hex).append(' ').append(chunk.size)
                for ((addr, _) in chunk) {
                    sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16))
                }
                val resp = sendCommandLocked(sb.toString(), isScan = false) ?: continue
                if (resp.startsWith("OK ")) {
                    totalWritten += resp.removePrefix("OK ").trim().toIntOrNull() ?: 0
                }
            }
            return totalWritten
        }
        for (chunk in entries.chunked(4096)) {
            val sb = StringBuilder(chunk.size * 28 + 32)
            sb.append("BATCH_WRITE ").append(chunk.size)
            for ((addr, data) in chunk) {
                if (data.isEmpty()) continue
                sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16)).append(':').append(bytesToHex(data))
            }
            val resp = sendCommandLocked(sb.toString(), isScan = false) ?: continue
            if (resp.startsWith("OK ")) {
                totalWritten += resp.removePrefix("OK ").trim().toIntOrNull() ?: 0
            }
        }
        return totalWritten
    }

    @Synchronized
    fun lockBatch(entries: List<MemoryBackend.BatchLockEntry>): Int {
        if (entries.isEmpty()) return 0
        var totalLocked = 0
        for (chunk in entries.chunked(2048)) {
            val sb = StringBuilder(chunk.size * 48 + 32)
            sb.append("BATCH_LOCK ").append(chunk.size)
            for (e in chunk) {
                if (e.data.isEmpty()) continue
                sb.append(' ').append(java.lang.Long.toUnsignedString(e.address, 16))
                    .append(' ').append(bytesToHex(e.data))
                    .append(' ').append(e.nativeDt)
                    .append(' ').append(e.freezeMode)
                    .append(' ').append(e.minVal)
                    .append(' ').append(e.maxVal)
            }
            val resp = sendCommandLocked(sb.toString(), isScan = false) ?: continue
            if (resp.startsWith("OK ")) {
                totalLocked += resp.removePrefix("OK ").trim().toIntOrNull() ?: 0
            }
        }
        return totalLocked
    }

    @Synchronized
    fun unlockBatch(addresses: Collection<Long>) {
        if (addresses.isEmpty()) return
        for (chunk in addresses.chunked(8192)) {
            val sb = StringBuilder(chunk.size * 18 + 32)
            sb.append("BATCH_UNLOCK ").append(chunk.size)
            for (addr in chunk) {
                sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16))
            }
            sendCommandLocked(sb.toString(), isScan = false)
        }
    }

    @Synchronized
    fun removePrefixCandidates(count: Int): Long {
        if (count <= 0) return 0L
        val resp = sendCommandLocked("REMOVE_PREFIX $count", isScan = false) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun copyMemory(fromAddr: Long, toAddr: Long, length: Long): Long {
        val fromHex = java.lang.Long.toUnsignedString(fromAddr, 16)
        val toHex = java.lang.Long.toUnsignedString(toAddr, 16)
        val resp = sendCommandLocked("COPY $fromHex $toHex $length", isScan = true) ?: return 0L
        if (!resp.startsWith("OK ")) return 0L
        return resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L
    }

    @Synchronized
    fun dumpMemory(startAddr: Long, endAddr: Long, outPath: String): Long {
        val startHex = java.lang.Long.toUnsignedString(startAddr, 16)
        val endHex = java.lang.Long.toUnsignedString(endAddr, 16)
        val resp = sendCommandLocked("DUMP $startHex $endHex $outPath", isScan = true) ?: return 0L
        if (!resp.startsWith("OK ")) return 0L
        return resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L
    }

    @Synchronized
    fun lockAddress(address: Long, data: ByteArray) {
        if (data.isEmpty()) return
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val hex = bytesToHex(data)
        sendCommandLocked("LOCK $addrHex $hex", isScan = false)
    }

    @Synchronized
    fun lockAddressConfigured(
        address: Long,
        data: ByteArray,
        dataType: Int,
        freezeMode: Int,
        minVal: Long,
        maxVal: Long
    ) {
        if (data.isEmpty()) return
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val hex = bytesToHex(data)
        sendCommandLocked("LOCK_CONFIG $addrHex $hex $dataType $freezeMode $minVal $maxVal", isScan = false)
    }

    @Synchronized
    fun setFreezePaused(paused: Boolean) {
        val p = if (paused) 1 else 0
        sendCommandLocked("LOCK_PAUSE $p", isScan = false)
    }

    @Synchronized
    fun setFreezeIntervalMs(intervalMs: Int) {
        val safeMs = intervalMs.coerceIn(5, 5000)
        sendCommandLocked("LOCK_INTERVAL $safeMs", isScan = false)
    }

    @Synchronized
    fun unlockAddress(address: Long) {
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        sendCommandLocked("UNLOCK $addrHex", isScan = false)
    }

    @Synchronized
    fun unlockAll() {
        sendCommandLocked("UNLOCK_ALL", isScan = false)
    }

    @Synchronized
    fun clearSession() {
        sendCommandLocked("CLEAR_SESSION", isScan = false)
    }

    @Synchronized
    fun removeCandidates(addresses: Collection<Long>): Long {
        if (addresses.isEmpty()) return 0L
        if (addresses.size <= 8192) {
            val sb = StringBuilder(addresses.size * 18 + 32)
            sb.append("REMOVE_CANDIDATES ").append(addresses.size)
            for (addr in addresses) {
                sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16))
            }
            val resp = sendCommandLocked(sb.toString(), isScan = false) ?: return 0L
            return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
        }
        sendCommandLocked("REMOVE_BEGIN ${addresses.size}", isScan = false)
        for (chunk in addresses.chunked(8192)) {
            val sb = StringBuilder(chunk.size * 18 + 32)
            sb.append("REMOVE_CHUNK ").append(chunk.size)
            for (addr in chunk) {
                sb.append(' ').append(java.lang.Long.toUnsignedString(addr, 16))
            }
            sendCommandLocked(sb.toString(), isScan = false)
        }
        val resp = sendCommandLocked("REMOVE_COMMIT", isScan = false) ?: return 0L
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim().toLongOrNull() ?: 0L else 0L
    }

    @Synchronized
    fun searchExact(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val a = if (aligned) 1 else 0
        val cmd = "SEARCH_EXACT $dataType $intTarget ${formatDouble(floatTarget)} ${formatDouble(epsilon)} $a"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun refineExact(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val cmd = "REFINE_EXACT $dataType $intTarget ${formatDouble(floatTarget)} ${formatDouble(epsilon)}"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun searchNotEqual(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val a = if (aligned) 1 else 0
        val cmd = "SEARCH_NEQ $dataType $intTarget ${formatDouble(floatTarget)} ${formatDouble(epsilon)} $a"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun refineNotEqual(
        dataType: Int,
        intTarget: Long,
        floatTarget: Double,
        epsilon: Double,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val cmd = "REFINE_NEQ $dataType $intTarget ${formatDouble(floatTarget)} ${formatDouble(epsilon)}"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun searchRange(
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        aligned: Boolean,
        excludeRange: Boolean = false,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val inc = if (inclusive) 1 else 0
        val aln = if (aligned) 1 else 0
        val exc = if (excludeRange) 1 else 0
        val cmd = "SEARCH_RANGE $dataType $intMin $intMax ${formatDouble(floatMin)} ${formatDouble(floatMax)} $inc $aln $exc"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun refineRange(
        dataType: Int,
        intMin: Long,
        intMax: Long,
        floatMin: Double,
        floatMax: Double,
        inclusive: Boolean,
        excludeRange: Boolean = false,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val inc = if (inclusive) 1 else 0
        val exc = if (excludeRange) 1 else 0
        val cmd = "REFINE_RANGE $dataType $intMin $intMax ${formatDouble(floatMin)} ${formatDouble(floatMax)} $inc $exc"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun searchPointers(
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int = NativeMemBridge.PTR_PROT_ALL,
        aligned: Boolean = true,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val aln = if (aligned) 1 else 0
        val addrHex = java.lang.Long.toUnsignedString(targetAddress, 16)
        val uOff = java.lang.Long.toUnsignedString(if (maxOffset >= 0L) maxOffset else -maxOffset)
        val uProt = protMask.toLong() and 0xFFFFFFFFL
        val cmd = "SEARCH_POINTER $dataType $addrHex $uOff $uProt $aln"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun refinePointers(
        dataType: Int,
        targetAddress: Long,
        maxOffset: Long,
        protMask: Int = NativeMemBridge.PTR_PROT_ALL,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val addrHex = java.lang.Long.toUnsignedString(targetAddress, 16)
        val uOff = java.lang.Long.toUnsignedString(if (maxOffset >= 0L) maxOffset else -maxOffset)
        val uProt = protMask.toLong() and 0xFFFFFFFFL
        val cmd = "REFINE_POINTER $dataType $addrHex $uOff $uProt"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
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
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long = sendGroupCmd("SEARCH_GROUP", types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned, onProgress)

    @Synchronized
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
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long = sendGroupCmd("REFINE_GROUP", types, intVals, floatVals, intMaxVals, floatMaxVals, fieldOps, windowSize, ordered, aligned, onProgress)

    private fun sendGroupCmd(
        cmdName: String,
        types: IntArray,
        intVals: LongArray,
        floatVals: DoubleArray,
        intMaxVals: LongArray,
        floatMaxVals: DoubleArray,
        fieldOps: IntArray,
        windowSize: Int,
        ordered: Boolean,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val ord = if (ordered) 1 else 0
        val aln = if (aligned) 1 else 0
        val sb = StringBuilder()
        sb.append(cmdName).append(' ').append(windowSize).append(' ')
            .append(ord).append(' ').append(aln).append(' ').append(types.size)
        for (i in types.indices) {
            val iMax = intMaxVals.getOrElse(i) { intVals[i] }
            val fMax = floatMaxVals.getOrElse(i) { floatVals[i] }
            val op = fieldOps.getOrElse(i) { 0 }
            sb.append(' ').append(types[i])
                .append(' ').append(intVals[i])
                .append(' ').append(formatDouble(floatVals[i]))
                .append(' ').append(iMax)
                .append(' ').append(formatDouble(fMax))
                .append(' ').append(op)
        }
        return parseScanCount(sendCommandLocked(sb.toString(), onProgress, isScan = true))
    }

    @Synchronized
    fun searchPattern(
        pattern: ByteArray,
        mask: String,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val hex = bytesToHex(pattern)
        val safeMask = mask.ifEmpty { "x".repeat(pattern.size) }
        return parseScanCount(sendCommandLocked("SEARCH_PATTERN $hex $safeMask", onProgress, isScan = true))
    }

    @Synchronized
    fun searchEncoded(
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        aligned: Boolean,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val aln = if (aligned) 1 else 0
        val uKey = java.lang.Long.toUnsignedString(keyOrMask)
        val cmd = "SEARCH_ENCODED $dataType $encodedMode $logicalInt ${formatDouble(logicalFloat)} $uKey $aln"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun refineEncoded(
        dataType: Int,
        encodedMode: Int,
        logicalInt: Long,
        logicalFloat: Double,
        keyOrMask: Long,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val uKey = java.lang.Long.toUnsignedString(keyOrMask)
        val cmd = "REFINE_ENCODED $dataType $encodedMode $logicalInt ${formatDouble(logicalFloat)} $uKey"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun startUnknownSnapshot(
        dataType: Int,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        return parseScanCount(sendCommandLocked("FUZZY_INIT $dataType", onProgress, isScan = true))
    }

    @Synchronized
    fun refineFuzzy(
        deltaOp: Int,
        intDelta: Long,
        floatDelta: Double,
        epsilon: Double = 1e-4,
        onlyNth: Int = 0,
        onProgress: ((Long, Long, Long) -> Unit)? = null
    ): Long {
        val cmd = "FUZZY_REFINE $deltaOp $intDelta ${formatDouble(floatDelta)} ${formatDouble(epsilon)} ${onlyNth.coerceAtLeast(0)}"
        return parseScanCount(sendCommandLocked(cmd, onProgress, isScan = true))
    }

    @Synchronized
    fun getCandidates(offset: Int, limit: Int): List<MemoryBackend.RawCandidate> {
        val header = sendCommandLocked("GET_CANDIDATES $offset $limit", isScan = false) ?: return emptyList()
        if (!header.startsWith("OK ")) return emptyList()
        val count = header.removePrefix("OK ").trim().toIntOrNull() ?: 0
        if (count <= 0) return emptyList()
        val r = reader ?: return emptyList()
        return readBulkWithTimeout(r, BULK_READ_TIMEOUT_MS) { br ->
            val out = ArrayList<MemoryBackend.RawCandidate>(count)
            for (i in 0 until count) {
                val line = br.readLine() ?: break
                val cand = parseCandidateLine(line) ?: continue
                out.add(cand)
            }
            out
        } ?: emptyList()
    }

    // ============ Hardware Breakpoints (Perf & Ptrace) ============

    @Synchronized
    fun bpAdd(
        address: Long,
        bpType: Int,
        bpSize: Int,
        bpMode: Int,
        captureCallstack: Boolean
    ): Pair<Int, String> {
        val addrHex = java.lang.Long.toUnsignedString(address, 16)
        val cap = if (captureCallstack) 1 else 0
        val resp = sendCommandLocked("BP_ADD $addrHex $bpType $bpSize $bpMode $cap", isScan = false)
            ?: return -1 to "守护进程未响应"
        if (resp.startsWith("OK ")) {
            val id = resp.removePrefix("OK ").trim().toIntOrNull() ?: -1
            return id to ""
        }
        return -1 to resp.removePrefix("ERR ").trim().ifEmpty { "添加断点失败" }
    }

    @Synchronized
    fun bpRemove(bpId: Int): Boolean {
        val resp = sendCommandLocked("BP_REMOVE $bpId", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun bpSetPaused(bpId: Int, paused: Boolean): Boolean {
        val p = if (paused) 1 else 0
        val resp = sendCommandLocked("BP_PAUSE $bpId $p", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun bpSetAllPaused(paused: Boolean): Boolean {
        val p = if (paused) 1 else 0
        val resp = sendCommandLocked("BP_PAUSE_ALL $p", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun bpClearHits(bpId: Int = 0): Boolean {
        val resp = sendCommandLocked("BP_CLEAR_HITS $bpId", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun bpStopAll(): Boolean {
        val resp = sendCommandLocked("BP_STOP_ALL", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun bpSnapshotJson(): String {
        val resp = sendCommandLocked("BP_SNAPSHOT", isScan = false) ?: return "[]"
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim() else "[]"
    }

    @Synchronized
    fun bpTraceJson(bpId: Int = 0, sinceSeq: Long = 0L, limit: Int = 120): String {
        val resp = sendCommandLocked("BP_TRACE $bpId $sinceSeq $limit", isScan = false) ?: return "[]"
        return if (resp.startsWith("OK ")) resp.removePrefix("OK ").trim() else "[]"
    }

    @Synchronized
    fun bpClearTrace(bpId: Int = 0) {
        sendCommandLocked("BP_CLEAR_TRACE $bpId", isScan = false)
    }

    @Synchronized
    fun bpSetRegister(
        bpId: Int,
        tid: Int,
        hitPc: Long,
        regName: String,
        hexValue: String,
        isFp: Boolean
    ): Pair<Boolean, String> {
        val pcHex = java.lang.Long.toUnsignedString(hitPc, 16)
        val fpFlag = if (isFp) 1 else 0
        val cleanReg = regName.trim().replace(" ", "")
        val cleanVal = hexValue.trim().replace(" ", "")
        val resp = sendCommandLocked("BP_SET_REG $bpId $tid $pcHex $fpFlag $cleanReg $cleanVal", isScan = false)
            ?: return false to "守护进程未响应"
        if (resp.startsWith("OK")) return true to ""
        return false to resp.removePrefix("ERR ").trim().ifEmpty { "写回寄存器失败" }
    }

    @Synchronized
    fun setSpeedConfig(pid: Int, rate: Float): Boolean {
        val resp = sendCommandLocked("SET_SPEED_CFG $pid ${formatDouble(rate.toDouble())}", isScan = false) ?: return false
        return resp.startsWith("OK")
    }

    @Synchronized
    fun allocTargetPage(hintAddr: Long, size: Int, prot: Int): Pair<Long, String> {
        val addrHex = java.lang.Long.toUnsignedString(hintAddr, 16)
        val resp = sendCommandLocked("ALLOC_PAGE $addrHex $size $prot", isScan = false)
            ?: return 0L to "守护进程未响应"
        if (!resp.startsWith("OK ")) {
            val err = resp.removePrefix("ERR ").trim().ifEmpty { "目标进程分配内存页失败" }
            return 0L to err
        }
        val parts = resp.removePrefix("OK ").trim().split(' ')
        val hex = parts.firstOrNull().orEmpty()
        val addr = parseHexLong(hex, 0, hex.length) ?: 0L
        if (addr == 0L) return 0L to "返回了无效的分配地址"
        return addr to ""
    }

    @Synchronized
    fun allocPageLocal(hintAddr: Long, size: Int, prot: Int): Long =
        allocTargetPage(hintAddr, size, prot).first

    @Synchronized
    fun hookCommand(reqJson: String): String {
        val hex = bytesToHex(reqJson.toByteArray(Charsets.UTF_8))
        val resp = sendCommandLocked("HOOK_CMD $hex", isScan = false)
            ?: return """{"ok":false,"error":"Root daemon did not respond"}"""
        if (resp.startsWith("OK ")) {
            return resp.removePrefix("OK ").trim()
        }
        val err = resp.removePrefix("ERR ").trim().ifEmpty { "HOOK_CMD failed" }
        return org.json.JSONObject().put("ok", false).put("error", err).toString()
    }

    @Synchronized
    fun elfExportsJson(libName: String, filter: String = "", limit: Int = 500): Pair<Long, String> {
        val libHex = bytesToHex(libName.toByteArray(Charsets.UTF_8))
        val filterToken = if (filter.isEmpty()) "-" else bytesToHex(filter.toByteArray(Charsets.UTF_8))
        val cmd = "ELF_EXPORTS $libHex $filterToken ${limit.coerceIn(1, 5000)}"
        val resp = sendCommandLocked(cmd, isScan = false) ?: return 0L to "[]"
        if (!resp.startsWith("OK ")) return 0L to "[]"
        val body = resp.removePrefix("OK ").trim()
        val sp = body.indexOf(' ')
        if (sp <= 0) return 0L to "[]"
        val baseHex = body.substring(0, sp)
        val json = body.substring(sp + 1).trim()
        val modBase = parseHexLong(baseHex, 0, baseHex.length) ?: 0L
        return modBase to json
    }

    @Synchronized
    fun findSymbol(libName: String, symName: String): Long {
        if (libName.isBlank() || symName.isBlank()) return 0L
        val libHex = bytesToHex(libName.toByteArray(Charsets.UTF_8))
        val symHex = bytesToHex(symName.toByteArray(Charsets.UTF_8))
        val resp = sendCommandLocked("ELF_FIND_SYM $libHex $symHex", isScan = false) ?: return 0L
        if (!resp.startsWith("OK ")) return 0L
        val addrHex = resp.removePrefix("OK ").trim().substringBefore(' ')
        return parseHexLong(addrHex, 0, addrHex.length) ?: 0L
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    private fun hexToBytes(hex: String, expectedLen: Int): ByteArray {
        val out = ByteArray(expectedLen)
        val maxPairs = minOf(expectedLen, hex.length / 2)
        for (i in 0 until maxPairs) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi >= 0 && lo >= 0) {
                out[i] = ((hi shl 4) or lo).toByte()
            }
        }
        return out
    }
}
