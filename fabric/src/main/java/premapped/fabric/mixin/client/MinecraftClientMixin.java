package premapped.fabric.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import premapped.fabric.client.PreMappedClient;

/**
 * Reuses vanilla integrated-server bootstrap and intercepts only the local-login step.
 * The ordinary Play path has no armed request and passes through unchanged.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Unique private boolean premapped$interceptLogin;

    /** Binds this invocation to the exact world requested by a PreMapped menu action. */
    @Inject(method = "startIntegratedServer", at = @At("HEAD"))
    private void premapped$claimLaunch(String name, LevelStorage.Session session,
                                      ResourcePackManager packs, SaveLoader loader,
                                      boolean newWorld, CallbackInfo callback) {
        premapped$interceptLogin = PreMappedClient.claimLaunch(name);
    }

    /** Cancels before binding the local connection, after vanilla has initialized its server. */
    @Inject(method = "startIntegratedServer", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/ServerNetworkIo;bindLocal()Ljava/net/SocketAddress;"), cancellable = true)
    private void premapped$openMonitor(String name, LevelStorage.Session session,
                                      ResourcePackManager packs, SaveLoader loader,
                                      boolean newWorld, CallbackInfo callback) {
        if (premapped$interceptLogin) {
            premapped$interceptLogin = false;
            PreMappedClient.enter(((MinecraftClient) (Object) this).getServer());
            callback.cancel();
        }
    }

    /** Prevents a cancelled or failed launch request from leaking into a later Play action. */
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void premapped$clearCancelledRequest(Screen screen, CallbackInfo callback) {
        PreMappedClient.returnedToMenu(screen);
    }
}
