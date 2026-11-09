package override_mirage.undeadinvasion.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.AcidRain;

/**
 * 把酸雨塑造成真正的<strong>无来源环境伤害</strong>。
 *
 * <p>{@code LivingEntity#hurtServer} 默认是为「被实体攻击」设计的：它会施加击退、设置约 10 刻的
 * 无敌帧（{@code invulnerableTime}）、播放受击音效与红闪，并让护甲/附魔/抗性/吸收心层层减免伤害。
 * 酸雨每秒一次、持续整场风暴，走这套管线就会出现随机弹飞、某些脉冲不掉血、掉血量忽高忽低。
 *
 * <p>这里配合伤害类型标签（{@code no_knockback} / {@code no_impact} / {@code bypasses_cooldown}）
 * 把攻击副作用全部剥掉，并在伤害管线里钉死两点：
 * <ul>
 *   <li>清掉实体身上残留的无敌帧，且本次伤害结束后不产生新的无敌帧，保证每个脉冲都独立结算；</li>
 *   <li>把所有减免后的最终伤害固定为 {@link AcidRain#DAMAGE}，即恰好 1 点。</li>
 * </ul>
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class AcidRainDamageEvents {
    private AcidRainDamageEvents() {}

    /**
     * 在 {@code hurtServer} 最早期介入：此时无敌帧判断尚未发生。
     *
     * <p>{@code invulnerableTime} 是 public 字段，其它伤害（摔落、怪物攻击等）留下的无敌帧会让
     * 本次酸雨被直接丢弃，所以先清零；再把「攻击后无敌帧」设为最小值 1（NeoForge 要求该值 &gt; 0），
     * 酸雨每 20 刻才结算一次，1 刻不会干扰下一次脉冲，也几乎不影响其它伤害。
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!event.getSource().is(AcidRain.DAMAGE_TYPE)) {
            return;
        }
        event.getEntity().invulnerableTime = 0;
        event.setInvulnerabilityTicks(1);
    }

    /**
     * 护甲、保护附魔、抗性提升、吸收心等减免都在 {@code actuallyHurt} 里计算完毕，
     * 此事件拿到的是即将写入血量的最终值，直接钉死为 1 点。
     */
    @SubscribeEvent
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        if (event.getSource().is(AcidRain.DAMAGE_TYPE)) {
            event.setNewDamage(AcidRain.DAMAGE);
        }
    }
}
