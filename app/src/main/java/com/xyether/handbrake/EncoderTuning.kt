package com.xyether.handbrake

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import kotlin.math.roundToInt

internal fun hardwareEncoding(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29)
    info.isHardwareAccelerated else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)

internal fun encodingEffortSupported(mime: String): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
    it.isEncoder && hardwareEncoding(it) && it.supportedTypes.any { type -> type.equals(mime, true) } &&
        runCatching { it.getCapabilitiesForType(mime).encoderCapabilities.complexityRange.let { r -> r.upper > r.lower } }.getOrDefault(false)
}

internal fun complexityValue(effort: Float, lower: Int, upper: Int): Int {
    require(effort.isFinite() && effort in 0f..1f && lower < upper)
    return (lower.toDouble() + (upper.toDouble() - lower) * effort).roundToInt().coerceIn(lower, upper)
}

internal fun applyEncodingEffort(format: MediaFormat, info: MediaCodecInfo, effort: Float?) {
    if (effort == null) return
    val range = info.getCapabilitiesForType(format.getString(MediaFormat.KEY_MIME)!!).encoderCapabilities.complexityRange
    require(range.upper > range.lower) { "This encoder has no adjustable speed. Select Device default." }
    format.setInteger(MediaFormat.KEY_COMPLEXITY, complexityValue(effort, range.lower, range.upper))
}
