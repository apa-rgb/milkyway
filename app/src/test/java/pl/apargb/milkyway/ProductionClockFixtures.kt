package pl.apargb.milkyway

import java.time.LocalDate

/** Noon within the fixture's production day, rather than an unrelated 1970 timestamp. */
internal fun productionTimestamp(date: LocalDate, offset: Long = 0): Long =
    date.atTime(12, 0).atZone(WarehouseZone).toInstant().toEpochMilli() + offset
