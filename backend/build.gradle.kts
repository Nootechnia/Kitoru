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
            api(project(":llm-api"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            implementation(project(":testkit"))
        }
        desktopTest.dependencies {
            // Los tests portados de FunshiEngineAI usan JUnit 5.
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.test)
        }
        desktopMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "com.funshiengine.kitoru.backend"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Los tests portados de FunshiEngineAI usan JUnit 5. Sin `useJUnitPlatform()`
// la tarea corría sin ejecutar nada: el build quedaba verde con 0 tests.
//
// Ojo: se aplica sólo a `desktopTest`. Aplicarlo a todas las tareas `Test`
// rompería los tests unitarios de Android, que usan `kotlin.test`.
tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
