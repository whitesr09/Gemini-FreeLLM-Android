package com.nshd.geminifreellm.ui

import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.model.Attachment
import java.io.File
import kotlin.math.roundToInt

@Composable
fun MediaPreviewDialog(
    attachment: Attachment,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: (() -> Unit)?
) {
    val file = remember(attachment.localPath) { File(attachment.localPath) }
    val isVideo = attachment.mimeType.startsWith("video/") || attachment.kind == Attachment.Kind.GENERATED_VIDEO
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!file.exists()) {
                    Text("This media file is no longer available.")
                } else if (isVideo) {
                    AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                val controller = MediaController(context)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoPath(file.absolutePath)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)
                    )
                } else {
                    val bitmap = remember(file.absolutePath) {
                        BitmapFactory.Options().run {
                            inJustDecodeBounds = true
                            BitmapFactory.decodeFile(file.absolutePath, this)
                            val side = maxOf(outWidth, outHeight).coerceAtLeast(1)
                            var sample = 1
                            while (side / sample > 2048 && sample < 64) sample *= 2
                            inSampleSize = sample
                            inJustDecodeBounds = false
                            BitmapFactory.decodeFile(file.absolutePath, this)
                        }
                    }
                    if (bitmap != null) {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        var offsetY by remember { mutableFloatStateOf(0f) }
                        val transformState = rememberTransformableState { zoom, pan, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = attachment.name,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)
                                .background(LocalAppColors.current.background, RoundedCornerShape(12.dp))
                                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                                .transformable(transformState),
                            contentScale = ContentScale.Fit
                        )
                    } else Text("Unable to decode this image.")
                }
                Text(attachment.mimeType + " · " + formatBytes(attachment.sizeBytes), color = LocalAppColors.current.muted)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSave) { Text("Save") }
                TextButton(onClick = onShare) { Text("Share") }
                onRegenerate?.let { action -> TextButton(onClick = action) { Text("Regenerate") } }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun formatBytes(value: Long): String = when {
    value < 1024 -> value.toString() + " B"
    value < 1024 * 1024 -> (value / 1024f).roundToInt().toString() + " KB"
    value < 1024 * 1024 * 1024L -> (value / (1024f * 1024f)).roundToInt().toString() + " MB"
    else -> (value / (1024f * 1024f * 1024f)).roundToInt().toString() + " GB"
}