plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include(":virtual-core", ":app", ":launcher", ":xposed-core", ":shared-helper")
rootProject.name = "VirtualXposed"