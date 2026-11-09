package top.xiaojiang233.wetypemulti.common.romaji

/** Romaji (Hepburn + common Kunrei/Nihon variants) <-> hiragana conversion. */
object Romaji {
    // Preferred (Hepburn) spelling must come FIRST for each kana: it is used for reverse conversion.
    private const val RAW = """
a あ|i い|u う|e え|o お
ka か|ki き|ku く|ke け|ko こ
sa さ|shi し|su す|se せ|so そ
ta た|chi ち|tsu つ|te て|to と
na な|ni に|nu ぬ|ne ね|no の
ha は|hi ひ|fu ふ|he へ|ho ほ
ma ま|mi み|mu む|me め|mo も
ya や|yu ゆ|yo よ
ra ら|ri り|ru る|re れ|ro ろ
wa わ|wo を
ga が|gi ぎ|gu ぐ|ge げ|go ご
za ざ|ji じ|zu ず|ze ぜ|zo ぞ
da だ|de で|do ど
ba ば|bi び|bu ぶ|be べ|bo ぼ
pa ぱ|pi ぴ|pu ぷ|pe ぺ|po ぽ
kya きゃ|kyu きゅ|kyo きょ
sha しゃ|shu しゅ|sho しょ
cha ちゃ|chu ちゅ|cho ちょ
nya にゃ|nyu にゅ|nyo にょ
hya ひゃ|hyu ひゅ|hyo ひょ
mya みゃ|myu みゅ|myo みょ
rya りゃ|ryu りゅ|ryo りょ
gya ぎゃ|gyu ぎゅ|gyo ぎょ
ja じゃ|ju じゅ|jo じょ
bya びゃ|byu びゅ|byo びょ
pya ぴゃ|pyu ぴゅ|pyo ぴょ
fa ふぁ|fi ふぃ|fe ふぇ|fo ふぉ
thi てぃ|dhi でぃ|twu とぅ|dwu どぅ|wi うぃ|we うぇ|va ゔぁ|vi ゔぃ|vu ゔ|ve ゔぇ|vo ゔぉ
je じぇ|che ちぇ|she しぇ|tsa つぁ|tsi つぃ|tse つぇ|tso つぉ
la ぁ|li ぃ|lu ぅ|le ぇ|lo ぉ|xa ぁ|xi ぃ|xu ぅ|xe ぇ|xo ぉ
lya ゃ|lyu ゅ|lyo ょ|xya ゃ|xyu ゅ|xyo ょ|ltu っ|xtu っ|ltsu っ|xtsu っ|lwa ゎ|xwa ゎ
si し|ci ち|ti ち|tu つ|di ぢ|du づ|hu ふ|zi じ|sya しゃ|syu しゅ|syo しょ|tya ちゃ|tyu ちゅ|tyo ちょ
zya じゃ|zyu じゅ|zyo じょ|jya じゃ|jyu じゅ|jyo じょ|cya ちゃ|cyu ちゅ|cyo ちょ|dya ぢゃ|dyu ぢゅ|dyo ぢょ
n' ん|xn ん|- ー|, 、|. 。|? ？|! ！|[ 「|] 」
"""

    private val forward = LinkedHashMap<String, String>()
    private val reverse = LinkedHashMap<String, String>()

    init {
        for (pair in RAW.split('\n', '|')) {
            val p = pair.trim()
            if (p.isEmpty()) continue
            val (k, v) = p.split(' ')
            forward.putIfAbsent(k, v)
            reverse.putIfAbsent(v, k)
        }
    }

    private val maxKeyLen = forward.keys.maxOf { it.length }
    private val vowels = "aiueo"

    data class Result(val kana: String, val pending: String)

    private fun isPrefixOfKey(s: String) = forward.keys.any { it.startsWith(s) }

    /** Convert as much of [input] as possible; the unfinished tail is returned as [Result.pending]. */
    fun convert(input: String): Result {
        val s = input.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            var matched = false
            for (len in minOf(maxKeyLen, s.length - i) downTo 1) {
                val v = forward[s.substring(i, i + len)] ?: continue
                out.append(v); i += len; matched = true; break
            }
            if (matched) continue
            val c = s[i]
            if (c.isLetter() && c !in vowels && c != 'n' && i + 1 < s.length && s[i + 1] == c) {
                out.append('っ'); i++; continue
            }
            // "matcha": the t is the small tsu and "cha" follows (Mozc's table has tch -> っ + ch).
            if (c == 't' && s.startsWith("ch", i + 1)) { out.append('っ'); i++; continue }
            if (c == 'n' && i + 1 < s.length) {
                val nx = s[i + 1]
                if (nx == 'n') {
                    // "nn" is ん; but in "konnichi" the second n starts "ni".
                    val after = s.getOrNull(i + 2)
                    out.append('ん'); i += if (after != null && (after in vowels || after == 'y')) 1 else 2
                    continue
                }
                if (nx !in vowels && nx != 'y' && nx != '\'') { out.append('ん'); i++; continue }
            }
            val rest = s.substring(i)
            if (isPrefixOfKey(rest)) {
                return Result(out.toString(), rest)
            }
            if (c.isLetter() && i == s.length - 1) return Result(out.toString(), rest)
            out.append(c); i++
        }
        return Result(out.toString(), "")
    }

    /** Convert and force-flush a trailing "n" to ん. */
    fun toKana(input: String): String {
        val r = convert(input)
        return r.kana + r.pending.replace("n", "ん")
    }

    fun toKatakana(hiragana: String): String = buildString {
        for (ch in hiragana) append(if (ch in 'ぁ'..'ゖ') (ch + 0x60) else ch)
    }

    fun toHiragana(katakana: String): String = buildString {
        for (ch in katakana) append(if (ch in 'ァ'..'ヶ') (ch - 0x60) else ch)
    }

    /** Hiragana/katakana -> Hepburn romaji (used when exporting readings to Rime codes). */
    fun toRomaji(kana: String): String {
        val s = toHiragana(kana)
        val out = StringBuilder()
        var i = 0
        var sokuon = false
        while (i < s.length) {
            val ch = s[i]
            if (ch == 'っ') { sokuon = true; i++; continue }
            var piece: String? = null
            var len = 1
            if (i + 1 < s.length) { piece = reverse[s.substring(i, i + 2)]; if (piece != null) len = 2 }
            if (piece == null) piece = reverse[ch.toString()] ?: ch.toString()
            if (ch == 'ん') {
                val next = s.getOrNull(i + 1)?.let { reverse[it.toString()] }
                piece = if (next != null && (next[0] in vowels || next[0] == 'y' || next[0] == 'n')) "n'" else "n"
            }
            if (sokuon) { if (piece[0].isLetter()) out.append(piece[0]); sokuon = false }
            out.append(piece)
            i += len
        }
        if (sokuon) out.append("xtu")
        return out.toString()
    }
}
