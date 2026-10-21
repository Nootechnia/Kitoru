package kitoru.platform

import kitoru.domain.KitoruJson
import kitoru.domain.ModelMode
import kitoru.domain.ModelPolicy
import kitoru.domain.PolicySecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del redactor de secretos y del ciclo de la credencial.
 *
 * El redactor es la **última** barrera: la primera es no pasar el secreto al
 * logger. Estos tests fijan el comportamiento mínimo que la app necesita:
 * un token no puede quedar en un texto, una cabecera `Authorization` tampoco,
 * y el texto normal no puede romperse.
 */
class SecretRedactorTest {

    @Test
    fun `no filtra un token de Kitoru`() {
        val token = "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA"
        val texto = "Falló la llamada con la clave $token y no se pudo seguir."

        val limpio = SecretRedactor.redact(texto)

        assertFalse(limpio.contains(token), "el token no puede quedar en el texto: $limpio")
        assertTrue(limpio.contains("ktor_K01J"), "el kid es público y puede quedar: $limpio")
    }

    @Test
    fun `filtra la cabecera Authorization`() {
        val texto = "Authorization: Bearer sk-abcdefghijklmnop1234"

        val limpio = SecretRedactor.redact(texto)

        assertFalse(
            limpio.contains("sk-abcdefghijklmnop1234"),
            "la credencial no puede quedar en el texto: $limpio",
        )
        assertTrue(limpio.contains("Authorization"), "el nombre de la cabecera puede quedar: $limpio")
    }

    @Test
    fun `filtra un par api key`() {
        val texto = """config = {"apiKey": "clave-de-prueba-1234567890"}"""

        val limpio = SecretRedactor.redact(texto)

        assertFalse(
            limpio.contains("clave-de-prueba-1234567890"),
            "la credencial no puede quedar en el texto: $limpio",
        )
        assertTrue(limpio.contains("apiKey"), "el nombre del campo puede quedar: $limpio")
    }

    @Test
    fun `no rompe el texto normal`() {
        val texto = "El alumno preguntó por el material de la cátedra y recibió 3 fragmentos."

        assertEquals(texto, SecretRedactor.redact(texto))
    }

    @Test
    fun `no filtra la palabra tokens`() {
        val texto = "Se guardaron 12000 tokens en el informe de la analítica."

        assertEquals(texto, SecretRedactor.redact(texto))
    }

    @Test
    fun `filtra un secreto registrado`() {
        val secreto = "clave-de-prueba-registrada-9876"
        SecretRedactor.register(secreto)
        try {
            val limpio = SecretRedactor.redact("usando $secreto ahora")

            assertFalse(limpio.contains(secreto), "el secreto no puede quedar en el texto: $limpio")
        } finally {
            SecretRedactor.forget(secreto)
        }
    }

    @Test
    fun `la clave de la politica no se serializa`() {
        val secreto = "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA"
        val policy = ModelPolicy(
            mode = ModelMode.REMOTE,
            remoteBaseUrl = "https://ia.universidad.edu/kitoru/v1",
            apiKey = secreto,
            modelName = "Qwen3-8B",
        )

        val json = KitoruJson.encodeToString(ModelPolicy.serializer(), policy)

        assertFalse(json.contains(secreto), "la clave no puede estar en el JSON: $json")
    }

    @Test
    fun `el ciclo dehydrate e hydrate devuelve la misma clave`() {
        val store = InMemorySecureStore()
        val secreto = "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA"
        val alias = SecretRef.forCourse("IA-2026-1")
        val policy = ModelPolicy(
            mode = ModelMode.REMOTE,
            remoteBaseUrl = "https://ia.universidad.edu/kitoru/v1",
            apiKey = secreto,
            modelName = "Qwen3-8B",
        )

        val seca = PolicySecrets.dehydrate(policy, store, alias)

        assertEquals("", seca.apiKey, "la política guardada no lleva la clave")
        assertEquals(alias, seca.apiKeyRef, "la política guardada lleva el alias")
        assertTrue(store.has(alias), "el alias tiene que estar en el almacén")

        val hidratada = PolicySecrets.hydrate(seca, store)

        assertEquals(secreto, hidratada.apiKey, "la clave vuelve del almacén")
        assertFalse(
            hidratada.toString().contains(secreto),
            "el toString de la política no puede filtrar la clave",
        )
    }

    @Test
    fun `el almacen en memoria no sobrevive a un reinicio`() {
        val alias = SecretRef.forCourse("IA-2026-1")
        val store = InMemorySecureStore()
        store.put(alias, "secreto")

        assertEquals("secreto", store.get(alias))

        // "Reiniciar" la app es crear un almacén nuevo: no hay nada en disco.
        val despuesDelReinicio = InMemorySecureStore()

        assertNull(despuesDelReinicio.get(alias), "en memoria no queda nada después de reiniciar")
        assertFalse(despuesDelReinicio.isSecure, "el almacén en memoria no es seguro")
    }

    @Test
    fun `si el almacen falla la clave no se pierde`() {
        val store = FailingSecureStore()
        val secreto = "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA"
        val policy = ModelPolicy(apiKey = secreto)

        val seca = PolicySecrets.dehydrate(policy, store, SecretRef.forCourse("IA-2026-1"))

        assertEquals(secreto, seca.apiKey, "si el almacén falla, la clave sigue en memoria")
        assertEquals("", seca.apiKeyRef, "no se apunta a un alias que no tiene nada")
    }
}

/**
 * Un almacén que no puede guardar: simula el Keystore fallando o el disco
 * sin permisos.
 */
private class FailingSecureStore : SecureStore {

    override val kind: SecureStoreKind = SecureStoreKind.MEMORY

    override var lastError: String? = "No se pudo guardar la credencial."

    override fun put(alias: String, secret: String) = Unit

    override fun get(alias: String): String? = null

    override fun remove(alias: String) = Unit

    override fun has(alias: String): Boolean = false

    override fun aliases(): List<String> = emptyList()
}
