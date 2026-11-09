package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;

/**
 * 亡灵通用弩攻击目标。
 *
 * <p>原版没有「弩」版本的骷髅 AI：{@code AbstractSkeleton#reassessWeaponGoal} 只认 {@code BowItem}，
 * 拿弩会退回近战；且骷髅没有箭矢库存，{@code CrossbowItem#onUseTick} 的自动装填会失败。
 * 这里补上完整流程：站定蓄力 → 写入已装填状态 → 瞄准射击。
 *
 * <p>由 {@code MobBackpack} 在骷髅获得弩时注册一次，优先级高于原版近战目标（4），因此会优先进远程。
 */
public final class CrossbowAttackGoal extends Goal {
    private final Mob mob;
    private final double speedModifier;
    private final float attackRadiusSqr;

    private int seeTime;
    private int shootDelay;
    private int pathDelay;
    private boolean charging;

    public CrossbowAttackGoal(Mob mob, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.hasTarget() && this.isHoldingCrossbow();
    }

    @Override
    public boolean canContinueToUse() {
        return (this.hasTarget() || !this.mob.getNavigation().isDone()) && this.isHoldingCrossbow();
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
        this.shootDelay = 0;
        this.cancelCharge();
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }

        ItemStack crossbow = this.mob.getMainHandItem();
        if (!(crossbow.getItem() instanceof CrossbowItem) || ProjectileImmunity.shouldUseMelee(this.mob)) {
            this.cancelCharge();
            return;
        }
        RangedAttackFacing.faceTarget(this.mob, target);

        boolean lineOfSight = this.mob.getSensing().hasLineOfSight(target);
        this.seeTime = lineOfSight ? Math.min(this.seeTime + 1, 20) : Math.max(this.seeTime - 1, 0);

        if (CrossbowItem.isCharged(crossbow)) {
            this.cancelCharge();
            this.mob.getNavigation().stop();
            if (lineOfSight && --this.shootDelay <= 0) {
                this.shoot(target, crossbow);
                this.shootDelay = 20 + this.mob.getRandom().nextInt(20);
            }
            return;
        }

        boolean tooFar = this.mob.distanceToSqr(target) > (double) this.attackRadiusSqr || this.seeTime < 5;
        if (tooFar) {
            this.cancelCharge();
            if (--this.pathDelay <= 0) {
                this.mob.getNavigation().moveTo(target, this.speedModifier);
                this.pathDelay = 20 + this.mob.getRandom().nextInt(20);
            }
            return;
        }

        this.mob.getNavigation().stop();
        this.charge(crossbow);
    }

    private void charge(ItemStack crossbow) {
        if (!this.charging || !this.mob.isUsingItem()) {
            this.mob.startUsingItem(InteractionHand.MAIN_HAND);
            this.charging = true;
            return;
        }

        if (this.mob.getTicksUsingItem() >= CrossbowItem.getChargeDuration(crossbow, this.mob)) {
            this.mob.stopUsingItem();
            this.charging = false;
            this.shootDelay = 10 + this.mob.getRandom().nextInt(10);
            // 骷髅没有箭矢库存，原版装填必然失败，这里直接写入已装填状态。
            crossbow.set(DataComponents.CHARGED_PROJECTILES,
                    ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        }
    }

    private void shoot(LivingEntity target, ItemStack crossbow) {
        if (crossbow.getItem() instanceof CrossbowItem item) {
            double distance = target.position().subtract(this.mob.position()).horizontalDistance();
            // 原来的固定 1.0 初速无法覆盖 AI 的攻击半径，箭在到达远处目标前就会落地。
            boolean firework = crossbow.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY)
                    .contains(Items.FIREWORK_ROCKET);
            float velocity = firework ? 1.6F : RangedArrowAim.velocity(distance);
            item.performShooting(this.mob.level(), this.mob, InteractionHand.MAIN_HAND, crossbow, velocity,
                    14.0F - this.mob.level().getDifficulty().getId() * 4.0F, target);
        }
    }

    private void cancelCharge() {
        if (this.charging) {
            this.mob.stopUsingItem();
            this.charging = false;
        }
    }

    private boolean hasTarget() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive();
    }

    private boolean isHoldingCrossbow() {
        return this.mob.getMainHandItem().getItem() instanceof CrossbowItem
                && !ProjectileImmunity.shouldUseMelee(this.mob);
    }
}
