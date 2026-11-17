package com.mhss.app.domain.use_case

import com.mhss.app.domain.model.CalendarEvent
import com.mhss.app.domain.model.Note
import com.mhss.app.domain.model.Priority
import com.mhss.app.domain.model.Task
import com.mhss.app.domain.transcription.NOTEBOOK_FOLDER_NAME
import com.mhss.app.domain.transcription.TranscriptionImportResult
import com.mhss.app.domain.transcription.TranscriptionItem
import com.mhss.app.domain.transcription.TranscriptionPriority
import com.mhss.app.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.koin.core.annotation.Factory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Crea en Mi Cerebro los elementos elegidos de una transcripción:
 * tareas → tareas, fechas → eventos del calendario del teléfono,
 * notas → notas sueltas y cuaderno → notas dentro de la carpeta "Cuaderno".
 */
@Factory
class ImportTranscriptionUseCase(
    private val upsertTask: UpsertTaskUseCase,
    private val addCalendarEvent: AddCalendarEventUseCase,
    private val upsertNote: UpsertNoteUseCase,
    private val searchNoteFolders: SearchNoteFoldersByNameUseCase,
    private val createNoteFolder: CreateNoteFolderUseCase,
    private val widgetUpdater: WidgetUpdater
) {
    /**
     * @param calendarId calendario donde se crean los eventos (si el teléfono no tiene ninguno,
     * [AddCalendarEventUseCase] crea uno local).
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        items: List<TranscriptionItem>,
        calendarId: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): TranscriptionImportResult {
        var result = TranscriptionImportResult()
        var notebookFolderId: String? = null

        for (item in items) {
            try {
                when (item) {
                    is TranscriptionItem.TaskItem -> {
                        val now = Clock.System.now().toEpochMilliseconds()
                        val dueDate = item.dueDate?.let { date ->
                            LocalDateTime(date, item.dueTime ?: DEFAULT_TASK_TIME)
                                .toInstant(timeZone)
                                .toEpochMilliseconds()
                        }
                        upsertTask(
                            Task(
                                title = item.title,
                                description = item.description.orEmpty(),
                                priority = item.priority.toTaskPriority(),
                                dueDate = dueDate,
                                createdDate = now,
                                updatedDate = now,
                                id = Uuid.generateV7().toString()
                            )
                        )
                        result = result.copy(tasks = result.tasks + 1)
                    }

                    is TranscriptionItem.EventItem -> {
                        val (start, end) = if (item.allDay) {
                            // Convención de Android: los eventos de todo el día van en UTC a medianoche
                            item.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() to
                                    item.date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                        } else {
                            val startInstant = LocalDateTime(item.date, item.startTime!!).toInstant(timeZone)
                            val endInstant = item.endTime
                                ?.let { LocalDateTime(item.date, it).toInstant(timeZone) }
                                ?: (startInstant + 1.hours)
                            startInstant.toEpochMilliseconds() to endInstant.toEpochMilliseconds()
                        }
                        val id = addCalendarEvent(
                            CalendarEvent(
                                id = 0,
                                title = item.title,
                                description = item.description,
                                start = start,
                                end = end,
                                allDay = item.allDay,
                                calendarId = calendarId
                            ),
                            updateWidget = false // se redibuja una sola vez al final
                        )
                        result = if (id != null) result.copy(events = result.events + 1)
                        else result.copy(failed = result.failed + 1)
                    }

                    is TranscriptionItem.NoteItem -> {
                        val folderId = if (item.notebook) {
                            notebookFolderId ?: findOrCreateNotebookFolder { created ->
                                if (created) result = result.copy(notebookFolderCreated = true)
                            }.also { notebookFolderId = it }
                        } else null
                        val now = Clock.System.now().toEpochMilliseconds()
                        upsertNote(
                            Note(
                                title = item.title,
                                content = item.content,
                                folderId = folderId,
                                createdDate = now,
                                updatedDate = now
                            )
                        )
                        result = if (item.notebook) result.copy(notebookNotes = result.notebookNotes + 1)
                        else result.copy(notes = result.notes + 1)
                    }

                    is TranscriptionItem.Unrecognized -> Unit // nunca se importa
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                result = result.copy(failed = result.failed + 1)
            }
        }
        // Tareas y notas: WidgetAutoUpdater redibuja su widget una vez (con demora) al ver los cambios.
        // Calendario: un solo redibujo para toda la importación, en vez de uno por evento.
        if (result.events > 0) {
            try {
                widgetUpdater.updateAll(WidgetUpdater.WidgetType.Calendar)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return result
    }

    private suspend fun findOrCreateNotebookFolder(onCreated: (Boolean) -> Unit): String {
        val existing = searchNoteFolders(NOTEBOOK_FOLDER_NAME)
            .firstOrNull { it.name.trim().equals(NOTEBOOK_FOLDER_NAME, ignoreCase = true) }
        if (existing != null) {
            onCreated(false)
            return existing.id
        }
        onCreated(true)
        return createNoteFolder(NOTEBOOK_FOLDER_NAME)
    }

    private fun TranscriptionPriority?.toTaskPriority() = when (this) {
        TranscriptionPriority.HIGH -> Priority.HIGH
        TranscriptionPriority.MEDIUM -> Priority.MEDIUM
        TranscriptionPriority.LOW, null -> Priority.LOW
    }

    companion object {
        /** Hora del recordatorio cuando la tarea tiene día pero no hora. */
        val DEFAULT_TASK_TIME = LocalTime(9, 0)
    }
}
