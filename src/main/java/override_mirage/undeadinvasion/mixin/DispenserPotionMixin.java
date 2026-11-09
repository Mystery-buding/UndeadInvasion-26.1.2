package override_mirage.undeadinvasion.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import override_mirage.undeadinvasion.gameplay.DispenserProjectiles;

@Mixin(ThrownSplashPotion.class)
public abstract class DispenserPotionMixin {
    @ModifyExpressionValue(method = "onHitAsPotion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> undeadinvasion$filterDispenserSplash(List<LivingEntity> entities) {
        ThrownSplashPotion potion = (ThrownSplashPotion) (Object) this;
        return DispenserProjectiles.ammo(potion) == null ? entities
                : entities.stream().filter(entity -> DispenserProjectiles.canAffect(potion, entity)).toList();
    }
}
