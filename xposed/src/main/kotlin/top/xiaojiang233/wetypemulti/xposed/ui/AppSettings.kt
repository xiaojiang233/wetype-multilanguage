package top.xiaojiang233.wetypemulti.xposed.ui

import android.content.Context
import android.content.SharedPreferences
import top.xiaojiang233.wetypemulti.common.config.MultiLanguageConfig
import java.io.File

object AppSettings {
    private const val PREFS_NAME = "wetype_multi_prefs"
    private const val KEY_LONG_VOWEL = "long_vowel_enabled"
    private const val KEY_SMART_PRED = "smart_prediction_enabled"
    private const val KEY_LEARNING = "learning_enabled"
    private const val KEY_PAGE_SIZE = "candidate_page_size"
    private const val KEY_ENABLED_KEYBOARDS = "enabled_keyboards"

    fun load(context: Context): MultiLanguageConfig {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val kbString = sp.getString(KEY_ENABLED_KEYBOARDS, null)
        val kbList = if (!kbString.isNullOrBlank()) {
            kbString.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            listOf("zh_CN", "en_US", "ja_JP")
        }
        return MultiLanguageConfig(
            longVowelKeyEnabled = sp.getBoolean(KEY_LONG_VOWEL, true),
            smartPredictionEnabled = sp.getBoolean(KEY_SMART_PRED, true),
            learningEnabled = sp.getBoolean(KEY_LEARNING, true),
            candidatePageSize = sp.getInt(KEY_PAGE_SIZE, 50),
            enabledKeyboardIds = kbList,
        )
    }

    fun save(context: Context, config: MultiLanguageConfig) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_LONG_VOWEL, config.longVowelKeyEnabled)
            .putBoolean(KEY_SMART_PRED, config.smartPredictionEnabled)
            .putBoolean(KEY_LEARNING, config.learningEnabled)
            .putInt(KEY_PAGE_SIZE, config.candidatePageSize)
            .putString(KEY_ENABLED_KEYBOARDS, config.enabledKeyboardIds.joinToString(","))
            .apply()

        // Also sync to world-readable or accessible json file for Xposed process
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val configFile = File(dir, "config.json")
            configFile.writeText(config.toJson())
        }
    }
}
