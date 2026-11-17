package com.mhss.app.domain.transcription

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Arma el prompt que se le da a una IA (Gemini, Grok, la IA configurada en Mi Cerebro…)
 * para convertir el audio o la transcripción de una clase/reunión en el Markdown que
 * entiende [TranscriptionParser].
 */
object TranscriptionPrompt {

    /**
     * @param referenceDate fecha de la grabación (por defecto, hoy en el teléfono).
     * @param timeZoneId zona horaria del teléfono, por ejemplo "America/Argentina/Jujuy".
     * @param transcript si viene, se agrega al final (para procesarla con la IA de Mi Cerebro).
     */
    fun build(
        referenceDate: LocalDate,
        timeZoneId: String,
        transcript: String? = null
    ): String {
        val example1 = referenceDate.plus(2, DateTimeUnit.DAY)
        val example2 = referenceDate.plus(6, DateTimeUnit.DAY)
        val example3 = referenceDate.plus(9, DateTimeUnit.DAY)
        val reference = "$referenceDate, ${referenceDate.dayOfWeek.spanishName()}"

        val prompt = """
Sos un asistente que convierte el audio o la transcripción de una clase o de una reunión en datos para importar en la app "Mi Cerebro" (tareas, calendario y notas).

Fecha de la grabación/referencia: $reference
Zona horaria del teléfono: $timeZoneId

Escuchá o leé TODO el contenido y respondé SOLO con Markdown: nada de texto antes ni después, sin saludos, sin explicaciones y sin encerrar la respuesta en un bloque de código. Usá exactamente estas cuatro secciones, con estos títulos y en este orden:

## Tareas
## Fechas
## Notas
## Cuaderno

Si una sección no tiene nada, escribí `Ninguna.` en la línea de abajo del título.

FORMATO DE CADA SECCIÓN

## Tareas
Cosas que alguien tiene que hacer: trabajos prácticos, lecturas, ejercicios, pendientes, cosas para llevar. Una tarea por línea, con este formato exacto:
- [ ] Título | vence: AAAA-MM-DD HH:MM | prioridad: alta
• "vence" y "prioridad" son opcionales: agregalos solo si se dijeron (o si salen sin ninguna duda de lo que se dijo).
• Si se dijo el día pero no la hora, poné solo `vence: AAAA-MM-DD`.
• La prioridad solo puede ser `alta`, `media` o `baja`.

## Fechas
Todo lo que tenga un día concreto en el calendario: exámenes, parciales, entregas, reuniones, clases especiales, salidas, feriados. Uno por línea, con uno de estos formatos exactos:
- AAAA-MM-DD HH:MM-HH:MM | Título
- AAAA-MM-DD HH:MM | Título
- AAAA-MM-DD | Título (todo el día)
• Usá el primero si se sabe la hora de inicio y de fin, el segundo si solo se sabe la de inicio y el tercero si no se dijo la hora.
• Si una entrega tiene día, va acá; y si además hay trabajo que hacer para esa entrega, agregá también la tarea en ## Tareas con el mismo "vence".

## Notas
Información útil que no es tarea ni fecha: avisos, acuerdos, decisiones, criterios de evaluación, materiales, links, datos de contacto. Cada nota así:
### Título corto de la nota
Texto de la nota en Markdown (párrafos o viñetas).

## Cuaderno
SOLO si en el audio o la transcripción hay contenido de clase o apuntes: temas explicados, definiciones, fórmulas, ejemplos y ejercicios del profe, lo que se dictó o se escribió en el pizarrón. Cada tema así:
### Tema
Apuntes ordenados en Markdown: subtítulos con ####, viñetas, definiciones en **negrita**, fórmulas, pasos y ejemplos.
Si no hay contenido de clase (por ejemplo, si es una reunión), escribí `Ninguna.`

REGLAS
1. No inventes nada. Usá únicamente lo que se dice en el audio o en la transcripción. Si falta un dato (fecha, hora, prioridad, lugar), omitilo; no lo completes con suposiciones.
2. Fechas relativas ("mañana", "el jueves", "la semana que viene", "dentro de 15 días"): calculalas SOLO a partir de la fecha de referencia de arriba. Si la fecha es ambigua o no se puede calcular con seguridad ("a fin de mes", "un día de estos", "después del parcial"), NO pongas una fecha: anotalo en ## Notas copiando la frase tal como se dijo.
3. Fechas siempre como AAAA-MM-DD y horas como HH:MM en formato de 24 horas, en la zona horaria indicada. Sin segundos ni zona horaria.
4. Una tarea o fecha por línea. No uses tablas ni el carácter | dentro de los títulos.
5. Respetá el idioma original del audio o la transcripción para el contenido. Los títulos de sección (## Tareas, ## Fechas, ## Notas, ## Cuaderno) y las palabras `vence`, `prioridad` y `Ninguna.` van siempre así, en español.
6. En ## Cuaderno no resumas de más: conservá completas las definiciones, fórmulas, pasos y ejemplos, en el orden en que se explicaron. Podés ordenar la redacción y sacar muletillas, pero no recortes contenido.
7. Empezá la respuesta directamente con "## Tareas".

EJEMPLO DE FORMATO (datos inventados solo para mostrar el formato; no los copies):
## Tareas
- [ ] Leer el capítulo 3 del apunte | vence: $example1 | prioridad: media
- [ ] Llevar la calculadora científica
## Fechas
- $example2 18:00-20:00 | Parcial de Física I
- $example3 | Entrega del TP 2 (todo el día)
## Notas
### Material de estudio
El apunte está en la fotocopiadora del centro de estudiantes.
## Cuaderno
### Ley de Ohm
- **Definición:** la corriente es proporcional a la tensión: V = I · R.
- Ejemplo del profe: con R = 10 Ω y V = 5 V, la corriente es I = 0,5 A.
""".trim()

        if (transcript.isNullOrBlank()) return prompt
        return buildString {
            append(prompt)
            append("\n\nTRANSCRIPCIÓN (entre las líneas de comillas triples):\n\"\"\"\n")
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
