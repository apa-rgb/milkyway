package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// Shared reminders (scope 0) are reserved for the notice board. Reuse the
// existing snapshot format so older clients can still read and retain entries.
internal const val ANNOUNCEMENT_SCOPE = 0

@Composable
internal fun AnnouncementBoardPage(ui: WorkNotesState, model: WorkNotesViewModel) {
    val firstDay = LocalDate.of(1900, 1, 1)
    val lastDay = LocalDate.of(2100, 12, 31)
    val pages = rememberPagerState(initialPage = ChronoUnit.DAYS.between(firstDay, LocalDate.now()).toInt()) {
        ChronoUnit.DAYS.between(firstDay, lastDay).toInt() + 1
    }
    val scope = rememberCoroutineScope()
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val date = firstDay.plusDays(pages.currentPage.toLong())
    val announcements = ui.notes.filter { it.scope == ANNOUNCEMENT_SCOPE && it.kind == NoteKind.REMINDER }
        .sortedWith(compareByDescending<WorkNote> { it.createdAt }.thenBy { it.id })
    val byDay = announcements.groupBy { announcementDay(it.createdAt) }
    val enabled = !ui.loading && !ui.saving && ui.loadError == null
    fun changeDay(day: LocalDate) {
        if (day in firstDay..lastDay) scope.launch { pages.scrollToPage(ChronoUnit.DAYS.between(firstDay, day).toInt()) }
    }
    Column(Modifier.fillMaxSize().testTag("announcements-page")) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tablica ogłoszeń", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Wspólne informacje dla wszystkich zmian", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { changeDay(date.minusDays(1)) }, enabled = !ui.saving && date > firstDay,
                modifier = Modifier.testTag("announcements-previous-day")) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Poprzedni dzień ogłoszeń")
            }
            TextButton(onClick = { calendarOpen = true }, enabled = !ui.saving,
                modifier = Modifier.weight(1f).testTag("announcements-date")) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(queueDateLabel(date))
            }
            TextButton(onClick = { changeDay(LocalDate.now()) }, enabled = !ui.saving && date != LocalDate.now(),
                modifier = Modifier.testTag("announcements-today")) { Text("Dzisiaj") }
            IconButton(onClick = { changeDay(date.plusDays(1)) }, enabled = !ui.saving && date < lastDay,
                modifier = Modifier.testTag("announcements-next-day")) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Następny dzień ogłoszeń")
            }
        }
        Button(onClick = {
            changeDay(LocalDate.now()); model.clearError(); editingId = UUID.randomUUID().toString()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("announcements-add")) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Dodaj ogłoszenie")
        }
        if (editingId == null) ui.operationError?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        when {
            ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.loadError != null -> Column(Modifier.padding(20.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::reload) { Text("Ponów odczyt") }
            }
            else -> VerticalPager(pages, modifier = Modifier.fillMaxWidth().weight(1f).testTag("announcements-days"),
                userScrollEnabled = enabled) { index ->
                val day = firstDay.plusDays(index.toLong())
                val dayNotes = byDay[day].orEmpty()
                LazyColumn(Modifier.fillMaxSize().testTag("announcements-day-$day"),
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dayNotes.isEmpty()) item {
                        Surface(color = Department.Reception.tint, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Brak ogłoszeń na ten dzień", fontWeight = FontWeight.SemiBold)
                                Text("Przewiń w górę lub w dół, aby zmienić dzień, albo wybierz datę w kalendarzu.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(dayNotes, key = { it.id }) { note ->
                        Card(onClick = { model.clearError(); editingId = note.id }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("announcement-${note.id}"), shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = if (note.completed) Department.Processing.tint else MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (note.title.isNotBlank()) Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    color = if (note.important) ImportantNoteColor else MaterialTheme.colorScheme.onSurface)
                                if (note.visibleBody.isNotBlank()) Text(note.visibleBody, maxLines = 6, overflow = TextOverflow.Ellipsis,
                                    color = if (note.important) ImportantNoteColor else MaterialTheme.colorScheme.onSurface)
                                Text("Dodano: ${announcementTimestamp(note.createdAt)}", Modifier.testTag("announcement-created-${note.id}"),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                NoteAuthorLabel(note.author, Modifier.testTag("announcement-author-${note.id}"))
                                ReminderStatusLabel(note.reminder)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(note.completed, { model.setCompleted(note, it) }, enabled = enabled,
                                        modifier = Modifier.testTag("announcement-done-${note.id}"))
                                    Text(if (note.completed) "Załatwione" else "Do załatwienia",
                                        Modifier.testTag("announcement-status-${note.id}"),
                                        color = if (note.completed) Department.Processing.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (calendarOpen) QueueDatePicker(date, { calendarOpen = false }, { changeDay(it); calendarOpen = false },
        title = "Wybierz dzień ogłoszeń", calendarTag = "announcements-calendar")
    editingId?.let { id -> key(id) {
        WorkNoteEditor(id, announcements.find { it.id == id }, NoteKind.REMINDER, ANNOUNCEMENT_SCOPE, ui, model,
            onClose = {
                editingId = null
                announcements.find { it.id == id }?.let { changeDay(announcementDay(it.createdAt)) }
            }, pageTitle = "Tablica ogłoszeń", addLabel = "Dodaj ogłoszenie")
    } }
}

internal fun announcementDay(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
internal fun announcementTimestamp(time: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
