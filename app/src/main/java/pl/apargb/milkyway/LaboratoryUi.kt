package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import java.util.UUID

internal fun laboratoryPinMatches(pin: String): Boolean {
    if (pin.length != 4 || pin.any { it !in '0'..'9' }) return false
    val expected = "9b04f5410f803df1f5f93aa3778059efe90db326c19f5d7ba85414c96739b3bd"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8)))
}

@Composable
fun LaboratoryLockPage(onUnlock: () -> Unit) {
    // Neither the entered PIN nor permission to enter is restored from saved instance state.
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Laboratorium", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Wpisz PIN, aby otworzyć pomiary zbiorników.")
                OutlinedTextField(value = pin, onValueChange = { pin = it.filter { digit -> digit in '0'..'9' }.take(4); error = false },
                    label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error, modifier = Modifier.fillMaxWidth().testTag("lab-pin"))
                if (error) Text("Nieprawidłowy PIN.", color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (laboratoryPinMatches(pin)) { pin = ""; onUnlock() }
                    else { error = true; pin = "" }
                }, enabled = pin.length == 4, modifier = Modifier.fillMaxWidth().testTag("lab-unlock")) { Text("Odblokuj") }
            }
        }
    }
}

@Composable
fun LaboratoryPage(ui: InventoryUiState, model: InventoryViewModel, shift: Int, onRetry: () -> Unit) {
    var departmentName by rememberSaveable { mutableStateOf(Department.Reception.name) }
    var expanded by remember { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val department = Department.valueOf(departmentName)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Laboratorium", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Dotknij zbiornika, aby wpisać pomiary.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("lab-department")) {
                Text("Dział: ${department.title}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Department.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.title) }, onClick = { departmentName = item.name; expanded = false },
                        modifier = Modifier.testTag("lab-department-${item.name}"))
                }
            }
        }
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.loadError != null -> Column(Modifier.padding(16.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Ponów odczyt") }
            }
            else -> key(department) {
                LazyColumn(Modifier.weight(1f).testTag("lab-list"), contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppContent.tanks.filter { it.department == department }, key = { it.id }) { tank ->
                        val status = ui.overview.states[tank.id] ?: TankStatus()
                        Card(onClick = { model.clearError(); editingId = tank.id },
                            modifier = Modifier.fillMaxWidth().testTag("lab-tank-${tank.id}"), shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = department.tint)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(tank.name, fontWeight = FontWeight.Bold, color = department.accent)
                                    status.laboratoryMeasuredAt?.let { LaboratoryBadge(it, Modifier.testTag("lab-list-marker-${tank.id}")) }
                                }
                                Text(status.litres?.let { "Stan: ${decimalLabel(it)} l" } ?: "Stan nieustalony", style = MaterialTheme.typography.bodySmall)
                                val first = if (tank.usesFatMeasurement) "Tłuszcz: ${status.measurements.fatPercent?.let(::decimalLabel) ?: "—"} %"
                                    else "Brix: ${status.measurements.brix?.let(::decimalLabel) ?: "—"} °Bx"
                                if (!tank.isOilTank) Text(first, style = MaterialTheme.typography.bodySmall)
                                Text((if (tank.isOilTank) "" else "pH: ${status.measurements.ph?.let(::decimalLabel) ?: "—"} · SH: ${status.measurements.sh?.let(::decimalLabel) ?: "—"} · ") + "Temp.: ${status.measurements.temperature?.let(::decimalLabel) ?: "—"} °C",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    AppContent.tanks.find { it.id == editingId }?.let { tank ->
        key(tank.id) { LaboratoryMeasurementsDialog(tank, ui, model, shift) { editingId = null } }
    }
}

@Composable
private fun LaboratoryMeasurementsDialog(tank: Tank, ui: InventoryUiState, model: InventoryViewModel,
                                         shift: Int, onClose: () -> Unit) {
    val state = ui.overview.states[tank.id] ?: TankStatus()
    val initial = state.measurements
    var mainValue by rememberSaveable { mutableStateOf((if (tank.usesFatMeasurement) initial.fatPercent else initial.brix).inputText()) }
    var ph by rememberSaveable { mutableStateOf(initial.ph.inputText()) }
    var sh by rememberSaveable { mutableStateOf(initial.sh.inputText()) }
    var temperature by rememberSaveable { mutableStateOf(initial.temperature.inputText()) }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(ui.lastSavedRequestId) { if (ui.lastSavedRequestId == requestId) onClose() }
    TankFormDialog(title = "Pomiary: ${tank.name}", saving = ui.saving, canSave = state.litres?.signum() != 0,
        onClose = onClose, onSave = {
            try {
                val value = parseDecimal(mainValue, if (tank.usesFatMeasurement) "Tłuszcz" else "Brix")
                val measurements = Measurements(brix = if (tank.usesFatMeasurement || tank.isOilTank) null else value,
                    fatPercent = if (tank.usesFatMeasurement) value else null, ph = if (tank.isOilTank) null else parseDecimal(ph, "pH"),
                    sh = if (tank.isOilTank) null else parseDecimal(sh, "SH"), temperature = parseDecimal(temperature, "Temperatura"))
                model.save(requestId) { it.updateMeasurements(tank.id, measurements, System.currentTimeMillis(), shift) }
            } catch (error: IllegalArgumentException) { model.showError(error.message ?: "Sprawdź wpisane dane.") }
        }, content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.litres?.let { "Stan: ${decimalLabel(it)} l" } ?: "Stan nieustalony")
                if (state.litres?.signum() == 0) Text("Zbiornik jest pusty. Ustal ilość materiału w zakładce Zbiorniki.", color = MaterialTheme.colorScheme.error)
                Text("Zapis pomiaru zachowuje ilość, zawartość i datę napełnienia. Czas pomiaru trafia do historii.", style = MaterialTheme.typography.bodySmall)
                if (!tank.isOilTank) NumberField(if (tank.usesFatMeasurement) "Tłuszcz [%]" else "Brix [°Bx]", mainValue, !ui.saving) { mainValue = it; model.clearError() }
                if (!tank.isOilTank) NumberField("pH", ph, !ui.saving) { ph = it; model.clearError() }
                if (!tank.isOilTank) NumberField("SH", sh, !ui.saving) { sh = it; model.clearError() }
                NumberField("Temperatura [°C]", temperature, !ui.saving, signed = true) { temperature = it; model.clearError() }
                ui.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
}
