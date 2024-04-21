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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                artifactId = "actually-tiny-remapper"
            }
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "Repsy"
            url = uri("https://repo.repsy.io/mvn/0xjoemama/public")
            credentials {
                username = System.getenv("REPSY_USERNAME")
                password = System.getenv("REPSY_PASSWORD")
            }
        }
    }
}
