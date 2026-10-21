package premapped.fabric.mixin.client;

import java.util.Arrays;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.tab.Tab;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import premapped.fabric.client.PreMappedClient;
import premapped.fabric.client.PreMappedTab;

/** Appends the PreMapped tab without replacing any existing vanilla or modded tabs. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Shadow @Final private WorldCreator worldCreator;

    /** Invokes vanilla creation so all selected settings and datapacks are retained. */
    @Shadow private void createLevel() { throw new AssertionError("Mixin shadow"); }

    /** Extends the tab array at its original construction point. */
    @ModifyArg(method = "init", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/widget/TabNavigationWidget$Builder;tabs([Lnet/minecraft/client/gui/tab/Tab;)Lnet/minecraft/client/gui/widget/TabNavigationWidget$Builder;"), index = 0)
    private Tab[] premapped$appendTab(Tab[] tabs) {
        Tab[] extended = Arrays.copyOf(tabs, tabs.length + 1);
        extended[tabs.length] = new PreMappedTab(() -> {
            PreMappedClient.arm(worldCreator.getWorldDirectoryName());
            createLevel();
        });
        return extended;
    }
}
