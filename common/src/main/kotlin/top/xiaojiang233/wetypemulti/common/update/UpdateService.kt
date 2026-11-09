package top.xiaojiang233.wetypemulti.common.update

data class AppRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val downloadUrl: String?,
    val htmlUrl: String,
)

interface UpdateService {
    /**
     * Checks if a new release is available compared to [currentVersion].
     * Returns [AppRelease] if a newer version exists, or null otherwise.
     */
    fun checkUpdate(currentVersion: String): AppRelease?
}

object GitHubReleaseParser {
    /**
     * Minimal JSON parser for GitHub release response without external JSON dependencies.
     */
    fun parse(json: String): AppRelease? {
        val tag = extractString(json, "tag_name") ?: return null
        val name = extractString(json, "name") ?: tag
        val body = extractString(json, "body") ?: ""
        val htmlUrl = extractString(json, "html_url") ?: "https://github.com/xiaojiang233/wetype-multilanguage/releases"

        // Search for first .apk download asset if present
        val apkUrl = Regex("\"browser_download_url\":\\s*\"([^\"]+\\.apk)\"")
            .find(json)?.groupValues?.get(1)

        return AppRelease(
            tagName = tag,
            name = name,
            body = body,
            downloadUrl = apkUrl,
            htmlUrl = htmlUrl,
        )
    }

    private fun extractString(json: String, key: String): String? {
        val match = Regex("\"$key\":\\s*\"(.*?)(?<!\\\\)\"").find(json)
        return match?.groupValues?.get(1)
            ?.replace("\\r\\n", "\n")
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }
}
