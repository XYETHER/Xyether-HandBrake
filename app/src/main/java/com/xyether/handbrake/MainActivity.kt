package com.xyether.handbrake

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID

val Bg = Color(0xFF050505)
val CardCol = Color(0xFF0D0D0D)
val Line = Color(0xFF222222)
val ChipBg = Color(0xFF171717)
val TextPri = Color(0xFFF4F4F4)
val TextSec = Color(0xFF969696)
val TextDim = Color(0xFF888888)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

fun ts(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight,
       spacing: androidx.compose.ui.unit.TextUnit = 0.sp,
       lh: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = Inter, fontWeight = weight, fontSize = size,
        letterSpacing = spacing, lineHeight = lh,
    )

val AppType = Typography(
    headlineSmall = ts(26.sp, FontWeight.SemiBold, (-0.3).sp),
    titleMedium = ts(15.sp, FontWeight.Medium, 0.sp, 20.sp),
    bodyMedium = ts(13.sp, FontWeight.Normal, 0.1.sp, 19.sp),
    labelSmall = ts(10.sp, FontWeight.Medium, 1.2.sp),
)

data class Job(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val preset: Preset,
    val settings: Settings = preset.settings(),
    val expanded: Boolean = false,
    val meta: VideoMeta? = null,
    val status: JobStatus,
    val progress: Float = 0f,
    val outSizeBytes: Long? = null,
    val outPath: String? = null,
    val savedToDevice: Boolean = false,
    val error: String? = null,
)

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED }

data class Settings(
    val webOptimize: Boolean = false,
    val resolution: Int? = null,          // null = off/source; else height cap
    val fps: Double? = null,              // null = same as source
    val rateMode: RateMode = RateMode.VBR,
    val vbrMbps: Float = 5f,
    val encoder: EncoderOption = EncoderOption.H264,
    val encodingEffort: Float? = null,
)

enum class RateMode(val label: String) { VBR("Avg Bitrate") }

enum class EncoderOption(
    val label: String,
    val mime: String,
    val is10Bit: Boolean,
) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC, false),
    H265("H.265", MediaFormat.MIMETYPE_VIDEO_HEVC, false),
    H265_10_BIT("H.265 10-bit", MediaFormat.MIMETYPE_VIDEO_HEVC, true),
}

fun availableEncoders(): List<EncoderOption> = EncoderOption.entries.filter { option ->
    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        codec.isEncoder && isHardwareCodec(codec) && codec.supportedTypes.any { it.equals(option.mime, true) } &&
            runCatching {
                val caps = codec.getCapabilitiesForType(option.mime)
                !option.is10Bit || caps.profileLevels.any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            }.getOrDefault(false)
    }
}

data class VideoMeta(
    val width: Int,
    val height: Int,
    val durMs: Long,
    val fps: Float,
)

fun probeMeta(ctx: android.content.Context, uri: Uri): VideoMeta? {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(ctx, uri)
        val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
        return if (w > 0 && h > 0) VideoMeta(if (rot % 180 != 0) h else w, if (rot % 180 != 0) w else h, dur, fps) else null
    } catch (_: Exception) { return null } finally { mmr.release() }
}

fun estBitrateMbps(s: Settings, w: Int, h: Int, fps: Float): Double {
    return s.vbrMbps.toDouble()
}

fun estimateOutBytes(meta: VideoMeta?, s: Settings): Long? {
    val m = meta ?: return null
    val outH = s.resolution?.coerceAtMost(m.height) ?: m.height
    val scale = outH.toDouble() / m.height
    val outW = (m.width * scale).toInt()
    val outFps = if (s.fps != null) s.fps.toFloat().coerceAtMost(m.fps) else m.fps
    val sec = m.durMs / 1000.0
    val videoBits = estBitrateMbps(s, outW, outH, outFps) * 1e6 * sec
    val audioBits = 128_000.0 * sec                       // stereo AAC default
    return ((videoBits + audioBits) / 8.0 * 1.04).toLong() // +4% mux overhead
}

val RES_OPTIONS = listOf(null, 2160, 1440, 1080, 720, 480, 360)
fun resLabel(r: Int?) = when (r) {
    null -> "Off"
    2160 -> "4K"
    1440 -> "2K"
    else -> "${r}p"
}

val FPS_OPTIONS = listOf<Double?>(null, 120.0, 60.0, 30.0, 23.976, 11.98, 7.992)
fun fpsLabel(f: Double?) = when (f) {
    null -> "Source"
    23.976 -> "23.976"
    11.98 -> "11.98"
    7.992 -> "7.992"
    else -> "%.0f".format(f)
}

enum class Preset(val label: String, val detail: String) {
    BALANCED("Balanced", "~1080p"),
    SMALL("Small", "~720p"),
    TINY("Tiny", "~480p"),
}

fun Preset.settings() = when (this) {
    Preset.BALANCED -> Settings(resolution = 1080, vbrMbps = 5f)
    Preset.SMALL -> Settings(resolution = 720, vbrMbps = 2.5f)
    Preset.TINY -> Settings(resolution = 480, vbrMbps = 1f)
}

fun fmtSize(b: Long): String = when {
    b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun queryName(ctx: android.content.Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }

fun querySize(ctx: android.content.Context, uri: Uri): Long? =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
    }

class QueueViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val jobs = mutableStateListOf<Job>()
    var rendering by mutableStateOf(false)
    var doneJob by mutableStateOf<Job?>(null)
    var saveDocumentJob by mutableStateOf<Job?>(null)
    private val store = QueueStore(application.filesDir)
    init {
        jobs.addAll(store.load())
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { jobs.toList() }.collect { snapshot ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { store.save(snapshot) }.onFailure { android.util.Log.e("QueueStore", "Could not save queue", it) }
                }
            }
        }
    }
    fun startBatch(context: android.content.Context) {
        if (rendering) return
        if (RenderExecution.active.get()) {
            Toast.makeText(context, "The previous codec is still stopping. Wait or restart the app.", Toast.LENGTH_LONG).show()
            return
        }
        val targets = jobs.filter { it.status == JobStatus.QUEUED }
        if (targets.isEmpty()) return
        rendering = true
        RenderBus.abortRequested = false
        RenderBus.batchTotal.intValue = targets.size
        RenderBus.batchIx.intValue = 0
        RenderBus.frameBmp.value = null
        viewModelScope.launch {
            var lastCompleted: Job? = null
            try {
            for ((ix, job) in targets.withIndex()) {
                if (RenderBus.abortRequested) break
                RenderBus.batchIx.intValue = ix + 1
                RenderBus.activeName.value = job.name
                RenderBus.progress.floatValue = 0f
                RenderBus.outBytes.longValue = 0L
                RenderBus.encodeFps.floatValue = 0f
                RenderBus.totalDurSec.floatValue = (job.meta?.durMs ?: 10_000L) / 1000f
                RenderBus.webOpt = job.settings.webOptimize
                RenderBus.frameBmp.value = null
                val ji = jobs.indexOfFirst { it.id == job.id }
                if (ji >= 0) jobs[ji] = jobs[ji].copy(
                    status = JobStatus.RUNNING, progress = 0f,
                    outPath = null, savedToDevice = false)
                try {
                    val baseName = job.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    val out = File(context.filesDir, "exports").apply { mkdirs() }
                        .resolve("hb_${baseName}_${System.currentTimeMillis()}.mp4")
                    val written = renderJob(context, job, out)
                    // pin completion state so the window never freezes mid-values
                    RenderBus.progress.floatValue = 1f
                    RenderBus.frameBmp.value = null
                    RenderBus.activeName.value = null
                    val j2 = jobs.indexOfFirst { it.id == job.id }
                    if (j2 >= 0) {
                        jobs[j2] = jobs[j2].copy(
                            status = JobStatus.DONE, progress = 1f, outSizeBytes = written,
                            outPath = out.absolutePath)
                        lastCompleted = jobs[j2]
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val aborted = e.message == "__aborted__"
                    val j2 = jobs.indexOfFirst { it.id == job.id }
                    if (j2 >= 0) jobs[j2] = jobs[j2].copy(
                        status = if (aborted) JobStatus.QUEUED else JobStatus.FAILED, error = if (aborted) null else e.message ?: "Compression failed")
                    if (aborted) break   // stop the whole batch on abort
                    if (RenderExecution.active.get()) break // Preserve remaining queued jobs while native cleanup is blocked.
                }
            }
            } finally {
            // hard reset: overlay must always dismiss, whatever happened above
            rendering = false
            RenderBus.activeName.value = null
            RenderBus.frameBmp.value = null
            doneJob = lastCompleted
            }
        }
    }


    override fun onCleared() { RenderBus.abortRequested = true }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val queue = androidx.lifecycle.ViewModelProvider(this)[QueueViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                primary = Color.White, onPrimary = Bg,
                background = Bg, onBackground = TextPri,
                surface = CardCol, onSurface = TextPri,
                surfaceVariant = ChipBg, onSurfaceVariant = TextSec,
            ), typography = AppType) {
                Surface(Modifier.fillMaxSize(), color = Bg) { HomeScreen(queue) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(queue: QueueViewModel) {
    val context = LocalContext.current
    val jobs = queue.jobs
    val scope = rememberCoroutineScope()
    val rendering = queue.rendering
    androidx.compose.runtime.DisposableEffect(rendering) {
        val window = (context as? android.app.Activity)?.window
        if (rendering) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var doneJob by queue::doneJob
    var discardJob by remember { mutableStateOf<Job?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var saveDocumentJob by queue::saveDocumentJob
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val pending = saveDocumentJob
        saveDocumentJob = null
        if (uri != null && pending != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os -> File(pending.outPath!!).inputStream().use { it.copyTo(os) } }
                        ?: error("Cannot open destination")
                }.isSuccess
            }
            val index = jobs.indexOfFirst { it.id == pending.id }
            if (ok && index >= 0) jobs[index] = jobs[index].copy(savedToDevice = true)
            Toast.makeText(context, if (ok) "Saved" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun addVideos(uris: List<Uri>) {
        scope.launch {
            val added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { u -> runCatching {
                    try { context.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
                    Job(uri = u, name = queryName(context, u) ?: "video.mp4", sizeBytes = querySize(context, u) ?: 0L,
                        preset = Preset.BALANCED, status = JobStatus.QUEUED, meta = probeMeta(context, u))
                }.getOrNull() }
            }
            jobs.addAll(added)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::addVideos)
    val sourceLabel = remember { mutableStateOf("System picker") }
    var showSourceSheet by remember { mutableStateOf(false) }
    val photoPickerSamsung = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9), ::addVideos)

    fun launchPicker() {
        when (sourceLabel.value) {
            "Samsung Gallery" -> photoPickerSamsung.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.VideoOnly))
            else -> picker.launch(arrayOf("video/*"))
        }
    }

    val nQueued = jobs.count { it.status == JobStatus.QUEUED }
    val nRunning = jobs.count { it.status == JobStatus.RUNNING }

    Scaffold(
        containerColor = Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (jobs.isNotEmpty()) {
                Surface(color = Bg) {
                    Box(
                        Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .background(Bg)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Button(
                            onClick = { queue.startBatch(context.applicationContext) },
                            enabled = nQueued > 0 && nRunning == 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = ChipBg,
                                disabledContentColor = TextDim,
                            ),
                        ) {
                            Icon(Icons.Filled.Compress, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                when {
                                    nRunning > 0 -> "Compressing…"
                                    nQueued > 0 -> "Compress $nQueued"
                                    else -> "All done"
                                },
                                fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.2.sp,
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Header(totalVideos = jobs.size, totalBytes = jobs.sumOf { it.sizeBytes })
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SOURCE", color = TextDim, fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, Line, RoundedCornerShape(7.dp))
                            .clickable { showSourceSheet = true }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sourceLabel.value, color = TextSec, fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = TextDim,
                            modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { showAbout = true }) {
                        Text("About", color = TextSec, fontSize = 10.sp)
                    }
                }
                AddDropZone { launchPicker() }

                if (jobs.isEmpty()) {
                    EmptyState()
                } else {
                    Spacer(Modifier.height(20.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(jobs, key = { it.id }) { job ->
                            JobCard(
                                job = job,
                                onRemove = { if (job.status != JobStatus.RUNNING) discardJob = job },
                                onPresetChange = { p ->
                                    val i = jobs.indexOfFirst { it.id == job.id }
                                    if (i >= 0) jobs[i] = jobs[i].copy(preset = p, settings = p.settings(), status = JobStatus.QUEUED, error = null)
                                },
                                onToggleExpand = {
                                    val i = jobs.indexOfFirst { it.id == job.id }
                                    if (i >= 0) jobs[i] = jobs[i].copy(expanded = !jobs[i].expanded)
                                },
                                onSettings = { s ->
                                    val i = jobs.indexOfFirst { it.id == job.id }
                                    if (i >= 0) jobs[i] = jobs[i].copy(settings = s, status = JobStatus.QUEUED, error = null)
                                },
                                onResult = { doneJob = job },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }

    if (rendering) {
        BackHandler(enabled = true) { /* block back during render */ }
        RenderWindow(onAbort = {
            RenderBus.abortRequested = true
        })
    }

    doneJob?.let { dj ->
        DoneDialog(
            job = dj,
            onDismiss = { doneJob = null },
            onSave = {
                doneJob = null
                if (android.os.Build.VERSION.SDK_INT < 29) {
                    saveDocumentJob = dj
                    saveDocument.launch(File(dj.outPath!!).name)
                } else scope.launch {
                    val ok = saveToDevice(context, dj)
                    val index = jobs.indexOfFirst { it.id == dj.id }
                    if (ok && index >= 0) jobs[index] = jobs[index].copy(savedToDevice = true)
                    Toast.makeText(context, if (ok) "Saved to Movies/XyetherHandBrake" else "Save failed", Toast.LENGTH_SHORT).show()
                }
            },
            onShare = {
                doneJob = null
                shareVideo(context, dj)
            },
        )
    }

    discardJob?.let { dj ->
        DiscardDialog(
            name = dj.name,
            onCancel = { discardJob = null },
            onConfirm = {
                jobs.removeAll { it.id == dj.id }
                dj.outPath?.let { p -> File(p).delete() }
                discardJob = null
            },
        )
    }

    if (showSourceSheet) {
        SourceSheet(
            current = sourceLabel.value,
            onPick = { sourceLabel.value = it; showSourceSheet = false },
            onDismiss = { showSourceSheet = false },
        )
    }
    if (showAbout) {
        val notices = remember {
            "Xyether HandBrake by xyether\nOffline Android video compressor. Independent of the HandBrake project.\n\n" +
                "AndroidX and Compose: Apache License 2.0\nKotlin: Apache License 2.0\nInter font: SIL Open Font License 1.1\n\n" +
                context.assets.open("licenses/Inter-OFL.txt").bufferedReader().use { it.readText() } + "\n\n" +
                context.assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAbout = false }, containerColor = CardCol,
            title = { Text("About / licenses", color = TextPri) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item { Text(notices, color = TextSec, fontSize = 11.sp) }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showAbout = false }) { Text("Close", color = TextPri) } },
        )
    }
}

@Composable
fun Header(totalVideos: Int, totalBytes: Long) {
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_art),
            contentDescription = null,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp)),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Xyether HandBrake", color = TextPri, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
            if (totalVideos > 0) {
                Text("${totalVideos} VIDEO${if (totalVideos > 1) "S" else ""} · ${fmtSize(totalBytes).uppercase()}",
                    color = TextDim, fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp)
            } else {
                Text("OFFLINE VIDEO COMPRESSOR", color = TextDim, fontSize = 9.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
            }
        }
    }
}

@Composable
fun AddDropZone(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardCol)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, null, tint = Color.Black, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Add videos", color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("MP4 · MOV · MKV · WEBM", color = TextSec, fontSize = 11.sp, letterSpacing = 0.3.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("+", color = TextDim, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .border(1.dp, Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Movie, null, tint = TextDim, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Nothing queued", color = TextPri, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "Pick a video, choose a target size,\ncompress it entirely on this device.",
            color = TextSec, fontSize = 12.5.sp, lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobCard(
    job: Job,
    onRemove: () -> Unit,
    onPresetChange: (Preset) -> Unit,
    onToggleExpand: () -> Unit,
    onSettings: (Settings) -> Unit,
    onResult: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardCol)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .animateContentSize()
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoThumb(job.uri, Modifier.size(62.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.1).sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    settingsSummary(job),
                    color = TextSec, fontSize = 11.5.sp, letterSpacing = 0.2.sp,
                )
                EstimateRow(job)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(job.status)
                Spacer(Modifier.height(2.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Close, null, tint = TextDim, modifier = Modifier.size(15.dp))
                }
            }
        }

        when (job.status) {
            JobStatus.QUEUED, JobStatus.FAILED -> {
                if (job.status == JobStatus.FAILED) {
                    Text(job.error ?: "Compression failed", color = TextSec, fontSize = 12.sp)
                    androidx.compose.material3.TextButton(onClick = onToggleExpand) { Text("Edit settings", color = TextPri) }
                }
                if (!job.expanded) {
                    Spacer(Modifier.height(12.dp))
                    PresetPicker(job.preset, onPresetChange)
                }
                Spacer(Modifier.height(8.dp))
                ExpandHeader(job.expanded, onToggleExpand)
                if (job.expanded) {
                    SettingsPanel(job.settings, onSettings)
                }
            }
            JobStatus.RUNNING -> {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = ChipBg,
                )
                Spacer(Modifier.height(7.dp))
                Text("COMPRESSING  ${(job.progress * 100).toInt()}%",
                    color = TextSec, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp)
            }
            JobStatus.DONE -> {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(ChipBg)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmtSize(job.sizeBytes)}  →  ${fmtSize(job.outSizeBytes ?: 0)}",
                            color = TextPri, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        val saved = 1f - (job.outSizeBytes ?: 0).toFloat() /
                                job.sizeBytes.coerceAtLeast(1)
                        Text("SAVED ${(saved * 100).toInt()}%",
                            color = TextSec, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                            letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    IconButton(onClick = { shareVideo(context, job) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Share, "Share", tint = TextPri, modifier = Modifier.size(16.dp))
                    }
                }
                androidx.compose.material3.TextButton(onClick = onResult) {
                    Text("Save / share", color = TextPri)
                }
            }

        }
    }
}

@Composable
fun EstimateRow(job: Job) {
    val est = remember(job.settings, job.meta) { estimateOutBytes(job.meta, job.settings) }
    if (est != null && job.status == JobStatus.QUEUED) {
        Text(
            "EST ~${fmtSize(est)}",
            color = TextDim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.3.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
fun RenderWindow(onAbort: () -> Unit) {
    val progress = RenderBus.progress.floatValue
    val outBytes = RenderBus.outBytes.longValue
    val encFps = RenderBus.encodeFps.floatValue
    val frame = RenderBus.frameBmp.value
    val name = RenderBus.activeName.value ?: "…"
    val ix = RenderBus.batchIx.intValue
    val total = RenderBus.batchTotal.intValue

    // ETA: remaining source seconds over observed encode speed; em dash while warming up
    val etaTxt = if (progress > 0.02f && RenderBus.elapsedSeconds.floatValue > 0f) {
        val etaS = ((1f - progress) * RenderBus.elapsedSeconds.floatValue / progress).toLong()
        if (etaS >= 3600) "%d:%02d:%02d".format(etaS / 3600, etaS % 3600 / 60, etaS % 60)
        else "%d:%02d".format(etaS / 60, etaS % 60)
    } else "—"

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .clickable(enabled = false) { }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RENDERING ${ix}/$total", color = TextDim, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
                    modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onAbort,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF2A0A0A), CircleShape)
                        .border(1.dp, Color(0xFFB3261E), CircleShape),
                ) {
                    Icon(Icons.Filled.Close, "Abort", tint = Color(0xFFFF5A52),
                        modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(name, color = TextPri, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(20.dp))

            // viewfinder — source preview at the current encode timestamp
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF101010))
                    .border(1.dp, Line, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    Image(frame.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())
                    Text(
                        "SOURCE PREVIEW · T+${(progress * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                } else {
                    Icon(Icons.Filled.Movie, null, tint = TextDim, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(22.dp))

            // big percent + stats
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(progress * 100).toInt()}", color = TextPri, fontSize = 46.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
                Text("%", color = TextDim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 9.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Stat("ETA", etaTxt)
                    Spacer(Modifier.height(7.dp))
                    Stat("ENC FPS", if (encFps >= 10f) "%.0f".format(encFps) else "%.1f".format(encFps))
                }
            }

            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color.White,
                trackColor = Color(0xFF1C1C1C),
            )

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth()) {
                Stat("OUT", fmtSize(outBytes), Modifier.weight(1f))
                Stat("MODE", "VPU · ${RenderBus.encoderLabel.value}", Modifier.weight(1f))
                Stat("WEB OPT", if (RenderBus.webOpt) "ON" else "OFF", Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = TextDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp)
        Text(value, color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun VideoThumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var durMs by remember(uri) { mutableStateOf<Long?>(null) }

    LaunchedEffect(uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            bmp = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
        } finally {
            mmr.release()
        }
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF151515)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.Movie, null, tint = TextDim, modifier = Modifier.size(20.dp))
        }
        durMs?.let {
            Text(
                fmtDur(it),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

fun settingsSummary(job: Job): String = buildString {
    append(fmtSize(job.sizeBytes))
    val s = job.settings
    append("  ·  ${resLabel(s.resolution)}")
    s.fps?.let { append(" · ${fpsLabel(it)}fps") }
    append(" · ${s.encoder.label}")
    append(" · ")
    append(fmtVbr(s.vbrMbps))
}

fun fmtVbr(v: Float): String =
    if (v >= 1f) "%.1f Mbps".format(v) else "%.0f kbps".format(v * 1000)

@Composable
fun ExpandHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) "LESS OPTIONS" else "MORE OPTIONS",
            color = TextSec, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.KeyboardArrowDown, null, tint = TextSec,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}

@Composable
fun SettingsPanel(s: Settings, onChange: (Settings) -> Unit) {
    Column(
        Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(ChipBg)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        WebOptimizeRow(s.webOptimize) { onChange(s.copy(webOptimize = it)) }
        ThinDivider()
        OptionRow(
            title = "Resolution Limit",
            options = RES_OPTIONS,
            label = ::resLabel,
            current = s.resolution,
        ) { onChange(s.copy(resolution = it)) }
        ThinDivider()
        OptionRow(
            title = "Framerate",
            options = FPS_OPTIONS,
            label = ::fpsLabel,
            current = s.fps,
        ) { onChange(s.copy(fps = it)) }
        ThinDivider()
        OptionRow(
            title = "Encoder",
            options = remember { availableEncoders() },
            label = { it.label },
            current = s.encoder,
        ) { onChange(s.copy(encoder = it)) }
        ThinDivider()
        RateControl(s, onChange)
        EncodingEffortControl(s, onChange)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun ThinDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Line)
    )
}

@Composable
fun WebOptimizeRow(checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Web Optimized", color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Start playback before the download finishes", color = TextDim, fontSize = 8.5.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = Color(0xFF101010),
                uncheckedBorderColor = Line,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionRow(
    title: String,
    options: List<T>,
    label: (T) -> String,
    current: T,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .clickable { open = !open }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label(current),
                    color = TextPri, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = TextSec,
                    modifier = Modifier.size(13.dp))
            }
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { opt ->
                    val sel = opt == current
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Color.White else Color(0xFF101010))
                            .border(1.dp, if (sel) Color.White else Line, RoundedCornerShape(8.dp))
                            .clickable { onPick(opt); open = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(label(opt),
                            color = if (sel) Color.Black else TextPri,
                            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun RateControl(s: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        // Hardware constant-quality support varies, so use average bitrate.
        Row(Modifier.padding(top = 14.dp)) {
            Text("Bitrate", color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(fmtVbr(s.vbrMbps), color = TextPri, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
        }
        MonoSlider(
            value = s.vbrMbps,
            onValueChange = { onChange(s.copy(vbrMbps = it)) },
            valueRange = 0.25f..40f,
        )
        Row(Modifier.padding(top = 2.dp)) {
            Text("Smaller file", color = TextDim, fontSize = 8.5.sp, letterSpacing = 0.sp,
                modifier = Modifier.weight(1f))
            Text("Higher bitrate", color = TextDim, fontSize = 8.5.sp, letterSpacing = 0.sp)
        }
    }
}

@Composable
fun MonoSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color(0xFF202020),
        ),
    )
}

@Composable
fun PresetPicker(current: Preset, onChange: (Preset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ChipBg, RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Preset.entries.forEach { p ->
            val sel = p == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Color.White else Color.Transparent)
                    .clickable { onChange(p) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.label, color = if (sel) Color.Black else TextPri,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    Text(p.detail, color = if (sel) Color.Black.copy(alpha = 0.6f) else TextSec,
                        fontSize = 10.sp, letterSpacing = 0.2.sp)
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: JobStatus) {
    val (text, icon) = when (status) {
        JobStatus.QUEUED -> "Queued" to Icons.Filled.Schedule
        JobStatus.RUNNING -> "Working" to Icons.Filled.Compress
        JobStatus.DONE -> "Done" to Icons.Filled.CheckCircle
        JobStatus.FAILED -> "Failed" to Icons.Filled.ErrorOutline
    }
    Row(
        Modifier
            .background(ChipBg, RoundedCornerShape(999.dp))
            .border(1.dp, Line, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = TextPri, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = TextPri, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp)
    }
}

@Composable
fun EncodingEffortControl(s: Settings, onChange: (Settings) -> Unit) {
    val supported = remember(s.encoder) { encodingEffortSupported(s.encoder.mime) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text("Encoding speed", color = TextPri, fontSize = 13.sp)
        Text(if (supported) "Higher quality may take longer. Speed depends on your phone." else
            "This encoder runs at a fixed speed.", color = TextSec, fontSize = 11.sp)
        if (supported) {
            TextButton(onClick = { onChange(s.copy(encodingEffort = null)) }) {
                Text(if (s.encodingEffort == null) "Device default ✓" else "Use device default")
            }
            Slider(value = s.encodingEffort ?: 0.5f, onValueChange = { onChange(s.copy(encodingEffort = it)) }, valueRange = 0f..1f)
            Row(Modifier.fillMaxWidth()) {
                Text("Faster", color = TextSec, modifier = Modifier.weight(1f))
                Text("Higher quality", color = TextSec)
            }
        } else if (s.encodingEffort != null) TextButton(onClick = { onChange(s.copy(encodingEffort = null)) }) { Text("Use device default") }
    }
}
