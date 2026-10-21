package premapped.generation;

/** Identifies a chunk without depending on a particular Minecraft mod loader. */
public record ChunkCoordinate(int x, int z) {
    /** Packs signed coordinates into the same lossless representation used by Minecraft. */
    public long packed() {
        return Integer.toUnsignedLong(x) | ((long) z << 32);
    }
}
