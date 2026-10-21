package rag.retrieval

/**
 * Forma del material: decide la política de troceado.
 *
 * No es lo mismo trocear un PDF denso (párrafos largos, sin estructura) que un
 * apunte en Markdown (secciones, código) o una presentación (una unidad por
 * diapositiva). La forma decide el tamaño del fragmento.
 */
enum class MaterialShape(val label: String) {
    /** Documento o apunte en prosa: PDF, DOCX, TXT. */
    PROSE("Documento / apunte"),

    /** Markdown: tiene secciones y bloques de código. */
    MARKDOWN("Markdown"),

    /** Diapositivas: cada diapositiva es atómica. */
    SLIDES("Diapositivas"),

    /** Transcripción de un video. */
    TRANSCRIPT("Transcripción"),

    /** Tabla o CSV. */
    TABLE("Tabla"),
}

/**
 * Política de troceado de un material.
 *
 * Los tres números son el núcleo de la política:
 *
 *  - [targetChars]: tamaño **deseado**. El empaquetador cierra el fragmento al
 *    llegar a él.
 *  - [minChars]: tamaño **mínimo**. Evita fragmentos diminutos: uno más chico
 *    que el mínimo se fusiona con el anterior.
 *  - [maxChars]: tamaño **duro**. No se supera, salvo una unidad indivisible
 *    (un bloque de código o una palabra más larga que el máximo).
 *
 * El solapamiento es **por oración**: se arrastra la última oración completa
 * del fragmento anterior, con un tope ([maxOverlapChars]) y un piso
 * ([minOverlapChars]). Nunca parte una palabra.
 */
data class ChunkingStrategy(
    val targetChars: Int = PROSE_TARGET,
    val minChars: Int = PROSE_MIN,
    val maxChars: Int = PROSE_MAX,
    /** Oraciones de solapamiento entre fragmentos vecinos (0 = sin solapamiento). */
    val overlapSentences: Int = 1,
    /** Tope del solapamiento en caracteres. */
    val maxOverlapChars: Int = 180,
    /** Piso del solapamiento: si la cola es más corta, no hay solapamiento. */
    val minOverlapChars: Int = 20,
    /** Forma del material: de dónde salió la política. */
    val shape: MaterialShape = MaterialShape.PROSE,
) {
    init {
        require(targetChars > 0) { "El tamaño objetivo tiene que ser positivo (actual: $targetChars)." }
        require(minChars > 0) { "El tamaño mínimo tiene que ser positivo (actual: $minChars)." }
        require(maxChars >= targetChars) {
            "El tamaño máximo no puede ser menor que el objetivo (actual: $maxChars < $targetChars)."
        }
        require(overlapSentences >= 0) { "El solapamiento no puede ser negativo (actual: $overlapSentences)." }
        require(maxOverlapChars >= 0) { "El tope de solapamiento no puede ser negativo (actual: $maxOverlapChars)." }
        require(minOverlapChars >= 0) { "El piso de solapamiento no puede ser negativo (actual: $minOverlapChars)." }
        require(minOverlapChars <= maxOverlapChars) {
            "El piso de solapamiento no puede ser mayor que el tope (actual: $minOverlapChars > $maxOverlapChars)."
        }
    }

    companion object {
        /** Tamaño de fragmento por defecto de una cátedra. */
        const val DEFAULT_COURSE_CHUNK_SIZE = 1200

        /** PDF / prosa: objetivo, mínimo y máximo. */
        const val PROSE_TARGET = 600
        const val PROSE_MIN = 400
        const val PROSE_MAX = 800

        /** Markdown: objetivo, mínimo y máximo. */
        const val MARKDOWN_TARGET = 1200
        const val MARKDOWN_MIN = 600
        const val MARKDOWN_MAX = 1500

        /** Diapositivas: objetivo, mínimo y máximo. */
        const val SLIDES_TARGET = 500
        const val SLIDES_MIN = 350
        const val SLIDES_MAX = 650

        /**
         * Política efectiva según el tipo de material.
         *
         * [courseChunkSize] es el valor que eligió el docente: es el **tope**,
         * no el objetivo. Para el PDF denso el tope real es 600/800 porque un
         * fragmento de 1.200 caracteres diluye el contexto del modelo.
         */
        fun forShape(
            shape: MaterialShape,
            courseChunkSize: Int = DEFAULT_COURSE_CHUNK_SIZE,
        ): ChunkingStrategy {
            val objetivo = courseChunkSize.coerceIn(PROSE_MIN, 2400)
            return when (shape) {
                MaterialShape.PROSE -> ChunkingStrategy(
                    targetChars = minOf(objetivo, PROSE_TARGET),
                    minChars = minOf(objetivo, PROSE_MIN),
                    maxChars = minOf(objetivo, PROSE_MAX),
                    shape = shape,
                )
                MaterialShape.MARKDOWN -> ChunkingStrategy(
                    targetChars = objetivo,
                    minChars = objetivo / 2,
                    maxChars = (objetivo * 1.25).toInt(),
                    shape = shape,
                )
                MaterialShape.SLIDES -> ChunkingStrategy(
                    targetChars = minOf(objetivo, SLIDES_TARGET),
                    minChars = minOf(objetivo, SLIDES_MIN),
                    maxChars = minOf(objetivo, SLIDES_MAX),
                    overlapSentences = 0, // cada diapositiva es atómica
                    shape = shape,
                )
                MaterialShape.TRANSCRIPT -> ChunkingStrategy(
                    targetChars = minOf(objetivo, 700),
                    minChars = minOf(objetivo, 500),
                    maxChars = minOf(objetivo, 900),
                    shape = shape,
                )
                MaterialShape.TABLE -> ChunkingStrategy(
                    targetChars = objetivo,
                    minChars = objetivo / 2,
                    maxChars = (objetivo * 1.5).toInt(),
                    overlapSentences = 0,
                    shape = shape,
                )
            }
        }

        /**
         * Política según la extensión del material.
         *
         * Es el criterio que se usa cuando el material no trae su forma
         * declarada: un `.md` es Markdown y un `.pptx` son diapositivas.
         */
        fun forPath(
            path: String,
            courseChunkSize: Int = DEFAULT_COURSE_CHUNK_SIZE,
        ): ChunkingStrategy {
            val extension = path.substringAfterLast('.', "").lowercase()
            val shape = when (extension) {
                "md", "mdx", "markdown", "rst", "adoc" -> MaterialShape.MARKDOWN
                "ppt", "pptx", "odp", "key" -> MaterialShape.SLIDES
                "vtt", "srt" -> MaterialShape.TRANSCRIPT
                "csv", "tsv" -> MaterialShape.TABLE
                else -> MaterialShape.PROSE
            }
            return forShape(shape, courseChunkSize)
        }
    }
}
