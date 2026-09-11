package ncode.app

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import ncode.BreakSignal
import ncode.ContinueSignal
import ncode.NcodeError
import ncode.NcodeInterpreter
import ncode.SceneStop

class MainActivity : com.badlogic.gdx.backends.android.AndroidApplication() {
    private lateinit var tv: TextView
    private lateinit var root: FrameLayout
    private lateinit var consoleView: ScrollView
    private var plat: AndroidPlatform? = null

    private fun log(s: String) {
        runOnUiThread {
            if (!isFinishing) {
                tv.append(s)
                tv.post {
                    consoleView.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private fun showGame(v: android.view.View) {
        runOnUiThread {
            if (!isFinishing) {
                root.removeAllViews()
                root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun showConsole() {
        runOnUiThread {
            if (!isFinishing) {
                root.removeAllViews()
                root.addView(consoleView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
    }

    override fun onBackPressed() {
        val g = plat?.gfx
        if (g is AndroidGfx && g.userBack()) return
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        val g = plat?.gfx
        if (g is AndroidGfx) g.onActivityPaused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this)
        tv.setTextIsSelectable(true)
        tv.textSize = 16f
        consoleView = ScrollView(this)
        consoleView.addView(tv)
        root = FrameLayout(this)
        root.addView(consoleView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        val p = AndroidPlatform(this, { s -> log(s) }, { v -> showGame(v) }, { showConsole() })
        plat = p
        Thread {
            try {
                var name = "game.ncode"
                val text = try {
                    assets.open(name).bufferedReader(Charsets.UTF_8).readText()
                } catch (e: Exception) {
                    name = "test.ncode"
                    assets.open(name).bufferedReader(Charsets.UTF_8).readText()
                }
                val lines = text.split("\n")
                val probe = NcodeInterpreter(p)
                probe.dryRun = true
                probe.checkLabel = name
                val skips0 = probe.loadHandlers(lines)
                probe.resetScripts(name)
                var code = probe.runLines(lines, 1, skips0)
                if (probe.checkHandlers() != 0) code = 1
                if (probe.checkKeyHandlers() != 0) code = 1
                if (code != 0) {
                    log("Ошибки проверки\n")
                    return@Thread
                }
                val it = NcodeInterpreter(p)
                it.resetScripts(name)
                val skips = try {
                    it.loadHandlers(lines)
                } catch (e: NcodeError) {
                    log("Ошибка: " + (e.message ?: e.toString()) + "\n")
                    return@Thread
                }
                code = try {
                    it.runLines(lines, 1, skips)
                } catch (e: SceneStop) {
                    0
                } catch (e: BreakSignal) {
                    log("стоп — только внутри цикла\n")
                    1
                } catch (e: ContinueSignal) {
                    log("дальше — только внутри цикла\n")
                    1
                }
                if (code != 0) {
                    log("Готово с ошибками\n")
                    return@Thread
                }
                while (it.isWindowOpen()) {
                    it.checkEvents()
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                if (!it.isWindowOpen()) log("Готово\n")
            } catch (e: Exception) {
                log("Ошибка: " + (e.message ?: e.toString()) + "\n")
            }
        }.start()
    }
}
