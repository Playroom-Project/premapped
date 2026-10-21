package premapped.fabric;

import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import premapped.world.WorldgenFingerprint;

/**
 * Computes a conservative compatibility guard from actual loaded worldgen inputs.
 * All config files are included initially: a harmless change may require approval,
 * but changed mod settings are not silently ignored. No file contents are logged.
 */
public final class FabricWorldgenFingerprint {
    private FabricWorldgenFingerprint() { }

    /** Streams resources and config files so large modpacks do not require a second in-memory copy. */
    public static String compute(MinecraftServer server) throws IOException {
        Map<String, byte[]> inputs = new HashMap<>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> inputs.put(
                "mod/" + mod.getMetadata().getId(), text(mod.getMetadata().getVersion().getFriendlyString())));
        inputs.put("seed", text(Long.toString(server.getSaveProperties().getGeneratorOptions().getSeed())));
        for (var world : server.getWorlds()) {
            String name = world.getRegistryKey().getValue().toString();
            inputs.put("dimension/" + name + "/height", text(world.getBottomY() + ":" + world.getHeight()));
            var encoded = ChunkGenerator.CODEC.encodeStart(
                    RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager()),
                    world.getChunkManager().getChunkGenerator());
            inputs.put("dimension/" + name + "/generator", text(encoded.getOrThrow(false,
                    message -> PreMappedFabric.LOGGER.warn("Worldgen codec: {}", message)).toString()));
        }
        // Effective resources include data supplied by mod jars and enabled datapacks.
        for (String prefix : new String[] {"worldgen", "dimension", "dimension_type", "structures", "tags"}) {
            for (var entry : server.getResourceManager().findResources(prefix, id -> true).entrySet()) {
                try (InputStream stream = entry.getValue().getInputStream()) {
                    inputs.put("resource/" + entry.getKey(), digest(stream));
                }
            }
        }
        Path config = FabricLoader.getInstance().getConfigDir();
        if (Files.isDirectory(config)) {
            try (var files = Files.walk(config)) {
                for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList()) {
                    try (InputStream stream = Files.newInputStream(file)) {
                        inputs.put("config/" + config.relativize(file).toString().replace('\\', '/'), digest(stream));
                    }
                }
            }
        }
        return WorldgenFingerprint.compute(inputs);
    }

    /** Uses UTF-8 consistently across operating systems. */
    private static byte[] text(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    /** Hashes one stream with a fixed memory budget; read failures abort compatibility checks. */
    private static byte[] digest(InputStream stream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = stream.read(buffer)) != -1) { digest.update(buffer, 0, length); }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java must provide SHA-256", exception);
        }
    }
}
