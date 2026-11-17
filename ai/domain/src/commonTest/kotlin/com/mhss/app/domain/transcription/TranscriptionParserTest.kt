package com.mhss.app.domain.transcription

import com.mhss.app.domain.stripReasoningBlocks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionParserTest {

    private val complete = """
        ## Tareas
        - [ ] Leer el capítulo 3 | vence: 2026-11-20 18:30 | prioridad: alta
        - [ ] Llevar la calculadora | vence: 2026-11-21
        - [ ] Mandar mail al grupo
        ## Fechas
        - 2026-11-24 18:00-20:00 | Parcial de Física I
        - 2026-11-25 10:00 | Reunión con la directora
        - 2026-11-27 | Entrega del TP 2 (todo el día)
        ## Notas
        ### Material
        El apunte está en la fotocopiadora.

        - Precio: 500 pesos
        ### Criterios
        Se aprueba con 6.
        ## Cuaderno
        ### Ley de Ohm
        - **Definición:** V = I · R
        #### Ejemplo
        R = 10 Ω, V = 5 V → I = 0,5 A
    """.trimIndent()

    @Test
    fun parsesCompleteDocument() {
        val parsed = TranscriptionParser.parse(complete)

        assertEquals(3, parsed.tasks.size)
        assertEquals(3, parsed.events.size)
        assertEquals(2, parsed.notes.size)
        assertEquals(1, parsed.notebook.size)
        assertTrue(parsed.unrecognized.isEmpty())
        assertFalse(parsed.noSectionsFound)

        val first = parsed.tasks[0]
        assertEquals("Leer el capítulo 3", first.title)
        assertEquals(LocalDate(2026, 11, 20), first.dueDate)
        assertEquals(LocalTime(18, 30), first.dueTime)
        assertEquals(TranscriptionPriority.HIGH, first.priority)

        val second = parsed.tasks[1]
        assertEquals(LocalDate(2026, 11, 21), second.dueDate)
        assertNull(second.dueTime)
        assertNull(second.priority)

        val third = parsed.tasks[2]
        assertNull(third.dueDate)
        assertEquals("Mandar mail al grupo", third.title)

        val exam = parsed.events[0]
        assertEquals("Parcial de Física I", exam.title)
        assertEquals(LocalTime(18, 0), exam.startTime)
        assertEquals(LocalTime(20, 0), exam.endTime)
        assertFalse(exam.allDay)

        val meeting = parsed.events[1]
        assertEquals(LocalTime(10, 0), meeting.startTime)
        assertNull(meeting.endTime)

        val delivery = parsed.events[2]
        assertEquals("Entrega del TP 2", delivery.title)
        assertTrue(delivery.allDay)

        assertEquals("Material", parsed.notes[0].title)
        assertEquals("El apunte está en la fotocopiadora.\n\n- Precio: 500 pesos", parsed.notes[0].content)
        assertEquals("Criterios", parsed.notes[1].title)

        val notebook = parsed.notebook.single()
        assertEquals("Ley de Ohm", notebook.title)
        assertTrue(notebook.notebook)
        assertTrue(notebook.content.contains("#### Ejemplo"))
        assertTrue(notebook.content.contains("I = 0,5 A"))

        // ids únicos para las casillas
        assertEquals(parsed.items.size, parsed.items.map { it.id }.toSet().size)
    }

    @Test
    fun emptySectionsProduceNoItems() {
        val parsed = TranscriptionParser.parse(
            """
            ## Tareas
            Ninguna.
            ## Fechas
            - Ninguna.
            ## Notas
            Ninguna.
            ## Cuaderno
            *Ninguna.*
            """.trimIndent()
        )
        assertTrue(parsed.items.isEmpty())
        assertEquals(
            setOf(
                TranscriptionSection.TASKS,
                TranscriptionSection.DATES,
                TranscriptionSection.NOTES,
                TranscriptionSection.NOTEBOOK
            ),
            parsed.foundSections
        )
        assertFalse(parsed.noSectionsFound)
    }

    @Test
    fun worksWithoutNotebookSection() {
        val parsed = TranscriptionParser.parse(
            """
            ## Tareas
            - [ ] Preparar la presentación | prioridad: media
            ## Fechas
            Ninguna.
            ## Notas
            ### Acuerdo
            Nos juntamos los martes.
            """.trimIndent()
        )
        assertEquals(1, parsed.tasks.size)
        assertEquals(TranscriptionPriority.MEDIUM, parsed.tasks[0].priority)
        assertEquals(1, parsed.notes.size)
        assertTrue(parsed.notebook.isEmpty())
        assertTrue(parsed.unrecognized.isEmpty())
    }

    @Test
    fun ignoresGarbageAndCodeFenceAroundTheAnswer() {
        val parsed = TranscriptionParser.parse(
            """
            ¡Claro! Acá tenés el resumen de la clase:

            ```markdown
            ## TAREAS
            * [x] Hacer el ejercicio 4 | Vence: 2026-12-01 | Prioridad: Baja
            – [ ]   Estudiar derivadas
            ## fechas
            -  2026-12-03 08:00 – 10:00 | Recuperatorio
            ## Notas
            Ninguna.
            ## Cuaderno
            ### Derivadas
            ```python
            # esto es un comentario, no un título
            print("hola")
            ```
            Fin del tema.
            ```

            Espero que te sirva 😊
            """.trimIndent()
        )
        assertEquals(2, parsed.tasks.size)
        assertEquals("Hacer el ejercicio 4", parsed.tasks[0].title)
        assertEquals(LocalDate(2026, 12, 1), parsed.tasks[0].dueDate)
        assertEquals(TranscriptionPriority.LOW, parsed.tasks[0].priority)
        assertEquals("Estudiar derivadas", parsed.tasks[1].title)

        val event = parsed.events.single()
        assertEquals("Recuperatorio", event.title)
        assertEquals(LocalTime(8, 0), event.startTime)
        assertEquals(LocalTime(10, 0), event.endTime)

        val notebook = parsed.notebook.single()
        assertEquals("Derivadas", notebook.title)
        assertTrue(notebook.content.contains("# esto es un comentario"))
        assertTrue(notebook.content.endsWith("Fin del tema."))
        assertTrue(parsed.unrecognized.isEmpty(), "No debería haber nada sin reconocer: ${parsed.unrecognized}")
    }

    @Test
    fun invalidDatesAreReportedInsteadOfInvented() {
        val parsed = TranscriptionParser.parse(
            """
            ## Tareas
            - [ ] Entregar informe | vence: 2026-02-30 | prioridad: urgentísima
            - [ ] Leer | vence: 2026-11-20 25:99
            ## Fechas
            - 2026-13-40 | Examen imposible
            - el jueves | Reunión sin fecha
            - 2026-11-24 20:00-18:00 | Al revés
            ## Notas
            Ninguna.
            ## Cuaderno
            Ninguna.
            """.trimIndent()
        )
        val report = parsed.tasks[0]
        assertEquals("Entregar informe", report.title)
        assertNull(report.dueDate)
        assertNull(report.priority)
        assertTrue(TranscriptionWarning.INVALID_DUE_DATE in report.warnings)
        assertTrue(TranscriptionWarning.INVALID_PRIORITY in report.warnings)

        val read = parsed.tasks[1]
        assertEquals(LocalDate(2026, 11, 20), read.dueDate)
        assertNull(read.dueTime)
        assertTrue(TranscriptionWarning.INVALID_TIME in read.warnings)

        assertEquals(2, parsed.unrecognized.size)
        assertEquals(UnrecognizedReason.INVALID_DATE, parsed.unrecognized[0].reason)
        assertEquals(UnrecognizedReason.BAD_FORMAT, parsed.unrecognized[1].reason)

        val reversed = parsed.events.single()
        assertEquals("Al revés", reversed.title)
        assertNull(reversed.endTime)
        assertTrue(TranscriptionWarning.END_BEFORE_START in reversed.warnings)
    }

    @Test
    fun textWithoutSectionsIsReported() {
        val parsed = TranscriptionParser.parse("Hoy hablamos de muchas cosas pero no hay formato.")
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.noSectionsFound)
    }

    @Test
    fun unknownSectionsAndBadLinesAreUnrecognized() {
        val parsed = TranscriptionParser.parse(
            """
            ## Tareas
            Esto no es una viñeta
            - [ ] Tarea buena
            ## Resumen
            Un resumen que no pedimos.
            ## Notas
            Ninguna.
            """.trimIndent()
        )
        assertEquals(1, parsed.tasks.size)
        assertEquals(2, parsed.unrecognized.size)
        assertEquals(UnrecognizedReason.BAD_FORMAT, parsed.unrecognized[0].reason)
        assertEquals(UnrecognizedReason.UNKNOWN_SECTION, parsed.unrecognized[1].reason)
        assertTrue(parsed.unrecognized[1].text.contains("Un resumen"))
    }

    @Test
    fun toleratesEventsWithoutPipeAndArgentineDates() {
        val parsed = TranscriptionParser.parse(
            """
            ## Tareas
            1. [ ] Comprar materiales | vence: 20/11/2026 | alta
            ## Fechas
            - 2026-11-28 Acto de fin de año
            - Clase de consulta | 2026-11-26 15:00
            """.trimIndent()
        )
        val task = parsed.tasks.single()
        assertEquals(LocalDate(2026, 11, 20), task.dueDate)
        assertEquals(TranscriptionPriority.HIGH, task.priority)

        assertEquals(2, parsed.events.size)
        assertEquals("Acto de fin de año", parsed.events[0].title)
        assertTrue(parsed.events[0].allDay)
        assertEquals("Clase de consulta", parsed.events[1].title)
        assertEquals(LocalTime(15, 0), parsed.events[1].startTime)
    }

    @Test
    fun trailingChatAfterEmptyNotebookIsNotANote() {
        val parsed = TranscriptionParser.parse(
            """
            ## Tareas
            Ninguna.
            ## Cuaderno
            Ninguna.

            Si querés te armo un resumen más corto.
            """.trimIndent()
        )
        assertTrue(parsed.notebook.isEmpty())
        assertEquals(1, parsed.unrecognized.size)
    }

    @Test
    fun notesWithoutTitleUseFirstLine() {
        val parsed = TranscriptionParser.parse(
            """
            ## Notas
            La profe dijo que el parcial es a fin de mes.
            """.trimIndent()
        )
        val note = parsed.notes.single()
        assertEquals("La profe dijo que el parcial es a fin de mes.", note.title)
    }

    @Test
    fun reasoningBlocksAreRemoved() {
        val parsed = TranscriptionParser.parse(
            "<think>Primero pienso qué tareas hay...</think>\n## Tareas\n- [ ] Estudiar"
        )
        assertEquals("Estudiar", parsed.tasks.single().title)
        assertEquals("hola", "<think>a\nb</think>\nhola".stripReasoningBlocks())
        assertEquals("", "<think>sin cerrar".stripReasoningBlocks())
        assertEquals("respuesta", "razono sin abrir</think>respuesta".stripReasoningBlocks())
    }

    @Test
    fun neverCrashesOnWeirdInput() {
        val weird = listOf("", "   ", "##", "## Tareas\n-", "## Fechas\n- 9999-99-99 99:99-99:99 |", "## Notas\n###", "```\n## Tareas\n```")
        weird.forEach { input ->
            TranscriptionParser.parse(input) // no debe lanzar
        }
        val items = TranscriptionParser.parse("## Fechas\n- 9999-99-99 99:99-99:99 |").items
        assertTrue(items.all { it is TranscriptionItem.Unrecognized })
    }
}

class TranscriptionPromptTest {

    @Test
    fun includesReferenceDateTimeZoneAndSections() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "America/Argentina/Jujuy")
        assertTrue(prompt.contains("Fecha de la grabación/referencia: 2026-11-17, martes"))
        assertTrue(prompt.contains("Zona horaria del teléfono: America/Argentina/Jujuy"))
        val order = listOf("## Tareas", "## Fechas", "## Notas", "## Cuaderno").map { prompt.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertTrue(prompt.contains("Ninguna."))
        assertFalse(prompt.contains("TRANSCRIPCIÓN"))
    }

    @Test
    fun appendsTranscriptWhenGiven() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "UTC", "  Hola, hoy vemos derivadas.  ")
        assertTrue(prompt.trimEnd().endsWith("Hola, hoy vemos derivadas.\n\"\"\""))
    }

    @Test
    fun exampleInPromptIsParseable() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "UTC")
        val example = prompt.substringAfter("no los copies):\n")
        val parsed = TranscriptionParser.parse(example)
        assertEquals(2, parsed.tasks.size)
        assertEquals(2, parsed.events.size)
        assertEquals(1, parsed.notes.size)
        assertEquals(1, parsed.notebook.size)
        assertTrue(parsed.unrecognized.isEmpty())
        assertEquals(LocalDate(2026, 11, 19), parsed.tasks[0].dueDate)
    }
}
