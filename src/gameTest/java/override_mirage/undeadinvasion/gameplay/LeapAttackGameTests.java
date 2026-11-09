package override_mirage.undeadinvasion.gameplay;

import java.util.Map;
import java.util.function.Consumer;
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
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.ai.LeapAttackGoal;

/** Real jump physics, consecutive strikes and the separate player cooldown. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class LeapAttackGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("sword_leaps_chain_after_landing_against_mobs", helper -> consecutiveLeaps(helper, Items.IRON_SWORD)),
            Map.entry("axe_leaps_chain_after_landing_against_mobs", helper -> consecutiveLeaps(helper, Items.IRON_AXE)),
            Map.entry("trident_leaps_chain_after_landing_against_mobs", helper -> consecutiveLeaps(helper, Items.TRIDENT)),
            Map.entry("player_leaps_keep_original_cooldown_and_minimum_distance", LeapAttackGameTests::playerCooldown),
            Map.entry("changing_leap_targets_preserves_player_cooldown", LeapAttackGameTests::changingTargets),
            Map.entry("consecutive_leaps_still_require_ground_range_and_weapon", LeapAttackGameTests::restrictions));

    private LeapAttackGameTests() {}

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
        var environment = event.registerEnvironment(id("leap_attack"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_arena"),
                        80, 0, true, Rotation.NONE, false, 1, 1, false, 16))));
    }

    private static Zombie attacker(GameTestHelper helper, Item weapon) {
        Zombie mob = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)));
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        helper.getLevel().addFreshEntity(mob);
        mob.removeFreeWill();
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        return mob;
    }

    private static Villager target(GameTestHelper helper, Zombie mob, double distance) {
        Villager target = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(5.5 + distance, 1.0, 5.5));
        target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        target.setHealth(1000.0F);
        mob.setTarget(target);
        return target;
    }

    private static Player player(GameTestHelper helper, Zombie mob, double distance) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(mob.position().add(distance, 0.0, 0.0));
        mob.setTarget(player);
        return player;
    }

    private static void consecutiveLeaps(GameTestHelper helper, Item weapon) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        Zombie mob = attacker(helper, weapon);
        Villager target = target(helper, mob, 3.5);
        mob.goalSelector.addGoal(1, new LeapAttackGoal(mob));
        int[] launches = {0};
        int[] firstLanding = {-1};
        int[] secondLaunch = {-1};
        float[] healthAfterFirstLeap = {1000.0F};
        boolean[] rising = {false};
        helper.onEachTick(() -> {
            boolean nowRising = !mob.onGround() && mob.getDeltaMovement().y > 0.0;
            if (nowRising && !rising[0]) {
                launches[0]++;
                if (launches[0] == 2) {
                    secondLaunch[0] = mob.tickCount;
                }
            }
            rising[0] = nowRising;
            if (launches[0] == 1 && mob.onGround() && firstLanding[0] < 0) {
                firstLanding[0] = mob.tickCount;
                healthAfterFirstLeap[0] = target.getHealth();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(firstLanding[0] >= 0 && secondLaunch[0] >= 0,
                    "The carrier must land and begin a second leap using the real goal selector");
            helper.assertTrue(secondLaunch[0] - firstLanding[0] <= 2,
                    "A non-player target must allow another leap on the next AI update after landing");
            helper.assertTrue(healthAfterFirstLeap[0] < 1000.0F && target.getHealth() < healthAfterFirstLeap[0],
                    "Both consecutive leaps must deal damage, including the second leap at close range");
        });
    }

    private static void playerCooldown(GameTestHelper helper) {
        Zombie mob = attacker(helper, Items.IRON_SWORD);
        Player player = player(helper, mob, 3.5);
        mob.setOnGround(true);
        LeapAttackGoal goal = new LeapAttackGoal(mob);
        helper.assertTrue(goal.canUse(), "An in-range player must remain a valid first leap target");
        goal.start();
        goal.stop();
        mob.setOnGround(true);
        for (int attempt = 0; attempt < 80; attempt++) {
            helper.assertFalse(goal.canUse(), "Landing must not remove the original player cooldown");
        }
        boolean ready = false;
        for (int attempt = 0; attempt < 40 && !ready; attempt++) {
            ready = goal.canUse();
        }
        helper.assertTrue(ready, "Player leaping must resume after the original random cooldown");
        player.setPos(mob.position().add(1.0, 0.0, 0.0));
        helper.assertFalse(goal.canUse(), "Players must retain the two-block minimum leap distance");
        helper.succeed();
    }

    private static void changingTargets(GameTestHelper helper) {
        Zombie mob = attacker(helper, Items.IRON_SWORD);
        Player player = player(helper, mob, 2.5);
        mob.setOnGround(true);
        LeapAttackGoal goal = new LeapAttackGoal(mob);
        helper.assertTrue(goal.canUse(), "The first leap must start against the player");
        goal.start();
        goal.stop();
        target(helper, mob, 1.0);
        mob.setOnGround(true);
        helper.assertTrue(goal.canUse(), "Switching to a nearby mob must bypass the remaining player cooldown");
        goal.start();
        goal.stop();
        mob.setTarget(player);
        mob.setOnGround(true);
        helper.assertFalse(goal.canUse(), "Switching back to a player must not inherit the mob's immediate repeat");
        helper.succeed();
    }

    private static void restrictions(GameTestHelper helper) {
        Zombie mob = attacker(helper, Items.IRON_SWORD);
        Villager target = target(helper, mob, 1.0);
        mob.setOnGround(true);
        LeapAttackGoal goal = new LeapAttackGoal(mob);
        helper.assertTrue(goal.canUse(), "A nearby non-player must allow consecutive close-range leaps");
        mob.setOnGround(false);
        helper.assertFalse(goal.canUse(), "The carrier must finish its current jump before starting another");
        mob.setOnGround(true);
        target.setPos(mob.position().add(3.6, 0.0, 0.0));
        helper.assertFalse(goal.canUse(), "Consecutive leaps must not start beyond the new 3.5-block maximum");
        target.setPos(mob.position().add(1.0, 0.0, 0.0));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SPEAR));
        helper.assertFalse(goal.canUse(), "Spears must continue using their own attacks instead of jump strikes");
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        helper.assertFalse(goal.canUse(), "Ranged weapons must not gain the melee jump attack");
        helper.succeed();
    }
}
