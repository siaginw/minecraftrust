# ForgeGradle & Build Tooling in 2026

## 1. Executive Summary
- **Historical Baseline Generation:** **ForgeGradle 2.3** (`net.minecraftforge.gradle:ForgeGradle:2.3-SNAPSHOT` / `2.3.4`)
- **Modernized Backport Generation:** **ForgeGradle 3+** (`net.minecraftforge.gradle:ForgeGradle:3.+`)
- **Required JVM Toolchain:** **Java 8 (OpenJDK 1.8.0_504)** exclusively
- **Gradle Version Support:**
  - FG 2.3: Gradle `4.9` to `4.10.3` (Gradle 5+ is unsupported due to internal API removals)
  - FG 3+: Gradle `4.10.3` to `5.6.4`
- **Primary Maven Repository:** `https://maven.minecraftforge.net/` (mandatory HTTPS)

## 2. Historical Context: ForgeGradle 2.3 vs 3.x
Historically, the entirety of the Minecraft 1.12.2 modding ecosystem (2017–2020) was built on **ForgeGradle 2.3**.
- **FG 2.3 Workflow:** Relied on Gradle tasks:
  - `gradle setupDecompWorkspace`: Downloads clean Mojang server/client jars, merges them, applies FernFlower decompilation, applies Forge patches from `patches/minecraft/`, and generates an SRG-mapped development workspace jar.
  - `gradle build`: Compiles mod against mapped MCP names and runs `reobfJar` to translate method/field references back to SRG bytecode identifiers for distribution.
- **FG 3+ Backport:** In 2020 (starting with build `14.23.5.2852` / commit `b8.99.5033`), Forge backported `userdev` support using ForgeGradle 3. This eliminated the monolithic decompilation step, using dynamic runtime remapping and MCPConfig artifacts instead.

## 3. Reproducibility & Resolution Issues in 2026

### A. Deprecated Maven Endpoint (`files.minecraftforge.net`)
- **Failure:** Older `build.gradle` scripts declare `http://files.minecraftforge.net/maven`. Modern Gradle/Java environments reject plain HTTP (cleartext traffic policy), and the endpoint redirects with HTTP 301.
- **Fix:** Update repository declarations to:
  ```groovy
  maven { url = 'https://maven.minecraftforge.net/' }
  mavenCentral()
  ```

### B. Sunset of JCenter (Bintray)
- **Failure:** FG 2.3 internally attempted to fetch certain dependencies from `jcenter.bintray.com`. Since JCenter sunset in 2021, these requests can fail with HTTP 502/403 or hang.
- **Fix:** Declare `mavenCentral()` explicitly before any fallback repositories.

### C. Java 9+ JVM Incompatibility
- **Failure:** Running FG 2.3 with Java 11/17/21/25 fails immediately during task execution with:
  `java.lang.IllegalArgumentException: Unsupported class file major version`
  or `java.lang.reflect.InaccessibleObjectException: Unable to make protected void java.net.URLClassLoader.addURL(java.net.URL) accessible`.
- **Fix:** Always invoke Gradle via the project-local Java 8 wrapper:
  ```bash
  ./tools/java8/with-java8.sh ./gradlew setupDecompWorkspace
  ```

### D. Missing SHA1 on Ancient Dynamic Snapshots
- **Failure:** `ForgeGradle:2.3-SNAPSHOT` resolves dynamic metadata. If upstream metadata changes, builds become non-deterministic.
- **Fix:** Pin to the concrete artifact release `net.minecraftforge.gradle:ForgeGradle:2.3.4` or maintain a cached dependency verification lockfile.

## 4. Operational Strategy for Reference Environment
1. For **reference Forge source study**, we use the checked-out `1.12.x` Forge source tree and its mapped patches directly.
2. For **decompiling reference Minecraft 1.12.2 source**, prefer standalone MCP tools or an isolated FG 2.3/3.x userdev workspace driven by `./tools/java8/with-java8.sh`.
3. Never alter system-wide `JAVA_HOME`.
