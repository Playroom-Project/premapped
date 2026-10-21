package premapped.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;
import premapped.generation.GenerationJob;

/**
 * Owns only a world's premapped directory. Immutable journal snapshots are written
 * before an atomic metadata commit, preventing mixed-generation checkpoint pairs.
 */
public final class JobRepository {
    private static final int MAGIC = 0x504d4a31;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    /** The caller must already hold Minecraft's world-session lock. */
    public JobRepository(Path directory) { this.directory = directory; }

    /** Binds metadata to one immutable journal snapshot through a random checkpoint ID. */
    private record Checkpoint(GenerationJob job, UUID checkpointId) { }

    /** Returns validated job metadata and its matching checksummed bitmap. */
    public record StoredJob(GenerationJob job, ChunkJournal journal) { }

    /** Loads a committed snapshot; malformed or incompatible files fail closed. */
    public Optional<StoredJob> load() throws IOException {
        Path metadata = directory.resolve("generation.json");
        if (!Files.exists(metadata)) { return Optional.empty(); }
        try {
            if (Files.size(metadata) > MAX_METADATA_BYTES) { throw new IOException("Metadata too large"); }
            Checkpoint checkpoint = GSON.fromJson(Files.readString(metadata), Checkpoint.class);
            if (checkpoint == null || checkpoint.job() == null || checkpoint.checkpointId() == null) {
                throw new IOException("Incomplete job metadata");
            }
            Path journalPath = journalPath(checkpoint.checkpointId());
            int maxBytes = (checkpoint.job().plan().size() + 7) / 8 + 64;
            if (Files.size(journalPath) > maxBytes) { throw new IOException("Journal too large"); }
            byte[] data = Files.readAllBytes(journalPath);
            if (data.length < 36) { throw new IOException("Truncated journal"); }
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length - 8);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
                if (input.readInt() != MAGIC || input.readInt() != GenerationJob.SCHEMA_VERSION
                        || !new UUID(input.readLong(), input.readLong()).equals(checkpoint.job().jobId())) {
                    throw new IOException("Journal identity or schema mismatch");
                }
                int length = input.readInt();
                if (length < 0 || length > (checkpoint.job().plan().size() + 7) / 8
                        || length != data.length - 36) {
                    throw new IOException("Invalid journal length");
                }
                byte[] bitmap = input.readNBytes(length);
                if (input.readLong() != crc.getValue()) { throw new IOException("Journal checksum mismatch"); }
                return Optional.of(new StoredJob(checkpoint.job(),
                        new ChunkJournal(checkpoint.job().plan().size(), bitmap)));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid PreMapped checkpoint; generation has not started", exception);
        }
    }

    /** Writes a durable journal first, then atomically switches the metadata reference. */
    public void save(GenerationJob job, ChunkJournal journal) throws IOException {
        Files.createDirectories(directory);
        UUID checkpointId = UUID.randomUUID();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(GenerationJob.SCHEMA_VERSION);
            output.writeLong(job.jobId().getMostSignificantBits());
            output.writeLong(job.jobId().getLeastSignificantBits());
            byte[] bitmap = journal.bytes();
            output.writeInt(bitmap.length);
            output.write(bitmap);
            output.flush();
            CRC32 crc = new CRC32();
            crc.update(bytes.toByteArray());
            output.writeLong(crc.getValue());
        }
        atomicWrite(journalPath(checkpointId), bytes.toByteArray());
        atomicWrite(directory.resolve("generation.json"),
                GSON.toJson(new Checkpoint(job, checkpointId)).getBytes(StandardCharsets.UTF_8));
        // Cleanup is best effort after the commit: failure must not undo a valid checkpoint.
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString()
                    .matches("journal-[0-9a-f-]{36}\\.bin")).toList()) {
                if (!file.equals(journalPath(checkpointId))) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { /* Retry next save. */ }
                }
            }
        } catch (IOException ignored) {
            // The metadata commit already succeeded; orphan snapshots can be cleaned later.
        }
    }

    /** Removes the committed job definition only; all Minecraft chunks remain untouched. */
    public void delete() throws IOException {
        Files.deleteIfExists(directory.resolve("generation.json"));
    }

    /** Builds a filename exclusively from a parsed UUID, never from untrusted path text. */
    private Path journalPath(UUID id) { return directory.resolve("journal-" + id + ".bin"); }

    /** Refuses non-atomic replacement rather than silently weakening checkpoint guarantees. */
    private static void atomicWrite(Path target, byte[] data) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
