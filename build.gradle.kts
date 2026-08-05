import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.9.1")
    }
}

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
    // Embedded persistent storage for parkour records
    implementation("org.xerial:sqlite-jdbc:3.51.1.1")
    // Soft-depend at runtime (plugin.yml); not shaded
    compileOnly("com.github.retrooper:packetevents-spigot:2.7.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
        // Unoptimized fat jar; ProGuard produces the deploy artifact
        archiveClassifier.set("full")
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

    test {
        useJUnitPlatform()
    }
}

// Shrink + optimize the shaded jar (Guardsquare ProGuard free edition)
val proguardTask = tasks.register<ProGuardTask>("proguard") {
    group = "build"
    description = "Shrink and optimize the shaded McParty jar with ProGuard"
    dependsOn(tasks.jar)

    configuration(files("proguard-rules.pro"))

    injars(tasks.jar.flatMap { it.archiveFile })
    outjars(layout.buildDirectory.file("libs/McParty-${project.version}.jar"))

    // Server-provided / soft-depend APIs (not in the fat jar). Prefer compileClasspath
    // minus runtimeClasspath so we do not list the shaded file-loader twice.
    val libraryJars = configurations.compileClasspath.get().files
        .filter { it.extension == "jar" }
        .filter { jar -> jar !in configurations.runtimeClasspath.get().files }
    libraryjars(files(libraryJars))

    // JDK modules referenced by the plugin / shaded deps
    val javaHome = System.getProperty("java.home")
    val jmodFilter = mapOf(
        "jarfilter" to "!**.jar",
        "filter" to "!module-info.class",
    )
    listOf(
        "java.base",
        "java.logging",
        "java.xml",
        "java.desktop",
        "java.management",
        "java.naming",
        "java.sql",
        "jdk.httpserver",
    ).forEach { module ->
        val jmod = file("$javaHome/jmods/$module.jmod")
        if (jmod.exists()) {
            // Named filters first (Groovy-style API on ProGuardTask)
            libraryjars(jmodFilter, jmod)
        }
    }

    printmapping(layout.buildDirectory.file("proguard/mapping.txt"))
    printseeds(layout.buildDirectory.file("proguard/seeds.txt"))
    printusage(layout.buildDirectory.file("proguard/usage.txt"))
}

tasks.assemble {
    dependsOn(proguardTask)
}

// Default package target: optimized jar (same as assemble)
tasks.register("packagePlugin") {
    group = "build"
    description = "Build the ProGuard-optimized McParty plugin jar"
    dependsOn(proguardTask)
}
