package com.littlecodesauce.ggnext.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.littlecodesauce.ggnext.engine.Formatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("gg_settings")

/**
 * 设置项定义（仅保留已完整实现的真实配置项，值持久化到 DataStore）。
 */
data class SettingSpec(
    val id: String,
    val title: String,
    val group: String,
    val kind: Kind,
    val default: Any,
    val choices: List<Pair<Int, String>> = emptyList(),
    val min: Long = 0,
    val max: Long = 100,
    val help: String = ""
) {
    enum class Kind { SWITCH, CHOICE, NUMBER, ACTION, TEXT }
}

object SettingKeys {
    const val GROUP_ACTION = "操作"
    const val GROUP_GAME = "内存设置"
    const val GROUP_GLOBAL = "通用设置"
    const val GROUP_UI = "界面设置"
    const val GROUP_OTHER = "其余选项"

    const val KEY_HISTORY_SEARCH_JSON = "history_search_json"
    const val KEY_HISTORY_GOTO_JSON = "history_goto_json"
    const val KEY_HISTORY_SCRIPT_JSON = "history_script_json"
    const val KEY_SAVED_LIST_JSON = "saved_list_json"
}

object SettingsSpecs {

    val valueFormatChoices: List<Pair<Int, String>> = listOf(
        Formatter.F_HEX to "HEX",
        Formatter.F_DWORD to "Dword",
        Formatter.F_FLOAT to "Float",
        Formatter.F_DOUBLE to "Double",
        Formatter.F_WORD to "Word",
        Formatter.F_BYTE to "Byte",
        Formatter.F_QWORD to "Qword",
        Formatter.F_XOR to "Xor",
        Formatter.F_RHEX to "HEX 逆序",
        Formatter.F_STRING to "字符串",
        Formatter.F_JAVA to "Java 字符串"
    )

    val game = listOf(
        SettingSpec("ranges", "选择内存范围", SettingKeys.GROUP_GAME, SettingSpec.Kind.ACTION, 0,
            help = "选择需要搜索的内存区域，如 Jh(Java 堆)、Ch(C++ 堆)、Xa(可执行) 等。"),
        SettingSpec("autopause", "自动暂停游戏", SettingKeys.GROUP_GAME, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "否", 1 to "是")),
        SettingSpec("freeze_interval", "冻结间隔", SettingKeys.GROUP_GAME, SettingSpec.Kind.NUMBER, 33L, min = 5L, max = 500L),
        SettingSpec("speedhack_speeds", "变速倍率", SettingKeys.GROUP_GAME, SettingSpec.Kind.ACTION, 0)
    )

    val global = listOf(
        SettingSpec("work_mode", "工作模式", SettingKeys.GROUP_GLOBAL, SettingSpec.Kind.TEXT, "root")
    )

    val ui = listOf(
        SettingSpec("history_limit", "历史记录条数限制", SettingKeys.GROUP_UI, SettingSpec.Kind.NUMBER, 500L, min = 0, max = 100000L),
        SettingSpec("toolbar_buttons", "工具栏按钮", SettingKeys.GROUP_UI, SettingSpec.Kind.ACTION, 0),
        SettingSpec("icons_size", "悬浮窗大小", SettingKeys.GROUP_UI, SettingSpec.Kind.NUMBER, 48L, min = 12, max = 96),
        SettingSpec("language", "语言", SettingKeys.GROUP_UI, SettingSpec.Kind.CHOICE, 0,
            listOf(0 to "简体中文", 1 to "English")),
        SettingSpec("theme_preset", "主题配色", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "ROSE"),
        SettingSpec("dark_mode", "深色模式", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "DARK"),
        SettingSpec("dynamic_color", "动态壁纸取色", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0"),
        SettingSpec("font_scale", "界面字体缩放", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0.88"),
        SettingSpec("dialog_corner", "弹窗圆角", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "12"),
        SettingSpec("bg_opacity", "背景透明度", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0.96"),
        SettingSpec("panel_scale", "页面屏幕占比", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "1.00"),
        SettingSpec("glass_enabled", "毛玻璃效果", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "0"),
        SettingSpec("glass_blur", "毛玻璃强度", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "16"),
        SettingSpec("show_process_icon", "悬浮球显示目标进程图标", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "1"),
        SettingSpec("skeleton", "列表动画", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "1"),
        SettingSpec("custom_font_path", "自定义字体路径", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, ""),
        SettingSpec("custom_font_name", "自定义字体名称", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, ""),
        SettingSpec("managed_fonts_json", "已导入字体列表", SettingKeys.GROUP_UI, SettingSpec.Kind.TEXT, "")
    )

    val other = listOf(
        SettingSpec("clear_history", "清除输入历史", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("show_logcat", "显示日志", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("about", "关于", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0),
        SettingSpec("changelog", "更新日志", SettingKeys.GROUP_OTHER, SettingSpec.Kind.ACTION, 0)
    )

    val all: List<SettingSpec> = game + global + ui + other

    fun byId(id: String): SettingSpec? = all.firstOrNull { it.id == id }

    fun grouped(): List<Pair<String, List<SettingSpec>>> = listOf(
        SettingKeys.GROUP_GLOBAL to global,
        SettingKeys.GROUP_UI to ui,
        SettingKeys.GROUP_GAME to game,
        SettingKeys.GROUP_ACTION to listOf(
            SettingSpec("help", "帮助", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("reset_config", "重置设置", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("exit", "退出", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0),
            SettingSpec("kill_game", "终止此进程", SettingKeys.GROUP_ACTION, SettingSpec.Kind.ACTION, 0)
        ),
        SettingKeys.GROUP_OTHER to other
    )

    /** 默认显示掩码 */
    const val DEFAULT_FORMAT_MASK: Int = Formatter.F_HEX or Formatter.F_DWORD or Formatter.F_FLOAT
}

class SettingsRepository(private val context: Context) {

    private object K {
        val valueFormatMask = intPreferencesKey("value_format_mask")
        val savedFormatMask = intPreferencesKey("saved_format_mask")
        val memoryFormatMask = intPreferencesKey("memory_format_mask")
        val rangeMode = intPreferencesKey("range_mode")
        val memoryFrom = longPreferencesKey("memory_from")
        val memoryTo = longPreferencesKey("memory_to")
        val fuzzyEqual = intPreferencesKey("fuzzy_equal")
        val toolbarButtons = intPreferencesKey("toolbar_buttons")
        val hotPointX = intPreferencesKey("hot_x")
        val hotPointY = intPreferencesKey("hot_y")
        val lastPid = intPreferencesKey("last_pid")
        val lastPackage = stringPreferencesKey("last_package")
        val speed = longPreferencesKey("speed_value")
        fun key(id: String) = stringPreferencesKey("cfg_$id")
    }

    val flow: Flow<Preferences> = context.dataStore.data

    fun valueFormatMask(): Flow<Int?> = flow.map { it[K.valueFormatMask] }
    fun savedFormatMask(): Flow<Int> = flow.map { it[K.savedFormatMask] ?: 0 }
    fun memoryFormatMask(): Flow<Int?> = flow.map { it[K.memoryFormatMask] }

    fun settingValue(id: String): Flow<String?> = flow.map { it[K.key(id)] }

    suspend fun setInt(id: String, value: Int) {
        context.dataStore.edit { K.key(id).let { k -> it[k] = value.toString() } }
    }

    suspend fun setString(id: String, value: String) {
        context.dataStore.edit { it[K.key(id)] = value }
    }

    suspend fun setStrings(pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        context.dataStore.edit { prefs ->
            pairs.forEach { (id, value) ->
                prefs[K.key(id)] = value
            }
        }
    }

    suspend fun removeKeys(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            ids.forEach { id ->
                prefs.remove(K.key(id))
            }
        }
    }

    suspend fun setValueFormatMask(mask: Int) {
        context.dataStore.edit { it[K.valueFormatMask] = mask }
    }

    suspend fun setSavedFormatMask(mask: Int) {
        context.dataStore.edit { it[K.savedFormatMask] = mask }
    }

    suspend fun setMemoryFormatMask(mask: Int) {
        context.dataStore.edit { it[K.memoryFormatMask] = mask }
    }

    suspend fun saveTarget(pid: Int, pkg: String) {
        context.dataStore.edit {
            it[K.lastPid] = pid
            it[K.lastPackage] = pkg
        }
    }

    suspend fun saveHotPoint(x: Int, y: Int) {
        context.dataStore.edit {
            it[K.hotPointX] = x
            it[K.hotPointY] = y
        }
    }

    fun settingsSnapshot(): Flow<Map<String, String>> = flow.map { prefs ->
        val out = LinkedHashMap<String, String>()
        SettingsSpecs.all.forEach { spec ->
            out[spec.id] = prefs[K.key(spec.id)] ?: spec.default.toString()
        }
        prefs.asMap().forEach { (prefKey, prefVal) ->
            val name = prefKey.name
            if (name.startsWith("cfg_")) {
                out[name.removePrefix("cfg_")] = prefVal.toString()
            }
        }
        out
    }

    companion object {
        fun valueOf(prefs: Map<String, String>, spec: SettingSpec): String {
            return prefs[spec.id] ?: spec.default.toString()
        }

        fun intOf(prefs: Map<String, String>, spec: SettingSpec): Int =
            valueOf(prefs, spec).toIntOrNull() ?: (spec.default as? Int ?: 0)

        fun longOf(prefs: Map<String, String>, spec: SettingSpec): Long =
            valueOf(prefs, spec).toLongOrNull() ?: (spec.default as? Long ?: 0L)
    }
}
