package override_mirage.undeadinvasion.gameplay;

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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.world.DayCounterData;

/** Exercise native sunlight wear, equipped-item damage, transfers and saved entities. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class HelmetDurabilityGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("undead_helmet_durability_protection_starts_on_day_ten", HelmetDurabilityGameTests::threshold),
            Map.entry("undead_helmets_survive_sunlight_but_bare_heads_still_burn", HelmetDurabilityGameTests::sunlight),
            Map.entry("helmet_protection_does_not_change_other_gear_or_player_items", HelmetDurabilityGameTests::otherEquipment),
            Map.entry("helmet_protection_excludes_unmanaged_and_external_mobs", HelmetDurabilityGameTests::excludedMobs),
            Map.entry("helmet_protection_survives_reload_and_tracks_the_current_day", HelmetDurabilityGameTests::reload));

    private HelmetDurabilityGameTests() {}

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
        var environment = event.registerEnvironment(id("helmet_durability"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"),
                        40, 0, true, Rotation.NONE, false, 1, 1, false, 12))));
    }

    private static void withDay(GameTestHelper helper, long day, Runnable action) {
        var days = DayCounterData.get(helper.getLevel().getServer());
        days.ensureInitialized(helper.getLevel().getGameTime());
        long previousDay = days.getDay();
        days.setDay(day);
        try {
            action.run();
        } finally {
            days.setDay(previousDay);
        }
    }

    private static Mob mob(GameTestHelper helper, EntityType<? extends Mob> type) {
        Mob mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.setPos(helper.absoluteVec(new Vec3(1.5, 2.0, 1.5)));
        mob.setNoAi(true);
        mob.setNoGravity(true);
        MobBackpack.sync(mob, DayCounterData.dayOf(helper.getLevel()), mob.getRandom());
        return mob;
    }

    private static ItemStack helmet(Mob mob, int damage) {
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.setDamageValue(damage);
        mob.setItemSlot(EquipmentSlot.HEAD, helmet);
        return helmet;
    }

    private static void threshold(GameTestHelper helper) {
        for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIE, EntityType.HUSK,
                EntityType.ZOMBIE_VILLAGER, EntityType.DROWNED, EntityType.SKELETON, EntityType.STRAY,
                EntityType.BOGGED, EntityType.PARCHED)) {
            withDay(helper, 9, () -> {
                Mob mob = mob(helper, type);
                ItemStack helmet = helmet(mob, 0);
                helmet.setDamageValue(helmet.getMaxDamage() - 1);
                helmet.hurtAndBreak(1, mob, EquipmentSlot.HEAD);
                helper.assertTrue(helmet.isEmpty(), "Day nine must retain normal helmet breakage: " + type);
            });
            for (long day : new long[]{10, 45, 70}) {
                withDay(helper, day, () -> {
                    Mob mob = mob(helper, type);
                    ItemStack helmet = helmet(mob, 0);
                    int damage = helmet.getMaxDamage() - 1;
                    helmet.setDamageValue(damage);
                    helmet.hurtAndBreak(50, mob, EquipmentSlot.HEAD);
                    helper.assertTrue(!helmet.isEmpty() && helmet.getDamageValue() == damage,
                            "Worn helmets must retain their durability from day ten: " + type + ", day=" + day);
                    helper.assertFalse(helmet.has(DataComponents.UNBREAKABLE), "Protection must not alter the helmet's components");
                });
            }
        }
        helper.succeed();
    }

    private static void sunlight(GameTestHelper helper) {
        helper.setTime(6000);
        helper.runAfterDelay(2, () -> {
            helper.getLevel().updateSkyBrightness();
            for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIE, EntityType.SKELETON)) {
                withDay(helper, 9, () -> {
                    Mob mob = mob(helper, type);
                    ItemStack helmet = helmet(mob, 0);
                    mob.getRandom().setSeed(0L);
                    for (int tick = 0; tick < 512; tick++) {
                        mob.aiStep();
                    }
                    helper.assertTrue(helmet.getDamageValue() > 0, "The daylight fixture must actually wear an ordinary helmet: " + type);
                });
                withDay(helper, 10, () -> {
                    Mob mob = mob(helper, type);
                    ItemStack helmet = helmet(mob, 0);
                    int damage = helmet.getMaxDamage() - 1;
                    helmet.setDamageValue(damage);
                    mob.getRandom().setSeed(0L);
                    for (int tick = 0; tick < 512; tick++) {
                        mob.aiStep();
                    }
                    helper.assertTrue(mob.getItemBySlot(EquipmentSlot.HEAD) == helmet && helmet.getDamageValue() == damage,
                            "Native daylight ticks must not wear or break a protected helmet: " + type);
                    helper.assertFalse(mob.isOnFire(), "The protected helmet must still block sunlight");
                    mob.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                    mob.getRandom().setSeed(0L);
                    for (int tick = 0; tick < 512; tick++) {
                        mob.aiStep();
                    }
                    helper.assertTrue(mob.isOnFire(), "Removing the helmet must restore normal sunlight burning");
                });
            }
            helper.succeed();
        });
    }

    private static void otherEquipment(GameTestHelper helper) {
        withDay(helper, 10, () -> {
            Mob mob = mob(helper, EntityType.ZOMBIE);
            ItemStack helmet = helmet(mob, 7);
            helmet.hurtAndBreak(5, mob, EquipmentSlot.HEAD);
            helper.assertTrue(helmet.getDamageValue() == 7, "Head protection must cover the normal equipment damage path");
            helmet.hurtAndBreak(-2, mob, EquipmentSlot.HEAD);
            helper.assertTrue(helmet.getDamageValue() == 5, "Repairing a protected helmet must still work");
            for (EquipmentSlot slot : List.of(EquipmentSlot.CHEST, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                ItemStack stack = new ItemStack(switch (slot) {
                    case CHEST -> Items.IRON_CHESTPLATE;
                    case MAINHAND -> Items.IRON_SWORD;
                    default -> Items.SHIELD;
                });
                mob.setItemSlot(slot, stack);
                stack.hurtAndBreak(5, mob, slot);
                helper.assertTrue(stack.getDamageValue() == 5, "Other armor, weapons and shields must retain durability use: " + slot);
            }
            mob.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            helmet.hurtAndBreak(5, player, EquipmentSlot.HEAD);
            helper.assertTrue(helmet.getDamageValue() == 10, "The same helmet must consume durability after transfer to a player");
        });
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static void excludedMobs(GameTestHelper helper) {
        EntityType<? extends Mob> external = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.fromNamespaceAndPath("undeadinvasion_combat_fixture", "external_undead"));
        withDay(helper, 10, () -> {
            for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER_SKELETON,
                    EntityType.IRON_GOLEM, external)) {
                Mob mob = mob(helper, type);
                ItemStack helmet = helmet(mob, 7);
                helmet.hurtAndBreak(5, mob, EquipmentSlot.HEAD);
                helper.assertTrue(helmet.getDamageValue() == 12, "Unmanaged mobs must retain normal helmet durability: " + type);
            }
        });
        helper.succeed();
    }

    private static void reload(GameTestHelper helper) {
        withDay(helper, 10, () -> {
            Mob original = mob(helper, EntityType.ZOMBIE);
            helmet(original, 7);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            helper.assertTrue(original.save(output), "The equipped undead must serialize");
            Mob loaded = (Mob) EntityType.loadEntityRecursive(output.buildResult(), helper.getLevel(), EntitySpawnReason.LOAD, entity -> entity);
            helper.assertTrue(loaded != null, "The equipped undead must reload");
            ItemStack helmet = loaded.getItemBySlot(EquipmentSlot.HEAD);
            helmet.hurtAndBreak(5, helper.getLevel(), loaded, item -> loaded.onEquippedItemBroken(item, EquipmentSlot.HEAD));
            helper.assertTrue(helmet.getDamageValue() == 7, "Reloading must retain protection without changing the saved item");
            withDay(helper, 9, () -> {
                helmet.hurtAndBreak(5, loaded, EquipmentSlot.HEAD);
                helper.assertTrue(helmet.getDamageValue() == 12, "Changing the world back below day ten must restore normal wear");
            });
            helmet.hurtAndBreak(5, loaded, EquipmentSlot.HEAD);
            helper.assertTrue(helmet.getDamageValue() == 12, "Returning to day ten must immediately restore protection");
        });
        helper.succeed();
    }
}
