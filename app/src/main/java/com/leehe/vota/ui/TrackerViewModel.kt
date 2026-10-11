package com.leehe.vota.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leehe.vota.data.BackgroundMode
import com.leehe.vota.data.BackgroundStore
import com.leehe.vota.data.BingImage
import com.leehe.vota.data.BingWallpaper
import com.leehe.vota.data.DeviceConfig
import com.leehe.vota.data.DeviceInfoReader
import com.leehe.vota.data.ImageStore
import com.leehe.vota.data.OtaRepository
import com.leehe.vota.data.QueryResult
import com.leehe.vota.data.toIndexedBingImageOrNull
import com.leehe.vota.data.toLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 查询界面状态。所有副作用（网络、加解密、读系统属性、解码图片）都走 [viewModelScope] + IO，
 * Compose 里只读状态。
 */
class TrackerViewModel(app: Application) : AndroidViewModel(app) {

    // ---------------- 查询 ----------------

    var config by mutableStateOf(DeviceConfig())
        private set

    var querying by mutableStateOf(false)
        private set

    var result by mutableStateOf<QueryResult?>(null)
        private set

    var rawLog by mutableStateOf<List<String>>(emptyList())
        private set

    /** 本机信息只读一次 */
    val deviceSummary: DeviceInfoReader.Summary by lazy { DeviceInfoReader.read() }

    fun update(block: (DeviceConfig) -> DeviceConfig) {
        config = block(config)
    }

    /** 用本机读到的 SW_MODEL / 版本等填表 */
    fun fillFromDevice() {
        config = DeviceInfoReader.applyTo(config, deviceSummary)
    }

    fun query() {
        if (querying) return
        querying = true
        result = null
        rawLog = emptyList()

        val snapshot = config
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val log = mutableListOf<String>()
                val r = OtaRepository.query(snapshot) { log += it }
                r to log
            }
            result = outcome.first
            rawLog = outcome.second
            querying = false
        }
    }

    // ---------------- 背景 ----------------

    private val store = BackgroundStore(app)
    private val bgMutex = Mutex()

    var backgroundMode by mutableStateOf(store.mode)
        private set

    /** 当前背景位图；null 时界面会退回内置色块 */
    var backgroundBitmap by mutableStateOf<Bitmap?>(null)
        private set

    /** 当前展示的 Bing 图（含版权信息），非 Bing 模式下为 null */
    var bingImage by mutableStateOf<BingImage?>(null)
        private set

    var backgroundLoading by mutableStateOf(false)
        private set

    /** 背景相关的错误提示（拉取失败等），成功加载后清空 */
    var backgroundError by mutableStateOf<String?>(null)
        private set

    /** 已缓存的 Bing 图列表（今天在前），用来「换一张」 */
    private var bingCache: List<Pair<Int, BingImage>> = emptyList()

    /** 当前展示的缓存序号 */
    private var bingCursor = 0

    init {
        restoreBackground()
    }

    fun selectBackgroundMode(mode: BackgroundMode) {
        if (mode == backgroundMode && backgroundBitmap != null) return
        backgroundMode = mode
        store.mode = mode
        backgroundError = null
        when (mode) {
            BackgroundMode.PATTERN -> {
                backgroundBitmap = null
                bingImage = null
            }

            BackgroundMode.BING -> loadBing(autoRefresh = true)
            BackgroundMode.LOCAL -> loadLocal()
        }
    }

    /** 「换一张」：先翻本地缓存的最近 8 天，翻到底再重新拉一次接口 */
    fun nextBackground() {
        when (backgroundMode) {
            BackgroundMode.BING -> {
                if (bingCache.size > 1) {
                    bingCursor = (bingCursor + 1) % bingCache.size
                    val entry = bingCache[bingCursor]
                    viewModelScope.launch { showBing(entry) }
                } else {
                    loadBing(autoRefresh = true, forceFetch = true)
                }
            }

            BackgroundMode.LOCAL -> loadLocal()
            BackgroundMode.PATTERN -> Unit
        }
    }

    /** 本地相册选图回调 */
    fun onLocalImagePicked(uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            backgroundLoading = true
            val copied = withContext(Dispatchers.IO) { copyToPrivateStorage(uri) }
            if (copied == null) {
                backgroundError = "读取所选图片失败"
            } else {
                store.localImagePath = copied.absolutePath
                backgroundMode = BackgroundMode.LOCAL
                store.mode = BackgroundMode.LOCAL
                showLocal(copied)
                backgroundError = null
            }
            backgroundLoading = false
        }
    }

    // ---------------- 内部实现 ----------------

    private fun restoreBackground() {
        bingCache = store.bingLines.mapNotNull { it.toIndexedBingImageOrNull() }.sortedBy { it.first }
        when (backgroundMode) {
            BackgroundMode.BING -> loadBing(autoRefresh = true)
            BackgroundMode.LOCAL -> loadLocal()
            BackgroundMode.PATTERN -> Unit
        }
    }

    private fun loadLocal() {
        val path = store.localImagePath
        if (path == null) {
            // 还没选过图不是错误，交给界面显示引导文案
            backgroundBitmap = null
            backgroundError = null
            return
        }
        val file = File(path)
        if (!file.exists()) {
            backgroundBitmap = null
            backgroundError = "本地图片已不存在（可能被清理）"
            return
        }
        viewModelScope.launch {
            backgroundLoading = true
            val bitmap = withContext(Dispatchers.IO) { runCatching { ImageStore.decode(file.readBytes()) }.getOrNull() }
            backgroundBitmap = bitmap
            bingImage = null
            backgroundError = if (bitmap == null) "本地图片解码失败" else null
            backgroundLoading = false
        }
    }

    /**
     * Bing 模式：同一天只请求一次接口；跨天自动刷新。
     * 接口失败时**保留当前背景**并给出提示，不会把界面搞成空白。
     */
    private fun loadBing(autoRefresh: Boolean, forceFetch: Boolean = false) {
        viewModelScope.launch {
            bgMutex.withLock {
                val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val stale = store.bingFetchedDay != today
                val needFetch = forceFetch || bingCache.isEmpty() || (autoRefresh && stale)

                if (needFetch) {
                    backgroundLoading = true
                    val fetched = withContext(Dispatchers.IO) {
                        runCatching { BingWallpaper.fetchImages() }.getOrNull()
                    }
                    if (fetched != null && fetched.isNotEmpty()) {
                        bingCache = fetched.mapIndexed { i, img -> i to img }
                        store.bingLines = fetched.mapIndexed { i, img -> img.toLine(i) }
                        store.bingFetchedDay = today
                        bingCursor = 0
                        backgroundError = null
                    } else if (bingCache.isEmpty()) {
                        backgroundLoading = false
                        backgroundError = "Bing 每日一图获取失败，请检查网络"
                        return@withLock
                    }
                }

                if (bingCache.isEmpty()) {
                    backgroundError = "Bing 每日一图暂不可用"
                    return@withLock
                }

                showBing(bingCache[bingCursor.coerceIn(bingCache.indices)])
            }
        }
    }

    private suspend fun showBing(entry: Pair<Int, BingImage>) {
        backgroundLoading = true
        val (index, image) = entry
        val bitmap = withContext(Dispatchers.IO) {
            BingWallpaper.loadBitmap(getApplication(), image)
        }
        if (bitmap != null) {
            backgroundBitmap = bitmap
            bingImage = image
            bingCursor = index
            backgroundError = null
        } else {
            backgroundError = "图片下载失败，已保留上一张"
        }
        backgroundLoading = false
    }

    private fun showLocal(file: File) {
        viewModelScope.launch {
            backgroundLoading = true
            val bitmap = withContext(Dispatchers.IO) { runCatching { ImageStore.decode(file.readBytes()) }.getOrNull() }
            backgroundBitmap = bitmap
            bingImage = null
            backgroundError = if (bitmap == null) "本地图片解码失败" else null
            backgroundLoading = false
        }
    }

    /** 相册图片拷进私有目录：不依赖长期 URI 授权，换设备/清缓存后的行为也可预期 */
    private fun copyToPrivateStorage(uri: android.net.Uri): File? = runCatching {
        val dir = File(getApplication<Application>().filesDir, "background").apply { mkdirs() }
        val target = File(dir, "local_image.jpg")
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.takeIf { it.length() > 0 }
    }.getOrNull()
}
