# 08 — Estrategia de testing del motor de analítica

> Subagente: diseño de la **estrategia de tests** del motor de analítica de
> Kitoru (`AnalyticsAnalyzer` y las funciones de dominio nuevas) y de las
> funciones de dominio que las sostienen.
>
> Módulos tocados: `:core` (dominio + plataforma), `:backend` (RAG + agente).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast con mensajes en español*.

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | Los tests del dominio (`AnalyticsAnalyzer`, `ModelPolicy`, `JsonStore`) van en **`:core/commonTest` con `kotlin.test`**. | Son funciones puras de `commonMain`: los mismos fuentes corren en `desktopTest` **y** en `androidUnitTest`, sin duplicar nada y con la garantía de que la app Android no se rompe. |
| D2 | El test de integración (flujo completo) va en **`:backend/desktopTest` con JUnit 5**. | Cruza `:core` + `:backend` + `:llm-api`, y reusa el doble `ScriptedGateway` que ya usa el `AgentLoopTest` portado de FunshiEngineAI. |
| D3 | **No se agrega ninguna librería de property-based testing.** Se genera aleatoriedad con `kotlin.random.Random(semilla fija)` dentro de `kotlin.test`. | Una dependencia nueva (kotest/quickcheck) rompe la portabilidad a Android y pesa de más para lo que acá hace falta: verificar invariantes, noshrinkear. Con semilla fija el fallo es reproducible. |
| D4 | La persistencia se testea con un **`InMemoryFileSystem`** (doble de `FileSystem` en `commonTest`), no contra el disco. | El disco real no es determinista y no existe en Android igual que en escritorio. El FS real se cubre con un smoke test en `desktopTest`. |
| D5 | Los tests son **deterministas**: ni red, ni reloj, ni disco real. Los tiempos son constantes (`DIA = 1_699_920_000_000`). | Un test que depende de `nowMillis()` es un test que falla un día sí y otro no. |
| D6 | El analizador se testea **por contrato**, no por implementación: los tests fijan la regla de negocio ("un alumno `NONE` no aparece en ningún agregado"), no el algoritmo. | Si el algoritmo cambia, los tests siguen valiendo. |
| D7 | **P0 bloqueante:** hoy los tests de `:backend` **no se ejecutan** (0 tests) porque falta `useJUnitPlatform()`. Con el arreglo, corren 108 y pasan. | Sin esto, "tenemos tests" es falso: es la primera línea de la estrategia. Ver §1.5. |

### Cobertura de lo pedido

| Pedido | Dónde está resuelto |
|---|---|
| 1. Qué se testea y con qué framework, y dónde va cada test | §1.1–§1.4 (regla de decisión + tabla final) y §1.6–§1.7 (convenciones y fixtures) |
| 2. Casos de test del `AnalyticsAnalyzer` (≥15, con 4 en código) | §2.1 (36 casos, T01–T36) y §2.2 (5 tests completos en Kotlin) |
| 3. Property-based testing | §3 (decisión, 10 invariantes y el test con semilla fija) |
| 4. Casos borde | §4 (19 casos, B01–B19) + §4.2 (código) |
| 5. Tests de `ModelPolicy.resolveFor` | §5 (tabla de bloqueos campo por campo + código) |
| 6. Tests de la persistencia con FS en memoria | §6 (`InMemoryFileSystem` completo + store, atómica, concurrencia, repos) |
| 7. Test de integración con gateway falso | §7 (`ScriptedGateway` portado + flujo completo) |
| 8. Qué no se testea y por qué | §8 (11 ítems con riesgo y mitigación) |

---

## 1. Qué se testea y con qué framework

### 1.1 El mapa de source sets (cómo está armado hoy)

`:core` y `:backend` declaran los mismos source sets, con un eslabón
`jvmCommonMain`/`jvmCommonTest` para el código que comparten escritorio y
Android:

```
core/src/
├── commonMain/     ← el dominio (AnalyticsAnalyzer, ModelPolicy, JsonStore…)
├── commonTest/     ← kotlin.test  (tests portables, corren en desktop Y android)
├── desktopTest/    ← JUnit 5      (tests que necesitan el disco real)
├── androidUnitTest/← (hereda commonTest)
└── jvmCommonMain/  ← las implementaciones JVM de las plataformas
```

`:core/build.gradle.kts` ya declara en `commonTest`:
`kotlinx.coroutines.test` y `kotlin("test")`. `:backend/build.gradle.kts` ya
declara en `desktopTest`: `junit-jupiter` + `junit-platform-launcher`.

### 1.2 La regla de decisión

```
¿El código bajo prueba vive en commonMain de :core?
    ├─ sí  → :core/commonTest, kotlin.test
    └─ no  → ¿cruza :core + :backend + :llm-api?
              ├─ sí → :backend/desktopTest, JUnit 5
              └─ no → ¿necesita el disco real?
                        ├─ sí → :core/desktopTest, JUnit 5
                        └─ no → :core/commonTest, kotlin.test
```

**Justificación de fondo.** El dominio de `:core` es, por diseño, portable a
Android: es la misma app que corre en el teléfono del alumno. Un test escrito
en `commonTest` se ejecuta **dos veces** con un solo archivo de código
(`desktopTest` y `androidUnitTest`). Eso no es un detalle: garantiza que la
lógica de analítica no use nada de JVM por accidente.

En `:backend/desktopTest` se usa **JUnit 5** porque es el framework que ya
traen los tests portados de FunshiEngineAI (`Bm25IndexTest`,
`CitationVerifierTest`, `AgentLoopTest`, …) y porque el doble `ScriptedGateway`
ya está escrito en ese estilo. Mezclar frameworks dentro de un mismo módulo
sería peor que la alternativa.

### 1.3 Tabla final: archivo → source set → framework → por qué

| Archivo de test | Source set | Framework | Qué cubre |
|---|---|---|---|
| `core/.../domain/AnalyticsAnalyzerTest.kt` | `commonTest` | `kotlin.test` | Los 36 casos de §2 (métricas, visibilidad, días, latencia, fuentes, inactivos). |
| `core/.../domain/StudentAnalyticsTest.kt` | `commonTest` | `kotlin.test` | Las métricas derivadas (`citationSupportRate`, `ungroundedAnswerRate`) y `CitationStats`. |
| `core/.../domain/ModelPolicyResolveTest.kt` | `commonTest` | `kotlin.test` | La tabla de bloqueos de §5 + la fuga de la API key (H9). |
| `core/.../domain/JsonStoreTest.kt` | `commonTest` | `kotlin.test` + `runTest` | Guardar/cargar, archivo corrupto, ausente, escritura atómica, concurrencia (§6). |
| `core/.../domain/FileRepositoriesTest.kt` | `commonTest` | `kotlin.test` + `runTest` | Los repositorios sobre el FS en memoria (incluye H7). |
| `core/.../domain/AnalyticsPropertiesTest.kt` | `commonTest` | `kotlin.test` | Invariantes con datos aleatorios de semilla fija (§3). |
| `core/.../test/InMemoryFileSystem.kt` | `commonTest` | — | El doble de `FileSystem` (no es un test, es infraestructura). |
| `core/.../test/Fixtures.kt` | `commonTest` | — | Builders de `Course`, `Enrollment`, `ChatThread`, `ChatMessage` y constantes de tiempo. |
| `core/.../platform/SystemFileSystemTest.kt` | `desktopTest` | JUnit 5 | Smoke test del FS real sobre un directorio temporal (5 casos). |
| `backend/.../rag/test/ScriptedGateway.kt` | `desktopTest` | — | El doble de gateway, **extraído** del `AgentLoopTest` para reusarlo. |
| `backend/.../kitoru/analytics/AnalyticsFlowIntegrationTest.kt` | `desktopTest` | JUnit 5 | El flujo completo de §7. |

### 1.4 Qué falta en el build (cambios de build, no de código de tests)

Estos tres cambios son **prerrequisitos** de la estrategia. Son de
`build.gradle.kts`, no de código de producción:

**(a) `:core` necesita JUnit 5 en `desktopTest`** (hoy sólo tiene `commonTest`):

```kotlin
// core/build.gradle.kts
desktopTest.dependencies {
    implementation(libs.junit.jupiter)
    runtimeOnly(libs.junit.platform.launcher)
    implementation(libs.kotlinx.coroutines.test)
}
```

**(b) Activar JUnit Platform en los tests de escritorio** (raíz):

```kotlin
// build.gradle.kts (raíz)
subprojects {
    tasks.withType<Test>().configureEach {
        // Sólo los tests de escritorio. Los de Android siguen con JUnit 4
        // (default de AGP), que es lo que `kotlin("test")` resuelve en
        // `androidUnitTest`.
        if (name == "desktopTest") {
            useJUnitPlatform()
        }
    }
}
```

Alternativa KMP idiomática (por módulo):

```kotlin
kotlin {
    jvm("desktop") {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
}
```

> Con `useJUnitPlatform()` activo **sólo** en `desktopTest`, la resolución de
> `kotlin("test")` por target hace lo correcto: `kotlin-test-junit5` en
> escritorio y `kotlin-test-junit` en Android. Los **mismos** fuentes de
> `commonTest` compilan contra los dos. `kotlin.test.Test` es un `typealias`,
> así que el nombre del test no cambia.

**(c) Un alias de tarea para correr todo:**

```kotlin
// build.gradle.kts (raíz)
tasks.register("testAll") {
    group = "verification"
    description = "Corre los tests de escritorio de todos los módulos."
    dependsOn(
        ":core:desktopTest",
        ":backend:desktopTest",
        ":llm-api:desktopTest",
    )
}
```

### 1.5 P0 — hoy los tests de `:backend` NO se ejecutan

Esto se verificó **corriendo el build**, no leyendo el código:

```console
$ ./gradlew :backend:cleanDesktopTest :backend:desktopTest
...
BUILD SUCCESSFUL          # pero el reporte dice:
tests = 0   failures = 0   ignored = 0
```

El `desktopTest` de `:backend` declara `junit-jupiter`, pero la tarea Gradle
corre con el runner de JUnit 4 (default) → **ningún** test JUnit 5 se
ejecuta y el build pasa en verde. Con el arreglo de §1.4(b):

```console
$ ./gradlew :backend:cleanDesktopTest :backend:desktopTest -I junit5.init.gradle
...
tests = 108   failures = 0   ignored = 0   duration = 1.057s
```

Clases ejecutadas: `rag.agent.AgentLoopTest`, `rag.engine.CitationVerifierTest`,
`rag.retrieval.Bm25IndexTest`, `rag.retrieval.ChunkerTest`,
`rag.agent.PromptToolParserTest`, `rag.agent.ToolCallSanitizerTest`,
`rag.agent.ToolPermissionsTest`, `rag.agent.ToolRegistryTest`.

> La verificación se hizo con un **init script de Gradle fuera del repo**
> (`-I junit5.init.gradle`, con `tasks.withType<Test> { useJUnitPlatform() }`)
> para no tocar el build durante la exploración. El arreglo definitivo es el
> del §1.4(b), en el `build.gradle.kts` de la raíz.

> **Consecuencia para la estrategia:** hasta que (b) no esté aplicado, un
> `BUILD SUCCESSFUL` no significa nada. El primer paso del plan de tests es
> **verificar que el reporte diga `tests > 0`**, y en CI agregar un paso que
> falle si el total de tests es cero (ver §9).

### 1.6 Convenciones de estilo (heredadas de FunshiEngineAI)

- **Nombres de test en español, entre acentos graves, en tercera persona y
  describiendo el comportamiento**: ``fun `una cita literal esta respaldada` ``.
- **Un assert por idea**, con mensaje cuando hay contexto:
  `assertEquals(3, a.totalMessages, "los mensajes del docente no cuentan")`.
- **Sin `Thread.sleep`, sin `nowMillis()`, sin red, sin disco real.**
- **Cada test es independiente**: no comparte estado mutable salvo
  `KitoruPaths`, que se restaura en un `finally`.
- Los doubles viven en archivos propios (`ScriptedGateway`,
  `InMemoryFileSystem`), no anidados en la clase de test, cuando los usan
  más de un archivo.
- Documentación de la clase de test: qué reglas del negocio cubre (igual que
  `CitationVerifierTest` y `AgentLoopTest`).

### 1.7 Fixtures compartidos (`core/src/commonTest/kotlin/kitoru/test/Fixtures.kt`)

```kotlin
package kitoru.test

import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.Course
import kitoru.domain.CourseRole
import kitoru.domain.CitationStats
import kitoru.domain.Enrollment
import kitoru.domain.ChatVisibility

/** Medianoche UTC del 14/11/2023. Todos los tiempos de los tests son fijos. */
const val DIA: Long = 1_699_920_000_000
const val DIA_2: Long = DIA + 86_400_000L
const val DIA_3: Long = DIA + 2 * 86_400_000L

fun curso(id: String = "c1", analyticsEnabled: Boolean = true) = Course(
    id = id,
    code = "IA-2026-1",
    name = "Inteligencia Artificial",
    term = "2026-1",
    joinCode = "ABC123",
    analyticsEnabled = analyticsEnabled,
)

fun inscripcion(
    userId: String,
    nombre: String = userId,
    rol: CourseRole = CourseRole.ALUMNO,
    visibilidad: ChatVisibility = ChatVisibility.FULL,
    courseId: String = "c1",
) = Enrollment(
    // El id incluye la cátedra: el mismo usuario puede estar en dos cátedras.
    id = "e-$courseId-$userId",
    courseId = courseId,
    userId = userId,
    displayName = nombre,
    role = rol,
    joinedAt = DIA,
    visibility = visibilidad,
)

fun hilo(id: String, autorId: String, courseId: String = "c1") = ChatThread(
    id = id,
    courseId = courseId,
    authorId = autorId,
    authorName = autorId,
    createdAt = DIA,
    updatedAt = DIA,
)

fun mensajeUsuario(
    id: String,
    hiloId: String,
    at: Long = DIA,
    tokensIn: Int = 0,
    fuentes: List<String> = emptyList(),
) = ChatMessage(
    id = id,
    threadId = hiloId,
    role = "user",
    text = "¿Qué es el RAG?",
    createdAt = at,
    sources = fuentes,
    tokensIn = tokensIn,
)

fun mensajeAsistente(
    id: String,
    hiloId: String,
    at: Long = DIA,
    latenciaMs: Long = 0,
    citas: CitationStats? = null,
    fuentes: List<String> = emptyList(),
    tokensOut: Int = 0,
) = ChatMessage(
    id = id,
    threadId = hiloId,
    role = "assistant",
    text = "El RAG recupera fragmentos relevantes.",
    createdAt = at,
    sources = fuentes,
    citation = citas,
    latencyMs = latenciaMs,
    tokensOut = tokensOut,
)
```

---

## 2. Casos de test del `AnalyticsAnalyzer`

El analizador es una **función pura**: `(course, enrollments, threads,
messages) → CourseAnalytics`. No toca disco, no llama al modelo, no lee el
reloj. Por eso se puede testear al 100% y los números que ve el docente son
reproducibles.

### 2.1 Tabla de casos

Leyenda de la columna **Estado**: `OK` = hoy pasa; `⚠ Hn` = **falla hoy** y
el test fija el comportamiento correcto (ver §2.3).

#### A. Cátedra vacía y alumnos sin actividad

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T01 | `una catedra sin datos devuelve todos los agregados en cero` | sin inscripciones, sin hilos, sin mensajes | `students=0`, `activeStudents=0`, `teachers=0`, `totalMessages=0`, `totalReplies=0`, `totalThreads=0`, `avgMessagesPerStudent=0.0`, `citationSupportRate=0.0`, `avgLatencyMs=0`, `totalTokens=0`, y `perStudent`/`perDay`/`topSources`/`inactiveStudents` vacíos | OK |
| T02 | `un alumno sin mensajes aparece con todo en cero y como inactivo` | 1 inscripción `ALUMNO`, sin hilos ni mensajes | `perStudent.size=1` con `messages=0`, `replies=0`, `threads=0`, `activeDays=0`, `lastActivity=0`, `avgLatencyMs=0`; `students=1`, `activeStudents=0`, `inactiveStudents=["Ana"]` | OK |
| T03 | `un docente cuenta como docente y no como alumno` | 1 inscripción `TITULAR` + 1 `ALUMNO` | `teachers=1`, `students=1`; el docente aparece en `perStudent` con su rol | OK |

#### B. Conteo de mensajes y respuestas

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T04 | `cuenta los mensajes y las respuestas de cada alumno` | Ana: 1 hilo, 2 mensajes de usuario + 2 respuestas. Beto: 2 hilos, 1 mensaje + 1 respuesta | Ana: `messages=2`, `replies=2`, `threads=1`. Beto: `messages=1`, `replies=1`, `threads=2`. `totalMessages=3`, `totalReplies=3`, `totalThreads=3`, `avgMessagesPerStudent=2.0` (3/2 → 1.5 → 2) | OK |
| T05 | `los alumnos se ordenan por cantidad de mensajes` | Ana 3 mensajes, Beto 1, Cora 2 | `perStudent.map { it.userId } == ["Ana", "Cora", "Beto"]` | OK |
| T06 | `un mensaje de un hilo ajeno no se cuenta para ningun alumno` | Ana tiene `t1`; el mensaje `m3` (rol usuario) está en `t1` pero lo escribió Beto (hilo de Beto) | el mensaje se cuenta para el **autor del hilo** (Beto), no para quien lo escribió | OK (documenta que manda el hilo, no el autor del mensaje) |
| T07 | `un mensaje sin hilo no aparece en ningun agregado` | `ChatMessage(threadId="hilo-fantasma")` sin hilo correspondiente | no suma mensajes, no aparece en `perDay`, no aporta fuentes | OK |
| T08 | `los mensajes del docente no cuentan en los totales de alumnos` | inscripción `TITULAR` con 5 mensajes | `totalMessages` cuenta sólo los alumnos; el docente sí está en `perStudent` | OK |
| T09 | `un mensaje con rol desconocido no cuenta como mensaje del alumno` | mensaje con `role="system"` en el hilo de Ana | `messages=0` (no es "user"), pero `activeDays=1` (cuenta para los días) | ⚠ ver nota¹ |

#### C. Citas y calidad de las respuestas

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T10 | `la tasa de citas es la proporcion de citas verificadas` | Ana: respuesta con `CitationStats(verified=3, unsupported=1, unknown=0)` | `citationsVerified=3`, `citationsUnsupported=1`, `citationSupportRate=0.75` | OK |
| T11 | `una respuesta con citas sin respaldo cuenta como respuesta sin respaldo` | Ana: respuesta 1 con `(verified=1, unsupported=1)`, respuesta 2 con `(verified=2)` | `answersWithUnsupportedCitations=1`; `ungroundedAnswerRate=0.5` (1 de 2 respuestas) | OK |
| T12 | `sin citas la tasa es cero y no NaN` | respuestas con `CitationStats()` (todo en cero) | `citationsTotal=0` → `citationSupportRate=0.0` (no `NaN`), `fullySupported=false` | OK |
| T13 | `las citas se cuentan por alumno y no se mezclan` | Ana 1 cita verificada, Beto 3 verificadas + 1 sin respaldo | `perStudent` de Ana: `verified=1`; de Beto: `verified=3`, `unsupported=1`; total de la cátedra: `verified=4`, `unsupported=1` | OK |
| T14 | `la tasa de la catedra es la de sus alumnos` | Ana 1 cita verificada (`supportRate=1.0`), Beto 3 verificadas + 1 sin respaldo (`supportRate=0.75`) | `citationSupportRate` de la cátedra = `4/5 = 0.8` (agregado), **no** el promedio de las tasas de los alumnos (0.875) | OK |

#### D. Visibilidad (la regla que no se negocia)

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T15 | `un alumno con visibilidad NONE no aparece en ningun agregado` | Ana `FULL` (1 mensaje, fuente `apuntes.md`); Beto `NONE` (5 mensajes, fuente `secreto.md`) | `perStudent` tiene **sólo** a Ana; `totalMessages=1`; `inactiveStudents` no incluye a Beto; `topSources` **no** menciona `secreto.md` | ⚠ **H1** |
| T16 | `un alumno con METRICS_ONLY si aparece en las metricas` | Beto `METRICS_ONLY` con 3 mensajes | aparece en `perStudent` con sus conteos; el resultado **no contiene texto** en ningún campo | OK |
| T17 | `un alumno NONE tampoco aporta actividad por dia` | Beto `NONE` con mensajes en `DIA` | `perDay` no tiene entradas de Beto | OK |
| T18 | `la visibilidad se lee en el momento del analisis` | mismos mensajes, dos llamadas: con `FULL` y luego con `NONE` | la 1ª cuenta al alumno, la 2ª no | OK |
| T19 | `la actividad por dia es solo de los alumnos` | docente con 4 mensajes el mismo día | `perDay` no debería incluir los mensajes del docente (la suma debe cuadrar con `totalMessages`) | ⚠ **H2** |
| T20 | `las fuentes mas consultadas no incluyen las de un alumno NONE` | Beto `NONE` con respuestas que citan `secreto.md` | `topSources` no lo menciona | ⚠ **H1** |

#### E. Días activos y agrupación por día

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T21 | `los dias activos son los dias distintos con mensajes` | Ana: 3 mensajes, 2 en `DIA` y 1 en `DIA_2` | `activeDays=2` | OK |
| T22 | `la actividad por dia agrupa los mensajes de cada dia` | Ana: 2 usuarios + 1 asistente en `DIA`; 1 usuario en `DIA_2` | `perDay == [DayActivity(DIA, messages=2, replies=1), DayActivity(DIA_2, messages=1, replies=0)]` | OK |
| T23 | `la actividad por dia viene ordenada por dia` | mensajes en `DIA_3`, `DIA`, `DIA_2` | `perDay.map { it.day }` es creciente | OK |
| T24 | `un mensaje con createdAt en cero cae en el dia epoch` | mensaje con `createdAt=0` | no rompe; cae en el día `0` (bucket propio) | OK |
| T25 | `un mensaje en el futuro cae en su propio dia futuro` | mensaje con `createdAt = DIA + 30 días` | `perDay` tiene una entrada con ese día; los días anteriores no se mezclan | OK |
| T26 | `la ultima actividad es el mensaje mas reciente` | mensajes en `DIA` y `DIA_2` | `lastActivity = DIA_2` | OK |

#### F. Latencia

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T27 | `la latencia media de la catedra pondera por respuestas` | Ana: 1 respuesta de 1000 ms. Beto: 9 respuestas de 100 ms | `avgLatencyMs=190` (media ponderada), **no** 550 (media simple de las medias) | OK |
| T28 | `un alumno sin respuestas tiene latencia cero` | Ana: 2 mensajes de usuario, ninguna respuesta | `avgLatencyMs=0` (sin división por cero) | OK |
| T29 | `la latencia media por alumno es el promedio de sus respuestas` | Ana: respuestas de 100, 200 y 300 ms | `avgLatencyMs=200` | OK |

#### G. Fuentes más consultadas

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T30 | `las fuentes mas consultadas se ordenan por uso y luego por nombre` | respuestas con fuentes `a.md`×2, `b.md`×2, `c.md`×1 | `topSources == [SourceUsage("a.md",2), SourceUsage("b.md",2), SourceUsage("c.md",1)]` (empate → alfabético) | OK |
| T31 | `solo cuentan las fuentes de las respuestas del asistente` | un mensaje de usuario con `sources=["a.md"]` y una respuesta con `sources=["b.md"]` | `topSources` sólo contiene `b.md` | OK |
| T32 | `el top de fuentes se limita a diez` | 12 fuentes distintas | `topSources.size == 10`, con las 10 más usadas | OK |

#### H. Alumnos inactivos y bordes de ids

| # | Test | Entrada | Resultado esperado | Estado |
|---|---|---|---|---|
| T33 | `los alumnos inactivos son los que nunca chatearon` | Ana (0), Beto (3), Cora (inscrita sin hilo) | `inactiveStudents == ["Ana", "Cora"]` | OK |
| T34 | `dos inscripciones del mismo usuario no duplican los totales` | dos `Enrollment` con el mismo `userId` (roles distintos) y 2 mensajes | el usuario se cuenta **una vez**: `students=1`, `totalMessages=2` | ⚠ **H3** |
| T35 | `los hilos de otra catedra no se mezclan` | hilo con `courseId="c2"` del mismo alumno | no suma mensajes ni hilos de esa cátedra | ⚠ **H4** |
| T36 | `una catedra con la analitica deshabilitada no devuelve datos` | `course.analyticsEnabled=false` | la capa de servicio no llama al analizador (o devuelve todo en cero) | ⚠ **H5** |

> ¹ **T09 (nota):** el código cuenta como "día activo" cualquier mensaje del
> hilo (incluidos los de rol `system` o `tool`), y en `perDay` cuenta como
> "respuesta" todo lo que no sea `user`. Es un borde real (el pipeline puede
> guardar mensajes de sistema): el test fija que **no** cuentan como mensajes
> del alumno, y deja anotado que el conteo de días incluye mensajes que no son
> del alumno. Si se decide lo contrario, se cambia el código y el test.

**Total: 36 casos** (pedido mínimo: 15).

### 2.2 Los tests más importantes, en código completo (5 de 36)

#### T01 — cátedra vacía (`core/src/commonTest/kotlin/kitoru/domain/AnalyticsAnalyzerTest.kt`)

```kotlin
package kitoru.domain

import kitoru.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del analizador de la cátedra.
 *
 * El analizador es una función pura: estos tests fijan **reglas de negocio**
 * (qué se cuenta, qué no se ve, cómo se agrupa) y no el algoritmo. La regla
 * que más importa es la de visibilidad: un alumno con `ChatVisibility.NONE`
 * no aparece en ningún agregado.
 */
class AnalyticsAnalyzerTest {

    @Test
    fun `una catedra sin datos devuelve todos los agregados en cero`() {
        val a = AnalyticsAnalyzer.analyze(curso(), emptyList(), emptyList(), emptyList())

        assertEquals("c1", a.courseId)
        assertEquals(0, a.students)
        assertEquals(0, a.activeStudents)
        assertEquals(0, a.teachers)
        assertEquals(0, a.totalMessages)
        assertEquals(0, a.totalReplies)
        assertEquals(0, a.totalThreads)
        assertEquals(0.0, a.avgMessagesPerStudent)
        assertEquals(0.0, a.citationSupportRate)
        assertEquals(0L, a.avgLatencyMs)
        assertEquals(0, a.totalTokens)
        assertTrue(a.perStudent.isEmpty(), "no hay alumnos: ${a.perStudent}")
        assertTrue(a.perDay.isEmpty(), "no hay actividad por día: ${a.perDay}")
        assertTrue(a.topSources.isEmpty(), "no hay fuentes: ${a.topSources}")
        assertTrue(a.inactiveStudents.isEmpty(), "no hay inactivos: ${a.inactiveStudents}")
    }
}
```

#### T15 — la visibilidad (`NONE` no aparece en absoluto)

```kotlin
    @Test
    fun `un alumno con visibilidad NONE no aparece en ningun agregado`() {
        val ana = inscripcion("u1", "Ana")
        val beto = inscripcion("u2", "Beto", visibilidad = ChatVisibility.NONE)

        val hilos = listOf(hilo("t1", "u1"), hilo("t2", "u2"))
        val mensajes = listOf(
            mensajeUsuario("m1", "t1"),
            mensajeAsistente("m2", "t1", fuentes = listOf("apuntes.md")),
            // Beto chateó, pero el docente no tiene por qué enterarse.
            mensajeUsuario("m3", "t2"),
            mensajeAsistente("m4", "t2", fuentes = listOf("secreto.md")),
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana, beto), hilos, mensajes)

        assertEquals(1, a.students, "sólo se ve a Ana")
        assertEquals(listOf("u1"), a.perStudent.map { it.userId })
        assertEquals(1, a.totalMessages, "los mensajes de Beto no se cuentan")
        assertEquals(1, a.totalReplies)
        assertTrue(a.inactiveStudents.isEmpty(), "Beto no está inactivo: está oculto")
        assertEquals(
            listOf("apuntes.md"),
            a.topSources.map { it.label },
            "las fuentes de un alumno oculto tampoco pueden filtrar qué material leyó
        )
        assertTrue(a.perDay.all { it.messages + it.replies == 1 }, "sólo la actividad de Ana")
    }
```

> Este test **falla hoy** (H1): `topSources` recorre todos los hilos sin
> mirar la visibilidad. Es exactamente el tipo de fuga que la estrategia de
> testing tiene que hacer visible.

#### T22 — la actividad por día

```kotlin
    @Test
    fun `la actividad por dia agrupa los mensajes de cada dia`() {
        val ana = inscripcion("u1", "Ana")
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(
            mensajeUsuario("m1", "t1", at = DIA),
            mensajeUsuario("m2", "t1", at = DIA),
            mensajeAsistente("m3", "t1", at = DIA),
            mensajeUsuario("m4", "t1", at = DIA_2),
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana), hilos, mensajes)

        assertEquals(
            listOf(
                DayActivity(DIA, messages = 2, replies = 1),
                DayActivity(DIA_2, messages = 1, replies = 0),
            ),
            a.perDay,
        )
        assertEquals(2, a.perStudent.first().activeDays, "dos días distintos con actividad")
        assertEquals(DIA_2, a.perStudent.first().lastActivity)
    }
```

#### T27 — la latencia ponderada

```kotlin
    @Test
    fun `la latencia media de la catedra pondera por respuestas`() {
        val ana = inscripcion("u1", "Ana")
        val beto = inscripcion("u2", "Beto")
        val hilos = listOf(hilo("t1", "u1"), hilo("t2", "u2"))

        val mensajes = mutableListOf(
            mensajeUsuario("m1", "t1"),
            mensajeAsistente("m2", "t1", latenciaMs = 1_000),
        )
        // Beto: 9 respuestas de 100 ms.
        repeat(9) { i ->
            mensajes += mensajeUsuario("u-$i", "t2", at = DIA + i)
            mensajes += mensajeAsistente("a-$i", "t2", at = DIA + i, latenciaMs = 100)
        }

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana, beto), hilos, mensajes)

        // Ponderada por respuestas: (1000*1 + 100*9) / 10 = 190.
        // Un promedio simple de las medias por alumno daría 550: no es lo mismo.
        assertEquals(190L, a.avgLatencyMs)
        assertEquals(1_000L, a.perStudent.first { it.userId == "u1" }.avgLatencyMs)
        assertEquals(100L, a.perStudent.first { it.userId == "u2" }.avgLatencyMs)
    }
```

#### T30 + T10 — fuentes y tasa de citas

```kotlin
    @Test
    fun `las fuentes mas consultadas se ordenan por uso y luego por nombre`() {
        val ana = inscripcion("u1", "Ana")
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(
            mensajeAsistente("m1", "t1", fuentes = listOf("a.md", "b.md")),
            mensajeAsistente("m2", "t1", fuentes = listOf("a.md", "b.md")),
            mensajeAsistente("m3", "t1", fuentes = listOf("c.md")),
            mensajeUsuario("m4", "t1", fuentes = listOf("d.md")), // no cuenta: no es del asistente
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana), hilos, mensajes)

        assertEquals(
            listOf(SourceUsage("a.md", 2), SourceUsage("b.md", 2), SourceUsage("c.md", 1)),
            a.topSources,
            "empate resuelto por nombre",
        )
    }

    @Test
    fun `la tasa de citas es la proporcion de citas verificadas`() {
        val ana = inscripcion("u1", "Ana")
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(
            mensajeUsuario("m1", "t1"),
            mensajeAsistente(
                "m2", "t1",
                citas = CitationStats(verified = 3, unsupported = 1, unknown = 0),
            ),
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana), hilos, mensajes)

        assertEquals(3, a.citationsVerified)
        assertEquals(1, a.citationsUnsupported)
        assertEquals(0.75, a.citationSupportRate)
        assertEquals(1.0, a.perStudent.first().ungroundedAnswerRate, "1 de 1 respuestas sin respaldo")
    }
```

### 2.3 Hallazgos que los tests hacen visibles (y su arreglo mínimo)

| # | Hallazgo | Dónde | Arreglo mínimo |
|---|---|---|---|
| **H1** | `topSources` cuenta las fuentes de **todos** los hilos, sin filtrar por visibilidad: un alumno `NONE` filtra qué material consultó. | `AnalyticsAnalyzer.analyze`, bloque de `sourceCounts` | Filtrar por los hilos de los alumnos visibles: `val hilosVisibles = threads.filter { it.authorId in byUser.keys }` y recorrer sólo ésos. |
| **H2** | `perDay` incluye la actividad de los docentes: la suma de `perDay` no cuadra con `totalMessages`. | `AnalyticsAnalyzer.analyze`, bloque `byDay` | Saltar las inscripciones con `role != ALUMNO` (o llevar la actividad docente en una serie aparte). |
| **H3** | Dos inscripciones con el mismo `userId` **duplican** todos los totales (`perStudent` tiene dos entradas para la misma persona). | `visible.map { … }` | Deduplicar: `visible.distinctBy { it.userId }` antes de mapear. |
| **H4** | El analizador no filtra los hilos por `courseId`: se cuelan hilos de otra cátedra del mismo alumno. | `threadsByUser` | `val hilos = threads.filter { it.courseId == course.id }` al principio de `analyze`. |
| **H5** | `Course.analyticsEnabled` no se tiene en cuenta en el analizador. | `AnalyticsAnalyzer.analyze` | Dejar la puerta en la **capa de servicio** (`AnalyticsService.analyze`), que devuelve un `CourseAnalytics()` vacío si la analítica está apagada. El analizador puro sigue siendo puro. |
| **H6** | `JsonStore` escribe **encima** del destino (no es atómico) y, si la ruta no tiene padre, `createDirectories(path.parent ?: path)` crea un **directorio** con el nombre del archivo. | `JsonStore._write` | Ver §6.3. |
| **H7** | `FileMessageRepository.delete(threadId)` borra por **id de mensaje**, no por hilo: no borra nada. | `FileRepositories.kt` | `override suspend fun delete(threadId: String) = store.update { list -> list.filterNot { it.threadId == threadId } }`. |
| **H8** | `DayActivity.day` se documenta como "medianoche **local**" pero `dayStart` calcula la medianoche **UTC**. | `Analytics.kt` / `AnalyticsAnalyzer.dayStart` | O bien se documenta como UTC, o bien se pasa un `ZoneId` a `dayStart` (cambia la firma: `dayStart(millis, zone)`). Recomiendo **documentar UTC** y que la UI lo convierta a la zona local al mostrar. |
| **H9** | Con `API_KEY` bloqueado y `BASE_URL` libre, el alumno puede apuntar la clave de la cátedra a **su** servidor. | `ModelPolicy.resolveFor` | Ver §5.3. |
| **H10** | Los tests de `:backend` no se ejecutan (falta `useJUnitPlatform()`). | build | Ver §1.4(b) y §1.5. |

> Los hallazgos H1–H4 y H7 **rompen la promesa de privacidad** que hace el
> dominio. Por eso los tests correspondientes (T15, T19, T20, T34, T35) están
> marcados como *fallan hoy*: son la especificación ejecutable del arreglo.

---

## 3. Property-based testing: sí, pero liviano

### 3.1 La decisión

**¿Vale la pena?** Sí, para las **métricas agregadas**: son la parte donde una
combinación rara (muchos alumnos, muchas citas, visibilidad mezclada) puede
producir un número raro (NaN, negativo, descuadrado) que ningún caso escrito a
mano va a cubrir.

**¿Con qué framework?** `kotlin.test` + `kotlin.random.Random` con **semilla
fija**. Sin librerías nuevas.

Motivos:

1. **Portabilidad.** Una librería de property-based testing para KMP común es
   poca y mal mantenida; la alternativa es `kotest-property`, que es JVM-only
   (Android se queda sin los tests). El objetivo de D1 es que los tests corran
   en Android: perder eso por una librería de shrinkers es un mal negocio.
2. **Determinismo.** Con semilla fija, un fallo se reproduce siempre. Con
   `Random()` sin semilla, un fallo es una historia para el café.
3. **El dominio es aritmética, no estructura.** No hay árboles ni listas
   anidadas: las invariantes son numéricas y se pueden expresar en tres
   líneas. Shrinker aporta poco.
4. **Sin dependencias nuevas** (regla *reutilizar, no reescribir*: el árbol de
   dependencias tiene que seguir chico y portable).

**Lo que se pierde:** *shrinking* (el contraejemplo mínimo). Se compensa con
un **reproductor**: cuando falla, el test imprime la semilla y el caso, y el
generador es determinista, así que se puede volver a correr con exactamente
los mismos datos.

### 3.2 Las invariantes

| # | Invariante | Por qué importa |
|---|---|---|
| I1 | `totalMessages == perStudent.filter { ALUMNO }.sumOf { it.messages }` | El total es la suma de los alumnos: si no, el docente ve números que no puede reconciliar. |
| I2 | `0.0 <= citationSupportRate <= 1.0` (y lo mismo por alumno) | Una tasa fuera de rango o `NaN` llega directo a la pantalla del docente. |
| I3 | `totalReplies >= 0`, `totalMessages >= 0`, `totalThreads >= 0`, `avgLatencyMs >= 0` | Sin negativos ni `NaN`. |
| I4 | `activeStudents <= students` y `inactiveStudents.size == students - activeStudents` | Coherencia entre los contadores y la lista de inactivos. |
| I5 | `perStudent.size <= enrollments.size` y `perStudent.size == number of distinct visible userIds` | Detecta H3 (inscripciones duplicadas). |
| I6 | `perDay.sumOf { it.messages } == totalMessages` y `perDay.sumOf { it.replies } == totalReplies` | Detecta H2 (actividad del docente mezclada) y mensajes sin hilo. |
| I7 | `topSources.sumOf { it.times } <= total de respuestas con fuentes` | El top no puede inventar consultas; detecta H1. |
| I8 | Un alumno con `visibility = NONE` no aporta a ningún agregado | La regla de privacidad, verificada sobre datos arbitrarios. |
| I9 | `avgMessagesPerStudent` es el redondeo de `totalMessages / students` | El número que ve el docente tiene que ser derivable de los otros. |
| I10 | `perDay` viene ordenado por día y `perStudent` por mensajes descendente | El orden es parte del contrato de la UI (los gráficos asumen orden). |

### 3.3 El test

```kotlin
package kitoru.domain

import kitoru.test.curso
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariantes de la analítica sobre datos generados al azar.
 *
 * No es un property-based testing completo (sin shrinker): es un generador
 * **determinista** (semilla fija) que verifica las invariantes numéricas del
 * agregado. Si un caso falla, la semilla y el número de caso permiten
 * reproducirlo exactamente.
 */
class AnalyticsPropertiesTest {

    @Test
    fun `los agregados son coherentes con datos aleatorios`() {
        // Semilla fija: si falla, vuelve a fallar igual.
        val rnd = Random(20_260_921)

        repeat(200) { caso ->
            val nAlumnos = rnd.nextInt(0, 6)
            val visibilidad = listOf(
                ChatVisibility.FULL,
                ChatVisibility.METRICS_ONLY,
                ChatVisibility.NONE,
            )

            val alumnos = (0 until nAlumnos).map { i ->
                Enrollment(
                    id = "e$i",
                    courseId = "c1",
                    userId = "u$i",
                    displayName = "Alumno $i",
                    role = CourseRole.ALUMNO,
                    visibility = visibilidad[rnd.nextInt(visibilidad.size)],
                )
            }
            val hilos = (0 until rnd.nextInt(0, 8)).map { i ->
                ChatThread(
                    id = "t$i",
                    courseId = "c1",
                    authorId = "u${rnd.nextInt(maxOf(nAlumnos, 1))}",
                    createdAt = 1_699_920_000_000L + rnd.nextInt(0, 5) * 86_400_000L,
                )
            }
            val mensajes = (0 until rnd.nextInt(0, 40)).map { i ->
                val esAsistente = rnd.nextBoolean()
                ChatMessage(
                    id = "m$i",
                    threadId = hilos.getOrNull(rnd.nextInt(maxOf(hilos.size, 1)))?.id ?: "t-fantasma",
                    role = if (esAsistente) "assistant" else "user",
                    text = "texto",
                    createdAt = 1_699_920_000_000L + rnd.nextInt(0, 5) * 86_400_000L,
                    sources = if (esAsistente && rnd.nextInt(3) == 0) listOf("a.md", "b.md") else emptyList(),
                    citation = if (esAsistente && rnd.nextInt(2) == 0) {
                        CitationStats(
                            verified = rnd.nextInt(0, 4),
                            unsupported = rnd.nextInt(0, 2),
                            unknown = rnd.nextInt(0, 2),
                        )
                    } else {
                        null
                    },
                    latencyMs = if (esAsistente) rnd.nextLong(0, 5_000) else 0L,
                )
            }

            val a = AnalyticsAnalyzer.analyze(curso(), alumnos, hilos, mensajes)
            val contexto = "caso $caso (semilla 20260921)"

            // I1 — el total es la suma de los alumnos.
            assertEquals(
                a.totalMessages,
                a.perStudent.filter { it.role == CourseRole.ALUMNO }.sumOf { it.messages },
                "I1 $contexto",
            )

            // I2 — las tasas están en rango y no son NaN.
            assertTrue(a.citationSupportRate in 0.0..1.0, "I2 $contexto: ${a.citationSupportRate}")
            a.perStudent.forEach {
                assertTrue(it.citationSupportRate in 0.0..1.0, "I2 $contexto: $it")
                assertTrue(it.ungroundedAnswerRate in 0.0..1.0, "I2 $contexto: $it")
            }

            // I3 — nada negativo.
            assertTrue(a.totalMessages >= 0 && a.totalReplies >= 0, "I3 $contexto")
            assertTrue(a.totalThreads >= 0 && a.avgLatencyMs >= 0, "I3 $contexto")

            // I4 — activos e inactivos son complementarios.
            assertTrue(a.activeStudents <= a.students, "I4 $contexto")
            assertEquals(a.students - a.activeStudents, a.inactiveStudents.size, "I4 $contexto")

            // I5 — un usuario, una fila (detecta inscripciones duplicadas: H3).
            assertEquals(
                alumnos.filter { it.visibility.showsMetrics }.distinctBy { it.userId }.size,
                a.perStudent.size,
                "I5 $contexto",
            )

            // I6 — la actividad por día cuadra con los totales (detecta H2).
            assertEquals(a.totalMessages, a.perDay.sumOf { it.messages }, "I6 $contexto")
            assertEquals(a.totalReplies, a.perDay.sumOf { it.replies }, "I6 $contexto")

            // I7 — el top de fuentes no puede inventar consultas (detecta H1).
            val respuestasConFuentes = mensajes.count { it.role == "assistant" && it.sources.isNotEmpty() }
            assertTrue(a.topSources.sumOf { it.times } <= respuestasConFuentes, "I7 $contexto")

            // I8 — los alumnos ocultos no dejan rastro.
            val ocultos = alumnos.filter { !it.visibility.showsMetrics }.map { it.userId }.toSet()
            assertTrue(a.perStudent.none { it.userId in ocultos }, "I8 $contexto")

            // I9 — el promedio por alumno es derivable.
            if (a.students > 0) {
                assertEquals(
                    (a.totalMessages.toDouble() / a.students).roundToInt().toDouble(),
                    a.avgMessagesPerStudent,
                    "I9 $contexto",
                )
            } else {
                assertEquals(0.0, a.avgMessagesPerStudent, "I9 $contexto")
            }

            // I10 — los órdenes que la UI asume.
            assertEquals(a.perDay.map { it.day }.sorted(), a.perDay.map { it.day }, "I10 $contexto")
            assertEquals(
                a.perStudent.map { it.messages }.sortedDescending(),
                a.perStudent.map { it.messages },
                "I10 $contexto",
            )
        }
    }
}
```

> **Nota sobre I6, I7 e I8:** hoy **fallan** (son H1 y H2). Es lo esperado:
> los tests de propiedad son los que descubren que la regla y la
> implementación no coinciden en los casos raros. Cuando se apliquen los
> arreglos, los tests pasan sin tocarlos.

---

## 4. Casos borde

### 4.1 Tabla

| # | Caso borde | Entrada | Comportamiento esperado | Nota |
|---|---|---|---|---|
| B01 | **Citas con `total == 0`** | `CitationStats()` (todo en cero) | `total=0`, `supportRate=0.0` (**no `NaN`**), `fullySupported=false` | ya está bien en `Chat.kt` |
| B02 | **División por cero en la tasa de citas** | ninguna cita en toda la cátedra | `citationSupportRate=0.0` | ya está bien |
| B03 | **División por cero en el promedio por alumno** | 0 alumnos | `avgMessagesPerStudent=0.0` | ya está bien |
| B04 | **División por cero en la latencia** | 0 respuestas | `avgLatencyMs=0` | ya está bien |
| B05 | **División por cero en la tasa de respuestas sin respaldo** | 0 respuestas | `ungroundedAnswerRate=0.0` | ya está bien |
| B06 | **Lista de mensajes vacía** | `messages = emptyList()` | todo en cero; `perDay` y `topSources` vacíos | ya está bien |
| B07 | **Lista de hilos vacía** | `threads = emptyList()` | `totalThreads=0`; los mensajes sin hilo no cuentan | ya está bien |
| B08 | **`createdAt == 0`** | mensaje con `createdAt=0` | bucket propio (día 0 = epoch); no rompe ni se mezcla | ok |
| B09 | **`createdAt` en el futuro** | `createdAt = ahora + 30 días` | bucket futuro propio; no arrastra los días previos | ok |
| B10 | **`createdAt` negativo** ( reloj mal seteado) | `createdAt = -1` | `dayStart` usa floor → día `-86_400_000`; no rompe | ok (verificar) |
| B11 | **Dos alumnos con el mismo `userId`** | 2 inscripciones, mismo `userId` | se cuenta **una vez**; no duplica totales | ⚠ H3 |
| B12 | **Dos inscripciones con el mismo `id`** (distinto `userId`) | 2 inscripciones `id="e1"` | el analizador no ve ids de inscripción: cuenta 2 alumnos | ok (la deduplicación por id la hace el repositorio) |
| B13 | **`visibility` cambiada a mitad de datos** | mismos mensajes; 1ª llamada con `FULL`, 2ª con `NONE` | cada llamada refleja la visibilidad de ese momento | ok |
| B14 | **Alumno sin inscripción** (mensajes de un usuario que no está inscrito) | hilos de `u9` sin `Enrollment` | no aparece en ningún agregado (no hay inscripción → no hay visibilidad autorizada) | ok |
| B15 | **Hilo sin mensajes** | hilo de Ana vacío | cuenta como hilo (`threads=1`) pero no como actividad | ok — **decisión de negocio a confirmar** |
| B16 | **`CitationStats` con números negativo** | `verified = -1` | hoy `total` puede ser negativo → `supportRate` negativo | ⚠ **H11**: hay que normalizar o rechazar en el constructor |
| B17 | **Mensaje con `role` vacío o desconocido** | `role = ""` | no cuenta como mensaje del alumno; hoy cuenta como "respuesta" en `perDay` | ver nota de T09 |
| B18 | **Curso con `analyticsEnabled=false`** | — | la capa de servicio no analiza | ⚠ H5 |
| B19 | **Enumerable enorme** | 500 alumnos × 200 mensajes | termina; ver §8 (riesgo de rendimiento) | a medir |

### 4.2 Código de los bordes más delicados

```kotlin
// core/src/commonTest/kotlin/kitoru/domain/StudentAnalyticsTest.kt
package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Las métricas derivadas: rates que no pueden dar NaN ni negativos. */
class StudentAnalyticsTest {

    @Test
    fun `una cita sin total tiene tasa cero y no esta respaldada`() {
        val citas = CitationStats(verified = 0, unsupported = 0, unknown = 0)

        assertEquals(0, citas.total)
        assertEquals(0.0, citas.supportRate, "sin citas la tasa es 0, no NaN")
        assertFalse(citas.supportRate.isNaN())
        assertFalse(citas.fullySupported, "sin citas no se puede decir que están respaldadas")
    }

    @Test
    fun `una cita con todas sus citas respaldada esta respaldada`() {
        val citas = CitationStats(verified = 2, unsupported = 0, unknown = 0)

        assertEquals(2, citas.total)
        assertEquals(1.0, citas.supportRate)
        assertTrue(citas.fullySupported)
    }

    @Test
    fun `una cita a una fuente inexistente no esta respaldada`() {
        val citas = CitationStats(verified = 1, unsupported = 0, unknown = 2)

        assertEquals(3, citas.total)
        assertEquals(1.0 / 3.0, citas.supportRate)
        assertFalse(citas.fullySupported, "hay citas sin fuente: no puede decir que están respaldadas")
    }

    @Test
    fun `un alumno sin respuestas no tiene respuestas sin respaldo`() {
        val s = StudentAnalytics(userId = "u1", displayName = "Ana", role = CourseRole.ALUMNO)

        assertEquals(0.0, s.ungroundedAnswerRate, "0 de 0 respuestas no es NaN")
        assertEquals(0.0, s.citationSupportRate)
        assertEquals(0L, s.avgLatencyMs)
        assertEquals(0, s.citationsTotal)
    }

    @Test
    fun `la tasa de respuestas sin respaldo va de cero a uno`() {
        val s = StudentAnalytics(
            userId = "u1",
            displayName = "Ana",
            role = CourseRole.ALUMNO,
            replies = 4,
            answersWithUnsupportedCitations = 1,
        )
        assertEquals(0.25, s.ungroundedAnswerRate)
    }
}
```

```kotlin
// core/src/commonTest/kotlin/kitoru/domain/AnalyticsAnalyzerTest.kt (continuación)

    @Test
    fun `un mensaje con createdAt en cero cae en su propio dia`() {
        val ana = inscripcion("u1", "Ana")
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(
            mensajeUsuario("m1", "t1", at = 0),
            mensajeUsuario("m2", "t1", at = DIA),
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana), hilos, mensajes)

        assertEquals(2, a.perDay.size, "el epoch es un día propio: ${a.perDay}")
        assertEquals(0L, a.perDay.first().day)
        assertEquals(1, a.perDay.first().messages)
    }

    @Test
    fun `un mensaje en el futuro no se mezcla con los de hoy`() {
        val ana = inscripcion("u1", "Ana")
        val futuro = DIA + 30L * 86_400_000L
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(
            mensajeUsuario("m1", "t1", at = DIA),
            mensajeUsuario("m2", "t1", at = futuro),
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana), hilos, mensajes)

        assertEquals(2, a.perDay.size)
        assertEquals(futuro - (futuro % 86_400_000L), a.perDay.last().day)
    }

    @Test
    fun `la visibilidad se relee en cada analisis`() {
        val ana = inscripcion("u1", "Ana")
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(mensajeUsuario("m1", "t1"))

        val antes = AnalyticsAnalyzer.analyze(curso(), listOf(ana), hilos, mensajes)
        val despues = AnalyticsAnalyzer.analyze(
            curso(),
            listOf(ana.copy(visibility = ChatVisibility.NONE)),
            hilos,
            mensajes,
        )

        assertEquals(1, antes.totalMessages)
        assertEquals(0, despues.totalMessages, "el alumno bajó la visibilidad: ya no se lo ve")
        assertTrue(despues.perStudent.isEmpty())
    }

    @Test
    fun `dos inscripciones del mismo usuario no duplican los totales`() {
        val ana = inscripcion("u1", "Ana")
        val anaComoAyudante = ana.copy(id = "e-otro", role = CourseRole.AYUDANTE)
        val hilos = listOf(hilo("t1", "u1"))
        val mensajes = listOf(
            mensajeUsuario("m1", "t1"),
            mensajeAsistente("m2", "t1"),
        )

        val a = AnalyticsAnalyzer.analyze(curso(), listOf(ana, anaComoAyudante), hilos, mensajes)

        assertEquals(1, a.perStudent.size, "la misma persona es una sola fila")
        assertEquals(1, a.totalMessages)
        assertEquals(1, a.totalReplies)
    }
```

> El último test **falla hoy** (H3): con dos inscripciones del mismo `userId`,
> `perStudent` tiene dos filas y `totalMessages` vale 2.

---

## 5. Tests de `ModelPolicy.resolveFor`

### 5.1 La regla

`resolveFor(studentChoice)` es la puerta que decide qué puede tocar el alumno:

- Si `allowStudentOverride == false` → **manda la cátedra entera**: se
  devuelve `this` sin mirar la elección del alumno.
- Si `allowStudentOverride == true` → por cada campo:
  - está en `lockedFields` → **manda la cátedra**;
  - no está → **manda el alumno**.

### 5.2 Tabla de casos

| # | Campo bloqueado | `allowStudentOverride` | El alumno cambió el valor | `apiKey` | `remoteBaseUrl` | `temperature` | `maxTokens` | `topK` | `modelName` | `systemPrompt` |
|---|---|---|---|---|---|---|---|---|---|---|
| T-P1 | `API_KEY` | `true` | sí | **cátedra** | alumno | alumno | alumno | alumno | alumno | alumno |
| T-P2 | `API_KEY` | `true` | no | cátedra | alumno | alumno | alumno | alumno | alumno | alumno |
| T-P3 | (ninguno) | `true` | sí | alumno | alumno | alumno | alumno | alumno | alumno | alumno |
| T-P4 | `API_KEY` | `false` | sí | **cátedra** | **cátedra** | **cátedra** | **cátedra** | **cátedra** | **cátedra** | **cátedra** |
| T-P5 | (ninguno) | `false` | sí | **cátedra** | **cátedra** | **cátedra** | **cátedra** | **cátedra** | **cátedra** | **cátedra** |
| T-P6 | todos | `true` | sí | cátedra | cátedra | cátedra | cátedra | cátedra | cátedra | cátedra |

> "Cátedra" = el valor de `this` (la configuración base de la cátedra).
> "Alumno" = el valor de `studentChoice`.
> En T-P4/T-P5 el resultado es **exactamente** `this` (el `ModelPolicy` de la
> cátedra, sin tocar), incluidos los campos que el alumno nunca podría
> cambiar (`mode`, `hfRepo`, `contextSize`, …).

**Tabla campo por campo** (para el test data-driven):

| Campo | Valor de la cátedra | Valor del alumno | Con bloqueo | Sin bloqueo |
|---|---|---|---|---|
| `API_KEY` | `"clave-catedra"` | `"clave-alumno"` | `"clave-catedra"` | `"clave-alumno"` |
| `BASE_URL` | `"https://catedra/v1"` | `"http://localhost:8081/v1"` | `"https://catedra/v1"` | `"http://localhost:8081/v1"` |
| `MODEL_NAME` | `"qwen3-8b"` | `"otro-modelo"` | `"qwen3-8b"` | `"otro-modelo"` |
| `SYSTEM_PROMPT` | `"Respondé con el material."` | `"Sos un chef."` | `"Respondé con el material."` | `"Sos un chef."` |
| `TEMPERATURE` | `0.2` | `1.7` | `0.2` | `1.7` |
| `MAX_TOKENS` | `1024` | `4096` | `1024` | `4096` |
| `TOP_K` | `5` | `9` | `5` | `9` |

### 5.3 El código

```kotlin
package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del bloqueo de campos de la configuración del modelo.
 *
 * Es la regla de agencia del alumno: la cátedra pone la base y el alumno
 * puede ajustarla **salvo** en los campos bloqueados. Un error acá significa
 * que el alumno puede tocar la clave de la cátedra, o que no puede tocar lo
 * que debería.
 */
class ModelPolicyResolveTest {

    private val catedra = ModelPolicy(
        apiKey = "clave-catedra",
        remoteBaseUrl = "https://catedra/v1",
        modelName = "qwen3-8b",
        systemPrompt = "Respondé con el material.",
        temperature = 0.2,
        maxTokens = 1024,
        topK = 5,
    )

    private val eleccionAlumno = ModelPolicy(
        apiKey = "clave-alumno",
        remoteBaseUrl = "http://localhost:8081/v1",
        modelName = "otro-modelo",
        systemPrompt = "Sos un chef.",
        temperature = 1.7,
        maxTokens = 4096,
        topK = 9,
    )

    private fun valorDe(p: ModelPolicy, campo: LockedField): Any = when (campo) {
        LockedField.API_KEY -> p.apiKey
        LockedField.BASE_URL -> p.remoteBaseUrl
        LockedField.MODEL_NAME -> p.modelName
        LockedField.SYSTEM_PROMPT -> p.systemPrompt
        LockedField.TEMPERATURE -> p.temperature
        LockedField.MAX_TOKENS -> p.maxTokens
        LockedField.TOP_K -> p.topK
    }

    @Test
    fun `sin override el alumno usa exactamente la configuracion de la catedra`() {
        val base = catedra.copy(allowStudentOverride = false)
        val out = base.resolveFor(eleccionAlumno)

        assertEquals(base, out, "sin override gana la cátedra entera")
        assertEquals("clave-catedra", out.apiKey)
        assertEquals("https://catedra/v1", out.remoteBaseUrl)
    }

    @Test
    fun `sin campos bloqueados gana la eleccion del alumno`() {
        val out = catedra.copy(lockedFields = emptySet()).resolveFor(eleccionAlumno)

        assertEquals("clave-alumno", out.apiKey)
        assertEquals("http://localhost:8081/v1", out.remoteBaseUrl)
        assertEquals(1.7, out.temperature)
        assertEquals(4096, out.maxTokens)
        assertEquals(9, out.topK)
    }

    @Test
    fun `un campo bloqueado gana sobre la eleccion del alumno`() {
        val out = catedra.copy(lockedFields = setOf(LockedField.API_KEY)).resolveFor(eleccionAlumno)

        assertEquals("clave-catedra", out.apiKey, "la clave la pone la cátedra")
        assertEquals("http://localhost:8081/v1", out.remoteBaseUrl, "lo demás lo elige el alumno")
        assertEquals(1.7, out.temperature)
    }

    @Test
    fun `cada campo se puede bloquear de a uno`() {
        for (campo in LockedField.entries) {
            val out = catedra.copy(lockedFields = setOf(campo)).resolveFor(eleccionAlumno)

            assertEquals(
                valorDe(catedra, campo),
                valorDe(out, campo),
                "el campo $campo está bloqueado: tiene que ganar la cátedra",
            )
            // Los demás campos los elige el alumno.
            for (otro in LockedField.entries - campo) {
                assertEquals(
                    valorDe(eleccionAlumno, otro),
                    valorDe(out, otro),
                    "el campo $otro no está bloqueado: gana el alumno",
                )
            }
        }
    }

    @Test
    fun `con todos los campos bloqueados gana la catedra en todo`() {
        val out = catedra.copy(lockedFields = LockedField.entries.toSet()).resolveFor(eleccionAlumno)

        LockedField.entries.forEach {
            assertEquals(valorDe(catedra, it), valorDe(out, it), "campo $it")
        }
    }

    @Test
    fun `resolver no modifica la configuracion de la catedra ni la del alumno`() {
        val antesCatedra = catedra.copy(lockedFields = setOf(LockedField.API_KEY))
        val antesAlumno = eleccionAlumno

        antesCatedra.resolveFor(eleccionAlumno)

        assertEquals(antesCatedra, antesCatedra, "la cátedra no se toca")
        assertEquals(antesAlumno, eleccionAlumno, "la elección del alumno no se toca")
    }

    @Test
    fun `resolver la eleccion del alumno con la de la catedra no cambia nada`() {
        val base = catedra.copy(lockedFields = setOf(LockedField.API_KEY))
        assertEquals(base, base.resolveFor(base))
    }

    @Test
    fun `la clave de la catedra no puede usarse contra un servidor del alumno`() {
        // La clave la provee la institución: mandarla a un servidor elegido por
        // el alumno es una fuga, aunque la clave esté "bloqueada".
        val catedraConClave = catedra.copy(lockedFields = setOf(LockedField.API_KEY))
        val alumnoMalo = catedraConClave.copy(remoteBaseUrl = "https://malo.example/v1")

        val out = catedraConClave.resolveFor(alumnoMalo)

        assertEquals(
            "https://catedra/v1",
            out.remoteBaseUrl,
            "si la clave es de la cátedra, la dirección también tiene que serlo",
        )
    }
}
```

### 5.4 El hallazgo H9 (fuga de la clave) y su arreglo

`resolveFor` bloquea campos **de a uno**. Si la cátedra bloquea `API_KEY`
pero no `BASE_URL`, el alumno puede:

1. dejar la `apiKey` de la cátedra (porque está bloqueada), y
2. poner `remoteBaseUrl = "https://mi-servidor/v1"`, y

el gateway manda la clave de la institución al servidor del alumno.

**Arreglo propuesto** (en `ModelPolicy`, no en el test): bloquear la clave
implica bloquear la dirección. Se puede hacer en `normalize()` (que el
diseño `05-config-modelo.md` ya prevé) o en `resolveFor`:

```kotlin
// En normalize(): la clave y la dirección viajan juntas.
if (LockedField.API_KEY in lockedFields && LockedField.BASE_URL !in lockedFields) {
    lockedFields = lockedFields + LockedField.BASE_URL
}
```

Alternativa (más fuerte, si el modelo es local): el gateway no manda la clave
de la cátedra a una URL que no sea la de la cátedra. Es una regla del
`OpenAiCompatibleGateway`, no del dominio.

**Mientras tanto**, el test `la clave de la catedra no puede usarse contra un
servidor del alumno` **falla** y documenta el riesgo.

---

## 6. Tests de la persistencia

### 6.1 El doble: `InMemoryFileSystem`

Hoy `FileSystem` sólo tiene la implementación real (`java.io.File`). Para
testear la persistencia sin tocar el disco se necesita un doble. Va en
`core/src/commonTest/kotlin/kitoru/test/InMemoryFileSystem.kt` (es código de
test, no de producción: no va en `commonMain`).

```kotlin
package kitoru.test

import kitoru.platform.FilePath
import kitoru.platform.FileSystem

/**
 * Sistema de archivos en memoria.
 *
 * Sirve para testear la persistencia sin tocar el disco: los tests son
 * deterministas, no dependen del sistema de archivos real y funcionan igual
 * en el escritorio y en Android.
 *
 * Además de la interfaz, expone ganchos para simular fallas:
 *  - [fallarEscritura]: hace fallar la escritura a una ruta (para probar la
 *    escritura atómica);
 *  - [sembrar]: escribe un archivo directamente (para simular corrupción).
 */
class InMemoryFileSystem : FileSystem {

    private val archivos = mutableMapOf<String, String>()
    private val carpetas = mutableSetOf<String>()

    /** Si devuelve `true`, la escritura a esa ruta falla. */
    var fallarEscritura: (FilePath) -> Boolean = { false }

    /** Contadores, para aserciones. */
    var lecturas = 0
        private set
    var escrituras = 0
        private set

    // --- helpers internos ---

    private fun clave(p: FilePath): String {
        val v = p.value.replace('\\', '/')
        return if (v.length > 1) v.trimEnd('/') else v
    }

    private fun padreDe(c: String): String? {
        val i = c.lastIndexOf('/')
        return if (i <= 0) null else c.substring(0, i)
    }

    private fun crearCarpetas(c: String) {
        var actual = c
        while (actual.isNotEmpty() && actual != "/") {
            carpetas += actual
            actual = padreDe(actual) ?: ""
        }
    }

    // --- FileSystem ---

    override fun exists(path: FilePath): Boolean {
        val c = clave(path)
        return archivos.containsKey(c) || carpetas.contains(c)
    }

    override fun isDirectory(path: FilePath): Boolean = carpetas.contains(clave(path))

    override fun sizeBytes(path: FilePath): Long = archivos[clave(path)]?.length?.toLong() ?: 0L

    override fun readText(path: FilePath): String {
        lecturas++
        val c = clave(path)
        return archivos[c] ?: throw IllegalStateException("No existe el archivo: $c")
    }

    override fun writeText(path: FilePath, text: String) {
        val c = clave(path)
        if (fallarEscritura(path)) {
            throw IllegalStateException("Fallo de escritura simulado: $c")
        }
        padreDe(c)?.let { crearCarpetas(it) }
        archivos[c] = text
        escrituras++
    }

    override fun delete(path: FilePath) {
        val c = clave(path)
        archivos -= c
        carpetas -= c
        val prefijo = "$c/"
        archivos.keys.filter { it.startsWith(prefijo) }.toList().forEach { archivos -= it }
        carpetas.filter { it.startsWith(prefijo) }.toList().forEach { carpetas -= it }
    }

    override fun createDirectories(path: FilePath) {
        crearCarpetas(clave(path))
    }

    override fun listFiles(dir: FilePath): List<FilePath> {
        val c = clave(dir)
        return archivos.keys
            .filter { padreDe(it) == c }
            .sorted()
            .map { FilePath(it) }
    }

    override fun copy(from: FilePath, to: FilePath) {
        val o = clave(from)
        val texto = archivos[o] ?: throw IllegalStateException("No existe el archivo: $o")
        writeText(to, texto)
    }

    /** Renombra/mueve: el destino se reemplaza. Es lo que hace atómica la escritura. */
    override fun move(from: FilePath, to: FilePath) {
        val o = clave(from)
        val d = clave(to)
        val texto = archivos.remove(o) ?: throw IllegalStateException("No existe el archivo: $o")
        padreDe(d)?.let { crearCarpetas(it) }
        archivos[d] = texto
    }

    // --- ganchos de test ---

    /** Escribe un archivo directamente, saltando la lógica de escrituras. */
    fun sembrar(path: FilePath, texto: String) {
        val c = clave(path)
        padreDe(c)?.let { crearCarpetas(it) }
        archivos[c] = texto
    }

    /** Rutas de todos los archivos (no carpetas). */
    fun archivos(): Set<String> = archivos.keys.toSet()

    /** Texto crudo de un archivo, o `null` si no está. */
    fun texto(path: FilePath): String? = archivos[clave(path)]
}
```

> **Requisito**: `move` no existe en `FileSystem`. Hay que agregarlo a la
> interfaz (`core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt`) y a la
> implementación JVM (`FileSystem.jvm.kt`):
>
> ```kotlin
> // en la interfaz
> /** Renombra o mueve un archivo (reemplaza el destino si existe). */
> fun move(from: FilePath, to: FilePath)
>
> // en JvmFileSystem
> override fun move(from: FilePath, to: FilePath) {
>     val src = f(from)
>     val dst = f(to)
>     dst.parentFile?.mkdirs()
>     // `File.renameTo` en Windows no reemplaza el destino:
>     // se borra primero (ventana mínima) y luego se renombra.
>     if (dst.exists()) dst.delete()
>     if (!src.renameTo(dst)) {
>         src.copyTo(dst, overwrite = true)
>         src.delete()
>     }
> }
> ```
>
> Es el mismo cambio en las dos plataformas (Android y escritorio), y por eso
> `java.nio.file.Files.move` queda descartado (`minSdk = 24`).

### 6.2 `JsonStore` / `JsonListStore`: guardar, cargar, corrupto, ausente

```kotlin
package kitoru.domain

import kitoru.platform.FilePath
import kitoru.test.InMemoryFileSystem
import kitoru.test.curso
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonStoreTest {

    private val fs = InMemoryFileSystem()
    private val ruta = FilePath("/mem/config/cursos.json")

    private fun cursos() = JsonListStore(fs, ruta, Course.serializer())

    @Test
    fun `un store nuevo devuelve la lista vacia`() = runTest {
        assertEquals(emptyList(), cursos().read())
    }

    @Test
    fun `guardar y cargar conserva los datos`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1"), curso("c2")))

        assertEquals(listOf(curso("c1"), curso("c2")), s.read())
    }

    @Test
    fun `un archivo ausente devuelve el valor vacio`() = runTest {
        assertTrue(!fs.exists(ruta), "el archivo no está: es un store nuevo")
        assertEquals(emptyList(), cursos().read())
    }

    @Test
    fun `un archivo corrupto se ignora y se devuelve el valor vacio`() = runTest {
        fs.sembrar(ruta, "{ esto no es json")

        assertEquals(emptyList(), cursos().read(), "un archivo roto no puede voltear la app")
    }

    @Test
    fun `un archivo con el tipo equivocado devuelve el valor vacio`() = runTest {
        fs.sembrar(ruta, """{"id":"c1"}""") // es un objeto, no una lista

        assertEquals(emptyList(), cursos().read())
    }

    @Test
    fun `los campos desconocidos se ignoran`() = runTest {
        fs.sembrar(ruta, """[{"id":"c1","code":"IA","campoDelFuturo":123}]""")

        assertEquals(1, cursos().read().size, "un campo nuevo no rompe la lectura")
    }

    @Test
    fun `escribir crea los directorios que faltan`() = runTest {
        val rutaNueva = FilePath("/mem/nuevo/dir/cosas.json")
        val s = JsonListStore(fs, rutaNueva, Course.serializer())

        s.write(listOf(curso()))

        assertTrue(fs.exists(rutaNueva))
        assertTrue(fs.isDirectory(FilePath("/mem/nuevo/dir")))
    }

    @Test
    fun `update lee transforma y escribe`() = runTest {
        val s = JsonListStore(fs, ruta, Course.serializer())
        s.write(listOf(curso("c1")))

        s.update { lista -> lista + curso("c2") }

        assertEquals(2, s.read().size)
    }
}
```

### 6.3 Escritura atómica (H6)

**El problema.** Hoy `_write` escribe **encima** del archivo destino: si la app
se corta en mitad de la escritura (o se queda sin espacio), el archivo queda
a medio escribir. La próxima lectura lo detecta como corrupto y devuelve el
valor vacío → **se pierde toda la cátedra guardada**.

**El arreglo propuesto** (`JsonStore._write`): escribir a un temporal y
renombrar.

```kotlin
private fun _write(value: T) {
    val texto = KitoruJson.encodeToString(serializer, value)
    val dir = path.parent
    if (dir != null) fs.createDirectories(dir)
    val tmp = FilePath("${dir?.value ?: "."}/${path.name}.tmp")
    try {
        fs.writeText(tmp, texto)
        fs.move(tmp, path)
    } catch (e: Throwable) {
        // El temporal no puede quedar tirado: se borra y el error sigue su curso.
        runCatching { fs.delete(tmp) }
        throw e
    }
}
```

> Nota: el `path.parent ?: path` actual es un bug (H6): con una ruta sin
> padre ("cursos.json") crearía un **directorio** llamado `cursos.json` y la
> escritura fallaría. Con el arreglo, una ruta sin padre escribe en el
> directorio de trabajo.

**Los tests:**

```kotlin
    @Test
    fun `una escritura fallida deja intacto el archivo anterior`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))

        // Falla sólo la escritura del temporal.
        fs.fallarEscritura = { it.value.endsWith(".tmp") }

        val fallo = runCatching { s.write(listOf(curso("c2"))) }.exceptionOrNull()
        assertTrue(fallo != null, "la escritura tiene que fallar")

        assertEquals(
            listOf(curso("c1")),
            s.read(),
            "el archivo bueno tiene que seguir intacto: no se pierde la cátedra",
        )
    }

    @Test
    fun `una escritura fallida no deja temporales tirados`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))
        fs.fallarEscritura = { it.value.endsWith(".tmp") }

        runCatching { s.write(listOf(curso("c2"))) }

        assertTrue(
            fs.archivos().none { it.endsWith(".tmp") },
            "no puede quedar un temporal: ${fs.archivos()}",
        )
    }

    @Test
    fun `una escritura correcta tampoco deja temporales`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))

        assertTrue(
            fs.archivos().none { it.endsWith(".tmp") },
            "el temporal se renombra: ${fs.archivos()}",
        )
    }
```

### 6.4 Concurrencia (dos corrutinas escribiendo)

El `JsonStore` serializa con un `Mutex`. Los tests fijan que el mutex sirve de
verdad: sin él, dos escrituras simultáneas se pisan.

```kotlin
    @Test
    fun `cien updates concurrentes no pierden ninguno`() = runTest {
        val s = JsonStore(fs, FilePath("/mem/config/n.json"), Int.serializer(), { 0 })

        // Con Dispatchers.Default las escrituras corren de verdad en paralelo
        // (con el scheduler de runTest no habría carrera real).
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(100) { launch { s.update { it + 1 } } }
            }
        }

        assertEquals(100, s.read(), "ningún update puede perderse")
    }

    @Test
    fun `dos escrituras concurrentes dejan un archivo valido`() = runTest {
        val s = cursos()

        withContext(Dispatchers.Default) {
            coroutineScope {
                launch { s.write(listOf(curso("a"))) }
                launch { s.write(listOf(curso("b"))) }
            }
        }

        val leido = s.read()
        assertEquals(1, leido.size, "el archivo no puede quedar a medias")
        assertTrue(leido[0].id == "a" || leido[0].id == "b")
    }

    @Test
    fun `leer y escribir a la vez no rompe el archivo`() = runTest {
        val s = cursos()
        s.write(listOf(curso("a")))

        withContext(Dispatchers.Default) {
            coroutineScope {
                launch { repeat(50) { s.write(listOf(curso("a"))) } }
                launch { repeat(50) { s.read() } }
            }
        }

        assertEquals(1, s.read().size, "el archivo sigue siendo JSON válido")
    }
```

### 6.5 Los repositorios sobre el FS en memoria

```kotlin
package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.test.InMemoryFileSystem
import kitoru.test.curso
import kitoru.test.inscripcion
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRepositoriesTest {

    private val fs = InMemoryFileSystem()
    private lateinit var dirPrevio: FilePath

    @BeforeTest
    fun setUp() {
        dirPrevio = KitoruPaths.dataDir
        KitoruPaths.setDataDir(FilePath("/mem"))
    }

    @AfterTest
    fun tearDown() {
        KitoruPaths.setDataDir(dirPrevio)
    }

    @Test
    fun `guardar y listar una catedra`() = runTest {
        val repo = FileCourseRepository(fs)
        repo.save(curso("c1"))
        repo.save(curso("c2"))

        assertEquals(2, repo.list().size)
        assertEquals("c1", repo.get("c1")?.id)
    }

    @Test
    fun `guardar dos veces la misma catedra la actualiza`() = runTest {
        val repo = FileCourseRepository(fs)
        repo.save(curso("c1"))
        repo.save(curso("c1").copy(name = "Nuevo nombre"))

        assertEquals(1, repo.list().size, "no puede quedar duplicada")
        assertEquals("Nuevo nombre", repo.get("c1")?.name)
    }

    @Test
    fun `borrar una catedra la saca del listado`() = runTest {
        val repo = FileCourseRepository(fs)
        repo.save(curso("c1"))
        repo.delete("c1")

        assertTrue(repo.list().isEmpty())
        assertNull(repo.get("c1"))
    }

    @Test
    fun `las inscripciones se filtran por catedra y por usuario`() = runTest {
        val repo = FileEnrollmentRepository(fs)
        repo.save(inscripcion("u1", cursoId = "c1"))
        repo.save(inscripcion("u2", cursoId = "c1"))
        repo.save(inscripcion("u1", cursoId = "c2"))

        assertEquals(2, repo.listForCourse("c1").size)
        assertEquals(2, repo.listForUser("u1").size)
    }

    @Test
    fun `los mensajes de un hilo se listan ordenados por fecha`() = runTest {
        val repo = FileMessageRepository(fs)
        repo.save(ChatMessage("m2", "c1|t1", "assistant", "segunda", createdAt = 2_000))
        repo.save(ChatMessage("m1", "c1|t1", "user", "primera", createdAt = 1_000))

        val lista = repo.listForThread("c1|t1")
        assertEquals(listOf("m1", "m2"), lista.map { it.id })
    }

    @Test
    fun `borrar un hilo borra sus mensajes`() = runTest {
        val repo = FileMessageRepository(fs)
        repo.save(ChatMessage("m1", "c1|t1", "user", "hola"))
        repo.save(ChatMessage("m2", "c1|t2", "user", "otro hilo"))

        repo.delete("c1|t1")

        assertEquals(
            listOf("m2"),
            repo.listForThread("c1|t2").map { it.id },
            "los mensajes del hilo borrado tienen que irse (H7)",
        )
        assertTrue(repo.listForThread("c1|t1").isEmpty(), "H7: hoy no se borra nada")
    }
}
```

### 6.6 Smoke test del FS real (`:core/desktopTest`, JUnit 5)

El FS en memoria no reproduce los detalles del sistema de archivos (separadores
de ruta, `renameTo` en Windows, archivo bloqueado). Un smoke test chico sobre
un directorio temporal cubre lo esencial:

```kotlin
package kitoru.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Smoke test del sistema de archivos real.
 *
 * El resto de la persistencia se testea con el FS en memoria; esto verifica
 * que la implementación de `java.io.File` se comporta como espera el dominio
 * (crear directorios, escribir, listar, renombrar). JUnit se encarga de
 * borrar el directorio temporal al terminar.
 */
class SystemFileSystemTest {

    private val fs = systemFileSystem()

    @TempDir
    lateinit var temporal: java.io.File

    private fun dir() = FilePath(temporal.absolutePath)

    @Test
    fun `escribir y leer un archivo`() {
        val archivo = dir().resolve("datos.json")

        fs.writeText(archivo, "hola")

        assertTrue(fs.exists(archivo))
        assertEquals("hola", fs.readText(archivo))
        assertEquals(4L, fs.sizeBytes(archivo))
    }

    @Test
    fun `escribir crea los directorios que faltan`() {
        val archivo = dir().resolve("a/b/c/datos.json")

        fs.writeText(archivo, "hola")

        assertTrue(fs.exists(archivo))
        assertTrue(fs.isDirectory(dir().resolve("a/b")))
    }

    @Test
    fun `listar devuelve los archivos ordenados`() {
        fs.writeText(dir().resolve("b.json"), "b")
        fs.writeText(dir().resolve("a.json"), "a")

        assertEquals(listOf("a.json", "b.json"), fs.listFiles(dir()).map { it.name })
    }

    @Test
    fun `renombrar reemplaza el destino`() {
        fs.writeText(dir().resolve("viejo.json"), "nuevo")
        fs.writeText(dir().resolve("nuevo.json"), "viejo")

        fs.move(dir().resolve("viejo.json"), dir().resolve("nuevo.json"))

        assertEquals("nuevo", fs.readText(dir().resolve("nuevo.json")))
        assertFalse(fs.exists(dir().resolve("viejo.json")))
    }

    @Test
    fun `borrar un archivo lo saca`() {
        val archivo = dir().resolve("datos.json")
        fs.writeText(archivo, "hola")

        fs.delete(archivo)

        assertFalse(fs.exists(archivo))
    }
}
```

---

## 7. Test de integración: el flujo completo

### 7.1 El doble de gateway (extraído del `AgentLoopTest`)

El `AgentLoopTest` de FunshiEngineAI (portado a `:backend/desktopTest`) define
un `ScriptedGateway` **anidado** en la clase de test. Para que el test de
integración de la analítica pueda reusarlo (sin duplicarlo y sin que un test
dependa de la clase de otro), se extrae a su propio archivo. Es una
adaptación mínima y documentada del test portado.

```kotlin
// backend/src/desktopTest/kotlin/rag/test/ScriptedGateway.kt
package rag.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage

/**
 * Gateway que responde con un guion de rondas prefijado.
 *
 * Es el doble de prueba que ya usaba el `AgentLoopTest` (portado de
 * FunshiEngineAI); se extrae a un archivo propio para poder reusarlo en el
 * test de integración de la analítica.
 *
 * Cada request queda registrado en [requests] para poder assertear qué se le
 * pasó al modelo.
 */
class ScriptedGateway(private val script: List<List<LlmChunk>>) : LlmGateway {

    /** Cada request que recibió el gateway. */
    val requests = mutableListOf<List<ChatMessage>>()

    /** El catálogo de tools que recibió en cada ronda (null = modo por prompt). */
    val catalogs = mutableListOf<List<ToolSpec>?>()

    /** Cuántas rondas consumió. */
    var round = 0
        private set

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> {
        requests += messages
        catalogs += tools
        val chunks = script.getOrElse(round) { emptyList() }
        round++
        return flow { chunks.forEach { emit(it) } }
    }
}
```

> El `AgentLoopTest` pasa a usar este doble compartido (borrando su copia
> anidada). El resto del test queda intacto.

### 7.2 El esqueleto del test de integración

```kotlin
package kitoru.analytics

import kitoru.domain.AnalyticsAnalyzer
import kitoru.domain.Course
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.domain.Material
import kitoru.domain.MaterialKind
import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.ChatVisibility
import kitoru.domain.CitationStats
import kitoru.domain.FileCourseRepository
import kitoru.domain.FileEnrollmentRepository
import kitoru.domain.FileMaterialRepository
import kitoru.domain.FileMessageRepository
import kitoru.domain.FileThreadRepository
import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.test.InMemoryFileSystem
import llm.api.LlmChunk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.config.RagConfig
import rag.engine.RagEngine
import rag.retrieval.CourseMaterialSource
import rag.retrieval.KnowledgeBase
import rag.test.ScriptedGateway
import rag.test.runTest

/**
 * Flujo completo de una cátedra: crear, subir material, indexar, chatear con
 * un gateway falso, verificar las citas y analizar.
 *
 * Es el test que une las tres piezas (`:core` + `:backend` + `:llm-api`) y el
 * que verifica que lo que el docente ve sale de lo que el alumno efectivamente
 * escribió. No usa red ni modelo: el gateway es un guion.
 */
class AnalyticsFlowIntegrationTest {

    private val materialTexto =
        "El RAG recupera fragmentos relevantes del material de la cátedra y los pasa al modelo como contexto."

    @Test
    fun `flujo completo: catedra, material, indice, chat con citas y analitica`() = runTest {
        val fs = InMemoryFileSystem()
        val dirPrevio = KitoruPaths.dataDir
        KitoruPaths.setDataDir(FilePath("/mem"))
        try {
            // 1. La cátedra y la inscripción del alumno.
            val catedra = Course(
                id = "c1",
                code = "IA-2026-1",
                name = "Inteligencia Artificial",
                term = "2026-1",
                joinCode = "ABC123",
                systemPrompt = "Respondé sólo con el material de la cátedra y citá las fuentes.",
            )
            FileCourseRepository(fs).save(catedra)
            val inscripcion = Enrollment(
                id = "e1",
                courseId = "c1",
                userId = "u1",
                displayName = "Ana",
                role = CourseRole.ALUMNO,
                visibility = ChatVisibility.FULL,
            )
            FileEnrollmentRepository(fs).save(inscripcion)

            // 2. El material que sube el docente.
            val material = Material(
                id = "m1",
                courseId = "c1",
                title = "apuntes.md",
                kind = MaterialKind.DOCUMENT,
                extractedText = materialTexto,
                visible = true,
            )
            FileMaterialRepository(fs).save(material)

            // 3. La indexación.
            val kb = KnowledgeBase(RagConfig(chunkSize = 400, chunkOverlap = 50, topK = 3))
            kb.sources = listOf(CourseMaterialSource(listOf(material)))
            kb.sync()
            assertTrue(kb.chunkCount > 0, "el material tiene que quedar indexado")

            // 4. El chat, con un gateway falso.
            val respuesta = "El RAG recupera fragmentos relevantes del material de la cátedra [1]."
            val gateway = ScriptedGateway(listOf(listOf(LlmChunk.Text(respuesta))))
            val engine = RagEngine(kb.config, kb, gateway) { catedra.systemPrompt }

            val prepared = engine.prepare("¿Qué hace el RAG?", emptyList(), useDocs = true)
            assertTrue(prepared.sources.isNotEmpty(), "la pregunta tiene que recuperar material")
            assertTrue(gateway.requests.isEmpty(), "preparar el contexto no llama al modelo")

            val texto = gateway.chat(prepared.messages)
            assertEquals(respuesta, texto)

            // 5. La verificación de citas (determinista).
            val informe = engine.verify(texto, prepared.sources)
            assertTrue(informe.hasCitations, "la respuesta cita: $informe")
            assertEquals(1, informe.verified, "la cita está respaldada: ${informe.checks}")
            assertEquals(0, informe.unsupported)

            // 6. Lo que la app guardaría del chat.
            val hiloId = "c1|t1"
            FileThreadRepository(fs).save(
                ChatThread(hiloId, "c1", "u1", "Ana", createdAt = 1_699_920_000_000L),
            )
            FileMessageRepository(fs).save(
                ChatMessage("msg-1", hiloId, "user", "¿Qué hace el RAG?", createdAt = 1_699_920_000_000L),
            )
            FileMessageRepository(fs).save(
                ChatMessage(
                    "msg-2", hiloId, "assistant", texto,
                    createdAt = 1_699_920_000_000L,
                    sources = prepared.sources.map { it.label },
                    citation = CitationStats(
                        verified = informe.verified,
                        unsupported = informe.unsupported,
                        unknown = informe.unknownCount,
                    ),
                    latencyMs = 1_200,
                    tokensOut = 40,
                ),
            )

            // 7. La analítica que ve el docente.
            val analitica = AnalyticsAnalyzer.analyze(
                catedra,
                FileEnrollmentRepository(fs).listForCourse("c1"),
                FileThreadRepository(fs).listForCourse("c1"),
                FileMessageRepository(fs).listForCourse("c1"),
            )

            assertEquals(1, analitica.students)
            assertEquals(1, analitica.activeStudents)
            assertEquals(1, analitica.totalMessages)
            assertEquals(1, analitica.totalReplies)
            assertEquals(1.0, analitica.citationSupportRate, "la cita estaba respaldada")
            assertEquals(0.0, analitica.perStudent.first().ungroundedAnswerRate)
            assertTrue(
                analitica.topSources.any { it.label == "cátedra: apuntes.md" },
                "el material consultado tiene que aparecer en el top: ${analitica.topSources}",
            )
        } finally {
            KitoruPaths.setDataDir(dirPrevio)
        }
    }
}
```

### 7.3 Qué NO cubre el test de integración

- **Dos alumnos con visibilidades distintas** (el caso interesante de
  privacidad): conviene un segundo test en el mismo archivo —
  `el alumno oculto no aparece en la analitica de la catedra` — con Ana
  `FULL` y Beto `NONE`, que verifica T15 de punta a punta.
- **Reindexado en caliente**: subir un segundo material y volver a indexar.
- **Errores del gateway**: cubierto por `AgentLoopTest`; acá el gateway es
  feliz por definición.

---

## 8. Qué NO se testea y por qué (y qué riesgo queda)

| # | Qué no se testea | Por qué | Riesgo que queda | Mitigación |
|---|---|---|---|---|
| N1 | El `OpenAiCompatibleGateway` real contra un endpoint vivo (SSE, `delta.tool_calls` fragmentados, `[DONE]`, clasificación de errores HTTP) | Requiere red y un servidor; un test con red es un test que falla por causas externas | Un gateway roto rompe el chat entero sin que ningún test lo detecte | (a) Con `Ktor MockEngine` se puede testear el parseo del SSE **sin red**: es el próximo paso recomendado. (b) Mientras tanto, smoke test manual contra el Kitoru-Server antes de cada release. |
| N2 | El `systemFileSystem()` en Android (almacenamiento con scope, permisos, `renameTo`) | Requiere un dispositivo o emulador | Un `IOException` en Android voltea la app al guardar | El doble en memoria cubre la lógica; el FS real se prueba en desktop. La persistencia en Android se prueba a mano en QA. |
| N3 | Detalles del sistema de archivos real: disco lleno, archivo bloqueado por otro proceso, rutas con acentos o espacios en Windows | Son fallos del entorno, no del código | Un error poco claro al guardar | El `JsonStore` ya captura exceptions de lectura; falta capturar las de escritura y avisar en la UI (fuera del alcance de esta estrategia). |
| N4 | La UI (Compose Multiplatform) | `:ui` todavía no está construido | La UI puede mostrar mal los datos (un número, un color, un filtro) | Cuando exista `:ui`: tests de ViewModel (que son `commonTest` y usan `kotlin.test`) y tests de screenshot en desktop. |
| N5 | La calidad del modelo (si la respuesta es *buena*) | No es determinista y no es del dominio | El modelo puede responder mal y las métricas lo detectan (citas sin respaldo) | La calidad se mide con el verificador determinista de citas, que sí está testeado (`CitationVerifierTest`). |
| N6 | El `LocalModelService` (llama-server) | Necesita el binario y una GPU | El modo local puede no arrancar | Test manual del modo local; el resto del path es el mismo gateway. |
| N7 | El **rendimiento** del analizador con volúmenes reales | Un test de performance es frágil en CI (máquinas distintas) | Con 800 alumnos y 100 000 mensajes, el analizador actual es **O(E × M)** (filtra los mensajes una vez por alumno): 80 millones de comparaciones → varios segundos de UI bloqueada | (a) Optimizar a O(M + E) indexando los mensajes por hilo una sola vez. (b) Medir a mano con datos sintéticos y documentar el techo. (c) Si se quiere un test, un `@Tag("perf")` **excluido** del CI por defecto. |
| N8 | La concurrencia entre la app corriendo y el analizador leyendo (lectura mientras se escribe un mensaje) | Es un problema de la UI, no del analizador | Un agregado puede no incluir el último mensaje | El `JsonStore` serializa con `Mutex`: el analizador lee un snapshot. Aceptable. |
| N9 | La exportación de la analítica (CSV/JSON) | Todavía no existe | — | Cuando se implemente, su test es trivial (comparar el texto generado). |
| N10 | El módulo de permisos (`Capability`, `PermissionResolver`) | Tiene su propio plan de tests (`08-permisos.md` §10) | — | Ver ese documento. La analítica sólo depende de `ChatVisibility`, que sí está testeado acá. |
| N11 | La sincronización con Kitoru-Server (envío de chats al docente) | Es otro repo | — | — |

---

## 9. Ejecución y control

### 9.1 Comandos

```console
# Todo (escritorio + Android unit tests)
./gradlew check

# Sólo los tests de escritorio (lo que se corre en cada commit)
./gradlew :core:desktopTest :backend:desktopTest

# Un módulo, un test
./gradlew :core:desktopTest --tests "kitoru.domain.AnalyticsAnalyzerTest"

# El test de integración
./gradlew :backend:desktopTest --tests "kitoru.analytics.AnalyticsFlowIntegrationTest"
```

### 9.2 Reglas de control (para que "los tests pasan" signifique algo)

1. **El total de tests tiene que ser > 0.** Es el bug H10: hoy `:backend`
   reporta 0 tests y el build pasa en verde. En CI, agregar un paso que
   falle si el reporte HTML/JSON dice `tests = 0`.
2. **Ningún test puede tocar la red.** Si algo necesita red, va marcado
   (`@Tag("network")`) y excluido del CI por defecto.
3. **Ningún test puede depender del reloj.** No se usa `nowMillis()`: los
   tiempos son constantes (`DIA`).
4. **Ningún test escribe en el disco del usuario.** El FS real se prueba
   sobre un directorio temporal.
5. **Los tests que fallan hoy están marcados en el código** con un comentario
   que enlace al hallazgo (H1, H2, …). Cuando se arregla, se borra el
   comentario y el test pasa: el arreglo es el que se verifica.

---

## 10. Archivos

```
core/src/commonTest/kotlin/kitoru/
├── test/
│   ├── Fixtures.kt                    # builders + constantes de tiempo
│   └── InMemoryFileSystem.kt          # doble de FileSystem (+ ganchos de falla)
└── domain/
    ├── AnalyticsAnalyzerTest.kt       # T01..T36 (§2)
    ├── StudentAnalyticsTest.kt        # rates derivadas, NaN, bordes
    ├── AnalyticsPropertiesTest.kt     # invariantes con semilla fija (§3)
    ├── ModelPolicyResolveTest.kt      # tabla de bloqueos (§5)
    ├── JsonStoreTest.kt               # persistencia (§6)
    └── FileRepositoriesTest.kt        # repositorios sobre FS en memoria (§6)

core/src/desktopTest/kotlin/kitoru/platform/
└── SystemFileSystemTest.kt            # smoke test del FS real (JUnit 5)

backend/src/desktopTest/kotlin/
├── rag/test/ScriptedGateway.kt        # doble extraído del AgentLoopTest
└── kitoru/analytics/
    └── AnalyticsFlowIntegrationTest.kt # flujo completo (§7)

# Cambios de build (no son tests, pero son prerrequisito)
build.gradle.kts                      # + useJUnitPlatform() para desktopTest
core/build.gradle.kts                 # + junit-jupiter en desktopTest
```

---

## 11. Checklist de implementación

- [ ] **P0** Agregar `useJUnitPlatform()` para `desktopTest` (raíz) y
      `junit-jupiter` en `:core/desktopTest`. Verificar que el reporte de
      `:backend` pasa de `tests = 0` a `tests = 108`.
- [ ] **P0** Agregar `move` a `FileSystem` + implementación JVM.
- [ ] `core/commonTest/.../test/Fixtures.kt` e `InMemoryFileSystem.kt`.
- [ ] `AnalyticsAnalyzerTest` con los casos T01–T36 (marcando los que fallan
      hoy: T15, T19, T20, T34 y T35 por H1–H4; más el test de la fuga de la
      clave en `ModelPolicyResolveTest` (H9), los de escritura atómica (H6) y
      el de borrado de hilos (H7)).
- [ ] `StudentAnalyticsTest` (bordes de `NaN` y división por cero).
- [ ] `AnalyticsPropertiesTest` (invariantes I1–I10, semilla fija).
- [ ] `ModelPolicyResolveTest` (tabla de bloqueos + H9).
- [ ] `JsonStoreTest` (guardar/cargar, corrupto, ausente, atómica,
      concurrencia).
- [ ] `FileRepositoriesTest` (incluye H7).
- [ ] `SystemFileSystemTest` (smoke test del FS real).
- [ ] Extraer `ScriptedGateway` y adaptarlo en `AgentLoopTest`.
- [ ] `AnalyticsFlowIntegrationTest` (+ el caso de privacidad con dos alumnos).
- [ ] Aplicar los arreglos H1, H2, H3, H4, H7 en el código de producción y
      **quitar los comentarios de "falla hoy"** de los tests correspondientes.
- [ ] Agregar el paso de CI que falla si el total de tests es 0.

---

## Apéndice — Glosario de hallazgos

| # | Resumen | Severidad |
|---|---|---|
| H1 | `topSources` no filtra por visibilidad: filtra qué material consultó un alumno `NONE`. | **Privacidad** |
| H2 | `perDay` mezcla la actividad de los docentes: la suma no cuadra con `totalMessages`. | Corrección |
| H3 | Dos inscripciones del mismo `userId` duplican todos los totales. | Corrección |
| H4 | El analizador no filtra los hilos por `courseId`. | Corrección |
| H5 | `Course.analyticsEnabled` no se tiene en cuenta (la puerta debe estar en el servicio). | Diseño |
| H6 | `JsonStore` no escribe de forma atómica y `path.parent ?: path` crea un directorio. | Integridad de datos |
| H7 | `FileMessageRepository.delete(threadId)` borra por id de mensaje: no borra nada. | Corrección |
| H8 | `DayActivity.day` se documenta como medianoche local y es UTC. | Documentación |
| H9 | Con `API_KEY` bloqueado y `BASE_URL` libre, la clave de la cátedra puede viajar a un servidor del alumno. | **Seguridad** |
| H10 | Los tests de `:backend` no se ejecutan (falta `useJUnitPlatform()`): 0 tests, build en verde. | **Proceso** |
| H11 | `CitationStats` con valores negativos produce tasas imposibles. | Robustez |
