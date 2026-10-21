package rag.retrieval

import kitoru.domain.Material

/**
 * Fuente de documentos para el índice.
 *
 * En FunshiEngineAI las fuentes eran repos de GitHub y carpetas del disco.
 * En Kitoru la fuente es **la cátedra**: los materiales que subió el
 * docente. La interfaz deja el RAG abierto a otras fuentes (URLs, un servidor
 * remoto, un libro) sin tocar el motor de recuperación.
 */
interface DocumentSource {
    /** Etiqueta de la fuente (aparece en las citas: "corpus:/apuntes.md"). */
    val label: String

    /** Carga los documentos a indexar. */
    suspend fun load(): List<Doc>
}

/**
 * Fuente que toma los materiales de una cátedra.
 *
 * El texto ya viene extraído en [Material.extractedText] (lo hizo la app al
 * subir el archivo), así que la carga es inmediata y no toca disco.
 */
class CourseMaterialSource(
    private val materials: List<Material>,
    private val corpusLabel: String = "cátedra",
) : DocumentSource {

    override val label: String get() = corpusLabel

    override suspend fun load(): List<Doc> = materials
        .filter { it.visible && it.extractedText.isNotBlank() }
        .map { material ->
            Doc(
                source = corpusLabel,
                path = material.title.ifBlank { material.path },
                text = material.extractedText,
            )
        }
}

/** Fuente en memoria: útil para tests y para previsualizar un material. */
class InMemorySource(
    override val label: String,
    private val docs: List<Doc>,
) : DocumentSource {
    override suspend fun load(): List<Doc> = docs
}
