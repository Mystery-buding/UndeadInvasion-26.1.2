package override_mirage.undeadinvasion.client;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.client.hud.DayCounterLayer;
import override_mirage.undeadinvasion.world.StormType;

/**
 * 客户端初始化：向 HUD 图层管理器注册自定义图层。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    public static final Identifier DAY_COUNTER_LAYER = Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, "day_counter");

    private ClientSetup() {}

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAcidRain.set(StormType.NONE);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 排在原版经验条之后：位于老板条、聊天栏、标题之下，不会盖住它们。
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_LEVEL, DAY_COUNTER_LAYER, DayCounterLayer.INSTANCE);
    }
}
