package override_mirage.undeadinvasion.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.event.MobBackpackHandler;
import override_mirage.undeadinvasion.gameplay.ai.DispenserAttackGoal;
import override_mirage.undeadinvasion.world.DayCounterData;

/** Real spawn initialization, saved identities, projectile impacts and mixed-faction effects. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class DispenserUndeadGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("dispenser_spawn_is_five_percent_from_day_45", DispenserUndeadGameTests::spawnProbability),
            Map.entry("dispenser_spawn_event_equips_head_after_vanilla_initialization", DispenserUndeadGameTests::spawnEvent),
            Map.entry("dispenser_pending_spawn_survives_chunk_save_without_promoting_old_mobs", DispenserUndeadGameTests::pendingSpawn),
            Map.entry("dispenser_trait_excludes_skeletons_nether_and_external_mobs", DispenserUndeadGameTests::excludedTypes),
            Map.entry("dispenser_spawn_roll_is_not_repeated_after_day_change", DispenserUndeadGameTests::oneRoll),
            Map.entry("dispenser_identity_head_and_ai_survive_reload_and_tier_changes", DispenserUndeadGameTests::reload),
            Map.entry("dispenser_identity_survives_zombie_conversion", DispenserUndeadGameTests::conversion),
            Map.entry("dispenser_potions_have_exactly_one_level_five_effect", DispenserUndeadGameTests::singleEffects),
            Map.entry("dispenser_buffs_only_affect_vanilla_undead", DispenserUndeadGameTests::buffSplash),
            Map.entry("dispenser_harming_heals_vanilla_undead_and_damages_non_undead_enemies", DispenserUndeadGameTests::healingSplash),
            Map.entry("dispenser_debuff_splash_only_affects_enemies", DispenserUndeadGameTests::debuffSplash),
            Map.entry("dispenser_support_potions_pass_enemies_and_hit_allies", DispenserUndeadGameTests::supportCollision),
            Map.entry("dispenser_harming_attack_preserves_its_target_rules_after_reload", helper -> harmingCollision(helper, false)),
            Map.entry("dispenser_harming_support_preserves_its_target_rules_after_reload", helper -> harmingCollision(helper, true)),
            Map.entry("dispenser_fireballs_pass_allies_and_burn_enemies", DispenserUndeadGameTests::fireballCollision),
            Map.entry("dispenser_fireballs_do_not_ignite_terrain", DispenserUndeadGameTests::fireballTerrain),
            Map.entry("dispenser_dragon_cloud_damages_external_undead_and_protects_vanilla_undead", DispenserUndeadGameTests::dragonCloud),
            Map.entry("dispenser_dragon_cloud_preserves_factions_after_reload", DispenserUndeadGameTests::cloudReload),
            Map.entry("ordinary_potions_and_dragon_clouds_keep_vanilla_effects", DispenserUndeadGameTests::ordinaryEffects),
            Map.entry("dispenser_potion_reaches_target_32_blocks_away", helper -> potionRange(helper, 0.0)),
            Map.entry("dispenser_potion_reaches_elevated_target_32_blocks_away", helper -> potionRange(helper, 3.0)),
            Map.entry("dispenser_ai_heals_wounded_ally_without_enemy", DispenserUndeadGameTests::aiHealing),
            Map.entry("dispenser_ai_supplies_all_three_positive_potions", DispenserUndeadGameTests::aiBuffs),
            Map.entry("dispenser_ai_can_damage_enemies_with_instant_harming", DispenserUndeadGameTests::aiHarming),
            Map.entry("dispenser_ai_avoids_negative_potions_against_external_undead", DispenserUndeadGameTests::aiUndeadEnemy),
            Map.entry("dispenser_ai_alternates_support_and_attack_without_retargeting", DispenserUndeadGameTests::aiMixed));

    private DispenserUndeadGameTests() {}

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
        var environment = event.registerEnvironment(id("dispenser_undead"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("trident_arena"),
                        260, 0, true, Rotation.NONE, false, 1, 1, false, 18))));
    }

    private static void prepare(GameTestHelper helper) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        days.setDay(45);
        for (int x = 0; x < 80; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static <T extends Mob> T create(GameTestHelper helper, EntityType<T> type, double x, double z) {
        T mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.getPersistentData().putBoolean("undeadinvasion:no_camel_mount", true);
        mob.setPos(helper.absoluteVec(new Vec3(x, 1.0, z)));
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPersistenceRequired();
        return mob;
    }

    private static <T extends Mob> T mob(GameTestHelper helper, EntityType<T> type, double x, double z) {
        T mob = create(helper, type, x, z);
        helper.getLevel().addFreshEntity(mob);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        mob.setHealth(1000.0F);
        return mob;
    }

    private static Zombie shooter(GameTestHelper helper) {
        Zombie mob = create(helper, EntityType.ZOMBIE, 2.5, 5.5);
        helper.assertTrue(DispenserUndead.rollForSpawn(mob, 45, new PercentileRandom(0)), "The fixture must select a dispenser unit");
        helper.getLevel().addFreshEntity(mob);
        helper.assertTrue(DispenserUndead.isArmed(mob), "The initialized fixture must wear its functional dispenser head");
        mob.setInvulnerable(true);
        return mob;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob> externalType(boolean undead) {
        return (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath(
                "undeadinvasion_combat_fixture", undead ? "external_undead" : "external_animal"));
    }

    private static Player player(GameTestHelper helper, double x, double z) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(x, 1.0, z)));
        player.setNoGravity(true);
        helper.getLevel().addFreshEntity(player);
        return player;
    }

    private static void spawnProbability(GameTestHelper helper) {
        for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIE, EntityType.HUSK,
                EntityType.ZOMBIE_VILLAGER, EntityType.DROWNED)) {
            for (long day : new long[]{44, 45, 70}) {
                int count = 0;
                for (int percentile = 0; percentile < 100; percentile++) {
                    Mob mob = type.create(helper.getLevel(), EntitySpawnReason.NATURAL);
                    if (DispenserUndead.rollForSpawn(mob, day, new PercentileRandom(percentile))) {
                        count++;
                    }
                }
                helper.assertTrue(count == (day < 45 ? 0 : 5),
                        "Every zombie type must allocate exactly five percentile buckets from day 45: " + type + ", day=" + day);
            }
        }
        helper.succeed();
    }

    private static void spawnEvent(GameTestHelper helper) {
        prepare(helper);
        Zombie mob = create(helper, EntityType.ZOMBIE, 5.5, 5.5);
        EventHooks.finalizeMobSpawn(mob, helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.STRUCTURE, null);
        winningRoll(mob);
        helper.getLevel().addFreshEntity(mob);
        helper.assertTrue(DispenserUndead.isArmed(mob), "World entry must complete the spawn-event roll and equip the dispenser after vanilla initialization");
        helper.succeed();
    }

    private static void winningRoll(Mob mob) {
        long seed = 0;
        do {
            mob.getRandom().setSeed(seed++);
        } while (mob.getRandom().nextInt(100) >= 5);
        mob.getRandom().setSeed(seed - 1);
    }

    private static void pendingSpawn(GameTestHelper helper) {
        prepare(helper);
        Zombie pending = create(helper, EntityType.ZOMBIE, 5.5, 5.5);
        DispenserUndead.prepareSpawn(pending);
        Zombie loaded = (Zombie) saveAndReload(helper, pending);
        winningRoll(loaded);
        MobBackpackHandler.onEntityJoinLevel(new EntityJoinLevelEvent(loaded, helper.getLevel(), true));
        helper.assertTrue(DispenserUndead.isArmed(loaded), "A newly generated entity saved before first entry must retain its pending spawn roll");
        Zombie legacy = (Zombie) saveAndReload(helper, create(helper, EntityType.ZOMBIE, 5.5, 5.5));
        winningRoll(legacy);
        MobBackpackHandler.onEntityJoinLevel(new EntityJoinLevelEvent(legacy, helper.getLevel(), true));
        helper.assertFalse(DispenserUndead.isVariant(legacy), "Loading an old mob without a new-spawn marker must never grant a fresh roll");
        helper.succeed();
    }

    private static void excludedTypes(GameTestHelper helper) {
        for (EntityType<? extends Mob> type : List.of(EntityType.SKELETON, EntityType.WITHER_SKELETON,
                EntityType.ZOMBIFIED_PIGLIN, EntityType.VILLAGER, externalType(true))) {
            Mob mob = type.create(helper.getLevel(), EntitySpawnReason.NATURAL);
            MobBackpack.sync(mob, 45, mob.getRandom());
            helper.assertFalse(DispenserUndead.rollForSpawn(mob, 45, new PercentileRandom(0)),
                    "Non-project zombie types must never gain dispenser roles: " + type);
        }
        helper.succeed();
    }

    private static void oneRoll(GameTestHelper helper) {
        Zombie beforeDay45 = create(helper, EntityType.ZOMBIE, 2.5, 5.5);
        helper.assertFalse(DispenserUndead.rollForSpawn(beforeDay45, 44, new PercentileRandom(0)), "Day 44 must not create the role");
        helper.assertFalse(DispenserUndead.rollForSpawn(beforeDay45, 45, new PercentileRandom(0)), "An old entity must not roll again on day 45");
        Zombie missed = create(helper, EntityType.ZOMBIE, 2.5, 5.5);
        helper.assertFalse(DispenserUndead.rollForSpawn(missed, 45, new PercentileRandom(5)), "The sixth percentile bucket must not create the role");
        helper.assertFalse(DispenserUndead.rollForSpawn(missed, 70, new PercentileRandom(0)), "Repeated initialization must preserve a failed roll");
        helper.succeed();
    }

    private static Entity saveAndReload(GameTestHelper helper, Entity entity) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        helper.assertTrue(entity.save(output), "The fixture must serialize");
        Entity loaded = EntityType.loadEntityRecursive(output.buildResult(), helper.getLevel(), EntitySpawnReason.LOAD, current -> current);
        helper.assertTrue(loaded != null, "The fixture must deserialize");
        return loaded;
    }

    private static void reload(GameTestHelper helper) {
        prepare(helper);
        Zombie original = shooter(helper);
        Zombie loaded = (Zombie) saveAndReload(helper, original);
        helper.assertTrue(loaded != original && loaded.getUUID().equals(original.getUUID()), "Loading must recreate the same saved identity");
        MobBackpackHandler.onEntityJoinLevel(new EntityJoinLevelEvent(loaded, helper.getLevel(), true));
        for (long day : new long[]{50, 70, 45, 45}) {
            MobBackpack.sync(loaded, day, loaded.getRandom());
            helper.assertTrue(DispenserUndead.isArmed(loaded), "Equipment changes must retain the functional dispenser helmet");
            helper.assertTrue(loaded.goalSelector.getAvailableGoals().stream()
                            .filter(goal -> goal.getGoal() instanceof DispenserAttackGoal).count() == 1,
                    "Loading and repeated synchronization must install exactly one dispenser goal");
        }
        helper.succeed();
    }

    private static void conversion(GameTestHelper helper) {
        prepare(helper);
        Zombie original = shooter(helper);
        var converted = create(helper, EntityType.DROWNED, 8.5, 5.5);
        MobBackpackHandler.onConversion(new LivingConversionEvent.Post(original, converted));
        helper.assertTrue(DispenserUndead.isArmed(converted), "Zombie conversion must retain the dispenser role and restore its AI");
        helper.succeed();
    }

    private static void singleEffects(GameTestHelper helper) {
        for (DispenserAmmo ammo : DispenserAmmo.values()) {
            if (ammo.effect() == null) {
                continue;
            }
            PotionContents contents = ammo.potionStack().get(DataComponents.POTION_CONTENTS);
            List<MobEffectInstance> effects = new ArrayList<>();
            contents.getAllEffects().forEach(effects::add);
            helper.assertTrue(contents.potion().isEmpty() && effects.size() == 1, "Every projectile must carry exactly one custom effect");
            helper.assertTrue(effects.getFirst().getEffect().equals(ammo.effect()) && effects.getFirst().getAmplifier() == 4,
                    "Every requested potion must contain its own level-five effect: " + ammo);
        }
        helper.succeed();
    }

    private static List<LivingEntity> mixedGroup(GameTestHelper helper) {
        List<LivingEntity> group = new ArrayList<>();
        group.add(mob(helper, EntityType.ZOMBIE, 10.5, 5.5));
        group.add(mob(helper, EntityType.SKELETON, 10.5, 6.5));
        Mob baseline = mob(helper, EntityType.ZOMBIE, 10.5, 4.5);
        MobBackpack.sync(baseline, 0, baseline.getRandom());
        group.add(baseline);
        group.add(mob(helper, EntityType.ZOMBIFIED_PIGLIN, 11.5, 5.5));
        group.add(mob(helper, EntityType.VILLAGER, 9.5, 5.5));
        group.add(mob(helper, EntityType.IRON_GOLEM, 9.5, 6.5));
        group.add(player(helper, 9.5, 4.5));
        group.add(mob(helper, externalType(true), 11.5, 6.5));
        group.add(mob(helper, externalType(false), 11.5, 4.5));
        return group;
    }

    private static void splash(GameTestHelper helper, Zombie shooter, DispenserAmmo ammo, LivingEntity target, Vec3 impact) {
        ThrownSplashPotion potion = (ThrownSplashPotion) DispenserProjectiles.fire(shooter, ammo, target);
        helper.assertTrue(potion != null, "The dispenser must emit the requested valid potion");
        potion.onHitAsPotion(helper.getLevel(), potion.getItem(), new EntityHitResult(target, impact));
        potion.discard();
    }

    private static void buffSplash(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        List<LivingEntity> group = mixedGroup(helper);
        group.add(mob(helper, EntityType.WITHER_SKELETON, 10.5, 7.5));
        for (DispenserAmmo ammo : List.of(DispenserAmmo.RESISTANCE, DispenserAmmo.SPEED, DispenserAmmo.STRENGTH)) {
            splash(helper, shooter, ammo, group.getFirst(), group.getFirst().position());
            for (int index = 0; index < group.size(); index++) {
                MobEffectInstance effect = group.get(index).getEffect(ammo.effect());
                boolean vanillaUndead = index < 4 || index == 9;
                helper.assertTrue(vanillaUndead ? effect != null && effect.getAmplifier() == 4 : effect == null,
                        "Buff splash must reach only vanilla undead, including unreinforced zombies and nether undead: " + ammo + ", index=" + index);
            }
        }
        helper.succeed();
    }

    private static void healingSplash(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        List<LivingEntity> group = mixedGroup(helper);
        group.forEach(entity -> entity.setHealth(entity.getMaxHealth() * 0.5F));
        float[] health = new float[group.size()];
        for (int i = 0; i < group.size(); i++) {
            health[i] = group.get(i).getHealth();
        }
        splash(helper, shooter, DispenserAmmo.INSTANT_DAMAGE, group.getFirst(), group.getFirst().position());
        for (int index = 0; index < group.size(); index++) {
            float currentHealth = group.get(index).getHealth();
            boolean expected = index < 4 ? currentHealth > health[index]
                    : index == 7 ? currentHealth == health[index] : currentHealth < health[index];
            helper.assertTrue(expected, "Harming must heal vanilla undead, hurt non-undead enemies and ignore external undead: " + index);
        }
        helper.succeed();
    }

    private static void debuffSplash(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        List<LivingEntity> group = mixedGroup(helper);
        for (DispenserAmmo ammo : List.of(DispenserAmmo.WEAKNESS, DispenserAmmo.SLOWNESS)) {
            splash(helper, shooter, ammo, group.get(4), group.getFirst().position());
            for (int index = 0; index < group.size(); index++) {
                MobEffectInstance effect = group.get(index).getEffect(ammo.effect());
                boolean undead = index < 4 || index == 7;
                helper.assertTrue(undead ? effect == null : effect != null && effect.getAmplifier() == 4,
                        "Debuffs must affect non-undead enemies while protecting both vanilla and external undead: " + index);
            }
        }
        helper.succeed();
    }

    private static void fly(Projectile projectile) {
        for (int tick = 0; tick < 80 && !projectile.isRemoved(); tick++) {
            projectile.tickCount++;
            projectile.tick();
        }
    }

    private static void cloudStep(AreaEffectCloud cloud) {
        // ServerLevel advances entity age before invoking tick; direct test ticks must do the same.
        cloud.tickCount++;
        cloud.tick();
    }

    private static void supportCollision(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob enemy = mob(helper, EntityType.VILLAGER, 6.5, 5.5);
        Mob ally = mob(helper, EntityType.ZOMBIE, 10.5, 5.5);
        Projectile projectile = DispenserProjectiles.fire(shooter, DispenserAmmo.SPEED, ally);
        fly(projectile);
        helper.assertTrue(ally.hasEffect(MobEffects.SPEED) && !enemy.hasEffect(MobEffects.SPEED),
                "A support potion must pass through the enemy and physically impact its allied recipient");
        helper.succeed();
    }

    private static void harmingCollision(GameTestHelper helper, boolean support) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob ally = mob(helper, EntityType.ZOMBIE, support ? 12.5 : 6.5, 5.5);
        Mob enemy = mob(helper, EntityType.VILLAGER, support ? 6.5 : 12.5, 5.5);
        Mob externalUndead = mob(helper, externalType(true), 9.5, 5.5);
        ally.setHealth(500.0F);
        externalUndead.setHealth(500.0F);
        Projectile original = DispenserProjectiles.fire(shooter, DispenserAmmo.INSTANT_DAMAGE, support ? ally : enemy);
        helper.assertTrue(original != null, "Instant harming must be available for both healing and attacking");
        Projectile projectile = (Projectile) saveAndReload(helper, original);
        original.discard();
        if (support) {
            // A legacy saved healing potion has no purpose tag and must keep working as support.
            projectile.getPersistentData().remove("undeadinvasion:dispenser_support_shot");
        }
        helper.getLevel().addFreshEntity(projectile);
        helper.assertTrue(DispenserProjectiles.isSupportShot(projectile) == support, "Reload must preserve the shot's purpose");
        fly(projectile);
        helper.assertTrue(support ? ally.getHealth() > 500.0F : ally.getHealth() == 500.0F,
                "Healing shots must reach allies; attack shots must pass through distant allies");
        helper.assertTrue(support ? enemy.getHealth() == 1000.0F : enemy.getHealth() < 1000.0F,
                "Attack shots must reach enemies; healing shots must pass through distant enemies");
        helper.assertTrue(externalUndead.getHealth() == 500.0F, "Neither purpose may collide with, heal or damage external undead");
        helper.succeed();
    }

    private static void fireballCollision(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob ally = mob(helper, EntityType.ZOMBIE, 6.5, 5.5);
        Mob enemy = mob(helper, EntityType.VILLAGER, 10.5, 5.5);
        Projectile projectile = DispenserProjectiles.fire(shooter, DispenserAmmo.FIREBALL, enemy);
        fly(projectile);
        helper.assertTrue(ally.getHealth() == 1000.0F && !ally.isOnFire(), "A fireball must neither damage nor ignite allied undead");
        helper.assertTrue(enemy.getHealth() < 1000.0F && enemy.isOnFire(), "A fireball must damage and ignite its enemy");
        helper.succeed();
    }

    private static void fireballTerrain(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob enemy = mob(helper, EntityType.VILLAGER, 12.5, 5.5);
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(8, y, 5, Blocks.STONE);
        }
        Projectile projectile = DispenserProjectiles.fire(shooter, DispenserAmmo.FIREBALL, enemy);
        fly(projectile);
        helper.assertTrue(projectile.isRemoved(), "The wall must absorb the fireball");
        for (int y = 1; y <= 4; y++) {
            helper.assertBlockNotPresent(Blocks.FIRE, new net.minecraft.core.BlockPos(7, y, 5));
        }
        helper.succeed();
    }

    private static AreaEffectCloud impactCloud(GameTestHelper helper, Zombie shooter, LivingEntity enemy) {
        Projectile projectile = DispenserProjectiles.fire(shooter, DispenserAmmo.DRAGON_BREATH, enemy);
        fly(projectile);
        var clouds = helper.getLevel().getEntitiesOfClass(AreaEffectCloud.class, helper.getBounds(), cloud -> cloud.getOwner() == shooter);
        helper.assertTrue(clouds.size() == 1, "A real dragon-fireball impact must create its marked breath cloud");
        return clouds.getFirst();
    }

    private static void dragonCloud(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        List<LivingEntity> group = mixedGroup(helper);
        group.forEach(entity -> entity.setHealth(entity.getMaxHealth() * 0.5F));
        float[] health = new float[group.size()];
        for (int index = 0; index < group.size(); index++) {
            health[index] = group.get(index).getHealth();
        }
        AreaEffectCloud cloud = impactCloud(helper, shooter, group.get(4));
        cloud.setPos(group.getFirst().position());
        cloud.tickCount = 19;
        cloudStep(cloud);
        for (int index = 0; index < group.size(); index++) {
            LivingEntity entity = group.get(index);
            helper.assertTrue(index < 4 ? entity.getHealth() == health[index] : entity.getHealth() < health[index],
                    "Breath must hurt every enemy, including tagged modded undead, and neither heal nor hurt vanilla undead: " + index);
            if (index >= 4) {
                helper.assertTrue(entity.getLastDamageSource() != null && entity.getLastDamageSource().is(DamageTypes.DRAGON_BREATH),
                        "The cloud must deal dragon-breath damage rather than inverted potion damage");
            }
        }
        helper.succeed();
    }

    private static void cloudReload(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob enemy = mob(helper, externalType(true), 10.5, 5.5);
        AreaEffectCloud original = impactCloud(helper, shooter, enemy);
        original.tickCount = 19;
        AreaEffectCloud loaded = (AreaEffectCloud) saveAndReload(helper, original);
        original.discard();
        Mob ally = mob(helper, EntityType.ZOMBIE, 10.5, 6.5);
        ally.setHealth(500.0F);
        loaded.setPos(enemy.position());
        cloudStep(loaded);
        helper.assertTrue(enemy.getHealth() < 1000.0F && ally.getHealth() == 500.0F,
                "A restored cloud must preserve direct damage to external undead and protect original undead");
        helper.succeed();
    }

    private static void ordinaryEffects(GameTestHelper helper) {
        prepare(helper);
        Mob owner = mob(helper, EntityType.ZOMBIE, 2.5, 5.5);
        Mob ally = mob(helper, EntityType.ZOMBIE, 10.5, 5.5);
        Mob enemy = mob(helper, EntityType.VILLAGER, 10.5, 6.5);
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withEffectAdded(new MobEffectInstance(MobEffects.SPEED, 200, 0)));
        ThrownSplashPotion ordinary = new ThrownSplashPotion(helper.getLevel(), owner, stack);
        ordinary.onHitAsPotion(helper.getLevel(), stack, new EntityHitResult(enemy, enemy.position()));
        helper.assertTrue(ally.hasEffect(MobEffects.SPEED) && enemy.hasEffect(MobEffects.SPEED), "An unmarked potion must keep ordinary splash behavior");
        ally.setHealth(500.0F);
        AreaEffectCloud cloud = new AreaEffectCloud(helper.getLevel(), ally.getX(), ally.getY(), ally.getZ());
        cloud.setOwner(owner);
        cloud.setDuration(100);
        cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 1));
        cloud.tickCount = 19;
        cloudStep(cloud);
        helper.assertTrue(ally.getHealth() > 500.0F && enemy.getHealth() < 1000.0F,
                "An unmarked instant-damage cloud must keep its vanilla healing inversion");
        helper.succeed();
    }

    private static void potionRange(GameTestHelper helper, double elevation) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob enemy = mob(helper, EntityType.VILLAGER, 34.5, 5.5);
        enemy.setPos(enemy.position().add(0.0, elevation, 0.0));
        Projectile projectile = DispenserProjectiles.fire(shooter, DispenserAmmo.SLOWNESS, enemy);
        fly(projectile);
        helper.assertTrue(enemy.hasEffect(MobEffects.SLOWNESS) && enemy.getEffect(MobEffects.SLOWNESS).getAmplifier() == 4,
                "The real potion trajectory must reach a target 32 blocks away, elevation=" + elevation);
        helper.succeed();
    }

    private static Zombie aiShooter(GameTestHelper helper) {
        Zombie shooter = shooter(helper);
        shooter.goalSelector.removeAllGoals(goal -> !(goal instanceof DispenserAttackGoal));
        shooter.targetSelector.removeAllGoals(goal -> true);
        shooter.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
        shooter.setNoAi(false);
        return shooter;
    }

    private static void aiHealing(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = aiShooter(helper);
        Mob ally = mob(helper, EntityType.ZOMBIE, 10.5, 5.5);
        MobBackpack.sync(ally, 0, ally.getRandom());
        ally.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        ally.setHealth(500.0F);
        List<Mob> excluded = List.of(mob(helper, EntityType.ZOMBIFIED_PIGLIN, 2.5, 10.5),
                mob(helper, EntityType.WITHER_SKELETON, 2.5, 0.5),
                mob(helper, externalType(true), 2.5, 9.5));
        excluded.forEach(entity -> entity.setHealth(100.0F));
        helper.succeedWhen(() -> {
            helper.assertTrue(ally.getHealth() > 500.0F, "The real dispenser AI must heal even an unreinforced vanilla zombie");
            excluded.forEach(entity -> helper.assertTrue(entity.getHealth() == 100.0F,
                    "More severely wounded entities outside the managed zombie/skeleton families must not receive targeted support"));
            helper.assertTrue(shooter.getTarget() == null, "Supporting an ally must not create an attack target");
        });
    }

    private static void aiBuffs(GameTestHelper helper) {
        prepare(helper);
        aiShooter(helper);
        Mob ally = mob(helper, EntityType.SKELETON, 10.5, 5.5);
        MobBackpack.sync(ally, 0, ally.getRandom());
        ally.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        helper.succeedWhen(() -> {
            for (var effect : List.of(MobEffects.RESISTANCE, MobEffects.SPEED, MobEffects.STRENGTH)) {
                helper.assertTrue(ally.getEffect(effect) != null && ally.getEffect(effect).getAmplifier() == 4,
                        "The real AI must supply every positive level-five potion, missing=" + effect);
            }
        });
    }

    private static Projectile aiShot(GameTestHelper helper, Zombie shooter) {
        DispenserAttackGoal goal = new DispenserAttackGoal(shooter);
        for (int tick = 0; tick < 45; tick++) {
            // Seed the actual shot decision, rather than depending on random world-tick consumption.
            shooter.getRandom().setSeed(0L);
            goal.tick();
            List<Projectile> shots = helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds(),
                    projectile -> projectile.getOwner() == shooter && DispenserProjectiles.ammo(projectile) != null);
            if (!shots.isEmpty()) {
                return shots.getFirst();
            }
        }
        throw new IllegalStateException("The dispenser AI did not select a valid attack");
    }

    private static void aiHarming(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob enemy = mob(helper, EntityType.VILLAGER, 12.5, 5.5);
        shooter.setTarget(enemy);
        Projectile projectile = aiShot(helper, shooter);
        helper.assertTrue(DispenserProjectiles.ammo(projectile) == DispenserAmmo.INSTANT_DAMAGE
                        && !DispenserProjectiles.isSupportShot(projectile),
                "The real attack decision must be able to select instant harming as an offensive potion");
        fly(projectile);
        helper.assertTrue(enemy.getHealth() < 1000.0F, "The selected harming potion must actually damage the enemy");
        helper.succeed();
    }

    private static void aiUndeadEnemy(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = shooter(helper);
        Mob enemy = mob(helper, externalType(true), 12.5, 5.5);
        shooter.setTarget(enemy);
        Projectile projectile = aiShot(helper, shooter);
        DispenserAmmo ammo = DispenserProjectiles.ammo(projectile);
        helper.assertTrue(ammo == DispenserAmmo.DRAGON_BREATH || ammo == DispenserAmmo.FIREBALL,
                "The AI must filter every negative potion out of the attack pool against external undead");
        fly(projectile);
        if (ammo == DispenserAmmo.DRAGON_BREATH) {
            helper.getLevel().getEntitiesOfClass(AreaEffectCloud.class, helper.getBounds(), cloud -> cloud.getOwner() == shooter)
                    .forEach(cloud -> {
                        cloud.setPos(enemy.position());
                        cloud.tickCount = 19;
                        cloudStep(cloud);
                    });
        }
        helper.assertTrue(enemy.getHealth() < 1000.0F, "Existing fireball and dragon-breath attacks must still damage external undead");
        helper.succeed();
    }

    private static void aiMixed(GameTestHelper helper) {
        prepare(helper);
        Zombie shooter = aiShooter(helper);
        mob(helper, EntityType.ZOMBIE, 10.5, 5.5);
        Mob enemy = mob(helper, EntityType.VILLAGER, 18.5, 5.5);
        shooter.setTarget(enemy);
        boolean[] seen = {false, false};
        helper.onEachTick(() -> helper.getLevel().getEntitiesOfClass(Projectile.class, helper.getBounds(),
                projectile -> projectile.getOwner() == shooter).forEach(projectile -> {
                    DispenserAmmo ammo = DispenserProjectiles.ammo(projectile);
                    if (ammo != null) {
                        seen[DispenserProjectiles.isSupportShot(projectile) ? 0 : 1] = true;
                    }
                }));
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0] && seen[1],
                    "The real AI must both support an ally and shoot its enemy");
            helper.assertTrue(shooter.getTarget() == enemy, "Support must retain the original hostile target");
        });
    }

    private static final class PercentileRandom extends LegacyRandomSource {
        private final int percentile;

        private PercentileRandom(int percentile) {
            super(0L);
            this.percentile = percentile;
        }

        @Override
        public int nextInt(int bound) {
            return this.percentile % bound;
        }
    }
}
