package com.seyal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.seyal.app.data.StepStatus
import com.seyal.app.ui.theme.Ink

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground
    Box(
        Modifier.height(36.dp).background(bg, shape).border(1.dp, if (selected) bg else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun StatusPill(status: StepStatus) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        StepStatus.DONE -> Pill("done", cs.primaryContainer, cs.onPrimaryContainer)
        StepStatus.RUNNING -> Pill("running", cs.primaryContainer, cs.onPrimaryContainer)
        StepStatus.PENDING -> Pill("queued", cs.surfaceVariant, cs.onSurfaceVariant)
        StepStatus.WAITING_APPROVAL -> Pill("asks you", cs.secondaryContainer, cs.onSecondaryContainer)
        StepStatus.WAITING_CHOICE -> Pill("choose", cs.secondaryContainer, cs.onSecondaryContainer)
        StepStatus.WAITING_RETURN -> Pill("your tap", cs.secondaryContainer, cs.onSecondaryContainer)
        StepStatus.FAILED -> Pill("failed", cs.errorContainer, cs.onErrorContainer)
        StepStatus.SKIPPED -> Pill("skipped", cs.surfaceVariant, cs.onSurfaceVariant)
        StepStatus.CANCELLED -> Pill("cancelled", cs.surfaceVariant, cs.onSurfaceVariant)
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(width = 64.dp, height = 16.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.height(44.dp).background(if (enabled) Ink.green else MaterialTheme.colorScheme.surfaceVariant, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.height(44.dp).background(MaterialTheme.colorScheme.surface, shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center,
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
fun SmallButton(text: String, filled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val bg = when { danger -> Ink.red; filled -> Ink.green; else -> MaterialTheme.colorScheme.surface }
    val fg = if (filled || danger) Color.White else MaterialTheme.colorScheme.onBackground
    Box(
        Modifier.height(32.dp).background(bg, shape).border(1.dp, if (filled || danger) bg else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelMedium) }
}
