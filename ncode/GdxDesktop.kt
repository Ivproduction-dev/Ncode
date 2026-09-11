package ncode

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWWindowCloseCallback

class GdxJvmSound : NcodeSound {
    private val dec = DesktopSound()
    override fun decodeFile(displayPath: String, canonPath: String, ext: String): SndPcm {
        return dec.decodeFile(displayPath, canonPath, ext)
    }
    override fun openLine(rate: Int, channels: Int): NcodeSoundLine {
        return try {
            if (Gdx.app == null) throw IllegalStateException()
            GdxSoundLine(rate, channels >= 2)
        } catch (e: Exception) {
            dec.openLine(rate, channels)
        }
    }
}

class GdxDesktopGfx : NcodeGfx {
    @Volatile private var game: NcodeGdxGame? = null
    @Volatile private var shown = false
    @Volatile private var appStarted = false
    @Volatile private var appExited = false
    @Volatile private var wantResizable = false
    @Volatile private var glThread: Thread? = null
    private var closeCb: GLFWWindowCloseCallback? = null

    private fun armCloseCallback() {
        try {
            val win = (Gdx.graphics as Lwjgl3Graphics).window
            try {
                closeCb?.free()
            } catch (e: Exception) {
            }
            closeCb = object : GLFWWindowCloseCallback() {
                override fun invoke(window: Long) {
                    kotlin.system.exitProcess(0)
                }
            }
            GLFW.glfwSetWindowCloseCallback(win.windowHandle, closeCb)
        } catch (e: Exception) {
        }
    }

    override fun isOpen(): Boolean {
        if (!shown) return false
        val th = glThread ?: return true
        return th.isAlive
    }

    private fun applyResizableGL(resizable: Boolean) {
        try {
            val win = (Gdx.graphics as Lwjgl3Graphics).window
            if (resizable) {
                win.setSizeLimits(-1, -1, -1, -1)
            } else {
                val w = Gdx.graphics.width
                val h = Gdx.graphics.height
                if (w > 0 && h > 0) win.setSizeLimits(w, h, w, h)
            }
        } catch (e: Exception) {
        }
    }

    override fun setResizable(resizable: Boolean) {
        wantResizable = resizable
        val g = game
        if (g != null && g.created) {
            try {
                Gdx.app.postRunnable { applyResizableGL(resizable) }
            } catch (e: Exception) {
            }
        }
    }

    override fun setCamera(x: Double, y: Double) {
        game?.setCam(x, y)
    }

    override fun render(frame: GfxFrame) {
        game?.postFrame(frame)
    }

    override fun closeWindow() {
        shown = false
        val g = game ?: return
        if (!g.created) return
        try {
            Gdx.app.postRunnable {
                try {
                    (Gdx.graphics as Lwjgl3Graphics).window.setVisible(false)
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    fun exitApp() {
        if (!appStarted || appExited) return
        appExited = true
        try {
            Gdx.app.exit()
        } catch (e: Exception) {
        }
        try {
            glThread?.join(2000)
        } catch (e: Exception) {
        }
    }

    private fun glWait(job: () -> Unit) {
        val done = CountDownLatch(1)
        try {
            Gdx.app.postRunnable {
                try {
                    job()
                } catch (e: Exception) {
                } finally {
                    done.countDown()
                }
            }
        } catch (e: Exception) {
            done.countDown()
        }
        try {
            done.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
        }
    }

    override fun openWindow(w: Int, h: Int, title: String, sink: NcodeInputSink, onClose: () -> Unit) {
        if (isOpen()) throw NcodeError("окно уже есть — сначала закрыть окно")
        val g = game
        if (g == null) {
            val ng = NcodeGdxGame()
            ng.baseW = w
            ng.baseH = h
            ng.sink = sink
            ng.onCloseCb = onClose
            val ready = CountDownLatch(1)
            ng.createLatch = ready
            val cfg = Lwjgl3ApplicationConfiguration()
            cfg.setTitle(title)
            cfg.setWindowedMode(w, h)
            cfg.setResizable(wantResizable)
            cfg.useVsync(true)
            cfg.setForegroundFPS(60)
            val self = this
            cfg.setWindowListener(object : Lwjgl3WindowAdapter() {
                override fun closeRequested(): Boolean {
                    self.shown = false
                    try {
                        onClose()
                    } catch (e: Exception) {
                    }
                    kotlin.system.exitProcess(0)
                }
            })
            val t = Thread({
                try {
                    Lwjgl3Application(ng, cfg)
                } catch (e: Exception) {
                    ready.countDown()
                }
            }, "ncode-gl")
            t.isDaemon = true
            glThread = t
            t.start()
            appStarted = true
            if (!ready.await(15, TimeUnit.SECONDS)) throw NcodeError("окно не открылось")
            if (!ng.created) throw NcodeError("окно не открылось")
            game = ng
            glWait { armCloseCallback() }
            shown = true
        } else {
            g.sink = sink
            g.onCloseCb = onClose
            glWait {
                g.rebuildViewport(w, h)
                try {
                    Gdx.graphics.setTitle(title)
                } catch (e: Exception) {
                }
                applyResizableGL(wantResizable)
                try {
                    (Gdx.graphics as Lwjgl3Graphics).window.setVisible(true)
                } catch (e: Exception) {
                }
            }
            shown = true
        }
    }
}
