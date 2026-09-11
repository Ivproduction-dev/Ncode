package ncode

import java.awt.*
import java.awt.event.*
import java.io.File
import java.util.Vector
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeSelectionEvent
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.text.*
import javax.swing.tree.*

private fun stripCommentIde(s: String): String {
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

fun isGame2D(text: String): Boolean {
    for (raw in text.lines()) {
        val t = stripCommentIde(raw).trim()
        if (t.isEmpty()) continue
        val parts = t.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) continue
        val a = parts[0].lowercase()
        val b = parts[1].lowercase()
        if ((a == "создать" || a == "открыть") && b == "окно") return true
    }
    return false
}

fun ideJavaBin(): String {
    return File(System.getProperty("java.home"), "bin\\java.exe").path
}

fun ideJavawBin(): String {
    return File(System.getProperty("java.home"), "bin\\javaw.exe").path
}

fun ideOwnJar(): String? {
    return try {
        val loc = IdeKtAnchor::class.java.protectionDomain.codeSource.location.toURI()
        val f = File(loc)
        if (f.isFile && f.name.lowercase().endsWith(".jar")) f.canonicalPath else null
    } catch (e: Exception) {
        null
    }
}

fun ideBaseDir(): String {
    return try {
        val loc = IdeKtAnchor::class.java.protectionDomain.codeSource.location.toURI()
        val f = File(loc)
        val base = if (f.isFile) f.parent else System.getProperty("user.dir")
        base ?: System.getProperty("user.dir") ?: "."
    } catch (e: Exception) {
        try {
            System.getProperty("user.dir") ?: "."
        } catch (e2: Exception) {
            "."
        }
    }
}

private object IdeKtAnchor

fun ideRunBat(javaBin: String, jar: String, script: String): String {
    return "@echo off\nchcp 65001 >nul\n\"$javaBin\" -Dfile.encoding=UTF-8 -jar \"$jar\" \"$script\"\nif errorlevel 1 pause\n"
}

fun ideRunCmd(javaBin: String, jar: String, script: String): List<String> {
    return listOf(javaBin, "-Dfile.encoding=UTF-8", "-jar", jar, script)
}

fun ideBatFile(): File {
    val f = File.createTempFile("ncode-run-", ".bat")
    f.deleteOnExit()
    return f
}

fun ideConsoleCmd(workDir: String, bat: String): List<String> {
    return listOf("cmd", "/c", "start", "", "/D", workDir, bat)
}

fun ideGuiCmd(javawBin: String, jar: String, script: String): List<String> {
    return listOf(javawBin, "-Dfile.encoding=UTF-8", "-jar", jar, script)
}

object IdeTheme {
    val bgDeep = Color(24, 24, 37)
    val bgMain = Color(30, 30, 46)
    val bgEditor = Color(17, 17, 27)
    val bgHeader = Color(20, 20, 31)
    val bgHover = Color(49, 50, 68)
    val bgActive = Color(69, 71, 90)
    
    val fgMain = Color(205, 214, 244)
    val fgMuted = Color(166, 173, 200)
    val fgSubtle = Color(108, 112, 134)
    
    val accent = Color(137, 180, 250)
    val accentDark = Color(114, 135, 230)
    val accentRun = Color(166, 227, 161)
    val accentRunHover = Color(140, 210, 135)
    val accentWarn = Color(249, 226, 175)
    val accentError = Color(243, 139, 168)
    
    val gutter = Color(24, 24, 37)
    val lineNumbers = Color(108, 112, 134)
    val lineNumbersActive = Color(205, 214, 244)
    val line = Color(49, 50, 68)
    val lineHi = Color(137, 180, 250, 20)
    
    val caret = Color(245, 194, 231)
    val selection = Color(69, 71, 90, 180)
    
    val synKeyword = Color(203, 166, 247)
    val synCmd = Color(137, 220, 235)
    val synString = Color(166, 227, 161)
    val synNumber = Color(250, 179, 135)
    val synComment = Color(108, 112, 134)
    val synOperator = Color(243, 139, 168)

    val fontUi = Font("Segoe UI", Font.PLAIN, 12)
    val fontUiBold = Font("Segoe UI", Font.BOLD, 12)
    val fontTitle = Font("Segoe UI", Font.BOLD, 13)
    val fontCode = chooseCodeFont()

    private fun chooseCodeFont(): Font {
        val candidates = arrayOf("JetBrains Mono", "Consolas", "Cascadia Code", "Menlo", "Courier New", Font.MONOSPACED)
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val names = ge.availableFontFamilyNames.toSet()
        for (c in candidates) {
            if (c == Font.MONOSPACED || names.contains(c)) {
                return Font(c, Font.PLAIN, 14)
            }
        }
        return Font(Font.MONOSPACED, Font.PLAIN, 14)
    }
}

class ModernScrollBarUI : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        thumbColor = IdeTheme.bgHover
        trackColor = IdeTheme.bgEditor
    }
    override fun createDecreaseButton(orientation: Int): JButton = createZeroButton()
    override fun createIncreaseButton(orientation: Int): JButton = createZeroButton()
    private fun createZeroButton(): JButton {
        val b = JButton()
        b.preferredSize = Dimension(0, 0)
        return b
    }
    override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: Rectangle) {
        if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (isThumbRollover) IdeTheme.bgActive else IdeTheme.bgHover
        g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2, thumbBounds.width - 4, thumbBounds.height - 4, 6, 6)
        g2.dispose()
    }
    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) {
        g.color = scrollbar.background ?: IdeTheme.bgEditor
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height)
    }
}

class FlatButton(
    text: String,
    val iconKind: String? = null,
    val isPrimary: Boolean = false,
    val isRun: Boolean = false,
    val onClick: (JButton) -> Unit
) : JButton(text) {
    private var hover = false
    private var pressed = false

    init {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        font = if (isRun || isPrimary) IdeTheme.fontTitle else IdeTheme.fontUi
        foreground = when {
            isRun -> Color(17, 17, 27)
            isPrimary -> Color(17, 17, 27)
            else -> IdeTheme.fgMain
        }
        // Резервируем место слева под рисуемую иконку (iconKind),
        // иначе текст JButton рисуется по центру и налазит на иконку.
        margin = if (iconKind != null) Insets(5, 28, 5, 12) else Insets(5, 12, 5, 12)
        iconTextGap = 8
        horizontalAlignment = SwingConstants.CENTER
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) { pressed = false; repaint() }
        })
        addActionListener { onClick(this@FlatButton) }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val h = height

        when {
            isRun -> {
                g2.color = when {
                    pressed -> IdeTheme.accentRunHover.darker()
                    hover -> IdeTheme.accentRunHover
                    else -> IdeTheme.accentRun
                }
                g2.fillRoundRect(0, 0, w, h, 8, 8)
            }
            isPrimary -> {
                g2.color = when {
                    pressed -> IdeTheme.accentDark
                    hover -> IdeTheme.accent
                    else -> IdeTheme.accent
                }
                g2.fillRoundRect(0, 0, w, h, 8, 8)
            }
            else -> {
                if (hover || pressed) {
                    g2.color = if (pressed) IdeTheme.bgActive else IdeTheme.bgHover
                    g2.fillRoundRect(0, 0, w, h, 6, 6)
                }
            }
        }

        if (iconKind != null) {
            g2.color = if (isRun || isPrimary) Color(17, 17, 27) else IdeTheme.accent
            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val iy = (h - 14) / 2
            when (iconKind) {
                "new" -> {
                    g2.drawRoundRect(8, iy, 11, 14, 2, 2)
                    g2.drawLine(13, iy + 4, 13, iy + 10)
                    g2.drawLine(10, iy + 7, 16, iy + 7)
                }
                "open" -> {
                    g2.drawRoundRect(7, iy + 3, 14, 10, 2, 2)
                    g2.drawLine(7, iy + 5, 13, iy + 5)
                }
                "save" -> {
                    g2.drawRoundRect(8, iy + 1, 12, 12, 2, 2)
                    g2.fillRect(11, iy + 2, 6, 4)
                }
                "run" -> {
                    val xPoints = intArrayOf(10, 10, 18)
                    val yPoints = intArrayOf(iy + 2, iy + 12, iy + 7)
                    g2.fillPolygon(xPoints, yPoints, 3)
                }
                "find" -> {
                    g2.drawOval(8, iy + 1, 9, 9)
                    g2.drawLine(15, iy + 8, 19, iy + 12)
                }
                "folder" -> {
                    g2.drawRoundRect(8, iy + 2, 12, 10, 2, 2)
                }
                "book" -> {
                    g2.drawRoundRect(8, iy + 1, 12, 12, 2, 2)
                    g2.drawLine(8, iy + 5, 20, iy + 5)
                    g2.drawLine(8, iy + 9, 16, iy + 9)
                }
                "drop" -> {
                    g2.drawOval(8, iy + 2, 10, 10)
                    g2.fillOval(11, iy + 5, 5, 5)
                }
            }
        }

        g2.dispose()
        super.paintComponent(g)
    }
}

class FlatIconButton(val iconText: String, val tooltip: String, val onClick: () -> Unit) : JButton(iconText) {
    private var hover = false
    init {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        toolTipText = tooltip
        font = IdeTheme.fontTitle
        foreground = IdeTheme.fgMuted
        margin = Insets(4, 8, 4, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; foreground = IdeTheme.fgMain; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; foreground = IdeTheme.fgMuted; repaint() }
        })
        addActionListener { onClick() }
    }
    override fun paintComponent(g: Graphics) {
        if (hover) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = IdeTheme.bgHover
            g2.fillRoundRect(0, 0, width, height, 6, 6)
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

class NcodeSyntaxHighlighter(val doc: DefaultStyledDocument) {
    private val keyAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.synKeyword)
        StyleConstants.setBold(this, true)
    }
    private val cmdAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.synCmd)
    }
    private val strAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.synString)
    }
    private val numAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.synNumber)
    }
    private val commentAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.synComment)
        StyleConstants.setItalic(this, true)
    }
    private val opAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.synOperator)
    }
    private val plainAttr = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, IdeTheme.fgMain)
        StyleConstants.setBold(this, false)
        StyleConstants.setItalic(this, false)
    }

    private val keywords = setOf(
        "если", "эсли", "то", "тогда", "иначеесли", "иначе", "конец",
        "повтори", "повторить", "повторять", "пока", "покуда", "всегда", "повторяй",
        "как", "только", "когда", "получено", "будет", "чтобы", "каждые",
        "стоп", "дальше", "истина", "ложь", "да", "нет"
    )

    private val commands = setOf(
        "задать", "изменить", "поменять", "напечатать", "печатать", "вывести",
        "спросить", "сохранить", "вешать", "всем", "создать", "открыть",
        "нарисовать", "написать", "надпись", "играть", "включить", "остановить",
        "выключить", "пауза", "продолжить", "показать", "скрыть", "очистить",
        "вызвать", "идти", "повернуть", "плыть", "двигать", "отскочить",
        "добавить", "взять", "убрать", "записать", "прочитать", "удалить"
    )

    fun rehighlightAll() {
        val text = doc.getText(0, doc.length)
        doc.setCharacterAttributes(0, doc.length, plainAttr, true)

        val commentMatcher = Pattern.compile("(//|#).*").matcher(text)
        while (commentMatcher.find()) {
            doc.setCharacterAttributes(commentMatcher.start(), commentMatcher.end() - commentMatcher.start(), commentAttr, true)
        }

        val strMatcher = Pattern.compile("\"[^\"]*\"").matcher(text)
        while (strMatcher.find()) {
            doc.setCharacterAttributes(strMatcher.start(), strMatcher.end() - strMatcher.start(), strAttr, true)
        }

        val numMatcher = Pattern.compile("\\b\\d+(\\.\\d+)?\\b").matcher(text)
        while (numMatcher.find()) {
            doc.setCharacterAttributes(numMatcher.start(), numMatcher.end() - numMatcher.start(), numAttr, true)
        }

        val wordMatcher = Pattern.compile("[а-яёА-ЯЁa-zA-Z_][а-яёА-ЯЁa-zA-Z0-9_]*").matcher(text)
        while (wordMatcher.find()) {
            val word = wordMatcher.group().lowercase()
            val start = wordMatcher.start()
            val len = wordMatcher.end() - start
            if (keywords.contains(word)) {
                doc.setCharacterAttributes(start, len, keyAttr, true)
            } else if (commands.contains(word)) {
                doc.setCharacterAttributes(start, len, cmdAttr, true)
            }
        }

        val opMatcher = Pattern.compile("(\\+|\\-|\\*|\\/|%|=|==|!=|>|<|>=|<=|&&|\\|\\|)").matcher(text)
        while (opMatcher.find()) {
            doc.setCharacterAttributes(opMatcher.start(), opMatcher.end() - opMatcher.start(), opAttr, true)
        }
    }
}

class LineNumbersGutter(private val pane: JTextPane) : JPanel() {
    var errorLines: Set<Int> = emptySet()

    init {
        font = IdeTheme.fontCode
        background = IdeTheme.gutter
        foreground = IdeTheme.lineNumbers
    }

    fun refresh() {
        val total = maxOf(pane.document.defaultRootElement.elementCount, 1)
        val digits = maxOf(total.toString().length, 2)
        val fm = getFontMetrics(font)
        preferredSize = Dimension(fm.charWidth('0') * digits + 24, 0)
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val root = pane.document.defaultRootElement
        val caretPos = pane.caretPosition
        val currentLine = root.getElementIndex(caretPos)
        val fm = g2.getFontMetrics(font)
        val clip = g2.clipBounds

        for (i in 0 until root.elementCount) {
            val elem = root.getElement(i)
            val rect = try {
                pane.modelToView(elem.startOffset)
            } catch (e: Exception) { null } ?: continue

            if (rect.y + rect.height < clip.y || rect.y > clip.y + clip.height) continue

            val lineNo = i + 1
            val isCurrent = i == currentLine
            val isError = errorLines.contains(lineNo)

            if (isError) {
                g2.color = IdeTheme.accentError
                g2.fillOval(4, rect.y + (rect.height - 8) / 2, 8, 8)
            }

            g2.color = when {
                isError -> IdeTheme.accentError
                isCurrent -> IdeTheme.lineNumbersActive
                else -> IdeTheme.lineNumbers
            }
            g2.font = if (isCurrent) font.deriveFont(Font.BOLD) else font
            val str = lineNo.toString()
            g2.drawString(str, width - fm.stringWidth(str) - 8, rect.y + fm.ascent)
        }
        g2.dispose()
    }

    fun watch(scroll: JScrollPane) {
        pane.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refresh()
            override fun removeUpdate(e: DocumentEvent?) = refresh()
            override fun changedUpdate(e: DocumentEvent?) = refresh()
        })
        pane.addCaretListener { refresh() }
        scroll.viewport.addChangeListener { repaint() }
    }
}

class NcodeEditorTab(var file: File? = null) : JPanel(BorderLayout()) {
    val doc = DefaultStyledDocument()
    val pane = JTextPane(doc)
    val gutter = LineNumbersGutter(pane)
    val scroll = JScrollPane(pane)
    val highlighter = NcodeSyntaxHighlighter(doc)
    var isModified = false
    var onModificationChanged: (() -> Unit)? = null

    private val findPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
    private val tfFind = JTextField(14)
    private val tfReplace = JTextField(14)
    private val lblMatchCount = JLabel("")

    private val autoPopup = JPopupMenu()
    private val autoList = JList<String>()

    val ghostPopup = JPopupMenu()
    private var ghostWords: List<String> = emptyList()
    private var ghostIx = 0
    private var ghostBase = ""
    private val ghostLine = JLabel()
    private val ghostHint = JLabel("Tab — принять")

    init {
        background = IdeTheme.bgEditor
        pane.font = IdeTheme.fontCode
        pane.background = IdeTheme.bgEditor
        pane.foreground = IdeTheme.fgMain
        pane.caretColor = IdeTheme.caret
        pane.selectionColor = IdeTheme.selection
        pane.border = EmptyBorder(6, 8, 6, 8)
        pane.isFocusable = true
        pane.focusTraversalKeysEnabled = false
        pane.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, java.util.Collections.emptySet())
        pane.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, java.util.Collections.emptySet())

        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.background = IdeTheme.bgEditor
        scroll.verticalScrollBar.setUI(ModernScrollBarUI())
        scroll.horizontalScrollBar.setUI(ModernScrollBarUI())
        scroll.setRowHeaderView(gutter)
        gutter.watch(scroll)

        setupFindPanel()
        setupAutocomplete()
        setupGhost()
        setupListeners()

        add(findPanel, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun setTextContent(text: String) {
        pane.text = text
        pane.caretPosition = 0
        highlighter.rehighlightAll()
        gutter.refresh()
        isModified = false
        onModificationChanged?.invoke()
    }

    fun getTextContent(): String = pane.text

    private fun setupFindPanel() {
        findPanel.background = IdeTheme.bgHeader
        findPanel.border = BorderFactory.createMatteBorder(0, 0, 1, 0, IdeTheme.line)
        findPanel.isVisible = false

        tfFind.background = IdeTheme.bgEditor
        tfFind.foreground = IdeTheme.fgMain
        tfFind.caretColor = IdeTheme.caret
        tfFind.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IdeTheme.line),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)
        )

        tfReplace.background = IdeTheme.bgEditor
        tfReplace.foreground = IdeTheme.fgMain
        tfReplace.caretColor = IdeTheme.caret
        tfReplace.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IdeTheme.line),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)
        )

        lblMatchCount.foreground = IdeTheme.fgMuted
        lblMatchCount.font = IdeTheme.fontUi

        val btnNext = FlatButton("Далее") { findNext(true) }
        val btnPrev = FlatButton("Назад") { findNext(false) }
        val btnRep = FlatButton("Заменить") { replaceOne() }
        val btnRepAll = FlatButton("Заменить всё") { replaceAll() }
        val btnClose = FlatIconButton("X", "Закрыть поиск") { hideFindPanel() }

        tfFind.addActionListener { findNext(true) }

        findPanel.add(JLabel("Найти:").apply { foreground = IdeTheme.fgMuted; font = IdeTheme.fontUi })
        findPanel.add(tfFind)
        findPanel.add(btnNext)
        findPanel.add(btnPrev)
        findPanel.add(JLabel("Заменить:").apply { foreground = IdeTheme.fgMuted; font = IdeTheme.fontUi })
        findPanel.add(tfReplace)
        findPanel.add(btnRep)
        findPanel.add(btnRepAll)
        findPanel.add(lblMatchCount)
        findPanel.add(btnClose)
    }

    fun showFindPanel() {
        findPanel.isVisible = true
        tfFind.requestFocusInWindow()
        tfFind.selectAll()
        revalidate()
    }

    fun hideFindPanel() {
        findPanel.isVisible = false
        pane.requestFocusInWindow()
        revalidate()
    }

    private fun findNext(forward: Boolean) {
        val query = tfFind.text
        if (query.isEmpty()) return
        val text = pane.text.lowercase()
        val q = query.lowercase()
        var pos = if (forward) pane.selectionEnd else pane.selectionStart - 1
        if (pos < 0) pos = 0

        var idx = if (forward) text.indexOf(q, pos) else text.lastIndexOf(q, pos)
        if (idx < 0) {
            idx = if (forward) text.indexOf(q) else text.lastIndexOf(q)
        }

        if (idx >= 0) {
            pane.select(idx, idx + q.length)
            pane.requestFocusInWindow()
        }
    }

    private fun replaceOne() {
        if (pane.selectedText != null) {
            pane.replaceSelection(tfReplace.text)
            findNext(true)
        } else {
            findNext(true)
        }
    }

    private fun replaceAll() {
        val q = tfFind.text
        if (q.isEmpty()) return
        val text = pane.text
        val newText = text.replace(q, tfReplace.text, ignoreCase = true)
        setTextContent(newText)
        isModified = true
        onModificationChanged?.invoke()
    }

    private fun setupAutocomplete() {
        autoList.background = IdeTheme.bgHeader
        autoList.foreground = IdeTheme.fgMain
        autoList.selectionBackground = IdeTheme.bgActive
        autoList.selectionForeground = IdeTheme.accent
        autoList.font = IdeTheme.fontCode
        autoList.border = EmptyBorder(4, 4, 4, 4)

        autoPopup.border = BorderFactory.createLineBorder(IdeTheme.line)
        autoPopup.add(JScrollPane(autoList).apply {
            preferredSize = Dimension(220, 140)
            border = BorderFactory.createEmptyBorder()
        })

        autoList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) insertAutocomplete()
            }
        })
    }

    private fun triggerAutocomplete() {
        val pos = pane.caretPosition
        val text = pane.text
        var start = pos - 1
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '_')) {
            start--
        }
        start++
        val prefix = text.substring(start, pos).lowercase()
        if (prefix.length < 2) {
            autoPopup.isVisible = false
            return
        }

        val candidates = listOf(
            "задать", "изменить", "напечатать", "если", "то", "иначе", "конец",
            "повтори", "пока", "всегда", "создать окно", "нарисовать", "играть звук",
            "когда будет получено", "вешать всем", "спросить", "список", "файл", "истина", "ложь"
        ).filter { it.startsWith(prefix) }

        if (candidates.isEmpty()) {
            autoPopup.isVisible = false
            return
        }

        autoList.setListData(candidates.toTypedArray())
        autoList.selectedIndex = 0

        try {
            val rect = pane.modelToView(pos)
            autoPopup.show(pane, rect.x, rect.y + rect.height)
        } catch (e: Exception) {}
    }

    private fun insertAutocomplete() {
        val selected = autoList.selectedValue ?: return
        val pos = pane.caretPosition
        val text = pane.text
        var start = pos - 1
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '_')) {
            start--
        }
        start++
        doc.replace(start, pos - start, selected, null)
        autoPopup.isVisible = false
        pane.requestFocusInWindow()
    }

    private fun setupGhost() {
        ghostLine.font = IdeTheme.fontCode
        ghostHint.font = IdeTheme.fontUi
        ghostHint.foreground = IdeTheme.fgMuted
        val panel = JPanel(BorderLayout()).apply {
            background = IdeTheme.bgHeader
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(IdeTheme.line),
                EmptyBorder(6, 10, 6, 10)
            )
            add(ghostLine, BorderLayout.CENTER)
            add(ghostHint, BorderLayout.SOUTH)
        }
        ghostPopup.add(panel)
        ghostPopup.border = BorderFactory.createEmptyBorder()
        pane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                hideGhost()
            }
        })
        val im = pane.getInputMap(JComponent.WHEN_FOCUSED)
        val imWin = pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = pane.actionMap
        val defUp = am.get(DefaultEditorKit.upAction)
        val defDown = am.get(DefaultEditorKit.downAction)
        fun putBoth(ks: KeyStroke, name: String, act: javax.swing.AbstractAction) {
            im.put(ks, name)
            imWin.put(ks, name)
            am.put(name, act)
        }
        putBoth(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "ghostTab", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (ghostPopup.isVisible) acceptGhost()
                else {
                    try { doc.insertString(pane.caretPosition, "    ", null) } catch (ex: Exception) {}
                }
            }
        })
        putBoth(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ghostEsc", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (ghostPopup.isVisible) hideGhost()
                else autoPopup.isVisible = false
            }
        })
        putBoth(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "ghostUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (ghostPopup.isVisible) cycleGhost(-1)
                else if (autoPopup.isVisible) autoList.selectedIndex = (autoList.selectedIndex - 1 + autoList.model.size) % autoList.model.size
                else defUp?.actionPerformed(e)
            }
        })
        putBoth(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "ghostDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (ghostPopup.isVisible) cycleGhost(1)
                else if (autoPopup.isVisible) autoList.selectedIndex = (autoList.selectedIndex + 1) % autoList.model.size
                else defDown?.actionPerformed(e)
            }
        })
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && ghostPopup.isVisible) {
                when (e.keyCode) {
                    KeyEvent.VK_TAB -> { acceptGhost(); e.consume(); true }
                    KeyEvent.VK_UP -> { cycleGhost(-1); e.consume(); true }
                    KeyEvent.VK_DOWN -> { cycleGhost(1); e.consume(); true }
                    KeyEvent.VK_ESCAPE -> { hideGhost(); e.consume(); true }
                    else -> false
                }
            } else false
        }
    }

    private fun renderGhost() {
        val cand = ghostWords.getOrElse(ghostIx) { return }
        val count = if (ghostWords.size > 1) " <font color='#6C7086'>${ghostIx + 1}/${ghostWords.size}</font>" else ""
        ghostLine.text = "<html><font color='#CDD6F4'>$ghostBase</font> <font color='#6C7086'>$cand</font>$count</html>"
        ghostPopup.preferredSize = null
        ghostPopup.pack()
        try {
            val rect = pane.modelToView(pane.caretPosition)
            val x = rect.x
            val y = rect.y - ghostPopup.preferredSize.height - 4
            ghostPopup.show(pane, x, maxOf(y, 0))
        } catch (e: Exception) {
            ghostPopup.isVisible = false
        }
    }

    fun showGhost(base: String, cands: List<String>) {
        if (cands.isEmpty()) {
            hideGhost()
            return
        }
        ghostBase = base
        ghostWords = cands
        ghostIx = 0
        renderGhost()
    }

    fun hideGhost() {
        ghostWords = emptyList()
        ghostPopup.isVisible = false
    }

    fun acceptGhost(): Boolean {
        val cand = ghostWords.getOrNull(ghostIx) ?: return false
        try {
            doc.insertString(pane.caretPosition, cand + " ", null)
        } catch (e: Exception) {
            return false
        }
        hideGhost()
        pane.requestFocusInWindow()
        return true
    }

    fun cycleGhost(d: Int) {
        if (ghostWords.isEmpty()) return
        ghostIx = (ghostIx + d + ghostWords.size * 100) % ghostWords.size
        renderGhost()
    }

    private fun maybeGhost() {
        maybeGhostAt(pane.caretPosition)
    }

    private fun maybeGhostAt(pos: Int) {
        if (autoPopup.isVisible) return
        val root = doc.defaultRootElement
        val lineIdx = root.getElementIndex(pos)
        val lineStart = root.getElement(lineIdx).startOffset
        val prefix = try {
            doc.getText(lineStart, pos - lineStart)
        } catch (e: Exception) {
            hideGhost()
            return
        }
        if (!prefix.endsWith(" ") && !prefix.endsWith("\t")) {
            hideGhost()
            return
        }
        val prev = prefix.trim().split(Regex("\\s+")).lastOrNull() ?: ""
        if (prev.isEmpty()) {
            hideGhost()
            return
        }
        showGhost(prev, ideNextWords(prev))
    }

    private fun setupListeners() {
        doc.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                onTyped(e)
            }
            override fun removeUpdate(e: DocumentEvent?) {
                onChange()
                hideGhost()
            }
            override fun changedUpdate(e: DocumentEvent?) {}

            private fun onChange() {
                if (!isModified) {
                    isModified = true
                    onModificationChanged?.invoke()
                }
                SwingUtilities.invokeLater {
                    highlighter.rehighlightAll()
                }
            }

            private fun onTyped(e: DocumentEvent?) {
                onChange()
                try {
                    if (e == null) {
                        hideGhost()
                        return
                    }
                    val ins = doc.getText(e.offset, e.length)
                    if (!ins.contains(" ") && !ins.contains("\t") && !ins.contains("\n")) {
                        hideGhost()
                        return
                    }
                    maybeGhostAt(e.offset + e.length)
                } catch (ex: Exception) {
                    hideGhost()
                }
            }
        })

        pane.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (ghostPopup.isVisible) {
                    when (e.keyCode) {
                        KeyEvent.VK_TAB -> {
                            acceptGhost()
                            e.consume()
                            return
                        }
                        KeyEvent.VK_DOWN -> {
                            cycleGhost(1)
                            e.consume()
                            return
                        }
                        KeyEvent.VK_UP -> {
                            cycleGhost(-1)
                            e.consume()
                            return
                        }
                        KeyEvent.VK_ESCAPE -> {
                            hideGhost()
                            e.consume()
                            return
                        }
                        else -> hideGhost()
                    }
                }
                if (e.isControlDown && e.keyCode == KeyEvent.VK_SPACE) {
                    triggerAutocomplete()
                    e.consume()
                } else if (autoPopup.isVisible) {
                    if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_TAB) {
                        insertAutocomplete()
                        e.consume()
                    } else if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        autoPopup.isVisible = false
                        e.consume()
                    } else if (e.keyCode == KeyEvent.VK_DOWN) {
                        autoList.selectedIndex = (autoList.selectedIndex + 1) % autoList.model.size
                        e.consume()
                    } else if (e.keyCode == KeyEvent.VK_UP) {
                        autoList.selectedIndex = (autoList.selectedIndex - 1 + autoList.model.size) % autoList.model.size
                        e.consume()
                    }
                } else if (e.keyCode == KeyEvent.VK_ENTER) {
                    val caret = pane.caretPosition
                    val root = doc.defaultRootElement
                    val lineIdx = root.getElementIndex(caret)
                    val lineElem = root.getElement(lineIdx)
                    val lineText = doc.getText(lineElem.startOffset, lineElem.endOffset - lineElem.startOffset)
                    var indent = ""
                    for (ch in lineText) {
                        if (ch == ' ' || ch == '\t') indent += ch else break
                    }
                    if (indent.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            doc.insertString(pane.caretPosition, indent, null)
                        }
                    }
                }
            }
        })
    }

    fun gotoLine(lineNo: Int) {
        val root = doc.defaultRootElement
        if (lineNo in 1..root.elementCount) {
            val elem = root.getElement(lineNo - 1)
            pane.caretPosition = elem.startOffset
            pane.requestFocusInWindow()
        }
    }
}

class DarkTreeCellRenderer : DefaultTreeCellRenderer() {
    init {
        backgroundNonSelectionColor = IdeTheme.bgDeep
        backgroundSelectionColor = IdeTheme.bgActive
        textNonSelectionColor = IdeTheme.fgMain
        textSelectionColor = IdeTheme.accent
        borderSelectionColor = Color(0, 0, 0, 0)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        val dictItem = node?.userObject as? DictItem
        if (dictItem != null) {
            text = dictItem.cmd
        } else {
            val file = node?.userObject as? File
            if (file != null) {
                text = file.name
            } else if (node?.userObject is String) {
                text = node.userObject as String
            }
        }
        c.foreground = if (sel) IdeTheme.accent else IdeTheme.fgMain
        c.background = if (sel) IdeTheme.bgActive else IdeTheme.bgDeep
        if (c is JComponent) {
            c.isOpaque = sel
        }
        return c
    }
}

class FileExplorerPanel(
    val rootDir: File,
    val onFileSelected: (File) -> Unit,
    val onNewFileRequested: () -> Unit,
    val onHideRequested: () -> Unit
) : JPanel(BorderLayout()) {

    private val treeModel: DefaultTreeModel
    private val tree: JTree

    init {
        background = IdeTheme.bgDeep
        preferredSize = Dimension(220, 0)
        border = BorderFactory.createMatteBorder(0, 0, 0, 1, IdeTheme.line)

        val rootNode = DefaultMutableTreeNode(rootDir.name)
        treeModel = DefaultTreeModel(rootNode)
        tree = JTree(treeModel)
        tree.background = IdeTheme.bgDeep
        tree.foreground = IdeTheme.fgMain
        tree.font = IdeTheme.fontUi
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.cellRenderer = DarkTreeCellRenderer()

        val header = JPanel(BorderLayout())
        header.background = IdeTheme.bgHeader
        header.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, IdeTheme.line),
            EmptyBorder(6, 10, 6, 10)
        )

        val title = JLabel("ПРОЕКТ").apply {
            font = IdeTheme.fontTitle
            foreground = IdeTheme.fgMuted
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(FlatIconButton("+", "Новый файл") { onNewFileRequested() })
            add(FlatIconButton("R", "Обновить") { refreshTree() })
            add(FlatIconButton("<<", "Скрыть дерево папок") { onHideRequested() })
        }

        header.add(title, BorderLayout.WEST)
        header.add(actions, BorderLayout.EAST)

        val scroll = JScrollPane(tree).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = IdeTheme.bgDeep
            verticalScrollBar.setUI(ModernScrollBarUI())
            horizontalScrollBar.setUI(ModernScrollBarUI())
        }

        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        tree.addTreeSelectionListener { e: TreeSelectionEvent ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val file = node.userObject as? File ?: return@addTreeSelectionListener
            if (file.isFile) {
                onFileSelected(file)
            }
        }

        refreshTree()
    }

    fun refreshTree() {
        val rootNode = DefaultMutableTreeNode(rootDir.name)
        buildNodes(rootDir, rootNode)
        treeModel.setRoot(rootNode)
        treeModel.reload()
    }

    private fun buildNodes(dir: File, parentNode: DefaultMutableTreeNode) {
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (f in files) {
            if (f.name.startsWith(".") || f.name.endsWith(".jar") || f.name.endsWith(".class")) continue
            val node = DefaultMutableTreeNode(f)
            parentNode.add(node)
            if (f.isDirectory) {
                buildNodes(f, node)
            }
        }
    }
}

data class DictItem(val cmd: String, val examples: List<String>)
data class DictGroup(val name: String, val items: List<DictItem>)

fun ideDictionary(): List<DictGroup> {
    return listOf(
        DictGroup("Переменные", listOf(
            DictItem("задать", listOf("задать счёт 0", "задать имя Иван")),
            DictItem("изменить", listOf("изменить счёт \"счёт\" плюс 1")),
            DictItem("показать переменную", listOf("показать переменную счёт 200 160 60 100 255 255 255")),
            DictItem("свойство", listOf("свойство х объекта герой", "напечатать свойство ширины объекта стена"))
        )),
        DictGroup("Условия", listOf(
            DictItem("если", listOf("если \"возраст\" больше или равно 18 то вывести взрослый иначе вывести маленький конец")),
            DictItem("не", listOf("если не \"готово\" то вывести ждём конец")),
            DictItem("как только", listOf("как только \"готово\" равно да то вывести ура конец")),
            DictItem("ждать пока", listOf("ждать пока \"готово\" равно да"))
        )),
        DictGroup("Циклы", listOf(
            DictItem("повтори", listOf("повтори 3 раза\nнапечатать привет\nконец")),
            DictItem("пока", listOf("пока \"счёт\" меньше 5\nизменить счёт \"счёт\" плюс 1\nконец")),
            DictItem("повторяй", listOf("повторяй\nждать 1 секунду\nконец")),
            DictItem("каждые", listOf("каждые 2 секунды\nвывести тик"))
        )),
        DictGroup("Процедуры и события", listOf(
            DictItem("чтобы", listOf("чтобы прыжок\nвывести прыг")),
            DictItem("вызвать", listOf("вызвать прыжок")),
            DictItem("вещать", listOf("вещать всем старт")),
            DictItem("когда будет получено", listOf("когда будет получено старт\nвывести поехали"))
        )),
        DictGroup("Окно и объекты", listOf(
            DictItem("создать окно", listOf("создать окно 800 600 мояигра")),
            DictItem("масштабирование окна", listOf("масштабирование окна истина", "масштабирование окна ложь", "масштабирование окна 1")),
            DictItem("нарисовать", listOf("нарисовать герой 0 0")),
            DictItem("прозрачность", listOf("задать прозрачность 60 объекту герой")),
            DictItem("размер", listOf("задать размер 120 объекту герой")),
            DictItem("цвет", listOf("задать цвет красный объекту герой")),
            DictItem("образ", listOf("задать образ объекту герой 1.png")),
            DictItem("текстура", listOf("задать текстуру офис.png объекту какашка")),
            DictItem("поворот", listOf("повернуть налево на 15 объекту герой")),
            DictItem("фон", listOf("задать фон белый")),
            DictItem("темнота", listOf("задать темноту 70")),
            DictItem("свет", listOf("создать свет лампа 100 50", "привязать свет фонарь к объекту игрок")),
            DictItem("написать", listOf("написать табло привет")),
            DictItem("показать", listOf("скрыть герой", "показать герой")),
            DictItem("следить", listOf("следить за объектом герой истина", "следить за объектом герой ложь")),
            DictItem("запустить", listOf("запустить уровень2.ncode", "запустить меню.ncode истина")),
            DictItem("клон", listOf("создать клон герой"))
        )),
        DictGroup("Движение", listOf(
            DictItem("идти", listOf("идти 10 шагов объекту герой")),
            DictItem("плыть", listOf("плыть к 100 50 за 2 секунды объекту герой")),
            DictItem("скорость", listOf("задать скорость 100 объекту мяч")),
            DictItem("отскочить", listOf("отскочить от края объекту мяч")),
            DictItem("касается", listOf("если касается герой стена то вывести бум конец")),
            DictItem("нажата", listOf("если нажата пробел то вывести держишь конец"))
        )),
        DictGroup("Звук", listOf(
            DictItem("играть звук", listOf("играть звук 1.mp3", "играть звук и ждать 1.mp3")),
            DictItem("играть музыку", listOf("играть музыку фон.mp3")),
            DictItem("остановить музыку", listOf("остановить музыку")),
            DictItem("остановить звук", listOf("остановить звук 1.mp3")),
            DictItem("громкость", listOf("задать громкость 60 для звука 1.mp3"))
        )),
        DictGroup("Файлы и списки", listOf(
            DictItem("создать список", listOf("создать список очки")),
            DictItem("перемешать список", listOf("перемешать список очки")),
            DictItem("записать в файл", listOf("записать в файл saves/очки.txt 100")),
            DictItem("прочитать файл", listOf("напечатать прочитать файл saves/очки.txt")),
            DictItem("есть ли файл", listOf("если есть ли файл saves/очки.txt то вывести есть конец")),
            DictItem("создать папку", listOf("создать папку моисевы"))
        )),
        DictGroup("Интернет", listOf(
            DictItem("запрос", listOf("запрос гет https://example.com сохранить в ответ", "запрос пост https://httpbin.org/post данные привет сохранить в ответ")),
            DictItem("код ответа", listOf("напечатать код ответа")),
            DictItem("записать в базу", listOf("записать в базу https://моя-игра.firebaseio.com очки/рекорд 100")),
            DictItem("создать в базе", listOf("создать в базе https://моя-игра.firebaseio.com очки/рекорд 100")),
            DictItem("прочитать базу", listOf("прочитать базу https://моя-игра.firebaseio.com очки/рекорд сохранить в рекорд")),
            DictItem("удалить из базы", listOf("удалить из базы https://моя-игра.firebaseio.com очки/рекорд"))
        )),
        DictGroup("Формулы", listOf(
            DictItem("случайно", listOf("задать кубик случайно 1 6")),
            DictItem("время", listOf("задать старт время")),
            DictItem("синус", listOf("напечатать синус 90"))
        )),
        DictGroup("Ввод", listOf(
            DictItem("спросить", listOf("спросить Как зовут сохранить в имя")),
            DictItem("когда нажата клавиша", listOf("когда нажата клавиша пробел\nвывести прыг")),
            DictItem("когда отпущена клавиша", listOf("когда отпущена клавиша a\nзадать скорость 0 0 объекту игрок", "когда отпущена пробел\nвывести отпустил")),
            DictItem("при нажатии", listOf("при нажатии\nвывести клик")),
            DictItem("ждать", listOf("ждать 2 секунды")),
            DictItem("напечатать", listOf("напечатать привет"))
        ))
    )
}

fun ideNextWords(first: String): List<String> {
    return when (first.lowercase()) {
        "задать" -> listOf("прозрачность", "размер", "поворот", "цвет", "образ", "текстуру", "костюм", "форма", "слой", "скорость", "фон", "громкость")
        "изменить", "поменять", "присвоить", "сделать" -> listOf("прозрачность", "размер", "поворот", "цвет", "образ", "текстуру", "костюм", "форма", "слой", "скорость", "фон", "громкость")
        "создать" -> listOf("окно", "список", "клон", "папку")
        "показать" -> listOf("переменную")
        "скрыть" -> listOf("переменную")
        "играть", "включить" -> listOf("звук", "музыку")
        "остановить", "выключить" -> listOf("звук", "музыку")
        "продолжить", "пауза" -> listOf("звук", "музыку")
        "когда" -> listOf("нажата", "отпущена", "создан", "будет", "получено")
        "при" -> listOf("нажатии", "отпускании", "движении", "клике")
        "нажата" -> listOf("клавиша")
        "отпущена", "отпущен", "отпущено" -> listOf("клавиша")
        "ждать" -> listOf("пока", "до")
        "свойство" -> listOf("х", "у", "размера", "ширины", "высоты", "прозрачности")
        "повтори", "повторить", "повторять" -> listOf("раз", "раза", "разов")
        else -> emptyList()
    }
}

class DictionaryPanel(
    val onInsert: (String) -> Unit,
    var onHideRequested: () -> Unit = {}
) : JPanel(BorderLayout()) {
    private val exampleArea = JTextArea()

    init {
        background = IdeTheme.bgDeep
        preferredSize = Dimension(300, 0)
        border = BorderFactory.createMatteBorder(0, 1, 0, 0, IdeTheme.line)

        val header = JPanel(BorderLayout())
        header.background = IdeTheme.bgHeader
        header.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, IdeTheme.line),
            EmptyBorder(6, 10, 6, 10)
        )
        val title = JLabel("СЛОВАРЬ").apply {
            font = IdeTheme.fontTitle
            foreground = IdeTheme.fgMuted
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(FlatIconButton(">>", "Скрыть словарь") { onHideRequested() })
        }
        header.add(title, BorderLayout.WEST)
        header.add(actions, BorderLayout.EAST)

        val root = DefaultMutableTreeNode("Команды")
        for (g in ideDictionary()) {
            val gn = DefaultMutableTreeNode(g.name)
            for (item in g.items) {
                gn.add(DefaultMutableTreeNode(item))
            }
            root.add(gn)
        }
        val tree = JTree(DefaultTreeModel(root))
        tree.background = IdeTheme.bgDeep
        tree.foreground = IdeTheme.fgMain
        tree.font = IdeTheme.fontUi
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = DarkTreeCellRenderer()
        tree.addTreeSelectionListener { e: TreeSelectionEvent ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val item = node.userObject as? DictItem ?: return@addTreeSelectionListener
            exampleArea.text = item.examples.joinToString("\n\n")
            exampleArea.caretPosition = 0
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val item = node.userObject as? DictItem ?: return
                    onInsert(item.examples.first())
                }
            }
        })
        val treeScroll = JScrollPane(tree).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = IdeTheme.bgDeep
            verticalScrollBar.setUI(ModernScrollBarUI())
        }

        exampleArea.font = IdeTheme.fontCode
        exampleArea.background = IdeTheme.bgEditor
        exampleArea.foreground = IdeTheme.synString
        exampleArea.isEditable = false
        exampleArea.border = EmptyBorder(6, 8, 6, 8)
        val exScroll = JScrollPane(exampleArea).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = IdeTheme.bgEditor
            verticalScrollBar.setUI(ModernScrollBarUI())
            preferredSize = Dimension(0, 130)
        }
        val btnInsert = FlatButton("Вставить", "new") { insertCurrent() }
        val bottom = JPanel(BorderLayout()).apply {
            background = IdeTheme.bgHeader
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, IdeTheme.line)
            add(exScroll, BorderLayout.CENTER)
            val bar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
                isOpaque = false
                add(btnInsert)
            }
            add(bar, BorderLayout.SOUTH)
        }

        add(header, BorderLayout.NORTH)
        add(treeScroll, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
    }

    private fun insertCurrent() {
        val t = exampleArea.text.substringBefore("\n\n").trim()
        if (t.isNotEmpty()) onInsert(t)
    }
}

class ColorPanel(val onPick: ((Int, Int, Int) -> Unit)? = null) : JPanel(BorderLayout()) {
    private val slider = JSlider(0, 100, 100)
    private val wheel = WheelView()
    private val preview = JPanel()
    val rgbLabel = JLabel("", SwingConstants.CENTER)
    private var r = 255
    private var g = 0
    private var b = 0

    init {
        background = IdeTheme.bgHeader
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IdeTheme.line),
            EmptyBorder(10, 10, 10, 10)
        )
        wheel.preferredSize = Dimension(170, 170)
        slider.background = IdeTheme.bgHeader
        slider.foreground = IdeTheme.fgMuted
        val mid = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(wheel, BorderLayout.CENTER)
            add(slider, BorderLayout.SOUTH)
        }
        preview.background = Color(r, g, b)
        preview.preferredSize = Dimension(0, 34)
        preview.border = BorderFactory.createLineBorder(IdeTheme.line)
        rgbLabel.font = IdeTheme.fontCode
        rgbLabel.foreground = IdeTheme.fgMain
        refreshLabel()
        val btnCopy = FlatButton("Копировать", null) { doCopy() }
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(preview, BorderLayout.NORTH)
            add(rgbLabel, BorderLayout.CENTER)
            val bar = JPanel(FlowLayout(FlowLayout.CENTER, 0, 6)).apply {
                isOpaque = false
                add(btnCopy)
            }
            add(bar, BorderLayout.SOUTH)
        }
        add(mid, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        wheel.setMarker(255, 0, 0)
    }

    fun rgbText(): String = "$r $g $b"

    fun pickAt(px: Int, py: Int) {
        val c = wheel.pick(px, py, slider.value / 100f)
        if (c != null) setRgb(c[0], c[1], c[2])
    }

    fun setRgb(nr: Int, ng: Int, nb: Int) {
        r = nr.coerceIn(0, 255)
        g = ng.coerceIn(0, 255)
        b = nb.coerceIn(0, 255)
        preview.background = Color(r, g, b)
        refreshLabel()
        wheel.setMarker(r, g, b)
        onPick?.invoke(r, g, b)
    }

    private fun refreshLabel() {
        rgbLabel.text = rgbText()
    }

    fun doCopy(): Boolean {
        return try {
            val sel = java.awt.datatransfer.StringSelection(rgbText())
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private inner class WheelView : JComponent() {
        private var img: java.awt.image.BufferedImage? = null
        private var lastV = -1f
        private var mr = -1f
        private var ma = 0f

        init {
            preferredSize = Dimension(170, 170)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    pickAt(e.x, e.y)
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    pickAt(e.x, e.y)
                }
            })
        }

        fun setMarker(nr: Int, ng: Int, nb: Int) {
            val hsb = FloatArray(3)
            Color.RGBtoHSB(nr, ng, nb, hsb)
            ma = hsb[0] * 360f
            mr = hsb[1]
            repaint()
        }

        fun pick(px: Int, py: Int, v: Float): IntArray? {
            val cx = width / 2f
            val cy = height / 2f
            val rad = minOf(width, height) / 2f - 4f
            if (rad <= 0) return null
            var dx = (px - cx) / rad
            var dy = (py - cy) / rad
            val d = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d > 1f) {
                dx /= d
                dy /= d
            }
            var hue = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toDouble()).toFloat()
            if (hue < 0) hue += 360f
            val sat = minOf(d, 1f)
            val rgb = Color.HSBtoRGB(hue / 360f, sat, v.coerceIn(0f, 1f))
            return intArrayOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        }

        private fun rebuild() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val v = (slider.value / 100f).coerceIn(0f, 1f)
            val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val cx = w / 2f
            val cy = h / 2f
            val rad = minOf(w, h) / 2f - 4f
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val dx = (x - cx) / rad
                    val dy = (y - cy) / rad
                    val d = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    if (d <= 1.0) {
                        var hue = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                        if (hue < 0) hue += 360f
                        out.setRGB(x, y, Color.HSBtoRGB(hue / 360f, d.toFloat(), v))
                    }
                    x++
                }
                y++
            }
            img = out
        }

        override fun paintComponent(gr: Graphics) {
            super.paintComponent(gr)
            val v = (slider.value / 100f).coerceIn(0f, 1f)
            if (img == null || img!!.width != width || img!!.height != height || v != lastV) {
                lastV = v
                rebuild()
            }
            val g2 = gr.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                img?.let { g2.drawImage(it, 0, 0, null) }
                if (mr >= 0f) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val rad = minOf(width, height) / 2f - 4f
                    val a = Math.toRadians(ma.toDouble())
                    val mx = cx + kotlin.math.cos(a).toFloat() * mr * rad
                    val my = cy + kotlin.math.sin(a).toFloat() * mr * rad
                    g2.color = Color.WHITE
                    g2.stroke = BasicStroke(2f)
                    g2.drawOval((mx - 6).toInt(), (my - 6).toInt(), 12, 12)
                    g2.color = Color(20, 20, 30)
                    g2.drawOval((mx - 7).toInt(), (my - 7).toInt(), 14, 14)
                }
            } finally {
                g2.dispose()
            }
        }

        init {
            slider.addChangeListener { repaint() }
        }
    }
}

class BottomDockPanel(val onGotoLine: (Int) -> Unit, val onStopRequested: () -> Unit) : JPanel(BorderLayout()) {
    private val tabbedPane = JTabbedPane()
    val consoleArea = JTextArea()
    val inputField = JTextField()
    val diagListModel = DefaultListModel<String>()
    val diagList = JList(diagListModel)
    @Volatile private var stdinStream: java.io.OutputStream? = null

    private fun onEdt(f: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) f()
        else SwingUtilities.invokeLater { f() }
    }

    init {
        preferredSize = Dimension(0, 180)
        background = IdeTheme.bgHeader
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, IdeTheme.line)

        consoleArea.font = IdeTheme.fontCode
        consoleArea.background = IdeTheme.bgEditor
        consoleArea.foreground = IdeTheme.fgMain
        consoleArea.isEditable = false
        consoleArea.border = EmptyBorder(6, 8, 6, 8)

        val consoleScroll = JScrollPane(consoleArea).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = IdeTheme.bgEditor
            verticalScrollBar.setUI(ModernScrollBarUI())
        }

        inputField.background = IdeTheme.bgEditor
        inputField.foreground = IdeTheme.fgMain
        inputField.caretColor = IdeTheme.caret
        inputField.font = IdeTheme.fontUi
        inputField.toolTipText = "Ввод для «спросить» (Enter — отправить)"
        inputField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, IdeTheme.line),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)
        )
        inputField.addActionListener {
            val t = inputField.text
            inputField.text = ""
            sendInput(t)
        }

        val consoleTab = JPanel(BorderLayout()).apply {
            background = IdeTheme.bgEditor
            add(consoleScroll, BorderLayout.CENTER)
            add(inputField, BorderLayout.SOUTH)
        }

        diagList.font = IdeTheme.fontUi
        diagList.background = IdeTheme.bgEditor
        diagList.foreground = IdeTheme.accentError
        diagList.selectionBackground = IdeTheme.bgActive
        diagList.selectionForeground = IdeTheme.fgMain
        diagList.border = EmptyBorder(4, 8, 4, 8)

        val diagScroll = JScrollPane(diagList).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = IdeTheme.bgEditor
            verticalScrollBar.setUI(ModernScrollBarUI())
        }

        diagList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val sel = diagList.selectedValue ?: return
                    val lineMatch = Pattern.compile("строке?\\s+(\\d+)").matcher(sel)
                    if (lineMatch.find()) {
                        val ln = lineMatch.group(1).toInt()
                        onGotoLine(ln)
                    }
                }
            }
        })

        tabbedPane.background = IdeTheme.bgHeader
        tabbedPane.foreground = IdeTheme.fgMain
        tabbedPane.font = IdeTheme.fontTitle

        tabbedPane.addTab("Консоль вывода", consoleTab)
        tabbedPane.addTab("Ошибки и Проверка", diagScroll)

        val headerTool = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            background = IdeTheme.bgHeader
            add(FlatIconButton("■", "Остановить запуск") { onStopRequested() })
            add(FlatIconButton("C", "Очистить консоль") { clearConsole() })
        }

        val topBar = JPanel(BorderLayout()).apply {
            background = IdeTheme.bgHeader
            add(tabbedPane, BorderLayout.CENTER)
            add(headerTool, BorderLayout.EAST)
        }

        add(topBar, BorderLayout.CENTER)
    }

    fun setStdin(s: java.io.OutputStream?) {
        stdinStream = s
    }

    fun sendInput(line: String) {
        val s = stdinStream ?: return
        try {
            appendConsole("> " + line)
            s.write((line + "\n").toByteArray(Charsets.UTF_8))
            s.flush()
        } catch (e: Exception) {
        }
    }

    fun appendConsole(text: String) {
        onEdt {
            val t = if (text.endsWith("\n")) text else text + "\n"
            consoleArea.append(t)
            consoleArea.caretPosition = consoleArea.document.length
            tabbedPane.selectedIndex = 0
        }
    }

    fun clearConsole() {
        onEdt {
            consoleArea.text = ""
            diagListModel.clear()
        }
    }

    fun setDiagnostics(errors: List<String>) {
        onEdt {
            diagListModel.clear()
            for (err in errors) {
                diagListModel.addElement(err)
            }
            if (errors.isNotEmpty()) {
                tabbedPane.selectedIndex = 1
            }
        }
    }
}

fun applyDarkThemeDefaults() {
    UIManager.put("Panel.background", IdeTheme.bgDeep)
    UIManager.put("Viewport.background", IdeTheme.bgEditor)
    
    UIManager.put("Tree.background", IdeTheme.bgDeep)
    UIManager.put("Tree.textBackground", IdeTheme.bgDeep)
    UIManager.put("Tree.textForeground", IdeTheme.fgMain)
    UIManager.put("Tree.selectionBackground", IdeTheme.bgActive)
    UIManager.put("Tree.selectionForeground", IdeTheme.accent)
    UIManager.put("Tree.rendererFillBackground", true)

    UIManager.put("TabbedPane.background", IdeTheme.bgHeader)
    UIManager.put("TabbedPane.foreground", IdeTheme.fgMain)
    UIManager.put("TabbedPane.selected", IdeTheme.bgEditor)
    UIManager.put("TabbedPane.selectedForeground", IdeTheme.accent)
    UIManager.put("TabbedPane.contentAreaColor", IdeTheme.bgEditor)
    UIManager.put("TabbedPane.focus", IdeTheme.bgHeader)
    UIManager.put("TabbedPane.borderHighlightColor", IdeTheme.line)
    UIManager.put("TabbedPane.darkShadow", IdeTheme.bgHeader)

    UIManager.put("TextField.background", IdeTheme.bgEditor)
    UIManager.put("TextField.foreground", IdeTheme.fgMain)
    UIManager.put("TextField.caretForeground", IdeTheme.caret)
    UIManager.put("TextField.selectionBackground", IdeTheme.selection)
    UIManager.put("TextField.selectionForeground", IdeTheme.fgMain)

    UIManager.put("TextArea.background", IdeTheme.bgEditor)
    UIManager.put("TextArea.foreground", IdeTheme.fgMain)

    UIManager.put("TextPane.background", IdeTheme.bgEditor)
    UIManager.put("TextPane.foreground", IdeTheme.fgMain)

    UIManager.put("List.background", IdeTheme.bgEditor)
    UIManager.put("List.foreground", IdeTheme.fgMain)
    UIManager.put("List.selectionBackground", IdeTheme.bgActive)
    UIManager.put("List.selectionForeground", IdeTheme.fgMain)

    UIManager.put("SplitPane.background", IdeTheme.bgDeep)
    UIManager.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder())

    UIManager.put("ScrollPane.background", IdeTheme.bgEditor)
    UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder())

    UIManager.put("Label.foreground", IdeTheme.fgMain)
    UIManager.put("Button.background", IdeTheme.bgHover)
    UIManager.put("Button.foreground", IdeTheme.fgMain)
    UIManager.put("OptionPane.background", IdeTheme.bgDeep)
    UIManager.put("OptionPane.messageForeground", IdeTheme.fgMain)
}

fun runIde() {
    try {
        UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel")
    } catch (e: Exception) {}

    applyDarkThemeDefaults()

    SwingUtilities.invokeLater {
        val frame = JFrame("Ncode IDE v2.1 — Современная среда разработки")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.background = IdeTheme.bgDeep

        val stateBaseDir = ideBaseDir()
        val workspaceDir = File(stateBaseDir)
        var lastDir = stateBaseDir

        fun pickOpen(): File? {
            val d = java.awt.FileDialog(frame, "Открыть", java.awt.FileDialog.LOAD)
            d.directory = lastDir
            d.setFilenameFilter { _, name -> name.lowercase().endsWith(".ncode") }
            d.isVisible = true
            val f = d.file ?: return null
            val out = File(d.directory, f)
            lastDir = out.parent ?: lastDir
            return out
        }

        fun pickSave(cur: File?): File? {
            val d = java.awt.FileDialog(frame, "Сохранить", java.awt.FileDialog.SAVE)
            d.directory = cur?.parent ?: lastDir
            d.file = cur?.name ?: "игра.ncode"
            d.isVisible = true
            val f = d.file ?: return null
            var out = File(d.directory, f)
            if (!out.name.lowercase().endsWith(".ncode")) out = File(out.parent, out.name + ".ncode")
            lastDir = out.parent ?: lastDir
            return out
        }

        val tabbedPane = JTabbedPane()
        tabbedPane.background = IdeTheme.bgHeader
        tabbedPane.foreground = IdeTheme.fgMain
        tabbedPane.font = IdeTheme.fontTitle

        val lblStatus = JLabel("Готов")
        val lblPosition = JLabel("Строка 1, Столбец 1")
        val lblMode = JLabel("Ncode 2D")

        val statusBar = JPanel(BorderLayout()).apply {
            background = IdeTheme.bgDeep
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, IdeTheme.line),
                EmptyBorder(4, 12, 4, 12)
            )
            lblStatus.foreground = IdeTheme.fgMuted
            lblStatus.font = IdeTheme.fontUi
            lblPosition.foreground = IdeTheme.fgMuted
            lblPosition.font = IdeTheme.fontUi
            lblMode.foreground = IdeTheme.accent
            lblMode.font = IdeTheme.fontUiBold

            add(lblStatus, BorderLayout.WEST)
            val rightInfo = JPanel(FlowLayout(FlowLayout.RIGHT, 16, 0)).apply {
                isOpaque = false
                add(lblPosition)
                add(lblMode)
            }
            add(rightInfo, BorderLayout.EAST)
        }

        lateinit var bottomDock: BottomDockPanel
        var runProc: Process? = null

        fun stopRun(note: Boolean) {
            val p = runProc
            runProc = null
            bottomDock.setStdin(null)
            if (p != null && p.isAlive) {
                try {
                    p.destroyForcibly()
                } catch (e: Exception) {
                }
                if (note) bottomDock.appendConsole("Остановлено")
            }
        }

        bottomDock = BottomDockPanel({ lineNo ->
            val curTab = tabbedPane.selectedComponent as? NcodeEditorTab
            curTab?.gotoLine(lineNo)
        }, {
            stopRun(true)
        })
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                stopRun(false)
            }
        })

        fun updateStatus() {
            val curTab = tabbedPane.selectedComponent as? NcodeEditorTab ?: return
            val pos = curTab.pane.caretPosition
            val root = curTab.doc.defaultRootElement
            val lineIdx = root.getElementIndex(pos)
            val lineStart = root.getElement(lineIdx).startOffset
            val col = pos - lineStart + 1
            lblPosition.text = "Строка ${lineIdx + 1}, Столбец $col"

            val is2D = isGame2D(curTab.getTextContent())
            lblMode.text = if (is2D) "Ncode 2D Игра" else "Ncode Консоль"
        }

        lateinit var explorer: FileExplorerPanel
        lateinit var centerSplit: JSplitPane
        lateinit var mainSplit: JSplitPane
        var treeDivider = 220
        var dockDivider = -1

        fun setTreeVisible(v: Boolean) {
            if (v) {
                explorer.isVisible = true
                val w = if (treeDivider > 50) treeDivider else 220
                SwingUtilities.invokeLater { centerSplit.setDividerLocation(w) }
            } else {
                treeDivider = centerSplit.dividerLocation
                explorer.isVisible = false
            }
            centerSplit.revalidate()
        }

        fun setDockVisible(v: Boolean) {
            if (v) {
                bottomDock.isVisible = true
                SwingUtilities.invokeLater {
                    if (dockDivider > 50) mainSplit.setDividerLocation(dockDivider)
                    else mainSplit.setDividerLocation(0.8)
                }
            } else {
                dockDivider = mainSplit.dividerLocation
                bottomDock.isVisible = false
            }
            mainSplit.revalidate()
        }


        fun saveTab(tab: NcodeEditorTab): Boolean {
            var f = tab.file
            if (f == null) {
                val picked = pickSave(null) ?: return false
                tab.file = picked
                f = picked
            }
            val saveFile = f ?: return false
            return try {
                saveFile.writeText(tab.getTextContent(), Charsets.UTF_8)
                tab.isModified = false
                tab.onModificationChanged?.invoke()
                lblStatus.text = "Сохранено: ${saveFile.name}"
                explorer.refreshTree()
                true
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(frame, "Ошибка сохранения: ${e.message}", "Ошибка", JOptionPane.ERROR_MESSAGE)
                false
            }
        }

        fun refreshTabTitle(tab: NcodeEditorTab) {
            val idx = tabbedPane.indexOfComponent(tab)
            if (idx < 0) return
            val fn = tab.file?.name ?: "Новый файл.ncode"
            val title = if (tab.isModified) "* $fn" else fn
            tabbedPane.setTitleAt(idx, title)
            (tabbedPane.getClientProperty(tab) as? JLabel)?.text = title
        }

        fun closeTab(idx: Int) {
            if (idx < 0 || idx >= tabbedPane.tabCount) return
            val tab = tabbedPane.getComponentAt(idx) as? NcodeEditorTab ?: return
            if (tab.isModified) {
                val choice = JOptionPane.showOptionDialog(
                    frame, "Сохранить изменения?", tab.file?.name ?: "Новый файл",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, arrayOf("Сохранить", "Не сохранять", "Отмена"), "Сохранить"
                )
                if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return
                if (choice == 0 && !saveTab(tab)) return
            }
            if (tabbedPane.tabCount == 1) {
                tab.file = null
                tab.setTextContent("")
                return
            }
            tabbedPane.putClientProperty(tab, null)
            tabbedPane.removeTabAt(idx)
        }

        fun tabHeader(tab: NcodeEditorTab, title: String): JPanel {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            panel.isOpaque = false
            val label = JLabel(title)
            label.font = IdeTheme.fontUi
            label.foreground = IdeTheme.fgMain
            label.border = EmptyBorder(0, 0, 0, 6)
            val x = FlatIconButton("×", "Закрыть вкладку") {
                closeTab(tabbedPane.indexOfComponent(tab))
            }
            panel.add(label)
            panel.add(x)
            tabbedPane.putClientProperty(tab, label)
            return panel
        }

        fun createNewTab(file: File? = null, initialText: String = ""): NcodeEditorTab {
            val tab = NcodeEditorTab(file)
            if (initialText.isNotEmpty()) {
                tab.setTextContent(initialText)
            } else if (file != null && file.exists()) {
                try {
                    tab.setTextContent(file.readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(frame, "Не удалось открыть файл: ${e.message}", "Ошибка", JOptionPane.ERROR_MESSAGE)
                }
            }

            val title = file?.name ?: "Новый файл.ncode"
            tabbedPane.addTab(title, tab)
            tabbedPane.setTabComponentAt(tabbedPane.indexOfComponent(tab), tabHeader(tab, title))
            tabbedPane.setSelectedComponent(tab)

            tab.pane.addCaretListener { updateStatus() }
            tab.onModificationChanged = { refreshTabTitle(tab) }

            updateStatus()
            return tab
        }

        createNewTab(null, "создать окно 800 600 МояИгра\nвывести Привет\n")

        explorer = FileExplorerPanel(
            workspaceDir,
            onFileSelected = { selectedFile ->
                for (i in 0 until tabbedPane.tabCount) {
                    val tab = tabbedPane.getComponentAt(i) as? NcodeEditorTab
                    if (tab?.file?.canonicalPath == selectedFile.canonicalPath) {
                        tabbedPane.selectedIndex = i
                        return@FileExplorerPanel
                    }
                }
                createNewTab(selectedFile)
            },
            onNewFileRequested = {
                val name = JOptionPane.showInputDialog(frame, "Имя нового файла:", "Новый файл", JOptionPane.PLAIN_MESSAGE)
                if (!name.isNullOrBlank()) {
                    var fn = name.trim()
                    if (!fn.endsWith(".ncode")) fn += ".ncode"
                    val newFile = File(workspaceDir, fn)
                    try {
                        newFile.writeText("", Charsets.UTF_8)
                        explorer.refreshTree()
                        createNewTab(newFile)
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(frame, "Не удалось создать файл: ${e.message}", "Ошибка", JOptionPane.ERROR_MESSAGE)
                    }
                }
            },
            onHideRequested = {
                setTreeVisible(false)
            }
        )

        centerSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, tabbedPane).apply {
            dividerSize = 2
            border = BorderFactory.createEmptyBorder()
            isContinuousLayout = true
        }

        var dictDivider = -1
        lateinit var rightSplit: JSplitPane

        val dictPanel = DictionaryPanel({ text ->
            val curTab = tabbedPane.selectedComponent as? NcodeEditorTab ?: return@DictionaryPanel
            try {
                curTab.doc.insertString(curTab.pane.caretPosition, text + "\n", null)
                curTab.pane.requestFocusInWindow()
            } catch (e: Exception) {
            }
        })
        dictPanel.onHideRequested = {
            dictPanel.isVisible = false
            rightSplit.revalidate()
        }
        dictPanel.isVisible = false

        rightSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerSplit, dictPanel).apply {
            dividerSize = 2
            border = BorderFactory.createEmptyBorder()
            isContinuousLayout = true
            resizeWeight = 1.0
        }

        mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, rightSplit, bottomDock).apply {
            dividerSize = 2
            border = BorderFactory.createEmptyBorder()
            isContinuousLayout = true
            resizeWeight = 0.8
        }

        fun setDictVisible(v: Boolean) {
            if (v) {
                dictPanel.isVisible = true
                SwingUtilities.invokeLater {
                    val w = rightSplit.width
                    if (w > 0) rightSplit.setDividerLocation(maxOf(w - 300, 50))
                }
            } else {
                dictDivider = rightSplit.dividerLocation
                dictPanel.isVisible = false
            }
            rightSplit.revalidate()
        }

        fun runCurrentScript() {
            val curTab = tabbedPane.selectedComponent as? NcodeEditorTab ?: return
            if (curTab.isModified || curTab.file == null) {
                if (!saveTab(curTab)) return
            }

            val scriptFile = curTab.file ?: return
            val scriptPath = scriptFile.canonicalPath
            val dir = scriptFile.parent ?: "."
            val jar = ideOwnJar()

            stopRun(false)
            bottomDock.clearConsole()
            bottomDock.setDiagnostics(emptyList())
            bottomDock.appendConsole("Запуск программы: ${scriptFile.name}...")

            if (jar == null) {
                bottomDock.appendConsole("Ошибка: Запустите IDE через ncode.jar!")
                return
            }

            val proc = try {
                ProcessBuilder(ideRunCmd(ideJavaBin(), jar, scriptPath))
                    .directory(File(dir))
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                bottomDock.appendConsole("Не запустилось: " + (e.message ?: e.toString()))
                return
            }
            runProc = proc
            bottomDock.setStdin(proc.outputStream)
            val diags = mutableListOf<String>()
            val t = Thread {
                try {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        bottomDock.appendConsole(line)
                        if (line.contains("шибка") || line.contains("арнинг") || Regex("\\.ncode:\\d+:").containsMatchIn(line)) {
                            diags.add(line)
                        }
                    }
                } catch (e: Exception) {
                }
                val code = try {
                    proc.waitFor()
                } catch (e: Exception) {
                    -1
                }
                bottomDock.setDiagnostics(diags.toList())
                if (runProc === proc) {
                    runProc = null
                    bottomDock.setStdin(null)
                }
                if (code == 0) bottomDock.appendConsole("Готово")
                else bottomDock.appendConsole("Завершено с кодом " + code)
            }
            t.isDaemon = true
            t.start()
        }

        val toolBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply {
            background = IdeTheme.bgHeader
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, IdeTheme.line),
                EmptyBorder(2, 6, 2, 6)
            )

            val logo = JLabel("NCODE").apply {
                font = Font("Segoe UI", Font.BOLD, 15)
                foreground = IdeTheme.accent
                border = EmptyBorder(0, 8, 0, 12)
            }

            add(logo)
            add(FlatButton("Новый", "new") { createNewTab() })
            add(FlatButton("Открыть", "open") {
                val picked = pickOpen() ?: return@FlatButton
                createNewTab(picked)
            })
            add(FlatButton("Сохранить", "save") {
                val curTab = tabbedPane.selectedComponent as? NcodeEditorTab
                if (curTab != null) saveTab(curTab)
            })
            add(Box.createHorizontalStrut(8))
            add(FlatButton("Запустить", "run", isRun = true) { runCurrentScript() })
            add(Box.createHorizontalStrut(8))
            add(FlatButton("Найти", "find") {
                val curTab = tabbedPane.selectedComponent as? NcodeEditorTab
                curTab?.showFindPanel()
            })
            add(FlatButton("Проводник", "folder") {
                setTreeVisible(!explorer.isVisible)
            })
            add(FlatButton("Словарь", "book") {
                setDictVisible(!dictPanel.isVisible)
            })
            add(FlatButton("Цвета", "drop") { inv ->
                val pop = JPopupMenu()
                pop.border = BorderFactory.createEmptyBorder()
                pop.add(ColorPanel())
                pop.show(inv, 0, inv.height)
            })
            add(FlatIconButton("K", "Свернуть/Развернуть консоль") {
                setDockVisible(!bottomDock.isVisible)
            })
        }

        val rootPane = frame.rootPane
        val im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = rootPane.actionMap

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save")
        am.put("save", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val curTab = tabbedPane.selectedComponent as? NcodeEditorTab
                if (curTab != null) saveTab(curTab)
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "run")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "run")
        am.put("run", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                runCurrentScript()
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "new")
        am.put("new", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                createNewTab()
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "find")
        am.put("find", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val curTab = tabbedPane.selectedComponent as? NcodeEditorTab
                curTab?.showFindPanel()
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "close_tab")
        am.put("close_tab", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                closeTab(tabbedPane.selectedIndex)
            }
        })

        frame.contentPane.add(toolBar, BorderLayout.NORTH)
        frame.contentPane.add(mainSplit, BorderLayout.CENTER)
        frame.contentPane.add(statusBar, BorderLayout.SOUTH)

        frame.minimumSize = Dimension(900, 600)
        frame.setSize(1100, 750)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}
