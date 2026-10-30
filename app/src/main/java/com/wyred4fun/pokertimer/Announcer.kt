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
 * The knock is a recorded wood-block sound (res/raw/knock.wav), the chime is made in code,
 * and the voice is the box's own text-to-speech.
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
        knockTrack = loadWav(ctx, R.raw.knock)?.let { (pcm, rate) -> makeTrack(pcm, rate) }
        chimeTrack = makeTrack(chimePcm(), RATE)
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

    /** Reads a mono 16-bit WAV from res/raw: the samples and their sample rate. */
    private fun loadWav(ctx: Context, resId: Int): Pair<ShortArray, Int>? = try {
        val b = ctx.resources.openRawResource(resId).use { it.readBytes() }
        fun le32(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
        val rate = le32(24)
        var p = 12
        var data = -1; var len = 0
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val size = le32(p + 4)
            if (id == "data") { data = p + 8; len = minOf(size, b.size - data); break }
            p += 8 + size + (size and 1)
        }
        if (data < 0) null else {
            val n = len / 2
            Pair(ShortArray(n) { ((b[data + 2 * it].toInt() and 0xff) or (b[data + 2 * it + 1].toInt() shl 8)).toShort() }, rate)
        }
    } catch (e: Exception) {
        null
    }

    private fun makeTrack(pcm: ShortArray, rate: Int): AudioTrack? = try {
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
                    .setSampleRate(rate)
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
