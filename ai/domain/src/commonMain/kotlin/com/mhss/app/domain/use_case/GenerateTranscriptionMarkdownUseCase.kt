package com.mhss.app.domain.use_case

import com.mhss.app.domain.repository.AiRepository
import com.mhss.app.domain.transcription.TranscriptionPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import org.koin.core.annotation.Factory

/**
 * Manda la transcripción a la IA configurada en Mi Cerebro con el mismo prompt que se copia
 * para las IAs externas. Emite el texto acumulado a medida que llega.
 */
@Factory
class GenerateTranscriptionMarkdownUseCase(
    private val aiRepository: AiRepository
) {
    operator fun invoke(
        transcript: String,
        referenceDate: LocalDate,
        timeZoneId: String
    ): Flow<String> = flow {
        val prompt = TranscriptionPrompt.build(referenceDate, timeZoneId, transcript)
        val accumulated = StringBuilder()
        aiRepository.sendPrompt(prompt).collect { chunk ->
            accumulated.append(chunk)
            emit(accumulated.toString())
        }
    }
}
