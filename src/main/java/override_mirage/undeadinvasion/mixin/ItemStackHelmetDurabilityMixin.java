package override_mirage.undeadinvasion.mixin;

import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import override_mirage.undeadinvasion.gameplay.UndeadHelmetDurability;

@Mixin(ItemStack.class)
public abstract class ItemStackHelmetDurabilityMixin {
    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private void undeadinvasion$protectWornHelmet(int amount, ServerLevel level, @Nullable LivingEntity owner,
            Consumer<Item> onBreak, CallbackInfo callback) {
        if (amount > 0 && owner != null && (Object) this == owner.getItemBySlot(EquipmentSlot.HEAD)
                && UndeadHelmetDurability.isProtected(owner)) {
            callback.cancel();
        }
    }
}
