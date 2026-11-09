package override_mirage.undeadinvasion.gameplay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import override_mirage.undeadinvasion.gameplay.ai.DispenserAttackGoal;

/** 第 45 天起生成的僵尸系支援单位；身份随实体保存，读档不会重新抽取。 */
public final class DispenserUndead {
    public static final long START_DAY = 45;
    public static final int SPAWN_CHANCE_PERCENT = 5;
    private static final String ROLLED_TAG = "undeadinvasion:dispenser_rolled";
    private static final String VARIANT_TAG = "undeadinvasion:dispenser_undead";
    private static final String PENDING_TAG = "undeadinvasion:dispenser_spawn_pending";

    private DispenserUndead() {}

    private static boolean eligibleType(Mob mob) {
        return mob instanceof Zombie && MobBackpack.isCarrier(mob) && UndeadCombat.isFactionMember(mob);
    }

    /** 区块生成可能在工作线程初始化生物，此时只标记，不访问服务端存档天数。 */
    public static void prepareSpawn(Mob mob) {
        if (eligibleType(mob) && !mob.getPersistentData().getBooleanOr(ROLLED_TAG, false)) {
            mob.getPersistentData().putBoolean(PENDING_TAG, true);
        }
    }

    /** 首次实际进入世界后完成判定；区块中尚未入场的新实体也会保存待判定标记。 */
    public static void finishSpawn(Mob mob, long day, RandomSource random) {
        if (eligibleType(mob) && mob.getPersistentData().getBooleanOr(PENDING_TAG, false)) {
            mob.getPersistentData().remove(PENDING_TAG);
            rollForSpawn(mob, day, random);
        }
    }

    /** 记录首次生成的判定结果；读档和再次进入世界不会重复抽取。 */
    public static boolean rollForSpawn(Mob mob, long day, RandomSource random) {
        if (!eligibleType(mob)) {
            return false;
        }
        CompoundTag data = mob.getPersistentData();
        if (!data.getBooleanOr(ROLLED_TAG, false)) {
            data.putBoolean(ROLLED_TAG, true);
            if (day >= START_DAY && MobBackpack.hasKitForDay(day)
                    && random.nextInt(100) < SPAWN_CHANCE_PERCENT) {
                data.putBoolean(VARIANT_TAG, true);
            }
        }
        return isVariant(mob);
    }

    public static boolean isVariant(Mob mob) {
        return eligibleType(mob) && mob.getPersistentData().getBooleanOr(VARIANT_TAG, false);
    }

    public static boolean isArmed(Mob mob) {
        return isVariant(mob) && MobBackpack.isReinforced(mob)
                && mob.getItemBySlot(EquipmentSlot.HEAD).is(Items.DISPENSER);
    }

    public static void sync(Mob mob) {
        if (!isVariant(mob)) {
            return;
        }
        if (!mob.getItemBySlot(EquipmentSlot.HEAD).is(Items.DISPENSER)) {
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DISPENSER));
        }
        if (mob.goalSelector.getAvailableGoals().stream()
                .noneMatch(goal -> goal.getGoal() instanceof DispenserAttackGoal)) {
            mob.goalSelector.addGoal(0, new DispenserAttackGoal(mob));
        }
    }

    public static void inheritConversion(LivingEntity original, Mob converted) {
        if (original instanceof Mob old && eligibleType(converted)) {
            if (old.getPersistentData().getBooleanOr(ROLLED_TAG, false)) {
                converted.getPersistentData().putBoolean(ROLLED_TAG, true);
            }
            if (isVariant(old)) {
                converted.getPersistentData().putBoolean(VARIANT_TAG, true);
            }
        }
    }

    /** 主动支援原版僵尸、骷髅系；不要求已经配发强化装备。 */
    public static boolean isSupportAlly(LivingEntity entity) {
        return entity.isAlive() && entity instanceof Mob mob && MobBackpack.isCarrier(mob)
                && UndeadCombat.isFactionMember(mob);
    }

    /** 负面药水排除所有亡灵，包括其他模组注册并带亡灵标签的生物。 */
    public static boolean isPotionEnemy(@Nullable LivingEntity owner, LivingEntity target) {
        return !target.is(EntityTypeTags.UNDEAD) && isEnemy(owner, target);
    }

    public static boolean isEnemy(@Nullable LivingEntity owner, LivingEntity target) {
        return target.isAlive() && !target.isSpectator() && !UndeadCombat.isFactionMember(target)
                && !(target instanceof Player player && player.isCreative())
                && (owner == null || owner != target && !owner.isAlliedTo(target));
    }
}
