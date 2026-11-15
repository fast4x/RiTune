plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// =============================================================================
// FIRMA RELEASE CI (stessa tecnica del workflow .github/workflows/build-release.yml)
// -----------------------------------------------------------------------------
// Il workflow GitHub Actions:
//   1. decodifica il keystore dai secrets in $RUNNER_TEMP/keystore/
//      RiTuneKeystore.jks (mai nel workspace, mai negli artifact; rimosso
//      dopo la build con if: always());
//   2. passa le credenziali alla build via variabili d'ambiente:
//        SIGNING_STORE_FILE      -> percorso del keystore decodificato
//        SIGNING_STORE_PASSWORD  -> password del keystore (secret KEYSTORE_PASSWORD)
//        SIGNING_KEY_ALIAS       -> alias della chiave    (secret KEY_ALIAS)
//        SIGNING_KEY_PASSWORD    -> password della chiave (secret KEY_PASSWORD)
// Il signingConfig "ciRelease" qui sotto legge ESATTAMENTE quelle variabili:
// in CI l'APK release esce firmato; in locale (env assenti) nessun
// signingConfig viene creato e la release resta unsigned come prima.
// =============================================================================
val ciStoreFile = System.getenv("SIGNING_STORE_FILE")
val ciStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val ciKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val ciKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")

android {
    namespace = "it.fast4x.ritune"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.fast4x.ritune"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fail-fast esplicito (filosofia della proforma: mai in silenzio): se le
    // env di firma sono parzialmente impostate o il keystore non esiste, la
    // configurazione Gradle muore subito con messaggio chiaro.
    if (ciStoreFile != null) {
        require(ciStorePassword != null && ciKeyAlias != null && ciKeyPassword != null) {
            "Firma release CI incompleta: impostare TUTTE le variabili " +
                "SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD"
        }
        require(file(ciStoreFile).exists()) {
            "Firma release CI: keystore non trovato: $ciStoreFile"
        }
    }

    signingConfigs {
        if (ciStoreFile != null) {
            create("ciRelease") {
                storeFile = file(ciStoreFile)
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Firma CI: con le env impostate (workflow) l'APK esce firmato;
            // senza env (build locale) resta unsigned, come prima della patch.
            if (ciStoreFile != null) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }

    packaging{
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    dependenciesInfo {
        // Rimuove il blocco "Dependency metadata" dal APK Signing Block
        includeInApk = false
        includeInBundle = false
    }

    /*
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val outputFileName = "RiTune-${output.baseName}-${variant.versionName}.apk"
                output.outputFileName = outputFileName
            }
    }
     */
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidyoutubeplayer)
    implementation(libs.androidyoutubeplayer.custom.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.ktor.network)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    implementation(libs.serialization.json)
    implementation("androidx.compose.material:material-icons-extended")


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
