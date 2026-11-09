package override_mirage.undeadinvasion.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.client.ClientDayCounter;

/**
 * 服务端 → 客户端的天数同步包。
 *
 * <p>天数由服务端计算并持久化，客户端只负责显示，因此原版时间被任何方式改写都不会影响显示值。
 */
public record DaySyncPayload(long day) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DaySyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, "day_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DaySyncPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_LONG, DaySyncPayload::day, DaySyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DaySyncPayload payload, IPayloadContext context) {
        ClientDayCounter.set(payload.day());
    }
}
