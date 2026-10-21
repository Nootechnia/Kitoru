package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Configuración de la serialización: la misma de FunshiEngineAI. */
val KitoruJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Guardado JSON genérico sobre [FileSystem].
 *
 * Todo se guarda en un archivo por tipo dentro de `config/`. Es simple,
 * portable a Android y suficiente para el milestone 1; el formato es el
 * mismo que usa FunshiEngineAI (`chats.json`), así que las herramientas de
 * diagnóstico se pueden reaprovechar.
 *
 * Las escrituras son serializadas con un [Mutex] para que dos corrutinas no
 * se pisen el archivo.
 *
 * @param fs sistema de archivos donde vive el archivo.
 * @param path ruta del archivo.
 * @param serializer serializador del valor guardado.
 * @param empty valor que se devuelve si no hay nada que leer.
 * @param recover recupera el valor de un archivo que no se pudo decodificar
 *   entero. Por defecto no se recupera nada (se devuelve el vacío). Las
 *   listas lo usan para rescatar los elementos válidos de una lista con
 *   algún elemento roto: perder un elemento no puede costar la lista entera.
 */
class JsonStore<T : Any>(
    private val fs: FileSystem,
    private val path: FilePath,
    private val serializer: KSerializer<T>,
    private val empty: () -> T,
    private val recover: ((String) -> T)? = null,
) {
    private val mutex = Mutex()

    /**
     * Lee el valor; si el archivo no existe o está roto, devuelve el vacío.
     *
     * Las variantes [_read]/[_write] no toman el cerrojo: se usan desde
     * [update], que ya lo tiene tomado (un [Mutex] no es reentrante).
     */
    suspend fun read(): T = mutex.withLock { _read() }

    /** Escribe el valor completo. */
    suspend fun write(value: T) = mutex.withLock { _write(value) }

    /** Lee, transforma y escribe, todo bajo el mismo cerrojo. */
    suspend fun update(transform: (T) -> T) = mutex.withLock {
        _write(transform(_read()))
    }

    private fun _read(): T {
        if (!fs.exists(path)) return empty()
        val text = try {
            fs.readText(path)
        } catch (_: Exception) {
            return empty()
        }
        return try {
            KitoruJson.decodeFromString(serializer, text)
        } catch (_: Exception) {
            // Un archivo corrupto no debe voltear la app: se intenta
            // recuperar lo que se pueda antes de devolver el vacío.
            recover?.invoke(text) ?: empty()
        }
    }

    /**
     * Escribe el valor de forma **atómica**.
     *
     * Se escribe primero en `archivo.tmp` y después se renombra al destino.
     * Si la app se corta (o se apaga) a mitad de escritura, el archivo bueno
     * sigue intacto: nunca queda un JSON a medio escribir en el destino.
     */
    private fun _write(value: T) {
        // Sólo se crea la carpeta padre si la ruta la tiene: crear un
        // directorio con el nombre del archivo lo deja inaccesible.
        path.parent?.let { fs.createDirectories(it) }
        val tmp = FilePath(path.value + ".tmp")
        try {
            fs.writeText(tmp, KitoruJson.encodeToString(serializer, value))
            fs.move(tmp, path)
        } catch (e: Exception) {
            // No queda basura: el `.tmp` se borra y el error sube.
            fs.delete(tmp)
            throw e
        }
    }
}

/** [JsonStore] para listas. */
class JsonListStore<T : Any>(
    fs: FileSystem,
    path: FilePath,
    itemSerializer: KSerializer<T>,
) {
    private val store = JsonStore(
        fs = fs,
        path = path,
        serializer = ListSerializer(itemSerializer),
        empty = { emptyList() },
        recover = { texto -> recuperarElementos(texto, itemSerializer) },
    )

    suspend fun read(): List<T> = store.read()
    suspend fun write(value: List<T>) = store.write(value)
    suspend fun update(transform: (List<T>) -> List<T>) = store.update(transform)
}

/**
 * Rescata los elementos válidos de una lista que no se pudo decodificar.
 *
 * Si un solo elemento está roto, la lista entera no se puede leer: eso
 * perdería el trabajo de todos los demás. Se decodifica **de a uno**: los
 * que están bien se conservan y el roto se descarta.
 */
private fun <T : Any> recuperarElementos(texto: String, itemSerializer: KSerializer<T>): List<T> {
    val elementos = try {
        KitoruJson.parseToJsonElement(texto) as? JsonArray
    } catch (_: Exception) {
        null
    }
    if (elementos != null) {
        return elementos.mapNotNull { elemento ->
            try {
                KitoruJson.decodeFromJsonElement(itemSerializer, elemento)
            } catch (_: Exception) {
                // Este elemento está roto: se descarta, el resto se salva.
                null
            }
        }
    }
    // El JSON ni siquiera es válido (archivo cortado a mitad de escritura):
    // se rescatan los objetos completos que se puedan.
    return recuperarObjetos(texto, itemSerializer)
}

/**
 * Decodifica los objetos `{...}` que se puedan encontrar en un texto que no
 * es JSON válido (por ejemplo, un archivo cortado por la mitad).
 */
private fun <T : Any> recuperarObjetos(texto: String, itemSerializer: KSerializer<T>): List<T> {
    val resultado = mutableListOf<T>()
    var i = 0
    while (i < texto.length) {
        val inicio = texto.indexOf('{', i)
        if (inicio < 0) break
        var nivel = 0
        var fin = -1
        var j = inicio
        while (j < texto.length) {
            when (texto[j]) {
                '{' -> nivel++
                '}' -> {
                    nivel--
                    if (nivel == 0) fin = j
                }
            }
            if (fin >= 0) break
            j++
        }
        // Un objeto sin cerrar: el archivo quedó cortado, no hay más.
        if (fin < 0) break
        try {
            resultado += KitoruJson.decodeFromString(itemSerializer, texto.substring(inicio, fin + 1))
        } catch (_: Exception) {
            // Este elemento está roto: se descarta, el resto se salva.
        }
        i = fin + 1
    }
    return resultado
}
