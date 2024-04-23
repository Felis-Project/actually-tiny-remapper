plugins {
    alias(libs.plugins.jvm)
    `java-library`
    `maven-publish`
}

group = "io.github.joemama"
version = "1.0.1-alpha"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.asm)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
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
