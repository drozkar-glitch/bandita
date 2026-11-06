import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
        }
    }
}

val banditaRunNum = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
val banditaBuildNum = System.getenv("BUILD_NUM")?.toIntOrNull() ?: banditaRunNum
val banditaAppVer = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0"

val localKeyProps = Properties().apply {
    val propFile = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: rootProject.file("android_keystore.props").takeIf { it.exists() }
    propFile?.inputStream()?.use { load(it) }
}

fun resolveKeyParam(vararg envKeys: String): String? {
    for (k in envKeys) {
        val envVal = System.getenv(k)?.trim()
        if (!envVal.isNullOrEmpty()) return envVal
        val propVal = localKeyProps.getProperty(k)?.trim()
        if (!propVal.isNullOrEmpty()) return propVal
    }
    return null
}

android {
    namespace = "cz.mares.bandita"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.mares.bandita"
        minSdk = 24
        targetSdk = 36
        versionCode = banditaBuildNum
        versionName = "$banditaAppVer.$banditaBuildNum"
    }

    signingConfigs {
        create("banditaSigning") {
            val ksPath = resolveKeyParam("CM_KEYSTORE_PATH", "STORE_FILE", "KEYSTORE_FILE", "ANDROID_KEYSTORE_PATH", "storeFile")
            val ksPass = resolveKeyParam("CM_KEYSTORE_PASSWORD", "STORE_PASSWORD", "KEYSTORE_PASSWORD", "storePassword")
            val kAlias = resolveKeyParam("CM_KEY_ALIAS", "KEY_ALIAS", "keyAlias")
            val kPass = resolveKeyParam("CM_KEY_PASSWORD", "KEY_PASSWORD", "keyPassword") ?: ksPass

            if (!ksPath.isNullOrEmpty() && file(ksPath).exists() && !ksPass.isNullOrEmpty() && !kAlias.isNullOrEmpty()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = kAlias
                keyPassword = kPass
            }
        }
    }

    buildTypes {
        val customConfig = signingConfigs.findByName("banditaSigning")
        val hasValidCustomKey = customConfig?.storeFile?.exists() == true

        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasValidCustomKey) customConfig else signingConfigs.getByName("debug")
        }
        getByName("debug") {
            signingConfig = if (hasValidCustomKey) customConfig else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
