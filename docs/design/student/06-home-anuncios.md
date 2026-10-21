# 06 — Home del alumno y pantalla de anuncios

> Subagente: diseño de la **pantalla de entrada del alumno** (la home) y de cómo
> se le **presenta la información de la cátedra**: los avisos, las tareas, el
> acceso al chat y el estado sin conexión.
> Módulos tocados: `:core` (dominio + plataforma), `:ui` (Compose Multiplatform,
> todavía no construido).
> Documentos de referencia leídos: `docs/design/CONTEXTO.md`,
> `docs/design/domain/07-anuncios.md` (entidad `Announcement`, lecturas,
> comentarios, `LocalNotifier`), `docs/design/domain/08-permisos.md` (matriz de
> capacidades), `docs/design/teacher/07-chats.md` (nombres de composables,
> `MarkdownText`, convención de view-models).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *determinista*, *fail-fast con mensajes en español*, *el estudiante
> conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| Contenido de la home | **4 tarjetas fijas**: Novedades · Tareas pendientes · Chat · Tu cátedra | Una pregunta por tarjeta. Si la home crece, deja de contestarse sola. |
| Selector de cátedra | Sólo si el alumno tiene **más de una**; el último elegido se persiste | Con una sola cátedra el selector es ruido; con varias es indispensable y debe recordar la última. |
| Tareas pendientes | Entidad mínima **`Assignment`** en `:core` (hoy no existe) + progreso local del alumno | La home necesita una tercera fuente de verdad, no se puede inferir de los avisos. Se declara como dominio nuevo (§3.4). |
| Dónde vive el hilo de preguntas | **Comentarios del aviso** (un nivel, visibles para la cátedra) | El chat es privado con la IA; la respuesta a "¿se cancela?" tiene que ser pública. |
| "¿Le pregunto a la IA?" | **Sí**, como atajo: abre el chat con el aviso de contexto y permite *insertar* la respuesta en el comentario (marcada como IA) | La IA ayuda a formular; **nunca** postea sola. |
| Distintivo de IA | **Chip "Redactado con IA"** + crédito en el cuerpo + `aiGenerated` como registro | El alumno tiene que saber que el texto no lo escribió una persona. |
| Marcar como leído | Al **abrir** el aviso (nunca en la lista) + "Marcar todo como leído" manual | Un scroll no puede marcar como leído. |
| Lo que ve el docente | "Leído por 37 de 41" + nombres + **fecha de primera lectura** | Un aviso que nadie leyó no sirvió. Es metadato de la cátedra, no del chat. |
| Notificaciones desktop | **Dentro de la app** (bandeja "Novedades" + badge) + `SystemTray` *best effort* | La bandeja del SO no es portable y no existe sin daemon. |
| Notificaciones Android | **`NotificationManager`** + canal `kitoru_avisos` + permiso `POST_NOTIFICATIONS` | Es lo que Android sabe hacer, y el seam `expect/actual` lo abstrae. |
| Seam de notificaciones | `expect fun createLocalNotifier(): LocalNotifier` en `kitoru.platform` | Ya definido en `07-anuncios.md` §5.2; este doc lo **usa**, no lo duplica. |
| Sin conexión | **Local-first**: la pantalla lee siempre del store local; la red sólo *trae* datos | El store local **es** la caché. El snapshot por cátedra es una copia filtrada del alumno. |
| Estado vacío | No es una pantalla vacía: es una tarjeta de bienvenida con **"Preguntale algo a tu profesor por el chat"** | Un alumno recién inscripto necesita una acción, no un "no hay nada". |

---

## 1. Principio: qué información le sirve de verdad

### 1.1 Las tres preguntas del alumno

Un alumno abre la app y tiene tres preguntas, en este orden:

1. **"¿Me perdí algo?"** → ¿hay avisos que no leí?
2. **"¿Qué tengo que entregar?"** → ¿hay tareas por vencer?
3. **"¿Cómo le pregunto?"** → ¿cómo hago una consulta con el material de la cátedra?

Todo lo que no responda una de esas tres preguntas **no va en la home**: se
manda a la pantalla que le corresponde (Avisos, Chat, Materiales, Ajustes).

### 1.2 La regla de las cuatro tarjetas

La home tiene **cuatro tarjetas y ni una más**. El límite es deliberado:

- Un tablero con 9 tarjetas es un tablero que hay que leer: el alumno vuelve a
  no leer nada.
- Cada tarjeta responde **una** pregunta y tiene **una** acción principal.
- Lo que no entra, se linkea ("Ver todos los avisos (7)") hacia la pantalla
 dedicada. La home es un índice, no un tablero de control.

### 1.3 Lo que NO va en la home

| No va | Por qué | Dónde va |
|---|---|---|
| Lista completa de avisos | Duplica la pantalla Avisos | Pantalla Avisos |
| Lista de materiales | Es del curso, no del alumno | Pestaña Materiales |
| Configuración del modelo (sliders) | Es de la cátedra, y el ajuste fino en un panel aparte | Panel "Modelo" (el resumen sí va en la home) |
| Analítica de la cátedra | Es del docente | — |
| Tareas ya entregadas / completas | Ruido: lo que importa es lo pendiente | Pestaña "Tareas" (completa) |
| Cualquier cosa que requiera red para verse | Rompe la promesa offline | — |

---

## 2. El shell del alumno (dónde vive la home)

### 2.1 Pestañas

```
Alumno → [ Inicio ] [ Avisos ] [ Chat ] [ Materiales ] [ Ajustes ]
```

| Pestaña | Contenido | Dueño del doc |
|---|---|---|
| **Inicio** | La home de este documento | **este doc** |
| **Avisos** | Lista + detalle de anuncios | **este doc** |
| **Chat** | Conversaciones con la IA de la cátedra | doc del chat del alumno |
| **Materiales** | Materiales visibles de la cátedra | doc de materiales |
| **Ajustes** | Perfil, visibilidad de los chats, notificaciones, IA local | doc de ajustes |

### 2.2 Desktop: riel de navegación

Riel vertical de 72 dp a la izquierda con íconos; el ícono de **Avisos** lleva
un **badge con la cantidad de no leídos** (§8.5). Contenido a la derecha, ancho
máximo 1100 dp, centrado.

### 2.3 Android: barra inferior

Barra inferior de Material 3 con 5 destinos; los destinos "Inicio" y "Avisos"
llevan badge (`BadgedBox` de M3). Se usa la misma `StudentHomeScreen` en
ambos: **la home no tiene dos versiones**, sólo cambia la disposición de las
tarjetas (§3.2).

### 2.4 Rutas

```kotlin
// Rutas del alumno (string, no tipadas: el shell de Compose Multiplatform
// ya usa rutas por string para el NavHost de Android).
object StudentRoutes {
    const val HOME = "alumno/inicio"
    const val ANNOUNCEMENTS = "alumno/avisos"
    const val ANNOUNCEMENT_DETAIL = "alumno/avisos/{id}"
    const val CHAT = "alumno/chat"
    const val CHAT_THREAD = "alumno/chat/{id}"
    const val MATERIALS = "alumno/materiales"
    const val ASSIGNMENTS = "alumno/tareas"
    const val SETTINGS = "alumno/ajustes"
}
```

---

## 3. La home del alumno

### 3.1 Layout (desktop)

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Kitoru   [ IA-2026-1 · Inteligencia Artificial ▾ ③ ]        ● Conectado   [↻ Actualizar] [⚙]     │
├────┬───────────────────────────────────────────────────────────────────────────────────────────────┤
│ 🏠 │  Hola, Ana · Cátedra IA-2026-1 · cursada 2026-1                                           │
│ Inicio│                                                                                            │
│    │ ┌─── Novedades ──────────────────────────────┐  ┌─── Chat con la IA ───────────────────────┐ │
│ 🔔 │ │ ● Clase del martes cancelada                │  │  Preguntale a tu profesor por el chat.     │ │
│ ③  │ │   hace 2 h · Prof. Ana Gómez                │  │  ┌──────────────────────────────────────┐  │ │
│    │ │   [Redactado con IA]                        │  │  │ Escribí tu consulta…             →   │  │ │
│ 💬 │ │ ───────────────────────────────────────────  │  │  └──────────────────────────────────────┘  │ │
│ Chat│ │ ● La TP1 se entrega el lunes                │  │  Qwen3-8B · 6 fragmentos · Usa el material   │ │
│    │ │   ayer · Prof. Ana Gómez                     │  │  de la cátedra · Tu docente ve: Completo    │ │
│ 📚 │ │                                             │  └──────────────────────────────────────────────┘ │
│ Mate│ │ (Ver todos los avisos · 7)                 │                                               │
│    │ └─────────────────────────────────────────────┘                                               │
│    │ ┌─── Tareas pendientes (2) ───────────────────┐  ┌─── Tu cátedra ──────────────────────────┐ │
│ ⚙ │ │ ☐ TP1 · Entrega individual · Vence el vie 24 │  │  14 materiales · 3 avisados               │ │
│    │ │ ☐ Leer el cap. 3 · Vence hoy                 │  │  Inscripción: 12/09/2026                  │ │
│    │ │ ⚠ La TP2 venció el lunes 20/10              │  │  El chat responde con el material de la     │ │
│    │ │ (Ver todas las tareas)                      │  │  cátedra, no con documentación general.     │ │
│    │ └─────────────────────────────────────────────┘  └──────────────────────────────────────────┘ │
└────┴───────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Layout (Android)

Una columna, mismo orden de tarjetas (Novedades → Tareas → Chat → Cátedra), con
la diferencia de que **la tarjeta de Chat se convierte en una barra fija abajo**
(con el campo de consulta) para no obligar a scrollear hasta el final. La
tarjeta "Tu cátedra" se pliega en un `Card` chico con dos líneas.

### 3.3 Tarjeta 1 — **Novedades** (lo que el alumno no leyó)

Es la tarjeta que gana el espacio. Responde "¿me perdí algo?".

| Elemento | Contenido |
|---|---|
| Título | "Novedades" + chip con el conteo ("3") |
| Lista | Hasta **3** avisos no leídos, ordenados por `createdAt` descendente (los fijados primero) |
| Fila | Punto de no leído · Título (1 línea, `maxLines = 1`, ellipsis) · "hace 2 h · Prof. Ana Gómez" · chips |
| Chips por fila | `Redactado con IA` (si `aiGenerated`), `Fijado` (si `pinned`), `📎 2` (adjuntos), `💬 3` (comentarios) |
| Estado vacío de la tarjeta | "No tenés novedades. Todo lo que publiquen tus profesores va a aparecer acá." + link a Avisos |
| Pie | "Ver todos los avisos (7)" → `StudentRoutes.ANNOUNCEMENTS` |

**Los leídos no se listan acá.** Si el alumno leyó todo, la tarjeta dice
"No tenés novedades" y no se muestran los leídos: la home es la lista de lo
pendiente, la pantalla Avisos es el histórico.

Si el alumno tiene **avisos fijados sin leer**, se listan primero (son los que
importan: "modalidad de examen").

### 3.4 Tarjeta 2 — **Tareas pendientes**

El dominio **no tiene tareas** (ver `CONTEXTO.md`): hay que agregar la entidad
mínima. Se propone en `:core`:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Assignment.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Una tarea de la cátedra (entrega, lectura, actividad, examen).
 *
 * Es la única fuente de "qué tengo que hacer" para la home del alumno.
 * Mínima a propósito: el hito 1 no tiene sistema de entregas ni corrección;
 * la tarea es un cartel con fecha de vencimiento.
 */
@Serializable
data class Assignment(
    val id: String,
    val courseId: String,
    val title: String,
    /** Descripción en Markdown (opcional). */
    val description: String = "",
    val kind: AssignmentKind = AssignmentKind.ENTREGA,
    /** Vencimiento (epoch ms). 0 = sin fecha. */
    val dueAt: Long = 0L,
    val publishedAt: Long = 0L,
    val publishedBy: String = "",
    val publishedByName: String = "",
    val attachments: List<AnnouncementAttachment> = emptyList(),
    /** El docente cerró la tarea (ya no se publica nada más). */
    val closed: Boolean = false,
) {
    val hasDueDate: Boolean get() = dueAt > 0L
    /** Venció y el docente no la cerró. */
    fun isOverdue(now: Long): Boolean = hasDueDate && dueAt < now && !closed
}

@Serializable
enum class AssignmentKind(val label: String) {
    ENTREGA("Entrega"),
    LECTURA("Lectura"),
    ACTIVIDAD("Actividad"),
    EXAMEN("Examen"),
}

/**
 * El progreso del alumno sobre una tarea: es **local** en el hito 1.
 *
 * Vive en el store del alumno (`config/assignment-progress.json`), no en el
 * de la cátedra: el docente no necesita verlo y el alumno puede usarlo con
 * IA local sin que nada salga de su máquina.
 */
@Serializable
data class AssignmentProgress(
    val assignmentId: String,
    val userId: String,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    /** Nota personal del alumno (opcional, 500 caracteres). */
    val note: String = "",
)
```

Repositorios (mismo patrón `File*Repository`):

```kotlin
interface AssignmentRepository {
    suspend fun listForCourse(courseId: String): List<Assignment>
    suspend fun get(id: String): Assignment?
    suspend fun save(assignment: Assignment)
    suspend fun delete(id: String)
}

interface AssignmentProgressRepository {
    suspend fun listForCourse(courseId: String, userId: String): List<AssignmentProgress>
    suspend fun setDone(assignmentId: String, userId: String, done: Boolean, at: Long = nowMillis())
    suspend fun get(assignmentId: String, userId: String): AssignmentProgress?
}
```

Reglas de la tarjeta:

- Se listan **hasta 3** tareas: primero las vencidas, luego las que vencen
  hoy, luego las próximas, en cada grupo por `dueAt` ascendente.
- La casilla es un **checkbox local** (`AssignmentProgress.done`): el alumno
  marca "hecho" y desaparece de la tarjeta. Esto no es una entrega: el
  checkbox significa "ya lo hice".
- `kind = EXAMEN` se muestra con chip rojo y **no** tiene checkbox (un examen
  no se "completa" en la app).
- Si no hay tareas: "No tenés tareas pendientes. Cuando tu profesor publique
  una entrega, te va a aparecer acá con su fecha."
- Pie: "Ver todas las tareas" → `StudentRoutes.ASSIGNMENTS`.

> **Nota de alcance**: la entidad `Assignment` y su pantalla completa las
> publica el docente (pantalla "Tareas" del lado docente). Este doc define
> **el contrato mínimo** que la home necesita: título, tipo, vencimiento y el
> checkbox local. Si el doc docente define `Assignment` con más campos, se
> adopta su versión y esta tarjeta se adapta.

### 3.5 Tarjeta 3 — **Chat con la IA de la cátedra**

Es el acceso al chat y, además, la respuesta a la pregunta importante de la
transparencia: **¿qué ve el docente de lo que escribo?**

```
┌─── Chat con la IA ────────────────────────────────────┐
│  Preguntale a tu profesor por el chat.                 │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Escribí tu consulta…                       →    │  │
│  └──────────────────────────────────────────────────┘  │
│  Qwen3-8B · 6 fragmentos · Usa el material de la      │
│  cátedra, no documentación general.                    │
│  Tu docente ve: Completo        [Cambiar]             │
└───────────────────────────────────────────────────────┘
```

| Elemento | Contenido | Origen |
|---|---|---|
| Campo de consulta | Enviar abre el chat con la pregunta **precargada** | `StudentRoutes.CHAT` + `?draft=` |
| Modelo efectivo | `modelPolicy.resolveFor(studentChoice).modelLabel` | `ModelPolicy` |
| RAG | "6 fragmentos · Usa el material de la cátedra" | `modelPolicy.topK`, `course.allowLocalModels` |
| **Visibilidad** | "Tu docente ve: **Completo / Solo métricas / Oculto**" + botón "Cambiar" | `Enrollment.visibility` |

El chip de visibilidad es **obligatorio**: es la diferencia entre la app de un
alumno y la app de un docente. El alumno tiene que saber si lo que escribe lo
va a ver su profesor, y poder cambiarlo (la enunciación del problema: "el
alumno puede modificar libremente" su configuración).

Clic en "Cambiar" → diálogo con las tres opciones de `ChatVisibility` y su
descripción exacta (las de `Roles.kt`), que **sólo puede restringirse**, nunca
ampliarse por encima del tope que ya fijó el docente.

### 3.6 Tarjeta 4 — **Tu cátedra**

Resumen corto, sin acciones:

- `14 materiales · 3 avisados` (visible / total).
- `Inscripción: 12/09/2026`.
- Una línea de contrato: *"El chat responde con el material de la cátedra, no
  con documentación general."*
- Si `modelPolicy` no valida (falta la clave, el modelo no existe): un chip de
  advertencia "El modelo de la cátedra no está configurado" con link a
  "Ver configuración" (la edición es del docente; el alumno sólo puede
  ajustar lo permitido).

### 3.7 Selector de cátedra

| Situación | Comportamiento |
|---|---|
| 1 sola cátedra | **No hay selector**: la cabecera muestra el nombre de la cátedra como texto. |
| 2 o más | `ExposedDropdownMenuBox` en la cabecera con una fila por cátedra: código · nombre · **badge con los no leídos**. |
| 0 cátedras | La home es la pantalla de "unirse a una cátedra" (§3.9, estado vacío). |

- La cátedra elegida se persiste en `StudentUiState.selectedCourseId`
  (`config/ui-state.json`) y se restaura al abrir la app.
- Al cambiar de cátedra se recargan las cuatro tarjetas.
- Cambio de cátedra **no** borra nada: cada cátedra tiene su propio estado de
  lectura, sus tareas y su caché.

### 3.8 Cabecera de la home

```
┌ Kitoru  [ IA-2026-1 · Inteligencia Artificial ▾ ③ ]        ● Conectado   [↻ Actualizar] [⚙] ┐
```

- **Chip de conexión**: `● Conectado` (verde) / `● Sin conexión` (ámbar) /
  `● Sin conexión · mostrando lo de hace 2 h` (ámbar, con el tooltip del
  §9.5). El estado es **de la app**, no del sistema operativo (§9.1).
- **[↻ Actualizar]**: relee el feed local y (en hito 2) consulta al servidor.
  Deshabilitado mientras dura la operación.
- **[⚙]**: ajustes (fuera del alcance de este doc).

### 3.9 Estado vacío de la home (alumno recién inscripto)

Se detalla en §10.

---

## 4. Anuncios: la lista

Ruta: `StudentRoutes.ANNOUNCEMENTS`.

### 4.1 Layout (desktop)

```
┌───────────────────────────────────────────────────────────────────────────────────────────────┐
│  ← Inicio     IA-2026-1 · Inteligencia Artificial                                             │
│  Avisos                                                              [Todas|Sin leer|Fijadas]  │
│                                     7 avisos · 3 sin leer                    [Marcar todo leído] │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│  📌 FIJADOS                                                                                    │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │ ●  Modalidad del examen parcial                                                           │ │
│  │    Martes 4/11, 10 h, aula 3. El parcial cubre las unidades 1 a 3.                        │ │
│  │    Prof. Ana Gómez · 2 h · [Redactado con IA] · 💬 4                                      │ │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                               │
│  NO LEÍDOS                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │ ●  Clase del martes cancelada                                                             │ │
│  │    Se cancela la clase del martes 27/10. La clase se dicta el jueves 29/10 a las 10 h.     │ │
│  │    Prof. Ana Gómez · hace 2 h · 💬 2                                                       │ │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │ ●  La TP1 se entrega el lunes                                                            │ │
│  │    …                                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                               │
│  ANTERIORES                                                                                   │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │    Bienvenida a la cátedra · Prof. Ana Gómez · 12/09          Leído el 12/09              │ │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Orden y secciones

Tres secciones, en este orden:

1. **Fijados** (`pinned = true`): siempre arriba, aunque ya se hayan leído.
   Un aviso fijado es "esto importa ahora" (modalidad de examen).
2. **No leídos**: `createdAt` descendente.
3. **Anteriores**: los leídos, `createdAt` descendente.

Un aviso **fijado y no leído** aparece **sólo en la primera sección** (no se
duplica). La regla es: `pinned` manda sobre "no leído".

### 4.3 La fila de aviso

```
●  Título del aviso                                        [Redactado con IA] [Fijado]
   Extracto del cuerpo (2 líneas, ellipsis)
   Prof. Ana Gómez · hace 2 h · 💬 3 · 📎 2
```

| Parte | Regla |
|---|---|
| Punto `●` | Sólo si **no leído**. Es el indicador más barato de leer. |
| Título | `titleMedium`, 2 líneas como máximo, `ellipsis` |
| Extracto | Primeros ~140 caracteres del `body` **en plano** (el Markdown se muestra crudo: sin renderer en `:core`) |
| Autoría y fecha | `authorName` + tiempo relativo (`formatRelative` de `ui/ui/Format.kt`) |
| Chips | `Redactado con IA`, `Fijado`, `💬 N` (comentarios), `📎 N` (adjuntos) |
| Fila leída | Sin punto, con la fecha de lectura a la derecha: "Leído el 12/09" |
| Fila histórica | Si `createdAt < enrollment.joinedAt`, la fila muestra el chip "Anterior a tu inscripción" |

### 4.4 Filtros

Segmentado de 3 opciones sobre la misma lista (no hay pantallas distintas):

| Filtro | Contenido |
|---|---|
| **Todas** | Fijados + no leídos + anteriores |
| **Sin leer** | Sólo los no leídos (fijados y no) |
| **Fijadas** | Sólo los fijados |

Se agregará "Buscar" cuando haya más de 50 avisos en una cátedra (hito 2); en
el hito 1 no hace falta y la lista se scrollea.

### 4.5 Acciones masivas

- **"Marcar todo como leído"** (en la barra superior, sólo si hay no leídos):
  crea un `AnnouncementRead` por cada aviso no leído, con **el mismo
  `readAt`** (el momento del clic). Es idempotente y no pisa lecturas
  previas (regla de `07-anuncios.md` §3).
- **"Marcar como no leído"**: acción del detalle (§5.5). Borra el registro de
  lectura del alumno para ese aviso.

### 4.6 El distintivo de "redactado con IA"

Aparece **en la lista y en el detalle**, como chip:

```
[✦ Redactado con IA]
```

- Es un chip de `secondaryContainer` con ícono de chispa (`AutoAwesome`), **no**
  un error ni un warning: el texto fue revisado por el docente.
- Tooltip / texto auxiliar: *"El borrador lo generó {aiModel} y lo revisó
  {authorName}."*
- El chip se apoya en `announcement.aiGenerated`, que es el **registro**; no
  en el texto del cuerpo. Si el docente borró a mano la línea de crédito, el
  chip sigue ahí (regla 2 y 3 de `07-anuncios.md` §4.1).

---

## 5. Anuncios: el detalle

Ruta: `StudentRoutes.ANNOUNCEMENT_DETAIL`.

### 5.1 Layout

```
┌───────────────────────────────────────────────────────────────────────────────────────────────┐
│  ← Avisos                                                                                      │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │  ● Clase del martes cancelada          [Redactado con IA] [Fijado]                       │ │
│  │  Prof. Ana Gómez · martes 21/10 14:02 · Toda la cátedra          [⋮]                     │ │
│  │  ─────────────────────────────────────────────────────────────────────────────────────  │ │
│  │  Se cancela la clase del martes 27/10.                                                    │ │
│  │                                                                                            │ │
│  │  **La clase se dicta el jueves 29/10 a las 10 h**, en el aula 3.                          │ │
│  │                                                                                            │ │
│  │  > _Generado con asistencia de IA (modelo «llama-3.1-8b-instruct») y revisado por el    │ │
│  │  > docente._                                                                            │ │
│  │  ─────────────────────────────────────────────────────────────────────────────────────  │ │
│  │  📎 Adjuntos                                                                             │ │
│  │   · Plan de clases (PDF)                                                                │ │
│  │  ─────────────────────────────────────────────────────────────────────────────────────  │ │
│  │  💬 2 comentarios                                                                       │ │
│  │   Ana Ruiz (Alumno) · hace 1 h                                                          │ │
│  │   ¿La TP2 también se corre?                                                             │ │
│  │     ↳ Prof. Ana Gómez · hace 30 min                                                     │ │
│  │       Sí, corre el viernes.                                                              │ │
│  │   ─────────────────────────────────────────────────────────────────────────────────────  │ │
│  │  [ Escribí una pregunta…                                              ] [Enviar]        │ │
│  │  Leído el martes 21/10 14:05                                            [Marcar no leído]  │ │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                    [ ¿Preguntarle a la IA sobre este aviso? ]                 │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 El Markdown se renderiza con el parser portable

El cuerpo (`Announcement.body`) es Markdown y se dibuja con el **parser
portable portado de FunshiEngineAI**, no con un renderer de plataforma:

| Pieza | Origen | Ruta en Kitoru |
|---|---|---|
| Parser de nodos (`MdNode`, `parseMarkdown`) | `FunshiEngineAI/frontend/src/main/kotlin/app/ui/Markdown.kt` | `ui/src/commonMain/kotlin/kitoru/ui/markdown/MarkdownText.kt` |
| Tests del parser | `FunshiEngineAI/frontend/src/test/kotlin/app/ui/MarkdownTest.kt` | `ui/src/commonTest/kotlin/kitoru/ui/markdown/MarkdownTextTest.kt` |

Criterio de portabilidad (mismo que decidió `teacher/07-chats.md` §4.3):

- **Se porta** el parser, los nodos y el render de texto: encabezados,
  párrafos, listas, citas, tablas, reglas, bloques de código e inline
  (negrita, cursiva, tachado, `código`, links).
- **No se porta** el render de LaTeX de FunshiEngineAI (`MathRender` usa
  `java.awt` → no es portable). Las fórmulas `$…$` se muestran como **texto
  monoespaciado legible y copiable**, tal como se decidió para el chat del
  docente.
- El renderer es **compartido** con el chat del alumno: una sola implementación
  de Markdown en toda la app.

Los nodos que produce el parser son los mismos que ya están definidos en
`teacher/07-chats.md` §9.2:

```kotlin
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    /** Resalta un fragmento (búsqueda en el chat). */
    highlight: String = "",
)
```

Cuidado con el `body` que el docente escribe: `MAX_BODY = 8_000` caracteres
(`07-anuncios.md`), o sea ~40 líneas. La lista **no** renderiza el Markdown
(sólo texto plano, §4.3): renderizar 7 avisos completos en una lista sería
caro y noisy.

### 5.3 El distintivo de IA en el detalle

Dos capas, y **las dos** están:

1. **El chip** `✦ Redactado con IA` en el encabezado (visible sin scrollear).
2. **La línea de crédito** en el final del cuerpo:

   ```markdown
   > _Generado con asistencia de IA (modelo «llama-3.1-8b-instruct») y revisado por el docente._
   ```

   La línea la agrega la app de forma determinística (`07-anuncios.md` §4.1,
   regla 2): el modelo no la escribe y el docente no puede olvidarla.

Si el chip dice "redactado con IA" y el crédito no está (porque el docente lo
borró), la app **no** vuelve a insertarlo: el registro manda. El chip es la
fuente de verdad para el alumno.

Un aviso escrito a mano por el docente **no** lleva chip ni crédito: es
importante que el alumno distinga "esto lo escribió una persona".

### 5.4 Adjuntos

`Announcement.attachments` son **referencias a materiales** de la cátedra
(`AnnouncementAttachment(materialId, title, path, kind)`). La lista de adjuntos
se dibuja con el mismo componente de materiales que usa la pestaña Materiales:
ícono por `MaterialKind`, título, y al tocarlo se abre el material (o se
avisa "Este material ya no está disponible" si el archivo no existe).

### 5.5 Pie del detalle y acciones

| Elemento | Comportamiento |
|---|---|
| "Leído el martes 21/10 14:05" | Estado de lectura **propio**. Si no se leyó todavía (no debería pasar: se marca al abrir), dice "Marcar como leído". |
| [Marcar no leído] | Borra el `AnnouncementRead` del alumno para este aviso. Útil para volver a mirar un aviso que se leyó de pasada. |
| [⋮] | Copiar el texto del aviso · Copiar el enlace (`kitoru://anuncio/<id>`) · Reportar un problema (hito 2) |
| Botón inferior "**¿Preguntarle a la IA sobre este aviso?**" | §7.3 |

---

## 6. Marcar como leído, y lo que ve el docente

### 6.1 Reglas (del lado del alumno)

1. **Se marca al abrir el detalle**, nunca al ver la fila de la lista. Un
   scroll no puede marcar 7 avisos como leídos.
2. El `AnnouncementRead` se escribe **una sola vez**: `markRead` hace upsert y
   **conserva el `readAt` más antiguo** (`07-anuncios.md` §3). Releer no cambia
   la fecha.
3. El `readAt` es el momento de la **primera** lectura.
4. Un aviso anterior a `enrollment.joinedAt` **no** se cuenta como pendiente ni
   dispara notificación, pero se lista y se puede leer.
5. "Marcar todo como leído" (§4.5) escribe un registro por aviso con el mismo
   `readAt`.

### 6.2 Lo que ve el docente

En la pantalla de detalle del aviso (lado docente), una tarjeta **"Lecturas"**:

```
┌─ Lecturas ─────────────────────────────────────────────────────┐
│  ████████████░░░░░░░░░░░░░░░░  37 de 41 leyeron el aviso       │
│                                                                  │
│  Ana Ruiz        leída el 21/10 14:05                           │
│  Bruno Díaz      leída el 21/10 15:12                           │
│  Carla Méndez    leída el 21/10 16:40                           │
│  …                                                                    │
│  ── Todavía no leyeron (4) ──                                   │
│  Diego Paz, Elena Ruiz, Franco Soto, Gaby N.                    │
└──────────────────────────────────────────────────────────────────┘
```

| Dato | Visible para el docente |
|---|---|
| Quién leyó (nombre) | Sí |
| Fecha de **primera** lectura | Sí (`readAt`, que es inmutable) |
| Cuántas veces lo leyó | **No**: no existe el dato |
| Cuándo lo releyó | **No**: no existe el dato |
| Los que **no** leyeron | Sí (nombres de los inscriptos − leídos) |
| El texto de los chats del alumno | **No**: es otra categoría de dato (§6.3) |

**Privacidad**: lo que el docente ve es "lo leyó", no "lo está mirando". El
`readAt` de primera lectura es información de la cátedra (comunicación), no
del chat del alumno; por eso **no** habilita ninguna capacidad de
`ChatVisibility`.

> **Punto a alinear con `domain/07-anuncios.md`**: aquel doc §3 lista lo que ve
> el docente como "el número y la lista de nombres". Acá se propone sumar la
> **fecha de primera lectura** por persona, que es dato que ya está en
> `AnnouncementRead.readAt`. Si el dueño de aquel doc prefiere no mostrarla, la
> tarjeta lo omite sin cambios en el dominio: es una decisión de presentación.

### 6.3 Qué NO habilita leer el aviso

Marcar un aviso como leído **no** le da al docente acceso a:

- el texto de los chats del alumno (`ChatVisibility` sigue mandando),
- las métricas de los chats,
- los comentarios que el alumno escribió en **otro** aviso.

La lista de lecturas es un **metadato del curso**, equivalente al "visto" de
una notificación. Queda explícito para que nadie lo lea como una puerta
lateral.

---

## 7. Preguntar en el anuncio: comentarios + "preguntarle a la IA"

### 7.1 Decisión

> **El hilo de respuestas vive en los comentarios del aviso** (un solo nivel,
> visibles para toda la cátedra), **no en el chat**.

Por qué:

| Alternativa | Por qué no |
|---|---|
| Chat con el docente | No existe: el chat es con la **IA**. |
| Chat con la IA y que la respuesta se pegue sola | Un texto generado no es una respuesta institucional: si se postea solo, el docente queda sin enterarse. |
| Comentarios en el aviso (elegida) | Es el canal institucional, es público (evita 40 consultas privadas idénticas), el docente lo modera y el alumno **decide** qué publica. |

La Visibility del aviso (`announcement.commentsEnabled`) manda: si el docente
cerró las preguntas, el compositor **no aparece** y se muestra
*"El profesor cerró las preguntas de este aviso."*

### 7.2 El hilo

`AnnouncementComment` (definido en `07-anuncios.md` §6) ya cubre lo necesario:
`body` en Markdown, `authorRole`, `parentId` (un nivel), `aiGenerated`.

Reglas de la UI del alumno:

- **Un solo nivel**: pregunta → respuestas. Una respuesta a una respuesta se
  muestra indentada pero **no** admite respuestas (el compositor de respuesta
  dice "Respondé en el hilo principal").
- **Visibles para toda la cátedra**: el alumno que pregunta y los demás
  alumnos ven las respuestas. Esto convierte el aviso en el FAQ de la cursada.
- Límite: 2000 caracteres, 1 comentario cada 30 s por cliente (el servidor
  cuando exista impone el límite real).
- El alumno puede **borrar el propio** mientras nadie le haya respondido;
  después, sólo el docente.
- Markdown: el cuerpo del comentario se renderiza con el **mismo**
  `MarkdownText` del aviso (una sola implementación).

### 7.3 "¿Preguntarle a la IA sobre este aviso?"

Botón en el detalle del aviso. Abre el **chat** con un borrador ya armado:

```
Sobre el aviso «Clase del martes cancelada» de la cátedra IA-2026-1:

Se cancela la clase del martes 27/10. La clase se dicta el jueves 29/10 a las 10 h.

> _Generado con asistencia de IA (modelo «llama-3.1-8b-instruct») y revisado por el docente._

Mi consulta: ¿la TP2 también se corre?
```

Detalles:

- Se crea un **hilo nuevo** del chat con título `"Consulta: {título del aviso}"`.
- El modelo responde con el **RAG de la cátedra** (no con documentación
  general): el mismo motor que usa el chat normal.
- El texto del aviso va **completo** en el prompt (≤ 8.000 caracteres: entra de
  sobra) y marcado como contexto, para que la IA no lo confunda con la
  pregunta.
- Si la cátedra no tiene material indexado, la IA lo dice: "no encontré nada
  en el material sobre esto".

**Y el retorno, que es lo importante:** en la pantalla del chat, la respuesta
de la IA ofrece el botón **"Insertar en el comentario"**. Al insertar:

1. el texto de la IA se pega en el compositor del comentario del aviso;
2. el comentario queda con `aiGenerated = true`;
3. se muestra el chip **"✦ Redactado con IA"** en el comentario, con el mismo
   texto auxiliar que los avisos.

Es decir: la IA **ayuda a redactar**, el alumno **decide qué publica** y
siempre queda dicho que el texto es de la IA. La IA nunca postea sola.

### 7.4 Qué puede hacer el docente (recordatorio)

- Moderar: borrar cualquier comentario.
- Cerrar las preguntas de un aviso (`commentsEnabled = false`).
- Responder con ayuda de IA: el borrador se edita a mano y el comentario
  queda con `aiGenerated = true` (`07-anuncios.md` §6).

---

## 8. Notificaciones

### 8.1 El seam `expect/actual`

El contrato ya está definido en `domain/07-anuncios.md` §5.2. Acá se **usa** y
se completa con lo que la home necesita (destino, badge y deep link):

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Notifier.kt
package kitoru.platform

/** Qué hay que abrir cuando el usuario toca la notificación. */
enum class NotificationTargetKind { ANNOUNCEMENT, COMMENT, ASSIGNMENT, COURSE }

/**
 * Destino de una notificación.
 *
 * Es un dato, no una función: el `actual` de cada plataforma lo arma en un
 * `Intent` (Android) o lo despacha al shell (desktop).
 */
data class NotificationTarget(
    val kind: NotificationTargetKind,
    /** Id del aviso, comentario o tarea. */
    val id: String,
    val courseId: String = "",
)

/** Notificación local del dispositivo. */
data class LocalNotification(
    /** Id estable: permite reemplazar o cancelar. */
    val id: String,
    val title: String,
    val body: String,
    val target: NotificationTarget,
    /** Cantidad que se muestra en el badge (0 = sin badge). */
    val badgeCount: Int = 1,
    val createdAt: Long = 0L,
)

/**
 * Publica notificaciones locales del sistema operativo.
 *
 * **Una implementación por plataforma.** El núcleo portable (qué notificar y
 * cuándo) vive en `kitoru.domain.AnnouncementNotifier`.
 */
interface LocalNotifier {
    /** ¿El sistema operativo permite notificar? (permiso + ajustes del SO). */
    fun areEnabled(): Boolean
    /** ¿Falta pedir permiso? (Android 33+). En el resto, false. */
    fun needsPermission(): Boolean = false
    /** Publica (o reemplaza) una notificación. */
    fun post(notification: LocalNotification)
    /** Cierra una notificación ya publicada. */
    fun cancel(id: String)
    /** Cierra todas las de una cátedra. */
    fun cancelAllForCourse(courseId: String)
}

/** La implementación de la plataforma. */
expect fun createLocalNotifier(): LocalNotifier
```

Y el puente de vuelta hacia la app (para el deep link):

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/NotificationCenter.kt
package kitoru.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Canal por el que la app se entera de que el usuario tocó una notificación.
 *
 * En desktop lo emite el clic en el globo de la bandeja del sistema; en
 * Android, la `Activity` que recibió el `Intent` de la notificación.
 * El shell del alumno lo colecta y navega.
 */
class NotificationCenter {
    private val _targets = MutableSharedFlow<NotificationTarget>(extraBufferCapacity = 8)

    /** Destinos a los que hay que navegar. */
    val targets: SharedFlow<NotificationTarget> = _targets

    /** Lo llama la plataforma cuando el usuario tocó la notificación. */
    fun dispatch(target: NotificationTarget) {
        _targets.tryEmit(target)
    }
}
```

El `NotificationCenter` es **una instancia por app**, inyectada en el shell y
en los view-models que la necesitan.

### 8.2 Qué hace cada plataforma en el hito 1

| Mecanismo | Desktop (hito 1) | Android (hito 1) | ¿Portable? |
|---|---|---|---|
| **Sección "Novedades"** en la home | Sí (tarjeta 1) | Sí (tarjeta 1) | ✅ **es la base** |
| **Badge en el ícono** de la app | Sí: sufijo en el título de la ventana (`Kitoru (3)`) + badge en el ícono del riel | Sí: badge en el ítem de la barra inferior (`BadgedBox`) | ✅ (dentro de la app) |
| **Banner emergente** con la app abierta | Sí | Sí | ✅ |
| **Notificación del sistema operativo** | `SystemTray` (`displayMessage`) — **sólo si hay bandeja**; si no, no-op silencioso | `NotificationManager` + canal `kitoru_avisos` | ⚠️ dos adaptadores finos |
| **Con la app cerrada** | ❌ (no hay daemon; en el hito 1 tampoco "modo bandeja") | ❌ (requiere Kitoru-Server + `WorkManager`; hoy es un stub) | ❌ |
| **Push del servidor (FCM)** | ❌ | ❌ | ❌ fuera de alcance |

**La regla de oro**: si el SO no notifica, la app **igual** muestra la novedad.
Por eso el badge y la tarjeta "Novedades" **no** dependen de
`LocalNotifier.areEnabled()`: se calculan de los datos (`AnnouncementRead` +
`Audience`), nunca de un contador paralelo que se pueda desincronizar.

### 8.3 Android: `NotificationManager`

Archivo: `core/src/androidMain/kotlin/kitoru/platform/Notifier.android.kt`.

```kotlin
// El contexto de la aplicación se inyecta al arrancar, igual que
// KitoruPaths.setDataDir(...).
object KitoruAndroid {
    private lateinit var appContext: Context
    private var activityClassName: String = ""

    fun init(context: Context, activityClassName: String) {
        appContext = context.applicationContext
        this.activityClassName = activityClassName
    }
}
```

Detalles:

- **Canal**: `kitoru_avisos`, `IMPORTANCE_DEFAULT`, creado de forma perezosa
  la primera vez que se postea.
- **Permiso**: `POST_NOTIFICATIONS` (API 33+). El módulo `:core` **no** pide
  permisos (necesita una `Activity`): expone `needsPermission()` y el módulo
  `:android` (la app) lo pide al arrancar o la primera vez que va a notificar.
  En API 24–32 el permiso es implícito.
- **Dependencia**: `androidx.core:core-ktx` para `NotificationCompat` (funciona
  desde API 14 y evita el `if (SDK >= 26)` con dos caminos de construcción).
- **Deep link**: `PendingIntent` con `Intent(activityClassName)` y extras
  `EXTRA_TARGET_KIND` / `EXTRA_TARGET_ID`. La `Activity` los lee al arrancar y
  hace `notificationCenter.dispatch(target)`.
- **Cancelación**: `cancelAllForCourse` busca por tag (`courseId`) y cancela.

```kotlin
private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        CHANNEL_ID, "Avisos de la cátedra", NotificationManager.IMPORTANCE_DEFAULT,
    ).apply { description = "Novedades y respuestas de tus profesores." }
    appContext.getSystemService(NotificationManager::class.java)
        ?.createNotificationChannel(channel)
}
```

### 8.4 Desktop: bandeja del sistema y badge

Archivo: `core/src/desktopMain/kotlin/kitoru/platform/Notifier.desktop.kt`.

- `java.awt.SystemTray`: si `SystemTray.isSupported()`, se instala un `TrayIcon`
  con el ícono de la app y se llama a
  `displayMessage(title, body, MessageType.INFO)` cuando llega un aviso.
  El clic en el globo → `notificationCenter.dispatch(target)`.
- Si **no** hay bandeja (Linux sin tray, RDP, servidor): `post` es un **no-op
  silencioso** y la UI muestra el banner interno. **No se avisa al usuario de
  que no se notificó**: el banner interno ya cumplió.
- El **badge** (título de la ventana con el conteo) no usa `SystemTray`: es un
  `expect/actual` de la capa `:ui`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/AppBadge.kt
package kitoru.ui.platform

/**
 * Badge de la app en la barra de tareas / dock.
 *
 * - Desktop: sufijo en el título de la ventana ("Kitoru (3)").
 * - Android: **no-op**: el badge del lanzador no es portable (y la app ya
 *   muestra el badge en su barra inferior).
 */
expect fun setAppBadge(count: Int)
```

### 8.5 El badge de "no leídos" (núcleo portable)

Se calcula siempre de los datos:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AnnouncementPolicy.kt
package kitoru.domain

/**
 * Reglas puras sobre los avisos de una cátedra, vistas por un alumno.
 *
 * Son funciones puras (sin repositorio, sin reloj del sistema): se testean
 * con listas y timestamps fijos.
 */
object AnnouncementPolicy {

    /** Avisos que el alumno puede ver, ordenados: fijados primero, luego por fecha. */
    fun visible(
        announcements: List<Announcement>,
        enrollment: Enrollment,
    ): List<Announcement> = announcements
        .filter { it.audience.matches(enrollment) }
        .sortedWith(
            compareByDescending<Announcement> { it.pinned }
                .thenByDescending { it.createdAt },
        )

    /** Cuántos no leídos tiene (los anteriores a la inscripción no cuentan). */
    fun unreadCount(
        announcements: List<Announcement>,
        enrollment: Enrollment,
        readUserIds: Set<String>,
    ): Int = announcements.count { a ->
        a.createdAt >= enrollment.joinedAt &&
            a.audience.includes(enrollment.role) &&
            a.id !in readUserIds
    }

    /** ¿Este aviso es "nuevo" para el alumno? */
    fun isUnread(
        announcement: Announcement,
        enrollment: Enrollment,
        readUserIds: Set<String>,
    ): Boolean = announcement.createdAt >= enrollment.joinedAt &&
        announcement.id !in readUserIds
}
```

El badge de la barra/riel es `AnnouncementPolicy.unreadCount(...)`: **derivado**,
no un contador almacenado. Si se desincroniza, no hay nada que
desincronizar.

### 8.6 Qué se notifica

`AnnouncementNotifier.notifyPending(course, enrollment)` (definido en
`07-anuncios.md` §5.2) decide **qué**; el `LocalNotifier` decide **dónde**.

Reglas:

- Notifica los avisos no leídos y posteriores a `enrollment.joinedAt`.
- `id = "anuncio:${announcement.id}"` → el mismo aviso no genera dos
  notificaciones.
- Título de la notificación = `announcement.title`.
- Cuerpo = los primeros ~140 caracteres del `body` **en plano**.
- Cuando el docente responde un comentario del alumno:
  `id = "comentario:${comment.id}"`, destino `COMMENT`.
- **El propio docente que publica no se notifica**: `notifyPending` se llama
  desde el cliente que **recibe** el aviso, no desde el que lo escribe.

---

## 9. Estado sin conexión: la caché

### 9.1 El modelo: local-first

> **La pantalla nunca lee de la red. Lee siempre del store local.** La red
> *trae* datos; la app los guarda; la pantalla los muestra.

Esto no es una decisión de la home: es la forma en que el proyecto guarda todo
lo demás (`JsonListStore` en `config/`). La consecuencia práctica es que
**los avisos ya se pueden leer sin red por construcción** en el hito 1: son
archivos locales.

El estado de conexión de la app **no** se pregunta al sistema operativo
(`NetworkInterface` no es portable ni fiable en escritorio). Es un dato de la
app:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Connectivity.kt
package kitoru.domain

/**
 * Estado de la red **según la app**.
 *
 * No se pregunta al sistema operativo: se deduce de si la última operación
 * de red (sync, consulta al modelo) funcionó. Es determinista y portable.
 */
enum class Connectivity {
    /** Todavía no se intentó nada. */
    UNKNOWN,

    /** La última operación de red funcionó. */
    ONLINE,

    /** La última operación de red falló. */
    OFFLINE,
}
```

Cada cliente de red (el sync del Kitoru-Server, el gateway del modelo)
informa el resultado y el shell actualiza el chip de la cabecera.

### 9.2 Qué se cachea y dónde

Hay **dos** copias y no son redundantes:

| Almacén | Contenido | Quién lo lee |
|---|---|---|
| `config/announcements.json` | **La verdad de la cátedra**: todos los avisos, incluidos los de audiencia `STAFF` | El docente, y el proceso que arma el feed |
| `cache/feeds/<courseId>.json` | **La vista del alumno**: sólo los avisos que su inscripción puede ver | **El alumno** (home, lista, detalle) |

La copia del alumno **existe para poder filtrar en el momento de escribir**, no
en el de leer: un aviso `STAFF` **nunca llega** al archivo del alumno, ni
siquiera su `body` (`07-anuncios.md` §2). Se filtra dos veces (escritura y
lectura) por defensa en profundidad.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentFeed.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Lo que el alumno ve de una cátedra: su copia local.
 *
 * Es una **copia filtrada**: sólo contiene avisos y comentarios que la
 * inscripción del alumno puede ver. Es lo que se lee sin red.
 */
@Serializable
data class StudentFeed(
    val courseId: String,
    /** Cuándo se armó esta copia (epoch ms). */
    val fetchedAt: Long = 0L,
    /** Revisión del origen (para resolver conflictos). 0 = local. */
    val revision: Long = 0L,
    val announcements: List<Announcement> = emptyList(),
    val comments: List<AnnouncementComment> = emptyList(),
) {
    fun announcement(id: String): Announcement? = announcements.firstOrNull { it.id == id }
    fun commentsFor(announcementId: String): List<AnnouncementComment> =
        comments.filter { it.announcementId == announcementId }
}

interface StudentFeedRepository {
    suspend fun read(courseId: String): StudentFeed?
    suspend fun write(feed: StudentFeed)
    suspend fun update(courseId: String, transform: (StudentFeed) -> StudentFeed)
    suspend fun clear(courseId: String)
}
```

Implementación:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/FileStudentFeedRepository.kt
class FileStudentFeedRepository(fs: FileSystem) : StudentFeedRepository {

    private fun file(courseId: String): FilePath =
        KitoruPaths.cacheDir.resolve("feeds").resolve("$courseId.json")

    override suspend fun read(courseId: String): StudentFeed? {
        val path = file(courseId)
        if (!fs.exists(path)) return null
        val text = fs.readText(path)
        return runCatching { KitoruJson.decodeFromString(StudentFeed.serializer(), text) }.getOrNull()
    }

    override suspend fun write(feed: StudentFeed) {
        val path = file(feed.courseId)
        fs.createDirectories(path.parent ?: path)
        // Escritura atómica: se escribe a un .tmp y se copia encima.
        // (FileSystem no tiene rename; copy + delete es el equivalente portable.)
        val tmp = path.parent?.resolve("${path.name}.tmp") ?: path
        fs.writeText(tmp, KitoruJson.encodeToString(StudentFeed.serializer(), feed))
        fs.copy(tmp, path)
        fs.delete(tmp)
    }

    override suspend fun update(courseId: String, transform: (StudentFeed) -> StudentFeed) {
        val current = read(courseId) ?: StudentFeed(courseId = courseId)
        write(transform(current))
    }

    override suspend fun clear(courseId: String) {
        val path = file(courseId)
        if (fs.exists(path)) fs.delete(path)
    }
}
```

### 9.3 Quién arma el feed

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentFeedWriter.kt
package kitoru.domain

/**
 * Arma la copia local del alumno a partir de la verdad de la cátedra.
 *
 * Filtra por audiencia **antes** de escribir: un aviso de docentes no llega
 * al archivo del alumno.
 */
class StudentFeedWriter(
    private val announcements: AnnouncementRepository,
    private val comments: AnnouncementCommentRepository,
    private val feeds: StudentFeedRepository,
) {
    /** Recalcula el feed de una cátedra para una inscripción concreta. */
    suspend fun refresh(courseId: String, enrollment: Enrollment): StudentFeed {
        val visible = AnnouncementPolicy.visible(announcements.listForCourse(courseId), enrollment)
        val visibleIds = visible.map { it.id }.toSet()
        val feedComments = comments.listForCourse(courseId)
            .filter { it.announcementId in visibleIds }
        val feed = StudentFeed(
            courseId = courseId,
            fetchedAt = nowMillis(),
            revision = visible.maxOfOrNull { it.updatedAt } ?: 0L,
            announcements = visible,
            comments = feedComments,
        )
        feeds.write(feed)
        return feed
    }
}
```

En el hito 1, `refresh` se llama: al abrir la app, al cambiar de cátedra, al
importar un paquete de cátedra y cuando el docente publica (en la instalación
del docente). En el hito 2, el cliente del Kitoru-Server escribe el feed
directamente desde la respuesta del servidor.

### 9.4 Outbox: escribir sin red

Un alumno puede estar en el subte sin señal y querer dejar su pregunta escrita.
La **bandeja de salida** permite eso:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CommentOutbox.kt
package kitoru.domain

/**
 * Comentario que el alumno escribió sin conexión y todavía no se publicó.
 *
 * Vive en `cache/outbox/`, no en `config/`: es un borrador temporal, no un
 * dato de la cátedra. Si el alumno borra la app, se pierde sin consecuencias.
 */
@Serializable
data class PendingComment(
    val id: String,
    val courseId: String,
    val announcementId: String,
    val body: String,
    val parentId: String? = null,
    val aiGenerated: Boolean = false,
    val createdAt: Long = 0L,
    val attempts: Int = 0,
)

interface CommentOutbox {
    suspend fun enqueue(comment: PendingComment)
    suspend fun pending(courseId: String): List<PendingComment>
    suspend fun remove(id: String)
    suspend fun fail(id: String)
}
```

Reglas:

- Si el comentario se publica, sale del outbox.
- Si falla, queda con `attempts + 1` y se reintenta al volver la conexión
  (o al abrir la app). La UI muestra "1 comentario pendiente de enviar".
- El **alumno ve su comentario pendiente** en el hilo (con el chip "pendiente
  de enviar"), aunque todavía no lo vean los demás.

### 9.5 Indicadores en la UI

| Dónde | Indicador |
|---|---|
| Cabecera de la home | `● Sin conexión · mostrando lo de hace 2 h` |
| Pantalla Avisos | Mismo chip en la barra superior + "Última actualización: martes 21/10 14:02" |
| Detalle del aviso | Igual; el contenido se lee igual |
| Compositor de comentario | Botón **Enviar** deshabilitado + ayuda "Sin conexión: tu pregunta se guarda y se envía sola al volver la conexión" |
| Tarjeta de Chat | El error real del gateway (`LlmException` con su mensaje en español) |
| Avisos nuevos mientras no hay red | No hay: no hay red, no hay arrivals |

---

## 10. Estado vacío: alumno recién inscripto

### 10.1 Cuándo aparece

- La cátedra no tiene **ningún** aviso visible para el alumno, **y**
- no tiene tareas, **y**
- el alumno tiene al menos un material visible (o ninguno: se muestra igual).

### 10.2 Qué ve

La home **no** queda en blanco ni muestra cuatro tarjetas vacías: se reemplaza
por **una tarjeta de bienvenida** con la misma caja y el mismo ancho:

```
┌───────────────────────────────────────────────────────────────────────────────────────────────┐
│  Hola, Ana · Cátedra IA-2026-1 · cursada 2026-1                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │  👋  Todavía no hay novedades en esta cátedra                                            │ │
│  │                                                                                           │ │
│  │  Cuando tu profesor publique un aviso, lo vas a ver acá y te va a llegar una               │ │
│  │  notificación. Por ahora podés:                                                          │ │
│  │                                                                                           │ │
│  │  ┌───────────────────────────────────────────┐  ┌────────────────────────────────────┐  │ │
│  │  │ 💬  Preguntale algo a tu profesor        │  │ 📚  Ver el material de la cátedra    │  │ │
│  │  │     por el chat                          │  │     14 materiales                     │  │ │
│  │  │  "Escribí tu consulta…"                  │  │     Prompts, apuntes y PDFs de la      │  │ │
│  │  │                                           │  │     materia                          │  │ │
│  │  └───────────────────────────────────────────┘  └────────────────────────────────────┘  │ │
│  │                                                                                           │ │
│  │  Sugerencia: «¿Qué material leo primero para la TP1?»                                      │ │
│  │  (clic para mandarla al chat)                                                             │ │
│  └─────────────────────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 10.3 Decisiones

1. **La acción principal es el chat**, no "esperar". La consigna del
   enunciado es que el alumno interactúe con el material de la cátedra: la
   primera pantalla debe empujar hacia eso.
2. **La sugerencia es un borrador, no un mensaje**: el alumno lo edita antes de
   mandarlo. Click = lo carga en el compositor del chat.
3. **"Ver el material"** es la segunda acción: si la cátedra subió material,
   el alumno tiene algo que hacer aunque no haya avisos.
4. **El texto es honesto**: "cuando tu profesor publique un aviso…". No se
   promete nada que no dependa de la app ("no hay nada que hacer" sería falso:
   el docente puede publicar un aviso en cualquier momento).
5. **El estado vacío NO es un error**: no hay icono de "error", no hay snackbar,
   no hay spinner. Es una bienvenida.

### 10.4 Los tres estados vacíos distintos

| Estado | Dónde | Qué muestra |
|---|---|---|
| Sin novedades | Tarjeta Novedades | "No tenés novedades. Todo lo que publiquen tus profesores va a aparecer acá." |
| Sin tareas | Tarjeta Tareas | "No tenés tareas pendientes. Cuando tu profesor publique una entrega, te va a aparecer acá con su fecha." |
| Cátedra vacía | Home entera | La tarjeta de bienvenida (§10.2) |
| Sin cátedras | Home entera | "No estás inscripto en ninguna cátedra" + botón "Unirme con un código" + explicación del código de inscripción |

---

## 11. Nombres: archivos, composables, view-models

### 11.1 `:core` (KMP, `commonMain` salvo indicado)

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/Announcement.kt` | **Ya definido** en `07-anuncios.md`: `Announcement`, `Audience`, `AnnouncementAttachment`, `AnnouncementRead`, `AnnouncementComment`. |
| `core/src/commonMain/kotlin/kitoru/domain/AnnouncementPolicy.kt` | **Nuevo**: reglas puras (`visible`, `unreadCount`, `isUnread`, `sortForList`). |
| `core/src/commonMain/kotlin/kitoru/domain/AnnouncementNotifier.kt` | **Ya definido** en `07-anuncios.md` §5.2: decide *qué* notificar. |
| `core/src/commonMain/kotlin/kitoru/domain/StudentFeed.kt` | **Nuevo**: `StudentFeed`, `StudentFeedRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/FileStudentFeedRepository.kt` | **Nuevo**: implementación con `JsonStore` + escritura atómica. |
| `core/src/commonMain/kotlin/kitoru/domain/StudentFeedWriter.kt` | **Nuevo**: arma el feed filtrado por audiencia. |
| `core/src/commonMain/kotlin/kitoru/domain/CommentOutbox.kt` | **Nuevo**: `PendingComment`, `CommentOutbox` (+ `FileCommentOutbox`). |
| `core/src/commonMain/kotlin/kitoru/domain/Assignment.kt` | **Nuevo**: `Assignment`, `AssignmentKind`, `AssignmentProgress`. |
| `core/src/commonMain/kotlin/kitoru/domain/Connectivity.kt` | **Nuevo**: enum `Connectivity`. |
| `core/src/commonMain/kotlin/kitoru/domain/StudentUiState.kt` | **Nuevo**: `StudentUiState(selectedCourseId, seenCommentIds, lastCourseId)` + `UiStateRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | **Cambios**: `+ AssignmentRepository`, `+ AssignmentProgressRepository`, `+ StudentFeedRepository`, `+ CommentOutbox`, `+ UiStateRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` | **Cambios**: `FileAssignmentRepository`, `FileAssignmentProgressRepository`, `FileCommentOutbox`. |
| `core/src/commonMain/kotlin/kitoru/platform/Notifier.kt` | **Ya definido** en `07-anuncios.md`: `LocalNotification`, `LocalNotifier`, `expect createLocalNotifier()`. **Se le agrega** `NotificationTarget`, `NotificationTargetKind`. |
| `core/src/commonMain/kotlin/kitoru/platform/NotificationCenter.kt` | **Nuevo**: `NotificationCenter` (flujo de deep links). |
| `core/src/androidMain/kotlin/kitoru/platform/Notifier.android.kt` | `NotificationManager` + `KitoruAndroid.init(context, activityClassName)`. |
| `core/src/desktopMain/kotlin/kitoru/platform/Notifier.desktop.kt` | `SystemTray` (best effort). |

### 11.2 `:ui` (Compose Multiplatform, `commonMain` salvo indicado)

| Archivo | Composables / funciones |
|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/StudentHomeScreen.kt` | `StudentHomeScreen(vm)` — la home completa. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/StudentHomeViewModel.kt` | `StudentHomeViewModel` (§11.4). |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/StudentHomeUiState.kt` | `StudentHomeUiState`, `CourseChip`, `AnnouncementSummary`, `TaskSummary`, `ChatCardState`, `CourseSummaryState`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/HomeHeader.kt` | `HomeHeader(courses, selected, onSelect, connectivity, onRefresh)` — incluye el selector. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/CourseSelector.kt` | `CourseSelector(courses, selectedId, onSelect)` — `ExposedDropdownMenuBox` con badges. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/ConnectivityChip.kt` | `ConnectivityChip(state, lastSyncAt)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/HomeNovedadesCard.kt` | `HomeNovedadesCard(items, unreadCount, onOpen, onSeeAll)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/HomeTareasCard.kt` | `HomeTareasCard(items, onToggle, onSeeAll)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/HomeChatCard.kt` | `HomeChatCard(state, onAsk, onChangeVisibility)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/HomeCatedraCard.kt` | `HomeCatedraCard(state, onOpenMaterials, onOpenSettings)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/home/HomeEmptyState.kt` | `HomeEmptyState(course, materialsCount, onAsk, onOpenMaterials)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementsScreen.kt` | `AnnouncementsScreen(vm)` — lista. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementsViewModel.kt` | `AnnouncementsViewModel` (§11.4). |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementsUiState.kt` | `AnnouncementsUiState`, `AnnouncementFilter`, `AnnouncementRowModel`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementRow.kt` | `AnnouncementRow(model, onClick)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementDetailScreen.kt` | `AnnouncementDetailScreen(vm)` — detalle. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementDetailViewModel.kt` | `AnnouncementDetailViewModel` (§11.4). |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AnnouncementComments.kt` | `AnnouncementComments(items, canComment, onReply, onDelete)` + `CommentBubble`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/CommentComposer.kt` | `CommentComposer(onSend, enabled, pending)` — con soporte de "Insertar respuesta de la IA". |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AiGeneratedBadge.kt` | `AiGeneratedBadge(model, authorName)` — **compartido** con la pantalla docente. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/AttachmentList.kt` | `AttachmentList(items, onOpen)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/OfflineBanner.kt` | `OfflineBanner(state, onRetry)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/student/announcements/ChatNavigator.kt` | `ChatNavigator` — puerto para abrir el chat con un borrador (§7.3). |
| `ui/src/commonMain/kotlin/kitoru/ui/markdown/MarkdownText.kt` | `MarkdownText`, `parseMarkdown`, `buildInlineAnnotated` — **portado de FunshiEngineAI** (compartido con el chat). |
| `ui/src/commonMain/kotlin/kitoru/ui/ui/Format.kt` | `formatRelative`, `formatDate`, `formatDateTime` (ya previsto en `teacher/07-chats.md`). |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/AppBadge.kt` | `expect fun setAppBadge(count: Int)`. |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/AppBadge.desktop.kt` | `actual`: sufijo en el título de la ventana. |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/AppBadge.android.kt` | `actual`: no-op (el badge del lanzador no es portable). |

### 11.3 Puertos de plataforma

| Puerto | commonMain | desktopMain | androidMain |
|---|---|---|---|
| `createLocalNotifier()` | `LocalNotifier` + `expect` | `SystemTray` | `NotificationManager` |
| `setAppBadge(count)` | `expect` | título de la ventana | no-op |
| `KitoruAndroid.init(context, activityClassName)` | — | — | contexto de la app |

Se pasan por constructor a los view-models (inyección manual, como
`FileSystem`), de modo que los tests usen un doble en memoria:

```kotlin
/** Doble de pruebas: no toca el sistema operativo. */
class FakeNotifier : LocalNotifier {
    val posted = mutableListOf<LocalNotification>()
    var enabled: Boolean = true
    override fun areEnabled(): Boolean = enabled
    override fun post(notification: LocalNotification) { posted += notification }
    override fun cancel(id: String) { posted.removeAll { it.id == id } }
    override fun cancelAllForCourse(courseId: String) { posted.removeAll { it.target.courseId == courseId } }
}
```

### 11.4 View-models y estado

```kotlin
class StudentHomeViewModel(
    private val user: UserProfile,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val feeds: StudentFeedRepository,
    private val feedWriter: StudentFeedWriter,
    private val reads: AnnouncementReadRepository,
    private val assignments: AssignmentRepository,
    private val progress: AssignmentProgressRepository,
    private val uiState: UiStateRepository,
    private val notifier: LocalNotifier,
) {
    val state: StateFlow<StudentHomeUiState>
}
```

```kotlin
data class StudentHomeUiState(
    val loading: Boolean = true,
    /** Cátedras del alumno, para el selector. */
    val courses: List<CourseChip> = emptyList(),
    val selectedCourseId: String = "",
    val course: Course? = null,
    val enrollment: Enrollment? = null,
    /** Avisos sin leer (máx. 3 para la tarjeta). */
    val novedades: List<AnnouncementSummary> = emptyList(),
    /** Total de no leídos (para el badge del ícono). */
    val unreadCount: Int = 0,
    /** Tareas pendientes (máx. 3). */
    val tareas: List<TaskSummary> = emptyList(),
    val pendingTasksCount: Int = 0,
    val chat: ChatCardState = ChatCardState(),
    val catedra: CourseSummaryState = CourseSummaryState(),
    val connectivity: Connectivity = Connectivity.UNKNOWN,
    val lastSyncAt: Long = 0L,
    /** La cátedra no tiene nada: mostrar la bienvenida (§10). */
    val welcome: Boolean = false,
    val message: String = "",   // snackbar
)
```

```kotlin
data class CourseChip(
    val id: String,
    val code: String,
    val name: String,
    val unreadCount: Int,
)

data class AnnouncementSummary(
    val id: String,
    val title: String,
    val excerpt: String,
    val authorName: String,
    val createdAt: Long,
    val pinned: Boolean,
    val aiGenerated: Boolean,
    val aiModel: String,
    val commentCount: Int,
    val attachmentCount: Int,
    val read: Boolean,
)

data class TaskSummary(
    val id: String,
    val title: String,
    val kind: AssignmentKind,
    val dueAt: Long,
    val overdue: Boolean,
    val done: Boolean,
    val checkable: Boolean,   // false para EXAMEN
)

data class ChatCardState(
    val modelLabel: String = "",
    val topK: Int = 0,
    val usesCourseMaterial: Boolean = true,
    val visibility: ChatVisibility = ChatVisibility.FULL,
    val draft: String = "",
)
```

```kotlin
class AnnouncementsViewModel(
    private val user: UserProfile,
    private val courseId: String,
    private val enrollment: Enrollment,
    private val feeds: StudentFeedRepository,
    private val reads: AnnouncementReadRepository,
    private val comments: AnnouncementCommentRepository,
    private val outbox: CommentOutbox,
) {
    val state: StateFlow<AnnouncementsUiState>
}

data class AnnouncementsUiState(
    val loading: Boolean = true,
    val rows: List<AnnouncementRowModel> = emptyList(),
    val filter: AnnouncementFilter = AnnouncementFilter.TODAS,
    val unreadCount: Int = 0,
    val totalCount: Int = 0,
    val connectivity: Connectivity = Connectivity.UNKNOWN,
    val lastSyncAt: Long = 0L,
    val message: String = "",
)

enum class AnnouncementFilter(val label: String) {
    TODAS("Todas"), SIN_LEER("Sin leer"), FIJADAS("Fijadas"),
}
```

```kotlin
class AnnouncementDetailViewModel(
    private val user: UserProfile,
    private val announcementId: String,
    private val enrollment: Enrollment,
    private val feeds: StudentFeedRepository,
    private val reads: AnnouncementReadRepository,
    private val comments: AnnouncementCommentRepository,
    private val outbox: CommentOutbox,
    private val chatNavigator: ChatNavigator,   // puerto: abre el chat con un borrador
) {
    val state: StateFlow<AnnouncementDetailUiState>
}

data class AnnouncementDetailUiState(
    val loading: Boolean = true,
    val announcement: Announcement? = null,
    val comments: List<CommentItem> = emptyList(),
    val readAt: Long = 0L,          // 0 = no leído todavía
    val canComment: Boolean = true,
    val composerText: String = "",
    val sending: Boolean = false,
    val pendingComments: Int = 0,   // en el outbox
    val connectivity: Connectivity = Connectivity.UNKNOWN,
)
```

El puerto de navegación al chat (para §7.3) es una interfaz pequeña, para que
el view-model sea testeable:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/announcements/ChatNavigator.kt
interface ChatNavigator {
    /** Abre el chat con una consulta pre-cargada. */
    fun openWithDraft(title: String, draft: String)
}
```

---

## 12. Casos borde

| Caso | Comportamiento |
|---|---|
| El alumno tiene **0 cátedras** | La home muestra "No estás inscripto en ninguna cátedra" + "Unirme con un código". No hay selector. |
| El alumno tiene **1 cátedra** | No hay selector: la cabecera muestra el nombre. |
| Cambia de cátedra | Se recargan las 4 tarjetas; el feed de la otra cátedra **no se borra**; la selección persiste. |
| La cátedra no tiene modelo configurado | La tarjeta de Chat muestra el chip "El modelo de la cátedra no está configurado" y el campo de consulta queda deshabilitado con el motivo (mensajes de `ModelPolicy.validate()`). |
| La cátedra no tiene material | El chat funciona igual (el RAG devuelve 0 fragmentos); la tarjeta lo dice: "Todavía no hay material de la cátedra". |
| El modelo remoto no responde | La tarjeta de Chat muestra el error del gateway (`LlmException`, en español). Los avisos **no** se ven afectados: son locales. |
| Aviso `STAFF` | **No aparece** en el feed del alumno (se filtra al escribir el feed, no al pintar). |
| El alumno se inscribe después de publicado el aviso | Lo ve en "Anteriores" con el chip "Anterior a tu inscripción"; no cuenta como no leído ni dispara notificación. |
| El docente edita un aviso ya leído | Los que lo leyeron **siguen** con el badge de leído (no se "des-lee"); los que no lo leyeron lo ven como no leído. |
| El docente borra un aviso | Se borra en cascada lecturas y comentarios; el feed del alumno se regenera y el aviso desaparece. |
| El alumno marca "todo como leído" | Un `AnnouncementRead` por aviso, con el mismo `readAt`; el badge queda en 0. |
| El alumno marca "no leído" un aviso | Se borra su registro de lectura; el aviso vuelve a la sección "No leídos" y el badge sube. |
| Comentario con red caída | Se encola en el outbox, se muestra en el hilo con el chip "pendiente de enviar" y sale al volver la conexión. |
| Comentario mientras `commentsEnabled = false` | El compositor no aparece: "El profesor cerró las preguntas de este aviso." |
| El alumno es ayudante y entra a la app del alumno | Puede usar la home; los avisos `STAFF` los ve (su rol los incluye). La tarjeta de visibilidad muestra su propia inscripción. |
| Un aviso con 30 comentarios | La lista de la home **no** muestra el conteo de comentarios de los leídos; el detalle hace scroll. |
| Un aviso sin `body` (sólo título) | Se muestra el título y un cuerpo vacío; no se rompe el render. |
| Markdown mal formado (código sin cerrar) | El parser portable lo marca (`closed = false`) y lo dibuja igual, sin perder texto. |
| La caché del feed está corrupta | `read` devuelve `null` (el `runCatching` lo traga) y la app regenera el feed desde el store local. |
| `fetchedAt` muy viejo (> 7 días) | La cabecera muestra "mostrando lo de hace 8 días" en ámbar. |
| Dos anuncios con el mismo título | Se permiten; el id los distingue. |
| El alumno tiene 200 avisos sin leer | La tarjeta muestra 3 y el pie "Ver todos los avisos (200)"; el badge muestra "200+" (no "200+999"). |

---

## 13. Criterios de aceptación

1. Con una cátedra, la home muestra cuatro tarjetas: Novedades (con los no
   leídos), Tareas pendientes, Chat y Tu cátedra.
2. Con dos o más cátedras, la cabecera tiene selector; el badge de no leídos
   de cada cátedra es correcto; la selección persiste entre reinicios.
3. Los avisos sin leer aparecen en la tarjeta de Novedades **ordenados por
   fecha** (fijados primero) y el punto de no leído se va al abrir el aviso.
4. Abrir un aviso **crea un `AnnouncementRead`** con el `readAt` de la primera
   lectura; reabrirlo no cambia la fecha.
5. "Marcar todo como leído" deja el badge en 0 y crea un registro por aviso.
6. El cuerpo del aviso se renderiza con el parser portable: los encabezados,
   listas, citas, tablas y bloques de código se dibujan correctamente y el
   Markdown crudo **no** se muestra.
7. Todo aviso con `aiGenerated = true` muestra el chip "Redactado con IA",
   **aunque el cuerpo no tenga la línea de crédito**.
8. Un aviso escrito a mano por el docente **no** muestra chip ni crédito.
9. El alumno puede escribir un comentario en el aviso; el comentario aparece en
   el hilo con su autor y su fecha, y lo ven los demás alumnos.
10. "Preguntarle a la IA sobre este aviso" abre el chat con el aviso de
    contexto y la pregunta del alumno; la respuesta se puede insertar en el
    comentario, que queda marcado como generado por IA.
11. El botón "Marcar todo como leído" no existe si no hay no leídos; el botón
    "Marcar no leído" del detalle borra el registro del alumno.
12. En Android, un aviso nuevo publica una notificación del sistema con el
    título del aviso y abre el detalle al tocarla; en desktop, si hay bandeja
    del sistema, aparece un globo; si no hay bandeja, la app **no** falla y
    muestra el banner interno.
13. Con la app en modo sin conexión, la home, la lista y el detalle de avisos
    **se abren igual** y muestran el contenido cacheado, con el chip "Sin
    conexión · mostrando lo de hace 2 h".
14. Un alumno recién inscripto sin avisos ni tareas ve la tarjeta de
    bienvenida con "Preguntale algo a tu profesor por el chat" como acción
    principal.
15. El badge de no leídos de la barra/riel es **derivado** de
    `AnnouncementRead`: no hay ningún contador guardado que se pueda
    desincronizar.
16. Un aviso de audiencia `STAFF` **no está** en el archivo de feed del alumno
    (`cache/feeds/<courseId>.json`): verificado con un test que abre el
    archivo y busca el id del aviso.

---

## 14. Dependencias con otros documentos

| Otro doc | Relación |
|---|---|
| `domain/07-anuncios.md` | Define `Announcement`, `Audience`, `AnnouncementRead`, `AnnouncementComment`, `LocalNotifier` y `AnnouncementNotifier`. Este doc los **usa**. Dos puntos a alinear: (a) la columna "fecha de primera lectura" en el panel de lecturas del docente (§6.2); (b) el aviso de que el `LocalNotifier` ahora lleva `NotificationTarget` en lugar de `targetId` suelto. |
| `domain/08-permisos.md` | La matriz de capacidades **no tiene área de anuncios**. Proponer un área nueva `CapabilityArea.ANUNCIOS` con: `ANNOUNCEMENT_VIEW` (todos), `ANNOUNCEMENT_COMMENT` (todos, condicional a `commentsEnabled`), `ANNOUNCEMENT_MARK_READ` (todos), `ANNOUNCEMENT_READERS_VIEW` (TITULAR/AYUDANTE), `ANNOUNCEMENT_PUBLISH` (TITULAR), `ANNOUNCEMENT_GENERATE_WITH_AI` (TITULAR). Mientras no exista, estas pantallas no usan la matriz: chequean rol y `audience` directamente. |
| `teacher/07-chats.md` | Define `MarkdownText` en `ui/src/commonMain/kotlin/kitoru/ui/markdown/` y la convención de view-models (`state: StateFlow<…UiState>`, inyección manual). Este doc los reutiliza. También define la decisión de mostrar LaTeX como texto monoespaciado. |
| Chat del alumno | La tarjeta de Chat de la home abre el chat con un borrador; el `ChatNavigator` es el puerto entre las dos pantallas. El doc del chat define el `?draft=` y el botón "Insertar en el comentario". |
| Pantalla de tareas (docente) | Define `Assignment` con más detalle. Este doc declara el contrato mínimo que necesita la home (§3.4). |
| Pantalla de materiales | `Announcement.attachments` se abre con el mismo componente de material. |
| Kitoru-Server | En el hito 2, el servidor es quien escribe `StudentFeed` y quien vacía el outbox. La interfaz `StudentFeedRepository` es el punto de cambio. |

---

## 15. Fuera de alcance (explícito)

- **Notificaciones con la app cerrada** en desktop (no hay daemon) y en Android
  (no hay servidor todavía): el `WorkManager` queda como stub.
- **Push real** (FCM/APNs).
- **Réplica**: un "me gusta" o un emoji en los avisos.
- **Búsqueda de avisos** (se agrega cuando una cátedra tenga más de 50 avisos).
- **Suscripción por etiqueta** ("quiero sólo los de examen").
- **Entrega de tareas dentro de la app**: la casilla es local ("ya lo hice"),
  no es un sistema de entregas ni de corrección.
- **Comentarios anónimos** y **hilos de más de un nivel**.
- **Exportar un aviso a PDF o a `.ics`**.
- **Vista del docente de los comentarios del alumno fuera del aviso**: el
  docente los ve en el propio aviso (modera) y en la analítica agregada.
