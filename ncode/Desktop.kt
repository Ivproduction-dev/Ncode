package ncode

class DesktopClock : NcodeClock {
    override fun nanoTime(): Long = System.nanoTime()
    override fun sleepMs(ms: Long) = Thread.sleep(ms)
}

class DesktopConsole : NcodeConsole {
    private var stdin: java.io.BufferedReader? = null
    override fun printText(s: String) = print(s)
    override fun printLine(s: String) = println(s)
    override fun flush() = System.out.flush()
    override fun printErr(s: String) = System.err.println(s)
    override fun readLine(): String? {
        if (stdin == null) stdin = java.io.BufferedReader(
            java.io.InputStreamReader(System.`in`, Charsets.UTF_8)
        )
        return stdin!!.readLine()
    }
}

class DesktopFiles : NcodeFiles {
    override fun readText(path: String): String {
        val file = java.io.File(path)
        if (!file.isFile) throw NcodeError("нет файла `$path`")
        return try {
            readNcodeLines(file).joinToString("\n")
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$path`")
        }
    }
    override fun writeText(path: String, text: String) {
        val file = java.io.File(path)
        try {
            val parent = file.parentFile
            if (parent != null) parent.mkdirs()
            file.writeText(text + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            throw NcodeError("не могу записать `$path`")
        }
    }
    override fun appendText(path: String, text: String) {
        val file = java.io.File(path)
        try {
            val parent = file.parentFile
            if (parent != null) parent.mkdirs()
            file.appendText(text + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            throw NcodeError("не могу записать `$path`")
        }
    }
    override fun delete(path: String): Boolean {
        return try {
            java.io.File(path).delete()
        } catch (e: Exception) {
            false
        }
    }
    override fun exists(path: String): Boolean {
        return try {
            java.io.File(path).exists()
        } catch (e: Exception) {
            false
        }
    }
    override fun isFile(path: String): Boolean {
        return try {
            java.io.File(path).isFile
        } catch (e: Exception) {
            false
        }
    }
    override fun isDir(path: String): Boolean {
        return try {
            java.io.File(path).isDirectory
        } catch (e: Exception) {
            false
        }
    }
    override fun mkdirs(path: String): Boolean {
        return try {
            val dir = java.io.File(path)
            if (dir.isDirectory) true else dir.mkdirs()
        } catch (e: Exception) {
            false
        }
    }
    override fun realPath(path: String): String {
        return try {
            java.io.File(path).canonicalPath
        } catch (e: Exception) {
            path
        }
    }
}

class DesktopSoundLine(private val line: javax.sound.sampled.SourceDataLine) : NcodeSoundLine {
    override fun start() = line.start()
    override fun write(data: ByteArray, off: Int, len: Int) {
        line.write(data, off, len)
    }
    override fun drain() = line.drain()
    override fun stop() = line.stop()
    override fun close() = line.close()
    override fun setVolume(vol: Int) {
        if (!line.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) return
        val g = line.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN) as javax.sound.sampled.FloatControl
        if (vol <= 0) g.value = g.minimum
        else {
            val db = (20.0 * kotlin.math.log10(vol.toDouble() / 100.0)).toFloat()
            g.value = db.coerceIn(g.minimum, g.maximum)
        }
    }
}

class DesktopSound : NcodeSound {
    override fun decodeFile(displayPath: String, canonPath: String, ext: String): SndPcm {
        val file = java.io.File(canonPath)
        return when (ext) {
            "wav" -> decodeWav(file, displayPath)
            "mp3" -> decodeMp3(file, displayPath)
            "ogg" -> decodeOgg(file, displayPath)
            else -> decodeM4a(file, displayPath)
        }
    }
    override fun openLine(rate: Int, channels: Int): NcodeSoundLine {
        val fmt = javax.sound.sampled.AudioFormat(
            javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
            rate.toFloat(), 16, channels, channels * 2, rate.toFloat(), false
        )
        val line = try {
            val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, fmt)
            javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
        } catch (e: Exception) {
            throw NcodeError("нет звука — нет аудиоустройства")
        }
        try {
            line.open(fmt)
        } catch (e: Exception) {
            throw NcodeError("нет звука — нет аудиоустройства")
        }
        return DesktopSoundLine(line)
    }
    private fun readAll(s: javax.sound.sampled.AudioInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = try {
                s.read(buf)
            } catch (e: Exception) {
                throw NcodeError("не могу прочитать звук")
            }
            if (n < 0) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
    private fun shortsToBytes(s: ShortArray, n: Int, out: java.io.ByteArrayOutputStream) {
        var i = 0
        while (i < n) {
            val v = s[i].toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            i++
        }
    }
    private fun floatsToBytes(ch: List<FloatArray>, out: java.io.ByteArrayOutputStream) {
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
    private fun decodeWav(file: java.io.File, path: String): SndPcm {
        val src = try {
            javax.sound.sampled.AudioSystem.getAudioInputStream(file)
        } catch (e: Exception) {
            throw NcodeError("не звук `$path`")
        }
        try {
            val base = src.format
            val to = javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false
            )
            val c = try {
                javax.sound.sampled.AudioSystem.getAudioInputStream(to, src)
            } catch (e: Exception) {
                throw NcodeError("не звук `$path`")
            }
            val data = readAll(c)
            if (data.isEmpty()) throw NcodeError("не звук `$path`")
            return SndPcm(base.sampleRate.toInt(), base.channels, data)
        } finally {
            try { src.close() } catch (e: Exception) {}
        }
    }
    private fun decodeMp3(file: java.io.File, path: String): SndPcm {
        val inf = try {
            java.io.BufferedInputStream(java.io.FileInputStream(file))
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$path`")
        }
        try {
            val bs = javazoom.jl.decoder.Bitstream(inf)
            val dec = javazoom.jl.decoder.Decoder()
            val out = java.io.ByteArrayOutputStream()
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
                    shortsToBytes(b.buffer, b.bufferLength, out)
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
        } finally {
            try { inf.close() } catch (e: Exception) {}
        }
    }
    private fun decodeOgg(file: java.io.File, path: String): SndPcm {
        val inf = try {
            java.io.FileInputStream(file)
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$path`")
        }
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
            var n = inf.read(oy.data, idx, 8192)
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
                n = inf.read(oy.data, idx, 8192)
                if (n <= 0) break
                oy.wrote(n)
            }
            vd.synthesis_init(vi)
            vb.init(vd)
            val out = java.io.ByteArrayOutputStream()
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
                    n = inf.read(oy.data, idx, 8192)
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
        } finally {
            try { inf.close() } catch (e: Exception) {}
        }
    }
    private fun decodeM4a(file: java.io.File, path: String): SndPcm {
        val raf = try {
            java.io.RandomAccessFile(file, "r")
        } catch (e: Exception) {
            throw NcodeError("не могу прочитать `$path`")
        }
        try {
            val inp = net.sourceforge.jaad.mp4.MP4InputStream.open(raf)
            val cont = try {
                net.sourceforge.jaad.mp4.MP4Container(inp)
            } catch (e: Exception) {
                throw NcodeError("не звук `$path`")
            }
            var at: net.sourceforge.jaad.mp4.api.AudioTrack? = null
            for (t in cont.movie.tracks) {
                if (t is net.sourceforge.jaad.mp4.api.AudioTrack && t.codec == net.sourceforge.jaad.mp4.api.AudioTrack.AudioCodec.AAC) at = t
            }
            val track = at ?: throw NcodeError("не звук `$path`")
            val dec = try {
                net.sourceforge.jaad.aac.Decoder.create(track.decoderSpecificInfo.data)
            } catch (e: Exception) {
                throw NcodeError("не звук `$path`")
            }
            val out = java.io.ByteArrayOutputStream()
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
                throw NcodeError("не звук `$path`")
            }
            if (out.size() == 0) throw NcodeError("не звук `$path`")
            val af = dec.audioFormat
            return SndPcm(af.sampleRate.toInt(), af.channels, out.toByteArray())
        } finally {
            try { raf.close() } catch (e: Exception) {}
        }
    }
}

class DesktopPlatform : NcodePlatform {
    override val files: NcodeFiles = DesktopFiles()
    override val clock: NcodeClock = DesktopClock()
    override val console: NcodeConsole = DesktopConsole()
    override val sound: NcodeSound = GdxJvmSound()
    override val gfx: NcodeGfx = GdxDesktopGfx()
    override val assets: NcodeAssets = DesktopAssets()
}

class DesktopAssets : NcodeAssets {
    private val cache = mutableMapOf<String, GfxImage>()
    override fun imageFor(displayPath: String, baseDir: String?): Pair<String, GfxImage> {
        val fromDir = baseDir?.let { java.io.File(it) }
        val given = java.io.File(displayPath)
        val file = when {
            given.isAbsolute -> given
            fromDir != null && java.io.File(fromDir, displayPath).exists() -> java.io.File(fromDir, displayPath)
            java.io.File(displayPath).exists() -> java.io.File(displayPath)
            fromDir != null -> java.io.File(fromDir, displayPath)
            else -> given
        }
        if (!file.isFile) throw NcodeError("нет файла `$displayPath`")
        val canon = try {
            file.canonicalPath
        } catch (e: Exception) {
            throw NcodeError("плохой путь `$displayPath`")
        }
        cache[canon]?.let { return canon to it }
        val src = try {
            javax.imageio.ImageIO.read(file)
        } catch (e: Exception) {
            null
        } ?: throw NcodeError("не картинка `$displayPath`")
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getRGB(0, 0, w, h, px, 0, w)
        val img = GfxImage(w, h, px)
        cache[canon] = img
        return canon to img
    }
}

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
      Ncode --ide             — лёгкая среда: редактор и запуск
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
      ждать пока <условие>                 — ждать пока станет истиной (формы: пока, покуда, до)
        ждать пока "готово" равно да
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
      пока <условие> — цикл пока правда, тело до `конец` (только блоком)
      повторяй/всегда — бесконечно, тело до `конец`; стоп — выйти, дальше — дальше
      создать список/добавить/взять/длина/убрать/очистить — списки с 1
      записать/добавить/прочитать/удалить файл, есть ли файл, создать папку — файлы
      запрос гет/пост/пут/делит/патч <адрес> [данные <тело>] сохранить в <имя> — интернет (ответ — текст, код ответа — число)
      записать в базу <адрес> <путь> <значение> (перезаписывает), создать в базе — только если нет, прочитать базу <адрес> <путь> сохранить в <имя>, удалить из базы <адрес> <путь> — Firebase (адрес и путь можно менять местами)
      найти/заменить — строки; вещать X данные Y — данные в обработчик
      создать окно/открыть окно, закрыть окно — окно 800×600 mygame
      масштабирование окна истина/ложь (можно 1/0) — по умолчанию ложь: окно фиксировано; истина: можно разворачивать, всё тянется само
      нарисовать <имя> x y, задать/изменить прозрачность/размер/поворот/цвет/образ/текстуру/форму/слой/скорость/тип движения объекту — 2д (текстура = тот же образ, объект не пересоздаётся: задать текстуру офис.png объекту какашка)
      написать/надпись <имя> <текст>, наверх/выше/вниз/ниже [имя], задать фон R G B|цвет, показать/скрыть/очистить — 2д
      показать переменную <имя> x y размер прозрачность r g b, скрыть переменную <имя> — переменная на экране
      икс/x/х, игрек/y/у, размер, угол/поворот, виден, прозрачность, форма, скорость <имя>,
      # и // — комментарии до конца строки (внутри "..." не режутся)
      касается А Б/край, расстояние А Б, нажата К/мышь, отпущена К — запросы (отпущена = клавиша не нажата)
      свойство х/y/размера/ширины/высоты/угла/костюма/прозрачности/красного/зеленого/синего/видимости/текста/пера/слоя/формы/скорости/типа объекта <имя>
      задать гравитацию X Y (м/с2, 1м=100px: 0 -9.8 = земная), гравитация x/y, задать плотность воздуха ЗНАЧЕНИЕ — мир
      приложить силу X Y объекту ИМЯ, приложить импульс X Y объекту ИМЯ, приложить силу в точке X Y по координатам PX PY объекту ИМЯ, приложить крутящий момент ЗНАЧЕНИЕ объекту ИМЯ, притянуть объект ИМЯ1 к объекту ИМЯ2 с силой СИЛА, остановить все силы объекту ИМЯ — силы
      сделать тело динамическим/статическим/кинематическим объекту ИМЯ, задать массу МАССА объекту ИМЯ, масса объекта ИМЯ, задать упругость 0..1 объекту ИМЯ, задать трение ЗНАЧЕНИЕ объекту ИМЯ, задать сопротивление воздуха/вращению ЗНАЧЕНИЕ объекту ИМЯ, зафиксировать вращение ИСТИНА/ЛОЖЬ объекту ИМЯ, задать масштаб гравитации ЗНАЧЕНИЕ объекту ИМЯ, задать пулевой режим ИСТИНА/ЛОЖЬ объекту ИМЯ — тело
      задать скорость X Y объекту ИМЯ (px/сек), скорость x/y объекта ИМЯ, общая скорость объекта ИМЯ, задать угловую скорость СКОРОСТЬ объекту ИМЯ (град/сек), угловая скорость объекта ИМЯ, телепортировать X Y объекту ИМЯ — скорость
      задать коллайдер коробка ШИРИНА ВЫСОТА объекту ИМЯ, задать коллайдер круг РАДИУС объекту ИМЯ, задать коллайдер капсула ШИРИНА ВЫСОТА объекту ИМЯ, задать смещение коллайдера DX DY объекту ИМЯ, сделать триггером ИСТИНА/ЛОЖЬ объекту ИМЯ, объект ИМЯ является триггером — коллайдер
      соединить веревкой ИМЯ1 и ИМЯ2 длина ДЛИНА, соединить пружиной ИМЯ1 и ИМЯ2 жесткость К гашение D, соединить шарниром ИМЯ1 и ИМЯ2 в точке X Y, ограничить угол шарнира МИН МАКС соединению ID, задать мотор шарнира СКОРОСТЬ сила СИЛА соединению ID, удалить соединение ID, задать прочность соединения СИЛА соединению ID — суставы
      когда происходит столкновение ИМЯ1 и ИМЯ2, когда объект ИМЯ1 входит в триггер ИМЯ2, объект ИМЯ на земле, сила последнего удара объекта ИМЯ, точка столкновения x/y, бросить луч от X1 Y1 до X2 Y2 пересекает ИМЯ, дистанция луча до ИМЯ от X Y в направлении УГОЛ — события
      цвет словом: красный зелёный синий белый чёрный жёлтый оранжевый фиолетовый розовый серый голубой
      играть/включить звук <путь> (.mp3 .wav .m4a .ogg, эффектов сразу максимум 10), играть звук и ждать <путь>,
      играть музыку <путь> — фон по кругу (одна, новая сменяет старую), остановить музыку [путь],
      остановить/выключить звук|музыку <путь>, пауза/продолжить звук <путь>, задать громкость <число> для звука <путь>
      следить за объектом <имя> истина/ложь (можно 1/0, короче: следить <имя> <флаг>) — камера едет за объектом, ложь — назад в центр
      задать темноту 0..100 — ночь на весь мир; создать свет <имя> x y, задать размер/цвет/силу/мерцание свету <имя>, показать/скрыть/удалить свет <имя>, привязать свет <имя> к объекту <цель> — лампы
      запустить <файл.ncode> [истина|ложь] — без флага/ложь: выполнить скрипт поверх сцены; истина: стереть сцену (переменные живы), остановить всех и начать другой скрипт
      идти/повернуть/плыть/двигать — движение; отскочить от края; создать клон/удалить — клоны
      чтобы <имя> ... вызвать <имя> — процедуры (конец не пишем); каждые <число> <единица> — таймер
      когда нажата клавиша К / когда отпущена клавиша К (слово клавиша можно пропустить: когда нажата a), при нажатии/отпускании/движении мыши — ввод; перемешать список — списки
    Знаки — то же словами: + плюс, - минус, * умножить, / разделить, % остаток,
      = и == равно, != неравно, > больше, < меньше, >= <=, && и, || или
    Формулы (везде, где значение): случайно 1 5, корень 9, модуль -5,
      округлить 3.7, степень 2 10, минимум 3 7, максимум 3 7, длина "слово",
      синус 90, косинус 0, время (секунды с запуска)
    Выражения: .. > умножить/разделить/остаток > плюс/минус > сравнение > не > и > или
      сравнения: равно/равняется, неравно/неравняется, больше, меньше,
                 больше или равно/равняется, меньше или равно/равняется
      правда: истина/да/правда; ложь: ложь/нет/неправда
    Примеры: test.ncode, test2.ncode. Дока: NCODE_v0.1.md
""".trimIndent()

internal fun readNcodeLines(file: java.io.File): List<String> {
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

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.out)), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.err)), true, "UTF-8"))
    enableUtf8Console()
    if (args.isNotEmpty() && args[0].lowercase() in setOf("--ide", "-ide", "иде", "редактор")) {
        runIde()
        return
    }
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
        val probe = NcodeInterpreter(DesktopPlatform())
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
    val plat = DesktopPlatform()
    val interp = NcodeInterpreter(plat)
    interp.dryRun = dry
    interp.checkLabel = relLabel(fileArg)
    if (dry) {
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
            interp.warnStaticCycles(file, lines)
            if (interp.checkHandlers() != 0) code = 1
            if (interp.checkKeyHandlers() != 0) code = 1
        } catch (e: BreakSignal) {
            System.err.println("стоп — только внутри цикла")
            code = 1
        } catch (e: ContinueSignal) {
            System.err.println("дальше — только внутри цикла")
            code = 1
        }
        if (code != 0) kotlin.system.exitProcess(code)
        return
    }
    val t = Thread({
        var code = 0
        try {
            val curSkips = try {
                interp.loadHandlers(lines)
            } catch (e: NcodeError) {
                System.err.println("Ошибка: " + e.message)
                kotlin.system.exitProcess(1)
                return@Thread
            }
            interp.resetScripts(safeCanon(file))
            code = interp.runLines(lines, 1, curSkips)
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
        interp.warnNoWindowForInput()
        while (interp.isWindowOpen()) {
            interp.checkEvents()
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }
    }, "ncode-script")
    t.start()
    try {
        t.join()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    try {
        (plat.gfx as? GdxDesktopGfx)?.exitApp()
    } catch (e: Exception) {
    }
}
