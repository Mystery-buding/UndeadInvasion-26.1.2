package override_mirage.undeadinvasion.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.client.ClientAcidRain;
import override_mirage.undeadinvasion.world.StormType;

/** 下发当前特殊降雨类型；用一个 byte 传枚举 id，避免依赖注册表。 */
public record AcidRainPayload(StormType storm) implements CustomPacketPayload {
    public static final Type<AcidRainPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, "acid_rain"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AcidRainPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public AcidRainPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new AcidRainPayload(StormType.fromId(buffer.readByte()));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AcidRainPayload payload) {
                    buffer.writeByte(payload.storm().id());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AcidRainPayload payload, IPayloadContext context) {
        ClientAcidRain.set(payload.storm());
    }
}
