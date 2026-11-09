package override_mirage.undeadinvasion.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowCombatMixin {
    @Inject(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;deflect(Lnet/minecraft/world/entity/projectile/ProjectileDeflection;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/EntityReference;Z)Z"),
            cancellable = true)
    private void undeadinvasion$absorbRejectedVillagerHit(EntityHitResult hitResult, CallbackInfo callback) {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (!arrow.level().isClientSide() && UndeadCombat.isFactionMember(arrow.getOwner())
                && hitResult.getEntity() instanceof Villager villager && !villager.isBlocking()) {
            // 此处伤害已被原版拒绝，燃烧时间也已还原；保留受伤冷却和其它模组的伤害保护。
            // 普通箭被吸收，穿透箭沿原方向继续。玩家盾牌和真正的投射物反射沿用原版。
            if (arrow.getPierceLevel() <= 0) {
                arrow.discard();
            }
            callback.cancel();
        }
    }
}
