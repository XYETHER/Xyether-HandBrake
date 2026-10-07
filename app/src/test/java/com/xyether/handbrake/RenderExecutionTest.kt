package com.xyether.handbrake

import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RenderExecutionTest {
    private fun waitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) {}
    }
    private fun awaitIdle() {
        val deadline=System.nanoTime()+2_000_000_000L
        while (RenderExecution.active.get() && System.nanoTime()<deadline) Thread.sleep(5)
        assertFalse(RenderExecution.active.get())
    }
    @Test(timeout=5000) fun watchdogEscapesBlockedCallAndPreventsOverlappingCleanup() = runBlocking {
        val release=CountDownLatch(1)
        try {
            try {
                RenderExecution.run(cancelled={false},stallTimeoutMs=100,abortGraceMs=40) {
                    it.stage="presenting a resized frame"; waitIgnoringInterrupts(release); 7L
                }
                fail("Stalled call must time out")
            } catch(e:RenderException) { assertTrue(e.message!!.contains("presenting a resized frame")) }
            assertTrue(RenderExecution.active.get())
            try { RenderExecution.run(cancelled={false}) { 1L }; fail("Cleanup still owns the codec") }
            catch(e:RenderException) { assertTrue(e.message!!.contains("still stopping")) }
        } finally { release.countDown(); awaitIdle() }
        assertEquals(9L,RenderExecution.run(cancelled={false}) { 9L })
    }
    @Test(timeout=5000) fun cancelEscapesAnUninterruptibleCall() = runBlocking {
        val release=CountDownLatch(1)
        try {
            try {
                RenderExecution.run(cancelled={true},abortGraceMs=40) { waitIgnoringInterrupts(release); 1L }
                fail("Expected cancellation")
            } catch(e:RenderException) { assertEquals("__aborted__",e.message) }
        } finally { release.countDown(); awaitIdle() }
    }
    @Test(timeout=5000) fun activeProgressDoesNotTriggerWatchdog() = runBlocking {
        assertEquals(12L,RenderExecution.run(cancelled={false},stallTimeoutMs=100) { session ->
            repeat(12) { session.advance(); Thread.sleep(20) }; 12L
        })
        awaitIdle()
    }
}
