package kitoru.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vigila el estado de la conexión.
 *
 * **La señal de verdad es el heartbeat HTTP al Kitoru-Server.** El estado que
 * reporta el sistema operativo es **una pista**: sirve para no gastar un
 * timeout cuando ya sabemos que no hay red, y para avisar enseguida cuando
 * vuelve, pero nunca decide por sí solo que se puede trabajar "en línea".
 *
 * La razón: "hay internet" y "el servidor de la institución responde" son dos
 * preguntas distintas. El servidor puede estar apagado, puede estar en una LAN
 * a la que no se llega desde casa, o puede haber un portal cautivo en el
 * hotel; y al revés, un teléfono puede tener 4G validado y no llegar a la red
 * del laboratorio.
 */
interface NetworkMonitor {

    /**
     * Estado actual de la conexión.
     *
     * Es un [StateFlow] porque la UI lo observa para dibujar el chip de
     * "Conectado" / "Sin conexión" sin preguntar a la red en cada
     * recomposición.
     */
    val isOnline: StateFlow<Boolean>

    /**
     * Chequeo autoritativo del estado de la conexión.
     *
     * Primero consulta la pista del sistema operativo: si dice que no hay red,
     * no gasta el timeout del heartbeat. Si hay red, confirma con el heartbeat
     * HTTP al servidor, que es el que manda.
     *
     * @return true si se puede trabajar contra el servidor.
     */
    suspend fun check(): Boolean
}

/**
 * Crea el monitor de conexión de la plataforma.
 *
 * El monitor lee las señales registradas en [NetworkSignals]: la pista del
 * sistema operativo (la instala cada plataforma) y el heartbeat HTTP (lo
 * instala la capa de sincronización, que es la que sabe la dirección del
 * servidor).
 */
expect fun createNetworkMonitor(): NetworkMonitor

/**
 * La pista del sistema operativo: rápida, gratis y **aproximada**.
 *
 * Sirve para dos cosas y ninguna más: no gastar un timeout cuando ya se sabe
 * que no hay red, y avisar enseguida cuando la red vuelve. Nunca para
 * afirmar que el servidor de la institución está vivo.
 */
interface ConnectivityProbe {

    /** ¿El sistema cree que hay red? */
    suspend fun suggestsOnline(): Boolean
}

/**
 * El "ping" al Kitoru-Server: la señal que manda.
 *
 * Responde la única pregunta que importa para trabajar: "¿hay alguien al otro
 * lado?". Un `true` acá significa que el servidor respondió, no que hay
 * internet.
 */
fun interface Heartbeat {

    /** true si el servidor respondió. */
    suspend fun ping(): Boolean
}

/**
 * Las señales de conexión, en un solo lugar.
 *
 * Es un registro mutable a propósito: la dirección del servidor se cambia en
 * los Ajustes sin reiniciar la app, y la pista del sistema operativo la
 * instala la plataforma cuando la app arranca (en Android hace falta el
 * `Context`, que sólo existe en tiempo de ejecución).
 */
object NetworkSignals {

    /** El servidor configurado (lo cambian los Ajustes). */
    @Volatile
    var server: ServerEndpoint? = null

    /** La pista del sistema operativo. Si es null, se usa la de la plataforma. */
    @Volatile
    var probe: ConnectivityProbe? = null

    /** El heartbeat HTTP al servidor. Si es null, no hay nada que confirmar. */
    @Volatile
    var heartbeat: Heartbeat? = null
}

/**
 * A dónde está el Kitoru-Server.
 *
 * Se parsea la URL a mano (en `commonMain` no hay `java.net.URI`) porque lo
 * único que hace falta es el host y el puerto, para el sondeo por socket.
 */
data class ServerEndpoint(val baseUrl: String) {

    /** La parte de la URL entre el esquema y la barra inicial. */
    private val authority: String =
        baseUrl.substringAfter("://", baseUrl).substringBefore('/').trim()

    /** El host del servidor, sin esquema ni puerto. */
    val host: String = authority.substringBefore(':')

    /** El puerto del servidor: el de la URL, o 443/80 según el esquema. */
    val port: Int = authority.substringAfter(':', "")
        .toIntOrNull()
        ?: if (baseUrl.startsWith("https", ignoreCase = true)) 443 else 80
}

/**
 * La implementación que comparten las dos plataformas.
 *
 * Sólo orchestrá las dos señales: primero la pista del sistema operativo (que
 * es gratis) y después el heartbeat (que es el que manda).
 */
class DefaultNetworkMonitor(
    private val platformProbe: suspend () -> Boolean,
    private val heartbeat: suspend () -> Boolean,
) : NetworkMonitor {

    private val state = MutableStateFlow(false)

    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun check(): Boolean {
        // 1) La pista del sistema operativo: si dice que no hay red, no gastamos
        //    el timeout del heartbeat.
        if (!platformProbe()) {
            state.value = false
            return false
        }
        // 2) El heartbeat: la verdad.
        val online = heartbeat()
        state.value = online
        return online
    }
}
