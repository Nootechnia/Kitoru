package llm.api

import llm.config.LocalModelSettings

/**
 * Servicio de modelo **local**: levanta un motor de inferencia en el propio
 * dispositivo y expone un [LlmGateway] apuntando a él.
 *
 * En el escritorio la implementación real es `llama-server` de llama.cpp
 * (proceso hijo). En Android todavía no hay implementación: la interfaz
 * devuelve un error explícito en vez de fallar de forma confusa.
 *
 * El modelo local es el modo en que un alumno puede estudiar **sin**
 * depender de la institución: si el Kitoru-Server no está disponible, el
 * alumno sigue pudiendo chatear con su propio modelo. La contrapartida es
 * que el docente no ve esos chats (ver [kitoru.domain.ChatVisibility]).
 */
interface LocalModelService {

    /** ¿Hay un motor corriendo? */
    val running: Boolean

    /**
     * Arranca el motor local y devuelve el gateway para hablar con él.
     * [onStatus] reporta el progreso (descarga, carga del modelo…).
     */
    suspend fun start(
        settings: LocalModelSettings,
        onStatus: (String) -> Unit = {},
    ): LlmGateway

    /** Detiene el motor (mata el proceso hijo si lo hay). */
    fun stop()
}

/** Crea el servicio de modelo local de la plataforma. */
expect fun createLocalModelService(): LocalModelService
