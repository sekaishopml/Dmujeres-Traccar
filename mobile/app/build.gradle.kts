import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("io.sentry.android.gradle")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.dmujeres.traccar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dmujeres.traccar"
        minSdk = 26
        targetSdk = 35
        versionCode = 87
        versionName = "1.0.87"
        // DSN de Sentry para reporte de crashes. Ver docs/SENTRY.md.
        // Se obtuvo de tu proyecto "DMujeres Tracking" (org sekaidev-w5).
        buildConfigField("String", "SENTRY_DSN", "\"https://1f47e345c56f117bf87d9221a403e53a@o4511839263064064.ingest.us.sentry.io/4512058795491328\"")
    }

    // Firma DEBUG compartida del equipo (keystore versionado en
    // mobile/keystore/debug.keystore): todos firmamos igual y el APK instala
    // encima sin desinstalar, en cualquier PC. NO poner aquí la release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.create("release").apply {
                    keyAlias = keystoreProperties["keyAlias"] as String?
                    keyPassword = keystoreProperties["keyPassword"] as String?
                    storeFile = file(keystoreProperties["storeFile"] as String?)
                    storePassword = keystoreProperties["storePassword"] as String?
                }
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
        buildConfig = true
        compose = true
    }

    // Unit tests JVM: los stubs de android.jar lanzan por defecto ("not mocked").
    // Con defaults, android.util.Log y amigos son no-op en tests (org.json sí es
    // real vía testImplementation). Sin esto, cualquier Log en código bajo test
    // rompería la suite con RuntimeException en vez de probar lógica.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Reporte de crashes (Sentry). Solo se activa con SENTRY_DSN configurado
    // arriba; ver docs/SENTRY.md para obtenerlo gratis.
    implementation("io.sentry:sentry-android:7.16.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // org.json real para unit tests (el stub de android.jar lanza "not mocked").
    testImplementation("org.json:json:20180813")
}

// Subida de mapping R8 a Sentry (pilas legibles en release). El token NUNCA
// va en este fichero: se lee de la variable de entorno SENTRY_AUTH_TOKEN.
// Sin token, el build sigue funcionando (solo avisa y omite la subida).
sentry {
    org.set("sekaidev-w5")
    projectName.set("dmujeres-tracking")
    val sentryToken = System.getenv("SENTRY_AUTH_TOKEN")
    if (!sentryToken.isNullOrBlank()) {
        authToken.set(sentryToken)
    }
    autoUploadProguardMapping.set(true)
    includeProguardMapping.set(true)
}
