plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

base {
    archivesName.set("Mi-Cerebro")
}

val releaseStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull

android {
    namespace = "com.mhss.app.mybrain"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mhss.app.mybrain"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 21
        versionName = "3.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val releaseSigningConfig =
        if (
            listOf(
                releaseStoreFile,
                releaseStorePassword,
                releaseKeyAlias,
                releaseKeyPassword,
            ).all { !it.isNullOrBlank() }
        ) {
            signingConfigs.create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else {
            null
        }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            releaseSigningConfig?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "Mi Cerebro Debug")
        }
        // Versión optimizada para el celular propio: R8 + recursos reducidos + baseline profile,
        // pero con el MISMO paquete (.debug) y la MISMA firma (debug.keystore) que la debug ya
        // instalada, así `adb install -r` actualiza encima sin perder datos y la app Soledad
        // conserva el permiso de firma LEER_TAREAS.
        create("personal") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-personal"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            ndk {
                // El Xiaomi es arm64; armeabi-v7a queda por compatibilidad con el otro celular.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }
    sourceSets {
        // Reusar los perfiles generados para release (baseline + startup) en la variante personal.
        getByName("personal") {
            baselineProfiles.directories.add("src/release/generated/baselineProfiles")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }
    lint {
        disable.add("MissingTranslation")
        disable.add("NullSafeMutableLiveData")
    }
}

baselineProfile {
    saveInSrc = true
}

dependencies {
    implementation(libs.androidx.profileinstaller)
    implementation(projects.composeApp)
    implementation(projects.core.ui)
    implementation(projects.core.datetime)

    implementation(project.dependencies.platform(libs.compose.bom))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.bundles.compose)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.android.workmanager)
    baselineProfile(projects.baselineProfile)
}
