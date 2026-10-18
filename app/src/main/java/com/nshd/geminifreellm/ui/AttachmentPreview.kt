package com.nshd.geminifreellm.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nshd.geminifreellm.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LocalAttachmentImage(file: Attachment, modifier: Modifier, fullSize: Boolean = false) {
    val image by produceState<ImageBitmap?>(null, file.localPath, fullSize) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.localPath, bounds)
            var sample = 1
            val maxSize = if (fullSize) 1600 else 128
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            BitmapFactory.decodeFile(file.localPath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    if (image != null) Image(image!!, contentDescription = if (fullSize) file.name else null, modifier = modifier,
        contentScale = if (fullSize) ContentScale.Fit else ContentScale.Crop)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, if (fullSize) "Image unavailable" else null) }
}

@Composable
fun AttachmentPreview(file: Attachment, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = Design.page), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close attachment") }
                }
                if (file.isImage) LocalAttachmentImage(file, Modifier.fillMaxSize().padding(Design.medium), fullSize = true)
                else SelectionContainer {
                    Text(file.text, Modifier.verticalScroll(rememberScrollState()).padding(Design.page), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
