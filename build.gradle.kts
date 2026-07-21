plugins {
    java
}

group = "dev.epicc"
version = "1.0.0-SNAPSHOT"
description = "Mario Party-style multiplayer plugin for AdvancedSlimePaper"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "enginehub"
        url = uri("https://maven.enginehub.org/repo/")
    }
    maven {
        name = "infernalsuite-snapshots"
        url = uri("https://repo.infernalsuite.com/repository/maven-snapshots/")
    }
    maven {
        name = "infernalsuite-releases"
        url = uri("https://repo.infernalsuite.com/repository/maven-releases/")
    }
    maven {
        name = "codemc-releases"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    // Provided by AdvancedSlimePaper server fork
    compileOnly("com.infernalsuite.asp:api:4.2.0-SNAPSHOT")
    // Reference loaders are not on the server — must be shaded into this plugin
    implementation("com.infernalsuite.asp:file-loader:4.2.0-SNAPSHOT")
    // Soft-depend at runtime (plugin.yml); not shaded
    compileOnly("com.github.retrooper:packetevents-spigot:2.7.0")
    // Non-transitive: WorldEdit strict Guava/Gson constraints conflict with Paper 26
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.14") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.14") {
        isTransitive = false
    }
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
        // Bundle dice resource pack for local hosting / first-run extract
        from("resourcepack") {
            into("resourcepack")
            exclude("README.md")
        }
    }

    jar {
        archiveBaseName.set("McParty")
        // Shade file-loader (and any runtime deps) into the plugin jar
        from({
            configurations.runtimeClasspath.get()
                .filter { it.name.endsWith(".jar") }
                .map { zipTree(it) }
        })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
