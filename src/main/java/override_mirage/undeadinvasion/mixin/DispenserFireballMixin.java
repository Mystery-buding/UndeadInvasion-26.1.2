package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.gameplay.DispenserAmmo;
import override_mirage.undeadinvasion.gameplay.DispenserProjectiles;

@Mixin(SmallFireball.class)
public abstract class DispenserFireballMixin {
    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$keepDispenserFireOnEnemies(BlockHitResult hit, CallbackInfo callback) {
        if (DispenserProjectiles.ammo((SmallFireball) (Object) this) == DispenserAmmo.FIREBALL) {
            callback.cancel();
        }
    }
}
