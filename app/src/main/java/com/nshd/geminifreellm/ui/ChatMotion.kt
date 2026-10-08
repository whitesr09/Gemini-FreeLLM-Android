package com.nshd.geminifreellm.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sin

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun motionEnabled() = !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()

@Composable
fun ThinkingIndicator() {
    val animate = motionEnabled()
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "Thinking dots")
        transition.animateFloat(0f, 6.283185f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "Thinking wave").value
    } else 0f
    val color = MaterialTheme.colorScheme.primary
    Row(Modifier.heightIn(min = 36.dp).clearAndSetSemantics { contentDescription = "Thinking"; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.medium)) {
        Canvas(Modifier.size(width = 32.dp, height = 20.dp)) {
            repeat(3) { index ->
                val wave = if (animate) (sin(phase - index * 0.8f) + 1f) / 2f else 0.6f
                drawCircle(color.copy(alpha = 0.4f + wave * 0.6f), 2.7.dp.toPx(),
                    Offset((5 + index * 11).dp.toPx(), size.height / 2 - wave * 3.dp.toPx()))
            }
        }
        Text(if (seconds < 10) "Thinking…" else "Thinking · ${seconds}s", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StreamingContent(text: String, streaming: Boolean, onCanvas: (String, String) -> Unit) {
    // A short catch-up smooths network bursts; completed and long replies render immediately.
    val animated = motionEnabled() && streaming && text.length < 16_000
    val count by animateIntAsState(text.length, animationSpec = if (animated) tween(80, easing = LinearEasing) else snap(), label = "Streaming text")
    var end = if (animated) count.coerceIn(0, text.length) else text.length
    if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end--
    Column(verticalArrangement = Arrangement.spacedBy(Design.tiny)) {
        MessageContent(text.substring(0, end), onCanvas)
        if (streaming) {
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(8.dp).clearAndSetSemantics { }) { drawCircle(color) }
        }
    }
}
