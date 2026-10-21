package premapped.generation;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable metadata for one chunk job. LOD work will use a separate job type;
 * this record never interprets Distant Horizons progress as chunk completion.
 */
public record GenerationJob(int schemaVersion, UUID jobId, String worldId, long seed,
                            String dimension, ChunkPlan plan, String worldgenFingerprint,
                            JobState state, String lastError) {
    public static final int SCHEMA_VERSION = 1;

    /** Validates disk-loaded data as strictly as newly created metadata. */
    public GenerationJob {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported job schema: " + schemaVersion);
        }
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        if (worldId == null || worldId.isBlank() || dimension == null || dimension.isBlank()
                || worldgenFingerprint == null || !worldgenFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid world identity, dimension or fingerprint");
        }
        lastError = lastError == null ? "" : lastError;
    }

    /** Creates a new job without starting generation or touching Minecraft files. */
    public static GenerationJob create(String worldId, long seed, String dimension,
                                       ChunkPlan plan, String fingerprint) {
        return new GenerationJob(SCHEMA_VERSION, UUID.randomUUID(), worldId, seed,
                dimension, plan, fingerprint, JobState.CREATED, "");
    }

    /** Produces a new metadata snapshot; mutable execution state stays in the manager. */
    public GenerationJob withState(JobState next, String error) {
        return new GenerationJob(schemaVersion, jobId, worldId, seed, dimension, plan,
                worldgenFingerprint, next, error);
    }

    /** Records an explicitly approved worldgen change before generation resumes. */
    public GenerationJob withFingerprint(String fingerprint) {
        return new GenerationJob(schemaVersion, jobId, worldId, seed, dimension, plan,
                fingerprint, state, lastError);
    }
}
