package override_mirage.undeadinvasion.gameplay.ai;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jspecify.annotations.Nullable;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.DispenserAmmo;
import override_mirage.undeadinvasion.gameplay.DispenserProjectiles;
import override_mirage.undeadinvasion.gameplay.DispenserUndead;

/** 头顶发射器独立于手持武器工作，支援时也保留原本的敌对目标和移动任务。 */
public final class DispenserAttackGoal extends Goal {
    public static final int SHOT_INTERVAL = 40;
    private static final DispenserAmmo[] BUFFS = {
            DispenserAmmo.RESISTANCE, DispenserAmmo.SPEED, DispenserAmmo.STRENGTH};
    private static final DispenserAmmo[] ATTACKS = {
            DispenserAmmo.INSTANT_DAMAGE, DispenserAmmo.WEAKNESS, DispenserAmmo.SLOWNESS,
            DispenserAmmo.DRAGON_BREATH, DispenserAmmo.FIREBALL};
    private final Mob mob;
    private int cooldown;
    private int buffIndex;
    private boolean supportTurn = true;

    public DispenserAttackGoal(Mob mob) {
        this.mob = mob;
        this.cooldown = 20 + mob.getRandom().nextInt(21);
        // 不占用移动或观察控制位；仅出手时同步头部朝向，不打断破门和普通战斗。
    }

    @Override
    public boolean canUse() {
        return this.mob.isAlive() && DispenserUndead.isArmed(this.mob);
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
    public void tick() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }
        if (!(this.mob.level() instanceof ServerLevel level) || !this.canUse()) {
            return;
        }

        double range = Math.max(16.0, UndeadConfig.settings().siegeRange());
        double rangeSqr = range * range;
        // 每次准备发射时才查找队友；空闲时每秒重试，不逐 tick 扫描。
        List<Mob> allies = level.getEntitiesOfClass(Mob.class, this.mob.getBoundingBox().inflate(range),
                ally -> ally != this.mob && DispenserUndead.isSupportAlly(ally)
                        && this.mob.distanceToSqr(ally) <= rangeSqr && this.mob.hasLineOfSight(ally));
        Mob wounded = allies.stream().filter(ally -> ally.getHealth() < ally.getMaxHealth() - 0.5F)
                .min(Comparator.comparingDouble(ally -> ally.getHealth() / ally.getMaxHealth())).orElse(null);
        if (wounded != null) {
            this.shoot(DispenserAmmo.INSTANT_DAMAGE, wounded);
            return;
        }

        LivingEntity enemy = this.mob.getTarget();
        if (enemy != null && (!DispenserUndead.isEnemy(this.mob, enemy)
                || this.mob.distanceToSqr(enemy) > rangeSqr || !this.mob.hasLineOfSight(enemy))) {
            enemy = null;
        }
        Shot support = this.findBuff(allies);
        if (support != null && (this.supportTurn || enemy == null)) {
            if (this.shoot(support.ammo(), support.target())) {
                this.buffIndex = (this.buffIndex + 1) % BUFFS.length;
                this.supportTurn = false;
            }
        } else if (enemy != null) {
            if (this.attack(enemy)) {
                this.supportTurn = true;
            }
        } else {
            this.cooldown = 20;
        }
    }

    private boolean attack(LivingEntity target) {
        List<DispenserAmmo> attacks = Arrays.stream(ATTACKS)
                .filter(ammo -> DispenserProjectiles.canAttack(this.mob, ammo, target)).toList();
        if (attacks.isEmpty()) {
            this.cooldown = 20;
            return false;
        }
        return this.shoot(attacks.get(this.mob.getRandom().nextInt(attacks.size())), target);
    }

    private @Nullable Shot findBuff(List<Mob> allies) {
        for (int offset = 0; offset < BUFFS.length; offset++) {
            DispenserAmmo ammo = BUFFS[(this.buffIndex + offset) % BUFFS.length];
            Mob ally = allies.stream().filter(candidate -> {
                MobEffectInstance current = candidate.getEffect(ammo.effect());
                return current == null || current.getAmplifier() < DispenserAmmo.EFFECT_AMPLIFIER
                        || current.getAmplifier() == DispenserAmmo.EFFECT_AMPLIFIER && current.endsWithin(40);
            }).min(Comparator.comparingDouble(this.mob::distanceToSqr)).orElse(null);
            if (ally != null) {
                return new Shot(ammo, ally);
            }
        }
        return null;
    }

    private boolean shoot(DispenserAmmo ammo, LivingEntity target) {
        boolean fired = DispenserProjectiles.fire(this.mob, ammo, target) != null;
        this.cooldown = fired ? SHOT_INTERVAL : 20;
        return fired;
    }

    private record Shot(DispenserAmmo ammo, LivingEntity target) {}
}
