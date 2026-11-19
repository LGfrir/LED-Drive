package com.lgfrir.leddrivebt.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lgfrir.leddrivebt.ui.theme.AppType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.lgfrir.leddrivebt.ui.theme.MinTouchTarget
import com.lgfrir.leddrivebt.ui.theme.Motion
import com.lgfrir.leddrivebt.ui.theme.Radius
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 卡片三档。层级从外到内：底板 → 卡片 → 内容。
 * 每档取各自的透明度 CompositionLocal，所以外观面板里三根滑条仍然实时生效。
 */
enum class CardLevel { Base, Raised, Overlay }

/** 从外到内：无 → 底板 → 卡片 → 内容。展开层读到自己盖住的最深一层，再往里取一级。 */
enum class PlateStep { None, Base, Raised, Overlay }

val LocalPlateStep = compositionLocalOf { PlateStep.None }

fun PlateStep.inward(): PlateStep = when (this) {
    PlateStep.None -> PlateStep.Base
    PlateStep.Base -> PlateStep.Raised
    PlateStep.Raised -> PlateStep.Overlay
    PlateStep.Overlay -> PlateStep.Overlay
}

/**
 * 半透明卡片。深色主题不用阴影，靠 surface / surfaceHigh 的明度差分层。
 * 描边只给 Raised，避免每层都描一圈虚线感。
 */
@Composable
fun Modifier.surfaceCard(level: CardLevel, enabled: Boolean = true): Modifier {
    val palette = LocalPalette.current
    val shape = remember(level) {
        RoundedCornerShape(if (level == CardLevel.Base) Radius.large else Radius.medium)
    }
    val strength = when (level) {
        CardLevel.Base -> LocalIoAcrylic.current
        CardLevel.Raised -> LocalAcrylic.current
        CardLevel.Overlay -> LocalFilm.current
    }
    val fill = if (level == CardLevel.Overlay) palette.surfaceHigh else palette.surface
    val alpha = acrylicAlpha(strength) * (if (enabled) 1f else 0.6f)
    var next = this
        .clip(shape)
        .background(fill.copy(alpha = alpha))
    if (level == CardLevel.Raised && enabled) {
        next = next.border(1.dp, palette.hairline, shape)
    }
    return next
}

/** 按下缩到 0.98，120ms 回弹。全站可点卡片/行只用这一种按压反馈。 */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    scale: Float = 0.98f,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val factor by animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = tween(Motion.press),
        label = "press",
    )
    return this.graphicsLayer {
        scaleX = factor
        scaleY = factor
    }
}

/**
 * 底板内部的分组：不铺底色，只用标题、间距和细分隔线组织内容。
 * 目前留作备用，默认不用——底板里再叠一层会被看成"多出来的底板"。
 */
@Composable
fun PlateGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = Space.x3)) {
        if (title != null) {
            Text(
                title,
                color = LocalPalette.current.muted,
                style = AppType.caption,
                modifier = Modifier.padding(bottom = Space.x2),
            )
        }
        content()
    }
}

/** 分组之间的细分隔线。 */
@Composable
fun PlateDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x3)
            .height(1.dp)
            .background(palette.hairline),
    )
}

/**
 * 可点区域。按压反馈统一走 pressScale，不再叠 ripple，免得被圆角裁掉一半。
 */
@Composable
fun Modifier.tapTarget(
    enabled: Boolean = true,
    description: String? = null,
    clickRole: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val base = this
        .pressScale(source, enabled)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = clickRole,
            onClick = onClick,
        )
    return if (description == null) base else base.semantics {
        contentDescription = description
        role = clickRole
    }
}

/** 忙碌 / 锁定时整体降透明度，让"点不动"变成看得见的"不可用"。 */
fun Modifier.enabledDim(enabled: Boolean, dim: Float = 0.5f): Modifier =
    if (enabled) this else this.graphicsLayer { alpha = dim }

/**
 * 统一顶栏。返回箭头 / 汉堡二选一，右侧最多两个动作。
 */
@Composable
fun AppChrome(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val palette = LocalPalette.current
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                onBack != null -> BarIcon("返回", onBack) { BackChevron() }
                onMenu != null -> BarIcon("打开设置", onMenu) { Hamburger() }
            }
            Column(
                Modifier.weight(1f).padding(
                    horizontal = if (onBack != null || onMenu != null) Space.x2 else 0.dp,
                ),
            ) {
                Text(
                    title,
                    color = palette.text,
                    style = AppType.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = palette.muted,
                        style = AppType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.x1),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        content()
    }
}

@Composable
private fun BarIcon(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).tapTarget(description = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun BackChevron() {
    val color = LocalPalette.current.text
    Canvas(Modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.66f, size.height * 0.16f)
            lineTo(size.width * 0.30f, size.height * 0.5f)
            lineTo(size.width * 0.66f, size.height * 0.84f)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun Hamburger() {
    val color = LocalPalette.current.text
    Column(verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        repeat(3) {
            Box(Modifier.size(width = 18.dp, height = 2.dp).background(color))
        }
    }
}

/**
 * 列表首帧入场：淡入 + 上移 8dp，只播一次。
 * 列表目前都是 Column（不是 Lazy），行不会被回收，所以滚回来不会重播。
 */
@Composable
fun EnterOnce(index: Int = 0, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(24L * index.coerceIn(0, 8))
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.enter)) + slideInVertically(tween(Motion.enter)) { full -> full / 8 },
    ) {
        content()
    }
}

/** 空态：一句说明 + 一个主操作，不留白。 */
@Composable
fun EmptyState(    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.x4, vertical = Space.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = palette.muted, style = AppType.body, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = Space.x3)) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * 加减按钮。点一下走一步，按住后每 70ms 连续步进，用于亮度这类连续量。
 */
@Composable
fun StepButton(label: String, enabled: Boolean, description: String, onStep: () -> Unit) {
    val palette = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(Radius.small)
    val step = rememberUpdatedState(onStep)
    val live = rememberUpdatedState(enabled)
    var held by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (held) 0.94f else 1f,
        animationSpec = tween(Motion.press),
        label = "step",
    )
    Box(
        Modifier
            .size(MinTouchTarget)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(shape)
            .background(palette.surface.copy(alpha = if (enabled) 0.55f else 0.2f))
            .border(1.dp, if (enabled) palette.hairline else palette.hairline.copy(alpha = 0.4f), shape)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!live.value) return@awaitEachGesture
                    held = true
                    try {
                        step.value.invoke()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        var repeat = 0
                        while (live.value) {
                            val released = withTimeoutOrNull(if (repeat == 0) 420L else 70L) {
                                awaitRelease()
                            }
                            if (released == true) break
                            step.value.invoke()
                            repeat++
                        }
                    } finally {
                        held = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) palette.text else palette.muted,
            fontSize = 20.sp,
            style = AppType.strong,
        )
    }
}

private suspend fun AwaitPointerEventScope.awaitRelease(): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return true
    }
}
