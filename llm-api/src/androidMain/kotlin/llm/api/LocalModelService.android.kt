package llm.api

import llm.config.LocalModelSettings
import llm.error.LlmErrorCode
import llm.error.LlmException

/**
 * Modelo local en Android: todavía no hay motor embebido.
 *
 * El error es explícito a propósito: el alumno debe entender que en el
 * teléfono el modo local no está disponible y que la alternativa es el
 * servidor de la cátedra (modo REMOTE).
 */
class UnavailableLocalModelService : LocalModelService {
    override val running: Boolean get() = false

    override suspend fun start(settings: LocalModelSettings, onStatus: (String) -> Unit): LlmGateway =
        throw LlmException(
            LlmErrorCode.NO_CONFIGURADO,
            "El modelo local todavía no está disponible en Android. " +
                "Usá el servidor de la cátedra (modo remoto) o el modelo local del escritorio.",
        )

    override fun stop() = Unit
}

/** Servicio de modelo local de Android. */
actual fun createLocalModelService(): LocalModelService = UnavailableLocalModelService()
