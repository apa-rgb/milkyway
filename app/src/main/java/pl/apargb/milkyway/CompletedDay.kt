package pl.apargb.milkyway

import java.math.BigDecimal
import java.time.LocalDate

internal data class CompletedDayProduct(val entry: ProductionQueueEntry, val receipts: List<ProductionCompletion>) {
    val amount: BigDecimal get() = receipts.fold(BigDecimal.ZERO) { total, receipt -> total + receipt.amount }
}

internal fun completedProductsForDay(entries: List<ProductionQueueEntry>, completions: List<ProductionCompletion>,
                                     date: LocalDate, line: ProductionLine? = null): List<CompletedDayProduct> {
    val receipts = completions.filter { it.producedOn == date }.groupBy { it.entryId }
    return entries.filter { it.id in receipts && (line == null || it.line == line) }
        .map { CompletedDayProduct(it, receipts.getValue(it.id)) }
        .sortedByDescending { it.receipts.maxOf { receipt -> receipt.occurredAt } }
}
