package com.mhss.app.presentation.transcription

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mhss.app.domain.transcription.ParsedTranscription
import com.mhss.app.domain.transcription.TranscriptionImportResult
import com.mhss.app.domain.transcription.TranscriptionItem
import com.mhss.app.domain.transcription.TranscriptionPriority
import com.mhss.app.domain.transcription.TranscriptionWarning
import com.mhss.app.domain.transcription.UnrecognizedReason
import com.mhss.app.domain.transcription.spanishName
import com.mhss.app.presentation.toUserMessage
import com.mhss.app.ui.Res
import com.mhss.app.ui.components.common.MyBrainAppBar
import com.mhss.app.ui.ic_copy
import com.mhss.app.ui.ic_delete
import com.mhss.app.ui.ic_import
import com.mhss.app.ui.ic_paste
import com.mhss.app.ui.import_transcription
import com.mhss.app.ui.navigation.Screen
import com.mhss.app.ui.ok
import com.mhss.app.ui.cancel
import com.mhss.app.ui.snackbar.LocalisedSnackbarHost
import com.mhss.app.ui.snackbar.LocalisedSnackbarMessage
import com.mhss.app.ui.transcription_ai_desc
import com.mhss.app.ui.transcription_ai_not_configured
import com.mhss.app.ui.transcription_ai_title
import com.mhss.app.ui.transcription_all_day
import com.mhss.app.ui.transcription_calendar_label
import com.mhss.app.ui.transcription_calendar_permission
import com.mhss.app.ui.transcription_clear
import com.mhss.app.ui.transcription_clipboard_empty
import com.mhss.app.ui.transcription_copy_prompt
import com.mhss.app.ui.transcription_due
import com.mhss.app.ui.transcription_hide_prompt
import com.mhss.app.ui.transcription_import_selected
import com.mhss.app.ui.transcription_importing
import com.mhss.app.ui.transcription_include_agenda
import com.mhss.app.ui.transcription_fix_with_ai
import com.mhss.app.ui.transcription_keep_as_note
import com.mhss.app.ui.transcription_proposed
import com.mhss.app.ui.transcription_markdown_hint
import com.mhss.app.ui.transcription_no_sections
import com.mhss.app.ui.transcription_open_integrations
import com.mhss.app.ui.transcription_paste
import com.mhss.app.ui.transcription_preview_title
import com.mhss.app.ui.transcription_priority_high
import com.mhss.app.ui.transcription_priority_low
import com.mhss.app.ui.transcription_priority_medium
import com.mhss.app.ui.transcription_process_with_ai
import com.mhss.app.ui.transcription_processing
import com.mhss.app.ui.transcription_prompt_context
import com.mhss.app.ui.transcription_prompt_copied
import com.mhss.app.ui.transcription_reason_bad_format
import com.mhss.app.ui.transcription_reason_empty_title
import com.mhss.app.ui.transcription_reason_invalid_date
import com.mhss.app.ui.transcription_reason_unknown_section
import com.mhss.app.ui.transcription_result_events
import com.mhss.app.ui.transcription_result_failed
import com.mhss.app.ui.transcription_result_folder_created
import com.mhss.app.ui.transcription_result_notebook
import com.mhss.app.ui.transcription_result_notes
import com.mhss.app.ui.transcription_result_tasks
import com.mhss.app.ui.transcription_result_title
import com.mhss.app.ui.transcription_section_dates
import com.mhss.app.ui.transcription_section_notebook
import com.mhss.app.ui.transcription_section_notes
import com.mhss.app.ui.transcription_section_tasks
import com.mhss.app.ui.transcription_section_unrecognized
import com.mhss.app.ui.transcription_select_all
import com.mhss.app.ui.transcription_select_none
import com.mhss.app.ui.transcription_show_prompt
import com.mhss.app.ui.transcription_step1_desc
import com.mhss.app.ui.transcription_step1_title
import com.mhss.app.ui.transcription_step2_title
import com.mhss.app.ui.transcription_transcript_hint
import com.mhss.app.ui.transcription_warning_end_before_start
import com.mhss.app.ui.transcription_warning_invalid_due_date
import com.mhss.app.ui.transcription_warning_invalid_priority
import com.mhss.app.ui.transcription_warning_invalid_time
import com.mhss.app.util.clipboard.copyText
import com.mhss.app.util.clipboard.pasteText
import com.mhss.app.util.permissions.Permission
import com.mhss.app.util.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ImportTranscriptionScreen(
    navController: NavHostController,
    viewModel: ImportTranscriptionViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val readCalendarPermission = rememberPermissionState(Permission.READ_CALENDAR)
    val writeCalendarPermission = rememberPermissionState(Permission.WRITE_CALENDAR)
    var showPrompt by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(readCalendarPermission.isGranted) {
        if (readCalendarPermission.isGranted) viewModel.onEvent(ImportTranscriptionEvent.ReloadCalendars)
    }

    fun showMessage(message: StringResource, success: Boolean) {
        scope.launch {
            snackbarHostState.showSnackbar(
                if (success) LocalisedSnackbarMessage.Success(message) else LocalisedSnackbarMessage.Error(message)
            )
        }
    }

    Scaffold(
        topBar = { MyBrainAppBar(stringResource(Res.string.import_transcription)) },
        snackbarHost = { LocalisedSnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 1. Prompt ----------
            item(key = "prompt") {
                StepCard(title = stringResource(Res.string.transcription_step1_title)) {
                    Text(
                        text = stringResource(Res.string.transcription_step1_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.referenceDate?.let { date ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                Res.string.transcription_prompt_context,
                                date.pretty(),
                                state.timeZoneId
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.agenda.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onEvent(ImportTranscriptionEvent.IncludeAgenda(!state.includeAgenda)) }
                        ) {
                            Checkbox(
                                checked = state.includeAgenda,
                                onCheckedChange = { viewModel.onEvent(ImportTranscriptionEvent.IncludeAgenda(it)) }
                            )
                            Text(
                                text = stringResource(Res.string.transcription_include_agenda),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                clipboard.copyText("prompt", state.prompt)
                                snackbarHostState.showSnackbar(
                                    LocalisedSnackbarMessage.Success(Res.string.transcription_prompt_copied)
                                )
                            }
                        }) {
                            Icon(painterResource(Res.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.transcription_copy_prompt))
                        }
                        TextButton(onClick = { showPrompt = !showPrompt }) {
                            Text(
                                stringResource(
                                    if (showPrompt) Res.string.transcription_hide_prompt else Res.string.transcription_show_prompt
                                )
                            )
                        }
                    }
                    AnimatedVisibility(showPrompt) {
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            Text(
                                text = state.prompt,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }

            // ---------- 2. Pegar Markdown ----------
            item(key = "markdown") {
                StepCard(title = stringResource(Res.string.transcription_step2_title)) {
                    OutlinedTextField(
                        value = state.markdown,
                        onValueChange = { viewModel.onEvent(ImportTranscriptionEvent.UpdateMarkdown(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(Res.string.transcription_markdown_hint)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 5,
                        maxLines = 12,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !state.aiLoading
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            enabled = !state.aiLoading,
                            onClick = {
                                scope.launch {
                                    val text = clipboard.pasteText()
                                    if (text.isNullOrBlank()) {
                                        snackbarHostState.showSnackbar(
                                            LocalisedSnackbarMessage.Error(Res.string.transcription_clipboard_empty)
                                        )
                                    } else {
                                        viewModel.onEvent(ImportTranscriptionEvent.UpdateMarkdown(text))
                                    }
                                }
                            }
                        ) {
                            Icon(painterResource(Res.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.transcription_paste))
                        }
                        if (state.markdown.isNotEmpty() && !state.aiLoading) {
                            OutlinedButton(onClick = { viewModel.onEvent(ImportTranscriptionEvent.UpdateMarkdown("")) }) {
                                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.transcription_clear))
                            }
                        }
                    }
                }
            }

            // ---------- 2 bis. IA de Mi Cerebro ----------
            item(key = "ai") {
                StepCard(title = stringResource(Res.string.transcription_ai_title)) {
                    Text(
                        text = stringResource(Res.string.transcription_ai_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.transcript,
                        onValueChange = { viewModel.onEvent(ImportTranscriptionEvent.UpdateTranscript(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(Res.string.transcription_transcript_hint)) },
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !state.aiLoading
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!state.aiEnabled) {
                        Text(
                            text = stringResource(Res.string.transcription_ai_not_configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { navController.navigate(Screen.IntegrationsScreen) }) {
                            Text(stringResource(Res.string.transcription_open_integrations))
                        }
                    } else if (state.aiLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(Res.string.transcription_processing),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.onEvent(ImportTranscriptionEvent.CancelAi) }) {
                                Text(stringResource(Res.string.cancel))
                            }
                        }
                    } else {
                        Button(
                            enabled = state.transcript.isNotBlank(),
                            onClick = { viewModel.onEvent(ImportTranscriptionEvent.GenerateWithAi) }
                        ) {
                            Text(stringResource(Res.string.transcription_process_with_ai))
                        }
                    }
                    state.aiError?.let { error ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = error.toUserMessage(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---------- 3. Vista previa ----------
            val parsed = state.parsed
            if (parsed != null) {
                previewItems(
                    parsed = parsed,
                    selectedIds = state.selectedIds,
                    onToggle = { viewModel.onEvent(ImportTranscriptionEvent.ToggleItem(it)) },
                    onSelectAll = { viewModel.onEvent(ImportTranscriptionEvent.SelectAll(it)) },
                    canFixWithAi = state.aiEnabled && !state.aiLoading,
                    onFixWithAi = { viewModel.onEvent(ImportTranscriptionEvent.FixWithAi) },
                    onKeepAsNote = { viewModel.onEvent(ImportTranscriptionEvent.KeepAsNote) },
                    calendarSelector = {
                        if (state.calendars.isNotEmpty()) {
                            CalendarSelector(
                                calendars = state.calendars.map { it.id to "${it.name} (${it.account})" },
                                selectedId = state.selectedCalendarId,
                                onSelect = { viewModel.onEvent(ImportTranscriptionEvent.SelectCalendar(it)) }
                            )
                        }
                    }
                )
                if (parsed.importable.isNotEmpty()) {
                    item(key = "import_button") {
                        Button(
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = state.selectedItems.isNotEmpty() && !state.importing && !state.aiLoading,
                            onClick = {
                                val calendarOk = readCalendarPermission.isGranted && writeCalendarPermission.isGranted
                                if (state.hasSelectedEvents && !calendarOk) {
                                    if (!readCalendarPermission.isGranted) readCalendarPermission.launchRequest()
                                    else writeCalendarPermission.launchRequest()
                                    showMessage(Res.string.transcription_calendar_permission, success = false)
                                } else {
                                    viewModel.onEvent(ImportTranscriptionEvent.Import)
                                }
                            }
                        ) {
                            if (state.importing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(Res.string.transcription_importing))
                            } else {
                                Icon(painterResource(Res.drawable.ic_import), contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(Res.string.transcription_import_selected, state.selectedItems.size))
                            }
                        }
                    }
                }
            }
        }
    }

    state.importResult?.let { result ->
        ImportResultDialog(
            result = result,
            onDismiss = { viewModel.onEvent(ImportTranscriptionEvent.DismissResult) }
        )
    }
}

private fun LazyListScope.previewItems(
    parsed: ParsedTranscription,
    selectedIds: Set<Int>,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    canFixWithAi: Boolean,
    onFixWithAi: () -> Unit,
    onKeepAsNote: () -> Unit,
    calendarSelector: @Composable () -> Unit
) {
    item(key = "preview_header") {
        Column {
            Text(
                text = stringResource(Res.string.transcription_preview_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            when {
                // Nunca un callejón sin salida: si no se entendió, se puede ordenar con la IA propia o guardar tal cual
                parsed.importable.isEmpty() -> Column {
                    WarningText(stringResource(Res.string.transcription_no_sections))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        if (canFixWithAi) {
                            Button(onClick = onFixWithAi) { Text(stringResource(Res.string.transcription_fix_with_ai)) }
                        }
                        OutlinedButton(onClick = onKeepAsNote) { Text(stringResource(Res.string.transcription_keep_as_note)) }
                    }
                }
                parsed.importable.isNotEmpty() -> Row {
                    TextButton(onClick = { onSelectAll(true) }) { Text(stringResource(Res.string.transcription_select_all)) }
                    TextButton(onClick = { onSelectAll(false) }) { Text(stringResource(Res.string.transcription_select_none)) }
                }
            }
        }
    }
    section("tasks", Res.string.transcription_section_tasks, parsed.tasks, selectedIds, onToggle)
    if (parsed.events.isNotEmpty()) {
        section("events", Res.string.transcription_section_dates, parsed.events, selectedIds, onToggle)
        item(key = "calendar_selector") { calendarSelector() }
    }
    section("notes", Res.string.transcription_section_notes, parsed.notes, selectedIds, onToggle)
    section("notebook", Res.string.transcription_section_notebook, parsed.notebook, selectedIds, onToggle)
    section("unrecognized", Res.string.transcription_section_unrecognized, parsed.unrecognized, selectedIds, onToggle)
}

private fun LazyListScope.section(
    key: String,
    title: StringResource,
    sectionItems: List<TranscriptionItem>,
    selectedIds: Set<Int>,
    onToggle: (Int) -> Unit
) {
    if (sectionItems.isEmpty()) return
    item(key = "header_$key") {
        Text(
            text = stringResource(title, sectionItems.size),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (key == "unrecognized") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
    items(sectionItems, key = { "item_${it.id}" }) { item ->
        PreviewItemCard(
            item = item,
            checked = item.id in selectedIds,
            onToggle = { onToggle(item.id) }
        )
    }
}

@Composable
private fun PreviewItemCard(
    item: TranscriptionItem,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val unrecognized = item is TranscriptionItem.Unrecognized
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unrecognized) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (unrecognized) Modifier else Modifier.clickable(onClick = onToggle))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (!unrecognized) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(top = if (unrecognized) 4.dp else 12.dp, end = 8.dp, bottom = 4.dp)) {
                when (item) {
                    is TranscriptionItem.TaskItem -> {
                        ItemTitle(item.title)
                        val details = buildList {
                            item.dueDate?.let { add(stringResource(Res.string.transcription_due, prettyDateTime(it, item.dueTime))) }
                            item.priority?.let { add(stringResource(it.labelRes())) }
                        }
                        if (details.isNotEmpty()) ItemDetail(details.joinToString(" · "))
                        if (item.proposed) ProposedText()
                        item.description?.let { ItemDetail(it) }
                        item.warnings.forEach { WarningText(stringResource(it.labelRes())) }
                    }
                    is TranscriptionItem.EventItem -> {
                        ItemTitle(item.title)
                        val start = item.startTime
                        val end = item.endTime
                        val time = when {
                            start == null -> stringResource(Res.string.transcription_all_day)
                            end != null -> "${start.pretty()}–${end.pretty()}"
                            else -> start.pretty()
                        }
                        ItemDetail("${item.date.pretty()} · $time")
                        if (item.proposed) ProposedText()
                        item.description?.let { ItemDetail(it) }
                        item.warnings.forEach { WarningText(stringResource(it.labelRes())) }
                    }
                    is TranscriptionItem.NoteItem -> {
                        ItemTitle(item.title)
                        if (item.content.isNotBlank()) {
                            Text(
                                text = item.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    is TranscriptionItem.Unrecognized -> {
                        Text(
                            text = stringResource(item.reason.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSelector(
    calendars: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = calendars.firstOrNull { it.first == selectedId }?.second ?: calendars.first().second
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            label = { Text(stringResource(Res.string.transcription_calendar_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            calendars.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportResultDialog(
    result: TranscriptionImportResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.transcription_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (result.tasks > 0) Text(stringResource(Res.string.transcription_result_tasks, result.tasks))
                if (result.events > 0) Text(stringResource(Res.string.transcription_result_events, result.events))
                if (result.notes > 0) Text(stringResource(Res.string.transcription_result_notes, result.notes))
                if (result.notebookNotes > 0) Text(stringResource(Res.string.transcription_result_notebook, result.notebookNotes))
                if (result.notebookFolderCreated) Text(stringResource(Res.string.transcription_result_folder_created))
                if (result.failed > 0) {
                    Text(
                        text = stringResource(Res.string.transcription_result_failed, result.failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ok)) }
        }
    )
}

@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun ItemTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ItemDetail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** El horario lo eligió la IA porque se lo pediste: se distingue de lo que se dijo en el audio. */
@Composable
private fun ProposedText() {
    Text(
        text = stringResource(Res.string.transcription_proposed),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun WarningText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

private fun LocalDate.pretty(): String {
    val (year, month, day) = toString().split("-")
    return "${dayOfWeek.spanishName().take(3)} $day/$month/$year"
}

private fun LocalTime.pretty(): String = toString().take(5)

private fun prettyDateTime(date: LocalDate, time: LocalTime?): String =
    if (time == null) date.pretty() else "${date.pretty()} ${time.pretty()}"

private fun TranscriptionPriority.labelRes() = when (this) {
    TranscriptionPriority.HIGH -> Res.string.transcription_priority_high
    TranscriptionPriority.MEDIUM -> Res.string.transcription_priority_medium
    TranscriptionPriority.LOW -> Res.string.transcription_priority_low
}

private fun TranscriptionWarning.labelRes() = when (this) {
    TranscriptionWarning.INVALID_DUE_DATE -> Res.string.transcription_warning_invalid_due_date
    TranscriptionWarning.INVALID_TIME -> Res.string.transcription_warning_invalid_time
    TranscriptionWarning.INVALID_PRIORITY -> Res.string.transcription_warning_invalid_priority
    TranscriptionWarning.END_BEFORE_START -> Res.string.transcription_warning_end_before_start
}

private fun UnrecognizedReason.labelRes() = when (this) {
    UnrecognizedReason.UNKNOWN_SECTION -> Res.string.transcription_reason_unknown_section
    UnrecognizedReason.BAD_FORMAT -> Res.string.transcription_reason_bad_format
    UnrecognizedReason.INVALID_DATE -> Res.string.transcription_reason_invalid_date
    UnrecognizedReason.EMPTY_TITLE -> Res.string.transcription_reason_empty_title
}
