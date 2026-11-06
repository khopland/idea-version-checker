package io.github.khopland.versionchecker.vuln

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Lenient reads of vulnerability database answers: a missing or differently typed field is null or empty. */
internal fun JsonObject.string(name: String): String? = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString
internal fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
internal fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()
internal fun List<JsonElement>?.objects(): List<JsonObject> = orEmpty().mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
