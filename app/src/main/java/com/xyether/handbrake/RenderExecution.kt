package com.xyether.handbrake

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

internal class PipelineSession {
    private val progressAt = AtomicLong(System.nanoTime())
    val stopRequested = AtomicBoolean(false)
    @Volatile var stage: String = "opening the video"
    fun advance() { progressAt.set(System.nanoTime()) }
    fun idleMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - progressAt.get())
}

internal object RenderExecution {
    val active = AtomicBoolean(false)

    /** Monitor outside the native codec thread: an OEM call cannot trap the UI coroutine. */
    suspend fun run(
        cancelled: () -> Boolean,
        stallTimeoutMs: Long = 60_000,
        abortGraceMs: Long = 3_000,
        encode: (PipelineSession) -> Long,
    ): Long {
        if (!active.compareAndSet(false, true))
            throw RenderException("The previous codec is still stopping. Please wait, or restart the app if it remains stuck.")
        val session = PipelineSession()
        val finished = CountDownLatch(1)
        var result = 0L
        var failure: Throwable? = null
        val worker = Thread({
            try { result = encode(session) }
            catch (e: Throwable) { failure = e }
            finally { active.set(false); finished.countDown() }
        }, "hb-render").apply { isDaemon = true }
        try { worker.start() }
        catch (e: Throwable) { active.set(false); throw e }
        try {
            while (finished.count > 0) {
                if (cancelled() || session.idleMs() >= stallTimeoutMs) {
                    val aborted = cancelled()
                    session.stopRequested.set(true)
                    worker.interrupt()
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(abortGraceMs)
                    while (finished.count > 0 && System.nanoTime() < deadline) delay(20)
                    if (aborted) throw RenderException("__aborted__")
                    throw RenderException("Encoding stopped making progress while ${session.stage}." +
                        if (finished.count > 0) " The device driver is still stopping; restart the app if retry remains unavailable." else "")
                }
                delay(20)
            }
            failure?.let { throw it }
            return result
        } finally {
            if (finished.count > 0) { session.stopRequested.set(true); worker.interrupt() }
            // The native worker owns cleanup. Do not race release or start another job while it is stuck.
        }
    }
}
