package com.mhss.app.presentation.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mhss.app.datetime.currentLocalDate
import com.mhss.app.domain.model.AiRepositoryException
import com.mhss.app.domain.model.AssistantResult
import com.mhss.app.domain.model.Calendar
import com.mhss.app.domain.stripReasoningBlocks
import com.mhss.app.domain.transcription.ParsedTranscription
import com.mhss.app.domain.transcription.TranscriptionImportResult
import com.mhss.app.domain.transcription.TranscriptionItem
import com.mhss.app.domain.transcription.TranscriptionParser
import com.mhss.app.domain.transcription.TranscriptionPrompt
import com.mhss.app.domain.use_case.GenerateTranscriptionMarkdownUseCase
import com.mhss.app.domain.use_case.GetAllCalendarsUseCase
import com.mhss.app.domain.use_case.ImportTranscriptionUseCase
import com.mhss.app.preferences.PrefsConstants
import com.mhss.app.preferences.domain.model.AiProvider
import com.mhss.app.preferences.domain.model.intPreferencesKey
import com.mhss.app.preferences.domain.model.longPreferencesKey
import com.mhss.app.preferences.domain.model.toAiProvider
import com.mhss.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.koin.core.annotation.KoinViewModel

data class ImportTranscriptionUiState(
    val prompt: String = "",
    val referenceDate: LocalDate? = null,
    val timeZoneId: String = "",
    val markdown: String = "",
    val transcript: String = "",
    val parsed: ParsedTranscription? = null,
    val selectedIds: Set<Int> = emptySet(),
    val calendars: List<Calendar> = emptyList(),
    val selectedCalendarId: Long? = null,
    val aiEnabled: Boolean = false,
    val aiLoading: Boolean = false,
    val aiError: AssistantResult.Failure? = null,
    val importing: Boolean = false,
    val importResult: TranscriptionImportResult? = null
) {
    val selectedItems: List<TranscriptionItem>
        get() = parsed?.importable?.filter { it.id in selectedIds }.orEmpty()
    val hasSelectedEvents: Boolean
        get() = selectedItems.any { it is TranscriptionItem.EventItem }
}

sealed interface ImportTranscriptionEvent {
    data class UpdateMarkdown(val markdown: String) : ImportTranscriptionEvent
    data class UpdateTranscript(val transcript: String) : ImportTranscriptionEvent
    data class ToggleItem(val id: Int) : ImportTranscriptionEvent
    data class SelectAll(val selected: Boolean) : ImportTranscriptionEvent
    data class SelectCalendar(val calendarId: Long) : ImportTranscriptionEvent
    data object ReloadCalendars : ImportTranscriptionEvent
    data object GenerateWithAi : ImportTranscriptionEvent
    data object CancelAi : ImportTranscriptionEvent
    data object Import : ImportTranscriptionEvent
    data object DismissResult : ImportTranscriptionEvent
}

@OptIn(FlowPreview::class)
@KoinViewModel
class ImportTranscriptionViewModel(
    private val generateMarkdown: GenerateTranscriptionMarkdownUseCase,
    private val importTranscription: ImportTranscriptionUseCase,
    private val getAllCalendars: GetAllCalendarsUseCase,
    private val getPreference: GetPreferenceUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportTranscriptionUiState())
    val uiState: StateFlow<ImportTranscriptionUiState> = _uiState.asStateFlow()

    private val markdownFlow = MutableStateFlow("")
    private var aiJob: Job? = null

    init {
        val today = currentLocalDate()
        val timeZoneId = TimeZone.currentSystemDefault().id
        _uiState.update {
            it.copy(
                prompt = TranscriptionPrompt.build(today, timeZoneId),
                referenceDate = today,
                timeZoneId = timeZoneId
            )
        }
        viewModelScope.launch {
            markdownFlow
                .debounce(PARSE_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collectLatest { markdown ->
                    val parsed = if (markdown.isBlank()) null else withContext(Dispatchers.Default) {
                        TranscriptionParser.parse(markdown)
                    }
                    _uiState.update { state ->
                        state.copy(
                            parsed = parsed,
                            // Todo marcado por defecto
                            selectedIds = parsed?.importable?.map { it.id }?.toSet().orEmpty()
                        )
                    }
                }
        }
        viewModelScope.launch {
            getPreference(intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY), AiProvider.None.id)
                .map { it.toAiProvider() }
                .collect { provider -> _uiState.update { it.copy(aiEnabled = provider != AiProvider.None) } }
        }
        loadCalendars()
    }

    fun onEvent(event: ImportTranscriptionEvent) {
        when (event) {
            is ImportTranscriptionEvent.UpdateMarkdown -> setMarkdown(event.markdown)
            is ImportTranscriptionEvent.UpdateTranscript -> _uiState.update { it.copy(transcript = event.transcript) }
            is ImportTranscriptionEvent.ToggleItem -> _uiState.update { state ->
                val ids = if (event.id in state.selectedIds) state.selectedIds - event.id else state.selectedIds + event.id
                state.copy(selectedIds = ids)
            }
            is ImportTranscriptionEvent.SelectAll -> _uiState.update { state ->
                state.copy(
                    selectedIds = if (event.selected) state.parsed?.importable?.map { it.id }?.toSet().orEmpty()
                    else emptySet()
                )
            }
            is ImportTranscriptionEvent.SelectCalendar -> _uiState.update { it.copy(selectedCalendarId = event.calendarId) }
            ImportTranscriptionEvent.ReloadCalendars -> loadCalendars()
            ImportTranscriptionEvent.GenerateWithAi -> generateWithAi()
            ImportTranscriptionEvent.CancelAi -> {
                aiJob?.cancel()
                aiJob = null
                _uiState.update { it.copy(aiLoading = false) }
            }
            ImportTranscriptionEvent.Import -> importSelected()
            ImportTranscriptionEvent.DismissResult -> {
                setMarkdown("")
                _uiState.update { it.copy(importResult = null, transcript = "", parsed = null, selectedIds = emptySet()) }
            }
        }
    }

    private fun setMarkdown(markdown: String) {
        _uiState.update { it.copy(markdown = markdown) }
        markdownFlow.value = markdown
    }

    private fun loadCalendars() {
        viewModelScope.launch {
            // Sin permiso de calendario esto falla: se deja la lista vacía
            val calendars = runCatching { getAllCalendars(emptyList()).values.flatten() }.getOrDefault(emptyList())
            val latestId = getPreference(longPreferencesKey(PrefsConstants.LATEST_CALENDAR_ID_KEY), -1L).first()
            _uiState.update { state ->
                val current = state.selectedCalendarId?.takeIf { id -> calendars.any { it.id == id } }
                state.copy(
                    calendars = calendars,
                    selectedCalendarId = current
                        ?: calendars.firstOrNull { it.id == latestId }?.id
                        ?: calendars.firstOrNull()?.id
                )
            }
        }
    }

    private fun generateWithAi() {
        val state = _uiState.value
        if (state.transcript.isBlank() || state.aiLoading) return
        val referenceDate = state.referenceDate ?: currentLocalDate()
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _uiState.update { it.copy(aiLoading = true, aiError = null) }
            try {
                var last = ""
                generateMarkdown(state.transcript, referenceDate, state.timeZoneId).collect { accumulated ->
                    last = accumulated
                    setMarkdown(accumulated)
                }
                setMarkdown(last.stripReasoningBlocks())
            } catch (e: AiRepositoryException) {
                _uiState.update { it.copy(aiError = e.failure) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(aiError = AssistantResult.OtherError(e.message)) }
            } finally {
                _uiState.update { it.copy(aiLoading = false) }
            }
        }
    }

    private fun importSelected() {
        val state = _uiState.value
        val selected = state.selectedItems
        if (selected.isEmpty() || state.importing) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true) }
            // 0 = sin calendarios en el teléfono: AddCalendarEventUseCase crea uno local
            val calendarId = state.selectedCalendarId ?: state.calendars.firstOrNull()?.id ?: 0L
            val result = try {
                importTranscription(selected, calendarId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                TranscriptionImportResult(failed = selected.size)
            }
            _uiState.update { it.copy(importing = false, importResult = result) }
        }
    }

    private companion object {
        const val PARSE_DEBOUNCE_MILLIS = 250L
    }
}
