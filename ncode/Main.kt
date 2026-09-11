package ncode

class NcodeInterpreter(val platform: NcodePlatform) : NcodeInputSink {
    override fun keyDown(cands: Set<String>) {
        synchronized(keyQueue) {
            heldKeys.addAll(cands)
            keyQueue.add(KeyEv(cands))
        }
    }
    override fun keyPress(cands: Set<String>) {
        synchronized(keyQueue) {
            keyQueue.add(KeyEv(cands))
        }
    }
    override fun keyUp(cands: Set<String>) {
        synchronized(keyQueue) {
            heldKeys.removeAll(cands)
        }
        synchronized(keyUpQueue) {
            keyUpQueue.add(KeyEv(cands))
        }
    }
    override fun focusLost() {
        synchronized(keyQueue) {
            heldKeys.clear()
        }
    }
    override fun mouseDown(x: Double, y: Double) {
        synchronized(mouseQueue) {
            mouseDown = true
            mouseQueue.add(MouseEv(x, y, 0))
        }
    }
    override fun mouseUp(x: Double, y: Double) {
        synchronized(mouseQueue) {
            mouseDown = false
            mouseQueue.add(MouseEv(x, y, 1))
        }
    }
    override fun mouseMove(x: Double, y: Double) {
        synchronized(mouseQueue) {
            mouseQueue.add(MouseEv(x, y, 2))
        }
    }

    private val vars = mutableMapOf<String, String>()
    private val lists = mutableMapOf<String, MutableList<String>>()
    private var execFailed = false
    var dryRun = false
    var checkLabel = ""
    private var warnedCycle = false
    private var warnedNoWindow = false

    private fun reportError(lineNo: Int, msg: String?) {
        val text = msg ?: "ошибка"
        if (dryRun) platform.console.printErr(checkLabel + ":" + lineNo + ": " + text)
        else platform.console.printErr("Ошибка в строке " + lineNo + ": " + text)
    }

    private fun warnCycle() {
        if (warnedCycle) return
        warnedCycle = true
        platform.console.printErr("Варнинг: зацикливание есть")
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
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) { i++; continue }
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
                if (dryRun) platform.console.printErr(checkLabel + ":" + (base + i) + ": " + e.message)
                else platform.console.printErr("Ошибка в строке ${base + i}: ${e.message}  >> $rawLine")
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
    private data class MouseEv(val x: Double, val y: Double, val kind: Int)

    private data class MouseHandler(val target: String?, val lines: List<String>, val base: Int, val kind: Int)

    private data class CloneHandler(val parent: String, val lines: List<String>, val base: Int)
    private data class ProcHandler(val name: String, val lines: List<String>, val base: Int)
    private data class TimerH(val countText: String, val unit: String, var intervalNs: Long, var next: Long, var ready: Boolean, val lines: List<String>, val base: Int)

    private val cloneHandlers = mutableListOf<CloneHandler>()
    private val procHandlers = mutableListOf<ProcHandler>()
    private val timerHandlers = mutableListOf<TimerH>()
    private data class CollHandler(val a: String, val b: String, val lines: List<String>, val base: Int)
    private data class TrigHandler(val a: String, val b: String, val lines: List<String>, val base: Int)
    private val collHandlers = mutableListOf<CollHandler>()
    private val trigHandlers = mutableListOf<TrigHandler>()
    private val collLast = mutableMapOf<String, Boolean>()
    private val trigLast = mutableMapOf<String, Boolean>()
    private val cloneCount = mutableMapOf<String, Int>()
    private var procDepth = 0

    private val keyHandlers = mutableListOf<KeyHandler>()
    private val keyUpHandlers = mutableListOf<KeyHandler>()
    private val mouseHandlers = mutableListOf<MouseHandler>()
    private val keyQueue = mutableListOf<KeyEv>()
    private val keyUpQueue = mutableListOf<KeyEv>()
    private val mouseQueue = mutableListOf<MouseEv>()
    private val heldKeys = mutableSetOf<String>()
    private var mouseDown = false

    private val keyAliases = mapOf(
        "пробел" to "space",
        "ввод" to "enter",
        "энтер" to "enter",
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

    private fun normKey(raw: String): String {
        val t = raw.trim().lowercase(java.util.Locale.ROOT)
        if (t.length == 1) {
            val c = t[0]
            if (c in 'a'..'z' || c in '0'..'9') return t
            ruKeys[t]?.let { return it }
            return t
        }
        keyAliases[t]?.let { return it }
        ruKeys[t]?.let { return it }
        return t.replace(" ", "_")
    }

    private fun keyMatches(want: String, ev: KeyEv): Boolean {
        return want in ev.cands
    }

    private fun keyHeader(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts[0].lowercase(java.util.Locale.ROOT) != "когда") return null
        if (parts[1].lowercase(java.util.Locale.ROOT) != "нажата") return null
        if (parts.size == 4 && parts[2].lowercase(java.util.Locale.ROOT) == "клавиша") return parts[3]
        if (parts.size == 3 && parts[2].lowercase(java.util.Locale.ROOT) != "клавиша") return parts[2]
        return null
    }

    private fun keyUpHeader(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts[0].lowercase(java.util.Locale.ROOT) != "когда") return null
        val second = parts[1].lowercase(java.util.Locale.ROOT)
        if (second != "отпущена" && second != "отпущен" && second != "отпущено") return null
        if (parts.size == 4 && parts[2].lowercase(java.util.Locale.ROOT) == "клавиша") return parts[3]
        if (parts.size == 3 && parts[2].lowercase(java.util.Locale.ROOT) != "клавиша") return parts[2]
        return null
    }

    private fun cloneHeader(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size != 4) return null
        if (parts[0].lowercase(java.util.Locale.ROOT) != "когда") return null
        if (parts[1].lowercase(java.util.Locale.ROOT) != "создан") return null
        if (parts[2].lowercase(java.util.Locale.ROOT) != "клон") return null
        return parts[3]
    }

    private fun procHeader(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        if (parts[0].lowercase(java.util.Locale.ROOT) != "чтобы") return null
        return parts[1]
    }

    private fun timerHeader(t: String): Boolean {
        val parts = t.trim().split(Regex("\\s+"), limit = 2)
        return parts[0].lowercase(java.util.Locale.ROOT) == "каждые"
    }

    private fun collHeader(t: String): Pair<String, String>? {
        val m = Regex("^когда\\s+происходит\\s+столкновение\\s+(\\S+)\\s+и\\s+(\\S+)\\s*$", RegexOption.IGNORE_CASE).find(t.trim()) ?: return null
        return m.groupValues[1].lowercase(java.util.Locale.ROOT) to m.groupValues[2].lowercase(java.util.Locale.ROOT)
    }

    private fun trigHeader(t: String): Pair<String, String>? {
        val m = Regex("^когда\\s+объект\\s+(\\S+)\\s+входит\\s+в\\s+триггер\\s+(\\S+)\\s*$", RegexOption.IGNORE_CASE).find(t.trim()) ?: return null
        return m.groupValues[1].lowercase(java.util.Locale.ROOT) to m.groupValues[2].lowercase(java.util.Locale.ROOT)
    }

    private fun mouseTarget(t: String): String? {
        val low = t.trim().lowercase(java.util.Locale.ROOT)
        if (low == "при нажатии" || low == "при клике" || low == "при отпускании" || low == "при движении мыши" || low == "при движении") return null
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size != 4) {
            if (parts.size >= 2 && parts[1].lowercase(java.util.Locale.ROOT) == "отпускании") throw NcodeError("нужно: при отпускании на <имя>")
            throw NcodeError("нужно: при нажатии на <имя>")
        }
        return parts[3].lowercase(java.util.Locale.ROOT)
    }

    private fun mouseKind(t: String): Int {
        val low = t.trim().lowercase(java.util.Locale.ROOT)
        if (low == "при нажатии" || low == "при клике") return 0
        if (low == "при отпускании") return 1
        if (low == "при движении мыши" || low == "при движении") return 2
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size < 3) return -1
        if (parts[0].lowercase(java.util.Locale.ROOT) != "при") return -1
        val second = parts[1].lowercase(java.util.Locale.ROOT)
        if ((second == "нажатии" || second == "клике") && parts[2].lowercase(java.util.Locale.ROOT) == "на") return 0
        if (second == "отпускании" && parts[2].lowercase(java.util.Locale.ROOT) == "на") return 1
        return -1
    }

    private fun isMouseHeader(t: String): Boolean {
        return mouseKind(t) >= 0
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
        return handlerMsg(t) != null || keyHeader(t) != null || keyUpHeader(t) != null || cloneHeader(t) != null || procHeader(t) != null || timerHeader(t) || mouseHeader(t) || collHeader(t) != null || trigHeader(t) != null
    }

    private fun dropTailEnd(body: MutableList<String>) {
        var k = body.size - 1
        while (k >= 0) {
            val lt = stripComment(body[k]).trim()
            if (lt.isEmpty()) { k--; continue }
            break
        }
        if (k >= 0 && stripComment(body[k]).trim().lowercase(java.util.Locale.ROOT) == "конец") body.removeAt(k)
    }

    fun extractHandlers(lines: List<String>): List<IntRange> {
        val skips = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            val t = stripComment(lines[i]).trim()
            if (t.isEmpty()) { i++; continue }
            val key = keyHeader(t)
            if (key != null) {
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                keyHandlers.add(KeyHandler(normKey(key), body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            val keyUp = keyUpHeader(t)
            if (keyUp != null) {
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                keyUpHandlers.add(KeyHandler(normKey(keyUp), body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            val clone = cloneHeader(t)
            if (clone != null) {
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                cloneHandlers.add(CloneHandler(clone.lowercase(java.util.Locale.ROOT), body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            val proc = procHeader(t)
            if (proc != null) {
                if (!nameRegex.matches(proc)) throw NcodeError("строка ${i + 1}: плохое имя `$proc`")
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                procHandlers.add(ProcHandler(proc.lowercase(java.util.Locale.ROOT), body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            if (timerHeader(t)) {
                val start = i
                val tail = t.trim().substring(6).trim()
                if (tail.isEmpty()) throw NcodeError("строка ${i + 1}: нужно: каждые <число> <секунды|минуты|часы>")
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                val cut = tail.indexOfLast { it.isWhitespace() }
                if (cut < 0) throw NcodeError("строка ${i + 1}: нужно: каждые <число> <секунды|минуты|часы>")
                timerHandlers.add(TimerH(tail.substring(0, cut).trim(), tail.substring(cut).trim(), 0, 0, false, body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            val coll = collHeader(t)
            if (coll != null) {
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                collHandlers.add(CollHandler(coll.first, coll.second, body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            val trig = trigHeader(t)
            if (trig != null) {
                val start = i
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                trigHandlers.add(TrigHandler(trig.first, trig.second, body, start + 2))
                skips.add(start..i - 1)
                continue
            }
            if (mouseHeader(t)) {
                val start = i
                val kind = mouseKind(t)
                val target = try {
                    mouseTarget(t)
                } catch (e: NcodeError) {
                    throw NcodeError("строка ${i + 1}: " + (e.message ?: "ошибка"))
                }
                i++
                val body = mutableListOf<String>()
                while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
                    body.add(lines[i])
                    i++
                }
                dropTailEnd(body)
                mouseHandlers.add(MouseHandler(target, body, start + 2, kind))
                skips.add(start..i - 1)
                continue
            }
            val msg = handlerMsg(t)
            if (msg == null) { i++; continue }
            if (msg.isEmpty()) throw NcodeError("строка ${i + 1}: после `когда будет получено` нужно сообщение")
            val start = i
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && !isHeader(stripComment(lines[i]).trim())) {
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
        keyUpHandlers.clear()
        cloneHandlers.clear()
        procHandlers.clear()
        timerHandlers.clear()
        collHandlers.clear()
        trigHandlers.clear()
        collLast.clear()
        trigLast.clear()
        cloneCount.clear()
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
                val t = stripComment(raw).trim()
                if (t.isEmpty()) continue
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
                        platform.console.printErr(relLabel(entry.canonicalPath) + ":" + (h.base + idx) + ": Варнинг: зацикливание есть")
                        warnedCycle = true
                        return
                    }
                }
            }
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
        var acc = stripComment(lines[start]).trim()
        var idx = start
        var endPos = matchEnd(acc)
        while (endPos < 0) {
            idx++
            if (idx >= lines.size) throw NcodeError("нет `конец` для `если`")
            val t = stripComment(lines[idx]).trim()
            if (t.isEmpty()) continue
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
        val first = stripComment(lines[start]).trim()
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
        val (cond, trail) = splitHeader(stripComment(lines[start]).trim())
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
            val t = stripComment(raw).trim()
            if (t.isEmpty()) { i++; continue }
            if (startsKw(t, "иначеесли")) {
                if (inElse) throw NcodeError("`иначеесли` после `иначе` — нельзя")
                val (c2, tr2) = splitHeader(t)
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
                    val tj = stripComment(lines[j]).trim()
                    if (tj.isEmpty()) continue
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
        val first = stripComment(lines[start]).trim()
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
        val first = stripComment(lines[start]).trim()
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
        val t0 = stripComment(lines[start]).trim()
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
            val t = stripComment(raw).trim()
            if (t.isEmpty()) { i++; continue }
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
                    val tk = stripComment(lines[k]).trim()
                    if (tk.isEmpty()) continue
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
        for (h in keyUpHandlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        for (h in cloneHandlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        for (h in procHandlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        for (h in timerHandlers) {
            try {
                val v = evalExpression(h.countText)
                if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
                if (v.toDouble() * msMult(h.unit) <= 0) throw NcodeError("интервал больше нуля")
            } catch (e: NcodeError) {
                reportError(h.base, e.message)
                code = 1
                continue
            }
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        for (h in collHandlers) {
            if (runLines(h.lines, h.base) != 0) code = 1
        }
        for (h in trigHandlers) {
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
        val ups = synchronized(keyUpQueue) {
            val got = keyUpQueue.toList()
            keyUpQueue.clear()
            got
        }
        for (ev in ups) {
            for (h in keyUpHandlers.toList()) {
                if (keyMatches(h.key, ev)) {
                    if (runLines(h.lines, h.base) != 0) execFailed = true
                }
            }
        }
        if (timerHandlers.isNotEmpty()) {
            val now = platform.clock.nanoTime()
            for (t in timerHandlers.toList()) {
                if (!t.ready) {
                    val v = try {
                        evalExpression(t.countText)
                    } catch (e: NcodeError) {
                        reportError(t.base, e.message)
                        execFailed = true
                        timerHandlers.remove(t)
                        continue
                    }
                    if (!numberRegex.matches(v)) {
                        reportError(t.base, "тут нужно число, а тут `$v`")
                        execFailed = true
                        timerHandlers.remove(t)
                        continue
                    }
                    val ms = try {
                        v.toDouble() * msMult(t.unit)
                    } catch (e: NcodeError) {
                        reportError(t.base, e.message)
                        execFailed = true
                        timerHandlers.remove(t)
                        continue
                    }
                    if (ms <= 0) {
                        reportError(t.base, "интервал больше нуля")
                        execFailed = true
                        timerHandlers.remove(t)
                        continue
                    }
                    t.intervalNs = (ms * 1_000_000).toLong()
                    t.next = now + t.intervalNs
                    t.ready = true
                    continue
                }
                if (now >= t.next) {
                    if (runLines(t.lines, t.base) != 0) execFailed = true
                    t.next = platform.clock.nanoTime() + t.intervalNs
                }
            }
        }
        for (h in collHandlers.toList()) {
            val key = h.a + "|" + h.b
            val now = try { touches(h.a, h.b) } catch (e: NcodeError) { false }
            val was = collLast[key] ?: false
            if (now && !was) {
                val b = try { objBox(h.a) } catch (e: Exception) { null }
                if (b != null) {
                    lastCollX = (b[0] + b[1]) / 2
                    lastCollY = (b[2] + b[3]) / 2
                }
                val aObj = try { synchronized(gfxLock) { gfxObjs.find { it.name == h.a } } } catch (e: Exception) { null }
                if (aObj != null) aObj.lastHit = Math.hypot(aObj.vx, aObj.vy)
                if (runLines(h.lines, h.base) != 0) execFailed = true
            }
            collLast[key] = now
        }
        for (h in trigHandlers.toList()) {
            val key = h.a + "|" + h.b
            val isTrig = try { synchronized(gfxLock) { gfxObjs.find { it.name == h.b }?.isTrigger } ?: false } catch (e: Exception) { false }
            val now = if (!isTrig) false else try { touches(h.a, h.b) } catch (e: NcodeError) { false }
            val was = trigLast[key] ?: false
            if (now && !was) {
                if (runLines(h.lines, h.base) != 0) execFailed = true
            }
            trigLast[key] = now
        }
        physicsStep()
        val clicks = synchronized(mouseQueue) {
            val got = mouseQueue.toList()
            mouseQueue.clear()
            got
        }
        for (ev in clicks) {
            vars["мышьх"] = fmtNum(ev.x)
            vars["мышьу"] = fmtNum(ev.y)
            for (h in mouseHandlers.toList()) {
                if (h.kind != ev.kind) continue
                if (h.target == null || clickHits(h.target, ev.x, ev.y)) {
                    if (runLines(h.lines, h.base) != 0) execFailed = true
                }
            }
        }
        pumpLabels()
    }

    private var lastLabelRender = 0L

    private fun pumpLabels() {
        if (varShows.isEmpty()) return
        var any = false
        synchronized(gfxLock) {
            any = varShows.any { it.value.visible }
        }
        if (!any) return
        val now = platform.clock.nanoTime()
        if (now - lastLabelRender < 100_000_000) return
        lastLabelRender = now
        renderCurrent()
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
            val s = o.size / img.w.toDouble()
            hw = img.w * s / 2.0
            hh = img.h * s / 2.0
        } else {
            hw = o.size / 2.0
            hh = o.size / 2.0
        }
        if (o.circle && img == null && o.text == null) {
            return lx * lx + ly * ly <= hw * hw
        }
        return lx >= -hw && lx <= hw && ly >= -hh && ly <= hh
    }

    private fun objBox(name: String): DoubleArray? {
        val o = synchronized(gfxLock) {
            gfxObjs.find { it.name == name }?.copy()
        } ?: return null
        val img = o.costumes.getOrNull(o.costume)
        val hw: Double
        val hh: Double
        if (img != null) {
            val s = o.size / img.w.toDouble()
            hw = img.w * s / 2.0
            hh = img.h * s / 2.0
        } else {
            hw = o.size / 2.0
            hh = o.size / 2.0
        }
        val rad = Math.toRadians(o.rot)
        val cs = kotlin.math.abs(Math.cos(rad))
        val sn = kotlin.math.abs(Math.sin(rad))
        val ex = hw * cs + hh * sn
        val ey = hw * sn + hh * cs
        return doubleArrayOf(o.x - ex, o.x + ex, o.y - ey, o.y + ey)
    }

    private fun physHalf(o: GObj): DoubleArray {
        if (o.colType == 1 && o.colR > 0) {
            return doubleArrayOf(o.colR, o.colR, o.colDx, o.colDy)
        }
        if (o.colW > 0 && o.colH > 0) {
            return doubleArrayOf(o.colW / 2.0, o.colH / 2.0, o.colDx, o.colDy)
        }
        val img = o.costumes.getOrNull(o.costume)
        if (img != null) {
            val s = o.size / img.w.toDouble()
            return doubleArrayOf(img.w * s / 2.0, img.h * s / 2.0, 0.0, 0.0)
        }
        return doubleArrayOf(o.size / 2.0, o.size / 2.0, 0.0, 0.0)
    }

    private fun physBoxOf(o: GObj): DoubleArray {
        val h = physHalf(o)
        val cx = o.x + h[2]
        val cy = o.y + h[3]
        return doubleArrayOf(cx - h[0], cx + h[0], cy - h[1], cy + h[1])
    }

    private fun physBox(name: String): DoubleArray? {
        val o = synchronized(gfxLock) {
            gfxObjs.find { it.name == name }?.copy()
        } ?: return null
        return physBoxOf(o)
    }

    private fun touches(a: String, b: String): Boolean {
        val vis = synchronized(gfxLock) {
            val oa = gfxObjs.find { it.name == a } ?: throw NcodeError("объект не нарисован")
            val ob = gfxObjs.find { it.name == b } ?: throw NcodeError("объект не нарисован")
            oa.visible && ob.visible
        }
        if (!vis) return false
        val ba = physBox(a) ?: throw NcodeError("объект не нарисован")
        val bb = physBox(b) ?: throw NcodeError("объект не нарисован")
        return ba[0] <= bb[1] && bb[0] <= ba[1] && ba[2] <= bb[3] && bb[2] <= ba[3]
    }

    private fun touchesEdge(name: String): Boolean {
        val vis = synchronized(gfxLock) {
            (gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")).visible
        }
        if (!vis) return false
        val b = objBox(name) ?: throw NcodeError("объект не нарисован")
        return b[0] <= -gfxW / 2.0 + camX || b[1] >= gfxW / 2.0 + camX || b[2] <= -gfxH / 2.0 + camY || b[3] >= gfxH / 2.0 + camY
    }

    private fun distObjs(a: String, b: String): Double {
        val p = synchronized(gfxLock) {
            val oa = gfxObjs.find { it.name == a } ?: throw NcodeError("объект не нарисован")
            val ob = gfxObjs.find { it.name == b } ?: throw NcodeError("объект не нарисован")
            doubleArrayOf(oa.x, oa.y, ob.x, ob.y)
        }
        val dx = p[0] - p[2]
        val dy = p[1] - p[3]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun resolveStatic(name: String) {
        val dyn = synchronized(gfxLock) { gfxObjs.find { it.name == name } } ?: return
        if (dyn.bodyType != 0) return
        if (dyn.isTrigger) return
        val statics = synchronized(gfxLock) { gfxObjs.filter { it.bodyType != 0 && !it.isTrigger && it.name != name }.map { it.copy() } }
        for (st in statics) {
            if (!touches(name, st.name)) continue
            val a = physBox(name) ?: continue
            val b = physBox(st.name) ?: continue
            val overlapX = minOf(a[1], b[1]) - maxOf(a[0], b[0])
            val overlapY = minOf(a[3], b[3]) - maxOf(a[2], b[2])
            if (overlapX <= 0 || overlapY <= 0) continue
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: return
                val impact = Math.hypot(o.vx, o.vy)
                if (overlapX < overlapY) {
                    if (o.x < st.x) o.x -= overlapX else o.x += overlapX
                    o.vx = -o.vx * o.rest
                    if (kotlin.math.abs(o.vx) < 1.0) o.vx = 0.0
                } else {
                    val landed = o.y > st.y
                    if (o.y < st.y) o.y -= overlapY else o.y += overlapY
                    o.vy = -o.vy * o.rest
                    if (kotlin.math.abs(o.vy) < 1.0) o.vy = 0.0
                    if (landed && o.vy == 0.0) {
                        o.vx *= (1.0 - kotlin.math.min(0.3, o.fric * 0.1)).coerceIn(0.0, 1.0)
                        if (kotlin.math.abs(o.vx) < 1.0 && o.fric > 0) o.vx = 0.0
                    }
                }
                lastCollX = (maxOf(a[0], b[0]) + minOf(a[1], b[1])) / 2
                lastCollY = (maxOf(a[2], b[2]) + minOf(a[3], b[3])) / 2
                o.lastHit = impact
            }
        }
    }

    private fun isOnGround(name: String): Boolean {
        val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: return false
        if (o.bodyType == 1) return false
        if (o.isTrigger) return false
        val a = physBox(name) ?: return false
        val eps = 3.0
        val feetY = a[2]
        val checks = synchronized(gfxLock) { gfxObjs.filter { it.bodyType != 0 && !it.isTrigger && it.name != name }.map { it.copy() } }
        for (st in checks) {
            val b = physBox(st.name) ?: continue
            if (Math.abs(feetY - b[3]) < eps && a[0] < b[1] - 1.0 && a[1] > b[0] + 1.0) return true
        }
        return false
    }

    private var lastPhys = 0L
    private fun physicsStep() {
        if (dryRun) return
        if (!platform.gfx.isOpen()) return
        val now = platform.clock.nanoTime()
        if (lastPhys == 0L) { lastPhys = now; return }
        var dt = (now - lastPhys).toDouble() / 1e9
        lastPhys = now
        if (dt <= 0 || dt > 0.2) dt = 0.016
        val toUpdate = synchronized(gfxLock) { gfxObjs.filter { it.bodyType == 0 }.map { it.name } }
        for (nm in toUpdate) {
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == nm } } ?: continue
            if (o.bodyType != 0) continue
            o.vx += gravX * 100.0 * o.gravScale * dt
            o.vy += gravY * 100.0 * o.gravScale * dt
            o.vx *= (1.0 - (airDens + o.linDamp) * dt).coerceIn(0.0, 1.0)
            o.vy *= (1.0 - (airDens + o.linDamp) * dt).coerceIn(0.0, 1.0)
            o.av *= (1.0 - o.angDamp * dt).coerceIn(0.0, 1.0)
            if (o.fixedRot) o.av = 0.0
            val dist = Math.hypot(o.vx * dt, o.vy * dt)
            val half = physHalf(o)
            val minHalf = kotlin.math.max(2.0, kotlin.math.min(half[0], half[1]))
            val maxStep = if (o.bullet) 2.0 else 8.0
            var n = kotlin.math.ceil(dist / kotlin.math.min(minHalf, maxStep)).toInt()
            if (n < 1) n = 1
            if (n > 16) n = 16
            var moved = false
            var k = 0
            while (k < n) {
                val sx = o.vx * dt / n
                val sy = o.vy * dt / n
                if (sx == 0.0 && sy == 0.0) break
                val nx = o.x + sx
                val ny = o.y + sy
                if (o.penDown) {
                    synchronized(gfxLock) { penLines.add(PenSeg(o.x, o.y, nx, ny, o.penR, o.penG, o.penB, o.penSize)) }
                }
                o.x = nx
                o.y = ny
                moved = true
                if (!o.fixedRot && o.av != 0.0) o.rot = (o.rot + o.av * dt / n) % 360
                resolveStatic(nm)
                if (o.vx == 0.0 && o.vy == 0.0) break
                k++
            }
            if (!moved && !o.fixedRot && o.av != 0.0) {
                o.rot = (o.rot + o.av * dt) % 360
            }
        }
        for (j in joints.values.toList()) {
            val a = synchronized(gfxLock) { gfxObjs.find { it.name == j.a } } ?: continue
            val b = synchronized(gfxLock) { gfxObjs.find { it.name == j.b } } ?: continue
            if (j.type == 0) {
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = Math.hypot(dx, dy)
                if (d > j.len) {
                    val ux = dx / d
                    val uy = dy / d
                    val diff = d - j.len
                    if (a.bodyType == 0) { a.x += ux * diff * 0.5; a.y += uy * diff * 0.5 }
                    if (b.bodyType == 0) { b.x -= ux * diff * 0.5; b.y -= uy * diff * 0.5 }
                    if (Math.abs(diff) > j.breakF) joints.remove(j.id)
                }
            } else if (j.type == 1) {
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = Math.hypot(dx, dy)
                val target = j.len
                if (j.len == 0.0) continue
                val diff = d - target
                val force = -j.k * diff - j.d * ((a.vx - b.vx) * (dx / d) + (a.vy - b.vy) * (dy / d))
                if (a.bodyType == 0) { a.vx += force * 0.016 / a.mass; a.vy += force * 0.016 / a.mass }
                if (b.bodyType == 0) { b.vx -= force * 0.016 / b.mass; b.vy -= force * 0.016 / b.mass }
                if (Math.abs(force) > j.breakF) joints.remove(j.id)
            } else if (j.type == 2) {
                val mx = (a.x + b.x) / 2
                val my = (a.y + b.y) / 2
                if (j.motForce != 0.0) {
                    if (a.bodyType == 0) a.av += j.motSpeed * 0.016
                    if (b.bodyType == 0) b.av -= j.motSpeed * 0.016
                }
                if (Math.hypot(a.vx, a.vy) > j.breakF) joints.remove(j.id)
            }
        }
        if (toUpdate.isNotEmpty() || joints.isNotEmpty()) renderCurrent()
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
        val t0 = stripComment(lines[start]).trim()
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
            val t = stripComment(raw).trim()
            if (t.isEmpty()) { i++; continue }
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
                    val tk = stripComment(lines[k]).trim()
                    if (tk.isEmpty()) continue
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
        val first = stripComment(lines[start]).trim()
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
            val t = stripComment(raw).trim()
            if (t.isEmpty()) { i++; continue }
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
                    val tk = stripComment(lines[k]).trim()
                    if (tk.isEmpty()) continue
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
        val first = stripComment(lines[start]).trim()
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
            val t = stripComment(raw).trim()
            if (t.isEmpty()) { i++; continue }
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
                    val tk = stripComment(lines[k]).trim()
                    if (tk.isEmpty()) continue
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

    private fun runPeremeshat(line: String) {
        val rest = keywordTail(line, "перемешать список").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: перемешать список <имя>")
        listByName(rest).shuffle()
    }

    private fun runSozdatPapku(line: String) {
        val rest = keywordTail(line, "создать папку").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: создать папку <имя>")
        if (rest.startsWith("/") || rest.startsWith("\\") || (rest.length >= 2 && rest[1] == ':') || rest.split('/', '\\').any { it == ".." }) throw NcodeError("плохая папка `$rest`")
        if (dryRun) return
        if (!platform.files.mkdirs("saves/" + rest)) throw NcodeError("не могу создать папку `$rest`")
    }

    private fun runUdalitFile(line: String) {
        val rest = keywordTail(line, "удалить файл").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: удалить файл <путь>")
        if (dryRun) {
            if (!dryWritten.contains(rest) && !platform.files.isFile(rest)) throw NcodeError("нет файла `$rest`")
            return
        }
        if (platform.files.isDir(rest)) throw NcodeError("это папка, а не файл `$rest`")
        if (!platform.files.isFile(rest)) throw NcodeError("нет файла `$rest`")
        if (!platform.files.delete(rest) || platform.files.exists(rest)) throw NcodeError("не могу удалить `$rest`")
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
        if (append) platform.files.appendText(path, value)
        else platform.files.writeText(path, value)
    }

    private var lastHttpCode = 200

    private data class HttpRes(val code: Int, val body: String)

    private fun httpCall(method: String, url: String, body: String?): HttpRes {
        var res: HttpRes? = null
        var err: Exception? = null
        val t = Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Ncode")
                    conn.setRequestProperty("Accept", "*/*")
                    if (body != null) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val ct = if (body.trimStart().startsWith("{")) "application/json" else "text/plain"
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", ct + "; charset=utf-8")
                        conn.outputStream.use { it.write(bytes) }
                    }
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val text = try {
                        stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    res = HttpRes(code, text.trimEnd('\r', '\n'))
                } finally {
                    try {
                        conn.disconnect()
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                err = e
            }
        }
        t.isDaemon = true
        t.start()
        t.join(30000)
        if (t.isAlive) throw NcodeError("сервер долго не отвечает (таймаут 30 секунд)")
        err?.let { throw NcodeError("нет ответа от `$url` — проверь адрес и интернет") }
        return res ?: throw NcodeError("нет ответа от `$url` — проверь адрес и интернет")
    }

    private fun hasSaveMarker(s: String): Boolean {
        val ws = wordsOutsideQuotes(s)
        for (i in ws.indices) {
            for (m in saveMarkers) {
                if (i + m.size > ws.size) continue
                var ok = true
                for (k in m.indices) {
                    if (!ws[i + k].text.equals(m[k], ignoreCase = true)) {
                        ok = false
                        break
                    }
                }
                if (ok) return true
            }
        }
        return false
    }

    private fun runZapros(line: String) {
        val usage = "нужно: запрос <гет|пост|пут|делит|патч> <адрес> [данные <тело>] сохранить в <имя>"
        var rest = keywordTail(line, "запрос").trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val sp = rest.indexOfFirst { it.isWhitespace() }
        val methodRaw = if (sp < 0) rest else rest.substring(0, sp)
        val method = when (methodRaw.lowercase(java.util.Locale.ROOT)) {
            "гет", "get" -> "GET"
            "пост", "post" -> "POST"
            "пут", "put" -> "PUT"
            "делит", "delete" -> "DELETE"
            "патч", "patch" -> "PATCH"
            else -> throw NcodeError("не знаю метод `$methodRaw` (можно: гет, пост, пут, делит, патч)")
        }
        if (sp < 0) throw NcodeError(usage)
        rest = rest.substring(sp).trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val sp2 = rest.indexOfFirst { it.isWhitespace() }
        val urlRaw = if (sp2 < 0) rest else rest.substring(0, sp2)
        val middle = if (sp2 < 0) "" else rest.substring(sp2).trim()
        val url = if (urlRaw.length >= 2 && urlRaw.startsWith("\"") && urlRaw.endsWith("\"")) {
            val inner = urlRaw.substring(1, urlRaw.length - 1).trim()
            if (!nameRegex.matches(inner)) throw NcodeError("плохое имя в кавычках `\"$inner\"`")
            vars[inner.lowercase(java.util.Locale.ROOT)] ?: throw NcodeError("нет переменной \"$inner\"")
        } else urlRaw
        val lowUrl = url.lowercase(java.util.Locale.ROOT)
        if (!lowUrl.startsWith("http://") && !lowUrl.startsWith("https://")) throw NcodeError("адрес должен начинаться с http:// или https://")
        if (middle.isEmpty() || !hasSaveMarker(middle)) throw NcodeError(usage + ", пример: запрос гет " + url + " сохранить в ответ")
        val (before, name) = try {
            splitAsk(middle)
        } catch (e: NcodeError) {
            throw NcodeError(usage)
        }
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        val body: String? = if (before.isEmpty()) {
            null
        } else {
            val bt = before.trim()
            val bsp = bt.indexOfFirst { it.isWhitespace() }
            if (bsp < 0 || !bt.substring(0, bsp).equals("данные", ignoreCase = true)) throw NcodeError("нужно: ... данные <тело> сохранить в <имя>")
            val btBody = bt.substring(bsp).trim()
            if (btBody.isEmpty()) throw NcodeError("после `данные` нужно тело запроса")
            evalExpression(btBody)
        }
        if (dryRun) {
            vars[name.lowercase(java.util.Locale.ROOT)] = "1"
            lastHttpCode = 200
            return
        }
        val r = httpCall(method, url, body)
        vars[name.lowercase(java.util.Locale.ROOT)] = r.body
        lastHttpCode = r.code
    }

    private fun encodeJsonValue(v: String): String {
        val t = v.trim()
        if (numberRegex.matches(t)) return t
        val low = t.lowercase(java.util.Locale.ROOT)
        if (low == "истина" || low == "да" || low == "правда") return "true"
        if (low == "ложь" || low == "нет" || low == "неправда") return "false"
        if (low == "null") return "null"
        val sb = StringBuilder("\"")
        for (c in t) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u" + c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    private fun decodeJsonValue(body: String): String {
        val t = body.trim()
        if (t.isEmpty() || t == "null") return ""
        if (t == "true") return "истина"
        if (t == "false") return "ложь"
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    when (inner[i + 1]) {
                        '"', '\\', '/' -> {
                            sb.append(inner[i + 1])
                            i += 2
                        }
                        'n' -> {
                            sb.append('\n')
                            i += 2
                        }
                        'r' -> {
                            sb.append('\r')
                            i += 2
                        }
                        't' -> {
                            sb.append('\t')
                            i += 2
                        }
                        'b' -> {
                            sb.append('\b')
                            i += 2
                        }
                        'f' -> {
                            sb.append('\u000C')
                            i += 2
                        }
                        'u' -> if (i + 5 < inner.length) {
                            sb.append(inner.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?')
                            i += 6
                        } else {
                            sb.append(c)
                            i++
                        }
                        else -> {
                            sb.append(c)
                            i++
                        }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
        return t
    }

    private fun bazaToken(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1).trim()
            if (!nameRegex.matches(inner)) throw NcodeError("плохое имя в кавычках `\"$inner\"`")
            return vars[inner.lowercase(java.util.Locale.ROOT)] ?: throw NcodeError("нет переменной \"$inner\"")
        }
        return t
    }

    private fun normBaseUrl(raw: String): String {
        return checkBaseUrl(bazaToken(raw))
    }

    private fun checkBaseUrl(u: String): String {
        if (u.isEmpty()) throw NcodeError("пустой адрес базы")
        val withScheme = if (u.contains("://")) u else "https://$u"
        val low = withScheme.lowercase(java.util.Locale.ROOT)
        if (!low.startsWith("http://") && !low.startsWith("https://")) throw NcodeError("адрес базы должен быть http(s)-ссылкой")
        return withScheme.trimEnd('/')
    }

    private fun normBasePath(raw: String): String {
        return checkBasePath(bazaToken(raw))
    }

    private fun checkBasePath(p: String): String {
        return p.trim().trim('/')
    }

    private fun bazaAddrPath(first: String, second: String): Pair<String, String> {
        val v1 = bazaToken(first)
        val v2 = bazaToken(second)
        val u1 = v1.contains("://") || v1.contains(".")
        val u2 = v2.contains("://") || v2.contains(".")
        return if (!u1 && u2) {
            Pair(checkBaseUrl(v2), checkBasePath(v1))
        } else {
            Pair(checkBaseUrl(v1), checkBasePath(v2))
        }
    }

    private fun encodeBasePath(path: String): String {
        if (path.isEmpty()) return ""
        return path.split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }

    private fun runZapisatBazu(line: String) {
        return runWriteBaza(line, "записать в базу", false)
    }

    private fun runWriteBaza(line: String, kw: String, onlyNew: Boolean) {
        val usage = if (onlyNew) "нужно: создать в базе <адрес> <путь> <значение> (можно: <путь> <адрес>)" else "нужно: записать в базу <адрес> <путь> <значение> (можно: <путь> <адрес>)"
        val rest = keywordTail(line, kw).trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val parts = rest.split(Regex("\\s+"), limit = 3)
        if (parts.size < 3 || parts[2].trim().isEmpty()) throw NcodeError(usage + ", пример: " + kw + " https://моя-игра.firebaseio.com очки/рекорд 100")
        val (base, path) = bazaAddrPath(parts[0], parts[1])
        if (onlyNew && path.isEmpty()) throw NcodeError("нужен путь (ключ), пример: очки/рекорд")
        val value = evalExpression(parts[2].trim())
        if (dryRun) return
        if (onlyNew) {
            val cur = httpCall("GET", base + "/" + encodeBasePath(path) + ".json", null)
            if (cur.code >= 400) throw NcodeError("база отказала (код " + cur.code + ") — проверь правила доступа и путь")
            if (decodeJsonValue(cur.body).isNotEmpty()) throw NcodeError("уже есть `" + path + "` — `записать в базу` перезапишет")
        }
        val r = httpCall("PUT", base + "/" + encodeBasePath(path) + ".json", encodeJsonValue(value))
        if (r.code >= 400) throw NcodeError("база отказала (код " + r.code + ") — проверь правила доступа и путь")
    }

    private fun runSozdatBazu(line: String) {
        return runWriteBaza(line, "создать в базе", true)
    }

    private fun runProchitatBazu(line: String) {
        val usage = "нужно: прочитать базу <адрес> <путь> сохранить в <имя> (можно: прочитать базу <путь> <адрес> сохранить в <имя>)"
        val rest = keywordTail(line, "прочитать базу").trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val parts = rest.split(Regex("\\s+"), limit = 3)
        if (parts.size < 3) throw NcodeError(usage + ", пример: прочитать базу https://моя-игра.firebaseio.com очки/рекорд сохранить в рекорд")
        val (base, path) = bazaAddrPath(parts[0], parts[1])
        val tail = parts[2].trim()
        if (!hasSaveMarker(tail)) throw NcodeError(usage)
        val (before, name) = try {
            splitAsk(tail)
        } catch (e: NcodeError) {
            throw NcodeError(usage)
        }
        if (before.isNotEmpty()) throw NcodeError(usage)
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        if (dryRun) {
            vars[name.lowercase(java.util.Locale.ROOT)] = "1"
            return
        }
        val r = httpCall("GET", base + "/" + encodeBasePath(path) + ".json", null)
        if (r.code >= 400) throw NcodeError("база отказала (код " + r.code + ") — проверь правила доступа и путь")
        vars[name.lowercase(java.util.Locale.ROOT)] = decodeJsonValue(r.body)
    }

    private fun runUdalitBazu(line: String) {
        val usage = "нужно: удалить из базы <адрес> <путь> (можно: удалить из базы <путь> <адрес>)"
        val rest = keywordTail(line, "удалить из базы").trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val parts = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != 2) throw NcodeError(usage + ", пример: удалить из базы https://моя-игра.firebaseio.com очки/рекорд")
        val (base, path) = bazaAddrPath(parts[0], parts[1])
        if (dryRun) return
        val r = httpCall("DELETE", base + "/" + encodeBasePath(path) + ".json", null)
        if (r.code >= 400) throw NcodeError("база отказала (код " + r.code + ") — проверь правила доступа и путь")
    }

    private val dryWritten = mutableSetOf<String>()

    private fun readFileText(path: String): String {
        return platform.files.readText(path)
    }

    private fun readFileTextOrDry(path: String): String {
        if (!dryRun || platform.files.isFile(path)) return readFileText(path)
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
        var costumes: MutableList<GfxImage>,
        var costume: Int,
        var visible: Boolean,
        var circle: Boolean = false,
        var text: String? = null,
        var vel: Double = 0.0,
        var lastMove: Long = 0,
        var bodyType: Int = 1,
        var mass: Double = 1.0,
        var rest: Double = 0.2,
        var fric: Double = 0.5,
        var linDamp: Double = 0.0,
        var angDamp: Double = 0.0,
        var fixedRot: Boolean = false,
        var gravScale: Double = 1.0,
        var bullet: Boolean = false,
        var vx: Double = 0.0,
        var vy: Double = 0.0,
        var av: Double = 0.0,
        var colType: Int = 0,
        var colW: Double = 0.0,
        var colH: Double = 0.0,
        var colR: Double = 0.0,
        var colDx: Double = 0.0,
        var colDy: Double = 0.0,
        var isTrigger: Boolean = false,
        var lastHit: Double = 0.0,
        var penDown: Boolean = false,
        var penR: Int = 0,
        var penG: Int = 0,
        var penB: Int = 0,
        var penSize: Int = 1
    )

    private var gfxEverOpened = false
    private var gfxW = 800
    private var gfxH = 600
    private var windowScalable = false
    private var camFollow: String? = null
    private var camX = 0.0
    private var camY = 0.0
    private var bgR = 0
    private var bgG = 0
    private var bgB = 0
    private var gravX = 0.0
    private var gravY = -9.8
    private var airDens = 0.0
    private var lastCollX = 0.0
    private var lastCollY = 0.0
    private data class Joint(val id: String, var a: String, var b: String, var type: Int, var len: Double = 0.0, var k: Double = 0.0, var d: Double = 0.0, var x: Double = 0.0, var y: Double = 0.0, var minA: Double = 0.0, var maxA: Double = 0.0, var motSpeed: Double = 0.0, var motForce: Double = 0.0, var breakF: Double = Double.MAX_VALUE)
    private val joints = mutableMapOf<String, Joint>()
    private var jointSeq = 0
    private val gfxObjs = mutableListOf<GObj>()
    private val penLines = mutableListOf<PenSeg>()
    private val gfxLock = Object()
    private var lastDrawn: String? = null
    @Volatile private var closeRequested = false

    private fun requireWindow() {
        if (!platform.gfx.isOpen()) throw NcodeError("сначала создать окно")
    }

    fun isWindowOpen(): Boolean {
        return platform.gfx.isOpen()
    }

    private data class VarSlot(
        val x: Double,
        val y: Double,
        val size: Double,
        val alpha: Int,
        val r: Int,
        val g: Int,
        val b: Int,
        val visible: Boolean
    )

    private val varShows = mutableMapOf<String, VarSlot>()

    private data class Svet(
        val name: String,
        var x: Double,
        var y: Double,
        var size: Double,
        var r: Int,
        var g: Int,
        var b: Int,
        var power: Int,
        var flicker: Int,
        var visible: Boolean,
        var follow: String? = null
    )

    private val svetObjs = mutableListOf<Svet>()
    private var darkness = 0

    private fun applyCamera() {
        val target = camFollow ?: return
        val o = synchronized(gfxLock) { gfxObjs.find { it.name == target }?.copy() } ?: return
        camX = o.x
        camY = o.y
        try {
            platform.gfx.setCamera(camX, camY)
        } catch (e: Exception) {
        }
    }

    private fun applyLights() {
        synchronized(gfxLock) {
            for (s in svetObjs) {
                val t = s.follow ?: continue
                val o = gfxObjs.find { it.name == t } ?: continue
                s.x = o.x
                s.y = o.y
            }
        }
    }

    private fun renderCurrent() {
        if (dryRun || !platform.gfx.isOpen()) return
        applyCamera()
        applyLights()
        val snap: List<GObj>
        val trails: List<PenSeg>
        val labels: List<VarLabel>
        val svetSnap: List<Svet>
        val dark: Int
        synchronized(gfxLock) {
            snap = gfxObjs.map { it.copy() }
            trails = penLines.toList()
            labels = varShows.mapNotNull { (name, s) ->
                if (!s.visible) null
                else VarLabel(vars[name] ?: return@mapNotNull null, s.x, s.y, s.size, s.alpha, s.r, s.g, s.b)
            }
            svetSnap = svetObjs.map { it.copy() }
            dark = darkness
        }
        platform.gfx.render(
            GfxFrame(
                bgR, bgG, bgB, trails,
                snap.map {
                    GfxObj(
                        it.name, it.x, it.y, it.size, it.rot, it.r, it.g, it.b, it.alpha,
                        it.costumes.toList(), it.costume, it.visible, it.circle, it.text
                    )
                },
                labels,
                svetSnap.map {
                    Light(it.name, it.x, it.y, it.size, it.r, it.g, it.b, it.power, it.flicker, it.visible)
                },
                dark
            )
        )
    }
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

    fun warnNoWindowForInput() {
        if (gfxEverOpened || warnedNoWindow) return
        if (keyHandlers.isEmpty() && keyUpHandlers.isEmpty() && mouseHandlers.isEmpty() && cloneHandlers.isEmpty() && timerHandlers.isEmpty()) return
        warnedNoWindow = true
        platform.console.printErr("Варнинг: события без окна не сработают — сначала создать окно")
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
        gfxW = w
        gfxH = h
        platform.gfx.openWindow(w, h, title, this) { closeRequested = true }
        try {
            platform.gfx.setResizable(windowScalable)
        } catch (_: Exception) {}
        gfxEverOpened = true
        renderCurrent()
    }

    private fun resetScene() {
        handlers.clear()
        keyHandlers.clear()
        keyUpHandlers.clear()
        mouseHandlers.clear()
        cloneHandlers.clear()
        procHandlers.clear()
        timerHandlers.clear()
        collHandlers.clear()
        trigHandlers.clear()
        collLast.clear()
        trigLast.clear()
        cloneCount.clear()
        kakListeners.clear()
        synchronized(keyQueue) { keyQueue.clear() }
        synchronized(keyUpQueue) { keyUpQueue.clear() }
        synchronized(mouseQueue) { mouseQueue.clear() }
        synchronized(keyQueue) { heldKeys.clear() }
        mouseDown = false
        synchronized(gfxLock) {
            gfxObjs.clear()
            penLines.clear()
            varShows.clear()
            svetObjs.clear()
        }
        joints.clear()
        jointSeq = 0
        darkness = 0
        lastDrawn = null
        gfxEverOpened = false
        gfxW = 800
        gfxH = 600
        bgR = 0
        bgG = 0
        bgB = 0
        gravX = 0.0
        gravY = -9.8
        airDens = 0.0
        lastCollX = 0.0
        lastCollY = 0.0
        lastData = ""
        lastHttpCode = 200
        windowScalable = false
        camFollow = null
        camX = 0.0
        camY = 0.0
        try {
            platform.gfx.setCamera(0.0, 0.0)
        } catch (_: Exception) {}
        broadcastDepth = 0
        procDepth = 0
        execFailed = false
        warnedCycle = false
        warnedNoWindow = false
        lastPhys = 0L
        lastLabelRender = 0L
        sndStopAll()
        synchronized(sndLock) { sndVol.clear() }
    }

    private fun readChildScript(rawPath: String): Pair<String, List<String>> {
        val cands = mutableListOf(rawPath)
        scriptStack.lastOrNull()?.let { top ->
            val s = top.replace('\\', '/')
            if (s.contains("/")) {
                val withBase = s.substringBeforeLast("/") + "/" + rawPath
                if (withBase != rawPath) cands.add(withBase)
            }
        }
        for (c in cands) {
            var canon: String? = null
            try {
                val f = resolveScriptFile(c, currentScriptDir())
                canon = try {
                    f.canonicalPath
                } catch (e: Exception) {
                    f.path
                }
            } catch (e: NcodeError) {
            }
            if (canon == null) {
                try {
                    if (platform.files.exists(c)) canon = c
                } catch (e: Exception) {
                }
            }
            if (canon == null) continue
            if (canon in scriptStack || c in scriptStack) throw NcodeError("круг: `$rawPath` уже запущен выше")
            return canon to platform.files.readText(canon).split("\n")
        }
        throw NcodeError("нет файла `$rawPath`")
    }

    private fun runZapustit(line: String) {
        val usage = "нужно: запустить <файл.ncode> [истина|ложь], пример: запустить уровень2.ncode"
        val rest = keywordTail(line, "запустить").trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size > 2) throw NcodeError(usage)
        val rawPath = toks[0]
        var wipe = false
        if (toks.size == 2) {
            val v = evalExpression(toks[1])
            wipe = when (v.lowercase(java.util.Locale.ROOT)) {
                "истина", "да", "правда", "1" -> true
                "ложь", "нет", "неправда", "0" -> false
                else -> throw NcodeError("тут нужно истина/ложь или 1/0, а тут `$v`")
            }
        }
        val (canon, childLines) = readChildScript(rawPath)
        if (dryRun) {
            if (wipe) {
                val probe = NcodeInterpreter(platform)
                probe.dryRun = true
                probe.checkLabel = canon
                probe.resetScripts(canon)
                for ((k, v) in vars) probe.vars[k] = v
                for ((k, v) in lists) probe.lists[k] = v.toMutableList()
                val skips = try {
                    probe.loadHandlers(childLines)
                } catch (e: NcodeError) {
                    reportError(1, e.message)
                    execFailed = true
                    return
                }
                if (probe.runLines(childLines, 1, skips) != 0) execFailed = true
                if (probe.checkHandlers() != 0) execFailed = true
                if (probe.checkKeyHandlers() != 0) execFailed = true
            } else {
                scriptStack.add(canon)
                try {
                    val skips = extractHandlers(childLines)
                    if (runLines(childLines, 1, skips) != 0) execFailed = true
                } finally {
                    scriptStack.removeAt(scriptStack.size - 1)
                }
            }
            return
        }
        if (!wipe) {
            scriptStack.add(canon)
            try {
                val skips = extractHandlers(childLines)
                runLines(childLines, 1, skips)
            } finally {
                if (scriptStack.isNotEmpty()) scriptStack.removeAt(scriptStack.size - 1)
            }
            return
        }
        resetScene()
        if (platform.gfx.isOpen()) {
            try {
                platform.gfx.closeWindow()
            } catch (_: Exception) {}
        }
        resetScripts(canon)
        val skips = loadHandlers(childLines)
        runLines(childLines, 1, skips)
        throw SceneStop()
    }

    private fun runMasshtabOkna(line: String) {
        val rest = keywordTail(line, "масштабирование окна").trim()
        if (rest.isEmpty()) throw NcodeError("нужно: масштабирование окна истина/ложь (можно 1/0)")
        val v = evalExpression(rest)
        val flag = when (v.lowercase(java.util.Locale.ROOT)) {
            "истина", "да", "правда", "1" -> true
            "ложь", "нет", "неправда", "0" -> false
            else -> throw NcodeError("тут нужно истина/ложь или 1/0, а тут `$v`")
        }
        windowScalable = flag
        if (dryRun) return
        if (platform.gfx.isOpen()) {
            try {
                platform.gfx.setResizable(flag)
            } catch (_: Exception) {}
        }
    }

    private fun runSozdatSvet(line: String) {
        val usage = "нужно: создать свет <имя> x y, пример: создать свет лампа 100 50"
        val rest = keywordTail(line, "создать свет").trim()
        val toks = if (rest.isEmpty()) emptyList() else rest.split(Regex("\\s+"))
        if (toks.size != 3) throw NcodeError(usage)
        if (!nameRegex.matches(toks[0])) throw NcodeError("плохое имя `${toks[0]}` (буквы/цифры/_ без кавычек)")
        val x = evalArithOperand(toks[1])
        val y = evalArithOperand(toks[2])
        val name = toks[0].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            if (svetObjs.any { it.name == name }) throw NcodeError("свет `$name` уже есть")
            svetObjs.add(Svet(name, x, y, 150.0, 255, 255, 255, 80, 0, true))
        }
        renderCurrent()
    }

    private fun isSvetProp(line: String): Boolean {
        val toks = line.trim().split(Regex("\\s+"))
        if (toks.size < 2) return false
        val a = toks[0].lowercase(java.util.Locale.ROOT)
        if (a != "задать" && a != "присвоить" && a != "сделать" && a != "изменить" && a != "поменять") return false
        val p = toks[1].lowercase(java.util.Locale.ROOT)
        if (p != "размер" && p != "цвет" && p != "силу" && p != "мерцание") return false
        for (x in toks) if (x.lowercase(java.util.Locale.ROOT) == "свету") return true
        return false
    }

    private fun runSvetProp(line: String) {
        val t = line.trim()
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        val afterAct = t.substring(j).trim()
        var k = 0
        while (k < afterAct.length && (afterAct[k].isLetterOrDigit() || afterAct[k] == '_')) k++
        val prop = afterAct.substring(0, k).lowercase(java.util.Locale.ROOT)
        val tail = afterAct.substring(k).trim()
        val toks = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var idx = -1
        for (q in toks.indices) if (toks[q].lowercase(java.util.Locale.ROOT) == "свету") idx = q
        if (idx < 0) throw NcodeError("нужно слово `свету`")
        if (idx + 1 != toks.size - 1) throw NcodeError("после `свету` нужно одно имя")
        val name = toks[idx + 1].lowercase(java.util.Locale.ROOT)
        val before = toks.take(idx)
        if (prop == "цвет") {
            val named = if (before.size == 1) namedColor(before[0]) else null
            if (before.size != 3 && named == null) throw NcodeError("нужно: задать цвет R G B свету <имя>")
            val r = if (named != null) named[0].toDouble() else evalArithOperand(before[0])
            val g = if (named != null) named[1].toDouble() else evalArithOperand(before[1])
            val b = if (named != null) named[2].toDouble() else evalArithOperand(before[2])
            for ((c, n) in listOf(r to "красный", g to "зелёный", b to "синий")) {
                if (c != kotlin.math.floor(c) || c < 0 || c > 255) throw NcodeError(n + " — целое 0..255")
            }
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = svetObjs.find { it.name == name } ?: throw NcodeError("свет не создан")
                o.r = r.toInt()
                o.g = g.toInt()
                o.b = b.toInt()
            }
            renderCurrent()
            return
        }
        if (before.isEmpty()) throw NcodeError("нужно: задать $prop свету <имя>")
        val v = evalExpression(before.joinToString(" "))
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        val d = v.toDouble()
        if (prop == "размер") {
            if (d <= 0) throw NcodeError("размер больше нуля")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = svetObjs.find { it.name == name } ?: throw NcodeError("свет не создан")
                o.size = d
            }
            renderCurrent()
            return
        }
        if (d != kotlin.math.floor(d) || d < 0 || d > 100) throw NcodeError("$prop — целое 0..100")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = svetObjs.find { it.name == name } ?: throw NcodeError("свет не создан")
            if (prop == "силу") o.power = d.toInt() else o.flicker = d.toInt()
        }
        renderCurrent()
    }

    private fun runTemnota(line: String) {
        val rest = keywordTail(line, "задать темноту").trim()
        if (rest.isEmpty()) throw NcodeError("нужно: задать темноту 0..100")
        val v = evalExpression(rest)
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        val d = v.toDouble()
        if (d != kotlin.math.floor(d) || d < 0 || d > 100) throw NcodeError("темнота — целое 0..100")
        darkness = d.toInt()
        if (dryRun) return
        renderCurrent()
    }

    private fun runPokazatSvet(line: String, keyword: String, show: Boolean) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: $keyword <имя>")
        if (!nameRegex.matches(rest)) throw NcodeError("плохое имя `$rest`")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = svetObjs.find { it.name == rest.lowercase(java.util.Locale.ROOT) } ?: throw NcodeError("свет не создан")
            o.visible = show
        }
        renderCurrent()
    }

    private fun runUdalitSvet(line: String) {
        val rest = keywordTail(line, "удалить свет").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: удалить свет <имя>")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val ix = svetObjs.indexOfFirst { it.name == rest.lowercase(java.util.Locale.ROOT) }
            if (ix < 0) throw NcodeError("свет не создан")
            svetObjs.removeAt(ix)
        }
        renderCurrent()
    }

    private fun runPrivyazat(line: String) {
        val usage = "нужно: привязать свет <имя> к объекту <цель>, пример: привязать свет фонарь к объекту игрок"
        val rest = keywordTail(line, "привязать свет").trim()
        val toks = if (rest.isEmpty()) emptyList() else rest.split(Regex("\\s+"))
        var ki = -1
        for (q in toks.indices) if (toks[q].lowercase(java.util.Locale.ROOT) == "к") ki = q
        if (ki < 0 || ki != 1 || toks.size !in 3..4) throw NcodeError(usage)
        if (!nameRegex.matches(toks[0])) throw NcodeError("плохое имя `${toks[0]}`")
        val target: String
        val afterK = toks.drop(ki + 1)
        if (afterK.size == 2 && (afterK[0].lowercase(java.util.Locale.ROOT) == "объекту" || afterK[0].lowercase(java.util.Locale.ROOT) == "обьекту")) {
            target = afterK[1]
        } else if (afterK.size == 1) {
            target = afterK[0]
        } else throw NcodeError(usage)
        if (!nameRegex.matches(target)) throw NcodeError("плохое имя `$target`")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = svetObjs.find { it.name == toks[0].lowercase(java.util.Locale.ROOT) } ?: throw NcodeError("свет не создан")
            o.follow = target.lowercase(java.util.Locale.ROOT)
        }
        renderCurrent()
    }

    private fun runOtvyazat(line: String) {
        val rest = keywordTail(line, "отвязать свет").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: отвязать свет <имя>")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = svetObjs.find { it.name == rest.lowercase(java.util.Locale.ROOT) } ?: throw NcodeError("свет не создан")
            o.follow = null
        }
    }

    private fun runSledit(line: String) {
        val usage = "нужно: следить за объектом <имя> истина/ложь (можно 1/0)"
        val rest = keywordTail(line, "следить").trim()
        if (rest.isEmpty()) throw NcodeError(usage)
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val name: String
        val flagRaw: String
        if (toks.size == 4 && toks[0].lowercase(java.util.Locale.ROOT) == "за" &&
            (toks[1].lowercase(java.util.Locale.ROOT) == "объектом" || toks[1].lowercase(java.util.Locale.ROOT) == "обьектом")
        ) {
            name = toks[2]
            flagRaw = toks[3]
        } else if (toks.size == 2) {
            name = toks[0]
            flagRaw = toks[1]
        } else throw NcodeError(usage + ", пример: следить за объектом игрок истина")
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        val v = evalExpression(flagRaw)
        val flag = when (v.lowercase(java.util.Locale.ROOT)) {
            "истина", "да", "правда", "1" -> true
            "ложь", "нет", "неправда", "0" -> false
            else -> throw NcodeError("тут нужно истина/ложь или 1/0, а тут `$v`")
        }
        if (flag) {
            camFollow = name.lowercase(java.util.Locale.ROOT)
        } else {
            camFollow = null
            camX = 0.0
            camY = 0.0
            if (!dryRun && platform.gfx.isOpen()) {
                try {
                    platform.gfx.setCamera(0.0, 0.0)
                } catch (_: Exception) {}
            }
        }
    }

    private fun isPhysZadat(low: String): Boolean {
        return low.startsWith("задать гравитацию") || low.startsWith("задать плотность воздуха") || low.startsWith("задать массу") || low.startsWith("задать упругость") || low.startsWith("задать трение") || low.startsWith("задать сопротивление воздуха") || low.startsWith("задать сопротивление вращению") || low.startsWith("задать масштаб гравитации") || low.startsWith("задать пулевой режим") || low.startsWith("задать скорость") || low.startsWith("задать угловую скорость") || low.startsWith("задать коллайдер") || low.startsWith("задать смещение коллайдера") || low.startsWith("задать прочность соединения")
    }

    private fun stripComment(s: String): String {
        var inQ = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '"') inQ = !inQ
            if (!inQ) {
                if (c == '#') return s.substring(0, i)
                if (c == '/' && i + 1 < s.length && s[i + 1] == '/' && (i == 0 || s[i - 1] != ':')) return s.substring(0, i)
            }
            i++
        }
        return s
    }

    private fun runZakrytOkno(line: String) {
        if (keywordTail(line, "закрыть окно").trim().isNotEmpty()) throw NcodeError("нужно: закрыть окно")
        if (dryRun) return
        if (!platform.gfx.isOpen()) throw NcodeError("окна нет")
        platform.gfx.closeWindow()
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

    private fun loadImage(path: String): GfxImage {
        val base = currentScriptDir()?.path
        return platform.assets.imageFor(path, base).second
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
                    penLines.add(PenSeg(old.x, old.y, spec.x, spec.y, old.penR, old.penG, old.penB, old.penSize))
                }
                old.x = spec.x
                old.y = spec.y
            } else {
                gfxObjs.add(GObj(key, spec.x, spec.y, 100.0, 0.0, 0, 0, 0, 100, mutableListOf(), 0, true))
            }
            lastDrawn = key
        }
        resolveStatic(key)
        renderCurrent()
    }

    private fun isObjectProp(line: String): Boolean {
        val toks = line.trim().split(Regex("\\s+"))
        if (toks.size < 2) return false
        val a = toks[0].lowercase(java.util.Locale.ROOT)
        if (a != "задать" && a != "присвоить" && a != "сделать" && a != "изменить" && a != "поменять") return false
        val p = toks[1].lowercase(java.util.Locale.ROOT)
        if (p != "прозрачность" && p != "размер" && p != "поворот" && p != "угол" && p != "цвет" && p != "образ" && p != "костюм" && p != "текстура" && p != "текстуру" && p != "форма" && p != "слой" && p != "тип") return false
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
        if (prop == "образ" || prop == "костюм" || prop == "текстура" || prop == "текстуру") {
            val toks = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val name: String
            val path: String
            if (toks.size == 3 && toks[0].lowercase(java.util.Locale.ROOT) == "объекту") {
                name = toks[1].lowercase(java.util.Locale.ROOT)
                path = toks[2]
            } else if (toks.size == 3 && toks[1].lowercase(java.util.Locale.ROOT) == "объекту") {
                path = toks[0]
                name = toks[2].lowercase(java.util.Locale.ROOT)
            } else throw NcodeError("нужно: задать образ объекту <имя> <путь> или задать текстуру <путь> объекту <имя>")
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
                o.text = null
            }
            renderCurrent()
            return
        }
        if (prop == "форма") {
            val fb = splitPropTail(tail)
            if (fb.second.size != 1) throw NcodeError("нужно: задать форму круг|квадрат объекту <имя>")
            val shape = fb.first.joinToString(" ").trim().lowercase(java.util.Locale.ROOT)
            if (shape != "круг" && shape != "квадрат") throw NcodeError("нужно: задать форму круг|квадрат объекту <имя>")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == fb.second[0].lowercase(java.util.Locale.ROOT) } ?: throw NcodeError("объект не нарисован")
                o.circle = shape == "круг"
            }
            renderCurrent()
            return
        }
        if (prop == "слой") {
            val sb = splitPropTail(tail)
            if (sb.second.size != 1) throw NcodeError("нужно: задать слой <число> объекту <имя>")
            val vv = evalExpression(sb.first.joinToString(" "))
            if (!numberRegex.matches(vv)) throw NcodeError("тут нужно число, а тут `$vv`")
            val dd = vv.toDouble()
            if (dd != kotlin.math.floor(dd) || dd < 0) throw NcodeError("слой — целое от нуля")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val ix = gfxObjs.indexOfFirst { it.name == sb.second[0].lowercase(java.util.Locale.ROOT) }
                if (ix < 0) throw NcodeError("объект не нарисован")
                val o = gfxObjs.removeAt(ix)
                if (dd.toInt() > gfxObjs.size) throw NcodeError("слои 0.." + gfxObjs.size)
                gfxObjs.add(dd.toInt(), o)
            }
            renderCurrent()
            return
        }
        val (before, after) = splitPropTail(tail)
        if (after.size != 1) throw NcodeError("нужно: задать $prop объекту <имя>")
        val name = after[0].lowercase(java.util.Locale.ROOT)
        if (prop == "цвет") {
            val named = if (before.size == 1) namedColor(before[0]) else null
            if (before.size != 3 && named == null) throw NcodeError("нужно: задать цвет R G B объекту <имя> | задать цвет <имя цвета> объекту <имя>")
            val r = if (named != null) named[0].toDouble() else evalArithOperand(before[0])
            val g = if (named != null) named[1].toDouble() else evalArithOperand(before[1])
            val b = if (named != null) named[2].toDouble() else evalArithOperand(before[2])
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
            renderCurrent()
            return
        }
        if (before.isEmpty()) throw NcodeError("нужно: задать $prop объекту <имя>")
        if (before.size > 1 && (prop == "размер" || prop == "прозрачность" || prop == "поворот" || prop == "угол" || prop == "скорость")) {
            val hint = when (prop) {
                "размер" -> "размер — одно число (квадрат!). Прямоугольник: задай размер + задай коллайдер коробка ШИРИНА ВЫСОТА объекту <имя>"
                "прозрачность" -> "прозрачность — одно целое число 0..100"
                "скорость" -> "скорость — одно число"
                else -> "$prop — одно число (градусы)"
            }
            throw NcodeError("тут нужно одно число, а тут `" + before.joinToString(" ") + "` (" + hint + ")")
        }
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
            renderCurrent()
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
            renderCurrent()
            return
        }
        if (prop == "поворот" || prop == "угол") {
            if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.rot = ((v.toDouble() % 360) + 360) % 360
            }
            renderCurrent()
            return
        }
        if (prop == "скорость") {
            if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.vel = v.toDouble()
                o.lastMove = platform.clock.nanoTime()
            }
            return
        }
        if (prop == "тип") {
            val raw = before.joinToString(" ").trim().lowercase(java.util.Locale.ROOT).replace("ё", "е")
            val toks2 = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val valWord = when {
                toks2.size == 2 && toks2[0] == "движения" -> toks2[1]
                toks2.size == 1 -> toks2[0]
                else -> throw NcodeError("нужно: задать тип движения динамичный|статичный объекту <имя>")
            }
            val isStat = when {
                valWord.startsWith("стат") -> true
                valWord.startsWith("дин") -> false
                else -> throw NcodeError("нужно: задать тип движения динамичный|статичный объекту <имя>")
            }
            if (dryRun) return
            requireWindow()
            synchronized(gfxLock) {
                val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
                o.bodyType = if (isStat) 1 else 0
            }
            renderCurrent()
            return
        }
        throw NcodeError("неизвестная команда")
    }

    private fun runPokazatPeremennoy(line: String) {
        val rest = keywordTail(line, "показать переменную").trim()
        val toks = if (rest.isEmpty()) emptyList() else rest.split(Regex("\\s+"))
        if (toks.size != 8) throw NcodeError("нужно: показать переменную <имя> x y размер прозрачность r g b")
        val name = toks[0].lowercase(java.util.Locale.ROOT)
        if (!nameRegex.matches(toks[0])) throw NcodeError("плохое имя `${toks[0]}`")
        if (!vars.containsKey(name)) throw NcodeError("нет переменной `${toks[0]}` — сначала `задать`")
        val x = evalArithOperand(toks[1])
        val y = evalArithOperand(toks[2])
        val sizeText = evalExpression(toks[3])
        if (!numberRegex.matches(sizeText)) throw NcodeError("тут нужно число, а тут `$sizeText`")
        if (sizeText.toDouble() <= 0) throw NcodeError("размер больше нуля")
        val alphaText = evalExpression(toks[4])
        if (!numberRegex.matches(alphaText)) throw NcodeError("тут нужно число, а тут `$alphaText`")
        val ad = alphaText.toDouble()
        if (ad != kotlin.math.floor(ad) || ad < 0 || ad > 100) throw NcodeError("прозрачность — целое 0..100")
        val r = evalArithOperand(toks[5])
        val g = evalArithOperand(toks[6])
        val b = evalArithOperand(toks[7])
        for ((c, n) in listOf(r to "красный", g to "зелёный", b to "синий")) {
            if (c != kotlin.math.floor(c) || c < 0 || c > 255) throw NcodeError(n + " — целое 0..255")
        }
        if (dryRun) {
            synchronized(gfxLock) {
                varShows[name] = VarSlot(x, y, sizeText.toDouble(), ad.toInt(), r.toInt(), g.toInt(), b.toInt(), true)
            }
            return
        }
        requireWindow()
        synchronized(gfxLock) {
            varShows[name] = VarSlot(x, y, sizeText.toDouble(), ad.toInt(), r.toInt(), g.toInt(), b.toInt(), true)
        }
        renderCurrent()
    }

    private fun runSkrytPeremennoy(line: String) {
        val rest = keywordTail(line, "скрыть переменную").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: скрыть переменную <имя>")
        val name = rest.lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val s = varShows[name] ?: throw NcodeError("переменная не показана")
            varShows[name] = s.copy(visible = false)
        }
        renderCurrent()
    }

    private fun lastDrawnKey(): String {
        return lastDrawn ?: throw NcodeError("нечего показать — сначала нарисовать")
    }

    private fun msMult(unit: String): Double {
        return when (unit.lowercase(java.util.Locale.ROOT)) {
            "секунда", "секунды", "секунд", "секунду" -> 1_000.0
            "минута", "минуты", "минут", "минуту" -> 60_000.0
            "час", "часа", "часов" -> 3_600_000.0
            else -> throw NcodeError(
                "не знаю единицу `$unit` " +
                "(можно: секунду/секунды/секунд, минуту/минуты/минут, час/часа/часов)"
            )
        }
    }

    private fun runKlon(line: String) {
        val rest = keywordTail(line, "создать клон").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: создать клон <имя>")
        val parent = rest.lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        val src = synchronized(gfxLock) {
            gfxObjs.find { it.name == parent }?.copy()
        } ?: throw NcodeError("объект не нарисован")
        var nm: String
        synchronized(gfxLock) {
            var n = (cloneCount[parent] ?: 1) + 1
            nm = parent + n
            while (gfxObjs.any { it.name == nm }) {
                n++
                nm = parent + n
            }
            cloneCount[parent] = n
            gfxObjs.add(GObj(nm, src.x, src.y, src.size, src.rot, src.r, src.g, src.b, src.alpha, src.costumes.toMutableList(), src.costume, src.visible, src.circle, src.text, src.vel, 0, src.bodyType, src.mass, src.rest, src.fric, src.linDamp, src.angDamp, src.fixedRot, src.gravScale, src.bullet, src.vx, src.vy, src.av, src.colType, src.colW, src.colH, src.colR, src.colDx, src.colDy, src.isTrigger, src.lastHit, false, src.penR, src.penG, src.penB, src.penSize))
            lastDrawn = nm
        }
        renderCurrent()
        if (procDepth >= 100) {
            warnCycle()
            return
        }
        procDepth++
        try {
            for (h in cloneHandlers.toList()) {
                if (h.parent == parent) {
                    if (runLines(h.lines, h.base) != 0) execFailed = true
                }
            }
        } finally {
            procDepth--
        }
    }

    private fun runUdalit(line: String) {
        val rest = keywordTail(line, "удалить").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: удалить <имя>")
        val name = rest.lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val ix = gfxObjs.indexOfFirst { it.name == name }
            if (ix < 0) throw NcodeError("объект не нарисован")
            gfxObjs.removeAt(ix)
            if (lastDrawn == name) lastDrawn = null
        }
        renderCurrent()
    }

    private fun runVyzvat(line: String) {
        val rest = keywordTail(line, "вызвать").trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: вызвать <имя>")
        val name = rest.lowercase(java.util.Locale.ROOT)
        val h = procHandlers.find { it.name == name } ?: throw NcodeError("нет процедуры `$rest` — сначала `чтобы $rest`")
        if (procDepth >= 100) {
            warnCycle()
            return
        }
        procDepth++
        try {
            if (runLines(h.lines, h.base) != 0) execFailed = true
        } finally {
            procDepth--
        }
    }

    private fun runSetGrav(line: String) {
        val rest = keywordTail(line, "задать гравитацию").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 2) throw NcodeError("нужно: задать гравитацию X Y")
        val x = evalArithOperand(toks[0])
        val y = evalArithOperand(toks[1])
        if (dryRun) return
        gravX = x
        gravY = y
    }

    private fun runSetAirDens(line: String) {
        val rest = keywordTail(line, "задать плотность воздуха").trim()
        if (rest.isEmpty()) throw NcodeError("нужно: задать плотность воздуха ЗНАЧЕНИЕ")
        val v = evalArithOperand(rest)
        if (v < 0) throw NcodeError("плотность >=0")
        if (dryRun) return
        airDens = v
    }

    private fun runApplyForce(line: String, impulse: Boolean) {
        val kw = if (impulse) "приложить импульс" else "приложить силу"
        val rest = keywordTail(line, kw).trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 4 || toks[2].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: $kw X Y объекту ИМЯ")
        val fx = evalArithOperand(toks[0])
        val fy = evalArithOperand(toks[1])
        val name = toks[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            if (o.bodyType == 1) return
            if (impulse) {
                o.vx += fx / o.mass
                o.vy += fy / o.mass
            } else {
                o.vx += fx / o.mass * 0.016
                o.vy += fy / o.mass * 0.016
            }
        }
    }

    private fun runApplyForceAt(line: String) {
        val rest = keywordTail(line, "приложить силу в точке").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 8 || toks[2].lowercase(java.util.Locale.ROOT) != "по" || toks[3].lowercase(java.util.Locale.ROOT) != "координатам" || toks[6].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: приложить силу в точке X Y по координатам PX PY объекту ИМЯ")
        val fx = evalArithOperand(toks[0])
        val fy = evalArithOperand(toks[1])
        val px = evalArithOperand(toks[4])
        val py = evalArithOperand(toks[5])
        val name = toks[7].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            if (o.bodyType == 1) return
            o.vx += fx / o.mass * 0.016
            o.vy += fy / o.mass * 0.016
            val dx = px - o.x
            val dy = py - o.y
            o.av += (dx * fy - dy * fx) / (o.mass * 100.0)
        }
    }

    private fun runTorque(line: String) {
        val rest = keywordTail(line, "приложить крутящий момент").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: приложить крутящий момент ЗНАЧЕНИЕ объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            if (o.bodyType == 1) return
            o.av += v / o.mass
        }
    }

    private fun runAttract2(line: String) {
        val low = line.lowercase(java.util.Locale.ROOT)
        val m = Regex("притянуть объект\\s+(\\S+)\\s+к объекту\\s+(\\S+)\\s+с силой\\s+(\\S+)").find(low) ?: throw NcodeError("нужно: притянуть объект ИМЯ1 к объекту ИМЯ2 с силой СИЛА")
        val n1 = m.groupValues[1].lowercase(java.util.Locale.ROOT)
        val n2 = m.groupValues[2].lowercase(java.util.Locale.ROOT)
        val f = evalArithOperand(m.groupValues[3])
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val a = gfxObjs.find { it.name == n1 } ?: throw NcodeError("объект не нарисован")
            val b = gfxObjs.find { it.name == n2 } ?: throw NcodeError("объект не нарисован")
            if (a.bodyType == 1 || b.bodyType == 1) return
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < 0.01) return
            val ux = dx / d
            val uy = dy / d
            a.vx += ux * f / a.mass * 0.016
            a.vy += uy * f / a.mass * 0.016
            b.vx -= ux * f / b.mass * 0.016
            b.vy -= uy * f / b.mass * 0.016
        }
    }

    private fun runStopForces(line: String) {
        val rest = keywordTail(line, "остановить все силы").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 2 || toks[0].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: остановить все силы объекту ИМЯ")
        val name = toks[1].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.vx = 0.0
            o.vy = 0.0
            o.av = 0.0
        }
    }

    private fun runMakeBody(line: String, type: Int) {
        val kw = when (type) {
            0 -> "сделать тело динамическим"
            1 -> "сделать тело статическим"
            else -> "сделать тело кинематическим"
        }
        val rest = keywordTail(line, kw).trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 2 || toks[0].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: $kw объекту ИМЯ")
        val name = toks[1].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.bodyType = type
        }
        renderCurrent()
    }

    private fun runSetMass(line: String) {
        val rest = keywordTail(line, "задать массу").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать массу МАССА объекту ИМЯ")
        val m = evalArithOperand(toks[0])
        if (m <= 0) throw NcodeError("масса >0")
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.mass = m
        }
    }

    private fun runSetRest(line: String) {
        val rest = keywordTail(line, "задать упругость").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать упругость 0..1 объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        if (v < 0 || v > 1) throw NcodeError("упругость 0..1")
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.rest = v
        }
    }

    private fun runSetFric(line: String) {
        val rest = keywordTail(line, "задать трение").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать трение ЗНАЧЕНИЕ объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        if (v < 0) throw NcodeError("трение >=0")
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.fric = v
        }
    }

    private fun runSetLinDamp(line: String) {
        val rest = keywordTail(line, "задать сопротивление воздуха").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать сопротивление воздуха ЗНАЧЕНИЕ объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        if (v < 0) throw NcodeError(">=0")
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.linDamp = v
        }
    }

    private fun runSetAngDamp(line: String) {
        val rest = keywordTail(line, "задать сопротивление вращению").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать сопротивление вращению ЗНАЧЕНИЕ объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        if (v < 0) throw NcodeError(">=0")
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.angDamp = v
        }
    }

    private fun runFixRot(line: String) {
        val rest = keywordTail(line, "зафиксировать вращение").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: зафиксировать вращение ИСТИНА/ЛОЖЬ объекту ИМЯ")
        val b = toBool(evalExpression(toks[0]))
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.fixedRot = b
        }
    }

    private fun runGravScale(line: String) {
        val rest = keywordTail(line, "задать масштаб гравитации").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать масштаб гравитации ЗНАЧЕНИЕ объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.gravScale = v
        }
    }

    private fun runBullet(line: String) {
        val rest = keywordTail(line, "задать пулевой режим").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать пулевой режим ИСТИНА/ЛОЖЬ объекту ИМЯ")
        val b = toBool(evalExpression(toks[0]))
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.bullet = b
        }
    }

    private fun runSetVel(line: String) {
        val rest = keywordTail(line, "задать скорость").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 4 || toks[2].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать скорость X Y объекту ИМЯ")
        val x = evalArithOperand(toks[0])
        val y = evalArithOperand(toks[1])
        val name = toks[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.vx = x
            o.vy = y
        }
    }

    private fun runSetAngVel(line: String) {
        val rest = keywordTail(line, "задать угловую скорость").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать угловую скорость СКОРОСТЬ объекту ИМЯ")
        val v = evalArithOperand(toks[0])
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.av = v
        }
    }

    private fun runTeleport(line: String) {
        val rest = keywordTail(line, "телепортировать").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 4 || toks[2].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: телепортировать X Y объекту ИМЯ")
        val x = evalArithOperand(toks[0])
        val y = evalArithOperand(toks[1])
        val name = toks[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.x = x
            o.y = y
            o.vx = 0.0
            o.vy = 0.0
        }
        resolveStatic(name)
        renderCurrent()
    }

    private fun runColliderBox(line: String) {
        val rest = keywordTail(line, "задать коллайдер коробка").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 4 || toks[2].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать коллайдер коробка ШИРИНА ВЫСОТА объекту ИМЯ")
        val w = evalArithOperand(toks[0])
        val h = evalArithOperand(toks[1])
        if (w <= 0 || h <= 0) throw NcodeError("размер >0")
        val name = toks[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.colType = 0
            o.colW = w
            o.colH = h
        }
    }

    private fun runColliderCircle(line: String) {
        val rest = keywordTail(line, "задать коллайдер круг").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать коллайдер круг РАДИУС объекту ИМЯ")
        val r = evalArithOperand(toks[0])
        if (r <= 0) throw NcodeError("радиус >0")
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.colType = 1
            o.colR = r
        }
    }

    private fun runColliderCapsule(line: String) {
        val rest = keywordTail(line, "задать коллайдер капсула").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 4 || toks[2].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать коллайдер капсула ШИРИНА ВЫСОТА объекту ИМЯ")
        val w = evalArithOperand(toks[0])
        val h = evalArithOperand(toks[1])
        if (w <= 0 || h <= 0) throw NcodeError("размер >0")
        val name = toks[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.colType = 2
            o.colW = w
            o.colH = h
        }
    }

    private fun runColliderOffset(line: String) {
        val rest = keywordTail(line, "задать смещение коллайдера").trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 4 || toks[2].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: задать смещение коллайдера DX DY объекту ИМЯ")
        val dx = evalArithOperand(toks[0])
        val dy = evalArithOperand(toks[1])
        val name = toks[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.colDx = dx
            o.colDy = dy
        }
    }

    private fun runMakeTrigger(line: String) {
        val low = line.lowercase(java.util.Locale.ROOT)
        val kw = if (low.startsWith("сделать триггером")) "сделать триггером" else throw NcodeError("?")
        val rest = keywordTail(line, kw).trim()
        val toks = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size != 3 || toks[1].lowercase(java.util.Locale.ROOT) != "объекту") throw NcodeError("нужно: сделать триггером ИСТИНА/ЛОЖЬ объекту ИМЯ")
        val b = toBool(evalExpression(toks[0]))
        val name = toks[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            o.isTrigger = b
        }
    }

    private fun runRope(line: String) {
        val m = Regex("соединить веревкой\\s+(\\S+)\\s+и\\s+(\\S+)\\s+длина\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line) ?: throw NcodeError("нужно: соединить веревкой ИМЯ1 и ИМЯ2 длина ДЛИНА")
        val a = m.groupValues[1].lowercase(java.util.Locale.ROOT)
        val b = m.groupValues[2].lowercase(java.util.Locale.ROOT)
        val len = evalArithOperand(m.groupValues[3])
        if (len <= 0) throw NcodeError("длина >0")
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            if (gfxObjs.none { it.name == a } || gfxObjs.none { it.name == b }) throw NcodeError("объект не нарисован")
        }
        val id = "j" + (++jointSeq)
        joints[id] = Joint(id, a, b, 0, len = len)
        vars["последнее соединение"] = id
    }

    private fun runSpring(line: String) {
        val m = Regex("соединить пружиной\\s+(\\S+)\\s+и\\s+(\\S+)\\s+жесткость\\s+(\\S+)\\s+гашение\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line) ?: throw NcodeError("нужно: соединить пружиной ИМЯ1 и ИМЯ2 жесткость К гашение D")
        val a = m.groupValues[1].lowercase(java.util.Locale.ROOT)
        val b = m.groupValues[2].lowercase(java.util.Locale.ROOT)
        val k = evalArithOperand(m.groupValues[3])
        val d = evalArithOperand(m.groupValues[4])
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            if (gfxObjs.none { it.name == a } || gfxObjs.none { it.name == b }) throw NcodeError("объект не нарисован")
        }
        val id = "j" + (++jointSeq)
        joints[id] = Joint(id, a, b, 1, k = k, d = d)
        vars["последнее соединение"] = id
    }

    private fun runHinge(line: String) {
        val m = Regex("соединить шарниром\\s+(\\S+)\\s+и\\s+(\\S+)\\s+в точке\\s+(\\S+)\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line) ?: throw NcodeError("нужно: соединить шарниром ИМЯ1 и ИМЯ2 в точке X Y")
        val a = m.groupValues[1].lowercase(java.util.Locale.ROOT)
        val b = m.groupValues[2].lowercase(java.util.Locale.ROOT)
        val x = evalArithOperand(m.groupValues[3])
        val y = evalArithOperand(m.groupValues[4])
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            if (gfxObjs.none { it.name == a } || gfxObjs.none { it.name == b }) throw NcodeError("объект не нарисован")
        }
        val id = "j" + (++jointSeq)
        joints[id] = Joint(id, a, b, 2, x = x, y = y)
        vars["последнее соединение"] = id
    }

    private fun runLimit(line: String) {
        val m = Regex("ограничить угол шарнира\\s+(\\S+)\\s+(\\S+)\\s+соединению\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line) ?: throw NcodeError("нужно: ограничить угол шарнира МИН МАКС соединению ID")
        val mn = evalArithOperand(m.groupValues[1])
        val mx = evalArithOperand(m.groupValues[2])
        val id = m.groupValues[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        val j = joints[id] ?: throw NcodeError("нет соединения `$id`")
        j.minA = mn
        j.maxA = mx
    }

    private fun runMotor(line: String) {
        val m = Regex("задать мотор шарнира\\s+(\\S+)\\s+сила\\s+(\\S+)\\s+соединению\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line) ?: throw NcodeError("нужно: задать мотор шарнира СКОРОСТЬ сила СИЛА соединению ID")
        val sp = evalArithOperand(m.groupValues[1])
        val fr = evalArithOperand(m.groupValues[2])
        val id = m.groupValues[3].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        val j = joints[id] ?: throw NcodeError("нет соединения `$id`")
        j.motSpeed = sp
        j.motForce = fr
    }

    private fun runDelJoint(line: String) {
        val rest = keywordTail(line, "удалить соединение").trim()
        if (rest.isEmpty()) throw NcodeError("нужно: удалить соединение ID")
        val id = rest.lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        if (joints.remove(id) == null) throw NcodeError("нет соединения `$id`")
    }

    private fun runBreak(line: String) {
        val m = Regex("задать прочность соединения\\s+(\\S+)\\s+соединению\\s+(\\S+)", RegexOption.IGNORE_CASE).find(line) ?: throw NcodeError("нужно: задать прочность соединения СИЛА_РАЗРЫВА соединению ID")
        val f = evalArithOperand(m.groupValues[1])
        val id = m.groupValues[2].lowercase(java.util.Locale.ROOT)
        if (dryRun) return
        val j = joints[id] ?: throw NcodeError("нет соединения `$id`")
        j.breakF = f
    }

    private fun runRay(line: String): String {
        val low = line.lowercase(java.util.Locale.ROOT)
        val m = Regex("бросить луч от\\s+(\\S+)\\s+(\\S+)\\s+до\\s+(\\S+)\\s+(\\S+)\\s+пересекает\\s+(\\S+)").find(low) ?: throw NcodeError("нужно: бросить луч от X1 Y1 до X2 Y2 пересекает ИМЯ")
        val x1 = evalArithOperand(m.groupValues[1])
        val y1 = evalArithOperand(m.groupValues[2])
        val x2 = evalArithOperand(m.groupValues[3])
        val y2 = evalArithOperand(m.groupValues[4])
        val name = m.groupValues[5].lowercase(java.util.Locale.ROOT)
        if (dryRun) return "ложь"
        val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
        val b = objBox(name) ?: return "ложь"
        val hit = segmentsIntersect(x1, y1, x2, y2, b[0], b[2], b[1], b[2]) || segmentsIntersect(x1, y1, x2, y2, b[1], b[2], b[1], b[3]) || segmentsIntersect(x1, y1, x2, y2, b[1], b[3], b[0], b[3]) || segmentsIntersect(x1, y1, x2, y2, b[0], b[3], b[0], b[2])
        return if (hit) "истина" else "ложь"
    }

    private fun segmentsIntersect(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        val d1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val d2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax)
        val d3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx)
        val d4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx)
        return (d1 * d2 < 0 && d3 * d4 < 0)
    }

    private fun runRayDist(args: List<String>): String {
        if (args.size != 5) throw NcodeError("нужно: дистанция луча до ИМЯ от X Y в направлении УГОЛ")
        val name = args[0].lowercase(java.util.Locale.ROOT)
        val x = evalArithOperand(args[1])
        val y = evalArithOperand(args[2])
        val ang = evalArithOperand(args[3])
        if (dryRun) return "0"
        val b = objBox(name) ?: throw NcodeError("объект не нарисован")
        val rad = Math.toRadians(ang)
        val farX = x + Math.cos(rad) * 10000
        val farY = y + Math.sin(rad) * 10000
        val cx = (b[0] + b[1]) / 2
        val cy = (b[2] + b[3]) / 2
        return fmtNum(Math.hypot(cx - x, cy - y))
    }

    private fun runPokazat(line: String, keyword: String, show: Boolean) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isNotEmpty() && rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно одно имя")
        if (dryRun) return
        val name = if (rest.isEmpty()) lastDrawnKey() else rest.lowercase(java.util.Locale.ROOT)
        requireWindow()
        synchronized(gfxLock) {
            val live = gfxObjs.find { it.name == name }
            if (live != null) {
                live.visible = show
                renderCurrent()
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
            penLines.clear()
            varShows.clear()
            svetObjs.clear()
        }
        renderCurrent()
    }

    private fun isBg(line: String): Boolean {
        val toks = line.trim().split(Regex("\\s+"))
        if (toks.size < 2) return false
        val a = toks[0].lowercase(java.util.Locale.ROOT)
        if (a != "задать" && a != "присвоить" && a != "сделать" && a != "изменить" && a != "поменять") return false
        return toks[1].lowercase(java.util.Locale.ROOT) == "фон"
    }

    private fun namedColor(w: String): IntArray? {
        return when (w.lowercase(java.util.Locale.ROOT).replace("ё", "е")) {
            "красный" -> intArrayOf(255, 0, 0)
            "зеленый" -> intArrayOf(0, 255, 0)
            "синий" -> intArrayOf(0, 0, 255)
            "белый" -> intArrayOf(255, 255, 255)
            "черный" -> intArrayOf(0, 0, 0)
            "желтый" -> intArrayOf(255, 255, 0)
            "оранжевый" -> intArrayOf(255, 165, 0)
            "фиолетовый" -> intArrayOf(128, 0, 128)
            "розовый" -> intArrayOf(255, 192, 203)
            "серый" -> intArrayOf(128, 128, 128)
            "голубой" -> intArrayOf(0, 255, 255)
            else -> null
        }
    }

    private fun runBg(line: String) {
        val t = line.trim()
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        val toks = t.substring(j).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if ((toks.size != 4 && toks.size != 2) || toks[0].lowercase(java.util.Locale.ROOT) != "фон") throw NcodeError("нужно: задать фон R G B | задать фон <цвет>")
        val named = if (toks.size == 2) namedColor(toks[1]) else null
        if (toks.size == 2 && named == null) throw NcodeError("не знаю цвет `" + toks[1] + "`")
        val r = if (named != null) named[0].toDouble() else evalArithOperand(toks[1])
        val g = if (named != null) named[1].toDouble() else evalArithOperand(toks[2])
        val b = if (named != null) named[2].toDouble() else evalArithOperand(toks[3])
        for ((c, n) in listOf(r to "красный", g to "зелёный", b to "синий")) {
            if (c != kotlin.math.floor(c) || c < 0 || c > 255) throw NcodeError(n + " — целое 0..255")
        }
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            bgR = r.toInt()
            bgG = g.toInt()
            bgB = b.toInt()
        }
        renderCurrent()
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

    private fun runDvigat(line: String) {
        val rest = keywordTail(line, "двигать").trim()
        var obj: String? = null
        if (rest.isNotEmpty()) {
            val split = splitObjEnd(rest)
            if (split.first.isNotEmpty() || split.second == null) throw NcodeError("нужно: двигать [объекту <имя>]")
            obj = split.second
        }
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            val now = platform.clock.nanoTime()
            var dt = (now - o.lastMove).toDouble() / 1_000_000_000.0
            o.lastMove = now
            if (dt < 0) dt = 0.0
            if (dt > 1.0) dt = 1.0
            val steps = o.vel * dt
            if (steps != 0.0) {
                val rad = Math.toRadians(o.rot)
                val nx = o.x + steps * Math.cos(rad)
                val ny = o.y + steps * Math.sin(rad)
                if (o.penDown && (nx != o.x || ny != o.y)) {
                    penLines.add(PenSeg(o.x, o.y, nx, ny, o.penR, o.penG, o.penB, o.penSize))
                }
                o.x = nx
                o.y = ny
            }
        }
        resolveStatic(key)
        renderCurrent()
    }

    private fun runOtskochit(line: String) {
        val rest = keywordTail(line, "отскочить от края").trim()
        var obj: String? = null
        if (rest.isNotEmpty()) {
            val split = splitObjEnd(rest)
            if (split.first.isNotEmpty() || split.second == null) throw NcodeError("нужно: отскочить от края [объекту <имя>]")
            obj = split.second
        }
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            val b = objBox(key) ?: throw NcodeError("объект не нарисован")
            val hitX = b[0] <= -gfxW / 2.0 || b[1] >= gfxW / 2.0
            val hitY = b[2] <= -gfxH / 2.0 || b[3] >= gfxH / 2.0
            if (!hitX && !hitY) return
            var rot = o.rot
            if (hitX) rot = 180.0 - rot
            if (hitY) rot = -rot
            o.rot = ((rot % 360) + 360) % 360
        }
        renderCurrent()
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
                penLines.add(PenSeg(o.x, o.y, nx, ny, o.penR, o.penG, o.penB, o.penSize))
            }
            o.x = nx
            o.y = ny
        }
        resolveStatic(key)
        renderCurrent()
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
        renderCurrent()
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
                    penLines.add(PenSeg(obj.x, obj.y, nx, ny, obj.penR, obj.penG, obj.penB, obj.penSize))
                }
                obj.x = nx
                obj.y = ny
            }
            resolveStatic(key)
            renderCurrent()
            try {
                platform.clock.sleepMs(sleepMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw NcodeError("ожидание прервано")
            }
            checkEvents()
        }
        renderCurrent()
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
        if (tail.size == 3 && tail[1].lowercase(java.util.Locale.ROOT) == "объекту") {
            rgb = listOf(tail[0])
            obj = tail[2].lowercase(java.util.Locale.ROOT)
        }
        else if (tail.size == 3) rgb = tail
        else if (tail.size == 1) rgb = tail
        else if (tail.size == 5 && tail[3].lowercase(java.util.Locale.ROOT) == "объекту") {
            rgb = tail.take(3)
            obj = tail[4].lowercase(java.util.Locale.ROOT)
        } else throw NcodeError("нужно: цвет пера R G B [объекту <имя>] | цвет пера <имя цвета> [объекту <имя>]")
        val named = if (rgb.size == 1) namedColor(rgb[0]) else null
        if (rgb.size == 1 && named == null) throw NcodeError("не знаю цвет `" + rgb[0] + "`")
        val r = if (named != null) named[0].toDouble() else evalArithOperand(rgb[0])
        val g = if (named != null) named[1].toDouble() else evalArithOperand(rgb[1])
        val b = if (named != null) named[2].toDouble() else evalArithOperand(rgb[2])
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
        renderCurrent()
    }

    private fun runSleduyushiy(line: String, keyword: String, step: Int) {
        val toks = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var obj: String? = null
        if (toks.size == 2) obj = null
        else if (toks.size == 4 && toks[2].lowercase(java.util.Locale.ROOT) == "объекту") obj = toks[3].lowercase(java.util.Locale.ROOT)
        else throw NcodeError("нужно: $keyword [объекту <имя>]")
        if (dryRun) return
        requireWindow()
        val key = obj ?: movingObjectKey()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == key } ?: throw NcodeError("объект не нарисован")
            if (o.costumes.isEmpty()) throw NcodeError("нет костюмов")
            o.costume = (o.costume + step + o.costumes.size) % o.costumes.size
        }
        renderCurrent()
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
        renderCurrent()
    }

    private data class SndPlay(val line: NcodeSoundLine, val thread: Thread, @Volatile var alive: Boolean, @Volatile var pos: Int = 0, val fmt: SndPcm? = null, val data: ByteArray? = null, val loop: Boolean = false)
    private val sndLock = Object()
    private val sndActive = mutableMapOf<String, MutableList<SndPlay>>()
    private val sndPaused = mutableMapOf<String, Pair<SndPcm, Int>>()
    private val sndVol = mutableMapOf<String, Int>()
    private val sndExts = setOf("mp3", "wav", "m4a", "ogg")
    private var musicCanon: String? = null
    private val maxSfx = 10

    private fun sndExt(path: String): String {
        val dot = path.lastIndexOf('.')
        if (dot < 0) throw NcodeError("нужно: звук .mp3 .wav .m4a .ogg")
        val e = path.substring(dot + 1).lowercase(java.util.Locale.ROOT)
        if (e !in sndExts) throw NcodeError("нужно: звук .mp3 .wav .m4a .ogg")
        return e
    }

    private fun sndCanon(path: String): String {
        return platform.files.realPath(resolveScriptFile(path, currentScriptDir()).path)
    }

    private fun sndStart(canon: String, pcm: SndPcm, wait: Boolean, from: Int = 0, loop: Boolean = false) {
        val data = pcm.bytes
        val line = platform.sound.openLine(pcm.rate, pcm.channels)
        line.setVolume(synchronized(sndLock) { sndVol[canon] ?: 100 })
        val holder = arrayOfNulls<SndPlay>(1)
        val th = Thread {
            try {
                line.start()
                var off = from
                do {
                    while (off < data.size) {
                        val h = holder[0] ?: break
                        if (!h.alive) break
                        line.write(data, off, minOf(8192, data.size - off))
                        off += 8192
                        h.pos = off
                    }
                    if (loop && (holder[0]?.alive == true)) {
                        off = 0
                        holder[0]?.pos = 0
                    } else break
                } while (true)
                line.drain()
            } catch (e: Exception) {
            } finally {
                try { line.stop() } catch (e: Exception) {}
                try { line.close() } catch (e: Exception) {}
                synchronized(sndLock) {
                    val l = sndActive[canon]
                    if (l != null) {
                        l.remove(holder[0])
                        if (l.isEmpty()) sndActive.remove(canon)
                    }
                }
                holder[0]?.alive = false
            }
        }
        th.isDaemon = true
        val p = SndPlay(line, th, true, from, pcm, data, loop)
        holder[0] = p
        synchronized(sndLock) {
            sndActive.getOrPut(canon) { mutableListOf() }.add(p)
        }
        th.start()
        if (wait) {
            while (p.alive) {
                if (closeRequested) {
                    sndStop(canon)
                    throw SceneStop()
                }
                try {
                    platform.clock.sleepMs(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    sndStop(canon)
                    throw NcodeError("ожидание прервано")
                }
                checkEvents()
            }
        }
    }

    private fun runPauza(line: String, keyword: String) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: пауза звук <путь>")
        val path = rest
        sndExt(path)
        if (dryRun) {
            resolveScriptFile(path, currentScriptDir())
            return
        }
        val canon = sndCanon(path)
        val ps = synchronized(sndLock) { (sndActive.remove(canon) ?: mutableListOf()).toList() }
        if (ps.isEmpty()) return
        for (p in ps) {
            p.alive = false
            try { p.line.stop() } catch (e: Exception) {}
            try { p.line.close() } catch (e: Exception) {}
        }
        try { ps[0].thread.join(500) } catch (e: Exception) {}
        val f = ps[0].fmt
        if (f != null) {
            synchronized(sndLock) {
                sndPaused[canon] = f to minOf(ps[0].pos, f.bytes.size)
            }
        }
    }

    private fun runProdolzhit(line: String, keyword: String) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isEmpty() || rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: продолжить звук <путь>")
        val path = rest
        sndExt(path)
        if (dryRun) {
            resolveScriptFile(path, currentScriptDir())
            return
        }
        val canon = sndCanon(path)
        val st = synchronized(sndLock) { sndPaused.remove(canon) } ?: return
        if (st.second >= st.first.bytes.size) return
        sndStart(canon, st.first, false, st.second)
    }

    private fun sndStop(canon: String) {
        val ps = synchronized(sndLock) {
            if (musicCanon == canon) musicCanon = null
            (sndActive.remove(canon) ?: mutableListOf()).toList()
        }
        for (p in ps) {
            p.alive = false
            try { p.line.stop() } catch (e: Exception) {}
            try { p.line.close() } catch (e: Exception) {}
        }
    }

    private fun sndStopAll() {
        val ps = synchronized(sndLock) {
            musicCanon = null
            sndPaused.clear()
            val all = sndActive.values.flatten()
            sndActive.clear()
            all
        }
        for (p in ps) {
            p.alive = false
            try { p.line.stop() } catch (e: Exception) {}
            try { p.line.close() } catch (e: Exception) {}
        }
    }

    private fun sndSfxCount(): Int {
        return synchronized(sndLock) { sndActive.values.sumOf { l -> l.count { !it.loop } } }
    }

    private fun isZvuk(w: String): Boolean {
        val l = w.lowercase(java.util.Locale.ROOT)
        return l == "звук" || l == "музыку"
    }

    private fun isZvuka(w: String): Boolean {
        val l = w.lowercase(java.util.Locale.ROOT)
        return l == "звука" || l == "музыки"
    }

    private fun runIgrat(line: String, keyword: String) {
        val toks = keywordTail(line, keyword).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var wait = false
        var path = ""
        if (toks.size == 2 && isZvuk(toks[0])) path = toks[1]
        else if (toks.size == 4 && isZvuk(toks[0]) && toks[1].lowercase(java.util.Locale.ROOT) == "и" && toks[2].lowercase(java.util.Locale.ROOT) == "ждать") {
            wait = true
            path = toks[3]
        } else throw NcodeError("нужно: играть звук <путь> | играть звук и ждать <путь>")
        val ext = sndExt(path)
        val isMusic = toks[0].lowercase(java.util.Locale.ROOT) == "музыку"
        if (isMusic && wait) throw NcodeError("музыка крутится по кругу — `и ждать` нельзя, она бесконечная")
        if (dryRun) {
            resolveScriptFile(path, currentScriptDir())
            return
        }
        val canon = sndCanon(path)
        val dec = platform.sound.decodeFile(path, canon, ext)
        if (isMusic) {
            val old = synchronized(sndLock) { musicCanon }
            if (old != null) sndStop(old)
            sndStart(canon, dec, false, 0, true)
            synchronized(sndLock) { musicCanon = canon }
            return
        }
        if (sndSfxCount() >= maxSfx) return
        sndStart(canon, dec, wait)
    }

    private fun runOstanovit(line: String, keyword: String) {
        val rest = keywordTail(line, keyword).trim()
        val isMusicKw = keyword.lowercase(java.util.Locale.ROOT).contains("музыку")
        if (rest.isEmpty()) {
            if (!isMusicKw) throw NcodeError("нужно: остановить звук <путь> (для музыки можно без пути: остановить музыку)")
            if (dryRun) return
            val mc = synchronized(sndLock) { musicCanon }
            if (mc != null) sndStop(mc)
            return
        }
        if (rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: остановить звук <путь>")
        sndExt(rest)
        if (dryRun) {
            resolveScriptFile(rest, currentScriptDir())
            return
        }
        sndStop(sndCanon(rest))
    }

    private fun isSoundVol(line: String): Boolean {
        val toks = line.trim().split(Regex("\\s+"))
        if (toks.size < 2) return false
        val a = toks[0].lowercase(java.util.Locale.ROOT)
        if (a != "задать" && a != "присвоить" && a != "сделать" && a != "изменить" && a != "поменять") return false
        if (toks[1].lowercase(java.util.Locale.ROOT) != "громкость") return false
        for (x in toks) if (x.lowercase(java.util.Locale.ROOT) == "для") return true
        return false
    }

    private fun runGromkost(line: String) {
        val t0 = line.trim()
        var i = 0
        while (i < t0.length && (t0[i].isLetterOrDigit() || t0[i] == '_')) i++
        val t = t0.substring(i).trim()
        var j = 0
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        val tail = t.substring(j).trim()
        val toks = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var didx = -1
        for (q in toks.indices) if (toks[q].lowercase(java.util.Locale.ROOT) == "для") didx = q
        if (didx < 0) throw NcodeError("нужно: задать громкость <число> для звука <путь>")
        val after = toks.drop(didx + 1)
        if (after.size != 2 || !isZvuka(after[0])) throw NcodeError("нужно: задать громкость <число> для звука <путь>")
        val vexpr = toks.take(didx).joinToString(" ")
        if (vexpr.isEmpty()) throw NcodeError("нужно: задать громкость <число> для звука <путь>")
        val v = evalExpression(vexpr)
        if (!numberRegex.matches(v)) throw NcodeError("тут нужно число, а тут `$v`")
        val d = v.toDouble()
        if (d != kotlin.math.floor(d) || d < 0 || d > 100) throw NcodeError("громкость — целое 0..100")
        val path = after[1]
        sndExt(path)
        if (dryRun) {
            resolveScriptFile(path, currentScriptDir())
            return
        }
        val canon = sndCanon(path)
        synchronized(sndLock) {
            sndVol[canon] = d.toInt()
        }
        val ps = synchronized(sndLock) {
            sndActive[canon]?.toList() ?: emptyList()
        }
        for (p in ps) p.line.setVolume(d.toInt())
    }

    private fun runVerh(line: String, keyword: String, front: Boolean) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isNotEmpty() && rest.split(Regex("\\s+")).size != 1) throw NcodeError("нужно: $keyword [имя]")
        if (dryRun) return
        val name = if (rest.isEmpty()) lastDrawnKey() else rest.lowercase(java.util.Locale.ROOT)
        requireWindow()
        synchronized(gfxLock) {
            val ix = gfxObjs.indexOfFirst { it.name == name }
            if (ix < 0) throw NcodeError("объект не нарисован")
            val o = gfxObjs.removeAt(ix)
            if (front) gfxObjs.add(o) else gfxObjs.add(0, o)
        }
        renderCurrent()
    }

    private fun runNapisat(line: String, keyword: String) {
        val rest = keywordTail(line, keyword).trim()
        if (rest.isEmpty()) throw NcodeError("нужно: $keyword <имя> <текст>")
        val cut = rest.indexOfFirst { it.isWhitespace() }
        if (cut < 0) throw NcodeError("нужно: $keyword <имя> <текст>")
        val name = rest.substring(0, cut).lowercase(java.util.Locale.ROOT)
        val value = evalExpression(rest.substring(cut).trim())
        if (dryRun) return
        requireWindow()
        synchronized(gfxLock) {
            val o = gfxObjs.find { it.name == name } ?: throw NcodeError("объект не нарисован")
            if (value.isEmpty()) o.text = null
            else {
                o.text = value
                o.costumes.clear()
                o.costume = 0
            }
        }
        renderCurrent()
    }

    private fun runLine(line: String) {
        val low = line.lowercase()
        when {
            isObjectProp(line) ->
                runObjectProp(line)
            isSoundVol(line) ->
                runGromkost(line)
            isBg(line) ->
                runBg(line)
            isSvetProp(line) ->
                runSvetProp(line)
            low == "задать темноту" || low.startsWith("задать темноту ") || low.startsWith("задать темноту\t") ->
                runTemnota(line)
            (low == "задать" || low.startsWith("задать ") || low.startsWith("задать\t")) && !isPhysZadat(low) ->
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
            low == "создать папку" || low.startsWith("создать папку ") || low.startsWith("создать папку\t") ->
                runSozdatPapku(line)
            low == "добавить в список" || low.startsWith("добавить в список ") || low.startsWith("добавить в список\t") ->
                runDobavit(line)
            low == "убрать из списка" || low.startsWith("убрать из списка ") || low.startsWith("убрать из списка\t") ->
                runUbrat(line)
            low == "очистить список" || low.startsWith("очистить список ") || low.startsWith("очистить список\t") ->
                runOchistitSpisok(line)
            low == "перемешать список" || low.startsWith("перемешать список ") || low.startsWith("перемешать список\t") ->
                runPeremeshat(line)
            low == "стоп" || low.startsWith("стоп ") || low.startsWith("стоп\t") ->
                throw BreakSignal()
            low == "дальше" || low.startsWith("дальше ") || low.startsWith("дальше\t") ->
                throw ContinueSignal()
            low == "создать окно" || low.startsWith("создать окно ") || low.startsWith("создать окно\t") ->
                runSozdatOkno(line, "создать окно")
            low == "открыть окно" || low.startsWith("открыть окно ") || low.startsWith("открыть окно\t") ->
                runSozdatOkno(line, "открыть окно")
            low == "закрыть окно" ->
                runZakrytOkno(line)
            low == "масштабирование окна" || low.startsWith("масштабирование окна ") || low.startsWith("масштабирование окна\t") ->
                runMasshtabOkna(line)
            low == "следить" || low.startsWith("следить ") || low.startsWith("следить\t") ->
                runSledit(line)
            low == "запустить" || low.startsWith("запустить ") || low.startsWith("запустить\t") ->
                runZapustit(line)
            low == "нарисовать" || low.startsWith("нарисовать ") || low.startsWith("нарисовать\t") ||
                low == "рисовать" || low.startsWith("рисовать ") || low.startsWith("рисовать\t") ||
                low == "рисуй" || low.startsWith("рисуй ") || low.startsWith("рисуй\t") ->
                runRisovat(line)
            low == "показать переменную" || low.startsWith("показать переменную ") || low.startsWith("показать переменную\t") ->
                runPokazatPeremennoy(line)
            low == "скрыть переменную" || low.startsWith("скрыть переменную ") || low.startsWith("скрыть переменную\t") ->
                runSkrytPeremennoy(line)
            low == "показать свет" || low.startsWith("показать свет ") || low.startsWith("показать свет\t") ->
                runPokazatSvet(line, "показать свет", true)
            low == "показать" || low.startsWith("показать ") || low.startsWith("показать\t") ->
                runPokazat(line, "показать", true)
            low == "спрятать свет" || low.startsWith("спрятать свет ") || low.startsWith("спрятать свет\t") ->
                runPokazatSvet(line, "спрятать свет", false)
            low == "спрятать" || low.startsWith("спрятать ") || low.startsWith("спрятать\t") ->
                runPokazat(line, "спрятать", false)
            low == "скрыть свет" || low.startsWith("скрыть свет ") || low.startsWith("скрыть свет\t") ->
                runPokazatSvet(line, "скрыть свет", false)
            low == "скрыть" || low.startsWith("скрыть ") || low.startsWith("скрыть\t") ->
                runPokazat(line, "скрыть", false)
            low == "наверх" || low.startsWith("наверх ") || low.startsWith("наверх\t") ->
                runVerh(line, "наверх", true)
            low == "выше" || low.startsWith("выше ") || low.startsWith("выше\t") ->
                runVerh(line, "выше", true)
            low == "вниз" || low.startsWith("вниз ") || low.startsWith("вниз\t") ->
                runVerh(line, "вниз", false)
            low == "ниже" || low.startsWith("ниже ") || low.startsWith("ниже\t") ->
                runVerh(line, "ниже", false)
            low == "написать" || low.startsWith("написать ") || low.startsWith("написать\t") ->
                runNapisat(line, "написать")
            low == "надпись" || low.startsWith("надпись ") || low.startsWith("надпись\t") ->
                runNapisat(line, "надпись")
            low == "очистить" ->
                runOchistit(line)
            low == "создать клон" || low.startsWith("создать клон ") || low.startsWith("создать клон\t") ->
                runKlon(line)
            low == "создать свет" || low.startsWith("создать свет ") || low.startsWith("создать свет\t") ->
                runSozdatSvet(line)
            low == "привязать свет" || low.startsWith("привязать свет ") || low.startsWith("привязать свет\t") ->
                runPrivyazat(line)
            low == "отвязать свет" || low.startsWith("отвязать свет ") || low.startsWith("отвязать свет\t") ->
                runOtvyazat(line)
            low == "удалить свет" || low.startsWith("удалить свет ") || low.startsWith("удалить свет\t") ->
                runUdalitSvet(line)
            low == "удалить файл" || low.startsWith("удалить файл ") || low.startsWith("удалить файл\t") ->
                runUdalitFile(line)
            low == "удалить из базы" || low.startsWith("удалить из базы ") || low.startsWith("удалить из базы\t") ->
                runUdalitBazu(line)
            (low == "удалить" || low.startsWith("удалить ") || low.startsWith("удалить\t")) && !low.startsWith("удалить соединение") ->
                runUdalit(line)
            low == "вызвать" || low.startsWith("вызвать ") || low.startsWith("вызвать\t") ->
                runVyzvat(line)
            low == "задать гравитацию" || low.startsWith("задать гравитацию ") || low.startsWith("задать гравитацию\t") ->
                runSetGrav(line)
            low == "задать плотность воздуха" || low.startsWith("задать плотность воздуха ") || low.startsWith("задать плотность воздуха\t") ->
                runSetAirDens(line)
            low == "приложить силу в точке" || low.startsWith("приложить силу в точке ") || low.startsWith("приложить силу в точке\t") ->
                runApplyForceAt(line)
            low == "приложить силу" || low.startsWith("приложить силу ") || low.startsWith("приложить силу\t") ->
                runApplyForce(line, false)
            low == "приложить импульс" || low.startsWith("приложить импульс ") || low.startsWith("приложить импульс\t") ->
                runApplyForce(line, true)
            low == "приложить крутящий момент" || low.startsWith("приложить крутящий момент ") || low.startsWith("приложить крутящий момент\t") ->
                runTorque(line)
            low == "притянуть объект" || low.startsWith("притянуть объект ") || low.startsWith("притянуть объект\t") ->
                runAttract2(line)
            low == "остановить все силы" || low.startsWith("остановить все силы ") || low.startsWith("остановить все силы\t") ->
                runStopForces(line)
            low == "сделать тело динамическим" || low.startsWith("сделать тело динамическим ") || low.startsWith("сделать тело динамическим\t") ->
                runMakeBody(line, 0)
            low == "сделать тело статическим" || low.startsWith("сделать тело статическим ") || low.startsWith("сделать тело статическим\t") ->
                runMakeBody(line, 1)
            low == "сделать тело кинематическим" || low.startsWith("сделать тело кинематическим ") || low.startsWith("сделать тело кинематическим\t") ->
                runMakeBody(line, 2)
            low == "задать массу" || low.startsWith("задать массу ") || low.startsWith("задать массу\t") ->
                runSetMass(line)
            low == "задать упругость" || low.startsWith("задать упругость ") || low.startsWith("задать упругость\t") ->
                runSetRest(line)
            low == "задать трение" || low.startsWith("задать трение ") || low.startsWith("задать трение\t") ->
                runSetFric(line)
            low == "задать сопротивление воздуха" || low.startsWith("задать сопротивление воздуха ") || low.startsWith("задать сопротивление воздуха\t") ->
                runSetLinDamp(line)
            low == "задать сопротивление вращению" || low.startsWith("задать сопротивление вращению ") || low.startsWith("задать сопротивление вращению\t") ->
                runSetAngDamp(line)
            low == "зафиксировать вращение" || low.startsWith("зафиксировать вращение ") || low.startsWith("зафиксировать вращение\t") ->
                runFixRot(line)
            low == "задать масштаб гравитации" || low.startsWith("задать масштаб гравитации ") || low.startsWith("задать масштаб гравитации\t") ->
                runGravScale(line)
            low == "задать пулевой режим" || low.startsWith("задать пулевой режим ") || low.startsWith("задать пулевой режим\t") ->
                runBullet(line)
            low == "задать скорость" || low.startsWith("задать скорость ") || low.startsWith("задать скорость\t") ->
                runSetVel(line)
            low == "задать угловую скорость" || low.startsWith("задать угловую скорость ") || low.startsWith("задать угловую скорость\t") ->
                runSetAngVel(line)
            low == "телепортировать" || low.startsWith("телепортировать ") || low.startsWith("телепортировать\t") ->
                runTeleport(line)
            low == "задать коллайдер коробка" || low.startsWith("задать коллайдер коробка ") || low.startsWith("задать коллайдер коробка\t") ->
                runColliderBox(line)
            low == "задать коллайдер круг" || low.startsWith("задать коллайдер круг ") || low.startsWith("задать коллайдер круг\t") ->
                runColliderCircle(line)
            low == "задать коллайдер капсула" || low.startsWith("задать коллайдер капсула ") || low.startsWith("задать коллайдер капсула\t") ->
                runColliderCapsule(line)
            low == "задать смещение коллайдера" || low.startsWith("задать смещение коллайдера ") || low.startsWith("задать смещение коллайдера\t") ->
                runColliderOffset(line)
            low == "сделать триггером" || low.startsWith("сделать триггером ") || low.startsWith("сделать триггером\t") ->
                runMakeTrigger(line)
            low == "соединить веревкой" || low.startsWith("соединить веревкой ") || low.startsWith("соединить веревкой\t") ->
                runRope(line)
            low == "соединить пружиной" || low.startsWith("соединить пружиной ") || low.startsWith("соединить пружиной\t") ->
                runSpring(line)
            low == "соединить шарниром" || low.startsWith("соединить шарниром ") || low.startsWith("соединить шарниром\t") ->
                runHinge(line)
            low == "ограничить угол шарнира" || low.startsWith("ограничить угол шарнира ") || low.startsWith("ограничить угол шарнира\t") ->
                runLimit(line)
            low == "задать мотор шарнира" || low.startsWith("задать мотор шарнира ") || low.startsWith("задать мотор шарнира\t") ->
                runMotor(line)
            low == "удалить соединение" || low.startsWith("удалить соединение ") || low.startsWith("удалить соединение\t") ->
                runDelJoint(line)
            low == "задать прочность соединения" || low.startsWith("задать прочность соединения ") || low.startsWith("задать прочность соединения\t") ->
                runBreak(line)
            low == "идти" || low.startsWith("идти ") || low.startsWith("идти\t") ->
                runIdti(line)
            low == "двигать" || low.startsWith("двигать ") || low.startsWith("двигать\t") ->
                runDvigat(line)
            low == "отскочить от края" || low.startsWith("отскочить от края ") || low.startsWith("отскочить от края\t") ->
                runOtskochit(line)
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
                runSleduyushiy(line, "следующий костюм", 1)
            low == "предыдущий костюм" || low.startsWith("предыдущий костюм ") || low.startsWith("предыдущий костюм\t") ->
                runSleduyushiy(line, "предыдущий костюм", -1)
            low == "прошлый костюм" || low.startsWith("прошлый костюм ") || low.startsWith("прошлый костюм\t") ->
                runSleduyushiy(line, "прошлый костюм", -1)
            low == "костюм" || low.startsWith("костюм ") || low.startsWith("костюм\t") ->
                runKostyum(line)
            low == "играть" || low.startsWith("играть ") || low.startsWith("играть\t") ->
                runIgrat(line, "играть")
            low == "включить" || low.startsWith("включить ") || low.startsWith("включить\t") ->
                runIgrat(line, "включить")
            low == "остановить звук" || low.startsWith("остановить звук ") || low.startsWith("остановить звук\t") ->
                runOstanovit(line, "остановить звук")
            low == "остановить музыку" || low.startsWith("остановить музыку ") || low.startsWith("остановить музыку\t") ->
                runOstanovit(line, "остановить музыку")
            low == "выключить звук" || low.startsWith("выключить звук ") || low.startsWith("выключить звук\t") ->
                runOstanovit(line, "выключить звук")
            low == "выключить музыку" || low.startsWith("выключить музыку ") || low.startsWith("выключить музыку\t") ->
                runOstanovit(line, "выключить музыку")
            low == "пауза звук" || low.startsWith("пауза звук ") || low.startsWith("пауза звук\t") ->
                runPauza(line, "пауза звук")
            low == "пауза музыку" || low.startsWith("пауза музыку ") || low.startsWith("пауза музыку\t") ->
                runPauza(line, "пауза музыку")
            low == "продолжить звук" || low.startsWith("продолжить звук ") || low.startsWith("продолжить звук\t") ->
                runProdolzhit(line, "продолжить звук")
            low == "продолжить музыку" || low.startsWith("продолжить музыку ") || low.startsWith("продолжить музыку\t") ->
                runProdolzhit(line, "продолжить музыку")
            low == "прочитать базу" || low.startsWith("прочитать базу ") || low.startsWith("прочитать базу\t") ->
                runProchitatBazu(line)
            low == "создать в базе" || low.startsWith("создать в базе ") || low.startsWith("создать в базе\t") ->
                runSozdatBazu(line)
            low == "записать в базу" || low.startsWith("записать в базу ") || low.startsWith("записать в базу\t") ->
                runZapisatBazu(line)
            low == "записать в файл" || low.startsWith("записать в файл ") || low.startsWith("записать в файл\t") ->
                runZapisat(line, false)
            low == "добавить в файл" || low.startsWith("добавить в файл ") || low.startsWith("добавить в файл\t") ->
                runZapisat(line, true)
            low == "запрос" || low.startsWith("запрос ") || low.startsWith("запрос\t") ->
                runZapros(line)
            else -> throw NcodeError("неизвестная команда (нужно: задать / изменить / присвоить / сделать / напечатать / печатать / вывести / ждать / спросить / если / повтори / вещать / создать окно / масштабирование окна / рисовать / показать / задать прозрачность / задать образ объекту / играть звук / остановить звук / задать громкость для звука / идти / написать / наверх / создать клон / вызвать / при нажатии / запрос / записать в базу / создать в базе / прочитать базу / удалить из базы / создать свет / темнота)")
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
        if (!dryRun) platform.console.printLine(out)
    }

    fun evalExpression(expr: String): String {
        val (shielded, urls) = shieldUrls(expr)
        if (urls.isEmpty()) return evalExpressionRaw(expr)
        try {
            return unshieldUrls(evalExpressionRaw(shielded), urls)
        } catch (e: NcodeError) {
            throw NcodeError(unshieldUrls(e.message ?: "ошибка", urls))
        }
    }

    private fun shieldUrls(expr: String): Pair<String, List<String>> {
        val urls = mutableListOf<String>()
        val out = StringBuilder()
        var i = 0
        var inQ = false
        while (i < expr.length) {
            val c = expr[i]
            if (c == '"') {
                inQ = !inQ
                out.append(c)
                i++
                continue
            }
            if (!inQ && (expr.startsWith("http://", i, ignoreCase = true) || expr.startsWith("https://", i, ignoreCase = true))) {
                var j = i
                while (j < expr.length && !expr[j].isWhitespace() && expr[j] != '"') j++
                urls.add(expr.substring(i, j))
                out.append("\u0001URL" + (urls.size - 1) + "\u0001")
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return out.toString() to urls
    }

    private fun unshieldUrls(s: String, urls: List<String>): String {
        var r = s
        for (k in urls.indices) r = r.replace("\u0001URL" + k + "\u0001", urls[k])
        return r
    }

    private fun evalExpressionRaw(expr: String): String {
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
            return if (parts.all { toBool(evalNot(it)) }) "истина" else "ложь"
        }
        return evalNot(expr)
    }

    private val neLeadWords = setOf(
        "истина", "да", "правда", "ложь", "нет", "неправда", "не",
        "случайно", "корень", "модуль", "округлить", "степень",
        "минимум", "максимум", "длина", "синус", "косинус", "время",
        "сумма", "среднее", "взять", "есть", "найти", "заменить", "прочитать"
    )

    private fun evalNot(expr: String): String {
        var t = expr.trim()
        var neg = false
        var ate = false
        while (true) {
            val m = Regex("^(не)(?=$|[\\s\".])").find(t.lowercase(java.util.Locale.ROOT)) ?: break
            val rest = t.substring(m.range.last + 1).trim()
            if (rest.isEmpty()) break
            val head = rest.split(Regex("\\s+"), limit = 2)[0]
            val hl = head.lowercase(java.util.Locale.ROOT)
            if (hl !in neLeadWords && !numberRegex.matches(head) && !(head.length >= 2 && head.startsWith("\""))) break
            neg = !neg
            ate = true
            t = rest
        }
        if (!ate) return evalComparison(expr)
        val v = evalComparison(t)
        if (!neg) return v
        return if (toBool(v)) "ложь" else "истина"
    }

    private fun evalComparison(expr: String): String {
        val low = expr.lowercase(java.util.Locale.ROOT)
        val all = cmpRegex.findAll(low).filter { m ->
            isOutsideQuotes(expr, m.range.first + m.groupValues[1].length)
        }.toList()
        val m = all.firstOrNull() ?: return evalAddSub(expr)
        val leadLen = m.groupValues[1].length
        val left = expr.substring(0, m.range.first + leadLen)
        val right = expr.substring(m.range.last + 1)
        if (left.trim().isEmpty() || right.trim().isEmpty())
            throw NcodeError("у сравнения нужны левая и правая части")
        val rightLow = right.lowercase(java.util.Locale.ROOT)
        if (cmpRegex.findAll(rightLow).any { r ->
            val absPos = m.range.last + 1 + r.range.first + r.groupValues[1].length
            isOutsideQuotes(expr, absPos)
        }) throw NcodeError("только одно сравнение в выражении")
        return compareValues(evalAddSub(left), evalAddSub(right), opOf(m.groupValues[2]))
    }

    private fun runWait(line: String) {
        val rest = keywordTail(line, "ждать")
        if (rest.isEmpty())
            throw NcodeError("нужно: ждать <число> <секунду|минуту|час>, пример: ждать 2 секунды")
        val head = rest.trim().split(Regex("\\s+"), limit = 2)
        val first = head[0].lowercase(java.util.Locale.ROOT)
        if (first == "пока" || first == "покуда" || first == "до") {
            runWaitPoka(if (head.size > 1) head[1].trim() else "")
            return
        }
        val cut = rest.trim().indexOfLast { it.isWhitespace() }
        if (cut < 0)
            throw NcodeError("нужно: ждать <число> <секунду|минуту|час>, пример: ждать 2 секунды")
        val numText = rest.trim().substring(0, cut).trim()
        val unit = rest.trim().substring(cut).trim()
        val numVal = evalExpression(numText)
        if (!numberRegex.matches(numVal)) throw NcodeError("`${numText}` — не число")
        val amount = numVal.toDouble()
        if (amount < 0) throw NcodeError("время не может быть отрицательным")
        val mult = msMult(unit)
        val millis = amount * mult
        if (millis > 86_400_000.0) throw NcodeError("максимум — 24 часа")
        if (!dryRun) {
            var left = millis.toLong()
            while (left > 0) {
                if (closeRequested) throw SceneStop()
                val step = if (left > 50) 50 else left
                try {
                    platform.clock.sleepMs(step)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw NcodeError("ожидание прервано")
                }
                left -= step
                checkEvents()
            }
        }
    }

    private fun runWaitPoka(cond: String) {
        if (cond.isEmpty()) throw NcodeError("нужно: ждать пока <условие>")
        if (dryRun) {
            toBool(evalExpression(cond))
            return
        }
        while (!toBool(evalExpression(cond))) {
            if (closeRequested) throw SceneStop()
            try {
                platform.clock.sleepMs(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw NcodeError("ожидание прервано")
            }
            checkEvents()
        }
    }

    private fun readStdinLine(): String? {
        return platform.console.readLine()
    }

    private fun runAsk(line: String) {
        val rest = keywordTail(line, "спросить")
        if (rest.isEmpty()) throw NcodeError("нужно: спросить <подсказка> сохранить в <имя>")
        val (prompt, name) = splitAsk(rest)
        if (!nameRegex.matches(name)) throw NcodeError("плохое имя `$name` (буквы/цифры/_ без кавычек)")
        if (prompt.isNotEmpty()) {
            if (dryRun) evalConcat(prompt)
            else {
                platform.console.printText(evalConcat(prompt) + " ")
                platform.console.flush()
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
            if (c == '/' && i + 1 < expr.length && expr[i + 1] == '/') {
                out.append("//")
                i += 2
                continue
            }
            if (c == '/' && i > 0 && i + 1 < expr.length && expr[i - 1].isLetter() && expr[i + 1].isLetter()) {
                out.append('/')
                i++
                needOperand = false
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

    private fun isOutsideQuotes(expr: String, pos: Int): Boolean {
        var inQ = false
        var i = 0
        while (i < pos) {
            if (expr[i] == '"') inQ = !inQ
            i++
        }
        return !inQ
    }

    private fun splitWithOps(expr: String, alts: String): Pair<List<String>, List<String>>? {
        val t = expr.trim().lowercase(java.util.Locale.ROOT)
        if (alts.split('|').any { it == t }) return null
        val low = expr.lowercase(java.util.Locale.ROOT)
        val rx = Regex("(^|[\\s\".])($alts)(?=$|[\\s\".])")
        val matches = rx.findAll(low).filter { m ->
            val opPos = m.range.first + m.groupValues[1].length
            isOutsideQuotes(expr, opPos)
        }.toList()
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
        if (r == kotlin.math.floor(r) && kotlin.math.abs(r) < 9e18) return r.toLong().toString()
        return java.math.BigDecimal(r.toString()).toPlainString()
    }

    private fun evalConcat(expr: String): String {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var inQ = false
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c == '"') {
                inQ = !inQ
                cur.append(c)
                i++
                continue
            }
            if (!inQ && c == '.' && i + 1 < expr.length && expr[i + 1] == '.') {
                parts.add(cur.toString())
                cur.setLength(0)
                i += 2
                continue
            }
            cur.append(c)
            i++
        }
        parts.add(cur.toString())
        if (parts.size == 1) return evalSingle(parts[0].trim(), single = true)
        return parts.joinToString("") { raw -> evalSingle(raw, single = false, rawFallback = raw) }
    }

    private fun splitByWordOp(expr: String, word: String): List<String>? {
        if (expr.trim().lowercase(java.util.Locale.ROOT) == word) return null
        val rx = Regex("(^|[\\s\".])($word)(?=$|[\\s\".])")
        val low = expr.lowercase(java.util.Locale.ROOT)
        val guarded = cmp3Regex.findAll(low).map { it.range }.toList()
        val matches = rx.findAll(low).filter { m ->
            guarded.none { g -> m.range.first <= g.last && g.first <= m.range.last } &&
            isOutsideQuotes(expr, m.range.first + m.groupValues[1].length)
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

    private fun objProp(name: String, prop: String): String? {
        if (dryRun) return when (prop) {
            "икс", "игрек", "угол", "скорость" -> "0"
            "размер", "прозрачность" -> "100"
            "форма" -> "квадрат"
            else -> "ложь"
        }
        val o = synchronized(gfxLock) {
            gfxObjs.find { it.name == name }?.copy()
        } ?: return null
        return when (prop) {
            "икс" -> fmtNum(o.x)
            "игрек" -> fmtNum(o.y)
            "размер" -> fmtNum(o.size)
            "угол" -> fmtNum(o.rot)
            "скорость" -> fmtNum(o.vel)
            "виден" -> if (o.visible) "истина" else "ложь"
            "форма" -> if (o.circle) "круг" else "квадрат"
            else -> o.alpha.toString()
        }
    }

    private fun svoKnown(prop: String): Boolean {
        return prop == "х" || prop == "x" || prop == "у" || prop == "y" ||
            prop == "прозрачность" || prop == "прозрачности" ||
            prop == "размер" || prop == "размера" ||
            prop == "ширина" || prop == "ширины" ||
            prop == "высота" || prop == "высоты" ||
            prop == "угол" || prop == "угла" ||
            prop == "костюм" || prop == "костюма" ||
            prop == "красный" || prop == "красного" ||
            prop == "зеленый" || prop == "зеленого" ||
            prop == "синий" || prop == "синего" ||
            prop == "видимость" || prop == "видимости" || prop == "текст" || prop == "текста" ||
            prop == "перо" || prop == "пера" || prop == "слой" || prop == "слоя" ||
            prop == "форма" || prop == "формы" || prop == "скорость" || prop == "скорости" ||
            prop == "тип" || prop == "типа"
    }

    private fun svoProp(name: String, prop: String): String {
        if (dryRun) return when (prop) {
            "х", "x", "у", "y", "угол", "угла", "слой", "слоя" -> "0"
            "костюм", "костюма" -> "1"
            "видимость", "видимости", "перо", "пера" -> "ложь"
            "текст", "текста" -> ""
            "скорость", "скорости" -> "0"
            "форма", "формы" -> "квадрат"
            "тип", "типа" -> "динамичный"
            else -> "100"
        }
        val o = synchronized(gfxLock) {
            gfxObjs.find { it.name == name }?.copy()
        } ?: throw NcodeError("объект не нарисован")
        val img = o.costumes.getOrNull(o.costume)
        val w = if (img != null) img.w * (o.size / img.w.toDouble()) else o.size
        val h = if (img != null) img.h * (o.size / img.w.toDouble()) else o.size
        return when (prop) {
            "х", "x" -> fmtNum(o.x)
            "у", "y" -> fmtNum(o.y)
            "прозрачность", "прозрачности" -> o.alpha.toString()
            "размер", "размера" -> fmtNum(o.size)
            "ширина", "ширины" -> fmtNum(w)
            "высота", "высоты" -> fmtNum(h)
            "угол", "угла" -> fmtNum(o.rot)
            "костюм", "костюма" -> (o.costume + 1).toString()
            "красный", "красного" -> o.r.toString()
            "зеленый", "зеленого" -> o.g.toString()
            "синий", "синего" -> o.b.toString()
            "видимость", "видимости" -> if (o.visible) "истина" else "ложь"
            "скорость", "скорости" -> fmtNum(o.vel)
            "форма", "формы" -> if (o.circle) "круг" else "квадрат"
            "тип", "типа" -> if (o.bodyType == 1) "статичный" else "динамичный"
            "текст", "текста" -> o.text ?: ""
            "перо", "пера" -> if (o.penDown) "истина" else "ложь"
            else -> synchronized(gfxLock) { gfxObjs.indexOfFirst { it.name == name }.toString() }
        }
    }

    private fun isEst(w: String): Boolean {
        val l = w.lowercase(java.util.Locale.ROOT)
        return l == "есть" || l == "эсть" || l == "ест" || l == "имеется"
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
        if (isEst(head0) && toks.size >= 3) {
            var k = 1
            if (toks[k].lowercase(java.util.Locale.ROOT) == "ли") k++
            if (k + 1 < toks.size && toks[k].lowercase(java.util.Locale.ROOT) == "в" && toks[k + 1].lowercase(java.util.Locale.ROOT) == "списке") {
                if (toks.size < k + 4) throw NcodeError("нужно: есть в списке <имя> <значение>")
                val list = listByName(toks[k + 2])
                val v = evalExpression(toks.drop(k + 3).joinToString(" ")).lowercase(java.util.Locale.ROOT)
                return if (list.any { it.lowercase(java.util.Locale.ROOT) == v }) "истина" else "ложь"
            }
            if (k < toks.size && toks[k].lowercase(java.util.Locale.ROOT) == "файл") {
                if (toks.size != k + 2) throw NcodeError("нужно: есть ли файл <путь>")
                val path = toks[k + 1]
                if (dryRun && dryWritten.contains(path)) return "истина"
                return if (platform.files.isFile(path)) "истина" else "ложь"
            }
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
        if (head0 == "нажата" && (toks.size == 2 || (toks.size == 3 && (toks[1].lowercase(java.util.Locale.ROOT) == "клавиша" || toks[1].lowercase(java.util.Locale.ROOT) == "стрелка" || (toks[1].lowercase(java.util.Locale.ROOT) == "кнопка" && (toks[2].lowercase(java.util.Locale.ROOT) == "мыши" || toks[2].lowercase(java.util.Locale.ROOT) == "мышь")))))) {
            if (toks.size == 2 && (toks[1].lowercase(java.util.Locale.ROOT) == "мышь" || toks[1].lowercase(java.util.Locale.ROOT) == "кнопка")) {
                if (dryRun) return "ложь"
                return if (synchronized(mouseQueue) { mouseDown }) "истина" else "ложь"
            }
            if (toks.size == 3 && toks[1].lowercase(java.util.Locale.ROOT) == "кнопка") {
                if (dryRun) return "ложь"
                return if (synchronized(mouseQueue) { mouseDown }) "истина" else "ложь"
            }
            val key = if (toks.size == 2) toks[1] else toks[2]
            if (dryRun) return "ложь"
            val down = synchronized(keyQueue) { normKey(key) in heldKeys }
            return if (down) "истина" else "ложь"
        }
        if ((head0 == "отпущена" || head0 == "отпущен" || head0 == "отпущено") && (toks.size == 2 || (toks.size == 3 && toks[1].lowercase(java.util.Locale.ROOT) == "клавиша"))) {
            val key = if (toks.size == 2) toks[1] else toks[2]
            if (dryRun) return "ложь"
            val down = synchronized(keyQueue) { normKey(key) in heldKeys }
            return if (!down) "истина" else "ложь"
        }
        if (head0 == "касается" && toks.size == 3) {
            if (dryRun) return "ложь"
            val a = toks[1].lowercase(java.util.Locale.ROOT)
            if (toks[2].lowercase(java.util.Locale.ROOT) == "край") {
                return if (touchesEdge(a)) "истина" else "ложь"
            }
            val b = toks[2].lowercase(java.util.Locale.ROOT)
            return if (touches(a, b)) "истина" else "ложь"
        }
        if (head0 == "расстояние" && toks.size == 3) {
            if (dryRun) return "0"
            val a = toks[1].lowercase(java.util.Locale.ROOT)
            val b = toks[2].lowercase(java.util.Locale.ROOT)
            return fmtNum(distObjs(a, b))
        }
        if (head0 == "гравитация" && toks.size == 2 && (toks[1].lowercase(java.util.Locale.ROOT) == "x" || toks[1].lowercase(java.util.Locale.ROOT) == "y")) {
            return fmtNum(if (toks[1].lowercase(java.util.Locale.ROOT) == "x") gravX else gravY)
        }
        if (head0 == "масса" && toks.size == 3 && toks[1].lowercase(java.util.Locale.ROOT) == "объекта") {
            val name = toks[2].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "1"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return fmtNum(o.mass)
        }
        if (head0 == "скорость" && toks.size == 4 && toks[1].lowercase(java.util.Locale.ROOT) == "x" && toks[2].lowercase(java.util.Locale.ROOT) == "объекта") {
            val name = toks[3].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "0"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return fmtNum(o.vx)
        }
        if (head0 == "скорость" && toks.size == 4 && toks[1].lowercase(java.util.Locale.ROOT) == "y" && toks[2].lowercase(java.util.Locale.ROOT) == "объекта") {
            val name = toks[3].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "0"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return fmtNum(o.vy)
        }
        if ((head0 == "общая" && toks.size == 4 && toks[1].lowercase(java.util.Locale.ROOT) == "скорость" && toks[2].lowercase(java.util.Locale.ROOT) == "объекта") || (head0 == "скорость" && toks.size == 3 && toks[1].lowercase(java.util.Locale.ROOT) == "объекта")) {
            val name = if (head0 == "общая") toks[3].lowercase(java.util.Locale.ROOT) else toks[2].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "0"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return fmtNum(Math.hypot(o.vx, o.vy))
        }
        if (head0 == "угловая" && toks.size == 4 && toks[1].lowercase(java.util.Locale.ROOT) == "скорость" && toks[2].lowercase(java.util.Locale.ROOT) == "объекта") {
            val name = toks[3].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "0"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return fmtNum(o.av)
        }
        if (head0 == "объект" && toks.size == 4 && toks[2].lowercase(java.util.Locale.ROOT) == "является" && toks[3].lowercase(java.util.Locale.ROOT) == "триггером") {
            val name = toks[1].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "ложь"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return if (o.isTrigger) "истина" else "ложь"
        }
        if (head0 == "объект" && toks.size == 4 && toks[2].lowercase(java.util.Locale.ROOT) == "на" && toks[3].lowercase(java.util.Locale.ROOT) == "земле") {
            val name = toks[1].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "ложь"
            return if (touchesEdge(name) || isOnGround(name)) "истина" else "ложь"
        }
        if (head0 == "сила" && toks.size == 6 && toks[1].lowercase(java.util.Locale.ROOT) == "последнего" && toks[2].lowercase(java.util.Locale.ROOT) == "удара" && toks[3].lowercase(java.util.Locale.ROOT) == "объекта") {
            val name = toks[5].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "0"
            val o = synchronized(gfxLock) { gfxObjs.find { it.name == name }?.copy() } ?: throw NcodeError("объект не нарисован")
            return fmtNum(o.lastHit)
        }
        if (head0 == "точка" && toks.size == 3 && toks[1].lowercase(java.util.Locale.ROOT) == "столкновения" && (toks[2].lowercase(java.util.Locale.ROOT) == "x" || toks[2].lowercase(java.util.Locale.ROOT) == "y")) {
            return fmtNum(if (toks[2].lowercase(java.util.Locale.ROOT) == "x") lastCollX else lastCollY)
        }
        if (head0 == "бросить" && toks.size == 10 && toks[1].lowercase(java.util.Locale.ROOT) == "луч" && toks[2].lowercase(java.util.Locale.ROOT) == "от" && toks[5].lowercase(java.util.Locale.ROOT) == "до" && toks[8].lowercase(java.util.Locale.ROOT) == "пересекает") {
            val x1 = evalArithOperand(toks[3])
            val y1 = evalArithOperand(toks[4])
            val x2 = evalArithOperand(toks[6])
            val y2 = evalArithOperand(toks[7])
            val name = toks[9].lowercase(java.util.Locale.ROOT)
            if (dryRun) return "ложь"
            val b = objBox(name) ?: throw NcodeError("объект не нарисован")
            val hit = segmentsIntersect(x1, y1, x2, y2, b[0], b[2], b[1], b[2]) || segmentsIntersect(x1, y1, x2, y2, b[1], b[2], b[1], b[3]) || segmentsIntersect(x1, y1, x2, y2, b[1], b[3], b[0], b[3]) || segmentsIntersect(x1, y1, x2, y2, b[0], b[3], b[0], b[2])
            return if (hit) "истина" else "ложь"
        }
        if (head0 == "дистанция" && toks.size == 10 && toks[1].lowercase(java.util.Locale.ROOT) == "луча" && toks[2].lowercase(java.util.Locale.ROOT) == "до" && toks[4].lowercase(java.util.Locale.ROOT) == "от" && toks[7].lowercase(java.util.Locale.ROOT) == "в" && toks[8].lowercase(java.util.Locale.ROOT) == "направлении") {
            val name = toks[3].lowercase(java.util.Locale.ROOT)
            val x = evalArithOperand(toks[5])
            val y = evalArithOperand(toks[6])
            val ang = evalArithOperand(toks[9])
            if (dryRun) return "0"
            val b = objBox(name) ?: throw NcodeError("объект не нарисован")
            val cx = (b[0] + b[1]) / 2
            val cy = (b[2] + b[3]) / 2
            return fmtNum(Math.hypot(cx - x, cy - y))
        }
        if (head0 == "код" && toks.size == 2 && toks[1].lowercase(java.util.Locale.ROOT) == "ответа") {
            if (dryRun) return "200"
            return lastHttpCode.toString()
        }
        if (head0 == "свойство") {
            if (toks.size == 5 && toks[1].lowercase(java.util.Locale.ROOT) == "тип" && toks[2].lowercase(java.util.Locale.ROOT) == "движения" && (toks[3].lowercase(java.util.Locale.ROOT) == "объекта" || toks[3].lowercase(java.util.Locale.ROOT) == "обьекта")) {
                return svoProp(toks[4].lowercase(java.util.Locale.ROOT), "тип")
            }
            if (toks.size == 4 && (toks[2].lowercase(java.util.Locale.ROOT) == "объекта" || toks[2].lowercase(java.util.Locale.ROOT) == "обьекта")) {
                val prop = toks[1].lowercase(java.util.Locale.ROOT).replace("ё", "е")
                if (!svoKnown(prop)) throw NcodeError("не знаю свойство `$prop`")
                return svoProp(toks[3].lowercase(java.util.Locale.ROOT), prop)
            }
            if (toks.size >= 2 && (svoKnown(toks[1].lowercase(java.util.Locale.ROOT).replace("ё", "е")) || (toks.size >= 3 && (toks[2].lowercase(java.util.Locale.ROOT) == "объекта" || toks[2].lowercase(java.util.Locale.ROOT) == "обьекта")))) {
                throw NcodeError("нужно: свойство <что> объекта <имя>")
            }
        }
        if ((head0 == "икс" || head0 == "x" || head0 == "х" || head0 == "игрек" || head0 == "y" || head0 == "у" || head0 == "размер" || head0 == "угол" || head0 == "поворот" || head0 == "виден" || head0 == "прозрачность" || head0 == "форма" || head0 == "скорость") && toks.size == 2) {
            val prop = when (head0) {
                "x", "х" -> "икс"
                "y", "у" -> "игрек"
                "поворот" -> "угол"
                else -> head0
            }
            objProp(toks[1].lowercase(java.util.Locale.ROOT), prop)?.let { return it }
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
        if (!single && low == "точкаточка") return ".."
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
    private val startNanos = platform.clock.nanoTime()

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
                return fmtNum((platform.clock.nanoTime() - startNanos).toDouble() / 1e9)
            }
            else -> throw NcodeError("не знаю формулу `$fname`")
        }
    }
}

class NcodeError(message: String) : Exception(message)
class BreakSignal : Exception()
class ContinueSignal : Exception()
class SceneStop : Exception()

fun relLabel(path: String): String {
    return try {
        val cwd = java.io.File(System.getProperty("user.dir")).canonicalFile.toPath()
        val rel = cwd.relativize(java.io.File(path).canonicalFile.toPath()).toString()
        if (rel.startsWith("..")) path else rel
    } catch (e: Exception) {
        path
    }
}
