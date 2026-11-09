package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;
import override_mirage.undeadinvasion.gameplay.DispenserProjectiles;

@Mixin(Projectile.class)
public abstract class ProjectileAllyMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$passThroughAllies(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        Projectile projectile = (Projectile) (Object) this;
        if (DispenserProjectiles.ammo(projectile) != null) {
            if (!(entity instanceof LivingEntity living) || !DispenserProjectiles.canHit(projectile, living)) {
                callback.setReturnValue(false);
            }
            // 支援药水允许命中队友，但仍交给原版检查发射者与同乘载具的碰撞。
            return;
        }
        // 在碰撞筛选阶段跳过同伴，避免反弹、点燃、药水效果和消耗穿透次数。
        if (UndeadCombat.areAllies(projectile.getOwner(), entity)) {
            callback.setReturnValue(false);
        }
    }
}
