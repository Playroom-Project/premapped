package premapped.fabric.client;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;
import premapped.fabric.FabricWorldgenBridge;
import premapped.fabric.FabricWorldgenFingerprint;
import premapped.fabric.PreMappedFabric;
import premapped.generation.ChunkPlan;
import premapped.generation.GenerationJob;
import premapped.generation.GenerationManager;
import premapped.generation.JobState;
import premapped.persistence.ChunkJournal;
import premapped.persistence.JobRepository;

/**
 * Coordinates menu-driven generation with the one integrated server owned by Minecraft.
 * The manager is server-thread confined; only immutable snapshots cross to the renderer.
 */
public final class PreMappedClient implements ClientModInitializer {
    private static String pendingWorld;
    private static volatile IntegratedServer ownedServer;
    private static GenerationManager manager;
    private static String fingerprint;
    private static volatile GenerationManager.Snapshot snapshot;
    private static volatile String error = "";

    /** Installs events once; no world is opened and no generation starts during mod initialization. */
    @Override
    public void onInitializeClient() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server == ownedServer && manager != null) {
                manager.tick();
                publish();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server == ownedServer && manager != null) {
                try { manager.stopForShutdown(); publish(); }
                catch (Exception exception) { report(exception); }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server == ownedServer) { manager = null; ownedServer = null; }
        });
    }

    /** Arms one exact world-directory name immediately before vanilla starts that world. */
    public static void arm(String worldDirectory) { pendingWorld = worldDirectory; }

    /** Consumes the one-shot request; an unrelated ordinary world launch cannot inherit it. */
    public static boolean claimLaunch(String worldDirectory) {
        boolean matches = worldDirectory.equals(pendingWorld);
        pendingWorld = null;
        return matches;
    }

    /** Clears a cancelled launch when vanilla returns from a warning or load failure. */
    public static void returnedToMenu(Screen screen) {
        if (screen instanceof TitleScreen || screen instanceof SelectWorldScreen
                || screen instanceof CreateWorldScreen) { pendingWorld = null; }
    }

    /**
     * Takes over only after vanilla has finished server initialization and before local login.
     * Normal settings, datapack validation, world locks and mod lifecycle events are preserved.
     */
    public static void enter(IntegratedServer server) {
        if (server == null) { throw new IllegalStateException("Vanilla did not create an integrated server"); }
        ownedServer = server;
        snapshot = null;
        error = "";
        server.execute(() -> initialize(server));
        MinecraftClient.getInstance().setScreen(new PreMappedScreen());
    }

    /** Initializes a new job or restores a paused one under Minecraft's existing world lock. */
    private static void initialize(MinecraftServer server) {
        try {
            Path worldPath = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            String worldId = worldPath.getFileName().toString();
            long seed = server.getSaveProperties().getGeneratorOptions().getSeed();
            fingerprint = FabricWorldgenFingerprint.compute(server);
            JobRepository repository = new JobRepository(worldPath.resolve("premapped"));
            var stored = repository.load();
            GenerationJob job;
            ChunkJournal journal;
            if (stored.isPresent()) {
                job = stored.get().job();
                journal = stored.get().journal();
                if (!worldId.equals(job.worldId()) || seed != job.seed()
                        || !"minecraft:overworld".equals(job.dimension())) {
                    throw new IllegalStateException("Saved job does not match this world, seed or supported dimension");
                }
            } else {
                job = GenerationJob.create(worldId, seed, "minecraft:overworld", ChunkPlan.prototype(), fingerprint);
                journal = new ChunkJournal(job.plan().size());
                repository.save(job, journal);
            }
            manager = new GenerationManager(job, journal,
                    new FabricWorldgenBridge(server, server.getOverworld()), repository);
            publish();
        } catch (Exception exception) { report(exception); }
    }

    /** Dispatches a user-requested Resume onto the server thread, with explicit change approval. */
    public static void resume(boolean approveChangedWorldgen) {
        submit(() -> {
            JobState state = manager.job().state();
            if (state == JobState.RUNNING || state == JobState.PAUSING || state == JobState.CANCELLED) {
                return; // Repeated GUI clicks cannot turn an active job into an action error.
            }
            String reviewedFingerprint = fingerprint;
            fingerprint = FabricWorldgenFingerprint.compute(ownedServer);
            boolean changed = !manager.job().worldgenFingerprint().equals(fingerprint);
            if (changed && (!approveChangedWorldgen || !fingerprint.equals(reviewedFingerprint))) {
                // Approval applies to the exact inputs reviewed, never to a later edit.
                MinecraftClient.getInstance().execute(() -> {
                    if (MinecraftClient.getInstance().currentScreen instanceof PreMappedScreen screen) {
                        screen.confirmWorldgenChange();
                    }
                });
                return;
            }
            manager.resume(fingerprint, approveChangedWorldgen);
            error = "";
        });
    }

    /** Stops dispatching new work immediately after this request reaches the server thread. */
    public static void pause() { submit(() -> manager.pause()); }

    /** Deletes only the job once pending native work has drained. */
    public static void cancel() { submit(() -> manager.cancel()); }

    /** Returns the latest immutable status, or null while initialization is still running. */
    public static GenerationManager.Snapshot snapshot() { return snapshot; }

    /** Returns a user-visible initialization or persistence failure without hiding its cause. */
    public static String error() { return error; }

    /** Serializes every UI command with tick processing and native world access. */
    private static void submit(ServerAction action) {
        IntegratedServer server = ownedServer;
        if (server == null) { return; }
        server.execute(() -> {
            if (manager == null) { return; }
            try { action.run(); publish(); }
            catch (Exception exception) { report(exception); }
        });
    }

    /** Restricts checked exceptions to this internal server-thread callback. */
    @FunctionalInterface
    private interface ServerAction { void run() throws Exception; }

    /** Publishes a snapshot through a volatile field after each state change. */
    private static void publish() { snapshot = manager.snapshot(); }

    /** Logs the full exception and exposes a concise reason while leaving Stop available. */
    private static void report(Exception exception) {
        error = exception.toString();
        PreMappedFabric.LOGGER.error("PreMapped operation failed", exception);
    }
}
