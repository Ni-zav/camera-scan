plugins {
    id("com.android.application")
}

android {
    namespace = "dev.nizav.documentscanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.nizav.documentscanner"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.transition:transition:1.7.2")

    implementation("com.google.android.material:material:1.14.0")

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    annotationProcessor("androidx.room:room-compiler:$room")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("org.opencv:opencv:4.14.0")

    // Bundled/offline Latin OCR. The model is packaged with the APK.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
