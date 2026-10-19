package com.nshd.geminifreellm.ui

import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nshd.geminifreellm.ChatViewModel
import com.nshd.geminifreellm.model.AppSettings
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import java.util.Locale

private val JarvisInk = Color(0xFF32152E)
private val JarvisMuted = Color(0xFF9B6E88)
private val JarvisPink = Color(0xFFFF5D86)
private val JarvisCoral = Color(0xFFFF886B)
private val JarvisLilac = Color(0xFFB9A0F6)
private val JarvisCream = Color(0xFFFFF0E9)

/** Dedicated Jarvis home, opened from the chat toolbar. */
@Composable
fun JarvisScreen(
    settings: AppSettings,
    vm: ChatViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onChat: () -> Unit
) {
    val context = LocalContext.current
    val speechEngine = remember { mutableStateOf<TextToSpeech?>(null) }
    val speechReady = remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            speechReady.value = status == TextToSpeech.SUCCESS
            if (status == TextToSpeech.SUCCESS) {
                speechEngine.value?.language = Locale.getDefault()
            }
        }
        speechEngine.value = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            speechEngine.value = null
            speechReady.value = false
        }
    }
    val infinite = rememberInfiniteTransition(label = "jarvis-orb")
    val rotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = FastOutSlowInEasing)), label = "orb-rotation"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(if (settings.reducedMotion) 1 else 2_200), repeatMode = RepeatMode.Reverse),
        label = "orb-pulse"
    )
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("Tap the microphone to speak") }
    val voiceController = remember(context) {
        JarvisVoiceController(
            context = context,
            onListeningChanged = { isListening = it },
            onTranscript = { transcript ->
                if (speechReady.value) {
                    speechEngine.value?.speak("Got it. Processing your request.", TextToSpeech.QUEUE_FLUSH, null, "jarvis-acknowledgement")
                }
                handleVoiceTranscript(context, transcript, { spokenText ->
                    vm.draft(spokenText)
                    onChat()
                }, vm::notice)
            },
            onMessage = { voiceStatus = it }
        )
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceController.startListening()
        else {
            voiceStatus = "Microphone permission is needed for voice control"
            vm.notice("Allow microphone access to use Jarvis voice control.")
        }
    }
    DisposableEffect(voiceController) {
        onDispose { voiceController.release() }
    }
    val startVoice = {
        if (isListening) {
            voiceController.cancelListening()
            voiceStatus = "Voice input cancelled"
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voiceController.startListening()
        } else {
            microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    BackHandler(onBack = onBack)

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFFFFF1EA), Color(0xFFFFE8E3), Color(0xFFFFF0F1)))
        )
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                JarvisIconButton("Back to chat", onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = JarvisInk)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Jarvis", fontSize = 34.sp, fontWeight = FontWeight.Light, color = JarvisInk, letterSpacing = (-1.8).sp)
                    Text("YOUR PERSONAL AI", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = JarvisMuted, letterSpacing = 2.2.sp)
                }
                JarvisIconButton("Jarvis settings", onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, null, tint = JarvisInk)
                }
            }

            Spacer(Modifier.weight(0.55f))
            Text("Welcome back", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = JarvisInk)
            Text("What can I do for you?", fontSize = 15.sp, color = JarvisMuted, modifier = Modifier.padding(top = 5.dp))

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().weight(2.6f).semantics { contentDescription = "Jarvis animated AI orb" },
                contentAlignment = Alignment.Center
            ) {
                JarvisOrb(rotation = rotation, pulse = if (settings.reducedMotion) 1f else pulse, reducedMotion = settings.reducedMotion)
                Surface(
                    onClick = startVoice,
                    modifier = Modifier.size(82.dp).shadow(10.dp, CircleShape),
                    shape = CircleShape,
                    color = Color(0xFFFFF8F5),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.9f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (isListening) Icons.Outlined.Waves else Icons.Outlined.Mic, if (isListening) "Stop listening" else "Talk to Jarvis", tint = JarvisInk, modifier = Modifier.size(32.dp))
                    }
                }
            }

            Surface(
                onClick = startVoice,
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.68f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8B9C5)),
                modifier = Modifier.semantics { contentDescription = "Start Jarvis voice command" }
            ) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(JarvisPink))
                    Spacer(Modifier.width(10.dp))
                    Text(if (isListening) "TAP TO STOP LISTENING" else "TAP TO TALK TO JARVIS", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = JarvisMuted)
                }
            }
            Text(voiceStatus, fontSize = 12.sp, color = JarvisMuted, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)
            Text("Try “Open YouTube” or ask a question", fontSize = 12.sp, color = JarvisMuted, modifier = Modifier.padding(top = 5.dp))

            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.82f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCC0C6)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFFFE2E8)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Waves, null, tint = JarvisPink)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (settings.active.apiKey.isBlank()) "Connect your AI" else "Ready when you are",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = JarvisInk)
                        Text(if (settings.active.apiKey.isBlank()) "Add your API key in Settings to ask AI questions." else "Voice commands and chat are ready to use.",
                            fontSize = 12.sp, color = JarvisMuted, modifier = Modifier.padding(top = 3.dp))
                    }
                    if (settings.active.apiKey.isBlank()) {
                        TextButton(onClick = onSettings) { Text("Setup", color = JarvisPink, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JarvisQuickAction(
                    title = "Chat",
                    subtitle = "Ask anything",
                    modifier = Modifier.weight(1f),
                    onClick = onChat
                ) { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = JarvisCoral) }
                JarvisQuickAction(
                    title = "Settings",
                    subtitle = "Configure AI",
                    modifier = Modifier.weight(1f),
                    onClick = onSettings
                ) { Icon(Icons.Outlined.AutoAwesome, null, tint = JarvisCoral) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun JarvisIconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8B9C5)),
        modifier = Modifier.size(54.dp).semantics { contentDescription = label }
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun JarvisQuickAction(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCC0C6))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFE7DF)), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(title, color = JarvisInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = JarvisMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun JarvisOrb(rotation: Float, pulse: Float, reducedMotion: Boolean) {
    val dots = remember { List(1_050) { index ->
        val random = Random(index * 7919 + 17)
        val angle = random.nextFloat() * (Math.PI * 2).toFloat()
        val radius = kotlin.math.sqrt(random.nextFloat())
        Triple(angle, radius, random.nextFloat())
    } }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.34f * pulse
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD5E2).copy(alpha = 0.65f), Color(0xFFF7B5D1).copy(alpha = 0.12f), Color.Transparent), center, radius * 1.55f), radius * 1.55f, center)
        rotate(if (reducedMotion) 0f else rotation, center) {
            drawCircle(Color(0xFFDDA6CF).copy(alpha = 0.65f), radius * 1.27f, center, style = Stroke(width = 1.2.dp.toPx()))
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, JarvisLilac, JarvisPink, Color.Transparent), center),
                startAngle = 24f, sweepAngle = 125f, useCenter = false,
                topLeft = Offset(center.x - radius * 1.38f, center.y - radius * 1.38f),
                size = androidx.compose.ui.geometry.Size(radius * 2.76f, radius * 2.76f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, JarvisCoral, Color(0xFFF8B8D0), Color.Transparent), center),
                startAngle = 198f, sweepAngle = 94f, useCenter = false,
                topLeft = Offset(center.x - radius * 1.48f, center.y - radius * 1.48f),
                size = androidx.compose.ui.geometry.Size(radius * 2.96f, radius * 2.96f),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF9FA), Color(0xFFFFDCE8), Color(0xFFF8B7D1)), center, radius), radius, center)
        dots.forEach { (angle, distance, opacity) ->
            val x = center.x + cos(angle) * radius * distance
            val y = center.y + sin(angle) * radius * distance
            val color = if (opacity > 0.84f) JarvisCoral else if (opacity > 0.48f) JarvisPink else Color(0xFFC57AB4)
            drawCircle(color.copy(alpha = 0.2f + opacity * 0.52f), (0.45f + opacity * 1.1f).dp.toPx(), Offset(x, y))
        }
        drawCircle(Color.White.copy(alpha = 0.5f), radius * 0.92f, center, style = Stroke(width = 0.8.dp.toPx()))
    }
}
