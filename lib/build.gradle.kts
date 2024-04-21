plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.jvm)
    `java-library`
    `maven-publish`
}

group = "io.github.joemama"
version = "1.0.0-alpha"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
