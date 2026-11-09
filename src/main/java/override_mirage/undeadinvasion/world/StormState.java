package override_mirage.undeadinvasion.world;

/**
 * 进程内共享的「当前特殊降雨类型」。
 *
 * <p>{@link ClientAcidRain} 只存在于客户端，而 {@code Entity#isInRain} 的 mixin 是 common 的，
 * 专用服务端没有客户端类。这里放一个两端都可访问的静态状态：
 * <ul>
 *   <li>服务端：每个 tick 由 {@code AcidRain#tick} 写入权威值；</li>
 *   <li>客户端：收到 {@code AcidRainPayload} 时写入，用于渲染与灭火判定。</li>
 * </ul>
 *
 * <p>仅用于渲染/灭火这类表现层判定，权威数据仍以服务端存档 {@link AcidRainData} 为准。
 */
public final class StormState {
    private static volatile StormType current = StormType.NONE;

    private StormState() {}

    public static StormType get() { return current; }
    public static boolean is(StormType type) { return current == type; }
    public static void set(StormType type) { current = type == null ? StormType.NONE : type; }
}
