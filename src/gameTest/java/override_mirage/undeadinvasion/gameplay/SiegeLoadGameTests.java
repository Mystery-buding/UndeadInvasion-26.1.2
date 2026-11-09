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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.event.MobBackpackHandler;
import override_mirage.undeadinvasion.gameplay.ai.SiegeBreakDoorGoal;
import override_mirage.undeadinvasion.world.DayCounterData;

/** Exercises siege setup after loading a complete saved mount/passenger tree. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class SiegeLoadGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("loaded_aquatic_rider_does_not_abort_entity_join", SiegeLoadGameTests::aquaticRider),
            Map.entry("loaded_aquatic_rider_breaks_door_after_dismounting", SiegeLoadGameTests::dismountedRider),
            Map.entry("reloaded_zombie_with_same_uuid_restores_siege_and_kills_villager", SiegeLoadGameTests::sameUuid));

    private SiegeLoadGameTests() {}

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, name);
    }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry ->
                TESTS.forEach((name, function) -> registry.register(id(name), function)));
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("siege_load"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_arena"),
                        600, 0, true, Rotation.NONE, false, 1, 1, false, 18))));
    }

    private static void prepare(GameTestHelper helper) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        days.setDay(UndeadConfig.settings().siegeDay());
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static Entity saveAndReload(GameTestHelper helper, Entity entity) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        helper.assertTrue(entity.save(output), "The complete mount/passenger tree must be saved");
        Entity loaded = EntityType.loadEntityRecursive(output.buildResult(), helper.getLevel(), EntitySpawnReason.LOAD,
                current -> current);
        helper.assertTrue(loaded != null, "The saved entity tree must deserialize");
        return loaded;
    }

    private static Drowned loadedRider(GameTestHelper helper) {
        Drowned rider = EntityType.DROWNED.create(helper.getLevel(), EntitySpawnReason.LOAD);
        rider.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        rider.setPersistenceRequired();
        rider.setInvulnerable(true);
        rider.setPos(helper.absoluteVec(new Vec3(8.5, 1.0, 2.5)));
        var mount = EntityType.ZOMBIE_NAUTILUS.create(helper.getLevel(), EntitySpawnReason.LOAD);
        mount.setPersistenceRequired();
        mount.setPos(rider.position());
        helper.assertTrue(rider.startRiding(mount, true, true), "The fixture must begin with an aquatic rider");
        Entity loadedMount = saveAndReload(helper, mount);
        helper.assertTrue(loadedMount.getFirstPassenger() instanceof Drowned, "Deserialization must restore the drowned rider");
        Drowned loaded = (Drowned) loadedMount.getFirstPassenger();
        helper.assertFalse(loaded.getNavigation().canNavigateGround(), "The loaded rider must use its mount's aquatic navigation");
        // Invoke the disk-load event directly, so a regression is reported without leaving a world entity half-added.
        MobBackpackHandler.onEntityJoinLevel(new EntityJoinLevelEvent(loaded, helper.getLevel(), true));
        return loaded;
    }

    private static SiegeBreakDoorGoal installedGoal(GameTestHelper helper, Zombie mob) {
        var goals = mob.goalSelector.getAvailableGoals().stream()
                .map(goal -> goal.getGoal()).filter(goal -> goal instanceof SiegeBreakDoorGoal).toList();
        helper.assertTrue(goals.size() == 1, "Every loaded entity must have exactly one siege goal: " + goals.size());
        return (SiegeBreakDoorGoal) goals.getFirst();
    }

    private static void aquaticRider(GameTestHelper helper) {
        prepare(helper);
        Drowned rider = loadedRider(helper);
        var originalNavigation = rider.getNavigation();
        var target = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(8.5, 1.0, 12.5));
        rider.setTarget(target);
        SiegeBreakDoorGoal goal = installedGoal(helper, rider);
        helper.assertFalse(goal.canUse(), "An aquatic mount must not be forced into ground door navigation");
        MobBackpack.sync(rider, DayCounterData.dayOf(helper.getLevel()), rider.getRandom());
        helper.assertTrue(rider.isPassenger() && rider.getNavigation() == originalNavigation,
                "Loading and repeated synchronization must retain the mount and its aquatic navigation");
        installedGoal(helper, rider);
        helper.succeed();
    }

    private static void dismountedRider(GameTestHelper helper) {
        prepare(helper);
        Drowned rider = loadedRider(helper);
        rider.stopRiding();
        helper.assertTrue(rider.getNavigation().canNavigateGround(), "The dismounted drowned must regain amphibious navigation");
        // Native drowned may refuse dry targets in daylight; this case verifies restored door breaking.
        // The reloaded ground-zombie case also verifies hidden-target acquisition and lethal pursuit.
        breachHouse(helper, rider, false);
    }

    private static void breachHouse(GameTestHelper helper, Zombie rider, boolean requireKill) {
        rider.setPos(helper.absoluteVec(new Vec3(8.5, 1.0, 2.5)));
        rider.setInvulnerable(true);
        rider.setPersistenceRequired();
        helper.getLevel().addFreshEntity(rider);
        installedGoal(helper, rider);
        rider.getPersistentData().putInt("undeadinvasion:ranged_index", -1);
        rider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        rider.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0);
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
        BlockPos door = new BlockPos(8, 1, 9);
        var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        helper.setBlock(door, state);
        helper.setBlock(door.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        var victim = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(8.5, 1.0, 12.5));
        victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        helper.runAfterDelay(15, () -> helper.assertTrue(rider.getTarget() == victim,
                "The reloaded entity must acquire the hidden villager itself"));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_DOOR, door);
            helper.assertTrue(rider.distanceToSqr(Vec3.atBottomCenterOf(helper.absolutePos(door))) < 36.0,
                    "The loaded entity must actually approach its route door");
            if (requireKill) {
                helper.assertTrue(!victim.isAlive(), "The reloaded zombie must enter and kill the villager");
            }
        });
    }

    private static void sameUuid(GameTestHelper helper) {
        prepare(helper);
        Zombie original = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.LOAD);
        original.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        original.setPos(helper.absoluteVec(new Vec3(8.5, 1.0, 2.5)));
        MobBackpack.sync(original, DayCounterData.dayOf(helper.getLevel()), original.getRandom());
        installedGoal(helper, original);
        Zombie reloaded = (Zombie) saveAndReload(helper, original);
        helper.assertTrue(original != reloaded && original.getUUID().equals(reloaded.getUUID()),
                "A chunk reload creates a new instance with the same saved UUID");
        MobBackpackHandler.onEntityJoinLevel(new EntityJoinLevelEvent(reloaded, helper.getLevel(), true));
        installedGoal(helper, reloaded);
        MobBackpack.sync(reloaded, DayCounterData.dayOf(helper.getLevel()), reloaded.getRandom());
        installedGoal(helper, reloaded);
        breachHouse(helper, reloaded, true);
    }
}
