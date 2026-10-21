package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Un fragmento (chunk) del corpus, visto desde el dominio.
 *
 * Se llama `CorpusChunk` y no `Chunk` a propósito: en `:backend` ya existe
 * `rag.retrieval.Chunk` y `:core` **no puede** depender de `:backend` (la
 * dependencia va al revés). Con dos clases chamadas `Chunk`, cualquier archivo
 * que importe `kitoru.domain.*` y `rag.retrieval.*` quedaría ambiguo.
 *
 * El adaptador de `:backend` (el que corre el `Chunker` real) convierte
 * `rag.retrieval.Chunk` → `kitoru.domain.CorpusChunk`.
 */
data class CorpusChunk(
    /** Material del que sale el fragmento. */
    val materialId: String = "",
    /** Encabezado del fragmento ("## Página 7" → "Página 7"). */
    val heading: String = "",
    /** Texto del fragmento (lo que ve el modelo). */
    val text: String = "",
) {
    /** Caracteres del fragmento. */
    val length: Int get() = text.length
}

/**
 * Qué tan grave es un problema del material.
 */
@Serializable
enum class CheckSeverity(val label: String) {
    /** Impide publicar el material. */
    BLOQUEANTE("Bloqueante"),

    /** Se puede publicar, pero hay que revisarlo. */
    ADVERTENCIA("Advertencia"),

    /** Informativo: no requiere acción. */
    INFO("Info"),
}

/**
 * Un check de calidad del corpus.
 *
 * El [message] es el texto **exacto** que ve el docente: no se arma en la
 * UI, para que el mensaje sea el mismo en todos los lugares donde aparece.
 */
@Serializable
data class CorpusCheck(
    /** Identificador estable ("VACIO", "DUPLICADO", …). */
    val id: String,
    /** Gravedad. */
    val severity: CheckSeverity,
    /** Título corto: "El texto está vacío". */
    val title: String,
    /** Mensaje completo, en español, con la acción a seguir. */
    val message: String,
    /** Acción sugerida: "Editar texto", "Revisar", "Ajustar fragmento". */
    val action: String = "",
    /** Cuántos casos hay (p. ej. 12 fragmentos diminutos). */
    val count: Int = 0,
    /**
     * Material al que aplica el check.
     *
     * Vacío = check de cátedra (no de un material en particular). Permite
     * partir un informe en los checks de cada fila del catálogo sin volver a
     * correr el análisis.
     */
    val materialId: String = "",
)

/**
 * Informe de calidad de un corpus (o de un material).
 */
@Serializable
data class CorpusQualityReport(
    /** Checks encontrados. */
    val checks: List<CorpusCheck> = emptyList(),
    /** Instante del análisis (epoch millis). */
    val checkedAt: Long = 0L,
) {
    /** Checks que impiden publicar. */
    val blocking: List<CorpusCheck> get() = checks.filter { it.severity == CheckSeverity.BLOQUEANTE }

    /** Checks que hay que revisar. */
    val warnings: List<CorpusCheck> get() = checks.filter { it.severity == CheckSeverity.ADVERTENCIA }

    /** Checks informativos. */
    val info: List<CorpusCheck> get() = checks.filter { it.severity == CheckSeverity.INFO }

    /** ¿Se puede publicar? */
    val publishable: Boolean get() = blocking.isEmpty()

    /**
     * Salud, de 0 a 100.
     *
     * 100 = sin observaciones. Cada bloqueante resta 30 y cada advertencia 8:
     * un material con texto vacío tiene que estar claramente en rojo, pero un
     * material con un solo aviso no puede hundir la salud del curso entero.
     */
    val healthScore: Int
        get() = (100 - blocking.size * 30 - warnings.size * 8).coerceIn(0, 100)

    /** ¿Hay algo que reportar? */
    val hasObservations: Boolean get() = checks.isNotEmpty()

    /** Los checks de un material concreto (para la fila del catálogo). */
    fun forMaterial(materialId: String): CorpusQualityReport =
        CorpusQualityReport(checks = checks.filter { it.materialId == materialId }, checkedAt = checkedAt)

    /** Los checks que no son de un material concreto. */
    val courseChecks: List<CorpusCheck> get() = checks.filter { it.materialId.isBlank() }
}

/**
 * Checks automáticos de calidad del corpus.
 *
 * **Deterministas y sin modelo**: los mismos datos dan el mismo informe en
 * cualquier máquina (regla 3 del proyecto). Es la respuesta al 80 % de las
 * quejas ("no encuentra lo que pregunto"): casi siempre es un problema del
 * material, no del modelo.
 *
 * Los checks implementados son los que el docente puede **arreglar**:
 * texto vacío (PDF escaneado), un único fragmento gigante, fragmentos
 * diminutos, falta de encabezados, duplicados y mezcla de idiomas. El diseño
 * (`docs/design/rag/09-corpus-gestion.md` §6.2) además plantea OCR, encoding
 * roto y páginas sin texto: son checks de la **misma forma**, se agregan
 * acá cuando el extractor exponga la información necesaria.
 */
object CorpusQualityChecker {

    /**
     * Umbral de fragmento "inservible" (menos de 200 caracteres).
     *
     * Un fragmento más chico que esto no le sirve al modelo: no tiene
     * contexto para responder.
     */
    const val MIN_USEFUL_CHARS = 200

    /**
     * Umbral de fragmento "gigante" (más de 2.000 caracteres).
     *
     * El modelo lo lee entero y la cita queda larguísima.
     */
    const val MAX_COMFORTABLE_CHARS = 2000

    /**
     * Proporción mínima de marcadores de otro idioma para avisar.
     *
     * Por debajo de esto es ruido: un texto puede tener un par de palabras en
     * inglés sin que el material esté "en inglés".
     */
    const val FOREIGN_LANGUAGE_RATIO = 0.15

    /**
     * Mínima cantidad de marcadores léxicos para analizar el idioma.
     *
     * Con menos muestras no se puede afirmar nada.
     */
    const val MIN_LANGUAGE_MARKERS = 20

    /**
     * Analiza un corpus y devuelve el informe de calidad.
     *
     * @param materials materiales de la cátedra (los que hay que revisar).
     * @param chunks fragmentos **reales** del índice (los que va a recuperar
     *   el modelo): sin ellos no se puede decir si el troceado sirve.
     * @param language idioma esperado del material de la cátedra.
     * @param checkedAt instante del análisis (epoch millis).
     */
    fun check(
        materials: List<Material>,
        chunks: List<CorpusChunk>,
        language: String = "es",
        checkedAt: Long = 0L,
    ): CorpusQualityReport {
        val byMaterial = chunks.groupBy { it.materialId }
        val checks = mutableListOf<CorpusCheck>()

        for (material in materials) {
            val own = byMaterial[material.id].orEmpty()
            checkEmpty(material)?.let { checks += it }
            checkSingleGiantChunk(material, own)?.let { checks += it }
            checkGiantChunks(material, own)?.let { checks += it }
            checkTinyChunks(material, own)?.let { checks += it }
            checkHeadings(material, own)?.let { checks += it }
            checkLanguage(material, language)?.let { checks += it }
        }
        checks += checkDuplicates(materials)

        return CorpusQualityReport(checks = checks, checkedAt = checkedAt)
    }

    /**
     * Texto extraído vacío: el archivo parece un escaneo.
     *
     * Es **bloqueante**: publicar un material sin texto es publicar algo que
     * la IA no puede leer, y el alumno va a preguntar "¿por qué no encuentra
     * lo que está en el PDF?".
     */
    private fun checkEmpty(material: Material): CorpusCheck? {
        if (material.extractedText.isNotBlank()) return null
        return CorpusCheck(
            id = "VACIO",
            severity = CheckSeverity.BLOQUEANTE,
            title = "El texto está vacío",
            message = "No se pudo extraer texto de «${material.title}»: el archivo parece un escaneo " +
                "y la IA no lo va a poder leer. Subilo con texto seleccionable o pegá el texto " +
                "en «Editar texto».",
            action = "Editar texto",
            materialId = material.id,
        )
    }

    /**
     * El material se indexó en un único fragmento gigante.
     *
     * Es el síntoma clásico de un PDF sin estructura: todo el texto entra en
     * un bloque y el modelo no puede recuperar nada fino.
     */
    private fun checkSingleGiantChunk(material: Material, chunks: List<CorpusChunk>): CorpusCheck? {
        if (chunks.size != 1) return null
        val text = chunks.first().text
        if (text.length < MAX_COMFORTABLE_CHARS) return null
        return CorpusCheck(
            id = "CHUNK_UNICO",
            severity = CheckSeverity.ADVERTENCIA,
            title = "Todo el material quedó en un solo fragmento",
            message = "«${material.title}» se indexó en 1 fragmento de ${text.length} caracteres: " +
                "conviene subirlo más troceado o como Markdown.",
            action = "Ajustar fragmento",
            count = 1,
            materialId = material.id,
        )
    }

    /**
     * Fragmentos que desbordan el contexto.
     */
    private fun checkGiantChunks(material: Material, chunks: List<CorpusChunk>): CorpusCheck? {
        if (chunks.size < 2) return null
        val giants = chunks.count { it.text.length > MAX_COMFORTABLE_CHARS }
        if (giants * 100 <= chunks.size * 10) return null
        return CorpusCheck(
            id = "CHUNK_GIGANTE",
            severity = CheckSeverity.ADVERTENCIA,
            title = "Fragmentos demasiado grandes",
            message = "«${material.title}» tiene $giants fragmentos de más de $MAX_COMFORTABLE_CHARS " +
                "caracteres: el modelo los lee enteros y las citas quedan larguísimas. " +
                "Probá bajar el tamaño de fragmento.",
            action = "Ajustar fragmento",
            count = giants,
            materialId = material.id,
        )
    }

    /**
     * Fragmentos diminutos: el troceado partió el material en pedazos
     * inservibles (típico de PDF con muchas marcas de página).
     */
    private fun checkTinyChunks(material: Material, chunks: List<CorpusChunk>): CorpusCheck? {
        if (chunks.isEmpty()) return null
        val tiny = chunks.count { it.text.length < MIN_USEFUL_CHARS }
        if (tiny == 0) return null
        return CorpusCheck(
            id = "CHUNK_DIMINUTO",
            severity = CheckSeverity.ADVERTENCIA,
            title = "Fragmentos demasiado chicos",
            message = "«${material.title}» se troceó en $tiny fragmentos de menos de $MIN_USEFUL_CHARS " +
                "caracteres: el modelo no puede usarlos. Probá subir el tamaño de fragmento o " +
                "subir el material como Markdown con menos encabezados.",
            action = "Ajustar fragmento",
            count = tiny,
            materialId = material.id,
        )
    }

    /**
     * Sin encabezados: todos los fragmentos quedaron con el mismo título.
     *
     * Sin encabezados las citas dicen sólo "cátedra: Apuntes-IA" y el alumno
     * no sabe qué parte del material respaldó la respuesta.
     */
    private fun checkHeadings(material: Material, chunks: List<CorpusChunk>): CorpusCheck? {
        if (chunks.size < 2) return null
        val first = chunks.first().heading.trim()
        if (chunks.any { it.heading.trim() != first }) return null
        val where = if (first.isEmpty()) {
            "sin título"
        } else {
            "con el mismo título («$first»)"
        }
        return CorpusCheck(
            id = "SIN_ENCABEZADOS",
            severity = CheckSeverity.ADVERTENCIA,
            title = "El material no tiene encabezados",
            message = "«${material.title}» no tiene encabezados: los ${chunks.size} fragmentos " +
                "quedaron $where y las citas van a decir sólo «cátedra: ${material.title}».",
            action = "Revisar",
            count = chunks.size,
            materialId = material.id,
        )
    }

    /**
     * Duplicados: dos materiales con el mismo contenido normalizado.
     *
     * "El mismo PDF subido dos veces" es el error más común y el más caro:
     * infla el catálogo y hace que el modelo cite dos veces lo mismo.
     */
    private fun checkDuplicates(materials: List<Material>): List<CorpusCheck> {
        val out = mutableListOf<CorpusCheck>()
        val seen = mutableMapOf<String, Material>()
        for (material in materials) {
            if (material.extractedText.isBlank()) continue
            val hash = ContentHasher.normalizedHash(material.extractedText)
            val other = seen[hash]
            if (other == null) {
                seen[hash] = material
                continue
            }
            out += CorpusCheck(
                id = "DUPLICADO",
                severity = CheckSeverity.ADVERTENCIA,
                title = "El material ya estaba en la cátedra",
                message = "«${material.title}» tiene el mismo contenido que «${other.title}»: " +
                    "no lo agregues dos veces. Elegí uno y ocultá el otro.",
                action = "Ver el otro",
                count = 1,
                materialId = material.id,
            )
        }
        return out
    }

    /**
     * Mezcla de idiomas.
     *
     * No es un detector de idioma académico: es un **contador de marcadores
     * léxicos**, determinista y sin modelo. La pregunta que importa es
     * "¿este material está en el idioma de la cátedra?".
     */
    private fun checkLanguage(material: Material, expected: String): CorpusCheck? {
        val text = material.extractedText
        if (text.isBlank()) return null
        val signal = detectLanguage(text)
        if (signal.markers < MIN_LANGUAGE_MARKERS) return null
        if (signal.language == expected) return null
        if (signal.dominantShare < FOREIGN_LANGUAGE_RATIO) return null
        val pct = (signal.dominantShare * 100).toInt()
        return CorpusCheck(
            id = "IDIOMA_INCONSISTENTE",
            severity = CheckSeverity.ADVERTENCIA,
            title = "Idioma distinto al de la cátedra",
            message = "El $pct % del texto de «${material.title}» está en ${nombreIdioma(signal.language)} " +
                "y el material de la cátedra está en ${nombreIdioma(expected)}: las citas van a " +
                "mezclar idiomas.",
            action = "Revisar",
            count = pct,
            materialId = material.id,
        )
    }

    /**
     * Nombre del idioma en español, para los mensajes.
     *
     * El mensaje va a un docente: "está en en" no es un mensaje, es un
     * código de(programación).
     */
    private fun nombreIdioma(codigo: String): String = when (codigo.lowercase()) {
        "es" -> "español"
        "en" -> "inglés"
        "pt" -> "portugués"
        else -> codigo
    }

    /** Señal de idioma de un texto. */
    private data class LanguageSignal(
        /** Idioma dominante. */
        val language: String,
        /** Marcadores del idioma dominante. */
        val markers: Int,
        /**
         * Proporción del texto que está en el idioma dominante (0..1).
         *
         * Si el idioma dominante es el que **no** es el de la cátedra, esta
         * proporción es la del idioma ":ruso" en el texto.
         */
        val dominantShare: Double,
    )

    /**
     * Marcadores léxicos por idioma.
     *
     * Se eligieron palabras que no se solapan entre idiomas y que son
     * frecuentes en material de cátedra.
     */
    private val MARKERS: Map<String, Set<String>> = mapOf(
        "es" to setOf(
            "los", "las", "una", "del", "para", "como", "este", "esta", "sus", "entre",
            "también", "pero", "donde", "cuál", "que", "con", "por", "más", "curso",
            "unidad", "tema", "ejemplo", "teoría", "práctica", "clase", "semana", "año",
        ),
        "en" to setOf(
            "the", "and", "of", "to", "in", "is", "that", "this", "with", "for",
            "are", "was", "were", "which", "from", "have", "been", "not", "but", "they",
            "their", "example", "chapter", "course", "notes", "lecture", "week", "student",
        ),
        "pt" to setOf(
            "não", "uma", "com", "você", "mais", "quando", "sobre", "deve", "pelo", "pela",
            "seu", "sua", "para", "que", "como", "aula", "semana", "exemplo", "teoria",
        ),
    )

    /**
     * Detecta el idioma dominante de un texto (determinista).
     */
    private fun detectLanguage(text: String): LanguageSignal {
        val tokens = text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return LanguageSignal("es", 0, 0.0)
        val counts = MARKERS.mapValues { (_, set) -> tokens.count { it in set } }
        val dominant = counts.maxByOrNull { it.value } ?: return LanguageSignal("es", 0, 0.0)
        if (dominant.value == 0) return LanguageSignal("es", 0, 0.0)
        val total = counts.values.sum()
        return LanguageSignal(
            language = dominant.key,
            markers = dominant.value,
            dominantShare = dominant.value.toDouble() / total,
        )
    }
}

/**
 * Estadísticas de uso de un material.
 *
 * Se derivan de la analítica del chat; nunca del modelo. Son **agregados**:
 * el docente ve cuántas veces se usó el material, no qué dijo cada alumno.
 */
@Serializable
data class MaterialUsage(
    /** Veces que el material fue recuperado como fuente. */
    val citations: Int = 0,
    /** Respuestas del modelo en las que se usó este material. */
    val answers: Int = 0,
    /** Alumnos distintos que interactuaron con el material. */
    val students: Int = 0,
    /**
     * Respuestas que citaron este material y además tenían alguna cita sin
     * respaldo.
     *
     * El verificador de citas clasifica **por respuesta**, no por fuente: no
     * se puede atribuir una cita sin respaldo a un material concreto. Por eso
     * el número es "respuestas sin respaldo que citan este material".
     */
    val unsupportedAnswers: Int = 0,
    /** Aperturas del material en la vista del alumno (0 si no hay eventos). */
    val opens: Int = 0,
    /** Búsquedas dentro del material (0 si no hay eventos). */
    val searches: Int = 0,
    /** Última actividad (epoch millis; 0 = nunca). */
    val lastActivityAt: Long = 0L,
) {
    /** Tasa de respuestas sin respaldo, en puntos básicos (0..10000). */
    val unsupportedRate: Int
        get() = if (answers <= 0) 0 else (unsupportedAnswers * 10_000) / answers

    /** ¿Lo usó alguien? */
    val isUsed: Boolean get() = citations > 0 || answers > 0 || opens > 0 || searches > 0
}

/**
 * Una fila del catálogo del corpus.
 *
 * Es una **vista**: no se persiste. Se arma con el material, su versión
 * vigente, el informe de calidad y las estadísticas de uso.
 */
@Serializable
data class CorpusEntry(
    val materialId: String,
    val title: String = "",
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    /** ¿Lo ven los alumnos? */
    val visible: Boolean = true,
    /** Versión del material (sube cuando cambia el texto). */
    val version: Int = 0,
    /** Hash del texto (para comparar con el manifiesto). */
    val sha256: String = "",
    /** Tamaño del texto extraído, en bytes. */
    val textBytes: Int = 0,
    /** Unidades de la taxonomía. */
    val unitIds: List<String> = emptyList(),
    /** Etiquetas libres. */
    val tags: List<String> = emptyList(),
    /** Última modificación. */
    val updatedAt: Long = 0L,
    /** Fragmentos que aporta al índice. */
    val chunkCount: Int = 0,
    /** Informe de calidad (sólo los checks de este material). */
    val quality: CorpusQualityReport = CorpusQualityReport(),
    /** Estadísticas de uso. */
    val usage: MaterialUsage = MaterialUsage(),
) {
    /** La salud, en un número 0..100 (para la barrita). */
    val health: Int get() = quality.healthScore

    /** ¿Necesita una acción del docente? */
    val needsAttention: Boolean
        get() = quality.blocking.isNotEmpty() || quality.warnings.isNotEmpty()
}

/**
 * Totales de la cabecera del catálogo.
 */
@Serializable
data class CatalogSummary(
    /** Materiales de la cátedra. */
    val materials: Int = 0,
    /** Materiales visibles para los alumnos. */
    val published: Int = 0,
    /** Materiales ocultos. */
    val hidden: Int = 0,
    /** Fragmentos que aporta el corpus. */
    val chunks: Int = 0,
    /** Caracteres de texto del corpus. */
    val chars: Long = 0L,
    /** Materiales con un check bloqueante. */
    val withBlocking: Int = 0,
    /** Materiales con advertencias. */
    val withWarnings: Int = 0,
    /** Materiales que nadie usó. */
    val unused: Int = 0,
)

/**
 * El catálogo del corpus: la pantalla principal del docente en materiales.
 *
 * No es un gestor de archivos: es un producto con salud y con uso.
 */
@Serializable
data class CorpusCatalog(
    val courseId: String,
    /** Una fila por material. */
    val entries: List<CorpusEntry> = emptyList(),
    /** Totales para la cabecera. */
    val summary: CatalogSummary = CatalogSummary(),
    /** Versión del manifiesto que refleja el catálogo. */
    val manifestVersion: Long = 0L,
) {
    /**
     * Salud del corpus, de 0 a 100.
     *
     * Es el **promedio** de la salud de sus materiales: un corpus con diez
     * materiales sanos y uno roto es un corpus sano con un material roto, no
     * un corpus a medias. Los materiales con problema se señalan en su fila.
     */
    val health: Int
        get() = if (entries.isEmpty()) 0 else entries.sumOf { it.health } / entries.size

    /** Materiales que necesitan una acción del docente. */
    val needsAttention: Int get() = entries.count { it.needsAttention }
}

/**
 * Arma el catálogo del corpus de una cátedra.
 *
 * Es una **función pura**: los mismos datos dan el mismo catálogo. Todo lo
 * que no se puede calcular con los datos disponibles queda en 0 y está
 * documentado (por ejemplo, las aperturas y búsquedas: el modelo de datos
 * todavía no tiene eventos de corpus).
 */
object CorpusCatalogBuilder {

    /**
     * @param courseId cátedra del catálogo.
     * @param materials materiales de la cátedra.
     * @param manifest manifiesto publicado (opcional: aporta la versión).
     * @param quality informe de calidad (el que devuelve [CorpusQualityChecker]).
     * @param chunkCountByMaterial fragmentos por material.
     * @param messages mensajes de la cátedra (para las estadísticas de uso).
     * @param threads hilos de la cátedra (para saber el autor de cada mensaje).
     */
    fun build(
        courseId: String,
        materials: List<Material>,
        manifest: CorpusManifest? = null,
        quality: CorpusQualityReport = CorpusQualityReport(),
        chunkCountByMaterial: Map<String, Int> = emptyMap(),
        messages: List<ChatMessage> = emptyList(),
        threads: List<ChatThread> = emptyList(),
    ): CorpusCatalog {
        val usage = usageByMaterial(materials, messages, threads)
        val entries = materials.map { material ->
            val entry = manifest?.entryOf(material.id)
            CorpusEntry(
                materialId = material.id,
                title = material.title,
                kind = material.kind,
                visible = material.visible,
                version = entry?.version ?: 0,
                sha256 = entry?.sha256 ?: ContentHasher.exact(material.extractedText),
                textBytes = entry?.textBytes ?: material.extractedText.encodeToByteArray().size,
                unitIds = entry?.unitIds ?: emptyList(),
                tags = material.tags,
                updatedAt = entry?.updatedAt ?: material.uploadedAt,
                chunkCount = chunkCountByMaterial[material.id] ?: 0,
                quality = quality.forMaterial(material.id),
                usage = usage[material.id] ?: MaterialUsage(),
            )
        }
        val summary = CatalogSummary(
            materials = entries.size,
            published = entries.count { it.visible },
            hidden = entries.count { !it.visible },
            chunks = entries.sumOf { it.chunkCount },
            chars = entries.sumOf { it.textBytes.toLong() },
            withBlocking = entries.count { it.quality.blocking.isNotEmpty() },
            withWarnings = entries.count { it.quality.warnings.isNotEmpty() },
            unused = entries.count { !it.usage.isUsed },
        )
        return CorpusCatalog(
            courseId = courseId,
            entries = entries,
            summary = summary,
            manifestVersion = manifest?.version ?: 0L,
        )
    }

    /**
     * Estadísticas de uso por material, a partir de los mensajes.
     *
     * Los `sources` de un mensaje son etiquetas ("cátedra: Apuntes-IA"), así
     * que el cruce con el material es por título o por ruta del archivo. Es
     * una heurística honesta: si el docente renombra el archivo, las citas
     * viejas dejan de cruzarse (por eso el material guarda su título en el
     * manifiesto y las citas nuevas lo usan).
     */
    private fun usageByMaterial(
        materials: List<Material>,
        messages: List<ChatMessage>,
        threads: List<ChatThread>,
    ): Map<String, MaterialUsage> {
        if (materials.isEmpty() || messages.isEmpty()) return emptyMap()
        val authorByThread = threads.associate { it.id to it.authorId }
        val citations = mutableMapOf<String, Int>()
        val answers = mutableMapOf<String, Int>()
        val students = mutableMapOf<String, MutableSet<String>>()
        val unsupported = mutableMapOf<String, Int>()
        val lastActivity = mutableMapOf<String, Long>()

        for (message in messages) {
            if (!message.isAssistant) continue
            for (label in message.sources) {
                for (material in materials) {
                    if (!matches(label, material)) continue
                    citations[material.id] = (citations[material.id] ?: 0) + 1
                    lastActivity[material.id] = maxOf(lastActivity[material.id] ?: 0L, message.createdAt)
                }
            }
            if (message.sources.isEmpty()) continue
            val cited = materials.filter { material -> message.sources.any { matches(it, material) } }
            if (cited.isEmpty()) continue
            val author = authorByThread[message.threadId] ?: message.threadId
            val hasUnsupported = (message.citation?.unsupported ?: 0) > 0
            for (material in cited) {
                answers[material.id] = (answers[material.id] ?: 0) + 1
                students.getOrPut(material.id) { mutableSetOf() } += author
                if (hasUnsupported) {
                    unsupported[material.id] = (unsupported[material.id] ?: 0) + 1
                }
            }
        }

        return materials.associate { material ->
            material.id to MaterialUsage(
                citations = citations[material.id] ?: 0,
                answers = answers[material.id] ?: 0,
                students = students[material.id]?.size ?: 0,
                unsupportedAnswers = unsupported[material.id] ?: 0,
                // El modelo de datos todavía no tiene eventos de corpus
                // (aperturas y búsquedas): quedan en 0 en vez de inventar.
                opens = 0,
                searches = 0,
                lastActivityAt = lastActivity[material.id] ?: 0L,
            )
        }
    }

    /** ¿La etiqueta de una fuente corresponde a este material? */
    private fun matches(label: String, material: Material): Boolean {
        val tail = label.substringAfterLast(':').trim()
        if (tail.isEmpty()) return false
        val title = material.title.trim()
        val path = material.path.trim()
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').trim()
        return (title.isNotEmpty() && tail.equals(title, ignoreCase = true)) ||
            (path.isNotEmpty() && tail.equals(path, ignoreCase = true)) ||
            (fileName.isNotEmpty() && tail.equals(fileName, ignoreCase = true))
    }
}
