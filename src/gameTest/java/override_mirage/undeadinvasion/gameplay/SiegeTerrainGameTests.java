package override_mirage.undeadinvasion.gameplay;

import java.util.List;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
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

/** End-to-end siege navigation: real terrain, doors, target acquisition and lethal melee. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class SiegeTerrainGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("siege_climbs_block_steps_breaks_door_and_kills_villager", helper -> elevation(helper, true, false)),
            Map.entry("siege_descends_block_steps_breaks_door_and_kills_golem", helper -> elevation(helper, false, true)),
            Map.entry("siege_crosses_half_slab_threshold_and_kills_villager", SiegeTerrainGameTests::slabThreshold),
            Map.entry("siege_climbs_interior_staircase_and_kills_upstairs_villager", SiegeTerrainGameTests::upstairs),
            Map.entry("visible_villager_through_window_still_requires_breaking_door", SiegeTerrainGameTests::visibleWindow),
            Map.entry("siege_follows_long_detour_to_door_and_kills_villager", SiegeTerrainGameTests::longDetour),
            Map.entry("siege_breaks_distant_entrance_and_kills_golem", SiegeTerrainGameTests::distantEntrance),
            Map.entry("siege_breaks_two_consecutive_doors_and_kills_villager", SiegeTerrainGameTests::twoDoors),
            Map.entry("siege_uses_bridge_instead_of_stopping_at_ditch", SiegeTerrainGameTests::bridge),
            Map.entry("siege_cavalry_dismounts_at_narrow_passage_and_kills_villager", SiegeTerrainGameTests::narrowPassage),
            Map.entry("siege_stops_breaking_and_pursues_escaped_villager", SiegeTerrainGameTests::escapedTarget),
            Map.entry("siege_leaves_door_cell_without_breaking_door_behind_it", SiegeTerrainGameTests::leavingDoor),
            Map.entry("siege_does_not_demolish_iron_doors", SiegeTerrainGameTests::ironDoor),
            Map.entry("siege_abandons_door_when_its_target_is_gone", SiegeTerrainGameTests::lostTarget));

    private SiegeTerrainGameTests() {}

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
        var environment = event.registerEnvironment(id("siege_terrain"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_terrain_arena"),
                        1000, 0, true, Rotation.NONE, false, 1, 1, false, 18))));
    }

    private static void prepare(GameTestHelper helper) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        days.setDay(UndeadConfig.settings().siegeDay());
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 32; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
                if (x == 0 || x == 47 || z == 0 || z == 31) {
                    for (int y = 1; y <= 10; y++) {
                        helper.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
    }

    private static void door(GameTestHelper helper, BlockPos pos, boolean iron) {
        var state = (iron ? Blocks.IRON_DOOR : Blocks.OAK_DOOR).defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH);
        helper.setBlock(pos, state);
        helper.setBlock(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static BlockPos house(GameTestHelper helper, int minX, int maxX, int front, int back, int floorY, int height) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = front; z <= back; z++) {
                helper.setBlock(x, floorY - 1, z, Blocks.STONE);
                helper.setBlock(x, floorY + height, z, Blocks.STONE);
                if (x == minX || x == maxX || z == front || z == back) {
                    for (int y = floorY; y < floorY + height; y++) {
                        helper.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        BlockPos door = new BlockPos((minX + maxX) / 2, floorY, front);
        door(helper, door, false);
        return door;
    }

    private static Zombie attacker(GameTestHelper helper, Vec3 position) {
        Zombie mob = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(position));
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        helper.getLevel().addFreshEntity(mob);
        mob.getPersistentData().putInt("undeadinvasion:ranged_index", -1);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0);
        return mob;
    }

    private static Mob victim(GameTestHelper helper, Vec3 position, boolean golem) {
        Mob mob = golem ? helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, position)
                : helper.spawnWithNoFreeWill(EntityType.VILLAGER, position);
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        return mob;
    }

    private static void assertKilled(GameTestHelper helper, Zombie attacker, Mob victim, List<BlockPos> doors) {
        helper.succeedWhen(() -> {
            for (BlockPos door : doors) {
                helper.assertTrue(!helper.getBlockState(door).is(Blocks.OAK_DOOR),
                        "The siege must reach and break the route door " + door + ": zombie=" + attacker.position()
                                + ", target=" + attacker.getTarget() + ", route=" + attacker.getNavigation().getPath()
                                + ", runningGoals=" + attacker.goalSelector.getAvailableGoals().stream()
                                        .filter(goal -> goal.isRunning())
                                        .map(goal -> goal.getGoal().getClass().getSimpleName()).toList());
                helper.assertBlockNotPresent(Blocks.OAK_DOOR, door.above());
            }
            helper.assertTrue(!victim.isAlive(), "The siege must continue after entry and kill the acquired target: zombie="
                    + attacker.position() + ", target=" + attacker.getTarget() + ", health=" + victim.getHealth());
        });
    }

    private static void elevation(GameTestHelper helper, boolean uphill, boolean golem) {
        prepare(helper);
        for (int z = 1; z < 31; z++) {
            int height = z < 8 ? 0 : z < 10 ? 1 : z < 12 ? 2 : 3;
            if (!uphill) {
                height = 3 - height;
            }
            for (int x = 1; x < 47; x++) {
                for (int y = 1; y <= height; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        int floorY = uphill ? 4 : 1;
        BlockPos door = house(helper, 10, 18, 16, 28, floorY, 4);
        Zombie mob = attacker(helper, new Vec3(14.5, uphill ? 1.0 : 4.0, 3.5));
        Mob victim = victim(helper, new Vec3(14.5, floorY, 24.5), golem);
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void slabThreshold(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 10, 18, 16, 28, 2, 4);
        for (int x = 13; x <= 15; x++) {
            helper.setBlock(x, 1, 15, Blocks.STONE_BRICK_SLAB);
        }
        Zombie mob = attacker(helper, new Vec3(14.5, 1.0, 4.5));
        Mob victim = victim(helper, new Vec3(14.5, 2.0, 24.5), false);
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void upstairs(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 16, 12, 28, 1, 9);
        for (int x = 5; x < 16; x++) {
            for (int z = 20; z < 28; z++) {
                helper.setBlock(x, 4, z, Blocks.STONE);
            }
        }
        for (int step = 0; step < 4; step++) {
            for (int x = 9; x <= 11; x++) {
                for (int y = 1; y <= step; y++) {
                    helper.setBlock(x, y, 16 + step, Blocks.STONE);
                }
                helper.setBlock(new BlockPos(x, 1 + step, 16 + step),
                        Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            }
        }
        Zombie mob = attacker(helper, new Vec3(10.5, 1.0, 3.5));
        Mob victim = victim(helper, new Vec3(10.5, 5.0, 25.5), false);
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void visibleWindow(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 12, 16, 28, 1, 4);
        // A one-block-high viewing slit reveals the villager but cannot admit an adult zombie.
        helper.setBlock(6, 2, 16, Blocks.AIR);
        Zombie mob = attacker(helper, new Vec3(6.5, 1.0, 4.5));
        Mob victim = victim(helper, new Vec3(6.5, 1.0, 24.5), false);
        helper.assertTrue(mob.hasLineOfSight(victim), "The viewing slit must reveal the target without providing a walkable entrance");
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void longDetour(GameTestHelper helper) {
        prepare(helper);
        for (int x = 1; x <= 23; x++) {
            for (int y = 1; y <= 6; y++) {
                helper.setBlock(x, y, 12, Blocks.STONE);
            }
        }
        BlockPos door = house(helper, 4, 12, 20, 29, 1, 4);
        Zombie mob = attacker(helper, new Vec3(8.5, 1.0, 3.5));
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 26.5), false);
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void distantEntrance(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 12, 5, 29, 1, 4);
        Zombie mob = attacker(helper, new Vec3(8.5, 1.0, 2.5));
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 27.5), true);
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void twoDoors(GameTestHelper helper) {
        prepare(helper);
        BlockPos firstDoor = house(helper, 4, 12, 9, 29, 1, 4);
        for (int x = 5; x < 12; x++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(x, y, 18, Blocks.STONE);
            }
        }
        BlockPos secondDoor = new BlockPos(8, 1, 18);
        door(helper, secondDoor, false);
        Zombie mob = attacker(helper, new Vec3(8.5, 1.0, 3.5));
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 26.5), false);
        assertKilled(helper, mob, victim, List.of(firstDoor, secondDoor));
    }

    private static void bridge(GameTestHelper helper) {
        prepare(helper);
        for (int x = 1; x < 47; x++) {
            for (int z = 10; z <= 12; z++) {
                if (x < 7 || x > 9) {
                    helper.setBlock(x, 0, z, Blocks.AIR);
                    helper.setBlock(x, -1, z, Blocks.AIR);
                    helper.setBlock(x, -2, z, Blocks.AIR);
                    helper.setBlock(x, -3, z, Blocks.STONE);
                }
            }
        }
        BlockPos door = house(helper, 10, 18, 17, 29, 1, 4);
        Zombie mob = attacker(helper, new Vec3(14.5, 1.0, 4.5));
        Mob victim = victim(helper, new Vec3(14.5, 1.0, 25.5), false);
        helper.onEachTick(() -> helper.assertTrue(mob.getY() >= helper.absolutePos(new BlockPos(0, 0, 0)).getY(),
                "The siege must use the bridge instead of dropping into an inescapable ditch"));
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void ironDoor(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 12, 12, 28, 1, 4);
        door(helper, door, true);
        attacker(helper, new Vec3(8.5, 1.0, 4.5));
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 24.5), false);
        helper.runAfterDelay(320, () -> {
            helper.assertBlockPresent(Blocks.IRON_DOOR, door);
            helper.assertTrue(victim.isAlive(), "Iron doors must not become destructible as a terrain workaround");
            helper.succeed();
        });
    }

    private static void narrowPassage(GameTestHelper helper) {
        prepare(helper);
        for (int x = 1; x < 47; x++) {
            for (int y = 1; y <= 6; y++) {
                if (x != 14 || y > 2) {
                    helper.setBlock(x, y, 10, Blocks.STONE);
                }
            }
        }
        BlockPos door = house(helper, 10, 18, 18, 29, 1, 4);
        Zombie mob = attacker(helper, new Vec3(14.5, 1.0, 3.5));
        var horse = EntityType.ZOMBIE_HORSE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        horse.setPos(mob.position());
        horse.setPersistenceRequired();
        MobMounts.setReserved(horse, true);
        MobMounts.ensureMountReady(horse);
        helper.getLevel().addFreshEntity(horse);
        horse.removeFreeWill();
        helper.assertTrue(mob.startRiding(horse, true, true), "The siege test must start on horseback");
        MobMounts.setReserved(horse, false);
        MobBackpack.sync(mob, DayCounterData.dayOf(helper.getLevel()), mob.getRandom());
        Mob victim = victim(helper, new Vec3(14.5, 1.0, 25.5), false);
        boolean[] dismounted = {false};
        helper.onEachTick(() -> {
            if (!mob.isPassenger() && !dismounted[0]) {
                helper.assertTrue(horse.distanceToSqr(Vec3.atBottomCenterOf(helper.absolutePos(door))) > 16.0,
                        "The narrow passage must force dismounting before reaching the door");
                dismounted[0] = true;
                mob.getPersistentData().putInt("undeadinvasion:ranged_index", -1);
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
            if (dismounted[0]) {
                helper.assertFalse(mob.isPassenger(), "The siege must continue on foot after the blocked passage");
            }
        });
        assertKilled(helper, mob, victim, List.of(door));
    }

    private static void escapedTarget(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 12, 12, 28, 1, 4);
        Zombie mob = attacker(helper, new Vec3(8.5, 1.0, 9.5));
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 24.5), false);
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(mob.getTarget() == victim, "The siege must first acquire the indoor villager");
            helper.assertTrue(mob.distanceToSqr(Vec3.atBottomCenterOf(helper.absolutePos(door))) < 4.0,
                    "The zombie must reach the door before the villager escapes");
            helper.assertBlockPresent(Blocks.OAK_DOOR, door);
            victim.setPos(helper.absoluteVec(new Vec3(8.5, 1.0, 3.5)));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!victim.isAlive(), "The siege must leave the obsolete door and kill the escaped target");
            helper.assertBlockPresent(Blocks.OAK_DOOR, door);
        });
    }

    private static void leavingDoor(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 12, 12, 28, 1, 4);
        // A closed north-facing door occupies the back edge of its cell. The zombie can walk north out of it.
        Zombie mob = attacker(helper, new Vec3(8.5, 1.0, 12.45));
        helper.assertTrue(helper.getLevel().noCollision(mob), "The zombie must begin in the clear side of the door cell");
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 3.5), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(!victim.isAlive(), "The zombie must walk away from the doorway and kill its outdoor target");
            helper.assertBlockPresent(Blocks.OAK_DOOR, door);
        });
    }

    private static void lostTarget(GameTestHelper helper) {
        prepare(helper);
        BlockPos door = house(helper, 4, 12, 12, 28, 1, 4);
        Zombie mob = attacker(helper, new Vec3(8.5, 1.0, 9.5));
        Mob victim = victim(helper, new Vec3(8.5, 1.0, 24.5), false);
        helper.runAfterDelay(50, () -> {
            helper.assertTrue(mob.getTarget() == victim, "The siege must first acquire its victim");
            victim.discard();
        });
        helper.runAfterDelay(330, () -> {
            helper.assertBlockPresent(Blocks.OAK_DOOR, door);
            helper.succeed();
        });
    }
}
