package com.wyred4fun.pokertimer

/**
 * The clock itself, with no screen or sound code so it can be tested on its own.
 * "now" is always passed in (milliseconds from a steady clock), never read here.
 */
class TimerEngine(levels: List<Level>, private val listener: Listener) {

    interface Listener {
        /** 1 minute left and another step follows. */
        fun onWarning(current: Level, next: Level)
        /** The last few seconds (3, 2, 1) when another step follows: one knock each. */
        fun onCountdown(secondsLeft: Int)
        /** The clock moved on to a new step by itself. quiet = caught up after being away, so no sounds. */
        fun onLevelChanged(now: Level, quiet: Boolean)
        /** The final step reached 0:00. */
        fun onFinished(quiet: Boolean)
    }

    var levels: List<Level> = levels
        private set
    var testMode = false
    /** How many notification sounds lead into the new blinds (one per second); set from the chosen sound. */
    var knocks = 3
    var idx = 0
        private set
    var running = false
        private set
    var finished = false
        private set

    private var remainingMs: Long = durationMs(0)
    private var endAt = 0L
    private var lastSec = 0

    fun durationMs(i: Int): Long = if (testMode) TEST_SECONDS * 1000L else levels[i].minutes * 60_000L

    /** When the 1-minute heads-up comes (test mode levels are 75 s, so it happens there too). */
    val warnAt: Int get() = 60

    val current: Level get() = levels[idx]
    val next: Level? get() = levels.getOrNull(idx + 1)

    /** Whole seconds shown on the clock (rounded up, so a fresh level shows 20:00). */
    fun secondsLeft(now: Long): Int {
        val ms = if (running) endAt - now else remainingMs
        return ceilSec(ms).coerceAtLeast(0)
    }

    fun msLeft(now: Long): Long = (if (running) endAt - now else remainingMs).coerceAtLeast(0)

    fun start(now: Long) {
        if (running || finished) return
        if (remainingMs <= 0) remainingMs = durationMs(idx)
        endAt = now + remainingMs
        lastSec = ceilSec(remainingMs)
        running = true
    }

    fun pause(now: Long) {
        if (!running) return
        update(now)
        if (!running) return
        remainingMs = (endAt - now).coerceAtLeast(1)
        running = false
    }

    /** Back to the full time for the step we are on. */
    fun resetLevel(now: Long) {
        finished = false
        remainingMs = durationMs(idx)
        if (running) {
            endAt = now + remainingMs
            lastSec = ceilSec(remainingMs)
        }
    }

    /** Jump to a step (Back / Skip). Keeps running if it was running. */
    fun goTo(i: Int, now: Long): Boolean {
        if (i < 0 || i >= levels.size) return false
        idx = i
        resetLevel(now)
        return true
    }

    /** Stop the night: back to step 1, clock stopped. */
    fun stopAll() {
        running = false
        finished = false
        idx = 0
        remainingMs = durationMs(0)
    }

    /**
     * New lineup from Setup. Stays on the same step; removedBefore = rows deleted
     * in front of the current step, so the night doesn't shift.
     */
    fun setLevels(newLevels: List<Level>, now: Long, removedBefore: Int = 0) {
        update(now) // let a step that ran out move on first
        val left = msLeft(now)
        levels = newLevels
        idx = (idx - removedBefore).coerceAtLeast(0)
        if (finished && idx < levels.size - 1) {
            // a level was added after the finished final one: carry on with it
            finished = false
            idx += 1
            remainingMs = durationMs(idx)
        } else if (idx >= levels.size) {
            idx = levels.size - 1
            remainingMs = durationMs(idx)
            finished = false
        } else {
            if (!finished) {
                remainingMs = left.coerceAtMost(durationMs(idx))
                if (remainingMs <= 0) remainingMs = durationMs(idx)
            }
        }
        if (running) {
            endAt = now + remainingMs
            lastSec = ceilSec(remainingMs)
        }
    }

    /** Put back a saved state (after the app was closed). */
    fun restore(index: Int, msLeftAtSave: Long, msSinceSave: Long, wasRunning: Boolean, wasFinished: Boolean, now: Long) {
        idx = index.coerceIn(0, levels.size - 1)
        finished = wasFinished && idx == levels.size - 1
        remainingMs = msLeftAtSave.coerceIn(0, durationMs(idx))
        running = false
        if (remainingMs <= 0 && !finished) remainingMs = durationMs(idx)
        if (wasRunning && !finished) {
            // the clock kept going while the app was closed: run it forward quietly
            lastSec = ceilSec(remainingMs)
            endAt = now + remainingMs - msSinceSave.coerceAtLeast(0)
            running = true
            update(now)
        }
    }

    /** Call often (several times a second). Fires the warning, the knocks and the level changes. */
    fun update(now: Long) {
        if (!running) return
        while (true) {
            val target = ceilSec(endAt - now).coerceAtLeast(0)
            val quiet = lastSec - target > 3
            while (lastSec > target) {
                lastSec -= 1
                if (!quiet) onSecond(lastSec)
            }
            if (lastSec > 0) break
            val nx = next
            if (nx != null) {
                idx += 1
                endAt += durationMs(idx)
                lastSec = ceilSec(durationMs(idx))
                listener.onLevelChanged(levels[idx], quiet || now - endAt + durationMs(idx) > 3000)
            } else {
                running = false
                finished = true
                remainingMs = 0
                listener.onFinished(quiet)
                return
            }
        }
        remainingMs = (endAt - now).coerceAtLeast(0)
    }

    private fun onSecond(s: Int) {
        val nx = next ?: return
        if (s == warnAt && durationMs(idx) > warnAt * 1000L) listener.onWarning(current, nx)
        if (s in 1..knocks) listener.onCountdown(s)
    }

    companion object {
        const val TEST_SECONDS = 75

        fun ceilSec(ms: Long): Int = (-Math.floorDiv(-ms, 1000L)).toInt()
    }
}
