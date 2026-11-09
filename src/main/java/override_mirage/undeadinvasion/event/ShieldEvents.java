package override_mirage.undeadinvasion.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.ShieldBreak;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;
import override_mirage.undeadinvasion.gameplay.ai.ShieldBlockGoal;

/**
 * 破盾冷却期间的格挡限制。
 *
 * <p>收盾与重新举盾由 {@link ShieldBlockGoal} 根据投射物距离、近战攻击和冷却状态决定。
 * 此处阻止冷却中的盾牌参与伤害格挡。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class ShieldEvents {
    private ShieldEvents() {}

    /** 攻击事件的目标就是本次左键选中的实体，在伤害处理前响应。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerAttack(AttackEntityEvent event) {
        var player = event.getEntity();
        if (!(player.level() instanceof ServerLevel)
                || !(event.getTarget() instanceof Mob mob) || !MobBackpack.isCarrier(mob)) {
            return;
        }
        if (!player.hasLineOfSight(mob)
                || !player.isWithinAttackRange(player.getMainHandItem(), mob.getBoundingBox(), 0.0)) {
            return;
        }
        ShieldBlockGoal.reactToAttack(mob);
    }

    /** 生物的直接攻击在格挡结算前举盾；投射物仍由距离检测决定收盾时间。 */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onIncomingAttack(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !MobBackpack.isCarrier(mob)
                || !(mob.level() instanceof ServerLevel) || event.getAmount() <= 0.0F) {
            return;
        }
        if (!(event.getSource().getDirectEntity() instanceof LivingEntity attacker)
                || attacker == mob || UndeadCombat.areAllies(mob, attacker)) {
            return;
        }
        ShieldBlockGoal.reactToAttack(mob);
    }

    /** 冷却期间即使其它 AI 启动了盾牌使用，也不能产生格挡。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onShieldBlock(LivingShieldBlockEvent event) {
        if (event.getEntity() instanceof Mob mob && MobBackpack.isCarrier(mob) && ShieldBreak.isBroken(mob)) {
            event.setBlocked(false);
        }
    }
}
