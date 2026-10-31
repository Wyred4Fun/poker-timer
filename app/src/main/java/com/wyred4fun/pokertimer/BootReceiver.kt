package com.wyred4fun.pokertimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Opens Poker Timer when the box starts up, if that switch is on in Setup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED) return
        PreGame.schedule(ctx.applicationContext)   // alarms are cleared when the box restarts
        if (!BuildConfig.SIGNAGE && !Prefs.openAtStartup(ctx)) return   // a billboard screen always starts itself
        val app = ctx.applicationContext
        val pending: PendingResult? = goAsync()
        // give the home screen a few seconds to come up first, so the timer lands on top of it
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
            pending?.finish()
        }, 4000)
    }
}
