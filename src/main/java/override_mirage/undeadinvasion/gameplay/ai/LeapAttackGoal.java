package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;

/**
 * 近战跳劈：持剑 / 斧 / 三叉戟时向目标起跳，并在空中命中一次。
 *
 * <p>行为参考原版 {@code LeapAtTargetGoal}：
 * <ul>
 *   <li>只有主手是剑、斧、三叉戟时才起跳（长矛走原版突刺，不参与）。</li>
 *   <li>玩家目标距离限制在 2–3.5 格，非玩家目标在 3.5 格内均可起跳。</li>
 *   <li>玩家目标保留原有冷却；非玩家目标落地后即可再次起跳。</li>
 * </ul>
 *
 * <p>空中命中通过 {@code Mob#doHurtTarget} 触发正常伤害流程，伤害加成与暴击粒子由
 * {@code LeapAttackEvents} 处理。
 */
public final class LeapAttackGoal extends Goal {
    /** 对玩家起跳的最小距离平方（2 格）。 */
    private static final double MIN_PLAYER_DISTANCE_SQR = 4.0;
    /** 起跳的最大距离平方（3.5 格）。 */
    private static final double MAX_DISTANCE_SQR = 12.25;
    /** 空中命中的判定距离平方（2 格）。 */
    private static final double STRIKE_DISTANCE_SQR = 4.0;
    /** 对玩家起跳的冷却基数。 */
    private static final int PLAYER_COOLDOWN = 80;
    /** 起跳竖直速度。 */
    private static final double UPWARD_SPEED = 0.42;
    /** 起跳水平速度，与扩展后的起跳范围配套。 */
    private static final double FORWARD_SPEED = 0.50;

    private final Mob mob;
    private LivingEntity target;
    private int cooldown;
    private boolean struck;

    public LeapAttackGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.JUMP, Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        this.target = this.mob.getTarget();
        if (this.cooldown > 0) {
            this.cooldown--;
            if (this.target instanceof Player) {
                return false;
            }
        }
        if (this.mob.hasControllingPassenger()) {
            return false;
        }

        if (this.target == null || !this.target.isAlive()) {
            return false;
        }
        if (!this.mob.onGround() || !isLeapWeapon(this.mob.getMainHandItem())) {
            return false;
        }

        double distance = this.mob.distanceToSqr(this.target);
        return distance <= MAX_DISTANCE_SQR
                && (!(this.target instanceof Player) || distance >= MIN_PLAYER_DISTANCE_SQR);
    }

    @Override
    public boolean canContinueToUse() {
        return !this.mob.onGround() && this.target != null && this.target.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.struck = false;

        Vec3 movement = this.mob.getDeltaMovement();
        Vec3 direction = new Vec3(this.target.getX() - this.mob.getX(), 0.0, this.target.getZ() - this.mob.getZ());
        if (direction.lengthSqr() > 1.0E-7) {
            direction = direction.normalize().scale(FORWARD_SPEED).add(movement.scale(0.2));
        }
        this.mob.setDeltaMovement(direction.x, UPWARD_SPEED, direction.z);
        this.mob.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        this.cooldown = PLAYER_COOLDOWN + this.mob.getRandom().nextInt(40);
    }

    @Override
    public void tick() {
        if (this.struck || this.target == null) {
            return;
        }
        // 只在开始下落时出手，保证命中判定与「劈」的动作和加成一致。
        if (this.mob.getDeltaMovement().y > 0.0) {
            return;
        }
        if (this.mob.distanceToSqr(this.target) > STRIKE_DISTANCE_SQR) {
            return;
        }

        this.struck = true;
        if (this.mob.level() instanceof ServerLevel level) {
            this.mob.doHurtTarget(level, this.target);
        }
    }

    /**
     * 跳劈武器：剑、斧、三叉戟。长矛不参与（走原版突刺）。
     */
    public static boolean isLeapWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.getItem() instanceof TridentItem;
    }
}
