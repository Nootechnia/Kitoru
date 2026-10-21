# 09 · Panel de "calidad de las respuestas" (docente)

> **Estado:** diseño. **Módulos:** `:core` (dominio), `:backend` (verificador), `:ui` (pantalla).
> **Depende de:** `docs/design/CONTEXTO.md`, `rag.engine.CitationVerifier`, `kitoru.domain.CitationStats`.
> **Audiencia:** docente (titular y ayudante).

---

## 0. TL;DR

La calidad de las respuestas de la IA **se mide con un programa, no con una opinión**. El
verificador de citas (`rag.engine.CitationVerifier`, portado de FunshiEngineAI) es una función
pura: no llama al modelo, no usa la red, y da siempre el mismo veredicto para el mismo texto.
Por eso el docente puede ver, sin ambigüedad, **tres cosas** de cada respuesta:

| # | Lo que ve el docente | Significado técnico |
|---|---|---|
| 1 | *se apoyó en el material* | todas las citas `[n]` apuntan a una fuente que **dice** lo que la respuesta afirma |
| 2 | *afirmó cosas que el material no dice* | hay al menos una cita `[n]` cuya fuente **no respalda** el fragmento, o que **no existe** |
| 3 | *no comportó ninguna cita* | la respuesta no tiene ningún marcador `[n]`: el modelo respondió de memoria, no del material |

La frase ancla de la interfaz (encabezado de la pantalla):

> **De 100 respuestas, 78 se apoyaron en el material; 12 afirmaron cosas que el material no
> dice; 10 no comportaron ninguna cita.**

De abajo sale lo accionable: **qué material** concentra esas 12 respuestas, **por qué** falló
cada cita, y **qué hacer** con esa información (reabrir el material, reindexarlo, avisarle al
alumno, dejar una nota).

---

## 1. La idea central: el verificador es determinista

### 1.1 Qué es y qué no es

El `CitationVerifier` no "opina" sobre la respuesta: **compara texto con texto**.

1. El modelo responde con el material de la cátedra en el contexto, y **debe** citar con
   `[1]`, `[2]`… la fuente de la que sacó cada dato.
2. Después de generar, el verificador recorre cada marcador `[n]` de la respuesta, toma la
   **oración** que lo contiene (el *snippet*, máximo 400 caracteres) y la contrasta contra el
   texto de la fuente `n` (`sources[n-1]`).
3. Veredicto por cita, con una razón en español si falla.

Los tres criterios de "respaldada" (en este orden) son:

| # | Criterio | Qué exige |
|---|---|---|
| 1 | **Literal** | el fragmento aparece tal cual en la fuente (tras normalizar: minúsculas, sin acentos, sin puntuación) |
| 2 | **Sin detalles inventados** | los *términos clave* del fragmento (nombres propios y números de ≥2 dígitos o con decimal: `Vulkan`, `DirectX 12`, `1.0`) tienen que aparecer en la fuente |
| 3 | **Paráfrasis** | al menos el **50 %** de las palabras significativas del fragmento aparecen en la fuente (coincidencia exacta o raíz común de ≥5 letras) |

Si ninguno se cumple, la cita queda **sin respaldo**. La respuesta **no se descarta**: queda
marcada. Esto es fundamental para el diseño del panel: *el verificador no juzga si la respuesta
es "buena", sólo si está apoyada en el material*. El docente conserva la decisión.

### 1.2 Por qué esto se puede mostrar como un número

| Propiedad | Consecuencia en la UI |
|---|---|
| Es una **función pura** sobre texto | el mismo material + la misma respuesta ⇒ el mismo veredicto, siempre. Se puede recalcular y auditar. |
| **No depende del modelo** | no hay "el modelo dijo que era incorrecto". No hay falsos positivos de un juez LLM. |
| **No depende de la red** | el docente puede abrir el panel sin conexión; los tests son deterministas. |
| Produce una **razón textual** | cada fallo se puede explicar con una frase concreta, no con un score. |

En el panel, esta propiedad se traduce en un encabezado explícito:

> **Verificación automática y determinista.** Cada cita de cada respuesta se comparó con el
> texto del material. No es una evaluación del modelo: es una comparación de textos.

### 1.3 La clasificación de una respuesta

Cada respuesta del asistente con verificación disponible cae en **exactamente un** estado
(la precedencia importa: si una respuesta falla por dos motivos, se muestra el más grave):

```
SIN_FUENTE  →  SIN_RESPALDO  →  SIN_CITAS  →  RESPALDADA
```

| Estado | Condición | Cómo se lo dice al docente |
|---|---|---|
| `RESPALDADA` | hay ≥1 cita y **todas** están respaldadas | "se apoyó en el material" |
| `SIN_RESPALDO` | alguna cita `[n]` apunta a una fuente que existe pero **no dice** lo que la respuesta afirma | "afirmó cosas que el material no dice" |
| `SIN_FUENTE` | alguna cita `[n]` apunta a un índice **fuera** de las fuentes recuperadas | "afirmó cosas que el material no dice" (sub-caso: la cita no apunta a ninguna fuente) |
| `SIN_CITAS` | la respuesta no tiene ningún marcador `[n]` | "no comportó ninguna cita" |

> **Nota sobre la prosa.** El encabezado usa **tres** números, no cuatro: `SIN_RESPALDO` y
> `SIN_FUENTE` se agrupan en "afirmaron cosas que el material no dice" (12 = 10 + 2), porque
> para el docente es el mismo problema con distinta causa. El desglose por causa vive **una
> pantalla más abajo** (§2 y §3) y en el panel de tres columnas (§5).

### 1.4 El enunciado que se genera

El texto del encabezado **se arma con los datos**, nunca se escribe a mano. Para 100 respuestas:

```
De 100 respuestas, 78 se apoyaron en el material;
12 afirmaron cosas que el material no dice;
10 no comportaron ninguna cita.
```

Si además hay citas que apuntan a fuentes inexistentes, se agrega una línea secundaria:

```
2 de las 12 señaladas citaban una fuente que no existe.
```

Casos borde del generador:

| Situación | Texto generado |
|---|---|
| 0 respuestas con verificación | "Todavía no hay respuestas verificadas de la cátedra." |
| 100 % respaldadas | "Las 42 respuestas se apoyaron en el material." |
| 0 % respaldadas | "Ninguna de las 42 respuestas se apoyó en el material." |
| 1 respuesta | "De 1 respuesta, 1 se apoyó en el material." (singular) |

### 1.5 Lo que este panel NO es

- **No es una nota ni una calificación del alumno.** El panel mide *material vs. modelo*, no
  *alumno vs. materia*. Un alumno que pregunta mucho encuentra más casos: por eso el corte
  por alumno existe, pero la lectura principal es "qué material hay que arreglar".
- **No es un detector de plagio.** Mide si la respuesta está apoyada en el material.
- **No depende de un juez LLM.** No hay costo, ni latencia, ni variabilidad entre corridas.

---

## 2. Los tres motivos de fallo (y el cuarto, que es de borde)

El verificador emite **una razón en español** por cada cita que falla. Estas son las razones
reales que produce `CitationVerifier.checkSnippet` / `verify` (textos exactos del código):

| Clave | Mensaje real del verificador | Origen en el código |
|---|---|---|
| `no_apoya` | `El fragmento no aparece en la fuente (sólo el 20% de sus palabras).` | cobertura de palabras < 50 % |
| `detalle_inventado` | `La cita menciona "Vulkan", que no aparece en la fuente.` | criterio 2 (términos clave ausentes) |
| `fuente_inexistente` | `No hay una fuente [7] entre las recuperadas.` | `index < 1 \|\| index > sources.size` |
| `fuente_vacia` | `La fuente está vacía: no hay nada que respalde la cita.` | `normSource.isBlank()` (borde) |

### 2.1 (a) La fuente no respalda el fragmento — `no_apoya`

**Qué significa.** La cita `[n]` apunta a una fuente que **existe** y es la correcta, pero el
material **no contiene** esa frase. Puede ser una paráfrasis honesta que el verificador no
logró reconocer (falso positivo) o, peor, una afirmación del modelo que el material no
sostiene (se apoyó en su conocimiento previo).

**Ejemplo real.**

```
Fragmento de la respuesta : "El motor renderiza con Vulkan"
Fuente [1] (Tema 3.pdf)  : "El motor usa OpenGL para el renderizado por hardware"
Veredicto                : ✗ El fragmento no aparece en la fuente (sólo el 33% de sus palabras).
```

**Qué puede hacer el docente.**

1. Abrir la fuente y leer el pasaje equivalente. Si el material **sí** lo dice (con otras
   palabras), es ruido del verificador → no hacer nada, o anotar el material como ambiguo.
2. Si el material **no** lo dice, el modelo inventó → acción "Responder al alumno" (§6).
3. Si el material lo dice pero en **otro fragmento** (otro chunk), el problema es el
   troceado, no el material → probar con `chunkSize` menor (§3.4).

**Cómo se agrega en la UI.**

- Chip **ámbar** con el texto: `La fuente no lo dice`.
- Debajo, el fragmento citado entre comillas y el **porcentaje de coincidencia** como barra
  (`33 %`), que da la sensación de "casi" vs. "nada que ver".
- El texto real del verificador aparece en letra chica, plegable: *"detalle técnico"*.

### 2.2 (b) La cita menciona nombres o números que no están en la fuente — `detalle_inventado`

**Qué significa.** El fragmento habla de un **nombre propio** (`Vulkan`, `DirectX 12`,
`CUDA`) o de un **número con forma de versión o cantidad** (`12`, `1.0`, `2024`) que **no
aparece en la fuente**. Es el caso más grave: el modelo puso un dato concreto que no está en
el material — típicamente de su memoria de entrenamiento, o de una fuente distinta (un PDF que
el alumno tenía en otra carpeta, un apunte de otro curso).

Es también el criterio que separa una paráfrasis de una invención: si el modelo escribió
"usa DirectX 12" y la fuente dice "usa Vulkan", las frases se parecen pero **el dato está
inventado**.

**Ejemplo real.**

```
Fragmento de la respuesta : "El motor renderiza con DirectX 12"
Fuente [1] (Tema 3.pdf)  : "El motor renderiza con Vulkan"
Veredicto                : ✗ La cita menciona "DirectX 12", que no aparece en la fuente.
```

**Qué puede hacer el docente.**

1. **Reindexar el material**: un `detalle_inventado` muy frecuente sobre un material suele
   significar que el texto extraído **no es** el que el docente cree (PDF escaneado sin OCR,
   diapositivas con el texto en imágenes, tabla que se perdió al extraer) → acciones
   "Marcar para reindexar" y "Reabrir material y editar su texto" (§6).
2. Revisar si el modelo recibió material de otra cátedra (configuración de `topK`, materiales
   no visibles).
3. Avisarle al alumno: es el motivo por el que conviene responderle.

**Cómo se agrega en la UI.**

- Chip **rojo** con el texto: `Detalle inventado`.
- Los términos suspects se muestran **resaltados y entre comillas**, tal como los arma el
  verificador: `Detalle inventado: "DirectX 12"`.
- En la columna de detalle, el fragmento con el término inventado marcado en rojo y, al lado,
  el extracto real de la fuente. Es la comparación visual que hace evidente el error.

### 2.3 (c) La cita apunta a una fuente inexistente — `fuente_inexistente`

**Qué significa.** El modelo escribió `[7]` pero sólo se recuperaron 3 fuentes. El número no
existe en el contexto que recibió. Causas posibles:

- el modelo **inventó el índice** (alucinó la numeración);
- el motor le pasó al modelo una lista de fuentes **distinta** de la que se verifica (bug de
  pipeline);
- el modelo **[0]** (índice 0), que el verificador trata como inexistente.

**Ejemplo real.**

```
Fragmento de la respuesta : "El tema 5 se explica en el apunte 5 [7]"
Fuentes recuperadas [1..3]
Veredicto                : ✗ No hay una fuente [7] entre las recuperadas.
```

**Qué puede hacer el docente.**

1. Es el menos "accionable sobre el material" y el más grave como señal de **pipeline**: si
   hay muchas citas a fuentes inexistentes en la cátedra, hay que revisar el `topK`, el
   troceado o el prompt (que le pide al modelo citar `[1]`, `[2]`…).
2. Igual se puede responder al alumno, porque la afirmación no está respaldada por nada.

**Cómo se agrega en la UI.**

- Chip **rojo** con el texto: `Fuente inexistente` y el índice: `Fuente inexistente: [7]`.
- A diferencia de los otros dos, **no hay material al que abrir**: la columna de detalle lo
  dice explícitamente: **"El modelo cité una fuente que no le fue entregada."**

### 2.4 (d) Caso borde: la fuente está vacía — `fuente_vacia`

**Qué significa.** El material se recuperó pero **no tiene texto extraído** (`extractedText`
vacío o ilegible). Todas sus citas fallan con este motivo. Es la señal más clara de que un
material está mal indexado.

**Cómo se agrega en la UI.** Chip **rojo** `Material vacío`, y en la vista por material (§3)
el material aparece arriba de todo, con la acción "Reindexar" destacada.

### 2.5 Tabla resumen de los motivos

| Motivo | Gravedad | Se arregla en el material | Se arregla en la config | Requiere hablar con el alumno |
|---|---|---|---|---|
| `no_apoya` | media | a veces (re-chunk, editar texto) | no | a veces |
| `detalle_inventado` | alta | **casi siempre** (reindexar / editar texto) | no | sí |
| `fuente_inexistente` | alta | no | **sí** (`topK`, prompt, pipeline) | sí |
| `fuente_vacia` | alta | **siempre** (reindexar) | no | no |

---

## 3. Cruce por material: la vista más accionable

### 3.1 La pregunta que responde esta vista

> *¿Qué material de la cátedra está generando respuestas sin respaldo?*

Si un PDF falla el 40 % de las veces, el problema casi nunca es el modelo: es que **ese
material está mal indexado, es ambiguo, o está mal troceado**. Es la única vista del panel que
produce una tarea concreta para el docente ("arreglar este archivo"), y por eso es la vista
principal — no un extra.

### 3.2 La tabla

```
Material                            Resp.  Sin resp.   %     Motivo dominante             Acción sugerida
────────────────────────────────────────────────────────────────────────────────────────────────
Tema 3 - GPUs.pdf                     25       10      40 %   La fuente no lo dice        ↓ chunkSize
Transcripción Clase 2                  9        4      44 %   Detalle inventado            ⟳ reindexar
Clase 1 - Intro.pdf                   14        3      21 %   Detalle inventado            ⟳ reindexar
Apuntes Tema 5.pdf                    21        2      10 %   —                            —
```

Columnas:

| Columna | Cálculo | Significado |
|---|---|---|
| **Resp.** | nº de respuestas que citaron este material | volumen de exposición |
| **Sin resp.** | nº de esas respuestas con ≥1 cita sin respaldo sobre este material | cantidad absoluta |
| **%** | `Sin resp. / Resp.` | la tasa que dispara la alerta |
| **Motivo dominante** | el motivo con más citas fallidas | *qué* está mal |
| **Acción sugerida** | derivada del motivo dominante | *qué hacer* (§3.4) |


> El primer renglón es el ejemplo del enunciado: **"el PDF del Tema 3 tiene 40 % de respuestas
> sin respaldo"**. 10 de las 25 respuestas que lo citaron tenían al menos una cita que el
> material no respaldaba.

Se muestran **dos tasas**, porque no son lo mismo:

- `tasa por respuesta` = respuestas con ≥1 fallo ÷ respuestas que citaron el material → lo
  que entiende el docente ("40 % de las respuestas que lo citaron fallaron con él").
- `tasa por cita` = citas fallidas ÷ citas totales a ese material → lo que entiende el
  técnico ("1 de cada 3 citas a ese archivo no se sostiene").

En la tabla se muestra la tasa por respuesta (la del ejemplo: "40 %"); la tasa por cita está
disponible en el tooltip de la fila.

### 3.3 El cálculo (determinista, sin modelo)

Para cada respuesta del asistente `m` de un alumno visible, con `m.citation != null`:

```
para cada check c de m.citation.checks:
    materialId = c.materialId                       // id del material (ver §7.3)
    si materialId == null:
        → el fallo no se puede atribuir a un material
          (c corresponde a una fuente inexistente) → se cuenta en el cajón "sin material"
        continuar
    porMaterial[materialId].citations      += 1
    porMaterial[materialId].citationsOK    += (c.supported ? 1 : 0)
    respuestasQueUsan[materialId].add(m.id)          // un set: cuenta respuestas, no citas
    si !c.supported:
        porMaterial[materialId].answersWithFailures += 1   // una respuesta cuenta una sola vez
        porMaterial[materialId].reasons[c.reasonKind] += 1
```

Con lo cual:

```
tasaSinRespaldo(material)   = respuestasConFallo / respuestasQueUsan
tasaPorCita(material)       = citasFallidas / citasTotales
```

**Regla de significancia.** Un material no se marca como "sospechoso" con una sola cita:

```kotlin
val esSospechoso = respuestasQueUsan >= MIN_MUESTRAS && tasaSinRespaldo >= 0.25
const val MIN_MUESTRAS = 5
```

Con menos de 5 respuestas que lo citan, la tabla muestra la tasa en gris y la etiqueta
*"pocos datos, no concluyente"*. Esto evita que el docente persiga un PDF que teve un fallo
aislado.

### 3.4 De la tasa a la acción: diagnóstico por motivo dominante

El mismo síntoma puede tener causas distintas. El motivo dominante dice cuál:

| Motivo dominante | Diagnóstico probable | Acción sugerida (automática) |
|---|---|---|
| `no_apoya` | el texto **está** en el material pero en otro chunk → troceado demasiado grande o solapamiento insuficiente | `Ajustar troceado` (bajar `chunkSize`, subir `chunkOverlap`) |
| `detalle_inventado` | el texto extraído no es el que el docente cree (OCR, diapositivas, tablas) | `Reindexar` |
| `fuente_inexistente` | problema de recuperación o de prompt | `Revisar topK` / `Revisar prompt` |
| `fuente_vacia` | el material no tiene texto | `Reindexar` (obligatorio) |

La columna "Acción sugerida" es un **enlace directo** a la acción (§6), no un texto decorativo.

### 3.5 Interacción: filtrar cruzando

- Click en una fila de la tabla → **filtra** el panel de tres columnas (§5) por ese material y
  cambia la pestaña activa a "Respuestas problemáticas".
- El chip de filtro queda visible arriba: `Filtrando por: Tema 3 - GPUs.pdf  ✕`.
- El botón "Ver en el material" abre el editor de texto de ese material (§6).

---

## 4. El distintivo de citas en el chat (el `CitationBadge`)

El distintivo ya existe en FunshiEngineAI (`frontend/.../app/chat/MessageBubble.kt`,
`CitationBadge`). En Kitoru se porta y **se rediseña** para que el alumno entienda qué está
viendo, sin vocabulario técnico.

### 4.1 Cómo se ve

Debajo del texto de la respuesta y **debajo de la lista de fuentes**, con ícono + una línea de
texto (patrón copiado de FunshiEngineAI, que funciona bien en escritorio):

| Estado | Ícono | Texto | Color |
|---|---|---|---|
| Todas respaldadas | `Icons.Filled.Verified` | `Citas verificadas (3)` | `primary` |
| Con fallos | `Icons.Filled.WarningAmber` | `2 citas sin respaldo` (+ ` · 1 sin fuente` si hay) | `error` |
| Sin citas | `Icons.Outlined.RemoveCircle` | `Sin citas: respondió sin el material` | `onSurfaceVariant` |

Pluralización: `1 cita sin respaldo` / `2 citas sin respaldo`.

Ejemplo de la burbuja (Kitoru):

```
┌──────────────────────────────────────────────────────────────┐
│ El motor usa Vulkan para renderizar [1]. La API se expone   │
│ desde la versión 12 [2].                                     │
│                                                              │
│ Fuentes:                                                     │
│ [1] Tema 3 - GPUs.pdf                                       │
│ [2] Apuntes Tema 5.pdf                                      │
│                                                              │
│ ⚠ 1 cita sin respaldo                                       │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 Qué se le explica al alumno

El distintivo es **clicable**: abre una hoja inferior (*sheet*) con el detalle y una explicación
en lenguaje llano.

**Contenido de la hoja "Detalle de citas":**

```
Cómo funciona la verificación

Cada vez que el asistente responde, la app compara lo que dijo con el
texto del material de la cátedra. Si una afirmación no está en el material,
te lo avisamos: el material es la referencia, no la memoria del modelo.

✓ Citas verificadas (1)
   [1] "El motor usa Vulkan para renderizar" — Tema 3 - GPUs.pdf § Gráficos
   La fuente dice exactamente esto.

✗ La fuente no lo dice (1)
   [2] "La API se expone desde la versión 12" — Apuntes Tema 5.pdf
   El fragmento no aparece en la fuente (sólo el 25% de sus palabras).
   Puede ser una paráfrasis o un error del modelo. Verificá la fuente.

ℹ Sin citas
   Esta respuesta no usó el material de la cátedra.
```

**Reglas de la explicación al alumno:**

1. **Nunca** se dice "el modelo mintió" ni "alucinó". Se dice "no coincide con el material".
2. Se aclara que el distintivo **no califica** la respuesta: el alumno puede usar igual la
   respuesta, pero conviene verificarla.
3. Se nombra la fuente para que el alumno pueda ir a buscarla.
4. El texto del verificador se muestra **tal cual** (es claro y está en español), precedido
   de una versión en lenguaje llano.

### 4.3 Mejoras sobre el `CitationBadge` original

| Mejora | Por qué |
|---|---|
| El texto del chip cambia según el caso ("Sin citas: respondió sin el material" en vez de "Sin citas") | "Sin citas" era ambiguo: parecía un error de la app |
| El chip es clicable y abre el detalle por cita | el alumno puede ver *cuál* cita falló, no sólo cuántas |
| Los marcadores `[n]` del texto se renderizan como chips clicables que hacen scroll a la fuente | en FunshiEngineAI el `[1]` es texto plano dentro del markdown |
| El badge sólo aparece en respuestas del asistente y sólo cuando hay material | en chat libre (sin RAG) el distintivo sería ruido |

### 4.4 Copy exacto de los estados

```
todas ok        →  "Citas verificadas (3)"
con fallos      →  "2 citas sin respaldo"
                  →  "2 citas sin respaldo · 1 sin fuente"
sin citas       →  "Sin citas: respondió sin el material"
```

(El copy de FunshiEngineAI era `"${c.unsupported} cita${...} sin respaldo"` y
`"${c.unknown} sin fuente"`; se conserva la estructura y se le agrega el caso "sin citas".)

---

## 5. La lista de "respuestas problemáticas": panel de tres columnas

### 5.1 Estructura de la pantalla

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ Calidad de las respuestas     [Cátedra ▾] [Últimos 30 días ▾] [Material ▾] [Exportar CSV]  │
├───────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌─ Verificación automática y determinista ──────────────────────────────────────────────┐ │
│  │ De 100 respuestas, 78 se apoyaron en el material;                                   │ │
│  │ 12 afirmaron cosas que el material no dice; 10 no comportaron ninguna cita.          │ │
│  │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │ │
│  │ Causas: ● La fuente no lo dice (7)  ● Detalle inventado (3)  ● Fuente inexistente (2) │ │
│  └──────────────────────────────────────────────────────────────────────────────────────┘ │
├───────────────────────────────────────────────────────────────────────────────────────────┤
│  [Respuestas problemáticas] [Por material] [Por alumno] [Por causa]                       │
├───────────────────────┬───────────────────────────────┬──────────────────────────────────┤
│ (1) LISTA             │ (2) DETALLE DE LA RESPUESTA    │ (3) ACCIONES                     │
│                       │                                │                                  │
│ Filtros:             │ Pregunta del alumno:           │ ▸ Reabrir material y editar       │
│ [Con fallo  12]    │ "¿Cómo se elige la API?"       │   su texto                       │
│ [Fuente no dice 7]  │                                │ ▸ Marcar para reindexar          │
│ [Detalle inv.  3]    │ Respuesta:                     │ ▸ Responder al alumno            │
│ [Fuente inex.   2]   │ "El motor usa DirectX 12 [1]"  │ ▸ Añadir una nota                │
│ [Sin citas     10]   │  ▲ término inventado           │ ▸ Ver el hilo completo           │
│ [Todos          22]   │                                │                                  │
│                       │                                │                                  │
│ ▸ Ana Ruiz           │ Comprobaciones:                │ Motivo de este fallo:            │
│   Tema 3 - GPUs.pdf  │  ✓ [1] respaldada — Tema 3     │  Detalle inventado               │
│   "…DirectX 12 [1]"  │  ✗ [2] La cita menciona       │  → reindexar el material         │
│   ⚠ Detalle inv.     │    "DirectX 12", que no        │                                  │
│                       │    aparece en la fuente.       │                                  │
│ ▸ Bruno Sosa         │  Fuente: Tema 3 - GPUs.pdf     │                                  │
│   Sin citas          │  "El motor renderiza con       │                                  │
│                       │   Vulkan"                     │                                  │
└───────────────────────┴───────────────────────────────┴──────────────────────────────────┘
```

En pantallas angostas (< 1100 dp) las tres columnas se apilan: lista arriba, detalle abajo
(la columna de acciones se convierte en una barra fija bajo el detalle).

### 5.2 Columna 1 — la lista

Cada fila es una respuesta problemática. Contenido:

```
⚠ Ana Ruiz · "Normalización de datos" · 12/10
  "El motor usa DirectX 12 para renderizar" [1]
  Detalle inventado · Tema 3 - GPUs.pdf § Gráficos
```

| Elemento | Origen |
|---|---|
| Nombre del alumno | `Enrollment.displayName` (sólo si `visibility.showsMetrics`) |
| Título del hilo | `ChatThread.title` |
| Fecha | `ChatMessage.createdAt` |
| Icono | ⚠ rojo (fallo de cita), ℹ gris (sin citas) |
| Fragmento | el `snippet` de la cita que falló (o los primeros 120 caracteres si no hay citas) |
| Chips | motivo + material + sección |

**Filtros de la columna 1** (chips con contador, calculados sobre el mismo conjunto):

- `Con fallo de cita (12)` — respuestas con ≥1 cita sin respaldo o sin fuente.
- `La fuente no lo dice (7)` — respuestas con ≥1 cita `no_apoya`.
- `Detalle inventado (3)` — respuestas con ≥1 cita `detalle_inventado`.
- `Fuente inexistente (2)` — respuestas con ≥1 cita `fuente_inexistente`.
- `Sin citas (10)` — respuestas sin ninguna cita.
- `Todos (22)` — todo lo que hay en la lista.
- Búsqueda por texto (alumno, hilo, material).

> Los tres chips de causa **se pueden superponer**: una respuesta puede fallar a la vez por
> `no_apoya` y por `detalle_inventado` (dos citas distintas con dos motivos distintos). Por eso
> sus contadores no suman 12 necesariamente; el chip `Con fallo de cita (12)` es el que
> reconcilia con el encabezado.

**Orden por defecto:** por fecha descendente (lo reciente primero). Alternativas:
"más fallidas primero" (por nº de citas fallidas) y "por material".

**Cruce con la visibilidad.** Si el alumno está en `METRICS_ONLY`, la fila muestra el
material y los contadores, **pero el fragmento va tachado con un candado** y el texto real no
se carga. La fila sigue siendo útil (el docente ve que *ese material* falla) sin violar la
visibilidad (§9).

### 5.3 Columna 2 — el detalle de la respuesta

Contenido, en orden:

1. **La pregunta del alumno** (mensaje `user` anterior en el hilo).
2. **La respuesta completa**, con los marcadores `[n]` resaltados y el término inventado
   marcado en rojo.
3. **Las comprobaciones**, una por cita, con su veredicto:
   - `✓ [1] Respaldada — Tema 3 - GPUs.pdf § Gráficos`
   - `✗ [2] La cita menciona "DirectX 12", que no aparece en la fuente. — Apuntes Tema 5.pdf`
4. **La comparación lado a lado** (sólo para las citas que fallan):
   - Izquierda: el **fragmento exacto** de la respuesta (lo que el modelo afirmó).
   - Derecha: el **extracto de la fuente** (primeros 400 caracteres del chunk recuperado),
     con el texto relevante resaltado si se puede localizar.
5. **El motivo**, con el texto real del verificador y una traducción a lenguaje de docente.
6. **El material de la fuente**, con botón "Abrir material".

> El "extracto de la fuente" (400 caracteres) se persiste **sólo para las citas que fallan**
> (§7). Es lo que hace posible la comparación lado a lado sin volver a recuperar el chunk.

### 5.4 Columna 3 — las acciones

Ver §6. La columna muestra también el **motivo de este fallo** y la **acción sugerida**
(derivada del motivo dominante), para que el docente no tenga que decidir desde cero.

---

## 6. Acciones correctivas desde el panel

Las cuatro acciones están disponibles en la columna 3 y también contextualizadas en la vista
por material (una acción sobre *el material*, no sobre *la respuesta*).

### 6.1 Reabrir el material y editar su texto

- **Qué abre:** el editor del material (`MaterialTextEditor`) con `Material.extractedText` en un
  campo de texto multilínea, el título arriba y el tipo de material al lado.
- **Qué permite:** corregir el texto extraído (típico: el PDF traía el texto de las
  diapositivas en el orden equivocado, o le faltaban los enunciados de los ejercicios).
- **Efecto al guardar:**
  ```kotlin
  materialRepository.save(material.copy(
      extractedText = nuevoTexto,
      needsReindex = true,          // editar el texto implica re-trocear
      updatedAt = nowMillis(),
  ))
  ```
  y se muestra: *"Se marcará para reindexar. El cambio se aplica cuando termine la
  reindexación."*
- **Quién puede:** `CourseRole.canDesign` (titular y ayudante).
- **Efecto en el panel:** el material aparece con la insignia ⟳ *Pendiente de reindexar* hasta
  que el índice se rehaga.

### 6.2 Marcar para reindexar

- **Qué hace:** `material.needsReindex = true` + un motivo corto, elegible de una lista:
  - `El texto extraído está mal`
  - `Falta contenido`
  - `El material es demasiado largo`
  - `El material es ambiguo`
  - `Otro` (texto libre)
- **Para qué sirve:** el pipeline de indexación revisa la bandera y rehace el índice de ese
  material **sin** que el docente tenga que resubir el archivo.
- **Dónde se ve:** insignia ⟳ en la lista de materiales de la cátedra y en el panel de
  calidad; se limpia sola cuando la reindexación termina.
- **Relación con §6.1:** si el docente edita el texto, la reindexación se marca
  automáticamente; el botón "Marcar para reindexar" es para el caso "el texto está bien, el
  problema es el troceado o la indexación".

### 6.3 Responder al alumno

- **Qué abre:** un diálogo con:
  - destinatario: el alumno (nombre y si está en modo local o remoto);
  - el **fragmento citado** precargado y seleccionable, para que el docente responda sobre lo
    que el modelo afirmó;
  - un campo de texto libre.
- **Efecto:** crea un mensaje en el hilo del alumno con `role = "teacher"`:
  ```kotlin
  messageRepository.save(ChatMessage(
      id = nuevoId(),
      threadId = hilo.threadId,
      role = "teacher",                 // NUEVO rol
      text = texto,
      createdAt = nowMillis(),
  ))
  ```
- **Cómo lo ve el alumno:** en su chat, una burbuja distinta (color `tertiary`/`primary`),
  con la etiqueta **"Docente"** arriba y un ícono de persona. El mensaje del docente **no** pasa
  por el verificador (no lleva `citation`), porque es texto humano.
- **Regla de visibilidad:** si el alumno está en `ChatVisibility.NONE`, el docente **no puede**
  responder sobre un fragmento concreto (no lo ve) pero sí puede dejar un mensaje general en el
  hilo. Si está en `METRICS_ONLY`, puede responder pero **el fragmento no se precarga** (no lo ve).
- **Auditoría:** el mensaje del docente queda en el hilo con su autor y su fecha; el
  `AnalyticsAnalyzer` no lo cuenta como respuesta del modelo (filtra `role == "assistant"`).

### 6.4 Añadir una nota

- **Qué es:** una nota **interna** del docente, que **no** ve el alumno.
- **Dónde se guarda:** en el material, como `MaterialNote` (§7.9):
  ```kotlin
  @Serializable
  data class MaterialNote(
      val id: String,
      val authorId: String,
      val authorName: String,
      val text: String,
      val createdAt: Long,
      /** Fragmento de la respuesta que originó la nota (opcional). */
      val quote: String = "",
      /** Material al que se refiere (opcional). */
      val materialId: String = "",
  )
  ```
- **Datos precargados:** el fragmento que falló, el motivo y la etiqueta del material. Así la
  nota dice *"Tema 3 - GPUs.pdf: el PDF tiene las diapositivas en orden inverso"* sin que el
  docente tenga que volver a buscarlo.
- **Cómo se ve:** en la columna 3, un bloque "Notas (2)" con las notas del material; y en la
  lista de materiales de la cátedra, el contador de notas.

### 6.5 Resumen de las acciones

| Acción | Afecta | Persistencia | Visible para el alumno |
|---|---|---|---|
| Editar texto del material | `Material.extractedText` | `MaterialRepository` | sí (tras reindexar) |
| Marcar para reindexar | `Material.needsReindex` | `MaterialRepository` | no |
| Responder al alumno | nuevo `ChatMessage(role="teacher")` | `MessageRepository` | sí (en su chat) |
| Añadir una nota | `Material.notes` | `MaterialRepository` | no |

---

## 7. Métricas nuevas que hay que añadir al modelo

### 7.1 La extensión exacta de `CitationStats`

El tipo vive hoy en `core/src/commonMain/kotlin/kitoru/domain/Chat.kt`. Se **extiende en el
mismo archivo** (no se mueve: moverlo rompe los `import` de quien ya lo use) y se le agregan
dos campos con valor por defecto, más un tipo nuevo en un archivo nuevo.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt  (modificado)

/** Verificación de citas de una respuesta (heredada del verificador de FunshiEngineAI). */
@Serializable
data class CitationStats(
    /** Citas respaldadas por la fuente que citan. */
    val verified: Int = 0,
    /** Citas cuya fuente existe pero no respalda lo que se dice. */
    val unsupported: Int = 0,
    /** Citas que apuntan a una fuente inexistente. */
    val unknown: Int = 0,

    // ─── NUEVOS (con valor por defecto: no rompen lo existente) ───

    /** Verificación de cada cita, en el orden en que aparecen en la respuesta. */
    val checks: List<CitationCheckStats> = emptyList(),
    /** Motivos de fallo agregados: clave estable → cantidad de citas. */
    val reasons: Map<String, Int> = emptyMap(),
) {
    val total: Int get() = verified + unsupported + unknown

    /** Proporción de citas respaldadas (0..1). Sin citas = 0. */
    val supportRate: Double get() = if (total == 0) 0.0 else verified.toDouble() / total

    /** ¿Todas las citas están respaldadas? */
    val fullySupported: Boolean get() = total > 0 && unsupported == 0 && unknown == 0

    // ─── NUEVOS: derivados ───

    /** ¿La respuesta tuvo alguna cita? (false = respondió de memoria) */
    val hasCitations: Boolean get() = total > 0

    /** Citas que fallaron (sin respaldo o sin fuente). */
    val failed: Int get() = unsupported + unknown

    /** La respuesta se apoyó enteramente en el material. */
    val grounded: Boolean get() = hasCitations && failed == 0

    /** Comprobaciones que fallaron. */
    val failedChecks: List<CitationCheckStats> get() = checks.filter { !it.supported }

    /** Motivos de fallo tipificados (ignora claves desconocidas). */
    val reasonCounts: Map<CitationReason, Int>
        get() = reasons.mapNotNull { (k, v) -> CitationReason.fromKey(k)?.let { it to v } }.toMap()
}
```

> **Nota de diseño:** `hasCitations` **no** necesita ser un campo persistido. En el
> verificador, `hasCitations == false` implica `checks` vacío, y `checks` no vacío implica
> `total > 0`; por lo tanto `total > 0` ⇔ la respuesta tenía citas. Derivar la propiedad evita
> un campo que podría quedar inconsistente con los contadores.

### 7.2 El tipo nuevo `CitationCheckStats` (dominio)

`:core` no puede depender de `:backend` (es al revés: `:backend` depende de `:core`), así que
el dominio tiene **su propia copia** del detalle de la cita. No es duplicación inútil: el
dominio guarda lo que se **persiste**; el backend guarda lo que se **calcula**.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Citation.kt   (NUEVO)

package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Motivo por el que una cita no está respaldada.
 *
 * La clave (`key`) es **estable**: es lo que se persiste y lo que se grafica.
 * El texto visible (`label`) se puede cambiar sin romper los datos viejos.
 */
enum class CitationReason(
    val key: String,
    val label: String,
    /** Qué le decimos al docente que puede hacer. */
    val teacherHint: String,
) {
    NO_APOYA(
        "no_apoya",
        "La fuente no lo dice",
        "El fragmento no coincide con el texto de la fuente. Revisá el material: puede estar mal troceado o puede ser una paráfrasis.",
    ),
    DETALLE_INVENTADO(
        "detalle_inventado",
        "Detalle inventado",
        "La respuesta menciona un nombre o un número que no está en el material. Suele indicar que el texto extraído está mal: reindexá el material.",
    ),
    FUENTE_INEXISTENTE(
        "fuente_inexistente",
        "Fuente inexistente",
        "La cita apunta a una fuente que no se recuperó. Es un problema de recuperación: revisá el topK y el prompt de la cátedra.",
    ),
    FUENTE_VACIA(
        "fuente_vacia",
        "Material vacío",
        "El material no tiene texto extraído. Reindexalo antes de volver a usarlo.",
    ),
    ;

    companion object {
        /** Busca el motivo por su clave persistida (desconocida → null). */
        fun fromKey(key: String): CitationReason? = entries.firstOrNull { it.key == key }
    }
}

/** Verificación de UNA cita, ya persistida (copia de dominio de `rag.engine.CitationCheck`). */
@Serializable
data class CitationCheckStats(
    /** Índice citado (1 = la primera fuente recuperada). */
    val index: Int,
    /** Etiqueta de la fuente ("cátedra: Tema 3 - GPUs.pdf"). Vacía si el índice no existe. */
    val label: String = "",
    /** Sección del material de la que salió el fragmento. */
    val heading: String = "",
    /** El fragmento exacto de la respuesta alrededor de la cita (sin el marcador). */
    val snippet: String = "",
    /** ¿La fuente respalda el fragmento? */
    val supported: Boolean = false,
    /** Clave del motivo (`CitationReason.key`), vacía si la cita está respaldada. */
    val reasonKind: String = "",
    /** Texto real del verificador (sólo si la cita no está respaldada). */
    val reason: String = "",
    /** Fracción de palabras del fragmento presentes en la fuente (0..1). -1 = no aplica. */
    val coverage: Double = -1.0,
    /** Extracto de la fuente (sólo en las citas que fallan, máx. 400 caracteres). */
    val sourceExcerpt: String = "",
    /** Id del material del que salió la fuente (vacío si no se pudo determinar). */
    val materialId: String = "",
) {
    /** Motivo tipificado, si la cita falló. */
    val reasonType: CitationReason? get() = CitationReason.fromKey(reasonKind)

    /** Etiqueta corta para la UI: "Tema 3 - GPUs.pdf § Gráficos". */
    val sourceLabel: String
        get() = if (heading.isBlank()) label else "$label § $heading"
}
```

### 7.3 El mapeo backend → dominio

El backend ya sabe calcular todo; sólo falta volcarlo al tipo persistible.

```kotlin
// backend/src/commonMain/kotlin/rag/engine/CitationStatsMapper.kt   (NUEVO)

package rag.engine

import kitoru.domain.CitationCheckStats
import kitoru.domain.CitationStats
import rag.retrieval.Chunk

/**
 * Convierte el informe del verificador en el tipo que se persiste en el mensaje.
 *
 * Se hace en `:backend` porque es el módulo que conoce tanto el verificador
 * (`rag.engine`) como el dominio (`kitoru.domain`).
 */
fun CitationReport.toCitationStats(sources: List<Chunk> = emptyList()): CitationStats =
    CitationStats(
        verified = verified,
        unsupported = unsupported,
        unknown = unknownCount,
        checks = checks.map { check ->
            val chunk = sources.getOrNull(check.index - 1)
            CitationCheckStats(
                index = check.index,
                label = check.label,
                heading = check.heading.ifBlank { chunk?.heading.orEmpty() },
                snippet = check.snippet,
                supported = check.supported,
                reasonKind = check.reasonKind,
                reason = check.reason,
                coverage = check.coverage,
                // Sólo guardamos el extracto de la fuente para las citas que fallan:
                // es lo que permite la comparación lado a lado sin reindexar.
                sourceExcerpt = if (check.supported) "" else chunk?.text?.take(MAX_EXCERPT).orEmpty(),
                materialId = chunk?.sourceId.orEmpty(),
            )
        },
        reasons = reasons,
    )

/** Largo máximo del extracto de fuente persistido. */
const val MAX_EXCERPT = 400
```

Y los cambios mínimos en el verificador (`backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt`),
**sin cambiar ninguna firma pública**:

```kotlin
/** Verificación de una cita concreta. */
data class CitationCheck(
    val index: Int,
    val label: String,
    val snippet: String,
    val supported: Boolean,
    val reason: String = "",
    // ─── NUEVOS (con valor por defecto) ───
    /** Sección del material de la que salió el fragmento. */
    val heading: String = "",
    /** Clave estable del motivo (`kitoru.domain.CitationReason.key`). */
    val reasonKind: String = "",
    /** Cobertura de palabras (0..1). -1 = no aplica. */
    val coverage: Double = -1.0,
)

/** Resultado de verificar todas las citas de una respuesta. */
data class CitationReport(
    val checks: List<CitationCheck>,
    val hasCitations: Boolean,
    val valid: Boolean,
    val unknown: List<Int> = emptyList(),
    val uncited: List<Int> = emptyList(),
) {
    val verified: Int get() = checks.count { it.supported }
    val unsupported: Int get() = checks.count { !it.supported && it.index > 0 }
    val unknownCount: Int get() = unknown.size

    // ─── NUEVO: causas agregadas, listas para graficar ───
    /** Motivos de fallo agregados: clave estable → cantidad de citas. */
    val reasons: Map<String, Int>
        get() = checks.filter { !it.supported }
            .groupingBy { it.reasonKind }
            .eachCount()
}
```

Y dentro del verificador, `checkSnippet` deja de devolver `Pair<Boolean, String>` y devuelve un
`Verdict` interno (es una función **privada**, no hay ruptura de API):

```kotlin
/** Veredicto interno de una comprobación: qué pasó y con qué detalle. */
private data class Verdict(
    val supported: Boolean,
    val reason: String = "",
    val reasonKind: String = "",
    val coverage: Double = -1.0,
)
```

con las cuatro salidas:

| Situación | `reason` | `reasonKind` | `coverage` |
|---|---|---|---|
| literal o paráfrasis OK | `""` | `""` | -1.0 |
| fuente vacía | `La fuente está vacía: no hay nada que respalde la cita.` | `fuente_vacia` | -1.0 |
| término clave ausente | `La cita menciona $lista, que no aparece en la fuente.` | `detalle_inventado` | -1.0 |
| cobertura insuficiente | `El fragmento no aparece en la fuente (sólo el $percent% de sus palabras).` | `no_apoya` | cobertura real (0..1) |
| índice inexistente | `No hay una fuente [$index] entre las recuperadas.` | `fuente_inexistente` | -1.0 |

### 7.4 Identificar el material de una fuente (`sourceId`)

Para cruzar citas con materiales hace falta saber **a qué material** pertenece cada fuente
recuperada. Hoy `Chunk.label` es `"cátedra: <título del material>"`, que depende de texto
libre (dos materiales pueden llamarse igual). Se agrega un id estable:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt   (modificado)

data class Doc(
    val source: String,
    val path: String,
    val text: String,
    // ─── NUEVO ───
    /** Id del material de origen (vacío si la fuente no es un material). */
    val sourceId: String = "",
)

data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    // ─── NUEVO ───
    /** Id del material de origen (vacío si la fuente no es un material). */
    val sourceId: String = "",
) {
    val label get() = "$source: $path"
}
```

Propagación (dos líneas):

- `Chunker.split`: `out += Chunk(doc.source, doc.path, heading, piece, doc.sourceId)`.
- `CourseMaterialSource.load`: `Doc(source = corpusLabel, path = material.title…, text = material.extractedText, sourceId = material.id)`.

Y en el mensaje, para poder cruzar sin depender del chunk:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt   (modificado, en ChatMessage)

/** Ids de los materiales de donde salieron las fuentes (mismo orden que [sources]). */
val sourceIds: List<String> = emptyList(),
```

`ChatMessage.sourceIds` se llena en el pipeline del chat:
`message.sourceIds = prepared.sources.map { it.sourceId }` y
`message.citation = report.toCitationStats(prepared.sources)`.

> **Fallback:** si `sourceIds` está vacío (mensajes viejos, anteriores a este cambio), el
> cruce por material usa el índice → `sources[index-1]` → se busca el material cuyo título
> coincida con el prefijo de la etiqueta. Si hay coincidencia múltiple, el fallo se atribuye al
> cajón "material desconocido" y no se cuela en la tabla.

### 7.5 Los agregados nuevos (`Analytics.kt`)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Analytics.kt   (modificado)

@Serializable
data class StudentAnalytics(
    // … campos existentes …
    /** Respuestas del modelo que no tuvieron ninguna cita (respondieron de memoria). */
    val repliesWithoutCitations: Int = 0,
    /** Motivos de fallo agregados del alumno: clave → citas. */
    val citationReasons: Map<String, Int> = emptyMap(),
) {
    // … propiedades existentes …
    /** Proporción de respuestas sin ninguna cita (0..1). */
    val uncitedAnswerRate: Double
        get() = if (replies == 0) 0.0 else repliesWithoutCitations.toDouble() / replies
}

@Serializable
data class CourseAnalytics(
    // … campos existentes …
    /** Respuestas del modelo sin ninguna cita. */
    val repliesWithoutCitations: Int = 0,
    /** Respuestas enteramente apoyadas en el material. */
    val answersGrounded: Int = 0,
    /** Respuestas con al menos una cita sin respaldo o sin fuente. */
    val answersProblematic: Int = 0,
    /** Motivos de fallo agregados de la cátedra: clave → citas. */
    val citationReasons: Map<String, Int> = emptyMap(),
) {
    /** Proporción de respuestas apoyadas en el material (0..1). */
    val groundedAnswerRate: Double
        get() = if (totalReplies == 0) 0.0 else answersGrounded.toDouble() / totalReplies
}
```

Todos los campos nuevos **tienen valor por defecto** → los `CourseAnalytics.json` /
`StudentAnalytics.json` viejos se leen sin problema.

### 7.6 Los tipos nuevos del resumen de calidad

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Citation.kt   (NUEVO, continuación)

/** Estado de una respuesta frente al material de la cátedra. */
enum class AnswerQuality(val label: String) {
    RESPALDADA("Se apoyó en el material"),
    SIN_RESPALDO("Afirmó cosas que el material no dice"),
    SIN_FUENTE("Cita sin fuente"),
    SIN_CITAS("No comportó ninguna cita"),
    ;

    companion object {
        /** Clasifica una respuesta. Precedencia: SIN_FUENTE > SIN_RESPALDO > SIN_CITAS. */
        fun of(stats: CitationStats): AnswerQuality = when {
            stats.unknown > 0 -> SIN_FUENTE
            stats.unsupported > 0 -> SIN_RESPALDO
            !stats.hasCitations -> SIN_CITAS
            else -> RESPALDADA
        }
    }
}

/** Calidad de las respuestas que citaron un material. */
@Serializable
data class MaterialQuality(
    val materialId: String,
    val title: String,
    /** Respuestas que citaron este material. */
    val answersUsing: Int = 0,
    /** De ellas, cuántas tuvieron al menos una cita sin respaldo. */
    val answersWithFailures: Int = 0,
    /** Citas totales a este material. */
    val citations: Int = 0,
    val citationsSupported: Int = 0,
    val citationsUnsupported: Int = 0,
    /** Motivos de fallo: clave → citas. */
    val reasons: Map<String, Int> = emptyMap(),
    /** Instante de la última respuesta con un fallo sobre este material. */
    val lastFailureAt: Long = 0L,
) {
    /** Proporción de respuestas que fallaron con este material (0..1). */
    val unsupportedAnswerRate: Double
        get() = if (answersUsing == 0) 0.0 else answersWithFailures.toDouble() / answersUsing

    /** Proporción de citas no respaldadas (0..1). */
    val unsupportedCitationRate: Double
        get() = if (citations == 0) 0.0 else citationsUnsupported.toDouble() / citations

    /** Con pocos datos no se marca como sospechoso. */
    val isSuspicious: Boolean
        get() = answersUsing >= MIN_MUESTRAS_MATERIAL && unsupportedAnswerRate >= 0.25

    /** Motivo con más citas fallidas. */
    val dominantReason: CitationReason?
        get() = reasons.entries
            .maxByOrNull { it.value }
            ?.key?.let { CitationReason.fromKey(it) }
}

/** Nº mínimo de respuestas que deben citar un material para sacar conclusiones. */
const val MIN_MUESTRAS_MATERIAL = 5

/** Calidad de las respuestas de un alumno. */
@Serializable
data class StudentQuality(
    val userId: String,
    val displayName: String,
    val answers: Int = 0,
    val grounded: Int = 0,
    val problematic: Int = 0,
    val uncited: Int = 0,
    val reasons: Map<String, Int> = emptyMap(),
)

/** Una respuesta problemática, con lo que el docente necesita para actuar. */
@Serializable
data class ProblematicAnswer(
    val messageId: String,
    val threadId: String,
    val courseId: String,
    val studentId: String,
    val studentName: String,
    val threadTitle: String,
    val createdAt: Long,
    /** Pregunta del alumno (vacía si el docente no puede ver el texto). */
    val question: String = "",
    /** Texto de la respuesta (vacío si el docente no puede ver el texto). */
    val answerText: String = "",
    val citation: CitationStats,
    val quality: AnswerQuality,
    /** Comprobaciones que fallaron (con snippet y extracto de la fuente). */
    val failedChecks: List<CitationCheckStats> = emptyList(),
    /** Materiales involucrados (para el filtro cruzado). */
    val materialIds: List<String> = emptyList(),
    /** true = el texto está oculto por la visibilidad del alumno. */
    val redacted: Boolean = false,
)

/** El resumen completo que consume la pantalla. */
@Serializable
data class QualityOverview(
    val courseId: String,
    /** Respuestas del asistente con verificación disponible. */
    val answers: Int = 0,
    /** Respuestas enteramente apoyadas en el material. */
    val grounded: Int = 0,
    /** Respuestas con al menos una cita sin respaldo o sin fuente. */
    val problematic: Int = 0,
    /** Respuestas sin ninguna cita. */
    val uncited: Int = 0,
    /** De las problemáticas, cuántas citaron una fuente inexistente. */
    val unknownSource: Int = 0,
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    /** Motivos de fallo agregados: clave → citas. */
    val reasons: Map<String, Int> = emptyMap(),
    /** Calidad por material (ordenada por tasa de respuestas sin respaldo). */
    val perMaterial: List<MaterialQuality> = emptyList(),
    /** Calidad por alumno. */
    val perStudent: List<StudentQuality> = emptyList(),
    /** El detalle: las respuestas problemáticas. */
    val problematicAnswers: List<ProblematicAnswer> = emptyList(),
) {
    /** Proporción de respuestas apoyadas en el material (0..1). */
    val groundedRate: Double get() = if (answers == 0) 0.0 else grounded.toDouble() / answers
    /** Proporción de respuestas problemáticas (0..1). */
    val problematicRate: Double get() = if (answers == 0) 0.0 else problematic.toDouble() / answers
    /** Proporción de respuestas sin ninguna cita (0..1). */
    val uncitedRate: Double get() = if (answers == 0) 0.0 else uncited.toDouble() / answers

    /** El texto del encabezado (§1.4), armado con los datos. */
    val headline: String
        get() = when {
            answers == 0 -> "Todavía no hay respuestas verificadas de la cátedra."
            else -> buildString {
                append("De $answers respuestas, ")
                append("$grounded ")
                append(if (grounded == 1) "se apoyó" else "se apoyaron")
                append(" en el material; ")
                append("$problematic ")
                append(if (problematic == 1) "afirmó" else "afirmaron")
                append(" cosas que el material no dice; ")
                append("$uncited ")
                append(if (uncited == 1) "no comportó" else "no comportaron")
                append(" ninguna cita.")
            }
        }

    /** Nota secundaria: cuántas de las problemáticas citaban una fuente inexistente. */
    val unknownSourceNote: String
        get() = if (unknownSource == 0) ""
        else "$unknownSource de las $problematic señaladas citaban una fuente que no existe."
}
```

### 7.7 Cómo se serializa sin romper lo existente

Las reglas del proyecto ya lo garantizan (`core/.../domain/JsonStore.kt`):

```kotlin
val KitoruJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true   // ← campos nuevos en archivos viejos: OK
    encodeDefaults = true      // ← los campos nuevos siempre se escriben
}
```

| Situación | Resultado |
|---|---|
| Se lee un `messages.json` viejo (sin `checks`, sin `reasons`, sin `sourceIds`) | `ignoreUnknownKeys` + valores por defecto ⇒ se lee perfecto; los mensajes viejos aparecen como "sin detalle" |
| Se escribe un mensaje nuevo | `checks`, `reasons`, `sourceIds` se escriben siempre (`encodeDefaults`) |
| Un mensaje viejo se abre en el panel | aparece en los agregados (los contadores `verified/unsupported/unknown` sí estaban) pero **no** en la lista de "respuestas problemáticas" con detalle, porque no hay `checks`. Se marca `redacted = true` / `sin detalle` |
| Se cambia el texto de un `CitationReason` (`label`, `teacherHint`) | no afecta a los datos: lo persistido es `key`, no el texto |
| Se **renombra** una `key` | hay que migrar; por eso la `key` se declara estable y no se renombra nunca |

**Por qué `Map<String, Int>` y no `Map<CitationReason, Int>`.** El mapa se persiste como objeto
JSON con claves de texto:

```json
"reasons": { "no_apoya": 7, "detalle_inventado": 3, "fuente_inexistente": 2 }
```

Con `Map<String, Int>`:

- una clave desconocida (de una versión futura del app, o de otra máquina) **no rompe** la
  lectura: queda en el mapa y el código la ignora (`reasonCounts` filtra por `fromKey`);
- el enum `CitationReason` puede cambiar de nombre, de texto o de cantidad de casos sin
  invalidar un solo archivo;
- el graficar por causa es trivial (`reasons["no_apoya"]`) y el JSON es legible por una
  persona.

El coste es que el compilador no garantiza que las claves sean válidas; se compensa con
`CitationReason.fromKey()` y con los accesores tipados (`reasonCounts`,
`MaterialQuality.dominantReason`).

**Tamaño en disco.** `checks` guarda por cita: índice, etiqueta, sección, snippet (≤400
caracteres), motivo y —**sólo si falla**— un extracto de la fuente (≤400 caracteres). El peor
caso son ~1 KB por cita fallida. Para 1.000 respuestas con 2 citas fallidas cada una: ~2 MB.
Es aceptable, pero si molesta, la alternativa es persistir `checks` **sólo** para las citas
que fallan y un `List<Int>` con los índices respaldados (bastante menos):

```kotlin
/** Alternativa liviana: índices de las citas respaldadas + detalle sólo de las que fallan. */
val supportedIndexes: List<Int> = emptyList(),
```

**Recomendación:** empezar con `checks` completo (la UI lo pide entero) y medir.

### 7.8 El analizador (función pura, en `:core`)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/QualityAnalyzer.kt   (NUEVO)

package kitoru.domain

/**
 * Calcula el resumen de calidad de las respuestas de una cátedra.
 *
 * Es una función **pura**, como `AnalyticsAnalyzer`: no toca disco, no llama
 * al modelo ni a la red. Los números del panel son reproducibles.
 *
 * Reglas:
 *  - Sólo se cuentan los alumnos cuya inscripción muestra métricas
 *    (`ChatVisibility.showsMetrics`); con `NONE` no aparece nada.
 *  - Si la inscripción es `METRICS_ONLY`, el docente ve los **números**
 *    pero no el texto: los campos de texto se vacían y se marca `redacted`.
 */
object QualityAnalyzer {

    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        materials: List<Material> = emptyList(),
    ): QualityOverview { /* … */ }
}
```

Algoritmo resumido:

```
por cada mensaje m con m.isAssistant y m.citation != null:
    enrollment = inscripción del autor del hilo
    si enrollment == null o !enrollment.visibility.showsMetrics: continue

    stats = m.citation
    quality = AnswerQuality.of(stats)

    answers      += 1
    si quality == RESPALDADA : grounded    += 1
    si quality == SIN_CITAS  : uncited     += 1
    si quality == SIN_RESPALDO o quality == SIN_FUENTE : problematic += 1
    si quality == SIN_FUENTE : unknownSource += 1

    citationsVerified   += stats.verified
    citationsUnsupported += stats.unsupported
    citationsUnknown    += stats.unknown
    reasons += stats.reasons

    // por material
    para cada check c de stats.checks:
        materialId = c.materialId o resolución por índice (§7.4)
        si materialId == null: continue
        qualityPorMaterial[materialId].citations += 1
        si c.supported: +citationsSupported
        else: +citationsUnsupported ; +answersWithFailures ; +reasons[c.reasonKind]

    // detalle
    si quality != RESPALDADA:
        problematicAnswers += ProblematicAnswer(...)
```

> **Los tres grupos son excluyentes y suman el total:** `grounded` (RESPALDADA) +
> `problematic` (SIN_RESPALDO o SIN_FUENTE) + `uncited` (SIN_CITAS) = `answers`. Por eso
> `grounded + problematic + uncited == answers` siempre, y por eso los dos casos de fallo con
> cita (sin respaldo y sin fuente) van juntos en el "afirmaron cosas que el material no dice".

El desglose por material se ordena por `unsupportedAnswerRate` descendente, con los
sospechosos (`isSuspicious`) primero.

### 7.9 Cambios en `Material` (para las acciones correctivas)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt   (modificado)

@Serializable
data class Material(
    // … campos existentes …
    /** El docente marcó que este material debe reindexarse. */
    val needsReindex: Boolean = false,
    /** Por qué (texto corto). */
    val reindexReason: String = "",
    /** Notas internas del docente sobre el material. */
    val notes: List<MaterialNote> = emptyList(),
)

@Serializable
data class MaterialNote(
    val id: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long,
    /** Fragmento de la respuesta que originó la nota (opcional). */
    val quote: String = "",
)
```

---

## 8. Composables, archivos y view-models

### 8.1 `:core` (dominio)

| Archivo | Estado | Contenido |
|---|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | modificado | `CitationStats` extendida (`checks`, `reasons` + derivados); `ChatMessage.sourceIds`; `role = "teacher"` permitido |
| `core/src/commonMain/kotlin/kitoru/domain/Citation.kt` | **nuevo** | `CitationReason`, `CitationCheckStats`, `AnswerQuality`, `MaterialQuality`, `StudentQuality`, `ProblematicAnswer`, `QualityOverview` |
| `core/src/commonMain/kotlin/kitoru/domain/QualityAnalyzer.kt` | **nuevo** | `QualityAnalyzer.analyze(...)` (función pura) |
| `core/src/commonMain/kotlin/kitoru/domain/Course.kt` | modificado | `Material.needsReindex`, `reindexReason`, `notes`; `MaterialNote` |
| `core/src/commonMain/kotlin/kitoru/domain/Analytics.kt` | modificado | `StudentAnalytics.repliesWithoutCitations`, `.citationReasons`; `CourseAnalytics.answersGrounded`, `.answersProblematic`, `.repliesWithoutCitations`, `.citationReasons` |

### 8.2 `:backend`

| Archivo | Estado | Contenido |
|---|---|---|
| `backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt` | modificado | `Verdict` interno; `CitationCheck.heading/reasonKind/coverage`; `CitationReport.reasons` |
| `backend/src/commonMain/kotlin/rag/engine/CitationStatsMapper.kt` | **nuevo** | `CitationReport.toCitationStats(sources)` |
| `backend/src/commonMain/kotlin/rag/retrieval/Models.kt` | modificado | `Doc.sourceId`, `Chunk.sourceId` |
| `backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt` | modificado | propaga `sourceId` al chunk |
| `backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt` | modificado | `CourseMaterialSource` setea `sourceId = material.id` |

### 8.3 `:ui` (aún no existe) — paquete `kitoru.ui.teacher.quality`

| Archivo | Composables | Qué hace |
|---|---|---|
| `TeacherQualityScreen.kt` | `TeacherQualityScreen(state, onEvent)` | contenedor: encabezado, pestañas, panel de tres columnas |
| `QualitySummaryCard.kt` | `QualitySummaryCard(overview)` | la tarjeta "De 100 respuestas…" + barra apilada + chips de causas |
| `QualityReasonBars.kt` | `QualityReasonBars(reasons, selected, onSelect)` | barras horizontales por causa (clicables = filtro) |
| `MaterialQualityTable.kt` | `MaterialQualityTable(rows, selected, onSelect, onAction)` | la tabla del §3, con la acción sugerida por fila |
| `ProblematicAnswersPanel.kt` | `ProblematicAnswersPanel(answers, filters, onFilter, onSelect)` | columna 1: lista + filtros |
| `ProblematicAnswerDetail.kt` | `ProblematicAnswerDetail(answer, onOpenMaterial)` | columna 2: respuesta, fragmento, fuente, motivo |
| `ProblematicAnswerRow.kt` | `ProblematicAnswerRow(answer)` | una fila de la lista |
| `CitationReasonChip.kt` | `CitationReasonChip(reason, count, selected, onClick)` | el chip de motivo (ámbar / rojo / gris) |
| `QualityActions.kt` | `QualityActions(answer, materials, onEdit, onReindex, onReply, onNote)` | columna 3: las cuatro acciones |
| `MaterialTextEditor.kt` | `MaterialTextEditor(material, onSave, onCancel)` | editor del texto extraído |
| `TeacherNoteDialog.kt` | `TeacherNoteDialog(material, quote, onSave)` | alta de nota interna |
| `TeacherReplyDialog.kt` | `TeacherReplyDialog(student, quote, onSend)` | respuesta al alumno |

### 8.4 `:ui` — paquete `kitoru.ui.chat` (lado alumno)

| Archivo | Composables | Qué hace |
|---|---|---|
| `CitationQualityBadge.kt` | `CitationQualityBadge(stats, onClick)` | el distintivo del chat (§4) |
| `CitationDetailSheet.kt` | `CitationDetailSheet(stats, sources, onOpenSource)` | la hoja con el detalle por cita + la explicación al alumno |
| `CitationMarker.kt` | `CitationMarker(index, state, onClick)` | el `[n]` clicable dentro del texto de la respuesta |

### 8.5 View-models

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/quality/TeacherQualityViewModel.kt

@Serializable
enum class QualityTab(val label: String) {
    PROBLEMATICAS("Respuestas problemáticas"),
    MATERIALES("Por material"),
    ALUMNOS("Por alumno"),
    CAUSAS("Por causa"),
}

/** Filtros del panel de tres columnas. */
data class QualityFilter(
    val reason: CitationReason? = null,
    val materialId: String? = null,
    val studentId: String? = null,
    val onlyUncited: Boolean = false,
    val search: String = "",
)

data class TeacherQualityUiState(
    val loading: Boolean = true,
    val overview: QualityOverview = QualityOverview(),
    val tab: QualityTab = QualityTab.PROBLEMATICAS,
    val filter: QualityFilter = QualityFilter(),
    val selectedAnswerId: String? = null,
    val selectedMaterialId: String? = null,
    val busy: Boolean = false,
    val feedback: String? = null,     // mensaje de resultado de una acción
)

class TeacherQualityViewModel(
    private val courseId: String,
    private val enrollmentRepository: EnrollmentRepository,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val materialRepository: MaterialRepository,
) {
    val state: StateFlow<TeacherQualityUiState>

    /** Recarga el resumen (función pura: `QualityAnalyzer.analyze`). */
    fun refresh()

    fun selectTab(tab: QualityTab)
    fun selectAnswer(messageId: String?)
    fun setReasonFilter(reason: CitationReason?)
    fun setMaterialFilter(materialId: String?)
    fun setStudentFilter(studentId: String?)
    fun setSearch(text: String)

    // ─── Acciones correctivas (§6) ───
    /** Reabre el material y guarda el texto editado (marca reindexar). */
    suspend fun editMaterialText(materialId: String, newText: String): Result<Unit>
    /** Marca el material para reindexar. */
    suspend fun markForReindex(materialId: String, reason: String): Result<Unit>
    /** Responde al alumno en su hilo (mensaje con role = "teacher"). */
    suspend fun replyToStudent(threadId: String, text: String): Result<Unit>
    /** Añade una nota interna al material. */
    suspend fun addNote(materialId: String, text: String, quote: String): Result<Unit>
}
```

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/quality/MaterialEditorViewModel.kt

data class MaterialEditorUiState(
    val materialId: String = "",
    val title: String = "",
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    val text: String = "",
    val originalText: String = "",
    val needsReindex: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

class MaterialEditorViewModel(
    private val materialRepository: MaterialRepository,
) {
    val state: StateFlow<MaterialEditorUiState>
    fun load(materialId: String)
    fun onTextChange(text: String)
    fun save()          // → material.copy(extractedText = …, needsReindex = true)
}
```

> El `ChatViewModel` del alumno no cambia: ya recibe `CitationStats` en el mensaje; sólo se le
> agrega el composable `CitationQualityBadge` a la burbuja.

### 8.6 Dónde vive la lógica y por qué

| Capa | Responsabilidad |
|---|---|
| `:backend` | **calcular** el veredicto (ya lo hace) y **mapearlo** al tipo persistible |
| `:core` | **modelar** el resultado y **agregarlo** (`QualityAnalyzer`, función pura, testeable al 100 %) |
| `:ui` | **mostrarlo** y **actuar** sobre los materiales y los hilos |

Regla del proyecto respetada: *"la analítica y la verificación de citas no pueden depender del
modelo ni de la red: son funciones puras y testeables"*. `QualityAnalyzer` cumple: recibe
datos, devuelve datos.

---

## 9. Visibilidad y privacidad (regla dura)

La visibilidad por inscripción **manda** sobre el panel. No es una excepción del panel: es la
regla del producto.

| Inscripción | ¿El docente ve los números de calidad? | ¿Ve el texto de las respuestas? | ¿Ve el fragmento que falló? | ¿Puede responder al alumno? |
|---|---|---|---|---|
| `FULL` | sí | sí | sí | sí, con el fragmento precargado |
| `METRICS_ONLY` | sí | no | no (se muestra el material y los contadores) | sí, pero **sin** fragmento precargado |
| `NONE` | no | no | no | sí, como mensaje general en el hilo (si el docente lo busca por otros medios) — o no, según la política de la cátedra |

Reglas de implementación:

1. `QualityAnalyzer` **excluye** por completo a los alumnos con `visibility == NONE`: no
   aportan a ningún agregado, ni siquiera al total.
2. Con `METRICS_ONLY`, los mensajes siguen contando para los agregados, pero
   `ProblematicAnswer.question`, `.answerText` y `CitationCheckStats.snippet` /
   `.sourceExcerpt` se vacían y se marca `redacted = true`. La UI muestra un candado con el
   motivo: *"El alumno dejó esta conversación en modo «solo métricas»: se ven los números,
   no el texto."*
3. El docente **no puede** saltarse la visibilidad desde el panel: no hay un conmutador
   "ver todo". Si necesita el texto, el alumno tiene que cambiar su inscripción.
4. Las **notas internas** (`MaterialNote`) y el texto del **material** son del docente: no
   dependen de la visibilidad de los alumnos.
5. El **cruce por material** hereda la regla: si un material falla sólo en respuestas de
   alumnos con `NONE`, ese material no aparece en la tabla (los datos de ese alumno no son
   del docente). Con `METRICS_ONLY`, el material **sí** aparece: el docente ve que el
   material falla, sin ver qué dijo el alumno.

---

## 10. Casos borde

| Caso | Qué muestra el panel |
|---|---|
| Un mensaje de respuesta **sin** `citation` (respuesta en modo general, sin RAG, o de una versión vieja) | no cuenta en ningún agregado. No es "una respuesta sin citas": es una respuesta **no verificada**. El panel distingue "no cites" de "no sé" |
| Un mensaje viejo (persistido antes de este diseño) | cuenta en los agregados (los contadores existían) pero no aparece en la lista de detalle (no hay `checks`). Se marca `sin detalle` |
| Un material con **el mismo título** que otro | se desambigua con `sourceId`; si no hay `sourceId` (datos viejos), el fallo va al cajón "material desconocido" |
| Un material **nunca citado** | no aparece en la tabla de calidad (no hay exposición). Aparece en la lista de materiales con "0 citas" |
| Un material **sin texto extraído** (`extractedText` vacío o con `warning`) | aparece en la tabla con `fuente_vacia` y la acción "Reindexar" destacada |
| La misma cita repetida (`[1] ... [1]`) | son **dos** comprobaciones (dos apariciones). `unsupported` las cuenta como dos. `unknown` cuenta índices **distintos** (diferencia semántica ya presente en el código: se documenta en el panel, no se cambia) |
| El modelo responde `[0]` | `fuente_inexistente` (el índice 0 no existe) |
| Un fragmento con **0 palabras significativas** | el verificador lo considera respaldado (`words.isEmpty() → true`): no aparece como fallo |
| Una cita que apunta a una fuente **vacía** | `fuente_vacia` |
| El `snippet` supera 400 caracteres | se trunca (ya lo hace `MAX_SNIPPET_CHARS`); la UI muestra "…" |
| Cambia un material (se edita o se reindexa) | las respuestas viejas **no** se recalculan: el veredicto histórico es el que se dio. El panel muestra la fecha del último fallo para entender que el material ya cambió |
| La cátedra tiene `analyticsEnabled = false` | el panel no muestra nada (ni números). La analítica es una decisión de la cátedra |

---

## 11. Criterios de aceptación

**Lógica (`:backend` / `:core`, testeable)**

1. `CitationReport.toCitationStats()` produce `verified`, `unsupported` y `unknown` idénticos a
   los del reporte, y `checks` con una entrada por aparición de marcador.
2. Cada `reasonKind` corresponde al mensaje real del verificador:
   `no_apoya` ⇔ el mensaje empieza con "El fragmento no aparece en la fuente";
   `detalle_inventado` ⇔ "La cita menciona";
   `fuente_inexistente` ⇔ "No hay una fuente"; `fuente_vacia` ⇔ "La fuente está vacía".
3. `CitationStats.reasons` es la suma de los `reasonKind` de los `checks` fallidos.
4. `QualityAnalyzer.analyze` es **determinista**: dos llamadas con los mismos datos dan el
   mismo `QualityOverview` (test de "golden").
5. La suma `grounded + problematic + uncited == answers` siempre.
6. Un alumno con `ChatVisibility.NONE` no aparece en ningún agregado; con `METRICS_ONLY`
   aparece en los números pero sus textos quedan vacíos y `redacted == true`.
7. `MaterialQuality.unsupportedAnswerRate` coincide con el cálculo manual sobre los mensajes
   de ejemplo.
8. `isSuspicious` es `false` con menos de 5 respuestas que citan el material.

**Serialización**

9. Un `messages.json` viejo (sin `checks`, `reasons`, `sourceIds`) se lee sin excepción y
   produce los mismos agregados que antes.
10. Un `messages.json` con `reasons` desconocido (`{"motivo_nuevo": 3}`) se lee sin excepción
    y la clave desconocida no aparece en los gráficos.

**UI**

11. El encabezado dice exactamente la frase del §1.4 con los números del conjunto de datos.
12. La tabla por material ordena por tasa descendente, marca los sospechosos y ofrece la
    acción sugerida.
13. Click en una fila de la tabla filtra la lista de respuestas problemáticas por ese material.
14. El distintivo del chat muestra los tres copy exactos del §4.4 y abre el detalle por cita.
15. Las cuatro acciones del §6 funcionan y persisten: el texto editado, la marca de reindexar,
    el mensaje `role = "teacher"` en el hilo del alumno y la nota interna.

---

## 12. Riesgos y límites

| Riesgo | Mitigación |
|---|---|
| **Falsos positivos** del verificador en paráfrasis legítimas | el motivo `no_apoya` muestra el % de coincidencia; el docente ve el fragmento y la fuente lado a lado y decide. Un caso frecuente en un material concreto es la señal para **re-chunkear**, no para juzgar al modelo |
| Que el docente interprete "40 % sin respaldo" como "40 % de alumnos suspendidos" | el copy es explícito ("de las respuestas que citaron este material") y la vista por material está separada de la vista por alumno |
| Que el panel se use para vigilar alumnos | los números agregados son de **material**, no de personas; la vista por alumno es un desglose, y los datos de un alumno con `NONE` no existen para el docente |
| Crecer el `messages.json` | `checks` sólo para lo necesario, extracto sólo en fallos, tope de 400 caracteres; alternativa liviana documentada en §7.7 |
| Que el panel dé una falsa sensación de "calidad de la IA" | el subtítulo dice "comparación de textos", no "evaluación"; el panel mide *apoyo en el material*, no *corrección científica* |
| Reindexar mientras los alumnos chatean | el `KnowledgeBase` ya se reindexa en caliente (`sync`); el material queda con la insignia ⟳ hasta que termine |
