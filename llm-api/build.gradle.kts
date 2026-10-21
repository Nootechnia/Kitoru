plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val desktopMain by getting
        val desktopTest by getting
        val androidMain by getting
        val androidUnitTest by getting

        val jvmCommonMain by creating { dependsOn(commonMain) }
        val jvmCommonTest by creating { dependsOn(commonTest) }
        desktopMain.dependsOn(jvmCommonMain)
        androidMain.dependsOn(jvmCommonMain)
        desktopTest.dependsOn(jvmCommonTest)
        androidUnitTest.dependsOn(jvmCommonTest)

        commonMain.dependencies {
            api(project(":core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            // Los dobles compartidos (ScriptedGateway) viven en :testkit.
            implementation(project(":testkit"))
            // Motor de prueba de Ktor: permite testear el protocolo del
            // gateway (el `usage` del SSE, el `stream_options` del body) sin
            // red ni servidor. Sólo se usa en los tests.
            implementation("io.ktor:ktor-client-mock:3.0.3")
        }
        desktopTest.dependencies {
            // Los tests de :llm-api usan `kotlin.test`, que el plugin de Kotlin
            // resuelve a kotlin-test-junit5 cuando la tarea usa JUnit 5.
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.test)
        }
        desktopMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "com.funshiengine.kitoru.llm"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// JUnit 5 sólo en `desktopTest`: aplicarlo a todas las tareas `Test` rompería
// los tests unitarios de Android, que usan `kotlin.test` con JUnit 4.
tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
