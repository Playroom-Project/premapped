package premapped.fabric.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import premapped.generation.JobState;

/**
 * Minimal playerless job monitor. Closing it always uses vanilla disconnect/save;
 * background generation during active gameplay is reserved for the performance milestone.
 */
public final class PreMappedScreen extends Screen {
    private ButtonWidget resume;
    private ButtonWidget pause;
    private ButtonWidget cancel;

    /** Opens with no generation started; the player explicitly presses Start or Resume. */
    public PreMappedScreen() { super(Text.translatable("premapped.title")); }

    /** Creates fixed, keyboard-navigable controls within the minimum supported GUI width. */
    @Override
    protected void init() {
        int left = width / 2 - 150;
        resume = addDrawableChild(ButtonWidget.builder(Text.translatable("premapped.resume"),
            button -> requestResume()).dimensions(left, height - 62, 96, 20).build());
        pause = addDrawableChild(ButtonWidget.builder(Text.translatable("premapped.pause"),
            button -> PreMappedClient.pause()).dimensions(left + 102, height - 62, 96, 20).build());
        cancel = addDrawableChild(ButtonWidget.builder(Text.translatable("premapped.cancel"),
            button -> requestCancel()).dimensions(left + 204, height - 62, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("premapped.stop"),
            button -> close()).dimensions(width / 2 - 150, height - 36, 300, 20).build());
        tick();
    }

    /** Enables actions only in states that the server-side state machine can accept. */
    @Override
    public void tick() {
        var status = PreMappedClient.snapshot();
        boolean ready = status != null && PreMappedClient.error().isEmpty();
        resume.active = ready && (status.state() == JobState.CREATED || status.state() == JobState.PAUSED
            || status.state() == JobState.FAILED);
        pause.active = ready && status.state() == JobState.RUNNING;
        cancel.active = ready && status.state() != JobState.CANCELLED && status.state() != JobState.PAUSING;
    }

    /** Shows retained checkpoint progress separately from this run's storage verification. */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xffffff);
        var status = PreMappedClient.snapshot();
        String line = status == null ? Text.translatable(PreMappedClient.error().isEmpty()
            ? "premapped.loading" : "premapped.init.failed").getString()
            : status.state() + "  Recorded: " + status.recordedCompleted() + " / " + status.total();
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(line), width / 2, 42,
            status != null && status.state() == JobState.FAILED ? 0xff7777 : 0xffffff);
        context.fill(width / 2 - 150, 61, width / 2 + 150, 73, 0xff333333);
        if (status != null) {
            int completedWidth = (int) (300L * status.recordedCompleted() / status.total());
            context.fill(width / 2 - 150, 61, width / 2 - 150 + completedWidth, 73, 0xff55aa88);
            if (status.active() != null) {
                context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Chunk " + status.active().x() + ", " + status.active().z()),
                    width / 2, 83, 0xbbbbbb);
            }
            context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("premapped.monitor.counts", status.existingThisRun(), status.generatedThisRun())
                    .append(" | Checked: " + status.checkedThisRun() + " / " + status.total()),
                width / 2, 96, 0xbbbbbb);
        }
        String failure = PreMappedClient.error();
        if (failure.isEmpty() && status != null) { failure = status.error(); }
        // Long native exceptions remain in the log and cannot cover the action buttons.
        context.enableScissor(width / 2 - 150, 112, width / 2 + 150, height - 68);
        if (!failure.isEmpty()) {
            context.drawTextWrapped(textRenderer, Text.literal(failure), width / 2 - 150,
                115, 300, 0xff7777);
        } else {
            context.drawTextWrapped(textRenderer, Text.translatable("premapped.monitor.note"),
                width / 2 - 150, 115, 300, 0xbbbbbb);
        }
        context.disableScissor();
        super.render(context, mouseX, mouseY, delta);
    }

    /** Shows explicit approval when worldgen inputs differ from the saved checkpoint. */
    private void requestResume() {
        resume.active = false;
        PreMappedClient.resume(false);
    }

    /** Opens approval only after the server has recomputed the actual current fingerprint. */
    public void confirmWorldgenChange() {
        client.setScreen(new ConfirmScreen(approved -> {
            client.setScreen(this);
            if (approved) { PreMappedClient.resume(true); }
        }, Text.translatable("premapped.changed.title"), Text.translatable("premapped.changed.message")));
    }

    /** Makes the scope of Cancel concrete: remove the job and retain world chunks. */
    private void requestCancel() {
        client.setScreen(new ConfirmScreen(approved -> {
            client.setScreen(this);
            if (approved) { PreMappedClient.cancel(); }
        }, Text.translatable("premapped.cancel.title"), Text.translatable("premapped.cancel.message")));
    }

    /** Disconnect's mixin first stops and joins our server, then vanilla clears client state. */
    @Override
    public void close() {
        client.disconnect(new TitleScreen());
        client.setScreen(new TitleScreen());
    }

    /** Prevents an ordinary GUI pause from freezing the playerless server's job pump. */
    @Override
    public boolean shouldPause() { return false; }
}
