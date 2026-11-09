package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;

@Mixin(LivingEntity.class)
public abstract class LivingEntityCombatMixin {
    @Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$rejectAlliedTargets(LivingEntity target, CallbackInfoReturnable<Boolean> callback) {
        // 同时约束目标选择器、受伤反击和使用 Brain 的亡灵，旧目标读档后也会失效。
        if (UndeadCombat.areAllies((LivingEntity) (Object) this, target)) {
            callback.setReturnValue(false);
        }
    }
}
