package com.nshd.geminifreellm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Tappable brand badge used in the Settings → About Developer section.
 * The artwork is a Flaticon PNG loaded via DeveloperSocialIcon in
 * SettingsScreen.kt. The 44dp circle keeps the tap target comfortably
 * above the 40dp minimum while the glyph inside stays visually small.
 */
@Composable
fun BrandIconButton(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick, onClickLabel = label)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        icon()
    }
}
