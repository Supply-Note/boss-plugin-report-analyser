package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiRequest
import kotlinx.coroutines.CancellationException

/**
 * Retries a gateway call on the other models BOSS offers when the default one is
 * unavailable (502/503/overloaded), so one provider outage doesn't break the chat.
 */
internal object AiRouting {

    private val TRANSIENT = Regex("unavailable|\\b50[234]\\b|\\b529\\b|overloaded|temporarily|try again|retry shortly", RegexOption.IGNORE_CASE)

    fun isTransient(e: Throwable): Boolean = TRANSIENT.containsMatchIn(e.message.orEmpty())

    /** Default routing first, then every other available model (needs the providerOverride capability). */
    private fun candidates(gateway: AiGatewayAPI): List<Pair<Map<String, String>, String>> {
        val active = runCatching { gateway.activeModel() }.getOrNull()
        val default = emptyMap<String, String>() to (active?.modelId ?: "default model")
        val canOverride = runCatching { AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE in gateway.capabilities() }.getOrDefault(false)
        if (!canOverride) return listOf(default)
        val others = runCatching { gateway.availableModels() }.getOrDefault(emptyList()).flatMap { p ->
            p.models.filterNot { p.providerId == active?.providerId && it.id == active.modelId }.map { m ->
                mapOf(AiRequest.EXTRAS_KEY_PROVIDER_ID to p.providerId, AiRequest.EXTRAS_KEY_MODEL_OVERRIDE to m.id) to m.id
            }
        }
        return listOf(default) + others.take(MAX_FALLBACKS)
    }

    /** Runs [call] with fallbacks; on success returns the result and the model that produced it. */
    suspend fun <T> withFallback(
        gateway: AiGatewayAPI,
        request: AiRequest,
        onFallback: (String) -> Unit = {},
        call: suspend (AiRequest) -> Result<T>,
    ): Result<Pair<T, String>> {
        var lastError: Throwable? = null
        candidates(gateway).forEachIndexed { i, (extras, label) ->
            if (i > 0) onFallback(label)
            val result = try {
                call(if (extras.isEmpty()) request else request.copy(extras = request.extras + extras))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { return Result.success(it to label) }
            val error = result.exceptionOrNull()!!
            if (!isTransient(error)) return Result.failure(error)
            lastError = error
        }
        return Result.failure(
            IllegalStateException(
                "The AI provider is unavailable right now (${lastError?.message}). " +
                    "Try again shortly, or pick another provider in BOSS Settings → AI Providers.",
            ),
        )
    }

    private const val MAX_FALLBACKS = 3
}
