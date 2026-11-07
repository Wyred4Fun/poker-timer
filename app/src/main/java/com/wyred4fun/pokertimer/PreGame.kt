package com.wyred4fun.pokertimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Friday night, by itself:
 *  - Friday 5:00 PM: the "Poker starts sharp at 8:00 PM" countdown comes up and stays up.
 *  - Friday 8:00 PM: the game starts with no one touching anything (Start is already pressed; OK pauses it).
 *  - Saturday 2:00 AM: the night is reset and the club billboard takes over again.
 *  - Saturday to Friday afternoon: the countdown takes the last 3 minutes of every quarter hour between slides.
 * It only runs while the app is open on the TV; the app does not open itself for it.
 */
object PreGame {
    const val GAME_DAY = Calendar.FRIDAY
    const val SHOW_HOUR = 17          // Friday 5:00 PM
    const val END_HOUR = 2            // Saturday 2:00 AM
    const val TITLE = "FRIDAY NIGHT GAME"

    /** Game start, minutes after midnight (Setup: 7:30 PM, 8:00 PM or custom). Default 8:00 PM. */
    const val DEFAULT_START = 20 * 60
    const val START_MIN = 17 * 60 + 30   // custom range: 5:30 PM ...
    const val START_MAX = 23 * 60 + 30   // ... to 11:30 PM
    @Volatile var gameMinutes = DEFAULT_START

    /** Reads the saved start time (call whenever the app comes forward or Setup changes it). */
    fun loadStart(ctx: android.content.Context) {
        val v = ctx.getSharedPreferences("poker_timer", android.content.Context.MODE_PRIVATE).getInt("startMin", DEFAULT_START)
        gameMinutes = v.coerceIn(START_MIN, START_MAX)
    }

    fun startsAt(): String = format(gameMinutes)

    fun format(minutes: Int): String {
        val h = minutes / 60; val m = minutes % 60
        return "${if (h % 12 == 0) 12 else h % 12}:${if (m < 10) "0$m" else "$m"} ${if (h < 12) "AM" else "PM"}"
    }

    /** Wall clock (ms); tests swap it. */
    var clock: () -> Long = { System.currentTimeMillis() }

    /** One Friday night around [now]: when the countdown comes up, when the game starts, when the night ends (Saturday 2 AM). */
    data class Window(val showFrom: Long, val gameAt: Long, val endsAt: Long)

    fun window(now: Long): Window {
        // the night belongs to the day it began: before 2 AM Saturday it is still Friday's
        val ref = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.HOUR_OF_DAY, -END_HOUR) }
        val back = (ref.get(Calendar.DAY_OF_WEEK) - GAME_DAY + 7) % 7          // days since Friday
        val day = (ref.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, if (back == 0) 0 else 7 - back)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun at(h: Int, m: Int, plusDays: Int = 0) = (day.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, plusDays); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        }.timeInMillis
        return Window(at(SHOW_HOUR, 0), at(gameMinutes / 60, gameMinutes % 60), at(END_HOUR, 0, 1))
    }

    /** Friday evening heads-ups sit at fixed clock times (6:00, 6:30, 7:00 and 7:15 PM), whatever the start time; each says how long is left. */
    val HEADS_UP_AT = listOf(18 * 60, 18 * 60 + 30, 19 * 60, 19 * 60 + 15)

    fun headsUp(minutes: Int): String = when (minutes) {
        120 -> "Attention players. Poker starts in 2 hours."
        90 -> "Attention players. Poker starts in 90 minutes."
        60 -> "Attention players. Poker starts in one hour."
        else -> "Attention players. Poker starts in $minutes minutes."
    }

    /** The most recent Saturday 2:00 AM at or before [now]. */
    fun lastEnd(now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -7) }
        return window(c.timeInMillis).endsAt
    }

    /** While the slides run, the countdown takes a turn: the last 3 minutes of every quarter hour (:12 to :15, :27 to :30, ...). */
    const val ROTATE_EVERY_MIN = 15
    const val ROTATE_SHOW_MIN = 3
    fun rotationSlot(wall: Long): Boolean = (wall / 60_000L) % ROTATE_EVERY_MIN >= ROTATE_EVERY_MIN - ROTATE_SHOW_MIN

    /** Should the countdown screen be up? Only in the window, and only if the night hasn't started since it opened. */
    fun showing(now: Long, startedAt: Long, running: Boolean): Boolean {
        val w = window(now)
        if (now < w.showFrom || now >= w.endsAt) return false
        if (running) return false
        return startedAt < w.showFrom
    }

    /** Only the Catalpa Club Poker Timer version has the Friday countdown. */
    fun enabled(ctx: Context) = BuildConfig.CLUB &&
        ctx.getSharedPreferences("poker_timer", Context.MODE_PRIVATE).getBoolean("preGame", true)

    /**
     * The app does not open itself for Friday night. This only clears the Thursday alarm that earlier
     * builds set, so nothing is left behind (it runs at startup and when Setup changes).
     */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, PreGameReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try { am.cancel(pi) } catch (e: Exception) {}
    }
}

/** Left in place for any alarm an earlier build set: it does nothing now. */
class PreGameReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PreGame.schedule(ctx.applicationContext)
    }
}
