# 04 · Cumplimiento normativo y política institucional

> Diseño del cumplimiento de la normativa de datos personales en Kitoru: qué
> marco legal aplica, con qué base de licitud se trata cada dato, qué se
> recoge de más, cuánto tiempo se conserva, cómo se ejercen los derechos desde
> la app, qué deja constancia de todo eso y qué necesita hacer la institución
> para poder aprobar la herramienta.
>
> Depende de: `docs/design/CONTEXTO.md`,
> `docs/design/security/01-claves.md` (modelo de secretos),
> `docs/design/security/02-privacidad.md` (modelo de consentimiento y
> visibilidad), `docs/design/security/03-threat-model.md` (amenazas y
> mitigaciones),
> `docs/design/domain/08-permisos.md` (capacidades y `AccessLog`),
> `docs/design/domain/09-identidad.md` (perfil local, alias),
> `docs/design/domain/01-catedra.md` (ciclo de vida de la cursada,
> `termStartAt`/`termEndAt`), `docs/design/teacher/08-alumnos.md` (visibilidad,
> `VisibilityRequest`, `ErasureRecord`), `docs/design/teacher/07-chats.md`
> (explorador de chats del docente) y el dominio `kitoru.domain`
> (`Roles.kt`, `Course.kt`, `Chat.kt`, `Analytics.kt`).
>
> **Este documento no redefine el modelo de privacidad**: lo recorta a la
> lengua del marco legal y añade lo que la ley exige y los otros documentos no
> cubren (base de licitud por tratamiento, minimización, retención con borrado
> automático, deber de información, ficha de tratamiento y responsabilidades de
> la institución). Los nombres canónicos son los de `02-privacidad.md`;
> la reconciliación completa está en §1.3.

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | Kitoru se apoya en **tres marcos a la vez**: RGPD (UE), Ley 25.326 (AR) y LFPDPPP (MX), y se diseña contra **el más exigente de los tres**, que es el RGPD. | La app se usa en tres países con la misma base de código. Diseñar contra el estándar más alto evita tener que rehacer nada. |
| D2 | **El dato no es el mismo según dónde vive.** El dispositivo del alumno y el Kitoru-Server de la facultad son responsables distintos, con bases de licitud y plazos distintos. | La arquitectura de "IA local" sólo sirve si la frontera de datos es real y está escrita. |
| D3 | La base de licitud se modela **por tratamiento, no por app**: un mismo dato puede tener dos bases según la finalidad (p. ej. el texto del chat: contrato para la tutoría, consentimiento para que el docente lo lea). | Es lo que hacen los tres marcos y es lo que la ficha de tratamiento tiene que poder listar. |
| D4 | La **analítica agregada** va por **interés legítimo** (RGPD 6.1.f / 25.326 art. 6), con test de equilibrio documentado y con **derecho de oposición real** (un switch que saca al alumno de los agregados sin bajar a "Oculto"). | Es la base que menos riesgo da y la única que no obliga a pedir permiso para usar la herramienta. |
| D5 | El **texto de los chats** sólo lo ve el docente **con consentimiento** del alumno, con la excepción de una **obligación legal de evaluación** declarada por la institución y activada explícitamente por el titular. | La relación docente-alumno es de dependencia: el "interés legítimo" no se sostiene para el contenido. |
| D6 | La visibilidad es **fail-closed**: sin consentimiento explícito y registrado, el techo del alumno es `METRICS_ONLY`, nunca `FULL`. En IA alojada, el aviso bloqueante de la inscripción **es** el consentimiento (y por eso el default efectivo es `FULL` avisado); en IA local, el default es `NONE`. | El valor por defecto es la decisión normativa más fuerte de la app: define qué se ve sin que nadie haga nada. Se adopta el fail-closed de `02-privacidad.md` D2. |
| D7 | La **retención es un parámetro real**: `RetentionPolicy` por cátedra (y por institución) + marca `expiresAt` en cada artefacto + **job de limpieza** que borra solo y deja un **recibo** con conteos (sin contenido). | La conservación limitada no se cumple avisando: hay que borrar. Y hay que poder probarlo. |
| D8 | Los **derechos se ejercen desde la app**: "Exportar mis datos" (JSON con esquema versionado) y "Borrar mis datos" (supresión en cascada), más rectificación, oposición y portabilidad. | Un derecho que hay que pedir por email a la facultad no se cumple en la práctica. |
| D9 | El **deber de información** se cumple en el momento de la inscripción, con un aviso **por capas** (corto en pantalla, largo y versionado en "Tus datos") y con registro de la aceptación (`NoticeAcceptance`). | Sin aviso y sin constancia de aceptación, cualquier tratamiento posterior es difícil de justificar. |
| D10 | El **registro de accesos** es la evidencia de rendición de cuentas: se conserva **más tiempo que los datos** (5 años vs. 2), es exportable y el alumno lo ve en su app. | La prueba de que se respetó la visibilidad es el registro, no la promesa. |
| D11 | La app **no decide** la base legal de la evaluación docente: el derecho de supresión, los contratos con los proveedores de modelos, la transferencia internacional y el aviso institucional son **decisiones de la institución**. | La app puede aplicar una política; no puede crearla ni firmarla. |
| D12 | El documento se apoya en los hermanos: el modelo de consentimiento es el de `02-privacidad.md`, el modelo de secretos el de `01-claves.md` y el análisis de amenazas el de `03-threat-model.md`. | No hay un segundo modelo de privacidad: hay **uno**, repartido en tres documentos que se citan y se reconcilian (§1.3). |

---

## 1. Alcance: qué es y qué no es este documento

### 1.1 Qué es

Es el diseño de **cómo Kitoru cumple** la normativa de protección de datos
personales y de **qué necesita la institución** para poder usarla en una
cátedra. Concreto: la base de licitud de cada tratamiento, la minimización, la
retención con borrado automático, los derechos del interesado, el deber de
información, la auditoría, el reparto de datos entre cliente y servidor, y el
índice de la ficha de tratamiento que la facultad tiene que poder firmar.

### 1.2 Qué NO es

> **Este documento no es un dictamen legal ni reemplaza al asbestos legal.**
> Un abogado de datos personales de la jurisdicción tiene que revisar la
> ficha de tratamiento (§11) antes de que la institución use Kitoru con datos
> reales de alumnos. Lo que este documento hace es **dejarle el trabajo
> preparado**: la app aplica una política, la registra, la exporta y deja
> constancia; lo que la institución tiene que decidir y firmar está marcado
> explícitamente en §12.

Tres precisiones que evitan malentendidos:

1. **El marco aplicable depende de la persona y del servidor**, no del código.
   Un alumno en España con la IA local no tiene ningún dato en un servidor
   argentino. Las obligaciones del proveedor de modelos son de la institución,
   no de la app.
2. **El RGPD aplica por territorialidad** (art. 3) a la institutions que ofrece
   el servicio o que observa la conducta, no "porque la app esté escrita en
   Kotlin". La LFPDPPP (México) tiene reglas de **transferencia internacional**
   distintas a las del RGPD: una institución mexicana que use un modelo
   alojado en EE.UU. tiene obligaciones que no tiene una argentina.
3. **Las disposiciones locales de la universidad** (reglamento de evaluación,
   estatutos, políticas de SIC) pueden ser **más estrictas** que la ley y en
   ese caso mandan sobre la ley. La app tiene que poder ser más laxa que
   la política de la facultad: por eso `AppSettings.managed` y la política de
   retención que llega desde el servidor.

### 1.3 Reconciliación con los documentos hermanos de seguridad

Este documento **no redefine** el modelo de privacidad: lo recorta a la
lenguaje del marco legal. Los nombres canónicos son los de los documentos
hermanos, y esta tabla es la que hay que leer antes de implementar cualquier
cosa de §4 a §9.

| Concepto | Campo / tipo canónico | Documento que lo fija | Alias en otros documentos |
|---|---|---|---|
| Techo declarado por el alumno | `Enrollment.visibility` | `02-privacidad.md` §1.4 | `studentCap` (`teacher/08-alumnos.md`), `visibilityCeiling` (`domain/08-permisos.md`) |
| Tope impuesto por el docente | `Enrollment.visibilityCap` | `02-privacidad.md` §1.4 | `visibility` en `teacher/08-alumnos.md` (significado invertido) |
| Constancia del consentimiento | `Enrollment.consent: VisibilityConsent` (`textVersion`, `textHash`) | `02-privacidad.md` §3.7 | `VisibilityConsent` (`teacher/08-alumnos.md` §2.3) |
| Cálculo de la visibilidad efectiva | `AccessControl.effectiveVisibility(...)` — **un solo lugar** | `02-privacidad.md` §1.2 | — |
| Texto de consentimiento versionado | `ConsentTexts` (`VERSION`, `ENROLL_HOSTED`, `CHOOSE_VISIBILITY`, …) | `02-privacidad.md` §3.7 | — |
| Pantalla del alumno | **"Tus datos"** (Ajustes → Tus datos) | `02-privacidad.md` §4.1 | "Tus datos" (este doc, §7.6) |
| Botón de portabilidad | **"Descargar mis datos"** | `02-privacidad.md` §4.4 | "Exportar mis datos" (este doc, §7.2) |
| Formato de la exportación | `format: "kitoru.student-export"`, `version: 1` | `02-privacidad.md` §4.4 | — |
| Borrado | **Lógico** (30 días, recuperable) → **físico** (definitivo) | `02-privacidad.md` D10 | — |
| Anonimato de los agregados | **K = 5** para mostrar, **K = 10** para exportar, con supresión secundaria | `02-privacidad.md` D7 | — |
| Secreto de la API key | `ModelPolicy.apiKeyRef` + `SecretRef` (`apikey:course:<id>`, `apikey:student:<enrollmentId>`) en el `SecureStore` | `01-claves.md` D1, D7 | `ModelPolicy.apiKey` y `CredentialStore` (`domain/09-identidad.md`) — **deuda a corregir** |
| Registro de accesos | `AccessLogEntry` append-only, **empujado al servidor** (`POST /v1/audit`) | `03-threat-model.md` §5.7, T-07 | `AccessLog` (`domain/08-permisos.md` §8) |

**Las tres reconciliaciones que este documento cambia o agrega:**

1. **El `AccessLog` local es decorativo** (`03-threat-model.md` §5.7: *"mientras
   el log sea local, la auditoría es decorativa"*). Por eso §9.3 exige que el
   registro **viva en el servidor** y que el local sea sólo una copia con
   reintento (outbox de 90 días). El `AccessLog` con `basis` legal y
   `consentId` es **la evidencia** de rendición de cuentas: si el docente
   puede borrarlo de su máquina, no es evidencia de nada.
2. **El borrado es en dos tiempos** (lógico 30 días + físico), y **avisa a los
   docentes** que leyeron los chats, pidiéndoles que confirmen la eliminación
   de su copia. Es la única forma honesta de cerrar el derecho de supresión
   cuando el dato ya fue leído (§7.5).
3. **El secreto de la API key no se exporta nunca**: `apiKeyRef` es un alias
   determinista y **no secreto** (`01-claves.md` D7), así que puede ir en el
   export sin riesgo; el valor jamás.

---

## 2. El marco

### 2.1 Las tres normas, en una tabla

| | **RGPD** (Reglamento UE 2016/679) | **Ley 25.326** (Argentina) | **LFPDPPP** (México, 2010 reformada 2025) |
|---|---|---|---|
| Ámbito | Tratamiento de datos personales de personas en la UE | Datos personales de personas en Argentina | Datos personales de personas en México |
| Derechos del titular | Acceso (15), rectificación (16), supresión (17), oposición (21), portabilidad (20), limitación (18) | Acceso (14), rectificación (16), supresión (17/18) | Acceso (18), rectificación (19), supresión (20/21), oposición (22), portabilidad (21) |
| Base de licitud | 6.1: a) consentimiento · b) contrato · c) obligación legal · d) vital · e) público · f) interés legítimo | art. 5/6: consentimiento (art. 5), y causales de ejecución de contrato / obligación legal / interés legítimo con las limitaciones del art. 6 | art. 7–13: consentimiento, excepción y legitimación |
| Consentimiento | art. 7: libre, específico, informado, inequívoco, tan fácil de retirar como de dar | art. 5: escrito, claro, expreso e informado; se presume el silencio | art. 8–13: escrito, libre, informado, inequívoco, con firma autógrafa o electrónica si el contexto lo amerita |
| Interés legítimo | art. 6.1.f + art. 7.4 (*balancing test* documentado) | art. 6 inc. d, con enumeración taxativa y condiciones | art. 10 (interés legítimo, con los límites del art. 10.3) |
| Deber de información | art. 13/14 antes del tratamiento | art. 6 (deber de información al recoger los datos) | art. 15–17 |
| Responsabilidad y cuentas | art. 5.2 + art. 30 (registro de actividades) | art. 6 inc. c (seguridad), art. 25 | art. 19–20 (responsabilidad), art. 26–28 (medidas de seguridad) |
| Registro de actividades | art. 30 — obligatorio (salvo organizaciones con menos de 250 personas) | No obligatorio en la ley; **buena práctica** y lo exige el RGPD | No obligatorio; **buena práctica** |
| Transferencia internacional | Cap. V: acordado (art. 45), salvaguardas (art. 46) | Ley 25.326 + Decreto 1.603/2018 (CCyC art. 19) | Transferencias (art. 33–36) |
| Notificación de brecha | art. 33: 72 h a la autoridad; art. 34 al titular | art. 16 | art. 20 |
| DPIA | art. 35 — obligatoria en varios supuestos | No obligatoria; recomendada | art. 35 |

> **Regla de diseño:** la app implementa el **conjunto RGPD** como suelo común.
> Si la jurisdicción local exige un plazo de respuesta más corto, la
> institución lo configura (`AppSettings.rightsResponseDays`, por defecto 30 =
> un mes, que es el plazo del RGPD art. 12(3)).

### 2.2 Los principios que de verdad aplican a una app educativa

Los principios son la parte fácil; lo difícil es traducirlos a decisiones de
código. Esta es la tabla de trabajo:

| Principio | Qué significa en la ley | Qué significa **en Kitoru** | Dónde se implementa |
|---|---|---|---|
| **Licitud** | No hay tratamiento sin una base de licitud identificable. | Cada tratamiento tiene una `LegalBasis` y aparece en la ficha. | `ProcessingActivity` (§4.2) |
| **Minimización** | Adquirir datos adecuados, pertinentes y limitados a lo necesario. | La "lista mínima" de §5. Lo que no está en la lista, no se recoge. | `DataCategory` permitido por actividad |
| **Finalidad** | Los datos se recogen para una finalidad determinada; no se usan para otra incompatible. | El `purpose` es un campo cerrado: el `AuditLog` no puede reutilizar un chat para "detectar alumnos que hacen trampa". | `ProcessingActivity.purpose` |
| **Información** | Informar al titular antes de tratar: identidad del responsable, datos, finalidad, base, destinatarios, plazo, derechos. | Aviso por capas en la inscripción + versión + constancia de aceptación (§8). | `NoticeAcceptance` |
| **Exactitud** | Datos exactos y actualizados; rectificación. | El alias es lo que ve el docente; el nombre real es local; el alumno rectifica el alias en un clic. | §7.3 |
| **Conservación limitada** | Conservar sólo lo necesario, con plazos definidos y borrado al vencer. | `RetentionPolicy` + `expiresAt` + `RetentionSweeper` (§6). | `RetentionSweeper` |
| **Seguridad** | Medidas técnicas y organizativas apropiadas al riesgo. | Aislamiento por visibilidad **en el repositorio**, no en la UI; credenciales en el `SecureStore` (fuera de la carpeta de datos); auditoría. | §9, `docs/design/domain/08-permisos.md` |
| **Derechos del interesado** | Acceso, rectificación, supresión, oposición, portabilidad, limitación. | Botones en "Tus datos" (§7). | `PrivacyService` |
| **Responsabilidad** | El responsable debe poder demostrar que cumple. | Ficha, registro de actividades, recibos de borrado, registro de accesos. | §11, §9 |
| **Rendición de cuentas** (accountability) | Documentar, medir, auditar. | `AccessLog` exportable + `PurgeReceipt` + ficha generada por la app. | §9, §11 |

### 2.3 Qué **no** es dato personal en Kitoru

No todo lo que la app guarda es un dato personal, y decirlo ahorra
tratamientos innecesarios:

| Elemento | ¿Es dato personal? | Por qué |
|---|---|---|
| `Course.id`, `Course.code`, `Course.term` | No | Datos de la cátedra, no de una persona. |
| `Material.extractedText` | **En general no** | Es material docente. Sí lo es si el material contiene datos de alumnos: por eso hay un **escaneo de PII en la carga** (§5.4). |
| `CitationStats`, `tokensIn/Out`, `latencyMs` | **Sí, si están-PCtivos** a un hilo | Son metadatos de una conversación identificada: son personales en la medida en que permiten reconstruirla. Se tratan como T6 (interés legítimo). |
| `CourseAnalytics` agregado | **Depende** | Es dato **anónimo** si lo sostienen **K = 5** alumnos o más (K = 10 para exportarlo), con supresión secundaria: un agregado por debajo del umbral es un dato de una persona y se trata como T8. |
| El texto de un chat con IA **local** | **Sí**, pero sólo para quien lo tiene | Nunca sale del dispositivo. Ver §10.2: es el caso donde el dato personal existe sin que haya transferencia. |

> **Regla del umbral (K).** Es la k-anonimidad de `02-privacidad.md` D7: un
> agregado es dato no personal si lo sostienen **K = 5** alumnos para
> **mostrarlo** y **K = 10** para **exportarlo**, con **supresión primaria y
> secundaria** (no se puede deducir un dato sensible comparando dos
> agregados). Por debajo del umbral, el agregado es un dato de una persona y
> se trata como T8. Es minimización aplicada a la visualización y, además, un
> criterio de anonimización: un agregado con K ≥ 5, sin identificadores y sin
> supresión secundaria es datos anonimizados (fuera del ámbito del RGPD, art.
> 4(1) y Recital 26; en México, art. 3 y 29 de la LFPDPPP).

---

## 3. Quién es el responsable del tratamiento

Este es el punto que más se olvida y el que más problemas genera cuando la
institución monta una herramienta de IA para sus alumnos. En Kitoru hay **cuatro
actores** y a veces **dos responsables** en paralelo sobre el mismo dato.

| Actor | Qué es | ¿Es responsable del tratamiento? | Dónde vive su responsabilidad |
|---|---|---|---|
| **El alumno** | Persona con un dispositivo donde corre Kitoru | **Sí**, de los datos de su propio dispositivo (perfil, chats con IA local, exportaciones). El es el "responsable" de su instalación, y a la vez el **titular** de los datos. | Datos que nunca salen de su máquina: no hay transferencia, no hay riesgo de fuga por el servidor. |
| **La institución** (facultad) | Entidad que opera el Kitoru-Server, emite las API keys, define el curso | **Sí**: es la responsable del servidor, del padrón de alumnos, de las analíticas y de la lectura docente de los chats. | Kitoru-Server + la instalación del docente (que es, en la práctica, un punto de datos de la institución). |
| **El docente** (titular / ayudante) | Persona que diseña la cátedra y accede a los chats | **No es responsable**: es un **encargado** (RGPD art. 4(8) / responsable del tratamiento en los términos de la ley local) que actúa por cuenta de la institución. **Pero no por su cuenta propia**: su acceso a datos de alumnos está dado por la institución, con base legal y con registro. | Sus lecturas quedan en el `AccessLog`, y su instalación es un repositorio de datos personales de la facultad. |
| **El proveedor del modelo** (si el modelo es alojado fuera) | OpenAI, Anthropic, un gateway, etc. | **Encargado** (tratamiento por cuenta) o **tercero independiente** si entrena con los prompts. **Esto lo decide el contrato, no la app.** | La app no lo puede decidir: por eso el aviso tiene que mostrar el host configurado (§8.4). |

### 3.1 Las tres reglas que se derivan

1. **El docente no actúa por cuenta propia.** Si el titularreno y le pide a un
   ayudante que revise chats, el ayudante lo hace como representante de la
   institución. La institución es la responsable y la que debe poder responder
   al titular.
2. **El alumno es responsable de su dispositivo, pero no de los datos de los
   demás.** Si exporta sus datos a un `.json` y los manda por WhatsApp, el
   problema es suyo (la app lo advierte en el diálogo de exportación, §7.2).
3. **La responsabilidad no se transfiere por el uso de la herramienta.** Que
   los datos viajen por HTTP a un modelo remoto no convierte al proveedor en
   responsable: lo convierte en *encargado del encargado*. El contrato es lo
   que define el reparto (§12).

---

## 4. La base de licitud, tratamiento por tratamiento

### 4.1 Las bases disponibles y cuál encaja

| Base | Cuándo aplica en Kitoru | Requisito que la app debe poder cumplir |
|---|---|---|
| **Ejecución del contrato** (RGPD 6.1.b) | La relación docente-alumno: inscripción a la cátedra, acceso al material, uso del modelo, tutoría con IA. | Que el contrato exista y que el tratamiento sea **necesario** para la relación. Todo lo que el alumno usa para aprender entra acá. |
| **Consentimiento** (RGPD 6.1.a) | **Leer el texto de los chats de un alumno por parte del docente**, cuando esa lectura no es necesaria para la relación. Y el uso de un modelo que **entrena** con los prompts, si lo hubiera. | Que sea libre, específico, informado, separable y revocable sin consecuencias. Por eso es **por chat y por curso**, nunca un "Acepto" global en la instalación. |
| **Interés legítimo** (RGPD 6.1.f) | La **analítica agregada** (uso del curso, calidad de las citas, detección de alumnos inactivos). | Test de equilibrio documentado + **oposición real** + que el dato sea **agregado y sin contenido**. |
| **Cumplimiento de obligación legal** (RGPD 6.1.c) | El acceso del docente cuando hay un **reglamento de evaluación** que obliga a revisar las interacciones con IA. | Que la institución **haya definido** esa obligación (norma/resolución) y que la app la registre. La app no la inventa. |
| **Interés público** (RGPD 6.1.e) | No aplica: Kitoru no es un servicio público. | — |

### 4.2 La tabla de tratamientos

**Esta es la tabla central del documento.** Es la que se vuelca en la ficha de
tratamiento (§11) y la que la institución tiene que revisar y firmada.

Leyenda de retención: *Fin de cursada + N* = N años después de `Course.termEndAt`;
*Borrado definitivo a los N años* = plazo máximo desde la creación del artefacto.

| # | Dato (campo concreto) | Finalidad | Base de licitud | Retención (por defecto) | Quién accede |
|---|---|---|---|---|---|
| **T1** | `UserProfile.displayName` (nombre real) | Identificar a la persona en su instalación; mostrarlo si ella elige | Contrato (6.1.b) | Hasta el cierre de la cuenta + 3 años | El titular. **El docente sólo si el alias es `REAL`** |
| **T2** | `Enrollment.displayName` (etiqueta/seudónimo) | Roster, atribución de hilos, analítica | Contrato (6.1.b) | Inscripción + 3 años | Titular y ayudante de **esa** cátedra |
| **T3** | `UserProfile.email` | Vincular con la cuenta institucional; enviar invitaciones | Contrato (6.1.b); interés legítimo para el envío puntual | Hasta desvincular + 6 meses | El titular; la institución sólo al vincular. **Nunca el docente**, salvo que el alumno marque `shareEmailWithTeacher = true` |
| **T4** | `ChatMessage.text` — **preguntas del alumno** | Prestar el servicio educativo (tutoría con IA sobre el material de la cátedra) | Contrato (6.1.b) | Fin de cursada + 2 años; borrado definitivo a los 5 | El titular; el docente **sólo con consentimiento u obligación legal**; el servidor de la facultad si el modelo es alojado |
| **T5** | `ChatMessage.text` — **respuestas del modelo** | Ídem + corpus de evaluación de calidad | Contrato (6.1.b) | Ídem | Ídem |
| **T6** | `ChatMessage.sources`, `citation`, `latencyMs`, `tokensIn/Out` | Analítica de **calidad** (citas verificadas), sin contenido | Interés legítimo (6.1.f) + art. 25.326 6.d | Fin de cursada + 2 años; borrado definitivo a los 5 | Titular y ayudante, si la visibilidad ≥ `METRICS_ONLY` |
| **T7** | `ChatMessage.toolCalls.args` y `.result` | Auditar el uso de herramientas (qué buscó, qué archivos tocó) | Interés legítimo, con **reserva de redacción** | Ídem; **los `args` se redactan por defecto** | Sólo el titular. El docente si `toolsEnabled` **y** visibilidad `FULL` |
| **T8** | `StudentAnalytics` (por alumno: mensajes, días activos, citas) | Seguimiento del uso; detección de alumnos que no participan | Interés legítimo (6.1.f) | Fin de cursada + 2 años; borrado definitivo a los 5 | Titular y ayudante, si la visibilidad ≥ `METRICS_ONLY` y el alumno no se opuso (§7.4) |
| **T9** | `CourseAnalytics` (agregado de cátedra) | Mejorar el curso; detectar material poco usado | Interés legítimo (6.1.f); **datos anonimizados si n ≥ 5** | Fin de cursada + 2 años; borrado definitivo a los 5 | Todo docente de la cátedra |
| **T10** | `AccessLogEntry` (registro de accesos) | Rendición de cuentas; transparencia hacia el titular; investigación | Interés legítimo + **obligación legal** de la institución de registrar la actividad docente | Fin de cursada + **5 años**; borrado definitivo a los 7 | El titular, el titular de la cátedra y la institución (en el servidor) |
| **T11** | `Material.extractedText` y archivos | Corpus del RAG de la cátedra | Contrato (6.1.b) | Cátedra + 2 años; borrado definitivo a los 10 (son datos de la institución) | Alumnos y docentes de esa cátedra |
| **T12** | `ModelPolicy.apiKeyRef` → `SecretRef` (`apikey:course:<id>`, `apikey:student:<enrollmentId>`) | Permiso de consumo del modelo alojado | Contrato (6.1.b) | **Vence al fin de la cursada** (`apiKeyExpiresAt = termEndAt`) | El alumno (en el `SecureStore`) y el emisor. El `ref` es un alias **no secreto** (`01-claves.md` D7) |
| **T13** | `deviceId` (UUID local) | Detectar inscripciones duplicadas | Interés legítimo (6.1.f) | 1 año desde la última conexión | Servidor institucional, **sólo si `telemetryEnabled = true`** (por defecto `false`) |
| **T14** | IP, user-agent, logs de seguridad (**sólo servidor**) | Seguridad, antiabuso, trazabilidad de ataques | Interés legítimo + obligación legal | **30 días** | Administradores del servidor |
| **T15** | Datos de los **docentes** (nombre, email, rol) | Relación laboral y académico | Contrato + obligación legal | Mientras dure la relación laboral + plazo estatutario | RRHH, dirección; los alumnos sólo el nombre y el rol |
| **T16** | `PurgeReceipt` (conteos de borrado, sin contenido) | **Prueba** de que se cumplió la conservación limitada | Obligación legal de la institución | 7 años | Titular, titular de la cátedra, institución |

> **Por qué el registro de accesos (T10) dura más que los chats (T4/T5).** Es
> contraintuitivo y es deliberado: el log **no contiene el contenido** que se
> borró, sólo el hecho de que alguien lo leyó. Si el log se borrara a los 2
> años y el chat a los 2 años, no quedaría forma de demostrar que se respetó
> la visibilidad. El log es la evidencia; la evidencia se conserva.

### 4.3 El caso espinoso: ¿puede el docente ver el chat de un alumno sin consentimiento?

**Respuesta corta: no, no automáticamente.** Y "automáticamente" hay que
entenderlo con precisión, porque la respuesta honesta tiene cuatro puertas y
sólo dos están abiertas por defecto.

| Puerta | ¿Se sostiene? | Qué exige | Qué tiene que hacer la institución |
|---|---|---|---|
| **1. Ejecución del contrato** | **No, por sí sola** | El contrato entre el alumno y la facultad cubre la **prestación del servicio educativo**: que el alumno pueda preguntar al modelo del curso y que el modelo le responda. **Leer** el contenido de las preguntas de un alumno para corregirlo o auditarlo es una **finalidad adicional** (evaluación de calidad del uso de la IA), no la prestación del servicio. | La institución **puede** declarar esa finalidad en su aviso. Si lo hace, la base pasa a ser el contrato **+ deber de información**. Pero **la app no la activa por defecto**: sin consentimiento explícito, el techo es `METRICS_ONLY` (fail-closed, D6 y `02-privacidad.md` D2). |
| **2. Interés legítimo** | **Muy dudoso, y en general no** | Test de equilibrio (RGPD 6.1.f / art. 7.4): el interés legítimo de la facultad en mejorar el curso y detectar uso indebido **vs.** las expectativas legítimas del alumno. El test **no se sostiene** para el contenido de las conversaciones por tres razones concretas: (a) el alumno **no puede negarse sin dejar de cursar** ⇒ no hay alternativa real ⇒ el consentimiento tampoco sería libre; (b) un chat puede contener **categorías especiales** (art. 9: salud, orientación laboral, situación de violencia, orientación sexual) ⇒ el RGPD exige una base adicional que acá no existe; (c) la asimetría de poder docente-alumno es estructural, no accidental. | Si la institución quisiera sostenerlo igual, necesita un test de equilibrio **por escrito** y un mecanismo de oposición que **no** afecte la nota. La recomendación de este diseño es no intentarlo para el texto. |
| **3. Consentimiento** | **Sí** | art. 7: libre, específico, informado, inequívoco, revocable. Es la vía que toma la app: en IA alojada, el aviso bloqueante de la inscripción (`ConsentTexts.ENROLL_HOSTED`) **es** el consentimiento; para el resto, el botón "Pedir que vea todo" (Texto D) y la `VisibilityRequest` del doc 08. Granularidad: por cátedra, con override por hilo. | La app gestiona el registro; la institución define en qué casos conviene pedirlo (evaluación, seguimiento de un alumno que pidió ayuda) y qué pasa si se niega. |
| **4. Cumplimiento de obligación legal** | **Sí, si existe** | Un **reglamento de evaluación** (o una resolución de la facultad o de la cátedra) que obligue al docente a revisar las interacciones del alumno con la IA y que **defina** la finalidad, los datos y el plazo. | La institución tiene que **dictar esa norma**. Mientras no exista, la puerta 4 **no existe** en la app: `Course.evaluationMandatory` es `false` y el botón "Revisión justificada" no aparece. |

**La regla que implementa la app** (en código, sobre el modelo del doc 08):

```
visibilidad efectiva = min(visibilidad de la cátedra, studentCap del alumno)
                     = min(lo que pidió la cátedra, lo que el alumno autorizó)
```

Con dos agregados:

- El docente **nunca** sube la visibilidad por su cuenta. Sólo puede
  (a) **aprobar** una `VisibilityRequest` del alumno, o (b) usar la vía
  institucional, que es un botón distinto y con otro nombre.
- La vía institucional — "Revisión justificada" — exige: que la cátedra tenga
  `evaluationMandatory = true` (flag que sólo puede activar el titular, y que
  la institución habilita con su norma), un texto de justificación (mín. 30
  caracteres), y **notifica al alumno** en su app. El acceso queda en el
  `AccessLog` con `basis = LEGAL_OBLIGATION`, que es la evidencia de que el
  acceso fue institucional y no personal.

**Por qué esto es defendible ante un titular que se queja.** La respuesta es
la cadena de pruebas, no el discurso:

1. El aviso (art. 13) decía exactamente qué se recoge, para qué, quién lo
   ve y cuánto tiempo.
2. El alumno **eligió una visibilidad concreta** y la app **nunca** la sube
   sola: en IA alojada, un clic explícito en el aviso bloqueante de la
   inscripción; sin ese clic, el techo queda en `METRICS_ONLY` (fail-closed,
   `02-privacidad.md` D2).
3. Cada lectura del texto quedó en un registro con **actor, momento, alcance y
   base legal** (`AccessLogEntry`).
4. El alumno puede **ver ese registro en su propia app** y exportarlo.

Si el titular está de acuerdo con 1–4, el procesamiento es defendible
cualquiera que sea la base. Si falta el paso 2 o el 3, no hay defensa posible:
por eso la app insiste en ellos.

### 4.4 Cómo se registra y cómo se retira el consentimiento

El consentimiento tiene que ser **tan fácil de retirar como de dar**, y el
registro tiene que sobrevivir al retiro (la prueba de que se dio).

**Lo que ya existe** (`02-privacidad.md` §3.7) es el consentimiento de
**visibilidad**: vive en `Enrollment.consent: VisibilityConsent` y ya lleva
`state`, `level`, `grantedAt`, `withdrawnAt`, `textVersion` y `textHash`.
Ese es el registro canónico del consentimiento de que el docente lea el
texto y **este documento no lo duplica**.

Lo que este documento **agrega** es que no todos los consentimientos son de
visibilidad: hay consentimientos de otros tratamientos (mandar las
conversaciones a un proveedor externo, compartir el detalle de las
herramientas). Por eso se propone un registro **general**, del que el de
visibilidad es un caso particular:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Privacy.kt
// (se AGREGA a lo que ya define 02-privacidad.md §3.7)

/**
 * Qué se cede con un consentimiento.
 *
 * [VISIBILITY] es el caso que ya existe en `Enrollment.consent`
 * (`02-privacidad.md` §3.7): el texto de las conversaciones de una cátedra.
 * Los demás son consentimientos que la ley puede pedir y que Kitoru no tenía.
 */
@Serializable
enum class ConsentScope(val label: String) {
    /** El docente puede leer el texto de las conversaciones. */
    VISIBILITY("Lectura del texto de las conversaciones"),

    /** El docente puede ver los metadatos de las herramientas invocadas. */
    TOOL_DETAILS("Detalle de las herramientas invocadas"),

    /** El alumno autoriza el envío de sus conversaciones a un proveedor externo. */
    EXTERNAL_MODEL("Envío de las conversaciones a un proveedor externo"),
}

/**
 * Un consentimiento explícito, con su prueba, para un tratamiento que **no**
 * es el de visibilidad.
 *
 * El consentimiento de visibilidad **no** se duplica acá: vive en
 * `Enrollment.consent` (`VisibilityConsent`), que ya tiene `textVersion` y
 * `textHash`. Este tipo cubre los consentimientos restantes.
 */
@Serializable
data class ConsentRecord(
    val id: String,
    val courseId: String,
    val userId: String,
    /** Qué consiente, en una línea legible. */
    val purpose: String,
    val scope: ConsentScope,
    val state: ConsentState = ConsentState.NOT_REQUESTED,
    val requestedAt: Long = 0L,
    val grantedAt: Long = 0L,
    val withdrawnAt: Long = 0L,
    /** Versión del texto mostrado (la de `ConsentTexts.VERSION`). */
    val textVersion: String = "",
    /** SHA-256 del texto exacto mostrado. */
    val textHash: String = "",
    /** Modo de IA en el momento del consentimiento (LOCAL / REMOTE / HYBRID). */
    val aiMode: StudentAiMode = StudentAiMode.HOSTED,
    /** Nota opcional del alumno. */
    val note: String = "",
)
```

**Reglas del consentimiento** (las cuatro son del marco legal, no del diseño):

1. **Nunca es implícito.** Un clic en "Acepto" al final de un formulario largo
   no es consentimiento válido para leer conversaciones. El texto que se
   muestra es corto, literal, y su hash queda guardado (`ConsentTexts`,
   `02-privacidad.md` §3.7).
2. **Es granular y por cátedra** (con override por hilo), nunca por mensaje
   (`02-privacidad.md` D6).
3. **Se retira en un clic** desde "Tus datos". El retiro **baja la visibilidad
   efectiva de inmediato** y **no deshace lo ya leído**: la app lo dice con
   las palabras exactas de `02-privacidad.md` §2.4 — *"Lo que ya leyó antes
   queda: no se puede deshacer. Si querés que desaparezca, usá «Borrar mis
   datos»."*
4. **El registro sobrevive al retiro** (con `withdrawnAt`), porque es la
   evidencia de la rendición de cuentas: prueba que se pidió, que se concedió
   y que se retiró.

### 4.5 Transferencias y proveedores de modelos

Este es el punto que más se pasa por alto y el que más caro sale a la
institución.

Si el modelo está **alojado en el Kitoru-Server de la facultad** (llama.cpp),
**no hay transferencia a terceros**: el dato no sale de la institución. Es la
situación ideal y es la que el diseño de "IA local" del proyecto empuja.

Si el modelo está **alojado fuera** (cualquier gateway compatible con
OpenAI), entonces cada prompt es una **transferencia internacional** de datos
personales. Consecuencias concretas:

| Consecuencia | Qué tiene que hacer la institución | Qué hace la app |
|---|---|---|
| Salvaguarda de transferencia (RGPD cap. V; en México art. 33-36; en AR art. 19 CCyC) |firmar cláusulas contractuales tipo con el proveedor | **Mostrar el host configurado** en el aviso de inscripción, con el país si se puede determinar |
| Uso del prompt para entrenar el modelo |_cláusula de "no training" | Mostrar un aviso si el host es un proveedor conocido de entrenamiento |
| Retención de prompts en el proveedor |_acuerdo de "retention = 0" | **Parametrizar `ModelPolicy.logPrompts`**: el servidor no loguea prompts salvo que la institución lo habilite **con una justificación** |
| Ubicación del dato | | `ModelPolicy.remoteBaseUrl` se muestra literally en la pantalla de inscripción: *"Tu docente configuró el modelo alojado en `api.university.edu`"* |

> **Decisión de diseño:** `Course.modelPolicy.logPrompts` existe **por defecto en
> `false`**, y activarlo exige que el titular deje una justificación escrita
> que queda en el `AccessLog`. Es una de esas cosas que son triviales de poner
> y gravísimas de omitir.

---

## 5. Minimización

### 5.1 Qué se recoge de más hoy

Revisando el dominio actual (`UserProfile`, `Enrollment`, `ChatThread`,
`ChatMessage`, `Material`, `Course`) contra la lista de la ficha:

| Dato | ¿Hace falta? | Veredicto | Qué se hace |
|---|---|---|---|
| **`UserProfile.email`** | **Casi nunca.** Sólo hace falta para (a) vincular con la cuenta institucional, (b) que el servidor reconozca a la persona, (c) enviar invitaciones. | **Sobrecarga.** | Sigue siendo **opcional** (`email = ""` por defecto). **Nunca** va al roster exportado (contradice la columna `email` del CSV del doc 08 → se corrige: la columna `email` del CSV sólo se llena si el alumno marcó `shareEmailWithTeacher = true`; en caso contrario va vacía, como ya se hace con las métricas de los alumnos `NONE`). |
| **Ubicación / geolocalización** | **Nada.** Ninguna funcionalidad de Kitoru la necesita: el RAG es sobre el material de la cátedra, la analítica agrupa por día con la zona horaria del perfil. | **No se recoge. Punto.** | La app **no declara ningún permiso de ubicación** en el manifiesto de Android (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` no aparecen). Si alguna vez hace falta, tiene que pasar por el sistema de permisos de herramientas (`ToolCategory`) con concesión por chat, como la web o los archivos. |
| **Dirección IP** | Sólo en el servidor, para seguridad. | **Sólo servidor, 30 días.** | El cliente **no** la ve ni la guarda. El log del servidor la guarda en formato truncado (`/24` IPv4) tras los primeros 30 días, y se borra entera a los 30. |
| **`deviceId`** | Detectar inscripciones duplicadas en el servidor. | **Opcional y apagado por defecto.** | `AppSettings.telemetryEnabled = false`. Sin `telemetryEnabled`, el `deviceId` **no viaja**. Cuando viaja, viaja **hasheado con sal por servidor** (`sha256(deviceId + serverSalt)`), de modo que el servidor no puede reconstruirlo ni conservarlo más allá de la deduplicación. |
| **Avatar remoto** | Puramente decorativo. | **Riesgo unnecessary.** | Por defecto `INITIALS`. En instalaciones `managed`, las avatares remotos están bloqueados: una URL de avatar es una petición a un tercero desde el dispositivo del alumno, y eso es una transferencia que nadie pidió. |
| **`ChatMessage.toolCalls.args`** | Puede contener texto del alumno (una búsqueda web, un fragmento de archivo). | **Redacción por defecto.** | `ToolCallStats` gana `argsRedacted: Boolean` y `argsPreview: String`. El texto completo sólo se guarda si la categoría de la herramienta lo requiere y el chat es local; el docente nunca ve los `args` completos salvo `TOOLS` + visibilidad `FULL` + `ConsentScope.TOOL_DETAILS`. |
| **`Material.extractedText` completo** | Necesario para el RAG. | **Con escaneo de PII.** | Al subir un material, la app escanea el texto extraído buscando emails, teléfonos, DNI/CUIT y patterns de legajo, y **avisa al docente**: *"Este material contiene 3 correos y 1 teléfono. Si son datos de alumnos, el texto del material se envía al modelo del alumno y queda en su dispositivo: no lo subas."* Con opción de redactar antes de publicar. |
| **Hora exacta de cada mensaje** | Sí: es la unidad de la analítica (actividad por día, latencia). | **Se queda, pero se minimiza en la exportación**: la exportación propia trae la fecha; la exportación del docente (CSV del roster) trae **sólo el día**, no la hora. | `CourseAnalytics.perDay` ya agrupa por día; el detalle fino se queda en el dispositivo del alumno. |
| **`ChatThread.tags`, `title`** | Contenido del alumno. | **Se queda** (es del alumno) pero **nunca se exporta en el CSV del roster** y sólo aparece en la exportación JSON con texto (T5) si la visibilidad es `FULL`. | — |
| **Nombre real en `Enrollment.displayName`** | Depende del alias elegido. | **Ya resuelto** por el doc 09: `authorName` = etiqueta, nunca el nombre real. | Se agrega un **test de regresión**. |
| **`Enrollment.email`** (el que agrega el docente a mano) | Sirve para invitar. | **Se queda, pero es dato de la institución**, no del alumno: no se exporta salvo `shareEmailWithTeacher`. | — |

### 5.2 La lista mínima, actor por actor

**Lo mínimo que la app necesita para funcionar como herramienta educativa:**

| Necesidad | Dato mínimo | Por qué no hace falta más |
|---|---|---|
| Saber quién es el alumno | `UserProfile.id` (UUID local) + `Enrollment.displayName` (etiqueta) | No hace falta nombre real, ni documento, ni dirección, ni fecha de nacimiento, ni teléfono. **Nunca** un identificador institucional en el roster. |
| Saber a qué cátedra pertenece | `Enrollment.courseId` + `Enrollment.role` + `Enrollment.joinedAt` | No hace falta "carrera", "comisión", "turno" ni "sede" dentro de la app: eso lo sabe el LMS institucional, si lo hay. |
| Prestar el servicio | `ChatThread` + `ChatMessage` (texto, rol, fecha) + `sources` + `citation` | No hace falta geolocalización, ni accelerometer, ni clipboard, ni contactos, ni historial de otras apps. |
| Medir la calidad | `latencyMs`, `tokensIn/Out`, `citation` (verificado / sin respaldo / inexistente) | Los contadores ya son **el resultado de una función pura** (`CitationVerifier`): no hacen falta LLM extra ni re-lectura de los chats. |
| Cumplir la obligación de información | `NoticeAcceptance(noticeId, noticeVersion, acceptedAt)` | Un booleano por inscripción, no un expediente. |

**Lo que la app NO pide nunca:**

- Permisos de ubicación, cámara, micrófono, contactos, SMS, almacenamiento
  compartido, bluetooth.
- Identificadores de hardware del dispositivo (IMEI, MAC, Android ID): sólo un
  UUID aleatorio generado por la app.
- Lectura de archivos fuera de la carpeta de la app y de la carpeta que el
  usuario elija en el selector de archivos.
- Contactos, calendario, historial de navegación.
- Publicidad, analítica de terceros, crash reporting a servicios externos en
  una instalación `managed`.

> **La app debería poder publicarse sin un solo permiso sensible en el
> manifiesto.** Es un criterio de aceptación verificable (y una buena
> pregunta para la ficha: *"¿qué permisos pide la app? ninguno, salvo el de
> almacenamiento propio"*).

### 5.3 El mismo criterio aplicado al servidor

| No guardar en el servidor | Por qué |
|---|---|
| El texto de los prompts, si `logPrompts = false` | El servidor es un proxy de inferencia, no un archivo de chats. El texto vive en el cliente. |
| Las API keys de los alumnos en texto plano | Se guardan hasheadas (bcrypt/argon2) o, mejor, sólo el **prefijo visible** + hash. |
| El `subject` institucional sin necesidad | Si el servidor no lo necesita para deduplicar, no lo guarda. |
| Los datos de los alumnos que usan IA local | No llegan. Punto. |
| La IP más de 30 días | — |

---

## 6. Retención

### 6.1 Los plazos por defecto

Estos son los valores por defecto de la app. **Todos son configurables por la
institución** (§6.3); la institución puede sólo **acortarlos** desde una
instalación gestionada (`managed = true`).

| Artefacto | Referencia | Retención | Borrado definitivo | Fundamento |
|---|---|---|---|---|
| Conversaciones y mensajes (T4/T5) | fin de cursada | **+ 2 años** | **+ 5 años** desde la creación | 2 años cubre el ciclo académico (evaluaciones, recuperatorios, equivalencias); 5 años es el tope porque el dato ya no aporta nada después |
| Metadatos y analítica por alumno (T6/T8) | fin de cursada | **+ 2 años** | + 5 años | Mismo rationale que el texto: sin contenido, el riesgo es bajo |
| Analítica agregada de cátedra (T9) | fin de cursada | + 2 años | + 5 años | Es la base del interés legítimo: sin datos históricos no hay "equilibrio" que defender |
| Registro de accesos (T10) | fin de cursada | **+ 5 años** | + 7 años | Es la evidencia (§4.2). Sin el log, no hay rendición de cuentas |
| Inscripción y padrón (T2) | fin de cursada | + 3 años | + 7 años | El padrón es un registro académico: el plazo estatutario de la institución (libros de actas) suele ir en esa zona |
| Perfil local (T1, T3) | cierre de la cuenta | + 3 años | + 5 años | Permite que el alumno vuelva sin perder su historial |
| Materiales de la cátedra (T11) | fin de cursada | + 2 años | + 10 años | Son obra de la institución, no dato personal |
| API key (T12) | fin de cursada | **0: vence** | inmediata | `apiKeyExpiresAt = termEndAt` (doc 01) |
| `deviceId` (T13) | última conexión | 1 año | 1 año | — |
| IP y logs de seguridad (T14) | generación | **30 días** | 30 días | Necesario para investigar incidentes, no más |
| Recibo de borrado (T16) | generación | 7 años | 7 años | Es la prueba de la conservación limitada |

### 6.2 El modelo: `RetentionPolicy` + `expiresAt`

Dos piezas, una por lado:

1. **La política** (`RetentionPolicy`): cuánto se conserva, por tipo de dato.
   Es **configurable por la institución** y viaja con el curso o la instala.
2. **La marca** (`expiresAt`): **cuándo vence este artefacto concreto**.
   Se calcula **en el momento de escribir**, no cuando vence la cursada, para
   que el dato tenga un destino desde que nace.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Retention.kt (nuevo)

/** Los tipos de dato que tienen política de retención propia. */
@Serializable
enum class RetentionKind(val label: String) {
    CHAT("Conversaciones y mensajes"),
    ANALYTICS("Analítica por alumno"),
    COURSE_ANALYTICS("Analítica agregada de cátedra"),
    ACCESS_LOG("Registro de accesos"),
    ENROLLMENT("Inscripciones y padrón"),
    PROFILE("Perfil local"),
    MATERIAL("Materiales de la cátedra"),
    PURGE_RECEIPT("Recibos de borrado"),
    ;
}

/**
 * Una regla de conservación.
 *
 * - [afterTermDays]: días que sobrevive el dato **después** del fin de la
 *   cursada. Es el plazo que la institución piensa.
 * - [maxAgeDays]: tope máximo **desde la creación**, y es el borrado
 *   definitivo. Existe para que un dato no viva indefinidamente si la
 *   cursada se reabre o si el fin de cursada nunca se fija.
 */
@Serializable
data class RetentionRule(
    val kind: RetentionKind,
    val label: String = "",
    /** Días desde el fin de la cursada. */
    val afterTermDays: Int = 730,          // 2 años
    /** Días desde la creación: borrado definitivo. */
    val maxAgeDays: Int = 1825,            // 5 años
    /** Si es `true`, la regla no se aplica (investigación en curso). */
    val legalHold: Boolean = false,
) {
    fun afterTermMillis(): Long = afterTermDays * MILLIS_PER_DAY
    fun maxAgeMillis(): Long = maxAgeDays * MILLIS_PER_DAY
}

/**
 * La política de retención de una instalación o de una cátedra.
 *
 * Es un **parámetro real**: la app la aplica automáticamente (§6.4).
 * En una instalación gestionada por la institución, la política llega del
 * servidor y el usuario local no puede alargarla.
 */
@Serializable
data class RetentionPolicy(
    val id: String = "default",
    val name: String = "Política por defecto",
    val rules: Map<RetentionKind, RetentionRule> = defaultRules(),
    /** Si es `true`, el barrido no borra nada (investigación, litigio). */
    val legalHold: Boolean = false,
    /** Versión de la política: queda en los recibos de borrado. */
    val version: String = "1",
) {
    fun rule(kind: RetentionKind): RetentionRule =
        rules[kind] ?: defaultRules()[kind]!!

    /** La política **más restrictiva** entre dos (la institution manda). */
    fun stricterThan(other: RetentionPolicy): RetentionPolicy = RetentionPolicy(
        id = if (other.id != id) other.id else id,
        name = if (other.id != id) other.name else name,
        rules = RetentionKind.entries.associateWith { kind ->
            val a = rule(kind)
            val b = other.rule(kind)
            RetentionRule(
                kind = kind,
                label = a.label,
                afterTermDays = minOf(a.afterTermDays, b.afterTermDays),
                maxAgeDays = minOf(a.maxAgeDays, b.maxAgeDays),
                legalHold = a.legalHold || b.legalHold,
            )
        },
        legalHold = legalHold || other.legalHold,
        version = version,
    )

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        /** Los valores por defecto de §6.1. */
        fun defaultRules(): Map<RetentionKind, RetentionRule> = mapOf(
            RetentionKind.CHAT to RetentionRule(RetentionKind.CHAT, "Conversaciones y mensajes", 730, 1825),
            RetentionKind.ANALYTICS to RetentionRule(RetentionKind.ANALYTICS, "Analítica por alumno", 730, 1825),
            RetentionKind.COURSE_ANALYTICS to RetentionRule(RetentionKind.COURSE_ANALYTICS, "Analítica de la cátedra", 730, 1825),
            RetentionKind.ACCESS_LOG to RetentionRule(RetentionKind.ACCESS_LOG, "Registro de accesos", 1825, 2555),
            RetentionKind.ENROLLMENT to RetentionRule(RetentionKind.ENROLLMENT, "Inscripciones y padrón", 1095, 2555),
            RetentionKind.PROFILE to RetentionRule(RetentionKind.PROFILE, "Perfil local", 1095, 1825),
            RetentionKind.MATERIAL to RetentionRule(RetentionKind.MATERIAL, "Materiales de la cátedra", 730, 3650),
            RetentionKind.PURGE_RECEIPT to RetentionRule(RetentionKind.PURGE_RECEIPT, "Recibos de borrado", 2555, 2555),
        )
    }
}
```

**El cálculo del vencimiento.** Cada artefacto lleva `expiresAt` (epoch ms,
`0` = sin vencimiento todavía). Se calcula al escribir:

```kotlin
object RetentionResolver {

    /**
     * Vencimiento de un artefacto creado en [createdAt] dentro de [course].
     *
     * effective = min(creación + tope, fin de cursada + plazo de la regla)
     *
     * El mínimo manda: si la cursada termina mañana, el chat de hoy vence
     * dentro de 2 años, no dentro de 5.
     */
    fun expiresAt(
        kind: RetentionKind,
        policy: RetentionPolicy,
        course: Course,
        createdAt: Long,
    ): Long {
        if (policy.legalHold) return 0L
        val rule = policy.rule(kind)
        if (rule.legalHold) return 0L
        val hard = createdAt + rule.maxAgeMillis()
        val termEnd = course.termEndAt
        val soft = if (termEnd > 0L) termEnd + rule.afterTermMillis() else hard
        return minOf(hard, soft)
    }

    /** Vencimiento del archivo completo de la cátedra (todos los tipos). */
    fun courseDeadline(course: Course, policy: RetentionPolicy): Long {
        val base = if (course.termEndAt > 0L) course.termEndAt else course.createdAt
        return base + policy.rule(RetentionKind.CHAT).afterTermMillis()
    }
}
```

**Los campos nuevos** (todos con default ⇒ los JSON viejos siguen leyéndose):

| Tipo | Campo nuevo | Tipo |
|---|---|---|
| `ChatThread` | `expiresAt: Long = 0L` | `core/.../domain/Chat.kt` |
| `ChatMessage` | `expiresAt: Long = 0L` | ídem |
| `Enrollment` | `expiresAt: Long = 0L`, `dataErased: Boolean = false` | `core/.../domain/Course.kt` |
| `Material` | `expiresAt: Long = 0L` | ídem |
| `Course` | `retentionPolicy: RetentionPolicy = RetentionPolicy()` | ídem |
| `AppSettings` | `retentionPolicy: RetentionPolicy = RetentionPolicy()`, `telemetryEnabled: Boolean = false`, `rightsResponseDays: Int = 30` | `core/.../domain/AppSettings.kt` (doc 08) |
| `AccessLogEntry` | `expiresAt: Long = 0L`, `basis: LegalBasis? = null`, `consentId: String = ""` | `core/.../domain/AccessLog.kt` (doc 08) |

### 6.3 Configuración: tres niveles, gana el más restrictivo

```
        AppSettings.retentionPolicy        (instalación local, por defecto)
                 │
    Course.retentionPolicy                (la cátedra; el docente la fija)
                 │
    política que llega del servidor       (institutional; managed = true)
                 │
                 ▼
      min() de los tres  →  la más restrictiva
```

Reglas:

1. **El docente puede acortar, no alargar.** `Course.retentionPolicy` se
   calcula como `appSettings.retentionPolicy.stricterThan(la que eligió el
   docente)`. Si el docente quiere 1 año y la app tiene 2, gana 1. Si quiere 5
   y la app tiene 2, gana 2.
2. **En una instalación `managed`, el servidor manda** y el usuario local no
   puede tocar la política. El servidor publica la política con cada
   inscripción (`POST /v1/join` la devuelve) y la app la adopta.
3. **La política se versiona.** `RetentionPolicy.version` queda en todos los
   recibos de borrado: si la institución cambia la política a mitad de cursada,
   se puede demostrar con qué política se borró cada cosa.
4. **Cambio de cursada.** Si el titular **cierra** la cursada
   (`Course.close()`, doc 01), se recalculan todos los `expiresAt` de la
   cátedra con `termEndAt = now`. Si la **reabre**, se recalculan al revés.
   Por eso `expiresAt` **no** es inmutable: lo recalcula `CourseLifecycle`.

### 6.4 El mecanismo: el job de limpieza

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Retention.kt (extracto)

/**
 * El recibo de un borrado por retención.
 *
 * Es la **prueba** de que el borrado ocurrió (T16). Contiene conteos y
 * **nunca contenido**: el recibo no puede reintroducir el dato que borró.
 */
@Serializable
data class PurgeReceipt(
    val id: String,
    val at: Long,
    val courseId: String,
    val policyId: String,
    val policyVersion: String,
    /** Qué se borró: CHAT, ENROLLMENT, … */
    val kinds: List<RetentionKind>,
    val threadsErased: Int = 0,
    val messagesErased: Int = 0,
    val enrollmentsErased: Int = 0,
    val materialsErased: Int = 0,
    /** Motivo: "vencimiento de retención". */
    val reason: String = "Vencimiento de la política de retención",
    /** Si la política estaba en legal hold, no se borró nada. */
    val legalHold: Boolean = false,
)

/**
 * Barrido de retención.
 *
 * Es **determinista** y **sin reloj propio**: recibe `now` por parámetro, así
 * que se puede testear entero sin esperar dos años.
 */
class RetentionSweeper(
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val enrollments: EnrollmentRepository,
    private val materials: MaterialRepository,
    private val receipts: PurgeReceiptRepository,
    private val audit: RetentionAudit,
) {
    /**
     * Borra todo lo vencido en la cátedra.
     *
     * Nunca borra de más: sólo artefactos cuyo [expiresAt] ya pasó, o
     * artefactos de un curso cuyo plazo de archivo venció.
     */
    suspend fun sweep(course: Course, policy: RetentionPolicy, now: Long): PurgeReport {
        if (policy.legalHold) {
            return PurgeReport(legalHold = true, receipt = null)
        }

        val deadline = RetentionResolver.courseDeadline(course, policy)
        var threadsErased = 0
        var messagesErased = 0
        var enrollmentsErased = 0

        // 1. Hilos y mensajes.
        for (thread in threads.listForCourse(course.id)) {
            val vencido = (thread.expiresAt in 1..now) || (deadline in 1..now)
            if (!vencido) continue
            val count = messages.listForThread(thread.id).size
            messages.delete(thread.id)
            threads.delete(thread.id)
            threadsErased++
            messagesErased += count
        }

        // 2. Inscripciones: se anonimizan, no se borran (el padrón necesita
        //    saber que esa persona estuvo y por qué se fue).
        if (deadline in 1..now) {
            for (enrollment in enrollments.listForCourse(course.id)) {
                if (enrollment.dataErased) continue
                enrollments.save(
                    enrollment.copy(
                        dataErased = true,
                        displayName = "(datos suprimidos)",
                        expiresAt = now,
                    )
                )
                enrollmentsErased++
            }
        }

        // 3. Recibo: la evidencia.
        val receipt = PurgeReceipt(
            id = newId(),
            at = now,
            courseId = course.id,
            policyId = policy.id,
            policyVersion = policy.version,
            kinds = listOf(RetentionKind.CHAT, RetentionKind.ENROLLMENT),
            threadsErased = threadsErased,
            messagesErased = messagesErased,
            enrollmentsErased = enrollmentsErased,
            legalHold = false,
        )
        receipts.append(receipt)
        audit.record(receipt)

        return PurgeReport(
            legalHold = false,
            receipt = receipt,
            threadsErased = threadsErased,
            messagesErased = messagesErased,
            enrollmentsErased = enrollmentsErased,
        )
    }
}
```

**Cuándo corre el job:**

| Momento | Comportamiento |
|---|---|
| **Arranque de la app** | Barrido de todas las cátedras locales. Es el momento barato y suficiente para una app de escritorio. |
| **Cada 6 horas** (desktop) | Un `CoroutineScope` de aplicación con un `delay` en bucle. En Android, un `expect/actual` `schedulePeriodicTask` (desktop: un hilo de fondo; Android: `WorkManager` con un `PeriodicWorkRequest` de 6 h, que es el mínimo que Android permite). |
| **Al cerrar una cursada** | `Course.close()` dispara el barrido inmediato de esa cátedra: el chat deja de crecer y empieza a contar. |
| **Al cambiar la política** | Recalcula `expiresAt` de todos los artefactos y barre lo que ya venció. |
| **En el servidor** | El Kitoru-Server barre sus propias copias con la misma política (implementación server-side del mismo algoritmo: la lógica es **compartida** en `:core` para que no diverjan). |

**El barrido tiene que ser real, no cosmético.** Concretamente:

1. Borra los mensajes del hilo (`MessageRepository.delete(threadId)` — el
   repositorio ya borra por id de hilo, así que alcanza).
2. Borra el hilo (`ThreadRepository.delete`).
3. **Borra los paquetes importados** que el docente tenga en
   `courses/<id>/imports/<pkgId>/` (doc 07, D9): si no, el docente conserva una
   copia del chat del alumno fuera del control de la retención.
4. **Anomina la inscripción** en vez de dejarla con el nombre del alumno: el
   padrón puede tener una fila "datos suprimidos", no una fila con datos
   personales de alguien a quien ya se le borró todo.
5. Deja el recibo.

### 6.5 Casos borde de la retención

| Caso | Comportamiento definido |
|---|---|
| La cursada nunca se cierra (`termEndAt = 0`) | Se usa `course.createdAt` como base del plazo blando; el tope duro (`createdAt + 5 años`) sigue aplicando. |
| El docente reabre una cursada cerrada | Se recalculan los `expiresAt` **hacia adelante** (se restituye el plazo) **sólo** para los artefactos no vencidos. Los ya vencidos y borrados **no se recuperan**: por eso el borrado definitivo es a los 5 años y no a los 2. |
| Un alumno pide borrar sus datos antes de que venza | La supresión (§7.5) es **inmediata** y no espera a la retención. El `expiresAt` del resto sigue su curso. |
| `legalHold = true` | El barrido no borra nada y emite un recibo con `legalHold = true` para dejar constancia de que se suspendió la política. Sólo el titular puede ponerlo, y queda auditado. |
| El reloj del dispositivo está mal puesto (adelantado) | `expiresAt` se compara con `now` del sistema. Una máquina con el reloj 10 años adelante borraría todo. Mitigación: el servidor manda su `now` y el cliente usa `min(nowLocal, nowServidor + 5 min)` cuando está vinculado. Sin servidor, se avisa en el log de purga si el salto es mayor a 30 días. |
| Dos dispositivos con la misma cátedra | Cada uno barre **sus** datos. El barrido no es transaccional entre dispositivos: es por diseño (el modelo local no puede sincronizar borrados). En la arquitectura con servidor, el borrado es **del servidor** y el cliente lo propaga. |
| El docente tiene el chat exportado a un `.json` en su escritorio | **Kitoru no puede borrarlo.** La app lo advierte al exportar: *"Si compartís este archivo, el borrado posterior desde Kitoru no lo alcanza."* Es la parte del derecho de supresión que depende de la conducta del tercero. |

---

## 7. Los derechos del interesado, desde la app

### 7.1 Mapa: derecho → acción en la app

| Derecho (RGPD / 25.326 / LFPDPPP) | Cómo se ejerce en Kitoru | Dónde | Plazo |
|---|---|---|---|
| **Acceso** (art. 15 / 14 / 18) | Botón **"Descargar mis datos"** → archivo JSON con todo | Ajustes → **Tus datos** | Inmediato (local). Si hay datos en el servidor, ≤ 30 días |
| **Rectificación** (art. 16 / 16 / 19) | Editar nombre y alias en línea; el alias se re-materializa en el padrón | Ajustes → Perfil; Ajustes → Tus datos | Inmediato |
| **Supresión** (art. 17 / 17 / 20) | Botón **"Borrar mis datos"** → borrado lógico (30 días) → físico + recibo | Ajustes → **Tus datos** | Inmediato (local). ≤ 30 días (servidor) |
| **Oposición** (art. 21 / 21 / 22) | Switch **"No incluir mis datos en la analítica"** (interés legítimo, T8) + bajar la visibilidad a "Oculto" | Ajustes → Tus datos | Inmediato |
| **Limitación** (art. 18) | No implementado como bloqueo de procesamiento; **se documenta como no disponible** y se compensa con la supresión | — | — |
| **Portabilidad** (art. 20 / 21) | **El mismo** botón "Descargar mis datos": JSON legible por máquina | Ajustes → Tus datos | Inmediato |
| **Retirar el consentimiento** (art. 7(3)) | Bajar la visibilidad / revocar `ConsentRecord` | Ajustes → Tus datos | Inmediato, con efecto en el acceso siguiente |

> **Decisión de diseño: un botón, dos derechos.** "Descargar mis datos"
> cumple simultáneamente **acceso** y **portabilidad**: el formato es el mismo
> (JSON legible por máquina), porque un PDF no sería portabilidad y un JSON
> sí es un formato estructurado y reutilizable. El formato canónico es
> `kitoru.student-export` v1 (`02-privacidad.md` §4.4); este documento fija el
> **esquema detallado** de ese formato. La app **además** ofrece una vista
> legible en Markdown, pero la forma canónica es el JSON.

### 7.2 "Descargar mis datos": el botón y el formato

**El diálogo:**

```
Descargar mis datos
────────────────────────────────────────────────────────
Se va a guardar un archivo con TODO lo que Kitoru tiene de vos:

  ✓ Tu perfil (nombre, alias, idioma, zona horaria)
  ✓ Tus inscripciones y tu visibilidad en cada cátedra
  ✓ Tus conversaciones, con el texto de cada mensaje
  ✓ Tus métricas y tu actividad por día
  ✓ Los accesos que los docentes hicieron a tus conversaciones
  ✓ Los consentimientos que diste y retiraste

Formato: JSON (kitoru.student-export v1, esquema documentado en la app)
Tamaño estimado: 4,2 MB

El archivo sale en tu EQUIPO. Kitoru no lo sube a ningún lado.
Si lo compartís, queda bajo tu responsabilidad: el borrado posterior
desde Kitoru no alcanza a una copia que ya hiciste.

                        [Cancelar]  [Descargar]
```

**El esquema del export** (canónico, versionado, documentado en
`docs/schemas/kitoru-student-export-v1.schema.json`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Export.kt (extracto)

/**
 * La exportación de datos personales del titular.
 *
 * Es un **formato versionado**: `version` sube sólo con cambios
 * incompatibles. La app muestra el esquema en "Descargar mis datos →
 * Ver el esquema" para que el documento sea autosuficiente.
 *
 * El `format` es el mismo del paquete de compartición (`02-privacidad.md`
 * §4.4): lo que los distingue es el `subject` — el paquete que el alumno
 * comparte con el docente va seudonizado; el export personal lleva los datos
 * reales.
 */
@Serializable
data class PersonalDataExport(
    /** Discriminante del formato: "kitoru.student-export". */
    val format: String = "kitoru.student-export",
    /** Versión del esquema. 1 = inicial. */
    val version: Int = 1,
    val exportedAt: Long,
    val generator: String = "Kitoru",
    val generatorVersion: String = "",
    /** Identificador del perfil exportado (UUID local). */
    val subjectId: String,
    val profile: ExportProfile,
    val enrollments: List<ExportEnrollment> = emptyList(),
    val threads: List<ExportThread> = emptyList(),
    val messages: List<ExportMessage> = emptyList(),
    val analytics: ExportAnalytics? = null,
    /** Accesos hechos **sobre** los datos de esta persona. */
    val accesses: List<ExportAccess> = emptyList(),
    /** Consentimientos dados y retirados. */
    val consents: List<ExportConsent> = emptyList(),
    /** Avisos aceptados (con su versión). */
    val noticeAcceptances: List<ExportNoticeAcceptance> = emptyList(),
    /** Estado de la conservación en el momento de la exportación. */
    val retention: ExportRetention,
    /** Qué NO se incluye y por qué. */
    val omissions: List<ExportOmission> = emptyList(),
)
```

Estructura del JSON (ejemplo real, recortado):

```json
{
  "format": "kitoru.student-export",
  "version": 1,
  "exportedAt": 1772664000000,
  "generator": "Kitoru",
  "generatorVersion": "0.4.0",
  "subjectId": "u-8f3c1a90",
  "profile": {
    "displayName": "Ana Pérez",
    "email": "",
    "locale": "es",
    "timeZone": "America/Argentina/Buenos_Aires",
    "createdAt": 1769541600000,
    "updatedAt": 1772100000000
  },
  "enrollments": [
    {
      "id": "e-21a7",
      "courseId": "c-ia-2026",
      "courseCode": "IA-2026-1",
      "courseName": "Inteligencia Artificial",
      "displayName": "Fantasma",
      "aliasKind": "PSEUDONYM",
      "role": "ALUMNO",
      "joinedAt": 1770000000000,
      "visibility": {
        "teacherSetting": "FULL",
        "studentCap": "METRICS_ONLY",
        "effective": "METRICS_ONLY",
        "consentId": "c-9912",
        "consentState": "GRANTED"
      },
      "expiresAt": 1780000000000
    }
  ],
  "threads": [
    {
      "id": "c-ia-2026|t-7731",
      "courseId": "c-ia-2026",
      "title": "Duda de parcial",
      "modelLabel": "llama3.1:8b",
      "modelMode": "REMOTE",
      "createdAt": 1772100000000,
      "updatedAt": 1772103600000,
      "tags": ["parcial"],
      "expiresAt": 1788000000000
    }
  ],
  "messages": [
    {
      "id": "m-1",
      "threadId": "c-ia-2026|t-7731",
      "role": "user",
      "text": "¿Cómo se define margen de seguridad?",
      "createdAt": 1772100000000,
      "sources": [],
      "citation": null,
      "toolCalls": [],
      "latencyMs": 0,
      "tokensIn": 18,
      "tokensOut": 0,
      "expiresAt": 1788000000000
    }
  ],
  "analytics": {
    "messages": 142,
    "replies": 138,
    "threads": 7,
    "activeDays": 12,
    "lastActivity": 1772742000000,
    "avgLatencyMs": 1180,
    "tokensIn": 22140,
    "tokensOut": 18402,
    "citations": { "verified": 88, "unsupported": 9, "unknown": 3, "supportRate": 0.88 },
    "ungroundedAnswerRate": 0.065
  },
  "accesses": [
    {
      "at": 1772664000000,
      "actorName": "Prof. Titular",
      "capability": "CHAT_VIEW_OTHER",
      "scope": "3 conversaciones",
      "outcome": "ALLOWED",
      "basis": "CONSENT",
      "consentId": "c-9912"
    }
  ],
  "consents": [
    {
      "id": "c-9912",
      "courseId": "c-ia-2026",
      "purpose": "Lectura del texto de las conversaciones",
      "scope": "CHAT_TEXT",
      "state": "GRANTED",
      "requestedAt": 1772000000000,
      "grantedAt": 1772000600000,
      "withdrawnAt": 0,
      "noticeVersion": "aviso-2026.1"
    }
  ],
  "noticeAcceptances": [
    { "noticeId": "kitoru-privacy", "noticeVersion": "aviso-2026.1", "acceptedAt": 1770000000000 }
  ],
  "retention": {
    "policyId": "fac-2026",
    "policyVersion": "2",
    "chatExpiresAt": 1788000000000,
    "auditExpiresAt": 1815000000000
  },
  "omissions": [
    {
      "kind": "API_KEY",
      "reason": "Es un secreto: se exporta su huella (sha256 truncado), nunca su valor"
    },
    {
      "kind": "INSTITUTIONAL_SUBJECT",
      "reason": "Identificador de la cuenta institucional: no forma parte de tus datos exportables"
    }
  ]
}
```

**Reglas del export:**

| Regla | Detalle |
|---|---|
| **Nunca** incluye secretos | El secreto de la API key no se exporta: sólo viaja el `apiKeyRef`, que es un alias determinista y no secreto (`01-claves.md` D7). El `SecureStore` entero no se exporta. |
| **No incluye datos de terceros** | No se exportan los chats de otros alumnos, ni los nombres del padrón, ni los materiales de la cátedra (salvo los que el alumno subió). |
| **Respeta la visibilidad** | Si el alumno está en `METRICS_ONLY` y exporta, el export **incluye** su texto (es suyo), pero marca en `accesses` que el docente no lo ve. La exportación es siempre del titular: el derecho de acceso no lo limita la visibilidad de terceros. |
| **Incluye las omisiones y por qué** | El campo `omissions` es lo que hace que la exportación sea **completa**: el titular tiene que saber qué hay que no está y por qué. |
| **Es determinista** | Mismo estado ⇒ mismo JSON (salvo `exportedAt`). Un test puede comparar contra un golden. |
| **No lo sube a ningún lado** | Se escribe con `pickSaveTextFile` (doc 08, §7.3). Si no hay `FilePicker`, se ofrece "Copiar al portapapeles". |

### 7.3 Rectificación

| Dato | Cómo se rectifica | Efecto |
|---|---|---|
| `UserProfile.displayName` | Ajustes → Perfil | Inmediato. **No** cambia lo que el docente ve si el alias materializado ya no lo era (§12 del doc 09). |
| Alias por cátedra (`CourseAlias`) | Ajustes → Privacidad → "Cómo me ven en cada cátedra" | Inmediato: se re-materializa `Enrollment.displayName` |
| Etiqueta en el padrón (la puso el docente) | El alumno **pide** que se libere; el docente la libera o no (doc 08: `labelSource`) | Con aviso al docente |
| `Enrollment.email` | Ajustes → Perfil | Inmediato; se propaga al servidor en la próxima sincronización |
| Correo de un material | El docente edita el material y reindexa | — |

> **Honestidad de la rectificación:** no todo es rectificable en el acto. Si el
> docente ya exportó los chats de un alumno a un `.csv` en su escritorio, esa
> copia no se actualiza. La app no finge lo contrario: al rectificar el alias,
> avisa *"Tu docente ya había exportado datos con tu nombre anterior; esos
> archivos quedan fuera del alcance de Kitoru."*

### 7.4 Oposición: dos niveles distintos

La oposición tiene dos formas y confundirlas es un error frecuente:

| Forma | Qué hace | Dónde |
|---|---|---|
| **Oposición al interés legítimo** (RGPD art. 21) | El alumno **sale de los agregados** de analítica, sin perder el uso de la herramienta | `UserProfile.analyticsOptOut = true` → el `AnalyticsAnalyzer` lo excluye de `perStudent`, de `inactiveStudents`, de `perDay` y de los denominadores de las cohortes |
| **Oposición al acceso del docente** | El alumno **no** deja que se lea su texto ni sus métricas | `studentCap = NONE` (doc 08) |

El primero es lo que la ley llama "derecho a oponerse a un tratamiento basado en
interés legítimo": es **gratuito, inmediato y no puede tener consecuencias
desfavorables**. Por eso el `analyticsOptOut` **no** puede castigar al alumno
(con "no vas a ver tus propias métricas", por ejemplo): el alumno siempre ve
sus propios datos.

**Texto en la app (switch):**

```
No incluir mis datos en la analítica del curso
────────────────────────────────────────────────
Los docentes ven estadísticas agregadas del uso de la
herramienta (cuántos mensajes escriben, qué materiales se
consultan, cuántas citas están respaldadas).

Si activás esto, tus datos no se incluyen en esas
estadísticas: ni en tu ficha ni en las medias de la cátedra.

No afecta tu propia actividad: vos seguís viendo todo lo tuyo.
Podés desactivarlo cuando quieras.
                                            [ Desactivar ]
```

### 7.5 "Borrar mis datos": la supresión

La supresión tiene **dos tiempos** (`02-privacidad.md` D10):

- **Borrado lógico** (30 días, recuperable): los datos salen de la vista del
  usuario y de los agregados, pero siguen en disco. El alumno puede
  arrepentirse y recuperarlos.
- **Borrado físico** (definitivo): a los 30 días, o antes si el alumno lo
  pide, los datos desaparecen del disco y del servidor.

Y tiene un tercer paso que casi nadie diseña y que es **el que hace el
derecho real**: **avisar a los docentes que leyeron los chats** y pedirles que
eliminen su copia.

**El diálogo (el más cuidadoso de la app del alumno):**

```
Borrar mis datos
────────────────────────────────────────────────────
⚠ Vas a borrar de forma definitiva:

  ✓ Tus 7 conversaciones y 143 mensajes de IA-2026-1
  ✓ Tus 2 conversaciones y 31 mensajes de Redes-2026-1
  ✓ Tus inscripciones y tu actividad
  ✓ Tu perfil local de Kitoru en este dispositivo

Esto no se puede deshacer.

Lo que Kitoru NO puede borrar:
  ✗ Lo que tu docente ya leyó o copió. No hay forma de des-leer.
    Le vamos a avisar que pediste el borrado y le vamos a pedir que
    elimine lo que tenga de tu parte.
  ✗ Lo que ya exportaste a un archivo (queda en tu equipo).
  ✗ Las conversaciones que están en el servidor de la facultad
    (si usaste el modelo alojado). La facultad las borra en 5 años.

Vamos a dejar constancia de la supresión (tu nombre y la fecha,
sin el contenido de las conversaciones).

Escribí  BORRAR  para confirmar:
[____________________]

                          [Cancelar]  [Borrar mis datos]
```

**Qué hace, en orden:**

1. **Enumera** lo que se va a borrar (calculado de verdad, con
   `DataInventory` de `02-privacidad.md` §4.2, no un texto fijo) y avisa de lo
   que **no** se puede borrar. Esta honestidad es obligatoria: sin ella, el
   derecho de supresión es una promesa falsa.
2. **Marca para borrado lógico**: los hilos y mensajes salen de la UI y de
   los agregados (`studentCap = NONE` + `dataErased = true`), con
   `purgeAt = now + 30 días`. El alumno puede **deshacer** durante esos 30
   días.
3. **Borra en cascada** al vencer: `ChatMessage` de cada hilo →
   `ChatThread` → `Enrollment` (o los anonimiza con `dataErased = true`) →
   `UserProfile` (si no está en ninguna otra cátedra).
4. **Borra los paquetes importados** que el alumno hubiera compartido.
5. **Notifica a los docentes** que leyeron los chats de esa persona y les
   **pide confirmar** que eliminaron su copia. El alumno ve el estado:
   *"Tus datos fueron borrados el 12/03/2026. Pedimos a 3 docentes de 2
   cátedras que eliminen lo que tenían de tu parte. Te avisamos cuando
   cada uno confirme."* (texto de `02-privacidad.md` §4.3).
6. **Deja un `ErasureRecord`** con conteos (sin contenido), que es la evidencia
   de la supresión, con su propia retención (7 años).
7. **Propaga al servidor** si está vinculado: `DELETE /v1/me/data` con el
   `subject`; el servidor borra y devuelve un `PurgeReceipt` que la app guarda.
   **La app no puede prometer la supresión del servidor si no tiene
   respuesta del servidor**: si la llamada falla, el diálogo lo dice
   explícitamente y queda una solicitud pendiente para reintentar.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Privacy.kt (extracto)

/**
 * Servicio de los derechos del interesado.
 *
 * Todos los métodos son **deterministas** salvo los que necesitan red, que
 * separan el caso "hecho local" del caso "pendiente en el servidor".
 */
class PrivacyService(
    private val profiles: ProfileRepository,
    private val enrollments: EnrollmentRepository,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val consents: ConsentRepository,
    private val receipts: PurgeReceiptRepository,
    private val erasures: ErasureRepository,
) {
    /** Acceso + portabilidad: el JSON de §7.2. */
    suspend fun exportMyData(profileId: String, now: Long): PersonalDataExport

    /** Supresión: borra en cascada y deja constancia. */
    suspend fun eraseMyData(profileId: String, now: Long): ErasureOutcome

    /** Rectificación del alias: re-materializa la etiqueta del padrón. */
    suspend fun renameAlias(profileId: String, courseId: String, alias: CourseAlias): List<Enrollment>

    /** Oposición al interés legítimo (analítica). */
    suspend fun setAnalyticsOptOut(profileId: String, optOut: Boolean)

    /** Retiro del consentimiento: baja la visibilidad efectiva de inmediato. */
    suspend fun withdrawConsent(consentId: String, now: Long): List<Enrollment>
}
```

### 7.6 La pantalla "Tus datos"

**La pantalla canónica es "Tus datos"** (`02-privacidad.md` §4.1: Ajustes → Tus
datos). Este documento no la rediseña: sólo le agrega los bloques que la
ley exige y que el doc 02 no tenía — el **aviso de privacidad** (con su
versión aceptada) y la **retención vigente**.

```
Tus datos
─────────────────────────────────────────────────────

¿QUÉ VE TU DOCENTE
  Estado actual: Solo métricas
  [Ver el detalle]  [Cambiar]

¿TUS CONVERSACIONES?  7 en 1 cátedra  (más)

¿ACCESOS DEL DOCENTE?  14 desde el 12/03/2026
  12/03 15:04 · Prof. Titular abrió 3 de tus conversaciones
  11/03 09:12 · Prof. Titular exportó tus datos
  [Ver los 14]  [Exportar CSV]

¿TUS MÉTRICAS?  142 mensajes · 88 % de citas verificadas

TUS DATOS
  [ Descargar mis datos ]   → JSON con todo lo tuyo
  [ Borrar mis datos ]       → supresión en dos tiempos

OPCIONES
  ( ) No incluir mis datos en la analítica del curso

AVISO DE PRIVACIDAD
  Versión 2026.1, aceptada el 12/03/2026
  [Leer el aviso completo]

CONSERVACIÓN
  Esta cátedra conserva tus datos hasta el 15/03/2028
  (2 años después de que termina la cursada).
  [Ver la política de retención]
```

> El bloque "CONSERVACIÓN" cumple el deber de información por sólo: el
> titular tiene que saber, sin pedir nada, **cuánto tiempo** va a estar
> guardada su información y cuándo se va a borrar. Es un requisito del
> art. 13 RGPD y de la ley 25.326 art. 6, y no estaba en el doc 02.

---

## 8. El deber de información

### 8.1 Cuándo y cómo se muestra

| Momento | Qué se muestra | Registro |
|---|---|---|
| **Antes de confirmar la inscripción** | El aviso **corto** (5 líneas) + el enlace "leer el aviso completo" | `NoticeAcceptance` al confirmar |
| **Al cambiar de cátedra con otras políticas** | Aviso corto de la nueva cátedra | `NoticeAcceptance` nuevo, con `noticeVersion` distinta |
| **Al vincular con la institución** | Aviso corto del **servidor**: qué datos va a tener la facultad | `NoticeAcceptance` con `noticeId = "kitoru-server"` |
| **Después, siempre** | "Tus datos → Aviso de privacidad" con el texto completo y la versión aceptada | — |

**La versión del aviso importa.** Si la institución cambia el aviso (o cambia
la política de retención), la app muestra un aviso nuevo y **pide la aceptación
de nuevo** para los tratamientos que cambian de base. El `noticeVersion` viaja
en cada `ConsentRecord` y en cada `AccessLogEntry`.

### 8.2 El texto corto (en la pantalla de inscripción)

```
Antes de anotarte, tres cosas:

• Por defecto tu docente ve SOLO ESTADÍSTICAS de tu actividad
  (cuántos mensajes, qué materiales consultas, cuántas citas están
  respaldadas). No ve el texto de tus conversaciones salvo que vos
  lo autorices.

• Si usás el modelo que da la facultad, tus conversaciones pasan por
  el servidor de la facultad. Si usás un modelo local en tu equipo,
  no salen de tu equipo.

• Tus datos se conservan 2 años después de que termina la cursada y
  se borran a los 5 años. Podés exportar y borrar todo cuando quieras.

Aviso completo: [leer]        [anotarme]
```

### 8.3 El aviso completo (versionado, en "Tus datos")

```
AVISO DE PRIVACIDAD Y TRATAMIENTO DE DATOS PERSONALES
Versión 2026.1 · Válido desde el 1 de marzo de 2026

1. QUIÉN ES EL RESPONSABLE
   [Nombre de la Facultad / Universidad], con domicilio en [dirección],
   es responsable del tratamiento de tus datos personales en el marco
   del uso de Kitoru en la cátedra [código y nombre].
   Delegado de datos: [nombre, email]. Las consultas sobre tus datos
   podés hacerlas en [email] o en la app, en "Tus datos".

2. QUÉ DATOS TRATAMOS
   · Tu nombre o el seudónimo que elegiste usar en cada cátedra.
   · Tus conversaciones con el modelo de IA: lo que escribís y lo que
     responde el modelo, con la fecha, el material consultado y las
     citas verificadas.
   · Estadísticas de uso: cantidad de mensajes, días de actividad,
     latencia, cantidad de tokens, calidad de las citas.
   · El registro de accesos: quién de la cátedra abrió qué
     conversaciones tuyas, cuándo y con qué motivo.
   · Tu dirección de correo electrónico, sólo si la vinculaste con tu
     cuenta institucional.
   NO tratamos: tu ubicación, tus contactos, tu cámara, tu micrófono,
   tu actividad fuera de esta aplicación.

3. PARA QUÉ Y CON QUÉ BASE LEGAL
   · Para prestar el servicio de la cátedra (materiales, tutoría con
     IA, estado académico): ejecución del contrato.
   · Para que tu docente pueda LEER el texto de tus conversaciones:
     tu consentimiento, que podés dar y retirar cuando quieras.
   · Para las estadísticas de uso de la cátedra: interés legítimo de
     la institución en mejorar el curso, que no afecta tus derechos
     porque los datos son agregados y podés oponerte.
   · Para el registro de accesos: cumplimiento de las obligaciones de
     la institución en materia de rendición de cuentas.

4. QUIÉN ACCEDE A TUS DATOS
   · Tu docente titular y los ayudantes de la cátedra: estadísticas,
     y el texto de tus conversaciones sólo si lo autorizaste o si hay
     una norma de evaluación que lo obligue.
   · El personal administrativo de la facultad, en el marco de sus
     funciones.
   · [Nombre del proveedor del modelo], [país], como encargado del
     tratamiento, cuando la cátedra usa un modelo alojado fuera de
     la institución. [Si no hay proveedor externo, decir que el modelo
     se ejecuta en los servidores de la facultad y que tus
     conversaciones no salen de la institución.]
   · Nadie más. No vendemos tus datos, no los usamos para publicidad
     ni para entrenar modelos.

5. CUÁNTO TIEMPO LOS CONSERVAMOS
   · Conversaciones y estadísticas: 2 años después de que termina la
     cursada; borrado definitivo a los 5 años.
   · Registro de accesos: 5 años después del fin de la cursada.
   · Tu inscripción y tu perfil: 3 años.
   Podés ver los plazos exactos de tu cátedra en "Tus datos".

6. TUS DERECHOS
   Podés acceder a tus datos, corregirlos, borrarlos, oponerte a su
   uso para estadísticas y pedir que te los entreguemos en un formato
   que puedas usar, todo desde la app, en "Tus datos".
   Respondemos en un plazo máximo de 30 días.

7. SI SOS MENOR DE EDAD
   Kitoru es una herramienta para el nivel universitario. Si sos menor
   de 18 años, necesitás la autorización de tu responsable legal.

8. CAMBIOS EN ESTE AVISO
   Si cambia, te avisamos en la app y te pedimos la aceptación
   nuevamente para lo que cambie. Guardamos la versión que aceptaste.
```

### 8.4 El registro de la aceptación

```kotlin
@Serializable
data class NoticeAcceptance(
    val id: String,
    /** Identificador del aviso: "kitoru-privacy" | "kitoru-server". */
    val noticeId: String,
    /** Versión: "2026.1". */
    val noticeVersion: String,
    val userId: String,
    val courseId: String = "",
    val acceptedAt: Long,
    /** SHA-256 del texto exacto aceptado: prueba de qué se aceptó. */
    val textHash: String = "",
    /** Texto aceptado (o su hash + el aviso completo, que no cambia). */
    val text: String = "",
    /** Canal: IN_APP | IMPORTED_FILE | SSO. */
    val channel: String = "IN_APP",
    val appVersion: String = "",
)
```

> **El `textHash` importa.** Si la institución cambia el aviso y un alumno
> ya aceptó la versión anterior, el registro dice **exactamente** qué aceptó.
> Sin el hash, un aviso "actualizado" reescribe la historia.

---

## 9. Auditoría y rendición de cuentas

### 9.1 Qué es la evidencia

La rendición de cuentas (RGPD art. 5.2) no se demuestra con una declaración:
se demuestra con un registro. En Kitoru hay **tres** registros, con funciones
distintas:

| Registro | Qué prueba | Quién lo ve | Retención |
|---|---|---|---|
| **`AccessLog`** (doc 08) | **Quién accedió a qué datos de quién, cuándo y con qué base** | El titular de la cátedra, el titular de la institución | +5 años tras fin de cursada |
| **`EnrollmentAuditEvent`** (doc 08) | Las decisiones administrativas: cambios de visibilidad, altas, bajas, exportaciones, supresiones | El titular de la cátedra | +5 años |
| **`PurgeReceipt`** (§6.4) | **Que el borrado por retención ocurrió**, con conteos | El titular, la institución | 7 años |

### 9.2 El formato del registro de accesos (extensión de `AccessLogEntry`)

El `AccessLogEntry` del doc 08 tiene: `id`, `at`, `actorId`, `actorName`,
`courseId`, `capability`, `targetUserId`, `targetUserName`, `detail`,
`outcome`. Para que sea evidencia de cumplimiento hay que agregarle:

| Campo nuevo | Por qué |
|---|---|
| `basis: LegalBasis?` | **La base legal del acceso.** Sin esto, el log dice "el docente leyó" pero no "con qué título". Es el campo que convierte un log de actividad en evidencia normativa. |
| `consentId: String` | El consentimiento que ampara el acceso (si lo hay). Permite auditar "los 47 accesos de marzo se ampararon en el mismo consentimiento `c-9912`". |
| `justification: String` | La justificación escrita, cuando el acceso fue por la vía institucional (obligación legal). |
| `itemCount: Int` | Cuántos hilos o mensajes tocó. "abrió 3 conversaciones" es información; "abrió 1" es otra. |
| `policyVersion: String` | La versión de la política de retención vigente en ese momento. |
| `expiresAt: Long` | Cuándo se va a borrar este registro (§6.1: 5 años). |
| `prevHash: String` | Encadenamiento de hash (§9.4). |

```kotlin
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
    // --- cumplimiento normativo ---
    /** Base legal del acceso. Null = acceso a datos propios o a la cátedra. */
    val basis: LegalBasis? = null,
    /** Consentimiento que ampara el acceso, si lo hay. */
    val consentId: String = "",
    /** Justificación escrita (vía institucional). */
    val justification: String = "",
    /** Cuántos elementos se tocaron. */
    val itemCount: Int = 0,
    /** Versión de la política de retención vigente. */
    val policyVersion: String = "",
    /** Cuándo se borra esta entrada. */
    val expiresAt: Long = 0L,
    /** Hash de la entrada anterior: hace el log a prueba de manipulación. */
    val prevHash: String = "",
) {
    /** Hash de esta entrada (cadena de integridad). */
    fun hash(): String = sha256Hex(
        listOf(id, at, actorId, courseId, capability.name, targetUserId,
               outcome.name, basis?.name ?: "", consentId, itemCount, prevHash)
            .joinToString("|")
    )
}
```

### 9.3 La retención del registro

| Regla | Valor | Motivo |
|---|---|---|
| El log **dura más que el dato** | 5 años vs. 2 | Es la prueba (§4.2, T10) |
| El log **no se borra por acción del docente** | — | El `clear()` del `FileAccessLogRepository` del doc 08 queda **restringido**: sólo lo puede ejecutar un `RetentionSweeper` con política, y cada `clear` genera un `PurgeReceipt` |
| El log **no contiene contenido** | — | Guarda `itemCount`, no los mensajes. Es metadata sobre el acceso, no el acceso (`03-threat-model.md` T-07: si el log guardara snippets, sería una vía de fuga del dato que audita) |
| El log **se exporta** | CSV y JSON | La institución lo entrega a la autoridad o al titular en un plazo de 30 días |
| **El log vive en el servidor** | `POST /v1/audit` | `03-threat-model.md` §5.7 lo dice sin rodeos: *"mientras el log sea local, la auditoría es decorativa"*. El archivo del cliente es una **copia de trabajo** |
| El outbox del cliente se purga | 90 días | `03-threat-model.md` §7 (T-10): el outbox no puede crecer sin límite |

> **La consecuencia normative de esto es fuerte y hay que aceptarla:** si el
> `AccessLog` vive sólo en el disco del docente, **no hay rendición de
> cuentas** (§5.2 RGPD: *"el responsable debe poder demostrar que cumple"*).
> Un docente que borra su log borra la prueba. Por eso el servidor es
> **obligatorio** para cualquier uso institucional serio: el log del servidor
> es el que la institución podrá entregar a la autoridad o al titular, y
> el del cliente es una Caché para mostrar en la UI.

### 9.4 La prueba de no manipulación (v2)

El log del cliente es un archivo JSON que el docente dueño del dispositivo
puede editar. Eso es un problema real de rendición de cuentas: *"¿cómo sé
que este log no lo armaste vos?"*. Y con `03-threat-model.md` T-07 (docente que
abusa) y T-10 (servidor comprometido por un insider) la pregunta es
obligatoria.

Solución propuesta (marcada como **v2** porque el doc 08 la dejó fuera de
alcance, pero el marco normativo la vuelve deseable):

- **Encadenamiento por hash**: cada entrada incluye `prevHash` (el hash de la
  anterior) y su propio hash se calcula sobre el contenido. Alterar una entrada
  rompe la cadena desde ese punto.
- **Ancla periódica**: cada N entradas (o al cerrar la cursada), el docente
  publica el hash de la cabeza de la cadena — por ejemplo, en el cartel de la
  cátedra o en un correo al paddón. Cualquiera puede verificar después que el
  log no se alteró.
- En el servidor, la cadena se sella con el hash firmado por el servidor, y el
  servidor **también** registra sus propios accesos a los chats (el
  operador del sistema es el que puede saltarse el modelo de visibilidad:
  `03-threat-model.md` §3).

Esto no vuelve el log inalterable (un atacante con acceso al archivo puede
recalcular toda la cadena), pero **hace la alteración detectable**, que es lo
que importa en la práctica.

### 9.5 Qué ve el alumno

El doc 08 ya lo tiene en la pantalla "Tus datos". Lo que agrega este
documento:

- El alumno ve **qué base legal** ampara cada acceso.
- El alumno ve **la justificación** cuando el acceso fue por la vía
  institucional.
- El alumno puede **exportar el registro** (CSV/JSON) y pedirlo.
- El alumno ve el **plazo de conservación** de los datos del docente.

---

## 10. El rol de la institución: qué tiene el servidor y qué sólo el cliente

### 10.1 La tabla servidor / cliente

Kitoru-Server es **operado por la facultad**. Eso lo convierte en el
**responsable** de los datos que guarda, y define un contrato con el alumno.

| Dato | Cliente (dispositivo del alumno) | Kitoru-Server (facultad) | Base |
|---|---|---|---|
| `UserProfile.id` (UUID local) | Sí | **No** (sólo si el alumno se vincula, y entonces es el `subject`) | Contrato |
| Nombre real | Sí (local) | No, salvo que el alias sea `REAL` | Contrato |
| Alias / etiqueta | Sí | Sí, si hay servidor (es lo que ve el docente) | Contrato |
| Email | Sí | **Sólo al vincular** | Contrato |
| **Chats con IA local** | **Sí, y no salen** | **Nunca los ve** | — (no hay transferencia) |
| **Chats con IA alojada (REMOTE)** | Sí | **Sí, en tránsito**; en reposo **sólo si `logPrompts = true`** | Contrato |
| Metadatos de los chats (citas, latencia, tokens) | Sí | Sí (los calcula el cliente y los sube, si la analítica está habilitada) | Interés legítimo |
| Analítica por alumno | Sí | Sí | Interés legítimo |
| Analítica agregada | Sí | Sí | Interés legítimo |
| **Registro de accesos** | Copia local | **El autoritativo** | Obligación legal |
| Materiales de la cátedra | Copia local (descargados) | Sí (los publica) | Contrato |
| API key | En el `SecureStore` del cliente (fuera de `~/.kitoru`) | La emite; guarda **sólo la huella** | Contrato |
| `deviceId` | Sí | Sólo si `telemetryEnabled`, hasheado con sal | Interés legítimo |
| IP | **Nunca** | Sí, 30 días | Interés legítimo |
| `PurgeReceipt` | Copia local | El autoritativo | Obligación legal |
| `NoticeAcceptance` | Sí | Sí (para poder demostrar la aceptación) | Contrato |

### 10.2 El modelo "el alumno puede usar IA local" es la mejor posición de privacidad

La arquitectura de Kitoru tiene una propiedad que muchas herramientas
institucionales no tienen: **el peor caso ya no es el peor caso**.

```
   Alumno con IA LOCAL                Alumno con IA ALOJADA
   ─────────────────────              ─────────────────────
   El texto existe sólo en            El texto pasa por el
   el dispositivo del alumno.         servidor de la facultad.

   La institución NO PUEDE            La institución PUEDE
   leerlo (no existe de su lado).     leerlo (y auditar quién
                                      lo leyó).

   No hay transferencia.              Hay transferencia (posible
                                      a un tercero).
```

Consecuencias normativas y de producto:

1. **La base legal se simplifica**: en local, la institución **no es
   responsable** del texto de los chats (el alumno es el responsable de su
   dispositivo, con la institución como institución que provee la
   herramienta). El texto no es un dato que la institución tenga.
2. **La jurisdicción se vuelve irrelevante**: si el texto no sale del
   dispositivo, no hay transferencia internacional que evaluar. Es la respuesta
   definitiva al problema de los proveedores de modelos en la nube.
3. **El analizador de citas es local y determinista** (regla 3 del CONTEXTO):
   la calidad de las respuestas se puede medir **sin** que el texto viaje. Es
   el activo normativo más valioso del proyecto: permite que la institución
   tenga métricas de calidad con el dato sin salir.

> **Recomendación operativa para la institución:** la configuración por defecto
> del servidor institucional debería ser **`allowLocalModels = true`** y
> **`logPrompts = false`**. Con eso, el caso por defecto de la facultad es el
> de mejor posición de privacidad, y la IA alojada queda como una excepción
> justificada (potencia de cómputo, modelo más grande, evaluación comparada).

### 10.3 Qué tiene que hacer el servidor

El Kitoru-Server no es "el backend de la app": es **un sistema de información
de la institución** con obligaciones propias. Del lado del servidor, el diseño
debe garantizar:

| Requisito | Cómo |
|---|---|
| **Autenticación real de los docentes** | Los roles no se deciden por un código compartido: el servidor los asigna desde el padrón institucional (o por un rol del IdP). El código de cátedra del modelo local es un mecanismo de respaldo, no el mecanismo de producción. |
| **Autorización por cátedra** | Un docente de otra cátedra no puede ver los chats de ésta. El servidor aplica la misma matriz de permisos que el cliente (doc 08), no una versión simplificada. |
| **Separación de functions** | El servidor **emite** API keys, pero **no** las ve (sólo la huella). El servidor **publica** materiales, pero **no** edita los chats de un alumno. |
| **Logs de seguridad** | 30 días, IP truncada. |
| **Borrado efectivo** | El servidor aplica la **misma** `RetentionPolicy` que el cliente, con el **mismo código** (la función de barrido vive en `:core`). |
| **Registro de accesos autoritativo** | El servidor es la fuente de la verdad del `AccessLog`. |
| **Sin prompt logging por defecto** | `logPrompts = false` y, si se activa, justification + aviso. |

---

## 11. Qué necesita la institución para aprobar la herramienta

### 11.1 El índice de la ficha de tratamiento

La ficha de tratamiento de datos (el "registro de actividades de tratamiento"
del art. 30 RGPD, extendido con lo que la ley local pide) es el documento que
la facultad tiene que poder firmar antes de usar Kitoru con datos reales de
alumnos.

> **FICHAS DE TRATAMIENTO DE DATOS PERSONALES — KITORU**
> *Facultad de [X] · Versión [n] · Aprobada por [Comisión / Consejo] el [fecha]*

| § | Apartado | Contenido | ¿Quién lo redacta? |
|---|---|---|---|
| 1 | **Responsable del tratamiento** | Denominación social, domicilio, actividad, representante legal | Institución |
| 2 | **Delegado de datos (DPO)** | Nombre, contacto, alcance de la delegación | Institución |
| 3 | **Encargados y subencargados** | El equipo que mantiene Kitoru (tratamiento por cuenta), el proveedor de infraestructura del servidor, el proveedor del modelo | Institución |
| 4 | **Finalidades del tratamiento** | La lista cerrada de §4.2 (una línea por finalidad) | App + institución |
| 5 | **Categorías de interesados** | Alumnos, docentes, personal de apoyo, y los menores de edad (§8.3, punto 7 del aviso) | Institución |
| 6 | **Categorías de datos** | La lista de §5.2 (perfil, etiqueta, contenido, metadatos, auditoría, credenciales, dispositivo) | App |
| 7 | **Bases de licitud por finalidad** | La columna "Base de licitud" de la tabla de §4.2, con el artículo citado en cada norma | App + institución |
| 8 | **Interés legítimo: test de equilibrio** | Documento aparte: interés, necesidad, balancing, salvaguardas, cómo se opone el titular | Institución (la app provee los datos) |
| 9 | **Destinatarios** | Docentes, ayudantes, RRHH, dirección, y su proporcionalidad | Institución |
| 10 | **Transferencias internacionales** | Proveedor del modelo, país, tipo de garantía (art. 46 RGPD / CCyC 19 / LFPDPPP 33-36) | Institución |
| 11 | **Plazos de conservación** | La tabla de §6.1, con los valores adoptados | App (valores) + institución (adopción) |
| 12 | **Medidas de seguridad técnicas** | Cifrado en tránsito y reposo, almacén de secretos (\`SecureStore\`) fuera de la carpeta de datos, aislamiento por visibilidad en el repositorio, control de acceso por rol | App |
| 13 | **Medidas de seguridad organizativas** | Política de accesos del docente, rotación de API keys, procedimiento de alta/baja de personal, formación | Institución |
| 14 | **Derechos: procedimiento y plazos** | Dónde se ejercer, quién responde, plazo (30 días), formulario, respuesta a menores | App + institución |
| 15 | **Notificación de brechas** | Responsable, plazo (72 h), comunicación al titular, formulario | Institución |
| 16 | **Uso de IA: proveedores y política** | Modelo por defecto, si hay entrenamiento, si hay retención, cómo se apaga el logging | App + institución |
| 17 | **Instalaciones gestionadas y no gestionadas** | Diferencias: qué se puede cambiar y qué no | App |
| 18 | **Menores de edad** | Política y edad mínima | Institución |
| 19 | **Evaluación de impacto (DPIA)** | El documento, o la justificación de por qué no aplica | Institución |
| 20 | **Anexo A: registro de actividades** | La tabla de §4.2 completa, generada por la app | **App (automático)** |
| 21 | **Anexo B: parámetros de retención vigentes** | Los valores de `RetentionPolicy` en uso, con su versión | **App (automático)** |
| 22 | **Anexo C: inventario de permisos de la app** | Los permisos que pide la app en cada plataforma (la respuesta es "ninguno sensible") | **App (automático)** |
| 23 | **Control de versiones y aprobación** | Fecha, versión, quién aprobó, cuándo se revisa (anual) | Institución |

### 11.2 Qué puede generar la app

Tres de los anexos salen de la app sin que nadie los escriba a mano:

| Anexo | Qué genera | Formato |
|---|---|---|
| **Anexo A** — registro de actividades | La tabla de §4.2, con el `purpose`, la `basis`, los destinatarios y los plazos tal como están configurados en la app | JSON + Markdown |
| **Anexo B** — parámetros de retención | La `RetentionPolicy` vigente (cliente + servidor), con su versión | JSON |
| **Anexo C** — inventario de permisos | La lista de permisos declarados por plataforma, leída del manifiesto en build | JSON |

Botón en el panel del docente: **"Ajustes → Cumplimiento → Exportar ficha"**, que genera un `.zip` con:

```
kitoru-ficha-<institucion>-<fecha>/
├── anexo-a-registro-actividades.md
├── anexo-a-registro-actividades.json
├── anexo-b-retencion.json
├── anexo-c-permisos.json
└── README.md        (qué tiene que completar la institución)
```

El `README.md` es la lista de lo que la app **no** puede saber: la base legal
de la evaluación docente, los contratos con los proveedores, el nombre del
DPO, la política de menores. Es el puente entre lo que la app hace y lo que la
institución tiene que hacer (§12).

---

## 12. Qué NO hace la app por sí sola

Esta es la lista de responsabilidades que **no se pueden automatizar**. Cada
una es una tarea de la institución, con el detalle de por qué la app no puede
resolverla.

| # | Lo que la institución tiene que hacer | Por qué la app no puede | Qué sí hace la app para ayudar |
|---|---|---|---|
| 1 | **Determinar la base legal de la evaluación docente** y, si corresponde, dictar el reglamento o resolución que la respalde | Es un acto jurídico y académico. La app no puede inventar una obligación legal. | La vía "Revisión justificada" sólo existe si la institución activa `Course.evaluationMandatory` (§4.3) |
| 2 | **Redactar y publicar el aviso de privacidad institucional** y sus versiones | El aviso es un documento legal de la institución. La app puede mostrar un texto, no darle la autorización. | La app define el **esqueleto** del aviso (§8.3), con los campos a completar, y registra la aceptación con `noticeVersion` |
| 3 | **Celebrar y documentar el consentimiento** cuando la base sea el consentimiento | La app registra el clic; la institución define **qué** se somete a consentimiento y **cómo se prueba** ante un juez. | `ConsentRecord` con texto exacto, hash, versión del aviso y canal |
| 4 | **Firmar contratos con los proveedores de modelos**: encargo de tratamiento, no-entrenamiento, retención cero, subcontratación, transferencias | Es una relación contractual. | La app muestra el host y advierte si el prompt se está registrando (`logPrompts`) |
| 5 | **Resolver las transferencias internacionales** (salvaguardas del art. 46 RGPD; art. 19 CCyC; art. 33-36 LFPDPPP) | Es un acto jurídico. | La app lista en el aviso qué host se usa (§8.4) |
| 6 | **Designar el DPO / responsable y publicar el canal de contacto** | Es un nombramiento institucional. | El aviso tiene el campo "Delegado de datos" |
| 7 | **Hacer la evaluación de impacto** (DPIA, art. 35 RGPD) o justificar que no aplica | Es un análisis de riesgo institucional. | La app provee el inventario de datos, los destinatarios y los riesgos estructurales |
| 8 | **Definir el procedimiento de notificación de brechas** y quién lo ejecuta | Es un proceso humano con plazos de 72 h. | — |
| 9 | **Definir qué es "personal docente"** y quién es titular de una cátedra (la app no puede inventar la jerarquía académica) | Es la organización de la institución. | El modelo de roles del doc 08 y el servidor que asigna roles desde el IdP |
| 10 | **Establecer la política de retención institucional** y aprobarla | Es una decisión de política. | `RetentionPolicy` con valores por defecto parametrizables y versioning (§6.3) |
| 11 | **Manejar el caso de los menores de edad** | La app no puede verificar la edad y no debe pedirle datos de identidad para eso. | El aviso lo dice (§8.3, punto 7) |
| 12 | **Hacer cumplir sus propias normas disciplinarias** (qué pasa si un docente lee chats sin autorización) | Es materia disciplinaria. | El `AccessLog` es la evidencia para el sumario |
| 13 | **Decidir qué se hace con los datos después de la cursada** (¿se archivan para la evaluación de la materia? ¿se borran?) | Es política académica. | La `RetentionPolicy` lo parametriza; el recibo de borrado deja constancia de qué se hizo |
| 14 | **Publicar las estadísticas de la facultad** (uso por cátedra, uso por carrera) | Requiere el test de equilibrio y el derecho de oposición; es una decisión institucional. | La app exporta la analítica agregada con n ≥ 5, y sólo agregada |
| 15 | **Formar al personal docente** en el uso de la herramienta | Es un proceso humano. | La app muestra, en el panel del docente, un recordatorio de las reglas: qué puede ver y qué no, y por qué |

---

## 13. Cambios de dominio propuestos

| Cambio | Dónde | Motivo |
|---|---|---|
| `RetentionPolicy`, `RetentionRule`, `RetentionKind`, `RetentionResolver`, `RetentionSweeper`, `PurgeReceipt` (nuevos) | `core/.../domain/Retention.kt` | §6 |
| `LegalBasis` (nuevo) | `core/.../domain/Privacy.kt` | §4.1, §4.2 |
| `ConsentScope`, `ConsentRecord` (nuevos; **se agregan** a `ConsentState`/`VisibilityConsent` que ya define `02-privacidad.md` §3.7) | `core/.../domain/Privacy.kt` | §4.4 |
| `NoticeAcceptance` (nuevo) | `core/.../domain/Privacy.kt` | §8.4 |
| `PrivacyService`, `ErasureOutcome` (nuevos) | `core/.../domain/usecases/PrivacyService.kt` | §7 |
| `PersonalDataExport` y los tipos `Export*` (nuevos) | `core/.../domain/Export.kt` | §7.2 |
| `ProcessingActivity`, `DataCategory`, `DataSubject`, `Recipient` (nuevos) | `core/.../domain/Processing.kt` | §4.2, Anexo A |
| `ChatThread.expiresAt`, `ChatMessage.expiresAt` | `core/.../domain/Chat.kt` | §6.2 |
| `Enrollment.expiresAt`, `Enrollment.dataErased`, `Enrollment.shareEmailWithTeacher` | `core/.../domain/Course.kt` | §5.1, §6.2 |
| `Material.expiresAt` | `core/.../domain/Course.kt` | §6.2 |
| `Course.retentionPolicy`, `Course.evaluationMandatory` | `core/.../domain/Course.kt` | §6.3, §4.3 |
| `ModelPolicy.logPrompts` (nuevo, `false` por defecto) | `core/.../domain/ModelPolicy.kt` | §4.5 |
| `UserProfile.analyticsOptOut` | `core/.../domain/Roles.kt` | §7.4 |
| `AppSettings.retentionPolicy`, `AppSettings.telemetryEnabled`, `AppSettings.rightsResponseDays` | `core/.../domain/AppSettings.kt` (doc 08) | §6.3, §5.1 |
| `AccessLogEntry`: `basis`, `consentId`, `justification`, `itemCount`, `policyVersion`, `expiresAt`, `prevHash` | `core/.../domain/AccessLog.kt` (doc 08) | §9.2 |
| `ToolCallStats.argsRedacted`, `.argsPreview` | `core/.../domain/Chat.kt` | §5.1 |
| `CourseRepository.findByJoinCode`, `EnrollmentRepository.findByCourseAndUser` | `core/.../domain/Repositories.kt` (doc 09) | ya está propuesto |

**Deuda que este documento deja explícita** (y que hay que cerrar antes de
implementar, porque son tres modelos que hoy se contradicen):

| Deuda | Dónde está | Quién la arregla |
|---|---|---|
| `ModelPolicy.apiKey` en claro → `apiKeyRef` + `SecureStore` | `ModelPolicy.kt` | `01-claves.md` §3.4 |
| `CredentialStore` → `SecureStore` (y `SecretRef` con formato `esquema:ámbito:id`) | `domain/09-identidad.md` | `01-claves.md` §2 |
| `Enrollment.visibility` / `visibilityCap` / `consent` (nombres canónicos) | `teacher/08-alumnos.md` §2.2 | `02-privacidad.md` §1.4 |
| `AccessLog` local → **servidor** (`POST /v1/audit`), con outbox de 90 días | `domain/08-permisos.md` §8 | `03-threat-model.md` §5.7 |

## 14. Plan de tests

| Test | Qué cubre |
|---|---|
| `RetentionResolverTest` | `expiresAt = min(creación + tope, fin de cursada + plazo)`; cursada sin `termEndAt`; `legalHold` ⇒ `0`; la política más restrictiva gana en todos los plazos |
| `RetentionSweeperTest` | Borra sólo lo vencido; anonimiza la inscripción en vez de borrarla; **no borra** nada con `legalHold`; emite el `PurgeReceipt` con los conteos; **no borra** artefactos ajenos a la cátedra |
| `RetentionPolicyTest` | `stricterThan` toma el mínimo plazo a plazo y une los `legalHold`; la política por defecto tiene los valores de §6.1 |
| `PrivacyServiceTest` | La exportación contiene todo lo listado en §7.2 y **no** contiene la API key ni datos de terceros; el borrado es en cascada y deja `ErasureRecord`; el `analyticsOptOut` excluye al alumno de `AnalyticsAnalyzer` |
| `ExportSchemaTest` | El JSON exportado valida contra `kitoru-student-export-v1.schema.json`; el `format` y la `version` están presentes; el `omissions` lista lo excluido; el export **no** contiene el secreto de la API key (sólo el `apiKeyRef`) |
| `ConsentTest` | El consentimiento concede `CHAT_TEXT` **sólo** para el curso y el scope indicados; retirarlo baja `studentCap` de inmediato; el registro sobrevive al retiro |
| `NoticeAcceptanceTest` | Se registra la aceptación con la versión y el hash del texto; un aviso nuevo (versión distinta) vuelve a pedir aceptación |
| `VisibilityLawTest` | El docente **no** puede subir la visibilidad por encima de `studentCap`; la vía institucional exige `evaluationMandatory` + justificación + notificación; **todo** acceso al texto escribe un `AccessLogEntry` con `basis` |
| `AccessLogChainTest` | El `prevHash` encadena; alterar una entrada rompe la verificación de la cadena |
| `MinimizationTest` | El roster exportado **no** contiene `email` salvo `shareEmailWithTeacher`; el CSV del roster no contiene texto de chats; `ChatThread.authorName` es la etiqueta y no el nombre real |
| `PiiScanTest` | El escaneo de materiales detecta emails, teléfonos y legajos en el texto extraído |
| `ProcessingRegistryTest` | La tabla de §4.2 y el registro generado por la app tienen el mismo contenido (el documento no puede quedar viejo) |

Todo en `commonTest`, salvo los que necesiten sistema de archivos real
(`desktopTest`).

---

## 15. Archivos

```
core/src/commonMain/kotlin/kitoru/domain/
├── Privacy.kt            # LegalBasis, ConsentScope, ConsentRecord,
│                      # NoticeAcceptance  (+ ConsentState y VisibilityConsent
│                      #  que ya define 02-privacidad.md §3.7)
├── Retention.kt          # RetentionKind, RetentionRule, RetentionPolicy,
│                      # RetentionResolver, RetentionSweeper, PurgeReceipt
├── Export.kt             # PersonalDataExport + tipos Export*
├── Processing.kt         # ProcessingActivity, DataCategory, DataSubject, Recipient
└── (modificados) Chat.kt, Course.kt, Roles.kt, ModelPolicy.kt, AccessLog.kt

core/src/commonMain/kotlin/kitoru/domain/usecases/
└── PrivacyService.kt     # los derechos del interesado

docs/schemas/
└── kitoru-student-export-v1.schema.json   # el esquema de la exportación

docs/design/security/
├── 01-claves.md           # modelo de secretos (hermano)
├── 02-privacidad.md      # modelo de consentimiento y visibilidad (hermano)
├── 03-threat-model.md     # amenazas y mitigaciones (hermano)
└── 04-cumplimiento.md     # este documento
```

## 16. Resumen de decisiones

| Decisión | Motivo |
|---|---|
| Tres marcos, se diseña contra el más exigente (RGPD) | La app se usa en AR, MX y UE con la misma base de código |
| La base de licitud es **por tratamiento**, no por app | El mismo dato tiene bases distintas según la finalidad |
| Contrato para el uso académico, interés legítimo para la analítica, consentimiento para la lectura del texto | Cada base tiene sus requisitos y la app puede cumplir los tres |
| **El docente no ve el chat sin consentimiento**, salvo obligación legal declarada por la institución | El interés legítimo no se sostiene en una relación de dependencia |
| Fail-closed: sin consentimiento registrado, el techo es `METRICS_ONLY` | El default define qué se ve sin que nadie haga nada (`02-privacidad.md` D2) |
| La **retención es un parámetro real**: política + `expiresAt` + job + recibo | La conservación limitada se cumple borrando, y se prueba con el recibo |
| La retención por defecto: 2 años tras el fin de cursada, borrado a los 5 | Cubre el ciclo académico y pone un tope |
| El registro de accesos dura **más** que los datos (5 vs. 2 años) **y vive en el servidor** | Es la evidencia de rendición de cuentas; en local es decorativo |
| Los derechos se ejercen **desde la app**: "Descargar mis datos" (JSON versionado) y "Borrar mis datos" (lógico → físico, con aviso a los docentes) | Un derecho que hay que pedir por email no se ejerce |
| El deber de información se cumple en la inscripción, **por capas y versionado**, con hash del texto aceptado | Sin aviso y sin constancia, el tratamiento es difícil de justificar |
| **El servidor tiene los datos de la institución; el cliente, los del alumno** | La frontera de datos tiene que ser real y escrita |
| La IA local es la **mejor posición de privacidad** y debería ser el default institucional | El texto no existe de parte de la institución: no hay transferencia |
| La app **no decide** la base legal de la evaluación, ni los contratos, ni el aviso | Son actos jurídicos de la institución; la app aplica y registra, no crea |
| La ficha de tratamiento tiene **23 apartados**, 3 de ellos generados por la app | La institución tiene que poder aprobar la herramienta: el documento es el entregable |
| Los nombres canónicos son los de `02-privacidad.md`; este documento **no** redefine el modelo de privacidad | Un solo modelo de privacidad, repartido en tres documentos que se citan |
