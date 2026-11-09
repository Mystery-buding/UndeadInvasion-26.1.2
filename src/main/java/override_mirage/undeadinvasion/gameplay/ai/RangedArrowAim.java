package override_mirage.undeadinvasion.gameplay.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/** 弓与弩共用箭矢初速和下坠补偿，避免相同索敌距离下弩箭提前落地。 */
public final class RangedArrowAim {
    private RangedArrowAim() {}

    public static float velocity(double horizontalDistance) {
        return (float) Math.min(3.0, 1.6 + horizontalDistance * 0.05);
    }

    public static Vec3 direction(LivingEntity shooter, LivingEntity target, Projectile arrow, float velocity) {
        double xd = target.getX() - shooter.getX();
        double yd = target.getY(1.0 / 3.0) - arrow.getY();
        double zd = target.getZ() - shooter.getZ();
        double flightTicks = Math.sqrt(xd * xd + zd * zd) / velocity;
        double dropCompensation = Math.min(0.5 * 0.05 * flightTicks * flightTicks * 1.25, 8.0);
        return new Vec3(xd, yd + dropCompensation, zd);
    }
}
