package com.leehe.vota.data

import android.content.Context
import androidx.core.content.edit

/**
 * 轻量偏好存储（不需要 DataStore 那一套）。
 * 背景选择、Bing 图片信息都是小字符串，SharedPreferences 足够。
 */
class BackgroundStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("vota.background", Context.MODE_PRIVATE)

    var mode: BackgroundMode
        get() = runCatching { BackgroundMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(BackgroundMode.BING)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    /** 最近一次成功拉取的 Bing 图片列表，一行一张：`idx|startDate|urlBase|copyright` */
    var bingLines: List<String>
        get() = prefs.getString(KEY_BING, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        set(value) = prefs.edit { putString(KEY_BING, value.joinToString("\n")) }

    /** Bing 列表的本地日期（yyyyMMdd），用于「每天自动换一次」 */
    var bingFetchedDay: String
        get() = prefs.getString(KEY_BING_DAY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_BING_DAY, value) }

    /** 本地图片拷贝到私有目录后的绝对路径 */
    var localImagePath: String?
        get() = prefs.getString(KEY_LOCAL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LOCAL) else putString(KEY_LOCAL, value)
        }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_BING = "bing_list"
        const val KEY_BING_DAY = "bing_day"
        const val KEY_LOCAL = "local_path"
    }
}

/** 序列化 Bing 图片列表（带 idx，便于「换一张」跨进程恢复） */
fun BingImage.toLine(index: Int): String = "$index|$startDate|$urlBase|$copyright"

/** 反序列化：[BingImage] 与它的 idx */
fun String.toIndexedBingImageOrNull(): Pair<Int, BingImage>? {
    val parts = split('|')
    if (parts.size < 4) return null
    val index = parts[0].toIntOrNull() ?: return null
    return index to BingImage(
        startDate = parts[1],
        urlBase = parts[2],
        copyright = parts.subList(3, parts.size).joinToString("|"),
    )
}
