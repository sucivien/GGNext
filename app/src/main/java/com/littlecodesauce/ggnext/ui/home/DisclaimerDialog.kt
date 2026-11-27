package com.littlecodesauce.ggnext.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.ui.components.GgModalOverlay
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.delay

@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(10) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft -= 1
        }
    }

    // 首次未同意前拦截返回键，防止绕过免责声明
    BackHandler(enabled = true) {}

    val canAccept = secondsLeft <= 0
    val acceptBg by animateColorAsState(
        targetValue = if (canAccept) Rose.Primary else Rose.CardHigh,
        animationSpec = Motion.ColorNormal,
        label = "disclaimerAcceptBg"
    )
    val acceptFg by animateColorAsState(
        targetValue = if (canAccept) Rose.OnPrimary else Rose.TextDim,
        animationSpec = Motion.ColorNormal,
        label = "disclaimerAcceptFg"
    )

    GgModalOverlay(
        visible = true,
        onDismiss = {},
        zIndex = 200f,
        dismissOnScrimClick = false
    ) {
        GgSurfaceCard(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = GgShapes.Dialog,
            contentPadding = PaddingValues(GgSpacing.Xxl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(GgShapes.Md)
                        .background(Rose.Primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Gavel,
                        contentDescription = null,
                        tint = Rose.Primary,
                        modifier = Modifier.size(GgIconSize.Lg)
                    )
                }
                Spacer(Modifier.width(GgSpacing.Lg))
                Column {
                    Text(
                        text = tr("使用声明与免责协议"),
                        color = Rose.Text,
                        fontSize = fsp(17f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = tr("请完整阅读以下条款后决定是否继续使用"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 340.dp)
                    .clip(GgShapes.Lg)
                    .background(Rose.Surface.copy(alpha = 0.65f))
                    .border(GgBorder.Default, Rose.Divider, GgShapes.Lg)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)
            ) {
                DisclaimerClause(
                    title = "一、工具性质与适用范围",
                    body = "GGNext 为开源本地内存分析与调试工具，仅适用于程序开发测试、故障排查、安全研究及经明确授权的本地环境实验。任何功能均按通用内存读写机制提供，不针对任何特定商业软件、游戏或网络服务。"
                )
                DisclaimerClause(
                    title = "二、严禁非法及侵权用途",
                    body = "严禁将本软件用于制作或分发游戏外挂、篡改联机数据、破坏计算机信息系统、绕过付费与版权验证、窃取敏感信息或任何违反当地法律法规及第三方服务协议的行为。如您所在地区法律限制此类工具的使用，请立即停止使用并卸载。"
                )
                DisclaimerClause(
                    title = "三、使用者独立责任",
                    body = "因使用本软件而产生的一切技术后果、数据损坏、账号封禁、设备异常及法律责任，均由使用者本人独立承担。软件作者及代码贡献者无法控制任何第三方的具体操作方式，亦不参与、不教唆任何违规用途。"
                )
                DisclaimerClause(
                    title = "四、开源协议与无担保声明",
                    body = "本软件依据 GNU General Public License v3.0 协议以现状形式开源分发，不附带任何明示或暗示的适用性、可靠性或无侵权担保。在适用法律允许的最大范围内，作者与贡献者不对因使用或无法使用本软件造成的任何直接、间接或附带损失承担赔偿责任。"
                )
                DisclaimerClause(
                    title = "五、确认与生效",
                    body = "点击「同意并继续」即表示您已充分阅读、理解并自愿遵守上述全部条款；若您不同意任何内容，请点击「不同意并退出」并立即移除本软件。"
                )
            }

            Spacer(Modifier.height(GgSpacing.Xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(GgControlHeight.Input)
                        .clip(GgShapes.Md)
                        .background(Rose.CardHigh)
                        .border(GgBorder.Default, Rose.Divider, GgShapes.Md)
                        .clickable(onClick = onDecline),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tr("不同意并退出"),
                        color = Rose.TextSecond,
                        fontSize = fsp(14f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1.25f)
                        .height(GgControlHeight.Input)
                        .clip(GgShapes.Md)
                        .background(acceptBg)
                        .then(
                            if (canAccept) Modifier.clickable(onClick = onAccept)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (canAccept) tr("同意并继续") else "${tr("同意并继续")} ${secondsLeft}s",
                        color = acceptFg,
                        fontSize = fsp(14f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclaimerClause(
    title: String,
    body: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(13f),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = tr(body),
            color = Rose.TextSecond,
            fontSize = fsp(12.2f),
            lineHeight = fsp(18.5f)
        )
    }
}
