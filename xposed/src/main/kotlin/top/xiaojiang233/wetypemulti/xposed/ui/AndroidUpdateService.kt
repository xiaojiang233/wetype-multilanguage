package top.xiaojiang233.wetypemulti.xposed.ui

import top.xiaojiang233.wetypemulti.common.update.AppRelease
import top.xiaojiang233.wetypemulti.common.update.GitHubReleaseParser
import top.xiaojiang233.wetypemulti.common.update.UpdateService
import java.net.HttpURLConnection
import java.net.URL

class AndroidUpdateService : UpdateService {
    override fun checkUpdate(currentVersion: String): AppRelease? {
        val url = URL("https://api.github.com/repos/xiaojiang233/wetype-multilanguage/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "WeType-MultiLanguage-Android")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }

        return runCatching {
            conn.connect()
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val release = GitHubReleaseParser.parse(json) ?: return null
                val cleanTag = release.tagName.removePrefix("v").trim()
                val cleanCurrent = currentVersion.removePrefix("v").trim()
                if (cleanTag != cleanCurrent) {
                    release
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }
}
