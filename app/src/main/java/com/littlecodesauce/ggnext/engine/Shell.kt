package com.littlecodesauce.ggnext.engine

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/** 通过 su 执行 shell 命令，返回输出行 */
object Shell {

    data class Result(val exitCode: Int, val out: List<String>)

    @Volatile
    private var cachedRoot: Boolean? = null

    fun resetRootCache() {
        cachedRoot = null
    }

    fun hasRoot(): Boolean {
        if (cachedRoot == true) return true
        val ok = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text.contains("uid=0")
        } catch (_: Throwable) {
            false
        } || try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            DataOutputStream(p.outputStream).use { it.writeBytes("id\nexit\n") }
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            cachedRoot = true
        }
        return ok
    }

    fun run(vararg commands: String): Result = runAs(commands.joinToString("\n"))

    fun runAs(script: String, asRoot: Boolean = true): Result {
        return try {
            if (asRoot) {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                DataOutputStream(p.outputStream).use { it.writeBytes(script + "\nexit\n") }
                val out = ArrayList<String>()
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines -> lines.forEach { out.add(it) } }
                val code = p.waitFor()
                Result(code, out)
            } else {
                val p = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
                val out = ArrayList<String>()
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines -> lines.forEach { out.add(it) } }
                val code = p.waitFor()
                Result(code, out)
            }
        } catch (t: Throwable) {
            Result(-1, listOf("shell error: ${t.message}"))
        }
    }

    fun su(command: String): String = runAs(command, asRoot = true).out.joinToString("\n")

    fun suLines(command: String): List<String> = runAs(command, asRoot = true).out

    /** 直接读文件（root 下也能读 /proc/<pid>/maps） */
    fun readFile(path: String): String {
        return try {
            File(path).readText()
        } catch (_: Throwable) {
            if (!hasRoot()) return ""
            val r = runAs("cat $path")
            if (r.exitCode == 0) r.out.joinToString("\n") else ""
        }
    }
}
