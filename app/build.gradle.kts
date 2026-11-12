plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val keystore = env("KS_PATH")?.let { file(it) }?.takeIf { it.exists() }
// pannello Kairos (lo stesso di KAIROS TV); la variabile KAIROS_SERVER_URL del repository, se c'è, lo sostituisce
val serverUrl = (env("KAIROS_SERVER_URL") ?: "https://kairos-panel.mariopiccolo8562.workers.dev").trimEnd('/').replace("\"", "")
// download e aggiornamenti solo dal dominio dei download, niente pannello né GitHub (vedi UpdFeed.kt): l'app chiede
// <DL_BASE>/get/tv.json; la variabile KAIROS_DL_BASE del repository, se c'è, lo sostituisce
val dlBase = (env("KAIROS_DL_BASE") ?: "https://store-k.download").trimEnd('/').replace("\"", "")
// secondo dominio dei download, di riserva (variabile KAIROS_DL_BASE2, vuota finché non c'è): oltre ai domini che il
// pannello annuncia nei file degli aggiornamenti («mirrors», che l'app ricorda)
val dlBase2 = (env("KAIROS_DL_BASE2") ?: "").trimEnd('/').replace("\"", "")

android {
    namespace = "com.kairos.tv"
    compileSdk = 35

    defaultConfig {
        // app separata da Kairos Pro e Kairos Lite: si installa accanto, con aggiornamenti suoi
        applicationId = env("TV_APP_ID") ?: "com.kairos.tv"
        // 22 = Android 5.1: Fire TV Stick di 1ª e 2ª generazione (Fire OS 5) comprese
        minSdk = 22
        targetSdk = 35
        versionCode = env("VERSION_CODE")?.toInt() ?: 1
        versionName = env("VERSION_NAME") ?: "1.0.0"
        resValue("string", "app_name", env("TV_APP_NAME") ?: "Kairos TV")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "DL_BASE", "\"$dlBase\"")
        buildConfigField("String", "DL_BASE2", "\"$dlBase2\"")
        // Fire TV / Android TV / telefoni sono ARM: niente x86, APK più piccolo
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // l'app parla solo italiano: delle librerie (lettore, ecc.) restano i testi in italiano e quelli di base,
        // non le traduzioni in decine di altre lingue che l'app non mostra mai
        resourceConfigurations += listOf("it")
    }

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = keystore
                storePassword = env("KS_PASS")
                keyAlias = env("KEY_ALIAS")
                keyPassword = env("KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // la libreria WireGuard usa java.time / Optional / stream: servono anche su Android 5-7
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // poche librerie, tutte compatibili con Android 5.1
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
    // JSON e lettore XML veri per le prove della guida XMLTV e dei trailer (nell'app li dà Android)
    testImplementation("org.json:json:20231013")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // profilo di base (src/main/baseline-prof.txt) installato al primo avvio: avvio e liste già compilati (libreria piccola)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // 1.0.20250531 = ultima con minSdk 21 (stesso motore wireguard-go della versione successiva)
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
