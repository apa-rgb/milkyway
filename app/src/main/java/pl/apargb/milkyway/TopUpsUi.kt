package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TankTopUpsDialog(state: TopUpsUiState, onClose: () -> Unit, onLoadMore: () -> Unit) {
    val tank = AppContent.tanks.find { it.id == state.tankId } ?: return
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("Dolania: ${tank.name}", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wróć do zbiornika")
                } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("top-ups-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DepartmentBadge(tank.department)
                        Text("Napełnienia, dolewki i dopływy z innych zbiorników.", style = MaterialTheme.typography.bodyMedium)
                        Text("Wyświetlono ${state.events.size} z ${state.totalCount}",
                            modifier = Modifier.testTag("top-ups-count"), style = MaterialTheme.typography.labelLarge,
                            color = tank.department.accent)
                        Text("Wpisy pozostają po opróżnieniu zbiornika. Najnowsze na górze.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.events, key = { it.id }) { event ->
                    Card(Modifier.fillMaxWidth().testTag("top-up-${event.id}"), shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("+ ${decimalLabel(event.litres)} l", style = MaterialTheme.typography.titleLarge,
                                color = tank.department.accent, fontWeight = FontWeight.Bold)
                            Text(movementDescription(event), fontWeight = FontWeight.SemiBold)
                            Text(dateLabel(event.occurredAt) + (event.shift?.let { " · Zmiana $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            event.previousLitres?.let { before ->
                                Text("Stan: ${decimalLabel(before)} → ${decimalLabel(before + event.litres)} l",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("Zawartość po dolaniu: ${event.material.ifBlank { "niepodana" }}", style = MaterialTheme.typography.bodyMedium)
                            if (event.measurements.hasAnyValue()) {
                                Text("Parametry po dolaniu", style = MaterialTheme.typography.labelSmall)
                                MeasurementSummary(event.measurements, tank.usesFatMeasurement)
                            }
                            if (event.oilType.isNotBlank()) Text("Rodzaj oleju: ${event.oilType}")
                            if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.error != null -> Column {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onLoadMore) { Text("Ponów") }
                        }
                        state.events.isEmpty() -> Text("Brak zapisanych dolewek ani napełnień.")
                        state.nextBeforeRowId != null -> OutlinedButton(onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth().testTag("load-older-top-ups")) {
                            Text("Wczytaj starsze dolania")
                        }
                        else -> Text("To wszystkie zapisane dolania.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
