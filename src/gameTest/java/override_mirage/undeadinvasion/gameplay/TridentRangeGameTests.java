package override_mirage.undeadinvasion.gameplay;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.ai.RangedArrowAim;
import override_mirage.undeadinvasion.gameplay.ai.RangedTridentAim;
import override_mirage.undeadinvasion.gameplay.ai.TridentAttackGoal;
import override_mirage.undeadinvasion.mixin.AbstractArrowAccessor;

/** Real trident flight and AI scheduling in a wide test arena, never included in the shipped mod. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class TridentRangeGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("custom_trident_hits_at_eight_blocks", helper -> flight(helper, 8.0, false, 0.0, 0.0, false)),
            Map.entry("custom_trident_hits_at_sixteen_blocks", helper -> flight(helper, 16.0, false, 0.0, 0.0, false)),
            Map.entry("custom_trident_hits_at_bow_range", helper -> flight(helper, 32.0, false, 0.0, 0.0, false)),
            Map.entry("custom_trident_hits_at_configured_maximum_range", helper -> flight(helper, 64.0, false, 0.0, 0.0, false)),
            Map.entry("native_drowned_trident_hits_at_eight_blocks", helper -> flight(helper, 8.0, true, 0.0, 0.0, false)),
            Map.entry("native_drowned_trident_hits_at_sixteen_blocks", helper -> flight(helper, 16.0, true, 0.0, 0.0, false)),
            Map.entry("native_drowned_trident_hits_at_bow_range", helper -> flight(helper, 32.0, true, 0.0, 0.0, false)),
            Map.entry("native_drowned_trident_hits_at_configured_maximum_range", helper -> flight(helper, 64.0, true, 0.0, 0.0, false)),
            Map.entry("trident_hits_a_higher_target_at_bow_range", helper -> flight(helper, 32.0, false, 0.0, 8.0, false)),
            Map.entry("trident_hits_a_lower_target_at_bow_range", helper -> flight(helper, 32.0, false, 6.0, 0.0, false)),
            Map.entry("custom_trident_reaches_bow_range_underwater", helper -> flight(helper, 32.0, false, 0.0, 0.0, true)),
            Map.entry("native_trident_reaches_bow_range_underwater", helper -> flight(helper, 32.0, true, 0.0, 0.0, true)),
            Map.entry("drowned_ai_throws_from_bow_range_without_closing_to_ten_blocks", TridentRangeGameTests::drownedAi),
            Map.entry("trident_approaches_targets_outside_attack_radius", TridentRangeGameTests::outsideRange),
            Map.entry("trident_range_fix_preserves_cooldown_and_weapon", TridentRangeGameTests::cooldownAndWeapon),
            Map.entry("trident_still_uses_melee_within_five_blocks", TridentRangeGameTests::meleeDistance));

    private TridentRangeGameTests() {}

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
        var environment = event.registerEnvironment(id("trident_range"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("trident_arena"),
                        180, 0, true, Rotation.NONE, false, 1, 1, false, 16))));
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 80; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static <T extends Mob> T shooter(GameTestHelper helper, EntityType<T> type, boolean keepAi) {
        T mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(2.5, 1.0, 5.5)));
        mob.setPersistenceRequired();
        mob.setNoGravity(true);
        mob.setInvulnerable(true);
        helper.getLevel().addFreshEntity(mob);
        MobBackpack.sync(mob, 30, mob.getRandom());
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (!keepAi) {
            mob.removeFreeWill();
        }
        mob.targetSelector.removeAllGoals(goal -> true);
        return mob;
    }

    private static Villager target(GameTestHelper helper, Mob shooter, double distance) {
        Villager target = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(2.5 + distance, 1.0, 5.5));
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        target.setHealth(1000.0F);
        target.setNoGravity(true);
        shooter.setTarget(target);
        return target;
    }

    private static List<ThrownTrident> tridents(GameTestHelper helper, Mob owner) {
        return helper.getLevel().getEntitiesOfClass(ThrownTrident.class, helper.getBounds(),
                projectile -> projectile.getOwner() == owner);
    }

    private static ThrownTrident fire(GameTestHelper helper, Mob shooter, Villager target, boolean nativeDrowned) {
        if (nativeDrowned) {
            ((Drowned) shooter).performRangedAttack(target, 1.0F);
        } else {
            TridentAttackGoal goal = new TridentAttackGoal(shooter, 64.0F);
            helper.assertTrue(goal.canUse(), "A trident carrier must attack a visible ranged target");
            goal.start();
            goal.tick();
        }
        List<ThrownTrident> shots = tridents(helper, shooter);
        helper.assertTrue(shots.size() == 1, "The actual ranged attack must spawn exactly one trident");
        return shots.getFirst();
    }

    private static void flight(GameTestHelper helper, double distance, boolean nativeDrowned,
                               double shooterHeight, double targetHeight, boolean underwater) {
        floor(helper);
        if (underwater) {
            for (int x = 0; x < distance + 8; x++) {
                for (int y = 1; y <= 7; y++) {
                    for (int z = 2; z <= 8; z++) {
                        helper.setBlock(x, y, z, Blocks.WATER);
                    }
                }
            }
        }
        Mob shooter = nativeDrowned ? shooter(helper, EntityType.DROWNED, false)
                : shooter(helper, EntityType.ZOMBIE, false);
        Villager target = target(helper, shooter, distance);
        shooter.setPos(shooter.position().add(0.0, shooterHeight, 0.0));
        target.setPos(target.position().add(0.0, targetHeight, 0.0));
        ThrownTrident trident = fire(helper, shooter, target, nativeDrowned);
        float speed = RangedArrowAim.velocity(distance);
        double actualSpeed = trident.getDeltaMovement().length();
        double uncertainty = 14.0 - helper.getLevel().getDifficulty().getId() * 4.0;
        double maximumSpreadEffect = speed * Math.sqrt(3.0) * 0.0172275 * uncertainty;
        helper.assertTrue(Math.abs(actualSpeed - speed) <= maximumSpreadEffect + 1.0E-5,
                "The real attack must use the distance-scaled speed within vanilla spread: actual=" + actualSpeed);
        // Vanilla spread changes speed as well as direction. Verify nominal range without either random effect.
        Vec3 direction = RangedTridentAim.direction(shooter, target, trident, speed);
        trident.shoot(direction.x, direction.y, direction.z, speed, 0.0F);
        for (int tick = 0; tick < 120 && trident.isAlive() && target.getHealth() == 1000.0F
                && !((AbstractArrowAccessor) trident).undeadinvasion$isInGround(); tick++) {
            trident.tick();
        }
        helper.assertTrue(target.getHealth() < 1000.0F && target.getLastHurtByMob() == shooter,
                "The real trident must hit at " + distance + " blocks, rather than fall short: speed="
                        + speed + ", position=" + trident.position() + ", target=" + target.position());
        helper.succeed();
    }

    private static void drownedAi(GameTestHelper helper) {
        floor(helper);
        Drowned drowned = shooter(helper, EntityType.DROWNED, true);
        Villager target = target(helper, drowned, 32.0);
        helper.succeedWhen(() -> {
            List<ThrownTrident> shots = tridents(helper, drowned);
            helper.assertTrue(!shots.isEmpty(), "The live drowned AI must throw from 32 blocks away");
            helper.assertTrue(drowned.distanceToSqr(target) > 31.0 * 31.0,
                    "The original ten-block ranged goal must not force the drowned to walk closer");
            helper.assertTrue(shots.getFirst().getDeltaMovement().horizontalDistance() > 2.4,
                    "The live AI's projectile must retain the corrected long-range launch speed");
        });
    }

    private static void outsideRange(GameTestHelper helper) {
        floor(helper);
        Mob mob = shooter(helper, EntityType.ZOMBIE, false);
        Villager target = target(helper, mob, 32.0);
        // Let the mob land before checking ground navigation, then verify actual movement.
        mob.setNoGravity(false);
        double startX = mob.getX();
        mob.goalSelector.addGoal(2, new TridentAttackGoal(mob, 16.0F));
        helper.succeedWhen(() -> {
            helper.assertTrue(mob.getX() > startX + 1.0,
                    "The carrier must actually approach instead of stopping outside attack range");
            helper.assertTrue(mob.distanceToSqr(target) > 17.0 * 17.0,
                    "Approaching must start before the target enters the throwing radius");
            helper.assertTrue(tridents(helper, mob).isEmpty(), "An out-of-range target must not trigger a short throw");
        });
    }

    private static void cooldownAndWeapon(GameTestHelper helper) {
        floor(helper);
        Mob mob = shooter(helper, EntityType.ZOMBIE, false);
        target(helper, mob, 32.0);
        ItemStack weapon = mob.getMainHandItem();
        weapon.set(DataComponents.CUSTOM_NAME, Component.literal("Range test trident"));
        weapon.setDamageValue(7);
        TridentAttackGoal goal = new TridentAttackGoal(mob, 32.0F);
        goal.tick();
        ItemStack projectileWeapon = tridents(helper, mob).getFirst().getWeaponItem();
        helper.assertTrue(mob.getMainHandItem() == weapon && weapon.getDamageValue() == 7,
                "Extended range must neither consume nor replace the held trident");
        helper.assertTrue(projectileWeapon.getDamageValue() == 7
                        && weapon.get(DataComponents.CUSTOM_NAME).equals(projectileWeapon.get(DataComponents.CUSTOM_NAME)),
                "The thrown trident must retain its copied item data");
        for (int tick = 0; tick < 60; tick++) {
            goal.tick();
        }
        helper.assertTrue(tridents(helper, mob).size() == 1, "The range fix must preserve the existing throw cooldown");
        goal.tick();
        helper.assertTrue(tridents(helper, mob).size() == 2, "Throwing must resume after the cooldown");
        helper.succeed();
    }

    private static void meleeDistance(GameTestHelper helper) {
        floor(helper);
        Mob mob = shooter(helper, EntityType.ZOMBIE, false);
        target(helper, mob, 5.0);
        TridentAttackGoal goal = new TridentAttackGoal(mob, 32.0F);
        helper.assertFalse(goal.canUse(), "The longer range must retain the five-block melee threshold");
        goal.tick();
        helper.assertTrue(tridents(helper, mob).isEmpty(), "A close target must be left to melee combat");
        helper.succeed();
    }
}
