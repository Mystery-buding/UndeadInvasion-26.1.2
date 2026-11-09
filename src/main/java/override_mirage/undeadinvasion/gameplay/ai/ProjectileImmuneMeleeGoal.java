package override_mirage.undeadinvasion.gameplay.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;

/** 对投射物免疫的目标使用原版近战寻路，包括尚无备用近战武器的骷髅。 */
public final class ProjectileImmuneMeleeGoal extends MeleeAttackGoal {
    public ProjectileImmuneMeleeGoal(PathfinderMob mob) {
        super(mob, 1.0, true);
    }

    @Override
    public boolean canUse() {
        return ProjectileImmunity.shouldUseMelee(this.mob) && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return ProjectileImmunity.shouldUseMelee(this.mob) && super.canContinueToUse();
    }

    @Override
    public void start() {
        this.mob.stopUsingItem();
        super.start();
    }
}
