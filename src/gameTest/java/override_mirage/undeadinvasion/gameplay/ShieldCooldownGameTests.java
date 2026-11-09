package override_mirage.undeadinvasion.gameplay;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.gameplay.ai.BowAttackGoal;
import override_mirage.undeadinvasion.gameplay.ai.ShieldBlockGoal;
import override_mirage.undeadinvasion.mixin.AbstractArrowAccessor;

/** Regression tests included only in the game test run, never in the distributed mod. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class ShieldCooldownGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("axe_disables_shield_for_100_ticks", ShieldCooldownGameTests::axeCooldown),
            Map.entry("swords_and_projectiles_do_not_disable_shields", ShieldCooldownGameTests::otherDamage),
            Map.entry("drowned_cooldown_survives_reload_and_equipment_sync", ShieldCooldownGameTests::reloadDrowned),
            Map.entry("cancelled_axe_hit_does_not_disable_shield", ShieldCooldownGameTests::cancelledHit),
            Map.entry("projectiles_within_five_blocks_raise_shield_without_target", ShieldCooldownGameTests::projectileRange),
            Map.entry("environment_does_not_raise_or_disable_a_shield", ShieldCooldownGameTests::environmentDamage),
            Map.entry("blocking_a_projectile_keeps_shield_raised", ShieldCooldownGameTests::keepShieldRaised),
            Map.entry("shield_takes_priority_over_drawing_a_bow", ShieldCooldownGameTests::shieldPriority),
            Map.entry("grounded_arrows_do_not_keep_shield_raised", ShieldCooldownGameTests::groundedArrow),
            Map.entry("wall_embedded_arrows_do_not_keep_shield_raised", ShieldCooldownGameTests::wallEmbeddedArrow),
            Map.entry("player_left_click_blocks_the_first_sword_hit", ShieldCooldownGameTests::meleeClick),
            Map.entry("left_click_axe_breaks_shield_and_cooldown_blocks_both_triggers", ShieldCooldownGameTests::meleeAxeCooldown),
            Map.entry("melee_shield_requires_a_visible_reachable_uncancelled_attack", ShieldCooldownGameTests::meleeConditions));

    private ShieldCooldownGameTests() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, path);
    }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry ->
                TESTS.forEach((name, function) -> registry.register(id(name), function)));
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("shield_cooldown"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"),
                        140, 0, true, Rotation.NONE, false, 1, 1, false, 12))));
    }

    private static <T extends Mob> T prepare(GameTestHelper helper, EntityType<T> type) {
        T mob = helper.spawnWithNoFreeWill(type, new BlockPos(1, 1, 1));
        mob.setNoGravity(true);
        MobBackpack.sync(mob, 30, mob.getRandom());
        mob.setYHeadRot(0.0F);
        return mob;
    }

    private static Player attacker(GameTestHelper helper, Mob mob, ItemStack weapon) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(mob.position().add(0.0, 0.0, 2.0));
        player.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        mob.setTarget(player);
        return player;
    }

    private static void incomingArrow(GameTestHelper helper, Mob mob, ShieldBlockGoal goal) {
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(mob.position().add(0.0, mob.getBbHeight() * 0.5, 2.0));
        arrow.setDeltaMovement(new Vec3(0.0, 0.0, -1.0));
        helper.getLevel().addFreshEntity(arrow);
        goal.tick();
        arrow.discard();
    }

    private static Arrow stationaryArrow(GameTestHelper helper, Mob mob) {
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(mob.getBoundingBox().getCenter().add(0.0, 0.0, 2.0));
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(arrow);
        return arrow;
    }

    private static void axeCooldown(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_AXE));
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        ItemStack shield = mob.getOffhandItem();
        shield.set(DataComponents.CUSTOM_NAME, Component.literal("Cooldown regression shield"));
        shield.setDamageValue(7);

        incomingArrow(helper, mob, goal);
        helper.assertTrue(mob.isBlocking(), "Incoming arrows must raise the shield before the axe hit");
        player.attack(mob);
        helper.assertTrue(ShieldBreak.isBroken(mob), "A real player axe attack must start cooldown");
        helper.assertFalse(mob.isUsingItem(), "The axe hit must lower the shield immediately");
        helper.assertTrue(mob.getOffhandItem() == shield, "Cooldown must retain the original shield stack");
        helper.assertFalse(goal.canUse(), "Shield AI must not start during cooldown");

        // Test both an already running goal and a different AI starting item use.
        mob.startUsingItem(InteractionHand.OFF_HAND);
        incomingArrow(helper, mob, goal);
        helper.assertFalse(mob.isUsingItem(), "A running goal must lower the shield during cooldown");
        mob.startUsingItem(InteractionHand.OFF_HAND);
        float blocked = mob.applyItemBlocking(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4.0F);
        helper.assertTrue(blocked == 0.0F, "A disabled shield must not block even if another AI raises it");
        mob.stopUsingItem();
        Arrow nearby = stationaryArrow(helper, mob);

        MobBackpack.sync(mob, 40, mob.getRandom());
        helper.assertTrue(ShieldBreak.isBroken(mob), "Equipment tier changes must preserve cooldown");
        helper.assertTrue(mob.getOffhandItem() == shield, "Equipment sync must preserve shield metadata");

        helper.runAfterDelay(50, () -> {
            player.setPos(mob.position().add(0.0, 0.0, 2.0));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            mob.invulnerableTime = 0;
            player.attack(mob);
            helper.assertTrue(ShieldBreak.isBroken(mob), "A sword click during cooldown must leave it active");
            helper.assertFalse(mob.isBlocking(), "Left clicks must not raise a disabled shield");
        });
        helper.runAfterDelay(99, () -> {
            player.setPos(mob.position().add(0.0, 0.0, 2.0));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            mob.invulnerableTime = 0;
            player.attack(mob);
            helper.assertTrue(ShieldBreak.isBroken(mob), "Cooldown must still be active at tick 99");
            helper.assertFalse(goal.canUse(), "Shield AI must remain disabled until tick 100");
            incomingArrow(helper, mob, goal);
            helper.assertFalse(mob.isUsingItem(), "Incoming arrows must not bypass cooldown");
        });
        helper.runAfterDelay(100, () -> {
            helper.assertFalse(ShieldBreak.isBroken(mob), "Cooldown must expire exactly at tick 100");
            helper.assertTrue(goal.canUse(), "Shield AI must resume when cooldown expires");
            incomingArrow(helper, mob, goal);
            helper.assertTrue(mob.isBlocking(), "The shield must block incoming arrows again after cooldown");
            nearby.discard();
            goal.tick();
            helper.assertFalse(mob.isBlocking(), "The shield must lower when no projectiles remain");
            helper.assertFalse(goal.canUse(), "Cooldown expiry alone must not activate shield AI");
            helper.succeed();
        });
    }

    private static void otherDamage(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_SWORD));
        mob.startUsingItem(InteractionHand.OFF_HAND);
        player.attack(mob);
        helper.assertFalse(ShieldBreak.isBroken(mob), "Sword attacks must not disable shields");

        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setOwner(player);
        arrow.setPos(player.position());
        mob.invulnerableTime = 0;
        mob.hurtServer(helper.getLevel(), helper.getLevel().damageSources().arrow(arrow, player), 2.0F);
        helper.assertFalse(ShieldBreak.isBroken(mob), "Arrows must not disable shields when their shooter holds an axe");

        mob.setLastHurtByMob(player);
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        incomingArrow(helper, mob, goal);
        helper.assertFalse(ShieldBreak.isBroken(mob), "Last attacker state must not turn projectile hits into axe hits");
        helper.assertTrue(mob.isBlocking(), "Projectile hits must leave the shield available");
        helper.succeed();
    }

    private static void reloadDrowned(GameTestHelper helper) {
        Drowned mob = prepare(helper, EntityType.DROWNED);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_AXE));
        mob.getOffhandItem().setDamageValue(13);
        mob.startUsingItem(InteractionHand.OFF_HAND);
        player.attack(mob);
        helper.assertTrue(ShieldBreak.isBroken(mob), "Axe attacks must also disable drowned shields");

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        mob.saveWithoutId(output);
        Drowned reloaded = new Drowned(EntityType.DROWNED, helper.getLevel());
        reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
        reloaded.setTarget(player);
        MobBackpack.sync(reloaded, 40, reloaded.getRandom());
        ShieldBlockGoal goal = new ShieldBlockGoal(reloaded);

        helper.assertTrue(ShieldBreak.isBroken(reloaded), "Entity serialization must preserve cooldown");
        helper.assertFalse(goal.canUse(), "Reloaded drowned must not raise a disabled shield");
        helper.assertTrue(reloaded.getOffhandItem().getDamageValue() == 13, "Reload and equipment sync must retain shield durability");
        helper.runAfterDelay(100, () -> {
            helper.assertFalse(ShieldBreak.isBroken(reloaded), "Reload must not extend the original cooldown");
            helper.assertFalse(goal.canUse(), "A recovered drowned must still wait for a nearby projectile");
            incomingArrow(helper, reloaded, goal);
            helper.assertTrue(reloaded.isBlocking(), "Reloaded drowned must defend again after the original 100 ticks");
            helper.succeed();
        });
    }

    private static void cancelledHit(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_AXE));
        mob.startUsingItem(InteractionHand.OFF_HAND);
        Consumer<LivingIncomingDamageEvent> cancel = event -> {
            if (event.getEntity() == mob) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, LivingIncomingDamageEvent.class, cancel);
        try {
            player.attack(mob);
            helper.assertFalse(ShieldBreak.isBroken(mob), "Cancelled axe attacks must not start cooldown");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
        }
        helper.succeed();
    }

    private static void projectileRange(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        mob.setTarget(null);
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        Vec3 center = mob.getBoundingBox().getCenter();
        helper.assertFalse(goal.canUse(), "No projectile must mean no shield activation");

        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(center.add(5.01, 0.0, 0.0));
        arrow.setDeltaMovement(1.0, 0.0, 0.0);
        helper.getLevel().addFreshEntity(arrow);
        goal.tick();
        helper.assertFalse(mob.isBlocking(), "Projectiles outside five blocks must not raise the shield");

        arrow.setPos(center.add(5.0, 0.0, 0.0));
        helper.assertTrue(goal.canUse(), "A projectile at five blocks must activate shield AI without a target");
        goal.tick();
        helper.assertTrue(mob.isBlocking(), "Nearby projectiles must raise the shield even when moving away");
        arrow.setPos(center.add(5.01, 0.0, 0.0));
        goal.tick();
        helper.assertFalse(mob.isBlocking(), "The shield must lower when a projectile leaves the radius");
        arrow.discard();

        Snowball snowball = new Snowball(EntityType.SNOWBALL, helper.getLevel());
        snowball.setPos(center.add(0.0, 0.0, 4.0));
        snowball.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(snowball);
        goal.tick();
        helper.assertTrue(mob.isBlocking(), "Other projectile types must also raise the shield based on presence");
        snowball.discard();
        goal.tick();
        helper.assertFalse(mob.isBlocking(), "Removed projectiles must no longer keep the shield raised");
        helper.succeed();
    }

    private static void environmentDamage(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_AXE));
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        goal.tick();
        mob.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 1.0F);
        helper.assertFalse(ShieldBreak.isBroken(mob) || mob.isBlocking() || goal.canUse(),
                "Environmental damage must not raise or disable a lowered shield");

        incomingArrow(helper, mob, goal);
        helper.assertTrue(mob.isBlocking(), "A nearby projectile must raise the shield");
        mob.invulnerableTime = 0;
        player.attack(mob);
        helper.assertTrue(ShieldBreak.isBroken(mob), "The same axe hit must disable a raised shield");
        helper.assertFalse(mob.isBlocking(), "Breaking a raised shield must immediately stop blocking");
        helper.succeed();
    }

    private static void keepShieldRaised(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_SWORD));
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        Arrow arrow = stationaryArrow(helper, mob);
        arrow.setOwner(player);
        goal.tick();
        helper.assertTrue(mob.isBlocking(), "A nearby arrow must raise the shield");

        float health = mob.getHealth();
        mob.hurtServer(helper.getLevel(), helper.getLevel().damageSources().arrow(arrow, player), 2.0F);
        helper.assertTrue(mob.getHealth() == health, "The projectile must be blocked");
        helper.assertTrue(mob.isBlocking(), "Blocking one projectile must not force the shield down");
        goal.tick();
        helper.assertTrue(mob.isBlocking(), "A remaining projectile must keep the shield raised");
        arrow.discard();
        goal.tick();
        helper.assertFalse(mob.isBlocking(), "The shield must lower once the last projectile is removed");
        helper.succeed();
    }

    private static void shieldPriority(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        attacker(helper, mob, new ItemStack(Items.IRON_AXE));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        GoalSelector selector = new GoalSelector();
        selector.addGoal(2, new BowAttackGoal(mob, 1.0, 15.0F));
        selector.addGoal(0, new ShieldBlockGoal(mob));
        selector.tick();
        helper.assertTrue(mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.MAIN_HAND,
                "The zombie must draw its bow before a projectile appears");

        Arrow arrow = stationaryArrow(helper, mob);
        selector.tick();
        helper.assertTrue(mob.isBlocking() && mob.getUsedItemHand() == InteractionHand.OFF_HAND,
                "A nearby projectile must interrupt bow drawing and raise the shield");
        selector.tick();
        helper.assertTrue(mob.isBlocking(), "The bow goal must not take the shield's item use slot");

        arrow.discard();
        selector.tick();
        helper.assertTrue(mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.MAIN_HAND,
                "The bow goal must resume once there are no nearby projectiles");
        helper.succeed();
    }

    private static void groundedArrow(GameTestHelper helper) {
        embeddedArrow(helper, false);
    }

    private static void wallEmbeddedArrow(GameTestHelper helper) {
        embeddedArrow(helper, true);
    }

    private static void embeddedArrow(GameTestHelper helper, boolean wall) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        helper.setBlock(wall ? new BlockPos(4, 2, 1) : new BlockPos(3, 1, 1), Blocks.STONE);

        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(helper.absoluteVec(wall ? new Vec3(3.0, 2.5, 1.5) : new Vec3(3.5, 3.5, 1.5)));
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(wall ? new Vec3(0.5, 0.0, 0.0) : new Vec3(0.0, -0.5, 0.0));
        helper.getLevel().addFreshEntity(arrow);
        try {
            goal.tick();
            helper.assertTrue(mob.isBlocking(), "The airborne arrow must raise the shield before impact");

            AbstractArrowAccessor state = (AbstractArrowAccessor) arrow;
            for (int step = 0; step < 20 && !state.undeadinvasion$isInGround(); step++) {
                arrow.tick();
            }
            helper.assertTrue(state.undeadinvasion$isInGround(), "The arrow must hit the real block and become embedded");
            helper.assertTrue(arrow.isAlive(), "The embedded arrow must still exist in the world");
            helper.assertTrue(arrow.position().distanceToSqr(mob.getBoundingBox().getCenter()) <= 25.0,
                    "The embedded arrow must remain within the five block detection radius");

            goal.tick();
            helper.assertFalse(mob.isBlocking(), "An embedded arrow must cause the shield to lower");
            helper.assertFalse(goal.canUse(), "Embedded arrows must not start shield AI");
            helper.assertFalse(goal.canContinueToUse(), "Embedded arrows must not keep shield AI running");

            incomingArrow(helper, mob, goal);
            helper.assertTrue(mob.isBlocking(), "A new airborne arrow must still raise the shield beside an embedded arrow");
            goal.tick();
            helper.assertFalse(mob.isBlocking(), "Only the embedded arrow remains, so the shield must lower again");
            helper.succeed();
        } finally {
            arrow.discard();
        }
    }

    private static void meleeClick(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        // A newly equipped vanilla shield must also block the first hit immediately.
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        Mob bystander = prepare(helper, EntityType.ZOMBIE);
        bystander.setPos(mob.position().add(2.0, 0.0, 0.0));
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_SWORD));
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        float health = mob.getHealth();
        helper.assertFalse(goal.canUse(), "The shield must be lowered before a player clicks");

        player.attack(mob);
        helper.assertTrue(mob.isBlocking(), "The player left click must raise the targeted undead's shield");
        helper.assertTrue(mob.getHealth() == health, "The first sword hit must already be blocked");
        helper.assertFalse(ShieldBreak.isBroken(mob), "Sword clicks must not apply axe cooldown");
        helper.assertFalse(bystander.isBlocking(), "Undead that were not targeted must not react to the click");
        goal.tick();
        helper.assertTrue(mob.isBlocking(), "The melee trigger must hold the shield without any projectiles");

        helper.runAfterDelay(9, () -> {
            goal.tick();
            helper.assertTrue(mob.isBlocking(), "The melee shield reaction must remain active for half a second");
        });
        helper.runAfterDelay(10, () -> {
            goal.tick();
            helper.assertFalse(goal.canUse(), "The melee reaction must expire after ten ticks");
            helper.assertFalse(mob.isBlocking(), "A click must not leave the shield raised indefinitely");
            helper.succeed();
        });
    }

    private static void meleeAxeCooldown(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_AXE));
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);
        helper.assertFalse(mob.isBlocking(), "No projectile or earlier click should raise the shield yet");

        player.attack(mob);
        mob.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(ShieldBreak.isBroken(mob), "The axe click must raise and then break the shield in the same attack");
        helper.assertFalse(mob.isBlocking(), "Axe breaking must lower the shield immediately");
        Arrow nearby = stationaryArrow(helper, mob);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        mob.invulnerableTime = 0;
        player.attack(mob);
        mob.setDeltaMovement(Vec3.ZERO);
        goal.tick();
        helper.assertFalse(mob.isBlocking(), "Neither the new click nor a nearby arrow may raise the disabled shield");
        helper.assertFalse(goal.canUse(), "Both trigger types must obey the shared cooldown");

        helper.runAfterDelay(100, () -> {
            helper.assertFalse(ShieldBreak.isBroken(mob), "Melee-triggered shield breaking must expire after 100 ticks");
            goal.tick();
            helper.assertTrue(mob.isBlocking(), "Nearby arrows must work again after melee shield cooldown");
            nearby.discard();
            goal.tick();
            helper.assertFalse(mob.isBlocking(), "Clicks during cooldown must not leave a queued shield reaction");

            player.setPos(mob.position().add(0.0, 0.0, 2.0));
            mob.invulnerableTime = 0;
            float health = mob.getHealth();
            player.attack(mob);
            helper.assertTrue(mob.isBlocking() && mob.getHealth() == health,
                    "A new left click must block normally once cooldown has expired");
            helper.succeed();
        });
    }

    private static void meleeConditions(GameTestHelper helper) {
        Mob mob = prepare(helper, EntityType.ZOMBIE);
        Player player = attacker(helper, mob, new ItemStack(Items.IRON_SWORD));
        ShieldBlockGoal goal = new ShieldBlockGoal(mob);

        player.setPos(mob.position().add(0.0, 0.0, 8.0));
        NeoForge.EVENT_BUS.post(new AttackEntityEvent(player, mob));
        helper.assertFalse(mob.isBlocking() || goal.canUse(), "Targets outside the player's attack reach must not react");

        player.setPos(mob.position().add(0.0, 0.0, 2.0));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.STONE);
        NeoForge.EVENT_BUS.post(new AttackEntityEvent(player, mob));
        helper.assertFalse(mob.isBlocking() || goal.canUse(), "Undead behind a wall must not react to the click");
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.AIR);

        Consumer<AttackEntityEvent> cancel = event -> {
            if (event.getTarget() == mob) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, AttackEntityEvent.class, cancel);
        try {
            player.attack(mob);
            helper.assertFalse(mob.isBlocking() || goal.canUse(), "Cancelled attacks must not trigger shield use");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
        }

        player.attack(mob);
        helper.assertTrue(mob.isBlocking(), "A visible target within reach must react to a normal click");
        helper.succeed();
    }
}
