plugins {
    id("com.android.library")
}

android {
    namespace = "com.virtualxposed.xposed"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments.addAll(
                    listOf(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DPROJECT_ROOT=${rootDir.absolutePath}",
                        // Enforce 16 KB page size alignment for Android 15+ compatibility
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    )
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.31.6"
            ndkVersion = "29.0.14206865"
        }
    }
}

dependencies {
    implementation(project(":shared-helper"))

    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    api("me.weishu.exposed:exposed-xposedapi:0.4.6")

    implementation("io.github.vvb2060.ndk:dobby:1.2")
    implementation("androidx.tracing:tracing-ktx:2.0.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
}