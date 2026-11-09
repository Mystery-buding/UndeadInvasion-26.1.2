package override_mirage.undeadinvasion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import override_mirage.undeadinvasion.gameplay.UndeadHelmetDurability;

@Mixin(Mob.class)
public abstract class MobHelmetDurabilityMixin {
    @WrapOperation(method = "burnUndead", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;isDamageableItem()Z"))
    private boolean undeadinvasion$protectHelmetFromSun(ItemStack stack, Operation<Boolean> original) {
        Mob mob = (Mob) (Object) this;
        // 日晒直接设置损伤值，不经过 hurtAndBreak；只跳过头盔磨损，保留原版遮阳判定。
        return original.call(stack)
                && !(stack == mob.getItemBySlot(EquipmentSlot.HEAD) && UndeadHelmetDurability.isProtected(mob));
    }
}
