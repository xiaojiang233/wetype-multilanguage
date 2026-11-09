package top.xiaojiang233.wetypemulti.common

import com.google.gson.JsonParser
import top.xiaojiang233.wetypemulti.common.layout.JaLayoutRewriter
import top.xiaojiang233.wetypemulti.common.layout.KeyPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JaLayoutRewriterTest {
    /** A cut-down layout with the same shape as WeType's: `config`, `keyDefaultConfig`, and `keys` as rows of keys. */
    private val layout = """
        {"config":{"name":[1,1,1]},"keyDefaultConfig":{"mainTextSize":[64,58,64]},"keys":[
          [{"id":["S2_key_k","S2_key_k","S2_key_k"],"mainText":["K","K","K"],"floatText":["“","“","“"]}],
          [{"id":["dot","dot","dot"],"mainText":["，","，","，"],"floatText":["。","。","。"],"type":[5,5,5]}]
        ]}
    """.trimIndent()

    @Test
    fun replacesEveryVariantOfTheNamedPropertyOnly() {
        val r = JaLayoutRewriter.rewrite(
            layout,
            listOf(KeyPatch("S2_key_k", "floatText", "「"), KeyPatch("dot", "mainText", "、")),
        )
        val rows = JsonParser.parseString(r.json).asJsonObject.getAsJsonArray("keys")
        val k = rows[0].asJsonArray[0].asJsonObject
        assertEquals(listOf("「", "「", "「"), k.getAsJsonArray("floatText").map { it.asString })
        assertEquals(listOf("K", "K", "K"), k.getAsJsonArray("mainText").map { it.asString })
        val dot = rows[1].asJsonArray[0].asJsonObject
        assertEquals(listOf("、", "、", "、"), dot.getAsJsonArray("mainText").map { it.asString })
        assertEquals(listOf("。", "。", "。"), dot.getAsJsonArray("floatText").map { it.asString })
        assertEquals(setOf("S2_key_k.floatText", "dot.mainText"), r.applied.toSet())
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun numericPatchWritesJsonNumbers() {
        val r = JaLayoutRewriter.rewrite(layout, listOf(KeyPatch("dot", "type", "1", number = true)))
        val dot = JsonParser.parseString(r.json).asJsonObject.getAsJsonArray("keys")[1].asJsonArray[0].asJsonObject
        assertEquals(listOf(1, 1, 1), dot.getAsJsonArray("type").map { it.asInt })
    }

    @Test
    fun booleanPatchWritesJsonBooleans() {
        val hidden = """{"keys":[[{"id":["emoji","emoji","emoji"],"isVisible":[false,false,false]}]]}"""
        val r = JaLayoutRewriter.rewrite(hidden, listOf(KeyPatch("emoji", "isVisible", "true", bool = true)))
        val emoji = JsonParser.parseString(r.json).asJsonObject.getAsJsonArray("keys")[0].asJsonArray[0].asJsonObject
        assertEquals(listOf(true, true, true), emoji.getAsJsonArray("isVisible").map { it.asBoolean })
        assertTrue(r.json.contains("[true,true,true]"), r.json)
    }

    @Test
    fun theJapaneseRulesDropTheSwipeOnAAndRewritePunctuation() {
        val patches = top.xiaojiang233.wetypemulti.common.layout.JaLayoutRules.PATCHES
        assertEquals("", patches.single { it.keyId == "S2_key_a" && it.property == "floatText" }.value)
        assertEquals("「", patches.single { it.keyId == "S2_key_k" && it.property == "floatText" }.value)
        assertEquals("」", patches.single { it.keyId == "S2_key_l" && it.property == "floatText" }.value)
        assertEquals("〜", patches.single { it.keyId == "S2_key_j" && it.property == "floatText" }.value)
        assertEquals("、", patches.single { it.keyId == "dot" && it.property == "mainText" }.value)
    }

    @Test
    fun keepsTheRestOfTheLayoutAndReportsMissingKeys() {
        val r = JaLayoutRewriter.rewrite(layout, listOf(KeyPatch("nope", "mainText", "x")))
        assertEquals(listOf("nope.mainText"), r.missing)
        assertTrue(r.applied.isEmpty())
        val root = JsonParser.parseString(r.json).asJsonObject
        assertEquals(1, root.getAsJsonObject("config").getAsJsonArray("name")[0].asInt)
        assertEquals(64, root.getAsJsonObject("keyDefaultConfig").getAsJsonArray("mainTextSize")[0].asInt)
    }

    @Test
    fun outputIsNotHtmlEscaped() {
        val r = JaLayoutRewriter.rewrite(layout, listOf(KeyPatch("dot", "mainText", "、「」〜")))
        assertTrue(r.json.contains("、「」〜"), r.json)
    }
}
