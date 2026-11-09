package override_mirage.undeadinvasion.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import override_mirage.undeadinvasion.UndeadInvasion;

/**
 * 特殊降雨的存档状态：每天只抽取一次，保存下次概率、当前风暴的结束时间与类型。
 *
 * <p>类名保留 {@code AcidRainData} 以兼容旧存档的 {@code data/undeadinvasion/acid_rain.dat}；
 * 旧存档没有 {@code type} 字段时默认按酸雨读取。
 */
public final class AcidRainData extends SavedData {
    public static final long START_DAY = 30;
    public static final int BASE_CHANCE = 5;
    public static final int DURATION = 20 * 60 * 10;
    /** 硫磺雨在抽中特殊降雨后所占的比例（百分比）。 */
    public static final int SULFUR_SPLIT = 40;
    public static final Codec<AcidRainData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("last_roll_day", -1L).forGetter(AcidRainData::lastRollDay),
            Codec.intRange(5, 100).optionalFieldOf("next_chance", BASE_CHANCE).forGetter(AcidRainData::nextChance),
            Codec.LONG.optionalFieldOf("ends_at", 0L).forGetter(AcidRainData::endsAt),
            Codec.BOOL.optionalFieldOf("forced", false).forGetter(AcidRainData::isForced),
            Codec.STRING.optionalFieldOf("type", StormType.ACID.name()).forGetter(d -> d.type.name())
    ).apply(instance, AcidRainData::new));
    public static final SavedDataType<AcidRainData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, "acid_rain"), AcidRainData::new, CODEC);

    private long lastRollDay;
    private int nextChance;
    private long endsAt;
    /** 是否由管理指令强制触发；强制风暴不受 {@link #START_DAY} 天数门槛约束。 */
    private boolean forced;
    private StormType type;

    public AcidRainData() {
        this(-1L, BASE_CHANCE, 0L, false, StormType.ACID.name());
    }

    private AcidRainData(long lastRollDay, int nextChance, long endsAt, boolean forced, String type) {
        this.lastRollDay = lastRollDay;
        this.nextChance = nextChance;
        this.endsAt = endsAt;
        this.forced = forced;
        StormType parsed;
        try {
            parsed = StormType.valueOf(type);
        } catch (IllegalArgumentException e) {
            parsed = StormType.ACID;
        }
        this.type = parsed;
    }

    public static AcidRainData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public long lastRollDay() { return this.lastRollDay; }
    public int nextChance() { return this.nextChance; }
    public long endsAt() { return this.endsAt; }
    public boolean isForced() { return this.forced; }
    /** 当前风暴类型；没有进行中风暴时返回 {@link StormType#NONE}。 */
    public StormType type() { return this.hasStorm() ? this.type : StormType.NONE; }
    public boolean hasStorm() { return this.endsAt > 0L; }
    public boolean isActive(long gameTime) { return this.endsAt > gameTime; }
    /** 当前生效的风暴类型（含时间判定）。 */
    public StormType activeType(long gameTime) {
        return this.isActive(gameTime) ? this.type : StormType.NONE;
    }

    /**
     * 管理指令：立刻强制开始一场指定类型的风暴，不受天数门槛与当日概率影响。
     *
     * <p>同时把当天记为已抽取，避免自动逻辑在同一天重复触发。
     *
     * @param durationTicks 风暴持续的刻数，须大于 0
     */
    public void start(long gameTime, long day, int durationTicks, StormType newType) {
        this.lastRollDay = Math.max(this.lastRollDay, day);
        this.nextChance = BASE_CHANCE;
        this.endsAt = gameTime + Math.max(1, durationTicks);
        this.forced = true;
        this.type = newType.isActive() ? newType : StormType.ACID;
        this.setDirty();
    }

    /** 使用默认时长 {@link #DURATION} 与酸雨类型的便捷重载。 */
    public void start(long gameTime, long day) {
        this.start(gameTime, day, DURATION, StormType.ACID);
    }

    /**
     * 每日一次的概率判定。抽中后按 {@link #SULFUR_SPLIT} 决定是酸雨还是硫磺雨。
     *
     * @return 抽中后决定的风暴类型，未抽中返回 {@link StormType#NONE}
     */
    public StormType rollForDay(long day, long gameTime, RandomSource random) {
        if (day < START_DAY || day <= this.lastRollDay) {
            return StormType.NONE;
        }
        // 跨天时若上一场风暴尚未结束，当天已经下过，无需再抽一次。
        if (this.isActive(gameTime)) {
            this.lastRollDay = day;
            this.nextChance = BASE_CHANCE;
            this.setDirty();
            return StormType.NONE;
        }
        if (this.lastRollDay >= START_DAY) {
            long skippedDays = Math.min(20L, day - this.lastRollDay - 1L);
            this.nextChance = Math.min(100, this.nextChance + (int) skippedDays * BASE_CHANCE);
        }
        this.lastRollDay = day;
        boolean starts = random.nextInt(100) < this.nextChance;
        this.nextChance = starts ? BASE_CHANCE : Math.min(100, this.nextChance + BASE_CHANCE);
        StormType rolledType = StormType.NONE;
        if (starts) {
            this.endsAt = gameTime + DURATION;
            rolledType = random.nextInt(100) < SULFUR_SPLIT ? StormType.SULFUR : StormType.ACID;
            this.type = rolledType;
            this.forced = false;
        }
        this.setDirty();
        return rolledType;
    }

    public void stop() {
        if (this.hasStorm() || this.forced) {
            this.endsAt = 0L;
            this.forced = false;
            this.setDirty();
        }
    }
}
