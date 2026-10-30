package com.wyred4fun.pokertimer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Update app: looks for a newer build on the GitHub Releases page, downloads it
 * and hands it to Android to install over this one.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/Wyred4Fun/poker-timer/releases?per_page=20"

    data class Release(val number: Int, val apkUrl: String, val size: Long)

    /** One download/install at a time for the whole app, even if Setup is closed and reopened. */
    val busy = AtomicBoolean(false)

    /** May this app hand APKs to the installer? (Android 8+: "Install unknown apps" for Poker Timer.) */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Remove a leftover downloaded update (it is only needed until it is installed). */
    fun cleanUp(ctx: Context) {
        if (!busy.get()) File(ctx.cacheDir, "update.apk").delete()
    }

    /** This app's build number (1.0.N -> N). */
    fun currentNumber(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Newest build on GitHub, or null if none. Runs on a background thread; throws on network trouble. */
    fun newest(): Release? {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "PokerTimer")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            return pick(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** The highest build-N release that has an APK attached. */
    fun pick(json: String): Release? {
            val arr = JSONArray(json)
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val n = r.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (!a.optString("name").endsWith(".apk")) continue
                    if (best == null || n > best.number) best = Release(n, a.optString("browser_download_url"), a.optLong("size"))
                }
            }
            return best
    }

    /** Download the APK, reporting 0-100. Background thread. */
    fun download(ctx: Context, rel: Release, progress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        out.delete()
        val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "PokerTimer")
        try {
            if (c.responseCode != 200) throw IllegalStateException("download answered ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else rel.size
            var done = 0L; var last = -1
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("download was cut short")
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Hand the APK to Android's installer; Android asks to confirm on screen. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { ins ->
                    s.openWrite("update.apk", 0, apk.length()).use { os -> ins.copyTo(os); s.fsync(os) }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
                s.commit(cb.intentSender)
            }
        } catch (e: Exception) {
            // don't leave a half-written install behind
            try { pi.abandonSession(id) } catch (x: Exception) {}
            throw e
        }
    }
}

/** Android reports back here: show its "Install?" screen, or say what went wrong. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (e: Exception) {
                    Toast.makeText(ctx, "Couldn't open the installer", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                Toast.makeText(ctx, "Update didn't install: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
