package com.example.ui.map

/**
 * Frame budget and frame statistics for the map's animation loop
 * (Phase B, backlog B-18).
 *
 * The reference product regulates its renderer: a `Choreographer.FrameCallback`
 * sets a **target frame rate** (30 fps by default) and an *adaptive* minimum,
 * and it collects frame statistics (`FrameStats-<bucket>: totalFrameCount=…
 * totalFramesOverBudget=…`) plus an FPS histogram. This is the
 * RideTogether-original equivalent: the same intent — a declared rate the map
 * renders at, and the measurements to prove whether the device can hold it.
 *
 * The budget only decides **when a frame is drawn**. The animation itself stays
 * time-based (exponential damping on the elapsed time), so a skipped frame
 * simply applies a larger delta — the motion never slows down, it is only
 * sampled less often.
 */
class FrameBudget(
    val targetFps: Int = DEFAULT_TARGET_FPS,
    private val nanoClock: () -> Long = { System.nanoTime() }
) {

    /** Minimum interval between two rendered frames, in nanoseconds. */
    val minIntervalNanos: Long = if (targetFps <= 0) 0L else 1_000_000_000L / targetFps

    private var lastRenderNanos: Long = Long.MIN_VALUE

    /** Frames the loop asked for. */
    var totalFrames: Int = 0
        private set

    /** Frames that were actually drawn. */
    var renderedFrames: Int = 0
        private set

    /** Frames skipped because the target rate was not reached yet. */
    var skippedFrames: Int = 0
        private set

    /** Frames whose work took longer than the frame interval. */
    var framesOverBudget: Int = 0
        private set

    private val frameDurations = ArrayDeque<Long>()

    /**
     * True when a frame may be drawn now.
     *
     * The first call always renders (so the map appears immediately); afterwards
     * a frame is only drawn once the target interval has elapsed.
     */
    fun shouldRender(nowNanos: Long = nanoClock()): Boolean {
        totalFrames++
        if (lastRenderNanos != Long.MIN_VALUE && nowNanos - lastRenderNanos < minIntervalNanos) {
            skippedFrames++
            return false
        }
        lastRenderNanos = nowNanos
        renderedFrames++
        return true
    }

    /** Records how long the drawn frame's work took. */
    fun recordFrameDuration(durationNanos: Long) {
        if (durationNanos < 0) return
        frameDurations.addLast(durationNanos)
        while (frameDurations.size > MAX_SAMPLES) frameDurations.removeFirst()
        if (durationNanos > minIntervalNanos) framesOverBudget++
    }

    /** The current statistics. */
    fun stats(): FrameStats {
        val samples = frameDurations.toList()
        val average = if (samples.isEmpty()) 0.0 else samples.sum().toDouble() / samples.size
        val p95 = percentile(samples, 95)
        return FrameStats(
            totalFrames = totalFrames,
            renderedFrames = renderedFrames,
            skippedFrames = skippedFrames,
            framesOverBudget = framesOverBudget,
            averageFrameMs = average / 1_000_000.0,
            p95FrameMs = p95 / 1_000_000.0,
            targetFps = targetFps
        )
    }

    /** Clears the statistics (used when the map is re-created). */
    fun reset() {
        lastRenderNanos = Long.MIN_VALUE
        totalFrames = 0
        renderedFrames = 0
        skippedFrames = 0
        framesOverBudget = 0
        frameDurations.clear()
    }

    private fun percentile(samples: List<Long>, percentile: Int): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        /** Default target rate for the map renderer. */
        const val DEFAULT_TARGET_FPS = 60

        /** Number of frame durations kept for the percentile. */
        const val MAX_SAMPLES = 120
    }
}

/** A snapshot of the map renderer's frame statistics. */
data class FrameStats(
    val totalFrames: Int,
    val renderedFrames: Int,
    val skippedFrames: Int,
    val framesOverBudget: Int,
    val averageFrameMs: Double,
    val p95FrameMs: Double,
    val targetFps: Int
) {
    /** The share of frames that met the budget (0..1). */
    fun onBudgetRatio(): Double =
        if (renderedFrames == 0) 1.0 else (renderedFrames - framesOverBudget).toDouble() / renderedFrames

    /** A compact, log-friendly summary. */
    fun summary(): String =
        "target=${targetFps}fps rendered=$renderedFrames skipped=$skippedFrames overBudget=$framesOverBudget " +
            "avg=${"%.1f".format(averageFrameMs)}ms p95=${"%.1f".format(p95FrameMs)}ms"
}
