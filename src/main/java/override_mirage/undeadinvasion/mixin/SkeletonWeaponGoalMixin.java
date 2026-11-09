package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.gameplay.ai.DeferredWeaponGoalUpdate;

@Mixin(AbstractSkeleton.class)
public abstract class SkeletonWeaponGoalMixin implements DeferredWeaponGoalUpdate {
    @Unique
    private int undeadinvasion$aiStepDepth;
    @Unique
    private boolean undeadinvasion$weaponGoalDirty;

    @Inject(method = "reassessWeaponGoal", at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$queueWeaponGoalUpdate(CallbackInfo callback) {
        if (this.undeadinvasion$aiStepDepth > 0) {
            // onEquipItem 对护甲和武器都会触发重建，自动上马的装备同步也必须覆盖。
            this.undeadinvasion$weaponGoalDirty = true;
            callback.cancel();
        }
    }

    @Override
    public void undeadinvasion$beginAiStep() {
        this.undeadinvasion$aiStepDepth++;
    }

    @Override
    public void undeadinvasion$endAiStep() {
        if (--this.undeadinvasion$aiStepDepth == 0 && this.undeadinvasion$weaponGoalDirty) {
            this.undeadinvasion$weaponGoalDirty = false;
            ((AbstractSkeleton) (Object) this).reassessWeaponGoal();
        }
    }
}
