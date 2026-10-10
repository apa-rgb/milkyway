package pl.apargb.milkyway

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun ProductionCompletedPage(ui: ProductionQueueState, model: ProductionQueueViewModel, now: java.time.Instant = java.time.Instant.now()) {
    val currentDay = warehouseProductionDay(now)
    var department by rememberSaveable { mutableStateOf<String?>(null) }
    val firstDay = LocalDate.of(1900, 1, 1)
    val lastDay = LocalDate.of(2100, 12, 31)
    val days = rememberPagerState(initialPage = ChronoUnit.DAYS.between(firstDay, currentDay).toInt()) {
        ChronoUnit.DAYS.between(firstDay, lastDay).toInt() + 1
    }
    val scope = rememberCoroutineScope()
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var notesId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var rejectDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removalRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var addingId by rememberSaveable { mutableStateOf<String?>(null) }
    val date = firstDay.plusDays(days.currentPage.toLong())
    val line = department?.let(ProductionLine::valueOf)
    val enabled = !ui.loading && !ui.saving && ui.loadError == null
    val products = completedProductsForDay(ui.entries, ui.completions, date, line)
    fun changeDay(value: LocalDate) {
        if (value in firstDay..lastDay) scope.launch {
            days.scrollToPage(ChronoUnit.DAYS.between(firstDay, value).toInt())
            detailsId = null; rejectDetailsId = null; model.clearError()
        }
    }
    Column(Modifier.fillMaxSize().testTag("completed-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(WAREHOUSE_TITLE, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = { model.clearError(); addingId = UUID.randomUUID().toString() }, enabled = enabled,
                modifier = Modifier.testTag("completed-add-rejected"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("Wybrakowany", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { changeDay(date.minusDays(1)) }, enabled = !ui.saving && date > LocalDate.of(1900, 1, 1),
                modifier = Modifier.testTag("completed-previous-day")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Poprzedni dzień rejestru", Modifier.size(18.dp)) }
            TextButton(onClick = { calendarOpen = true }, enabled = !ui.saving, modifier = Modifier.weight(1f).testTag("completed-date")) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(queueDateLabel(date))
            }
            TextButton(onClick = { changeDay(currentDay) }, enabled = !ui.saving && date != currentDay,
                modifier = Modifier.testTag("completed-today"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Bieżący") }
            IconButton(onClick = { changeDay(date.plusDays(1)) }, enabled = !ui.saving && date < LocalDate.of(2100, 12, 31),
                modifier = Modifier.testTag("completed-next-day")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Następny dzień rejestru", Modifier.size(18.dp)) }
        }
        Text("Dzień produkcyjny: 09:00 – 09:00 następnego dnia", Modifier.padding(horizontal = 12.dp).testTag("completed-day-window"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(department == null, onClick = { department = null }, label = { Text("Wszystkie") })
            ProductionLine.entries.forEach { item ->
                FilterChip(department == item.name, onClick = { department = item.name }, label = { Text(item.title) },
                    modifier = Modifier.testTag("completed-filter-${item.name}"))
            }
        }
        ui.operationError?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        when {
            ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.loadError != null -> Column(Modifier.padding(12.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::reload) { Text("Ponów odczyt") }
            }
            else -> VerticalPager(state = days, modifier = Modifier.fillMaxWidth().weight(1f).testTag("completed-days"),
                userScrollEnabled = enabled) { index ->
                val day = firstDay.plusDays(index.toLong())
                CompletedDayContent(day, line, ui, enabled && index == days.currentPage && !days.isScrollInProgress,
                    onDetails = { detailsId = it },
                    onNotes = { model.clearError(); notesId = it },
                    onRejectedDetails = { rejectDetailsId = it },
                    onDeleteRejected = { model.clearError(); deletingId = it; removalRequest = UUID.randomUUID().toString() })
            }
        }
    }
    if (calendarOpen) QueueDatePicker(date, { calendarOpen = false }, { changeDay(it); calendarOpen = false })
    addingId?.let { id -> key(id) { RejectedGoodsEditor(id, line ?: ProductionLine.BUTTER, date, ui, model) {
        if (ui.lastSavedRequestId == id) ui.rejectedGoods.find { it.id == id }?.let {
            changeDay(it.date)
            if (department != null) department = it.line.name
        }
        addingId = null
    } } }
    products.find { it.entry.id == detailsId }?.let { product ->
        CompletedProductDetails(product, date, enabled, onDelete = {
            model.clearError(); removingId = product.entry.id; removalRequest = UUID.randomUUID().toString(); detailsId = null
        }, onClose = { detailsId = null })
    }
    LaunchedEffect(ui.lastSavedRequestId, removalRequest) {
        if (removalRequest != null && ui.lastSavedRequestId == removalRequest) { removingId = null; deletingId = null; removalRequest = null }
    }
    products.find { it.entry.id == removingId }?.let { product -> key(removalRequest) {
        WarehouseDeleteDialog("Usunąć wpis z magazynu?",
            "${product.entry.title}\n${decimalLabel(product.amount)} ${product.entry.unit.label} · ${queueDateLabel(date)}\nHistoria wykonanej produkcji zostaje zachowana.",
            ui.saving, ui.operationError, "warehouse-delete-confirm",
            onDelete = { pin -> removalRequest?.let { model.removeFromWarehouse(it, product, date, pin) } },
            onClose = { removingId = null; removalRequest = null; model.clearError() })
    } }
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
    ui.rejectedGoods.find { it.id == deletingId }?.let { item -> key(removalRequest) {
        WarehouseDeleteDialog("Usunąć wybrakowany towar?", "${decimalLabel(item.kilograms)} kg · ${queueDateLabel(item.date)}\n${item.description}",
            ui.saving, ui.operationError, "rejected-delete-confirm",
            onDelete = { pin -> removalRequest?.let { model.deleteRejectedGoods(it, item.id, pin) } },
            onClose = { deletingId = null; removalRequest = null; model.clearError() })
    } }
}

internal fun completedOrderLabel(entry: ProductionQueueEntry) = "Zamówiono: ${entry.plannedAmount?.let(::decimalLabel) ?: "—"} ${entry.unit.label}" +
    if (entry.excessAmount.signum() > 0) " + nadmiar: ${decimalLabel(entry.excessAmount)} ${entry.unit.label}" else ""

@Composable
private fun CompletedProductDetails(product: CompletedDayProduct, date: LocalDate, enabled: Boolean, onDelete: () -> Unit, onClose: () -> Unit) {
    val entry = product.entry
    AlertDialog(onDismissRequest = onClose, title = { Text(entry.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("completed-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${entry.line.title} · ${queueDateLabel(date)}")
            entry.butterKind?.let { Text("Rodzaj produktu: ${it.title}") }
            Text("Wyprodukowano tego dnia: ${decimalLabel(product.amount)} ${entry.unit.label}", fontWeight = FontWeight.Bold)
            Text("Łącznie wyprodukowano: ${decimalLabel(entry.producedAmount)} ${entry.unit.label}")
            Text(completedOrderLabel(entry))
            if (!entry.completed) Text((if (entry.pendingOrder) "Oczekuje na zaplanowanie: " else "W kolejce pozostało: ") +
                "${entry.remainingAmount?.let(::decimalLabel) ?: "—"} ${entry.unit.label}")
            if (entry.planDescription.isNotBlank()) Text(entry.planDescription)
            HorizontalDivider(); Text("Zapisy wykonania", fontWeight = FontWeight.Bold)
            product.receipts.forEach { receipt -> Text("${decimalLabel(receipt.amount)} ${entry.unit.label} · ${dateLabel(receipt.occurredAt)}") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Zamknij") } },
        dismissButton = { TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("warehouse-delete")) { Text("Usuń z magazynu") } })
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
