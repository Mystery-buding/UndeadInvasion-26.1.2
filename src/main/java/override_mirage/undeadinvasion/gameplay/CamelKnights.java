package override_mirage.undeadinvasion.gameplay;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.monster.skeleton.Parched;
import net.minecraft.world.entity.monster.zombie.Husk;
import org.jspecify.annotations.Nullable;
import override_mirage.undeadinvasion.config.UndeadConfig;

/**
 * 骆驼尸壳骑士：沙漠里的尸壳/焦骸有概率以「双人骑兵」形式出现。
 *
 * <p>骆驼尸壳有两个座位：
 * <ul>
 *   <li>前座尸壳——固定长矛（走 {@link MobBackpack} 的骑兵规则）。</li>
 *   <li>后座焦骸——固定弓（走射手规则）。</li>
 * </ul>
 *
 * <p>如果原始刷出的是焦骸，会额外生成一只尸壳坐前座，原焦骸坐后座；反之亦然。
 */
public final class CamelKnights {
    /** 默认组队概率，实际值来自配置。 */
    private static final float DEFAULT_CHANCE = 0.05F;

    /** 标记「这只实体是组队时补出来的」，避免它自己再触发一次组队。 */
    private static final String NO_MOUNT_TAG = "undeadinvasion:no_camel_mount";

    private CamelKnights() {}

    /**
     * 是否是被组队逻辑预留的实体（补出来的搭档），这类实体不应再触发任何坐骑判定。
     */
    public static boolean isReserved(Mob mob) {
        return mob.getPersistentData().getBooleanOr(NO_MOUNT_TAG, false);
    }

    /**
     * 尝试把刚生成的尸壳/焦骸变成骆驼骑士。
     *
     * @return 是否成功组队（成功时调用方应跳过其它坐骑逻辑）
     */
    public static boolean trySpawn(ServerLevel level, Mob original, long day) {
        if (isReserved(original)) {
            return false;
        }
        if (original.isPassenger() || original.isVehicle()) {
            return false;
        }

        boolean originalIsHusk = original instanceof Husk;
        boolean originalIsParched = original instanceof Parched;
        if (!originalIsHusk && !originalIsParched) {
            return false;
        }
        if (!DesertSpawns.isDesert(level, original.blockPosition())) {
            return false;
        }
        float chance = UndeadConfig.settings().camelKnightChance();
        if (chance <= 0.0F || level.getRandom().nextFloat() >= chance) {
            return false;
        }

        CamelHusk camel = EntityType.CAMEL_HUSK.create(level, EntitySpawnReason.JOCKEY);
        if (camel == null) {
            return false;
        }
        camel.snapTo(original.getX(), original.getY(), original.getZ(), original.getYRot(), 0.0F);
        camel.setPersistenceRequired();
        // 驯服 + 装鞍：不驯服会被原版 RunAroundLikeCrazyGoal 甩人。
        MobMounts.ensureMountReady(camel);
        if (!level.addFreshEntity(camel)) {
            return false;
        }

        Mob husk = originalIsHusk ? original : spawnPartner(level, EntityType.HUSK, original);
        Mob parched = originalIsParched ? original : spawnPartner(level, EntityType.PARCHED, original);
        if (husk == null || parched == null) {
            return false;
        }

        // 先上尸壳再上焦骸，确保座位顺序：0 = 前座（矛），1 = 后座（弓）。
        husk.startRiding(camel, true, true);
        parched.startRiding(camel, true, true);

        MobBackpack.sync(husk, day, husk.getRandom());
        MobBackpack.sync(parched, day, parched.getRandom());
        return true;
    }

    private static @Nullable Mob spawnPartner(ServerLevel level, EntityType<? extends Mob> type, Mob around) {
        Mob partner = type.create(level, EntitySpawnReason.JOCKEY);
        if (partner == null) {
            return null;
        }
        // 先打标记再入场，避免它自己的加入事件又触发一次组队。
        partner.getPersistentData().putBoolean(NO_MOUNT_TAG, true);
        partner.snapTo(around.getX(), around.getY(), around.getZ(), around.getYRot(), 0.0F);
        partner.setPersistenceRequired();
        return level.addFreshEntity(partner) ? partner : null;
    }
}
