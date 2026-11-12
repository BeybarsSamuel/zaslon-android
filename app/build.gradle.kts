import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.zaslon.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaslon.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 200
        versionName = "2.0.0-zaslon"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val ksPath = System.getenv("ZASLON_KEYSTORE_PATH") ?: keystoreProps.getProperty("storeFile")
        val ksPass = System.getenv("ZASLON_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
        val ksAlias = System.getenv("ZASLON_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
        val ksKeyPass = System.getenv("ZASLON_KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
        if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && !ksAlias.isNullOrBlank() && !ksKeyPass.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 1. Mozilla GeckoView Çekirdeği (Glean ve Google Play Telemetri bağımlılığı olmadan saf motor)
    implementation("org.mozilla.geckoview:geckoview:133.0.20241121140525")

    // 2. Jetpack Compose & Modern Mobil UI
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 3. Asenkron Ağ, ByeDPI SOCKS5 Tüneli ve LAN Eşitleme için Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 4. Medya Oynatma Bildirimi (Ekran Kapalıyken Arka Plan Ses Oynatma)
    implementation("androidx.media:media:1.7.0")

    // 5. Birim Testleri (DoH / ByeDPI / LAN Doğrulaması)
    testImplementation("junit:junit:4.13.2")
}
