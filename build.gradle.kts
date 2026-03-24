plugins {
    kotlin("jvm") version "1.9.22"
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.jeff-media.com/public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")

    // Kotlin
    implementation(kotlin("stdlib"))

    // CustomBlockData - PDC for blocks
    implementation("com.jeff-media:custom-block-data:2.2.5")

    // Floodgate API (optional - for Bedrock player detection)
    compileOnly("org.geysermc.floodgate:api:2.2.2-SNAPSHOT")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.9")
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    shadowJar {
        archiveClassifier.set("")

        // Relocate dependencies to avoid conflicts
        relocate("com.jeff_media.customblockdata", "com.example.paperdrawers.lib.customblockdata")
        relocate("kotlin", "com.example.paperdrawers.lib.kotlin")
        relocate("org.jetbrains", "com.example.paperdrawers.lib.jetbrains")
        relocate("org.intellij", "com.example.paperdrawers.lib.intellij")

        // Don't relocate internal classes
        dependencies {
            exclude(dependency("io.papermc.paper:.*"))
        }
    }

    assemble {
        dependsOn(reobfJar)
    }

    build {
        dependsOn(reobfJar)
    }

    runServer {
        minecraftVersion("1.21.1")
    }

    test {
        useJUnitPlatform()
    }
}

kotlin {
    jvmToolchain(21)
}
