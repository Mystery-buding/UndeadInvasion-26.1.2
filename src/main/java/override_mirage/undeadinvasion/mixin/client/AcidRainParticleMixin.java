package override_mirage.undeadinvasion.mixin.client;

import net.minecraft.client.particle.WaterDropParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.client.ClientAcidRain;
import override_mirage.undeadinvasion.world.StormType;

@Mixin(WaterDropParticle.class)
public abstract class AcidRainParticleMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void undeadinvasion$tintRainSplash(CallbackInfo callback) {
        StormType type = ClientAcidRain.get();
        if (type.isActive()) {
            ((WaterDropParticle) (Object) this).setColor(type.red(), type.green(), type.blue());
        }
    }
}
