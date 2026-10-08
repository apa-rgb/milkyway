package pl.apargb.milkyway

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.math.floor

private data class CompletedRow(val product: CompletedDayProduct? = null, val rejected: RejectedGoods? = null) {
    val id get() = product?.entry?.id ?: rejected!!.id
    val timestamp get() = product?.receipts?.maxOf { it.occurredAt } ?: rejected!!.createdAt
}

@Composable
internal fun ProductionCompletedPage(ui: ProductionQueueState, model: ProductionQueueViewModel) {
    var department by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var notesId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var rejectDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingId by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable(selectedDate, department) { mutableIntStateOf(0) }
    val date = LocalDate.parse(selectedDate)
    val line = department?.let(ProductionLine::valueOf)
    val enabled = !ui.loading && !ui.saving && ui.loadError == null
    val products = completedProductsForDay(ui.entries, ui.completions, date, line)
    val rejected = ui.rejectedGoods.filter { it.date == date && (line == null || it.line == line) }
    val rows = (products.map { CompletedRow(product = it) } + rejected.map { CompletedRow(rejected = it) }).sortedByDescending { it.timestamp }
    fun changeDay(value: LocalDate) {
        if (value.year in 1900..2100) { selectedDate = value.toString(); detailsId = null; rejectDetailsId = null; model.clearError() }
    }
    Column(Modifier.fillMaxSize().testTag("completed-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Wyprodukowano", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = { model.clearError(); addingId = UUID.randomUUID().toString() }, enabled = enabled,
                modifier = Modifier.testTag("completed-add-rejected"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Wybrakowany", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { changeDay(date.minusDays(1)) }, enabled = !ui.saving && date > LocalDate.of(1900, 1, 1),
                modifier = Modifier.testTag("completed-previous-day")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Poprzedni dzień rejestru") }
            TextButton(onClick = { calendarOpen = true }, enabled = !ui.saving, modifier = Modifier.weight(1f).testTag("completed-date")) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(queueDateLabel(date))
            }
            TextButton(onClick = { changeDay(LocalDate.now()) }, enabled = !ui.saving && date != LocalDate.now(),
                modifier = Modifier.testTag("completed-today"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Dzisiaj") }
            IconButton(onClick = { changeDay(date.plusDays(1)) }, enabled = !ui.saving && date < LocalDate.of(2100, 12, 31),
                modifier = Modifier.testTag("completed-next-day")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Następny dzień rejestru") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(department == null, onClick = { department = null }, label = { Text("Wszystkie") })
            ProductionLine.entries.forEach { item ->
                FilterChip(department == item.name, onClick = { department = item.name }, label = { Text(item.title) },
                    modifier = Modifier.testTag("completed-filter-${item.name}"))
            }
        }
        val total = products.groupBy { it.entry.unit }.map { (unit, values) ->
            "${decimalLabel(values.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount })} ${unit.label}"
        }.joinToString(" · ").ifEmpty { "0" }
        Text("Produkcja: $total  |  Wybrakowane: ${decimalLabel(rejected.fold(BigDecimal.ZERO) { sum, item -> sum + item.kilograms })} kg",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("completed-day-summary"), style = MaterialTheme.typography.labelSmall)
        ui.operationError?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        when {
            ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.loadError != null -> Column(Modifier.padding(12.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::reload) { Text("Ponów odczyt") }
            }
            else -> BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                // Keep readable rows on one screen; large daily registers have pages within the same day.
                val minimumRow = 62.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val pageSize = floor((maxHeight - 44.dp).value / (minimumRow + 4.dp).value).toInt().coerceAtLeast(1)
                val pages = ((rows.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                val visiblePage = page.coerceIn(0, pages - 1)
                LaunchedEffect(pages) { page = page.coerceIn(0, pages - 1) }
                val rowHeight = ((maxHeight - 44.dp) / pageSize - 4.dp).coerceIn(minimumRow, minimumRow + 12.dp)
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("completed-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (rows.isEmpty()) item { Text("Brak wyprodukowanego towaru", Modifier.padding(12.dp)) }
                        items(rows.drop(visiblePage * pageSize).take(pageSize), key = { if (it.rejected != null) "reject-${it.id}" else "product-${it.id}" }) { row ->
                            row.product?.let { product ->
                                val entry = product.entry
                                Surface(onClick = { detailsId = entry.id }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("completed-entry-${entry.id}"),
                                    shape = RoundedCornerShape(10.dp), color = entry.line.tone.tint) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.title, fontWeight = FontWeight.Bold, color = entry.line.tone.accent,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(entry.line.title, style = MaterialTheme.typography.labelSmall)
                                                Text("Wyprodukowano: ${decimalLabel(product.amount)} ${entry.unit.label}",
                                                    Modifier.testTag("completed-amount-${entry.id}"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            }
                                            if (entry.excessAmount.signum() > 0) Text(completedOrderLabel(entry),
                                                Modifier.testTag("completed-order-${entry.id}"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall, color = entry.line.tone.accent)
                                            else ui.productNotes.firstOrNull { it.entryId == entry.id }?.let {
                                                Text(it.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        IconButton(onClick = { model.clearError(); notesId = entry.id }, enabled = enabled,
                                            modifier = Modifier.size(40.dp).testTag("product-notes-${entry.id}")) {
                                            Icon(Icons.Outlined.NoteAlt, "Notatki: ${entry.title}", Modifier.size(20.dp), tint = entry.line.tone.accent)
                                        }
                                    }
                                }
                            }
                            row.rejected?.let { item ->
                                Surface(onClick = { rejectDetailsId = item.id }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("completed-rejected-${item.id}"),
                                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .5f)) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.description, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelLarge)
                                            Text("${item.line.title} · Wybrakowany · ${decimalLabel(item.kilograms)} kg",
                                                Modifier.testTag("rejected-amount-${item.id}"), style = MaterialTheme.typography.labelSmall)
                                        }
                                        IconButton(onClick = { model.clearError(); deletingId = item.id }, enabled = enabled,
                                            modifier = Modifier.size(40.dp).testTag("rejected-delete-${item.id}")) {
                                            Icon(Icons.Outlined.DeleteOutline, "Usuń wybrakowany towar", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { page = visiblePage - 1 }, enabled = visiblePage > 0, modifier = Modifier.testTag("completed-previous-page")) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Poprzednia strona dnia")
                        }
                        Text("${rows.size} pozycji · Strona ${visiblePage + 1}/$pages", Modifier.testTag("completed-page-number"), style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { page = visiblePage + 1 }, enabled = visiblePage < pages - 1, modifier = Modifier.testTag("completed-next-page")) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Następna strona dnia")
                        }
                    }
                }
            }
        }
    }
    if (calendarOpen) QueueDatePicker(date, { calendarOpen = false }, { changeDay(it); calendarOpen = false })
    addingId?.let { id -> key(id) { RejectedGoodsEditor(id, line ?: ProductionLine.BUTTER, date, ui, model) {
        if (ui.lastSavedRequestId == id) ui.rejectedGoods.find { it.id == id }?.let {
            selectedDate = it.date.toString()
            if (department != null) department = it.line.name
        }
        addingId = null
    } } }
    products.find { it.entry.id == detailsId }?.let { CompletedProductDetails(it, date) { detailsId = null } }
    ui.entries.find { it.id == notesId }?.let { entry -> key(entry.id) {
        ProductNotesDialog(entry, ProductNoteStage.COMPLETED, ui, model, onClose = { notesId = null })
    } }
    ui.rejectedGoods.find { it.id == rejectDetailsId }?.let { item ->
        AlertDialog(onDismissRequest = { rejectDetailsId = null }, title = { Text("Wybrakowany towar") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("rejected-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${item.line.title} · ${queueDateLabel(item.date)}"); Text("Masa: ${decimalLabel(item.kilograms)} kg", fontWeight = FontWeight.Bold)
                Text(item.description); Text("Zapisano: ${dateLabel(item.createdAt)}", style = MaterialTheme.typography.labelSmall)
            }
        }, confirmButton = { TextButton(onClick = { rejectDetailsId = null }) { Text("Zamknij") } })
    }
    ui.rejectedGoods.find { it.id == deletingId }?.let { item ->
        AlertDialog(onDismissRequest = { if (!ui.saving) deletingId = null }, title = { Text("Usunąć wybrakowany towar?") },
            text = { Column { Text("${decimalLabel(item.kilograms)} kg · ${queueDateLabel(item.date)}"); Text(item.description, maxLines = 4) } },
            confirmButton = { TextButton(onClick = { model.deleteRejectedGoods(item.id); deletingId = null }, enabled = !ui.saving,
                modifier = Modifier.testTag("rejected-delete-confirm")) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { deletingId = null }, enabled = !ui.saving) { Text("Anuluj") } })
    }
}

private fun completedOrderLabel(entry: ProductionQueueEntry) = "Zamówiono: ${entry.plannedAmount?.let(::decimalLabel) ?: "—"} ${entry.unit.label}" +
    if (entry.excessAmount.signum() > 0) " + nadmiar: ${decimalLabel(entry.excessAmount)} ${entry.unit.label}" else ""

@Composable
private fun CompletedProductDetails(product: CompletedDayProduct, date: LocalDate, onClose: () -> Unit) {
    val entry = product.entry
    AlertDialog(onDismissRequest = onClose, title = { Text(entry.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("completed-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${entry.line.title} · ${queueDateLabel(date)}")
            Text("Wyprodukowano tego dnia: ${decimalLabel(product.amount)} ${entry.unit.label}", fontWeight = FontWeight.Bold)
            Text("Łącznie wyprodukowano: ${decimalLabel(entry.producedAmount)} ${entry.unit.label}")
            Text(completedOrderLabel(entry))
            if (!entry.completed) Text((if (entry.pendingOrder) "Oczekuje na zaplanowanie: " else "W kolejce pozostało: ") +
                "${entry.remainingAmount?.let(::decimalLabel) ?: "—"} ${entry.unit.label}")
            if (entry.description.isNotBlank()) Text(entry.description)
            HorizontalDivider(); Text("Zapisy wykonania", fontWeight = FontWeight.Bold)
            product.receipts.forEach { receipt -> Text("${decimalLabel(receipt.amount)} ${entry.unit.label} · ${dateLabel(receipt.occurredAt)}") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Zamknij") } })
}

@Composable
private fun RejectedGoodsEditor(id: String, initialLine: ProductionLine, initialDate: LocalDate, ui: ProductionQueueState,
                               model: ProductionQueueViewModel, onClose: () -> Unit) {
    var line by rememberSaveable { mutableStateOf(initialLine.name) }
    var day by rememberSaveable { mutableStateOf(initialDate.toString()) }
    var description by rememberSaveable { mutableStateOf("") }
    var mass by rememberSaveable { mutableStateOf("") }
    var calendar by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(day)
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == id) onClose() }
    TankFormDialog("Dodaj wybrakowany towar", ui.saving, description.isNotBlank() && mass.isNotBlank(), onClose,
        onSave = {
            try { model.addRejectedGoods(id, ProductionLine.valueOf(line), date, description, parseDecimal(mass, "Masa", required = true)!!) }
            catch (error: IllegalArgumentException) { model.showError(error.message ?: "Sprawdź masę.") }
        }, content = {
            Column(Modifier.testTag("rejected-editor"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Dział", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProductionLine.entries.forEach { item -> FilterChip(line == item.name, { line = item.name }, enabled = !ui.saving,
                        label = { Text(item.title) }, modifier = Modifier.testTag("rejected-line-${item.name}")) }
                }
                OutlinedButton(onClick = { calendar = true }, enabled = !ui.saving, modifier = Modifier.testTag("rejected-date")) {
                    Text("Dzień: ${queueDateLabel(date)}")
                }
                OutlinedTextField(description, onValueChange = { description = it.take(4000); model.clearError() }, label = { Text("Opis wybrakowanego towaru") },
                    modifier = Modifier.fillMaxWidth().testTag("rejected-description"), minLines = 3, enabled = !ui.saving)
                NumberField("Masa [kg]", mass, !ui.saving, Modifier.testTag("rejected-mass")) { mass = it; model.clearError() }
                Text("Osobny wpis w rejestrze wybranego dnia. Masa jest zawsze w kg.", style = MaterialTheme.typography.bodySmall)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
    if (calendar) QueueDatePicker(date, { calendar = false }, { day = it.toString(); calendar = false })
}
