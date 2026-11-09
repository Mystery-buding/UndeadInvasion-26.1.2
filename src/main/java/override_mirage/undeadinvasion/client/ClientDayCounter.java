package override_mirage.undeadinvasion.client;

/**
 * 客户端持有的天数快照，由服务端通过 {@code DaySyncPayload} 下发。
 *
 * <p>HUD 只读这里；不自行用世界时间推算，避免与原版时间被改写后的服务端数值不一致。
 */
public final class ClientDayCounter {
    private static volatile long day = 0L;

    private ClientDayCounter() {}

    public static long getDay() {
        return day;
    }

    public static void set(long value) {
        day = Math.max(0L, value);
    }
}
