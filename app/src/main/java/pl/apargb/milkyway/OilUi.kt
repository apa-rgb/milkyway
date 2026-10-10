package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal val OilWarningColor = Color(0xFFB3261E)
internal val OilWarningBackground = Color(0xFFFFE5E3)
internal fun LocalDate?.oilDateLabel(): String = this?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: "—"

@Composable
internal fun OilDateField(label: String, value: String, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    val date = value.takeIf(String::isNotBlank)?.let(LocalDate::parse)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { calendar = true }, enabled = enabled,
            modifier = Modifier.weight(1f).testTag(tag), shape = RoundedCornerShape(12.dp)) {
            Text("$label: ${date.oilDateLabel()}")
        }
        if (date != null) TextButton(onClick = { onChange("") }, enabled = enabled,
            modifier = Modifier.testTag("$tag-clear")) { Text("Usuń") }
    }
    if (calendar) QueueDatePicker(date ?: LocalDate.now(), onClose = { calendar = false },
        onSelect = { onChange(it.toString()); calendar = false }, title = label, calendarTag = "$tag-calendar")
}

@Composable
internal fun TankResetDialog(tank: Tank, ui: InventoryUiState, model: InventoryViewModel, shift: Int?, onClose: () -> Unit) {
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    AlertDialog(onDismissRequest = { if (!ui.saving) onClose() }, title = { Text("Wyzerować ${tank.name}?") },
        text = { Column {
            Text("Stan zostanie ustawiony na 0 l. Bieżąca zawartość, pomiary, trasa oraz dane partii zostaną wyczyszczone. Historia napełnień pozostanie.")
            ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            TextButton(enabled = !ui.saving, modifier = Modifier.testTag("confirm-reset-tank"), onClick = {
                model.clearError()
                model.save(requestId) { it.resetTank(tank.id, System.currentTimeMillis(), shift) }
            }) { Text(if (ui.saving) "Zapisywanie…" else "Zeruj zbiornik") }
        }, dismissButton = { TextButton(enabled = !ui.saving, onClick = onClose) { Text("Anuluj") } })
}
