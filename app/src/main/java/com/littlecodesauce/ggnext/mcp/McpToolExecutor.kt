package com.littlecodesauce.ggnext.mcp

import android.content.Context
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class McpToolCallResult(
    val text: String,
    val isError: Boolean = false
)

data class McpToolParamSpec(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String
)

data class McpToolSummary(
    val name: String,
    val category: String,
    val description: String,
    val params: List<McpToolParamSpec> = emptyList()
)

private data class PatchRecord(
    val id: Int,
    val address: Long,
    val originalBytes: ByteArray,
    val patchedBytes: ByteArray,
    val note: String,
    val timestamp: String
)

/**
 * GGNext 本地 MCP 工具注册表与执行器（25 项真实工具）：
 * - 全部工具描述与参数提示词均使用规范英文，面向大模型工具调用优化；
 * - 所有工具均直接打通 Session / MemoryEngine / AsmEngineManager / ProcessScanner / Shell 真实链路。
 */
object McpToolExecutor {

    private val engineLock = Any()
    private val patchHistory = ArrayList<PatchRecord>()
    private var nextPatchId = 1

    private val cachedDefinitions: JSONArray by lazy { buildToolDefinitions() }

    val toolSummaries: List<McpToolSummary> by lazy {
        val defs = cachedDefinitions
        val categoryMap = mapOf(
            "get_status" to "System",
            "list_processes" to "Process",
            "attach_process" to "Process",
            "process_control" to "Process",
            "list_regions" to "Memory",
            "set_search_range" to "Memory",
            "read_memory" to "Memory",
            "write_memory" to "Memory",
            "view_memory" to "Memory",
            "allocate_memory_page" to "Memory",
            "copy_memory" to "Memory",
            "dump_memory" to "Memory",
            "patch_memory" to "Edit",
            "freeze_value" to "Edit",
            "search_memory" to "Search",
            "fuzzy_search" to "Search",
            "pointer_search" to "Search",
            "get_search_results" to "List",
            "manage_search_results" to "List",
            "clear_search" to "List",
            "manage_saved_results" to "List",
            "assemble" to "ASM",
            "disassemble" to "ASM",
            "manage_breakpoints" to "Debug",
            "bp_modify_register" to "Debug",
            "get_breakpoint_trace" to "Debug",
            "inspect_so_module" to "ELF",
            "hook_install" to "Hook",
            "hook_uninstall" to "Hook",
            "hook_list" to "Hook",
            "hook_get_logs" to "Hook",
            "hook_call_function" to "Hook"
        )
        List(defs.length()) { i ->
            val obj = defs.getJSONObject(i)
            val name = obj.getString("name")
            val desc = obj.getString("description")
            val schema = obj.optJSONObject("inputSchema") ?: JSONObject()
            val props = schema.optJSONObject("properties") ?: JSONObject()
            val reqArr = schema.optJSONArray("required")
            val reqSet = HashSet<String>()
            if (reqArr != null) {
                for (r in 0 until reqArr.length()) reqSet.add(reqArr.optString(r))
            }
            val paramList = ArrayList<McpToolParamSpec>()
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val pObj = props.optJSONObject(k) ?: continue
                paramList.add(
                    McpToolParamSpec(
                        name = k,
                        type = pObj.optString("type", "string"),
                        required = k in reqSet,
                        description = pObj.optString("description", "")
                    )
                )
            }
            McpToolSummary(
                name = name,
                category = categoryMap[name] ?: "General",
                description = desc,
                params = paramList
            )
        }
    }

    fun toolDefinitions(): JSONArray = cachedDefinitions

    private fun buildToolDefinitions(): JSONArray = JSONArray().apply {
        put(toolDef(
            name = "get_status",
            desc = "Get current GGNext runtime status including work mode (root/no_root), attached target process, architecture, search result count, saved list count, breakpoints count, selected regions, and assembler/disassembler status.",
            props = JSONObject(),
            required = emptyList()
        ))
        put(toolDef(
            name = "list_processes",
            desc = "List running Android processes visible under the current work mode.",
            props = JSONObject()
                .put("query", prop("string", "Optional case-insensitive filter matching process name, package name, or PID."))
                .put("limit", prop("integer", "Max processes to return (default 50, max 200).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "attach_process",
            desc = "Attach GGNext memory engine to a target process by PID or package name.",
            props = JSONObject()
                .put("pid", prop("integer", "Target process PID."))
                .put("package_name", prop("string", "Target package name or process label (used when pid is omitted).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "process_control",
            desc = "Pause (SIGSTOP), resume (SIGCONT), or terminate (SIGKILL) the currently attached target process.",
            props = JSONObject()
                .put("action", prop("string", "Control action: pause, resume, or kill.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "list_regions",
            desc = "List memory regions (/proc/<pid>/maps) of the attached process and aggregated module base addresses.",
            props = JSONObject()
                .put("region_codes", prop("string", "Optional comma-separated region codes to filter, e.g. 'Ca,A,Xa,Cd'."))
                .put("module_filter", prop("string", "Optional substring filter on module/pathname, e.g. 'libil2cpp.so'."))
                .put("offset", prop("integer", "Pagination offset (default 0)."))
                .put("limit", prop("integer", "Max regions to return (default 100, max 500).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "set_search_range",
            desc = "Update active memory region selection used by subsequent searches.",
            props = JSONObject()
                .put("regions", prop("string", "Comma-separated region codes (e.g. 'Ca,A,Cd,Cb,Xa'), or 'default', or 'all'.")),
            required = listOf("regions")
        ))
        put(toolDef(
            name = "read_memory",
            desc = "Read raw hex bytes and decoded typed values at an address in the attached process.",
            props = JSONObject()
                .put("address", prop("string", "Target address in hex (e.g. '0x7a12345678') or decimal."))
                .put("size", prop("integer", "Number of raw bytes to read (default 16, max 4096)."))
                .put("type", prop("string", "Optional typed decode: byte, word, dword, qword, float, double, utf8, utf16.")),
            required = listOf("address")
        ))
        put(toolDef(
            name = "write_memory",
            desc = "Write raw hex bytes or a typed value directly to an address in the attached process.",
            props = JSONObject()
                .put("address", prop("string", "Target address in hex or decimal."))
                .put("hex_bytes", prop("string", "Hex byte string to write (e.g. '1F 20 03 D5'). Takes precedence over value/type."))
                .put("value", prop("string", "Numeric value string to write when hex_bytes is omitted (e.g. '999', '3.14', '0x10')."))
                .put("type", prop("string", "Value type for numeric write: byte, word, dword, qword, float, double, xor (default dword).")),
            required = listOf("address")
        ))
        put(toolDef(
            name = "patch_memory",
            desc = "Apply a reversible memory patch (auto-saving original bytes before writing), list recorded patches, or roll back a previous patch.",
            props = JSONObject()
                .put("action", prop("string", "Action: apply, rollback, or list."))
                .put("address", prop("string", "Target address in hex (required for apply)."))
                .put("hex_bytes", prop("string", "Replacement hex bytes (required for apply)."))
                .put("note", prop("string", "Optional label describing this patch."))
                .put("patch_id", prop("integer", "Patch ID to rollback (defaults to most recent patch if omitted).")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "view_memory",
            desc = "Inspect a structured memory window around an address (4-byte aligned rows with hex, dword, float, pointer tag, ascii, and optional disassembly).",
            props = JSONObject()
                .put("address", prop("string", "Start address in hex or decimal."))
                .put("bytes", prop("integer", "Window size in bytes (default 128, min 4, max 1024)."))
                .put("disasm_arch", prop("string", "Optional disassembly arch: arm64, arm, thumb, x86, x86_64.")),
            required = listOf("address")
        ))
        put(toolDef(
            name = "search_memory",
            desc = "Search or refine memory in the attached process. Supports exact values, ranges (A~B), group search (A;B:512), XOR (AXK), hex (1234h), and text.",
            props = JSONObject()
                .put("expression", prop("string", "Search query expression, e.g. '100', '10~20', '100;200:512', '50X4'."))
                .put("type", prop("string", "Value type: auto, byte, word, dword, qword, float, double, xor (default dword)."))
                .put("refine", prop("boolean", "If true, refine existing search results; if false, start a new search (default false)."))
                .put("sign", prop("string", "Comparison operator: eq, neq, gt, gte, lt, lte (default eq)."))
                .put("regions", prop("string", "Optional comma-separated region codes (e.g. 'Ca,A,Cd,Cb'). Defaults to active selection."))
                .put("memory_from", prop("string", "Optional lower address bound (hex)."))
                .put("memory_to", prop("string", "Optional upper address bound (hex)."))
                .put("encrypted_xor", prop("boolean", "Enable encrypted value search mode (default false)."))
                .put("preview_limit", prop("integer", "Max sample results returned inline (default 50, max 200).")),
            required = listOf("expression")
        ))
        put(toolDef(
            name = "fuzzy_search",
            desc = "Perform unknown initial memory snapshot or fuzzy change refinement (increased, decreased, changed, unchanged, delta).",
            props = JSONObject()
                .put("action", prop("string", "Action: init, increased, decreased, changed, unchanged, eq, neq, gt, lt, delta."))
                .put("type", prop("string", "Value type: byte, word, dword, qword, float, double (default dword)."))
                .put("delta", prop("string", "Optional delta value when action is 'delta'."))
                .put("regions", prop("string", "Optional comma-separated region codes for init."))
                .put("preview_limit", prop("integer", "Max sample results returned inline (default 50, max 200).")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "pointer_search",
            desc = "Perform a single-level (depth 1) pointer search or refinement finding addresses whose pointer value falls within [target_address - max_offset, target_address].",
            props = JSONObject()
                .put("target_address", prop("string", "Target address in hex to find pointers referencing it."))
                .put("max_offset", prop("integer", "Maximum positive byte offset from pointer to target_address (default 512)."))
                .put("pointer_type", prop("string", "Pointer width: auto, qword (64-bit), or dword (32-bit). Defaults to auto."))
                .put("refine", prop("boolean", "If true, refine existing search results instead of scanning all regions (default false)."))
                .put("regions", prop("string", "Optional comma-separated region codes to scan."))
                .put("preview_limit", prop("integer", "Max sample results returned inline (default 50, max 200).")),
            required = listOf("target_address")
        ))
        put(toolDef(
            name = "get_search_results",
            desc = "Fetch a paginated slice of current search results with live refreshed memory values.",
            props = JSONObject()
                .put("offset", prop("integer", "Start index (default 0)."))
                .put("limit", prop("integer", "Number of items (default 50, max 200).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "manage_search_results",
            desc = "Manage the current search result list: remove specific addresses, save top/sliced results to the saved list, or clear all results.",
            props = JSONObject()
                .put("action", prop("string", "Action: save_to_list, remove_addresses, or clear."))
                .put("addresses", prop("string", "Comma or semicolon-separated hex addresses (for remove_addresses)."))
                .put("offset", prop("integer", "Start offset in search results (for save_to_list, default 0)."))
                .put("count", prop("integer", "Number of search results to save (for save_to_list, default 20, max 200)."))
                .put("note", prop("string", "Optional note/label applied to saved entries.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "clear_search",
            desc = "Clear current search results and release native candidate buffers.",
            props = JSONObject(),
            required = emptyList()
        ))
        put(toolDef(
            name = "manage_saved_results",
            desc = "Read and manage the saved address list: list entries (with live values), add an address, remove addresses, update note, or clear all.",
            props = JSONObject()
                .put("action", prop("string", "Action: list, add, remove, update_note, or clear."))
                .put("address", prop("string", "Target address in hex (for add or update_note)."))
                .put("addresses", prop("string", "Comma or semicolon-separated hex addresses (for remove)."))
                .put("type", prop("string", "Value type for add: byte, word, dword, qword, float, double, xor (default dword)."))
                .put("note", prop("string", "Note/description text (for add or update_note).")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "assemble",
            desc = "Assemble instructions into machine code bytes (arm64, arm, thumb, x86, x86_64) and optionally write them directly to target process memory.",
            props = JSONObject()
                .put("code", prop("string", "Assembly instruction(s), e.g. 'MOV X0, #1; RET' or 'NOP'."))
                .put("arch", prop("string", "Architecture: arm64, arm, thumb, x86, x86_64 (defaults to target arch)."))
                .put("address", prop("string", "Base address for PC-relative encoding and optional memory write (default '0x0')."))
                .put("write_to_address", prop("boolean", "If true, write assembled bytes directly to the specified address in target process (default false).")),
            required = listOf("code")
        ))
        put(toolDef(
            name = "disassemble",
            desc = "Disassemble either raw hex machine code or live bytes read from an address in the attached process.",
            props = JSONObject()
                .put("address", prop("string", "Base address in hex (required when reading target memory, optional for hex_bytes)."))
                .put("hex_bytes", prop("string", "Optional raw hex bytes to disassemble instead of reading target memory."))
                .put("bytes", prop("integer", "Bytes to read from target process if hex_bytes is omitted (default 64, max 1024)."))
                .put("arch", prop("string", "Architecture: arm64, arm, thumb, x86, x86_64 (defaults to target arch)."))
                .put("max_instructions", prop("integer", "Max instructions to decode (default 32, max 256).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "allocate_memory_page",
            desc = "Allocate a new memory page inside the target process via cross-process SYS_mmap with custom RWX permissions.",
            props = JSONObject()
                .put("hint_address", prop("string", "Optional preferred hint address in hex (default '0x0' for kernel choice)."))
                .put("size", prop("integer", "Allocation size in bytes (default 4096, page-aligned)."))
                .put("read", prop("boolean", "PROT_READ permission (default true)."))
                .put("write", prop("boolean", "PROT_WRITE permission (default true)."))
                .put("exec", prop("boolean", "PROT_EXEC permission (default false).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "copy_memory",
            desc = "Copy a block of memory from one address to another within the attached process.",
            props = JSONObject()
                .put("from_address", prop("string", "Source address in hex."))
                .put("to_address", prop("string", "Destination address in hex."))
                .put("size", prop("integer", "Number of bytes to copy (1..16777216).")),
            required = listOf("from_address", "to_address", "size")
        ))
        put(toolDef(
            name = "dump_memory",
            desc = "Dump a memory range [start_address, end_address) of the attached process to a file on storage.",
            props = JSONObject()
                .put("start_address", prop("string", "Start address in hex."))
                .put("end_address", prop("string", "End address in hex (exclusive)."))
                .put("output_path", prop("string", "Output file path (defaults to app cache dir dump file).")),
            required = listOf("start_address", "end_address")
        ))
        put(toolDef(
            name = "freeze_value",
            desc = "Lock (freeze) or unlock an address value using the native locker.",
            props = JSONObject()
                .put("action", prop("string", "Action: lock, unlock, unlock_all."))
                .put("address", prop("string", "Target address in hex (required for lock/unlock)."))
                .put("value", prop("string", "Value to freeze (required for lock)."))
                .put("type", prop("string", "Value type: byte, word, dword, qword, float, double (default dword)."))
                .put("freeze_mode", prop("string", "Mode: exact, increment, decrement, range (default exact)."))
                .put("range_from", prop("string", "Min value for range clamp mode."))
                .put("range_to", prop("string", "Max value for range clamp mode.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "manage_breakpoints",
            desc = "Manage hardware breakpoints/watchpoints (Perf or Ptrace backend): add, list, pause, resume, clear_hits, remove, stop_all.",
            props = JSONObject()
                .put("action", prop("string", "Action: add, list, pause, resume, clear_hits, remove, stop_all."))
                .put("address", prop("string", "Breakpoint address in hex (required for add)."))
                .put("bp_type", prop("string", "Type for add: read, write, rw, exec (default rw)."))
                .put("size", prop("integer", "Watchpoint byte length for add: 1, 2, 4, 8 (default 4)."))
                .put("mode", prop("string", "Backend mode for add: perf or ptrace (default from settings)."))
                .put("capture_callstack", prop("boolean", "Capture callstack on hit (default true)."))
                .put("bp_id", prop("integer", "Breakpoint ID for pause, resume, clear_hits, remove.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "bp_modify_register",
            desc = "Modify a general-purpose or floating-point register value on a thread hit by an active hardware breakpoint.",
            props = JSONObject()
                .put("bp_id", prop("integer", "Active breakpoint session ID."))
                .put("tid", prop("integer", "Target thread ID (tid) from breakpoint hit."))
                .put("hit_pc", prop("string", "Hit PC address in hex for snapshot cache sync."))
                .put("reg_name", prop("string", "Register name, e.g. 'x0', 'pc', 'v0', 'rax'."))
                .put("hex_value", prop("string", "New register value in hex (without or with 0x prefix)."))
                .put("is_fp", prop("boolean", "True if modifying a floating-point/SIMD register (default false).")),
            required = listOf("bp_id", "tid", "reg_name", "hex_value")
        ))
        put(toolDef(
            name = "get_breakpoint_trace",
            desc = "Read real-time chronological hardware breakpoint register trace events (Frida-style live stream with per-PC changed register diff).",
            props = JSONObject()
                .put("bp_id", prop("integer", "Filter by breakpoint ID (0 or omit for all breakpoints)."))
                .put("since_seq", prop("integer", "Incremental sequence cursor; only return events with seq > since_seq (default 0)."))
                .put("limit", prop("integer", "Max trace events to return (default 50, max 200)."))
                .put("reg_filter", prop("string", "Optional comma-separated register names to include, e.g. 'X0,X1,X8,X9,LR,SP'."))
                .put("include_fpr", prop("boolean", "Include floating-point/SIMD registers (default false).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "inspect_so_module",
            desc = "Parse an ELF shared library (.so) loaded in the attached process: runtime VMA base/segments, ELF header, sections, dynamic exports/imports with live virtual addresses, JNI Java_* exports, or .rodata strings.",
            props = JSONObject()
                .put("module_name", prop("string", "Target .so name or substring, e.g. 'libil2cpp.so' or 'libtutorial.so'."))
                .put("action", prop("string", "Inspection mode: summary (default), sections, exports, imports, jni, or strings."))
                .put("filter", prop("string", "Optional case-insensitive substring filter for symbol, section, or string name."))
                .put("limit", prop("integer", "Max items to return (default 100, max 500).")),
            required = listOf("module_name")
        ))
        put(toolDef(
            name = "hook_install",
            desc = "Install or update a ShadowHook inline function hook or instruction-level register intercept in the target process (by library+symbol name or raw virtual address). Supports pre-call argument/register overrides, bypassing the original function, return-value replacement, and hit logging.",
            props = JSONObject()
                .put("hook_id", prop("integer", "Optional existing hook ID to update rules in-place instead of installing a new hook."))
                .put("lib_name", prop("string", "Target shared library name, e.g. 'libc.so' or 'libil2cpp.so' (used with symbol_name)."))
                .put("symbol_name", prop("string", "Exported or debug symbol name to hook (used with lib_name)."))
                .put("address", prop("string", "Raw virtual address in hex (e.g. '0x7123456000') when hooking/intercepting by address."))
                .put("mode", prop("string", "Hook mode: 'hook' (default, function inline hook with orig trampoline) or 'intercept' (instruction-level register intercept)."))
                .put("arg_overrides", prop("object", "Optional map of argument index ('0'..'7') to replacement integer/hex value, or null to clear."))
                .put("reg_overrides", prop("object", "Optional map of register name (e.g. 'x0', 'x1', 'd0', 'r0') to replacement value for intercept mode."))
                .put("bypass_original", prop("boolean", "If true, skip calling the original function and return return_override (or 0)."))
                .put("return_override", prop("string", "Optional replacement return value (integer or 0x hex; null to clear)."))
                .put("log_enabled", prop("boolean", "Record hook hits into the ring buffer (default true).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "hook_uninstall",
            desc = "Uninstall an active ShadowHook hook/intercept by hook_id, or uninstall all active hooks when all=true.",
            props = JSONObject()
                .put("hook_id", prop("integer", "Active hook ID to uninstall."))
                .put("all", prop("boolean", "If true, uninstall all active hooks in the process.")),
            required = emptyList()
        ))
        put(toolDef(
            name = "hook_list",
            desc = "List all active ShadowHook hooks and register intercepts with their target addresses, orig trampoline addresses, hit counts, and active override rules.",
            props = JSONObject(),
            required = emptyList()
        ))
        put(toolDef(
            name = "hook_get_logs",
            desc = "Retrieve ShadowHook hit logs (caller LR, PC, arguments, original return value, final return value, bypass/modified flags) from the ring buffer, or clear logs.",
            props = JSONObject()
                .put("hook_id", prop("integer", "Filter by hook ID (0 or omit for all hooks)."))
                .put("since_seq", prop("integer", "Incremental sequence cursor; only return records with seq > since_seq (default 0)."))
                .put("limit", prop("integer", "Max records to return (default 100, max 512)."))
                .put("clear", prop("boolean", "If true, clear matching records after reading (default false).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "hook_call_function",
            desc = "Safely invoke a native function inside the target process with signal crash protection (SIGSEGV/SIGBUS/SIGILL/SIGFPE guarded). Can call an active hook's original ShadowHook trampoline (via hook_id), a raw function address, or a resolved (lib_name + symbol_name).",
            props = JSONObject()
                .put("hook_id", prop("integer", "Active inline hook ID whose ShadowHook orig trampoline will be called."))
                .put("address", prop("string", "Raw target function virtual address in hex."))
                .put("lib_name", prop("string", "Shared library name to resolve symbol_name via xDL."))
                .put("symbol_name", prop("string", "Symbol name inside lib_name to call."))
                .put("args", prop("array", "Positional arguments array: numbers, strings, or typed objects {type:'int64'|'uint64'|'ptr'|'float'|'double'|'string', value:...}."))
                .put("return_type", prop("string", "Expected return type: 'int64' (default), 'uint64', 'int32', 'ptr', 'float', 'double', 'string', or 'void'.")),
            required = emptyList()
        ))
    }

    fun executeTool(context: Context, name: String, args: JSONObject): McpToolCallResult {
        return try {
            when (name) {
                "get_status" -> handleGetStatus()
                "list_processes" -> handleListProcesses(context, args)
                "attach_process" -> handleAttachProcess(context, args)
                "process_control" -> handleProcessControl(args)
                "list_regions" -> handleListRegions(args)
                "set_search_range" -> handleSetSearchRange(args)
                "read_memory" -> handleReadMemory(args)
                "write_memory" -> handleWriteMemory(args)
                "patch_memory" -> handlePatchMemory(args)
                "view_memory" -> handleViewMemory(args)
                "search_memory" -> handleSearchMemory(args)
                "fuzzy_search" -> handleFuzzySearch(args)
                "pointer_search" -> handlePointerSearch(args)
                "get_search_results" -> handleGetSearchResults(args)
                "manage_search_results" -> handleManageSearchResults(args)
                "clear_search" -> handleClearSearch()
                "manage_saved_results" -> handleManageSavedResults(args)
                "assemble" -> handleAssemble(args)
                "disassemble" -> handleDisassemble(args)
                "allocate_memory_page" -> handleAllocatePage(args)
                "copy_memory" -> handleCopyMemory(args)
                "dump_memory" -> handleDumpMemory(context, args)
                "freeze_value" -> handleFreezeValue(args)
                "manage_breakpoints" -> handleManageBreakpoints(args)
                "bp_modify_register" -> handleBpModifyRegister(args)
                "get_breakpoint_trace" -> handleGetBreakpointTrace(args)
                "inspect_so_module" -> handleInspectSoModule(args)
                "hook_install" -> handleHookInstall(args)
                "hook_uninstall" -> handleHookUninstall(args)
                "hook_list" -> handleHookList()
                "hook_get_logs" -> handleHookGetLogs(args)
                "hook_call_function" -> handleHookCallFunction(args)
                else -> McpToolCallResult("Unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            McpToolCallResult("Tool '$name' failed: ${t.message ?: t.javaClass.simpleName}", isError = true)
        }
    }

    private fun handleGetStatus(): McpToolCallResult {
        val ui = Session.ui.value
        val eng = Session.engineForMcp()
        val asm = AsmEngineManager.status
        val out = JSONObject().apply {
            put("work_mode", ui.workMode.code)
            put("root_available", ui.hasRootPermission)
            put("attached", eng.attachedOk && eng.pid > 0)
            put("pid", eng.pid)
            put("process_paused", ui.processPaused)
            put("target_label", ui.target?.label ?: "")
            put("target_package", ui.target?.packageName ?: "")
            put("target_64bit", eng.x64)
            put("root_daemon_active", eng.rootDaemonActive)
            put("native_backend_active", eng.nativeBackendActive)
            put("search_result_total", ui.totalCount)
            put("saved_count", ui.saved.size)
            put("breakpoints_count", ui.breakpoints.size)
            put("selected_regions", JSONArray(ui.selectedRegions.toList()))
            put("asm_engine", JSONObject().apply {
                put("installed", asm.installed)
                put("keystone_ready", asm.keystoneReady)
                put("capstone_ready", asm.capstoneReady)
                put("abi", asm.abi)
            })
        }
        return McpToolCallResult(out.toString(2))
    }

    private fun handleListProcesses(context: Context, args: JSONObject): McpToolCallResult {
        val query = args.optString("query", "").trim().lowercase(Locale.ROOT)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val procs = ProcessScanner.scan(context)
        val filtered = if (query.isEmpty()) procs else procs.filter {
            it.label.lowercase(Locale.ROOT).contains(query) ||
                it.packageName.lowercase(Locale.ROOT).contains(query) ||
                it.pid.toString().contains(query)
        }
        val arr = JSONArray()
        filtered.take(limit).forEach { p ->
            arr.put(JSONObject().apply {
                put("pid", p.pid)
                put("package_name", p.packageName)
                put("label", p.label)
                put("x64", p.x64)
                put("uid", p.uid)
                put("memory_bytes", p.sizeBytes)
                put("is_virtual", p.isVirtual)
            })
        }
        val out = JSONObject()
            .put("total_matched", filtered.size)
            .put("returned", arr.length())
            .put("processes", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleAttachProcess(context: Context, args: JSONObject): McpToolCallResult {
        val pidArg = args.optInt("pid", -1)
        val pkgArg = args.optString("package_name", "").trim()
        val procs = ProcessScanner.scan(context)
        val target: TargetProcess = when {
            pidArg > 0 -> procs.firstOrNull { it.pid == pidArg }
                ?: TargetProcess(
                    pid = pidArg,
                    packageName = pkgArg.ifEmpty { "pid_$pidArg" },
                    label = pkgArg.ifEmpty { "PID $pidArg" },
                    x64 = true
                )
            pkgArg.isNotEmpty() -> procs.firstOrNull {
                it.packageName.equals(pkgArg, ignoreCase = true) ||
                    it.label.equals(pkgArg, ignoreCase = true)
            } ?: procs.firstOrNull {
                it.packageName.contains(pkgArg, ignoreCase = true) ||
                    it.label.contains(pkgArg, ignoreCase = true)
            } ?: return McpToolCallResult("Process not found matching '$pkgArg'", isError = true)
            else -> return McpToolCallResult("Either 'pid' or 'package_name' must be provided", isError = true)
        }

        val ok = synchronized(engineLock) { Session.attachFromMcpSync(target) }
        val eng = Session.engineForMcp()
        val out = JSONObject().apply {
            put("attached", ok)
            put("pid", target.pid)
            put("package_name", target.packageName)
            put("label", target.label)
            put("x64", eng.x64)
            put("root_daemon_active", eng.rootDaemonActive)
            if (!ok) put("error", eng.lastAttachError.ifBlank { "Attach failed" })
        }
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleProcessControl(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        val (ok, msg) = synchronized(engineLock) { Session.controlProcessFromMcp(action) }
        val out = JSONObject().put("success", ok).put("action", action).put("message", msg)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleListRegions(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached. Call attach_process first.", isError = true)
        val codeFilter = args.optString("region_codes", "").trim()
            .split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val modFilter = args.optString("module_filter", "").trim().lowercase(Locale.ROOT)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 100).coerceIn(1, 500)

        val all = synchronized(engineLock) { eng.regions() }
        val filtered = all.filter { r ->
            val codeOk = codeFilter.isEmpty() || r.code.lowercase(Locale.ROOT) in codeFilter
            val modOk = modFilter.isEmpty() || r.path.lowercase(Locale.ROOT).contains(modFilter)
            codeOk && modOk
        }
        // Aggregate module base addresses for fast lookup by agents
        val modBases = LinkedHashMap<String, Pair<Long, Long>>()
        for (r in filtered) {
            if (r.path.isNotBlank() && !r.path.startsWith("[")) {
                val modName = r.path.substringAfterLast('/')
                val prev = modBases[modName]
                if (prev == null) {
                    modBases[modName] = r.start to r.end
                } else {
                    modBases[modName] = minOf(prev.first, r.start) to maxOf(prev.second, r.end)
                }
            }
        }
        val modArr = JSONArray()
        modBases.entries.take(60).forEach { (name, range) ->
            modArr.put(JSONObject().apply {
                put("module", name)
                put("base", toHex(range.first))
                put("end", toHex(range.second))
                put("size", range.second - range.first)
            })
        }

        val arr = JSONArray()
        filtered.drop(offset).take(limit).forEach { r ->
            arr.put(JSONObject().apply {
                put("start", toHex(r.start))
                put("end", toHex(r.end))
                put("size", r.size)
                put("perms", r.perms)
                put("code", r.code)
                put("path", r.path)
            })
        }
        val out = JSONObject()
            .put("total_regions", filtered.size)
            .put("offset", offset)
            .put("returned", arr.length())
            .put("module_bases", modArr)
            .put("regions", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleSetSearchRange(args: JSONObject): McpToolCallResult {
        val raw = args.optString("regions", "").trim()
        if (raw.isEmpty()) return McpToolCallResult("'regions' is required", isError = true)
        val resolved: Set<String> = when (raw.lowercase(Locale.ROOT)) {
            "default" -> RegionCatalog.DEFAULT_SELECTED
            "all" -> RegionCatalog.ALL.map { it.code }.toSet()
            else -> parseRegionSet(raw)
        }
        Session.setSelectedRegionsFromMcp(resolved)
        val out = JSONObject()
            .put("success", true)
            .put("selected_regions", JSONArray(resolved.toList()))
        return McpToolCallResult(out.toString(2))
    }

    private fun handleReadMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val addr = parseAddress(args.optString("address", ""))
            ?: return McpToolCallResult("Invalid address format", isError = true)
        val size = args.optInt("size", 16).coerceIn(1, 4096)
        val typeStr = args.optString("type", "").trim().lowercase(Locale.ROOT)

        val bytes = synchronized(engineLock) { eng.readBlock(addr, size) }
        if (bytes.isEmpty()) {
            return McpToolCallResult("Failed to read memory at ${toHex(addr)}", isError = true)
        }
        val out = JSONObject().apply {
            put("address", toHex(addr))
            put("size", bytes.size)
            put("region", eng.regionCodeAt(addr))
            put("hex", bytesToHex(bytes))
            if (bytes.size >= 4) {
                val dw = bytesToLittleEndianLong(bytes, 4)
                put("dword", dw.toInt())
                put("uint32", dw and 0xFFFFFFFFL)
                put("float", java.lang.Float.intBitsToFloat(dw.toInt()).toDouble())
            }
            if (bytes.size >= 8) {
                val qw = bytesToLittleEndianLong(bytes, 8)
                put("qword", qw)
                put("double", java.lang.Double.longBitsToDouble(qw))
            }
            if (typeStr.isNotEmpty()) {
                put("typed_value", decodeTyped(bytes, typeStr))
            }
        }
        return McpToolCallResult(out.toString(2))
    }

    private fun handleWriteMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val addr = parseAddress(args.optString("address", ""))
            ?: return McpToolCallResult("Invalid address format", isError = true)
        val hexStr = args.optString("hex_bytes", "").trim()

        val ok: Boolean
        val writtenBytes: Int
        var syncedBits: Long? = null
        if (hexStr.isNotEmpty()) {
            val bytes = hexToBytes(hexStr)
            if (bytes.isEmpty()) return McpToolCallResult("Invalid hex_bytes", isError = true)
            ok = synchronized(engineLock) { eng.writeBlock(addr, bytes) }
            writtenBytes = bytes.size
            if (ok) {
                syncedBits = synchronized(engineLock) { eng.read(addr, writtenBytes.coerceIn(1, 8)) }
            }
        } else {
            val valStr = args.optString("value", "").trim()
            if (valStr.isEmpty()) return McpToolCallResult("Provide either 'hex_bytes' or 'value'", isError = true)
            val typeFlag = parseValueType(args.optString("type", "dword"))
            val rawBits = parseTypedValueBits(valStr, typeFlag)
                ?: return McpToolCallResult("Cannot parse value '$valStr' as ${ValueType.shortName(typeFlag)}", isError = true)
            writtenBytes = ValueType.sizeOf(typeFlag)
            ok = synchronized(engineLock) { eng.write(addr, writtenBytes, rawBits) }
            if (ok) syncedBits = rawBits
        }
        if (ok && syncedBits != null) {
            Session.syncWrittenAddressFromMcp(addr, syncedBits)
        }
        val out = JSONObject()
            .put("success", ok)
            .put("address", toHex(addr))
            .put("bytes_written", if (ok) writtenBytes else 0)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handlePatchMemory(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val arr = JSONArray()
                synchronized(engineLock) {
                    patchHistory.reversed().take(50).forEach { p ->
                        arr.put(JSONObject().apply {
                            put("patch_id", p.id)
                            put("address", toHex(p.address))
                            put("original_hex", bytesToHex(p.originalBytes))
                            put("patched_hex", bytesToHex(p.patchedBytes))
                            put("note", p.note)
                            put("timestamp", p.timestamp)
                        })
                    }
                }
                McpToolCallResult(JSONObject().put("count", arr.length()).put("patches", arr).toString(2))
            }
            "apply" -> {
                val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val newBytes = hexToBytes(args.optString("hex_bytes", ""))
                if (newBytes.isEmpty()) return McpToolCallResult("Invalid hex_bytes", isError = true)
                val note = args.optString("note", "").trim()
                val result = synchronized(engineLock) {
                    val orig = eng.readBlock(addr, newBytes.size)
                    if (orig.size != newBytes.size) return@synchronized null to "Failed to snapshot original bytes at ${toHex(addr)}"
                    val wrote = eng.writeBlock(addr, newBytes)
                    if (!wrote) return@synchronized null to "Failed to write patch bytes at ${toHex(addr)}"
                    val rec = PatchRecord(
                        id = nextPatchId++,
                        address = addr,
                        originalBytes = orig,
                        patchedBytes = newBytes,
                        note = note,
                        timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    )
                    patchHistory.add(rec)
                    if (patchHistory.size > 100) patchHistory.removeAt(0)
                    rec to ""
                }
                val rec = result.first
                if (rec == null) return McpToolCallResult(result.second, isError = true)
                val out = JSONObject()
                    .put("success", true)
                    .put("patch_id", rec.id)
                    .put("address", toHex(rec.address))
                    .put("original_hex", bytesToHex(rec.originalBytes))
                    .put("patched_hex", bytesToHex(rec.patchedBytes))
                McpToolCallResult(out.toString(2))
            }
            "rollback" -> {
                val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
                val targetId = args.optInt("patch_id", -1)
                val outcome = synchronized(engineLock) {
                    val idx = if (targetId > 0) patchHistory.indexOfLast { it.id == targetId } else patchHistory.lastIndex
                    if (idx < 0) return@synchronized null to "No matching patch record found to rollback"
                    val rec = patchHistory[idx]
                    val ok = eng.writeBlock(rec.address, rec.originalBytes)
                    if (!ok) return@synchronized null to "Failed to write original bytes back to ${toHex(rec.address)}"
                    patchHistory.removeAt(idx)
                    rec to ""
                }
                val rec = outcome.first
                if (rec == null) return McpToolCallResult(outcome.second, isError = true)
                val out = JSONObject()
                    .put("success", true)
                    .put("rolled_back_patch_id", rec.id)
                    .put("address", toHex(rec.address))
                    .put("restored_hex", bytesToHex(rec.originalBytes))
                McpToolCallResult(out.toString(2))
            }
            else -> McpToolCallResult("Unsupported patch action '$action'", isError = true)
        }
    }

    private fun handleViewMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val rawAddr = parseAddress(args.optString("address", ""))
            ?: return McpToolCallResult("Invalid address format", isError = true)
        val alignedAddr = rawAddr and -4L
        val length = args.optInt("bytes", 128).coerceIn(4, 1024)
        val alignedLen = ((length + 3) / 4) * 4
        val disasmArchStr = args.optString("disasm_arch", "").trim()
        val asmArch = if (disasmArchStr.isNotEmpty()) AsmEngineManager.archFromString(disasmArchStr) else null

        val bytes = synchronized(engineLock) { eng.readBlock(alignedAddr, alignedLen) }
        if (bytes.isEmpty()) {
            return McpToolCallResult("Failed to read memory window at ${toHex(alignedAddr)}", isError = true)
        }
        val disasmMap = if (asmArch != null && AsmEngineManager.status.capstoneReady) {
            AsmEngineManager.disassembleBlockMap(asmArch, alignedAddr, bytes, alignedLen / 2)
        } else {
            emptyMap()
        }

        val rows = JSONArray()
        var off = 0
        while (off + 4 <= bytes.size) {
            val rowAddr = alignedAddr + off
            val dw = ((bytes[off].toLong() and 0xFF)) or
                ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                ((bytes[off + 2].toLong() and 0xFF) shl 16) or
                ((bytes[off + 3].toLong() and 0xFF) shl 24)
            val ptrTag = if (eng.x64 && off + 8 <= bytes.size) {
                val qw = dw or
                    ((bytes[off + 4].toLong() and 0xFF) shl 32) or
                    ((bytes[off + 5].toLong() and 0xFF) shl 40) or
                    ((bytes[off + 6].toLong() and 0xFF) shl 48) or
                    ((bytes[off + 7].toLong() and 0xFF) shl 56)
                eng.classifyPointer(qw)
            } else {
                eng.classifyPointer(dw)
            }
            val rowObj = JSONObject().apply {
                put("address", toHex(rowAddr))
                put("hex", Formatter.hex(dw, 4))
                put("dword", dw.toInt())
                put("float", java.lang.Float.intBitsToFloat(dw.toInt()).toDouble())
                put("ascii", Formatter.bytesToAscii(dw, 4))
                if (ptrTag != 0) put("pointer_flag", ptrTag)
                disasmMap[rowAddr]?.let { put("disasm", it) }
            }
            rows.put(rowObj)
            off += 4
        }
        val out = JSONObject()
            .put("base_address", toHex(alignedAddr))
            .put("region", eng.regionCodeAt(alignedAddr))
            .put("bytes_read", bytes.size)
            .put("rows", rows)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleSearchMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val expr = args.optString("expression", "").trim()
        if (expr.isEmpty()) return McpToolCallResult("'expression' is required", isError = true)

        val typeFlag = parseValueType(args.optString("type", "dword"))
        val sign = parseSearchSign(args.optString("sign", "eq"))
        val refine = args.optBoolean("refine", false)
        val encryptedXor = args.optBoolean("encrypted_xor", false)
        val previewLimit = args.optInt("preview_limit", 50).coerceIn(1, 200)
        val regions = parseRegionSet(args.optString("regions", ""))
        val memFrom = parseAddress(args.optString("memory_from", "")) ?: 0L
        val memTo = parseAddress(args.optString("memory_to", "")) ?: Long.MAX_VALUE

        val q = NumberParser.parse(expr, typeFlag, sign)
        val firstNum = q.numbers.firstOrNull() ?: 0L
        val criteria = MemoryEngine.Criteria(
            type = q.baseType and 0x7F,
            value = firstNum,
            from = firstNum,
            to = q.to.firstOrNull() ?: firstNum,
            isRange = q.kind == NumberParser.Kind.RANGE,
            notRange = q.notRange,
            sign = q.sign,
            xorKey = q.xorKey,
            xorOffset = q.xorOffset,
            orderedGroup = if (q.kind == NumberParser.Kind.GROUP) q.numbers else emptyList(),
            groupTo = if (q.kind == NumberParser.Kind.GROUP) q.to else emptyList(),
            groupOps = if (q.kind == NumberParser.Kind.GROUP) q.groupOps else emptyList(),
            ordered = q.ordered,
            groupTypes = if (q.kind == NumberParser.Kind.GROUP) q.types else emptyList(),
            area = q.area,
            memoryFrom = memFrom,
            memoryTo = memTo,
            rawExpression = expr,
            encryptedXor = encryptedXor,
            selectedRegions = regions,
            isText = q.kind == NumberParser.Kind.TEXT,
            textBytes = q.textBytes,
            textUtf16 = q.textUtf16
        )

        val prev = if (refine) Session.ui.value.results else null
        val res = synchronized(engineLock) { eng.search(criteria, previous = prev) }
        Session.syncSearchResultsFromMcp(res.items, res.totalFound)

        val arr = JSONArray()
        res.items.take(previewLimit).forEach { arr.put(itemToJson(it)) }
        val out = JSONObject()
            .put("refine", refine)
            .put("total_count", res.totalFound)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleFuzzySearch(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        val typeFlag = parseValueType(args.optString("type", "dword"))
        val previewLimit = args.optInt("preview_limit", 50).coerceIn(1, 200)
        val regions = parseRegionSet(args.optString("regions", ""))

        val isInit = action == "init" || action == "unknown"
        val sign = when (action) {
            "init", "unknown" -> ValueType.SIGN_EQUAL
            "increased", "inc", "gt" -> ValueType.SIGN_GREATER_EQ
            "decreased", "dec", "lt" -> ValueType.SIGN_LESS_EQ
            "changed", "neq" -> ValueType.SIGN_NOT_EQUAL
            "unchanged", "eq" -> ValueType.SIGN_EQUAL
            "delta" -> ValueType.SIGN_NOT_EQUAL
            else -> return McpToolCallResult("Unsupported fuzzy action '$action'", isError = true)
        }
        val deltaStr = args.optString("delta", "").trim()
        val deltaBits = if (deltaStr.isNotEmpty()) parseTypedValueBits(deltaStr, typeFlag) ?: 0L else 0L

        val prev = if (isInit) null else Session.ui.value.results
        val res = synchronized(engineLock) {
            eng.fuzzySearch(
                type = typeFlag,
                sign = sign,
                delta = deltaBits,
                previous = prev,
                selectedRegions = regions
            )
        }
        Session.syncSearchResultsFromMcp(res.items, res.totalFound)
        val arr = JSONArray()
        res.items.take(previewLimit).forEach { arr.put(itemToJson(it)) }
        val out = JSONObject()
            .put("action", action)
            .put("total_count", res.totalFound)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handlePointerSearch(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val targetAddr = parseAddress(args.optString("target_address", ""))
            ?: return McpToolCallResult("Invalid target_address", isError = true)
        val maxOffset = args.optLong("max_offset", 512L).coerceIn(0L, 64L * 1024L * 1024L)
        val ptrType = when (args.optString("pointer_type", "auto").trim().lowercase(Locale.ROOT)) {
            "dword", "32", "32bit" -> ValueType.DWORD
            "qword", "64", "64bit" -> ValueType.QWORD
            else -> ValueType.AUTO
        }
        val refine = args.optBoolean("refine", false)
        val previewLimit = args.optInt("preview_limit", 50).coerceIn(1, 200)
        val regions = parseRegionSet(args.optString("regions", ""))

        val prev = if (refine) Session.ui.value.results else null
        val res = synchronized(engineLock) {
            eng.pointerSearch(
                targetAddress = targetAddr,
                maxOffset = maxOffset,
                type = ptrType,
                previous = prev,
                selectedRegions = regions
            )
        }
        Session.syncSearchResultsFromMcp(res.items, res.totalFound)
        val arr = JSONArray()
        res.items.take(previewLimit).forEach { item ->
            val obj = itemToJson(item)
            val offset = targetAddr - item.value
            obj.put("pointer_value", toHex(item.value))
            obj.put("offset", toHex(offset))
            arr.put(obj)
        }
        val out = JSONObject()
            .put("depth", 1)
            .put("target_address", toHex(targetAddr))
            .put("max_offset", maxOffset)
            .put("refine", refine)
            .put("total_count", res.totalFound)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleGetSearchResults(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val ui = Session.ui.value
        val slice = ui.results.drop(offset).take(limit)
        val refreshed = if (slice.isNotEmpty()) {
            val liveMap = synchronized(engineLock) {
                eng.batchReadRawValues(slice.map { it.address to it.size })
            }
            slice.map { item -> liveMap[item.address]?.let { item.copy(value = it) } ?: item }
        } else {
            emptyList()
        }
        val arr = JSONArray()
        refreshed.forEach { arr.put(itemToJson(it)) }
        val out = JSONObject()
            .put("total_count", ui.totalCount)
            .put("offset", offset)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleManageSearchResults(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        return when (action) {
            "clear" -> handleClearSearch()
            "remove_addresses" -> {
                val addrs = parseAddressList(args.optString("addresses", ""))
                if (addrs.isEmpty()) return McpToolCallResult("No valid addresses provided", isError = true)
                val removed = synchronized(engineLock) { Session.removeSearchAddressesFromMcp(addrs) }
                McpToolCallResult(JSONObject().put("success", true).put("removed_count", removed).toString(2))
            }
            "save_to_list" -> {
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val count = args.optInt("count", 20).coerceIn(1, 200)
                val note = args.optString("note", "").trim()
                val slice = Session.ui.value.results.drop(offset).take(count).map { item ->
                    if (note.isNotEmpty()) item.copy(name = note, note = note) else item
                }
                val added = synchronized(engineLock) { Session.saveItemsFromMcp(slice) }
                McpToolCallResult(JSONObject().put("success", true).put("saved_count", slice.size).put("newly_added", added).toString(2))
            }
            else -> McpToolCallResult("Unsupported action '$action'", isError = true)
        }
    }

    private fun handleClearSearch(): McpToolCallResult {
        synchronized(engineLock) { Session.clearResults() }
        return McpToolCallResult(JSONObject().put("cleared", true).toString(2))
    }

    private fun handleManageSavedResults(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val saved = Session.ui.value.saved
                val eng = Session.engineForMcp()
                val refreshed = if (eng.attachedOk && eng.pid > 0 && saved.isNotEmpty()) {
                    val liveMap = synchronized(engineLock) {
                        eng.batchReadRawValues(saved.map { it.address to it.size })
                    }
                    saved.map { item -> liveMap[item.address]?.let { item.copy(value = it) } ?: item }
                } else saved
                val arr = JSONArray()
                refreshed.forEach { item ->
                    val (isFrozen, fType) = Session.isAddressFrozenInMcp(item.address)
                    arr.put(itemToJson(item).apply {
                        put("note", item.note.ifBlank { item.name })
                        put("frozen", isFrozen)
                        put("freeze_type", if (isFrozen) fType else item.freezeType)
                    })
                }
                McpToolCallResult(JSONObject().put("count", arr.length()).put("saved", arr).toString(2))
            }
            "add" -> {
                val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val typeFlag = parseValueType(args.optString("type", "dword"))
                val note = args.optString("note", "").trim()
                val sz = ValueType.sizeOf(typeFlag)
                val rawVal = synchronized(engineLock) { eng.read(addr, sz) }
                val region = eng.regionCodeAt(addr)
                val item = MemoryItem(address = addr, value = rawVal, flags = typeFlag, name = note, note = note, region = region)
                val added = synchronized(engineLock) { Session.saveItemsFromMcp(listOf(item)) }
                McpToolCallResult(JSONObject().put("success", true).put("address", toHex(addr)).put("newly_added", added).toString(2))
            }
            "remove" -> {
                val addrs = parseAddressList(args.optString("addresses", args.optString("address", "")))
                if (addrs.isEmpty()) return McpToolCallResult("No valid addresses provided", isError = true)
                val removed = synchronized(engineLock) { Session.removeSavedAddressesFromMcp(addrs) }
                McpToolCallResult(JSONObject().put("success", true).put("removed_count", removed).toString(2))
            }
            "update_note" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val note = args.optString("note", "").trim()
                val ok = synchronized(engineLock) { Session.updateSavedNoteFromMcp(addr, note) }
                McpToolCallResult(JSONObject().put("success", ok).put("address", toHex(addr)).put("note", note).toString(2), isError = !ok)
            }
            "clear" -> {
                val cleared = synchronized(engineLock) { Session.clearSavedFromMcp() }
                McpToolCallResult(JSONObject().put("success", true).put("cleared_count", cleared).toString(2))
            }
            else -> McpToolCallResult("Unsupported saved-list action '$action'", isError = true)
        }
    }

    private fun handleAssemble(args: JSONObject): McpToolCallResult {
        val code = args.optString("code", "").trim()
        if (code.isEmpty()) return McpToolCallResult("'code' is required", isError = true)
        val eng = Session.engineForMcp()
        val archStr = args.optString("arch", "").trim()
        val arch = if (archStr.isNotEmpty()) AsmEngineManager.archFromString(archStr) else AsmEngineManager.defaultArchForDevice(eng.x64)
        val addr = parseAddress(args.optString("address", "0")) ?: 0L
        val writeBack = args.optBoolean("write_to_address", false)

        val asmResult = AsmEngineManager.assemble(arch, addr, code)
        val bytes = asmResult.getOrElse { err ->
            return McpToolCallResult("Assemble failed: ${err.message}", isError = true)
        }
        var written = false
        if (writeBack) {
            val activeEng = requireAttached() ?: return McpToolCallResult("Cannot write_to_address: no process attached", isError = true)
            written = synchronized(engineLock) { activeEng.writeBlock(addr, bytes) }
        }
        val out = JSONObject()
            .put("arch", arch.name)
            .put("address", toHex(addr))
            .put("byte_count", bytes.size)
            .put("hex_bytes", bytesToHex(bytes))
            .put("written_to_memory", written)
        return McpToolCallResult(out.toString(2), isError = writeBack && !written)
    }

    private fun handleDisassemble(args: JSONObject): McpToolCallResult {
        val eng = Session.engineForMcp()
        val archStr = args.optString("arch", "").trim()
        val arch = if (archStr.isNotEmpty()) AsmEngineManager.archFromString(archStr) else AsmEngineManager.defaultArchForDevice(eng.x64)
        val addr = parseAddress(args.optString("address", "0")) ?: 0L
        val hexStr = args.optString("hex_bytes", "").trim()
        val maxInsns = args.optInt("max_instructions", 32).coerceIn(1, 256)

        val bytes: ByteArray = if (hexStr.isNotEmpty()) {
            hexToBytes(hexStr)
        } else {
            val activeEng = requireAttached() ?: return McpToolCallResult("No target process attached (or provide hex_bytes).", isError = true)
            val len = args.optInt("bytes", 64).coerceIn(2, 1024)
            synchronized(engineLock) { activeEng.readBlock(addr, len) }
        }
        if (bytes.isEmpty()) return McpToolCallResult("No bytes available to disassemble", isError = true)
        if (!AsmEngineManager.status.capstoneReady) {
            return McpToolCallResult("Disassembler pack is not loaded. Import the ASM pack in Extensions first.", isError = true)
        }

        val map = AsmEngineManager.disassembleBlockMap(arch, addr, bytes, maxInsns)
        val arr = JSONArray()
        map.entries.sortedBy { it.key }.forEach { (insnAddr, insnText) ->
            arr.put(JSONObject().put("address", toHex(insnAddr)).put("instruction", insnText))
        }
        val out = JSONObject()
            .put("arch", arch.name)
            .put("base_address", toHex(addr))
            .put("count", arr.length())
            .put("instructions", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleAllocatePage(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val hint = parseAddress(args.optString("hint_address", "0")) ?: 0L
        val size = args.optInt("size", 4096).coerceIn(4096, 16 * 1024 * 1024)
        val read = args.optBoolean("read", true)
        val write = args.optBoolean("write", true)
        val exec = args.optBoolean("exec", false)

        val (allocAddr, msg) = synchronized(engineLock) {
            eng.allocatePage(hint, read, write, exec, size)
        }
        val ok = allocAddr != 0L
        val out = JSONObject()
            .put("success", ok)
            .put("address", if (ok) toHex(allocAddr) else "0x0")
            .put("size", size)
            .put("perms", "${if (read) 'r' else '-'}${if (write) 'w' else '-'}${if (exec) 'x' else '-'}")
            .put("message", msg)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleCopyMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val from = parseAddress(args.optString("from_address", ""))
            ?: return McpToolCallResult("Invalid from_address", isError = true)
        val to = parseAddress(args.optString("to_address", ""))
            ?: return McpToolCallResult("Invalid to_address", isError = true)
        val size = args.optLong("size", 0L).coerceIn(1L, 16L * 1024L * 1024L)
        val ok = synchronized(engineLock) { eng.copyMemory(from, to, size) }
        val out = JSONObject()
            .put("success", ok)
            .put("from_address", toHex(from))
            .put("to_address", toHex(to))
            .put("size", size)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleDumpMemory(context: Context, args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val start = parseAddress(args.optString("start_address", ""))
            ?: return McpToolCallResult("Invalid start_address", isError = true)
        val end = parseAddress(args.optString("end_address", ""))
            ?: return McpToolCallResult("Invalid end_address", isError = true)
        if (end <= start) return McpToolCallResult("end_address must be greater than start_address", isError = true)
        val outPath = args.optString("output_path", "").trim().ifEmpty {
            File(context.cacheDir, "mcp_dump_${toHex(start)}_${toHex(end)}.bin").absolutePath
        }
        val ok = synchronized(engineLock) { eng.dumpMemory(start, end, outPath) }
        val out = JSONObject()
            .put("success", ok)
            .put("start_address", toHex(start))
            .put("end_address", toHex(end))
            .put("output_path", outPath)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleFreezeValue(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        when (action) {
            "unlock_all" -> {
                synchronized(engineLock) { eng.clearNativeFreezes() }
                Session.clearAllFreezeStateFromMcp()
                return McpToolCallResult(JSONObject().put("action", "unlock_all").put("frozen", false).put("success", true).toString(2))
            }
            "unlock" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val typeFlag = parseValueType(args.optString("type", "dword"))
                synchronized(engineLock) { eng.setFreeze(addr, typeFlag, 0L, enabled = false) }
                Session.syncFreezeStateFromMcp(addr, typeFlag, 0L, enabled = false)
                return McpToolCallResult(JSONObject().put("action", "unlock").put("address", toHex(addr)).put("frozen", false).put("success", true).toString(2))
            }
            "lock" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val valStr = args.optString("value", "").trim()
                if (valStr.isEmpty()) return McpToolCallResult("'value' is required for lock", isError = true)
                val typeFlag = parseValueType(args.optString("type", "dword"))
                val bits = parseTypedValueBits(valStr, typeFlag)
                    ?: return McpToolCallResult("Invalid numeric value '$valStr'", isError = true)
                val fMode = when (args.optString("freeze_mode", "exact").lowercase(Locale.ROOT)) {
                    "increment", "inc" -> 1
                    "decrement", "dec" -> 2
                    "range", "clamp" -> 3
                    else -> 0
                }
                val fromBits = args.optString("range_from", "").takeIf { it.isNotBlank() }?.let { parseTypedValueBits(it, typeFlag) } ?: 0L
                val toBits = args.optString("range_to", "").takeIf { it.isNotBlank() }?.let { parseTypedValueBits(it, typeFlag) } ?: 0L
                synchronized(engineLock) {
                    eng.setFreeze(addr, typeFlag, bits, enabled = true, freezeType = fMode, freezeFrom = fromBits, freezeTo = toBits)
                }
                Session.syncFreezeStateFromMcp(addr, typeFlag, bits, enabled = true, freezeType = fMode, freezeFrom = fromBits, freezeTo = toBits)
                return McpToolCallResult(JSONObject().put("action", "lock").put("address", toHex(addr)).put("frozen", true).put("freeze_type", fMode).put("success", true).toString(2))
            }
            else -> return McpToolCallResult("Unsupported freeze action '$action'", isError = true)
        }
    }

    private fun handleManageBreakpoints(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val snaps = synchronized(engineLock) { eng.bpSnapshot() }
                val arr = JSONArray()
                snaps.forEach { bp ->
                    val hitsArr = JSONArray()
                    bp.hits.take(30).forEach { h ->
                        val gprObj = JSONObject()
                        h.gprs.forEach { gprObj.put(it.name, toHex(it.value)) }
                        val fprObj = JSONObject()
                        h.fprs.forEach { fprObj.put(it.name, it.hexValue) }
                        hitsArr.put(JSONObject().apply {
                            put("pc", toHex(h.pc))
                            put("tid", h.tid)
                            put("hit_count", h.count)
                            put("module", h.moduleName)
                            put("module_offset", toHex(h.moduleOffset))
                            put("disasm", h.disasmText)
                            put("gprs", gprObj)
                            put("fprs", fprObj)
                        })
                    }
                    arr.put(JSONObject().apply {
                        put("id", bp.id)
                        put("pid", bp.pid)
                        put("address", toHex(bp.address))
                        put("type", bp.type.name)
                        put("mode", bp.mode.name)
                        put("size", bp.size)
                        put("running", bp.running)
                        put("paused", bp.paused)
                        put("attached_threads", bp.attachedThreads)
                        put("total_threads", bp.totalThreads)
                        put("total_hits", bp.totalHits)
                        put("last_error", bp.lastError)
                        put("hits", hitsArr)
                    })
                }
                McpToolCallResult(JSONObject().put("count", arr.length()).put("breakpoints", arr).toString(2))
            }
            "add" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val bpType = when (args.optString("bp_type", "rw").lowercase(Locale.ROOT)) {
                    "read", "r" -> BpType.READ
                    "write", "w" -> BpType.WRITE
                    "exec", "x" -> BpType.EXEC
                    else -> BpType.RW
                }
                val size = args.optInt("size", 4).let { if (it in listOf(1, 2, 4, 8)) it else 4 }
                val modeStr = args.optString("mode", "").lowercase(Locale.ROOT)
                val bpMode = when (modeStr) {
                    "perf" -> BpMode.PERF
                    "ptrace" -> BpMode.PTRACE
                    else -> if (Session.ui.value.settings["bp_backend"] == "1") BpMode.PTRACE else BpMode.PERF
                }
                val callstack = args.optBoolean("capture_callstack", true)
                val (id, err) = synchronized(engineLock) { eng.bpAdd(addr, bpType, size, bpMode, callstack) }
                Session.refreshBreakpointsNow()
                val ok = id > 0
                McpToolCallResult(
                    JSONObject().put("success", ok).put("bp_id", id).put("error", err).toString(2),
                    isError = !ok
                )
            }
            "pause", "resume" -> {
                val bpId = args.optInt("bp_id", 0)
                val paused = action == "pause"
                val ok = synchronized(engineLock) {
                    if (bpId > 0) eng.bpSetPaused(bpId, paused) else eng.bpSetAllPaused(paused)
                }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", ok).put("bp_id", bpId).put("paused", paused).toString(2), isError = !ok)
            }
            "clear_hits" -> {
                val bpId = args.optInt("bp_id", 0)
                synchronized(engineLock) { eng.bpClearHits(bpId) }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", true).put("bp_id", bpId).toString(2))
            }
            "remove" -> {
                val bpId = args.optInt("bp_id", 0)
                if (bpId <= 0) return McpToolCallResult("'bp_id' > 0 is required for remove", isError = true)
                val ok = synchronized(engineLock) { eng.bpRemove(bpId) }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", ok).put("bp_id", bpId).toString(2), isError = !ok)
            }
            "stop_all" -> {
                synchronized(engineLock) { eng.bpStopAll() }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", true).toString(2))
            }
            else -> McpToolCallResult("Unsupported breakpoint action '$action'", isError = true)
        }
    }

    private fun handleBpModifyRegister(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val bpId = args.optInt("bp_id", 0)
        val tid = args.optInt("tid", 0)
        val hitPc = parseAddress(args.optString("hit_pc", "0")) ?: 0L
        val regName = args.optString("reg_name", "").trim()
        val hexVal = args.optString("hex_value", "").trim().removePrefix("0x").removePrefix("0X")
        val isFp = args.optBoolean("is_fp", false)
        if (bpId <= 0 || tid <= 0 || regName.isEmpty() || hexVal.isEmpty()) {
            return McpToolCallResult("bp_id, tid, reg_name, and hex_value are required", isError = true)
        }
        val (ok, err) = synchronized(engineLock) { eng.bpSetRegister(bpId, tid, hitPc, regName, hexVal, isFp) }
        Session.refreshBreakpointsNow()
        val out = JSONObject()
            .put("success", ok)
            .put("bp_id", bpId)
            .put("tid", tid)
            .put("reg_name", regName)
            .put("hex_value", hexVal)
            .put("error", err)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    // ---------------- Helpers ----------------

    private fun requireAttached(): MemoryEngine? {
        val eng = Session.engineForMcp()
        return if (eng.attachedOk && eng.pid > 0) eng else null
    }

    private fun handleGetBreakpointTrace(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val bpId = args.optInt("bp_id", 0)
        val sinceSeq = args.optLong("since_seq", 0L).coerceAtLeast(0L)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val includeFpr = args.optBoolean("include_fpr", false)
        val filterSet = args.optString("reg_filter", "")
            .split(',', ' ', ';', '|')
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

        val events = synchronized(engineLock) { eng.bpTraceEvents(bpId = bpId, sinceSeq = sinceSeq, limit = limit) }
        val prevByPc = HashMap<Long, Map<String, Long>>()
        val arr = JSONArray()
        var maxSeq = sinceSeq
        for (ev in events) {
            if (ev.seq > maxSeq) maxSeq = ev.seq
            val prevRegs = prevByPc[ev.pc]
            val changedArr = JSONArray()
            val gprObj = JSONObject()
            for (r in ev.gprs) {
                val keep = filterSet.isEmpty() || r.name.uppercase(Locale.ROOT) in filterSet
                if (keep) gprObj.put(r.name, toHex(r.value))
                val oldVal = prevRegs?.get(r.name)
                if (oldVal != null && oldVal != r.value) {
                    changedArr.put(r.name)
                }
            }
            prevByPc[ev.pc] = ev.gprs.associate { it.name to it.value }
            val obj = JSONObject().apply {
                put("seq", ev.seq)
                put("timestamp_ms", ev.timestampMs)
                put("time", ev.formattedTime)
                put("bp_id", ev.bpId)
                put("tid", ev.tid)
                put("hit_type", ev.hitType.name)
                put("pc", toHex(ev.pc))
                put("module", ev.moduleName)
                put("module_offset", ev.offsetHex)
                put("disasm", ev.disasmText)
                put("gprs", gprObj)
                put("changed_regs", changedArr)
                if (includeFpr) {
                    val fprObj = JSONObject()
                    for (fp in ev.fprs) {
                        if (filterSet.isEmpty() || fp.name.uppercase(Locale.ROOT) in filterSet) {
                            fprObj.put(fp.name, fp.hexValue)
                        }
                    }
                    put("fprs", fprObj)
                }
            }
            arr.put(obj)
        }
        return McpToolCallResult(
            JSONObject()
                .put("count", arr.length())
                .put("next_since_seq", maxSeq)
                .put("events", arr)
                .toString(2)
        )
    }

    private fun handleInspectSoModule(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val modQuery = args.optString("module_name", "").trim()
        if (modQuery.isEmpty()) return McpToolCallResult("'module_name' is required", isError = true)
        val action = args.optString("action", "summary").trim().lowercase(Locale.ROOT)
        val filter = args.optString("filter", "").trim().lowercase(Locale.ROOT)
        val limit = args.optInt("limit", 100).coerceIn(1, 500)

        val regions = synchronized(engineLock) { eng.regions() }
        fun extractName(path: String): String {
            val clean = if (path.contains("!/")) path.substringAfterLast("!/") else path
            return clean.substringAfterLast('/').trim()
        }
        val exact = regions.filter { it.path.isNotBlank() && extractName(it.path).equals(modQuery, ignoreCase = true) }
        val matched = if (exact.isNotEmpty()) exact else regions.filter {
            it.path.isNotBlank() && (extractName(it.path).contains(modQuery, ignoreCase = true) || it.path.contains(modQuery, ignoreCase = true))
        }
        if (matched.isEmpty()) {
            return McpToolCallResult("Module '$modQuery' not found in attached process.", isError = true)
        }
        val sortedSegs = matched.sortedBy { it.start }
        val baseAddr = sortedSegs.first().start
        val rawPath = sortedSegs.first().path.removeSuffix(" (deleted)").trim()
        val modName = extractName(rawPath)

        // Load ELF bytes from disk (supports APK!/lib/... zip entry as well as plain .so file)
        val elfBytes: ByteArray? = runCatching {
            if (rawPath.contains("!/")) {
                val apkPath = rawPath.substringBefore("!/")
                val entryPath = rawPath.substringAfter("!/")
                java.util.zip.ZipFile(apkPath).use { zf ->
                    val entry = zf.getEntry(entryPath) ?: return@use null
                    zf.getInputStream(entry).use { it.readBytes() }
                }
            } else {
                val f = java.io.File(rawPath)
                if (f.isFile && f.canRead() && f.length() <= 128L * 1024L * 1024L) f.readBytes() else null
            }
        }.getOrNull()

        val segArr = JSONArray()
        for (s in sortedSegs) {
            segArr.put(JSONObject().apply {
                put("region", s.code)
                put("start", toHex(s.start))
                put("end", toHex(s.end))
                put("size", s.end - s.start)
                put("perms", s.perms)
                put("path", s.path)
            })
        }

        if (elfBytes == null || elfBytes.size < 64 ||
            elfBytes[0] != 0x7F.toByte() || elfBytes[1] != 'E'.code.toByte() ||
            elfBytes[2] != 'L'.code.toByte() || elfBytes[3] != 'F'.code.toByte()
        ) {
            return McpToolCallResult(
                JSONObject()
                    .put("module", modName)
                    .put("path", rawPath)
                    .put("base_address", toHex(baseAddr))
                    .put("segment_count", segArr.length())
                    .put("segments", segArr)
                    .put("elf_parsed", false)
                    .put("note", "ELF file could not be read from disk; returning live VMA segments only.")
                    .toString(2)
            )
        }

        val is64 = elfBytes[4].toInt() == 2
        val le = elfBytes[5].toInt() == 1
        fun u16(off: Int): Int {
            if (off + 2 > elfBytes.size) return 0
            val b0 = elfBytes[off].toInt() and 0xFF
            val b1 = elfBytes[off + 1].toInt() and 0xFF
            return if (le) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
        }
        fun u32(off: Int): Long {
            if (off + 4 > elfBytes.size) return 0L
            val b0 = elfBytes[off].toLong() and 0xFF
            val b1 = elfBytes[off + 1].toLong() and 0xFF
            val b2 = elfBytes[off + 2].toLong() and 0xFF
            val b3 = elfBytes[off + 3].toLong() and 0xFF
            return if (le) (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
            else ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3)
        }
        fun u64(off: Int): Long {
            if (off + 8 > elfBytes.size) return 0L
            val lo = u32(off)
            val hi = u32(off + 4)
            return if (le) (lo or (hi shl 32)) else ((lo shl 32) or hi)
        }
        fun readCStr(base: Int, off: Int): String {
            val start = base + off
            if (start < 0 || start >= elfBytes.size) return ""
            var end = start
            val maxEnd = minOf(elfBytes.size, start + 512)
            while (end < maxEnd && elfBytes[end].toInt() != 0) end++
            return String(elfBytes, start, end - start, Charsets.UTF_8)
        }

        val machineCode = u16(18)
        val archName = when (machineCode) {
            183 -> "aarch64"
            40 -> "arm"
            62 -> "x86_64"
            3 -> "x86"
            else -> "machine_$machineCode"
        }
        val shoff = if (is64) u64(40).toInt() else u32(32).toInt()
        val shentsize = if (is64) u16(58) else u16(46)
        val shnum = if (is64) u16(60) else u16(48)
        val shstrndx = if (is64) u16(62) else u16(50)

        data class ElfSec(val idx: Int, val name: String, val type: Long, val addr: Long, val offset: Int, val size: Int, val link: Int, val entsize: Int)
        val shstrOff = if (shoff > 0 && shstrndx in 0 until shnum) {
            val s = shoff + shstrndx * shentsize
            if (is64) u64(s + 24).toInt() else u32(s + 16).toInt()
        } else 0

        val sections = ArrayList<ElfSec>(shnum)
        if (shoff > 0 && shentsize >= 40) {
            for (i in 0 until shnum) {
                val s = shoff + i * shentsize
                if (s + shentsize > elfBytes.size) break
                val nameOff = u32(s).toInt()
                val sType = u32(s + 4)
                val sAddr = if (is64) u64(s + 16) else u32(s + 12)
                val sOff = if (is64) u64(s + 24).toInt() else u32(s + 16).toInt()
                val sSize = if (is64) u64(s + 32).toInt() else u32(s + 20).toInt()
                val sLink = if (is64) u32(s + 40).toInt() else u32(s + 24).toInt()
                val sEnt = if (is64) u64(s + 56).toInt() else u32(s + 36).toInt()
                sections.add(ElfSec(i, readCStr(shstrOff, nameOff), sType, sAddr, sOff, sSize, sLink, sEnt))
            }
        }

        fun collectSymbols(exportsOnly: Boolean, importsOnly: Boolean, jniOnly: Boolean): JSONArray {
            val outArr = JSONArray()
            val symSecs = sections.filter { it.type == 11L || it.type == 2L } // SHT_DYNSYM(11) or SHT_SYMTAB(2)
            val seen = HashSet<String>()
            for (sec in symSecs) {
                val strSec = sections.getOrNull(sec.link) ?: continue
                val entSz = if (sec.entsize > 0) sec.entsize else (if (is64) 24 else 16)
                val count = sec.size / entSz
                for (i in 0 until count) {
                    if (outArr.length() >= limit) return outArr
                    val e = sec.offset + i * entSz
                    if (e + entSz > elfBytes.size) break
                    val stName = u32(e).toInt()
                    val stInfo = if (is64) (elfBytes[e + 4].toInt() and 0xFF) else (elfBytes[e + 12].toInt() and 0xFF)
                    val stShndx = if (is64) u16(e + 6) else u16(e + 14)
                    val stVal = if (is64) u64(e + 8) else u32(e + 4)
                    val stSize = if (is64) u64(e + 16) else u32(e + 8)
                    val symName = readCStr(strSec.offset, stName)
                    if (symName.isEmpty()) continue
                    val isUndef = stShndx == 0
                    if (exportsOnly && (isUndef || stVal == 0L)) continue
                    if (importsOnly && !isUndef) continue
                    if (jniOnly && !symName.startsWith("Java_")) continue
                    if (filter.isNotEmpty() && !symName.lowercase(Locale.ROOT).contains(filter)) continue
                    val key = "$symName@$stVal"
                    if (!seen.add(key)) continue
                    val symType = when (stInfo and 0xF) {
                        1 -> "OBJECT"
                        2 -> "FUNC"
                        else -> "OTHER"
                    }
                    outArr.put(JSONObject().apply {
                        put("name", symName)
                        put("type", symType)
                        put("offset", toHex(stVal))
                        if (!isUndef && stVal != 0L) put("runtime_va", toHex(baseAddr + stVal))
                        put("size", stSize)
                    })
                }
            }
            return outArr
        }

        val result = JSONObject().apply {
            put("module", modName)
            put("path", rawPath)
            put("arch", archName)
            put("bits", if (is64) 64 else 32)
            put("file_size", elfBytes.size)
            put("base_address", toHex(baseAddr))
            put("action", action)
        }
        when (action) {
            "sections" -> {
                val secArr = JSONArray()
                for (s in sections) {
                    if (filter.isNotEmpty() && !s.name.lowercase(Locale.ROOT).contains(filter)) continue
                    if (secArr.length() >= limit) break
                    secArr.put(JSONObject().apply {
                        put("name", s.name)
                        put("offset", toHex(s.offset.toLong()))
                        put("vaddr", toHex(s.addr))
                        if (s.addr != 0L) put("runtime_va", toHex(baseAddr + s.addr))
                        put("size", s.size)
                    })
                }
                result.put("count", secArr.length()).put("sections", secArr)
            }
            "exports" -> {
                val syms = collectSymbols(exportsOnly = true, importsOnly = false, jniOnly = false)
                result.put("count", syms.length()).put("exports", syms)
            }
            "imports" -> {
                val syms = collectSymbols(exportsOnly = false, importsOnly = true, jniOnly = false)
                result.put("count", syms.length()).put("imports", syms)
            }
            "jni" -> {
                val syms = collectSymbols(exportsOnly = true, importsOnly = false, jniOnly = true)
                result.put("count", syms.length()).put("jni_exports", syms)
            }
            "strings" -> {
                val rodata = sections.firstOrNull { it.name == ".rodata" }
                val strArr = JSONArray()
                if (rodata != null && rodata.offset > 0 && rodata.size > 0) {
                    var i = rodata.offset
                    val end = minOf(elfBytes.size, rodata.offset + rodata.size)
                    while (i < end && strArr.length() < limit) {
                        val start = i
                        while (i < end && elfBytes[i].toInt() in 0x20..0x7E) i++
                        val len = i - start
                        if (len >= 4) {
                            val s = String(elfBytes, start, len, Charsets.US_ASCII)
                            if (filter.isEmpty() || s.lowercase(Locale.ROOT).contains(filter)) {
                                val relOff = (start - rodata.offset).toLong()
                                strArr.put(JSONObject().apply {
                                    put("text", s)
                                    put("runtime_va", toHex(baseAddr + rodata.addr + relOff))
                                    put("file_offset", toHex(start.toLong()))
                                })
                            }
                        }
                        while (i < end && elfBytes[i].toInt() !in 0x20..0x7E) i++
                    }
                }
                result.put("count", strArr.length()).put("strings", strArr)
            }
            else -> {
                val jniSyms = collectSymbols(exportsOnly = true, importsOnly = false, jniOnly = true)
                result.put("segment_count", segArr.length())
                result.put("segments", segArr)
                result.put("section_count", sections.size)
                result.put("key_sections", JSONArray(sections.filter { it.name in setOf(".text", ".rodata", ".data", ".bss", ".plt", ".got", ".dynsym") }.map { it.name }))
                result.put("jni_export_count", jniSyms.length())
            }
        }
        return McpToolCallResult(result.toString(2))
    }

    private fun handleHookInstall(args: JSONObject): McpToolCallResult {
        val eng = Session.engineForMcp()
        val existingId = args.optInt("hook_id", 0)
        val req = JSONObject()
        if (existingId > 0) {
            req.put("action", "update").put("hookId", existingId)
        } else {
            req.put("action", "install")
            val libName = args.optString("lib_name", "").trim()
            val symName = args.optString("symbol_name", "").trim()
            val addrStr = args.optString("address", "").trim()
            if (libName.isNotEmpty()) req.put("libName", libName)
            if (symName.isNotEmpty()) req.put("symbolName", symName)
            if (addrStr.isNotEmpty()) {
                val parsed = parseAddress(addrStr)
                    ?: return McpToolCallResult("Invalid hex address: $addrStr", isError = true)
                req.put("address", toHex(parsed))
            }
            req.put("mode", args.optString("mode", "hook").trim().ifEmpty { "hook" })
        }
        args.optJSONObject("arg_overrides")?.let { req.put("argOverrides", it) }
        args.optJSONObject("reg_overrides")?.let { req.put("regOverrides", it) }
        if (args.has("bypass_original")) req.put("bypassOriginal", args.optBoolean("bypass_original", false))
        if (args.has("return_override")) {
            if (args.isNull("return_override")) req.put("returnOverride", JSONObject.NULL)
            else req.put("returnOverride", args.opt("return_override"))
        }
        if (args.has("log_enabled")) req.put("logEnabled", args.optBoolean("log_enabled", true))

        val resp = synchronized(engineLock) { eng.hookCommand(req) }
        return McpToolCallResult(resp.toString(2), isError = !resp.optBoolean("ok", false))
    }

    private fun handleHookUninstall(args: JSONObject): McpToolCallResult {
        val eng = Session.engineForMcp()
        val all = args.optBoolean("all", false)
        val hookId = args.optInt("hook_id", 0)
        val req = if (all) {
            JSONObject().put("action", "uninstall_all")
        } else {
            if (hookId <= 0) return McpToolCallResult("Provide 'hook_id' or set 'all': true", isError = true)
            JSONObject().put("action", "uninstall").put("hookId", hookId)
        }
        val resp = synchronized(engineLock) { eng.hookCommand(req) }
        return McpToolCallResult(resp.toString(2), isError = !resp.optBoolean("ok", false))
    }

    private fun handleHookList(): McpToolCallResult {
        val eng = Session.engineForMcp()
        val resp = synchronized(engineLock) { eng.hookCommand(JSONObject().put("action", "list")) }
        return McpToolCallResult(resp.toString(2), isError = !resp.optBoolean("ok", false))
    }

    private fun handleHookGetLogs(args: JSONObject): McpToolCallResult {
        val eng = Session.engineForMcp()
        val req = JSONObject()
            .put("action", "logs")
            .put("hookId", args.optInt("hook_id", 0))
            .put("sinceSeq", args.optLong("since_seq", 0L).coerceAtLeast(0L))
            .put("limit", args.optInt("limit", 100).coerceIn(1, 512))
            .put("clear", args.optBoolean("clear", false))
        val resp = synchronized(engineLock) { eng.hookCommand(req) }
        return McpToolCallResult(resp.toString(2), isError = !resp.optBoolean("ok", false))
    }

    private fun handleHookCallFunction(args: JSONObject): McpToolCallResult {
        val eng = Session.engineForMcp()
        val req = JSONObject().put("action", "call")
        val hookId = args.optInt("hook_id", 0)
        val addrStr = args.optString("address", "").trim()
        val libName = args.optString("lib_name", "").trim()
        val symName = args.optString("symbol_name", "").trim()
        when {
            hookId > 0 -> req.put("hookId", hookId)
            addrStr.isNotEmpty() -> {
                val parsed = parseAddress(addrStr)
                    ?: return McpToolCallResult("Invalid hex address: $addrStr", isError = true)
                req.put("address", toHex(parsed))
            }
            libName.isNotEmpty() && symName.isNotEmpty() -> {
                req.put("libName", libName).put("symbolName", symName)
            }
            else -> return McpToolCallResult("Provide 'hook_id', 'address', or ('lib_name' + 'symbol_name')", isError = true)
        }
        req.put("args", args.optJSONArray("args") ?: JSONArray())
        req.put("returnType", args.optString("return_type", "int64").trim().ifEmpty { "int64" })
        val resp = synchronized(engineLock) { eng.hookCommand(req) }
        return McpToolCallResult(resp.toString(2), isError = !resp.optBoolean("ok", false))
    }

    private fun toolDef(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", schema)
    }

    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun itemToJson(item: MemoryItem): JSONObject = JSONObject().apply {
        put("address", toHex(item.address))
        put("type", ValueType.shortName(item.flags))
        put("raw_value", item.value)
        put("formatted_value", Formatter.primary(item, Formatter.DEFAULT_MASK))
        put("region", item.region)
    }

    private fun parseAddress(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            when {
                s.startsWith("0x", ignoreCase = true) -> java.lang.Long.parseUnsignedLong(s.substring(2), 16)
                s.endsWith("h", ignoreCase = true) -> java.lang.Long.parseUnsignedLong(s.dropLast(1), 16)
                s.any { it in 'a'..'f' || it in 'A'..'F' } -> java.lang.Long.parseUnsignedLong(s, 16)
                else -> java.lang.Long.parseUnsignedLong(s, 10)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseAddressList(raw: String): Set<Long> =
        raw.split(',', ';', ' ', '\n').mapNotNull { parseAddress(it) }.toSet()

    private fun parseValueType(raw: String): Int = when (raw.trim().lowercase(Locale.ROOT)) {
        "byte", "b", "int8" -> ValueType.BYTE
        "word", "w", "int16" -> ValueType.WORD
        "dword", "d", "int32" -> ValueType.DWORD
        "qword", "q", "int64" -> ValueType.QWORD
        "float", "f", "f32" -> ValueType.FLOAT
        "double", "e", "f64" -> ValueType.DOUBLE
        "xor", "x" -> ValueType.XOR
        "auto", "a" -> ValueType.AUTO
        else -> ValueType.DWORD
    }

    private fun parseSearchSign(raw: String): Int = when (raw.trim().lowercase(Locale.ROOT)) {
        "neq", "!=" -> ValueType.SIGN_NOT_EQUAL
        "gt", ">", "gte", ">=" -> ValueType.SIGN_GREATER_EQ
        "lt", "<", "lte", "<=" -> ValueType.SIGN_LESS_EQ
        else -> ValueType.SIGN_EQUAL
    }

    private fun parseRegionSet(raw: String): Set<String> {
        val s = raw.trim()
        if (s.isEmpty()) return Session.ui.value.selectedRegions.ifEmpty { RegionCatalog.DEFAULT_SELECTED }
        return s.split(',').mapNotNull { token ->
            val clean = token.trim()
            RegionCatalog.ALL.firstOrNull { it.code.equals(clean, ignoreCase = true) }?.code
        }.toSet().ifEmpty { RegionCatalog.DEFAULT_SELECTED }
    }

    private fun parseTypedValueBits(raw: String, typeFlag: Int): Long? {
        val s = raw.trim()
        return try {
            when (typeFlag and 0x7F) {
                ValueType.FLOAT -> java.lang.Float.floatToRawIntBits(s.toFloat()).toLong() and 0xFFFFFFFFL
                ValueType.DOUBLE -> java.lang.Double.doubleToRawLongBits(s.toDouble())
                else -> {
                    if (s.startsWith("0x", ignoreCase = true)) java.lang.Long.parseUnsignedLong(s.substring(2), 16)
                    else s.toLong()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeTyped(bytes: ByteArray, typeStr: String): Any = when (typeStr) {
        "byte", "int8" -> bytes[0].toInt()
        "word", "int16" -> if (bytes.size >= 2) bytesToLittleEndianLong(bytes, 2).toShort().toInt() else 0
        "dword", "int32" -> if (bytes.size >= 4) bytesToLittleEndianLong(bytes, 4).toInt() else 0
        "qword", "int64" -> if (bytes.size >= 8) bytesToLittleEndianLong(bytes, 8) else 0L
        "float", "f32" -> if (bytes.size >= 4) java.lang.Float.intBitsToFloat(bytesToLittleEndianLong(bytes, 4).toInt()).toDouble() else 0.0
        "double", "f64" -> if (bytes.size >= 8) java.lang.Double.longBitsToDouble(bytesToLittleEndianLong(bytes, 8)) else 0.0
        "utf8" -> String(bytes, Charsets.UTF_8).substringBefore('\u0000')
        "utf16" -> String(bytes, Charsets.UTF_16LE).substringBefore('\u0000')
        else -> bytesToHex(bytes)
    }

    private fun bytesToLittleEndianLong(bytes: ByteArray, count: Int): Long {
        var v = 0L
        val n = minOf(count, bytes.size, 8)
        for (i in 0 until n) {
            v = v or ((bytes[i].toLong() and 0xFF) shl (i * 8))
        }
        return v
    }

    private fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789ABCDEF"[v ushr 4])
            append("0123456789ABCDEF"[v and 0x0F])
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("0x", "", ignoreCase = true).replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.length < 2 || clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun toHex(addr: Long): String =
        "0x" + java.lang.Long.toUnsignedString(addr, 16).uppercase(Locale.ROOT)
}
