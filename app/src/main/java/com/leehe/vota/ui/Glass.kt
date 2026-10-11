package com.leehe.vota.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRuntimeShaderSupported

/**
 * 液态玻璃（Liquid Glass）表面。
 *
 * 效果按调用顺序叠加：`blur → vibrancy → lens`。
 * `lens()` 是真正的折射（AGSL / RuntimeShader，**API 33+**），
 * 低版本库内部会直接跳过，只剩模糊 —— 这就是"毛玻璃"和"液态玻璃"的区别。
 *
 * 注意：2.0.1 的 `blur()` 收的是 **Float 像素值**，不是 Dp；`lens()` 的参数也是像素。
 * 这里因为 `effects` 的 receiver 是 `BackdropEffectScope`（继承 Density），
 * 可以直接用 `Dp.toPx()`。
 *
 * [surfaceAlpha] 是玻璃本体的深色底：彩色背景直接透出来会盖掉白字，
 * 压一层暗色后既保留折射又保证可读性。
 */
fun Modifier.glassSurface(
    backdrop: Backdrop,
    shape: Shape,
    refractionHeight: Dp = 28.dp,
    refractionAmount: Dp = 48.dp,
    depthEffect: Boolean = true,
    chromaticAberration: Boolean = false,
    blurRadius: Dp = 4.dp,
    vibrancyEnabled: Boolean = true,
    surfaceAlpha: Float = 0.50f,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        // 先磨砂，再提亮：顺序反了的话 vibrancy 的提亮会被模糊摊平
        if (blurRadius > 0.dp) blur(blurRadius.toPx())
        if (vibrancyEnabled) vibrancy()
        if (isRuntimeShaderSupported()) {
            lens(
                refractionHeight = refractionHeight.toPx(),
                refractionAmount = refractionAmount.toPx(),
                depthEffect = depthEffect,
                chromaticAberration = chromaticAberration,
            )
        }
    },
    onDrawSurface = {
        drawRect(surfaceTint.copy(alpha = surfaceAlpha))
    },
)

/** 玻璃本体的深色底（面板都是深色玻璃，白字才站得住） */
private val surfaceTint = Color(0xFF0B1020)

/** 常用圆角形状 */
fun glassShape(corner: Dp): Shape = RoundedCornerShape(corner)

/* ------------------------------------------------------------------ *
 *  玻璃控件
 * ------------------------------------------------------------------ */

@Composable
fun GlassSwitchRow(
    label: String,
    checked: Boolean,
    backdrop: Backdrop,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 玻璃胶囊按钮：选中态用玻璃面板实时折射背景 */
@Composable
fun GlassPillButton(
    text: String,
    selected: Boolean,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
) {
    val shape = RoundedCornerShape(percent = 50)
    val base = if (selected) {
        Modifier.glassSurface(
            backdrop = backdrop,
            shape = shape,
            refractionHeight = 18.dp,
            refractionAmount = 26.dp,
            blurRadius = 3.dp,
            surfaceAlpha = 0.55f,
        )
    } else {
        Modifier
    }

    Box(
        modifier
            .height(height)
            .then(base)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 文字颜色/字重跟着选中态过渡，避免「啪」地跳变
        val alpha by animateColorAsState(
            targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.62f),
            animationSpec = tween(220),
            label = "pillText",
        )
        Text(
            text = text,
            color = alpha,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/* ------------------------------------------------------------------ *
 *  分段开关（带滑动玻璃指示器）
 * ------------------------------------------------------------------ */

/**
 * 玻璃分段控件：选中项由一个**滑动的玻璃滑块**承载。
 *
 * 滑块位置用 `animateDpAsState` + `spring`（低阻尼，收尾有一点回弹），
 * 宽度用 `animateDpAsState` + `tween`（按文字宽度自适应，切换时不会突跳）。
 * 滑块本身是玻璃面板，滑动过程中背景会被实时折射 —— 这是这套动画的重点。
 *
 * 因为滑块要压在其它分段之上，点击区域和滑块必须同层，所以用 [BoxWithConstraints]
 * 自己算每个分段的偏移，而不依赖 `Row` 的 `weight`。
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
) {
    if (options.isEmpty()) return
    val gap = 6.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0x22FFFFFF)),
    ) {
        val available = maxWidth - gap * 2
        val segWidth = available / options.size

        // 滑块：位置用弹簧（收尾带一点回弹），宽度用 tween（避免切换瞬间突跳）
        val offsetX by animateDpAsState(
            targetValue = gap + segWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            label = "segOffset",
        )
        val sliderWidth by animateDpAsState(
            targetValue = segWidth,
            animationSpec = tween(220),
            label = "segWidth",
        )

        Box(
            Modifier
                .offset(x = offsetX)
                .width(sliderWidth)
                .height(height - gap * 2)
                .align(Alignment.CenterStart)
                .glassSurface(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    refractionHeight = 18.dp,
                    refractionAmount = 26.dp,
                    blurRadius = 3.dp,
                    surfaceAlpha = 0.58f,
                ),
        )

        Row(Modifier.fillMaxWidth().height(height)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .width(segWidth)
                        .height(height)
                        .padding(horizontal = gap / 2)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    val color by animateColorAsState(
                        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.60f),
                        animationSpec = tween(220),
                        label = "segText",
                    )
                    Text(
                        text = label,
                        color = color,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 胶囊动作按钮（「换一张」/「选图」这类） */
@Composable
fun GlassActionChip(
    text: String,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(percent = 50)
    val textColor by animateColorAsState(
        targetValue = if (enabled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.38f),
        animationSpec = tween(200),
        label = "chipText",
    )
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (enabled) 0.52f else 0.34f,
        animationSpec = tween(200),
        label = "chipSurface",
    )

    Row(
        modifier
            .height(34.dp)
            .glassSurface(
                backdrop = backdrop,
                shape = shape,
                refractionHeight = 16.dp,
                refractionAmount = 22.dp,
                blurRadius = 3.dp,
                surfaceAlpha = surfaceAlpha,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        }
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
