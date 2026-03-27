# Requirements Document

## Introduction

The IntelliJ MCP plugin currently requires users to manually install the stdio-mcp-server bridge binary via `install.sh` or `release.sh`. This feature automates that process: when the IDE starts and the plugin initializes, it detects whether the bridge is missing or outdated and unpacks a bundled copy into `~/.intellij-dev-mcp/`. This eliminates the manual installation step and ensures the bridge version always matches the plugin version.

## Glossary

- **Plugin**: The IntelliJ IDEA plugin (DevelopmentMcp) that exposes MCP tools to AI assistants via a file-based protocol
- **Bridge**: The stdio-mcp-server standalone JVM application that translates MCP stdio transport to the file-based protocol used by the Plugin
- **Bridge_Distribution**: A zip archive containing the Bridge executable script (`bin/stdio-mcp-server`) and its dependency JARs (`lib/`)
- **Install_Directory**: The directory `~/.intellij-dev-mcp/` where the Bridge is installed, containing `bin/` and `lib/` subdirectories
- **Bridge_Installer**: The Plugin component responsible for detecting, unpacking, and installing the Bridge_Distribution at IDE startup
- **Version_Marker**: A file (`~/.intellij-dev-mcp/bridge.version`) that records the version string of the currently installed Bridge
- **Startup_Activity**: The `postStartupActivity` extension point that triggers Plugin initialization when a project opens

## Requirements

### Requirement 1: Bundle Bridge Distribution in Plugin

**User Story:** As a plugin developer, I want the Bridge_Distribution to be bundled inside the Plugin archive, so that the Bridge can be installed without downloading external artifacts.

#### Acceptance Criteria

1. THE Plugin build SHALL include the Bridge_Distribution zip as a resource inside the Plugin archive
2. WHEN the Plugin archive is built, THE build system SHALL produce the Bridge_Distribution before packaging the Plugin
3. THE Bridge_Distribution resource SHALL contain the `bin/` and `lib/` directories required to run the Bridge

### Requirement 2: Detect Missing Bridge at Startup

**User Story:** As a user, I want the Plugin to detect when the Bridge is not installed, so that it can be set up automatically without manual intervention.

#### Acceptance Criteria

1. WHEN the Startup_Activity executes and the Install_Directory does not contain `bin/stdio-mcp-server`, THE Bridge_Installer SHALL identify the Bridge as missing
2. WHEN the Startup_Activity executes and the Version_Marker file does not exist, THE Bridge_Installer SHALL identify the Bridge as missing
3. WHEN the Bridge is identified as missing, THE Bridge_Installer SHALL proceed to install the Bridge from the bundled Bridge_Distribution

### Requirement 3: Detect Outdated Bridge at Startup

**User Story:** As a user, I want the Plugin to detect when my installed Bridge version does not match the Plugin version, so that the Bridge is kept in sync automatically.

#### Acceptance Criteria

1. WHEN the Startup_Activity executes and the Version_Marker contains a version string that differs from the Plugin version, THE Bridge_Installer SHALL identify the Bridge as outdated
2. WHEN the Bridge is identified as outdated, THE Bridge_Installer SHALL proceed to install the Bridge from the bundled Bridge_Distribution, replacing the existing installation

### Requirement 4: Unpack and Install Bridge

**User Story:** As a user, I want the Bridge to be unpacked and installed into the correct location automatically, so that MCP communication works without manual setup.

#### Acceptance Criteria

1. WHEN the Bridge_Installer proceeds to install, THE Bridge_Installer SHALL extract the bundled Bridge_Distribution into the Install_Directory
2. WHEN extraction completes, THE Bridge_Installer SHALL place the Bridge executable in `bin/stdio-mcp-server` within the Install_Directory
3. WHEN extraction completes, THE Bridge_Installer SHALL place all dependency JARs in `lib/` within the Install_Directory
4. WHEN extraction completes on a non-Windows operating system, THE Bridge_Installer SHALL set the executable permission on `bin/stdio-mcp-server`
5. WHEN installation succeeds, THE Bridge_Installer SHALL write the current Plugin version string to the Version_Marker file
6. WHEN installation succeeds, THE Bridge_Installer SHALL log a message indicating the Bridge version that was installed

### Requirement 5: Handle Installation Errors

**User Story:** As a user, I want the Plugin to handle installation failures gracefully, so that a broken Bridge install does not prevent the IDE from functioning.

#### Acceptance Criteria

1. IF the Bridge_Distribution resource is not found inside the Plugin archive, THEN THE Bridge_Installer SHALL log a warning and skip installation
2. IF extraction of the Bridge_Distribution fails due to an I/O error, THEN THE Bridge_Installer SHALL log the error details and skip installation
3. IF installation fails, THEN THE Bridge_Installer SHALL leave the Install_Directory in its previous state or clean up partial files
4. IF installation fails, THEN THE Plugin SHALL continue its normal startup sequence without the Bridge

### Requirement 6: Non-Blocking Startup

**User Story:** As a user, I want the Bridge installation to not delay IDE startup noticeably, so that my development workflow is not interrupted.

#### Acceptance Criteria

1. WHEN the Bridge is already installed and up-to-date, THE Bridge_Installer SHALL complete the version check without performing extraction
2. THE Bridge_Installer SHALL perform installation asynchronously so that the Startup_Activity does not block project opening
3. WHEN installation is in progress, THE Plugin SHALL proceed with its normal initialization of the file-based protocol

### Requirement 7: Skip Installation When Bridge Is Current

**User Story:** As a user, I want the Plugin to skip installation when the Bridge is already up-to-date, so that startup is fast on subsequent launches.

#### Acceptance Criteria

1. WHEN the Startup_Activity executes and the Version_Marker contains a version string equal to the Plugin version, THE Bridge_Installer SHALL skip extraction and installation
2. WHEN installation is skipped, THE Bridge_Installer SHALL log a debug-level message confirming the Bridge is current
