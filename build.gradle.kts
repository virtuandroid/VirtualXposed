plugins {
    java
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    extra["kotlin_version"] = "2.4.20"

    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // We recommend changing it to the latest version from our changelog:
        // https://docs.fabric.io/android/changelog.html#fabric-gradle-plugin
        classpath("com.android.tools.build:gradle:9.3.1")
        classpath("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.20")
    }
}



allprojects {
    tasks.withType<JavaCompile>().configureEach {
        javaCompiler.set(
            project.javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        )
    }

    repositories {
        mavenLocal()
        maven {
            url = uri("https://jitpack.io")
        }
        google()
        mavenCentral()
        maven {
            url = uri("$rootDir/XposedBridge/build/repo")
        }
        maven { url = uri("https://artifactory.appodeal.com/appodeal") }
    }
}