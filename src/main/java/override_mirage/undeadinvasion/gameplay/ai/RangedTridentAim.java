package override_mirage.undeadinvasion.gameplay.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.Vec3;

/** 三叉戟与弓使用相同初速，并按原版逐 tick 阻力和重力计算低弧线。 */
public final class RangedTridentAim {
    private static final double INERTIA = 0.99;
    private static final int MAX_FLIGHT_TICKS = 120;

    private RangedTridentAim() {}

    public static Vec3 direction(LivingEntity shooter, LivingEntity target, ThrownTrident trident, float speed) {
        Vec3 offset = new Vec3(target.getX() - trident.getX(),
                target.getY(1.0 / 3.0) - trident.getY(), target.getZ() - trident.getZ());
        double speedSqr = speed * speed;
        double previousTime = 0.0;
        for (double time = 0.5; time <= MAX_FLIGHT_TICKS; time += 0.5) {
            if (requiredVelocity(offset, trident.getGravity(), time).lengthSqr() <= speedSqr) {
                double low = previousTime;
                double high = time;
                for (int step = 0; step < 24; step++) {
                    double middle = (low + high) * 0.5;
                    if (requiredVelocity(offset, trident.getGravity(), middle).lengthSqr() > speedSqr) {
                        low = middle;
                    } else {
                        high = middle;
                    }
                }
                return requiredVelocity(offset, trident.getGravity(), high);
            }
            previousTime = time;
        }
        return RangedArrowAim.direction(shooter, target, trident, speed);
    }

    private static Vec3 requiredVelocity(Vec3 offset, double gravity, double ticks) {
        double travel = (1.0 - Math.pow(INERTIA, ticks)) / (1.0 - INERTIA);
        double drop = gravity * (ticks - travel) / (1.0 - INERTIA);
        return new Vec3(offset.x / travel, (offset.y + drop) / travel, offset.z / travel);
    }
}
