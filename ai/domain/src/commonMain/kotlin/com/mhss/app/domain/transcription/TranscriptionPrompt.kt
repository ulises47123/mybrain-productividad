package com.mhss.app.domain.transcription

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Arma el pedido que se le da a una IA (Gemini, Grok, ChatGPT, la IA configurada en Mi Cerebro…)
 * para convertir un audio o un texto (clase, reunión o nota de voz personal) en líneas que
 * entiende [TranscriptionParser].
 *
 * Decisiones (probadas con las apps de Gemini y Grok el 18/11/2026):
 * - Cada línea dice su tipo en texto plano (`- TAREA | …`, `NOTA: …`): el botón «Copiar» de
 *   Gemini borra los `##` de los títulos, así que el formato no puede depender de ellos.
 * - Es corto: con el pedido anterior (~4.300 caracteres) Grok ofrecía adjuntarlo como archivo.
 * - El ejemplo es la especificación: los modelos imitan ejemplos mejor de lo que siguen reglas.
 */
object TranscriptionPrompt {

    /**
     * @param referenceDate fecha de hoy en el teléfono.
     * @param timeZoneId zona horaria del teléfono, por ejemplo "America/Argentina/Jujuy".
     * @param transcript si viene, se agrega al final (para procesarlo con la IA de Mi Cerebro).
     * @param currentTime hora actual "HH:MM", para que la IA no proponga horarios que ya pasaron.
     * @param agenda eventos ya agendados de los próximos días, una línea por evento, para que
     *   los horarios que proponga la IA no se superpongan. Vacío o null = no se incluye.
     */
    fun build(
        referenceDate: LocalDate,
        timeZoneId: String,
        transcript: String? = null,
        currentTime: String? = null,
        agenda: String? = null
    ): String {
        val example1 = referenceDate.plus(1, DateTimeUnit.DAY)
        val example2 = referenceDate.plus(6, DateTimeUnit.DAY)
        val example3 = referenceDate.plus(9, DateTimeUnit.DAY)
        val now = "$referenceDate (${referenceDate.dayOfWeek.spanishName()})" + (currentTime?.let { ", $it" } ?: "")
        val busy = agenda?.trim().orEmpty()

        val prompt = buildString {
            append(
                """
Convertí el audio o texto adjunto (clase, reunión o nota de voz personal) en datos para mi app de agenda. Respondé SOLO con líneas del formato de abajo: sin saludo, sin comentarios, sin tablas y sin títulos.

Hoy es $now, zona horaria $timeZoneId.
""".trim()
            )
            if (busy.isNotEmpty()) append("\nYa tengo agendado:\n").append(busy)
            append("\n\n")
            append(
                """
FORMATO (ejemplo con datos inventados, no los copies):
- TAREA | Leer el capítulo 3 | vence: $example1 | prioridad: alta
- TAREA | Ordenar el escritorio | vence: $example1 16:00 | propuesto
- TAREA | Llevar la calculadora
- EVENTO | $example2 18:00-20:00 | Parcial de Física
- EVENTO | $example3 | Entrega del TP 2

NOTA: Título corto
Texto de la nota.

CUADERNO: Tema de la clase
Apuntes completos, con viñetas y **negrita**.

REGLAS
1. TAREA = algo para hacer. EVENTO = algo con día fijo (examen, entrega, reunión, turno). "vence", la hora y "prioridad" (alta, media o baja) son opcionales. Fechas AAAA-MM-DD y horas de 24 h.
2. Calculá las fechas relativas ("mañana", "el jueves") desde hoy. Una fecha dicha una sola vez vale para todo lo enumerado en esa frase: "mañana tengo que A, B y C" son tres tareas con vence mañana.
3. No inventes fechas ni horas. Única excepción: si la persona pide que elijas vos ("ponelo cuando pueda", "no sé cuándo", "organizame el día"), elegí un día y una hora razonables, posteriores a ahora, que no se superpongan con lo que ya tengo agendado ni entre sí, y terminá esa línea con "| propuesto". Nunca propongas ni cambies exámenes, entregas ni nada que tenga fecha puesta por otra persona.
4. Si una fecha quedó dudosa y nadie pidió que elijas, dejá la tarea sin "vence" y agregá "| dicho: la frase textual".
5. NOTA solo para información que sirva después (avisos, acuerdos, datos, links). No hagas notas con saludos, presentaciones ni resúmenes del audio.
6. CUADERNO solo si hay contenido de clase: conservá definiciones, fórmulas, pasos y ejemplos en orden, sin resumir de más.
7. Orden: primero todas las TAREA y EVENTO, después las NOTA y al final el CUADERNO, con una línea en blanco antes de cada "NOTA:" y "CUADERNO:". Si no hay nada de un tipo, no escribas nada de ese tipo. Mantené el idioma del audio.
""".trim()
            )
        }

        if (transcript.isNullOrBlank()) return prompt
        return buildString {
            append(prompt)
            append("\n\nTEXTO (entre las líneas de comillas triples):\n\"\"\"\n")
            append(transcript.trim())
            append("\n\"\"\"")
        }
    }
}

fun DayOfWeek.spanishName(): String = when (this) {
    DayOfWeek.MONDAY -> "lunes"
    DayOfWeek.TUESDAY -> "martes"
    DayOfWeek.WEDNESDAY -> "miércoles"
    DayOfWeek.THURSDAY -> "jueves"
    DayOfWeek.FRIDAY -> "viernes"
    DayOfWeek.SATURDAY -> "sábado"
    DayOfWeek.SUNDAY -> "domingo"
}
