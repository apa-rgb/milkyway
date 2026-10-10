package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.Instant

class ProductionDayTest {
    private fun instant(local: String): Instant = java.time.LocalDateTime.parse(local).atZone(WarehouseZone).toInstant()

    @Test fun dayStartsAtNineAndIncludesTheFollowingMorning() {
        val day = LocalDate.of(2026, 10, 10)
        assertEquals(day.minusDays(1), warehouseProductionDay(instant("2026-10-10T08:59:59.999")))
        assertEquals(day, warehouseProductionDay(instant("2026-10-10T09:00:00")))
        assertEquals(day, warehouseProductionDay(instant("2026-10-11T00:00:00")))
        assertEquals(day, warehouseProductionDay(instant("2026-10-11T08:59:59.999")))
        assertEquals(day.plusDays(1), warehouseProductionDay(instant("2026-10-11T09:00:00")))
    }

    @Test fun yearAndDaylightSavingChangesUsePolishCalendarDays() {
        assertEquals(LocalDate.of(2026, 12, 31), warehouseProductionDay(instant("2027-01-01T08:30:00")))
        listOf("2026-03-29", "2026-10-25").forEach { date ->
            val day = LocalDate.parse(date)
            assertEquals(day.minusDays(1), warehouseProductionDay(instant("${date}T08:59:59")))
            assertEquals(day, warehouseProductionDay(instant("${date}T09:00:00")))
        }
        // 07:00 UTC is 09:00 in Warsaw in October; the host/device zone is irrelevant.
        assertEquals(LocalDate.of(2026, 10, 10), warehouseProductionDay(Instant.parse("2026-10-10T07:00:00Z")))
    }

    @Test fun previousQueueCanBeCompletedUntilNineAndFutureQueuesStayBlocked() {
        val day = LocalDate.of(2026, 10, 11)
        val before = instant("2026-10-11T08:59:59")
        assertTrue(canRecordProductionOn(day.minusDays(1), before))
        assertTrue(canRecordProductionOn(day, before))
        assertFalse(canRecordProductionOn(day.minusDays(2), before))
        assertFalse(canRecordProductionOn(day.plusDays(1), before))
        assertFalse(canRecordProductionOn(day.minusDays(1), instant("2026-10-11T09:00:00")))
        assertTrue(canRecordProductionOn(day, instant("2026-10-11T09:00:00")))
    }
}
