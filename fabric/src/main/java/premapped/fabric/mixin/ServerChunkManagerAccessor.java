package premapped.fabric.mixin;

import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes vanilla's asynchronous NBT reader without opening or editing region files. */
@Mixin(ServerChunkManager.class)
public interface ServerChunkManagerAccessor {
    /** Returns storage owned and locked by the active Minecraft world session. */
    @Accessor("threadedAnvilChunkStorage")
    ThreadedAnvilChunkStorage premapped$getStorage();
}
