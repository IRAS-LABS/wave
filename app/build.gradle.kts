import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wave.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wave.scanner"
        // 29 (Android 10), not 31. Plenty of still-serviceable budget hardware never got
        // past Android 11 or 12, and minSdk 31 made the APK refuse to install on it.
        // Everything 31-only is already behind a Build.VERSION guard.
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.2"
    }

    // Signing material is never in this file and never in the repository. Put a
    // keystore.properties next to it - storeFile, storePassword, keyAlias, keyPassword -
    // and keep both it and the .jks out of version control; .gitignore already does.
    // Without it the release build is simply unsigned, which is the correct behaviour for
    // anyone who cloned this and is not the person who publishes the releases.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasKeystore = keystoreProps.getProperty("storeFile")
        ?.let { rootProject.file(it).exists() } == true

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v2 is on by default; v3 adds the rotation-capable block, so a future
                // key change does not have to break every existing install.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // AGP otherwise embeds the git repository URL, branch and commit hash into
            // META-INF/version-control-info.textproto inside the APK. A shipped binary has
            // no business carrying the layout of the machine that built it.
            vcsInfo { include = false }

            // R8 matters more than usual here: material-icons-extended alone is around
            // 30 MB of dex and Wave uses seven icons from it. Shrinking takes the APK
            // from 62 MB to roughly a third of that.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // buildConfig carries the version name into the OSM User-Agent, so it cannot
    // drift out of date the way a hardcoded string does.
    buildFeatures { compose = true; buildConfig = true }

    // The bundled OUI SQLite database is already compressed content; leaving it
    // uncompressed lets us open it by memory-mapping instead of unpacking 18 MB on boot.
    androidResources {
        noCompress += listOf("db", "mbtiles")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }
}

dependencies {
    // Test-only: JUnit never enters the APK, so the shipped size is unaffected.
    testImplementation("junit:junit:4.13.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // No map SDK on purpose: every tile request would tell a third party where this phone
    // has been, which is the exact exposure Wave exists to detect. The map tab projects
    // your own data locally instead, and KML export covers the case where you want it on
    // top of real streets.

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
