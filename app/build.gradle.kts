import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One number, read from gradle.properties, turned into every other form of itself.
val appVersion = (project.findProperty("appVersion") as String? ?: "1").trim().toInt()

// The signing key is a repository secret restored by the workflow. Locally the file is absent and
// the release build falls back to unsigned, which is correct: a build made on a desk is not a
// build that may be delivered (delivery-gate.md G1).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// THE GOOGLE MAPS KEY IS NEVER IN THE REPOSITORY (secrets.md 3, keyring.md 11). It arrives as a
// gradle property from local.properties or from the workflow, which writes it from a repository
// secret. When it is absent the app still builds and still runs: the Google layer is present in
// the switcher and inactive, with the reason on it. Nothing appears or disappears.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val googleMapsKey: String = (localProperties.getProperty("googleMapsKey")
    ?: project.findProperty("googleMapsKey") as String?
    ?: System.getenv("GOOGLE_MAPS_API_KEY")
    ?: "").trim()

android {
    namespace = "com.mantra.trail"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mantra.trail"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = appVersion.toString()
        manifestPlaceholders["googleMapsKey"] = googleMapsKey
        buildConfigField("boolean", "HAS_GOOGLE_KEY", if (googleMapsKey.isEmpty()) "false" else "true")
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // G3: Lint blocking from the first build. Narrow it in the session it cries wolf, never carry it.
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = false
        xmlReport = true
        // Mapsforge is a Java library built before the AndroidX era; its own API surface is not
        // something this app can fix, and a blanket block on it would stop the build for somebody
        // else's code rather than for ours.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "ObsoleteLintCustomCheck")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // play-services-maps drags in a pre-AndroidX-era fragment, and registerForActivityResult is
    // unsafe against it (InvalidFragmentVersionForActivityResult, build 3). Naming a modern one
    // here raises the resolved version rather than turning the check off: the check was right.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // The map engine: offline vector .map files (OpenAndroMaps) and raster tile layers with a
    // file-backed cache, in one view. Pure Java, no native libraries, so CI builds it unchanged.
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-map:0.25.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    // androidsvg is NOT declared here: mapsforge-map-android already brings the plain jar, and
    // declaring the aar as well put both on the path and every class in it twice
    // (checkReleaseDuplicateClasses, build 2). One copy, and it is the one mapsforge chose.

    // The fix: GPS, Wi-Fi and cell fused by the system, plus the raw satellite status underneath it.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Google's own map, online only: its terms forbid caching tiles (Map Tiles API policies).
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:maps-compose:6.4.1")

    testImplementation("junit:junit:4.13.2")
}
