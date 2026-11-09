package override_mirage.undeadinvasion.event;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;

@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class UndeadCombatEvents {
    private UndeadCombatEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (UndeadCombat.areAllies(event.getEntity(), event.getSource().getEntity())) {
            // 在格挡、击退、附魔和受伤反击记录之前拒绝误伤。
            event.setCanceled(true);
        }
    }
}
