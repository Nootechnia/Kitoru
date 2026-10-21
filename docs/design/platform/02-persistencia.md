# 02 · Persistencia y esquema de datos

> Diseño definitivo de cómo Kitoru guarda sus datos: qué formato, dónde vive
> cada cosa, cómo se versiona, cómo se escribe sin perder nada y cómo se
> migra.
>
> Módulo objetivo: un módulo nuevo `:storage` (KMP) que implementa las
> interfaces de repositorio de `:core` (`kitoru.domain`).
>
> **Fuentes leídas para este documento**:
> `docs/design/CONTEXTO.md`, `docs/design/domain/04-conversaciones.md`,
> `docs/design/domain/08-permisos.md`, `docs/design/analytics/02-metricas.md`,
> `docs/design/analytics/06-coste-latencia.md`, `docs/design/analytics/07-temporal-cohortes.md`,
> `docs/design/teacher/10-informes.md`,
> `core/.../domain/JsonStore.kt`, `core/.../domain/FileRepositories.kt`,
> `core/.../domain/Repositories.kt`, `core/.../domain/Chat.kt`,
> `core/.../domain/Course.kt`, `core/.../domain/ModelPolicy.kt`,
> `core/.../domain/Analytics.kt`, `core/.../domain/AnalyticsAnalyzer.kt`,
> `core/.../platform/FileSystem.kt`, `core/.../platform/KitoruPaths.kt`,
> `C:/Users/gianf/Desktop/Projects/FunshiEngineAI/backend/.../rag/store/ChatStore.kt`.

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| **D1** | Los datos estructurados (cátedras, inscripciones, materiales, hilos, mensajes, analítica, auditoría) viven en **una base SQLite** vía **SQLDelight**. | Reescribir 400.000 mensajes por cada mensaje nuevo es inviable; y la analítica necesita `GROUP BY` sobre 400.000 filas. |
| **D2** | **SQLite es la única fuente de verdad.** No hay `stats.json` ni `aggregate.json` ni caches paralelos: los agregados *son* las consultas SQL. | Dos fuentes de verdad ⇒ inconsistencia silenciosa. Una base transaccional elimina la categoría del problema. |
| **D3** | Los **archivos** (materiales originales, texto extraído, modelos, logs, exportaciones) siguen en el disco, en el layout de `04-conversaciones.md` §5.1. La base guarda sólo sus metadatos. | Son datos grandes, derivados y transferibles: no van en una base relacional. |
| **D4** | El `.kitoru` es un **ZIP con entradas JSON legibles** (`manifest.json`, `course.json`, `threads.json`, `messages.json`…). El importador acepta también el JSON plano `kitoru.chat-export` v1. | Un bundle de 600 MB no se puede parsear entero; y el formato tiene que ser legible y diffeable. |
| **D5** | La **API key no está en la base ni en ningún JSON**: vive en el `SecureStore` del sistema y se rehidrata en memoria. `ModelPolicy.apiKey` nunca se serializa. | Una credencial en texto plano es un costo de cuota y un abuso de la institución. |
| **D6** | El esquema se versiona con **`PRAGMA user_version`** + migraciones `.sqm` de SQLDelight, verificadas en CI con `verifyMigrations`. | Las migraciones se prueban, no se improvisan en la máquina del usuario. |
| **D7** | Una **base vieja o más nueva** no se pisa nunca: se renombra, se avisa y se crea una nueva. Una base **corrupta** se renombra con sufijo y se conserva. | Perder la cursada de un alumno por una migración mal escrita es inaceptable. |
| **D8** | Las escrituras son **transacciones SQLite** (`db.transaction { }`) con un `Mutex` por proceso; las lecturas concurrentes no bloquean gracias a **WAL**. | Atomicidad real y UI que no se congela. |
| **D9** | `message` **denormaliza** `course_id` y `author_id`, garantizados por una **clave foránea compuesta** contra `thread(id, course_id, author_id)`. | La analítica hace `JOIN` de 2 tablas en vez de 3, y la consistencia no depende de que la app se porte bien. |
| **D10** | `message.day_start` se **materializa** al escribir (medianoche local de la zona de la cátedra). | El día es local y configurable por cátedra; la columna evita recalcular en cada consulta y permite un índice `(course_id, day_start)`. |
| **D11** | Las citas y las consultas por material viven en `message_source` (una fila por fuente recuperada, con `cited_count`/`supported_count`/…). | Habilita «consultado ≠ citado ≠ respaldado» **por `materialId`** (D8 de `02-metricas.md`). |
| **D12** | `AnalyticsAnalyzer` (función pura) **se conserva** como especificación de referencia, y un test de **paridad** lo compara con las consultas SQL sobre la misma base sembrada. | La analítica sigue siendo determinista y testeable sin disco, y SQL no puede divergir sin que el test lo note. |
| **D13** | Dialecto SQL **SQLite 3.18** (el más conservador que cubre lo necesario): sin `UPSERT`, sin funciones de ventana, sin `FILTER`. | `minSdk = 24` ⇒ SQLite 3.9 en Android 4.7–6. Escribir contra 3.18 garantiza que la misma consulta corre en **todas** las plataformas. |
| **D14** | El corpus (texto extraído + fragmentos) vive en **disco**, indexado **en memoria** con el BM25 ya portado de FunshiEngineAI. | Es dato derivado y regenerable; meterlo en la base infla el `.db`, las copias de seguridad y cada `VACUUM`. |
| **D15** | La **base no se cifra** en la v1 (fuera SQLCipher). El modelo de amenazas y las mitigaciones están en §10.3. | El texto de los chats es visible para el docente por diseño; lo que hay que proteger es la **credencial**. |
| **D16** | El `FileSystem` de `:core` se extiende con `move`, `sync`, `lastModifiedMillis` (ya pedidos por `04-conversaciones.md` §5.6) y **`readBytes`/`writeBytes`** (nuevos, para el almacén seguro). | La base no se escribe con `FileSystem`, pero el respaldo, el blob cifrado y los bundles sí. |

---

## 1. El problema concreto

### 1.1 Qué hace hoy el código

`FileMessageRepository` (`core/.../domain/FileRepositories.kt:109`) extiende
`FileBackedRepository<ChatMessage>` con `fileName = "messages.json"`. Eso
significa que **todos los mensajes de todas las cátedras** viven en un único
archivo `config/messages.json`.

Cada alta de mensaje recorre esta cadena (`FileRepositories.kt:17` →
`JsonStore.kt:49`):

```kotlin
override suspend fun put(item: T) = store.update { list ->
    val without = list.filterNot { sameId(it, item) }
    without + item
}
```

```kotlin
suspend fun update(transform: (T) -> T) = mutex.withLock {
    _write(transform(_read()))        // ← lee TODO, transforma, escribe TODO
}
```

O sea, por **cada** mensaje:

1. lee el archivo entero (cientos de MB) a memoria,
2. lo deserializa (parseo JSON de cientos de MB),
3. serializa la lista completa otra vez,
4. abre el archivo en modo *truncate* y lo reescribe.

Además `listForCourse` (`FileRepositories.kt:116`) carga **todo** en memoria y
filtra en Kotlin con la convención `threadId.startsWith("$courseId|")`.

### 1.2 La medición

Simulación del formato exacto que produce `KitoruJson`
(`prettyPrint = true`, `encodeDefaults = true`) sobre `ChatMessage`, con
textos realistas (pregunta de 40–120 caracteres, respuesta de 120–450
caracteres, fuentes + citas + tool calls):

| Mensajes | Archivo `messages.json` | Bytes por mensaje | Lo que se escribe **por cada mensaje nuevo** |
|---:|---:|---:|---:|
| 10.000 | 15,5 MiB | 1.405 B | 15,5 MiB |
| 50.000 | 77,5 MiB | 1.405 B | 77,5 MiB |
| 200.000 | 310 MiB | 1.405 B | 310 MiB |
| **400.000** | **536 MiB** | **1.405 B** | **536 MiB** |

Costo de la sesión del enunciado (200 alumnos × 2.000 mensajes = 400.000
mensajes, guardados en 20 turnos con los 200 alumnos chateando):

```
mensajes guardados en la sesión : 8.000
bytes escritos (suma)           : ≈ 7,6 GiB      (1.405 B × N²/2)
lecturas + parseos              : ≈ 7,6 GiB      (otra vez)
```

El parseo del mismo payload tarda **≈ 8,9 s** con el parser JSON de referencia
de Python (medido; el de `kotlinx.serialization` está en el mismo orden de
magnitud). Traducido a la experiencia de usuario: **la UI se congela varios
segundos por cada mensaje**, y al final de la cursada, minutos.

El caso del enunciado (200 × 2.000) es un **caso bueno**: una cátedra de 400
alumnos × 3.000 mensajes = 1,2 M de mensajes ⇒ **≈ 1,6 GiB de archivo** y
**≈ 100 GiB escritos** en el semestre.

### 1.3 Defectos reales (verificables en el código, no opiniones)

| # | Defecto | Ubicación | Efecto |
|---|---|---|---|
| 1 | `delete(threadId)` **no borra nada**: llama `removeById(threadId)`, que filtra por `idOf(item) == id` y `idOf` devuelve el **id del mensaje**, no el del hilo. | `FileRepositories.kt:126` vs `:129` | Borrar un hilo deja sus mensajes huérfanos en el archivo. El repositorio no puede borrar una conversación. |
| 2 | `put()` es **O(n)** por alta ⇒ **O(n²)** para insertar n mensajes. | `FileRepositories.kt:17-20` | Con 400.000 mensajes, insertar el último cuesta 400.000 comparaciones y 400.000 deserializaciones. |
| 3 | `_read()` devuelve `empty()` ante **cualquier** error de parseo. | `JsonStore.kt:59-66` | Un archivo truncado se ve como «no hay datos». Y el siguiente `_write()` **sobrescribe el archivo entero**: pérdida total y silenciosa. |
| 4 | `_write()` escribe en modo *truncate*, **sin atómico**. | `JsonStore.kt:68-71` + `FileSystem.jvm.kt:23` | Un corte de luz de 200 ms deja el archivo a medias ⇒ por (3), la cursada entera desaparece. |
| 5 | `listForCourse` depende de la convención `"<courseId>|"` dentro del `threadId`. | `FileRepositories.kt:121` | Acoplamiento invisible entre dos repositorios; un id de hilo mal formado hace que la consulta devuelva datos de otra cátedra. |
| 6 | `Material.extractedText` vive **dentro** de `materials.json`. | `Course.kt:107` | El corpus completo se re-serializa en cada alta de material: mismo problema, otra vez. |
| 7 | `ModelPolicy.apiKey` se serializa en `courses.json` y en `threads.json`. | `ModelPolicy.kt:43` | La clave de la institución queda en texto plano en el disco. |
| 8 | `FileSystem` no tiene `move` ni `sync`. | `FileSystem.kt:47` | No se puede escribir de forma atómica (y `04-conversaciones.md` §5.6 ya lo pide). |

> Los puntos 3 y 4 no son sólo problemas de escalabilidad: **son pérdida de
> datos**. Y el punto 1 es un bug funcional: hoy la app **no puede borrar un
> hilo**. Los tres se arreglan solos al pasar a una base transaccional.

### 1.4 Lo que la analítica ya pidió (el disparador se cumplió)

`04-conversaciones.md` §5.4 descartó SQLite «por ahora» y dejó escrito el
criterio de reevaluación:

> «Si el nº de alumnos crece mucho (>500 por cátedra) o **aparecen consultas
> nuevas**, se reconsidera.»

Las consultas nuevas **aparecieron**: `analytics/02-metricas.md` define ~30
métricas, y several de ellas no se pueden calcular con una lista de archivos:

| Métrica | Qué exige | Con JSONL-por-hilo |
|---|---|---|
| Conteos por alumno (`M`, `R`, `T`, `D`) | `GROUP BY` sobre 400k filas | leer y parsear los 200 hilos del alumno |
| Actividad por día | agrupar por día local del curso | lo mismo, y el día local exige zona horaria por cátedra |
| p50/p95 de latencia | **histograma** de 12 buckets, sumable entre alumnos | hay que acumular y fusionar histogramas por hilo a mano |
| Consultas / citadas / respaldadas **por `materialId`** | `JOIN` + `GROUP BY material_id` | el `ChatMessage.sources` es `List<String>`: la etiqueta es frágil (D8 de `02-metricas.md`) |
| Sesiones (hueco > 30 min) | scan **ordenado global** del alumno | fusionar 25 archivos ordenados en memoria, en Kotlin, cada vez |
| `USR`, `NSC`, `hitRate` | ratios ponderadas sobre poblaciones filtradas por visibilidad | lo mismo, en Kotlin |
| `tasaRepeticion` | comparar texto de todos los mensajes del alumno | escaneo O(n²) en memoria |

La alternativa que propone el documento (§5.1) es mantener `stats.json` y
`aggregate.json` como **caches**. Eso convierte el problema de *consulta* en
un problema de *invalidación de cache*: dos fuentes de verdad que se desincronizan
en el primer corte de luz y producen números que contradicen la especificación.
D2 lo evita: **no hay cache, hay índices**.

---

## 2. Las cuatro opciones

### 2.1 Opción A — JSON/JSONL por hilo

Un directorio por hilo, `messages.jsonl` en modo *append* (el diseño de
`04-conversaciones.md` §5.1–5.5).

- **Escritura**: excelente. Se agrega **una línea** (≈1,1 KB) por mensaje.
  Costo total del semestre: 200 MB escritos **una vez** (no N²/2).
- **Lectura de un hilo**: excelente. Se lee 66 KB.
- **Lectura agregada**: mala. Cada consulta de analítica tiene que recorrer
  todos los hilos de la cátedra: 440 MB parseados por consulta.
- **Borrado de un hilo**: excelente (`delete` de un directorio).
- **Integridad**: el `Mutex` es **por proceso**. Si el usuario abre la app en
  dos procesos (o la app y una herramienta de diagnóstico), las escrituras se
  pisan: el JSONL no tiene transacciones ni `user_version`.
- **Portable**: 10/10. Cero dependencias.

### 2.2 Opción B — un `messages.json` por cátedra

Un archivo por cátedra en vez de uno global.

- Reduce el archivo de 536 MiB a 536/200 = 2,7 MiB por cátedra (si hay 200
  cátedras). **Pero** una cátedra grande (400 alumnos × 2.000) vuelve a tener
  2,7 MiB… y una sola cátedra muy grande (todos los alumnos de la facultad)
  vuelve al problema global.
- Sigue reescribiendo el archivo completo en cada mensaje: el costo por
  mensaje es O(cátedra), no O(1).
- La consulta agregada de la cátedra lee el archivo entero: 2,7 MiB parseados
  por consulta. Mejor que antes, pero sin consultas agregadas de verdad
  (todo se filtra en Kotlin).
- **Portable**: 10/10.

### 2.3 Opción C — SQLite con SQLDelight

- **Escritura**: un `INSERT` dentro de una transacción. O(1), atómico,
  con journaling y rollback.
- **Lectura agregada**: `GROUP BY` dentro de SQLite, con índices. Devuelve
  decenas de filas, no 400.000 objetos.
- **Concurrencia**: WAL → lectores concurrentes con un escritor; `busy_timeout`
  para esperar en vez de fallar.
- **Migraciones**: `.sqm` + `verifyMigrations` en CI.
- **Portabilidad**: `app.cash.sqldelight` es KMP: `android-driver` (SQLite del
  framework, sin binario extra) y `sqlite-driver` (JDBC, `sqlite-jdbc` con
  los natives de Win/Linux/mac) para el escritorio. El mismo esquema `.sq`
  y el mismo código Kotlin en las dos plataformas.
- **Compose Multiplatform**: SQLDelight **no** depende de la UI; la
  integración es `Query.asFlow()` (`coroutines-extensions`), que emite de nuevo
  cuando cambia la tabla underlying. En Compose:
  `val rows by query.asFlow().mapToList().collectAsState(initial = emptyList())`.
  Eso **reemplaza la invalidación manual** que el store de archivos no tiene
  (hoy no hay forma de saber que un archivo cambió: hay que releerlo a mano).
- **Costo**: un plugin de Gradle, 3 dependencias, un paso de generación de
  código, y un `expect/actual` para el driver.

### 2.4 Opción D — Room

- **Portabilidad**: ❌. Room es **Android**: necesita `Context`, `androidx.room`
  y `androidx.sqlite`. El target de escritorio de Kitoru es `jvm("desktop")`,
  un JVM normal: **no hay driver de Room para escritorio**. Room 2.7 tiene
  artefactos KMP para Android/iOS/macOS, pero **no** para un target JVM de
  escritorio, y sigue siendo experimental.
- **Consultas agregadas**: las mismas de SQLDelight (Room es SQL), pero con
  anotaciones, un processor de KSP y menos control sobre el SQL generado.
- **Veredicto**: **descartada**. Kitoru es *desktop-first*; Room elimina la
  mitad del producto.

### 2.5 Tabla comparativa

| Criterio | **A** JSONL por hilo | **B** `messages.json` por cátedra | **C** SQLite + SQLDelight | **D** Room |
|---|---|---|---|---|
| **Portabilidad** | 10/10 (cero deps) | 10/10 | 9/10 (driver por plataforma, 2 drivers mantenidos por el proyecto) | ❌ 0/10 (sólo Android) |
| **Escritura: costo por mensaje** | ✅ O(1), 1,1 KB | ❌ O(cátedra), 2,7 MiB | ✅ O(1), un `INSERT` transaccional | ✅ O(1) |
| **Escrituras concurrentes** | ❌ `Mutex` por proceso; sin transacciones; dos procesos se pisan | ❌ igual | ✅ WAL + transacciones + `busy_timeout` | ✅ igual que C |
| **Consultas agregadas (analítica)** | ❌ hay que parsear los 200 hilos (440 MB) por consulta; o caches que se desincronizan | ❌ se parsea el archivo de la cátedra y se filtra en Kotlin | ✅ `GROUP BY` con índice: 10–100 ms | ✅ (pero no aplica) |
| **Tamaño en disco (400k msg)** | ≈ 440 MB (JSONL) | ≈ 536 MB (JSON) | ≈ 290–390 MB (texto + metadatos + índices) | ≈ igual que C |
| **Migración de esquema** | `schemaVersion` manual en un JSON; migraciones a mano, sin rollback | idem | ✅ `.sqm` + `user_version` + `verifyMigrations` en CI + rollback por backup | idem que C |
| **Compleplicidad** | Baja al empezar, **alta al crecer** (caches, índices en memoria, consistencias) | Baja | Media (plugin + codegen + driver) | Media-alta (KSP + Android) |
| **Consultas observables (`Query.asFlow`)** | ❌ hay que inventar un `Flow` + invalidación manual | ❌ idem | ✅ nativo (`coroutines-extensions`) | ✅ (LiveData/Flow) |
| **Exportación legible** | ✅ el JSONL *es* el formato de exportación | ✅ | ✅ **con un exportador explícito** (D4) | ✅ con un exportador explícito |
| **Borrar una cátedra completa** | `delete` de un directorio | `delete` de un archivo | ✅ `DELETE FROM course WHERE id=?` con cascadas | ✅ idem |

### 2.6 El punto que decide

El enunciado pide comparar «consultas agregadas (que la analítica necesita)» y
«el formato de exportación `.kitoru` tiene que ser legible». Estas dos cosas
**no son la misma decisión**:

> **Legibilidad es una propiedad del formato de exportación, no del formato de
> almacenamiento.**

El `.kitoru` es, de todas formas, obligatorio: es el mecanismo por el que un
alumno que usa IA local le entrega sus chats al docente
(`04-conversaciones.md` D7) y el mecanismo de respaldo del docente
(`teacher/10-informes.md` §6). El exportador **lee de la base y escribe JSON
legible**; a la base no le importa el formato del archivo.

El argumento contrario que circuló —«con SQLite la base no se puede copiar
como carpeta, hay que exportarla»— **es vacío**: el exportador hay que
escribirlo igual, porque el `.kitoru` es un requisito del producto, no una
consecuencia del almacenamiento.

---

## 3. Veredicto

### 3.1 La recomendación

**Opción C — SQLite con SQLDelight**, como **única fuente de verdad** de los
datos estructurados (D1, D2), con:

- los **materiales, el corpus, los modelos, los logs y las exportaciones en
  disco**, en el layout ya especificado (`04-conversaciones.md` §5.1), sin
  cambios (D3);
- el **`.kitoru` como ZIP de entradas JSON legibles** (D4);
- la **clave de API fuera de la base**, en el `SecureStore` del sistema (D5);
- el **corpus indexado en memoria** con el BM25 que ya está portado (D14).

### 3.2 Fundamento, punto por punto

1. **El problema medido no es un problema de JSON: es de *reescritura*.**
   La opción A lo arregla (append O(1)). Pero el problema real del enunciado
   tiene dos caras y A sólo arregla una: la escritura. La lectura agregada
   —que la analítica necesita y que `02-metricas.md` ya especificó con ~30
   métricas— sigue siendo un escaneo de 440 MB por consulta.

2. **A + caches es B con más pasos.** La alternativa honesta a SQL en el mundo
   de archivos es mantener `stats.json`/`aggregate.json`. Eso duplica el
   estado, obliga a invalidar, y el fallo (un número que no cuadra con la
   especificación) es exactamente el que `02-metricas.md` §0.7 viene a
   corregir. **SQL es la manera de no tener caches.**

3. **El disparador de reevaluación que puso el propio diseño ya se cumplió**
   (§1.4). Reconsiderar ahora cuesta menos que reconsiderar después: Kitoru
   es **nascente, no hay un solo dato real que migrar** (§10 de este
   documento). La ventana para elegir bien es ahora.

4. **La analítica necesita más que `GROUP BY`.** Percentiles p50/p95 exige
   histograma (D11 + `latency_bucket`), sesiones exige orden global, y
   «consultado ≠ citado ≠ respaldado» por `materialId` exige una tabla de
   fuentes, no una `List<String>`. Modelar eso en archivos significa
   reimplementar una base de datos en Kotlin: la base ya existe, es
   estándar, es de 500 KB y funciona en las dos plataformas.

5. **El coste de SQLDelight es real y está pagado de antemano.** Tres
   dependencias, un plugin, un paso de generación. A cambio: transacciones de
   verdad, `GROUP BY` de verdad, migraciones verificadas en CI, consultas
   observables para Compose, y **robustez** que el dominio necesita.

6. **Y no se pierde nada del diseño por archivos:** el layout de carpetas, el
   patrón de escritura atómica (`AtomicFiles`, §8.1), el `FileSystem`
   extensible y el `.kitoru` JSON se conservan **exactamente**. Lo que se cae
   es el directorio `chats/<authorId>/<threadId>/messages.jsonl` y sus caches
   (`stats.json`, `aggregate.json`): pasan a ser filas.

### 3.3 Reconciliación con los documentos existentes

| Documento | Qué dice | Qué pasa con este diseño |
|---|---|---|
| `04-conversaciones.md` §5.4 | «SQLite: descartada por ahora; archivos ahora; se reconsidera si aparecen consultas nuevas» | **El criterio se cumplió** (§1.4). Se reconsidera y se elige SQL. La decisión queda registrada aquí y hay que **actualizar §5.4 y §5.1** de ese documento para que no queden dos verdades. |
| `04-conversaciones.md` §5.1 | Layout `config/*.json` + `courses/<id>/chats/...` | El layout de **materiales y corpus** se mantiene tal cual. `config/*.json` y `courses/<id>/chats/` pasan a la base. `stats.json` y `aggregate.json` **desaparecen** (D2). |
| `04-conversaciones.md` §5.6 | `AtomicFiles`, extensión de `FileSystem` con `move`/`sync`/`lastModifiedMillis` | **Se adopta tal cual** (D16). Sigue siendo necesario para `settings.json`, los logs, los blobs del almacén seguro y los `.kitoru` de texto plano. Falta `readBytes`/`writeBytes`. |
| `04-conversaciones.md` §6.3 | `.kitoru` = JSON plano, `format: "kitoru.chat-export"`, `version: 1`, `checksum` | Se conserva **como formato de entrada** (importador) y como opción de salida. El formato **canónico** pasa a ser el bundle ZIP con `manifest.json` (D4), que unifica el export del alumno y el backup del docente y permite streaming. |
| `teacher/10-informes.md` §6 | `.kitoru` = ZIP con `manifest.json`, `courses.json`, `messages.json`, `materials/` | **Adoptado** como formato canónico. Se le agrega `format: "kitoru"` + `version` + `scope.kind ∈ {chat-export, course, app}`. |
| `02-metricas.md` §9.1 | 15 cambios de modelo de datos bloqueantes (`status`, `grounding`, `sourceCount`, `byMaterial`, `latencyHistogram`, …) | **Todos** tienen columna en el esquema (§5). Este documento es el que decide la forma de las tablas. |
| `02-metricas.md` §0.7 | 12 defectos de la implementación actual de la analítica | El esquema los hace **estructuralmente imposibles** de repetir: `status` separa `CANCELLED`/`ERROR`, `day_start` es local, `latency_sum_ms` no se reconstruye desde promedios, `message_source` agrega por `material_id`, y la visibilidad se filtra **en SQL**. |
| `08-permisos.md` §8.4 | `FileAccessLogRepository` sobre `config/access-log.json` | Pasa a la tabla `access_log` (§5.9), con tope y consulta por cátedra. |

### 3.4 Costos y riesgos que se aceptan

| Riesgo | Impacto | Mitigación |
|---|---|---|
| El plugin de SQLDelight rompe el build en CI | desarrollo bloqueado | Pin de versión (`2.1.0`), `verifyMigrations` como tarea de CI, y el mismo `.sq` sirve para un `sqlite3` de línea de comandos si hay que depurar a mano |
| Un driver por plataforma que mantener | 2 drivers | Son 2 artefactos mantenidos por SQLDelight; el *código* de Kitoru es uno |
| El `.db` no es legible con un editor de texto | Depuración manual más difícil | `sqlite3 kitoru.db ".schema"`; y el `.kitoru` **sí** es JSON legible |
| Una migración mal escrita borra datos | Pérdida de cursada | Backup automático antes de migrar + `verifyMigrations` en CI + D7 (nunca pisar una base vieja) |

---

## 4. Arquitectura: el módulo `:storage`

### 4.1 Módulos

```
:core     kitoru.domain + kitoru.platform   (puro, SIN dependencias de base)
:storage  kitoru.storage                    (SQLDelight, implementa las interfaces de :core)
:llm-api  ...                              (sin cambios)
:backend  ...                              (sin cambios)
:ui       ...                              (consume :core; los repositorios le llegan por DI)
:desktop  ...                              (desktopMain de :storage + driver JVM)
:android  ...                              (androidMain de :storage + driver Android)
```

`:core` **no** depende de SQLDelight: las interfaces de `Repositories.kt` ya
existen y son el contrato. `:storage` las implementa. Esto mantiene el
dominio testeable sin base y deja abierta la puerta a un backend remoto
(Kitoru-Server) que implemente las mismas interfaces.

### 4.2 Gradle

```kotlin
// storage/build.gradle.kts
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)          // app.cash.sqldelight 2.1.0
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)             // app.cash.sqldelight:runtime
                implementation(libs.sqldelight.coroutinesExt)      // app.cash.sqldelight:coroutines-extensions
            }
        }
        val desktopMain by getting {
            dependencies { implementation(libs.sqldelight.sqliteDriver) }  // sqlite-driver (JDBC)
        }
        val androidMain by getting {
            dependencies { implementation(libs.sqldelight.androidDriver) }  // android-driver
        }
    }
}

sqldelight {
    databases {
        create("KitoruDatabase") {
            packageName.set("kitoru.storage.db")
            dialect(libs.sqldelight.dialect)                  // app.cash.sqldelight:sqlite-3-18
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)                         // tarea: verifySqlDelightMigration
        }
    }
}
```

> **Versión**: `2.1.0` es la contemporaneous a Kotlin 2.1.0 y es la que
> compila el `kotlin-compiler-embeddable` 2.1.x sin conflictos. Si el build
> protesta, `2.0.2` es el plan B estable. **No** subir a 2.4.x sin verificar
> la compatibilidad con AGP 8.7 / Kotlin 2.1.0.

### 4.3 Drivers

```kotlin
// desktopMain
actual fun desktopDriver(dbFile: FilePath, schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver {
    val url = "jdbc:sqlite:" + dbFile.value.replace('\\', '/')   // Windows: C:/... no C:\...
    val driver = JdbcSqliteDriver(url, Properties(), schema)     // crea o migra solo
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    driver.execute(null, "PRAGMA journal_mode = WAL", 0)
    driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
    driver.execute(null, "PRAGMA busy_timeout = 5000", 0)
    return driver
}

// androidMain
actual fun androidDriver(context: Context, schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver {
    val driver = AndroidSqliteDriver(schema, context, "kitoru.db")
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    driver.execute(null, "PRAGMA busy_timeout = 5000", 0)
    return driver
}
```

**Cuidado con `foreign_keys`**: en SQLite están **desactivadas por defecto** en
casi todas las conexiones. Si quedan apagadas, **las cascadas no existen** y
borrar una cátedra deja 400.000 mensajes huérfanos. Por eso:

- el `PRAGMA` se ejecuta en **cada** apertura (no sólo en la primera);
- hay un **test** que inserta un hilo con mensajes, borra el hilo y verifica
  que los mensajes desaparecieron (y otro que borra la cátedra entera);
- `PRAGMA foreign_keys` se consulta al abrir y, si valiera `0`, la app
  **no arranca** con un mensaje en español.

**WAL en Android**: el `AndroidSqliteDriver` de SQLDelight usa el SQLite del
framework; el modo WAL se activa con `db.enableWriteAheadLogging()` en el
callback de apertura de `SQLiteOpenHelper`. Si la versión de SQLDelight
usada ya lo activa por defecto, la llamada explícita es inocua. En el
escritorio, `journal_mode = WAL` es el default que se fuerza con el `PRAGMA`.

### 4.4 Una advertencia que hay que dejar escrita en el código

> **Toda escritura debe pasar por las queries generadas de SQLDelight**
> (`db.messageQueries.insertMessage(...)`), **nunca** por
> `driver.execute(...)` crudo. El mecanismo de notificación que hace que
> `Query.asFlow()` re-emita se dispara desde las `Queries` generadas; una
> escritura cruda no notifica y la UI queda desactualizada sin avisar.

---

## 5. El esquema `.sq` completo

Archivos en `storage/src/commonMain/sqldelight/kitoru/storage/db/`:

```
Course.sq  Enrollment.sq  Profile.sq  Material.sq  Thread.sq
Message.sq  MessageSource.sq  ToolCall.sq  AccessLog.sq
Usage.sq  Schedule.sq  Meta.sq  Analytics.sq
```

Convenciones:

- **Timestamps**: `INTEGER` epoch millis.
- **Booleanos**: `INTEGER` 0/1 (SQLite no tiene booleano; `CHECK (x IN (0,1))`).
- **Enums**: `TEXT` + `CHECK (... IN (...))`. Se evita el `CREATE ENUM` de
  SQLDelight para que las columnas se puedan comparar con literales en SQL
  crudo y en las consultas de la analítica.
- **Colecciones**: `TEXT` con JSON (`tags_json`, `model_policy_json`). Son
  campos opacos que se leen y escriben enteros: no se consulta por SQL, así
  que no justifica una tabla.
- **Identificadores**: `TEXT` (UUID v4 en minúscula, como los que ya usan los
  documentos de `04-conversaciones.md`).

### 5.1 `Course.sq`

```sql
CREATE TABLE course (
    id                 TEXT    NOT NULL PRIMARY KEY,
    code               TEXT    NOT NULL DEFAULT '',
    name               TEXT    NOT NULL DEFAULT '',
    term               TEXT    NOT NULL DEFAULT '',
    description        TEXT    NOT NULL DEFAULT '',
    created_at         INTEGER NOT NULL DEFAULT 0,
    join_code          TEXT    NOT NULL DEFAULT '',
    system_prompt      TEXT    NOT NULL DEFAULT '',
    top_k              INTEGER NOT NULL DEFAULT 5,
    chunk_size         INTEGER NOT NULL DEFAULT 1200,
    analytics_enabled  INTEGER NOT NULL DEFAULT 1,
    allow_local_models INTEGER NOT NULL DEFAULT 1,
    published          INTEGER NOT NULL DEFAULT 0,
    published_at       INTEGER NOT NULL DEFAULT 0,
    -- Zona horaria de la cátedra: define el "día" de la analítica (§10 de
    -- 02-metricas.md). Vacío = zona del sistema.
    time_zone          TEXT    NOT NULL DEFAULT '',
    -- La ModelPolicy completa, serializada. La apiKey NUNCA va acá (ver §10).
    model_policy_json  TEXT    NOT NULL DEFAULT '{}',
    CHECK (top_k > 0),
    CHECK (chunk_size > 0),
    CHECK (published IN (0, 1)),
    CHECK (analytics_enabled IN (0, 1)),
    CHECK (allow_local_models IN (0, 1))
);

CREATE INDEX idx_course_code ON course(code);
CREATE INDEX idx_course_join_code ON course(join_code);
```

> `join_code` es la credencial de inscripción a la cátedra. No es una API key
> (no da acceso a un servicio), pero sí conviene que no sea trivial de
> adivinar: se genera con 8 caracteres de un alfabeto sin vocales
> ambiguas (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`) y se puede rotar
> (`COURSE_ROTATE_JOIN_CODE`).

### 5.2 `Enrollment.sq`

```sql
CREATE TABLE enrollment (
    id              TEXT    NOT NULL PRIMARY KEY,
    course_id       TEXT    NOT NULL,
    user_id         TEXT    NOT NULL,
    display_name    TEXT    NOT NULL DEFAULT '',
    role            TEXT    NOT NULL DEFAULT 'ALUMNO',
    joined_at       INTEGER NOT NULL DEFAULT 0,
    visibility      TEXT    NOT NULL DEFAULT 'FULL',
    -- 'RAW' = el docente tiene el texto; 'AGGREGATE' = sólo agregados
    -- (02-metricas.md §9.1, punto 14).
    analytics_scope TEXT    NOT NULL DEFAULT 'RAW',
    FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE CASCADE,
    CHECK (role IN ('TITULAR', 'AYUDANTE', 'ALUMNO')),
    CHECK (visibility IN ('FULL', 'METRICS_ONLY', 'NONE')),
    CHECK (analytics_scope IN ('RAW', 'AGGREGATE'))
);

-- Una inscripción por persona por cátedra.
CREATE UNIQUE INDEX idx_enrollment_course_user ON enrollment(course_id, user_id);
CREATE INDEX idx_enrollment_user ON enrollment(user_id);
CREATE INDEX idx_enrollment_course_role ON enrollment(course_id, role);
```

### 5.3 `Profile.sq`

```sql
CREATE TABLE profile (
    id           TEXT NOT NULL PRIMARY KEY,
    display_name TEXT NOT NULL DEFAULT '',
    email        TEXT NOT NULL DEFAULT '',
    created_at   INTEGER NOT NULL DEFAULT 0
);
```

### 5.4 `Material.sq`

```sql
CREATE TABLE material (
    id                TEXT    NOT NULL PRIMARY KEY,
    course_id         TEXT    NOT NULL,
    title             TEXT    NOT NULL DEFAULT '',
    kind              TEXT    NOT NULL DEFAULT 'DOCUMENT',
    -- Ruta del original, relativa a courses/<courseId>/materials/.
    path              TEXT    NOT NULL DEFAULT '',
    size_bytes        INTEGER NOT NULL DEFAULT 0,
    uploaded_by       TEXT    NOT NULL DEFAULT '',
    uploaded_at       INTEGER NOT NULL DEFAULT 0,
    tags_json         TEXT    NOT NULL DEFAULT '[]',
    visible           INTEGER NOT NULL DEFAULT 1,
    warning           TEXT    NOT NULL DEFAULT '',
    -- El TEXTO EXTRAÍDO NO ESTÁ ACÁ: vive en disco (§9).
    extracted_chars   INTEGER NOT NULL DEFAULT 0,
    content_hash      TEXT    NOT NULL DEFAULT '',
    extractor_version TEXT    NOT NULL DEFAULT '',
    indexed_at        INTEGER NOT NULL DEFAULT 0,
    chunks_indexed    INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE CASCADE,
    CHECK (kind IN ('DOCUMENT', 'SLIDES', 'TRANSCRIPT', 'LINK', 'OTHER')),
    CHECK (visible IN (0, 1))
);

CREATE INDEX idx_material_course ON material(course_id, uploaded_at);
CREATE UNIQUE INDEX idx_material_course_path ON material(course_id, path);
```

### 5.5 `Thread.sq`

```sql
CREATE TABLE thread (
    id                    TEXT    NOT NULL PRIMARY KEY,
    course_id             TEXT    NOT NULL,
    author_id             TEXT    NOT NULL,
    author_name           TEXT    NOT NULL DEFAULT '',
    title                 TEXT    NOT NULL DEFAULT '',
    title_source          TEXT    NOT NULL DEFAULT 'auto',
    created_at            INTEGER NOT NULL DEFAULT 0,
    updated_at            INTEGER NOT NULL DEFAULT 0,
    model_label           TEXT    NOT NULL DEFAULT '',
    effective_model_json  TEXT    NOT NULL DEFAULT '{}',
    tags_json             TEXT    NOT NULL DEFAULT '[]',
    origin                TEXT    NOT NULL DEFAULT 'local',
    closed_by_user        INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE CASCADE,
    CHECK (origin IN ('local', 'imported', 'remote')),
    CHECK (closed_by_user IN (0, 1))
);

CREATE INDEX idx_thread_course_updated ON thread(course_id, updated_at DESC);
CREATE INDEX idx_thread_course_author ON thread(course_id, author_id, updated_at DESC);
CREATE INDEX idx_thread_author ON thread(author_id);

-- Clave única que habilita la FK compuesta de message (D9).
CREATE UNIQUE INDEX idx_thread_id_course_author ON thread(id, course_id, author_id);
```

### 5.6 `Message.sq`

```sql
CREATE TABLE message (
    id                   TEXT    NOT NULL PRIMARY KEY,
    thread_id            TEXT    NOT NULL,
    -- Denormalizados y garantizados por la FK compuesta (D9).
    course_id            TEXT    NOT NULL,
    author_id            TEXT    NOT NULL,
    seq                  INTEGER NOT NULL,
    role                 TEXT    NOT NULL,
    text                 TEXT    NOT NULL DEFAULT '',
    created_at           INTEGER NOT NULL DEFAULT 0,
    -- Medianoche local de la zona de la cátedra, materializada (D10).
    day_start            INTEGER NOT NULL DEFAULT 0,
    -- Estado del turno (02-metricas.md §9.1 punto 1).
    status               TEXT    NOT NULL DEFAULT 'COMPLETED',
    -- 'GENERAL' (chat sin corpus) | 'RAG' (con corpus) (§9.1 punto 2).
    grounding            TEXT    NOT NULL DEFAULT 'GENERAL',
    -- Citas (§9.1 puntos 4 y 5).
    has_citations        INTEGER NOT NULL DEFAULT 0,
    citation_verified    INTEGER NOT NULL DEFAULT 0,
    citation_unsupported INTEGER NOT NULL DEFAULT 0,
    citation_unknown     INTEGER NOT NULL DEFAULT 0,
    citation_uncited     INTEGER NOT NULL DEFAULT 0,
    source_count         INTEGER NOT NULL DEFAULT 0,
    -- Recursos (§9.1 punto 10).
    tokens_in            INTEGER NOT NULL DEFAULT 0,
    tokens_out           INTEGER NOT NULL DEFAULT 0,
    tokens_estimated     INTEGER NOT NULL DEFAULT 0,
    tokens_context       INTEGER NOT NULL DEFAULT 0,
    tokens_history       INTEGER NOT NULL DEFAULT 0,
    tokens_question      INTEGER NOT NULL DEFAULT 0,
    -- Latencia: el total y el bucket, para poder hacer medias ponderadas y
    -- percentiles sin guardar la lista de muestras (§9.1 punto 9).
    latency_ms           INTEGER NOT NULL DEFAULT 0,
    latency_bucket       INTEGER NOT NULL DEFAULT 0,
    ttft_ms              INTEGER NOT NULL DEFAULT 0,
    error_kind           TEXT    NOT NULL DEFAULT '',
    model_label          TEXT    NOT NULL DEFAULT '',
    FOREIGN KEY (thread_id, course_id, author_id)
        REFERENCES thread(id, course_id, author_id) ON DELETE CASCADE,
    CHECK (role IN ('user', 'assistant', 'tool', 'system')),
    CHECK (status IN ('COMPLETED', 'CANCELLED', 'ERROR')),
    CHECK (grounding IN ('GENERAL', 'RAG'))
);

-- El hilo es la unidad de lectura de la UI: (thread_id, seq) la cubre.
CREATE UNIQUE INDEX idx_message_thread_seq ON message(thread_id, seq);

-- Analítica por curso y por día (D10): la columna materializa el día.
CREATE INDEX idx_message_course_day ON message(course_id, day_start, created_at);

-- Analítica por alumno (y detección de sesiones: orden temporal del alumno).
CREATE INDEX idx_message_course_author ON message(course_id, author_id, created_at);
```

> **Por qué `seq` y no confiar en `created_at`.** Dos mensajes pueden tener el
> mismo `created_at` (ms de resolución, escrituras en el mismo tick). `seq` es
> un contador **por hilo**, asignado dentro de la transacción de alta, y da un
> orden total y estable. `ORDER BY seq` es el orden canónico de un hilo;
> `created_at` es el orden temporal para la analítica.

### 5.7 `MessageSource.sq`

Una fila por **fuente recuperada** (chunk) en el contexto RAG de la respuesta.
Es la tabla que habilita D8 de `02-metricas.md` (agregar por `materialId`,
nunca por etiqueta) y la distinción **consultado ≠ citado ≠ respaldado** (§5
de `02-metricas.md`).

```sql
CREATE TABLE message_source (
    message_id       TEXT    NOT NULL,
    -- Posición 1-based en el contexto RAG: el marcador [n] de la respuesta
    -- apunta a la fuente con ordinal = n (mismo convenio que CitationVerifier).
    ordinal          INTEGER NOT NULL,
    material_id      TEXT,
    label            TEXT    NOT NULL DEFAULT '',
    heading          TEXT    NOT NULL DEFAULT '',
    chunk_index      INTEGER NOT NULL DEFAULT -1,
    retrieval_score  REAL    NOT NULL DEFAULT 0,
    -- Desglose de citas para ESTA fuente.
    cited_count        INTEGER NOT NULL DEFAULT 0,
    supported_count    INTEGER NOT NULL DEFAULT 0,
    unsupported_count  INTEGER NOT NULL DEFAULT 0,
    unknown_count      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, ordinal),
    FOREIGN KEY (message_id) REFERENCES message(id) ON DELETE CASCADE,
    -- Si se borra el material, la fuente histórica sobrevive (label) pero
    -- pierde el id: las estadísticas de "material más consultado" no se
    -- borran, y el material borrado pasa a "no disponible".
    FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE SET NULL
);

CREATE INDEX idx_source_material ON message_source(material_id);
CREATE INDEX idx_source_label   ON message_source(label);
```

### 5.8 `ToolCall.sq`

```sql
CREATE TABLE tool_call (
    id          TEXT    NOT NULL PRIMARY KEY,
    message_id  TEXT    NOT NULL,
    ordinal     INTEGER NOT NULL DEFAULT 0,
    name        TEXT    NOT NULL DEFAULT '',
    args        TEXT    NOT NULL DEFAULT '',
    outcome     TEXT    NOT NULL DEFAULT 'UNKNOWN',
    ok          INTEGER,
    result      TEXT    NOT NULL DEFAULT '',
    duration_ms INTEGER NOT NULL DEFAULT 0,
    agent       TEXT    NOT NULL DEFAULT '',
    FOREIGN KEY (message_id) REFERENCES message(id) ON DELETE CASCADE,
    CHECK (outcome IN ('OK', 'ERROR', 'DENIED', 'INVALID', 'UNKNOWN')),
    CHECK (ok IS NULL OR ok IN (0, 1))
);

CREATE INDEX idx_tool_call_message ON tool_call(message_id);
CREATE INDEX idx_tool_call_name   ON tool_call(name);
```

### 5.9 `AccessLog.sq`

```sql
CREATE TABLE access_log (
    id               TEXT    NOT NULL PRIMARY KEY,
    at               INTEGER NOT NULL DEFAULT 0,
    actor_id         TEXT    NOT NULL DEFAULT '',
    actor_name       TEXT    NOT NULL DEFAULT '',
    course_id        TEXT    NOT NULL DEFAULT '',
    capability       TEXT    NOT NULL DEFAULT '',
    target_user_id   TEXT    NOT NULL DEFAULT '',
    target_user_name TEXT    NOT NULL DEFAULT '',
    detail           TEXT    NOT NULL DEFAULT '',
    outcome          TEXT    NOT NULL DEFAULT 'ALLOWED',
    CHECK (outcome IN ('ALLOWED', 'DENIED'))
);

CREATE INDEX idx_access_log_course_at ON access_log(course_id, at DESC);
CREATE INDEX idx_access_log_at        ON access_log(at DESC);
```

> El log es **append-only** y local a la instalación. El tope de 5.000
> entradas que propone `08-permisos.md` §8.4 se aplica con una `DELETE` de las
> más viejas dentro de la misma transacción del `append`.

### 5.10 `Usage.sq`, `Schedule.sq`, `Meta.sq`

```sql
-- Usage.sq
-- Ledger mergeable por cátedra y período (analytics/06-coste-latencia.md).
CREATE TABLE usage_ledger (
    course_id  TEXT    NOT NULL,
    period     TEXT    NOT NULL,
    data_json  TEXT    NOT NULL DEFAULT '{}',
    updated_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (course_id, period)
);

CREATE TABLE budget (
    id          TEXT NOT NULL PRIMARY KEY,
    course_id   TEXT NOT NULL DEFAULT '',
    scope       TEXT NOT NULL DEFAULT 'course',
    period      TEXT NOT NULL DEFAULT '',
    limits_json TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE budget_override (
    id          TEXT NOT NULL PRIMARY KEY,
    budget_id   TEXT NOT NULL,
    user_id     TEXT NOT NULL DEFAULT '',
    limits_json TEXT NOT NULL DEFAULT '{}',
    FOREIGN KEY (budget_id) REFERENCES budget(id) ON DELETE CASCADE
);

CREATE TABLE price_table (
    id          TEXT NOT NULL PRIMARY KEY,
    model       TEXT NOT NULL DEFAULT '',
    currency    TEXT NOT NULL DEFAULT 'USD',
    prices_json TEXT NOT NULL DEFAULT '{}',
    updated_at  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE server_profile (
    id          TEXT NOT NULL PRIMARY KEY,
    label       TEXT NOT NULL DEFAULT '',
    base_url    TEXT NOT NULL DEFAULT '',
    model       TEXT NOT NULL DEFAULT '',
    -- Referencia a la clave en el SecureStore (§10). Nunca la clave.
    api_key_ref TEXT NOT NULL DEFAULT '',
    enabled     INTEGER NOT NULL DEFAULT 1,
    config_json TEXT NOT NULL DEFAULT '{}',
    CHECK (enabled IN (0, 1))
);

-- Schedule.sq
CREATE TABLE schedule_entry (
    id               TEXT    NOT NULL PRIMARY KEY,
    course_id        TEXT    NOT NULL,
    title            TEXT    NOT NULL DEFAULT '',
    kind             TEXT    NOT NULL DEFAULT 'CLASS',
    day              INTEGER NOT NULL DEFAULT 0,
    end_day          INTEGER NOT NULL DEFAULT 0,
    start_minute     INTEGER,
    duration_minutes INTEGER,
    notes            TEXT    NOT NULL DEFAULT '',
    created_by       TEXT    NOT NULL DEFAULT '',
    created_at       INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE CASCADE
);

CREATE INDEX idx_schedule_course_day ON schedule_entry(course_id, day);

-- Meta.sq
CREATE TABLE kitoru_meta (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL DEFAULT ''
);
```

`kitoru_meta` guarda: `schema_version`, `app_version_written`,
`legacy_json_imported`, `analytics_algorithm_version`, `corpus_extractor_version`.

### 5.11 Índice de índices y criterio

| Índice | Tabla | Columnas | Consulta que sirve | Criterio |
|---|---|---|---|---|
| `idx_message_thread_seq` | message | `(thread_id, seq)` **único** | «abrir este hilo» | Es el camino caliente: la UI abre un hilo y pide sus mensajes ordenados. Único ⇒ la secuencia por hilo no se rompe. Cubre además el `DELETE` en cascada. |
| `idx_message_course_day` | message | `(course_id, day_start, created_at)` | «actividad por día», «mensajes de la cátedra en un período» | `day_start` está **materializado** (D10), así que el `GROUP BY day_start` es un escaneo del índice, sin `GROUP BY` sobre una expresión. La `created_at` final permite acotar por rango dentro del día. |
| `idx_message_course_author` | message | `(course_id, author_id, created_at)` | «mensajes de este alumno», detección de sesiones | El orden temporal por alumno es lo que necesita la detección de sesiones (hueco > 30 min). |
| `idx_thread_course_updated` | thread | `(course_id, updated_at DESC)` | «hilos de la cátedra, más reciente primero» | El listado por defecto de la UI. |
| `idx_thread_course_author` | thread | `(course_id, author_id, updated_at DESC)` | «hilos de este alumno» | Cubre a la vez el filtro por alumno y el orden. |
| `idx_thread_id_course_author` | thread | `(id, course_id, author_id)` **único** | habilita la FK compuesta de `message` | No es una consulta: es la **garantía** de que `message.course_id`/`author_id` no puedan contradecir al hilo (D9). |
| `idx_source_material` | message_source | `(material_id)` | «materiales más consultados» (D8) | El `GROUP BY material_id` del ranking. |
| `idx_source_label` | message_source | `(label)` | recuperación por etiqueta (legado) | Permite evitar escaneos completos cuando el `material_id` es NULL (material borrado). |
| `idx_material_course` | material | `(course_id, uploaded_at)` | «materiales de la cátedra» | Listado del panel de materiales. |
| `idx_enrollment_course_user` | enrollment | `(course_id, user_id)` **único** | «inscriptos de la cátedra» | El `JOIN` de la analítica empieza por acá. |
| `idx_enrollment_user` | enrollment | `(user_id)` | «mis cátedras» | El listado del alumno. |
| `idx_tool_call_name` | tool_call | `(name)` | «herramientas más usadas» | El `GROUP BY name`. |
| `idx_access_log_course_at` | access_log | `(course_id, at DESC)` | «accesos a esta cátedra» | Auditoría por cátedra. |
| `idx_schedule_course_day` | schedule_entry | `(course_id, day)` | «hitos en un período» | Correlaciona actividad con parciales. |

**Índices que NO se crean** (y por qué):

- `message(created_at)` global: nadie consulta «todos los mensajes del mundo
  por fecha». Sería un índice grande que se actualiza en cada alta para una
  consulta que no existe.
- `message(text)`: la búsqueda por texto no es un caso de uso (el RAG busca
  en el **corpus**, no en los chats). Si algún día hace falta, es FTS5, y en
  Android eso implica subir de versión de SQLite (§4.3).
- `thread(title)`: los títulos se buscan a lo sumo con un `LIKE` sobre la
  lista de la cátedra, que son decenas.

### 5.12 Claves foráneas y cascadas

```
course ─┬─< enrollment        ON DELETE CASCADE
        ├─< material          ON DELETE CASCADE
        ├─< thread            ON DELETE CASCADE
        └─< schedule_entry    ON DELETE CASCADE
thread ──< message            ON DELETE CASCADE   (FK compuesta: thread_id+course_id+author_id)
message ─┬─< message_source   ON DELETE CASCADE
          └─< tool_call       ON DELETE CASCADE
message_source >── material    ON DELETE SET NULL
budget ──< budget_override    ON DELETE CASCADE
```

Efectos:

- **Borrar una cátedra** borra en cascada sus inscripciones, materiales,
  hilos, mensajes, fuentes y tool calls: **una sola sentencia**, sin scripts
  de limpieza, y sin dejar huérfanos (el defecto 1 del store de archivos queda
  resuelto por construcción).
- **Borrar un hilo** borra sus mensajes y, en cascada, sus fuentes y tool
  calls.
- **Borrar un material** deja las fuentes históricas con `material_id = NULL`
  y su `label`: las estadísticas de uso no se reescriben.

> Las cascadas **exigen** `PRAGMA foreign_keys = ON` (§4.3). Es el punto más
> frágil de toda la configuración y por eso tiene su propio test.

---

## 6. Las queries que la app necesita

SQLDelight genera, por cada consulta con nombre, una función tipada en el
`Queries` correspondiente (`db.messageQueries.selectByThread(...)`).

### 6.1 Escrituras

```sql
-- Message.sq
insertMessage:
INSERT INTO message (
    id, thread_id, course_id, author_id, seq, role, text, created_at, day_start,
    status, grounding, has_citations, citation_verified, citation_unsupported,
    citation_unknown, citation_uncited, source_count,
    tokens_in, tokens_out, tokens_estimated, tokens_context, tokens_history,
    tokens_question, latency_ms, latency_bucket, ttft_ms, error_kind, model_label
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

nextSeq:
SELECT COALESCE(MAX(seq), 0) + 1 FROM message WHERE thread_id = ?;

updateThreadTimestamp:
UPDATE thread SET updated_at = ? WHERE id = ?;

deleteByThread:
DELETE FROM message WHERE thread_id = ?;
```

```sql
-- MessageSource.sq
insertSource:
INSERT INTO message_source (
    message_id, ordinal, material_id, label, heading, chunk_index,
    retrieval_score, cited_count, supported_count, unsupported_count, unknown_count
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

```sql
-- ToolCall.sq
insertToolCall:
INSERT INTO tool_call (id, message_id, ordinal, name, args, outcome, ok, result, duration_ms, agent)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

### 6.2 Lecturas del chat

```sql
-- Message.sq
selectByThread:
SELECT * FROM message WHERE thread_id = ? ORDER BY seq ASC;

selectByThreadAfter:
SELECT * FROM message WHERE thread_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?;

selectByCourse:
SELECT * FROM message WHERE course_id = ? ORDER BY created_at ASC;

countByThread:
SELECT COUNT(*) FROM message WHERE thread_id = ?;

selectLastOfThread:
SELECT * FROM message WHERE thread_id = ? ORDER BY seq DESC LIMIT 1;
```

```sql
-- Thread.sq
selectByCourse:
SELECT * FROM thread WHERE course_id = ? ORDER BY updated_at DESC;

selectByCourseAndAuthor:
SELECT * FROM thread WHERE course_id = ? AND author_id = ? ORDER BY updated_at DESC;

selectByAuthor:
SELECT * FROM thread WHERE author_id = ? ORDER BY updated_at DESC;
```

### 6.3 Agregados de analítica

**Conteos por alumno** (el GROUP BY central; respeta la visibilidad):

```sql
-- Analytics.sq
perStudent:
SELECT
    e.user_id                 AS user_id,
    e.display_name            AS display_name,
    e.role                    AS role,
    COUNT(DISTINCT t.id)      AS threads,
    SUM(CASE WHEN m.role = 'user'      THEN 1 ELSE 0 END) AS messages,
    SUM(CASE WHEN m.role = 'assistant' THEN 1 ELSE 0 END) AS replies,
    SUM(CASE WHEN m.role = 'assistant' AND m.status = 'COMPLETED' THEN 1 ELSE 0 END) AS replies_completed,
    SUM(CASE WHEN m.role = 'assistant' AND m.status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
    SUM(CASE WHEN m.role = 'assistant' AND m.status = 'ERROR'     THEN 1 ELSE 0 END) AS errors,
    -- Días en los que el ALUMNO escribió (§1.5 de 02-metricas.md).
    COUNT(DISTINCT CASE WHEN m.role = 'user' THEN m.day_start END) AS active_days,
    MAX(m.created_at)           AS last_activity,
    SUM(CASE WHEN m.role = 'user'      THEN m.tokens_in  ELSE 0 END) AS tokens_in,
    SUM(CASE WHEN m.role = 'assistant' THEN m.tokens_out ELSE 0 END) AS tokens_out,
    -- Suma y muestras separadas: la media ponderada exacta, sin truncar.
    SUM(CASE WHEN m.role = 'assistant' AND m.status = 'COMPLETED' THEN m.latency_ms ELSE 0 END) AS latency_sum_ms,
    SUM(CASE WHEN m.role = 'assistant' AND m.status = 'COMPLETED' THEN 1 ELSE 0 END) AS latency_samples,
    SUM(m.citation_verified)   AS citations_verified,
    SUM(m.citation_unsupported) AS citations_unsupported,
    SUM(m.citation_unknown)    AS citations_unknown,
    SUM(CASE WHEN m.has_citations = 1 THEN 1 ELSE 0 END) AS answers_with_citations,
    SUM(CASE WHEN m.grounding = 'RAG'    THEN 1 ELSE 0 END) AS rag_answers,
    SUM(CASE WHEN m.grounding = 'RAG' AND m.source_count > 0 THEN 1 ELSE 0 END) AS rag_with_sources,
    SUM(CASE WHEN m.grounding = 'RAG' AND m.source_count = 0 THEN 1 ELSE 0 END) AS rag_without_sources,
    SUM(CASE WHEN m.grounding = 'GENERAL' THEN 1 ELSE 0 END) AS general_answers
FROM enrollment e
LEFT JOIN thread  t ON t.course_id = e.course_id AND t.author_id = e.user_id
LEFT JOIN message m ON m.thread_id = t.id
WHERE e.course_id = ? AND e.visibility != 'NONE'
GROUP BY e.user_id, e.display_name, e.role
ORDER BY messages DESC, e.user_id ASC;
```

**Actividad por día:**

```sql
perDay:
SELECT
    m.day_start AS day,
    COUNT(*)    AS messages,
    SUM(CASE WHEN m.role = 'user'      THEN 1 ELSE 0 END) AS user_messages,
    SUM(CASE WHEN m.role = 'assistant' THEN 1 ELSE 0 END) AS replies,
    SUM(m.citation_verified)   AS citations_verified,
    SUM(m.citation_unsupported) AS citations_unsupported,
    SUM(m.source_count)        AS sources
FROM message m
JOIN enrollment e ON e.course_id = m.course_id AND e.user_id = m.author_id
WHERE m.course_id = ? AND e.visibility != 'NONE' AND e.role = 'ALUMNO'
GROUP BY m.day_start
ORDER BY m.day_start ASC;
```

**Uso por material** (consultado / citado / respaldado):

```sql
topMaterials:
SELECT
    ms.material_id AS material_id,
    COALESCE(mt.title, ms.label) AS title,
    COUNT(*)                   AS consultas,
    SUM(ms.cited_count)        AS citadas,
    SUM(ms.supported_count)    AS respaldadas,
    SUM(ms.unsupported_count)  AS sin_respaldo,
    SUM(ms.unknown_count)      AS inexistentes
FROM message_source ms
JOIN message m    ON m.id = ms.message_id
JOIN enrollment e ON e.course_id = m.course_id AND e.user_id = m.author_id
LEFT JOIN material mt ON mt.id = ms.material_id
WHERE m.course_id = ? AND e.visibility != 'NONE' AND e.role = 'ALUMNO'
  AND m.role = 'assistant' AND m.status = 'COMPLETED'
GROUP BY ms.material_id, COALESCE(mt.title, ms.label)
ORDER BY consultas DESC, title ASC
LIMIT ?;
```

**Materiales nunca consultados:**

```sql
neverConsulted:
SELECT mt.id AS id, mt.title AS title
FROM material mt
LEFT JOIN (
    SELECT ms.material_id AS material_id, COUNT(*) AS c
    FROM message_source ms
    JOIN message m ON m.id = ms.message_id
    WHERE m.course_id = ?
    GROUP BY ms.material_id
) u ON u.material_id = mt.id
WHERE mt.course_id = ?
  AND mt.visible = 1
  AND mt.extracted_chars > 0
  AND mt.warning = ''
  AND u.c IS NULL;
```

**Histograma de latencia** (percentiles sin guardar las muestras):

```sql
latencyHistogram:
SELECT m.latency_bucket AS bucket, COUNT(*) AS samples
FROM message m
JOIN enrollment e ON e.course_id = m.course_id AND e.user_id = m.author_id
WHERE m.course_id = ? AND e.visibility != 'NONE' AND e.role = 'ALUMNO'
  AND m.role = 'assistant' AND m.status = 'COMPLETED'
GROUP BY m.latency_bucket
ORDER BY m.latency_bucket ASC;
```

> Los buckets son los de `02-metricas.md` §8.3:
> `{0,1, 0,25, 0,5, 1, 2, 4, 8, 15, 30, 60, 120, +∞}` segundos.
> `latency_bucket` se calcula **al escribir** el mensaje (no hay funciones de
> ventana disponibles en SQLite 3.18) y se interpola dentro del bucket.

**Totales de la cátedra** (los agregados de §8.1 de `02-metricas.md`, siempre
como `Σ numerador / Σ denominador`):

```sql
courseTotals:
SELECT
    COUNT(*)                   AS total_messages,
    SUM(CASE WHEN m.role = 'user'      THEN 1 ELSE 0 END) AS user_messages,
    SUM(CASE WHEN m.role = 'assistant' THEN 1 ELSE 0 END) AS replies,
    SUM(CASE WHEN m.status = 'ERROR'     THEN 1 ELSE 0 END) AS errors,
    SUM(CASE WHEN m.status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
    SUM(CASE WHEN m.role='assistant' AND m.status='COMPLETED' THEN m.latency_ms ELSE 0 END) AS latency_sum_ms,
    SUM(CASE WHEN m.role='assistant' AND m.status='COMPLETED' THEN 1 ELSE 0 END) AS latency_samples,
    SUM(m.citation_verified)   AS citations_verified,
    SUM(m.citation_unsupported) AS citations_unsupported,
    SUM(m.citation_unknown)    AS citations_unknown,
    SUM(CASE WHEN m.has_citations = 1 THEN 1 ELSE 0 END) AS answers_with_citations,
    SUM(CASE WHEN m.grounding='RAG' THEN 1 ELSE 0 END) AS rag_answers,
    SUM(CASE WHEN m.grounding='RAG' AND m.source_count>0 THEN 1 ELSE 0 END) AS rag_with_sources,
    SUM(m.source_count)        AS total_sources,
    SUM(m.tokens_in)           AS tokens_in,
    SUM(m.tokens_out)          AS tokens_out
FROM message m
JOIN enrollment e ON e.course_id = m.course_id AND e.user_id = m.author_id
WHERE m.course_id = ? AND e.visibility != 'NONE' AND e.role = 'ALUMNO';
```

**Herramientas:**

```sql
topTools:
SELECT
    tc.name AS name,
    COUNT(*) AS calls,
    SUM(CASE WHEN tc.ok = 0 THEN 1 ELSE 0 END)    AS failures,
    SUM(CASE WHEN tc.ok IS NULL THEN 1 ELSE 0 END) AS incomplete
FROM tool_call tc
JOIN message m    ON m.id = tc.message_id
JOIN enrollment e ON e.course_id = m.course_id AND e.user_id = m.author_id
WHERE m.course_id = ? AND e.visibility != 'NONE' AND e.role = 'ALUMNO'
GROUP BY tc.name
ORDER BY calls DESC, name ASC
LIMIT ?;
```

### 6.4 Lo que **no** se puede pedir en SQL y se calcula en Kotlin

| Cálculo | Por qué no es SQL | Dónde |
|---|---|---|
| **Sesiones** (hueco > 30 min) | Requiere `LAG()` (funciones de ventana: SQLite 3.25, no disponible en `minSdk 24`). | La consulta devuelve los `created_at` ordenados por alumno y la app arma los grupos. |
| **Tasa de repetición** (Jaccard sobre texto normalizado) | Es una comparación texto a texto. | `kitoru.text.normalizeForCompare` + un conjunto de tokens; se hace sobre el conjunto de mensajes del alumno, en memoria. |
| **Ratios** (`CSR`, `USR`, `NSC`, `hitRate`, `TFR`) | No son `GROUP BY`: son divisiones de sumas ya calculadas. | La app divide `Σ num / Σ den` con la convención de denominador 0 → `null` de `02-metricas.md` §0.4. |
| **`Double?` con «sin datos»** | SQL devuelve `0`, no `null` para una razón. | La app decide: `den > 0 ? num/den : null`. |

> **Regla**: SQL cuenta; Kotlin decide y etiqueta. La base nunca calcula
> una razón.

### 6.5 El SQL de este documento ya fue validado

Antes de escribir este documento, **el esquema y las consultas de §5 y §6 se
ejecutaron contra un motor SQLite real** (extrayendo los bloques `sql` del
propio Markdown y corriendo un banco de pruebas con 4 hilos, 24 mensajes,
fuentes, tool calls y cuatro inscripciones con visibilidades distintas). El
resultado:

| Comprobación | Resultado |
|---|---|
| Las 37 sentencias DDL (`CREATE TABLE` + `CREATE INDEX`) | ✅ se crean sin error |
| La FK compuesta de `message` | ✅ registrada con `ON DELETE CASCADE` en las tres columnas |
| `INSERT` con `course_id` distinto al del hilo | ✅ **rechazado** por la FK |
| `INSERT` con `author_id` distinto al del hilo | ✅ **rechazado** por la FK |
| `DELETE FROM thread` | ✅ borra mensajes, fuentes y tool calls (cascada) |
| `DELETE FROM course` | ✅ borra inscripciones, hilos, mensajes, fuentes, tool calls y schedule |
| `perStudent` con un alumno en `visibility = NONE` | ✅ **no aparece** en el agregado |
| `neverConsulted` | ✅ devuelve sólo el material sin consultas |
| `perDay`, `topMaterials`, `latencyHistogram`, `courseTotals`, `topTools` | ✅ devuelven filas coherentes con la siembra |
| Los `INSERT` de §6.1 | ✅ número de columnas = número de parámetros |

> Es una comprobación puntual, no una suite: sirve para que el SQL de este
> documento no tenga errores de sintaxis ni de semántica. La cobertura real
> es la del §15.

### 6.6 Paridad con `AnalyticsAnalyzer`

`AnalyticsAnalyzer.analyze(...)` (`core/.../domain/AnalyticsAnalyzer.kt`) se
**conserva** como especificación ejecutable de la analítica. Se agrega un test
de paridad:

```kotlin
// storage/src/commonTest/kotlin/kitoru/storage/AnalyticsParityTest.kt
@Test fun `la analitica SQL coincide con la funcion pura`() {
    val seed = SeedData.courseWith(alumnos = 3, mensajesPorAlumno = 40, conCitas = true)
    val db = seed.toDatabase()

    // 1. La función pura, sobre las mismas estructuras de dominio.
    val esperado = AnalyticsAnalyzer.analyze(
        course = seed.course,
        enrollments = seed.enrollments,
        threads = seed.threads,
        messages = seed.messages,
    )

    // 2. Las consultas SQL, sobre la misma base.
    val sql = SqlAnalyticsRepository(db).courseAnalytics(seed.course.id)

    assertEquals(esperado.totalMessages, sql.totalMessages)
    assertEquals(esperado.perStudent.size, sql.perStudent.size)
    // ... y todos los contadores de §8.1 de 02-metricas.md
}
```

El test **falla** si una consulta SQL y la función pura divergen. Es el
mecanismo que garantiza que el cambio de almacenamiento no cambia un solo
número de la pantalla del docente.

> Nota: `AnalyticsAnalyzer` hoy tiene 12 defectos documentados en
> `02-metricas.md` §0.7 (día en UTC, `topSources` sin filtro de visibilidad,
> `citationSupportRate = 0.0` con total 0, …). El test de paridad **debe**
> escribir los valores esperados de la especificación, no los del analizador
> actual: es decir, la paridad se verifica **después** de arreglar el
> analizador, o el test se escribe con los números de `02-metricas.md` como oro.

---

## 7. Migraciones

### 7.1 Versionado

- **Versión del esquema**: la que SQLDelight compila en `KitoruDatabase.Schema.version`
  (número de `.sqm` + 1).
- **Versión en disco**: `PRAGMA user_version`. SQLite la escribe y la lee
  sola; no se usa ninguna tabla propia para esto.
- **Versión del formato de exportación**: el campo `version` del `manifest.json`
  (distinto del de la base: uno es el esquema local, el otro el del archivo).

### 7.2 SQLDelight: archivos `.sqm`

```
storage/src/commonMain/sqldelight/kitoru/storage/db/
├── 1.sqm        -- de la versión 1 a la 2
└── 2.sqm        -- de la versión 2 a la 3
```

Ejemplo **ilustrativo** de `1.sqm` (de la versión 1 a la 2). Supone que el
esquema v1 ya tenía `message` pero **no** las columnas `status` ni
`grounding` (que es exactamente el salto que hoy pide `02-metricas.md` §9.1):

```sql
-- 1.sqm · añade el estado del turno y el modo de fundamentación
ALTER TABLE message ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE message ADD COLUMN grounding TEXT NOT NULL DEFAULT 'GENERAL';
```

Una migración también puede contener **datos**, no sólo DDL. Por ejemplo,
para recalcular el `day_start` de los mensajes existentes cuando cambia la
zona horaria de una cátedra:

```sql
-- 2.sqm · (ejemplo) rellena la columna nueva a partir de la vieja
UPDATE message SET day_start = (created_at / 86400000) * 86400000
WHERE day_start = 0 AND created_at > 0;
```

> Las migraciones **de datos** que dependen de la zona horaria de la cátedra
> no se pueden expresar en SQL (SQLite no conoce la zona del curso): las hace
> la aplicación, dentro de una transacción, antes de la migración de DDL
> (ver el riesgo 2 de §16).

**Reglas para los `.sqm`** (por el dialecto 3.18, D13):

| Se puede | No se puede |
|---|---|
| `ALTER TABLE … ADD COLUMN` | `ALTER TABLE … RENAME COLUMN` (SQLite 3.25) |
| `CREATE TABLE`, `CREATE INDEX`, `DROP INDEX` | `UPSERT` / `ON CONFLICT DO UPDATE` (3.24) |
| `DROP TABLE` + `CREATE TABLE` + `INSERT … SELECT` (el patrón "recrear") | funciones de ventana (3.25) |
| `UPDATE`, `DELETE`, `INSERT` | `ALTER TABLE … DROP COLUMN` (3.35) |
| | columnas generadas (3.31) |

> Si alguna vez hace falta `RENAME COLUMN` o funciones de ventana, la salida
> es **subir `minSdk` a 27** (SQLite 3.25) o usar el
> `BundledSQLiteDriver` de `androidx.sqlite` (SQLite 3.45, +~1 MB por APK).
> Se deja anotado como decisión pendiente, no se aplica hoy.

### 7.3 Verificación en CI

```kotlin
sqldelight {
    databases {
        create("KitoruDatabase") {
            packageName.set("kitoru.storage.db")
            dialect(libs.sqldelight.dialect)
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
```

Con `schemaOutputDirectory` + `verifyMigrations = true`, el plugin:

1. genera un `.db` por versión del esquema en `src/commonMain/sqldelight/databases/`;
2. la tarea **`verifySqlDelightMigration`** aplica cada `.sqm` sobre el `.db`
   de la versión anterior y compara el resultado con el `.db` de la versión
   actual.

Si alguien escribe un `.sqm` que no produce el esquema esperado, **el build
falla en CI antes de llegar a la máquina de un usuario**. Ése es el valor
principal de SQLDelight sobre un `schemaVersion` en un JSON.

```bash
./gradlew :storage:verifySqlDelightMigration   # en CI
```

### 7.4 Qué pasa si el usuario tiene una base vieja

El arranque hace, en este orden:

```kotlin
object KitoruStorage {
    fun open(fs: FileSystem, dbFile: FilePath): KitoruDatabase {
        val schema = KitoruDatabase.Schema
        val current = schema.version

        // 1. No hay base → se crea.
        if (!fs.exists(dbFile)) return create(fs, dbFile, schema)

        // 2. Base de una versión FUTURA → no se toca.
        val userVersion = readUserVersion(dbFile)
        if (userVersion > current) {
            throw KitoruStorageException(
                "Esta base de datos fue creada por una versión más nueva de Kitoru " +
                "(esquema $userVersion, esta versión entiende hasta $current). " +
                "Actualizá Kitoru para abrirla."
            )
        }

        // 3. Base vieja → respaldo y migración.
        if (userVersion < current) {
            backup(fs, dbFile, userVersion)          // kitoru.db.bak-v<userVersion>
            migrate(fs, dbFile, schema, userVersion) // Schema.migrate(...)
        }

        // 4. Base corrupta → no se pisa nunca.
        //    (la comprobación es integrity_check; si falla, se renombra)
        return create(fs, dbFile, schema)
    }
}
```

Reglas:

| Situación | Acción | Mensaje al usuario |
|---|---|---|
| No existe `kitoru.db` | `Schema.create` | — |
| `user_version < actual` | **backup** `kitoru.db.bak-v<N>` + `Schema.migrate` | «La base de datos se actualizó de la versión N a la M.» |
| `user_version > actual` | **no se toca** | «Esta base de datos fue creada por una versión más nueva de Kitoru. Actualizá la aplicación.» |
| `integrity_check` falla | se renombra a `kitoru.db.corrupto-<ts>` y se crea una nueva | «La base de datos estaba dañada. Se conservó una copia en `<ruta>`.» |
| Falla una migración | se restaura el backup y se aborta el arranque | «No se pudo actualizar la base de datos. Se restauró la copia anterior.» |

**El backup previo a cada migración** es barato (el `.db` es ≤ 400 MB y sólo
se copia una vez por versión) y convierte una migración con error de
escritura en un incidente en vez de en una pérdida.

### 7.5 Migración desde el JSON actual

Kitoru es **nascente**: no hay datos reales que migrar. Por eso la decisión
es libre y se toma ahora:

- **La v1 de Kitoru no incluye el store JSON.** `FileMessageRepository`,
  `FileThreadRepository`, `FileCourseRepository`, `FileEnrollmentRepository`,
  `FileMaterialRepository` y `FileProfileRepository` **se borran** y se
  reemplazan por las implementaciones `Sql*`. `JsonStore` y `JsonListStore`
  se quedan **sólo** para lo que sigue siendo un archivo (ajustes, blobs).
- **Aun así**, se incluye un **importador del JSON de prototipo**, para los
  desarrolladores que ya tienen datos locales y para el caso de que un
  usuario de una versión de prueba pida migrar:

  ```kotlin
  // storage/src/commonMain/kotlin/kitoru/storage/LegacyJsonImporter.kt
  /**
   * Importa el JSON del prototipo (`config/*.json`) a la base.
   *
   * Es idempotente: si `kitoru_meta['legacy_json_imported']` está puesto, no
   * hace nada. Los archivos originales **no se borran**: se mueven a
   * `config/legacy/<timestamp>/`.
   */
  class LegacyJsonImporter(
      private val db: KitoruDatabase,
      private val fs: FileSystem,
      private val configDir: FilePath,
  ) {
      suspend fun importIfNeeded(): LegacyImportReport {
          if (db.kitoruMetaQueries.get("legacy_json_imported") != null) {
              return LegacyImportReport(alreadyImported = true)
          }
          val files = listOf("courses", "enrollments", "materials", "threads", "messages", "profiles")
              .map { configDir.resolve("$it.json") }
              .filter { fs.exists(it) }
          if (files.isEmpty()) return LegacyImportReport(nothingToDo = true)

          // TODO: una sola transacción para todo el import.
          db.transaction {
              files.forEach { file -> importFile(file) }
              db.kitoruMetaQueries.insert("legacy_json_imported", nowMillis().toString())
          }
          // Los originales se conservan como respaldo.
          moveToLegacy(files)
          return LegacyImportReport(imported = files.size)
      }
  }
  ```

  | Campo JSON | Columna | Nota |
  |---|---|---|
  | `ChatMessage.id` | `message.id` | |
  | `ChatMessage.threadId` | `message.thread_id` | `course_id` y `author_id` se resuelven del hilo |
  | `ChatMessage.createdAt` | `message.created_at` + `day_start` | `day_start` se calcula con la zona de la cátedra |
  | `ChatMessage.sources` | filas en `message_source` | `material_id` se resuelve por `label`; si no, `NULL` |
  | `ChatMessage.citation` | `citation_*` | |
  | `ChatMessage.toolCalls` | filas en `tool_call` | |
  | `Material.extractedText` | **no se importa a la base** | se escribe a `courses/<id>/corpus/<materialId>.txt` (§9) |
  | `Course.modelPolicy.apiKey` | **no se importa** | se ignora con un aviso: la clave va al `SecureStore` (§10) |

### 7.6 Importar el `chats.json` de FunshiEngineAI

El formato que Kitoru reemplaza (`FunshiEngineAI/backend/.../rag/store/ChatStore.kt`)
es un **árbol** de `StoredNode` (`StoredNode(id, folder, name, createdAt,
messages, children, grantedCats)`) con `StoredMessage(role, text, sources,
toolCards, attachments, citation)`.

Mapeo:

| FunshiEngineAI | Kitoru |
|---|---|
| nodo hoja (`folder = false`) | un `thread` |
| `children` (carpetas) | **no se importa**: la carpeta es una organización del árbol, Kitoru tiene `tags` |
| `StoredMessage.role` (`"user"`/`"assistant"`) | `message.role` |
| `StoredMessage.text` | `message.text` |
| `StoredMessage.sources` (`List<String>` de etiquetas) | filas en `message_source` con `material_id = NULL` y `label` = la etiqueta |
| `StoredMessage.citation` (`verified/unsupported/unknown`) | `citation_verified/unsupported/unknown` |
| `StoredMessage.toolCards` | filas en `tool_call` (`name`, `args`, `ok`, `result`) |
| `StoredMessage.attachments` | **se pierden**: no hay campo en el modelo de dominio (los archivos generados viven en `cache/` y no se referencian) |
| `StoredNode.createdAt` | `thread.created_at` y **también** `message.created_at` de todos sus mensajes |
| — | `message.createdAt` **no existe** en FunshiEngineAI ⇒ se sintetiza: `node.createdAt + índice × 1000` ms. **Los datos importados tienen fechas sintéticas** y hay que decirlo en la UI de importación ("importado sin marcas de tiempo exactas"). |

`StoredMessage` no tiene `tokens` ni `latencyMs`: quedan en 0 y las métricas
de recursos de esos chats son **cero real, no estimadas** (el `tokens_estimated`
se pone en 0 para no contaminar los agregados de costo).

> El importador es **opcional** y de bajo valor (FunshiEngineAI es una app
> personal, no un producto con datos de usuarios). Se anota como **fuera de
> alcance de la v1** y queda el mapeo documentado por si hace falta.

---

## 8. Escrituras atómicas y concurrentes

### 8.1 El patrón «escribir a temporal y renombrar»

Sigue siendo necesario, pero **sólo para los archivos que no son la base**:

- `config/settings.json`
- `logs/kitoru.log` (append, no rename)
- el blob del almacén seguro (fallback de escritorio)
- el `.kitoru` de texto plano (formato legado)
- los archivos del corpus (`<materialId>.txt`, `.chunks.json`)

El patrón, tal como lo define `04-conversaciones.md` §5.6 y como **se adopta
sin cambios**:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/AtomicFiles.kt
object AtomicFiles {
    const val TMP_SUFFIX = ".tmp"

    /** Escribe [text] en [path] de forma atómica. */
    fun writeText(fs: FileSystem, path: FilePath, text: String) {
        val dir = path.parent
        if (dir != null) fs.createDirectories(dir)
        val tmp = path.resolve(path.name + TMP_SUFFIX)
        try {
            fs.writeText(tmp, text)
            fs.sync(tmp)            // a disco antes de renombrar
            fs.move(tmp, path)      // atómico dentro del mismo volumen
        } catch (e: Exception) {
            runCatching { fs.delete(tmp) }
            throw e
        }
    }

    /** Al arrancar: recupera temporales de escrituras interrumpidas. */
    fun recover(fs: FileSystem, dir: FilePath) { /* promover o borrar, según mtime */ }
}
```

**Extensión de `FileSystem` que hace falta** (D16): `move`, `sync`,
`lastModifiedMillis` (ya pedidos por `04-conversaciones.md`) **más**:

```kotlin
interface FileSystem {
    // ... lo actual ...
    fun move(from: FilePath, to: FilePath)
    fun sync(path: FilePath) {}
    fun lastModifiedMillis(path: FilePath): Long = 0L

    // NUEVOS: para el almacén seguro y para el cálculo de hashes.
    fun readBytes(path: FilePath): ByteArray
    fun writeBytes(path: FilePath, bytes: ByteArray)
    /** Permisos 0700/0600 para el usuario actual. No-op en Windows. */
    fun restrictToOwner(path: FilePath) {}
}
```

**Sobre `move` en Windows**: `Files.move(src, dst, ATOMIC_MOVE, REPLACE_EXISTING)`
es atómico en NTFS, pero la especificación de Java dice que con `ATOMIC_MOVE`
las demás opciones se ignoran, y en algunas versiones el destino existente
provoca `FileAlreadyExistsException`. La implementación JVM debe:

```kotlin
override fun move(from: FilePath, to: FilePath) {
    val src = File(from.value)
    val dst = File(to.value)
    dst.parentFile?.mkdirs()
    try {
        Files.move(src.toPath(), dst.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return
    } catch (_: AtomicMoveNotSupportedException) {
        // Caída al rename no atómico.
    } catch (_: FileAlreadyExistsException) {
        // Windows + ATOMIC_MOVE ignora REPLACE_EXISTING: reintento.
    }
    Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
}
```

### 8.2 Transacciones en SQLite

Cada operación de escritura es **una transacción**:

```kotlin
// storage/src/commonMain/kotlin/kitoru/storage/SqlMessageRepository.kt
class SqlMessageRepository(
    private val db: KitoruDatabase,
    private val writeLock: Mutex,          // un mutex por proceso (§8.4)
) : MessageRepository {

    override suspend fun save(message: ChatMessage) {
        writeLock.withLock {
            db.transaction {
                // 1. La secuencia, dentro de la misma transacción.
                val seq = db.messageQueries.nextSeq(message.threadId).executeAsOne()

                // 2. El mensaje.
                db.messageQueries.insertMessage(
                    id = message.id,
                    thread_id = message.threadId,
                    course_id = thread.courseId,
                    author_id = thread.authorId,
                    seq = seq,
                    /* ... */ status = message.status.name,
                    grounding = message.grounding.name,
                    day_start = dayStart(message.createdAt, course.timeZone),
                    /* ... */
                )

                // 3. Las fuentes.
                message.sources.forEachIndexed { i, src ->
                    db.messageSourceQueries.insertSource(
                        message_id = message.id,
                        ordinal = i + 1,
                        material_id = src.materialId,
                        label = src.label,
                        /* ... */
                    )
                }

                // 4. Las tool calls.
                message.toolCalls.forEachIndexed { i, tc ->
                    db.toolCallQueries.insertToolCall(/* ... */)
                }

                // 5. El hilo se actualiza (updatedAt).
                db.threadQueries.updateThreadTimestamp(nowMillis(), message.threadId)
            }
        }
    }
}
```

Qué compra la transacción:

- **Atomicidad**: o se guardan el mensaje, sus fuentes y sus tool calls, o no
  se guarda nada. No hay mensajes sin fuentes.
- **Consistencia de `seq`**: `nextSeq` + `insert` dentro de la misma
  transacción ⇒ no hay dos mensajes con el mismo `seq` (y el índice único lo
  garantiza igual).
- **Rollback**: si el `INSERT` de una tool call falla, no queda el mensaje
  huérfano.

### 8.3 Concurrencia

| Aspecto | Configuración | Por qué |
|---|---|---|
| Journal | `PRAGMA journal_mode = WAL` | Lectores concurrentes con un escritor. Sin WAL, un lector bloquea al escritor y viceversa: la UI se congela. |
| Durabilidad | `PRAGMA synchronous = NORMAL` | Con WAL, `NORMAL` es seguro frente a caídas de la app (puede perder la última transacción ante una caída de **hardware**, no ante un cierre de proceso) y es varias veces más rápido que `FULL`. |
| Espera | `PRAGMA busy_timeout = 5000` | En vez de «database is locked», se espera 5 s. |
| Claves foráneas | `PRAGMA foreign_keys = ON` | Sin esto, **no hay cascadas** (§5.12). |
| Escritores | `Mutex` por proceso | SQLite serializa los escritores igual, pero el `Mutex` convierte un `SQLITE_BUSY` en una espera ordenada y evita que dos corrutinas del mismo proceso se pisen en el cálculo de `seq`. |

**Escenario real de concurrencia**: dos procesos (la app abierta y una
herramienta de diagnóstico, o dos instancias de la app):

- Con WAL + `busy_timeout` + `PRAGMA foreign_keys`, los dos pueden leer y
  escribir. SQLite garantiza la serialización de los escritores.
- El `Mutex` de Kotlin **no** protege entre procesos: por eso el `busy_timeout`
  y el WAL son obligatorios, y no opcionales.
- El store de archivos, en cambio, no tenía ninguna de estas garantías: dos
  procesos con el `Mutex` de `JsonStore` (por proceso) se pisaban y el
  resultado era un `messages.json` con líneas mezcladas o truncado.

### 8.4 Escrituras observables y la UI

```kotlin
// :ui — la pantalla del hilo
val messages by remember(threadId) {
    db.messageQueries.selectByThread(threadId)
        .asFlow(ioDispatcher)          // app.cash.sqldelight:coroutines-extensions
        .mapToList(ioDispatcher)
}.collectAsState(initial = emptyList())
```

Cada `INSERT` dispara la notificación de SQLDelight ⇒ el `Flow` re-emite ⇒ la
UI se actualiza. **No hay invalidación manual**, no hay `StateFlow` que
invalidar, no hay `refresh()`.

> Recordatorio de §4.4: la notificación sólo se dispara si la escritura pasa
> por las `Queries` generadas.

---

## 9. Caché del corpus

### 9.1 La decisión

**El corpus no vive en la base.** Vive en disco, indexado en memoria.

```
courses/<courseId>/
├── materials/
│   ├── <materialId>__programa-parcial-1.pdf      ← el original que subió el docente
│   └── ...
└── corpus/                                        ← derivado, se puede borrar
    ├── <materialId>.txt                           ← texto extraído
    └── <materialId>.chunks.json                   ← fragmentos con su heading
```

La base guarda **sólo los metadatos**: `path`, `size_bytes`, `extracted_chars`,
`content_hash`, `extractor_version`, `indexed_at`, `chunks_indexed`,
`warning`.

### 9.2 Por qué en disco y no en la base

| Razón | Detalle |
|---|---|
| **Es dato derivado** | El texto sale del original. Se regenera con el extractor; no necesita estar en la base. |
| **Es grande** | 50 PDF × 300 KB de texto = 15 MB por cátedra, 1,5 GB en una facultad. En la base infla el `.db`, cada `VACUUM` y cada copia de seguridad. |
| **Es transferible** | El corpus es lo que el alumno necesita para que el RAG funcione contra «el material que subió la cátedra» (`CONTEXTO.md` §2). Viaja como carpeta, no como filas. |
| **El índice es en memoria igual** | `Bm25Index` (`:backend`, ya portado) vive en RAM. Guardarlo en la base no evita nada: se carga igual al arrancar. |
| **Se regenera con una versión** | `extractor_version` + `content_hash` permiten saber si el texto guardado corresponde al original actual. Si no, se re-extrae. |

### 9.3 El índice en memoria

`KnowledgeBase.sync(...)` ya arma el `Bm25Index` a partir de los `Doc` del
corpus. El arranque de la app hace:

```
1. Por cada cátedra con materiales visibles:
   a. Por cada material: leer courses/<id>/corpus/<materialId>.txt
   b. Si content_hash/extractor_version no coinciden → re-extraer del original
   c. Chunker.split(doc, chunkSize, overlap) → los fragmentos
   d. Bm25Index.add(chunks)
2. La KnowledgeBase queda lista en RAM.
```

Coste típico: 50 materiales × 300 KB = 15 MB de texto ⇒ troceado + indexado
en **~1–3 s**. Es el mismo trabajo que ya hace hoy el `sync` del backend; no
es un costo nuevo.

**Caché opcional en disco**: `courses/<id>/corpus/index-v1.bin` con el índice
BM25 serializado, para saltarse los 1–3 s del arranque. **Es una caché**: si
el archivo no está, se reconstruye; si está corrupto, se borra y se
reconstruye. No es fuente de verdad.

### 9.4 ¿Y el texto extraído del material que el alumno no tiene?

El alumno recibe la cátedra (materiales + corpus) por el `.kitoru` o por
Kitoru-Server. Si no tiene el material, el RAG corre «a ciegas» y el chat
avisa: «No hay material de esta cátedra en tu dispositivo».

---

## 10. Cifrado y credenciales

### 10.1 El seam

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/SecureStore.kt
package kitoru.platform

/**
 * Almacén de credenciales: API keys, tokens de servidor.
 *
 * **Nunca** guarda datos de negocio. Es la única pieza que sabe cómo
 * protege el sistema operativo lo que se le guarda.
 */
interface SecureStore {

    /** Qué tan fuerte es la protección real. La UI lo muestra. */
    val protection: SecureStoreProtection

    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun clear()
}

/** Nivel de protección efectivo del almacén. */
enum class SecureStoreProtection(val label: String) {
    /** Android Keystore (hardware-backed si el dispositivo lo tiene). */
    OS_KEYSTORE("Protegido por el almacén de claves del sistema"),
    /** macOS Keychain / Windows DPAPI. */
    OS_KEYCHAIN("Protegido por el almacén de credenciales del sistema"),
    /** Archivo local con permisos restringidos: obscurity, no cifrado real. */
    FILE_PERMISSIONS("Protegido por permisos de archivo (protección débil)"),
    /** Sin protección (no debería ocurrir). */
    NONE("Sin protección"),
}

/** El almacén seguro de la plataforma. */
expect fun secureStore(): SecureStore
```

### 10.2 Android

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/SecureStore.android.kt
actual fun secureStore(): SecureStore = AndroidSecureStore(context)

/**
 * Keystore de Android (AES-256-GCM) + SharedPreferences.
 *
 * Se usa la API del Keystore directamente y NO `EncryptedSharedPreferences`:
 * `androidx.security:security-crypto` está deprecado y su manejo de claves
 * tiene problemas conocidos. La clave no sale del Keystore; sólo el
 * ciphertext (Base64) va al prefs.
 */
private class AndroidSecureStore(private val context: Context) : SecureStore {
    private val prefs get() = context.getSharedPreferences("kitoru_secure", Context.MODE_PRIVATE)

    override val protection = SecureStoreProtection.OS_KEYSTORE

    override suspend fun get(key: String): String? {
        val b64 = prefs.getString(key, null) ?: return null
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secret = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val iv = Base64.decode(prefs.getString("$key.iv", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(Base64.decode(b64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()      // clave no descifrable (cambio de lockscreen) → null
    }

    override suspend fun put(key: String, value: String) {
        val secret = obtainKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secret)
        }
        prefs.edit()
            .putString(key, Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            .putString("$key.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }
}
```

> `minSdk = 24`: `GCMParameterSpec` (API 21) y AES/GCM en el Keystore (API 23)
> están disponibles. ✔

### 10.3 Escritorio

**Primario en Windows — DPAPI vía JNA** (`com.sun.jna:jna:5.x` +
`jna-platform`):

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/SecureStore.windows.kt
private object WindowsDpapi {
    // CryptProtectData / CryptUnprotectData con CRYPTPROTECT_UI_FORBIDDEN.
    // La clave queda atada al usuario de Windows: otro usuario del mismo
    // equipo no puede descifrarla.
    fun protect(plain: ByteArray, entropy: ByteArray): ByteArray
    fun unprotect(cipher: ByteArray, entropy: ByteArray): ByteArray
}
```

**Primario en macOS — Keychain** vía JNA (`SecKeychainAddGenericPassword` /
`SecKeychainFindGenericPassword`).

**Primario en Linux — libsecret** vía JNA si está disponible
(`secret_password_lookup` / `secret_password_store`).

**Fallback universal** (Linux sin libsecret, o si el almacén del sistema falla):

```
config/secure.bin      # AES-256-GCM
config/secure.key      # la clave de 32 bytes, en un archivo 0600
```

La clave se genera con `SecureRandom` al primer uso. Esto **no es cifrado
real**: cualquiera con permisos de lectura del directorio puede descifrarlo.
Por eso:

- el archivo y la clave se crean con permisos **0600** (`restrictToOwner`);
- el `SecureStoreProtection` devuelto es `FILE_PERMISSIONS`, y **la UI lo
  dice**: un aviso persistente en «Ajustes → Modelo»:
  > «En este sistema la clave de API se guarda en un archivo local protegido
  > por permisos. No es tan seguro como el almacén del sistema operativo.»

**Por qué no cifrar la base** (D15) y qué sí se hace:

| Pregunta | Respuesta |
|---|---|
| ¿Qué hay en la base que sea secreto? | **Nada.** La API key sale (D5). Los chats son datos de negocio, visibles para el docente **por diseño**. |
| ¿Cuál es la amenaza? | Alguien con acceso a la cuenta del usuario o al disco. Si tiene eso, también tiene las sesiones del navegador y los archivos del usuario. |
| ¿Qué daría cifrar la base? | SQLCipher (`net.zetetic:sqlcipher-android` + `sqlcipher-jni`): dos binarios nativos más, la clave de la base tendría que vivir en el mismo `SecureStore` (que en el fallback es un archivo 0600 → protección circular), y el `.kitoru` tendría que descifrar para exportar. |
| ¿Qué costaría? | +3–5 MB por plataforma, una capa de driver más, y `VACUUM`/`backup` más complicados. |
| **Mitigaciones reales** | Carpeta de datos con permisos 0700; la base **no** tiene credenciales; la app ofrece «borrar todos los datos locales»; y se documenta que, en una máquina compartida, lo correcto es **BitLocker / LUKS**, no cifrar la base de la app. |

**Decisión final**: la base va en claro; **las credenciales no**. Si más
adelante el requisito cambia (p. ej. una instalación «gestionada por la
institución»), el punto de extensión es `SecureStore` + un driver SQLCipher
**alternativo**, no una reescritura del esquema.

### 10.4 La API key en el modelo de dominio

`ModelPolicy.apiKey` sigue existiendo en el dominio (la UI y el gateway la
necesitan), pero **nunca se persiste**:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt
@Serializable
data class ModelPolicy(
    /* ... */
    /**
     * Clave de API. **Vive sólo en memoria**: los repositorios la borran
     * antes de serializar y la rehidratan desde el `SecureStore`.
     */
    val apiKey: String = "",

    /**
     * Dónde está la clave guardada de verdad.
     * Formato: `course:<id>`, `user:<id>`, `thread:<id>` o `local`.
     */
    val apiKeyRef: String = "",
    /* ... */
) {
    /** Copia sin la clave: es lo que se persiste y lo que se exporta. */
    fun redacted(): ModelPolicy = copy(apiKey = "", apiKeyRef = apiKeyRef)
}
```

Los *mappers* de `:storage` hacen:

```kotlin
fun ModelPolicy.toJson(): String = redacted().let { json.encodeToString(it) }

fun ModelPolicy.rehydrate(secure: SecureStore): ModelPolicy =
    if (apiKeyRef.isBlank()) this
    else copy(apiKey = secure.get("apiKey:$apiKeyRef").orEmpty())
```

**Test de no-fuga** (obligatorio):

```kotlin
@Test fun `la clave de API no aparece en el archivo de la base`() {
    val secret = "sk-kitoru-1234567890"
    // … sembrar una cátedra con apiKey = secret …
    val bytes = dbFile.readBytes()
    assertFalse(String(bytes, Charsets.ISO_8859_1).contains(secret))
    // Y tampoco en el .kitoru exportado.
    assertFalse(bundleBytes.contains(secretAsBytes))
}
```

---

## 11. Empaquetado y estructura de carpetas

### 11.1 Qué va en el instalador

| Componente | Escritorio | Android |
|---|---|---|
| La app (bytecode + Kotlin stdlib) | ✔ | ✔ |
| Skiko (nativos de Compose Desktop) | ✔ (~40 MB) | ✔ (via Compose Android) |
| `sqlite-jdbc` con los natives de SQLite (Win/Linux/mac) | ✔ (~12 MB) | — (se usa el SQLite del framework) |
| `android-driver` de SQLDelight | — | ✔ (~200 KB, sin binario extra) |
| `jna` + `jna-platform` (sólo para DPAPI/Keychain) | ✔ (~2 MB) | — |
| **La base de datos** | ❌ | ❌ |
| **Los materiales y el corpus** | ❌ | ❌ |
| **Los modelos `.gguf`** | ❌ | ❌ |

> **Nunca se empaqueta una base pre-sembrada**: en Windows el directorio de
> instalación es de sólo lectura, y una base con `user_version` fijo
> obligaría a migrar en el primer arranque de cada instalación. La base se
> crea en el primer arranque.

### 11.2 Qué se crea en el primer arranque

```
1. Se crea el árbol de carpetas (§11.3).
2. Se crea kitoru.db con Schema.create.
3. Se ejecuta PRAGMA foreign_keys=ON, journal_mode=WAL, synchronous=NORMAL,
   busy_timeout=5000.
4. Se escribe config/settings.json con los valores por defecto (AtomicFiles).
5. Se crea logs/kitoru.log.
6. Se registra en kitoru_meta: schema_version, app_version_written.
```

### 11.3 Estructura de carpetas

```
<dataDir>/                              ~/.kitoru (desktop) · filesDir/kitoru (Android)
├── kitoru.db                           ← la base SQLite
├── kitoru.db-wal                       ← WAL (no tocar)
├── kitoru.db-shm                       ← índice compartido (no tocar)
├── kitoru.db.bak-v<N>                  ← respaldo previo a una migración
├── kitoru.db.corrupto-<ts>             ← base que no se pudo abrir
├── config/
│   ├── settings.json                   ← AppSettings (sin secretos)
│   └── secure.bin                      ← sólo en el fallback de escritorio (0600)
├── courses/
│   └── <courseId>/
│       ├── materials/
│       │   └── <materialId>__<slug>.<ext>      ← originales del docente
│       └── corpus/
│           ├── <materialId>.txt                ← texto extraído (derivado)
│           ├── <materialId>.chunks.json        ← fragmentos (derivado)
│           └── index-v1.bin                   ← caché del BM25 (opcional)
├── models/                             ← .gguf locales del alumno
├── logs/
│   └── kitoru.log
├── cache/                              ← temporales, descargas, índices descartables
├── exports/
│   └── <courseCode>-<author>-<yyyy-MM-dd>.kitoru
└── legacy/                             ← JSON viejo importado (respaldo)
```

**Lo que desaparece respecto de `04-conversaciones.md` §5.1**: el árbol
`courses/<id>/chats/<authorId>/<threadId>/` con `thread.json`,
`messages.jsonl` y `stats.json`, y el `aggregate.json`. Ahora son filas.

**Lo que aparece**: `kitoru.db*`, `models/`, `exports/`, `legacy/`.

### 11.4 Permisos

| Carpeta | Unix | Windows |
|---|---|---|
| `<dataDir>` | 0700 | `%USERPROFILE%` (heredado) |
| `<dataDir>/config/secure.bin` y `.key` | 0600 |_dpapi_ o 0600 best-effort |
| resto | 0755 | — |

`KitoruPaths` se extiende:

```kotlin
object KitoruPaths {
    var dataDir: FilePath = FilePath(".")
        private set
    fun setDataDir(path: FilePath) { dataDir = path }

    val configDir: FilePath   get() = dataDir.resolve("config")
    val coursesDir: FilePath  get() = dataDir.resolve("courses")
    val cacheDir: FilePath    get() = dataDir.resolve("cache")
    val logsDir: FilePath     get() = dataDir.resolve("logs")
    val exportsDir: FilePath  get() = dataDir.resolve("exports")
    val modelsDir: FilePath   get() = dataDir.resolve("models")
    val legacyDir: FilePath   get() = dataDir.resolve("legacy")
    val dbFile: FilePath      get() = dataDir.resolve("kitoru.db")

    fun courseDir(courseId: String): FilePath     = coursesDir.resolve(courseId)
    fun materialsDir(courseId: String): FilePath = courseDir(courseId).resolve("materials")
    fun corpusDir(courseId: String): FilePath    = courseDir(courseId).resolve("corpus")
}
```

---

## 12. Nombres de archivos

| Archivo | Dónde | Qué es | Naturaleza |
|---|---|---|---|
| `kitoru.db` | `<dataDir>` | La base SQLite | generado, **no tocar** |
| `kitoru.db-wal`, `kitoru.db-shm` | `<dataDir>` | WAL e índice compartido | generados por SQLite, **no tocar** |
| `kitoru.db.bak-v<N>` | `<dataDir>` | Copia previa a una migración | generado, se puede borrar a mano |
| `kitoru.db.corrupto-<ts>` | `<dataDir>` | Base que no se pudo abrir | generado, **se conserva** |
| `settings.json` | `config/` | `AppSettings` + preferencias de UI | legible, atómico |
| `secure.bin` | `config/` | Credenciales cifradas (fallback escritorio) | cifrado, 0600 |
| `secure.key` | `config/` | Clave del fallback de escritorio | 0600, **es lo que lo hace débil** |
| `<materialId>__<slug>.<ext>` | `courses/<id>/materials/` | Original del material | del usuario |
| `<materialId>.txt` | `courses/<id>/corpus/` | Texto extraído | derivado, se regenera |
| `<materialId>.chunks.json` | `courses/<id>/corpus/` | Fragmentos con heading | derivado, se regenera |
| `index-v1.bin` | `courses/<id>/corpus/` | Caché del índice BM25 | derivado, opcional |
| `kitoru.log` | `logs/` | Log de la app | append, rotado por tamaño |
| `<courseCode>-<author>-<yyyy-MM-dd>.kitoru` | `exports/` | Bundle exportado | generado por la app |
| `<nombre>.tmp` | cualquiera | Temporal de escritura atómica | transitorio, se limpia al arrancar |
| `manifest.json` | dentro del `.kitoru` | Metadatos, versión, checksums | ver §7.6 |

**Convención de nombres de carpetas**: los ids son UUID, así que son seguros
como nombres de carpeta. Para ids importados con caracteres raros se usa el
`safeFileKey(raw)` que ya propone `04-conversaciones.md` §5.1.

---

## 13. El formato `.kitoru` (legibilidad)

### 13.1 Estructura del bundle

```
catedra_IA-2026-1_20261021.kitoru        (ZIP renombrado)
├── manifest.json
├── course.json
├── enrollments.json
├── materials.json
├── threads.json
├── messages.json
└── materials/                           (opcional)
    ├── a3f9c1__Unidad 1 - Introducción.pdf
    └── b71d02__Apuntes de la clase 3.md
```

`manifest.json`:

```json
{
  "format": "kitoru",
  "version": 1,
  "createdAt": 1750464000000,
  "app": { "name": "Kitoru", "version": "1.0.0" },
  "scope": {
    "kind": "course",
    "courseId": "c-ia-2026-1",
    "courseCode": "IA-2026-1",
    "courseName": "Inteligencia Artificial"
  },
  "counts": { "courses": 1, "enrollments": 42, "materials": 12,
              "threads": 340, "messages": 5180, "materialFiles": 12 },
  "files": [
    { "name": "messages.json", "bytes": 41200311, "sha256": "9f2c…" }
  ],
  "options": { "includeMaterialFiles": true, "includeMessageText": true, "anonymized": false },
  "privacy": { "enrollmentsIncluded": true, "messageTextIncluded": true, "hiddenStudents": 3 }
}
```

### 13.2 Por qué ZIP y no un JSON plano

| Criterio | JSON plano | ZIP con entradas JSON |
|---|---|---|
| Legibilidad | ✔ máxima (se abre con cualquier editor) | ✔ (las entradas son JSON; se abre con 7-Zip) |
| Tamaño | 536 MB sin comprimir | ~120 MB con deflate (el texto de los chats comprime 4–5×) |
| Lectura | hay que parsear **todo** el documento | se lee **una entrada** a la vez (streaming) |
| Checksums | uno global | **uno por archivo** (se puede verificar una parte) |
| Binarios | hay que base64-ificar los materiales | van crudos |
| Implementación | `kotlinx.serialization` | `java.util.zip`, **que está en `jvmCommonMain`** (desktop **y** Android) |

> El argumento «un ZIP no se puede inspeccionar» es falso: `unzip` está en
> Windows 11, macOS y Linux, y las entradas son JSON. Y la objeción de
> `04-conversaciones.md` §6.3 («`FileSystem` no tiene compresión») no aplica:
> la compresión no pasa por `FileSystem`, la hace `java.util.zip` directamente
> sobre el `FilePath`.

### 13.3 El export del alumno

Es **el mismo formato** con `scope.kind = "chat-export"`. Se mantiene el
formato plano `kitoru.chat-export` v1 de `04-conversaciones.md` §6.3 **como
formato de entrada** del importador (y como opción de salida «un solo
archivo JSON»), para no romper lo ya especificado:

| Formato | Dirección | Uso |
|---|---|---|
| `kitoru` v1 (ZIP) | salida y entrada | **canónico**: backup del docente, export de cátedra, export del alumno |
| `kitoru.chat-export` v1 (JSON plano) | entrada (y salida opcional) | compatibilidad con lo ya especificado en `04-conversaciones.md` |

El importador **detecta el formato por contenido**, no por extensión: si los
primeros bytes son `PK\x03\x04` es un bundle; si es `{` es el JSON plano.

### 13.4 Reglas del export

- La `apiKey` **jamás** sale: `ModelPolicy` va redacted y la clave no está en
  la base (§10).
- Con `scope = METRICS`, `messages.json` lleva los mensajes **sin `text`**
  (sólo contadores).
- `messages.json` se escribe **en streaming**: se consulta por lotes
  (`SELECT … LIMIT 10000 OFFSET ?` o por cursor de `seq`) y se escribe la
  entrada del ZIP en trozos. Nunca se materializan 400.000 mensajes en RAM.
- El `sha256` de cada entrada se calcula sobre los bytes exactos escritos.
- La importación es **idempotente** por id (`INSERT OR REPLACE`… no: `INSERT
  OR IGNORE` + `UPDATE` explícito, porque `UPSERT` no existe en 3.18),
  dentro de **una transacción**: o entra el bundle entero, o no entra nada.

---

## 14. Plan de trabajo

| Fase | Qué | Depende de |
|---|---|---|
| **F0** | Módulo `:storage` con el plugin de SQLDelight, el esquema `.sq` y `Schema.create`. Un test que abre la base y la cierra. | — |
| **F1** | `SecureStore` (`expect/actual` + Keystore en Android + DPAPI/Keychain/fallback en escritorio) + el test de no-fuga de la clave. | F0 |
| **F2** | Repositorios `Sql*` + los mappers de `ModelPolicy` con `apiKeyRef`. Tests de ida y vuelta (dominio → fila → dominio). | F0, F1 |
| **F3** | `SqlAnalyticsRepository` + el **test de paridad** contra `AnalyticsAnalyzer`. | F2 |
| **F4** | Integración con la UI: `Query.asFlow()` en las pantallas de hilo, lista de hilos y panel de analítica. | F3 |
| **F5** | Exportador/importador `.kitoru` (ZIP) + el importador del JSON legado. | F2 |
| **F6** | Migraciones: primer `.sqm` real + `verifyMigrations` en CI + el mecanismo de backup y de base corrupta. | F0 |
| **F7** | Corpus en disco + `Bm25Index` en memoria + la caché `index-v1.bin`. | F2 |

---

## 15. Plan de tests

| Test | Qué cubre |
|---|---|
| `SqliteSchemaTest` | La base se crea, `user_version` queda en la versión del esquema, y `PRAGMA foreign_key_list` devuelve las FK esperadas. |
| `ForeignKeysEnabledTest` | **`PRAGMA foreign_keys` = 1** al abrir. Si no, el test falla ruidosamente. |
| `CascadeDeleteTest` | Borrar un hilo borra sus mensajes, fuentes y tool calls. Borrar una cátedra borra todo lo suyo. |
| `SeqUniquenessTest` | Dos altas concurrentes del mismo hilo no producen el mismo `seq` (índice único + `Mutex`). |
| `RepositoryRoundTripTest` | Ida y vuelta dominio ↔ base para `Course`, `Enrollment`, `Material`, `Thread`, `ChatMessage` (incluidos `citation`, `sources`, `toolCalls`, `status`, `grounding`). |
| `AnalyticsParityTest` | Las consultas SQL dan **exactamente** los mismos números que `AnalyticsAnalyzer` (D12). |
| `VisibilityFilterTest` | Un alumno con `visibility = NONE` no aparece en ningún agregado; uno con `METRICS_ONLY` aparece en las métricas pero no aporta texto (D7 de `02-metricas.md`). |
| `MaterialAggregationTest` | «consultado ≠ citado ≠ respaldado» por `materialId` (no por etiqueta). |
| `LatencyHistogramTest` | El histograma por bucket suma el total de muestras y los percentiles caen dentro del rango del bucket. |
| `ApiKeyNotPersistedTest` | La clave no aparece en los bytes del `.db` ni del `.kitoru` (§10.4). |
| `MigrationTest` | `Schema.migrate` de la versión N a la N+1 produce el esquema esperado; `verifyMigrations` en CI. |
| `CorruptDatabaseTest` | Una base corrupta se renombra, se crea una nueva y el original queda en `kitoru.db.corrupto-<ts>`. |
| `NewerDatabaseTest` | Una base con `user_version` mayor se **no** toca y la app avisa. |
| `AtomicFilesTest` | Un temporal huérfano se promueve o se descarta según su fecha (§8.1). |
| `ExportBundleTest` | El `.kitoru` exportado se reimporta y produce los mismos datos; los checksums cuadran; el `scope = METRICS` no contiene texto. |
| `LegacyJsonImportTest` | El importador del JSON legado es idempotente y conserva los originales. |

---

## 16. Riesgos abiertos (a resolver antes de F4)

| # | Riesgo | Pregunta a responder | Dueño |
|---|---|---|---|
| 1 | **Los tokens** | `02-metricas.md` §2.1 atribuye `tokensIn` al mensaje del **usuario**, pero la request HTTP es del **asistente**. ¿Dónde se guarda `tokens_in`? | dominio + analítica |
| 2 | **El día local** | `day_start` se materializa con la zona de la cátedra. Si el docente **cambia la zona**, hay que recalcular todos los mensajes de la cátedra: ¿recalculo en background con progreso, o se recalcula al vuelo en la consulta? | plataforma |
| 3 | **La zona horaria** | `java.time.ZoneId` no existe en `commonMain`: hace falta un `expect fun zoneOffsetMillis(zoneId: String, instantMillis: Long): Int` en `kitoru.platform`. | plataforma |
| 4 | **Las sesiones** | Requieren funciones de ventana, que no hay en SQLite 3.18. ¿Se calculan en Kotlin (como propone §6.4) o se sube `minSdk` a 27? | producto + plataforma |
| 5 | **`minSdk`** | ¿Se sube `minSdk` de 24 a 26/27 para ganar SQLite 3.19/3.25? | producto |
| 6 | **El bundle vs. el chat-export** | Hay dos formatos `.kitoru` en los documentos (§13.3). Hay que dejar **uno canónico** y marcar el otro como entrada. | diseño |
| 7 | **El peso del driver** | `sqlite-jdbc` son ~12 MB en el instalador de escritorio. ¿Se acepta o se busca un SQLite empaquetado más chico? | plataforma |
| 8 | **Reconciliar `04-conversaciones.md`** | Su §5.1 y §5.4 describen el layout y la decisión opuestos a este documento. Hay que actualizarlos (o marcarlos como **superados**) para que no queden dos verdades. | diseño |

---

## 17. Resumen en cinco líneas

1. **El problema medido**: `messages.json` guarda los 400.000 mensajes de todas
   las cátedras y **lo reescribe entero en cada alta** (536 MiB escritos y
   parseados por mensaje; ≈ 7,6 GiB por sesión), y encima `delete(threadId)`
   no borra nada y un archivo truncado se lee como «base vacía».
2. **La comparación**: JSONL-por-hilo arregla la escritura (append O(1)) pero
   no las consultas agregadas que `02-metricas.md` ya definía (~30 métricas
   con `GROUP BY`, histogramas y joins por `materialId`); Room no existe para
   escritorio; **SQLite con SQLDelight** es el único que da escritura O(1) con
   transacciones, agregados en SQL, consultas observables para Compose y
   migraciones verificadas en CI.
3. **El veredicto**: **SQLite (SQLDelight) como única fuente de verdad**;
   materiales, corpus, modelos y logs **siguen en disco** en el layout ya
   especificado; la legibilidad no se pierde porque **el `.kitoru` (ZIP con
   entradas JSON) es el formato de intercambio**, obligatorio de todos modos.
4. **El resto del diseño**: esquema `.sq` con `course/enrollment/material/
   thread/message/message_source/tool_call/access_log/…`, `message`
   denormalizado con FK compuesta y `day_start` materializado, `PRAGMA
   foreign_keys=ON` + WAL + `busy_timeout`, migraciones `.sqm` con
   `verifyMigrations` en CI y backup previo, **API key fuera de la base** (en
   `SecureStore`: Keystore / DPAPI / Keychain, con fallback 0600 honestamente
   etiquetado), y la base **sin cifrar** a propósito (el secreto es la
   credencial, no los chats).
5. **El plan**: como Kitoru es nacente, **no hay nada que migrar**: se borra
   el store JSON y se empieza con la base desde el día uno, dejando el
   importador del JSON de prototipo y del `chats.json` de FunshiEngineAI como
   **red de seguridad**, y **actualizando `04-conversaciones.md` §5.1/§5.4**
   para que no queden dos verdades sobre dónde viven los datos.
