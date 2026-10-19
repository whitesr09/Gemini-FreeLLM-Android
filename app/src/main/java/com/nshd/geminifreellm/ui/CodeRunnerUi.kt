package com.nshd.geminifreellm.ui

import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nshd.geminifreellm.data.JsRunResult

/** Inline console showing JS run output or the error, with copy and dismiss actions. */
@Composable
fun RunConsole(running: Boolean, result: JsRunResult?, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Surface(modifier, shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    result is JsRunResult.Success -> Icon(Icons.Outlined.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    else -> Icon(Icons.Outlined.ErrorOutline, null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(Design.small))
                Text(if (running) "Running…" else "Output", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                val output = (result as? JsRunResult.Success)?.output.orEmpty()
                if (!running && output.isNotBlank()) IconButton(
                    onClick = { clipboard.setText(AnnotatedString(output)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Copy output", Modifier.size(16.dp))
                }
                if (!running) IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, "Clear output", Modifier.size(16.dp))
                }
            }
            if (!running && result != null) {
                val text = when (result) {
                    is JsRunResult.Success -> buildString {
                        if (result.output.isNotBlank()) append(result.output)
                        if (result.returned != null) {
                            if (isNotEmpty()) append('\n')
                            append("→ ${result.returned}")
                        }
                        if (isEmpty()) append("(no output)")
                    }
                    is JsRunResult.Failure ->
                        (if (result.line > 0) "Line ${result.line}: " else "") + result.message
                }
                Text(text, Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (result is JsRunResult.Failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Full-screen in-app preview for HTML code, with JavaScript enabled. */
@Composable
fun HtmlPreviewDialog(html: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(Design.medium), shape = Design.card,
            color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.padding(start = Design.medium), verticalAlignment = Alignment.CenterVertically) {
                    Text("Preview", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close preview") }
                }
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.textZoom = 100
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            tag = html
                            loadDataWithBaseURL("https://preview.local/", html, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    update = { webView ->
                        if (webView.tag != html) {
                            webView.tag = html
                            webView.loadDataWithBaseURL("https://preview.local/", html, "text/html", "UTF-8", null)
                        }
                    }
                )
            }
        }
    }
}
