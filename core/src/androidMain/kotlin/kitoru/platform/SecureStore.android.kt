package kitoru.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * El almacén de secretos de **Android**.
 *
 * Usa el `AndroidKeyStore` del sistema: la clave AES-256-GCM **nunca sale**
 * del Keystore y no se puede exportar. El blob cifrado vive en el sandbox
 * privado de la app (`filesDir/secure`).
 *
 * Qué protege: un volcado del sistema de archivos (un `adb` con root, una
 * copia del `filesDir`) no sirve de nada sin la clave, que no se puede
 * llevar. Qué **no** protege: un dispositivo rooteado con la app
 * commitmentada puede pedirle la clave al Keystore. Es el límite honesto de
 * esta protección, y el mismo que en cualquier app Android.
 *
 * No se usa `androidx.security` (`EncryptedSharedPreferences`): está
 * **deprecado** desde `1.1.0-alpha03` y arrastra Tink y una versión alpha.
 * `minSdk = 24` ya garantiza `KeyGenParameterSpec` (API 23), así que el
 * Keystore directo son unas pocas líneas y cero dependencias.
 */
actual fun secureStore(): SecureStore = AndroidSecureStore(AndroidPlatform.requireContext())

/**
 * Inyecta el `Context` de la app.
 *
 * Lo llama la app una vez al arrancar, igual que `KitoruPaths.setDataDir(..)`:
 * es el mismo patrón que ya usa el repo, para no inventar un contenedor de
 * dependencias en este hito.
 *
 * Sin `Context` no hay `filesDir`, y sin `filesDir` no hay sandbox: el
 * almacén no puede existir. Es un error explícito (fail-fast), no una
 * degradación silenciosa.
 */
object AndroidPlatform {

    private var appContext: Context? = null

    /** Guarda el contexto de la app (el de la aplicación, no el de la Activity). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** El contexto de la app; falla con un mensaje claro si no se inicializó. */
    internal fun requireContext(): Context =
        appContext ?: error(
            "Falta llamar a AndroidPlatform.init(context) al arrancar de la app: " +
                "el almacén seguro necesita el sandbox privado de la app.",
        )
}

/**
 * El almacén de secretos en Android: una clave AES-256-GCM en el
 * `AndroidKeyStore` y un blob por alias dentro del sandbox de la app.
 */
private class AndroidSecureStore(private val context: Context) : SecureStore {

    override val kind: SecureStoreKind = SecureStoreKind.ANDROID_KEYSTORE

    override var lastError: String? = null

    /** Carpeta del sandbox donde viven los blobs cifrados. */
    private val dir: File = File(context.filesDir, "secure")

    override fun put(alias: String, secret: String) {
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(aadOf(alias))
            val body = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val out = ByteArray(1 + cipher.iv.size + body.size)
            out[0] = AesGcm.FORMAT_VERSION
            cipher.iv.copyInto(out, 1)
            body.copyInto(out, 1 + cipher.iv.size)
            writeFile(fileFor(alias), out)
            lastError = null
        } catch (e: Exception) {
            lastError = "No se pudo guardar la clave en el Keystore: ${e.message}"
        }
    }

    override fun get(alias: String): String? {
        val file = fileFor(alias)
        if (!file.exists()) return null
        return try {
            val raw = file.readBytes()
            if (raw.size < 1 + AesGcm.IV_SIZE + 16 || raw[0] != AesGcm.FORMAT_VERSION) {
                lastError = "El archivo de credenciales tiene un formato desconocido."
                return null
            }
            val iv = raw.copyOfRange(1, 1 + AesGcm.IV_SIZE)
            val body = raw.copyOfRange(1 + AesGcm.IV_SIZE, raw.size)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(AesGcm.TAG_BITS, iv))
            cipher.updateAAD(aadOf(alias))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // Clave rotada, blob corrupto, Keystore invalidado: no se recupera.
            lastError = "No se pudo leer la clave guardada: ${e.message}"
            null
        }
    }

    override fun remove(alias: String) {
        fileFor(alias).delete()
    }

    override fun has(alias: String): Boolean = fileFor(alias).exists()

    override fun aliases(): List<String> = dir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(EXTENSION) }
        .mapNotNull { file ->
            Hex.decode(file.name.removeSuffix(EXTENSION))?.toString(Charsets.UTF_8)
        }

    /**
     * La clave AES del Keystore.
     *
     * Se genera la primera vez y queda en el Keystore del sistema: no se
     * exporta, no se copia y no se puede leer desde otro dispositivo.
     *
     * `setUserAuthenticationRequired(false)`: la clave protege el dato **en
     * reposo**, no es una puerta al uso de la app. Pedir la huella en cada
     * mensaje sería insostenible para un chat.
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** El alias va como datos autenticados: el blob no se puede mover de alias. */
    private fun aadOf(alias: String): ByteArray = alias.toByteArray(Charsets.UTF_8)

    private fun fileFor(alias: String): File =
        File(dir, Hex.encode(alias.toByteArray(Charsets.UTF_8)) + EXTENSION)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kitoru.credentials.v1"
        const val CIPHER = "AES/GCM/NoPadding"
        const val EXTENSION = ".bin"
    }
}

/**
 * En Android no hay comandos del sistema operativo que ejecutar.
 *
 * El `actual` de escritorio lanza procesos (`powershell.exe`, `security`,
 * `secret-tool`); en Android eso no existe, y fingir que sí sería mentir.
 */
internal actual fun runOsCommand(
    command: List<String>,
    stdin: String?,
    timeoutMs: Long,
): OsCommandResult = OsCommandResult(
    exitCode = -1,
    output = "En Android no hay comandos del sistema operativo.",
)
