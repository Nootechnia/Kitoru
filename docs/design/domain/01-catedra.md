# 01 — Ciclo de vida de la Cátedra

> Diseño de dominio. Todo el código de este documento es **propuesta concreta**
> lista para copiar al repo; no se escribió en el repo.
>
> Archivos nuevos que este diseño implica:
>
> | Archivo | Contenido |
> |---|---|
> | `core/src/commonMain/kotlin/kitoru/domain/CourseStatus.kt` | `CourseStatus` + serializer tolerante |
> | `core/src/commonMain/kotlin/kitoru/domain/CourseLifecycle.kt` | Tabla de transiciones y funciones de transición |
> | `core/src/commonMain/kotlin/kitoru/domain/JoinCode.kt` | Generación, normalización y rotación del código |
> | `core/src/commonMain/kotlin/kitoru/domain/Term.kt` | Cursada (año/cuatrimestre) |
> | `core/src/commonMain/kotlin/kitoru/domain/KeyLease.kt` | Estado de la credencial de la institución |
> | `core/src/commonMain/kotlin/kitoru/domain/CourseMigration.kt` | Normalización de cátedras viejas |
> | `core/src/commonMain/kotlin/kitoru/domain/EnrollmentInvite.kt` | Códigos de un solo uso (opcional) |
> | `core/src/commonMain/kotlin/kitoru/platform/CivilDate.kt` | Fecha civil sin `java.time` |
> | `core/src/desktopTest/kotlin/kitoru/domain/CourseLifecycleTest.kt` | Tests de la máquina de estados |

---

## 0. Resumen de las decisiones

| Tema | Decisión | Por qué |
|---|---|---|
| Estados | **Enum explícito** `CourseStatus` (5 estados) | Hay estados que no se derivan del tiempo (`ARCHIVADA`, `CERRADA` son decisiones del docente) y hacen falta para esconder/mostrar botones |
| Código de inscripción | `joinCode` de **8 chars base32** (40 bits), **permanente** mientras la cátedra esté abierta | 1,1 × 10¹² combinaciones; 8 caracteres se tipean bien en un pizarrón |
| Rotación | El docente la dispara; el código viejo muere al instante | Es la única forma de "quemar" un código filtrado |
| Cursada | `term` sigue siendo `String` canónico `"YYYY-H"`; se agrega `Term` **derivado** + `termStartAt`/`termEndAt` | Cambiar el tipo de un campo serializado rompe los JSON viejos **y** `JsonStore` los descarta en silencio |
| Vencimiento de la key | `apiKeyExpiresAt` explícito + `effectiveKeyExpiry = min(key, fin de cursada)` | La institución emite la key; la cursada pone el techo |
| Multi-cátedra | Un usuario en N cátedras; `Enrollment` único por `(courseId, userId)` | Un docente puede ser titular en una y alumno en otra |
| Archivar vs borrar | **Archivar conserva todo**; borrar es una purga explícita e irreversible | La analítica y los chats son el producto del docente: no se pierden por archivar |
| Serialización | Todo campo nuevo con default; `term` no cambia de tipo; enum con serializer tolerante | `ignoreUnknownKeys` ya da compat hacia adelante; los defaults dan compat hacia atrás |

---

## 1. Estados de la cátedra

### 1.1 ¿Enum o campo derivado?

**Enum explícito.** Un campo derivado (`published = createdAt < now`, `archived = ...`) no alcanza:

- `ARCHIVADA` y `CERRADA` son **decisiones del docente**, no se deducen de ninguna fecha.
- El botón de la UI depende del estado: con un enum la UI itera `CourseLifecycle.nextStates(status)` y no conoce reglas.
- Un enum serializado es más legible en el JSON (`"status": "EN_CURSADA"`) que un conjunto de booleanos.

Lo que **sí** es derivado: los permisos que cada estado implica (`acceptsEnrollment`, `allowsChat`,
`isReadOnly`). Esos viven como propiedades del enum, no como campos guardados.

### 1.2 El enum

```kotlin
package kitoru.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Estado del ciclo de vida de una cátedra.
 *
 * BORRADOR ──publicar──▶ PUBLICADA ──iniciar cursada──▶ EN_CURSADA
 *    │                       │  ▲                          │
 *    │                       │  └──── reabrir inscripción ┘
 *    ▼                       ▼                             ▼
 * ARCHIVADA ◀───────────── CERRADA ◀──────────────────────┘
 *    │
 *    └── desarchivar ──▶ PUBLICADA
 */
@Serializable(with = CourseStatusSerializer::class)
enum class CourseStatus(val label: String, val description: String) {
    /** Sólo el docente la ve. No acepta inscripciones ni chats. */
    BORRADOR(
        "Borrador",
        "Sólo el docente la ve. Todavía no acepta inscripciones.",
    ),

    /** Abierta: los alumnos se anotan con el código y pueden chatear. */
    PUBLICADA(
        "Publicada",
        "Abierta: los alumnos se anotan con el código y pueden chatear.",
    ),

    /** La cursada arrancó: el reloj de la cursada corre y la clave se consume. */
    EN_CURSADA(
        "En cursada",
        "La cursada arrancó: la clave de la institución está en uso.",
    ),

    /** Terminó la cursada: todo queda en sólo lectura y la clave vence. */
    CERRADA(
        "Cerrada",
        "Terminó la cursada: los chats quedan en sólo lectura y la clave venció.",
    ),

    /** Fuera de la lista principal. Los datos se conservan intactos. */
    ARCHIVADA(
        "Archivada",
        "Fuera de la lista principal. Los datos se conservan intactos.",
    ),
    ;

    /** ¿Se puede anotar un alumno con el código de inscripción? */
    val acceptsEnrollment: Boolean get() = this == PUBLICADA || this == EN_CURSADA

    /** ¿Se puede chatear en esta cátedra? */
    val allowsChat: Boolean get() = this == PUBLICADA || this == EN_CURSADA

    /** ¿Todo es de sólo lectura (materiales, chats, analítica)? */
    val isReadOnly: Boolean get() = this == CERRADA || this == ARCHIVADA

    /** ¿Está en la lista principal del alumno? */
    val isVisibleToStudents: Boolean get() = this != BORRADOR

    /** ¿Es una cátedra "viva" (no archivada ni cerrada)? */
    val isActive: Boolean get() = this == BORRADOR || this == PUBLICADA || this == EN_CURSADA

    companion object {
        fun fromName(name: String): CourseStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Serializer tolerante: un estado desconocido (de una versión más nueva de la
 * app) **no** rompe la carga de `courses.json`.
 *
 * Con el serializer por defecto de kotlinx, un valor de enum desconocido
 * lanza `SerializationException`; `JsonStore` la traga y devuelve una lista
 * vacía → el usuario ve "no tenés cátedras". Con este serializer el estado
 * desconocido cae en `BORRADOR` (la posición más conservadora) y el resto de
 * la cátedra se conserva.
 */
object CourseStatusSerializer : KSerializer<CourseStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kitoru.domain.CourseStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): CourseStatus {
        val raw = decoder.decodeString()
        return CourseStatus.fromName(raw) ?: CourseStatus.BORRADOR
    }

    override fun serialize(encoder: Encoder, value: CourseStatus) {
        encoder.encodeString(value.name)
    }
}
```

### 1.3 Tabla de transiciones

| Desde | Hacia | Botón en la UI | Efectos |
|---|---|---|---|
| `BORRADOR` | `PUBLICADA` | **Publicar** | Valida nombre/código/código-de-inscripción; emite `joinCode` si falta; `publishedAt = now` |
| `BORRADOR` | `ARCHIVADA` | **Archivar** | Invalida el código; conserva todo lo demás |
| `PUBLICADA` | `BORRADOR` | **Volver a borrador** | Invalida el código; `publishedAt = 0`; conserva `startedAt`/`closedAt` como historial |
| `PUBLICADA` | `EN_CURSADA` | **Iniciar cursada** | Materializa `termStartAt`/`termEndAt`; `startedAt = now`; si la key no tiene vencimiento propio → `apiKeyExpiresAt = termEndAt` |
| `PUBLICADA` | `ARCHIVADA` | **Archivar** | Invalida el código |
| `EN_CURSADA` | `PUBLICADA` | **Reabrir inscripciones** | Vuelve a aceptar anotaciones tardías; no reinicia el reloj de la cursada |
| `EN_CURSADA` | `CERRADA` | **Cerrar cursada** | `closedAt = now`; **la clave vence ahora** (`apiKeyExpiresAt = now`); chats y materiales a sólo lectura |
| `EN_CURSADA` | `ARCHIVADA` | **Archivar** | Invalida el código; conserva analítica y chats |
| `CERRADA` | `PUBLICADA` | **Reabrir cursada** | Exige **key nueva** (la anterior está vencida); reinicia `startedAt`/`closedAt` |
| `CERRADA` | `ARCHIVADA` | **Archivar** | |
| `ARCHIVADA` | `PUBLICADA` | **Desarchivar** | Vuelve a la lista principal; requiere key vigente si la cursada ya había empezado |
| `ARCHIVADA` | `BORRADOR` | **Restaurar a borrador** | |
| `ARCHIVADA` | `CERRADA` | **Cerrar** | Cierra sin volver a publicar |

Transiciones **prohibidas** (y por lo tanto con error explícito):
`BORRADOR → EN_CURSADA`, `BORRADOR → CERRADA`, `EN_CURSADA → BORRADOR`,
`PUBLICADA → CERRADA` (primero hay que iniciar la cursada), `CERRADA → BORRADOR`,
`BORRADOR → BORRADOR` (no es una transición).

`PUBLISHED → CLOSED` directo está prohibido a propósito: si nunca se inició la cursada,
cerrarla no tiene sentido; se archiva.

### 1.4 Código de la máquina de estados

```kotlin
package kitoru.domain

import kitoru.platform.nowMillis

/**
 * Ciclo de vida de la cátedra.
 *
 * Es un objeto con funciones puras: recibe una [Course] y devuelve otra
 * [Course]. No toca disco, no llama a la red. Eso lo hace testeable al 100%
 * (mismo criterio que [AnalyticsAnalyzer]).
 */
object CourseLifecycle {

    /** Transiciones válidas por estado de origen. */
    private val TRANSITIONS: Map<CourseStatus, Set<CourseStatus>> = mapOf(
        CourseStatus.BORRADOR to setOf(
            CourseStatus.PUBLICADA,
            CourseStatus.ARCHIVADA,
        ),
        CourseStatus.PUBLICADA to setOf(
            CourseStatus.BORRADOR,
            CourseStatus.EN_CURSADA,
            CourseStatus.ARCHIVADA,
        ),
        CourseStatus.EN_CURSADA to setOf(
            CourseStatus.PUBLICADA,
            CourseStatus.CERRADA,
            CourseStatus.ARCHIVADA,
        ),
        CourseStatus.CERRADA to setOf(
            CourseStatus.PUBLICADA,
            CourseStatus.ARCHIVADA,
        ),
        CourseStatus.ARCHIVADA to setOf(
            CourseStatus.BORRADOR,
            CourseStatus.PUBLICADA,
            CourseStatus.CERRADA,
        ),
    )

    /** ¿Se puede pasar de [from] a [to]? */
    fun canTransition(from: CourseStatus, to: CourseStatus): Boolean =
        to in TRANSITIONS[from].orEmpty()

    /** Estados alcanzables desde [from] (la UI dibuja un botón por cada uno). */
    fun nextStates(from: CourseStatus): Set<CourseStatus> = TRANSITIONS[from].orEmpty()

    /**
     * Transición de la cátedra.
     *
     * @param course cátedra actual.
     * @param to estado destino.
     * @param actor rol que pide el cambio (sólo el titular puede cambiar el estado).
     * @param now instante actual (epoch millis); inyectable para los tests.
     * @param joinCode código de inscripción a usar al publicar (el que resolvió
     *   el generador único). Si es `null` se usa el que ya tiene la cátedra.
     * @return la cátedra actualizada (con `updatedAt` seteado).
     * @throws IllegalStateException si la transición no es válida o faltan datos.
     */
    fun transition(
        course: Course,
        to: CourseStatus,
        actor: CourseRole = CourseRole.TITULAR,
        now: Long = nowMillis(),
        joinCode: String? = null,
    ): Course {
        if (actor != CourseRole.TITULAR) {
            throw IllegalStateException(
                "Sólo el profesor titular puede cambiar el estado de la cátedra.",
            )
        }
        if (!canTransition(course.status, to)) {
            throw IllegalStateException(
                "No se puede pasar la cátedra de ${course.status.label} a ${to.label}.",
            )
        }
        val updated = when (to) {
            CourseStatus.BORRADOR -> toDraft(course)
            CourseStatus.PUBLICADA -> toPublished(course, now, joinCode)
            CourseStatus.EN_CURSADA -> toInProgress(course, now)
            CourseStatus.CERRADA -> toClosed(course, now)
            CourseStatus.ARCHIVADA -> toArchived(course, now)
        }
        return updated.copy(status = to, updatedAt = now)
    }

    /**
     * Rota el código de inscripción: el anterior deja de funcionar en el acto.
     *
     * Las inscripciones ya hechas no se caen: dependen del `Enrollment`, no
     * del código.
     */
    fun rotateJoinCode(
        course: Course,
        newCode: String,
        now: Long = nowMillis(),
    ): Course {
        if (!course.status.acceptsEnrollment) {
            throw IllegalStateException(
                "Sólo se puede rotar el código de una cátedra publicada o en cursada.",
            )
        }
        val code = JoinCode.normalize(newCode)
        if (!JoinCode.isValid(code)) {
            throw IllegalArgumentException(
                "El código de inscripción debe tener ${JoinCode.LENGTH} caracteres.",
            )
        }
        return course.copy(
            joinCode = code,
            joinCodeIssuedAt = now,
            joinCodeRotatedAt = now,
            updatedAt = now,
        )
    }

    // ---- Efectos de cada transición ----------------------------------------

    /** Volver a borrador: se invalida el código, se conserva el historial. */
    private fun toDraft(course: Course): Course = course.copy(
        joinCode = "",
        joinCodeIssuedAt = 0L,
        joinCodeRotatedAt = 0L,
        publishedAt = 0L,
        archivedAt = 0L,
    )

    /** Publicar (o desarchivar / reabrir). */
    private fun toPublished(course: Course, now: Long, joinCode: String?): Course {
        val code = JoinCode.normalize(joinCode ?: course.joinCode)
        val errors = mutableListOf<String>()
        if (course.name.isBlank()) {
            errors += "La cátedra necesita un nombre antes de publicarse."
        }
        if (course.code.isBlank()) {
            errors += "La cátedra necesita un código de materia antes de publicarse."
        }
        if (code.isBlank()) {
            errors += "La cátedra necesita un código de inscripción."
        } else if (!JoinCode.isValid(code)) {
            errors += "El código de inscripción debe tener ${JoinCode.LENGTH} caracteres."
        }
        // Reabrir una cursada cerrada exige una clave nueva: la anterior venció.
        if (course.status == CourseStatus.CERRADA &&
            course.apiKeyExpiresAt in 1..now
        ) {
            errors += "La clave de la institución venció: pegá la clave nueva antes de reabrir."
        }
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString(" "))

        val rotated = code != course.joinCode
        return course.copy(
            joinCode = code,
            joinCodeIssuedAt = if (rotated) now else course.joinCodeIssuedAt,
            joinCodeRotatedAt = if (rotated) now else course.joinCodeRotatedAt,
            publishedAt = if (course.publishedAt > 0L) course.publishedAt else now,
            startedAt = 0L,
            closedAt = 0L,
            archivedAt = 0L,
        )
    }

    /** Iniciar la cursada: se materializan las fechas y la ventana de la clave. */
    private fun toInProgress(course: Course, now: Long): Course {
        val errors = course.validateForStart()
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString(" "))

        val start = if (course.termStartAt > 0L) course.termStartAt else now
        val end = if (course.termEndAt > 0L) {
            course.termEndAt
        } else {
            course.termInfo.defaultEnd()?.toEpochMillis() ?: now
        }
        // Sin vencimiento propio, la clave vive exactamente lo que la cursada.
        val keyExpiry = if (course.apiKeyExpiresAt > 0L) course.apiKeyExpiresAt else end
        return course.copy(
            termStartAt = start,
            termEndAt = end,
            apiKeyExpiresAt = keyExpiry,
            startedAt = now,
        )
    }

    /** Cerrar la cursada: la clave de la institución vence en este instante. */
    private fun toClosed(course: Course, now: Long): Course = course.copy(
        closedAt = now,
        termEndAt = if (course.termEndAt > 0L) course.termEndAt else now,
        // La clave muere con la cursada.
        apiKeyExpiresAt = now,
        // El secreto no queda dando vueltas en el JSON.
        joinCode = "",
        joinCodeIssuedAt = 0L,
    )

    /** Archivar: conserva todo, invalida el código. */
    private fun toArchived(course: Course, now: Long): Course = course.copy(
        archivedAt = now,
        joinCode = "",
        joinCodeIssuedAt = 0L,
        joinCodeRotatedAt = 0L,
    )
}
```

### 1.5 Permisos por rol

`CourseLifecycle.transition` exige `CourseRole.TITULAR`. Los ayudantes
(`AYUDANTE`) pueden subir materiales y ver la analítica (`canDesign`), pero no
mueven el estado de la cátedra: abrir/cerrar la inscripción es una decisión
administrativa.

---

## 2. El `joinCode`

### 2.1 Formato, longitud y entropía

- Alfabeto: **base32 sin caracteres ambiguos** — `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`
  (32 símbolos; fuera `0`, `1`, `I`, `O`, que se confunden con `D`, `L`, `1`).
- Longitud: **8 caracteres** → 8 × 5 bits = **40 bits ≈ 1,1 × 10¹²** combinaciones.
- Se muestra con un guion para leerlo de un pizarrón: `ABCD-EFGH`; **se guarda sin guion**
  y **siempre en mayúsculas y normalizado**.

Con 1.000 cátedras simultáneas, la probabilidad de colisión por el birthday
problem es ~4,5 × 10⁻⁷: despreciable, pero **igual se chequea** (§2.4).

### 2.2 Generación

```kotlin
package kitoru.domain

import kotlin.random.Random

/**
 * Códigos de inscripción de las cátedras.
 *
 * Formato: 8 caracteres de un base32 sin caracteres ambiguos (`0`, `1`, `I`,
 * `O` quedan afuera), siempre en mayúsculas. Se guardan **normalizados**
 * (sin guiones ni espacios) para que la comparación sea trivial.
 */
object JoinCode {

    /** Alfabeto base32 sin caracteres ambiguos. */
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** Cantidad de caracteres del código (8 × 5 bits = 40 bits ≈ 1,1e12). */
    const val LENGTH = 8

    /** Intentos antes de rendirse si el código choca con otra cátedra. */
    const val MAX_ATTEMPTS = 20

    /** Genera un código aleatorio. */
    fun generate(random: Random = Random.Default, length: Int = LENGTH): String {
        require(length > 0) { "El código debe tener al menos un carácter." }
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Normaliza lo que el usuario tipea: mayúsculas, sin espacios ni guiones. */
    fun normalize(raw: String): String =
        raw.trim().uppercase().filter { it in ALPHABET }

    /** Formato para mostrar y dictar: "ABCD-EFGH". */
    fun format(raw: String): String = normalize(raw).chunked(4).joinToString("-")

    /** ¿El código tiene el formato esperado? */
    fun isValid(raw: String): Boolean = normalize(raw).length == LENGTH

    /** Versión enmascarada ("AB••••••") para contexts donde no va el secreto. */
    fun mask(raw: String): String {
        val n = normalize(raw)
        if (n.isEmpty()) return ""
        return n.take(2) + "•".repeat((n.length - 2).coerceAtLeast(0))
    }
}

/**
 * Genera códigos de inscripción **únicos** entre las cátedras activas.
 *
 * La unicidad se chequea contra el repositorio (no contra una lista en
 * memoria) porque la app puede tener varias ventanas abierta.
 */
class JoinCodeGenerator(
    private val courses: CourseRepository,
    private val random: Random = Random.Default,
) {
    /**
     * @param excludeCourseId cátedra que se está editando (su código actual
     *   no cuenta como choque).
     */
    suspend fun generateUnique(excludeCourseId: String? = null): String {
        repeat(JoinCode.MAX_ATTEMPTS) {
            val candidate = JoinCode.generate(random)
            val clash = courses.findByJoinCode(candidate)
            if (clash == null || clash.id == excludeCourseId) return candidate
        }
        throw IllegalStateException(
            "No se pudo generar un código de inscripción único. Intentá de nuevo en un momento.",
        )
    }
}
```

> `Random` es inyectable → el test puede fijar la semilla y verificar el
> formato y la unicidad de forma determinista.

### 2.3 Permanente vs de un solo uso

| | Código de la cátedra (`Course.joinCode`) | Invitación personal (`EnrollmentInvite`) |
|---|---|---|
| Vida | Permanente mientras la cátedra está `PUBLICADA`/`EN_CURSADA` | De un solo uso (o `maxUses`) |
| Se muestra | Al docente, en "Compartir" | Al docente, una vez por invitado |
| Al anotarse | Se **consume** (queda el `Enrollment`) | Se consume el uso (`uses++`) |
| Rotación | Manual (`rotateJoinCode`) | `revokedAt` la anula |
| Persistencia | En `courses.json` (junto a la cátedra) | En `invites.json` (entidad propia) |

El `joinCode` es el **código de la cursada**: el docente lo dicta en clase y
todos los alumnos usan el mismo. Las invitaciones de un solo uso son para el
caso "mandale este código a Juan", que es opcional y no cambia el modelo.

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Invitación de un solo uso a una cátedra.
 *
 * Es opcional: el flujo principal es el [Course.joinCode] permanente. Sirve
 * para repartir acceso individual (alumno que se incorpora tarde, alumno de
 * otro curso, ayudante que además quiere chatear) sin exponer el código de
 * la cursada.
 */
@Serializable
data class EnrollmentInvite(
    val id: String,
    val courseId: String,
    /** Código con el mismo alfabeto que [JoinCode]. */
    val code: String = "",
    val createdAt: Long = 0L,
    /** Vencimiento de la invitación (epoch millis). 0 = sin vencimiento. */
    val expiresAt: Long = 0L,
    /** Usos permitidos (1 = un solo uso). */
    val maxUses: Int = 1,
    /** Usos consumidos. */
    val uses: Int = 0,
    /** Ids de los usuarios que la usaron. */
    val usedBy: List<String> = emptyList(),
    /** Si fue anulada. */
    val revokedAt: Long = 0L,
) {
    /** ¿La invitación se puede usar ahora mismo? */
    fun isUsable(now: Long): Boolean =
        revokedAt == 0L &&
            uses < maxUses &&
            (expiresAt == 0L || expiresAt > now)

    /** Usos que quedan. */
    val remainingUses: Int get() = (maxUses - uses).coerceAtLeast(0)
}
```

### 2.4 Colisiones entre dos cátedras

1. Al generar, `JoinCodeGenerator` busca con `CourseRepository.findByJoinCode(code)`
   (comparación sobre el código **normalizado**).
2. Si choca con otra cátedra, genera otro (hasta 20 intentos).
3. Si se agotan los intentos → `IllegalStateException` en español. No se
   "roba" el código de otra cátedra ni se degrada a un código más corto
   (un código de 7 chars son 35 bits: sólo como último recurso explícito).
4. **El código se libera cuando la cátedra se archiva o se cierra** (las
   transiciones lo vacían). Por eso un código archivado nunca bloquea a otro.

Para que esto funcione hace falta un método nuevo en el repositorio:

```kotlin
interface CourseRepository {
    suspend fun list(): List<Course>
    suspend fun get(id: String): Course?

    /** Busca por código de inscripción normalizado (sin guiones, mayúsculas). */
    suspend fun findByJoinCode(code: String): Course?

    suspend fun save(course: Course)

    /** Purga definitiva (borra materiales, chats e inscripciones). Ver §4.3. */
    suspend fun delete(id: String)
}
```

Implementación:

```kotlin
override suspend fun findByJoinCode(code: String): Course? {
    val wanted = JoinCode.normalize(code)
    if (wanted.isEmpty()) return null
    return all().firstOrNull { JoinCode.normalize(it.joinCode) == wanted }
}
```

### 2.5 Rotación

`CourseLifecycle.rotateJoinCode(course, newCode)` (§1.4):

- Sólo se puede rotar en `PUBLICADA`/`EN_CURSADA`.
- El código anterior **deja de funcionar en el acto** (`joinCode` se reemplaza;
  no hay lista de códigos viejos válidos).
- `joinCodeRotatedAt` guarda el instante, para la UI ("rotado hace 2 días").
- Las inscripciones existentes **no se caen**: dependen del `Enrollment`.

Caso típico: el docente фильtra el código en un grupo de WhatsApp →
rota → el que lo tenía ya no puede anotarse.

### 2.6 ¿El alumno puede dejar de ver el código?

Sí, y de hecho **el alumno nunca lo ve**:

- `joinCode` es un **secreto del docente**. Sólo se muestra en las pantallas de
  diseño (que exigen `CourseRole.canDesign`) y se copia al portapapeles.
- Los view models del alumno (`CourseStudentView`) **no incluyen** el campo.
- Una vez anotado, el alumno deja de tener el código: la pantalla de la cátedra
  del alumno muestra "Estás anotado" y el nombre de la materia, nada más.
- El docente puede dejarlo de ver también: al archivar o cerrar la cátedra el
  código se vacía del JSON, y el botón "Compartir" deja de existir.

```kotlin
/** Vista de la cátedra que se le arma al alumno. No lleva el código. */
data class CourseStudentView(
    val id: String,
    val displayName: String,
    val status: CourseStatus,
    val progress: CourseProgress,
    val role: CourseRole,
    val enrolled: Boolean,
)
```

---

## 3. La cursada (`term`) y el vencimiento de la clave de API

### 3.1 Modelo año/cuatrimestre

La `term` actual es un `String` libre (`"2026-1"`). Se **mantiene como `String`**
—es un campo serializado, cambiarle el tipo rompe los JSON viejos— y se le
agrega una vista estructurada **derivada**:

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable
import kitoru.platform.CivilDate

/**
 * Cursada: año + cuatrimestre.
 *
 * No se guarda dentro de [Course] (allí vive el `term: String` canónico, por
 * compatibilidad con los JSON existentes); es una **vista derivada**:
 * `Course.termInfo`.
 */
@Serializable
data class Term(
    /** Año (ej. 2026). 0 = sin definir. */
    val year: Int = 0,
    /** 1 = primer cuatrimestre (o semestre), 2 = segundo. 0 = sin definir. */
    val half: Int = 0,
) {
    /** Etiqueta canónica: "2026-1". */
    val label: String
        get() = when {
            year <= 0 -> ""
            half == 1 -> "$year-1"
            half == 2 -> "$year-2"
            else -> "$year"
        }

    /** ¿Está la cursada bien definida? */
    val isDefined: Boolean get() = year > 0 && half in 1..2

    /** Inicio por defecto del cuatrimestre (10 de marzo). */
    fun defaultStart(): CivilDate? = when {
        year <= 0 || half != 1 -> null
        else -> CivilDate(year, 3, 10)
    }

    /** Fin por defecto del cuatrimestre (30 de junio / 30 de noviembre). */
    fun defaultEnd(): CivilDate? = when {
        year <= 0 || half !in 1..2 -> null
        half == 1 -> CivilDate(year, 6, 30)
        else -> CivilDate(year, 11, 30)
    }

    companion object {
        private val FULL = Regex("""(\d{4})\s*[-/\s.]?\s*([12])""")
        private val YEAR_ONLY = Regex("""\d{4}""")

        /** Interpreta "2026-1", "2026/1", "1C-2026", "2026". */
        fun parse(raw: String): Term {
            val text = raw.trim().uppercase()
            val m = FULL.find(text)
            if (m != null) {
                return Term(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
            val y = YEAR_ONLY.find(text)
            return if (y != null) Term(y.value.toInt(), 0) else Term()
        }
    }
}
```

> Los defaults (10/3 – 30/6 y 1/8 – 30/11) son los habituales de universities
> argentinas; se pueden cambiar en un solo lugar. Cuando la cátedra **inicia
> la cursada** el default se **materializa** en `termStartAt`/`termEndAt`, así
> que cambiar la tabla de defaults después no altera las cátedras viejas.

### 3.2 Fechas sin `java.time`

`commonMain` no tiene `java.time` (Android por debajo de API 26) y no queremos
sumar `kotlinx-datetime` todavía. Un tipo mínimo y puro:

```kotlin
package kitoru.platform

/**
 * Fecha civil (año/mes/día) sin hora ni zona horaria.
 *
 * `commonMain` no tiene `java.time` y no queremos sumar `kotlinx-datetime`
 * todavía: este tipo cubre lo que el dominio necesita (inicio y fin de la
 * cursada, "días que faltan") con aritmética entera pura.
 *
 * Conversión epoch-day con el algoritmo de Howard Hinnant
 * (`days_from_civil` / `civil_from_days`), que es exacto para el calendario
 * gregoriano.
 */
data class CivilDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CivilDate> {

    init {
        require(month in 1..12) { "Mes inválido: $month" }
        require(day in 1..31) { "Día inválido: $day" }
        require(year in 1..9999) { "Año inválido: $year" }
    }

    /** Días desde 1970-01-01 (negativo para fechas anteriores). */
    fun toEpochDay(): Int {
        val y = year - if (month <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Medianoche UTC del día (epoch millis). */
    fun toEpochMillis(): Long = toEpochDay() * MILLIS_PER_DAY

    fun plusDays(days: Int): CivilDate = ofEpochDay(toEpochDay() + days)

    override fun compareTo(other: CivilDate): Int =
        toEpochDay().compareTo(other.toEpochDay())

    override fun toString(): String = buildString {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
    }

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        fun of(year: Int, month: Int, day: Int): CivilDate = CivilDate(year, month, day)

        /** Fecha a partir de los días desde epoch. */
        fun ofEpochDay(epochDay: Int): CivilDate {
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = mp + if (mp < 10) 3 else -9
            return CivilDate(y + if (m <= 2) 1 else 0, m, d)
        }

        /** Parsea "YYYY-MM-DD". */
        fun parse(text: String): CivilDate? {
            val parts = text.trim().split("-")
            if (parts.size != 3) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val d = parts[2].toIntOrNull() ?: return null
            return runCatching { CivilDate(y, m, d) }.getOrNull()
        }
    }
}
```

### 3.3 Cómo se representa el vencimiento de la clave de API

El enunciado dice: *la institución emite keys que vencen al terminar la
cursada*. En el dominio eso son **dos cosas distintas** que hay que separar:

1. **La credencial** (la key y su ventana de validez) — vive en
   `ModelPolicy.apiKey` + un nuevo `Course.apiKeyExpiresAt`.
2. **El techo de la cursada** — `termEndAt`.

Regla: **la clave nunca puede vivir más allá del fin de la cursada**.

```kotlin
package kitoru.domain

import kitoru.platform.CivilDate

/** Estado de la credencial que da acceso al servidor de la institución. */
enum class KeyLeaseStatus(val label: String) {
    /** La cátedra no tiene key (trabaja con IA local o todavía no la pegó). */
    SIN_CLAVE("Sin clave"),

    /** Vigente. */
    ACTIVA("Activa"),

    /** Vigente pero vence en menos de una semana: avisar al docente. */
    POR_VENCER("Por vencer"),

    /** Vencida: los alumnos no pueden chatear contra el servidor. */
    VENCIDA("Vencida"),
}

/**
 * Vista derivada de la credencial de la institución para una cátedra.
 *
 * No se serializa: se calcula desde [Course.modelPolicy] y
 * [Course.apiKeyExpiresAt].
 */
data class KeyLease(
    /** ¿Hay key cargada? */
    val hasKey: Boolean,
    /** Vencimiento efectivo (epoch millis). 0 = sin vencimiento conocido. */
    val expiresAt: Long,
) {
    /** Situación actual. */
    fun status(now: Long): KeyLeaseStatus = when {
        !hasKey -> KeyLeaseStatus.SIN_CLAVE
        expiresAt <= 0L -> KeyLeaseStatus.ACTIVA
        now >= expiresAt -> KeyLeaseStatus.VENCIDA
        expiresAt - now <= EXPIRY_WARNING_MS -> KeyLeaseStatus.POR_VENCER
        else -> KeyLeaseStatus.ACTIVA
    }

    /** Días que faltan para vencer; -1 si no hay vencimiento conocido. */
    fun daysLeft(now: Long): Int =
        if (expiresAt <= 0L) -1
        else ((expiresAt - now) / CivilDate.MILLIS_PER_DAY).toInt().coerceAtLeast(0)

    /** ¿Se puede usar la key para chatear? */
    fun isUsable(now: Long): Boolean = status(now) != KeyLeaseStatus.VENCIDA

    companion object {
        /** Ventana de aviso antes del vencimiento (7 días). */
        const val EXPIRY_WARNING_MS = 7L * 24 * 60 * 60 * 1000
    }
}
```

Y en `Course`:

```kotlin
    /**
     * Vencimiento efectivo de la clave de la institución.
     *
     * Es el mínimo entre el vencimiento que declara la key y el fin de la
     * cursada: **la credencial no puede sobrevivir a la cursada**.
     */
    val effectiveKeyExpiry: Long
        get() = when {
            apiKeyExpiresAt <= 0L -> effectiveTermEndAt
            effectiveTermEndAt <= 0L -> apiKeyExpiresAt
            else -> minOf(apiKeyExpiresAt, effectiveTermEndAt)
        }

    /** Datos de la credencial, para la UI. */
    val keyLease: KeyLease
        get() = KeyLease(
            hasKey = modelPolicy.apiKey.isNotBlank(),
            expiresAt = effectiveKeyExpiry,
        )
```

Reglas del ciclo:

| Momento | `apiKeyExpiresAt` |
|---|---|
| Cátedra recién creada | `0` (sin key) |
| El docente pega la key | `0` → la institución avisa el vencimiento y el docente lo carga |
| `BORRADOR → PUBLICADA` | se mantiene |
| `PUBLICADA → EN_CURSADA` | si es `0`, se materializa `= termEndAt` (la key vive lo que la cursada) |
| `EN_CURSADA → CERRADA` | `= now` (la clave muere con la cursada) |
| `CERRADA → PUBLICADA` (reabrir) | hay que pegar **key nueva**; si no, la transición falla |

La UI muestra un chip: `Clave: Activa` / `Clave: Por vencer (5 días)` /
`Clave: Vencida — los alumnos no pueden chatear` / `Sin clave (IA local)`.

---

## 4. Multi-cátedra, archivado y borrado

### 4.1 Un usuario, varias cátedras

Sí. El modelo ya lo soporta: el rol **no** está en `UserProfile`, está en
`Enrollment` (`courseId` + `userId` + `role`). Un mismo `userId` puede ser:

- `TITULAR` en "Inteligencia Artificial" y `ALUMNO` en "Matemática Discreta".
- `ALUMNO` en 5 materias del mismo cuatrimestre.

Falta un método en el repositorio de inscripciones para la unicidad:

```kotlin
interface EnrollmentRepository {
    suspend fun listForCourse(courseId: String): List<Enrollment>
    suspend fun listForUser(userId: String): List<Enrollment>

    /** Busca la inscripción de un usuario en una cátedra (clave única del par). */
    suspend fun find(courseId: String, userId: String): Enrollment?

    suspend fun get(id: String): Enrollment?
    suspend fun save(enrollment: Enrollment)
    suspend fun delete(id: String)
}
```

Y la pantalla de "Mis cátedras" del alumno las agrupa:
**Activas** (`status.isVisibleToStudents` y no archivada) / **Anteriores**
(archivadas y cerradas, sólo lectura).

### 4.2 Archivar: se conserva todo

Al archivar (`→ ARCHIVADA`):

| Dato | Qué pasa |
|---|---|
| Materiales (`Material`) | **Se conservan**. El índice se puede dejar en disco; si se quiere, se regenera al desarchivar |
| Chats (`ChatThread`, `ChatMessage`) | **Se conservan**, en sólo lectura |
| Analítica | **Se conserva** y sigue consultable por el docente |
| Inscripciones (`Enrollment`) | **Se conservan** |
| `joinCode` | **Se invalida** (se vacía el campo) |
| Key de API | Se conserva en la config, pero la cursada queda cerrada → no se usa |

El docente puede **desarchivar** y todo vuelve como estaba. Archivar **no**
borra nada: es la operación por defecto cuando el docente quiere terminar una
cursada y conservar el material para reutilizarlo el año siguiente.

El alumno ve la cátedra archivada en una sección aparte, en sólo lectura
(puede volver a leer sus chats y los apuntes, pero no chatear ni subir nada).

### 4.3 Borrar: purga explícita e irreversible

Borrar es una **purga** y requiere una confirmación explícita (escribir el
nombre de la cátedra). Borra:

1. La cátedra de `courses.json`.
2. Las inscripciones (`enrollments.json`).
3. Los hilos y mensajes (`threads.json`, `messages.json`).
4. Los materiales y la carpeta `KitoruPaths.coursesDir/<courseId>` del disco.
5. Las invitaciones (`invites.json`).

En la UI:

```kotlin
/**
 * Purga definitiva de una cátedra.
 *
 * Sólo la ofrece el titular, sobre una cátedra **archivada o cerrada**
 * (nunca sobre una en cursada), y exige confirmación escrita.
 */
suspend fun purgeCourse(courseId: String) {
    // 1. Verificar que está archivada o cerrada.
    // 2. materialsRepository.deleteForCourse(courseId)
    // 3. messageRepository.deleteByCourse(courseId) + threadRepository.deleteForCourse(courseId)
    // 4. enrollmentRepository.deleteForCourse(courseId)
    // 5. inviteRepository.deleteForCourse(courseId)
    // 6. fs.delete(KitoruPaths.coursesDir.resolve(courseId))
    // 7. courseRepository.delete(courseId)
}
```

Regla de producto: **el botón "Eliminar" de la UI es "Archivar"**. El borrado
real está escondido detrás de "Archivar → Eliminar definitivamente" y avisa
que los chats y la analítica se pierden.

Recomendación: ofrecer **exportar la analítica a JSON/CSV antes de archivar**
(la analítica es el producto del docente; si va a un Drive compartido, mejor
que dentro de la app).

### 4.4 Qué pasa con los datos de la cursada al cerrar

Cerrar **no** borra nada: congela. Los chats quedan en sólo lectura (el
alumno puede leer, no escribir), la analítica queda congelada en los números
que ya había (el `AnalyticsAnalyzer` sigue calculando lo mismo porque no
entran mensajes nuevos) y la clave de la institución vence.

---

## 5. Propiedades derivadas para la UI

| Propiedad | Tipo | Para qué |
|---|---|---|
| `shortName` | `String` | Nombre corto en las listas: `"IA-2026-1"` o, si no hay código, el nombre |
| `displayName` | `String` | Título de cabecera: `"IA-2026-1 · Inteligencia Artificial"` |
| `status` | `CourseStatus` | Chip de estado (label + description) |
| `statusLabel` | `String` | `"Publicada"` |
| `acceptsEnrollment` | `Boolean` | Mostrar el campo "código de inscripción" |
| `allowsChat` | `Boolean` | Habilitar el chat |
| `isReadOnly` | `Boolean` | Banner de "cátedra cerrada" |
| `isVisibleToStudents` | `Boolean` | Filtrar la lista del alumno |
| `termInfo` | `Term` | Mostrar `"2026-1"` y calcular los defaults |
| `effectiveTermStartAt` / `effectiveTermEndAt` | `Long` | Fechas de la cursada (con default por cuatrimestre) |
| `progress(now)` | `CourseProgress` | "Semana 8 de 16", barra de avance |
| `keyLease` / `keyLease.status(now)` | `KeyLease` / `KeyLeaseStatus` | Chip de la clave |
| `daysLeft` (dentro de `progress`) | `Int` | "Faltan 12 días" |

`CourseProgress`:

```kotlin
/**
 * Progreso de la cursada (para la UI: "Semana 8 de 16").
 */
data class CourseProgress(
    /** ¿Arrancó la cursada? */
    val started: Boolean,
    /** ¿Terminó? */
    val finished: Boolean,
    /** Fracción de la cursada transcurrida (0..1). */
    val fraction: Double,
    /** Semana actual (1..totalWeeks). */
    val week: Int,
    /** Duración total en semanas. */
    val totalWeeks: Int,
    /** Días que faltan para el fin de la cursada. */
    val daysLeft: Int,
) {
    /** Etiqueta lista para pintar. */
    val label: String
        get() = when {
            !started -> "Sin empezar"
            finished -> "Terminada"
            totalWeeks <= 0 -> "En curso"
            else -> "Semana $week de $totalWeeks"
        }
}
```

Y su cálculo en `Course`:

```kotlin
    /**
     * Progreso de la cursada a un instante dado.
     *
     * Usa las fechas de la cursada; si no hay, cae a las marcas del ciclo de
     * vida (`startedAt`/`closedAt`).
     */
    fun progress(now: Long): CourseProgress {
        val start = if (termStartAt > 0L) termStartAt else startedAt
        val end = if (termEndAt > 0L) termEndAt else closedAt
        if (start <= 0L || end <= 0L || end <= start) {
            return CourseProgress(
                started = startedAt > 0L,
                finished = status == CourseStatus.CERRADA,
                fraction = 0.0,
                week = 0,
                totalWeeks = 0,
                daysLeft = 0,
            )
        }
        val total = end - start
        val elapsed = (now - start).coerceIn(0L, total)
        val totalWeeks = ((total + WEEK_MS - 1) / WEEK_MS).toInt().coerceAtLeast(1)
        val week = ((elapsed + WEEK_MS - 1) / WEEK_MS).toInt().coerceIn(0, totalWeeks)
        val daysLeft = ((end - now).coerceAtLeast(0L) / CivilDate.MILLIS_PER_DAY).toInt()
        return CourseProgress(
            started = true,
            finished = status == CourseStatus.CERRADA,
            fraction = elapsed.toDouble() / total.toDouble(),
            week = week,
            totalWeeks = totalWeeks,
            daysLeft = daysLeft,
        )
    }
```

> **Lo que NO va en `Course`:** la cantidad de alumnos (`memberCount`) ni la
> actividad reciente. Son datos de otras tablas: se calculan en el view model
> (`CourseCard(course, members = enrollments.size, analytics = ...)`) para no
> duplicar y que se desincronicen.

---

## 6. El `Course` final

Campos que **faltan hoy** y hacen falta para todo lo anterior:

| Campo | Tipo | Default | Para qué |
|---|---|---|---|
| `status` | `CourseStatus` | `BORRADOR` | Estado del ciclo de vida |
| `updatedAt` | `Long` | `0L` | Última modificación (auditoría y "cambios recientes") |
| `joinCodeIssuedAt` | `Long` | `0L` | Cuándo se emitió el código actual |
| `joinCodeRotatedAt` | `Long` | `0L` | Cuándo se rotó por última vez |
| `publishedAt` | `Long` | `0L` | Marca de publicación |
| `startedAt` | `Long` | `0L` | Marca de inicio de cursada |
| `closedAt` | `Long` | `0L` | Marca de cierre |
| `archivedAt` | `Long` | `0L` | Marca de archivado |
| `termStartAt` | `Long` | `0L` | Inicio de la cursada (epoch millis) |
| `termEndAt` | `Long` | `0L` | Fin de la cursada (epoch millis) |
| `apiKeyExpiresAt` | `Long` | `0L` | Vencimiento de la clave de la institución |
| `code` | `String` | `""` | Default (antes era obligatorio: un JSON viejo sin `code` no cargaba) |
| `name` | `String` | `""` | Default (idem) |

Además, `Course.topK` y `Course.systemPrompt` **duplican** lo que ya está en
`ModelPolicy`. Se conservan (compatibilidad) pero pasan a ser **legacy**: la
fuente de verdad pasa a ser `modelPolicy`, y la migración (§7.3) copia los
valores viejos hacia la policy.

### 6.1 Bloque final (copiar-pegar)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable
import kitoru.platform.CivilDate

/** Una semana en milisegundos (para el cálculo del progreso de la cursada). */
private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * La cátedra: el espacio de una materia.
 *
 * Es la unidad que el profesor titular diseña (materiales, configuración base
 * del modelo, instrucciones) y a la que los alumnos se inscriben.
 *
 * Ciclo de vida: ver [CourseStatus] y [CourseLifecycle].
 *
 * Regla de serialización: **no cambiar el tipo de un campo existente**. Los
 * campos nuevos van con default para que los JSON viejos sigan cargando, y
 * `term` sigue siendo `String` a propósito (ver [termInfo]).
 */
@Serializable
data class Course(
    /** Id estable de la cátedra. Único en toda la app. */
    val id: String,

    /** Código de la materia (ej. "IA"). */
    val code: String = "",

    /** Nombre de la materia (ej. "Inteligencia Artificial"). */
    val name: String = "",

    /**
     * Cursada (ej. "2026-1"). Canónico: [Term.label].
     *
     * Sigue siendo `String` (y no un `Term`) para no romper los JSON
     * existentes: cambiar el tipo de un campo serializado hace fallar la
     * carga de todo el archivo. La vista estructurada es [termInfo].
     */
    val term: String = "",

    val description: String = "",

    val createdAt: Long = 0L,

    /** Última modificación (epoch millis). */
    val updatedAt: Long = 0L,

    /** Estado del ciclo de vida. */
    @Serializable(with = CourseStatusSerializer::class)
    val status: CourseStatus = CourseStatus.BORRADOR,

    /**
     * Código con el que los alumnos se anotan a la cátedra.
     *
     * Secreto del docente: el alumno nunca lo ve. Se guarda normalizado
     * (mayúsculas, sin guiones) y se vacía al cerrar o archivar la cátedra.
     */
    val joinCode: String = "",

    /** Cuándo se emitió el código actual (epoch millis). */
    val joinCodeIssuedAt: Long = 0L,

    /** Cuándo se rotó el código por última vez (epoch millis). */
    val joinCodeRotatedAt: Long = 0L,

    // ---- Marcas de tiempo del ciclo de vida (0 = todavía no ocurrió) ----

    /** Cuándo se publicó. */
    val publishedAt: Long = 0L,

    /** Cuándo arrancó la cursada. */
    val startedAt: Long = 0L,

    /** Cuándo se cerró la cursada. */
    val closedAt: Long = 0L,

    /** Cuándo se archivó. */
    val archivedAt: Long = 0L,

    // ---- Cursada y credencial ----

    /** Inicio de la cursada (epoch millis, UTC). 0 = sin definir. */
    val termStartAt: Long = 0L,

    /** Fin de la cursada (epoch millis, UTC). 0 = sin definir. */
    val termEndAt: Long = 0L,

    /**
     * Vencimiento de la clave de API emitida por la institución
     * (epoch millis). 0 = sin vencimiento conocido.
     *
     * La clave nunca vive más allá del fin de la cursada: ver
     * [effectiveKeyExpiry].
     */
    val apiKeyExpiresAt: Long = 0L,

    // ---- Configuración ----

    /** Configuración base del modelo que impone la cátedra. */
    val modelPolicy: ModelPolicy = ModelPolicy(),

    /**
     * Instrucciones del sistema por defecto de la cátedra.
     *
     * LEGACY: la fuente de verdad es [ModelPolicy.systemPrompt]. Se conserva
     * por compatibilidad y la migración copia su valor a la policy.
     */
    val systemPrompt: String = "",

    /**
     * Tope de fragmentos que el RAG recupera.
     *
     * LEGACY: la fuente de verdad es [ModelPolicy.topK].
     */
    val topK: Int = 5,

    /** Tamaño del chunk al indexar los materiales. */
    val chunkSize: Int = 1200,

    /** Si la analítica de los chats está habilitada para esta cátedra. */
    val analyticsEnabled: Boolean = true,

    /** Si el docente permite que el alumno use IA local en su dispositivo. */
    val allowLocalModels: Boolean = true,
) {

    // ------------------------------------------------------------------ UI --

    /** Nombre corto para las listas: el código si hay, si no el nombre. */
    val shortName: String get() = code.ifBlank { name }

    /** Título de cabecera: "IA · Inteligencia Artificial". */
    val displayName: String get() = if (code.isBlank()) name else "$code · $name"

    /** Etiqueta del estado ("Publicada"). */
    val statusLabel: String get() = status.label

    // ----------------------------------------------------------- Permisos --

    /** ¿Se puede anotar un alumno con el código de inscripción? */
    val acceptsEnrollment: Boolean get() = status.acceptsEnrollment

    /** ¿Se puede chatear en esta cátedra? */
    val allowsChat: Boolean get() = status.allowsChat

    /** ¿Todo es de sólo lectura? */
    val isReadOnly: Boolean get() = status.isReadOnly

    /** ¿Está visible en la lista principal del alumno? */
    val isVisibleToStudents: Boolean get() = status.visibleToStudents

    // ------------------------------------------------------------ Cursada --

    /** Cursada estructurada, derivada de [term]. */
    val termInfo: Term get() = Term.parse(term)

    /** Inicio de la cursada: el declarado o el default del cuatrimestre. */
    val effectiveTermStartAt: Long
        get() = when {
            termStartAt > 0L -> termStartAt
            else -> termInfo.defaultStart()?.toEpochMillis() ?: 0L
        }

    /** Fin de la cursada: el declarado o el default del cuatrimestre. */
    val effectiveTermEndAt: Long
        get() = when {
            termEndAt > 0L -> termEndAt
            else -> termInfo.defaultEnd()?.toEpochMillis() ?: 0L
        }

    /**
     * Vencimiento efectivo de la clave de la institución.
     *
     * Es el mínimo entre el vencimiento que declara la key y el fin de la
     * cursada: **la credencial no puede sobrevivir a la cursada**.
     */
    val effectiveKeyExpiry: Long
        get() = when {
            apiKeyExpiresAt <= 0L -> effectiveTermEndAt
            effectiveTermEndAt <= 0L -> apiKeyExpiresAt
            else -> minOf(apiKeyExpiresAt, effectiveTermEndAt)
        }

    /** Datos de la credencial, para la UI. */
    val keyLease: KeyLease
        get() = KeyLease(
            hasKey = modelPolicy.apiKey.isNotBlank(),
            expiresAt = effectiveKeyExpiry,
        )

    // ----------------------------------------------------------- Progreso --

    /** Progreso de la cursada a un instante dado. */
    fun progress(now: Long): CourseProgress {
        val start = if (termStartAt > 0L) termStartAt else startedAt
        val end = if (termEndAt > 0L) termEndAt else closedAt
        if (start <= 0L || end <= 0L || end <= start) {
            return CourseProgress(
                started = startedAt > 0L,
                finished = status == CourseStatus.CERRADA,
                fraction = 0.0,
                week = 0,
                totalWeeks = 0,
                daysLeft = 0,
            )
        }
        val total = end - start
        val elapsed = (now - start).coerceIn(0L, total)
        val totalWeeks = ((total + WEEK_MILLIS - 1) / WEEK_MILLIS).toInt().coerceAtLeast(1)
        val week = ((elapsed + WEEK_MILLIS - 1) / WEEK_MILLIS).toInt()
            .coerceIn(0, totalWeeks)
        val daysLeft = ((end - now).coerceAtLeast(0L) / CivilDate.MILLIS_PER_DAY).toInt()
        return CourseProgress(
            started = true,
            finished = status == CourseStatus.CERRADA,
            fraction = elapsed.toDouble() / total.toDouble(),
            week = week,
            totalWeeks = totalWeeks,
            daysLeft = daysLeft,
        )
    }

    // -------------------------------------------------------- Validaciones --

    /**
     * Errores que impiden **publicar** (lista vacía = se puede publicar).
     *
     * Publicar no exige que el servidor esté listo: se puede abrir la
     * inscripción y configurar el modelo después.
     */
    fun validateForPublish(): List<String> {
        val errors = mutableListOf<String>()
        if (name.isBlank()) errors += "La cátedra necesita un nombre."
        if (code.isBlank()) errors += "La cátedra necesita un código de materia."
        if (joinCode.isBlank()) errors += "La cátedra necesita un código de inscripción."
        else if (!JoinCode.isValid(joinCode)) {
            errors += "El código de inscripción debe tener ${JoinCode.LENGTH} caracteres."
        }
        return errors
    }

    /**
     * Errores que impiden **iniciar la cursada**.
     *
     * Acá sí se exige que la configuración del modelo esté lista: si los
     * alumnos van a chatear, tiene que haber a qué chatear.
     */
    fun validateForStart(): List<String> {
        val errors = validateForPublish().toMutableList()
        errors += modelPolicy.validate()
        if (modelPolicy.mode == ModelMode.REMOTE &&
            !allowLocalModels &&
            modelPolicy.apiKey.isBlank()
        ) {
            errors += "Falta la clave de API de la institución."
        }
        return errors
    }
}
```

> Nota de implementación: la constante de una semana va **a nivel de archivo**
> (`private const val WEEK_MILLIS = ...`) y no en un `companion object`
> privado: el plugin de kotlinx.serialization genera `Course.serializer()` en
> el companion, y un companion privado lo dejaría inaccesible para
> `FileCourseRepository`.

### 6.2 Cambios en los repositorios

```kotlin
interface CourseRepository {
    suspend fun list(): List<Course>
    suspend fun get(id: String): Course?

    /** Busca por código de inscripción normalizado. */
    suspend fun findByJoinCode(code: String): Course?

    suspend fun save(course: Course)

    /** Purga definitiva (ver §4.3). */
    suspend fun delete(id: String)
}

interface EnrollmentRepository {
    suspend fun listForCourse(courseId: String): List<Enrollment>
    suspend fun listForUser(userId: String): List<Enrollment>

    /** Inscripción de un usuario en una cátedra (unicidad del par). */
    suspend fun find(courseId: String, userId: String): Enrollment?

    suspend fun get(id: String): Enrollment?
    suspend fun save(enrollment: Enrollment)
    suspend fun delete(id: String)
}
```

En `FileCourseRepository`, `list()` y `get()` aplican la normalización (§7.3):

```kotlin
class FileCourseRepository(fs: FileSystem) : FileBackedRepository<Course>(
    fs, "courses.json", Course.serializer(),
), CourseRepository {

    override suspend fun list(): List<Course> =
        all().map { CourseMigration.normalize(it) }
            .sortedBy { it.code.ifBlank { it.name } }

    override suspend fun get(id: String): Course? =
        all().firstOrNull { it.id == id }?.let { CourseMigration.normalize(it) }

    override suspend fun findByJoinCode(code: String): Course? {
        val wanted = JoinCode.normalize(code)
        if (wanted.isEmpty()) return null
        return all()
            .map { CourseMigration.normalize(it) }
            .firstOrNull { JoinCode.normalize(it.joinCode) == wanted }
    }

    override suspend fun save(course: Course) = put(CourseMigration.normalize(course))

    override suspend fun delete(id: String) = removeById(id)

    override fun idOf(item: Course): String = item.id
}
```

### 6.3 Caso de uso: anotarse con un código

```kotlin
package kitoru.domain

import kitoru.platform.nowMillis

/**
 * Anotación de un alumno a una cátedra con el código de inscripción.
 */
class JoinCourseUseCase(
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
) {
    /**
     * @param code código tipeado por el alumno (puede venir con guiones).
     * @param userId id del alumno.
     * @param displayName nombre para mostrar en la lista del docente.
     */
    suspend fun join(
        code: String,
        userId: String,
        displayName: String,
        now: Long = nowMillis(),
    ): Enrollment {
        val normalized = JoinCode.normalize(code)
        if (!JoinCode.isValid(normalized)) {
            throw IllegalArgumentException("El código de inscripción no es válido.")
        }
        val course = courses.findByJoinCode(normalized)
            ?: throw IllegalArgumentException("No hay ninguna cátedra con ese código.")
        if (!course.status.acceptsEnrollment) {
            throw IllegalStateException(
                "La cátedra ${course.displayName} no está abierta a inscripciones.",
            )
        }
        // Idempotente: si ya estabas anotado, no se duplica.
        enrollments.find(course.id, userId)?.let { return it }
        val enrollment = Enrollment(
            id = "enr|${course.id}|$userId",
            courseId = course.id,
            userId = userId,
            displayName = displayName,
            role = CourseRole.ALUMNO,
            joinedAt = now,
            visibility = ChatVisibility.METRICS_ONLY,
        )
        enrollments.save(enrollment)
        return enrollment
    }
}
```

> `visibility = METRICS_ONLY` por defecto: el docente ve **estadísticas y
> calidad**, no el texto, salvo que el alumno lo habilite. Es la posición
> conservative que exige la regla 6 del CONTEXTO.

---

## 7. Serialización y compatibilidad

### 7.1 Qué es opcional

`KitoruJson` (en `JsonStore.kt`) ya tiene:

```kotlin
val KitoruJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true   // compat hacia adelante
    encodeDefaults = true      // los campos nuevos se escriben explícitos
}
```

- **Compat hacia adelante** (un JSON nuevo lo lee una app vieja): la resuelve
  `ignoreUnknownKeys = true`. Los campos que la app vieja no conoce se
  descartan sin error.
- **Compat hacia atrás** (un JSON viejo lo lee la app nueva): la resuelve que
  **todo campo nuevo tenga default**. Con eso, un `courses.json` escrito antes
  de este diseño carga sin tocarlo.

Campos **obligatorios** (sin default) después de este cambio: **sólo `id`**.
`code` y `name` pasan a tener default `""` (hoy no lo tienen: un JSON viejo
sin `code` **no** cargaba, y como `JsonStore` traga la excepción, se perdía
toda la lista).

Campos opcionales (con default) y su default:

| Campo | Default |
|---|---|
| `code`, `name`, `term`, `description` | `""` |
| `createdAt`, `updatedAt`, `joinCodeIssuedAt`, `joinCodeRotatedAt` | `0L` |
| `publishedAt`, `startedAt`, `closedAt`, `archivedAt` | `0L` |
| `termStartAt`, `termEndAt`, `apiKeyExpiresAt` | `0L` |
| `status` | `CourseStatus.BORRADOR` |
| `modelPolicy` | `ModelPolicy()` |
| `systemPrompt`, `joinCode` | `""` |
| `topK` | `5` |
| `chunkSize` | `1200` |
| `analyticsEnabled`, `allowLocalModels` | `true` |

### 7.2 Reglas para no romper nada

1. **Nunca cambiar el tipo de un campo existente.** `term` sigue siendo `String`.
   Si alguna vez hace falta un `Term` estructurado *dentro* de `Course`, se
   agrega un campo **nuevo** (`termInfo: Term = Term()`) y el viejo queda como
   etiqueta legible.
2. **Nunca quitar un campo**; se marca `LEGACY` y se deja el default.
3. **Los enums serializados necesitan un serializer tolerante.** Un valor de
   enum desconocido lanza excepción en kotlinx (a diferencia de una clave
   desconocida). Por eso `CourseStatus` usa `CourseStatusSerializer`
   (§1.2). La misma regla debería aplicarse a `CourseRole` y `ChatVisibility`
   (pendiente, fuera de este documento).
4. **Enums: agregar valores es un cambio de schema.** Si mañana hace falta un
   estado nuevo, se agrega al enum y el serializer lo tolera en las versiones
   viejas (caen en `BORRADOR`), pero conviene subir un `schemaVersion`.

### 7.3 Migración de cátedras viejas

Una cátedra escrita con el formato anterior no tiene `status`. Como el default
es `BORRADOR`, hay que distinguir "cátedra nueva en borrador" de "cátedra vieja
ya en uso". Regla: **si tiene código de inscripción y `createdAt > 0`, estaba
publicada**.

```kotlin
package kitoru.domain

/**
 * Normaliza cátedras venidas de versiones viejas del formato.
 *
 * Se aplica al leer (list/get) y al guardar, así la migración queda
 * persistida en la primera escritura.
 */
object CourseMigration {

    fun normalize(course: Course): Course {
        var out = course

        // 1. Las cátedras anteriores a los estados: si tenían código de
        //    inscripción, estaban publicadas.
        if (out.status == CourseStatus.BORRADOR &&
            out.joinCode.isNotBlank() &&
            out.createdAt > 0L
        ) {
            out = out.copy(
                status = CourseStatus.PUBLICADA,
                publishedAt = if (out.publishedAt > 0L) out.publishedAt else out.createdAt,
            )
        }

        // 2. El código de inscripción se guarda normalizado.
        val normalizedCode = JoinCode.normalize(out.joinCode)
        if (normalizedCode != out.joinCode) {
            out = out.copy(joinCode = normalizedCode)
        }

        // 3. topK vivía en Course; ahora vive en ModelPolicy.
        val defaultPolicy = ModelPolicy()
        if (out.modelPolicy.topK == defaultPolicy.topK && out.topK != defaultPolicy.topK) {
            out = out.copy(modelPolicy = out.modelPolicy.copy(topK = out.topK))
        }

        // 4. systemPrompt vivía en Course; ahora vive en ModelPolicy.
        if (out.modelPolicy.systemPrompt.isBlank() && out.systemPrompt.isNotBlank()) {
            out = out.copy(modelPolicy = out.modelPolicy.copy(systemPrompt = out.systemPrompt))
        }

        return out
    }
}
```

### 7.4 Endurecer `JsonStore` (riesgo actual, ya existente)

Hoy `JsonStore._read` hace:

```kotlin
return try {
    KitoruJson.decodeFromString(serializer, text)
} catch (_: Exception) {
    // Un archivo corrupto no debe voltear la app: se ignora.
    empty()
}
```

Consecuencia: **un solo curso con un campo raro borra la lista completa de
cátedras en pantalla** (y el usuario no ve ningún error). Con los cambios de
este diseño el riesgo baja (defaults + serializer tolerante), pero conviene
arreglarlo:

1. Decodificar **elemento por elemento** (`JsonArray` + `decodeFromJsonElement`
   con `try/catch` por ítem) y conservar los que sirven.
2. Antes de pisar un archivo ilegible, guardarlo como
   `courses.json.corrupt-<timestamp>`.
3. Sumar a `KitoruJson`:
   ```kotlin
   val KitoruJson: Json = Json {
       prettyPrint = true
       ignoreUnknownKeys = true
       encodeDefaults = true
       coerceInputValues = true   // null → default, no excepción
       explicitNulls = false      // no escribir nulls: menos superficie de rotura
   }
   ```
   `coerceInputValues` es la clave: si una versión más nueva escribe
   `"apiKeyExpiresAt": null` (porque cambió el tipo a nullable), la versión
   vieja lo convierte en `0L` en vez de fallar.
4. `config/meta.json` con `{"schemaVersion": 3}` paraSaber con qué versión se
   escribió cada archivo y sostener migraciones explícitas.

### 7.5 Resumen de compatibilidad

| Escenario | Resultado |
|---|---|
| JSON viejo → app nueva | Carga. `status` se deduce (`PUBLICADA` si tenía código), `topK`/`systemPrompt` migran a la policy |
| JSON nuevo → app vieja | Carga. Los campos desconocidos se ignoran; un `status` desconocido cae en `BORRADOR` |
| JSON con `null` en un campo no-nullable | Con `coerceInputValues = true` cae en el default |
| JSON corrupto | Hoy: lista vacía. Propuesta: ítems válidos conservados + copia del archivo + aviso en la UI |

---

## 8. Tests propuestos

`core/src/desktopTest/kotlin/kitoru/domain/CourseLifecycleTest.kt`:

- La tabla de transiciones: toda transición de la tabla se acepta; toda
  transición fuera de la tabla se rechaza con mensaje en español.
- Publicar sin nombre → error "La cátedra necesita un nombre antes de publicarse."
- Publicar sin código de inscripción → error.
- `PUBLICADA → EN_CURSADA` materializa `termStartAt`/`termEndAt` y deja
  `apiKeyExpiresAt = termEndAt`.
- `EN_CURSADA → CERRADA` deja `apiKeyExpiresAt == closedAt`.
- `CERRADA → PUBLICADA` con key vencida → error; con key nueva → OK.
- Archivar limpia el `joinCode` y conserva `materials`, `threads` y `analytics`
  (que no se tocan, porque el estado no las afecta).
- `effectiveKeyExpiry` es siempre ≤ `effectiveTermEndAt`.
- `JoinCode.generate` produce 8 caracteres del alfabeto; 1000 códigos no
  repetidos (con `Random(seed)`).
- `JoinCode.normalize("abcd-efgh") == "ABCDEFGH"`; `JoinCode.format` y `mask`.
- `CourseMigration.normalize` sobre una cátedra legacy sin `status` →
  `PUBLICADA`.
- `Term.parse("2026-1")`, `Term.parse("2026")`, `defaultEnd()` de los dos
  cuatrimestres.
- `CivilDate` ida y vuelta: `ofEpochDay(toEpochDay())` para 1000 fechas
  consecutivas y para el 29/02 de un año bisiesto.
- `progress(now)`: semana 8 de 16 en el medio de la cursada.

---

## 9. Checklist de implementación

1. `platform/CivilDate.kt` (nuevo, sin dependencias).
2. `domain/CourseStatus.kt` (nuevo).
3. `domain/KeyLease.kt` (nuevo).
4. `domain/Term.kt` (nuevo).
5. `domain/JoinCode.kt` (nuevo).
6. `domain/Course.kt`: reemplazar el `data class Course` por el de §6.1 y
   agregar `Enrollment` sin cambios.
7. `domain/CourseLifecycle.kt` (nuevo).
8. `domain/CourseMigration.kt` (nuevo).
9. `domain/FileRepositories.kt`: `findByJoinCode` en `CourseRepository`,
   `find` en `EnrollmentRepository`, normalización en `list`/`get`/`save`.
10. `domain/JsonStore.kt`: `coerceInputValues`, `explicitNulls = false`,
    decodificación por ítem y backup del archivo corrupto.
11. `domain/EnrollmentInvite.kt` (opcional, §2.3).
12. `domain/JoinCourseUseCase.kt` (nuevo, §6.3).
13. Tests (§8).
