package kitoru.domain

import kitoru.platform.SecretRedactor
import kitoru.platform.SecureStore

/**
 * Ida y vuelta de la clave de API entre el modelo serializado y el almacén
 * seguro.
 *
 * El modelo que se persiste (`courses.json`, `threads.json`) **nunca** lleva
 * la clave: lleva un **alias** (`apiKeyRef`) que apunta a un secreto guardado
 * en el almacén seguro de la plataforma.
 *
 * El flujo es siempre el mismo:
 *
 * ```
 * clave pegada por el usuario ──dehydrate──▶ política con apiKeyRef ──▶ JSON
 *                                       │
 *                                       ▼
 *                    ┌───────────── hydrate ──────────────┐
 *                    │  (justo antes de llamar al modelo)  │
 *                    └─────────────────────────────────────┘
 *                                       │
 *                                       ▼
 *                          política con la clave real (en memoria)
 * ```
 *
 * Por qué hidratar **tarde** (justo antes de la petición) y no al abrir la
 * app: la clave en memoria es una superficie de exposición. Cuanto menos
 * tiempo vive, mejor.
 */
object PolicySecrets {

    /**
     * Guarda la clave en el almacén y devuelve la política **sin** el secreto.
     *
     * Si el almacén no pudo guardar (no hay permisos, el Keystore falló), el
     * secreto **no se pierde**: se devuelve la política con la clave todavía
     * en memoria para que la UI pueda avisar y reintentar. Nunca se devuelve
     * una política "vacía" que haga creer que la clave se guardó.
     */
    fun dehydrate(policy: ModelPolicy, store: SecureStore, alias: String): ModelPolicy {
        val secret = policy.apiKey
        if (secret.isBlank()) {
            // No hay secreto nuevo: si la política ya apunta a un alias, se
            // respeta (no se pisa una credencial que ya estaba guardada).
            return policy.copy(apiKey = "", apiKeyRef = policy.apiKeyRef)
        }
        store.put(alias, secret)
        if (!store.has(alias)) {
            // El almacén falló: el secreto sigue en la política, en memoria.
            return policy.copy(apiKeyRef = "")
        }
        return policy.copy(apiKey = "", apiKeyRef = alias)
    }

    /**
     * Sólo quita el secreto de la política, sin tocar el almacén.
     *
     * Para el caso en que la credencial **ya** está guardada bajo ese alias
     * (por ejemplo, una key que emitió el servidor): lo que hay que hacer es
     * dejar de arrastrarla en el modelo.
     */
    fun dehydrate(policy: ModelPolicy, alias: String): ModelPolicy =
        policy.copy(apiKey = "", apiKeyRef = alias)

    /**
     * Vuelve a poner la clave real en la política, leyéndola del almacén.
     *
     * Si el alias no está o el almacén falló, la política vuelve **sin**
     * credencial: la UI distingue los dos casos mirando [SecureStore.lastError]
     * ("no hay clave guardada" es distinto de "no se pudo leer la guardada").
     */
    fun hydrate(policy: ModelPolicy, store: SecureStore): ModelPolicy {
        val ref = policy.apiKeyRef
        if (ref.isBlank()) return policy
        val secret = store.get(ref)
        if (secret.isNullOrEmpty()) return policy.copy(apiKey = "")
        // Se registra el secreto para que el redactor lo tape en cualquier
        // log, error o reporte de crash.
        SecretRedactor.register(secret)
        return policy.copy(apiKey = secret)
    }

    /**
     * Borra la credencial del almacén (al quitar la clave o al borrar la
     * cátedra).
     *
     * La política no cambia: el alias lo decide la app que la guarda.
     */
    fun revoke(policy: ModelPolicy, store: SecureStore) {
        val ref = policy.apiKeyRef
        if (ref.isBlank()) return
        store.remove(ref)
    }

    /** ¿Hay una credencial guardada para esta política? */
    fun hasSecret(policy: ModelPolicy, store: SecureStore): Boolean {
        val ref = policy.apiKeyRef
        return ref.isNotBlank() && store.has(ref)
    }
}
