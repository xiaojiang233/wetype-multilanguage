package top.xiaojiang233.wetypemulti.common

import top.xiaojiang233.wetypemulti.common.config.MultiLanguageConfig
import top.xiaojiang233.wetypemulti.common.update.GitHubReleaseParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigAndUpdateTest {
    @Test
    fun configSerializationRoundtrip() {
        val original = MultiLanguageConfig(
            longVowelKeyEnabled = false,
            smartPredictionEnabled = true,
            learningEnabled = false,
            candidatePageSize = 25,
            enabledKeyboardIds = listOf("zh_CN", "ja_JP"),
        )
        val json = original.toJson()
        val restored = MultiLanguageConfig.fromJson(json)
        assertEquals(original, restored)
        assertEquals(listOf("zh_CN", "ja_JP"), restored.enabledKeyboardIds)
    }

    @Test
    fun parseGitHubReleaseJson() {
        val sampleJson = """
            {
              "tag_name": "v0.2.0",
              "name": "WeType MultiLanguage v0.2.0",
              "body": "### Changes\n- Improved candidate display\n- Added Miuix UI settings app",
              "html_url": "https://github.com/xiaojiang233/wetype-multilanguage/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "xposed-debug.apk",
                  "browser_download_url": "https://github.com/xiaojiang233/wetype-multilanguage/releases/download/v0.2.0/xposed-debug.apk"
                }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseParser.parse(sampleJson)
        assertNotNull(release)
        assertEquals("v0.2.0", release.tagName)
        assertEquals("WeType MultiLanguage v0.2.0", release.name)
        assertTrue(release.body.contains("Miuix UI"))
        assertEquals("https://github.com/xiaojiang233/wetype-multilanguage/releases/download/v0.2.0/xposed-debug.apk", release.downloadUrl)
    }
}
