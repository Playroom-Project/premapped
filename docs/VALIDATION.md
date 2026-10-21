# Validation record — 0.1.0-alpha.1

Date: 2026-10-21. Target: Minecraft 1.20.1, Yarn 1.20.1+build.10, Java 17.

## Completed checks

- Java 17 compilation of the loader-independent core and JUnit tests.
- **19/19 JUnit tests passed**, using independent native-storage fakes.
- Common code contains no Minecraft/Fabric/Mixin imports.
- Resource JSON parses correctly; English/Norwegian locale keys match.
- Template example mixins and Hello Fabric initialization were removed.
- Original supplied icon and CC0 license are byte-for-byte unchanged.
- The exact mapped Minecraft 1.20.1 bytecode contains the Create World tab-builder
  call and the pre-local-login `ServerNetworkIo.bindLocal` call used by the mixins.
- Integrated-server bytecode continues normal ticks without a network handler,
  supporting the design assumption; this is static inspection, not a live game test.

## Build validation

The following Gradle checks completed successfully using JDK 17.0.20.1 and
Gradle 8.10.2, with the local environment adaptation described below:

| Check | Result |
| --- | --- |
| `:common:test` | 19 tests; zero failures/errors/skips |
| `:common:javadoc` | Passed |
| `:fabric:compileJava` and Mixin annotation processing | Passed |
| `:fabric:javadoc` | Passed |
| `:common:transformProductionFabric` | Passed |
| `:fabric:shadowJar`, `:fabric:remapJar`, `:fabric:build` | Passed |
| Gradle 8.10.2 wrapper generation with SHA-256 | Passed |

The production jar contains both modules, resources and an explicit refmap for
all five mixins. Project class files target Java 17 (major 61); Architectury's
generated platform helper uses an older compatible class format. No Minecraft
classes or Gson implementation are bundled. JAR duplicate-entry/CRC checks and
metadata/version/resource checks passed. The original license/icon were retained.

Pinned build plugins: Architectury Plugin 3.4.162, Architectury Loom 1.7.435,
Shadow 8.1.1. Runtime compile dependencies: Fabric Loader 0.16.10, Fabric API
0.92.2+1.20.1 and Architectury API 9.2.14. Test dependency: JUnit Jupiter 5.10.2.

The restricted validation host blocks Unix-domain sockets. Unmodified Architectury
Loom fails during its optional platform capability probe. For local verification,
a temporary environment-only Java agent makes that probe report **unsupported**;
Gradle's optional instrumentation agent is disabled so the fallback is respected.
This does not enable the blocked feature or change project/mod code. It is not
bundled in the project or required by the project's normal build instructions.
Full unmodified Gradle execution on a normal development machine remains a
separate reproducibility check.

## Not performed here

- GUI launch, actual mixin application and a running zero-player worldgen session.
- Native save/restart/recovery and opening the resulting world in normal Play.
- The actual Playroom Project reference pack and the user-specified mod overrides.
- C2ME/DH compatibility, performance benchmarking or LOD generation.

The artifact is a foundation alpha. Follow TESTING.md before treating the native
bootstrap and save path as accepted. Preview, adaptive gameplay scheduling,
multi-dimension planning, FancyMenu and separate LOD jobs are future milestones,
not completed features of this delivery.
