package rag.config

/**
 * Parámetros del RAG de una cátedra.
 *
 * En FunshiEngineAI esta configuración era global y leía de
 * `app-config.json`. En Kitoru es **por cátedra**: el docente la ajusta desde
 * la pantalla de diseño y el alumno no la toca.
 */
data class RagConfig(
    /** Tamaño (en caracteres) de cada fragmento indexado. */
    val chunkSize: Int = 1200,
    /** Solapamiento entre fragmentos, para no cortar ideas por la mitad. */
    val chunkOverlap: Int = 150,
    /** Cuántos fragmentos se recuperan por pregunta. */
    val topK: Int = 5,
    /** Cuántas rondas de historial se mandan al modelo. */
    val historyTurns: Int = 6,
    /** Extensiones que se indexan de los materiales subidos. */
    val extensions: List<String> = listOf("md", "mdx", "txt", "rst", "adoc", "pdf", "csv"),
)
