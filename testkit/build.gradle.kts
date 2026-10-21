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
        }
    }
}

android {
    namespace = "com.funshiengine.kitoru.testkit"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
