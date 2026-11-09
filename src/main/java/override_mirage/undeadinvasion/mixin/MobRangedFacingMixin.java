package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.gameplay.ai.RangedAttackFacing;

@Mixin(Mob.class)
public abstract class MobRangedFacingMixin {
    @Inject(method = "tickHeadTurn", at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$faceRangedAim(float bodyRotationTarget, CallbackInfo callback) {
        if (RangedAttackFacing.keepBodyFacingAim((Mob) (Object) this)) {
            callback.cancel();
        }
    }
}
