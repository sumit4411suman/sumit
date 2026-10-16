plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.names26.productchanger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.names26.productchanger"
        minSdk = 29
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildTypes {
        release {
            // No shrinking: the app is tiny and relies on reflection into Samsung framework classes.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = false
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "META-INF/*.kotlin_module")
    }
}

// The Magisk module ZIP is rebuilt from ../magisk-module and embedded as an asset on every build,
// so the APK always ships the module sources that live in this repository.
val moduleSourceDir = rootProject.file("magisk-module")
val moduleAssetDir = layout.buildDirectory.dir("generated/names26-assets")
val packageModule by tasks.registering(Zip::class) {
    from(moduleSourceDir)
    archiveFileName.set("names26_module.zip")
    destinationDirectory.set(moduleAssetDir)
    // Deterministic archive: same inputs -> same bytes -> same SHA-256.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    entryCompression = ZipEntryCompression.DEFLATED
}
android.sourceSets.getByName("main").assets.srcDir(moduleAssetDir)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(packageModule)
}

dependencies {
    // Intentionally no AndroidX / third-party dependencies: only the Android framework and the Kotlin stdlib.
}
