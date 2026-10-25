package com.nocturne.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nocturne.data.LogEntry
import com.nocturne.data.LogRepository
import com.nocturne.session.LogLevel
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.ConfirmDialog
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PAGE = 500

/**
 * The phone's log of the rig, newest first: Ekos module logs, alerts, and what the app did or saw
 * on its connection (see [com.nocturne.session.LogCollector]). Kept until deleted here. Filters
 * are by source, night, warnings-and-errors and text; Pause freezes the list while reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogScreen(repo: LogRepository, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<String?>(null) }
    var night by remember { mutableStateOf<String?>(null) }
    var problemsOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(PAGE) }
    var frozen by remember { mutableStateOf<List<LogEntry>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val flow = remember(source, night, problemsOnly, query, limit) {
        repo.observe(source, if (problemsOnly) LogLevel.WARN else LogLevel.INFO, night, query.trim(), limit)
    }
    val live by flow.collectAsState(emptyList())
    val sources by remember { repo.sources() }.collectAsState(emptyList())
    val nights by remember { repo.nights() }.collectAsState(emptyList())
    val rows = frozen ?: live

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextC("Log", style = t.HeaderTitle, color = c.text, modifier = Modifier.weight(1f))
                IconBtn(icon = Phosphor.X, onClick = onClose, size = 34)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("All sources", source == null) { source = null }
                sources.forEach { s -> Chip(s, source == s) { source = if (source == s) null else s } }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("All nights", night == null) { night = null }
                nights.take(8).forEach { n -> Chip(nightLabel(n), night == n) { night = if (night == n) null else n } }
                Chip("Warnings & errors", problemsOnly) { problemsOnly = !problemsOnly }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(c.surface, RoundedCornerShape(4.dp))
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) TextC("Search the log", style = t.Body13, color = c.textFaint)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NocturneButton(
                    text = if (frozen == null) "Pause" else "Follow",
                    onClick = { frozen = if (frozen == null) live else null },
                    style = BtnStyle.SUBTLE,
                    modifier = Modifier.weight(1f).height(36.dp),
                )
                NocturneButton("Copy", onClick = { copyToClipboard(context, excerpt(rows)) }, style = BtnStyle.SUBTLE, modifier = Modifier.weight(1f).height(36.dp))
                NocturneButton("Share", onClick = { share(context, excerpt(rows)) }, style = BtnStyle.SUBTLE, modifier = Modifier.weight(1f).height(36.dp))
                NocturneButton(
                    text = if (night != null) "Delete night" else "Clear",
                    onClick = { confirmDelete = true },
                    style = BtnStyle.SUBTLE,
                    modifier = Modifier.weight(1f).height(36.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextC(
                if (frozen != null) "Paused — ${rows.size} lines" else "${rows.size} lines" + if (rows.size >= limit) " (newest)" else "",
                style = t.MonoMicro, color = c.textFaint,
            )
            Spacer(Modifier.height(4.dp))

            if (rows.isEmpty()) {
                TextC(
                    if (sources.isEmpty()) "Nothing logged yet. Entries appear while the app is connected to the rig." else "No lines match these filters.",
                    style = t.Body13, color = c.textMuted, modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows) { entry -> LogRow(entry) }
                    if (rows.size >= limit) item {
                        NocturneButton(
                            "Show older",
                            onClick = { limit += PAGE },
                            style = BtnStyle.OUTLINE,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(40.dp),
                        )
                    }
                }
            }
        }

        if (confirmDelete) {
            ConfirmDialog(
                title = if (night != null) "Delete ${nightLabel(night!!)}?" else "Clear the whole log?",
                message = if (night != null) "Removes every line of that night from this phone." else "Removes every logged line from this phone. The rig's own logs are not touched.",
                confirmText = "Delete",
                confirmStyle = BtnStyle.DANGER,
                onConfirm = {
                    confirmDelete = false
                    val n = night
                    scope.launch { if (n != null) repo.deleteNight(n) else repo.clear() }
                    if (n != null) night = null
                    frozen = null
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@Composable
internal fun LogRow(entry: LogEntry) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val color = when (entry.level) {
        LogLevel.ERROR -> c.danger
        LogLevel.WARN -> c.warn
        LogLevel.INFO -> c.text
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        TextC(timeLabel(entry.timeMs), style = t.MonoMicro, color = c.textFaint, modifier = Modifier.width(74.dp))
        TextC(entry.source, style = t.MonoMicro, color = c.accent400, modifier = Modifier.width(62.dp))
        TextC(entry.text, style = t.MonoSmall, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .background(if (selected) c.accent.copy(alpha = 0.18f) else c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.6f) else c.divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        TextC(label, style = t.MonoMicro, color = if (selected) c.accent400 else c.textMuted)
    }
}

private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
private val DATE_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US)
private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

/** "21:04:11" for today, "Oct 24 21:04" for older lines. */
private fun timeLabel(timeMs: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(timeMs).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) TIME_ONLY.format(at) else DATE_TIME.format(at)
}

/** "night of Oct 24" from a night key "2026-10-24". */
private fun nightLabel(key: String): String =
    runCatching { "night of " + DateTimeFormatter.ofPattern("MMM d", Locale.US).format(LocalDate.parse(key)) }.getOrDefault(key)

/** The shown lines, oldest first, one per line, in a form that reads on its own when pasted. */
private fun excerpt(rows: List<LogEntry>): String {
    val zone = ZoneId.systemDefault()
    return rows.asReversed().joinToString("\n") {
        "${FULL.format(Instant.ofEpochMilli(it.timeMs).atZone(zone))} [${it.source}] ${it.text}"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Nocturne log", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
