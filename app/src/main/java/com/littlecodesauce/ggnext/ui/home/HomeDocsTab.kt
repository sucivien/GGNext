package com.littlecodesauce.ggnext.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.I18n
import com.littlecodesauce.ggnext.ui.components.expandEnter
import com.littlecodesauce.ggnext.ui.components.expandExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Rose

private enum class DocCategory(val zhLabel: String, val enLabel: String) {
    ALL("全部", "All"),
    HOOK("ShadowHook 与主动调用", "ShadowHook & Active Call"),
    BREAKPOINT("硬件断点与寄存器", "Hardware Breakpoints"),
    ELF("ELF / .so 解析", "ELF / .so Analysis"),
    UI_PLUGIN("声明式 UI 与插件", "Declarative UI & Plugins"),
    CONSTANTS("常量与兼容说明", "Constants & Compat")
}

private data class ApiParamSpec(
    val name: String,
    val type: String,
    val descZh: String,
    val descEn: String
)

private data class ApiDocEntry(
    val id: String,
    val category: DocCategory,
    val name: String,
    val signature: String,
    val summaryZh: String,
    val summaryEn: String,
    val params: List<ApiParamSpec>,
    val returnsZh: String,
    val returnsEn: String,
    val exampleLua: String
)

private val GGNEXT_API_DOCS: List<ApiDocEntry> = listOf(
    // ==================== ShadowHook & Active Call ====================
    ApiDocEntry(
        id = "hook_symbol",
        category = DocCategory.HOOK,
        name = "gg.hookSymbol",
        signature = "gg.hookSymbol(libName, symName [, opts])",
        summaryZh = "基于内置 ShadowHook 按动态库与符号名安装 Inline Hook，支持参数/返回值拦截、修改与直接绕过原函数，也可注册 Lua 命中回调。",
        summaryEn = "Install a ShadowHook inline hook by shared library & symbol name. Supports argument/return inspection, override, bypass, and Lua callbacks.",
        params = listOf(
            ApiParamSpec("libName", "string", "目标 .so 名称或路径（如 \"libil2cpp.so\"）", "Target .so name or path (e.g. \"libil2cpp.so\")"),
            ApiParamSpec("symName", "string", "导出符号名（支持 C/C++ 修饰符号）", "Exported symbol name (including C++ mangled symbols)"),
            ApiParamSpec("opts", "table?", "可选配置：{ logHits, bypass, overrideRet, retValue, overrideArgs={[0]=val}, onHit=fn }", "Optional table: { logHits, bypass, overrideRet, retValue, overrideArgs={[0]=val}, onHit=fn }")
        ),
        returnsZh = "成功返回 hookId (integer)；失败返回 nil, errMsg (string)。",
        returnsEn = "Returns hookId (integer) on success; nil, errMsg (string) on failure.",
        exampleLua = """local id, err = gg.hookSymbol("libil2cpp.so", "Player_GetCoin", {
    logHits = true,
    overrideRet = true,
    retValue = 999999,
    onHit = function(ev)
        print("hit tid=" .. ev.tid .. " x0=" .. ev.x0 .. " ret=" .. ev.ret)
    end
})
assert(id, err)
-- 在循环中轮询派发 Lua 回调
gg.sleep(500)
gg.pollHooks(id)"""
    ),
    ApiDocEntry(
        id = "hook_address",
        category = DocCategory.HOOK,
        name = "gg.hookAddress",
        signature = "gg.hookAddress(address [, opts])",
        summaryZh = "按运行时内存地址安装 ShadowHook Inline Hook（适用于无导出符号或 Stripped .so 函数，可配合 gg.getModuleBase 计算函数地址）。",
        summaryEn = "Install a ShadowHook inline hook at a raw runtime address (ideal for stripped .so functions combined with gg.getModuleBase).",
        params = listOf(
            ApiParamSpec("address", "integer|string", "目标函数运行时地址（64 位无符号整数或 0x 十六进制字符串）", "Target function runtime address (uint64 integer or 0x hex string)"),
            ApiParamSpec("opts", "table?", "可选配置：{ logHits, bypass, overrideRet, retValue, overrideArgs, onHit }", "Optional table: { logHits, bypass, overrideRet, retValue, overrideArgs, onHit }")
        ),
        returnsZh = "成功返回 hookId (integer)；失败返回 nil, errMsg (string)。",
        returnsEn = "Returns hookId (integer) on success; nil, errMsg (string) on failure.",
        exampleLua = """local base = gg.getModuleBase("libil2cpp.so")
local fnAddr = base + 0x1A2B30
local id, err = gg.hookAddress(fnAddr, {
    logHits = true,
    overrideArgs = { [1] = 1000 } -- 修改第 2 个参数 (x1/r1)
})
print("hookId =", id, err)"""
    ),
    ApiDocEntry(
        id = "intercept_address",
        category = DocCategory.HOOK,
        name = "gg.interceptAddress",
        signature = "gg.interceptAddress(address [, opts])",
        summaryZh = "基于 ShadowHook 指令级拦截（shadowhook_intercept_instr_addr），在任意指令地址读取或修改 CPU 通用寄存器，无需函数完整序言。",
        summaryEn = "Instruction-level register interceptor via shadowhook_intercept_instr_addr. Inspect or patch GPRs at arbitrary instruction addresses.",
        params = listOf(
            ApiParamSpec("address", "integer|string", "目标指令运行时地址", "Target instruction runtime address"),
            ApiParamSpec("opts", "table?", "可选配置：{ logHits, overrideArgs={[regIdx]=val}, onHit=fn }", "Optional table: { logHits, overrideArgs={[regIdx]=val}, onHit=fn }")
        ),
        returnsZh = "成功返回 hookId (integer)；失败返回 nil, errMsg (string)。",
        returnsEn = "Returns hookId (integer) on success; nil, errMsg (string) on failure.",
        exampleLua = """local base = gg.getModuleBase("libgame.so")
local id, err = gg.interceptAddress(base + 0x4580C, {
    logHits = true,
    overrideArgs = { [0] = 500 } -- 将 X0/R0 改为 500
})
if id then gg.pollHooks(id) end"""
    ),
    ApiDocEntry(
        id = "call_original",
        category = DocCategory.HOOK,
        name = "gg.callOriginal",
        signature = "gg.callOriginal(hookId, args [, retType])",
        summaryZh = "通过 ShadowHook 跳板（orig trampoline）安全主动调用已被 Hook 的原函数，带 SIGSEGV/SIGBUS 信号防崩溃保护，支持整型/指针/浮点/字符串参数。",
        summaryEn = "Safely invoke the original function through ShadowHook's trampoline with signal crash protection. Supports int, pointer, float/double, and C-string args.",
        params = listOf(
            ApiParamSpec("hookId", "integer", "已安装 Inline Hook 的 hookId", "Hook ID of an active inline hook"),
            ApiParamSpec("args", "table?", "参数数组，支持直接传值或 {type=\"int\"|\"ptr\"|\"float\"|\"double\"|\"cstr\", value=...}", "Argument list: raw values or {type=\"int\"|\"ptr\"|\"float\"|\"double\"|\"cstr\", value=...}"),
            ApiParamSpec("retType", "string?", "返回值类型：\"int\"(默认) / \"ptr\" / \"float\" / \"double\" / \"cstr\"", "Return type: \"int\" (default), \"ptr\", \"float\", \"double\", or \"cstr\"")
        ),
        returnsZh = "成功返回结果表 { ret, retHex, retFloat?, retDouble?, retStr? }；失败返回 nil, errMsg。",
        returnsEn = "Returns result table { ret, retHex, retFloat?, retDouble?, retStr? } on success; nil, errMsg on failure.",
        exampleLua = """-- 通过跳板安全调用原函数 AddGold(playerPtr, 500)
local res, err = gg.callOriginal(hookId, {
    { type = "ptr", value = playerPtr },
    { type = "int", value = 500 }
}, "int")
if res then
    print("Original returned:", res.ret, res.retHex)
end"""
    ),
    ApiDocEntry(
        id = "call_function",
        category = DocCategory.HOOK,
        name = "gg.callFunction",
        signature = "gg.callFunction(address, args [, retType])",
        summaryZh = "直接安全主动调用目标进程内任意函数地址（无需预先安装 Hook），自动适配 AAPCS64/AAPCS32 调用约定并进行信号异常捕获。",
        summaryEn = "Safely call an arbitrary function address directly without pre-hooking, handling ARM64/ARM32 ABI conventions and signal guards.",
        params = listOf(
            ApiParamSpec("address", "integer|string", "目标函数运行时地址", "Target function runtime address"),
            ApiParamSpec("args", "table?", "参数数组（最多 8 个通用参数 + 8 个浮点参数）", "Argument array (up to 8 GPR + 8 FPR arguments)"),
            ApiParamSpec("retType", "string?", "返回值类型：\"int\" / \"ptr\" / \"float\" / \"double\" / \"cstr\"", "Return type: \"int\", \"ptr\", \"float\", \"double\", or \"cstr\"")
        ),
        returnsZh = "成功返回结果表 { ret, retHex, retFloat?, retDouble?, retStr? }；失败返回 nil, errMsg。",
        returnsEn = "Returns result table { ret, retHex, retFloat?, retDouble?, retStr? } on success; nil, errMsg on failure.",
        exampleLua = """local fn = gg.findSymbol("libil2cpp.so", "il2cpp_domain_get")
local res, err = gg.callFunction(fn, {}, "ptr")
print("domain ptr =", res and res.retHex or err)"""
    ),
    ApiDocEntry(
        id = "hook_manage",
        category = DocCategory.HOOK,
        name = "gg.updateHook / unhook / getHooks / getHookLogs",
        signature = "gg.updateHook(id, opts) | gg.unhook(id) | gg.unhookAll() | gg.getHooks() | gg.getHookLogs([id, sinceSeq, limit])",
        summaryZh = "动态更新 Hook 规则（如运行时开关 bypass/修改返回值）、卸载单个或全部 Hook、查询活动 Hook 列表与拉取环形命中日志。",
        summaryEn = "Update live hook rules dynamically, uninstall one or all hooks, list active hooks, and query or clear ring-buffered hit logs.",
        params = listOf(
            ApiParamSpec("hookId", "integer?", "目标 Hook ID（0 或省略表示全部）", "Target hook ID (0 or omitted for all)"),
            ApiParamSpec("sinceSeq", "integer?", "增量拉取起始序号", "Incremental fetch start sequence number"),
            ApiParamSpec("limit", "integer?", "单次最多返回日志条数（默认 64）", "Maximum log entries returned (default 64)")
        ),
        returnsZh = "getHooks() 返回 Hook 数组；getHookLogs() 返回命中日志数组；updateHook/unhook 返回 boolean。",
        returnsEn = "getHooks() returns hook array; getHookLogs() returns hit log array; updateHook/unhook return boolean.",
        exampleLua = """gg.updateHook(id, { overrideRet = true, retValue = 8888 })
local hooks = gg.getHooks()
print("Active hooks:", #hooks)
local logs = gg.getHookLogs(id, 0, 20)
for _, ev in ipairs(logs) do
    print(ev.seq, ev.tid, ev.x0, ev.ret)
end
gg.clearHookLogs(id)
gg.unhook(id)"""
    ),

    // ==================== Hardware Breakpoints ====================
    ApiDocEntry(
        id = "bp_add",
        category = DocCategory.BREAKPOINT,
        name = "gg.addBreakpoint",
        signature = "gg.addBreakpoint(address [, type [, size [, mode [, callstack]]]])",
        summaryZh = "在目标进程安装硬件读写/执行断点（支持 Perf 低侵入与 Ptrace 双后端），命中后自动采集完整通用/浮点寄存器及栈回溯。",
        summaryEn = "Install a hardware watchpoint/breakpoint (Perf or Ptrace backend) capturing live GPR/FPR registers and callstacks on hit.",
        params = listOf(
            ApiParamSpec("address", "integer|string", "监控的内存或指令地址", "Memory or instruction address to watch"),
            ApiParamSpec("type", "integer?", "断点类型：gg.BP_READ(1) / BP_WRITE(2) / BP_READ_WRITE(3) / BP_EXEC(4)", "Type: gg.BP_READ(1), BP_WRITE(2), BP_READ_WRITE(3), BP_EXEC(4)"),
            ApiParamSpec("size", "integer?", "监控字节宽度：1 / 2 / 4 / 8（默认 4）", "Watch size in bytes: 1, 2, 4, or 8 (default 4)"),
            ApiParamSpec("mode", "integer?", "后端：gg.BP_MODE_PERF(0) / gg.BP_MODE_PTRACE(1)", "Backend: gg.BP_MODE_PERF(0) or gg.BP_MODE_PTRACE(1)"),
            ApiParamSpec("callstack", "boolean?", "是否采集调用栈回溯（默认 true）", "Capture callstack backtrace (default true)")
        ),
        returnsZh = "成功返回 bpId (integer)；失败返回 nil, errMsg (string)。",
        returnsEn = "Returns bpId (integer) on success; nil, errMsg (string) on failure.",
        exampleLua = """local bpId, err = gg.addBreakpoint(0x7A12340000, gg.BP_WRITE, 4, gg.BP_MODE_PERF, true)
assert(bpId, err)
gg.sleep(2000)
local trace = gg.getBreakpointTrace(bpId, 0, 10)
for _, ev in ipairs(trace) do
    print("PC=" .. ev.pcHex .. " X0=" .. (ev.regs.X0 or "?"))
end
gg.removeBreakpoint(bpId)"""
    ),
    ApiDocEntry(
        id = "bp_manage",
        category = DocCategory.BREAKPOINT,
        name = "gg.getBreakpoints / setBreakpointPaused / removeBreakpoint / clearBreakpointHits",
        signature = "gg.getBreakpoints() | gg.setBreakpointPaused(id, paused) | gg.removeBreakpoint(id) | gg.clearBreakpointHits([id])",
        summaryZh = "查询全部硬件断点及按 PC 聚合的命中指令列表、暂停或恢复指定断点、清空命中统计或卸载断点。",
        summaryEn = "List all hardware breakpoints with PC-aggregated hits, pause/resume breakpoints, clear hit counters, or remove breakpoints.",
        params = listOf(
            ApiParamSpec("bpId", "integer", "硬件断点 ID（clearBreakpointHits 传 0 清空全部）", "Breakpoint ID (pass 0 to clearBreakpointHits for all)"),
            ApiParamSpec("paused", "boolean", "true 暂停监听，false 恢复监听", "true to pause watching, false to resume")
        ),
        returnsZh = "getBreakpoints() 返回断点详情数组；其余接口返回 boolean。",
        returnsEn = "getBreakpoints() returns breakpoint array; others return boolean.",
        exampleLua = """local list = gg.getBreakpoints()
for _, bp in ipairs(list) do
    print("BP#" .. bp.id, bp.addressHex, "hits=" .. bp.totalHits)
end
gg.setBreakpointPaused(bpId, true)
gg.clearBreakpointHits(bpId)"""
    ),
    ApiDocEntry(
        id = "bp_trace_reg",
        category = DocCategory.BREAKPOINT,
        name = "gg.getBreakpointTrace / clearBreakpointTrace / setBreakpointRegister",
        signature = "gg.getBreakpointTrace([bpId, sinceSeq, limit]) | gg.setBreakpointRegister(bpId, tid, regName, val [, isFp])",
        summaryZh = "增量拉取实时寄存器快照流（类似 Frida 实时打印），并支持在 Ptrace 断点命中暂停时直接修改指定线程的寄存器值并写回。",
        summaryEn = "Stream incremental live register snapshots and modify/write back target thread registers when paused in Ptrace mode.",
        params = listOf(
            ApiParamSpec("bpId", "integer?", "断点 ID（0 表示全部断点）", "Breakpoint ID (0 for all breakpoints)"),
            ApiParamSpec("tid", "integer", "目标线程 TID", "Target thread TID"),
            ApiParamSpec("regName", "string", "寄存器名（如 \"X0\"、\"SP\"、\"S0\"、\"D0\"）", "Register name (e.g. \"X0\", \"SP\", \"S0\", \"D0\")"),
            ApiParamSpec("value", "integer|number|string", "要写回的新寄存器值", "New value to write back to the register")
        ),
        returnsZh = "getBreakpointTrace 返回事件数组；setBreakpointRegister 返回 boolean。",
        returnsEn = "getBreakpointTrace returns event array; setBreakpointRegister returns boolean.",
        exampleLua = """local events = gg.getBreakpointTrace(bpId, 0, 50)
if #events > 0 then
    local last = events[#events]
    gg.setBreakpointRegister(bpId, last.tid, "X0", 9999, false)
end"""
    ),

    // ==================== ELF / .so Analysis ====================
    ApiDocEntry(
        id = "elf_module_base",
        category = DocCategory.ELF,
        name = "gg.getModuleBase",
        signature = "gg.getModuleBase(moduleName)",
        summaryZh = "实时解析目标进程 /proc/<pid>/maps，返回指定 .so 或可执行模块的加载基址（优先返回 ELF 头所在最低映射基址）。",
        summaryEn = "Resolve the runtime load base address of a .so or executable module from target /proc/<pid>/maps.",
        params = listOf(
            ApiParamSpec("moduleName", "string", "模块名或路径子串（如 \"libil2cpp.so\"）", "Module name or path substring (e.g. \"libil2cpp.so\")")
        ),
        returnsZh = "返回模块基址 (integer, uint64)；未加载则返回 0。",
        returnsEn = "Returns module base address (uint64 integer), or 0 if not loaded.",
        exampleLua = """local base = gg.getModuleBase("libil2cpp.so")
if base ~= 0 then
    print(string.format("libil2cpp.so base = 0x%X", base))
end"""
    ),
    ApiDocEntry(
        id = "elf_exports",
        category = DocCategory.ELF,
        name = "gg.getModuleExports",
        signature = "gg.getModuleExports(moduleName [, filter [, limit]])",
        summaryZh = "原生 C++20 解析目标进程中指定 .so 的 ELF64/ELF32 动态符号表（.dynsym / .symtab），返回导出符号名称、偏移与运行时绝对地址。",
        summaryEn = "Natively parse ELF64/ELF32 symbol tables (.dynsym / .symtab) of a target .so and return symbol names, offsets, and runtime addresses.",
        params = listOf(
            ApiParamSpec("moduleName", "string", "目标 .so 模块名", "Target .so module name"),
            ApiParamSpec("filter", "string?", "可选名称关键词过滤（忽略大小写）", "Optional case-insensitive name filter"),
            ApiParamSpec("limit", "integer?", "最大返回条数（默认 128，最大 1024）", "Max symbols returned (default 128, max 1024)")
        ),
        returnsZh = "返回表 { base, baseHex, path, count, symbols = { { name, offset, address, size, type }... } }。",
        returnsEn = "Returns table { base, baseHex, path, count, symbols = { { name, offset, address, size, type }... } }.",
        exampleLua = """local info = gg.getModuleExports("libil2cpp.so", "il2cpp_class_", 30)
print("Module:", info.path, "base:", info.baseHex)
for _, sym in ipairs(info.symbols or {}) do
    print(sym.name, string.format("0x%X", sym.address))
end"""
    ),
    ApiDocEntry(
        id = "elf_find_symbol",
        category = DocCategory.ELF,
        name = "gg.findSymbol",
        signature = "gg.findSymbol(moduleName, symbolName)",
        summaryZh = "精确查找目标 .so 内指定导出符号的运行时绝对内存地址，可直接用于 gg.hookAddress、gg.callFunction 或 gg.addBreakpoint。",
        summaryEn = "Resolve the exact runtime address of an exported symbol inside a target .so for direct use with hooks, active calls, or breakpoints.",
        params = listOf(
            ApiParamSpec("moduleName", "string", "目标 .so 名称", "Target .so name"),
            ApiParamSpec("symbolName", "string", "精确符号名", "Exact symbol name")
        ),
        returnsZh = "找到返回运行时地址 (integer, uint64)；未找到返回 0。",
        returnsEn = "Returns runtime address (uint64 integer), or 0 if not found.",
        exampleLua = """local addr = gg.findSymbol("libc.so", "gettimeofday")
print(string.format("gettimeofday = 0x%X", addr))"""
    ),

    // ==================== Declarative UI & Plugins ====================
    ApiDocEntry(
        id = "ui_declarative",
        category = DocCategory.UI_PLUGIN,
        name = "ui.show / ui.update / ui.pollEvent / ui.close",
        signature = "ui.show(spec) | ui.update(nodeId, patch) | ui.pollEvent([timeoutMs]) | ui.close()",
        summaryZh = "GGNext 声明式脚本界面协议，自动复用当前全局 Rose 主题与深浅色样式，支持文本、按钮、开关、滑块、输入框与局部热更新。",
        summaryEn = "Declarative Compose UI protocol automatically styled with the active GGNext theme. Supports text, buttons, switches, sliders, inputs, and live patching.",
        params = listOf(
            ApiParamSpec("spec", "table", "{ title, subtitle, dismissible, root = { type, children... } }", "{ title, subtitle, dismissible, root = { type, children... } }"),
            ApiParamSpec("nodeId", "string", "需要局部更新的组件 id", "Component id for partial live update"),
            ApiParamSpec("timeoutMs", "integer?", "事件轮询超时毫秒数（默认 100）", "Event poll timeout in ms (default 100)")
        ),
        returnsZh = "ui.pollEvent() 返回 { type=\"event\"|\"closed\", callbackId, payload } 或 nil。",
        returnsEn = "ui.pollEvent() returns { type=\"event\"|\"closed\", callbackId, payload } or nil.",
        exampleLua = """ui.show({
    title = "GGNext 演示面板",
    root = {
        type = "column",
        children = {
            { id = "status", type = "text", text = "等待点击" },
            { type = "button", text = "触发", onClick = "btn_go" }
        }
    }
})
while true do
    local ev = ui.pollEvent(100)
    if ev and ev.type == "closed" then break end
    if ev and ev.callbackId == "btn_go" then
        ui.update("status", { text = "已触发!" })
    end
end"""
    ),
    ApiDocEntry(
        id = "plugin_bridge",
        category = DocCategory.UI_PLUGIN,
        name = "plugin.list / plugin.invoke",
        signature = "plugin.list() | plugin.invoke(pluginId, action [, payload])",
        summaryZh = "枚举已安装的 Lua 扩展插件并调用插件暴露的动作接口，实现脚本与插件能力互通。",
        summaryEn = "Enumerate installed Lua plugins and invoke exposed plugin actions for modular script composition.",
        params = listOf(
            ApiParamSpec("pluginId", "string", "插件唯一 ID", "Plugin unique identifier"),
            ApiParamSpec("action", "string", "目标插件导出的动作名", "Exported action name"),
            ApiParamSpec("payload", "table?", "可选入参表", "Optional payload table")
        ),
        returnsZh = "plugin.list() 返回插件列表；plugin.invoke() 返回调用结果表。",
        returnsEn = "plugin.list() returns plugin array; plugin.invoke() returns invocation result table.",
        exampleLua = """local plugins = plugin.list()
for _, p in ipairs(plugins) do
    print(p.id, p.name, p.version)
end"""
    ),

    // ==================== Constants & Compat ====================
    ApiDocEntry(
        id = "compat_constants",
        category = DocCategory.CONSTANTS,
        name = "GGNext 独有常量与原版 GG 兼容增强",
        signature = "gg.BP_* | gg.HOOK_MODE_* | gg.getResultCount()",
        summaryZh = "所有 GGNext 独有 API 均挂载在 gg.* 命名空间下；同时深度兼容原版 GG 脚本（支持 Lua 5.5.1 循环变量可改写、浮点子类型保留 15.0、64 位无符号地址无损比较及 gg.getResultCount 旧版别名）。",
        summaryEn = "All GGNext-exclusive APIs live under gg.*. Also includes deep legacy GG script compatibility: mutable for-loop variables, float subtype preservation (15.0), lossless uint64 address comparison, and gg.getResultCount() alias.",
        params = listOf(
            ApiParamSpec("gg.BP_READ / WRITE / READ_WRITE / EXEC", "1 / 2 / 3 / 4", "硬件断点监控类型常量", "Hardware breakpoint watch type constants"),
            ApiParamSpec("gg.BP_MODE_PERF / BP_MODE_PTRACE", "0 / 1", "硬件断点调试后端常量", "Hardware breakpoint backend constants"),
            ApiParamSpec("gg.HOOK_MODE_INLINE / INTERCEPT", "0 / 1", "ShadowHook 挂载模式常量", "ShadowHook attachment mode constants")
        ),
        returnsZh = "内置于全局 gg 表，脚本启动时自动就绪。",
        returnsEn = "Pre-populated in the global gg table at script startup.",
        exampleLua = """-- 原版别名与浮点精度兼容示例
gg.searchNumber("15.0", gg.TYPE_FLOAT)
local cnt = gg.getResultCount() -- 兼容旧版拼写别名
local list = gg.getResults(cnt)
print("Count =", cnt, "First =", list[1] and list[1].value)"""
    )
)

@Composable
fun HomeDocsTab() {
    val isEn = I18n.currentLang == AppStrings.LANG_EN
    val clipboard = LocalClipboardManager.current

    var selectedCat by remember { mutableStateOf(DocCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    val expandedMap = remember { mutableStateMapOf<String, Boolean>("hook_symbol" to true) }

    val filteredDocs = remember(selectedCat, searchQuery, isEn) {
        val q = searchQuery.trim()
        GGNEXT_API_DOCS.filter { item ->
            val catOk = selectedCat == DocCategory.ALL || item.category == selectedCat
            val qOk = q.isEmpty() ||
                item.name.contains(q, ignoreCase = true) ||
                item.signature.contains(q, ignoreCase = true) ||
                item.summaryZh.contains(q, ignoreCase = true) ||
                item.summaryEn.contains(q, ignoreCase = true) ||
                item.exampleLua.contains(q, ignoreCase = true)
            catOk && qOk
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 顶部标题与数量徽章
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr("API 文档"),
                color = Rose.Text,
                fontSize = fsp(24f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            GgBadge(
                text = "gg.* (${filteredDocs.size})",
                textColor = Rose.Primary,
                backgroundColor = Rose.Primary.copy(alpha = 0.14f),
                shape = GgShapes.Pill,
                fontSize = 11.5f,
                monospace = true,
                fontWeight = FontWeight.SemiBold,
                horizontalPadding = GgSpacing.Md,
                verticalPadding = 3.dp,
                translate = false
            )
        }

        Text(
            text = tr("GGNext 独有 Lua 扩展 API 手册（统一挂载于 gg.* 命名空间）"),
            color = Rose.TextSecond,
            fontSize = fsp(12f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Spacer(Modifier.height(GgSpacing.Md))

        // 关键词搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(GgShapes.Lg)
                .background(Rose.CardHigh)
                .border(GgBorder.Thin, Rose.Divider, GgShapes.Lg)
                .padding(horizontal = GgSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size(18.dp)
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = tr("搜索 API 名称、关键词或示例代码…"),
                        color = Rose.TextDim,
                        fontSize = fsp(12.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = monoStyle(12, Rose.Text),
                    cursorBrush = SolidColor(Rose.Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (searchQuery.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = tr("清空"),
                    tint = Rose.TextDim,
                    modifier = Modifier
                        .size(17.dp)
                        .clickable { searchQuery = "" }
                )
            }
        }

        Spacer(Modifier.height(GgSpacing.Sm))

        // 分类筛选行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DocCategory.entries.forEach { cat ->
                val selected = selectedCat == cat
                Box(
                    modifier = Modifier
                        .clip(GgShapes.Md)
                        .background(if (selected) Rose.ChipSelected else Rose.CardHigh)
                        .border(
                            width = if (selected) GgBorder.Default else GgBorder.Hairline,
                            color = if (selected) Rose.Primary else Rose.Divider,
                            shape = GgShapes.Md
                        )
                        .clickable { selectedCat = cat }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isEn) cat.enLabel else cat.zhLabel,
                        color = if (selected) Rose.Primary else Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(GgSpacing.Md))

        if (filteredDocs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.MenuBook,
                        contentDescription = null,
                        tint = Rose.TextDim,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(GgSpacing.Sm))
                    Text(
                        text = tr("未找到匹配的 API 文档"),
                        color = Rose.TextSecond,
                        fontSize = fsp(13f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)
            ) {
                items(
                    items = filteredDocs,
                    key = { it.id }
                ) { doc ->
                    val expanded = expandedMap[doc.id] ?: false
                    ApiDocCard(
                        doc = doc,
                        isEn = isEn,
                        expanded = expanded,
                        onToggle = { expandedMap[doc.id] = !expanded },
                        onCopyExample = {
                            clipboard.setText(AnnotatedString(doc.exampleLua))
                            Session.toast("已复制 ${doc.name} 示例")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiDocCard(
    doc: ApiDocEntry,
    isEn: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopyExample: () -> Unit
) {
    val catColor = when (doc.category) {
        DocCategory.HOOK -> Rose.Primary
        DocCategory.BREAKPOINT -> Rose.Green
        DocCategory.ELF -> Rose.Blue
        DocCategory.UI_PLUGIN -> Rose.Yellow
        else -> Rose.TextSecond
    }

    GgSurfaceCard(
        shape = GgShapes.Xl,
        borderWidth = GgBorder.Thin,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 卡片头部（点击展开/折叠）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = doc.name,
                            style = monoStyle(13, Rose.Text, FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        GgBadge(
                            text = if (isEn) doc.category.enLabel else doc.category.zhLabel,
                            textColor = catColor,
                            backgroundColor = catColor.copy(alpha = 0.14f),
                            shape = GgShapes.Sm,
                            fontSize = 9.5f,
                            horizontalPadding = 6.dp,
                            verticalPadding = 2.dp,
                            translate = false
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isEn) doc.summaryEn else doc.summaryZh,
                        color = Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        lineHeight = fsp(16.5f),
                        maxLines = if (expanded) 10 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Rose.TextDim,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = expandExit()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GgDivider()

                    // 函数签名
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = tr("函数签名"),
                            color = Rose.TextDim,
                            fontSize = fsp(10.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GgShapes.Sm)
                                .background(Rose.Surface)
                                .border(GgBorder.Hairline, Rose.Divider, GgShapes.Sm)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = doc.signature,
                                style = monoStyle(11, Rose.Primary, FontWeight.Medium)
                            )
                        }
                    }

                    // 参数说明
                    if (doc.params.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = tr("参数说明"),
                                color = Rose.TextDim,
                                fontSize = fsp(10.5f),
                                fontWeight = FontWeight.SemiBold
                            )
                            doc.params.forEach { p ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = p.name,
                                        style = monoStyle(10, Rose.Text, FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = p.type,
                                        style = monoStyle(10, Rose.Blue)
                                    )
                                    Text(
                                        text = if (isEn) p.descEn else p.descZh,
                                        color = Rose.TextSecond,
                                        fontSize = fsp(11f),
                                        lineHeight = fsp(15.5f),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // 返回值
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = tr("返回值"),
                            color = Rose.TextDim,
                            fontSize = fsp(10.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isEn) doc.returnsEn else doc.returnsZh,
                            color = Rose.TextSecond,
                            fontSize = fsp(11f),
                            lineHeight = fsp(16f)
                        )
                    }

                    // Lua 调用示例 + 一键复制
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr("Lua 调用示例"),
                                color = Rose.TextDim,
                                fontSize = fsp(10.5f),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                modifier = Modifier
                                    .clip(GgShapes.Sm)
                                    .background(Rose.Primary.copy(alpha = 0.14f))
                                    .clickable { onCopyExample() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = tr("复制示例"),
                                    tint = Rose.Primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = tr("复制示例"),
                                    color = Rose.Primary,
                                    fontSize = fsp(10.5f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GgShapes.Sm)
                                .background(Rose.Surface)
                                .border(GgBorder.Hairline, Rose.Divider, GgShapes.Sm)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = doc.exampleLua,
                                style = monoStyle(10, Rose.Text)
                            )
                        }
                    }
                }
            }
        }
    }
}
