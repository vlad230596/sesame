plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing is driven entirely by environment variables so that CI can inject
 * a persistent keystore (see SPEC §10) while a local checkout builds without one.
 *
 *   SESAME_KEYSTORE_PATH      — path to the .jks file
 *   SESAME_KEYSTORE_PASSWORD  — keystore password
 *   SESAME_KEY_ALIAS          — key alias
 *   SESAME_KEY_PASSWORD       — key password
 *
 * If SESAME_KEYSTORE_PATH is unset or points at a missing file, a LOCAL release build
 * falls back to the debug signing config instead of failing — convenient for a quick
 * check on a machine that has no keystore.
 *
 * In CI that fallback would be a trap: a typo in the secret would produce a green run
 * and a debug-signed APK in the release, which is exactly what SPEC §10 exists to
 * prevent (such a build refuses to install over the existing app, and the dataset dies
 * with the uninstall). So when CI=true, a release build without a keystore fails.
 */
val sesameKeystoreFile: File? = System.getenv("SESAME_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.isFile }

val runningInCi: Boolean = System.getenv("CI")?.equals("true", ignoreCase = true) == true
val failReleaseWithoutKeystore: Boolean = runningInCi && sesameKeystoreFile == null

/**
 * Version comes from the git tag at release time: the workflow passes -PversionName.
 * Without it the local default below is used, so a plain `./gradlew assembleRelease`
 * still works.
 */
val sesameVersionName: String =
    (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"

/**
 * versionCode выводится ИЗ ВЕРСИИ, а не из счётчика прогонов CI.
 *
 * Счётчик прогонов казался проще, но он не связан с версией: локальная сборка и
 * сборка из CI получали несопоставимые коды, и APK из релиза мог отказаться
 * ставиться поверх уже установленного с формулировкой про понижение версии.
 * Пересоздание репозитория или сброс счётчика ломали бы это так же.
 *
 * major * 1_000_000 + minor * 1_000 + patch: монотонно растёт вместе с semver,
 * оставляет по 999 значений на minor и patch и не переполняет Int до major 2147.
 * Суффикс предрелиза (0.3.0-rc.1) на код не влияет — он метка, а не порядок.
 */
val sesameVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: run {
        val parts = sesameVersionName.substringBefore('-').split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 1_000_000 + minor * 1_000 + patch
    }

android {
    namespace = "com.vlad230596.sesame"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vlad230596.sesame"
        minSdk = 34
        targetSdk = 36
        versionCode = sesameVersionCode
        versionName = sesameVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (sesameKeystoreFile != null) {
            create("release") {
                storeFile = sesameKeystoreFile
                storePassword = System.getenv("SESAME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SESAME_KEY_ALIAS")
                keyPassword = System.getenv("SESAME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Obfuscation is deliberately off at this stage: it only gets in the way
            // of a sideloaded personal build.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Glance widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Настройки (§4.6) — DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Location
    implementation(libs.play.services.location)
}

// Fail release packaging in CI when no keystore was injected, instead of quietly
// shipping a debug-signed APK. Attached to packageRelease so it fires before the APK
// is produced, not after it already exists.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (failReleaseWithoutKeystore) {
            throw GradleException(
                "Release build in CI without a keystore: SESAME_KEYSTORE_PATH is unset " +
                    "or points at a missing file. Check the SESAME_KEYSTORE_B64 secret. " +
                    "Refusing to publish a debug-signed APK - see SPEC section 10.",
            )
        }
    }
}
