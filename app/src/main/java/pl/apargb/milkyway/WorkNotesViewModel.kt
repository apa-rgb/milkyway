package pl.apargb.milkyway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkNotesState(val notes: List<WorkNote> = emptyList(), val loading: Boolean = true,
                          val saving: Boolean = false, val loadError: String? = null, val operationError: String? = null,
                          val lastSavedRequestId: String? = null)

class WorkNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkNotesRepository(application)
    private val mutableState = MutableStateFlow(WorkNotesState())
    val state = mutableState.asStateFlow()

    init { reload() }

    fun reload() {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(loading = true, loadError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudAccess.notes(getApplication(), repository) { it.load() } } }
                .onSuccess { mutableState.value = mutableState.value.copy(notes = it, loading = false) }
                .onFailure { mutableState.value = mutableState.value.copy(loading = false, loadError = "Nie udało się odczytać wpisów. Spróbuj ponownie.") }
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(operationError = null) }

    fun save(requestId: String, id: String, scope: Int, kind: NoteKind, title: String, body: String) {
        val author = CloudSession.get(getApplication()).state.value.noteAuthor
        mutate(requestId) { it.save(id, scope, kind, title, body, System.currentTimeMillis(), author) }
    }
    fun setCompleted(note: WorkNote, completed: Boolean) = mutate { it.setCompleted(note, completed, System.currentTimeMillis()) }
    fun delete(note: WorkNote) = mutate { it.delete(note) }
    internal fun scheduleReminder(note: WorkNote, at: Long) {
        val reminder = NoteReminder(at)
        mutate { it.scheduleReminder(note, reminder, System.currentTimeMillis()) }
    }
    internal fun actReminder(note: WorkNote, token: String, action: ReminderAction) {
        val reader = CloudSession.get(getApplication()).state.value.reminderReader
        mutate { it.actReminder(note, token, action, reader, System.currentTimeMillis()) }
    }

    private fun mutate(requestId: String? = null, action: (WorkNotesRepository) -> List<WorkNote>) {
        if (mutableState.value.loading || mutableState.value.saving || mutableState.value.loadError != null) return
        mutableState.value = mutableState.value.copy(saving = true, operationError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudAccess.notes(getApplication(), repository, mutation = true, operation = action) } }
                .onSuccess { mutableState.value = mutableState.value.copy(notes = it, saving = false,
                    lastSavedRequestId = requestId ?: mutableState.value.lastSavedRequestId) }
                .onFailure { mutableState.value = mutableState.value.copy(saving = false,
                    operationError = if (it is IllegalArgumentException) it.message else "Nie udało się zapisać wpisu. Spróbuj ponownie.") }
        }
    }

    override fun onCleared() { super.onCleared(); repository.close() }
}
