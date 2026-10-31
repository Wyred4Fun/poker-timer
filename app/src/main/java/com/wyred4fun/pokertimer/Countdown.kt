package com.wyred4fun.pokertimer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock

/**
 * The 5-4-3-2-1 countdown sound, played in exact step with the numbers on screen.
 *
 * How: about 2 seconds before "5" shows, one audio stream starts playing silence (so the
 * TV's sound path is already awake). Android tells us when each piece of audio really comes
 * out of the speakers, so each hit is written into the stream at the spot that will be heard
 * exactly when its number appears: 5 s, 4 s, 3 s, 2 s and 1 s before the level ends.
 * One continuous stream means the gaps between hits can't wobble.
 */
object Countdown {
    /** Each countdown gets a number; a newer one (or cancel) makes the older stream stop. */
    @Volatile private var generation = 0
    /** The level end (elapsedRealtime ms) the running countdown is aimed at; 0 = none. */
    var aimedAt = 0L
        private set

    /** Start a countdown that ends with the level at [endAtElapsed] (SystemClock.elapsedRealtime ms). */
    /** True while a Test sounds / picker preview is playing (not a real countdown). */
    @Volatile var isPreview = false
        private set

    /** The level end whose announcement is inside the stream (0 = none), so it isn't said twice. */
    @Volatile var speechAt = 0L
        private set

    /** Let the stream finish what it is playing (the announcement) without aiming at anything any more. */
    fun releaseAim() { aimedAt = 0L }

    fun start(endAtElapsed: Long, hits: Int, pcm: ShortArray, rate: Int, preview: Boolean = false, tail: ShortArray? = null) {
        val my = ++generation
        aimedAt = endAtElapsed
        isPreview = preview
        speechAt = if (tail != null && !preview) endAtElapsed else 0L
        // the same moment on the clock the audio system uses (System.nanoTime)
        val endNano = System.nanoTime() + (endAtElapsed - SystemClock.elapsedRealtime()) * 1_000_000L
        val targets = LongArray(hits) { endNano - (hits - it) * 1_000_000_000L }
        Thread({ run(my, targets, endNano, pcm, rate, tail) }, "countdown").start()
    }

    fun cancel() {
        generation++
        aimedAt = 0L
        isPreview = false
    }

    private fun run(my: Int, targets: LongArray, endNano: Long, pcm: ShortArray, rate: Int, tail: ShortArray?) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Exception) {}
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, rate / 5 * 2))
                .build()
        } catch (e: Exception) { return }
        try {
            track.play()
            val chunk = rate / 100                        // write 10 ms at a time
            val buf = ShortArray(chunk)
            val starts = LongArray(targets.size) { -1L }  // the frame each hit starts on, once decided
            var tailStart = -1L                           // the frame the announcement starts on (at 0:00)
            val ts = AudioTimestamp()
            var written = 0L
            var haveStamp = false
            while (my == generation) {
                if (!haveStamp || written % (rate / 2) < chunk) haveStamp = track.getTimestamp(ts) || haveStamp
                val nowNano = System.nanoTime()
                // when will the next frame we write be heard?
                val presentNano = if (haveStamp) ts.nanoTime + (written - ts.framePosition) * 1_000_000_000L / rate
                else nowNano + (written - track.playbackHeadPosition.toLong()) * 1_000_000_000L / rate
                // decide each hit's spot a little before it is due, then keep it fixed
                for (k in targets.indices) {
                    if (starts[k] < 0 && targets[k] - presentNano < 400_000_000L) {
                        starts[k] = Plan.hitFrame(targets[k], written, presentNano, rate)
                    }
                }
                if (tail != null && tailStart < 0 && endNano - presentNano < 400_000_000L) {
                    tailStart = Plan.hitFrame(endNano, written, presentNano, rate)
                }
                java.util.Arrays.fill(buf, 0.toShort())
                for (k in targets.indices) Plan.mix(buf, written, starts[k], pcm)
                if (tail != null) Plan.mix(buf, written, tailStart, tail)
                track.write(buf, 0, chunk)                // blocks: keeps us in step with playback
                written += chunk
                val lastHit = if (starts.isEmpty()) 0L else starts.last()
                val hitsDone = starts.isEmpty() || (lastHit >= 0 && written > lastHit + pcm.size + rate / 5)
                val tailDone = tail == null || (tailStart >= 0 && written > tailStart + tail.size + rate / 5)
                if (hitsDone && tailDone) break
            }
        } catch (e: Exception) {
        } finally {
            try { track.stop() } catch (e: Exception) {}
            try { track.release() } catch (e: Exception) {}
        }
    }

    /** The arithmetic, kept separate so it can be tested on its own. */
    object Plan {
        /** The frame that will be heard at [targetNano], given frame [written] is heard at [presentNano]. Never in the past. */
        fun hitFrame(targetNano: Long, written: Long, presentNano: Long, rate: Int): Long {
            val offset = Math.round((targetNano - presentNano).toDouble() * rate / 1e9)
            return written + maxOf(0L, offset)
        }

        /** Add the part of a hit starting at frame [start] that falls in this chunk (chunk starts at frame [written]). */
        /** Change the sample rate of a sound (straight-line in-between values; fine for speech). */
        fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to || src.isEmpty()) return src
            val n = (src.size.toLong() * to / from).toInt()
            return ShortArray(n) { i ->
                val pos = i.toDouble() * from / to
                val a = pos.toInt().coerceAtMost(src.size - 1)
                val b = (a + 1).coerceAtMost(src.size - 1)
                val frac = pos - a
                (src[a] * (1 - frac) + src[b] * frac).toInt().toShort()
            }
        }

        fun mix(buf: ShortArray, written: Long, start: Long, pcm: ShortArray) {
            if (start < 0) return
            val from = maxOf(written, start)
            val to = minOf(written + buf.size, start + pcm.size)
            var f = from
            while (f < to) {
                val i = (f - written).toInt()
                val v = buf[i] + pcm[(f - start).toInt()]
                buf[i] = v.coerceIn(-32767, 32767).toShort()
                f++
            }
        }
    }
}
