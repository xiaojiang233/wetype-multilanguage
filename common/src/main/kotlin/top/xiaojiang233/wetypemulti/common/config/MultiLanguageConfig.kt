package top.xiaojiang233.wetypemulti.common.config

/**
 * Global configuration options for WeType MultiLanguage.
 */
data class MultiLanguageConfig(
    val longVowelKeyEnabled: Boolean = true,
    val smartPredictionEnabled: Boolean = true,
    val learningEnabled: Boolean = true,
    val candidatePageSize: Int = 50,
    val enabledKeyboardIds: List<String> = listOf("zh_CN", "en_US", "ja_JP"),
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"longVowelKeyEnabled\": ").append(longVowelKeyEnabled).append(",\n")
        append("  \"smartPredictionEnabled\": ").append(smartPredictionEnabled).append(",\n")
        append("  \"learningEnabled\": ").append(learningEnabled).append(",\n")
        append("  \"candidatePageSize\": ").append(candidatePageSize).append(",\n")
        append("  \"enabledKeyboardIds\": [")
        append(enabledKeyboardIds.joinToString(", ") { "\"$it\"" })
        append("]\n")
        append("}")
    }

    companion object {
        fun fromJson(json: String): MultiLanguageConfig {
            var longVowel = true
            var smartPred = true
            var learning = true
            var pageSize = 50
            var keyboards = listOf("zh_CN", "en_US", "ja_JP")

            for (line in json.lines()) {
                val trim = line.trim().removeSuffix(",")
                when {
                    "\"longVowelKeyEnabled\"" in trim ->
                        trim.substringAfter(":").trim().toBooleanStrictOrNull()?.let { longVowel = it }
                    "\"smartPredictionEnabled\"" in trim ->
                        trim.substringAfter(":").trim().toBooleanStrictOrNull()?.let { smartPred = it }
                    "\"learningEnabled\"" in trim ->
                        trim.substringAfter(":").trim().toBooleanStrictOrNull()?.let { learning = it }
                    "\"candidatePageSize\"" in trim ->
                        trim.substringAfter(":").trim().toIntOrNull()?.let { pageSize = it }
                    "\"enabledKeyboardIds\"" in trim -> {
                        val ids = trim.substringAfter("[").substringBefore("]")
                            .split(",")
                            .map { it.trim().removeSurrounding("\"") }
                            .filter { it.isNotBlank() }
                        if (ids.isNotEmpty()) {
                            keyboards = ids
                        }
                    }
                }
            }
            return MultiLanguageConfig(
                longVowelKeyEnabled = longVowel,
                smartPredictionEnabled = smartPred,
                learningEnabled = learning,
                candidatePageSize = pageSize,
                enabledKeyboardIds = keyboards,
            )
        }
    }
}
