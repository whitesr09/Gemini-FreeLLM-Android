package com.nshd.geminifreellm.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun motionEnabled() = !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()

private data class OrbOrbit(val tilt: Float, val rx: Float, val ry: Float, val speed: Float, val phase: Float)

/**
 * Dotted "thinking orb": bright particles traveling tilted elliptical orbits
 * over depth-faded ghost dots. Plain 2D canvas, monochrome, cheap on low-end
 * devices. Original implementation inspired by the thinking-orbs concept
 * (MIT, Jakub Antalik).
 *
 * @param size orb diameter; 22.dp suits inline chat rows, 56.dp suits empty states.
 */
@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animate = motionEnabled()
    // A cancellable animation loop stops completely when reduced motion is enabled.
    val clock = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (animate) {
            clock.snapTo(0f)
            while (true) {
                clock.animateTo(1f, animationSpec = tween(4200, easing = LinearEasing))
                clock.snapTo(0f)
            }
        } else {
            clock.snapTo(0.6f)
        }
    }
    val t = clock.value
    // Precompute in px: inside the Canvas draw scope, `size` means the canvas Size and would shadow the Dp param.
    val px = with(LocalDensity.current) { size.toPx() }
    Canvas(modifier.size(size)) {
        val cx = px / 2f
        val cy = px / 2f
        val radius = px / 2f
        // Three tilted orbits at different speeds; negative speed = counter-rotation.
        val orbits = listOf(
            OrbOrbit(tilt = -0.55f, rx = 0.92f, ry = 0.40f, speed = 1f, phase = 0f),
            OrbOrbit(tilt = 0.50f, rx = 0.92f, ry = 0.40f, speed = -0.7f, phase = 2.1f),
            OrbOrbit(tilt = 1.30f, rx = 0.92f, ry = 0.40f, speed = 0.55f, phase = 4.2f),
        )
        orbits.forEach { orbit ->
            val cosTilt = cos(orbit.tilt)
            val sinTilt = sin(orbit.tilt)
            fun point(angle: Float): Triple<Float, Float, Float> {
                val ex = cos(angle) * orbit.rx * radius
                val ey = sin(angle) * orbit.ry * radius
                val depth = sin(angle) * 0.5f + 0.5f // 0 = far side, 1 = near side
                return Triple(cx + ex * cosTilt - ey * sinTilt, cy + ex * sinTilt + ey * cosTilt, depth)
            }
            // Ghost dots tracing the ring, fading with depth.
            val dots = 16
            repeat(dots) { i ->
                val (x, y, depth) = point((i / dots.toFloat()) * 2f * PI.toFloat())
                drawCircle(color.copy(alpha = 0.16f + depth * 0.5f),
                    radius = px * 0.026f * (0.7f + depth * 0.6f), center = Offset(x, y))
            }
            // Bright traveler particle.
            val (x, y, depth) = point(t * 2f * PI.toFloat() * orbit.speed + orbit.phase)
            drawCircle(color.copy(alpha = 0.55f + depth * 0.45f), radius = px * 0.055f, center = Offset(x, y))
        }
    }
}

@Composable
fun ThinkingIndicator() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    Row(Modifier.heightIn(min = 36.dp).clearAndSetSemantics { contentDescription = "Thinking"; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.medium)) {
        ThinkingOrb(Modifier, 22.dp)
        Text(if (seconds < 10) "Thinking…" else "Thinking · ${seconds}s", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StreamingContent(text: String, streaming: Boolean, onCanvas: (String, String) -> Unit) {
    // Reasoning models wrap chain-of-thought in <think>…</think>; DeepSeek-style:
    // collapse it behind a "Thinking / Thought" toggle instead of dumping it as a reply.
    val (body, think, thinkOpen) = remember(text) { splitThink(text) }
    Column(verticalArrangement = Arrangement.spacedBy(Design.tiny)) {
        if (think.isNotEmpty() || thinkOpen) ThinkBlock(think, thinkOpen)
        // A short catch-up smooths network bursts; completed and long replies render immediately.
        val animated = motionEnabled() && streaming && body.length < 16_000
        val count by animateIntAsState(body.length, animationSpec = if (animated) tween(80, easing = LinearEasing) else snap(), label = "Streaming text")
        var end = if (animated) count.coerceIn(0, body.length) else body.length
        if (end in 1 until body.length && body[end - 1].isHighSurrogate()) end--
        MessageContent(body.substring(0, end), onCanvas)
        if (streaming) {
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(8.dp).clearAndSetSemantics { }) { drawCircle(color) }
        }
    }
}

/** Splits "<think>…</think>" reasoning out of assistant text. Returns (body, think, thinkStillOpen). */
internal fun splitThink(text: String): Triple<String, String, Boolean> {
    val body = StringBuilder()
    val thoughts = StringBuilder()
    var cursor = 0
    var stillOpen = false
    while (true) {
        val start = text.indexOf("<think>", cursor)
        if (start < 0) {
            body.append(text.substring(cursor))
            break
        }
        body.append(text.substring(cursor, start))
        val contentStart = start + "<think>".length
        val end = text.indexOf("</think>", contentStart)
        if (end < 0) {
            if (thoughts.isNotEmpty()) thoughts.append('\n')
            thoughts.append(text.substring(contentStart).trimStart())
            stillOpen = true
            break
        }
        if (thoughts.isNotEmpty()) thoughts.append('\n')
        thoughts.append(text.substring(contentStart, end).trim())
        cursor = end + "</think>".length
    }
    return Triple(body.toString().trim(), thoughts.toString(), stillOpen)
}

@Composable
private fun ThinkBlock(think: String, open: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.clickable(onClickLabel = if (expanded) "Collapse reasoning" else "Expand reasoning") { expanded = !expanded }
            .padding(vertical = Design.tiny).clearAndSetSemantics { contentDescription = if (open) "Thinking" else "Thought" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.small)) {
            if (open) ThinkingOrb(Modifier, 18.dp)
            Text(if (open) "Thinking…" else "Thought", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(150)) + expandVertically(tween(150)), exit = fadeOut(tween(120)) + shrinkVertically(tween(120))) {
            Text(think.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Design.small, bottom = Design.small))
        }
    }
}
