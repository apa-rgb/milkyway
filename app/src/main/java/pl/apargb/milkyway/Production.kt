package pl.apargb.milkyway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.MathContext

data class ProductionAssumptions(
    val densityKgPerLitre: BigDecimal? = null,
    val productContentPercent: BigDecimal? = null,
    val recoveryPercent: BigDecimal = BigDecimal("100")
) {
    fun validate() {
        require(densityKgPerLitre != null && densityKgPerLitre > BigDecimal.ZERO) { "Gęstość musi być większa od zera." }
        require(productContentPercent != null && productContentPercent > BigDecimal.ZERO && productContentPercent <= BigDecimal("100")) {
            "Udział składnika w produkcie musi być większy od 0 i nie większy niż 100%."
        }
        require(recoveryPercent >= BigDecimal.ZERO && recoveryPercent <= BigDecimal("100")) { "Uzysk musi być w zakresie 0–100%." }
    }
}

data class ProductionEstimate(val kilograms: BigDecimal? = null, val missing: String? = null)

fun estimateProduction(tank: Tank, status: TankStatus, assumptions: ProductionAssumptions): ProductionEstimate {
    require(tank.isPowderTank || tank.usesFatMeasurement) { "Wybierz zbiornik proszkowni lub masłowni." }
    val litres = status.litres ?: return ProductionEstimate(missing = "Ustal ilość materiału")
    if (litres.signum() == 0) return ProductionEstimate(BigDecimal.ZERO)
    val content = (if (tank.usesFatMeasurement) status.measurements.fatPercent else status.measurements.brix)
        ?: return ProductionEstimate(missing = if (tank.usesFatMeasurement) "Uzupełnij tłuszcz [%]" else "Uzupełnij Brix")
    if (assumptions.densityKgPerLitre == null || assumptions.productContentPercent == null) {
        return ProductionEstimate(missing = "Uzupełnij założenia produkcji")
    }
    require(litres > BigDecimal.ZERO) { "Ilość nie może być ujemna." }
    status.measurements.validate()
    assumptions.validate()
    // L × kg/L × input-content/product-content × recovery. No pH/temperature yield is inferred.
    return ProductionEstimate(litres.multiply(assumptions.densityKgPerLitre).multiply(content)
        .multiply(assumptions.recoveryPercent).divide(assumptions.productContentPercent.multiply(BigDecimal("100")), MathContext.DECIMAL128))
}

val productionDepartments = listOf(Department.Powder, Department.Butter)

class ProductionViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("production_assumptions", Application.MODE_PRIVATE)
    private fun read(department: Department) = ProductionAssumptions(
        prefs.getString("${department.name}-density", "1")!!.toBigDecimal(),
        prefs.getString("${department.name}-content", if (department == Department.Powder) "96" else "82")!!.toBigDecimal(),
        prefs.getString("${department.name}-recovery", "100")!!.toBigDecimal())
    private val mutableState = MutableStateFlow(productionDepartments.associateWith(::read))
    val state = mutableState.asStateFlow()

    fun save(department: Department, assumptions: ProductionAssumptions) {
        require(department in productionDepartments)
        assumptions.validate()
        prefs.edit().putString("${department.name}-density", assumptions.densityKgPerLitre!!.toPlainString())
            .putString("${department.name}-content", assumptions.productContentPercent!!.toPlainString())
            .putString("${department.name}-recovery", assumptions.recoveryPercent.toPlainString()).apply()
        mutableState.value = mutableState.value + (department to assumptions)
    }
}
