package com.wyred4fun.pokertimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * The Friday night countdown: "Poker starts sharp at 7:30 PM".
 * From Thursday 5:00 PM it fills the screen and counts down to Friday 7:30 PM, then shows
 * "Shuffle up and deal" until Start is pressed (or Friday ends). Thursday at 5:00 PM the app opens by itself.
 */
object PreGame {
    const val SHOW_DAY = Calendar.THURSDAY
    const val SHOW_HOUR = 17
    const val GAME_DAY = Calendar.FRIDAY
    const val GAME_HOUR = 19
    const val GAME_MINUTE = 30
    const val TITLE = "FRIDAY NIGHT GAME"
    const val STARTS_AT = "7:30 PM"

    /** Wall clock (ms); tests swap it. */
    var clock: () -> Long = { System.currentTimeMillis() }

    /** This week's window around [now]: when it shows, the start of the game, when it ends (Friday midnight). */
    data class Window(val showFrom: Long, val gameAt: Long, val endsAt: Long)

    fun window(now: Long): Window {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        // the Friday of this week (Saturday to Friday): Saturday and later count toward the coming Friday
        val back = (c.get(Calendar.DAY_OF_WEEK) - GAME_DAY + 7) % 7          // days since the last Friday
        val game = (c.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, if (back == 0) 0 else 7 - back)
            set(Calendar.HOUR_OF_DAY, GAME_HOUR); set(Calendar.MINUTE, GAME_MINUTE)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val show = (game.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -((GAME_DAY - SHOW_DAY + 7) % 7))
            set(Calendar.HOUR_OF_DAY, SHOW_HOUR); set(Calendar.MINUTE, 0)
        }
        val end = (game.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }
        return Window(show.timeInMillis, game.timeInMillis, end.timeInMillis)
    }

    /** Should the countdown screen be up? Only in the window, and only if the night hasn't started since it opened. */
    fun showing(now: Long, startedAt: Long, running: Boolean): Boolean {
        val w = window(now)
        if (now < w.showFrom || now >= w.endsAt) return false
        if (running) return false
        return startedAt < w.showFrom
    }

    /** The next Thursday 5:00 PM after [now]. */
    fun nextPopUp(now: Long): Long {
        val w = window(now)
        if (w.showFrom > now) return w.showFrom
        return window(w.endsAt + 60_000L).showFrom
    }

    /** Only the Catalpa Club Poker Timer version has the Friday countdown. */
    fun enabled(ctx: Context) = BuildConfig.CLUB &&
        ctx.getSharedPreferences("poker_timer", Context.MODE_PRIVATE).getBoolean("preGame", true)

    /** Set (or clear) the alarm that opens the app on Thursday at 5:00 PM. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, PreGameReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.cancel(pi)
            if (!enabled(ctx)) return
            val at = nextPopUp(clock())
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.set(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
        }
    }
}

/** Thursday 5:00 PM: bring the timer up (it shows the countdown), then set next week's alarm. */
class PreGameReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (PreGame.enabled(app)) {
            try {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
        PreGame.schedule(app)
    }
}
