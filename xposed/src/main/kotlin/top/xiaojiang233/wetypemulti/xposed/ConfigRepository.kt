package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.config.MultiLanguageConfig
import java.io.File

/**
 * Thread-safe in-memory cache for [MultiLanguageConfig].
 * Eliminates synchronous disk I/O during key events and keyboard cycling.
 */
object ConfigRepository {
    @Volatile
    private var cachedConfig: MultiLanguageConfig = MultiLanguageConfig()

    @Volatile
    private var lastLoadedTime = 0L

    @Volatile
    private var configDir: File? = null

    fun init(dir: File) {
        configDir = dir
        loadFromDisk(dir)
    }

    fun get(): MultiLanguageConfig {
        checkReload()
        return cachedConfig
    }

    fun update(config: MultiLanguageConfig) {
        cachedConfig = config
    }

    private fun checkReload() {
        val dir = configDir ?: return
        val file = File(dir, "config.json")
        if (file.exists() && file.lastModified() > lastLoadedTime) {
            loadFromDisk(dir)
        }
    }

    fun loadFromDisk(dir: File) {
        val file = File(dir, "config.json")
        if (!file.exists()) {
            // Also try fallback directory if WeType data dir
            val fallback = File("/data/data/com.tencent.wetype/files/config.json")
            if (fallback.exists()) {
                runCatching {
                    cachedConfig = MultiLanguageConfig.fromJson(fallback.readText())
                    lastLoadedTime = fallback.lastModified()
                }
            }
            return
        }
        runCatching {
            cachedConfig = MultiLanguageConfig.fromJson(file.readText())
            lastLoadedTime = file.lastModified()
        }
    }
}
