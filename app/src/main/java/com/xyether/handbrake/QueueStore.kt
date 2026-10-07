package com.xyether.handbrake

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.AtomicFile

/** Local queue/history only. Completed exports are never deleted during recovery. */
internal class QueueStore(directory: File) {
    private val file = AtomicFile(File(directory, "queue.json"))
    @Synchronized fun save(jobs: List<Job>) {
        val json = JSONArray()
        jobs.forEach { j ->
            val s=j.settings
            json.put(JSONObject().apply {
                put("id",j.id); put("uri",j.uri.toString()); put("name",j.name); put("size",j.sizeBytes)
                put("preset",j.preset.name); put("status",j.status.name); put("outPath",j.outPath)
                put("outSize",j.outSizeBytes); put("saved",j.savedToDevice); put("error",j.error)
                put("web",s.webOptimize); put("resolution",s.resolution); put("fps",s.fps)
                put("effort",s.encodingEffort); put("bitrate",s.vbrMbps); put("encoder",s.encoder.name)
                j.meta?.let { put("meta",JSONObject().put("w",it.width).put("h",it.height).put("duration",it.durMs).put("fps",it.fps)) }
            })
        }
        val stream=file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun load(): List<Job> = try {
        val array=JSONArray(file.openRead().use { it.bufferedReader().readText() })
        (0 until array.length()).mapNotNull { index -> runCatching {
            val j=array.getJSONObject(index)
            val previous=JobStatus.valueOf(j.getString("status"))
            val meta=j.optJSONObject("meta")?.let { VideoMeta(it.getInt("w"),it.getInt("h"),it.getLong("duration"),it.getDouble("fps").toFloat()) }
            Job(id=j.getString("id"),uri=Uri.parse(j.getString("uri")),name=j.getString("name"),sizeBytes=j.getLong("size"),
                preset=Preset.valueOf(j.getString("preset")),
                settings=Settings(webOptimize=j.optBoolean("web"),resolution=if(j.has("resolution")) j.getInt("resolution") else null,
                    fps=if(j.has("fps")) j.getDouble("fps") else null,vbrMbps=j.getDouble("bitrate").toFloat(),encoder=EncoderOption.valueOf(j.getString("encoder")),encodingEffort=if(j.has("effort")) j.getDouble("effort").toFloat() else null),
                status=if(previous==JobStatus.RUNNING) JobStatus.QUEUED else previous,meta=meta,
                outPath=if(previous==JobStatus.RUNNING) null else j.optString("outPath").takeIf { it.isNotEmpty() },
                outSizeBytes=if(j.has("outSize")) j.getLong("outSize") else null,
                savedToDevice=j.optBoolean("saved"),error=j.optString("error").takeIf { it.isNotEmpty() })
        }.getOrNull() }
    } catch (_: java.io.FileNotFoundException) { emptyList() }
      catch (_: Exception) { emptyList() }
}
