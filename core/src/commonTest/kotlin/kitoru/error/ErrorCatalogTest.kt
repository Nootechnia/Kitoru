package kitoru.error

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del catálogo de errores.
 *
 * El catálogo es un **contrato de textos**: son las frases que el usuario
 * lee. Por eso hay tests que revisan el estilo de todas las entradas (no de
 * algunas), el mapeo de los códigos del LLM y la traducción de excepciones.
 *
 * El test corre en `commonTest` porque no toca nada de plataforma: los
 * mensajes y el mapeo son iguales en el escritorio y en Android.
 */
class ErrorCatalogTest {

    // -----------------------------------------------------------------------
    // 1. El catálogo
    // -----------------------------------------------------------------------

    @Test
    fun `el catalogo tiene 30 codigos`() {
        assertEquals(30, ErrorCode.entries.size, "El catálogo cambió de tamaño.")
    }

    @Test
    fun `todo codigo tiene mensaje y pista`() {
        for (code in ErrorCode.entries) {
            val error = ErrorCatalog.of(code)
            assertTrue(error.message.isNotBlank(), "El código $code no tiene mensaje.")
            assertTrue(error.hint.isNotBlank(), "El código $code no tiene pista: hay que poder hacer algo.")
            assertEquals(code, error.code, "El código $code no se devuelve en su propio error.")
        }
    }

    @Test
    fun `los mensajes arrancan en mayuscula y terminan en punto`() {
        for (code in ErrorCode.entries) {
            val error = ErrorCatalog.of(code)
            for ((donde, texto) in listOf("mensaje" to error.message, "pista" to error.hint)) {
                assertTrue(texto.isNotBlank(), "El $donde de $code está vacío.")
                assertTrue(
                    texto.first().isUpperCase(),
                    "El $donde de $code no arranca en mayúscula: «$texto».",
                )
                assertTrue(texto.endsWith("."), "El $donde de $code no termina en punto: «$texto».")
            }
        }
    }

    @Test
    fun `ningun mensaje supera los 200 caracteres`() {
        for (code in ErrorCode.entries) {
            val error = ErrorCatalog.of(code)
            assertTrue(error.message.length <= 200, "El mensaje de $code es demasiado largo: ${error.message.length}.")
            assertTrue(error.hint.length <= 200, "La pista de $code es demasiado larga: ${error.hint.length}.")
        }
    }

    @Test
    fun `ningun mensaje termina en una excepcion de Java`() {
        for (code in ErrorCode.entries) {
            val error = ErrorCatalog.of(code)
            for ((donde, texto) in listOf("mensaje" to error.message, "pista" to error.hint)) {
                val ultima = ultimaPalabra(texto)
                assertFalse(
                    SUFIJO_DE_EXCEPCION.containsMatchIn(ultima),
                    "El $donde de $code termina en una clase de Java: «$texto».",
                )
                assertFalse(
                    ultima.contains('.'),
                    "El $donde de $code termina en un nombre de clase de Java: «$texto».",
                )
            }
        }
    }

    @Test
    fun `ningun mensaje contiene null ni Throwable`() {
        for (code in ErrorCode.entries) {
            val error = ErrorCatalog.of(code)
            for ((donde, texto) in listOf("mensaje" to error.message, "pista" to error.hint)) {
                assertFalse(
                    texto.contains("null", ignoreCase = true),
                    "El $donde de $code contiene «null»: «$texto».",
                )
                assertFalse(
                    texto.contains("Throwable"),
                    "El $donde de $code contiene «Throwable»: «$texto».",
                )
            }
        }
    }

    @Test
    fun `los mensajes del catalogo son todos distintos`() {
        val mensajes = ErrorCode.entries.map { ErrorCatalog.of(it).message }
        assertEquals(
            mensajes.size,
            mensajes.toSet().size,
            "Hay dos códigos con el mismo mensaje: el catálogo se está desincronizando.",
        )
    }

    @Test
    fun `las acciones de un codigo no se repiten`() {
        for (code in ErrorCode.entries) {
            val acciones = ErrorCatalog.of(code).actions
            assertEquals(
                acciones.size,
                acciones.toSet().size,
                "El código $code ofrece la misma acción dos veces: $acciones.",
            )
        }
    }

    // -----------------------------------------------------------------------
    // 2. El mapeo de los códigos del LLM
    // -----------------------------------------------------------------------

    @Test
    fun `cada codigo del LLM se traduce a su codigo de Kitoru`() {
        // Los nombres son los de `llm.error.LlmErrorCode`: el mapeo es por
        // nombre porque `:core` no depende de `:llm-api`.
        val esperados = mapOf(
            "SIN_CUOTA" to ErrorCode.SIN_CUOTA,
            "CLAVE_INVALIDA" to ErrorCode.CLAVE_INVALIDA,
            "TIMEOUT" to ErrorCode.TIMEOUT,
            "NO_CONFIGURADO" to ErrorCode.NO_CONFIGURADO,
            "MODELO_NO_DISPONIBLE" to ErrorCode.MODELO_NO_DISPONIBLE,
            "CONTEXTO_AGOTADO" to ErrorCode.CONTEXTO_AGOTADO,
            "DESCARGA_FALLIDA" to ErrorCode.DESCARGA_FALLIDA,
            "SERVIDOR_CAIDO" to ErrorCode.SERVIDOR_CAIDO,
            "CANCELADO" to ErrorCode.CANCELADO,
            "DESCONOCIDO" to ErrorCode.DESCONOCIDO,
        )
        assertEquals(
            esperados.keys,
            ErrorCatalog.llmCodeNames,
            "El catálogo no cubre todos los códigos del LLM.",
        )
        for ((nombre, esperado) in esperados) {
            assertEquals(esperado, ErrorCatalog.fromLlmCodeName(nombre), "El código $nombre no se traduce bien.")
        }
    }

    @Test
    fun `un codigo del LLM desconocido cae en DESCONOCIDO`() {
        assertEquals(ErrorCode.DESCONOCIDO, ErrorCatalog.fromLlmCodeName("CODIGO_DEL_FUTURO"))
        assertEquals(ErrorCode.DESCONOCIDO, ErrorCatalog.fromLlmCodeName(null))
        assertEquals(ErrorCode.DESCONOCIDO, ErrorCatalog.fromLlmCodeName("  "))
    }

    @Test
    fun `el mapeo del LLM tolera mayusculas y espacios`() {
        assertEquals(ErrorCode.SIN_CUOTA, ErrorCatalog.fromLlmCodeName("sin_cuota"))
        assertEquals(ErrorCode.SIN_CUOTA, ErrorCatalog.fromLlmCodeName(" Sin_Cuota "))
    }

    @Test
    fun `fromName tolera mayusculas espacios y guiones`() {
        assertEquals(ErrorCode.SIN_CONEXION, ErrorCode.fromName("sin conexion"))
        assertEquals(ErrorCode.SIN_CONEXION, ErrorCode.fromName("SIN-CONEXION"))
        assertEquals(ErrorCode.SIN_CONEXION, ErrorCode.fromName("  Sin_Conexion  "))
    }

    @Test
    fun `fromName devuelve el respaldo para un nombre desconocido`() {
        assertEquals(ErrorCode.DESCONOCIDO, ErrorCode.fromName("no_existe"))
        assertEquals(ErrorCode.DESCONOCIDO, ErrorCode.fromName(null))
        assertEquals(ErrorCode.DESCONOCIDO, ErrorCode.fromName(""))
        assertEquals(ErrorCode.TIMEOUT, ErrorCode.fromName("no_existe", fallback = ErrorCode.TIMEOUT))
    }

    // -----------------------------------------------------------------------
    // 3. La traducción de las excepciones
    // -----------------------------------------------------------------------

    @Test
    fun `una excepcion generica se traduce a DESCONOCIDO`() {
        val error = IllegalStateException("Connection refused").toKitoruError()
        assertEquals(ErrorCode.DESCONOCIDO, error.code)
        assertEquals(ErrorSeverity.ERROR, error.severity)
    }

    @Test
    fun `el toString crudo de la excepcion nunca llega al mensaje`() {
        val crudo = "java.net.ConnectException: Connection refused"
        val error = IllegalStateException(crudo).toKitoruError()
        assertFalse(error.message.contains("Connection refused"), "El mensaje crudo se coló: ${error.message}")
        assertFalse(error.message.contains("java."), "El mensaje crudo se coló: ${error.message}")
        assertFalse(error.hint.contains("java."), "La pista se coló: ${error.hint}")
        assertEquals(
            ErrorCatalog.of(ErrorCode.DESCONOCIDO).message,
            error.message,
            "El mensaje tiene que salir del catálogo, no de la excepción.",
        )
    }

    @Test
    fun `el detalle tecnico conserva el tipo y el mensaje de la excepcion`() {
        val error = IllegalStateException("Connection refused").toKitoruError()
        val detalle = assertNotNull(error.detail, "La excepción genérica tiene que llevar detalle técnico.")
        assertTrue(detalle.contains("IllegalStateException"), "El detalle no dice el tipo: $detalle")
        assertTrue(detalle.contains("Connection refused"), "El detalle no dice el mensaje: $detalle")
    }

    @Test
    fun `el detalle tecnico redacta las claves de API`() {
        val error = IllegalStateException("falló con api_key=sk-abcdef123456789").toKitoruError()
        val detalle = assertNotNull(error.detail)
        assertFalse(detalle.contains("sk-abcdef123456789"), "La clave de API se coló en el diagnóstico: $detalle")
        assertTrue(detalle.contains("redactada"), "La clave de API no se marcó como redactada: $detalle")
    }

    @Test
    fun `una excepcion con corchetes que no es del LLM no se confunde`() {
        val error = IllegalStateException("[ALGO] el chat no arrancó").toKitoruError()
        assertEquals(ErrorCode.DESCONOCIDO, error.code)
    }

    @Test
    fun `un error del LLM se traduce por su codigo`() {
        val error = ExcepcionDeLlmDePrueba("SIN_CUOTA", "la API alcanzó su límite de uso (HTTP 429).")
            .toKitoruError()
        assertEquals(ErrorCode.SIN_CUOTA, error.code)
        assertEquals(
            ErrorCatalog.of(ErrorCode.SIN_CUOTA).message,
            error.message,
            "El mensaje del LLM tiene que salir del catálogo.",
        )
        assertFalse(
            error.message.contains("HTTP"),
            "El mensaje del LLM no puede mostrar el código HTTP: ${error.message}",
        )
    }

    @Test
    fun `un error del LLM desconocido se degrada a DESCONOCIDO`() {
        val error = ExcepcionDeLlmDePrueba("CODIGO_DEL_FUTURO", "algo raro").toKitoruError()
        assertEquals(ErrorCode.DESCONOCIDO, error.code)
    }

    @Test
    fun `la cancelacion del LLM no se reporta como error`() {
        val error = ExcepcionDeLlmDePrueba("CANCELADO", "La generación fue cancelada.").toKitoruError()
        assertEquals(ErrorCode.CANCELADO, error.code)
        assertEquals(ErrorSeverity.INFO, error.severity)
    }

    @Test
    fun `la cancelacion no se reporta como error`() {
        val error = CancellationException("cancelada").toKitoruError()
        assertEquals(ErrorCode.CANCELADO, error.code)
        assertEquals(ErrorSeverity.INFO, error.severity)
        assertFalse(error.isBlocking, "Una cancelación no puede bloquear la app.")
        assertEquals(ErrorSeverity.INFO, ErrorCatalog.of(ErrorCode.CANCELADO).severity)
    }

    @Test
    fun `el error desconocido ofrece una salida`() {
        val error = ErrorCatalog.of(ErrorCode.DESCONOCIDO)
        assertTrue(error.hasActions, "El error desconocido tiene que ofrecer acciones.")
        assertTrue(error.actions.contains(ErrorAction.EXPORT_DIAGNOSTIC), "Falta la acción de diagnóstico.")
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    /** La última palabra de una frase, sin el punto final. */
    private fun ultimaPalabra(texto: String): String =
        texto.trim().removeSuffix(".").substringAfterLast(' ').trim()

    /** Los sufijos de las clases de Java que no pueden aparecer en un mensaje. */
    private val SUFIJO_DE_EXCEPCION = Regex("(Exception|Error|Throwable)$")
}

/**
 * Doble de la excepción del LLM.
 *
 * `:core` no depende de `:llm-api`, así que el test no puede usar
 * `llm.error.LlmException`: reproduce la forma que Kitoru reconoce
 * (`"LlmException[CODIGO, retryable=...]"`).
 */
private class ExcepcionDeLlmDePrueba(
    val code: String,
    override val message: String,
) : Exception(message) {
    override fun toString(): String = "LlmException[$code, retryable=false]: $message"
}
