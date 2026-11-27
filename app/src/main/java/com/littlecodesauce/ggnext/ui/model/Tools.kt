package com.littlecodesauce.ggnext.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.ui.graphics.vector.ImageVector
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.GgIcons

/**
 * 工具栏动作项定义与元数据
 */
enum class ToolId(val label: String) {
    OFFSET("偏移量计算器"),
    EDIT("编辑所选"),
    SEARCH("精确搜索"),
    FUZZY("模糊搜索"),
    MASK("蒙版搜索"),
    POINTER("指针搜索"),
    SAVE("保存所选"),
    REMOVE("移除"),
    REVERT("恢复所选"),
    SELECT_ALL("全选"),
    INVERT("反选"),
    CLEAR_SEL("清除选择"),
    COPY_SEARCH("以联合搜索方式复制"),
    LOAD_RESULTS("选定为搜索结果"),
    LOAD_CHANGED("仅保留有变化的结果"),
    CALC_OFFSET("计算偏移、异或"),
    ADDRESS("更改地址/类型"),
    LOAD("从文件载入"),
    SAVE_FILE("保存到文件"),
    GOTO("转到地址"),
    COPY_MEM("复制内存"),
    ALLOCATE("分配内存页"),
    BACK("后退"),
    FORWARD("前进"),
    EXPORT("导出选定项"),
    SPEED("变速"),
    FILTER("过滤搜索结果"),
    DUMP("导出内存"),
    BREAKPOINT("硬件断点"),
    WRITE_ASM("写入汇编"),
    BP_ADD("添加断点"),
    BP_PAUSE_ALL("暂停/恢复断点"),
    BP_CLEAR_HITS("清空命中"),
    BP_DELETE("删除断点"),
    EXEC_SCRIPT("执行脚本"),
    MENU("工具栏顺序")
}

fun toolIcon(id: ToolId): ImageVector = when (id) {
    ToolId.OFFSET -> GgIcons.Calculator
    ToolId.EDIT -> GgIcons.TooltipEdit
    ToolId.SEARCH -> GgIcons.Magnify
    ToolId.FUZZY -> GgIcons.FuzzySearch
    ToolId.MASK -> GgIcons.MaskSearch
    ToolId.POINTER -> GgIcons.PointerSearch
    ToolId.SAVE -> Icons.Rounded.Save
    ToolId.REMOVE -> Icons.Rounded.Delete
    ToolId.REVERT -> Icons.AutoMirrored.Rounded.Undo
    ToolId.SELECT_ALL -> GgIcons.SelectAll
    ToolId.INVERT -> GgIcons.SelectInverse
    ToolId.CLEAR_SEL -> GgIcons.SelectOff
    ToolId.COPY_SEARCH -> Icons.Rounded.ContentCopy
    ToolId.LOAD_RESULTS -> GgIcons.SearchSelected
    ToolId.LOAD_CHANGED -> GgIcons.SearchChanged
    ToolId.CALC_OFFSET -> GgIcons.VectorDifference
    ToolId.ADDRESS -> GgIcons.CursorMover
    ToolId.LOAD -> Icons.Rounded.Upload
    ToolId.SAVE_FILE -> Icons.Rounded.Download
    ToolId.GOTO -> Icons.Rounded.Place
    ToolId.COPY_MEM -> GgIcons.ContentDuplicate
    ToolId.ALLOCATE -> GgIcons.CodeBraces
    ToolId.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
    ToolId.FORWARD -> Icons.AutoMirrored.Rounded.ArrowForward
    ToolId.EXPORT -> GgIcons.DownloadChecked
    ToolId.SPEED -> GgIcons.Speedometer
    ToolId.FILTER -> Icons.Rounded.FilterAlt
    ToolId.DUMP -> Icons.Rounded.Folder
    ToolId.BREAKPOINT -> Icons.Rounded.BugReport
    ToolId.WRITE_ASM -> Icons.Rounded.Code
    ToolId.BP_ADD -> Icons.Rounded.Add
    ToolId.BP_PAUSE_ALL -> Icons.Rounded.Pause
    ToolId.BP_CLEAR_HITS -> Icons.Rounded.CleaningServices
    ToolId.BP_DELETE -> Icons.Rounded.Delete
    ToolId.EXEC_SCRIPT -> GgIcons.AngleBrackets
    ToolId.MENU -> GgIcons.MenuThreeLines
}

/** 默认固定工具栏（右侧常驻） */
val DEFAULT_PINNED_TOOLS = listOf(
    ToolId.EXEC_SCRIPT,
    ToolId.MENU
)

/** 搜索列表默认流动工具栏 */
val DEFAULT_TOOLS_SEARCH = listOf(
    ToolId.EDIT,
    ToolId.SEARCH,
    ToolId.FUZZY,
    ToolId.MASK,
    ToolId.POINTER,
    ToolId.OFFSET,
    ToolId.SAVE,
    ToolId.REMOVE,
    ToolId.REVERT,
    ToolId.SELECT_ALL,
    ToolId.INVERT,
    ToolId.CLEAR_SEL,
    ToolId.FILTER,
    ToolId.SPEED,
    ToolId.COPY_SEARCH,
    ToolId.LOAD_RESULTS,
    ToolId.LOAD_CHANGED,
    ToolId.CALC_OFFSET,
    ToolId.EXPORT
)

/** 保存列表默认流动工具栏 */
val DEFAULT_TOOLS_SAVED = listOf(
    ToolId.EDIT,
    ToolId.OFFSET,
    ToolId.ADDRESS,
    ToolId.LOAD,
    ToolId.SAVE_FILE,
    ToolId.REVERT,
    ToolId.REMOVE,
    ToolId.SELECT_ALL,
    ToolId.INVERT,
    ToolId.CLEAR_SEL,
    ToolId.COPY_SEARCH,
    ToolId.LOAD_RESULTS,
    ToolId.CALC_OFFSET,
    ToolId.EXPORT
)

/** 内存页默认流动工具栏 */
val DEFAULT_TOOLS_MEMORY = listOf(
    ToolId.SELECT_ALL,
    ToolId.INVERT,
    ToolId.EDIT,
    ToolId.WRITE_ASM,
    ToolId.BREAKPOINT,
    ToolId.ALLOCATE,
    ToolId.REMOVE,
    ToolId.SEARCH,
    ToolId.SAVE,
    ToolId.GOTO,
    ToolId.OFFSET,
    ToolId.COPY_MEM,
    ToolId.BACK,
    ToolId.FORWARD,
    ToolId.DUMP
)

/** 调试页默认流动工具栏（与搜索/保存/内存页共用统一工具栏与选择图标） */
val DEFAULT_TOOLS_DEBUG = listOf(
    ToolId.BP_ADD,
    ToolId.BP_PAUSE_ALL,
    ToolId.BP_CLEAR_HITS,
    ToolId.BP_DELETE,
    ToolId.SELECT_ALL,
    ToolId.INVERT,
    ToolId.CLEAR_SEL
)

val ALL_TOOLS = ToolId.entries.toList()

/**
 * 核心功能：工具按钮可用性判定（根据当前界面状况实时计算可用/不可用状态，不可用时按钮图标呈灰色）
 */
fun isToolEnabled(id: ToolId, tab: NextTab, ui: Ui): Boolean {
    val hasSearchItems = ui.rawResults.isNotEmpty() || ui.results.isNotEmpty()
    val hasSavedItems = ui.saved.isNotEmpty()
    val hasMemoryRows = ui.memoryRows.isNotEmpty()
    val hasBreakpoints = ui.breakpoints.isNotEmpty()

    return when (id) {
        // 始终可用的全局功能
        ToolId.SEARCH, ToolId.FUZZY, ToolId.MASK, ToolId.POINTER,
        ToolId.OFFSET, ToolId.SPEED, ToolId.FILTER, ToolId.GOTO,
        ToolId.ALLOCATE, ToolId.LOAD, ToolId.BREAKPOINT, ToolId.WRITE_ASM,
        ToolId.EXEC_SCRIPT, ToolId.MENU -> true

        // 保存所选 / 选定为搜索结果 / 复制搜索 / 导出等
        ToolId.SAVE, ToolId.LOAD_RESULTS, ToolId.LOAD_CHANGED, ToolId.COPY_SEARCH,
        ToolId.EXPORT, ToolId.CALC_OFFSET -> {
            when (tab) {
                NextTab.SEARCH_LIST -> hasSearchItems
                NextTab.SAVED_LIST -> hasSavedItems
                NextTab.MEMORY -> hasMemoryRows
                else -> false
            }
        }

        // 编辑 / 移除 / 恢复所选
        ToolId.EDIT -> {
            when (tab) {
                NextTab.SEARCH_LIST -> hasSearchItems
                NextTab.SAVED_LIST -> hasSavedItems
                NextTab.MEMORY -> hasMemoryRows
                else -> false
            }
        }
        ToolId.REMOVE, ToolId.REVERT -> {
            when (tab) {
                NextTab.SEARCH_LIST -> hasSearchItems
                NextTab.SAVED_LIST -> hasSavedItems
                NextTab.MEMORY -> hasMemoryRows
                else -> false
            }
        }

        // 调试页断点操作
        ToolId.BP_ADD -> true
        ToolId.BP_PAUSE_ALL, ToolId.BP_CLEAR_HITS, ToolId.BP_DELETE -> hasBreakpoints

        // 全选 / 反选 / 清除选择
        ToolId.SELECT_ALL, ToolId.INVERT, ToolId.CLEAR_SEL -> {
            when (tab) {
                NextTab.SEARCH_LIST -> hasSearchItems
                NextTab.SAVED_LIST -> hasSavedItems
                NextTab.MEMORY -> hasMemoryRows
                NextTab.DEBUG -> hasBreakpoints
                else -> false
            }
        }

        // 保存文件
        ToolId.SAVE_FILE -> {
            when (tab) {
                NextTab.SAVED_LIST -> hasSavedItems
                NextTab.SEARCH_LIST -> hasSearchItems
                else -> false
            }
        }

        // 更改地址/类型
        ToolId.ADDRESS -> {
            when (tab) {
                NextTab.SAVED_LIST -> hasSavedItems
                NextTab.SEARCH_LIST -> hasSearchItems
                NextTab.MEMORY -> hasMemoryRows
                else -> false
            }
        }

        // 复制内存 / 导出内存
        ToolId.COPY_MEM, ToolId.DUMP -> {
            when (tab) {
                NextTab.MEMORY -> hasMemoryRows
                NextTab.SEARCH_LIST -> hasSearchItems
                NextTab.SAVED_LIST -> hasSavedItems
                else -> true
            }
        }

        // 内存页前进 / 后退历史
        ToolId.BACK -> ui.memoryHistoryBack.isNotEmpty()
        ToolId.FORWARD -> ui.memoryHistoryForward.isNotEmpty()
    }
}
