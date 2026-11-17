package com.mhss.app.data

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import com.mhss.app.domain.baseChatSystemMessage
import com.mhss.app.domain.model.AiMessage
import com.mhss.app.domain.toolsSystemMessage
import com.mhss.app.preferences.domain.model.AiProvider
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


fun List<AiMessage>.buildChatPrompt(
    systemMessage: String,
    provider: AiProvider = AiProvider.None
) = compactForPrompt().let { compacted ->
    val system = if (compacted.size < size) systemMessage + "\n" + HISTORY_TRIMMED_NOTE else systemMessage
    compacted.buildPromptMessages(system, provider.llmParams(CHAT_MAX_OUTPUT_TOKENS))
}

/**
 * Parámetros de la petición según el proveedor.
 * NVIDIA (Nemotron) razona por defecto: se apaga con chat_template_kwargs.enable_thinking = false
 * y se manda el max_tokens clásico en lugar de max_completion_tokens.
 */
fun AiProvider.llmParams(maxTokens: Int? = null): LLMParams = when (this) {
    AiProvider.Nvidia -> LLMParams(
        additionalProperties = buildMap {
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            if (maxTokens != null) put("max_tokens", JsonPrimitive(maxTokens))
        }
    )
    else -> LLMParams(maxTokens = maxTokens)
}

private fun List<AiMessage>.buildPromptMessages(systemMessage: String, params: LLMParams) = prompt(
    "chat_prompt",
    params
) {
    system(systemMessage)
    forEach { message ->
        when (message) {
            is AiMessage.UserMessage -> user(message.content + message.attachmentsText)
            is AiMessage.AssistantMessage -> assistant(message.content)
            is AiMessage.ToolCall -> {
                assistant {
                    if (message.thoughtSignature != null) {
                        reasoning(
                            MessagePart.Reasoning(
                                content = emptyList(),
                                encrypted = message.thoughtSignature
                            )
                        )
                    }
                    toolCall(
                        MessagePart.Tool.Call(
                            id = message.id,
                            tool = message.name,
                            args = message.rawContent
                        )
                    )
                }
                toolResult(
                    MessagePart.Tool.Result(
                        id = message.id,
                        tool = message.name,
                        output = message.resultRawContent,
                        isError = message.isFailed
                    )
                )
            }
        }
    }
}


private const val HISTORY_TRIMMED_NOTE =
    "Note: older messages were omitted from this conversation to save tokens."

/**
 * Keeps the request small: drops the oldest messages once the history exceeds a character
 * budget (~4 chars per token) and shortens stale tool results and attachments.
 * The most recent turn is always kept intact.
 */
internal fun List<AiMessage>.compactForPrompt(
    maxChars: Int = HISTORY_MAX_CHARS,
    keepFullToolResults: Int = 2
): List<AiMessage> {
    if (isEmpty()) return this
    val lastUserIndex = indexOfLast { it is AiMessage.UserMessage }
    val recentToolCalls = withIndex()
        .filter { it.value is AiMessage.ToolCall }
        .map { it.index }
        .takeLast(keepFullToolResults)
        .toSet()

    val shortened = mapIndexed { index, message ->
        when {
            message is AiMessage.ToolCall && index !in recentToolCalls && message.resultRawContent.length > OLD_TOOL_RESULT_MAX_CHARS ->
                message.copy(resultRawContent = message.resultRawContent.take(OLD_TOOL_RESULT_MAX_CHARS) + "…[truncated]")
            message is AiMessage.UserMessage && index != lastUserIndex && message.attachmentsText.length > OLD_ATTACHMENT_MAX_CHARS ->
                message.copy(attachmentsText = message.attachmentsText.take(OLD_ATTACHMENT_MAX_CHARS) + "…[truncated]")
            else -> message
        }
    }

    var total = 0
    var firstKept = shortened.size
    for (i in shortened.indices.reversed()) {
        total += shortened[i].approxChars()
        if (total > maxChars && i < lastUserIndex) break
        firstKept = i
    }
    var kept = shortened.subList(firstKept, shortened.size)
    // Never start the history in the middle of an exchange
    val firstUser = kept.indexOfFirst { it is AiMessage.UserMessage }
    if (firstUser > 0) kept = kept.subList(firstUser, kept.size)
    return kept
}

private fun AiMessage.approxChars(): Int = when (this) {
    is AiMessage.UserMessage -> content.length + attachmentsText.length
    is AiMessage.AssistantMessage -> content.length
    is AiMessage.ToolCall -> rawContent.length + resultRawContent.length
}

private const val HISTORY_MAX_CHARS = 32_000
private const val OLD_TOOL_RESULT_MAX_CHARS = 400
private const val OLD_ATTACHMENT_MAX_CHARS = 1_500
private const val CHAT_MAX_OUTPUT_TOKENS = 8_192

@OptIn(ExperimentalUuidApi::class)
fun MessagePart.Tool.Call.toAiMessage(
    toolCallResult: Result<AiMessage.ToolCall>,
    thoughtSignature: String? = null
): AiMessage {
    return toolCallResult.getOrNull()?.copy(thoughtSignature = thoughtSignature)
        ?: AiMessage.ToolCall(
            uuid = Uuid.generateV7().toString(),
            id = id,
            name = tool,
            rawContent = args,
            resultRawContent = toolCallResult.exceptionOrNull()
                ?.getRootCause()
                ?.toString()
                ?: "Error executing tool",
            time = nowMillis(),
            isFailed = true,
            thoughtSignature = thoughtSignature
        )
}

fun String.toLLModel(provider: AiProvider, withTools: Boolean): LLModel {
    val llmProvider = provider.toLLMProvider()
    return LLModel(
        provider = llmProvider,
        id = this,
        capabilities = buildList {
            // Koog internally throws if an Anthropic model doesn't declare tool capabilities
            if (withTools || provider == AiProvider.Anthropic) {
                add(LLMCapability.Tools)
                add(LLMCapability.ToolChoice)
            }
            if (provider == AiProvider.Gemini) {
                add(LLMCapability.Thinking)
            }
            add(LLMCapability.Completion)
            if (llmProvider == LLMProvider.OpenAI){
                // NVIDIA solo expone /v1/chat/completions (no la Responses API)
                if (provider != AiProvider.Nvidia) add(LLMCapability.OpenAIEndpoint.Responses)
                add(LLMCapability.OpenAIEndpoint.Completions)
            }
        },
        contextLength = 128_000,
        maxOutputTokens = 32_000,
    )
}

fun AiProvider.toLLMProvider() = when (this) {
    AiProvider.OpenAI -> LLMProvider.OpenAI
    AiProvider.Gemini -> LLMProvider.Google
    AiProvider.Anthropic -> LLMProvider.Anthropic
    AiProvider.OpenRouter -> LLMProvider.OpenRouter
    AiProvider.Nvidia -> LLMProvider.OpenAI
    AiProvider.Ollama -> LLMProvider.Ollama
    AiProvider.LmStudio -> LLMProvider.OpenAI
    AiProvider.GeminiNano -> LLMProvider.Google
    AiProvider.None -> LLMProvider.OpenAI // just a placeholder
}

@OptIn(ExperimentalUuidApi::class)
fun Message.Assistant.toNewAssistantMessage() = AiMessage.AssistantMessage(
    uuid = Uuid.generateV7().toString(),
    content = textContent(),
    time = nowMillis()
)

internal fun nowMillis() = Clock.System.now().toEpochMilliseconds()
private val currentTimeZone = TimeZone.currentSystemDefault()
internal fun currentLocalDateTime() = Clock.System.now().toLocalDateTime(currentTimeZone)
internal const val llmDateTimeFormatUnicode = "HH:mm dd-MM-yyyy"

internal val llmDateTimeWithDayNameFormat = LocalDateTime.Format {
    hour(); char(':'); minute();
    char(' ');
    dayOfWeek(DayOfWeekNames.ENGLISH_FULL)
    char(' ');
    day(); char('-'); monthNumber(); char('-'); year()
}
internal val llmDateTimeFormat = LocalDateTime.Format {
    byUnicodePattern(llmDateTimeFormatUnicode)
}
internal fun String.parseDateTimeFromLLM() = runCatching {
    LocalDateTime.parse(this, llmDateTimeFormat).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
}.getOrNull()
internal fun Long.formatDateTimeForLLM() =
    Instant.fromEpochMilliseconds(this)
        .toLocalDateTime(currentTimeZone)
        .format(llmDateTimeWithDayNameFormat)

fun buildChatSystemMessage(toolsEnabled: Boolean) = buildString {
    appendLine(baseChatSystemMessage)
    if (toolsEnabled) appendLine(toolsSystemMessage)
    append("Current date & time: ")
    appendLine(currentLocalDateTime().format(llmDateTimeWithDayNameFormat))
    append("Time zone: "); append(currentTimeZone)
}

fun Throwable.getRootCause(): Throwable {
    var rootCause: Throwable? = this
    while (rootCause?.cause != null) {
        rootCause = rootCause.cause
    }
    return rootCause ?: this
}

internal val json = Json { ignoreUnknownKeys = true }
