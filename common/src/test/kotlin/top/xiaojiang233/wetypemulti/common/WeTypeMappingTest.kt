package top.xiaojiang233.wetypemulti.common

import top.xiaojiang233.wetypemulti.common.mapping.ClassInfo
import top.xiaojiang233.wetypemulti.common.mapping.ClassSource
import top.xiaojiang233.wetypemulti.common.mapping.FieldInfo
import top.xiaojiang233.wetypemulti.common.mapping.MappingResolver
import top.xiaojiang233.wetypemulti.common.mapping.MemberSpec
import top.xiaojiang233.wetypemulti.common.mapping.MethodInfo
import top.xiaojiang233.wetypemulti.common.mapping.Resolved
import top.xiaojiang233.wetypemulti.common.mapping.RoleSpec
import top.xiaojiang233.wetypemulti.common.mapping.WeTypeMapping
import top.xiaojiang233.wetypemulti.common.mapping.summarize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WeTypeMappingTest {
    private val bundle = "android.os.Bundle"
    private val keyboardData = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.bean.KeyboardData"

    private fun source(vararg classes: ClassInfo) = MappingResolver(ClassSource { n -> classes.firstOrNull { it.name == n } })

    private fun m(name: String, vararg params: String, returns: String = "void", isStatic: Boolean = false) =
        MethodInfo(name, params.toList(), returns, isStatic)

    private val modelN = ClassInfo("com.tencent.wetype.plugin.hld.model.N", methods = listOf(
        m("l3", "int", bundle),
        m("u0", returns = "int"),
    ))

    private val view = ClassInfo("com.tencent.wetype.plugin.hld.keyboard.selfdraw.o", methods = listOf(
        m("q9", returns = keyboardData),
        m("s2", "xc.l", "xc.a"),
    ))

    @Test
    fun classCandidatesFallThroughToTheFirstLoadedClass() {
        val role = RoleSpec(classes = listOf("x.Gone", "com.tencent.wetype.plugin.hld.model.N"))
        val r = source(modelN).resolveRole("layout.model", role)
        assertEquals(Resolved.Found("layout.model", "com.tencent.wetype.plugin.hld.model.N", null, "class"), r)
    }

    @Test
    fun memberMatchesByNameAndParameterTypes() {
        val role = RoleSpec(classes = listOf(modelN.name), members = listOf(MemberSpec(names = listOf("l3"), params = listOf("int", bundle))))
        val r = assertIs<Resolved.Found>(source(modelN).resolveRole("layout.switch", role))
        assertEquals("l3", r.member)
    }

    @Test
    fun twoArgAndThreeArgVariantsBothResolveToWhateverExists() {
        val threeArg = ClassInfo(modelN.name, methods = listOf(m("l3", "int", bundle, "boolean")))
        val alternatives = listOf(
            MemberSpec(names = listOf("l3"), params = listOf("int", bundle, "boolean")),
            MemberSpec(names = listOf("l3"), params = listOf("int", bundle)),
        )
        val role = RoleSpec(classes = listOf(modelN.name), members = alternatives)
        assertIs<Resolved.Found>(source(modelN).resolveRole("layout.switch", role))
        assertIs<Resolved.Found>(source(threeArg).resolveRole("layout.switch", role))
    }

    @Test
    fun signatureOnlyMatchWorksAfterTheMethodWasRenamed() {
        val members = listOf(
            MemberSpec(names = listOf("x1"), params = emptyList(), returns = keyboardData),
            MemberSpec(params = emptyList(), returns = keyboardData),
        )
        val r = source(view).resolveRole("layout.view", RoleSpec(classes = listOf(view.name), members = members))
        assertEquals("q9", assertIs<Resolved.Found>(r).member)
    }

    @Test
    fun ambiguousSignatureIsReportedInsteadOfGuessed() {
        val twice = ClassInfo(view.name, methods = listOf(m("q9", returns = keyboardData), m("t9", returns = keyboardData)))
        val members = listOf(MemberSpec(params = emptyList(), returns = keyboardData))
        val r = source(twice).resolveRole("layout.view", RoleSpec(classes = listOf(view.name), members = members))
        val missing = assertIs<Resolved.Missing>(r)
        assertTrue(missing.reason.contains("2 matches"), missing.reason)
    }

    @Test
    fun allowMultipleResolvesEveryMatch() {
        val lambda = ClassInfo("com.tencent.wetype.plugin.hld.utils.v0\$f", methods = listOf(
            m("invoke", returns = "java.lang.Object"),
            m("invoke", returns = "void"),
        ))
        val role = RoleSpec(classes = listOf(lambda.name), members = listOf(MemberSpec(names = listOf("invoke"), params = emptyList(), allowMultiple = true)))
        assertEquals("invoke,invoke", assertIs<Resolved.Found>(source(lambda).resolveRole("popup.item", role)).member)
    }

    @Test
    fun enumConstantNeedsAnEnumClassWithAStaticField() {
        val keyboardT = ClassInfo("com.tencent.wetype.plugin.hld.keyboard.t", fields = listOf(
            FieldInfo("d", "com.tencent.wetype.plugin.hld.keyboard.t", isStatic = true),
        ), isEnum = true)
        val role = RoleSpec(classes = listOf(keyboardT.name), enumConstant = "d")
        assertIs<Resolved.Found>(source(keyboardT).resolveRole("keyboard.chinese", role))

        val notEnum = keyboardT.copy(isEnum = false)
        assertIs<Resolved.Missing>(source(notEnum).resolveRole("keyboard.chinese", role))
    }

    @Test
    fun fieldMatchesByNameStaticnessAndType() {
        val s0 = ClassInfo("com.tencent.wetype.plugin.hld.model.s0", fields = listOf(
            FieldInfo("b", "java.util.Map", isStatic = true),
        ))
        val role = RoleSpec(classes = listOf(s0.name), members = listOf(MemberSpec(kind = "field", names = listOf("b"), returns = "java.util.Map", isStatic = true)))
        assertEquals("b", assertIs<Resolved.Found>(source(s0).resolveRole("layout.cache.origin", role)).member)

        val instance = s0.copy(fields = listOf(FieldInfo("b", "java.util.Map", isStatic = false)))
        assertIs<Resolved.Missing>(source(instance).resolveRole("layout.cache.origin", role))
    }

    @Test
    fun missingClassesSayWhyTheyFailed() {
        val r = source(modelN).resolveRole("layout.view", RoleSpec(classes = listOf("a.B", "c.D")))
        val missing = assertIs<Resolved.Missing>(r)
        assertTrue(missing.reason.contains("a.B not loaded") && missing.reason.contains("c.D not loaded"), missing.reason)
    }

    @Test
    fun mappingJsonParsesRolesAndBadJsonIsEmpty() {
        val json = """
            {"versionCode": 58201, "roles": {
              "layout.model": {"classes": ["com.tencent.wetype.plugin.hld.model.N"]},
              "layout.switch": {"classes": ["com.tencent.wetype.plugin.hld.model.N"],
                "members": [{"names": ["l3"], "params": ["int", "android.os.Bundle"]}]}
            }}
        """.trimIndent()
        val mapping = WeTypeMapping.parse(json)
        assertEquals(58201L, mapping.versionCode)
        assertEquals(setOf("layout.model", "layout.switch"), mapping.allRoles.keys)
        assertEquals(listOf("l3"), mapping.allRoles.getValue("layout.switch").alternatives.single().nameList)
        assertTrue(WeTypeMapping.parse("not json").allRoles.isEmpty())
    }

    @Test
    fun summaryCountsFoundAndNamesMissingRoles() {
        val results = source(modelN).resolve(WeTypeMapping(roles = mapOf(
            "layout.model" to RoleSpec(classes = listOf(modelN.name)),
            "layout.view" to RoleSpec(classes = listOf("a.B")),
        )))
        val text = summarize(results)
        assertTrue(text.startsWith("resolved 1/2"), text)
        assertTrue(text.contains("missing: layout.view"), text)
    }

    @Test
    fun argRolesComeBackWithTheMatchedMember() {
        val candidates = "com.tencent.wxhld.info.DisplayCandidates"
        val params = listOf("long", "java.lang.String", "[B", "java.lang.String", "[B", "[L$candidates;", candidates)
        val api = ClassInfo("com.tencent.wxhld.WxhldApi", methods = listOf(m("select_candidate", *params.toTypedArray())))
        val member = MemberSpec(names = listOf("select_candidate"), params = params, args = mapOf("candidate" to 1, "followUp" to 3))
        val r = assertIs<Resolved.Found>(source(api).resolveRole("wxhld.select", RoleSpec(classes = listOf(api.name), members = listOf(member))))
        assertEquals("select_candidate", r.member)
        assertEquals(mapOf("candidate" to 1, "followUp" to 3), r.args)
    }

    @Test
    fun argRolesParseFromJsonAndClassOnlyRolesHaveNone() {
        val json = """
            {"roles": {
              "wxhld.select": {"classes": ["com.tencent.wxhld.WxhldApi"],
                "members": [{"names": ["select_candidate"], "args": {"candidate": 1, "followUp": 3}}]},
              "layout.model": {"classes": ["com.tencent.wetype.plugin.hld.model.N"]}
            }}
        """.trimIndent()
        val roles = WeTypeMapping.parse(json).allRoles
        assertEquals(mapOf("candidate" to 1, "followUp" to 3), roles.getValue("wxhld.select").alternatives.single().args)
        val r = assertIs<Resolved.Found>(source(modelN).resolveRole("layout.model", roles.getValue("layout.model")))
        assertTrue(r.args.isEmpty())
    }
}
