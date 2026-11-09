package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;
import override_mirage.undeadinvasion.gameplay.ai.RangedArrowAim;
import override_mirage.undeadinvasion.gameplay.ai.RangedAttackFacing;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    @Inject(method = "shootProjectile", at = @At("HEAD"))
    private void undeadinvasion$faceCrossbowTarget(LivingEntity shooter, Projectile projectile,
                                                  int index, float power, float uncertainty, float angle,
                                                  LivingEntity target, CallbackInfo callback) {
        if (shooter instanceof Mob mob && UndeadCombat.isFactionMember(mob) && target != null) {
            RangedAttackFacing.faceTarget(mob, target);
        }
    }

    @ModifyArgs(method = "shootProjectile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/CrossbowItem;getProjectileShotVector(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;F)Lorg/joml/Vector3f;"))
    private void undeadinvasion$aimUndeadArrow(Args args, LivingEntity shooter, Projectile projectile,
                                              int index, float power, float uncertainty, float angle,
                                              LivingEntity target) {
        if (shooter instanceof Mob mob && MobBackpack.isCarrier(mob)
                && projectile instanceof AbstractArrow && target != null) {
            // 在原版多重射击旋转之前修正中心弹道，保留弹药、附魔、耐久和声音流程。
            args.set(1, RangedArrowAim.direction(shooter, target, projectile, power));
        }
    }
}
