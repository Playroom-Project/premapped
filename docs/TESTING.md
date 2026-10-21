# Test protocol

## Automated

Run `./gradlew :common:test :common:javadoc :fabric:javadoc :fabric:build` on JDK 17. JUnit uses an
independent fake native-storage boundary. Tests cover bounds, fingerprints, journal
round trips, interruption/corruption, stale completion claims, manual restart,
worldgen approval, in-flight pause/cancel, failure without completion and persistence
failure before dispatch. Compilation uses pinned Yarn 1.20.1+build.10 mappings.
Core tests/compilation do not prove mixin application or game/modpack behavior.

## A — playerless bootstrap on a disposable Fabric world

Use Minecraft 1.20.1, Fabric, Fabric API, Architectury API 9.2.14 and this mod.
Record exact versions/hardware. Use seed `123456789`.

- [ ] Create World's PreMapped tab and normal tabs/settings all work.
- [ ] PreMapped's create action reaches the monitor without first-person Play.
- [ ] Inspect server diagnostics/debugger: player count stays zero.
- [ ] Start is explicit; exactly 256 planned chunks are counted.
- [ ] Progress requires stored FULL verification; dependency chunks do not inflate it.
- [ ] Existing/Newly generated counters distinguish reads from generation requests.
- [ ] Pause drains pending work and becomes PAUSED without new dispatch.
- [ ] Save/Return releases the world lock and reports no shutdown errors.
- [ ] Restart Minecraft; select the same world and click header PreMapped.
- [ ] No automatic Resume; explicit Resume rechecks data and skips stored FULL chunks.
- [ ] All 256 verify FULL before COMPLETED; normal Play works afterwards.

Spawn preparation may already generate much of the origin-centered area. To exercise
missing chunks, developers should temporarily change `ChunkPlan.prototype()` to an
offset rectangle outside spawn, repeat, then revert the test-only coordinates.
An all-existing run is not a new-generation stress test.

## B — interruption and failures

- [ ] Crash/terminate during a disposable job; reopening requires manual Resume and disk checks.
- [ ] Interrupt between journal creation and JSON replacement; old committed snapshot still loads.
- [ ] Corrupt a journal copy; initialization stops, no dispatch, Save/Return remains available.
- [ ] Change datapack/config between sessions; Resume requires approval; declining starts no work.
- [ ] Change inputs while monitor is open; a fresh Resume check detects them.
- [ ] Cancel during generation; current work drains, job definition disappears, chunks remain.
- [ ] Trigger a reproducible native generation/save error; FAILED with reason, never false 100%.
- [ ] Cancel a load/backup warning, then normal Play; stale requests must not intercept it.

## C — actual reference modpack

Repeat A/B with the exact Playroom Project manifest and supplied overrides. Record
versions, enabled datapacks, dimensions/heights, seed, hashes and logs. Inspect mods
requiring player-join events, spawn initialization, C2ME async work, structure/river
planners and native shutdown. DH LOD completion is outside this milestone.

## Performance baseline

This reference version flushes each generated requested chunk. Measure correctness
first; record wall time, chunks/s, peak heap/GC, tick duration and IO before designing
batched saves or parallelism. Compare identical seeds, configs, regions and durability.
