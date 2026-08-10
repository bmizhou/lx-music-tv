package com.lxmusic.tv.util

import kotlinx.serialization.json.*

/**
 * 宽松 JSON 解析统一入口。
 *
 * 背景：Android 13(API33)+ 系统自带的 [org.json] 为严格模式，遇重复 key 直接抛
 * JSONException（如网易云 /api/toplist 响应中 updateFrequency 字段重复出现）导致解析崩溃。
 * 更隐蔽的是：开启 R8 shrink（isMinifyEnabled=true）后，被平台同名遮蔽的宽松版
 * org.json:json 会被剔除，release 包只剩框架自带严格版，于是重复 key 必然崩。
 *
 * 这里统一改用 kotlinx.serialization（isLenient 对重复 key 取最后一个值、不抛异常），
 * 且它不是平台遮蔽类，R8 不会剔除，minify 开/关都稳定可用。
 *
 * 本文件提供与 org.json 同名的方法扩展（optString/optInt/optLong/optJSONObject/
 * optJSONArray/has/length/getJSONObject/opt 等），便于各网络 API 文件在只替换
 * 解析入口（JSONObject(x) -> parseToObj(x)）与兜底字面量（?: JSONObject() -> ?: JsonObject(emptyMap())）
 * 的前提下完成迁移，解析逻辑几乎零改动。注意：仅“解析响应”走这里；构造请求体（put/toString）
 * 仍用 org.json，无重复 key 风险。
 */

/** 宽松解析器：重复 key 取最后一个值，不抛异常。 */
internal val lenientJson: Json = Json { isLenient = true }

/** 将响应体解析为 kotlinx JsonObject；解析失败（含非对象结构）时返回空对象，不抛异常。 */
internal fun parseToObj(body: String): JsonObject {
    return try {
        val el = lenientJson.parseToJsonElement(body)
        el as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }
}

/** 从 kotlinx JsonObject 中安全取字符串（语义对齐 org.json 的 optString）：
 *  值为任意 JsonPrimitive（字符串/数字/布尔）时返回其原始内容文本（数字如 123、布尔如 true）；
 *  缺失、JSON null、非基础类型（对象/数组）时返回 def（默认 ""）。
 *  注意：绝不能加 isString 守卫——org.json 会把数字也转成字符串读出，加了会导致所有数字字段读成默认值。 */
internal fun JsonObject.optString(key: String, def: String = ""): String {
    val v = this[key] ?: return def
    return when (v) {
        is JsonNull -> def
        is JsonPrimitive -> v.content
        else -> def
    }
}

/** 取可选字符串（缺失/JSON null/非基础类型时返回 null），用于原先以 optString(key, null) 表达“可空”的场景。
 *  任意 JsonPrimitive（含数字/布尔）都返回其原始内容文本。 */
internal fun JsonObject.optStr(key: String): String? {
    val v = this[key] ?: return null
    return when (v) {
        is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> null
    }
}

/** 从 kotlinx JsonObject 中安全取可选 Long（缺失/非数字时返回 def）。 */
internal fun JsonObject.optLong(key: String, def: Long = 0L): Long {
    return optStr(key)?.toLongOrNull() ?: def
}

/** 从 kotlinx JsonObject 中安全取可选 Int（缺失/非数字时返回 def）。 */
internal fun JsonObject.optInt(key: String, def: Int = 0): Int {
    return optStr(key)?.toIntOrNull() ?: def
}

/** 从 kotlinx JsonObject 中安全取可选 Boolean（缺失/非布尔时返回 def）。 */
internal fun JsonObject.optBoolean(key: String, def: Boolean = false): Boolean {
    val v = this[key] ?: return def
    return when (v) {
        is JsonNull -> def
        is JsonPrimitive -> v.content?.toBooleanStrictOrNull() ?: def
        else -> def
    }
}

/** 取嵌套 JsonObject（缺失、JSON null 或非对象时返回 null）。 */
internal fun JsonObject.optJSONObject(key: String): JsonObject? {
    val v = this[key] ?: return null
    return when (v) {
        is JsonNull -> null
        is JsonObject -> v
        else -> null
    }
}

/** 取嵌套 JsonArray（缺失、JSON null 或非数组时返回 null）。 */
internal fun JsonObject.optJSONArray(key: String): JsonArray? {
    val v = this[key] ?: return null
    return when (v) {
        is JsonNull -> null
        is JsonArray -> v
        else -> null
    }
}

/** 是否包含指定 key（即使值为 JSON null 也算包含，与 org.json 行为一致）。 */
internal fun JsonObject.has(key: String): Boolean = containsKey(key)

/**
 * 取原始值（模仿 org.json 的 opt）：字符串/数字/布尔返回其内容字符串，嵌套对象/数组返回对应实例。
 * 主要用于个别字段可能是“字符串或数组”双形态的场景（如酷狗播放地址 url）。
 */
internal fun JsonObject.opt(key: String): Any? {
    val v = this[key] ?: return null
    return when (v) {
        is JsonNull -> null
        is JsonPrimitive -> v.content
        is JsonObject -> v
        is JsonArray -> v
    }
}

/** kotlinx JsonArray 的长度（对应 org.json JSONArray.length()）。 */
internal fun JsonArray.length(): Int = size

/** 按下标取 JsonObject（越界或非对象时返回空对象，不抛异常）。 */
internal fun JsonArray.getJSONObject(index: Int): JsonObject {
    val v = getOrNull(index)
    return (v as? JsonObject) ?: JsonObject(emptyMap())
}

/** 按下标安全取字符串（越界、JSON null 或非字符串时返回空串）。 */
internal fun JsonArray.optString(index: Int): String {
    val v = getOrNull(index)
    return when (v) {
        is JsonNull -> ""
        is JsonPrimitive -> if (v.isString) v.content else ""
        else -> ""
    }
}

/** 按下标取 JsonObject（越界或非对象时返回 null）。 */
internal fun JsonArray.optJSONObject(index: Int): JsonObject? {
    return getOrNull(index) as? JsonObject
}
