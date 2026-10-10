package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun ProductNotesDialog(entry: ProductionQueueEntry, stage: ProductNoteStage, ui: ProductionQueueState,
                                model: ProductionQueueViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var important by rememberSaveable { mutableStateOf(false) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var reminderMetadata by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val notes = ui.productNotes.filter { it.entryId == entry.id }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    TankFormDialog("Notatki produktu", saving = ui.saving, canSave = text.isNotBlank(), onClose = onClose, compact = true,
        onSave = { model.addProductNote(requestId, entry, stage, withCompletedText(withNoteReminder(withImportantText(text, important), noteReminder(reminderMetadata)), completed)) },
        footer = { NoteActionsBar(important, completed, noteReminder(reminderMetadata), !ui.saving,
            "product-note-important", "product-note-completed", onImportant = { important = it }, onCompleted = { completed = it },
            onReminder = { reminderMetadata = withNoteReminder("", NoteReminder(it)); completed = false }) }, content = {
            Column(Modifier.testTag("product-notes-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = entry.line.tone.accent)
                Text("${entry.line.title} · ${stage.title}", style = MaterialTheme.typography.bodySmall)
                NoteAuthorLabel(currentNoteAuthor(), Modifier.testTag("product-note-new-author"))
                OutlinedTextField(text, onValueChange = { text = it.take(4000); model.clearError() }, label = { Text("Nowa notatka", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth().testTag("product-note-input"), minLines = 3, maxLines = 5, enabled = !ui.saving,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (important) ImportantNoteColor else MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(20.dp))
                Text("Notatka zostanie dopisana do historii produktu wraz z datą i etapem.", style = MaterialTheme.typography.bodySmall)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Text("Notatki ze wszystkich etapów", fontWeight = FontWeight.SemiBold)
                if (entry.planDescription.isNotBlank()) {
                    Text("Plan / uwagi", style = MaterialTheme.typography.labelMedium, color = entry.line.tone.accent)
                    Text(entry.planDescription, style = MaterialTheme.typography.bodyMedium)
                }
                if (notes.isEmpty()) Text("Brak dodatkowych notatek", style = MaterialTheme.typography.bodySmall)
                notes.forEach { note ->
                    Surface(shape = RoundedCornerShape(12.dp), color = noteCardColor(note.id, note.completed)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${note.stage.title} · ${dateLabel(note.createdAt)}", style = MaterialTheme.typography.labelSmall,
                                color = entry.line.tone.accent)
                            if (note.isAutomaticProductionCode()) Text("Kod dodany automatycznie", style = MaterialTheme.typography.labelSmall)
                            NoteAuthorLabel(note.author, Modifier.testTag("product-note-author-${note.id}"))
                            if (!note.isProductionCode()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(note.important, { model.setProductNoteImportant(note, it) }, enabled = !ui.saving,
                                    modifier = Modifier.testTag("product-note-important-${note.id}"))
                                Text("Ważne", style = MaterialTheme.typography.labelSmall,
                                    color = if (note.important) ImportantNoteColor else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!note.isProductionCode()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(note.completed, { model.setProductNoteCompleted(note, it) }, enabled = !ui.saving,
                                    modifier = Modifier.testTag("product-note-completed-${note.id}"))
                                Text("Załatwione", style = MaterialTheme.typography.labelSmall,
                                    color = if (note.completed) CompletedNoteColor else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(note.visibleText, modifier = Modifier.testTag("product-note-${note.id}"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (note.important) ImportantNoteColor else MaterialTheme.colorScheme.onSurface)
                            if (!note.isProductionCode()) ReminderButton(note.reminder, !ui.saving, "product-note-reminder-${note.id}") {
                                model.scheduleReminder(note, it)
                            }
                        }
                    }
                }
            }
        })
}
