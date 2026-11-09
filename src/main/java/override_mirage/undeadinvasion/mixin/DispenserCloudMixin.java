package override_mirage.undeadinvasion.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.gameplay.DispenserProjectiles;

@Mixin(AreaEffectCloud.class)
public abstract class DispenserCloudMixin {
    @Inject(method = "serverTick", at = @At("RETURN"))
    private void undeadinvasion$damageDispenserBreathTargets(ServerLevel level, CallbackInfo callback) {
        DispenserProjectiles.tickDragonCloud((AreaEffectCloud) (Object) this, level);
    }
}
