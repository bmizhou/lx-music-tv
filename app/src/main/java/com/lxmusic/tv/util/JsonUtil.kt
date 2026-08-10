package com.lxmusic.tv.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON工具类
 * 用于JSON序列化和反序列化
 */
object JsonUtil {

    /**
     * 将对象转换为JSON字符串
     */
    fun toJson(obj: Any?): String {
        return when (obj) {
            null -> "null"
            is String -> "\"${escapeJson(obj)}\""
            is Number, is Boolean -> obj.toString()
            is Map<*, *> -> mapToJson(obj).toString()
            is List<*> -> listToJson(obj).toString()
            is Array<*> -> listToJson(obj.toList()).toString()
            else -> {
                // 尝试使用反射转换为Map
                try {
                    val map = mutableMapOf<String, Any?>()
                    obj::class.java.declaredFields.forEach { field ->
                        field.isAccessible = true
                        map[field.name] = field.get(obj)
                    }
                    mapToJson(map).toString()
                } catch (e: Exception) {
                    "\"${escapeJson(obj.toString())}\""
                }
            }
        }
    }

    /**
     * 将JSON字符串转换为指定类型
     */
    inline fun <reified T> fromJson(json: String): T? {
        return try {
            val jsonObj = JSONObject(json)
            fromJsonObj(jsonObj, T::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将Map转换为JSONObject
     */
    fun mapToJson(map: Map<*, *>): JSONObject {
        val jsonObj = JSONObject()
        for ((key, value) in map) {
            val jsonKey = key?.toString() ?: continue
            when (value) {
                null -> jsonObj.put(jsonKey, JSONObject.NULL)
                is Map<*, *> -> jsonObj.put(jsonKey, mapToJson(value))
                is List<*> -> jsonObj.put(jsonKey, listToJson(value))
                is Array<*> -> jsonObj.put(jsonKey, listToJson(value.toList()))
                is String -> jsonObj.put(jsonKey, value)
                is Number -> jsonObj.put(jsonKey, value)
                is Boolean -> jsonObj.put(jsonKey, value)
                else -> jsonObj.put(jsonKey, toJson(value))
            }
        }
        return jsonObj
    }

    /**
     * 将List转换为JSONArray
     */
    fun listToJson(list: List<*>): JSONArray {
        val jsonArr = JSONArray()
        for (item in list) {
            when (item) {
                null -> jsonArr.put(JSONObject.NULL)
                is Map<*, *> -> jsonArr.put(mapToJson(item))
                is List<*> -> jsonArr.put(listToJson(item))
                is Array<*> -> jsonArr.put(listToJson(item.toList()))
                is String -> jsonArr.put(item)
                is Number -> jsonArr.put(item)
                is Boolean -> jsonArr.put(item)
                else -> jsonArr.put(toJson(item))
            }
        }
        return jsonArr
    }

    /**
     * 将JSONObject转换为Map
     */
    fun jsonToMap(jsonObj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObj.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    /**
     * 将JSONArray转换为List
     */
    fun jsonArrayToList(jsonArr: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until jsonArr.length()) {
            val value = jsonArr.get(i)
            list.add(when (value) {
                is JSONObject -> jsonToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            })
        }
        return list
    }

    /**
     * 转义JSON字符串
     */
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
    }

    /**
     * 从JSON对象转换为指定类型（简化版）
     */
    fun <T> fromJsonObj(jsonObj: JSONObject, clazz: Class<T>): T? {
        // 这里可以实现更复杂的类型转换
        // 目前简化为返回Map
        @Suppress("UNCHECKED_CAST")
        return jsonToMap(jsonObj) as? T
    }

    /**
     * 验证JSON格式是否正确
     */
    fun isValidJson(json: String): Boolean {
        return try {
            JSONObject(json)
            true
        } catch (e: Exception) {
            try {
                JSONArray(json)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 格式化JSON字符串
     */
    fun formatJson(json: String, indent: Int = 2): String {
        return try {
            val jsonObj = JSONObject(json)
            jsonObj.toString(indent)
        } catch (e: Exception) {
            json
        }
    }

    /**
     * 合并两个JSON对象
     */
    fun mergeJson(base: JSONObject, override: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        val keys = override.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, override.get(key))
        }
        return result
    }

    /**
     * 从JSON字符串中提取值
     */
    fun getValue(json: String, path: String): Any? {
        val parts = path.split(".")
        var current: Any = JSONObject(json)

        for (part in parts) {
            current = when (current) {
                is JSONObject -> {
                    if (!current.has(part)) return null
                    current.get(part)
                }
                is JSONArray -> {
                    val index = part.toIntOrNull() ?: return null
                    if (index < 0 || index >= current.length()) return null
                    current.get(index)
                }
                else -> return null
            }
        }

        return if (current == JSONObject.NULL) null else current
    }
}