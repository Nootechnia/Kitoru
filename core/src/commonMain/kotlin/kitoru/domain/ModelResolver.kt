package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * De dónde salió el valor que se pidió para un campo.
 *
 * El docente necesita saber quién movedió cada cosa: si fue el alumno, vino
 * de un preset de la cátedra, o es el valor de la cátedra (no lo tocó nadie).
 */
@Serializable
enum class ChangeOrigin(val label: String) {
    /** Lo ajustó el alumno a mano (es un delta suyo). */
    STUDENT("Ajuste del alumno"),

    /** Vino de un preset de la cátedra. */
    PRESET("Preset de la cátedra"),

    /** Es el valor de la cátedra: no lo tocó nadie. */
    POLICY("Cátedra"),
}

/**
 * Qué pasó con el valor que pidió el alumno.
 *
 * - [INHERITED]: el alumno no tocó el campo ⇒ se hereda el de la cátedra.
 * - [ACCEPTED]: el alumno lo cambió y se aplicó tal cual.
 * - [CLAMPED]: se aplicó, pero recortado al rango permitido por la cátedra.
 * - [REJECTED]: se descartó (campo bloqueado, valor no permitido, o la
 *   cátedra no permite ajustar la configuración).
 * - [LOCKED]: el alumno no tocó el campo, pero está bloqueado por la cátedra
 *   (informativo: "esto no se puede tocar").
 */
@Serializable
enum class ChangeStatus(val label: String) {
    /** Se heredó el valor de la cátedra (el alumno no lo tocó). */
    INHERITED("Heredado"),

    /** Se aplicó el valor pedido tal cual. */
    ACCEPTED("Aplicado"),

    /** Se aplicó, pero ajustado al rango permitido. */
    CLAMPED("Ajustado a la cátedra"),

    /** Se descartó el valor pedido. */
    REJECTED("Ignorado"),

    /** El campo está bloqueado por la cátedra (y nadie lo pidió). */
    LOCKED("Bloqueado por la cátedra"),
}

/**
 * Un cambio de configuración, campo a campo: lo que el docente ve.
 *
 * Es el diff **calculado durante la resolución** (no se comparan dos
 * objetos), así que el diff que ve el docente *es* la configuración que se
 * usó: no hay forma de que se desincronicen.
 *
 * **Nunca contiene material de la API key**: los valores del campo
 * [PolicyField.API_KEY] van enmascarados (ver [maskSecret]).
 */
@Serializable
data class ConfigChange(
    /** Campo afectado. */
    val campo: PolicyField,

    /** Valor de la cátedra (la base). */
    val valorBase: String = "",

    /** Valor pedido por el alumno (o por el preset). */
    val valorPedido: String = "",

    /** Valor final después de aplicar las reglas. */
    val valorEfectivo: String = "",

    /** Qué pasó con el cambio. */
    val estado: ChangeStatus = ChangeStatus.INHERITED,

    /** De dónde salió el valor pedido. */
    val origen: ChangeOrigin = ChangeOrigin.POLICY,

    /** Por qué (mensaje en español para la UI). */
    val motivo: String = "",
) {
    /** ¿El alumno Effectively cambió algo y se aplicó? */
    val isChange: Boolean get() = estado == ChangeStatus.ACCEPTED || estado == ChangeStatus.CLAMPED

    /** ¿Se descartó lo que pidió? */
    val isRejected: Boolean get() = estado == ChangeStatus.REJECTED

    /**
     * Copia sin material sensible.
     *
     * Los valores del campo [PolicyField.API_KEY] se enmascaran: aunque
     * alguien construya el cambio a mano con la clave en crudo, el reporte
     * que ve el docente nunca la muestra.
     */
    fun redacted(): ConfigChange = if (campo.isSecret) {
        copy(
            valorBase = maskSecret(valorBase),
            valorPedido = maskSecret(valorPedido),
            valorEfectivo = maskSecret(valorEfectivo),
        )
    } else {
        this
    }
}

/**
 * El resultado de resolver la configuración: la efectiva + el diff.
 *
 * - [effective]: la configuración final (lo que se usa para chatear).
 * - [changes]: **una entrada por campo rastreado**, con base / pedido /
 *   efectivo y el estado. Los campos que el alumno no tocó aparecen con
 *   estado [ChangeStatus.INHERITED] (o [ChangeStatus.LOCKED] si están
 *   bloqueados), para que la tabla del docente esté completa.
 * - [rejected]: lo que el alumno pidió y **no** se aceptó (está bloqueado,
 *   o la cátedra no permite ajustar la configuración).
 */
@Serializable
data class ModelResolution(
    /** La configuración final. */
    val effective: ModelPolicy,

    /** El diff campo a campo. */
    val changes: List<ConfigChange> = emptyList(),

    /** Lo que el alumno pidió y no se aceptó. */
    val rejected: List<ConfigChange> = emptyList(),
) {
    /** El cambio de un campo (si está rastreado). */
    fun changeFor(campo: PolicyField): ConfigChange? = changes.firstOrNull { it.campo == campo }

    /** Los cambios que el alumno efectivamente puso (aplicados o ajustados). */
    val modified: List<ConfigChange> get() = changes.filter { it.isChange }
}

/**
 * El resumen de la configuración de un alumno, para la vista del docente.
 *
 * **Nunca contiene la API key**: los cambios del campo
 * [PolicyField.API_KEY] van enmascarados (ver [ConfigChange.redacted]).
 */
@Serializable
data class StudentConfigReport(
    /** Alumno al que pertenece el reporte. */
    val userId: String = "",

    /** Nombre con el que se lo muestra al docente. */
    val displayName: String = "",

    /** El diff campo a campo. */
    val changes: List<ConfigChange> = emptyList(),
) {
    /** Los cambios que el alumno puso (aplicados o ajustados). */
    val modified: List<ConfigChange> get() = changes.filter { it.isChange }

    /** Lo que el alumno pidió y no se aceptó. */
    val rejected: List<ConfigChange> get() = changes.filter { it.isRejected }

    companion object {
        /**
         * Construye el reporte **sanitizado**: aunque el diff venga con
         * material de la API key, acá sale enmascarado.
         */
        fun of(userId: String, displayName: String, changes: List<ConfigChange>): StudentConfigReport =
            StudentConfigReport(userId, displayName, changes.map { it.redacted() })
    }
}

/**
 * Cuánto puede mover el alumno un campo.
 *
 * Es el equivalente del `ModelPolicy.fieldRules` del diseño 05, pero vive
 * acá porque `ModelPolicy` (archivo que en esta tanda no se puede tocar) hoy
 * no lo tiene: el resolver lo recibe por parámetro.
 *
 * TODO(docs/design/domain/05-modelo-config.md §4.1): cuando `ModelPolicy`
 * tenga `fieldRules`, este tipo se reemplaza por `FieldConstraint` y la
 * política pasa a traer las reglas; `ModelResolver.resolve` deja de
 * recibirlas por parámetro.
 */
@Serializable
sealed interface PolicyRule {
    /** Libre: el alumno puede cambiar el valor como quiera. */
    @Serializable
    data object Free : PolicyRule

    /** Bloqueado: el valor de la cátedra es el único válido. */
    @Serializable
    data object Locked : PolicyRule

    /** Rango permitido: si el valor está fuera, se recorta al borde (`CLAMPED`). */
    @Serializable
    data class Ranged(val min: Double, val max: Double) : PolicyRule

    /** Valores permitidos: si el pedido no está en la lista, gana la base. */
    @Serializable
    data class Choice(val values: List<String>) : PolicyRule
}

/**
 * Un preset de la cátedra: un atajo a un conjunto de valores.
 *
 * Los campos vacíos significan "hereda de la cátedra": el preset define sólo
 * lo que cambia. El alumno elige el preset; el preset **no** esquiva las
 * reglas de la cátedra (si un valor está fuera de rango, se recorta).
 *
 * TODO(docs/design/domain/05-modelo-config.md §7): cuando `ModelPolicy` tenga
 * `presets`, este tipo se reemplaza por `ModelPreset`.
 */
@Serializable
data class PolicyPreset(
    /** Id estable del preset. */
    val id: String = "",

    /** Etiqueta visible ("Rápido", "Razonador"). */
    val label: String = "",

    /** Los valores que impone (los que no están, se heredan). */
    val values: Map<PolicyField, String> = emptyMap(),
)

/**
 * Resolución de la configuración: **base de la cátedra + libertad del alumno**.
 *
 * Reglas (en el orden en que se aplican):
 *
 * 1. Si el campo está **bloqueado** por la cátedra ([PolicyRule.Locked] o
 *    el campo está en `ModelPolicy.lockedFields`), el delta del alumno se
 *    **rechaza**: gana el valor de la cátedra y el cambio queda registrado
 *    como [ChangeStatus.REJECTED] con su motivo.
 * 2. Si la cátedra puso un **rango** ([PolicyRule.Ranged]) y el valor está
 *    fuera, se **ajusta** al borde ([ChangeStatus.CLAMPED]) en vez de
 *    rechazarse.
 * 3. Si la cátedra **no permite ajustar la configuración**
 *    (`ModelPolicy.allowStudentOverride == false`), **no se aplica ningún
 *    delta**… con **una excepción**: el [PolicyField.MODE] a `LOCAL`.
 * 4. El [PolicyField.SYSTEM_PROMPT] no se reemplaza: se **compone**
 *    (instrucciones de la cátedra + instrucciones del alumno).
 * 5. El modelo local ([LocalModelProfile]) es **del alumno**: no lo manda ni
 *    lo ve la cátedra, pero los valores que la cátedra acota (contexto,
 *    capas en GPU) se le recortan igual.
 *
 * Todo es **determinista**: no toca la red ni el reloj. Por eso el diff que ve
 * el docente es reproducible y se puede testear.
 */
object ModelResolver {

    /**
     * Resuelve la configuración de un alumno contra la política de su cátedra.
     *
     * @param coursePolicy la base que impone la cátedra.
     * @param student los deltas del alumno (no la política completa).
     * @param rules reglas finas por campo (rango / bloqueo / elección).
     * @param preset preset de la cátedra elegido por el alumno (opcional).
     * @param allowLocalModels si la cátedra permite IA local en esta materia.
     * @param personalKey la clave personal del alumno, resuelta desde su
     *   [StudentModelProfile.personalKeyRef] por el almacén de secretos.
     */
    fun resolve(
        coursePolicy: ModelPolicy,
        student: StudentModelProfile,
        rules: Map<PolicyField, PolicyRule> = emptyMap(),
        preset: PolicyPreset? = null,
        allowLocalModels: Boolean = true,
        personalKey: String = "",
    ): ModelResolution {
        val effectiveRules = rulesFor(coursePolicy) + rules
        val changes = mutableListOf<ConfigChange>()
        val override = coursePolicy.allowStudentOverride

        // ---- 1. Modo (se resuelve primero: decide qué campos aplican) ----
        val mode = resolveMode(coursePolicy, student, preset, effectiveRules, allowLocalModels, override, changes)

        // ---- 2. Prompt del sistema (composición, no reemplazo) -----------
        val systemPrompt = resolveSystemPrompt(coursePolicy, student, preset, effectiveRules, override, changes)

        // ---- 3. Parámetros de generación --------------------------------
        val temperature = resolveNumber(
            campo = PolicyField.TEMPERATURE,
            rule = effectiveRules[PolicyField.TEMPERATURE],
            base = coursePolicy.temperature,
            pedido = requested(student, preset, PolicyField.TEMPERATURE),
            overrideAllowed = override,
            origen = originOf(student, preset, PolicyField.TEMPERATURE),
            changes = changes,
        )
        val maxTokens = resolveNumber(
            campo = PolicyField.MAX_TOKENS,
            rule = effectiveRules[PolicyField.MAX_TOKENS],
            base = coursePolicy.maxTokens.toDouble(),
            pedido = requested(student, preset, PolicyField.MAX_TOKENS),
            overrideAllowed = override,
            origen = originOf(student, preset, PolicyField.MAX_TOKENS),
            changes = changes,
        ).toInt()
        val topK = resolveNumber(
            campo = PolicyField.TOP_K,
            rule = effectiveRules[PolicyField.TOP_K],
            base = coursePolicy.topK.toDouble(),
            pedido = requested(student, preset, PolicyField.TOP_K),
            overrideAllowed = override,
            origen = originOf(student, preset, PolicyField.TOP_K),
            changes = changes,
        ).toInt()

        // ---- 4. Modo local: el modelo es del alumno -----------------------
        if (mode == ModelMode.LOCAL) {
            val local = resolveLocal(coursePolicy, student, effectiveRules, changes)
            val effective = coursePolicy.copy(
                mode = ModelMode.LOCAL,
                apiKey = "",
                apiKeyRef = "",
                remoteBaseUrl = "",
                modelName = local.hfFile,
                hfRepo = local.hfRepo,
                hfFile = local.hfFile,
                modelsDir = local.modelsDir,
                llamaServerPath = local.llamaServerPath,
                localPort = local.localPort,
                contextSize = local.contextSize,
                gpuLayers = local.gpuLayers,
                temperature = temperature,
                maxTokens = maxTokens,
                topK = topK,
                systemPrompt = systemPrompt,
            )
            return ModelResolution(effective, changes.toList(), changes.filter { it.isRejected })
        }

        // ---- 5. Modo remoto: endpoint, modelo y credencial ----------------
        val remoteBaseUrl = resolveText(
            campo = PolicyField.REMOTE_BASE_URL,
            rule = effectiveRules[PolicyField.REMOTE_BASE_URL],
            base = coursePolicy.remoteBaseUrl,
            pedido = requested(student, preset, PolicyField.REMOTE_BASE_URL),
            overrideAllowed = override,
            origen = originOf(student, preset, PolicyField.REMOTE_BASE_URL),
            changes = changes,
        )
        val modelName = resolveText(
            campo = PolicyField.MODEL_NAME,
            rule = effectiveRules[PolicyField.MODEL_NAME],
            base = coursePolicy.modelName,
            pedido = requested(student, preset, PolicyField.MODEL_NAME),
            overrideAllowed = override,
            origen = originOf(student, preset, PolicyField.MODEL_NAME),
            changes = changes,
        )
        val credential = resolveApiKey(coursePolicy, student, preset, effectiveRules[PolicyField.API_KEY], override, personalKey, changes)

        val effective = coursePolicy.copy(
            mode = ModelMode.REMOTE,
            remoteBaseUrl = remoteBaseUrl,
            modelName = modelName,
            apiKey = credential.key,
            apiKeyRef = credential.ref,
            temperature = temperature,
            maxTokens = maxTokens,
            topK = topK,
            systemPrompt = systemPrompt,
        )
        return ModelResolution(effective, changes.toList(), changes.filter { it.isRejected })
    }

    /**
     * Reglas efectivas de la política: los campos de `lockedFields` quedan
     * bloqueados y las reglas finas (si las hay) pisan a los bloqueos.
     *
     * TODO(docs/design/domain/05-modelo-config.md §4.1): cuando `ModelPolicy`
     * tenga `fieldRules`, esto pasa a ser `ModelPolicy.effectiveRules` y el
     * `Map` explícito de `resolve` desaparece. Mientras tanto, los campos
     * del modelo local (repo, archivo, puerto, contexto, GPU) **no** se
     * pueden bloquear con `lockedFields` (el enum `LockedField` no los
     * tiene): se bloquean pasando la regla explícita por parámetro.
     */
    fun rulesFor(policy: ModelPolicy): Map<PolicyField, PolicyRule> {
        val out = LinkedHashMap<PolicyField, PolicyRule>()
        policy.lockedFields.forEach { locked ->
            PolicyField.fromLocked(locked)?.let { out[it] = PolicyRule.Locked }
        }
        return out
    }

    /**
     * Reporte para la vista del docente.
     *
     * El diff va **sanitizado**: la API key nunca aparece (ni en crudo ni
     * enmascarado más de lo debido).
     */
    fun report(userId: String, displayName: String, resolution: ModelResolution): StudentConfigReport =
        StudentConfigReport.of(userId, displayName, resolution.changes)

    /** Reporte para la vista del docente, resolviendo primero. */
    fun report(
        coursePolicy: ModelPolicy,
        student: StudentModelProfile,
        displayName: String,
        rules: Map<PolicyField, PolicyRule> = emptyMap(),
        preset: PolicyPreset? = null,
        allowLocalModels: Boolean = true,
        personalKey: String = "",
    ): StudentConfigReport = report(
        userId = student.userId,
        displayName = displayName,
        resolution = resolve(coursePolicy, student, rules, preset, allowLocalModels, personalKey),
    )

    // ------------------------------------------------------------------
    // Piezas de la resolución
    // ------------------------------------------------------------------

    /**
     * El modo es **la válvula de escape** de la política.
     *
     * Es la única excepción a `allowStudentOverride == false`: si la key de la
     * institución venció y el alumno tiene IA local descargada, bloquearle el
     * modo lo deja sin poder estudiar. Pass-through deliberado:
     *
     *  - `allowStudentOverride == false` ⇒ el alumno usa exactamente lo de la
     *    cátedra, **salvo** que pida `LOCAL`;
     *  - si la cátedra **no permite** IA local (`allowLocalModels == false`),
     *    el pedido se rechaza: es una decisión explícita de la cátedra;
     *  - si la cátedra fijó el modo a propósito (regla [PolicyRule.Locked] o
     *    [PolicyRule.Choice]), esa decisión explícita gana: el docente que
     *    bloquea el modo está diciendo "no hay IA local en esta materia".
     */
    private fun resolveMode(
        policy: ModelPolicy,
        student: StudentModelProfile,
        preset: PolicyPreset?,
        rules: Map<PolicyField, PolicyRule>,
        allowLocalModels: Boolean,
        override: Boolean,
        changes: MutableList<ConfigChange>,
    ): ModelMode {
        val rule = rules[PolicyField.MODE]
        val pedido = requested(student, preset, PolicyField.MODE)
        if (pedido == null) {
            push(
                changes, PolicyField.MODE,
                policy.mode.name, policy.mode.name, policy.mode.name,
                if (rule is PolicyRule.Locked) ChangeStatus.LOCKED else ChangeStatus.INHERITED,
                "", ChangeOrigin.POLICY,
            )
            return policy.mode
        }

        val requestedMode = ModelMode.entries.firstOrNull { it.name.equals(pedido.trim(), ignoreCase = true) }
        if (requestedMode == null) {
            push(
                changes, PolicyField.MODE,
                policy.mode.name, pedido, policy.mode.name,
                ChangeStatus.REJECTED,
                "No se pudo interpretar «$pedido» como modo (REMOTE o LOCAL).",
                originOf(student, preset, PolicyField.MODE),
            )
            return policy.mode
        }

        if (rule is PolicyRule.Locked) {
            push(
                changes, PolicyField.MODE,
                policy.mode.name, requestedMode.name, policy.mode.name,
                ChangeStatus.REJECTED,
                "La cátedra fijó el modo en «${policy.mode.label}»: no se puede cambiar.",
                originOf(student, preset, PolicyField.MODE),
            )
            return policy.mode
        }
        if (rule is PolicyRule.Choice && requestedMode.name !in rule.values) {
            push(
                changes, PolicyField.MODE,
                policy.mode.name, requestedMode.name, policy.mode.name,
                ChangeStatus.REJECTED,
                "El modo «${requestedMode.label}» no está permitido por la cátedra " +
                    "(${rule.values.joinToString(", ")}).",
                originOf(student, preset, PolicyField.MODE),
            )
            return policy.mode
        }
        if (requestedMode == ModelMode.LOCAL && !allowLocalModels) {
            push(
                changes, PolicyField.MODE,
                policy.mode.name, requestedMode.name, policy.mode.name,
                ChangeStatus.REJECTED,
                "La cátedra no permite usar IA local en esta materia.",
                originOf(student, preset, PolicyField.MODE),
            )
            return policy.mode
        }
        if (!override && requestedMode != policy.mode) {
            if (requestedMode == ModelMode.LOCAL) {
                // Válvula de escape: se cuela aunque la cátedra no permita ajustar nada.
                push(
                    changes, PolicyField.MODE,
                    policy.mode.name, requestedMode.name, requestedMode.name,
                    ChangeStatus.ACCEPTED,
                    "Aunque la cátedra no permita ajustar la configuración, se acepta pasar a IA local: " +
                        "es la única forma de seguir trabajando si la clave de la institución vence.",
                    originOf(student, preset, PolicyField.MODE),
                )
                return ModelMode.LOCAL
            }
            push(
                changes, PolicyField.MODE,
                policy.mode.name, requestedMode.name, policy.mode.name,
                ChangeStatus.REJECTED,
                "La cátedra no permite ajustar la configuración del modelo.",
                originOf(student, preset, PolicyField.MODE),
            )
            return policy.mode
        }
        push(
            changes, PolicyField.MODE,
            policy.mode.name, requestedMode.name, requestedMode.name,
            if (requestedMode == policy.mode) ChangeStatus.INHERITED else ChangeStatus.ACCEPTED,
            "",
            originOf(student, preset, PolicyField.MODE),
        )
        return requestedMode
    }

    /**
     * El prompt del sistema **se compone**: el de la cátedra define el rol y
     * el del alumno es un extra ("resumí en viñetas").
     *
     * Si está bloqueado, gana el de la cátedra y el del alumno se descarta
     * (queda registrado como `REJECTED`).
     */
    private fun resolveSystemPrompt(
        policy: ModelPolicy,
        student: StudentModelProfile,
        preset: PolicyPreset?,
        rules: Map<PolicyField, PolicyRule>,
        override: Boolean,
        changes: MutableList<ConfigChange>,
    ): String {
        val rule = rules[PolicyField.SYSTEM_PROMPT]
        val base = policy.systemPrompt
        val pedido = requested(student, preset, PolicyField.SYSTEM_PROMPT)
        if (pedido == null) {
            push(
                changes, PolicyField.SYSTEM_PROMPT,
                base, base, base,
                if (rule is PolicyRule.Locked) ChangeStatus.LOCKED else ChangeStatus.INHERITED,
                "", ChangeOrigin.POLICY,
            )
            return base
        }
        val extra = pedido.trim()
        if (rule is PolicyRule.Locked) {
            push(
                changes, PolicyField.SYSTEM_PROMPT,
                base, extra, base,
                ChangeStatus.REJECTED,
                "La cátedra fijó las instrucciones del sistema: las tuyas se descartan.",
                originOf(student, preset, PolicyField.SYSTEM_PROMPT),
            )
            return base
        }
        if (!override) {
            push(
                changes, PolicyField.SYSTEM_PROMPT,
                base, extra, base,
                ChangeStatus.REJECTED,
                "La cátedra no permite ajustar la configuración del modelo.",
                originOf(student, preset, PolicyField.SYSTEM_PROMPT),
            )
            return base
        }
        val combined = when {
            extra.isBlank() -> base
            base.isBlank() -> extra
            else -> base.trimEnd() + "\n\n" + extra
        }
        push(
            changes, PolicyField.SYSTEM_PROMPT,
            base, extra, combined,
            if (combined == base) ChangeStatus.INHERITED else ChangeStatus.ACCEPTED,
            "",
            originOf(student, preset, PolicyField.SYSTEM_PROMPT),
        )
        return combined
    }

    /**
     * El modelo local del alumno.
     *
     * El modelo local **no es un delta**: es del alumno (por eso no lo manda
     * a la cátedra ni aparece en su política). Pero los valores que la
     * cátedra acota (por ejemplo el contexto) se le recortan igual, para que
     * las respuestas sean comparables entre alumnos.
     */
    private fun resolveLocal(
        policy: ModelPolicy,
        student: StudentModelProfile,
        rules: Map<PolicyField, PolicyRule>,
        changes: MutableList<ConfigChange>,
    ): LocalModelProfile {
        val local = student.localModel
        val origen = if (local == null) ChangeOrigin.POLICY else ChangeOrigin.STUDENT
        val hfRepo = resolveText(
            campo = PolicyField.HF_REPO,
            rule = rules[PolicyField.HF_REPO],
            base = policy.hfRepo,
            pedido = local?.hfRepo,
            overrideAllowed = true,
            origen = origen,
            changes = changes,
        )
        val hfFile = resolveText(
            campo = PolicyField.HF_FILE,
            rule = rules[PolicyField.HF_FILE],
            base = policy.hfFile,
            pedido = local?.hfFile,
            overrideAllowed = true,
            origen = origen,
            changes = changes,
        )
        val localPort = resolveNumber(
            campo = PolicyField.LOCAL_PORT,
            rule = rules[PolicyField.LOCAL_PORT],
            base = policy.localPort.toDouble(),
            pedido = local?.localPort?.toString(),
            overrideAllowed = true,
            origen = origen,
            changes = changes,
        ).toInt()
        val contextSize = resolveNumber(
            campo = PolicyField.CONTEXT_SIZE,
            rule = rules[PolicyField.CONTEXT_SIZE],
            base = policy.contextSize.toDouble(),
            pedido = local?.contextSize?.toString(),
            overrideAllowed = true,
            origen = origen,
            changes = changes,
        ).toInt()
        val gpuLayers = resolveNumber(
            campo = PolicyField.GPU_LAYERS,
            rule = rules[PolicyField.GPU_LAYERS],
            base = policy.gpuLayers.toDouble(),
            pedido = local?.gpuLayers?.toString(),
            overrideAllowed = true,
            origen = origen,
            changes = changes,
        ).toInt()

        return LocalModelProfile(
            hfRepo = hfRepo,
            hfFile = hfFile,
            modelsDir = local?.modelsDir ?: policy.modelsDir,
            llamaServerPath = local?.llamaServerPath ?: policy.llamaServerPath,
            localPort = localPort,
            contextSize = contextSize,
            gpuLayers = gpuLayers,
        )
    }

    /**
     * Decide qué credencial se usa.
     *
     * - Campo bloqueado ⇒ **siempre** la de la cátedra (la del alumno se
     *   descarta y queda registrado como `REJECTED`).
     * - Sin bloqueo ⇒ la del alumno si tiene, si no la de la cátedra.
     * - Con `allowStudentOverride == false` ⇒ la de la cátedra.
     *
     * Los valores que se registran en el diff van **enmascarados**: la
     * clave en crudo no sale de acá.
     *
     * Devuelve además el **alias** del secreto en el almacén seguro
     * ([ModelPolicy.apiKeyRef]): si gana la clave de la cátedra se conserva
     * el de la cátedra; si gana la del alumno, el de la cátedra deja de
     * aplicar y manda el del alumno ([StudentModelProfile.personalKeyRef]).
     */
    private fun resolveApiKey(
        policy: ModelPolicy,
        student: StudentModelProfile,
        preset: PolicyPreset?,
        rule: PolicyRule?,
        override: Boolean,
        personalKey: String,
        changes: MutableList<ConfigChange>,
    ): Credential {
        val base = policy.apiKey
        val pedido = student.delta(PolicyField.API_KEY)
            ?: requested(student, preset, PolicyField.API_KEY)
            ?: personalKey.takeIf { it.isNotBlank() && student.personalKeyRef.isNotBlank() }
        if (pedido == null) {
            push(
                changes, PolicyField.API_KEY,
                base, base, base,
                if (rule is PolicyRule.Locked) ChangeStatus.LOCKED else ChangeStatus.INHERITED,
                "", ChangeOrigin.POLICY,
            )
            return Credential(base, policy.apiKeyRef)
        }
        if (rule is PolicyRule.Locked) {
            push(
                changes, PolicyField.API_KEY,
                base, pedido, base,
                ChangeStatus.REJECTED,
                "La cátedra fijó la clave de API: la tuya se descarta.",
                originOf(student, preset, PolicyField.API_KEY),
            )
            return Credential(base, policy.apiKeyRef)
        }
        if (!override) {
            push(
                changes, PolicyField.API_KEY,
                base, pedido, base,
                ChangeStatus.REJECTED,
                "La cátedra no permite ajustar la configuración del modelo.",
                originOf(student, preset, PolicyField.API_KEY),
            )
            return Credential(base, policy.apiKeyRef)
        }
        push(
            changes, PolicyField.API_KEY,
            base, pedido, pedido,
            if (pedido == base) ChangeStatus.INHERITED else ChangeStatus.ACCEPTED,
            "",
            originOf(student, preset, PolicyField.API_KEY),
        )
        return Credential(pedido, student.personalKeyRef)
    }

    // ------------------------------------------------------------------
    // Aplicación de las reglas
    // ------------------------------------------------------------------

    /** Resultado interno de aplicar una regla a un campo numérico. */
    private data class NumericOutcome(
        val effective: Double,
        val status: ChangeStatus,
        val reason: String,
    )

    /** Resultado interno de aplicar una regla a un campo de texto. */
    private data class TextOutcome(
        val effective: String,
        val status: ChangeStatus,
        val reason: String,
    )

    /**
     * La credencial que se usa: la clave en crudo (sólo en memoria) y su
     * alias en el almacén seguro ([ModelPolicy.apiKeyRef]).
     */
    private data class Credential(
        val key: String,
        val ref: String,
    )

    private fun resolveNumber(
        campo: PolicyField,
        rule: PolicyRule?,
        base: Double,
        pedido: String?,
        overrideAllowed: Boolean,
        origen: ChangeOrigin,
        changes: MutableList<ConfigChange>,
    ): Double {
        if (pedido == null) {
            push(
                changes, campo,
                formatNumber(base), formatNumber(base), formatNumber(base),
                if (rule is PolicyRule.Locked) ChangeStatus.LOCKED else ChangeStatus.INHERITED,
                "", ChangeOrigin.POLICY,
            )
            return base
        }
        val requestedValue = pedido.trim().toDoubleOrNull()
        if (requestedValue == null) {
            push(
                changes, campo,
                formatNumber(base), pedido, formatNumber(base),
                ChangeStatus.REJECTED,
                "No se pudo interpretar «$pedido» como número.",
                origen,
            )
            return base
        }
        if (!overrideAllowed) {
            push(
                changes, campo,
                formatNumber(base), formatNumber(requestedValue), formatNumber(base),
                ChangeStatus.REJECTED,
                "La cátedra no permite ajustar la configuración del modelo.",
                origen,
            )
            return base
        }
        val outcome = when (rule) {
            is PolicyRule.Locked -> NumericOutcome(
                effective = base,
                status = ChangeStatus.REJECTED,
                reason = "La cátedra fijó «${campo.label}»: tu valor se descarta.",
            )

            is PolicyRule.Ranged -> {
                val clamped = requestedValue.coerceIn(rule.min, rule.max)
                if (clamped != requestedValue) {
                    NumericOutcome(
                        effective = clamped,
                        status = ChangeStatus.CLAMPED,
                        reason = "La cátedra limita ${campo.label.lowercase()} a " +
                            "${formatNumber(rule.min)} – ${formatNumber(rule.max)}: se usa ${formatNumber(clamped)}.",
                    )
                } else {
                    NumericOutcome(
                        effective = clamped,
                        status = if (clamped != base) ChangeStatus.ACCEPTED else ChangeStatus.INHERITED,
                        reason = "",
                    )
                }
            }

            is PolicyRule.Choice -> NumericOutcome(
                effective = base,
                status = ChangeStatus.REJECTED,
                reason = "«${campo.label}» está fijo por la cátedra.",
            )

            else -> NumericOutcome(
                effective = requestedValue,
                status = if (requestedValue != base) ChangeStatus.ACCEPTED else ChangeStatus.INHERITED,
                reason = "",
            )
        }
        push(
            changes, campo,
            formatNumber(base), formatNumber(requestedValue), formatNumber(outcome.effective),
            outcome.status, outcome.reason, origen,
        )
        return outcome.effective
    }

    private fun resolveText(
        campo: PolicyField,
        rule: PolicyRule?,
        base: String,
        pedido: String?,
        overrideAllowed: Boolean,
        origen: ChangeOrigin,
        changes: MutableList<ConfigChange>,
    ): String {
        if (pedido == null) {
            push(
                changes, campo,
                base, base, base,
                if (rule is PolicyRule.Locked) ChangeStatus.LOCKED else ChangeStatus.INHERITED,
                "", ChangeOrigin.POLICY,
            )
            return base
        }
        val requestedValue = pedido.trim()
        if (!overrideAllowed) {
            push(
                changes, campo,
                base, requestedValue, base,
                ChangeStatus.REJECTED,
                "La cátedra no permite ajustar la configuración del modelo.",
                origen,
            )
            return base
        }
        val outcome = when (rule) {
            is PolicyRule.Locked -> TextOutcome(
                effective = base,
                status = ChangeStatus.REJECTED,
                reason = "La cátedra fijó «${campo.label}»: tu valor se descarta.",
            )

            is PolicyRule.Choice -> if (requestedValue in rule.values) {
                TextOutcome(
                    effective = requestedValue,
                    status = if (requestedValue != base) ChangeStatus.ACCEPTED else ChangeStatus.INHERITED,
                    reason = "",
                )
            } else {
                TextOutcome(
                    effective = base,
                    status = ChangeStatus.REJECTED,
                    reason = "«$requestedValue» no está entre los valores permitidos por la cátedra " +
                        "(${rule.values.joinToString(", ")}).",
                )
            }

            else -> TextOutcome(
                effective = requestedValue,
                status = if (requestedValue != base) ChangeStatus.ACCEPTED else ChangeStatus.INHERITED,
                reason = "",
            )
        }
        push(changes, campo, base, requestedValue, outcome.effective, outcome.status, outcome.reason, origen)
        return outcome.effective
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Registra un cambio en el diff.
     *
     * El diff **se calcula durante la resolución**: cada vez que se aplica
     * una regla se deja acá la traza de base / pedido / efectivo. Así el diff
     * que ve el docente *es* la configuración que se usó.
     */
    private fun push(
        changes: MutableList<ConfigChange>,
        campo: PolicyField,
        valorBase: String,
        valorPedido: String,
        valorEfectivo: String,
        estado: ChangeStatus,
        motivo: String,
        origen: ChangeOrigin,
    ) {
        changes += ConfigChange(
            campo = campo,
            valorBase = maskIfSecret(campo, valorBase),
            valorPedido = maskIfSecret(campo, valorPedido),
            valorEfectivo = maskIfSecret(campo, valorEfectivo),
            estado = estado,
            origen = origen,
            motivo = motivo,
        )
    }

    /** Lo que pidió el alumno (delta) o el preset; `null` = nadie lo tocó. */
    private fun requested(
        student: StudentModelProfile,
        preset: PolicyPreset?,
        campo: PolicyField,
    ): String? = student.delta(campo) ?: preset?.values?.get(campo)?.takeIf { it.isNotBlank() }

    /** De dónde salió el valor pedido. */
    private fun originOf(
        student: StudentModelProfile,
        preset: PolicyPreset?,
        campo: PolicyField,
    ): ChangeOrigin = when {
        student.delta(campo) != null -> ChangeOrigin.STUDENT
        preset?.values?.get(campo)?.isNotBlank() == true -> ChangeOrigin.PRESET
        else -> ChangeOrigin.POLICY
    }

    /** Enmascara el valor si el campo es secreto. */
    private fun maskIfSecret(campo: PolicyField, value: String): String =
        if (campo.isSecret) maskSecret(value) else value
}

/**
 * Enmascara un secreto para mostrarlo: `"sk-1234567890abcdef"` → `"••••cdef"`.
 *
 * Nunca devuelve el valor completo, así que se puede usar sin miedo en el
 * diff y en los reportes del docente. Es **idempotente**: enmascarar algo ya
 * enmascarado no vuelve a recortar los últimos caracteres.
 */
fun maskSecret(value: String): String = when {
    value.isBlank() -> ""
    value.startsWith(MASK_PREFIX) -> value
    value.length <= 8 -> MASK_PREFIX
    else -> MASK_PREFIX + value.takeLast(4)
}

/** Prefijo con el que se marca un valor enmascarado. */
const val MASK_PREFIX: String = "••••"

/**
 * Formatea un número para el diff: sin decimales inútiles
 * (`1024.0` → `"1024"`, `0.2` → `"0.2"`).
 */
private fun formatNumber(value: Double): String {
    val text = value.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
