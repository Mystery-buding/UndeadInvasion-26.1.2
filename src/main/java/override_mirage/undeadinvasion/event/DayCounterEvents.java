package override_mirage.undeadinvasion.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.AcidRain;
import override_mirage.undeadinvasion.network.DaySync;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 独立天数的服务端驱动：按真实游戏刻推进、睡觉结算、登录同步。
 *
 * <p>这里刻意不读取世界时钟值做天数换算，因此 {@code /time set}、{@code /time add}
 * 或其它模组改写原版时间都不会改变天数，只会改变昼夜外观。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class DayCounterEvents {
    private DayCounterEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        DayCounterData data = DayCounterData.get(server);
        if (data.advance(server.overworld().getGameTime())) {
            notifyChanged(server, data);
        }
        AcidRain.tick(server, data.getDay());
    }

    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        DayCounterData data = DayCounterData.get(server);
        data.ensureInitialized(server.overworld().getGameTime());
        if (data.onSleep()) {
            notifyChanged(server, data);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }

        // 这个事件抛异常会让服务端以「无效的玩家数据」断开玩家，同步失败不应影响进服。
        try {
            DayCounterData data = DayCounterData.get(server);
            data.ensureInitialized(server.overworld().getGameTime());
            DaySync.sendTo(player, data.getDay());
        } catch (Exception e) {
            UndeadInvasion.LOGGER.warn("Failed to sync undead invasion day to {}", player.getPlainTextName(), e);
        }
    }

    /**
     * 天数变化后的统一收尾：刷新客户端显示，并把已加载的亡灵同步到新档位。
     *
     * <p>指令修改天数时也走这里，保证显示与实体装备同时更新。
     */
    public static void notifyChanged(MinecraftServer server, DayCounterData data) {
        DaySync.broadcast(server, data.getDay());
        MobBackpackHandler.resyncLoaded(server);
    }
}
