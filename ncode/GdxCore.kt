package ncode

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.audio.AudioDevice
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.FitViewport

private val gdxEnToRu: Map<String, String> = ruKeys.entries.associate { (k, v) -> v to k }

fun gdxKeyName(code: Int): String {
    if (code in Input.Keys.A..Input.Keys.Z) return ('a' + (code - Input.Keys.A)).toString()
    if (code in Input.Keys.NUM_0..Input.Keys.NUM_9) return ('0' + (code - Input.Keys.NUM_0)).toString()
    if (code in Input.Keys.NUMPAD_0..Input.Keys.NUMPAD_9) return ('0' + (code - Input.Keys.NUMPAD_0)).toString()
    if (code in Input.Keys.F1..Input.Keys.F12) return "f" + (code - Input.Keys.F1 + 1)
    return when (code) {
        Input.Keys.SPACE -> "space"
        Input.Keys.ENTER -> "enter"
        Input.Keys.ESCAPE -> "escape"
        Input.Keys.TAB -> "tab"
        Input.Keys.LEFT -> "left"
        Input.Keys.RIGHT -> "right"
        Input.Keys.UP -> "up"
        Input.Keys.DOWN -> "down"
        Input.Keys.BACKSPACE -> "back_space"
        Input.Keys.FORWARD_DEL -> "delete"
        Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT -> "shift"
        Input.Keys.CONTROL_LEFT, Input.Keys.CONTROL_RIGHT -> "control"
        Input.Keys.ALT_LEFT, Input.Keys.ALT_RIGHT -> "alt"
        Input.Keys.COMMA -> "comma"
        Input.Keys.PERIOD -> "period"
        Input.Keys.SEMICOLON -> "semicolon"
        Input.Keys.SLASH -> "slash"
        Input.Keys.BACKSLASH -> "backslash"
        Input.Keys.APOSTROPHE -> "quote"
        Input.Keys.LEFT_BRACKET -> "open_bracket"
        Input.Keys.RIGHT_BRACKET -> "close_bracket"
        Input.Keys.MINUS -> "minus"
        Input.Keys.EQUALS -> "equals"
        Input.Keys.INSERT -> "insert"
        Input.Keys.HOME -> "home"
        Input.Keys.END -> "end"
        Input.Keys.PAGE_UP -> "page_up"
        Input.Keys.PAGE_DOWN -> "page_down"
        else -> Input.Keys.toString(code).lowercase(java.util.Locale.ROOT).replace(' ', '_')
    }
}

fun gdxKeyCands(code: Int): MutableSet<String> {
    val cands = mutableSetOf(gdxKeyName(code))
    gdxEnToRu[gdxKeyName(code)]?.let { cands.add(it) }
    return cands
}

class GdxSoundLine(rate: Int, stereo: Boolean) : NcodeSoundLine {
    private val dev: AudioDevice = Gdx.audio.newAudioDevice(rate, stereo)
    @Volatile private var dead = false
    override fun start() {}
    override fun write(data: ByteArray, off: Int, len: Int) {
        if (dead) return
        val n = len / 2
        if (n <= 0) return
        val s = ShortArray(n)
        var i = 0
        while (i < n) {
            s[i] = ((data[off + i * 2 + 1].toInt() shl 8) or (data[off + i * 2].toInt() and 0xFF)).toShort()
            i++
        }
        try {
            dev.writeSamples(s, 0, n)
        } catch (e: Exception) {
        }
    }
    override fun drain() {}
    override fun stop() = dispose()
    override fun close() = dispose()
    override fun setVolume(vol: Int) {
        try {
            dev.setVolume(if (vol <= 0) 0f else vol / 100f)
        } catch (e: Exception) {
        }
    }
    private fun dispose() {
        if (!dead) {
            dead = true
            try {
                dev.dispose()
            } catch (e: Exception) {
            }
        }
    }
}

private const val FONT_CHARS =
    " !\"#\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~" +
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
    "«»–—…№"

class NcodeGdxGame : ApplicationAdapter(), InputProcessor {
    @Volatile var baseW = 800
    @Volatile var baseH = 600
    @Volatile var frame: GfxFrame? = null
    @Volatile var camX = 0.0
    @Volatile var camY = 0.0
    @Volatile var sink: NcodeInputSink? = null
    @Volatile var onCloseCb: (() -> Unit)? = null
    @Volatile var created = false
    @Volatile var createLatch: java.util.concurrent.CountDownLatch? = null

    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private lateinit var viewport: FitViewport
    private lateinit var fontGen: FreeTypeFontGenerator
    private val fonts = object : LinkedHashMap<Int, BitmapFont>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BitmapFont>?): Boolean {
            if (size > 48 && eldest != null) {
                try {
                    eldest.value.dispose()
                } catch (e: Exception) {
                }
                return true
            }
            return false
        }
    }
    private val texCache = mutableMapOf<GfxImage, Texture>()
    private val layout = GlyphLayout()
    private val projV = Vector2()
    private var glowTex: Texture? = null
    private var flatTex: Texture? = null

    private fun glowTexture(): Texture {
        glowTex?.let { return it }
        val n = 256
        val pm = Pixmap(n, n, Pixmap.Format.RGBA8888)
        var y = 0
        while (y < n) {
            var x = 0
            while (x < n) {
                val dx = (x - n / 2f) / (n / 2f)
                val dy = (y - n / 2f) / (n / 2f)
                var d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (d > 1f) d = 1f
                val a = ((1f - d) * (1f - d) * 255f).toInt()
                pm.drawPixel(x, y, (255 shl 24) or (255 shl 16) or (255 shl 8) or a)
                x++
            }
            y++
        }
        val t = Texture(pm)
        pm.dispose()
        glowTex = t
        return t
    }

    private fun flatTexture(): Texture {
        flatTex?.let { return it }
        val pm = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pm.drawPixel(0, 0, -1)
        val t = Texture(pm)
        pm.dispose()
        flatTex = t
        return t
    }

    fun setCam(x: Double, y: Double) {
        camX = x
        camY = y
    }

    fun postFrame(f: GfxFrame) {
        frame = f
    }

    fun rebuildViewport(w: Int, h: Int) {
        baseW = w
        baseH = h
        if (created) {
            try {
                viewport.setWorldSize(w.toFloat(), h.toFloat())
                viewport.update(Gdx.graphics.width, Gdx.graphics.height, false)
            } catch (e: Exception) {
            }
        }
    }

    private fun fontFor(px: Int): BitmapFont {
        val s = px.coerceAtLeast(1)
        fonts[s]?.let { return it }
        val p = FreeTypeFontGenerator.FreeTypeFontParameter()
        p.size = s
        p.characters = FONT_CHARS
        p.minFilter = Texture.TextureFilter.Linear
        p.magFilter = Texture.TextureFilter.Linear
        val f = fontGen.generateFont(p)
        fonts[s] = f
        return f
    }

    private fun toTexture(img: GfxImage): Texture {
        texCache[img]?.let { return it }
        val pm = Pixmap(img.w, img.h, Pixmap.Format.RGBA8888)
        var y = 0
        while (y < img.h) {
            var x = 0
            while (x < img.w) {
                val p = img.pixels[y * img.w + x]
                val a = (p ushr 24) and 0xFF
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                pm.drawPixel(x, y, (r shl 24) or (g shl 16) or (b shl 8) or a)
                x++
            }
            y++
        }
        val t = Texture(pm)
        pm.dispose()
        texCache[img] = t
        return t
    }

    override fun create() {
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        camera = OrthographicCamera()
        viewport = FitViewport(baseW.toFloat(), baseH.toFloat(), camera)
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, false)
        fontGen = FreeTypeFontGenerator(Gdx.files.internal("font.ttf"))
        Gdx.input.inputProcessor = this
        created = true
        try {
            createLatch?.countDown()
        } catch (e: Exception) {
        }
    }

    override fun resize(w: Int, h: Int) {
        try {
            viewport.update(w, h, false)
        } catch (e: Exception) {
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        camera.position.set(camX.toFloat(), camY.toFloat(), 0f)
        camera.update()
        viewport.apply()
        val f = frame ?: return
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.projectionMatrix = camera.combined
        shapes.projectionMatrix = camera.combined
        val hw = baseW / 2f
        val hh = baseH / 2f
        var inShapes = false
        var inBatch = false
        fun useShapes() {
            if (inBatch) {
                try {
                    batch.end()
                } catch (e: Exception) {
                }
                inBatch = false
            }
            if (!inShapes) {
                shapes.begin(ShapeRenderer.ShapeType.Filled)
                inShapes = true
            }
        }
        fun useBatch() {
            if (inShapes) {
                try {
                    shapes.end()
                } catch (e: Exception) {
                }
                inShapes = false
            }
            if (!inBatch) {
                batch.begin()
                inBatch = true
            }
        }
        try {
            useShapes()
            shapes.color = Color(f.bgR / 255f, f.bgG / 255f, f.bgB / 255f, 1f)
            shapes.rect(-hw, -hh, baseW.toFloat(), baseH.toFloat())
            for (t in f.pens) {
                shapes.color = Color(t.r / 255f, t.g / 255f, t.b / 255f, 1f)
                shapes.rectLine(t.x1.toFloat(), t.y1.toFloat(), t.x2.toFloat(), t.y2.toFloat(), t.size.toFloat())
            }
            for (o in f.objs) {
                if (!o.visible) continue
                val img = o.images.getOrNull(o.imageIx)
                val txt = o.text
                val alpha = (o.alpha.coerceIn(0, 100)) / 100f
                if (txt != null) {
                    useBatch()
                    val font = fontFor(o.size.toInt())
                    batch.color = Color(o.r / 255f, o.g / 255f, o.b / 255f, alpha)
                    layout.setText(font, txt)
                    font.draw(batch, layout, o.x.toFloat() - layout.width / 2f, o.y.toFloat() + layout.height / 2f)
                } else if (img != null) {
                    useBatch()
                    val t = toTexture(img)
                    val s = o.size / img.w.toDouble()
                    val w = (img.w * s).toFloat()
                    val h = (img.h * s).toFloat()
                    batch.color = Color(1f, 1f, 1f, alpha)
                    batch.draw(t, o.x.toFloat() - w / 2f, o.y.toFloat() - h / 2f, w / 2f, h / 2f, w, h, 1f, 1f, o.rot.toFloat(), 0, 0, img.w, img.h, false, false)
                } else {
                    useShapes()
                    shapes.color = Color(o.r / 255f, o.g / 255f, o.b / 255f, alpha)
                    val s = o.size.toFloat()
                    if (o.circle) {
                        shapes.circle(o.x.toFloat(), o.y.toFloat(), s / 2f, 48)
                    } else {
                        shapes.rect(o.x.toFloat() - s / 2f, o.y.toFloat() - s / 2f, s / 2f, s / 2f, s, s, 1f, 1f, o.rot.toFloat())
                    }
                }
            }
            if (f.darkness > 0) {
                useBatch()
                batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
                batch.color = Color(0f, 0f, 0f, (f.darkness.coerceIn(0, 100)) / 100f)
                batch.draw(flatTexture(), (camX - baseW / 2).toFloat(), (camY - baseH / 2).toFloat(), baseW.toFloat(), baseH.toFloat())
                batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
                val glow = glowTexture()
                for (li in f.lights) {
                    if (!li.visible || li.power <= 0 || li.size <= 0.0) continue
                    var a = (li.power.coerceIn(0, 100)) / 100f
                    if (li.flicker > 0) a *= 1f - (li.flicker.coerceIn(0, 100)) / 100f * Math.random().toFloat()
                    if (a <= 0.01f) continue
                    batch.color = Color(li.r / 255f, li.g / 255f, li.b / 255f, a)
                    val s = li.size.toFloat()
                    batch.draw(glow, li.x.toFloat() - s / 2f, li.y.toFloat() - s / 2f, s, s)
                }
                batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
                batch.color = Color.WHITE
            }
            useBatch()
            for (l in f.labels) {
                val font = fontFor(l.size.toInt())
                batch.color = Color(l.r / 255f, l.g / 255f, l.b / 255f, (l.alpha.coerceIn(0, 100)) / 100f)
                layout.setText(font, l.text)
                font.draw(batch, layout, l.x.toFloat() - layout.width / 2f, l.y.toFloat() + layout.height / 2f)
            }
            batch.color = Color.WHITE
        } finally {
            if (inBatch) {
                try {
                    batch.end()
                } catch (e: Exception) {
                }
            }
            if (inShapes) {
                try {
                    shapes.end()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun toWorld(sx: Float, sy: Float): Pair<Double, Double> {
        projV.set(sx, sy)
        viewport.unproject(projV)
        return projV.x.toDouble() to projV.y.toDouble()
    }

    override fun keyDown(keycode: Int): Boolean {
        sink?.keyDown(gdxKeyCands(keycode))
        return false
    }

    override fun keyUp(keycode: Int): Boolean {
        sink?.keyUp(gdxKeyCands(keycode))
        return false
    }

    override fun keyTyped(ch: Char): Boolean {
        if (ch.code == 0 || ch.isLetterOrDigit() || ch == ' ') return false
        sink?.keyPress(setOf(ch.toString().lowercase(java.util.Locale.ROOT)))
        return false
    }

    override fun touchDown(sx: Int, sy: Int, pointer: Int, button: Int): Boolean {
        if (pointer != 0) return false
        val (wx, wy) = toWorld(sx.toFloat(), sy.toFloat())
        sink?.mouseDown(wx, wy)
        return false
    }

    override fun touchUp(sx: Int, sy: Int, pointer: Int, button: Int): Boolean {
        if (pointer != 0) return false
        val (wx, wy) = toWorld(sx.toFloat(), sy.toFloat())
        sink?.mouseUp(wx, wy)
        return false
    }

    override fun touchDragged(sx: Int, sy: Int, pointer: Int): Boolean {
        if (pointer != 0) return false
        val (wx, wy) = toWorld(sx.toFloat(), sy.toFloat())
        sink?.mouseMove(wx, wy)
        return false
    }

    override fun mouseMoved(sx: Int, sy: Int): Boolean {
        val (wx, wy) = toWorld(sx.toFloat(), sy.toFloat())
        sink?.mouseMove(wx, wy)
        return false
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean = false

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = false

    override fun pause() {
        try {
            sink?.focusLost()
        } catch (e: Exception) {
        }
    }

    override fun dispose() {
        try {
            glowTex?.dispose()
        } catch (e: Exception) {
        }
        glowTex = null
        try {
            flatTex?.dispose()
        } catch (e: Exception) {
        }
        flatTex = null
        try {
            batch.dispose()
        } catch (e: Exception) {
        }
        try {
            shapes.dispose()
        } catch (e: Exception) {
        }
        try {
            fontGen.dispose()
        } catch (e: Exception) {
        }
        for ((_, f) in fonts) {
            try {
                f.dispose()
            } catch (e: Exception) {
            }
        }
        fonts.clear()
        for ((_, t) in texCache) {
            try {
                t.dispose()
            } catch (e: Exception) {
            }
        }
        texCache.clear()
    }
}
