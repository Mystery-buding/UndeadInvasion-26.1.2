package override_mirage.undeadinvasion.event;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.CamelKnights;
import override_mirage.undeadinvasion.gameplay.DesertSpawns;
import override_mirage.undeadinvasion.gameplay.DispenserUndead;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.MobMounts;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 亡灵背包装备与坐骑的同步入口。
 *
 * <p>触发点：
 * <ul>
 *   <li>生成前：沙漠群系拦截普通僵尸/骷髅的自然生成，只留尸壳与焦骸。</li>
 *   <li>进入世界：按天数穿戴装备；新生成的单位还可能组队成骆驼骑士或骑上马。</li>
 *   <li>实体转化：僵尸溺水变溺尸时补一次同步。</li>
 *   <li>前排阵亡：骆驼骑士的尸壳死后，后座焦骸补到前排并换成长矛。</li>
 *   <li>天数变化：{@link #resyncLoaded} 遍历已加载实体统一刷新。</li>
 * </ul>
 *
 * <p>选用「加入世界」而非「生成前」事件：原版会在 {@code finalizeSpawn} 中随机穿上盔甲、给骷髅配弓，
 * 只有在其之后写入才能保证背包内容最终生效。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class MobBackpackHandler {
    private MobBackpackHandler() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Mob mob = event.getEntity();
        if (DesertSpawns.shouldCancelNaturalSpawn(mob, event.getLevel(), mob.blockPosition(), event.getSpawnType())) {
            event.setSpawnCancelled(true);
            return;
        }
        if (!event.isSpawnCancelled()) {
            DispenserUndead.prepareSpawn(mob);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }

        // 修复历史上未驯服的坐骑（会被原版甩人逻辑踢下骑手）。
        MobMounts.ensureMountReady(mob);

        long day = DayCounterData.dayOf(level);

        if (MobBackpack.isCarrier(mob)) {
            DispenserUndead.finishSpawn(mob, day, mob.getRandom());
            MobBackpack.sync(mob, day, mob.getRandom());
            boolean freshSpawn = !event.loadedFromDisk();
            if (freshSpawn && !CamelKnights.isReserved(mob)) {
                // 只对新生成的亡灵掷坐骑骰，读档时补同步不会凭空多出坐骑。
                // 骆驼骑士优先于普通坐骑，避免同一只亡灵被两套系统同时处理。
                if (!CamelKnights.trySpawn(level, mob, day)) {
                    MobMounts.tryMount(level, mob, day);
                }
            }
            if (!CamelKnights.isReserved(mob)) {
                // 读档时也允许尸壳/焦骸去坐现成的骆驼尸壳（不会生成新实体）；
                // 生成新马只在 freshSpawn 时进行，避免每次载入都刷马。
                MobMounts.tryAutoMount(level, mob, day);
            }
        } else if (MobMounts.isMount(mob)) {
            MobMounts.syncMount(mob, day);
            // 坐骑入场时先尝试一次拉人；骑手侧每 5 秒还会主动来找。
            MobMounts.tryFillMount(level, mob, day);
        }
    }

    /**
     * 僵尸溺水转化成溺尸：实体类型变了但不会走「加入世界」，需要补一次同步。
     */
    @SubscribeEvent
    public static void onConversion(LivingConversionEvent.Post event) {
        if (!(event.getOutcome() instanceof Mob mob) || !MobBackpack.isCarrier(mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        DispenserUndead.inheritConversion(event.getEntity(), mob);
        MobBackpack.sync(mob, DayCounterData.dayOf(level), mob.getRandom());
    }

    /**
     * 旧存档兼容：骆驼载入时主动寻找附近骑手补座。
     */
    @SubscribeEvent
    public static void onCamelJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getEntity() instanceof CamelHusk camel) {
            MobMounts.tryFillCamel(level, camel, DayCounterData.dayOf(level));
        }
    }

    /**
     * 骆驼骑士的前排尸壳阵亡：让出座位，后座焦骸自动补到前排并换成长矛。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob dead)) {
            return;
        }
        if (!(dead.level() instanceof ServerLevel level)) {
            return;
        }

        long day = DayCounterData.dayOf(level);

        // 骆驼骑士任意一名乘客阵亡：让出座位，剩余的人按新座位重同步，再把空座补上。
        if (dead.getVehicle() instanceof CamelHusk camel) {
            dead.stopRiding();

            for (Entity passenger : camel.getPassengers()) {
                if (passenger instanceof Mob mob && MobBackpack.isCarrier(mob)) {
                    MobBackpack.sync(mob, day, mob.getRandom());
                }
            }
            MobMounts.tryFillCamel(level, camel, day);
            return;
        }

        // 马匹骑手阵亡：让附近最近的同类立刻补位。
        MobMounts.onRiderDeath(level, dead, day);
    }

    /**
     * 天数变化后重刷所有已加载的亡灵与坐骑。
     *
     * <p>只处理已加载的实体；未加载区块里的会在下次进入世界（{@link #onEntityJoinLevel}）时补齐。
     */
    public static void resyncLoaded(MinecraftServer server) {
        long day = DayCounterData.get(server).getDay();
        List<Mob> unmounted = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) {
                    continue;
                }
                MobMounts.ensureMountReady(mob);
                if (MobBackpack.isCarrier(mob)) {
                    MobBackpack.sync(mob, day, mob.getRandom());
                    // 记下来，遍历结束后再补坐骑：遍历期间不能往世界里加实体。
                    if (!mob.isPassenger() && !mob.isVehicle()) {
                        unmounted.add(mob);
                    }
                } else if (MobMounts.isMount(mob)) {
                    MobMounts.syncMount(mob, day);
                }
            }
        }

        for (Mob mob : unmounted) {
            if (mob.level() instanceof ServerLevel level) {
                MobMounts.tryAutoMount(level, mob, day);
            }
        }
    }
}
