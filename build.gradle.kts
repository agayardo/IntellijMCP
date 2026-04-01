import java.security.MessageDigest

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ca.artemgm"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("JUnit")
        bundledPlugin("Coverage")
        bundledPlugin("com.intellij.gradle")
    }

    implementation(project(":protocol-shared"))
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
    implementation(kotlin("reflect"))
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // do not remove: junit4 is needed by intellij junit platform
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    processResources {
        dependsOn(":stdio-mcp-server:distZip")
        from(project(":stdio-mcp-server").layout.buildDirectory.dir("distributions")) {
            include("*.zip")
            into("bridge")
            rename { "stdio-mcp-server.zip" }
        }
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<Test> {
        useJUnitPlatform()
    }
}

val generateBridgeVersion by tasks.registering {
    dependsOn(":stdio-mcp-server:distZip")
    val distDir = project(":stdio-mcp-server").layout.buildDirectory.dir("distributions")
    val outputDir = layout.buildDirectory.dir("generated/bridge-version")
    outputs.dir(outputDir)
    doLast {
        val dir = distDir.get().asFile
        val zipFiles = dir.listFiles { f -> f.extension == "zip" }
            ?: error("Distribution directory not found or not readable: $dir")
        val zipFile = zipFiles.singleOrNull()
            ?: error("Expected exactly one zip in $dir, found ${zipFiles.size}")
        val hash = MessageDigest.getInstance("SHA-256").digest(zipFile.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        outputDir.get().asFile.apply { mkdirs() }.resolve("bridge-version.txt").writeText(hash)
    }
}

sourceSets.main {
    resources.srcDir(generateBridgeVersion)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
