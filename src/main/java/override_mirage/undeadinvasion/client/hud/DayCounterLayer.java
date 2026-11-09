package override_mirage.undeadinvasion.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.GuiLayer;
import override_mirage.undeadinvasion.client.ClientDayCounter;

/**
 * 左上角的独立天数显示。
 *
 * <p>数值来自服务端下发的 {@link ClientDayCounter}，而不是客户端读世界时间推算：
 * 这样原版时间被任何指令或模组改写时，显示值仍保持模组自己的计数。
 */
public final class DayCounterLayer implements GuiLayer {
    public static final DayCounterLayer INSTANCE = new DayCounterLayer();

    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private DayCounterLayer() {}

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        // 与原版 HUD 行为一致：F1 隐藏界面时一并隐藏。
        if (minecraft.options.hideGui) {
            return;
        }
        // F3 调试界面占用同一角落，避免文字重叠。
        if (minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }

        Component text = Component.translatable("hud.undeadinvasion.day", ClientDayCounter.getDay());
        graphics.text(minecraft.font, text, MARGIN_X, MARGIN_Y, TEXT_COLOR);
    }
}
