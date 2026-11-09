package override_mirage.undeadinvasion.gameplay;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.nautilus.ZombieNautilus;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.skeleton.Parched;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import override_mirage.undeadinvasion.config.UndeadConfig;

/**
 * 亡灵坐骑：生成时有概率骑乘，坐骑本身也按天数穿戴护甲。
 *
 * <ul>
 *   <li>僵尸（含尸壳、僵尸村民）→ 僵尸马</li>
 *   <li>骷髅（含流浪者、沼骸、干枯者）→ 骷髅马</li>
 *   <li>溺尸 → 僵尸鹦鹉螺</li>
 * </ul>
 *
 * <p>坐骑护甲写入 {@code EquipmentSlot.BODY}，与原版马铠/鹦鹉螺铠同一槽位，
 * 因此会正常渲染、被玩家替换，并在死亡时掉落。
 */
public final class MobMounts {
    /** 默认坐骑概率，实际值来自配置。 */
    private static final float DEFAULT_MOUNT_CHANCE = 0.05F;

    /** 寻找附近骆驼尸壳的半径（格）。 */
    private static final double SEARCH_RADIUS = 16.0;

    /** 新建坐骑在指定骑手入座前，禁止其它逻辑拉人上马。 */
    private static final String RESERVED_TAG = "undeadinvasion:mount_reserved";


    /** 本模组会发放的坐骑护甲，用于天数回退时清理。 */
    private static final Set<Item> MOUNT_ARMOR = Set.of(
            Items.LEATHER_HORSE_ARMOR, Items.COPPER_HORSE_ARMOR, Items.IRON_HORSE_ARMOR,
            Items.GOLDEN_HORSE_ARMOR, Items.DIAMOND_HORSE_ARMOR, Items.NETHERITE_HORSE_ARMOR,
            Items.COPPER_NAUTILUS_ARMOR, Items.GOLDEN_NAUTILUS_ARMOR, Items.IRON_NAUTILUS_ARMOR,
            Items.DIAMOND_NAUTILUS_ARMOR, Items.NETHERITE_NAUTILUS_ARMOR);

    private MobMounts() {}

    /**
     * 按概率给刚生成的亡灵配上坐骑，并让坐骑立刻穿上对应天数的护甲。
     *
     * @param level 所在服务端世界
     * @param rider 刚生成的亡灵
     * @param day   当前独立天数
     */
    public static void tryMount(ServerLevel level, Mob rider, long day) {
        if (rider.isPassenger() || rider.isVehicle() || ZombieSiege.shouldStayOnFoot(rider)) {
            // 已经骑在别的东西上（例如鸡骑士），或者本身就载着乘客，不再叠加。
            return;
        }

        EntityType<? extends Mob> mountType = mountTypeFor(rider);
        float chance = UndeadConfig.settings().mountChance();
        if (mountType == null || chance <= 0.0F || level.getRandom().nextFloat() >= chance) {
            return;
        }

        Mob mount = mountType.create(level, EntitySpawnReason.JOCKEY);
        if (mount == null) {
            return;
        }

        mount.snapTo(rider.getX(), rider.getY(), rider.getZ(), rider.getYRot(), 0.0F);
        mount.setPersistenceRequired();
        ensureMountReady(mount);
        // 入场瞬间会触发「坐骑找骑手」，先预定住，避免别人抢座。
        setReserved(mount, true);
        if (!level.addFreshEntity(mount)) {
            setReserved(mount, false);
            return;
        }

        rider.startRiding(mount, true, true);
        setReserved(mount, false);
        syncMount(mount, day);
        // 骑上马之后骑手切换成「长矛骑兵」配置（骑乘单位固定长矛、不带远程）。
        MobBackpack.sync(rider, day, rider.getRandom());
    }

    /**
     * 生成时、读档与跨天重刷时的自动上骑：
     * <ul>
     *   <li>尸壳/焦骸 → 坐现成的骆驼尸壳（座位规则见各自方法）。</li>
     *   <li>其它僵尸 → 骑附近现成的僵尸马。</li>
     *   <li>其它骷髅 → 骑附近现成的骷髅马。</li>
     * </ul>
     *
     * <p>这里**只寻找已存在的坐骑，不生成任何新实体**——坐骑的创建只发生在
     * 5% 随机坐骑与骆驼骑士组队那两处。
     */
    public static void tryAutoMount(ServerLevel level, Mob mob, long day) {
        if (mob.isPassenger() || mob.isVehicle() || ZombieSiege.shouldStayOnFoot(mob)) {
            return;
        }

        if (mob instanceof Husk husk) {
            tryCamelForHusk(level, husk, day);
            return;
        }
        if (mob instanceof Parched parched) {
            tryCamelForParched(level, parched, day);
            return;
        }

        // 溺尸找现成的僵尸鹦鹉螺。
        if (mob instanceof Drowned drowned) {
            ZombieNautilus nautilus = findNearby(level, drowned, ZombieNautilus.class);
            if (nautilus != null && !nautilus.isVehicle() && !isReserved(nautilus)) {
                ensureMountReady(nautilus);
                drowned.startRiding(nautilus, true, true);
                MobBackpack.sync(drowned, day, drowned.getRandom());
            }
            return;
        }

        // 只有僵尸与骷髅会去找马，其它实体直接忽略。
        if (mob instanceof Zombie) {
            if (mob instanceof ZombifiedPiglin) {
                return;
            }
            ZombieHorse horse = findNearby(level, mob, ZombieHorse.class);
            if (horse != null && !horse.isVehicle() && !isReserved(horse)) {
                ensureMountReady(horse);
                mob.startRiding(horse, true, true);
                MobBackpack.sync(mob, day, mob.getRandom());
            }
            return;
        }
        if (mob instanceof AbstractSkeleton) {
            if (mob instanceof WitherSkeleton) {
                return;
            }
            SkeletonHorse horse = findNearby(level, mob, SkeletonHorse.class);
            if (horse != null && !horse.isVehicle() && !isReserved(horse)) {
                ensureMountReady(horse);
                mob.startRiding(horse, true, true);
                MobBackpack.sync(mob, day, mob.getRandom());
            }
        }
    }

    /**
     * 尸壳找骆驼：优先空骆驼；其次只有一只焦骸的骆驼（焦骸退到后座）。
     */
    private static boolean tryCamelForHusk(ServerLevel level, Husk husk, long day) {
        CamelHusk empty = findCamel(level, husk, candidate -> candidate.getPassengers().isEmpty());
        if (empty != null) {
            husk.startRiding(empty, true, true);
            MobBackpack.sync(husk, day, husk.getRandom());
            return true;
        }

        CamelHusk occupied = findCamel(level, husk, candidate -> {
            return candidate.getPassengers().size() == 1 && candidate.getFirstPassenger() instanceof Parched;
        });
        if (occupied == null) {
            return false;
        }

        if (!(occupied.getFirstPassenger() instanceof Mob parched)) {
            return false;
        }

        // 焦骸先让出前排，尸壳坐前排（长矛），焦骸回到后排（弓）。
        parched.stopRiding();
        if (!husk.startRiding(occupied, true, true)) {
            parched.startRiding(occupied, true, true);
            return false;
        }
        parched.startRiding(occupied, true, true);

        MobBackpack.sync(husk, day, husk.getRandom());
        MobBackpack.sync(parched, day, parched.getRandom());
        return true;
    }

    /**
     * 焦骸找骆驼：空骆驼坐前座（长矛），已有尸壳的坐后座（弓）。
     */
    private static boolean tryCamelForParched(ServerLevel level, Parched parched, long day) {
        CamelHusk camel = findCamel(level, parched, candidate -> {
            int passengers = candidate.getPassengers().size();
            if (passengers == 0) {
                return true;
            }
            return passengers == 1 && candidate.getFirstPassenger() instanceof Husk;
        });
        if (camel == null) {
            return false;
        }
        ensureMountReady(camel);

        parched.startRiding(camel, true, true);
        // 坐前座时 isLancer 成立（换矛），坐后座时 isArcher 成立（换弓）。
        MobBackpack.sync(parched, day, parched.getRandom());
        return true;
    }

    /**
     * 骆驼载入时的反向补救：主动寻找附近的尸壳/焦骸补齐座位。
     *
     * <p>旧存档里实体载入顺序不固定，如果骑手比骆驼先载入，骑手那边找不到骆驼就会漏配；
     * 这里由骆驼发起一次匹配，覆盖这种情况。
     */
    public static void tryFillCamel(ServerLevel level, CamelHusk camel, long day) {
        ensureMountReady(camel);

        int passengers = camel.getPassengers().size();
        if (passengers >= 2) {
            return;
        }

        if (passengers == 0) {
            Mob husk = findNearby(level, camel, Husk.class);
            if (husk != null) {
                husk.startRiding(camel, true, true);
                MobBackpack.sync(husk, day, husk.getRandom());
                return;
            }
            Mob parched = findNearby(level, camel, Parched.class);
            if (parched != null) {
                parched.startRiding(camel, true, true);
                MobBackpack.sync(parched, day, parched.getRandom());
            }
            return;
        }

        if (camel.getFirstPassenger() instanceof Husk) {
            Mob parched = findNearby(level, camel, Parched.class);
            if (parched != null) {
                parched.startRiding(camel, true, true);
                MobBackpack.sync(parched, day, parched.getRandom());
            }
            return;
        }

        // 前座是焦骸：让尸壳坐前排，焦骸退到后排（换弓）。
        if (camel.getFirstPassenger() instanceof Mob parchedRider) {
            Mob husk = findNearby(level, camel, Husk.class);
            if (husk == null) {
                return;
            }
            parchedRider.stopRiding();
            if (!husk.startRiding(camel, true, true)) {
                parchedRider.startRiding(camel, true, true);
                return;
            }
            parchedRider.startRiding(camel, true, true);
            MobBackpack.sync(husk, day, husk.getRandom());
            MobBackpack.sync(parchedRider, day, parchedRider.getRandom());
        }
    }

    private static <T extends Mob> @Nullable T findNearby(ServerLevel level, Mob around, Class<T> type) {
        return findNearby(level, around, type, candidate -> true);
    }

    private static <T extends Mob> @Nullable T findNearby(ServerLevel level, Mob around, Class<T> type,
                                                          Predicate<T> extraFilter) {
        List<T> candidates = level.getEntitiesOfClass(type, around.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> candidate.isAlive() && !candidate.isPassenger() && !candidate.isVehicle()
                        && !ZombieSiege.shouldStayOnFoot(candidate)
                        && extraFilter.test(candidate));
        T nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (T candidate : candidates) {
            double distance = candidate.distanceToSqr(around);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * 由坐骑发起：找一个附近的同类坐上来（僵尸马找僵尸、骷髅马找骷髅、鹦鹉螺找溺尸）。
     */
    public static void tryFillMount(ServerLevel level, Mob mount, long day) {
        if (!mount.isAlive() || mount.isVehicle() || isReserved(mount)) {
            return;
        }
        ensureMountReady(mount);

        Mob rider;
        if (mount instanceof ZombieHorse) {
            rider = findNearby(level, mount, Zombie.class,
                    candidate -> !(candidate instanceof Drowned) && !(candidate instanceof ZombifiedPiglin)
                            && !(candidate instanceof Husk));
        } else if (mount instanceof SkeletonHorse) {
            rider = findNearby(level, mount, AbstractSkeleton.class,
                    candidate -> !(candidate instanceof WitherSkeleton) && !(candidate instanceof Parched));
        } else if (mount instanceof ZombieNautilus) {
            rider = findNearby(level, mount, Drowned.class);
        } else {
            return;
        }
        if (rider == null) {
            return;
        }

        rider.startRiding(mount, true, true);
        MobBackpack.sync(rider, day, rider.getRandom());
    }

    /**
     * 马匹骑手阵亡时的补位：骑手下车后，让附近最近的同类立刻骑上这匹马。
     */
    public static void onRiderDeath(ServerLevel level, Mob rider, long day) {
        if (!(rider.getVehicle() instanceof Mob mount)) {
            return;
        }
        if (!(mount instanceof ZombieHorse) && !(mount instanceof SkeletonHorse) && !(mount instanceof ZombieNautilus)) {
            return;
        }

        rider.stopRiding();
        if (mount.isVehicle()) {
            return;
        }

        Mob successor;
        if (mount instanceof ZombieHorse) {
            successor = findNearby(level, mount, Zombie.class,
                    candidate -> !(candidate instanceof Drowned) && !(candidate instanceof ZombifiedPiglin)
                            && !(candidate instanceof Husk));
        } else if (mount instanceof SkeletonHorse) {
            successor = findNearby(level, mount, AbstractSkeleton.class,
                    candidate -> !(candidate instanceof WitherSkeleton) && !(candidate instanceof Parched));
        } else {
            successor = findNearby(level, mount, Drowned.class);
        }
        if (successor == null) {
            return;
        }

        ensureMountReady(mount);
        successor.startRiding(mount, true, true);
        MobBackpack.sync(successor, day, successor.getRandom());
    }

    private static CamelHusk findCamel(ServerLevel level, Mob rider, Predicate<CamelHusk> filter) {
        List<CamelHusk> camels = level.getEntitiesOfClass(CamelHusk.class, rider.getBoundingBox().inflate(SEARCH_RADIUS));
        CamelHusk nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (CamelHusk camel : camels) {
            if (!camel.isAlive() || !filter.test(camel)) {
                continue;
            }
            double distance = camel.distanceToSqr(rider);
            if (distance < nearestDistance) {
                nearest = camel;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * 判断实体是否是本模组会管理的坐骑。
     */
    public static boolean isMount(Mob mob) {
        return mob instanceof ZombieHorse || mob instanceof SkeletonHorse || mob instanceof ZombieNautilus;
    }

    /**
     * 让坐骑处于可骑乘状态：只做驯服，不装马鞍。
     *
     * <p>驯服是必须的：原版 {@code AbstractHorse} 自带 {@code RunAroundLikeCrazyGoal}，
     * 未驯服且载人时会定期 {@code ejectPassengers()} 把骑手甩下去。模组用
     * {@code EntityType.create} 创建的坐骑不走自然生成流程，默认未驯服。
     *
     * <p>鞍不默认发放（生物骑乘本身不要求鞍）。模组坐骑如果空着却留着鞍
     * （例如旧版本加上的），顺手摘掉；正被骑着的保留，不去动玩家手里的状态。
     */
    public static void ensureMountReady(Mob mob) {
        if (mob instanceof AbstractHorse horse && !horse.isTamed()
                && (mob instanceof ZombieHorse || mob instanceof SkeletonHorse || mob instanceof CamelHusk)) {
            horse.setTamed(true);
        }
        if (isMount(mob) || mob instanceof CamelHusk) {
            stripSaddleIfIdle(mob);
        }
    }

    private static void stripSaddleIfIdle(Mob mount) {
        // 玩家自己在骑的坐骑不动，避免把人家放的鞍摘掉。
        for (Entity passenger : mount.getPassengers()) {
            if (passenger instanceof Player) {
                return;
            }
        }
        if (mount.getItemBySlot(EquipmentSlot.SADDLE).is(Items.SADDLE)) {
            mount.setItemSlot(EquipmentSlot.SADDLE, ItemStack.EMPTY);
        }
    }

    /**
     * 标记/解除「已被预定」状态：预定期间不允许自动补骑手，避免一匹马坐上两个人。
     */
    public static void setReserved(Mob mount, boolean reserved) {
        mount.getPersistentData().putBoolean(RESERVED_TAG, reserved);
    }

    private static boolean isReserved(Mob mount) {
        return mount.getPersistentData().getBooleanOr(RESERVED_TAG, false);
    }

    /**
     * 按天数给坐骑换护甲；天数低于首档时卸下本模组发过的护甲。
     */
    public static void syncMount(Mob mount, long day) {
        Item armor = bodyArmorFor(mount, day);
        ItemStack current = mount.getItemBySlot(EquipmentSlot.BODY);

        if (armor == null) {
            if (MOUNT_ARMOR.contains(current.getItem())) {
                mount.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
            }
            return;
        }
        if (current.getItem() == armor) {
            return;
        }
        mount.setBodyArmorItem(new ItemStack(armor));
    }

    private static @Nullable EntityType<? extends Mob> mountTypeFor(Mob rider) {
        if (rider instanceof Drowned) {
            return EntityType.ZOMBIE_NAUTILUS;
        }
        if (rider instanceof Husk || rider instanceof Parched) {
            // 尸壳与焦骸只骑骆驼尸壳，不参与随机马匹。
            return null;
        }
        if (rider instanceof AbstractSkeleton) {
            return EntityType.SKELETON_HORSE;
        }
        if (rider instanceof Zombie) {
            return EntityType.ZOMBIE_HORSE;
        }
        return null;
    }

    /**
     * 坐骑护甲按天数映射；没有对应材质的档位向上取最近的一种
     * （锁链 → 铁，皮革档的鹦鹉螺无甲）。
     */
    private static @Nullable Item bodyArmorFor(Mob mount, long day) {
        if (mount instanceof ZombieNautilus) {
            if (day >= MobBackpack.netheriteDay()) {
                return Items.NETHERITE_NAUTILUS_ARMOR;
            }
            if (day >= MobBackpack.diamondDay()) {
                return Items.DIAMOND_NAUTILUS_ARMOR;
            }
            if (day >= MobBackpack.ironDay()) {
                return Items.IRON_NAUTILUS_ARMOR;
            }
            if (day >= MobBackpack.goldDay()) {
                return Items.GOLDEN_NAUTILUS_ARMOR;
            }
            if (day >= MobBackpack.copperDay()) {
                return Items.COPPER_NAUTILUS_ARMOR;
            }
            return null;
        }

        if (day >= MobBackpack.netheriteDay()) {
            return Items.NETHERITE_HORSE_ARMOR;
        }
        if (day >= MobBackpack.diamondDay()) {
            return Items.DIAMOND_HORSE_ARMOR;
        }
        if (day >= MobBackpack.ironDay()) {
            return Items.IRON_HORSE_ARMOR;
        }
        if (day >= MobBackpack.goldDay()) {
            return Items.GOLDEN_HORSE_ARMOR;
        }
        if (day >= MobBackpack.copperDay()) {
            return Items.COPPER_HORSE_ARMOR;
        }
        if (day >= MobBackpack.leatherDay()) {
            return Items.LEATHER_HORSE_ARMOR;
        }
        return null;
    }
}
