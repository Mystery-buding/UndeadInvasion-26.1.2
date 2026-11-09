package override_mirage.undeadinvasion;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import override_mirage.undeadinvasion.config.UndeadConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Undead Invasion 主类。
 *
 * <p>客户端 HUD 通过 {@code @EventBusSubscriber} 自动注册，此处仅保留模组入口。
 */
@Mod(UndeadInvasion.MOD_ID)
public final class UndeadInvasion {
    public static final String MOD_ID = "undeadinvasion";
    public static final Logger LOGGER = LoggerFactory.getLogger("Undead Invasion");

    public UndeadInvasion(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, UndeadConfig.SPEC);
        LOGGER.debug("Undead Invasion initialised on {}", modContainer.getModId());
    }
}
