package pl.apargb.milkyway

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun reminderLabel(reminder: NoteReminder) = Instant.ofEpochMilli(reminder.at).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

internal fun reminderMoment(date: LocalDate, time: String, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Long {
    val local = LocalDateTime.of(date, parseProductionTime(time))
    require(zone.rules.getValidOffsets(local).isNotEmpty()) { "Ta godzina nie istnieje w dniu zmiany czasu. Wybierz inną." }
    val at = local.atZone(zone).toInstant().toEpochMilli()
    require(at > now) { "Wybierz przyszłą datę i godzinę przypomnienia." }
    return at
}

@Composable
internal fun ReminderStatusLabel(reminder: NoteReminder?) {
    reminder?.let {
        Text(if (it.done) "Przypomnienie: zrobione" else "Przypomnienie: ${reminderLabel(it)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReminderButton(reminder: NoteReminder?, enabled: Boolean, tag: String = "note-reminder", onSelect: (Long) -> Unit) {
    var selecting by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(onClick = { selecting = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Przypomnienie")
        }
        ReminderStatusLabel(reminder)
    }
    if (selecting) ReminderPicker(reminder, onClose = { selecting = false }, onSelect = { onSelect(it); selecting = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPicker(initial: NoteReminder?, onClose: () -> Unit, onSelect: (Long) -> Unit) {
    val default = initial?.let { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        ?.takeIf { it.isAfter(LocalDateTime.now()) } ?: LocalDateTime.now().plusHours(1)
    var date by rememberSaveable { mutableStateOf(default.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(default.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var clock by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    TankFormDialog("Kiedy przypomnieć?", false, true, onClose, onSave = {
        runCatching { reminderMoment(LocalDate.parse(date), time) }.onSuccess(onSelect).onFailure { error = it.message }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { calendar = true }, modifier = Modifier.fillMaxWidth().testTag("reminder-date")) {
                Text("Dzień: ${queueDateLabel(LocalDate.parse(date))}")
            }
            OutlinedButton(onClick = { clock = true }, modifier = Modifier.fillMaxWidth().testTag("reminder-time")) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("Godzina: $time")
            }
            Text("Godzina według ustawień telefonu. Przypomnienie zobaczysz na głównym pulpicie.", style = MaterialTheme.typography.bodySmall)
            ReminderPermissions()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reminder-picker-error")) }
        }
    }
    if (calendar) QueueDatePicker(LocalDate.parse(date), { calendar = false }, {
        date = it.toString(); calendar = false; error = null
    }, title = "Wybierz dzień przypomnienia", calendarTag = "reminder-calendar")
    if (clock) {
        val selected = parseProductionTime(time)
        val state = rememberTimePickerState(selected.hour, selected.minute, is24Hour = true)
        Dialog(onDismissRequest = { clock = false }) {
            Surface(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp).testTag("reminder-clock"), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Wybierz godzinę", style = MaterialTheme.typography.titleMedium)
                    TimePicker(state)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { clock = false }) { Text("Anuluj") }
                        TextButton(onClick = {
                            time = java.time.LocalTime.of(state.hour, state.minute).format(DateTimeFormatter.ofPattern("HH:mm"))
                            error = null; clock = false
                        }, modifier = Modifier.testTag("reminder-clock-confirm")) { Text("Wybierz") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderPermissions() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var notifications by remember { mutableStateOf(ReminderScheduler.notificationsAllowed(context)) }
    var exact by remember { mutableStateOf(ReminderScheduler.exactAllowed(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = ReminderScheduler.notificationsAllowed(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            notifications = ReminderScheduler.notificationsAllowed(context); exact = ReminderScheduler.exactAllowed(context)
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    if (!notifications) {
        Text("Włącz powiadomienia, aby telefon przypomniał również po zamknięciu aplikacji.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Włącz powiadomienia") }
    }
    if (!exact && Build.VERSION.SDK_INT >= 31) {
        Text("Zezwól na dokładny termin. Bez tego ustawienia powiadomienie w tle może przyjść później.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) {
            Text("Włącz dokładne przypomnienia")
        }
    }
}

@Composable
internal fun DueReminderDialog(item: ReminderItem, saving: Boolean, error: String?, onAction: (ReminderAction) -> Unit) {
    var faded by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) {
        // Three gentle pulses attract attention without continuously distracting from the text.
        repeat(6) { delay(850); faded = !faded }
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().alpha(if (faded) 0.92f else 1f).testTag("due-reminder"), shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Przypomnienie · ${reminderLabel(item.reminder)}", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.domain == "production") Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(item.caption, style = MaterialTheme.typography.labelSmall)
                    NoteAuthorLabel(item.author)
                    Text(item.text.ifBlank { item.title }, modifier = Modifier.testTag("due-reminder-text"), style = MaterialTheme.typography.bodySmall,
                        color = if (item.important) ImportantNoteColor else MaterialTheme.colorScheme.onSurface)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ onAction(ReminderAction.DONE) }, enabled = !saving,
                        modifier = Modifier.weight(1f).testTag("reminder-done")) { Text("Zrobione") }
                    Button({ onAction(ReminderAction.READ) }, enabled = !saving,
                        modifier = Modifier.weight(1f).testTag("reminder-read")) { Text("OK") }
                    TextButton({ onAction(ReminderAction.URGENT) }, enabled = !saving && !item.important,
                        modifier = Modifier.weight(1f).testTag("reminder-urgent")) { Text("Pilne", color = ImportantNoteColor) }
                }
            }
        }
    }
}
