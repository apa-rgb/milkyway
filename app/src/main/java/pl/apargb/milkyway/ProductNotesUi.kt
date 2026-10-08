package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun ProductNotesDialog(entry: ProductionQueueEntry, stage: ProductNoteStage, ui: ProductionQueueState,
                                model: ProductionQueueViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val notes = ui.productNotes.filter { it.entryId == entry.id }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    TankFormDialog("Notatki produktu", saving = ui.saving, canSave = text.isNotBlank(), onClose = onClose,
        onSave = { model.addProductNote(requestId, entry, stage, text) }, content = {
            Column(Modifier.testTag("product-notes-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = entry.line.tone.accent)
                Text("${entry.line.title} · ${stage.title}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, onValueChange = { text = it.take(4000); model.clearError() }, label = { Text("Nowa notatka") },
                    modifier = Modifier.fillMaxWidth().testTag("product-note-input"), minLines = 3, enabled = !ui.saving,
                    shape = RoundedCornerShape(14.dp))
                Text("Notatka zostanie dopisana do historii produktu wraz z datą i etapem.", style = MaterialTheme.typography.bodySmall)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Text("Notatki ze wszystkich etapów", fontWeight = FontWeight.SemiBold)
                if (entry.description.isNotBlank()) {
                    Text("Plan / uwagi", style = MaterialTheme.typography.labelMedium, color = entry.line.tone.accent)
                    Text(entry.description)
                }
                if (notes.isEmpty()) Text("Brak dodatkowych notatek", style = MaterialTheme.typography.bodySmall)
                notes.forEach { note ->
                    Surface(shape = RoundedCornerShape(12.dp), color = entry.line.tone.tint) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${note.stage.title} · ${dateLabel(note.createdAt)}", style = MaterialTheme.typography.labelSmall,
                                color = entry.line.tone.accent)
                            Text(note.text, modifier = Modifier.testTag("product-note-${note.id}"))
                        }
                    }
                }
            }
        })
}
