package pl.apargb.milkyway

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class TankEditor { STATE, RECEIVE, TRANSFER }

@Composable
fun TankStockCard(tank: Tank, status: TankStatus, latest: Movement?,
                  onEdit: () -> Unit, onReceive: () -> Unit, onTransfer: () -> Unit, onHistory: () -> Unit,
                  onRouting: () -> Unit, onOilType: () -> Unit, onTopUps: () -> Unit,
                  today: java.time.LocalDate = java.time.LocalDate.now(), now: Instant = Instant.now()) {
    val department = tank.department
    val litres = status.litres
    val capacity = tank.capacityLitres?.let(BigDecimal::valueOf)
    val expiryWarning = tank.isOilTank && status.oilExpiresSoon(today)
    val urgent = tank.isOilTank && status.oilExpiresWithinDay(now, ZoneId.systemDefault())
    val mainColor = if (urgent) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (urgent) OilUrgentSecondary else MaterialTheme.colorScheme.onSurface
    val accent = if (urgent) Color.White else department.accent
    Card(Modifier.fillMaxWidth().testTag("tank-${tank.id}"), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (urgent) OilUrgentBackground else if (expiryWarning) OilWarningBackground else MaterialTheme.colorScheme.surface,
            contentColor = mainColor),
        border = BorderStroke(1.dp, if (urgent) OilUrgentOutline else if (expiryWarning) OilWarningColor else department.accent.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(department.accent))
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tank.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                DepartmentBadge(department)
            }
            Text("Opis zbiornika: ${tank.description}", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = secondaryColor)
            if (tank.isOilTank) AssistChip(onClick = onOilType,
                label = { Text("Rodzaj oleju: ${status.oilType.ifBlank { "niepodany" }}") },
                colors = AssistChipDefaults.assistChipColors(containerColor = department.tint, labelColor = department.accent))
            if (tank.isPowderTank) {
                AssistChip(onClick = onRouting, label = {
                    Text("Wyparka ${status.routing.evaporator ?: "—"} · Kryst. ${status.routing.crystallizer ?: "—"}",
                        style = MaterialTheme.typography.labelMedium)
                }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.AltRoute, null, Modifier.size(16.dp), tint = department.accent) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = department.tint, labelColor = department.accent))
            }
            Surface(color = department.tint, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (litres == null) "Stan: nieustalony" else "Stan: ${decimalLabel(litres)} l",
                        Modifier.fillMaxWidth().testTag("detail-quantity-${tank.id}")
                            .clickable(role = Role.Button, onClickLabel = "Edytuj ilość: ${tank.name}", onClick = onEdit),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = department.accent)
                    Text("Pojemność: ${capacityLabel(tank.capacityLitres)}", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (litres != null && capacity != null && capacity.signum() > 0) {
                        val fraction = litres.divide(capacity, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                            color = department.accent, trackColor = department.accent.copy(alpha = 0.12f))
                        Text("Napełnienie: ${decimalLabel(litres.multiply(BigDecimal("100")).divide(capacity, 1, RoundingMode.HALF_UP))}% · Wolne: ${decimalLabel(capacity - litres)} l",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Text("Zawartość: " + when {
                litres?.signum() == 0 -> "zbiornik pusty"
                status.material.isNotBlank() -> status.material
                litres == null -> "nieustalona"
                else -> "niepodana"
            }, fontWeight = FontWeight.SemiBold,
                color = if (litres?.signum() == 0) EmptyTankColor else mainColor)
            MeasurementTiles(status.measurements, tank.usesFatMeasurement, tank.isOilTank)
            if (tank.isOilTank) {
                Text("Data produkcji: ${status.oilBatch.producedOn.oilDateLabel()}", modifier = Modifier.clickable(onClick = onOilType),
                    fontWeight = FontWeight.SemiBold)
                Text("Data ważności: ${status.oilBatch.expiresOn.oilDateLabel()}",
                    modifier = Modifier.testTag("detail-oil-expiry-${tank.id}").clickable(onClick = onOilType),
                    fontWeight = FontWeight.Bold, color = if (urgent) Color.White else if (expiryWarning) OilWarningColor else mainColor)
                if (expiryWarning) Text(if (urgent) "Uwaga: ostatnia doba ważności lub termin już minął."
                    else "Uwaga: termin ważności za mniej niż 3 dni.", color = if (urgent) Color.White else OilWarningColor,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
            status.laboratoryMeasuredAt?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LaboratoryBadge(it, Modifier.testTag("detail-lab-marker-${tank.id}"))
                    Text("Laboratorium: ${dateLabel(it)}", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium, color = if (urgent) secondaryColor else EmptyTankColor)
                }
            }
            Text("Ostatnie napełnienie: ${status.filledAt?.let(::dateLabel) ?: "—"}", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold)
            if (latest != null) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ostatnia operacja", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
                    Text(movementDescription(latest), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(dateLabel(latest.occurredAt), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium, color = secondaryColor)
                }
            }
            if (litres == null) Text("Dotknij stanu, aby wpisać ilość w m³. Dla pustego zbiornika wpisz 0.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onReceive, enabled = litres != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (litres != null && litres.signum() > 0) "Dolej materiał" else "Napełnij")
            }
            TextButton(onClick = onTopUps, modifier = Modifier.fillMaxWidth().testTag("top-ups-${tank.id}"),
                colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                Icon(Icons.Outlined.History, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Wszystkie dolania")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTransfer, enabled = litres != null && litres.signum() > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = department.accent, contentColor = Color.White),
                    modifier = Modifier.weight(1f)) { Text("Przenieś materiał") }
                TextButton(onClick = onHistory, colors = ButtonDefaults.textButtonColors(contentColor = accent),
                    modifier = Modifier.weight(1f)) { Text("Historia") }
            }
        }
    }
}

@Composable
private fun MeasurementTiles(values: Measurements, usesFat: Boolean, oil: Boolean) {
    val mainMeasurement = if (usesFat) "Tłuszcz" to (values.fatPercent?.let { "${decimalLabel(it)} %" } ?: "—")
        else "Brix" to (values.brix?.let { "${decimalLabel(it)} °Bx" } ?: "—")
    val tiles = (if (oil) emptyList() else listOf(mainMeasurement,
        "pH" to (values.ph?.let(::decimalLabel) ?: "—"), "SH" to (values.sh?.let(::decimalLabel) ?: "—"))) + listOf(
        "Temperatura" to (values.temperature?.let { "${decimalLabel(it)} °C" } ?: "—"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MeasurementSummary(values: Measurements, usesFat: Boolean, oil: Boolean = false) {
    fun value(number: BigDecimal?, unit: String = "") = number?.let { decimalLabel(it) + unit } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val mainMeasurement = if (usesFat) {
            "Tłuszcz: ${value(values.fatPercent, " %")}"
        } else {
            "Brix: ${value(values.brix, " °Bx")}"
        }
        if (!oil) Text("$mainMeasurement · pH: ${value(values.ph)} · SH: ${value(values.sh)}",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (!oil && usesFat && values.brix != null) Text("Wcześniejszy pomiar Brix: ${value(values.brix, " °Bx")}",
            style = MaterialTheme.typography.bodySmall)
        Text("Temperatura: ${value(values.temperature, " °C")}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TankOperationDialog(tank: Tank, mode: TankEditor, ui: InventoryUiState, shift: Int?,
                        model: InventoryViewModel, onClose: () -> Unit) {
    val state = ui.overview.states[tank.id] ?: TankStatus()
    val initial = if (mode == TankEditor.STATE) state.measurements else Measurements()
    var amount by rememberSaveable { mutableStateOf(if (mode == TankEditor.STATE) state.litres.cubicMetresInputText() else "") }
    var material by rememberSaveable { mutableStateOf(if (mode != TankEditor.TRANSFER) state.material else "") }
    var source by rememberSaveable { mutableStateOf("") }
    var targetId by rememberSaveable { mutableStateOf("") }
    var brix by rememberSaveable { mutableStateOf(initial.brix.inputText()) }
    var fatPercent by rememberSaveable { mutableStateOf(initial.fatPercent.inputText()) }
    var ph by rememberSaveable { mutableStateOf(initial.ph.inputText()) }
    var sh by rememberSaveable { mutableStateOf(initial.sh.inputText()) }
    var temperature by rememberSaveable { mutableStateOf(initial.temperature.inputText()) }
    var note by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) {
        if (ui.lastSavedRequestId == requestId) onClose()
    }
    val enabled = !ui.saving
    val target = AppContent.tanks.find { it.id == targetId }
    val receivingTank = if (mode == TankEditor.TRANSFER) target else tank
    val usesFat = receivingTank?.usesFatMeasurement == true
    val oil = receivingTank?.isOilTank == true
    fun clear() = model.clearError()

    TankFormDialog(
        onClose = onClose,
        saving = ui.saving,
        title = when (mode) {
            TankEditor.STATE -> "Edytuj: ${tank.name}"
            TankEditor.RECEIVE -> if (state.litres?.signum() == 1) "Dolej do: ${tank.name}" else "Napełnij: ${tank.name}"
            TankEditor.TRANSFER -> "Przenieś z ${tank.name}"
        },
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DepartmentBadge(tank.department)
                if (mode != TankEditor.STATE) {
                    Text("Obecny stan: ${state.litres?.let { "${decimalLabel(it)} l" } ?: "nieustalony"}")
                }
                if (mode == TankEditor.TRANSFER) {
                    TankPicker(tank.id, targetId, ui, enabled) {
                        targetId = it
                        material = if (ui.overview.states[it]?.litres?.signum() == 0) state.material else ""
                        clear()
                    }
                    if (target != null) {
                        val targetLevel = ui.overview.states[target.id]?.litres
                        Text("Stan docelowy: ${targetLevel?.let { "${decimalLabel(it)} l" } ?: "nieustalony — najpierw ustaw stan tego zbiornika"}")
                    }
                }
                if (mode == TankEditor.RECEIVE) {
                    Text("Dostawa z zewnątrz. Ruch między zbiornikami zapiszesz przez „Przenieś materiał”.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = source, onValueChange = { source = it.take(120); clear() },
                        label = { Text("Skąd pochodzi materiał") }, placeholder = { Text("Np. dostawa / nazwa źródła") },
                        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                NumberField(if (mode == TankEditor.STATE) "Nowy stan [m³]" else "Ilość [m³]",
                    amount, enabled, supportingText = volumeInputHint(amount)) { amount = it; clear() }
                if (mode == TankEditor.RECEIVE && state.litres != null) {
                    val added = runCatching { parseCubicMetres(amount) }.getOrNull()
                    if (added != null && added.signum() > 0) {
                        val after = state.litres + added
                        Text("Stan po dolaniu: ${decimalLabel(after)} l", fontWeight = FontWeight.SemiBold,
                            color = if (tank.capacityLitres?.let { after > BigDecimal.valueOf(it) } == true)
                                MaterialTheme.colorScheme.error else tank.department.accent,
                            modifier = Modifier.testTag("top-up-preview"))
                    }
                }
                if (mode == TankEditor.STATE) Text("Puste pole ilości pozostawia stan bez zmian.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = material, onValueChange = { material = it.take(1000); clear() },
                    label = { Text("Zawartość / materiał") }, modifier = Modifier.fillMaxWidth(), enabled = enabled, maxLines = 4)
                if (receivingTank != null) {
                    Text("Pojemność celu: ${capacityLabel(receivingTank.capacityLitres)}")
                    if (receivingTank.capacityLitres == null) {
                        Text("Pojemność nieznana — maksymalny stan nie jest kontrolowany.", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(if (mode == TankEditor.STATE) "Parametry materiału" else "Parametry materiału po napełnieniu",
                    fontWeight = FontWeight.SemiBold)
                if (mode == TankEditor.STATE) Text("Stan 0 m³ usuwa opis zawartości, bieżące pomiary i datę napełnienia. Historia pozostaje.", style = MaterialTheme.typography.bodySmall)
                if (mode != TankEditor.STATE) {
                    Text("Wpisz aktualne pomiary. Puste pola oznaczają brak pomiaru; parametry nie są uśredniane ani przenoszone automatycznie.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (usesFat) NumberField("Tłuszcz [%]", fatPercent, enabled) { fatPercent = it; clear() }
                else if (receivingTank != null && !oil) NumberField("Brix [°Bx]", brix, enabled) { brix = it; clear() }
                if (!oil) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("pH", ph, enabled, Modifier.weight(1f)) { ph = it; clear() }
                    NumberField("SH", sh, enabled, Modifier.weight(1f)) { sh = it; clear() }
                }
                NumberField("Temperatura [°C]", temperature, enabled, signed = true) { temperature = it; clear() }
                Text("Data ostatniego napełnienia zapisuje się automatycznie przy dopływie materiału. Edycja samych parametrów jej nie zmienia.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text("Uwagi (opcjonalnie)") },
                    modifier = Modifier.fillMaxWidth(), enabled = enabled, maxLines = 3)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        canSave = (mode == TankEditor.STATE || amount.isNotBlank()) && (mode != TankEditor.TRANSFER || target != null),
        onSave = {
                try {
                    val litres = parseCubicMetres(amount, required = mode != TankEditor.STATE)
                    val measurements = Measurements(
                        brix = if (usesFat || oil) null else parseDecimal(brix, "Brix"),
                        ph = if (oil) null else parseDecimal(ph, "pH"), sh = if (oil) null else parseDecimal(sh, "SH"),
                        temperature = parseDecimal(temperature, "Temperatura"),
                        fatPercent = if (usesFat) parseDecimal(fatPercent, "Tłuszcz") else null)
                    val externalSource = source
                    val targetTankId = targetId
                    val savedNote = note.trim()
                    val savedMaterial = material.trim()
                    model.save(requestId) { rules ->
                        val now = System.currentTimeMillis()
                        when (mode) {
                            TankEditor.STATE -> if (litres == null) rules.updateDetails(tank.id, savedMaterial, measurements, now, savedNote, shift)
                                else rules.setState(tank.id, litres, measurements, now, savedNote, shift, savedMaterial)
                            TankEditor.RECEIVE -> rules.receive(tank.id, litres!!, externalSource, measurements, now, savedNote, shift, savedMaterial)
                            TankEditor.TRANSFER -> rules.transfer(tank.id, targetTankId, litres!!, measurements, now, savedNote, shift, savedMaterial)
                        }
                    }
                } catch (error: IllegalArgumentException) {
                    model.showError(error.message ?: "Sprawdź wpisane dane.")
                }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TankFormDialog(title: String, saving: Boolean, canSave: Boolean, onClose: () -> Unit,
                           onSave: () -> Unit, footer: @Composable () -> Unit = {}, compact: Boolean = false,
                           content: @Composable () -> Unit) {
    Dialog(onDismissRequest = { if (!saving) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(if (compact) Modifier.fillMaxWidth().padding(horizontal = 12.dp).imePadding().fillMaxHeight(.78f) else Modifier.fillMaxSize(),
            shape = RoundedCornerShape(if (compact) 24.dp else 0.dp),
            border = if (compact) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
            Scaffold(
                topBar = { if (compact) Text(title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    else TopAppBar(title = { Text(title) }) },
                bottomBar = {
                    Column {
                        footer()
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().then(if (compact) Modifier else Modifier.imePadding()).padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onClose, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Anuluj") }
                            Button(onClick = onSave, enabled = !saving && canSave, modifier = Modifier.weight(1f).testTag("form-save-$title")) {
                                Text(if (saving) "Zapisywanie…" else "Zapisz")
                            }
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(if (compact) 14.dp else 20.dp)) { content() }
            }
        }
    }
}

@Composable
internal fun NumberField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
                        signed: Boolean = false, supportingText: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.take(24)) }, label = { Text(label, maxLines = 1) },
        shape = RoundedCornerShape(14.dp),
        supportingText = supportingText?.let { hint -> { Text(hint) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
        singleLine = true, enabled = enabled, modifier = modifier.fillMaxWidth())
}

@Composable
private fun TankPicker(sourceId: String, selectedId: String, ui: InventoryUiState, enabled: Boolean,
                       onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(AppContent.tanks.find { it.id == selectedId }?.let { "Do: ${it.name}" } ?: "Wybierz zbiornik docelowy")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            AppContent.tanks.filter { it.id != sourceId }.forEach { tank ->
                DropdownMenuItem(modifier = Modifier.testTag("target-${tank.id}"), text = {
                    Column {
                        Text(tank.name)
                        Text(ui.overview.states[tank.id]?.litres?.let { "Stan: ${decimalLabel(it)} l" } ?: "Stan nieustalony",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = { onSelect(tank.id); expanded = false })
            }
        }
    }
}

@Composable
fun TankHistoryDialog(history: HistoryUiState, onClose: () -> Unit, onRetry: () -> Unit) {
    val tank = AppContent.tanks.find { it.id == history.tankId } ?: return
    AlertDialog(onDismissRequest = onClose, title = { Text("Historia: ${tank.name}") }, text = {
        when {
            history.loading -> CircularProgressIndicator()
            history.error != null -> Column { Text(history.error); TextButton(onClick = onRetry) { Text("Ponów") } }
            history.events.isEmpty() -> Text("Brak zapisanych operacji.")
            else -> LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("Ostatnie operacje (do 100 wpisów)", style = MaterialTheme.typography.bodySmall) }
                items(history.events, key = { it.id }) { event ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(movementDescription(event), fontWeight = FontWeight.SemiBold)
                        Text(dateLabel(event.occurredAt) + (event.shift?.let { " · Zmiana $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        Text("Parametry w zbiorniku docelowym", style = MaterialTheme.typography.labelSmall)
                        Text("Zawartość: ${event.material.ifBlank { "niepodana" }}")
                        MeasurementSummary(event.measurements, AppContent.tanks.find { it.id == event.targetId }?.usesFatMeasurement == true, tank.isOilTank)
                        event.laboratoryMeasuredAt?.let { Text("Pomiar z laboratorium: ${dateLabel(it)}", color = EmptyTankColor,
                            style = MaterialTheme.typography.bodySmall) }
                        if (event.displayNote().isNotBlank()) Text(event.displayNote())
                        event.note.oilBatchOrNull()?.let { Text("Produkcja: ${it.producedOn.oilDateLabel()} · Ważność: ${it.expiresOn.oilDateLabel()}", style = MaterialTheme.typography.bodySmall) }
                        if (event.oilType.isNotBlank()) Text("Rodzaj oleju: ${event.oilType}", style = MaterialTheme.typography.bodySmall)
                        if (event.routing != TankRouting()) Text("Oznaczenie trasy: ${routingLabel(event.routing)}", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Zamknij") } })
}

fun movementDescription(event: Movement): String {
    fun name(id: String) = AppContent.tanks.find { it.id == id }?.name ?: id
    return when (event.type) {
        MovementType.SET_STATE -> "Ustawiono stan ${name(event.targetId)}: ${decimalLabel(event.litres)} l " +
            "(poprzednio: ${event.previousLitres?.let { "${decimalLabel(it)} l" } ?: "nieustalony"})"
        MovementType.RECEIPT -> "${event.externalSource} → ${name(event.targetId)} · ${decimalLabel(event.litres)} l"
        MovementType.TRANSFER -> "${name(event.sourceId!!)} → ${name(event.targetId)} · ${decimalLabel(event.litres)} l"
        MovementType.ROUTING -> "Trasa ${name(event.targetId)}: ${routingLabel(event.routing)}"
        MovementType.DETAILS -> "Ręczna edycja zawartości / parametrów: ${name(event.targetId)}"
        MovementType.OIL_TYPE -> "Rodzaj oleju ${name(event.targetId)}: ${event.oilType.ifBlank { "niepodany" }}"
    }
}

private fun routingLabel(routing: TankRouting): String =
    "wyparka ${routing.evaporator ?: "—"}, krystalizator ${routing.crystallizer ?: "—"}"

@Composable
fun TankOilTypeDialog(tank: Tank, ui: InventoryUiState, model: InventoryViewModel, shift: Int?, onClose: () -> Unit) {
    val batch = ui.overview.states[tank.id]?.oilBatch ?: OilBatch()
    var producedOn by rememberSaveable { mutableStateOf(batch.producedOn?.toString() ?: "") }
    var expiresOn by rememberSaveable { mutableStateOf(batch.expiresOn?.toString() ?: "") }
    var oilType by rememberSaveable { mutableStateOf(ui.overview.states[tank.id]?.oilType ?: "") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    TankFormDialog(title = "Rodzaj oleju: ${tank.name}", saving = ui.saving, canSave = true,
        onClose = onClose, onSave = {
            model.save(requestId) { it.setOilBatch(tank.id, oilType, OilBatch(
                producedOn.takeIf(String::isNotBlank)?.let(java.time.LocalDate::parse),
                expiresOn.takeIf(String::isNotBlank)?.let(java.time.LocalDate::parse)), System.currentTimeMillis(), shift) }
        }, content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = oilType, onValueChange = { oilType = it.take(120); model.clearError() },
                    label = { Text("Rodzaj oleju") }, placeholder = { Text("Np. rzepakowy") },
                    enabled = !ui.saving, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    shape = RoundedCornerShape(14.dp))
                OilDateField("Data produkcji", producedOn, !ui.saving, "oil-production-date") { producedOn = it; model.clearError() }
                OilDateField("Data ważności", expiresOn, !ui.saving, "oil-expiry-date") { expiresOn = it; model.clearError() }
                Text("Zbiornik z olejem zmieni kolor na czerwony, gdy do daty ważności pozostaną mniej niż 3 dni. Napełnienie jest zapisywane automatycznie z godziną.", style = MaterialTheme.typography.bodySmall)
                Text("Puste pole usuwa oznaczenie rodzaju oleju.", style = MaterialTheme.typography.bodySmall)
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
}

@Composable
fun TankRoutingDialog(tank: Tank, ui: InventoryUiState, model: InventoryViewModel, shift: Int?, onClose: () -> Unit) {
    val current = ui.overview.states[tank.id]?.routing ?: TankRouting()
    var evaporator by rememberSaveable { mutableStateOf(current.evaporator) }
    var crystallizer by rememberSaveable { mutableStateOf(current.crystallizer) }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    AlertDialog(onDismissRequest = { if (!ui.saving) onClose() }, title = { Text("Trasa: ${tank.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wyparka")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, "C", "F").forEach { value ->
                    FilterChip(selected = evaporator == value, onClick = { evaporator = value; model.clearError() },
                        label = { Text(value ?: "—") }, enabled = !ui.saving)
                }
            }
            Text("Krystalizator")
            (listOf<Int?>(null) + crystallizerNumbers).chunked(4).forEach { numbers ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    numbers.forEach { value ->
                        FilterChip(selected = crystallizer == value, onClick = { crystallizer = value; model.clearError() },
                            label = { Text(value?.toString() ?: "—") }, enabled = !ui.saving)
                    }
                }
            }
            Text("Znak — usuwa wybór. Oznaczenie trasy nie zmienia stanu napełnienia.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !ui.saving, modifier = Modifier.fillMaxWidth().testTag("reset-routing-${tank.id}"),
                onClick = {
                    model.clearError()
                    model.save(requestId) { it.resetRouting(tank.id, System.currentTimeMillis(), shift) }
                }) {
                Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Wyzeruj trasę")
            }
            Text("Przy wstrzymaniu produkcji usuwa wyparkę i krystalizator oraz zapisuje zdarzenie w historii.",
                style = MaterialTheme.typography.bodySmall)
            ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !ui.saving, onClick = {
            val routing = TankRouting(evaporator, crystallizer)
            model.save(requestId) { it.setRouting(tank.id, routing, System.currentTimeMillis(), shift) }
        }) { Text(if (ui.saving) "Zapisywanie…" else "Zapisz") }
    }, dismissButton = { TextButton(enabled = !ui.saving, onClick = onClose) { Text("Anuluj") } })
}

internal fun dateLabel(timestamp: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("pl-PL"))
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
