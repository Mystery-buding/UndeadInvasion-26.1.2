package override_mirage.undeadinvasion.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import override_mirage.undeadinvasion.UndeadInvasion;

/**
 * 网络载荷注册。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class NetworkSetup {
    private static final String PROTOCOL_VERSION = "1";

    private NetworkSetup() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
                .playToClient(DaySyncPayload.TYPE, DaySyncPayload.STREAM_CODEC, DaySyncPayload::handle)
                .playToClient(AcidRainPayload.TYPE, AcidRainPayload.STREAM_CODEC, AcidRainPayload::handle);
    }
}
