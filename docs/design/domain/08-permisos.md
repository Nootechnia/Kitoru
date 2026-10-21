# 08 · Permisos y capacidades

> Diseño del control de acceso de Kitoru: qué puede hacer cada rol en cada
> cátedra, bajo qué condiciones, cómo se combinan los permisos de las
> herramientas del modelo y cómo queda registrada la lectura de datos de los
> alumnos.
>
> Módulo objetivo: `:core` (`kitoru.domain`), con un punto de apoyo en
> `:backend` (`rag.agent.ToolPermissions`).

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | Los permisos se modelan como **capacidades** (`Capability`), no como banderas sueltas por rol. | Un enum finito y con nombre permite exhaustive-checking, UI auto-documentada, logs legibles y un único lugar donde agregar una acción nueva. |
| D2 | La matriz rol × capacidad es una **tabla de datos** (`PermissionMatrix.ROWS`), no un `when`. | La tabla es la fuente única: de ahí se genera el markdown de este documento y la pantalla "Ver matriz de permisos", así que el doc no puede quedar viejo. Un `when` de 60 ramas es imposible de auditar de un vistazo y su `else` olvocado concede o deniega en silencio. |
| D3 | Lo **condicional** se modela con un `Condition` por capacidad, evaluado contra un contexto inmutable. | Las condiciones quedan explícitas, son funciones puras y se testean sin UI ni disco. |
| D4 | La condicionalidad es **intrínseca a la capacidad**, no al rol: si una capacidad tiene condición, es `CONDITIONAL` en todo rol donde no está `DENIED`. | Evita el caso imposible de "el titular puede pero el ayudante no, y la diferencia es la condición" y hace la matriz coherente y verificable con un `require`. |
| D5 | Los permisos de herramientas tienen **dos niveles**: techo de cátedra (docente) y concesión por chat (alumno). El techo manda. | El docente puede apagar la web en una cátedra de derecho; el alumno sigue pudiendo conceder *dentro* del techo. |
| D6 | El techo de herramientas se aplica en `ToolContext.allowedCategories`, **fail-closed** por defecto. | Si el techo no se calcula, ninguna herramienta externa corre. |
| D7 | Todo acceso a datos privados de un alumno se **registra** en un `AccessLog` local (append-only, tope de entradas). | Transparencia: el titular ve qué accesó un ayudante, y el propio docente deja rastro de que leyó los chats. |
| D8 | El estado global de la app se modela como `AppSettings`, con ámbito propio (`CapabilityScope.APP`). | Las capacidades de la app no dependen del rol de cátedra: el usuario local es dueño de su instalación. |

---

## 1. Principios

1. **El rol es de cátedra, no de la app.** `TITULAR`, `AYUDANTE` y `ALUMNO`
   dicen qué se puede hacer *dentro de una cátedra*. Lo que se puede hacer
   con *la instalación* (ajustes, modelos locales, MCP, auditoría) es otro
   ámbito (`APP`).
2. **La visibilidad del alumno manda.** Ninguna capacidad de lectura de chats
   de un alumno se concede si su inscripción no lo autoriza
   (`ChatVisibility.showsText` / `showsMetrics`). Es la regla, no la excepción
   (regla 6 del CONTEXTO).
3. **El docente no ve lo que no debe, y queda constancia de lo que ve.**
4. **Fail-closed.** Ante la duda, se deniega. Los permisos de herramientas
   arrancan cerrados.
5. **Portable.** Todo el modelo de permisos vive en `commonMain`: nada de
   `java.nio`, nada de I/O en el resolver.
6. **Determinista y testeable.** `resolve()` es una función pura sobre datos
   inmutables: mismo contexto → mismo resultado, sin disco, sin red, sin reloj.

---

## 2. Áreas y lista completa de capacidades

Las capacidades se agrupan en siete áreas. Cada entrada del enum lleva: área,
etiqueta legible para la UI, ámbito, condición intrínseca (si la tiene) y si
es **auditada** (§7).

### 2.1 Cátedra (`CapabilityArea.CATEDRA`)

| Capacidad | Qué habilita |
|---|---|
| `COURSE_VIEW` | Ver la cátedra (nombre, código, cursada, descripción, estado). |
| `COURSE_EDIT` | Editar los datos de la cátedra (nombre, código, cursada, descripción). |
| `COURSE_PUBLISH` | Publicar la cátedra (de borrador a publicada). |
| `COURSE_UNPUBLISH` | Volver a borrador. |
| `COURSE_ARCHIVE` | Archivar la cátedra (deja de aceptar inscripciones). |
| `COURSE_DELETE` | Eliminar la cátedra (destructivo). |
| `COURSE_VIEW_JOIN_CODE` | Ver el código de inscripción. |
| `COURSE_ROTATE_JOIN_CODE` | Regenerar el código de inscripción. |
| `COURSE_CONFIG_RAG` | Ajustar la recuperación (`topK`, `chunkSize`). |
| `COURSE_VIEW_SETTINGS` | Ver la configuración completa de la cátedra. |
| `COURSE_EXPORT` | Exportar la cátedra (configuración + índice de materiales). |

### 2.2 Materiales (`CapabilityArea.MATERIALES`)

| Capacidad | Qué habilita |
|---|---|
| `MATERIAL_VIEW` | Ver el listado de materiales. |
| `MATERIAL_VIEW_CONTENT` | Abrir el material y leer su texto extraído. |
| `MATERIAL_UPLOAD` | Subir material. |
| `MATERIAL_EDIT` | Editar metadata (título, tipo, etiquetas). |
| `MATERIAL_DELETE` | Eliminar material. |
| `MATERIAL_REINDEX` | Re-extraer el texto y reindexar. |
| `MATERIAL_VIEW_HIDDEN` | Ver los materiales marcados como no visibles para los alumnos. |

> El filtrado por `Material.visible` **no** es una capacidad: es un filtro de
> recurso. Un alumno con `MATERIAL_VIEW` ve el listado, pero sólo de los
> materiales visibles; ver los ocultos exige `MATERIAL_VIEW_HIDDEN`.

### 2.3 Modelo (`CapabilityArea.MODELO`)

| Capacidad | Qué habilita |
|---|---|
| `MODEL_USE` | Usar el modelo en la cátedra (enviar mensajes). |
| `MODEL_VIEW_POLICY` | Ver la configuración base del modelo (la clave de API se redacta siempre). |
| `MODEL_EDIT_POLICY` | Editar la configuración base (endpoint, modelo, temperatura, tokens). |
| `MODEL_SET_MODE` | Cambiar el modo (`REMOTE` servidor de la cátedra / `LOCAL` IA local). |
| `MODEL_LOCK_FIELDS` | Bloquear campos para que el alumno no los toque. |
| `MODEL_SET_ALLOW_OVERRIDE` | Permitir o quitar el override del alumno. |
| `MODEL_EDIT_SYSTEM_PROMPT` | Editar las instrucciones del sistema (`Course.systemPrompt` y `ModelPolicy.systemPrompt`). |
| `MODEL_OVERRIDE` | Ajustar la configuración **para sí mismo** en el chat. |
| `MODEL_USE_LOCAL` | Usar IA local en el propio dispositivo. |

**Herramientas del modelo** (mismo área, sub-bloque "herramientas"):

| Capacidad | Qué habilita |
|---|---|
| `MODEL_TOOLS_ENABLE` | Interruptor maestro: habilitar herramientas en la cátedra. |
| `MODEL_TOOLS_FILES` | Permitir la categoría de herramientas de archivos. |
| `MODEL_TOOLS_WEB` | Permitir la categoría de herramientas web. |
| `MODEL_TOOLS_MCP` | Permitir la categoría de herramientas MCP. |
| `MODEL_TOOLS_GRANT` | Permitir que el usuario conceda permisos de herramientas por chat. |

### 2.4 Chats (`CapabilityArea.CHATS`)

| Capacidad | Qué habilita |
|---|---|
| `CHAT_CREATE` | Crear un hilo de conversación. |
| `CHAT_SEND` | Enviar mensajes y recibir respuestas. |
| `CHAT_VIEW_OWN` | Ver los chats propios. |
| `CHAT_VIEW_OTHER` | Ver el **texto** de los chats de otro participante. |
| `CHAT_VIEW_OTHER_METRICS` | Ver las **métricas agregadas** de otro participante. |
| `CHAT_VIEW_TOOL_CALLS` | Ver el detalle de las herramientas que invocó el modelo en chats ajenos. |
| `CHAT_DELETE_OWN` | Borrar los chats propios. |
| `CHAT_DELETE_ANY` | Borrar los chats de cualquier participante. |
| `CHAT_RENAME_TAG` | Renombrar y etiquetar hilos (los propios; los ajenos exigen además `CHAT_VIEW_OTHER`). |

### 2.5 Analítica (`CapabilityArea.ANALITICA`)

| Capacidad | Qué habilita |
|---|---|
| `ANALYTICS_VIEW_COURSE` | Ver la analítica agregada de la cátedra. |
| `ANALYTICS_VIEW_PER_STUDENT` | Ver el detalle por alumno. |
| `ANALYTICS_VIEW_ANSWER_TEXT` | Ver el texto de las respuestas de un alumno (drill-down). |
| `ANALYTICS_VIEW_SOURCES` | Ver qué materiales se consultan más. |
| `ANALYTICS_EXPORT` | Exportar la analítica (CSV/JSON). |
| `ANALYTICS_VIEW_OWN` | Ver la propia actividad. |

### 2.6 Inscripciones (`CapabilityArea.ENROLMENTS`)

| Capacidad | Qué habilita |
|---|---|
| `ENROLLMENT_VIEW_ALL` | Ver el listado completo de participantes. |
| `ENROLLMENT_VIEW_OWN` | Ver la propia inscripción. |
| `ENROLLMENT_INVITE` | Inscribir/invitar a alguien (por código o por usuario). |
| `ENROLLMENT_REMOVE` | Quitar a un participante de la cátedra. |
| `ENROLLMENT_CHANGE_ROLE` | Cambiar el rol de un participante. |
| `ENROLLMENT_SET_VISIBILITY` | **Restringir** la visibilidad de los chats de un alumno (nunca ampliarla). |
| `ENROLLMENT_SET_OWN_VISIBILITY` | El alumno fija **su tope** de visibilidad. |
| `ENROLLMENT_SELF_LEAVE` | Darse de baja de la cátedra. |

### 2.7 Configuración de la app (`CapabilityArea.APP`)

| Capacidad | Qué habilita |
|---|---|
| `APP_SETTINGS_VIEW` | Ver los ajustes globales. |
| `APP_SETTINGS_EDIT` | Editar los ajustes globales. |
| `APP_LOCAL_MODEL_MANAGE` | Instalar y configurar modelos locales (llama.cpp). |
| `APP_MCP_MANAGE` | Agregar/quitar servidores MCP. |
| `APP_TOOLS_POLICY` | Política global de herramientas (qué categorías admite la app). |
| `APP_AUDIT_LOG_VIEW` | Ver el registro de accesos. |
| `APP_AUDIT_LOG_EXPORT` | Exportar el registro de accesos. |
| `APP_DATA_EXPORT` | Exportar todos los datos locales (diagnóstico). |
| `APP_RESET` | Borrar todos los datos locales. |

**Total: 64 capacidades.**

> `Capability` es `@Serializable` porque aparece dentro de `AccessLogEntry`.
> kotlinx.serialization serializa los enums **por nombre**, así que la
> propiedad `condition` (que no es serializable) no se persiste: al leer un
> log, el enum se reconstruye y su condición se recupera del propio enum.
> Añadir una capacidad al enum no rompe los logs viejos: `ignoreUnknownKeys`
> y `fromName` cubren el caso inverso.

---

## 3. Matriz rol × capacidad

Leyenda: **✔** permitido · **◆** condicional (permitido si se cumple la
condición de la última columna) · **✖** denegado.

Las capacidades de ámbito **app** no dependen del rol de cátedra (el usuario
local es dueño de su instalación): sus tres celdas son iguales y su veredicto
real sale de `AppSettings` a través de la condición.

### 3.1 Cátedra

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `COURSE_VIEW` | cátedra | ✔ | ✔ | ✔ | — |
| `COURSE_EDIT` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_PUBLISH` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_UNPUBLISH` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_ARCHIVE` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_DELETE` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_VIEW_JOIN_CODE` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_ROTATE_JOIN_CODE` | cátedra | ✔ | ✖ | ✖ | — |
| `COURSE_CONFIG_RAG` | cátedra | ◆ | ◆ | ✖ | la cátedra está publicada |
| `COURSE_VIEW_SETTINGS` | cátedra | ✔ | ✔ | ✖ | — |
| `COURSE_EXPORT` | cátedra | ✔ | ✖ | ✖ | — |

### 3.2 Materiales

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `MATERIAL_VIEW` | cátedra | ✔ | ✔ | ✔ | — |
| `MATERIAL_VIEW_CONTENT` | cátedra | ✔ | ✔ | ✔ | — |
| `MATERIAL_UPLOAD` | cátedra | ◆ | ◆ | ✖ | la cátedra está publicada |
| `MATERIAL_EDIT` | cátedra | ◆ | ◆ | ✖ | la cátedra está publicada |
| `MATERIAL_DELETE` | cátedra | ✔ | ✖ | ✖ | — |
| `MATERIAL_REINDEX` | cátedra | ◆ | ◆ | ✖ | la cátedra está publicada |
| `MATERIAL_VIEW_HIDDEN` | cátedra | ✔ | ✖ | ✖ | — |

> `MATERIAL_DELETE` es exclusivo del titular a propósito: borrar altera el
> corpus compartido y afecta el RAG de todos. El ayudante edita y reindexa,
> no borra.
>
> **Por qué "publicada" también para el titular** (invariante I2: si una
> capacidad tiene condición, es condicional en todo rol donde no está
> denegada): mientras la cátedra es un borrador el corpus está "en
> construcción"; el flujo es `borrador → publicar → materiales → reindexar`.
> Quien quiera volver atrás usa `COURSE_UNPUBLISH`, que no tiene condición.
> Si en el futuro molesta, la alternativa es una condición propia
> (`CourseNotArchived`) en lugar de `CoursePublished`.

### 3.3 Modelo

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `MODEL_USE` | cátedra | ✔ | ✔ | ✔ | — |
| `MODEL_VIEW_POLICY` | cátedra | ✔ | ✔ | ✔ | — |
| `MODEL_EDIT_POLICY` | cátedra | ✔ | ✖ | ✖ | — |
| `MODEL_SET_MODE` | cátedra | ✔ | ✖ | ✖ | — |
| `MODEL_LOCK_FIELDS` | cátedra | ✔ | ✖ | ✖ | — |
| `MODEL_SET_ALLOW_OVERRIDE` | cátedra | ✔ | ✖ | ✖ | — |
| `MODEL_EDIT_SYSTEM_PROMPT` | cátedra | ✔ | ✖ | ✖ | — |
| `MODEL_OVERRIDE` | cátedra | ◆ | ◆ | ◆ | la cátedra permite el ajuste del alumno |
| `MODEL_USE_LOCAL` | cátedra | ◆ | ◆ | ◆ | la cátedra permite IA local |

> `MODEL_VIEW_POLICY` para el alumno muestra la configuración **efectiva**
> que le aplica (base + sus ajustes), con la clave de API redactada siempre.
> La redacción no es un permiso: es una regla de presentación.

### 3.4 Herramientas del modelo

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `MODEL_TOOLS_ENABLE` | cátedra | ✔ | ✖ | ✖ | — |
| `MODEL_TOOLS_FILES` | cátedra | ◆ | ◆ | ◆ | herramientas habilitadas **y** categoría `FILES` permitida por el docente |
| `MODEL_TOOLS_WEB` | cátedra | ◆ | ◆ | ◆ | herramientas habilitadas **y** categoría `WEB` permitida por el docente |
| `MODEL_TOOLS_MCP` | cátedra | ◆ | ◆ | ◆ | herramientas habilitadas, categoría `MCP` permitida, la app permite MCP y la instalación no está gestionada |
| `MODEL_TOOLS_GRANT` | cátedra | ◆ | ◆ | ◆ | herramientas habilitadas en la cátedra |

> `MODEL_TOOLS_GRANT` habilita el **diálogo** de permiso de herramientas.
> Para el alumno, además, `CourseToolPolicy.allowStudentGrant` debe ser
> `true`: si el docente lo apaga, el techo del alumno queda vacío (§6.2) y no
> hay nada que conceder. Esa parte es una regla de recurso (el techo), no una
> capacidad: por eso no se modela como condición, que sería dependiente del
> rol.
>
> Las capacidades `MODEL_TOOLS_*` expresan la **política de la cátedra**; el
> techo real que se aplica en runtime es la intersección con la política
> global de la app (§6.2).

### 3.5 Chats

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `CHAT_CREATE` | cátedra | ✔ | ✔ | ✔ | — |
| `CHAT_SEND` | cátedra | ✔ | ✔ | ✔ | — |
| `CHAT_VIEW_OWN` | cátedra | ✔ | ✔ | ✔ | — |
| `CHAT_VIEW_OTHER` | cátedra | ◆ | ◆ | ◆ | el alumno autorizó ver el texto de sus chats |
| `CHAT_VIEW_OTHER_METRICS` | cátedra | ◆ | ◆ | ◆ | el alumno autorizó ver las métricas de sus chats |
| `CHAT_VIEW_TOOL_CALLS` | cátedra | ◆ | ◆ | ◆ | el alumno autorizó ver el texto de sus chats |
| `CHAT_DELETE_OWN` | cátedra | ✔ | ✔ | ✔ | — |
| `CHAT_DELETE_ANY` | cátedra | ✔ | ✖ | ✖ | — |
| `CHAT_RENAME_TAG` | cátedra | ✔ | ✔ | ✔ | — |

> `CHAT_VIEW_OTHER` con veredicto condicional **también** para el alumno: un
> alumno no puede leer los chats de otro alumno salvo que ese otro lo
> autorice (visibilidad `FULL`).
>
> `CHAT_RENAME_TAG` es ✔ para todos porque sólo aplica sobre hilos propios;
> sobre un hilo ajeno hay que tener además `CHAT_VIEW_OTHER`. Eso es un filtro
> de recurso, no una capacidad.

### 3.6 Analítica

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `ANALYTICS_VIEW_COURSE` | cátedra | ◆ | ◆ | ✖ | analítica habilitada (cátedra **y** app) |
| `ANALYTICS_VIEW_PER_STUDENT` | cátedra | ◆ | ◆ | ✖ | analítica habilitada **y** el alumno autorizó ver sus métricas |
| `ANALYTICS_VIEW_ANSWER_TEXT` | cátedra | ◆ | ◆ | ✖ | analítica habilitada **y** el alumno autorizó ver el texto |
| `ANALYTICS_VIEW_SOURCES` | cátedra | ◆ | ◆ | ✖ | analítica habilitada |
| `ANALYTICS_EXPORT` | cátedra | ◆ | ◆ | ✖ | analítica habilitada |
| `ANALYTICS_VIEW_OWN` | cátedra | ✔ | ✔ | ✔ | — |

### 3.7 Inscripciones

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `ENROLLMENT_VIEW_ALL` | cátedra | ✔ | ✔ | ✖ | — |
| `ENROLLMENT_VIEW_OWN` | cátedra | ◆ | ◆ | ◆ | es la propia inscripción |
| `ENROLLMENT_INVITE` | cátedra | ✔ | ✔ | ✖ | — |
| `ENROLLMENT_REMOVE` | cátedra | ◆ | ◆ | ✖ | el objetivo no es el titular de la cátedra |
| `ENROLLMENT_CHANGE_ROLE` | cátedra | ✔ | ✖ | ✖ | — |
| `ENROLLMENT_SET_VISIBILITY` | cátedra | ✔ | ✔ | ✖ | — |
| `ENROLLMENT_SET_OWN_VISIBILITY` | cátedra | ◆ | ◆ | ◆ | es la propia inscripción |
| `ENROLLMENT_SELF_LEAVE` | cátedra | ✖ | ✔ | ✔ | — |

> `ENROLLMENT_SET_VISIBILITY` es del docente pero **sólo puede restringir**:
> nunca ampliar la visibilidad más allá del tope que el alumno autorizó
> (`Enrollment.visibilityCeiling`). Si el docente intenta subirla, el servicio
> de inscripciones lo rechaza con un mensaje en español. Es una regla de
> negocio sobre el permiso concedido, no una capacidad distinta.
>
> `ENROLLMENT_SELF_LEAVE` es ✖ para el titular: el titular archiva o borra la
> cátedra, no se "da de baja" de ella.

### 3.8 Configuración de la app

| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |
|---|---|:---:|:---:|:---:|---|
| `APP_SETTINGS_VIEW` | app | ✔ | ✔ | ✔ | — |
| `APP_SETTINGS_EDIT` | app | ✔ | ✔ | ✔ | — |
| `APP_LOCAL_MODEL_MANAGE` | app | ◆ | ◆ | ◆ | la instalación no está gestionada por la institución |
| `APP_MCP_MANAGE` | app | ◆ | ◆ | ◆ | la app permite MCP **y** la instalación no está gestionada |
| `APP_TOOLS_POLICY` | app | ◆ | ◆ | ◆ | la instalación no está gestionada por la institución |
| `APP_AUDIT_LOG_VIEW` | app | ◆ | ◆ | ◆ | el registro de accesos está habilitado |
| `APP_AUDIT_LOG_EXPORT` | app | ◆ | ◆ | ◆ | el registro de accesos está habilitado |
| `APP_DATA_EXPORT` | app | ✔ | ✔ | ✔ | — |
| `APP_RESET` | app | ✔ | ✔ | ✔ | — |

---

## 4. Implementación de la matriz: tabla de datos vs `when`

### 4.1 Las dos opciones

**Opción A — `when` en `CourseRole.can(capability)`:**

```kotlin
fun can(capability: Capability): Boolean = when (capability) {
    Capability.COURSE_EDIT -> this == CourseRole.TITULAR
    Capability.MATERIAL_UPLOAD -> this != CourseRole.ALUMNO
    // ... 61 ramas
}
```

**Opción B — tabla de datos:**

```kotlin
data class PermissionRow(
    val capability: Capability,
    val titular: Verdict,
    val ayudante: Verdict,
    val alumno: Verdict,
)

object PermissionMatrix {
    val ROWS: List<PermissionRow> = listOf(
        PermissionRow(Capability.COURSE_VIEW, ALLOWED, ALLOWED, ALLOWED),
        PermissionRow(Capability.COURSE_EDIT, ALLOWED, DENIED, DENIED),
        // ... una fila por capacidad
    )
}
```

### 4.2 Recomendación: **opción B (tabla de datos)**

1. **El documento se genera desde el código.** `PermissionMatrix.toMarkdown()`
   produce exactamente la tabla de §3. Un test compara el markdown del
   documento con el generado: si alguien agrega una capacidad y no
   actualiza el doc, el test falla. Con un `when` esto es imposible de
   automatizar.
2. **Es auditable de un vistazo.** La tabla se lee como el doc. Un `when` de
   61 ramas repartidas en un solo cuerpo es imposible de revisar de un
   vistazo, y el `else` final (o un `else -> false`) esconde los casos
   olvidados: agregar una capacidad y forgetting la rama significa
   "denegado en todos los roles" sin que nadie lo note.
3. **Crece sin costo.** Agregar una capacidad es agregar una fila y una
   entrada del enum; la cobertura se verifica sola (abajo).
4. **Se puede mostrar en la app.** La misma tabla alimenta una pantalla
   "Ver matriz de permisos" y, más adelante, la validación del lado del
   servidor (Kitoru-Server) que aplica la política de la cátedra.
5. **La tabla es dato, no lógica.** Se puede embeber en un `when` para
   serializar, o leer de un recurso en el futuro.

**Contra:** el `when` da exhaustividad en tiempo de compilación. Se compensa
con dos guardas explícitas:

- un `require` al construir la tabla que falla al arrancar si falta alguna
  capacidad o si hay duplicados, y
- un test que verifica la invariante D4 (toda capacidad con condición es
  `CONDITIONAL` en todo rol donde no está `DENIED`).

**Híbrido final:** la tabla es la fuente de verdad; `CourseRole.can(...)`
queda como **fachada** sobre la tabla, no como un `when` propio:

```kotlin
fun can(capability: Capability): Boolean =
    PermissionMatrix.verdict(this, capability) != Verdict.DENIED
```

`CourseRole.canDesign` y `canConfigureModel` quedan obsoletos y se
re-declaran en términos de capacidades (se marcan `@Deprecated`):

```kotlin
@Deprecated("Usar Capability.COURSE_EDIT / MATERIAL_UPLOAD")
val canDesign: Boolean get() = can(Capability.COURSE_EDIT) || can(Capability.MATERIAL_UPLOAD)

@Deprecated("Usar Capability.MODEL_EDIT_POLICY")
val canConfigureModel: Boolean get() = can(Capability.MODEL_EDIT_POLICY)
```

---

## 5. Capacidades condicionales

### 5.1 Modelo

Una condición es una **función pura** sobre un contexto inmutable:

```kotlin
sealed interface Condition {
    /** Texto para la UI: "la cátedra está publicada". */
    val requirement: String
    fun evaluate(ctx: PermissionContext): Boolean
}
```

El contexto lleva todo lo que una condición puede necesitar:

```kotlin
data class PermissionContext(
    val course: Course,
    val actor: Enrollment,
    val app: AppSettings = AppSettings(),
    /** A quién pertenecen los datos consultados (el alumno, en general). */
    val target: Enrollment? = null,
    val facts: CourseFacts = CourseFacts(),
)

data class CourseFacts(
    val materialCount: Int = 0,
    val visibleMaterialCount: Int = 0,
    val enrolledCount: Int = 0,
)
```

### 5.2 Catálogo de condiciones

| Condición | Requisito (texto de UI) | Evalúa |
|---|---|---|
| `CoursePublished` | la cátedra está publicada | `ctx.course.published` |
| `CourseHasMaterials` | la cátedra tiene materiales cargados | `ctx.facts.materialCount > 0` |
| `AnalyticsEnabled` | la analítica está habilitada en la cátedra y en la app | `ctx.course.analyticsEnabled && ctx.app.analyticsEnabled` |
| `TargetAllowsText` | el alumno autorizó ver el texto de sus chats | `ctx.target?.visibility?.showsText == true` |
| `TargetAllowsMetrics` | el alumno autorizó ver las métricas de sus chats | `ctx.target?.visibility?.showsMetrics == true` |
| `TargetIsSelf` | es la propia inscripción | `ctx.target?.id == ctx.actor.id` |
| `TargetIsNotOwner` | el objetivo no es el titular de la cátedra | `ctx.target?.role != CourseRole.TITULAR` |
| `StudentOverrideAllowed` | la cátedra permite que el alumno ajuste la configuración | `ctx.course.modelPolicy.allowStudentOverride` |
| `LocalModelsAllowed` | la cátedra permite usar IA local | `ctx.course.allowLocalModels` |
| `ToolsEnabledInCourse` | las herramientas están habilitadas en la cátedra | `ctx.course.modelPolicy.toolsEnabled` |
| `ToolCategoryAllowed(cat)` | el docente habilitó la categoría `<cat>` | `cat in ctx.course.toolPolicy.enabledCategories` |
| `McpAllowedByApp` | la app permite servidores MCP | `ctx.app.mcpEnabled` |
| `AppNotManaged` | la instalación no está gestionada por la institución | `!ctx.app.managed` |
| `AuditLogEnabled` | el registro de accesos está habilitado | `ctx.app.auditLogEnabled` |

Combinadores: `AllOf(parts)`, `AnyOf(parts)`, `Not(part)`, con azúcar
sintáctico `allOf(a, b)` / `anyOf(a, b)`.

### 5.3 Invariantes (verificables con tests)

1. **I1 — cobertura total:** `PermissionMatrix.ROWS` contiene exactamente
   `Capability.entries`, sin duplicados. Si falta una capacidad, la app
   **no arranca** (`require` en el `init` del objeto).
2. **I2 — condicionalidad intrínseca:** si `capability.condition != null`,
   ningún rol puede tenerla como `ALLOWED`: debe ser `CONDITIONAL` o
   `DENIED`. (Decisión D4.)
3. **I3 — coherencia de ámbito:** toda capacidad `APP` tiene el mismo
   veredicto en los tres roles.
4. **I4 — condiciones puras:** `evaluate` no lee disco, red ni reloj.
5. **I5 — sin condiciones huérfanas:** toda capacidad `CONDITIONAL` en la
   matriz tiene `condition != null`.

### 5.4 Casos concretos

| Escenario | Resultado |
|---|---|
| Alumno con IA local y `visibility = METRICS_ONLY`; el titular abre su panel | `CHAT_VIEW_OTHER` → denegado: *"Requiere: el alumno autorizó ver el texto de sus chats"*. `ANALYTICS_VIEW_PER_STUDENT` → permitido (muestra métricas). `ANALYTICS_VIEW_ANSWER_TEXT` → denegado. |
| Alumno con `visibility = FULL` | El titular puede ver el texto, y cada lectura queda en el `AccessLog`. |
| Alumno con `visibility = NONE` | No entra en ningún agregado: `AnalyticsAnalyzer` ya lo filtra por `showsMetrics`; además `ANALYTICS_VIEW_PER_STUDENT` queda denegado por condición. |
| Cátedra en borrador, el ayudante intenta subir un PDF | `MATERIAL_UPLOAD` → denegado: *"Requiere: la cátedra está publicada"*. |
| Cátedra publicada, `analyticsEnabled = false` | `ANALYTICS_VIEW_COURSE` → denegado: *"Requiere: la analítica está habilitada en la cátedra y en la app"*. |
| Alumno en cátedra con `allowLocalModels = false` | `MODEL_USE_LOCAL` → denegado; el chat sigue funcionando con el modelo de la cátedra. |
| Cátedra con `allowStudentOverride = false` | `MODEL_OVERRIDE` → denegado para todos. La configuración efectiva es exactamente la de la cátedra. |
| Alumno intenta leer el chat de otro alumno | `CHAT_VIEW_OTHER` → denegado por rol (no tiene la capacidad en la matriz). |

---

## 6. Permisos de las herramientas del modelo

### 6.1 El problema

`rag.agent` ya tiene una puerta de permisos **por chat**:
`ToolPermissions.authorize` pide confirmación si la categoría no está en
`ToolContext.grantedCategories`, y `ToolCategory.NONE` nunca pregunta. Eso
resuelve el consent del **usuario**, pero no el control que el docente quiere
ejercer sobre una cátedra ("en esta cátedra no hay web").

### 6.2 Respuesta: dos niveles, el techo manda

| Nivel | Quién lo fija | Dónde vive | Efecto |
|---|---|---|---|
| **Techo (ceiling)** | El docente (por cátedra) y la app (global) | `Course.toolPolicy` + `AppSettings` | Define qué categorías **existen** en el chat. Fuera del techo, la herramienta no se ofrece al modelo y nunca se ejecuta. |
| **Concesión (grant)** | El usuario, por chat | `ChatThread` / estado del chat (`grantedCategories`) | Decide qué categorías se ejecutan **sin preguntar**. Sólo puede conceder dentro del techo. |

**Techo efectivo:**

```
ceiling = (course.toolPolicy.enabledCategories ∩ app.allowedToolCategories)
          ∩ (si !course.modelPolicy.toolsEnabled → ∅)
          ∩ (si el actor es ALUMNO y !course.toolPolicy.allowStudentGrant → ∅)
```

`CourseToolPolicy.mcpServers` no actúa sobre las categorías sino sobre los
**servidores**: si la lista no está vacía, un servidor MCP fuera de ella no
aporta ninguna herramienta al chat, aunque la categoría `MCP` esté en el
techo (`ToolPolicyResolver.mcpAllowed`).

**Combinación con la concesión del alumno:**

| Categoría | En el techo | Concedida por el alumno | Resultado |
|---|---|---|---|
| `NONE` | — | — | Se ejecuta siempre (sin efecto externo). |
| `FILES` | no | — | **DENY sin preguntar** (la UI muestra "el docente desactivó los archivos"). |
| `FILES` | sí | no | Pregunta; `ALLOW_ALWAYS` la guarda como concesión del chat. |
| `FILES` | sí | sí | Se ejecuta sin preguntar. |
| `WEB` | sí, y después el docente apaga `WEB` | sí | **DENY sin preguntar**: la concesión previa queda invalidada por el techo más estrecho. |

**Regla de oro:** *el techo siempre gana*. Una concesión del alumno es un
permiso **dentro** del techo; nunca lo expande.

### 6.3 Dónde vive la política de la cátedra

```kotlin
@Serializable
data class CourseToolPolicy(
    /** Categorías habilitadas por el docente. */
    val enabledCategories: Set<ToolCategory> = emptySet(),
    /** Si el alumno puede conceder permisos por chat (dentro del techo). */
    val allowStudentGrant: Boolean = true,
    /** Allowlist de servidores MCP por nombre; vacía = todos. */
    val mcpServers: Set<String> = emptySet(),
)
```

Y en `Course`: `val toolPolicy: CourseToolPolicy = CourseToolPolicy()`.

### 6.4 Cambios en `:backend`

**`ToolCategory` se mueve a `:core`** (lo necesita `CourseToolPolicy`, y
`:core` no puede depender de `:backend`). En `rag.agent` queda un alias para
no tocar nada más:

```kotlin
// rag/agent/Tool.kt
typealias ToolCategory = kitoru.domain.ToolCategory
```

**`ToolContext` gana el techo** (fail-closed por defecto):

```kotlin
data class ToolContext(
    val chatId: String,
    val outputsDir: FilePath,
    /** Techo: categorías que la cátedra y la app permiten. Vacío = ninguna. */
    val allowedCategories: Set<ToolCategory> = emptySet(),
    /** Categorías ya concedidas en este chat (se ejecutan sin preguntar). */
    val grantedCategories: Set<ToolCategory> = emptySet(),
    val requestPermission: suspend (PermissionRequest) -> PermissionDecision = { PermissionDecision.DENY },
)
```

**`ToolPermissions.authorize` cambia de forma mínima**:

```kotlin
suspend fun authorize(tool: Tool, argsJson: String, ctx: ToolContext): PermissionDecision {
    val category = tool.category
    if (category == ToolCategory.NONE) return PermissionDecision.ALLOW_ONCE
    // Techo: la cátedra o la app lo apagaron. No se pregunta: se denies.
    if (category !in ctx.allowedCategories) return PermissionDecision.DENY
    if (category in ctx.grantedCategories) return PermissionDecision.ALLOW_ONCE
    val request = PermissionRequest(
        chatId = ctx.chatId,
        tool = tool.spec.name,
        category = category,
        summary = tool.describeCall(parseArgs(argsJson)),
        argsJson = argsJson,
    )
    return ctx.requestPermission(request)
}
```

**El catálogo que ve el modelo también se filtra** (para no ofrecerle al
modelo herramientas que no va a poder usar y para no filtrar existencia de
herramientas):

```kotlin
val visible = registry.all().filter { it.category == ToolCategory.NONE || it.category in ceiling }
val specs = visible.map { it.spec }
```

**Impacto en el test portado** (`ToolPermissionsTest`): el `ctx` de prueba
tiene que declarar el techo explícitamente
(`allowedCategories = ToolCategory.entries.toSet()`), porque ahora el valor
por defecto es *cerrado*. Es una adaptación mínima y documentada del test
portado de FunshiEngineAI.

### 6.5 Quién controla qué (resumen)

| Decisión | Docente | Alumno | App |
|---|:---:|:---:|:---:|
| ¿Existe la categoría en la cátedra? | **sí** (`MODEL_TOOLS_*`) | no | sí (techo global) |
| ¿Esta herramienta se ejecuta sin preguntar? | no | **sí** (concesión por chat) | no |
| ¿Se puede pedir confirmación en el chat? | sí | sí | — |
| ¿El modelo puede ver la herramienta en el catálogo? | derivado del techo | derivado del techo | derivado del techo |

> Respuesta a la pregunta del enunciado: **los controlan ambos, con
> jerarquía**. El docente pone el techo (y puede apagarlo por cátedra: nada
> de web en una cátedra de derecho); el alumno concede por chat **dentro**
> del techo. Si el docente apaga `WEB` después de que un alumno lo haya
> concedido "siempre", la concesión queda invalidada.

---

## 7. Permisos efectivos

### 7.1 Firma

```kotlin
object PermissionResolver {
    fun resolve(
        actor: Enrollment,                  // quién pide (rol + visibilidad propios)
        course: Course,                     // la cátedra
        app: AppSettings = AppSettings(),   // estado global de la app
        target: Enrollment? = null,         // de quién son los datos consultados
        facts: CourseFacts = CourseFacts(), // hechos derivados (nº de materiales, etc.)
    ): EffectivePermissions
}
```

Y el resultado:

```kotlin
data class EffectivePermissions(
    val granted: Set<Capability>,
    val denied: Map<Capability, DenialReason>,
    /** Capacidades cuyo veredicto base es condicional (pueden estar en granted o denied). */
    val conditional: Map<Capability, Condition>,
) {
    fun allows(capability: Capability): Boolean = capability in granted
    fun isDenied(capability: Capability): Boolean = capability in denied
    fun reason(capability: Capability): String? = denied[capability]?.message
    fun require(capability: Capability) { /* lanza IllegalStateException en español */ }
}

data class DenialReason(val message: String, val condition: Condition? = null)
```

### 7.2 Algoritmo

```
1. ctx ← PermissionContext(course, actor, app, target, facts)

2. Para cada cap en Capability.entries:
     base ← PermissionMatrix.verdict(actor.role, cap)

     según base:
       DENIED     → denied[cap]  ← "El rol {rol} no tiene este permiso en esta cátedra."
       ALLOWED    → granted += cap
       CONDITIONAL→
           cond ← cap.condition            (garantizado por la invariante I5)
           conditional[cap] ← cond
           si cond.evaluate(ctx):
               granted += cap
           si no:
               denied[cap] ← DenialReason("Requiere: {cond.requirement}.", cond)

3. EffectivePermissions(granted, denied, conditional)
```

Notas:

- Las capacidades de ámbito **APP** no dependen del rol: su veredicto base
  sale de la misma matriz (idéntico en los tres roles, invariante I3) y sus
  condiciones sí leen `AppSettings`.
- Los **filtros de recurso** (materiales no visibles, hilos de otro alumno)
  no se resuelven acá: se aplican en el punto de acceso al dato, después de
  que el permiso de la operación haya pasado.
- El reloj no aparece: `resolve()` es puro. El `at` del log de auditoría lo
  pone el servicio que escribe, no el resolver.

### 7.3 Uso

```kotlin
val perms = PermissionResolver.resolve(docenteEnrollment, course, app, target = alumnoEnrollment)

if (perms.allows(Capability.CHAT_VIEW_OTHER)) {
    val texto = threadService.readText(threadId)      // punto de acceso: auditar aquí
    audit.record(docenteEnrollment, course, Capability.CHAT_VIEW_OTHER, alumnoEnrollment, "hilo $threadId")
} else {
    ui.show(perms.reason(Capability.CHAT_VIEW_OTHER)!!)
}
```

Helper de conveniencia para el caso frecuente:

```kotlin
fun PermissionResolver.canSeeStudentChats(
    actor: Enrollment, course: Course, student: Enrollment, app: AppSettings = AppSettings(),
): Boolean = resolve(actor, course, app, target = student).allows(Capability.CHAT_VIEW_OTHER)
```

---

## 8. Auditoría: `AccessLog`

### 8.1 Motivación

El docente puede leer los chats de los alumnos. Para que el sistema sea
confiable hace falta que **quede constancia**: quién abrió qué chats de quién
y cuándo. Sirve para tres cosas:

1. **Transparencia hacia el alumno**: el docente puede exportar el registro y
   compartirlo; el alumno puede exigirlo.
2. **Transparencia interna**: el titular ve si un ayudante está leyendo
   chats.
3. **Auditoría**: `AccessLog` también registra los intentos **denegados**
   (útil para detectar un bug de permisos o un abuso).

### 8.2 Modelo (mínimo)

```kotlin
@Serializable
enum class AccessOutcome(val label: String) {
    ALLOWED("Permitido"),
    DENIED("Denegado"),
}

/**
 * Una entrada del registro de accesos: quién, qué, cuándo y a quién.
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

Eso es todo: **quién, qué, cuándo** (+ a quién y con qué resultado, que es lo
mínimo para que la entrada sirva).

### 8.3 Qué se audita

Sólo las capacidades marcadas `audited = true` en el enum:

`CHAT_VIEW_OTHER`, `CHAT_VIEW_TOOL_CALLS`, `CHAT_VIEW_OTHER_METRICS`,
`ANALYTICS_VIEW_PER_STUDENT`, `ANALYTICS_VIEW_ANSWER_TEXT`,
`ANALYTICS_EXPORT`, `CHAT_DELETE_ANY`, `ENROLLMENT_SET_VISIBILITY`,
`ENROLLMENT_SET_OWN_VISIBILITY`, `APP_AUDIT_LOG_VIEW`, `APP_AUDIT_LOG_EXPORT`.

No se auditan las lecturas de lo propio (`CHAT_VIEW_OWN`) ni las acciones de
diseño: el ruido tapa el signal.

### 8.4 Persistencia

```kotlin
interface AccessLogRepository {
    suspend fun list(courseId: String? = null): List<AccessLogEntry>
    suspend fun append(entry: AccessLogEntry)
    suspend fun clear()
}

/**
 * Log de accesos sobre `config/access-log.json`.
 *
 * Es append-only con tope: si se supera [MAX_ENTRIES] se descartan las
 * entradas más viejas. `JsonListStore` ya serializa las escrituras con un
 * Mutex, así que dos corrutinas no pisan el archivo.
 */
class FileAccessLogRepository(fs: FileSystem) : AccessLogRepository {

    private val store = JsonListStore(
        fs = fs,
        path = KitoruPaths.configDir.resolve("access-log.json"),
        itemSerializer = AccessLogEntry.serializer(),
    )

    override suspend fun list(courseId: String?): List<AccessLogEntry> =
        store.read()
            .filter { courseId == null || it.courseId == courseId }
            .sortedByDescending { it.at }

    override suspend fun append(entry: AccessLogEntry) {
        store.update { list ->
            val updated = list + entry
            if (updated.size > MAX_ENTRIES) updated.takeLast(MAX_ENTRIES) else updated
        }
    }

    override suspend fun clear() {
        store.write(emptyList())
    }

    companion object {
        /** Tope de entradas: con ~10 accesos por día alcanza para años. */
        const val MAX_ENTRIES = 5000
    }
}
```

El log es **local a la instalación** (el archivo del docente). En este
milestone no viaja a Kitoru-Server.

### 8.5 Servicio de auditoría

```kotlin
class PermissionAudit(
    private val repo: AccessLogRepository,
    private val clock: () -> Long = { kitoru.platform.nowMillis() },
    private val newId: () -> String = { newId() },
) {
    /** Registra el acceso si la capacidad es auditable; si no, no hace nada. */
    suspend fun record(
        actor: Enrollment,
        course: Course,
        capability: Capability,
        target: Enrollment? = null,
        detail: String = "",
        outcome: AccessOutcome = AccessOutcome.ALLOWED,
    ) {
        if (!capability.audited) return
        repo.append(
            AccessLogEntry(
                id = newId(),
                at = clock(),
                actorId = actor.userId,
                actorName = actor.displayName,
                courseId = course.id,
                capability = capability,
                targetUserId = target?.userId.orEmpty(),
                targetUserName = target?.displayName.orEmpty(),
                detail = detail,
                outcome = outcome,
            )
        )
    }
}
```

El punto de llamada es **el servicio que lee el dato**, no la UI:

```kotlin
suspend fun readThreadText(actor: Enrollment, threadId: String): String {
    val perms = PermissionResolver.resolve(actor, course, app, target = ownerEnrollment)
    if (!perms.allows(Capability.CHAT_VIEW_OTHER)) {
        audit.record(actor, course, Capability.CHAT_VIEW_OTHER, ownerEnrollment, "hilo $threadId", AccessOutcome.DENIED)
        throw IllegalStateException(perms.reason(Capability.CHAT_VIEW_OTHER)!!)
    }
    audit.record(actor, course, Capability.CHAT_VIEW_OTHER, ownerEnrollment, "hilo $threadId")
    return messages.map { it.text }.joinToString("\n")
}
```

### 8.6 Qué muestra la UI

Pantalla "Accesos" dentro del panel de la cátedra:

- Últimas 100 entradas: quién, qué capacidad, sobre quién, cuándo, resultado.
- Resumen por alumno: "este alumno fue leído N veces por M docente(s)".
- Botón "Exportar CSV" (usa `APP_AUDIT_LOG_EXPORT`).

El docente puede compartir ese export con el alumno: ése es el mecanismo de
transparencia en el milestone 1.

**Fuera de alcance (anotado, no implementado):** cadena de hashes
(`prevHash`) para detectar manipulación del log local, y envío del log a
Kitoru-Server. Se dejan anotados en el doc a propósito.

---

## 9. Cambios de dominio requeridos

| Cambio | Dónde | Motivo |
|---|---|---|
| `Course.published: Boolean = false` (+ `publishedAt: Long = 0L`) | `kitoru/domain/Course.kt` | La condición `CoursePublished` necesita saber si la cátedra está publicada. |
| `Course.toolPolicy: CourseToolPolicy = CourseToolPolicy()` | `kitoru/domain/Course.kt` | El techo de herramientas por cátedra. |
| `Enrollment.visibilityCeiling: ChatVisibility = ChatVisibility.FULL` | `kitoru/domain/Course.kt` | Separa lo que el **alumno autoriza** (techo) de lo que el docente ve (efectivo). El docente nunca puede subir por encima del techo. |
| `ToolCategory` (mover) + `typealias` en `rag.agent` | `kitoru/domain/ToolPolicy.kt` | `:core` no puede depender de `:backend`; la política de la cátedra vive en `:core`. |
| `AppSettings` (nuevo) | `kitoru/domain/AppSettings.kt` | Estado global de la app (ámbito APP). |
| `Capability`, `Verdict`, `Condition`, `PermissionMatrix`, `EffectivePermissions`, `PermissionResolver`, `AccessLog*` (nuevos) | `kitoru/domain/` | Este documento. |
| `ToolContext.allowedCategories` + `ToolPermissions.authorize` (modificados) | `backend/.../rag/agent/` | Techo fail-closed. |

### 9.1 `AppSettings` (mínimo)

```kotlin
@Serializable
data class AppSettings(
    /** Interruptor global de analítica. */
    val analyticsEnabled: Boolean = true,
    /** Registrar los accesos a datos de alumnos. */
    val auditLogEnabled: Boolean = true,
    /** La app admite servidores MCP. */
    val mcpEnabled: Boolean = false,
    /** Techo global de categorías de herramientas. */
    val allowedToolCategories: Set<ToolCategory> = setOf(ToolCategory.NONE),
    /** Instalación gestionada por la institución: los ajustes sensibles se bloquean. */
    val managed: Boolean = false,
)
```

---

## 10. Plan de tests

| Test | Qué cubre |
|---|---|
| `PermissionMatrixTest` | I1 (cobertura total, sin duplicados), I2 (condicionalidad intrínseca), I3 (ámbito APP coherente), I5 (sin condiciones huérfanas). Falla si se agrega una capacidad sin fila. |
| `PermissionMatrixMarkdownTest` | El markdown de §3 coincide con `PermissionMatrix.toMarkdown()`: mismas capacidades, en el mismo orden y con los mismos tres veredictos. El texto de la columna "Condición" es libre (redacción de UI), así que el test compara los veredictos, no la prosa. |
| `PermissionResolverTest` | Los casos de §5.4: visibilidad `FULL`/`METRICS_ONLY`/`NONE`, cátedra en borrador, `analyticsEnabled = false`, `allowLocalModels = false`, `allowStudentOverride = false`, alumno leyendo a otro alumno. |
| `ToolPolicyTest` | `ceiling = curso ∩ app ∩ toolsEnabled`; la concesión del alumno nunca expande el techo; `mcpServers` filtra. |
| `ToolPermissionsTest` (adaptado) | Categoría fuera del techo → `DENY` **sin preguntar**; dentro del techo y no concedida → pregunta; `NONE` → nunca pregunta. |
| `AccessLogTest` | Se registra sólo lo auditable; se registran también los denegados; el tope de entradas descarta las más viejas; el `list(courseId)` filtra. |

Todo en `commonTest` salvo el `ToolPermissionsTest`, que ya vive en
`desktopTest` con JUnit 5.

---

## 11. Archivos

```
core/src/commonMain/kotlin/kitoru/domain/
├── Capability.kt          # Capability, CapabilityArea, CapabilityScope, Verdict
├── Conditions.kt          # Condition + catálogo + combinadores + PermissionContext, CourseFacts
├── PermissionMatrix.kt    # PermissionRow, PermissionMatrix (+ toMarkdown)
├── Permissions.kt         # EffectivePermissions, DenialReason, PermissionResolver
├── AppSettings.kt         # AppSettings
├── ToolPolicy.kt          # ToolCategory (movido), CourseToolPolicy, ToolPolicyResolver
├── AccessLog.kt           # AccessLogEntry, AccessOutcome, AccessLogRepository, FileAccessLogRepository, PermissionAudit
└── Roles.kt               # CourseRole (can/fachada), ChatVisibility (sin cambios)

backend/src/commonMain/kotlin/rag/agent/
├── Tool.kt                # typealias ToolCategory; ToolContext.allowedCategories
└── ToolPermissions.kt     # authorize con el techo
```

---

## Anexo A · Código Kotlin final

### A.1 `kitoru/domain/Capability.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** Veredicto de la matriz rol × capacidad. */
enum class Verdict(val label: String) {
    /** Permitido sin más. */
    ALLOWED("Permitido"),

    /** Permitido sólo si se cumple la condición intrínseca de la capacidad. */
    CONDITIONAL("Condicional"),

    /** Denegado: el rol no tiene la capacidad en esta cátedra. */
    DENIED("Denegado"),
}

/** Áreas de la app a las que pertenece una capacidad. */
enum class CapabilityArea(val label: String) {
    CATEDRA("Cátedra"),
    MATERIALES("Materiales"),
    MODELO("Modelo"),
    CHATS("Chats"),
    ANALITICA("Analítica"),
    ENROLMENTS("Inscripciones"),
    APP("Configuración de la app"),
}

/** Ámbito al que pertenece una capacidad. */
enum class CapabilityScope(val label: String) {
    /** Se resuelve con el rol dentro de una cátedra. */
    COURSE("Cátedra"),

    /** Se resuelve con el estado global de la app; no depende del rol. */
    APP("App"),
}

/**
 * Toda acción que la app puede autorizar.
 *
 * - [area]: a qué sección de la app pertenece (para armar la UI y el log).
 * - [label]: texto legible para la UI y para los mensajes de error.
 * - [scope]: si la capacidad depende del rol de cátedra o del estado global.
 * - [condition]: condición intrínseca. Si existe, la capacidad es
 *   `CONDITIONAL` en todo rol donde no esté `DENIED` (invariante I2).
 * - [audited]: si el ejercicio de la capacidad queda en el `AccessLog`.
 */
@Serializable
enum class Capability(
    val area: CapabilityArea,
    val label: String,
    val scope: CapabilityScope = CapabilityScope.COURSE,
    val condition: Condition? = null,
    val audited: Boolean = false,
) {
    // ---------------- Cátedra ----------------
    COURSE_VIEW(CapabilityArea.CATEDRA, "Ver la cátedra"),
    COURSE_EDIT(CapabilityArea.CATEDRA, "Editar los datos de la cátedra"),
    COURSE_PUBLISH(CapabilityArea.CATEDRA, "Publicar la cátedra"),
    COURSE_UNPUBLISH(CapabilityArea.CATEDRA, "Volver a borrador"),
    COURSE_ARCHIVE(CapabilityArea.CATEDRA, "Archivar la cátedra"),
    COURSE_DELETE(CapabilityArea.CATEDRA, "Eliminar la cátedra"),
    COURSE_VIEW_JOIN_CODE(CapabilityArea.CATEDRA, "Ver el código de inscripción"),
    COURSE_ROTATE_JOIN_CODE(CapabilityArea.CATEDRA, "Regenerar el código de inscripción"),
    COURSE_CONFIG_RAG(
        CapabilityArea.CATEDRA, "Ajustar la recuperación",
        condition = CoursePublished,
    ),
    COURSE_VIEW_SETTINGS(CapabilityArea.CATEDRA, "Ver la configuración de la cátedra"),
    COURSE_EXPORT(CapabilityArea.CATEDRA, "Exportar la cátedra"),

    // ---------------- Materiales ----------------
    MATERIAL_VIEW(CapabilityArea.MATERIALES, "Ver los materiales"),
    MATERIAL_VIEW_CONTENT(CapabilityArea.MATERIALES, "Leer el contenido de un material"),
    MATERIAL_UPLOAD(
        CapabilityArea.MATERIALES, "Subir materiales",
        condition = CoursePublished,
    ),
    MATERIAL_EDIT(
        CapabilityArea.MATERIALES, "Editar los metadatos de un material",
        condition = CoursePublished,
    ),
    MATERIAL_DELETE(CapabilityArea.MATERIALES, "Eliminar materiales"),
    MATERIAL_REINDEX(
        CapabilityArea.MATERIALES, "Reindexar los materiales",
        condition = CoursePublished,
    ),
    MATERIAL_VIEW_HIDDEN(CapabilityArea.MATERIALES, "Ver los materiales ocultos a los alumnos"),

    // ---------------- Modelo ----------------
    MODEL_USE(CapabilityArea.MODELO, "Usar el modelo"),
    MODEL_VIEW_POLICY(CapabilityArea.MODELO, "Ver la configuración del modelo"),
    MODEL_EDIT_POLICY(CapabilityArea.MODELO, "Editar la configuración base del modelo"),
    MODEL_SET_MODE(CapabilityArea.MODELO, "Cambiar el modo del modelo"),
    MODEL_LOCK_FIELDS(CapabilityArea.MODELO, "Bloquear campos de la configuración"),
    MODEL_SET_ALLOW_OVERRIDE(CapabilityArea.MODELO, "Permitir el ajuste del alumno"),
    MODEL_EDIT_SYSTEM_PROMPT(CapabilityArea.MODELO, "Editar las instrucciones del sistema"),
    MODEL_OVERRIDE(
        CapabilityArea.MODELO, "Ajustar la configuración para sí mismo",
        condition = StudentOverrideAllowed,
    ),
    MODEL_USE_LOCAL(
        CapabilityArea.MODELO, "Usar IA local en el dispositivo",
        condition = LocalModelsAllowed,
    ),

    // ---------------- Herramientas del modelo ----------------
    MODEL_TOOLS_ENABLE(CapabilityArea.MODELO, "Habilitar herramientas en la cátedra"),
    MODEL_TOOLS_FILES(
        CapabilityArea.MODELO, "Permitir herramientas de archivos",
        condition = allOf(ToolsEnabledInCourse, ToolCategoryAllowed(ToolCategory.FILES)),
    ),
    MODEL_TOOLS_WEB(
        CapabilityArea.MODELO, "Permitir herramientas web",
        condition = allOf(ToolsEnabledInCourse, ToolCategoryAllowed(ToolCategory.WEB)),
    ),
    MODEL_TOOLS_MCP(
        CapabilityArea.MODELO, "Permitir herramientas MCP",
        condition = allOf(
            ToolsEnabledInCourse,
            ToolCategoryAllowed(ToolCategory.MCP),
            McpAllowedByApp,
            AppNotManaged,
        ),
    ),
    MODEL_TOOLS_GRANT(
        CapabilityArea.MODELO, "Conceder permisos de herramientas por chat",
        condition = ToolsEnabledInCourse,
    ),

    // ---------------- Chats ----------------
    CHAT_CREATE(CapabilityArea.CHATS, "Crear conversaciones"),
    CHAT_SEND(CapabilityArea.CHATS, "Enviar mensajes"),
    CHAT_VIEW_OWN(CapabilityArea.CHATS, "Ver las conversaciones propias"),
    CHAT_VIEW_OTHER(
        CapabilityArea.CHATS, "Ver el texto de las conversaciones de otro participante",
        condition = TargetAllowsText, audited = true,
    ),
    CHAT_VIEW_OTHER_METRICS(
        CapabilityArea.CHATS, "Ver las métricas de otro participante",
        condition = TargetAllowsMetrics, audited = true,
    ),
    CHAT_VIEW_TOOL_CALLS(
        CapabilityArea.CHATS, "Ver las herramientas invocadas en conversaciones ajenas",
        condition = TargetAllowsText, audited = true,
    ),
    CHAT_DELETE_OWN(CapabilityArea.CHATS, "Borrar las conversaciones propias"),
    CHAT_DELETE_ANY(
        CapabilityArea.CHATS, "Borrar las conversaciones de cualquier participante",
        audited = true,
    ),
    CHAT_RENAME_TAG(CapabilityArea.CHATS, "Renombrar y etiquetar conversaciones"),

    // ---------------- Analítica ----------------
    ANALYTICS_VIEW_COURSE(
        CapabilityArea.ANALITICA, "Ver la analítica de la cátedra",
        condition = AnalyticsEnabled,
    ),
    ANALYTICS_VIEW_PER_STUDENT(
        CapabilityArea.ANALITICA, "Ver la analítica por alumno",
        condition = allOf(AnalyticsEnabled, TargetAllowsMetrics), audited = true,
    ),
    ANALYTICS_VIEW_ANSWER_TEXT(
        CapabilityArea.ANALITICA, "Ver el texto de las respuestas de un alumno",
        condition = allOf(AnalyticsEnabled, TargetAllowsText), audited = true,
    ),
    ANALYTICS_VIEW_SOURCES(
        CapabilityArea.ANALITICA, "Ver el uso de fuentes",
        condition = AnalyticsEnabled,
    ),
    ANALYTICS_EXPORT(
        CapabilityArea.ANALITICA, "Exportar la analítica",
        condition = AnalyticsEnabled, audited = true,
    ),
    ANALYTICS_VIEW_OWN(CapabilityArea.ANALITICA, "Ver la propia actividad"),

    // ---------------- Inscripciones ----------------
    ENROLLMENT_VIEW_ALL(CapabilityArea.ENROLMENTS, "Ver todos los participantes"),
    ENROLLMENT_VIEW_OWN(
        CapabilityArea.ENROLMENTS, "Ver la propia inscripción",
        condition = TargetIsSelf,
    ),
    ENROLLMENT_INVITE(CapabilityArea.ENROLMENTS, "Inscribir a alguien"),
    ENROLLMENT_REMOVE(
        CapabilityArea.ENROLMENTS, "Quitar a un participante",
        condition = TargetIsNotOwner,
    ),
    ENROLLMENT_CHANGE_ROLE(CapabilityArea.ENROLMENTS, "Cambiar el rol de un participante"),
    ENROLLMENT_SET_VISIBILITY(
        CapabilityArea.ENROLMENTS, "Restringir la visibilidad de un alumno",
        audited = true,
    ),
    ENROLLMENT_SET_OWN_VISIBILITY(
        CapabilityArea.ENROLMENTS, "Fijar la propia visibilidad",
        condition = TargetIsSelf, audited = true,
    ),
    ENROLLMENT_SELF_LEAVE(CapabilityArea.ENROLMENTS, "Darse de baja de la cátedra"),

    // ---------------- Configuración de la app ----------------
    APP_SETTINGS_VIEW(
        CapabilityArea.APP, "Ver los ajustes de la app",
        scope = CapabilityScope.APP,
    ),
    APP_SETTINGS_EDIT(
        CapabilityArea.APP, "Editar los ajustes de la app",
        scope = CapabilityScope.APP,
    ),
    APP_LOCAL_MODEL_MANAGE(
        CapabilityArea.APP, "Administrar los modelos locales",
        scope = CapabilityScope.APP, condition = AppNotManaged,
    ),
    APP_MCP_MANAGE(
        CapabilityArea.APP, "Administrar los servidores MCP",
        scope = CapabilityScope.APP, condition = allOf(McpAllowedByApp, AppNotManaged),
    ),
    APP_TOOLS_POLICY(
        CapabilityArea.APP, "Editar la política global de herramientas",
        scope = CapabilityScope.APP, condition = AppNotManaged,
    ),
    APP_AUDIT_LOG_VIEW(
        CapabilityArea.APP, "Ver el registro de accesos",
        scope = CapabilityScope.APP, condition = AuditLogEnabled, audited = true,
    ),
    APP_AUDIT_LOG_EXPORT(
        CapabilityArea.APP, "Exportar el registro de accesos",
        scope = CapabilityScope.APP, condition = AuditLogEnabled, audited = true,
    ),
    APP_DATA_EXPORT(
        CapabilityArea.APP, "Exportar los datos locales",
        scope = CapabilityScope.APP,
    ),
    APP_RESET(
        CapabilityArea.APP, "Borrar los datos locales",
        scope = CapabilityScope.APP,
    ),
    ;

    companion object {
        /** Convierte el nombre persistido ("COURSE_EDIT") al enum; null si no existe. */
        fun fromName(name: String): Capability? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### A.2 `kitoru/domain/Conditions.kt`

```kotlin
package kitoru.domain

/**
 * Condición de habilitación de una capacidad.
 *
 * Es una **función pura** sobre un contexto inmutable: mismo contexto,
 * mismo resultado. Sin disco, sin red, sin reloj → testeable al 100%.
 */
sealed interface Condition {
    /** Texto para la UI: "la cátedra está publicada". */
    val requirement: String

    /** ¿Se cumple la condición? */
    fun evaluate(ctx: PermissionContext): Boolean
}

/** Contexto inmutable con todo lo que una condición puede necesitar. */
data class PermissionContext(
    val course: Course,
    val actor: Enrollment,
    val app: AppSettings = AppSettings(),
    /** A quién pertenecen los datos consultados (el alumno, en general). */
    val target: Enrollment? = null,
    val facts: CourseFacts = CourseFacts(),
)

/** Hechos derivados de la cátedra que no viven en [Course]. */
data class CourseFacts(
    val materialCount: Int = 0,
    val visibleMaterialCount: Int = 0,
    val enrolledCount: Int = 0,
)

// ---------------- Condiciones simples ----------------

data object CoursePublished : Condition {
    override val requirement = "la cátedra está publicada"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.course.published
}

data object CourseHasMaterials : Condition {
    override val requirement = "la cátedra tiene materiales cargados"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.facts.materialCount > 0
}

data object AnalyticsEnabled : Condition {
    override val requirement = "la analítica está habilitada en la cátedra y en la app"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.course.analyticsEnabled && ctx.app.analyticsEnabled
}

data object TargetAllowsText : Condition {
    override val requirement = "el alumno autorizó ver el texto de sus chats"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.target?.visibility?.showsText == true
}

data object TargetAllowsMetrics : Condition {
    override val requirement = "el alumno autorizó ver las métricas de sus chats"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.target?.visibility?.showsMetrics == true
}

data object TargetIsSelf : Condition {
    override val requirement = "es la propia inscripción"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.target?.id == ctx.actor.id
}

data object TargetIsNotOwner : Condition {
    override val requirement = "el objetivo no es el titular de la cátedra"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.target?.role != CourseRole.TITULAR
}

data object StudentOverrideAllowed : Condition {
    override val requirement = "la cátedra permite que el alumno ajuste la configuración"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.course.modelPolicy.allowStudentOverride
}

data object LocalModelsAllowed : Condition {
    override val requirement = "la cátedra permite usar IA local"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.course.allowLocalModels
}

data object ToolsEnabledInCourse : Condition {
    override val requirement = "las herramientas están habilitadas en la cátedra"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.course.modelPolicy.toolsEnabled
}

/** El docente habilitó esa categoría de herramientas en la cátedra. */
data class ToolCategoryAllowed(val category: ToolCategory) : Condition {
    override val requirement = "el docente habilitó la categoría \"${category.label}\""
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.course.toolPolicy.enabledCategories.contains(category)
}

data object McpAllowedByApp : Condition {
    override val requirement = "la app permite servidores MCP"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.app.mcpEnabled
}

data object AppNotManaged : Condition {
    override val requirement = "la instalación no está gestionada por la institución"
    override fun evaluate(ctx: PermissionContext): Boolean = !ctx.app.managed
}

data object AuditLogEnabled : Condition {
    override val requirement = "el registro de accesos está habilitado"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.app.auditLogEnabled
}

// ---------------- Combinadores ----------------

data class AllOf(val parts: List<Condition>) : Condition {
    override val requirement: String get() = parts.joinToString(" y ") { it.requirement }
    override fun evaluate(ctx: PermissionContext): Boolean = parts.all { it.evaluate(ctx) }
}

data class AnyOf(val parts: List<Condition>) : Condition {
    override val requirement: String get() = parts.joinToString(" o ") { it.requirement }
    override fun evaluate(ctx: PermissionContext): Boolean = parts.any { it.evaluate(ctx) }
}

data class Not(val part: Condition) : Condition {
    override val requirement: String get() = "no ${part.requirement}"
    override fun evaluate(ctx: PermissionContext): Boolean = !part.evaluate(ctx)
}

/** Azúcar sintáctico: `allOf(a, b)` en lugar de `AllOf(listOf(a, b))`. */
fun allOf(vararg parts: Condition): AllOf = AllOf(parts.toList())

/** Azúcar sintáctico: `anyOf(a, b)` en lugar de `AnyOf(listOf(a, b))`. */
fun anyOf(vararg parts: Condition): AnyOf = AnyOf(parts.toList())
```

### A.3 `kitoru/domain/PermissionMatrix.kt`

```kotlin
package kitoru.domain

/**
 * Una fila de la matriz rol × capacidad.
 *
 * Una fila por capacidad: el markdown del documento de diseño se genera a
 * partir de estas filas, así que el doc no puede quedar viejo.
 */
data class PermissionRow(
    val capability: Capability,
    val titular: Verdict,
    val ayudante: Verdict,
    val alumno: Verdict,
) {
    /** Veredicto para un rol. */
    fun verdict(role: CourseRole): Verdict = when (role) {
        CourseRole.TITULAR -> titular
        CourseRole.AYUDANTE -> ayudante
        CourseRole.ALUMNO -> alumno
    }
}

/**
 * La matriz de permisos: la fuente única de verdad.
 *
 * Es **datos**, no lógica: se puede revisar de un vistazo, se puede mostrar en
 * la app y se puede generar el markdown del diseño.
 */
object PermissionMatrix {

    val ROWS: List<PermissionRow> = listOf(
        // ---------------- Cátedra ----------------
        PermissionRow(Capability.COURSE_VIEW, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.COURSE_EDIT, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_PUBLISH, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_UNPUBLISH, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_ARCHIVE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_DELETE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_VIEW_JOIN_CODE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_ROTATE_JOIN_CODE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_CONFIG_RAG, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.COURSE_VIEW_SETTINGS, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_EXPORT, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),

        // ---------------- Materiales ----------------
        PermissionRow(Capability.MATERIAL_VIEW, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MATERIAL_VIEW_CONTENT, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MATERIAL_UPLOAD, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_EDIT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_DELETE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_REINDEX, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_VIEW_HIDDEN, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),

        // ---------------- Modelo ----------------
        PermissionRow(Capability.MODEL_USE, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MODEL_VIEW_POLICY, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MODEL_EDIT_POLICY, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_SET_MODE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_LOCK_FIELDS, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_SET_ALLOW_OVERRIDE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_EDIT_SYSTEM_PROMPT, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_OVERRIDE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_USE_LOCAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),

        // ---------------- Herramientas del modelo ----------------
        PermissionRow(Capability.MODEL_TOOLS_ENABLE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_TOOLS_FILES, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_TOOLS_WEB, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_TOOLS_MCP, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_TOOLS_GRANT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),

        // ---------------- Chats ----------------
        PermissionRow(Capability.CHAT_CREATE, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_SEND, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_VIEW_OWN, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_VIEW_OTHER, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.CHAT_VIEW_OTHER_METRICS, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.CHAT_VIEW_TOOL_CALLS, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.CHAT_DELETE_OWN, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_DELETE_ANY, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.CHAT_RENAME_TAG, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),

        // ---------------- Analítica ----------------
        PermissionRow(Capability.ANALYTICS_VIEW_COURSE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_PER_STUDENT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_ANSWER_TEXT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_SOURCES, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_EXPORT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_OWN, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),

        // ---------------- Inscripciones ----------------
        PermissionRow(Capability.ENROLLMENT_VIEW_ALL, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_VIEW_OWN, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.ENROLLMENT_INVITE, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_REMOVE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_CHANGE_ROLE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_SET_VISIBILITY, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_SET_OWN_VISIBILITY, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.ENROLLMENT_SELF_LEAVE, Verdict.DENIED, Verdict.ALLOWED, Verdict.ALLOWED),

        // ---------------- Configuración de la app ----------------
        PermissionRow(Capability.APP_SETTINGS_VIEW, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.APP_SETTINGS_EDIT, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.APP_LOCAL_MODEL_MANAGE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_MCP_MANAGE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_TOOLS_POLICY, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_AUDIT_LOG_VIEW, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_AUDIT_LOG_EXPORT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_DATA_EXPORT, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.APP_RESET, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
    )

    init {
        // I1: cobertura total, sin duplicados.
        val names = ROWS.map { it.capability }
        require(names.size == names.toSet().size) {
            "La matriz de permisos tiene capacidades repetidas."
        }
        require(names.toSet() == Capability.entries.toSet()) {
            val faltan = Capability.entries.toSet() - names.toSet()
            val sobran = names.toSet() - Capability.entries.toSet()
            "La matriz de permisos no coincide con las capacidades. Faltan: $faltan. Sobran: $sobran."
        }
        // I5: toda capacidad condicional tiene condición.
        for (row in ROWS) {
            val condicional = listOf(row.titular, row.ayudante, row.alumno).any { it == Verdict.CONDITIONAL }
            require(!condicional || row.capability.condition != null) {
                "La capacidad ${row.capability.name} es condicional pero no define condición."
            }
            // I2: la condicionalidad es intrínseca: si hay condición, nadie la tiene como ALLOWED.
            if (row.capability.condition != null) {
                val hayAllowed = listOf(row.titular, row.ayudante, row.alumno).any { it == Verdict.ALLOWED }
                require(!hayAllowed) {
                    "La capacidad ${row.capability.name} tiene condición y no puede ser ALLOWED en ningún rol."
                }
            }
            // I3: las capacidades de la app son iguales en los tres roles.
            if (row.capability.scope == CapabilityScope.APP) {
                require(row.titular == row.ayudante && row.ayudante == row.alumno) {
                    "La capacidad ${row.capability.name} es de ámbito app: su veredicto no puede depender del rol."
                }
            }
        }
    }

    private val index: Map<Capability, PermissionRow> = ROWS.associateBy { it.capability }

    /** Veredicto de un rol para una capacidad. */
    fun verdict(role: CourseRole, capability: Capability): Verdict =
        index.getValue(capability).verdict(role)

    /** Veredicto de un rol para una capacidad (con target, si aplica). */
    fun verdict(role: CourseRole, capability: Capability, ctx: PermissionContext): Verdict {
        val base = verdict(role, capability)
        if (base != Verdict.CONDITIONAL) return base
        val cond = capability.condition ?: return Verdict.DENIED
        return if (cond.evaluate(ctx)) Verdict.ALLOWED else Verdict.DENIED
    }

    /**
     * Markdown de la matriz.
     *
     * La columna "Condición" usa el texto de UI de la condición (libre); el
     * test de sincronización con el documento compara capacidades y
     * veredictos, no esa prosa.
     */
    fun toMarkdown(): String = buildString {
        appendLine("| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |")
        appendLine("|---|---|:---:|:---:|:---:|---|")
        for (row in ROWS) {
            val cap = row.capability
            val simbolo = listOf(row.titular, row.ayudante, row.alumno).joinToString(" | ") { marca(it) }
            val condicion = cap.condition?.requirement ?: "—"
            appendLine("| `${cap.name}` | ${cap.scope.label.lowercase()} | $simbolo | $condicion |")
        }
    }

    private fun marca(v: Verdict): String = when (v) {
        Verdict.ALLOWED -> "✔"
        Verdict.CONDITIONAL -> "◆"
        Verdict.DENIED -> "✖"
    }
}
```

### A.4 `kitoru/domain/Permissions.kt`

```kotlin
package kitoru.domain

/** Por qué se denegó una capacidad. */
data class DenialReason(
    val message: String,
    val condition: Condition? = null,
)

/**
 * El conjunto de capacidades efectivas de un actor en una cátedra.
 *
 * [granted] y [denied] son disjuntos. [conditional] son las capacidades cuyo
 * veredicto base era condicional: sirve para que la UI diga "requiere X",
 * esté concessida o no.
 */
data class EffectivePermissions(
    val granted: Set<Capability>,
    val denied: Map<Capability, DenialReason>,
    val conditional: Map<Capability, Condition>,
) {
    /** ¿La operación está permitida? */
    fun allows(capability: Capability): Boolean = capability in granted

    /** ¿La operación está denegada? */
    fun isDenied(capability: Capability): Boolean = capability in denied

    /** ¿El permiso depende de una condición? */
    fun isConditional(capability: Capability): Boolean = capability in conditional

    /** Motivo del permiso denegado (null si está concedido). */
    fun reason(capability: Capability): String? = denied[capability]?.message

    /** Falla rápido con un mensaje en español si la capacidad no está concedida. */
    fun require(capability: Capability) {
        if (allows(capability)) return
        val motivo = reason(capability) ?: "El rol no tiene el permiso \"${capability.label}\"."
        throw IllegalStateException(motivo)
    }

    /** Sólo las capacidades concedidas, para pintar la UI. */
    fun grantedOf(area: CapabilityArea): Set<Capability> =
        granted.filter { it.area == area }.toSet()
}

/**
 * Resolvedor de permisos efectivos.
 *
 * Función **pura**: mismo contexto → mismo resultado. No lee disco, no
 * llama a la red y no usa el reloj.
 */
object PermissionResolver {

    /**
     * Calcula las capacidades efectivas de [actor] en [course].
     *
     * @param actor inscripción de quien pide el permiso (define el rol).
     * @param course cátedra en la que se opera.
     * @param app estado global de la app.
     * @param target inscripción de cuyos datos se trata (el alumno, en general).
     * @param facts hechos derivados de la cátedra.
     */
    fun resolve(
        actor: Enrollment,
        course: Course,
        app: AppSettings = AppSettings(),
        target: Enrollment? = null,
        facts: CourseFacts = CourseFacts(),
    ): EffectivePermissions {
        val ctx = PermissionContext(course = course, actor = actor, app = app, target = target, facts = facts)
        val granted = mutableSetOf<Capability>()
        val denied = mutableMapOf<Capability, DenialReason>()
        val conditional = mutableMapOf<Capability, Condition>()

        for (capability in Capability.entries) {
            when (PermissionMatrix.verdict(actor.role, capability)) {
                Verdict.DENIED -> {
                    denied[capability] = DenialReason(
                        "El rol \"${actor.role.label}\" no tiene el permiso \"${capability.label}\" en esta cátedra."
                    )
                }

                Verdict.ALLOWED -> granted += capability

                Verdict.CONDITIONAL -> {
                    val condition = capability.condition
                    if (condition != null) {
                        conditional[capability] = condition
                        if (condition.evaluate(ctx)) {
                            granted += capability
                        } else {
                            denied[capability] = DenialReason(
                                "Requiere: ${condition.requirement}.",
                                condition,
                            )
                        }
                    }
                }
            }
        }
        return EffectivePermissions(granted, denied, conditional)
    }

    /** Atajo: ¿puede este docente leer el texto de los chats de este alumno? */
    fun canSeeStudentChats(
        actor: Enrollment,
        course: Course,
        student: Enrollment,
        app: AppSettings = AppSettings(),
    ): Boolean = resolve(actor, course, app, target = student).allows(Capability.CHAT_VIEW_OTHER)

    /** Atajo: ¿puede este docente ver las métricas de este alumno? */
    fun canSeeStudentMetrics(
        actor: Enrollment,
        course: Course,
        student: Enrollment,
        app: AppSettings = AppSettings(),
    ): Boolean =
        resolve(actor, course, app, target = student).allows(Capability.CHAT_VIEW_OTHER_METRICS)
}
```

### A.5 Fachada en `kitoru/domain/Roles.kt`

```kotlin
@Serializable
enum class CourseRole(val label: String) {
    TITULAR("Profesor titular"),
    AYUDANTE("Ayudante"),
    ALUMNO("Alumno"),
    ;

    /**
     * ¿El rol tiene esta capacidad en alguna cátedra?
     *
     * No dice si la operación concreta se permite: eso lo resuelve
     * [PermissionResolver] con el estado de la cátedra y del alumno.
     */
    fun can(capability: Capability): Boolean =
        PermissionMatrix.verdict(this, capability) != Verdict.DENIED

    @Deprecated("Usar Capability.COURSE_EDIT / Capability.MATERIAL_UPLOAD")
    val canDesign: Boolean
        get() = can(Capability.COURSE_EDIT) || can(Capability.MATERIAL_UPLOAD)

    @Deprecated("Usar Capability.MODEL_EDIT_POLICY")
    val canConfigureModel: Boolean get() = can(Capability.MODEL_EDIT_POLICY)

    companion object {
        fun fromName(name: String): CourseRole? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### A.6 `kitoru/domain/ToolPolicy.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Categoría de permiso de una herramienta.
 *
 * Movida desde `rag.agent` porque `:core` no puede depender de `:backend` y
 * la política de herramientas por cátedra vive en el dominio.
 * En `rag.agent` queda un `typealias` para no tocar el código portado.
 */
@Serializable
enum class ToolCategory(val label: String) {
    NONE("Sin permisos"),
    FILES("Archivos"),
    WEB("Web"),
    MCP("MCP"),
    ;

    companion object {
        fun fromName(name: String): ToolCategory? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Techo de herramientas de una cátedra: lo que el docente permite.
 *
 * Es el **nivel 1** de los permisos de herramientas. El nivel 2 es la
 * concesión por chat que hace el usuario (que nunca puede salir de este
 * techo).
 */
@Serializable
data class CourseToolPolicy(
    /** Categorías habilitadas por el docente. Vacío = ninguna. */
    val enabledCategories: Set<ToolCategory> = emptySet(),
    /** Si el alumno puede conceder permisos por chat (dentro del techo). */
    val allowStudentGrant: Boolean = true,
    /** Allowlist de servidores MCP por nombre; vacía = todos. */
    val mcpServers: Set<String> = emptySet(),
)

/**
 * Calcula el techo efectivo de herramientas de una cátedra.
 *
 * `techo = curso ∩ app ∩ (toolsEnabled)`.
 */
object ToolPolicyResolver {

    fun ceiling(
        course: Course,
        app: AppSettings = AppSettings(),
        actor: Enrollment? = null,
    ): Set<ToolCategory> {
        if (!course.modelPolicy.toolsEnabled) return emptySet()
        val porCurso = course.toolPolicy.enabledCategories
        val porApp = app.allowedToolCategories
        var techo = porCurso.intersect(porApp)
        if (actor?.role == CourseRole.ALUMNO && !course.toolPolicy.allowStudentGrant) {
            techo = emptySet()
        }
        return techo
    }

    /** ¿El servidor MCP está permitido en esta cátedra? */
    fun mcpAllowed(course: Course, serverName: String): Boolean {
        val allowlist = course.toolPolicy.mcpServers
        return allowlist.isEmpty() || serverName in allowlist
    }
}
```

### A.7 `kitoru/domain/AppSettings.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** Estado global de la app (ámbito APP). */
@Serializable
data class AppSettings(
    /** Interruptor global de analítica. */
    val analyticsEnabled: Boolean = true,
    /** Registrar los accesos a datos de los alumnos. */
    val auditLogEnabled: Boolean = true,
    /** La app admite servidores MCP. */
    val mcpEnabled: Boolean = false,
    /** Techo global de categorías de herramientas. */
    val allowedToolCategories: Set<ToolCategory> = setOf(ToolCategory.NONE),
    /** Instalación gestionada por la institución: se bloquean los ajustes sensibles. */
    val managed: Boolean = false,
)
```

### A.8 `kitoru/domain/AccessLog.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** Resultado de un acceso auditado. */
@Serializable
enum class AccessOutcome(val label: String) {
    ALLOWED("Permitido"),
    DENIED("Denegado"),
}

/**
 * Una entrada del registro de accesos: quién, qué, cuándo (y a quién).
 */
@Serializable
data class AccessLogEntry(
    val id: String,
    val at: Long,
    val actorId: String,
    val actorName: String,
    val courseId: String,
    val capability: Capability,
    val targetUserId: String = "",
    val targetUserName: String = "",
    val detail: String = "",
    val outcome: AccessOutcome = AccessOutcome.ALLOWED,
)

/** Persistencia del registro de accesos. */
interface AccessLogRepository {
    suspend fun list(courseId: String? = null): List<AccessLogEntry>
    suspend fun append(entry: AccessLogEntry)
    suspend fun clear()
}

/** Log de accesos sobre `config/access-log.json`, append-only con tope. */
class FileAccessLogRepository(fs: FileSystem) : AccessLogRepository {

    private val store = JsonListStore(
        fs = fs,
        path = KitoruPaths.configDir.resolve("access-log.json"),
        itemSerializer = AccessLogEntry.serializer(),
    )

    override suspend fun list(courseId: String?): List<AccessLogEntry> =
        store.read()
            .filter { courseId == null || it.courseId == courseId }
            .sortedByDescending { it.at }

    override suspend fun append(entry: AccessLogEntry) {
        store.update { list ->
            val updated = list + entry
            if (updated.size > MAX_ENTRIES) updated.takeLast(MAX_ENTRIES) else updated
        }
    }

    override suspend fun clear() {
        store.write(emptyList())
    }

    companion object {
        const val MAX_ENTRIES = 5000
    }
}

/**
 * Escribe los accesos sensibles en el log.
 *
 * Sólo se registran las capacidades con `audited = true`: así el log dice
 * algo (el docente leyó los chats de Ana) sin llenarse de ruido.
 */
class PermissionAudit(
    private val repo: AccessLogRepository,
    private val clock: () -> Long = { kitoru.platform.nowMillis() },
    private val newId: () -> String = { newId() },
) {
    suspend fun record(
        actor: Enrollment,
        course: Course,
        capability: Capability,
        target: Enrollment? = null,
        detail: String = "",
        outcome: AccessOutcome = AccessOutcome.ALLOWED,
    ) {
        if (!capability.audited) return
        repo.append(
            AccessLogEntry(
                id = newId(),
                at = clock(),
                actorId = actor.userId,
                actorName = actor.displayName,
                courseId = course.id,
                capability = capability,
                targetUserId = target?.userId.orEmpty(),
                targetUserName = target?.displayName.orEmpty(),
                detail = detail,
                outcome = outcome,
            )
        )
    }
}
```

