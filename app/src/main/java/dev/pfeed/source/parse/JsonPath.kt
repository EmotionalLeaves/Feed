package dev.pfeed.source.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tiny dotted-path selector over a kotlinx.serialization [JsonElement]. Numeric segments index into
 * arrays, named segments index into objects. Used by the Generic JSON adapter so the user can map any
 * API's shape with paths like "data.children.0.data" without a full JSONPath dependency.
 */
object JsonPath {

    fun select(root: JsonElement?, path: String): JsonElement? {
        if (path.isBlank()) return root
        var cur = root
        for (seg in path.split('.')) {
            if (seg.isEmpty()) continue
            cur = when (val c = cur) {
                is JsonObject -> c[seg]
                is JsonArray -> seg.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return cur
    }

    fun string(root: JsonElement?, path: String): String? =
        (select(root, path) as? JsonPrimitive)?.contentOrNull

    fun array(root: JsonElement?, path: String): List<JsonElement> =
        (select(root, path) as? JsonArray)?.toList() ?: emptyList()
}
