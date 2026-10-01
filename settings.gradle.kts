plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include(":virtual-core", ":app", ":launcher", ":xposed-core", ":log-client")
rootProject.name = "VirtualXposed"