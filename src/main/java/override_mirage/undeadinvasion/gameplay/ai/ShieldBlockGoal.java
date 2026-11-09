package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.ShieldBreak;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;
import override_mirage.undeadinvasion.mixin.AbstractArrowAccessor;

/**
 * 响应空中投射物和生物近战攻击的举盾目标。
 *
 * <p>规则：
 * <ul>
 *   <li>自身 5 格半径内有非同伴的空中投射物就举盾，不要求攻击目标或投射物的飞行方向。</li>
 *   <li>落地或插入方块的箭矢、三叉戟不参与检测。</li>
 *   <li>范围内仍有空中投射物就持续举盾；全部落地、离开或移除后收盾。</li>
 *   <li>玩家左键攻击或受到其他生物的近战攻击时立即举盾并保持 0.5 秒，连续攻击会刷新保持时间。</li>
 *   <li>举盾优先于拉弓/装填，避免其它攻击目标覆盖物品使用状态。</li>
 *   <li>举盾时被玩家用斧命中后立即收盾，5 秒内不能格挡。</li>
 *   <li>破盾冷却期间不响应投射物或近战攻击，结束后按新的触发条件恢复举盾。</li>
 * </ul>
 *
 * <p>举盾会让原版 {@code LivingEntity#isBlocking()} 成立，减伤、音效、耐久与正面角度判定
 * 全部沿用原版逻辑。
 */
public final class ShieldBlockGoal extends Goal {
    /** 检测半径（格）。 */
    private static final double SCAN_RADIUS = 5.0;
    private static final String MELEE_BLOCK_UNTIL_TAG = "undeadinvasion:shield_melee_until";
    /** 近战攻击触发后保持举盾的时长（tick）。 */
    private static final int MELEE_BLOCK_TICKS = 10;
    /** 斧破盾后禁止格挡的时长（tick）。 */
    public static final int AXE_BREAK_TICKS = 100;

    private final Mob mob;

    public ShieldBlockGoal(Mob mob) {
        this.mob = mob;
        // 优先级 0 的举盾目标与攻击目标互斥，避免拉弓/装填在同一 tick 抢回使用槽。
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.mob.isAlive()
                && this.mob.getOffhandItem().is(Items.SHIELD)
                && !ShieldBreak.isBroken(this.mob)
                && this.shouldBlock();
    }

    /** 在本次近战攻击造成伤害之前触发，保证第一下攻击也能被格挡。 */
    public static boolean reactToAttack(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()
                || !mob.getOffhandItem().is(Items.SHIELD) || ShieldBreak.isBroken(mob)) {
            return false;
        }
        mob.getPersistentData().putLong(MELEE_BLOCK_UNTIL_TAG, level.getGameTime() + MELEE_BLOCK_TICKS);
        raise(mob);
        return true;
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
    public void start() {
        this.raise();
    }

    @Override
    public void tick() {
        // 目标已经运行时也要检查，避免受击当 tick 又把盾举起。
        if (ShieldBreak.isBroken(this.mob) || !this.mob.getOffhandItem().is(Items.SHIELD)) {
            this.lower();
            return;
        }

        if (this.shouldBlock()) {
            this.raise();
            if (this.mob.getTarget() != null) {
                this.mob.getLookControl().setLookAt(this.mob.getTarget(), 30.0F, 30.0F);
            }
        } else {
            this.lower();
        }
    }

    /**
     * 投射物威胁与近战举盾保持时间结束、进入冷却或目标停止时收盾。
     */
    @Override
    public void stop() {
        this.lower();
    }

    private void raise() {
        raise(this.mob);
    }

    private static void raise(Mob mob) {
        MobBackpack.makeShieldFast(mob.getOffhandItem());
        if (mob.isUsingItem() && mob.getUseItem().is(Items.SHIELD)) {
            return;
        }
        mob.stopUsingItem();
        mob.startUsingItem(InteractionHand.OFF_HAND);
    }

    private void lower() {
        if (this.mob.isUsingItem() && this.mob.getUseItem().is(Items.SHIELD)) {
            this.mob.stopUsingItem();
        }
    }

    private boolean shouldBlock() {
        return this.mob.getPersistentData().getLongOr(MELEE_BLOCK_UNTIL_TAG, 0L) > this.mob.level().getGameTime()
                || this.hasNearbyProjectile();
    }

    private boolean hasNearbyProjectile() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }

        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class,
                this.mob.getBoundingBox().inflate(SCAN_RADIUS));
        Vec3 center = this.mob.getBoundingBox().getCenter();

        for (Projectile projectile : projectiles) {
            if (UndeadCombat.areAllies(this.mob, projectile.getOwner())) {
                continue;
            }
            if (projectile instanceof AbstractArrow arrow
                    && ((AbstractArrowAccessor) arrow).undeadinvasion$isInGround()) {
                continue;
            }
            if (!projectile.isRemoved() && projectile.position().distanceToSqr(center) <= SCAN_RADIUS * SCAN_RADIUS) {
                return true;
            }
        }
        return false;
    }
}
