# 10 · Onboarding y primera ejecución de Kitoru

> Subagente: diseño del **onboarding** y de la **primera ejecución** de Kitoru.
> Módulos tocados: `:core` (dominio + plataforma), `:ui` (Compose Multiplatform),
> `:backend` (sólo reuso: `KnowledgeBase.sync`, `Chunker`, `Bm25Index`,
> `CitationVerifier`).
> Documentos de referencia leídos: `docs/design/CONTEXTO.md`,
> `docs/design/student/01-entrada.md`, `docs/design/student/02-chat.md`,
> `docs/design/student/06-home-anuncios.md`, `docs/design/domain/01-catedra.md`,
> `docs/design/domain/03-inscripciones.md`, `docs/design/domain/09-identidad.md`,
> `docs/design/teacher/01-navegacion.md`, `docs/design/teacher/02-editor-catedra.md`,
> `docs/design/teacher/03-materiales.md`, `docs/design/teacher/04-prompt.md`,
> `docs/design/teacher/09-calidad.md`, `docs/design/security/02-privacidad.md`,
> `docs/design/sync/02-offline.md`, `docs/design/process/01-packaging-ci.md`,
> `docs/design/process/02-roadmap.md`.
> Código de referencia: `FunshiEngineAI/backend/src/main/kotlin/rag/config/Config.kt`
> (el `loadOrCreate()`), `FunshiEngineAI/frontend/src/main/kotlin/app/chat/LoadingPane.kt`.
>
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast con mensajes en español*, *el
> estudiante conserva agencia*, *el docente no ve lo que no debe*.
>
> **Estado: diseño. Este documento no escribe código.** Todo lo que aparece
> como Kotlin es una *propuesta concreta* para copiar al repo.

---

## 0. Decisiones en una línea

| # | Decisión | Respuesta | Motivo |
|---|---|---|---|
| D1 | La primera ejecución **no es una pantalla**, es un **estado derivado de los datos** | `AppEntryState = FirstRun \| NoCourses \| Ready`, calculado con `appEntryState(profile, enrollments)` | Un flag "ya hice el onboarding" guardado en un JSON se desincroniza del primer dato real. Ya está diseñado en `student/01-entrada.md` §2.1: se reusa, no se re-inventa. |
| D2 | **No hay selector de rol** en el primer arranque | El rol lo da el código (alumno) o el botón "Crear una cátedra" (docente) | El rol vive en `Enrollment`, es por cátedra. Ya decidido en `student/01-entrada.md` §2.3. |
| D3 | El docente tiene **dos puertas**, no un menú | "Unirme a una cátedra" (principal) y "Crear una cátedra" (secundaria) | El alumno es el caso normal; el docente no es un caso degradado, es otra puerta a la misma puerta de entrada. |
| D4 | El recorrido del docente es **un wizard corto (2 pasos) + un checklist permanente** | Wizard: nombre → crear la cátedra. Checklist: material, modelo, prueba, publicar, invitar | Un wizard de 6 pasos obligatorio es una jaula: el docente muchas veces no tiene el PDF a mano en ese momento. El checklist acompaña, no bloquea. |
| D5 | El objetivo medible es **5:00 hasta "la cátedra está creada, con código activo y lista para invitar"** | Presupuesto por paso en §3.10 | "Lista" significa: código generado, modelo configurado (o diferido con aviso), probado al menos una vez. El material puede quedar para después. |
| D6 | El alumno ve **una sola pantalla de privacidad**, y en ella **elige** IA alojada o local | Texto A' (§4.3) + variante local | Un aviso que hay que aceptar y después una pantalla aparte para elegir el modelo son dos momentos de confianza. La elección *es* parte del aviso. |
| D7 | El aviso de privacidad **lista qué ve el docente y qué NO** | Texto literal en §4.3, con versión + hash (`ConsentTexts`) | Es el momento de confianza de la app. La versión/hash ya están definidos en `security/02-privacidad.md` §3.7: se reusan y se **extienden** (bumpear `ConsentTexts.VERSION`). |
| D8 | Se ofrece una **cátedra de ejemplo**, y es **una cátedra real** marcada como tal | `Course.sample = true`, material real en `resources/sample/`, código fijo | Nadie abre la app ante un vacío. Y es una cátedra de verdad (se puede chatear, ver citas, ver analítica): un mock no prueba nada. |
| D9 | La cátedra de ejemplo **nunca se borra sola** | Se ofrece borrarla (1 clic + confirmación) y se ofrece restablecerla | Borrar datos sin preguntar contradice todo `security/02-privacidad.md`. El docente puede haber usado la demo en clase de verdad. |
| D10 | El primer chat del alumno es **un hilo sembrado**, no un mensaje del modelo | `ChatMessage.origin = GUIDED_INTRO`, generado en el dispositivo, sin red | Cero latencia, cero costo, funciona sin conexión, y le muestra al alumno **qué** va a pasar antes de que pase. |
| D11 | El mensaje inicial del tutor **se adapta al material real** | Las sugerencias se derivan de los títulos de los materiales visibles | "Probalá con esto" sólo sirve si la pregunta se puede responder con lo que hay. |
| D12 | La indexación se muestra **por fases con progreso real**, y termina con **una pregunta de prueba** | `MaterialStatus` (`PENDING/EXTRACTING/INDEXING/READY/NEEDS_REVIEW/FAILED`) + prueba BM25 sin modelo | El docente tiene que entender que el PDF se *partió*, y ver el fragmento recuperado. "Si la IA no encuentra lo que busco, el problema es el material" (`teacher/03-materiales.md`). |
| D13 | La **pregunta de prueba** se responde en dos niveles: "con el material" (BM25, sin red) y "con el modelo" (RAG completo) | Dos botones | El primero aísla el material; el segundo demuestra el resultado final. Es el ciclo de mejora del docente. |
| D14 | El docente tiene un **modo de prueba** del chat antes de invitar a nadie | Hilo `SMOKE_TEST`, excluido de la analítica, con cinta permanente | Es lo que convierte al docente en el producto: prueba su cátedra con un alumno antes de exponerla. |
| D15 | La migración de datos **nunca repite el onboarding** | El estado de entrada es derivado; la migración sólo cambia el `schemaVersion` | Si el onboarding dependiera de un flag, cada actualización rompería el estado. |
| D16 | La migración se **anuncia pero no bloquea** (salvo app vieja sobre datos nuevos) | Aviso de una línea + "Ver qué cambió" | El usuario tiene que saber que sus datos se movieron, no que la app se rompió. |
| D17 | Sin red, la inscripción **es local**; lo que necesita red se explica | Matriz §10 | La inscripción es un dato local. El material y el modelo son lo que viaja. |
| D18 | Los textos del onboarding son **literales, versionados y hasheados** | `OnboardingTexts` + `ConsentTexts` | Es el mismo mecanismo que ya usa el doc de privacidad: el texto exacto que se mostró es prueba de qué se consintió. |

---

## 1. Qué es "la primera ejecución"

### 1.1 Dos journeys, dos puntos de entrada

Kitoru tiene dos maneras de entrar por primera vez y **no se parecen en nada**:

| | **DOCENTE** | **ALUMNO** |
|---|---|---|
| De dónde viene | Instala la app, no tiene nada | Instala la app, ya tiene un código en el bolsillo |
| Pregunta inicial | "¿Cómo hago una cátedra?" | "¿Cómo me meto a la mía?" |
| Tiene que inventar | Nombre, código, cursada | Nada: tiene el código |
| Tiene que conseguir | Material, modelo, alumnos | Sólo el código (y el modelo, si la cátedra no lo provee) |
| Momento de mayor riesgo | **Abandono por complejidad** (el wizard asusta) | **Abandono por desconfianza** (el aviso asusta) |
| Objetivo | Cátedra creada y lista para invitar en **5 min** | Entra, entiende el modelo de confianza y hace **una pregunta** |

Los dos journeys **comparten el mismo esqueleto de arranque** (§2) y **no se
mezclan nunca en una misma pantalla**: el docente no ve el wizard de
inscripción y el alumno no ve el editor de cátedra.

### 1.2 El estado de entrada es derivado, no guardado

Se reusa tal cual lo decidido en `student/01-entrada.md` §2.1:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AppEntryState.kt

/** En qué punto del flujo está la persona al abrir la app. */
sealed interface AppEntryState {
    /** Primer arranque: no hay perfil local. */
    data object FirstRun : AppEntryState
    /** Ya hay perfil, pero ninguna inscripción. */
    data object NoCourses : AppEntryState
    /** Todo listo: home. */
    data object Ready : AppEntryState
}

/**
 * Estado de entrada de la app.
 *
 * Se deriva del perfil local y de las inscripciones: no se guarda un flag
 * "ya hice el onboarding" que se pueda desincronizar de los datos.
 */
fun appEntryState(profile: UserProfile?, enrollments: List<Enrollment>): AppEntryState = when {
    profile == null -> AppEntryState.FirstRun
    enrollments.isEmpty() -> AppEntryState.NoCourses
    else -> AppEntryState.Ready
}
```

**Por qué esto importa para el onboarding:** no existe "la pantalla de
onboarding" que haya que saltarse. Si la persona tiene una inscripción, ve su
home; si no, ve la bienvenida. Una **migración de esquema** (§8) o un
**restablecimiento de la cátedra de ejemplo** (§5) no pueden desincronizar
nada, porque no hay nada que sincronizar.

El único dato que el onboarding **escribe** es el `UserProfile` (nombre local)
y, en el caso del docente, la `Course` y su `Enrollment` de rol `TITULAR`.

### 1.3 Qué pasa en los primeros 60 segundos (arranque en frío)

| Segundos | Qué hace la app | Qué ve |
|---|---|---|
| 0,0 – 0,2 | Fija `KitoruPaths.dataDir`, crea las carpetas, crea `config/app-config.json` | (nada: splash con el logo) |
| 0,2 – 0,4 | Carga perfil, cátedras, inscripciones | `LoadingPane` con fases (port de `LoadingPane.kt` de FunshiEngineAI) |
| 0,4 – 0,6 | Migra si hace falta (§8) | Aviso de una línea, si hubo migración |
| 0,6 – 1,0 | Resuelve `AppEntryState` | Pantalla de bienvenida, wizard o home |

**Regla dura:** el arranque **no consulta la red de forma bloqueante**. La app
abre en menos de 1 segundo con o sin internet (`sync/02-offline.md` §6.5).

---

## 2. El arranque en frío: qué se crea en el disco

Este es el análogo directo de `AppConfig.loadOrCreate()` de
`FunshiEngineAI/backend/src/main/kotlin/rag/config/Config.kt`. Se reusa la
**forma** (crear lo que falta, no pisar lo que existe, migrar al final) y se
cambia el **contenido** por el de Kitoru.

### 2.1 La carpeta

`KitoruPaths` ya existe en `:core` y ya define la forma:

```text
<dataDir>/
  config/
    app-config.json          ← un solo archivo: versión de esquema + ajustes
    profiles.json            ← lista
    courses.json             ← lista
    enrollments.json         ← lista
    materials.json           ← lista (metadatos; el texto va aparte)
    threads.json             ← lista
    settings.json            ← ajustes del dispositivo
    credentials.json         ← almacén de secretos (desktop) / EncryptedSharedPreferences (Android)
    backups/                 ← una copia por versión de esquema migrada
    templates/
      system_prompt.md       ← plantilla de instrucciones de cátedra
  courses/
    <courseId>/
      files/                 ← originales subidos por el docente
      text/                  ← texto extraído
      index/                 ← índice BM25 serializado
      threads/<threadId>.json
      outputs/
  cache/
```

`dataDir`: escritorio `~/.kitoru`, Android `context.filesDir/kitoru`
(`KitoruPaths.kt` ya lo documenta).

### 2.2 El `loadOrCreate()` de Kitoru

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AppBootstrap.kt

/**
 * Arranque en frío: crea lo que falta y migra lo que está viejo.
 *
 * Es el análogo de `AppConfig.loadOrCreate()` de FunshiEngineAI, con la misma
 * forma (crear si falta, no pisar si existe, migrar al final) y el contenido
 * de Kitoru.
 *
 * Es una función **por-effects** y **determinista**: recibe el sistema de
 * archivos, no lo busca por dentro. Así se testea en memoria.
 */
object AppBootstrap {

    /**
     * @param fs sistema de archivos (real o `InMemoryFileSystem`).
     * @param paths raíz de datos ya fijada.
     * @return el resultado del arranque, con lo que la UI necesita para pintar.
     */
    suspend fun loadOrCreate(fs: FileSystem, paths: KitoruPaths): BootResult
}

/** Lo que el arranque devuelve: la config migrada y lo que pasó. */
data class BootResult(
    /** Configuración ya migrada a la versión actual. */
    val config: AppConfig,
    /** Versión de esquema encontrada antes de migrar (0 = instalación nueva). */
    val migratedFrom: Int,
    /** Aviso a mostrar por la migración (vacío = no hubo migración). */
    val migrationNotice: String,
    /** La app se bloquea porque los datos son de una versión más nueva. */
    val blocked: Boolean = false,
    /** Mensaje de bloqueo (si `blocked`). */
    val blockedMessage: String = "",
)
```

El cuerpo, en detalle:

```kotlin
suspend fun loadOrCreate(fs: FileSystem, paths: KitoruPaths): BootResult {
    // 1. Carpetas base.
    fs.createDirectories(paths.configDir)
    fs.createDirectories(paths.coursesDir)
    fs.createDirectories(paths.cacheDir)
    fs.createDirectories(paths.configDir.resolve("backups"))
    fs.createDirectories(paths.configDir.resolve("templates"))

    // 2. El archivo de configuración: se crea si no existe, nunca se pisa.
    val configFile = paths.configDir.resolve("app-config.json")
    if (!fs.exists(configFile)) {
        fs.writeText(configFile, KitoruJson.encodeToString(AppConfig.empty()))
    }

    // 3. La plantilla de instrucciones de cátedra (equivalente al
    //    `default_system_prompt.md` de FunshiEngineAI, pero por cátedra).
    val template = paths.configDir.resolve("templates/system_prompt.md")
    if (!fs.exists(template)) {
        fs.writeText(template, DEFAULT_SYSTEM_PROMPT)
    }

    // 4. Los archivos por tipo: se crean vacíos para que existan (y para que
    //    `JsonStore` no tenga que adivinar). Con `[]` como contenido.
    for (name in EMPTY_LIST_FILES) {
        val f = paths.configDir.resolve(name)
        if (!fs.exists(f)) fs.writeText(f, "[]")
    }

    // 5. Migración: copia de seguridad antes de tocar nada.
    val raw = KitoruJson.decodeFromString<AppConfig>(fs.readText(configFile))
    if (raw.schemaVersion > SchemaVersion.CURRENT) {
        // App vieja sobre datos nuevos: NO se escribe nada.
        return BootResult(raw, raw.schemaVersion, "", blocked = true,
            blockedMessage = SchemaTooNewMessage(raw.schemaVersion))
    }
    if (raw.schemaVersion == SchemaVersion.CURRENT) {
        return BootResult(raw, raw.schemaVersion, "")
    }
    fs.writeText(
        paths.configDir.resolve("backups/app-config.v${raw.schemaVersion}.json"),
        fs.readText(configFile),
    )
    val migrated = Migrations.migrate(raw, raw.schemaVersion)
    fs.writeText(configFile, KitoruJson.encodeToString(migrated))
    return BootResult(migrated, raw.schemaVersion, Migrations.noticeFor(raw.schemaVersion))
}
```

**Diferencias con el `loadOrCreate()` de FunshiEngineAI, y por qué:**

| FunshiEngineAI | Kitoru | Por qué |
|---|---|---|
| `AppPaths.home` por `DOCS_CHATBOT_HOME` / `user.home/.docs-chatbot` | `KitoruPaths.dataDir` (`~/.kitoru`) | Ya existe en `:core`; no se duplica. |
| Crea `config/app-config.json` | Crea `config/app-config.json` (mismo nombre, por simetría) | Reutiliza el nombre y el `Json` (`ignoreUnknownKeys`, `encodeDefaults`). |
| Crea `config/system_prompt.md` desde `/default_system_prompt.md` | Crea `config/templates/system_prompt.md` | En Kitoru el prompt es **por cátedra** (`Course.systemPrompt`), no global. La plantilla se copia al crear la cátedra. |
| Crea `internal-docs/bienvenida.md` | Crea `config/materials-demo/` desde `resources/sample/` | El "bienvenido" de FunshiEngineAI es la carpeta de docs internas; en Kitoru el equivalente es la **cátedra de ejemplo** (§5). |
| `migrate()` al final, con `llm.configVersion` | `migrate()` al final, con `schemaVersion` en `app-config.json` | Mismo patrón, migraciones testeadas (`process/01-packaging-ci.md` §6.2). |
| Reactiva `buildFromSource` si estaba en `false` | Reactiva lo que corresponda en la v1 | Se documenta cada "fix-up" en la nota de la versión, como FunshiEngineAI lo hace con `buildFromSource`. |

### 2.3 Los archivos que nacen

| Archivo | Contenido inicial | Quién lo escribe |
|---|---|---|
| `config/app-config.json` | `AppConfig.empty()` con `schemaVersion = CURRENT` | `AppBootstrap` |
| `config/profiles.json` | `[]` | `AppBootstrap` |
| `config/courses.json` | `[]` | `AppBootstrap` |
| `config/enrollments.json` | `[]` | `AppBootstrap` |
| `config/materials.json` | `[]` | `AppBootstrap` |
| `config/threads.json` | `[]` | `AppBootstrap` |
| `config/settings.json` | `AppSettings()` con `deviceId` nuevo | `AppBootstrap` |
| `config/credentials.json` | `{}` (desktop) / `EncryptedSharedPreferences` (Android) | `AppBootstrap` |
| `config/templates/system_prompt.md` | La plantilla de instrucciones (§3.2) | `AppBootstrap` |
| `config/backups/` | vacía | `AppBootstrap` |
| `courses/` | vacía | `AppBootstrap` |

**Lo que NO se crea:** nada de red, nada de descargas, nada de modelos. El
modelo GGUF se descarga **la primera vez que alguien elige IA local**, no en el
arranque (`process/01-packaging-ci.md` D10).

### 2.4 La pantalla de carga (port de `LoadingPane.kt`)

Se porta el patrón de `FunshiEngineAI/frontend/.../app/chat/LoadingPane.kt`:
`LinearProgressIndicator`, etiqueta de fase, porcentaje, tamaño, botón de
cancelar en las descargas, botón "Reintentar" en el error.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/LoadingPane.kt

/**
 * Pantalla de carga del arranque con progreso REAL por fases.
 *
 * PORT del `LoadingPane` de FunshiEngineAI, con las fases de Kitoru.
 */
@Composable
fun LoadingPane(vm: AppViewModel) {
    // título fijo: "Preparando Kitoru…"
    // subtítulo: vm.status
    // barra: LinearProgressIndicator(progress = { pct/100f })
    // línea: "${p.label} — $pct% · ${humanSize(done)} / ${humanSize(total)}"
    // botón "Cancelar" sólo si la fase es una descarga
    // botón "Reintentar" si vm.error no está vacío
}
```

Fases de Kitoru (en orden):

| Fase | Etiqueta | Barra |
|---|---|---|
| 1 | "Leyendo tu perfil" | indeterminado |
| 2 | "Cargando tus cátedras" | indeterminado |
| 3 | "Cargando las inscripciones" | indeterminado |
| 4 | "Actualizando tus datos" (sólo si hubo migración) | indeterminado |
| 5 | "Descargando el modelo" (sólo IA local, con %) | determinado |

**Regla:** el `status` es siempre una frase en español que dice **qué está
haciendo**, no un nombre de método. Nunca se ve "loading courses.json".

---

## 3. El flujo del docente

### 3.0 Mapa del recorrido

```
┌─ WelcomeScreen ────────────────────────────────────────────────────┐
│  Unirme a una cátedra (principal) │ Crear una cátedra (secundaria)  │
└───────────────┬───────────────────────────┬────────────────────────┘
                │                           │
                │ (alumno, §4)             │ (docente)
                ▼                           ▼
        JoinCourseScreen          ┌─ TeacherCourseWizard ───────────┐
                                 │ Paso 1 · Tu nombre              │
                                 │ Paso 2 · Crear la cátedra      │
                                 └───────────────┬──────────────────┘
                                                 ▼
                                 ┌─ TeacherDashboard ─────────────┐
                                 │  "Puesta en marcha" (checklist)  │
                                 │   ○ Subir el material           │
                                 │   ○ Configurar el modelo        │
                                 │   ○ Probar el chat  (§9)        │
                                 │   ○ Publicar                    │
                                 │   ○ Invitar a los alumnos       │
                                 └─────────────────────────────────┘
```

El checklist **es** el resto del flujo. No es un adorno: es el mecanismo que
permite que el docente haga las cosas en el orden que puede (a veces tiene el
PDF, a veces no; a veces ya tiene la clave del servidor, a veces se la dan
mañana).

### 3.1 Paso 0 — La primera pantalla (`WelcomeScreen`)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                                  Kitoru                                 │
│              La IA de tu cátedra, en tu computadora.                    │
│                                                                          │
│   ┌────────────────────────────────────────────────────────────┐        │
│   │  Unirme a una cátedra                                      │        │
│   │  Escribí el código que te dio tu docente.                   │        │
│   └────────────────────────────────────────────────────────────┘        │
│                                                                          │
│   Crear una cátedra (soy docente)                                       │
│                                                                          │
│   ┌────────────────────────────────────────────────────────────┐        │
│   │  Probar con una cátedra de ejemplo                         │        │
│   │  Tenés material de muestra para probar la app sin subir     │        │
│   │  nada tuyo. Podés borrarla cuando quieras.                 │        │
│   └────────────────────────────────────────────────────────────┘        │
│                                                                          │
│   ¿Qué es Kitoru?  ·  Estado de la conexión: ● Conectado                │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Kitoru` |
| Subtítulo | `La IA de tu cátedra, en tu computadora.` |
| Botón principal | `Unirme a una cátedra` |
| Ayuda del principal | `Escribí el código que te dio tu docente.` |
| Botón secundario | `Crear una cátedra (soy docente)` |
| Tarjeta de ejemplo | `Probar con una cátedra de ejemplo` |
| Ayuda de la ejemplo | `Tenés material de muestra para probar la app sin subir nada tuyo. Podés borrarla cuando quieras.` |
| Enlace | `¿Qué es Kitoru?` |

**Decisiones de diseño de esta pantalla:**

1. **El botón principal es el del alumno**, no el del docente. La mayoría de
   los que instalan Kitoru son alumnos, y el caso "tengo un código" es el que
   se resuelve solo. El docente que llega a Kitoru primero busca "crear
   cátedra", y la encuentra a la vista con un verbo explícito y su rol
   entre paréntesis.
2. **La tarjeta de ejemplo es una tarjeta, no un enlace**: tiene título, dos
   líneas de explicación y consequences. Es una invitación, no un botón
   engañoso. Es el camino que usan los evaluadores y el soporte.
3. **"¿Qué es Kitoru?"** abre un diálogo con tres viñetas: qué hace
   (responde desde el material de tu cátedra), qué no hace (no es Google: no
   busca en internet), y quién ve qué (tu docente puede ver el texto de tus
   conversaciones si usás la IA de la cátedra). Es el aviso de privacidad
   **antes** de que el usuario tenga que decidir nada.
4. **El estado de conexión va en el pie**, no en una cinta superior: en el
   primer arranque la conexión casi nunca importa (todo lo local funciona). La
   cinta se reserva para el chat (§10).

### 3.2 Paso 1 — Tu nombre

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Crear una cátedra                                          Paso 1 de 2│
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ¿Cómo te llamás?                                                       │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Ana Gómez                                                │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  Es el nombre que muestra Kitoru en esta computadora. Tus alumnos no lo  │
│  ven: en la cátedra aparecés con el nombre que elijas cuando te sumes.  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ 📄 Plantilla de las instrucciones de la cátedra            │            │
│  │ Eres el asistente de {MATERIA}. Respondé siempre a partir  │            │
│  │ del material de la cátedra. Si la respuesta no está en el  │            │
│  │ material, decilo y no inventes.                            │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ☐ No guardes mi nombre (usá "Docente")                                 │
│                                                                          │
│                                     [ Continuar → ]                     │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `¿Cómo te llamás?` |
| Ayuda | `Es el nombre que muestra Kitoru en esta computadora. Tus alumnos no lo ven: en la cátedra aparecés con el nombre que elijas cuando te sumes.` |
| Checkbox | `No guardes mi nombre (usá "Docente")` |
| Botón | `Continuar →` |

**Por qué sólo se pide el nombre local y no el nombre "de la cátedra":** el
docente **no es un alumno de su propia cátedra**. La `Enrollment` con rol
`TITULAR` la crea el sistema al crear la cátedra, con
`displayName = "Docente"` por defecto. Si el docente quiere aparecer con un
nombre propio en el roster (para que sus ayudantes lo reconozcan), lo cambia
después desde la sección Alumnos. Preguntarlo en el primer arranque es pedir
un dato que la mayoría no tiene decidido.

**La plantilla del prompt:** se muestra porque es el segundo factor que decide
la calidad de las respuestas del alumno (`rag/05-prompt-catedra.md`). Mostrar
la plantilla en el primer arranque, editable, es la forma más barata de
conseguir que el docente la toque. El botón para editarla a fondo está en la
sección "Instrucciones" del editor de cátedra.

### 3.3 Paso 2 — Crear la cátedra

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Crear una cátedra                                          Paso 2 de 2│
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Creá tu cátedra                                                         │
│                                                                          │
│  Nombre de la materia                                                    │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Inteligencia Artificial                                  │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  Código de la materia                                                    │
│  ┌────────────────────────┐                                              │
│  │ IA                    │  Se usa para ordenar tus cátedras y para       │
│  └────────────────────────┘  mostrarlas en el padrón.                      │
│                                                                          │
│  Cursada                                                                 │
│  ┌────────────────────────┐                                              │
│  │ 2026-1                │  Opcional. Sirve para distinguir dos           │
│  └────────────────────────┘  cursadas de la misma materia.                  │
│                                                                          │
│  ¿Dónde va a vivir el material?                                          │
│  ( ) En esta computadora y la comparto yo (archivo .kitoru)              │
│  ( ) En el servidor de la institución                                     │
│                                                                          │
│                                     [ Crear la cátedra ]                 │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Creá tu cátedra` |
| Campo 1 | `Nombre de la materia` |
| Campo 2 | `Código de la materia` — ayuda: `Se usa para ordenar tus cátedras y para mostrarlas en el padrón.` |
| Campo 3 | `Cursada` — ayuda: `Opcional. Sirve para distinguir dos cursadas de la misma materia.` |
| Grupo 4 | `¿Dónde va a vivir el material?` |
| Radio A | `En esta computadora y la comparto yo (archivo .kitoru)` |
| Radio B | `En el servidor de la institución` |
| Botón | `Crear la cátedra` |

**Decisiones:**

1. **Son tres campos y ninguno es obligatorio salvo el nombre.** El código se
   **autocompleta** con las iniciales del nombre ("Inteligencia Artificial" →
   "IA"), y la cursada se **sugiere** con el cuatrimestre actual ("2026-1"),
   editable, y **opcional** (`domain/01-catedra.md` lo marca como `WARNING`,
   no `ERROR`).
2. **"¿Dónde va a vivir el material?"** es la única pregunta de arquitectura
   que se hace, y se hace porque define el resto del flujo (offline vs. online
   vs. servidor). El default es la primera opción: en el milestone 1 el
   servidor no existe todavía, y el bundle `.kitoru` es el camino soportado
   (`sync/02-offline.md` §7.5).
3. **No se pide clave de API en este paso.** La clave del servidor no se pega
   hasta que el docente decide qué modelo va a usar (§3.6). Pedirla acá es
   pedir un secreto antes de que el usuario sepa para qué sirve.

**Al apretar "Crear la cátedra", en este orden (todo local, < 400 ms):**

1. Se genera el `Course` con `status = BORRADOR`, `joinCode` **ya generado**
   (no espera a la publicación: el código se muestra en el paso siguiente).
2. Se genera el `joinCode` con `JoinCodeGenerator.generateUnique()` — 8
   caracteres del alfabeto base32 sin ambiguos, mostrados como `ABCD-EFGH`
   (`domain/01-catedra.md` §2.1).
3. Se copia la plantilla de instrucciones a `course.systemPrompt`.
4. Se crea la `Enrollment` del docente: `role = TITULAR`, `visibility = FULL`,
   `status = ACTIVE`, `joinedAt = now`.
5. Se guarda `courses.json` y `enrollments.json`.
6. Se navega a la pantalla del código (§3.4).

### 3.4 Paso 3 — El código de inscripción

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Tu cátedra está creada                                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Código de inscripción                                                   │
│                                                                          │
│      A B C D  -  E F G H        [ 📋 Copiar ]                           │
│                                                                          │
│  Todos los alumnos usan este mismo código. Podés rotarlo cuando quieras,  │
│  por ejemplo si se filtró a un grupo de WhatsApp.                        │
│                                                                          │
│  ─────────────────────────────────────────────────────────────────────   │
│  Falta para que esté lista:                                              │
│                                                                          │
│  ○ Subir el material de la materia                                       │
│  ○ Configurar el modelo de IA                                            │
│  ○ Probar el chat                                                        │
│  ○ Publicar la cátedra                                                    │
│  ○ Invitar a los alumnos                                                  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ [ Ir al panel de la cátedra → ]                           │            │
│  └──────────────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Tu cátedra está creada` |
| Etiqueta | `Código de inscripción` |
| Ayuda | `Todos los alumnos usan este mismo código. Podés rotarlo cuando quieras, por ejemplo si se filtró a un grupo de WhatsApp.` |
| Encabezado de la lista | `Falta para que esté lista:` |
| CTA | `Ir al panel de la cátedra →` |

**Por qué el código se muestra acá y no en "Publicar":** el código es lo que
el docente quiere *llevarse* de este momento. Mostrarlo en el momento de
crear la cátedra convierte la creación en un logro tangible ("ya tengo el
código") y es el disparador de laSharing. La publicación (§3.9) es un acto
administrativo distinto.

### 3.5 Paso 4 — Subir el primer material

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Subir material                                                      │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Subí el primer material                                                 │
│                                                                          │
│  Es lo que tus alumnos van a preguntarle a la IA. Un PDF, un Word, una   │
│  presentación o un archivo de texto: lo que uses para enseñar.           │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │                                                          │            │
│  │            Arrastrá el archivo acá                       │            │
│  │       o hacé clic para elegirlo en tu computadora         │            │
│  │                                                          │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  Formatos: PDF, Word, PowerPoint, Markdown, texto, CSV                   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ ¿No tenés material a mano?                                │            │
│  │ Usá el material de ejemplo y reemplazalo cuando quieras. │            │
│  │ [ Usar material de ejemplo ]                              │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ ¿Qué va a pasar?                                          │            │
│  │ Vamos a leer el texto del archivo y a partirlo en         │            │
│  │ fragmentos. Después, cuando un alumno pregunte algo, la IA │            │
│  │ busca en esos fragmentos y te dice de dónde salió.        │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│                              [ Elegir archivo ]                          │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Subí el primer material` |
| Ayuda | `Es lo que tus alumnos van a preguntarle a la IA. Un PDF, un Word, una presentación o un archivo de texto: lo que uses para enseñar.` |
| Drop | `Arrastrá el archivo acá` / `o hacé clic para elegirlo en tu computadora` |
| Formatos | `Formatos: PDF, Word, PowerPoint, Markdown, texto, CSV` |
| Atajo | `¿No tenés material a mano?` + `Usá el material de ejemplo y reemplazalo cuando quieras.` |
| Explicación | `¿Qué va a pasar?` + `Vamos a leer el texto del archivo y a partirlo en fragmentos. Después, cuando un alumno pregunte algo, la IA busca en esos fragmentos y te dice de dónde salió.` |
| Botón | `Elegir archivo` |

El bloque "¿Qué va a pasar?" es **obligatorio** en el primer material y
desaparece después (ya lo entendió). Es la misma técnica que usa
`LoadingPane`: decir la fase, no un spinner.

El feedback de la indexación está en §7.

### 3.6 Paso 5 — Configurar el modelo

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Configurar el modelo                                                │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Configurá el modelo de IA                                               │
│                                                                          │
│  Tus alumnos van a chatear con este modelo. Si tu institución tiene un   │
│  servidor de IA, pegá la dirección y la clave.                           │
│                                                                          │
│  Dirección del servidor                                                  │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ https://ia.unlp.edu.ar/v1                                │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  Clave de API                                                            │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ sk-••••••••••••••••••••••••                            │  👁           │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  Nombre del modelo                                                       │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Qwen3-8B                                                │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ [ Probar conexión ]  ● La clave funciona                 │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ( ) Usar la IA en mi computadora (descargás un modelo de ~5 GB)        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Podés dejar esto para más tarde. Tus alumnos van a poder  │            │
│  │ entrar a la cátedra igual, pero no van a poder chatear     │            │
│  │ hasta que haya un modelo configurado.                     │            │
│  │ [ Lo configuro más tarde ]                                │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│                            [ Guardar y continuar → ]                     │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Configurá el modelo de IA` |
| Ayuda | `Tus alumnos van a chatear con este modelo. Si tu institución tiene un servidor de IA, pegá la dirección y la clave.` |
| Campo 1 | `Dirección del servidor` |
| Campo 2 | `Clave de API` |
| Campo 3 | `Nombre del modelo` |
| Botón | `Probar conexión` |
| Éxito | `● La clave funciona` |
| Radio | `Usar la IA en mi computadora (descargás un modelo de ~5 GB)` |
| Escape | `Podés dejar esto para más tarde. Tus alumnos van a poder entrar a la cátedra igual, pero no van a poder chatear hasta que haya un modelo configurado.` |
| Botón escape | `Lo configuro más tarde` |
| Botón principal | `Guardar y continuar →` |

**"Probar conexión"** = `GET {baseUrl}/models` con el bearer, timeout de 20 s,
usando `OpenAiCompatibleGateway.listModels()` que ya existe en `:llm-api`. El
resultado es el `LlmException` de Kitoru, que ya viene en español con la URL y
qué revisar (`teacher/05-config-modelo.md` §4).

**"Lo configuro más tarde"** es una salida de primera clase, no un
abandono. Guarda la cátedra sin modelo, marca el ítem del checklist y sigue.
El chat del docente queda deshabilitado con el motivo *"Primero configura el
modelo en la pestaña «Modelo»."* (`teacher/04-prompt.md`).

### 3.7 Paso 6 — Probar el chat

Botón en el panel del docente y en el editor de cátedra (`Ctrl/Cmd+Enter`).
Está diseñado en §9.

### 3.8 Paso 7 — Invitar a los alumnos

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Invitá a tus alumnos                                                    │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Pasales el código. Todos usan el mismo.                                │
│                                                                          │
│      A B C D  -  E F G H        [ 📋 Copiar ]                            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Enlace de inscripción                                     │            │
│  │ kitoru://join/ABCD-EFGH                        [ 📋 Copiar ]│           │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Si alguno de tus alumnos no tiene internet en el momento │            │
│  │ podés exportar la cátedra como archivo y pasárselo:       │            │
│  │ funciona sin conexión.                                    │            │
│  │ [ Exportar archivo .kitoru ]                              │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ ⚠ La cátedra todavía no está publicada. Con el código no  │            │
│  │ pasa nada todavía: publicala cuando tengas el material.   │            │
│  │ [ Publicar la cátedra ]                                   │            │
│  └──────────────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Invitá a tus alumnos` |
| Ayuda | `Pasales el código. Todos usan el mismo.` |
| Enlace | `Enlace de inscripción` — valor `kitoru://join/ABCD-EFGH` |
| Exportar | `Si alguno de tus alumnos no tiene internet en el momento podés exportar la cátedra como archivo y pasárselo: funciona sin conexión.` |
| Botón exportar | `Exportar archivo .kitoru` |
| Aviso | `La cátedra todavía no está publicada. Con el código no pasa nada todavía: publicala cuando tengas el material.` |

**El código y el enlace son dos cosas distintas y hacen falta las dos:**

- El **código** se dicta, se escribe en el pizarrón, se manda por WhatsApp. Es
  8 caracteres: se tipea sin errores.
- El **enlace** se manda como enlace (`kitoru://join/...`): en Android abre la
  app; en escritorio muestra el código ya puesto. Es un clic, cero errores de
  tipeo.

### 3.9 El checklist de "puesta en marcha"

Después del wizard, el panel del docente muestra una tarjeta: **"Puesta en
marcha"**. Es lo que reemplaza al wizard largo.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CourseReadiness.kt

/**
 * Los pasos que faltan para que la cátedra esté lista.
 *
 * Es un checklist **derivado de los datos**: no se guarda un flag, se calcula.
 * Por eso el mismo checklist sirve para el primer arranque, para el docente
 * que vuelve en marzo, y para el que borró un material.
 */
enum class ReadinessItem(
    val label: String,
    /** Texto del botón que resuelve el paso. */
    val actionLabel: String,
    /** Dónde lleva el botón. */
    val target: ReadinessTarget,
) {
    IDENTIDAD("La cátedra tiene nombre y código", "Completar", ReadinessTarget.COURSE_EDITOR),
    MATERIAL("Tiene al menos un material indexado", "Subir material", ReadinessTarget.MATERIALS),
    MODELO("El modelo de IA está configurado", "Configurar el modelo", ReadinessTarget.MODEL),
    PROMPT("Las instrucciones de la cátedra están escritas", "Escribir instrucciones", ReadinessTarget.PROMPT),
    PRUEBA("Probaste el chat", "Probar el chat", ReadinessTarget.SMOKE_TEST),
    PUBLICADA("La cátedra está publicada", "Publicar", ReadinessTarget.COURSE_EDITOR),
    INVITAR("Compartiste el código con tus alumnos", "Invitar", ReadinessTarget.STUDENTS),
    ;

    companion object {
        /** El orden en que se muestran: primero lo que bloquea más. */
        val ORDER: List<ReadinessItem> = listOf(MATERIAL, MODELO, PROMPT, IDENTIDAD, PROUEBA, PUBLICADA, INVITAR)
    }
}

/** Calcula el checklist a partir de los datos de la cátedra. */
object Readiness {
    fun of(
        course: Course,
        materials: List<Material>,
        hasSmokeTest: Boolean,
    ): List<ReadinessItem> { /* … */ }

    /** Porcentaje de avance (0..100), para la barra. */
    fun percent(items: List<ReadinessItem>): Int
}
```

**Presentación en el panel del docente:**

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Puesta en marcha                                       2 de 7 listos     │
│  ██████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  28%│
│                                                                          │
│  ● La cátedra tiene nombre y código                                     │
│  ● Tiene al menos un material indexado        [ Subir material ]         │
│  ○ El modelo de IA está configurado           [ Configurar el modelo ]   │
│  ○ Las instrucciones de la cátedra están escritas  [ Escribir instrucciones ]│
│  ○ Probaste el chat                           [ Probar el chat ]         │
│  ○ La cátedra está publicada                  [ Publicar ]                │
│  ○ Compartiste el código con tus alumnos      [ Invitar ]                 │
└──────────────────────────────────────────────────────────────────────────┘
```

**El checklist desaparece solo** cuando los 7 ítems están hechos. No hay un
"¡listo! 🎉" que bloquee: la app simplemente saca la tarjeta.

**Regla:** el checklist es **informativo**, no bloqueante. Nunca impide
publicar ni chatear; informa de lo que falta y por qué.

### 3.10 Presupuesto de tiempo (objetivo: 5 minutos)

| # | Paso | Objetivo | Acumulado | Interacciones |
|---|---|---|---|---|
| 0 | Bienvenida | 0:15 | 0:15 | 1 clic |
| 1 | Tu nombre | 0:20 | 0:35 | 1 campo + 1 clic |
| 2 | Crear la cátedra | 0:45 | 1:20 | 3 campos (2 prellenados) + 1 clic |
| 3 | Ver el código | 0:15 | 1:35 | 1 clic (copiar) |
| 4 | Configurar el modelo | 1:30 | 3:05 | 3 campos + "Probar conexión" |
| 5 | Probar el chat | 0:45 | 3:50 | 1 pregunta |
| 6 | Subir el material | 1:00 | 4:50 | elegir archivo + esperar la indexación |
| 7 | Invitar | 0:10 | **5:00** | 1 clic (copiar) |

**Supuestos que hacen posible el objetivo:**

- El **código de la materia** y la **cursada** vienen prellenados (§3.3): 2 de
  los 3 campos son pulsaciones.
- El **modelo** es el paso caro (1:30) porque incluye esperar la respuesta de
  "Probar conexión". Si el docente ya tiene la configuración guardada de otra
  cátedra, baja a 0:30.
- El **material** es 1:00 si ya lo tiene a mano; si no, se salta y queda en el
  checklist (la cátedra sigue lista en 3:50).
- **Nada de esto** descarga un modelo de 5 GB en el primer arranque: la IA
  local se descarga sólo si el docente la elige, y **no** es parte del
  objetivo de 5 minutos.

**Cómo se mide:** prueba manual cronometrada con 5 docentes que nunca vieron
la app, en un guion de `docs/design/process/scripts/` (el criterio 3 de
"fase terminada" del roadmap, F1.3). Si la mediana supera 5:00, el culpable
es siempre el paso 4 (modelo) o el 6 (material): por eso el 4 tiene salida
"Lo configuro más tarde" y el 6 tiene salida "Usar material de ejemplo".

---

## 4. El flujo del alumno

### 4.1 Paso 1 — El código

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Unirme a una cátedra                                         Paso 1 de 4│
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Escribí el código de la cátedra                                         │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ A B C D - E F G H                                        │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  No importa si va con guion o sin guion, ni si está en minúsculas.       │
│                                                                          │
│  Pegá un enlace          [ Escanear QR ]   (sólo Android)                │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ ⚠ No encontramos una cátedra con ese código.            │            │
│  │   Revisá el código y probá de nuevo. Si tu docente te     │            │
│  │   pasó un archivo de cátedra, abrilo con doble clic.      │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│                                        [ Continuar → ]                  │
└──────────────────────────────────────────────────────────────────────────┘
```

Idéntico al §3.2 de `student/01-entrada.md` (se reusa, no se re-diseña).

**Una diferencia con ese documento:** el contador de pasos. Acá es **"Paso 1 de
4"**, y el 4 es el número de pasos *de este caso* (código → identidad →
privacidad/modelo → resumen). El paso de la clave **no cuenta** si la cátedra
trae la clave o si el alumno elige IA local: el contador se calcula con los
pasos que realmente aparecen.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/join/JoinSteps.kt
/** Los pasos del wizard de inscripción, ya resueltos según el caso. */
enum class JoinStep(val title: String) {
    CODE("Código"),
    IDENTITY("Tu nombre"),
    PRIVACY("Qué ve tu docente"),
    KEY("La clave"),
    SUMMARY("Listo"),
}
```

### 4.2 Paso 2 — La vista previa y tu nombre

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Unirme a una cátedra                                         Paso 2 de 4│
├──────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Inteligencia Artificial · IA · 2026-1                      │            │
│  │ Ana Gómez · 34 alumnos · 12 materiales                    │            │
│  │ ● Servidor de la cátedra · Qwen3-8B                       │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  ¿Cómo querés aparecer en esta cátedra?                                 │
│                                                                          │
│  ( ) Usar un seudónimo          ← recomendado                           │
│      Zorro Tranquilo                                    [ 🎲 otro ]       │
│                                                                          │
│  ( ) Usar mi nombre real                                                  │
│      Ana Gómez                                                            │
│                                                                          │
│  El nombre que elijas es el que ve tu docente. Podés cambiarlo después   │
│  desde la tarjeta de la cátedra.                                          │
│                                                                          │
│  ☐ Recordar este nombre para otras cátedras                               │
│                                                                          │
│  ─────────────────────────────────────────────────────────────────────   │
│  ℹ Estás entrando después de que empezó la cursada. Los avisos y         │
│    materiales anteriores a hoy quedan como históricos.                    │
│                                                                          │
│                                        [ Continuar → ]                  │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales** (los que agrego o cambio respecto de
`student/01-entrada.md` §4.1):

| Elemento | Texto |
|---|---|
| Título | `¿Cómo querés aparecer en esta cátedra?` |
| Radio 1 | `Usar un seudónimo` — con la etiqueta `← recomendado` |
| Radio 2 | `Usar mi nombre real` |
| Ayuda | `El nombre que elijas es el que ve tu docente. Podés cambiarlo después desde la tarjeta de la cátedra.` |
| Checkbox | `Recordar este nombre para otras cátedras` |

**El "← recomendado" va pegado al radio, no en una nota aparte.** Es la
decisión por defecto de `AliasGenerator` ("Zorro Tranquilo", "Halcón Sereno",
"Tejedor Curioso", "Lince Valiente", "Ciervo Nítido" — 40 palabras fijas,
deterministas, sin possibility de nombres ofensivos).

### 4.3 Paso 3 — El aviso de privacidad (al pie de la letra)

> **Éste es el momento de confianza de toda la app.** Si se pierde la
> confianza aquí, no hay recuperación: el alumno deja de escribir en el chat.
> Por eso el texto es corto, concreto, y **dice qué NO pasa**.

**Decisión estructural:** el aviso y la **elección de IA son la misma
pantalla**. La elección de "IA de la institución" o "IA local" es
precisamente la aceptación del aviso, así que separar el aviso del botón
haría que el alumno aceptara algo y después eligiera otra cosa sin volver a
leer.

#### 4.3.1 Variante A — IA de la institución (alojada)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Unirme a una cátedra                                         Paso 3 de 4│
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Antes de entrar: qué va a ver tu docente                               │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Vas a usar el modelo de la cátedra (Qwen3-8B) que corre   │            │
│  │ en los servidores de la Facultad. Tus conversaciones pasan │            │
│  │ por esos servidores y quedan guardadas ahí.               │            │
│  │                                                          │            │
│  │ Con esa IA, tu docente puede ver:                        │            │
│  │   · el texto de tus preguntas y de las respuestas         │            │
│  │   · cuántos mensajes escribís y cuántas conversaciones    │            │
│  │     abrís                                               │            │
│  │   · qué materiales de la cátedra se consultan             │            │
│  │   · cuánto tarda el modelo y cuántos tokens usa            │            │
│  │   · si las citas de las respuestas están respaldadas por  │            │
│  │     el material                                          │            │
│  │                                                          │            │
│  │ Con esa IA, tu docente NO puede ver:                     │            │
│  │   · tu correo electrónico                                │            │
│  │   · tus conversaciones con otras cátedras                 │            │
│  │   · los chats que marques como privados                  │            │
│  │                                                          │            │
│  │ Lo que autorizás acá es el máximo que tu docente puede   │            │
│  │ ver. Podés bajarlo cuando quieras, en un clic, desde     │            │
│  │ «Tus datos»: nadie, ni siquiera el titular, puede ver    │            │
│  │ más de lo que vos autorizás.                             │            │
│  │                                                          │            │
│  │ Si preferís que no lea nada, podés usar una IA en tu      │            │
│  │ propio dispositivo: en ese caso lo que escribís no sale   │            │
│  │ de tu computadora y tu docente no ve nada.                │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  [ ] Entendí lo anterior y quiero usar la IA de la cátedra              │
│                                                                          │
│        [ Usar la IA de la cátedra ]     [ Prefiero la IA de mi compu ]   │
└──────────────────────────────────────────────────────────────────────────┘
```

**Texto literal, sin los recuadros de la maqueta** (`ConsentTexts.ENROLL_HOSTED`):

```
Vas a usar el modelo de la cátedra (Qwen3-8B) que corre en los servidores de
la Facultad. Tus conversaciones pasan por esos servidores y quedan guardadas
ahí.

Con esa IA, tu docente puede ver:
  · el texto de tus preguntas y de las respuestas
  · cuántos mensajes escribís y cuántas conversaciones abrís
  · qué materiales de la cátedra se consultan
  · cuánto tarda el modelo y cuántos tokens usa
  · si las citas de las respuestas están respaldadas por el material

Con esa IA, tu docente NO puede ver:
  · tu correo electrónico
  · tus conversaciones con otras cátedras
  · los chats que marques como privados

Lo que autorizás acá es el máximo que tu docente puede ver. Podés bajarlo
cuando quieras, en un clic, desde «Tus datos»: nadie, ni siquiera el titular,
puede ver más de lo que vos autorizás.

Si preferís que no lea nada, podés usar una IA en tu propio dispositivo: en ese
caso lo que escribís no sale de tu computadora y tu docente no ve nada.
```

**Variante si el servidor es de un tercero** (no de la institución), que ya
existe en `security/02-privacidad.md` §3.2 y se **antepone** a este texto:

```
⚠ Esta cátedra usa una IA de terceros (OpenAI), fuera de la red de la
universidad. Tus conversaciones se envían a ese servicio para generar las
respuestas, y Kitoru no puede garantizar que ese servicio no las use para
entrenar sus modelos. La cátedra declara que no lo hace.
```

#### 4.3.2 Variante B — IA local

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ←  Unirme a una cátedra                                         Paso 3 de 4│
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  IA en tu propia computadora                                            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ El modelo se descarga y corre en este dispositivo.        │            │
│  │                                                          │            │
│  │ Tu docente NO puede ver:                                 │            │
│  │   · el texto de tus preguntas ni el de las respuestas     │            │
│  │   · lo que escribís en este chat                         │            │
│  │                                                          │            │
│  │ Tu docente sí puede ver, si vos lo autorizás:             │            │
│  │   · cuántos mensajes escribís                            │            │
│  │   · cuánto tarda el modelo                               │            │
│  │   · si las citas están respaldadas por el material        │            │
│  │                                                          │            │
│  │ Lo que escribís no sale de tu computadora.                │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  [ ] Entendí lo anterior y quiero usar la IA de mi computadora          │
│                                                                          │
│        [ Usar la IA de mi computadora ]  [ Prefiero la de la cátedra ]   │
└──────────────────────────────────────────────────────────────────────────┘
```

**Texto literal** (`ConsentTexts.ENROLL_LOCAL`):

```
El modelo se descarga y corre en este dispositivo. Lo que escribís no sale de
tu computadora.

Tu docente NO puede ver:
  · el texto de tus preguntas ni el de las respuestas
  · lo que escribís en este chat

Tu docente sí puede ver, si vos lo autorizás:
  · cuántos mensajes escribís
  · cuánto tarda el modelo
  · si las citas están respaldadas por el material
```

**Reglas de redacción aplicadas** (las de `security/02-privacidad.md` §3.1):
vos siempre · ≤ 4 frases por bloque · concreto · describe el efecto ·
**dice qué NO pasa** · una acción por diálogo · **nada pre-marcado** ·
el texto se versiona y se hashea.

**Por qué el checkbox es obligatorio y no pre-marcado:** RGPD art. 7 — la
acción tiene que ser libre, específica e informada. Un checkbox pre-marcado no
es consentimiento. El botón principal queda **deshabilitado** hasta que se
marque:

| Estado del checkbox | Botón principal |
|---|---|
| Sin marcar | `Usar la IA de la cátedra` (deshabilitado) |
| Marcado | `Usar la IA de la cátedra` (habilitado) |

**El botón alternativo nunca se deshabilita.** "Prefiero la IA de mi
computadora" está disponible desde el primer segundo, sin marcar nada: es la
salida de confianza y tiene que ser tan fácil como el camino feliz. Al
pulsarlo, **se cambia el texto del aviso** (de A a B) y se **desmarca** el
checkbox, porque se cambió la pregunta.

**Constancia del consentimiento:** al confirmar, se escribe
`Enrollment.consent = VisibilityConsent(state = GRANTED, level = FULL, grantedAt = now, textVersion = ConsentTexts.VERSION, textHash = sha256Hex(textoMostrado), aiMode = HOSTED | LOCAL)`.
Es el mismo mecanismo de `security/02-privacidad.md` §3.7: el hash del texto
exacto mostrado es la prueba de qué se aceptó.

**Bumpe de versión:** este texto **extiende** el Texto A que ya existe en
`security/02-privacidad.md` §3.2 (agrega la lista de "NO puede ver" y la
frase del techo de visibilidad). Por lo tanto hay que **bumpear**
`ConsentTexts.VERSION` de `"2026-04-01"` a `"2026-10-21"`. Las inscripciones
viejas **conservan** su `textHash` y su `textVersion`: siguen apuntando al
texto con el que el alumno aceptó (§3.7 de ese documento).

#### 4.3.3 Lo que NO se hace

| No se hace | Por qué |
|---|---|
| Un "¿Aceptás?" genérico | No informa nada. El texto de arriba es el consentimiento. |
| Un enlace a "leé la política de privacidad" | A nadie lo lee, y la ley de mindfulness digital exige que el texto relevante esté en la pantalla. |
| Pedir dos consentimientos (uno por el texto de arriba, otro por la visibilidad) | Son la misma decisión. Un solo clic. |
| Un checkbox "compartir con mi docente" por separado | Ya está implícito en el texto y en el botón. Un segundo checkbox es ruido. |
| Ocultar la opción local | Ocultarla deja al alumno creyendo que no existe. Se muestra siempre, deshabilitada si `allowLocalModels = false`, **con el motivo**: *"Esta cátedra no permite IA local. Podés chatgear igual, pero tus conversaciones van a pasar por el servidor de la institución."* |

### 4.4 Paso 4 — La clave (sólo si hace falta)

Aparece sólo si la cátedra usa `ApiKeyMode.PER_STUDENT` y el servidor puede
emitirla. El texto y el diseño están en `student/01-entrada.md` §5.2. No se
re-diseña acá; se anota que en el primer arranque el botón principal es
`Obtener mi clave automáticamente` (el pegado a mano queda como alternativa
desplegada).

**Texto literal del botón:** `Obtener mi clave automáticamente`
**Ayuda:** `Se la pedimos al servidor por vos. No tenés que copiar nada.`

### 4.5 Paso 5 — Resumen y entrada

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                        ¡Ya estás inscripto!                             │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ Inteligencia Artificial · 2026-1                          │            │
│  │                                                          │            │
│  │ Te anotaste como      Zorro Tranquilo                     │            │
│  │ Vas a usar            el modelo de la cátedra (Qwen3-8B) │            │
│  │ Tu docente va a ver   el texto de tus conversaciones      │            │
│  │                                                          │            │
│  │ Frase de verificación (por si tu docente no te reconoce):│            │
│  │ cobre marfil sauce Bruno 42                              │            │
│  └──────────────────────────────────────────────────────────┘            │
│                                                                          │
│  [ Abrir la cátedra → ]                                                  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `¡Ya estás inscripto!` |
| Fila 1 | `Te anotaste como` |
| Fila 2 | `Vas a usar` |
| Fila 3 | `Tu docente va a ver` |
| Fila 4 | `Frase de verificación (por si tu docente no te reconoce):` |
| Botón | `Abrir la cátedra →` |

**La frase de verificación** (`domain/09-identidad.md` §6.3) es un detalle de
producto que vale la pena: convierte un problema invisible ("¿este es el Bruno
o un impostor?") en uno verificable en diez segundos.

**Si el docente ve la visibilidad `METRICS_ONLY`** (porque el alumno no marcó
el checkbox, o porque la política de la cátedra lo impone), la fila 3 dice
`Sólo estadísticas: la cantidad de mensajes y la calidad de las citas. No el
texto.`

### 4.6 La matriz de errores del flujo del alumno

Se reusa la de `student/01-entrada.md` §3.8 (`JoinOutcome`). Se agrega el caso
propio del primer arranque:

| Situación | Qué ve el alumno | Botón |
|---|---|---|
| Código inexistente | `No encontramos una cátedra con ese código.` + `Revisá el código y probá de nuevo. Si tu docente te pasó un archivo de cátedra, abrilo con doble clic.` | `Importar archivo…` · `Reintentar` |
| Cátedra cerrada | `Esta cátedra no está accepting new students.` (corregir a español: `Esta cátedra no acepta alumnos nuevos.`) + `Pedile a tu docente que la abra o que te agregue.` | `Reintentar` |
| Plazo vencido | `La inscripción a esta materia cerró el 30/06.` + `Pedile a tu docente que te agregue.` | `Reintentar` |
| Ya inscripto | `Ya estás en esta cátedra.` | `Abrir la cátedra` |
| Cursada empezada | (no bloquea) `Estás entrando después de que empezó la cursada. Los avisos y materiales anteriores a hoy quedan como históricos.` | `Continuar` |
| **Sin red, sin bundle** | `No hay conexión. Necesitamos conexión para buscar la cátedra.` + `Si tu docente te pasó un archivo de cátedra, abrilo con doble clic: funciona sin conexión.` | `Importar archivo…` · `Reintentar` |
| **Sin red, con bundle** | (no bloquea) `Te anotaste sin conexión. El servidor va a confirmar tu inscripción cuando vuelva la conexión.` + `Meanwhile podés leer el material y chatear con la IA de tu computadora.` | `Abrir la cátedra` |
| clave rechazada (401) | `La clave no fue aceptada por el servidor.` + `Revisala o pedile una nueva a tu docente.` | `Pegar una nueva` |

---

## 5. La cátedra de ejemplo

### 5.1 La decisión

**Sí se ofrece.** Es una decisión de producto con consecuencias concretas:

| A favor | En contra |
|---|---|
| Nadie abre la app ante un vacío: la evaluación y el soporte empiezan con algo que funciona. | Un mock no prueba nada: el que lo usa tiene que ver el chat con citas de verdad. |
| Sirve para explicar la app en una reunión ("abrí esto y mirá"). | Puede contaminar la analítica de una cátedra real si se mezcla. |
| Es el camino natural para aprender el flujo: el docente ve el material, el modelo, las citas y la analítica antes de cargar nada suyo. | Ocupa espacio y confunde ("¿esto es mi cátedra?"). |
| Permite escribir la documentación con capturas reales. | Hay que mantenerla (el material envejece). |

**Las dos condiciones que la hacen viable:**

1. **Es una cátedra real**, no un mock: se chattea, se citan las fuentes, se ve
   la analítica. Si fuera una maqueta, no cumpliría su propósito.
2. **Está marcada**: `Course.sample = true`. La UI lo dice siempre (cinta
   permanente), el docente la borra con un clic, y **nunca** se sube a un
   servidor sin confirmación explícita.

### 5.2 Qué contiene

La cátedra de ejemplo se llama **"Kitoru en 5 minutos"** y su contenido es **la
propia documentación de Kitoru**. Es una decisión deliberada: no hay riesgo de
que la app "enseñe" algo falso (si el modelo inventa, el material lo contradice
y el distintivo de citas lo delata), y el contenido es útil de verdad.

**Metadatos:**

| Campo | Valor |
|---|---|
| `id` | `sample-kitoru-guide` (fijo, para reconocerla) |
| `name` | `Kitoru en 5 minutos` |
| `code` | `DEMO` |
| `term` | *(vacío: es una cátedra evergreen)* |
| `description` | `Esta es una cátedra de ejemplo. Tiene material de muestra para que puedas probar Kitoru sin subir nada tuyo. Podés borrarla cuando quieras.` |
| `joinCode` | `KTR7-F3K3` (constante; ver §5.5) |
| `sample` | `true` |
| `analyticsEnabled` | `true` (el docente tiene que ver la analítica funcionando: es parte de lo que evalúa) |
| `allowLocalModels` | `true` |
| `status` | `PUBLICADA` (así el alumno puede entrar sin que el docente haga nada más) |

**Los tres materiales** (en `core/src/commonMain/resources/sample/`):

| Archivo | Título | Contenido (secciones) |
|---|---|---|
| `01-como-funciona.md` | `Cómo funciona Kitoru` | Qué es una cátedra · De dónde salen las respuestas · Qué es el material · Códigos e inscripción |
| `02-el-distintivo-de-citas.md` | `El distintivo de citas` | Qué verifica · Las tres etiquetas · Qué hacer cuando una cita no tiene respaldo |
| `03-visibilidad-y-privacidad.md` | `Qué ve tu docente` | Los tres niveles de visibilidad · IA local · Quién ve qué · Cómo cambiarlo |

Cada uno con encabezados Markdown (`#`, `##`), que es lo que el `Chunker` de
`:backend` usa para cortar y lo que hace que las citas Digan
`Cátedra de ejemplo · Cómo funciona Kitoru · De dónde salen las respuestas`.

**Longitud:** 400–700 palabras cada uno. Suficiente para que el índice BM25
tenga contexto, poco para que el docente no se aburra.

### 5.3 Cómo se crea

Un botón. Tres clicks máximo.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/SampleCourse.kt

/**
 * La cátedra de ejemplo.
 *
 * Se instala como una cátedra **real**: se chatea con ella, tiene analítica,
 * y el docente puede editarla o borrarla. Es la forma más barata de que
 * nadie abra la app ante un vacío.
 *
 * Los archivos de texto van en `resources/sample/` y se copian al disco
 * cuando se instala: el corpus vive en `courses/<id>/text/`, como cualquier
 * material.
 */
object SampleCourse {

    /** Id fijo: permite reconocerla y ofrecer "restablecer". */
    const val COURSE_ID = "sample-kitoru-guide"

    /** Código de inscripción fijo (ver §5.5). */
    const val JOIN_CODE = "KTR7F3K3"

    /** Archivos de ejemplo, en orden. */
    val FILES: List<SampleFile> = listOf(
        SampleFile("01-como-funciona.md", "Cómo funciona Kitoru", MaterialKind.TRANSCRIPT),
        SampleFile("02-el-distintivo-de-citas.md", "El distintivo de citas", MaterialKind.TRANSCRIPT),
        SampleFile("03-visibilidad-y-privacidad.md", "Qué ve tu docente", MaterialKind.TRANSCRIPT),
    )

    /**
     * Instala la cátedra de ejemplo (idempotente).
     *
     * @return la cátedra creada, o la que ya existía.
     */
    suspend fun install(
        fs: FileSystem,
        paths: KitoruPaths,
        courses: CourseRepository,
        enrollments: EnrollmentRepository,
        materials: MaterialRepository,
        ownerUserId: String,
        now: Long,
    ): Course
}
```

**El `Course` gana un campo** (con default, como todos):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt (campo nuevo)

/**
 * ¿Es la cátedra de ejemplo?
 *
 * `true` ⇒ la UI muestra la cinta de "cátedra de ejemplo", la analítica se
 * etiqueta como muestra, y "Publicar al servidor" pide confirmación
 * explícita.
 */
val sample: Boolean = false,
```

**La instalación hace, en este orden:**

1. Crea el `Course` con los metadatos de §5.2 y `status = PUBLICADA`.
2. Genera el `joinCode = JOIN_CODE` (constante, no aleatorio: tiene que ser
   recordable y publicable en la documentación).
3. Escribe los tres `.md` en `courses/sample-kitoru-guide/text/`.
4. Crea tres `Material` con `status = READY`, `extractedText` = el contenido,
   `chunkCount` calculado con el mismo `Chunker` que usa el resto.
5. Indexa: construye el `Bm25Index` con esos chunks (para que el primer chat
   no espere).
6. Crea la `Enrollment` del docente con rol `TITULAR`.
7. Guarda y devuelve.

**Idempotencia:** si ya existe una `Course` con `id = COURSE_ID`, **no se
toca**. El botón pasa a ser "Restablecer la cátedra de ejemplo" y sólo ofrece
borrar y reinstalar (§5.6).

### 5.4 La cinta de "cátedra de ejemplo"

Aparece en el panel del docente, en la lista de cátedras, en el editor y en
el chat de prueba:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⓘ  Cátedra de ejemplo. Funciona como una materia real: los alumnos se   │
│     pueden anotar con el código KTR7-F3K3 y ver el material. Borrala     │
│     cuando quieras: no se mezcla con nada tuyo.                         │
│     [ Borrar la cátedra de ejemplo ]                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Cinta | `Cátedra de ejemplo. Funciona como una materia real: los alumnos se pueden anotar con el código KTR7-F3K3 y ver el material. Borrala cuando quieras: no se mezcla con nada tuyo.` |
| Botón | `Borrar la cátedra de ejemplo` |

En el lado del alumno, si alguien entra con el código de la ejemplo:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ℹ Estás en una cátedra de ejemplo. El material habla de Kitoru, no de   │
│    una materia real.                                                   │
└──────────────────────────────────────────────────────────────────────────┘
```

### 5.5 El código de inscripción: `KTR7-F3K3`

**Formato canónico** (de `domain/01-catedra.md` §2.1): 8 caracteres del
alfabeto base32 sin ambiguos, mostrados con un guion cada 4. `KTR7F3K3` usa
sólo caracteres válidos en **las dos** definiciones de alfabeto que conviven
en los documentos (la de `domain/01-catedra.md` y la Crockford de
`teacher/02-editor-catedra.md`), y no contiene `0`, `1`, `I`, `L`, `O` ni `U`.

**Es fijo y público. Consecuencias asumidas:**

- Cualquiera que lea la documentación puede entrar a la cátedra de ejemplo.
  Es aceptable: no hay datos reales, es una demo.
- El ejemplo **no se publica a un servidor** sin confirmación explícita
  ("Esta cátedra es de ejemplo. ¿Querés publicarla en el servidor? Los
  alumnos que entren van a ver material de muestra."). Por default, la
  instalación deja la cátedra en el modo local.

**El código se puede rotar** como cualquier otro (`CourseLifecycle.rotateJoinCode`),
pero el botón "Restablecer" lo vuelve a poner.

### 5.6 Cómo se limpia

**Regla de oro: nada se borra automáticamente.** Borrar datos sin preguntar
contradice el principio que estructura todo `security/02-privacidad.md`, y el
docente puede haber usado la cátedra de ejemplo en clase de verdad (alumnos
reales, preguntas reales).

**Tres salidas, todas a un clic:**

| Salida | Dónde | Qué hace |
|---|---|---|
| **Borrar ahora** | cinta de la cátedra · Ajustes → Datos | Purga la cátedra, sus materiales, inscripciones, hilos y la carpeta `courses/sample-kitoru-guide/`. Es el `purgeCourse()` de `domain/01-catedra.md` §4.3, con confirmación escrita: `Escribí "borrar" para confirmar. Esto borra la cátedra de ejemplo, sus materiales y las conversaciones que haya adentro.` |
| **Restablecer** | cinta de la cátedra | Borra y reinstala los materiales originales (para cuando el docente los editó y quiere volver atrás). Es el mismo borrado + `SampleCourse.install()`. |
| **Deslinde por inactividad** | panel del docente, tras 14 días sin uso | Aviso blando, una sola vez: `¿Seguís usando la cátedra de ejemplo? Podés borrarla con un clic.` No borra nada. |

**El aviso de inactividad se muestra una sola vez y se marca con un flag
local** (`AppSettings.sampleNudgeShown`), no con un timestamp en la cátedra.

**El botón "Borrar todos los datos locales"** de Ajustes también la borra
(`security/02-privacidad.md` lo exige: no quedan restos).

### 5.7 Qué ve el evaluador

Este es el guion de la demo, y es la razón de ser de la cátedra de ejemplo:

> 1. Abrir Kitoru → "Probar con una cátedra de ejemplo".
> 2. Se abre el panel del docente, con la cinta de ejemplo y el checklist
>    "Puesta en marcha" en 4 de 6.
> 3. Click en "Probar el chat" → el docente pregunta
>    *"¿Qué es el distintivo de citas?"*.
> 4. La respuesta cita **"Cómo funciona Kitoru"** y el distintivo dice
>    `Citas verificadas (2)`.
> 5. Click en la cinta de fuentes → se ve el fragmento exacto del material.
> 6. Click en "Analítica" → hay actividad, latencia y calidad de citas.

En **90 segundos** el evaluador vio: creación, material, indexación, chat,
citas, verificación y analítica.

---

## 6. El primer chat guiado

### 6.1 Qué es y por qué no es un mensaje del modelo

El primer chat del alumno **se siembra con un mensaje del tutor**, generado
en el dispositivo:

- **Sin red y sin modelo**: si el alumno se inscribe sin conexión, igual ve la
  guía.
- **Sin latencia**: el mensaje está ahí cuando el hilo se abre.
- **Sin costo**: no consume tokens.
- **Sin alucinación**: el texto es fijo y auditado.

El mensaje se guarda como un `ChatMessage` con `origin = GUIDED_INTRO`, de
modo que es parte del hilo, sobrevive al reinicio y se puede mostrar distinto
(una cinta "Guía inicial") de una respuesta del modelo.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/GuidedChat.kt

/**
 * El primer chat guiado del alumno.
 *
 * Es un hilo **sembrado**: el mensaje inicial lo escribe la app (no el
 * modelo), y las sugerencias se derivan del material real de la cátedra para
 * que las primeras respuestas tengan citas.
 */
object GuidedChat {

    /** Versión del texto de la guía. Cambia si cambia el texto. */
    const val INTRO_VERSION = "2026-10-21"

    /**
     * Construye el mensaje inicial del tutor.
     *
     * @param course la cátedra en la que se inscribe el alumno.
     * @param materials materiales **visibles** de la cátedra (ordenados por
     *   fecha de subida). Si está vacía, la guía avisa que no hay material.
     */
    fun introMessage(course: Course, materials: List<Material>): ChatMessage

    /**
     * Las preguntas sugeridas.
     *
     * Se derivan de los títulos de los materiales: siempre se pueden
     * responder con lo que hay. Sin material, se devuelve una sola pregunta
     * genérica sobre la materia.
     */
    fun suggestions(course: Course, materials: List<Material>): List<String>
}
```

### 6.2 El mensaje inicial del tutor (texto al pie de la letra)

**Caso con material** (`ConsentTexts`-style, pero como `GuidedChat.INTRO`):

```
Hola. Soy el tutor de esta cátedra.

Antes de empezar, tres cosas:

1. Preguntame lo que necesites de la materia. Respondo desde el material de la
   cátedra, no desde internet: si algo no está en el material, te lo digo.

2. Cada respuesta viene con sus fuentes: el archivo y la página de donde
   salió lo que te dije. Tocá el chip para ver el fragmento.

3. Debajo de cada respuesta vas a ver un distintivo:
   ✓ Citas verificadas — lo que te dije está en el material.
   ⚠ 1 cita sin respaldo — una parte no la encontré en el material. Puede ser
     una paráfrasis o un error: verificala.

Probalá con una de estas:
[ ¿Qué dice «Cómo funciona Kitoru»? ]
[ ¿De qué trata «El distintivo de citas»? ]

Y si algo no te cierra: en el menú ⋮ está «Tus datos», donde decidís qué ve
tu docente y qué modelo usás.
```

**Caso sin material** (la cátedra todavía no tiene nada indexado):

```
Hola. Soy el tutor de esta cátedra.

Todavía no hay material en esta cátedra, así que mis respuestas no van a
tener citas: van a salir de lo que sé, no de tus apuntes. Cuando tu docente
suba material, te lo vas a notar: cada respuesta va a mostrar el archivo y la
página de donde salió.

Igual podés preguntarme lo que quieras. Y si algo no te cierra: en el menú ⋮
está «Tus datos», donde decidís qué ve tu docente y qué modelo usás.
```

**Reglas del texto:**

| Regla | Cómo se cumple |
|---|---|
| No dice "soy una IA" | Dice "soy el tutor de esta cátedra": el rol importa más que la tecnología. |
| Explica el **material** antes que el **modelo** | El material es lo que el alumno va a notar. |
| Explica las citas **con el distintivo exacto** que va a ver | El texto del distintivo se copia de `teacher/09-calidad.md` §4.1: `Citas verificadas (3)` / `2 citas sin respaldo` / `Sin citas: respondió sin el material`. |
| Explica qué hacer si una cita falla | "Verificala" — no "no confíes en la IA". El verificador es una herramienta, no un juez. |
| Da una salida al que no le cierra | El enlace a «Tus datos». |
| Es corto | 14 líneas. Cabe en una pantalla de teléfono sin scroll. |

### 6.3 Las sugerencias: derivadas del material real

```kotlin
fun suggestions(course: Course, materials: List<Material>): List<String> {
    val visibles = materials.filter { it.visible }.take(2)
    if (visibles.isEmpty()) return listOf("¿De qué se trata esta materia?")
    return visibles.mapIndexed { i, m ->
        val titulo = m.title.trim().removeSuffix(".")
        if (i == 0) "¿Qué dice «$titulo»?" else "¿De qué trata «$titulo»?"
    }
}
```

**Por qué se derivan de los títulos y no se inventan:** una sugerencia que la
IA no puede responder con el material es la peor primera experiencia posible
(el alumno prueba, no encuentra nada, y deduce que la app no funciona).
Con los títulos, la pregunta siempre tiene respuesta.

Las sugerencias se muestran como **chips pulsables** debajo del mensaje del
tutor. Al tocar uno, se llena el redactor (no se envía automáticamente: el
alumno puede editarla antes de mandarla).

### 6.4 Cómo se crea el hilo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/EnrollmentService.kt (extensión)

/**
 * Crea el hilo guiado de primera vez.
 *
 * Se llama una sola vez: al anotarse en una cátedra en la que el usuario no
 * tiene ningún hilo todavía.
 */
suspend fun createGuidedThread(
    course: Course,
    materials: List<Material>,
    authorId: String,
    authorName: String,
    threads: ThreadRepository,
    messages: MessageRepository,
    now: Long,
): ChatThread
```

**Reglas:**

- Se crea **sólo** si el usuario no tiene ningún hilo en esa cátedra. Si ya
  chatteó, no se siembra nada (no se interrumpe una conversación en curso).
- Se crea **una vez por inscripción**, no por cátedra: si el alumno se da de
  baja y vuelve, no se le vuelve a sembrar.
- El hilo se llama `Presentacion` y lleva `origin = GUIDED`.
- El mensaje del tutor lleva `origin = GUIDED_INTRO` y **no cuenta para la
  analítica**: `AnalyticsAnalyzer` lo excluye por `origin`.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (campos nuevos)

/** De dónde salió el mensaje. */
@Serializable
enum class MessageOrigin(val label: String) {
    /** Respuesta normal del modelo. */
    NORMAL("Respuesta"),
    /** Mensaje inicial del tutor (sembrado por la app). */
    GUIDED_INTRO("Guía inicial"),
    /** Mensaje del propio usuario. */
    USER("Pregunta"),
    /** Resultado de una herramienta. */
    TOOL("Herramienta"),
}

/** De dónde salió el hilo. */
@Serializable
enum class ChatOrigin(val label: String) {
    /** Hilo normal. */
    NORMAL("Conversación"),
    /** Hilo guiado de primera vez. */
    GUIDED("Presentación"),
    /** Hilo de prueba del docente (no cuenta para la analítica). */
    SMOKE_TEST("Prueba"),
}
```

**Lo que el alumno ve al abrir el chat por primera vez:**

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Inteligencia Artificial · 2026-1            [ Qwen3-8B ⌄ ]  [ ● Con material ]│
├──────────────────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  ┐         │
│  │ Tutor de Kitoru                            [Guía inicial]│  │         │
│  │                                                        │  │         │
│  │ Hola. Soy el tutor de esta cátedra.                    │  │         │
│  │                                                        │  │         │
│  │ Antes de empezar, tres cosas:                          │  │         │
│  │                                                        │  │         │
│  │ 1. Preguntame lo que necesites de la materia…          │  │         │
│  │ 2. Cada respuesta viene con sus fuentes…               │  │         │
│  │ 3. Debajo vas a ver un distintivo…                      │  │         │
│  │                                                        │  │         │
│  │ [ ¿Qué dice «Cómo funciona Kitoru»? ]                  │  │         │
│  │ [ ¿De qué trata «El distintivo de citas»? ]             │  │         │
│  └────────────────────────────────────────────────────────┘  │         │
│                                                                          │
│  [● Usar el material]  Preguntas libres    Escribí tu pregunta…   [ ↑ ] │
└──────────────────────────────────────────────────────────────────────────┘
```

**El chip `[ ● Con material ]`** es el toggle de modo que ya está diseñado en
`student/02-chat.md` (D-8): default "Usar el material de la cátedra".

---

## 7. El primer material: indexación y prueba

### 7.1 Qué se le explica al docente

El bloque "¿Qué va a pasar?" del §3.5 es el aviso **antes**. Durante, la UI
muestra la fase. Después, el resultado.

**Las tres fases con su texto literal:**

| Fase (`MaterialStatus`) | Etiqueta de la fase | Explicación (una línea) |
|---|---|---|
| `PENDING` | `Preparando el archivo…` | `Copiando el archivo a la carpeta de la cátedra.` |
| `EXTRACTING` | `Leyendo el texto del archivo…` | `Página 8 de 24.` (o `Leyendo las 3 diapositivas…`) |
| `INDEXING` | `Partiendo el material en fragmentos…` | `Vamos a hacer 87 fragmentos de 1.200 caracteres.` |
| `READY` | `Listo` | `El material aporta 87 fragmentos al índice de la cátedra.` |
| `NEEDS_REVIEW` | `Revisión necesaria` | ver §7.4 |
| `FAILED` | `No se pudo leer el archivo` | ver §7.4 |

### 7.2 El panel de indexación

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Apuntes-IA.pdf                                                           │
│  ● Leyendo el texto del archivo…                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ ████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│  Página 8 de 24                                                           │
│                                                                          │
│  ℹ Estamos partiendo el archivo en fragmentos para que la IA pueda       │
│    buscar dentro. No hace falta que esperes: podés seguir trabajando.     │
└──────────────────────────────────────────────────────────────────────────┘
```

**Elementos (port de `LoadingPane.kt` de FunshiEngineAI):**

- `LinearProgressIndicator` con `progress = { pct/100f }` cuando hay
  progreso conocido; indeterminado cuando no.
- La línea de fase: `"${label} — $pct% · ${humanSize(done)} / ${humanSize(total)}"`
  cuando aplica (copia de `humanSize` de FunshiEngineAI).
- Botón **"Cancelar descarga"** **sólo** si la fase es una descarga (el modelo
  GGUF). La indexación local **no** se cancela: es rápida y dejarla a medias
  deja el índice inconsistente.

**El texto "No hace falta que esperes"** es importante: el docente no debe
sentir que la app se congeló. El índice se construye en segundo plano y la
pantalla del material ya se puede abrir.

### 7.3 La pregunta de prueba

Cuando el material pasa a `READY`, la app propone una **pregunta de prueba**.
No es un adorno: es la forma más corta de que el docente vea que el material
**está en el índice**.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Apuntes-IA.pdf · Listo                                                   │
│  24 páginas · 48.230 caracteres · 87 fragmentos · reindexado ahora        │
├──────────────────────────────────────────────────────────────────────────┤
│  Probá que la IA encuentre lo que tiene que encontrar                    │
│                                                                          │
│  Pregunta de prueba                                                       │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ ¿Qué dice «Apuntes-IA.pdf» sobre redes convolucionales?   │            │
│  └──────────────────────────────────────────────────────────┘            │
│  (La app propone una pregunta a partir del material. Podés cambiarla.)   │
│                                                                          │
│  [ Probar con el material ]      [ Probar con el modelo ]                │
└──────────────────────────────────────────────────────────────────────────┘
```

**Cómo se propone la pregunta (determinista):** se toma el **primer
encabezado** del texto extraído (el `Chunker` ya los separa por encabezado
Markdown) y se arma `¿Qué dice «<título del material>» sobre <encabezado
en minúsculas>?`. Si no hay encabezados, `¿Qué dice «<título del material>»?`.
Es una pregunta que **siempre** tiene respuesta en el material.

**El botón "Probar con el material"** = búsqueda BM25 pura, **sin modelo y
sin red** (`KnowledgeBase.retrieve(query, k)`). Muestra los fragmentos
recuperados:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  3 fragmentos recuperados de 87                                           │
│                                                                          │
│  ① Apuntes-IA.pdf · Página 12 — «Las redes convolucionales aplican…»     │
│  ② Apuntes-IA.pdf · Página 13 — «…el pooling reduce la dimensión…»       │
│  ③ Tema 3 - GPUs.pdf · «Un tensor es un arreglo…»                        │
│                                                                          │
│  El material se encontró. Ahora probá con el modelo.                     │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │ El material usa filtros convolucionales…  [1] [2]        │            │
│  │ 🟢 Citas verificadas (2)                                  │            │
│  └──────────────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

**El botón "Probar con el modelo"** = RAG completo (`RagEngine.prepare` +
`AgentLoop` + `CitationVerifier`), y muestra la respuesta con el distintivo
de citas. Es el mismo camino que usa el chat del alumno: **lo que el docente
ve es exactamente lo que va a ver el alumno**.

**Por qué dos niveles:** el primero aísla la variable "material"; el segundo
cierra el círculo. Si el primero no encuentra nada, el problema es el
material (no hay texto, el chunk quedó mal, la pregunta no está en el
documento). Si el primero encuentra y el segundo no cita, el problema es el
modelo o el prompt. Esta distinción es el corazón de
`teacher/03-materiales.md`.

**Copia de la referencia:** este panel es la pestaña "Probar pregunta" de
`teacher/03-materiales.md`, usada por el onboarding como primer contacto.

### 7.4 Los dos finales que hay que explicar bien

**PDF escaneado (`NEEDS_REVIEW`)** — la causa número uno de "la IA no
encuentra nada":

```
⚠ Este PDF parece escaneado: no tiene capa de texto, así que la IA no puede
  leerlo. Podés subirlo de nuevo como PDF con texto seleccionable, o pegar el
  contenido en un archivo .md.

[ Pegar el texto en un .md ]   [ Ver el texto extraído ]
```

**Error de lectura (`FAILED`)**:

```
⚠ No se pudo leer el archivo. Es un PDF protegido con contraseña.

Desprotegelo y volvé a subirlo.
```

Ambos mensajes **dicen qué hacer**: son la diferencia entre un docente que
reintenta tres veces y uno que entiende el problema.

---

## 8. La migración de datos

### 8.1 El mecanismo

`schemaVersion` vive en `config/app-config.json` (`process/01-packaging-ci.md`
§6.1). El `AppBootstrap.loadOrCreate()` (§2.2) ya hace el trabajo completo.
Lo que se define acá es **qué ve el usuario**.

| Situación | Qué hace la app | Qué ve el usuario |
|---|---|---|
| `schemaVersion == CURRENT` | Nada | Nada |
| `schemaVersion < CURRENT` | Copia de seguridad → migra → escribe → avisa | Aviso de una línea (§8.3) |
| `schemaVersion > CURRENT` | **No escribe nada** | Pantalla bloqueante (§8.4) |
| La migración falla | Restaura el respaldo | Mensaje (§8.5) |

### 8.2 La regla que une onboarding y migración

> **La migración nunca repite el onboarding.**

Es una consecuencia directa de D1: el estado de entrada es **derivado** de
`profile` + `enrollments`. Una migración que agregue un campo con default no
cambia esos datos, así que el usuario sigue viendo su home. Y si la migración
cambia el *significado* de un dato (por ejemplo, `visibility` default pasa de
`FULL` a `METRICS_ONLY`), **eso se avisa explícitamente** con un banner, no
con un onboarding.

**El caso del que hay que acordarse** (ya está en
`security/02-privacidad.md` §3.7): si una migración deja
`consent.state = NOT_REQUESTED`, el efecto es `METRICS_ONLY` (fail-closed) y
la app muestra:

> *"Confirmá qué puede ver tu docente"* — con un botón que concede `FULL` si
> el alumno quiere.

Eso **no** es un onboarding: es un banner en la home, sobre una sola
cátedra, con un botón. El resto de la app funciona.

### 8.3 El aviso de migración (bloqueante: no)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ℹ Actualizamos Kitoru                                                    │
│    Tus cátedras, tu material y tus conversaciones siguen como estaban.    │
│    Algunas formas nuevas no aparecen hasta que las vuelvas a usar.       │
│    [ Entendido ]   [ Ver qué cambió ]                                   │
└──────────────────────────────────────────────────────────────────────────┘
```

**Textos literales:**

| Elemento | Texto |
|---|---|
| Título | `Actualizamos Kitoru` |
| Cuerpo | `Tus cátedras, tu material y tus conversaciones siguen como estaban. Algunas formas nuevas no aparecen hasta que las vuelvas a usar.` |
| Botón 1 | `Entendido` |
| Botón 2 | `Ver qué cambió` |

**"Ver qué_changed"** abre el `CHANGELOG.md` embebido en la app (o la página
de releases), no un documento legal.

**Un aviso por versión, no por archivo.** Los pasos de migración se registran
todos bajo el mismo `schemaVersion`.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Migrations.kt

object Migrations {

    /** Aviso a mostrar por cada versión desde la que se migró. */
    val NOTICES: Map<Int, String> = mapOf(
        1 to "Tus cátedras, tu material y tus conversaciones siguen como estaban. Algunas formas nuevas no aparecen hasta que las vuelvas a usar.",
        // 2 -> "...",
    )

    /** El aviso para una versión dada (vacío = no hubo migración relevante). */
    fun noticeFor(from: Int): String =
        (from..SchemaVersion.CURRENT).mapNotNull { NOTICES[it] }.firstOrNull() ?: ""
}
```

### 8.4 La app vieja sobre datos nuevos

Reproducido de `process/01-packaging-ci.md` §6.3 (no se re-diseña):

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠  Actualizá Kitoru                                                     │
│                                                                          │
│  Estos datos los creó una versión más nueva de Kitoru (esquema 2). Esta  │
│  versión (0.1.0) sólo entiende hasta el esquema 1.                       │
│                                                                          │
│  Si actualizás, vas a poder seguir usando todo tal cual. Si no, no vamos │
│  a tocar tus datos.                                                      │
│                                                                          │
│  [ Actualizar ahora ]   [ Salir ]                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

**En el onboarding esto significa una cosa extra:** esta pantalla **no**
puede dejar pasar al onboarding. Es el único caso en que la app muestra una
pantalla que no es ni la bienvenida ni la home.

### 8.5 La migración que falla

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠ No se pudo actualizar la configuración de la app                      │
│                                                                          │
│  Se restauró la copia anterior. Podés seguir usando la app, pero es      │
│  posible que falten algunos ajustes.                                     │
│                                                                          │
│  [ Entendido ]                                                            │
└──────────────────────────────────────────────────────────────────────────┘
```

El texto es el de `process/01-packaging-ci.md` §6.4. Se añade una línea
sólo si aplica:

```
Tus cátedras y tus conversaciones no se tocaron: el problema está en los
ajustes de la app, no en tu material.
```

### 8.6 Qué NO se re-hace al migrar

| No se re-hace | Por qué |
|---|---|
| Re-descargar el modelo GGUF | El modelo vive en `modelsDir`, no en el JSON. La migración no lo toca. |
| Re-indexar todo el material | El índice se invalida por **hash de contenido + `chunkSize`**, no por versión de esquema (`rag/01-extraccion.md`). Si el `chunkSize` no cambió, el índice sigue válido. |
| Re-preguntar la identidad | El `UserProfile` ya está: la migración no lo borra. |
| Re-mostrar la bienvenida | El estado es derivado (§1.2). |

**El único trabajo extra de la migración** es reindexar si cambió
`chunkSize` o el algoritmo de troceado, y eso se hace **en segundo plano**,
con una barra discreta en el panel del docente:

```
ℹ Estamos reindexando el material con el nuevo tamaño de fragmento. Va a
  tardar un poco; podés seguir usando la app.
```

---

## 9. El modo de prueba del docente

### 9.1 Por qué es lo más importante del documento

> **Un docente que prueba su propia cátedra antes de invitar a nadie es un
> docente que se convierte en el producto.**

Sin esta función, el docente tiene tres opciones y todas son malas:

1. Invitar a los alumnos y descubrir que el modelo no responde → **vergüenza
   en clase**.
2. Probar el chat con su propia cuenta de alumno → **pierde su rol** y ensucia
   el padrón.
3. No probar → **no sabe qué va a pasar** y 保... la cátedra sin publicar.

El modo de prueba resuelve las tres: el docente ve **exactamente** lo que va
a ver el alumno, con el material real y el modelo real, sin Consequences.

### 9.2 Las dos variantes

| Variante | Desde | Qué usa | Para qué |
|---|---|---|---|
| **Probar el borrador** | El editor de cátedra (`Ctrl/Cmd+Enter`) | El `Course` de trabajo (borrador) + el material real | Probar antes de publicar. Cambia lo que el editor toca. |
| **Probar lo publicado** | El panel del docente | El `PublishedCourse` (copia congelada) | Ver lo que ve el alumno **hoy**. Cambia lo que el editor no toca. |

**Regla:** si la cátedra no está publicada, la variante "lo publicado" usa el
borrador y muestra una cinta:

> `Estás probando el borrador. Tus alumnos no ven nada hasta que publiques.`

(Esto ya está decidido en `teacher/02-editor-catedra.md` D10: "Probar el
chat" usa el **borrador**.)

### 9.3 La pantalla

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⓘ  Estás probando el chat como un alumno. Esta conversación no entra en │
│     la analítica de la cátedra y no la ve ningún docente.                 │
│     [ Ver como el alumno ve la cátedra ]        [ Cerrar la prueba ]      │
├──────────────────────────────────────────────────────────────────────────┤
│  Aparecen las instrucciones de la cátedra:                               │
│  «Eres el asistente de Inteligencia Artificial. Respondé siempre a partir │
│   del material de la cátedra. Si la respuesta no está en el material,      │
│   decilo y no inventes.»                                                   │
├──────────────────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐              │
│  │  ¿Qué entra en el primer parcial?                        │              │
│  │  según Apuntes-IA.pdf, el primer parcial cubre…      │              │
│  │  [1]                                                  │              │
│  │  🟢 Citas verificadas (1)                               │              │
│  └────────────────────────────────────────────────────────┘              │
│                                                                          │
│  [● Usar el material]  Preguntas libres   Escribí una pregunta…   [ ↑ ]  │
└──────────────────────────────────────────────────────────────────────────┘
```

**La cinta permanente es la regla:** el docente tiene que saber **todo el
tiempo** que está en un sandbox. Nunca se oculta.

**"Ver como el alumno ve la cátedra"** abre una pantalla con la
`PublishedCourse` renderizada tal como la ve el alumno (nombre, código,
cursada, descripción, lista de materiales, chip del modelo). Es el
"panel de vista previa" del editor de cátedra, pero como destino aparte.

### 9.4 Qué se guarda y qué no

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (campo nuevo en ChatThread)

/**
 * ¿Es un hilo de prueba del docente?
 *
 * `true` ⇒ no entra en la analítica de la cátedra, no aparece en el listado
 * de chats de alumnos, y se puede borrar de un tirón.
 */
val smokeTest: Boolean = false,
```

| | Hilo normal | Hilo de prueba |
|---|---|---|
| Se guarda | Sí | Sí (para que el docente pueda revisarlo) |
| Cuenta para la analítica | Sí | **No** |
| Aparece en "Chats de alumnos" | Sí | No (y nunca: el autor es el docente) |
| El texto lo ve otro docente | Según visibilidad | No (jamás) |
| Se puede borrar | No desde la UI del docente | Sí: `Borrar las pruebas` |

**`AnalyticsAnalyzer.analyze()`** gana un parámetro:

```kotlin
fun analyze(
    course: Course,
    enrollments: List<Enrollment>,
    threads: List<ChatThread>,
    messages: List<ChatMessage>,
    /** Ignorar los hilos de prueba del docente (por defecto, `true`). */
    excludeSmokeTests: Boolean = true,
): CourseAnalytics
```

Es el mismo criterio que `domain/03-inscripciones.md` ya usa para excluir los
hilos importados (`origin = LOCAL_IMPORT`).

**El botón "Borrar las pruebas"** está en el panel del docente, abajo:

> `Probaste 3 veces. Estas conversaciones no cuentan para la analítica.`
> `[ Borrar las pruebas ]`

### 9.5 El resumen al terminar

Al cerrar la prueba, se muestra una tarjeta:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Probaste 3 preguntas                                                    │
│                                                                          │
│  · 2 con citas verificadas                                               │
│  · 1 sin respaldo: "el gradient descent" — el material dice "descenso     │
│    del gradiente"                                                        │
│                                                                          │
│  [ Ver el material que falló ]   [ Probar de nuevo ]   [ Listo ]        │
└──────────────────────────────────────────────────────────────────────────┘
```

**"Ver el material que falló"** abre el material, en la pestaña "Probar
pregunta", con la consulta precargada. Es el ciclo de mejora del docente, en
un clic: **probar → ver qué falló → corregir el material → volver a probar**.

---

## 10. Sin conexión en el primer arranque

### 10.1 La idea

**La inscripción es local.** El código, el nombre, la inscripción, el material
y el índice viven en el dispositivo. **Lo que requiere red es el modelo
alojado y la distribución del material a los demás.** Son dos cosas
distintas, y el onboarding tiene que distinguirlas en el texto, no dejar que
el alumno las descubra por su cuenta.

### 10.2 La matriz

| Momento | Sin red | Qué ve el usuario |
|---|---|---|
| **Arranque** | Funciona | La app abre en < 1 s (`LoadingPane` con fases locales) |
| **Crear identidad** | Funciona | — |
| **Crear la cátedra** (docente) | Funciona | — |
| **Subir material** (docente) | **Funciona** | La extracción (PDFBox) y el índice (BM25) son locales |
| **Indexar** | Funciona | — |
| **"Probar conexión"** (docente) | Falla | `⚠ Sin validar` — la app sigue funcionando (`sync/02-offline.md` §6.3) |
| **Buscar la cátedra por código** (alumno) | Falla, salvo bundle local | Mensaje honesto + `Importar archivo…` |
| **Inscribirse** (alumno, con bundle) | **Funciona** | `Te anotaste sin conexión. El servidor va a confirmar tu inscripción cuando vuelva la conexión.` |
| **Inscribirse** (alumno, sin bundle) | Falla | `No hay conexión. Necesitamos conexión para buscar la cátedra.` |
| **Descargar el material** (alumno) | Falla | `Material pendiente de descarga (0 de 12)` |
| **Chat con IA alojada** | Falla | Mensaje del `LlmException` (§10.4) |
| **Chat con IA local** | **Funciona** | Sin RAG si el material no está: el modelo responde y lo dice |

### 10.3 El mensaje del alumno sin red (texto literal)

**Caso A — tiene el archivo de la cátedra (bundle) y la inscripción se puede
resolver localmente:**

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ℹ Te anotaste sin conexión.                                             │
│                                                                          │
│  Tu inscripción y el material de la cátedra quedan en esta computadora.   │
│  Cuando vuelva la conexión, se sincronizan solos.                       │
│                                                                          │
│  Mientras tanto podés leer el material y chatear con la IA de tu          │
│  computadora.                                                            │
│                                                                          │
│  [ Abrir la cátedra ]                                                    │
└──────────────────────────────────────────────────────────────────────────┘
```

**Caso B — no hay conexión y no hay bundle:**

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠ No hay conexión.                                                     │
│                                                                          │
│  Necesitamos conexión para buscar la cátedra. Si tu docente te pasó un  │
│  archivo de cátedra (.kitoru), abrilo con doble clic: funciona sin        │
│  conexión.                                                               │
│                                                                          │
│  [ Importar archivo… ]   [ Reintentar ]                                  │
└──────────────────────────────────────────────────────────────────────────┘
```

(Es el mismo texto de `sync/02-offline.md` §6.2, que ya está bien escrito.)

**La honestidad clave:** en el caso B **no se dice** "código inválido". El
código puede ser perfectamente válido: lo que no sabemos es si existe. Ver
§4.6.

### 10.4 El mensaje del alumno cuando quiere chatear sin red

Si el alumno eligió IA alojada y no hay red, el chat **no se bloquea**: se
explica y se ofrecen salidas.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠ Sin conexión con el servidor de la cátedra.                           │
│    No puedo responder hasta que vuelva la conexión.                       │
│    [ Reintentar ]   [ Usar la IA de mi computadora ]                     │
└──────────────────────────────────────────────────────────────────────────┘
```

**Si el alumno usa IA local y no tiene el material descargado**, el modelo
responde igual y **lo dice** (`sync/02-offline.md` §6.4):

> *"Ahora no tengo el material de la cátedra, así que no puedo citarte los
> apuntes. Va a volver cuando se descargue."*

### 10.5 El mensaje del docente sin red

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠ Sin conexión con el servidor.                                         │
│    Podés seguir trabajando: crear la cátedra, subir material y           │
│    configurarlo todo funciona sin internet. Lo que necesita conexión es   │
│    el modelo de IA que van a usar los alumnos.                            │
│    [ Ver qué falta subir ]                                                │
└──────────────────────────────────────────────────────────────────────────┘
```

**Este mensaje aparece una sola vez** (la primera vez que se detecta la falta
de conexión en el panel del docente), y no bloquea nada.

### 10.6 La regla de "offline-first" aplicada al onboarding

| El onboarding… | …no debe |
|---|---|
| Abrir la app | Consultar la red de forma bloqueante |
| Crear la cátedra | Exigir un servidor |
| Subir el primer material | Requerir red (PDFBox y BM25 son locales) |
| Mostrar el guía del tutor | Llamar al modelo |
| Probar el material | Llamar al modelo (el nivel BM25 es local) |
| Mostrar la cátedra de ejemplo | Descargar nada (los `.md` van en el binario) |

**La única excepción** es el modelo GGUF de la IA local, que se descarga
cuando el usuario lo elige (no es parte del arranque) y que tiene su propio
`LoadingPane` con porcentaje y botón de cancelar.

---

## 11. Catálogo de textos literales

Todos los textos del onboarding, con su identificador, su destino y su
versión. Los que son **consentimiento** van a `ConsentTexts` (con hash); los
demás a `OnboardingTexts` (sin hash, pero versionados).

### 11.1 `ConsentTexts` (con hash — son prueba legal)

| Id | Texto | Aparece en |
|---|---|---|
| `ENROLL_HOSTED` | (el texto largo de §4.3.1) | Paso 3 del alta del alumno, variante alojada |
| `ENROLL_LOCAL` | (el texto de §4.3.2) | Paso 3 del alta del alumno, variante local |
| `ENROLL_THIRD_PARTY` | `⚠ Esta cátedra usa una IA de terceros (OpenAI), fuera de la red de la universidad. Tus conversaciones se envían a ese servicio para generar las respuestas, y Kitoru no puede garantizar que ese servicio no las use para entrenar sus modelos. La cátedra declara que no lo hace.` | Antepuesto a `ENROLL_HOSTED` |

**Versión:** `ConsentTexts.VERSION` pasa de `"2026-04-01"` a `"2026-10-21"`.
Las inscripciones existentes **conservan** su `textHash` y `textVersion`.

### 11.2 `OnboardingTexts` (versionados, sin hash)

| Id | Texto |
|---|---|
| `WELCOME.TITLE` | `Kitoru` |
| `WELCOME.SUBTITLE` | `La IA de tu cátedra, en tu computadora.` |
| `WELCOME.JOIN` | `Unirme a una cátedra` |
| `WELCOME.JOIN.HINT` | `Escribí el código que te dio tu docente.` |
| `WELCOME.CREATE` | `Crear una cátedra (soy docente)` |
| `WELCOME.SAMPLE` | `Probar con una cátedra de ejemplo` |
| `WELCOME.SAMPLE.HINT` | `Tenés material de muestra para probar la app sin subir nada tuyo. Podés borrarla cuando quieras.` |
| `WELCOME.WHAT` | `¿Qué es Kitoru?` |
| `TEACHER.NAME.TITLE` | `¿Cómo te llamás?` |
| `TEACHER.NAME.HINT` | `Es el nombre que muestra Kitoru en esta computadora. Tus alumnos no lo ven: en la cátedra aparecés con el nombre que elijas cuando te sumes.` |
| `TEACHER.NAME.ANON` | `No guardes mi nombre (usá "Docente")` |
| `TEACHER.COURSE.TITLE` | `Creá tu cátedra` |
| `TEACHER.COURSE.CODE.HINT` | `Se usa para ordenar tus cátedras y para mostrarlas en el padrón.` |
| `TEACHER.COURSE.TERM.HINT` | `Opcional. Sirve para distinguir dos cursadas de la misma materia.` |
| `TEACHER.COURSE.HOST.TITLE` | `¿Dónde va a vivir el material?` |
| `TEACHER.COURSE.HOST.LOCAL` | `En esta computadora y la comparto yo (archivo .kitoru)` |
| `TEACHER.COURSE.HOST.SERVER` | `En el servidor de la institución` |
| `TEACHER.COURSE.SUBMIT` | `Crear la cátedra` |
| `TEACHER.CODE.TITLE` | `Tu cátedra está creada` |
| `TEACHER.CODE.HINT` | `Todos los alumnos usan este mismo código. Podés rotarlo cuando quieras, por ejemplo si se filtró a un grupo de WhatsApp.` |
| `TEACHER.CODE.CTA` | `Ir al panel de la cátedra →` |
| `TEACHER.MATERIAL.TITLE` | `Subí el primer material` |
| `TEACHER.MATERIAL.HINT` | `Es lo que tus alumnos van a preguntarle a la IA. Un PDF, un Word, una presentación o un archivo de texto: lo que uses para enseñar.` |
| `TEACHER.MATERIAL.FORMATS` | `Formatos: PDF, Word, PowerPoint, Markdown, texto, CSV` |
| `TEACHER.MATERIAL.SAMPLE` | `¿No tenés material a mano? Usá el material de ejemplo y reemplazalo cuando quieras.` |
| `TEACHER.MATERIAL.WHAT` | `¿Qué va a pasar? Vamos a leer el texto del archivo y a partirlo en fragmentos. Después, cuando un alumno pregunte algo, la IA busca en esos fragmentos y te dice de dónde salió.` |
| `TEACHER.MODEL.TITLE` | `Configurá el modelo de IA` |
| `TEACHER.MODEL.HINT` | `Tus alumnos van a chatear con este modelo. Si tu institución tiene un servidor de IA, pegá la dirección y la clave.` |
| `TEACHER.MODEL.TEST` | `Probar conexión` |
| `TEACHER.MODEL.TEST_OK` | `● La clave funciona` |
| `TEACHER.MODEL.LOCAL` | `Usar la IA en mi computadora (descargás un modelo de ~5 GB)` |
| `TEACHER.MODEL.LATER` | `Podés dejar esto para más tarde. Tus alumnos van a poder entrar a la cátedra igual, pero no van a poder chatear hasta que haya un modelo configurado.` |
| `TEACHER.MODEL.LATER_BTN` | `Lo configuro más tarde` |
| `TEACHER.INVITE.TITLE` | `Invitá a tus alumnos` |
| `TEACHER.INVITE.HINT` | `Pasales el código. Todos usan el mismo.` |
| `TEACHER.INVITE.LINK` | `Enlace de inscripción` |
| `TEACHER.INVITE.EXPORT` | `Si alguno de tus alumnos no tiene internet en el momento podés exportar la cátedra como archivo y pasárselo: funciona sin conexión.` |
| `TEACHER.INVITE.EXPORT_BTN` | `Exportar archivo .kitoru` |
| `TEACHER.INVITE.NOT_PUBLISHED` | `La cátedra todavía no está publicada. Con el código no pasa nada todavía: publicala cuando tengas el material.` |
| `SAMPLE.RIBBON` | `Cátedra de ejemplo. Funciona como una materia real: los alumnos se pueden anotar con el código KTR7-F3K3 y ver el material. Borrala cuando quieras: no se mezcla con nada tuyo.` |
| `SAMPLE.RIBBON_BTN` | `Borrar la cátedra de ejemplo` |
| `SAMPLE.STUDENT_NOTE` | `Estás en una cátedra de ejemplo. El material habla de Kitoru, no de una materia real.` |
| `SMOKE.RIBBON` | `Estás probando el chat como un alumno. Esta conversación no entra en la analítica de la cátedra y no la ve ningún docente.` |
| `SMOKE.DRAFT_NOTE` | `Estás probando el borrador. Tus alumnos no ven nada hasta que publiques.` |
| `SMOKE.CLEAR` | `Probaste 3 veces. Estas conversaciones no cuentan para la analítica.` |
| `SMOKE.CLEAR_BTN` | `Borrar las pruebas` |
| `INDEX.PENDING` | `Preparando el archivo…` |
| `INDEX.EXTRACTING` | `Leyendo el texto del archivo…` |
| `INDEX.INDEXING` | `Partiendo el material en fragmentos…` |
| `INDEX.READY` | `Listo. El material aporta 87 fragmentos al índice de la cátedra.` |
| `INDEX.SCANNED` | `Este PDF parece escaneado: no tiene capa de texto, así que la IA no puede leerlo. Podés subirlo de nuevo como PDF con texto seleccionable, o pegar el contenido en un archivo .md.` |
| `MIGRATION.NOTICE` | `Tus cátedras, tu material y tus conversaciones siguen como estaban. Algunas formas nuevas no aparecen hasta que las vuelvas a usar.` |
| `OFFLINE.TEACHER` | `Sin conexión con el servidor. Podés seguir trabajando: crear la cátedra, subir material y configurarlo todo funciona sin internet. Lo que necesita conexión es el modelo de IA que van a usar los alumnos.` |
| `OFFLINE.STUDENT_LOCAL` | `Te anotaste sin conexión. Tu inscripción y el material de la cátedra quedan en esta computadora. Cuando vuelva la conexión, se sincronizan solos.` |

### 11.3 Los textos del tutor (`GuidedChat`)

| Id | Texto |
|---|---|
| `INTRO.WITH_MATERIAL` | (el texto de §6.2) |
| `INTRO.NO_MATERIAL` | (el texto de §6.2, caso sin material) |
| `INTRO.AUTHOR` | `Tutor de Kitoru` |
| `INTRO.BADGE` | `Guía inicial` |

---

## 12. Modelo de datos y archivos nuevos

### 12.1 Tipos nuevos en `:core`

| Archivo | Contenido |
|---|---|
| `domain/AppConfig.kt` | `AppConfig` con `schemaVersion`, `profile`, `uiMode`, `lastCourseId`, `theme`, `model`, `modelCatalog`, `sampleNudgeShown`, `draftCourse` (borrador del wizard, para poder retomarlo) |
| `domain/SchemaVersion.kt` | `SchemaVersion.CURRENT`, `isTooNew(found)` |
| `domain/Migrations.kt` | `Migrations.migrate(config, from)` + `NOTICES` + `noticeFor(from)` |
| `domain/AppBootstrap.kt` | `AppBootstrap.loadOrCreate(fs, paths)` + `BootResult` |
| `domain/AppEntryState.kt` | `AppEntryState` + `appEntryState(profile, enrollments)` (ya diseñado en `student/01-entrada.md` §2.1) |
| `domain/CourseReadiness.kt` | `ReadinessItem`, `ReadinessTarget`, `Readiness.of(...)` |
| `domain/SampleCourse.kt` | `SampleCourse.install(...)`, `FILES`, `JOIN_CODE`, `SampleFile` |
| `domain/GuidedChat.kt` | `GuidedChat.introMessage(...)`, `suggestions(...)`, `INTRO_VERSION` |
| `domain/OnboardingTexts.kt` | El catálogo de `OnboardingTexts` (§11.2) |
| `domain/Privacy.kt` (extensión) | `ConsentTexts.ENROLL_HOSTED` / `ENROLL_LOCAL` (ya existe el objeto en `security/02-privacidad.md`) |
| `domain/Chat.kt` (extensión) | `MessageOrigin`, `ChatOrigin`, `ChatThread.smokeTest` |
| `domain/Course.kt` (extensión) | `Course.sample` |

### 12.2 Recursos

| Ruta | Contenido |
|---|---|
| `core/src/commonMain/resources/sample/01-como-funciona.md` | ~600 palabras |
| `core/src/commonMain/resources/sample/02-el-distintivo-de-citas.md` | ~450 palabras |
| `core/src/commonMain/resources/sample/03-visibilidad-y-privacidad.md` | ~500 palabras |
| `core/src/commonMain/resources/templates/system_prompt.md` | La plantilla de instrucciones de cátedra |

### 12.3 Componentes nuevos en `:ui`

| Archivo | Qué hace |
|---|---|
| `ui/commonMain/.../onboarding/WelcomeScreen.kt` | La primera pantalla (§3.1) |
| `ui/commonMain/.../onboarding/TeacherCourseWizard.kt` | Los 2 pasos del docente (§3.2–3.3) |
| `ui/commonMain/.../onboarding/WhatIsKitoruDialog.kt` | Las tres viñetas de "¿Qué es Kitoru?" |
| `ui/commonMain/.../onboarding/PrivacyNoticeCard.kt` | El texto A' y su variante local (§4.3) |
| `ui/commonMain/.../onboarding/SampleCourseCard.kt` | La tarjeta de la cátedra de ejemplo |
| `ui/commonMain/.../onboarding/ReadinessCard.kt` | El checklist de puesta en marcha (§3.9) |
| `ui/commonMain/.../onboarding/GuidedIntroMessage.kt` | La burbuja del tutor + los chips de sugerencias |
| `ui/commonMain/.../teacher/materials/IndexingProgressCard.kt` | El panel de indexación (§7.2) |
| `ui/commonMain/.../teacher/materials/MaterialProbeCard.kt` | La pregunta de prueba (§7.3) |
| `ui/commonMain/.../teacher/SmokeTestRibbon.kt` | La cinta del modo de prueba (§9.3) |
| `ui/commonMain/.../teacher/SampleRibbon.kt` | La cinta de cátedra de ejemplo (§5.4) |
| `ui/commonMain/.../shell/LoadingPane.kt` | El port de `LoadingPane.kt` de FunshiEngineAI (§2.4) |
| `ui/commonMain/.../shell/SchemaTooNewScreen.kt` | La pantalla bloqueante de §8.4 |

**Componentes reusados sin tocar:** `CitationBadge` (`teacher/04-prompt.md`
§6.4), `MarkdownText`, `CodeBlock`, `DetailPane`, `EmptyState`, `ConfirmDialog`,
`humanSize` (`app/core/humanSize.kt` de FunshiEngineAI).

### 12.4 Cambios en código existente

| Archivo | Cambio |
|---|---|
| `core/.../domain/Repositories.kt` | `CourseRepository.findByJoinCode(code)` ya está en `domain/09-identidad.md` §10.3: hace falta para la cátedra de ejemplo y para el alta |
| `core/.../domain/AnalyticsAnalyzer.kt` | `analyze(..., excludeSmokeTests: Boolean = true)` |
| `core/.../domain/Chat.kt` | `ChatMessage.origin`, `ChatThread.origin`, `ChatThread.smokeTest` |
| `core/.../domain/Course.kt` | `Course.sample` |
| `:desktop` / `:android` | Llamar a `AppBootstrap.loadOrCreate()` antes de componer la UI |

---

## 13. Conflictos con otros documentos

Este documento registra los conflictos que encontró y **propone** resolverlos.
No los resuelve por su cuenta: son decisiones del proyecto.

| # | Conflicto | Dónde | Propuesta |
|---|---|---|---|
| C1 | **Largo del código de inscripción**: 8 chars base32 (`domain/01-catedra.md` §2.1) vs. 6 chars Crockford (`teacher/02-editor-catedra.md` D6) vs. `IA-7F3K` (7 chars con prefijo, `student/01-entrada.md` §3.1) | tres documentos | **8 caracteres base32**, mostrados `ABCD-EFGH`, que es lo que define el dominio. El prefijo de la materia se muestra aparte, como etiqueta. |
| C2 | **Alfabeto**: base32 sin `0,1,I,O` (dominio) vs. Crockford sin `I,L,O,U` (doc del editor) | dos documentos | Un solo alfabeto en `JoinCode`: Crockford (`0,1,I,L,O,U` afuera). El código de la ejemplo (`KTR7F3K3`) es válido en los dos, así que no bloquea nada. |
| C3 | **Nombre del archivo de configuración**: `config/app.json` (`process/02-roadmap.md` F1) vs. `config/app-config.json` (`process/01-packaging-ci.md` §6.1) | dos documentos | **`config/app-config.json`**: es el mismo nombre que usa FunshiEngineAI y el que ya está especificado con contenido. El roadmap se actualiza. |
| C4 | **Número de pasos del alta del alumno**: "Paso 1 de 3" (`student/01-entrada.md`) vs. "Paso 1 de 4" (acá) | dos documentos | El contador se **calcula** con los pasos que aparecen (§4.1). Se actualiza `student/01-entrada.md`. |
| C5 | **Texto A del consentimiento**: `security/02-privacidad.md` §3.2 vs. el texto A' de §4.3.1 | dos documentos | El texto A' **extiende** al A (agrega la lista de "NO puede ver" y la frase del techo). Se bumpea `ConsentTexts.VERSION` a `"2026-10-21"`. Las inscripciones viejas conservan su hash. |
| C6 | **"Probar el chat"**: el editor usa el borrador (`teacher/02-editor-catedra.md` D10) | un documento | Se unifican las dos variantes (§9.2): desde el editor, el borrador; desde el panel, lo publicado. El badge del hilo (`SMOKE_TEST`) es el mismo. |

---

## 14. Checklist de implementación

### 14.1 `:core`

- [ ] `AppConfig` con `schemaVersion` (default `0`) y `AppConfig.empty()`.
- [ ] `SchemaVersion.CURRENT` + `isTooNew`.
- [ ] `Migrations.migrate(config, from)` + `NOTICES` + `noticeFor(from)`.
- [ ] `AppBootstrap.loadOrCreate(fs, paths)` con la copia de seguridad previa.
- [ ] `appEntryState(profile, enrollments)`.
- [ ] `Course.sample`, `ChatMessage.origin`, `ChatThread.origin`, `ChatThread.smokeTest`.
- [ ] `Readiness.of(course, materials, hasSmokeTest)`.
- [ ] `SampleCourse.install(...)` idempotente + los tres `.md` en `resources/sample/`.
- [ ] `GuidedChat.introMessage(...)` + `suggestions(...)`.
- [ ] `OnboardingTexts` con los ~60 textos de §11.2.
- [ ] `ConsentTexts.ENROLL_HOSTED` / `ENROLL_LOCAL` + bumpe de `VERSION`.
- [ ] `AnalyticsAnalyzer.analyze(..., excludeSmokeTests)`.
- [ ] `EnrollmentService.createGuidedThread(...)`.

### 14.2 `:ui`

- [ ] `WelcomeScreen` con las tres puertas.
- [ ] `TeacherCourseWizard` (2 pasos).
- [ ] `PrivacyNoticeCard` con las dos variantes y el checkbox obligatorio.
- [ ] `ReadinessCard` en el panel del docente.
- [ ] `GuidedIntroMessage` + chips de sugerencias.
- [ ] `IndexingProgressCard` + `MaterialProbeCard` (reusar la pestaña "Probar pregunta").
- [ ] `SmokeTestRibbon` + `SampleRibbon`.
- [ ] `LoadingPane` (port de FunshiEngineAI) con las fases de Kitoru.
- [ ] `SchemaTooNewScreen`.
- [ ] Banner de migración en la home.

### 14.3 Pruebas

| Test | Dónde | Qué prueba |
|---|---|---|
| `AppBootstrapTest` | `:core` `desktopTest` | Instala limpia → crea todos los archivos; con `schemaVersion` viejo → migra y deja copia; con `schemaVersion` mayor → **no escribe nada** |
| `MigrationsTest` | `:core` `desktopTest` | Un test por paso de migración; idempotencia (migrar dos veces = lo mismo) |
| `AppEntryStateTest` | `:core` `commonTest` | Las tres combinaciones de perfil/inscripciones |
| `ReadinessTest` | `:core` `commonTest` | Los seis ítems se calculan bien a partir de los datos |
| `SampleCourseTest` | `:core` `desktopTest` | Instalar dos veces no duplica; el texto se extrae y el índice tiene fragmentos; borrar deja el disco limpio |
| `GuidedChatTest` | `:core` `commonTest` | Las sugerencias se derivan de los materiales; sin material hay una sola; el texto tiene el largo esperado |
| `OnboardingTextsTest` | `:core` `commonTest` | Ningún texto tiene placeholders sin reemplazar; todos los IDs de §11.2 existen; los textos de consentimiento tienen su hash |
| `AnalyticsSmokeTestTest` | `:core` `commonTest` | Un hilo `smokeTest = true` no altera ningún número de la analítica |

### 14.4 Prueba manual (el guion de los 5 minutos)

En `docs/design/process/scripts/onboarding-5min.md`, cronometrado, con 5
docentes que nunca vieron la app:

1. Abrir Kitoru → "Crear una cátedra" → nombre → crear → copiar el código.
2. Configurar el modelo (URL y clave de prueba) → "Probar conexión".
3. "Probar el chat" → hacer una pregunta.
4. Subir un PDF de 20 páginas.
5. "Exportar archivo .kitoru".

**Se mide**: tiempo total, número de personas que se pierden en un paso,
número de preguntas que hacen. **Criterio de aceptación**: mediana ≤ 5:00 y
cero personas que se pierden después del paso 2.

---

## 15. Casos borde

| Caso | Comportamiento |
|---|---|
| El docente cierra la app en medio del wizard | El borrador del wizard se guarda en `AppConfig.draftCourse` (con default `null`). Al reabrir, se ofrece "Retomar: te faltó crear la cátedra «IA»". |
| El docente crea dos cátedras con el mismo nombre | Permitido (el nombre no es clave). El código de la materia se valida contra las otras cátedras: `Ya existe una cátedra con el código «IA».` |
| El alumno se inscribe, borra la app y vuelve | Pierde la inscripción local (no hay cuenta). Se lo dice al reingresar con el código: `Ya te habías anotado en esta cátedra en este dispositivo, pero se borraron los datos. Volvé a anotarte.` (o se le pide el código de verificación). |
| El alumno se inscribe con el código de la cátedra de ejemplo | Ve la nota `Estás en una cátedra de ejemplo…` (§5.4). |
| El docente rota el código y hay alumnos inscriptos | Las inscripciones viejas siguen funcionando (`domain/01-catedra.md` §2.5). |
| El material del primer material falla al extraerse | Se muestra el mensaje de §7.4 y **el docente puede seguir**: la cátedra se publica igual, y el chat responde sin citas con el aviso `Sin citas: respondió sin el material`. |
| El modelo remoto devuelve 401 | `La clave no fue aceptada por el servidor. Revisala o pedile una nueva.` El chat **no** se bloquea para siempre: queda el botón "Probar conexión" otra vez. |
| La red se corta en mitad de una respuesta | El mensaje parcial se conserva y se marca `(detenido)` (`student/02-chat.md` §3.4). |
| El usuario tiene dos perfiles en el mismo dispositivo | Cada perfil tiene su propio onboarding (son datos separados, `domain/09-identidad.md` §9). |
| La app se actualiza mientras está abierta | No se actualiza en caliente. El aviso de migración aparece en el **siguiente** arranque. |
| El usuario borra `config/app-config.json` a mano | Los archivos por tipo siguen ahí: la app los reconstruye y **no** borra las cátedras. El aviso `Guardamos tu clave en el almacén seguro…` (`security/01-claves.md` §3.4) no aplica. |
| La cátedra de ejemplo se borra mientras un alumno está chateando | El chat del alumno muestra `Esta cátedra ya no está disponible.` y no borra el historial local del alumno. |
| El docente nunca toca el checklist | La cátedra sigue funcionando con lo que haya. **El onboarding nunca bloquea.** |

---

## 16. Resumen en cinco líneas

1. **La primera ejecución no es una pantalla sino un estado derivado** (`appEntryState`): sin perfil → bienvenida, sin inscripciones → bienvenida, con inscripciones → home; por eso una migración de esquema nunca repite el onboarding.
2. **El docente tiene un wizard de 2 pasos (nombre → crear la cátedra) y después un checklist permanente**; el objetivo medible es **5:00 hasta "cátedra creada, con código activo y lista para invitar"**, medido con un guion cronometrado.
3. **El alumno ve una sola pantalla de privacidad** que es también la elección de IA (alojada o local): lista **qué ve el docente y qué NO**, exige un checkbox sin pre-marcar, y se versiona y hashea (`ConsentTexts` v2026-10-21) porque es la prueba del consentimiento.
4. **La cátedra de ejemplo** es una cátedra real marcada (`Course.sample = true`) con material de muestra en el binario, código fijo `KTR7-F3K3`, que se borra con un clic y **nunca sola**; el primer chat es un hilo sembrado con un mensaje del tutor que explica las citas y ofrece sugerencias derivadas del material real.
5. **El feedback de la indexación termina en una pregunta de prueba** (nivel BM25 sin red y nivel RAG completo) y el docente tiene un **modo de prueba** del chat (`SMOKE_TEST`, excluido de la analítica) que es lo que convierte al docente en el producto antes de invitar a nadie.
