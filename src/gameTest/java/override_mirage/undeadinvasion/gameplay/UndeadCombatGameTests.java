package override_mirage.undeadinvasion.gameplay;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.ai.ShieldBlockGoal;
import override_mirage.undeadinvasion.gameplay.ai.WallPiercingTargetGoal;
import override_mirage.undeadinvasion.mixin.AbstractArrowAccessor;
import override_mirage.undeadinvasion.world.DayCounterData;

/** Actual projectile collisions and combat AI; never included in the shipped mod. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class UndeadCombatGameTests {
    private static final Identifier EXTERNAL_UNDEAD_ID = Identifier.fromNamespaceAndPath(
            "undeadinvasion_combat_fixture", "external_undead");
    private static final Identifier EXTERNAL_ANIMAL_ID = Identifier.fromNamespaceAndPath(
            "undeadinvasion_combat_fixture", "external_animal");
    private static EntityType<Zombie> externalUndeadType;
    private static EntityType<Cow> externalAnimalType;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("bow_arrows_do_not_bounce_during_villager_hurt_cooldown", helper -> cooldownHit(helper, Items.BOW)),
            Map.entry("crossbow_arrows_do_not_bounce_during_villager_hurt_cooldown", helper -> cooldownHit(helper, Items.CROSSBOW)),
            Map.entry("piercing_arrows_continue_after_a_rejected_villager_hit", UndeadCombatGameTests::piercingHit),
            Map.entry("player_shields_still_reflect_undead_arrows", UndeadCombatGameTests::playerShield),
            Map.entry("cancelled_villager_damage_is_not_bypassed", UndeadCombatGameTests::cancelledDamage),
            Map.entry("all_undead_types_are_allies_and_reject_undead_targets", UndeadCombatGameTests::allies),
            Map.entry("friendly_melee_does_not_damage_or_change_hostile_target", UndeadCombatGameTests::friendlyMelee),
            Map.entry("hurt_by_goal_ignores_an_old_undead_attacker", UndeadCombatGameTests::retaliation),
            Map.entry("bow_arrows_pass_allied_undead_and_hit_villagers", helper -> friendlyArrow(helper, Items.BOW, Items.ARROW)),
            Map.entry("crossbow_spectral_arrows_pass_allied_undead_and_hit_villagers", helper -> friendlyArrow(helper, Items.CROSSBOW, Items.SPECTRAL_ARROW)),
            Map.entry("tridents_pass_allied_undead_and_hit_villagers", UndeadCombatGameTests::friendlyTrident),
            Map.entry("friendly_projectiles_do_not_trigger_undead_shields", UndeadCombatGameTests::friendlyShield),
            Map.entry("brain_combat_ai_rejects_undead_but_accepts_villagers", UndeadCombatGameTests::brainTargets),
            Map.entry("player_arrows_and_environment_still_damage_undead", UndeadCombatGameTests::hostileDamage),
            Map.entry("tagged_modded_undead_are_not_in_the_vanilla_faction", UndeadCombatGameTests::externalFaction),
            Map.entry("bow_arrows_hit_tagged_modded_undead", helper -> externalArrow(helper, Items.BOW, false)),
            Map.entry("crossbow_arrows_hit_tagged_modded_undead", helper -> externalArrow(helper, Items.CROSSBOW, false)),
            Map.entry("modded_undead_arrows_hit_vanilla_undead", helper -> externalArrow(helper, Items.BOW, true)),
            Map.entry("tridents_hit_tagged_modded_undead", UndeadCombatGameTests::externalTrident),
            Map.entry("modded_undead_arrows_trigger_vanilla_undead_shields", UndeadCombatGameTests::externalShield),
            Map.entry("modded_undead_keep_vanilla_villager_hurt_cooldown_hits", UndeadCombatGameTests::externalVillagerHit),
            Map.entry("undead_proactively_target_modded_undead", helper -> proactiveTargets(helper, true)),
            Map.entry("undead_proactively_target_modded_animals", helper -> proactiveTargets(helper, false)),
            Map.entry("external_undead_do_not_gain_the_new_targeting_ai", UndeadCombatGameTests::externalTargeting),
            Map.entry("siege_targets_modded_undead_behind_walls", helper -> wallTargets(helper, true)),
            Map.entry("siege_targets_modded_animals_behind_walls", helper -> wallTargets(helper, false)),
            Map.entry("modded_melee_blocks_the_first_hit_and_refreshes_shield", UndeadCombatGameTests::externalMeleeShield),
            Map.entry("mob_melee_obeys_shield_cooldown", UndeadCombatGameTests::mobMeleeCooldown),
            Map.entry("friendly_and_cancelled_mob_attacks_do_not_raise_shields", UndeadCombatGameTests::ignoredMelee));

    private UndeadCombatGameTests() {}

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, name);
    }

    @SubscribeEvent
    public static void registerTestEntity(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, registry -> {
            externalUndeadType = EntityType.Builder.<Zombie>of(Zombie::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F).eyeHeight(1.74F).noLootTable()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, EXTERNAL_UNDEAD_ID));
            registry.register(EXTERNAL_UNDEAD_ID, externalUndeadType);
            externalAnimalType = EntityType.Builder.<Cow>of(Cow::new, MobCategory.CREATURE)
                    .sized(0.9F, 1.4F).noLootTable()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, EXTERNAL_ANIMAL_ID));
            registry.register(EXTERNAL_ANIMAL_ID, externalAnimalType);
        });
    }

    @SubscribeEvent
    public static void registerTestEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(externalUndeadType, Zombie.createAttributes().build());
        event.put(externalAnimalType, Cow.createAttributes().build());
    }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry ->
                TESTS.forEach((name, function) -> registry.register(id(name), function)));
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("undead_combat"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("ranged_arena"),
                        80, 0, true, Rotation.NONE, false, 1, 1, false, 16))));
    }

    private static <T extends Mob> T mob(GameTestHelper helper, EntityType<T> type, double x) {
        return mob(helper, type, x, false);
    }

    private static <T extends Mob> T mob(GameTestHelper helper, EntityType<T> type, double x, boolean keepTargetGoals) {
        T mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(x, 1.0, 5.5)));
        mob.setNoGravity(true);
        mob.setPersistenceRequired();
        helper.getLevel().addFreshEntity(mob);
        for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            mob.setItemSlot(slot, ItemStack.EMPTY);
        }
        mob.removeFreeWill();
        if (!keepTargetGoals) {
            mob.targetSelector.removeAllGoals(goal -> true);
        }
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        mob.setHealth(1000.0F);
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        return mob;
    }

    private static Villager villager(GameTestHelper helper, double x) {
        return mob(helper, EntityType.VILLAGER, x);
    }

    private static AbstractArrow fire(GameTestHelper helper, LivingEntity owner, LivingEntity target,
                                      Item weaponItem, Item ammoItem) {
        return fire(helper, owner, target, new ItemStack(weaponItem), ammoItem);
    }

    private static AbstractArrow fire(GameTestHelper helper, LivingEntity owner, LivingEntity target,
                                      ItemStack weapon, Item ammoItem) {
        owner.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        AbstractArrow arrow;
        if (weapon.is(Items.CROSSBOW)) {
            weapon.set(DataComponents.CHARGED_PROJECTILES,
                    ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(ammoItem))));
            ((CrossbowItem) weapon.getItem()).performShooting(helper.getLevel(), owner, InteractionHand.MAIN_HAND,
                    weapon, 2.0F, 0.0F, target);
            arrow = helper.getLevel().getEntitiesOfClass(AbstractArrow.class, helper.getBounds(),
                    projectile -> projectile.getOwner() == owner).getLast();
        } else {
            arrow = ProjectileUtil.getMobArrow(owner, new ItemStack(ammoItem), 1.0F, weapon);
            helper.getLevel().addFreshEntity(arrow);
        }
        // Keep the actual weapon/ammo, but isolate collision behavior from random spread and gravity.
        arrow.setPos(owner.position().add(0.0, 1.0, 0.0));
        arrow.setNoGravity(true);
        arrow.setCritArrow(false);
        arrow.setBaseDamage(2.0);
        Vec3 direction = target.position().add(0.0, 1.0, 0.0).subtract(arrow.position());
        arrow.shoot(direction.x, direction.y, direction.z, 2.0F, 0.0F);
        return arrow;
    }

    private static void fly(GameTestHelper helper, AbstractArrow arrow, LivingEntity target) {
        for (int tick = 0; tick < 20 && arrow.isAlive()
                && arrow.getDeltaMovement().x > 0.0
                && arrow.getX() < target.getX() + 1.0
                && !((AbstractArrowAccessor) arrow).undeadinvasion$isInGround(); tick++) {
            arrow.tick();
        }
    }

    private static void cooldownHit(GameTestHelper helper, Item weapon) {
        Zombie shooter = mob(helper, EntityType.ZOMBIE, 2.5);
        Villager target = villager(helper, 10.5);
        shooter.setTarget(target);
        AbstractArrow first = fire(helper, shooter, target, weapon, Items.ARROW);
        fly(helper, first, target);
        helper.assertTrue(target.getHealth() < 1000.0F && first.isRemoved(),
                "The first real arrow must hit and damage the villager");
        float health = target.getHealth();
        int cooldown = target.invulnerableTime;
        helper.assertTrue(cooldown > 10, "The first hit must activate the vanilla hurt cooldown");

        AbstractArrow second = fire(helper, shooter, target, weapon, Items.ARROW);
        second.igniteForSeconds(5.0F);
        int fireTicks = target.getRemainingFireTicks();
        fly(helper, second, target);
        helper.assertTrue(second.isRemoved(), "A rejected non-piercing arrow must be consumed instead of bouncing");
        helper.assertTrue(second.getDeltaMovement().x > 0.0, "Villager hurt cooldown must not reverse an arrow");
        helper.assertTrue(target.getHealth() == health && target.invulnerableTime == cooldown,
                "The bounce fix must preserve hurt cooldown and must not inflict extra damage");
        helper.assertTrue(target.getRemainingFireTicks() == fireTicks,
                "A rejected burning arrow must restore the villager's original fire duration");
        helper.succeed();
    }

    private static void piercingHit(GameTestHelper helper) {
        Zombie shooter = mob(helper, EntityType.ZOMBIE, 2.5);
        Villager protectedTarget = villager(helper, 8.5);
        Villager nextTarget = villager(helper, 14.5);
        protectedTarget.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 50.0F);
        float health = protectedTarget.getHealth();
        int cooldown = protectedTarget.invulnerableTime;
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.PIERCING), 1);
        AbstractArrow arrow = fire(helper, shooter, protectedTarget, crossbow, Items.ARROW);
        helper.assertTrue(arrow.getPierceLevel() == 1, "The real piercing enchantment must apply to the loaded arrow");
        fly(helper, arrow, nextTarget);
        helper.assertTrue(protectedTarget.getHealth() == health && protectedTarget.invulnerableTime == cooldown,
                "Piercing arrows must still respect the first villager's hurt cooldown");
        helper.assertTrue(nextTarget.getHealth() < 1000.0F && arrow.getDeltaMovement().x > 0.0,
                "Piercing must continue forward and damage the next eligible target");
        helper.succeed();
    }

    private static void playerShield(GameTestHelper helper) {
        Zombie shooter = mob(helper, EntityType.ZOMBIE, 2.5);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(10.5, 1.0, 5.5)));
        player.setYRot(90.0F);
        player.setYHeadRot(90.0F);
        ItemStack shield = new ItemStack(Items.SHIELD);
        MobBackpack.makeShieldFast(shield);
        player.setItemSlot(EquipmentSlot.OFFHAND, shield);
        player.startUsingItem(InteractionHand.OFF_HAND);
        helper.getLevel().addFreshEntity(player);
        helper.assertTrue(player.isBlocking(), "The player must be facing the arrow with a raised shield");
        float health = player.getHealth();
        AbstractArrow arrow = fire(helper, shooter, player, Items.BOW, Items.ARROW);
        fly(helper, arrow, player);
        helper.assertTrue(player.getHealth() == health && arrow.isAlive() && arrow.getDeltaMovement().x < 0.0,
                "Actual player shield blocks must retain vanilla arrow reflection");
        helper.succeed();
    }

    private static void cancelledDamage(GameTestHelper helper) {
        Zombie shooter = mob(helper, EntityType.ZOMBIE, 2.5);
        Villager target = villager(helper, 10.5);
        Consumer<LivingIncomingDamageEvent> cancel = event -> {
            if (event.getEntity() == target) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, LivingIncomingDamageEvent.class, cancel);
        try {
            AbstractArrow arrow = fire(helper, shooter, target, Items.CROSSBOW, Items.ARROW);
            fly(helper, arrow, target);
            helper.assertTrue(target.getHealth() == 1000.0F && target.invulnerableTime == 0,
                    "Damage protection from another event handler must remain effective");
            helper.succeed();
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
        }
    }

    private static void allies(GameTestHelper helper) {
        Zombie zombie = mob(helper, EntityType.ZOMBIE, 2.5);
        List<EntityType<? extends Mob>> types = List.of(EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER,
                EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN,
                EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED, EntityType.PARCHED,
                EntityType.WITHER_SKELETON, EntityType.PHANTOM, EntityType.WITHER,
                EntityType.ZOMBIE_HORSE, EntityType.SKELETON_HORSE, EntityType.CAMEL_HUSK, EntityType.ZOMBIE_NAUTILUS);
        for (EntityType<? extends Mob> type : types) {
            Mob other = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
            helper.assertTrue(other.is(EntityTypeTags.UNDEAD), "The test type must belong to the vanilla undead tag: " + type);
            helper.assertTrue(zombie.isAlliedTo(other) && other.isAlliedTo(zombie), "Undead must be mutual allies: " + type);
            helper.assertFalse(zombie.canAttack(other) || other.canAttack(zombie), "Undead must reject allied combat targets: " + type);
            zombie.setTarget(other);
            helper.assertTrue(zombie.getTarget() == null, "Explicit target changes must not permit attacking undead: " + type);
        }
        Villager enemy = villager(helper, 10.5);
        zombie.setTarget(enemy);
        helper.assertTrue(zombie.canAttack(enemy) && zombie.getTarget() == enemy && !zombie.isAlliedTo(enemy),
                "Villagers must remain valid enemies");
        helper.succeed();
    }

    private static void friendlyMelee(GameTestHelper helper) {
        Zombie attacker = mob(helper, EntityType.ZOMBIE, 2.5);
        Zombie victim = mob(helper, EntityType.ZOMBIE, 8.5);
        Villager enemy = villager(helper, 14.5);
        victim.setTarget(enemy);
        attacker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        boolean damaged = attacker.doHurtTarget(helper.getLevel(), victim);
        AbstractArrow arrow = fire(helper, attacker, enemy, Items.BOW, Items.ARROW);
        ThrownTrident trident = new ThrownTrident(helper.getLevel(), attacker, new ItemStack(Items.TRIDENT));
        boolean arrowDamage = victim.hurtServer(helper.getLevel(), helper.getLevel().damageSources().arrow(arrow, attacker), 8.0F);
        boolean tridentDamage = victim.hurtServer(helper.getLevel(), helper.getLevel().damageSources().trident(trident, attacker), 8.0F);
        helper.assertFalse(damaged || arrowDamage || tridentDamage, "Friendly melee, arrow and trident damage must all be rejected");
        helper.assertTrue(victim.getHealth() == 1000.0F && victim.invulnerableTime == 0 && victim.getDeltaMovement().equals(Vec3.ZERO),
                "Friendly fire must not inflict damage, hurt cooldown or knockback");
        helper.assertTrue(victim.getLastHurtByMob() == null && victim.getTarget() == enemy,
                "Friendly fire must not replace an existing hostile target or record an allied attacker");
        helper.succeed();
    }

    private static void retaliation(GameTestHelper helper) {
        Zombie victim = mob(helper, EntityType.ZOMBIE, 8.5);
        Mob oldAttacker = mob(helper, EntityType.SKELETON, 2.5);
        victim.tickCount = 1;
        victim.setLastHurtByMob(oldAttacker);
        HurtByTargetGoal goal = new HurtByTargetGoal(victim).setAlertOthers();
        helper.assertFalse(goal.canUse(), "An old friendly-fire record must not start retaliation or alert other undead");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(victim.position().add(0.0, 0.0, 2.0));
        victim.tickCount = 2;
        boolean hurt = victim.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 6.0F);
        helper.assertTrue(hurt && goal.canUse(), "Player damage must still activate normal retaliation");
        goal.start();
        helper.assertTrue(victim.getTarget() == player, "Retaliation must target the hostile player");
        helper.succeed();
    }

    private static void shield(Mob mob) {
        ItemStack shield = new ItemStack(Items.SHIELD);
        MobBackpack.makeShieldFast(shield);
        mob.setItemSlot(EquipmentSlot.OFFHAND, shield);
        mob.setYRot(90.0F);
        mob.setYHeadRot(90.0F);
        mob.startUsingItem(InteractionHand.OFF_HAND);
    }

    private static void friendlyArrow(GameTestHelper helper, Item weapon, Item ammo) {
        Mob shooter = mob(helper, EntityType.SKELETON, 2.5);
        Zombie firstAlly = mob(helper, EntityType.ZOMBIE, 6.5);
        Mob secondAlly = mob(helper, EntityType.DROWNED, 10.5);
        Villager enemy = villager(helper, 14.5);
        shield(firstAlly);
        shield(secondAlly);
        firstAlly.setTarget(enemy);
        secondAlly.setTarget(enemy);
        AbstractArrow arrow = fire(helper, shooter, enemy, weapon, ammo);
        arrow.igniteForSeconds(5.0F);
        fly(helper, arrow, enemy);
        helper.assertTrue(enemy.getHealth() < 1000.0F && arrow.isRemoved(), "The real arrow must pass multiple allies and hit its enemy");
        for (Mob ally : List.of(firstAlly, secondAlly)) {
            helper.assertTrue(ally.getHealth() == 1000.0F && !ally.isOnFire() && ally.getArrowCount() == 0,
                    "Friendly arrows must not damage, ignite or stick in allied undead");
            helper.assertTrue(ally.getActiveEffects().isEmpty() && ally.getOffhandItem().getDamageValue() == 0,
                    "Friendly spectral arrows and flames must not apply effects or damage allied shields");
            helper.assertTrue(ally.getLastHurtByMob() == null && ally.getTarget() == enemy,
                    "Allies must keep attacking their hostile target after arrows pass them");
        }
        helper.succeed();
    }

    private static void friendlyTrident(GameTestHelper helper) {
        Mob shooter = mob(helper, EntityType.DROWNED, 2.5);
        Mob ally = mob(helper, EntityType.SKELETON, 8.5);
        Villager enemy = villager(helper, 14.5);
        shield(ally);
        ThrownTrident trident = new ThrownTrident(helper.getLevel(), shooter, new ItemStack(Items.TRIDENT));
        trident.setPos(shooter.position().add(0.0, 1.0, 0.0));
        trident.setNoGravity(true);
        trident.setDeltaMovement(new Vec3(2.0, 0.0, 0.0));
        helper.getLevel().addFreshEntity(trident);
        fly(helper, trident, enemy);
        helper.assertTrue(enemy.getHealth() < 1000.0F, "A thrown trident must pass allied undead and hit the villager");
        helper.assertTrue(ally.getHealth() == 1000.0F && ally.getLastHurtByMob() == null && ally.getOffhandItem().getDamageValue() == 0,
                "Friendly tridents must not hit allied shields or start retaliation");
        helper.succeed();
    }

    private static void friendlyShield(GameTestHelper helper) {
        Mob shooter = mob(helper, EntityType.SKELETON, 2.5);
        Zombie defender = mob(helper, EntityType.ZOMBIE, 8.5);
        defender.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        AbstractArrow friendly = fire(helper, shooter, defender, Items.BOW, Items.ARROW);
        friendly.setPos(defender.position().add(0.0, 1.0, 2.0));
        ShieldBlockGoal goal = new ShieldBlockGoal(defender);
        goal.tick();
        helper.assertFalse(goal.canUse() || defender.isBlocking(), "Nearby friendly arrows must not interrupt combat to raise a shield");
        Player enemy = helper.makeMockPlayer(GameType.SURVIVAL);
        enemy.setPos(defender.position().add(0.0, 0.0, 3.0));
        AbstractArrow hostile = fire(helper, enemy, defender, Items.BOW, Items.ARROW);
        hostile.setPos(defender.position().add(0.0, 1.0, 2.0));
        goal.tick();
        helper.assertTrue(goal.canUse() && defender.isBlocking(), "Player arrows must still raise the shield normally");
        hostile.discard();
        goal.tick();
        helper.assertFalse(goal.canContinueToUse() || defender.isBlocking(), "Friendly arrows alone must not keep the shield raised");
        helper.succeed();
    }

    private static void brainTargets(GameTestHelper helper) {
        Zoglin zoglin = mob(helper, EntityType.ZOGLIN, 2.5);
        Zombie ally = mob(helper, EntityType.ZOMBIE, 8.5);
        Villager enemy = villager(helper, 14.5);
        zoglin.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        var alliedBehavior = StartAttacking.<Zoglin>create((level, body) -> Optional.of(ally));
        boolean allied = alliedBehavior.tryStart(helper.getLevel(), zoglin, helper.getLevel().getGameTime());
        helper.assertFalse(allied || zoglin.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET),
                "Brain-based targeting must reject allied undead as well as ordinary target goals");
        var hostileBehavior = StartAttacking.<Zoglin>create((level, body) -> Optional.of(enemy));
        boolean hostile = hostileBehavior.tryStart(helper.getLevel(), zoglin, helper.getLevel().getGameTime());
        helper.assertTrue(hostile && zoglin.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == enemy,
                "The same brain behavior must still accept a hostile villager");
        helper.succeed();
    }

    private static void hostileDamage(GameTestHelper helper) {
        Zombie target = mob(helper, EntityType.ZOMBIE, 10.5);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(2.5, 1.0, 5.5)));
        AbstractArrow arrow = fire(helper, player, target, Items.BOW, Items.ARROW);
        fly(helper, arrow, target);
        helper.assertTrue(target.getHealth() < 1000.0F && target.getLastHurtByMob() == player,
                "Player arrows must still collide, damage undead and record the hostile attacker");
        target.invulnerableTime = 0;
        float health = target.getHealth();
        boolean hurt = target.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 2.0F);
        helper.assertTrue(hurt && target.getHealth() < health, "Environmental damage without an undead attacker must remain effective");
        helper.succeed();
    }

    private static Zombie externalUndead(GameTestHelper helper, double x) {
        Zombie external = mob(helper, externalUndeadType, x);
        helper.assertTrue(external.is(EntityTypeTags.UNDEAD),
                "The external entity must use the shared undead tag to reproduce mod compatibility");
        return external;
    }

    private static void externalFaction(GameTestHelper helper) {
        Zombie vanilla = mob(helper, EntityType.ZOMBIE, 2.5);
        Zombie external = externalUndead(helper, 8.5);
        Zombie secondExternal = externalUndead(helper, 14.5);
        helper.assertFalse(UndeadCombat.areAllies(vanilla, external) || UndeadCombat.areAllies(external, vanilla)
                        || UndeadCombat.areAllies(external, secondExternal),
                "A shared undead tag must not assign other mods' entities to our faction");
        helper.assertFalse(vanilla.isAlliedTo(external) || external.isAlliedTo(vanilla)
                        || external.isAlliedTo(secondExternal),
                "Other mods must retain their normal alliance rules, including between their own mobs");
        vanilla.setTarget(external);
        external.setTarget(vanilla);
        helper.assertTrue(vanilla.canAttack(external) && external.canAttack(vanilla)
                        && vanilla.getTarget() == external && external.getTarget() == vanilla,
                "Other mods' tagged undead must remain eligible combat targets in both directions");
        boolean externalHurt = external.hurtServer(helper.getLevel(),
                helper.getLevel().damageSources().mobAttack(vanilla), 6.0F);
        boolean vanillaHurt = vanilla.hurtServer(helper.getLevel(),
                helper.getLevel().damageSources().mobAttack(external), 6.0F);
        boolean secondExternalHurt = secondExternal.hurtServer(helper.getLevel(),
                helper.getLevel().damageSources().mobAttack(external), 6.0F);
        helper.assertTrue(externalHurt && vanillaHurt && secondExternalHurt
                        && external.getHealth() < 1000.0F && vanilla.getHealth() < 1000.0F
                        && secondExternal.getHealth() < 1000.0F,
                "The mod must not cancel ordinary damage involving external undead");
        helper.succeed();
    }

    private static void externalArrow(GameTestHelper helper, Item weapon, boolean externalShooter) {
        Zombie shooter = externalShooter ? externalUndead(helper, 2.5) : mob(helper, EntityType.ZOMBIE, 2.5);
        Zombie target = externalShooter ? mob(helper, EntityType.ZOMBIE, 10.5) : externalUndead(helper, 10.5);
        Villager behind = villager(helper, 14.5);
        target.tickCount = 1;
        AbstractArrow arrow = fire(helper, shooter, target, weapon, Items.ARROW);
        fly(helper, arrow, behind);
        helper.assertTrue(target.getHealth() < 1000.0F && arrow.isRemoved() && behind.getHealth() == 1000.0F,
                "A non-piercing arrow must hit external undead instead of passing through to the villager behind");
        helper.assertTrue(target.getLastHurtByMob() == shooter && new HurtByTargetGoal(target).canUse(),
                "Damage involving other mods must still activate normal retaliation");
        helper.succeed();
    }

    private static void externalTrident(GameTestHelper helper) {
        Zombie shooter = mob(helper, EntityType.DROWNED, 2.5);
        Zombie target = externalUndead(helper, 10.5);
        ThrownTrident trident = new ThrownTrident(helper.getLevel(), shooter, new ItemStack(Items.TRIDENT));
        trident.setPos(shooter.position().add(0.0, 1.0, 0.0));
        trident.setNoGravity(true);
        trident.setDeltaMovement(new Vec3(2.0, 0.0, 0.0));
        helper.getLevel().addFreshEntity(trident);
        fly(helper, trident, target);
        helper.assertTrue(target.getHealth() < 1000.0F && target.getLastHurtByMob() == shooter,
                "The trident must collide with a tagged external undead mob");
        helper.succeed();
    }

    private static void externalShield(GameTestHelper helper) {
        Zombie external = externalUndead(helper, 2.5);
        Zombie defender = mob(helper, EntityType.ZOMBIE, 8.5);
        defender.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        AbstractArrow arrow = fire(helper, external, defender, Items.BOW, Items.ARROW);
        arrow.setPos(defender.position().add(0.0, 1.0, 2.0));
        ShieldBlockGoal goal = new ShieldBlockGoal(defender);
        goal.tick();
        helper.assertTrue(goal.canUse() && defender.isBlocking(),
                "Arrows owned by other mods' undead must still trigger shield defense");
        arrow.discard();
        goal.tick();
        helper.assertFalse(goal.canContinueToUse() || defender.isBlocking(),
                "The shield must lower again when the external projectile is gone");
        helper.succeed();
    }

    private static void externalVillagerHit(GameTestHelper helper) {
        Zombie external = externalUndead(helper, 2.5);
        Villager target = villager(helper, 10.5);
        target.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 50.0F);
        float health = target.getHealth();
        int cooldown = target.invulnerableTime;
        AbstractArrow arrow = fire(helper, external, target, Items.BOW, Items.ARROW);
        fly(helper, arrow, target);
        helper.assertTrue(target.getHealth() == health && target.invulnerableTime == cooldown
                        && arrow.isAlive() && arrow.getDeltaMovement().x < 0.0,
                "The villager bounce adjustment must be scoped to our faction's arrows");
        helper.succeed();
    }

    private static void scanTargets(Mob mob) {
        mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(8.0);
        mob.getRandom().setSeed(0L);
        // Exercise the installed selectors, including their normal random scan interval.
        for (int tick = 0; tick < 100; tick++) {
            mob.targetSelector.tick();
        }
    }

    private static void proactiveTargets(GameTestHelper helper, boolean undeadTarget) {
        Mob target = undeadTarget ? mob(helper, externalUndeadType, 8.5) : mob(helper, externalAnimalType, 8.5);
        Mob vanillaAnimal = mob(helper, EntityType.COW, 4.5);
        Mob ally = mob(helper, EntityType.ZOMBIE, 6.5);
        for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIE, EntityType.HUSK,
                EntityType.ZOMBIE_VILLAGER, EntityType.DROWNED, EntityType.SKELETON,
                EntityType.STRAY, EntityType.BOGGED, EntityType.PARCHED)) {
            Mob attacker = mob(helper, type, 2.5, true);
            // Isolate ordinary targeting from the separate, day-gated siege behavior.
            attacker.targetSelector.removeAllGoals(goal -> goal instanceof WallPiercingTargetGoal);
            scanTargets(attacker);
            helper.assertTrue(attacker.getTarget() == target,
                    type + " must acquire the modded mob without retaliation and ignore nearer vanilla mobs");
            Vec3 originalPosition = target.position();
            target.setPos(attacker.position().add(0.0, 0.0, 1.5));
            target.invulnerableTime = 0;
            float health = target.getHealth();
            attacker.doHurtTarget(helper.getLevel(), target);
            helper.assertTrue(target.getHealth() < health, type + " must actually damage its acquired modded target");
            target.setPos(originalPosition);
            attacker.discard();
        }
        helper.assertTrue(vanillaAnimal.getHealth() == 1000.0F && ally.getHealth() == 1000.0F,
                "The added targeting must leave vanilla animals and allied undead alone");
        helper.succeed();
    }

    private static void externalTargeting(GameTestHelper helper) {
        Zombie external = mob(helper, externalUndeadType, 2.5, true);
        mob(helper, externalAnimalType, 8.5);
        external.targetSelector.removeAllGoals(goal -> goal instanceof WallPiercingTargetGoal);
        scanTargets(external);
        helper.assertTrue(external.getTarget() == null,
                "A modded Zombie subclass must not receive the vanilla faction's new aggression goal");
        helper.succeed();
    }

    private static void wallTargets(GameTestHelper helper, boolean undeadTarget) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        long previousDay = days.getDay();
        try {
            days.setDay(UndeadConfig.settings().siegeDay());
            Zombie attacker = mob(helper, EntityType.ZOMBIE, 2.5, true);
            Mob target = undeadTarget ? mob(helper, externalUndeadType, 8.5) : mob(helper, externalAnimalType, 8.5);
            for (int y = 1; y <= 4; y++) {
                for (int z = 4; z <= 7; z++) {
                    helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
                }
            }
            helper.assertFalse(attacker.hasLineOfSight(target), "The modded test target must be hidden behind a wall");
            scanTargets(attacker);
            helper.assertTrue(attacker.getTarget() == target, "Siege targeting must also acquire hidden modded mobs");
            helper.succeed();
        } finally {
            days.setDay(previousDay);
        }
    }

    private static void externalMeleeShield(GameTestHelper helper) {
        Zombie defender = mob(helper, EntityType.ZOMBIE, 2.5);
        Zombie attacker = externalUndead(helper, 8.5);
        // Keep random sunlight damage out of the timed shield assertions.
        defender.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        attacker.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        attacker.setPos(defender.position().add(0.0, 0.0, 1.5));
        defender.setYRot(0.0F);
        defender.setYBodyRot(0.0F);
        defender.setYHeadRot(0.0F);
        defender.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        attacker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        ShieldBlockGoal goal = new ShieldBlockGoal(defender);
        float health = defender.getHealth();
        attacker.doHurtTarget(helper.getLevel(), defender);
        helper.assertTrue(defender.isBlocking() && defender.getHealth() == health,
                "A modded mob's first melee hit must raise the shield before damage is resolved");
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(defender.getHealth() == health,
                    "Damage between attacks: health=" + defender.getHealth() + ", burning=" + defender.isOnFire());
            attacker.setPos(defender.position().add(0.0, 0.0, 1.5));
            attacker.doHurtTarget(helper.getLevel(), defender);
            helper.assertTrue(defender.getHealth() == health,
                    "Repeated melee attacks must remain blockable: health=" + defender.getHealth()
                            + ", head=" + defender.getYHeadRot() + ", yaw=" + defender.getYRot()
                            + ", blocking=" + defender.isBlocking());
        });
        helper.runAfterDelay(10, () -> {
            goal.tick();
            helper.assertTrue(defender.isBlocking(), "A later attack must refresh the half-second shield hold");
        });
        helper.runAfterDelay(16, () -> {
            goal.tick();
            helper.assertFalse(goal.canUse() || defender.isBlocking(), "The shield must lower after attacks stop");
            helper.succeed();
        });
    }

    private static void mobMeleeCooldown(GameTestHelper helper) {
        Zombie defender = mob(helper, EntityType.ZOMBIE, 2.5);
        Mob attacker = mob(helper, EntityType.IRON_GOLEM, 8.5);
        defender.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        attacker.setPos(defender.position().add(0.0, 0.0, 1.5));
        defender.setYRot(0.0F);
        defender.setYBodyRot(0.0F);
        defender.setYHeadRot(0.0F);
        defender.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        ShieldBlockGoal goal = new ShieldBlockGoal(defender);
        ShieldBreak.breakShield(defender, 10);
        float health = defender.getHealth();
        attacker.doHurtTarget(helper.getLevel(), defender);
        helper.assertFalse(defender.isBlocking() || goal.canUse(), "Mob attacks must not raise a disabled shield");
        helper.assertTrue(defender.getHealth() < health, "Damage must still apply while the shield is disabled");
        helper.runAfterDelay(10, () -> {
            goal.tick();
            helper.assertFalse(defender.isBlocking(), "Attacks during cooldown must not queue a later shield reaction");
            attacker.setPos(defender.position().add(0.0, 0.0, 1.5));
            defender.invulnerableTime = 0;
            float remainingHealth = defender.getHealth();
            attacker.doHurtTarget(helper.getLevel(), defender);
            helper.assertTrue(defender.isBlocking() && defender.getHealth() == remainingHealth,
                    "A vanilla mob's next melee hit must trigger blocking after cooldown expires");
            helper.succeed();
        });
    }

    private static void ignoredMelee(GameTestHelper helper) {
        Zombie defender = mob(helper, EntityType.ZOMBIE, 2.5);
        Zombie ally = mob(helper, EntityType.ZOMBIE, 4.5);
        Zombie attacker = externalUndead(helper, 8.5);
        defender.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        ShieldBlockGoal goal = new ShieldBlockGoal(defender);
        ally.doHurtTarget(helper.getLevel(), defender);
        helper.assertFalse(defender.isBlocking() || goal.canUse(), "Friendly melee must not trigger shield use");
        Consumer<LivingIncomingDamageEvent> cancel = event -> {
            if (event.getEntity() == defender) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, LivingIncomingDamageEvent.class, cancel);
        try {
            attacker.doHurtTarget(helper.getLevel(), defender);
            helper.assertFalse(defender.isBlocking() || goal.canUse(), "Cancelled mob attacks must not trigger shield use");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
        }
        helper.succeed();
    }
}
