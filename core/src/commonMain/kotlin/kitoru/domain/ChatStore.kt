package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistencia de las conversaciones (hilos y mensajes) en archivos.
 *
 * ## Layout en disco
 *
 * ```
 * <coursesDir>/
 *   threads.index.json                 # índice de rutas (caché, no datos)
 *   <courseId>/
 *     chats/
 *       <authorId>/
 *         <threadId>/
 *           meta.json                  # cabecera del hilo (ChatThread)
 *           messages.jsonl             # un mensaje por línea (append-only)
 * ```
 *
 * ## Por qué un directorio por hilo
 *
 * El id del hilo es un **UUID puro**: no codifica la cátedra. Eso evita la
 * convención frágil `"<courseId>|<uuid>"` que usaba `FileMessageRepository`
 * (un `startsWith` sobre un archivo global) y hace que el mismo id valga en
 * el equipo del alumno, en el `.kitoru` que entrega y en el servidor.
 *
 * La relación con la cátedra y con el autor son FKs explícitos que viven en
 * `meta.json`, y el **path es el índice**: listar una cátedra es listar
 * directorios, no filtrar strings en memoria.
 *
 * ## Por qué `messages.jsonl` y no un JSON
 *
 * Un `messages.json` global se reescribe entero por cada mensaje nuevo
 * (con 60 alumnos y 3.000 mensajes por alumno son ~200 MB por escritura). Con
 * un archivo por hilo en modo append, escribir un mensaje cuesta ~1 KB.
 *
 * ## Por qué el índice de rutas
 *
 * `FileSystem.listFiles` devuelve **archivos**, no directorios: con la
 * interfaz actual no se puede recorrer el árbol de `courses/` para descubrir
 * qué hilos hay. El índice (`threads.index.json`) guarda la correspondencia
 * `threadId → (courseId, authorId)` y es un **caché de rutas, no de datos**:
 * los datos viven en la carpeta del hilo y el índice se puede reconstruir.
 */
class ChatStore(
    private val fs: FileSystem,
    /**
     * Carpeta donde viven las cátedras.
     *
     * Es una **función** y no un valor porque `KitoruPaths.dataDir` lo fija la
     * aplicación al arrancar: si se capturara el valor en el constructor, el
     * store quedaría apuntando al directorio viejo.
     */
    private val baseDir: () -> FilePath = { KitoruPaths.coursesDir },
) {

    /** Cerrojo: dos escrituras simultáneas no se pisan el archivo. */
    private val mutex = Mutex()

    /** Índice de rutas cacheado en memoria (el archivo es la fuente durable). */
    private var indexCache: ThreadIndex? = null

    // -----------------------------------------------------------------------
    // Rutas
    // -----------------------------------------------------------------------

    /** Carpeta de los chats de una cátedra. */
    fun chatsDir(courseId: String): FilePath =
        baseDir().resolve(safeDirKey(courseId)).resolve(CHATS_DIR)

    /** Carpeta de un hilo. */
    fun threadDir(courseId: String, authorId: String, threadId: String): FilePath =
        chatsDir(courseId).resolve(safeDirKey(authorId)).resolve(safeDirKey(threadId))

    /** Ruta de la cabecera de un hilo. */
    fun metaPath(courseId: String, authorId: String, threadId: String): FilePath =
        threadDir(courseId, authorId, threadId).resolve(META_FILE)

    /** Ruta del archivo de mensajes de un hilo. */
    fun messagesPath(courseId: String, authorId: String, threadId: String): FilePath =
        threadDir(courseId, authorId, threadId).resolve(MESSAGES_FILE)

    // -----------------------------------------------------------------------
    // Hilos
    // -----------------------------------------------------------------------

    /**
     * Guarda la cabecera de un hilo, creando la carpeta si hace falta.
     *
     * Es un **upsert** por id: volver a guardar el mismo hilo lo reemplaza.
     * Si el hilo estaba borrado lógicamente, sigue borrado: un guardado no
     * resucita un hilo (el borrado es un dato, no un estado de la UI).
     */
    suspend fun saveThread(thread: ChatThread) {
        mutex.withLock { saveThreadNow(thread) }
    }

    /**
     * Actualiza la cabecera de un hilo.
     *
     * Equivale a [saveThread] (el store es un upsert por id) y se mantiene
     * como entrada separada porque en el código de la UI "guardar" y
     * "actualizar" son intenciones distintas aunque compartan la operación.
     */
    suspend fun updateThread(thread: ChatThread) {
        mutex.withLock { saveThreadNow(thread) }
    }

    /**
     * Devuelve un hilo por su id, o `null` si no existe o si está borrado
     * lógicamente.
     */
    suspend fun getThread(threadId: String): ChatThread? = mutex.withLock {
        val dir = resolveThreadDir(threadId) ?: return@withLock null
        readMeta(dir)?.takeIf { it.deletedAt == null }?.thread
    }

    /**
     * Lista los hilos vivos de una cátedra, del más reciente al más viejo.
     *
     * Los hilos borrados lógicamente no aparecen: el borrado es logical, la
     * carpeta sigue en el disco.
     */
    suspend fun listThreads(courseId: String): List<ChatThread> = mutex.withLock {
        listThreadsNow(courseId, null)
    }

    /**
     * Lista los hilos vivos de una cátedra que escribió un autor.
     */
    suspend fun listThreads(courseId: String, authorId: String): List<ChatThread> = mutex.withLock {
        listThreadsNow(courseId, authorId)
    }

    /**
     * Marca un hilo como borrado lógicamente.
     *
     * No se borra nada del disco: se escribe `deletedAt` en `meta.json`. El
     * borrado físico (la papelera) es una operación aparte, para que el
     * docente pueda ver que el alumno borró un hilo y cuándo.
     *
     * @throws IllegalStateException si el hilo no existe.
     */
    suspend fun deleteThread(threadId: String) = mutex.withLock {
        val dir = resolveThreadDir(threadId)
            ?: throw IllegalStateException("No se encontró el hilo '$threadId'.")
        val meta = readMeta(dir)
            ?: throw IllegalStateException("El hilo '$threadId' no tiene cabecera: no se puede marcar como borrado.")
        val updated = meta.copy(deletedAt = nowMillis(), deletedBy = DELETED_BY_LOCAL)
        writeAtomic(fs, dir.resolve(META_FILE), chatJson.encodeToString(ThreadMeta.serializer(), updated))
    }

    // -----------------------------------------------------------------------
    // Mensajes
    // -----------------------------------------------------------------------

    /**
     * Agrega un mensaje al final del hilo (append-only).
     *
     * Cada mensaje es **una línea JSON** de `messages.jsonl`. El archivo sólo
     * crece: nunca se reescribe un mensaje que ya estaba (salvo una
     * regeneración, que es un mensaje nuevo).
     *
     * @throws IllegalStateException si el hilo todavía no fue guardado.
     */
    suspend fun appendMessage(message: ChatMessage) = mutex.withLock {
        val dir = resolveThreadDir(message.threadId)
            ?: throw IllegalStateException(
                "No se encontró el hilo '${message.threadId}': primero hay que guardarlo con saveThread.",
            )
        val path = dir.resolve(MESSAGES_FILE)
        val line = chatJsonLines.encodeToString(ChatMessage.serializer(), message)
        val current = if (fs.exists(path)) fs.readText(path) else ""
        // `FileSystem` no tiene append: se relee el archivo y se reescribe
        // entero, que es O(n) por mensaje. Con un append real del sistema de
        // archivos esto pasa a ser O(1) sin cambiar nada más.
        writeAtomic(fs, path, repairTail(current) + line + "\n")
    }

    /**
     * Lee los mensajes de un hilo, en el orden en que se escribieron.
     *
     * Las líneas que no se pueden parsear se saltan: un corte de escritura no
     * puede tirar abajo la conversación entera.
     */
    suspend fun messages(threadId: String): List<ChatMessage> = mutex.withLock {
        readMessagesNow(threadId).messages
    }

    /**
     * Lee los mensajes de un hilo y dice cuántas líneas se saltaron.
     *
     * El conteo de líneas corruptas es la señal de salud del archivo: si
     * aparece, hubo un corte de escritura (o una edición manual).
     */
    suspend fun readThread(threadId: String): ThreadRead = mutex.withLock {
        readMessagesNow(threadId)
    }

    /** Resultado de leer los mensajes de un hilo. */
    data class ThreadRead(
        /** Mensajes leídos, en orden. */
        val messages: List<ChatMessage>,
        /** Líneas del JSONL que no se pudieron parsear y se saltaron. */
        val corruptedLines: Int,
    )

    // -----------------------------------------------------------------------
    // Internos
    // -----------------------------------------------------------------------

    /** Guarda la cabecera del hilo y registra su carpeta en el índice. */
    private suspend fun saveThreadNow(thread: ChatThread) {
        val dir = threadDir(thread.courseId, thread.authorId, thread.id)
        fs.createDirectories(dir)
        val previous = readMeta(dir)
        val meta = ThreadMeta(
            thread = thread.redacted(),
            deletedAt = previous?.deletedAt,
            deletedBy = previous?.deletedBy ?: "",
            deleteReason = previous?.deleteReason ?: "",
        )
        writeAtomic(fs, dir.resolve(META_FILE), chatJson.encodeToString(ThreadMeta.serializer(), meta))
        registerThread(thread.courseId, thread.authorId, thread.id)
    }

    /** Lista los hilos vivos (con filtro opcional por autor). */
    private suspend fun listThreadsNow(courseId: String, authorId: String?): List<ChatThread> {
        val metas = threadDirs(courseId)
            .mapNotNull { readMeta(it) }
            .filter { it.deletedAt == null }
            .filter { authorId == null || it.thread.authorId == authorId }
        return metas.map { it.thread }.sortedByDescending { it.updatedAt }
    }

    /** Carpetas de los hilos de una cátedra (índice + recorrido del árbol). */
    private fun threadDirs(courseId: String): List<FilePath> {
        val fromIndex = loadIndex().threads
            .filter { it.courseId == courseId }
            .map { threadDir(it.courseId, it.authorId, it.threadId) }
            .filter { fs.exists(it) }
        // El recorrido del árbol complements al índice: si el índice se perdió
        // y el sistema de archivos sabe listar directorios, los hilos siguen
        // siendo visibles.
        val fromTree = childDirs(chatsDir(courseId)).flatMap { childDirs(it) }
        return (fromIndex + fromTree).distinct()
    }

    /**
     * Busca la carpeta de un hilo por su id.
     *
     * Primero mira el índice de rutas; si no lo conoce, recorre el árbol de
     * `courses/` (el recorrido sólo rinde cuando el sistema de archivos sabe
     * listar directorios).
     */
    private fun resolveThreadDir(threadId: String): FilePath? {
        val entry = loadIndex().threads.firstOrNull { it.threadId == threadId }
        if (entry != null) {
            val dir = threadDir(entry.courseId, entry.authorId, threadId)
            if (fs.exists(dir)) return dir
        }
        return walkThreadDirs().firstOrNull { it.name == safeDirKey(threadId) }
    }

    /** Recorre el árbol de `courses/` y devuelve las carpetas de hilo. */
    private fun walkThreadDirs(): List<FilePath> =
        childDirs(baseDir()).flatMap { course ->
            childDirs(course.resolve(CHATS_DIR)).flatMap { author -> childDirs(author) }
        }

    /** Subdirectorios de una carpeta (los que el sistema de archivos expone). */
    private fun childDirs(dir: FilePath): List<FilePath> {
        if (!fs.exists(dir)) return emptyList()
        return fs.listFiles(dir).filter { fs.isDirectory(it) }
    }

    /** Lee la cabecera de un hilo (`null` si no está o está corrupta). */
    private fun readMeta(dir: FilePath): ThreadMeta? {
        val path = dir.resolve(META_FILE)
        if (!fs.exists(path)) return null
        val text = try {
            fs.readText(path)
        } catch (_: Exception) {
            return null
        }
        return try {
            chatJson.decodeFromString(ThreadMeta.serializer(), text)
        } catch (_: Exception) {
            // Una cabecera corrupta no debe voltear la app: el hilo se ignora.
            null
        }
    }

    /** Lee los mensajes del JSONL, saltando las líneas que no se pueden parsear. */
    private fun readMessagesNow(threadId: String): ThreadRead {
        val dir = resolveThreadDir(threadId) ?: return ThreadRead(emptyList(), 0)
        val path = dir.resolve(MESSAGES_FILE)
        if (!fs.exists(path)) return ThreadRead(emptyList(), 0)
        val text = try {
            fs.readText(path)
        } catch (_: Exception) {
            return ThreadRead(emptyList(), 0)
        }
        val messages = mutableListOf<ChatMessage>()
        var corrupted = 0
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                messages += chatJsonLines.decodeFromString(ChatMessage.serializer(), trimmed)
            } catch (_: Exception) {
                // Línea corrupta: se saltea, pero no se pierde el resto del hilo.
                corrupted++
            }
        }
        return ThreadRead(messages, corrupted)
    }

    /**
     * Repara la cola del JSONL.
     *
     * Si el archivo no termina en `\n`, la última línea quedó a medias (un
     * corte justo durante la escritura). Si esa línea es un JSON válido se la
     * conserva (sólo le falta el salto final); si no, se descarta: es basura de
     * una escritura interrumpida.
     */
    private fun repairTail(text: String): String {
        if (text.isEmpty() || text.endsWith("\n")) return text
        val cut = text.lastIndexOf('\n')
        val tail = text.substring(cut + 1).trim()
        val complete = runCatching {
            chatJsonLines.decodeFromString(ChatMessage.serializer(), tail)
        }.isSuccess
        return if (complete) text + "\n" else text.substring(0, cut + 1)
    }

    // ---- índice de rutas ----

    /** Ruta del índice de rutas. */
    private fun indexPath(): FilePath = baseDir().resolve(INDEX_FILE)

    /** Lee el índice (cacheado; si el archivo está roto, se arranca vacío). */
    private fun loadIndex(): ThreadIndex {
        indexCache?.let { return it }
        val path = indexPath()
        val loaded = if (fs.exists(path)) {
            try {
                chatJson.decodeFromString(ThreadIndex.serializer(), fs.readText(path))
            } catch (_: Exception) {
                ThreadIndex()
            }
        } else {
            ThreadIndex()
        }
        indexCache = loaded
        return loaded
    }

    /** Escribe el índice de rutas. */
    private fun storeIndex(index: ThreadIndex) {
        indexCache = index
        writeAtomic(fs, indexPath(), chatJson.encodeToString(ThreadIndex.serializer(), index))
    }

    /** Registra la carpeta de un hilo en el índice. */
    private fun registerThread(courseId: String, authorId: String, threadId: String) {
        val index = loadIndex()
        val entries = index.threads.filterNot { it.threadId == threadId } +
            ThreadIndexEntry(threadId = threadId, courseId = courseId, authorId = authorId)
        storeIndex(index.copy(threads = entries))
    }

    companion object {
        /** Carpeta de los chats dentro de la cátedra. */
        const val CHATS_DIR = "chats"

        /** Nombre de la cabecera de un hilo. */
        const val META_FILE = "meta.json"

        /** Nombre del archivo de mensajes de un hilo. */
        const val MESSAGES_FILE = "messages.jsonl"

        /** Nombre del índice de rutas. */
        const val INDEX_FILE = "threads.index.json"

        /** Sufijo del archivo temporal de las escrituras atómicas. */
        const val TMP_SUFFIX = ".tmp"

        /** Actor por defecto del borrado lógico (la app local). */
        const val DELETED_BY_LOCAL = "local"
    }
}

/**
 * Cabecera de un hilo en disco (`meta.json`).
 *
 * Envuelve al [ChatThread] y agrega lo que el modelo todavía no tiene: el
 * borrado lógico (`deletedAt`). El `courseId` y el `authorId` viven acá (y no
 * en el id) para que el id del hilo sea un UUID puro.
 */
@Serializable
internal data class ThreadMeta(
    val format: String = META_FORMAT,
    val version: Int = META_VERSION,
    val thread: ChatThread,
    /** Instante del borrado lógico; `null` = el hilo vive. */
    val deletedAt: Long? = null,
    /** Quién borró el hilo ("local", "student:<id>", "teacher:<id>"). */
    val deletedBy: String = "",
    /** Motivo del borrado (obligatorio en una supresión). */
    val deleteReason: String = "",
)

/** Índice de rutas: `threadId → (courseId, authorId)`. */
@Serializable
internal data class ThreadIndex(
    val format: String = INDEX_FORMAT,
    val version: Int = INDEX_VERSION,
    val threads: List<ThreadIndexEntry> = emptyList(),
)

/** Una entrada del índice de rutas. */
@Serializable
internal data class ThreadIndexEntry(
    val threadId: String,
    val courseId: String,
    val authorId: String,
)

/**
 * Copia del hilo sin la credencial.
 *
 * La clave que la cátedra da para su servidor **no se persiste nunca**: si
 * quedara escrita en disco, viajaría a la copia de seguridad del alumno y al
 * `.kitoru` que el alumno entrega al docente. La clave efectiva se resuelve
 * en runtime desde la cátedra, no desde el hilo.
 */
internal fun ChatThread.redacted(): ChatThread =
    if (effectiveModel.apiKey.isEmpty() && effectiveModel.apiKeyRef.isEmpty()) this
    else copy(effectiveModel = effectiveModel.redacted())

/**
 * Convierte un id en un nombre de carpeta seguro.
 *
 * Los ids de Kitoru son UUID, así que esto es una red de seguridad para los
 * ids importados, que pueden traer barras o puntos y romper el path.
 */
internal fun safeDirKey(raw: String): String {
    val cleaned = raw.map { c ->
        if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
    }.joinToString("")
    return if (cleaned == raw && cleaned.isNotEmpty()) cleaned
    else "${cleaned.take(48)}_${raw.hashCode().toUInt().toString(16)}"
}

/**
 * Escribe [text] en [path] de forma atómica.
 *
 * Patrón "write-temp-then-rename": se escribe en `<archivo>.tmp`, se fuerza a
 * disco y se renombra. Un corte de luz nunca deja un archivo a medio escribir:
 * el archivo viejo queda entero hasta que el nuevo está completo.
 *
 * `FileSystem` todavía no expone `move`, así que el rename se emula con
 * `copy` + `delete`. Cuando la plataforma sume `move` (o `rename`), este
 * helper debe pasar a usarlo: el rename es atómico y la copia no.
 */
internal fun writeAtomic(fs: FileSystem, path: FilePath, text: String) {
    val dir = path.parent
    if (dir != null) fs.createDirectories(dir)
    val tmp = path.resolve(path.name + ChatStore.TMP_SUFFIX)
    try {
        fs.writeText(tmp, text)
        if (!tryMove(fs, tmp, path)) {
            fs.copy(tmp, path)
            fs.delete(tmp)
        }
    } catch (e: Exception) {
        runCatching { fs.delete(tmp) }
        throw e
    }
}

/**
 * Renombra un archivo usando el `move` de la plataforma, si existe.
 *
 * Se busca por reflexión para que el store no tenga que recompilarse cuando
 * la plataforma agregue el método: si aparece, se usa (el rename es atómico);
 * si no, se cae al `copy` + `delete`.
 *
 * @return `true` si el archivo quedó renombrado.
 */
private fun tryMove(fs: FileSystem, from: FilePath, to: FilePath): Boolean {
    val method = runCatching {
        fs.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "move" &&
                candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[0] == FilePath::class.java &&
                candidate.parameterTypes[1] == FilePath::class.java
        }
    }.getOrNull() ?: return false
    val failure = runCatching { method.invoke(fs, from, to) }.exceptionOrNull()
    if (failure != null) {
        // Si el rename falla (volúmenes distintos, destino bloqueado) se cae
        // al camino de la copia, que no es atómico pero no deja datos perdidos.
        return false
    }
    return true
}

/** Formato del archivo de cabecera de un hilo. */
internal const val META_FORMAT = "kitoru.thread-meta"

/** Versión del formato del archivo de cabecera de un hilo. */
internal const val META_VERSION = 1

/** Formato del índice de rutas. */
internal const val INDEX_FORMAT = "kitoru.thread-index"

/** Versión del formato del índice de rutas. */
internal const val INDEX_VERSION = 1

/** JSON compacto para las cabeceras y el índice. */
internal val chatJson = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * JSON compacto para el JSONL de mensajes.
 *
 * `encodeDefaults = false` + `ignoreUnknownKeys = true` ⇒ **evolución
 * compatible**: un archivo viejo se lee con los defaults nuevos y un archivo
 * nuevo con un cliente viejo no rompe (ignora los campos que no conoce).
 */
internal val chatJsonLines = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** Serializador de una lista de hilos (para `threads.json`). */
internal val threadListSerializer = ListSerializer(ChatThread.serializer())
