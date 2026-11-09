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
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.config.UndeadConfig;

/** Exhaustive percentile buckets verify weapon odds without statistical test failures. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class MobEquipmentGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("spear_probability_is_five_percent_in_every_equipment_tier", MobEquipmentGameTests::freshEquipment),
            Map.entry("repairing_missing_weapon_data_uses_five_percent_spear_probability", MobEquipmentGameTests::missingWeaponData));

    private MobEquipmentGameTests() {}

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
        var environment = event.registerEnvironment(id("mob_equipment"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"),
                        40, 0, true, Rotation.NONE, false, 1, 1, false, 12))));
    }

    private static void freshEquipment(GameTestHelper helper) {
        var settings = UndeadConfig.settings();
        long[] days = {settings.leatherDay(), settings.copperDay(), settings.goldDay(), settings.chainDay(),
                settings.ironDay(), settings.diamondDay(), settings.netheriteDay()};
        Item[] spears = {Items.WOODEN_SPEAR, Items.COPPER_SPEAR, Items.GOLDEN_SPEAR, Items.IRON_SPEAR,
                Items.IRON_SPEAR, Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR};
        Zombie mob = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        for (int tier = 0; tier < days.length; tier++) {
            int spearCount = 0;
            for (int percentile = 0; percentile < 100; percentile++) {
                mob.getPersistentData().remove("undeadinvasion:backpack_kit");
                MobBackpack.sync(mob, days[tier], new PercentileRandom(percentile));
                Item weapon = mob.getMainHandItem().getItem();
                if (weapon == spears[tier]) {
                    spearCount++;
                }
                mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                MobBackpack.sync(mob, days[tier], new PercentileRandom(99 - percentile));
                helper.assertTrue(mob.getMainHandItem().getItem() == weapon,
                        "Repairing equipment must preserve the chosen weapon rather than repeatedly roll its odds");
            }
            helper.assertTrue(spearCount == 5, "Day " + days[tier] + " must allocate exactly five of 100 buckets to spears");
        }
        helper.succeed();
    }

    private static void missingWeaponData(GameTestHelper helper) {
        Zombie mob = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        long day = UndeadConfig.settings().ironDay();
        MobBackpack.sync(mob, day, new PercentileRandom(99));
        int spearCount = 0;
        for (int percentile = 0; percentile < 100; percentile++) {
            mob.getPersistentData().remove("undeadinvasion:melee_index");
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            MobBackpack.sync(mob, day, new PercentileRandom(percentile));
            if (mob.getMainHandItem().is(Items.IRON_SPEAR)) {
                spearCount++;
            }
        }
        helper.assertTrue(spearCount == 5, "Missing saved weapon data must use the same five-percent distribution");
        helper.succeed();
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
