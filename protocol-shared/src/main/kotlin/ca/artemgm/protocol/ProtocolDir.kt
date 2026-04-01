package ca.artemgm.protocol

import java.nio.file.Path

val PROTOCOL_DIR: Path get() = Path.of(System.getProperty("user.home"), ".intellij-dev-mcp")
