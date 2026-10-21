package kitoru.platform

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.withContext

/**
 * La pista de conectividad en la JVM (escritorio y Android).
 *
 * Un `Socket` contra el host del servidor configurado, con timeout corto. No
 * manda ni un byte: sólo comprueba que hay alguien escuchando.
 *
 * Decisiones:
 * - El host es el del **servidor configurado**, no un DNS público: "hay
 *   internet" no dice si el servidor de la institución está vivo, que es la
 *   pregunta que importa.
 * - El timeout es de 1,5 s: más corto da falsos negativos en redes
 *   celulares; más largo deja la UI esperando.
 * - Si no hay servidor configurado, no se puede sondear nada: el estado es
 *   "desconocido", no "sin conexión".
 */
private class JvmSocketProbe(
    private val timeoutMs: Int = 1_500,
) : ConnectivityProbe {

    override suspend fun suggestsOnline(): Boolean {
        val server = NetworkSignals.server ?: return false
        return withContext(ioDispatcher) { tcpReachable(server.host, server.port) }
    }

    /**
     * Un TCP connect con timeout corto.
     *
     * No comprueba internet: comprueba *ese* host. Si el servidor está en la
     * LAN y el alumno está en casa, el resultado es "sin conexión", que es la
     * respuesta correcta para la pregunta que importa.
     */
    private fun tcpReachable(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/**
 * El monitor de conexión en la JVM.
 *
 * Reúne las dos señales: la pista del sistema operativo (el socket) y el
 * heartbeat HTTP (lo instala la capa de sincronización).
 */
actual fun createNetworkMonitor(): NetworkMonitor = DefaultNetworkMonitor(
    platformProbe = {
        val probe = NetworkSignals.probe
        if (probe != null) probe.suggestsOnline() else JvmSocketProbe().suggestsOnline()
    },
    heartbeat = {
        val beat = NetworkSignals.heartbeat
        // Sin servidor configurado no hay a quién preguntarle: no hay nada que
        // confirmar y la app sigue funcionando como local.
        if (beat != null) beat.ping() else true
    },
)
