package override_mirage.undeadinvasion.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import override_mirage.undeadinvasion.client.ClientAcidRain;

@Mixin(WeatherEffectRenderer.class)
public abstract class AcidRainRendererMixin {
    @ModifyExpressionValue(method = "renderInstances", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
    private int undeadinvasion$greenAcidRain(int original, VertexConsumer builder,
            List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 cameraPos, float maxAlpha, int radius, float intensity) {
        // 原版雨柱最大透明度为 1，雪柱为 0.8；只染雨，保留雪和雨滴透明度。
        // 酸雨绿 / 硫磺雨黄，颜色由客户端当前风暴类型决定。
        return maxAlpha == 1.0F && ClientAcidRain.isActive() ? ClientAcidRain.tint(original) : original;
    }
}
