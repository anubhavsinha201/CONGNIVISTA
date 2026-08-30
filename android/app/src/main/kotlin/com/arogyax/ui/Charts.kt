package com.arogyax.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The ECG trace, drawn directly onto a Canvas.
 *
 * CLAUDE.md's own reasoning: "Charting packages cannot hold 250 Hz." A 30 s
 * window is 7500 samples against a few hundred pixels of width, so the view
 * min/max-decimates into one vertical segment per pixel column. That keeps every
 * R peak visible — averaging would flatten exactly the spikes the whole product
 * is about — while drawing a bounded number of segments regardless of window
 * length.
 *
 * Autoscaled, and deliberately unlabelled on the y axis: samples are arbitrary
 * ADU, not millivolts, and nothing here is calibrated against a reference. A mV
 * gridline would be a claim this project has not earned.
 */
class EcgTraceView(context: Context) : View(context) {

    private var samples: DoubleArray = DoubleArray(0)
    private var peaks: IntArray = IntArray(0)

    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gov.NAVY
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply {
        color = Color.parseColor("#E3E9F1")
        strokeWidth = context.dp(1).toFloat()
    }
    private val beatMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gov.SAFFRON
        strokeWidth = context.dp(1).toFloat()
    }
    private val path = Path()

    fun setTrace(data: DoubleArray, rPeaks: IntArray = IntArray(0)) {
        samples = data
        peaks = rPeaks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // One-second gridlines. Spatial reference only, never a voltage claim.
        val seconds = 30
        for (i in 1 until seconds) {
            val x = w * i / seconds
            canvas.drawLine(x, 0f, x, h, grid)
        }
        canvas.drawLine(0f, h / 2f, w, h / 2f, grid)

        if (samples.size < 2 || w <= 0f) return

        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in samples) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = max(abs(hi - lo), 1e-9)
        val pad = context.dp(6)
        val usable = h - 2 * pad

        fun y(v: Double) = (pad + (1.0 - (v - lo) / span) * usable).toFloat()

        // Min/max decimation: one column of pixels gets the true extremes of
        // every sample that falls in it, so an R peak one sample wide survives.
        val cols = w.toInt().coerceAtLeast(1)
        val per = samples.size.toDouble() / cols

        path.reset()
        for (c in 0 until cols) {
            val start = (c * per).toInt()
            val end = ((c + 1) * per).toInt().coerceAtMost(samples.size)
            if (start >= end) continue
            var mn = samples[start]
            var mx = samples[start]
            for (i in start until end) {
                val v = samples[i]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val x = c.toFloat()
            if (c == 0) path.moveTo(x, y(mx)) else path.lineTo(x, y(mx))
            path.lineTo(x, y(mn))
        }
        canvas.drawPath(path, trace)

        // Detected beats, so "31 beats" on the result screen is checkable
        // against the trace the worker is looking at.
        for (p in peaks) {
            if (p < 0 || p >= samples.size) continue
            val x = (p.toDouble() / samples.size * w).toFloat()
            canvas.drawLine(x, h - context.dp(10), x, h, beatMark)
        }
    }
}

/** Horizontal proportion bar, for quality factors and coverage figures. */
class MeterView(context: Context) : View(context) {
    private var fraction = 0.0
    private var barColor = Gov.NAVY
    private val track = Paint().apply { color = Gov.SUNKEN }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(fraction: Double, color: Int) {
        this.fraction = fraction.coerceIn(0.0, 1.0)
        this.barColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(2).toFloat()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        fill.color = barColor
        val w = (width * fraction).toFloat()
        if (w > 0f) canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fill)
    }
}

/**
 * A patient's tier history as a row of blocks, oldest to newest.
 *
 * The spec's section 6 timeline. Blocks rather than a line chart because tiers
 * are ordinal categories, not a continuous quantity — joining them with a line
 * would imply a rate of change that does not exist.
 */
class TierStripView(context: Context) : View(context) {
    private var colors: List<Pair<Int, Int>> = emptyList() // ink, fill
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(entries: List<Pair<Int, Int>>) {
        colors = entries
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (colors.isEmpty()) return
        val gap = context.dp(3).toFloat()
        val n = colors.size
        val cell = (width - gap * (n - 1)) / n
        val r = context.dp(2).toFloat()
        colors.forEachIndexed { i, (ink, fillColor) ->
            val left = i * (cell + gap)
            box.color = fillColor
            canvas.drawRoundRect(left, 0f, left + cell, height.toFloat(), r, r, box)
            box.color = ink
            val inset = context.dp(3).toFloat()
            canvas.drawRoundRect(
                left + inset, height - context.dp(5).toFloat(),
                left + cell - inset, height.toFloat() - inset / 2,
                r, r, box,
            )
        }
    }
}

/** A quality factor row: name, meter, percentage. */
fun Context.meterRow(label: String, fraction: Double, color: Int): LinearLayout = column {
    add(
        row {
            add(text(label, 14f, Gov.INK_MUTED), weight = 1f)
            add(
                text("${(fraction * 100).roundToInt()}%", 14f, color, bold = true),
                matchWidth = false,
            )
        },
        bottom = 5,
    )
    add(
        MeterView(this@meterRow).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8),
            )
            set(fraction, color)
        },
        bottom = 12,
    )
}

/**
 * A rolling ECG trace for a capture in progress.
 *
 * Keeps a fixed ring buffer of the most recent [seconds] and redraws as frames
 * arrive at 10 Hz. Separate from [EcgTraceView] on purpose: that one decimates a
 * whole finished 30 s window, this one scrolls a short live tail, and merging the
 * two would give a view that does neither well.
 *
 * Autoscales to the visible tail rather than to all-time extremes, so an early
 * motion spike does not flatten the rest of the recording into a line.
 */
class LiveTraceView(context: Context) : View(context) {

    private val seconds = 6
    private val fs = 250
    private val ring = DoubleArray(seconds * fs)
    private var head = 0
    private var count = 0

    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gov.NAVY
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val grid = Paint().apply {
        color = Color.parseColor("#E3E9F1")
        strokeWidth = context.dp(1).toFloat()
    }
    private val path = Path()

    fun clear() {
        head = 0
        count = 0
        invalidate()
    }

    /** Appends one frame's samples. Called at 10 Hz while streaming. */
    fun append(samples: DoubleArray) {
        for (v in samples) {
            ring[head] = v
            head = (head + 1) % ring.size
            if (count < ring.size) count++
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 1 until seconds) {
            val x = w * i / seconds
            canvas.drawLine(x, 0f, x, h, grid)
        }
        canvas.drawLine(0f, h / 2f, w, h / 2f, grid)

        if (count < 2) return

        // Oldest-first ordering of the ring.
        val start = if (count < ring.size) 0 else head
        fun at(i: Int) = ring[(start + i) % ring.size]

        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until count) {
            val v = at(i)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = max(abs(hi - lo), 1e-9)
        val pad = context.dp(6)
        val usable = h - 2 * pad
        fun y(v: Double) = (pad + (1.0 - (v - lo) / span) * usable).toFloat()

        // The tail is pinned to the right edge, so the newest sample is always
        // in the same place instead of the whole trace sliding while it fills.
        val cols = w.toInt().coerceAtLeast(1)
        val per = ring.size.toDouble() / cols
        val firstCol = ((ring.size - count) / per).toInt()

        path.reset()
        var started = false
        for (c in firstCol until cols) {
            val lo1 = ((c * per).toInt() - (ring.size - count)).coerceAtLeast(0)
            val hi1 = (((c + 1) * per).toInt() - (ring.size - count)).coerceAtMost(count)
            if (lo1 >= hi1) continue
            var mn = at(lo1)
            var mx = at(lo1)
            for (i in lo1 until hi1) {
                val v = at(i)
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val x = c.toFloat()
            if (!started) { path.moveTo(x, y(mx)); started = true } else path.lineTo(x, y(mx))
            path.lineTo(x, y(mn))
        }
        canvas.drawPath(path, trace)
    }
}
