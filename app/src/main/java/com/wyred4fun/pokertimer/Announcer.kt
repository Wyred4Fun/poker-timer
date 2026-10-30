package com.wyred4fun.pokertimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The chime, the wood knock and the spoken announcements.
 * Sounds are made in code (no sound files), the voice is the box's own text-to-speech.
 */
object Announcer {
    private const val RATE = 44100
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var knockTrack: AudioTrack? = null
    private var chimeTrack: AudioTrack? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let {
                        val r = it.setLanguage(Locale.US)
                        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                        it.setSpeechRate(0.92f)
                    }
                }
            }
        } catch (e: Exception) {
            tts = null
        }
        knockTrack = makeTrack(knockPcm())
        chimeTrack = makeTrack(chimePcm())
    }

    private fun soundsOn() = app?.let { Prefs.sounds(it) } ?: true
    private fun voiceOn() = app?.let { Prefs.voice(it) } ?: true

    fun knock() { if (soundsOn()) play(knockTrack) }
    fun chime() { if (soundsOn()) play(chimeTrack) }

    fun say(text: String, delayMs: Long = 0) {
        if (!voiceOn()) return
        val r = Runnable {
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poker")
        }
        if (delayMs > 0) main.postDelayed(r, delayMs) else r.run()
    }

    /** Stop anything waiting to be said, and anything being said. */
    fun hush() {
        main.removeCallbacksAndMessages(null)
        try { tts?.stop() } catch (e: Exception) {}
    }

    /** The full sequence, for the Test Sounds button. */
    fun demo() {
        hush()
        chime()
        say("Attention players. Blinds will be going up in one minute.", 1500)
        for (i in 0 until 5) main.postDelayed({ knock() }, 6000L + i * 1000L)
        main.postDelayed({ say("Blinds are now 2 and 4.") }, 11400L)
    }

    private fun play(t: AudioTrack?) {
        if (t == null) return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {}
    }

    private fun makeTrack(pcm: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t
    } catch (e: Exception) {
        null
    }

    /** A block of wood hitting something: a short woody click plus a low thump. */
    fun knockPcm(): ShortArray {
        val n = (RATE * 0.22).toInt()
        val out = FloatArray(n)
        val rnd = java.util.Random(7)
        // band-pass resonator around 950 Hz for the woody click
        val f = 950.0; val q = 2.2
        val w = 2 * PI * f / RATE
        val alpha = sin(w) / (2 * q)
        val b0 = alpha; val b2 = -alpha
        val a0 = 1 + alpha; val a1 = -2 * kotlin.math.cos(w); val a2 = 1 - alpha
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val clickLen = (RATE * 0.09).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val x = if (i < clickLen) (rnd.nextDouble() * 2 - 1) * Math.pow(1.0 - i.toDouble() / clickLen, 5.0) else 0.0
            val y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            // thump: 210 Hz falling to 105 Hz, fast decay
            val freq = 210.0 * Math.pow(0.5, (t / 0.12).coerceAtMost(1.0))
            phase += 2 * PI * freq / RATE
            val attack = (t / 0.004).coerceAtMost(1.0)
            val thump = sin(phase) * attack * exp(-t / 0.035)
            out[i] = (y * 2.2 + thump * 0.9).toFloat()
        }
        return toPcm(out, 0.9f)
    }

    /** A mellow two-note chime (E then C) with soft edges. */
    fun chimePcm(): ShortArray {
        val n = (RATE * 2.0).toInt()
        val out = FloatArray(n)
        val notes = listOf(659.25 to 0.0, 523.25 to 0.45)
        for ((freq, start) in notes) {
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val env = (t / 0.06).coerceAtMost(1.0) * exp(-t / 0.42)
                val v = sin(2 * PI * freq * t) * 0.8 + sin(2 * PI * freq * 2 * t) * 0.15 + sin(2 * PI * freq * 3 * t) * 0.05
                out[i] += (v * env).toFloat()
            }
        }
        return toPcm(out, 0.55f)
    }

    private fun toPcm(src: FloatArray, peak: Float): ShortArray {
        var max = 0f
        for (v in src) if (kotlin.math.abs(v) > max) max = kotlin.math.abs(v)
        val g = if (max > 0) peak / max else 0f
        return ShortArray(src.size) { (src[it] * g * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }
}
