package override_mirage.undeadinvasion.gameplay;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.client.ClientAcidRain;
import override_mirage.undeadinvasion.network.AcidRainPayload;
import override_mirage.undeadinvasion.world.AcidRainData;
import override_mirage.undeadinvasion.world.DayCounterData;
import override_mirage.undeadinvasion.world.StormType;

/** Exact day-roll buckets, saved storms, native weather/exposure and real server-tick damage. */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class AcidRainGameTests {
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("acid_rain_starts_on_day_30_with_five_percent_chance", AcidRainGameTests::threshold),
            Map.entry("acid_rain_chance_increases_daily_and_resets_after_rain", AcidRainGameTests::progression),
            Map.entry("acid_rain_probability_caps_at_one_hundred_percent", AcidRainGameTests::cap),
            Map.entry("acid_rain_roll_and_active_storm_survive_save_reload", AcidRainGameTests::persistence),
            Map.entry("acid_rain_skipped_days_and_ongoing_storm_are_counted", AcidRainGameTests::dayTransitions),
            Map.entry("acid_rain_starts_and_finishes_native_rain_without_reroll", AcidRainGameTests::weatherLifecycle),
            Map.entry("acid_rain_respects_weather_cycle_and_weather_clear", AcidRainGameTests::weatherControl),
            Map.entry("acid_rain_deals_one_damage_to_all_non_undead_in_rain", AcidRainGameTests::damage),
            Map.entry("acid_rain_protects_vanilla_and_external_undead", AcidRainGameTests::undead),
            Map.entry("acid_rain_roof_and_weatherless_dimensions_protect_entities", AcidRainGameTests::cover),
            Map.entry("ordinary_rain_does_not_apply_acid_damage", AcidRainGameTests::ordinaryRain),
            Map.entry("acid_rain_real_server_damage_occurs_once_per_second", AcidRainGameTests::cadence),
            Map.entry("acid_rain_sync_roundtrip_and_color_preserve_transparency", AcidRainGameTests::sync),
            Map.entry("acid_rain_command_starts_immediately_below_threshold", AcidRainGameTests::commandStart),
            Map.entry("acid_rain_command_stop_clears_storm_and_weather", AcidRainGameTests::commandStop),
            Map.entry("acid_rain_is_environmental_no_knockback_and_exactly_one_damage", AcidRainGameTests::environmentalDamage),
            Map.entry("sulfur_rain_ignites_exposed_creatures_including_undead", AcidRainGameTests::sulfurIgnite),
            Map.entry("sulfur_rain_and_acid_rain_are_mutually_exclusive", AcidRainGameTests::mutualExclusion),
            Map.entry("sulfur_rain_auto_roll_split_and_persistence", AcidRainGameTests::sulfurRoll));

    private AcidRainGameTests() {}

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, name); }

    /** 把返回 {@link StormType} 的每日判定转成旧测试用的 boolean。 */
    private static boolean started(StormType type) { return type != StormType.NONE; }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> TESTS.forEach((name, function) -> registry.register(id(name), function)));
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("acid_rain"));
        TESTS.forEach((name, function) -> event.registerTest(id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                new TestData<>(environment, id("siege_arena"), 100, 0, true, Rotation.NONE, false, 1, 1, true, 18))));
    }

    private static void threshold(GameTestHelper helper) {
        int starts = 0;
        for (int bucket = 0; bucket < 100; bucket++) {
            AcidRainData data = new AcidRainData();
            helper.assertFalse(started(data.rollForDay(29, 1, new PercentileRandom(0))), "Day 29 must not roll or start acid rain");
            helper.assertTrue(data.nextChance() == 5 && data.lastRollDay() == -1, "Before day 30 no probability may be consumed");
            if (started(data.rollForDay(30, 1, new PercentileRandom(bucket)))) { starts++; }
        }
        helper.assertTrue(starts == 5, "Day 30 must select exactly five percentile buckets");
        helper.succeed();
    }

    private static void progression(GameTestHelper helper) {
        AcidRainData data = new AcidRainData();
        helper.assertFalse(started(data.rollForDay(30, 1, new PercentileRandom(99))), "The first day must be able to miss");
        helper.assertTrue(data.nextChance() == 10, "The next day after a miss must have ten percent chance");
        helper.assertFalse(started(data.rollForDay(30, 2, new PercentileRandom(0))), "The same day must not get another roll");
        helper.assertFalse(started(data.rollForDay(31, 24001, new PercentileRandom(99))), "The second day must be able to miss");
        helper.assertTrue(data.nextChance() == 15, "Two missed days must produce fifteen percent chance");
        helper.assertTrue(started(data.rollForDay(32, 48001, new PercentileRandom(14))), "The fifteenth bucket must now trigger rain");
        helper.assertTrue(data.nextChance() == 5, "An acid-rain day must reset the next chance to five percent");
        helper.assertFalse(started(data.rollForDay(33, 72001, new PercentileRandom(5))), "The sixth bucket after a reset must miss");
        helper.succeed();
    }

    private static void cap(GameTestHelper helper) {
        AcidRainData data = new AcidRainData();
        for (long day = 30; day < 49; day++) {
            helper.assertFalse(started(data.rollForDay(day, (day - 30) * 24000 + 1, new PercentileRandom(99))), "The last bucket must miss below 100 percent");
            helper.assertTrue(data.nextChance() <= 100, "Probability must never exceed 100 percent");
        }
        helper.assertTrue(data.nextChance() == 100, "Nineteen missed days must reach 100 percent");
        helper.assertTrue(started(data.rollForDay(49, 19 * 24000 + 1, new PercentileRandom(99))), "The twentieth day must trigger even for the last bucket");
        helper.assertTrue(data.nextChance() == 5, "Guaranteed rain must also reset probability");
        helper.succeed();
    }

    private static AcidRainData saved(AcidRainData data) {
        var tag = AcidRainData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        return AcidRainData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static void persistence(GameTestHelper helper) {
        AcidRainData missed = new AcidRainData();
        missed.rollForDay(30, 10, new PercentileRandom(99));
        AcidRainData loaded = saved(missed);
        helper.assertTrue(loaded.nextChance() == 10 && loaded.lastRollDay() == 30, "Missed-day probability must survive the actual NBT codec");
        helper.assertFalse(started(loaded.rollForDay(30, 100, new PercentileRandom(0))), "Reloading cannot reroll a failed day");
        helper.assertTrue(started(loaded.rollForDay(31, 24010, new PercentileRandom(0))), "The next day must use its saved probability");
        loaded = saved(loaded);
        helper.assertTrue(loaded.endsAt() == 24010 + 12000 && loaded.isActive(24011), "A loaded storm must keep its original deadline");
        helper.assertFalse(started(loaded.rollForDay(31, loaded.endsAt() + 1, new PercentileRandom(0))), "Ending or loading the storm cannot repeat the day's roll");
        helper.assertTrue(loaded.nextChance() == 5, "The successful-day reset must survive save/load");
        helper.succeed();
    }

    private static void dayTransitions(GameTestHelper helper) {
        AcidRainData skipped = new AcidRainData();
        skipped.rollForDay(30, 1, new PercentileRandom(99));
        helper.assertTrue(started(skipped.rollForDay(33, 72001, new PercentileRandom(19))), "Two skipped dry days must bring day 33 to twenty percent");
        helper.assertFalse(started(skipped.rollForDay(32, 72002, new PercentileRandom(0))), "Moving the day backwards must not repeat a roll");
        AcidRainData ongoing = new AcidRainData();
        ongoing.rollForDay(30, 1, new PercentileRandom(0));
        helper.assertFalse(started(ongoing.rollForDay(31, 100, new PercentileRandom(0))), "An ongoing storm already makes the new day an acid-rain day");
        helper.assertTrue(ongoing.lastRollDay() == 31 && ongoing.nextChance() == 5 && ongoing.endsAt() == 12001,
                "Crossing a day must record rain without extending the storm or increasing its chance");
        helper.succeed();
    }

    private static final class WeatherSnapshot implements AutoCloseable {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final int clearTime, rainTime, thunderTime;
        private final boolean raining, thundering, advance;
        private final float rainLevel;

        private WeatherSnapshot(GameTestHelper helper) {
            this.level = helper.getLevel();
            this.server = this.level.getServer();
            var weather = this.server.getWeatherData();
            this.clearTime = weather.getClearWeatherTime(); this.rainTime = weather.getRainTime(); this.thunderTime = weather.getThunderTime();
            this.raining = weather.isRaining(); this.thundering = weather.isThundering();
            this.advance = this.server.getGameRules().get(GameRules.ADVANCE_WEATHER);
            this.rainLevel = this.level.getRainLevel(1.0F);
        }

        @Override
        public void close() {
            var weather = this.server.getWeatherData();
            weather.setClearWeatherTime(this.clearTime); weather.setRainTime(this.rainTime); weather.setThunderTime(this.thunderTime);
            weather.setRaining(this.raining); weather.setThundering(this.thundering);
            this.level.setRainLevel(this.rainLevel);
            this.server.getGameRules().set(GameRules.ADVANCE_WEATHER, this.advance, this.server);
        }
    }

    private static void weatherLifecycle(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            MinecraftServer server = helper.getLevel().getServer();
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, true, server);
            server.setWeatherParameters(10000, 0, false, false);
            AcidRainData data = new AcidRainData();
            AcidRain.tick(server, data, 29, 1001, new PercentileRandom(0));
            helper.assertFalse(server.getWeatherData().isRaining(), "Day 29 must not change clear weather");
            AcidRain.tick(server, data, 30, 1001, new PercentileRandom(0));
            helper.assertTrue(server.getWeatherData().isRaining() && !server.getWeatherData().isThundering(), "A winning daily roll must create native rain");
            helper.assertTrue(data.endsAt() == 13001 && server.getWeatherData().getRainTime() == 12000, "The storm must last ten minutes");
            AcidRain.tick(server, data, 30, 2001, new PercentileRandom(0));
            helper.assertTrue(data.endsAt() == 13001, "Repeated ticks must not extend the rain");
            AcidRain.tick(server, data, 30, 13001, new PercentileRandom(0));
            helper.assertFalse(data.hasStorm() || server.getWeatherData().isRaining(), "At the deadline the acid rain must end without rerolling");
        }
        helper.succeed();
    }

    private static void weatherControl(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            MinecraftServer server = helper.getLevel().getServer();
            server.setWeatherParameters(10000, 0, false, false);
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            AcidRainData data = new AcidRainData();
            AcidRain.tick(server, data, 30, 1001, new PercentileRandom(0));
            helper.assertTrue(data.lastRollDay() == -1 && !server.getWeatherData().isRaining(), "Disabling weather cycling must prevent new storms");
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, true, server);
            AcidRain.tick(server, data, 30, 1001, new PercentileRandom(0));
            server.setWeatherParameters(10000, 0, false, false);
            AcidRain.tick(server, data, 30, 1002, new PercentileRandom(0));
            helper.assertFalse(data.hasStorm() || server.getWeatherData().isRaining(), "Clearing weather must stop acid rain without restarting it");
        }
        helper.succeed();
    }

    private static void rain(GameTestHelper helper) {
        helper.getLevel().getServer().setWeatherParameters(0, 12000, true, false);
        helper.getLevel().setRainLevel(1.0F);
        for (int x = 0; x < 10; x++) { for (int z = 0; z < 10; z++) { helper.setBlock(x, 0, z, Blocks.STONE); } }
    }

    private static Mob mob(GameTestHelper helper, EntityType<? extends Mob> type) {
        Mob mob = type.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
        mob.setPos(helper.absoluteVec(new Vec3(3.5, 1.0, 3.5)));
        mob.setNoAi(true); mob.setNoGravity(true); mob.setPersistenceRequired();
        MobMounts.setReserved(mob, true);
        helper.getLevel().addFreshEntity(mob);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        mob.setHealth(100.0F);
        return mob;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob> external(boolean undead) {
        return (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.fromNamespaceAndPath("undeadinvasion_combat_fixture", undead ? "external_undead" : "external_animal"));
    }

    private static void damage(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            rain(helper);
            List<LivingEntity> entities = new ArrayList<>();
            for (EntityType<? extends Mob> type : List.of(EntityType.VILLAGER, EntityType.IRON_GOLEM, EntityType.COW, external(false))) {
                entities.add(mob(helper, type));
            }
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 3.5)));
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0); player.setHealth(100.0F);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
            entities.add(player);
            for (LivingEntity entity : entities) {
                helper.assertTrue(AcidRain.damageExposedEntity(helper.getLevel(), entity), "An exposed non-undead creature must take acid-rain damage");
                helper.assertTrue(entity.getHealth() == 99.0F, "One pulse must deal exactly one point, including through armor");
                helper.assertTrue(entity.getLastDamageSource().is(AcidRain.DAMAGE_TYPE), "Damage must use the registered acid-rain type");
            }
        }
        helper.succeed();
    }

    private static void undead(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            rain(helper);
            // 特殊降雨作用于所有生物：亡灵不再豁免（与硫磺雨「所有生物」的设定一致）。
            for (EntityType<? extends Mob> type : List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.DROWNED,
                    EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER_SKELETON, external(true))) {
                Mob mob = mob(helper, type);
                helper.assertTrue(AcidRain.damageExposedEntity(helper.getLevel(), mob),
                        "Undead creatures must now take acid-rain damage too: " + type);
                helper.assertTrue(mob.getHealth() == 99.0F, "Undead must take exactly one point: " + type);
            }
        }
        helper.succeed();
    }

    private static void cover(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            rain(helper);
            Mob mob = mob(helper, EntityType.VILLAGER);
            for (int x = 2; x <= 4; x++) { for (int z = 2; z <= 4; z++) { helper.setBlock(x, 4, z, Blocks.STONE); } }
            helper.assertFalse(AcidRain.damageExposedEntity(helper.getLevel(), mob), "A roof must protect the creature from actual rain");
            for (int x = 2; x <= 4; x++) { for (int z = 2; z <= 4; z++) { helper.setBlock(x, 4, z, Blocks.AIR); } }
            helper.assertTrue(AcidRain.damageExposedEntity(helper.getLevel(), mob), "Removing the roof must expose the same creature to rain");
            for (var dimension : List.of(Level.NETHER, Level.END)) {
                ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
                Mob protectedMob = EntityType.VILLAGER.create(level, EntitySpawnReason.STRUCTURE);
                helper.assertFalse(AcidRain.damageExposedEntity(level, protectedMob), "Dimensions without rain must not apply acid damage");
            }
        }
        helper.succeed();
    }

    private static void ordinaryRain(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            rain(helper);
            Mob mob = mob(helper, EntityType.VILLAGER);
            MinecraftServer server = helper.getLevel().getServer();
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            AcidRain.tick(server, new AcidRainData(), 45, 1000, new PercentileRandom(99));
            helper.assertTrue(mob.getHealth() == 100.0F && server.getWeatherData().isRaining(), "Ordinary rain must remain harmless and keep raining");
        }
        helper.succeed();
    }

    private static void cadence(GameTestHelper helper) {
        WeatherSnapshot snapshot = new WeatherSnapshot(helper);
        var server = helper.getLevel().getServer();
        var days = DayCounterData.get(server);
        long previousDay = DayCounterData.dayOf(helper.getLevel());
        days.setDay(30);
        rain(helper);
        Mob mob = mob(helper, EntityType.VILLAGER);
        AcidRainData data = AcidRainData.get(server);
        helper.assertTrue(started(data.rollForDay(30, helper.getLevel().getGameTime(), new PercentileRandom(0))), "The fixture must start its real persisted storm");
        List<Long> hits = new ArrayList<>();
        List<Float> amounts = new ArrayList<>();
        float[] health = {100.0F};
        helper.onEachTick(() -> {
            if (mob.getHealth() < health[0]) {
                hits.add(helper.getLevel().getGameTime()); amounts.add(health[0] - mob.getHealth()); health[0] = mob.getHealth();
            }
        });
        helper.runAfterDelay(65, () -> {
            try {
                helper.assertTrue(hits.size() >= 3 && hits.size() <= 4, "65 actual server ticks must produce three or four one-second damage pulses");
                for (int index = 0; index < hits.size(); index++) {
                    helper.assertTrue(amounts.get(index) == 1.0F, "Each real server pulse must deal one point of damage");
                    if (index > 0) { helper.assertTrue(hits.get(index) - hits.get(index - 1) == 20L, "Damage pulses must be twenty game ticks apart"); }
                }
                helper.succeed();
            } finally {
                data.stop(); days.setDay(previousDay); snapshot.close();
            }
        });
    }

    /**
     * 管理指令：在天数低于 START_DAY 时也必须能立刻下雨，且不会被下一次 tick 的天数门槛掐掉。
     */
    private static void commandStart(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            MinecraftServer server = helper.getLevel().getServer();
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            server.setWeatherParameters(10000, 0, false, false);
            AcidRainData data = new AcidRainData();
            long gameTime = helper.getLevel().getGameTime();

            AcidRain.start(server, data, 1, 20 * 30);
            helper.assertTrue(data.hasStorm() && data.isForced(), "The command must force a storm regardless of the day");
            helper.assertTrue(data.endsAt() == gameTime + 600, "The command must honour the requested duration");
            helper.assertTrue(server.getWeatherData().isRaining() && !server.getWeatherData().isThundering(),
                    "The command must create native rain immediately");
            helper.assertTrue(server.getWeatherData().getRainTime() == 600, "Native rain must last the requested duration");
            helper.assertTrue(AcidRain.remainingTicks(data, gameTime, true) == 600, "Query must report the remaining ticks");

            // 关键回归点：day 1 < START_DAY，旧逻辑会在这一 tick 立刻把雨掐掉。
            AcidRain.tick(server, data, 1, gameTime + 1, new PercentileRandom(0));
            helper.assertTrue(data.isActive(gameTime + 2) && server.getWeatherData().isRaining(),
                    "A command-forced storm must survive a tick below the start day");

            // 到达截止时间后正常结束，且 forced 标记被清除。
            AcidRain.tick(server, data, 1, data.endsAt(), new PercentileRandom(0));
            helper.assertFalse(data.hasStorm() || data.isForced() || server.getWeatherData().isRaining(),
                    "The forced storm must end at its deadline and clear the forced flag");
        }
        helper.succeed();
    }

    /** 管理指令：stop 立刻停雨、放晴，重复调用返回 false。 */
    private static void commandStop(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            MinecraftServer server = helper.getLevel().getServer();
            server.setWeatherParameters(10000, 0, false, false);
            AcidRainData data = new AcidRainData();

            helper.assertFalse(AcidRain.stop(server, data), "Stopping with no storm must report false");
            AcidRain.start(server, data, 1, 20 * 30);
            helper.assertTrue(AcidRain.stop(server, data), "Stopping an active storm must report true");
            helper.assertFalse(data.hasStorm() || data.isForced(), "Stopping must clear the persisted storm state");
            helper.assertFalse(server.getWeatherData().isRaining(), "Stopping must clear native weather");
            helper.assertTrue(AcidRain.remainingTicks(data, helper.getLevel().getGameTime(), false) == 0,
                    "Query must report no remaining rain after stop");
        }
        helper.succeed();
    }

    /**
     * 酸雨是无来源环境伤害：不击退、不产生无敌帧，且即便实体身上已有来自其它伤害的无敌帧，
     * 每个脉冲也必须精确扣 1 点血。
     */
    private static void environmentalDamage(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            rain(helper);
            Mob mob = mob(helper, EntityType.VILLAGER);
            ServerLevel level = helper.getLevel();

            // 残留的无敌帧（例如刚被怪物打过）不应吞掉酸雨脉冲。
            mob.invulnerableTime = 20;
            Vec3 before = mob.getDeltaMovement();
            helper.assertTrue(AcidRain.damageExposedEntity(level, mob), "Acid rain must still land during existing invulnerability ticks");
            helper.assertTrue(mob.getHealth() == 99.0F, "The first pulse must deal exactly one point despite prior invulnerability");
            helper.assertTrue(mob.getDeltaMovement().equals(before), "Environmental damage must not knock the entity back");

            // 连续第二个脉冲：酸雨只留下最小的 1 刻无敌帧，下一脉冲（20 刻后）不会被自己挡掉。
            helper.assertTrue(mob.invulnerableTime <= 1, "Acid rain must leave at most one invulnerability tick");
            helper.assertTrue(AcidRain.damageExposedEntity(level, mob), "A second pulse immediately after the first must land");
            helper.assertTrue(mob.getHealth() == 98.0F, "Every pulse must deal exactly one point, no more and no less");
            helper.assertTrue(mob.getLastDamageSource().is(AcidRain.DAMAGE_TYPE), "Damage must keep the acid-rain source");
        }
        helper.succeed();
    }

    /** 硫磺雨点燃所有暴露生物（含亡灵），且不造成酸雨类型的直接伤害。 */
    private static void sulfurIgnite(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            rain(helper);
            ServerLevel level = helper.getLevel();
            for (EntityType<? extends Mob> type : List.of(EntityType.VILLAGER, EntityType.ZOMBIE, EntityType.SKELETON)) {
                Mob target = mob(helper, type);
                float before = target.getHealth();
                helper.assertTrue(AcidRain.affectExposedEntity(level, target, StormType.SULFUR),
                        "Sulfur rain must ignite exposed creatures: " + type);
                helper.assertTrue(target.getRemainingFireTicks() > 0, "The creature must be on fire: " + type);
                helper.assertTrue(target.getHealth() == before,
                        "Sulfur rain itself must not apply direct acid damage: " + type);
            }
        }
        helper.succeed();
    }

    /** 两种雨互斥：开硫磺雨会覆盖酸雨，反之亦然，query/activeType 始终只反映一种。 */
    private static void mutualExclusion(GameTestHelper helper) {
        try (var snapshot = new WeatherSnapshot(helper)) {
            MinecraftServer server = helper.getLevel().getServer();
            server.setWeatherParameters(10000, 0, false, false);
            AcidRainData data = new AcidRainData();

            AcidRain.start(server, data, 1, StormType.ACID, 20 * 30);
            helper.assertTrue(data.type() == StormType.ACID, "Acid rain must be active");
            AcidRain.start(server, data, 1, StormType.SULFUR, 20 * 30);
            helper.assertTrue(data.type() == StormType.SULFUR, "Starting sulfur rain must replace acid rain");
            helper.assertTrue(AcidRain.activeType(data, helper.getLevel().getGameTime(), true) == StormType.SULFUR,
                    "Only one storm type may be active at a time");
            AcidRain.start(server, data, 1, StormType.ACID, 20 * 30);
            helper.assertTrue(data.type() == StormType.ACID, "Starting acid rain must replace sulfur rain");
            AcidRain.stop(server, data);
            helper.assertTrue(data.type() == StormType.NONE, "Stopping must clear the storm type");
        }
        helper.succeed();
    }

    /** 自动判定按 SULFUR_SPLIT 分流，且类型随存档持久化。 */
    private static void sulfurRoll(GameTestHelper helper) {
        AcidRainData acid = new AcidRainData();
        helper.assertTrue(acid.rollForDay(30, 1, new PercentileRandom(0, 99)) == StormType.ACID,
                "A high split bucket must roll acid rain");
        AcidRainData sulfur = new AcidRainData();
        helper.assertTrue(sulfur.rollForDay(30, 1, new PercentileRandom(0, 0)) == StormType.SULFUR,
                "A low split bucket must roll sulfur rain");
        helper.assertTrue(sulfur.type() == StormType.SULFUR, "The rolled type must be stored immediately");
        AcidRainData loaded = saved(sulfur);
        helper.assertTrue(loaded.type() == StormType.SULFUR && loaded.isActive(2),
                "Sulfur-rain type must survive the actual NBT codec");
        helper.succeed();
    }

    private static void sync(GameTestHelper helper) {
        for (StormType type : StormType.values()) {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                AcidRainPayload.STREAM_CODEC.encode(buffer, new AcidRainPayload(type));
                helper.assertTrue(AcidRainPayload.STREAM_CODEC.decode(buffer).storm() == type,
                        "Storm type must survive its actual network codec: " + type);
            } finally { buffer.release(); }
        }
        ClientAcidRain.set(StormType.ACID);
        helper.assertTrue(ClientAcidRain.tint(0x7FFFFFFF) == 0x7F64FF40, "Green acid-rain tint must retain rain transparency");
        helper.assertTrue(ClientAcidRain.tint(0x00FFFFFF) == 0x0064FF40, "Transparent rain must stay transparent");
        ClientAcidRain.set(StormType.SULFUR);
        helper.assertTrue(ClientAcidRain.tint(0x7FFFFFFF) == 0x7FFFB800,
                "Sulfur rain must use the golden yellow #FFB800");
        StormType sulfur = ClientAcidRain.get();
        helper.assertTrue(sulfur.red() == 1.0F && sulfur.green() == 184.0F / 255.0F && sulfur.blue() == 0.0F,
                "Splash particle colors must match the golden sulfur yellow");
        // 回归保护：硫磺雨必须与酸雨明显区分，且 G 明显低于 R、B 归零，否则会泛绿。
        helper.assertTrue(sulfur.color() != StormType.ACID.color(), "Acid and sulfur rain colors must differ");
        helper.assertTrue(sulfur.green() < sulfur.red() - 0.2F && sulfur.blue() == 0.0F,
                "Sulfur rain must not look green: G well below R and zero blue");
        helper.assertTrue(sulfur.fogBlue() == 0.0F && sulfur.fogRed() > sulfur.fogGreen(),
                "Sulfur fog must be golden too, never greenish");
        ClientAcidRain.set(StormType.NONE);
        helper.succeed();
    }

    /**
     * 固定的百分位随机源：第一次调用（每日概率）返回 {@code bucket}，
     * 第二次调用（酸雨/硫磺雨分流）返回 {@code split}（默认 0 → 酸雨），
     * 让原有概率断言在引入第二种雨之后仍然成立。
     */
    private static final class PercentileRandom extends LegacyRandomSource {
        private final int bucket;
        private final int split;
        private int calls;

        private PercentileRandom(int bucket) { this(bucket, 0); }
        private PercentileRandom(int bucket, int split) {
            super(0L);
            this.bucket = bucket;
            this.split = split;
        }

        @Override
        public int nextInt(int bound) {
            int value = this.calls++ == 0 ? this.bucket : this.split;
            return value % bound;
        }
    }
}
