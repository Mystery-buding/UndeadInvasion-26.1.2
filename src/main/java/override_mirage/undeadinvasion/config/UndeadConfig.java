package override_mirage.undeadinvasion.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import override_mirage.undeadinvasion.UndeadInvasion;

/**
 * 模组配置：各装备档位的起始天数。
 *
 * <p>配置位于 {@code config/undeadinvasion-common.toml}。读取走 {@link #settings()} 返回的缓存快照，
 * 避免配置尚未加载时直接访问 {@code ModConfigSpec} 抛异常；配置加载/重载后再刷新快照。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class UndeadConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue LEATHER_DAY;
    private static final ModConfigSpec.IntValue COPPER_DAY;
    private static final ModConfigSpec.IntValue GOLD_DAY;
    private static final ModConfigSpec.IntValue CHAIN_DAY;
    private static final ModConfigSpec.IntValue IRON_DAY;
    private static final ModConfigSpec.IntValue DIAMOND_DAY;
    private static final ModConfigSpec.IntValue NETHERITE_DAY;
    private static final ModConfigSpec.IntValue SHIELD_DAY;
    private static final ModConfigSpec.IntValue MOUNT_CHANCE;
    private static final ModConfigSpec.IntValue CAMEL_KNIGHT_CHANCE;
    private static final ModConfigSpec.IntValue SIEGE_DAY;
    private static final ModConfigSpec.IntValue SIEGE_RANGE;

    private static volatile Settings settings = Settings.DEFAULTS;
    private static volatile boolean refreshed;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("各装备档位的起始天数。0 表示第 0 天即生效；数值需按从小到大的顺序填写。").push("day_tiers");
        LEATHER_DAY = builder.comment("皮革套 + 木制武器").defineInRange("leather", 10, 0, 100000);
        COPPER_DAY = builder.comment("铜制套 + 铜制武器").defineInRange("copper", 20, 0, 100000);
        GOLD_DAY = builder.comment("金制套 + 金制武器").defineInRange("gold", 30, 0, 100000);
        CHAIN_DAY = builder.comment("锁链套 + 铁制武器").defineInRange("chainmail", 40, 0, 100000);
        IRON_DAY = builder.comment("铁制套 + 铁制武器").defineInRange("iron", 50, 0, 100000);
        DIAMOND_DAY = builder.comment("钻石套 + 钻石武器").defineInRange("diamond", 60, 0, 100000);
        NETHERITE_DAY = builder.comment("下界合金套 + 下界合金武器").defineInRange("netherite", 70, 0, 100000);
        SHIELD_DAY = builder.comment("副手盾牌起始天数（达到该天数的档位才会配盾）")
                .defineInRange("shield", 30, 0, 100000);
        builder.pop();

        builder.comment("生成相关的概率，单位为百分比（0–100）").push("spawn");
        MOUNT_CHANCE = builder.comment("新生成亡灵直接骑乘坐骑的概率")
                .defineInRange("mount_chance", 5, 0, 100);
        CAMEL_KNIGHT_CHANCE = builder.comment("沙漠中尸壳/焦骸组成骆驼骑士的概率")
                .defineInRange("camel_knight_chance", 5, 0, 100);
        builder.pop();

        builder.comment("僵尸攻城相关设置").push("siege");
        SIEGE_DAY = builder.comment("僵尸从第几天开始穿墙索敌并破门")
                .defineInRange("siege_day", 10, 0, 100000);
        SIEGE_RANGE = builder.comment("穿墙索敌半径（格）")
                .defineInRange("siege_range", 32, 4, 64);
        builder.pop();
        SPEC = builder.build();
    }

    private UndeadConfig() {}

    /**
     * 当前生效的配置快照。
     *
     * <p>首次访问时尝试主动读取一次（配置可能尚未触发加载事件）；读取失败则继续使用默认值。
     */
    public static Settings settings() {
        if (!refreshed) {
            try {
                refresh();
            } catch (RuntimeException ignored) {
                // 配置还没加载完，先用默认值，等 Loading/Reloading 事件再刷新。
            }
        }
        return settings;
    }

    @SubscribeEvent
    public static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }

    @SubscribeEvent
    public static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }

    private static void refresh() {
        settings = new Settings(
                LEATHER_DAY.get(),
                COPPER_DAY.get(),
                GOLD_DAY.get(),
                CHAIN_DAY.get(),
                IRON_DAY.get(),
                DIAMOND_DAY.get(),
                NETHERITE_DAY.get(),
                SHIELD_DAY.get(),
                MOUNT_CHANCE.get(),
                CAMEL_KNIGHT_CHANCE.get(),
                SIEGE_DAY.get(),
                SIEGE_RANGE.get());
        refreshed = true;
    }

    /**
     * 配置快照。每次加载/重载都会生成新实例，调用方可用实例比较判断是否需要重建缓存。
     */
    public record Settings(long leatherDay, long copperDay, long goldDay, long chainDay,
                           long ironDay, long diamondDay, long netheriteDay, long shieldDay,
                           int mountChancePercent, int camelKnightChancePercent,
                           int siegeDay, int siegeRange) {
        public static final Settings DEFAULTS =
                new Settings(10L, 20L, 30L, 40L, 50L, 60L, 70L, 30L, 5, 5, 10, 32);

        /** 随机坐骑概率（0–1）。 */
        public float mountChance() {
            return this.mountChancePercent / 100.0F;
        }

        /** 骆驼骑士概率（0–1）。 */
        public float camelKnightChance() {
            return this.camelKnightChancePercent / 100.0F;
        }
    }
}
