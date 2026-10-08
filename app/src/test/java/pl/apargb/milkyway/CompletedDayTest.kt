package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CompletedDayTest {
    @Test fun dailyAmountsUseExecutionDatesMergeSameDayReceiptsAndKeepLinesAndUnitsSeparate() {
        val first = LocalDate.of(2026, 10, 8)
        val next = first.plusDays(1)
        val butter = ProductionQueueEntry("b", ProductionLine.BUTTER, next.plusDays(1), 0, "Masło", "", 0, 5,
            BigDecimal("1000"), BigDecimal("1000"), pendingOrder = true)
        val uht = butter.copy(id = "u", line = ProductionLine.UHT, unit = ProductionQuantityUnit.LITRES)
        val entries = listOf(butter, uht)
        val receipts = listOf(
            ProductionCompletion("1", "b", BigDecimal("200"), first, 1),
            ProductionCompletion("2", "b", BigDecimal("300"), first, 2),
            ProductionCompletion("3", "b", BigDecimal("500"), next, 3),
            ProductionCompletion("4", "u", BigDecimal("750"), first, 4))
        val daily = completedProductsForDay(entries, receipts, first)
        assertEquals(listOf("u", "b"), daily.map { it.entry.id })
        assertEquals(BigDecimal("500"), daily.single { it.entry.id == "b" }.amount)
        assertEquals(2, daily.single { it.entry.id == "b" }.receipts.size)
        assertEquals(ProductionFulfilment.COMPLETE, daily.single { it.entry.id == "b" }.entry.fulfilment)
        assertEquals(ProductionQuantityUnit.LITRES, daily.first().entry.unit)
        assertEquals(BigDecimal("500"), completedProductsForDay(entries, receipts, next).single().amount)
        assertEquals(listOf("b"), completedProductsForDay(entries, receipts, first, ProductionLine.BUTTER).map { it.entry.id })
        assertTrue(completedProductsForDay(entries, receipts, next.plusDays(1)).isEmpty())
    }

    @Test fun fulfilmentComparesTotalProductionWithTheOrderIncludingDecimalScaleAndUnknownPlans() {
        val date = LocalDate.of(2026, 10, 8)
        val entry = ProductionQueueEntry("b", ProductionLine.BUTTER, date, 0, "Masło", "", 0, 0,
            BigDecimal("1000"), BigDecimal("400"))
        assertEquals(ProductionFulfilment.PARTIAL, entry.fulfilment)
        assertEquals(BigDecimal("600"), entry.remainingAmount)
        assertEquals(ProductionFulfilment.COMPLETE, entry.copy(producedAmount = BigDecimal("1000.000")).fulfilment)
        val excess = entry.copy(producedAmount = BigDecimal("1200"))
        assertEquals(ProductionFulfilment.EXCESS, excess.fulfilment)
        assertEquals(BigDecimal("200"), excess.excessAmount)
        assertEquals(ProductionFulfilment.UNKNOWN, entry.copy(plannedAmount = null).fulfilment)
        assertEquals(ProductionFulfilment.UNKNOWN, entry.copy(plannedAmount = BigDecimal.ZERO).fulfilment)
    }
}
