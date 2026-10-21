# Specification v0.5 — implementation baseline

Playroom Projects / PreMapped: Minecraft 1.20.1 Fabric, Java 17, Architectury.
This preserves the agreed design. Planned requirements are not claims of implementation.

## Foundation

Implemented code covers new/existing Singleplayer entry points, playerless bootstrap
interception, a fixed rectangular Overworld job, serial native FULL generation/save,
stored verification, manual Resume, checksummed checkpoints, explicit worldgen-change
approval, Pause and Cancel retaining chunks. Core state/persistence tests are provided.
Real-game acceptance remains a gate; see VALIDATION.md and TESTING.md.

## Agreed remaining behavior

| Feature | Requirement |
| --- | --- |
| Map zoom | Default 5,000; min 1,000; max 20,000 blocks across window width, not radius |
| Map resources | Adaptive tile resolution with bounded memory/processing |
| Preview sources | New/existing worlds; calculated data gradually replaced by confirmed data |
| Preview cache | Retain on closing/reopening with identical inputs |
| Invalidation | Immediately invalidate old seed/worldgen work and cache; never delete world data |
| Terrain/topography | Natural color layers with height shading |
| Satellite | Detailed terrain/color layer, not individual block-texture rendering |
| Caves | Temporary computation before pregeneration; distinguish calculated/confirmed |
| Cave Y range | Two endpoints; default 0–20; clamp to real dimension minY/height |
| Structures | Predictions where supported; verify from actual generated structure data |
| Biome palettes | Global with per-world overrides and editable HEX colors |
| Generation strategies | Center-Out, Region-Optimized, Linear Scan; benchmark before ranking |
| Region shapes | Extend beyond foundation rectangle to agreed selectable shapes |
| Dimensions | Per-dimension regions/priorities; Overworld, Nether, End defaults; user reorder |
| Mixed scheduling | Sequential stages with parallel groups, e.g. Overworld then Nether+End |
| Errors | Stop/pause critical failures; persist cause; red failed markers; never false 100% |
| Default closing | Pause/save; leaving world for menus stops/saves the job |
| Background play | Optional, only the same world and active integrated server |
| Performance | Adaptive FPS baseline/user target plus MSPT/TPS, CPU, heap/GC, measurable disk pressure |
| Stability | Smoothed measurements and hysteresis; responsiveness before throughput during play |
| DH LOD | Independent LOD job/progress; LOD failure never regenerates completed Minecraft chunks |
| DH Resume | Resume LOD separately using supported installed-version APIs; report unsupported APIs |
| UI | Vanilla works independently; FancyMenu is optional |
| Dedicated servers | After Singleplayer completion |
| Other loaders | Separate future Forge/NeoForge implementation with portable common code |

Preview and adaptive controls are later milestones. Temporary preview contexts must
not contaminate real-world storage. FPS is a regulation target, not a guarantee.

## Reference environment

[Playroom Project v1.4.2.32-alpha](https://modrinth.com/modpack/playroom-project/version/v1.4.2.32-alpha),
with user-specified Streams Reflowing `2.15.2` and Hybrid Aquatic
`mc1.20.1-1.1.3-fabric`. The ZIP did not include this manifest/mod collection;
these are targets, not a verified tested mod list. Test C2ME, DH/DH SeedGen,
Tectonic, Better End/Nether and all actual installed worldgen integrations explicitly.

## Ordered milestones

| Version | Scope | Gate |
| --- | --- | --- |
| 0.1 | Bootstrap, FULL chunks, save/recovery | 256 requested chunks, zero players, normal Play afterwards |
| 0.2 | Dimensions, shapes, priorities, three strategies | Exact membership and safe mixed scheduling |
| 0.3 | Tiles, zoom, biomes/height, retained cache | Correct invalidation and memory bounds |
| 0.4 | Satellite, Caves, structures | Modded accuracy and confidence checks |
| 0.5 | Adaptive controls, same-world gameplay | Measured FPS/MSPT stability |
| 0.6 | Separate DH LOD jobs | LOD-only recovery without chunk regeneration |
| 0.7 | Full UI/FancyMenu/palettes | Vanilla accessibility and optional integration |
| 0.8 | Actual reference modpack | Recorded complete-pack results |
| 1.0 | Stable Fabric | All supported acceptance gates passed |

Forge/NeoForge, dedicated servers and measured GPU acceleration remain later work.
