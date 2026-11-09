package top.xiaojiang233.wetypemulti.common.sync

/** Text form of one [SyncItem]: `type TAB key TAB value TAB deleted TAB timestamp TAB device`, TAB/newline escaped. */
object SyncFormat {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun unesc(s: String): String {
        val o = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                o.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] }); i += 2
            } else { o.append(c); i++ }
        }
        return o.toString()
    }

    fun encode(i: SyncItem) =
        "${i.type}\t${esc(i.key)}\t${esc(i.value)}\t${if (i.deleted) 1 else 0}\t${i.timestamp}\t${esc(i.device)}"

    fun decode(line: String): SyncItem? {
        val c = line.split('\t'); if (c.size < 6) return null
        return runCatching { SyncItem(DataType.valueOf(c[0]), unesc(c[1]), unesc(c[2]), c[3] == "1", c[4].toLong(), unesc(c[5])) }.getOrNull()
    }
}
