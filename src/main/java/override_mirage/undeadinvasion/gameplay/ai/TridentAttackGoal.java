package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;

/**
 * 三叉戟的远程用法：在 5 格以外把三叉戟掷向目标。
 *
 * <p>三叉戟在本模组里是「近战/远程通用」武器，近距离交给近战目标处理，这里只负责远距离投掷。
 * 投掷不会消耗手里的三叉戟（与原版溺尸一致），因此不需要重新装备。
 */
public final class TridentAttackGoal extends Goal {
    /** 进入近战的距离（格），与武器切换阈值保持一致。 */
    private static final double MELEE_DISTANCE = 5.0;
    /** 两次投掷之间的间隔（tick）。 */
    private static final int THROW_COOLDOWN = 60;

    private final Mob mob;
    private final double attackRadiusSqr;
    private int cooldown;

    public TridentAttackGoal(Mob mob) {
        this(mob, (float) Math.max(15.0, UndeadConfig.settings().siegeRange()));
    }

    public TridentAttackGoal(Mob mob, float attackRadius) {
        this.mob = mob;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.getMainHandItem().getItem() instanceof TridentItem)) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive() || ProjectileImmunity.shouldUseMelee(this.mob, target)) {
            return false;
        }
        return this.mob.distanceToSqr(target) > MELEE_DISTANCE * MELEE_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
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
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive() || !(this.mob.getMainHandItem().getItem() instanceof TridentItem)
                || ProjectileImmunity.shouldUseMelee(this.mob, target)) {
            return;
        }

        double distanceSqr = this.mob.distanceToSqr(target);
        if (distanceSqr <= MELEE_DISTANCE * MELEE_DISTANCE) {
            return;
        }
        boolean canThrow = distanceSqr <= this.attackRadiusSqr && this.mob.getSensing().hasLineOfSight(target);
        if (canThrow) {
            this.mob.getNavigation().stop();
        } else {
            this.mob.getNavigation().moveTo(target, 1.0);
        }
        RangedAttackFacing.faceTarget(this.mob, target);

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }
        if (canThrow) {
            this.throwTrident(target);
            this.cooldown = THROW_COOLDOWN;
        }
    }

    private void throwTrident(LivingEntity target) {
        ItemStack tridentStack = this.mob.getMainHandItem().copy();
        ThrownTrident trident = new ThrownTrident(this.mob.level(), this.mob, tridentStack);

        double distance = target.position().subtract(this.mob.position()).horizontalDistance();
        float velocity = RangedArrowAim.velocity(distance);
        Vec3 direction = RangedTridentAim.direction(this.mob, target, trident, velocity);

        if (this.mob.level() instanceof ServerLevel serverLevel) {
            Projectile.spawnProjectileUsingShoot(trident, serverLevel, tridentStack,
                    direction.x, direction.y, direction.z, velocity,
                    14.0F - serverLevel.getDifficulty().getId() * 4.0F);
        }
        this.mob.playSound(SoundEvents.TRIDENT_THROW.value(), 1.0F,
                1.0F / (this.mob.getRandom().nextFloat() * 0.4F + 0.8F));
    }
}
