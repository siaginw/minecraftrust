# Java 8 Toolchain Documentation

## 1. Overview & Distribution
Minecraft 1.12.2 and Forge 14.23.5.x are pinned to Java 8 (JVM target 1.8). Modern Java versions (such as Java 21 and Java 25 present on this machine) cause bytecode incompatibility, security manager deprecations, LaunchWrapper classloader failures, and ForgeGradle 2.3 build failures.

To preserve modern tooling for Hermes while ensuring 100% compatibility for reference Minecraft/Forge code, Java 8 is installed **side-by-side**. It is NOT set as the machine-wide default.

- **Distribution:** Eclipse Temurin JDK with Hotspot 8 (Adoptium)
- **Exact Version:** `8.0.504.1` (build `1.8.0_504-b01`)
- **Architecture:** `x64` Windows
- **Installation Path:** `C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot`
- **POSIX/MSYS Path:** `/c/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot`

## 2. Installation Method
Installed via Windows Package Manager (`winget`):
```powershell
winget install --id EclipseAdoptium.Temurin.8.JDK -e --silent --accept-package-agreements --accept-source-agreements
```
Installer artifact: `OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.msi`.

## 3. Project Toolchain Integration
Project scripts access Java 8 through dedicated wrappers under `tools/java8/`. These wrappers inspect `JAVA8_HOME`, falling back to the canonical Temurin 8 installation path.

### Available Helpers
- **PowerShell:**
  - `tools/java8/java8.ps1`: Invokes `java.exe` (1.8.0_504)
  - `tools/java8/javac8.ps1`: Invokes `javac.exe` (1.8.0_504)
  - `tools/java8/with-java8.ps1 <command> [args...]`: Executes command with `JAVA_HOME` pointing to Java 8
- **Bash / MSYS:**
  - `tools/java8/java8.sh`: Invokes `java` (1.8.0_504)
  - `tools/java8/javac8.sh`: Invokes `javac` (1.8.0_504)
  - `tools/java8/with-java8.sh <command> [args...]`: Sets `JAVA_HOME` in subshell and executes command

### Verification Output
```text
$ ./tools/java8/java8.sh -version
openjdk version "1.8.0_504"
OpenJDK Runtime Environment (Temurin)(build 1.8.0_504-b01)
OpenJDK 64-Bit Server VM (Temurin)(build 25.504-b01, mixed mode)

$ ./tools/java8/javac8.sh -version
javac 1.8.0_504
```

## 4. Operation Scope Matrix

| Operation Category | Required Toolchain | Reason |
|---|---|---|
| **Minecraft 1.12.2 Server Execution** | **Java 8 (Temurin 8)** | LaunchWrapper / ASM transformers depend on Java 8 ClassLoader internals |
| **Forge 14.23.5.x Build / Workspace** | **Java 8 (Temurin 8)** | ForgeGradle 2.3 hard-coded assumptions and deprecated ASM versions |
| **MCP Decompilation / Recompilation** | **Java 8 (Temurin 8)** | Target bytecode 1.8 compatibility |
| **Parity Benchmark Server Runs** | **Java 8 (Temurin 8)** | Ensures accurate baseline match with historical Forge performance |
| **Rust Crates / Cargo** | **Rust 1.94.0** | Independent of Java version |
| **CodeGraph / Node CLI** | **Node.js v22.23.2** | Independent of Java version |
| **Hermes Agent Runtime** | **Python 3.11 / System** | Independent of Java version |
