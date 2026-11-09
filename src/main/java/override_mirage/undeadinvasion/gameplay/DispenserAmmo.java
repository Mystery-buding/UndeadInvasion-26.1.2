package override_mirage.undeadinvasion.gameplay;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jspecify.annotations.Nullable;

/** 一次仅发射一种弹药，药水不混合效果；放大等级 4 对应游戏中的 V 级。 */
public enum DispenserAmmo {
    INSTANT_DAMAGE(MobEffects.INSTANT_DAMAGE, true),
    RESISTANCE(MobEffects.RESISTANCE, true),
    SPEED(MobEffects.SPEED, true),
    STRENGTH(MobEffects.STRENGTH, true),
    WEAKNESS(MobEffects.WEAKNESS, false),
    SLOWNESS(MobEffects.SLOWNESS, false),
    DRAGON_BREATH(null, false),
    FIREBALL(null, false);

    public static final int EFFECT_AMPLIFIER = 4;
    /** 持续类药水的基础时长：10 分钟，每秒 20 tick。 */
    public static final int EFFECT_DURATION = 20 * 60 * 10;
    private final @Nullable Holder<MobEffect> effect;
    private final boolean support;

    DispenserAmmo(@Nullable Holder<MobEffect> effect, boolean support) {
        this.effect = effect;
        this.support = support;
    }

    public @Nullable Holder<MobEffect> effect() {
        return this.effect;
    }

    /** 是否可以用于支援；瞬间伤害同时也可用于攻击。 */
    public boolean isSupport() {
        return this.support;
    }

    public ItemStack potionStack() {
        if (this.effect == null) {
            throw new IllegalStateException("This ammunition is not a potion: " + this);
        }
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        int duration = this.effect.value().isInstantenous() ? 1 : EFFECT_DURATION;
        stack.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withEffectAdded(
                new MobEffectInstance(this.effect, duration, EFFECT_AMPLIFIER)));
        return stack;
    }
}
