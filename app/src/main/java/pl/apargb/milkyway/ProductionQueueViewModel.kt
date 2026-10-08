package pl.apargb.milkyway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.math.BigDecimal

data class ProductionQueueState(val entries: List<ProductionQueueEntry> = emptyList(), val loading: Boolean = true,
                                val saving: Boolean = false, val loadError: String? = null,
                                val operationError: String? = null, val lastSavedRequestId: String? = null,
                                val completions: List<ProductionCompletion> = emptyList(),
                                val productNotes: List<ProductNote> = emptyList(),
                                val rejectedGoods: List<RejectedGoods> = emptyList())

class ProductionQueueViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProductionQueueRepository(application)
    private val mutableState = MutableStateFlow(ProductionQueueState())
    val state = mutableState.asStateFlow()

    init { reload() }

    fun reload() {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(loading = true, loadError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudAccess.production(getApplication(), repository) { it.snapshot() } } }
                .onSuccess { mutableState.value = mutableState.value.copy(entries = it.entries, completions = it.completions,
                    productNotes = it.productNotes, rejectedGoods = it.rejectedGoods, loading = false) }
                .onFailure { mutableState.value = mutableState.value.copy(loading = false,
                    loadError = "Nie udało się odczytać kolejki. Spróbuj ponownie.") }
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(operationError = null) }

    fun save(requestId: String, id: String, line: ProductionLine, date: LocalDate, title: String, description: String, plannedAmount: BigDecimal,
             pendingOrder: Boolean? = null, scheduledTime: LocalTime? = null) = mutate(requestId) {
        it.save(id, line, date, title, description, System.currentTimeMillis(), plannedAmount, pendingOrder, scheduledTime)
    }

    fun schedule(entry: ProductionQueueEntry, date: LocalDate, time: LocalTime) = mutate {
        it.schedule(entry, date, time, System.currentTimeMillis())
    }

    fun returnToPending(entry: ProductionQueueEntry) = mutate {
        it.returnToPending(entry, System.currentTimeMillis())
    }

    fun addProductNote(requestId: String, entry: ProductionQueueEntry, stage: ProductNoteStage, text: String) = mutate(requestId) {
        it.addProductNote(requestId, entry, stage, text, System.currentTimeMillis())
    }

    fun recordProduction(requestId: String, entry: ProductionQueueEntry, amount: BigDecimal?) = mutate(requestId) {
        it.recordProduction(requestId, entry.id, entry.line, entry.date, amount, System.currentTimeMillis())
    }

    fun showError(message: String) { mutableState.value = mutableState.value.copy(operationError = message) }

    fun move(entry: ProductionQueueEntry, direction: Int) = mutate {
        it.move(entry.id, entry.line, entry.date, direction, System.currentTimeMillis())
    }

    fun delete(entry: ProductionQueueEntry) = mutate { it.delete(entry) }

    fun addRejectedGoods(id: String, line: ProductionLine, date: LocalDate, description: String, kilograms: BigDecimal) = mutate(id) {
        it.addRejectedGoods(id, line, date, description, kilograms, System.currentTimeMillis())
    }

    fun deleteRejectedGoods(id: String) = mutate { it.deleteRejectedGoods(id) }

    private fun mutate(requestId: String? = null, action: (ProductionQueueRepository) -> List<ProductionQueueEntry>) {
        if (mutableState.value.loading || mutableState.value.saving || mutableState.value.loadError != null) return
        mutableState.value = mutableState.value.copy(saving = true, operationError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudAccess.production(getApplication(), repository, mutation = true) { action(it); it.snapshot() } } }
                .onSuccess { mutableState.value = mutableState.value.copy(entries = it.entries, completions = it.completions,
                    productNotes = it.productNotes, rejectedGoods = it.rejectedGoods, saving = false,
                    lastSavedRequestId = requestId ?: mutableState.value.lastSavedRequestId) }
                .onFailure { mutableState.value = mutableState.value.copy(saving = false,
                    operationError = if (it is IllegalArgumentException) it.message else "Nie udało się zapisać planu. Spróbuj ponownie.") }
        }
    }

    override fun onCleared() { super.onCleared(); repository.close() }
}
