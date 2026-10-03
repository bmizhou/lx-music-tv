package com.lxmusic.tv.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class QQMusicLyricResponseTest {
    private val lyric = "[ti:Hello]\n[00:06.34]Hello it's me\n[00:11.86]I was wondering"
    private val translation = "[00:06.34]你好吗 是我\n[00:11.86]我犹豫着要不要给你来电"

    private fun response(
        original: String = Base64.Default.encode(lyric.toByteArray()),
        translated: JsonElement = JsonPrimitive(Base64.Default.encode(translation.toByteArray())),
        code: Int = 0,
        reqCode: Int = 0,
        crypt: Int = 0,
        qrc: Int = 0
    ): String = buildJsonObject {
        put("code", code)
        putJsonObject("req") {
            put("code", reqCode)
            putJsonObject("data") {
                put("crypt", crypt)
                put("qrc", qrc)
                put("lyric", original)
                put("trans", translated)
            }
        }
    }.toString()

    @Test
    fun `decodes original and Chinese translation preserving timestamps`() {
        val parsed = Json.parseToJsonElement(QQMusicLyricResponse.parse(response())!!).jsonObject
        assertEquals(lyric, parsed["lyric"]!!.jsonPrimitive.content)
        assertEquals(translation, parsed["tlyric"]!!.jsonPrimitive.content)
    }

    @Test
    fun `songs without translation retain original lyrics`() {
        for (translated in listOf(JsonPrimitive(""), JsonNull, JsonPrimitive("%%%"))) {
            val parsed = Json.parseToJsonElement(QQMusicLyricResponse.parse(response(translated = translated))!!).jsonObject
            assertEquals(lyric, parsed["lyric"]!!.jsonPrimitive.content)
            assertFalse(parsed.containsKey("tlyric"))
        }
    }

    @Test
    fun `rejects failed empty malformed and encrypted responses for fallback`() {
        for (body in listOf(
            response(code = 1), response(reqCode = 500003), response(original = ""),
            response(original = "%%%"), response(crypt = 1), response(qrc = 1),
            "{}", "{\"code\":0,\"req\":null}", "not json"
        )) assertNull(body, QQMusicLyricResponse.parse(body))
    }
}
