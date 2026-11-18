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
    fun includesDateTimeZoneAndIsShort() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "America/Argentina/Jujuy", currentTime = "14:30")
        assertTrue(prompt.contains("Hoy es 2026-11-17 (martes), 14:30, zona horaria America/Argentina/Jujuy."))
        assertTrue(prompt.contains("- TAREA |"))
        assertTrue(prompt.contains("- EVENTO |"))
        assertTrue(prompt.contains("NOTA:"))
        assertTrue(prompt.contains("CUADERNO:"))
        assertFalse(prompt.contains("## "))
        assertFalse(prompt.contains("Ya tengo agendado"))
        assertFalse(prompt.contains("TEXTO (entre"))
        // Con el pedido anterior (~4.300) Grok ofrecía adjuntarlo como archivo
        assertTrue(prompt.length < 2_800, "el pedido mide ${prompt.length}")
    }

    @Test
    fun includesAgendaWhenGiven() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "UTC", agenda = "- mié 18: 18:00-22:00 Clases\n")
        assertTrue(prompt.contains("Ya tengo agendado:\n- mié 18: 18:00-22:00 Clases\n\nFORMATO"))
    }

    @Test
    fun appendsTranscriptWhenGiven() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "UTC", "  Hola, hoy vemos derivadas.  ")
        assertTrue(prompt.trimEnd().endsWith("Hola, hoy vemos derivadas.\n\"\"\""))
    }

    @Test
    fun exampleInPromptIsParseable() {
        val prompt = TranscriptionPrompt.build(LocalDate(2026, 11, 17), "UTC")
        val example = prompt.substringAfter("no los copies):\n").substringBefore("REGLAS")
        val parsed = TranscriptionParser.parse(example)
        assertEquals(3, parsed.tasks.size)
        assertEquals(2, parsed.events.size)
        assertEquals(1, parsed.notes.size)
        assertEquals(1, parsed.notebook.size)
        assertTrue(parsed.unrecognized.isEmpty())
        assertEquals(LocalDate(2026, 11, 18), parsed.tasks[0].dueDate)
        assertFalse(parsed.tasks[0].proposed)
        assertTrue(parsed.tasks[1].proposed)
        assertEquals(LocalTime(16, 0), parsed.tasks[1].dueTime)
    }
}

/** Lo que de verdad llega al portapapeles desde las apps de IA (capturado el 18/11/2026). */
class TranscriptionRealWorldTest {

    /** Gemini (Android), botón Copiar: borra los ## y ### y cambia las viñetas a « * ». */
    private val geminiCopied = """
Tareas
 * [ ] Ordenar el cuarto | vence: 2026-11-19
 * [ ] Lavar los platos
 * [ ] Regar las plantas
 * [ ] Sacar la basura
 * [ ] Ver una película
 * [ ] Doblar la ropa
Fechas
Ninguna.
Notas
Película
quiero ver una película pero no sé cuándo, puede ser hoy o mañana
Cuaderno
Ninguna.
"""

    @Test
    fun geminiCopyWithoutHashesIsUnderstood() {
        val parsed = TranscriptionParser.parse(geminiCopied)
        assertFalse(parsed.noSectionsFound)
        assertEquals(6, parsed.tasks.size)
        assertEquals("Ordenar el cuarto", parsed.tasks[0].title)
        assertEquals(LocalDate(2026, 11, 19), parsed.tasks[0].dueDate)
        assertTrue(parsed.events.isEmpty())
        val note = parsed.notes.single()
        assertEquals("Película", note.title)
        assertTrue(note.content.startsWith("quiero ver una película"))
        assertTrue(parsed.notebook.isEmpty())
        assertTrue(parsed.unrecognized.isEmpty())
    }

    @Test
    fun bareSectionWordInsideANoteDoesNotOpenASection() {
        val parsed = TranscriptionParser.parse(
            "Tareas\n * [ ] Estudiar\nNotas\nReunión\nHablamos de las\nTareas\nde la semana y de las\nNotas\nfinales.\nCuaderno\nNinguna."
        )
        assertEquals(1, parsed.tasks.size)
        val note = parsed.notes.single()
        assertEquals("Reunión", note.title)
        assertTrue(note.content.contains("Tareas\nde la semana"))
        assertTrue(note.content.contains("Notas\nfinales."))
    }

    @Test
    fun hashFormatIsLeftAlone() {
        val parsed = TranscriptionParser.parse("## Notas\n### Acuerdo\nHablamos de:\nNotas\nTarea: nada raro\n## Cuaderno\nNinguna.")
        val note = parsed.notes.single()
        assertEquals("Acuerdo", note.title)
        assertTrue(note.content.contains("Notas\nTarea: nada raro"))
        assertTrue(parsed.tasks.isEmpty())
    }

    @Test
    fun typedLinesFormat() {
        val parsed = TranscriptionParser.parse(
            """
Acá tenés lo que pediste:

- TAREA | Limpiar mi pieza | vence: 2026-11-19
* TAREA | Ver una película | vence: 2026-11-18 21:00 | propuesto
• **TAREA** | Comprar pan | dicho: un día de estos
- EVENTO | 2026-11-24 18:00-20:00 | Parcial de Física
- EVENTO | 2026-11-27 | Entrega del TP 2

NOTA: Material
El apunte está en la fotocopiadora.
- Precio: 500 pesos

NOTA: Criterios
Se aprueba con 6.

CUADERNO: Ley de Ohm
- **Definición:** V = I · R
## Ejemplo
R = 10 Ω
""".trimIndent()
        )
        assertEquals(listOf("Limpiar mi pieza", "Ver una película", "Comprar pan"), parsed.tasks.map { it.title })
        assertEquals(listOf(false, true, false), parsed.tasks.map { it.proposed })
        assertEquals(LocalTime(21, 0), parsed.tasks[1].dueTime)
        assertEquals("dicho: un día de estos", parsed.tasks[2].description)
        assertNull(parsed.tasks[2].dueDate)
        assertEquals(2, parsed.events.size)
        assertEquals("Parcial de Física", parsed.events[0].title)
        assertTrue(parsed.events[1].allDay)
        assertEquals(listOf("Material", "Criterios"), parsed.notes.map { it.title })
        assertTrue(parsed.notes[0].content.contains("- Precio: 500 pesos"))
        val notebook = parsed.notebook.single()
        assertEquals("Ley de Ohm", notebook.title)
        assertTrue(notebook.content.contains("#### Ejemplo"))
        assertTrue(parsed.unrecognized.isEmpty())
    }

    @Test
    fun typedLinesWithoutBulletsOrInsideACodeFence() {
        val parsed = TranscriptionParser.parse(
            "```\nTAREA | Doblar la ropa\nEVENTO | 2026-11-20 10:00 | Dentista | propuesto\n```"
        )
        assertEquals("Doblar la ropa", parsed.tasks.single().title)
        val event = parsed.events.single()
        assertEquals("Dentista", event.title)
        assertTrue(event.proposed)
    }

    @Test
    fun proseIsNotMistakenForTypedLines() {
        val parsed = TranscriptionParser.parse("## Notas\n### Aviso\nTarea: leer el capítulo 3.\nNota: traer DNI.")
        assertTrue(parsed.tasks.isEmpty())
        assertEquals(1, parsed.notes.size)
    }
}
