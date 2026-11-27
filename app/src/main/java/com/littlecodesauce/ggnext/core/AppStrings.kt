package com.littlecodesauce.ggnext.core

import java.util.concurrent.atomic.AtomicReference

/**
 * 纯 Kotlin 线程安全双语核心（0 = 简体中文，1 = English）：
 * - 可在 Composable、非 Composable（Session Toast、NumberParser 报错）及 JVM 单元测试中直接调用；
 * - Compose 层通过 `LocalAppLang` 与 `I18n.currentLang` 驱动全界面实时热切换，无需重启。
 */
object AppStrings {

    const val LANG_ZH = "0"
    const val LANG_EN = "1"

    private val currentLangRef = AtomicReference(LANG_ZH)

    @Volatile
    var onLangChanged: ((String) -> Unit)? = null

    val currentLang: String
        get() = currentLangRef.get()

    val isEnglish: Boolean
        get() = currentLang == LANG_EN || currentLang == "2"

    fun setLang(lang: String) {
        val normalized = if (lang == LANG_EN || lang == "2") LANG_EN else LANG_ZH
        val prev = currentLangRef.getAndSet(normalized)
        if (prev != normalized) {
            onLangChanged?.invoke(normalized)
        }
    }

    fun tr(text: String, lang: String = currentLang): String {
        if (text.isEmpty()) return text
        return when (lang) {
            LANG_EN, "2" -> toEnglish(text)
            else -> text
        }
    }

    fun helpText(lang: String = currentLang): String =
        if (lang == LANG_EN || lang == "2") HELP_TEXT_EN else HELP_TEXT_ZH

    fun changelogText(lang: String = currentLang): String =
        if (lang == LANG_EN || lang == "2") CHANGELOG_TEXT_EN else CHANGELOG_TEXT_ZH

    private const val HELP_TEXT_ZH = """• 精确与高级搜索：支持数字、范围 100~200、反向范围 100~~200、文本 'abc' / "abc"、HEX 数值（1234h / h4142）、联合搜索 1;2;3:512、有序联合 1;2::64、异或 100X7 / 100X4。
• 模糊搜索：先采样初始值，再按「有变化 / 无变化 / 增加了 / 减少了 / 差值 / 第 N 个结果」逐步缩小范围。
• 搜索中可随时点击进度条右侧「取消」按钮中断底层 C++20 扫描线程。
• 列表操作：点击地址行快捷编辑，长按行或点击工具栏按钮可批量修改、批量更改类型、锁定冻结（正常/递增/递减/范围钳制）、计算偏移、指针搜索与内存浏览。
• 悬浮窗：在主页启动悬浮球，短按悬浮球展开面板（展开时自动隐藏悬浮球）。"""

    private const val HELP_TEXT_EN = """• Exact & Advanced Search: Supports single values, ranges (100~200), inverted ranges (100~~200), text ('abc' / "abc"), HEX (1234h / h4142), group search (1;2;3:512), ordered group (1;2::64), and XOR (100X7 / 100X4).
• Fuzzy Search: Take an initial unknown snapshot, then narrow results by Changed / Unchanged / Increased / Decreased / Delta / Only N-th result.
• Tap 'Cancel' on the search progress bar anytime to abort the native C++20 scan immediately.
• List Operations: Tap an item to edit, or use toolbar/context actions for batch edit, batch type change, freeze modes (Normal / Increment / Decrement / Range clamp), offset calculator, pointer search, and memory browser.
• Floating Overlay: Start the floating ball from Home; tap it to open the panel (auto-hides while panel is open)."""

    private const val CHANGELOG_TEXT_ZH = """GGNext v0.10.0 更新日志：
1. 深度修复 Lua 5.5.1 脚本兼容性（支持循环变量改写、保留浮点子类型精度、64 位无符号地址无损过滤与 gg.getResultCount 别名）。
2. 基于内置 ShadowHook 端到端实现 gg.* Hook 与跳板主动调用 API、硬件断点与 ELF/.so 解析 API，并同步在 MCP 服务新增 5 个 Hook 工具。
3. 优化脚本悬浮控制（Sx 与纯图标停止按钮精简尺寸且支持自由拖拽）、重构高性能可筛选/可复制/可导出脚本日志面板。
4. 修复断点展开状态持久化与单条结果默认折叠层级；新增内存页非破坏性多级指针路径栏与主页「文档」API 手册页。"""

    private const val CHANGELOG_TEXT_EN = """GGNext v0.10.0 Changelog:
1. Deep Lua 5.5.1 script compatibility fixes (mutable for-loop variables, float subtype preservation, lossless uint64 address bounds, and gg.getResultCount alias).
2. Added end-to-end ShadowHook gg.* Hook & trampoline active-call APIs, hardware breakpoint & ELF/.so APIs, plus 5 matching MCP Hook tools.
3. Refined floating script controls (compact draggable Sx and icon-only Stop buttons) and overhauled the script log console with LazyColumn, filters, long-press copy, and .txt export.
4. Persisted breakpoint expansion state and unified single-hit default hierarchy; added non-destructive multi-level pointer path bar in Memory Editor and the new Home API Docs tab."""

    private val enMap: Map<String, String> = mapOf(
        "主页" to "Home",
        "点击顶部按钮控制显示/过滤，点击条目将地址填入输入框" to "Toggle top buttons to filter/show fields; tap an item to fill address",
        "点击顶部按钮控制显示/过滤，点击条目跳转定位" to "Toggle top buttons to filter/show fields; tap an item to jump",
        "正在加载内存模块..." to "Loading memory modules...",
        "未找到符合条件的内存模块" to "No matching memory modules found",
        "支持单值、范围、联合、字符串 (: UTF-8 / ; UTF-16) 与汇编 (~A8 / ~A / ~T)" to "Supports single, range, group, string (: UTF-8 / ; UTF-16) & ASM (~A8 / ~A / ~T)",
        "请输入汇编指令，如" to "Enter assembly instruction, e.g.",
        "机器码" to "Machine code",
        "解析失败" to "Parse failed",
        "常规配置" to "General Settings",
        "悬浮窗图标、自动暂停、冻结间隔与断点后端" to "Floating icon, auto-pause, freeze interval & debugger backend",
        "悬浮窗与进程联动" to "Overlay & Process",
        "悬浮窗图标显示当前进程" to "Show current process icon on floating ball",
        "开启后，悬浮球图标将自动显示当前附加目标进程的应用图标" to "Automatically display the attached target app icon on the floating ball",
        "打开面板时自动暂停游戏" to "Auto-pause game when panel opens",
        "展开修改器面板时自动发送 SIGSTOP 暂停目标进程，关闭面板后自动恢复" to "Send SIGSTOP when opening the panel and resume on close",
        "关闭可进一步降低低配设备界面渲染开销" to "Disable to reduce rendering overhead on lower-end devices",
        "引擎与调试偏好" to "Engine & Debugging",
        "冻结刷新间隔" to "Freeze refresh interval",
        "硬件断点默认后端" to "Default breakpoint backend",
        "Perf 低侵入无停顿（需 Root），Ptrace 支持完整浮点寄存器与免 Root" to "Perf is low-overhead (Root); Ptrace supports full FPRs & No-Root",
        "清空搜索历史与转到地址历史记录" to "Clear search and go-to-address history",
        "断点列表" to "Breakpoints",
        "实时寄存器终端" to "Live Register Console",
        "实时打印寄存器" to "Live register trace",
        "实时监听中" to "Live Tracing",
        "全部断点" to "All BPs",
        "全部寄存器" to "All Regs",
        "常用" to "Common",
        "筛选寄存器（逗号分隔，如 X0,X1,X8,X9,LR）..." to "Filter registers (comma-separated, e.g. X0,X1,X8,X9,LR)...",
        "等待硬件断点触发… 命中后将按时间顺序实时打印寄存器快照（变化值自动高亮）" to "Waiting for hardware breakpoint hits… Register snapshots will stream here in real time (changed values highlighted)",
        "以联合方式复制" to "Copy as Group Search",
        "为" to "Select copy format for",
        "个已选项选择复制格式。" to "selected item(s).",
        "十六进制格式" to "Hexadecimal",
        "反向十六进制格式" to "Reverse hexadecimal",
        "添加类型" to "Add types",
        "添加组列大小" to "Add group size",
        "内存选择器" to "Memory selector",
        "输入十六进制地址或 .so 模块名（支持 +偏移）定位内存" to "Enter hex address or .so module name (supports +offset)",
        "地址或模块名 (如 libil2cpp.so+0x10)" to "Address or module (e.g. libil2cpp.so+0x10)",
        "筛选 .so 模块名..." to "Filter .so module name...",
        "正在解析进程 .so 模块..." to "Loading .so modules...",
        "未找到匹配的 .so 模块" to "No matching .so modules found",
        "正在解析区域模块..." to "Loading region segments...",
        "该区域暂无可用内存段" to "No memory segments in this region",
        "点击顶部按钮控制显示字段，点击条目直接跳转" to "Toggle top buttons to show/hide fields; tap an item to jump",
        "搜索模块名称、权限或路径..." to "Search module name, perms, or path...",
        "搜索列表显示格式" to "Search list display format",
        "内存页显示格式" to "Memory view display format",
        "ARM (x32) 操作码" to "ARM (x32) opcode",
        "Thumb 操作码" to "Thumb opcode",
        "ARM (x64) 操作码" to "ARM (x64) opcode",
        "h: 十六进制格式 (little-endian)" to "h: Hexadecimal (little-endian)",
        "r: 反向十六进制格式 (big-endian)" to "r: Reverse hexadecimal (big-endian)",
        "S: 字符串表达式" to "S: String representation",
        "J: UTF-16LE" to "J: UTF-16LE",
        "A: ARM (x32) 操作码" to "A: ARM (x32) opcode",
        "T: Thumb 操作码" to "T: Thumb opcode",
        "A8: ARM (x64) 操作码" to "A8: ARM (x64) opcode",
        "当前：" to "Current: ",
        "设置" to "Settings",
        "菜单" to "Menu",
        "软件设置" to "App Settings",
        "启动" to "Start",
        "已启动" to "Running",
        "未启动" to "Stopped",
        "启动悬浮窗" to "Start Overlay",
        "停止悬浮窗" to "Stop Overlay",
        "停止服务" to "Stop Service",
        "展开面板" to "Open Panel",
        "展开修改器" to "Open Modifier",
        "Android 内存调试工具" to "Android Memory Debugger",
        "悬浮窗运行中" to "Overlay Active",
        "悬浮窗未启动" to "Overlay Inactive",
        "短按悬浮球打开调试面板，长按悬浮球调节游戏速率" to "Tap the floating ball to open the panel, hold to adjust speed",
        "短按悬浮球打开调试面板" to "Tap the floating ball to open the debugging panel",
        "启动后将在屏幕显示可拖拽悬浮球，点击即可附加进程并检索内存" to "Shows a draggable floating ball on screen to attach processes and inspect memory",
        "运行环境" to "Environment",
        "运行方式" to "Operation Mode",
        "已允许在其他应用上层显示" to "Display over other apps allowed",
        "尚未授予系统悬浮窗权限" to "Overlay permission not granted yet",
        "已授权" to "Granted",
        "未授权" to "Unauthorized",
        "共享 UID" to "Shared UID",
        "Root" to "Root",
        "免 Root" to "No Root",
        "Root 模式" to "Root Mode",
        "免 Root 模式" to "No-Root Mode",
        "通过 Root 权限进行全系统跨进程读写（支持所有已运行应用与游戏）" to "System-wide cross-process memory I/O via Root access",
        "针对虚拟空间/双开沙箱（所有应用共享相同 UID），无需 Root 跨进程读写" to "For virtual spaces / shared-UID sandboxes without Root",
        "Root 权限已获取，支持全系统进程读写" to "Root access ready for system-wide process memory I/O",
        "未检测到 Root 授权，点击检测或切换至免 Root" to "Root access not detected, tap to recheck or switch to No-Root",
        "适用虚拟空间及共享 UID 环境，无需 Root 权限" to "For virtual spaces and shared UID environments without Root",
        "主题配色" to "Theme Color",
        "界面语言" to "Language",
        "语言" to "Language",
        "恢复默认设置" to "Reset Default Settings",

        // MCP Service & Battery Optimization
        "MCP 服务" to "MCP Server",
        "本地 MCP 接口与后台保活配置" to "Local MCP endpoint & background keep-alive",
        "服务控制" to "Service Control",
        "启用 MCP 服务" to "Enable MCP Server",
        "开启本地 MCP 接口，供外部智能体调用内存与调试能力" to "Expose local MCP tools for external AI agents",
        "MCP 服务已启动" to "MCP Server started",
        "MCP 服务已停止" to "MCP Server stopped",
        "启动失败" to "Start failed",
        "请求" to "Requests",
        "Streamable HTTP 地址" to "Streamable HTTP Endpoint",
        "SSE 兼容地址" to "SSE Compatible Endpoint",
        "复制客户端配置 JSON" to "Copy Client Config JSON",
        "一键复制 mcpServers 标准接入配置" to "Copy standard mcpServers client config",
        "已复制 MCP 客户端配置" to "Copied MCP client config",
        "后台保活与权限" to "Background Keep-Alive & Permissions",
        "忽略电池优化" to "Ignore Battery Optimizations",
        "已允许后台无限制运行，切换应用或锁屏时连接更稳定" to "Unrestricted background execution enabled for stable connections",
        "建议授予此权限，防止切后台后 MCP 服务被系统休眠中断" to "Recommended to prevent the system from suspending MCP in background",
        "已开启" to "Enabled",
        "未开启" to "Off",
        "去申请" to "Grant",
        "无法打开系统电池优化页面" to "Unable to open battery optimization settings",
        "网络与鉴权" to "Network & Authentication",
        "监听端口 (1024–65535)" to "Listen Port (1024–65535)",
        "已保存端口" to "Saved port",
        "允许局域网访问" to "Allow LAN Access",
        "监听 0.0.0.0（同一 Wi-Fi 下电脑可直接连接）" to "Listen on 0.0.0.0 (accessible from devices on the same Wi-Fi)",
        "仅限本机 127.0.0.1 访问（推荐配合 ADB 端口转发）" to "Localhost 127.0.0.1 only (recommended with ADB port forwarding)",
        "启用令牌鉴权" to "Enable Token Auth",
        "开启后请求需携带 Bearer Token，局域网访问建议开启" to "Require Bearer Token on requests (recommended for LAN access)",
        "已复制访问令牌" to "Copied access token",
        "重置令牌" to "Reset Token",
        "已重置访问令牌" to "Access token regenerated",
        "已提供工具" to "Available Tools",

        "工具列表" to "Tool List",
        "MCP 工具列表" to "MCP Tools",
        "查看全部已注册 MCP 工具与参数说明" to "View all registered MCP tools and parameter schemas",
        "点击任意工具展开英文提示词与参数详情" to "Tap any tool to inspect its English prompt and parameters",
        "参数列表" to "Parameters",
        "无需参数" to "No parameters required",
        "必填" to "Required",
        "可选" to "Optional",
        "最近调用记录" to "Recent Calls",
        "系统" to "System",
        "进程" to "Process",
        "搜索" to "Search",
        "汇编" to "ASM",
        "修改" to "Edit",
        "获取工作模式、目标进程、搜索与汇编引擎状态" to "Get work mode, attached target, search & ASM status",
        "枚举运行中进程，支持关键词过滤" to "List running processes with keyword filter",
        "按 PID 或包名附加目标进程" to "Attach target process by PID or package name",
        "列出目标进程内存映射区域 (/proc/pid/maps)" to "List target process memory regions (/proc/pid/maps)",
        "按地址读取原始字节及指定数据类型数值" to "Read raw bytes and typed values at address",
        "按地址写入原始字节或指定类型数值" to "Write raw bytes or typed values to address",
        "查看内存窗口（地址/HEX/Dword/Float/指针/可选反汇编）" to "Inspect memory window with hex, values, pointers & optional disasm",
        "执行精确、范围、联合、异或与 HEX 新搜索或改善" to "Run exact, range, group, XOR & HEX search or refine",
        "执行未知初始快照与模糊变化改善搜索" to "Run unknown initial snapshot & fuzzy delta refinement",
        "分页拉取当前搜索结果并刷新实时值" to "Paginate search results with live refreshed values",
        "清空当前搜索会话并释放缓存" to "Clear current search session and buffers",
        "将多架构汇编指令编译为机器码，可选直接写入地址" to "Assemble instructions for target arch and optionally write to memory",
        "反汇编指定内存地址或十六进制机器码" to "Disassemble target memory address or raw hex bytes",
        "在目标进程跨进程分配自定义 RWX 权限内存页" to "Allocate a custom RWX memory page in the target process",
        "在目标进程内复制指定长度的内存块" to "Copy a memory block within the target process",
        "将目标进程指定地址区间导出到文件" to "Dump a target memory range to a file",
        "锁定/解锁目标地址数值（支持精确/递增/递减/范围钳制）" to "Freeze or unfreeze an address value (exact/inc/dec/range)",
        "添加/查询/暂停/恢复/清除/移除硬件断点并查看命中寄存器" to "Add, list, pause, resume, clear or remove hardware breakpoints",
        "文档" to "Docs",
        "API 文档" to "API Docs",
        "GGNext 独有 Lua 扩展 API 手册（统一挂载于 gg.* 命名空间）" to "GGNext exclusive Lua extension API reference (all mounted under gg.*)",
        "搜索 API 名称、关键词或示例代码…" to "Search API name, keyword, or example…",
        "未找到匹配的 API 文档" to "No matching API documentation found",
        "函数签名" to "Signature",
        "参数说明" to "Parameters",
        "返回值" to "Returns",
        "Lua 调用示例" to "Lua Example",
        "复制示例" to "Copy Example",
        "指针链" to "Ptr Path",
        "移除此层" to "Remove layer",
        "清空路径" to "Clear path",
        "脚本按钮 Sx" to "Script Button Sx",
        "暂停自动滚底" to "Pause auto-scroll",
        "恢复自动滚底" to "Resume auto-scroll",
        "复制全部日志" to "Copy all logs",
        "导出日志 (.txt)" to "Export logs (.txt)",
        "清空日志" to "Clear logs",
        "筛选日志关键词（长按单条可复制）…" to "Filter log keyword (long-press entry to copy)…",
        "无匹配日志" to "No matching logs",
        "已复制日志条目" to "Log entry copied",
        "导出日志失败：" to "Export logs failed: ",
        "基于 ShadowHook 按符号或地址安装 Inline Hook / 指令级寄存器拦截" to "Install ShadowHook inline hook or instruction register intercept by symbol/address",
        "卸载指定 Hook ID 或全部已安装的 ShadowHook" to "Uninstall a specific Hook ID or all active ShadowHooks",
        "列出当前目标进程所有活动 ShadowHook 状态与命中统计" to "List all active ShadowHooks and hit counts in target process",
        "拉取或清空 ShadowHook 命中参数/寄存器环形日志" to "Fetch or clear ring-buffered ShadowHook hit argument/register logs",
        "通过 ShadowHook 跳板或函数地址安全主动调用目标函数" to "Safely invoke original trampoline or target function address",
        "重置主题、透明度、屏幕占比、字号与语言设置" to "Reset theme, opacity, scale, font size and language",
        "冻结间隔" to "Freeze Interval",
        "使用声明与免责协议" to "Terms of Use & Disclaimer",
        "请完整阅读以下条款后决定是否继续使用" to "Please read the terms carefully before continuing",
        "一、工具性质与适用范围" to "1. Purpose and Intended Scope",
        "GGNext 为开源本地内存分析与调试工具，仅适用于程序开发测试、故障排查、安全研究及经明确授权的本地环境实验。任何功能均按通用内存读写机制提供，不针对任何特定商业软件、游戏或网络服务。" to "GGNext is an open-source local memory analysis and debugging tool intended solely for development testing, troubleshooting, security research, and authorized local environments. Its features provide general memory read/write operations and do not target any specific commercial software, game, or online service.",
        "二、严禁非法及侵权用途" to "2. Prohibited Uses",
        "严禁将本软件用于制作或分发游戏外挂、篡改联机数据、破坏计算机信息系统、绕过付费与版权保护、窃取敏感信息或任何违反当地法律法规及第三方服务协议的行为。如您所在地区法律限制此类工具的使用，请立即停止使用并卸载。" to "You must not use this software to create or distribute cheats, alter online data, compromise computer systems, bypass billing or copyright protections, extract sensitive data, or violate applicable laws or third-party terms of service. Discontinue use and uninstall immediately if restricted in your jurisdiction.",
        "三、使用者独立责任" to "3. Sole User Responsibility",
        "因使用本软件而产生的一切技术后果、数据损坏、账号封禁、设备异常及法律责任，均由使用者本人独立承担。软件作者及代码贡献者无法控制任何第三方的具体操作方式，亦不参与、不教唆任何违规用途。" to "You assume full responsibility for all technical outcomes, data corruption, account penalties, device issues, and legal liabilities arising from your use of this software. The authors and contributors cannot control third-party usage and neither participate in nor endorse unlawful activity.",
        "四、开源协议与无担保声明" to "4. License and No Warranty",
        "本软件依据 GNU General Public License v3.0 协议以现状形式开源分发，不附带任何明示或暗示的适用性、可靠性或无侵权担保。在适用法律允许的最大范围内，作者与贡献者不对因使用或无法使用本软件造成的任何直接、间接或附带损失承担赔偿责任。" to "This software is distributed AS IS under the GNU General Public License v3.0 without warranties of any kind, express or implied, including fitness for a particular purpose or non-infringement. To the maximum extent permitted by law, authors and contributors are not liable for any direct, indirect, or consequential damages.",
        "五、确认与生效" to "5. Acceptance",
        "点击「同意并继续」即表示您已充分阅读、理解并自愿遵守上述全部条款；若您不同意任何内容，请点击「不同意并退出」并立即移除本软件。" to "Tapping Agree & Continue confirms that you have read, understood, and agree to these terms. If you disagree, tap Decline & Exit and remove the software.",
        "不同意并退出" to "Decline & Exit",
        "同意并继续" to "Agree & Continue",
        "开源内存分析与调试工具，遵循 GNU General Public License v3.0 协议发布。" to "Open-source memory analysis and debugging tool licensed under GNU General Public License v3.0.",
        "项目地址" to "Repository",
        "获取源码、版本发布与问题反馈" to "Source code, releases and issue tracker",
        "参考来源" to "Reference",
        "界面布局与交互设计灵感源自 AGameGuardian 优秀的界面设计。" to "Interface layout and interaction design drew inspiration from AGameGuardian's clean UI.",
        "点击打开或复制" to "Tap to open or copy",
        "当前目标" to "Current Target",
        "悬浮球已在屏幕显示" to "Floating ball is active on screen",
        "点击上方按钮启动悬浮球" to "Tap the button above to start floating ball",
        "悬浮球服务运行中 · 短按打开面板 / 长按调节变速" to "Overlay active · Tap to open panel",
        "新一代高性能内存调试工具" to "Next-gen high-performance memory debugger",
        "运行环境与权限" to "Environment & Permissions",
        "悬浮窗权限" to "Overlay Permission",
        "已授予系统悬浮窗权限，支持跨应用显示" to "System overlay permission granted",
        "点击前往系统设置授予悬浮窗权限" to "Tap to grant overlay permission in system settings",
        "已就绪" to "Ready",
        "去授权" to "Grant",
        "工作模式" to "Working Mode",
        "软件快捷入口" to "Quick Shortcuts",
        "主题与外观设置" to "Theme & Appearance",
        "语言 (Language)" to "Language",
        "使用帮助与语法指南" to "Help & Syntax Guide",
        "了解精确搜索、模糊搜索、联合搜索与批量修改用法" to "Learn exact, fuzzy, group search & batch editing",
        "关于软件" to "About App",
        "GGNext v0.10.0 · 查看软件信息与更新日志" to "GGNext v0.10.0 · App info & changelog",
        "管理软件语言、主题配色、背景透明度、页面屏幕占比与关于信息" to "Manage language, theme, opacity, screen ratio & about info",
        "语言与区域" to "Language & Region",
        "切换" to "Switch",
        "主题与外观" to "Theme & Appearance",
        "主题配色方案" to "Color Theme Preset",
        "深色模式" to "Dark Mode",
        "跟随系统" to "System",
        "深色" to "Dark",
        "浅色" to "Light",
        // 16 Theme Presets
        "玫瑰" to "Rose",
        "青翠" to "Green",
        "天青" to "Cyan",
        "蔚蓝" to "Blue",
        "紫罗兰" to "Violet",
        "琥珀" to "Amber",
        "赤红" to "Red",
        "中性" to "Mono",
        "珊瑚" to "Coral",
        "祖母绿" to "Emerald",
        "靛蓝" to "Indigo",
        "浆果" to "Berry",
        "松石" to "Teal",
        "暖沙" to "Oat",
        "青柠" to "Lime",
        "岩灰" to "Slate",
        "Android 12+ 动态壁纸取色" to "Android 12+ Dynamic Wallpaper Color",
        "动态取色" to "Dynamic Color",
        "Android 12+ 从壁纸提取配色" to "Extract palette from wallpaper on Android 12+",
        "需要 Android 12 及以上" to "Requires Android 12 or above",
        "背景透明度" to "Background Opacity",
        "页面屏幕占比" to "Screen Ratio",
        "界面字体缩放" to "Font Scale",
        "弹窗圆角半径" to "Dialog Corner Radius",
        "伪弹窗圆角" to "Dialog Corner Radius",
        "悬浮球尺寸" to "Floating Ball Size",
        "列表入场与骨架屏动画" to "List Enter & Skeleton Animation",
        "关于与支持" to "About & Support",
        "关于 GGNext" to "About GGNext",
        "版本 v0.10.0 · 架构与环境信息" to "Version v0.10.0 · Architecture & environment",
        "更新日志" to "Changelog",
        "查看 v0.10.0 版本优化与修复详情" to "View v0.10.0 optimizations & bug fixes",
        "使用帮助" to "User Guide",
        "搜索语法与功能说明" to "Search syntax & feature reference",
        "软件运行日志" to "Runtime Log",
        "查看内部状态与调试日志" to "View internal state & debug logs",
        "恢复软件默认设置" to "Reset App Settings",
        "重置主题、透明度、屏幕占比、字号、语言等软件偏好设置" to "Reset theme, opacity, screen ratio, font size & language",
        "通用设置" to "General Settings",
        "通用与界面设置" to "General & UI Settings",
        "内存设置" to "Memory Settings",
        "操作与诊断" to "Actions & Diagnostics",
        "自动暂停游戏" to "Autopause game",
        "冻结间隔 (毫秒)" to "Freeze interval (ms)",
        "进程选择" to "Select process",
        "未选择进程，点击选择" to "No process selected, tap to select",
        "未选择进程 (点击选择)" to "No process selected (tap to select)",
        "内存区域" to "Memory ranges",
        "未选择" to "None",
        "搜索结果过滤器" to "Search result filter",
        "数值显示格式" to "Value format",
        "变速倍率设置" to "Speedhack settings",
        "工具栏顺序与增删" to "Toolbar order & buttons",
        "调整搜索列表 / 保存列表 / 内存页工具栏图标" to "Customize toolbar icons for Search / Saved / Memory tabs",
        "外观与字体缩放" to "Appearance & font scale",
        "清除输入历史" to "Clear input history",
        "运行日志" to "Runtime log",
        "查看引擎与附加记录" to "View engine & attach logs",
        "帮助文档" to "Help documentation",
        "查看搜索语法与功能说明" to "View search syntax & feature guide",
        "关于" to "About",
        "搜索列表" to "Search",
        "保存列表" to "Saved",
        "内存编辑器" to "Memory",
        "内存" to "Memory",
        "未选择进程。\n点击进程栏选择目标。" to "No process selected.\nTap the process bar to select a target.",
        "打开搜索面板开始。" to "Open the search panel to start.",
        "保存列表为空。\n在搜索列表中勾选后按「保存」加入。" to "Saved list is empty.\nCheck items in Search list and tap Save.",
        "内存编辑器\n使用工具栏「转到地址」加载内存视图。" to "Memory Editor\nUse 'Go to address' on the toolbar to load memory.",
        "上一页" to "Prev",
        "下一页" to "Next",
        "定址" to "Goto",
        "刷新" to "Refresh",
        "精确搜索" to "Exact Search",
        "支持单值、范围 (A~B)、联合 (A;B:512)、异或 (AXK) 与 HEX" to "Supports single, range (A~B), group (A;B:512), XOR & HEX",
        "更多" to "More",
        "收起" to "Less",
        "键盘" to "Kbd",
        "= 等于" to "= Equal",
        "≠ 不等于" to "≠ Not equal",
        "≥ 大于或等于" to "≥ Greater or equal",
        "≤ 小于或等于" to "≤ Less or equal",
        "地址蒙版" to "Address mask",
        "搜索范围：在全部内存中" to "Range: All memory",
        "搜索范围：仅在附近内存中" to "Range: Nearby memory only",
        "加密值搜索" to "Encrypted value",
        "改善搜索" to "Refine",
        "新搜索" to "New search",
        "模糊搜索" to "Fuzzy Search",
        "选择要采样的数值类型，点击下方「搜索」按钮进行初始内存采样：" to "Select the value type to sample and tap 'Search' below:",
        "取消" to "Cancel",
        "搜索" to "Search",
        "值是否发生了改变?" to "How has the value changed?",
        "无变化" to "Unchanged",
        "有变化" to "Changed",
        "增加了" to "Increased",
        "减少了" to "Decreased",
        "只有第N个结果: " to "Only N-th result: ",
        "最大显示记录: " to "Max shown records: ",
        "地址:  ≥" to "Address: ≥",
        "地址:  ≤" to "Address: ≤",
        "数值:  ≥" to "Value: ≥",
        "数值:  ≤" to "Value: ≤",
        "类型: " to "Type: ",
        "指针: " to "Pointer: ",
        "点击选择" to "Tap to pick",
        "重置" to "Reset",
        "应用" to "Apply",
        "完成" to "Done",
        "确定" to "OK",
        "保存" to "Save",
        "返回" to "Back",
        "单项修改" to "Edit Value",
        "批量修改" to "Batch Edit",
        "输入修改值…" to "Enter value…",
        "备注" to "Note",
        "描述（可选）" to "Description (optional)",
        "冻结并保存" to "Freeze & save",
        "冻结模式" to "Freeze Mode",
        "正常冻结" to "Normal",
        "递增" to "Increment",
        "递减" to "Decrement",
        "范围钳制" to "Range",
        "范围下限" to "Min value",
        "范围上限" to "Max value",
        "加到值内" to "Add to value, do not replace",
        "可选增量步进值" to "Optional step increment",
        "值列表" to "Values",
        "修改" to "Apply",
        "正在写入..." to "Writing...",
        "选择数据类型" to "Select Data Type",
        "更改地址 / 类型" to "Change Address / Type",
        "批量更改类型" to "Batch Change Type",
        "地址" to "Address",
        "选择新类型" to "Select new type",
        "应用到全部选中项" to "Apply to all selected",
        "数值格式" to "Value Format",
        "已保存列表显示格式" to "Display format for saved list",
        "搜索列表与内存页显示格式" to "Display format for search list & memory",
        "默认" to "Default",
        "偏移量 & 异或计算" to "Offset & XOR Calculator",
        "长按任意计算出的偏移量、目标地址或异或值即可复制" to "Long-press any calculated offset, target address or XOR value to copy",
        "基址 (HEX)" to "Base (HEX)",
        "偏移 (HEX)" to "Offset (HEX)",
        "计算偏移:" to "Offset:",
        "目标地址:" to "Target:",
        "异或结果:" to "XOR:",
        "已勾选地址两两偏移 & 异或:" to "Checked addresses pairwise Offset & XOR:",
        "复制偏移" to "Copy Offset",
        "跳转到目标地址" to "Go to Target",
        "移除" to "Remove",
        "转到地址" to "Go to Address",
        "输入十六进制内存地址定位并高亮目标地址" to "Enter hex memory address to locate and highlight",
        "目标地址 (HEX)" to "Target (HEX)",
        "转到" to "Go to",
        "模块列表" to "Module List",
        "正在加载内存映射..." to "Loading memory maps...",
        "执行脚本" to "Execute Script",
        "运行本地明文脚本并管理脚本历史。" to "Run local plain-text scripts and manage script history.",
        "输入可读取的本地脚本路径。" to "Enter a readable local script path.",
        "最近执行的脚本:" to "Recent Scripts:",
        "浏览" to "Browse",
        "运行" to "Run",
        "执行" to "Execute",
        "停止" to "Stop",
        "停止脚本" to "Stop Script",
        "脚本运行中：" to "Running: ",
        "脚本执行出错" to "Script Error",
        "脚本控制台输出" to "Script Console Output",
        "脚本提示" to "Script Alert",
        "请选择" to "Select an Option",
        "多项选择" to "Select Options",
        "脚本参数输入" to "Script Prompt",
        "脚本面板" to "Script Panel",
        "脚本网络访问确认" to "Script Network Request",
        "当前脚本请求访问以下网络地址，是否允许？" to "The running script wants to access the following URL. Allow?",
        "允许" to "Allow",
        "拒绝" to "Deny",
        "请先选择或输入脚本路径" to "Please select or enter a script path first",
        "已有脚本正在运行，请先停止当前脚本" to "A script is already running; stop it first",
        "脚本引擎未加载" to "Script engine is not loaded",
        "初始化脚本引擎失败" to "Failed to initialize script engine",
        "脚本已停止" to "Script stopped",
        "浏览并选择文件夹" to "Select Folder",
        "文件浏览器" to "File Browser",
        "上一级" to "Up",
        "新建目录" to "New Folder",
        "根目录 /" to "Root /",
        "搜索文件名或后缀…" to "Search file name or ext…",
        "全部" to "All",
        "选择当前文件夹" to "Select This Folder",
        "复制内存" to "Copy Memory",
        "复制起始地址与结束地址之间区域的内存数据" to "Copy memory between start address and end address",
        "起始地址" to "From",
        "结束地址" to "To",
        "结束地址必须大于或等于起始地址" to "End address must be >= start address",
        "复制" to "Copy",
        "导出内存" to "Dump Memory",
        "将起始地址与结束地址之间的内存区域导出到文件" to "Export memory region between start and end address to file",
        "路径" to "Path",
        "导出" to "Dump",
        "分配内存页" to "Allocate Memory Page",
        "当前版本暂未实现跨进程内存分配" to "Cross-process memory page allocation (ptrace mmap) is not implemented in this version",
        "也许可读" to "May read",
        "也许可写" to "May write",
        "也许可执行" to "May execute",
        "地址:" to "Address:",
        "保存/载入列表文件" to "List File Manager",
        "从文件载入列表" to "Load List from File",
        "保存列表到文件" to "Save List to File",
        "导出选定项到文件" to "Export Selected to File",
        "载入" to "Load",
        "保存文件" to "Save File",
        "导出选中" to "Export Selected",
        "输入文件路径或点击右侧「浏览」从文件浏览器中选择" to "Enter file path or tap 'Browse' to pick from file browser",
        "游戏变速" to "Speedhack",
        "当前版本暂未实现底层变速 Hook，倍率仅作占位提示" to "Low-level clock speedhack hook is not implemented in this version; value is placeholder only",
        "选择语言 (Language)" to "Select Language",
        "输入历史" to "Input History",
        "无历史记录" to "No history",
        "清空" to "Clear",
        "暂无日志" to "No logs yet",
        "悬浮窗管理" to "Floating Window",
        "关闭悬浮窗" to "Stop Overlay",
        "进程列表" to "Process List",
        "选择要附加的进程" to "Select a process to attach",
        "搜索名称 / 包名 / pid" to "Search name / package / pid",
        "用户" to "User",
        "系统" to "System",
        "优先级" to "Order",
        "大小" to "Size",
        "选择内存范围" to "Select Memory Ranges",
        "未选择进程" to "No process selected",
        "工具栏顺序" to "Toolbar Order",
        "长按行上下拖动排序 / 下方可增删工具" to "Long-press and drag up/down to reorder / add or remove below",
        "增删工具" to "Add/Remove",
        "点击勾选或移除该页面的工具栏图标" to "Tap to enable or disable toolbar icons for this tab",
        "返回排序" to "Back to Order",
        "外观" to "Appearance",
        "主题配色、深浅色模式、背景透明度与屏幕占比" to "Theme color, dark/light mode, opacity & screen ratio",
        "主题色" to "Theme Color",
        "地址蒙版搜索" to "Address Mask Search",
        "支持使用 ? 通配符匹配特定偏移规律的内存地址" to "Match addresses by bitmask or '?' wildcards",
        "蒙版:" to "Mask:",
        "改善结果" to "Refine",
        "新蒙版搜索" to "New Mask Search",
        "指针搜索" to "Pointer Search",
        "查找指向目标地址及最大偏移范围内的指针链" to "Find pointers to target address within max offset",
        "最大偏移:" to "Max offset:",
        "开始指针搜索" to "Search Pointers",
        "偏移量计算器" to "Offset calculator",
        "计算偏移、异或" to "Calculate offset, XOR",
        "搜索附近内存" to "Search nearby memory",
        "附近" to "Nearby",
        "附近:" to "Nearby:",
        "之前" to "Before",
        "后" to "After",
        "距离:" to "Distance:",
        "偏移量:" to "Offset:",
        "结果:" to "Result:",
        "终止此进程" to "Kill Target Process",
        "退出" to "Exit GGNext",
        "全选" to "Select All",
        "粘贴" to "Paste",
        "剪切" to "Cut",
        "反选" to "Invert",
        "清空选择" to "Clear Selection",
        "删除" to "Delete",
        "编辑" to "Edit",
        "过滤" to "Filter",
        "脚本" to "Scripts",
        "文件" to "Files",
        "恢复" to "Revert",
        "导出选中" to "Export",
        "复制地址" to "Copy Address",
        "更改类型" to "Change Type",
        "历史" to "History",
        "搜索" to "Search",
        "模糊" to "Fuzzy",
        "蒙版" to "Mask",
        "指针" to "Pointer",
        "计算" to "Offset",
        "暂停" to "Pause",
        "后退" to "Back",
        "前进" to "Forward",
        "数值格式" to "Format",
        "内存区域" to "Regions",
        "菜单" to "More",
        "变速" to "Speed",
        "载入为结果" to "Load as Results",
        "保留有变化" to "Keep Changed",
        "历史记录条数限制" to "History Limit",
        "工具栏按钮" to "Toolbar Buttons",
        "悬浮窗大小" to "Floating Ball Size",
        "显示日志" to "Show Logs",
        "重置设置" to "Reset Settings",
        "帮助" to "Help",
        "操作" to "Actions",
        "其余选项" to "Other",
        "是" to "Yes",
        "否" to "No",
        "HEX 逆序" to "Reverse HEX",
        "字符串" to "String",
        "Java 字符串" to "Java String",
        // Toasts & Parser errors
        "已取消搜索" to "Search cancelled",
        "目标进程已退出或重启，请重新附加" to "Target process exited or restarted, please re-attach",
        "请先选择进程" to "Please select a process first",
        "请先附加目标进程" to "Please attach to a target process first",
        "请先成功附加目标进程（当前无内存读取权限）" to "Please attach to a target process first (no memory read permission)",
        "请输入搜索条件" to "Please enter a search query",
        "请输入要搜索的值" to "Please enter a value to search",
        "文本不能为空" to "Text cannot be empty",
        "数值为空" to "Value is empty",
        "范围只能有两个边界值" to "Range must have exactly two bounds",
        "区域大小必须在 2 - 65,536 之间" to "Group span must be between 2 and 65,536",
        "联合搜索至少需要两个数值" to "Group search requires at least 2 values",
        "联合搜索最多 64 个数值" to "Group search supports at most 64 values",
        "无项目被选中" to "No items selected",
        "没有可修改的项目" to "No items to edit",
        "请输入修改数值" to "Please enter a value",
        "修改值无效" to "Invalid edit value",
        "输入格式错误" to "Invalid input format",
        "已恢复默认设置" to "Default settings restored",
        "已保存内存范围设置" to "Memory range settings saved",
        "已应用筛选" to "Filter applied",
        "已重置筛选条件" to "Filter reset",
        "已清空历史" to "History cleared",
        "已刷新实时内存数据" to "Live memory values refreshed",
        "已切换至 Root 模式" to "Switched to Root mode",
        "已切换至 Root 模式，未检测到 Root 授权" to "Switched to Root mode (Root access not detected)",
        "已切换至免 Root 模式" to "Switched to No-Root mode",
        "Root 权限已获取" to "Root access granted",
        "未检测到 Root 权限，请在管理器中授权或切换到免 Root 模式" to "Root not detected; grant access or switch to No-Root mode",
        "请输入有效的文件路径" to "Please enter a valid file path",
        "文件不存在" to "File does not exist",
        "文件中没有可载入的有效条目" to "No valid entries found in file",
        "保存列表为空，无可保存项目" to "Saved list is empty",
        "结束地址必须大于起始地址" to "End address must be greater than start address",
        "字节数必须大于 0" to "Byte count must be greater than 0",
        "复制内存失败，请检查地址范围是否可读写" to "Copy memory failed; check readable/writable range",
        "固定工具栏至少需保留一项" to "Pinned toolbar must keep at least 1 item",
        "未选择目标进程" to "No target process selected",
        "修改器已退出" to "GGNext exited",
        "已启动悬浮球，短按打开面板" to "Floating ball started; tap to open panel",
        "已关闭悬浮窗" to "Floating overlay stopped",
        "未发现数值发生变化的项目" to "No changed items found",
        "请先勾选要载入为搜索结果的项目" to "Select items to load as search results first",
        "正在读取..." to "Reading...",
        "调试" to "Debug",
        "调试功能将在后续版本中提供" to "Debugging features will be available in a future update",
        "扩展" to "Extensions",
        "扩展插件" to "Extensions",
        "扩展插件系统将在后续版本中提供" to "Extension plugin system will be available in a future update",
        "工具" to "Tools",
        "悬浮窗已联动" to "Overlay Linked",
        "未选择目标进程" to "No Target Process Selected",
        "点击选择目标进程，所有工具将与目标进程及悬浮窗实时联动" to "Tap to select a process; all tools link with the target process and floating overlay in real time",
        "切换目标进程" to "Switch Target Process",
        "选择目标进程" to "Select Target Process",
        "打开悬浮窗面板" to "Open Floating Panel",
        "数值与地址计算" to "Value & Address Calculators",
        "多进制与全类型转换器" to "Multi-Base & Type Converter",
        "HEX / RHEX / Dword / Qword / Float / Double / 字符串实时互转，一键送入搜索" to "Live conversion across HEX / RHEX / Dword / Qword / Float / Double / Text with 1-tap Send to Search",
        "地址偏移与异或指针计算器" to "Address Offset & XOR Pointer Calculator",
        "地址偏移与异或计算器" to "Address Offset & XOR Calculator",
        "多级基址偏移加减、异或编解码、实时读取目标进程内存并跳转内存页" to "Multi-level offset chain arithmetic, XOR codec, live target memory preview, and Memory Editor jump",
        "联合搜索与特征码构造器" to "Group Search & Signature Builder",
        "可视化拼接有序/无序联合搜索、范围与通配符表达式，直接同步至修改器" to "Visually compose ordered/unordered group searches, ranges, and wildcards synced to the modifier",
        "汇编与进程分析" to "Assembly & Process Analysis",
        "汇编与机器码工作台" to "Assembly & Opcode Workbench",
        "ARM64 / ARM / Thumb / x86 / x86_64 指令双向转换与常用逆向补丁速查" to "Bi-directional ARM64 / ARM / Thumb / x86 / x86_64 assembly & common patch templates",
        "进程模块与内存段分析器" to "Process Module & Maps Inspector",
        "浏览目标进程 /proc/pid/maps 模块基址、ELF 权限段并快速定位" to "Inspect target /proc/pid/maps module bases, ELF permissions, and jump to memory",
        "悬浮窗联动工具" to "Floating Overlay Linked Tools",
        "指针链搜索器" to "Pointer Chain Scanner",
        "在悬浮窗中打开目标地址指针扫描面板" to "Open target address pointer scanner in the floating overlay",
        "地址掩码搜索" to "Address Mask Search",
        "按地址位掩码 (Mask) 过滤并检索目标进程内存" to "Search target process memory filtered by address bitmask",
        "内存区域转储 (Memory Dump)" to "Memory Region Dump",
        "将目标进程指定地址区间或模块导出为本地二进制文件" to "Dump target process memory range or module to local binary files",
        "远程内存页分配" to "Remote Memory Page Allocation",
        "在目标进程空间分配可读/可写/可执行 (RWX) 内存页" to "Allocate readable/writable/executable (RWX) memory pages in the target process",
        "联动展开修改器调试面板，配置 Perf / Ptrace 硬件断点与寄存器监控" to "Open the modifier debugger tab to configure Perf / Ptrace hardware breakpoints and register watch",
        "开源引擎与资源" to "Open-Source Engines & Resources",
        "Keystone / Capstone 开源汇编引擎" to "Keystone / Capstone Open-Source Assembly Engine",
        "可从开源仓库获取预编译包并导入，提供多架构指令实时汇编与反汇编" to "Import prebuilt packs from open-source repositories for multi-arch live assembly and disassembly",
        "Keystone Engine 官方开源仓库" to "Keystone Engine Official Repository",
        "Capstone Engine 官方开源仓库" to "Capstone Engine Official Repository",
        "JetBrains Mono 开源等宽编程字体" to "JetBrains Mono Open-Source Monospace Font",
        "Frida 动态插桩分析工具集" to "Frida Dynamic Instrumentation Toolkit",
        "Il2CppDumper 符号与结构体还原工具" to "Il2CppDumper Symbol & Metadata Recovery Tool",
        "毛玻璃效果" to "Frosted Glass Effect",
        "所有页面、卡片与悬浮窗实时毛玻璃折射" to "Real-time frosted glass refraction across all pages, cards, and overlay",
        "为所有主页、工具页、弹窗与悬浮窗开启毛玻璃折射与系统级背景模糊" to "Enable frosted glass refraction and system background blur across all pages, tools, dialogs, and overlay",
        "毛玻璃强度" to "Frosted Blur Intensity",
        "为所有页面背景、卡片边缘与悬浮球渲染动态环境光与镜面高光" to "Render dynamic ambient glow and specular rim highlights across pages, cards, and floating ball",
        "光源随时间平滑律动并跨主页与悬浮窗同步" to "Smoothly animate light sources in real time across Home and Floating Overlay",
        "环境光源与镜面高光随时间平滑律动，跨主页与悬浮窗实时同步" to "Smoothly animate ambient light and specular highlights in real time across Home and Overlay",
        "悬浮球显示目标进程图标" to "Show Target Process Icon on Floating Ball",
        "附加进程后悬浮球自动切换为目标应用图标" to "Automatically show the attached target app icon on the floating ball",
        "选择目标进程后，悬浮球自动切换展示该进程的应用图标" to "Automatically display the selected target process icon on the floating ball",
        "已启用全局毛玻璃效果" to "Global frosted glass enabled",
        "已关闭全局毛玻璃效果" to "Global frosted glass disabled",
        "当前进程" to "Current Target",
        "选择要附加的目标进程（显示应用图标与内存占用）" to "Select target process to attach (shows app icon & memory usage)",
        "常规" to "General",
        "主题" to "Theme",
        "字体" to "Font",
        "系统默认" to "System Default",
        "自定义字体" to "Custom Font",
        "导入 TTF" to "Import TTF",
        "恢复默认" to "Reset",
        "选择 TTF 字体文件" to "Select TTF Font File",
        "默认使用系统字体，可导入本地 .ttf 字体文件并同步至主页与悬浮窗" to "Uses system font by default. Import a local .ttf font to sync across Home and Overlay.",
        "已恢复系统默认字体" to "Restored system default font",
        "字体导入失败：仅支持有效的 .ttf / .otf / .ttc 字体文件" to "Font import failed: only valid .ttf / .otf / .ttc font files are supported",
        "字体文件无效或已损坏" to "Invalid or corrupted font file",
        "字体文件不存在" to "Font file does not exist",
        "改善" to "Refine",
        "外观与主题" to "Appearance & Theme",
        "主题配色、字体、深浅色模式、背景透明度与屏幕占比" to "Theme color, font, dark/light mode, opacity & screen ratio",
        "悬浮窗服务" to "Overlay Service",
        "在屏幕显示可拖拽悬浮球" to "Display a draggable floating ball on screen",
        "短按悬浮球打开面板，长按调节变速" to "Tap the floating ball to open panel, hold for speed",
        "GGNext v0.10.0 · 点击展开修改器面板" to "GGNext v0.10.0 · Tap to open modifier panel",
        "GGNext v0.10.0 · 点击启动悬浮球服务" to "GGNext v0.10.0 · Tap to start overlay service",
        "Root · 已授权" to "Root · Granted",
        "Root · 未授权，点击检测" to "Root · Not granted, tap to check",
        "免 Root · 共享 UID" to "No Root · Shared UID",
        "版本 v0.10.0、项目地址与开源许可" to "Version v0.10.0, repository & open-source license",
        "重置主题、字体、透明度、屏幕占比与语言设置" to "Reset theme, font, opacity, screen ratio & language",
        "权限与环境" to "Permissions & Environment",
        "所有文件访问权限" to "All Files Access",
        "文件浏览器与脚本路径可访问完整外部存储" to "Full external storage access for file browser & scripts",
        "授予后可在文件浏览器中完整查看所有文件" to "Grant to view all files in the built-in file browser",
        "已授予所有文件访问权限" to "All files access permission granted",
        "Root 权限检测" to "Root Access Check",
        "已获取超级用户权限，可直接读写进程内存" to "Superuser access granted for direct memory read/write",
        "未检测到 Root 授权，点击重新检测" to "Root access not detected, tap to check again",
        "检测" to "Check",
        "运行状态" to "Runtime Status",
        "当前目标进程" to "Current Target Process",
        "未附加" to "Not Attached",
        "已启用内存区域" to "Enabled Memory Regions",
        "GGNext v0.10.0 · 点击启动悬浮球" to "GGNext v0.10.0 · Tap to start floating ball",
        "停止" to "Stop",
        "启动" to "Start",
        "支持与诊断" to "Support & Diagnostics",
        "搜索语法、联合搜索与功能说明" to "Search syntax, group search & feature guide",
        "关于" to "About",
        "特别鸣谢" to "Special Thanks",
        "致谢启发本项目设计的优秀工具" to "Credits to projects that inspired GGNext",
        "版本 v0.10.0、官方群组与开源许可" to "Version v0.10.0, official group & license",
        "色彩与深浅模式" to "Colors & Dark Mode",
        "字体管理" to "Font Manager",
        "已管理字体" to "Managed Fonts",
        "导入字体" to "Import Font",
        "导入本地 .ttf / .otf 字体后可随时切换或删除，同步生效于主页与悬浮窗" to "Import local .ttf / .otf fonts to switch or delete anytime across Home & Overlay",
        "不额外加载外部字体文件" to "Uses system font without loading external files",
        "删除字体" to "Delete Font",
        "悬浮窗与界面尺寸" to "Overlay & Layout Sizing",
        "暂无日志可复制" to "No logs to copy",
        "已复制运行日志" to "Logs copied",
        "GGNext 的设计与成长离不开以下优秀项目与先驱工具的启发，谨致以诚挚谢意。" to "GGNext's design and growth were inspired by the following outstanding projects and pioneers.",
        "界面与交互灵感" to "UI & Interaction Inspiration",
        "GGNext 的整体视觉层次、布局排布与多项交互细节，参考并致敬了 AGameGuardian 优秀的界面设计理念。" to "GGNext's visual hierarchy, layout structure, and interaction details draw inspiration from AGameGuardian's excellent interface design.",
        "经典内存分析工具先驱" to "Classic Memory Analysis Pioneer",
        "致敬 Android 平台经典的内存分析与修改工具 GameGuardian。其开创性的搜索语法、联合搜索范式与内存浏览体系，为本项目奠定了重要参考与灵感。" to "A tribute to GameGuardian, the classic Android memory analysis tool whose pioneering search syntax, group search paradigm, and memory viewer inspired this project.",
        "点击卡片即可前往对应项目主页" to "Tap any card to visit its homepage",
        "无法打开浏览器，已复制链接" to "Could not open browser; link copied",
        "无法直接打开浏览器，已复制链接" to "Could not open browser; link copied",
        "开源 Android 内存分析与调试工具，采用 Jetpack Compose 与 C++20 原生引擎构建，遵循 GNU General Public License v3.0 协议发布。" to "Open-source Android memory analysis and debugging tool built with Jetpack Compose and a native C++20 engine under GNU GPL v3.0.",
        "社区与项目" to "Community & Project",
        "官方群组" to "Official Group",
        "QQ 群" to "QQ Group",
        "交流反馈与更新动态" to "Feedback, discussion & release updates",
        "加入" to "Join",
        "开源仓库" to "Open Source Repository",
        "GGNext GitHub · 查看源码、提交 Issue 与获取更新" to "GGNext GitHub · Source code, issues & releases",
        "查看源代码、提交 Issue 与获取版本发布" to "View source code, submit issues & get releases",
        "加入交流反馈、获取更新动态与使用技巧" to "Join for feedback, updates & usage tips",
        "点击加入或复制" to "Tap to join or copy",
        "点击前往" to "Open",
        "点击访问" to "Visit",
        "开源协议" to "License",
        "当前环境" to "Current Environment",
        "当前字体" to "Current Font",
        "返回上一级" to "Parent directory",
        "文件夹" to "Folder",
        "正在读取目录…" to "Loading directory...",
        "未找到匹配的文件或文件夹" to "No matching files or folders",
        "当前目录为空或无访问权限" to "Directory is empty or inaccessible",
        "显示隐藏文件" to "Show hidden files",
        "刷新目录" to "Refresh directory",
        "未授予所有文件访问权限，可能无法显示部分文件 · 点击授权" to "All files access not granted; some files may be hidden · Tap to grant",
        "按顺序" to "Ordered",
        "群组大小" to "Group Size",
        "群组距离" to "Group Distance",
        "设置联合搜索最大跨度（默认 512）" to "Set max group search span (default 512)",
        // Complete English localization audit additions
        "+固定" to "+Pin",
        "移出" to "Unpin",
        "固定工具栏" to "Pinned Toolbar",
        "流动工具栏" to "Flowing Toolbar",
        "个工具" to "tools",
        "长按上下拖动排序" to "Long-press & drag to reorder",
        "长按按钮上下拖动排序，支持将常用工具固定到右侧常驻栏" to "Long-press to drag and reorder; pin frequent tools to the right bar",
        "调整搜索 / 保存 / 内存 / 调试页工具栏图标" to "Customize Search / Saved / Memory / Debug toolbar icons",
        "断点调试后端" to "Breakpoint Backend",
        "Perf 硬件断点" to "Perf Hardware Breakpoint",
        "Ptrace 断点" to "Ptrace Breakpoint",
        "Perf 硬件断点 · 点击切换为 Ptrace" to "Perf Hardware Breakpoint · Tap to switch to Ptrace",
        "Ptrace 断点 · 点击切换为 Perf" to "Ptrace Breakpoint · Tap to switch to Perf",
        "基于内核 perf_event_open，低侵入无停顿（需 Root 与内核支持）" to "Kernel perf_event_open, low overhead without stops (requires Root)",
        "基于 ptrace 硬件调试寄存器，支持完整浮点寄存器与免 Root 同 UID 模式" to "Ptrace debug registers, full FP registers & No-Root shared UID",
        "添加断点" to "Add Breakpoint",
        "暂停/恢复断点" to "Pause/Resume All",
        "清空命中" to "Clear Hits",
        "删除断点" to "Delete Breakpoint",
        "命中" to "Hits",
        "修改寄存器" to "Edit Register",
        "点击修改" to "Tap to edit",
        "写入" to "Write",
        "复制指令信息" to "Copy instruction info",
        "转到 PC 地址" to "Go to PC address",
        "已复制命中指令" to "Copied hit instruction",
        "输入格式无效，请检查数值" to "Invalid input format",
        "寄存器写回失败" to "Failed to write register",
        "正在监听中，等待目标进程访问此地址…" to "Listening, waiting for target process access…",
        "当前断点模式未采集浮点/向量寄存器（切换到 Ptrace 模式可采集完整 V0–V31 寄存器）" to "FP/SIMD registers not captured in current mode (switch to Ptrace in Settings for V0–V31)",
        "硬件断点调试" to "Hardware Breakpoint Debugger",
        "支持硬件断点监听，命中后自动采集寄存器与调用链回溯" to "Supports hardware breakpoints with register & backtrace capture on hit",
        "请先附加目标进程，然后从内存/搜索列表长按地址或点击上方 + 添加硬件断点" to "Attach a target process first, then long-press an address or tap + above",
        "未附加目标进程" to "No process attached",
        "当前未附加目标进程" to "No target process attached",
        "当前未选择目标进程。" to "No target process selected.",
        "当前没有可删除的断点" to "No breakpoints to delete",
        "添加断点失败" to "Failed to add breakpoint",
        "已暂停全部断点" to "All breakpoints paused",
        "已恢复全部断点" to "All breakpoints resumed",
        "已清空断点命中记录" to "Cleared breakpoint hits",
        "输入十六进制地址…" to "Enter hex address…",
        "例如：MOV X0, #1; RET" to "e.g. MOV X0, #1; RET",
        "汇编指令（支持分号 ; 或换行分隔多条）" to "Assembly instructions (separate with ; or newline)",
        "汇编失败" to "Assembly failed",
        "请输入汇编指令" to "Please enter assembly instructions",
        "未生成有效机器码" to "No valid machine code generated",
        "写入目标地址内存失败（请确认权限或内存页可写）" to "Memory write failed (check permissions or writable page)",
        "未检测到 Keystone 汇编引擎：请先返回软件主页导入 ggnext-asm-engine-pack.zip 压缩包。" to "Keystone engine not loaded: import the assembly engine pack on Home first.",
        "输入指令后自动生成机器码预览；亦可在编辑框中使用 ~A8 / ~A / ~T / ~X86 / ~X64 前缀直接写入" to "Machine code preview updates automatically; also supports ~A8 / ~A / ~T / ~X86 / ~X64 prefixes in editor",
        "独立外置压缩包（arm / arm64 / x86 / x86_64），导入后启用完整汇编与反汇编" to "External engine pack (arm / arm64 / x86 / x86_64) for full assemble & disassemble",
        "导入汇编引擎包失败" to "Failed to import assembly engine pack",
        "已移除外置汇编引擎包" to "Removed assembly engine pack",
        "Android Inline Hook 框架" to "Android Inline Hook Framework",
        "感谢字节跳动开源的 Android inline hook 库 ShadowHook，为本项目底层函数拦截能力提供了稳定可靠的实现基础。" to "Thanks to ByteDance's open-source Android inline hook library ShadowHook for providing a reliable native hook foundation.",
        "增强型 Android DL 库" to "Enhanced Android DL Library",
        "感谢开源项目 xDL，为本项目在 Android 高版本环境下解析动态库与符号地址提供了高效可靠的底层支持。" to "Thanks to xDL for reliable dynamic library and symbol resolution across modern Android versions.",
        "调整时间倍率" to "Adjust time multiplier",
        "启用重启预加载 (Root)" to "Enable Preload on Restart (Root)",
        "重启目标应用后生效" to "Takes effect after restarting the target app",
        "选择或输入列表文件路径" to "Select or enter list file path",
        "确认终止进程" to "Confirm Kill Process",
        "确认终止" to "Kill",
        "确认退出" to "Confirm Exit",
        "确定要关闭悬浮窗并完全退出 GGNext 吗？" to "Close overlay and exit GGNext completely?",
        "关闭悬浮窗并完全退出修改器" to "Close overlay and exit completely",
        "Root 模式 · 已授权" to "Root Mode · Granted",
        "Root 模式 · 未授权，点击切换" to "Root Mode · Unauthorized, tap to switch",
        "免 Root 模式 · 共享 UID" to "No-Root Mode · Shared UID",
        "当前运行于 Root 直读模式" to "Running in Root direct mode",
        "当前运行于免 Root 共享 UID 模式" to "Running in No-Root shared UID mode",
        "系统悬浮窗权限已授予" to "Overlay permission granted",
        "已授予系统悬浮窗权限" to "Overlay permission granted",
        "未授予系统悬浮窗权限，请先在系统设置中开启" to "Overlay permission not granted; enable in system settings first",
        "短按悬浮球打开面板，长按悬浮球调节变速" to "Tap floating ball to open panel, long-press for speed",
        "已开启自动暂停游戏" to "Autopause enabled",
        "已关闭自动暂停游戏" to "Autopause disabled",
        "已开启壁纸动态取色" to "Dynamic wallpaper colors enabled",
        "已关闭壁纸动态取色" to "Dynamic wallpaper colors disabled",
        "已启用列表动画" to "List animations enabled",
        "已关闭列表动画" to "List animations disabled",
        "支持导入多个 .ttf/.otf 字体并自由切换或删除" to "Import multiple .ttf/.otf fonts to switch or delete anytime",
        "跟随系统内置字体" to "Follow system default font",
        "已导入字体" to "Imported Font",
        "导入字体失败" to "Font import failed",
        "已切换为系统默认字体" to "Switched to system default font",
        "已删除字体" to "Font deleted",
        "已删除字体并恢复系统默认" to "Font deleted; restored system default",
        "字体文件不存在，已自动移除该记录" to "Font file missing; entry removed",
        "仅支持导入 .ttf / .otf 字体文件" to "Only .ttf / .otf font files are supported",
        "字体文件过大（上限 32MB）" to "Font file too large (max 32MB)",
        "无法读取所选字体文件" to "Unable to read selected font file",
        "所选文件不是有效的 TTF/OTF 字体文件" to "Selected file is not a valid TTF/OTF font",
        "无法解析该字体文件，请更换有效的 TTF 字体" to "Failed to parse font file; try another TTF font",
        "找不到或无法读取字体文件" to "Font file not found or unreadable",
        "选择语言" to "Select Language",
        "0 (系统自动分配)" to "0 (Auto-allocate)",
        "选择内存段" to "Select memory region",
        "正在分配内存页" to "Allocating memory page",
        "编辑所选" to "Edit Selected",
        "蒙版搜索" to "Mask Search",
        "保存所选" to "Save Selected",
        "恢复所选" to "Revert Selected",
        "清除选择" to "Clear Selection",
        "以联合搜索方式复制" to "Copy as Group Search",
        "选定为搜索结果" to "Load as Results",
        "仅保留有变化的结果" to "Keep Changed Only",
        "更改地址/类型" to "Change Address/Type",
        "从文件载入" to "Load from File",
        "保存到文件" to "Save to File",
        "导出选定项" to "Export Selected",
        "过滤搜索结果" to "Filter Results",
        "暂停进程" to "Pause Process",
        "收起面板" to "Minimize",
        "扫描中" to "Scanning",
        "搜索中..." to "Searching...",
        "取消搜索" to "Cancel search",
        "当前无可选项目" to "No selectable items",
        "无项目可修改，请先搜索或勾选项目" to "No items to edit; search or select items first",
        "请先勾选要复制为联合搜索的项目" to "Select items to copy as group search first",
        "无跨进程内存读写权限，请检查 Root 授权" to "No cross-process memory permission; check Root access",
        "值格式" to "Format",
        "冻结" to "Freeze",
        "允许增大" to "Allow increase",
        "允许减小" to "Allow decrease",
        "范围限制" to "Range limit",
        "从" to "From",
        "到" to "To",
        "从:" to "From:",
        "到:" to "To:",
        "最小值" to "Min",
        "最大值" to "Max",
        "处理中" to "Working",
        "选择类型" to "Select Type",
        "关闭" to "Close",
        "关闭弹窗" to "Close dialog",
        "空格" to "Space",
        "退格删除" to "Backspace",
        "删除光标后字符" to "Delete forward",
        "删除光标后字符 / 清空" to "Delete forward / Clear",
        "全选输入内容" to "Select all text",
        "输入历史记录" to "Input history",
        "输入历史 (点击直接填入):" to "History (tap to insert):",
        "完整管理" to "Manage",
        "暂无输入历史" to "No history yet",
        "缩放/收起键盘" to "Resize / Hide keyboard",
        "收起键盘" to "Hide keyboard",
        "内置键盘" to "Built-in Keyboard",
        "系统" to "Sys",
        "全部内存" to "All Memory",
        "指定地址区间" to "Custom Address Range",
        "数据类型:" to "Data Type:",
        "点击编辑联合搜索距离" to "Tap to edit group span",
        "差值 (D): " to "Delta (D): ",
        "每 N 个取 1 个: " to "Every N-th: ",
        "N - 新值;  O - 旧值;  D - 差值" to "N - New;  O - Old;  D - Delta",
        "-: 非指针" to "-: Not pointer",
        "RO: 只读" to "RO: Read-only",
        "W: 可写" to "W: Writable",
        "X: 可执行" to "X: Executable",
        "WX: 读写执行" to "WX: Read/Write/Exec",
        "十六进制格式 (little-endian)" to "HEX format (little-endian)",
        "反向十六进制格式 (big-endian)" to "Reverse HEX format (big-endian)",
        "字符串表达式" to "String expression",
        "实数 x 的小数部分为小数点后的数字" to "Fractional digits after decimal point",
        "将按新类型重读数值并同步已冻结锁值" to "Re-reads values with new type and syncs freezes",
        "多选状态下可一键统一变更所有已选地址的数据类型" to "Change data type for all selected addresses at once",
        "输入偏移实时计算目标地址并展示各种类型的内存数值" to "Enter offset to compute target address & live values",
        "已复制目标地址及内存数值" to "Copied target address & values",
        "地址偏移/异或" to "Offset / XOR",
        "偏移" to "Offset",
        "异或" to "XOR",
        "点击任意行复制结果" to "Tap any row to copy",
        "请先在列表中勾选至少 2 个地址以计算两两偏移与异或" to "Select at least 2 items to compute pairwise Offset & XOR",
        "输入地址" to "Enter address",
        "内存模块列表" to "Module List",
        "内存跳转历史 (点击直接填入):" to "Goto History (tap to fill):",
        "跳转" to "Go",
        "地址无效" to "Invalid address",
        "权限" to "Perm",
        "开始" to "Start",
        "选择内存模块" to "Select Module",
        "点击模块起始地址直接跳转定位内存" to "Tap module base address to jump",
        "搜索模块名称或路径..." to "Search module name or path...",
        "已固定置顶" to "Pinned to top",
        "已取消固定" to "Unpinned",
        "固定" to "Pin",
        "置顶" to "Pin",
        "取消置顶" to "Unpin",
        "已置顶" to "Pinned",
        "已删除脚本记录" to "Script entry removed",
        "左滑快捷操作" to "Swipe left for actions",
        "编辑脚本记录与备注" to "Edit Script Entry & Note",
        "添加可选备注/说明" to "Add optional note",
        "已更新脚本记录" to "Script entry updated",
        "历史记录" to "History",
        "将指定起始地址的数据复制并粘贴覆盖到目标内存区域" to "Copy memory from source address to destination",
        "复制从 :" to "Copy from:",
        "粘贴到 :" to "Paste to:",
        "字节数 :" to "Bytes:",
        "复制字节数必须大于 0" to "Byte count must be > 0",
        "日志已清空" to "Logs cleared",
        "输入有误" to "Invalid input",
        "未知错误" to "Unknown error",
        "莓果" to "Berry",
        "燕麦" to "Oat",
        "石板" to "Slate",
        "严禁将本软件用于制作或分发游戏外挂、篡改联机数据、破坏计算机信息系统、绕过付费与版权验证、窃取敏感信息或任何违反当地法律法规及第三方服务协议的行为。如您所在地区法律限制此类工具的使用，请立即停止使用并卸载。" to "You must not use this software to create or distribute cheats, alter online data, compromise computer systems, bypass billing or copyright protections, extract sensitive data, or violate applicable laws or third-party terms of service. Discontinue use and uninstall immediately if restricted in your jurisdiction.",
        "致敬经典 Android 内存分析与修改工具 GameGuardian，其功能范式与交互理念为本项目提供了重要启发。" to "A tribute to GameGuardian, the classic Android memory analysis tool whose paradigm inspired this project.",
        "未附加，点击选择目标进程" to "Detached, tap to select process",
        "已切换主题：" to "Theme switched: "
    )

    private fun toEnglish(text: String): String {
        enMap[text]?.let { return it }
        when {
            text.startsWith("已选 ") -> return "Selected " + text.removePrefix("已选 ")
            text.startsWith("修改选中 ") -> return "Edit " + text.removePrefix("修改选中 ") + " selected"
            text.startsWith("批量更改类型（已选 ") ->
                return text.replace("批量更改类型（已选 ", "Batch Change Type (").replace(" 项）", " selected)")
            text.startsWith("当前：") -> return "Current: " + toEnglish(text.removePrefix("当前："))
            text.startsWith("当前预设：") -> return "Preset: " + toEnglish(text.removePrefix("当前预设："))
            text.startsWith("当前主题：") -> return "Theme: " + toEnglish(text.removePrefix("当前主题："))
            text.startsWith("当前共有 ") -> return text.replace("当前共有 ", "Total ").replace(" 条输入记录", " history records")
            text.startsWith("最大显示 ") -> return text.replace("最大显示 ", "Max ").replace(" 条 · 地址/数值/类型筛选", " · Address/Value/Type filter")
            text.startsWith("搜索范围：指定地址区间") -> return text.replace("搜索范围：指定地址区间", "Range: Custom")
            text.startsWith("值列表 [") -> return text.replace("值列表 [", "Value Action [")
            text.startsWith("编辑此地址数值") -> return text.replace("编辑此地址数值", "Edit value")
            text.startsWith("转到此地址：") -> return text.replace("转到此地址：", "Go to address: ")
            text.startsWith("跳转到指针地址：") -> return text.replace("跳转到指针地址：", "Go to pointer: ")
            text.startsWith("跳转到 D 指针：") -> return text.replace("跳转到 D 指针：", "Go to D pointer: ")
            text.startsWith("跳转到 Q 指针：") -> return text.replace("跳转到 Q 指针：", "Go to Q pointer: ")
            text.startsWith("复制地址：") -> return text.replace("复制地址：", "Copy address: ")
            text.startsWith("复制十进制值：") -> return text.replace("复制十进制值：", "Copy decimal: ")
            text.startsWith("复制十六进制：") -> return text.replace("复制十六进制：", "Copy HEX: ")
            text.startsWith("复制反向十六进制：") -> return text.replace("复制反向十六进制：", "Copy reverse HEX: ")
            text.startsWith("恢复并移除所选") -> return text.replace("恢复并移除所选", "Revert & remove selected")
            text.startsWith("移除所选") -> return text.replace("移除所选", "Remove selected")
            text == "移除全部列表项" -> return "Remove all items"
            text.startsWith("文件夹 · ") -> return text.replace("文件夹 · ", "Folder · ").replace(" 项", " items")
            text.startsWith("选中区域: ") -> return text.replace("选中区域: ", "Region: ").replace("共 ", "").replace(" 字节", " bytes")
            text.startsWith("导出区间: ") -> return text.replace("导出区间: ", "Dump: ").replace(" 字节", " bytes")
            text.startsWith("最终合成蒙版: ") -> return text.replace("最终合成蒙版: ", "Resultant mask: ")
            text.startsWith("命中 ") -> return "Found " + text.removePrefix("命中 ")
            text.startsWith("找到 ") -> return text.replace("找到 ", "Found ").replace(" 条结果", " results")
            text.startsWith("已完成初始模糊采样，共 ") -> return text.replace("已完成初始模糊采样，共 ", "Initial fuzzy snapshot: ").replace(" 项", " items")
            text.startsWith("模糊筛选 ") -> return text.replace("模糊筛选 ", "Fuzzy ").replace("：剩余 ", ": ").replace(" 项", " left")
            text.startsWith("蒙版搜索找到 ") -> return text.replace("蒙版搜索找到 ", "Mask search found ").replace(" 项", " items")
            text.startsWith("指针搜索找到 ") -> return text.replace("指针搜索找到 ", "Pointer search found ").replace(" 条结果", " results")
            text.startsWith("已附加到 ") -> return "Attached to " + text.removePrefix("已附加到 ")
            text.startsWith("当前已附加到 ") -> return "Already attached to " + text.removePrefix("当前已附加到 ")
            text.startsWith("附加 ") -> return text.replace("附加 ", "Attach ").replace(" 失败：", " failed: ")
            text.startsWith("已暂停目标进程 ") -> return "Paused " + text.removePrefix("已暂停目标进程 ")
            text.startsWith("已恢复目标进程 ") -> return "Resumed " + text.removePrefix("已恢复目标进程 ")
            text.startsWith("已终止目标进程 ") -> return "Terminated " + text.removePrefix("已终止目标进程 ")
            text.startsWith("已保存 ") -> return text.replace("已保存 ", "Saved ").replace(" 项到保存列表", " items to Saved list").replace(" 项到 ", " items to ")
            text.startsWith("已从 ") -> return text.replace("已从 ", "Loaded from ").replace(" 载入 ", ": ").replace(" 项", " items")
            text.startsWith("已批量修改 ") -> return text.replace("已批量修改 ", "Batch edited ").replace(" 项", " items")
            text.startsWith("已修改地址 ") -> return "Edited address " + text.removePrefix("已修改地址 ")
            text.startsWith("已批量更改 ") -> return text.replace("已批量更改 ", "Changed type of ").replace(" 项的类型为 ", " items to ")
            text.startsWith("已更新地址为 ") -> return "Updated address to " + text.removePrefix("已更新地址为 ")
            text.startsWith("已恢复 ") -> return text.replace("已恢复 ", "Reverted ").replace(" 项为先前数值", " items to previous values")
            text.startsWith("已将 ") -> return text.replace("已将 ", "Loaded ").replace(" 项载入为搜索结果", " items as search results")
            text.startsWith("已保留 ") -> return text.replace("已保留 ", "Kept ").replace(" 个有变化的结果", " changed results")
            text.startsWith("新搜索: ") -> return "New search: " + text.removePrefix("新搜索: ")
            text.startsWith("改善: ") -> return "Refine: " + text.removePrefix("改善: ")
            text.startsWith("模糊初始采样中") -> return "Fuzzy initial snapshot..."
            text.startsWith("模糊筛选 (") -> return text.replace("模糊筛选 (", "Fuzzy (")
            text.startsWith("地址蒙版搜索中") -> return "Address mask searching..."
            text.startsWith("指针链搜索中") -> return "Pointer searching..."
            text.startsWith("读取内存页") -> return "Reading memory page"
            text.startsWith("导出内存中") -> return "Dumping memory"
            text.startsWith("已导出内存区间到 ") -> return "Dumped memory to " + text.removePrefix("已导出内存区间到 ")
            text.startsWith("导出内存失败") -> return "Memory dump failed"
            text.startsWith("文件读写失败: ") -> return "File I/O error: " + text.removePrefix("文件读写失败: ")
            text.startsWith("已成功复制 ") -> return text.replace("已成功复制 ", "Copied ").replace(" 字节到 ", " bytes to ")
            text.startsWith("无效的反向十六进制: ") -> return "Invalid reverse HEX: " + text.removePrefix("无效的反向十六进制: ")
            text.startsWith("无效的十六进制: ") -> return "Invalid HEX: " + text.removePrefix("无效的十六进制: ")
            text.startsWith("无效的浮点数: ") -> return "Invalid Float: " + text.removePrefix("无效的浮点数: ")
            text.startsWith("无效的双精度数: ") -> return "Invalid Double: " + text.removePrefix("无效的双精度数: ")
            text.startsWith("无效的数值: ") -> return "Invalid number: " + text.removePrefix("无效的数值: ")
            text.startsWith("区域大小无效: ") -> return "Invalid span: " + text.removePrefix("区域大小无效: ")
            text.startsWith("Byte 取值范围为") -> return text.replace("Byte 取值范围为 -128 ~ 255，当前: ", "Byte range is -128..255, got: ")
            text.startsWith("Word 取值范围为") -> return text.replace("Word 取值范围为 -32,768 ~ 65,535，当前: ", "Word range is -32,768..65,535, got: ")
            text.startsWith("Dword 取值范围为") -> return text.replace("Dword 取值范围为 -2,147,483,648 ~ 4,294,967,295，当前: ", "Dword range is -2,147,483,648..4,294,967,295, got: ")
            text == "硬件断点" -> return "Hardware Breakpoint"
            text == "硬件断点调试器" -> return "Hardware Breakpoint Debugger"
            text == "设置硬件断点" -> return "Set Hardware Breakpoint"
            text == "添加硬件断点" -> return "Add Hardware Breakpoint"
            text == "添加断点" -> return "Add Breakpoint"
            text == "断点地址 (HEX)" -> return "Breakpoint Address (HEX)"
            text == "断点类型" -> return "Breakpoint Type"
            text == "监视大小 (字节)" -> return "Watch Size (Bytes)"
            text == "调试后端模式" -> return "Debugger Backend Mode"
            text == "只读" -> return "Read"
            text == "只写" -> return "Write"
            text == "读写" -> return "R/W"
            text == "执行" -> return "Exec"
            text == "字节" -> return "B"
            text == "活动" -> return "Active"
            text == "运行中" -> return "Running"
            text == "已暂停" -> return "Paused"
            text == "已停止" -> return "Stopped"
            text == "目标" -> return "PID"
            text == "线程" -> return "Threads"
            text == "架构" -> return "Arch"
            text == "寄存器" -> return "Registers"
            text == "浮点" -> return "FP"
            text == "回溯" -> return "Backtrace"
            text == "确认添加" -> return "Add"
            text == "写入汇编" -> return "Write ASM"
            text == "写入汇编指令" -> return "Write Assembly"
            text == "写入地址 (HEX)" -> return "Target Address (HEX)"
            text == "指令集架构" -> return "Architecture"
            text == "机器码预览" -> return "Machine Code Preview"
            text == "汇编引擎扩展" -> return "Assembly Engine Pack"
            text == "Keystone / Capstone 引擎包" -> return "Keystone / Capstone Pack"
            text == "已就绪" -> return "Ready"
            text == "部分加载" -> return "Partial"
            text == "未导入" -> return "Not Imported"
            text == "导入压缩包 (.zip)" -> return "Import Pack (.zip)"
            text == "重新导入压缩包" -> return "Re-import Pack"
            text == "移除" -> return "Remove"
            text == "应用变速" -> return "Apply Speed"
            text == "重置 1.0x" -> return "Reset 1.0x"
            text == "分配" -> return "Allocate"
            text == "采集寄存器与调用链回溯" -> return "Capture Registers & Backtrace"
            text == "命中断点时自动记录完整寄存器状态及栈回溯" -> return "Automatically record GPR/FP registers and callstack on hit"
            text.startsWith("目标：") -> return "Target: " + text.removePrefix("目标：")
            text.startsWith("已修改寄存器 ") -> return "Updated register " + text.removePrefix("已修改寄存器 ")
            text.startsWith("已切换断点后端为 ") -> return "Breakpoint backend: " + toEnglish(text.removePrefix("已切换断点后端为 "))
            text.startsWith("已添加断点 ") -> return "Added breakpoint " + text.removePrefix("已添加断点 ")
            text.startsWith("已移除断点 ") -> return "Removed breakpoint " + text.removePrefix("已移除断点 ")
            text.startsWith("已删除 ") && text.endsWith(" 个断点") ->
                return "Deleted " + text.removePrefix("已删除 ").removeSuffix(" 个断点") + " breakpoints"
            text.startsWith("已复制回溯帧 ") -> return "Copied backtrace frame " + text.removePrefix("已复制回溯帧 ")
            text.startsWith("已复制 ") -> return "Copied " + text.removePrefix("已复制 ")
            text.startsWith("已复制联合搜索表达式：") -> return "Copied group search: " + text.removePrefix("已复制联合搜索表达式：")
            text.startsWith("已切换语言：") -> return "Language switched: " + text.removePrefix("已切换语言：")
            text.startsWith("已切换字体：") -> return "Font switched: " + text.removePrefix("已切换字体：")
            text.startsWith("已导入并应用字体：") -> return "Imported & applied font: " + text.removePrefix("已导入并应用字体：")
            text.startsWith("已将「") && text.endsWith("」固定到右侧工具栏") ->
                return "Pinned \"" + text.removePrefix("已将「").removeSuffix("」固定到右侧工具栏") + "\" to right toolbar"
            text.startsWith("已将「") && text.endsWith("」移回流动工具栏") ->
                return "Unpinned \"" + text.removePrefix("已将「").removeSuffix("」移回流动工具栏") + "\" to flowing toolbar"
            text.startsWith("已跳转到模块起始地址：") -> return "Jumped to module base: " + text.removePrefix("已跳转到模块起始地址：")
            text.startsWith("当前进程未找到 ") -> return text.replace("当前进程未找到 ", "Region not found: ").replace(" 内存段", "")
            text.startsWith("终止当前选中的进程 ") -> return "Kill current target " + text.removePrefix("终止当前选中的进程 ")
            text.startsWith("确定要强制终止目标进程 ") ->
                return text.replace("确定要强制终止目标进程 ", "Force kill target process ").replace(" 吗？进程未保存的数据可能会丢失。", "? Unsaved process data may be lost.")
            text.startsWith("汇编引擎包已导入并加载") -> return text.replace("汇编引擎包已导入并加载", "Assembly engine pack loaded")
            text.startsWith("已部分加载汇编引擎：") -> return "Partially loaded assembly engine: " + text.removePrefix("已部分加载汇编引擎：")
            text.startsWith("已写入 ") && text.contains(" 汇编至 ") ->
                return text.replace("已写入 ", "Wrote ").replace(" 汇编至 ", " ASM to ").replace(" 处地址", " addresses")
            text.startsWith("已写入 ") && text.contains(" 字节") ->
                return text.replace("已写入 ", "Wrote ").replace(" 字节", " bytes")
            text.startsWith("汇编写入失败：") -> return "ASM write failed: " + text.removePrefix("汇编写入失败：")
            text.startsWith("正在跳转 QQ 群：") -> return "Opening QQ group: " + text.removePrefix("正在跳转 QQ 群：")
            text.startsWith("已分配内存页 ") -> return "Allocated memory page " + text.removePrefix("已分配内存页 ")
            text.startsWith("分配内存页失败") -> return text.replace("分配内存页失败", "Memory page allocation failed")
            text.contains("Keystone 已就绪") -> return text.replace("Keystone 已就绪", "Keystone Ready")
            text.contains("请先在主页导入汇编引擎包") -> return text.replace("请先在主页导入汇编引擎包", "Import assembly pack on Home first")
            text.startsWith("已复制 ") && text.endsWith(" 条日志") ->
                return "Copied " + text.removePrefix("已复制 ").removeSuffix(" 条日志") + " log entries"
            text.startsWith("已复制 ") && text.endsWith(" 示例") ->
                return "Copied " + text.removePrefix("已复制 ").removeSuffix(" 示例") + " example"
            text.startsWith("已导出日志到 ") ->
                return "Exported logs to " + text.removePrefix("已导出日志到 ")
            text.startsWith("导出日志失败：") ->
                return "Export logs failed: " + text.removePrefix("导出日志失败：")
        }
        return text
    }
}
