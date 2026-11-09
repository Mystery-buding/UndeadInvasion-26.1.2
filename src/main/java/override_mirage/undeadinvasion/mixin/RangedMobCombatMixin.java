package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;
import override_mirage.undeadinvasion.gameplay.ai.RangedArrowAim;
import override_mirage.undeadinvasion.gameplay.ai.RangedAttackFacing;
import override_mirage.undeadinvasion.gameplay.ai.RangedTridentAim;

@Mixin({AbstractSkeleton.class, Drowned.class})
public abstract class RangedMobCombatMixin {
    @Inject(method = "performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
            at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$avoidImmuneTargets(LivingEntity target, float power, CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (ProjectileImmunity.shouldUseMelee(mob, target)) {
            callback.cancel();
            return;
        }
        if (UndeadCombat.isFactionMember(mob)) {
            RangedAttackFacing.faceTarget(mob, target);
        }
    }

    @ModifyArgs(method = "performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/Projectile;spawnProjectileUsingShoot(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;DDDFF)Lnet/minecraft/world/entity/projectile/Projectile;"))
    private void undeadinvasion$extendTridentRange(Args args, LivingEntity target, float power) {
        Mob mob = (Mob) (Object) this;
        if (!(args.get(0) instanceof ThrownTrident trident) || !UndeadCombat.isFactionMember(mob)) {
            return;
        }
        float velocity = RangedArrowAim.velocity(target.position().subtract(mob.position()).horizontalDistance());
        var direction = RangedTridentAim.direction(mob, target, trident, velocity);
        args.set(3, direction.x);
        args.set(4, direction.y);
        args.set(5, direction.z);
        args.set(6, velocity);
    }
}
