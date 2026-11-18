package com.lxmusic.tv.presentation.screen

import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.Canvas as AndroidCanvas
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.graphics.withSave
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil

private class TiltedLyricSpan(private val sizeFactor: Float, private val angle: Float) : ReplacementSpan() {
    private fun styledPaint(paint: Paint) = Paint(paint).apply { textSize *= sizeFactor }

    private fun bounds(paint: Paint, text: CharSequence, start: Int, end: Int): Pair<Float, Float> {
        val width = paint.measureText(text, start, end)
        val height = paint.descent() - paint.ascent()
        val radians = Math.toRadians(angle.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        return (width * c + height * s) to (height * c + width * s)
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val (width, height) = bounds(styledPaint(paint), text, start, end)
        fm?.apply {
            ascent = -ceil(height).toInt()
            top = ascent
            descent = 0
            bottom = 0
            leading = 0
        }
        return ceil(width).toInt()
    }

    override fun draw(
        canvas: AndroidCanvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val styled = styledPaint(paint)
        val (width, height) = bounds(styled, text, start, end)
        canvas.withSave {
            translate(x + ceil(width).toFloat() / 2f, y - height / 2f)
            rotate(angle)
            drawText(text, start, end, -styled.measureText(text, start, end) / 2f,
                -(styled.ascent() + styled.descent()) / 2f, styled)
        }
    }
}

private fun scatteredLyricText(text: String): CharSequence {
    val styled = SpannableString(text)
    val random = Random(text.hashCode())
    // Keep alphabetic words together so connected scripts are not split into isolated letters.
    val tokens = Regex("[\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}]|[\\p{L}\\p{M}\\p{N}]+|\\S")
    tokens.findAll(text).forEach { token ->
        styled.setSpan(
            TiltedLyricSpan(0.78f + random.nextFloat() * 0.5f, (random.nextFloat() - 0.5f) * 28f),
            token.range.first, token.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return styled
}

private data class FlyingLineLayout(
    val original: StaticLayout,
    val translated: StaticLayout?,
    val gap: Float,
    val height: Float,
    val origin: FlyingPoint,
    val destination: FlyingPoint,
    val leadMs: Long,
    val exitStartMs: Long
)

@Composable
internal fun FullScreenFlyingLyrics(
    lyric: String?,
    translation: String?,
    positionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean
) {
    val lyrics = remember(lyric, translation) { parseLrcWithTranslation(lyric, translation) }
    val time = remember(lyric) { mutableLongStateOf(positionMs) }
    // Use playback time, not Animatable: some TVs globally disable animator duration.
    LaunchedEffect(positionMs, isPlaying, lyric) {
        time.longValue = positionMs
        if (isPlaying) {
            val anchorMs = SystemClock.uptimeMillis()
            while (true) {
                withFrameNanos {
                    time.longValue = positionMs + (SystemClock.uptimeMillis() - anchorMs).coerceAtMost(1200L)
                }
            }
        }
    }
    if (lyrics.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = Color.White.copy(alpha = 0.5f))
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val maxFontPx = flyingMaxFontPx(width, height)
        val blockWidth = (width * 0.8f).toInt().coerceAtLeast(1)
        val layouts = remember(lyrics, width, height, totalDurationMs) {
            fun textLayout(text: CharSequence, translated: Boolean, fontPx: Float): StaticLayout {
                val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = fontPx * if (translated) 0.5f else 1f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                return StaticLayout.Builder.obtain(text, 0, text.length, paint, blockWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setLineSpacing(fontPx * 0.12f, 1f)
                    .build()
            }
            lyrics.mapIndexed { index, line ->
                val originalText = scatteredLyricText(line.text)
                val translatedText = line.translation?.takeIf { it.isNotBlank() }?.let { scatteredLyricText(it) }
                fun layoutAt(fontPx: Float): Triple<StaticLayout, StaticLayout?, Float> {
                    val original = textLayout(originalText, false, fontPx)
                    val translated = translatedText?.let { textLayout(it, true, fontPx) }
                    return Triple(original, translated, if (translated == null) 0f else fontPx * 0.25f)
                }
                // Fit measured, tilted text and translation together; keep long lyrics multiline, not ellipsized.
                var lower = 1f
                var upper = maxFontPx
                repeat(9) {
                    val candidate = (lower + upper) / 2f
                    val (original, translated, gap) = layoutAt(candidate)
                    val fitsWidth = listOfNotNull(original, translated).all { layout ->
                        (0 until layout.lineCount).all { layout.getLineWidth(it) <= blockWidth + 1f }
                    }
                    if (fitsWidth && original.height + gap + (translated?.height ?: 0) <= height * 0.6f) {
                        lower = candidate
                    } else {
                        upper = candidate
                    }
                }
                val (original, translated, gap) = layoutAt(lower)
                val random = Random(index * 1009 + line.text.hashCode())
                val next = lyrics.getOrNull(index + 1)
                val nextLead = next?.let { flyingLeadMs(it.timeMs, line.timeMs) } ?: 0L
                FlyingLineLayout(
                    original = original,
                    translated = translated,
                    gap = gap,
                    height = original.height + gap + (translated?.height ?: 0),
                    origin = flyingScreenPoint(random, width, height),
                    destination = flyingScreenPoint(random, width, height),
                    leadMs = flyingLeadMs(line.timeMs, lyrics.getOrNull(index - 1)?.timeMs ?: 0L),
                    exitStartMs = next?.let {
                        (it.timeMs - (nextLead * 0.4f).toLong()).coerceAtLeast(line.timeMs)
                    } ?: if (totalDurationMs > line.timeMs) {
                        (totalDurationMs - FLYING_EXIT_MS).coerceAtLeast(line.timeMs)
                    } else Long.MAX_VALUE
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val now = time.longValue
            val canvas = drawContext.canvas.nativeCanvas
            layouts.forEachIndexed { index, layout ->
                val phase = flyingLinePhase(now, lyrics[index].timeMs, layout.leadMs, layout.exitStartMs)
                if (phase.entering <= 0f || phase.leaving >= 1f) return@forEachIndexed
                val enter = phase.entering * phase.entering * (3f - 2f * phase.entering)
                val leave = phase.leaving * phase.leaving * (3f - 2f * phase.leaving)
                val centerX = width / 2f
                val centerY = height / 2f
                val x = layout.origin.x + (centerX - layout.origin.x) * enter +
                    (layout.destination.x - centerX) * leave
                val y = layout.origin.y + (centerY - layout.origin.y) * enter +
                    (layout.destination.y - centerY) * leave
                val fit = (height * 0.65f / layout.height.coerceAtLeast(1f)).coerceAtMost(1f)
                val scale = (0.25f + enter * 0.75f) * (1f - leave * 0.75f) * fit
                val alpha = (0.5f + enter * 0.5f) * (1f - phase.leaving)
                layout.original.paint.alpha = (255 * alpha).toInt()
                layout.translated?.paint?.alpha = (190 * alpha).toInt()
                // One transform for the entire original + translation block.
                canvas.withSave {
                    translate(x, y)
                    scale(scale, scale)
                    translate(-blockWidth / 2f, -layout.height / 2f)
                    layout.original.draw(this)
                    layout.translated?.let {
                        translate(0f, layout.original.height + layout.gap)
                        it.draw(this)
                    }
                }
            }
        }
    }
}
