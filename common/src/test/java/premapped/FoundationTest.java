package premapped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import premapped.api.WorldgenPlatformBridge;
import premapped.generation.ChunkCoordinate;
import premapped.generation.ChunkPlan;
import premapped.generation.GenerationJob;
import premapped.generation.GenerationManager;
import premapped.generation.JobState;
import premapped.persistence.ChunkJournal;
import premapped.persistence.JobRepository;
import premapped.world.WorldgenFingerprint;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for durability, crash recovery and the loader-independent state machine. */
class FoundationTest {
    @TempDir Path directory;
    private static final String HASH = "a".repeat(64);
    private static final String CHANGED_HASH = "b".repeat(64);

    /** Models storage independently of the journal so stale completion claims are detectable. */
    private static final class FakeBridge implements WorldgenPlatformBridge {
        final Set<ChunkCoordinate> stored = new HashSet<>();
        int generated;
        boolean fail;
        CompletableFuture<Void> blocked;

        @Override public CompletableFuture<Boolean> isStoredFull(ChunkCoordinate position) {
            return CompletableFuture.completedFuture(stored.contains(position));
        }

        @Override public CompletableFuture<Void> generateAndSave(ChunkCoordinate position) {
            generated++;
            if (fail) { return CompletableFuture.failedFuture(new IOException("Synthetic disk failure")); }
            if (blocked != null) { return blocked.thenRun(() -> stored.add(position)); }
            stored.add(position);
            return CompletableFuture.completedFuture(null);
        }

        @Override public void close() { }
    }

    /** Creates a fresh small job whose entire lifecycle is cheap enough to exercise. */
    private GenerationJob job() {
        return GenerationJob.create("test-world", 123456789L, "minecraft:overworld",
                new ChunkPlan(-2, -1, 4, 2), HASH);
    }

    /** Runs only a bounded number of ticks to catch accidental nontermination. */
    private void run(GenerationManager manager) {
        for (int i = 0; i < 200 && (manager.snapshot().state() == JobState.RUNNING
                || manager.snapshot().state() == JobState.PAUSING); i++) { manager.tick(); }
    }

    /** Verifies the exact prototype area and lossless signed coordinate identity. */
    @Test void plansNegativeCoordinatesWithoutDuplicates() {
        ChunkPlan plan = ChunkPlan.prototype();
        Set<ChunkCoordinate> seen = new HashSet<>();
        for (int i = 0; i < plan.size(); i++) { assertTrue(seen.add(plan.at(i))); }
        assertEquals(256, seen.size());
        assertEquals(new ChunkCoordinate(-8, -8), plan.at(0));
        assertEquals(new ChunkCoordinate(7, 7), plan.at(255));
        assertNotEquals(new ChunkCoordinate(-1, 0).packed(), new ChunkCoordinate(0, -1).packed());
    }

    /** Rejects empty, oversized, overflowing and out-of-border plans. */
    @Test void rejectsUnsafePlansBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkPlan(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPlan(0, 0, Integer.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPlan(Integer.MAX_VALUE, 0, 2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> ChunkPlan.prototype().at(256));
    }

    /** Requires deterministic fingerprints while detecting changed effective input bytes. */
    @Test void fingerprintIgnoresInsertionOrderButDetectsContentChanges() {
        Map<String, byte[]> first = new HashMap<>();
        first.put("seed", new byte[] {1}); first.put("mod", new byte[] {2});
        Map<String, byte[]> second = new java.util.LinkedHashMap<>();
        second.put("mod", new byte[] {2}); second.put("seed", new byte[] {1});
        assertEquals(WorldgenFingerprint.compute(first), WorldgenFingerprint.compute(second));
        second.put("seed", new byte[] {3});
        assertNotEquals(WorldgenFingerprint.compute(first), WorldgenFingerprint.compute(second));
    }

    /** Restores the exact metadata and verified bitmap from one committed snapshot. */
    @Test void checkpointRoundTripsMetadataAndCompactJournal() throws Exception {
        JobRepository repository = new JobRepository(directory);
        ChunkJournal journal = new ChunkJournal(8);
        journal.set(1, true); journal.set(7, true);
        GenerationJob metadata = job();
        repository.save(metadata, journal);
        var loaded = repository.load().orElseThrow();
        assertEquals(metadata, loaded.job());
        assertEquals(2, loaded.journal().completed());
    }

    /** Ignores unreferenced journal and temporary metadata left by an interrupted commit. */
    @Test void uncommittedJournalDoesNotReplaceCommittedMetadata() throws Exception {
        JobRepository repository = new JobRepository(directory);
        GenerationJob metadata = job();
        repository.save(metadata, new ChunkJournal(8));
        Files.write(directory.resolve("journal-00000000-0000-0000-0000-000000000000.bin"), new byte[] {9});
        Files.writeString(directory.resolve("generation.json.tmp"), "interrupted write");
        assertEquals(metadata, repository.load().orElseThrow().job());
    }

    /** Detects checksum corruption before any resumed world work can be authorized. */
    @Test void corruptJournalFailsClosed() throws Exception {
        JobRepository repository = new JobRepository(directory);
        repository.save(job(), new ChunkJournal(8));
        Path journal;
        try (var files = Files.list(directory)) {
            journal = files.filter(path -> path.toString().endsWith(".bin")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(journal);
        bytes[bytes.length - 1] ^= 1;
        Files.write(journal, bytes);
        assertThrows(IOException.class, repository::load);
    }

    /** Rejects malformed commit files and journal entries outside the requested plan. */
    @Test void malformedMetadataAndOutOfRangeBitsFailClosed() throws Exception {
        JobRepository repository = new JobRepository(directory);
        Files.writeString(directory.resolve("generation.json"), "{}");
        assertThrows(IOException.class, repository::load);
        assertThrows(IllegalArgumentException.class, () -> new ChunkJournal(1, new byte[] {2}));
    }

    /** Skips authoritative stored chunks and commits completion only after verification. */
    @Test void generatesOnlyMissingChunksAndPersistsCompletion() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.stored.add(job().plan().at(0));
        JobRepository repository = new JobRepository(directory);
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge, repository);
        manager.resume(HASH, false);
        run(manager);
        assertEquals(JobState.COMPLETED, manager.snapshot().state());
        assertEquals(7, bridge.generated);
        assertEquals(1, manager.snapshot().existingThisRun());
        assertEquals(7, manager.snapshot().generatedThisRun());
        assertEquals(8, manager.snapshot().completed());
        assertEquals(JobState.COMPLETED, repository.load().orElseThrow().job().state());
    }

    /** Invalidates stale journal claims by verifying actual storage on every Resume. */
    @Test void resumeRechecksDiskEvenWhenJournalClaimsEverythingComplete() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ChunkJournal staleJournal = new ChunkJournal(8);
        for (int i = 0; i < 8; i++) { staleJournal.set(i, true); }
        GenerationManager manager = new GenerationManager(job().withState(JobState.PAUSED, ""),
                staleJournal, bridge, new JobRepository(directory));
        manager.resume(HASH, false);
        assertEquals(0, manager.snapshot().completed());
        run(manager);
        assertEquals(8, bridge.generated);
        assertEquals(JobState.COMPLETED, manager.snapshot().state());
    }

    /** Restoring an interrupted RUNNING checkpoint cannot restart generation by itself. */
    @Test void manualResumeIsRequiredAfterInterruption() throws Exception {
        GenerationManager manager = new GenerationManager(job().withState(JobState.RUNNING, ""),
                new ChunkJournal(8), new FakeBridge(), new JobRepository(directory));
        manager.tick();
        assertEquals(JobState.PAUSED, manager.snapshot().state());
        assertEquals(0, manager.snapshot().completed());
    }

    /** Requires explicit approval before accepting changed worldgen inputs. */
    @Test void changedFingerprintRequiresExplicitApproval() throws Exception {
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8),
                new FakeBridge(), new JobRepository(directory));
        assertThrows(IllegalStateException.class, () -> manager.resume(CHANGED_HASH, false));
        assertEquals(JobState.CREATED, manager.snapshot().state());
        manager.resume(CHANGED_HASH, true);
        assertEquals(CHANGED_HASH, manager.job().worldgenFingerprint());
    }

    /** A failed checkpoint must not leave a RUNNING manager able to mutate the world. */
    @Test void failedResumeCheckpointCannotStartGeneration() throws Exception {
        Path obstruction = directory.resolve("not-a-directory");
        Files.writeString(obstruction, "existing file");
        FakeBridge bridge = new FakeBridge();
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge,
                new JobRepository(obstruction));
        assertThrows(IOException.class, () -> manager.resume(HASH, false));
        manager.tick();
        assertEquals(JobState.CREATED, manager.snapshot().state());
        assertEquals(0, bridge.generated);
    }

    /** Drains an already requested native chunk before committing a pause. */
    @Test void pauseDrainsCurrentChunkBeforeSavingPausedState() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.blocked = new CompletableFuture<>();
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge,
                new JobRepository(directory));
        manager.resume(HASH, false);
        manager.tick(); manager.tick();
        manager.pause(); manager.tick();
        assertEquals(JobState.PAUSING, manager.snapshot().state());
        assertEquals(0, manager.snapshot().completed());
        bridge.blocked.complete(null); manager.tick();
        assertEquals(JobState.PAUSED, manager.snapshot().state());
        assertEquals(1, manager.snapshot().completed());
        assertEquals(1, bridge.generated);
    }

    /** Preserves native stored chunks across a shutdown and a manually resumed manager. */
    @Test void restartSkipsChunksFromPreviousSession() throws Exception {
        FakeBridge bridge = new FakeBridge();
        JobRepository repository = new JobRepository(directory);
        GenerationManager first = new GenerationManager(job(), new ChunkJournal(8), bridge, repository);
        first.resume(HASH, false);
        first.tick(); first.tick(); first.tick();
        first.stopForShutdown();
        var stored = repository.load().orElseThrow();
        GenerationManager second = new GenerationManager(stored.job(), stored.journal(), bridge, repository);
        assertEquals(JobState.PAUSED, second.snapshot().state());
        second.resume(HASH, false); run(second);
        assertEquals(8, bridge.generated);
        assertEquals(JobState.COMPLETED, second.snapshot().state());
    }

    /** Stops on an exceptional native operation without counting it complete. */
    @Test void failedChunkCannotReportFullCompletion() throws Exception {
        FakeBridge bridge = new FakeBridge(); bridge.fail = true;
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge,
                new JobRepository(directory));
        manager.resume(HASH, false); run(manager);
        assertEquals(JobState.FAILED, manager.snapshot().state());
        assertEquals(0, manager.snapshot().completed());
        assertTrue(manager.snapshot().error().contains("Synthetic disk failure"));
    }

    /** Deletes the job definition while retaining every stored world chunk. */
    @Test void cancelDeletesJobAndKeepsGeneratedChunks() throws Exception {
        FakeBridge bridge = new FakeBridge();
        JobRepository repository = new JobRepository(directory);
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge, repository);
        manager.resume(HASH, false); run(manager);
        manager.cancel();
        assertEquals(JobState.CANCELLED, manager.snapshot().state());
        assertTrue(repository.load().isEmpty());
        assertEquals(8, bridge.stored.size());
    }

    /** Defers job removal until the current native generation operation drains. */
    @Test void cancelDuringGenerationWaitsForCurrentChunk() throws Exception {
        FakeBridge bridge = new FakeBridge(); bridge.blocked = new CompletableFuture<>();
        JobRepository repository = new JobRepository(directory);
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge, repository);
        manager.resume(HASH, false); manager.tick(); manager.tick(); manager.cancel();
        assertTrue(repository.load().isPresent());
        bridge.blocked.complete(null); manager.tick();
        assertTrue(repository.load().isEmpty());
        assertEquals(1, bridge.stored.size());
        assertEquals(JobState.CANCELLED, manager.snapshot().state());
    }

    /** Pausing an unfinished disk check must not dispatch new worldgen when the read completes. */
    @Test void pauseDuringDiskCheckDoesNotGenerateMissingChunk() throws Exception {
        FakeBridge bridge = new FakeBridge();
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge,
                new JobRepository(directory));
        manager.resume(HASH, false); manager.tick(); manager.pause(); manager.tick();
        assertEquals(JobState.PAUSED, manager.snapshot().state());
        assertEquals(0, bridge.generated);
        assertEquals(0, manager.snapshot().completed());
    }

    /** A native failure is recoverable only after an explicit new Resume request. */
    @Test void failureCanResumeAfterUnderlyingProblemIsFixed() throws Exception {
        FakeBridge bridge = new FakeBridge(); bridge.fail = true;
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(8), bridge,
                new JobRepository(directory));
        manager.resume(HASH, false); run(manager);
        bridge.fail = false; manager.tick();
        assertEquals(JobState.FAILED, manager.snapshot().state());
        manager.resume(HASH, false); run(manager);
        assertEquals(JobState.COMPLETED, manager.snapshot().state());
    }
}
