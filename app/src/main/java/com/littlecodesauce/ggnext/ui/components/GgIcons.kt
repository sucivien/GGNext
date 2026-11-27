package com.littlecodesauce.ggnext.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 严格复刻原版 GG 96 (drawable-mdpi-v4/ic_*_24dp.png) 及参考图的矢量图标集，
 * 并包含文件浏览器各后缀类型（文件夹、.lua、.so、.txt、.md、.bin）专属矢量图标。
 */
object GgIcons {

    private inline fun icon24(
        name: String,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

    private val WhiteFill = SolidColor(Color.White)

    /**
     * 内存页导航栏图标 —— 左边三个竖排圆点，右边三条竖着排列的横线
     */
    val MemoryList: ImageVector by lazy {
        icon24("GgMemoryList") {
            path(fill = WhiteFill) {
                moveTo(4f, 4.2f)
                arcToRelative(1.8f, 1.8f, 0f, true, true, 0f, 3.6f)
                arcToRelative(1.8f, 1.8f, 0f, true, true, 0f, -3.6f)
                close()
                moveTo(4f, 10.2f)
                arcToRelative(1.8f, 1.8f, 0f, true, true, 0f, 3.6f)
                arcToRelative(1.8f, 1.8f, 0f, true, true, 0f, -3.6f)
                close()
                moveTo(4f, 16.2f)
                arcToRelative(1.8f, 1.8f, 0f, true, true, 0f, 3.6f)
                arcToRelative(1.8f, 1.8f, 0f, true, true, 0f, -3.6f)
                close()
                moveTo(8f, 5f)
                horizontalLineTo(21f)
                verticalLineTo(7f)
                horizontalLineTo(8f)
                close()
                moveTo(8f, 11f)
                horizontalLineTo(21f)
                verticalLineTo(13f)
                horizontalLineTo(8f)
                close()
                moveTo(8f, 17f)
                horizontalLineTo(21f)
                verticalLineTo(19f)
                horizontalLineTo(8f)
                close()
            }
        }
    }

    /**
     * 工具栏图标 —— 三条横线 (ic_menu_white_24dp)
     */
    val MenuThreeLines: ImageVector by lazy {
        icon24("GgMenuThreeLines") {
            path(fill = WhiteFill) {
                moveTo(3f, 6f)
                horizontalLineTo(21f)
                verticalLineTo(8f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 11f)
                horizontalLineTo(21f)
                verticalLineTo(13f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 16f)
                horizontalLineTo(21f)
                verticalLineTo(18f)
                horizontalLineTo(3f)
                close()
            }
        }
    }

    /**
     * 编辑所选：气泡内带斜向铅笔 (ic_tooltip_edit_white_24dp)
     */
    val TooltipEdit: ImageVector by lazy {
        icon24("GgTooltipEdit") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 2f)
                horizontalLineTo(20f)
                quadTo(22f, 2f, 22f, 4f)
                verticalLineTo(16f)
                quadTo(22f, 18f, 20f, 18f)
                horizontalLineTo(15f)
                lineTo(12f, 22f)
                lineTo(9f, 18f)
                horizontalLineTo(4f)
                quadTo(2f, 18f, 2f, 16f)
                verticalLineTo(4f)
                quadTo(2f, 2f, 4f, 2f)
                close()
                moveTo(6.5f, 12.2f)
                lineTo(6.5f, 14.5f)
                lineTo(8.8f, 14.5f)
                lineTo(15.6f, 7.7f)
                lineTo(13.3f, 5.4f)
                close()
                moveTo(17.2f, 6.1f)
                lineTo(15.9f, 4.8f)
                quadTo(15.4f, 4.3f, 14.9f, 4.8f)
                lineTo(14.0f, 5.7f)
                lineTo(16.3f, 8.0f)
                lineTo(17.2f, 7.1f)
                quadTo(17.7f, 6.6f, 17.2f, 6.1f)
                close()
            }
        }
    }

    /**
     * 精确搜索：标准放大镜 (ic_magnify_white_24dp)
     */
    val Magnify: ImageVector by lazy {
        icon24("GgMagnify") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(9.5f, 3f)
                arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, 13f)
                arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, -13f)
                close()
                moveTo(9.5f, 5f)
                arcToRelative(4.5f, 4.5f, 0f, true, false, 0f, 9f)
                arcToRelative(4.5f, 4.5f, 0f, true, false, 0f, -9f)
                close()
                moveTo(14.3f, 13.0f)
                lineTo(20.5f, 19.2f)
                lineTo(19.0f, 20.7f)
                lineTo(12.8f, 14.5f)
                close()
            }
        }
    }

    /**
     * 模糊搜索：左下放大镜 + 右上问号 '?' (ic_fuzzy_white_24dp)
     */
    val FuzzySearch: ImageVector by lazy {
        icon24("GgFuzzySearch") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(8.5f, 6f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, 11f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, -11f)
                close()
                moveTo(8.5f, 7.8f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, 7.4f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, -7.4f)
                close()
                moveTo(12.6f, 14.5f)
                lineTo(18.2f, 20.1f)
                lineTo(16.8f, 21.5f)
                lineTo(11.2f, 15.9f)
                close()
            }
            path(fill = WhiteFill) {
                moveTo(15.2f, 2.2f)
                quadTo(17.8f, 1.0f, 20.0f, 2.5f)
                quadTo(21.5f, 3.6f, 21.2f, 5.5f)
                quadTo(20.9f, 7.0f, 19.3f, 8.0f)
                quadTo(18.3f, 8.7f, 18.3f, 9.8f)
                horizontalLineTo(16.5f)
                quadTo(16.5f, 8.1f, 17.8f, 7.1f)
                quadTo(19.2f, 6.2f, 19.2f, 5.1f)
                quadTo(19.2f, 3.8f, 17.8f, 3.6f)
                quadTo(16.3f, 3.5f, 16.0f, 5.0f)
                horizontalLineTo(14.2f)
                quadTo(14.4f, 2.8f, 15.2f, 2.2f)
                close()
                moveTo(16.4f, 11.0f)
                horizontalLineTo(18.4f)
                verticalLineTo(13.0f)
                horizontalLineTo(16.4f)
                close()
            }
        }
    }

    /**
     * 调整5：蒙版搜索 —— 左下放大镜 + 右上角小 '#' 号
     */
    val MaskSearch: ImageVector by lazy {
        icon24("GgMaskSearch") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(8.5f, 6f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, 11f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, -11f)
                close()
                moveTo(8.5f, 7.8f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, 7.4f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, -7.4f)
                close()
                moveTo(12.6f, 14.5f)
                lineTo(18.2f, 20.1f)
                lineTo(16.8f, 21.5f)
                lineTo(11.2f, 15.9f)
                close()
            }
            path(
                stroke = WhiteFill,
                strokeLineWidth = 1.55f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(16.7f, 1.8f)
                lineTo(16.1f, 9.6f)
                moveTo(19.9f, 1.8f)
                lineTo(19.3f, 9.6f)
                moveTo(14.4f, 4.3f)
                lineTo(21.6f, 4.3f)
                moveTo(14.1f, 7.2f)
                lineTo(21.3f, 7.2f)
            }
        }
    }

    /**
     * 调整9：指针搜索 —— 左下放大镜 + 右上角小 '*' 星号
     */
    val PointerSearch: ImageVector by lazy {
        icon24("GgPointerSearch") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(8.5f, 6f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, 11f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, -11f)
                close()
                moveTo(8.5f, 7.8f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, 7.4f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, -7.4f)
                close()
                moveTo(12.6f, 14.5f)
                lineTo(18.2f, 20.1f)
                lineTo(16.8f, 21.5f)
                lineTo(11.2f, 15.9f)
                close()
            }
            path(
                stroke = WhiteFill,
                strokeLineWidth = 1.65f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(18.2f, 1.8f)
                lineTo(18.2f, 9.2f)
                moveTo(15.0f, 3.65f)
                lineTo(21.4f, 7.35f)
                moveTo(21.4f, 3.65f)
                lineTo(15.0f, 7.35f)
            }
        }
    }

    /**
     * 选定为搜索结果：放大镜 + 右上对勾 (ic_search_selected_white_24dp)
     */
    val SearchSelected: ImageVector by lazy {
        icon24("GgSearchSelected") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(8.5f, 6f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, 11f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, -11f)
                close()
                moveTo(8.5f, 7.8f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, 7.4f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, -7.4f)
                close()
                moveTo(12.6f, 14.5f)
                lineTo(18.2f, 20.1f)
                lineTo(16.8f, 21.5f)
                lineTo(11.2f, 15.9f)
                close()
            }
            path(fill = WhiteFill) {
                moveTo(16.8f, 6.8f)
                lineTo(14.8f, 4.8f)
                lineTo(13.5f, 6.1f)
                lineTo(16.8f, 9.4f)
                lineTo(22.2f, 4.0f)
                lineTo(20.9f, 2.7f)
                close()
            }
        }
    }

    /**
     * 仅保留有变化的结果：放大镜 + 右上三角 Delta (ic_search_changed_white_24dp)
     */
    val SearchChanged: ImageVector by lazy {
        icon24("GgSearchChanged") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(8.5f, 6f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, 11f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, 0f, -11f)
                close()
                moveTo(8.5f, 7.8f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, 7.4f)
                arcToRelative(3.7f, 3.7f, 0f, true, false, 0f, -7.4f)
                close()
                moveTo(12.6f, 14.5f)
                lineTo(18.2f, 20.1f)
                lineTo(16.8f, 21.5f)
                lineTo(11.2f, 15.9f)
                close()
                moveTo(18.0f, 2.0f)
                lineTo(22.5f, 9.5f)
                horizontalLineTo(13.5f)
                close()
                moveTo(18.0f, 4.6f)
                lineTo(15.8f, 8.2f)
                horizontalLineTo(20.2f)
                close()
            }
        }
    }

    /**
     * 偏移量计算器 (ic_calculator_white_24dp)
     */
    val Calculator: ImageVector by lazy {
        icon24("GgCalculator") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                quadTo(21f, 3f, 21f, 5f)
                verticalLineTo(19f)
                quadTo(21f, 21f, 19f, 21f)
                horizontalLineTo(5f)
                quadTo(3f, 21f, 3f, 19f)
                verticalLineTo(5f)
                quadTo(3f, 3f, 5f, 3f)
                close()
                moveTo(7.5f, 6.2f)
                horizontalLineTo(9.1f)
                verticalLineTo(8.0f)
                horizontalLineTo(10.9f)
                verticalLineTo(9.6f)
                horizontalLineTo(9.1f)
                verticalLineTo(11.4f)
                horizontalLineTo(7.5f)
                verticalLineTo(9.6f)
                horizontalLineTo(5.7f)
                verticalLineTo(8.0f)
                horizontalLineTo(7.5f)
                close()
                moveTo(13.2f, 8.0f)
                horizontalLineTo(18.2f)
                verticalLineTo(9.6f)
                horizontalLineTo(13.2f)
                close()
                moveTo(13.2f, 14.2f)
                horizontalLineTo(18.2f)
                verticalLineTo(15.6f)
                horizontalLineTo(13.2f)
                close()
                moveTo(13.2f, 16.8f)
                horizontalLineTo(18.2f)
                verticalLineTo(18.2f)
                horizontalLineTo(13.2f)
                close()
                moveTo(6.4f, 14.2f)
                lineTo(8.3f, 16.1f)
                lineTo(10.2f, 14.2f)
                lineTo(11.3f, 15.3f)
                lineTo(9.4f, 17.2f)
                lineTo(11.3f, 19.1f)
                lineTo(10.2f, 20.2f)
                lineTo(8.3f, 18.3f)
                lineTo(6.4f, 20.2f)
                lineTo(5.3f, 19.1f)
                lineTo(7.2f, 17.2f)
                lineTo(5.3f, 15.3f)
                close()
            }
        }
    }

    /**
     * 计算偏移、异或：双重叠方块 (ic_vector_difference_white_24dp)
     */
    val VectorDifference: ImageVector by lazy {
        icon24("GgVectorDifference") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(2f, 2f)
                horizontalLineTo(15f)
                verticalLineTo(8f)
                horizontalLineTo(22f)
                verticalLineTo(22f)
                horizontalLineTo(8f)
                verticalLineTo(15f)
                horizontalLineTo(2f)
                close()
                moveTo(4f, 4f)
                verticalLineTo(13f)
                horizontalLineTo(8f)
                verticalLineTo(8f)
                horizontalLineTo(13f)
                verticalLineTo(4f)
                close()
                moveTo(10f, 10f)
                verticalLineTo(13f)
                horizontalLineTo(13f)
                verticalLineTo(10f)
                close()
                moveTo(15f, 10f)
                verticalLineTo(15f)
                horizontalLineTo(10f)
                verticalLineTo(20f)
                horizontalLineTo(20f)
                verticalLineTo(10f)
                close()
            }
        }
    }

    /**
     * 更改地址/类型：上下箭头夹横杠 (ic_cursor_mover_white_24dp)
     */
    val CursorMover: ImageVector by lazy {
        icon24("GgCursorMover") {
            path(fill = WhiteFill) {
                moveTo(12f, 2f)
                lineTo(16.5f, 6.5f)
                horizontalLineTo(13.2f)
                verticalLineTo(9.5f)
                horizontalLineTo(10.8f)
                verticalLineTo(6.5f)
                horizontalLineTo(7.5f)
                close()
                moveTo(5f, 11f)
                horizontalLineTo(19f)
                verticalLineTo(13f)
                horizontalLineTo(5f)
                close()
                moveTo(12f, 22f)
                lineTo(7.5f, 17.5f)
                horizontalLineTo(10.8f)
                verticalLineTo(14.5f)
                horizontalLineTo(13.2f)
                verticalLineTo(17.5f)
                horizontalLineTo(16.5f)
                close()
            }
        }
    }

    /**
     * 全选：虚线外框 + 实心内框 (ic_select_all_white_24dp)
     */
    val SelectAll: ImageVector by lazy {
        icon24("GgSelectAll") {
            path(fill = WhiteFill) {
                val pts = listOf(
                    3f to 3f, 7f to 3f, 11f to 3f, 15f to 3f, 19f to 3f,
                    3f to 7f, 19f to 7f,
                    3f to 11f, 19f to 11f,
                    3f to 15f, 19f to 15f,
                    3f to 19f, 7f to 19f, 11f to 19f, 15f to 19f, 19f to 19f
                )
                for ((x, y) in pts) {
                    moveTo(x, y)
                    horizontalLineTo(x + 2f)
                    verticalLineTo(y + 2f)
                    horizontalLineTo(x)
                    close()
                }
                moveTo(7f, 7f)
                horizontalLineTo(17f)
                verticalLineTo(17f)
                horizontalLineTo(7f)
                close()
                moveTo(9.2f, 9.2f)
                verticalLineTo(14.8f)
                horizontalLineTo(14.8f)
                verticalLineTo(9.2f)
                close()
            }
        }
    }

    /**
     * 反选：虚线外框 + 对角半实心内框 (ic_select_inverse_white_24dp)
     */
    val SelectInverse: ImageVector by lazy {
        icon24("GgSelectInverse") {
            path(fill = WhiteFill) {
                val pts = listOf(
                    3f to 3f, 7f to 3f, 11f to 3f, 15f to 3f, 19f to 3f,
                    3f to 7f, 19f to 7f,
                    3f to 11f, 19f to 11f,
                    3f to 15f, 19f to 15f,
                    3f to 19f, 7f to 19f, 11f to 19f, 15f to 19f, 19f to 19f
                )
                for ((x, y) in pts) {
                    moveTo(x, y)
                    horizontalLineTo(x + 2f)
                    verticalLineTo(y + 2f)
                    horizontalLineTo(x)
                    close()
                }
                moveTo(7f, 7f)
                horizontalLineTo(17f)
                verticalLineTo(17f)
                close()
            }
        }
    }

    /**
     * 清除选择：虚线外框 + 斜杠 (ic_select_off_white_24dp)
     */
    val SelectOff: ImageVector by lazy {
        icon24("GgSelectOff") {
            path(fill = WhiteFill) {
                val pts = listOf(
                    7f to 3f, 11f to 3f, 15f to 3f, 19f to 3f,
                    3f to 7f, 19f to 7f,
                    3f to 11f, 19f to 11f,
                    3f to 15f, 19f to 15f,
                    3f to 19f, 7f to 19f, 11f to 19f, 15f to 19f
                )
                for ((x, y) in pts) {
                    moveTo(x, y)
                    horizontalLineTo(x + 2f)
                    verticalLineTo(y + 2f)
                    horizontalLineTo(x)
                    close()
                }
                moveTo(3.5f, 2.1f)
                lineTo(21.9f, 20.5f)
                lineTo(20.5f, 21.9f)
                lineTo(2.1f, 3.5f)
                close()
            }
        }
    }

    /**
     * 数值格式：列表横线 + 右下对勾 (ic_playlist_check_white_24dp)
     */
    val PlaylistCheck: ImageVector by lazy {
        icon24("GgPlaylistCheck") {
            path(fill = WhiteFill) {
                moveTo(2f, 6f)
                horizontalLineTo(14f)
                verticalLineTo(8f)
                horizontalLineTo(2f)
                close()
                moveTo(2f, 10f)
                horizontalLineTo(14f)
                verticalLineTo(12f)
                horizontalLineTo(2f)
                close()
                moveTo(2f, 14f)
                horizontalLineTo(10f)
                verticalLineTo(16f)
                horizontalLineTo(2f)
                close()
                moveTo(15.5f, 16.2f)
                lineTo(13.0f, 13.7f)
                lineTo(11.6f, 15.1f)
                lineTo(15.5f, 19.0f)
                lineTo(22.0f, 12.5f)
                lineTo(20.6f, 11.1f)
                close()
            }
        }
    }

    /**
     * 分配内存页：花括号 { } (ic_code_braces_white_24dp)
     */
    val CodeBraces: ImageVector by lazy {
        icon24("GgCodeBraces") {
            path(fill = WhiteFill) {
                moveTo(8f, 4f)
                quadTo(6f, 4f, 6f, 6f)
                verticalLineTo(9.5f)
                quadTo(6f, 11f, 4f, 11f)
                verticalLineTo(13f)
                quadTo(6f, 13f, 6f, 14.5f)
                verticalLineTo(18f)
                quadTo(6f, 20f, 8f, 20f)
                horizontalLineTo(10f)
                verticalLineTo(18f)
                horizontalLineTo(8.5f)
                quadTo(8f, 18f, 8f, 17.5f)
                verticalLineTo(14f)
                quadTo(8f, 12.5f, 6.8f, 12f)
                quadTo(8f, 11.5f, 8f, 10f)
                verticalLineTo(6.5f)
                quadTo(8f, 6f, 8.5f, 6f)
                horizontalLineTo(10f)
                verticalLineTo(4f)
                close()
                moveTo(16f, 4f)
                quadTo(18f, 4f, 18f, 6f)
                verticalLineTo(9.5f)
                quadTo(18f, 11f, 20f, 11f)
                verticalLineTo(13f)
                quadTo(18f, 13f, 18f, 14.5f)
                verticalLineTo(18f)
                quadTo(18f, 20f, 16f, 20f)
                horizontalLineTo(14f)
                verticalLineTo(18f)
                horizontalLineTo(15.5f)
                quadTo(16f, 18f, 16f, 17.5f)
                verticalLineTo(14f)
                quadTo(16f, 12.5f, 17.2f, 12f)
                quadTo(16f, 11.5f, 16f, 10f)
                verticalLineTo(6.5f)
                quadTo(16f, 6f, 15.5f, 6f)
                horizontalLineTo(14f)
                verticalLineTo(4f)
                close()
            }
        }
    }

    /**
     * 执行脚本：尖括号 < >
     */
    val AngleBrackets: ImageVector by lazy {
        icon24("GgAngleBrackets") {
            path(
                stroke = WhiteFill,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9.2f, 7f)
                lineTo(4.2f, 12f)
                lineTo(9.2f, 17f)
                moveTo(14.8f, 7f)
                lineTo(19.8f, 12f)
                lineTo(14.8f, 17f)
            }
        }
    }

    /**
     * 变速：速度仪表盘 (ic_speedometer_white_24dp)
     */
    val Speedometer: ImageVector by lazy {
        icon24("GgSpeedometer") {
            path(fill = WhiteFill) {
                moveTo(12f, 4f)
                arcToRelative(9f, 9f, 0f, false, false, -9f, 9f)
                quadTo(3f, 16.5f, 5.2f, 19f)
                horizontalLineTo(7.8f)
                quadTo(5.2f, 16.5f, 5.2f, 13f)
                arcToRelative(6.8f, 6.8f, 0f, false, true, 13.6f, 0f)
                quadTo(18.8f, 16.5f, 16.2f, 19f)
                horizontalLineTo(18.8f)
                quadTo(21f, 16.5f, 21f, 13f)
                arcToRelative(9f, 9f, 0f, false, false, -9f, -9f)
                close()
                moveTo(10.8f, 13.8f)
                lineTo(16.6f, 8.0f)
                lineTo(18.0f, 9.4f)
                lineTo(12.2f, 15.2f)
                arcToRelative(1.6f, 1.6f, 0f, true, true, -1.4f, -1.4f)
                close()
            }
        }
    }

    /**
     * 导出选定项：下载箭头 + 右上角对勾 (ic_download_checked_white_24dp)
     */
    val DownloadChecked: ImageVector by lazy {
        icon24("GgDownloadChecked") {
            path(fill = WhiteFill) {
                moveTo(8f, 4f)
                horizontalLineTo(12f)
                verticalLineTo(10f)
                horizontalLineTo(15f)
                lineTo(10f, 15f)
                lineTo(5f, 10f)
                horizontalLineTo(8f)
                close()
                moveTo(4f, 17f)
                horizontalLineTo(18f)
                verticalLineTo(19f)
                horizontalLineTo(4f)
                close()
                moveTo(16.5f, 6.8f)
                lineTo(14.8f, 5.1f)
                lineTo(13.6f, 6.3f)
                lineTo(16.5f, 9.2f)
                lineTo(21.5f, 4.2f)
                lineTo(20.3f, 3.0f)
                close()
            }
        }
    }

    /**
     * 复制内存：双页叠加带加号 (ic_content_duplicate_white_24dp)
     */
    val ContentDuplicate: ImageVector by lazy {
        icon24("GgContentDuplicate") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 2f)
                horizontalLineTo(15f)
                verticalLineTo(4f)
                horizontalLineTo(6f)
                verticalLineTo(16f)
                horizontalLineTo(4f)
                close()
                moveTo(8f, 6f)
                horizontalLineTo(20f)
                verticalLineTo(21f)
                horizontalLineTo(8f)
                close()
                moveTo(10f, 8f)
                verticalLineTo(19f)
                horizontalLineTo(18f)
                verticalLineTo(8f)
                close()
                moveTo(13.2f, 10.5f)
                horizontalLineTo(14.8f)
                verticalLineTo(12.7f)
                horizontalLineTo(17.0f)
                verticalLineTo(14.3f)
                horizontalLineTo(14.8f)
                verticalLineTo(16.5f)
                horizontalLineTo(13.2f)
                verticalLineTo(14.3f)
                horizontalLineTo(11.0f)
                verticalLineTo(12.7f)
                horizontalLineTo(13.2f)
                close()
            }
        }
    }

    /**
     * 工具栏顺序调整的上下移动图标 (双向箭头 ↕)
     */
    val DragUpDown: ImageVector by lazy {
        icon24("GgDragUpDown") {
            path(fill = WhiteFill) {
                moveTo(12f, 3f)
                lineTo(7.5f, 7.8f)
                horizontalLineTo(10.8f)
                verticalLineTo(16.2f)
                horizontalLineTo(7.5f)
                lineTo(12f, 21f)
                lineTo(16.5f, 16.2f)
                horizontalLineTo(13.2f)
                verticalLineTo(7.8f)
                horizontalLineTo(16.5f)
                close()
            }
        }
    }

    /**
     * 内置键盘第1行右：向左退格 (ic_backspace_white_24dp)
     */
    val BackspaceLeft: ImageVector by lazy {
        icon24("GgBackspaceLeft") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(21f, 5f)
                horizontalLineTo(8.2f)
                lineTo(2.5f, 12f)
                lineTo(8.2f, 19f)
                horizontalLineTo(21f)
                quadTo(22.5f, 19f, 22.5f, 17.5f)
                verticalLineTo(6.5f)
                quadTo(22.5f, 5f, 21f, 5f)
                close()
                moveTo(11.6f, 9.2f)
                lineTo(14.4f, 12.0f)
                lineTo(17.2f, 9.2f)
                lineTo(18.5f, 10.5f)
                lineTo(15.7f, 13.3f)
                lineTo(18.5f, 16.1f)
                lineTo(17.2f, 17.4f)
                lineTo(14.4f, 14.6f)
                lineTo(11.6f, 17.4f)
                lineTo(10.3f, 16.1f)
                lineTo(13.1f, 13.3f)
                lineTo(10.3f, 10.5f)
                close()
            }
        }
    }

    /**
     * 内置键盘第2行右：向右删除 (ic_backspace_rev_white_24dp)
     */
    val DeleteRight: ImageVector by lazy {
        icon24("GgDeleteRight") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 5f)
                horizontalLineTo(15.8f)
                lineTo(21.5f, 12f)
                lineTo(15.8f, 19f)
                horizontalLineTo(3f)
                quadTo(1.5f, 19f, 1.5f, 17.5f)
                verticalLineTo(6.5f)
                quadTo(1.5f, 5f, 3f, 5f)
                close()
                moveTo(6.8f, 9.2f)
                lineTo(9.6f, 12.0f)
                lineTo(12.4f, 9.2f)
                lineTo(13.7f, 10.5f)
                lineTo(10.9f, 13.3f)
                lineTo(13.7f, 16.1f)
                lineTo(12.4f, 17.4f)
                lineTo(9.6f, 14.6f)
                lineTo(6.8f, 17.4f)
                lineTo(5.5f, 16.1f)
                lineTo(8.3f, 13.3f)
                lineTo(5.5f, 10.5f)
                close()
            }
        }
    }

    /**
     * 内置键盘右下角：上下向内收缩箭头 (ic_unfold_less_horizontal_white_24dp)
     */
    val CollapseVertical: ImageVector by lazy {
        icon24("GgCollapseVertical") {
            path(
                stroke = WhiteFill,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.5f, 5.5f)
                lineTo(12f, 9.2f)
                lineTo(15.5f, 5.5f)
                moveTo(8.5f, 18.5f)
                lineTo(12f, 14.8f)
                lineTo(15.5f, 18.5f)
            }
        }
    }

    /**
     * 改进11（对齐图4）：分配内存页与地址输入框右侧的内存段快捷选择器图标
     */
    val RegionPicker: ImageVector by lazy {
        icon24("GgRegionPicker") {
            path(
                stroke = WhiteFill,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // 左上圆圈
                moveTo(7.5f, 4.5f)
                arcToRelative(2.8f, 2.8f, 0f, true, true, 0f, 5.6f)
                arcToRelative(2.8f, 2.8f, 0f, true, true, 0f, -5.6f)
                close()
                // 右上方块
                moveTo(14.2f, 4.5f)
                horizontalLineTo(19.5f)
                verticalLineTo(9.8f)
                horizontalLineTo(14.2f)
                close()
                // 左下列表框
                moveTo(4.7f, 14.2f)
                horizontalLineTo(10.0f)
                verticalLineTo(19.5f)
                horizontalLineTo(4.7f)
                close()
                // 右下圆弧箭头
                moveTo(14.2f, 14.2f)
                horizontalLineTo(19.5f)
                verticalLineTo(19.5f)
                horizontalLineTo(14.2f)
                close()
            }
        }
    }

    // ============ 改进5：文件浏览器专属后缀矢量图标 (.lua / .so / .txt / .md / .bin / 文件夹) ============

    /** 文件夹图标 */
    val FolderIcon: ImageVector by lazy {
        icon24("GgFolderIcon") {
            path(fill = WhiteFill) {
                moveTo(3.5f, 4.5f)
                horizontalLineTo(9.8f)
                lineTo(11.8f, 6.8f)
                horizontalLineTo(20.5f)
                quadTo(22f, 6.8f, 22f, 8.3f)
                verticalLineTo(18.2f)
                quadTo(22f, 19.8f, 20.5f, 19.8f)
                horizontalLineTo(3.5f)
                quadTo(2f, 19.8f, 2f, 18.2f)
                verticalLineTo(6.0f)
                quadTo(2f, 4.5f, 3.5f, 4.5f)
                close()
            }
        }
    }

    /** .lua 脚本文件图标（文档框 + 内部 `<>` 脚本徽章） */
    val FileLua: ImageVector by lazy {
        icon24("GgFileLua") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(6f, 2f)
                horizontalLineTo(14.5f)
                lineTo(19.5f, 7f)
                verticalLineTo(20f)
                quadTo(19.5f, 22f, 17.5f, 22f)
                horizontalLineTo(6f)
                quadTo(4f, 22f, 4f, 20f)
                verticalLineTo(4f)
                quadTo(4f, 2f, 6f, 2f)
                close()
                moveTo(14f, 3.5f)
                verticalLineTo(7.5f)
                horizontalLineTo(18f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF141013)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9.8f, 11.8f)
                lineTo(7.3f, 14.3f)
                lineTo(9.8f, 16.8f)
                moveTo(13.8f, 11.8f)
                lineTo(16.3f, 14.3f)
                lineTo(13.8f, 16.8f)
            }
        }
    }

    /** .so 原生动态库文件图标（芯片/引脚造型） */
    val FileSo: ImageVector by lazy {
        icon24("GgFileSo") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                // 中心芯片体
                moveTo(6.5f, 6.5f)
                horizontalLineTo(17.5f)
                verticalLineTo(17.5f)
                horizontalLineTo(6.5f)
                close()
                moveTo(9.2f, 9.2f)
                verticalLineTo(14.8f)
                horizontalLineTo(14.8f)
                verticalLineTo(9.2f)
                close()
                // 上引脚
                moveTo(8.5f, 2.5f); horizontalLineTo(10.3f); verticalLineTo(5.5f); horizontalLineTo(8.5f); close()
                moveTo(13.7f, 2.5f); horizontalLineTo(15.5f); verticalLineTo(5.5f); horizontalLineTo(13.7f); close()
                // 下引脚
                moveTo(8.5f, 18.5f); horizontalLineTo(10.3f); verticalLineTo(21.5f); horizontalLineTo(8.5f); close()
                moveTo(13.7f, 18.5f); horizontalLineTo(15.5f); verticalLineTo(21.5f); horizontalLineTo(13.7f); close()
                // 左引脚
                moveTo(2.5f, 8.5f); horizontalLineTo(5.5f); verticalLineTo(10.3f); horizontalLineTo(2.5f); close()
                moveTo(2.5f, 13.7f); horizontalLineTo(5.5f); verticalLineTo(15.5f); horizontalLineTo(2.5f); close()
                // 右引脚
                moveTo(18.5f, 8.5f); horizontalLineTo(21.5f); verticalLineTo(10.3f); horizontalLineTo(18.5f); close()
                moveTo(18.5f, 13.7f); horizontalLineTo(21.5f); verticalLineTo(15.5f); horizontalLineTo(18.5f); close()
            }
        }
    }

    /** .txt 文本文件图标（文档框 + 清晰多行文本横线） */
    val FileTxt: ImageVector by lazy {
        icon24("GgFileTxt") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(6f, 2f)
                horizontalLineTo(14.5f)
                lineTo(19.5f, 7f)
                verticalLineTo(20f)
                quadTo(19.5f, 22f, 17.5f, 22f)
                horizontalLineTo(6f)
                quadTo(4f, 22f, 4f, 20f)
                verticalLineTo(4f)
                quadTo(4f, 2f, 6f, 2f)
                close()
                moveTo(14f, 3.5f)
                verticalLineTo(7.5f)
                horizontalLineTo(18f)
                close()
                moveTo(7.2f, 10.5f)
                horizontalLineTo(16.2f)
                verticalLineTo(12.1f)
                horizontalLineTo(7.2f)
                close()
                moveTo(7.2f, 13.8f)
                horizontalLineTo(16.2f)
                verticalLineTo(15.4f)
                horizontalLineTo(7.2f)
                close()
                moveTo(7.2f, 17.1f)
                horizontalLineTo(13.2f)
                verticalLineTo(18.7f)
                horizontalLineTo(7.2f)
                close()
            }
        }
    }

    /** .md Markdown 文件图标（圆角框 + M 与向下箭头） */
    val FileMd: ImageVector by lazy {
        icon24("GgFileMd") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 4f)
                horizontalLineTo(20f)
                quadTo(22f, 4f, 22f, 6f)
                verticalLineTo(18f)
                quadTo(22f, 20f, 20f, 20f)
                horizontalLineTo(4f)
                quadTo(2f, 20f, 2f, 18f)
                verticalLineTo(6f)
                quadTo(2f, 4f, 4f, 4f)
                close()
                // M 字样
                moveTo(4.8f, 15.8f)
                verticalLineTo(8.2f)
                horizontalLineTo(6.6f)
                lineTo(8.6f, 11.2f)
                lineTo(10.6f, 8.2f)
                horizontalLineTo(12.4f)
                verticalLineTo(15.8f)
                horizontalLineTo(10.6f)
                verticalLineTo(11.4f)
                lineTo(8.6f, 14.2f)
                lineTo(6.6f, 11.4f)
                verticalLineTo(15.8f)
                close()
                // 右侧 ↓ 箭头
                moveTo(16.2f, 8.2f)
                horizontalLineTo(18.0f)
                verticalLineTo(12.6f)
                horizontalLineTo(19.8f)
                lineTo(17.1f, 15.8f)
                lineTo(14.4f, 12.6f)
                horizontalLineTo(16.2f)
                close()
            }
        }
    }

    /** .bin / .dmp 二进制转储文件图标 */
    val FileBin: ImageVector by lazy {
        icon24("GgFileBin") {
            path(fill = WhiteFill, pathFillType = PathFillType.EvenOdd) {
                moveTo(6f, 2f)
                horizontalLineTo(14.5f)
                lineTo(19.5f, 7f)
                verticalLineTo(20f)
                quadTo(19.5f, 22f, 17.5f, 22f)
                horizontalLineTo(6f)
                quadTo(4f, 22f, 4f, 20f)
                verticalLineTo(4f)
                quadTo(4f, 2f, 6f, 2f)
                close()
                moveTo(14f, 3.5f)
                verticalLineTo(7.5f)
                horizontalLineTo(18f)
                close()
                // 01 / 10 矩阵块
                moveTo(7.5f, 10.5f); horizontalLineTo(10.5f); verticalLineTo(13.5f); horizontalLineTo(7.5f); close()
                moveTo(13.0f, 10.5f); horizontalLineTo(16.0f); verticalLineTo(13.5f); horizontalLineTo(13.0f); close()
                moveTo(7.5f, 15.2f); horizontalLineTo(10.5f); verticalLineTo(18.2f); horizontalLineTo(7.5f); close()
                moveTo(13.0f, 15.2f); horizontalLineTo(16.0f); verticalLineTo(18.2f); horizontalLineTo(13.0f); close()
            }
        }
    }
}
