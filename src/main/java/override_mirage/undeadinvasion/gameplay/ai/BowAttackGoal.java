package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;

/**
 * 通用弓攻击目标，供没有远程 AI 的亡灵（如僵尸）使用。
 *
 * <p>行为对齐原版 {@code RangedBowAttackGoal}：进入射程且有视线时站定拉弓，拉满 20 tick 后射出，
 * 射击间隔 40 tick；目标太远则持续靠近。原版这个目标要求实体实现 {@code RangedAttackMob}，
 * 僵尸没有实现，因此这里自行完成箭矢生成与发射。
 *
 * <p>骷髅不注册本目标——它自带的 {@code RangedBowAttackGoal} 已能正常使用弓。
 */
public final class BowAttackGoal extends Goal {
    /** 拉满一箭所需 tick。 */
    private static final int DRAW_TICKS = 20;
    /** 两次射击之间的间隔。 */
    private static final int ATTACK_INTERVAL = 40;

    private final Mob mob;
    private final double speedModifier;
    private final float attackRadiusSqr;

    private int attackTime = -1;
    private int seeTime;

    public BowAttackGoal(Mob mob, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.hasTarget() && this.isHoldingBow();
    }

    @Override
    public boolean canContinueToUse() {
        return (this.hasTarget() || !this.mob.getNavigation().isDone()) && this.isHoldingBow();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        this.mob.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }
        if (!this.isHoldingBow()) {
            this.mob.stopUsingItem();
            return;
        }

        boolean lineOfSight = this.mob.getSensing().hasLineOfSight(target);
        if (lineOfSight != this.seeTime > 0) {
            this.seeTime = 0;
        }
        this.seeTime += lineOfSight ? 1 : -1;

        if (this.mob.distanceToSqr(target) > (double) this.attackRadiusSqr || this.seeTime < 20) {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
        } else {
            this.mob.getNavigation().stop();
        }
        RangedAttackFacing.faceTarget(this.mob, target);

        if (this.mob.isUsingItem()) {
            if (!lineOfSight && this.seeTime < -60) {
                this.mob.stopUsingItem();
            } else if (lineOfSight) {
                int pullTicks = this.mob.getTicksUsingItem();
                if (pullTicks >= DRAW_TICKS) {
                    this.mob.stopUsingItem();
                    this.shoot(target, BowItem.getPowerForTime(pullTicks));
                    this.attackTime = ATTACK_INTERVAL;
                }
            }
        } else if (--this.attackTime <= 0 && this.seeTime >= -60) {
            this.mob.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    private void shoot(LivingEntity target, float power) {
        ItemStack bow = this.mob.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem)) {
            return;
        }

        ItemStack projectile = this.mob.getProjectile(bow);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this.mob, projectile, power, bow);
        if (bow.getItem() instanceof ProjectileWeaponItem weapon) {
            arrow = weapon.customArrow(arrow, projectile, bow);
        }

        float velocity = RangedArrowAim.velocity(target.position().subtract(this.mob.position()).horizontalDistance());
        Vec3 direction = RangedArrowAim.direction(this.mob, target, arrow, velocity);

        if (this.mob.level() instanceof ServerLevel serverLevel) {
            Projectile.spawnProjectileUsingShoot(arrow, serverLevel, projectile, direction.x,
                    direction.y, direction.z, velocity,
                    14.0F - serverLevel.getDifficulty().getId() * 4.0F);
        }
        this.mob.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (this.mob.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    private boolean hasTarget() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive();
    }

    private boolean isHoldingBow() {
        return this.mob.getMainHandItem().getItem() instanceof BowItem
                && !ProjectileImmunity.shouldUseMelee(this.mob);
    }
}
