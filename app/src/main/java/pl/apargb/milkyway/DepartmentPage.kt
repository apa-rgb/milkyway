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
import androidx.compose.ui.graphics.Color
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
                       onReset: (Tank) -> Unit = {}, today: LocalDate = LocalDate.now(),
                       now: java.time.Instant = java.time.Instant.now()) {
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
            Text("${tanks.size} zbiorników", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = department.accent)
        }
        if (department != Department.Oils) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text("Zbiornik · opis / zawartość", Modifier.weight(1f), fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("Stan / poj. [l]", Modifier.width(108.dp), fontSize = 10.sp, textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
                                onOilType = { onOilType(tank) }, onReset = { onReset(tank) }, today = today, now = now)
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
                     onReset: () -> Unit, today: LocalDate, now: java.time.Instant) {
    if (tank.isOilTank) {
        OilTankButton(tank, status, index, onOilType, onReset, today, now)
        return
    }
    val department = tank.department
    val capacity = tank.capacityLitres
    val fill = if (status.litres != null && capacity != null && capacity > 0) {
        status.litres.divide(BigDecimal.valueOf(capacity), 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    } else null
    val expiryWarning = tank.isOilTank && status.oilExpiresSoon(today)
    val urgent = tank.isOilTank && status.oilExpiresWithinDay(now, java.time.ZoneId.systemDefault())
    val mainColor = if (urgent) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (urgent) OilUrgentSecondary else MaterialTheme.colorScheme.onSurface
    val accent = if (urgent) Color.White else department.accent
    val outline = if (urgent) OilUrgentOutline else if (expiryWarning) OilWarningColor else department.accent.copy(alpha = 0.3f)
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp)
        .heightIn(min = rowHeight - 2.dp).testTag("tank-row-${tank.id}")
        .clip(shape).border(1.dp, outline, shape)
        .background(if (urgent) OilUrgentBackground else if (expiryWarning) OilWarningBackground else if (index % 2 == 0) MaterialTheme.colorScheme.surface else department.tint.copy(alpha = 0.45f))
        .clickable(role = Role.Button, onClickLabel = "Szczegóły: ${tank.name}", onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight - 5.dp).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.testTag("tank-heading-${tank.id}")
                        .clickable(role = Role.Button, onClickLabel = "Szczegóły: ${tank.name}", onClick = onOpen),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tank.name, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold,
                            color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        status.laboratoryMeasuredAt?.let { LaboratoryBadge(it, Modifier.testTag("lab-marker-${tank.id}")) }
                        if (status.litres?.signum() == 0) Text("pusty", fontSize = 10.sp, lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = EmptyTankColor)
                    }
                    Text(status.material.ifBlank { tank.description }, modifier = Modifier.weight(1f),
                        fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium, color = secondaryColor)
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
                    fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = secondaryColor)

            }
            Column(Modifier.width(108.dp).testTag("quantity-${tank.id}")
                .clickable(role = Role.Button, onClickLabel = "Edytuj ilość i zawartość: ${tank.name}", onClick = onEdit),
                horizontalAlignment = Alignment.End) {
                Text(status.litres?.let(::decimalLabel) ?: "Nieustalony",
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.End,
                    fontWeight = FontWeight.Bold, color = mainColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tank.capacityLitres?.let { capacityLabel(it).removeSuffix(" l") } ?: "Brak poj.",
                        fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium, color = secondaryColor)
                    Text("Zeruj", fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = accent,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, outline, RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button, onClick = onReset).padding(horizontal = 3.dp, vertical = 2.dp)
                            .testTag("reset-tank-${tank.id}"))
                }
            }
        }
        if (fill != null) {
            LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth().height(3.dp).testTag("fill-${tank.id}"),
                color = if (urgent) Color.White.copy(alpha = .3f) else department.accent.copy(alpha = 0.22f),
                trackColor = if (urgent) Color.White.copy(alpha = .08f) else department.accent.copy(alpha = 0.04f),
                strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
        } else {
            Box(Modifier.fillMaxWidth().height(3.dp).testTag("fill-${tank.id}")
                .background(department.accent.copy(alpha = 0.04f))
                .semantics { contentDescription = if (capacity == null) "Napełnienie nieznane: brak pojemności" else "Napełnienie nieustalone" })
        }
    }
}

@Composable
private fun OilTankButton(tank: Tank, status: TankStatus, index: Int, onClick: () -> Unit, onReset: () -> Unit,
                          today: LocalDate, now: java.time.Instant) {
    val urgent = status.oilExpiresWithinDay(now, java.time.ZoneId.systemDefault())
    val warning = status.oilExpiresSoon(today)
    val foreground = if (urgent) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (urgent) OilUrgentSecondary else foreground
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)
        .testTag("tank-row-${tank.id}").clip(shape)
        .background(if (urgent) OilUrgentBackground else if (warning) OilWarningBackground
            else if (index % 2 == 0) MaterialTheme.colorScheme.surface else tank.department.tint.copy(alpha = .45f))
        .border(1.dp, if (urgent) OilUrgentOutline else if (warning) OilWarningColor
            else tank.department.accent.copy(alpha = .35f), shape)
        .clickable(role = Role.Button, onClickLabel = "Edytuj ${tank.name}", onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).testTag("tank-heading-${tank.id}"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tank.name, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (urgent) Color.White else tank.department.accent)
                if (status.litres?.signum() == 0) Text("pusty", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmptyTankColor)
            }
            Text("${status.litres?.let(::decimalLabel) ?: "—"} / ${capacityLabel(tank.capacityLitres)}",
                Modifier.testTag("quantity-${tank.id}"), fontSize = 11.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Bold, color = foreground)
            Text("Zeruj", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp))
                .border(1.dp, if (urgent) OilUrgentSecondary else tank.department.accent.copy(alpha = .5f), RoundedCornerShape(5.dp))
                .clickable(role = Role.Button, onClickLabel = "Wyzeruj ${tank.name}", onClick = onReset)
                .padding(horizontal = 5.dp, vertical = 4.dp).testTag("reset-tank-${tank.id}"),
                fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (urgent) Color.White else tank.department.accent)
        }
        Text("Rodzaj oleju: ${status.oilType.ifBlank { "—" }}", Modifier.testTag("oil-type-${tank.id}"),
            fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ważność: ${status.oilBatch.expiresOn.oilDateLabel()}", Modifier.weight(1f).testTag("oil-expiry-${tank.id}"),
                fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold,
                color = if (urgent) Color.White else if (warning) OilWarningColor else secondary)
            Text("Temp ${status.measurements.temperature?.let(::decimalLabel) ?: "—"} °C",
                Modifier.testTag("measurements-${tank.id}"), fontSize = 10.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, color = secondary)
        }
        Text("Uwagi: ${status.oilBatch.notes.ifBlank { "—" }}", Modifier.testTag("oil-notes-${tank.id}"),
            fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TankDetailsDialog(tank: Tank, status: TankStatus, latest: Movement?, onClose: () -> Unit,
                      onEdit: () -> Unit, onReceive: () -> Unit, onTransfer: () -> Unit,
                      onHistory: () -> Unit, onRouting: () -> Unit, onOilType: () -> Unit, onTopUps: () -> Unit,
                      today: LocalDate = LocalDate.now(), now: java.time.Instant = java.time.Instant.now()) {
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("Szczegóły zbiornika", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wróć do działu")
                } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
                TankStockCard(tank, status, latest, onEdit, onReceive, onTransfer, onHistory, onRouting, onOilType, onTopUps, today, now)
            }
        }
    }
}
