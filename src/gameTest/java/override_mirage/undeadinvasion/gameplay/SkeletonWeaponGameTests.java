package override_mirage.undeadinvasion.gameplay;

import java.util.List;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
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
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.ai.LeapAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.ProjectileImmuneMeleeGoal;
import override_mirage.undeadinvasion.world.DayCounterData;

/** Exercise equipment changes inside real Mob AI, including both selector tick paths. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class SkeletonWeaponGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("skeleton_family_repeated_distance_switching_preserves_goals", SkeletonWeaponGameTests::distanceSwitching),
            Map.entry("skeleton_equipment_change_preserves_active_goal_iteration", SkeletonWeaponGameTests::equipmentDuringAi),
            Map.entry("skeleton_automatic_mount_safely_changes_weapon_goals", SkeletonWeaponGameTests::automaticMount),
            Map.entry("skeleton_immune_target_switches_safely_during_ai", SkeletonWeaponGameTests::immuneTarget),
            Map.entry("skeleton_native_melee_still_hits_after_weapon_switch", SkeletonWeaponGameTests::nativeMelee),
            Map.entry("skeleton_native_bow_still_fires_after_switching_back", SkeletonWeaponGameTests::nativeBow));

    private SkeletonWeaponGameTests() {}

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
        var environment = event.registerEnvironment(id("skeleton_weapons"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_arena"),
                        180, 0, true, Rotation.NONE, false, 1, 1, false, 18))));
    }

    private static void prepare(GameTestHelper helper) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        days.setDay(UndeadConfig.settings().ironDay());
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static AbstractSkeleton skeleton(GameTestHelper helper,
                                              EntityType<? extends AbstractSkeleton> type, boolean spawn) {
        AbstractSkeleton mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)));
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        mob.setNoGravity(true);
        MobBackpack.sync(mob, UndeadConfig.settings().ironDay(), mob.getRandom());
        if (spawn) {
            helper.getLevel().addFreshEntity(mob);
        }
        mob.getPersistentData().putInt("undeadinvasion:melee_index", 0);
        mob.getPersistentData().putInt("undeadinvasion:ranged_index", 0);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
        mob.targetSelector.removeAllGoals(goal -> true);
        // Isolate the native melee goal rather than allow a leap to supply the test's damage.
        mob.goalSelector.removeAllGoals(goal -> goal instanceof LeapAttackGoal);
        return mob;
    }

    private static Villager target(GameTestHelper helper, Mob mob, double distance, boolean spawn) {
        Villager target = EntityType.VILLAGER.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        target.setPos(mob.position().add(distance, 0.0, 0.0));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPersistenceRequired();
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        target.setHealth(1000.0F);
        if (spawn) {
            helper.getLevel().addFreshEntity(target);
        }
        mob.setTarget(target);
        return target;
    }

    private static void aiStep(Mob mob) {
        mob.tickCount++;
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setOnGround(true);
        mob.aiStep();
    }

    private static void assertNativeGoal(GameTestHelper helper, AbstractSkeleton mob, boolean bow, boolean running) {
        var nativeGoals = mob.goalSelector.getAvailableGoals().stream().filter(goal ->
                goal.getGoal() instanceof RangedBowAttackGoal<?>
                        || goal.getGoal() instanceof MeleeAttackGoal
                        && !(goal.getGoal() instanceof ProjectileImmuneMeleeGoal)).toList();
        helper.assertTrue(nativeGoals.size() == 1, "Exactly one native weapon goal must remain: " + nativeGoals.size());
        WrappedGoal current = nativeGoals.getFirst();
        helper.assertTrue((current.getGoal() instanceof RangedBowAttackGoal<?>) == bow,
                "The native attack goal must agree with the equipped weapon");
        if (running) {
            helper.assertTrue(current.isRunning(), "The native attack goal must resume after a weapon switch");
        }
    }

    private static void assertSameGoals(GameTestHelper helper, List<WrappedGoal> before, Mob mob) {
        List<WrappedGoal> after = List.copyOf(mob.goalSelector.getAvailableGoals());
        helper.assertTrue(before.size() == after.size(), "Equipment must not resize an active goal iteration");
        for (int index = 0; index < before.size(); index++) {
            helper.assertTrue(before.get(index) == after.get(index),
                    "Equipment must not remove or reinsert goals during an active iteration, index=" + index);
        }
    }

    private static void distanceSwitching(GameTestHelper helper) {
        prepare(helper);
        for (EntityType<? extends AbstractSkeleton> type : List.of(
                EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED, EntityType.PARCHED)) {
            AbstractSkeleton mob = skeleton(helper, type, false);
            Villager target = target(helper, mob, 4.0, false);
            for (int cycle = 0; cycle < 80; cycle++) {
                boolean bow = cycle % 2 != 0;
                target.setPos(mob.position().add(bow ? 10.0 : 4.0, 0.0, 0.0));
                for (int step = 0; step < 4; step++) {
                    aiStep(mob);
                }
                helper.assertTrue(mob.getMainHandItem().is(bow ? Items.BOW : Items.IRON_SWORD),
                        "Distance switching must equip the matching weapon, cycle=" + cycle + ", type=" + type);
                assertNativeGoal(helper, mob, bow, false);
                List<WrappedGoal> stableGoals = List.copyOf(mob.goalSelector.getAvailableGoals());
                aiStep(mob);
                assertSameGoals(helper, stableGoals, mob);
            }
        }
        helper.succeed();
    }

    private static void equipmentDuringAi(GameTestHelper helper) {
        prepare(helper);
        AbstractSkeleton mob = skeleton(helper, EntityType.SKELETON, false);
        boolean[] executed = {false};
        mob.goalSelector.addGoal(0, new Goal() {
            @Override
            public boolean canUse() {
                return !executed[0];
            }

            @Override
            public void tick() {
                List<WrappedGoal> before = List.copyOf(mob.goalSelector.getAvailableGoals());
                mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                assertSameGoals(helper, before, mob);
                executed[0] = true;
            }
        });
        aiStep(mob);
        helper.assertTrue(executed[0], "The equipment change must run inside a real selector tick");
        assertNativeGoal(helper, mob, false, false);
        helper.succeed();
    }

    private static void automaticMount(GameTestHelper helper) {
        prepare(helper);
        AbstractSkeleton mob = skeleton(helper, EntityType.SKELETON, false);
        var mount = EntityType.SKELETON_HORSE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mount.setPos(mob.position().add(2.0, 0.0, 0.0));
        mount.setNoAi(true);
        mount.getPersistentData().putBoolean("undeadinvasion:mount_reserved", true);
        helper.getLevel().addFreshEntity(mount);
        mount.getPersistentData().remove("undeadinvasion:mount_reserved");
        try {
            aiStep(mob);
            helper.assertTrue(mob.getVehicle() == mount, "The mount-seeking goal must actually mount the skeleton horse");
            helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SPEAR) && MobBackpack.rangedItem(mob) == null,
                    "Automatic mounting must still equip the mounted lancer kit");
            assertNativeGoal(helper, mob, false, false);
        } finally {
            mob.stopRiding();
            mount.discard();
        }
        helper.succeed();
    }

    private static void immuneTarget(GameTestHelper helper) {
        prepare(helper);
        AbstractSkeleton mob = skeleton(helper, EntityType.SKELETON, false);
        var immune = EntityType.ENDERMAN.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        immune.setPos(mob.position().add(10.0, 0.0, 0.0));
        mob.setTarget(immune);
        aiStep(mob);
        helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD),
                "Projectile immunity must switch to melee during actual AI even at ranged distance");
        assertNativeGoal(helper, mob, false, false);
        target(helper, mob, 10.0, false);
        aiStep(mob);
        helper.assertTrue(mob.getMainHandItem().is(Items.BOW), "A vulnerable target must restore ranged switching");
        assertNativeGoal(helper, mob, true, false);
        helper.succeed();
    }

    private static void nativeMelee(GameTestHelper helper) {
        prepare(helper);
        AbstractSkeleton mob = skeleton(helper, EntityType.SKELETON, true);
        Villager target = target(helper, mob, 1.0, true);
        helper.assertTrue(mob.isWithinMeleeAttackRange(target), "The fixture must begin inside native melee reach");
        helper.succeedWhen(() -> {
            helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD), "The nearby target must select the stored sword");
            helper.assertTrue(target.getHealth() < 1000.0F, "Native melee AI must actually damage the target after switching");
            assertNativeGoal(helper, mob, false, false);
        });
    }

    private static void nativeBow(GameTestHelper helper) {
        prepare(helper);
        AbstractSkeleton mob = skeleton(helper, EntityType.SKELETON, true);
        Villager target = target(helper, mob, 4.0, true);
        aiStep(mob);
        helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD), "The test must first switch from bow to melee");
        target.setPos(mob.position().add(10.0, 0.0, 0.0));
        helper.succeedWhen(() -> {
            helper.assertTrue(mob.getMainHandItem().is(Items.BOW), "The retreating target must restore the bow");
            helper.assertTrue(!helper.getLevel().getEntitiesOfClass(AbstractArrow.class, helper.getBounds(),
                            arrow -> arrow.getOwner() == mob).isEmpty(),
                    "Native bow AI must actually release an arrow after switching back");
            assertNativeGoal(helper, mob, true, true);
        });
    }
}
