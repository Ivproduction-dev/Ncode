package ncode

fun isGame2D(text: String): Boolean {
    for (raw in text.lines()) {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
        val parts = t.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) continue
        val a = parts[0].lowercase()
        val b = parts[1].lowercase()
        if ((a == "создать" || a == "открыть") && b == "окно") return true
    }
    return false
}

fun ideJavaBin(): String {
    return java.io.File(System.getProperty("java.home"), "bin\\java.exe").path
}

fun ideJavawBin(): String {
    return java.io.File(System.getProperty("java.home"), "bin\\javaw.exe").path
}

fun ideOwnJar(): String? {
    return try {
        val loc = IdeKtAnchor::class.java.protectionDomain.codeSource.location.toURI()
        val f = java.io.File(loc)
        if (f.isFile && f.name.lowercase().endsWith(".jar")) f.canonicalPath else null
    } catch (e: Exception) {
        null
    }
}

fun ideBaseDir(): String {
    return try {
        val loc = IdeKtAnchor::class.java.protectionDomain.codeSource.location.toURI()
        val f = java.io.File(loc)
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

fun ideConsoleCmd(javaBin: String, jar: String, script: String, workDir: String): List<String> {
    return listOf("cmd", "/c", "start", "", "/D", workDir, "cmd", "/k", javaBin, "-Dfile.encoding=UTF-8", "-jar", jar, script)
}

fun ideGuiCmd(javawBin: String, jar: String, script: String): List<String> {
    return listOf(javawBin, "-Dfile.encoding=UTF-8", "-jar", jar, script)
}

object IdeTheme {
    val bar = java.awt.Color(35, 35, 44)
    val editor = java.awt.Color(27, 27, 34)
    val gutter = java.awt.Color(32, 32, 42)
    val numbers = java.awt.Color(113, 113, 127)
    val numbersNow = java.awt.Color(185, 185, 255)
    val fg = java.awt.Color(236, 236, 241)
    val muted = java.awt.Color(154, 154, 168)
    val accent = java.awt.Color(124, 108, 255)
    val accentDark = java.awt.Color(88, 74, 220)
    val ink = java.awt.Color(27, 27, 32)
    val line = java.awt.Color(52, 52, 63)
    val caret = java.awt.Color(237, 237, 245)
    val select = java.awt.Color(61, 61, 92)
    val lineHi = java.awt.Color(124, 108, 255, 20)
    val iconLight = java.awt.Color(201, 201, 212)
    val iconMid = java.awt.Color(154, 154, 176)
    val iconDeep = java.awt.Color(42, 42, 53)
}

fun ideIcon(kind: String): javax.swing.ImageIcon {
    val s = 22
    val img = java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE)
        when (kind) {
            "new" -> {
                g.color = IdeTheme.iconLight
                g.stroke = java.awt.BasicStroke(1.6f)
                g.drawRoundRect(6, 3, 10, 16, 2, 2)
                g.color = IdeTheme.accent
                g.stroke = java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.drawLine(11, 7, 11, 15)
                g.drawLine(7, 11, 15, 11)
            }
            "open" -> {
                g.color = java.awt.Color(106, 106, 128)
                g.fillRoundRect(3, 5, 16, 11, 2, 2)
                g.color = IdeTheme.iconMid
                g.fillRoundRect(3, 9, 16, 7, 2, 2)
                g.color = IdeTheme.accent
                g.fillRoundRect(3, 5, 7, 4, 2, 2)
            }
            "save" -> {
                g.color = IdeTheme.accent
                g.fillRoundRect(5, 3, 12, 16, 2, 2)
                g.color = IdeTheme.iconLight
                g.fillRect(8, 5, 6, 5)
                g.color = IdeTheme.iconDeep
                g.fillRect(8, 13, 6, 3)
                g.color = IdeTheme.accentDark
                g.fillRect(9, 6, 4, 3)
            }
            "run" -> {
                g.color = IdeTheme.accent
                g.fillOval(3, 3, 16, 16)
                g.color = IdeTheme.ink
                g.fillPolygon(intArrayOf(9, 9, 16), intArrayOf(7, 15, 11), 3)
            }
            else -> throw IllegalArgumentException(kind)
        }
    } finally {
        g.dispose()
    }
    return javax.swing.ImageIcon(img)
}

class NcodeButton(text: String, icon: javax.swing.ImageIcon, val primary: Boolean, val onClick: () -> Unit) : javax.swing.JButton(text, icon) {
    private var hover = false
    private var glow = 0f
    private var timer: javax.swing.Timer? = null
    init {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        iconTextGap = 8
        margin = java.awt.Insets(7, 16, 7, 16)
        font = font.deriveFont(13f)
        foreground = if (primary) IdeTheme.ink else IdeTheme.fg
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                hover = true
                repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                hover = false
                repaint()
            }
        })
        addActionListener {
            glow = 1f
            if (timer == null) {
                timer = javax.swing.Timer(30) {
                    glow -= 0.18f
                    if (glow <= 0f) {
                        glow = 0f
                        timer?.stop()
                    }
                    repaint()
                }
            }
            timer?.restart()
            onClick()
        }
    }
    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            if (primary) {
                g2.color = if (model.isPressed) IdeTheme.accentDark else IdeTheme.accent
                g2.fillRoundRect(0, 0, w, h, 10, 10)
            } else {
                val base = IdeTheme.bar.brighter()
                if (model.isPressed) {
                    g2.color = java.awt.Color(20, 20, 28)
                    g2.fillRoundRect(0, 0, w, h, 10, 10)
                } else if (hover || glow > 0f) {
                    g2.color = base
                    g2.fillRoundRect(0, 0, w, h, 10, 10)
                }
            }
            if (glow > 0f) {
                g2.color = java.awt.Color(124, 108, 255, (200 * glow).toInt().coerceIn(0, 200))
                g2.stroke = java.awt.BasicStroke(2f)
                g2.drawRoundRect(1, 1, w - 2, h - 2, 10, 10)
            }
            super.paintComponent(g2)
        } finally {
            g2.dispose()
        }
    }
}

private class LineNumbers(private val area: javax.swing.JTextArea) : javax.swing.JPanel() {
    init {
        font = area.font
        background = IdeTheme.gutter
        foreground = IdeTheme.numbers
    }
    fun refresh() {
        val fm = getFontMetrics(font)
        var total = area.lineCount
        if (total < 1) total = 1
        val digits = maxOf(total.toString().length, 2)
        preferredSize = java.awt.Dimension(fm.charWidth('0') * digits + 12, 0)
        revalidate()
        repaint()
    }
    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        val fm = g.getFontMetrics(font)
        val lh = area.getFontMetrics(area.font).height
        val r = g.clipBounds
        var now = -1
        try {
            now = area.getLineOfOffset(area.caretPosition)
        } catch (e: Exception) {
        }
        try {
            val startOff = area.viewToModel(java.awt.Point(0, r.y))
            val startLine = area.getLineOfOffset(startOff)
            var line = startLine
            var y = area.modelToView(startOff).y + fm.ascent
            while (y < r.y + r.height + lh && line < area.lineCount) {
                g.color = if (line == now) IdeTheme.numbersNow else IdeTheme.numbers
                g.drawString((line + 1).toString(), 6, y)
                line++
                y += lh
            }
        } catch (e: Exception) {
        }
    }
    fun watch(scroll: javax.swing.JScrollPane) {
        area.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
        })
        area.addCaretListener { refresh() }
        scroll.viewport.addChangeListener { repaint() }
    }
}

private class IdeState(var file: java.io.File? = null, var lastDir: String = ideBaseDir())

private fun ideTitle(f: java.io.File?): String {
    return if (f == null) "Ncode IDE — новый" else "Ncode IDE — " + f.name
}

private fun ideRead(f: java.io.File): String {
    return f.readLines(Charsets.UTF_8).joinToString("\n")
}

private fun ideWrite(f: java.io.File, text: String): java.io.File {
    var name = f.path
    if (!name.lowercase().endsWith(".ncode")) name += ".ncode"
    val out = java.io.File(name)
    out.parentFile?.mkdirs()
    out.writeText(text, Charsets.UTF_8)
    return out
}

private fun idePickOpen(frame: javax.swing.JFrame, dir: String): java.io.File? {
    val d = java.awt.FileDialog(frame, "Открыть", java.awt.FileDialog.LOAD)
    d.directory = dir
    d.setFilenameFilter { _, name -> name.lowercase().endsWith(".ncode") }
    d.isVisible = true
    val f = d.file ?: return null
    return java.io.File(d.directory, f)
}

private fun idePickSave(frame: javax.swing.JFrame, dir: String, cur: java.io.File?): java.io.File? {
    val d = java.awt.FileDialog(frame, "Сохранить", java.awt.FileDialog.SAVE)
    d.directory = cur?.parent ?: dir
    d.file = cur?.name ?: "игра.ncode"
    d.isVisible = true
    val f = d.file ?: return null
    var out = java.io.File(d.directory, f)
    if (!out.name.lowercase().endsWith(".ncode")) out = java.io.File(out.parent, out.name + ".ncode")
    return out
}

private fun ideError(frame: javax.swing.JFrame, msg: String) {
    javax.swing.JOptionPane.showMessageDialog(frame, msg, "Ncode IDE", javax.swing.JOptionPane.ERROR_MESSAGE)
}

private fun ideLaunch(cmd: List<String>, workDir: String, nul: Boolean, frame: javax.swing.JFrame) {
    try {
        val pb = ProcessBuilder(cmd)
        pb.directory(java.io.File(workDir))
        if (nul) {
            pb.redirectOutput(java.io.File("NUL"))
            pb.redirectError(java.io.File("NUL"))
        }
        pb.start()
    } catch (e: Exception) {
        ideError(frame, "не запустилось: " + (e.message ?: e.toString()))
    }
}

private fun ideRun(frame: javax.swing.JFrame, area: javax.swing.JTextArea, st: IdeState) {
    var f = st.file
    if (f == null) {
        val picked = idePickSave(frame, st.lastDir, null) ?: return
        try {
            f = ideWrite(picked, area.text)
        } catch (e: Exception) {
            ideError(frame, "не сохранилось: " + (e.message ?: e.toString()))
            return
        }
        st.file = f
        st.lastDir = f.parent ?: st.lastDir
        frame.title = ideTitle(f)
    } else {
        try {
            f = ideWrite(f, area.text)
        } catch (e: Exception) {
            ideError(frame, "не сохранилось: " + (e.message ?: e.toString()))
            return
        }
        st.file = f
        frame.title = ideTitle(f)
    }
    val script = f.canonicalPath
    val dir = f.parent ?: "."
    val jar = ideOwnJar()
    if (jar == null) {
        ideError(frame, "запусти IDE из ncode.jar")
        return
    }
    if (!isGame2D(area.text)) {
        ideLaunch(ideConsoleCmd(ideJavaBin(), jar, script, dir), dir, false, frame)
        return
    }
    val choice = javax.swing.JOptionPane.showOptionDialog(
        frame, "Как запустить?", "Запуск",
        javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE,
        null, arrayOf("С консолью", "Без консоли"), "С консолью"
    )
    if (choice == 0) ideLaunch(ideConsoleCmd(ideJavaBin(), jar, script, dir), dir, false, frame)
    else if (choice == 1) ideLaunch(ideGuiCmd(ideJavawBin(), jar, script), dir, true, frame)
}

fun runIde() {
    try {
        javax.swing.UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel")
    } catch (e: Exception) {
    }
    try {
        javax.swing.UIManager.put("OptionPane.background", IdeTheme.bar)
        javax.swing.UIManager.put("OptionPane.messageForeground", IdeTheme.fg)
        javax.swing.UIManager.put("Panel.background", IdeTheme.bar)
        javax.swing.UIManager.put("Button.background", IdeTheme.gutter)
        javax.swing.UIManager.put("Button.foreground", IdeTheme.fg)
    } catch (e: Exception) {
    }
    javax.swing.SwingUtilities.invokeLater {
        val frame = javax.swing.JFrame("Ncode IDE")
        frame.defaultCloseOperation = javax.swing.WindowConstants.EXIT_ON_CLOSE
        frame.background = IdeTheme.bar
        val area = javax.swing.JTextArea()
        area.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 15)
        area.tabSize = 4
        area.background = IdeTheme.editor
        area.foreground = IdeTheme.fg
        area.caretColor = IdeTheme.caret
        area.selectionColor = IdeTheme.select
        val status = javax.swing.JLabel(" ")
        status.background = IdeTheme.bar
        status.foreground = IdeTheme.muted
        status.isOpaque = true
        status.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, IdeTheme.line),
            javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10)
        )
        val scroll = javax.swing.JScrollPane(area)
        scroll.border = javax.swing.BorderFactory.createEmptyBorder()
        scroll.viewport.background = IdeTheme.editor
        val gutter = LineNumbers(area)
        scroll.setRowHeaderView(gutter)
        gutter.watch(scroll)
        gutter.refresh()
        val st = IdeState()
        var lineTag: Any? = null
        fun paintLine() {
            try {
                val h = area.highlighter
                (lineTag as? javax.swing.text.Highlighter.Highlight)?.let { h.removeHighlight(it) }
                lineTag = null
                val pos = area.caretPosition
                val ln = area.getLineOfOffset(pos)
                lineTag = h.addHighlight(
                    area.getLineStartOffset(ln),
                    area.getLineEndOffset(ln),
                    javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(IdeTheme.lineHi)
                )
            } catch (e: Exception) {
            }
        }
        fun syncStatus() {
            var ln = 1
            var col = 1
            try {
                val pos = area.caretPosition
                ln = area.getLineOfOffset(pos) + 1
                col = pos - area.getLineStartOffset(ln - 1) + 1
            } catch (e: Exception) {
            }
            val fn = st.file?.path ?: "новый"
            status.text = fn + "    Строка " + ln + ", столбец " + col
        }
        area.addCaretListener {
            paintLine()
            syncStatus()
        }
        val bar = javax.swing.JToolBar()
        bar.isFloatable = false
        bar.background = IdeTheme.bar
        bar.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, IdeTheme.line),
            javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)
        )
        val bNew = NcodeButton("Новый", ideIcon("new"), false) {
            val picked = idePickSave(frame, st.lastDir, null) ?: return@NcodeButton
            st.file = picked
            st.lastDir = picked.parent ?: st.lastDir
            area.text = ""
            area.caretPosition = 0
            frame.title = ideTitle(picked)
            syncStatus()
        }
        val bOpen = NcodeButton("Открыть", ideIcon("open"), false) {
            val picked = idePickOpen(frame, st.lastDir) ?: return@NcodeButton
            try {
                area.text = ideRead(picked)
                area.caretPosition = 0
                st.file = picked
                st.lastDir = picked.parent ?: st.lastDir
                frame.title = ideTitle(picked)
                syncStatus()
            } catch (e: Exception) {
                ideError(frame, "не открылось: " + (e.message ?: e.toString()))
            }
        }
        val bSave = NcodeButton("Сохранить", ideIcon("save"), false) {
            var f = st.file
            if (f == null) {
                f = idePickSave(frame, st.lastDir, null) ?: return@NcodeButton
            }
            try {
                f = ideWrite(f, area.text)
                st.file = f
                st.lastDir = f.parent ?: st.lastDir
                frame.title = ideTitle(f)
                syncStatus()
            } catch (e: Exception) {
                ideError(frame, "не сохранилось: " + (e.message ?: e.toString()))
            }
        }
        val bRun = NcodeButton("Запустить", ideIcon("run"), true) {
            ideRun(frame, area, st)
        }
        bar.add(bNew)
        bar.add(javax.swing.Box.createHorizontalStrut(6))
        bar.add(bOpen)
        bar.add(javax.swing.Box.createHorizontalStrut(6))
        bar.add(bSave)
        bar.add(javax.swing.Box.createHorizontalStrut(12))
        bar.add(bRun)
        frame.contentPane.add(bar, java.awt.BorderLayout.NORTH)
        frame.contentPane.add(scroll, java.awt.BorderLayout.CENTER)
        frame.contentPane.add(status, java.awt.BorderLayout.SOUTH)
        frame.title = ideTitle(null)
        syncStatus()
        paintLine()
        frame.minimumSize = java.awt.Dimension(700, 500)
        frame.setSize(980, 680)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}
