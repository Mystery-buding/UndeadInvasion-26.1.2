package override_mirage.undeadinvasion.event;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.ai.LeapAttackGoal;

/**
 * 跳劈的伤害加成与表现。
 *
 * <p>判定条件：攻击者是亡灵、处于空中、正在下落、主手为剑/斧/三叉戟。
 * 满足时把本次伤害乘以 {@value #LEAP_BONUS}，并在攻击者位置生成暴击粒子。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class LeapAttackEvents {
    /** 跳劈伤害倍率。 */
    private static final float LEAP_BONUS = 1.5F;

    private LeapAttackEvents() {}

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Mob attacker)) {
            return;
        }
        if (!MobBackpack.isCarrier(attacker)) {
            return;
        }
        if (attacker.onGround() || attacker.getDeltaMovement().y >= 0.0) {
            return;
        }
        if (!LeapAttackGoal.isLeapWeapon(attacker.getMainHandItem())) {
            return;
        }

        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }

        event.setAmount(amount * LEAP_BONUS);

        if (attacker.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CRIT,
                    attacker.getX(), attacker.getY() + attacker.getBbHeight() * 0.5, attacker.getZ(),
                    10, 0.3, 0.3, 0.3, 0.15);
        }
    }
}
