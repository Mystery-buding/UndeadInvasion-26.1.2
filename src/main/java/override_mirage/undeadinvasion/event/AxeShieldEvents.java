package override_mirage.undeadinvasion.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.ShieldBreak;
import override_mirage.undeadinvasion.gameplay.ai.ShieldBlockGoal;

/**
 * 玩家持斧破盾。
 *
 * <p>原版只给玩家添加盾牌冷却。这里在伤害减免前处理玩家的直接斧击，
 * 立即收盾并记录 5 秒冷却，保证举盾时命中也能触发。
 *
 * <p>只有正在举盾且不在冷却中的亡灵才会被破盾；未举盾、投射物或被取消的攻击不会触发，
 * 冷却期间再次受到斧击也不会延长冷却。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class AxeShieldEvents {
    private AxeShieldEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !MobBackpack.isCarrier(mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level) || event.getAmount() <= 0.0F) {
            return;
        }
        if (!mob.isBlocking() || ShieldBreak.isBroken(mob)) {
            return;
        }
        if (!(event.getSource().getDirectEntity() instanceof Player player)) {
            return;
        }

        ItemStack weapon = player.getMainHandItem();
        if (!(weapon.getItem() instanceof AxeItem) && !weapon.is(ItemTags.AXES)) {
            return;
        }

        if (!ShieldBreak.breakShield(mob, ShieldBlockGoal.AXE_BREAK_TICKS)) {
            return;
        }

        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F);
    }
}
