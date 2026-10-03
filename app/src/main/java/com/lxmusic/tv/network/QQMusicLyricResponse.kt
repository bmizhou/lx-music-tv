package com.lxmusic.tv.network

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** QQ 的非加密普通歌词响应转换为洛雪歌词协议。 */
internal object QQMusicLyricResponse {
    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(value: JsonElement?): String? {
        val encoded = (value as? JsonPrimitive)?.contentOrNull ?: return null
        if (encoded.isBlank()) return null
        return runCatching {
            Base64.Default.decode(encoded).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun parse(body: String): String? = runCatching {
        val response = Json.parseToJsonElement(body).jsonObject
        if (response["code"]?.jsonPrimitive?.intOrNull != 0) return null
        val req = response["req"]?.jsonObject ?: return null
        if (req["code"]?.jsonPrimitive?.intOrNull != 0) return null
        val data = req["data"]?.jsonObject ?: return null
        // 只接受 crypt=0、qrc=0，不能把加密 QRC 当作普通 LRC。
        if (data["crypt"]?.jsonPrimitive?.intOrNull != 0 ||
            data["qrc"]?.jsonPrimitive?.intOrNull != 0) return null
        val lyric = decode(data["lyric"]) ?: return null
        val translation = decode(data["trans"])
        buildJsonObject {
            put("lyric", lyric)
            if (translation != null) put("tlyric", translation)
        }.toString()
    }.getOrNull()
}
