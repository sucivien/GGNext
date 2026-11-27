package com.littlecodesauce.ggnext.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.engine.Shell
import com.littlecodesauce.ggnext.model.TargetProcess
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 真实进程扫描器（已彻底移除演示模式与全部硬编码假进程）：
 *
 * 1. Root 模式 (`WorkMode.ROOT`)：
 *    - 通过 `su` 执行 `ps -A -w -o UID,PID,PPID,RSS,ARGS`，获取完整无截断的包名/进程名、
 *      真实 UID、PPID 与物理内存 RSS；
 * 2. 免 Root 模式 (`WorkMode.NO_ROOT`)：
 *    - 扫描当前应用可读的 `/proc` 进程项及 `ActivityManager.runningAppProcesses`（共享 UID / 双开沙箱环境）；
 * 3. 严格过滤 Linux 内核线程与原生 HAL/守护进程：
 *    - 彻底过滤 `media.codec`、`media.metrics`、`android.hardware.*`、`vendor.qti.*`、`surfaceflinger` 等 Linux 原生守护进程；
 *    - 结合 `PackageManager` 已安装应用表 (`pkgMap` + `uidMap`) 精确区分「用户」应用 (`system = false`) 与「系统」应用 (`system = true`)。
 */
object ProcessScanner {

    private val NATIVE_DAEMON_PREFIXES = listOf(
        "android.hardware.",
        "vendor.",
        "media.",
        "init.",
        "zygote",
        "webview_zygote",
        "usap32",
        "usap64",
        "surfaceflinger",
        "servicemanager",
        "hwservicemanager",
        "vndservicemanager",
        "audioserver",
        "cameraserver",
        "mediaserver",
        "drmserver",
        "keystore",
        "credstore",
        "logd",
        "netd",
        "lmkd",
        "statsd",
        "installd",
        "storaged",
        "vold",
        "wificond",
        "traced",
        "incidentd",
        "tombstoned",
        "apexd",
        "adbd",
        "mdnsd",
        "gatekeeperd",
        "healthd",
        "thermalservice",
        "cnss-daemon",
        "imsdaemon",
        "rild",
        "qcrild"
    )

    private val SYSTEM_PKG_PREFIXES = listOf(
        "com.android.",
        "android.",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.webview",
        "com.google.android.ext.",
        "com.google.android.overlay.",
        "com.google.android.providers.",
        "com.google.android.networkstack",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.tts",
        "com.google.android.inputmethod",
        "com.qualcomm.",
        "com.qti.",
        "org.codeaurora.",
        "com.mediatek.",
        "com.samsung.android.",
        "com.sec.android.",
        "com.miui.",
        "com.xiaomi.",
        "com.coloros.",
        "com.oplus.",
        "com.heytap.",
        "com.vivo.",
        "com.bbk.",
        "com.iqoo.",
        "com.huawei.",
        "com.hihonor.",
        "com.meizu.",
        "com.oneplus.",
        "com.realme."
    )

    private val VIRTUAL_HOST_KEYWORDS = listOf(
        "parallel",
        "virtual",
        "dual",
        "multiapp",
        "clone",
        "dkplugin",
        "excelliance",
        "vxp",
        "sandvxposed",
        "blackbox",
        "gbox",
        "terista",
        "meta",
        "island",
        "shelter"
    )

    private val VALID_JAVA_PKG_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+(:[a-zA-Z0-9_.\\-]+)?$")

    fun scan(context: Context): List<TargetProcess> {
        val pm = context.packageManager
        val mode = Session.ui.value.workMode
        val myUid = Process.myUid()
        val myPid = Process.myPid()

        // 预先构建已安装应用的包名映射与 UID (及 appId) 映射，解决 Android 11+ 及双开/多用户问题
        val installedApps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val pkgMap = HashMap<String, ApplicationInfo>(installedApps.size * 2)
        val uidMap = HashMap<Int, MutableList<ApplicationInfo>>(installedApps.size)
        val appIdMap = HashMap<Int, MutableList<ApplicationInfo>>(installedApps.size)
        for (app in installedApps) {
            pkgMap[app.packageName] = app
            // 排除 GGNext 自身包名进入 uidMap/appIdMap 首选槽位，防止与目标应用共享 android:sharedUserId 时把目标进程误匹配为 GGNext 自身
            if (app.packageName != context.packageName) {
                uidMap.getOrPut(app.uid) { ArrayList(2) }.add(app)
                val baseAppId = app.uid % 100000
                appIdMap.getOrPut(baseAppId) { ArrayList(2) }.add(app)
            }
        }

        val out = LinkedHashMap<Int, TargetProcess>()
        val ppidByPid = HashMap<Int, Int>()
        val procIdentityByPid = HashMap<Int, String>()

        // 1. Root 模式下通过 su 获取全系统真实运行中的 Android 应用进程
        if (mode == WorkMode.ROOT && Shell.hasRoot()) {
            scanViaRootProc(context, pm, pkgMap, uidMap, appIdMap, myPid, out, ppidByPid, procIdentityByPid)
        }

        // 2. 无论是否为 Root 模式，始终扫描当前权限可读的 /proc（含 SELinux 直探同 UID / sharedUserId 进程）与 ActivityManager 作为补充
        scanProcDirectly(context, pm, installedApps, pkgMap, uidMap, appIdMap, myUid, myPid, mode, out, ppidByPid, procIdentityByPid)
        scanFromActivityManager(context, pm, pkgMap, uidMap, appIdMap, myPid, out, ppidByPid, procIdentityByPid)

        // 3. 基于 Android OS (uid, processName) 进程唯一性契约与父子进程树折叠 fork 辅助子进程 / 线程伪影
        val deduplicated = deduplicateForkedAndSharedProcesses(out, ppidByPid, procIdentityByPid)

        return deduplicated.sortedWith(
            compareBy<TargetProcess> { it.system }
                .thenByDescending { it.isVirtual }
                .thenBy { it.pid }
        )
    }

    /**
     * 读取 `/proc/<pid>/status` 验证该 PID 是否为真实进程主线程 (`Tgid == pid`) 且非僵尸进程 (`State != Z/X`)。
     * 若当前 SELinux 权限不可直接读 `/proc/<pid>/status`，则返回 `true` 交由上层 `ps` 数据处理。
     */
    private fun verifyThreadGroupLeaderIfReadable(pid: Int, myPid: Int): Boolean {
        val statusFile = File("/proc/$pid/status")
        if (!statusFile.canRead()) return true
        var tgid = 0
        var ppid = 0
        var isZombieOrDead = false
        val ok = runCatching {
            statusFile.forEachLine { line ->
                when {
                    line.startsWith("Tgid:") -> {
                        tgid = line.substringAfter("Tgid:").trim().toIntOrNull() ?: 0
                    }
                    line.startsWith("PPid:") -> {
                        ppid = line.substringAfter("PPid:").trim().toIntOrNull() ?: 0
                    }
                    line.startsWith("State:") -> {
                        val st = line.substringAfter("State:").trim()
                        if (st.startsWith("Z") || st.startsWith("X") || st.startsWith("x")) {
                            isZombieOrDead = true
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)
        if (!ok) return true
        if (isZombieOrDead) return false
        if (ppid == myPid) return false
        if (tgid > 0 && tgid != pid) return false
        return true
    }

    /**
     * 基于 Android `ActivityManagerService` 的 `(uid, processName)` 唯一性不变量：
     * - 同一用户 UID 下相同的 `processName` 在 Android 系统中仅对应唯一主应用进程；
     * - 当目标游戏或虚拟沙箱使用 `fork()` 派生崩溃守护子进程 / Houdini 辅助进程 / 信号监听子进程时，
     *   子进程会继承父进程相同的 `cmdline` 与 COW `VmRSS`。此处优先保留非子进程（`ppid` 不在同组中）且内存占用最高的主进程。
     */
    private fun deduplicateForkedAndSharedProcesses(
        candidates: Map<Int, TargetProcess>,
        ppidByPid: Map<Int, Int>,
        procIdentityByPid: Map<Int, String>
    ): List<TargetProcess> {
        if (candidates.size <= 1) return candidates.values.toList()
        val byIdentity = LinkedHashMap<String, MutableList<TargetProcess>>()
        for ((pid, proc) in candidates) {
            val identity = procIdentityByPid[pid] ?: "${proc.uid}|${proc.packageName}|${proc.label}"
            byIdentity.getOrPut(identity) { ArrayList(2) }.add(proc)
        }
        val result = ArrayList<TargetProcess>(byIdentity.size)
        for ((_, group) in byIdentity) {
            if (group.size == 1) {
                result.add(group[0])
            } else {
                val groupPids = group.mapTo(HashSet(group.size * 2)) { it.pid }
                val primary = group.sortedWith(
                    compareBy<TargetProcess> { (ppidByPid[it.pid] ?: 0) in groupPids }
                        .thenByDescending { it.sizeBytes }
                        .thenBy { it.pid }
                ).first()
                result.add(primary)
            }
        }
        return result
    }

    /**
     * 通过 Root 权限执行宽列不截断 `ps -A -w -o UID,PID,PPID,RSS,ARGS`，
     * 并结合 `/proc/<pid>/cmdline` 确保超长子进程名 100% 不被截断。
     */
    private fun scanViaRootProc(
        context: Context,
        pm: PackageManager,
        pkgMap: Map<String, ApplicationInfo>,
        uidMap: Map<Int, List<ApplicationInfo>>,
        appIdMap: Map<Int, List<ApplicationInfo>>,
        myPid: Int,
        out: MutableMap<Int, TargetProcess>,
        ppidByPid: MutableMap<Int, Int>,
        procIdentityByPid: MutableMap<Int, String>
    ) {
        val script = "ps -A -w -o UID,PID,PPID,RSS,ARGS 2>/dev/null || ps -A -o UID,PID,PPID,RSS,ARGS"
        val lines = Shell.suLines(script)

        for (raw in lines.drop(1)) {
            val parts = raw.trim().split(Regex("\\s+"), limit = 5)
            if (parts.size < 5) continue
            val uid = parseAndroidUid(parts[0])
            val pid = parts[1].toIntOrNull() ?: continue
            val ppid = parts[2].toIntOrNull() ?: 0
            val rss = parts[3].toLongOrNull() ?: 0L
            val cmdRaw = parts[4].trim()

            if (pid <= 1 || pid == myPid || ppid == myPid || ppid == 2 || rss <= 0L) continue
            if (!verifyThreadGroupLeaderIfReadable(pid, myPid)) continue

            var cmd = cmdRaw.split(' ').firstOrNull()?.trim().orEmpty()
            // 若可读 /proc/<pid>/cmdline，优先读取完整无截断进程名
            val procCmd = readCmdlineDirect(pid)
            if (procCmd.length > cmd.length) {
                cmd = procCmd
            }
            if (cmd.isEmpty()) continue

            addCandidateIfAndroidApp(
                context = context,
                pm = pm,
                pkgMap = pkgMap,
                uidMap = uidMap,
                appIdMap = appIdMap,
                pid = pid,
                uid = uid,
                ppid = ppid,
                rssKb = rss,
                rawProcessName = cmd,
                allowRootInspect = true,
                out = out,
                ppidByPid = ppidByPid,
                procIdentityByPid = procIdentityByPid
            )
        }
    }

    private fun readCmdlineDirect(pid: Int): String = runCatching {
        File("/proc/$pid/cmdline").readBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trim()
    }.getOrDefault("")

    /**
     * 直接扫描 `/proc` 目录（免 Root / 共享 UID 模式下获取相同 UID 或可读沙箱内的应用进程）：
     * - 严格校验 `Tgid == pid` 且非僵尸进程，排除 GGNext 自身派生的子进程 (`ppid == myPid`)；
     * - 严格限制 UID 反查包名仅在 `cmdline` 为空且 `commName` 与包名前缀/后缀匹配时生效，
     *   彻底消除 `android:sharedUserId` 模式下将同 UID 的 shell / 辅助进程误重命名为目标游戏导致的重复进程问题。
     */
    private fun scanProcDirectly(
        context: Context,
        pm: PackageManager,
        installedApps: List<ApplicationInfo>,
        pkgMap: Map<String, ApplicationInfo>,
        uidMap: Map<Int, List<ApplicationInfo>>,
        appIdMap: Map<Int, List<ApplicationInfo>>,
        myUid: Int,
        myPid: Int,
        mode: WorkMode,
        out: MutableMap<Int, TargetProcess>,
        ppidByPid: MutableMap<Int, Int>,
        procIdentityByPid: MutableMap<Int, String>
    ) {
        val candidatePids = LinkedHashSet<Int>()
        val procEntries = runCatching { File("/proc").listFiles() }.getOrNull()
        if (!procEntries.isNullOrEmpty()) {
            for (entry in procEntries) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid > 1 && pid != myPid && !out.containsKey(pid)) {
                    candidatePids.add(pid)
                }
            }
        }

        // 当 SELinux 拦截 opendir("/proc") 或 hidepid 隐藏目录项时，在免 Root 模式（或 Root 扫描为空时）通过 proc:dir search 直接探测 PID 空间
        if ((candidatePids.isEmpty() && out.isEmpty()) || mode == WorkMode.NO_ROOT) {
            val maxPid = runCatching {
                File("/proc/sys/kernel/pid_max").readText().trim().toIntOrNull()
            }.getOrNull()?.coerceIn(4096, 131072) ?: 65536

            for (pid in 300..maxPid) {
                if (pid == myPid || out.containsKey(pid) || candidatePids.contains(pid)) continue
                val accessible = try {
                    android.system.Os.access("/proc/$pid/status", android.system.OsConstants.R_OK)
                } catch (_: Throwable) {
                    false
                }
                if (accessible) {
                    candidatePids.add(pid)
                }
            }
        }

        val myAppId = myUid % 100000
        for (pid in candidatePids) {
            if (pid <= 1 || pid == myPid || out.containsKey(pid)) continue

            var uid = -1
            var tgid = 0
            var ppid = 0
            var rssKb = 0L
            var commName = ""
            var isZombieOrDead = false
            val statusOk = runCatching {
                File("/proc/$pid/status").forEachLine { line ->
                    when {
                        line.startsWith("Name:") -> {
                            commName = line.substringAfter("Name:").trim()
                        }
                        line.startsWith("State:") -> {
                            val st = line.substringAfter("State:").trim()
                            if (st.startsWith("Z") || st.startsWith("X") || st.startsWith("x")) {
                                isZombieOrDead = true
                            }
                        }
                        line.startsWith("Tgid:") -> {
                            tgid = line.substringAfter("Tgid:").trim().toIntOrNull() ?: 0
                        }
                        line.startsWith("Uid:") -> {
                            uid = line.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: -1
                        }
                        line.startsWith("PPid:") -> {
                            ppid = line.substringAfter("PPid:").trim().toIntOrNull() ?: 0
                        }
                        line.startsWith("VmRSS:") -> {
                            rssKb = line.substringAfter("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                        }
                    }
                }
                true
            }.getOrDefault(false)

            // 严格过滤子线程 (tgid != pid)、僵尸进程、内核线程及 GGNext 自身派生的子进程 (ppid == myPid)
            if (!statusOk || isZombieOrDead || tgid != pid || ppid == 2 || ppid == myPid || rssKb <= 0L) continue
            if (mode == WorkMode.NO_ROOT && uid != -1 && (uid % 100000) != myAppId) {
                val mapsReadable = runCatching { File("/proc/$pid/maps").canRead() }.getOrDefault(false)
                if (!mapsReadable) continue
            }

            var procName = readCmdlineDirect(pid)
            if (procName.isEmpty() && commName.length >= 6) {
                // 仅当 /proc/<pid>/cmdline 为空且 15 字符 commName 与某已安装应用包名前缀/后缀明确匹配时还原完整包名
                val sameUidApps = if (uid >= 0) (uidMap[uid] ?: appIdMap[uid % 100000]).orEmpty() else emptyList()
                val matchedByComm = sameUidApps.firstOrNull {
                    it.packageName == commName ||
                        (commName.length >= 12 && (it.packageName.startsWith(commName) || it.packageName.endsWith(commName))) ||
                        commName.startsWith(it.packageName.take(15))
                }?.packageName ?: installedApps.firstOrNull {
                    it.packageName != context.packageName &&
                        (it.packageName == commName || (commName.length >= 15 && it.packageName.startsWith(commName)))
                }?.packageName.orEmpty()

                procName = matchedByComm.ifEmpty { commName }
            }
            if (procName.isEmpty()) continue

            addCandidateIfAndroidApp(
                context = context,
                pm = pm,
                pkgMap = pkgMap,
                uidMap = uidMap,
                appIdMap = appIdMap,
                pid = pid,
                uid = uid,
                ppid = ppid,
                rssKb = rssKb,
                rawProcessName = procName,
                allowRootInspect = false,
                out = out,
                ppidByPid = ppidByPid,
                procIdentityByPid = procIdentityByPid
            )
        }
    }

    private fun scanFromActivityManager(
        context: Context,
        pm: PackageManager,
        pkgMap: Map<String, ApplicationInfo>,
        uidMap: Map<Int, List<ApplicationInfo>>,
        appIdMap: Map<Int, List<ApplicationInfo>>,
        myPid: Int,
        out: MutableMap<Int, TargetProcess>,
        ppidByPid: MutableMap<Int, Int>,
        procIdentityByPid: MutableMap<Int, String>
    ) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.forEach { proc ->
                if (proc.pid <= 1 || proc.pid == myPid || out.containsKey(proc.pid)) return@forEach
                if (!verifyThreadGroupLeaderIfReadable(proc.pid, myPid)) return@forEach
                val cmdFromProc = readCmdlineDirect(proc.pid)
                val rawName = cmdFromProc.ifEmpty {
                    proc.processName?.takeIf {
                        it != context.packageName && !it.startsWith("${context.packageName}:")
                    } ?: return@forEach
                }
                val rss = readRssFromProc(proc.pid)
                addCandidateIfAndroidApp(
                    context = context,
                    pm = pm,
                    pkgMap = pkgMap,
                    uidMap = uidMap,
                    appIdMap = appIdMap,
                    pid = proc.pid,
                    uid = proc.uid,
                    ppid = 0,
                    rssKb = if (rss > 0L) rss else 64_000L,
                    rawProcessName = rawName,
                    allowRootInspect = false,
                    out = out,
                    ppidByPid = ppidByPid,
                    procIdentityByPid = procIdentityByPid
                )
            }
        }
    }

    /**
     * 核心过滤与分类逻辑：
     * - 严格拦截 Linux 原生进程、HAL 服务；
     * - 精确区分「用户应用 (system = false)」与「系统应用 (system = true)」（包含系统应用的子进程、共享系统 UID 组件及已更新系统应用）；
     * - 完整保留长子进程名 / 自定义进程名，绝不截断；
     * - 严格识别真正的虚拟空间 / 双开分身进程 (`isVirtual = true`)，避免将普通系统/用户应用子进程或共享 UID 应用误判为虚拟空间进程。
     */
    private fun addCandidateIfAndroidApp(
        context: Context,
        pm: PackageManager,
        pkgMap: Map<String, ApplicationInfo>,
        uidMap: Map<Int, List<ApplicationInfo>>,
        appIdMap: Map<Int, List<ApplicationInfo>>,
        pid: Int,
        uid: Int,
        ppid: Int,
        rssKb: Long,
        rawProcessName: String,
        allowRootInspect: Boolean,
        out: MutableMap<Int, TargetProcess>,
        ppidByPid: MutableMap<Int, Int>,
        procIdentityByPid: MutableMap<Int, String>
    ) {
        if (ppid == 2 || rssKb <= 0L) return
        val cleanCmd = rawProcessName.trim()
        if (cleanCmd.isEmpty() || cleanCmd.startsWith("[") || cleanCmd.startsWith("/")) return
        if (cleanCmd.contains('@') || cleanCmd.contains('/')) return

        // 拦截所有已知的 Linux / HAL / 守护进程前缀
        val lowerCmd = cleanCmd.lowercase()
        for (prefix in NATIVE_DAEMON_PREFIXES) {
            if (lowerCmd == prefix || lowerCmd.startsWith(prefix)) {
                return
            }
        }

        val basePkg = cleanCmd.substringBefore(':')
        if (!basePkg.contains('.')) return
        if (!VALID_JAVA_PKG_REGEX.matches(cleanCmd)) return
        if (basePkg == context.packageName) return

        val userId = if (uid >= 0) (uid / 100000) else 0
        val appId = if (uid >= 0) (uid % 100000) else -1
        val isAppUidRange = appId in 10000..19999 || appId in 90000..99999

        // 1. 优先从 PackageManager 已安装应用映射表中按包名匹配
        var directAppInfo: ApplicationInfo? = pkgMap[basePkg]
            ?: runCatching { pm.getApplicationInfo(basePkg, 0) }.getOrNull()

        // 2. 通过 UID 或多用户 baseAppId 查找同 UID 下的宿主 ApplicationInfo 列表
        val uidPackages: List<ApplicationInfo> = if (uid >= 1000) {
            uidMap[uid]
                ?: appIdMap[appId]
                ?: runCatching {
                    pm.getPackagesForUid(uid)
                        ?.filter { it != context.packageName }
                        ?.mapNotNull { p -> pkgMap[p] ?: runCatching { pm.getApplicationInfo(p, 0) }.getOrNull() }
                }.getOrNull()
                ?: emptyList()
        } else emptyList()

        val hostAppInfo = directAppInfo
            ?: uidPackages.firstOrNull { it.packageName == basePkg }
            ?: uidPackages.firstOrNull { basePkg.startsWith(it.packageName) || it.packageName.startsWith(basePkg) }
            ?: uidPackages.firstOrNull()

        var appInfo: ApplicationInfo? = directAppInfo ?: hostAppInfo

        // 如果既不是已安装的 Android APK，又不在标准 Android 应用 UID 范围内，直接丢弃
        if (appInfo == null && !isAppUidRange) {
            return
        }

        // 判定某 ApplicationInfo 是否为系统内置/预装应用
        fun isSystemApplicationInfo(ai: ApplicationInfo): Boolean {
            val flagSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val flagUpdated = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val src = ai.sourceDir.orEmpty()
            val inSystemPartition = src.startsWith("/system/") ||
                src.startsWith("/product/") ||
                src.startsWith("/system_ext/") ||
                src.startsWith("/vendor/") ||
                src.startsWith("/apex/") ||
                src.startsWith("/oem/") ||
                src.startsWith("/odm/")
            return flagSystem || flagUpdated || inSystemPartition
        }

        val anyUidPkgIsSystem = uidPackages.any { isSystemApplicationInfo(it) } ||
            (appInfo != null && isSystemApplicationInfo(appInfo)) ||
            (appId in 0 until 10000)

        // 3. 虚拟空间与多开分身判定（修复误将应用子进程及系统共享 UID 子进程识别为虚拟空间应用的问题）：
        //   - 若进程属于系统 UID 或其所属/同 UID 应用为系统应用 (`anyUidPkgIsSystem == true`)，或是已安装应用自身的子进程 (`directAppInfo != null`)，
        //     只要 `userId == 0` 且宿主包名不含虚拟空间容器关键词，它就绝不是虚拟空间客体进程！
        val hostPkgName = hostAppInfo?.packageName.orEmpty().lowercase()
        val hostHasVirtualKeyword = VIRTUAL_HOST_KEYWORDS.any { kw -> hostPkgName.contains(kw) }
        val hostIsVirtualContainer = !anyUidPkgIsSystem && (
            hostHasVirtualKeyword ||
                (hostAppInfo != null && directAppInfo != null &&
                    (hostAppInfo.uid % 100000) != (directAppInfo.uid % 100000))
            )

        var guestApkPath = ""
        var guestLabel: String? = null
        if (directAppInfo == null && !anyUidPkgIsSystem && isAppUidRange && (hostHasVirtualKeyword || uidPackages.isEmpty())) {
            val resolvedGuest = resolveVirtualGuestApk(pid, pm, basePkg, allowRootInspect)
            if (resolvedGuest != null) {
                guestApkPath = resolvedGuest.first
                guestLabel = resolvedGuest.second
                if (resolvedGuest.third != null) {
                    directAppInfo = resolvedGuest.third
                    appInfo = resolvedGuest.third
                }
            }
        }

        val isVirtualProcess = !anyUidPkgIsSystem && (
            (userId > 0) ||
                hostIsVirtualContainer ||
                guestApkPath.isNotEmpty()
            )

        val resolvedPkg = directAppInfo?.packageName ?: hostAppInfo?.packageName ?: basePkg

        // 再次检查解析后的包名是否属于 HAL/原生守护进程
        val lowerPkg = resolvedPkg.lowercase()
        for (prefix in NATIVE_DAEMON_PREFIXES) {
            if (lowerPkg.startsWith(prefix)) return
        }

        // 4. 精确判定「系统应用 (system = true)」与「用户应用 (system = false)」
        // 修复：系统应用的子进程（即使具有自定义 process 名或与其它系统组件共享 UID）也严格保持为系统应用 (`system = true`)
        val isSystemPkgPrefix = SYSTEM_PKG_PREFIXES.any {
            resolvedPkg.startsWith(it, ignoreCase = true) || basePkg.startsWith(it, ignoreCase = true)
        }
        val isSystemApp = anyUidPkgIsSystem || isSystemPkgPrefix

        // 5. 进程名与子进程完整显示（修复长进程名/子进程被截断为仅剩软件名的问题）
        val baseLabel = guestLabel
            ?: directAppInfo?.let {
                runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            ?: appInfo?.let {
                runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            ?: cleanCmd

        val finalLabel = when {
            cleanCmd == resolvedPkg || cleanCmd == baseLabel -> baseLabel
            cleanCmd.startsWith("$resolvedPkg:") -> {
                val subName = cleanCmd.substringAfter(':')
                "$baseLabel (:$subName)"
            }
            else -> "$baseLabel ($cleanCmd)"
        }

        val x64 = detectProcess64Bit(pid, appInfo)

        val resolvedUid = if (uid >= 0) uid else (appInfo?.uid ?: 0)
        ppidByPid[pid] = ppid
        procIdentityByPid[pid] = "$resolvedUid|$resolvedPkg|$cleanCmd"

        out[pid] = TargetProcess(
            pid = pid,
            packageName = resolvedPkg,
            label = finalLabel,
            x64 = x64,
            uid = resolvedUid,
            sizeBytes = rssKb * 1024L,
            running = true,
            system = isSystemApp,
            isSelf = false,
            hasIcon = (directAppInfo != null || guestApkPath.isNotEmpty() || appInfo != null),
            isVirtual = isVirtualProcess,
            apkPath = guestApkPath.ifEmpty { directAppInfo?.sourceDir ?: appInfo?.sourceDir.orEmpty() }
        )
    }

    /**
     * 从虚拟空间进程的 `/proc/<pid>/maps` 中定位客体 APK 路径并提取真实应用名称与图标信息
     */
    private fun resolveVirtualGuestApk(
        pid: Int,
        pm: PackageManager,
        guestPkg: String,
        allowRoot: Boolean
    ): Triple<String, String?, ApplicationInfo?>? {
        val mapsLines = runCatching {
            val f = File("/proc/$pid/maps")
            if (f.canRead()) f.readLines() else emptyList()
        }.getOrDefault(emptyList()).let { direct ->
            if (direct.isNotEmpty()) direct
            else if (allowRoot) Shell.suLines("grep '\\.apk' /proc/$pid/maps 2>/dev/null | head -n 30")
            else emptyList()
        }
        if (mapsLines.isEmpty()) return null

        val apkPaths = LinkedHashSet<String>()
        for (line in mapsLines) {
            if (!line.contains(".apk")) continue
            val pathStart = line.indexOf('/')
            if (pathStart < 0) continue
            val rawPath = line.substring(pathStart).substringBefore(".apk") + ".apk"
            if (rawPath.startsWith("/system/") || rawPath.startsWith("/product/") || rawPath.startsWith("/apex/")) continue
            apkPaths.add(rawPath)
        }

        val preferredApk = apkPaths.firstOrNull { it.contains(guestPkg) } ?: apkPaths.firstOrNull() ?: return null
        val pkgInfo = runCatching { pm.getPackageArchiveInfo(preferredApk, 0) }.getOrNull()
        val ai = pkgInfo?.applicationInfo
        if (ai != null) {
            ai.sourceDir = preferredApk
            ai.publicSourceDir = preferredApk
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
            return Triple(preferredApk, label, ai)
        }
        return Triple(preferredApk, null, null)
    }

    /**
     * 解析 Android `ps` 输出的 UID 字段：
     * 支持纯数字 (`10245`)、`u0_a245` (`10245`)、`u10_a245` (`1010245`)、`system` (`1000`)、`root` (`0`) 等格式。
     */
    private fun parseAndroidUid(token: String): Int {
        val clean = token.trim()
        clean.toIntOrNull()?.let { return it }
        if (clean == "root") return 0
        if (clean == "system") return 1000
        if (clean == "shell") return 2000
        if (clean.startsWith("u") && clean.contains("_")) {
            val userPart = clean.substringAfter('u').substringBefore('_').toIntOrNull() ?: 0
            val rest = clean.substringAfter('_')
            return when {
                rest.startsWith("a") -> {
                    val appOffset = rest.removePrefix("a").toIntOrNull() ?: return -1
                    userPart * 100000 + 10000 + appOffset
                }
                rest.startsWith("i") -> {
                    val isoOffset = rest.removePrefix("i").toIntOrNull() ?: return -1
                    userPart * 100000 + 99000 + isoOffset
                }
                rest.startsWith("s") -> {
                    val sysOffset = rest.removePrefix("s").toIntOrNull() ?: return -1
                    userPart * 100000 + sysOffset
                }
                else -> -1
            }
        }
        return -1
    }

    private fun detectProcess64Bit(pid: Int, appInfo: ApplicationInfo?): Boolean {
        appInfo?.nativeLibraryDir?.let { dir ->
            if (dir.contains("arm64") || dir.contains("x86_64")) return true
            if (dir.contains("arm/") || dir.endsWith("/arm") || dir.contains("x86")) return false
        }
        val exeLink = runCatching { File("/proc/$pid/exe").canonicalPath }.getOrNull()
        if (!exeLink.isNullOrEmpty()) {
            if (exeLink.contains("64")) return true
            if (exeLink.endsWith("app_process32")) return false
        }
        return true
    }

    private fun readRssFromProc(pid: Int): Long {
        return try {
            val status = File("/proc/$pid/status").readText()
            val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
            line.replace("VmRSS:", "").replace("kB", "").trim().toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    fun sizeText(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}

/** 进程应用图标缓存（支持宿主已安装应用、子进程包名、共享 UID 应用及虚拟空间未安装客体 APK 图标提取） */
object AppIconCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    fun getBitmap(
        context: Context,
        packageName: String,
        apkPath: String = "",
        uid: Int = 0
    ): Bitmap? {
        val cleanPkg = packageName.trim()
        val basePkg = cleanPkg.substringBefore(':').trim()
        val cacheKey = "$cleanPkg|$apkPath|$uid"
        bitmapCache[cacheKey]?.let { return it }
        if (cleanPkg.isNotEmpty()) bitmapCache[cleanPkg]?.let { return it }
        if (basePkg.isNotEmpty()) bitmapCache[basePkg]?.let { return it }

        val pm = context.packageManager
        val drawable: Drawable? = runCatching {
            if (cleanPkg.isNotEmpty()) pm.getApplicationIcon(cleanPkg) else null
        }.getOrNull() ?: runCatching {
            if (basePkg.isNotEmpty() && basePkg != cleanPkg) pm.getApplicationIcon(basePkg) else null
        }.getOrNull() ?: runCatching {
            if (apkPath.isNotEmpty()) {
                val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0)
                val ai = pkgInfo?.applicationInfo
                if (ai != null) {
                    ai.sourceDir = apkPath
                    ai.publicSourceDir = apkPath
                    ai.loadIcon(pm)
                } else null
            } else null
        }.getOrNull() ?: runCatching {
            if (uid >= 1000) {
                pm.getPackagesForUid(uid)
                    ?.firstOrNull { it != context.packageName }
                    ?.let { pm.getApplicationIcon(it) }
            } else null
        }.getOrNull() ?: runCatching {
            if (basePkg.isNotEmpty()) {
                val ai = pm.getApplicationInfo(basePkg, PackageManager.GET_META_DATA)
                ai.loadIcon(pm)
            } else null
        }.getOrNull()

        if (drawable == null) return null
        return try {
            val bmp = drawableToBitmap(drawable)
            bitmapCache[cacheKey] = bmp
            if (cleanPkg.isNotEmpty()) bitmapCache[cleanPkg] = bmp
            if (basePkg.isNotEmpty()) bitmapCache[basePkg] = bmp
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    fun get(
        context: Context,
        packageName: String,
        apkPath: String = "",
        uid: Int = 0
    ): ImageBitmap? {
        val cleanPkg = packageName.trim()
        val basePkg = cleanPkg.substringBefore(':').trim()
        val cacheKey = "$cleanPkg|$apkPath|$uid"
        cache[cacheKey]?.let { return it }
        if (cleanPkg.isNotEmpty()) cache[cleanPkg]?.let { return it }
        if (basePkg.isNotEmpty()) cache[basePkg]?.let { return it }

        val bmp = getBitmap(context, packageName, apkPath, uid) ?: return null
        return try {
            val img = bmp.asImageBitmap()
            cache[cacheKey] = img
            if (cleanPkg.isNotEmpty()) cache[cleanPkg] = img
            if (basePkg.isNotEmpty()) cache[basePkg] = img
            img
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && !d.bitmap.isRecycled) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceIn(48, 192) ?: 112
        val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceIn(48, 192) ?: 112
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return b
    }
}
