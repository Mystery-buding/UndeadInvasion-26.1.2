package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;

@Mixin(Entity.class)
public abstract class EntityAllianceMixin {
    @Inject(method = "considersEntityAsAlly", at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$allyWithUndead(Entity other, CallbackInfoReturnable<Boolean> callback) {
        if (UndeadCombat.areAllies((Entity) (Object) this, other)) {
            callback.setReturnValue(true);
        }
    }
}
