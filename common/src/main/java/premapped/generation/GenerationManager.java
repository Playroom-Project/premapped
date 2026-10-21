package premapped.generation;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import premapped.api.WorldgenPlatformBridge;
import premapped.persistence.ChunkJournal;
import premapped.persistence.JobRepository;

/**
 * Serial, nonblocking reference implementation of the chunk-job state machine.
 * All methods run on the server thread. The client consumes immutable snapshots.
 * Recorded progress survives Pause/Resume, while each run verifies native storage again.
 * Only this run's checked chunks can complete a job; native futures are never joined.
 */
public final class GenerationManager {
    private final WorldgenPlatformBridge bridge;
    private final JobRepository repository;
    private GenerationJob job;
    private ChunkJournal journal;
    private CompletableFuture<?> pending;
    private boolean checkingDisk;
    private boolean cancelRequested;
    private int cursor;
    private int existingThisRun;
    private int generatedThisRun;

    /**
     * Carries immutable progress across threads. completed is verified this run;
     * recordedCompleted retains advisory checkpoint progress while disk checks restart.
     */
    public record Snapshot(JobState state, int completed, int total, int checkedThisRun,
                           int existingThisRun, int generatedThisRun,
                           ChunkCoordinate active, String error, int recordedCompleted) { }

    /** Restores metadata without automatically resuming a job after startup. */
    public GenerationManager(GenerationJob job, ChunkJournal journal,
                             WorldgenPlatformBridge bridge, JobRepository repository) {
        this.job = Objects.requireNonNull(job);
        this.journal = Objects.requireNonNull(journal);
        this.bridge = Objects.requireNonNull(bridge);
        this.repository = Objects.requireNonNull(repository);
        if (job.state() == JobState.RUNNING || job.state() == JobState.PAUSING
            || job.state() == JobState.COMPLETED) {
            this.job = job.withState(JobState.PAUSED, "Manual Resume required to verify stored chunks");
        }
    }

    /**
     * Restarts verification at index zero, including journal entries previously completed.
     * Explicit fingerprint approval is mandatory when relevant inputs have changed.
     */
    public void resume(String fingerprint, boolean approveChangedWorldgen) throws IOException {
        if (job.state() == JobState.RUNNING || job.state() == JobState.PAUSING
            || job.state() == JobState.CANCELLED) {
            throw new IllegalStateException("Cannot resume a " + job.state() + " job");
        }
        if (!job.worldgenFingerprint().equals(fingerprint) && !approveChangedWorldgen) {
            throw new IllegalStateException("Worldgen changed; explicit approval is required");
        }
        GenerationJob resumed = job.withFingerprint(fingerprint).withState(JobState.RUNNING, "");
        // Keep recorded progress; it is advisory until each native chunk is rechecked.
        // Commit intent before allowing the tick loop to dispatch any native work.
        repository.save(resumed, journal);
        job = resumed;
        cursor = 0;
        existingThisRun = 0;
        generatedThisRun = 0;
        cancelRequested = false;
        pending = null;
    }

    /** Stops dispatch immediately; an already requested chunk may finish and be saved. */
    public void pause() {
        if (job.state() == JobState.RUNNING) { job = job.withState(JobState.PAUSING, ""); }
    }

    /** Requests job removal only after the current operation has drained safely. */
    public void cancel() throws IOException {
        cancelRequested = true;
        if (pending == null) {
            finishStop();
        } else {
            job = job.withState(JobState.PAUSING, "");
        }
    }

    /** Advances at most one operation per tick, leaving Minecraft's task pump unblocked. */
    public void tick() {
        if (job.state() != JobState.RUNNING && job.state() != JobState.PAUSING) { return; }
        try {
            if (pending != null) {
                if (!pending.isDone()) { return; }
                // getNow is used only after isDone; exceptional futures are handled below.
                Object result = pending.getNow(null);
                pending = null;
                if (checkingDisk && !Boolean.TRUE.equals(result)) {
                    // A missing native chunk invalidates an old completion claim immediately.
                    journal.set(cursor, false);
                    if (job.state() == JobState.PAUSING) { finishStop(); return; }
                    checkingDisk = false;
                    pending = bridge.generateAndSave(job.plan().at(cursor));
                    return;
                }
                if (checkingDisk) { existingThisRun++; }
                else { generatedThisRun++; }
                journal.set(cursor++, true);
                if (cursor % 8 == 0) { repository.save(job, journal); }
            }
            if (job.state() == JobState.PAUSING) { finishStop(); return; }
            if (cursor == job.plan().size()) {
                job = job.withState(JobState.COMPLETED, "");
                repository.save(job, journal);
                return;
            }
            checkingDisk = true;
            pending = bridge.isStoredFull(job.plan().at(cursor));
        } catch (Exception exception) {
            fail(exception);
        }
    }

    /**
     * Called before vanilla shutdown saves the world. In-flight work is not claimed as
     * complete, and vanilla remains responsible for draining its own generation and IO.
     */
    public void stopForShutdown() throws IOException {
        try {
            if (job.state() == JobState.RUNNING || job.state() == JobState.PAUSING) {
                job = job.withState(JobState.PAUSED, "");
                if (cancelRequested) {
                    repository.delete();
                    job = job.withState(JobState.CANCELLED, "");
                } else {
                    repository.save(job, journal);
                }
            }
        } finally {
            pending = null;
            bridge.close();
        }
    }

    /** Produces a UI snapshot without exposing mutable journal or metadata internals. */
    public Snapshot snapshot() {
        return new Snapshot(job.state(), cursor, job.plan().size(), cursor,
            existingThisRun, generatedThisRun,
            pending == null ? null : job.plan().at(cursor), job.lastError(), journal.completed());
    }

    /** Exposes immutable metadata for fingerprint comparison and diagnostics. */
    public GenerationJob job() { return job; }

    /** Commits the final pause, or removes the job without deleting generated chunks. */
    private void finishStop() throws IOException {
        if (cancelRequested) {
            repository.delete();
            job = job.withState(JobState.CANCELLED, "");
        } else {
            job = job.withState(JobState.PAUSED, "");
            repository.save(job, journal);
        }
    }

    /** Preserves an actionable failure; an IO failure never masquerades as completion. */
    private void fail(Exception exception) {
        Throwable cause = exception instanceof CompletionException && exception.getCause() != null
            ? exception.getCause() : exception;
        String message = "Chunk " + (cursor < job.plan().size() ? job.plan().at(cursor) : "checkpoint")
            + ": " + cause;
        pending = null;
        job = job.withState(JobState.FAILED, message);
        bridge.close();
        try {
            repository.save(job, journal);
        } catch (IOException checkpointFailure) {
            job = job.withState(JobState.FAILED,
                message + "; checkpoint could not be saved: " + checkpointFailure);
        }
    }
}
