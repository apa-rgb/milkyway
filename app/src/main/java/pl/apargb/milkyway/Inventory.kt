package pl.apargb.milkyway

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

data class Measurements(
    val brix: BigDecimal? = null,
    val ph: BigDecimal? = null,
    val sh: BigDecimal? = null,
    val temperature: BigDecimal? = null,
    val fatPercent: BigDecimal? = null
) {
    fun validate() {
        require(brix == null || brix in BigDecimal.ZERO..BigDecimal("100")) { "Brix musi być w zakresie 0–100." }
        require(fatPercent == null || fatPercent in BigDecimal.ZERO..BigDecimal("100")) { "Tłuszcz musi być w zakresie 0–100%." }
        require(ph == null || ph in BigDecimal.ZERO..BigDecimal("14")) { "pH musi być w zakresie 0–14." }
        require(sh == null || sh >= BigDecimal.ZERO) { "SH nie może być ujemne." }
    }

    fun hasSameValuesAs(other: Measurements): Boolean =
        listOf(brix to other.brix, ph to other.ph, sh to other.sh,
            temperature to other.temperature, fatPercent to other.fatPercent).all { (first, second) ->
            if (first == null || second == null) first == null && second == null else first.compareTo(second) == 0
        }

    fun hasAnyValue(): Boolean = listOf(brix, ph, sh, temperature, fatPercent).any { it != null }
}

data class TankStatus(
    // Unknown is different from a confirmed empty tank.
    val litres: BigDecimal? = null,
    val measurements: Measurements = Measurements(),
    val filledAt: Long? = null,
    val routing: TankRouting = TankRouting(),
    val material: String = "",
    val oilType: String = "",
    val laboratoryMeasuredAt: Long? = null
)

val crystallizerNumbers = (1..6).toList()
val Tank.isPowderTank: Boolean get() = group.startsWith("Proszkownia")
val Tank.isOilTank: Boolean get() = group == "Oleje"
val Tank.usesFatMeasurement: Boolean get() = group == "Masłownia"

data class TankRouting(val evaporator: String? = null, val crystallizer: Int? = null) {
    fun validate() {
        require(evaporator == null || evaporator in listOf("C", "F")) { "Wybierz wyparkę C lub F." }
        require(crystallizer == null || crystallizer in crystallizerNumbers) { "Wybierz krystalizator 1–6." }
    }
}

enum class MovementType { SET_STATE, RECEIPT, TRANSFER, ROUTING, DETAILS, OIL_TYPE }

data class Movement(
    val id: String = UUID.randomUUID().toString(),
    val type: MovementType,
    val sourceId: String? = null,
    val targetId: String,
    val externalSource: String? = null,
    val litres: BigDecimal,
    val previousLitres: BigDecimal? = null,
    val measurements: Measurements,
    val occurredAt: Long,
    val note: String = "",
    val shift: Int? = null,
    val routing: TankRouting = TankRouting(),
    val material: String = "",
    val oilType: String = "",
    val laboratoryMeasuredAt: Long? = null
)

data class InventoryChange(val states: Map<String, TankStatus>, val movement: Movement)

/** Pure rules: validate a whole operation before producing either of its state changes. */
class InventoryRules(private val tanks: List<Tank>, private val states: Map<String, TankStatus>) {
    private fun tank(id: String): Tank = tanks.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Nie znaleziono zbiornika.")

    private fun status(id: String): TankStatus {
        tank(id)
        return states[id] ?: TankStatus()
    }

    private fun checkVolume(id: String, litres: BigDecimal) {
        require(litres >= BigDecimal.ZERO) { "Stan nie może być ujemny." }
        require(litres.stripTrailingZeros().scale() <= 3) { "Ilość podaj z dokładnością do 0,001 l." }
        tank(id).capacityLitres?.let { capacity ->
            require(litres <= BigDecimal.valueOf(capacity)) { "Przekroczono pojemność zbiornika ${tank(id).name}." }
        }
    }

    fun setState(id: String, litres: BigDecimal, measurements: Measurements, now: Long,
                 note: String = "", shift: Int? = null, material: String = ""): InventoryChange {
        val old = status(id)
        checkVolume(id, litres)
        measurements.validate()
        val savedMeasurements = if (litres.signum() == 0) Measurements() else measurements
        val savedMaterial = if (litres.signum() == 0) "" else material.trim()
        val laboratoryAt = if (litres.signum() != 0 && savedMeasurements.hasSameValuesAs(old.measurements)) old.laboratoryMeasuredAt else null
        val filledAt = when {
            litres.signum() == 0 -> null
            old.litres == null || litres > old.litres -> now
            else -> old.filledAt
        }
        return InventoryChange(
            mapOf(id to old.copy(litres = litres, measurements = savedMeasurements, filledAt = filledAt, material = savedMaterial,
                laboratoryMeasuredAt = laboratoryAt)),
            Movement(type = MovementType.SET_STATE, targetId = id, litres = litres,
                previousLitres = old.litres, measurements = savedMeasurements, occurredAt = now, note = note, shift = shift,
                routing = old.routing, material = savedMaterial, oilType = old.oilType, laboratoryMeasuredAt = laboratoryAt)
        )
    }

    fun receive(id: String, litres: BigDecimal, source: String, measurements: Measurements,
                now: Long, note: String = "", shift: Int? = null, material: String = ""): InventoryChange {
        val old = status(id)
        val before = old.litres ?: throw IllegalArgumentException("Najpierw ustal obecny stan zbiornika docelowego.")
        require(source.isNotBlank()) { "Podaj, skąd pochodzi materiał." }
        require(litres > BigDecimal.ZERO) { "Ilość musi być większa od zera." }
        checkVolume(id, before + litres)
        require(litres.stripTrailingZeros().scale() <= 3) { "Ilość podaj z dokładnością do 0,001 l." }
        measurements.validate()
        return InventoryChange(
            mapOf(id to old.copy(litres = before + litres, measurements = measurements, filledAt = now, material = material.trim(), laboratoryMeasuredAt = null)),
            Movement(type = MovementType.RECEIPT, targetId = id, externalSource = source.trim(),
                litres = litres, previousLitres = before, measurements = measurements, occurredAt = now, note = note, shift = shift, routing = old.routing,
                material = material.trim(), oilType = old.oilType)
        )
    }

    fun transfer(sourceId: String, targetId: String, litres: BigDecimal, measurements: Measurements,
                 now: Long, note: String = "", shift: Int? = null, material: String = ""): InventoryChange {
        require(sourceId != targetId) { "Wybierz inny zbiornik docelowy." }
        val source = status(sourceId)
        val target = status(targetId)
        val available = source.litres ?: throw IllegalArgumentException("Najpierw ustal stan zbiornika źródłowego.")
        val before = target.litres ?: throw IllegalArgumentException("Najpierw ustal stan zbiornika docelowego.")
        require(litres > BigDecimal.ZERO) { "Ilość musi być większa od zera." }
        require(litres <= available) { "W zbiorniku źródłowym nie ma wystarczającej ilości materiału." }
        require(litres.stripTrailingZeros().scale() <= 3) { "Ilość podaj z dokładnością do 0,001 l." }
        checkVolume(targetId, before + litres)
        measurements.validate()
        val remaining = available - litres
        val sourceAfter = if (remaining.signum() == 0) source.copy(litres = BigDecimal.ZERO, measurements = Measurements(), filledAt = null, material = "", laboratoryMeasuredAt = null)
            else source.copy(litres = remaining)
        return InventoryChange(
            mapOf(sourceId to sourceAfter, targetId to target.copy(litres = before + litres, measurements = measurements, filledAt = now, material = material.trim(), laboratoryMeasuredAt = null)),
            Movement(type = MovementType.TRANSFER, sourceId = sourceId, targetId = targetId,
                litres = litres, previousLitres = before, measurements = measurements, occurredAt = now, note = note, shift = shift, routing = source.routing,
                material = material.trim(), oilType = target.oilType)
        )
    }

    fun setRouting(id: String, routing: TankRouting, now: Long, shift: Int? = null): InventoryChange {
        require(tank(id).isPowderTank) { "Oznaczenie trasy jest dostępne w proszkowni." }
        routing.validate()
        val old = status(id)
        return InventoryChange(mapOf(id to old.copy(routing = routing)),
            Movement(type = MovementType.ROUTING, targetId = id, litres = BigDecimal.ZERO,
                measurements = old.measurements, occurredAt = now, shift = shift, routing = routing, material = old.material,
                laboratoryMeasuredAt = old.laboratoryMeasuredAt))
    }

    fun resetRouting(id: String, now: Long, shift: Int? = null): InventoryChange {
        val change = setRouting(id, TankRouting(), now, shift)
        return change.copy(movement = change.movement.copy(note = "Produkcja wstrzymana — wyzerowano trasę."))
    }

    fun updateDetails(id: String, material: String, measurements: Measurements, now: Long,
                      note: String = "", shift: Int? = null): InventoryChange {
        val old = status(id)
        measurements.validate()
        val empty = old.litres?.signum() == 0
        val after = old.copy(material = if (empty) "" else material.trim(),
            measurements = if (empty) Measurements() else measurements,
            laboratoryMeasuredAt = if (!empty && measurements.hasSameValuesAs(old.measurements)) old.laboratoryMeasuredAt else null)
        return InventoryChange(mapOf(id to after), Movement(type = MovementType.DETAILS, targetId = id,
            litres = BigDecimal.ZERO, measurements = after.measurements, occurredAt = now, note = note,
            shift = shift, routing = after.routing, material = after.material, oilType = after.oilType,
            laboratoryMeasuredAt = after.laboratoryMeasuredAt))
    }

    fun setOilType(id: String, oilType: String, now: Long, shift: Int? = null): InventoryChange {
        require(tank(id).isOilTank) { "Rodzaj oleju można określić w zakładce Oleje." }
        val type = oilType.trim()
        require(type.length <= 120) { "Rodzaj oleju może mieć do 120 znaków." }
        val old = status(id)
        return InventoryChange(mapOf(id to old.copy(oilType = type)),
            Movement(type = MovementType.OIL_TYPE, targetId = id, litres = BigDecimal.ZERO,
                measurements = old.measurements, occurredAt = now, shift = shift, material = old.material,
                oilType = type, note = "Poprzedni rodzaj oleju: ${old.oilType.ifBlank { "niepodany" }}",
                laboratoryMeasuredAt = old.laboratoryMeasuredAt))
    }

    fun updateMeasurements(id: String, measurements: Measurements, now: Long, shift: Int? = null): InventoryChange {
        val old = status(id)
        require(old.litres?.signum() != 0) { "Zbiornik jest pusty. Najpierw ustal dodatnią ilość materiału." }
        require(measurements.hasAnyValue()) { "Wpisz przynajmniej jeden pomiar." }
        val change = updateDetails(id, old.material, measurements, now, "Pomiar laboratoryjny", shift)
        return change.copy(states = mapOf(id to change.states.getValue(id).copy(laboratoryMeasuredAt = now)),
            movement = change.movement.copy(laboratoryMeasuredAt = now))
    }
}

fun parseDecimal(text: String, label: String, required: Boolean = false): BigDecimal? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        require(!required) { "Uzupełnij pole: $label." }
        return null
    }
    require(trimmed.length <= 24 && trimmed.matches(Regex("[-+]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)"))) {
        "Pole $label musi zawierać liczbę (np. 6,5)."
    }
    return trimmed.replace(',', '.').toBigDecimal()
}

fun decimalLabel(value: BigDecimal): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("pl-PL"))
    .apply { maximumFractionDigits = maxOf(3, value.scale().coerceAtMost(24)) }.format(value)

fun BigDecimal?.inputText(): String = this?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: ""

/** UI accepts cubic metres; persisted quantities and inventory rules stay in litres. */
fun parseCubicMetres(text: String, required: Boolean = false): BigDecimal? {
    val cubicMetres = parseDecimal(text, "Ilość [m³]", required) ?: return null
    require(cubicMetres >= BigDecimal.ZERO) { "Ilość w m³ nie może być ujemna." }
    require(cubicMetres.stripTrailingZeros().scale() <= 6) { "Ilość podaj z dokładnością do 0,000001 m³ (0,001 l)." }
    return cubicMetres.movePointRight(3)
}

fun BigDecimal?.cubicMetresInputText(): String = this?.movePointLeft(3).inputText()

fun volumeInputHint(text: String): String {
    if (text.isBlank()) return "1 m³ = 1 000 l"
    return try {
        "Po przeliczeniu: ${decimalLabel(parseCubicMetres(text)!!)} l"
    } catch (error: IllegalArgumentException) {
        error.message ?: "Wpisz ilość w m³."
    }
}
