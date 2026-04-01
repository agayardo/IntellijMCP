plugins {
    id("application")
}

application {
    mainClass.set("ca.artemgm.mcpserver.MainKt")
}

dependencies {
    implementation(project(":protocol-shared"))
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}
