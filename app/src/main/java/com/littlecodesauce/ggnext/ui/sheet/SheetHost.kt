package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.Scrim
import com.littlecodesauce.ggnext.ui.components.dialogEnter
import com.littlecodesauce.ggnext.ui.components.dialogExit
import com.littlecodesauce.ggnext.ui.theme.LocalAppLang

/** 弹窗宿主：遮罩淡入 + 卡片缩放入场，返回键按栈退回上一层弹窗 */
@Composable
fun SheetHost(ui: Ui) {
    val open = ui.sheet != Sheet.NONE
    val lang = LocalAppLang.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Scrim { Session.popSheet() }
        }
        AnimatedVisibility(
            visible = open,
            enter = dialogEnter(),
            exit = dialogExit(),
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { Session.popSheet() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { /* 消费所有弹窗内部空白处点击，仅点击弹窗外遮罩时关闭 */ }
                ) {
                    when (ui.sheet) {
                        Sheet.PROCESS -> ProcessSheet(ui)
                        Sheet.SEARCH -> SearchSheet(ui)
                        Sheet.FUZZY -> FuzzySearchSheet(ui)
                        Sheet.MASK_SEARCH -> MaskSearchSheet(ui)
                        Sheet.POINTER_SEARCH -> PointerSearchSheet(ui)
                        Sheet.FILTER -> FilterSearchSheet(ui)
                        Sheet.REGION -> RegionSheet(ui)
                        Sheet.VALUE_FORMAT -> FormatSheet(ui)
                        Sheet.EDITOR -> EditSheet(ui)
                        Sheet.OFFSET -> OffsetSheet(ui)
                        Sheet.CALC_OFFSET -> CalcOffsetXorSheet(ui)
                        Sheet.REMOVE -> RemoveSheet(ui)
                        Sheet.TOOLBAR_ORDER -> ToolbarSheet(ui)
                        Sheet.TOOLBAR_PICK -> ToolbarPickSheet(ui)
                        Sheet.GOTO -> GotoSheet()
                        Sheet.MODULE_LIST -> ModuleListSheet()
                        Sheet.SCRIPT -> ScriptSheet(ui)
                        Sheet.FILE_BROWSER -> FileBrowserSheet(ui)
                        Sheet.SPEED -> SpeedSheet(ui)
                        Sheet.HISTORY -> HistorySheet(ui)
                        Sheet.FUNCTION -> FunctionSheet()
                        Sheet.LOG -> LogSheet()
                        Sheet.OVERLAY -> OverlaySheet(ui)
                        Sheet.HELP -> InfoSheet("使用帮助", AppStrings.helpText(lang))
                        Sheet.ABOUT -> AboutSheet(ui)
                        Sheet.CHANGELOG -> InfoSheet("更新日志", AppStrings.changelogText(lang))
                        Sheet.LANGUAGE -> LanguageSheet()
                        Sheet.APPEARANCE -> AppearanceSheet(ui)
                        Sheet.CONTEXT -> ContextSheet(ui)
                        Sheet.TYPE_PICK -> TypePickSheet(ui)
                        Sheet.ADDRESS_EDIT -> AddressEditSheet(ui)
                        Sheet.FILE_MANAGER -> FileSheet(ui)
                        Sheet.COPY_MEMORY -> CopyMemorySheet(ui)
                        Sheet.COPY_GROUP -> CopyGroupSheet(ui)
                        Sheet.DUMP -> DumpSheet(ui)
                        Sheet.ALLOCATE -> AllocateSheet(ui)
                        Sheet.BREAKPOINT -> BreakpointSheet(ui)
                        Sheet.WRITE_ASM -> WriteAsmSheet(ui)
                        Sheet.NONE -> Unit
                    }
                }
            }
        }
    }
}
