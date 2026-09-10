package ncode.app

import ncode.GfxFrame
import ncode.GfxImage
import ncode.NcodeAssets
import ncode.NcodeClock
import ncode.NcodeConsole
import ncode.NcodeError
import ncode.NcodeFiles
import ncode.NcodeGfx
import ncode.NcodeInputSink
import ncode.NcodePlatform
import ncode.NcodeSound
import ncode.NcodeSoundLine
import ncode.SndPcm
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class AndroidClock : NcodeClock {
    override fun nanoTime(): Long = System.nanoTime()
    override fun sleepMs(ms: Long) = Thread.sleep(ms)
}

class AndroidConsole(private val act: Activity, private val show: (String) -> Unit) : NcodeConsole {
    private val buf = StringBuilder()
    override fun printText(s: String) {
        buf.append(s)
        show(s)
    }
    override fun printLine(s: String) {
        buf.append(s).append('\n')
        show(s + "\n")
    }
    override fun flush() {}
    override fun printErr(s: String) {
        buf.append(s).append('\n')
        show(s + "\n")
    }
    override fun readLine(): String? {
        val latch = CountDownLatch(1)
        val res = AtomicReference<String?>(null)
        act.runOnUiThread {
            if (act.isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            val et = android.widget.EditText(act)
            android.app.AlertDialog.Builder(act)
                .setView(et)
                .setPositiveButton("OK") { _, _ ->
                    res.set(et.text.toString())
                    latch.countDown()
                }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return res.get()
    }
}

class AndroidFiles(private val root: File) : NcodeFiles {
    private fun f(p: String) = File(root, p)
    override fun readText(path: String): String {
        val file = f(path)
        if (!file.isFile) throw NcodeError("нет файла `$path`")
        return try {
            file.readLines(Charsets.UTF_8).joinToString("\n")
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$path`")
        }
    }
    override fun writeText(path: String, text: String) {
        try {
            val file = f(path)
            file.parentFile?.mkdirs()
            file.writeText(text + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            throw NcodeError("не могу записать `$path`")
        }
    }
    override fun appendText(path: String, text: String) {
        try {
            val file = f(path)
            file.parentFile?.mkdirs()
            file.appendText(text + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            throw NcodeError("не могу записать `$path`")
        }
    }
    override fun delete(path: String): Boolean {
        return try {
            f(path).delete()
        } catch (e: Exception) {
            false
        }
    }
    override fun exists(path: String): Boolean {
        return try {
            f(path).exists()
        } catch (e: Exception) {
            false
        }
    }
    override fun isFile(path: String): Boolean {
        return try {
            f(path).isFile
        } catch (e: Exception) {
            false
        }
    }
    override fun isDir(path: String): Boolean {
        return try {
            f(path).isDirectory
        } catch (e: Exception) {
            false
        }
    }
    override fun mkdirs(path: String): Boolean {
        return try {
            val dir = f(path)
            if (dir.isDirectory) true else dir.mkdirs()
        } catch (e: Exception) {
            false
        }
    }
    override fun realPath(path: String): String {
        return try {
            f(path).canonicalPath
        } catch (e: Exception) {
            path
        }
    }
}

class AndroidSoundLine(private val track: AudioTrack) : NcodeSoundLine {
    override fun start() = track.play()
    override fun write(data: ByteArray, off: Int, len: Int) {
        track.write(data, off, len)
    }
    override fun drain() {}
    override fun stop() {
        try {
            track.stop()
        } catch (e: Exception) {
        }
    }
    override fun close() {
        try {
            track.release()
        } catch (e: Exception) {
        }
    }
    override fun setVolume(vol: Int) {
        try {
            track.setVolume(if (vol <= 0) 0f else vol / 100f)
        } catch (e: Exception) {
        }
    }
}

class AndroidSound(private val ctx: android.content.Context) : NcodeSound {
    override fun decodeFile(displayPath: String, canonPath: String, ext: String): SndPcm {
        return when (ext) {
            "wav" -> openSrc(displayPath, canonPath).use { decodeWav(it, displayPath) }
            "mp3" -> openSrc(displayPath, canonPath).use { decodeMp3(it, displayPath) }
            "ogg" -> openSrc(displayPath, canonPath).use { decodeOgg(it, displayPath) }
            else -> decodeM4a(displayPath, canonPath)
        }
    }
    private fun openSrc(displayPath: String, canonPath: String): InputStream {
        val f = File(canonPath)
        if (f.isFile) return f.inputStream()
        try {
            return ctx.assets.open(displayPath)
        } catch (e: Exception) {
            throw NcodeError("нет файла `$displayPath`")
        }
    }
    private fun floatsToBytes(ch: List<FloatArray>, out: ByteArrayOutputStream) {
        if (ch.isEmpty()) return
        val n = ch[0].size
        var i = 0
        while (i < n) {
            for (c in ch) {
                val v = (c[i] * 32767.0f).toInt().coerceIn(-32768, 32767)
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            i++
        }
    }
    private fun readLE(b: ByteArray, o: Int, n: Int): Long {
        var r = 0L
        var i = 0
        while (i < n) {
            r = r or ((b[o + i].toLong() and 0xFF) shl (8 * i))
            i++
        }
        return r
    }
    private fun readFully(s: InputStream, b: ByteArray, o: Int, n: Int) {
        var p = o
        val end = o + n
        while (p < end) {
            val r = s.read(b, p, end - p)
            if (r < 0) throw NcodeError("не звук")
            p += r
        }
    }
    private fun decodeWav(s: InputStream, path: String): SndPcm {
        try {
            val head = ByteArray(12)
            readFully(s, head, 0, 12)
            if (head[0] != 'R'.code.toByte() || head[1] != 'I'.code.toByte() || head[2] != 'F'.code.toByte() || head[3] != 'F'.code.toByte()) throw NcodeError("не звук `$path`")
            var fmt = 0
            var ch = 0
            var rate = 0
            var bits = 0
            var data: ByteArray? = null
            while (true) {
                val chid = ByteArray(8)
                try {
                    readFully(s, chid, 0, 8)
                } catch (e: NcodeError) {
                    break
                }
                val sz = readLE(chid, 4, 4).toInt()
                val id = String(byteArrayOf(chid[0], chid[1], chid[2], chid[3]), Charsets.US_ASCII)
                if (id == "fmt ") {
                    val fb = ByteArray(sz)
                    readFully(s, fb, 0, sz)
                    fmt = readLE(fb, 0, 2).toInt()
                    ch = readLE(fb, 2, 2).toInt()
                    rate = readLE(fb, 4, 4).toInt()
                    bits = readLE(fb, 14, 2).toInt()
                } else if (id == "data") {
                    val db = ByteArray(sz)
                    readFully(s, db, 0, sz)
                    data = db
                    break
                } else {
                    var left = sz.toLong()
                    while (left > 0) {
                        val sk = s.skip(left)
                        if (sk <= 0) break
                        left -= sk
                    }
                    if (sz % 2 == 1) s.read()
                }
            }
            val raw = data ?: throw NcodeError("не звук `$path`")
            if (ch < 1 || rate <= 0) throw NcodeError("не звук `$path`")
            val out = ByteArrayOutputStream(raw.size + 2)
            when (fmt) {
                1 -> when (bits) {
                    8 -> {
                        var i = 0
                        while (i < raw.size) {
                            val v = ((raw[i].toInt() and 0xFF) - 128) * 256
                            out.write(v and 0xFF)
                            out.write((v shr 8) and 0xFF)
                            i++
                        }
                    }
                    16 -> out.write(raw, 0, raw.size)
                    24 -> {
                        var i = 0
                        while (i + 2 < raw.size) {
                            out.write(raw[i + 1].toInt() and 0xFF)
                            out.write(raw[i + 2].toInt() and 0xFF)
                            i += 3
                        }
                    }
                    else -> throw NcodeError("не звук `$path`")
                }
                3 -> {
                    if (bits != 32) throw NcodeError("не звук `$path`")
                    var i = 0
                    while (i + 3 < raw.size) {
                        val f = java.lang.Float.intBitsToFloat(readLE(raw, i, 4).toInt())
                        val v = (f.coerceIn(-1f, 1f) * 32767.0f).toInt()
                        out.write(v and 0xFF)
                        out.write((v shr 8) and 0xFF)
                        i += 4
                    }
                }
                else -> throw NcodeError("не звук `$path`")
            }
            return SndPcm(rate, ch, out.toByteArray())
        } catch (e: NcodeError) {
            throw e
        } catch (e: Exception) {
            throw NcodeError("не звук `$path`")
        }
    }
    private fun decodeMp3(s: InputStream, path: String): SndPcm {
        try {
            val bs = javazoom.jl.decoder.Bitstream(s)
            val dec = javazoom.jl.decoder.Decoder()
            val out = ByteArrayOutputStream()
            var rate = 0
            var ch = 0
            var got = false
            while (true) {
                val h = try {
                    bs.readFrame()
                } catch (e: Exception) {
                    throw NcodeError("не звук `$path`")
                } ?: break
                try {
                    val b = dec.decodeFrame(h, bs) as javazoom.jl.decoder.SampleBuffer
                    if (!got) {
                        rate = b.sampleFrequency
                        ch = b.channelCount
                        got = true
                    }
                    val sb = b.buffer
                    var i = 0
                    while (i < b.bufferLength) {
                        val v = sb[i].toInt()
                        out.write(v and 0xFF)
                        out.write((v shr 8) and 0xFF)
                        i++
                    }
                } catch (e: NcodeError) {
                    throw e
                } catch (e: Exception) {
                    throw NcodeError("не звук `$path`")
                } finally {
                    bs.closeFrame()
                }
            }
            if (!got || out.size() == 0) throw NcodeError("не звук `$path`")
            return SndPcm(rate, ch, out.toByteArray())
        } catch (e: NcodeError) {
            throw e
        } catch (e: Exception) {
            throw NcodeError("не звук `$path`")
        }
    }
    private fun decodeOgg(s: InputStream, path: String): SndPcm {
        try {
            val oy = com.jcraft.jogg.SyncState()
            val os = com.jcraft.jogg.StreamState()
            val og = com.jcraft.jogg.Page()
            val op = com.jcraft.jogg.Packet()
            val vi = com.jcraft.jorbis.Info()
            val vc = com.jcraft.jorbis.Comment()
            val vd = com.jcraft.jorbis.DspState()
            val vb = com.jcraft.jorbis.Block(vd)
            oy.init()
            var idx = oy.buffer(8192)
            var n = s.read(oy.data, idx, 8192)
            if (n <= 0) throw NcodeError("не звук `$path`")
            oy.wrote(n)
            if (oy.pageout(og) != 1) throw NcodeError("не звук `$path`")
            os.init(og.serialno())
            vi.init()
            vc.init()
            os.pagein(og)
            if (os.packetout(op) != 1) throw NcodeError("не звук `$path`")
            vi.synthesis_headerin(vc, op)
            var i = 0
            while (i < 2) {
                while (i < 2) {
                    val r = oy.pageout(og)
                    if (r == 0) break
                    if (r == 1) {
                        os.pagein(og)
                        while (i < 2) {
                            val q = os.packetout(op)
                            if (q == 0) break
                            if (q < 0) continue
                            vi.synthesis_headerin(vc, op)
                            i++
                        }
                    }
                }
                idx = oy.buffer(8192)
                n = s.read(oy.data, idx, 8192)
                if (n <= 0) break
                oy.wrote(n)
            }
            vd.synthesis_init(vi)
            vb.init(vd)
            val out = ByteArrayOutputStream()
            val pcm = arrayOf(arrayOf<FloatArray>())
            var eos = false
            val index = IntArray(vi.channels)
            while (!eos) {
                while (!eos) {
                    val r = oy.pageout(og)
                    if (r == 0) break
                    if (r < 0) continue
                    os.pagein(og)
                    while (true) {
                        val q = os.packetout(op)
                        if (q == 0) break
                        if (q < 0) continue
                        if (vb.synthesis(op) == 0) vd.synthesis_blockin(vb)
                        var samples = vd.synthesis_pcmout(pcm, index)
                        while (samples > 0) {
                            val planes = pcm[0]
                            val ch = mutableListOf<FloatArray>()
                            for (c in 0 until vi.channels) ch.add(planes[c].copyOfRange(index[c], index[c] + samples))
                            floatsToBytes(ch, out)
                            vd.synthesis_read(samples)
                            samples = vd.synthesis_pcmout(pcm, index)
                        }
                    }
                    if (og.eos() != 0) eos = true
                }
                if (!eos) {
                    idx = oy.buffer(8192)
                    n = s.read(oy.data, idx, 8192)
                    if (n <= 0) break
                    oy.wrote(n)
                }
            }
            if (out.size() == 0) throw NcodeError("не звук `$path`")
            return SndPcm(vi.rate, vi.channels, out.toByteArray())
        } catch (e: NcodeError) {
            throw e
        } catch (e: Exception) {
            throw NcodeError("не звук `$path`")
        }
    }
    private fun decodeM4a(displayPath: String, canonPath: String): SndPcm {
        val tmp = File(ctx.cacheDir, "ncode_tmp.m4a")
        try {
            openSrc(displayPath, canonPath).use { src ->
                tmp.outputStream().use { dst -> src.copyTo(dst) }
            }
        } catch (e: NcodeError) {
            throw e
        } catch (e: Exception) {
            throw NcodeError("не звук `$displayPath`")
        }
        val raf = try {
            RandomAccessFile(tmp, "r")
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$displayPath`")
        }
        try {
            val inp = net.sourceforge.jaad.mp4.MP4InputStream.open(raf)
            val cont = try {
                net.sourceforge.jaad.mp4.MP4Container(inp)
            } catch (e: Exception) {
                throw NcodeError("не звук `$displayPath`")
            }
            var at: net.sourceforge.jaad.mp4.api.AudioTrack? = null
            for (t in cont.movie.tracks) {
                if (t is net.sourceforge.jaad.mp4.api.AudioTrack && t.codec == net.sourceforge.jaad.mp4.api.AudioTrack.AudioCodec.AAC) at = t
            }
            val track = at ?: throw NcodeError("не звук `$displayPath`")
            val dec = try {
                net.sourceforge.jaad.aac.Decoder.create(track.decoderSpecificInfo.data)
            } catch (e: Exception) {
                throw NcodeError("не звук `$displayPath`")
            }
            val out = ByteArrayOutputStream()
            val recv = object : net.sourceforge.jaad.aac.Receiver {
                override fun accept(data: List<FloatArray>, x: Int, y: Int) {
                    floatsToBytes(data, out)
                }
            }
            try {
                while (track.hasMoreFrames()) {
                    val fr = track.readNextFrame()
                    dec.decodeFrame(fr.data, recv)
                }
            } catch (e: Exception) {
                throw NcodeError("не звук `$displayPath`")
            }
            if (out.size() == 0) throw NcodeError("не звук `$displayPath`")
            return SndPcm(track.sampleRate, track.channelCount, out.toByteArray())
        } finally {
            try {
                raf.close()
            } catch (e: Exception) {
            }
            try {
                tmp.delete()
            } catch (e: Exception) {
            }
        }
    }
    override fun openLine(rate: Int, channels: Int): NcodeSoundLine {
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(if (minBuf > 0) maxOf(minBuf, 8192) else 32768)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            throw NcodeError("нет звука — нет аудиоустройства")
        }
        return AndroidSoundLine(track)
    }
}

class AndroidGfx(
    private val act: Activity,
    private val showGame: (android.view.View) -> Unit,
    private val showConsole: () -> Unit
) : NcodeGfx {
    @Volatile private var open = false
    @Volatile private var view: GameView? = null
    private var onCloseCb: (() -> Unit)? = null
    private var lastSink: NcodeInputSink? = null

    override fun isOpen(): Boolean = open

    override fun openWindow(w: Int, h: Int, title: String, sink: NcodeInputSink, onClose: () -> Unit) {
        if (open) throw NcodeError("окно уже есть — сначала закрыть окно")
        onCloseCb = onClose
        lastSink = sink
        val latch = CountDownLatch(1)
        val ok = AtomicReference(false)
        act.runOnUiThread {
            try {
                if (act.isFinishing) {
                    latch.countDown()
                    return@runOnUiThread
                }
                val v = GameView(act, w, h, sink)
                view = v
                showGame(v)
                open = true
                ok.set(true)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        if (!ok.get()) throw NcodeError("окно не открылось")
    }

    override fun closeWindow() {
        open = false
        view = null
        act.runOnUiThread { showConsole() }
    }

    fun userBack(): Boolean {
        if (!open) return false
        open = false
        view = null
        act.runOnUiThread { showConsole() }
        try {
            onCloseCb?.invoke()
        } catch (e: Exception) {
        }
        return true
    }

    fun onActivityPaused() {
        try {
            lastSink?.focusLost()
        } catch (e: Exception) {
        }
    }

    override fun render(frame: GfxFrame) {
        view?.postFrame(frame)
    }

    override fun setResizable(resizable: Boolean) {
    }

    inner class GameView(ctx: android.content.Context, val gw: Int, val gh: Int, val sink: NcodeInputSink) : android.view.View(ctx) {
        @Volatile private var frame: GfxFrame? = null
        private val imgCache = mutableMapOf<GfxImage, android.graphics.Bitmap>()
        private var scale = 1f
        private var offX = 0f
        private var offY = 0f
        private var activePtr = -1

        fun postFrame(f: GfxFrame) {
            frame = f
            postInvalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            scale = minOf(w / gw.toFloat(), h / gh.toFloat())
            offX = (w - gw * scale) / 2f
            offY = (h - gh * scale) / 2f
        }

        private fun toWorld(sx: Float, sy: Float): Pair<Double, Double> {
            val wx = (sx - offX) / scale - gw / 2.0
            val wy = gh / 2.0 - (sy - offY) / scale
            return wx to wy
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (activePtr == -1) {
                        val ix = e.actionIndex
                        activePtr = e.getPointerId(ix)
                        val (wx, wy) = toWorld(e.getX(ix), e.getY(ix))
                        sink.mouseDown(wx, wy)
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    var i = 0
                    while (i < e.pointerCount) {
                        if (e.getPointerId(i) == activePtr) {
                            val (wx, wy) = toWorld(e.getX(i), e.getY(i))
                            sink.mouseMove(wx, wy)
                            break
                        }
                        i++
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_POINTER_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val ix = e.actionIndex
                    if (e.getPointerId(ix) == activePtr) {
                        activePtr = -1
                        val (wx, wy) = toWorld(e.getX(ix), e.getY(ix))
                        sink.mouseUp(wx, wy)
                    }
                }
            }
            return true
        }

        private fun toBitmap(img: GfxImage): android.graphics.Bitmap {
            imgCache[img]?.let { return it }
            val b = android.graphics.Bitmap.createBitmap(img.pixels, img.w, img.h, android.graphics.Bitmap.Config.ARGB_8888)
            imgCache[img] = b
            return b
        }

        override fun onDraw(c: android.graphics.Canvas) {
            super.onDraw(c)
            val f = frame
            if (f == null) {
                c.drawColor(android.graphics.Color.BLACK)
                return
            }
            c.drawColor(android.graphics.Color.rgb(f.bgR, f.bgG, f.bgB))
            val linePaint = android.graphics.Paint()
            linePaint.style = android.graphics.Paint.Style.STROKE
            for (t in f.pens) {
                linePaint.color = android.graphics.Color.rgb(t.r, t.g, t.b)
                linePaint.strokeWidth = maxOf(1f, t.size * scale)
                c.drawLine(
                    offX + (gw / 2f + t.x1.toFloat()) * scale,
                    offY + (gh / 2f - t.y1.toFloat()) * scale,
                    offX + (gw / 2f + t.x2.toFloat()) * scale,
                    offY + (gh / 2f - t.y2.toFloat()) * scale,
                    linePaint
                )
            }
            val paint = android.graphics.Paint()
            paint.isAntiAlias = true
            for (o in f.objs) {
                if (!o.visible) continue
                val cx = offX + (gw / 2f + o.x.toFloat()) * scale
                val cy = offY + (gh / 2f - o.y.toFloat()) * scale
                c.save()
                c.translate(cx, cy)
                c.rotate(-o.rot.toFloat())
                val txt = o.text
                val img = o.images.getOrNull(o.imageIx)
                if (txt != null) {
                    paint.color = android.graphics.Color.rgb(o.r, o.g, o.b)
                    paint.alpha = ((o.alpha.coerceIn(0, 100)) * 255 / 100)
                    paint.textSize = maxOf(1f, o.size.toFloat() * scale)
                    paint.textAlign = android.graphics.Paint.Align.CENTER
                    val base = -(paint.descent() + paint.ascent()) / 2f
                    c.drawText(txt, 0f, base, paint)
                } else if (img != null) {
                    paint.alpha = ((o.alpha.coerceIn(0, 100)) * 255 / 100)
                    val b = toBitmap(img)
                    val s = o.size / b.width.toDouble() * scale
                    val w = (b.width * s).toFloat()
                    val h = (b.height * s).toFloat()
                    val dst = android.graphics.RectF(-w / 2, -h / 2, w / 2, h / 2)
                    c.drawBitmap(b, null, dst, paint)
                } else {
                    paint.color = android.graphics.Color.rgb(o.r, o.g, o.b)
                    paint.alpha = ((o.alpha.coerceIn(0, 100)) * 255 / 100)
                    paint.style = android.graphics.Paint.Style.FILL
                    val s = o.size.toFloat() * scale
                    if (o.circle) c.drawCircle(0f, 0f, s / 2f, paint)
                    else c.drawRect(-s / 2f, -s / 2f, s / 2f, s / 2f, paint)
                }
                c.restore()
            }
            for (l in f.labels) {
                paint.color = android.graphics.Color.rgb(l.r, l.g, l.b)
                paint.alpha = ((l.alpha.coerceIn(0, 100)) * 255 / 100)
                paint.textSize = maxOf(1f, l.size.toFloat() * scale)
                paint.textAlign = android.graphics.Paint.Align.CENTER
                val base = -(paint.descent() + paint.ascent()) / 2f
                c.drawText(
                    l.text,
                    offX + (gw / 2f + l.x.toFloat()) * scale,
                    offY + (gh / 2f - l.y.toFloat()) * scale + base,
                    paint
                )
            }
        }
    }
}

class AndroidAssets(private val ctx: android.content.Context) : NcodeAssets {
    private val cache = mutableMapOf<String, GfxImage>()
    override fun imageFor(displayPath: String, baseDir: String?): Pair<String, GfxImage> {
        val clean = displayPath.replace('\\', '/').trimStart('/')
        cache["file:$clean"]?.let { return "file:$clean" to it }
        cache["asset:$clean"]?.let { return "asset:$clean" to it }
        val fromFile = File(ctx.filesDir, clean)
        if (fromFile.isFile) {
            try {
                fromFile.inputStream().use {
                    val img = decode(it, displayPath)
                    cache["file:$clean"] = img
                    return "file:$clean" to img
                }
            } catch (e: NcodeError) {
                throw e
            } catch (e: Exception) {
                throw NcodeError("не картинка `$displayPath`")
            }
        }
        try {
            ctx.assets.open(clean).use {
                val img = decode(it, displayPath)
                cache["asset:$clean"] = img
                return "asset:$clean" to img
            }
        } catch (e: NcodeError) {
            throw e
        } catch (e: Exception) {
            throw NcodeError("нет файла `$displayPath`")
        }
    }
    private fun decode(s: InputStream, displayPath: String): GfxImage {
        val opts = android.graphics.BitmapFactory.Options()
        opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        val bmp = try {
            android.graphics.BitmapFactory.decodeStream(s, null, opts)
        } catch (e: Exception) {
            null
        } ?: throw NcodeError("не картинка `$displayPath`")
        try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) throw NcodeError("не картинка `$displayPath`")
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            return GfxImage(w, h, px)
        } finally {
            try {
                bmp.recycle()
            } catch (e: Exception) {
            }
        }
    }
}

class AndroidPlatform(act: Activity, show: (String) -> Unit, showGame: (android.view.View) -> Unit, showConsole: () -> Unit) : NcodePlatform {
    private val appCtx = act.applicationContext
    override val files: NcodeFiles = AndroidFiles(File(appCtx.filesDir, "ncode"))
    override val clock: NcodeClock = AndroidClock()
    override val console: NcodeConsole = AndroidConsole(act, show)
    override val sound: NcodeSound = AndroidSound(appCtx)
    override val gfx: NcodeGfx = AndroidGfx(act, showGame, showConsole)
    override val assets: NcodeAssets = AndroidAssets(appCtx)
}
