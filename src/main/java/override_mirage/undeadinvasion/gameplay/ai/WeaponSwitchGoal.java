package override_mirage.undeadinvasion.gameplay.ai;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.jetbrains.annotations.Nullable;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.ProjectileImmunity;

/**
 * 按目标距离在近战武器与远程武器之间切换。
 *
 * <p>背包给亡灵配了近战与远程两把武器（记录在实体数据里），本目标不占用任何 AI 控制位，
 * 与移动/攻击目标并行运行，只负责把主手换成当前距离该用的那把：
 * <ul>
 *   <li>目标 ≤ 5 格：切到近战武器（剑 / 斧 / 矛 / 三叉戟）。</li>
 *   <li>目标 ≥ 6 格：切到远程武器（弓 / 弩 / 三叉戟）。</li>
 *   <li>目标免疫投射物：优先切到近战武器，不受距离限制。</li>
 * </ul>
 * 中间留 1 格缓冲，避免玩家在 5 格附近来回移动时反复换武器。
 *
 * <p>三叉戟同时属于两类，若近战与远程都抽到三叉戟，切换不会产生实际变化，由三叉戟 AI 自行处理远近。
 */
public final class WeaponSwitchGoal extends Goal {
    /** 进入近战的距离（格）。 */
    private static final double MELEE_DISTANCE = 5.0;
    /** 退回远程的距离（格），留 1 格缓冲。 */
    private static final double RANGED_DISTANCE = 6.0;

    private final Mob mob;

    public WeaponSwitchGoal(Mob mob) {
        this.mob = mob;
        // 刻意不设置 Flag：只换装备，不参与移动/视角控制，可与其它目标同时运行。
    }

    @Override
    public boolean canUse() {
        return this.hasTarget()
                && MobBackpack.meleeItem(this.mob) != null
                && (MobBackpack.rangedItem(this.mob) != null || ProjectileImmunity.shouldUseMelee(this.mob));
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }

        Item melee = MobBackpack.meleeItem(this.mob);
        Item ranged = MobBackpack.rangedItem(this.mob);
        if (ProjectileImmunity.shouldUseMelee(this.mob)) {
            this.equip(melee);
            return;
        }
        if (melee == null || ranged == null) {
            return;
        }

        // 主手武器是三叉戟：远近都交给三叉戟（远距离由 TridentAttackGoal 投掷），不切弓弩。
        if (melee instanceof TridentItem) {
            this.equip(melee);
            return;
        }

        double distance = Math.sqrt(this.mob.distanceToSqr(target));
        ItemStack mainHand = this.mob.getMainHandItem();

        // 空手（例如武器被夺走）时直接补上当前距离该用的那把。
        if (mainHand.isEmpty()) {
            this.equip(distance >= RANGED_DISTANCE ? ranged : melee);
            return;
        }

        boolean rangedInHand = mainHand.getItem() == ranged;
        if (rangedInHand && distance <= MELEE_DISTANCE) {
            this.equip(melee);
        } else if (!rangedInHand && distance >= RANGED_DISTANCE) {
            this.equip(ranged);
        }
    }

    private void equip(@Nullable Item item) {
        if (item == null || this.mob.getMainHandItem().getItem() == item) {
            return;
        }

        // 换武器前中断拉弓/装填，避免动作残留。
        this.mob.stopUsingItem();
        // 装备回调已负责评估骷髅的武器目标，无需重复手动重建。
        this.mob.setItemSlot(EquipmentSlot.MAINHAND, MobBackpack.weaponStack(item));
    }

    private boolean hasTarget() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive();
    }
}
