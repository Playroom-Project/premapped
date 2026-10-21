package premapped;

import java.nio.file.Path;
import java.util.HashSet;
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
import static org.junit.jupiter.api.Assertions.*;

/** Regressions for retained progress without trusting old checkpoint claims as native storage. */
class ProgressRegressionTest {
    @TempDir Path directory;
    private static final String HASH = "a".repeat(64);

    /** Keeps native storage independent from the checkpoint and can delay a real save boundary. */
    private static final class Storage implements WorldgenPlatformBridge {
        final Set<ChunkCoordinate> stored = new HashSet<>();
        CompletableFuture<Void> blocked;
        int generated;

        @Override public CompletableFuture<Boolean> isStoredFull(ChunkCoordinate position) {
            return CompletableFuture.completedFuture(stored.contains(position));
        }

        @Override public CompletableFuture<Void> generateAndSave(ChunkCoordinate position) {
            generated++;
            if (blocked != null) { return blocked.thenRun(() -> stored.add(position)); }
            stored.add(position);
            return CompletableFuture.completedFuture(null);
        }

        @Override public void close() { }
    }

    /** Makes a two-chunk plan so a partial pause can be observed exactly. */
    private GenerationJob job() {
        return GenerationJob.create("test-world", 123456789L, "minecraft:overworld",
            new ChunkPlan(-2, -1, 2, 1), HASH);
    }

    /** Fails a regression rather than silently accepting an unfinished state machine. */
    private void drain(GenerationManager manager) {
        for (int i = 0; i < 30 && (manager.snapshot().state() == JobState.RUNNING
            || manager.snapshot().state() == JobState.PAUSING); i++) { manager.tick(); }
        assertNotEquals(JobState.RUNNING, manager.snapshot().state());
        assertNotEquals(JobState.PAUSING, manager.snapshot().state());
    }

    /** Resume preserves displayed and persisted progress while restarting native verification. */
    @Test void pauseResumeRetainsRecordedProgressAndRechecksStorage() throws Exception {
        Storage storage = new Storage();
        JobRepository repository = new JobRepository(directory);
        GenerationManager manager = new GenerationManager(job(), new ChunkJournal(2), storage, repository);
        manager.resume(HASH, false);
        manager.tick(); manager.tick(); manager.tick();
        manager.pause(); drain(manager);
        assertEquals(1, manager.snapshot().recordedCompleted());
        manager.resume(HASH, false);
        assertEquals(1, manager.snapshot().recordedCompleted());
        assertEquals(0, manager.snapshot().completed());
        assertEquals(1, repository.load().orElseThrow().journal().completed());
        drain(manager);
        assertEquals(JobState.COMPLETED, manager.snapshot().state());
        assertEquals(2, storage.generated);
        assertEquals(2, manager.snapshot().completed());
    }

    /** Missing native data immediately reduces recorded progress before its replacement save completes. */
    @Test void staleRecordedCompletionCannotCompleteAJob() throws Exception {
        Storage storage = new Storage();
        storage.blocked = new CompletableFuture<>();
        ChunkJournal journal = new ChunkJournal(2);
        journal.set(0, true); journal.set(1, true);
        GenerationManager manager = new GenerationManager(job(), journal, storage, new JobRepository(directory));
        manager.resume(HASH, false);
        assertEquals(2, manager.snapshot().recordedCompleted());
        assertEquals(0, manager.snapshot().completed());
        manager.tick(); manager.tick();
        assertEquals(1, manager.snapshot().recordedCompleted());
        assertEquals(0, manager.snapshot().completed());
        assertEquals(JobState.RUNNING, manager.snapshot().state());
    }

    /** A previously completed job still requires manual, authoritative verification after reopening. */
    @Test void reopeningCompletedCheckpointRequiresManualVerification() throws Exception {
        Storage storage = new Storage();
        storage.stored.add(job().plan().at(0)); storage.stored.add(job().plan().at(1));
        ChunkJournal journal = new ChunkJournal(2);
        journal.set(0, true); journal.set(1, true);
        GenerationManager manager = new GenerationManager(job().withState(JobState.COMPLETED, ""),
            journal, storage, new JobRepository(directory));
        manager.tick();
        assertEquals(JobState.PAUSED, manager.snapshot().state());
        assertEquals(2, manager.snapshot().recordedCompleted());
        assertEquals(0, manager.snapshot().completed());
        manager.resume(HASH, false); drain(manager);
        assertEquals(JobState.COMPLETED, manager.snapshot().state());
        assertEquals(2, manager.snapshot().completed());
        assertEquals(0, storage.generated);
    }
}
