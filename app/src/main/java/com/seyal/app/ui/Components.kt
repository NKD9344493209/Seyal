package com.seyal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.seyal.app.data.CallStatus
import com.seyal.app.data.SessionStatus
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
fun StatusPill(status: CallStatus) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        CallStatus.DONE -> Pill("done", cs.primaryContainer, cs.onPrimaryContainer)
        CallStatus.RUNNING -> Pill("running", cs.primaryContainer, cs.onPrimaryContainer)
        CallStatus.PENDING -> Pill("queued", cs.surfaceVariant, cs.onSurfaceVariant)
        CallStatus.WAITING_APPROVAL -> Pill("asks you", cs.secondaryContainer, cs.onSecondaryContainer)
        CallStatus.WAITING_USER -> Pill("question", cs.secondaryContainer, cs.onSecondaryContainer)
        CallStatus.FAILED -> Pill("failed", cs.errorContainer, cs.onErrorContainer)
        CallStatus.DENIED -> Pill("you said no", cs.surfaceVariant, cs.onSurfaceVariant)
        CallStatus.CANCELLED -> Pill("stopped", cs.surfaceVariant, cs.onSurfaceVariant)
    }
}

@Composable
fun SessionPill(status: SessionStatus) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        SessionStatus.DONE -> Pill("done", cs.primaryContainer, cs.onPrimaryContainer)
        SessionStatus.RUNNING -> Pill("working", cs.primaryContainer, cs.onPrimaryContainer)
        SessionStatus.WAITING -> Pill("needs you", cs.secondaryContainer, cs.onSecondaryContainer)
        SessionStatus.FAILED -> Pill("failed", cs.errorContainer, cs.onErrorContainer)
        SessionStatus.CANCELLED -> Pill("stopped", cs.surfaceVariant, cs.onSurfaceVariant)
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


/** "**bold**" → bold. An unbalanced pair is left as plain text. */
fun boldSpans(s: String): AnnotatedString {
    val clean = s.replace("`", "")
    val parts = clean.split("**")
    if (parts.size < 3 || parts.size % 2 == 0) return AnnotatedString(clean.replace("**", ""))
    return buildAnnotatedString {
        parts.forEachIndexed { i, p ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p) } else append(p)
        }
    }
}

/** The agent's answers: paragraphs, "- " bullets, "1." lists, "#" headings and **bold**. */
@Composable
fun Rich(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onBackground) {
    val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in lines) {
            val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")
            val heading = line.startsWith("#")
            val body = if (bullet) line.substring(2).trim() else if (heading) line.trimStart('#', ' ') else line
            if (bullet) {
                Row {
                    Text("•", style = style, color = color, modifier = Modifier.padding(end = 8.dp))
                    Text(boldSpans(body), style = style, color = color)
                }
            } else {
                Text(boldSpans(body), style = if (heading) MaterialTheme.typography.titleMedium else style, color = color)
            }
        }
    }
}
