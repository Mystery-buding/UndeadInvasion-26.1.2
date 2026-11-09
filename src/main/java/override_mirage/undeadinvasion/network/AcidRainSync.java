package override_mirage.undeadinvasion.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.AcidRain;
import override_mirage.undeadinvasion.world.StormType;

@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class AcidRainSync {
    private AcidRainSync() {}

    public static void sendTo(ServerPlayer player, StormType type) {
        if (player.connection.hasChannel(AcidRainPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, new AcidRainPayload(type));
        }
    }

    public static void broadcast(MinecraftServer server, StormType type) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, type);
        }
    }

    private static StormType current(MinecraftServer server) {
        return AcidRain.activeType(server);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                sendTo(player, current(server));
            }
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                sendTo(player, current(server));
            }
        }
    }
}
