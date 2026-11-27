package com.littlecodesauce.ggnext.lua

import android.content.Context
import android.system.Os
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import java.io.File

/**
 * GGNext 混合插件桥接层（为「Lua 写界面 + C++ (.so) 写功能」插件系统铺路）：
 * - 统一管理应用私有插件目录 (`filesDir/plugins/lua/` 与 `filesDir/plugins/native/<abi>/`)；
 * - 对外部传入的 `.so` 原生插件进行合法性校验并暂存到应用私有只读可执行目录（Android 14/15 `0555` 策略兼容），
 *   避免 `/sdcard` 等 `noexec` 挂载点 `dlopen` 失败及目录穿越风险；
 * - 扫描已注册的纯 Lua 模块供 `package.path` / `package.preload` 自动挂载。
 */
object LuaPluginBridge {

    private const val TAG = "LuaPluginBridge"
    private const val DIR_PLUGINS = "plugins"
    private const val DIR_LUA = "lua"
    private const val DIR_NATIVE = "native"
    private val VALID_MOD_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    fun luaPluginDir(context: Context): File {
        val dir = File(File(context.filesDir, DIR_PLUGINS), DIR_LUA)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun nativePluginDir(context: Context): File {
        val abi = AsmEngineManager.primaryAbi()
        val root = File(context.filesDir, DIR_PLUGINS)
        val nativeRoot = File(root, DIR_NATIVE)
        val abiDir = File(nativeRoot, abi)
        if (!abiDir.exists()) {
            abiDir.mkdirs()
            runCatching { Os.chmod(root.absolutePath, 493) }       // 0755
            runCatching { Os.chmod(nativeRoot.absolutePath, 493) } // 0755
            runCatching { Os.chmod(abiDir.absolutePath, 493) }     // 0755
        }
        return abiDir
    }

    /**
     * 校验并暂存外部 C++ 插件 `.so` 文件：
     * - 若路径已在应用 `nativeLibraryDir` 或 `filesDir/plugins/native/<abi>/` 中，直接校验权限并返回；
     * - 若路径位于外部存储（如 `/sdcard/...` 等 noexec 目录），复制到应用私有 `native/<abi>/` 并设为 `0555`；
     * - 校验 ELF 魔数 (`0x7F 'E' 'L' 'F'`)，拦截非法文件或路径穿越。
     */
    fun prepareNativePlugin(context: Context?, moduleName: String, rawSoPath: String): Result<String> {
        if (!VALID_MOD_NAME.matches(moduleName)) {
            return Result.failure(IllegalArgumentException("非法的插件模块名: $moduleName"))
        }
        if (rawSoPath.isBlank()) {
            return Result.failure(IllegalArgumentException("插件 .so 路径为空"))
        }
        val srcFile = runCatching { File(rawSoPath).canonicalFile }.getOrElse {
            return Result.failure(IllegalArgumentException("无法解析插件路径: $rawSoPath"))
        }
        if (!srcFile.exists() || !srcFile.isFile) {
            return Result.failure(IllegalArgumentException("插件动态库不存在: ${srcFile.absolutePath}"))
        }
        if (!srcFile.name.endsWith(".so")) {
            return Result.failure(IllegalArgumentException("原生插件必须是 .so 动态库"))
        }
        // ELF header check
        val header = ByteArray(4)
        val readLen = runCatching { srcFile.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (readLen < 4 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return Result.failure(IllegalArgumentException("文件不是有效的 ELF 动态库: ${srcFile.name}"))
        }

        if (context == null) {
            return Result.success(srcFile.absolutePath)
        }

        val appNativeDir = context.applicationInfo?.nativeLibraryDir?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        if (appNativeDir != null && srcFile.absolutePath.startsWith(appNativeDir.absolutePath + File.separator)) {
            return Result.success(srcFile.absolutePath)
        }

        val stagedDir = nativePluginDir(context)
        val stagedFile = File(stagedDir, "libggp_${moduleName}.so")
        return try {
            if (srcFile.canonicalPath != stagedFile.canonicalPath) {
                if (stagedFile.exists()) {
                    stagedFile.setWritable(true, false)
                    stagedFile.delete()
                }
                srcFile.copyTo(stagedFile, overwrite = true)
            }
            // Android 14/15 (targetSdk >= 34) requires dynamically loaded .so to be read-only (0555)
            stagedFile.setReadable(true, false)
            stagedFile.setExecutable(true, false)
            stagedFile.setWritable(false, false)
            runCatching { Os.chmod(stagedFile.absolutePath, 365) } // 0555
            AppLog.i(TAG, "Staged native Lua plugin '$moduleName' -> ${stagedFile.absolutePath}")
            Result.success(stagedFile.absolutePath)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Failed to stage native plugin '$moduleName'", t)
            Result.failure(t)
        }
    }

    /**
     * 列出应用私有插件目录中可用的纯 Lua 插件模块。
     */
    fun listInstalledLuaModules(context: Context): List<Pair<String, File>> {
        val dir = luaPluginDir(context)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".lua") } ?: return emptyList()
        return files.map { it.nameWithoutExtension to it }
    }
}
