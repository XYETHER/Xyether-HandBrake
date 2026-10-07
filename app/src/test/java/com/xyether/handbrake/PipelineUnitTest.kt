package com.xyether.handbrake

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class PipelineUnitTest {
    @Test fun fractionalFpsDoesNotDrift() {
        val schedule = FrameSchedule(23.976)
        val frames = (0 until 30_000).count { schedule.keep((it * 1_000_000.0 / 30).toLong()) }
        assertEquals(23_976, frames)
    }
    @Test fun frameCapsNeverDuplicateOrUpsample() {
        for (fps in listOf(7.992, 11.98, 23.976, 30.0, 60.0, 120.0)) {
            val schedule = FrameSchedule(fps)
            val frames = (0 until 300).count { schedule.keep((it * 1_000_000.0 / 30).toLong()) }
            assertTrue("fps=$fps count=$frames", kotlin.math.abs(frames - minOf(30.0, fps) * 10) <= 1)
        }
    }
    @Test fun geometryMaintainsAspectAndEvenSizes() {
        assertEquals(1280 to 720, scaledDims(1920, 1080, 720))
        assertEquals(640 to 360, scaledDims(640, 360, 1080))
        assertEquals(404 to 720, scaledDims(1080, 1920, 720))
        assertEquals(640 to 360, scaledDims(641, 361, null))
    }
    private fun box(type: String, bytes: ByteArray) = ByteBuffer.allocate(bytes.size + 8)
        .putInt(bytes.size + 8).put(type.toByteArray()).put(bytes).array()
    private fun fixture(co64: Boolean = false): ByteArray {
        val ftyp = box("ftyp", ByteArray(8))
        val mdat = box("mdat", byteArrayOf(11, 22, 33, 44))
        val entry = ByteBuffer.allocate(if (co64) 16 else 12).putInt(0).putInt(1)
        if (co64) entry.putLong(24) else entry.putInt(24)
        val stco = box(if (co64) "co64" else "stco", entry.array())
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stco)))))
        return ftyp + mdat + box("free", byteArrayOf(9, 9)) + moov + box("free", byteArrayOf(7))
    }
    @Test(timeout = 2000) fun fastStartPatchesAllOffsetsAndPreservesOtherBoxes() {
        for (co64 in listOf(false, true)) {
            val file = File.createTempFile("faststart", ".mp4")
            try {
                val input = fixture(co64); file.writeBytes(input)
                assertTrue(FastStart.apply(file))
                val output = file.readBytes()
                assertEquals(input.size, output.size)
                assertEquals("moov", String(output, 20, 4))
                val pos = output.toList().windowed(4).indexOfFirst { it.toByteArray().contentEquals((if(co64) "co64" else "stco").toByteArray()) }
                val moovSize = ByteBuffer.wrap(output).getInt(16)
                val offset = if (co64) ByteBuffer.wrap(output).getLong(pos + 12) else ByteBuffer.wrap(output).getInt(pos + 12).toLong()
                assertEquals(24L + moovSize, offset)
                assertEquals(11.toByte(), output[offset.toInt()])
                assertEquals(7.toByte(), output.last())
                val once = output.copyOf()
                assertTrue(FastStart.apply(file)); assertArrayEquals(once, file.readBytes())
                assertFalse(File(file.parentFile, file.name + ".fast").exists())
            } finally { file.delete() }
        }
    }
    @Test(timeout = 2000) fun malformedMp4LeavesOriginalUntouched() {
        val file = File.createTempFile("faststart", ".mp4")
        try {
            val bytes = byteArrayOf(0, 0, 0, 100, 109, 111, 111, 118)
            file.writeBytes(bytes); assertFalse(FastStart.apply(file)); assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }
}
