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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.ai.BowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.CrossbowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.TridentAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.WeaponSwitchGoal;

/** Real immunity queries, projectile collisions and melee navigation; never shipped with the mod. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class ProjectileImmunityGameTests {
    private static final Identifier TARGET_ID = Identifier.fromNamespaceAndPath(
            "undeadinvasion_combat_fixture", "projectile_proof_animal");
    private static EntityType<ProjectileProofCow> targetType;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("immune_target_overrides_zombie_ranged_weapon_distance", helper -> weaponSwitch(helper, EntityType.ZOMBIE)),
            Map.entry("immune_target_overrides_skeleton_ranged_weapon_distance", helper -> weaponSwitch(helper, EntityType.SKELETON)),
            Map.entry("immune_target_stops_custom_and_vanilla_ranged_attacks", ProjectileImmunityGameTests::stopRanged),
            Map.entry("rejected_bow_hit_teaches_projectile_immunity", helper -> learnFromArrow(helper, Items.BOW)),
            Map.entry("rejected_crossbow_hit_teaches_projectile_immunity", helper -> learnFromArrow(helper, Items.CROSSBOW)),
            Map.entry("rejected_trident_hit_teaches_projectile_immunity", ProjectileImmunityGameTests::learnFromTrident),
            Map.entry("accepted_projectile_without_damage_teaches_immunity", ProjectileImmunityGameTests::acceptedWithoutDamage),
            Map.entry("hurt_cooldown_is_not_projectile_immunity", ProjectileImmunityGameTests::hurtCooldown),
            Map.entry("shield_blocking_is_not_projectile_immunity", ProjectileImmunityGameTests::shieldBlocking),
            Map.entry("absorption_damage_is_not_projectile_immunity", ProjectileImmunityGameTests::absorptionDamage),
            Map.entry("immunity_memory_is_per_entity_and_success_clears_it", ProjectileImmunityGameTests::targetMemory),
            Map.entry("temporary_immunity_memory_allows_ranged_retry", ProjectileImmunityGameTests::memoryExpiry),
            Map.entry("drowned_chases_and_melees_projectile_immune_target", helper -> meleePursuit(helper, EntityType.DROWNED, 30)),
            Map.entry("baseline_skeleton_chases_and_melees_projectile_immune_target", helper -> meleePursuit(helper, EntityType.SKELETON, 0)),
            Map.entry("general_invulnerability_is_not_projectile_only_immunity", ProjectileImmunityGameTests::generalInvulnerability),
            Map.entry("player_projectiles_do_not_teach_the_undead_faction", ProjectileImmunityGameTests::playerProjectile));

    private ProjectileImmunityGameTests() {}

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, name);
    }

    @SubscribeEvent
    public static void registerTarget(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, registry -> {
            targetType = EntityType.Builder.<ProjectileProofCow>of(ProjectileProofCow::new, MobCategory.CREATURE)
                    .sized(0.9F, 1.4F).noLootTable()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, TARGET_ID));
            registry.register(TARGET_ID, targetType);
        });
    }

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(targetType, Cow.createAttributes().build());
    }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry ->
                TESTS.forEach((name, function) -> registry.register(id(name), function)));
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("projectile_immunity"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("ranged_arena"),
                        260, 0, true, Rotation.NONE, false, 1, 1, false, 16))));
    }

    private static <T extends Mob> T undead(GameTestHelper helper, EntityType<T> type, long day, boolean keepAi) {
        T mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(2.5, 1.0, 5.5)));
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        helper.getLevel().addFreshEntity(mob);
        MobBackpack.sync(mob, day, mob.getRandom());
        if (day == 50) {
            mob.getPersistentData().putInt("undeadinvasion:melee_index", 0);
            mob.getPersistentData().putInt("undeadinvasion:ranged_index", 0);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
        if (!keepAi) {
            mob.removeFreeWill();
            mob.setNoGravity(true);
        }
        mob.targetSelector.removeAllGoals(goal -> true);
        return mob;
    }

    private static ProjectileProofCow target(GameTestHelper helper, boolean reported, boolean immune) {
        ProjectileProofCow target = targetType.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        target.reportImmunity = reported;
        target.projectileImmune = immune;
        target.setPos(helper.absoluteVec(new Vec3(10.5, 1.0, 5.5)));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPersistenceRequired();
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        target.setHealth(1000.0F);
        helper.getLevel().addFreshEntity(target);
        return target;
    }

    private static AbstractArrow arrow(GameTestHelper helper, LivingEntity owner, LivingEntity target, Item weapon) {
        ItemStack stack = new ItemStack(weapon);
        owner.setItemSlot(EquipmentSlot.MAINHAND, stack);
        AbstractArrow arrow;
        if (weapon == Items.CROSSBOW) {
            stack.set(DataComponents.CHARGED_PROJECTILES,
                    ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
            ((CrossbowItem) stack.getItem()).performShooting(helper.getLevel(), owner, InteractionHand.MAIN_HAND,
                    stack, 2.0F, 0.0F, target);
            arrow = helper.getLevel().getEntitiesOfClass(AbstractArrow.class, helper.getBounds(),
                    projectile -> projectile.getOwner() == owner).getLast();
        } else {
            arrow = ProjectileUtil.getMobArrow(owner, new ItemStack(Items.ARROW), 1.0F, stack);
            helper.getLevel().addFreshEntity(arrow);
        }
        arrow.setPos(owner.position().add(0.0, 1.0, 0.0));
        arrow.setNoGravity(true);
        arrow.setCritArrow(false);
        arrow.setBaseDamage(2.0);
        Vec3 direction = target.position().add(0.0, 1.0, 0.0).subtract(arrow.position());
        arrow.shoot(direction.x, direction.y, direction.z, 2.0F, 0.0F);
        return arrow;
    }

    private static void collide(AbstractArrow projectile, LivingEntity target) {
        for (int tick = 0; tick < 16 && projectile.isAlive()
                && projectile.getDeltaMovement().x > 0.0 && projectile.getX() < target.getX() + 1.0; tick++) {
            projectile.tick();
        }
        projectile.discard();
    }

    private static void weaponSwitch(GameTestHelper helper, EntityType<? extends Mob> type) {
        Mob mob = undead(helper, type, 50, false);
        ProjectileProofCow immune = target(helper, true, true);
        mob.setTarget(immune);
        WeaponSwitchGoal goal = new WeaponSwitchGoal(mob);
        helper.assertTrue(goal.canUse() && ProjectileImmunity.shouldUseMelee(mob),
                "A reported projectile immunity must be detected before the first shot");
        goal.tick();
        helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD),
                "An immune target eight blocks away must force the stored melee weapon");
        immune.setPos(mob.position().add(20.0, 0.0, 0.0));
        goal.tick();
        helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD), "Distance must not switch the immune target back to ranged");
        ProjectileProofCow normal = target(helper, true, false);
        mob.setTarget(normal);
        goal.tick();
        helper.assertTrue(mob.getMainHandItem().is(Items.BOW), "A new vulnerable target must restore normal ranged switching");
        helper.succeed();
    }

    private static void stopRanged(GameTestHelper helper) {
        Zombie zombie = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, true, true);
        zombie.setTarget(immune);
        BowAttackGoal bow = new BowAttackGoal(zombie, 1.0, 32.0F);
        helper.assertFalse(bow.canUse(), "The bow goal must reject projectile-immune targets");
        zombie.startUsingItem(InteractionHand.MAIN_HAND);
        bow.tick();
        helper.assertFalse(zombie.isUsingItem(), "An active bow draw must stop when immunity is detected");
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        zombie.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        CrossbowAttackGoal crossbowGoal = new CrossbowAttackGoal(zombie, 1.0, 32.0F);
        helper.assertFalse(crossbowGoal.canUse(), "A loaded crossbow must not bypass the immunity check");
        crossbowGoal.tick();
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        TridentAttackGoal trident = new TridentAttackGoal(zombie);
        helper.assertFalse(trident.canUse(), "A trident must remain a melee weapon against immune targets");
        trident.tick();
        Skeleton skeleton = undead(helper, EntityType.SKELETON, 50, false);
        skeleton.setTarget(immune);
        skeleton.performRangedAttack(immune, 1.0F);
        Drowned drowned = undead(helper, EntityType.DROWNED, 30, false);
        drowned.setTarget(immune);
        drowned.performRangedAttack(immune, 1.0F);
        for (Mob owner : List.of(zombie, skeleton, drowned)) {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds(),
                    projectile -> projectile.getOwner() == owner).isEmpty(),
                    "Neither custom nor vanilla ranged AI may spawn a projectile at a known immune target");
        }
        helper.succeed();
    }

    private static void learnFromArrow(GameTestHelper helper, Item weapon) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, false, true);
        mob.setTarget(immune);
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "Unreported immunity must initially be unknown");
        collide(arrow(helper, mob, immune, weapon), immune);
        helper.assertTrue(immune.getHealth() == 1000.0F && ProjectileImmunity.shouldUseMelee(mob),
                "A real rejected projectile collision must teach immunity without extra damage probes");
        new WeaponSwitchGoal(mob).tick();
        helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD), "Learning immunity must force melee at ranged distance");
        helper.succeed();
    }

    private static void learnFromTrident(GameTestHelper helper) {
        Drowned mob = undead(helper, EntityType.DROWNED, 30, false);
        ProjectileProofCow immune = target(helper, false, true);
        mob.setTarget(immune);
        ThrownTrident trident = new ThrownTrident(helper.getLevel(), mob, new ItemStack(Items.TRIDENT));
        trident.setPos(mob.position().add(0.0, 1.0, 0.0));
        trident.setNoGravity(true);
        trident.setDeltaMovement(2.0, 0.0, 0.0);
        helper.getLevel().addFreshEntity(trident);
        collide(trident, immune);
        helper.assertTrue(ProjectileImmunity.shouldUseMelee(mob), "A rejected thrown trident must also teach immunity");
        helper.assertFalse(new TridentAttackGoal(mob).canUse(), "A learned immune target must stop further throws");
        helper.succeed();
    }

    private static void acceptedWithoutDamage(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, false, true);
        immune.acceptWithoutDamage = true;
        mob.setTarget(immune);
        collide(arrow(helper, mob, immune, Items.BOW), immune);
        helper.assertTrue(ProjectileImmunity.shouldUseMelee(mob),
                "Returning true without losing health or absorption must not keep the undead shooting forever");
        helper.succeed();
    }

    private static void hurtCooldown(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow normal = target(helper, true, false);
        mob.setTarget(normal);
        normal.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 6.0F);
        float health = normal.getHealth();
        collide(arrow(helper, mob, normal, Items.BOW), normal);
        helper.assertTrue(normal.getHealth() == health && normal.invulnerableTime > 0,
                "The real arrow must be rejected by the existing hurt cooldown");
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "Ordinary hurt cooldown must not teach projectile immunity");
        helper.succeed();
    }

    private static void shieldBlocking(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow normal = target(helper, true, false);
        mob.setTarget(normal);
        ItemStack shield = new ItemStack(Items.SHIELD);
        MobBackpack.makeShieldFast(shield);
        normal.setItemSlot(EquipmentSlot.OFFHAND, shield);
        normal.setYRot(90.0F);
        normal.setYHeadRot(90.0F);
        normal.startUsingItem(InteractionHand.OFF_HAND);
        collide(arrow(helper, mob, normal, Items.BOW), normal);
        helper.assertTrue(normal.isBlocking() && normal.getHealth() == 1000.0F, "The real arrow must hit a raised shield");
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "A shield block must not be mistaken for projectile immunity");
        helper.succeed();
    }

    private static void absorptionDamage(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow normal = target(helper, true, false);
        mob.setTarget(normal);
        normal.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20.0);
        normal.setAbsorptionAmount(20.0F);
        collide(arrow(helper, mob, normal, Items.BOW), normal);
        helper.assertTrue(normal.getHealth() == 1000.0F && normal.getAbsorptionAmount() < 20.0F,
                "The real arrow must deal absorption damage without changing health");
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "Absorption damage must count as an effective projectile hit");
        helper.succeed();
    }

    private static void targetMemory(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, false, true);
        mob.setTarget(immune);
        collide(arrow(helper, mob, immune, Items.BOW), immune);
        helper.assertTrue(ProjectileImmunity.shouldUseMelee(mob), "The hit entity must be remembered");
        ProjectileProofCow second = target(helper, false, true);
        mob.setTarget(second);
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "Another entity of the same type must not inherit immunity memory");
        second.discard();
        mob.setTarget(immune);
        immune.projectileImmune = false;
        collide(arrow(helper, mob, immune, Items.BOW), immune);
        helper.assertTrue(immune.getHealth() < 1000.0F && !ProjectileImmunity.shouldUseMelee(mob),
                "An effective in-flight projectile must clear the old immunity memory immediately");
        helper.succeed();
    }

    private static void memoryExpiry(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, false, true);
        mob.setTarget(immune);
        collide(arrow(helper, mob, immune, Items.BOW), immune);
        immune.projectileImmune = false;
        new WeaponSwitchGoal(mob).tick();
        helper.assertTrue(mob.getMainHandItem().is(Items.IRON_SWORD), "Learned immunity must temporarily hold melee mode");
        helper.runAfterDelay(200, () -> {
            new WeaponSwitchGoal(mob).tick();
            helper.assertTrue(!ProjectileImmunity.shouldUseMelee(mob) && mob.getMainHandItem().is(Items.BOW),
                    "Expired immunity memory must allow ranged attacks again after a temporary phase");
            helper.succeed();
        });
    }

    private static void meleePursuit(GameTestHelper helper, EntityType<? extends Mob> type, long day) {
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        Mob mob = undead(helper, type, day, true);
        ProjectileProofCow immune = target(helper, true, true);
        mob.setTarget(immune);
        helper.succeedWhen(() -> {
            DamageSource damage = immune.getLastDamageSource();
            helper.assertTrue(immune.getHealth() < 1000.0F && damage != null
                            && damage.getDirectEntity() == mob && !damage.is(DamageTypeTags.IS_PROJECTILE),
                    "The real AI must approach from eight blocks away and inflict direct melee damage");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds(),
                    projectile -> projectile.getOwner() == mob).isEmpty(), "Known immunity must prevent every ranged shot during pursuit");
        });
    }

    private static void generalInvulnerability(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, true, true);
        immune.setInvulnerable(true);
        mob.setTarget(immune);
        collide(arrow(helper, mob, immune, Items.BOW), immune);
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "Immunity to all damage must not be classified as projectile-only immunity");
        helper.succeed();
    }

    private static void playerProjectile(GameTestHelper helper) {
        Zombie mob = undead(helper, EntityType.ZOMBIE, 50, false);
        ProjectileProofCow immune = target(helper, false, true);
        mob.setTarget(immune);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(mob.position());
        collide(arrow(helper, player, immune, Items.BOW), immune);
        helper.assertFalse(ProjectileImmunity.shouldUseMelee(mob), "Player-owned projectiles must not update the undead's immunity memory");
        helper.succeed();
    }

    public static final class ProjectileProofCow extends Cow {
        private boolean projectileImmune = true;
        private boolean reportImmunity = true;
        private boolean acceptWithoutDamage;

        public ProjectileProofCow(EntityType<? extends Cow> type, Level level) {
            super(type, level);
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return super.isInvulnerableTo(level, source)
                    || this.projectileImmune && this.reportImmunity && source.is(DamageTypeTags.IS_PROJECTILE);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            if (this.projectileImmune && source.is(DamageTypeTags.IS_PROJECTILE)) {
                return this.acceptWithoutDamage;
            }
            return super.hurtServer(level, source, amount);
        }
    }
}
