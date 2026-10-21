package premapped.fabric.mixin.client;

import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Resolves an existing world's exact directory name without guessing its display name. */
@Mixin(WorldListWidget.WorldEntry.class)
public interface WorldEntryAccessor {
    /** Returns metadata already loaded by vanilla's world selection screen. */
    @Accessor("level")
    LevelSummary premapped$getLevel();
}
