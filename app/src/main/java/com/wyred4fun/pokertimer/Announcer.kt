package com.wyred4fun.pokertimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The chime, the wood knock and the spoken announcements.
 * The notification sound is one of the recorded sounds in res/raw (picked in Setup > Notification sound), the chime is made in code,
 * and the voice is the box's own text-to-speech.
 */
object Announcer {
    private const val RATE = 44100
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var knockTrack: AudioTrack? = null
    private var knockId: String? = null
    /** The chosen sound's samples and rate, for the timed countdown stream. */
    var knockPcm: Pair<ShortArray, Int>? = null
        private set
    /** How many times the chosen notification sound plays before the blinds go up. */
    val knockHits: Int get() = TimerEngine.COUNTDOWN
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
                        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onError(id: String?) {}
                            override fun onDone(id: String?) { prepared(id) }
                        })
                        prepare(DEMO_TEXT)
                    }
                }
            }
        } catch (e: Exception) {
            tts = null
        }
        useKnock(Prefs.knockSound(ctx))
        chimeTrack = makeTrack(chimePcm(), RATE)
    }

    fun soundsOn() = app?.let { Prefs.sounds(it) } ?: true
    private fun voiceMode() = app?.let { Prefs.voiceMode(it) } ?: Prefs.VOICE_BLINDS
    /** Is the spoken 1-minute warning wanted? */
    val warningWords: Boolean get() = voiceMode() == Prefs.VOICE_ALL

    fun knock() { if (soundsOn()) play(knockTrack) }

    /** Switch the countdown knock to another sound (from Sounds.ALL). */
    fun useKnock(id: String) {
        val ctx = app ?: return
        val s = Sounds.byId(id)
        if (s.id == knockId && knockTrack != null) return
        val old = knockTrack
        val wav = loadWav(ctx, s.res)
        knockPcm = wav
        knockTrack = wav?.let { (pcm, rate) -> makeTrack(pcm, rate) }
        knockId = s.id
        try { old?.release() } catch (e: Exception) {}
    }

    /** Pick a knock and play it once (even with sounds switched off) so you can hear what it is. */
    fun pickKnock(ctx: Context, id: String) {
        Prefs.setKnockSound(ctx, id)
        useKnock(id)
        hushPreview()
        // don't play over a real countdown that is happening right now
        val e = MainActivity.live
        val now = android.os.SystemClock.elapsedRealtime()
        if (e != null && e.running && e.next != null && e.secondsLeft(now) <= knockHits + e.knocks + 3) return
        // same timed stream as the real countdown: first hit about 0.6 s after OK
        // one hit only, through the same timed stream the countdown uses
        knockPcm?.let { (pcm, rate) -> Countdown.start(now + 600L + 1000L, 1, pcm, rate, preview = true) }
    }

    /** Previews and Test sounds are tagged, so stopping them never cuts off the running timer. */
    private val PREVIEW = Any()
    private fun later(delayMs: Long, r: () -> Unit) {
        main.postAtTime(Runnable { r() }, PREVIEW, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    /** Stop a preview or Test sounds run (the timer's own announcements carry on). */
    fun hushPreview() {
        main.removeCallbacksAndMessages(PREVIEW)
        if (Countdown.isPreview) Countdown.cancel()
    }
    fun chime() { if (soundsOn()) play(chimeTrack) }

    fun say(text: String, delayMs: Long = 0) {
        if (voiceMode() == Prefs.VOICE_OFF) return
        val r = Runnable {
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poker")
        }
        if (delayMs > 0) main.postDelayed(r, delayMs) else r.run()
    }

    /** Stop anything waiting to be said, and anything being said (and the countdown stream). */
    fun hush() {
        main.removeCallbacksAndMessages(null)
        Countdown.cancel()
        try { tts?.stop() } catch (e: Exception) {}
    }

    // ---------- announcements made ahead of time ----------
    // The voice can take a few seconds to start speaking. So the "blinds have now changed"
    // sentence is turned into sound well before it is needed, and the countdown stream plays it
    // the instant the level changes, right after the last hit.

    const val DEMO_TEXT = "Attention players. The blinds have now changed to 2 and 4."
    private val made = HashMap<String, Pair<ShortArray, Int>>()   // sentence -> samples, rate
    private val asked = HashMap<String, String>()                 // utterance id -> sentence
    private var serial = 0

    /** Start turning [text] into sound in the background (does nothing if it is already done or under way). */
    fun prepare(text: String) {
        val ctx = app ?: return
        val t = tts ?: return
        if (!ttsReady || voiceMode() == Prefs.VOICE_OFF) return
        synchronized(made) {
            if (made.containsKey(text) || asked.containsValue(text)) return
            if (made.size > 8) made.clear()
            val id = "prep" + (++serial)
            asked[id] = text
            val f = File(ctx.cacheDir, "$id.wav")
            val ok = try { t.synthesizeToFile(text, android.os.Bundle(), f, id) == TextToSpeech.SUCCESS } catch (e: Exception) { false }
            if (!ok) asked.remove(id)
        }
    }

    private fun prepared(id: String?) {
        val ctx = app ?: return
        val text = synchronized(made) { asked.remove(id ?: return) } ?: return
        val f = File(ctx.cacheDir, "$id.wav")
        val wav = try { parseWav(f.readBytes()) } catch (e: Exception) { null }
        f.delete()
        if (wav != null) synchronized(made) { made[text] = wav }
    }

    /** The sentence as sound at [rate] (ready to drop into the countdown stream), or null if not ready yet. */
    fun preparedSpeech(text: String, rate: Int): ShortArray? {
        if (voiceMode() == Prefs.VOICE_OFF) return null
        val w = synchronized(made) { made[text] } ?: return null
        return Countdown.Plan.resample(w.first, w.second, rate)
    }

    /** The full sequence, for the Test Sounds button. */
    fun demo() {
        hushPreview()
        chime()
        val gap = if (warningWords) 6000L else 3000L
        if (warningWords) later(1500) { say("Attention players. Blinds will be going up in one minute.") }
        val wav = knockPcm
        val speech = wav?.let { preparedSpeech(DEMO_TEXT, it.second) }
        if (wav != null) Countdown.start(android.os.SystemClock.elapsedRealtime() + gap + knockHits * 1000L, knockHits, wav.first, wav.second, preview = true, tail = speech)
        if (speech == null) later(gap + knockHits * 1000L) { say(DEMO_TEXT) }
    }

    private fun play(t: AudioTrack?) {
        if (t == null) return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {}
    }

    /** Reads a 16-bit WAV from res/raw: the samples (mono) and their sample rate. */
    private fun loadWav(ctx: Context, resId: Int): Pair<ShortArray, Int>? = try {
        parseWav(ctx.resources.openRawResource(resId).use { it.readBytes() })
    } catch (e: Exception) {
        null
    }

    /** A 16-bit PCM WAV file -> mono samples and sample rate (stereo is mixed down). */
    fun parseWav(b: ByteArray): Pair<ShortArray, Int>? {
        fun le16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        var rate = 0; var channels = 1; var bits = 16
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val size = le32(p + 4)
            if (id == "fmt ") { channels = le16(p + 10); rate = le32(p + 12); bits = le16(p + 22) }
            if (id == "data") {
                if (rate <= 0 || bits != 16 || channels < 1) return null
                val data = p + 8
                val len = minOf(size, b.size - data)
                val n = len / (2 * channels)
                return Pair(ShortArray(n) { f ->
                    var sum = 0
                    for (c in 0 until channels) {
                        val i = data + 2 * (f * channels + c)
                        sum += ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
                    }
                    (sum / channels).toShort()
                }, rate)
            }
            p += 8 + size + (size and 1)
        }
        return null
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
