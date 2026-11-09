package override_mirage.undeadinvasion.gameplay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

/**
 * 亡灵盾牌的破盾冷却。
 *
 * <p>原版只给玩家维护物品冷却，亡灵需要自行记录禁止格挡的截止时间。
 * 盾牌保留在副手，冷却写入实体持久数据，避免装备补发或实体重载提前恢复格挡。
 */
public final class ShieldBreak {
    private static final String DISABLED_UNTIL_TAG = "undeadinvasion:shield_disabled_until";

    private ShieldBreak() {}

    /**
     * 禁用盾牌并立即结束当前的盾牌使用状态。
     *
     * @return 是否应用了破盾冷却
     */
    public static boolean breakShield(Mob mob, int ticks) {
        if (!(mob.level() instanceof ServerLevel) || ticks <= 0 || !mob.getOffhandItem().is(Items.SHIELD)) {
            return false;
        }

        CompoundTag data = mob.getPersistentData();
        long disabledUntil = mob.level().getGameTime() + ticks;
        data.putLong(DISABLED_UNTIL_TAG, Math.max(data.getLongOr(DISABLED_UNTIL_TAG, 0L), disabledUntil));
        if (mob.isUsingItem() && mob.getUseItem().is(Items.SHIELD)) {
            mob.stopUsingItem();
        }
        return true;
    }

    /**
     * 当前是否处于破盾状态。
     */
    public static boolean isBroken(Mob mob) {
        return mob.getPersistentData().getLongOr(DISABLED_UNTIL_TAG, 0L) > mob.level().getGameTime();
    }
}
