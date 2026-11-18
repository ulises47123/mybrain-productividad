package com.mhss.app.domain.transcription

/**
 * Lleva lo que pega el usuario al Markdown por secciones que entiende [TranscriptionParser].
 *
 * Existe porque el botón «Copiar» de las apps de IA no entrega siempre lo mismo que se ve:
 * Gemini, por ejemplo, borra los `##` de los títulos. Se aceptan tres formas:
 *
 * 1. **Líneas con su tipo** (lo que pide el prompt actual): `- TAREA | …`, `- EVENTO | …`,
 *    `NOTA: Título` y `CUADERNO: Tema`. Es texto plano: sobrevive a cualquier copiado.
 * 2. **Secciones con `#`** (`## Tareas`…): el formato anterior. Se deja tal cual.
 * 3. **Secciones sin `#`**: una línea que es exactamente `Tareas`, `Fechas`, `Notas` o
 *    `Cuaderno`. Cada una vale una sola vez y en ese orden, para que la palabra «Notas»
 *    dentro del texto de una nota no abra otra sección.
 */
internal object TranscriptionNormalizer {

    fun normalize(lines: List<String>): List<String> = when {
        lines.any { typedItem(it) != null || typedBlock(it) != null } -> fromTypedLines(lines)
        lines.none { HASH_SECTION.matches(it) } && lines.any { bareSection(it) != null } -> fromBareSections(lines)
        else -> lines
    }

    // ---------- 1. Líneas con su tipo ----------

    private enum class Kind { TASK, EVENT, NOTE, NOTEBOOK }

    private fun typedItem(line: String): Pair<Kind, String>? {
        val match = TYPED_ITEM_UPPER.matchEntire(line) ?: TYPED_ITEM_PIPE.matchEntire(line) ?: return null
        val kind = when (match.groupValues[1].lowercase()) {
            "tarea", "task", "pendiente" -> Kind.TASK
            else -> Kind.EVENT
        }
        return kind to match.groupValues[2].trim()
    }

    private fun typedBlock(line: String): Pair<Kind, String>? {
        val match = TYPED_BLOCK.matchEntire(line) ?: return null
        val kind = if (match.groupValues[1].startsWith("N")) Kind.NOTE else Kind.NOTEBOOK
        return kind to match.groupValues[2].trim().trim('*', '_').trim()
    }

    private fun fromTypedLines(lines: List<String>): List<String> {
        val tasks = mutableListOf<String>()
        val events = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val notebook = mutableListOf<String>()
        var block: MutableList<String>? = null
        var inFence = false

        for (line in lines) {
            val item = if (inFence) null else typedItem(line)
            val header = if (inFence) null else typedBlock(line)
            when {
                item != null -> {
                    block = null
                    if (item.first == Kind.TASK) tasks += "- [ ] ${item.second}" else events += "- ${item.second}"
                }
                header != null -> {
                    block = if (header.first == Kind.NOTE) notes else notebook
                    block += "### ${header.second.ifBlank { "Nota" }}"
                }
                block != null -> {
                    // Dentro de una nota o del cuaderno todo es contenido (incluidos los bloques de código)
                    if (FENCE.matches(line)) inFence = !inFence
                    // Un título con # dentro del contenido se baja de nivel para que no corte la nota
                    block += if (HASH_TITLE.containsMatchIn(line)) line.replaceFirst(HASH_TITLE, "#### ") else line
                }
                // Antes de la primera nota: saludos, cercas ``` o texto suelto de la IA → se ignora
                else -> Unit
            }
        }
        return buildList {
            add("## Tareas"); addAll(tasks)
            add("## Fechas"); addAll(events)
            add("## Notas"); addAll(notes)
            add("## Cuaderno"); addAll(notebook)
        }
    }

    // ---------- 3. Secciones sin # ----------

    private fun bareSection(line: String): Int? {
        val name = line.trim().trim('*', '_').trim().trimEnd(':').trim().lowercase()
        return BARE_SECTIONS.indexOf(name).takeIf { it >= 0 }
    }

    private fun fromBareSections(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var current = -1
        var needsNoteTitle = false
        for ((index, line) in lines.withIndex()) {
            val section = bareSection(line)
            if (section != null && section > current) {
                current = section
                out += "## " + BARE_TITLES[section]
                needsNoteTitle = section >= NOTES_INDEX
                continue
            }
            if (needsNoteTitle && line.isNotBlank()) {
                needsNoteTitle = false
                // Lo que era «### Título» llega como una línea corta suelta, seguida del texto de la nota
                if (looksLikeNoteTitle(line, lines.getOrNull(index + 1))) {
                    out += "### " + line.trim()
                    continue
                }
            }
            out += line
        }
        return out
    }

    private fun looksLikeNoteTitle(line: String, next: String?): Boolean {
        val text = line.trim()
        if (text.length > MAX_BARE_TITLE || BULLET_START.containsMatchIn(text)) return false
        if (text.last() in ".,:;") return false
        if (next == null || next.isBlank()) return false
        return bareSection(next) == null
    }

    private const val NOTES_INDEX = 2
    private const val MAX_BARE_TITLE = 60
    private val BARE_SECTIONS = listOf("tareas", "fechas", "notas", "cuaderno")
    private val BARE_TITLES = listOf("Tareas", "Fechas", "Notas", "Cuaderno")

    private val HASH_SECTION = Regex("""^\s{0,3}#{1,2}(?!#)\s*\S.*$""")
    private val HASH_TITLE = Regex("""^\s{0,3}#{1,3}(?!#)\s*""")
    private val FENCE = Regex("""^\s*```.*$""")
    private val BULLET_START = Regex("""^(?:[-–—*•+]|\d{1,3}[.)]|\[)\s*""")

    // «- TAREA | Título | vence: …», «* **EVENTO** | 2026-11-24 18:00 | Parcial», «TAREA: Título».
    // En mayúsculas vale con «|» o «:»; en minúsculas solo con «|», para no confundirse con prosa («Tarea: leer…»).
    private const val ITEM_PREFIX = """^\s*(?:(?:[-–—*•+]|\d{1,3}[.)])\s+)?(?:\[[ xX✓✔]?\]\s*)?\**\s*"""
    private val TYPED_ITEM_UPPER = Regex(ITEM_PREFIX + """(TAREA|TASK|PENDIENTE|EVENTO|EVENT|FECHA)\s*\**\s*[|｜:]\s*\**\s*(.+)$""")
    private val TYPED_ITEM_PIPE = Regex(
        ITEM_PREFIX + """(tarea|task|pendiente|evento|event|fecha)\s*\**\s*[|｜]\s*\**\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // «NOTA: Título», «**CUADERNO:** Tema», «### NOTA: Título». En mayúsculas, para no confundirse con prosa («Nota: llevar…»)
    private val TYPED_BLOCK = Regex(
        """^\s*(?:#{1,6}\s*)?\**\s*(NOTA|CUADERNO)\s*\**\s*:\s*\**\s*(.*)$"""
    )
}
