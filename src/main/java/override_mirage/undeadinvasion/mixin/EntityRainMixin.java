package override_mirage.undeadinvasion.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import override_mirage.undeadinvasion.world.StormState;
import override_mirage.undeadinvasion.world.StormType;

/**
 * 硫磺雨期间让实体不判定为「在雨中」。
 *
 * <p>原版 {@code Entity#move} 里只要 {@code isInRain()} 就会 {@code clearFire()}，
 * 而硫磺雨本身是雨，直接点燃会被下一 tick 立刻浇灭。这里把硫磺雨的灭火判定屏蔽掉，
 * 让火焰持续燃烧并产生原版 onFire 伤害，同时不影响酸雨与普通雨的正常灭火行为。
 */
@Mixin(Entity.class)
public abstract class EntityRainMixin {
    @ModifyReturnValue(method = "isInRain()Z", at = @At("RETURN"))
    private boolean undeadinvasion$sulfurRainDoesNotExtinguish(boolean original) {
        return StormState.is(StormType.SULFUR) ? false : original;
    }
}
