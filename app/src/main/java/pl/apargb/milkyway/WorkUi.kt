package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun MenuTile(title: String, caption: String, icon: ImageVector, tone: Department, modifier: Modifier = Modifier,
             cornerRadius: Dp = 24.dp, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = tone.tint, shape = RoundedCornerShape(cornerRadius * (2f / 3f))) {
                Icon(icon, null, Modifier.padding(14.dp).size(28.dp), tint = tone.accent)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val controlKinds = listOf(NoteKind.AIR_CONDITIONING, NoteKind.WASHING, NoteKind.PRODUCTION, NoteKind.CURRENT_NOTES)

private fun NoteKind.icon() = when (this) {
    NoteKind.REMINDER -> Icons.Outlined.Notifications
    NoteKind.AIR_CONDITIONING -> Icons.Outlined.AcUnit
    NoteKind.WASHING -> Icons.Outlined.CleaningServices
    NoteKind.PRODUCTION -> Icons.Outlined.Factory
    NoteKind.CURRENT_NOTES -> Icons.Outlined.NoteAlt
}

private fun NoteKind.tone() = when (this) {
    NoteKind.REMINDER, NoteKind.PRODUCTION -> Department.Butter
    NoteKind.AIR_CONDITIONING -> Department.Reception
    NoteKind.WASHING -> Department.Processing
    NoteKind.CURRENT_NOTES -> Department.Powder
}

@Composable
fun ControlMenu(onSelect: (NoteKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Pomiary, przebieg pracy i uwagi z Twojej zmiany.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        controlKinds.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { kind ->
                    MenuTile(kind.title, when (kind) {
                        NoteKind.AIR_CONDITIONING -> "Pomiary i uwagi"
                        NoteKind.WASHING -> "Przebieg mycia"
                        NoteKind.PRODUCTION -> "Przebieg produkcji"
                        else -> "Notatki na bieżąco"
                    }, kind.icon(), kind.tone(), Modifier.weight(1f).fillMaxHeight().testTag("control-${kind.name}")) { onSelect(kind) }
                }
            }
        }
    }
}

@Composable
fun WorkNotesPage(kind: NoteKind, scope: Int, model: WorkNotesViewModel, ui: WorkNotesState) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val notes = ui.notes.filter { it.scope == scope && it.kind == kind }
    val tone = kind.tone()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (scope == 0) "Wpisy wspólne dla zmian" else "Wpisy dla zmiany $scope",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(kind.icon(), null, tint = tone.accent, modifier = Modifier.size(28.dp))
        }
        Button(onClick = { model.clearError(); editingId = UUID.randomUUID().toString() },
            enabled = !ui.loading && !ui.saving && ui.loadError == null, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = tone.accent)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(kind.addLabel)
        }
        if (editingId == null) ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.loadError != null -> Column(Modifier.padding(vertical = 16.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::reload) { Text("Ponów odczyt") }
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("notes-list"),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (notes.isEmpty()) item {
                    Surface(color = tone.tint, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Brak wpisów", fontWeight = FontWeight.SemiBold, color = tone.accent)
                            Text(when (kind) {
                                NoteKind.REMINDER -> "Dodaj przypomnienie z opisem. Po wykonaniu możesz je odznaczyć."
                                NoteKind.CURRENT_NOTES -> "Zapisuj bieżące uwagi i informacje do przekazania."
                                else -> "Dodaj opis kontroli, pomiary lub uwagi. Szczegółowe zasady uzupełnimy po dodaniu opisu."
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(notes, key = { it.id }) { note ->
                    Card(onClick = { if (!ui.saving) { model.clearError(); editingId = note.id } },
                        modifier = Modifier.fillMaxWidth().testTag("note-${note.id}"), shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (kind == NoteKind.REMINDER) Checkbox(checked = note.completed,
                                    onCheckedChange = { model.setCompleted(note, it) }, enabled = !ui.saving,
                                    modifier = Modifier.testTag("done-${note.id}"))
                                Text(note.title, Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                    textDecoration = if (note.completed) TextDecoration.LineThrough else TextDecoration.None)
                                IconButton(onClick = { deletingId = note.id }, enabled = !ui.saving) {
                                    Icon(Icons.Outlined.DeleteOutline, "Usuń wpis: ${note.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (note.body.isNotBlank()) Text(note.body, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(noteDate(note.updatedAt), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (note.completed) "Wykonane" else "Dotknij, aby poprawić",
                                    style = MaterialTheme.typography.labelSmall, color = tone.accent)
                            }
                        }
                    }
                }
            }
        }
    }
    editingId?.let { id ->
        key(id) {
            WorkNoteEditor(id, notes.find { it.id == id }, kind, scope, ui, model, onClose = { editingId = null })
        }
    }
    notes.find { it.id == deletingId }?.let { note ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Usunąć wpis?") },
            text = { Text(note.title) }, confirmButton = {
                TextButton(onClick = { model.delete(note); deletingId = null }, enabled = !ui.saving) { Text("Usuń") }
            }, dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Anuluj") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkNoteEditor(id: String, initial: WorkNote?, kind: NoteKind, scope: Int, ui: WorkNotesState,
                           model: WorkNotesViewModel, onClose: () -> Unit,
                           pageTitle: String = kind.title, addLabel: String = kind.addLabel) {
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "") }
    var body by rememberSaveable { mutableStateOf(initial?.body ?: "") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    Dialog(onDismissRequest = { if (!ui.saving) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(if (initial == null) addLabel else "Edytuj wpis") }) },
            bottomBar = {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClose, enabled = !ui.saving, modifier = Modifier.weight(1f)) { Text("Anuluj") }
                    Button(onClick = { model.save(requestId, id, scope, kind, title, body) },
                        enabled = !ui.saving && title.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text(if (ui.saving) "Zapisywanie…" else "Zapisz")
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (scope == 0) pageTitle else "$pageTitle · Zmiana $scope", color = kind.tone().accent)
                OutlinedTextField(value = title, onValueChange = { title = it.take(120); model.clearError() },
                    label = { Text("Tytuł") }, enabled = !ui.saving, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = body, onValueChange = { body = it.take(10000); model.clearError() },
                    label = { Text("Treść / notatka") }, enabled = !ui.saving,
                    modifier = Modifier.fillMaxWidth(), minLines = 7, shape = RoundedCornerShape(14.dp))
                Text("Data zapisu uzupełnia się automatycznie.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun noteDate(time: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("pl-PL"))
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
