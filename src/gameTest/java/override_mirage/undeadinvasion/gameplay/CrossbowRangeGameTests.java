package override_mirage.undeadinvasion.gameplay;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.ai.CrossbowAttackGoal;
import override_mirage.undeadinvasion.mixin.AbstractArrowAccessor;

/** Real crossbow shots and arrow flight; included only in game test runs. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class CrossbowRangeGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("zombie_crossbow_hits_at_eight_blocks", helper -> flight(helper, 8.0)),
            Map.entry("zombie_crossbow_hits_at_sixteen_blocks", helper -> flight(helper, 16.0)),
            Map.entry("zombie_crossbow_reaches_bow_range_at_thirty_two_blocks", helper -> flight(helper, 32.0)),
            Map.entry("zombie_loads_and_fires_crossbow_at_bow_range", CrossbowRangeGameTests::loadAndFire),
            Map.entry("crossbow_shot_retains_loaded_ammo_and_durability", CrossbowRangeGameTests::loadedAmmo),
            Map.entry("player_crossbow_trajectory_is_unchanged", CrossbowRangeGameTests::playerShot));
    private static final Method VANILLA_SHOOT = vanillaShoot();

    private CrossbowRangeGameTests() {}

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
        var environment = event.registerEnvironment(id("crossbow_range"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("ranged_arena"),
                        160, 0, true, Rotation.NONE, false, 1, 1, false, 16))));
    }

    private static Zombie shooter(GameTestHelper helper) {
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        zombie.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        zombie.setPos(helper.absoluteVec(new Vec3(2.5, 1.0, 5.5)));
        zombie.setNoGravity(true);
        zombie.setPersistenceRequired();
        helper.getLevel().addFreshEntity(zombie);
        zombie.removeFreeWill();
        zombie.targetSelector.removeAllGoals(goal -> true);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        zombie.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        return zombie;
    }

    private static Villager target(GameTestHelper helper, Zombie shooter, double distance) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(2.5 + distance, 1.0, 5.5));
        villager.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        villager.setHealth(1000.0F);
        villager.setNoGravity(true);
        shooter.setTarget(villager);
        return villager;
    }

    private static List<Arrow> arrows(GameTestHelper helper, LivingEntity owner) {
        return helper.getLevel().getEntitiesOfClass(Arrow.class, helper.getBounds(), arrow -> arrow.getOwner() == owner);
    }

    private static Arrow loadedShot(GameTestHelper helper, Zombie zombie) {
        ItemStack crossbow = zombie.getMainHandItem();
        crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        CrossbowAttackGoal goal = new CrossbowAttackGoal(zombie, 1.0, 32.0F);
        helper.assertTrue(goal.canUse(), "A zombie holding a loaded crossbow must be able to attack");
        goal.start();
        goal.tick();
        List<Arrow> arrows = arrows(helper, zombie);
        helper.assertTrue(arrows.size() == 1, "The crossbow must launch its loaded arrow");
        return arrows.getFirst();
    }

    private static void flight(GameTestHelper helper, double distance) {
        Zombie zombie = shooter(helper);
        Villager target = target(helper, zombie, distance);
        Arrow arrow = loadedShot(helper, zombie);
        float launchSpeed = (float) arrow.getDeltaMovement().length();
        // Reuse the actual AI launch speed and vanilla crossbow aiming, with spread disabled.
        // Random misses must not turn a ballistic range regression into a flaky test.
        shootWithoutSpread(zombie, arrow, launchSpeed, target);
        float health = target.getHealth();
        for (int tick = 0; tick < 80 && arrow.isAlive()
                && !((AbstractArrowAccessor) arrow).undeadinvasion$isInGround() && target.getHealth() == health; tick++) {
            arrow.tick();
        }
        helper.assertTrue(target.getHealth() < health,
                "The real crossbow arrow must hit at " + distance + " blocks rather than fall short: speed="
                        + launchSpeed + ", position=" + arrow.position());
        helper.succeed();
    }

    private static void loadAndFire(GameTestHelper helper) {
        Zombie zombie = shooter(helper);
        Villager target = target(helper, zombie, 32.0);
        zombie.goalSelector.addGoal(2, new CrossbowAttackGoal(zombie, 1.0, 32.0F));
        helper.runAfterDelay(10, () -> helper.assertTrue(zombie.isUsingItem(),
                "An unloaded crossbow must begin charging at the same 32 block attack range as the bow"));
        helper.succeedWhen(() -> {
            List<Arrow> arrows = arrows(helper, zombie);
            helper.assertTrue(!arrows.isEmpty(), "The zombie must complete loading and launch its arrow");
            Arrow arrow = arrows.getFirst();
            helper.assertTrue(arrow.getDeltaMovement().horizontalDistance() > 2.4,
                    "The fully loaded crossbow must launch an arrow fast enough for the distant target");
            helper.assertTrue(zombie.distanceToSqr(target) > 31.0 * 31.0,
                    "The zombie must fire from range instead of walking into a shorter firing distance");
            helper.assertFalse(CrossbowItem.isCharged(zombie.getMainHandItem()), "Firing must consume the loaded charge");
        });
    }

    private static void loadedAmmo(GameTestHelper helper) {
        Zombie zombie = shooter(helper);
        target(helper, zombie, 32.0);
        ItemStack crossbow = zombie.getMainHandItem();
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(
                new ItemStack(Items.SPECTRAL_ARROW), new ItemStack(Items.SPECTRAL_ARROW), new ItemStack(Items.SPECTRAL_ARROW))));
        int damage = crossbow.getDamageValue();
        CrossbowAttackGoal goal = new CrossbowAttackGoal(zombie, 1.0, 32.0F);
        goal.tick();
        var shots = helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds(), arrow -> arrow.getOwner() == zombie);
        helper.assertTrue(shots.size() == 3, "A shot must retain every loaded projectile");
        helper.assertTrue(shots.stream().allMatch(arrow -> arrow.getType() == EntityType.SPECTRAL_ARROW),
                "The range fix must retain loaded special arrow types");
        helper.assertTrue(crossbow.getDamageValue() == damage + 3, "Vanilla durability use must remain active");
        helper.assertFalse(CrossbowItem.isCharged(crossbow), "The original charged ammunition must be consumed");
        helper.succeed();
    }

    private static void playerShot(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(2.5, 1.0, 5.5)));
        player.setYRot(0.0F);
        player.setYHeadRot(0.0F);
        player.setXRot(0.0F);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        player.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        ((CrossbowItem) Items.CROSSBOW).performShooting(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                crossbow, 3.15F, 0.0F, null);
        Arrow arrow = arrows(helper, player).getFirst();
        Vec3 velocity = arrow.getDeltaMovement();
        helper.assertTrue(Math.abs(velocity.y) < 0.00001 && Math.abs(velocity.z - 3.15) < 0.00001,
                "Player crossbows must keep vanilla view direction and launch speed");
        helper.succeed();
    }

    private static Method vanillaShoot() {
        try {
            Method method = CrossbowItem.class.getDeclaredMethod("shootProjectile", LivingEntity.class, Projectile.class,
                    int.class, float.class, float.class, float.class, LivingEntity.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Vanilla crossbow shot API is unavailable", error);
        }
    }

    private static void shootWithoutSpread(LivingEntity shooter, Arrow arrow, float speed, LivingEntity target) {
        try {
            VANILLA_SHOOT.invoke(Items.CROSSBOW, shooter, arrow, 0, speed, 0.0F, 0.0F, target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unable to simulate a vanilla crossbow shot", error);
        }
    }
}
