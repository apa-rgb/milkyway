package pl.apargb.milkyway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlin.math.floor

private data class CompletedRow(val product: CompletedDayProduct? = null, val rejected: RejectedGoods? = null) {
    val id get() = product?.entry?.id ?: rejected!!.id
    val timestamp get() = product?.receipts?.maxOf { it.occurredAt } ?: rejected!!.createdAt
}

@Composable
internal fun CompletedDayContent(date: LocalDate, line: ProductionLine?, ui: ProductionQueueState, enabled: Boolean,
                                 onDetails: (String) -> Unit, onNotes: (String) -> Unit,
                                 onRejectedDetails: (String) -> Unit, onDeleteRejected: (String) -> Unit) {
    var page by rememberSaveable(date.toString(), line?.name) { mutableIntStateOf(0) }
    val products = completedProductsForDay(ui.entries, ui.completions, date, line)
    val rejected = ui.rejectedGoods.filter { it.date == date && (line == null || it.line == line) }
    val rows = (products.map { CompletedRow(product = it) } + rejected.map { CompletedRow(rejected = it) }).sortedByDescending { it.timestamp }
    val dayBackground = productionDayBackground(date, line?.tone?.tint ?: MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surface)
    val total = products.groupBy { it.entry.unit }.map { (unit, values) ->
        "${decimalLabel(values.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount })} ${unit.label}"
    }.joinToString(" · ").ifEmpty { "0" }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        // Keep readable rows on one screen; large daily registers have pages within the same day.
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val minimumRow = 52.dp * fontScale
        val headerHeight = 44.dp * fontScale
        val pageSize = floor((maxHeight - headerHeight - 44.dp).value / (minimumRow + 4.dp).value).toInt().coerceAtLeast(1)
        val pages = ((rows.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val visiblePage = page.coerceIn(0, pages - 1)
        LaunchedEffect(pages) { page = page.coerceIn(0, pages - 1) }
        val rowHeight = ((maxHeight - headerHeight - 44.dp) / pageSize - 4.dp).coerceIn(minimumRow, minimumRow + 8.dp)
        Column(Modifier.fillMaxSize().background(dayBackground, RoundedCornerShape(14.dp))) {
            Column(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(queueDateLabel(date), Modifier.testTag("completed-day-$date"), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = line?.tone?.accent ?: MaterialTheme.colorScheme.primary)
                Text("Produkcja: $total  |  Wybrakowane: ${decimalLabel(rejected.fold(BigDecimal.ZERO) { sum, item -> sum + item.kilograms })} kg",
                    Modifier.testTag("completed-day-summary"), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("completed-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rows.isEmpty()) item { Text("Brak wyprodukowanego towaru", Modifier.padding(12.dp)) }
                items(rows.drop(visiblePage * pageSize).take(pageSize), key = { if (it.rejected != null) "reject-${it.id}" else "product-${it.id}" }) { row ->
                    row.product?.let { product ->
                        val entry = product.entry
                        Surface(onClick = { onDetails(entry.id) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("completed-entry-${entry.id}"),
                            shape = RoundedCornerShape(10.dp), color = entry.fulfilment.tint) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, fontWeight = FontWeight.Bold, color = entry.line.tone.accent,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(entry.line.title, style = MaterialTheme.typography.labelSmall)
                                        Text("Wyprodukowano: ${decimalLabel(product.amount)} ${entry.unit.label}",
                                            Modifier.testTag("completed-amount-${entry.id}"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    if (entry.fulfilment == ProductionFulfilment.EXCESS) Text(completedOrderLabel(entry),
                                        Modifier.testTag("completed-order-${entry.id}"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = entry.line.tone.accent)
                                    else {
                                        val status = when (entry.fulfilment) {
                                            ProductionFulfilment.COMPLETE -> "Całość zamówienia"
                                            ProductionFulfilment.PARTIAL -> "Brakuje: ${entry.remainingAmount?.let(::decimalLabel)} ${entry.unit.label}"
                                            else -> "Plan do uzupełnienia"
                                        }
                                        val note = ui.productNotes.firstOrNull { it.entryId == entry.id }?.text
                                        Text(status + (note?.let { " · $it" } ?: ""), Modifier.testTag("completed-status-${entry.id}"),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(onClick = { onNotes(entry.id) }, enabled = enabled,
                                    modifier = Modifier.size(32.dp).testTag("product-notes-${entry.id}")) {
                                    Icon(Icons.Outlined.NoteAlt, "Notatki: ${entry.title}", Modifier.size(16.dp), tint = entry.line.tone.accent)
                                }
                            }
                        }
                    }
                    row.rejected?.let { item ->
                        Surface(onClick = { onRejectedDetails(item.id) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("completed-rejected-${item.id}"),
                            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .5f)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.description, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge)
                                    Text("${item.line.title} · Wybrakowany · ${decimalLabel(item.kilograms)} kg",
                                        Modifier.testTag("rejected-amount-${item.id}"), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { onDeleteRejected(item.id) }, enabled = enabled,
                                    modifier = Modifier.size(32.dp).testTag("rejected-delete-${item.id}")) {
                                    Icon(Icons.Outlined.DeleteOutline, "Usuń wybrakowany towar", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { page = visiblePage - 1 }, enabled = enabled && visiblePage > 0, modifier = Modifier.testTag("completed-previous-page")) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Poprzednia strona dnia", Modifier.size(18.dp))
                }
                Text("${rows.size} pozycji · Strona ${visiblePage + 1}/$pages", Modifier.testTag("completed-page-number"), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { page = visiblePage + 1 }, enabled = enabled && visiblePage < pages - 1, modifier = Modifier.testTag("completed-next-page")) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Następna strona dnia", Modifier.size(18.dp))
                }
            }
        }
    }
}
