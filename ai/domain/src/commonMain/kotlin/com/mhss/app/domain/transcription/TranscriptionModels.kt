package com.mhss.app.domain.transcription

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Secciones que la IA tiene que devolver, en este orden. */
enum class TranscriptionSection {
    TASKS,
    DATES,
    NOTES,
    NOTEBOOK,
    UNKNOWN
}

enum class TranscriptionPriority { HIGH, MEDIUM, LOW }

/** Avisos sobre un elemento que sí se puede importar pero al que se le descartó algún dato. */
enum class TranscriptionWarning {
    /** La fecha de vencimiento no es válida (por ejemplo 2026-02-30): la tarea se importa sin fecha. */
    INVALID_DUE_DATE,
    /** La hora no es válida: se usa solo el día. */
    INVALID_TIME,
    /** La prioridad no es alta/media/baja: se ignora. */
    INVALID_PRIORITY,
    /** La hora de fin es anterior o igual a la de inicio: el evento dura una hora. */
    END_BEFORE_START
}

/** Por qué una línea quedó como "no reconocida". */
enum class UnrecognizedReason {
    /** Está bajo un título que no es ninguna de las cuatro secciones. */
    UNKNOWN_SECTION,
    /** La línea no tiene el formato esperado para su sección. */
    BAD_FORMAT,
    /** La fecha del evento no existe (por ejemplo 2026-13-40). */
    INVALID_DATE,
    /** Falta el título. */
    EMPTY_TITLE
}

sealed interface TranscriptionItem {
    /** Identificador estable dentro de una misma interpretación (sirve para las casillas). */
    val id: Int
    val section: TranscriptionSection

    data class TaskItem(
        override val id: Int,
        val title: String,
        val dueDate: LocalDate? = null,
        val dueTime: LocalTime? = null,
        val priority: TranscriptionPriority? = null,
        val description: String? = null,
        val warnings: List<TranscriptionWarning> = emptyList()
    ) : TranscriptionItem {
        override val section get() = TranscriptionSection.TASKS
    }

    data class EventItem(
        override val id: Int,
        val title: String,
        val date: LocalDate,
        val startTime: LocalTime? = null,
        val endTime: LocalTime? = null,
        val description: String? = null,
        val warnings: List<TranscriptionWarning> = emptyList()
    ) : TranscriptionItem {
        override val section get() = TranscriptionSection.DATES
        val allDay: Boolean get() = startTime == null
    }

    /** Nota común ([notebook] = false) o apunte del cuaderno ([notebook] = true). */
    data class NoteItem(
        override val id: Int,
        val title: String,
        val content: String,
        val notebook: Boolean
    ) : TranscriptionItem {
        override val section get() = if (notebook) TranscriptionSection.NOTEBOOK else TranscriptionSection.NOTES
    }

    data class Unrecognized(
        override val id: Int,
        override val section: TranscriptionSection,
        val text: String,
        val reason: UnrecognizedReason
    ) : TranscriptionItem
}

data class ParsedTranscription(
    val items: List<TranscriptionItem> = emptyList(),
    /** Secciones reconocidas que aparecieron en el texto (aunque estuvieran vacías). */
    val foundSections: Set<TranscriptionSection> = emptySet()
) {
    val tasks get() = items.filterIsInstance<TranscriptionItem.TaskItem>()
    val events get() = items.filterIsInstance<TranscriptionItem.EventItem>()
    val notes get() = items.filterIsInstance<TranscriptionItem.NoteItem>().filter { !it.notebook }
    val notebook get() = items.filterIsInstance<TranscriptionItem.NoteItem>().filter { it.notebook }
    val unrecognized get() = items.filterIsInstance<TranscriptionItem.Unrecognized>()
    val importable get() = items.filter { it !is TranscriptionItem.Unrecognized }

    /** true si el texto no tenía ninguna de las secciones esperadas. */
    val noSectionsFound get() = foundSections.none { it != TranscriptionSection.UNKNOWN }
}

/** Resumen de lo que se creó al importar. */
data class TranscriptionImportResult(
    val tasks: Int = 0,
    val events: Int = 0,
    val notes: Int = 0,
    val notebookNotes: Int = 0,
    val failed: Int = 0,
    val notebookFolderCreated: Boolean = false
) {
    val total get() = tasks + events + notes + notebookNotes
}

const val NOTEBOOK_FOLDER_NAME = "Cuaderno"
