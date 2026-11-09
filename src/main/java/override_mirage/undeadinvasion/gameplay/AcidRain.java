package override_mirage.undeadinvasion.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.network.AcidRainSync;
import override_mirage.undeadinvasion.world.AcidRainData;
import override_mirage.undeadinvasion.world.DayCounterData;
import override_mirage.undeadinvasion.world.StormState;
import override_mirage.undeadinvasion.world.StormType;

/** 特殊降雨（酸雨 / 硫磺雨）的服务端驱动：只在风暴期间每秒检查已加载生物。 */
public final class AcidRain {
    public static final int DAMAGE_INTERVAL = 20;
    public static final float DAMAGE = 1.0F;
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, "acid_rain"));
    /** 硫磺雨每秒给暴露生物续的燃烧秒数。 */
    public static final int IGNITE_SECONDS = 3;

    /** 管理指令的默认时长（秒），与 {@link AcidRainData#DURATION} 保持一致。 */
    public static final int DEFAULT_DURATION_SECONDS = AcidRainData.DURATION / 20;

    private AcidRain() {}

    public static boolean isActive(MinecraftServer server) {
        return activeType(server).isActive();
    }

    /** 当前生效的风暴类型；天气已放晴或风暴已结束时返回 {@link StormType#NONE}。 */
    public static StormType activeType(MinecraftServer server) {
        return activeType(AcidRainData.get(server),
                server.overworld().getGameTime(), server.getWeatherData().isRaining());
    }

    static StormType activeType(AcidRainData data, long gameTime, boolean raining) {
        return raining ? data.activeType(gameTime) : StormType.NONE;
    }

    /** 剩余刻数；没有进行中的风暴或天气已放晴时返回 0。 */
    public static long remainingTicks(MinecraftServer server) {
        return remainingTicks(AcidRainData.get(server),
                server.overworld().getGameTime(), server.getWeatherData().isRaining());
    }

    /** 包内可见的重载：允许 GameTest 传入隔离数据而不触碰真实存档。 */
    static long remainingTicks(AcidRainData data, long gameTime, boolean raining) {
        if (!data.hasStorm() || !raining) {
            return 0L;
        }
        return Math.max(0L, data.endsAt() - gameTime);
    }

    /**
     * 管理指令 / 自动触发：立刻开始一场指定类型的风暴。
     *
     * <p>与另一种特殊降雨互斥：会覆盖进行中的风暴。不受 {@link AcidRainData#START_DAY}
     * 天数门槛、当日概率与天气循环开关影响，并立即把状态广播给客户端。
     */
    public static void start(MinecraftServer server, StormType type, int durationTicks) {
        start(server, AcidRainData.get(server), DayCounterData.dayOf(server.overworld()), type, durationTicks);
    }

    /** 包内可见的重载：允许 GameTest 传入隔离的 {@link AcidRainData} 实例，不污染真实存档。 */
    static void start(MinecraftServer server, AcidRainData data, long day, StormType type, int durationTicks) {
        data.start(server.overworld().getGameTime(), day, durationTicks, type);
        server.setWeatherParameters(0, durationTicks, true, false);
        StormState.set(type);
        AcidRainSync.broadcast(server, type);
    }

    /** 兼容旧调用：默认酸雨。 */
    public static void start(MinecraftServer server, int durationTicks) {
        start(server, StormType.ACID, durationTicks);
    }

    static void start(MinecraftServer server, AcidRainData data, long day, int durationTicks) {
        start(server, data, day, StormType.ACID, durationTicks);
    }

    /**
     * 管理指令：立刻停止风暴并放晴。
     *
     * @return 是否确实停掉了一场进行中的风暴
     */
    public static boolean stop(MinecraftServer server) {
        return stop(server, AcidRainData.get(server));
    }

    static boolean stop(MinecraftServer server, AcidRainData data) {
        if (!data.hasStorm()) {
            return false;
        }
        data.stop();
        server.setWeatherParameters(0, 0, false, false);
        StormState.set(StormType.NONE);
        AcidRainSync.broadcast(server, StormType.NONE);
        return true;
    }

    public static void tick(MinecraftServer server, long day) {
        tick(server, AcidRainData.get(server), day, server.overworld().getGameTime(), server.overworld().getRandom());
    }

    static void tick(MinecraftServer server, AcidRainData data, long day, long gameTime, RandomSource random) {
        StormType previous = data.hasStorm() ? data.type() : StormType.NONE;
        // 让 common 的 isInRain mixin 在专用服务端也能读到权威类型。
        StormState.set(previous);
        // 天数不足只会掐掉自然风暴；指令强制触发的风暴不受门槛约束，否则下一 tick 就会消失。
        boolean belowThreshold = !data.isForced() && day < AcidRainData.START_DAY;
        if (previous.isActive() && (belowThreshold || !data.isActive(gameTime) || !server.getWeatherData().isRaining())) {
            if (belowThreshold || !data.isActive(gameTime)) {
                server.setWeatherParameters(0, 0, false, false);
            }
            data.stop();
        }
        // 与原版天气循环开关一致；关闭循环时不自动生成新风暴。抽中类型后按类型开雨。
        if (server.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
            StormType rolled = data.rollForDay(day, gameTime, random);
            if (rolled.isActive()) {
                server.setWeatherParameters(0, AcidRainData.DURATION, true, false);
            }
        }
        StormType current = data.activeType(gameTime);
        StormState.set(current);
        if (previous != current) {
            AcidRainSync.broadcast(server, current);
        }
        if (current == StormType.NONE || gameTime % DAMAGE_INTERVAL != 0L) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.isRaining()) {
                continue;
            }
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living) {
                    affectExposedEntity(level, living, current);
                }
            }
        }
    }

    /** 按风暴类型施加效果：酸雨扣血，硫磺雨点燃。两者都作用于所有生物（含亡灵）。 */
    static boolean affectExposedEntity(ServerLevel level, LivingEntity entity, StormType type) {
        if (!entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        BlockPos feet = entity.blockPosition();
        BlockPos head = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
        if (!level.isRainingAt(feet) && !level.isRainingAt(head)) {
            return false;
        }
        if (type == StormType.SULFUR) {
            // 原版 onFire 伤害由实体自身 tick 产生；这里只负责持续点燃。
            entity.igniteForSeconds(IGNITE_SECONDS);
            return true;
        }
        return entity.hurtServer(level, level.damageSources().source(DAMAGE_TYPE), DAMAGE);
    }

    /** 兼容旧 GameTest 调用：等价于酸雨伤害。 */
    static boolean damageExposedEntity(ServerLevel level, LivingEntity entity) {
        return affectExposedEntity(level, entity, StormType.ACID);
    }
}
