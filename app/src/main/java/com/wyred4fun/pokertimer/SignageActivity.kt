package com.wyred4fun.pokertimer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Digital Billboard: the slides and nothing else, for the club's other screens.
 * Press OK or Menu on the remote to see the version and check for an update.
 */
class SignageActivity : Activity() {
    private lateinit var board: BillboardView
    private lateinit var panel: TextView
    private var found: Updater.Release? = null
    private var shownAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        board = BillboardView(this)
        root.addView(board, FrameLayout.LayoutParams(-1, -1))
        panel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0xE6000000.toInt()); setPadding(48, 28, 48, 28)
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 60 })
        setContentView(root)
        Updater.cleanUp(this)
    }

    override fun onResume() {
        super.onResume()
        board.start()
        MainActivity.foreground = true; BootReceiver.Log.opened(this)   // so a startup retry knows we're up
    }
    override fun onPause() { MainActivity.foreground = false; board.stop(); super.onPause() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                if (panel.visibility == View.VISIBLE && found != null && keyCode != KeyEvent.KEYCODE_MENU) install() else check()
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (panel.visibility == View.VISIBLE) { panel.visibility = View.GONE; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun say(t: String) { panel.text = t; panel.visibility = View.VISIBLE; shownAt = System.currentTimeMillis() }

    private fun check() {
        found = null
        say("Digital Billboard ${Updater.currentName(this)}\nChecking for an update…")
        if (!Updater.busy.compareAndSet(false, true)) return
        Thread {
            val r = try { Result.success(Updater.newest()) } catch (e: Exception) { Result.failure(e) }
            Updater.busy.set(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val rel = r.getOrNull()
                when {
                    r.isFailure -> say("Digital Billboard ${Updater.currentName(this)}\nCouldn't reach GitHub – check the Wi-Fi")
                    rel != null && rel.number > Updater.currentNumber(this) -> {
                        found = rel
                        say("Digital Billboard ${Updater.currentName(this)}\nUpdate 1.0.${rel.number} is ready – press OK to install")
                    }
                    else -> say("Digital Billboard ${Updater.currentName(this)}\nUp to date")
                }
                val at = shownAt
                panel.postDelayed({ if (shownAt == at && found == null) panel.visibility = View.GONE }, 6000)
            }
        }.start()
    }

    private fun install() {
        val rel = found ?: return
        if (!Updater.canInstall(this)) {
            say("Allow installs for Digital Billboard, then press OK again")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        if (!Updater.busy.compareAndSet(false, true)) return
        Thread {
            try {
                val f = Updater.download(this, rel) { p -> runOnUiThread { if (!isDestroyed) say("Downloading $p%") } }
                Updater.install(this, f)
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) say("Update failed: ${e.message}") }
            }
            Updater.busy.set(false)
        }.start()
    }
}
