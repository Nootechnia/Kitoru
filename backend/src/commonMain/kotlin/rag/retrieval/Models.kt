package rag.retrieval

import kitoru.domain.citationLabel as composeCitationLabel

/**
 * Documento que entra al índice.
 *
 * Los campos de metadatos son opcionales: un documento sin taxonomía (un PDF
 * suelto, un test) se indexa exactamente igual que antes.
 */
data class Doc(
    val source: String,
    val path: String,
    val text: String,
    /** Título legible del material ("" si el documento no viene de uno). */
    val title: String = "",
    /** Ids de las unidades del material (la primera es la primaria). */
    val unitIds: List<String> = emptyList(),
    /** Etiquetas de la ruta de unidades: ["Unidad 2", "Tema 2.1"]. */
    val unitPath: List<String> = emptyList(),
    /** Etiquetas libres del material ("examen", "parcial"...). */
    val tags: List<String> = emptyList(),
)

/**
 * Fragmento indexado.
 *
 * El constructor posicional `(source, path, heading, text)` no cambia: los
 * campos de taxonomía se agregaron al final y con valor por defecto, así que
 * el troceador y el índice que ya existían siguen funcionando igual.
 */
data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    /** Título legible del material ("" si el fragmento no viene de uno). */
    val title: String = "",
    /** Ids de las unidades del fragmento (la primera es la primaria). */
    val unitIds: List<String> = emptyList(),
    /** Etiquetas de la ruta de unidades: ["Unidad 2", "Tema 2.1"]. */
    val unitPath: List<String> = emptyList(),
    /** Etiquetas libres del material ("examen", "parcial"...). */
    val tags: List<String> = emptyList(),
) {
    /** Etiqueta corta y estable: "cátedra: Apuntes de regresión". */
    val label get() = "$source: $path"

    /**
     * Etiqueta de cita, con la jerarquía de la cátedra:
     * "Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal".
     *
     * La compone el dominio (`kitoru.domain.citationLabel`) para que la cita
     * sea la misma en el RAG, en la UI y en la analítica.
     */
    val citationLabel: String
        get() = composeCitationLabel(unitPath, title.ifBlank { path }, heading)
}
