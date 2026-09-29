import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

val properties = Properties()
val localProp = rootProject.file("local.properties")
if (localProp.exists()) {
    properties.load(localProp.inputStream())
}
val keyFile = file(properties.getProperty("keystore.path") ?: "/tmp/does_not_exist")

android {
    namespace = "io.virtualapp"
    compileSdk = 37

    signingConfigs {
        create("config") {
            keyAlias = properties.getProperty("keystore.alias")
            keyPassword = properties.getProperty("keystore.pwd")
            storeFile = keyFile
            storePassword = properties.getProperty("keystore.alias_pwd")
        }
    }

    defaultConfig {
        applicationId = "io.va.exposed64"
        minSdk = 26
        targetSdk = 37

        ndk {
            // TODO MORE ABI?
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("config")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":lib"))
    implementation(project(":launcher"))
    implementation(project(":exposed-core"))
    implementation(project(":log-client"))

    // Android Lib / Kotlin
    // Note: Pass your 'kotlin_version' via project extra properties or a version catalog if defined
    val kotlinVersion = rootProject.extra["kotlin_version"] as String
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")

    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.preference:preference:1.2.0")
    implementation("com.google.android.material:material:1.9.0")

    // Promise Support
    implementation("org.jdeferred:jdeferred-android-aar:1.2.4")

    // ThirdParty
    implementation("com.jonathanfinerty.once:once:1.3.1")

    val appCenterSdkVersion = "3.0.0"
    implementation("com.microsoft.appcenter:appcenter-analytics:$appCenterSdkVersion")
    implementation("com.microsoft.appcenter:appcenter-crashes:$appCenterSdkVersion")

    implementation("com.kyleduo.switchbutton:library:2.1.0")

    implementation("com.github.AlexLiuSheng:CheckVersionLib:2.2.1") {
        exclude(group = "androidx.appcompat")
    }

    implementation("com.github.medyo:android-about-page:1.2.2")
    implementation("moe.feng:AlipayZeroSdk:1.1")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")


    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose")

    // Android Studio Preview support
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")
    implementation("com.google.accompanist:accompanist-drawablepainter:0.37.3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
}