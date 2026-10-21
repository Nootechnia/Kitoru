package llm.api

import java.io.File
import java.util.concurrent.TimeUnit
import kitoru.platform.ioDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import llm.config.LocalModelSettings
import llm.error.LlmErrorCode
import llm.error.LlmException

/**
 * Modelo local en el escritorio: levanta `llama-server` de llama.cpp como
 * proceso hijo y devuelve un gateway HTTP apuntando a él.
 *
 * Es la contrapartida del modo REMOTE: el alumno puede chatear con su propio
 * modelo aunque el servidor de la cátedra esté caído (y a cambio, el docente
 * no ve esos chats).
 *
 * A diferencia del gestor de modelos de FunshiEngineAI, este servicio **no**
 * clona ni compila llama.cpp ni descarga el GGUF: espera que el binario y el
 * modelo ya estén en la máquina (o que el alumno los haya descargado con la
 * herramienta de modelos). Es el alcance del primer hito.
 */
class LlamaServerLocalModelService : LocalModelService {

    private var process: Process? = null

    override val running: Boolean get() = process?.isAlive == true

    override suspend fun start(
        settings: LocalModelSettings,
        onStatus: (String) -> Unit,
    ): LlmGateway {
        stop()
        val binary = findBinary(settings.llamaServerPath)
            ?: throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "No se encontró llama-server. Indicá la ruta en el modelo local " +
                    "(o dejá el binario en el PATH).",
            )
        val modelFile = findModel(settings)
            ?: throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "No se encontró el modelo ${settings.hfFile}. " +
                    "Descargalo o indicá la ruta del archivo .gguf.",
            )

        onStatus("Arrancando llama-server en el puerto ${settings.localPort}…")
        val args = buildList {
            add("-m"); add(modelFile.absolutePath)
            add("--port"); add(settings.localPort.toString())
            add("-c"); add(settings.contextSize.toString())
            add("-ngl"); add(settings.gpuLayers.toString())
            add("--host"); add("127.0.0.1")
        }

        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(binary.absolutePath, *args.toTypedArray())
                .redirectErrorStream(true)
                .start()
        }
        process = proc

        // Espera a que el servidor esté sano.
        val baseUrl = "http://127.0.0.1:${settings.localPort}/v1"
        val gateway = OpenAiCompatibleGateway(
            baseUrl = baseUrl,
            apiKey = "",
            model = settings.hfFile.substringBeforeLast('.'),
            temperature = 0.2,
            maxTokens = 1024,
            chatTimeoutMs = 0,
        )
        withContext(ioDispatcher) {
            var listo = false
            repeat(120) {
                if (!proc.isAlive) return@withContext
                val ok = runCatching { gateway.listModels() }.isSuccess
                if (ok) {
                    listo = true
                    return@withContext
                }
                delay(500)
            }
            if (!listo) {
                stop()
                throw LlmException(
                    LlmErrorCode.SERVIDOR_CAIDO,
                    "llama-server no respondió a tiempo. Revisá el log del proceso.",
                    retryable = true,
                )
            }
        }
        onStatus("Modelo local listo en $baseUrl")
        return gateway
    }

    override fun stop() {
        val proc = process ?: return
        process = null
        runCatching { proc.destroy() }
        runCatching { if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly() }
    }

    /** Busca el binario: ruta configurada, o `llama-server` en el PATH. */
    private fun findBinary(configured: String): File? {
        if (configured.isNotBlank()) {
            val f = File(configured)
            return if (f.isFile) f else null
        }
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "llama-server.exe" else "llama-server"
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        for (dir in path) {
            if (dir.isBlank()) continue
            val candidate = File(dir, exe)
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** Busca el .gguf en la carpeta de modelos configurada. */
    private fun findModel(settings: LocalModelSettings): File? {
        val dir = File(settings.modelsDir)
        if (!dir.isDirectory) return null
        val target = settings.hfFile
        val direct = File(dir, target)
        if (direct.isFile) return direct
        // Busca por nombre parcial (el archivo puede estar en una subcarpeta).
        return dir.walkTopDown()
            .filter { it.isFile && it.name.equals(target, ignoreCase = true) }
            .firstOrNull()
            ?: dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                .firstOrNull()
    }
}

/** Servicio de modelo local del escritorio. */
actual fun createLocalModelService(): LocalModelService = LlamaServerLocalModelService()
