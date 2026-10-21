package kitoru.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * La pista de conectividad de Android.
 *
 * Usa `ConnectivityManager` y exige `NET_CAPABILITY_VALIDATED`: el sistema
 * hizo una prueba de internet real. `NET_CAPABILITY_INTERNET` solo significa
 * "hay una interfaz de red configurada" (un hotspot sin datos, el Wi-Fi de un
 * hotel con portal cautivo).
 *
 * Ojo con el Wi-Fi de la facultad: el dispositivo puede tener
 * `VALIDATED = true` por el 4G y aun así no llegar al servidor de la LAN. Por
 * eso esta clase es una **pista** y no la señal de verdad: la señal de verdad
 * es el heartbeat HTTP al servidor.
 */
class AndroidConnectivityProbe(context: Context) : ConnectivityProbe {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override suspend fun suggestsOnline(): Boolean {
        val connectivity = manager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Instala la pista de Android en el monitor de conexión.
 *
 * La llama la app al arrancar: hace falta el `Context`, que sólo existe en
 * tiempo de ejecución.
 *
 * @param context el contexto de la aplicación.
 */
fun installAndroidConnectivityProbe(context: Context) {
    NetworkSignals.probe = AndroidConnectivityProbe(context)
}
