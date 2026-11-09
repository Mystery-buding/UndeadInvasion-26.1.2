package override_mirage.undeadinvasion.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import override_mirage.undeadinvasion.gameplay.ai.DeferredWeaponGoalUpdate;

@Mixin(Mob.class)
public abstract class MobAiStepMixin {
    @WrapMethod(method = "serverAiStep")
    private void undeadinvasion$deferWeaponGoalsDuringAi(Operation<Void> original) {
        if ((Object) this instanceof DeferredWeaponGoalUpdate update) {
            update.undeadinvasion$beginAiStep();
            try {
                original.call();
            } finally {
                // 包含完整 tick 和交替的 tickRunningGoals，也兼容其它模组取消 AI 的情况。
                update.undeadinvasion$endAiStep();
            }
        } else {
            original.call();
        }
    }
}
