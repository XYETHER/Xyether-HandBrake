package com.xyether.handbrake

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Rewrite a regular MP4 transactionally. Preserve the source on every failure. */
object FastStart {
    private data class Box(val type: String, val off: Long, val len: Long)
    fun apply(file: File): Boolean {
        val tmp = File(file.parentFile, file.name + ".fast")
        try {
            RandomAccessFile(file, "r").use { src ->
                val boxes = mutableListOf<Box>()
                var off = 0L
                while (off < src.length()) {
                    require(src.length() - off >= 8)
                    src.seek(off)
                    var len = src.readInt().toLong() and 0xffffffffL
                    val type = String(ByteArray(4).also { src.readFully(it) }, Charsets.ISO_8859_1)
                    val header = if (len == 1L) 16 else 8
                    if (len == 1L) len = src.readLong()
                    if (len == 0L) len = src.length() - off
                    require(len >= header && len <= src.length() - off)
                    boxes += Box(type, off, len)
                    off += len
                }
                require(boxes.none { it.type == "moof" })
                val moov = boxes.single { it.type == "moov" }
                val mdat = boxes.first { it.type == "mdat" }
                if (moov.off < mdat.off) return true
                require(moov.len <= 64L * 1024 * 1024)
                val bytes = ByteArray(moov.len.toInt())
                src.seek(moov.off); src.readFully(bytes)
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                fun patch(start: Int, end: Int) {
                    var pos = start
                    while (pos < end) {
                        require(end - pos >= 8)
                        var size = b.getInt(pos).toLong() and 0xffffffffL
                        val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
                        val header = if (size == 1L) 16 else 8
                        if (size == 1L) { require(end - pos >= 16); size = b.getLong(pos + 8) }
                        if (size == 0L) size = (end - pos).toLong()
                        require(size >= header && size <= end - pos)
                        val finish = pos + size.toInt()
                        val body = pos + header
                        if (type in setOf("moov", "trak", "mdia", "minf", "stbl")) patch(body, finish)
                        if (type == "stco" || type == "co64") {
                            require(finish - body >= 8 && b.getInt(body) == 0)
                            val count = b.getInt(body + 4).toLong() and 0xffffffffL
                            val stride = if (type == "stco") 4 else 8
                            require(count * stride <= finish - body - 8)
                            repeat(count.toInt()) { index ->
                                val at = body + 8 + index * stride
                                val old = if (stride == 4) b.getInt(at).toLong() and 0xffffffffL else b.getLong(at)
                                require(old >= 0 && old < src.length())
                                val shifted = when {
                                    old < mdat.off -> old
                                    old < moov.off -> Math.addExact(old, moov.len)
                                    old >= moov.off + moov.len -> old
                                    else -> error("Chunk points inside moov")
                                }
                                if (stride == 4) { require(shifted <= 0xffffffffL); b.putInt(at, shifted.toInt()) }
                                else b.putLong(at, shifted)
                            }
                        }
                        pos = finish
                    }
                }
                patch(0, bytes.size)
                RandomAccessFile(tmp, "rw").use { dst ->
                    dst.setLength(0)
                    val buffer = ByteArray(1024 * 1024)
                    for (box in boxes) {
                        if (box == moov) continue
                        if (box == mdat) dst.write(bytes)
                        src.seek(box.off)
                        var left = box.len
                        while (left > 0) {
                            val n = minOf(left, buffer.size.toLong()).toInt()
                            src.readFully(buffer, 0, n); dst.write(buffer, 0, n); left -= n
                        }
                    }
                    require(dst.length() == src.length())
                    dst.fd.sync()
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (_: Exception) { return false }
        finally { tmp.delete() }
    }
}
