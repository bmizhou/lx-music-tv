package com.lxmusic.tv.presentation.screen

import kotlin.random.Random

internal data class FlyingPoint(val x: Float, val y: Float)

internal fun flyingMaxFontPx(width: Float, height: Float): Float =
    minOf(width * 0.1f, height * 0.17f).coerceAtLeast(1f)

internal fun flyingScreenPoint(random: Random, width: Float, height: Float): FlyingPoint =
    FlyingPoint(width * (0.04f + random.nextFloat() * 0.92f), height * (0.04f + random.nextFloat() * 0.92f))

internal fun flyingLeadMs(lineStartMs: Long, previousStartMs: Long): Long =
    ((lineStartMs - previousStartMs).coerceAtLeast(1L) * 0.65f).toLong().coerceIn(400L, 3000L)

internal data class FlyingLinePhase(val entering: Float, val leaving: Float)

internal const val FLYING_EXIT_MS = 1800L

internal fun flyingLinePhase(timeMs: Long, lineStartMs: Long, leadMs: Long, exitStartMs: Long): FlyingLinePhase {
    val entering = ((timeMs - lineStartMs + leadMs).toFloat() / leadMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val leaving = if (exitStartMs == Long.MAX_VALUE) 0f else
        ((timeMs - exitStartMs).toFloat() / FLYING_EXIT_MS).coerceIn(0f, 1f)
    return FlyingLinePhase(entering, leaving)
}
