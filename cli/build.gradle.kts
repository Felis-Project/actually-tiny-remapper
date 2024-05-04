plugins {
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
    implementation(project(":lib"))
    implementation("com.github.ajalt.clikt:clikt:4.3.0")
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

tasks.jar {
    dependsOn(":lib:jar")
    manifest.attributes("Main-Class" to "io.github.joemama.atr.MainKt")
    from(*configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }.toTypedArray())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                artifactId = "actually-tiny-remapper-cli"
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
