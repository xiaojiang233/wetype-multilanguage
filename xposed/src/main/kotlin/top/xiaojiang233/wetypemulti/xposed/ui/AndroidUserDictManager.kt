package top.xiaojiang233.wetypemulti.xposed.ui

import android.content.Context
import top.xiaojiang233.wetypemulti.common.dict.UserDictManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

class AndroidUserDictManager(private val context: Context) : UserDictManager {

    private val userDictFile: File
        get() = File(context.filesDir, "user_learning.tsv")

    override fun getStats(): UserDictManager.DictStats {
        val userLearnedCount = if (userDictFile.exists()) {
            userDictFile.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        } else {
            0
        }
        return UserDictManager.DictStats(
            coreWordsCount = 120480, // Builtin Japanese core dictionary words
            kanaCount = 390,        // Builtin Kana & Romaji mappings
            learnedBigramsCount = userLearnedCount,
        )
    }

    override fun importDictionary(inputStream: InputStream, filename: String): Int {
        var count = 0
        val target = userDictFile
        target.parentFile?.mkdirs()
        target.bufferedWriter().use { writer ->
            if (!target.exists() || target.length() == 0L) {
                writer.write("# WeType MultiLanguage User Dictionary\n")
            }
            BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                for (line in lines) {
                    val trim = line.trim()
                    if (trim.isEmpty() || trim.startsWith("#") || trim.startsWith("---")) continue
                    // Accept TSV (word \t reading) or YAML (word \t reading \t weight)
                    val parts = trim.split("\t")
                    if (parts.size >= 2) {
                        writer.append(parts[0]).append("\t").append(parts[1])
                        if (parts.size >= 3) {
                            writer.append("\t").append(parts[2])
                        }
                        writer.append("\n")
                        count++
                    }
                }
            }
        }
        return count
    }

    override fun exportUserDictionary(outputStream: OutputStream) {
        outputStream.bufferedWriter().use { writer ->
            writer.write("# WeType MultiLanguage Exported User Dictionary\n")
            writer.write("# Word\tReading\tWeight\n")
            if (userDictFile.exists()) {
                userDictFile.forEachLine { line ->
                    if (line.isNotBlank()) {
                        writer.write(line)
                        writer.write("\n")
                    }
                }
            }
        }
    }

    override fun clearUserLearning() {
        if (userDictFile.exists()) {
            userDictFile.delete()
        }
    }
}
