class NcodeInterpreter {
    private val vars = mutableMapOf<String, String>()
    private val lists = mutableMapOf<String, MutableList<String>>()
    private var execFailed = false
    var dryRun = false
    var checkLabel = ""
    private var warnedCycle = false
    private var warnedNoWindow = false

    private fun reportError(lineNo: Int, msg: String?) {
        val text = msg ?: "ошибка"
        if (dryRun) System.err.println(checkLabel + ":" + lineNo + ": " + text)
        else System.err.println("Ошибка в строке " + lineNo + ": " + text)
    }

    private fun warnCycle() {
        if (warnedCycle) return
        warnedCycle = true
        System.err.println("Варнинг: зацикливание есть")
    }
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
        "случайно", "корень", "модуль", "округлить", "степень", "минимум", "максимум", "длина",
        "список", "списка", "списке", "списку", "списком", "взять", "есть",
        "найти", "заменить",
        "данные", "сумма", "среднее",
        "пока", "покуда", "всегда", "повторяй", "стоп", "дальше",
        "синус", "косинус", "время"
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

    fun runLines(lines: List<String>, base: Int = 1, skips: List<IntRange> = emptyList()): Int {
        var hadError = false
        var i = 0
        while (i < lines.size) {
            if (closeRequested) throw SceneStop()
            val skip = skips.firstOrNull { i in it }
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
                } else if (startsKw(line, "пока", "покуда")) {
                    i = runPoka(lines, i, base)
                } else if (startsKw(line, "повторяй", "всегда")) {
                    i = runVsegda(lines, i, base)
                } else {
                    if (startsKw(line, "конец"))
                        throw NcodeError("`конец` без открытого блока")
                    runLine(line)
                    i++
                }
            } catch (e: NcodeError) {
                if (dryRun) System.err.println(checkLabel + ":" + (base + i) + ": " + e.message)
                else System.err.println("Ошибка в строке ${base + i}: ${e.message}  >> $rawLine")
                hadError = true
                i = if (startsKw(line, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(line)) {
                    try { collectIf(lines, i).second } catch (e2: NcodeError) { lines.size }
                } else i + 1
            }
            checkEvents()
        }
        return if (hadError || execFailed) 1 else 0
    }

    private data class Handler(val msgExpr: String, val headerLine: Int, val lines: List<String>, val base: Int)

    private val handlers = mutableListOf<Handler>()
    private data class KeyHandler(val key: String, val lines: List<String>, val base: Int)

    private data class KeyEv(val cands: Set<String>)
    private data class MouseEv(val x: Double, val y: Double)

    private data class MouseHandler(val target: String?, val lines: List<String>, val base: Int)

    private val keyHandlers = mutableListOf<KeyHandler>()
    private val mouseHandlers = mutableListOf<MouseHandler>()
    private val keyQueue = mutableListOf<KeyEv>()
    private val mouseQueue = mutableListOf<MouseEv>()

    private val keyAliases = mapOf(
        "пробел" to "space",
        "ввод" to "enter",
        "esc" to "escape",
        "эскейп" to "escape",
        "таб" to "tab",
        "табуляция" to "tab",
        "шифт" to "shift",
        "контрол" to "control",
        "контрл" to "control",
        "альт" to "alt",
        "стереть" to "back_space",
        "влево" to "left",
        "вправо" to "right",
        "вверх" to "up",
        "вниз" to "down"
    )

    private val ruToEnKeys = mapOf(
        "ф" to "a", "и" to "b", "с" to "c", "в" to "d", "у" to "e", "а" to "f",
        "п" to "g", "р" to "h", "ш" to "i", "о" to "j", "л" to "k", "д" to "l",
        "ь" to "m", "т" to "n", "щ" to "o", "з" to "p", "й" to "q", "к" to "r",
        "ы" to "s", "е" to "t", "г" to "u", "м" to "v", "ц" to "w", "ч" to "x",
        "н" to "y", "я" to "z", "х" to "open_bracket", "ъ" to "close_bracket",
        "ж" to "semicolon", "э" to "quote", "б" to "comma", "ю" to "period"
    )

    private fun normKey(raw: String): String {
        val t = raw.trim().lowercase(java.util.Locale.ROOT)
        if (t.length == 1) {
            val c = t[0]
            if (c in 'a'..'z' || c in '0'..'9') return t
            ruToEnKeys[t]?.let { return it }
            return t
        }
        keyAliases[t]?.let { return it }
        ruToEnKeys[t]?.let { return it }
        return t.replace(" ", "_")
    }

    private fun keyMatches(want: String, ev: KeyEv): Boolean {
        return want in ev.cands
    }

    private fun keyHeader(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size != 4) return null
        if (parts[0].lowercase(java.util.Locale.ROOT) != "когда") return null
        if (parts[1].lowercase(java.util.Locale.ROOT) != "нажата") return null
        if (parts[2].lowercase(java.util.Locale.ROOT) != "клавиша") return null
        return parts[3]
    }

    private fun mouseTarget(t: String): String? {
        val low = t.trim().lowercase(java.util.Locale.ROOT)
        if (low == "при нажатии" || low == "при клике") return null
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size != 4) throw NcodeError("нужно: при нажатии на <имя>")
        return parts[3].lowercase(java.util.Locale.ROOT)
    }

    private fun isMouseHeader(t: String): Boolean {
        val low = t.trim().lowercase(java.util.Locale.ROOT)
        if (low == "при нажатии" || low == "при клике") return true
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size < 3) return false
        if (parts[0].lowercase(java.util.Locale.ROOT) != "при") return false
        val second = parts[1].lowercase(java.util.Locale.ROOT)
        if (second != "нажатии" && second != "клике") return false
        return parts[2].lowercase(java.util.Locale.ROOT) == "на"
    }

    private fun mouseHeader(t: String): Boolean {
        return isMouseHeader(t)
    }
    private val scriptStack = mutableListOf<String>()
    private var lastData = ""

    fun resetScripts(canon: String) {
        scriptStack.clear()
        scriptStack.add(canon)
    }
    private var broadcastDepth = 0

    private fun isHeader(t: String): Boolean {
        return handlerMsg(t) != null || keyHeader(t) != null || mouseHeader(t)
    }

    private fun dropTailEnd(body: MutableList<String>) {
        var k = body.size - 1
        while (k >= 0) {
            val lt = body[k].trim()
            if (lt.isEmpty() || lt.startsWith("#") || lt.startsWith("//")) { k--; continue }
            break
        }
        if (k >= 0 && body[k].trim().lowercase(java.util.Locale.ROOT) == "конец") body.removeAt(k)
    }

    fun extractHandlers(lines: List<String>): List<IntRange> {
        val skips = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) { i++; continue }
            val key = keyHeader(t)
            if (key != null) {
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(lines[i].trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                keyHandlers.add(KeyHandler(normKey(key), body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            if (mouseHeader(t)) {
                val start = i
                val target = try {
                    mouseTarget(t)
                } catch (e: NcodeError) {
                    throw NcodeError("строка ${i + 1}: " + (e.message ?: "ошибка"))
                }
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(lines[i].trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                mouseHandlers.add(MouseHandler(target, body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            val msg = handlerMsg(t)
            if (msg == null) { i++; continue }
            if (msg.isEmpty()) throw NcodeError("строка ${i + 1}: после `когда будет получено` нужно сообщение")
            val start = i
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && !isHeader(lines[i].trim())) {
                body.add(lines[i])
                i++
            }
            dropTailEnd(body)
            handlers.add(Handler(msg, start + 1, body, start + 2))
            skips.add(start..i - 1)
        }
        return skips
    }

    fun loadHandlers(lines: List<String>): List<IntRange> {
        handlers.clear()
        keyHandlers.clear()
        mouseHandlers.clear()
        kakListeners.clear()
        return extractHandlers(lines)
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
        val ws = wordsOutsideQuotes(msg)
        var cut = -1
        for (k in ws.indices) {
            if (ws[k].text.lowercase(java.util.Locale.ROOT) == "данные") cut = k
        }
        val msgText: String
        if (cut < 0) {
            msgText = msg
            lastData = ""
        } else {
            msgText = msg.substring(0, ws[cut].start).trim()
            if (msgText.isEmpty()) throw NcodeError("нужно: вещать <сообщение> [данные <значение>]")
            lastData = evalExpression(msg.substring(ws[cut].end).trim())
        }
        val value = evalExpression(msgText)
        if (broadcastDepth >= 100) {
            warnCycle()
            return
        }
        broadcastDepth++
        try {
            for (h in handlers) {
                val want = try {
                    evalExpression(h.msgExpr)
                } catch (e: NcodeError) {
                    reportError(h.headerLine, e.message)
                    execFailed = true
                    continue
                }
                if (want == value && !dryRun) {
                    if (runLines(h.lines, h.base) != 0) execFailed = true
                }
            }
        } finally {
            broadcastDepth--
        }
    }

    private fun parseZapustitPath(line: String): String {
        val low = line.lowercase()
        val kw = when {
            low == "запустить" || low.startsWith("запустить ") || low.startsWith("запустить\t") -> "запустить"
            low == "выполнить" || low.startsWith("выполнить ") || low.startsWith("выполнить\t") -> "выполнить"
            else -> throw NcodeError("неизвестная команда")
        }
        var rest = keywordTail(line, kw)
        if (startsKw(rest, "скрипт")) rest = rest.trim().substring(6).trim()
        if (rest.isEmpty()) throw NcodeError("нужно: запустить скрипт <путь>, пример: запустить скрипт images/игра.ncode")
        return rest
    }

    private fun runZapustit(line: String) {
        val rest = parseZapustitPath(line)
        if (dryRun) resolveScriptFile(rest, currentScriptDir())
        else runScriptFile(rest)
    }

    private fun resolveScriptFile(rawPath: String, fromDir: java.io.File?): java.io.File {
        val given = java.io.File(rawPath)
        val file = when {
            given.isAbsolute -> given
            fromDir != null && java.io.File(fromDir, rawPath).exists() -> java.io.File(fromDir, rawPath)
            java.io.File(rawPath).exists() -> java.io.File(rawPath)
            fromDir != null -> java.io.File(fromDir, rawPath)
            else -> given
        }
        if (!file.isFile) throw NcodeError("нет файла `$rawPath`")
        return file
    }

    private fun currentScriptDir(): java.io.File? {
        return scriptStack.lastOrNull()?.let { java.io.File(it).parentFile }
    }

    private fun runScriptFile(rawPath: String) {
        val file = resolveScriptFile(rawPath, currentScriptDir())
        if (!file.isFile) throw NcodeError("нет файла `$rawPath`")
        val canon = try {
            file.canonicalPath
        } catch (e: Exception) {
            throw NcodeError("плохой путь `$rawPath`")
        }
        if (canon in scriptStack || scriptStack.size >= 50) {
            warnCycle()
            return
        }
        val sub = try {
            readNcodeLines(file)
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$rawPath`")
        }
        val skips = try {
            extractHandlers(sub)
        } catch (e: NcodeError) {
            throw NcodeError(e.message + " (в скрипте " + rawPath + ")")
        }
        scriptStack.add(canon)
        try {
            val f = gfxFrame
            if (!dryRun && f != null && f.isDisplayable) {
                synchronized(gfxLock) {
                    gfxObjs.clear()
                }
                gfxPanel?.repaint()
            }
            if (runLines(sub, 1, skips) != 0) {
                System.err.println("в скрипте $rawPath")
                execFailed = true
            }
        } finally {
            scriptStack.removeAt(scriptStack.size - 1)
        }
    }

    private fun veshatRawMsg(line: String): String? {
        return try {
            var rest = keywordTail(line, "вещать")
            if (startsKw(rest, "всем")) rest = rest.trim().substring(4).trim()
            if (rest.isEmpty()) null else rest
        } catch (e: NcodeError) {
            null
        }
    }

    fun warnStaticCycles(entry: java.io.File, lines: List<String>) {
        for (h in handlers) {
            if (warnedCycle) return
            val want = h.msgExpr.trim().lowercase(java.util.Locale.ROOT)
            if (want.isEmpty() || !nameRegex.matches(want)) continue
            var depth = 0
            for ((idx, raw) in h.lines.withIndex()) {
                val t = raw.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
                if ((startsKw(t, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(t)) && matchEnd(t) < 0) {
                    depth++
                    continue
                }
                if (t.lowercase(java.util.Locale.ROOT) == "конец") {
                    if (depth > 0) depth--
                    continue
                }
                if (depth == 0 && startsKw(t, "вещать")) {
                    val m = veshatRawMsg(t)
                    if (m != null && m.trim().lowercase(java.util.Locale.ROOT) == want) {
                        System.err.println(relLabel(entry.canonicalPath) + ":" + (h.base + idx) + ": Варнинг: зацикливание есть")
                        warnedCycle = true
                        return
                    }
                }
            }
        }
        if (!warnedCycle) dfsCycle(entry, lines, mutableListOf(), mutableSetOf())
    }

    private fun dfsCycle(file: java.io.File, fromLines: List<String>, stack: MutableList<String>, visited: MutableSet<String>): Boolean {
        if (stack.size >= 50) return false
        for ((idx, raw) in fromLines.withIndex()) {
            val t = raw.trim()
            if (!startsKw(t, "запустить", "выполнить")) continue
            val target = try {
                parseZapustitPath(t)
            } catch (e: NcodeError) {
                continue
            }
            val resolved = try {
                resolveScriptFile(target, file.parentFile)
            } catch (e: NcodeError) {
                continue
            }
            val canon = try {
                resolved.canonicalPath
            } catch (e: Exception) {
                continue
            }
            if (canon in stack) {
                System.err.println(relLabel(file.canonicalPath) + ":" + (idx + 1) + ": Варнинг: зацикливание есть")
                warnedCycle = true
                return true
            }
            if (canon in visited) continue
            visited.add(canon)
            val sub = try {
                readNcodeLines(resolved)
            } catch (e: Exception) {
                continue
            }
            stack.add(canon)
            if (dfsCycle(resolved, sub, stack, visited)) return true
            stack.removeAt(stack.size - 1)
        }
        return false
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

    private enum class Kw { ЕСЛИ, ТО, ИНАЧЕЕСЛИ, ИНАЧЕ, КОНЕЦ, ПОВТОРИ, КАКТОЛЬКО, ПОКА, ВСЕГДА }
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
                    "повторяй", "всегда" -> Kw.ВСЕГДА
                    "пока", "покуда" -> Kw.ПОКА
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

    private fun firstWordEnd(t: String): Int {
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        return j
    }

    private fun splitHeaderTo(first: String, contentStart: Int): Pair<String, String?> {
        var depth = 0
        for (h in findKw(first)) {
            if (h.end <= contentStart) continue
            when (h.kw) {
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.КАКТОЛЬКО, Kw.ПОКА, Kw.ВСЕГДА -> depth++
                Kw.ТО -> if (depth == 0) return first.substring(contentStart, h.start).trim() to first.substring(h.end).trim()
                else -> {}
            }
        }
        return "" to null
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
        val (_, trail) = splitHeaderTo(first, firstWordEnd(first))
        if (trail.isNullOrEmpty()) return parseBlock(lines, start, base)
        parseIf(first, base + start)
        return start + 1
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
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(t)) {
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
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) cur.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            cur.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для `если`")
        for (b in branches) {
            val condTrue = try {
                toBool(evalExpression(b.cond))
            } catch (e: NcodeError) {
                reportError(b.condLine, e.message)
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
                    val failed = runLines(item.lines, item.firstLine) != 0 || execFailed
                    execFailed = before || failed
                    !failed
                }
            }
        } catch (e: NcodeError) {
            val ln = when (item) {
                is BlockItem.Cmd -> item.lineNo
                is BlockItem.Sub -> item.firstLine
            }
            reportError(ln, e.message)
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
            if (dryRun) {
                try {
                    execCmd(body, base + start)
                } catch (e: BreakSignal) {
                } catch (e: ContinueSignal) {
                }
            }
            else repeat(n) {
                try {
                    execCmd(body, base + start)
                } catch (e: BreakSignal) {
                    return start + 1
                } catch (e: ContinueSignal) {
                }
                if (closeRequested) throw SceneStop()
                checkEvents()
            }
            return start + 1
        }
        val (countText, trail, _) = splitRepeatUnit(first, firstWordEnd(first))
        if (trail.isEmpty()) return parseRepeatBlock(lines, start, base)
        val n = evalRepeatCount(countText)
        if (dryRun) {
            try {
                execCmd(trail, base + start)
            } catch (e: BreakSignal) {
            } catch (e: ContinueSignal) {
            }
        }
        else repeat(n) {
            try {
                execCmd(trail, base + start)
            } catch (e: BreakSignal) {
                return start + 1
            } catch (e: ContinueSignal) {
            }
            if (closeRequested) throw SceneStop()
            checkEvents()
        }
        return start + 1
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
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.КАКТОЛЬКО, Kw.ПОКА, Kw.ВСЕГДА -> depth++
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
        val (cond, trail) = splitHeaderTo(first, kakOpenerEnd(first))
        if (trail.isNullOrEmpty()) return parseKakBlock(lines, start, base)
        if (cond.isEmpty()) throw NcodeError("пустое условие")
        pollAndRun(cond, listOf(BlockItem.Cmd(base + start, trail)), base + start)
        return start + 1
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
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.КАКТОЛЬКО, Kw.ПОКА, Kw.ВСЕГДА -> depth++
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
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.КАКТОЛЬКО, Kw.ПОКА, Kw.ВСЕГДА -> depth++
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
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(t)) {
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
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) body.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            body.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для события")
        pollAndRun(cond, body, base + start)
        return i
    }

    fun checkHandlers(): Int {
        var code = 0
        for (h in handlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        return code
    }

    fun checkKeyHandlers(): Int {
        var code = 0
        for (h in keyHandlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        for (h in mouseHandlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        return code
    }

    private data class KakListener(var last: Boolean?, val cond: String, val body: List<BlockItem>, val lineNo: Int)

    private val kakListeners = mutableListOf<KakListener>()

    fun checkEvents() {
        if (dryRun) return
        if (kakListeners.isNotEmpty()) {
            var fires = 0
            var fired = true
            while (fired) {
                fired = false
                for (lis in kakListeners.toList()) {
                    val now = try {
                        toBool(evalExpression(lis.cond))
                    } catch (e: NcodeError) {
                        reportError(lis.lineNo, e.message)
                        execFailed = true
                        kakListeners.remove(lis)
                        continue
                    }
                    val edge = lis.last == null && now || lis.last == false && now
                    lis.last = now
                    if (edge) {
                        fires++
                        if (fires > 10000) {
                            warnCycle()
                            return
                        }
                        for (item in lis.body) {
                            if (!execItem(item)) break
                        }
                        fired = true
                    }
                }
            }
        }
        val keys = synchronized(keyQueue) {
            val got = keyQueue.toList()
            keyQueue.clear()
            got
        }
        for (ev in keys) {
            for (h in keyHandlers.toList()) {
                if (keyMatches(h.key, ev)) {
                    if (runLines(h.lines, h.base) != 0) execFailed = true
                }
            }
        }
        val clicks = synchronized(mouseQueue) {
            val got = mouseQueue.toList()
            mouseQueue.clear()
            got
        }
        for (ev in clicks) {
            vars["мышьх"] = fmtNum(ev.x)
            vars["мышьу"] = fmtNum(ev.y)
            for (h in mouseHandlers.toList()) {
                if (h.target == null || clickHits(h.target, ev.x, ev.y)) {
                    if (runLines(h.lines, h.base) != 0) execFailed = true
                }
            }
        }
    }

    private fun clickHits(name: String, wx: Double, wy: Double): Boolean {
        val o = synchronized(gfxLock) {
            gfxObjs.find { it.name == name }?.copy()
        } ?: return false
        if (!o.visible) return false
        val dx = wx - o.x
        val dy = wy - o.y
        val rad = Math.toRadians(o.rot)
        val lx = dx * Math.cos(rad) + dy * Math.sin(rad)
        val ly = -dx * Math.sin(rad) - dy * Math.cos(rad)
        val hw: Double
        val hh: Double
        val img = o.costumes.getOrNull(o.costume)
        if (img != null) {
            val s = o.size / img.width.toDouble()
            hw = img.width * s / 2.0
            hh = img.height * s / 2.0
        } else {
            hw = o.size / 2.0
            hh = o.size / 2.0
        }
        return lx >= -hw && lx <= hw && ly >= -hh && ly <= hh
    }

    private fun pollAndRun(cond: String, body: List<BlockItem>, condLine: Int) {
        if (dryRun) {
            try {
                toBool(evalExpression(cond))
            } catch (e: NcodeError) {
                reportError(condLine, e.message)
                execFailed = true
                return
            }
            for (item in body) {
                if (!execItem(item)) break
            }
            return
        }
        val lis = KakListener(null, cond, body, condLine)
        val ix = kakListeners.indexOfFirst { it.cond == cond }
        if (ix >= 0) kakListeners[ix] = lis else kakListeners.add(lis)
        checkEvents()
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
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(t)) {
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
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) body.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            body.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для `повтори`")
        val n = evalRepeatCount(countText)
        if (dryRun) {
            for (item in body) {
                try {
                    execItem(item)
                } catch (e: BreakSignal) {
                } catch (e: ContinueSignal) {
                }
            }
            return i
        }
        repeat(n) {
            try {
                for (item in body) {
                    if (!execItem(item)) return i
                }
            } catch (e: BreakSignal) {
                return i
            } catch (e: ContinueSignal) {
            }
            if (closeRequested) throw SceneStop()
            checkEvents()
        }
        return i
    }

    private fun stripOuterEnd(text: String, contentStart: Int): String {
        var depth = 0
        var closer: KwHit? = null
        for (h in findKw(text)) {
            if (h.end <= contentStart) continue
            when (h.kw) {
                Kw.ЕСЛИ, Kw.ПОВТОРИ, Kw.ПОКА, Kw.ВСЕГДА, Kw.КАКТОЛЬКО -> depth++
                Kw.КОНЕЦ -> if (depth == 0) closer = h else depth--
                else -> {}
            }
        }
        val c = closer ?: throw NcodeError("нет `конец`")
        if (text.substring(c.end).trim().isNotEmpty())
            throw NcodeError("после `конец` ничего не должно быть")
        val body = text.substring(contentStart, c.start).trim()
        if (body.isEmpty()) throw NcodeError("пустое тело")
        return body
    }

    private fun runPoka(lines: List<String>, start: Int, base: Int): Int {
        val first = lines[start].trim()
        if (matchEnd(first) >= 0) throw NcodeError("пока — только блоком: команда с новой строки, конец с новой строки")
        var j = 0
        while (j < first.length && (first[j].isLetterOrDigit() || first[j] == '_')) j++
        val cond = first.substring(j).trim()
        if (cond.isEmpty()) throw NcodeError("нужно: пока <условие>")
        val body = mutableListOf<BlockItem>()
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
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(t)) {
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
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) body.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            body.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для `пока`")
        if (dryRun) {
            toBool(evalExpression(cond))
            for (item in body) {
                try {
                    execItem(item)
                } catch (e: BreakSignal) {
                } catch (e: ContinueSignal) {
                }
            }
            return i
        }
        var iter = 0
        while (toBool(evalExpression(cond))) {
            iter++
            if (iter > 100000) throw NcodeError("пока крутится слишком долго (больше 100000)")
            try {
                for (item in body) {
                    if (!execItem(item)) return i
                }
            } catch (e: BreakSignal) {
                return i
            } catch (e: ContinueSignal) {
            }
            if (closeRequested) throw SceneStop()
            checkEvents()
        }
        return i
    }

    private fun runVsegda(lines: List<String>, start: Int, base: Int): Int {
        val first = lines[start].trim()
        val end = matchEnd(first)
        if (end >= 0) {
            if (first.substring(end).trim().isNotEmpty())
                throw NcodeError("после `конец` ничего не должно быть")
            val body = stripOuterEnd(first.substring(0, end), firstWordEnd(first))
            if (dryRun) {
                try {
                    execCmd(body, base + start)
                } catch (e: BreakSignal) {
                } catch (e: ContinueSignal) {
                }
                return start + 1
            }
            while (true) {
                try {
                    execCmd(body, base + start)
                } catch (e: BreakSignal) {
                    return start + 1
                } catch (e: ContinueSignal) {
                }
                if (closeRequested) throw SceneStop()
                checkEvents()
            }
        }
        var j = firstWordEnd(first)
        while (j < first.length && first[j].isWhitespace()) j++
        val trail = first.substring(j).trim()
        if (trail.isNotEmpty()) {
            if (findKw(trail).any { it.kw == Kw.КОНЕЦ })
                throw NcodeError("в многострочном повторе команда — с новой строки")
            return runVsegdaBlock(lines, start, base, mutableListOf(BlockItem.Cmd(base + start, trail)))
        }
        return runVsegdaBlock(lines, start, base, mutableListOf())
    }

    private fun runVsegdaBlock(lines: List<String>, start: Int, base: Int, body: MutableList<BlockItem>): Int {
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
            if (startsKw(t, "если", "эсли", "повтори", "повторить", "повторять", "пока", "покуда", "повторяй", "всегда") || startsKakTolko(t)) {
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
            val endHit = findKw(t).firstOrNull { it.kw == Kw.КОНЕЦ && it.start > 0 }
            if (endHit != null) {
                val before = t.substring(0, endHit.start).trim()
                if (t.substring(endHit.end).trim().isNotEmpty())
                    throw NcodeError("после `конец` ничего не должно быть")
                if (before.isNotEmpty()) body.add(BlockItem.Cmd(base + i, before))
                closed = true; i++; break
            }
            body.add(BlockItem.Cmd(base + i, t))
            i++
        }
        if (!closed) throw NcodeError("нет `конец` для повтора")
        if (dryRun) {
            for (item in body) {
                try {
                    execItem(item)
                } catch (e: BreakSignal) {
                } catch (e: ContinueSignal) {
                }
            }
            return i
        }
        while (true) {
            try {
                for (item in body) {
                    if (!execItem(item)) return i
                }
            } catch (e: BreakSignal) {
                return i
            } catch (e: ContinueSignal) {
            }
            if (closeRequested) throw SceneStop()
            checkEvents()
        }
    }

    private fun matchEnd(text: String): Int {
        val hits = findKw(text)
        if (hits.isEmpty() ||
            (hits[0].kw != Kw.ЕСЛИ && hits[0].kw != Kw.ПОВТОРИ && hits[0].kw != Kw.КАКТОЛЬКО && hits[0].kw != Kw.ПОКА && hits[0].kw != Kw.ВСЕГДА) ||
            hits[0].start != 0
        ) return -1
        var depth = 1
        for (h in hits.drop(1)) {
            when (h.kw) {
                Kw.ЕСЛИ -> depth++
                Kw.ПОВТОРИ -> depth++
                Kw.КАКТОЛЬКО -> depth++
                Kw.ПОКА -> depth++
                Kw.ВСЕГДА -> depth++
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
        fun finish(bound: Int) {
            when (state) {
                1 -> cmds.add(text.substring(cmdStart, bound))
                2 -> elseCmd = text.substring(cmdStart, bound)
                else -> throw NcodeError("после условия нужно `то`")
            }
            if (cmds.any { it.trim().isEmpty() })
                throw NcodeError("после `то` пусто")
            if (hasElse && elseCmd!!.trim().isEmpty())
                throw NcodeError("после `иначе` пусто")
            if (dryRun) {
                for (k in conds.indices) {
                    try {
                        toBool(evalExpression(conds[k]))
                    } catch (e: NcodeError) {
                        reportError(lineNo, e.message)
                        execFailed = true
                    }
                    try {
                        execCmd(cmds[k], lineNo)
                    } catch (e: NcodeError) {
                        reportError(lineNo, e.message)
                        execFailed = true
                    }
                }
                if (hasElse) {
                    try {
                        execCmd(elseCmd!!, lineNo)
                    } catch (e: NcodeError) {
                        reportError(lineNo, e.message)
                        execFailed = true
                    }
                }
                return
            }
            for (k in conds.indices) {
                if (toBool(evalExpression(conds[k]))) { execCmd(cmds[k], lineNo); return }
            }
            if (hasElse) execCmd(elseCmd!!, lineNo)
        }
        for (h in hits.drop(1)) {
            when (h.kw) {
                Kw.ЕСЛИ -> depth++
                Kw.ПОВТОРИ -> depth++
                Kw.КАКТОЛЬКО -> depth++
                Kw.ПОКА -> depth++
                Kw.ВСЕГДА -> depth++
                Kw.КОНЕЦ -> if (depth == 0) {
                    finish(h.start)
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
        finish(text.length)
    }

    private fun execCmd(cmd: String, lineNo: Int) {
        val t = cmd.trim()
        if (t.isEmpty()) throw NcodeError("пустая команда в ветке")
        if (startsKw(t, "если", "эсли")) parseIf(t, lineNo)
        else if (startsKw(t, "повтори", "повторить", "повторять")) runRepeat(listOf(t), 0, lineNo)
        else if (startsKakTolko(t)) runKakTolko(listOf(t), 0, lineNo)
        else runLine(t)
    }

    private fun listByName(name: String): MutableList<String> {
        return lists[name.lowercase(java.util.Locale.ROOT)] ?: throw NcodeError("нет списка `$name` — сначала `создать список`")
    }

    private fun listIndex(list: MutableList<String>, numText: String): Int {
        val v = resolveToken(numText)
        if (!numberRegex.matches(v)) throw NcodeError("номер — целое число, а тут `$v`")
        val d = v.toDouble()
        if (d != kotlin.math.floor(d) || d < 1 || d > list.size) throw NcodeError("в списке " + list.size + " штук (номера 1.." + list.size + ")")
        return d.toInt() - 1
    }

    private fun runSozdatSpisok(line: String) {
        val name = keywordTail(line, "создать список")
        if (name.isEmpty()) throw NcodeError("нужно: создать список <имя>")
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        if (name.lowercase(java.util.Locale.ROOT) in reserved) throw NcodeError("`$name` — служебное слово, возьми другое имя")
        if (lists.containsKey(name.lowercase(java.util.Locale.ROOT))) throw NcodeError("список `$name` уже есть")
        lists[name.lowercase(java.util.Locale.ROOT)] = mutableListOf()
    }

    private fun runDobavit(line: String) {
        val rest = keywordTail(line, "добавить в список")
        if (rest.isEmpty()) throw NcodeError("нужно: добавить в список <имя> <значение>")
        val cut = rest.indexOfFirst { it.isWhitespace() }
        if (cut < 0) throw NcodeError("нужно: добавить в список <имя> <значение>")
        val value = rest.substring(cut).trim()
        if (value.isEmpty()) throw NcodeError("нужно: добавить в список <имя> <значение>")
        listByName(rest.substring(0, cut)).add(evalExpression(value))
    }

    private fun runUbrat(line: String) {
        val rest = keywordTail(line, "убрать из списка")
        if (rest.isEmpty()) throw NcodeError("нужно: убрать из списка <имя> <номер>")
        val parts = rest.split(Regex("\\s+"))
        if (parts.size != 2) throw NcodeError("нужно: убрать из списка <имя> <номер>")
        val list = listByName(parts[0])
        list.removeAt(listIndex(list, parts[1]))
    }

    private fun runOchistitSpisok(line: String) {
        val name = keywordTail(line, "очистить список")
        if (name.isEmpty() || !nameRegex.matches(name)) throw NcodeError("нужно: очистить список <имя>")
        listByName(name).clear()
    }

    private fun runZapisat(line: String, append: Boolean) {
        val kw = if (append) "добавить в файл" else "записать в файл"
        val rest = keywordTail(line, kw)
        if (rest.isEmpty()) throw NcodeError("нужно: " + kw + " <путь> <текст>")
        val cut = rest.indexOfFirst { it.isWhitespace() }
        if (cut < 0) throw NcodeError("нужно: " + kw + " <путь> <текст>")
        val path = rest.substring(0, cut)
        val text = rest.substring(cut).trim()
        if (text.isEmpty()) throw NcodeError("нужно: " + kw + " <путь> <текст>")
        val value = evalExpression(text)
        if (dryRun) {
            dryWritten.add(path)
            return
        }
        val file = java.io.File(path)
        try {
            val parent = file.parentFile
            if (parent != null) parent.mkdirs()
            if (append) file.appendText(value + "\n", Charsets.UTF_8)
            else file.writeText(value + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            throw NcodeError("не могу записать `$path`")
        }
    }

    private val dryWritten = mutableSetOf<String>()

    private fun readFileText(path: String): String {
        val file = java.io.File(path)
        if (!file.isFile) throw NcodeError("нет файла `$path`")
        return try {
            readNcodeLines(file).joinToString("\n")
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$path`")
        }
    }

    private fun readFileTextOrDry(path: String): String {
        if (!dryRun || java.io.File(path).isFile) return readFileText(path)
        if (dryWritten.contains(path)) return "1"
        throw NcodeError("нет файла `$path`")
    }

    private data class GObj(
        val name: String,
        var x: Double,
        var y: Double,
        var size: Double,
        var rot: Double,
        var r: Int,
        var g: Int,
        var b: Int,
        var alpha: Int,
        var costumes: MutableList<java.awt.image.BufferedImage>,
        var costume: Int,
        var visible: Boolean,
        var penDown: Boolean = false,
        var penR: Int = 0,
        var penG: Int = 0,
        var penB: Int = 0,
        var penSize: Int = 1
    )

    private data class PenLine(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val r: Int,
        val g: Int,
        val b: Int,
        val size: Int
    )

    private data class RisSpec(
        val name: String?,
        val x: Double,
        val y: Double,
        val size: Double,
        val rot: Double,
        val r: Int,
        val g: Int,
        val b: Int,
        val alpha: Int,
        val path: String?
    )

    private var gfxFrame: javax.swing.JFrame? = null
    private var gfxEverOpened = false
    private var gfxPanel: javax.swing.JPanel? = null
    private var gfxW = 800
    private var gfxH = 600
    private val gfxObjs = mutableListOf<GObj>()
    private val penLines = mutableListOf<PenLine>()
    private val gfxLock = Object()
    private val gfxImages = mutableMapOf<String, java.awt.image.BufferedImage>()
    private var lastDrawn: String? = null
    @Volatile private var closeRequested = false

    private fun requireWindow(): javax.swing.JFrame {
        val f = gfxFrame
        if (f == null || !f.isDisplayable) throw NcodeError("сначала создать окно")
        return f
    }

    fun isWindowOpen(): Boolean {
        val f = gfxFrame
        return f != null && f.isDisplayable
    }

    fun warnNoWindowForInput() {
        if (gfxEverOpened || warnedNoWindow) return
        if (keyHandlers.isEmpty() && mouseHandlers.isEmpty()) return
        warnedNoWindow = true
        System.err.println("Варнинг: обработчики клавиш/мыши без окна не сработают — сначала создать окно")
    }

    private fun paintScene(g: java.awt.Graphics2D) {
        g.color = java.awt.Color.BLACK
        g.fillRect(0, 0, gfxW, gfxH)
        val trails: List<PenLine> = synchronized(gfxLock) { penLines.toList() }
        for (t in trails) {
            g.color = java.awt.Color(t.r, t.g, t.b)
            g.stroke = java.awt.BasicStroke(t.size.toFloat())
            g.drawLine(
                (gfxW / 2.0 + t.x1).toInt(),
                (gfxH / 2.0 - t.y1).toInt(),
                (gfxW / 2.0 + t.x2).toInt(),
                (gfxH / 2.0 - t.y2).toInt()
            )
        }
        g.stroke = java.awt.BasicStroke(1f)
        val snap: List<GObj> = synchronized(gfxLock) { gfxObjs.map { it.copy() } }
        for (o in snap) {
            if (!o.visible) continue
            val cx = gfxW / 2.0 + o.x
            val cy = gfxH / 2.0 - o.y
            val savedT = g.transform
            val savedC = g.composite
            g.translate(cx, cy)
            g.rotate(Math.toRadians(-o.rot))
            g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (o.alpha / 100f).coerceIn(0f, 1f))
            val img = o.costumes.getOrNull(o.costume)
            if (img != null) {
                val s = o.size / img.width.toDouble()
                val w = (img.width * s).toInt()
                val h = (img.height * s).toInt()
                g.drawImage(img, -w / 2, -h / 2, w, h, null)
            } else {
                g.color = java.awt.Color(o.r, o.g, o.b)
                val s = o.size.toInt()
                g.fillRect(-s / 2, -s / 2, s, s)
            }
            g.transform = savedT
            g.composite = savedC
        }
    }

    private fun evalPositiveInt(text: String, what: String): Int {
        val v = evalExpression(text)
        if (!numberRegex.matches(v)) throw NcodeError(what + " — целое число, а тут `" + v + "`")
        val d = v.toDouble()
        if (d != kotlin.math.floor(d) || d < 1) throw NcodeError(what + " — целое больше нуля")
        return d.toInt()
    }

    private fun runSozdatOkno(line: String, keyword: String) {
        val rest = keywordTail(line, keyword).trim()
        var w = 800
        var h = 600
        var title = "mygame"
        if (rest.isNotEmpty()) {
            val parts = rest.split(Regex("\\s+"))
            if (parts.size < 2) throw NcodeError("нужно: создать окно [ширина высота название]")
            w = evalPositiveInt(parts[0], "ширина")
            h = evalPositiveInt(parts[1], "высота")
            if (parts.size > 2) title = evalExpression(parts.drop(2).joinToString(" "))
        }
        if (dryRun) return
        val alive = gfxFrame
        if (alive != null && alive.isDisplayable) throw NcodeError("окно уже есть — сначала закрыть окно")
        gfxW = w
        gfxH = h
        try {
            javax.swing.SwingUtilities.invokeAndWait {
                val f = javax.swing.JFrame(title)
                f.defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
                f.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(e: java.awt.event.WindowEvent?) {
                        closeRequested = true
                        f.dispose()
                    }
                })
                val panel = object : javax.swing.JPanel() {
                    override fun paintComponent(gr: java.awt.Graphics) {
                        super.paintComponent(gr)
                        paintScene(gr as java.awt.Graphics2D)
                    }
                }
                panel.preferredSize = java.awt.Dimension(w, h)
                panel.isFocusable = true
                panel.setFocusTraversalKeysEnabled(false)
                f.contentPane.add(panel)
                val keyAdapt = object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        val code = java.awt.event.KeyEvent.getKeyText(e.keyCode).lowercase(java.util.Locale.ROOT).replace(" ", "_")
                        val cands = mutableSetOf(code)
                        val ch = e.keyChar
                        if (ch != java.awt.event.KeyEvent.CHAR_UNDEFINED) {
                            cands.add(ch.toString().lowercase(java.util.Locale.ROOT))
                            ruToEnKeys[ch.toString().lowercase(java.util.Locale.ROOT)]?.let { cands.add(it) }
                        }
                        synchronized(keyQueue) {
                            keyQueue.add(KeyEv(cands))
                        }
                    }
                    override fun keyTyped(e: java.awt.event.KeyEvent) {
                        val c = e.keyChar
                        if (c == java.awt.event.KeyEvent.CHAR_UNDEFINED || c.isLetterOrDigit() || c == ' ') return
                        synchronized(keyQueue) {
                            keyQueue.add(KeyEv(setOf(c.toString().lowercase(java.util.Locale.ROOT))))
                        }
                    }
                }
                f.addKeyListener(keyAdapt)
                panel.addKeyListener(keyAdapt)
                panel.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent) {
                        panel.requestFocusInWindow()
                        val wx = e.x - panel.width / 2.0
                        val wy = panel.height / 2.0 - e.y
                        synchronized(mouseQueue) {
                            mouseQueue.add(MouseEv(wx, wy))
                        }
                    }
                })
                f.pack()
                f.setLocationRelativeTo(null)
                f.isVisible = true
                f.toFront()
                panel.requestFocusInWindow()
                gfxFrame = f
                gfxPanel = panel
                gfxEverOpened = true
            }
        } catch (e: Exception) {
            throw NcodeError("окно не открылось")
        }
    }

    private fun runZakrytOkno(line: String) {
        if (keywordTail(line, "закрыть окно").trim().isNotEmpty()) throw NcodeError("нужно: закрыть окно")
        if (dryRun) return
        val f = gfxFrame
        if (f == null || !f.isDisplayable) throw NcodeError("окна нет")
        f.dispose()
        kotlin.system.exitProcess(0)
    }

    private fun isNumTok(t: String): Boolean {
        return numberRegex.matches(t) || (t.length >= 2 && t.startsWith("\"") && t.endsWith("\""))
    }

    private fun parseRisovat(line: String): RisSpec {
        val trimmed = line.trim()
        var j = 0
        while (j < trimmed.length && (trimmed[j].isLetterOrDigit() || trimmed[j] == '_')) j++
        val rest = trimmed.substring(j).trim()
        val toks = if (rest.isEmpty()) emptyList() else rest.split(Regex("\\s+"))
        if (toks.size == 3 && !isNumTok(toks[0])) {
            val x = evalArithOperand(toks[1])
            val y = evalArithOperand(toks[2])
            return RisSpec(toks[0].lowercase(java.util.Locale.ROOT), x, y, 100.0, 0.0, 0, 0, 0, 100, null)
        }
        if (toks.size == 2 && isNumTok(toks[0]) && isNumTok(toks[1])) {
            val x = evalArithOperand(toks[0])
            val y = evalArithOperand(toks[1])
            return RisSpec("", x, y, 100.0, 0.0, 0, 0, 0, 100, null)
        }
        throw NcodeError("нужно: нарисовать <имя> x y")
    }

    private fun loadImage(path: String): java.awt.image.BufferedImage {
        val file = resolveScriptFile(path, currentScriptDir())
        val canon = try {
            file.canonicalPath
        } catch (e: Exception) {
            throw NcodeError("плохой путь `$path`")
        }
        gfxImages[canon]?.let { return it }
        val img = try {
            javax.imageio.ImageIO.read(file)
        } catch (e: Exception) {
            null
        } ?: throw NcodeError("не картинка `$path`")
        gfxImages[canon] = img
        return img
    }

    private fun runRisovat(line: String) {
        val spec = parseRisovat(line)
        if (dryRun) return
        requireWindow()
        val key = spec.name ?: ""
        synchronized(gfxLock) {
            val old = gfxObjs.find { it.name == key }
            if (old != null) {
                if (old.penDown && (old.x != spec.x || old.y != spec.y)) {
                    penLines.add(PenLine(old.x, old.y, spec.x, spec.y, old.penR, old.penG, old.penB, old.penSize))
                }
                old.x = spec.x
                old.y = spec.y
            } else {
                gfxObjs.add(GObj(key, spec.x, spec.y, 100.0, 0.0, 0, 0, 0, 100, mutableListOf(), 0, true))
            }
            lastDrawn = key
        }
        gfxPanel?.repaint()
    }

    private fun isObjectProp(line: String): Boolean {
        val toks = line.trim().split(Regex("\\s+"))
        if (toks.size < 2) return false
        val a = toks[0].lowercase(java.util.Locale.ROOT)
        if (a != "задать" && a != "присвоить" && a != "сделать" && a != "изменить" && a != "поменять") return false
        val p = toks[1].lowercase(java.util.Locale.ROOT)
        if (p != "прозрачность" && p != "размер" && p != "поворот" && p != "цвет" && p != "образ" && p != "костюм") return false
        for (x in toks) if (x.lowercase(java.util.Locale.ROOT) == "объекту") return true
        return false
    }

    private fun splitPropTail(tail: String): Pair<List<String>, List<String>> {
        val toks = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var idx = -1
        for (q in toks.indices) if (toks[q].lowercase(java.util.Locale.ROOT) == "объекту") idx = q
        if (idx < 0) throw NcodeError("нужно слово `объекту`")
        return toks.take(idx) to toks.drop(idx + 1)
    }

    private fun runObjectProp(line: String) {
        val t = line.trim()
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        val afterAct = t.substring(j).trim()
        var k = 0
        while (k < afterAct.length && (afterAct[k].isLetterOrDigit() || afterAct[k] == '_')) k++
        val prop = afterAct.substring(0, k).lowercase(java.util.Locale.ROOT)
        val tail = afterAct.substring(k).trim()
        if (prop == "образ" || prop == "костюм") {
            val toks = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (toks.size != 3 || toks[0].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать образ объекту <имя> <путь>")
            val name = toks[1].lowercase(java.util.Locale.ROOT)
            val path = toks[2]
            if (dryRun) {
                resolveScriptFile(path, currentScriptDir())
                return
            }
            requireWindow()
            val img = loadImage(path)
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.costumes.clear()
                o.costumes.add(img)
                o.costume = 0
            }
            gfxPanel?.repaint()
            return
        }
        val (before, after) = splitPropTail(tail)
        if (after.size != 1) throw NcodeError("нужно: задать $prop объекту <имя>")
        val name = after[0].lowercase(java.util.Locale.ROOT)
        if (prop == "цвет") {
            if (before.size != 3) throw NcodeError("нужно: задать цвет R G B объекту <имя>")
            val r = evalArithOperand(before[0])
            val g = evalArithOperand(before[1])
            val b = evalArithOperand(before[2])
            for ((c, n) in listOf(r to "красный", g to "зелёный", b to "синий")) {
                if (c != kotlin.math.floor(c) || c < 0 || c > 255) throw NcodeError(n + " — целое 0..255")
            }
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.r = r.toInt()
                o.g = g.toInt()
                o.b = b.toInt()
            }
            gfxPanel?.repaint()
            return
        }
        if (before.isEmpty()) throw NcodeError("нужно: задать $prop объекту <имя>")
        val v = evalExpression(before.joinToString(" "))
        if (prop == "прозрачность") {
            if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
            val d = v.toDouble()
            if (d != kotlin.math.floor(d) || d < 0 || d > 100) throw NcodeError("прозрачность — целое 0..100")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.alpha = d.toInt()
            }
            gfxPanel?.repaint()
            return
        }
        if (prop == "размер") {
            if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
            val d = v.toDouble()
            if (d <= 0) throw NcodeError("размер больше нуля")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.size = d
            }
            gfxPanel?.repaint()
            return
        }
        if (prop == "поворот") {
            if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.rot = ((v.toDouble() % 360) + 360) % 360
            }
            gfxPanel?.repaint()
            return
        }
        throw NcodeError("неизвестная команда")
    }

    private fun lastDrawnKey(): String {
        return lastDrawn ?: throw NcodeError("нечего показать — сначала нарисовать")
    }

    private fun runPokazat(line: String, keyword: String, show: Boolean) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isNotEmpty() && rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно одно имя")
        val name = if (rest.isEmpty()) lastDrawnKey() else rest.lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val live = gfxObjs.find { it.name == name }
            if (live != null) {
                live.visible = show
                gfxPanel?.repaint()
                return
            }
        }
        throw NcodeError("объект не нарисован")
    }

    private fun runOchistit(line: String) {
        if (line.trim() != "очистить") throw NcodeError("нужно: очистить")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            gfxObjs.clear()
        }
        gfxPanel?.repaint()
    }

    private fun splitObjEnd(rest: String): Pair<String, String?> {
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var idx = -1
        for (q in toks.indices) if (toks[q].lowercase(java.util.Locale.ROOT) == "объекту") idx = q
        if (idx < 0) return rest to null
        if (idx != toks.size - 2) throw NcodeError("после `объекту` нужно одно имя")
        return toks.take(idx).joinToString(" ") to toks[idx + 1].lowercase(java.util.Locale.ROOT)
    }

    private fun movingObjectKey(): String {
        return lastDrawn ?: throw NcodeError("нечего двигать — сначала нарисовать")
    }

    private fun runIdti(line: String) {
        var rest = keywordTail(line, "идти").trim()
        if (rest.isEmpty()) throw NcodeError("нужно: идти <число> шагов [объекту <имя>]")
        val split = splitObjEnd(rest)
        val obj = split.second
        rest = split.first
        if (rest.isEmpty()) throw NcodeError("нужно: идти <число> шагов [объекту <имя>]")
        val toks = rest.split(Regex("\\s+"))
        val last = toks.last().lowercase(java.util.Locale.ROOT)
        if (last == "шаг" || last == "шага" || last == "шагов") {
            rest = toks.dropLast(1).joinToString(" ").trim()
        }
        if (rest.isEmpty()) throw NcodeError("нужно: идти <число> шагов [объекту <имя>]")
        val v = evalExpression(rest)
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        if (dryRun) return
        requireWindow()
        val steps = v.toDouble()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            val rad = Math.toRadians(o.rot)
            val nx = o.x + steps * Math.cos(rad)
            val ny = o.y + steps * Math.sin(rad)
            if (o.penDown && (nx != o.x || ny != o.y)) {
                penLines.add(PenLine(o.x, o.y, nx, ny, o.penR, o.penG, o.penB, o.penSize))
            }
            o.x = nx
            o.y = ny
        }
        gfxPanel?.repaint()
    }

    private fun runPovernut(line: String) {
        val rest0 = keywordTail(line, "повернуть").trim()
        if (rest0.isEmpty()) throw NcodeError("нужно: повернуть налево/направо на <число> [объекту <имя>]")
        val split = splitObjEnd(rest0)
        val obj = split.second
        val rest = split.first
        if (rest.isEmpty()) throw NcodeError("нужно: повернуть налево/направо на <число> [объекту <имя>]")
        val toks = rest.split(Regex("\\s+"))
        val dir = toks[0].lowercase(java.util.Locale.ROOT)
        if (dir != "налево" && dir != "направо") throw NcodeError("нужно: повернуть налево/направо на <число>")
        var tail = toks.drop(1)
        if (tail.isNotEmpty() && tail[0].lowercase(java.util.Locale.ROOT) == "на") tail = tail.drop(1)
        if (tail.isNotEmpty()) {
            val u = tail.last().lowercase(java.util.Locale.ROOT)
            if (u == "градус" || u == "градуса" || u == "градусов") tail = tail.dropLast(1)
        }
        if (tail.isEmpty()) throw NcodeError("нужно: повернуть налево/направо на <число> [объекту <имя>]")
        val v = evalExpression(tail.joinToString(" "))
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            val next = if (dir == "налево") o.rot + v.toDouble() else o.rot - v.toDouble()
            o.rot = ((next % 360) + 360) % 360
        }
        gfxPanel?.repaint()
    }

    private fun runPlyt(line: String) {
        val rest0 = keywordTail(line, "плыть").trim()
        if (rest0.isEmpty()) throw NcodeError("нужно: плыть к x y за <время> [объекту <имя>]")
        val split = splitObjEnd(rest0)
        val objName = split.second
        val rest = split.first
        if (rest.isEmpty()) throw NcodeError("нужно: плыть к x y за <время> [объекту <имя>]")
        var toks = rest.split(Regex("\\s+"))
        if (toks.isNotEmpty() && toks[0].lowercase(java.util.Locale.ROOT) == "к") toks = toks.drop(1)
        if (toks.size < 5 || toks[2].lowercase(java.util.Locale.ROOT) != "за") throw NcodeError("нужно: плыть к x y за <время> [объекту <имя>]")
        val tx = evalArithOperand(toks[0])
        val ty = evalArithOperand(toks[1])
        val u = toks.last().lowercase(java.util.Locale.ROOT)
        if (u != "секунда" && u != "секунды" && u != "секунд" && u != "секунду") throw NcodeError("время — в секундах")
        val tExpr = toks.drop(3).dropLast(1).joinToString(" ").trim()
        if (tExpr.isEmpty()) throw NcodeError("нужно: плыть к x y за <время>")
        val tVal = evalExpression(tExpr)
        if (!numberRegex.matches(tVal)) throw NcodeError("тут нужно число, а тут `$tVal`")
        val seconds = tVal.toDouble()
        if (seconds < 0) throw NcodeError("время не может быть отрицательным")
        if (seconds * 1000.0 > 86_400_000.0) throw NcodeError("максимум — 24 часа")
        if (dryRun) return
        requireWindow()
        val key = objName ?: movingObjectKey()
        val steps = maxOf(1, (seconds * 20).toInt())
        val sleepMs = (seconds * 1000 / steps).toLong()
        val obj = synchronized(gfxLock) {
            gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
        }
        val dx: Double
        val dy: Double
        synchronized(gfxLock) {
            dx = (tx - obj.x) / steps
            dy = (ty - obj.y) / steps
        }
        repeat(steps) {
            if (closeRequested) throw SceneStop()
            synchronized(gfxLock) {
                val nx = obj.x + dx
                val ny = obj.y + dy
                if (obj.penDown && (nx != obj.x || ny != obj.y)) {
                    penLines.add(PenLine(obj.x, obj.y, nx, ny, obj.penR, obj.penG, obj.penB, obj.penSize))
                }
                obj.x = nx
                obj.y = ny
            }
            gfxPanel?.repaint()
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw NcodeError("ожидание прервано")
            }
            checkEvents()
        }
        gfxPanel?.repaint()
    }

    private fun runPenDown(line: String, down: Boolean) {
        val toks = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var obj: String? = null
        if (toks.size == 2) obj = null
        else if (toks.size == 4 && toks[2].lowercase(java.util.Locale.ROOT) == "объекту") obj = toks[3].lowercase(java.util.Locale.ROOT)
        else throw NcodeError(if (down) "нужно: опустить перо [объекту <имя>]" else "нужно: поднять перо [объекту <имя>]")
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            o.penDown = down
        }
    }

    private fun runPenColor(line: String) {
        val all = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tail = all.drop(2)
        var rgb: List<String>
        var obj: String? = null
        if (tail.size == 3) rgb = tail
        else if (tail.size == 5 && tail[3].lowercase(java.util.Locale.ROOT) == "объекту") {
            rgb = tail.take(3)
            obj = tail[4].lowercase(java.util.Locale.ROOT)
        } else throw NcodeError("нужно: цвет пера R G B [объекту <имя>]")
        val r = evalArithOperand(rgb[0])
        val g = evalArithOperand(rgb[1])
        val b = evalArithOperand(rgb[2])
        for ((c, n) in listOf(r to "красный", g to "зелёный", b to "синий")) {
            if (c != kotlin.math.floor(c) || c < 0 || c > 255) throw NcodeError(n + " — целое 0..255")
        }
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            o.penR = r.toInt()
            o.penG = g.toInt()
            o.penB = b.toInt()
        }
    }

    private fun runPenSize(line: String) {
        val rest0 = keywordTail(line, "размер пера").trim()
        if (rest0.isEmpty()) throw NcodeError("нужно: размер пера <число> [объекту <имя>]")
        val split = splitObjEnd(rest0)
        val obj = split.second
        val core = split.first
        if (core.isEmpty()) throw NcodeError("нужно: размер пера <число> [объекту <имя>]")
        val v = evalExpression(core)
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        if (v.toDouble() < 1) throw NcodeError("размер пера больше нуля")
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            o.penSize = v.toDouble().toInt()
        }
    }

    private fun runDobavitKostyum(line: String) {
        val rest = keywordTail(line, "добавить костюм").trim()
        if (rest.isEmpty()) throw NcodeError("нужно: добавить костюм [объекту <имя>] <путь>")
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var path: String
        var obj: String? = null
        if (toks.size == 1) path = toks[0]
        else if (toks.size == 3 && toks[0].lowercase(java.util.Locale.ROOT) == "объекту") {
            obj = toks[1].lowercase(java.util.Locale.ROOT)
            path = toks[2]
        } else throw NcodeError("нужно: добавить костюм [объекту <имя>] <путь>")
        if (dryRun) {
            resolveScriptFile(path, currentScriptDir())
            return
        }
        requireWindow()
        val img = loadImage(path)
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            o.costumes.add(img)
        }
        gfxPanel?.repaint()
    }

    private fun runSleduyushiy(line: String) {
        val toks = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var obj: String? = null
        if (toks.size == 2) obj = null
        else if (toks.size == 4 && toks[2].lowercase(java.util.Locale.ROOT) == "объекту") obj = toks[3].lowercase(java.util.Locale.ROOT)
        else throw NcodeError("нужно: следующий костюм [объекту <имя>]")
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            if (o.costumes.isEmpty()) throw NcodeError("нет костюмов")
            o.costume = (o.costume + 1) % o.costumes.size
        }
        gfxPanel?.repaint()
    }

    private fun runKostyum(line: String) {
        val rest0 = keywordTail(line, "костюм").trim()
        if (rest0.isEmpty()) throw NcodeError("нужно: костюм <номер> [объекту <имя>]")
        val split = splitObjEnd(rest0)
        val obj = split.second
        val core = split.first
        if (core.isEmpty()) throw NcodeError("нужно: костюм <номер> [объекту <имя>]")
        val v = evalExpression(core)
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            if (o.costumes.isEmpty()) throw NcodeError("нет костюмов")
            val d = v.toDouble()
            if (d != kotlin.math.floor(d) || d < 1 || d > o.costumes.size) throw NcodeError("костюмы 1.." + o.costumes.size)
            o.costume = d.toInt() - 1
        }
        gfxPanel?.repaint()
    }

    private fun runLine(line: String) {
        val low = line.lowercase()
        when {
            isObjectProp(line) ->
                runObjectProp(line)
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
            low == "создать список" || low.startsWith("создать список ") || low.startsWith("создать список\t") ->
                runSozdatSpisok(line)
            low == "добавить в список" || low.startsWith("добавить в список ") || low.startsWith("добавить в список\t") ->
                runDobavit(line)
            low == "убрать из списка" || low.startsWith("убрать из списка ") || low.startsWith("убрать из списка\t") ->
                runUbrat(line)
            low == "очистить список" || low.startsWith("очистить список ") || low.startsWith("очистить список\t") ->
                runOchistitSpisok(line)
            low == "стоп" || low.startsWith("стоп ") || low.startsWith("стоп\t") ->
                throw BreakSignal()
            low == "дальше" || low.startsWith("дальше ") || low.startsWith("дальше\t") ->
                throw ContinueSignal()
            low == "запустить" || low.startsWith("запустить ") || low.startsWith("запустить\t") ->
                runZapustit(line)
            low == "выполнить" || low.startsWith("выполнить ") || low.startsWith("выполнить\t") ->
                runZapustit(line)
            low == "создать окно" || low.startsWith("создать окно ") || low.startsWith("создать окно\t") ->
                runSozdatOkno(line, "создать окно")
            low == "открыть окно" || low.startsWith("открыть окно ") || low.startsWith("открыть окно\t") ->
                runSozdatOkno(line, "открыть окно")
            low == "закрыть окно" ->
                runZakrytOkno(line)
            low == "нарисовать" || low.startsWith("нарисовать ") || low.startsWith("нарисовать\t") ||
                low == "рисовать" || low.startsWith("рисовать ") || low.startsWith("рисовать\t") ||
                low == "рисуй" || low.startsWith("рисуй ") || low.startsWith("рисуй\t") ->
                runRisovat(line)
            low == "показать" || low.startsWith("показать ") || low.startsWith("показать\t") ->
                runPokazat(line, "показать", true)
            low == "спрятать" || low.startsWith("спрятать ") || low.startsWith("спрятать\t") ->
                runPokazat(line, "спрятать", true)
            low == "скрыть" || low.startsWith("скрыть ") || low.startsWith("скрыть\t") ->
                runPokazat(line, "скрыть", false)
            low == "очистить" ->
                runOchistit(line)
            low == "идти" || low.startsWith("идти ") || low.startsWith("идти\t") ->
                runIdti(line)
            low == "повернуть" || low.startsWith("повернуть ") || low.startsWith("повернуть\t") ->
                runPovernut(line)
            low == "плыть" || low.startsWith("плыть ") || low.startsWith("плыть\t") ->
                runPlyt(line)
            low == "опустить перо" || low.startsWith("опустить перо ") || low.startsWith("опустить перо\t") ->
                runPenDown(line, true)
            low == "поднять перо" || low.startsWith("поднять перо ") || low.startsWith("поднять перо\t") ->
                runPenDown(line, false)
            low == "цвет пера" || low.startsWith("цвет пера ") || low.startsWith("цвет пера\t") ->
                runPenColor(line)
            low == "размер пера" || low.startsWith("размер пера ") || low.startsWith("размер пера\t") ->
                runPenSize(line)
            low == "добавить костюм" || low.startsWith("добавить костюм ") || low.startsWith("добавить костюм\t") ->
                runDobavitKostyum(line)
            low == "следующий костюм" || low.startsWith("следующий костюм ") || low.startsWith("следующий костюм\t") ->
                runSleduyushiy(line)
            low == "костюм" || low.startsWith("костюм ") || low.startsWith("костюм\t") ->
                runKostyum(line)
            low == "записать в файл" || low.startsWith("записать в файл ") || low.startsWith("записать в файл\t") ->
                runZapisat(line, false)
            low == "добавить в файл" || low.startsWith("добавить в файл ") || low.startsWith("добавить в файл\t") ->
                runZapisat(line, true)
            else -> throw NcodeError("неизвестная команда (нужно: задать / изменить / присвоить / сделать / напечатать / печатать / вывести / ждать / спросить / если / повтори / вещать / запустить / создать окно / рисовать / показать / задать прозрачность / задать образ объекту / идти / при нажатии)")
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
        val out = evalExpression(expr)
        if (!dryRun) println(out)
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
        if (!dryRun) {
            var left = millis.toLong()
            while (left > 0) {
                if (closeRequested) throw SceneStop()
                val step = if (left > 50) 50 else left
                try {
                    Thread.sleep(step)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw NcodeError("ожидание прервано")
                }
                left -= step
                checkEvents()
            }
        }
    }

    private var stdin: java.io.BufferedReader? = null

    private fun readStdinLine(): String? {
        if (stdin == null) stdin = java.io.BufferedReader(
            java.io.InputStreamReader(System.`in`, Charsets.UTF_8)
        )
        return stdin!!.readLine()
    }

    private fun runAsk(line: String) {
        val rest = keywordTail(line, "спросить")
        if (rest.isEmpty()) throw NcodeError("нужно: спросить <подсказка> сохранить в <имя>")
        val (prompt, name) = splitAsk(rest)
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        if (name.lowercase() in reserved) throw NcodeError("`$name` — служебное слово, возьми другое имя")
        if (prompt.isNotEmpty()) {
            if (dryRun) evalConcat(prompt)
            else {
                print(evalConcat(prompt) + " ")
                System.out.flush()
            }
        }
        if (dryRun) {
            vars[name.lowercase()] = "1"
            return
        }
        vars[name.lowercase()] = readStdinLine() ?: ""
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
        val head0 = toks[0].lowercase(java.util.Locale.ROOT)
        if (head0 == "взять" && toks.size >= 4 &&
            toks[1].lowercase(java.util.Locale.ROOT) == "из" &&
            toks[2].lowercase(java.util.Locale.ROOT) == "списка"
        ) {
            if (toks.size != 5) throw NcodeError("нужно: взять из списка <имя> <номер>")
            val list = listByName(toks[3])
            return list[listIndex(list, toks[4])]
        }
        if (head0 == "длина" && toks.size >= 2 && toks[1].lowercase(java.util.Locale.ROOT) == "списка") {
            if (toks.size != 3) throw NcodeError("нужно: длина списка <имя>")
            return listByName(toks[2]).size.toString()
        }
        if (head0 == "есть" && toks.size >= 4 &&
            toks[1].lowercase(java.util.Locale.ROOT) == "в" &&
            toks[2].lowercase(java.util.Locale.ROOT) == "списке"
        ) {
            if (toks.size < 5) throw NcodeError("нужно: есть в списке <имя> <значение>")
            val list = listByName(toks[3])
            val v = evalExpression(toks.drop(4).joinToString(" ")).lowercase(java.util.Locale.ROOT)
            return if (list.any { it.lowercase(java.util.Locale.ROOT) == v }) "истина" else "ложь"
        }
        if (head0 == "прочитать" && toks.size >= 2 && toks[1].lowercase(java.util.Locale.ROOT) == "файл") {
            if (toks.size != 3) throw NcodeError("нужно: прочитать файл <путь>")
            return readFileTextOrDry(toks[2])
        }
        if (head0 == "найти") {
            if (toks.size < 4 || toks[2].lowercase(java.util.Locale.ROOT) != "в") throw NcodeError("нужно: найти <что> в <где>")
            val needle = textArg(toks[1])
            val hay = textArg(toks.drop(3).joinToString(" "))
            val at = hay.indexOf(needle)
            return if (at < 0) "0" else (at + 1).toString()
        }
        if (head0 == "заменить") {
            if (toks.size < 6 || toks[2].lowercase(java.util.Locale.ROOT) != "на" || toks[4].lowercase(java.util.Locale.ROOT) != "в") throw NcodeError("нужно: заменить <что> на <чем> в <где>")
            val what = textArg(toks[1])
            val with = textArg(toks[3])
            val hay = textArg(toks.drop(5).joinToString(" "))
            return hay.replace(what, with)
        }
        if (head0 == "длина" && (toks.size < 2 || toks[1].lowercase(java.util.Locale.ROOT) != "списка")) {
            if (toks.size != 2) throw NcodeError("нужно: длина <что>")
            return textArg(toks[1]).length.toString()
        }
        if ((head0 == "сумма" || head0 == "среднее") && toks.size >= 2 && toks[1].lowercase(java.util.Locale.ROOT) == "списка") {
            if (toks.size != 3) throw NcodeError("нужно: " + head0 + " списка <имя>")
            val list = listByName(toks[2])
            if (list.isEmpty() && head0 == "среднее") throw NcodeError("список пуст")
            var sum = 0.0
            for (el in list) {
                if (!numberRegex.matches(el)) throw NcodeError("в списке не число: `$el`")
                sum += el.toDouble()
            }
            if (head0 == "сумма") return fmtNum(sum)
            return fmtNum(sum / list.size)
        }
        if ((head0 == "минимум" || head0 == "максимум") && toks.size >= 2 && toks[1].lowercase(java.util.Locale.ROOT) == "списка") {
            if (toks.size != 3) throw NcodeError("нужно: " + head0 + " списка <имя>")
            val list = listByName(toks[2])
            if (list.isEmpty()) throw NcodeError("список пуст")
            var best = list[0]
            if (!numberRegex.matches(best)) throw NcodeError("в списке не число: `$best`")
            for (el in list.drop(1)) {
                if (!numberRegex.matches(el)) throw NcodeError("в списке не число: `$el`")
                val better = if (head0 == "минимум") el.toDouble() < best.toDouble() else el.toDouble() > best.toDouble()
                if (better) best = el
            }
            return fmtNum(best.toDouble())
        }
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
        if (low == "данные") return lastData
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
        "синус" to 1,
        "косинус" to 1,
        "время" to 0
    )
    private val startNanos = System.nanoTime()

    private fun textArg(tok: String): String {
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
        return t
    }

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
            "синус" -> {
                return fmtNum(kotlin.math.sin(Math.toRadians(numArg(fname, args[0]))))
            }
            "косинус" -> {
                return fmtNum(kotlin.math.cos(Math.toRadians(numArg(fname, args[0]))))
            }
            "время" -> {
                return fmtNum((System.nanoTime() - startNanos).toDouble() / 1e9)
            }
            else -> throw NcodeError("не знаю формулу `$fname`")
        }
    }
}

class NcodeError(message: String) : Exception(message)
class BreakSignal : Exception()
class ContinueSignal : Exception()
class SceneStop : Exception()

private fun enableUtf8Console() {
    if (System.console() == null) return
    if (!System.getProperty("os.name", "").lowercase().startsWith("windows")) return
    try {
        ProcessBuilder("cmd", "/c", "chcp 65001 >nul 2>&1").inheritIO().start().waitFor()
    } catch (e: Exception) {
    }
}

private val HELP = """
    Ncode 2.1 — русский мини-язык (.ncode, UTF-8)
    Использование:
      Ncode программа.ncode   — выполнить файл (сначала молча проверяется)
      Ncode -help             — эта справка
      Ncode --проверить программа.ncode — проверить без запуска
    Команды (регистр не важен, одна строка — одна команда):
      задать <имя> <значение>              — создать или перезаписать переменную
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
      конец — закрывает блок; в одну строку можно не писать:
        если да то вывести а
      вещать всем <сообщение> — событие всем «когда будет получено»
        вещать всем какашка
      Когда будет получено <сообщение> — обработчик (конец не пишем)
        когда будет получено какашка
      запустить [скрипт] <путь> — выполнить другой файл (переменные общие)
        запустить скрипт images/игра.ncode
      пока <условие> — цикл пока правда, тело до `конец` (только блоком)
      повторяй/всегда — бесконечно, тело до `конец`; стоп — выйти, дальше — дальше
      создать список/добавить/взять/длина/убрать/очистить — списки с 1
      записать/добавить/прочитать файл — файлы построчно
      найти/заменить — строки; вещать X данные Y — данные в обработчик
      создать окно/открыть окно, закрыть окно — окно 800×600 mygame
      нарисовать <имя> x y, задать/изменить прозрачность/размер/поворот/цвет/образ объекту — сцена
      идти/повернуть/плыть — движение; когда нажата клавиша/при нажатии — ввод
    Знаки — то же словами: + плюс, - минус, * умножить, / разделить, % остаток,
      = и == равно, != неравно, > больше, < меньше, >= <=, && и, || или
    Формулы (везде, где значение): случайно 1 5, корень 9, модуль -5,
      округлить 3.7, степень 2 10, минимум 3 7, максимум 3 7, длина "слово",
      синус 90, косинус 0, время (секунды с запуска)
    Выражения: .. > умножить/разделить/остаток > плюс/минус > сравнение > и > или
      сравнения: равно/равняется, неравно/неравняется, больше, меньше,
                 больше или равно/равняется, меньше или равно/равняется
      правда: истина/да/правда; ложь: ложь/нет/неправда
    Примеры: test.ncode, test2.ncode. Дока: NCODE_v0.1.md
""".trimIndent()

private fun readNcodeLines(file: java.io.File): List<String> {
    val lines = file.readLines(Charsets.UTF_8)
    if (lines.isEmpty()) return lines
    val first = lines[0]
    if (first.isEmpty() || first[0] != 65279.toChar()) return lines
    return listOf(first.drop(1)) + lines.drop(1)
}

private fun safeCanon(f: java.io.File): String {
    return try {
        f.canonicalPath
    } catch (e: Exception) {
        f.path
    }
}

private fun relLabel(path: String): String {
    return try {
        val cwd = java.io.File(System.getProperty("user.dir")).canonicalFile.toPath()
        val rel = cwd.relativize(java.io.File(path).canonicalFile.toPath()).toString()
        if (rel.startsWith("..")) path else rel
    } catch (e: Exception) {
        path
    }
}

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
    var dry = false
    var fileArg = args[0]
    if (args[0].lowercase() in setOf("--проверить", "-проверить", "--check")) {
        dry = true
        if (args.size < 2) {
            System.err.println("Нужно: Ncode --проверить <файл.ncode>")
            kotlin.system.exitProcess(2)
        }
        fileArg = args[1]
    }
    val file = java.io.File(fileArg)
    if (!file.exists()) {
        System.err.println("Нет файла: " + fileArg)
        kotlin.system.exitProcess(2)
    }
    val lines = readNcodeLines(file)
    if (!dry) {
        val probe = NcodeInterpreter()
        probe.dryRun = true
        probe.checkLabel = relLabel(fileArg)
        var probeSkips: List<IntRange>
        try {
            probeSkips = probe.loadHandlers(lines)
        } catch (e: NcodeError) {
            System.err.println("Ошибка: " + e.message)
            kotlin.system.exitProcess(1)
            return
        }
        probe.resetScripts(safeCanon(file))
        var probeCode = 0
        try {
            probeCode = probe.runLines(lines, 1, probeSkips)
            probe.warnStaticCycles(file, lines)
            if (probe.checkHandlers() != 0) probeCode = 1
            if (probe.checkKeyHandlers() != 0) probeCode = 1
        } catch (e: BreakSignal) {
            System.err.println("стоп — только внутри цикла")
            probeCode = 1
        } catch (e: ContinueSignal) {
            System.err.println("дальше — только внутри цикла")
            probeCode = 1
        }
        if (probeCode != 0) kotlin.system.exitProcess(probeCode)
    }
    val interp = NcodeInterpreter()
    interp.dryRun = dry
    interp.checkLabel = relLabel(fileArg)
    var code = 0
    try {
        val curSkips = try {
            interp.loadHandlers(lines)
        } catch (e: NcodeError) {
            System.err.println("Ошибка: " + e.message)
            kotlin.system.exitProcess(1)
            return
        }
        interp.resetScripts(safeCanon(file))
                code = interp.runLines(lines, 1, curSkips)
                if (dry) {
                    interp.warnStaticCycles(file, lines)
                    if (interp.checkHandlers() != 0) code = 1
                    if (interp.checkKeyHandlers() != 0) code = 1
                }
    } catch (e: BreakSignal) {
        System.err.println("стоп — только внутри цикла")
        code = 1
    } catch (e: ContinueSignal) {
        System.err.println("дальше — только внутри цикла")
        code = 1
    } catch (e: SceneStop) {
        code = 0
    }
    if (code != 0) kotlin.system.exitProcess(code)
    if (!dry) {
        interp.warnNoWindowForInput()
        while (interp.isWindowOpen()) {
            interp.checkEvents()
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}
