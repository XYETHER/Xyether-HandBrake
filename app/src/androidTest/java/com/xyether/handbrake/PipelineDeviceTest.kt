package com.xyether.handbrake

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PipelineDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private fun input(name: String): File {
        val file = File(context.cacheDir, name)
        instrument.context.assets.open(name).use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }
    private fun export(name: String, settings: Settings, fixture: String = "input.mp4"): File {
        val source = input(fixture)
        val output = File(context.filesDir, "validation/$name.mp4").also { it.parentFile!!.mkdirs() }
        RenderBus.abortRequested = false
        // Emulator encoders are software. Only tests permit them; production remains hardware-only.
        encodeWithSurfaces(context, Job(uri=Uri.fromFile(source), name=fixture, sizeBytes=source.length(),
            preset=Preset.BALANCED, settings=settings, status=JobStatus.QUEUED), output, hardwareOnly=false)
        assertTrue(output.length() > 0)
        return output
    }
    private fun inspect(file: File, width: Int, height: Int, count: Int, durationUs: Long = 3_000_000) {
        val e = MediaExtractor()
        try {
            e.setDataSource(file.absolutePath)
            val video = (0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val f = e.getTrackFormat(video)
            assertEquals(width, f.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(height, f.getInteger(MediaFormat.KEY_HEIGHT))
            e.selectTrack(video)
            var frames=0; var last=-1L
            while(e.sampleTrackIndex >= 0) { assertTrue(e.sampleTime > last); last=e.sampleTime; frames++; e.advance() }
            assertEquals(count, frames)
            assertTrue(last in (durationUs - 200_000L)..(durationUs + 100_000L))
            e.unselectTrack(video)
            val audio=(0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            e.selectTrack(audio); var samples=0
            while(e.sampleTrackIndex >= 0) { samples++; e.advance() }
            assertTrue(samples > 100)
        } finally { e.release() }
    }
    @Test fun sourceSizeAndAudio() { inspect(export("source", Settings(vbrMbps=2f)), 640,360,90) }
    @Test fun resizeOrientationAndFastStart() {
        val output=export("scaled", Settings(resolution=180,vbrMbps=1f,webOptimize=true))
        inspect(output,320,180,90)
        val r=MediaMetadataRetriever()
        try {
            r.setDataSource(output.absolutePath)
            val b=r.getFrameAtTime(1_000_000,MediaMetadataRetriever.OPTION_CLOSEST)!!
            val top=b.getPixel(b.width/2,b.height/8); val bottom=b.getPixel(b.width/2,b.height*7/8)
            assertTrue("Top must stay red",android.graphics.Color.red(top)>android.graphics.Color.blue(top)+80)
            assertTrue("Bottom must stay blue",android.graphics.Color.blue(bottom)>android.graphics.Color.red(bottom)+80)
        } finally { r.release() }
    }
    @Test fun fractionalFpsCapAndAudio() { inspect(export("fps",Settings(fps=23.976,vbrMbps=2f)),640,360,72) }
    @Test fun rotationMetadataSurvivesBothPaths() {
        for (resolution in listOf<Int?>(null,180)) {
            val output=export("rotation_$resolution",Settings(resolution=resolution,vbrMbps=2f),"rotated.mp4")
            if (resolution != null) inspect(output,180,100,90)
            val r=MediaMetadataRetriever()
            try { r.setDataSource(output.absolutePath); assertEquals("90",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)) }
            finally { r.release() }
        }
    }
    @Test fun abortRemovesPartialExport() {
        val source=input("input.mp4"); val out=File(context.filesDir,"validation/aborted.mp4")
        RenderBus.abortRequested=true
        try {
            encodeWithSurfaces(context,Job(uri=Uri.fromFile(source),name=source.name,sizeBytes=source.length(),preset=Preset.BALANCED,status=JobStatus.QUEUED),out,hardwareOnly=false)
            fail("Expected cancellation")
        } catch (e:RenderException) { assertEquals("__aborted__",e.message) }
        finally { RenderBus.abortRequested=false }
        assertFalse(out.exists())
    }
    @Test fun queueSurvivesActivityRecreation() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var original: QueueViewModel? = null
            scenario.onActivity { activity ->
                original = androidx.lifecycle.ViewModelProvider(activity)[QueueViewModel::class.java]
                original!!.jobs.clear()
                original!!.jobs += Job(uri=Uri.fromFile(input("input.mp4")), name="keep.mp4", sizeBytes=100,
                    preset=Preset.SMALL, status=JobStatus.QUEUED)
                original!!.saveDocumentJob = original!!.jobs.first()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val current = androidx.lifecycle.ViewModelProvider(activity)[QueueViewModel::class.java]
                assertSame(original, current)
                assertEquals("keep.mp4", current.jobs.single().name)
                assertEquals(2.5f, current.jobs.single().settings.vbrMbps)
                assertEquals("keep.mp4", current.saveDocumentJob!!.name)
            }
        }
    }
    @Test fun savePublishesOnlyCompleteFile() {
        val out=export("save_${System.nanoTime()}",Settings(vbrMbps=1f))
        val job=Job(uri=Uri.fromFile(out), name=out.name, sizeBytes=out.length(),preset=Preset.BALANCED,
            status=JobStatus.DONE,outPath=out.absolutePath)
        assertTrue(kotlinx.coroutines.runBlocking { saveToDevice(context,job) })
        val collection=android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val resolver=context.contentResolver
        resolver.query(collection,arrayOf("_id","_size","is_pending"),"_display_name=?",arrayOf(out.name),null)!!.use { c ->
            assertTrue(c.moveToFirst()); assertEquals(out.length(),c.getLong(1)); assertEquals(0,c.getInt(2))
            resolver.delete(android.content.ContentUris.withAppendedId(collection,c.getLong(0)),null,null)
        }
        assertFalse(kotlinx.coroutines.runBlocking { saveToDevice(context,job.copy(outPath="/missing.mp4")) })
    }
    @Test fun durableQueueRetainsOutputsAndRecoversInterruptedJobs() {
        val directory=File(context.cacheDir,"store_${System.nanoTime()}").apply { mkdirs() }
        try {
            val output=File(directory,"completed.mp4").apply { writeText("protected") }
            val done=Job(uri=Uri.fromFile(output),name="done.mp4",sizeBytes=100,preset=Preset.TINY,status=JobStatus.DONE,
                outPath=output.absolutePath,outSizeBytes=9,savedToDevice=true,settings=Settings(encodingEffort=.75f))
            val running=done.copy(id="running",name="running.mp4",status=JobStatus.RUNNING)
            QueueStore(directory).save(listOf(done,running))
            val restored=QueueStore(directory).load()
            assertEquals(2,restored.size); assertEquals(done,restored[0])
            assertEquals(JobStatus.QUEUED,restored[1].status); assertNull(restored[1].outPath)
            assertEquals("protected",output.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test(timeout=120000) fun managedPipelineStressKeepsMoving() = kotlinx.coroutines.runBlocking {
        val source=input("stress.mp4")
        for (iteration in 0..2) for (scale in listOf(false,true)) {
            val output=File(context.filesDir,"validation/stress_${iteration}_$scale.mp4")
            val settings=Settings(resolution=if(scale) 180 else null,fps=if(scale) 30.0 else null,vbrMbps=2f,webOptimize=scale)
            RenderBus.abortRequested=false
            RenderExecution.run(cancelled={false}) { session ->
                encodeWithSurfaces(context,Job(uri=Uri.fromFile(source),name=source.name,sizeBytes=source.length(),
                    preset=Preset.BALANCED,settings=settings,status=JobStatus.QUEUED),output,hardwareOnly=false,session=session)
            }
            inspect(output,if(scale) 320 else 640,if(scale) 180 else 360,if(scale) 360 else 720,12_000_000)
            assertFalse(RenderExecution.active.get())
        }
    }
    @Test(timeout=30000) fun cancelDuringRealEncodingExitsAndCleansUp() = kotlinx.coroutines.runBlocking {
        val source=input("stress.mp4")
        val output=File(context.filesDir,"validation/midway-abort.mp4")
        RenderBus.abortRequested=false
        RenderBus.progress.floatValue=0f
        try {
            RenderExecution.run(cancelled={RenderBus.progress.floatValue > 0.1f}) { session ->
                encodeWithSurfaces(context,Job(uri=Uri.fromFile(source),name=source.name,sizeBytes=source.length(),
                    preset=Preset.BALANCED,settings=Settings(resolution=180),status=JobStatus.QUEUED),output,hardwareOnly=false,session=session)
            }
            fail("Expected mid-video cancellation")
        } catch (e:RenderException) { assertEquals("__aborted__",e.message) }
        assertFalse(RenderExecution.active.get()); assertFalse(output.exists())
    }
}
