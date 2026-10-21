package premapped.persistence;

import java.util.BitSet;

/**
 * Compact advisory record of chunks verified on disk, one bit per planned chunk.
 * Resume rechecks every planned chunk; these bits alone never prove disk durability.
 */
public final class ChunkJournal {
    private final int size;
    private final BitSet verified;

    /** Allocates a bounded journal after the plan has passed validation. */
    public ChunkJournal(int size) {
        this(size, new byte[0]);
    }

    /** Reconstructs a journal and rejects out-of-plan bits instead of silently truncating. */
    public ChunkJournal(int size, byte[] bytes) {
        if (size < 1 || size > premapped.generation.ChunkPlan.MAX_CHUNKS) {
            throw new IllegalArgumentException("Invalid journal size");
        }
        this.size = size;
        verified = BitSet.valueOf(bytes);
        if (verified.length() > size) {
            throw new IllegalArgumentException("Journal contains out-of-plan chunks");
        }
    }

    /** Updates a single entry after an authoritative disk read has completed. */
    public void set(int index, boolean complete) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        verified.set(index, complete);
    }

    /** Returns the number of verified entries, not the number of requested futures. */
    public int completed() { return verified.cardinality(); }

    /** Returns an independent compact byte array suitable for checksummed persistence. */
    public byte[] bytes() { return verified.toByteArray(); }
}
