package pl.apargb.milkyway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InventoryUiState(
    val overview: InventoryOverview = InventoryOverview(emptyMap(), emptyMap()),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val loadError: String? = null,
    val operationError: String? = null,
    val lastSavedRequestId: String? = null
)

data class HistoryUiState(val tankId: String? = null, val loading: Boolean = false,
                          val events: List<Movement> = emptyList(), val error: String? = null)

data class TopUpsUiState(val tankId: String? = null, val loading: Boolean = false,
                         val events: List<Movement> = emptyList(), val nextBeforeRowId: Long? = null,
                         val totalCount: Int = 0, val error: String? = null)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = InventoryRepository(application)
    private val mutableState = MutableStateFlow(InventoryUiState())
    val state = mutableState.asStateFlow()
    private val mutableHistory = MutableStateFlow(HistoryUiState())
    val history = mutableHistory.asStateFlow()

    private val mutableTopUps = MutableStateFlow(TopUpsUiState())
    val topUps = mutableTopUps.asStateFlow()
    private var topUpsGeneration = 0L

    init { reload() }

    fun reload() {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(loading = true, loadError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudAccess.inventory(getApplication(), repository) { it.load() } } }
                .onSuccess { mutableState.value = mutableState.value.copy(overview = it, loading = false) }
                .onFailure { mutableState.value = mutableState.value.copy(loading = false, loadError = "Nie udało się odczytać danych. Spróbuj ponownie.") }
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(operationError = null) }
    fun showError(message: String) { mutableState.value = mutableState.value.copy(operationError = message) }

    fun save(requestId: String, operation: (InventoryRules) -> InventoryChange) {
        if (mutableState.value.saving || mutableState.value.loading || mutableState.value.loadError != null) return
        mutableState.value = mutableState.value.copy(saving = true, operationError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudAccess.inventory(getApplication(), repository, mutation = true) { it.apply(requestId, operation) } } }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(overview = it, saving = false, lastSavedRequestId = requestId)
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(saving = false,
                        operationError = if (it is IllegalArgumentException) it.message else "Nie udało się zapisać zmian. Spróbuj ponownie.")
                }
        }
    }

    fun showHistory(tankId: String) {
        mutableHistory.value = HistoryUiState(tankId, loading = true)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { CloudAccess.inventory(getApplication(), repository) { it.history(tankId) } } }
            if (mutableHistory.value.tankId == tankId) {
                mutableHistory.value = result.fold(
                    { HistoryUiState(tankId, events = it) },
                    { HistoryUiState(tankId, error = "Nie udało się odczytać historii.") }
                )
            }
        }
    }

    fun hideHistory() { mutableHistory.value = HistoryUiState() }

    fun showTopUps(tankId: String) {
        topUpsGeneration++
        mutableTopUps.value = TopUpsUiState(tankId)
        loadMoreTopUps()
    }

    fun loadMoreTopUps() {
        val current = mutableTopUps.value
        val tankId = current.tankId ?: return
        if (current.loading || (current.events.isNotEmpty() && current.nextBeforeRowId == null)) return
        val generation = topUpsGeneration
        mutableTopUps.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { CloudAccess.inventory(getApplication(), repository) { it.topUps(tankId, current.nextBeforeRowId) } } }
            if (generation == topUpsGeneration) {
                mutableTopUps.value = result.fold(
                    { current.copy(loading = false, events = current.events + it.events,
                        nextBeforeRowId = it.nextBeforeRowId, totalCount = it.totalCount) },
                    { current.copy(error = "Nie udało się odczytać dolewek. Spróbuj ponownie.") }
                )
            }
        }
    }

    fun hideTopUps() {
        topUpsGeneration++
        mutableTopUps.value = TopUpsUiState()
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}
