package override_mirage.undeadinvasion.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import override_mirage.undeadinvasion.UndeadInvasion;

/**
 * 模组自己的天数计数，与「原版游戏时间」彻底解耦。
 *
 * <p>计数从第 0 天开始（与原版一致），只由世界真实流逝的游戏刻（{@code Level#getGameTime()}）推动：
 * 每 24000 刻 +1 天。
 * 原版时间值（{@code WorldClock}）被 {@code /time set}、{@code /time add} 或其它模组整体改写时，
 * 游戏刻不会随之跳变，因此本计数不会被改成对方的值，只是继续按真实流逝累加。
 *
 * <p>睡觉是玩家主动结束一个夜晚，按「过去一天」处理：天数 +1，并把当天的计数清零以对齐清晨。
 *
 * <p>数据存放在主世界存档的 {@code data/undeadinvasion/day_counter.dat} 中。
 * 旧存档首次装载时，以世界年龄（游戏刻总量 / 24000）推算初始天数，之后完全独立。
 */
public final class DayCounterData extends SavedData {
    /** 一个完整昼夜的刻数（20 分钟）。 */
    public static final long TICKS_PER_DAY = 24000L;

    private static final Codec<DayCounterData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("day", 0L).forGetter(DayCounterData::getDay),
            Codec.LONG.optionalFieldOf("ticks_into_day", 0L).forGetter(DayCounterData::getTicksIntoDay),
            Codec.LONG.optionalFieldOf("last_game_time", -1L).forGetter(DayCounterData::getLastGameTime)
    ).apply(instance, (day, ticks, lastGameTime) -> new DayCounterData(day, ticks, lastGameTime)));

    public static final SavedDataType<DayCounterData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(UndeadInvasion.MOD_ID, "day_counter"),
            DayCounterData::new,
            CODEC);

    private long day;
    private long ticksIntoDay;
    private long lastGameTime;

    private DayCounterData() {
        this(0L, 0L, -1L);
    }

    private DayCounterData(long day, long ticksIntoDay, long lastGameTime) {
        this.day = day;
        this.ticksIntoDay = ticksIntoDay;
        this.lastGameTime = lastGameTime;
    }

    public static DayCounterData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * @return 该维度所属存档的当前天数（服务端权威值）
     */
    public static long dayOf(ServerLevel level) {
        MinecraftServer server = level.getServer();
        DayCounterData data = get(server);
        data.ensureInitialized(server.overworld().getGameTime());
        return data.getDay();
    }

    /**
     * 旧存档首次装载时，用世界年龄推算起始天数；已初始化则不做任何事。
     */
    public void ensureInitialized(long gameTime) {
        if (this.lastGameTime >= 0L) {
            return;
        }
        this.day = Math.floorDiv(gameTime, TICKS_PER_DAY);
        this.ticksIntoDay = Math.floorMod(gameTime, TICKS_PER_DAY);
        this.lastGameTime = gameTime;
        this.setDirty();
    }

    /**
     * 按真实流逝的游戏刻推进计数。
     *
     * @return 天数是否发生变化（用于决定是否需要同步给客户端）
     */
    public boolean advance(long gameTime) {
        if (this.lastGameTime < 0L) {
            this.ensureInitialized(gameTime);
            return true;
        }

        long delta = gameTime - this.lastGameTime;
        if (delta < 0L) {
            // 存档回滚（崩溃恢复、世界被替换等）：重新对齐，不追溯也不停滞。
            this.lastGameTime = gameTime;
            this.setDirty();
            return false;
        }
        if (delta == 0L) {
            return false;
        }
        this.lastGameTime = gameTime;
        this.ticksIntoDay += delta;

        boolean dayChanged = false;
        while (this.ticksIntoDay >= TICKS_PER_DAY) {
            this.ticksIntoDay -= TICKS_PER_DAY;
            this.day++;
            dayChanged = true;
        }
        this.setDirty();
        return dayChanged;
    }

    /**
     * 玩家睡过一夜：计为一天，并对齐到清晨。
     *
     * @return 固定为 {@code true}，调用方据此立即同步
     */
    public boolean onSleep() {
        this.day++;
        this.ticksIntoDay = 0L;
        this.setDirty();
        return true;
    }

    /**
     * 直接指定天数（管理指令使用），下限为 1。
     *
     * <p>当天的部分进度保持不变，只改天数本身。
     */
    public void setDay(long day) {
        long clamped = Math.max(0L, day);
        if (clamped == this.day) {
            return;
        }
        this.day = clamped;
        this.setDirty();
    }

    /**
     * 在当前天数上累加（管理指令使用），结果下限为 1。
     */
    public void addDays(long delta) {
        this.setDay(this.day + delta);
    }

    public long getDay() {
        return this.day;
    }

    public long getTicksIntoDay() {
        return this.ticksIntoDay;
    }

    public long getLastGameTime() {
        return this.lastGameTime;
    }
}
