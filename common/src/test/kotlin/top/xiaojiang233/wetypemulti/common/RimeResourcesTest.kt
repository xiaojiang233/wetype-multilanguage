package top.xiaojiang233.wetypemulti.common

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the bundled Rime resources against what made Japanese input unusable on a phone: a dictionary of 320,000 entries
 * and 46 spelling rules took 41 s to compile, on the key thread, at every start of the IME process. The numbers here are not
 * magic; they leave room to grow while keeping the first deploy in the order of seconds. See scripts/generate_builtin_dict.py.
 */
class RimeResourcesTest {
    private fun resource(name: String): String =
        checkNotNull(RimeResourcesTest::class.java.classLoader.getResourceAsStream("rime/$name")) { "missing resource rime/$name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** The entries of a Rime dictionary file: (word, code, weight or null), everything after the `...` line. */
    private fun entries(name: String): List<Triple<String, String, String?>> =
        resource(name).lines().dropWhile { it != "..." }.drop(1)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Triple(it[0], it[1], it.getOrNull(2)) } }

    private fun algebra(): List<String> {
        val lines = resource("japanese.schema.yaml").lines()
        val start = lines.indexOfFirst { it.trim() == "algebra:" }
        assertTrue(start >= 0, "japanese.schema.yaml has no speller algebra")
        return lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.trim().startsWith("#") || it.trim().startsWith("- ") }
            .map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
    }

    @Test
    fun coreDictionaryStaysSmall() {
        val core = entries("japanese.core.dict.yaml")
        assertTrue(core.size in 20_000..100_000, "core dictionary has ${core.size} entries; it is compiled on the phone, keep it near 50,000")
        assertTrue(resource("japanese.core.dict.yaml").length < 3_000_000, "core dictionary is bigger than 3 MB")
    }

    @Test
    fun spellingAlgebraStaysShortAndTypeable() {
        val rules = algebra()
        // Every rule runs on every distinct code of the dictionary, so the count multiplies the compile time.
        assertTrue(rules.size in 1..16, "speller algebra has ${rules.size} rules; each one costs a regex run per dictionary code")
        for (rule in rules) {
            // abbrev/^([a-z]).+$/$1/ once turned "watas" into the sentence わたっ先生.
            assertTrue(!rule.startsWith("abbrev/"), "abbreviation rule would make single letters match whole words: $rule")
            if (rule.startsWith("derive/") || rule.startsWith("fuzz/")) {
                val replacement = rule.substringAfter('/').removeSuffix("/").substringAfter('/')
                assertTrue(' ' !in replacement, "a spelling with a space can never be typed: $rule")
            }
        }
    }

    @Test
    fun everyCodeCanBeTypedWithTheSpellerAlphabet() {
        val alphabet = Regex("alphabet:\\s*\"([^\"]*)\"").find(resource("japanese.schema.yaml"))!!.groupValues[1].toSet()
        for (file in listOf("japanese.kana.dict.yaml", "japanese.core.dict.yaml")) {
            val untypeable = entries(file).filter { (_, code, _) -> code.any { it !in alphabet } }
            assertTrue(untypeable.isEmpty(), "$file has codes outside the speller alphabet: ${untypeable.take(5)}")
        }
    }

    @Test
    fun mainDictionaryImportsTheTwoTablesByName() {
        val main = resource("japanese.dict.yaml")
        assertTrue("  - japanese.kana" in main && "  - japanese.core" in main, "japanese.dict.yaml must import japanese.kana and japanese.core")
        assertTrue("name: japanese.kana" in resource("japanese.kana.dict.yaml"))
        assertTrue("name: japanese.core" in resource("japanese.core.dict.yaml"))
    }

    @Test
    fun smallKanaAndSokuonCanBeTypedTheUsualWay() {
        val codes = entries("japanese.kana.dict.yaml").groupBy({ it.second }, { it.first })
        for (small in listOf("xa", "la", "xtu", "ltu", "xtsu", "ltsu", "xya", "lyo")) assertTrue(small in codes, "no kana typed as $small")
        assertTrue("っ" in codes.getValue("xtu"), "xtu should type the small tsu")
        for (geminate in listOf("kka", "tte", "ssho", "ppo")) assertTrue(geminate in codes, "no doubled-consonant kana typed as $geminate")
    }

    @Test
    fun theLongVowelMarkIsTypedWithTheHyphen() {
        // The hyphen key and the keyboard's own long vowel key both send "-"; the kana table makes it ー, alone and inside "ka-".
        assertTrue(("ー" to "-") in entries("japanese.kana.dict.yaml").map { it.first to it.second })
    }

    @Test
    fun longVowelsMayBeTypedShortButOnlyAsFuzzySpellings() {
        // Tokyo, chugoku, ohayo: fuzzy, so that a word typed in full still ranks first.
        val rules = algebra()
        for (r in listOf("fuzz/ou/o/", "fuzz/uu/u/")) assertTrue(r in rules, "missing $r")
        assertTrue(rules.none { it.startsWith("derive/ou") || it.startsWith("derive/uu") }, "long vowel shortening must be fuzz, not derive")
    }

    @Test
    fun noWordIsDefinedTwice() {
        // Rime logs a warning per repeat while compiling, thousands of lines on a phone.
        val seen = HashSet<Pair<String, String>>()
        val duplicates = ArrayList<Pair<String, String>>()
        for (file in listOf("japanese.kana.dict.yaml", "japanese.core.dict.yaml")) {
            for ((word, code, _) in entries(file)) if (!seen.add(word to code)) duplicates += word to code
        }
        assertTrue(duplicates.isEmpty(), "words defined twice: ${duplicates.take(5)}")
    }

    @Test
    fun singleSyllableKanjiNeverOutweighsTheKana() {
        // The source weights a kanji by how common the character is, whatever the reading: 日 is heavy even for "ka". At the end
        // of a sentence ("...desuka") that made か come out as 日.
        val kanaCodes = entries("japanese.kana.dict.yaml").map { it.second }.toSet()
        val kanaWeight = entries("japanese.kana.dict.yaml").first().third!!.toInt()
        val heavy = entries("japanese.core.dict.yaml").filter { (word, code, weight) ->
            word.length == 1 && word[0] in '一'..'鿿' && code in kanaCodes && (weight?.toInt() ?: 0) >= kanaWeight
        }
        assertTrue(heavy.isEmpty(), "single kanji heavier than the kana for the same syllable: ${heavy.take(5)}")
    }
}
