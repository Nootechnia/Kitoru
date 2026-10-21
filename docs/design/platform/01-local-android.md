# 01 — Inferencia local en Android

> Subagente: diseño de cómo se hace **inferencia local real** en la app Android
> de Kitoru (Kotlin Multiplatform + Compose Multiplatform).
> Módulos tocados: `:core` (plataforma), `:llm-api` (gateway + motor local),
> `:backend` (RAG, sólo lectura), `:android` (app, todavía no construido).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *fail-fast con mensajes en español*, *el estudiante conserva agencia*,
> *el docente no ve lo que no debe*.
> Referencia de código reutilizable:
> `FunshiEngineAI/app-home/llama.cpp/examples/llama.android/` (el ejemplo oficial
> de llama.cpp para Android, que ya está vendorizado en el repo hermano).

---

## 0. Resumen ejecutivo

| Decisión | Respuesta corta |
|---|---|
| ¿Qué motor? | **`ggml-org/llama.cpp` con binding JNI** (opción a). Es el único que nos deja reutilizar el catálogo GGUF de FunshiEngineAI, el `Bm25Index`, el verificador de citas y el bucle de herramientas sin reescribirlos. |
| ¿Se reutiliza el ejemplo `llama.android`? | **Parcialmente**: el CMake, el esqueleto JNI y el lector GGUF se reutilizan casi tal cual; el **bucle de chat hay que reescribirlo** porque el ejemplo resetea el contexto en cada turno y no soporta historial ni KV-cache reutilizable. |
| ¿Gateway local por HTTP o in-process? | **In-process.** En Android no se puede lanzar `llama-server` como proceso hijo, y un servidor HTTP en loopback dentro de la app sólo agrega copies, ports y políticas de red cleartext. El seam ya existe: `LocalModelService.start()` devuelve un `LlmGateway`, y en Android ese gateway habla con la librería nativa directamente. |
| ¿Qué modelo? | **Qwen2.5 1.5B Instruct Q4_K_M (Apache-2.0, 1,1 GB)** como default del teléfono; **Phi-4-mini Q4_K_M (MIT, 2,4 GB)** como opción "calidad" para teléfonos de ≥6 GB. |
| ¿Riesgo mayor? | El build nativo (NDK + llama.cpp + CMake). Mitigación: **el hito 1 se asume con modo remoto únicamente en Android**; la IA local es una función de autonomía del alumno, no del producto institucional. |
| ¿Cuándo se entrega? | Fase 0 (hoy, stub con mensaje claro) → Fase 1 (motor in-process, 1 chat, arm64 CPU) → Fase 2 (descarga con reanudación y sha256) → Fase 3 (RAG local + herramientas). |

---

## 1. Punto de partida: qué hay y qué falta

### 1.1 Lo que ya está construido

| Pieza | Ubicación | Estado |
|---|---|---|
| Contrato `LlmGateway` (`chatStream` → `Flow<LlmChunk>`) | `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` | ✅ listo, tipado, con `LlmChunk.Text` / `LlmChunk.ToolCall` |
| Gateway remoto OpenAI-compatible (SSE) | idem, `OpenAiCompatibleGateway` | ✅ listo, `ktor-client-okhttp` en Android |
| `LocalModelService` (`start` → `LlmGateway`, `stop`, `running`) | `llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` | ✅ interfaz; `expect fun createLocalModelService()` |
| Local remoto (escritorio) = `llama-server` como proceso hijo | `llm-api/src/desktopMain/.../LocalModelService.desktop.kt` | ✅ funcionando |
| Stub de Android | `llm-api/src/androidMain/.../LocalModelService.android.kt` | ⚠️ lanza `LlmException(NO_CONFIGURADO)` |
| `Bm25Index`, `Chunker`, `KnowledgeBase`, `RagEngine` | `backend/src/commonMain/kotlin/rag/retrieval/` | ✅ **100 % portables** (Kotlin común, sin JVM) |
| `CitationVerifier` (determinista) | `backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt` | ✅ portable |
| `AgentLoop` con modo nativo y modo prompt | `backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt` | ✅ portable, con `nativeToolCalls: Boolean` |

### 1.2 Lo que falta para Android

1. **Un motor nativo**: la librería llama.cpp compilada para `arm64-v8a` con un
   binding JNI que hable el protocolo de chat (multi-turno, templates,
   streaming token a token, cancelación).
2. **Un `LlmGateway` in-process** que lo envuelva.
3. **Un contexto de plataforma** para que `:llm-api` sepa dónde está la carpeta
   de modelos y dónde están las bibliotecas nativas (`getExternalFilesDir`,
   `nativeLibraryDir`). Hoy `expect fun createLocalModelService()` no recibe nada.
4. **La descarga del modelo** con reanudación, verificación sha256 y control de
   espacio en disco.
5. **La política de recursos** (ciclo de vida, `onTrimMemory`, ahorro de batería).

### 1.3 Restricciones del proyecto que condicionan el diseño

| Restricción | Consecuencia |
|---|---|
| `minSdk = 24` | llama.cpp se compila con `-DANDROID_PLATFORM=android-28`. **Hay que subir `minSdk` a 28** (Android 9, 2018) o usar flavors. Recomendación: subir a 28. |
| `compileSdk = 35`, `targetSdk` presumiblemente 35 | En Android 14+ (API 34) un foreground service necesita `foregroundServiceType` explícito y permiso propio; en Android 15 el tipo `dataSync` tiene tope de 6 h por 24 h. |
| Nada de `java.nio.file` en `commonMain` | `java.nio.file.Path` existe en Android **desde API 26**. Con `minSdk = 28` ya no es problema, pero el código nuevo en `jvmCommonMain` debería usar `java.io.File` para no atar el módulo a una API. |
| `rules/2` (portable por defecto) | Todo el C++/JNI queda en `androidMain`. El `commonMain` sólo ve interfaces Kotlin. |
| `rules/5` (el estudiante conserva agencia) | La IA local es opt-in del alumno; si la cátedra la bloquea, se explica (ver `docs/design/teacher/05-config-modelo.md` §10.2). |

---

## 2. Opciones de motor en Android

### 2.1 (a) `ggml-org/llama.cpp` con binding JNI

**Qué es.** Se compila `llama.cpp` con el NDK de Android como `.so` compartido y
se expone con JNI. Es exactamente lo que hace
`FunshiEngineAI/app-home/llama.cpp/examples/llama.android/`:
`lib/src/main/cpp/CMakeLists.txt` compila `ai_chat.cpp` + el árbol de llama.cpp
(`add_subdirectory(${LLAMA_SRC} build-llama)`), y
`lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt` es el binding
Kotlin (`external fun load(...)`, `prepare()`, `processSystemPrompt(...)`,
`processUserPrompt(...)`, `generateNextToken()`).

**Ventajas**

- **Es el único con el que el catálogo de modelos de FunshiEngineAI sirve tal
  cual**: los GGUFs (`unsloth/Phi-4-mini-instruct-GGUF`, `unsloth/Qwen3-4B-Instruct-2507-GGUF`,
  `unsloth/Llama-3.2-3B-Instruct-GGUF`) y el `HuggingFaceDownloader` que ya
  existen. No hay que convertir nada a `.task` ni a ONNX.
- **KleidiAI**: el build del ejemplo activa `GGML_CPU_KLEIDIAI=ON` para
  `arm64-v8a`, que es la aceleración de CPU de Arm (dotprod/i8mm/SVE). El
  `docs/build.md` de llama.cpp dice que el binding soporta "hardware acceleration
  up to SME2 for Arm" y "runs seamlessly on both the latest premium devices and
  older devices that may lack modern CPU features".
- **Selección de variante de CPU en runtime**: con `GGML_BACKEND_DL=ON` +
  `GGML_CPU_ALL_VARIANTS=ON` se empaquetan varias `.so` (`libggml-cpu-*.so`) y
  `ggml_backend_load_all_from_path(nativeLibraryDir)` elige la mejor para el
  dispositivo. Un solo APK sirve para un Snapdragon de gama baja y para un
  flagship.
- **mmap del GGUF**: los pesos no se copian al heap Java; quedan en el page cache
  y el kernel los puede reclaimar bajo presión de memoria. Es una ventaja real
  frente a runtimes que copian tensores.
- **Vulkan**: existe el backend Vulkan de llama.cpp (fase 3, experimental).
- **Tool-calling nativo**: se puede hacer con gramáticas GBNF (llama.cpp trae
  `common_chat_templates` + gramáticas). Es trabajo extra, no un blocker (el
  `AgentLoop` ya tiene modo prompt).
- Licencia MIT: sin fricción para una app que se distribuye en una universidad.

**Desventajas**

- Hay que **vendorizar llama.cpp** (submodule o tarball pinneado) y aprender el
  build CMake/NDK de llama.cpp.
- `minSdk` sube a 28.
- El APK crece: la `.so` de llama.cpp + las variantes de CPU ≈ 6–12 MB por ABI
  (arm64-v8a solamente).
- El `api_chat.cpp` del ejemplo **no sirve tal cual para chat multi-turno** (§3.3).
- C++ propio que mantener (API de llama.cpp cambia rápido: hay que re-pinnear el
  commit y recompilar).

### 2.2 (b) MediaPipe LLM Inference (Google)

**Qué es.** `com.google.mediapipe:tasks-genai:0.10.27`. API Kotlin:
`LlmInference.createFromOptions(context, LlmInferenceOptions.builder().setModelPath(...).build())`,
`generateResponseAsync(input)` con `setResultListener { partial, done -> }`.
Los modelos son `.task` (FlatBuffer) o `.litertlm`, ajustados con el conversor
de MediaPipe; se bajan de `litert-community` en Hugging Face.

**Ventajas**

- **Es la opción con menos esfuerzo de integración**: una dependencia de Maven y
  una API Kotlin. Cero NDK, cero CMake, cero vendorización.
- GPU delegate (Vulkan por atrás) en el paquete Maven (el API C no tiene GPU).
- Streaming con `ResultListener` (devuelve el acumulado, no deltas).
- TTS/VAD y LoRA incluidos.

**Límites (decisivos para Kitoru)**

- **La API está en *maintenance-only mode***. La propia documentación de Google
  (actualizada 2026-06-12) avisa: *"The MediaPipe LLM Inference API is in
  maintenance-only mode. We recommend migrating your Android projects to
  LiteRT-LM Android (Kotlin) API"*. Google está empujando LiteRT-LM: si
  arrancamos con MediaPipe, vamos a migrar.
- **No hay tool-calling.** El protocolo `tools[]` no existe en la API. Eso rompe
  el modo nativo del `AgentLoop`; sólo queda el modo prompt (`ToolCallSanitizer`),
  que es peor.
- **El modelo no es un GGUF**: hay que convertir (o descargar pre-convertido) y
  el catálogo de la cátedra (`ModelPolicy.hfRepo` / `hfFile`) es de GGUF. El
  catálogo de modelos de la cátedra (§7 de `05-config-modelo.md`) quedaría
  partido entre dos formatos.
- **Contexto chico**: `maxTokens` es *entrada + salida*combined, con default
  **512**. Para Kitoru (RAG con 5 fragmentos de 1200 caracteres + historial) eso
  es insuficiente; hay que subirlo a 2048–4096 y el modelo lo tiene que
  suportar.
- **Orientado a gama alta**: *"optimized for high-end Android devices, such as
  Pixel 8 and Samsung S23 or later, and does not reliably support device
  emulators"*.
- `maxTokens` default 512, `topK` 40, `temperature` 0.8, sin `seed` control
  determinista.
- Licencia del motor Apache-2.0 (bien), pero los modelos de Google (Gemma) tienen
  la licencia Gemma, con restricciones de uso.

**Veredicto:** excelente para una demo y para un "modo liviano" sin
herramientas; **incorrecto como el motor principal de Kitoru** por el tool
-calling y por el formato de modelo incompatible con el catálogo.

### 2.3 (c) ONNX Runtime GenAI mobile + modelo ONNX cuantizado

**Qué es.** `microsoft/onnxruntime-genai` (MIT). API Java:
`Model(dir)`, `Tokenizer(model)`, `Generator(model, params)`, `append_tokens()`,
`generate_next_token()`, `get_next_tokens()`. Modelos: `cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4`
de `microsoft/Phi-3-mini-128k-instruct-onnx`.

**Ventajas**

- MIT, y **soporta constrained decoding con gramáticas**, que es la base
  natural para el tool-calling nativo.
- La matriz de soporte incluye **QNN** (NPU de Snapdragon) y **NNAPI** vía
  Delegates: es la ruta con más futuro para inferencia rápida en Android.
- Multi-LoRA y continuous decoding.

**Límites (decisivos para Kitoru)**

- **La API Java para Android "requires build from source"** (nota al pie de la
  matriz de soporte del propio repo). No hay AAR listo: hay que cross-compilar
  ORT + GenAI para `arm64-v8a` con el NDK. Eso es un proyecto entero.
- Los modelos son **ONNX**, no GGUF: de nuevo, catálogo partido.
- Los modelos `cpu_and_mobile` int4 de Phi-3-mini son de ~2,3 GB y el proceso
  de conversión (optimum → ONNX → cuantización RTN) no es trivial de automatizar
  para el catálogo de la cátedra.
- El soporte de arquitecturas es más chico que el de llama.cpp (aunque ya lista
  Llama, Qwen, Phi, Gemma, Mistral, DeepSeek).

**Veredicto:** la mejor bets **a futuro** si queremos NPU (QNN), pero es la
opción más cara de arrancar. Se deja como fase 4 o como motor alternativo
detrás de la misma interfaz.

### 2.4 (d) `llama-cpp-android` del mercado

**Qué es.** AARs prebuilt publicados en Maven Central (por ejemplo
`dev.ffmpegkit-maintained:llama-cpp-android`, que aparece en la búsqueda de
Maven Central). Traen llama.cpp compilado con un binding Kotlin.

**Ventajas**

- Cero build nativo: `implementation("...:llama-cpp-android:x.y.z")` y listo.
- El GGUF es el mismo formato → el catálogo de la cátedra sirve sin cambios.

**Límites**

- **No podemos patchear el JNI.** Y el patch es justamente lo que hace falta:
  el ejemplo upstream no soporta historial multi-turno con KV-cache reutilizable
  (§3.3). Un AAR de terceros cuya API no exponés no sirve.
- **No controlamos la versión de llama.cpp** embebida. El `common_chat_templates`
  con Jinja (necesario para los templates de Phi-4 / Qwen / Llama 3.2) es una
  API que cambia entre releases; quedaríamos atados a lo que publique el
  mantenedor.
-|Проvenance y mantenimiento dudosos: un artefacto binario de terceros en el
  APK de una app universitaria es un riesgo de cadena de suministro.

**Veredicto:** se puede hacer un **spike de 1 día** para medir si la API
expuesta alcanza, pero **no se elige como motor primario**. Si el spike sale
bien, sirve como *fallback* para builds sin NDK.

### 2.5 Tabla comparativa

**Escala de esfuerzo**: 1 = 1 día · 2 = 2–4 días · 3 = 1 semana · 4 = 2–3 semanas · 5 = 1 mes+.

| | **(a) llama.cpp + JNI** | **(b) MediaPipe** | **(c) ONNX RT GenAI** | **(d) AAR del mercado** |
|---|---|---|---|---|
| **Esfuerzo de integración** | **4** | **1** | **5** | **2** |
| Motor / formato | llama.cpp / **GGUF** | MediaPipe tasks-genai / **`.task`**, `.litertlm` | ORT GenAI / **ONNX int4** | llama.cpp / GGUF |
| Tamaño de modelo típico | 1,1–2,4 GB | 0,8–2,0 GB | 1,7–2,3 GB | 1,1–2,4 GB |
| Velocidad (gama media, CPU) | 3–10 tok/s | 5–15 tok/s | 4–12 tok/s | 3–10 tok/s |
| Velocidad (gama alta, GPU) | 8–22 tok/s (Vulkan) | 10–25 tok/s | 6–15 tok/s (CPU), más con QNN | 8–22 tok/s |
| Aceleración NPU | ❌ (Vulkan sí) | ❌ (GPU delegate) | ✅ **QNN / NNAPI** | ❌ |
| **Tool-calling nativo** | ✅ posible (GBNF, fase 3) | ❌ **no existe** | ✅ gramáticas | depende del binding |
| Multi-turno con KV reutilizable | ✅ (hay que implementarlo) | ✅ (API de sesión) | ✅ (nativo) | ✅/⚠️ |
| Licencia del motor | MIT | Apache-2.0 | MIT | MIT + ? (mantenedor third-party) |
| Licencia de los modelos | MIT / Apache-2.0 (ideal) | Gemma (restricciones) | MIT / Apache-2.0 | MIT / Apache-2.0 |
| **Integración KMP** | Media: todo en `androidMain` + `expect/actual`; `commonMain` intacto | Baja: exponés una API Java como `LlmGateway` | Media-alta: compilar un AAR | Alta: Kotlin directo |
| Toca el catálogo GGUF | ❌ no | ✅ sí (rompe) | ✅ sí (rompe) | ❌ no |
| `minSdk` | 28 (por el NDK) | 24 | 26+ | 24 |
| Tamaño del APK | +8–14 MB (arm64) | +6–10 MB | +10–20 MB | +8–14 MB |
| Riesgo de suministro | Bajo (vendorizamos el código) | Bajo (Google) | Bajo (Microsoft) | **Alto** (binario de terceros) |
| Estado del proyecto upstream | Activo | **Maintenance-only** (→ LiteRT-LM) | Activo | Variable |

### 2.6 Decisión

**Se elige (a) llama.cpp con binding JNI**, y (b) MediaPipe queda anotada como
"modo liviano" futuro para un modelo chico sin herramientas, y (c) ONNX Runtime
GenAI como ruta futura si queremos NPU.

Tres razones, en orden de peso:

1. **Coherencia con el resto de Kitoru.** El `ModelPolicy` ya tiene
   `hfRepo`/`hfFile` con defaults GGUF (`unsloth/Phi-4-mini-instruct-GGUF`), el
   `HuggingFaceDownloader` de FunshiEngineAI baja GGUFs con reanudación, el
   `Bm25Index` y el `CitationVerifier` son portables, y el `AgentLoop` habla
   el protocolo OpenAI. llama.cpp es el motor que no obliga a tocar nada de eso.
2. **Tool-calling.** Sin `tools[]` el `AgentLoop` pierde el modo nativo. llama.cpp
   puede hacerlo con gramáticas GBNF.
3. **Control.** El build nativo es el riesgo, pero es un riesgo *nuestro* y
   acotado (un `CMakeLists.txt` copiado del ejemplo + un `FetchContent` pinneado
   a un commit). Un AAR de terceros nos deja sin poder corregir el JNI, que es
   justamente lo que hay que corregir.

**Criterio de reversión:** si en la Fase 1 el build nativo no cierra en 2 semanas,
se cae a (b) MediaPipe con Gemma 3 1B en "modo liviano" (sin herramientas), y el
modo local de Android queda documentado como no disponible.

---

## 3. Análisis de reutilización del ejemplo `llama.android`

### 3.1 Mapa de archivos del ejemplo

```
FunshiEngineAI/app-home/llama.cpp/examples/llama.android/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── app/                                  (app de ejemplo, no reutilizable)
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml      (extractNativeLibs="true")
│   └── src/main/java/com/example/llama/MainActivity.kt
└── lib/                                  ← ESTE ES EL MÓDULO REUTILIZABLE
    ├── build.gradle.kts                   (externalNativeBuild + CMake args)
    ├── consumer-rules.pro, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt             (add_subdirectory de llama.cpp)
        │   ├── ai_chat.cpp                (el JNI: 565 líneas)
        │   └── logging.h                  (bridge de logs a logcat)
        └── java/com/arm/aichat/
            ├── AiChat.kt                  (fachada: getInferenceEngine(context))
            ├── InferenceEngine.kt         (interfaz + State sealed class)
            ├── internal/InferenceEngineImpl.kt  (binding JNI, 324 líneas)
            ├── internal/gguf/GgufMetadataReaderImpl.kt
            └── gguf/{GgufMetadata, GgufMetadataReader, FileType}.kt
```

### 3.2 Qué se reutiliza (casi) tal cual

| Archivo | Reutilización | Notas |
|---|---|---|
| `lib/build.gradle.kts` | **95 %** | Copiar el bloque `externalNativeBuild` + los argumentos CMake. Ajustar `namespace`, `minSdk` (33 → 28) y `abiFilters` (dejar sólo `arm64-v8a` + opcional `x86_64` para emulador). |
| `lib/src/main/cpp/CMakeLists.txt` | **90 %** | Cambiar `LLAMA_SRC` (apunta a `../../../../../../`, relativo al checkout de llama.cpp; en Kitoru va a un `FetchContent` pinneado) y el nombre de la librería (`ai-chat` → `kitoru_llm`). **Mantener** `GGML_CPU_KLEIDIAI=ON` para arm64 y `GGML_OPENMP=ON`. |
| `lib/src/main/cpp/logging.h` | **100 %** | Bridge `llama_log_set` → `logcat`. Se usa tal cual. |
| `lib/src/main/java/com/arm/aichat/gguf/*` | **100 %** | `GgufMetadataReader` es Kotlin puro (lee el header GGUF: arquitectura, `n_ctx_train`, cuantización, chat template). Sirve para la pantalla "info del modelo" y para **validar que el GGUF es compatible antes de cargarlo**. Renombrar el paquete a `llm.local`. |
| `lib/src/main/java/com/arm/aichat/InferenceEngine.kt` | **50 %** | La máquina de estados (`Uninitialized → Initialized → LoadingModel → ModelReady → Generating → Error`) es buena y se reutiliza como modelo de la UI. **Se le agrega** el estado `Unloading` y se le sacan `bench()` y `setSystemPrompt()` (ver abajo). |
| `lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt` | **40 %** | La envoltura JNI y el `Dispatchers.IO.limitedParallelism(1)` (serializa el acceso al nativo: imprescindible) se reutilizan. El `State` queda; la lógica de turnos se reescribe. |
| `lib/src/main/AndroidManifest.xml` | **100 %** | Trivial. |
| `lib/src/main/cpp/ai_chat.cpp` | **~25 %** | Ver §3.3: se conserva la mécanique JNI (búsqueda de `Java_..._` por firma, `NewStringUTF`, la lógica de UTF-8 parcial con `cached_token_chars`, el log a logcat) pero **se reescribe el bucle de chat**. |

**Detalle importante del CMake del ejemplo** (`lib/src/main/cpp/CMakeLists.txt`):

```cmake
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)      # ← aceleración de Arm: clave para el rendimiento
    set(GGML_OPENMP ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    ...
endif()
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)
add_subdirectory(${LLAMA_SRC} build-llama)
add_library(${CMAKE_PROJECT_NAME} SHARED ai_chat.cpp)
target_link_libraries(${CMAKE_PROJECT_NAME} llama llama-common android log)
```

Y del `build.gradle.kts` del ejemplo, los argumentos CMake que importan:

```
-DCMAKE_BUILD_TYPE=Release
-DBUILD_SHARED_LIBS=ON
-DLLAMA_BUILD_APP=OFF  -DLLAMA_BUILD_COMMON=ON   # ← common: chat templates + sampler
-DLLAMA_OPENSSL=OFF
-DGGML_NATIVE=OFF  -DGGML_BACKEND_DL=ON  -DGGML_CPU_ALL_VARIANTS=ON  -DGGML_LLAMAFILE=OFF
```

`LLAMA_BUILD_COMMON=ON` es **imprescindible**: `common_chat_templates`, `common_sampler_*` y `common_batch` viven en `llama-common`.

### 3.3 El gap crítico: el ejemplo no hace chat multi-turno

Éste es el hallazgo central del análisis, y es lo que define el esfuerzo real.

El `ai_chat.cpp` del ejemplo tiene **estados de largo plazo** y **de corto plazo**:

```cpp
static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    if (clear_kv_cache) llama_memory_clear(llama_get_memory(g_context), false);
}
```

Y el flujo es:

1. `processSystemPrompt(prompt)`: **resetea** los estados de largo plazo (KV cache
   incluido), tokeniza el prompt de sistema con el template y lo decodifica.
2. `processUserPrompt(prompt, n_predict)`: decodifica el prompt del usuario
   sobre el contexto.
3. `generateNextToken()`: muestrea y decodifica de a un token, acumulando el
   texto; cuando encuentra EOG, llama `chat_add_and_format(ROLE_ASSISTANT, ...)`
   — que **sólo agrega el mensaje al vector `chat_msgs` para el template, sin
   decodificarlo**.

**Consecuencias para Kitoru:**

| Necesidad de Kitoru | ¿El ejemplo lo resuelve? |
|---|---|
| Historial de la conversación (turnos previos del alumno y del asistente) | ❌ No hay forma de alimentar turnos anteriores: `processUserPrompt` sólo formatea un mensaje `user` suelto. |
| Prompt de sistema que cambia por turno (el RAG inyecta fragmentos distintos en cada pregunta) | ❌ `processSystemPrompt` resetea el KV cache y **no puede llamarse en medio de una conversación**. |
| KV-cache reutilizable entre turnos (barato) vs. recalcular todo (caro) | ❌ Cada `processUserPrompt` decodifica desde `current_position`, pero como el ejemplo nunca decodifica la respuesta del asistente, `current_position` no refleja lo que realmente está en la KV cache. No hay noción de *prefijo común*. |
| Tools | ❌ No hay. |
| Cancelar la generación | ⚠️ Parcial: `InferenceEngineImpl` tiene `_cancelGeneration` y el loop Kotlin lo mira, pero el nativo no tiene un flag de "abortar el decode en curso". |

**Lo que hay que escribir: `kitoru_chat.cpp`.** La idea central es
**renderizar la conversación completa con el chat template y decodificar sólo el
prefijo nuevo**:

```cpp
// Pseudocódigo del núcleo de kitoru_chat.cpp

// 1) Parsear los mensajes JSON que llegan de Kotlin -> vector<common_chat_msg>
std::vector<common_chat_msg> msgs = parse_chat_msgs(json);

// 2) Renderizar la conversación completa con el template Jinja del modelo
std::string prompt = common_chat_apply_template(g_chat_templates.get(), msgs,
                                                /*add_ass =*/ true, /*use_jinja =*/ true);

// 3) Tokenizar
std::vector<llama_token> tokens = common_tokenize(g_context, prompt, true, true);

// 4) Calcular el PREFIJO COMÚN con lo que ya está en la KV cache
size_t prefix = common_prefix(g_kv_tokens, tokens);

// 5) Si el prefijo cambió, descartar la parte de la KV cache que ya no sirve
if (prefix < g_kv_tokens.size())
    llama_memory_seq_rm(llama_get_memory(g_context), 0, prefix, g_kv_tokens.size());

// 6) Decodificar SOLO tokens[prefix..] en lotes de 512
decode_in_batches(g_context, g_batch, tokens, prefix);

// 7) Guardar los tokens para el próximo turno
g_kv_tokens = tokens;
```

Con esto, un turno de chat típico (historial de 6 turnos + prompt de sistema +
5 fragmentos de RAG) decodifica **sólo los tokens nuevos**: el prefijo va al
KV-cache y no se recalcula. Eso es la diferencia entre ~8 tok/s útiles y ~1 tok/s
de utilería.

**El `common_prefix` y el `llama_memory_seq_rm` no están en el ejemplo**: hay
que escribirlos (son ~10 líneas). El resto (`common_chat_apply_template`,
`common_tokenize`, `common_batch`, `common_sampler_*`) ya está en `llama-common`.

**Registración JNI** (firma con `Java_` + paquete + clase + método, como en el ejemplo):

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_llm_local_LlamaCppEngine_nativeCreate(JNIEnv*, jobject, jstring nativeLibDir);

extern "C" JNIEXPORT jint JNICALL
Java_llm_local_LlamaCppEngine_nativeLoadModel(JNIEnv*, jobject, jlong h,
        jstring path, jint nCtx, jint nThreads, jint nGpuLayers);

extern "C" JNIEXPORT jint JNICALL
Java_llm_local_LlamaCppEngine_nativeChat(JNIEnv*, jobject, jlong h,
        jstring messagesJson, jint nPredict);      // render + decode del prefijo nuevo

extern "C" JNIEXPORT jstring JNICALL
Java_llm_local_LlamaCppEngine_nativeNextToken(JNIEnv*, jobject, jlong h);

extern "C" JNIEXPORT void JNICALL
Java_llm_local_LlamaCppEngine_nativeCancel(JNIEnv*, jobject, jlong h);

extern "C" JNIEXPORT void JNICALL
Java_llm_local_LlamaCppEngine_nativeUnload(JNIEnv*, jobject, jlong h);

extern "C" JNIEXPORT void JNICALL
Java_llm_local_LlamaCppEngine_nativeDestroy(JNIEnv*, jobject, jlong h);
```

El JSON de mensajes se pasa **como string**, no como objetos Java: evita el
`JNIEnv` por token y hace el parseo trivial en C++ (`nlohmann/json` ya está
vendorizado en llama.cpp como `json.hpp`).

### 3.4 Gotchas del build nativo (los que hacen fallar el primer intento)

| # | Gotcha | Síntoma | Mitigación |
|---|---|---|---|
| 1 | `GGML_BACKEND_DL=ON` + `GGML_CPU_ALL_VARIANTS=ON` ⇒ los backends de CPU son `.so` aparte que se cargan **en runtime** desde `nativeLibraryDir` | Con `extractNativeLibs=false` (default de AGP 8 para apps nuevas) `nativeLibraryDir` no las contiene → **no se encuentra ningún backend de CPU** → el modelo no carga | `packaging { jniLibs { useLegacyPackaging = true } }` (= `android:extractNativeLibs="true"`, como el `AndroidManifest.xml` del ejemplo). **O** compilar con `GGML_BACKEND_DL=OFF` (pierde la selección de variante en runtime, pero es a prueba de empaquetado). |
| 2 | `ANDROID_PLATFORM=android-28` | La `.so` usa símbolos de API 28; en API 24–27 falla al cargar | Subir `minSdk` a 28 (Android 9, 2018; ~99 % de dispositivos activos en 2026). |
| 3 | `llama.cpp` cambia la API C entre commits | No compila | **Pinnear un commit** (el de FunshiEngineAI: `547208d`) y re-evaluar trimestralmente. |
| 4 | `common_chat_apply_template` con `use_jinja=true` requiere que el GGUF traiga el `chat_template` en sus metadatos | Si el modelo no lo trae, hay que hardcodear el template por familia | El `GgufMetadataReader` del ejemplo **ya lee `tokenizer.chatTemplate`** del GGUF → se puede exponer como fallback y para mostrarlo en la UI. |
| 5 | El `.so` no se puede linkear dinámicamente con `libc++_shared.so` si no se empaqueta | `dlopen failed: libc++_shared.so` | `packaging { jniLibs { keepDebugSymbols += "**/*.so" } }` y/o `pickFirst` de `libc++_shared.so`. |
| 6 | `System.loadLibrary("kitoru_llm")` en un módulo de librería KMP | `UnsatisfiedLinkError` si el `.so` no se empaquetó | Registrar el `externalNativeBuild` en el bloque `android { }` de `:llm-api` y verificar con `unzip -l app.apk | grep .so`. |

### 3.5 Veredicto de reutilización

> **Reutilizable tal cual (~60 %):** `CMakeLists.txt`, `logging.h`, el lector GGUF,
> la máquina de estados, la envoltura JNI con `limitedParallelism(1)`, la
> configuración de Gradle del módulo, el `AndroidManifest.xml`.
>
> **Reutilizable con adaptación (~15 %):** `InferenceEngine` (se le saca `bench()`
> y `setSystemPrompt()`, se le agrega `Unloading` y `supportsNativeTools`),
> `InferenceEngineImpl` (se le sacan `setSystemPrompt`/`bench`, se le agrega el
> handle `jlong` y la cancelación nativa).
>
> **Hay que escribir (~25 %):** `kitoru_chat.cpp` (el bucle multi-turno con
> prefijo común, el parseo de mensajes, la cancelación) y el `LlamaCppGateway`
> en Kotlin.

**Volumen estimado de código nuevo: ~250 líneas de C++ + ~400 de Kotlin.**

---

## 4. El modelo concreto

### 4.1 Comparación de candidatos

| Modelo | Params | GGUF Q4_K_M | Capas | KV heads / head_dim | **KV @4096** | Español | Razonamiento / seguir instrucciones | Licencia | RAM libre necesaria | Teléfono mínimo |
|---|---|---|---|---|---|---|---|---|---|---|
| **Qwen2.5 1.5B Instruct** | 1,54 B | **~1,1 GB** | 28 | 2 / 128 | 112 MB | Bueno | Regular | **Apache-2.0** ✅ | ~1,6 GB | **3 GB** (recom. 4 GB) |
| **Gemma 2 2B Instruct** | 2,61 B | ~1,6 GB | 26 | 1 / 256 (MQA) | 104 MB | **Muy bueno** | Regular | Gemma Terms ⚠️ | ~2,1 GB | 4 GB |
| **Llama 3.2 3B Instruct** | 3,21 B | ~2,0 GB | 28 | 8 / 128 | 448 MB | Bueno | Bueno | Llama 3.2 Community ⚠️ | ~2,9 GB | 5–6 GB |
| **Qwen2.5 3B Instruct** | 3,09 B | ~1,9 GB | 36 | 2 / 128 | 144 MB | Muy bueno | Bueno | **Apache-2.0** ✅ | ~2,5 GB | 5 GB |
| **Phi-4-mini-instruct** (el de FunshiEngineAI) | 3,82 B | **~2,4 GB** | 32 | 8 / 96 | 384 MB | Bueno | **Muy bueno** | **MIT** ✅ | ~3,3 GB | **6 GB** (recom. 8 GB) |

Cálculo del KV cache: `2 (K y V) × capas × kv_heads × head_dim × 2 bytes (f16) × n_tokens`.
Con `n_ctx = 4096`. A `n_ctx = 8192` el KV se duplica (Phi-4-mini pasa de 384 MB a 768 MB).

### 4.2 El requisito de RAM (el caso que pregunta el enunciado)

> *"un modelo de 2,4 GB en un teléfono de 4 GB es problemas"*

**Confirmado, y es el punto central.** En un teléfono con 4 GB de RAM total,
Android se queda con ~1,5–2 GB para las apps (el sistema, el launcher y los
servicios del fabricante se llevan el resto). Un modelo de 2,4 GB **no entra**:

```
Teléfono de 4 GB
  RAM total .................. 4,0 GB
  − sistema + launcher ....... 1,8 GB
  = disponible para apps ..... 2,2 GB
  − pesos del modelo ......... 2,4 GB   ← no entra
  − KV cache (4096) .......... 0,4 GB
  − app (Compose + índice) ... 0,4 GB
  = necesario ............... 3,2 GB  →  NECESITA un teléfono de 5–6 GB
```

Por eso el catálogo **tiene que estar segmentado por RAM**, y la app tiene que
**consultar la RAM del dispositivo antes de ofrecer un modelo**:

```kotlin
// llm-api/src/commonMain/kotlin/llm/catalog/ModelCatalog.kt (extracto)

/** Requisito de RAM de un modelo: pesos + KV + app. */
data class LocalModelSpec(
    val id: String,
    val label: String,
    val repo: String,
    val file: String,
    val sizeBytes: Long,
    /** sha256 del GGUF (pinneado en el catálogo). */
    val sha256: String,
    /** RAM libre mínima estimada, en bytes. */
    val minFreeRamBytes: Long,
    /** RAM total mínima del dispositivo, en bytes. */
    val minDeviceRamBytes: Long,
    val contextTokens: Int,
    val license: String,
    val notes: String,
)
```

Y en Android:

```kotlin
// llm-api/src/androidMain/kotlin/llm/local/AndroidDeviceProfile.kt

/**
 * Perfil del dispositivo: RAM y espacio, para decidir qué modelos se ofrecen.
 */
class AndroidDeviceProfile(private val am: ActivityManager) {
    /** RAM total del dispositivo. */
    val totalRamBytes: Long
        get() = ActivityManager.MemoryInfo().let { am.getMemoryInfo(it); it.totalMem }

    /** RAM disponible ahora (no la "free", que es casi siempre 0 en Android). */
    val availableRamBytes: Long
        get() = ActivityManager.MemoryInfo().let { am.getMemoryInfo(it); it.availMem }

    /** ¿Es un dispositivo de RAM baja? (API 19+) */
    val isLowRamDevice: Boolean get() = am.isLowRamDevice
}
```

**Regla de offering** (se implementa en el selector de modelo del alumno):

```
modelos que se ofrecen = catálogo.filter { it.minDeviceRamBytes <= totalRamBytes }
modelos instalables = catálogo.filter { it.minFreeRamBytes <= availableRamBytes }
```

Si no hay ninguno que calce → **la opción "IA local" aparece deshabilitada con
el motivo**: *"Este teléfono tiene 3 GB de RAM y el modelo más chico necesita 3,2 GB.
Probá con el modelo del servidor de la cátedra."*

### 4.3 Velocidad (órdenes de magnitud, hay que medir en el dispositivo objetivo)

Medido en Snapdragon 8 Elite con Llama 3.2 1B INT4 (referencia pública de
AlephZero Labs, abril 2026): llama.cpp CPU/NEON 4–8 tok/s, llama.cpp
Vulkan/GPU 8–14 tok/s. Escalando a los modelos de Kitoru y a gama media
(Snapdragon 6-gen, 2022-2023, 4 núcleos):

| Modelo | CPU gama media (4 núcleos) | CPU gama alta (8 núcleos) | Vulkan gama alta |
|---|---|---|---|
| Qwen2.5 1.5B | 5–9 tok/s | 10–16 tok/s | 12–22 tok/s |
| Gemma 2 2B | 4–7 tok/s | 8–14 tok/s | 10–20 tok/s |
| Qwen2.5 3B | 3–5 tok/s | 6–10 tok/s | 8–15 tok/s |
| Phi-4-mini | 2–4 tok/s | 5–8 tok/s | 6–12 tok/s |

**Consecuencia de diseño (importante):** a 4 tok/s, una respuesta de 300 tokens
tarda **~75 segundos**. Eso obliga a:

- `maxTokens` por defecto **más bajo en local** (256–512, no 1024).
- **Streaming obligatorio** (el `LlmGateway` ya es un `Flow`): si el usuario ve
  el primer token a los 2–4 s, la espera se soporta; si ve la respuesta entera
  a los 75 s, no.
- Mostrar **"generando…"** con el contador de tokens, no un spinner mudo.

### 4.4 Recomendación final de modelo

**Catálogo por defecto en Android (3 escalones), filtrado por RAM:**

| # | Modelo | GGUF | Tamaño | Licencia | Cuándo se ofrece |
|---|---|---|---|---|---|
| 1 | **Qwen2.5 1.5B Instruct** (Q4_K_M) | `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf` | 1,1 GB | **Apache-2.0** | Teléfonos de ≥3 GB. **Es el default.** |
| 2 | **Qwen2.5 3B Instruct** (Q4_K_M) | `Qwen2.5-3B-Instruct-Q4_K_M.gguf` | 1,9 GB | **Apache-2.0** | Teléfonos de ≥5 GB. |
| 3 | **Phi-4-mini-instruct** (Q4_K_M) | `Phi-4-mini-instruct-Q4_K_M.gguf` | 2,4 GB | **MIT** | Teléfonos de ≥6 GB. **El que pone la cátedra por defecto** (`ModelPolicy.hfRepo` ya apunta acá). |

**Por qué Qwen2.5 1.5B y no Gemma 2 2B o Phi-4-mini como default:**

- **Licencia**: Apache-2.0 y MIT son limpias para una app que se distribuye a
  estudiantes de una universidad. Gemma y Llama 3.2 tienen términos propios con
  cláusulas de escala (AUP, MAU) que hay que revisar antes de distribuirlas.
- **RAM**: 1,1 GB es el único que entra en un teléfono de 3–4 GB, que es donde
  está la mayor parte de los alumnos. La diferencia entre 1,1 y 2,4 GB no es
  "un poco mejor": es **"funciona" vs. "no funciona"**.
- **Español**: Qwen2.5 es multilingüe fuerte (el español está en los datos de
  entrenamiento multilingüe), mejor que Gemma en la práctica para français-free
  apps, y comparable.
- **Dónde poner el "salto de calidad"**: Phi-4-mini queda como el modelo que la
  **cátedra** configura (`ModelPolicy.hfRepo` ya lo apunta) y que el alumno con
  un teléfono potente puede bajar. El `verificador de citas` determinista que ya
  tenemos (`CitationVerifier`) es lo que hace aceptable que un modelo chico
  alucine menos.

**Gemma 2 2B queda en el catálogo como opción** ("mejor en español, licencia
Gemma: revisala antes de distribuirla"), pero no es el default.

---

## 5. La interfaz portable: el seam

### 5.1 La decisión de diseño: **gateway in-process, sin HTTP**

**Decisión: en Android el gateway local es *in-process*: habla con la librería
nativa por JNI, sin HTTP en el medio.**

**Justificación (por qué NO levantar un servidor HTTP local en Android):**

1. **No se puede lanzar `llama-server` como proceso hijo.** En Android 10+ no
   se puede ejecutar un binario desde el directorio de datos de la app
   (`noexec`), y `ProcessBuilder` no está disponible para binarios propios. El
   camino que usa el escritorio es **imposible** en Android.
2. **Un servidor HTTP en loopback dentro de la app es un custo puro.** Habría que
   embeber un servidor (Ktor server / NanoHTTPD), abrir un puerto (colisiones,
   permisos de red, `NetworkSecurityConfig` para cleartext en loopback,
   `usesCleartextTraffic`), y serializar cada token a JSON y deserializarlo.
   Con 4 tok/s, el overhead de JSON es despreciable… pero el **costo de
   complejidad y de superficie de errores no lo es**.
3. **El streaming se pierde.** Un SSE local obliga a framear; un `Flow<String>`
   directo no.
4. **La cancelación se complica.** Cancelar un `POST` HTTP en vuelo a mitad de
   un token es mucho más frágil que setear un flag en el nativo.
5. **Consistencia con el resto del código.** `LlmGateway` es un `Flow` de
   chunks: la implementación in-process lo cumple **de forma exacta**;
   una implementación HTTP local tendría que traducir SSE → chunks (que es
   justamente lo que ya hace `OpenAiCompatibleGateway` para el modo remoto).

**El seam ya existe y no hay que inventarlo.** `LocalModelService.start()`
devuelve un `LlmGateway`. En el escritorio devuelve un
`OpenAiCompatibleGateway` (porque **allí** el motor es otro proceso: la
frontera es real). En Android devuelve un `LlamaCppGateway` (porque **acá** el
motor está en el mismo proceso: no hay frontera que cruzar).

```
                 ┌──────────────────────────────────────────┐
   :backend      │              LlmGateway                 │
   (RagEngine,   │  chatStream(messages, tools):           │
    AgentLoop)   │      Flow<LlmChunk>                     │
                 └───────────────┬──────────────────────────┘
                                 │
        ┌────────────────────────┼─────────────────────────┐
        │                        │                         │
┌───────▼────────┐   ┌───────────▼──────────┐   ┌──────────▼─────────┐
│ OpenAiCompat…   │   │  LlamaCppGateway     │   │ (futuro) OnnxGenAi │
│ (SSE a /v1)     │   │  (in-process, JNI)   │   │    Gateway         │
│                 │   │                      │   │                    │
│ Ktor HTTP       │   │ llama.cpp (C++)      │   │ ORT GenAI (C++)    │
│                 │   │  ├ libkitoru_llm.so  │   │                    │
└───────┬────────┘   └──────────┬──────────┘   └────────────────────┘
        │                        │
   ┌────▼─────┐          ┌───────▼────────┐
   │ Ktor-Srv │          │  Android app   │
   │ o K-Srv  │          │  (mismo proc.) │
   └──────────┘          └────────────────┘
```

**Consecuencia: NO hace falta una interfaz nueva `LocalInferenceEngine` que
devuelva tokens.** El `LlmGateway` ya es exactamente ese contrato
(`Flow<LlmChunk>`), y el `AgentLoop` ya lo consume sin saber qué hay detrás.
Lo que **sí** hace falta son dos cosas pequeñas:

1. **Declarar capacidades** (para que la UI y el `AgentLoop` adapten el
   comportamiento: tool-calling nativo sí/no, contexto máximo, GPU).
2. **Dar un contexto de plataforma** a `createLocalModelService()` (para que
   sepa dónde está la carpeta de modelos y la de las bibliotecas nativas).

### 5.2 La interfaz nueva: `PlatformContext` (en `:core`)

**Por qué hace falta.** `expect fun createLocalModelService()` no recibe nada,
pero el motor de Android necesita:

- `context.getExternalFilesDir(null)` → dónde van los GGUF (2,4 GB no van a
  `filesDir` en muchos teléfonos),
- `context.applicationInfo.nativeLibraryDir` → dónde buscar los backends de CPU
  dinámicos (`ggml_backend_load_all_from_path`),
- `context.cacheDir` → `.part` de descargas en curso.

KMP no tiene un tipo `Context` común, así que se abstrae:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/PlatformContext.kt

package kitoru.platform

/**
 * Contexto de la aplicación, en términos portables.
 *
 * Cada plataforma lo construye al arrancar:
 *  - Escritorio: rutas bajo `<user.home>/.kitoru`.
 *  - Android: `context.filesDir`, `context.cacheDir`, `context.getExternalFilesDir(null)`.
 *
 * Existe porque los módulos de dominio y de gateway no pueden depender de
 * `android.content.Context` (ni de tipos de JVM) y, aun así, necesitan saber
 * dónde están las carpetas de datos, de caché y de bibliotecas nativas.
 */
interface PlatformContext {
    /** Carpeta privada de la app (`filesDir` en Android). */
    val appFilesDir: FilePath

    /** Carpeta de caché (`cacheDir` en Android). */
    val appCacheDir: FilePath

    /**
     * Carpeta en almacenamiento externo **de la app** (`getExternalFilesDir(null)`
     * en Android). Es donde van los modelos: 2,4 GB no entran cómodamente en
     * el almacenamiento interno, y acá no cuentan contra la cuota de datos.
     * `null` si no hay almacenamiento externo montado.
     */
    val externalFilesDir: FilePath?

    /**
     * Ruta de la carpeta con las bibliotecas nativas ya descomprimidas
     * (`nativeLibraryDir` en Android). La usan los backends de llama.cpp que se
     * cargan en runtime.
     */
    val nativeLibraryDir: String

    /** ¿Estamos en Android? (para decisiones de UX, no de lógica de dominio). */
    val isAndroid: Boolean get() = false
}
```

Implementaciones:

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/PlatformContext.jvm.kt
// (el escritorio reusa el de Android, que sólo usa java.io.File)

/** Contexto de escritorio: todo bajo `~/.kitoru`. */
class DesktopPlatformContext(home: FilePath) : PlatformContext {
    override val appFilesDir = home.resolve("data")
    override val appCacheDir = home.resolve("cache")
    override val externalFilesDir: FilePath? = null
    override val nativeLibraryDir: String = ""
    override val isAndroid = false
}
```

```kotlin
// (en :android, cuando se construya)
// android/src/main/kotlin/com/funshiengine/kitoru/android/AndroidPlatformContext.kt

/** Contexto de Android: `filesDir`, `cacheDir` y `getExternalFilesDir(null)`. */
class AndroidPlatformContext(private val ctx: Context) : PlatformContext {
    override val appFilesDir = FilePath(ctx.filesDir.absolutePath)
    override val appCacheDir = FilePath(ctx.cacheDir.absolutePath)
    override val externalFilesDir = ctx.getExternalFilesDir(null)?.let { FilePath(it.absolutePath) }
    override val nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir
    override val isAndroid = true
}
```

### 5.3 Los cambios en `:llm-api`

**(a) `LocalModelService` — agregar `capabilities`:**

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt (modificado)

interface LocalModelService {

    /** ¿Hay un motor corriendo? */
    val running: Boolean

    /**
     * Qué puede hacer el motor local **en esta plataforma**.
     *
     * La UI lo consulta antes de ofrecer la opción, para no dejar al alumno
     * frente a una opción que va a fallar. En Android sin motor compilado,
     * [LocalCapabilities.available] es `false` y [LocalCapabilities.reason]
     * explica por qué, en español.
     */
    val capabilities: LocalCapabilities get() = LocalCapabilities.NONE

    suspend fun start(
        settings: LocalModelSettings,
        onStatus: (String) -> Unit = {},
    ): LlmGateway

    fun stop()
}

/** Crea el servicio de modelo local de la plataforma. */
expect fun createLocalModelService(ctx: PlatformContext): LocalModelService
```

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LocalCapabilities.kt (nuevo)

package llm.api

/**
 * Qué puede hacer el motor local de esta plataforma.
 *
 * Es un value object simple (no una interfaz): la UI lo muestra, el `AgentLoop`
 * lo consulta para decidir si usar tool-calling nativo, y el selector de
 * modelos lo usa para filtrar.
 */
data class LocalCapabilities(
    /** ¿Hay un motor local disponible? */
    val available: Boolean = false,
    /** ¿El motor soporta tool-calling nativo (protocolo `tools[]`)? */
    val nativeToolCalls: Boolean = false,
    /** Contexto máximo que el motor puede sostener. 0 = desconocido. */
    val maxContextTokens: Int = 0,
    /** ¿Se puede offloadear a GPU (Vulkan)? */
    val gpuAcceleration: Boolean = false,
    /** Motivo en español de por qué no está disponible (vacío = disponible). */
    val reason: String = "",
) {
    companion object {
        /** Nada disponible: el caso por defecto y el del stub de Android. */
        val NONE = LocalCapabilities(reason = "La IA local no está disponible en esta plataforma.")
    }
}
```

**(b) `LlmGateway` — declarar si soporta tool-calling nativo:**

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt (modificado)

interface LlmGateway {
    ...
    /**
     * ¿El motor habla el protocolo `tools[]` (tool-calling nativo)?
     *
     * `false` ⇒ el `AgentLoop` cae al **modo prompt** (el modelo escribe la
     * llamada en un bloque ```` ```tool_call ```` y `ToolCallSanitizer` la
     * parsea). Es lo que va a pasar con llama.cpp en la Fase 1: la API de
     * gramáticas GBNF para tool-calling es trabajo de la Fase 3.
     */
    val supportsNativeToolCalls: Boolean get() = true
}
```

Con esto, el `AgentLoop` se cablea así (sin tocar su lógica):

```kotlin
// backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt (uso)
val native = gateway.supportsNativeToolCalls
val loop = AgentLoop(gateway, registry, nativeToolCalls = native, toolTimeoutMs = 30_000)
```

**(c) `LocalModelSettings` — campos que en Android importan y en el escritorio no:**

```kotlin
// llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt (modificado)

@Serializable
data class LocalModelSettings(
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",

    // ---- Sólo escritorio (Android los ignora) ----
    /** Ruta del binario `llama-server` (vacío = autodetectar en el PATH). */
    val llamaServerPath: String = "",
    /** Puerto local donde se levanta el servidor. */
    val localPort: Int = 8081,

    // ---- Comunes ----
    /** Tamaño de contexto. */
    val contextSize: Int = 8192,
    /** Capas en GPU (0 = sólo CPU). En Android = capas en Vulkan. */
    val gpuLayers: Int = 0,
    /**
     * Carpeta donde vive el modelo ya descargado, **relativa** a la carpeta de
     * modelos de la plataforma (`getExternalFilesDir(null)/models` en Android,
     * `<dataDir>/models` en el escritorio).
     */
    val modelsDir: String = "models",

    // ---- Nuevos, pensados para el motor embebido ----
    /** Hilos de inferencia (0 = automático: min(4, núcleos − 2)). */
    val threads: Int = 0,
    /** Tope de tokens a generar por respuesta (más bajo que en remoto: el local es lento). */
    val nPredict: Int = 512,
    /** Temperatura del sampler. */
    val temperature: Double = 0.2,
)
```

**(d) El gateway local in-process (`:llm-api/androidMain`):**

```kotlin
// llm-api/src/androidMain/kotlin/llm/api/LlamaCppGateway.kt

package llm.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import llm.config.ChatMessage
import llm.local.LlamaCppEngine
import llm.local.LlamaCppEngineState

/**
 * Gateway **in-process** contra el motor llama.cpp embebido en la app.
 *
 * No hay HTTP: los tokens salen del JNI por un callback y se emiten en un
 * `Flow`. Es la contrapartida Android de `OpenAiCompatibleGateway`, que habla
 * SSE con un `llama-server` que corre en otro proceso (el escritorio).
 *
 * Contrato de errores: igual que el gateway remoto, todo falla con
 * `LlmException` y sus `LlmErrorCode`.
 */
class LlamaCppGateway(
    private val engine: LlamaCppEngine,
    private val settings: LocalModelSettings,
) : LlmGateway {

    /**
     * llama.cpp no expone el protocolo `tools[]` todavía: el tool-calling va
     * por prompt (modo prompt del `AgentLoop`).
     */
    override val supportsNativeToolCalls: Boolean get() = false

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        if (tools != null && tools.isNotEmpty()) {
            // No es un error: el `AgentLoop` consulta `supportsNativeToolCalls`
            // y no debería haber llegado acá. Si llegó, avisamos por log.
        }
        // El system prompt viaja como un ChatMessage(role = "system") más,
        // que el RagEngine arma por turno (y por eso cambia en cada pregunta).
        val json = LlamaCppMessages.encode(messages)
        engine.chat(json, settings.nPredict)   // render + decode del prefijo nuevo
        while (true) {
            val piece = engine.nextToken() ?: break   // null = EOG o cancelado
            if (piece.isNotEmpty()) emit(LlmChunk.Text(piece))
        }
    }.flowOn(engine.dispatcher)

    /** El modelo local no tiene "modelos" en el sentido del endpoint `/models`. */
    override suspend fun listModels(): List<String> = listOf(settings.hfFile.substringBeforeLast('.'))

    override fun close() { engine.unload() }
}
```

### 5.4 Encaje con el `AgentLoop` y el RAG

| Pieza | ¿Cambia para local? | Detalle |
|---|---|---|
| `RagEngine.prepare(question, history, useDocs)` | **No** | Es puro: arma los mensajes con los fragmentos de `KnowledgeBase`. |
| `KnowledgeBase` / `Bm25Index` | **No** | Ya son `commonMain` puro (Kotlin, sin JVM). El índice se construye **en el teléfono**. |
| `AgentLoop` | **No** (cableado) | Se le pasa `nativeToolCalls = gateway.supportsNativeToolCalls`. |
| `CitationVerifier` | **No** | Determinista: funciona igual con un modelo chico. **Es la red de seguridad que habilita los modelos chicos.** |
| `ToolRegistry` / `ToolPermissions` | **No** | En local, las tools se ejecutan en el teléfono (el `ToolContext` usa `FilePath`, que ya es portable). |
| `RagConfig.topK` / `chunkSize` | **Sí** | En local el contexto es de 4096, no de 8192: hay que escalar `topK` y `chunkSize` (ver §8, Fase 3). |
| `ChatMessage` | **No** | Se codifica a JSON para el nativo con el mismo contenido. |

---

## 6. Gestión de recursos en Android

### 6.1 El problema

Un motor local en un teléfono es un huésped **grande y egoísta**:

- 1,1–2,4 GB de pesos (mmapeados, pero residentes en el page cache),
- 100–450 MB de KV cache según el modelo y el contexto,
- **3–10 s de CPU por token generado**, es decir, el proceso satura varios núcleos.

Sobre eso: Android va a matar el proceso en cuanto la memoria
aprieta o el usuario pasa a otra app. El diseño tiene que asumir que **el
proceso puede morir en cualquier momento** y que **el trabajo se pierde** salvo
que esté en disco.

### 6.2 El ciclo de vida propuesto

```
┌────────────────────────────────────────────────────────────────────────┐
│ APP EN PRIMER PLANO (ProcessLifecycleOwner ON_START)                  │
│                                                                        │
│  1. El alumno abre un chat en modo LOCAL                              │
│  2. LocalModelService.start(settings, onStatus)                        │
│       ├─ verifica que el GGUF existe y tiene el sha256 correcto        │
│       ├─ llama_model_load_from_file()  (mmap, 1–3 s)                   │
│       ├─ llama_init_from_model()       (KV cache, 100–400 MB)          │
│       └─ devuelve LlamaCppGateway                                       │
│  3. Cada mensaje → chatStream() → carga el prefijo nuevo → genera       │
│                                                                        │
│  El modelo QUEDA CARGADO entre mensajes (recargar son 3 s + la RAM)    │
└──────────────────────────────┬─────────────────────────────────────────┘
                               │
                ON_STOP (app al background)
                               ▼
┌────────────────────────────────────────────────────────────────────────┐
│ APP EN BACKGROUND                                                       │
│  - Cancelar la generación en curso (nativeCancel)                      │
│  - Programar la descarga del modelo en 5 min (Handler.postDelayed)     │
│  - NO bajar la notificación ("servicio en uso")                        │
└──────────────────────────────┬─────────────────────────────────────────┘
                               │ 5 min después (o antes si hay presión)
                               ▼
┌────────────────────────────────────────────────────────────────────────┐
│ MODELO DESCARGADO                                                       │
│  - llama_model_free + llama_free(context)                              │
│  - La UI vuelve a "IA local (se descarga al usarla)"                   │
└────────────────────────────────────────────────────────────────────────┘
```

**Reglas concretas:**

| Regla | Implementación | Motivo |
|---|---|---|
| El modelo se carga **al usar**, no al arrancar | `LocalModelService.start()` | 3 s de carga + 2 GB de RAM no se pagan si el alumno no usa el modo local. |
| El modelo se descarga **5 min después de ir al background** | `Handler(Looper.getMainLooper()).postDelayed(...)` en el observer de `ProcessLifecycleOwner` | Si el alumno vuelve, no recarga. Si no, libera. |
| La generación se **cancela** al ir al background | `engine.cancel()` | Un token de 3 s a medias no vale un proceso muerto. |
| La generación se cancela en `TRIM_MEMORY_RUNNING_LOW` | idem | Presión de memoria. |
| En `TRIM_MEMORY_COMPLETE`/`MODERATE` se descarga **todo** | `engine.unload()` | El sistema está a punto de matar el proceso. |
| **Nunca** se usa un foreground service para inferir | — | Un FGS que retiene 2 GB de RAM en background es mala citizenship y Android lo mata igual; además el tipo `dataSync` tiene tope de 6 h/24 h en API 35. La inferencia es **foreground por definición**: el alumno la está mirando. |
| El FGS **sí** se usa para la **descarga** | `WorkManager` con `setForegroundAsync()` | Reclamar un CPU y mantener la red en un proceso en background exige FGS. |

### 6.3 `ProcessLifecycleOwner`

`ProcessLifecycleOwner` (de `androidx.lifecycle:lifecycle-process`) emite
`ON_START`/`ON_STOP` para **el proceso entero**, no por Activity: es lo que
queremos (si el alumno rota el teléfono, no hay `ON_STOP`).

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/android/LocalEngineLifecycle.kt

/**
 * Observa el ciclo de vida del **proceso** para decidir cuándo cancelar la
 * generación y cuándo descargar el modelo.
 *
 * Se apoya en `ProcessLifecycleOwner` (no en el Activity) para que rotar la
 * pantalla no descargue el modelo.
 */
class LocalEngineLifecycle(
    private val engine: LlamaCppEngine,
    private val scope: CoroutineScope,
    private val idleUnloadDelayMs: Long = 5 * 60_000L,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val unload = Runnable { engine.unload() }

    override fun onStop(owner: LifecycleOwner) {
        engine.cancel()                       // cortar la generación en curso
        handler.removeCallbacks(unload)
        handler.postDelayed(unload, idleUnloadDelayMs)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(unload)       // el alumno volvió: no descargar
    }
}
```

### 6.4 `onTrimMemory`

`onTrimMemory` es un callback de `ComponentCallbacks2`, que se implementa en la
clase `Application` (no en la Activity).

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/android/KitoruApp.kt

class KitoruApp : Application(), ComponentCallbacks2 {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // La UI se ocultó (el alumno apretó Home): NO es presión de memoria.
            // Sólo cortamos la generación en curso.
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> engine.cancel()

            // Presión real: cortamos y soltamos el KV cache.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                engine.cancel()
                engine.clearKvCache()          // libera 100–450 MB sin soltar el modelo
            }

            // El sistema está por matar el proceso: soltamos todo.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> engine.unload()
        }
    }
}
```

**Ojo con el orden de los niveles** (`ComponentCallbacks2` define constantes
decreasing, de "nada" a "todo"): el `when` de arriba está ordenado de **mayor a
menor** a propósito, así que el primer `>=` que matchea gana y el orden real
de llamadas no importa.

| Nivel | Significado | Acción |
|---|---|---|
| `TRIM_MEMORY_UI_HIDDEN` | La UI de la app dejó de estar visible | `cancel()` de la generación. **No** descargar el modelo (volvería a cargar 3 s después). |
| `TRIM_MEMORY_RUNNING_LOW` | El proceso está por debajo del umbral de memoria baja | `cancel()` + `clearKvCache()` (suelta el KV, conserva los pesos mmapeados). |
| `TRIM_MEMORY_RUNNING_CRITICAL` | Memoria crítica | `unload()` completo. |
| `TRIM_MEMORY_BACKGROUND` / `_MODERATE` / `_COMPLETE` | El sistema va a matar el proceso | `unload()` completo. |

### 6.5 Ahorro de batería, hibernate y "restricted bucket"

| Mecanismo | Qué es | Qué hacemos |
|---|---|---|
| `PowerManager.isPowerSaveMode` | El usuario activó el ahorro de batería | **Reducir el consumo**: `threads = min(2, cores)`, `nPredict = min(nPredict, 256)`, y avisar en la UI: *"El teléfono está en ahorro de energía: la respuesta va a tardar más."* |
| `PowerManager.isDeviceIdleMode` | El teléfono está en Doze (app inactiva) | No hacemos nada: la inferencia es foreground. El Doze tampoco aplica. |
| **App hibernation** (Android 12+) | Apps sin uso: se les borra la caché y se les revocan permisos | El modelo **nunca** va a la caché interna: vive en `getExternalFilesDir`, que no se borra. Y la descarga se reintentó con `WorkManager`, que sobrevive. |
| **Restricted bucket** (`ActivityManager.getAppStandbyBucket()`) | Android puede matar la app en cualquier momento y no ejecuta jobs | Si el bucket es `RESTRICTED`, la UI avisa: *"El sistema puede cerrar Kitoru en cualquier momento. Mantené la app abierta mientras esperás la respuesta."* |
| `ActivityManager.isLowRamDevice` | El dispositivo se declara de RAM baja | **No se ofrece la IA local** (o se ofrece sólo con el modelo más chico). |
| `WorkManager` + `setForegroundAsync` | Descargas largas en background | Mantiene el proceso vivo con una notificación. |

### 6.6 La app Android y `largeHeap`

```xml
<!-- android/src/main/AndroidManifest.xml -->
<application
    android:name=".KitoruApp"
    android:largeHeap="true"
    android:extractNativeLibs="true"   <!-- §3.4, gotcha #1 -->
    ... >
```

`android:largeHeap="true"` **ayuda poco** para llama.cpp (los pesos están
mmapeados fuera del heap de Java), pero sí para el índice BM25, los buffers de
Ktor y Compose. No es un garantia de nada.

### 6.7 Máquina de estados del motor

```kotlin
// llm-api/src/androidMain/kotlin/llm/local/LlamaCppEngineState.kt (nuevo)

package llm.local

/**
 * Estados del motor embebido, para la UI.
 *
 * Es basically el `InferenceEngine.State` del ejemplo `llama.android`, con dos
 * cambios: `Unloading` (que el ejemplo no tiene) y `Idle` explícito (el
 * ejemplo arranca en `Uninitialized` y nunca vuelve).
 */
sealed interface LlamaCppEngineState {
    /** Sin modelo cargado. */
    data object Idle : LlamaCppEngineState

    /** Cargando la `.so` nativa. */
    data object Initializing : LlamaCppEngineState

    /** Cargando el GGUF (mmap + KV cache). */
    data class LoadingModel(val path: String) : LlamaCppEngineState

    /** Listo para chatear. */
    data object Ready : LlamaCppEngineState

    /** Descargando el modelo (llama_model_free). */
    data object Unloading : LlamaCppEngineState

    /** Generando tokens. */
    data object Generating : LlamaCppEngineState

    /** Error. */
    data class Error(val message: String) : LlamaCppEngineState
}
```

---

## 7. Descarga de modelos

### 7.1 Dónde vive el modelo

**Decisión: `context.getExternalFilesDir(null)/models/`** (almacenamiento externo
**de la app**), con fallback a `filesDir/models/` si no hay almacenamiento
externo montado.

| Opción | Espacio | ¿Lo ve el usuario? | ¿Se borra al desinstalar? | ¿Se respalda? | Veredicto |
|---|---|---|---|---|---|
| `filesDir` (interno) | Cuota del almacenamiento interno (a veces 2 GB) | No | Sí | **Sí** (2,4 GB en la copia de seguridad: mal) | ❌ |
| `getExternalFilesDir(null)` | Almacenamiento externo compartido | Sí (el Files app lo muestra) | Sí | **No** (`noBackupFilesDir` no aplica; las carpetas `Android/data` no se respaldan) | ✅ **Elegido** |
| `getExternalFilesDirs(null)` (todas las SD) | Depende | Sí | Sí | No | ⚠️ Complejo; las SD se pueden desmontar |
| SAF (`ACTION_OPEN_DOCUMENT_TREE`) | Carpeta elegida por el usuario | Sí | No | No | ⚠️ El usuario puede borrarla o cambiar los permisos |

> **SAF como opción de importación**: el ejemplo `llama.android` usa
> `ActivityResultContracts.OpenDocument()` para que el usuario elija un `.gguf`
> de su almacenamiento y lo copie a la app (`ensureModelFile`). Eso **se
> mantiene** como el camino "traé tu propio modelo", y además sirve de plan B
> si la descarga desde HF falla (red del campus, etc.).
>
> Pero **la descarga automática va a `getExternalFilesDir`**, no a una carpeta
> SAF: la app necesita acceso estable y sin permisos revocables.

### 7.2 Reanudación

El `HuggingFaceDownloader` de FunshiEngineAI (`llm/catalog/HuggingFaceDownloader.kt`)
ya resuelve esto bien y es **el código a portar**:

```kotlin
// FunshiEngineAI/llm-api/src/main/kotlin/llm/catalog/HuggingFaceDownloader.kt (extracto)
val part = target.resolveSibling(target.fileName.toString() + ".part")
val already = if (Files.exists(part)) Files.size(part) else 0L
client.prepareGet("https://huggingface.co/$repo/resolve/main/$file") {
    if (token.isNotBlank()) bearerAuth(token)
    if (already > 0) header(HttpHeaders.Range, "bytes=$already-")
}.execute { resp ->
    if (resp.status == HttpStatusCode.RequestedRangeNotSatisfiable && already > 0) return@execute
    val resumed = resp.status == HttpStatusCode.PartialContent
    val offset = if (resumed) already else 0L
    ...
    FileOutputStream(part.toFile(), resumed).use { out -> /* copia en chunks de 64 KB */ }
    if (total > 0 && done < total) throw LlmException(DESCARGA_FALLIDA, "...", retryable = true)
}
Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
```

**Adaptaciones para Android:**

1. `java.nio.file.Path` → **`java.io.File`** (Android soporta `java.nio.file`
   desde API 26, pero `minSdk 24` original y `File` es universal).
2. `HttpClient(CIO)` → el cliente **OkHttp** que ya está en el classpath de
   Android (`ktor-client-okhttp` ya es dependencia de `androidMain` en
   `:llm-api`). OkHttp soporta `Range` y streaming sin buffering.
3. El `Dispatchers.IO` de `kotlinx-coroutines-android` (ya presente).
4. El **anti-array-buffer** que ya tiene el original (leer en chunks de 64 KB y
   escribir directo al `FileOutputStream`) es **esencial**: con 2,4 GB, un
   `bodyAsBytes()` reventaría la memoria del teléfono.

**En el teléfono, la descarga se hace dentro de un `CoroutineWorker` de
WorkManager** (§8, Fase 2), no en el hilo de la UI.

### 7.3 Verificación de integridad (sha256)

**Dos fuentes de verdad, en este orden:**

1. **El valor pinneado en el catálogo** (`LocalModelSpec.sha256`). Es el control
   fuerte: si el repo de Hugging Face cambia el modelo, nos enteramos.
2. **El `X-Linked-Etag` de la respuesta** de HF. Para archivos LFS, HF sirve el
   sha256 del contenido en ese header. Sirve para archivos que **no** estén
   pinneados (modelos que el alumno agrega).

Procedimiento:

```kotlin
// llm-api/src/jvmCommonMain/kotlin/llm/catalog/Sha256.kt (nuevo)

package llm.catalog

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * sha256 de un archivo, leído en bloques (nunca el archivo entero en memoria).
 *
 * Es portable a Android: `java.security.MessageDigest` existe en todas las
 * APIs y `FileInputStream` también.
 */
object Sha256 {
    private const val BUFFER = 1 shl 16   // 64 KB

    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
```

Y en el worker:

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/android/download/ModelDownloadWorker.kt

override suspend fun doWork(): Result {
    val target = File(modelsDir, spec.file)
    val part = File(target.parentFile, target.name + ".part")

    // 1. Espacio en disco
    if (!hasRoomFor(part, spec.sizeBytes)) {
        return Result.failure(workDataOf(KEY_ERROR to "No hay espacio suficiente…"))
    }

    // 2. Descarga con reanudación (Range) -> escribe en `part`
    val expected = downloader.download(spec.repo, spec.file, part) { done, total ->
        setProgress(workDataOf(KEY_PROGRESS to done, KEY_TOTAL to total))
        // Reanudar si el proceso muere: `part` queda en disco.
    }

    // 3. Verificación
    val digest = Sha256.of(part)
    val ok = spec.sha256.isBlank() || digest.equals(spec.sha256, ignoreCase = true)
    if (!ok) {
        part.delete()   // el .part corrupto no sirve: se tira y se reintenta
        return Result.failure(workDataOf(KEY_ERROR to "El modelo descargado está dañado. Se volvió a borrar."))
    }

    // 4. Publicación atómica
    if (!part.renameTo(target)) return Result.retry()
    return Result.success(workDataOf(KEY_PATH to target.absolutePath))
}
```

**El `renameTo` dentro del mismo filesystem es atómico**: el modelo pasa de
`.part` a su nombre final de un solo golpe, así que nunca hay un GGUF a medio
escribir en el lugar donde el motor lo busca.

### 7.4 Espacio en disco

```kotlin
// llm-api/src/commonMain/kotlin/llm/catalog/ModelDisk.kt (nuevo)

package llm.catalog

import kitoru.platform.FilePath

/** Espacio en disco del sistema de archivos que contiene [path]. */
expect fun availableBytes(path: FilePath): Long
```

```kotlin
// llm-api/src/jvmCommonMain/kotlin/llm/catalog/ModelDisk.jvm.kt
// (jvmCommonMain sirve para escritorio y Android: StatFs es Android, File en ambos)

actual fun availableBytes(path: FilePath): Long {
    val f = File(path.value)
    val dir = if (f.isDirectory) f else f.parentFile ?: f
    return runCatching {
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes
    }.getOrElse { 0L }
}
```

> ⚠️ `android.os.StatFs` **no** está en el JDK de escritorio. Por eso la
> implementación tiene que ir en `androidMain` (o usar `File.getFreeSpace()`,
> que sí existe en ambos: `java.io.File.getFreeSpace()` es API 9+ y funciona en
> el escritorio). **Recomendación: usar `File.getFreeSpace()`** en
> `jvmCommonMain` — es una línea, funciona en los dos, y evita partir el
> `expect/actual`.

Regla de espacio:

```
requerido = sizeBytes × 1,15   (el .part + el archivo final coexistiendo un instante)
```

Mensaje en español si no alcanza:

> *"No hay espacio suficiente en el teléfono: el modelo necesita 2,6 GB y tenés 1,8 GB libres. Liberá espacio o borrá otro modelo."*

### 7.5 Worked example — descargar el Phi-4-mini en Android

```
1. El alumno toca "Descargar modelo" en la pestaña "IA local".
2. Se encola un ModelDownloadWorker (repo, file, sha256, path destino).
3. WorkManager lo corre con:
   - Constraints(NetworkType.CONNECTED, requiresStorageNotLow = true)
   - setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
   - setForegroundAsync(notificación "Descargando Phi-4-mini… 34 %")
4. El worker:
   - crea <externalFilesDir>/models/
   - descarga a <externalFilesDir>/models/Phi-4-mini-instruct-Q4_K_M.gguf.part
     con Range: bytes=<ya-descargado>-
   - actualiza el progreso (cada 250 ms, siempre el último al 100 %)
   - verifica el sha256
   - hace renameTo() al nombre final
5. WorkManager:notification → "Modelo listo".
6. La UI pasa a "IA local — listo (Phi-4-mini, 2,4 GB)".
```

**Si el proceso muere a mitad de camino**: el `Worker` se re-encola, el `.part`
sigue en disco, y el siguiente intento pide `Range` y continúa. La reanudación
es **gratis** porque el estado está en el archivo, no en el proceso.

---

## 8. Plan por fases

### Fase 0 — El stub actual (lo que ya existe) + el mensaje claro

**Estado:** `UnavailableLocalModelService` en
`llm-api/src/androidMain/kotlin/llm/api/LocalModelService.android.kt`, que
lanza `LlmException(NO_CONFIGURADO, "El modelo local todavía no está disponible
en Android. Usá el servidor de la cátedra (modo remoto) o el modelo local del
escritorio.")`.

**Lo que falta y hay que agregar en esta fase** (es barato y evita romper la UI
más adelante):

| # | Cambio | Archivo |
|---|---|---|
| 0.1 | `LocalCapabilities` con `available=false` y `reason` en español | `llm-api/src/commonMain/kotlin/llm/api/LocalCapabilities.kt` (nuevo) |
| 0.2 | `LocalModelService.capabilities` | `llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` |
| 0.3 | `LlmGateway.supportsNativeToolCalls` | `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` |
| 0.4 | `expect fun createLocalModelService(ctx: PlatformContext)` (cambio de firma) | `llm-api/.../LocalModelService.kt` + `desktopMain` + `androidMain` |
| 0.5 | `PlatformContext` + `DesktopPlatformContext` | `core/src/commonMain/.../PlatformContext.kt`, `core/src/jvmCommonMain/.../PlatformContext.jvm.kt` |
| 0.6 | La UI del alumno muestra la opción "IA local" **visible pero deshabilitada**, con el motivo al lado | `:ui` (pantalla de configuración del modelo del alumno) |

**Texto exacto para la UI del alumno** (coherente con
`docs/design/teacher/05-config-modelo.md` §10.2 — *el alumno nunca queda frente
a una pantalla muerta*):

```
○ IA local en el teléfono — No disponible en esta versión
  La IA local todavía no está disponible en Android. Usá el modelo del
  servidor de la cátedra; en la app de escritorio podés usar un modelo
  propio.
```

**Criterio de aceptación de la Fase 0:**
- La app Android arranca, se inscribe en una cátedra, chatea con el
  Kitoru-Server, y la pestaña "IA local" aparece visible y deshabilitada con el
  texto de arriba.
- `LocalModelService.start()` en Android lanza `LlmException` con un mensaje
  en español que menciona la alternativa (el servidor de la cátedra).
- Los tests de `:llm-api` compilan en `desktopTest` y `androidUnitTest`.

---

### Fase 1 — El motor más simple que funcione

**Objetivo:** **un chat en el teléfono, con un modelo local, en proceso, sin
descarga automática todavía** (el modelo se trae a mano por SAF o se deja en la
carpeta).

**Alcance:**

| # | Entregable |
|---|---|
| 1.1 | `:llm-api` con `externalNativeBuild` (CMake) y `abiFilters = ["arm64-v8a"]`. |
| 1.2 | `llama.cpp` pinneado (commit `547208d`, el de FunshiEngineAI) vía `FetchContent` con URL a un tarball del commit. |
| 1.3 | `llm-api/src/androidMain/cpp/kitoru_chat.cpp`: `nativeCreate`, `nativeLoadModel`, `nativeChat` (render + prefijo común), `nativeNextToken`, `nativeCancel`, `nativeUnload`, `nativeDestroy`. |
| 1.4 | `llm-api/src/androidMain/cpp/CMakeLists.txt` (copia del del ejemplo, con `LLAMA_SRC` apuntando al `FetchContent`). |
| 1.5 | `llm-api/src/androidMain/kotlin/llm/local/LlamaCppEngine.kt` (binding JNI + `Flow` de tokens). |
| 1.6 | `llm-api/src/androidMain/kotlin/llm/local/LlamaCppEngineState.kt` (máquina de estados). |
| 1.7 | `llm-api/src/androidMain/kotlin/llm/api/LlamaCppGateway.kt` (implementa `LlmGateway`). |
| 1.8 | `llm-api/src/androidMain/kotlin/llm/api/LocalModelService.android.kt` → `AndroidLocalModelService` (usa el motor si la `.so` cargó; si no, el stub con `capabilities.reason`). |
| 1.9 | `llm-api/src/androidMain/kotlin/llm/local/GgufInfo.kt` (port del `GgufMetadataReader` del ejemplo, para mostrar info del modelo y validar el archivo). |
| 1.10 | Importación por **SAF** de un `.gguf` (el `OpenDocument` del ejemplo) → copia a `getExternalFilesDir/models/`. |
| 1.11 | `minSdk` de `:llm-api` y de la app: 24 → **28**. |
| 1.12 | `packaging { jniLibs { useLegacyPackaging = true } }` (§3.4 gotcha #1). |
| 1.13 | Un `LocalChatScreen` mínimo en `:ui` para probar: campo de texto, streaming, botón "Detener". |

**Criterios de aceptación:**

1. La app compila (`:android:assembleDebug`) con la `.so` incluida; se verifica
   con `unzip -l app.apk | grep libkitoru_llm.so` y
   `unzip -l app.apk | grep libggml-cpu`.
2. Un chat con **Phi-4-mini Q4_K_M** en un Pixel / teléfono de gama media
   produce tokens en streaming a ≥3 tok/s.
3. **El segundo turno es明显 más rápido que el primero** (porque el prefijo
   común se reutiliza). **Éste es el test que valida el diseño del prefijo
   común** — si el segundo turno no es más rápido, el `common_prefix` está mal.
4. El modelo se puede cancelar a mitad de la generación y el proceso queda
   sano (se puede volver a chatear).
5. `onTrimMemory(TRIM_MEMORY_COMPLETE)` descarga el modelo sin crashear.
6. Todo error visible está en español.

**Riesgos de esta fase y suPlan B:**

| Riesgo | Plan B |
|---|---|
| El build NDK no compila en CI | Cachear la `.so` como artefacto de build (`android-abi` artifacts de GitHub Actions) y compilar la nativa en un job aparte. |
| `common_chat_apply_template` no está disponible en el commit pinneado | Usar `common_chat_format_single` en loop (como hace el ejemplo) para construir el prompt a mano, con el template del GGUF leído por `GgufInfo`. |
| El modelo no entra en la RAM del teléfono de prueba | Bajar a Qwen2.5 1.5B (1,1 GB) para las pruebas de humo. |
| Multi-turno con prefijo común no funciona | Fallback: recalcular todo el contexto en cada turno (más lento pero funcional). Se marca como deuda. |

**Esfuerzo estimado: 2–3 semanas.**

---

### Fase 2 — Descarga y verificación del modelo

**Objetivo:** que el alumno baje el modelo él mismo, con progreso, reanudación y
verificación de integridad, sin intervención manual.

| # | Entregable |
|---|---|
| 2.1 | `llm-api/src/commonMain/kotlin/llm/catalog/ModelCatalog.kt`: `LocalModelSpec` + catálogo con 3 modelos (Qwen2.5 1.5B / Qwen2.5 3B / Phi-4-mini), sha256 pinneado, RAM mínima, licencia. |
| 2.2 | `llm-api/src/jvmCommonMain/kotlin/llm/catalog/HfModelDownloader.kt`: port del `HuggingFaceDownloader` de FunshiEngineAI (`.part` + `Range` + copia en chunks de 64 KB). |
| 2.3 | `llm-api/src/jvmCommonMain/kotlin/llm/catalog/Sha256.kt`. |
| 2.4 | `llm-api/src/commonMain/kotlin/llm/catalog/ModelDisk.kt` (`availableBytes`). |
| 2.5 | `android/src/main/kotlin/.../download/ModelDownloadWorker.kt` (CoroutineWorker de WorkManager). |
| 2.6 | `android/src/main/kotlin/.../download/ModelDownloadNotifier.kt` (canal + notificación de progreso). |
| 2.7 | `ModelDownloadManager` (en `:ui` o `:android`): encola, cancela, muestra progreso, reintenta. |
| 2.8 | `AndroidManifest.xml`: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`. |
| 2.9 | Selector de modelo en la UI del alumno, filtrado por RAM del dispositivo (`AndroidDeviceProfile`). |
| 2.10 | Verificación: si el sha256 no cierra, se borra el `.part` y se reintenta; a la 3ª falla se muestra el error en español. |

**Criterios de aceptación:**

1. Descargar 1,1 GB con la app en background: la notificación muestra el
   progreso y el proceso **no** muere.
2. **Matar la app a mitad de la descarga** y reabrirla: la descarga **continúa
   desde donde estaba** (no desde cero).
3. Un archivo corrupto (sha256 distinto) se detecta, se borra y se reporta:
   *"El modelo descargado está dañado. Volvé a intentarlo."*
4. Con espacio insuficiente, el error es claro y no se deja un `.part` colgado.
5. La app ofrece el modelo default (Qwen2.5 1.5B) en un teléfono de 4 GB y
   **no** ofrece Phi-4-mini en ese mismo teléfono.

**Esfuerzo estimado: 1–1,5 semanas.**

---

### Fase 3 — Integración con el RAG local

**Objetivo:** que el chat local **use el material de la cátedra** (el RAG), con
las mismas herramientas y el mismo verificador de citas que el modo remoto.

| # | Entregable |
|---|---|
| 3.1 | **El corpus se sincroniza en el teléfono**: los `Material.extractedText` y el `Course.modelPolicy` viajan al alumno (JSON en `KitoruPaths.dataDir`, que en Android es `filesDir/kitoru`). El `KnowledgeBase` se indexa **en el dispositivo** con `Bm25Index` (ya portable). |
| 3.2 | `KnowledgeBase.sync()` en Android: corre en `Dispatchers.IO` y **en background** (un `WorkManager` o un `launch` en la app), con `onStatus` para la UI ("Indexando 42 documentos…"). |
| 3.3 | **Perfil de contexto local**: el RAG tiene que escalar al contexto del motor local (4096 en vez de 8192). `RagConfig(topK = max(2, contextSize / 1200), chunkSize = min(chunkSize, 800))`. |
| 3.4 | El `AgentLoop` corre con `nativeToolCalls = gateway.supportsNativeToolCalls` (en local: `false` → modo prompt, que ya está implementado y testeado). |
| 3.5 | `CitationVerifier` corre igual (ya es determinista y portable). La UI muestra las citas igual que en remoto. |
| 3.6 | `ModelPolicy.resolveFor(studentChoice)` ya cubre el bloqueo de `LOCAL_MODE` (de `05-config-modelo.md`). Falta el cableado en la UI del alumno. |
| 3.7 | **Tool-calling nativo con gramáticas GBNF** (opcional, es elextra de la fase): `llama.cpp` puede generar un grammar a partir del JSON Schema de `ToolSpec` y forzar al modelo a emitir JSON válido. Se implementa detrás de `supportsNativeToolCalls = true`. |
| 3.8 | Vulkan (opcional): `GGML_VULKAN=ON` y un flag para probarlo. Se documenta como experimental (en algunos drivers es más lento que la CPU con KleidiAI). |
| 3.9 | Métricas locales: tok/s, tiempo al primer token, tokens generated. Se muestran en un panel de "info del modelo" (no se mandan a ningún servidor: la Visibility dice que el docente no ve los chats locales). |

**Criterios de aceptación:**

1. Una pregunta sobre el material de la cátedra, en modo local, devuelve una
   respuesta **con las citas** (el verificador funciona igual que en remoto).
2. El índice se construye en el teléfono en < 2 s para 50 documentos.
3. El `AgentLoop` completa un turno con `get_time` en modo local (modo prompt).
4. Con `Course.allowLocalModels = false`, el alumno **no puede** cambiar a local
   y la UI lo explica (lo de `05-config-modelo.md` §10.2).

**Esfuerzo estimado: 1,5–2 semanas.**

---

### Cronograma resumen

| Fase | Esfuerzo | Acumulado | Dependencia |
|---|---|---|---|
| 0 — Stub + `capabilities` + `PlatformContext` | 2–3 días | — | — |
| 1 — Motor in-process (llama.cpp JNI) | 2–3 semanas | ~3 semanas | Build NDK |
| 2 — Descarga con reanudación y sha256 | 1–1,5 semanas | ~4,5 semanas | Fase 1 |
| 3 — RAG local + herramientas | 1,5–2 semanas | ~6,5 semanas | Fases 1–2 |

**En paralelo y sin bloquear nada:** la app Android (`:android`) y la UI (`:ui`)
pueden avanzar con el modo remoto funcionando y la pestaña "IA local"
deshabilitada. **El modo remoto es el camino crítico del producto; la IA local
es una función de autonomía del alumno.**

---

## 9. Riesgos y plan B

### 9.1 Registro de riesgos

| # | Riesgo | Prob. | Impacto | Mitigación | Plan B |
|---|---|---|---|---|---|
| R1 | El build NDK/CMake de llama.cpp no es reproducible en CI | Alta | Alto | `FetchContent` con URL pinneada a un commit + caché de artefactos; job de build separado | Distribuir la `.so` como artefacto firmado de release |
| R2 | Multi-turno con prefijo común no funciona como se espera | Media | Alto | Test explícito en la Fase 1 (criterio 1.3) | Recalcular el contexto completo en cada turno (más lento) |
| R3 | La API de `llama.cpp` cambia y hay que recompilar | Alta | Medio | Pinear commit + matriz de versiones; el `common_chat_templates` es estable desde 2024 | Actualizar el commit pinneado en un PR aparte |
| R4 | El modelo no carga en gama baja (RAM) | Alta | Medio | Catálogo segmentado por RAM + `isLowRamDevice` | Default de 1,1 GB |
| R5 | El proceso se muere durante la inferencia | Media | Medio | Cancelar al ir a background; el chat se guarda en el repo antes de inferir | Reintento automático una vez |
| R6 | La app se饥 killed por "restricted bucket" | Media | Bajo | Aviso al usuario; WorkManager para lo crítico | — |
| R7 | La descarga se interrumpe siempre | Media | Bajo | Reanudación con `Range` + `.part` | Importación por SAF |
| R8 | El modelo responde peor que el remoto y el alumno se queja | Media | Medio | El verificador de citas determinista; el texto de la UI explica el trade-off | Botón "comparar con el del servidor" |
| R9 | `minSdk` 28 deja afuera dispositivos | Baja | Bajo | Android 9 es de 2018: < 1 % de dispositivos activos | Flavor `full` con `minSdk 24` (sin local) |
| R10 | Google retira MediaPipe del soporte y alguien pregunta | Baja | Bajo | Ya está en maintenance-only; si se pregunta, la respuesta es LiteRT-LM | — |

### 9.2 El plan B grande: **Android sólo con modo remoto**

**Decisión: el hito 1 (la primera versión de la app Android) asume
exclusivamente modo remoto.** La IA local entra detrás de un flag.

**Consecuencias concretas:**

1. **La primera versión de la app Android se entrega sin IA local.** El chat
   funciona contra el Kitoru-Server (o cualquier endpoint OpenAI-compatible).
   Es un producto completo y útil por sí mismo.

2. **La IA local se compila detrás de un flag de build**:

   ```kotlin
   // android/build.gradle.kts
   buildTypes {
       debug {
           buildConfigField("boolean", "LOCAL_LLM", "true")
       }
       release {
           buildConfigField("boolean", "LOCAL_LLM", "false")   // ← el flag
       }
   }
   ```

   y `AndroidLocalModelService` consulta el flag en runtime:

   ```kotlin
   class AndroidLocalModelService(
       private val ctx: PlatformContext,
       private val enabled: Boolean,          // ← BuildConfig.LOCAL_LLM
   ) : LocalModelService {
       override val capabilities: LocalCapabilities
           get() = if (!enabled) LocalCapabilities.NONE.copy(
               reason = "La IA local todavía no está disponible en esta versión. " +
                   "Usá el modelo del servidor de la cátedra.",
           ) else LlamaCppEngine.capabilities()
       ...
   }
   ```

3. **Cómo se le comunica al alumno** (regla de `05-config-modelo.md` §10.2:
   *el alumno nunca queda frente a una pantalla muerta*):

   ```
   ┌────────────────────────────────────────────────────────────────┐
   │ ○ IA local en el teléfono — No disponible en esta versión      │
   │                                                                  │
   │ La IA local todavía no está disponible en Android.               │
   │ Usá el modelo del servidor de la cátedra; en la app de           │
   │ escritorio podés usar un modelo propio.                         │
   │                                                                  │
   │ [ Saber más ]                                                    │
   └────────────────────────────────────────────────────────────────┘
   ```

   - La opción **no se oculta**: ocultarla hace creer al alumno que la función
     no existe.
   - No hay pantalla de error: **el chat sigue funcionando** con el modelo de
     la cátedra.
   - "Saber más" abre una hoja con el estado: por qué, qué falta, y cuándo se
     espera.

4. **Qué pasa si el docente configuró `ModelPolicy.mode = LOCAL`**
   (`05-config-modelo.md` §7.2 y §10.1): el cliente detecta que el modo local
   no está disponible **antes** de dejar al alumno en un chat que no va a
   funcionar, y le ofrece el remoto explícitamente:

   > *"Tu cátedra configuró la IA local, pero todavía no está disponible en
   > Android. Mientras tanto, ¿usás el modelo del servidor? Tu cátedra puede
   > habilitártelo cuando esté listo."*

   Es el mismo patrón que el caso "el servidor de la cátedra está caído".

5. **La decisión se toma con esta tabla**, y es revisable:

   | Si… | Entonces… |
   |---|---|
   | La Fase 1 cierra en plazo | La Fase 1 se entrega en la primera versión de la app Android (con el flag en `true` en debug y en release cuando se valide) |
   | La Fase 1 se atrasa más de 3 semanas | El release sale **sin** IA local (flag en `false`) y la Fase 1 sigue en desarrollo |
   | La Fase 1 revela un problema de fondo (R2, R1) | Se evalúa el **spike de MediaPipe** (opción b) como "modo liviano" sin herramientas, con Gemma 3 1B `.task` |
   | La inferencia local resulta inviable en el hardware objetivo | La app Android queda **sólo remoto** para siempre; el escritorio conserva la IA local. Es un resultado aceptable: la institución provee el servidor. |

### 9.3 Qué **no** se pierde si Android queda sólo-remoto

- El **producto institucional** (docente ↔ alumno vía Kitoru-Server) está
  completo.
- La **IA local del escritorio** sigue funcionando: es el escenario de "el
  alumno estudia en su notebook sin internet".
- La **matriz de visibilidad** no cambia: el modo local en Android tampoco
  sería visible para el docente.

Es decir: la IA local en Android es **una mejora**, no un pilar. El plan lo
trata como tal.

---

## 10. Nombres de archivos y clases

### 10.1 `:core`

| Archivo | Tipo | Contenido |
|---|---|---|
| `core/src/commonMain/kotlin/kitoru/platform/PlatformContext.kt` | **nuevo** | `interface PlatformContext` |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/PlatformContext.jvm.kt` | **nuevo** | `class DesktopPlatformContext` |

### 10.2 `:llm-api` — `commonMain`

| Archivo | Tipo | Contenido |
|---|---|---|
| `llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` | **modificado** | `LocalModelService.capabilities`; `expect fun createLocalModelService(ctx: PlatformContext)` |
| `llm-api/src/commonMain/kotlin/llm/api/LocalCapabilities.kt` | **nuevo** | `data class LocalCapabilities` |
| `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` | **modificado** | `LlmGateway.supportsNativeToolCalls` |
| `llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt` | **modificado** | `LocalModelSettings`: `threads`, `nPredict`, `temperature` |
| `llm-api/src/commonMain/kotlin/llm/catalog/ModelCatalog.kt` | **nuevo** | `data class LocalModelSpec`; `object ModelCatalog { val entries }` |
| `llm-api/src/commonMain/kotlin/llm/catalog/ModelDisk.kt` | **nuevo** | `expect fun availableBytes(path: FilePath): Long` |

### 10.3 `:llm-api` — `jvmCommonMain` (escritorio **y** Android)

| Archivo | Tipo | Contenido |
|---|---|---|
| `llm-api/src/jvmCommonMain/kotlin/llm/catalog/HfModelDownloader.kt` | **nuevo** | port de `HuggingFaceDownloader` (`.part` + `Range` + chunks de 64 KB), con `java.io.File` |
| `llm-api/src/jvmCommonMain/kotlin/llm/catalog/Sha256.kt` | **nuevo** | `object Sha256 { fun of(file: File): String }` |
| `llm-api/src/jvmCommonMain/kotlin/llm/catalog/ModelDisk.jvm.kt` | **nuevo** | `actual fun availableBytes(...)` con `File.getFreeSpace()` |

### 10.4 `:llm-api` — `androidMain`

| Archivo | Tipo | Contenido |
|---|---|---|
| `llm-api/src/androidMain/cpp/kitoru_chat.cpp` | **nuevo** | el JNI: `nativeCreate`, `nativeLoadModel`, `nativeChat`, `nativeNextToken`, `nativeCancel`, `nativeUnload`, `nativeDestroy` |
| `llm-api/src/androidMain/cpp/CMakeLists.txt` | **nuevo** | `add_subdirectory(llama.cpp)` + la lib `kitoru_llm` |
| `llm-api/src/androidMain/cpp/logging.h` | **copiado** | del ejemplo `llama.android` (bridge a logcat) |
| `llm-api/src/androidMain/kotlin/llm/local/LlamaCppEngine.kt` | **nuevo** | binding JNI + `Flow` de tokens + `cancel()` / `unload()` / `clearKvCache()` |
| `llm-api/src/androidMain/kotlin/llm/local/LlamaCppEngineState.kt` | **nuevo** | `sealed interface LlamaCppEngineState` |
| `llm-api/src/androidMain/kotlin/llm/local/LlamaCppMessages.kt` | **nuevo** | codificación de `List<ChatMessage>` → JSON para el nativo |
| `llm-api/src/androidMain/kotlin/llm/local/GgufInfo.kt` | **nuevo** | port del `GgufMetadataReader` (arquitectura, `n_ctx_train`, quant, chat template) |
| `llm-api/src/androidMain/kotlin/llm/local/AndroidDeviceProfile.kt` | **nuevo** | `totalRamBytes`, `availableRamBytes`, `isLowRamDevice` |
| `llm-api/src/androidMain/kotlin/llm/api/LlamaCppGateway.kt` | **nuevo** | `class LlamaCppGateway : LlmGateway` (in-process) |
| `llm-api/src/androidMain/kotlin/llm/api/LocalModelService.android.kt` | **modificado** | `AndroidLocalModelService` + `actual fun createLocalModelService(ctx)` |

### 10.5 `:llm-api` — `desktopMain`

| Archivo | Tipo | Contenido |
|---|---|---|
| `llm-api/src/desktopMain/kotlin/llm/api/LocalModelService.desktop.kt` | **modificado** | `actual fun createLocalModelService(ctx: PlatformContext)`; se ignora `ctx` |

### 10.6 `:backend`

| Archivo | Tipo | Contenido |
|---|---|---|
| `backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt` | **sin cambios** | se cablea con `nativeToolCalls = gateway.supportsNativeToolCalls` desde afuera |

### 10.7 `:android` (app, todavía no construido)

| Archivo | Tipo | Contenido |
|---|---|---|
| `android/src/main/kotlin/com/funshiengine/kitoru/android/KitoruApp.kt` | **nuevo** | `Application` + `ComponentCallbacks2` → `onTrimMemory` |
| `android/src/main/kotlin/com/funshiengine/kitoru/android/AndroidPlatformContext.kt` | **nuevo** | `class AndroidPlatformContext(ctx: Context) : PlatformContext` |
| `android/src/main/kotlin/com/funshiengine/kitoru/android/LocalEngineLifecycle.kt` | **nuevo** | `DefaultLifecycleObserver` sobre `ProcessLifecycleOwner` |
| `android/src/main/kotlin/com/funshiengine/kitoru/android/download/ModelDownloadWorker.kt` | **nuevo** | `CoroutineWorker` (descarga + sha256 + rename) |
| `android/src/main/kotlin/com/funshiengine/kitoru/android/download/ModelDownloadNotifier.kt` | **nuevo** | canal + notificación de progreso |
| `android/src/main/kotlin/com/funshiengine/kitoru/android/download/ModelDownloadManager.kt` | **nuevo** | encola / cancela / observa el worker |
| `android/src/main/AndroidManifest.xml` | **nuevo** | permisos + `largeHeap` + `extractNativeLibs` + FGS `dataSync` |

### 10.8 `:ui`

| Archivo | Tipo | Contenido |
|---|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/student/LocalModelSection.kt` | **nuevo** | la sección "IA local" del alumno: selector de modelo, descarga, estado, "no disponible" |
| `ui/src/commonMain/kotlin/kitoru/ui/student/LocalModelUiState.kt` | **nuevo** | `data class LocalModelUiState(...)` |

---

## 11. Resumen

1. **Motor elegido: `llama.cpp` con binding JNI**, porque es el único que
   mantiene el catálogo GGUF, el RAG portable, el verificador de citas y el
   tool-calling sin reescribirlos; MediaPipe no tiene tool-calling y está en
   maintenance-only, y ONNX Runtime GenAI para Android exige compilar desde el
   código fuente.
2. **El ejemplo `llama.android` es ~60 % reutilizable** (CMake, Gradle, logging,
   lector GGUF, máquina de estados) pero su `ai_chat.cpp` **no sirve para chat
   multi-turno**: hay que escribir `kitoru_chat.cpp` (~250 líneas) que renderice
   la conversación con el chat template y **decodifique sólo el prefijo nuevo**
   reutilizando la KV cache.
3. **El gateway local es in-process, sin HTTP**: el seam ya existe
   (`LocalModelService.start()` → `LlmGateway`), en Android no se puede lanzar
   `llama-server` como proceso hijo, y un servidor HTTP en loopback sólo agrega
   copias, puertos y política de cleartext; lo único nuevo es
   `LocalCapabilities` y `PlatformContext`.
4. **Modelo recomendado: Qwen2.5 1.5B Instruct Q4_K_M (1,1 GB, Apache-2.0)
   como default** y **Phi-4-mini Q4_K_M (2,4 GB, MIT) como opción de calidad**
   para teléfonos de ≥6 GB; un modelo de 2,4 GB en un teléfono de 4 GB no
   entra (de 4 GB totales, Android deja ~2 GB para apps), así que el catálogo se
   segmenta por RAM y la app consulta `ActivityManager.MemoryInfo` antes de
   ofrecer un modelo.
5. **El plan es F0 (stub + `capabilities` + `PlatformContext`, 2–3 días) → F1
   (motor in-process arm64, 2–3 semanas) → F2 (descarga con `.part` + `Range` +
   sha256 + `WorkManager`, 1–1,5 semanas) → F3 (RAG local en el teléfono +
   herramientas, 1,5–2 semanas)**, y la primera versión de la app Android se
   entrega **sólo con modo remoto** (flag `BuildConfig.LOCAL_LLM`), comunicando
   la IA local como opción visible pero deshabilitada con el motivo en español,
   tal como manda `05-config-modelo.md` §10.2.
