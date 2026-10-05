package com.nshd.geminifreellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text

@Composable
fun AppDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
    val c = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(18.dp).background(c.surface, RoundedCornerShape(18.dp)).border(1.dp, c.border, RoundedCornerShape(18.dp))) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = c.text, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    Text("×", color = c.muted, fontSize = 26.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 6.dp))
                }
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        }
    }
}

@Composable
fun AppButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Box(modifier.fillMaxWidth().height(44.dp).background(if (enabled) c.accent else c.elevated, RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else c.muted, fontSize = 14.sp)
    }
}

@Composable
fun AppTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Box(modifier.height(40.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) c.accent else c.muted, fontSize = 14.sp)
    }
}

@Composable
fun AppField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1, password: Boolean = false) {
    val c = LocalAppColors.current
    Column(modifier) {
        Text(label, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(12.dp)).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (password) VisualTransformation.Password else VisualTransformation.None
        )
    }
}
