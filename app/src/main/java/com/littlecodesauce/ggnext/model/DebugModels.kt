package com.littlecodesauce.ggnext.model

import org.json.JSONArray

enum class BpType(val code: Int, val label: String, val shortLabel: String) {
    READ(1, "只读", "只读"),
    WRITE(2, "只写", "只写"),
    RW(3, "读写", "读写"),
    EXEC(4, "执行", "执行");

    companion object {
        fun fromCode(code: Int): BpType = entries.firstOrNull { it.code == code } ?: RW
    }
}

enum class BpMode(val code: Int, val label: String, val desc: String) {
    PERF(0, "Perf 硬件断点", "基于内核 perf_event_open，低侵入无停顿（需 Root 与内核支持）"),
    PTRACE(1, "Ptrace 断点", "基于 ptrace 硬件调试寄存器，支持完整浮点寄存器与免 Root 同 UID 模式");

    companion object {
        fun fromCode(code: Int): BpMode = entries.firstOrNull { it.code == code } ?: PERF
    }
}

enum class AsmArch(
    val code: Int,
    val shortCode: String,
    val displayName: String,
    val ggPrefix: String,
    val fixedStep: Int
) {
    ARM64(0, "A8", "ARM64 (AArch64)", "~A8", 4),
    ARM(1, "A", "ARM (32-bit)", "~A", 4),
    THUMB(2, "T", "Thumb / Thumb-2", "~T", 2),
    X86(3, "x86", "x86 (32-bit)", "~X86", 1),
    X86_64(4, "x64", "x86_64 (64-bit)", "~X64", 1);

    companion object {
        fun fromCode(code: Int): AsmArch = entries.firstOrNull { it.code == code } ?: ARM64
    }
}

data class BpRegEntry(
    val name: String,
    val value: Long
) {
    val hex: String
        get() = java.lang.Long.toUnsignedString(value, 16).uppercase()
}

data class BpFpRegEntry(
    val name: String,
    val hexValue: String
)

data class BpCallFrame(
    val address: Long,
    val moduleName: String,
    val moduleOffset: Long
) {
    val addressHex: String
        get() = java.lang.Long.toUnsignedString(address, 16).uppercase()

    val offsetHex: String
        get() = "0x" + java.lang.Long.toUnsignedString(moduleOffset, 16).uppercase()
}

data class BpHitGroup(
    val pc: Long,
    val hitType: BpType,
    val tid: Int,
    val arch: String,
    val moduleName: String,
    val moduleOffset: Long,
    val insnHex: String,
    val disasmText: String,
    val count: Long,
    val sp: Long,
    val lr: Long,
    val gprs: List<BpRegEntry>,
    val fprs: List<BpFpRegEntry>,
    val callstack: List<BpCallFrame>
) {
    val pcHex: String get() = java.lang.Long.toUnsignedString(pc, 16).uppercase()
    val spHex: String get() = java.lang.Long.toUnsignedString(sp, 16).uppercase()
    val lrHex: String get() = java.lang.Long.toUnsignedString(lr, 16).uppercase()
}

data class BpSession(
    val id: Int,
    val pid: Int,
    val address: Long,
    val type: BpType,
    val size: Int,
    val mode: BpMode,
    val captureCallstack: Boolean,
    val running: Boolean,
    val paused: Boolean,
    val attachedThreads: Int,
    val totalThreads: Int,
    val totalHits: Long,
    val tracerPid: Int,
    val sampleTids: List<Int>,
    val lastError: String,
    val hits: List<BpHitGroup>
) {
    val addressHex: String
        get() = java.lang.Long.toUnsignedString(address, 16).uppercase()

    val isActive: Boolean
        get() = running && !paused
}

/** 硬件断点实时寄存器轨迹单条事件（类 Frida Interceptor 实时输出） */
data class BpTraceEvent(
    val seq: Long,
    val timestampMs: Long,
    val bpId: Int,
    val tid: Int,
    val hitType: BpType,
    val pc: Long,
    val sp: Long,
    val lr: Long,
    val arch: String,
    val moduleName: String,
    val moduleOffset: Long,
    val insnHex: String,
    val disasmText: String,
    val gprs: List<BpRegEntry>,
    val fprs: List<BpFpRegEntry>
) {
    val pcHex: String get() = java.lang.Long.toUnsignedString(pc, 16).uppercase()
    val offsetHex: String get() = "0x" + java.lang.Long.toUnsignedString(moduleOffset, 16).uppercase()
    val formattedTime: String
        get() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(timestampMs))

    fun formatFridaBlock(filterNames: Set<String> = emptySet(), showFpr: Boolean = false): String {
        val sb = StringBuilder(256)
        val loc = if (moduleName.isNotBlank() && moduleName != "[anon]") "$moduleName+$offsetHex" else "0x$pcHex"
        sb.append('[').append(formattedTime).append("] #").append(seq)
            .append(" BP#").append(bpId)
            .append(" TID:").append(tid)
            .append(' ').append(loc)
        if (disasmText.isNotBlank()) sb.append("  ").append(disasmText)
        val shownGprs = if (filterNames.isEmpty()) gprs else gprs.filter { it.name.uppercase() in filterNames }
        if (shownGprs.isNotEmpty()) {
            sb.append('\n')
            shownGprs.chunked(4).forEachIndexed { idx, row ->
                if (idx > 0) sb.append('\n')
                sb.append("  ")
                sb.append(row.joinToString("  ") { "${it.name}=${it.hex}" })
            }
        }
        if (showFpr && fprs.isNotEmpty()) {
            val shownFprs = if (filterNames.isEmpty()) fprs.take(8) else fprs.filter { it.name.uppercase() in filterNames }
            if (shownFprs.isNotEmpty()) {
                sb.append('\n')
                shownFprs.chunked(2).forEachIndexed { idx, row ->
                    if (idx > 0) sb.append('\n')
                    sb.append("  ")
                    sb.append(row.joinToString("  ") { "${it.name}=${it.hexValue}" })
                }
            }
        }
        return sb.toString()
    }
}

data class AsmPackStatus(
    val installed: Boolean = false,
    val keystoneReady: Boolean = false,
    val capstoneReady: Boolean = false,
    val abi: String = "",
    val libDir: String = "",
    val versionInfo: String = "",
    val error: String = ""
) {
    val fullyReady: Boolean get() = keystoneReady && capstoneReady
}

object BpSnapshotParser {

    fun parse(
        json: String,
        disassembleHex: (arch: String, pc: Long, insnHex: String) -> String
    ): List<BpSession> {
        if (json.isBlank() || json.trim() == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<BpSession>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tidsArr = obj.optJSONArray("sampleTids")
                val sampleTids = buildList {
                    if (tidsArr != null) {
                        for (t in 0 until tidsArr.length()) add(tidsArr.optInt(t))
                    }
                }
                val hitsArr = obj.optJSONArray("hits")
                val hits = buildList {
                    if (hitsArr != null) {
                        for (h in 0 until hitsArr.length()) {
                            val ho = hitsArr.getJSONObject(h)
                            val pc = ho.optLong("pc", 0L)
                            val arch = ho.optString("arch", "aarch64")
                            val insnHex = ho.optString("insnHex", "")
                            val disasm = if (insnHex.isNotEmpty()) {
                                disassembleHex(arch, pc, insnHex)
                            } else ""

                            val gprsArr = ho.optJSONArray("gprs")
                            val gprs = buildList {
                                if (gprsArr != null) {
                                    for (r in 0 until gprsArr.length()) {
                                        val ro = gprsArr.getJSONObject(r)
                                        add(BpRegEntry(ro.optString("n"), ro.optLong("v")))
                                    }
                                }
                            }
                            val fprsArr = ho.optJSONArray("fprs")
                            val fprs = buildList {
                                if (fprsArr != null) {
                                    for (r in 0 until fprsArr.length()) {
                                        val ro = fprsArr.getJSONObject(r)
                                        add(BpFpRegEntry(ro.optString("n"), ro.optString("v")))
                                    }
                                }
                            }
                            val stackArr = ho.optJSONArray("callstack")
                            val callstack = buildList {
                                if (stackArr != null) {
                                    for (c in 0 until stackArr.length()) {
                                        val co = stackArr.getJSONObject(c)
                                        add(
                                            BpCallFrame(
                                                address = co.optLong("addr"),
                                                moduleName = co.optString("mod", "[anon]"),
                                                moduleOffset = co.optLong("off", 0L)
                                            )
                                        )
                                    }
                                }
                            }
                            val rawHitType = BpType.fromCode(ho.optInt("hitType", 3))
                            val resolvedHitType = classifyDisasmHitType(disasm, rawHitType)
                            add(
                                BpHitGroup(
                                    pc = pc,
                                    hitType = resolvedHitType,
                                    tid = ho.optInt("tid", 0),
                                    arch = arch,
                                    moduleName = ho.optString("module", "[anon]"),
                                    moduleOffset = ho.optLong("offset", 0L),
                                    insnHex = insnHex,
                                    disasmText = disasm,
                                    count = ho.optLong("count", 0L),
                                    sp = ho.optLong("sp", 0L),
                                    lr = ho.optLong("lr", 0L),
                                    gprs = gprs,
                                    fprs = fprs,
                                    callstack = callstack
                                )
                            )
                        }
                    }
                }
                out.add(
                    BpSession(
                        id = obj.optInt("id"),
                        pid = obj.optInt("pid"),
                        address = obj.optLong("address"),
                        type = BpType.fromCode(obj.optInt("type", 3)),
                        size = obj.optInt("size", 4),
                        mode = BpMode.fromCode(obj.optInt("mode", 0)),
                        captureCallstack = obj.optBoolean("captureCallstack", true),
                        running = obj.optBoolean("running", false),
                        paused = obj.optBoolean("paused", false),
                        attachedThreads = obj.optInt("attachedThreads", 0),
                        totalThreads = obj.optInt("totalThreads", 0),
                        totalHits = obj.optLong("totalHits", 0L),
                        tracerPid = obj.optInt("tracerPid", 0),
                        sampleTids = sampleTids,
                        lastError = obj.optString("lastError", ""),
                        hits = hits
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun parseTraceEvents(
        json: String,
        disassembleHex: (arch: String, pc: Long, insnHex: String) -> String
    ): List<BpTraceEvent> {
        if (json.isBlank() || json.trim() == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<BpTraceEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pc = obj.optLong("pc", 0L)
                val arch = obj.optString("arch", "aarch64")
                val insnHex = obj.optString("insnHex", "")
                val disasm = if (insnHex.isNotEmpty()) disassembleHex(arch, pc, insnHex) else ""
                val gprsArr = obj.optJSONArray("gprs")
                val gprs = buildList {
                    if (gprsArr != null) {
                        for (r in 0 until gprsArr.length()) {
                            val ro = gprsArr.getJSONObject(r)
                            add(BpRegEntry(ro.optString("n"), ro.optLong("v")))
                        }
                    }
                }
                val fprsArr = obj.optJSONArray("fprs")
                val fprs = buildList {
                    if (fprsArr != null) {
                        for (r in 0 until fprsArr.length()) {
                            val ro = fprsArr.getJSONObject(r)
                            add(BpFpRegEntry(ro.optString("n"), ro.optString("v")))
                        }
                    }
                }
                val rawHitType = BpType.fromCode(obj.optInt("hitType", 3))
                out.add(
                    BpTraceEvent(
                        seq = obj.optLong("seq", 0L),
                        timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                        bpId = obj.optInt("bpId", 0),
                        tid = obj.optInt("tid", 0),
                        hitType = classifyDisasmHitType(disasm, rawHitType),
                        pc = pc,
                        sp = obj.optLong("sp", 0L),
                        lr = obj.optLong("lr", 0L),
                        arch = arch,
                        moduleName = obj.optString("module", "[anon]"),
                        moduleOffset = obj.optLong("offset", 0L),
                        insnHex = insnHex,
                        disasmText = disasm,
                        gprs = gprs,
                        fprs = fprs
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * 根据反汇编指令实际语义判定操作方向（只读 / 只写 / 执行），
     * 避免直接展示断点配置类型（如“读写”）掩盖真实内存访问性质。
     */
    fun classifyDisasmHitType(disasmText: String, fallback: BpType): BpType {
        if (fallback == BpType.EXEC) return BpType.EXEC
        val trimmed = disasmText.trim()
        if (trimmed.isEmpty()) return fallback
        val spaceIdx = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        val mnemonic = (if (spaceIdx > 0) trimmed.substring(0, spaceIdx) else trimmed).uppercase()
        val operands = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1).trim() else ""

        // ARM64 / ARM32 / Thumb store instructions -> WRITE
        if (mnemonic.startsWith("STR") || mnemonic.startsWith("STP") || mnemonic.startsWith("STUR") ||
            mnemonic.startsWith("STXR") || mnemonic.startsWith("STLR") || mnemonic.startsWith("STXP") ||
            mnemonic.startsWith("STNP") || mnemonic.startsWith("STTR") || mnemonic.startsWith("STLUR") ||
            mnemonic.startsWith("ST1") || mnemonic.startsWith("ST2") || mnemonic.startsWith("ST3") ||
            mnemonic.startsWith("ST4") || mnemonic.startsWith("STM") || mnemonic.startsWith("VSTR") ||
            mnemonic.startsWith("VSTM") || mnemonic.startsWith("VST") || mnemonic == "PUSH" ||
            mnemonic.startsWith("STADD") || mnemonic.startsWith("STSET") || mnemonic.startsWith("STCLR") ||
            mnemonic.startsWith("STEOR") || mnemonic.startsWith("SWP") || mnemonic.startsWith("CAS") ||
            mnemonic.startsWith("LDADD") || mnemonic.startsWith("LDSET") || mnemonic.startsWith("LDCLR") ||
            mnemonic.startsWith("LDEOR") || mnemonic.startsWith("STOS")
        ) {
            return BpType.WRITE
        }
        // ARM64 / ARM32 / Thumb load instructions -> READ
        if (mnemonic.startsWith("LDR") || mnemonic.startsWith("LDP") || mnemonic.startsWith("LDUR") ||
            mnemonic.startsWith("LDXR") || mnemonic.startsWith("LDAR") || mnemonic.startsWith("LDXP") ||
            mnemonic.startsWith("LDNP") || mnemonic.startsWith("LDTR") || mnemonic.startsWith("LDAUR") ||
            mnemonic.startsWith("LD1") || mnemonic.startsWith("LD2") || mnemonic.startsWith("LD3") ||
            mnemonic.startsWith("LD4") || mnemonic.startsWith("LDM") || mnemonic.startsWith("VLDR") ||
            mnemonic.startsWith("VLDM") || mnemonic.startsWith("VLD") || mnemonic == "POP" ||
            mnemonic.startsWith("PRFM") || mnemonic.startsWith("LODS")
        ) {
            return BpType.READ
        }
        // x86 / x86_64 Intel syntax: destination is first operand; memory bracket `[...]` in dst -> WRITE, in src -> READ
        val commaIdx = operands.indexOf(',')
        if (commaIdx > 0) {
            val dst = operands.substring(0, commaIdx)
            val src = operands.substring(commaIdx + 1)
            val dstMem = dst.contains('[')
            val srcMem = src.contains('[')
            if (dstMem && !srcMem) {
                return if (mnemonic == "CMP" || mnemonic == "TEST") BpType.READ else BpType.WRITE
            }
            if (srcMem && !dstMem) {
                return BpType.READ
            }
        } else if (operands.contains('[')) {
            if (mnemonic == "INC" || mnemonic == "DEC" || mnemonic == "NEG" || mnemonic == "NOT" ||
                mnemonic.startsWith("SET")
            ) {
                return BpType.WRITE
            }
            return BpType.READ
        }
        return fallback
    }
}
