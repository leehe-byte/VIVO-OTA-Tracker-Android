package com.leehe.vota.data

import com.leehe.vota.crypto.VivoProtocol
import com.leehe.vota.net.OtaHttpClient
import com.leehe.vota.net.OtaNetworkException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * OTA 查询业务层：构造请求参数 → 发加密请求 → 解析响应。
 * 逐行对齐原项目 `VivoOtaTracker.py` 的 `build_request_params` / `parse_update_response`。
 */
object OtaRepository {

    /** 生成 15 位随机 IMEI（原实现的 `generate_imei`） */
    private fun generateImei(): String = buildString(15) {
        repeat(15) { append(Random.nextInt(0, 10)) }
    }

    /** 构造请求参数；LinkedHashMap 保证顺序与原 Python dict 一致 */
    fun buildRequestParams(config: DeviceConfig): LinkedHashMap<String, String> {
        val isPhone = config.isPhone
        val hwVer = config.modelSwVer + "MA"

        val hasW = config.swVersion.contains(".W")
        val fullSwVersion = if (hasW) config.swVersion + ".V000L1" else config.swVersion
        val fullVer = if (hasW) {
            "${config.modelSwVer}_A_${config.swVersion}.V000L1"
        } else {
            "${config.modelSwVer}_A_${config.swVersion}"
        }
        val versionLong = if (hasW) {
            "${config.modelSwVer}_N_${config.modelSwVer}MA_${config.swVersion}.V000L1"
        } else {
            "${config.modelSwVer}_N_${config.modelSwVer}MA_${config.swVersion}"
        }

        val ts = SimpleDateFormat("yy_MM_dd-HH_mm_ss", Locale.US).format(Date())
        val elapsedTime: Int = if (isPhone) {
            140_000 + Random.nextInt(0, 80_000)
        } else {
            2_000_000 + Random.nextInt(0, 500_000)
        }

        val params = linkedMapOf(
            "vgcNewActiveVer" to "",
            "nt" to "WIFI",
            "vgcSwVer" to "1.1.1",
            "fullVer" to fullVer,
            "emmcid" to "",
            "sm1" to "null",
            "sm2" to "null",
            "model" to config.modelSwVer,
            "hasVgc" to "1",
            "vgcNewPassiveVer" to "",
            "ch" to "N",
            "gn" to "0",
            "newActiveVer" to "",
            "version" to versionLong,
            "st2" to "0",
            "cu" to "N",
            "srm2" to "0",
            "srm1" to "0",
            "cy" to "CN-ZH",
            "sn2" to "null",
            "ne" to "null",
            "sn1" to "null",
            "public_model" to config.deviceModel,
            "newPassiveVer" to "",
            "hwVer" to hwVer,
            "swVer" to fullSwVersion,
            "language" to "zh_CN",
            "isMan" to "1",
            "isFull" to if (config.isFull) "1" else "0",
            "protocalversion" to "1.0",
            "checkTrige" to "MANUL",
            "isstlifeover" to "false",
            "hwFingerprint" to "",
        )

        if (isPhone) {
            params += mapOf(
                "vgcCu" to "V000",
                "sf" to "1",
                "si" to "null",
                "dType" to "phone",
                "s_n" to "null",
                "elapsedtime" to elapsedTime.toString(),
                "st1" to (100_000 + Random.nextInt(0, 60_000)).toString(),
                "imei" to generateImei(),
                "ms" to "0",
                "mtype" to "no",
                "radiotype" to "L",
            )
        } else {
            params += mapOf(
                "romVersion" to "Funtouch ${config.androidVer}.0",
                "occurTime" to ts,
                "vgcCu" to "NULL",
                "battery" to "69",
                "sf" to "0",
                "si" to "",
                "oem" to "${config.modelSwVer}_CN-ZH_FULL_SC_NULL",
                "dType" to "tablet",
                "oemProjects" to "${config.modelSwVer}+${config.modelSwVer}B",
                "verName" to "1.1.1.1",
                "elapsedtime" to elapsedTime.toString(),
                "verCode" to "000000001",
                "st1" to "0",
                "snp" to config.snp,
                "imei" to "",
                "sdkVersion" to "34",
                "isCharge" to "false",
                "ms" to "-1",
                "mtype" to "FULL_SC",
                "radiotype" to "A",
            )
        }

        return params
    }

    fun joinParams(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${it.value}" }

    /**
     * 从（解密的）JSON 里按 `key":"` 模式抓字符串值。
     * 原 Python 实现就是这种"朴素解析"，这里保持一致以便行为完全对齐。
     */
    fun extractJsonStr(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        var end = json.indexOf('"', start)
        if (end < 0) {
            end = json.indexOf(',', start)
            if (end < 0) end = json.indexOf('}', start)
            if (end < 0) return null
            return json.substring(start, end).trim()
        }
        return json.substring(start, end)
    }

    fun parseUpdateResponse(response: String): UpdateInfo {
        val version = extractJsonStr(response, "version\":\"")
        val filename = extractJsonStr(response, "pkName\":\"")
        val size = extractJsonStr(response, "pkLen\":\"")
        val changelog = extractJsonStr(response, "h5Url\":\"")
        val sizeBytes = size?.toLongOrNull()

        return UpdateInfo(
            version = version,
            filename = filename?.let(::normalizeJsonUrl),
            sizeBytes = sizeBytes,
            sizeMb = sizeBytes?.let { it / 1048576 },
            downloadUrl = filename?.let { VivoProtocol.DOWNLOAD_PREFIX + normalizeJsonUrl(it) },
            changelogUrl = changelog?.let(::normalizeJsonUrl),
            rawResponse = response,
        )
    }

    /**
     * 服务端的 JSON 里 URL 是转义过的（`https:\/\/…`），
     * 对应原 Python 版的 `normalize_json_url()`。
     */
    fun normalizeJsonUrl(url: String): String = url.replace("\\/", "/")

    /**
     * 查询更新。
     *
     * @param onRaw 打开 verbose 时回调原始请求参数与原始响应，供界面展示
     */
    fun query(config: DeviceConfig, onRaw: (String) -> Unit = {}): QueryResult {
        val params = buildRequestParams(config)
        val rawParams = joinParams(params)

        if (config.verbose) {
            onRaw("[请求] POST ${VivoProtocol.BASE_URL}${VivoProtocol.UPDATE_ENDPOINT}")
            onRaw("[请求体] jvq_param=<AES-128-CBC + 16B 协议头，URL-safe Base64>")
            onRaw("[明文参数] $rawParams")
        }

        val response = try {
            OtaHttpClient.sendEncryptedRequest(rawParams)
        } catch (e: OtaNetworkException) {
            return QueryResult.Failure("网络请求失败：${e.message}")
        } catch (e: Exception) {
            return QueryResult.Failure("查询异常：${e.message ?: e.javaClass.simpleName}")
        }

        if (config.verbose) {
            onRaw("[明文响应] $response")
        }

        if (response.startsWith("[Error]")) {
            return QueryResult.Failure(response.removePrefix("[Error]").trim())
        }
        // 服务端业务拦截：{"message":"No update","retcode":210}
        if (response.contains("\"retcode\":210") || response.contains("\"retcode\": 210") ||
            response.contains("No update")
        ) {
            return QueryResult.NoUpdate(response)
        }

        val info = parseUpdateResponse(response)
        if (info.filename == null && info.version == null) {
            return QueryResult.Failure("响应中未找到固件信息：${response.take(400)}")
        }
        return QueryResult.Success(info)
    }
}
