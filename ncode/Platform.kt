package ncode

interface NcodeClock {
    fun nanoTime(): Long
    fun sleepMs(ms: Long)
}

interface NcodeConsole {
    fun printText(s: String)
    fun printLine(s: String)
    fun flush()
    fun printErr(s: String)
    fun readLine(): String?
}

interface NcodeFiles {
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun appendText(path: String, text: String)
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
    fun isFile(path: String): Boolean
    fun isDir(path: String): Boolean
    fun mkdirs(path: String): Boolean
    fun realPath(path: String): String
}

data class SndPcm(val rate: Int, val channels: Int, val bytes: ByteArray)

interface NcodeSoundLine {
    fun start()
    fun write(data: ByteArray, off: Int, len: Int)
    fun drain()
    fun stop()
    fun close()
    fun setVolume(vol: Int)
}

interface NcodeInputSink {
    fun keyDown(cands: Set<String>)
    fun keyPress(cands: Set<String>)
    fun keyUp(cands: Set<String>)
    fun focusLost()
    fun mouseDown(x: Double, y: Double)
    fun mouseUp(x: Double, y: Double)
    fun mouseMove(x: Double, y: Double)
}

val ruKeys = mapOf(
    "ф" to "a", "и" to "b", "с" to "c", "в" to "d", "у" to "e", "а" to "f",
    "п" to "g", "р" to "h", "ш" to "i", "о" to "j", "л" to "k", "д" to "l",
    "ь" to "m", "т" to "n", "щ" to "o", "з" to "p", "й" to "q", "к" to "r",
    "ы" to "s", "е" to "t", "г" to "u", "м" to "v", "ц" to "w", "ч" to "x",
    "н" to "y", "я" to "z", "х" to "open_bracket", "ъ" to "close_bracket",
    "ж" to "semicolon", "э" to "quote", "б" to "comma", "ю" to "period"
)

interface NcodeSound {
    fun decodeFile(displayPath: String, canonPath: String, ext: String): SndPcm
    fun openLine(rate: Int, channels: Int): NcodeSoundLine
}

interface NcodePlatform {
    val files: NcodeFiles
    val clock: NcodeClock
    val console: NcodeConsole
    val sound: NcodeSound
    val gfx: NcodeGfx
    val assets: NcodeAssets
}

data class GfxImage(val w: Int, val h: Int, val pixels: IntArray)

data class GfxObj(
    val name: String,
    val x: Double,
    val y: Double,
    val size: Double,
    val rot: Double,
    val r: Int,
    val g: Int,
    val b: Int,
    val alpha: Int,
    val images: List<GfxImage>,
    val imageIx: Int,
    val visible: Boolean,
    val circle: Boolean,
    val text: String?
)

data class PenSeg(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val r: Int,
    val g: Int,
    val b: Int,
    val size: Int
)

data class GfxFrame(
    val bgR: Int,
    val bgG: Int,
    val bgB: Int,
    val pens: List<PenSeg>,
    val objs: List<GfxObj>
)

interface NcodeAssets {
    fun imageFor(displayPath: String, baseDir: String?): Pair<String, GfxImage>
}

interface NcodeGfx {
    fun openWindow(w: Int, h: Int, title: String, sink: NcodeInputSink, onClose: () -> Unit)
    fun closeWindow()
    fun isOpen(): Boolean
    fun render(frame: GfxFrame)
}
