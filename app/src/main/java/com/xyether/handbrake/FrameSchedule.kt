package com.xyether.handbrake

import kotlin.math.max
import kotlin.math.roundToInt

fun scaledDims(w: Int, h: Int, capH: Int?): Pair<Int, Int> {
    require(w > 0 && h > 0 && (capH == null || capH > 0))
    fun even(v: Int) = max(2, v and -2)
    val scale = if (capH == null) 1.0 else minOf(1.0, capH.toDouble() / h)
    return even((w * scale).roundToInt()) to even((h * scale).roundToInt())
}

/** Select existing frames against a fixed clock without accumulating rounding drift. */
class FrameSchedule(fps: Double?) {
    private val interval = fps?.also { require(it.isFinite() && it > 0) }?.let { 1_000_000.0 / it }
    private var next = Double.NaN
    fun keep(ptsUs: Long): Boolean {
        val step = interval ?: return true
        if (next.isNaN()) next = ptsUs.toDouble()
        if (ptsUs + 1.0 < next) return false
        do { next += step } while (next <= ptsUs + 1.0)
        return true
    }
}

