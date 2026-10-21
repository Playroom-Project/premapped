package premapped.api;

import java.util.concurrent.CompletableFuture;
import premapped.generation.ChunkCoordinate;

/**
 * Isolates loader-specific server, ticket and storage APIs from the job engine.
 * Methods are called on the server thread. Futures must never be joined there.
 */
public interface WorldgenPlatformBridge extends AutoCloseable {
    /** Checks authoritative stored chunk NBT without generating or rewriting the chunk. */
    CompletableFuture<Boolean> isStoredFull(ChunkCoordinate position);

    /** Completes vanilla generation, flushes vanilla storage, then verifies stored FULL data. */
    CompletableFuture<Void> generateAndSave(ChunkCoordinate position);

    /** Releases only tickets owned by PreMapped; it never deletes world data. */
    @Override
    void close();
}
