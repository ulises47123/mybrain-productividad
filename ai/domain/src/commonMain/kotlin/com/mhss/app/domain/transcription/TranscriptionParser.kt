package com.mhss.app.domain.transcription

import com.mhss.app.domain.stripReasoningBlocks
import com.mhss.app.domain.transcription.TranscriptionItem.EventItem
import com.mhss.app.domain.transcription.TranscriptionItem.NoteItem
import com.mhss.app.domain.transcription.TranscriptionItem.TaskItem
import com.mhss.app.domain.transcription.TranscriptionItem.Unrecognized
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Interpreta el Markdown que devuelve la IA con las secciones
 * `## Tareas`, `## Fechas`, `## Notas` y `## Cuaderno`.
 *
 * Es tolerante (espacios, guiones `-`/`–`, viñetas `*`, casillas `[x]`, mayúsculas,
 * bloque ```markdown envolvente, texto de más antes o después) y nunca lanza excepciones:
 * lo que no entiende lo devuelve como [Unrecognized].
 */
object TranscriptionParser {

    fun parse(markdown: String): ParsedTranscription = try {
        Parser(markdown).run()
    } catch (e: Exception) {
        // Último recurso: nunca romper la pantalla por un texto raro
        ParsedTranscription(
            items = listOf(
                Unrecognized(0, TranscriptionSection.UNKNOWN, markdown.take(MAX_UNRECOGNIZED_CHARS), UnrecognizedReason.BAD_FORMAT)
            )
        )
    }

    private class Parser(markdown: String) {
        private val items = mutableListOf<TranscriptionItem>()
        private val foundSections = mutableSetOf<TranscriptionSection>()
        private var nextId = 0
        private val lines: List<String> = markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            // Espacios duros y marcas invisibles que meten las apps al copiar
            .replace('\u00A0', ' ')
            .replace("\u200B", "")
            .stripReasoningBlocks()
            .lines()
            .let { TranscriptionNormalizer.normalize(it) }

        // Estado de la sección actual
        private var section: TranscriptionSection? = null
        private var unknownSectionLines = mutableListOf<String>()
        private var blockTitle: String? = null
        private val blockLines = mutableListOf<String>()

        fun run(): ParsedTranscription {
            val start = lines.indexOfFirst { recognizedSectionOf(it) != null }
            if (start == -1) return ParsedTranscription()
            val end = closingFenceIndex(start) ?: lines.size

            var inCodeBlock = false
            for (i in start until end) {
                val line = lines[i]
                // Dentro de un bloque de código (por ejemplo en el cuaderno) no se buscan títulos
                if (FENCE.matches(line)) inCodeBlock = !inCodeBlock
                val header = if (inCodeBlock || FENCE.matches(line)) null else sectionHeaderOf(line)
                if (header != null) {
                    finishSection()
                    section = header
                    foundSections += header
                    if (header == TranscriptionSection.UNKNOWN) unknownSectionLines += line.trim()
                    continue
                }
                runCatching { handleLine(line) }.onFailure {
                    add(Unrecognized(0, section ?: TranscriptionSection.UNKNOWN, line.trim(), UnrecognizedReason.BAD_FORMAT))
                }
            }
            finishSection()
            return ParsedTranscription(items.toList(), foundSections.toSet())
        }

        /**
         * Si después de la primera sección hay una cantidad impar de cercas ```, la última es
         * el cierre del bloque que envolvía toda la respuesta: se corta ahí (y lo de después es basura).
         */
        private fun closingFenceIndex(start: Int): Int? {
            val fences = (start until lines.size).filter { FENCE.matches(lines[it]) }
            return if (fences.size % 2 == 1) fences.last() else null
        }

        private fun add(item: TranscriptionItem) {
            val withId = when (item) {
                is TaskItem -> item.copy(id = nextId)
                is EventItem -> item.copy(id = nextId)
                is NoteItem -> item.copy(id = nextId)
                is Unrecognized -> item.copy(id = nextId)
            }
            nextId++
            items += withId
        }

        private fun handleLine(line: String) {
            when (section) {
                TranscriptionSection.TASKS -> handleTaskLine(line)
                TranscriptionSection.DATES -> handleEventLine(line)
                TranscriptionSection.NOTES, TranscriptionSection.NOTEBOOK -> handleNoteLine(line)
                TranscriptionSection.UNKNOWN -> if (line.isNotBlank()) unknownSectionLines += line.trim()
                null -> Unit
            }
        }

        private fun finishSection() {
            when (section) {
                TranscriptionSection.NOTES, TranscriptionSection.NOTEBOOK -> finishNoteBlock()
                TranscriptionSection.UNKNOWN -> {
                    if (unknownSectionLines.isNotEmpty()) {
                        add(
                            Unrecognized(
                                0,
                                TranscriptionSection.UNKNOWN,
                                unknownSectionLines.joinToString("\n").take(MAX_UNRECOGNIZED_CHARS),
                                UnrecognizedReason.UNKNOWN_SECTION
                            )
                        )
                    }
                    unknownSectionLines = mutableListOf()
                }
                else -> Unit
            }
        }

        // ---------- Tareas ----------

        private fun handleTaskLine(line: String) {
            if (line.isBlank() || line.isEmptyMarker() || HORIZONTAL_RULE.matches(line)) return
            val content = line.bulletContent()
            if (content == null) {
                add(Unrecognized(0, TranscriptionSection.TASKS, line.trim(), UnrecognizedReason.BAD_FORMAT))
                return
            }
            val parts = content.split(PIPE).map { it.trim() }
            val title = parts.first().cleanTitle()
            if (title.isEmpty()) {
                add(Unrecognized(0, TranscriptionSection.TASKS, line.trim(), UnrecognizedReason.EMPTY_TITLE))
                return
            }
            var dueDate: LocalDate? = null
            var dueTime: LocalTime? = null
            var priority: TranscriptionPriority? = null
            var proposed = false
            val extra = mutableListOf<String>()
            val warnings = mutableListOf<TranscriptionWarning>()

            for (part in parts.drop(1)) {
                if (part.isBlank()) continue
                if (part.isProposedMark()) { proposed = true; continue }
                val (key, value) = part.splitKeyValue()
                when {
                    key != null && key in DUE_KEYS -> {
                        when (val parsed = parseDateTime(value)) {
                            is DateParse.Ok -> {
                                dueDate = parsed.date
                                dueTime = parsed.time
                                if (parsed.invalidTime) warnings += TranscriptionWarning.INVALID_TIME
                            }
                            DateParse.Invalid, DateParse.NotADate -> warnings += TranscriptionWarning.INVALID_DUE_DATE
                        }
                    }
                    key != null && key in PRIORITY_KEYS -> {
                        priority = value.toPriority()
                        if (priority == null) warnings += TranscriptionWarning.INVALID_PRIORITY
                    }
                    // Sin clave: puede ser directamente una fecha o una prioridad
                    key == null && parseDateTime(part) is DateParse.Ok && dueDate == null -> {
                        val parsed = parseDateTime(part) as DateParse.Ok
                        dueDate = parsed.date
                        dueTime = parsed.time
                        if (parsed.invalidTime) warnings += TranscriptionWarning.INVALID_TIME
                    }
                    key == null && part.toPriority() != null && priority == null -> priority = part.toPriority()
                    else -> extra += part
                }
            }
            add(
                TaskItem(
                    id = 0,
                    title = title,
                    dueDate = dueDate,
                    dueTime = dueTime,
                    priority = priority,
                    description = extra.joinToString("\n").ifBlank { null },
                    warnings = warnings.distinct(),
                    // Solo tiene sentido marcar como sugerido algo que trae fecha
                    proposed = proposed && dueDate != null
                )
            )
        }

        // ---------- Fechas ----------

        private fun handleEventLine(line: String) {
            if (line.isBlank() || line.isEmptyMarker() || HORIZONTAL_RULE.matches(line)) return
            val content = line.bulletContent()
            if (content == null) {
                add(Unrecognized(0, TranscriptionSection.DATES, line.trim(), UnrecognizedReason.BAD_FORMAT))
                return
            }
            val parts = content.split(PIPE).map { it.trim() }.filter { it.isNotEmpty() }
            val dateIndex = parts.indexOfFirst { EVENT_DATE_START.containsMatchIn(it) }
            if (dateIndex == -1) {
                add(Unrecognized(0, TranscriptionSection.DATES, line.trim(), UnrecognizedReason.BAD_FORMAT))
                return
            }
            val match = EVENT_DATE_PART.matchEntire(parts[dateIndex])
            if (match == null) {
                add(Unrecognized(0, TranscriptionSection.DATES, line.trim(), UnrecognizedReason.BAD_FORMAT))
                return
            }
            val date = dateFrom(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            if (date == null) {
                add(Unrecognized(0, TranscriptionSection.DATES, line.trim(), UnrecognizedReason.INVALID_DATE))
                return
            }
            val warnings = mutableListOf<TranscriptionWarning>()
            var start: LocalTime? = null
            var end: LocalTime? = null
            if (match.groupValues[4].isNotEmpty()) {
                start = timeFrom(match.groupValues[4], match.groupValues[5])
                if (start == null) warnings += TranscriptionWarning.INVALID_TIME
            }
            if (start != null && match.groupValues[6].isNotEmpty()) {
                end = timeFrom(match.groupValues[6], match.groupValues[7])
                if (end == null) {
                    warnings += TranscriptionWarning.INVALID_TIME
                } else if (end <= start) {
                    warnings += TranscriptionWarning.END_BEFORE_START
                    end = null
                }
            }
            val proposed = parts.any { it.isProposedMark() }
            val others = parts.filterIndexed { index, part -> index != dateIndex && !part.isProposedMark() }.toMutableList()
            // Si la IA no puso el "|", el título quedó pegado después de la fecha
            val inlineTitle = match.groupValues[8].trim().trimStart('-', '–', '—', ':').trim()
            if (inlineTitle.isNotEmpty()) others.add(0, inlineTitle)
            val title = others.firstOrNull()?.cleanEventTitle().orEmpty()
            if (title.isEmpty()) {
                add(Unrecognized(0, TranscriptionSection.DATES, line.trim(), UnrecognizedReason.EMPTY_TITLE))
                return
            }
            add(
                EventItem(
                    id = 0,
                    title = title,
                    date = date,
                    startTime = start,
                    endTime = end,
                    description = others.drop(1).joinToString("\n").ifBlank { null },
                    warnings = warnings.distinct(),
                    proposed = proposed
                )
            )
        }

        // ---------- Notas y Cuaderno ----------

        private fun handleNoteLine(line: String) {
            val title = NOTE_TITLE.matchEntire(line)?.groupValues?.get(1)
            if (title != null) {
                finishNoteBlock()
                blockTitle = title.cleanTitle()
                return
            }
            blockLines += line
        }

        private fun finishNoteBlock() {
            val notebook = section == TranscriptionSection.NOTEBOOK
            val trimmedLines = blockLines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            // "Ninguna." seguido de más texto sin título: es charla de la IA, no una nota
            if (blockTitle == null && trimmedLines.size > 1 && trimmedLines.first().isEmptyMarker()) {
                val rest = trimmedLines.drop(1).filter { it.isNotBlank() }.joinToString("\n")
                blockLines.clear()
                add(Unrecognized(0, section ?: TranscriptionSection.UNKNOWN, rest.take(MAX_UNRECOGNIZED_CHARS), UnrecognizedReason.BAD_FORMAT))
                return
            }
            val content = blockLines
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
                .let { lines -> if (lines.size == 1 && lines.first().isEmptyMarker()) emptyList() else lines }
                .joinToString("\n")
                .trimIndent()
            val title = blockTitle
            blockLines.clear()
            blockTitle = null
            when {
                title != null && title.isNotEmpty() -> add(NoteItem(0, title, content, notebook))
                content.isNotBlank() -> {
                    // Texto sin "### Título": se usa la primera línea como título
                    val firstLine = content.lineSequence().first { it.isNotBlank() }
                    val derived = firstLine.bulletContent()?.cleanTitle() ?: firstLine.cleanTitle()
                    add(NoteItem(0, derived.take(MAX_DERIVED_TITLE).ifBlank { DEFAULT_NOTE_TITLE }, content, notebook))
                }
            }
        }
    }

    // ---------- Fechas y horas ----------

    private sealed interface DateParse {
        data class Ok(val date: LocalDate, val time: LocalTime?, val invalidTime: Boolean) : DateParse
        data object Invalid : DateParse
        data object NotADate : DateParse
    }

    private fun parseDateTime(raw: String): DateParse {
        val value = raw.trim().trim('*', '_', '`').trim()
        val iso = ISO_DATE_TIME.matchEntire(value)
        val local = LOCAL_DATE_TIME.matchEntire(value)
        val (date, hour, minute) = when {
            iso != null -> Triple(
                dateFrom(iso.groupValues[1], iso.groupValues[2], iso.groupValues[3]),
                iso.groupValues[4],
                iso.groupValues[5]
            )
            local != null -> Triple(
                dateFrom(local.groupValues[3], local.groupValues[2], local.groupValues[1]),
                local.groupValues[4],
                local.groupValues[5]
            )
            else -> return DateParse.NotADate
        }
        if (date == null) return DateParse.Invalid
        if (hour.isEmpty()) return DateParse.Ok(date, null, invalidTime = false)
        val time = timeFrom(hour, minute)
        return DateParse.Ok(date, time, invalidTime = time == null)
    }

    private fun dateFrom(year: String, month: String, day: String): LocalDate? = runCatching {
        LocalDate(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

    private fun timeFrom(hour: String, minute: String): LocalTime? = runCatching {
        LocalTime(hour.toInt(), minute.ifEmpty { "0" }.toInt())
    }.getOrNull()

    // ---------- Utilidades de texto ----------

    private fun recognizedSectionOf(line: String): TranscriptionSection? =
        sectionHeaderOf(line)?.takeIf { it != TranscriptionSection.UNKNOWN }

    /** Devuelve la sección si la línea es un título `#`/`##` (o `**Tareas**`); null si no es título. */
    private fun sectionHeaderOf(line: String): TranscriptionSection? {
        SECTION_HEADER.matchEntire(line)?.let { match ->
            return sectionByName(match.groupValues[1]) ?: TranscriptionSection.UNKNOWN
        }
        BOLD_HEADER.matchEntire(line)?.let { match ->
            return sectionByName(match.groupValues[1]) // en negrita solo cuenta si es una sección conocida
        }
        return null
    }

    private fun sectionByName(raw: String): TranscriptionSection? {
        val name = raw.normalized().trim('*', '_', ':', ' ', '.')
        return when (name) {
            "tareas", "tarea", "pendientes", "tasks", "to do" -> TranscriptionSection.TASKS
            "fechas", "fecha", "eventos", "calendario", "fechas importantes", "dates", "events" -> TranscriptionSection.DATES
            "notas", "nota", "notes" -> TranscriptionSection.NOTES
            "cuaderno", "apuntes", "apuntes de clase", "cuaderno de clase", "notebook" -> TranscriptionSection.NOTEBOOK
            else -> null
        }
    }

    /** Contenido de una viñeta (`-`, `–`, `*`, `•`, `+`, `1.`) sin la casilla `[ ]`/`[x]`; null si no es viñeta. */
    private fun String.bulletContent(): String? {
        val afterBullet = BULLET.matchEntire(this)?.groupValues?.get(1)
            ?: CHECKBOX_ONLY.matchEntire(this)?.let { this.trim() }
            ?: return null
        return afterBullet.replace(CHECKBOX, "").trim()
    }

    private fun String.isEmptyMarker(): Boolean {
        val text = trim().trimStart('-', '–', '—', '*', '•', '+', ' ').trim('*', '_', '`', ' ').trimEnd('.', '!').trim()
        return text.normalized() in EMPTY_MARKERS
    }

    /** «propuesto» / «sugerido»: la IA eligió el horario porque la persona se lo pidió. */
    private fun String.isProposedMark(): Boolean =
        normalized().trim('*', '_', '(', ')', '.', ' ') in PROPOSED_MARKS

    private fun String.cleanTitle(): String =
        trim().trim('*', '_', '`').trim().trimEnd(':').trim()

    private fun String.cleanEventTitle(): String =
        replace(ALL_DAY_SUFFIX, "").cleanTitle()

    private fun String.splitKeyValue(): Pair<String?, String> {
        val index = indexOf(':')
        if (index <= 0) return null to this
        val key = substring(0, index).normalized().trim('*', '_', ' ')
        // "10:30" no es clave: valor
        if (key.isEmpty() || key.any { it.isDigit() }) return null to this
        return key to substring(index + 1).trim()
    }

    private fun String.toPriority(): TranscriptionPriority? =
        when (normalized().trim('*', '_', '.', ' ')) {
            "alta", "high", "urgente", "alta prioridad" -> TranscriptionPriority.HIGH
            "media", "medium", "normal", "intermedia" -> TranscriptionPriority.MEDIUM
            "baja", "low" -> TranscriptionPriority.LOW
            else -> null
        }

    /** Minúsculas y sin tildes, para comparar palabras clave. */
    private fun String.normalized(): String = lowercase().map { char ->
        when (char) {
            'á', 'à', 'ä', 'â' -> 'a'
            'é', 'è', 'ë', 'ê' -> 'e'
            'í', 'ì', 'ï', 'î' -> 'i'
            'ó', 'ò', 'ö', 'ô' -> 'o'
            'ú', 'ù', 'ü', 'û' -> 'u'
            else -> char
        }
    }.joinToString("").trim()

    private const val MAX_UNRECOGNIZED_CHARS = 1_000
    private const val DEFAULT_NOTE_TITLE = "Nota"

    private val DUE_KEYS = setOf("vence", "vencimiento", "fecha", "fecha limite", "limite", "due", "entrega", "para")
    private val PRIORITY_KEYS = setOf("prioridad", "priority", "prio")
    private val PROPOSED_MARKS = setOf("propuesto", "propuesta", "sugerido", "sugerida", "proposed", "suggested")
    private val HORIZONTAL_RULE = Regex("""^\s*(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$""")
    private const val MAX_DERIVED_TITLE = 80

    private val SECTION_HEADER = Regex("""^\s{0,3}#{1,2}(?!#)\s*(.+?)\s*#*\s*$""")
    private val BOLD_HEADER = Regex("""^\s*\*\*\s*(.+?)\s*\*\*\s*:?\s*$""")
    private val NOTE_TITLE = Regex("""^\s{0,3}###(?!#)\s*(.+?)\s*#*\s*$""")
    private val FENCE = Regex("""^\s*```.*$""")
    private val BULLET = Regex("""^\s*(?:[-–—*•+]|\d{1,3}[.)])\s+(.*)$""")
    private val CHECKBOX_ONLY = Regex("""^\s*\[[ xX✓✔]?\].*$""")
    private val CHECKBOX = Regex("""^\[[ xX✓✔]?\]\s*""")
    private val PIPE = Regex("""\s*[|｜]\s*""")
    private val ALL_DAY_SUFFIX = Regex("""(?i)\(\s*(todo el d[ií]a|all day|d[ií]a completo)\s*\)""")
    private val EMPTY_MARKERS = setOf(
        "", "ninguna", "ninguno", "ningunas", "ningunos", "nada", "none", "n/a", "sin datos", "no hay"
    )

    // AAAA-MM-DD [HH:MM]  (también acepta / o . y "10hs")
    private val ISO_DATE_TIME = Regex(
        """^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T,]+(\d{1,2})(?:[:.h](\d{2}))?\s*(?:hs?\.?|horas)?)?$""",
        RegexOption.IGNORE_CASE
    )

    // DD/MM/AAAA [HH:MM]  (formato argentino, por tolerancia)
    private val LOCAL_DATE_TIME = Regex(
        """^(\d{1,2})/(\d{1,2})/(\d{4})(?:[ ,]+(\d{1,2})(?:[:.h](\d{2}))?\s*(?:hs?\.?|horas)?)?$""",
        RegexOption.IGNORE_CASE
    )

    private val EVENT_DATE_START = Regex("""^\**\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}""")

    // AAAA-MM-DD [HH:MM [- HH:MM]] [resto]
    private val EVENT_DATE_PART = Regex(
        """^\**\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\**(?:[ T,]+(\d{1,2})[:.h](\d{2})\s*(?:hs?\.?)?(?:\s*(?:-|–|—|a|al|hasta)\s*(\d{1,2})[:.h](\d{2})\s*(?:hs?\.?)?)?)?(?:\s+(.*))?$""",
        RegexOption.IGNORE_CASE
    )
}
