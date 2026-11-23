package io.github.latencyscope.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.latencyscope.export.LatencyFormat
import io.github.latencyscope.export.PhaseSlice

/**
 * The stacked phase bar, drawn directly rather than built from nested views.
 *
 * Segments are separated by a 2dp gap so adjacent fills never touch, and the
 * ends are rounded. Colour alone never carries the meaning: whoever shows
 * this view also shows [legendText], because three of the five hues sit below
 * 3:1 contrast on a light surface.
 */
public class PhaseBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.latencyscope_track)
    }
    private val rect = RectF()
    private val gapPx = GAP_DP * resources.displayMetrics.density
    private val radiusPx = RADIUS_DP * resources.displayMetrics.density

    private var slices: List<PhaseSlice> = emptyList()
    private var total: Long = 0

    private val seriesColors: List<Int> = listOf(
        R.color.latencyscope_series_1,
        R.color.latencyscope_series_2,
        R.color.latencyscope_series_3,
        R.color.latencyscope_series_4,
        R.color.latencyscope_series_5,
    ).map { ContextCompat.getColor(context, it) }

    public fun setSlices(slices: List<PhaseSlice>) {
        this.slices = slices.filter { it.ms > 0 }
        this.total = this.slices.sumOf { it.ms }
        contentDescription = legendText()
        invalidate()
    }

    /** The same values as the bar, in words — the bar is never the only copy. */
    public fun legendText(): String = slices.joinToString("   ") { slice ->
        slice.label + " " + LatencyFormat.duration(slice.ms)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fullWidth = width.toFloat()
        val fullHeight = height.toFloat()
        if (fullWidth <= 0f || fullHeight <= 0f) return

        rect.set(0f, 0f, fullWidth, fullHeight)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, trackPaint)
        if (total <= 0L || slices.isEmpty()) return

        val gaps = gapPx * (slices.size - 1).coerceAtLeast(0)
        val drawable = (fullWidth - gaps).coerceAtLeast(0f)
        var x = 0f

        slices.forEachIndexed { index, slice ->
            val segment = drawable * (slice.ms.toFloat() / total)
            fill.color = seriesColors[slice.slot % seriesColors.size]
            rect.set(x, 0f, x + segment, fullHeight)
            // Round only the outer ends, so the bar reads as one bar.
            val roundStart = index == 0
            val roundEnd = index == slices.lastIndex
            if (roundStart || roundEnd) {
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fill)
                val patch = RectF(
                    if (roundStart) x + radiusPx else x,
                    0f,
                    if (roundEnd) x + segment - radiusPx else x + segment,
                    fullHeight,
                )
                if (patch.right > patch.left) canvas.drawRect(patch, fill)
            } else {
                canvas.drawRect(rect, fill)
            }
            x += segment + gapPx
        }
    }

    private companion object {
        const val GAP_DP = 2f
        const val RADIUS_DP = 3f
    }
}
