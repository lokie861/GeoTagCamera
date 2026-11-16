plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google Maps API key: -PMAPS_API_KEY, local.properties, or MAPS_API_KEY env var (GitHub secret)
val mapsKey: String = run {
    val lp = java.util.Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { lp.load(it) }
    (project.findProperty("MAPS_API_KEY") as String?)
        ?: lp.getProperty("MAPS_API_KEY")
        ?: System.getenv("MAPS_API_KEY")
        ?: ""
}

android {
    namespace = "com.geotag.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geotag.camera"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
