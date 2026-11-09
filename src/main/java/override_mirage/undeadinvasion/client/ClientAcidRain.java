package override_mirage.undeadinvasion.client;

import override_mirage.undeadinvasion.world.StormState;
import override_mirage.undeadinvasion.world.StormType;

/**
 * 客户端仅保存服务端下发的特殊降雨类型，雨柱染色时保留原有雨滴透明度。
 *
 * <p>类名沿用旧称，但实际状态是一个 {@link StormType} 枚举。
 */
public final class ClientAcidRain {
    private static volatile StormType active = StormType.NONE;

    private ClientAcidRain() {}

    public static StormType get() { return active; }
    public static boolean isActive() { return active.isActive(); }
    public static boolean is(StormType type) { return active == type; }
    public static void set(StormType value) {
        StormType resolved = value == null ? StormType.NONE : value;
        active = resolved;
        StormState.set(resolved);
    }

    /** 兼容旧调用：true → 酸雨，false → 无。 */
    public static void set(boolean acidActive) { set(acidActive ? StormType.ACID : StormType.NONE); }

    /** 把原版白色的 ARGB 染成当前风暴颜色，保留 alpha。 */
    public static int tint(int original) {
        return (original & 0xFF000000) | active.color();
    }
}
