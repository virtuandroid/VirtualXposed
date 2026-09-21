plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("plugin.serialization") version "2.3.20"
}

android {
    namespace = "com.lody.virtual"
    compileSdk = 35
    version = "1.0"

    defaultConfig {
        minSdk = 21

        /**
         * We use buildConfig to allow attack demonstrations without code changes
         *
         * `enableStrongIOSandbox` enables additional IO restictions which prevents attacks
         * using /proc/ or File Providers
         */
        buildConfigField("boolean", "enableStrongIOSandbox", "true")

        externalNativeBuild {
            ndkBuild {
                abiFilters("arm64-v8a", "x86_64")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        ndkBuild {
            path(file("src/main/jni/Android.mk"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        // IJobService need NewApi
        warning.addAll(listOf("NewApi", "OnClick"))
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":exposed-core"))
    implementation(project(":restrictionbypass"))
    implementation(project(":log-client"))
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("com.getkeepsafe.relinker:relinker:1.4.4")
}

repositories {
    maven {
        url = uri("https://jitpack.io")
    }
}