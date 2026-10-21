package premapped.generation;

/**
 * Defines a bounded rectangle in chunk coordinates, not block coordinates.
 * Coordinates are inclusive at the minimum and exclusive at the maximum.
 */
public record ChunkPlan(int minX, int minZ, int width, int depth) {
    public static final int MAX_CHUNKS = 1_000_000;
    private static final int BORDER_CHUNK = 1_874_999;

    /** Rejects invalid, overflowing or excessive plans before allocating a journal. */
    public ChunkPlan {
        if (width < 1 || depth < 1 || (long) width * depth > MAX_CHUNKS) {
            throw new IllegalArgumentException("A plan must contain 1 to " + MAX_CHUNKS + " chunks");
        }
        if (minX < -BORDER_CHUNK || minZ < -BORDER_CHUNK
                || (long) minX + width - 1 > BORDER_CHUNK
                || (long) minZ + depth - 1 > BORDER_CHUNK) {
            throw new IllegalArgumentException("Plan exceeds Minecraft's coordinate limits");
        }
    }

    /** Returns the exact requested count; worldgen dependency chunks are excluded. */
    public int size() {
        return width * depth;
    }

    /** Resolves a stable row-major index lazily, including for negative coordinates. */
    public ChunkCoordinate at(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
        return new ChunkCoordinate(minX + index % width, minZ + index / width);
    }

    /** Creates the agreed 256-chunk bootstrap area around the world origin. */
    public static ChunkPlan prototype() {
        return new ChunkPlan(-8, -8, 16, 16);
    }
}
