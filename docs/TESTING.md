# Testing de Kitoru

Kitoru tiene tres capas de tests. Cada una responde a una pregunta distinta
y se corre en un momento distinto del ciclo de desarrollo.

| Capa | Dónde | Qué corre | Cuándo |
|---|---|---|---|
| **Lógica** | `commonTest` de `:core`, `:llm-api`, `:backend`, `:ui` | Funciones puras: el analizador, el verificador de citas, el tokenizador, la resolución de permisos, los ViewModels. | En cada PR. Sin red, sin modelo, sin reloj. |
| **Integración** | `desktopTest` | El circuito completo con un gateway falso: el `AgentLoop`, la extracción de materiales, la persistencia. | En cada PR. |
| **Con modelo real** | `:eval` (aún no existe) | El RAG completo contra llama-server con el corpus de prueba. | Manual o nocturno. |

## Por qué `commonTest` y no `desktopTest`

El código común de Kitoru corre en **desktop y en Android**. Un test que
vive en `commonTest` se ejecuta en los dos targets, así que un bug que sólo
aparece en Android (por ejemplo, un `expect` mal implementado) se encuentra
en la máquina de desarrollo, no en la mano del usuario.

Por eso el target común **no** puede usar nada de la JVM: ni `java.nio.file`,
ni `java.awt`, ni `javax.swing`, ni `java.net.http`, ni `ProcessBuilder`. Lo
específico de plataforma va en `desktopMain`, `androidMain` o `jvmCommonMain`
(que comparten los dos, porque los dos son JVM).

## Los dos frameworks

- **`kotlin.test`** en `commonTest`: `kotlin.test.Test`, `assertEquals`,
  `assertTrue`, `assertNull`, `assertFailsWith`. Es lo que se usa para todo
  código común.
- **JUnit 5** en `desktopTest`: se usa para los tests **portados de
  FunshiEngineAI** (el verificador de citas, el bucle del agente, el
  sanitizador de tool calls). Se portaron tal cual para poder demostrar que
  el port no cambió el comportamiento: los mismos tests, los mismos
  nombres, los mismos números.

```kotlin
tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
}
```

**Cuidado**: `useJUnitPlatform()` va **sólo** en `desktopTest`. Aplicado a
todas las tareas `Test` rompe los tests unitarios de Android, que usan
`kotlin.test`.

## Reglas

1. **Un test debe fallar si el comportamiento cambia.** Si un test pasa con
   cualquier código, no está probando nada.
2. **El nombre del test dice qué garantiza**, en español y con backticks:

   ```kotlin
   @Test
   fun `un alumno con visibilidad NONE no aparece en la analítica`() { ... }
   ```

3. **Nada de red, nada de modelo, nada de reloj** en `commonTest`. Si un test
   necesita una respuesta del modelo, se usa un doble (el `ScriptedGateway`
   del módulo `:testkit`).
4. **Nada de disco real**: se usa el `InMemoryFileSystem` del `:testkit`.
5. **Determinista**: si el resultado depende del azar (un código de
   inscripción, un jitter), el generador se **inyecta** con una semilla fija.
6. **El comentario del test explica qué garantiza**, no qué hace el código.

## Los dobles de prueba (`:testkit`)

El módulo `:testkit` existe para que los dobles que necesitan varios módulos
vivan en un solo lugar:

- `InMemoryFileSystem` — un sistema de archivos completo en memoria.
- `ScriptedGateway` — un gateway que responde con un guion de rondas.
- `FailingGateway` — un gateway que siempre falla.
- `Fixtures` — constructores de datos de prueba (curso, inscripción, hilo,
  mensaje) para que los casos sean comparables entre sí.

## Correr los tests

```bash
# Todo (desktop + Android unit tests)
./gradlew check

# Un módulo
./gradlew :core:desktopTest
./gradlew :backend:desktopTest
./gradlew :ui:desktopTest
./gradlew :llm-api:desktopTest

# Sólo un target
./gradlew :core:desktopTest          # JVM
./gradlew :core:testDebugUnitTest    # Android (sin emulador)
```

## Qué hay que testear siempre

- **El analizador**: los totales, las tasas y **el filtro de visibilidad**.
  Una fuga de privacidad (un alumno invisible filtrando qué material
  consultó) es un bug grave: tiene test de regresión.
- **El verificador de citas**: es la métrica de calidad que ve el docente.
  Si cambia, cambia lo que la institución cree que está pasando.
- **La resolución de permisos**: `min` de los tres ejes de visibilidad, y
  *fail-closed* (sin consentimiento ⇒ nunca `FULL`).
- **La puerta de permisos de las herramientas**: si deja pasar una
  herramienta sin permiso, la app escribe archivos o sale a internet sin
  que el usuario lo haya consentido.
- **El redactor de secretos**: la API key no puede aparecer en un log, un
  error ni un `toString()`.
- **La persistencia**: un archivo corrupto no debe romper la app; una
  escritura interrumpida no debe perder datos.

## Lo que NO se testea (y por qué)

| Qué | Por qué | Riesgo que queda |
|---|---|---|
| El gateway contra un servidor real | Requiere red y es lento | Un cambio en el protocolo OpenAI rompe el chat. Mitigación: los tests del cliente con el `MockEngine` de Ktor. |
| El render real de la UI | Compose Multiplatform no tiene un framework de test multiplataforma estable | Un error de layout se ve al usarlo. Mitigación: los tests de lógica de UI y de ViewModel. |
| La inferencia local | Depende del modelo y del hardware | Un cambio en el arranque rompe el modo local. Mitigación: la comprobación de salud del modelo. |
| El rendimiento | Los tests de rendimiento son frágiles | La app se pone lenta con muchos mensajes. Mitigación: el acumulador incremental de métricas y las measurements del diseño. |
