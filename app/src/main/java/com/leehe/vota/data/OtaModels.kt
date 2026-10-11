package com.leehe.vota.data

/** 查询参数（对应 Python 版的 `DeviceConfig`） */
data class DeviceConfig(
    val deviceType: String = "phone",          // phone | tablet
    val modelSwVer: String = "PD2408",         // SW_MODEL，如 PD2408
    val deviceModel: String = "V2408A",        // DEVICE_MODEL，如 V2408A
    val swVersion: String = "16.1.16.5.W10",   // 当前版本
    val androidVer: Int = 16,                  // Android / OriginOS 大版本
    val snp: String = "A0000000000000A",       // 序列号（平板用）
    val isFull: Boolean = true,                // 完整包
    val verbose: Boolean = false,
) {
    val isPhone: Boolean get() = deviceType == "phone"
}

/** 解析后的更新信息（对应 Python 版的 `UpdateInfo`） */
data class UpdateInfo(
    val version: String? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val sizeMb: Long? = null,
    val downloadUrl: String? = null,
    val changelogUrl: String? = null,
    val rawResponse: String? = null,
) {
    val sizeText: String?
        get() = sizeBytes?.let { "%,d 字节 · %d MB".format(it, sizeMb ?: (it / 1048576)) }
}

/** 一次查询的完整结果 */
sealed interface QueryResult {
    data class Success(val info: UpdateInfo) : QueryResult
    /** 服务端明确回复"没有更新"（retcode 210） */
    data class NoUpdate(val raw: String) : QueryResult
    data class Failure(val message: String) : QueryResult
}
