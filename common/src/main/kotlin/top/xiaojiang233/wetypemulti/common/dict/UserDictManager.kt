package top.xiaojiang233.wetypemulti.common.dict

import java.io.InputStream
import java.io.OutputStream

/**
 * Service interface for dictionary operations: importing, exporting, and statistics.
 */
interface UserDictManager {
    data class DictStats(
        val coreWordsCount: Int,
        val kanaCount: Int,
        val learnedBigramsCount: Int,
    )

    /**
     * Imports dictionary data from an [InputStream] (format can be TSV, Mozc, or Rime dict.yaml).
     * Returns the number of imported entries.
     */
    fun importDictionary(inputStream: InputStream, filename: String): Int

    /**
     * Exports user-learned words and bigrams to TSV format into [outputStream].
     */
    fun exportUserDictionary(outputStream: OutputStream)

    /**
     * Retrieves current dictionary statistics.
     */
    fun getStats(): DictStats

    /**
     * Clears all user-learned words and bigrams.
     */
    fun clearUserLearning()
}
