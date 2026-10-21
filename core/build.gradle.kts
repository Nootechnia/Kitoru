plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
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

        // Código que comparten desktop y android (los dos son JVM).
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // PDFBox 2.0.x: la misma API en escritorio y en Android.
                // Va en `jvmCommonMain` (y no en `commonMain`) porque PDFBox
                // es código Java: `commonMain` no puede usar `java.*`.
                implementation("org.apache.pdfbox:pdfbox:2.0.37")
            }
        }
        val jvmCommonTest by creating { dependsOn(commonTest) }
        desktopMain.dependsOn(jvmCommonMain)
        androidMain.dependsOn(jvmCommonMain)
        desktopTest.dependsOn(jvmCommonTest)
        androidUnitTest.dependsOn(jvmCommonTest)

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // El cliente de sincronización habla HTTP con Ktor (el motor lo
            // elige el runtime: CIO en escritorio, OkHttp en Android).
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            implementation(project(":testkit"))
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
    namespace = "com.funshiengine.kitoru.core"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
