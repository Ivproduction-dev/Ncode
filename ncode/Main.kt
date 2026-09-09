class NcodeInterpreter {
    private val vars = mutableMapOf<String, String>()
    private var execFailed = false
    private val nameRegex = Regex("^[а-яёА-ЯЁa-zA-Z_][а-яёА-ЯЁa-zA-Z0-9_]*$")
    private val numberRegex = Regex("^-?\\d+(\\.\\d+)?$")
    private val trueWords = setOf("истина", "да", "правда")
    private val falseWords = setOf("ложь", "нет", "неправда")
    private val reserved = trueWords + falseWords + setOf(
        "пробел", "и", "или",
        "равно", "равняется", "неравно", "неравняется", "больше", "меньше",
        "плюс", "минус", "умножить", "разделить", "поделить", "остаток",
        "если", "эсли", "то", "тогда", "иначеесли", "иначе", "конец",
        "повтори", "повторить", "повторять",
        "случайно", "корень", "модуль", "округлить", "степень", "минимум", "максимум", "длина"
    )

    private val cmpBody =
        "больше\\s+или\\s+равняется|больше\\s+или\\s+равно|" +
        "меньше\\s+или\\s+равняется|меньше\\s+или\\s+равно|" +
        "неравняется|неравно|равняется|равно|больше|меньше"
    private val cmpRegex = Regex("(^|[\\s\".])($cmpBody)(?=$|[\\s\".])")
    private val cmp3Regex = Regex(
        "(^|[\\s\".])(больше\\s+или\\s+равняется|больше\\s+или\\s+равно|" +
        "меньше\\s+или\\s+равняется|меньше\\s+или\\s+равно)(?=$|[\\s\".])"
    )

    private enum class Cmp { EQ, NE, GT, LT, GE, LE }

    fun runLines(lines: List<String>, base: Int = 1, top: Boolean = true): Int {
        var hadError = false
        var i = 0
        while (i < lines.size) {
            val skip = if (top) skipRanges.firstOrNull { i in it } else null
            if (skip != null) { i = skip.last + 1; continue }
            val rawLine = lines[i]
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) { i++; continue }
            try {
                if (startsKw(line, "если", "эсли")) {
                    i = runIf(lines, i, base)
                } else if (startsKw(line, "повтори", "повторить", "повторять")) {
                    i = runRepeat(lines, i, base)
                } else if (startsKakTolko(line)) {
                    i = runKakTolko(lines, i, base)
                } else {
                    if (startsKw(line, "конец"))
                        throw NcodeError("`конец` без `если`/`повтори`")
                    runLine(line)
                    i++
                }
            } catch (e: NcodeError) {
                System.err.println("Ошибка в строке ${base + i}: ${e.message}  >> $rawLine")
                hadError = true
                i = if (startsKw(line, "если", "эсли", "повтори", "повторить", "повторять") || startsKakTolko(line)) {
                    try { collectIf(lines, i).second } catch (e2: NcodeError) { lines.size }
                } else i + 1
            }
        }
        return if (hadError || execFailed) 1 else 0
    }

    private data class Handler(val msgExpr: String, val headerLine: Int, val lines: List<String>, val base: Int)

    private val handlers = mutableListOf<Handler>()
    private val skipRanges = mutableListOf<IntRange>()
    private var broadcastDepth = 0

    fun extractHandlers(lines: List<String>) {
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) { i++; continue }
            val msg = handlerMsg(t)
            if (msg == null) { i++; continue }
            if (msg.isEmpty()) throw NcodeError("строка ${i + 1}: после `когда будет получено` нужно сообщение")
            val start = i
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && handlerMsg(lines[i].trim()) == null) {
                body.add(lines[i])
                i++
            }
            handlers.add(Handler(msg, start + 1, body, start + 2))
            skipRanges.add(start..i - 1)
        }
    }

    private fun handlerMsg(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[0].lowercase(java.util.Locale.ROOT) != "когда") return null
        val second = parts[1].lowercase(java.util.Locale.ROOT)
        val n = when {
            second == "получено" -> 2
            second == "будет" && parts.size >= 3 && parts[2].lowercase(java.util.Locale.ROOT) == "получено" -> 3
            else -> return null
        }
        val s = t.trim()
        var j = 0
        var seen = 0
        while (seen < n && j < s.length) {
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
            seen++
            while (j < s.length && s[j].isWhitespace()) j++
        }
        return s.substring(j).trim()
    }

    private fun runVeshat(line: String) {
        var msg = keywordTail(line, "вещать")
        if (startsKw(msg, "всем")) msg = msg.trim().substring(4).trim()
        if (msg.isEmpty()) throw NcodeError("нужно: вещать всем <сообщение>")
        val value = evalExpression(msg)
        if (broadcastDepth >= 100) throw NcodeError("события зациклились")
        broadcastDepth++
        try {
            for (h in handlers) {
                val want = try {
                    evalExpression(h.msgExpr)
                } catch (e: NcodeError) {
                    System.err.println("Ошибка в строке ${h.headerLine}: ${e.message}")
                    execFailed = true
                    continue
                }
                if (want == value) {
                    if (runLines(h.lines, h.base, false) != 0) execFailed = true
                }
            }
        } finally {
            broadcastDepth--
        }
    }

    private fun startsKakTolko(line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return false
        return parts[0].lowercase(java.util.Locale.ROOT) == "как" &&
            parts[1].lowercase(java.util.Locale.ROOT) == "только"
    }

    private fun startsKw(line: String, vararg words: String): Boolean {
        val t = line.trim()
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        if (j == 0) return false
        return t.substring(0, j).lowercase(java.util.Locale.ROOT) in words
    }

    private enum class Kw { ЕСЛИ, ТО, ИНАЧЕЕСЛИ, ИНАЧЕ, КОНЕЦ, ПОВТОРИ, КАКТОЛЬКО }
    private data class KwHit(val kw: Kw, val start: Int, val end: Int)

    private fun findKw(text: String): List<KwHit> {
        val out = mutableListOf<KwHit>()
        var i = 0
        var inQuotes = false
        while (i < text.length) {
            val c = text[i]
            if (c == '"') { inQuotes = !inQuotes; i++; continue }
            if (inQuotes) { i++; continue }
            if (c.isLetter()) {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val w = text.substring(i, j).lowercase(java.util.Locale.ROOT)
                if (w == "как") {
                    var k = j
                    while (k < text.length && text[k].isWhitespace()) k++
                    var m = k
                    while (m < text.length && (text[m].isLetterOrDigit() || text[m] == '_')) m++
                    if (text.substring(k, m).lowercase(java.util.Locale.ROOT) == "только") {
                        out.add(KwHit(Kw.КАКТОЛЬКО, i, m))
                        i = m
                        continue
                    }
                }
                val kw = when (w) {
                    "если", "эсли" -> Kw.ЕСЛИ
                    "то", "тогда" -> Kw.ТО
                    "иначеесли" -> Kw.ИНАЧЕЕСЛИ
                    "иначе" -> Kw.ИНАЧЕ
                    "конец" -> Kw.КОНЕЦ
                    "повтори", "повторить", "повторять" -> Kw.ПОВТОРИ
                    else -> null
                }
                if (kw != null) out.add(KwHit(kw, i, j))
                i = j
            } else i++
        }
        return out
    }

    private fun collectIf(lines: List<String>, start: Int): Pair<String, Int> {
        var acc = lines[start].trim()
        var idx = start
        var endPos = matchEnd(acc)
        while (endPos < 0) {
            idx++
            if (idx >= lines.size) throw NcodeError("нет `конец` для `если`")
            val t = lines[idx].trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
            acc += " $t"
            endPos = matchEnd(acc)
        }
        if (acc.substring(endPos).trim().isNotEmpty())
            throw NcodeError("после `конец` ничего не должно быть")
        return acc.substring(0, endPos) to (idx + 1)
    }

    private fun runIf(lines: List<String>, start: Int, base: Int): Int {
        val first = lines[start].trim()
        val end = matchEnd(first)
        if (end >= 0) {
            if (first.substring(end).trim().isNotEmpty())
                throw NcodeError("после `конец` ничего не должно быть")
            parseIf(first.substring(0, end), base + start)
            return start + 1
        }
        return parseBlock(lines, start, base)
    }
    private sealed interface BlockItem {
        data class Cmd(val lineNo: Int, val text: String) : BlockItem
        data class Sub(val firstLine: Int, val lines: List<String>) : BlockItem
    }

    private data class Branch(
        val cond: String,
        val condLine: Int,
        val cmds: MutableList<BlockItem> = mutableListOf()
    )

    private fun splitHeader(raw: String): Pair<String, String> {
        val t = raw.trim()
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        val to = findKw(t).firstOrNull { it.kw == Kw.ТО && it.start >= j }
            ?: throw NcodeError("после условия нужно `то`")
        val cond = t.substring(j, to.start).trim()
        if (cond.isEmpty()) throw NcodeError("пустое условие")
        return cond to t.substring(to.end).trim()
    }

    private fun trailHasBranchKw(trail: String): Boolean =
        findKw(trail).any { it.kw == Kw.ИНАЧЕЕСЛИ || it.kw == Kw.ИНАЧЕ || it.kw == Kw.КОНЕЦ }

    private fun parseBlock(lines: List<String>, start: Int, base: Int): Int {
        val branches = mutableListOf<Branch>()
        val elseCmds = mutableListOf<BlockItem>()
        var hasElse = false
        val (cond, trail) = splitHeader(lines[start])
        if (trailHasBranchKw(trail))
            throw NcodeError("в многострочном `если` команда — с новой строки")
        branches.add(Branch(cond, base + start))
        if (trail.isNotEmpty()) branches.last().cmds.add(BlockItem.Cmd(base + start, trail))
        var cur: MutableList<BlockItem> = branches.last().cmds
        var inElse = false
        var i = start + 1
        var closed = false
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) { i++; continue }
            if (startsKw(t, "иначеесли")) {
                if (inElse) throw NcodeError("`иначеесли` после `иначе` — нельзя")
                val (c2, tr2) = splitHeader(raw)
                if (trailHasBranchKw(tr2))
                    throw NcodeError("в многострочном `если` команда — с новой строки")
                branches.add(Branch(c2, base + i))
                if (tr2.isNotEmpty()) branches.last().cmds.add(BlockItem.Cmd(base + i, tr2))
                cur = branches.last().cmds
                i++; continue
            }
            if (startsKw(t, "иначе")) {
                if (inElse) throw NcodeError("второе `иначе` — нельзя")
                val rest = t.substring(5).trim()
                if (trailHasBranchKw(rest))
                    throw NcodeError("в многострочном `если` команда — с новой строки")
                inElse = true; hasElse = true
                if (rest.isNotEmpty()) elseCmds.add(BlockItem.Cmd(base + i, rest))
                cur = elseCmds
                i++; continue
            }
            if (startsKw(t, "конец")) {
                if (t.substring(5).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                closed = true; i++; break
            }
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) cur.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять")) {
                val sub = mutableListOf(raw)
                var j = i
                var acc = t
                while (matchEnd(acc) < 0) {
                    j++
                    if (j >= lines.size) throw NcodeError("нет `конец` для вложенного блока")
                    val tj = lines[j].trim()
                    if (tj.isEmpty() || tj.startsWith("#") || tj.startsWith("//")) continue
                    sub.add(lines[j])
                    acc += " $tj"
                }
                cur.add(BlockItem.Sub(base + i, sub))
                i = j + 1; continue
            }
            cur.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для `если`")
        for (b in branches) {
            val condTrue = try {
                toBool(evalExpression(b.cond))
            } catch (e: NcodeError) {
                System.err.println("Ошибка в строке ${b.condLine}: ${e.message}")
                execFailed = true
                return i
            }
            if (condTrue) {
                for (item in b.cmds) execItem(item)
                return i
            }
        }
        if (hasElse) for (item in elseCmds) execItem(item)
        return i
    }

    private fun execItem(item: BlockItem): Boolean {
        return try {
            when (item) {
                is BlockItem.Cmd -> { runLine(item.text); true }
                is BlockItem.Sub -> {
                    val before = execFailed
                    execFailed = false
                    val failed = runLines(item.lines, item.firstLine, false) != 0 || execFailed
                    execFailed = before || failed
                    !failed
                }
            }
        } catch (e: NcodeError) {
            val ln = when (item) {
                is BlockItem.Cmd -> item.lineNo
                is BlockItem.Sub -> item.firstLine
            }
            System.err.println("Ошибка в строке $ln: ${e.message}")
            execFailed = true
            false
        }
    }

    private val razWords = setOf("раз", "раза", "разов")

    private fun runRepeat(lines: List<String>, start: Int, base: Int): Int {
        val first = lines[start].trim()
        val end = matchEnd(first)
        if (end >= 0) {
            if (first.substring(end).trim().isNotEmpty())
                throw NcodeError("после `конец` ничего не должно быть")
            val (countText, body) = splitRepeatInline(first.substring(0, end))
            val n = evalRepeatCount(countText)
            repeat(n) { execCmd(body, base + start) }
            return start + 1
        }
        return parseRepeatBlock(lines, start, base)
    }

    private fun splitRepeatUnit(t: String, from: Int): Triple<String, String, Int> {
        val ws = wordsOutsideQuotes(t)
        var unitIdx = -1
        for (k in ws.indices) {
            if (ws[k].start >= from &&
                ws[k].text.lowercase(java.util.Locale.ROOT) in razWords
            ) unitIdx = k
        }
        if (unitIdx < 0)
            throw NcodeError("нужно: повтори <число> раз, пример: повтори 3 раза")
        val count = t.substring(from, ws[unitIdx].start).trim()
        if (count.isEmpty()) throw NcodeError("нужно: повтори <число> раз — нет числа")
        return Triple(count, t.substring(ws[unitIdx].end).trim(), ws[unitIdx].end)
    }

    private fun splitRepeatInline(text: String): Pair<String, String> {
        var j = 0
        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val (count, _, unitEnd) = splitRepeatUnit(text, j)
        val end = matchEnd(text)
        val body = text.substring(unitEnd, end).trim()
        var depth = 0
        var closer: KwHit? = null
        for (h in findKw(body)) {
            when (h.kw) {
                Kw.ЕСЛИ, Kw.ПОВТОРИ -> depth++
                Kw.КОНЕЦ -> if (depth == 0) closer = h else depth--
                else -> {}
            }
        }
        val c = closer ?: throw NcodeError("нет `конец`")
        if (body.substring(c.end).trim().isNotEmpty())
            throw NcodeError("после `конец` ничего не должно быть")
        val cmd = body.substring(0, c.start).trim()
        if (cmd.isEmpty()) throw NcodeError("после `раз` пусто — нужна команда")
        return count to cmd
    }

    private fun runKakTolko(lines: List<String>, start: Int, base: Int): Int {
        val first = lines[start].trim()
        val end = matchEnd(first)
        if (end >= 0) {
            if (first.substring(end).trim().isNotEmpty())
                throw NcodeError("после `конец` ничего не должно быть")
            val (cond, body) = splitKakSingle(first.substring(0, end))
            if (body.isEmpty()) throw NcodeError("после `то` пусто — нужна команда")
            pollAndRun(cond, listOf(BlockItem.Cmd(base + start, body)), base + start)
            return start + 1
        }
        return parseKakBlock(lines, start, base)
    }

    private fun kakOpenerEnd(t: String): Int {
        var j = 0
        repeat(2) {
            while (j < t.length && t[j].isWhitespace()) j++
            while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        }
        return j
    }

    private fun splitToEnd(text: String, contentStart: Int): Pair<String, String> {
        var depth = 0
        var toHit: KwHit? = null
        var closer: KwHit? = null
        for (h in findKw(text)) {
            if (h.end <= contentStart) continue
            when (h.kw) {
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.КАКТОЛЬКО -> depth++
                Kw.ТО -> if (depth == 0 && toHit == null) toHit = h
                Kw.КОНЕЦ -> if (depth == 0) closer = h else depth--
                else -> {}
            }
        }
        val t = toHit ?: throw NcodeError("после условия нужно `то`")
        val c = closer ?: throw NcodeError("нет `конец`")
        return text.substring(contentStart, t.start).trim() to text.substring(t.end, c.start).trim()
    }

    private fun splitKakSingle(text: String): Pair<String, String> {
        val (cond, body) = splitToEnd(text, kakOpenerEnd(text.trim()))
        if (cond.isEmpty()) throw NcodeError("пустое условие")
        return cond to body
    }

    private fun parseKakBlock(lines: List<String>, start: Int, base: Int): Int {
        val t0 = lines[start].trim()
        var j = 0
        var seen = 0
        while (seen < 2 && j < t0.length) {
            while (j < t0.length && (t0[j].isLetterOrDigit() || t0[j] == '_')) j++
            seen++
            while (j < t0.length && t0[j].isWhitespace()) j++
        }
        var depth = 0
        var toEnd = -1
        var cond = ""
        for (h in findKw(t0)) {
            if (h.end <= j) continue
            when (h.kw) {
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.КАКТОЛЬКО -> depth++
                Kw.ТО -> if (depth == 0) {
                    cond = t0.substring(j, h.start).trim()
                    toEnd = h.end
                    break
                }
                else -> {}
            }
        }
        if (toEnd < 0) throw NcodeError("после условия нужно `то`")
        if (cond.isEmpty()) throw NcodeError("пустое условие")
        val trail = t0.substring(toEnd).trim()
        if (trailHasBranchKw(trail))
            throw NcodeError("в многострочном событии команда — с новой строки")
        val body = mutableListOf<BlockItem>()
        if (trail.isNotEmpty()) body.add(BlockItem.Cmd(base + start, trail))
        var i = start + 1
        var closed = false
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) { i++; continue }
            if (startsKw(t, "иначе", "иначеесли"))
                throw NcodeError("тут нет `иначе` — только условие и команды")
            if (startsKw(t, "конец")) {
                if (t.substring(5).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                closed = true; i++; break
            }
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) body.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять") || startsKakTolko(t)) {
                val sub = mutableListOf(raw)
                var k = i
                var acc = t
                while (matchEnd(acc) < 0) {
                    k++
                    if (k >= lines.size) throw NcodeError("нет `конец` для вложенного блока")
                    val tk = lines[k].trim()
                    if (tk.isEmpty() || tk.startsWith("#") || tk.startsWith("//")) continue
                    sub.add(lines[k])
                    acc += " $tk"
                }
                body.add(BlockItem.Sub(base + i, sub))
                i = k + 1; continue
            }
            body.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для события")
        pollAndRun(cond, body, base + start)
        return i
    }

    private fun pollAndRun(cond: String, body: List<BlockItem>, condLine: Int) {
        val fire = try {
            while (!toBool(evalExpression(cond))) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw NcodeError("ожидание прервано")
                }
            }
            true
        } catch (e: NcodeError) {
            System.err.println("Ошибка в строке $condLine: ${e.message}")
            execFailed = true
            false
        }
        if (fire) for (item in body) {
            if (!execItem(item)) break
        }
    }

    private fun evalRepeatCount(text: String): Int {
        val v = evalExpression(text)
        if (!numberRegex.matches(v))
            throw NcodeError("повторить надо целое число раз, а тут `$v`")
        val d = v.toDouble()
        if (d < 0) throw NcodeError("повторить отрицательное число раз — нельзя")
        if (d != kotlin.math.floor(d)) throw NcodeError("повторить надо целое число раз")
        if (d > 100000) throw NcodeError("многовато повторов (максимум 100000)")
        return d.toInt()
    }

    private fun parseRepeatBlock(lines: List<String>, start: Int, base: Int): Int {
        var j = 0
        val t0 = lines[start].trim()
        while (j < t0.length && (t0[j].isLetterOrDigit() || t0[j] == '_')) j++
        val (countText, trail, _) = splitRepeatUnit(t0, j)
        if (findKw(trail).any { it.kw == Kw.КОНЕЦ })
            throw NcodeError("в многострочном `повтори` команда — с новой строки")
        val body = mutableListOf<BlockItem>()
        if (trail.isNotEmpty()) body.add(BlockItem.Cmd(base + start, trail))
        var i = start + 1
        var closed = false
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) { i++; continue }
            if (startsKw(t, "конец")) {
                if (t.substring(5).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                closed = true; i++; break
            }
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) body.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять")) {
                val sub = mutableListOf(raw)
                var k = i
                var acc = t
                while (matchEnd(acc) < 0) {
                    k++
                    if (k >= lines.size) throw NcodeError("нет `конец` для вложенного блока")
                    val tk = lines[k].trim()
                    if (tk.isEmpty() || tk.startsWith("#") || tk.startsWith("//")) continue
                    sub.add(lines[k])
                    acc += " $tk"
                }
                body.add(BlockItem.Sub(base + i, sub))
                i = k + 1; continue
            }
            body.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для `повтори`")
        val n = evalRepeatCount(countText)
        repeat(n) {
            for (item in body) {
                if (!execItem(item)) return i
            }
        }
        return i
    }
    private fun matchEnd(text: String): Int {
        val hits = findKw(text)
        if (hits.isEmpty() ||
            (hits[0].kw != Kw.ЕСЛИ && hits[0].kw != Kw.ПОВТОРИ && hits[0].kw != Kw.КАКТОЛЬКО) ||
            hits[0].start != 0
        ) return -1
        var depth = 1
        for (h in hits.drop(1)) {
            when (h.kw) {
                Kw.ЕСЛИ -> depth++
                Kw.ПОВТОРИ -> depth++
                Kw.КАКТОЛЬКО -> depth++
                Kw.КОНЕЦ -> {
                    depth--
                    if (depth == 0) return h.end
                }
                else -> {}
            }
        }
        return -1
    }

    private fun parseIf(text: String, lineNo: Int) {
        val hits = findKw(text)
        val conds = mutableListOf<String>()
        val cmds = mutableListOf<String>()
        var elseCmd: String? = null
        var hasElse = false
        var depth = 0
        var state = 0
        var segStart = hits[0].end
        var cmdStart = 0
        for (h in hits.drop(1)) {
            when (h.kw) {
                Kw.ЕСЛИ -> depth++
                Kw.ПОВТОРИ -> depth++
                Kw.КАКТОЛЬКО -> depth++
                Kw.КОНЕЦ -> if (depth == 0) {
                    when (state) {
                        1 -> cmds.add(text.substring(cmdStart, h.start))
                        2 -> elseCmd = text.substring(cmdStart, h.start)
                        else -> throw NcodeError("после условия нужно `то`")
                    }
                    if (cmds.any { it.trim().isEmpty() })
                        throw NcodeError("после `то` пусто")
                    if (hasElse && elseCmd!!.trim().isEmpty())
                        throw NcodeError("после `иначе` пусто")
                    for (k in conds.indices) {
                        if (toBool(evalExpression(conds[k]))) { execCmd(cmds[k], lineNo); return }
                    }
                    if (hasElse) execCmd(elseCmd!!, lineNo)
                    return
                } else depth--
                Kw.ТО -> if (depth == 0) {
                    if (state != 0) throw NcodeError("лишнее `то`")
                    conds.add(text.substring(segStart, h.start))
                    if (conds.last().trim().isEmpty())
                        throw NcodeError("пустое условие")
                    state = 1
                    cmdStart = h.end
                }
                Kw.ИНАЧЕЕСЛИ -> if (depth == 0) {
                    if (state != 1) throw NcodeError("`иначеесли` без команды выше")
                    cmds.add(text.substring(cmdStart, h.start))
                    state = 0
                    segStart = h.end
                }
                Kw.ИНАЧЕ -> if (depth == 0) {
                    if (state != 1) throw NcodeError("`иначе` без команды выше")
                    cmds.add(text.substring(cmdStart, h.start))
                    state = 2
                    cmdStart = h.end
                    hasElse = true
                }
            }
        }
        throw NcodeError("нет `конец`")
    }

    private fun execCmd(cmd: String, lineNo: Int) {
        val t = cmd.trim()
        if (t.isEmpty()) throw NcodeError("пустая команда в ветке")
        if (startsKw(t, "если", "эсли")) parseIf(t, lineNo)
        else if (startsKw(t, "повтори", "повторить", "повторять")) {
            if (matchEnd(t) < 0) throw NcodeError("повтор в одну строку — допиши `конец`")
            runRepeat(listOf(t), 0, lineNo)
        }
        else if (startsKakTolko(t)) {
            if (matchEnd(t) < 0) throw NcodeError("событие в одну строку — допиши `конец`")
            runKakTolko(listOf(t), 0, lineNo)
        }
        else runLine(t)
    }

    private fun runLine(line: String) {
        val low = line.lowercase()
        when {
            low == "задать" || low.startsWith("задать ") || low.startsWith("задать\t") ->
                runZadat(line)
            isPrintCommand(low) ->
                runPrint(line)
            low == "ждать" || low.startsWith("ждать ") || low.startsWith("ждать\t") ->
                runWait(line)
            low == "спросить" || low.startsWith("спросить ") || low.startsWith("спросить\t") ->
                runAsk(line)
            low == "изменить" || low.startsWith("изменить ") || low.startsWith("изменить\t") ->
                runIzmenit(line, "изменить")
            low == "поменять" || low.startsWith("поменять ") || low.startsWith("поменять\t") ->
                runIzmenit(line, "поменять")
            low == "вещать" || low.startsWith("вещать ") || low.startsWith("вещать\t") ->
                runVeshat(line)
            else -> throw NcodeError("неизвестная команда (нужно: задать / изменить / напечатать / печатать / вывести / ждать / спросить / если / повтори / вещать)")
        }
    }

    private fun isPrintCommand(low: String): Boolean {
        val keys = listOf("напечатать", "печатать", "вывести")
        return keys.any { k -> low == k || low.startsWith("$k ") || low.startsWith("$k\t") }
    }

    private fun keywordTail(line: String, keyword: String): String {
        val tail = line.trim().substring(keyword.length)
        if (tail.isNotEmpty() && !tail[0].isWhitespace()) throw NcodeError("после `$keyword` нужен пробел")
        return tail.trim()
    }

    private fun runZadat(line: String) {
        val (name, value) = parseAssign(line, "задать")
        if (vars.containsKey(name.lowercase())) throw NcodeError("`$name` уже есть — используй `изменить`")
        vars[name.lowercase()] = value
    }

    private fun runIzmenit(line: String, keyword: String) {
        val (name, value) = parseAssign(line, keyword)
        if (!vars.containsKey(name.lowercase())) throw NcodeError("нет `$name` — сначала `задать`")
        vars[name.lowercase()] = value
    }

    private fun parseAssign(line: String, keyword: String): Pair<String, String> {
        val rest = keywordTail(line, keyword)
        if (rest.isEmpty()) throw NcodeError("нужно: $keyword <имя> <значение>")
        val splitAt = rest.indexOfFirst { it.isWhitespace() }
        if (splitAt < 0) throw NcodeError("нужно: $keyword <имя> <значение>")
        val name = rest.substring(0, splitAt)
        val expr = rest.substring(splitAt).trim()
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        if (name.lowercase() in reserved) throw NcodeError("`$name` — служебное слово, возьми другое имя")
        if (expr.isEmpty()) throw NcodeError("нужно: $keyword <имя> <значение>")
        return name to evalExpression(expr)
    }

    private fun runPrint(line: String) {
        val trimmed = line.trim()
        val low = trimmed.lowercase()
        val keyword = listOf("напечатать", "печатать", "вывести").first { k -> low == k || low.startsWith("$k ") || low.startsWith("$k\t") }
        val expr = trimmed.substring(keyword.length).trim()
        if (expr.isEmpty()) throw NcodeError("нужно: $keyword <что>")
        println(evalExpression(expr))
    }

    fun evalExpression(expr: String): String {
        val e = normalizeSymbols(expr)
        splitByWordOp(e, "или")?.let { parts ->
            if (parts.any { it.trim().isEmpty() }) throw NcodeError("у `или` пусто слева или справа")
            return if (parts.any { toBool(evalAnd(it)) }) "истина" else "ложь"
        }
        return evalAnd(e)
    }

    private fun evalAnd(expr: String): String {
        splitByWordOp(expr, "и")?.let { parts ->
            if (parts.any { it.trim().isEmpty() }) throw NcodeError("у `и` пусто слева или справа")
            return if (parts.all { toBool(evalComparison(it)) }) "истина" else "ложь"
        }
        return evalComparison(expr)
    }

    private fun evalComparison(expr: String): String {
        val low = expr.lowercase(java.util.Locale.ROOT)
        val m = cmpRegex.find(low) ?: return evalAddSub(expr)
        val leadLen = m.groupValues[1].length
        val left = expr.substring(0, m.range.first + leadLen)
        val right = expr.substring(m.range.last + 1)
        if (left.trim().isEmpty() || right.trim().isEmpty())
            throw NcodeError("у сравнения нужны левая и правая части")
        if (cmpRegex.containsMatchIn(right.lowercase(java.util.Locale.ROOT)))
            throw NcodeError("только одно сравнение в выражении")
        return compareValues(evalAddSub(left), evalAddSub(right), opOf(m.groupValues[2]))
    }

    private fun runWait(line: String) {
        val rest = keywordTail(line, "ждать")
        if (rest.isEmpty())
            throw NcodeError("нужно: ждать <число> <секунду|минуту|час>, пример: ждать 2 секунды")
        val cut = rest.trim().indexOfLast { it.isWhitespace() }
        if (cut < 0)
            throw NcodeError("нужно: ждать <число> <секунду|минуту|час>, пример: ждать 2 секунды")
        val numText = rest.trim().substring(0, cut).trim()
        val unit = rest.trim().substring(cut).trim()
        val numVal = evalExpression(numText)
        if (!numberRegex.matches(numVal)) throw NcodeError("`${numText}` — не число")
        val amount = numVal.toDouble()
        if (amount < 0) throw NcodeError("время не может быть отрицательным")
        val mult = when (unit.lowercase(java.util.Locale.ROOT)) {
            "секунда", "секунды", "секунд", "секунду" -> 1_000.0
            "минута", "минуты", "минут", "минуту" -> 60_000.0
            "час", "часа", "часов" -> 3_600_000.0
            else -> throw NcodeError(
                "не знаю единицу `$unit` " +
                "(можно: секунду/секунды/секунд, минуту/минуты/минут, час/часа/часов)"
            )
        }
        val millis = amount * mult
        if (millis > 86_400_000.0) throw NcodeError("максимум — 24 часа")
        try {
            Thread.sleep(millis.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NcodeError("ожидание прервано")
        }
    }

    private var stdin: java.io.BufferedReader? = null

    private fun runAsk(line: String) {
        val rest = keywordTail(line, "спросить")
        if (rest.isEmpty()) throw NcodeError("нужно: спросить <подсказка> сохранить в <имя>")
        val (prompt, name) = splitAsk(rest)
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        if (name.lowercase() in reserved) throw NcodeError("`$name` — служебное слово, возьми другое имя")
        if (prompt.isNotEmpty()) {
            print(evalConcat(prompt) + " ")
            System.out.flush()
        }
        if (stdin == null) stdin = java.io.BufferedReader(
            java.io.InputStreamReader(System.`in`, Charsets.UTF_8)
        )
        vars[name.lowercase()] = stdin!!.readLine() ?: ""
    }

    private val saveMarkers = listOf(
        listOf("сохранить", "в"),
        listOf("сохр", "в"),
        listOf("сохранить"),
        listOf("сохр")
    )

    private data class W(val text: String, val start: Int, val end: Int)

    private fun wordsOutsideQuotes(s: String): List<W> {
        val out = mutableListOf<W>()
        var i = 0
        var inQ = false
        while (i < s.length) {
            val c = s[i]
            if (c == '"') { inQ = !inQ; i++; continue }
            if (inQ) { i++; continue }
            if (c.isLetter()) {
                var j = i + 1
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                out.add(W(s.substring(i, j), i, j))
                i = j
            } else i++
        }
        return out
    }

    private fun splitAsk(rest: String): Pair<String, String> {
        val ws = wordsOutsideQuotes(rest)
        var cut: Pair<Int, Int>? = null
        outer@ for (s in ws.size - 1 downTo 0) {
            for (m in saveMarkers) {
                if (s + m.size > ws.size) continue
                var ok = true
                for (k in m.indices) {
                    if (!ws[s + k].text.equals(m[k], ignoreCase = true)) { ok = false; break }
                }
                if (ok) {
                    cut = ws[s].start to ws[s + m.size - 1].end
                    break@outer
                }
            }
        }
        if (cut == null) {
            if (!nameRegex.matches(rest.trim()))
                throw NcodeError("пиши: спросить <подсказка> сохранить в <имя>")
            return "" to rest.trim()
        }
        val name = rest.substring(cut.second).trim()
        if (!nameRegex.matches(name))
            throw NcodeError("после `сохранить в` нужно одно имя, пример: сохранить в имя")
        return rest.substring(0, cut.first).trim() to name
    }

    private val opWordsForUnary = setOf(
        "и", "или",
        "равно", "равняется", "неравно", "неравняется", "больше", "меньше",
        "плюс", "минус", "умножить", "разделить", "поделить", "остаток"
    )

    private fun normalizeSymbols(expr: String): String {
        val out = StringBuilder()
        var i = 0
        var inQuotes = false
        var needOperand = true
        while (i < expr.length) {
            val c = expr[i]
            if (c == '"') {
                inQuotes = !inQuotes
                if (!inQuotes) needOperand = false
                out.append(c)
                i++
                continue
            }
            if (inQuotes) {
                out.append(c)
                i++
                continue
            }
            val two = if (i + 1 < expr.length) expr.substring(i, i + 2) else ""
            val pair = when (two) {
                "==" -> " равно "
                "!=" -> " неравно "
                ">=" -> " больше или равно "
                "<=" -> " меньше или равно "
                "&&" -> " и "
                "||" -> " или "
                else -> null
            }
            if (pair != null) {
                out.append(pair)
                i += 2
                needOperand = true
                continue
            }
            val one = when (c) {
                '=' -> " равно "
                '>' -> " больше "
                '<' -> " меньше "
                '*' -> " умножить "
                '/' -> " разделить "
                '%' -> " остаток "
                else -> null
            }
            if (one != null) {
                out.append(one)
                i++
                needOperand = true
                continue
            }
            if (c == '+' || c == '-') {
                if (needOperand) {
                    out.append(c)
                    i++
                    continue
                }
                var k = i - 1
                while (k >= 0 && expr[k].isWhitespace()) k--
                var m = k
                while (m >= 0 && (expr[m].isLetterOrDigit() || expr[m] == '_')) m--
                val prev = expr.substring(m + 1, k + 1).lowercase(java.util.Locale.ROOT)
                if (prev in opWordsForUnary || prev in funcArity.keys) {
                    out.append(c)
                    i++
                    continue
                }
                out.append(if (c == '+') " плюс " else " минус ")
                i++
                needOperand = true
                continue
            }
            if (!c.isWhitespace()) needOperand = false
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun splitWithOps(expr: String, alts: String): Pair<List<String>, List<String>>? {
        val low = expr.lowercase(java.util.Locale.ROOT)
        val rx = Regex("(^|[\\s\".])($alts)(?=$|[\\s\".])")
        val matches = rx.findAll(low).toList()
        if (matches.isEmpty()) return null
        val parts = mutableListOf<String>()
        val ops = mutableListOf<String>()
        var pos = 0
        for (m in matches) {
            parts.add(expr.substring(pos, m.range.first + m.groupValues[1].length))
            ops.add(m.groupValues[2])
            pos = m.range.last + 1
        }
        parts.add(expr.substring(pos))
        return parts to ops
    }

    private fun evalAddSub(expr: String): String {
        val split = splitWithOps(expr, "плюс|минус") ?: return evalMulDiv(expr)
        val parts = split.first
        val ops = split.second
        var idx = 0
        var acc: Double
        if (parts[0].trim().isEmpty()) {
            if (parts[1].trim().isEmpty()) throw NcodeError("у операции нужны числа слева и справа")
            acc = evalArithOperand(parts[1])
            if (ops[0].lowercase(java.util.Locale.ROOT) == "минус") acc = -acc
            idx = 1
        } else {
            acc = evalArithOperand(parts[0])
        }
        while (idx < ops.size) {
            if (parts[idx + 1].trim().isEmpty()) throw NcodeError("у операции нужны числа слева и справа")
            val r = evalArithOperand(parts[idx + 1])
            acc = if (ops[idx].lowercase(java.util.Locale.ROOT) == "плюс") acc + r else acc - r
            idx++
        }
        return fmtNum(acc)
    }

    private fun evalMulDiv(expr: String): String {
        val split = splitWithOps(expr, "умножить|разделить|поделить|остаток") ?: return evalConcat(expr)
        val parts = split.first
        val ops = split.second
        if (parts.any { it.trim().isEmpty() }) throw NcodeError("у операции нужны числа слева и справа")
        var acc = evalArithOperand(parts[0])
        for (k in ops.indices) {
            val r = evalArithOperand(parts[k + 1])
            val op = ops[k].lowercase(java.util.Locale.ROOT)
            acc = when (op) {
                "умножить" -> acc * r
                "остаток" -> {
                    if (r == 0.0) throw NcodeError("на ноль делить нельзя")
                    acc % r
                }
                else -> {
                    if (r == 0.0) throw NcodeError("на ноль делить нельзя")
                    acc / r
                }
            }
        }
        return fmtNum(acc)
    }

    private fun evalArithOperand(t: String): Double {
        val v = evalMulDiv(t)
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        return v.toDouble()
    }

    private fun fmtNum(d: Double): String {
        if (!d.isFinite()) throw NcodeError("не число вышло")
        val r = if (kotlin.math.abs(d) < 1e15) kotlin.math.round(d * 1e10) / 1e10 else d
        return if (r == kotlin.math.floor(r) && kotlin.math.abs(r) < 9e18) r.toLong().toString() else r.toString()
    }

    private fun evalConcat(expr: String): String {
        val parts = expr.split("..")
        if (parts.size == 1) return evalSingle(parts[0].trim(), single = true)
        return parts.joinToString("") { raw -> evalSingle(raw, single = false, rawFallback = raw) }
    }

    private fun splitByWordOp(expr: String, word: String): List<String>? {
        val rx = Regex("(^|[\\s\".])($word)(?=$|[\\s\".])")
        val low = expr.lowercase(java.util.Locale.ROOT)
        val guarded = cmp3Regex.findAll(low).map { it.range }.toList()
        val matches = rx.findAll(low).filter { m ->
            guarded.none { g -> m.range.first <= g.last && g.first <= m.range.last }
        }.toList()
        if (matches.isEmpty()) return null
        val out = mutableListOf<String>()
        var pos = 0
        for (m in matches) {
            out.add(expr.substring(pos, m.range.first + m.groupValues[1].length))
            pos = m.range.last + 1
        }
        out.add(expr.substring(pos))
        return out
    }

    private fun opOf(raw: String): Cmp {
        return when (raw.trim().lowercase().replace(Regex("\\s+"), " ")) {
            "равно", "равняется" -> Cmp.EQ
            "неравно", "неравняется" -> Cmp.NE
            "больше" -> Cmp.GT
            "меньше" -> Cmp.LT
            "больше или равно", "больше или равняется" -> Cmp.GE
            "меньше или равно", "меньше или равняется" -> Cmp.LE
            else -> throw NcodeError("неизвестное сравнение `$raw`")
        }
    }

    private fun compareValues(l: String, r: String, op: Cmp): String {
        val res: Int = when {
            numberRegex.matches(l) && numberRegex.matches(r) ->
                l.toDouble().compareTo(r.toDouble())
            isBool(l) && isBool(r) ->
                boolNum(l).compareTo(boolNum(r))
            else -> {
                val ll = l.lowercase()
                val rr = r.lowercase()
                ll.compareTo(rr)
            }
        }
        val ok = when (op) {
            Cmp.EQ -> res == 0
            Cmp.NE -> res != 0
            Cmp.GT -> res > 0
            Cmp.LT -> res < 0
            Cmp.GE -> res >= 0
            Cmp.LE -> res <= 0
        }
        return if (ok) "истина" else "ложь"
    }

    private fun isBool(v: String) = v == "истина" || v == "ложь"
    private fun boolNum(v: String) = if (v == "истина") 1 else 0

    private fun toBool(v: String): Boolean {
        val t = v.trim().lowercase()
        if (t == "истина") return true
        if (t == "ложь") return false
        if (numberRegex.matches(t)) return t.toDouble() != 0.0
        return t.isNotEmpty()
    }

    private fun evalSingle(trimmedInput: String, single: Boolean, rawFallback: String = trimmedInput): String {
        val t = trimmedInput.trim()
        if (t.isEmpty()) return rawFallback
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1).trim()
            if (inner.isEmpty()) throw NcodeError("пустые кавычки \"\"")
            if (!nameRegex.matches(inner)) throw NcodeError("плохое имя в кавычках `\"$inner\"`")
            return vars[inner.lowercase()] ?: throw NcodeError("нет переменной \"$inner\"")
        }
        val low = t.lowercase()
        val toks = t.split(Regex("\\s+"))
        val arity = funcArity[toks[0].lowercase(java.util.Locale.ROOT)]
        if (arity != null) {
            val args = toks.drop(1)
            if (args.size != arity) throw NcodeError(
                "в `" + toks[0].lowercase(java.util.Locale.ROOT) + "` надо " +
                (if (arity == 1) "1 аргумент" else "$arity аргумента") +
                ", а тут " + args.size
            )
            return evalFunc(toks[0].lowercase(java.util.Locale.ROOT), args)
        }
        if (low in trueWords) return "истина"
        if (low in falseWords) return "ложь"
        if (!single && low == "пробел") return " "
        if (numberRegex.matches(t)) return t
        return t
    }

    private val funcArity = mapOf(
        "случайно" to 2,
        "корень" to 1,
        "модуль" to 1,
        "округлить" to 1,
        "степень" to 2,
        "минимум" to 2,
        "максимум" to 2,
        "длина" to 1
    )

    private fun resolveToken(tok: String): String {
        val t = tok.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1).trim()
            if (!nameRegex.matches(inner)) throw NcodeError("плохое имя в кавычках `\"$inner\"`")
            return vars[inner.lowercase()] ?: throw NcodeError("нет переменной \"$inner\"")
        }
        if (numberRegex.matches(t)) return t
        val low = t.lowercase(java.util.Locale.ROOT)
        if (low in trueWords) return "истина"
        if (low in falseWords) return "ложь"
        throw NcodeError("не понимаю `$tok` — нужно число или \"переменная\"")
    }

    private fun numArg(fname: String, tok: String): Double {
        val v = resolveToken(tok)
        if (!numberRegex.matches(v)) throw NcodeError("в `$fname` нужно число, а тут `$v`")
        return v.toDouble()
    }

    private fun evalFunc(fname: String, args: List<String>): String {
        when (fname) {
            "случайно" -> {
                val a = numArg(fname, args[0])
                val b = numArg(fname, args[1])
                if (a != kotlin.math.floor(a) || b != kotlin.math.floor(b))
                    throw NcodeError("в `случайно` границы целые")
                if (kotlin.math.abs(a) > 1e9 || kotlin.math.abs(b) > 1e9)
                    throw NcodeError("в `случайно` границы поменьше")
                val from = a.toInt()
                val to = b.toInt()
                if (from > to) throw NcodeError("в `случайно` первое число меньше второго")
                return kotlin.random.Random.nextInt(from, to + 1).toString()
            }
            "корень" -> {
                val x = numArg(fname, args[0])
                if (x < 0) throw NcodeError("корень из отрицательного — нельзя")
                return fmtNum(kotlin.math.sqrt(x))
            }
            "модуль" -> {
                return fmtNum(kotlin.math.abs(numArg(fname, args[0])))
            }
            "округлить" -> {
                return fmtNum(kotlin.math.floor(numArg(fname, args[0]) + 0.5))
            }
            "степень" -> {
                return fmtNum(Math.pow(numArg(fname, args[0]), numArg(fname, args[1])))
            }
            "минимум" -> {
                val a = numArg(fname, args[0])
                val c = numArg(fname, args[1])
                return fmtNum(if (a <= c) a else c)
            }
            "максимум" -> {
                val a = numArg(fname, args[0])
                val c = numArg(fname, args[1])
                return fmtNum(if (a >= c) a else c)
            }
            "длина" -> {
                return resolveToken(args[0]).length.toString()
            }
            else -> throw NcodeError("не знаю формулу `$fname`")
        }
    }
}

class NcodeError(message: String) : Exception(message)

private fun enableUtf8Console() {
    if (System.console() == null) return
    if (!System.getProperty("os.name", "").lowercase().startsWith("windows")) return
    try {
        ProcessBuilder("cmd", "/c", "chcp 65001 >nul 2>&1").inheritIO().start().waitFor()
    } catch (e: Exception) {
    }
}

private val HELP = """
    Ncode 1.2 — русский мини-язык (.ncode, UTF-8)
    Использование:
      Ncode программа.ncode   — выполнить файл
      Ncode -help             — эта справка
    Команды (регистр не важен, одна строка — одна команда):
      задать <имя> <значение>              — создать НОВУЮ переменную
        задать какашка истина
        задать какашка "иван"              — взять значение из переменной иван
      изменить|поменять <имя> <значение>   — поменять СУЩЕСТВУЮЩУЮ переменную
        изменить какашка ложь
      напечатать|печатать|вывести <что>    — вывести текст, число или "переменную"
        напечатать хеллоу ворлд
        напечатать "иван" .. пробел .. "нептун"
      если У то К (иначеесли У то К)* (иначе К)? конец
        если "возраст" больше или равно 18 то вывести взрослый иначе вывести маленький конец
      ждать <число> <единица>              — пауза: секунду/секунды/секунд,
                                             минуту/минуты/минут, час/часа/часов
        ждать 1 секунду
        ждать 5 секунд
      спросить <подсказка> сохранить в <имя> — строка с клавиатуры в переменную
        спросить Как тебя зовут сохранить в имя
        (формы: сохранить в, сохранить, сохр в, сохр; коротко: спросить имя)
      повтори|повторить|повторять <число> раз — цикл, тело до `конец`
        повтори 3 раза
        напечатать привет
        конец
        (счёт: раз, раза, разов; можно из переменной: повтори "мало" раз)
      как только <условие> то — событие: ждёт правды, выполняет тело раз
        как только 2 плюс 2 равно 4 то вывести сработало конец
      вещать всем <сообщение> — событие всем «когда будет получено»
        вещать всем какашка
      Когда будет получено <сообщение> — обработчик (конец не пишем)
        когда будет получено какашка
    Знаки — то же словами: + плюс, - минус, * умножить, / разделить, % остаток,
      = и == равно, != неравно, > больше, < меньше, >= <=, && и, || или
    Формулы (везде, где значение): случайно 1 5, корень 9, модуль -5,
      округлить 3.7, степень 2 10, минимум 3 7, максимум 3 7, длина "слово"
    Выражения: .. > умножить/разделить/остаток > плюс/минус > сравнение > и > или
      сравнения: равно/равняется, неравно/неравняется, больше, меньше,
                 больше или равно/равняется, меньше или равно/равняется
      правда: истина/да/правда; ложь: ложь/нет/неправда
    Примеры: test.ncode, test2.ncode. Дока: NCODE_v0.1.md
""".trimIndent()

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.out)), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.err)), true, "UTF-8"))
    enableUtf8Console()
    if (args.isEmpty() || args[0].lowercase() in
        setOf("-help", "--help", "-h", "/?", "помощь", "справка")
    ) {
        println(HELP.trimIndent())
        return
    }
    val file = java.io.File(args[0])
    if (!file.exists()) {
        System.err.println("Нет файла: ${args[0]}")
        kotlin.system.exitProcess(2)
    }
    val lines = file.readLines(Charsets.UTF_8)
    val interp = NcodeInterpreter()
    try {
        interp.extractHandlers(lines)
    } catch (e: NcodeError) {
        System.err.println("Ошибка: " + e.message)
        kotlin.system.exitProcess(1)
    }
    val code = interp.runLines(lines)
    if (code != 0) kotlin.system.exitProcess(code)
}
