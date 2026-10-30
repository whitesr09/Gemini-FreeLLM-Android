package com.nshd.geminifreellm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Tappable brand badge used in the navigation drawer's Developer section. */
@Composable
fun BrandIconButton(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick, onClickLabel = label)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        icon()
    }
}

/** Instagram: gradient rounded square with a white camera glyph. */
@Composable
fun InstagramBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5))),
            cornerRadius = CornerRadius(s * 0.24f, s * 0.24f))
        val stroke = s * 0.075f
        drawRoundRect(Color.White, topLeft = Offset(s * 0.27f, s * 0.27f), size = Size(s * 0.46f, s * 0.46f),
            cornerRadius = CornerRadius(s * 0.12f, s * 0.12f), style = Stroke(stroke))
        drawCircle(Color.White, radius = s * 0.105f, center = center, style = Stroke(stroke))
        drawCircle(Color.White, radius = s * 0.048f, center = Offset(s * 0.655f, s * 0.345f))
    }
}

/** WhatsApp: green circle with a white handset. */
@Composable
fun WhatsAppBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(Color(0xFF25D366))
        drawArc(Color.White, startAngle = 60f, sweepAngle = 240f, useCenter = false,
            topLeft = Offset(s * 0.24f, s * 0.24f), size = Size(s * 0.52f, s * 0.52f),
            style = Stroke(s * 0.105f, cap = StrokeCap.Round))
    }
}

/** Telegram: blue circle with a white paper plane. */
@Composable
fun TelegramBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(Color(0xFF229ED9))
        val plane = Path().apply {
            moveTo(s * 0.78f, s * 0.30f)
            lineTo(s * 0.24f, s * 0.48f)
            lineTo(s * 0.44f, s * 0.55f)
            lineTo(s * 0.51f, s * 0.74f)
            lineTo(s * 0.57f, s * 0.565f)
            close()
        }
        drawPath(plane, Color.White)
    }
}

/** Email: red rounded square with a white envelope glyph. */
@Composable
fun EmailBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(Color(0xFFEA4335), cornerRadius = CornerRadius(s * 0.24f, s * 0.24f))
        val stroke = s * 0.07f
        drawRoundRect(Color.White, topLeft = Offset(s * 0.23f, s * 0.33f), size = Size(s * 0.54f, s * 0.38f),
            cornerRadius = CornerRadius(s * 0.06f, s * 0.06f), style = Stroke(stroke))
        val flap = Path().apply {
            moveTo(s * 0.25f, s * 0.375f)
            lineTo(s * 0.5f, s * 0.55f)
            lineTo(s * 0.75f, s * 0.375f)
        }
        drawPath(flap, Color.White, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Phone: blue circle with a white handset. */
@Composable
fun PhoneBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(Color(0xFF0A84FF))
        drawArc(Color.White, startAngle = 45f, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(s * 0.24f, s * 0.24f), size = Size(s * 0.52f, s * 0.52f),
            style = Stroke(s * 0.105f, cap = StrokeCap.Round))
    }
}
