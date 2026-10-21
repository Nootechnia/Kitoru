# 02 · Privacidad y consentimiento

> Diseño del modelo de **privacidad** de Kitoru: qué ve el docente de cada
> alumno, con qué consentimiento explícito, cómo el alumno lo sabe y cómo lo
> revoca.
>
> Depende de: `docs/design/CONTEXTO.md`,
> `core/src/commonMain/kotlin/kitoru/domain/{Roles,Course,Analytics,AnalyticsAnalyzer,Chat,ModelPolicy}.kt`
> y de los documentos `docs/design/domain/{03-inscripciones,08-permisos,09-identidad}.md`,
> `docs/design/teacher/{05-config-modelo,07-chats,08-alumnos}.md` y
> `docs/design/platform/03-kitoru-server-api.md`.
>
> Este documento **no reemplaza** a esos documentos: los completa con la capa de
> consentimiento y fija los textos literales que ve el alumno.

---

## 0. Resumen de decisiones

| # | Decisión | Por qué |
|---|---|---|
| **D1** | La visibilidad efectiva es el **mínimo** de cuatro cosas: el tope por rol del observador, el tope del docente sobre ese alumno, el techo declarado por el alumno y el override del hilo. | Una sola fórmula, testeable, sin casos raros. El "el docente no ve lo que no debe" (regla 6 del CONTEXTO) queda escrito en una línea. |
| **D2** | **Fail-closed**: sin un consentimiento explícito y registrado (`ConsentState.GRANTED`), el techo del alumno es `METRICS_ONLY`, nunca `FULL`. | El silencio no es consentimiento. Un `Enrollment` viejo o importado nunca debe abrir el texto por inercia. |
| **D3** | Los **defaults** son: IA alojada → `FULL` **avisado** (con clic activo del alumno); IA local → `NONE`; IA híbrida → `FULL` en lo alojado, `NONE` en lo local; paquete importado → lo que diga el alumno al exportar. | El modo alojado implica que el servidor ve el texto: no se puede ser "privado por sorpresa". El modo local no implica nada: se parte de cero. |
| **D4** | **El alumno decide siempre.** El docente puede *bajar* la visibilidad sin justificar y *subirla* sólo con consentimiento del alumno o justificación escrita. El tope por rol acota al observador. | Regla asimétrica: bajar privacidad es libre, subirla deja rastro. Coherente con `teacher/08-alumnos.md` D1. |
| **D5** | El consentimiento se **versiona y se hashea** (`consent.textVersion`, `consent.textHash`): queda prueba de *qué texto* se mostró. | Sin esto, "el alumno consintió" es una afirmación no verificable. |
| **D6** | La granularidad es **por cátedra**, con **override por hilo**. Nunca por mensaje. | El mensaje individual es demasiado fragmentario (36 decisiones para 36 mensajes) y no protege nada que el hilo no proteja. |
| **D7** | Un agregado es dato no personal si **≥ K alumnos** lo sostienen: **K = 5** para mostrar, **K = 10** para exportar, con **supresión secundaria**. | k-anonimidad con criterio explícito, no "más o menos". El detalle es lo que separa "analítica" de "vigilancia". |
| **D8** | **Pseudónimo por defecto**: el docente ve "Alumno 037" (número correlativo estable de la cátedra). El nombre real sólo si el alumno lo autoriza explícitamente y por un plazo. | Es el único default que sobrevive a ser compartido fuera del aula (exportes, informes, capturas). |
| **D9** | La declaración sobre el tratamiento de los prompts **se deriva de la respuesta del servidor** (`X-Kitoru-Privacy`), no está hardcodeada en la app. | Si el servidor no garantiza "no training", la app no puede decir que sí lo garantiza. |
| **D10** | Borrado **lógico** (30 días, recuperable) y borrado **físico** (definitivo). Si el docente ya leyó el chat, **no se puede des-leer**: se le notifica y se le exige decidir. | La respuesta honesta al derecho de supresión (GDPR art. 17 y 19, LOPD art. 14 bis). |
| **D11** | Todo acceso del docente a datos de alumnos queda en un **registro mínimo** (`AccessLogEntry`), que el alumno puede leer y exportar. | Sin registro, "el docente no ve lo que no debe" es una promesa sin prueba. |
| **D12** | La app muestra un **resumen informativo** de la política; el texto legal vinculante es el de la institución (el controlador del tratamiento). | Kitoru es la herramienta: no redacta la política de la facultad ni la sustituye. |

---

## 1. El principio rector

### 1.1 La frase

> **El docente no ve lo que el alumno no le da.**

Todo lo que sigue es la traducción técnica de esa frase. "Lo que el alumno da" tiene
tres capas, y las tres se modelan:

1. **Lo que el alumno autoriza** (`Enrollment.visibility`): su techo. Nadie lo
   supera, ni el titular.
2. **Lo que el docente tiene derecho** (`Course.visibilityPolicy`): el tope por
   rol del observador. Un ayudante no ve texto aunque el alumno lo autorice.
3. **Lo que el docente pidió** (`Enrollment.visibilityCap`): un tope que el docente
   puede *bajar* libremente. Nunca sube por encima del techo del alumno.

### 1.2 La fórmula

`ChatVisibility` (`Roles.kt`) ya tiene tres estados y `showsText` / `showsMetrics`.
Le agregamos el nivel (como propone `teacher/08-alumnos.md` §2.1) y un combinador:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Roles.kt
@Serializable
enum class ChatVisibility(
    val label: String,
    val description: String,
    /** 0 = más restrictivo. NONE(0) < METRICS_ONLY(1) < FULL(2). */
    val level: Int,
) {
    FULL("Completo",
        "El docente ve el texto de las conversaciones y el detalle de cada mensaje.", 2),
    METRICS_ONLY("Solo métricas",
        "El docente ve estadísticas y calidad de las respuestas, pero no el texto.", 1),
    NONE("Oculto",
        "El docente no ve ni el texto ni las estadísticas de este alumno.", 0),
    ;

    val showsText: Boolean get() = this == FULL
    val showsMetrics: Boolean get() = this != NONE

    /** El más restrictivo de los dos. Conmutativo y monótono. */
    fun atMost(other: ChatVisibility): ChatVisibility =
        if (other.level <= level) other else this

    companion object {
        /** El más restrictivo de los tres. */
        fun atMost(a: ChatVisibility, b: ChatVisibility, c: ChatVisibility): ChatVisibility =
            a.atMost(b).atMost(c)

        fun fromName(name: String): ChatVisibility? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

La visibilidad efectiva se calcula en **un solo lugar**, `AccessControl`:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AccessControl.kt

object AccessControl {

    /**
     * Techo que el propio alumno se impone (D2: fail-closed).
     *
     * - Un docente no es objeto de analítica de alumnos: no hay techo.
     * - Con consentimiento explícito rige lo que el alumno declaró.
     * - Sin consentimiento explícito, el techo es METRICS_ONLY: nunca FULL.
     */
    fun studentCeiling(enrollment: Enrollment): ChatVisibility =
        when {
            enrollment.role != CourseRole.ALUMNO -> ChatVisibility.FULL
            enrollment.consent.state == ConsentState.GRANTED -> enrollment.visibility
            else -> ChatVisibility.METRICS_ONLY
        }

    /**
     * Visibilidad efectiva del observador sobre los chats de [student].
     *
     * @param thread si no es null, se aplica también el override del hilo (§5).
     */
    fun effectiveVisibility(
        course: Course,
        observer: Enrollment?,
        student: Enrollment,
        thread: ChatThread? = null,
    ): ChatVisibility {
        val roleCap = course.visibilityPolicy.maxFor(observer?.role ?: CourseRole.TITULAR)
        var v = ChatVisibility.atMost(roleCap, student.visibilityCap)
        v = v.atMost(studentCeiling(student))
        if (thread != null) v = v.atMost(threadVisibility(student, thread))
        return v
    }

    /** ¿El observador puede leer el texto? */
    fun canSeeChatText(observer: Enrollment?, student: Enrollment, course: Course,
                       thread: ChatThread? = null): Boolean {
        if (observer == null) return false
        if (observer.userId == student.userId) return true // cada uno ve lo suyo
        if (!observer.can(Capability.VIEW_CHAT_TEXT)) return false
        return effectiveVisibility(course, observer, student, thread).showsText
    }

    /** ¿El observador puede ver las métricas? */
    fun canSeeMetrics(observer: Enrollment?, student: Enrollment, course: Course): Boolean {
        if (observer == null) return false
        if (observer.userId == student.userId) return true
        if (!observer.can(Capability.VIEW_ANALYTICS)) return false
        return effectiveVisibility(course, observer, student).showsMetrics
    }
}
```

### 1.3 Las tres reglas que no se negocian

1. **Subir es más difícil que bajar.** El alumno baja su visibilidad cuando
   quiere, en un clic, sin justificar. El docente también puede bajar la de un
   alumno sin justificar. Subirla requiere consentimiento del alumno o una
   justificación escrita, que queda registrada y se le avisa al alumno.
2. **Nunca se amplía por silencio.** Si el alumno no ha elegido explícitamente,
   el sistema asume `METRICS_ONLY` (D2). Un `Enrollment` importado de un CSV
   viejo, una inscripción creada por el docente a mano o un archivo de cátedra
   compartido: todos empiezan en `METRICS_ONLY` hasta que el alumno confirme.
3. **La restricción se aplica antes de leer, no al mostrar.** Si el texto no se
   carga, no se puede filtrar por accidente, no queda en memoria y no aparece en
   un volcado de memoria. Es la misma regla que `teacher/07-chats.md` §3.1 y que
   aplica el Kitoru-Server (`platform/03-kitoru-server-api.md` §8.4).

### 1.4 Reconciliación de nombres con los demás documentos

Los documentos previos usan tres nombres para tres cosas distintas. Este documento
fija el **nombre canónico**:

| Concepto | Campo canónico | Alias en otros documentos |
|---|---|---|
| Techo declarado por el alumno | `Enrollment.visibility` | `studentCap` (`teacher/08-alumnos.md` §2.2), `visibilityCeiling` (`domain/08-permisos.md` §9) |
| Tope impuesto por el docente | `Enrollment.visibilityCap` | `visibility` en `teacher/08-alumnos.md` (significado invertido) |
| Tope por rol del observador | `Course.visibilityPolicy.maxFor(role)` | `VisibilityPolicy` (`domain/03-inscripciones.md` §5.2) |
| Constancia del consentimiento | `Enrollment.consent` | `VisibilityConsent` (`teacher/08-alumnos.md` §2.3) |

**Deuda a corregir en los otros documentos** (no bloquea este diseño, pero hay que
alinearlos antes de implementar):

- `domain/03-inscripciones.md` §2.1: `visibility` es "lo que declara el alumno"
  ✔ compatible; `visibilityCap` es "el tope del docente" ✔ compatible.
- `teacher/08-alumnos.md` §2.2: invierte los dos nombres. **Hay que cambiarlo** a
  `visibility` (alumno) + `visibilityCap` (docente) + `consent`.
- `domain/08-permisos.md` §9: `Enrollment.visibilityCeiling` → renombrar a
  `Enrollment.visibility`, y las condiciones `TargetAllowsText` /
  `TargetAllowsMetrics` deben pasar a leer `AccessControl.studentCeiling(...)`,
  no `visibility` crudo (si no, el fail-closed no se aplica).
- `platform/03-kitoru-server-api.md` §8.4: la fórmula del servidor
  (`min(roster, declaración)`) debe pasar a `min(roster, declaración, FAIL_CLOSED)`
  y a distinguir el override por hilo (§5).

---

## 2. Los defaults: qué ve el docente si nadie dice nada

### 2.1 La tabla de defaults

| Modo del alumno | Dónde corre el modelo | **Default de visibilidad** | Aviso al alumno | Puede el docente ver el texto por defecto |
|---|---|---|---|---|
| **IA alojada** (`ModelMode.REMOTE`, key de la cátedra) | Servidor de la institución | **`FULL`** (avisado) | **Sí**, bloqueante en la inscripción (§3.2) | Sí |
| **IA alojada** (key propia del alumno contra el mismo servidor) | Servidor de la institución | **`FULL`** (avisado) | Sí, el mismo aviso + la nota de que la clave es personal | Sí |
| **IA local** (`ModelMode.LOCAL`) | El dispositivo del alumno | **`NONE`** | Sí, al cambiar de modo (§3.4) | No |
| **IA local declarada** (el alumno reporta uso local al servidor) | Mixto | **`NONE`** para lo local; lo que rija para lo alojado | Sí (texto de "reporte de uso" del doc del servidor §9.4) | No (para lo local) |
| **Paquete importado** (`.kitoru` que el alumno comparte a mano) | Ya no importa: el texto viaja en el archivo | **`NONE`** hasta que el alumno exporte con nivel `FULL` | No: el acto de compartir **es** el consentimiento | No, salvo que el paquete se exporte con `FULL` |
| **Inscripción creada por el docente** (alta manual, CSV) | El que sea | **`METRICS_ONLY`** (fail-closed, D2) | Aviso al alumno cuando entra por primera vez | No |
| **Inscripción antigua / sin consentimiento** (`consent.state = NOT_REQUESTED`) | El que sea | **`METRICS_ONLY`** (fail-closed, D2) | Aviso: *"Confirmá qué puede ver tu docente"* | No |

### 2.2 Quién decide

| Decisión | Puede tomarla | Restricciones |
|---|---|---|
| Cuánto ve el docente de **un alumno** | **El alumno** (`ENROLLMENT_SET_OWN_VISIBILITY`) | Su techo nunca sube solo; para subirlo usa la solicitud (§3.5) |
| Cuánto ve **un ayudante** | El titular (`Course.visibilityPolicy`) | Tope por rol; por defecto `METRICS_ONLY` (nunca texto) |
| Cuánto ve **el titular** | El titular (`Course.visibilityPolicy`) | Tope por rol; por defecto `FULL`, pero effectiveness limitada por el techo del alumno |
| Cuánto ve el docente de **un hilo concreto** | **El alumno** (`ChatThread.visibilityOverride`) | Sólo puede **bajar** respecto del nivel de la cátedra; el docente no lo sube |
| Si se **audit**a la lectura | nobody: es obligatorio | `AccessLogEntry` es append-only |

El **default** lo pone el sistema; la **decisión** es siempre del alumno cuando
hay datos que los puedan revelar. "El docente ve el texto" nunca es un default
mudo: o el alumno lo confirmó con un clic (IA alojada), o lo pidió (paquete
compartido a mano).

### 2.3 Por qué "alojada = FULL avisado" y "local = NONE"

- **IA alojada**: el servidor de la institución ve el prompt por necesidad técnica
  (es el que corre el modelo). Pretender que no lo ve sería mentira. La
  consecuencia honesta es que el dato **está en la institución**, y la pregunta
  correcta no es "¿el servidor lo ve?" sino "¿quién dentro de la institución lo
  lee?". La respuesta de este diseño: **sólo el docente, y con registro**. Por
  eso el default es `FULL` pero *avisado*: el alumno que no quiere eso tiene una
  salida de un clic (bajar a `METRICS_ONLY` o `NONE`), y una salida mejor aún
  (usar IA local, §3.4).
- **IA local**: el servidor no ve nada, no por política sino por arquitectura
  (`platform/03-kitoru-server-api.md` §9.1). El default lógico es `NONE`: si el
  alumno no comparte, no hay nada que mostrar. `METRICS_ONLY` queda disponible
  como punto intermedio para quien quiere que su cátedra vea que trabaja.

### 2.4 La revocación y su consecuencia honesta

Bajar la visibilidad **no borra lo ya visto**. La app lo dice, en el mismo
diálogo donde se baja, con estas palabras exactas:

> "Ahora tu docente va a ver menos. Lo que ya leyó antes queda: no se puede
> deshacer. Si querés que desaparezca, usá «Borrar mis datos»."

Y al final de la pantalla "Tus datos", la nota permanente:

> "Kitoru registra cada vez que un docente abre tus conversaciones. Podés ver el
> registro completo en «Accesos del docente»."

---

## 3. El consentimiento informado (textos literales)

### 3.1 Reglas de redacción

1. **Vos, siempre.** La app habla de voseo (es el registro del proyecto:
   "Ingresá", "Elegí", "Consultá").
2. **Corto.** Cada bloque tiene ≤ 4 frases y ≤ 400 caracteres. Si no entra, es
   un texto legal, no un texto de consentimiento.
3. **Concreto, no abstracto.** No "tus datos personales" sino "el texto de tus
   conversaciones". No "tratamiento" sino "sale de tu computadora".
4. **Describe el efecto, no la intención.** "Tu docente puede leer el texto de
   tus conversaciones" (no "se comparte con fines docentes").
5. **Dice qué NO pasa.** Todo bloque dice explícitamente qué queda fuera.
6. **Una acción por diálogo.** El botón principal es una sola acción, con
   verbo en primera persona.
7. **Nada pre-marcado.** Casillas y radios arrancan sin marcar. Un checkbox
   pre-marcado no es consentimiento (RGPD art. 7: acción libre, específica,
   informada).
8. **El texto exacto se versiona y se hashea** (D5), de modo que la prueba del
   consentimiento apunte a un texto concreto y no a "lo que decía la app".

### 3.2 Texto A — el aviso en la inscripción (IA alojada)

> **Aparece en:** pantalla de inscripción con el código, antes de crear la
> `Enrollment`. Es bloqueante: sin confirmar, no se inscribe.

```
Título: Tu cátedra usa una IA alojada en la universidad
─────────────────────────────────────────────────────

Vas a usar el modelo de la cátedra (Qwen3-8B) que corre en los servidores de
la Facultad. Eso significa que tus conversaciones pasan por esos servidores y
quedan guardadas ahí.

Mientras uses esa IA, tu docente puede leer el texto de tus conversaciones y
ver cuántos mensajes escribís, qué materiales se consultan y qué tan bien
responde el modelo. Si preferís que no lea nada, lo cambiás vos, en un clic,
desde «Tus datos»: nadie, ni siquiera el titular, puede ver más de lo que vos
autorizás.

Si no estás de acuerdo, podés usar una IA en tu propio dispositivo: en ese
caso lo que escribís no sale de tu computadora y tu docente no ve nada.

[✓] Entendí lo anterior y acepto usar la IA de la cátedra

                                  [Usar la IA de la cátedra]  [Prefiero IA local]
```

**Variante si el servidor es de un tercero** (no es de la institución), §8.2:

```
⚠ Esta cátedra usa una IA de terceros (OpenAI), fuera de la red de la
universidad. Tus conversaciones se envían a ese servicio para generar las
respuestas, y Kitoru no puede garantizar que ese servicio no las use para
entrenar sus modelos. La cátedra declara que no lo hace.
```

### 3.3 Texto B — el diálogo de elección de visibilidad

> **Aparece en:** "Tus datos → Qué ve tu docente → Cambiar". También al
> cambiar por primera vez de `METRICS_ONLY` a `FULL`.

```
¿Qué querés que tu docente vea de tus conversaciones?
──────────────────────────────────────────────────────

( ) Nada
    Tu docente no ve ni el texto ni las estadísticas. Lo único que ve es que
    estás inscripto en la cátedra.

( ) Solo métricas
    Tu docente ve cuántos mensajes escribís, qué materiales se consultan,
    cuánto tarda el modelo y si las citas están respaldadas por el material.
    No ve el texto de tus preguntas ni el de las respuestas.

( ) Todo
    Tu docente puede leer el texto completo de tus conversaciones. La cátedra
    puede usar eso para evaluar cómo usás la IA.

──────────────────────────────────────────────────────
Esta decisión es tuya y podés cambiarla cuando quieras. Si la bajás, lo que
tu docente ya leyó no se borra.

                                     [Cancelar]  [Guardar]
```

### 3.4 Texto C — re-consentimiento al cambiar a IA alojada

> **Aparece en:** el alumno cambia el modelo de local a alojado, o se une a una
> cátedra con `ModelMode.REMOTE` después de haber usado local.

```
Vas a pasar a la IA de la cátedra
──────────────────────────────────

A partir de ahora tus conversaciones pasan por los servidores de la Facultad
y tu docente puede leerlas (salvo que bajes la visibilidad en «Tus datos»).

Vas a ver este aviso cada vez que cambies de un modelo a otro.

                                  [Cancelar]  [Sí, cambiar a la IA de la cátedra]
```

### 3.5 Texto D — el docente pide ver el texto

> **Aparece en:** la app del alumno, como respuesta a una solicitud del docente
> (`VisibilityRequest`, `teacher/08-alumnos.md` §6.4).

```
Tu docente pide ver el texto de tus conversaciones
───────────────────────────────────────────────────

Ahora mismo tu docente ve: Solo métricas
Si lo subís a Todo, podrá leer el texto de tus conversaciones.

Motivo (opcional, lo lee tu docente):
[____________________________________________]

                        [Cancelar]  [Enviarle la solicitud]
```

Y en la app del docente, la banda de la solicitud:

```
┌───────────────────────────────────────────────────────────────────────┐
│ 📩 Ana Pérez pide que veas el texto de sus conversaciones.            │
│    "Quiero que revises mis respuestas del parcial antes de entregar." │
│    · pedida hace 2 h                                                  │
│    [Aprobar]  [Denegar]  [Ver detalle]                               │
└───────────────────────────────────────────────────────────────────────┘
```

Al **aprobar**, la app del alumno muestra:

```
Tu docente aprobó tu solicitud: ahora ve el texto de tus conversaciones.
Lo vas a ver reflejado en «Tus datos».
```

Al **denegar** (motivo obligatorio, ≥ 10 caracteres):

```
Tu docente denegó tu solicitud: "preferimos que compares con el resto de la
clase, no con tu caso particular".
```

### 3.6 Texto E — el alumno baja la visibilidad

> **Aparece en:** "Tus datos → Qué ve tu docente → Cambiar", cuando se elige un
> nivel menor al actual.

```
Vas a bajar la visibilidad a: Solo métricas
──────────────────────────────────────────

Tu docente va a dejar de ver el texto de tus conversaciones. A partir de ahora
sólo ve cantidades, tiempos y calidad de las respuestas.

Lo que ya leyó antes no se borra: no se puede deshacer.
Si querés que también desaparezca lo que ya leyó, usá «Borrar mis datos».

                                       [Cancelar]  [Guardar]
```

### 3.7 La prueba del consentimiento (D5)

El consentimiento no es un booleano: es un registro.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Privacy.kt

/** Estado del consentimiento de visibilidad de un alumno. */
@Serializable
enum class ConsentState(val label: String) {
    /** Nunca se le preguntó. Fail-closed: sólo métricas (D2). */
    NOT_REQUESTED("Sin consultar"),

    /** El alumno pidió más; el docente todavía no respondió. */
    PENDING("Solicitado"),

    /** El alumno confirmó el nivel elegido. */
    GRANTED("Concedido"),

    /** El docente denegó la solicitud. */
    DENIED("Denegado"),

    /** El alumno retiró el consentimiento. */
    WITHDRAWN("Retirado"),
}

/**
 * Constancia del consentimiento de visibilidad.
 *
 * Se escribe en [Enrollment.consent] y **acompaña** a la decisión: dice qué
 * nivel autorizó el alumno, cuándo, contra qué versión del texto y con qué
 * hash. Sin esto, "el alumno consintió" es una afirmación no verificable.
 */
@Serializable
data class VisibilityConsent(
    val state: ConsentState = ConsentState.NOT_REQUESTED,

    /** Nivel que el alumno autorizó (coincide con [Enrollment.visibility]). */
    val level: ChatVisibility = ChatVisibility.METRICS_ONLY,

    /** Epoch ms del clic. */
    val grantedAt: Long = 0L,

    /** Epoch ms de la revocación (0 = nunca). */
    val withdrawnAt: Long = 0L,

    /** Versión del texto de consentimiento mostrado (ej. "2026-04-01"). */
    val textVersion: String = "",

    /** SHA-256 del texto exacto mostrado, para poder probarlo. */
    val textHash: String = "",

    /** Modo de IA en el momento del consentimiento (LOCAL / REMOTE / HYBRID). */
    val aiMode: StudentAiMode = StudentAiMode.HOSTED,

    /** Nota opcional del alumno. */
    val note: String = "",
)
```

El texto vive en un objeto versionado, para que el hash sea reproducible:

```kotlin
/**
 * Los textos de consentimiento, versionados.
 *
 * El hash se calcula sobre el texto *exacto* que se muestra. Si el texto
 * cambia, cambia el hash, y las inscripciones viejas siguen apuntando al
 * texto con el que el alumno aceptó.
 */
object ConsentTexts {

    /** Versión vigente. Cambia cuando cambia cualquier texto de este objeto. */
    const val VERSION = "2026-04-01"

    /** Texto A: aviso de inscripción con IA alojada. */
    const val ENROLL_HOSTED = """..."""

    /** Texto B: elección de visibilidad. */
    const val CHOOSE_VISIBILITY = """..."""

    /** Texto C: re-consentimiento al pasar a IA alojada. */
    const val SWITCH_TO_HOSTED = """..."""

    /** Texto D: solicitud del docente. */
    const val TEACHER_REQUEST = """..."""

    /** Texto E: baja de visibilidad. */
    const val LOWER_VISIBILITY = """..."""

    /** Hash de un texto concreto. */
    fun hash(text: String): String = sha256Hex(text)
}
```

`Enrollment` queda así:

```kotlin
@Serializable
data class Enrollment(
    val id: String,
    val courseId: String,
    val userId: String,
    val displayName: String,
    val role: CourseRole = CourseRole.ALUMNO,
    val joinedAt: Long = 0L,

    /** Techo declarado por el ALUMNO. Nadie puede superarlo. */
    val visibility: ChatVisibility = ChatVisibility.FULL,

    /** Tope que el DOCENTE impone. Sólo puede bajar. */
    val visibilityCap: ChatVisibility = ChatVisibility.FULL,

    /** Constancia del consentimiento (D5). */
    val consent: VisibilityConsent = VisibilityConsent(),

    // ... resto de campos de domain/03-inscripciones.md
)
```

> **Nota de migración**: `enrollments.json` existentes se leen sin cambios (todos
> los campos nuevos tienen default). Pero `consent.state = NOT_REQUESTED` ⇒
> efectivo `METRICS_ONLY` (D2). Es un cambio de comportamiento **deliberado y
> seguro**: las inscripciones viejas dejan de exponer texto hasta que el alumno
> confirme. La app muestra el aviso: *"Confirmá qué puede ver tu docente"* con
> un botón que concede `FULL` si el alumno quiere.

---

## 4. El derecho de saber: la pantalla "Tus datos"

### 4.1 Qué muestra

> **Ruta**: Ajustes (o el menú del perfil) → **Tus datos**.
> Também se llega desde el chat: `⋮` → "Tus datos y privacidad".

#### Desktop

```
┌─ Ventana Kitoru ────────────────────────────────────────────────────────────────┐
│ ┌──────┐  Tus datos                                                             │
│ │ Chat │  ─────────────────────────────────────────────────────────────────────  │
│ │ Mat. │  Juan Pérez · Alumno de IA-2026-1 · 4 cátedras                        │
│ │ ⋮    │  ┌──────────────────────────────────────────────────────────────────┐   │
│ │      │  │ 👁 Qué ve tu docente                                            │   │
│ │      │  │                                                                    │   │
│ │      │  │ En IA-2026-1 (Inteligencia Artificial)                          │   │
│ │      │  │ Estado: Todo                                                     │   │
│ │      │  │ Desde el 12/03/2026                                             │   │
│ │      │  │                                                                    │   │
│ │      │  │ Lo que tu docente PUEDE ver:                                   │   │
│ │      │  │   · El texto de tus preguntas y de las respuestas del modelo   │   │
│ │      │  │   · Cuántos mensajes escribís y cuántas conversaciones abrís  │   │
│ │      │  │   · Qué materiales se consultan                                 │   │
│ │      │  │   · Cuánto tarda el modelo y cuántos tokens usa                  │   │
│ │      │  │   · Si las citas de las respuestas están respaldadas             │   │
│ │      │  │                                                                    │   │
│ │      │  │ Lo que tu docente NO PUEDE ver:                                │   │
│ │      │  │   · Tu correo electrónico                                        │   │
│ │      │  │   · Tus conversaciones de otras cátedras                         │   │
│ │      │  │   · Los chats que marcaste como privados (2)                    │   │
│ │      │  │                                                                    │   │
│ │      │  │ En Redes-2026-1: Solo métricas                                   │   │
│ │      │  │ En Física-2026-1: Nada (usás IA local)                          │   │
│ │      │  │                                                                    │   │
│ │      │  │ [ Cambiar ]  [ Pedir que vea todo ]                              │   │
│ │      │  └──────────────────────────────────────────────────────────────────┘   │
│ │      │  ┌──────────────────────────────────────────────────────────────────┐   │
│ │      │  │ 💬 Tus conversaciones — 18 en 4 cátedras                        │   │
│ │      │  │                                                                    │   │
│ │      │  │ IA-2026-1                                                         │   │
│ │      │  │   Duda sobre backprop          12/03 · 8 mensajes ·visible      │   │
│ │      │  │   Parcial 2 - normal            12/03 · 14 mensajes ·visible     │   │
│ │      │  │   Consultas sobre la cita 2   11/03 · 6 mensajes ·oculta  🔒    │   │
│ │      │  │   Sin título                   11/03 · 3 mensajes ·oculta  🔒    │   │
│ │      │  │   + 14 más                                                         │   │
│ │      │  │                                                                    │   │
│ │      │  │ Redes-2026-1: 3 conversaciones · todas ocultas                   │   │
│ │      │  └──────────────────────────────────────────────────────────────────┘   │
│ │      │  ┌──────────────────────────────────────────────────────────────────┐   │
│ │      │  │ 📊 Tus métricas — sólo las ve tu docente si lo autorizaste       │   │
│ │      │  │ 142 mensajes · 7 conversaciones · 11 días activos · 88 % de      │   │
│ │      │  │ citas verificadas · 2.1 s de latencia media                      │   │
│ │      │  └──────────────────────────────────────────────────────────────────┘   │
│ │      │  ┌──────────────────────────────────────────────────────────────────┐   │
│ │      │  │ 🛡 Accesos del docente — 14 desde el 12/03/2026                 │   │
│ │      │  │ 13/03 10:15 · Dra. Pérez abrió 3 conversaciones tuyas (texto)    │   │
│ │      │  │ 12/03 18:30 · Dra. Pérez vio tus métricas                       │   │
│ │      │  │ 12/03 09:02 · Lic. Gómez exportó tus datos                      │   │
│ │      │  │ [ Ver los 14 ]  [ Exportar CSV ]                                  │   │
│ │      │  └──────────────────────────────────────────────────────────────────┘   │
│ │      │  ┌──────────────────────────────────────────────────────────────────┐   │
│ │      │  │ 🗑 Tus datos                                                      │   │
│ │      │  │ Descargar una copia de todo lo que tenés en Kitoru  [ Descargar ] │   │
│ │      │  │ Borrar mis datos  (conversaciones, métricas y perfil)             │   │
│ │      │  │                                             [ Borrar mis datos ]  │   │
│ │      │  └──────────────────────────────────────────────────────────────────┘   │
│ └──────┘  [ ✕ ]                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

#### Android

```
┌───────────────────────────────┐
│ ‹ Tus datos                  │
├───────────────────────────────┤
│ 👁 Qué ve tu docente          │
│ IA-2026-1 · Estado: Todo      │
│ Desde el 12/03/2026           │
│ ▸ Lo que tu docente puede ver │
│ ▸ Lo que tu docente no puede  │
│   ver                          │
│ [ Cambiar ] [ Pedir que vea   │
│               todo ]           │
├───────────────────────────────┤
│ 💬 Tus conversaciones — 18    │
│ IA-2026-1                     │
│ ▸ Duda sobre backprop          │
│   12/03 · 8 msj · visible      │
│ ▸ Consultas sobre la cita 2   │
│   11/03 · 6 msj · 🔒 oculta    │
│ + 14 más                       │
├───────────────────────────────┤
│ 🛡 Accesos del docente — 14   │
│ 13/03 10:15 · Dra. Pérez      │
│ abrió 3 conversaciones tuyas  │
├───────────────────────────────┤
│ 🗑 Tus datos                   │
│ [ Descargar mis datos ]        │
│ [ Borrar mis datos ]            │
└───────────────────────────────┘
```

**Reglas de la pantalla:**

1. Es una **fotografía**, no un formulario: cada línea dice un hecho ("142
   mensajes", "desde el 12/03"), no una promesa ("trataremos tus datos de
   forma segura").
2. Los datos del docente están **agrupados por cátedra**, porque así es como el
   docente los tiene. El alumno no tiene queadre la información.
3. El ítem "🔒 oculta" por hilo explica **por qué** está oculta ("la marcaste
   como privada el 11/03").
4. El acceso a la pantalla **queda auditado en el lado del alumno**, no en el del
   docente: es el propio alumno mirando sus datos.

### 4.2 El modelo: `DataInventory`

La pantalla no lee repositorios a mano: pide un inventario. Es una **función
pura** (testeable, sin disco ni red) que responde exactamente "qué tiene el
docente sobre mí".

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/DataInventory.kt

/** Un hilo que el docente tiene (o no tiene) sobre un alumno. */
data class HeldThread(
    val threadId: String,
    val courseId: String,
    /** El título es texto: si no hay permiso de texto, no se muestra. */
    val title: String,
    val titleVisible: Boolean,
    val messages: Int,
    val replies: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** ¿El docente puede leer el texto de este hilo? */
    val textVisible: Boolean,
    /** Override explícito del alumno sobre este hilo. */
    val override: ChatVisibility?,
)

/** Fotografía de lo que el docente tiene sobre un alumno. */
data class TeacherHolding(
    val enrollment: Enrollment,
    /** Visibilidad efectiva del titular de la cátedra. */
    val effectiveVisibility: ChatVisibility,
    /** Desde cuándo (primer hilo compartido, o la inscripción si no hay nada). */
    val since: Long,
    val threads: List<HeldThread>,
    /** Métricas agregadas, si el docente las puede ver. */
    val metrics: StudentAnalytics?,
    /** Accesos del docente registrados sobre este alumno. */
    val accesses: List<AccessLogEntry>,
) {
    /** El docente no tiene nada: ni texto, ni métricas, ni hilos. */
    val holdsNothing: Boolean get() = effectiveVisibility == ChatVisibility.NONE
}

/**
 * Calcula exactamente qué tiene un docente sobre un alumno.
 *
 * Función pura: mismos datos de entrada → mismo inventario. Es la única fuente
 * de la pantalla "Tus datos" y del diálogo de borrado.
 */
object DataInventory {

    fun of(
        course: Course,
        student: Enrollment,
        observer: Enrollment?,          // el docente; null = el propio alumno
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        accesses: List<AccessLogEntry>,
    ): TeacherHolding {
        val eff = AccessControl.effectiveVisibility(course, observer, student)
        val myThreads = threads.filter { it.authorId == student.userId }
        val held = myThreads.map { t ->
            val msgs = messages.filter { it.threadId == t.id }
            val threadEff = AccessControl.effectiveVisibility(course, observer, student, t)
            HeldThread(
                threadId = t.id,
                courseId = t.courseId,
                title = if (threadEff.showsText) t.title else "",
                titleVisible = threadEff.showsText,
                messages = msgs.count { it.isUser },
                replies = msgs.count { it.isAssistant },
                firstAt = msgs.minOfOrNull { it.createdAt } ?: t.createdAt,
                lastAt = msgs.maxOfOrNull { it.createdAt } ?: t.updatedAt,
                textVisible = threadEff.showsText,
                override = t.visibilityOverride,
            )
        }
        val metrics = if (eff.showsMetrics) {
            AnalyticsAnalyzer.analyze(course, listOf(student), myThreads, messages).perStudent.firstOrNull()
        } else null
        return TeacherHolding(
            enrollment = student,
            effectiveVisibility = eff,
            since = held.minOfOrNull { it.firstAt } ?: student.joinedAt,
            threads = held,
            metrics = metrics,
            accesses = accesses.filter { it.targetUserId == student.userId },
        )
    }
}
```

### 4.3 El botón "Borrar mis datos"

Tres niveles, con tres textos distintos. El diálogo **no** es un "sí/no": es una
decisión con consecuencias declaradas.

```
Borrar mis datos
──────────────────────────────────────

Vas a borrar de forma definitiva:

  · 18 conversaciones (142 mensajes tuyos y 130 del modelo)
  · Tus métricas de las 4 cátedras
  · Tu perfil local de Kitoru en este dispositivo

Esto no se puede deshacer.

Lo que Kitoru NO puede borrar:
  · Lo que tu docente ya leyó o copió. No hay forma de des-leer.
    Le vamos a avisar que pediste el borrado y le vamos a pedir que elimine
    lo que tenga de tu parte.
  · Las notas que tu docente haya guardado en un papel o en un sistema
    externo a Kitoru.

                                        [Cancelar]  [Borrar mis datos]
```

Después de borrar, la pantalla queda así:

```
Tus datos fueron borrados el 12/03/2026.
Pedimos a 3 docentes de 2 cátedras que eliminen lo que tenían de tu parte.
Te avisamos cuando cada uno confirme.
```

### 4.4 "Descargar mis datos" (portabilidad, GDPR art. 20)

Botón **"Descargar mis datos"** → genera un `.zip` (o un `.json` único, para el
milestone 1) con:

```
kitoru-datos-juan-perez-2026-03-12/
├── perfil.json              # UserProfile (incluye el email)
├── inscripciones.json       # Enrollment + consent + eventos
├── conversaciones/
│   ├── IA-2026-1/
│   │   ├── hilo-1.json      # mensajes con texto, fuentes, citas, herramientas
│   │   └── hilo-2.json
│   └── Redes-2026-1/
├── metricas.json            # lo que el docente puede ver de vos
└── accesos-del-docente.json # el registro de accesos que Kitoru tiene
```

Formato: **el mismo formato `.kitoru`** que ya usa la importación de paquetes
(`teacher/07-chats.md` §8), con una cabecera distinta:

```json
{
  "format": "kitoru.student-export",
  "version": 1,
  "exportedAt": 1773000000000,
  "subject": { "userId": "u-8f3c", "displayName": "Juan Pérez", "email": "..." },
  "courses": ["IA-2026-1", "Redes-2026-1"],
  "threads": [ ... ],
  "metrics": [ ... ],
  "accessLog": [ ... ]
}
```

> La diferencia con el paquete que el alumno comparte con el docente (§2.1) es
> **el `subject`**: el paquete de compartir va seudonizado, el export personal
> lleva los datos reales. El botón "Exportar mis chats" que el docente puede
> pedir **nunca** incluye email, `subject` institucional ni `userId` real.

---

## 5. La granularidad: por cátedra, con override por hilo

### 5.1 Por qué no por mensaje

| Granularidad | Qué resuelve | Por qué no |
|---|---|---|
| Por mensaje | "Este mensaje no lo leas" | Ruidoso (36 decisiones), incoherente (un mensaje sin contexto no significa nada) y **engañoso**: el docente ve el hilo partido y no sabe qué le está mostrando el alumno |
| **Por cátedra** | "En esta materia no me leas" | Es la unidad natural de la relación docente-alumno y la que ya existe (`Enrollment`) |
| **Por hilo** | "De esta conversación, ni hablar" | Es la unidad natural del contenido: un hilo es un tema |

**Decisión: por cátedra, con override por hilo.** El override por hilo sólo
**baja** respecto del nivel de la cátedra (es el alumno el que baja su propio
techo), y **nunca** sube.

### 5.2 El modelo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt

@Serializable
data class ChatThread(
    val id: String,
    val courseId: String,
    val authorId: String,
    val authorName: String = "",
    val title: String = "Conversación",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modelLabel: String = "",
    val effectiveModel: ModelPolicy = ModelPolicy(),
    val tags: List<String> = emptyList(),

    // --- privacidad ---
    /**
     * Override del alumno sobre este hilo.
     *
     * `null` = sin override (rige el nivel de la cátedra).
     * Sólo puede **bajar** el nivel del alumno: el servidor y la app lo
     * validan (`ChatPolicy.validateOverride`).
     */
    val visibilityOverride: ChatVisibility? = null,

    /** Epoch ms del cambio de override (para la pantalla "Tus datos"). */
    val visibilityOverrideAt: Long = 0L,
)
```

Validación (al guardar, nunca al leer):

```kotlin
object ChatPolicy {
    /**
     * Un override sólo puede bajar el nivel del alumno.
     * @return el override válido (o null si no corresponde).
     */
    fun validateOverride(
        enrollment: Enrollment,
        requested: ChatVisibility?,
    ): ChatVisibility? {
        if (requested == null) return null
        val ceiling = AccessControl.studentCeiling(enrollment)
        require(requested.atMost(ceiling) == requested) {
            "No podés subir la visibilidad de una conversación: tu docente ya ve menos que eso."
        }
        return requested
    }
}
```

El nivel efectivo de un hilo:

```
nivel_de_hilo = min( nivel_de_cátedra_del_alumno , override_del_hilo ?? FULL )
```

### 5.3 UI del alumno

En la cabeza de cada conversación:

```
┌──────────────────────────────────────────────────────────────┐
│ Duda sobre backprop                    ⋮                    │
│ [ 👁 Solo métricas ]   ← chip de estado, siempre visible     │
└──────────────────────────────────────────────────────────────┘
```

Al tocar el chip:

```
Compartir esta conversación
──────────────────────────────────

Ahora mismo tu docente ve: Todo
Si lo cambiás a Solo métricas, en esta conversación no va a poder
leer ni tus preguntas ni las respuestas del modelo. En las demás
conversaciones de esta cátedra sigue viendo todo.

( ) Todo          ← lo que ve ahora
( ) Solo métricas
( ) Nada

Esta conversación fue creada el 12/03 y tiene 8 mensajes.
                                    [Cancelar]  [Guardar]
```

En el `⋮` de la conversación, la misma acción aparece como
**"Compartir esta conversación"**, y el estado se ve en la lista de
conversaciones con un candado:

```
Duda sobre backprop          12/03 · 8 mensajes · 🔒
```

### 5.4 UI del docente

En el explorador de chats (`teacher/07-chats.md`), el hilo aparece con el chip
del nivel efectivo **de ese hilo**, que puede diferir del de la inscripción:

```
┌──────────────────────────────────────────────────────────────────┐
│ Ana Pérez · 7 conversaciones                    [Solo métricas] │
├──────────────────────────────────────────────────────────────────┤
│ ▸ Duda sobre backprop              12/03 · llama3.1 · 8 mensajes │
│    🔒 El alumno limitó esta conversación a "Solo métricas".     │
│ ▾ Parcial 2 - normal               12/03 · llama3.1 · 14 mensajes│
│    👤: ¿Cómo se define ...?                                     │
│    🤖: (texto de la respuesta)                1.2 s · 342 tok   │
└──────────────────────────────────────────────────────────────────┘
```

Reglas:

- El texto de un hilo con override `METRICS_ONLY` **no se carga** (misma regla
  que la inscripción: se aplica antes de leer).
- El docente ve el motivo del override ("el alumno limitó esta conversación"),
  pero **no** puede pedir que se suba: el override es del alumno y sube por el
  mismo flujo de solicitud (§3.5).
- En la exportación, un hilo con override `METRICS_ONLY` se exporta **sólo con
  sus métricas**; el texto no sale del disco del docente.

### 5.5 Cómo llega el override al servidor

`POST /v1/usage/report` (o el header `X-Kitoru-Visibility` que ya usa el
diseño del servidor, §8.4) viaja **por hilo**:

```http
POST /v1/usage/report
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…
Content-Type: application/json

{
  "apiVersion": 1,
  "courseId": "IA-2026-1",
  "visibility": "METRICS_ONLY",
  "threads": [
    { "threadId": "thr_7a2", "visibility": "NONE" },
    { "threadId": "thr_7b1", "visibility": "METRICS_ONLY" }
  ],
  "evidence": "SELF_REPORTED"
}
```

El servidor **recorta antes de serializar**: un evento de `thr_7a2` con
visibilidad `NONE` se devuelve con `messages: []` y `textAvailable: false`
(`platform/03-kitoru-server-api.md` §8.4). El cliente del docente, además, pide
`includeText=false` y aplica `ChatPolicy.canReadText` al leer: **dos capas**.

---

## 6. Agregado vs individual: k-anonimidad

### 6.1 La regla, en una frase

> Un agregado de la cátedra es **dato no personal** si **5 o más alumnos** lo
> sostienen. Con menos de 5, el agregado es re-identificable y **no se muestra**.

### 6.2 Qué es dato personal y qué no

| Dato | ¿Personal? | Por qué | Qué lo habilita |
|---|---|---|---|
| Fila por alumno: "Ana Pérez: 142 mensajes, 88 % de citas" | **Sí** | Se refiere a una persona identificable | Consentimiento `METRICS_ONLY` del alumno (y nada más) |
| Texto de un mensaje | **Sí** | Es el dato más sensible | Consentimiento `FULL` |
| "La cátedra hizo 500 consultas en 6 semanas" | **No** (si ≥ 5 alumnos) | No se refiere a nadie en particular | k-anonimidad |
| "El 30 % de los alumnos usó el RAG la semana pasada" | **No** (si ≥ 5 alumnos) | Un porcentaje de una grupo grande no identifica | k-anonimidad |
| "El único alumno que no usó el RAG esta semana" | **Sí**, y además es un dato *negativo* | Identifica por exclusión | Se suprime por k |
| "Promedio de latencia: 2,1 s" (30 alumnos) | No | con 30 alumnos no re-identifica | k-anonimidad |
| "Promedio de latencia: 4,7 s" (3 alumnos) | **Sí**, en la práctica | Con 3 alumnos, el promedio es un dato de un grupo identificable | Se suprime por k |

El último caso es el que casi siempre se olvida: **el promedio de un grupo chico
es un dato individual**.

### 6.3 El criterio exacto

| Contexto | K | Regla |
|---|---|---|
| Celda agregada en pantalla (tarjetas, gráficos, series) | **5** | Si el número de alumnos que contribuyen a la celda es < 5, la celda se **suprime** |
| Exportación / informe para compartir fuera de la cátedra | **10** | Igual, con K = 10 (un documento sale de la cátedra y se comparte: sube el listón) |
| Fila por alumno | — | No aplica k: es dato personal y requiere consentimiento individual (`METRICS_ONLY`) |
| Roster (nombres, roles, fechas de inscripción) | — | No es analítica: es padrón. Lo ve el docente por su rol |

**K configurable por cátedra**: `Course.analyticsMinCohort: Int = 5`
(`Course.analyticsMinCohortExport: Int = 10`), para que una facultad con
requisitos más estrictos los suba. El mínimo del sistema es 5: **no se puede
bajar de 5**.

### 6.4 La supresión secundaria (el ataque por diferencia)

Suprimir sólo las celdas chicas no alcanza: si el total es visible y sólo falta
una celda, la celda suprimida se deduce restando.

**Regla**: en cada tabla o serie donde se suprime alguna celda, si queda
**exactamente una** suprimida, se suprime **también** la celda más próxima
(la de menor valor o la siguiente en la serie). Si la supresión pasa a ser
mayoritaria, se suprime la fila o la serie entera y se muestra:

```
— dato suprimido para proteger a menos de 5 alumnos
```

Ejemplo concreto, 30 alumnos, 4 secciones:

```
Uso por sección
──────────────────────────────────────────────
Sección A   12 alumnos   312 consultas   (visible)
Sección B    9 alumnos   140 consultas   (visible)
Sección C    8 alumnos    95 consultas   (visible)
Sección D    1 alumno      3 consultas   ← suprimido (1 < 5)
──────────────────────────────────────────────
Total        30 alumnos   550 consultas
```

El total **no** es 550: los 3 de la sección D están dentro. Si el docente ve
"550 consultas" y las secciones visibles suman 547, deduce que hay 1 alumno con
3 consultas en la sección D. Por eso:

```
Uso por sección
──────────────────────────────────────────────
Sección A   12 alumnos   312 consultas   (visible)
Sección B    9 alumnos   140 consultas   (visible)
Sección C    8 alumnos    95 consultas   (visible)
Sección D    —            —             ← suprimido
──────────────────────────────────────────────
Total        30 alumnos   550 consultas
```

Y, además, **el total se recalcula sin las celdas suprimidas** (550 − 3 = 547),
de modo que la resta no cierra. Si aun así la diferencia es deducible (porque
el total global del período está en otro lado), se suprime también el total de
esa fila.

### 6.5 El modelo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CohortPrivacy.kt

/**
 * k-anonimidad para los agregados de la cátedra.
 *
 * Un agregado es publicable si **al menos K alumnos** lo sostienen.
 * Con menos, es re-identificable y se suprime.
 */
object CohortPrivacy {

    /** K por defecto para mostrar en pantalla. */
    const val K_DISPLAY = 5

    /** K por defecto para exportar. */
    const val K_EXPORT = 10

    /** K mínimo absoluto: la cátedra puede subirlo, nunca bajarlo. */
    const val K_FLOOR = 5

    /** ¿Se puede publicar esta celda? */
    fun publishable(cohortSize: Int, threshold: Int = K_DISPLAY): Boolean =
        cohortSize >= maxOf(threshold, K_FLOOR)

    /** Umbral efectivo de la cátedra (nunca por debajo del piso). */
    fun threshold(course: Course, exporting: Boolean = false): Int =
        maxOf(
            if (exporting) course.analyticsMinCohortExport else course.analyticsMinCohort,
            K_FLOOR,
        )

    /**
     * Aplica supresión primaria y secundaria a una serie.
     *
     * @param sizes número de alumnos que contribuyen a cada celda.
     * @return los índices de las celdas a suprimir.
     */
    fun suppressedIndexes(sizes: List<Int>, threshold: Int): Set<Int> {
        var hidden = sizes.indices.filter { sizes[it] < threshold }.toMutableSet()
        if (hidden.size == 1) {
            // Supresión secundaria: sin ella, la celda se deduce por diferencia.
            val remaining = sizes.indices.filter { it !in hidden }
            val next = remaining.minByOrNull { sizes[it] }
            if (next != null) hidden += next
        }
        return hidden
    }
}
```

Y `DayActivity` gana el dato que hace falta para aplicar la regla:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Analytics.kt

@Serializable
data class DayActivity(
    val day: Long,
    val messages: Int,
    val replies: Int,
    /** Alumnos distintos que escribieron ese día. Es el "n" de la k-anonimidad. */
    val students: Int = 0,
)
```

> Sin `students`, una barra de un día con 40 mensajes de **una sola** persona es
> indistinguible de una barra de 40 mensajes de 40 personas: la primera identifica,
> la segunda no. Con `students` la regla se aplica.

### 6.6 Hallazgo sobre el código actual (a corregir)

`AnalyticsAnalyzer.analyze` **calcula `topSources` sin filtrar por visibilidad**
(`core/.../AnalyticsAnalyzer.kt`, líneas 69–80): itera todos los hilos y todos
los mensajes, incluidos los de alumnos con `visibility = NONE` o
`METRICS_ONLY`. El resultado es que **el uso de fuentes de un alumno oculto
queda filtrado al docente** como "topSources" de la cátedra.

Corrección propuesta (en el mismo estilo que el resto del analizador):

```kotlin
// antes: for (thread in threads) { for (message in messagesByThread[thread.id].orEmpty()) ... }
// después:
val visibleThreadIds = threads
    .filter { byUser[it.authorId]?.visibility?.showsMetrics == true }
    .map { it.id }
    .toSet()

for (thread in threads) {
    if (thread.id !in visibleThreadIds) continue
    for (message in messagesByThread[thread.id].orEmpty()) {
        if (!message.isAssistant) continue
        for (source in message.sources) sourceCounts[source] = (sourceCounts[source] ?: 0) + 1
    }
}
```

Y, con el fail-closed (D2), el filtro debe usar `AccessControl.studentCeiling(...)`
en lugar de `visibility` crudo.

### 6.7 Qué pasa con los agregados ya publicados

Regla: **el agregado se recalcula, no se retira.** Cuando un alumno baja su
visibilidad o se da de baja:

1. La analítica se recalcula desde los datos que quedan.
2. Los números que el docente ya vio **no se pueden borrar**: están en su
   cabeza, en su PDF y en su correo. La app no lo promete lo contrario; lo que hace
   es dejar constancia: *"Ana Pérez cambió su visibilidad el 12/03; sus datos
   se recalcularon"*.
3. En el informe exportado (CSV/PDF) el docente **no** puede incluir filas de
   alumnos por debajo de K en modo agregado: el exportador las omite y anota
   `"3 filas omitidas por cohortes menores a 10 alumnos"`.

---

## 7. La pseudonimización

### 7.1 Las dos preguntas (que no son la misma)

1. **¿Qué etiqueta ve el docente?** → `Enrollment.displayName` (materializada).
2. **¿A qué persona real corresponde?** → la correspondencia vive en el
   **servidor institucional** (o en el dispositivo del alumno, si no hay
   servidor). No en el roster del docente, salvo que el propio docente la haya
   cargado.

### 7.2 La decisión (D8)

> **Por defecto, el docente ve un seudónimo estable de cátedra ("Alumno 037").
> El nombre real aparece únicamente si el alumno lo autoriza explícitamente.**

Consecuencias:

| Aspecto | Decisión |
|---|---|
| Formato del seudónimo | `Alumno 037`: número correlativo **estable** según el orden de inscripción en la cátedra |
| Fuente del número | El servidor institucional (número de padrón) o, sin servidor, un contador local por cátedra |
| Etiqueta por defecto del alumno | `AliasKind.ANONYMOUS` (no `REAL`) — **cambio** respecto del default actual de `domain/09-identidad.md` §3.1 |
| Etiqueta por defecto del docente | `AliasKind.REAL` (el docente tiene que ver su nombre en el padrón) |
| Nombre real | Sólo con `AliasKind.REAL` elegido por el alumno, o con una **solicitud de identidad** aprobada (§7.4) |
| Estabilidad | El número **no cambia** durante la cursada, aunque el alumno cambie de alias o se reinscriba |
| El correo | **Nunca** en el roster exportado (ya está decidido en `domain/09-identidad.md` §3.2) |

> **Cambio propuesto en `domain/09-identidad.md`**: `CourseAlias.kind` pasa a
> tener default `ANONYMOUS` **para los alumnos**. Hoy el default es `REAL`, lo
> que contradice D8. Es un cambio de una línea con default, pero **cambia el
> comportamiento de los alumnos nuevos**: hay que hacerlo antes de que la app
> se use de verdad.

### 7.3 El modelo

`AliasKind` (`domain/09-identidad.md` §3.1) ya tiene los tres casos
(`REAL`, `PSEUDONYM`, `ANONYMOUS`). Lo que agregamos es el **número de padrón**
como fuente estable del seudónimo y una **etiqueta materializada** que sabe de
dónde salió:

```kotlin
@Serializable
enum class AliasKind(val label: String) {
    /** El nombre real del perfil ("Juan Pérez"). */
    REAL("Nombre real"),

    /** Un apodo elegido por el propio alumno ("Fantasma"). */
    PSEUDONYM("Seudónimo del alumno"),

    /** Una etiqueta genérica asignada por el sistema ("Alumno 037"). */
    ANONYMOUS("Anónimo"),
}

/**
 * Identificador opaco y estable de un alumno dentro de una cátedra.
 *
 * Es el "n" de la k-anonimidad y la etiqueta por defecto del roster.
 * No se puede adivinar desde fuera: el servidor lo asigna y lo entrega junto
 * con la inscripción; el docente nunca ve la correspondencia con el nombre
 * real salvo autorización expresa (§7.4).
 */
@Serializable
data class StudentCode(
    val courseId: String,
    /** Número correlativo según el orden de inscripción. */
    val number: Int,
    /** Etiqueta lista para mostrar: "Alumno 037". */
    val label: String = "Alumno ${number.toString().padStart(3, '0')}",
)
```

Y en `Enrollment`:

```kotlin
@Serializable
data class Enrollment(
    // ...
    /** Número de padrón en la cátedra (0 = todavía no asignado). */
    val seatNumber: Int = 0,

    /** Preferencia del alumno sobre su etiqueta. */
    val aliasKind: AliasKind = AliasKind.ANONYMOUS,

    /** Quién puso la etiqueta actual: define quién gana en un conflicto. */
    val labelSource: LabelSource = LabelSource.STUDENT,

    /** Identificador institucional (nunca visible para el docente sin autorización). */
    val institutionSubject: String = "",
)
```

`ChatThread.authorName` **debe ser la etiqueta** (`domain/09-identidad.md` §4.3):
si fuera el nombre real, el log del servidor filtraría la identidad real al
docente aunque el alias fuese anónimo.

### 7.4 La solicitud de identidad real

El caso real: el titular tiene que calificar un parcial y necesita saber quién es
"Alumno 037". El flujo:

```
Docente                                Alumno
───────                                ──────
"Ver ficha de Alumno 037"
  → "Necesito saber quién es para
     evaluar el parcial"
  → [ Solicitar identidad ]
                                        ┌────────────────────────────────┐
                                        │ Tu docente pide saber tu     │
                                        │ nombre                         │
                                        │                                │
                                        │ Va a ver: Juan Pérez          │
                                        │                                │
                                        │ Motivo: "Evaluar el parcial   │
                                        │ 2, para atribuir la nota"     │
                                        │                                │
                                        │ [ Aceptar hasta el 30/04 ]    │
                                        │ [ Aceptar sólo hoy ]           │
                                        │ [ Rechazar ]                   │
                                        └────────────────────────────────┘
```

La autorización tiene **plazo** (`IdentityGrant.until`). Vencido, el nombre
desaparece de la pantalla del docente y el evento queda auditado:

```
Alumno 037 · Juan Pérez
Identidad autorizada por el alumno hasta el 30/04/2026.
```

Modelo:

```kotlin
/** Autorización del alumno para que el docente vea su nombre real. */
@Serializable
data class IdentityGrant(
    val id: String,
    val courseId: String,
    val studentUserId: String,
    val teacherUserId: String,
    /** Epoch ms hasta el que vale. 0 = hasta el final de la cursada. */
    val until: Long,
    val grantedAt: Long = 0L,
    val reason: String = "",
    val revokedAt: Long = 0L,
)
```

> **El docente no ve la identidad institucional.** El `subject` (el mail
> `@universidad.edu.ar`) es del servidor, no del docente. Ver
> `domain/09-identidad.md` §8.4: *"vincular no le da más poder al docente sobre
> el alumno"*.

### 7.5 La verdad incómoda (y por qué el seudónimo sigue valiendo la pena)

En un aula real de 30 personas, el docente **sabe** que "Alumno 037" es Bruno.
La seudonimización **no lo protege frente al docente de su propia clase**: no
puede, porque el docente tiene el padrón, las listas, las caras y la lista de
notas.

Lo que sí protege:

| Escenario | La pseudonimización sirve |
|---|---|
| Un informe de analítica que se comparte con otra cátedra, con la dirección de la facultad o en un paper | **Sí**: el número 037 no identifica a nadie fuera del padrón |
| Una captura de pantalla que circula por WhatsApp | **Sí** |
| Un ayudante que no conoce a todos los alumnos | **Sí**, y mucho |
| La exportación que el docente deja en un Drive compartido | **Sí** |
| El titular de la cátedra mirando a sus propios alumnos | **No**, y está bien: él tiene que saber quién es quién para dar notas |

Por eso el seudónimo es el default y no un adorno: **el dato que sale de la
cátedra no lleva nombres**. Dentro de la cátedra, el docente que necesita
identificar pide la autorización (§7.4) y queda registrado.

### 7.6 Qué protege de verdad el seudónimo: que "Alumno 037" no se re-identifique por el título

Un detalle fino pero real: aunque el nombre sea "Alumno 037", **el título de una
conversación** ("Duda con el parcial de Ana") re-identifica al instante. Por eso:

- En `METRICS_ONLY` y en `NONE`, los títulos de los hilos **no se muestran** y
  se reemplazan por `"Conversación N"` (`teacher/07-chats.md` §3.3). Es la regla
  que ya está diseñada; queda ratificada acá.
- El **nombre del material** tampoco se filtra: "Parcial-2-2026-Soledad-Núñez.pdf"
  identifica a una persona. Los nombres de archivo se **normalizan** en la
  analítica: se muestran sólo los títulos lógicos ("Apuntes-01.pdf"), que es lo
  que el `CourseMaterialSource` ya indexa.

---

## 8. Los datos del modelo

### 8.1 Los tres destinos posibles del prompt

| Destino | ¿Qué sale del dispositivo? | ¿Quién lo ve? | Declaración |
|---|---|---|---|
| **Local** (llama.cpp en el dispositivo) | Nada del prompt. El **material de la cátedra** ya fue descargado (es de la cátedra, no del alumno) | Nadie fuera del dispositivo | "Lo que escribís no sale de tu computadora" |
| **Servidor institucional** (Kitoru-Server + llama-server dentro de la facultad) | El prompt + los fragmentos recuperados del corpus | El servidor (por necesidad técnica) y, si el alumno lo autorizó, el docente | "Se procesa en el servidor de la facultad y no se usan para entrenar modelos" |
| **Proveedor externo** (API compatible con OpenAI, fuera de la institución) | El prompt + los fragmentos | El proveedor y sus|subencargados | "Sale de la red de la universidad; Kitoru no puede garantizar qué hace el proveedor con eso" |

El tercero es el que casi nadie dice en voz alta y es el que **debe** estar
visible, porque el `ModelPolicy.remoteBaseUrl` lo permite sin avisar.

### 8.2 Qué declara la app (textos literales)

#### Servidor institucional

```
Las conversaciones se procesan en el servidor de la facultad, en un modelo que
corre dentro de la institución. No salen de la red de la universidad y no se
usan para entrenar modelos.
```

#### Proveedor externo

```
Esta cátedra usa un servicio de IA de terceros (OpenAI), fuera de la red de la
universidad. Tus conversaciones se envían a ese servicio para generar las
respuestas. Kitoru no puede garantizar que ese servicio no las use para
entrenar sus modelos: la cátedra declara que no lo hace.
```

#### IA local

```
Estás usando un modelo en tu propio dispositivo. Lo que escribís no sale de tu
computadora y tu docente no ve nada.
```

#### Cuando no se puede verificar

```
No pudimos verificar las garantías de privacidad del servidor (no respondió o no
declaró su política). Asumimos la opción más restrictiva: tu docente no va a
ver el texto de tus conversaciones.
```

### 8.3 Qué garantiza el Kitoru-Server (contrato "Privacy Contract v1")

El servidor **declara** sus garantías; la app **muestra lo que declara**, no lo
que la app quiere que sea cierto. El mecanismo:

```http
GET /v1/privacy
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…

200
{
  "apiVersion": 1,
  "contract": "kitoru.privacy.v1",
  "processor": "INSTITUTION",              // INSTITUTION | EXTERNAL
  "processorName": "Facultad de Ingeniería",
  "training": false,                        // el servidor no entrena con las conversaciones
  "retentionDays": 30,                      // 0 = sólo en memoria
  "region": "ar",                           // país del procesamiento
  "auditEnabled": true,
  "erasureSlaHours": 72,
  "model": { "type": "local", "name": "Qwen3-8B-Instruct" }
}
```

El cliente lo pide **antes de la primera inferencia** y lo cachea con su
timestamp. Si la respuesta falta o es inválida, aplica la declaración
conservadora (§8.2, último caso).

**Compromisos del contrato** (lo que el servidor garantiza y por tanto lo que la
app puede afirmar):

| # | Compromiso | Cómo se implementa |
|---|---|---|
| G1 | **No hay logs de contenido.** Los logs llevan user id opaco, timestamp, modelo, tokens, latencia y estado. Nunca el prompt ni la respuesta. | El `outbox` (§8.2 del doc del servidor) guarda los eventos para el docente; el log de operación es otro sink, con campos cerrados |
| G2 | **No training.** El modelo se sirve con llama.cpp dentro de la institución; ningún prompt se usa para entrenar ni para afinar nada. | G2 es estructural: no hay una tubería de entrenamiento |
| G3 | **Retención acotada.** Los eventos del outbox se purgan a los `--chat-retention-days` (por defecto 90 en el servidor actual; **la app recomienda 30** y la facultad puede bajarlo a 0). | `retentionDays` en `GET /v1/privacy` |
| G4 | **Sin consola de lectura.** No hay un panel de administración que muestre conversaciones: el docente accede por la API, y **cada lectura queda auditada** (`audit:write`). | Los scopes del token de servicio (`chats:read`, `audit:read`) |
| G5 | **Borrado a pedido.** `DELETE /v1/students/{subject}/data` purga contenido y responde con `purgedAt` en ≤ 72 h (`erasureSlaHours`). | Endpoint nuevo (ver §9) |
| G6 | **Claves revocables y con vencimiento.** La key del alumno se revoca con `DELETE /v1/keys/{id}` y expira al terminar la cursada. | Ya está en el diseño del servidor |

**Lo que el contrato NO puede garantizar** (y la app no debe prometer):

1. Quien controla la máquina del servidor puede leer el disco. La mitigación es
   cifrado de disco + auditoría de la capa de API, no una promesa.
2. Si la cátedra apunta a un proveedor externo (`processor: EXTERNAL`), G1, G2 y
   G3 **no aplican**: lo declara el propio `GET /v1/privacy`.
3. El docente puede **copiar** lo que lee (capturas, PDF, notas). Kitoru lo
   audita mientras está en la app; fuera de la app, no.

### 8.4 La firma en la API (el endpoint `DELETE`)

El diseño del servidor tiene `POST /v1/usage/report` y el outbox. Faltan dos
endpoints para que el derecho de supresión sea real:

```http
DELETE /v1/students/{subject}/data
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…
Idempotency-Key: er_01J9Z8Y2QK4M8fS

204 No Content
```

```http
GET /v1/students/{subject}/erasure
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…

200
{
  "apiVersion": 1,
  "subject": "enroll_9f3",
  "requestedAt": 1773000000000,
  "purgedAt": 1773003600000,
  "scope": "CONTENT_ONLY",
  "contentErased": true,
  "auditRetained": true
}
```

`scope` distingue dos cosas que la app debe explicar bien (§9.4):

- `CONTENT_ONLY`: se borró el contenido (mensajes, hilos, prompts).
- `FULL`: además se borraron las métricas y el historial de auditoría (sólo si
  el alumno lo pide **explícitamente**, con confirmación doble, porque el
  historial de auditoría es evidencia de que el docente **no** hizo nada malo).

---

## 9. El borrado

### 9.1 Borrado lógico vs físico

| Tipo | Qué borra | Cuándo | Reversible |
|---|---|---|---|
| **Lógico** (`ChatThread.deletedAt`) | El hilo desaparece de la UI del alumno; los datos siguen en el disco | "Borrar conversación" | Sí, durante 30 días ("Elementos eliminados") |
| **Físico local** | Se borran los mensajes del almacén del alumno y el hilo | "Borrar mis datos" (D10) | No |
| **Físico en el servidor** | El servidor purga el contenido (G5) | "Borrar mis datos" | No |
| **Supresión en el docente** | El docente elimina de su dispositivo lo que tenga (notificado, §9.3) | Notificación de supresión | No |

El borrado lógico existe por un motivo funcional, no legal: el alumno a veces
borra una conversación por error y la quiere recuperar. Los 30 días son el
período de gracia; después, el recolector de papelera la borra.

### 9.2 El flujo de supresión (derecho de supresión)

```
1. El alumno pulsa "Borrar mis datos" en «Tus datos».
2. Aparece el diálogo con las tres consecuencias (texto de §4.3), que nombra
   explícitamente lo que NO se puede borrar.
3. Confirmación. El cliente:
     a. borra localmente hilos, mensajes y métricas del perfil activo;
     b. envía DELETE /v1/students/{subject}/data al servidor (si hay servidor);
     c. escribe un ErasureRecord local (conteos, sin contenido);
     d. envía un aviso de supresión a las instalaciones de los docentes
        (a través del servidor: POST /v1/erasure-notices, §9.3).
4. La app del alumno muestra el estado:
     "Tus datos fueron borrados el 12/03/2026.
      Pedimos a 3 docentes de 2 cátedras que eliminen lo que tenían de tu
      parte. Te avisamos cuando cada uno confirme."
5. Cada docente recibe el aviso y decide (§9.3).
6. El servidor borra el outbox del alumno y responde el estado de borrado.
7. La app del alumno, al día siguiente, vuelve a preguntar al servidor:
     GET /v1/students/{subject}/erasure → "purgedAt".
```

### 9.3 La respuesta honesta: si el docente ya lo vio

**No se puede des-leer.** Cualquier diseño que prometa lo contrario es falso y
además es peligroso (el alumno confiaría en que su chat desapareció y escribiría
cosas que ya no volvería a estar privadas). Por eso la app lo dice **antes** de
borrar, con estas palabras (ya incluidas en §4.3):

> "Lo que tu docente ya leyó o copió no se puede deshacer."

Lo que Kitoru **sí** hace: notificar (§9.4), auditar la respuesta (§9.5) y
dejar constancia (§9.5).

### 9.4 El aviso al docente

La app del docente recibe el `erasure_notice` y muestra una banda **no
descartable**:

```
┌───────────────────────────────────────────────────────────────────────┐
│ 🗑 Ana Pérez pidió la supresión de sus datos                          │
│    12/03/2026 · 3 conversaciones · 42 mensajes                         │
│                                                                       │
│    Lo que tengas en tu dispositivo de esas conversaciones se puede     │
│    eliminar con un clic. Si lo conservás, queda registrado con tu      │
│    motivo y el titular de la cátedra lo ve.                            │
│                                                                       │
│    [ Eliminar de mi dispositivo ]   [ Conservar ]                     │
└───────────────────────────────────────────────────────────────────────┘
```

Al **conservar** (lo que un docente puede necesitar hacer si tiene una
obligación de conservación, por ejemplo una instancia de examen):

```
Conservar los datos de Ana Pérez
────────────────────────────────────

Escribí por qué conservás estos datos (lo va a ver el titular de la cátedra y
el alumno):

[____________________________________________]   (mínimo 30 caracteres)

Se va a registrar: qué conservaste, cuánto y por qué.

                            [Cancelar]  [Conservar y registrar]
```

El registro de esa decisión:

```kotlin
/** Decisión del docente frente a un aviso de supresión. */
@Serializable
enum class ErasureDecision(val label: String) {
    /** Eliminó la copia local. */
    PURGED("Eliminados"),

    /** Conservó la copia, con motivo escrito. */
    RETAINED("Conservados con motivo"),

    /** Todavía no respondió. */
    PENDING("Sin responder"),
}

@Serializable
data class ErasureNotice(
    val id: String,
    val courseId: String,
    /** Id del alumno (en el servidor; en el cliente del docente, el pseudónimo). */
    val studentUserId: String,
    val studentLabel: String,
    val requestedAt: Long,
    /** Hilos alcanzados por la supresión (ids, nunca contenido). */
    val threadIds: List<String> = emptyList(),
    val threadsErased: Int = 0,
    val messagesErased: Int = 0,
    /** Decisión del docente (la toma cada uno, en su dispositivo). */
    val decision: ErasureDecision = ErasureDecision.PENDING,
    val decidedAt: Long = 0L,
    val reason: String = "",
)
```

### 9.5 Qué sobrevive (evidencia sin contenido)

| Dato | ¿Sobrevive a la supresión? | Por qué |
|---|---|---|
| El texto de los mensajes | **No** | Es el dato suprimido |
| Los hilos y sus metadatos | **No** | Se borran |
| Las métricas derivadas | **No** (se recalculan) | Se derivan del contenido |
| Las entradas de auditoría del acceso del docente | **Sí**, pero **anonimizadas**: `targetUserId` pasa a un id tombstone (`erased:9f3c`) y el `detail` se vacía | La auditoría prueba que el docente leyó; no puede contener el contenido |
| El `ErasureRecord` (conteos, fechas, quién pidió) | **Sí** | Es el acta de la supresión |
| La decisión del docente ("conservado con motivo") | **Sí** | Es la rendición de cuentas |
| El roster (nombre o seudónimo, rol, fecha de inscripción) | Depende del alcance: `CONTENT_ONLY` lo deja; `FULL` lo anonimiza | El padrón es un dato de la institución, no del contenido |

### 9.6 Los plazos

| Acción | Plazo |
|---|---|
| Borrado físico en el servidor | ≤ 72 h desde la solicitud (`erasureSlaHours`) |
| Purga de las copias de los docentes | El aviso se manda **inmediatamente**; el docente tiene 7 días para decidir; después, el servidor considera la solicitud no atendida y lo escala al titular |
| Purga de las copias en los backups del servidor | 30 días (los backups son rotativos; no se puede hacer mejor) — se declara en la política |
| Conservación del registro de auditoría | 12 meses desde el fin de la cursada; después se borra |
| Conservación del acta de supresión (`ErasureRecord`) | 5 años (debe demostrar el cumplimiento de la obligation) |

### 9.7 Mapa de derechos (GDPR / LOPD)

| Derecho | Artículo | Cómo se implementa en Kitoru |
|---|---|---|
| Acceso (derecho de saber) | GDPR art. 15 / LOPD art. 14 | Pantalla "Tus datos" (§4) + "Descargar mis datos" (§4.4) |
| Rectificación | GDPR art. 16 | El alias y el nombre los edita el alumno; el `Enrollment.displayName` es suyo mientras `labelSource == STUDENT` |
| Supresión | GDPR art. 17 / LOPD art. 14 bis | Flujo de §9 |
| **Notificación a los destinatarios** | **GDPR art. 19** | El `erasure_notice` a los docentes (§9.4): el docente es "destinatario" de los datos |
| Limitación | GDPR art. 18 | Equivale a bajar la visibilidad a `METRICS_ONLY`/`NONE` |
| Oposición | GDPR art. 21 | Bajar la visibilidad es la forma más simple de oponerse |
| Portabilidad | GDPR art. 20 | "Descargar mis datos" (§4.4) |
| Minimización | GDPR art. 5.1.c | Sólo se guardan los metadatos que hacen falta para la analítica; el texto no se guarda si no hace falta |
| Consentimiento | GDPR art. 7 | `VisibilityConsent` versionado y hasheado (§3.7); revocable en un clic |
| Registro de actividades | GDPR art. 30 | Fuera de la app: es del responsable (la institución) |

**Roles (LOPD art. 5 / GDPR art. 4):**

- **Responsable del tratamiento**: la facultad (la institución), no Kitoru.
- **Encargado del tratamiento**: el operador del Kitoru-Server (si es un tercero).
- **Personal autorizado**: los docentes, con la instrucción de que sólo acceden a
  lo que el alumno autorizó.
- **Kitoru (app)**: herramienta; no es responsable ni encargado por sí misma.
  Esta frase tiene que estar en el texto legal (§11).

---

## 10. Auditoría del acceso del docente

### 10.1 Qué hay ya

- `docs/design/domain/08-permisos.md` §8: `AccessLogEntry` (append-only, con
  `outcome`), `AccessLogRepository`, `PermissionAudit`, y la lista de
  capacidades con `audited = true`.
- `docs/design/teacher/07-chats.md` §6: `AccessLog` con `AccessKind`
  (`VIEW_TEXT`, `VIEW_METRICS`, `SEARCH`, `EXPORT`, `IMPORT`), coalescencia a 5
  minutos y rotación.

Los dos modelos son **compatibles pero redundantes**. Este documento fija el
canónico: **`AccessLogEntry`** (el de `domain/08-permisos.md`), porque está
anclado a las capacidades y por lo tanto no puede quedar desactualizado respecto
de la matriz de permisos. `AccessKind` se conserva como un campo derivado
(`capability` → `kind`) para las pantallas.

### 10.2 El registro mínimo

Cinco preguntas y cinco campos. Nada más:

| Pregunta | Campo |
|---|---|
| ¿Quién? | `actorId` + `actorName` |
| ¿Qué hizo? | `capability` (+ `kind` derivado) |
| ¿Sobre qué? | `targetUserId` + `targetUserName` + `detail` |
| ¿Cuándo? | `at` |
| ¿Se lo permitió? | `outcome` |

```kotlin
/**
 * Una entrada del registro de accesos.
 *
 * Es la evidencia mínima de que "el docente no ve lo que no debe" y de que,
 * cuando lo ve, queda dicho quién y cuándo.
 */
@Serializable
data class AccessLogEntry(
    val id: String,
    val at: Long,                    // epoch millis
    val actorId: String,             // quién accedió
    val actorName: String,
    val courseId: String,
    val capability: Capability,      // qué se intentó hacer
    val targetUserId: String = "",   // de quién son los datos
    val targetUserName: String = "",
    val detail: String = "",         // ej. "hilo 42", "material m-7", "exportación CSV"
    val outcome: AccessOutcome = AccessOutcome.ALLOWED,
)
```

### 10.3 Qué se audita y qué no

**Sí** (`audited = true` en el enum `Capability`):

`CHAT_VIEW_OTHER`, `CHAT_VIEW_TOOL_CALLS`, `CHAT_VIEW_OTHER_METRICS`,
`ANALYTICS_VIEW_PER_STUDENT`, `ANALYTICS_VIEW_ANSWER_TEXT`, `ANALYTICS_EXPORT`,
`CHAT_DELETE_ANY`, `ENROLLMENT_SET_VISIBILITY`, `ENROLLMENT_SET_OWN_VISIBILITY`,
`APP_AUDIT_LOG_VIEW`, `APP_AUDIT_LOG_EXPORT`.

**No**: las lecturas de lo propio (`CHAT_VIEW_OWN`), la navegación sin contenido
(ver la lista de alumnos, ver la lista de hilos), la analítica agregada de la
cátedra (no es dato de un alumno), las acciones de diseño.

**También se auditan los intentos denegados** (`outcome = DENIED`): son los que
detectan un bug de permisos o un abuso.

> Nota: el `AccessLog` del docente es **local a su instalación** (`config/…`),
> no vive en el servidor. Eso significa que el alumno **no ve** el registro del
> docente salvo que el docente lo exporte. Es una limitación real del milestone 1
> y hay que decirla.
>
> **Mitigación en el milestone 2**: el docente publica sus accesos al servidor
> con `POST /v1/audit` (el scope `audit:write` ya existe en el diseño del
> servidor), y entonces el alumno sí los ve en su pantalla. Hasta entonces, la
> app del alumno muestra: *"Tu docente puede exportar el registro de accesos y
> mandártelo. Se lo podés pedir."*

### 10.4 Dónde vive, quién lo ve

| | Vive en | Quién lo ve |
|---|---|---|
| `AccessLog` del docente | `config/access_log.json` (o `config/…` en el servidor, v2) | El docente (t titular y ayudante), y el alumno **si el docente se lo exporta** |
| `EnrollmentAuditEvent` | `config/enrollment_audit.json` | El titular; el alumno ve las versiones que le aplican |
| `ErasureRecord` / `ErasureNotice` | `config/erasures.json` | El titular y el alumno afectado |

La pantalla del docente (ya diseñada en `teacher/07-chats.md` §6.3) es el lugar
natural; la del alumno es §4.1.

### 10.5 La vista del alumno

```
🛡 Accesos del docente — 14 desde el 12/03/2026
────────────────────────────────────────────────

13/03 10:15   Dra. Pérez      abrió 3 conversaciones tuyas (leyó el texto)
12/03 18:30   Dra. Pérez       vio tus métricas
12/03 09:02   Lic. Gómez       exportó tus datos
11/03 16:40   Dra. Pérez       buscó "derivada" en tus conversaciones
────────────────────────────────────────────────
14 accesos en total · 2 docentes · última lectura: hace 2 h

[ Ver los 14 ]  [ Exportar CSV ]
```

Cuatro reglas:

1. **Sin jerga.** "abrió 3 conversaciones tuyas (leyó el texto)", no
   `CHAT_VIEW_OTHER`.
2. **Sin identificadores técnicos.** Ni thread ids, ni seq, ni tokens de API.
3. **El detalle es el mínimo.** "3 conversaciones" y "12 coincidencias", nunca
   los snippets ni el texto buscado (coherente con `teacher/07-chats.md` §6.2:
   *"No se guarda el texto buscado ni los snippets"*).
4. **Coalescencia visible.** Si el docente abrió el mismo chat 12 veces, la
   entrada dice "×12", no 12 renglones.

### 10.6 Qué le falta (y es importante decirlo)

- **El log es local**: el docente puede borrarlo (`APP_AUDIT_LOG_EXPORT` /
   `APP_RESET`). Sin servidor, el log no es una prueba, es una cortesía.
- **Cadena de hashes**: `domain/08-permisos.md` §8.5 lo deja anotado. Con
   `prevHash` y `hash` en cada entrada, el tampering se detecta... salvo que
   quien lo borra también reescriba la cadena. Sólo sirve contra el borrado
   parcial. Se implementa en el milestone 2, cuando el log va al servidor
   (ahí sí hay un tercero que atestigua).
- **El docente puede exportar antes de que el alumno pregunte**: el log no
   notifica. En el milestone 2, cada acceso genera un evento en el servidor y
   el alumno recibe un push.

---

## 11. El texto legal: qué dice la app y dónde

### 11.1 Dónde vive

| Ubicación | Qué muestra | Audience |
|---|---|---|
| **Ajustes → Tus datos → "Política de privacidad"** | El resumen (§11.2) + el enlace a la política completa de la institución | Alumno |
| **Pantalla de inscripción** | Un link: *"Leé la política de privacidad de la facultad"* (debajo del Texto A) | Alumno que se inscribe |
| **Cátedra → Política de privacidad** (lado docente) | Un campo para pegar la URL de la política institucional + un texto propio de la cátedra (§11.3) | Docente |
| **`kitoru://privacy` y la URL de la app** | El mismo resumen, para poder mandarlo por WhatsApp | Alumno y docente |
| **Cada exportación** (CSV, PDF, `.kitoru`) | Una línea al pie: `"Generado por Kitoru el 12/03/2026. Los datos del alumno se comparten según su configuración de privacidad."` | Quien reciba el archivo |

### 11.2 El resumen (texto literal)

```
Privacidad en Kitoru
────────────────────

Quién trata tus datos
La facultad que administra esta cátedra es responsable de tus datos. Kitoru es
la herramienta: no decide qué se guarda ni qué se enseña.

Qué guardamos
· Las conversaciones que escribís, si usás la IA de la cátedra.
· Cuántos mensajes escribís, aunque no compartas el texto.
· La fecha de tu inscripción y el rol que tenés en la cátedra.
· El registro de cada vez que un docente abre tus conversaciones.

Qué NO guardamos
· Tu correo electrónico, salvo que lo escribas vos.
· Lo que escribís cuando usás una IA en tu propio dispositivo: eso no sale de
  tu computadora.

Quién puede ver qué
· Tu docente ve lo que vos autorizás en «Qué ve tu docente». Nadie, ni
  siquiera el titular, puede ver más de eso.
· Los ayudantes ven métricas, no el texto, salvo que el titular lo cambie.
· Los alumnos nunca ven las conversaciones de otros.

Dónde se procesan tus conversaciones
· Si usás la IA de la cátedra: en el servidor de la facultad, dentro de la
  institución, y no se usan para entrenar modelos.
· Si usás una IA local: en tu dispositivo.
· Si la cátedra usa un servicio de IA de terceros: te lo decimos en la
  inscripción, con el nombre del servicio.

Cuánto tiempo
· Las conversaciones se guardan hasta 30 días en el servidor de la facultad.
· El registro de accesos se guarda hasta 12 meses después de fin de curso.
· Cuando terminás la cursada, la clave de la IA de la cátedra deja de
  funcionar.

Tus derechos
Podés ver exactamente qué tiene tu docente sobre vos, cambiar lo que ve,
bajar tu nombre, pedir que borre tus datos y pedir una copia de todo, desde
«Tus datos». La política completa de la facultad está en: <URL>

Consultas
<email de la unidad de protección de datos / delegado de protección de datos de la facultad>
```

### 11.3 La política de la cátedra (texto del docente)

La cátedra puede **complementar** el resumen de la institución, pero no
contradecirlo. El campo es de texto libre y la app muestra:

```
Lo que agrega esta cátedra
────────────────────────────
<texto del docente>

Aviso: la política de la facultad (<URL>) manda sobre este texto.
```

Regla dura: si el texto del docente contradice la declaración técnica del
servidor (por ejemplo, dice "no usamos tus conversaciones" mientras
`GET /v1/privacy` dice `processor: EXTERNAL`), la app muestra un aviso:

```
⚠ Esta cátedra afirma que tus conversaciones no salen de la institución,
  pero su servidor declara procesar las conversaciones en un servicio externo
  (OpenAI). Leé la política de la facultad.
```

### 11.4 Lo que la app no puede prometer

1. **Kitoru no redacta la política legal** de la institución: la muestra. Si la
   facultad no tiene política de privacidad, la app lo dice:
   *"Esta facultad todavía no publicó una política de privacidad. Podés
   pedirle al docente o a la dirección de la carrera."*
2. **El resumen no es un contrato**: es un resumen. La app lo rotula como tal:
   *"Resumen. El documento que te vincula es el de la facultad."*
3. **No hay Analyze de IA**: la app no promete que el modelo no alucine ni que
   las respuestas sean correctas. La calidad se mide con el verificador de
   citas, que es determinista — y eso es un dato sobre el *corpus*, no una
   garantía sobre el contenido.
4. **La app no puede garantizar el borrado de lo que el docente copió**: por
   eso lo dice en el diálogo de borrado (§4.3) y no en letra chica.

---

## 12. Cambios de dominio

| Cambio | Archivo | Motivo |
|---|---|---|
| `ChatVisibility` gana `level` y `atMost` | `domain/Roles.kt` | La fórmula de D1 necesita un orden total |
| `Enrollment.visibility` = techo del alumno, `Enrollment.visibilityCap` = tope del docente, `Enrollment.consent` | `domain/Course.kt` | D1, D4 |
| `VisibilityConsent`, `ConsentState`, `ConsentTexts` (nuevo) | `domain/Privacy.kt` | D5 |
| `AccessControl` (nuevo) | `domain/AccessControl.kt` | La fórmula única (§1.2) |
| `ChatThread.visibilityOverride`, `visibilityOverrideAt` | `domain/Chat.kt` | D6 |
| `ChatPolicy.validateOverride` (nuevo) | `domain/ChatPolicy.kt` | Un override sólo baja |
| `Course.visibilityPolicy`, `Course.analyticsMinCohort`, `Course.analyticsMinCohortExport` | `domain/Course.kt` | Tope por rol + k-anonimidad |
| `CohortPrivacy` (nuevo) | `domain/CohortPrivacy.kt` | D7 |
| `DayActivity.students` | `domain/Analytics.kt` | El "n" de la k-anonimidad |
| `AnalyticsAnalyzer`: `topSources` filtrado por visibilidad + fail-closed | `domain/AnalyticsAnalyzer.kt` | §6.6 (fila real) |
| `AnalyticsAnalyzer.analyze(..., observer)` | `domain/AnalyticsAnalyzer.kt` | El analizador debe filtrar por visibilidad **efectiva** |
| `DataInventory`, `TeacherHolding`, `HeldThread` (nuevos) | `domain/DataInventory.kt` | §4.2 |
| `IdentityGrant`, `IdentityRequest` (nuevos) | `domain/Privacy.kt` | §7.4 |
| `ErasureRecord`, `ErasureNotice`, `ErasureDecision` (nuevos) | `domain/Erasure.kt` | §9 |
| `ModelPolicy.processorKind`, `processorName`, `noTraining`, `retentionDays` | `domain/ModelPolicy.kt` | §8.3 (la declaración sale de la config real) |
| `Enrollment.seatNumber`, `aliasKind`, `labelSource` | `domain/Course.kt` | §7.3 (seudonimización) |
| `AliasKind` default `ANONYMOUS` | `domain/Roles.kt` / `domain/Identity.kt` | D8 — **cambio de default** |

### 12.1 `ModelPolicy`: los campos de la declaración

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt

/** Quién procesa el prompt cuando el modelo es REMOTE. */
@Serializable
enum class ProcessorKind(val label: String) {
    /** El servidor de la institución. */
    INSTITUTION("Servidor de la institución"),

    /** Un proveedor externo, fuera de la red de la institución. */
    EXTERNAL("Servicio externo"),
}

@Serializable
data class ModelPolicy(
    // ... los campos actuales ...
    val mode: ModelMode = ModelMode.REMOTE,

    // --- privacidad (nuevo) ---
    /** Quién procesa el prompt. */
    val processorKind: ProcessorKind = ProcessorKind.INSTITUTION,
    /** Nombre legible del procesador ("OpenAI", "Facultad de Ingeniería"). */
    val processorName: String = "",
    /** El proveedor declara que no usa las conversaciones para entrenar. */
    val noTraining: Boolean = true,
    /** Días de retención en el servidor. 0 = sólo en memoria. */
    val retentionDays: Int = 30,
)
```

`validate()` gana una comprobación:

```kotlin
if (mode == ModelMode.REMOTE && processorKind == ProcessorKind.EXTERNAL && processorName.isBlank()) {
    errors += "Falta el nombre del servicio externo que procesa las conversaciones."
}
```

Y `ModelPolicy` gana un método que produce el **texto literal** de la
declaración (así el texto no está duplicado en la UI):

```kotlin
/**
 * Texto de declaración que ve el alumno, derivado de la configuración real.
 *
 * Es una función pura: el mismo `ModelPolicy` produce siempre el mismo texto,
 * y el texto no puede decir "no training" si la configuración no lo dice.
 */
fun disclosureText(): String = when {
    mode == ModelMode.LOCAL ->
        "Estás usando un modelo en tu propio dispositivo. Lo que escribís no " +
        "sale de tu computadora y tu docente no ve nada."

    processorKind == ProcessorKind.INSTITUTION ->
        "Las conversaciones se procesan en el servidor de la facultad, en un " +
        "modelo que corre dentro de la institución. No salen de la red de la " +
        "universidad y no se usan para entrenar modelos."

    noTraining ->
        "Esta cátedra usa un servicio de IA de terceros ($processorName), " +
        "fuera de la red de la universidad. Tus conversaciones se envían a ese " +
        "servicio para generar las respuestas. La cátedra declara que ese " +
        "servicio no las usa para entrenar modelos."

    else ->
        "Esta cátedra usa un servicio de IA de terceros ($processorName), " +
        "fuera de la red de la universidad. Tus conversaciones se envían a ese " +
        "servicio para generar las respuestas. Kitoru no puede garantizar que " +
        "ese servicio no las use para entrenar sus modelos."
}
```

### 12.2 `AnalyticsAnalyzer`: el filtro por visibilidad, corregido

```kotlin
fun analyze(
    course: Course,
    enrollments: List<Enrollment>,
    threads: List<ChatThread>,
    messages: List<ChatMessage>,
    observer: Enrollment? = null,      // nuevo: quién pregunta
): CourseAnalytics {
    val visible = enrollments.filter {
        AccessControl.effectiveVisibility(course, observer, it).showsMetrics
    }
    // ... el resto igual, más el filtro de topSources (§6.6) ...
}
```

> Con `observer = null` se conserva el comportamiento actual (docente titular).
> Con un observador real, un ayudante ve el mismo conjunto de alumnos (el tope
> por rol lo afecta al **texto**, no a las métricas), pero el conjunto se
> recalcula igual, para que el número de alumnos "sin datos compartidos" sea
> correcto.

---

## 13. Plan de tests

| Test | Qué cubre |
|---|---|
| `AccessControlTest` | La fórmula de D1 con las 3×3×3 combinaciones de tope por rol / tope del docente / techo del alumno. El **fail-closed**: sin `GRANTED`, el techo es `METRICS_ONLY`. |
| `VisibilityConsentTest` | `NOT_REQUESTED` ⇒ `METRICS_ONLY`; `GRANTED` ⇒ lo declarado; `WITHDRAWN` ⇒ `METRICS_ONLY`. El hash del texto coincide con `ConsentTexts.hash`. |
| `ChatPolicyTest` | Un override `FULL` sobre un techo `METRICS_ONLY` se rechaza con el mensaje en español. Un override `NONE` se acepta. El override nunca sube el nivel. |
| `CohortPrivacyTest` | K=5 muestra, K=10 exporta. `suppressedIndexes` suprime la secundaria (2 celdas) cuando queda una sola suprimida. El piso K=5 no se puede bajar. |
| `AnalyticsPrivacyTest` | Un alumno `NONE` **no** aparece en `topSources` (el bug de §6.6). Un alumno `NONE` no aparece en ningún agregado ni en `perDay`. Un alumno sin consentimiento aparece sólo con métricas. |
| `DataInventoryTest` | El inventario con `FULL` lista los hilos con título; con `METRICS_ONLY` los lista sin título y con `textVisible = false`; con `NONE` devuelve `holdsNothing = true`. El `since` es el primer mensaje. |
| `ModelPolicyDisclosureTest` | El texto de `disclosureText()` cambia según `processorKind` y `noTraining`. Con `EXTERNAL` + `noTraining = false` aparece la advertencia completa. |
| `ErasureTest` | La supresión borra el contenido, conserva el `ErasureRecord` con conteos, anonimiza el `targetUserId` de la auditoría y conserva la decisión del docente. |
| `IdentityRulesTest` | El número de padrón es estable; la etiqueta es "Alumno 037"; el `Enrollment.displayName` es la etiqueta y no el nombre real; el `authorName` del hilo es la etiqueta. |
| `MigrationTest` | Un `enrollments.json` viejo (sin `consent`) se lee sin error y produce `METRICS_ONLY` efectivo. |

Todo en `commonTest`.

---

## 14. Archivos

```
core/src/commonMain/kotlin/kitoru/domain/
├── Roles.kt              # ChatVisibility con level + atMost
├── Course.kt             # Enrollment (visibility, visibilityCap, consent, seatNumber…)
├── Privacy.kt            # ConsentState, VisibilityConsent, ConsentTexts, IdentityGrant
├── AccessControl.kt      # AccessControl (la fórmula única)
├── ChatPolicy.kt         # validateOverride, canReadText
├── Chat.kt               # ChatThread.visibilityOverride
├── CohortPrivacy.kt      # k-anonimidad
├── DataInventory.kt      # DataInventory, TeacherHolding, HeldThread
├── Erasure.kt            # ErasureRecord, ErasureNotice, ErasureDecision
├── ModelPolicy.kt        # ProcessorKind, disclosureText()
├── Analytics.kt          # DayActivity.students
├── AnalyticsAnalyzer.kt  # filtro por visibilidad efectiva + topSources corregido
└── AccessLog.kt          # (de domain/08-permisos.md) AccessLogEntry

ui/src/commonMain/kotlin/kitoru/student/privacy/
├── MyDataScreen.kt       # "Tus datos"
├── VisibilityPicker.kt   # Texto B
├── ConsentDialogs.kt     # Textos A, C, D, E
├── AccessLogList.kt      # §10.5
├── ErasureDialog.kt      # §4.3
└── DownloadMyData.kt     # §4.4
```

---

## 15. Casos borde

| Caso | Comportamiento definido |
|---|---|
| El alumno se inscribe y cierra la app sin confirmar el Texto A | No se crea la `Enrollment`. Si el docente lo agregó a mano, la inscripción queda `METRICS_ONLY` hasta que el alumno entre. |
| El alumno baja la visibilidad a `NONE` y después pide al docente que suba | El flujo de solicitud funciona igual (§3.5): el docente sube **con la aprobación**. Sin aprobación, no sube. |
| El docente sube la visibilidad de un alumno `NONE` a `FULL` "para revisar un parcial" | **Rechazado** sin consentimiento del alumno o justificación escrita de ≥ 30 caracteres (regla de `teacher/08-alumnos.md` §6.2). El alumno recibe el aviso. |
| El alumno usa IA local y nunca comparte | El docente ve "Alumno 037" en el padrón y nada más. En la analítica: `studentsWithoutSharedData`, no `inactiveStudents` ("0 mensajes" ≠ "no lo sé"). |
| El alumno importa un paquete `.kitoru` con `visibility = NONE` | La importación se rechaza con el mensaje de `domain/03-inscripciones.md` §6.5. El texto nunca se escribe en el disco del docente. |
| El alumno usa IA local y exporta un paquete para compartirlo | El paquete se genera con `visibility = FULL` **sólo** si el alumno lo elige explícitamente en el diálogo de exportación; el diálogo dice qué va a pasar. |
| La cátedra tiene 3 alumnos | La analítica agregada **no se muestra** (K=5). El docente ve el padrón, que es información institucional, no analítica. |
| La cátedra tiene 30 alumnos, 28 con `METRICS_ONLY` y 2 con `FULL` | El docente ve los textos de 2 alumnos y las métricas de 30. Los agregados se calculan sobre los 30 (las métricas están autorizadas para los 30). |
| Un alumno pide la baja después de que el docente exportó su chat | El chat ya no se borra del PDF del docente. La app del alumno lo dice en el diálogo: "lo que ya leyó no se borra". El `ErasureNotice` se manda igual. |
| El docente no responde el aviso de supresión | A los 7 días, el servidor escala el caso al titular de la cátedra y el alumno ve: "El docente todavía no confirmó la eliminación de tus datos." |
| El servidor institucional no declara `GET /v1/privacy` | La app aplica la declaración conservadora (§8.2) y el docente ve `METRICS_ONLY` para ese alumno, aunque el alumno haya autorizado `FULL`. Es fail-closed. |
| El alumno cambia de alias mientras el docente tiene la etiqueta bloqueada (`labelSource = TEACHER`) | Gana el docente (`domain/09-identidad.md` §4.1) y el alumno ve el aviso: "tu nombre visible fue reemplazado por tu docente". |
| Dos alumnos comparten el mismo `userId` (perfiles fusionados a medias) | La analítica los cuenta como uno. No se arregla en este documento; queda anotado. |
| Un hilo se borra lógicamente y luego el docente lo pide por el paquete de importación | El hilo no existe en el paquete (el borrado lógico saca el hilo del store). |

---

## 16. Decisiones abiertas (para la sesión que decida)

1. **¿El `K` de 5 se puede bajar?** Este documento dice que no (piso 5). Una
   facultad con cursos de 4 alumnos preferiría ver la analítica. Alternativa:
   permitir bajar a 3 con un aviso explícito ("cohortes menores: los datos
   agregados pueden ser re-identificables"). **Recomendación: mantener el
   piso en 5** y ofrecer, en su lugar, "modo aula chica": el docente ve los
   datos individuales de sus alumnos **sin** agregados, porque los datos
   individuales ya requieren consentimiento individual.
2. **¿La retención por defecto del servidor es 30 o 90 días?** El diseño del
   servidor (`platform/03-kitoru-server-api.md` §8.2) dice 90. Este documento
   recomienda 30 para la IA alojada, y 0 como opción ("sólo en memoria": el
   docente ve el chat en vivo, sin historial). **Requiere decisión del servidor.**
3. **¿La identidad real se puede pedir una sola vez o repetidas?** Cada
   solicitud debería generar ruido (el alumno ve un contador "tu docente te
   pidió la identidad 3 veces"). Propuesta: máximo 1 solicitud abierta por
   docente y por curso.
4. **¿El "borrar mis datos" borra el perfil local?** Este documento dice sí
   (§4.3). Pero el `UserProfile` puede tener inscripciones en otras
   cátedras: borrar el perfil las deja huérfanas. Alternativa: borrar el
   contenido y dejar el perfil, que es lo que hace `domain/09-identidad.md`
   §12. **Recomendación: borrar el contenido siempre; el perfil, sólo si el
   alumno lo pide explícitamente** (con un check aparte).
5. **El texto legal**: ¿lo escribe la facultad o Kitoru? Este documento asume
   que la facultad (que es el responsable del tratamiento) y que Kitoru muestra
   un resumen. La alternativa —que Kitoru provea el modelo de aviso de privacidad
   para que cada facultad lo personalice— es más trabajo pero más útil.

---

## 17. Lo que este documento NO hace (y por qué)

- **No redefine la matriz de permisos**: la usa (`domain/08-permisos.md`).
- **No diseña la pantalla del alumno**: la describe como contrato (qué datos
  aparecen, en qué orden, con qué textos); el diseño visual vive en el
  documento del alumno.
- **No diseña el endpoint de borrado**: lo especifica como contrato
  (`DELETE /v1/students/{subject}/data`), porque sin él el derecho de supresión
  es de mentira.
- **No escribe la política de privacidad de la facultad**: escribe el resumen
  que la app muestra y dice cuál es el documento que vincula.
