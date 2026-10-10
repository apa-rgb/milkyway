package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import java.time.LocalDate
import java.util.Locale

private val productionCodePattern = Regex("^Kod produkcji: ([0-9]{3}|—)$")
internal const val AUTOMATIC_PRODUCTION_CODE_PREFIX = "automatic-production-code:"
internal fun productionDateCode(date: LocalDate): String = "%03d".format(Locale.ROOT, date.dayOfYear)
internal fun ProductNote.isProductionCode(): Boolean = productionCodePattern.matches(text)
internal fun ProductNote.isAutomaticProductionCode(): Boolean = isProductionCode() && id.startsWith(AUTOMATIC_PRODUCTION_CODE_PREFIX)
// Repository order is newest first, with row IDs resolving equal timestamps.
internal fun productionCode(notes: List<ProductNote>, entryId: String): String = notes
    .firstOrNull { it.entryId == entryId && it.isProductionCode() }
    ?.let { productionCodePattern.matchEntire(it.text)!!.groupValues[1].takeUnless { code -> code == "—" } }.orEmpty()

@Composable
internal fun ProductionCodeDialog(entry: ProductionQueueEntry, ui: ProductionQueueState, model: ProductionQueueViewModel, onClose: () -> Unit) {
    var code by rememberSaveable { mutableStateOf(productionCode(ui.productNotes, entry.id)) }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    Dialog(onDismissRequest = { if (!ui.saving) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Kod produkcji", style = MaterialTheme.typography.headlineSmall)
                Text(entry.title)
                OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(3); model.clearError() },
                    label = { Text("3 cyfry (opcjonalnie)") }, modifier = Modifier.fillMaxWidth().testTag("production-code-input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !ui.saving)
                Text("Przy planowaniu zamówienia kod jest dniem roku (001–366) z daty produkcji. Możesz wpisać własny kod lub pozostawić puste pole, aby go usunąć. Ręczna zmiana nie zostanie nadpisana.", style = MaterialTheme.typography.bodySmall)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, enabled = !ui.saving) { Text("Anuluj") }
                    TextButton(onClick = { model.setProductionCode(requestId, entry, code) },
                        enabled = !ui.saving && (code.isEmpty() || code.length == 3), modifier = Modifier.testTag("production-code-save")) { Text("Zapisz") }
                }
            }
        }
    }
}
