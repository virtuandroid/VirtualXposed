plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("plugin.serialization") version "2.3.20"
}

android {
    namespace = "com.virtualxposed.shared"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
}