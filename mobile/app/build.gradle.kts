import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    // F2: google-services se aplica condicionalmente más abajo (sin JSON no rompe).
    id("io.sentry.android.gradle")
}

// R2 (SECURITY_BUILD): la firma de release se provisiona SIEMPRE desde
// mobile/keystore.properties (0600, gitignored). Si falta el archivo o el
// keystore no existe, el build release FALLA con error claro (preReleaseBuild,
// abajo). NO hay fallback silencioso a la firma debug.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

// S1: la clave de flota del canal HTTP NUNCA vive en el repo. Se inyecta
// desde mobile/secrets.properties (gitignored, 0600). En debug hay un fallback
// de desarrollo; en release su ausencia rompe el build a propósito.
val secretsProperties = Properties()
val secretsPropertiesFile = rootProject.file("secrets.properties")
if (secretsPropertiesFile.exists()) {
    secretsPropertiesFile.inputStream().use { secretsProperties.load(it) }
}
val mobileApiKeyFromSecrets = secretsProperties.getProperty("MOBILE_HTTP_API_KEY").orEmpty()

android {
    namespace = "com.dmujeres.traccar"
    compileSdk = 35

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.dmujeres.traccar"
        minSdk = 26
        targetSdk = 35
        versionCode = 131
        versionName = "1.1.21"
        // DSN de Sentry para reporte de crashes. Ver docs/SENTRY.md.
        // Se obtuvo de tu proyecto "DMujeres Tracking" (org sekaidev-w5).
        buildConfigField("String", "SENTRY_DSN", "\"https://1f47e345c56f117bf87d9221a403e53a@o4511839263064064.ingest.us.sentry.io/4512058795491328\"")
        // S1: clave de flota inyectada en build. Si no hay secretos, el
        // fallback dev SOLO se usa en builds debug; release lo valida abajo.
        val injectedKey = mobileApiKeyFromSecrets.ifBlank { "dmj-dev-fallback-key" }
        buildConfigField("String", "MOBILE_HTTP_API_KEY", "\"$injectedKey\"")
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
        // R2: la clave de release se define en keystore.properties (ver
        // docs/SECURITY_BUILD.md). Sin el archivo no se crea esta config y el
        // release falla en preReleaseBuild; nunca firma debug en silencio.
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Firma OTA (ver docs/SECURITY_BUILD.md): la flota instalada usa una
            // única identidad de firma; Android rechaza el update si cambia
            // (INSTALL_FAILED_UPDATE_INCOMPATIBLE: "no se instaló la app debido a
            // un conflicto con un paquete"). La clave se provisiona en
            // mobile/keystore.properties; sin ella el release NO firma debug en
            // silencio: el build falla en preReleaseBuild.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // F0: schemas exportados de Room (MigrationTestHelper los lee)
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
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
    // F2: SOLO mensajería FCM (sin analytics/crashlytics/firestore/auth).
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
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
    // Sin token NO se intenta subir (antes rompía assembleRelease con
    // "Auth token is required"); con token, sube mapping + contexto.
    autoUploadProguardMapping.set(!sentryToken.isNullOrBlank())
    includeProguardMapping.set(!sentryToken.isNullOrBlank())
    if (!sentryToken.isNullOrBlank()) {
        authToken.set(sentryToken)
    }
}

// F2: aplica google-services SOLO si el operador colocó google-services.json
// (mobile/app/google-services.json, fuera de git). Sin el archivo, el APK
// compila y la app degrada honestamente: "FCM no configurado".
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// F0: schemas exportados de Room para MigrationTestHelper (assets del androidTest)
android {
    sourceSets {
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }
}


// R2: un release SIN keystore provisionado no se genera (falla a propósito).
// S1: un release SIN clave de flota real no se genera (falla a propósito).
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (!keystorePropertiesFile.exists()) {
            throw GradleException(
                "R2: falta mobile/keystore.properties (0600, gitignored). " +
                    "Copia mobile/keystore.properties.example y apunta a la clave del " +
                    "canal OTA antes de compilar release (ver docs/SECURITY_BUILD.md)."
            )
        }
        val store = file(keystoreProperties["storeFile"] as String)
        if (!store.exists()) {
            throw GradleException(
                "R2: no existe el keystore configurado en keystore.properties: " +
                    store.absolutePath
            )
        }
        if (mobileApiKeyFromSecrets.isBlank()) {
            throw GradleException(
                "S1: falta MOBILE_HTTP_API_KEY en mobile/secrets.properties. " +
                    "Genera mobile/secrets.properties (0600) con la clave de flota " +
                    "antes de compilar release (ver docs/SECURITY_BUILD.md)."
            )
        }
    }
}
