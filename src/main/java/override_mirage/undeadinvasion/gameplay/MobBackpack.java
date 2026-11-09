package override_mirage.undeadinvasion.gameplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.BlocksAttacks;
import org.jetbrains.annotations.Nullable;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.ai.BowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.CrossbowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.LeapAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.MountSeekGoal;
import override_mirage.undeadinvasion.gameplay.ai.ProjectileImmuneMeleeGoal;
import override_mirage.undeadinvasion.gameplay.ai.ShieldBlockGoal;
import override_mirage.undeadinvasion.gameplay.ai.TridentAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.WallPiercingTargetGoal;
import override_mirage.undeadinvasion.gameplay.ai.WeaponSwitchGoal;

/**
 * 亡灵「背包」：为僵尸、骷髅、溺尸按天数配发装备与武器。
 *
 * <p>档位表（含首尾）：
 * <ul>
 *   <li>第 0–9 天：无装备，保留原版随机外观。</li>
 *   <li>第 10–19 天：皮革套 + 木制武器。</li>
 *   <li>第 20–29 天：铜制套 + 铜制武器。</li>
 *   <li>第 30–39 天：金制套 + 金制武器 + 盾牌。</li>
 *   <li>第 40–49 天：锁链套 + 铁制武器 + 盾牌（锁链无对应武器，按铁制处理）。</li>
 *   <li>第 50–59 天：铁制套 + 铁制武器 + 盾牌。</li>
 *   <li>第 60–69 天：钻石套 + 钻石武器 + 盾牌。</li>
 *   <li>第 70 天起：下界合金套 + 下界合金武器 + 盾牌（附魔档位待补，暂以本档为上限）。</li>
 * </ul>
 *
 * <p>普通亡灵从第 30 天起必定佩戴盾牌（副手）。
 *
 * <p>溺尸在拿到盾牌之后（第 30 天起）会固定成「主手三叉戟 + 副手盾牌」，不再随机；
 * 第 30 天之前与其它亡灵一致，按档位随机武器、没有盾牌。投掷不依赖副手是否为空，
 * 因此持盾状态下照常掷矛。
 *
 * <p>武器分两类，三叉戟同时属于两类：
 * <ul>
 *   <li>近战：长矛概率为 5%，剑、斧、三叉戟平分剩余 95%。</li>
 *   <li>远程：弓、弩、三叉戟。</li>
 * </ul>
 * 普通亡灵两把都配，{@link WeaponSwitchGoal} 按目标距离自动切换：5 格内用近战，6 格外用远程。
 * 备用武器不放在副手（否则骷髅会把副手的弓误判为手持弓，永远切不回近战），
 * 而是记录武器下标，需要时再换上；副手留给盾牌。
 *
 * <p>骑乘僵尸马/骷髅马的单位是例外：主手固定为本档长矛，不配远程武器，也不会触发距离切换。
 */
public final class MobBackpack {
    // 档位起始天数来自配置（config/undeadinvasion-common.toml），见 UndeadConfig。

    /** 记录已应用的档位（{@code Kit#fromDay}，0 表示当前无档位）。 */
    private static final String KIT_TAG = "undeadinvasion:backpack_kit";
    /** 近战武器在武器池中的下标。 */
    private static final String MELEE_INDEX_TAG = "undeadinvasion:melee_index";
    /** 远程武器在武器池中的下标。 */
    private static final String RANGED_INDEX_TAG = "undeadinvasion:ranged_index";
    private static final int NO_KIT = 0;
    /** 普通亡灵随机配发长矛的概率（百分比）。 */
    private static final int SPEAR_CHANCE_PERCENT = 5;

    /** 背包会管理的装备槽。 */
    private static final List<EquipmentSlot> MANAGED_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);

    /** 护甲槽顺序：头盔、胸甲、护腿、靴子。 */
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private static final List<Item> LEATHER_ARMOR = List.of(
            Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
    private static final List<Item> COPPER_ARMOR = List.of(
            Items.COPPER_HELMET, Items.COPPER_CHESTPLATE, Items.COPPER_LEGGINGS, Items.COPPER_BOOTS);
    private static final List<Item> GOLDEN_ARMOR = List.of(
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS);
    private static final List<Item> CHAINMAIL_ARMOR = List.of(
            Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS);
    private static final List<Item> IRON_ARMOR = List.of(
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
    private static final List<Item> DIAMOND_ARMOR = List.of(
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
    private static final List<Item> NETHERITE_ARMOR = List.of(
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);

    private static final List<Item> WOODEN_MELEE = List.of(
            Items.WOODEN_SWORD, Items.WOODEN_AXE, Items.WOODEN_SPEAR, Items.TRIDENT);
    private static final List<Item> COPPER_MELEE = List.of(
            Items.COPPER_SWORD, Items.COPPER_AXE, Items.COPPER_SPEAR, Items.TRIDENT);
    private static final List<Item> GOLDEN_MELEE = List.of(
            Items.GOLDEN_SWORD, Items.GOLDEN_AXE, Items.GOLDEN_SPEAR, Items.TRIDENT);
    private static final List<Item> IRON_MELEE = List.of(
            Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_SPEAR, Items.TRIDENT);
    private static final List<Item> DIAMOND_MELEE = List.of(
            Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_SPEAR, Items.TRIDENT);
    private static final List<Item> NETHERITE_MELEE = List.of(
            Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.NETHERITE_SPEAR, Items.TRIDENT);

    /** 远程武器池：弓、弩、三叉戟。 */
    private static final List<Item> RANGED = List.of(Items.BOW, Items.CROSSBOW, Items.TRIDENT);

    /** 档位缓存：配置快照变化时重建。 */
    private static volatile List<Kit> kitCache;
    private static volatile UndeadConfig.Settings kitCacheSettings;

    /** 所有档位里的护甲，用于判断某个护甲槽是不是背包发的。 */
    private static final Set<Item> KIT_ARMOR = collectKitArmor();

    /** 所有档位与武器池里出现过的物品，用于区分「背包发的」与「其它来源的」。 */
    private static final Set<Item> KIT_ITEMS = collectKitItems();

    private MobBackpack() {}

    /**
     * 判定该实体是否携带背包。
     *
     * <p>覆盖主世界亡灵：僵尸（含尸壳、僵尸村民、溺尸）与骷髅（含流浪者、沼骸、干枯者）。
     * 溺尸有专门规则（固定三叉戟 + 盾牌），见 {@link #sync}。
     * 排除僵尸猪灵（下界中立生物）与凋灵骷髅（下界精英）。
     */
    public static boolean isCarrier(Mob mob) {
        if (mob instanceof ZombifiedPiglin || mob instanceof WitherSkeleton) {
            return false;
        }
        return mob instanceof Zombie || mob instanceof AbstractSkeleton;
    }

    public static boolean hasKitForDay(long day) {
        return kitFor(day) != null;
    }

    public static boolean isReinforced(Mob mob) {
        return isCarrier(mob) && UndeadCombat.isFactionMember(mob) && storedKit(mob) != null;
    }

    /**
     * 把实体装备同步到指定天数对应的档位。可重复调用：档位与装备都一致时直接返回。
     *
     * @param mob    目标实体（服务端）
     * @param day    当前独立天数
     * @param random 用于抽取武器的随机源
     */
    public static void sync(Mob mob, long day, RandomSource random) {
        // 修正旧盾牌上的 5 tick 举盾延迟（原版盾牌默认 0.25 秒）。
        makeShieldFast(mob.getItemBySlot(EquipmentSlot.OFFHAND));

        // 第 10 天起：僵尸可以破门（穿墙索敌由 WallPiercingTargetGoal 负责）。
        if (mob instanceof Zombie zombie) {
            ZombieSiege.apply(zombie, day);
        }

        Kit kit = kitFor(day);

        // 溺尸在配发盾牌的档位（第 30 天起）锁定为三叉戟 + 盾牌；更早的档位按普通亡灵处理。
        if (mob instanceof Drowned drowned && kit != null && kit.shield()) {
            syncDrownedLocked(drowned, kit);
            installCombatAi(mob);
            DispenserUndead.sync(mob);
            return;
        }

        int targetKit = kit == null ? NO_KIT : (int) kit.fromDay();

        CompoundTag data = mob.getPersistentData();
        int storedKit = data.getIntOr(KIT_TAG, NO_KIT);
        boolean tracked = data.contains(KIT_TAG);
        boolean tierChanged = !tracked || storedKit != targetKit;

        if (tierChanged) {
            if (kit == null) {
                strip(mob);
            } else {
                // 首次同步（含老存档里的旧亡灵）直接覆盖原版随机装备；
                // 已同步过的只替换背包自己发的物品，避免抹掉玩家塞给它的东西。
                apply(mob, kit, random, !tracked, true);
            }
            data.putInt(KIT_TAG, targetKit);
        } else if (kit != null && !matches(mob, kit)) {
            // 档位没变但缺件（被扒装备等）：按原武器补齐，不重新掷骰。
            apply(mob, kit, random, false, false);
        }

        if (kit == null) {
            // 第 0–9 天没有档位装备：骷髅默认拿一把弓（对应原版骷髅）。
            ensureBaselineWeapon(mob);
        }

        installCombatAi(mob);
        DispenserUndead.sync(mob);
    }

    /**
     * 溺尸在配发盾牌后的锁定配置：护甲按档位，武器固定主手三叉戟、副手盾牌。
     *
     * <p>只在 {@code kit.shield()} 为真的档位调用（第 30 天起）；更早的档位走普通随机逻辑。
     * 武器下标一并写成三叉戟，保证距离切换与缺件补装读到的是同一把武器。
     */
    private static void syncDrownedLocked(Drowned mob, Kit kit) {
        CompoundTag data = mob.getPersistentData();
        int targetKit = (int) kit.fromDay();
        boolean tracked = data.contains(KIT_TAG);
        boolean tierChanged = !tracked || data.getIntOr(KIT_TAG, NO_KIT) != targetKit;

        if (tierChanged) {
            applyArmor(mob, kit, !tracked);
            data.putInt(KIT_TAG, targetKit);
        } else if (!armorMatches(mob, kit)) {
            applyArmor(mob, kit, false);
        }

        int meleeIndex = kit.meleeWeapons().indexOf(Items.TRIDENT);
        int rangedIndex = kit.rangedWeapons().indexOf(Items.TRIDENT);
        if (meleeIndex >= 0) {
            data.putInt(MELEE_INDEX_TAG, meleeIndex);
        }
        if (rangedIndex >= 0) {
            data.putInt(RANGED_INDEX_TAG, rangedIndex);
        }

        // 主手三叉戟、副手盾牌：溺尸的固定配置，缺失就补上。
        if (!(mob.getMainHandItem().getItem() instanceof TridentItem)) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        }
        if (mob.getOffhandItem().getItem() != Items.SHIELD) {
            mob.setItemSlot(EquipmentSlot.OFFHAND, createShield());
        } else {
            makeShieldFast(mob.getOffhandItem());
        }

        onWeaponChanged(mob);
    }

    /**
     * 当前档位配发的近战武器；未达首档或数据缺失时返回 {@code null}。
     */
    public static @Nullable Item meleeItem(Mob mob) {
        Kit kit = storedKit(mob);
        if (kit == null) {
            return null;
        }
        List<Item> pool = kit.meleeWeapons();
        int index = mob.getPersistentData().getIntOr(MELEE_INDEX_TAG, -1);
        return index >= 0 && index < pool.size() ? pool.get(index) : null;
    }

    /**
     * 当前档位配发的远程武器；未达首档或数据缺失时返回 {@code null}。
     */
    public static @Nullable Item rangedItem(Mob mob) {
        Kit kit = storedKit(mob);
        if (kit == null) {
            return null;
        }
        List<Item> pool = kit.rangedWeapons();
        int index = mob.getPersistentData().getIntOr(RANGED_INDEX_TAG, -1);
        return index >= 0 && index < pool.size() ? pool.get(index) : null;
    }

    /**
     * 生成该实体手中应有的武器实体。
     */
    public static ItemStack weaponStack(Item item) {
        return new ItemStack(item);
    }

    /**
     * 武器被换上后需要重新评估 AI（骷髅的弓/近战目标由原版方法切换）。
     */
    public static void onWeaponChanged(Mob mob) {
        if (mob instanceof AbstractSkeleton skeleton) {
            skeleton.reassessWeaponGoal();
        }
    }

    /**
     * 未到首个装备档位时的基础武器：骷髅持弓，其余保持空手。
     *
     * <p>只补主手，不加护甲；玩家塞给它的别的武器（非弓）不会被强行替换。
     */
    private static void ensureBaselineWeapon(Mob mob) {
        if (!(mob instanceof AbstractSkeleton) || mob instanceof WitherSkeleton) {
            return;
        }
        if (mob.getItemBySlot(EquipmentSlot.MAINHAND).getItem() instanceof BowItem) {
            return;
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        onWeaponChanged(mob);
    }

    private static @Nullable Kit storedKit(Mob mob) {
        int stored = mob.getPersistentData().getIntOr(KIT_TAG, NO_KIT);
        return stored == NO_KIT ? null : kitFor(stored);
    }

    /**
     * 装备是否与档位一致（缺件时会在下次同步补齐）。
     */
    private static boolean matches(Mob mob, Kit kit) {
        if (!armorMatches(mob, kit)) {
            return false;
        }
        if (kit.shield() && mob.getItemBySlot(EquipmentSlot.OFFHAND).getItem() != Items.SHIELD) {
            return false;
        }

        Item held = mob.getItemBySlot(EquipmentSlot.MAINHAND).getItem();
        CompoundTag data = mob.getPersistentData();

        // 骆驼骑士：前座固定长矛、后座固定弓。
        if (isLancer(mob)) {
            return held == kit.spear()
                    && data.getIntOr(MELEE_INDEX_TAG, -1) == kit.meleeWeapons().indexOf(kit.spear())
                    && data.getIntOr(RANGED_INDEX_TAG, -1) == -1;
        }
        if (isArcher(mob)) {
            return held == Items.BOW && data.getIntOr(MELEE_INDEX_TAG, -1) == -1
                    && data.getIntOr(RANGED_INDEX_TAG, -1) == kit.rangedWeapons().indexOf(Items.BOW);
        }

        int meleeIndex = data.getIntOr(MELEE_INDEX_TAG, -1);
        int rangedIndex = data.getIntOr(RANGED_INDEX_TAG, -1);
        if (meleeIndex < 0 || meleeIndex >= kit.meleeWeapons().size()) {
            return false;
        }
        if (rangedIndex < 0 || rangedIndex >= kit.rangedWeapons().size()) {
            return false;
        }

        // 主手必须是本档位的近战或远程武器之一（切换目标会在两者间来回换）。
        return kit.meleeWeapons().contains(held) || kit.rangedWeapons().contains(held);
    }

    /**
     * 前排骑兵：骑僵尸马/骷髅马，或坐在骆驼尸壳的前座。固定长矛、不带远程武器。
     */
    private static boolean isLancer(Mob mob) {
        Entity vehicle = mob.getVehicle();
        if (vehicle instanceof ZombieHorse || vehicle instanceof SkeletonHorse) {
            return true;
        }
        return vehicle instanceof CamelHusk camel && camel.getPassengers().indexOf(mob) == 0;
    }

    /**
     * 后排射手：坐在骆驼尸壳的后座。固定弓、不切近战。
     */
    private static boolean isArcher(Mob mob) {
        return mob.getVehicle() instanceof CamelHusk camel && camel.getPassengers().indexOf(mob) > 0;
    }

    private static boolean armorMatches(Mob mob, Kit kit) {
        List<Item> armor = kit.armor();
        for (int i = 0; i < ARMOR_SLOTS.size(); i++) {
            Item expected = ARMOR_SLOTS.get(i) == EquipmentSlot.HEAD && DispenserUndead.isVariant(mob)
                    ? Items.DISPENSER : armor.get(i);
            if (mob.getItemBySlot(ARMOR_SLOTS.get(i)).getItem() != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * 写入档位装备。
     *
     * <p>换档时优先保留原武器：新档位武器池里还有同款就继续用（三叉戟、弓、弩跨档位通用），
     * 只有整套换材质时才会重掷。
     *
     * @param overwrite 是否覆盖非背包物品（首次同步时为 true）
     * @param reroll    是否重新抽取武器（换档时 true，补件时 false）
     */
    private static void apply(Mob mob, Kit kit, RandomSource random, boolean overwrite, boolean reroll) {
        applyArmor(mob, kit, overwrite);

        List<Item> melee = kit.meleeWeapons();
        List<Item> ranged = kit.rangedWeapons();
        CompoundTag data = mob.getPersistentData();

        int meleeIndex;
        int rangedIndex;

        if (isLancer(mob)) {
            // 前排骑兵：主手固定本档长矛，远程下标清空，切换目标因此不会再启动。
            meleeIndex = melee.indexOf(kit.spear());
            rangedIndex = -1;
        } else if (isArcher(mob)) {
            // 后排射手：主手固定弓，近战下标清空，切换目标因此不会再启动。
            meleeIndex = -1;
            rangedIndex = ranged.indexOf(Items.BOW);
        } else if (reroll) {
            Item previousMelee = storedItem(mob, MELEE_INDEX_TAG, false);
            Item previousRanged = storedItem(mob, RANGED_INDEX_TAG, true);

            meleeIndex = previousMelee != null && melee.contains(previousMelee)
                    ? melee.indexOf(previousMelee)
                    : randomMeleeIndex(kit, random);

            Item rangedItem = previousRanged != null && ranged.contains(previousRanged)
                    ? previousRanged
                    : ranged.get(random.nextInt(ranged.size()));
            rangedIndex = ranged.indexOf(rangedItem);
        } else {
            meleeIndex = data.getIntOr(MELEE_INDEX_TAG, -1);
            rangedIndex = data.getIntOr(RANGED_INDEX_TAG, -1);
            if (meleeIndex < 0 || meleeIndex >= melee.size()) {
                meleeIndex = randomMeleeIndex(kit, random);
            }
            if (rangedIndex < 0 || rangedIndex >= ranged.size()) {
                rangedIndex = random.nextInt(ranged.size());
            }
        }

        data.putInt(MELEE_INDEX_TAG, meleeIndex);
        data.putInt(RANGED_INDEX_TAG, rangedIndex);

        Item mainHand = meleeIndex >= 0 ? melee.get(meleeIndex) : ranged.get(rangedIndex);
        setSlot(mob, EquipmentSlot.MAINHAND, mainHand, overwrite);

        // 副手留给盾牌；备用武器存在数据里，不放副手。
        if (kit.shield()) {
            setStack(mob, EquipmentSlot.OFFHAND, createShield(), overwrite);
        } else {
            clearKitItem(mob, EquipmentSlot.OFFHAND);
        }

        onWeaponChanged(mob);
    }

    /** 长矛单独按 5% 抽取，其余近战武器等概率分配。 */
    private static int randomMeleeIndex(Kit kit, RandomSource random) {
        int spearIndex = kit.meleeWeapons().indexOf(kit.spear());
        if (random.nextInt(100) < SPEAR_CHANCE_PERCENT) {
            return spearIndex;
        }
        int index = random.nextInt(kit.meleeWeapons().size() - 1);
        return index >= spearIndex ? index + 1 : index;
    }

    private static void applyArmor(Mob mob, Kit kit, boolean overwrite) {
        List<Item> armor = kit.armor();
        for (int i = 0; i < ARMOR_SLOTS.size(); i++) {
            if (ARMOR_SLOTS.get(i) == EquipmentSlot.HEAD && DispenserUndead.isVariant(mob)) {
                continue;
            }
            setSlot(mob, ARMOR_SLOTS.get(i), armor.get(i), overwrite);
        }
    }

    /**
     * 取出当前档位下标对应的武器，用于换档时判断能否沿用。
     */
    private static @Nullable Item storedItem(Mob mob, String tag, boolean ranged) {
        Kit kit = storedKit(mob);
        if (kit == null) {
            return null;
        }
        List<Item> pool = ranged ? kit.rangedWeapons() : kit.meleeWeapons();
        int index = mob.getPersistentData().getIntOr(tag, -1);
        return index >= 0 && index < pool.size() ? pool.get(index) : null;
    }

    /**
     * 生成模组专用盾牌：把原版 0.25 秒的举盾延迟清零，做到「举起来就生效」。
     */
    private static ItemStack createShield() {
        ItemStack stack = new ItemStack(Items.SHIELD);
        makeShieldFast(stack);
        return stack;
    }

    /**
     * 把盾牌的格挡延迟改为 0；已经是 0 或不是盾牌时直接返回。
     */
    public static void makeShieldFast(ItemStack stack) {
        if (!stack.is(Items.SHIELD)) {
            return;
        }
        BlocksAttacks base = stack.get(DataComponents.BLOCKS_ATTACKS);
        if (base == null || base.blockDelaySeconds() <= 0.0F) {
            return;
        }
        stack.set(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(
                0.0F,
                base.disableCooldownScale(),
                base.damageReductions(),
                base.itemDamage(),
                base.bypassedBy(),
                base.blockSound(),
                base.disableSound()));
    }

    private static void setStack(Mob mob, EquipmentSlot slot, ItemStack stack, boolean overwrite) {
        ItemStack current = mob.getItemBySlot(slot);
        if (!overwrite) {
            if (!current.isEmpty() && !isKitItem(current)) {
                return;
            }
            if (current.is(stack.getItem())) {
                return;
            }
        }
        mob.setItemSlot(slot, stack);
    }

    private static void setSlot(Mob mob, EquipmentSlot slot, Item item, boolean overwrite) {
        ItemStack current = mob.getItemBySlot(slot);
        if (!overwrite) {
            // 外来物品不动；已经是目标物品则跳过，避免重复装备包。
            if (!current.isEmpty() && !isKitItem(current)) {
                return;
            }
            if (current.getItem() == item) {
                return;
            }
        }
        mob.setItemSlot(slot, new ItemStack(item));
    }

    private static void clearKitItem(Mob mob, EquipmentSlot slot) {
        ItemStack current = mob.getItemBySlot(slot);
        if (!current.isEmpty() && isKitItem(current)) {
            mob.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /**
     * 卸下背包发放的装备；不碰其它来源的物品。
     */
    private static void strip(Mob mob) {
        boolean changed = false;
        for (EquipmentSlot slot : MANAGED_SLOTS) {
            ItemStack current = mob.getItemBySlot(slot);
            if (!current.isEmpty() && isKitItem(current)) {
                mob.setItemSlot(slot, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (changed) {
            onWeaponChanged(mob);
        }
    }

    /**
     * 安装战斗相关目标：距离切换、远程 AI。
     *
     * <p>优先级按生物类型区分：骷髅原版近战目标在 4，取 3；僵尸/溺尸原版近战目标在 3，必须取 2。
     */
    private static void installCombatAi(Mob mob) {
        // 按当前实例的目标选择器判断；区块重载后同 UUID 的新实例也必须补回战斗和索敌 AI。
        if (mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof WeaponSwitchGoal)) {
            return;
        }

        int priority = mob instanceof AbstractSkeleton ? 3 : 2;

        mob.goalSelector.addGoal(0, new WeaponSwitchGoal(mob));
        // 每 5 秒主动找一次附近坐骑，不依赖生成/载入顺序。
        mob.goalSelector.addGoal(0, new MountSeekGoal(mob));
        // 响应空中投射物与生物近战攻击，破盾冷却期间禁用格挡。
        mob.goalSelector.addGoal(0, new ShieldBlockGoal(mob));
        // 持剑/斧/三叉戟时向目标起跳并空中命中。
        mob.goalSelector.addGoal(1, new LeapAttackGoal(mob));
        if (mob instanceof PathfinderMob pathfinder) {
            // 投射物无效时主动靠近近战，优先于原版溺尸掷矛和骷髅射箭。
            mob.goalSelector.addGoal(1, new ProjectileImmuneMeleeGoal(pathfinder));
        }

        if (UndeadCombat.isFactionMember(mob)) {
            // 在原版索敌之外主动攻击其他模组的生物，不要求对方先造成伤害。
            mob.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, true,
                    (target, level) -> UndeadCombat.isModdedMob(target)));
        }

        if (mob instanceof Zombie) {
            // 第 10 天起无视视线锁定玩家 / 村民 / 铁傀儡与模组生物。
            mob.targetSelector.addGoal(0, new WallPiercingTargetGoal(mob));
        }

        // 远程攻击半径跟随穿墙索敌半径，否则锁定了 32 格外的目标却不敢开火。
        float rangedRadius = (float) Math.max(15.0, UndeadConfig.settings().siegeRange());
        if (!(mob instanceof AbstractSkeleton)) {
            // 骷髅自带的弓 AI 已可用，只给其它亡灵补。
            mob.goalSelector.addGoal(priority, new BowAttackGoal(mob, 1.0, rangedRadius));
        }
        mob.goalSelector.addGoal(priority, new CrossbowAttackGoal(mob, 1.0, rangedRadius));
        // 溺尸原版掷矛在优先级 2、射程仅 10 格，必须让扩展射程的目标优先接管。
        mob.goalSelector.addGoal(mob instanceof Drowned ? 1 : priority, new TridentAttackGoal(mob, rangedRadius));
    }

    private static boolean isKitItem(ItemStack stack) {
        return KIT_ITEMS.contains(stack.getItem());
    }

    /** 皮革档起始天数。 */
    public static long leatherDay() {
        return UndeadConfig.settings().leatherDay();
    }

    /** 铜制档起始天数。 */
    public static long copperDay() {
        return UndeadConfig.settings().copperDay();
    }

    /** 金制档起始天数。 */
    public static long goldDay() {
        return UndeadConfig.settings().goldDay();
    }

    /** 锁链档起始天数。 */
    public static long chainDay() {
        return UndeadConfig.settings().chainDay();
    }

    /** 铁制档起始天数。 */
    public static long ironDay() {
        return UndeadConfig.settings().ironDay();
    }

    /** 钻石档起始天数。 */
    public static long diamondDay() {
        return UndeadConfig.settings().diamondDay();
    }

    /** 下界合金档起始天数。 */
    public static long netheriteDay() {
        return UndeadConfig.settings().netheriteDay();
    }

    /**
     * 当前配置下的档位表；配置变化后自动重建。
     */
    private static List<Kit> kits() {
        UndeadConfig.Settings current = UndeadConfig.settings();
        List<Kit> cache = kitCache;
        if (cache == null || kitCacheSettings != current) {
            cache = buildKits(current);
            kitCache = cache;
            kitCacheSettings = current;
        }
        return cache;
    }

    private static List<Kit> buildKits(UndeadConfig.Settings s) {
        List<Kit> kits = new ArrayList<>(List.of(
                new Kit(s.leatherDay(), LEATHER_ARMOR, WOODEN_MELEE, RANGED, Items.WOODEN_SPEAR,
                        s.leatherDay() >= s.shieldDay()),
                new Kit(s.copperDay(), COPPER_ARMOR, COPPER_MELEE, RANGED, Items.COPPER_SPEAR,
                        s.copperDay() >= s.shieldDay()),
                new Kit(s.goldDay(), GOLDEN_ARMOR, GOLDEN_MELEE, RANGED, Items.GOLDEN_SPEAR,
                        s.goldDay() >= s.shieldDay()),
                new Kit(s.chainDay(), CHAINMAIL_ARMOR, IRON_MELEE, RANGED, Items.IRON_SPEAR,
                        s.chainDay() >= s.shieldDay()),
                new Kit(s.ironDay(), IRON_ARMOR, IRON_MELEE, RANGED, Items.IRON_SPEAR,
                        s.ironDay() >= s.shieldDay()),
                new Kit(s.diamondDay(), DIAMOND_ARMOR, DIAMOND_MELEE, RANGED, Items.DIAMOND_SPEAR,
                        s.diamondDay() >= s.shieldDay()),
                new Kit(s.netheriteDay(), NETHERITE_ARMOR, NETHERITE_MELEE, RANGED, Items.NETHERITE_SPEAR,
                        s.netheriteDay() >= s.shieldDay())));
        // 允许配置里乱序填写，这里统一按起始天数升序，kitFor 取「最后一个满足条件的档位」才正确。
        kits.sort(Comparator.comparingLong(Kit::fromDay));
        return List.copyOf(kits);
    }

    private static Kit kitFor(long day) {
        Kit selected = null;
        for (Kit kit : kits()) {
            if (day >= kit.fromDay()) {
                selected = kit;
            }
        }
        return selected;
    }

    private static Set<Item> collectKitArmor() {
        Set<Item> items = new HashSet<>();
        items.addAll(LEATHER_ARMOR);
        items.addAll(COPPER_ARMOR);
        items.addAll(GOLDEN_ARMOR);
        items.addAll(CHAINMAIL_ARMOR);
        items.addAll(IRON_ARMOR);
        items.addAll(DIAMOND_ARMOR);
        items.addAll(NETHERITE_ARMOR);
        return Set.copyOf(items);
    }

    private static Set<Item> collectKitItems() {
        Set<Item> items = new HashSet<>(KIT_ARMOR);
        items.add(Items.SHIELD);
        items.addAll(WOODEN_MELEE);
        items.addAll(COPPER_MELEE);
        items.addAll(GOLDEN_MELEE);
        items.addAll(IRON_MELEE);
        items.addAll(DIAMOND_MELEE);
        items.addAll(NETHERITE_MELEE);
        items.addAll(RANGED);
        return Set.copyOf(items);
    }

    /**
     * 一个装备档位。
     *
     * @param fromDay       生效天数（含）
     * @param armor         护甲四件套，顺序为头盔、胸甲、护腿、靴子
     * @param meleeWeapons  近战武器池
     * @param rangedWeapons 远程武器池
     * @param spear         本档位的长矛，骑乘单位固定使用
     * @param shield        是否配发副手盾牌
     */
    private record Kit(long fromDay, List<Item> armor, List<Item> meleeWeapons, List<Item> rangedWeapons,
                       Item spear, boolean shield) {}
}
