# 09 · Identidad y perfiles de usuario

> Diseño del modelo de identidad de Kitoru: quién es la persona que está usando
> la app en este dispositivo, cómo se inscribe en una cátedra con un código,
> cómo se vincula (o no) con una cuenta institucional y cómo se degrada todo
> con elegancia cuando **no hay servidor**.

Estado: propuesta cerrada. Tipos de referencia:
`kitoru.domain.Roles` (UserProfile), `kitoru.domain.Course` (Course, Enrollment),
`kitoru.domain.Chat` (ChatThread), `kitoru.domain.Repositories`,
`kitoru.domain.FileRepositories`, `kitoru.domain.JsonStore`.

---

## 1. El problema en una frase

Kitoru corre **en el dispositivo del alumno**, la institución **puede o no
estar detrás**, y el alumno **no tiene cuenta ni servidor al que autenticarse**
cuando apenas abre la app. La identidad, entonces, tiene que funcionar con
cero infraestructura y ser **mejorable** (no reemplazable) cuando aparece el
servidor institucional.

De ahí salen los tres requisitos que guían todo el diseño:

1. **Nada es obligatorio.** La identidad local es el piso, no un modo degradado.
2. **Enlazar no es migrar.** Vincularse a una cuenta institucional agrega
   credenciales; no cambia de identidad ni reescribe el historial.
3. **El nombre que ve el docente no es el nombre real del alumno**, salvo que
   el alumno lo elija (clima de aula).

---

## 2. Capas de identidad

Cuatro capas, cada una con su archivo y su dueño:

| Capa | Qué es | Dónde vive | Identificador |
|---|---|---|---|
| **Dispositivo** | La máquina, para agrupar perfiles y detectar duplicados | `config/device.json` | `deviceId` (UUID v4) |
| **Perfil local** | La persona que usa la app **en este dispositivo** | `config/profiles.json` | `UserProfile.id` (UUID v4) |
| **Inscripción** | El vínculo persona ↔ cátedra, con rol y etiqueta visible | `config/enrollments.json` | `Enrollment.id` (UUID v4) |
| **Cuenta institucional** | La identidad real de la persona en la institución | (remoto) + ref local en `UserProfile.institution` | `subject` (sub/mail del IdP) |

Reglas de oro:

- El **`userId` de una inscripción es un `UserProfile.id` local**. Nunca es el
  `subject` institucional, nunca el email. Es opaco y aleatorio.
- Un `UserProfile` puede tener **cero, una o varias inscripciones**, con roles
  distintos en cada una (`ALUMNO` en una, `AYUDANTE` en otra).
- Un `UserProfile` puede estar **vinculado o no** a una cuenta institucional.
  Vincular es opcional, posterior y reversible.
- El `deviceId` **no viaja** al docente ni al servidor como identidad: sólo se
  usa para agrupar perfiles locales y para heurísticas de duplicado.

---

## 3. `UserProfile` final

Reemplaza al `UserProfile` actual de `Roles.kt` (3 campos → 10). Todos los
campos nuevos tienen default, así que los `profiles.json` existentes siguen
leyéndose (`KitoruJson` ya tiene `ignoreUnknownKeys = true` y
`encodeDefaults = true`).

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Identidad local de una persona en este dispositivo.
 *
 * Es la identidad **por defecto** de Kitoru: no requiere cuenta, ni servidor,
 * ni red. Todo lo demás (cuenta institucional, roles docentes, analítica) se
 * apoya encima de esto.
 *
 * Un perfil es *local*: describe a la persona en **esta** instalación. Si la
 * misma persona usa dos dispositivos, tiene dos perfiles locales con el mismo
 * nombre y distinto `id`; ver [InstitutionLink] para el caso de que se
 * vincule una cuenta institucional que las una.
 */
@Serializable
data class UserProfile(
    /**
     * Id local opaco (UUID v4). Nunca es un correo ni un dato personal.
     * Es el `userId` que usan `Enrollment` y `ChatThread`.
     */
    val id: String,

    /**
     * Nombre real de la persona (o el nombre que elige usar en general).
     * Es **local**: no se exporta al docente ni al servidor salvo que la
     * persona lo acepte de forma explícita (ver [CourseAlias]).
     */
    val displayName: String,

    /**
     * Correo electrónico. Sirve para vincular con una cuenta institucional y
     * para que el usuario recuerde su identidad. **Nunca** se incluye en el
     * roster exportado al docente.
     */
    val email: String = "",

    /**
     * Avatar. Por defecto, las iniciales de [displayName] (sin dependencias,
     * sin red). Puede ser una imagen local o una URL.
     */
    val avatar: AvatarRef = AvatarRef(),

    /**
     * Idioma de la interfaz, tag BCP-47 simple: "es", "en", "pt-BR".
     * Por defecto se detecta del sistema al crear el perfil.
     */
    val locale: String = "es",

    /**
     * Zona horaria IANA: "America/Argentina/Buenos_Aires".
     * Se usa para agrupar la actividad por día en la analítica del docente.
     * Por defecto se detecta del sistema al crear el perfil.
     */
    val timeZone: String = "",

    /**
     * Seudónimo por cátedra, indexado por `courseId`.
     *
     * Es la **preferencia de la persona**; lo que el docente ve finalmente es
     * `Enrollment.displayName`, que es la materialización de esta preferencia
     * (ver §4).
     */
    val aliases: Map<String, CourseAlias> = emptyMap(),

    /**
     * Cuenta institucional vinculada (SSO / login contra Kitoru-Server).
     *
     * Ausente ⇒ identidad puramente local. Es el único punto donde la app
     * depende de una identidad externa, y **nunca guarda la API key**: sólo
     * una referencia opaca a un secreto en el almacén de credenciales.
     */
    val institution: InstitutionLink? = null,

    /** Cuándo se creó el perfil (epoch millis). */
    val createdAt: Long = 0L,

    /** Última modificación (epoch millis). */
    val updatedAt: Long = 0L,
) {
    /** ¿Está vinculado a una cuenta institucional? */
    val isLinked: Boolean get() = institution != null

    /** Alias configurado para una cátedra, o el default (nombre real). */
    fun aliasFor(courseId: String): CourseAlias = aliases[courseId] ?: CourseAlias()
}
```

### 3.1 Tipos de apoyo

```kotlin
/** De dónde sale el avatar. */
@Serializable
enum class AvatarKind(val label: String) {
    INITIALS("Iniciales"),
    LOCAL_FILE("Imagen local"),
    REMOTE_URL("Imagen remota"),
}

/**
 * Referencia al avatar.
 *
 * `INITIALS` no necesita [value]: se calcula con `IdentityRules.initials`.
 * `LOCAL_FILE` guarda una ruta relativa a `config/` (portable entre
 * dispositivos si el usuario exporta el perfil). `REMOTE_URL` guarda una URL
 * (típicamente la que devuelve el IdP institucional).
 */
@Serializable
data class AvatarRef(
    val kind: AvatarKind = AvatarKind.INITIALS,
    val value: String = "",
)

/** Qué muestra el docente para esta persona en esta cátedra. */
@Serializable
enum class AliasKind(val label: String) {
    /** El nombre real del perfil ("Juan Pérez"). */
    REAL("Nombre real"),

    /** Un apodo elegido por el propio alumno ("Fantasma"). */
    PSEUDONYM("Seudónimo del alumno"),

    /** Una etiqueta genérica asignada por el sistema ("Alumno 7F3K"). */
    ANONYMOUS("Anónimo"),
}

/**
 * Preferencia de seudónimo del alumno para una cátedra.
 *
 * Es lo que el alumno *quiere*; no es necesariamente lo que el docente ve
 * (el docente puede reetiquetar, ver [LabelSource]).
 */
@Serializable
data class CourseAlias(
    val kind: AliasKind = AliasKind.REAL,
    /** Texto del seudónimo; sólo se usa si [kind] es [AliasKind.PSEUDONYM]. */
    val value: String = "",
)

/**
 * Vínculo con una cuenta institucional.
 *
 * [subject] es el identificador estable del usuario en el IdP (`sub` de OIDC,
 * `uid` de SAML, o el mail institucional si ése es el identificador). Es el
 * único valor con el que el servidor puede reconocer a la persona.
 *
 * [credentialRef] es una **referencia** a un secreto guardado en el almacén de
 * credenciales del dispositivo (`session:<serverId>`), nunca el secreto.
 */
@Serializable
data class InstitutionLink(
    /** Id del servidor institucional (Kitoru-Server). */
    val serverId: String,
    /** Emisor / audiencia: URL del IdP o del servidor. */
    val issuer: String,
    /** Identificador estable de la persona en el IdP. */
    val subject: String,
    /** Nombre de usuario institucional (sólo informativo). */
    val username: String = "",
    /** Id del perfil de la persona en el servidor. */
    val serverProfileId: String = "",
    /** Referencia al token de sesión en el almacén de credenciales. */
    val credentialRef: String = "",
    /** Cuándo se vinculó (epoch millis). */
    val linkedAt: Long = 0L,
    /** Última sincronización con el servidor (epoch millis). */
    val lastSyncAt: Long = 0L,
)
```

### 3.2 Reglas de campos

| Campo | Visible para el docente | Visible para el servidor | Notas |
|---|---|---|---|
| `id` | No (salvo como clave de roster) | No (sólo `subject` si está vinculado) | UUID v4 local |
| `displayName` | Sólo si el alias es `REAL` | Sólo si la persona está vinculada | Local por defecto |
| `email` | **Nunca** | Sólo al vincular (para el login) | Fuera del roster exportado |
| `avatar` | Sólo si el alias es `REAL` | Con el link | `INITIALS` por defecto |
| `locale`, `timeZone` | No | No | Configuración del cliente |
| `aliases` | Se materializa en `Enrollment.displayName` | No | Preferencia local |
| `institution` | No | Es el vínculo | Sin secretos |

---

## 4. Seudónimo por cátedra: cómo se materializa

El objetivo es que el docente vea **"Juan Pérez"** o **"Alumno 037"** según lo
que el alumno haya elegido, y que el cambio de uno a otro no sea una decisión
irreversible.

### 4.1 Modelo de dos capas

- **Intención** → `UserProfile.aliases[courseId]: CourseAlias`.
- **Etiqueta materializada** → `Enrollment.displayName`, que es lo que el
  docente ve en el roster, en la analítica y en los hilos.

Reglas de sincronización:

1. Al crear la inscripción: `Enrollment.displayName = resolve(profile, courseId, enrollmentId)`.
2. Si el alumno cambia su alias y la etiqueta **no está bloqueada por el
   docente**, la inscripción se actualiza (`labelSource = STUDENT`).
3. Si el docente renombra (por ejemplo a "Alumno 037", el legajo, o un
   apodo), la etiqueta queda **bloqueada**: `labelSource = TEACHER`. Los
   cambios del alumno ya no pisan la etiqueta hasta que el docente la libere.

Esto resuelve el choque clásico "el docente lo llamó Alumno 037 y ahora el
alumno se cambió el nombre a Juan Pérez": gana el docente, y el alumno ve
claramente que su nombre está siendo reemplazado.

### 4.2 Resolución

```kotlin
/** Quién puso la etiqueta actual. Define quién gana en un conflicto. */
@Serializable
enum class LabelSource(val label: String) {
    STUDENT("El alumno"),
    TEACHER("El docente"),
    SYSTEM("El sistema"),
}
```

```kotlin
package kitoru.domain

import kitoru.platform.sha256Hex
import kitoru.platform.stripDiacritics

/**
 * Reglas puras de identidad: deterministas, sin disco y sin red, para que
 * el alumno y el docente calculen **lo mismo** a partir de los mismos datos.
 */
object IdentityRules {

    /**
     * Iniciales del avatar a partir del nombre: "Juan Pérez" → "JP",
     * "Alumno" → "AL". Sin acentos, sin depender de la fuente.
     */
    fun initials(name: String): String {
        val words = stripDiacritics(name).trim().split(' ', '\t', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /** Etiqueta visible por defecto según la preferencia del alumno. */
    fun resolveDisplayName(profile: UserProfile, courseId: String, enrollmentId: String): String {
        val alias = profile.aliasFor(courseId)
        return when (alias.kind) {
            AliasKind.REAL -> profile.displayName
            AliasKind.PSEUDONYM -> alias.value.ifBlank { profile.displayName }
            AliasKind.ANONYMOUS -> anonymousLabel(courseId, enrollmentId)
        }
    }

    /**
     * Etiqueta anónima estable: "Alumno 7F3K".
     *
     * Se deriva de `sha256(courseId|enrollmentId)`, así que **el alumno y el
     * docente calculan la misma etiqueta** sin necesidad de sincronizar nada:
     * basta con que compartan el mismo `Enrollment.id` (vía servidor o vía
     * exportación del roster).
     */
    fun anonymousLabel(courseId: String, enrollmentId: String): String {
        val h = sha256Hex("$courseId|$enrollmentId")
        return "Alumno ${h.take(4).uppercase()}"
    }
}
```

El prefijo "Alumno" + 4 caracteres hexadecimales deja 65.536 combinaciones:
con una clase de 40 personas la probabilidad de colisión es ~1,2%, y se puede
resolver alargando a 5 caracteres. Si el servidor institucional asigna un
número de padrón (`seatNumber`), ese número manda y se materializa igual en
`Enrollment.displayName`.

### 4.3 Dónde aparece la etiqueta (y dónde no)

- `Enrollment.displayName` → roster del docente, `StudentAnalytics.displayName`,
  `Enrollment.label`.
- `ChatThread.authorName` → **debe ser la etiqueta, no el nombre real**. Es un
  detalle de privacidad importante: si `authorName` fuera el nombre real, el
  log del servidor filtraría la identidad real del alumno al docente aunque el
  alias fuese anónimo. **Regla: `authorName` = `Enrollment.displayName`.**
- `CourseAnalytics.inactiveStudents` → hoy son `String`; debería pasar a ser
  `List<Enrollment>` o una lista de etiquetas (queda para el documento de
  analítica).
- `AvatarRef` con `kind = INITIALS` → `IdentityRules.initials(displayName)`.

### 4.4 Cambios en `Enrollment` (`kitoru.domain.Course`)

```kotlin
@Serializable
data class Enrollment(
    val id: String,
    val courseId: String,
    val userId: String,                 // UserProfile.id local
    val displayName: String = "",        // etiqueta visible para el docente
    val role: CourseRole = CourseRole.ALUMNO,
    val joinedAt: Long = 0L,
    val visibility: ChatVisibility = ChatVisibility.FULL,

    // --- identidad (doc 09) ---
    /** Preferencia del alumno que originó la etiqueta. */
    val aliasKind: AliasKind = AliasKind.REAL,
    /** Quién puso la etiqueta actual: define quién gana en los conflictos. */
    val labelSource: LabelSource = LabelSource.STUDENT,
    /** Frase de verificación que sólo quien creó la inscripción puede mostrar. */
    val claimPhrase: String = "",
    /** Número de padrón asignado por el servidor institucional (0 = no hay). */
    val seatNumber: Int = 0,
    /** Id de la inscripción en el servidor (vacío = sólo local). */
    val serverEnrollmentId: String = "",
    /** Inscripción revocada por el docente (se conserva para el historial). */
    val removed: Boolean = false,
)
```

Y los campos nuevos de `Course` (`kitoru.domain.Course`), que sostienen la
identidad docente del §7:

```kotlin
    /** Id del UserProfile local que creó la cátedra (es el titular). */
    val ownerProfileId: String = "",
    /** Hash del secreto de propietario: permite tomar posesión en otro dispositivo. */
    val ownerSecretHash: String = "",
    /** Hash del código de personal (ayudantes). */
    val staffCodeHash: String = "",
    /** Usos concedidos del código de personal (para revocarlos). */
    val staffCodeUses: Int = 0,
    /** Generación del código de inscripción: al rotarlo, el viejo deja de servir. */
    val codeEpoch: Int = 1,
```

### 4.5 Elección de seudónimo: qué le mostramos al alumno

En la pantalla de inscripción, tres opciones explícitas, con la primera
recomendada según la política de la cátedra:

- **"Usar mi nombre real"** → el docente ve "Juan Pérez".
- **"Usar un apodo"** → campo de texto libre (2..24 caracteres), el docente ve
  ese apodo.
- **"Prefiero que no usen mi nombre"** → el docente ve "Alumno 7F3K".

Explicación corta en la UI: *"El docente ve el nombre que elijas. El nombre
real nunca se envía si elegís otra cosa."* Esto es lo que hace saludable el
clima de aula: el alumno decide, en el momento, cuánto exponerse.

---

## 5. Identidad local vs. identidad institucional

### 5.1 Los dos estados

```
                 ┌──────────────────────────────────────────┐
                 │            IDENTIDAD LOCAL               │
                 │  UserProfile + Enrollment + credenciales  │
                 │  Todo funciona sin red. Es el default.     │
                 └───────────────────┬──────────────────────┘
                                     │ "Vincular con la institución"
                                     ▼
                 ┌──────────────────────────────────────────┐
                 │       IDENTIDAD INSTITUCIONAL            │
                 │  = perfil local + InstitutionLink         │
                 │    (subject del IdP, token en el almacén) │
                 │  El servidor ahora conoce a la persona.   │
             ┌───┴───────────────────────────────┐          │
             │ El servidor NO sabe la API key    │          │
             │ que el alumno usa: la emite y la  │          │
             │ guarda el cliente en su almacén   │          │
             │ de credenciales (nunca en el perfil│          │
             │ ni en el roster).                 │          │
             └───────────────────────────────────┘
```

### 5.2 Por qué "enlazar" y no "migrar"

Al vincular:

- **No se crea un perfil nuevo**: se le agrega `InstitutionLink` al perfil
  local activo. El `id` local no cambia → los `Enrollment.userId` y los
  `ChatThread.authorId` siguen válidos, el historial no se toca.
- **No se reescriben los chats**: los chats con IA local nunca salen del
  dispositivo, y los remotos ya estaban en el servidor.
- **El `subject` institucional no reemplaza al `userId`**: conviven. `userId`
  sigue siendo la clave local; `subject` es la clave que el servidor usa.
- **Es reversible**: `unlink` borra `institution` y el token, y el perfil
  vuelve a ser puramente local. Se avisa al usuario de que los chats que ya
  quedaron en el servidor institucional no se pueden borrar desde el cliente.

### 5.3 Conflictos al vincular

Al vincular una cuenta institucional contra un dispositivo que ya tiene
perfiles locales, hay tres casos y una decisión de producto:

| Situación | Qué hace la app |
|---|---|
| No hay ningún perfil con ese `subject` | Se **vincula al perfil activo** y se offered la lista de inscripciones del servidor |
| Hay un perfil local con el mismo `email` | Se propone **fusionar** (el local adopta el `subject`); el historial local se conserva |
| Hay **otro** perfil local con el mismo `subject` (misma cuenta, dos perfiles) | Se **fusionan automáticamente** los dos perfiles locales (§9.3) |

### 5.4 Un ejemplo de flujo completo

```
1. El alumno abre la app por primera vez.
   → no hay perfiles → pantalla "Creá tu identidad"
   → nombre "Juan Pérez", email opcional, idioma y zona horaria del sistema
   → UserProfile(id=u-8f3c…, displayName="Juan Pérez", locale="es",
                 timeZone="America/Argentina/Buenos_Aires", avatar=INITIALS)
   → se crea también el device.json con deviceId

2. Entra el código "IA-7F3K".
   → la app busca la cátedra (local o en el servidor configurado)
   → pide nombre y alias  ("Fantasma" / real / anónimo)
   → Enrollment(id=e-21a7…, userId=u-8f3c…, displayName="Fantasma",
                aliasKind=PSEUDONYM, labelSource=STUDENT,
                claimPhrase="cobre marfil sauce Bruno 42")

3. (Opcional, más adelante) "Vincular con la institución".
   → InstitutionLink(serverId="srv-unlp", issuer="https://sso.unlp.edu.ar",
                      subject="juan.perez@unlp.edu.ar", credentialRef="session:srv-unlp")
   → el token vive en el almacén de credenciales, no en profiles.json
   → los chats siguen siendo del mismo UserProfile.id
```

---

## 6. Inscripción por código de cátedra

### 6.1 El flujo (sin servidor)

El alumno **sólo tiene el código** (ej. "IA-7F3K"). La app:

1. **Normaliza** el código: `normalizeJoinCode("ia 7f3k") == "IA7F3K"`
   (mayúsculas, se quita todo lo que no sea alfanumérico). Así "IA-7F3K",
   "ia-7f3k" y "IA7F3K" son el mismo código.
2. **Busca la cátedra**: primero en el repositorio local
   (`CourseRepository.findByJoinCode`), después en el directorio del servidor
   institucional si hay uno configurado.
3. Si **no encuentra** la cátedra:
   - Sin servidor ⇒ *"Ese código no corresponde a ninguna cátedra disponible
     en este dispositivo. Pedile a tu docente el archivo de la cátedra."*
   - Con servidor pero sin internet ⇒ *"No pudimos conectarnos con el
     servidor de la institución. Probá de nuevo o usá el archivo de la
     cátedra."*
4. Si la encuentra, **verifica el código** contra `Course.joinCode`
   (normalizado). Si no coincide ⇒ *"El código no es válido para esta
     cátedra."*
5. **Pide identidad**: nombre (prellenado con `UserProfile.displayName`) y
   alias (real / apodo / anónimo).
6. **Crea la inscripción** con `role = ALUMNO`, `visibility = FULL` por
   defecto (el alumno puede bajarla a `METRICS_ONLY` o `NONE` después, que es
   su derecho y no debería ser un paso extra en el alta).
7. Muestra el **resumen de la inscripción**: nombre de la cátedra, modelo que
   va a usar, y si el material se va a descargar.

### 6.2 El flujo (con servidor institucional)

El servidor es el directorio de cátedras y el que asigna roles:

```
POST {server}/v1/join  { "code": "IA7F3K", "deviceId": "…", "subject": "…" }

200 {
  "course": { "id": "c-ia-2026", "code": "IA-2026-1", "name": "Inteligencia Artificial" },
  "enrollment": {
     "id": "se-771",                  // serverEnrollmentId
     "role": "ALUMNO",
     "displayName": "Fantasma",       // el servidor guarda lo que el alumno eligió
     "seatNumber": 37,
     "aliasKind": "PSEUDONYM"
  },
  "model": { "baseUrl": "…", "modelName": "…", "apiKey": "…" }   // la key NO se persiste en el perfil
}
```

El servidor sabe quién es el alumno **si el alumno se autenticó**; si no, sabe
lo mismo que el cliente local (un alias y un id de dispositivo), y eso es
deliberado: sin login hay menos datos, no inventados.

### 6.3 El problema real: el código ya lo usó otra persona en otro dispositivo

Este es el nudo del diseño y hay que decirlo sin adornos.

**Planteo.** El código "IA-7F3K" es, en la práctica, un *bearer token* de
grupo: lo que prueba es "conozco el código", no "soy el alumno 12". Si Ana lo
usa en su notebook y Bruno lo usa en su teléfono, en el mundo local hay **dos
identidades** (`u-a1…` y `u-b2…`), dos inscripciones (`e-a1…` y `e-b2…`) y el
docente ve dos alumnos. No hay ninguna forma de saber que son la misma
persona, ni de saber que la segunda es un impostor, **porque el dispositivo no
tiene contra quién comparar**. La identidad es local por diseño; el código es
compartido por diseño. La colisión es consecuencia directa de ambos.

**Consecuencias concretas que hay que asumir:**

- El roster puede tener duplicados que son la misma persona (doble cuenta: la
  analítica cuenta al alumno dos veces).
- Puede haber una inscripción de un impostor que el docente cree que es Ana.
- No se puede "revocar" el uso indebido de un código ya compartido sin
  invalidar el de los alumnos legítimos.

**Mitigaciones.** Se proponen cinco, con su alcance real:

1. **Dos clases de código: grupo e invitación.**
   `Course.joinCode` es el código de grupo (multi-uso, el que va en el cartel
   del aula). El docente puede además emitir **códigos de invitación de un
   solo uso** (`inv-…`), que el servidor consume. Un código de invitación
   usado dos veces ⇒ el servidor lo rechaza con
   *"Este código de invitación ya fue usado. Pedile a tu docente uno nuevo."*
   Esto **resuelve el caso	server** de forma real, y es la recomendación
   operativa para cursos con evaluación.

2. **Rotación por generación (`codeEpoch`).** `Course.codeEpoch` (entero,
   default 1). El docente rota el código cuando sospecha una filtración:
   las inscripciones nuevas usan el código nuevo; las viejas siguen siendo
   válidas pero el roster las marca como *"inscripto con la generación
   anterior"* (informativo, no bloqueante). Con servidor, la rotación es un
   `PATCH` y el servidor invalida el código viejo al instante.

3. **Frases de verificación (`claimPhrase`).** Cada inscripción lleva una
   frase corta ("cobre marfil sauce Bruno 42") derivada de un secreto del
   dispositivo + el `Enrollment.id`. El docente la ve en el roster; el alumno
   la puede mostrar en su dispositivo. Ante la duda "¿este es el Bruno?",
   el docente pregunta en privado a un alumno su frase y compara. Detecta
  suplantación de identidad (quien no es el alumno no puede producir la frase) y
   permite fusionar duplicados reales. No evita el problema, lo hace
   verificable en 10 segundos.

4. **Heurísticas de duplicado en el servidor.** Con login institucional, el
   servidor ve `subject`: si el mismo `subject` aparece con dos inscripciones
   para la misma cátedra, fusiona y avisa. Sin login, usa `deviceId` +
   similitud de `displayName` para *sugerir* fusiones al docente
   (*"Dos alumnos se llaman casi igual y se inscribieron con el mismo
   dispositivo"*). Son heurísticas: se muestran como sugerencias, nunca como
   acciones automáticas.

5. **Curación del roster por parte del docente.** El docente puede renombrar
   (a "Alumno 037"), reetiquetar, fusionar dos filas y desactivar una
   inscripción. Es la válvula de escape final: aun sin servidor, el docente
   tiene el control del roster.

**Qué se le muestra al alumno que intenta usar un código ya usado**, en el
caso local (que es el que no puede detectarlo): no se puede detectar, y por
eso **no se le muestra un error inventado**. Se le muestra el resultado del
alta y una nota honesta en el propio resumen de la inscripción:

> "Te anotaste como **Fantasma** en *Inteligencia Artificial*. Tu docente ve
> ese apodo. Si tu docente te reconoce mal, pedile que confirme tu código de
> verificación: **cobre marfil sauce Bruno 42**."

Ese código es el mecanismo que convierte un problema invisible en un
problema verificable.

### 6.4 Alta del curso (el lado docente, sin servidor)

Si no hay servidor, la cátedra también tiene que llegar al alumno. El camino
soportado es un **archivo de cátedra** (`.kitoru`, un JSON con el `Course` +
`ModelPolicy` + materiales) que el docente comparte por el medio que quiera
(un pendrive, un link, un QR). El alumno lo importa, y recién entonces el
código "IA-7F3K" tiene contra qué compararse en su dispositivo.

Alternativa para cursos grandes: un **directorio de cátedras** propio de la
institución (un simple archivo JSON compartido en una carpeta de red o un
bucket) que la app consulta por código. Es el mismo modelo de "directorio"
que el servidor, sin autenticación.

---

## 7. Identidad del rol docente

**No hay una "identidad docente" separada.** El docente es un `UserProfile`
más, con inscripciones de rol `TITULAR` o `AYUDANTE`. Lo que lo diferencia
del alumno no es su identidad, sino **qué puede hacer con la cátedra** y
**cómo lo demuestra**.

La pregunta honesta es: *"¿cómo sé que quien me pide editar la cátedra es el
profesor?"*. Sin servidor no hay a quién preguntarle, así que la respuesta
tiene que ser **posesión de un secreto**, no identidad:

| Capacidad | Mecanismo | Almacenamiento |
|---|---|---|
| Ser **titular** | El dispositivo que **creó** la cátedra es el titular (`Course.ownerProfileId`), o un **secreto de propietario** que el docente muestra al tomar posesión en otro dispositivo | `Course.ownerSecretHash` (hash, nunca el secreto) |
| Ser **ayudante** | Un **código de personal** que el titular genera y comparte | `Course.staffCodeHash` + contador de usos |
| Ser **alumno** | El **código de la cátedra** | `Course.joinCode` |

Detalles:

- **El archivo de la cátedra ES la credencial del titular.** Quien tiene el
  archivo puede editar la cátedra. Esto es una limitación real del modelo
  local y hay que documentarla: la confianza está en el canal de distribución
  del archivo. Por eso el bundle que se comparte **con los alumnos** va sin
  `ownerSecretHash` ni `staffCodeHash`: es el mismo `Course` con esos campos
  vacíos.
- **El código de personal** se genera en el dispositivo del titular, se
  muestra una vez y se entrega al ayudante. El ayudante lo tipea en su
  dispositivo; la app verifica contra `staffCodeHash` y, si coincide, crea
  la inscripción con rol `AYUDANTE`. El titular puede **rotarlo** (cambia el
  hash, invalida el anterior) y **revocar** usos ya concedidos
  (`Enrollment.removed = true`).
- El **código de personal debe ser largo** (8+ caracteres, generado al azar),
  porque el hash de un código corto se puede romper por fuerza bruta. No
  puede ser "1234".
- **Con servidor institucional**, los roles vienen del servidor: el `subject`
  dice "en esta cátedra sos titular" y la app crea la inscripción con ese rol.
  Los secretos locales pasan a ser un mecanismo de respaldo offline, no el
  principal.

Resumen para el usuario, en la UI: *"La cátedra se controla con códigos, no
con contraseñas: vos sos el docente de esta cátedra porque la creaste en este
dispositivo, o porque tenés el código de personal."*

---

## 8. Vinculación con Kitoru-Server sin exponer la API key

### 8.1 El principio

La API key **no es identidad**. Es un permiso de consumo. Por eso vive en un
almacén de credenciales aparte y se referencia por id, igual que un token:

- `UserProfile` → nunca contiene una key.
- `Enrollment` → nunca contiene una key.
- `Course.modelPolicy.apiKey` → sigue existiendo para el caso "el docente
  comparte un bundle con la key del curso", pero **la key personal que emite
  el servidor no se guarda ahí**: va al almacén de credenciales.

### 8.2 Almacén de credenciales

Nuevo concepto de plataforma (`kitoru.platform`), con interfaz en
`commonMain` e implementaciones por plataforma:

```kotlin
package kitoru.platform

/**
 * Almacén de secretos del dispositivo.
 *
 * La app guarda acá los tokens de sesión y las API keys. Los perfiles sólo
 * guardan una **referencia** ("session:srv-unlp"), nunca el secreto.
 */
interface CredentialStore {
    suspend fun put(ref: String, secret: String)
    suspend fun get(ref: String): String?
    suspend fun delete(ref: String)
    suspend fun has(ref: String): Boolean
}

/** Almacén de credenciales de la plataforma. */
expect fun systemCredentialStore(): CredentialStore
```

Implementaciones:

- **Desktop**: archivo `config/credentials.json` con permisos de usuario
  (0600). Documentar honestamente que **no es un keychain**: protege contra
  que otro usuario de la máquina lo lea, no contra malware con permisos del
  usuario. Dejar la interfaz lista para subir a DPAPI / Keychain / libsecret
  sin tocar el dominio.
- **Android**: `EncryptedSharedPreferences` (o `KeyStore` + AES-GCM) sobre
  `filesDir`. El sandbox de la app ya aísla el archivo.

Las referencias se nombran con un prefijo para poder auditarlas:

```
session:<serverId>          -> access token del servidor institucional
refresh:<serverId>          -> refresh token
apikey:<serverId>:<courseId> -> API key emitida por el servidor
```

### 8.3 Flujo de vinculación

```
1. Ajustes → "Vincular con la institución"
2. Se pide la dirección del servidor (o viene precargada por la institución
   en el build, o por un QR del docente: kitoru://link?server=…&course=…)
3. Se abre el navegador: {server}/v1/auth/start?redirect=kitoru://cb&state=<nonce>
4. El IdP autentica; el servidor redirige a kitoru://cb?code=…&state=…
5. La app valida state, canjea el código:
     POST {server}/v1/auth/exchange { code, deviceId }
   → { accessToken, refreshToken, expiresAt,
       user: { id, displayName, email, avatarUrl, institutions:[…] },
       courses: [ { courseId, role, enrollmentId } ] }
6. Los tokens se guardan en el CredentialStore con ref "session:<serverId>".
   La API key del curso, si viene, va con ref "apikey:<serverId>:<courseId>".
7. Se crea/actualiza InstitutionLink en el perfil local:
     InstitutionLink(serverId, issuer, subject, username, serverProfileId,
                     credentialRef = "session:<serverId>")
8. Se aplican los roles del servidor: se crean/actualizan Enrollment con
   serverEnrollmentId y seatNumber.
9. Nada de lo anterior escribe una key en profiles.json.
```

Al arrancar la app (y antes de usar la IA alojada):

- Si el token expiró ⇒ refresh con `refresh:<serverId>`; si el refresh falla ⇒
  `unlink` limpio: el perfil vuelve a ser local y se avisa
  *"Tu sesión con la institución expiró. Podés seguir usando Kitoru con tu
  identidad local."*

### 8.4 Qué sabe cada parte

| | Sin vincular | Vinculado |
|---|---|---|
| El servidor conoce el `subject` | No (sólo `deviceId` + alias) | Sí |
| El servidor puede fusionar duplicados | No (sólo heurísticas) | Sí, por `subject` |
| La app funciona sin red | Sí, completa | Sí, salvo IA alojada |
| El docente ve el alias del alumno | Sí | Sí |
| El docente ve el nombre real | Sólo si el alumno eligió `REAL` | Sólo si el alumno eligió `REAL` |

Punto que hay que subrayar: **vincular no le da más poder al docente sobre el
alumno**. El `subject` lo conoce el *servidor*, no el docente, salvo que el
alumno acepte que su identidad institucional se muestre en el roster. Eso es
otra decisión de producto, y por defecto es **no compartirla**.

---

## 9. Multi-perfil en un mismo dispositivo

### 9.1 La pregunta

"Un alumno que también es ayudante de otra cátedra" — ¿un perfil con varias
inscripciones, o dos perfiles?

**Respuesta: un solo `UserProfile` con varias inscripciones.** El rol vive en
`Enrollment`, no en el perfil. Juan es `ALUMNO` en "IA-2026" y `AYUDANTE` en
" Redes-2026" con el **mismo** `UserProfile.id`, y por lo tanto con el mismo
avatar, el mismo idioma, la misma zona horaria y **los mismos chats** en la
UI (que es lo que espera: "mis conversaciones", no "mis conversaciones de
alumno").

Sin embargo, la app **sí** soporta varios perfiles por dispositivo, porque hay
un caso real que el modelo de un-perfil no cubre: **dispositivos
compartidos** (una tablet en casa, una PC de laboratorio, el teléfono
prestado). Ahí hacen falta dos personas con dos historiales separados.

### 9.2 La regla

- `ProfileRepository` es un repositorio de **colección**: N perfiles.
- Hay **exactamente un perfil activo** en el dispositivo, guardado en
  `AppSettings.activeProfileId`.
- Todas las consultas de la UI (inscripciones, hilos, materiales) se filtran
  por el perfil activo.
- Los perfiles se crean, renombran y borran desde Ajustes → Perfiles, con un
  diálogo de cambio de perfil visible sólo si hay más de uno.
- El `UserProfile.id` es la clave de autoría de los chats: cambiar de perfil
  cambia qué conversaciones se ven, y **nunca** las mezcla.

Consecuencia importante: un `Enrollment` pertenece a un perfil (su `userId`).
Si el alumno tiene dos perfiles en el mismo dispositivo, puede tener **dos
inscripciones en la misma cátedra** con nombres distintos. Eso es correcto y
no hay que impedirlo (puede ser legítimo: un perfil "personal" y otro "de
trabajo"), pero el docente debe poder notarlo: el roster muestra un aviso
*"dos sesiones del mismo dispositivo"* cuando detecta el mismo `deviceId`.
Again, aviso, no bloqueo.

### 9.3 Fusión de perfiles

Al vincular una cuenta institucional que ya está vinculada a otro perfil local,
se fusionan: el perfil ** destino** conserva el `id`; el origen se borra y sus
recursos se reasignan (`Enrollment.userId`, `ChatThread.authorId` → id destino).

```kotlin
/**
 * Fusiona dos perfiles locales: `source` se vacía dentro de `target`.
 *
 * Se usa al vincular una cuenta institucional que ya existe en otro perfil
 * del mismo dispositivo, o cuando el usuario elige "unir estos perfiles".
 */
class ProfileMerger(
    private val profiles: ProfileRepository,
    private val enrollments: EnrollmentRepository,
    private val threads: ThreadRepository,
) {
    suspend fun merge(targetId: String, sourceId: String): MergeResult
}

sealed interface MergeResult {
    data class Ok(val movedEnrollments: Int, val movedThreads: Int) : MergeResult
    data class NotFound(val id: String) : MergeResult
    data class SameProfile(val id: String) : MergeResult
}
```

Reglas de la fusión:

- El `target` gana en nombre, avatar, locale y timeZone (los del `source` se
  copian **sólo** si el `target` los tiene vacíos).
- Las `aliases` se **combinan**: gana la del `target` si existe; si no, se
  copia la del `source`.
- Las inscripciones y los hilos del `source` se reasignan al `target`.
- Los chats **remotos** ya están en el servidor con el `subject`; los locales
  se quedan en el dispositivo. La fusión no toca el servidor.

---

## 10. Repositorios y servicios

### 10.1 `ProfileRepository` (`kitoru.domain.Repositories`)

```kotlin
/** Perfil local del usuario de la app. */
interface ProfileRepository {
    suspend fun get(id: String): UserProfile?
    suspend fun list(): List<UserProfile>
    suspend fun save(profile: UserProfile)

    /** Borra un perfil. No borra sus enrollments ni sus hilos (ver ProfileMerger). */
    suspend fun delete(id: String)

    /** Busca un perfil por su vínculo institucional (para no duplicar al vincular). */
    suspend fun findByInstitution(serverId: String, subject: String): UserProfile?
}
```

Implementación: `FileProfileRepository` sigue siendo `FileBackedRepository`
sobre `config/profiles.json`; `delete` usa `removeById`, y
`findByInstitution` filtra por `institution?.serverId` / `institution?.subject`.

**El perfil activo no vive acá**: vive en los ajustes (ver §10.2), así el
repositorio de perfiles queda sin estado global.

### 10.2 Ajustes y estado del dispositivo

```kotlin
/** Ajustes de la app (perfil activo, servidor institucional, preferencias). */
@Serializable
data class AppSettings(
    /** Id del perfil activo en este dispositivo. */
    val activeProfileId: String = "",
    /** URL del servidor institucional, si lo hay. */
    val serverUrl: String = "",
    /** Id del dispositivo (UUID v4), generado una vez. */
    val deviceId: String = "",
    /** Nombre legible del dispositivo ("Notebook de Ana"). */
    val deviceLabel: String = "",
    /** Idioma por defecto de la interfaz si el perfil no define uno. */
    val locale: String = "es",
)

interface SettingsRepository {
    suspend fun get(): AppSettings
    suspend fun update(block: (AppSettings) -> AppSettings)
}
```

Implementación: `FileSettingsRepository` sobre `JsonStore<AppSettings>`
(`config/settings.json`). `JsonStore` ya soporta el patrón leer-transformar-
escribir con `Mutex`, así que encaja sin tocarlo.

### 10.3 Cambios en los repositorios existentes

```kotlin
interface CourseRepository {
    // ... existente ...
    /** Busca una cátedra por su código de inscripción (normalizado). */
    suspend fun findByJoinCode(code: String): Course?
}

interface EnrollmentRepository {
    // ... existente ...
    /** Inscripción de un usuario en una cátedra, si existe. */
    suspend fun findByCourseAndUser(courseId: String, userId: String): Enrollment?
}
```

`findByJoinCode` normaliza con `IdentityRules.normalizeJoinCode` y compara
contra `Course.joinCode` normalizado; devuelve la primera coincidencia.

### 10.4 Servicios de dominio

```kotlin
/**
 * Alta de inscripción por código.
 *
 * Devuelve un resultado tipado en vez de tirar excepciones: la UI necesita
 * distinguir "código desconocido" de "código usado" de "ya estás inscripto".
 */
class JoinCourseService(
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val directory: CourseDirectory?,   // null = sin servidor
) {
    sealed interface JoinResult {
        /** Se necesita el nombre/alias del alumno. */
        data class NeedsIdentity(val courseId: String, val courseName: String, val code: String) : JoinResult
        /** La inscripción se creó. */
        data class Ok(val enrollment: Enrollment) : JoinResult
        /** El usuario ya estaba inscripto en esa cátedra. */
        data class AlreadyEnrolled(val enrollment: Enrollment) : JoinResult
        /** El código no existe (ni local ni en el servidor). */
        data class UnknownCode(val code: String) : JoinResult
        /** Código de invitación de un solo uso ya consumido. */
        data class CodeAlreadyUsed(val code: String) : JoinResult
        /** Habría que consultar al servidor, pero no hay servidor configurado. */
        data class NoServerConfigured(val code: String) : JoinResult
    }

    suspend fun join(rawCode: String, alias: CourseAlias): JoinResult
}

/**
 * Directorio de cátedras: resuelve un código contra el servidor institucional.
 * `null` cuando la app corre sin servidor.
 */
interface CourseDirectory {
    suspend fun resolve(rawCode: String): RemoteCourse?
    suspend fun join(rawCode: String, alias: CourseAlias): RemoteJoin?
}

data class RemoteCourse(
    val id: String,
    val code: String,
    val name: String,
    val term: String = "",
    val description: String = "",
    val singleUse: Boolean = false,
)

data class RemoteJoin(
    val serverEnrollmentId: String,
    val role: CourseRole,
    val displayName: String,
    val aliasKind: AliasKind,
    val seatNumber: Int = 0,
    val apiKey: String = "",       // va al CredentialStore, nunca al perfil
    val baseUrl: String = "",
    val modelName: String = "",
)
```

```kotlin
/** Identidad: alta de perfiles, alias, vinculación institucional, fusión. */
class IdentityService(
    private val profiles: ProfileRepository,
    private val enrollments: EnrollmentRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
) {
    /** Crea un perfil local nuevo y lo deja activo. */
    suspend fun createLocalProfile(
        displayName: String,
        email: String = "",
        locale: String = settings.get().locale,
        timeZone: String = systemTimeZoneId(),
    ): UserProfile

    /** Perfil activo del dispositivo (o null si no hay ninguno). */
    suspend fun activeProfile(): UserProfile?

    /** Cambia el alias de una cátedra y re-materializa las etiquetas. */
    suspend fun setAlias(profileId: String, courseId: String, alias: CourseAlias): List<Enrollment>

    /** Vincula (o actualiza) el vínculo institucional de un perfil. */
    suspend fun linkInstitution(profileId: String, link: InstitutionLink, tokens: SessionTokens)

    /** Quita el vínculo institucional. Los chats remotos ya subidos no se borran. */
    suspend fun unlinkInstitution(profileId: String)

    /** Fusiona dos perfiles locales. */
    suspend fun mergeProfiles(targetId: String, sourceId: String): MergeResult
}
```

### 10.5 Plataforma: helpers nuevos

```kotlin
// kitoru.platform

/** UUID v4 aleatorio (desktop y Android: java.util.UUID). */
expect fun randomId(): String

/** Zona horaria IANA del sistema ("America/Argentina/Buenos_Aires"). */
expect fun systemTimeZoneId(): String

/** Idioma del sistema, tag BCP-47 simple ("es", "en", "pt-BR"). */
expect fun systemLocaleTag(): String

/** SHA-256 en hexadecimal (MessageDigest, disponible desde Android API 1). */
expect fun sha256Hex(text: String): String
```

Los cuatro van en `jvmCommonMain` (desktop y Android son JVM), así que no
hacen falta `actual` duplicados.

### 10.6 Almacenamiento en disco

```
config/
  device.json       DeviceInfo { deviceId, firstSeenAt, appVersion, platform }
  settings.json     AppSettings
  profiles.json     List<UserProfile>
  enrollments.json  List<Enrollment>
  courses.json      List<Course>
  credentials.json  CredentialStore (desktop; en Android va a SharedPreferences cifrado)
```

Nada de esto contiene una API key salvo `credentials.json` y el
`Course.modelPolicy.apiKey` del bundle que el docente comparte a propósito.

### 10.7 Exportación del roster (para el docente sin servidor)

El alumno necesita poder mandarle al docente su inscripción. Formato de
intercambio, **sin datos personales**:

```json
{
  "version": 1,
  "course": { "id": "c-ia-2026", "code": "IA-2026-1", "name": "Inteligencia Artificial" },
  "exportedAt": 1750000000000,
  "enrollments": [
    {
      "id": "e-21a7…",
      "courseId": "c-ia-2026",
      "userId": "u-8f3c…",
      "displayName": "Fantasma",
      "role": "ALUMNO",
      "joinedAt": 1750000000000,
      "visibility": "FULL",
      "aliasKind": "PSEUDONYM",
      "labelSource": "STUDENT",
      "claimPhrase": "cobre marfil sauce Bruno 42",
      "seatNumber": 0,
      "serverEnrollmentId": ""
    }
  ]
}
```

El `exportedAt` y el `deviceId` (en un campo opcional, para heurísticas) se
incluyen; el **email, el nombre real y el `subject` institucional no**.

El docente importa el archivo y el roster se fusiona por `Enrollment.id`
(importar dos veces el mismo archivo no duplica). Un `MergeSummary` le dice
cuántas filas se agregaron, cuántas coincidieron y cuántas quedaron
pendientes por resolver.

---

## 11. Compatibilidad y migración

| Cambio | Impacto |
|---|---|
| `UserProfile` pasa de 3 a 10 campos | Los `profiles.json` viejos se leen sin problema (todos los campos nuevos tienen default) |
| `Enrollment` gana 7 campos | Igual: defaults. Los `enrollments.json` viejos siguen válidos |
| `displayName` de `Enrollment` pasa a ser la etiqueta | Los archivos viejos tienen el nombre real como etiqueta: es el comportamiento de antes, no hay que migrar nada |
| `Course` gana `ownerProfileId`, `ownerSecretHash`, `staffCodeHash`, `staffCodeUses`, `codeEpoch` | Defaults vacíos: las cátedras viejas siguen siendo editables por quien las tenga (mismo comportamiento de antes) |
| `ProfileRepository.delete` es nuevo | Implementación trivial sobre `removeById` |
| `AppSettings` es nuevo | `settings.json` se crea con defaults la primera vez |

El único cambio de **comportamiento** a tener en cuenta: `ChatThread.authorName`
debe pasar a ser la etiqueta de la inscripción y no el nombre real del perfil.
Es un cambio de una línea y una ganancia de privacidad importante.

---

## 12. Casos borde

| Caso | Comportamiento definido |
|---|---|
| El alumno borra su perfil | Sus enrollments quedan huérfanos: se avisa y se ofrecen a exportar. Los hilos no se borran (son datos, no perfil) |
| El alumno cambia su `displayName` | Las etiquetas `REAL` ya materializadas **no** se actualizan solas (el hilo ya tiene `authorName`); las nuevas inscripciones sí toman el nombre nuevo. Se avisa: "tu nombre visible en el chat no cambia" |
| Dos perfiles en el mismo dispositivo con inscripción en la misma cátedra | Permitido; el roster muestra aviso de "mismo dispositivo" |
| Un alumno se inscribe dos veces con el mismo código en el **mismo** dispositivo | `findByCourseAndUser` lo detecta: *"Ya estás inscripto en esta cátedra"*. No se crea la segunda inscripción |
| Un alumno se inscribe dos veces con **distinto** alias en el mismo dispositivo | Se **actualiza** el alias existente en vez de crear una segunda inscripción (mismo `userId` + `courseId` ⇒ misma inscripción) |
| La cátedra tiene el código vacío | El alta por código no aplica: sólo por invitación o por enlace directo |
| El servidor institucional no responde | El alta local por bundle sigue funcionando; si no hay bundle, error explícito en español, sin fallar con un timeout silencioso |
| El token expira a mitad de una conversación | La conversación falla con un error claro: *"Tu sesión con la institución expiró. Volvé a vincular tu cuenta."* El chat local no se pierde |
| El alumno usa un alias con acentos o emojis | Se normaliza para comparar (sin acentos, minúsculas) pero se **muestra tal cual**. Máximo 24 caracteres |
| Cambio de hora de verano | `timeZone` es IANA, no un offset fijo: el agrupamiento por día de la analítica usa la zona del alumno |
| El docente renombra a "Alumno 037" y el alumno cambia su alias | Gana el docente (`labelSource = TEACHER`); el alumno ve que su etiqueta fue reemplazada y puede pedir que la liberen |

---

## 13. Resumen de decisiones

| Decisión | Motivo |
|---|---|
| Identidad local por defecto, sin cuenta | La app tiene que funcionar en un dispositivo sin ninguna infraestructura detrás |
| Vincular en vez de migrar | No se pierde el historial local ni se invalidan los `userId` |
| `userId` local opaco, `subject` institucional aparte | El docente nunca ve identificadores institucionales sin consentimiento |
| Alias en dos capas (preferencia ↔ etiqueta materializada) | Permite que el docente reetiquete sin romper la preferencia del alumno, y viceversa |
| `ChatThread.authorName` = etiqueta | Si no, el log del servidor filtra la identidad real |
| Identidad docente por secretos de curso | Sin servidor no hay a quién autenticar; la posesión del secreto es el modelo honesto |
| Frase de verificación por inscripción | Convierte un problema invisible (¿quién es quién?) en uno verificable en 10 segundos |
| Sin forzar login | El servidor institucional sabe menos cuando el alumno no se autentica: es el comportamiento por defecto correcto |
| API key fuera del perfil | La key es un permiso de consumo, no una identidad |

---

## 14. Pendientes / fuera de alcance

- **Palabras de la frase de verificación**: hace falta una lista fija de ~256
  palabras cortas en español, versionada, para que la frase sea reproducible
  entre dispositivos. Ubicación propuesta:
  `core/src/commonMain/kotlin/kitoru/domain/ClaimWords.kt`.
- **Roster y analítica**: el documento de analítica debería consumir
  `Enrollment` (no `String`) para `inactiveStudents`, y usar siempre
  `displayName` como etiqueta.
- **Keychain real en desktop**: la interfaz `CredentialStore` está lista;
  implementar DPAPI/Keychain/libsecret es trabajo de plataforma, no de
  dominio.
- **Fusión de perfiles en la UI**: la lógica está en `ProfileMerger`; falta la
  pantalla de confirmación con el detalle de qué se va a mover.
- **Modo anónimo impuesto por el docente** (ver a todos como "Alumno NNN"):
  es interesante para demos en clase, pero choca con la necesidad de calificar.
  Queda como decisión de producto abierta, no como parte de este diseño.
