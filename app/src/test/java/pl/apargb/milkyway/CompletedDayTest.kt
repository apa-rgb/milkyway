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
        assertEquals(ProductionQuantityUnit.LITRES, daily.first().entry.unit)
        assertEquals(BigDecimal("500"), completedProductsForDay(entries, receipts, next).single().amount)
        assertEquals(listOf("b"), completedProductsForDay(entries, receipts, first, ProductionLine.BUTTER).map { it.entry.id })
        assertTrue(completedProductsForDay(entries, receipts, next.plusDays(1)).isEmpty())
    }
}
