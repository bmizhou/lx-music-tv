package com.lxmusic.tv.presentation.screen

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FlyingLyricMotionTest {
    @Test
    fun `font scales with viewport and stays bounded on narrow or short screens`() {
        val fullHd = flyingMaxFontPx(1920f, 1080f)
        assertEquals(fullHd * 2f, flyingMaxFontPx(3840f, 2160f), 0.001f)
        assertEquals(fullHd * 2f / 3f, flyingMaxFontPx(1280f, 720f), 0.001f)
        assertTrue(flyingMaxFontPx(600f, 1080f) <= 60f)
        assertTrue(flyingMaxFontPx(1920f, 400f) <= 68f)
    }

    @Test
    fun `flight endpoints cover screen interior and all quadrants at different resolutions`() {
        for ((width, height) in listOf(1920f to 1080f, 1280f to 720f, 3840f to 2160f)) {
            val random = Random(42)
            val points = List(100) { flyingScreenPoint(random, width, height) }
            assertTrue(points.any { it.x in width * 0.25f..width * 0.75f &&
                it.y in height * 0.25f..height * 0.75f })
            assertEquals(4, points.map { (it.x < width / 2f) to (it.y < height / 2f) }.toSet().size)
            for (point in points) {
                assertTrue(point.x in 0f..width && point.y in 0f..height)
            }
        }
    }

    @Test
    fun `whole line enters progressively and settles at lyric timestamp`() {
        val start = 10_000L
        val lead = flyingLeadMs(start, 6_000L)
        val first = flyingLinePhase(8_500L, start, lead, 14_000L)
        val later = flyingLinePhase(9_000L, start, lead, 14_000L)
        assertTrue(first.entering > 0f && later.entering > first.entering)
        assertEquals(1f, flyingLinePhase(start, start, lead, 14_000L).entering, 0f)
        assertEquals(0f, flyingLinePhase(5_000L, start, lead, 14_000L).entering, 0f)
        assertEquals(0f, later.leaving, 0f)
    }

    @Test
    fun `short or duplicate timestamps stay finite and final line stays visible`() {
        for (previous in listOf(0L, 9_900L, 10_000L)) {
            val lead = flyingLeadMs(10_000L, previous)
            val phase = flyingLinePhase(10_100L, 10_000L, lead, Long.MAX_VALUE)
            assertTrue(phase.entering.isFinite() && phase.entering in 0f..1f)
            assertEquals(0f, phase.leaving, 0f)
        }
    }

    @Test
    fun `outgoing line gradually leaves while next line enters`() {
        val outgoing = flyingLinePhase(10_500L, 6_000L, 2000L, 10_000L)
        val incoming = flyingLinePhase(10_500L, 12_000L, 3000L, 16_000L)
        assertTrue(outgoing.leaving > 0f && outgoing.leaving < 1f)
        assertTrue(incoming.entering > 0f && incoming.entering < 1f)
        assertEquals(1f, flyingLinePhase(11_800L, 6_000L, 2000L, 10_000L).leaving, 0f)
    }
}
