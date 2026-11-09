package override_mirage.undeadinvasion.gameplay;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.world.DayCounterData;

/** Exercises real navigation and goal scheduling in an isolated game test world. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class ZombieSiegeGameTests {
    private static final BlockPos DOOR = new BlockPos(8, 1, 9);
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("cavalry_keeps_mount_while_chasing_outdoor_player", ZombieSiegeGameTests::outdoorPlayer),
            Map.entry("losing_sight_without_a_door_does_not_dismount_cavalry", ZombieSiegeGameTests::hiddenPlayer),
            Map.entry("cavalry_approaches_breaks_door_and_enters_villager_house", ZombieSiegeGameTests::villagerHouse),
            Map.entry("door_is_broken_when_route_is_unreachable", ZombieSiegeGameTests::unreachableRoute),
            Map.entry("empty_house_door_is_not_broken", ZombieSiegeGameTests::emptyHouse),
            Map.entry("hidden_target_without_siege_still_allows_auto_mount", ZombieSiegeGameTests::hiddenTargetMount));

    private ZombieSiegeGameTests() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, path);
    }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry ->
                TESTS.forEach((name, function) -> registry.register(id(name), function)));
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("zombie_siege"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_arena"),
                        600, 0, true, Rotation.NONE, false, 1, 1, false, 18))));
    }

    private static long prepareArena(GameTestHelper helper) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        days.setDay(UndeadConfig.settings().siegeDay());
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        return days.getDay();
    }

    private static Zombie zombie(GameTestHelper helper, Vec3 position) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        zombie.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        zombie.setPersistenceRequired();
        zombie.setInvulnerable(true);
        zombie.setPos(helper.absoluteVec(position));
        helper.getLevel().addFreshEntity(zombie);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SPEAR));
        return zombie;
    }

    private static ZombieHorse horse(GameTestHelper helper, Zombie rider) {
        ZombieHorse horse = EntityType.ZOMBIE_HORSE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        horse.setPos(rider.position());
        horse.setPersistenceRequired();
        MobMounts.setReserved(horse, true);
        MobMounts.ensureMountReady(horse);
        helper.getLevel().addFreshEntity(horse);
        // Keep autonomous wandering out of assertions; rider control and navigation remain enabled.
        horse.removeFreeWill();
        helper.assertTrue(rider.startRiding(horse, true, true), "The test rider must mount its horse");
        MobMounts.setReserved(horse, false);
        MobBackpack.sync(rider, DayCounterData.dayOf(helper.getLevel()), rider.getRandom());
        return horse;
    }

    private static Player player(GameTestHelper helper, Vec3 position) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(position));
        return player;
    }

    private static void door(GameTestHelper helper, BlockPos pos) {
        var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        helper.setBlock(pos, lower);
        helper.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void house(GameTestHelper helper) {
        for (int x = 4; x <= 12; x++) {
            for (int z = 9; z <= 15; z++) {
                helper.setBlock(x, 5, z, Blocks.STONE);
                if (x == 4 || x == 12 || z == 9 || z == 15) {
                    for (int y = 1; y <= 4; y++) {
                        helper.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        helper.setBlock(DOOR, Blocks.AIR);
        helper.setBlock(DOOR.above(), Blocks.AIR);
        door(helper, DOOR);
    }

    private static void wall(GameTestHelper helper) {
        for (int x = 3; x <= 14; x++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(x, y, 9, Blocks.STONE);
            }
        }
    }

    private static void outdoorPlayer(GameTestHelper helper) {
        prepareArena(helper);
        Zombie zombie = zombie(helper, new Vec3(8.5, 1.0, 2.5));
        ZombieHorse horse = horse(helper, zombie);
        Player target = player(helper, new Vec3(8.5, 1.0, 16.5));
        zombie.setTarget(target);
        double startDistance = horse.distanceToSqr(target);
        helper.onEachTick(() -> helper.assertTrue(zombie.getVehicle() == horse,
                "An outdoor player chase must retain the mount: removed=" + zombie.isRemoved()
                        + ", horseRemoved=" + horse.isRemoved() + ", position=" + zombie.position()
                        + ", vehicle=" + zombie.getVehicle() + ", ticks=" + zombie.tickCount));
        helper.startSequence().thenIdle(100).thenExecute(() -> {
            helper.assertTrue(horse.distanceToSqr(target) < startDistance - 20.0,
                    "The mounted zombie must actually pursue the player: start=" + startDistance
                            + ", now=" + horse.distanceToSqr(target) + ", position=" + horse.position());
        }).thenSucceed();
    }

    private static void hiddenPlayer(GameTestHelper helper) {
        prepareArena(helper);
        wall(helper);
        // A nearby door that is unrelated to the route must not turn a wall into a siege trigger.
        BlockPos unrelatedDoor = new BlockPos(1, 1, 4);
        door(helper, unrelatedDoor);
        Zombie zombie = zombie(helper, new Vec3(8.5, 1.0, 2.5));
        ZombieHorse horse = horse(helper, zombie);
        Player target = player(helper, new Vec3(8.5, 1.0, 13.5));
        zombie.setTarget(target);
        helper.assertFalse(zombie.hasLineOfSight(target), "The wall must initially hide the player");
        helper.onEachTick(() -> helper.assertTrue(zombie.getVehicle() == horse,
                "Losing sight behind an ordinary wall must not trigger dismounting: removed="
                        + zombie.isRemoved() + ", horseRemoved=" + horse.isRemoved()
                        + ", position=" + zombie.position() + ", ticks=" + zombie.tickCount));
        helper.startSequence().thenIdle(300).thenExecute(() -> {
            helper.assertBlockPresent(Blocks.OAK_DOOR, unrelatedDoor);
        }).thenSucceed();
    }

    private static void villagerHouse(GameTestHelper helper) {
        long day = prepareArena(helper);
        house(helper);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(8.5, 1.0, 12.5));
        villager.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        villager.setHealth(1000.0F);
        Zombie zombie = zombie(helper, new Vec3(8.5, 1.0, 2.5));
        ZombieHorse horse = horse(helper, zombie);
        Vec3 doorPosition = Vec3.atBottomCenterOf(helper.absolutePos(DOOR));
        boolean[] approachedOnHorse = {false};
        boolean[] dismounted = {false};
        helper.assertFalse(zombie.hasLineOfSight(villager), "The house must hide its villager");
        helper.onEachTick(() -> {
            if (zombie.isPassenger() && horse.getZ() > helper.absoluteVec(new Vec3(8.5, 1.0, 4.0)).z) {
                approachedOnHorse[0] = true;
            }
            if (!zombie.isPassenger() && !dismounted[0]) {
                helper.assertTrue(approachedOnHorse[0], "Cavalry must approach on horseback before dismounting: horse="
                        + horse.position() + ", door=" + doorPosition + ", rider=" + zombie.position()
                        + ", path=" + horse.getNavigation().getPath());
                helper.assertTrue(horse.position().distanceToSqr(doorPosition) < 16.0,
                        "The rider must dismount at the doorway rather than far from the house");
                dismounted[0] = true;
            }
            if (dismounted[0]) {
                MobMounts.tryAutoMount(helper.getLevel(), zombie, day);
                MobMounts.tryFillMount(helper.getLevel(), horse, day);
                helper.assertFalse(zombie.isPassenger(), "Neither automatic mounting direction may interrupt entry");
            }
        });
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(zombie.getTarget() == villager, "The zombie must acquire the hidden villager itself: target="
                    + zombie.getTarget() + ", removed=" + zombie.isRemoved() + ", ticks=" + zombie.tickCount
                    + ", day=" + DayCounterData.dayOf(helper.getLevel()) + ", ai=" + zombie.isEffectiveAi());
            helper.assertBlockPresent(Blocks.OAK_DOOR, DOOR);
        });
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_DOOR, DOOR);
            helper.assertTrue(dismounted[0], "The narrow entrance requires dismounting");
            helper.assertTrue(zombie.getZ() > doorPosition.z + 1.0,
                    "After breaking the door the zombie must enter the house");
            helper.assertTrue(zombie.getTarget() == villager, "Entry must retain the villager target");
            zombie.setTarget(null);
            MobMounts.tryFillMount(helper.getLevel(), horse, day);
            helper.assertTrue(zombie.getVehicle() == horse, "Once the siege target is gone, remounting must work again");
        });
    }

    /**
     * 门后再砌一道墙，寻路到村民只会给出不可达的部分路径（canReach() == false）。
     * 真实村庄里屋内台阶、地毯、家具经常造成这种情况，此时依然必须破门。
     */
    private static void unreachableRoute(GameTestHelper helper) {
        prepareArena(helper);
        house(helper);
        for (int x = 4; x <= 12; x++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(x, y, 11, Blocks.STONE);
            }
        }
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(8.5, 1.0, 13.5));
        villager.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        villager.setHealth(1000.0F);
        Zombie zombie = zombie(helper, new Vec3(8.5, 1.0, 2.5));
        helper.assertFalse(zombie.hasLineOfSight(villager), "The house must hide its villager");
        helper.runAfterDelay(15, () -> helper.assertTrue(zombie.getTarget() == villager,
                "The zombie must acquire the hidden villager itself: target=" + zombie.getTarget()));
        Vec3 doorPosition = Vec3.atBottomCenterOf(helper.absolutePos(DOOR));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_DOOR, DOOR);
            helper.assertTrue(zombie.distanceToSqr(doorPosition) < 16.0,
                    "The zombie must walk to the door instead of stopping where it stands: position=" + zombie.position());
            helper.assertTrue(zombie.getTarget() == villager, "Breaking the door must retain the villager target");
        });
    }

    private static void emptyHouse(GameTestHelper helper) {
        long day = prepareArena(helper);
        house(helper);
        Zombie zombie = zombie(helper, new Vec3(8.5, 1.0, 7.5));
        zombie.setCanBreakDoors(true);
        MobBackpack.sync(zombie, day, zombie.getRandom());
        helper.assertFalse(zombie.canBreakDoors(), "Legacy vanilla door breaking must not bypass siege target checks");
        zombie.targetSelector.removeAllGoals(goal -> true);
        horse(helper, zombie);
        helper.runAfterDelay(300, () -> {
            helper.assertBlockPresent(Blocks.OAK_DOOR, DOOR);
            helper.assertTrue(zombie.isPassenger(), "An empty house must not trigger dismounting");
            helper.succeed();
        });
    }

    private static void hiddenTargetMount(GameTestHelper helper) {
        long day = prepareArena(helper);
        wall(helper);
        Zombie zombie = zombie(helper, new Vec3(8.5, 1.0, 2.5));
        ZombieHorse horse = horse(helper, zombie);
        zombie.stopRiding();
        zombie.setTarget(player(helper, new Vec3(8.5, 1.0, 13.5)));
        helper.assertFalse(zombie.hasLineOfSight(zombie.getTarget()), "The target must be hidden");
        MobMounts.tryAutoMount(helper.getLevel(), zombie, day);
        helper.assertTrue(zombie.getVehicle() == horse, "A hidden target alone must not prohibit mounting");
        helper.succeed();
    }
}
