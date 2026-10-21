package premapped.world;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Hashes deterministically ordered, length-delimited worldgen inputs with SHA-256. */
public final class WorldgenFingerprint {
    private WorldgenFingerprint() { }

    /** Avoids order-dependent hashes and ambiguous concatenations between adjacent fields. */
    public static String compute(Map<String, byte[]> inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(inputs).forEach((name, data) -> {
                byte[] key = name.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(key.length).array());
                digest.update(key);
                digest.update(ByteBuffer.allocate(4).putInt(data.length).array());
                digest.update(data);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java must provide SHA-256", exception);
        }
    }
}
