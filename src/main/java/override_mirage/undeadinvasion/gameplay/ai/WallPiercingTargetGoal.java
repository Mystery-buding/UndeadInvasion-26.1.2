package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 穿墙索敌：僵尸可以无视视线锁定玩家、村民、铁傀儡与其他模组的生物。
 *
 * <p>第 10 天起生效。只有当前没有目标时才会搜索，因此不会覆盖受伤反击等已有行为；
 * 搜索到目标后直接把目标写进 {@code Mob#setTarget}，由原版近战目标负责追击。
 *
 * <p>与破门配合：村民躲在房子里时，僵尸能锁定屋内目标并寻路到门口，再触发破门。
 */
public final class WallPiercingTargetGoal extends Goal {
    /** 两次搜索之间的间隔（tick）。 */
    private static final int SCAN_INTERVAL = 20;

    private final Mob mob;
    private int cooldown;
    private LivingEntity candidate;

    public WallPiercingTargetGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (DayCounterData.dayOf(level) < UndeadConfig.settings().siegeDay()) {
            return false;
        }

        LivingEntity current = this.mob.getTarget();
        if (current != null && current.isAlive()) {
            return false;
        }

        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        this.cooldown = SCAN_INTERVAL;

        this.candidate = findTarget(level);
        return this.candidate != null;
    }

    @Override
    public boolean canContinueToUse() {
        // 一次性目标：写入目标后立刻结束，释放 TARGET 控制位，
        // 否则会长期占用该 Flag，导致受伤反击等原版目标无法启动。
        return false;
    }

    @Override
    public void start() {
        this.mob.setTarget(this.candidate);
        this.candidate = null;
    }

    private LivingEntity findTarget(ServerLevel level) {
        double range = UndeadConfig.settings().siegeRange();
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                this.mob.getBoundingBox().inflate(range), this::isPrey);

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : candidates) {
            double distance = entity.distanceToSqr(this.mob);
            if (distance < nearestDistance) {
                nearest = entity;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private boolean isPrey(LivingEntity entity) {
        if (entity == this.mob || !entity.isAlive()) {
            return false;
        }
        if (UndeadCombat.isModdedMob(entity)) {
            return UndeadCombat.isFactionMember(this.mob)
                    && this.mob.canAttack(entity) && !this.mob.isAlliedTo(entity);
        }
        if (entity instanceof Player player) {
            return !player.isCreative() && !player.isSpectator();
        }
        return entity instanceof AbstractVillager || entity instanceof IronGolem;
    }
}
