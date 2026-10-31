package com.wyred4fun.pokertimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Opens the app when the box starts up, if that switch is on in Setup (a billboard-only screen always does).
 *
 * Some boxes are slow to settle at startup, so if the first try doesn't bring the app up, it tries again
 * 30 and 90 seconds later. What happened is written down (see [Log]) and Setup shows it, so a startup
 * that didn't work says which part failed: no startup message from Android, or Android refused to open the app.
 */
class BootReceiver : BroadcastReceiver() {

    /** The startup record, shown in Setup under "Open at startup". */
    object Log {
        private const val P = "poker_timer"
        private fun sp(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

        fun heard(ctx: Context, action: String) {
            sp(ctx).edit().putLong("bootSeenAt", System.currentTimeMillis()).putString("bootAction", action)
                .putBoolean("bootOverlay", Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)).apply()
        }

        /** Called when the timer screen comes up: the startup worked if that happens soon after the startup message. */
        fun opened(ctx: Context) {
            val s = sp(ctx)
            val seen = s.getLong("bootSeenAt", 0L); val now = System.currentTimeMillis()
            if (seen > 0 && s.getLong("bootOpenedAt", 0L) < seen && now - seen < 10 * 60_000L) s.edit().putLong("bootOpenedAt", now).apply()
        }

        fun cameUp(ctx: Context): Boolean { val s = sp(ctx); return s.getLong("bootOpenedAt", 0L) >= s.getLong("bootSeenAt", 0L) && s.getLong("bootSeenAt", 0L) > 0 }

        /** One line for Setup, or "" if no startup has been seen yet. */
        fun line(ctx: Context): String {
            val s = sp(ctx)
            val seen = s.getLong("bootSeenAt", 0L)
            if (seen == 0L) return "No startup seen yet"
            val f = java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(ctx)) "EEE H:mm" else "EEE h:mm a", java.util.Locale.US)
            val at = f.format(java.util.Date(seen))
            return if (cameUp(ctx)) "Last startup $at: opened"
            else if (!s.getBoolean("bootOverlay", true)) "Startup $at: blocked, needs Display over apps"
            else "Startup $at: asked, but it did not open"
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        val app = ctx.applicationContext
        when (a) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                PreGame.schedule(app)   // alarms are cleared when the box restarts
                if (!wanted(app)) return
                Log.heard(app, a)
                launchSoon(app, 4000)
                retryAt(app, 30_000L, 8)
                retryAt(app, 90_000L, 9)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // an update was just installed (the app was closed to do it): bring it back up
                PreGame.schedule(app)
                if (!wanted(app)) return
                Log.heard(app, a)
                launchSoon(app, 2000)
            }
            RETRY -> {
                if (intent.component == null) return   // only our own alarm, not another app
                // the first try didn't bring the app up
                if (!wanted(app) || Log.cameUp(app) || MainActivity.foreground) return
                launchSoon(app, 0)
            }
        }
    }

    private fun wanted(app: Context) = BuildConfig.SIGNAGE || Prefs.openAtStartup(app)

    /** Give the home screen a few seconds to come up first, so the app lands on top of it. */
    private fun launchSoon(app: Context, delayMs: Long) {
        val pending: PendingResult? = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
            pending?.finish()
        }, delayMs)
    }

    private fun retryAt(app: Context, afterMs: Long, code: Int) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(app, code, Intent(app, BootReceiver::class.java).setAction(RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val at = SystemClock.elapsedRealtime() + afterMs
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: Exception) {
        }
    }

    companion object { const val RETRY = "com.wyred4fun.pokertimer.START_RETRY" }
}
