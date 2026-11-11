package dev.aravind.assistant

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The app's colors: deep navy with a cyan accent in dark mode, and a matching light mode. */
object Palette {
    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFFA78BFA)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)

    val dark = darkColorScheme(
        primary = Cyan,
        onPrimary = Color(0xFF00222A),
        primaryContainer = Color(0xFF0E3A47),
        onPrimaryContainer = Color(0xFFCFF7FF),
        secondary = Violet,
        onSecondary = Color(0xFF1E1240),
        background = Color(0xFF0B1020),
        onBackground = Color(0xFFE6EAF2),
        surface = Color(0xFF111831),
        onSurface = Color(0xFFE6EAF2),
        surfaceVariant = Color(0xFF1A2442),
        onSurfaceVariant = Color(0xFFAEB8D0),
        outline = Color(0xFF2E3A5E),
        error = Red,
    )

    val light = lightColorScheme(
        primary = Color(0xFF0E7490),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCFF4FC),
        onPrimaryContainer = Color(0xFF083344),
        secondary = Color(0xFF6D28D9),
        onSecondary = Color.White,
        background = Color(0xFFF5F7FB),
        onBackground = Color(0xFF0F172A),
        surface = Color.White,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE8EDF6),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFCBD5E1),
        error = Color(0xFFB91C1C),
    )
}

/** The light or dark choice, shared by every screen so a change shows at once. */
object UiPrefs {
    val light = MutableStateFlow<Boolean?>(null)
}

private val appShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** Colors and shapes only, with no background, for the see-through wake card. */
@Composable
fun AssistantColors(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val chosen by UiPrefs.light.collectAsState()
    val light = chosen ?: Features.lightTheme(context)
    MaterialTheme(colorScheme = if (light) Palette.light else Palette.dark, shapes = appShapes, content = content)
}

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    AssistantColors {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/** A plain-words label for the assistant's status. */
fun statusLabel(status: String): String = when (status) {
    "Ready" -> "Ready"
    "Listening" -> "Listening…"
    "Thinking" -> "Thinking…"
    "Working" -> "Working…"
    "Off" -> "Assistant is off"
    else -> status
}

private enum class OrbMode { Idle, Listening, Thinking, Error, Off }

private fun orbMode(status: String) = when (status) {
    "Listening" -> OrbMode.Listening
    "Thinking", "Working", "Preparing voice" -> OrbMode.Thinking
    "Off" -> OrbMode.Off
    "Ready" -> OrbMode.Idle
    else -> if (status.contains("fail", ignoreCase = true) || status.contains("problem", ignoreCase = true)) OrbMode.Error else OrbMode.Idle
}

/**
 * The animated voice orb. It breathes when idle, ripples with your voice while listening,
 * spins while thinking, and turns red on an error. Tap it to talk.
 */
@Composable
fun VoiceOrb(status: String, diameter: Dp, onTap: () -> Unit) {
    val level by AssistantRuntime.micLevel.collectAsState()
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "spin",
    )
    val colors = MaterialTheme.colorScheme
    val mode = orbMode(status)
    val accent = when (mode) {
        OrbMode.Listening -> colors.primary
        OrbMode.Thinking -> colors.secondary
        OrbMode.Error -> colors.error
        OrbMode.Off -> colors.outline
        OrbMode.Idle -> colors.primary
    }
    val scale = when (mode) {
        OrbMode.Listening -> 1f + level * 0.22f
        OrbMode.Idle -> breath
        else -> 1f
    }
    Canvas(
        Modifier.size(diameter)
            .semantics { contentDescription = "Talk to the assistant. ${statusLabel(status)}" }
            .clickable(onClick = onTap),
    ) {
        val radius = this.size.minDimension / 2f * 0.6f * scale
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), center = center, radius = radius * 1.65f),
            radius = radius * 1.65f,
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), accent, accent.copy(alpha = 0.55f)), center = center, radius = radius),
            radius = radius,
        )
        if (mode == OrbMode.Thinking) {
            rotate(degrees = spin) {
                drawCircle(
                    Brush.sweepGradient(listOf(Color.Transparent, colors.primary, Color.Transparent), center = center),
                    radius = radius * 1.2f,
                    style = Stroke(width = radius * 0.08f),
                )
            }
        }
        if (mode == OrbMode.Listening) {
            for (ring in 1..3) {
                drawCircle(
                    accent.copy(alpha = 0.3f / ring),
                    radius = radius * (1f + 0.16f * ring + level * 0.3f),
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}

/** A small rounded label, used for the app the assistant is working in. */
@Composable
fun AppChip(text: String) {
    Box(
        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** One step of the timeline, like "Doing: Opening WhatsApp", with a colored dot for its kind. */
@Composable
fun StepRow(line: String) {
    val colors = MaterialTheme.colorScheme
    val kind = if (line.contains(": ")) line.substringBefore(": ") else ""
    val text = if (kind.isEmpty()) line else line.substringAfter(": ")
    val color = when (kind) {
        "Thinking" -> colors.secondary
        "Doing" -> colors.primary
        "Found" -> Palette.Green
        "Answer" -> colors.primary
        "Asking", "You said" -> Palette.Amber
        else -> if (line.startsWith("Stopped")) colors.error else colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            if (kind.isNotEmpty()) Text(kind, style = MaterialTheme.typography.labelMedium, color = color)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun isRunning(status: String) = status == "Thinking" || status == "Working" || status == "Listening"

/** The current task: what you asked, the app in use, each step as it happens, and a Stop button. */
@Composable
fun TaskPanel(onStop: () -> Unit) {
    val steps by AssistantRuntime.agentSteps.collectAsState()
    val status by AssistantRuntime.status.collectAsState()
    val app by AssistantRuntime.currentApp.collectAsState()
    val transcript by AssistantRuntime.transcript.collectAsState()
    val running = isRunning(status)
    if (steps.isEmpty() && !running) return
    val request = transcript.lastOrNull { it.startsWith("You: ") }?.removePrefix("You: ").orEmpty()
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running) statusLabel(status) else "Last task",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (request.isNotEmpty()) Text(request, style = MaterialTheme.typography.bodyLarge)
            if (app.isNotEmpty()) AppChip("In $app")
            steps.forEach { StepRow(it) }
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .background(
                    if (mine) colors.primaryContainer else colors.surfaceVariant,
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = if (mine) colors.onPrimaryContainer else colors.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The conversation as chat bubbles: yours on the right, the assistant's on the left. */
@Composable
fun ChatHistory(lines: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Conversation", style = MaterialTheme.typography.titleSmall)
        lines.takeLast(14).forEach { line ->
            when {
                line.startsWith("You: ") -> Bubble(line.removePrefix("You: "), mine = true)
                line.startsWith("Assistant: ") -> Bubble(line.removePrefix("Assistant: "), mine = false)
                else -> Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Type a command instead of speaking it. */
@Composable
fun CommandBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command") },
            maxLines = 3,
            shape = RoundedCornerShape(20.dp),
        )
        Button(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) { Text("Send") }
    }
}

private fun timeText(millis: Long) = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(millis))

@Composable
private fun TaskCard(task: AssistantRuntime.TaskRecord) {
    var expanded by remember(task.time) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(timeText(task.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(task.request, style = MaterialTheme.typography.bodyLarge)
            Text(task.outcome, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (expanded) {
                task.steps.forEach { StepRow(it) }
            } else if (task.steps.isNotEmpty()) {
                Text("${task.steps.size} steps. Tap to see them", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Tasks from this session with their steps, and the tamper-checked action log. */
@Composable
fun HistoryScreen(context: Context) {
    val tasks by AssistantRuntime.taskHistory.collectAsState()
    var log by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tasks", style = MaterialTheme.typography.titleLarge)
        if (tasks.isEmpty()) {
            Text("No tasks yet since the assistant started", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tasks.forEach { TaskCard(it) }
        Text("Action log", style = MaterialTheme.typography.titleLarge)
        Text("Every action the assistant took, protected against tampering", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { log = Audit.view(context) }) { Text(if (log == null) "Show action log" else "Refresh") }
        log?.let { (entries, intact) ->
            Text(
                if (intact) "Log check: intact" else "Warning: the log failed its tamper check",
                color = if (intact) Palette.Green else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
            entries.take(80).forEach { entry ->
                val millis = entry.substringBefore(' ').toLongOrNull()
                val event = entry.substringAfter(' ')
                Text(
                    (millis?.let { timeText(it) + "  " } ?: "") + event,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** View, add, edit and delete what the assistant remembers. Everything stays encrypted on the phone. */
@Composable
fun MemoryScreen(context: Context) {
    var version by remember { mutableIntStateOf(0) }
    val memories = remember(version) { MemoryStore.list(context) }
    val usage = remember(version) { MemoryStore.usage(context) }
    var learn by remember { mutableStateOf(Features.learnUsage(context)) }
    var newFact by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Memory", style = MaterialTheme.typography.titleLarge)
        Text(
            "Stored only on this phone, encrypted. Passwords and codes are never saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn from my usage", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Counts the apps, contacts and music you use most, so the assistant picks them first",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = learn,
                onCheckedChange = {
                    learn = it
                    Features.setLearnUsage(context, it)
                },
            )
        }

        Text("Things to remember", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newFact,
                onValueChange = { newFact = it.take(200) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("For example: I like Telugu movie songs") },
                shape = RoundedCornerShape(20.dp),
            )
            Button(
                onClick = {
                    note = MemoryStore.add(context, newFact)
                    newFact = ""
                    version++
                },
                enabled = newFact.isNotBlank(),
            ) { Text("Add") }
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
        if (memories.isEmpty()) {
            Text("Nothing saved yet. Say \"remember that…\" or add it here", style = MaterialTheme.typography.bodySmall)
        }
        memories.forEach { row ->
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (editing == row.id) {
                        OutlinedTextField(value = editText, onValueChange = { editText = it.take(200) }, modifier = Modifier.fillMaxWidth())
                        Row {
                            TextButton(
                                onClick = {
                                    if (!MemoryStore.update(context, row, editText)) note = "That can't be saved"
                                    editing = null
                                    version++
                                },
                            ) { Text("Save") }
                            TextButton(onClick = { editing = null }) { Text("Cancel") }
                        }
                    } else {
                        Text(row.text, style = MaterialTheme.typography.bodyLarge)
                        Row {
                            TextButton(
                                onClick = {
                                    editing = row.id
                                    editText = row.text
                                },
                            ) { Text("Edit") }
                            TextButton(
                                onClick = {
                                    MemoryStore.delete(context, row.id)
                                    version++
                                },
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }

        Text("Learned from your usage", style = MaterialTheme.typography.titleMedium)
        if (usage.isEmpty()) {
            Text(if (learn) "Nothing learned yet" else "Turn on learning above to use this", style = MaterialTheme.typography.bodySmall)
        }
        usage.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${row.kind}: ${row.name} (used ${row.uses} times)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = {
                        MemoryStore.deleteUsage(context, row.item)
                        version++
                    },
                ) { Text("Delete") }
            }
        }
        OutlinedButton(
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Forget everything") }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            MemoryStore.clearAll(context)
                            confirmClear = false
                            version++
                        },
                    ) { Text("Forget everything") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                title = { Text("Forget everything?") },
                text = { Text("This deletes everything the assistant remembers and has learned. It can't be undone.") },
            )
        }
    }
}
