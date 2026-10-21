# PreMapped

## Setup
### Kotlin
```
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```
```
dependencies {
    // Replace VERSION with a Git-tag/release (eks. 1.0.0) or a commit-hash
    implementation("com.github.Playroom-Project:premapped:VERSION")
}
```
### Groovy
```
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```
```
dependencies {
    // Replace VERSION with a Git-tag/release (eks. 1.0.0) or a commit-hash
    implementation 'com.github.Playroom-Project:premapped:VERSION'
}
```
### XML
```
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```
```
<dependency>
    <groupId>com.github.Playroom-Project</groupId>
    <artifactId>premapped</artifactId>
    <version>VERSION</version>
</dependency>
```
Replace VERSION with a Git-tag/release (eks. 1.0.0) or a commit-hash

## License
This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.



# PreMapped — Worldgen Foundation

Minecraft **1.20.1**, **Fabric**, **Java 17**. Version **0.1.0-alpha.1**.

Built from TLiG98's supplied ZIP, retaining its namespace, mod ID, icon, authorship,
CC0 license. The wrapper is refreshed for Gradle 8.10.2. The template is reorganized into Architectury `common`
and `fabric` modules. This is an experimental foundation, not a complete release.

## Included

- A fourth PreMapped tab in Create World and an existing-world header button.
- Vanilla integrated-server bootstrap with local player login intercepted.
- A fixed Overworld rectangle: 16 × 16 chunks, chunk coordinates `[-8, 8)` on both axes.
- Explicit Start/Resume, Pause, Cancel and Save/Return controls.
- One requested chunk at a time through the active modded generation pipeline.
- Stored FULL verification, compact checksummed checkpoints and manual recovery.
- Conservative worldgen fingerprints and explicit approval of changed inputs.
- English Javadoc/comments and English/Norwegian UI strings.

See [VALIDATION.md](docs/VALIDATION.md) for checks actually completed. Compilation
and core tests do not prove real-game bootstrap or modpack compatibility.

## Build and launch

Install **JDK 17** and select it as both the Gradle JVM and Java toolchain.
The first build downloads Gradle, Minecraft and the declared development dependencies.

```bash
chmod +x gradlew
./gradlew :common:test :common:javadoc :fabric:javadoc :fabric:build
./gradlew :fabric:runClient
```

On Windows use `gradlew.bat` with the same task names. Install only the production
`fabric/build/libs/premapped-fabric-0.1.0-alpha.1.jar`, alongside Fabric API and
Architectury API 9.2.14 for Fabric 1.20.1. The loader minimum is 0.16.10. The common,
dev, dev-shadow and sources jars are not installable production mods. A copy of
the built foundation alpha is included under `dist/` in the delivery archive.

## First game check

1. Create a disposable world with seed `123456789` and your normal world settings.
2. Open the PreMapped tab and click **Create and open PreMapped**. The ordinary
   bottom Create New World button still starts normal Play.
3. Wait for initialization, then press **Start / Resume**.
4. Pause, save/return, and restart Minecraft.
5. Select the same world in Singleplayer and click its header **PreMapped** button.
6. Resume manually. Stored FULL chunks appear as Existing and skip generation.
7. Finish all 256 requested chunks, save/return, then open the world normally.

Vanilla spawn/dependency generation can overlap this origin-centered area. The
requested total stays 256. See [TESTING.md](docs/TESTING.md) for a developer check
outside spawn that exercises newly generated chunks as well as existing data.

## Data and recovery

The world's `premapped/generation.json` references one immutable
`journal-<checkpoint UUID>.bin`. PreMapped does not write region files directly.
Resume rechecks native stored data, including completed journal entries. Interrupted
jobs require manual Resume. Cancel removes the job definition and retains chunks.
Failed verification or IO stops progress rather than claiming completion.

## Project guide

| Location | Responsibility |
| --- | --- |
| `common/.../generation` | Bounded planning and job state machine |
| `common/.../persistence` | Compact journal and transactional checkpoints |
| `common/.../api` | Loader-independent worldgen boundary |
| `fabric/.../fabric` | Native generation, saves, fingerprints |
| `fabric/.../client` | Create World tab and monitor |
| `fabric/.../mixin` | Small version-specific integration points |
| [SPECIFICATION.md](docs/SPECIFICATION.md) | Agreed requirements and roadmap |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Ownership, threads, durability and porting |
| [TESTING.md](docs/TESTING.md) | Automated and manual acceptance protocol |

Prove native bootstrap and recovery before adding dimension scheduling, preview,
adaptive gameplay controls and separate DH LOD jobs. Forge/NeoForge and dedicated
servers remain later work. License: CC0-1.0 as supplied; see [LICENSE](LICENSE).
