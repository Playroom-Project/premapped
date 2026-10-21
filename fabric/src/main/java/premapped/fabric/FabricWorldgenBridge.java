package premapped.fabric;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import premapped.api.WorldgenPlatformBridge;
import premapped.fabric.mixin.ServerChunkManagerAccessor;
import premapped.generation.ChunkCoordinate;

/**
 * Uses the running integrated server's actual modded chunk pipeline and vanilla IO.
 * This deliberately conservative prototype keeps one requested chunk ticket alive
 * through save verification; throughput optimizations belong to a later milestone.
 */
public final class FabricWorldgenBridge implements WorldgenPlatformBridge {
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create(
            "premapped", Comparator.comparingLong(ChunkPos::toLong));
    private final MinecraftServer server;
    private final ServerWorld world;
    private final Set<ChunkPos> ownedTickets = new HashSet<>();

    /** Attaches to an already initialized world; it never starts a second server. */
    public FabricWorldgenBridge(MinecraftServer server, ServerWorld world) {
        this.server = server;
        this.world = world;
    }

    /** Reads stored NBT through Minecraft, including signed-coordinate verification. */
    @Override
    public CompletableFuture<Boolean> isStoredFull(ChunkCoordinate position) {
        requireServerThread();
        return ((ServerChunkManagerAccessor) world.getChunkManager()).premapped$getStorage()
                .getNbt(new ChunkPos(position.x(), position.z()))
                .thenApply(nbt -> nbt.filter(value -> isFullNbt(value, position)).isPresent());
    }

    /** Requests FULL without blocking the server thread or running worldgen on our own executor. */
    @Override
    public CompletableFuture<Void> generateAndSave(ChunkCoordinate position) {
        requireServerThread();
        ChunkPos chunkPos = new ChunkPos(position.x(), position.z());
        world.getChunkManager().addTicket(TICKET, chunkPos, 0, chunkPos);
        ownedTickets.add(chunkPos);
        return world.getChunkManager().getChunkFutureSyncOnMainThread(
                position.x(), position.z(), ChunkStatus.FULL, true).thenCompose(result ->
                onServer(() -> {
                    var chunk = result.left().orElseThrow(() ->
                            new IllegalStateException("Minecraft could not complete " + chunkPos));
                    if (!chunk.getStatus().isAtLeast(ChunkStatus.FULL)) {
                        throw new IllegalStateException("Incomplete chunk returned for " + chunkPos);
                    }
                    // Force vanilla's normal save path before making any durable completion claim.
                    chunk.setNeedsSaving(true);
                    if (!server.save(true, true, true)) {
                        throw new IllegalStateException("Minecraft did not save " + chunkPos);
                    }
                    return isStoredFull(position);
                })).thenCompose(saved -> onServer(() -> {
                    release(chunkPos);
                    if (!saved) {
                        throw new IllegalStateException("Stored FULL verification failed for " + chunkPos);
                    }
                    return CompletableFuture.completedFuture(null);
                }));
    }

    /** Releases job-owned tickets during pause, failure or ordinary server shutdown. */
    @Override
    public void close() {
        requireServerThread();
        for (ChunkPos pos : Set.copyOf(ownedTickets)) { release(pos); }
    }

    /** Schedules follow-up Minecraft calls back onto its server thread after asynchronous IO. */
    private <T> CompletableFuture<T> onServer(Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (server.isStopping()) {
                    throw new IllegalStateException("Server is stopping; no further worldgen IO dispatched");
                }
                action.get().whenComplete((result, error) -> {
                    if (error != null) { future.completeExceptionally(error); }
                    else { future.complete(result); }
                });
            } catch (Exception exception) { future.completeExceptionally(exception); }
        });
        return future;
    }

    /** Removes only this bridge's ticket, leaving players' and other mods' tickets intact. */
    private void release(ChunkPos position) {
        world.getChunkManager().removeTicket(TICKET, position, 0, position);
        ownedTickets.remove(position);
    }

    /** Accepts only the requested coordinates and the final vanilla chunk status. */
    private static boolean isFullNbt(NbtCompound nbt, ChunkCoordinate position) {
        String status = nbt.getString("Status");
        return ("minecraft:full".equals(status) || "full".equals(status))
                && nbt.contains("xPos", NbtElement.INT_TYPE) && nbt.contains("zPos", NbtElement.INT_TYPE)
                && nbt.getInt("xPos") == position.x() && nbt.getInt("zPos") == position.z();
    }

    /** Fails early if a future integration accidentally calls Minecraft from the render thread. */
    private void requireServerThread() {
        if (!server.isOnThread()) { throw new IllegalStateException("Worldgen bridge requires server thread"); }
    }
}
