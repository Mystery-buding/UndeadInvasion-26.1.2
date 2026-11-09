package override_mirage.undeadinvasion.gameplay;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.ai.BowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.CrossbowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.TridentAttackGoal;

/** Actual weapon releases and body control must agree on the target's heading. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class RangedFacingGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("bow_faces_target_that_moves_just_before_release", RangedFacingGameTests::bowRetarget),
            Map.entry("native_skeleton_faces_target_before_firing", RangedFacingGameTests::skeletonBow),
            Map.entry("loaded_crossbow_faces_target_and_handles_retargeting", RangedFacingGameTests::crossbow),
            Map.entry("custom_trident_faces_target_before_first_throw", helper -> trident(helper, false)),
            Map.entry("native_drowned_faces_target_before_throw", helper -> trident(helper, true)),
            Map.entry("ranged_strafing_keeps_body_facing_aim", RangedFacingGameTests::strafing),
            Map.entry("melee_combat_keeps_normal_body_control", helper -> normalBodyControl(helper, EntityType.ZOMBIE, Items.IRON_AXE)),
            Map.entry("non_undead_keep_normal_body_control", helper -> normalBodyControl(helper, EntityType.VILLAGER, Items.BOW)));

    private RangedFacingGameTests() {}

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
        var environment = event.registerEnvironment(id("ranged_facing"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_arena"),
                        100, 0, true, Rotation.NONE, false, 1, 1, false, 16))));
    }

    private static <T extends Mob> T shooter(GameTestHelper helper, EntityType<T> type, Item weapon) {
        T mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)));
        mob.setNoGravity(true);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        helper.getLevel().addFreshEntity(mob);
        mob.removeFreeWill();
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        mob.setAggressive(true);
        mob.setYRot(90.0F);
        mob.setYHeadRot(90.0F);
        mob.setYBodyRot(90.0F);
        return mob;
    }

    private static Villager target(GameTestHelper helper, Mob shooter) {
        Villager target = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(15.5, 1.0, 5.5));
        target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.setHealth(1000.0F);
        shooter.setTarget(target);
        return target;
    }

    private static List<Projectile> shots(GameTestHelper helper, LivingEntity shooter) {
        return helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds(),
                projectile -> projectile.getOwner() == shooter);
    }

    private static void assertFacing(GameTestHelper helper, Mob shooter, LivingEntity target) {
        float yaw = (float) (Mth.atan2(target.getZ() - shooter.getZ(), target.getX() - shooter.getX())
                * 180.0 / Math.PI) - 90.0F;
        helper.assertTrue(Math.abs(Mth.wrapDegrees(shooter.getYHeadRot() - yaw)) < 0.01F,
                "The head must face the firing target: expected=" + yaw + ", actual=" + shooter.getYHeadRot());
        helper.assertTrue(Math.abs(Mth.wrapDegrees(shooter.yBodyRot - yaw)) < 0.01F,
                "The body must face the firing target: expected=" + yaw + ", actual=" + shooter.yBodyRot);
    }

    private static void assertShot(GameTestHelper helper, Mob shooter, LivingEntity target) {
        List<Projectile> shots = shots(helper, shooter);
        helper.assertTrue(!shots.isEmpty(), "The real attack must release a projectile");
        assertFacing(helper, shooter, target);
        Vec3 towardTarget = target.position().subtract(shooter.position()).multiply(1.0, 0.0, 1.0).normalize();
        Vec3 velocity = shots.getLast().getDeltaMovement().multiply(1.0, 0.0, 1.0).normalize();
        helper.assertTrue(towardTarget.dot(velocity) > 0.9,
                "The projectile must travel forward toward the faced target, allowing vanilla random spread");
    }

    private static void bowRetarget(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        Mob mob = shooter(helper, EntityType.ZOMBIE, Items.BOW);
        Villager target = target(helper, mob);
        mob.goalSelector.addGoal(2, new BowAttackGoal(mob, 1.0, 32.0F));
        boolean[] moved = {false};
        helper.onEachTick(() -> {
            if (!moved[0] && mob.isUsingItem() && mob.getTicksUsingItem() >= 19) {
                target.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 15.5)));
                moved[0] = true;
            }
            if (!shots(helper, mob).isEmpty()) {
                helper.assertTrue(moved[0], "The target must change direction immediately before the bow releases");
                assertShot(helper, mob, target);
                helper.succeed();
            }
        });
    }

    private static void skeletonBow(GameTestHelper helper) {
        AbstractSkeleton mob = shooter(helper, EntityType.SKELETON, Items.BOW);
        Villager target = target(helper, mob);
        mob.performRangedAttack(target, 1.0F);
        assertShot(helper, mob, target);
        helper.succeed();
    }

    private static void crossbow(GameTestHelper helper) {
        Mob mob = shooter(helper, EntityType.ZOMBIE, Items.CROSSBOW);
        Villager target = target(helper, mob);
        ItemStack crossbow = mob.getMainHandItem();
        crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        new CrossbowAttackGoal(mob, 1.0, 32.0F).tick();
        assertShot(helper, mob, target);
        shots(helper, mob).forEach(Projectile::discard);
        target.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 15.5)));
        crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        ((CrossbowItem) Items.CROSSBOW).performShooting(helper.getLevel(), mob, InteractionHand.MAIN_HAND,
                crossbow, 2.2F, 0.0F, target);
        assertShot(helper, mob, target);
        helper.succeed();
    }

    private static void trident(GameTestHelper helper, boolean nativeAttack) {
        Mob mob = nativeAttack ? shooter(helper, EntityType.DROWNED, Items.TRIDENT)
                : shooter(helper, EntityType.ZOMBIE, Items.TRIDENT);
        Villager target = target(helper, mob);
        if (nativeAttack) {
            ((Drowned) mob).performRangedAttack(target, 1.0F);
        } else {
            TridentAttackGoal goal = new TridentAttackGoal(mob, 32.0F);
            goal.start();
            goal.tick();
        }
        assertShot(helper, mob, target);
        helper.succeed();
    }

    private static void prepareBodyTurn(Mob mob) {
        mob.setNoAi(true);
        mob.setYRot(0.0F);
        mob.setYBodyRot(0.0F);
        mob.setYHeadRot(90.0F);
        mob.setDeltaMovement(new Vec3(0.12, 0.0, 0.0));
    }

    private static void strafing(GameTestHelper helper) {
        Mob mob = shooter(helper, EntityType.ZOMBIE, Items.BOW);
        prepareBodyTurn(mob);
        mob.tick();
        helper.assertTrue(Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - 90.0F)) < 0.01F
                        && Math.abs(Mth.wrapDegrees(mob.yBodyRot - 90.0F)) < 0.01F,
                "Movement body control must not pull the aiming head and body away from the target");
        helper.succeed();
    }

    private static void normalBodyControl(GameTestHelper helper, EntityType<? extends Mob> type, Item weapon) {
        Mob mob = shooter(helper, type, weapon);
        prepareBodyTurn(mob);
        mob.tick();
        helper.assertTrue(Math.abs(Mth.wrapDegrees(mob.yBodyRot)) < 0.01F
                        && Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - mob.yBodyRot)) > 10.0F,
                "The ranged-facing correction must not replace ordinary melee or non-undead body control");
        helper.succeed();
    }
}
