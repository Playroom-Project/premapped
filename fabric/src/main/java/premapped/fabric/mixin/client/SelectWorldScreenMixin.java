package premapped.fabric.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import premapped.fabric.client.PreMappedClient;

/** Adds existing-world Resume while preserving vanilla's normal load and backup checks. */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
    @Shadow private WorldListWidget levelList;
    @Unique private ButtonWidget premapped$open;

    /** Required only to satisfy the superclass contract of this screen mixin. */
    protected SelectWorldScreenMixin(Text title) { super(title); }

    /** Places a compact button in the header rather than covering world-list entries. */
    @Inject(method = "init", at = @At("TAIL"))
    private void premapped$addButton(CallbackInfo callback) {
        premapped$open = addDrawableChild(ButtonWidget.builder(Text.literal("PreMapped"), button -> {
            var selected = levelList.getSelectedOrNull();
            if (selected instanceof WorldListWidget.WorldEntry entry && entry.isAvailable()) {
                // The vanilla class is final; Mixin adds this interface at runtime.
                String name = ((WorldEntryAccessor) (Object) entry).premapped$getLevel().getName();
                PreMappedClient.arm(name);
                client.createIntegratedServerLoader().start(this, name);
            }
        }).dimensions(width - 100, 4, 96, 20).build());
        premapped$open.active = false;
    }

    /** Keeps button availability synchronized with the currently selected world. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void premapped$updateButton(CallbackInfo callback) {
        if (premapped$open != null) {
            premapped$open.active = levelList.getSelectedOrNull() instanceof WorldListWidget.WorldEntry entry
                    && entry.isAvailable();
        }
    }
}
