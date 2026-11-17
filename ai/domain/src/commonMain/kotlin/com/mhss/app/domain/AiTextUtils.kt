package com.mhss.app.domain

private val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
private const val THINK_OPEN = "<think>"
private const val THINK_CLOSE = "</think>"

/**
 * Quita el razonamiento (<think>…</think>) que algunos modelos (por ejemplo los de NVIDIA)
 * dejan en el texto final, aunque se haya pedido que no razonen.
 */
fun String.stripReasoningBlocks(): String {
    var text = replace(THINK_BLOCK_REGEX, "")
    // Cierre suelto: el modelo empezó a razonar sin la etiqueta de apertura
    val closeIndex = text.lastIndexOf(THINK_CLOSE, ignoreCase = true)
    if (closeIndex != -1) text = text.substring(closeIndex + THINK_CLOSE.length)
    // Apertura sin cierre al principio: todo lo que sigue es razonamiento cortado
    val openIndex = text.indexOf(THINK_OPEN, ignoreCase = true)
    if (openIndex != -1 && text.substring(0, openIndex).isBlank()) text = ""
    return text.trim()
}
