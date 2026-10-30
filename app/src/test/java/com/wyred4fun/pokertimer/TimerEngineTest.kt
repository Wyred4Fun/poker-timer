package com.wyred4fun.pokertimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerEngineTest {

    private class Rec : TimerEngine.Listener {
        val log = ArrayList<String>()
        override fun onWarning(current: Level, next: Level) { log.add("warn ${next.blindsText}${if (next.isBreak) " break" else ""}") }
        override fun onCountdown(secondsLeft: Int) { log.add("knock $secondsLeft") }
        override fun onLevelChanged(now: Level, quiet: Boolean) { log.add("now ${if (now.isBreak) "break" else now.blindsText}${if (quiet) " quiet" else ""}") }
        override fun onFinished(quiet: Boolean) { log.add("finished${if (quiet) " quiet" else ""}") }
    }

    /** Drive the clock in 100 ms steps like the app does. */
    private fun run(e: TimerEngine, from: Long, to: Long) {
        var t = from
        while (t <= to) { e.update(t); t += 100 }
    }

    @Test fun freshLevelShowsFullTime() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        assertEquals(20 * 60, e.secondsLeft(0))
        e.start(0)
        assertEquals(20 * 60, e.secondsLeft(0))
        assertEquals(20 * 60, e.secondsLeft(500))
        assertEquals(20 * 60 - 1, e.secondsLeft(1000))
    }

    @Test fun warningKnocksAndChangeInOrder() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.start(0)
        run(e, 0, 20 * 60_000L + 500)
        assertEquals(listOf("warn 2 – 4", "knock 3", "knock 2", "knock 1", "now 2 – 4"), r.log)
        assertEquals(1, e.idx)
        assertEquals(20 * 60, e.secondsLeft(20 * 60_000L))
    }

    @Test fun warningTimesAreExact() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.start(0)
        run(e, 0, 18 * 60_000L + 59_900)   // 1:00:100 left -> clock shows 1:01
        assertTrue(r.log.isEmpty())
        e.update(19 * 60_000L)              // exactly 1:00 left
        assertEquals(listOf("warn 2 – 4"), r.log)
    }

    @Test fun breakAnnouncedAndBreakEndsWarning() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.goTo(3, 0) // 4-8, next is a break
        e.start(0)
        run(e, 0, 20 * 60_000L + 15 * 60_000L + 500)
        assertEquals(
            listOf("warn 0 – 0 break", "knock 3", "knock 2", "knock 1", "now break",
                "warn 5 – 10", "knock 3", "knock 2", "knock 1", "now 5 – 10"),
            r.log
        )
    }

    @Test fun pauseStopsTheClock() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.start(0)
        e.pause(10_000)
        assertEquals(20 * 60 - 10, e.secondsLeft(10_000))
        assertEquals(20 * 60 - 10, e.secondsLeft(500_000))
        e.start(500_000)
        assertEquals(20 * 60 - 20, e.secondsLeft(510_000))
    }

    @Test fun resetLevelGoesBackToFullTime() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.start(0)
        run(e, 0, 300_000)
        e.resetLevel(300_000)
        assertEquals(20 * 60, e.secondsLeft(300_000))
        assertTrue(e.running)
        e.pause(301_000); e.resetLevel(301_000)
        assertEquals(20 * 60, e.secondsLeft(999_999))
        assertFalse(e.running)
    }

    @Test fun backAndSkip() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        assertFalse(e.goTo(-1, 0))
        assertTrue(e.goTo(1, 0))
        assertEquals("2 – 4", e.current.blindsText)
        assertTrue(e.goTo(14, 0))
        assertEquals(null, e.next)
        assertFalse(e.goTo(15, 0))
    }

    @Test fun stopGoesBackToLevelOne() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.goTo(6, 0); e.start(0)
        e.stopAll()
        assertEquals(0, e.idx)
        assertFalse(e.running)
        assertEquals(20 * 60, e.secondsLeft(123_456))
    }

    @Test fun finalLevelFinishes() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.goTo(14, 0); e.start(0)
        run(e, 0, 20 * 60_000L + 1000)
        assertEquals(listOf("finished"), r.log) // no warning or knocks when nothing follows
        assertTrue(e.finished)
        assertFalse(e.running)
        assertEquals(0, e.secondsLeft(30 * 60_000L))
    }

    @Test fun wholeNightEndsAt1235() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.start(0)
        run(e, 0, 275 * 60_000L - 200)
        assertFalse(e.finished)
        run(e, 275 * 60_000L - 100, 275 * 60_000L + 200)
        assertTrue(e.finished)
        assertEquals(14, r.log.count { it.startsWith("now") })
        assertEquals(14, r.log.count { it.startsWith("warn") })
        assertEquals(14 * 3, r.log.count { it.startsWith("knock") })
    }

    @Test fun reopeningAfterAwayCatchesUpQuietly() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        // saved with 2 minutes left on level 1; app was closed for 45 minutes
        e.restore(0, 120_000, 45 * 60_000L, wasRunning = true, wasFinished = false, now = 1000)
        // 2 min finishes level 1, 20 min level 2, 20 min level 3, then 3 min into 4-8
        assertEquals(3, e.idx)
        assertEquals(17 * 60, e.secondsLeft(1000))
        assertTrue(r.log.all { it.endsWith("quiet") })
        assertTrue(e.running)
    }

    @Test fun reopeningPausedKeepsTime() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.restore(5, 300_000, 9_999_999, wasRunning = false, wasFinished = false, now = 0)
        assertEquals(5, e.idx)
        assertEquals(300, e.secondsLeft(0))
        assertFalse(e.running)
    }

    @Test fun testModeLevelsAre75Seconds() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.testMode = true
        e.resetLevel(0)
        e.start(0)
        run(e, 0, 75_500)
        assertEquals(listOf("warn 2 – 4", "knock 3", "knock 2", "knock 1", "now 2 – 4"), r.log)
    }

    @Test fun changedLineupKeepsPlace() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.goTo(5, 0); e.start(0)
        e.setLevels(Lineup.DEFAULT.take(3), 60_000)
        assertEquals(2, e.idx)
        val shorter = Lineup.DEFAULT.toMutableList().also { it[2] = Level(3, 6, 5) }
        val e2 = TimerEngine(Lineup.DEFAULT, Rec())
        e2.goTo(2, 0); e2.start(0)
        e2.setLevels(shorter, 60_000)
        assertEquals(5 * 60, e2.secondsLeft(60_000))
    }

    @Test fun lineupSavesAndLoads() {
        val txt = Lineup.encode(Lineup.DEFAULT)
        assertEquals(Lineup.DEFAULT, Lineup.decode(txt))
        assertEquals(null, Lineup.decode("garbage"))
        assertEquals(null, Lineup.decode("B,15"))
    }

    @Test fun lineupChangeAfterStepRanOutMovesOn() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.start(0)
        e.update(20 * 60_000L - 30_000)          // 30 s left on 1-2
        e.setLevels(Lineup.DEFAULT, 20 * 60_000L + 30_000)  // came back a minute later
        assertEquals(1, e.idx)
        assertEquals(20 * 60 - 30, e.secondsLeft(20 * 60_000L + 30_000))
    }

    @Test fun deletingRowBeforeCurrentKeepsSameLevel() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.goTo(5, 0); e.start(0)                  // 5-10
        val noBreak = Lineup.DEFAULT.toMutableList().also { it.removeAt(4) }
        e.setLevels(noBreak, 60_000, removedBefore = 1)
        assertEquals("5 – 10", e.current.blindsText)
        assertEquals(19 * 60, e.secondsLeft(60_000))
    }

    @Test fun addingLevelAfterFinishCarriesOn() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.goTo(14, 0); e.start(0)
        e.update(20 * 60_000L + 1000)
        assertTrue(e.finished)
        e.setLevels(Lineup.DEFAULT + Level(25, 50, 20), 21 * 60_000L)
        assertFalse(e.finished)
        assertEquals("25 – 50", e.current.blindsText)
        assertEquals(20 * 60, e.secondsLeft(21 * 60_000L))
        e.start(21 * 60_000L)
        assertTrue(e.running)
    }
}
