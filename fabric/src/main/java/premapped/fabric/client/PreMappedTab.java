package premapped.fabric.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tab.GridScreenTab;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

/** Adds a minimal fourth Create World tab while retaining vanilla's settings and datapacks. */
public final class PreMappedTab extends GridScreenTab {
    /** Builds accessible widgets; the callback invokes the normal vanilla creation workflow. */
    public PreMappedTab(Runnable createWithPreMapped) {
        super(Text.literal("PreMapped"));
        var renderer = MinecraftClient.getInstance().textRenderer;
        grid.getMainPositioner().margin(6);
        grid.add(new TextWidget(Text.translatable("premapped.setup.area"), renderer), 0, 0);
        grid.add(new TextWidget(Text.translatable("premapped.setup.playerless"), renderer), 1, 0);
        grid.add(ButtonWidget.builder(Text.translatable("premapped.setup.create"),
                button -> createWithPreMapped.run()).width(240).build(), 2, 0);
    }
}
