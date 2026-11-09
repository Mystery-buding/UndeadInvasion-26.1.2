package override_mirage.undeadinvasion.gameplay;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import org.jspecify.annotations.Nullable;

/** 只观察实际伤害结果和免疫查询，不向目标施加额外的测试伤害。 */
public final class ProjectileImmunity {
    private static final int CHECK_INTERVAL = 20;
    private static final int LEARNED_IMMUNITY_TICKS = 200;
    // 仅在服务端线程访问；弱引用键避免实体卸载后留下永久记录。
    private static final Map<Mob, Check> CHECKS = new WeakHashMap<>();
    private static final Map<LivingEntity, Long> INEFFECTIVE_UNTIL = new WeakHashMap<>();

    private ProjectileImmunity() {}

    public static boolean shouldUseMelee(Mob mob) {
        return shouldUseMelee(mob, mob.getTarget());
    }

    public static boolean shouldUseMelee(Mob mob, @Nullable LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level) || !MobBackpack.isCarrier(mob)
                || !UndeadCombat.isFactionMember(mob) || target == null || !target.isAlive()
                || !mob.canAttack(target) || mob.isAlliedTo(target)) {
            return false;
        }

        long now = level.getGameTime();
        boolean trident = mob.getMainHandItem().getItem() instanceof TridentItem
                || MobBackpack.rangedItem(mob) instanceof TridentItem;
        Check check = CHECKS.get(mob);
        if (check == null || !check.target().equals(target.getUUID()) || check.trident() != trident
                || now >= check.nextCheck()) {
            boolean meleeAllowed = !target.isInvulnerableTo(level, level.damageSources().mobAttack(mob));
            boolean immune = target instanceof EnderMan
                    || target instanceof WitherBoss wither && wither.isPowered()
                    || target.isInvulnerableTo(level, projectileSource(level, mob, trident));
            check = new Check(target.getUUID(), trident, now + CHECK_INTERVAL, meleeAllowed, immune);
            CHECKS.put(mob, check);
        }

        Long until = INEFFECTIVE_UNTIL.get(target);
        if (until != null && until <= now) {
            INEFFECTIVE_UNTIL.remove(target);
            until = null;
        }
        return check.meleeAllowed() && (check.immune() || until != null);
    }

    private static DamageSource projectileSource(ServerLevel level, Mob mob, boolean trident) {
        // 查询使用未加入世界的投射物，只提供伤害来源信息，不发射、不调用伤害方法。
        if (trident) {
            ThrownTrident probe = new ThrownTrident(level, mob, new ItemStack(Items.TRIDENT));
            return level.damageSources().trident(probe, mob);
        }
        Arrow probe = new Arrow(EntityType.ARROW, level);
        probe.setOwner(mob);
        probe.setPos(mob.getEyePosition());
        return level.damageSources().arrow(probe, mob);
    }

    /** 保留原伤害调用与返回值，兼容在 hurtServer 或伤害事件里实现的免疫。 */
    public static boolean observeHit(Projectile projectile, Entity entity, DamageSource source,
                                     float amount, BooleanSupplier damageCall) {
        if (!(projectile.level() instanceof ServerLevel level) || !(entity instanceof LivingEntity target)
                || !(projectile.getOwner() instanceof Mob owner) || !MobBackpack.isCarrier(owner)
                || !UndeadCombat.isFactionMember(owner) || UndeadCombat.areAllies(owner, target)
                || !source.is(DamageTypeTags.IS_PROJECTILE)) {
            return damageCall.getAsBoolean();
        }

        float health = target.getHealth();
        float absorption = target.getAbsorptionAmount();
        boolean canLearn = amount > 0.0F && target.invulnerableTime <= 0 && !target.isBlocking()
                && !target.isInvulnerableTo(level, level.damageSources().mobAttack(owner));
        boolean accepted = damageCall.getAsBoolean();
        if (target.getHealth() < health || target.getAbsorptionAmount() < absorption) {
            INEFFECTIVE_UNTIL.remove(target);
            CHECKS.remove(owner);
        } else if (canLearn && target.isAlive() && !target.isBlocking()) {
            // 按具体实体短时记忆，允许阶段性免疫结束后重新尝试远程。
            INEFFECTIVE_UNTIL.put(target, level.getGameTime() + LEARNED_IMMUNITY_TICKS);
            CHECKS.remove(owner);
        }
        return accepted;
    }

    private record Check(UUID target, boolean trident, long nextCheck, boolean meleeAllowed, boolean immune) {}
}
