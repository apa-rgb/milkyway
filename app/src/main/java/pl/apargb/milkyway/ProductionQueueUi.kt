package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun ProductionQueuePage(line: ProductionLine, ui: ProductionQueueState, model: ProductionQueueViewModel) =
    ProductionPlanningBoard(line, ui, model)

@Composable
internal fun ProductionQueueEditor(id: String, initial: ProductionQueueEntry?, line: ProductionLine, initialDate: LocalDate,
                                  ui: ProductionQueueState, model: ProductionQueueViewModel, onClose: () -> Unit, scheduleOrder: Boolean = false) {
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "") }
    var description by rememberSaveable { mutableStateOf(initial?.description ?: "") }
    var amount by rememberSaveable { mutableStateOf(initial?.plannedAmount?.inputText() ?: "") }
    var pending by rememberSaveable { mutableStateOf(if (scheduleOrder) false else initial?.pendingOrder ?: true) }
    var time by rememberSaveable { mutableStateOf(initial?.scheduledTime?.toString() ?: if (scheduleOrder) "08:00" else "") }
    val unit = initial?.unit ?: line.defaultUnit
    var plannedDate by rememberSaveable { mutableStateOf((if (scheduleOrder) initialDate else initial?.date ?: initialDate).toString()) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(plannedDate)
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    TankFormDialog(title = if (scheduleOrder) "Zaplanuj zamówienie" else if (initial == null) "Dodaj zamówienie" else "Edytuj pozycję", saving = ui.saving,
        canSave = title.isNotBlank() && amount.isNotBlank(), onClose = onClose,
        onSave = {
            try { model.save(requestId, id, line, date, title, description, parseDecimal(amount, "Planowana ilość", required = true)!!,
                pending, if (pending || time.isBlank()) null else parseProductionTime(time)) }
            catch (error: IllegalArgumentException) { model.showError(error.message ?: "Sprawdź ilość.") }
        }, content = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(line.title, color = line.tone.accent, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(pending, onClick = { pending = true }, enabled = !ui.saving && initial?.completed != true,
                        label = { Text("Zamówienie") })
                    FilterChip(!pending, modifier = Modifier.testTag("queue-editor-production"), onClick = { pending = false; if (time.isBlank()) time = "08:00" }, enabled = !ui.saving,
                        label = { Text("Produkcja") })
                }
                if (!pending) {
                    OutlinedButton(onClick = { calendarOpen = true }, enabled = !ui.saving,
                        modifier = Modifier.fillMaxWidth().testTag("queue-editor-date")) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Dzień produkcji: ${queueDateLabel(date)}")
                    }
                    OutlinedTextField(value = time, onValueChange = { time = it.take(5); model.clearError() },
                        label = { Text("Godzina produkcji [HH:mm]") }, placeholder = { Text("08:30") }, singleLine = true,
                        enabled = !ui.saving, modifier = Modifier.fillMaxWidth().testTag("queue-editor-time"))
                }
                OutlinedTextField(value = title, onValueChange = { title = it.take(120); model.clearError() },
                    label = { Text("Produkcja / produkt") }, enabled = !ui.saving, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                NumberField("Planowana ilość [${unit.label}]", amount, !ui.saving) { amount = it; model.clearError() }
                if (initial != null && initial.producedAmount.signum() > 0) {
                    Text("Już wyprodukowano: ${decimalLabel(initial.producedAmount)} ${unit.label}. Zamówiono: ${initial.plannedAmount?.let(::decimalLabel)} ${unit.label}.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = description, onValueChange = { description = it.take(10000); model.clearError() },
                    label = { Text("Plan / uwagi") }, placeholder = { Text("Zbiornik, ilość, parametry lub uwagi") },
                    enabled = !ui.saving, modifier = Modifier.fillMaxWidth(), minLines = 5, shape = RoundedCornerShape(14.dp))
                Text(if (pending) "Zamówienie pozostanie po prawej do czasu zaplanowania."
                    else if (initial != null && date != initial.date) "Zmiana daty przeniesie pozycję na koniec kolejki wybranego dnia."
                    else "Termin dotyczy wybranego dnia. Możesz też przytrzymać kafelek i przeciągnąć go na godzinę w planie.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
    if (calendarOpen) QueueDatePicker(date, onClose = { calendarOpen = false }, onSelect = {
        plannedDate = it.toString(); calendarOpen = false; model.clearError()
    })
}

@Composable
internal fun ProductionCompletionDialog(entry: ProductionQueueEntry, ui: ProductionQueueState,
                                       model: ProductionQueueViewModel, onClose: () -> Unit) {
    var full by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val remaining = entry.remainingAmount ?: return
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    TankFormDialog("Wyprodukowano: ${entry.title}", saving = ui.saving, canSave = full || amount.isNotBlank(),
        onClose = onClose, onSave = {
            try {
                model.recordProduction(requestId, entry, if (full) null else parseDecimal(amount, "Wyprodukowana ilość", required = true))
            } catch (error: IllegalArgumentException) { model.showError(error.message ?: "Sprawdź ilość.") }
        }, content = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${entry.line.title} · ${queueDateLabel(entry.date)}", color = entry.line.tone.accent)
                Text("Plan: ${entry.plannedAmount?.let(::decimalLabel)} ${entry.unit.label}")
                Text("Wyprodukowano: ${decimalLabel(entry.producedAmount)} ${entry.unit.label}")
                Text("Pozostało: ${decimalLabel(remaining)} ${entry.unit.label}", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = full, onClick = { full = true; model.clearError() }, enabled = !ui.saving,
                        label = { Text("W całości") })
                    FilterChip(selected = !full, onClick = { full = false; model.clearError() }, enabled = !ui.saving,
                        label = { Text("Wpisz ilość") })
                }
                if (!full) NumberField("Wyprodukowano teraz [${entry.unit.label}]", amount, !ui.saving) { amount = it; model.clearError() }
                val added = if (full) remaining else runCatching { parseDecimal(amount, "Ilość") }.getOrNull()
                if (added != null && added.signum() > 0) {
                    Text("Po zapisie pozostanie: ${decimalLabel((remaining - added).max(java.math.BigDecimal.ZERO))} ${entry.unit.label}",
                        modifier = Modifier.testTag("production-remaining-preview"), fontWeight = FontWeight.SemiBold,
                        color = entry.line.tone.accent)
                }
                if (added != null && added > remaining) Text("Nadmiar: ${decimalLabel(added - remaining)} ${entry.unit.label}", color = entry.line.tone.accent)
                Text("Wpisz ilość z tego etapu produkcji. Kolejne zapisy sumują się. „W całości” zapisze całą pozostałą ilość.",
                    style = MaterialTheme.typography.bodySmall)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
}

@Composable
internal fun ProductionCompletionBubble(entry: ProductionQueueEntry, ui: ProductionQueueState,
                                        model: ProductionQueueViewModel, onClose: () -> Unit, onNotes: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val remaining = entry.remainingAmount
    val allowed = entry.date == LocalDate.now() && remaining?.signum() == 1
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wyprodukowano", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(entry.title, color = entry.line.tone.accent)
        Text("Pozostało: ${remaining?.let(::decimalLabel) ?: "—"} ${entry.unit.label}", style = MaterialTheme.typography.bodySmall)
        if (allowed) {
            NumberField("Wyprodukowano teraz [${entry.unit.label}]", amount, !ui.saving) { amount = it; model.clearError() }
            val added = runCatching { parseDecimal(amount, "Ilość") }.getOrNull()
            if (added != null && added.signum() > 0) {
                Text("W kolejce pozostanie: ${decimalLabel((remaining!! - added).max(java.math.BigDecimal.ZERO))} ${entry.unit.label}",
                    Modifier.testTag("bubble-remaining"), style = MaterialTheme.typography.bodySmall)
                if (added > remaining!!) Text("Nadmiar: ${decimalLabel(added - remaining)} ${entry.unit.label}",
                    Modifier.testTag("bubble-excess"), fontWeight = FontWeight.SemiBold, color = entry.line.tone.accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { amount = remaining!!.inputText(); model.clearError() }, enabled = !ui.saving) { Text("W całości") }
                TextButton(onClick = onNotes, enabled = !ui.saving, modifier = Modifier.testTag("bubble-notes")) { Text("Notatka") }
            }
        } else Text(if (remaining == null) "Uzupełnij zamówioną ilość w edycji towaru."
            else "Wykonanie zapiszesz w planie bieżącego dnia.", style = MaterialTheme.typography.bodySmall)
        if (!allowed) TextButton(onClick = onNotes, enabled = !ui.saving) { Text("Notatka") }
        ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose, enabled = !ui.saving, modifier = Modifier.weight(1f)) { Text("Zamknij") }
            Button(onClick = {
                try { model.recordProduction(requestId, entry, parseDecimal(amount, "Wyprodukowana ilość", required = true)) }
                catch (error: IllegalArgumentException) { model.showError(error.message ?: "Sprawdź ilość.") }
            }, enabled = allowed && amount.isNotBlank() && !ui.saving, modifier = Modifier.weight(1f).testTag("bubble-save")) {
                Text(if (ui.saving) "Zapis…" else "Zapisz")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueDatePicker(initial: LocalDate, onClose: () -> Unit, onSelect: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.calendarMillis(),
        initialDisplayedMonthMillis = initial.calendarMillis())
    DatePickerDialog(onDismissRequest = onClose, confirmButton = {
        TextButton(onClick = { state.selectedDateMillis?.let { onSelect(calendarDate(it)) } },
            enabled = state.selectedDateMillis != null) { Text("Wybierz") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } }) {
        DatePicker(state, modifier = Modifier.testTag("queue-calendar"), title = {
            Text("Wybierz dzień produkcji", Modifier.padding(start = 24.dp, top = 16.dp))
        })
    }
}

internal fun queueDateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

// Material's picker represents calendar days at UTC midnight, independent of the phone's time zone.
internal fun LocalDate.calendarMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun calendarDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun parseProductionTime(value: String): java.time.LocalTime {
    require(Regex("\\d{2}:\\d{2}").matches(value.trim())) { "Godzinę wpisz jako HH:mm, np. 08:30." }
    return try { java.time.LocalTime.parse(value.trim()) }
    catch (_: java.time.format.DateTimeParseException) { throw IllegalArgumentException("Godzina musi mieścić się w zakresie 00:00–23:59.") }
}
