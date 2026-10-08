package com.xyether.handbrake

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun DoneDialog(job: Job, onDismiss: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardCol,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Compression finished", color = TextPri, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(job.name, color = TextSec, fontSize = 12.5.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val saved = 1f - (job.outSizeBytes ?: 0).toFloat() / job.sizeBytes.coerceAtLeast(1)
                Text(
                    "${fmtSize(job.sizeBytes)}  →  ${fmtSize(job.outSizeBytes ?: 0)}   ·   SAVED ${(saved * 100).toInt()}%",
                    color = TextSec, fontSize = 11.5.sp,
                )
                if (job.savedToDevice) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DownloadDone, null, tint = TextPri, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Already saved to Movies/XyetherHandBrake",
                            color = TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !job.savedToDevice,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF1C1C1C),
                    disabledContentColor = TextDim,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (job.savedToDevice) "Saved" else "Save to device", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, null, tint = TextSec, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share", color = TextSec, fontSize = 12.sp)
            }
        },
    )
}

@Composable
fun DiscardDialog(name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CardCol,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Discard this video?", color = TextPri, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "The compressed copy of \"$name\" and its render history will be permanently deleted.",
                color = TextSec, fontSize = 12.5.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB3261E), contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Delete", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Keep", color = TextSec, fontSize = 12.sp) }
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardCol) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("CHOOSE VIDEO SOURCE", color = TextDim, fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(14.dp))
            SourceRow("System picker", "All folders · SAF", current, onPick)
            SourceRow("Samsung Gallery", "Photos & videos app", current, onPick)
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun SourceRow(label: String, sub: String, current: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current == label) Color(0xFF1A1A1A) else Color.Transparent)
            .clickable { onPick(label) }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPri, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = TextDim, fontSize = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
        if (current == label) {
            Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@androidx.annotation.RequiresApi(29)
suspend fun saveToDevice(context: Context, job: Job): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val resolver = context.contentResolver
    var destination: Uri? = null
    try {
        val src = File(job.outPath ?: return@withContext false)
        require(src.isFile && src.length() > 0)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XyetherHandBrake")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Cannot create destination")
        destination = uri
        resolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } } ?: error("Cannot open destination")
        values.clear(); values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
        require(resolver.update(uri, values, null, null) > 0)
        true
    } catch (_: Exception) {
        destination?.let { runCatching { resolver.delete(it, null, null) } }
        false
    }
}

fun shareVideo(context: Context, job: Job) {
    val f = File(job.outPath ?: return)
    if (!f.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share compressed video"))
}
