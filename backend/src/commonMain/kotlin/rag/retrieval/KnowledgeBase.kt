package rag.retrieval

import kitoru.platform.ioDispatcher
import kotlinx.coroutines.withContext
import rag.config.RagConfig

/**
 * Índice de la cátedra: carga las fuentes, las trocea y las indexa.
 *
 * La diferencia con FunshiEngineAI es que **no hay fuente fija**: las
 * fuentes las fija la cátedra (sus materiales), y se pueden cambiar en
 * caliente cuando el docente sube material nuevo.
 */
class KnowledgeBase(private val cfg: RagConfig) {

    @Volatile
    private var index = Bm25Index(emptyList())

    /**
     * Fuentes activas. Se pueden cambiar en caliente: el docente sube un
     * material y se reindexa.
     */
    @Volatile
    var sources: List<DocumentSource> = emptyList()

    val chunkCount get() = index.size

    /** ¿Hay algo indexado? */
    val isEmpty get() = index.size == 0

    /**
     * Reindexa: carga las fuentes, trocea los documentos y construye el
     * índice. [onStatus] reporta el progreso.
     */
    suspend fun sync(onStatus: (String) -> Unit = {}) = withContext(ioDispatcher) {
        onStatus("Cargando materiales de la cátedra…")
        val docs = mutableListOf<Doc>()
        val warnings = mutableListOf<String>()
        for (source in sources) {
            runCatching { source.load() }
                .onSuccess { docs += it }
                .onFailure { warnings += "${source.label}: ${it.message}" }
        }

        onStatus("Indexando ${docs.size} documentos…")
        val chunks = docs.flatMap { Chunker.split(it, cfg.chunkSize, cfg.chunkOverlap) }
        index = Bm25Index(chunks)
        val warn = if (warnings.isEmpty()) "" else " · ⚠ ${warnings.joinToString("; ")}"
        onStatus("Índice listo: ${docs.size} docs / ${chunks.size} fragmentos$warn")
    }

    /** Recupera los fragmentos más relevantes para una consulta. */
    fun retrieve(query: String, k: Int = cfg.topK): List<Chunk> =
        index.search(query, k).map { it.first }

    /** Las fuentes que el modelo puede citar (para mostrarlas en la UI). */
    fun chunkCountForSource(source: String): Int = index.size
}
