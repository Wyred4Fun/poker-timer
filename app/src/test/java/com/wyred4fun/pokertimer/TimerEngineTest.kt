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
        override fun onBreakEnding(minutesLeft: Int) { log.add("break over in $minutesLeft") }
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
        assertEquals(listOf("warn 2 – 4", "knock 5", "knock 4", "knock 3", "knock 2", "knock 1", "now 2 – 4"), r.log)
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
        e.goTo(2, 0) // 3-6, next is a break
        e.start(0)
        run(e, 0, 20 * 60_000L + 15 * 60_000L + 500)
        assertEquals(
            listOf("warn 0 – 0 break", "knock 5", "knock 4", "knock 3", "knock 2", "knock 1", "now break",
                "break over in 3", "warn 4 – 8", "knock 5", "knock 4", "knock 3", "knock 2", "knock 1", "now 4 – 8"),
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
        e.carryOn = false
        assertTrue(e.goTo(14, 0))
        assertEquals(null, e.next)
        assertFalse(e.goTo(15, 0))
        e.carryOn = true
        assertTrue(e.goTo(15, 0))          // Skip past the last level: the next keep-going round
        assertEquals("25 – 50", e.current.blindsText)
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
        e.carryOn = false
        e.goTo(14, 0); e.start(0)
        run(e, 0, 15 * 60_000L + 1000)
        assertEquals(listOf("finished"), r.log) // no warning or knocks when nothing follows
        assertTrue(e.finished)
        assertFalse(e.running)
        assertEquals(0, e.secondsLeft(30 * 60_000L))
    }

    @Test fun wholeLineupTakes250Minutes() {
        // 6 levels of 20, 6 levels of 15, breaks of 15, 15 and 10
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.carryOn = false
        e.start(0)
        run(e, 0, 250 * 60_000L - 200)
        assertFalse(e.finished)
        run(e, 250 * 60_000L - 100, 250 * 60_000L + 200)
        assertTrue(e.finished)
        assertEquals(14, r.log.count { it.startsWith("now") })
        assertEquals(14, r.log.count { it.startsWith("warn") })   // every level and break that has something after it
        assertEquals(3, r.log.count { it.startsWith("break over") })
        assertEquals(14 * 5, r.log.count { it.startsWith("knock") })
    }

    @Test fun newDefaultLineup() {
        assertEquals("1,2,20;2,4,20;3,6,20;B,15;4,8,20;5,10,20;B,15;6,12,20;7,14,15;8,16,15;B,10;9,18,15;10,20,15;15,30,15;20,40,15", Lineup.encode(Lineup.DEFAULT))
        assertEquals("12 levels, 3 breaks  \u00B7  1\u20132 to 20\u201340", Lineup.summary(Lineup.DEFAULT))
    }

    @Test fun afterTheLastLevelBlindsKeepGoingEvery15Minutes() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.goTo(14, 0); e.start(0)               // 20-40, the last row, 15 minutes
        assertFalse(e.isExtra())
        run(e, 0, 15 * 60_000L + 500)
        assertEquals(listOf("warn 25 – 50", "knock 5", "knock 4", "knock 3", "knock 2", "knock 1", "now 25 – 50"), r.log)
        assertTrue(e.isExtra())
        assertFalse(e.finished)
        assertEquals(15 * 60, e.secondsLeft(15 * 60_000L))
        run(e, 15 * 60_000L + 600, 30 * 60_000L + 500)
        assertEquals("30 – 60", e.current.blindsText)
        // Setup opened and closed: still on the same round
        e.setLevels(Lineup.DEFAULT, 31 * 60_000L)
        assertEquals("30 – 60", e.current.blindsText)
        assertEquals(14 * 60, e.secondsLeft(31 * 60_000L))
        // Reset tournament trims the extra rounds off again
        e.stopAll()
        assertEquals(Lineup.DEFAULT, e.levels)
    }

    @Test fun reopeningOnAKeepGoingRound() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.restore(16, 300_000, 0, wasRunning = false, wasFinished = false, now = 0)
        assertEquals(16, e.idx)
        assertEquals("30 – 60", e.current.blindsText)
    }

    @Test fun keepGoingStepFollowsTheLastIncrease() {
        assertEquals(Level(20, 40, 10), TimerEngine.extraAfter(listOf(Level(10, 20, 20), Level(0, 0, 15, isBreak = true), Level(15, 30, 10))))
        assertEquals(Level(2, 4, 20), TimerEngine.extraAfter(listOf(Level(1, 2, 20))))
        val up = Countdown.Plan.resample(ShortArray(4) { (it * 100).toShort() }, 22050, 44100)
        assertEquals(8, up.size)
        assertEquals(listOf<Short>(0, 50, 100, 150), up.take(4))
    }

    @Test fun reopeningAfterAwayCatchesUpQuietly() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        // saved with 2 minutes left on level 1; app was closed for 45 minutes
        e.restore(0, 120_000, 45 * 60_000L, wasRunning = true, wasFinished = false, now = 1000)
        // 2 min finishes level 1, 20 min level 2, 20 min level 3, then 3 min into the break
        assertEquals(3, e.idx)
        assertEquals(12 * 60, e.secondsLeft(1000))
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
        assertEquals(75, e.secondsLeft(0))
        run(e, 0, 75_500)
        assertEquals(listOf("warn 2 – 4", "knock 5", "knock 4", "knock 3", "knock 2", "knock 1", "now 2 – 4"), r.log)
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

    @Test fun movingTheCurrentRowKeepsTheClockOnIt() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.goTo(2, 0); e.start(0)                  // 3-6, the break is below it
        val swapped = Lineup.DEFAULT.toMutableList().also { val a = it[2]; it[2] = it[3]; it[3] = a }
        e.setLevels(swapped, 60_000, removedBefore = 2 - 3)   // 3-6 moved down one
        assertEquals(3, e.idx)
        assertEquals("3 – 6", e.current.blindsText)
        assertEquals(19 * 60, e.secondsLeft(60_000))
        // the break moved up past the level we are on: still on 3-6, now one row lower
        val e2 = TimerEngine(Lineup.DEFAULT, Rec())
        e2.goTo(2, 0); e2.start(0)
        e2.setLevels(swapped, 60_000, removedBefore = 2 - 3)
        assertEquals("3 – 6", e2.current.blindsText)
    }

    @Test fun plusMinusTimeButtons() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.start(0)
        run(e, 0, 5 * 60_000L)                       // 15:00 left
        assertTrue(e.adjust(-60_000, 5 * 60_000L))
        assertEquals(14 * 60, e.secondsLeft(5 * 60_000L))
        assertTrue(e.adjust(30_000, 5 * 60_000L))
        assertEquals(14 * 60 + 30, e.secondsLeft(5 * 60_000L))
        // never more than the full 20 minutes
        e.adjust(60_000, 5 * 60_000L); e.adjust(60_000, 5 * 60_000L); e.adjust(60_000, 5 * 60_000L); e.adjust(60_000, 5 * 60_000L)
        e.adjust(60_000, 5 * 60_000L); e.adjust(60_000, 5 * 60_000L)
        assertEquals(20 * 60, e.secondsLeft(5 * 60_000L))
        assertFalse(e.adjust(30_000, 5 * 60_000L))
        assertTrue(r.log.isEmpty())
        // paused: the clock moves but stays stopped
        e.pause(5 * 60_000L)
        e.adjust(-30_000, 6 * 60_000L)
        assertEquals(19 * 60 + 30, e.secondsLeft(9 * 60_000L))
        assertFalse(e.running)
    }

    @Test fun takingTimeOffPastOneMinuteStillWarnsAndCountsDown() {
        val r = Rec()
        val e = TimerEngine(Lineup.DEFAULT, r)
        e.start(0)
        run(e, 0, 19 * 60_000L - 30_000)             // 1:30 left
        e.adjust(-60_000, 19 * 60_000L - 30_000)      // 0:30 left
        assertEquals(listOf("warn 2 – 4"), r.log)
        run(e, 19 * 60_000L - 29_900, 19 * 60_000L + 500)
        assertEquals(listOf("warn 2 – 4", "knock 5", "knock 4", "knock 3", "knock 2", "knock 1", "now 2 – 4"), r.log)
        // never below 1 second
        val e2 = TimerEngine(Lineup.DEFAULT, Rec())
        e2.start(0); e2.update(20 * 60_000L - 20_000)
        e2.adjust(-60_000, 20 * 60_000L - 20_000)
        assertEquals(1, e2.secondsLeft(20 * 60_000L - 20_000))
        // putting time back on in the last minute: the heads-up and countdown come again
        val r3 = Rec(); val e3 = TimerEngine(Lineup.DEFAULT, r3)
        e3.start(0); run(e3, 0, 19 * 60_000L + 20_000)   // 0:40 left, warned
        e3.adjust(60_000, 19 * 60_000L + 20_000)          // 1:40 left
        run(e3, 19 * 60_000L + 20_100, 20 * 60_000L + 60_500)
        assertEquals(2, r3.log.count { it.startsWith("warn") })
        assertEquals(5, r3.log.count { it.startsWith("knock") })
    }

    @Test fun addingLevelAfterFinishCarriesOn() {
        val e = TimerEngine(Lineup.DEFAULT, Rec())
        e.carryOn = false
        e.goTo(14, 0); e.start(0)
        e.update(15 * 60_000L + 1000)
        assertTrue(e.finished)
        e.setLevels(Lineup.DEFAULT + Level(25, 50, 20), 21 * 60_000L)
        assertFalse(e.finished)
        assertEquals("25 – 50", e.current.blindsText)
        assertEquals(20 * 60, e.secondsLeft(21 * 60_000L))
        e.start(21 * 60_000L)
        assertTrue(e.running)
    }

    @Test fun soundCountFollowsChosenSound() {
        for (n in listOf(1, 2, 5)) {
            val r = Rec()
            val e = TimerEngine(Lineup.DEFAULT, r)
            e.knocks = n
            e.start(0)
            run(e, 0, 20 * 60_000L + 500)
            val expect = listOf("warn 2 – 4") + (n downTo 1).map { "knock $it" } + listOf("now 2 – 4")
            assertEquals(expect, r.log)
        }
    }

    @Test fun oldLineupBigBlindBecomesTwiceSmall() {
        val l = Lineup.decode("3,5,20;B,15;10,25,15")!!
        assertEquals(Level(3, 6, 20), l[0])
        assertEquals(Level(10, 20, 15), l[2])
    }

    @Test fun countdownHitsLandOnTheirFrames() {
        val rate = 44100
        // frame 1000 will be heard at t = 2.000 s; a hit wanted at t = 2.5 s goes 0.5 s later
        assertEquals(1000L + 22050L, Countdown.Plan.hitFrame(2_500_000_000L, 1000L, 2_000_000_000L, rate))
        // a hit already due goes on the very next frame, never in the past
        assertEquals(1000L, Countdown.Plan.hitFrame(1_900_000_000L, 1000L, 2_000_000_000L, rate))
        // 5 hits one second apart are exactly 44100 frames apart
        val f = (0 until 5).map { Countdown.Plan.hitFrame(3_000_000_000L + it * 1_000_000_000L, 0L, 1_000_000_000L, rate) }
        assertEquals(listOf(88200L, 132300L, 176400L, 220500L, 264600L), f)
        // mixing places the sound exactly, across chunk edges
        val pcm = ShortArray(10) { (it + 1).toShort() }
        val a = ShortArray(441); Countdown.Plan.mix(a, 0L, 436L, pcm)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), a.takeLast(5))
        val b = ShortArray(441); Countdown.Plan.mix(b, 441L, 436L, pcm)
        assertEquals(listOf<Short>(6, 7, 8, 9, 10, 0), b.take(6))
    }
}
