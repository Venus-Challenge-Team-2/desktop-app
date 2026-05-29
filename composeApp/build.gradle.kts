import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.koin.compiler)
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

kotlin {
    jvm()
    
    js {
        browser()
        binaries.executable()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation("de.kempmobil.ktor.mqtt:mqtt-core:1.1.0")
            implementation("de.kempmobil.ktor.mqtt:mqtt-client:1.1.0")
            implementation(project.dependencies.platform("io.insert-koin:koin-bom:4.2.0"))
            implementation("io.insert-koin:koin-core")
            implementation("io.insert-koin:koin-compose")
            implementation("io.insert-koin:koin-compose-viewmodel")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
            val gdxVersion = "1.13.1"
            implementation("com.badlogicgames.gdx:gdx:${gdxVersion}")
            implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:${gdxVersion}") // ◄ This already has everything we need
            implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
            implementation(kotlin("stdlib"))
            api("com.badlogicgames.gdx:gdx:${gdxVersion}")

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.leekleak.venusmonitor.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.leekleak.venusmonitor"
            packageVersion = "1.0.0"
        }
    }
}
