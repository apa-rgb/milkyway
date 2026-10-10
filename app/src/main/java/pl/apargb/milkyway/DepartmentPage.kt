package pl.apargb.milkyway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TankDepartmentPage(department: Department, ui: InventoryUiState, onDepartment: (Department) -> Unit,
                       onTank: (Tank) -> Unit, onEdit: (Tank) -> Unit, onRouting: (Tank) -> Unit,
                       onOilType: (Tank) -> Unit, onRetry: () -> Unit,
                       onReset: (Tank) -> Unit = {}, today: LocalDate = LocalDate.now()) {
    val tanks = AppContent.tanks.filter { it.department == department }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Department.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        val selected = item == department
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("department-${item.name}")
                            .clip(shape).background(if (selected) item.tint else MaterialTheme.colorScheme.surface)
                            .border(if (selected) 2.dp else 1.dp, item.accent.copy(alpha = if (selected) 1f else 0.4f), shape)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onDepartment(item) })
                            .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(item.title, fontSize = 14.sp, lineHeight = 18.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = item.accent, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(department.tint).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(department.icon, null, Modifier.size(18.dp), tint = department.accent)
            Text(department.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = department.accent)
            Text("${tanks.size} zbiorników", style = MaterialTheme.typography.labelSmall, color = department.accent)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text("Zbiornik · opis / zawartość", Modifier.weight(1f), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Stan / poj. [l]", Modifier.width(108.dp), fontSize = 10.sp, textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.loadError != null -> Column(Modifier.padding(16.dp)) {
                Text(ui.loadError, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Ponów odczyt") }
            }
            else -> BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Fit the largest departments on a normal phone; retain readable, scrollable rows on smaller screens.
                val rowHeight = (maxHeight / tanks.size.coerceAtLeast(1)).coerceIn(32.dp, 40.dp)
                key(department) {
                    LazyColumn(Modifier.fillMaxSize().testTag("main-list")) {
                        itemsIndexed(tanks, key = { _, tank -> tank.id }) { index, tank ->
                            TankLine(tank, ui.overview.states[tank.id] ?: TankStatus(), index, rowHeight,
                                onOpen = { onTank(tank) }, onEdit = { onEdit(tank) }, onRouting = { onRouting(tank) },
                                onOilType = { onOilType(tank) }, onReset = { onReset(tank) }, today = today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TankLine(tank: Tank, status: TankStatus, index: Int, rowHeight: androidx.compose.ui.unit.Dp,
                     onOpen: () -> Unit, onEdit: () -> Unit, onRouting: () -> Unit, onOilType: () -> Unit,
                     onReset: () -> Unit, today: LocalDate) {
    val department = tank.department
    val capacity = tank.capacityLitres
    val fill = if (status.litres != null && capacity != null && capacity > 0) {
        status.litres.divide(BigDecimal.valueOf(capacity), 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    } else null
    val expiryWarning = tank.isOilTank && status.oilExpiresSoon(today)
    val outline = if (expiryWarning) OilWarningColor else department.accent.copy(alpha = 0.3f)
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp)
        .heightIn(min = rowHeight - 2.dp).testTag("tank-row-${tank.id}")
        .clip(shape).border(1.dp, outline, shape)
        .background(if (expiryWarning) OilWarningBackground else if (index % 2 == 0) MaterialTheme.colorScheme.surface else department.tint.copy(alpha = 0.45f))
        .clickable(role = Role.Button, onClickLabel = "Szczegóły: ${tank.name}", onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight - 5.dp).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.testTag("tank-heading-${tank.id}")
                        .clickable(role = Role.Button, onClickLabel = "Szczegóły: ${tank.name}", onClick = onOpen),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tank.name, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold,
                            color = department.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        status.laboratoryMeasuredAt?.let { LaboratoryBadge(it, Modifier.testTag("lab-marker-${tank.id}")) }
                        if (status.litres?.signum() == 0) Text("pusty", fontSize = 10.sp, lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = EmptyTankColor)
                    }
                    Text(if (tank.isOilTank) "Rodzaj: ${status.oilType.ifBlank { "niepodany" }}" else status.material.ifBlank { tank.description },
                        modifier = Modifier.weight(1f).then(if (tank.isOilTank) Modifier.testTag("oil-type-${tank.id}")
                            .clickable(role = Role.Button, onClickLabel = "Edytuj rodzaj oleju: ${tank.name}", onClick = onOilType) else Modifier),
                        fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tank.isPowderTank) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Surface(onClick = onRouting, modifier = Modifier.width(52.dp).testTag("route-${tank.id}")
                                .semantics { contentDescription = "Trasa ${tank.name}: wyparka ${status.routing.evaporator ?: "brak"}, krystalizator ${status.routing.crystallizer ?: "brak"}" },
                                shape = RoundedCornerShape(6.dp), color = department.tint, contentColor = department.accent) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 3.dp)) {
                                    Text("${status.routing.evaporator ?: "—"} · ${status.routing.crystallizer ?: "—"}",
                                        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                val values = status.measurements
                val main = if (tank.usesFatMeasurement) "Tł. ${values.fatPercent?.let(::decimalLabel) ?: "—"}%"
                    else "Brix ${values.brix?.let(::decimalLabel) ?: "—"}"
                Text((if (tank.isOilTank) "" else "pH ${values.ph?.let(::decimalLabel) ?: "—"} · $main · ") + "Temp ${values.temperature?.let(::decimalLabel) ?: "—"} °C",
                    Modifier.fillMaxWidth().testTag("measurements-${tank.id}"),
                    fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

            }
            Column(Modifier.width(108.dp).testTag("quantity-${tank.id}")
                .clickable(role = Role.Button, onClickLabel = "Edytuj ilość i zawartość: ${tank.name}", onClick = onEdit),
                horizontalAlignment = Alignment.End) {
                Text(status.litres?.let(::decimalLabel) ?: "Nieustalony",
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.End,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tank.capacityLitres?.let { capacityLabel(it).removeSuffix(" l") } ?: "Brak poj.",
                        fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Zeruj", fontSize = 9.sp, lineHeight = 12.sp, color = department.accent,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, outline, RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button, onClick = onReset).padding(horizontal = 3.dp, vertical = 2.dp)
                            .testTag("reset-tank-${tank.id}"))
                }
            }
        }
        if (tank.isOilTank) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp).clickable(onClick = onOilType), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Produkcja: ${status.oilBatch.producedOn.oilDateLabel()}", fontSize = 10.sp, lineHeight = 12.sp,
                    modifier = Modifier.weight(1f).testTag("oil-produced-${tank.id}"))
                Text("Ważność: ${status.oilBatch.expiresOn.oilDateLabel()}", fontSize = 10.sp, lineHeight = 12.sp,
                    fontWeight = if (expiryWarning) FontWeight.Bold else FontWeight.Normal,
                    color = if (expiryWarning) OilWarningColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("oil-expiry-${tank.id}"))
            }
            Text("Napełniono: ${status.filledAt?.let(::dateLabel) ?: "—"}", fontSize = 10.sp, lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
        }
        if (fill != null) {
            LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth().height(3.dp).testTag("fill-${tank.id}"),
                color = department.accent.copy(alpha = 0.22f), trackColor = department.accent.copy(alpha = 0.04f),
                strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
        } else {
            Box(Modifier.fillMaxWidth().height(3.dp).testTag("fill-${tank.id}")
                .background(department.accent.copy(alpha = 0.04f))
                .semantics { contentDescription = if (capacity == null) "Napełnienie nieznane: brak pojemności" else "Napełnienie nieustalone" })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TankDetailsDialog(tank: Tank, status: TankStatus, latest: Movement?, onClose: () -> Unit,
                      onEdit: () -> Unit, onReceive: () -> Unit, onTransfer: () -> Unit,
                      onHistory: () -> Unit, onRouting: () -> Unit, onOilType: () -> Unit, onTopUps: () -> Unit,
                      today: LocalDate = LocalDate.now()) {
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("Szczegóły zbiornika", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wróć do działu")
                } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
                TankStockCard(tank, status, latest, onEdit, onReceive, onTransfer, onHistory, onRouting, onOilType, onTopUps, today)
            }
        }
    }
}
