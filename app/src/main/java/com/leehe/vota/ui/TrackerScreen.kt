package com.leehe.vota.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.leehe.vota.data.BackgroundMode
import com.leehe.vota.data.DeviceConfig
import com.leehe.vota.data.QueryResult
import com.leehe.vota.data.UpdateInfo
import com.leehe.vota.ui.theme.InkBlack

private val CardShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(14.dp)
private val SectionShape = RoundedCornerShape(24.dp)

/**
 * 主界面。
 *
 * 液态玻璃的接法（和酷安 16.6.1 一致）：
 * 1. `rememberLayerBackdrop { drawRect(底色); drawContent() }` 录制背景；
 * 2. 背景子树挂 `Modifier.layerBackdrop(backdrop)` 声明"这些像素会被录进去"；
 * 3. 浮在背景之上的面板用 `Modifier.glassSurface(backdrop, …)` 画玻璃，
 *    于是面板边缘的像素会被 `lens()` 真正折射。
 *
 * 背景可以是 Bing 每日一图、本地相册图片或内置色块；三种都静止，折射区域才稳定可见。
 */
@Composable
fun TrackerScreen(
    onOpenUrl: (String) -> Unit,
    onToast: (String) -> Unit,
    runtimeShaderSupported: Boolean,
    viewModel: TrackerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val config = viewModel.config

    // ---- 背景录制：底色 + 被标记子树 ----
    val backdrop = rememberLayerBackdrop {
        drawRect(InkBlack)
        drawContent()
    }

    // ---- 相册选图（Photo Picker，不需要读存储权限）----
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onLocalImagePicked(uri) }

    Box(Modifier.fillMaxSize()) {
        // ---- 被录制的背景：图片或内置色块 ----
        BackgroundSurface(
            backdrop = backdrop,
            bitmap = viewModel.backgroundBitmap,
            modifier = Modifier.fillMaxSize(),
        )

        // ---- 前景：全部是玻璃面板 ----
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            AppHeader(
                backdrop = backdrop,
                runtimeShaderSupported = runtimeShaderSupported,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            BackgroundPanel(
                backdrop = backdrop,
                mode = viewModel.backgroundMode,
                bingCopyright = viewModel.bingImage?.copyright,
                loading = viewModel.backgroundLoading,
                error = viewModel.backgroundError,
                onSelectMode = viewModel::selectBackgroundMode,
                onNext = viewModel::nextBackground,
                onPickLocal = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            ConfigPanel(
                backdrop = backdrop,
                config = config,
                deviceLine = viewModel.deviceSummary.line,
                onUpdate = viewModel::update,
                onFillFromDevice = viewModel::fillFromDevice,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            QueryButton(
                backdrop = backdrop,
                querying = viewModel.querying,
                hasResult = viewModel.result != null,
                onClick = viewModel::query,
                modifier = Modifier.fillMaxWidth(),
            )

            viewModel.result?.let { result ->
                Spacer(Modifier.height(14.dp))
                ResultPanel(
                    backdrop = backdrop,
                    result = result,
                    config = config,
                    rawLog = viewModel.rawLog,
                    onOpenUrl = onOpenUrl,
                    onCopy = { text ->
                        copyToClipboard(context, text)
                        onToast("下载链接已复制")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))

            AboutPanel(
                backdrop = backdrop,
                runtimeShaderSupported = runtimeShaderSupported,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

/* ==================================================================== *
 *  背景控制面板
 * ==================================================================== */

@Composable
private fun BackgroundPanel(
    backdrop: Backdrop,
    mode: BackgroundMode,
    bingCopyright: String?,
    loading: Boolean,
    error: String?,
    onSelectMode: (BackgroundMode) -> Unit,
    onNext: () -> Unit,
    onPickLocal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = BackgroundMode.entries

    Column(
        modifier
            .glassSurface(backdrop, CardShape, blurRadius = 6.dp, surfaceAlpha = 0.52f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("背景", fillWidth = false)
            Spacer(Modifier.width(10.dp))
            AnimatedVisibility(visible = loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.weight(1f))

            // 动作按钮随模式切换（带淡入淡出）
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "bgAction",
            ) { current ->
                when (current) {
                    BackgroundMode.BING -> GlassActionChip(
                        text = "换一张",
                        onClick = onNext,
                        backdrop = backdrop,
                        enabled = !loading,
                    )

                    BackgroundMode.LOCAL -> GlassActionChip(
                        text = "选择图片",
                        onClick = onPickLocal,
                        backdrop = backdrop,
                        enabled = !loading,
                    )

                    BackgroundMode.PATTERN -> GlassActionChip(
                        text = "不联网",
                        onClick = {},
                        backdrop = backdrop,
                        enabled = false,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        GlassSegmentedControl(
            options = modes.map { it.label() },
            selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
            onSelect = { index -> onSelectMode(modes[index]) },
            backdrop = backdrop,
        )

        // 版权信息 / 引导 / 错误提示：切换时淡入上滑，面板高度变化不会突兀
        val caption = when {
            error != null -> "⚠ $error"
            bingCopyright != null -> bingCopyright
            mode == BackgroundMode.LOCAL -> "点右上角「选择图片」从相册挑一张"
            mode == BackgroundMode.PATTERN -> "内置色块不联网，折射最明显"
            else -> null
        }
        AnimatedContent(
            targetState = caption,
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(160))
            },
            label = "bgCaption",
        ) { text ->
            if (text.isNullOrBlank()) {
                Spacer(Modifier.height(0.dp))
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    color = if (error != null) {
                        Color(0xFFFFB4AB)
                    } else {
                        Color.White.copy(alpha = 0.58f)
                    },
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun BackgroundMode.label(): String = when (this) {
    BackgroundMode.BING -> "Bing 每日"
    BackgroundMode.LOCAL -> "本地图片"
    BackgroundMode.PATTERN -> "内置色块"
}

/* ==================================================================== *
 *  背景
 *
 *  两种来源：Bing 每日一图 / 本地图片（都走同一张 [Image]），
 *  以及完全离线的内置色块。
 *  不管哪种，都要盖一层深色 scrim —— 图片直接透上来会盖掉白字，
 *  玻璃面板的折射也要有干净的中低亮度底子才好看。
 * ==================================================================== */

/** 图片背景的压暗程度：亮图也能保证玻璃面板与白字可读 */
private const val ImageScrimAlpha = 0.42f

@Composable
private fun BackgroundSurface(
    backdrop: LayerBackdrop,
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
    ) {
        // 切换背景时交叉淡入淡出，避免整屏闪一下
        AnimatedContent(
            targetState = bitmap,
            transitionSpec = { fadeIn(tween(520)) togetherWith fadeOut(tween(380)) },
            label = "background",
        ) { image ->
            if (image == null) {
                PatternBackground(Modifier.fillMaxSize())
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 压暗：图片直接透上来会盖掉白字，折射也需要干净的中低亮度底子
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ImageScrimAlpha))
        )
    }
}

/** 内置色块：不依赖网络，折射最明显 */
@Composable
private fun PatternBackground(modifier: Modifier = Modifier) {
    val spec = remember { BlobBackground() }

    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF05060B),
                    Color(0xFF0A0C18),
                    Color(0xFF04050A),
                )
            )
        )
    ) {
        // 彩色光斑：radialGradient 是按屏幕尺寸算圆心的，所以只能用 Canvas 画
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            spec.blobs.forEach { blob ->
                val center = Offset(w * blob.xf, h * blob.yf)
                val radius = size.minDimension * blob.rf
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            blob.color.copy(alpha = 0.75f),
                            blob.color.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }

        // 色块阵列：折射的"读数尺"，玻璃划过时能看到内容被拉动
        TileGrid()
    }
}

private data class Blob(
    val color: Color,
    val xf: Float,
    val yf: Float,
    val rf: Float,
)

private class BlobBackground {
    val blobs = listOf(
        Blob(Color(0xFF1B3DF0), 0.16f, 0.06f, 0.46f),
        Blob(Color(0xFF00C2C7), 0.92f, 0.16f, 0.42f),
        Blob(Color(0xFF8B2BE2), 0.08f, 0.46f, 0.50f),
        Blob(Color(0xFFFF4D6D), 0.88f, 0.60f, 0.44f),
        Blob(Color(0xFF12B76A), 0.30f, 0.88f, 0.48f),
        Blob(Color(0xFFFFB020), 0.80f, 0.98f, 0.40f),
    )
}

/** 机身/平板通用的一张彩色色块表：色相错开，便于看出边缘被"挤压/放大" */
private val tileColors = listOf(
    Color(0xFF3B82F6) to Color(0xFF12275E),
    Color(0xFF22D3EE) to Color(0xFF0B4650),
    Color(0xFFA855F7) to Color(0xFF3B1063),
    Color(0xFFF43F5E) to Color(0xFF5C0A22),
    Color(0xFFF59E0B) to Color(0xFF5A3605),
    Color(0xFF10B981) to Color(0xFF064E3B),
    Color(0xFF6366F1) to Color(0xFF1E1B5C),
    Color(0xFF14B8A6) to Color(0xFF0B4A46),
)

@Composable
private fun TileGrid() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp, vertical = 90.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(7) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) { col ->
                    val index = (row * 3 + col) % tileColors.size
                    val (start, end) = tileColors[index]
                    Box(
                        Modifier
                            .weight(if (col == 1) 1.3f else 1f)
                            .height(96.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.linearGradient(listOf(start, end))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${'A' + index}${row}",
                            color = Color.White.copy(alpha = 0.30f),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/* ==================================================================== *
 *  顶部标题
 * ==================================================================== */

@Composable
private fun AppHeader(
    backdrop: Backdrop,
    runtimeShaderSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .glassSurface(backdrop, CardShape, blurRadius = 7.dp, surfaceAlpha = 0.52f)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "VIVO OTA Tracker",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "官方 OTA 固件直链查询",
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 12.sp,
            )
        }
        // 玻璃"芯片"：折射可用性指示灯
        Box(
            Modifier
                .glassSurface(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    refractionHeight = 16.dp,
                    refractionAmount = 24.dp,
                    blurRadius = 3.dp,
                    surfaceAlpha = 0.58f,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (runtimeShaderSupported) "AGSL 折射" else "仅模糊",
                color = if (runtimeShaderSupported) Color(0xFF9CF6C8) else Color(0xFFFFC98A),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/* ==================================================================== *
 *  查询参数面板
 * ==================================================================== */

@Composable
private fun ConfigPanel(
    backdrop: Backdrop,
    config: DeviceConfig,
    deviceLine: String,
    onUpdate: ((DeviceConfig) -> DeviceConfig) -> Unit,
    onFillFromDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advanced by remember { mutableStateOf(false) }

    Column(
        modifier
            .glassSurface(backdrop, CardShape)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        SectionTitle("机型参数")

        // 设备类型
        Text(
            text = "设备类型",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        GlassSegmentedControl(
            options = listOf("手机 phone", "平板 tablet"),
            selectedIndex = if (config.deviceType == "tablet") 1 else 0,
            onSelect = { index ->
                onUpdate { it.copy(deviceType = if (index == 1) "tablet" else "phone") }
            },
            backdrop = backdrop,
        )

        Spacer(Modifier.height(6.dp))

        GlassTextField(
            label = "SW_MODEL（软件型号）",
            value = config.modelSwVer,
            hint = "如 PD2408 / DPD2106",
            onValueChange = { v -> onUpdate { it.copy(modelSwVer = v.trim()) } },
        )
        GlassTextField(
            label = "DEVICE_MODEL（机型）",
            value = config.deviceModel,
            hint = "如 V2408A / PA2170",
            onValueChange = { v -> onUpdate { it.copy(deviceModel = v.trim()) } },
        )
        GlassTextField(
            label = "当前软件版本",
            value = config.swVersion,
            hint = "如 16.1.16.5.W10 / 8.7.22",
            onValueChange = { v -> onUpdate { it.copy(swVersion = v.trim()) } },
        )
        GlassTextField(
            label = "Android / OriginOS 大版本",
            value = config.androidVer.toString(),
            hint = "如 16",
            keyboardType = KeyboardType.Number,
            onValueChange = { v ->
                v.filter { it.isDigit() }.take(2).toIntOrNull()
                    ?.let { n -> onUpdate { it.copy(androidVer = n) } }
            },
        )

        // 完整包 / 增量包
        Spacer(Modifier.height(8.dp))
        Text(
            text = "包类型",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        GlassSegmentedControl(
            options = listOf("完整包 isFull", "增量包 delta"),
            selectedIndex = if (config.isFull) 0 else 1,
            onSelect = { index -> onUpdate { it.copy(isFull = index == 0) } },
            backdrop = backdrop,
        )

        // 本机信息 + 一键填充
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本机：$deviceLine",
                color = Color.White.copy(alpha = 0.60f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            GlassPillButton(
                text = "读取本机",
                selected = false,
                backdrop = backdrop,
                height = 32.dp,
                onClick = onFillFromDevice,
            )
        }

        // 高级
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (advanced) "▾ 高级选项" else "▸ 高级选项",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 12.sp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { advanced = !advanced },
        )

        if (advanced) {
            Spacer(Modifier.height(6.dp))
            GlassTextField(
                label = "序列号 snp（平板用）",
                value = config.snp,
                hint = "A0000000000000A",
                onValueChange = { v -> onUpdate { it.copy(snp = v.trim()) } },
            )
            GlassSwitchRow(
                label = "verbose 调试输出",
                hint = "展示明文请求参数与解密后的原始响应",
                checked = config.verbose,
                backdrop = backdrop,
                onCheckedChange = { v -> onUpdate { it.copy(verbose = v) } },
            )
            Text(
                text = "verbose 的明文参数/响应会显示在结果卡片里。",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
    }
}

/* ==================================================================== *
 *  查询按钮
 * ==================================================================== */

@Composable
private fun QueryButton(
    backdrop: Backdrop,
    querying: Boolean,
    hasResult: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)

    Row(
        modifier
            .height(58.dp)
            .glassSurface(
                backdrop = backdrop,
                shape = shape,
                refractionHeight = 22.dp,
                refractionAmount = 34.dp,
                blurRadius = 5.dp,
                surfaceAlpha = 0.60f,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !querying,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (querying) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = when {
                querying -> "查询中…"
                hasResult -> "重新查询"
                else -> "查询 OTA 更新"
            },
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ==================================================================== *
 *  结果面板
 * ==================================================================== */

@Composable
private fun ResultPanel(
    backdrop: Backdrop,
    result: QueryResult,
    config: DeviceConfig,
    rawLog: List<String>,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .glassSurface(backdrop, CardShape, blurRadius = 7.dp, surfaceAlpha = 0.58f)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        when (result) {
            is QueryResult.Success -> {
                SectionTitle("查询结果")
                SuccessContent(result.info, config, onOpenUrl, onCopy)
            }

            is QueryResult.NoUpdate -> {
                SectionTitle("查询结果")
                Box(
                    Modifier
                        .clip(SectionShape)
                        .background(Color(0x22FFB020))
                        .border(1.dp, Color(0x55FFB020), SectionShape)
                        .padding(14.dp),
                ) {
                    Text(
                        text = "服务端返回「没有更新」（retcode 210）。\n\n" +
                            "常见原因：\n" +
                            "· 填的 SW_VERSION 不在官方开放升级的名单里\n" +
                            "· 该机型/版本的推送名额已满，或官方临时下架\n" +
                            "· 请求过于频繁被临时限流\n\n" +
                            "换一个机型或不带 .W 的版本号再试。",
                        color = Color(0xFFFFD79A),
                        fontSize = 12.sp,
                    )
                }
            }

            is QueryResult.Failure -> {
                SectionTitle("查询失败")
                Box(
                    Modifier
                        .clip(SectionShape)
                        .background(Color(0x22FF5252))
                        .border(1.dp, Color(0x55FF5252), SectionShape)
                        .padding(14.dp),
                ) {
                    Text(
                        text = result.message,
                        color = Color(0xFFFFB4AB),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (rawLog.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            RawLogSection(rawLog)
        }
    }
}

@Composable
private fun SuccessContent(
    info: UpdateInfo,
    config: DeviceConfig,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    // 顶部一句话
    Text(
        text = info.version ?: info.filename ?: "已获取固件信息",
        color = Color(0xFF9CF6C8),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(10.dp))

    InfoRow("机型", "${config.modelSwVer} / ${config.deviceModel}")
    info.filename?.let { InfoRow("文件名", it) }
    info.sizeText?.let { InfoRow("大小", it) }
    InfoRow("包类型", if (config.isFull) "完整包 (isFull=1)" else "增量包 (isFull=0)")

    info.downloadUrl?.let { url ->
        Spacer(Modifier.height(14.dp))
        Text(
            text = "下载直链",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(FieldShape)
                .background(Color(0x22FFFFFF))
                .border(1.dp, Color(0x33FFFFFF), FieldShape)
                .padding(12.dp),
        ) {
            Text(
                text = url,
                color = Color(0xFFCFE9FF),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip("复制链接") { onCopy(url) }
            ActionChip("打开链接") { onOpenUrl(url) }
        }
    }

    info.changelogUrl?.let { url ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = "更新日志",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        ActionChip("打开更新说明 (h5Url)") { onOpenUrl(url) }
    }
}

@Composable
private fun RawLogSection(log: List<String>) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = if (expanded) "▾ 调试输出 (${log.size} 行)" else "▸ 调试输出 (${log.size} 行)",
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 12.sp,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { expanded = !expanded },
    )

    if (expanded) {
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(FieldShape)
                .background(Color(0x33000000))
                .border(1.dp, Color(0x22FFFFFF), FieldShape)
                .padding(10.dp),
        ) {
            Text(
                text = log.joinToString("\n\n"),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/* ==================================================================== *
 *  关于
 * ==================================================================== */

@Composable
private fun AboutPanel(
    backdrop: Backdrop,
    runtimeShaderSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .glassSurface(backdrop, CardShape, blurRadius = 6.dp, surfaceAlpha = 0.52f)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        SectionTitle("关于")
        Text(
            text = listOf(
                "VIVO-OTA-Tracker 的 Android 移植版：把官方 OTA 查询协议",
                "（AES-128-CBC + 16 字节自定义头的 jvq_param 加密包）搬到手机上，",
                "直接取固件下载直链。",
            ).joinToString(""),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = listOf(
                "界面用 Kyant backdrop 实现液态玻璃：blur → vibrancy → lens 真折射",
                if (runtimeShaderSupported) "（本机 AGSL 可用）" else "（本机无 AGSL，只剩模糊）",
            ).joinToString(""),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "仅供技术学习与交流，请勿用于任何非法或商业用途。查询结果来自官方服务端，" +
                "出现「没有更新」不代表设备一定没有新版本。",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
    }
}

/* ==================================================================== *
 *  小组件
 * ==================================================================== */

@Composable
private fun SectionTitle(title: String, fillWidth: Boolean = true) {
    Row(
        if (fillWidth) Modifier.fillMaxWidth() else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(4.dp, 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF7CC7FF))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun GlassTextField(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(Color(0xFF7CC7FF)),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldShape)
                .background(Color(0x1FFFFFFF))
                .border(1.dp, Color(0x2EFFFFFF), FieldShape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            color = Color.White.copy(alpha = 0.32f),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0x26FFFFFF))
            .border(1.dp, Color(0x3DFFFFFF), RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("OTA URL", text))
}
