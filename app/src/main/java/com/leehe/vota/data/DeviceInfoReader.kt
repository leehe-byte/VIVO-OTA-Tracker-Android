package com.leehe.vota.data

import android.os.Build

/**
 * 读本机参数，尽量把查询表单填满。
 *
 * vivo/OriginOS 在 `ro.build.*` 里暴露了不少自描述字段，取用优先级：
 *
 * | 表单字段 | 来源 |
 * |---|---|
 * | SW_MODEL | `ro.product.model.bbk`（PD2324） |
 * | DEVICE_MODEL | `ro.build.version.bbk` 的第一段（PD2324F → 或 `Build.MODEL` 的 V2324A） |
 * | SW_VERSION | `ro.build.version.bbk` 去掉尾部 `.V000L1` |
 * | 设备类型 | `ro.build.characteristics` 含 pad/tablet → tablet |
 *
 * 有读不到的情况（非 vivo 设备、字段改名）就留空，让用户手填 —— 解析失败不影响使用。
 */
object DeviceInfoReader {

    /** 本机摘要，显示在界面顶部 */
    data class Summary(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdkInt: Int,
        val swModel: String?,
        val swVersion: String?,
        val deviceModel: String?,
        val deviceType: String,
        val isVivo: Boolean,
    ) {
        val line: String
            get() = buildString {
                append(manufacturer.ifBlank { "?" })
                append(' ')
                append(model)
                append(" · Android ")
                append(androidRelease)
                append(" (API ")
                append(sdkInt)
                append(')')
            }
    }

    /** `ro.build.version.bbk` 的解析结果，例如 `PD2324F_A_16.2.13.0.W10.V000L1` */
    data class BbkVersion(
        val swModel: String?,
        val deviceModelCandidate: String?,
        val swVersion: String?,
    )

    fun read(): Summary {
        val bbkRaw = systemProperty("ro.build.version.bbk").orEmpty()
        val parsed = parseBbkVersion(bbkRaw)
        val characteristics = systemProperty("ro.build.characteristics").orEmpty()
        val isVivo = Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
            Build.BRAND.contains("vivo", ignoreCase = true) ||
            systemProperty("ro.vivo.os.version") != null

        val deviceType = if (
            characteristics.contains("tablet", ignoreCase = true) ||
            characteristics.contains("pad", ignoreCase = true) ||
            Build.MODEL.startsWith("PA", ignoreCase = true)
        ) "tablet" else "phone"

        val swModel = systemProperty("ro.product.model.bbk")
            ?.takeIf { it.matches(Regex("[A-Za-z]{1,3}\\d{3,5}.*")) }
            ?: parsed.swModel

        // 优先用公开机型（V2324A），取不到才退回 bbk 里的 PD2324F
        val deviceModel = Build.MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: parsed.deviceModelCandidate

        return Summary(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            swModel = swModel,
            swVersion = parsed.swVersion,
            deviceModel = deviceModel,
            deviceType = deviceType,
            isVivo = isVivo,
        )
    }

    /** 把本机读到的值套到当前配置上（读不到的字段保持用户已填内容） */
    fun applyTo(config: DeviceConfig, summary: Summary = read()): DeviceConfig = config.copy(
        deviceType = summary.deviceType,
        modelSwVer = summary.swModel ?: config.modelSwVer,
        deviceModel = summary.deviceModel ?: config.deviceModel,
        swVersion = summary.swVersion ?: config.swVersion,
        // 这个字段用于 Android/OriginOS 大版本（平板分支的 romVersion），
        // 取 "16" 而不是 API level "36"，后者是协议里另一套编号
        androidVer = majorVersionOf(summary.androidRelease) ?: config.androidVer,
    )

    /** "16" → 16；带小版本或预览代号时只取首位数字 */
    fun majorVersionOf(release: String): Int? =
        Regex("(\\d+)").find(release)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 解析 `PD2324F_A_16.2.13.0.W10.V000L1`：
     * - 第 0 段去掉末尾单个字母 → SW_MODEL（PD2324F → PD2324）
     * - 第 2 段去掉尾部 `.V000L1` → SW_VERSION
     */
    fun parseBbkVersion(raw: String): BbkVersion {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return BbkVersion(null, null, null)

        val parts = trimmed.split('_')
        val first = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val swModel = first?.let { Regex("^([A-Za-z]{1,3}\\d{3,5})[A-Z]?$").find(it)?.groupValues?.get(1) }

        val swVersion = parts.getOrNull(2)
            ?.substringBefore(".V000L1", missingDelimiterValue = parts[2])
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return BbkVersion(
            swModel = swModel,
            deviceModelCandidate = first,
            swVersion = swVersion,
        )
    }

    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
