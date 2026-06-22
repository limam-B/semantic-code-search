plugins {
    // Lets Gradle auto-download the JDK 21 toolchain if it's not already on the machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "semantic-code-search"
