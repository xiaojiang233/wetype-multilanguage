package top.xiaojiang233.wetypemulti.common.layout

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Replace [property] of the layout key whose `id` is [keyId], in every variant of that property. [value] is written as a JSON
 * string, or as a number with [number], or as a boolean with [bool].
 */
data class KeyPatch(val keyId: String, val property: String, val value: String, val number: Boolean = false, val bool: Boolean = false)

/** [json] is the rewritten layout; [applied] and [missing] name the patches (`keyId.property`) that did or did not match. */
data class LayoutRewrite(val json: String, val applied: List<String>, val missing: List<String>)

/**
 * Applies [KeyPatch]es to a WeType keyboard layout (`keyboard/output/<name>.json`). WeType stores every key
 * property as an array with one element per layout variant, for example `"floatText": ["“", "“", "“"]`.
 * A patch replaces every element and keeps the array length, so all variants change together.
 */
object JaLayoutRewriter {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun rewrite(json: String, patches: List<KeyPatch>): LayoutRewrite {
        val root = JsonParser.parseString(json).asJsonObject
        val byId = patches.groupBy { it.keyId }
        val applied = linkedSetOf<String>()
        (root.get("keys") as? JsonArray)?.forEach { row ->
            (row as? JsonArray)?.forEach { element ->
                val key = element as? JsonObject ?: return@forEach
                val id = (key.get("id") as? JsonArray)?.firstOrNull()?.asString ?: return@forEach
                byId[id]?.forEach { patch ->
                    key.add(patch.property, variants(key, key.get(patch.property), patch.element()))
                    applied += "${patch.keyId}.${patch.property}"
                }
            }
        }
        val missing = patches.map { "${it.keyId}.${it.property}" }.filterNot { it in applied }
        return LayoutRewrite(gson.toJson(root), applied.toList(), missing)
    }

    /** Same length as the existing property (or as its `id` array when the property is absent). */
    private fun variants(key: JsonObject, existing: JsonElement?, value: JsonElement): JsonArray {
        val n = (existing as? JsonArray)?.size() ?: (key.get("id") as? JsonArray)?.size() ?: 1
        return JsonArray().also { array -> repeat(n.coerceAtLeast(1)) { array.add(value) } }
    }

    private fun KeyPatch.element(): JsonElement = when {
        number -> JsonPrimitive(value.toInt())
        bool -> JsonPrimitive(value.toBooleanStrict())
        else -> JsonPrimitive(value)
    }
}

/** The Japanese changes applied to WeType's Chinese QWERTY layout while Japanese mode is on. */
object JaLayoutRules {
    /** Layouts Japanese runs on: WeType's Chinese QWERTY, portrait and landscape. */
    val TARGET_PATHS = setOf(
        "keyboard/output/S2ChineseQwertyKeyboard.json",
        "keyboard/output/S2ChineseQwertyKeyboardLand.json",
    )

    /**
     * The key the long vowel key is drawn over: WeType's shift key (which acts as '词 on Chinese QWERTY).
     * Japanese mode replaces it with the long vowel mark ー.
     */
    const val LONG_VOWEL_SLOT = "shift"

    /**
     * Swipe and hint labels use Japanese punctuation; the dot key types 、 instead of ，. Long presses are rewritten at commit
     * time (JaPunctuation).
     *
     * The long vowel mark ー has a key of its own, as on Gboard, so the A key no longer carries it as a swipe-up: a thumb that slips
     * upward on the most used vowel must not type it. The key is drawn by the module over [LONG_VOWEL_SLOT].
     */
    val PATCHES = listOf(
        KeyPatch("S2_key_k", "floatText", "「"),
        KeyPatch("S2_key_l", "floatText", "」"),
        KeyPatch("S2_key_j", "floatText", "〜"),
        KeyPatch("S2_key_a", "floatText", ""),
        KeyPatch("dot", "mainText", "、"),
    )
}
