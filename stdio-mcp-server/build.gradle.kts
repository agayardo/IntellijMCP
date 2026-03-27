plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("application")
}

application {
    mainClass.set("ca.artemgm.mcpserver.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":protocol-shared"))
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
