package com.mhss.app.data

import kotlin.time.Duration.Companion.minutes

/**
 * Holds the API keys configured for a provider and skips the ones that recently failed
 * (rate limit, exhausted quota or invalid key) until their cooldown expires.
 * Cooldowns live in memory only, so they reset when the app process restarts.
 */
internal class ApiKeyPool(
    private val now: () -> Long = ::nowMillis
) {
    private var keys: List<String> = emptyList()
    private val blockedUntil = HashMap<String, Long>()
    private var cursor = 0

    fun setKeys(raw: String) {
        keys = raw.parseApiKeys()
        blockedUntil.keys.retainAll(keys.toSet())
        cursor = 0
    }

    val size: Int get() = keys.size

    /** Next key that is not cooling down, rotating so load is spread across keys. */
    fun nextAvailable(): String? {
        if (keys.isEmpty()) return null
        val time = now()
        repeat(keys.size) {
            val key = keys[cursor % keys.size]
            cursor = (cursor + 1) % keys.size
            if ((blockedUntil[key] ?: 0L) <= time) return key
        }
        return null
    }

    fun markFailed(key: String, cooldownMillis: Long = RATE_LIMIT_COOLDOWN_MILLIS) {
        blockedUntil[key] = now() + cooldownMillis
    }

    /** Minutes (rounded up) until the first blocked key becomes usable again. */
    fun minutesUntilAvailable(): Int {
        val next = keys.minOfOrNull { blockedUntil[it] ?: 0L } ?: return 0
        val remaining = (next - now()).coerceAtLeast(0L)
        return ((remaining + 59_999L) / 60_000L).toInt()
    }

    companion object {
        val RATE_LIMIT_COOLDOWN_MILLIS = 15.minutes.inWholeMilliseconds
        val INVALID_KEY_COOLDOWN_MILLIS = 60.minutes.inWholeMilliseconds
    }
}

/** Keys can be separated by commas, semicolons, spaces or new lines. */
internal fun String.parseApiKeys(): List<String> =
    split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.distinct()

internal enum class KeyFailure { None, RateLimited, InvalidKey }

/** Classifies a provider error so the repository knows whether to try another key. */
internal fun Throwable.toKeyFailure(): KeyFailure {
    val text = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    return when {
        listOf("429", "rate limit", "rate_limit", "quota", "resource_exhausted", "too many requests",
            "insufficient_quota", "credit balance", "overloaded", "529")
            .any { it in text } -> KeyFailure.RateLimited
        listOf("401", "403", "invalid api key", "invalid_api_key", "api key not valid",
            "unauthorized", "permission_denied", "authentication")
            .any { it in text } -> KeyFailure.InvalidKey
        else -> KeyFailure.None
    }
}
