package hunter.core

import com.google.gson.*

val json: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

fun JsonObject.text(key: String, default: String = ""): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default

fun JsonObject.obj(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

fun JsonObject.array(key: String): JsonArray =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

fun JsonObject.flag(key: String, default: Boolean = false) =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toBooleanStrictOrNull() ?: default

fun parseObject(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
