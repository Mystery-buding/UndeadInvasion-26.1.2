package override_mirage.undeadinvasion.gameplay;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import override_mirage.undeadinvasion.world.DayCounterData;

/** 第 10 天起，仅在本项目管理的亡灵佩戴期间保护头盔耐久，不修改物品组件。 */
public final class UndeadHelmetDurability {
    public static final long START_DAY = 10;

    private UndeadHelmetDurability() {}

    public static boolean isProtected(LivingEntity wearer) {
        return wearer instanceof Mob mob && MobBackpack.isCarrier(mob) && UndeadCombat.isFactionMember(mob)
                && mob.level() instanceof ServerLevel level && DayCounterData.dayOf(level) >= START_DAY;
    }
}
