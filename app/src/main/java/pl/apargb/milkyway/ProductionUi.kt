package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.RoundingMode

enum class ProductionDestination(val title: String) {
    MENU("Produkcja"), TANKS("Zbiorniki — przewidywana produkcja"), NOTES("Wpisy"),
    QUEUE("Kolejka produkcji"), QUEUE_BUTTER("Masłownia"), QUEUE_POWDER("Proszkownia"), QUEUE_UHT("UHT");

    val parent: ProductionDestination? get() = when (this) {
        MENU -> null
        QUEUE_BUTTER, QUEUE_POWDER, QUEUE_UHT -> QUEUE
        else -> MENU
    }
}

@Composable
fun ProductionPage(scope: Int, notesModel: WorkNotesViewModel, notes: WorkNotesState,
                   inventory: InventoryUiState, assumptions: Map<Department, ProductionAssumptions>,
                   productionModel: ProductionViewModel, onEdit: (Tank) -> Unit, onRetry: () -> Unit,
                   destination: ProductionDestination, onNavigate: (ProductionDestination) -> Unit,
                   queue: ProductionQueueState, queueModel: ProductionQueueViewModel) {
    key(destination) {
        when (destination) {
            ProductionDestination.TANKS -> ProductionTanksPage(inventory, assumptions, productionModel, onEdit, onRetry)
            ProductionDestination.NOTES -> WorkNotesPage(NoteKind.PRODUCTION, scope, notesModel, notes)
            ProductionDestination.MENU, ProductionDestination.QUEUE -> ProductionMenu(destination, onNavigate)
            else -> ProductionQueuePage(when (destination) {
                ProductionDestination.QUEUE_BUTTER -> ProductionLine.BUTTER
                ProductionDestination.QUEUE_POWDER -> ProductionLine.POWDER
                else -> ProductionLine.UHT
            }, queue, queueModel)
        }
    }
}

@Composable
private fun ProductionMenu(destination: ProductionDestination, onNavigate: (ProductionDestination) -> Unit) {
    val queue = destination == ProductionDestination.QUEUE
    val options = if (queue) listOf(ProductionDestination.QUEUE_BUTTER, ProductionDestination.QUEUE_POWDER, ProductionDestination.QUEUE_UHT)
        else listOf(ProductionDestination.TANKS, ProductionDestination.QUEUE, ProductionDestination.NOTES)
    LazyColumn(Modifier.fillMaxSize().testTag(if (queue) "production-queue-menu" else "production-menu"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(destination.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (queue) "Wybierz dział produkcji." else "Wybierz zbiorniki, kolejkę lub wpisy z przebiegu pracy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(options, key = { it.name }) { option ->
            val tone = when (option) {
                ProductionDestination.TANKS, ProductionDestination.QUEUE_POWDER -> Department.Powder
                ProductionDestination.NOTES, ProductionDestination.QUEUE_BUTTER -> Department.Butter
                else -> Department.Processing
            }
            val icon = when (option) {
                ProductionDestination.TANKS -> Icons.Outlined.WaterDrop
                ProductionDestination.NOTES -> Icons.Outlined.NoteAlt
                ProductionDestination.QUEUE -> Icons.Outlined.FormatListNumbered
                ProductionDestination.QUEUE_BUTTER -> Icons.Outlined.BakeryDining
                ProductionDestination.QUEUE_POWDER -> Icons.Outlined.Grain
                else -> Icons.Outlined.LocalFireDepartment
            }
            MenuTile(option.title, when (option) {
                ProductionDestination.TANKS -> "Przybliżona ilość proszku i masła z parametrów zbiorników"
                ProductionDestination.NOTES -> "Notatki o przebiegu produkcji na tej zmianie"
                ProductionDestination.QUEUE -> "Masłownia, proszkownia i UHT"
                else -> "Kolejka produkcji działu"
            }, icon, tone, Modifier.fillMaxWidth().testTag("production-${option.name.lowercase()}")) { onNavigate(option) }
        }
    }
}

@Composable
private fun ProductionTanksPage(ui: InventoryUiState, assumptions: Map<Department, ProductionAssumptions>,
                               model: ProductionViewModel, onEdit: (Tank) -> Unit, onRetry: () -> Unit) {
    var departmentName by rememberSaveable { mutableStateOf(Department.Powder.name) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val department = Department.valueOf(departmentName)
    val settings = assumptions.getValue(department)
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = productionDepartments.indexOf(department), contentColor = department.accent) {
            productionDepartments.forEach { item ->
                Tab(selected = item == department, onClick = { departmentName = item.name },
                    modifier = Modifier.testTag("production-department-${item.name}"), text = { Text(item.title) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (department == Department.Powder) "Produkcja proszku [kg]" else "Produkcja masła [kg]",
                    fontWeight = FontWeight.Bold, color = department.accent)
                Text(if (department == Department.Powder) "Z ilości materiału i Brix" else "Z ilości materiału i tłuszczu",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { settingsOpen = true }, modifier = Modifier.testTag("production-settings")) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Założenia")
            }
        }
        Text(if (settings.densityKgPerLitre == null || settings.productContentPercent == null)
            "Uzupełnij gęstość i skład docelowego produktu w Założeniach."
        else "Gęstość: ${decimalLabel(settings.densityKgPerLitre)} kg/l · skład produktu: ${decimalLabel(settings.productContentPercent)}% · uzysk: ${decimalLabel(settings.recoveryPercent)}%",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.loadError != null -> Column(Modifier.padding(16.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Ponów odczyt") }
            }
            else -> LazyColumn(Modifier.weight(1f).testTag("production-list"),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Przybliżenie z aktualnego stanu zbiornika, nie zapis rzeczywistej produkcji.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(AppContent.tanks.filter { it.department == department }, key = { it.id }) { tank ->
                    val status = ui.overview.states[tank.id] ?: TankStatus()
                    val estimate = estimateProduction(tank, status, settings)
                    Card(onClick = { onEdit(tank) }, modifier = Modifier.fillMaxWidth().testTag("production-tank-${tank.id}"),
                        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = department.tint)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tank.name, fontWeight = FontWeight.Bold, color = department.accent)
                                    status.laboratoryMeasuredAt?.let { LaboratoryBadge(it, Modifier.testTag("production-lab-marker-${tank.id}")) }
                                }
                                Text(status.litres?.let { "${decimalLabel(it)} l" } ?: "Stan nieustalony")
                            }
                            Text(if (department == Department.Butter)
                                "Tłuszcz: ${status.measurements.fatPercent?.let { "${decimalLabel(it)} %" } ?: "—"}"
                            else "Brix: ${status.measurements.brix?.let { "${decimalLabel(it)} °Bx" } ?: "—"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(estimate.kilograms?.let { "≈ ${decimalLabel(it.setScale(1, RoundingMode.HALF_UP))} kg" }
                                ?: "Produkcja: — · ${estimate.missing}",
                                modifier = Modifier.testTag("estimate-${tank.id}"), fontWeight = FontWeight.SemiBold,
                                color = if (status.litres?.signum() == 0) EmptyTankColor else department.accent)
                        }
                    }
                }
            }
        }
    }
    if (settingsOpen) ProductionSettingsDialog(department, settings,
        onClose = { settingsOpen = false }, onSave = { model.save(department, it); settingsOpen = false })
}

@Composable
private fun ProductionSettingsDialog(department: Department, initial: ProductionAssumptions,
                                     onClose: () -> Unit, onSave: (ProductionAssumptions) -> Unit) {
    var density by rememberSaveable { mutableStateOf(initial.densityKgPerLitre.inputText()) }
    var content by rememberSaveable { mutableStateOf(initial.productContentPercent.inputText()) }
    var recovery by rememberSaveable { mutableStateOf(initial.recoveryPercent.inputText()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    TankFormDialog("Założenia: ${department.title}", saving = false, canSave = true, onClose = onClose,
        onSave = {
            try {
                val values = ProductionAssumptions(parseDecimal(density, "Gęstość", true),
                    parseDecimal(content, "Skład produktu", true), parseDecimal(recovery, "Uzysk", true)!!)
                values.validate()
                onSave(values)
            } catch (failure: IllegalArgumentException) { error = failure.message }
        }, content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Wpisz założenia stosowane w zakładzie. Obowiązują dla wszystkich zbiorników tego działu.")
                NumberField("Gęstość materiału [kg/l]", density, true) { density = it; error = null }
                NumberField(if (department == Department.Powder) "Sucha masa produktu [%]" else "Tłuszcz w produkcie [%]",
                    content, true) { content = it; error = null }
                NumberField("Uzysk składnika [%]", recovery, true) { recovery = it; error = null }
                Text("100% uzysku oznacza obliczenie teoretyczne bez strat.", style = MaterialTheme.typography.bodySmall)
                Text(if (department == Department.Powder) "Brix jest tu przybliżeniem udziału suchej masy materiału."
                    else "Obliczenie wykorzystuje wpisany procent tłuszczu materiału.")
                Text("Produkcja [kg] = ilość [l] × gęstość [kg/l] × skład materiału [%] ÷ skład produktu [%] × uzysk [%] ÷ 100.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
}
