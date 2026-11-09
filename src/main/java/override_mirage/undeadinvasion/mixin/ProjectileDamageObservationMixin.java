package override_mirage.undeadinvasion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;

@Mixin({AbstractArrow.class, ThrownTrident.class})
public abstract class ProjectileDamageObservationMixin {
    @WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean undeadinvasion$observeProjectileDamage(Entity target, DamageSource source, float amount,
                                                           Operation<Boolean> original) {
        return ProjectileImmunity.observeHit((Projectile) (Object) this, target, source, amount,
                () -> original.call(target, source, amount));
    }
}
