package override_mirage.undeadinvasion.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 天数下发的统一出口，命令与事件共用。
 */
public final class DaySync {
    private DaySync() {}

    public static void sendTo(ServerPlayer player, long day) {
        // 纯原版客户端不认识这个通道，跳过以免发无效载荷。
        if (!player.connection.hasChannel(DaySyncPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new DaySyncPayload(day));
    }

    public static void broadcast(MinecraftServer server, long day) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, day);
        }
    }
}
