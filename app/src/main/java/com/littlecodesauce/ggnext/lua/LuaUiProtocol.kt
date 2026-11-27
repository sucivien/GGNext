package com.littlecodesauce.ggnext.lua

import org.json.JSONArray
import org.json.JSONObject

/**
 * GGNext Lua 5.5.1 交互弹窗与声明式 UI 协议模型。
 *
 * 为两类场景提供统一数据模型：
 * 1. 原版 GameGuardian 交互弹窗 (`gg.alert`, `gg.choice`, `gg.multiChoice`, `gg.prompt`, `gg.makeRequest` 联网确认)
 * 2. 面向混合插件系统（「Lua 写界面 + C++ 写功能」）的声明式 UI 节点树 (`ui.*`)
 */
sealed class LuaDialogRequest {
    abstract val requestId: Long

    data class Alert(
        override val requestId: Long,
        val text: String,
        val positive: String = "确定",
        val negative: String? = null,
        val neutral: String? = null
    ) : LuaDialogRequest()

    data class Choice(
        override val requestId: Long,
        val items: List<String>,
        val selectedIndex: Int = -1,
        val message: String? = null
    ) : LuaDialogRequest()

    data class MultiChoice(
        override val requestId: Long,
        val items: List<String>,
        val initialSelection: Set<Int> = emptySet(),
        val message: String? = null
    ) : LuaDialogRequest()

    data class PromptField(
        val index: Int,
        val label: String,
        val defaultValue: String,
        val type: String, // text, number, checkbox, file, path, new_file, setting, speed, seekbar
        val seekMin: Double = 0.0,
        val seekMax: Double = 100.0,
        val isSeekBar: Boolean = false
    )

    data class Prompt(
        override val requestId: Long,
        val fields: List<PromptField>
    ) : LuaDialogRequest()

    data class NetworkConsent(
        override val requestId: Long,
        val host: String,
        val url: String
    ) : LuaDialogRequest()
}

/**
 * 声明式 UI 节点（由 Lua `ui.*` 构建并序列化，宿主映射至 GGNext GgPrimitives / Rose Compose 控件）。
 */
data class LuaUiNode(
    val id: String? = null,
    val type: String,
    val text: String? = null,
    val label: String? = null,
    val subtitle: String? = null,
    val placeholder: String? = null,
    val value: String? = null,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val filled: Boolean = false,
    val monospace: Boolean = false,
    val tone: String = "default", // default, primary, success, warning, danger, dim
    val min: Float = 0f,
    val max: Float = 100f,
    val sliderValue: Float = 0f,
    val spacing: Int = 8,
    val size: Int = 8,
    val iconName: String? = null,
    val imagePath: String? = null,
    val items: List<String> = emptyList(),
    val selectedIndex: Int = -1,
    val onClickId: String? = null,
    val onChangeId: String? = null,
    val onSelectId: String? = null,
    val onSubmitId: String? = null,
    val children: List<LuaUiNode> = emptyList()
)

data class LuaUiPanelState(
    val title: String = "脚本面板",
    val subtitle: String = "",
    val dismissible: Boolean = true,
    val root: LuaUiNode
)

data class LuaUiEvent(
    val type: String, // "event" | "closed"
    val callbackId: String? = null,
    val payloadJson: String = "null"
)

enum class LuaLogLevel(val tag: String, val label: String) {
    PRINT("PRINT", "Print"),
    INFO("INFO", "Info"),
    WARN("WARN", "Warn"),
    ERROR("ERROR", "Error")
}

data class LuaLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LuaLogLevel,
    val message: String
) {
    fun formatShortTime(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
        return String.format(
            java.util.Locale.US,
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    fun formatExportLine(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
        val ts = String.format(
            java.util.Locale.US,
            "%02d:%02d:%02d.%03d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND)
        )
        return "[$ts] [${level.tag}] $message"
    }
}

data class LuaScriptState(
    val running: Boolean = false,
    val scriptPath: String = "",
    val scriptName: String = "",
    val lastError: String = "",
    val logs: List<LuaLogEntry> = emptyList(),
    val uiButtonVisible: Boolean = false,
    val sxOffsetX: Float = 0f,
    val sxOffsetY: Float = 0f,
    val stopOffsetX: Float = 0f,
    val stopOffsetY: Float = 0f,
    val activeDialog: LuaDialogRequest? = null,
    val activeUiPanel: LuaUiPanelState? = null
)

object LuaUiProtocol {

    fun parsePanel(obj: JSONObject): LuaUiPanelState? {
        val rootObj = obj.optJSONObject("root") ?: return null
        return LuaUiPanelState(
            title = obj.optString("title", "脚本面板").ifBlank { "脚本面板" },
            subtitle = obj.optString("subtitle", ""),
            dismissible = obj.optBoolean("dismissible", true),
            root = parseNode(rootObj)
        )
    }

    fun parseNode(obj: JSONObject): LuaUiNode {
        val type = obj.optString("type", "text").lowercase()
        val childrenArr = obj.optJSONArray("children")
        val children = if (childrenArr != null) {
            List(childrenArr.length()) { idx ->
                val child = childrenArr.optJSONObject(idx)
                if (child != null) parseNode(child) else LuaUiNode(type = "text", text = childrenArr.optString(idx))
            }
        } else {
            emptyList()
        }

        val itemsList = ArrayList<String>()
        val itemsArr = obj.optJSONArray("items")
        if (itemsArr != null) {
            for (i in 0 until itemsArr.length()) {
                val el = itemsArr.opt(i)
                if (el is JSONObject) {
                    itemsList.add(el.optString("label", el.optString("text", el.toString())))
                } else if (el != null && el != JSONObject.NULL) {
                    itemsList.add(el.toString())
                }
            }
        }

        val rawValue = obj.opt("value")
        val strValue = when (rawValue) {
            null, JSONObject.NULL -> null
            else -> rawValue.toString()
        }
        val numValue = when (rawValue) {
            is Number -> rawValue.toFloat()
            is String -> rawValue.toFloatOrNull() ?: obj.optDouble("min", 0.0).toFloat()
            else -> obj.optDouble("min", 0.0).toFloat()
        }

        return LuaUiNode(
            id = obj.optString("id", "").takeIf { it.isNotEmpty() },
            type = type,
            text = if (obj.has("text") && !obj.isNull("text")) obj.optString("text") else null,
            label = if (obj.has("label") && !obj.isNull("label")) obj.optString("label") else null,
            subtitle = if (obj.has("subtitle") && !obj.isNull("subtitle")) obj.optString("subtitle") else null,
            placeholder = if (obj.has("placeholder") && !obj.isNull("placeholder")) obj.optString("placeholder") else null,
            value = strValue,
            checked = obj.optBoolean("checked", rawValue as? Boolean ?: false),
            enabled = obj.optBoolean("enabled", true),
            filled = obj.optBoolean("filled", obj.optString("style", "") == "primary"),
            monospace = obj.optBoolean("monospace", type == "code"),
            tone = obj.optString("tone", "default").lowercase(),
            min = obj.optDouble("min", 0.0).toFloat(),
            max = obj.optDouble("max", 100.0).toFloat(),
            sliderValue = numValue,
            spacing = obj.optInt("spacing", 8).coerceIn(0, 48),
            size = obj.optInt("size", 8).coerceIn(1, 96),
            iconName = obj.optString("name", obj.optString("icon", "")).takeIf { it.isNotEmpty() },
            imagePath = obj.optString("path", obj.optString("src", "")).takeIf { it.isNotEmpty() },
            items = itemsList,
            selectedIndex = obj.optInt("selectedIndex", -1),
            onClickId = obj.optString("onClickId", "").takeIf { it.isNotEmpty() },
            onChangeId = obj.optString("onChangeId", "").takeIf { it.isNotEmpty() },
            onSelectId = obj.optString("onSelectId", "").takeIf { it.isNotEmpty() },
            onSubmitId = obj.optString("onSubmitId", "").takeIf { it.isNotEmpty() },
            children = children
        )
    }

    /**
     * 按节点 `id` 局部增量刷新声明式 UI 树（支持局部更新文本、值、开关状态、列表或子节点）。
     */
    fun patchNode(node: LuaUiNode, targetId: String, patch: JSONObject): LuaUiNode {
        if (node.id == targetId) {
            val updatedChildren = if (patch.has("children")) {
                val arr = patch.optJSONArray("children")
                if (arr != null) {
                    List(arr.length()) { i ->
                        arr.optJSONObject(i)?.let { parseNode(it) } ?: LuaUiNode(type = "text", text = arr.optString(i))
                    }
                } else node.children
            } else node.children

            val updatedItems = if (patch.has("items")) {
                val arr = patch.optJSONArray("items")
                if (arr != null) List(arr.length()) { i -> arr.optString(i) } else node.items
            } else node.items

            val hasVal = patch.has("value") && !patch.isNull("value")
            val rawVal = if (hasVal) patch.opt("value") else null
            return node.copy(
                text = if (patch.has("text")) patch.optString("text") else node.text,
                label = if (patch.has("label")) patch.optString("label") else node.label,
                subtitle = if (patch.has("subtitle")) patch.optString("subtitle") else node.subtitle,
                placeholder = if (patch.has("placeholder")) patch.optString("placeholder") else node.placeholder,
                value = if (hasVal) rawVal.toString() else node.value,
                checked = if (patch.has("checked")) patch.optBoolean("checked", node.checked) else node.checked,
                enabled = if (patch.has("enabled")) patch.optBoolean("enabled", node.enabled) else node.enabled,
                filled = if (patch.has("filled")) patch.optBoolean("filled", node.filled) else node.filled,
                tone = if (patch.has("tone")) patch.optString("tone", node.tone).lowercase() else node.tone,
                sliderValue = if (rawVal is Number) rawVal.toFloat() else node.sliderValue,
                items = updatedItems,
                selectedIndex = if (patch.has("selectedIndex")) patch.optInt("selectedIndex", node.selectedIndex) else node.selectedIndex,
                onClickId = if (patch.has("onClickId")) patch.optString("onClickId").takeIf { it.isNotEmpty() } else node.onClickId,
                onChangeId = if (patch.has("onChangeId")) patch.optString("onChangeId").takeIf { it.isNotEmpty() } else node.onChangeId,
                onSelectId = if (patch.has("onSelectId")) patch.optString("onSelectId").takeIf { it.isNotEmpty() } else node.onSelectId,
                children = updatedChildren
            )
        }
        if (node.children.isEmpty()) return node
        return node.copy(children = node.children.map { patchNode(it, targetId, patch) })
    }

    /**
     * 解析原版 GG `gg.prompt` 的 SeekBar 提示语法：`"标签名称 [min; max]"`。
     */
    fun parseSeekBarSpec(promptText: String): Triple<String, Double, Double>? {
        val m = Regex("""^(.*?)\s*\[\s*(-?\d+(?:\.\d+)?)\s*;\s*(-?\d+(?:\.\d+)?)\s*]\s*$""").find(promptText.trim())
            ?: return null
        val label = m.groupValues[1].trim()
        val minVal = m.groupValues[2].toDoubleOrNull() ?: return null
        val maxVal = m.groupValues[3].toDoubleOrNull() ?: return null
        if (maxVal <= minVal) return null
        return Triple(label.ifEmpty { promptText }, minVal, maxVal)
    }

    /**
     * 将 JSON 值转换为 Lua 兼容 JSON 字面量。
     */
    fun encodePayload(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is String -> JSONObject.quote(value)
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
