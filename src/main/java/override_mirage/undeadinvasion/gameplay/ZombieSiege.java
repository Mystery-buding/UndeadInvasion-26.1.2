package override_mirage.undeadinvasion.gameplay;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.zombie.Zombie;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.ai.SiegeBreakDoorGoal;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 僵尸攻城：达到配置天数后解锁穿墙索敌与破门。
 *
 * <p>破门只使用 {@link SiegeBreakDoorGoal}，移除原版随机或旧存档附带的破门目标。
 * 原版目标不判断敌人是否存在，会绕过攻城条件拆掉空房子的门。
 * 攻城目标会先确认通向敌人的路线经过木门，再靠近并拆门。
 */
public final class ZombieSiege {
    private static final String ON_FOOT_TARGET_TAG = "undeadinvasion:siege_on_foot_target";

    private ZombieSiege() {}

    public static boolean isEnabled(Mob mob) {
        return mob.level() instanceof ServerLevel level
                && DayCounterData.dayOf(level) >= UndeadConfig.settings().siegeDay();
    }

    /** 已经为进门转为步兵的单位，追击同一目标期间不再自动上马。 */
    public static void keepOnFoot(Mob mob, LivingEntity target) {
        mob.getPersistentData().putString(ON_FOOT_TARGET_TAG, target.getUUID().toString());
    }

    public static boolean shouldStayOnFoot(Mob mob) {
        String siegeTarget = mob.getPersistentData().getStringOr(ON_FOOT_TARGET_TAG, "");
        if (siegeTarget.isEmpty()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (isEnabled(mob) && target != null && target.isAlive()
                && siegeTarget.equals(target.getUUID().toString())) {
            return true;
        }
        mob.getPersistentData().remove(ON_FOOT_TARGET_TAG);
        return false;
    }

    /** 按目标实际高度寻路并允许规划穿门路线，普通追击仍使用原本的寻路预算。 */
    public static void prepareNavigation(PathNavigation navigation) {
        if (navigation.canNavigateGround()) {
            navigation.setCanOpenDoors(true);
        }
        if (navigation instanceof GroundPathNavigation ground) {
            ground.setCanPathToTargetsBelowSurface(true);
        }
    }

    /**
     * 按天数启用攻城能力；可重复调用。
     */
    public static void apply(Zombie zombie, long day) {
        if (day < UndeadConfig.settings().siegeDay()) {
            return;
        }

        zombie.setCanBreakDoors(false);
        // 只开启寻路通行，拆门动作统一交给有目标和路线检查的攻城目标。
        prepareNavigation(zombie.getNavigation());

        // 区块重载会创建同 UUID 的新实例；按实例检查，不能用全局 UUID 集合跳过安装。
        if (zombie.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof SiegeBreakDoorGoal)) {
            return;
        }
        zombie.goalSelector.addGoal(1, new SiegeBreakDoorGoal(zombie));
    }
}
