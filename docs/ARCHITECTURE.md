# Architecture

## Module boundary

`common` is Java 17 with no Fabric, Minecraft client, Mixin or Architectury API
imports. Gson is its only runtime library and is also supplied by Minecraft.
Architectury transforms/packages the common code into the Fabric production jar.
`WorldgenPlatformBridge` is the porting contract: query stored FULL status, generate
and save, release owned tickets. Future loaders must implement this contract and
their own lifecycle/bootstrap integration; Architectury does not automate porting.

## Ownership and threading

Minecraft owns the integrated server, world lock, settings, registries, datapacks,
chunk pipeline and storage. No second server is started. Create World invokes
vanilla `createLevel`; existing-world loading uses `IntegratedServerLoader.start`.
An exact-directory, one-shot request allows the client mixin to intercept local
network binding after initialization. Ordinary Play passes through unchanged.

The manager, journal and bridge are server-thread confined. GUI commands are
submitted there. Async IO callbacks marshal native calls back to the server thread.
Futures are polled only after `isDone`; they are never joined while waiting for
worldgen. The renderer reads immutable snapshots through a volatile field.
One requested chunk is outstanding at a time. Minecraft/C2ME may perform their
own internal neighbor/dependency work.

## Generation transaction

1. Query vanilla NBT storage for the requested chunk and verify coordinates/FULL.
2. Skip the generation path if already stored FULL.
3. Otherwise add an owned level-33 FULL ticket and request
   `getChunkFutureSyncOnMainThread(..., FULL, true)`.
4. Return to the server thread, require FULL, mark dirty and invoke vanilla save/flush.
5. Read stored NBT again; require FULL and matching xPos/zPos.
6. Release the ticket, advance progress and checkpoint periodically.

The per-chunk flush is deliberately conservative and expensive. Batched saves
need a separate durability design. Dependency chunks outside the rectangle never
inflate progress. PreMapped skips rewriting stored FULL chunks through its own
generation path; vanilla spawn preparation/ticking or other mods can still save them.

## State and recovery

CREATED requires explicit Start. RUNNING can become PAUSING, COMPLETED or FAILED.
PAUSING drains one operation, then becomes PAUSED or CANCELLED. PAUSED/FAILED can
resume explicitly. A loaded RUNNING/PAUSING checkpoint is normalized to PAUSED.
COMPLETED remains a record; CANCELLED has no committed job definition.

Resume commits intent before enabling dispatch, resets the verification bitmap and
checks every planned chunk from index zero. The previous bitmap is advisory, not
proof of disk durability. Missing/partial chunks use vanilla completion; stored
FULL chunks skip generation even if their journal entry was lost in a crash.

SERVER_STOPPING stops dispatch, stores PAUSED and releases owned tickets. In-flight
results are not claimed complete. Vanilla owns draining/saving during shutdown.
Cancel removes only PreMapped's commit file and preserves Minecraft world data.

## Checkpoint protocol

The JSON commit file references one random immutable journal UUID. Journal fields
are magic, schema, job UUID, bounded bitmap and CRC32. Reads fail closed on invalid
metadata/schema/length/checksum, out-of-plan bits or a missing committed journal.

Each save writes and forces a new journal, atomically renames it, then writes and
forces JSON and atomically switches the commit file. Only afterwards are old
journals cleaned up best effort. Unreferenced journals and temporary JSON files are
ignored. Atomic-move failure aborts the save. Cancel can leave orphan journals until
the next checkpoint. There is no universal power-loss durability promise: directory
persistence and hardware caches vary, as does native Minecraft/mod save behavior.

## Fingerprints

Inputs include loaded mod IDs/versions, seed, actual dimension heights, encoded
chunk generators, effective worldgen/dimension/structure/tag resources and streamed
config-file hashes. Field ordering is deterministic and length-delimited. Contents
are never logged; IO/codec failure stops initialization. Config symlinks are skipped.

All configs/mods are included initially, so unrelated changes may require approval.
Mods can read external inputs or change behavior at runtime; this is a compatibility
guard, not proof that every possible generator input is captured. The runtime
recomputes it before Resume so changes while the monitor is open require approval.

## Mixins and runtime gate

Mixins are required, Java 17-compatible and defaultRequire=1. Mapping compilation
does not replace checking actual mixin application and bootstrap in the game.

| Mixin | Purpose |
| --- | --- |
| CreateWorldScreenMixin | Append a tab at the tab-array construction point |
| SelectWorldScreenMixin | Selected-world header action |
| WorldEntryAccessor | Exact world-directory identity |
| MinecraftClientMixin | One-shot request and pre-login interception |
| ServerChunkManagerAccessor | Vanilla async NBT reader, no direct region IO |
