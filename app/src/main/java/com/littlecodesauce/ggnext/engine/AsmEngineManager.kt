package com.littlecodesauce.ggnext.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.system.Os
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.model.AsmArch
import com.littlecodesauce.ggnext.model.AsmPackStatus
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.zip.ZipInputStream

/**
 * 独立 Keystone / Capstone 汇编引擎包管理器：
 * - 避免将庞大的 libkeystone.so / libcapstone.so 打入 APK 导致体积膨胀；
 * - 用户在主页导入 `ggnext-asm-engine-pack.zip` 后，自动提取匹配当前设备 ABI 的动态库至应用私有目录并 `dlopen` 加载；
 * - 提供线程安全 LRU 缓存的高速汇编与反汇编接口，同时兼容原版 GG 的 `~A8` / `~A` / `~T` / `~X86` / `~X64` 语法。
 */
object AsmEngineManager {

    private const val DIR_NAME = "asm_libs"
    private const val META_FILE = "manifest.json"

    @Volatile
    var status: AsmPackStatus = AsmPackStatus()
        private set

    private val disasmOneCache = Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > 1024
        }
    )

    private fun processCompatibleAbis(): List<String> {
        val bitMatched = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS?.toList().orEmpty()
        } else {
            Build.SUPPORTED_32_BIT_ABIS?.toList().orEmpty()
        }
        val all = Build.SUPPORTED_ABIS?.toList().orEmpty()
        return (bitMatched + all).distinct().ifEmpty {
            listOf(if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a")
        }
    }

    fun primaryAbi(): String = processCompatibleAbis().firstOrNull()?.takeIf { it.isNotBlank() } ?: "arm64-v8a"

    fun defaultArchForDevice(isTarget64Bit: Boolean = true): AsmArch {
        val abi = primaryAbi()
        return when {
            abi.startsWith("arm64") -> if (isTarget64Bit) AsmArch.ARM64 else AsmArch.ARM
            abi.startsWith("armeabi") -> AsmArch.ARM
            abi == "x86_64" -> if (isTarget64Bit) AsmArch.X86_64 else AsmArch.X86
            abi == "x86" -> AsmArch.X86
            else -> AsmArch.ARM64
        }
    }

    fun archFromString(archName: String): AsmArch = when (archName.lowercase()) {
        "aarch64", "arm64", "a8" -> AsmArch.ARM64
        "arm", "armv7", "a" -> AsmArch.ARM
        "thumb", "t" -> AsmArch.THUMB
        "x86_64", "x64" -> AsmArch.X86_64
        "x86", "i386" -> AsmArch.X86
        else -> defaultArchForDevice(true)
    }

    /**
     * 解析原版 GG 汇编前缀：`~A8 MOV X0, #1` / `~A BX LR` / `~T NOP` / `~X86 NOP` / `~X64 RET`
     */
    fun parseGgAsmPrefix(raw: String): Pair<AsmArch, String>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("~")) return null
        val upper = trimmed.uppercase()
        return when {
            upper.startsWith("~A8") && (trimmed.length == 3 || trimmed[3] == ' ' || trimmed[3] == ':') ->
                AsmArch.ARM64 to trimmed.substring(3).trimStart(' ', ':').trim()
            upper.startsWith("~X64") && (trimmed.length == 4 || trimmed[4] == ' ' || trimmed[4] == ':') ->
                AsmArch.X86_64 to trimmed.substring(4).trimStart(' ', ':').trim()
            upper.startsWith("~X86") && (trimmed.length == 4 || trimmed[4] == ' ' || trimmed[4] == ':') ->
                AsmArch.X86 to trimmed.substring(4).trimStart(' ', ':').trim()
            upper.startsWith("~A") && (trimmed.length == 2 || trimmed[2] == ' ' || trimmed[2] == ':') ->
                AsmArch.ARM to trimmed.substring(2).trimStart(' ', ':').trim()
            upper.startsWith("~T") && (trimmed.length == 2 || trimmed[2] == ' ' || trimmed[2] == ':') ->
                AsmArch.THUMB to trimmed.substring(2).trimStart(' ', ':').trim()
            else -> null
        }
    }

    private fun rootDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun autoLoad(context: Context): AsmPackStatus {
        val root = rootDir(context)
        val abis = processCompatibleAbis()
        var chosenAbi = ""
        var chosenDir: File? = null
        for (abi in abis) {
            val d = File(root, abi)
            if (File(d, "libkeystone.so").exists() || File(d, "libcapstone.so").exists()) {
                chosenAbi = abi
                chosenDir = d
                break
            }
        }
        if (chosenDir == null) {
            status = AsmPackStatus(installed = false, abi = primaryAbi())
            return status
        }
        return loadFromDirectory(context, chosenDir, chosenAbi, readManifestSummary(File(root, META_FILE)))
    }

    fun importFromUri(context: Context, uri: Uri): AsmPackStatus {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return AsmPackStatus(error = "无法读取所选压缩包文件")
        return stream.use { importFromStream(context, it) }
    }

    fun importFromFile(context: Context, file: File): AsmPackStatus {
        if (!file.exists() || !file.isFile) {
            return AsmPackStatus(error = "压缩包文件不存在：${file.absolutePath}")
        }
        return FileInputStream(file).use { importFromStream(context, it) }
    }

    private fun importFromStream(context: Context, input: InputStream): AsmPackStatus {
        val root = rootDir(context)
        val tempDir = File(context.cacheDir, "asm_pack_staging_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        return try {
            val supportedAbis = processCompatibleAbis()
            val foundByAbi = HashMap<String, MutableList<Pair<String, File>>>()
            var manifestText = ""

            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }
                    val normName = entry.name.replace('\\', '/').trimStart('/')
                    if (normName.contains("..")) {
                        zis.closeEntry()
                        continue
                    }
                    when {
                        normName.endsWith("manifest.json") -> {
                            manifestText = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        normName.endsWith("/libkeystone.so") || normName.endsWith("/libcapstone.so") ||
                            normName == "libkeystone.so" || normName == "libcapstone.so" -> {
                            val fileName = normName.substringAfterLast('/')
                            val matchedAbi = supportedAbis.firstOrNull { abi ->
                                normName.contains("/$abi/") || normName.startsWith("$abi/")
                            } ?: if (normName == fileName) primaryAbi() else ""
                            if (matchedAbi.isNotEmpty()) {
                                val outF = File(File(tempDir, matchedAbi).apply { mkdirs() }, fileName)
                                FileOutputStream(outF).use { fos -> zis.copyTo(fos) }
                                foundByAbi.getOrPut(matchedAbi) { mutableListOf() }.add(fileName to outF)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }

            // Pick highest-priority ABI matching current process bitness
            val bestAbi = supportedAbis.firstOrNull { abi ->
                val list = foundByAbi[abi].orEmpty()
                list.any { it.first == "libkeystone.so" } && list.any { it.first == "libcapstone.so" }
            } ?: supportedAbis.firstOrNull { foundByAbi[it]?.isNotEmpty() == true }

            if (bestAbi == null) {
                val err = "压缩包中未找到适配当前进程架构 (${supportedAbis.joinToString("/")}) 的 libkeystone.so / libcapstone.so"
                status = status.copy(error = err)
                return status
            }

            if (root.exists()) root.deleteRecursively()
            val dstAbiDir = File(root, bestAbi).apply { mkdirs() }
            runCatching { Os.chmod(root.absolutePath, 493) } // 0755
            runCatching { Os.chmod(dstAbiDir.absolutePath, 493) } // 0755
            for ((name, stagedFile) in foundByAbi[bestAbi].orEmpty()) {
                val target = File(dstAbiDir, name)
                stagedFile.copyTo(target, overwrite = true)
                // Android 14/15 (targetSdk >= 34) requires dynamically loaded .so files to be read-only (0555)
                target.setReadable(true, false)
                target.setExecutable(true, false)
                target.setWritable(false, false)
                runCatching { Os.chmod(target.absolutePath, 365) } // 0555 (r-xr-xr-x)
            }
            if (manifestText.isNotBlank()) {
                File(root, META_FILE).writeText(manifestText)
            }
            disasmOneCache.clear()
            loadFromDirectory(context, dstAbiDir, bestAbi, parseManifestSummary(manifestText))
        } catch (t: Throwable) {
            AppLog.e("AsmEngineManager", "Import asm pack failed", t)
            val err = "导入失败：${t.message ?: t.javaClass.simpleName}"
            status = status.copy(error = err)
            status
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun clearPack(context: Context): AsmPackStatus {
        val root = rootDir(context)
        if (root.exists()) root.deleteRecursively()
        disasmOneCache.clear()
        status = AsmPackStatus(installed = false, abi = primaryAbi())
        return status
    }

    private fun loadFromDirectory(context: Context, dir: File, abi: String, versionInfo: String): AsmPackStatus {
        if (!NativeMemBridge.ensureLoaded(context)) {
            val reason = NativeMemBridge.loadError.ifBlank { "libmem_engine_jni.so 未打包或架构不匹配" }
            status = AsmPackStatus(
                installed = true,
                abi = abi,
                libDir = dir.absolutePath,
                versionInfo = versionInfo,
                error = "原生 JNI 库加载失败：$reason"
            )
            return status
        }
        // Ensure extracted libraries keep 0555 permissions before dlopen on Android 14/15
        listOf("libkeystone.so", "libcapstone.so").forEach { name ->
            val f = File(dir, name)
            if (f.exists()) {
                f.setWritable(false, false)
                runCatching { Os.chmod(f.absolutePath, 365) }
            }
        }
        val msg = NativeMemBridge.nativeLoadAsmEngine(dir.absolutePath)
        val flags = NativeMemBridge.nativeGetAsmEngineFlags()
        val ksOk = (flags and 1) != 0
        val csOk = (flags and 2) != 0
        val err = if (msg == "OK" || (ksOk && csOk)) "" else msg
        status = AsmPackStatus(
            installed = true,
            keystoneReady = ksOk,
            capstoneReady = csOk,
            abi = abi,
            libDir = dir.absolutePath,
            versionInfo = versionInfo.ifEmpty { "Keystone + Capstone ($abi)" },
            error = err
        )
        AppLog.i("AsmEngineManager", "Loaded ASM pack abi=$abi ks=$ksOk cs=$csOk err=$err")
        return status
    }

    private fun readManifestSummary(file: File): String {
        if (!file.exists()) return ""
        return runCatching { parseManifestSummary(file.readText()) }.getOrDefault("")
    }

    private fun parseManifestSummary(jsonText: String): String {
        if (jsonText.isBlank()) return ""
        return runCatching {
            val obj = JSONObject(jsonText)
            val cs = obj.optString("capstone", "")
            val ks = obj.optString("keystone", "")
            if (cs.isNotEmpty() || ks.isNotEmpty()) "Keystone $ks · Capstone $cs" else obj.optString("name", "")
        }.getOrDefault("")
    }

    fun assemble(arch: AsmArch, address: Long, code: String): Result<ByteArray> {
        val clean = code.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("请输入汇编指令"))
        if (!NativeMemBridge.isLoaded) return Result.failure(IllegalStateException("原生库未加载"))
        val resp = NativeMemBridge.nativeAssembleWithError(arch.code, address, clean)
        if (resp.startsWith("OK:")) {
            val hex = resp.removePrefix("OK:")
            return Result.success(hexToBytes(hex))
        }
        val errMsg = resp.removePrefix("ERR:").ifBlank { "汇编失败" }
        return Result.failure(IllegalArgumentException(errMsg))
    }

    fun disassembleWord(arch: AsmArch, address: Long, rawValue: Long, byteSize: Int = 4): String {
        val key = (((address * 31L) + rawValue) * 31L) + arch.code * 7L + byteSize
        synchronized(disasmOneCache) {
            disasmOneCache[key]?.let { return it }
        }
        val bytes = ByteArray(byteSize.coerceIn(2, 8)) { idx ->
            ((rawValue ushr (idx * 8)) and 0xFFL).toByte()
        }
        val text = if (NativeMemBridge.isLoaded) {
            NativeMemBridge.nativeDisassembleOne(arch.code, address, bytes)
        } else ""
        if (text.isNotEmpty()) {
            synchronized(disasmOneCache) { disasmOneCache[key] = text }
        }
        return text
    }

    fun disassembleHex(archName: String, address: Long, insnHex: String): String {
        if (insnHex.length < 4 || !NativeMemBridge.isLoaded) return ""
        val arch = archFromString(archName)
        val bytes = hexToBytes(insnHex)
        if (bytes.isEmpty()) return ""
        return NativeMemBridge.nativeDisassembleOne(arch.code, address, bytes).uppercase()
    }

    fun disassembleBlockMap(arch: AsmArch, baseAddress: Long, bytes: ByteArray, maxInsns: Int = 1024): Map<Long, String> {
        if (!NativeMemBridge.isLoaded || bytes.isEmpty()) return emptyMap()
        val raw = NativeMemBridge.nativeDisassembleBlock(arch.code, baseAddress, bytes, maxInsns)
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<Long, String>(maxInsns)
        raw.lineSequence().forEach { line ->
            val p1 = line.indexOf('|')
            if (p1 <= 0) return@forEach
            val p2 = line.indexOf('|', p1 + 1)
            if (p2 <= p1) return@forEach
            val addr = line.substring(0, p1).toULongOrNull(16)?.toLong() ?: return@forEach
            val text = line.substring(p2 + 1)
            if (text.isNotEmpty()) out[addr] = text
        }
        return out
    }

    fun bytesToSpacedHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val n = clean.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi >= 0 && lo >= 0) out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
