package override_mirage.undeadinvasion.gameplay;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import override_mirage.undeadinvasion.gameplay.ai.RangedArrowAim;
import override_mirage.undeadinvasion.gameplay.ai.RangedAttackFacing;

/** 只对本模组发射器标记的弹药应用阵营规则，普通药水和末影龙攻击保持原样。 */
public final class DispenserProjectiles {
    private static final String AMMO_TAG = "undeadinvasion:dispenser_ammo";
    private static final String SUPPORT_SHOT_TAG = "undeadinvasion:dispenser_support_shot";
    private static final String CLOUD_TAG = "undeadinvasion:dispenser_dragon_cloud";
    private static final DispenserAmmo[] AMMO = DispenserAmmo.values();
    private static final double POTION_INERTIA = 0.99F;

    private DispenserProjectiles() {}

    public static @Nullable DispenserAmmo ammo(Entity entity) {
        if (!(entity instanceof ThrownSplashPotion || entity instanceof DragonFireball || entity instanceof SmallFireball)) {
            return null;
        }
        int index = entity.getPersistentData().getIntOr(AMMO_TAG, -1);
        return index >= 0 && index < AMMO.length ? AMMO[index] : null;
    }

    public static boolean canAffect(Projectile projectile, LivingEntity entity) {
        DispenserAmmo ammo = ammo(projectile);
        if (ammo == null) {
            return true;
        }
        boolean vanillaUndead = entity.isAlive() && UndeadCombat.isFactionMember(entity);
        return switch (ammo) {
            case RESISTANCE, SPEED, STRENGTH -> vanillaUndead;
            case INSTANT_DAMAGE -> vanillaUndead || DispenserUndead.isPotionEnemy(livingOwner(projectile), entity);
            case WEAKNESS, SLOWNESS -> DispenserUndead.isPotionEnemy(livingOwner(projectile), entity);
            case DRAGON_BREATH, FIREBALL -> DispenserUndead.isEnemy(livingOwner(projectile), entity);
        };
    }

    public static boolean isSupportShot(Projectile projectile) {
        DispenserAmmo ammo = ammo(projectile);
        // 旧存档里的瞬间伤害弹全部用于治疗，没有新标记时沿用支援用途。
        return ammo != null && ammo.isSupport() && projectile.getPersistentData().getBooleanOr(SUPPORT_SHOT_TAG, true);
    }

    /** 碰撞按本次发射用途筛选，喷溅效果另按接收者的亡灵类型筛选。 */
    public static boolean canHit(Projectile projectile, LivingEntity entity) {
        DispenserAmmo ammo = ammo(projectile);
        return ammo == null || (isSupportShot(projectile) ? DispenserUndead.isSupportAlly(entity)
                : canAttack(livingOwner(projectile), ammo, entity));
    }

    public static boolean canAttack(@Nullable LivingEntity owner, DispenserAmmo ammo, LivingEntity target) {
        return switch (ammo) {
            case INSTANT_DAMAGE, WEAKNESS, SLOWNESS -> DispenserUndead.isPotionEnemy(owner, target);
            case DRAGON_BREATH, FIREBALL -> DispenserUndead.isEnemy(owner, target);
            case RESISTANCE, SPEED, STRENGTH -> false;
        };
    }

    private static @Nullable LivingEntity livingOwner(Projectile projectile) {
        return projectile.getOwner() instanceof LivingEntity owner ? owner : null;
    }

    public static @Nullable Projectile fire(Mob mob, DispenserAmmo ammo, LivingEntity target) {
        boolean supportShot = ammo.isSupport() && DispenserUndead.isSupportAlly(target);
        if (!(mob.level() instanceof ServerLevel level) || !DispenserUndead.isArmed(mob)
                || (!supportShot && !canAttack(mob, ammo, target))) {
            return null;
        }
        RangedAttackFacing.faceTarget(mob, target);
        Vec3 direction = target.position().add(0.0, target.getBbHeight() * 0.4, 0.0)
                .subtract(new Vec3(mob.getX(), mob.getEyeY(), mob.getZ()));
        Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z).normalize();
        Vec3 muzzle = new Vec3(mob.getX(), mob.getEyeY(), mob.getZ()).add(horizontal.scale(0.65));
        Projectile projectile;
        if (ammo.effect() != null) {
            ThrownSplashPotion potion = new ThrownSplashPotion(level, mob, ammo.potionStack());
            potion.setPos(muzzle);
            float speed = RangedArrowAim.velocity(muzzle.distanceTo(target.position()));
            Vec3 velocity = potionVelocity(potion, target, speed);
            potion.shoot(velocity.x, velocity.y, velocity.z, speed, 0.0F);
            projectile = potion;
        } else {
            direction = target.position().add(0.0, target.getBbHeight() * 0.4, 0.0).subtract(muzzle).normalize();
            projectile = ammo == DispenserAmmo.DRAGON_BREATH
                    ? new DragonFireball(level, mob, direction) : new SmallFireball(level, mob, direction);
            projectile.setPos(muzzle);
            projectile.setDeltaMovement(direction.scale(1.2));
        }
        projectile.getPersistentData().putInt(AMMO_TAG, ammo.ordinal());
        projectile.getPersistentData().putBoolean(SUPPORT_SHOT_TAG, supportShot);
        if (!level.addFreshEntity(projectile)) {
            return null;
        }
        level.levelEvent(LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH, mob.blockPosition(), 0);
        return projectile;
    }

    /** 药水先受重力和阻力再移动，与箭矢的 tick 顺序不同。 */
    private static Vec3 potionVelocity(ThrownSplashPotion potion, LivingEntity target, float speed) {
        Vec3 offset = target.position().add(0.0, target.getBbHeight() / 3.0, 0.0).subtract(potion.position());
        double previous = 0.0;
        for (double time = 0.5; time <= 120.0; time += 0.5) {
            if (requiredVelocity(offset, potion.getGravity(), time).lengthSqr() <= speed * speed) {
                double low = previous;
                double high = time;
                for (int step = 0; step < 24; step++) {
                    double middle = (low + high) * 0.5;
                    if (requiredVelocity(offset, potion.getGravity(), middle).lengthSqr() > speed * speed) {
                        low = middle;
                    } else {
                        high = middle;
                    }
                }
                return requiredVelocity(offset, potion.getGravity(), high);
            }
            previous = time;
        }
        return offset;
    }

    private static Vec3 requiredVelocity(Vec3 offset, double gravity, double ticks) {
        double travel = POTION_INERTIA * (1.0 - Math.pow(POTION_INERTIA, ticks)) / (1.0 - POTION_INERTIA);
        double drop = gravity * POTION_INERTIA * (ticks - travel) / (1.0 - POTION_INERTIA);
        return new Vec3(offset.x / travel, (offset.y + drop) / travel, offset.z / travel);
    }

    public static void prepareDragonCloud(DragonFireball fireball, AreaEffectCloud cloud) {
        if (ammo(fireball) == DispenserAmmo.DRAGON_BREATH) {
            cloud.getPersistentData().putBoolean(CLOUD_TAG, true);
            // 不使用瞬间伤害药水伤害云，否则其他模组的亡灵也会被反向治疗。
            cloud.setPotionContents(PotionContents.EMPTY);
        }
    }

    public static void tickDragonCloud(AreaEffectCloud cloud, ServerLevel level) {
        if (cloud.tickCount % 20 != 0 || cloud.isRemoved() || cloud.isWaiting()
                || !cloud.getPersistentData().getBooleanOr(CLOUD_TAG, false)) {
            return;
        }
        float radius = cloud.getRadius();
        var damage = level.damageSources().source(DamageTypes.DRAGON_BREATH, cloud, cloud.getOwner());
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, cloud.getBoundingBox(),
                target -> DispenserUndead.isEnemy(cloud.getOwner(), target))) {
            double dx = entity.getX() - cloud.getX();
            double dz = entity.getZ() - cloud.getZ();
            if (dx * dx + dz * dz <= radius * radius) {
                entity.hurtServer(level, damage, 6.0F);
            }
        }
    }
}
