package override_mirage.undeadinvasion.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import override_mirage.undeadinvasion.gameplay.DispenserProjectiles;

@Mixin(DragonFireball.class)
public abstract class DispenserDragonFireballMixin {
    @ModifyExpressionValue(method = "onHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> undeadinvasion$placeDispenserBreathAtEnemy(List<LivingEntity> entities) {
        DragonFireball fireball = (DragonFireball) (Object) this;
        return DispenserProjectiles.ammo(fireball) == null ? entities
                : entities.stream().filter(entity -> DispenserProjectiles.canAffect(fireball, entity)).toList();
    }

    @WrapOperation(method = "onHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean undeadinvasion$markDispenserDragonCloud(Level level, Entity entity, Operation<Boolean> original) {
        if (entity instanceof AreaEffectCloud cloud) {
            DispenserProjectiles.prepareDragonCloud((DragonFireball) (Object) this, cloud);
        }
        return original.call(level, entity);
    }
}
