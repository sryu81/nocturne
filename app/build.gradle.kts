plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.nocturne"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nocturne"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }

    testOptions {
        unitTests.all {
            // Forwards -DnocturneHwHost=<pi-ip> (and nocturneHwPort) from the Gradle invocation
            // into the forked test JVM — Gradle does NOT do this by default. Powers the opt-in
            // EkosRemoteHardwareTest; both this and the NOCTURNE_HW_HOST env var work (env vars
            // are forwarded automatically), see that test's own doc comment. Only set when
            // actually passed — an empty-string property would defeat the test's `?: null` skip.
            System.getProperty("nocturneHwHost")?.let { host -> it.systemProperty("nocturneHwHost", host) }
            System.getProperty("nocturneHwPort")?.let { port -> it.systemProperty("nocturneHwPort", port) }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Offline sky atlas (DSS2 colour HiPS orders 0..4, ~211 MB, 4,092 tiles) packed into the APK's
// assets at build time from a local mirror (pi-tools/hips-mirror), never committed to git.
// Order 5 (~760 MB) stays out of the APK; tools/push_sky_atlas.sh loads it into app storage.
// Without the mirror the APK still builds, just with no packaged atlas (framing falls back).
val skyAtlasSrc: String = providers.gradleProperty("nocturneAtlasDir")
    .getOrElse(System.getProperty("user.home") + "/astro-data/hips/DSSColor")
val skyAtlasAssets = layout.buildDirectory.dir("generated/skyAtlasAssets")
val syncSkyAtlasAssets = tasks.register<Sync>("syncSkyAtlasAssets") {
    from(skyAtlasSrc) {
        include((0..4).map { "Norder$it/**/Npix*.jpg" })
        into("hips/DSSColor")
    }
    into(skyAtlasAssets)
    doFirst {
        if (!file(skyAtlasSrc).isDirectory) logger.warn("No sky atlas at $skyAtlasSrc — APK built without it")
    }
}
android.sourceSets["main"].assets.srcDir(skyAtlasAssets)
tasks.named("preBuild") { dependsOn(syncSkyAtlasAssets) }
